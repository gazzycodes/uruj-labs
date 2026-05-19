package com.uruj.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Audio coach. Phone in frame bag = you can't look at the HUD constantly, so
 * the app speaks. Every km milestone gets a callout; new PRs and milestone
 * events do too. Plays through phone speaker or any active Bluetooth audio
 * device.
 *
 * v0.9.15 — Samsung-style audio ducking. When URUJ speaks while music or a
 * podcast is playing, we request transient audio focus with the
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` flag. The OS tells the music player
 * to drop volume ~60% for the duration of our utterance, then we abandon
 * focus and music restores. Same mechanism Samsung Health, Google Maps,
 * Spotify-driven podcasts, and Strava use.
 *
 * Lifecycle: request focus → speak → on utterance done → abandon focus.
 * Tracked via [UtteranceProgressListener]. Each utterance gets a unique
 * id; we hold focus only as long as the utterance is in flight.
 */
class TtsAnnouncer(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * v0.9.15 — AudioFocusRequest (API 26+) cached so we abandon the same
     * request we issued. Pre-API-26 we use the legacy
     * requestAudioFocus(listener, stream, durationHint) signature.
     */
    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener { /* no-op; we own short-lived focus */ }
                .build()
        } else null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { /* focus already requested in say() */ }
                    override fun onDone(utteranceId: String?) { abandonAudioFocus() }
                    @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, errorCode)"))
                    override fun onError(utteranceId: String?) { abandonAudioFocus() }
                    override fun onError(utteranceId: String?, errorCode: Int) { abandonAudioFocus() }
                })
                ready = true
                Log.d(TAG, "TTS ready")
            } else {
                Log.w(TAG, "TTS init failed: $status")
            }
        }
    }

    /**
     * Speak a line. Requests transient audio focus (music ducks ~60% while
     * we speak; restores when our utterance completes). Safe to call when
     * TTS isn't yet ready — silent return.
     */
    fun say(text: String) {
        if (!ready) return
        if (text.isBlank()) return
        requestAudioFocus()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun announceKilometer(km: Int, avgKph: Float, avgWatts: Float) {
        say("$km kilometres. Average ${avgKph.toInt()} kilometres per hour. ${avgWatts.toInt()} watts.")
    }

    fun announcePersonalRecord(windowLabel: String, watts: Int) {
        say("New $windowLabel power record. $watts watts.")
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
        abandonAudioFocus()
    }

    /** v0.9.15 — request transient duck focus. Music apps duck during TTS. */
    private fun requestAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
        }.onFailure { Log.w(TAG, "[v0.9.15] requestAudioFocus failed", it) }
    }

    /** v0.9.15 — release focus so music restores its volume. */
    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }.onFailure { Log.w(TAG, "[v0.9.15] abandonAudioFocus failed", it) }
    }

    companion object {
        private const val TAG = "URUJ-TTS"
    }
}
