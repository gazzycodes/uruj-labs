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
import com.uruj.data.BleSettingsStore
import com.uruj.data.PairedStrap
import com.uruj.sensor.android.AndroidHealthConnectHrSource
import com.uruj.sensor.android.BleHrSource
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
    private val bleSource by lazy { BleHrSource(this) }
    private val bleSettings by lazy { BleSettingsStore(this) }
    private val profileStore by lazy { RiderProfileStore(this) }
    private val historyRepo by lazy { RideHistoryRepository(this) }
    private val weatherClient by lazy { WeatherClient() }
    private val elevationClient by lazy { ElevationClient() }
    private val prTracker by lazy { PrTracker(this) }
    private var tts: TtsAnnouncer? = null

    // v0.9.76 — auto-pause detector promoted to a field (was a recordLoop local)
    // so the manual PAUSE/RESUME control can reset() it: on a manual RESUME we
    // hand control back to auto-pause with a fresh window (no instant re-pause
    // flicker). reset() is also called at the top of each recordLoop so a reused
    // service instance starts every ride with a clean detector.
    private val autoPause = AutoPauseDetector()

    override fun onCreate() {
        super.onCreate()
        RideNotifications.ensureChannel(this)
        tts = TtsAnnouncer(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(resumeSessionId = null)
            ACTION_RESUME -> {
                val sid = intent.getStringExtra(EXTRA_RESUME_SESSION_ID)
                if (sid != null) startRecording(resumeSessionId = sid)
                else Log.w(TAG, "ACTION_RESUME without session id, ignoring")
            }
            ACTION_STOP -> stopRecording()
            ACTION_TOGGLE_PAUSE -> togglePause()
        }
        return START_NOT_STICKY
    }

    /**
     * v0.9.76 — flip the rider's MANUAL pause (HUD PAUSE/RESUME button).
     *
     * PAUSE: set both `manuallyPaused` and `isPaused` true immediately, so the
     * 1 Hz moving-time ticker freezes on its very next tick — independent of the
     * GPS sampling cadence (which throttles when stationary, exactly when the
     * rider stops). The collect loop then keeps `isPaused` true via
     * `manuallyPaused || auto`.
     *
     * RESUME: clear `manuallyPaused`; drop `isPaused` to false and `reset()` the
     * auto-pause detector so it re-evaluates from now with a fresh window (no
     * instant re-pause flicker). If the rider is actually still stopped,
     * auto-pause re-engages after its normal 5 s — correct.
     *
     * No-op when no ride is recording (the HUD only shows the button mid-ride,
     * but guard defensively against a stray intent).
     */
    private fun togglePause() {
        if (recordingJob?.isActive != true) return
        var nowManuallyPaused = false
        RideStateHolder.update { current ->
            if (!current.isRecording) return@update current
            nowManuallyPaused = !current.manuallyPaused
            current.copy(manuallyPaused = nowManuallyPaused, isPaused = nowManuallyPaused)
        }
        if (!nowManuallyPaused) autoPause.reset() // manual resume → hand back to auto, fresh window
        Log.d(TAG, "manual pause toggled → manuallyPaused=$nowManuallyPaused")
        // Refresh the notification immediately so the shade reflects PAUSED/REC
        // without waiting up to 1s for the next ticker rebuild. (The 1 Hz ticker
        // also rebuilds it; this is just for responsiveness.)
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(
                RideNotifications.NOTIFICATION_ID,
                RideNotifications.build(this, RideStateHolder.state.value),
            )
        }.onFailure { Log.w(TAG, "notification refresh on pause-toggle failed", it) }
    }

    private fun startRecording(resumeSessionId: String? = null) {
        if (recordingJob?.isActive == true) return

        // Acquire a partial wake lock so the CPU stays awake during the ride even when
        // the screen is off and the app is backgrounded. Without this, OxygenOS will
        // aggressively throttle / suspend our foreground service after backgrounding —
        // exactly what killed the 2026-05-12 ride after 30 km.
        acquireWakeLock()

        try {
            val ridesDir = File(getExternalFilesDir(null), "rides").apply { mkdirs() }
            // True ride resume (v0.3.8): if a resumeSessionId is provided, we
            // restore from the existing summary's accumulators instead of
            // starting from zero. NDJSON file already exists; FileWriter
            // append=true means new samples extend the existing stream.
            val sessionId = resumeSessionId ?: UUID.randomUUID().toString()
            val existingSummary = if (resumeSessionId != null) historyRepo.load(sessionId) else null
            val startedAtMs = existingSummary?.startedAtMs ?: System.currentTimeMillis()
            val samplesFile = File(ridesDir, "$sessionId.ndjson")

            // .active marker — proof that this session is being actively recorded.
            // Service writes on start, deletes on clean stop. If marker persists
            // when no service is alive, the process was killed mid-ride → cold-
            // start dialog offers Resume / End / Discard.
            activeMarkerFile(ridesDir, sessionId).createNewFile()

            val initialState = if (existingSummary != null) {
                // Resume: seed accumulators from the existing summary so the new
                // samples extend the totals rather than starting from zero.
                Log.d(TAG, "Resuming session $sessionId: ${existingSummary.totalDistanceMeters / 1000} km already logged")
                RideState(
                    isRecording = true,
                    sessionId = sessionId,
                    startedAtMs = startedAtMs,
                    totalDistanceMeters = existingSummary.totalDistanceMeters,
                    movingTimeMs = existingSummary.movingTimeMs,
                    totalElapsedMs = existingSummary.totalElapsedMs,
                    averagePowerWatts = existingSummary.averagePowerWatts,
                    maxPowerWatts = existingSummary.maxPowerWatts,
                    totalWorkKj = existingSummary.totalWorkKj,
                    totalElevGainMeters = existingSummary.totalElevGainMeters,
                    totalElevLossMeters = existingSummary.totalElevLossMeters,
                    maxSpeedMs = existingSummary.maxSpeedKph / 3.6f,
                    ftpWatts = existingSummary.ftpWatts,
                )
            } else {
                RideState(
                    isRecording = true,
                    sessionId = sessionId,
                    startedAtMs = startedAtMs,
                )
            }
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
            // v0.3.8: clean shutdown → delete the .active marker so cold-start
            // doesn't try to offer a resume for a finished ride.
            runCatching {
                val ridesDir = File(getExternalFilesDir(null), "rides")
                activeMarkerFile(ridesDir, snapshot.sessionId).delete()
            }.onFailure { Log.w(TAG, "active-marker delete failed", it) }
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
        // v0.8.5 — signal post-ride quiet window so HC consumers serve
        // cache for the next 30s while the user navigates HUD → Summary
        // → tabs. Bridges the post-ride navigation cascade until normal
        // cache TTLs cover.
        com.uruj.data.HcReadGuard.recordRideEnded()
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
        autoPause.reset() // fresh detector state for this ride (field, reused across rides)
        val powerEstimator = PowerEstimator(profile)
        val elevation = ElevationTracker()
        val power3s = RollingAverage(windowSeconds = 3)
        val power30s = RollingAverage(windowSeconds = 30)
        // 20-min sliding window for FTP auto-update — the standard 20-min FTP
        // test methodology says FTP ≈ 0.95 × best sustained 20-min average power.
        // Tracking max across the ride lets us auto-update profile.ftpWatts at
        // ride end without needing the rider to do a dedicated test.
        val power20min = RollingAverage(windowSeconds = 1200)
        // v0.4.4 fix: seed local accumulators from the holder. On a RESUME,
        // startRecording() has already populated RideStateHolder with prior
        // ride totals via the existing summary. The buggy original initialized
        // these locals to zero, then on the first GPS sample wrote the locals
        // back to the holder via .update — clobbering the seeded values.
        // 2026-05-17 ride: 55km actual → only 6km recorded after 2 resumes.
        // Each resume reset distance + work + max power + elev to ~0 and the
        // 30s checkpoint cemented that loss to disk. Seeding the locals now
        // makes resume genuinely additive instead of destructive.
        val seed = RideStateHolder.state.value
        var best20MinPowerWatts = seed.best20MinPowerWatts
        var maxHrBpmObserved = seed.maxHrBpmObserved
        elevation.seed(
            initialGainMeters = seed.totalElevGainMeters.toDouble(),
            initialLossMeters = seed.totalElevLossMeters.toDouble(),
        )

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
                        val savedAt = System.currentTimeMillis()
                        val saved = runCatching {
                            historyRepo.save(
                                StoredRideSummary.from(snap, savedAt),
                            )
                        }.onFailure { Log.w(TAG, "checkpoint save failed", it) }.isSuccess
                        if (saved) {
                            // v0.3.7: surface the checkpoint heartbeat on the HUD so
                            // the rider can see the service is alive even when the
                            // phone is backgrounded and the notification is hidden.
                            RideStateHolder.update { it.copy(lastCheckpointAtMs = savedAt) }
                        }
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
        // v0.8.0 — audio coach state. Karvonen zones + session intent looked up
        // once at ride start; coach is rate-limited internally.
        val karvonenZones = com.uruj.power.KarvonenZonesCalculator()
            .compute(profile.maxHrBpm, profile.restingHrBpm.coerceAtLeast(40))
        val sessionCoach = com.uruj.audio.SessionCoach()
        // Read session intent + observe changes for mid-ride overrides
        val sessionIntentStore = com.uruj.data.SessionIntentStore(this@RideRecorderService)
        var currentIntent = sessionIntentStore.current()
        RideStateHolder.update { it.copy(sessionIntent = currentIntent) }
        launch {
            sessionIntentStore.intent.collect { newIntent ->
                if (newIntent != currentIntent) {
                    currentIntent = newIntent
                    sessionCoach.onIntentChanged(System.currentTimeMillis())
                    RideStateHolder.update { it.copy(sessionIntent = newIntent) }
                    Log.d(TAG, "[coach] session intent changed → $newIntent")
                }
            }
        }
        // v0.4.4 fix continued — seed the running accumulators so resume extends
        // prior totals instead of restarting from zero. See note above.
        // For power-average reconstruction: powerSum + powerSampleCount aren't
        // persisted in StoredRideSummary, so we approximate from the average
        // × an effective sample count derived from movingTimeMs (power samples
        // arrive at ~1Hz when GPS-accurate-and-moving). This will be slightly
        // off (zero-power samples weren't counted in the original live sum) but
        // keeps the rolling average continuous across resume — far better than
        // zeroing it and re-averaging from scratch.
        var totalDistanceMeters = seed.totalDistanceMeters
        var totalWorkJoules = seed.totalWorkKj.toDouble() * 1000.0
        var maxPowerWatts = seed.maxPowerWatts
        var powerSampleCount = if (seed.averagePowerWatts > 0f) {
            (seed.movingTimeMs / 1000L).toInt().coerceAtLeast(1)
        } else 0
        var powerSum = seed.averagePowerWatts.toDouble() * powerSampleCount
        var lastPowerSampleMs: Long? = null
        // Don't re-announce km callouts the rider already heard pre-resume.
        var lastKmAnnounced = (seed.totalDistanceMeters / 1_000.0).toInt()

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
        // v0.5.1 — HR source priority registry. Both sources stream concurrently;
        // BLE chest strap (ECG-precision, ~1Hz, real-time) WINS over Health
        // Connect (batched, ~5s poll) whenever it has a sample within the
        // freshness window. If BLE drops mid-ride, HC takes over within ~10s.
        //
        //   BLE_FRESHNESS_MS = 10s — BLE samples older than this lose priority.
        //   On reconnect, BLE immediately resumes winning.
        launch {
            try {
                hrSource.samples().collectLatest { sample ->
                    val current = latestHr
                    val now = System.currentTimeMillis()
                    val bleFresh = current != null &&
                        current.source == HrSample.Source.BLE_CHEST_STRAP &&
                        (now - current.receivedAtMs) < BLE_FRESHNESS_MS
                    if (!bleFresh) {
                        // HC wins when BLE is absent or stale.
                        latestHr = sample
                    }
                    // else: keep BLE — ignore this HC sample
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { Log.w(TAG, "HC HR flow died", e) }
        }
        // BLE flow — only starts if a paired strap exists in DataStore. The
        // first ride after pairing uses the saved MAC for direct connect (no
        // scan). Auto-reconnects via BleHrSource's internal retry on drop.
        launch {
            // v0.5.2 — retry loop with exponential back-off. BleHrSource closes
            // its flow when the GATT disconnects; this loop catches the
            // completion, marks bleConnected=false on HUD, waits with growing
            // back-off (5s → 10s → 20s → 40s → 60s cap), and re-opens the flow.
            // Cancelled cleanly when the ride ends (recordingJob.cancel).
            val paired: PairedStrap? = bleSettings.current()
            if (paired == null) {
                Log.d(TAG, "[ble] no paired strap — skipping BLE HR source for this ride")
                return@launch
            }
            val seedLabel = paired.model ?: paired.name ?: paired.address
            RideStateHolder.update { it.copy(bleStrapName = seedLabel) }
            Log.d(TAG, "[ble] direct-connecting to paired strap ${paired.address} ($seedLabel)")
            bleSource.onPaired = { info ->
                scope.launch { runCatching { bleSettings.touchLastConnected() } }
                RideStateHolder.update { it.copy(
                    bleStrapName = info.model ?: info.name ?: paired.address,
                ) }
            }

            var retryDelayMs = 5_000L
            while (isActive) {
                try {
                    bleSource.samples(directAddress = paired.address).collect { sample ->
                        // BLE always wins — fresh by definition.
                        latestHr = sample
                        // v0.5.2 — push beat-by-beat live BPM directly to HUD
                        // (instead of waiting for the GPS-tick latestSample update).
                        // Also surface state + battery + contact for the STRAP row.
                        RideStateHolder.update { it.copy(
                            bleConnected = true,
                            bleLiveBpm = sample.bpm,
                            bleBatteryPct = bleSource.battery.value,
                            bleContactDetected = sample.contactDetected,
                        ) }
                        // v0.8.1 — feed Live state for HUD inline waveform +
                        // Live tab. RideRecorderService is the active BLE owner
                        // during a ride; BiometricService is paused.
                        LiveStateHolder.onBleNotification(
                            receivedAtMs = sample.receivedAtMs,
                            bpm = sample.bpm,
                            rrIntervalsMs = sample.rrIntervalsMs,
                        )
                        // Reset back-off as soon as a sample arrives — we're stable.
                        retryDelayMs = 5_000L
                    }
                    Log.w(TAG, "[ble] flow ended (disconnect) — reconnect in ${retryDelayMs}ms")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "[ble] flow errored — reconnect in ${retryDelayMs}ms", e)
                }
                // Mark disconnected on HUD so STRAP row turns red OFFLINE.
                RideStateHolder.update { it.copy(
                    bleConnected = false,
                    bleLiveBpm = null,
                ) }
                delay(retryDelayMs)
                // Exponential back-off, capped at 60s. Reset on next success.
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
            }
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
                val autoPaused = autoPause.observe(
                    timestampMs = location.timestampMs,
                    speedMs = effectiveSpeed,
                    accelG = accelG,
                )
                // v0.9.76 — EFFECTIVE pause = manual OR auto. A rider-initiated
                // PAUSE (HUD button) sticks until a manual RESUME — auto-resume
                // can't clear it. Auto-pause keeps working normally when not
                // manually paused. This single `isPaused` flows through every
                // existing exclusion below (distance, power, max-speed, sample
                // stamping) and the 1 Hz moving-time gate, so nothing else changes.
                val isPaused = RideStateHolder.state.value.manuallyPaused || autoPaused

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
                    // v0.5.1 — capture RR intervals + source provenance for HRV
                    // computation. Empty list when source is HC (Fit Band 3
                    // doesn't expose RR via Health Connect).
                    rrIntervalsMs = latestHr?.rrIntervalsMs ?: emptyList(),
                    hrSource = latestHr?.source?.name,
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

                // v0.8.0 — audio coach zone-discipline check. Live BLE HR
                // (sub-100ms) takes precedence; HC HR (batched) is the
                // fallback. Silent if no HR, EXPLORATORY intent, or no zones.
                if (karvonenZones != null && currentIntent.hasTarget()) {
                    val hrForCoach = RideStateHolder.state.value.bleLiveBpm
                        ?: latestHr?.bpm?.toInt()
                        ?: 0
                    val cue = sessionCoach.tick(
                        hrBpm = hrForCoach,
                        zones = karvonenZones,
                        intent = currentIntent,
                        nowMs = location.timestampMs,
                    )
                    if (!cue.isNullOrBlank()) {
                        Log.d(TAG, "[coach] $cue")
                        tts?.say(cue)
                    }
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
                        // Re-derive from the freshest manual flag inside the atomic
                        // update so a manual toggle landing between the observe()
                        // above and here is never clobbered (v0.9.76).
                        isPaused = current.manuallyPaused || autoPaused,
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
                        currentAltitudeMeters = elevSnapshot.altitudeMeters.toFloat(),
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
        /** BLE HR samples older than this lose priority to Health Connect. v0.5.1. */
        private const val BLE_FRESHNESS_MS = 10_000L
        const val ACTION_START = "com.uruj.action.START_RIDE"
        const val ACTION_STOP = "com.uruj.action.STOP_RIDE"
        /** Resume an interrupted ride. Must be paired with EXTRA_RESUME_SESSION_ID. */
        const val ACTION_RESUME = "com.uruj.action.RESUME_RIDE"
        /** v0.9.76 — toggle the rider's MANUAL pause from the HUD button. Distinct
         *  from [ACTION_RESUME] (crash-recovery session resume). Single toggle:
         *  flips [RideState.manuallyPaused]. */
        const val ACTION_TOGGLE_PAUSE = "com.uruj.action.TOGGLE_PAUSE"
        const val EXTRA_RESUME_SESSION_ID = "com.uruj.extra.RESUME_SESSION_ID"

        /** Marker file written on service start, deleted on clean stop. Cold-start
         *  scanner looks for these to detect rides that were killed mid-recording. */
        fun activeMarkerFile(ridesDir: File, sessionId: String): File =
            File(ridesDir, "$sessionId.active")
    }
}
