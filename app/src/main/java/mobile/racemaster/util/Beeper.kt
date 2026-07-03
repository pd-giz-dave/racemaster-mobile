package mobile.racemaster.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Short audible confirmation that a split or bib entry was recorded. Generates a real single-
 * frequency sine tone (rather than relying on ToneGenerator's presets, which are either brief
 * UI "click" tones that ignore requested duration, or DTMF tones that are two simultaneous
 * frequencies mixed together and can sound like a doubled/warbling tone). 1kHz sits well below
 * the range age-related hearing loss usually affects first (typically >2-4kHz), so it should
 * stay clearly audible to the widest range of listeners. Uses the alarm stream (not
 * music/notification) since splits need to be heard outdoors over wind/crowd noise and that
 * stream isn't affected by media volume or silent/vibrate mode — the same reason alarm clocks
 * use it — and forces it to max volume so a quiet setting left over from something else can't
 * silently swallow it.
 */
class Beeper(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(toneSamples.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
        .apply { write(toneSamples, 0, toneSamples.size) }

    fun beep() {
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0,
        )
        audioTrack.stop()
        audioTrack.playbackHeadPosition = 0
        audioTrack.play()
    }

    fun release() {
        audioTrack.release()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 44_100
        const val FREQUENCY_HZ = 1_000.0
        const val DURATION_MS = 150
        const val FADE_MS = 3

        // Rapid rise, solid sustain, rapid fall: just enough fade (a few ms) to avoid the
        // click an instant on/off edge would add, without softening it into a slow swell.
        val toneSamples: ShortArray = run {
            val totalSamples = SAMPLE_RATE_HZ * DURATION_MS / 1000
            val fadeSamples = SAMPLE_RATE_HZ * FADE_MS / 1000
            ShortArray(totalSamples) { i ->
                val angle = 2.0 * PI * FREQUENCY_HZ * i / SAMPLE_RATE_HZ
                val envelope = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i > totalSamples - fadeSamples -> (totalSamples - i).toDouble() / fadeSamples
                    else -> 1.0
                }
                (sin(angle) * envelope * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }
}