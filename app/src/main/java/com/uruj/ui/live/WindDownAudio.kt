package com.uruj.ui.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * v0.9.71 — calm audio for WIND-DOWN, entirely SYNTHESISED (no assets, no TTS,
 * no robotic voice). A soft ambient drone (two low sines a fifth apart + a faint
 * octave, with a slow tremolo) plus a gentle enveloped tone on inhale / exhale.
 *
 * Streamed through one AudioTrack on a background coroutine. A slowly-eased
 * master gain (fade in on open, fade out on mute / leave) + raised-cosine tone
 * attacks keep everything click-free. Soft by design — meant to disappear under
 * your breath, not demand attention.
 */
class WindDownAudio {
    private val sampleRate = 44100
    private var track: AudioTrack? = null
    private var scope: CoroutineScope? = null

    @Volatile private var enabled = true
    @Volatile private var toneFreq = 0.0
    @Volatile private var toneLen = 0
    @Volatile private var toneTrigger = 0L

    private var masterGain = 0.0
    private var lastTrigger = 0L
    private var toneAge = 0
    private var p0 = 0.0; private var p1 = 0.0; private var p2 = 0.0
    private var pTone = 0.0; private var lfo = 0.0

    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBuf * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track = t
        t.play()
        val s = CoroutineScope(Dispatchers.Default)
        scope = s
        s.launch {
            val frames = 1024
            val buf = ShortArray(frames)
            while (isActive) {
                for (i in 0 until frames) buf[i] = nextSample()
                t.write(buf, 0, frames)
            }
        }
    }

    private fun nextSample(): Short {
        val target = if (enabled) 1.0 else 0.0
        masterGain += (target - masterGain) * 0.00003 // ~1s eased fade, click-free

        // Ambient drone: root + fifth + faint octave, slow tremolo.
        p0 += 2 * PI * 98.0 / sampleRate
        p1 += 2 * PI * 146.83 / sampleRate
        p2 += 2 * PI * 293.66 / sampleRate
        lfo += 2 * PI * 0.06 / sampleRate
        val trem = 0.75 + 0.25 * sin(lfo)
        var v = (sin(p0) * 0.5 + sin(p1) * 0.30 + sin(p2) * 0.09) * trem * 0.20 * masterGain

        // Breath tone — restart when trigger changes; raised-cosine attack + exp decay.
        if (toneTrigger != lastTrigger) {
            lastTrigger = toneTrigger; toneAge = 0; pTone = 0.0
        }
        if (toneFreq > 0.0 && toneLen > 0 && toneAge < toneLen) {
            pTone += 2 * PI * toneFreq / sampleRate
            val tn = toneAge.toDouble() / toneLen
            val env = if (tn < 0.18) (0.5 - 0.5 * cos(PI * tn / 0.18)) else exp(-3.0 * (tn - 0.18))
            v += sin(pTone) * 0.16 * env * masterGain
            toneAge++
        }
        return (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
    }

    fun setEnabled(on: Boolean) { enabled = on }

    /** Soft cue at a phase change. Inhale = warmer/higher, exhale = lower/longer. */
    fun breathTone(inhale: Boolean) {
        toneFreq = if (inhale) 396.0 else 264.0
        toneLen = (sampleRate * if (inhale) 1.2 else 1.7).toInt()
        toneTrigger++
    }

    fun release() {
        scope?.cancel(); scope = null
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) {}
        track?.release(); track = null
    }
}
