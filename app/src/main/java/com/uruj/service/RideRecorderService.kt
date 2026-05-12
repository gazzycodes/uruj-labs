package com.uruj.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import com.uruj.audio.TtsAnnouncer
import com.uruj.data.RideHistoryRepository
import com.uruj.data.RiderProfileStore
import com.uruj.data.StoredRideSummary
import com.uruj.domain.PowerZone
import com.uruj.domain.RideSample
import com.uruj.domain.RiderProfile
import com.uruj.power.ElevationTracker
import com.uruj.power.PowerEstimator
import com.uruj.power.PrTracker
import com.uruj.weather.ElevationClient
import com.uruj.weather.WeatherClient
import com.uruj.weather.WeatherStatus
import com.uruj.weather.WindMath
import com.uruj.sensor.AccelerometerSample
import com.uruj.sensor.BarometerSample
import com.uruj.sensor.HrSample
import com.uruj.sensor.LocationSample
import com.uruj.sensor.android.AndroidHealthConnectHrSource
import com.uruj.sensor.android.FusedLocationSource
import com.uruj.sensor.android.LinearAccelSource
import com.uruj.sensor.android.PressureBarometerSource
import com.uruj.util.RollingAverage
import com.uruj.util.haversineMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.io.File
import java.util.UUID

class RideRecorderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val locationSource by lazy { FusedLocationSource(this) }
    private val barometerSource by lazy { PressureBarometerSource(this) }
    private val accelSource by lazy { LinearAccelSource(this) }
    private val hrSource by lazy { AndroidHealthConnectHrSource(this) }
    private val profileStore by lazy { RiderProfileStore(this) }
    private val historyRepo by lazy { RideHistoryRepository(this) }
    private val weatherClient by lazy { WeatherClient() }
    private val elevationClient by lazy { ElevationClient() }
    private val prTracker by lazy { PrTracker(this) }
    private var tts: TtsAnnouncer? = null

    override fun onCreate() {
        super.onCreate()
        RideNotifications.ensureChannel(this)
        tts = TtsAnnouncer(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recordingJob?.isActive == true) return

        // Acquire a partial wake lock so the CPU stays awake during the ride even when
        // the screen is off and the app is backgrounded. Without this, OxygenOS will
        // aggressively throttle / suspend our foreground service after backgrounding —
        // exactly what killed the 2026-05-12 ride after 30 km.
        acquireWakeLock()

        try {
            val sessionId = UUID.randomUUID().toString()
            val startedAtMs = System.currentTimeMillis()
            val ridesDir = File(getExternalFilesDir(null), "rides").apply { mkdirs() }
            val samplesFile = File(ridesDir, "$sessionId.ndjson")

            val initialState = RideState(
                isRecording = true,
                sessionId = sessionId,
                startedAtMs = startedAtMs,
            )
            RideStateHolder.update { initialState }

            startInForeground(initialState)
            Log.d(TAG, "startForeground succeeded for session $sessionId")

            recordingJob = scope.launch {
                try {
                    // Snapshot profile once at ride start. Edits via the profile screen
                    // during a ride won't take effect mid-ride — that's intentional.
                    val profile = profileStore.current()
                    RideStateHolder.update { it.copy(ftpWatts = profile.ftpWatts) }
                    recordLoop(samplesFile, startedAtMs, profile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "recordLoop crashed", e)
                } finally {
                    // Save is handled in stopRecording() — running here too would race with
                    // state-clearing and clobber the saved file. Just mark recording done.
                    RideStateHolder.update { it.copy(isRecording = false) }
                    stopSelf()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording failed", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    this@RideRecorderService,
                    "Couldn't start recording: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            RideStateHolder.reset()
            stopSelf()
        }
    }

    private fun stopRecording() {
        // CRITICAL: snapshot + save BEFORE anything resets state. The recording coroutine's
        // finally block can't be relied on for the save — it may run after completeRide()
        // has cleared sessionId, causing every save to clobber the same "unknown" file.
        val snapshot = RideStateHolder.state.value
        if (snapshot.sessionId != null) {
            runCatching {
                historyRepo.save(
                    StoredRideSummary.from(snapshot, System.currentTimeMillis()),
                )
                Log.d(TAG, "Saved ride summary: ${snapshot.sessionId}")
            }.onFailure { Log.w(TAG, "save summary failed", it) }
        }

        // Ride-end profile write-backs — both max HR and FTP auto-update from
        // the just-finished ride's observed performance. Monotonic increase
        // only: if observed exceeds stored, persist. Lower values don't shrink
        // the profile (sub-max effort doesn't mean the rider's ceiling dropped).
        // Runs on long-lived service scope so it survives recordingJob.cancel().
        scope.launch {
            runCatching {
                val current = profileStore.current()
                var updated = current
                var changeNote: String? = null

                if (snapshot.maxHrBpmObserved > current.maxHrBpm) {
                    updated = updated.copy(maxHrBpm = snapshot.maxHrBpmObserved)
                    changeNote = "max HR ${current.maxHrBpm} → ${snapshot.maxHrBpmObserved}"
                }

                // FTP from 20-min best: standard methodology is FTP ≈ 0.95 × best
                // 20-minute sustained average power. Only auto-update when our
                // observed FTP estimate exceeds the stored value. The 0.95
                // multiplier conservatively accounts for the gap between a
                // 20-min all-out test and a 60-min sustainable threshold.
                val observedFtp = (snapshot.best20MinPowerWatts * 0.95f).toInt()
                if (observedFtp > current.ftpWatts && observedFtp >= 50) {
                    updated = updated.copy(ftpWatts = observedFtp)
                    val ftpNote = "FTP ${current.ftpWatts}W → ${observedFtp}W"
                    changeNote = if (changeNote == null) ftpNote else "$changeNote, $ftpNote"
                }

                if (updated != current) {
                    profileStore.save(updated)
                    Log.d(TAG, "Profile auto-updated: $changeNote")
                }
            }.onFailure { Log.w(TAG, "profile write-back failed", it) }
        }

        // Persist PRs on the long-lived service scope (not the recordingJob) so it
        // survives the upcoming cancel() and finishes writing to DataStore.
        scope.launch {
            runCatching { prTracker.persist() }
                .onFailure { Log.w(TAG, "PR persist failed", it) }
                .onSuccess { Log.d(TAG, "PRs persisted") }
        }

        recordingJob?.cancel()
        recordingJob = null
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        RideStateHolder.completeRide()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "URUJ::RideRecording").apply {
            // 12-hour cap — way more than any realistic ride, but enforces release if
            // something goes wrong with cleanup. Always release explicitly on stop.
            acquire(12 * 60 * 60 * 1_000L)
        }
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
            if (wakeLock != null) Log.d(TAG, "Wake lock released")
        }
        wakeLock = null
    }

    private fun startInForeground(state: RideState) {
        val notification = RideNotifications.build(this, state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                RideNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(RideNotifications.NOTIFICATION_ID, notification)
        }
    }

    private suspend fun recordLoop(
        samplesFile: File,
        startedAtMs: Long,
        profile: RiderProfile,
    ) = supervisorScope {
        val recorder = NdjsonRideRecorder(samplesFile)
        val autoPause = AutoPauseDetector()
        val powerEstimator = PowerEstimator(profile)
        val elevation = ElevationTracker()
        val power3s = RollingAverage(windowSeconds = 3)
        val power30s = RollingAverage(windowSeconds = 30)
        // 20-min sliding window for FTP auto-update — the standard 20-min FTP
        // test methodology says FTP ≈ 0.95 × best sustained 20-min average power.
        // Tracking max across the ride lets us auto-update profile.ftpWatts at
        // ride end without needing the rider to do a dedicated test.
        val power20min = RollingAverage(windowSeconds = 1200)
        var best20MinPowerWatts = 0f
        var maxHrBpmObserved = 0

        runCatching { prTracker.load() }.onFailure { Log.w(TAG, "PR load failed", it) }

        // 30s checkpoint loop — writes the current summary to disk so a service kill
        // mid-ride doesn't lose more than half a minute of history data. The 2026-05-12
        // ride lost 30 km because no checkpoint existed; never again.
        launch {
            try {
                while (isActive) {
                    delay(30_000L)
                    val snap = RideStateHolder.state.value
                    if (snap.sessionId != null && snap.totalDistanceMeters > 0) {
                        runCatching {
                            historyRepo.save(
                                StoredRideSummary.from(snap, System.currentTimeMillis()),
                            )
                        }.onFailure { Log.w(TAG, "checkpoint save failed", it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "checkpoint loop ended", e)
            }
        }

        var latestBarometer: BarometerSample? = null
        var latestAccel: AccelerometerSample? = null
        var latestHr: HrSample? = null
        var latestDemElevation: Float? = null
        var totalDistanceMeters = 0.0
        var totalWorkJoules = 0.0
        var maxPowerWatts = 0f
        var powerSampleCount = 0
        var powerSum = 0.0
        var lastPowerSampleMs: Long? = null
        var lastKmAnnounced = 0

        // DEM elevation lookup — when no barometer is present this is our canonical
        // altitude source (what Strava uses for power estimation post-ride). Polls every
        // 30s with current lat/lon, cached by 0.001-degree grid client-side.
        launch {
            try {
                while (isActive) {
                    val sample = RideStateHolder.state.value.latestSample
                    if (sample != null && (sample.latitude != 0.0 || sample.longitude != 0.0)) {
                        val elev = elevationClient.elevationFor(sample.latitude, sample.longitude)
                        if (elev != null) latestDemElevation = elev
                    }
                    delay(30_000L)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "DEM elevation loop ended", e)
            }
        }

        // Weather: poll Open-Meteo. Wait politely for a usable GPS sample, then fetch
        // immediately and refresh every 10 min. State machine drives a visible HUD status
        // so the rider knows when next refresh happens / if a fetch failed.
        launch {
            try {
                val refreshInterval = 10 * 60 * 1_000L
                val retryInterval = 30 * 1_000L
                RideStateHolder.update { it.copy(weatherStatus = WeatherStatus.WaitingForGps) }
                while (isActive) {
                    val sample = RideStateHolder.state.value.latestSample
                    // Weather just needs ANY location — cell-tower / Wi-Fi positioning (100m
                    // accuracy or worse) is plenty since wind/temp don't change inside a
                    // kilometer. Bike metrics use a stricter GPS-quality gate elsewhere.
                    val hasAnyLocation = sample != null &&
                        (sample.latitude != 0.0 || sample.longitude != 0.0)

                    if (!hasAnyLocation || sample == null) {
                        RideStateHolder.update { it.copy(weatherStatus = WeatherStatus.WaitingForGps) }
                        delay(5_000L)
                        continue
                    }

                    RideStateHolder.update { it.copy(weatherStatus = WeatherStatus.Fetching) }
                    val w = weatherClient.fetch(sample.latitude, sample.longitude)
                    val now = System.currentTimeMillis()
                    if (w != null) {
                        Log.d(
                            TAG,
                            "Weather ${w.temperatureCelsius}°C, wind ${w.windSpeedMs}m/s from ${w.windDirectionDeg}°",
                        )
                        RideStateHolder.update {
                            it.copy(
                                weather = w,
                                weatherStatus = WeatherStatus.Ok(
                                    fetchedAtMs = now,
                                    nextFetchAtMs = now + refreshInterval,
                                ),
                            )
                        }
                        delay(refreshInterval)
                    } else {
                        Log.w(TAG, "Weather fetch returned null — retrying in 30s")
                        RideStateHolder.update {
                            it.copy(weatherStatus = WeatherStatus.Failed(retryAtMs = now + retryInterval))
                        }
                        delay(retryInterval)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "weather loop ended", e)
            }
        }

        // Side flows feed cached "latest" values. Each wrapped so one failure can't kill
        // the whole recording session — supervisorScope makes children independent, but
        // an uncaught exception in collectLatest would still cancel that specific child.
        // CancellationException is rethrown — it's structured-concurrency cleanup, not an error.
        launch {
            try {
                barometerSource.samples().collectLatest { latestBarometer = it }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { Log.w(TAG, "barometer flow died", e) }
        }
        launch {
            try {
                accelSource.samples().collectLatest { latestAccel = it }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { Log.w(TAG, "accel flow died", e) }
        }
        launch {
            try {
                hrSource.samples().collectLatest { latestHr = it }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { Log.w(TAG, "HR flow died", e) }
        }

        val notificationManager = getSystemService(NotificationManager::class.java)

        // 1 Hz wall-clock ticker — drives elapsed/moving time + notification refresh,
        // independent of GPS sampling cadence. FusedLocationProvider throttles updates
        // when the phone is stationary or indoors; without this ticker the HUD timer
        // appears frozen for seconds at a time.
        launch {
            Log.d(TAG, "1Hz ticker started")
            var lastTickMs = System.currentTimeMillis()
            var tickCount = 0
            while (isActive) {
                delay(1_000L)
                val nowMs = System.currentTimeMillis()
                val deltaMs = (nowMs - lastTickMs).coerceAtLeast(0L)
                lastTickMs = nowMs
                tickCount++
                RideStateHolder.update { current ->
                    if (current.startedAtMs == null) return@update current
                    current.copy(
                        totalElapsedMs = nowMs - current.startedAtMs,
                        movingTimeMs = if (current.isPaused) current.movingTimeMs
                        else current.movingTimeMs + deltaMs,
                    )
                }
                runCatching {
                    notificationManager.notify(
                        RideNotifications.NOTIFICATION_ID,
                        RideNotifications.build(this@RideRecorderService, RideStateHolder.state.value),
                    )
                }.onFailure { Log.w(TAG, "notification.notify failed at tick $tickCount", it) }
                if (tickCount % 10 == 0) Log.d(TAG, "ticker alive: $tickCount ticks elapsed")
            }
            Log.d(TAG, "1Hz ticker exited")
        }

        var previousLocation: LocationSample? = null

        try {
            locationSource.samples(intervalMillis = 1_000L).collect { location ->
                val accelG = latestAccel?.magnitudeG ?: 0f
                // GPS quality gate: only trust positions/speeds when accuracy is within
                // bike-grade tolerance. Indoors / urban canyon, FusedLocationProvider
                // falls back to cell/Wi-Fi which produces 30–500m noise — we refuse to
                // build a ride on top of that. 25m matches Wahoo's outdoor threshold.
                val gpsAccurate = location.horizontalAccuracyMeters in 0.1f..GPS_ACCURACY_THRESHOLD_M

                // Auto-pause uses GATED speed (0 when GPS is junk) — combined with the
                // accelerometer this means a stationary phone with bad GPS WILL pause.
                val effectiveSpeed = if (gpsAccurate) location.speedMetersPerSecond else 0f
                val isPaused = autoPause.observe(
                    timestampMs = location.timestampMs,
                    speedMs = effectiveSpeed,
                    accelG = accelG,
                )

                val prev = previousLocation
                var distanceMovedM = 0.0
                if (gpsAccurate && !isPaused && prev != null) {
                    val d = haversineMeters(
                        prev.latitude, prev.longitude,
                        location.latitude, location.longitude,
                    )
                    // Lower bound 1m: reject GPS jitter. Upper 100m/s = 360 kph: glitch.
                    if (d in 1.0..100.0) {
                        totalDistanceMeters += d
                        distanceMovedM = d
                    }
                }
                previousLocation = location

                val maxSpeed = maxOf(
                    RideStateHolder.state.value.maxSpeedMs,
                    if (gpsAccurate && !isPaused) location.speedMetersPerSecond else 0f,
                )

                // Elevation tracker with 3-source priority: barometer > DEM > GPS.
                // On phones without a barometer (OnePlus 7/7T, most non-flagships) the DEM
                // lookup from Open-Meteo gives Strava-grade accuracy.
                val elevSnapshot = elevation.update(
                    timestampMs = location.timestampMs,
                    pressureHpa = latestBarometer?.pressureHpa,
                    demElevationM = latestDemElevation,
                    gpsAltitudeM = location.altitudeMeters,
                    distanceMovedM = distanceMovedM,
                )

                // Estimated power: only when GPS is good AND we're moving AND not paused.
                // A junk GPS speed produces a junk wattage — gating here keeps the HUD
                // honest and prevents false PRs.
                val instantPower = if (isPaused || !gpsAccurate) 0f
                else powerEstimator.estimateWatts(
                    speedMs = location.speedMetersPerSecond,
                    gradeFraction = elevSnapshot.gradeFraction,
                    accelMs2 = 0f, // smoothed accel is small; defer until v2.5
                )
                val pow3s = power3s.add(location.timestampMs, instantPower)
                val pow30s = power30s.add(location.timestampMs, instantPower)
                val pow20min = power20min.add(location.timestampMs, instantPower)
                // Only count the 20-min average toward FTP estimation once the
                // window is full (≥1200s of samples). Earlier partial windows
                // would underestimate because they include warmup periods.
                if (power20min.isFull() && pow20min > best20MinPowerWatts) {
                    best20MinPowerWatts = pow20min
                }

                // Track the max HR sample seen so far in the ride. latestHr is
                // refreshed by the HR flow coroutine — when it ticks higher,
                // we record it for the ride-end profile write-back.
                latestHr?.bpm?.let { bpm ->
                    if (bpm in 35..250 && bpm > maxHrBpmObserved) {
                        maxHrBpmObserved = bpm
                    }
                }

                // Cumulative power stats. Integrate watts × dt to total work in joules.
                lastPowerSampleMs?.let { previousMs ->
                    val dtSec = ((location.timestampMs - previousMs) / 1000.0).coerceIn(0.0, 5.0)
                    totalWorkJoules += instantPower * dtSec
                }
                lastPowerSampleMs = location.timestampMs
                if (instantPower > 0f) {
                    powerSum += instantPower
                    powerSampleCount += 1
                    if (instantPower > maxPowerWatts) maxPowerWatts = instantPower
                }
                val avgPower = if (powerSampleCount > 0) (powerSum / powerSampleCount).toFloat() else 0f
                val zone = if (instantPower > 0f) PowerZone.forPower(pow3s, profile.ftpWatts) else null

                val hrAgeMs = latestHr?.let { System.currentTimeMillis() - it.measuredAtMs }
                val sample = RideSample(
                    timestampMs = location.timestampMs,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitudeMeters = location.altitudeMeters,
                    speedMetersPerSecond = location.speedMetersPerSecond,
                    horizontalAccuracyMeters = location.horizontalAccuracyMeters,
                    pressureHpa = latestBarometer?.pressureHpa,
                    accelMagnitudeG = latestAccel?.magnitudeG,
                    hrBpm = latestHr?.bpm,
                    hrAgeMs = hrAgeMs,
                    isPaused = isPaused,
                )
                recorder.append(sample)

                // Live PR detection — flash on HUD + speak via TTS.
                val newPrs = prTracker.observe(location.timestampMs, instantPower)
                val freshPr = newPrs.maxByOrNull { it.watts }
                if (freshPr != null) {
                    Log.d(TAG, "PR! ${freshPr.label}: ${freshPr.watts.toInt()}W")
                    tts?.announcePersonalRecord(freshPr.label, freshPr.watts.toInt())
                }

                // TTS km callout.
                val km = (totalDistanceMeters / 1_000).toInt()
                if (km > lastKmAnnounced && km > 0) {
                    lastKmAnnounced = km
                    tts?.announceKilometer(
                        km = km,
                        avgKph = RideStateHolder.state.value.averageSpeedMovingKph,
                        avgWatts = avgPower,
                    )
                }

                // Wind component: only when GPS is reliable AND we have weather + bike heading.
                val headwind: Float = run {
                    val w = RideStateHolder.state.value.weather
                    val heading = location.bearingDeg
                    if (gpsAccurate && w != null && heading != null &&
                        location.speedMetersPerSecond > 1.0f
                    ) {
                        WindMath.headwindComponentMs(heading, w.windDirectionDeg, w.windSpeedMs)
                    } else 0f
                }

                RideStateHolder.update { current ->
                    current.copy(
                        isPaused = isPaused,
                        latestSample = sample,
                        gpsAccurate = gpsAccurate,
                        gpsAccuracyMeters = location.horizontalAccuracyMeters,
                        totalDistanceMeters = totalDistanceMeters,
                        maxSpeedMs = maxSpeed,
                        instantPowerWatts = instantPower,
                        smoothedPower3sWatts = pow3s,
                        smoothedPower30sWatts = pow30s,
                        averagePowerWatts = avgPower,
                        maxPowerWatts = maxPowerWatts,
                        best20MinPowerWatts = best20MinPowerWatts,
                        maxHrBpmObserved = maxHrBpmObserved,
                        totalWorkKj = (totalWorkJoules / 1000.0).toFloat(),
                        currentZone = zone,
                        totalElevGainMeters = elevSnapshot.totalGainMeters.toFloat(),
                        totalElevLossMeters = elevSnapshot.totalLossMeters.toFloat(),
                        currentGradeFraction = elevSnapshot.gradeFraction,
                        vamMetersPerHour = elevSnapshot.vamMetersPerHour,
                        elevationSource = elevSnapshot.source,
                        headwindMs = headwind,
                        latestPr = freshPr ?: current.latestPr,
                        prAnnouncedAtMs = if (freshPr != null) System.currentTimeMillis() else current.prAnnouncedAtMs,
                    )
                }
            }
        } finally {
            recorder.close()
            // PR persist is handled in stopRecording() on a non-cancellable launch —
            // doing it here would get canceled with the rest of the coroutine tree
            // and your hard-earned 5-minute power record wouldn't make it to disk.
        }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "URUJService"
        // Anything worse than this gets discarded — outdoor cycling routinely sees
        // ±3–15m, urban canyons ±15–25m, indoors ±50m+. 25m is the upper bike-grade
        // bound (Wahoo Roam uses similar). Tune if your real rides show consistent
        // genuine motion getting rejected at the wrong threshold.
        private const val GPS_ACCURACY_THRESHOLD_M = 25f
        const val ACTION_START = "com.uruj.action.START_RIDE"
        const val ACTION_STOP = "com.uruj.action.STOP_RIDE"
    }
}
