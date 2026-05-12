package com.uruj.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Audio coach. Phone in frame bag = you can't look at the HUD constantly, so the app
 * speaks. Every km milestone gets a callout; new PRs and milestone events do too.
 * Plays through phone speaker or any active Bluetooth audio device.
 */
class TtsAnnouncer(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ready = true
                Log.d(TAG, "TTS ready")
            } else {
                Log.w(TAG, "TTS init failed: $status")
            }
        }
    }

    fun say(text: String) {
        if (!ready) return
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
    }

    companion object {
        private const val TAG = "URUJ-TTS"
    }
}
