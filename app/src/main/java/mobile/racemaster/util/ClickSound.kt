package mobile.racemaster.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Short click tone played on every button press, generated directly via [AudioTrack] rather
 * than the system's View.playSoundEffect()/SoundEffectConstants — that route silently no-ops
 * whenever the device's "Touch sounds" system setting is off. Confirmed on real hardware: two
 * phones running the same build, one with that setting on and one off — the "off" one played
 * no click in this app at all, while still playing *other* apps' own bundled click sounds
 * fine (those don't go through the gated system API either). Generating our own tone
 * sidesteps that setting entirely — the same reliability trick [Beeper] already uses for the
 * split/bib confirmation beep.
 *
 * Played on the alarm stream at forced max volume, same as [Beeper] and for the same reason:
 * the finish line is a noisy place, and this needs to be heard over it rather than respecting
 * whatever the media/system volume happens to be set to.
 */
private object ClickTone {
    private const val SAMPLE_RATE_HZ = 44_100
    private const val FREQUENCY_HZ = 2_500.0
    private const val DURATION_MS = 20
    private const val FADE_MS = 2

    private val toneSamples: ShortArray = run {
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

    private val audioTrack: AudioTrack by lazy {
        AudioTrack.Builder()
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
    }

    fun play(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0,
        )
        audioTrack.stop()
        audioTrack.playbackHeadPosition = 0
        audioTrack.play()
    }
}

/** Wraps [onClick] to also play a short, maximally loud click tone first, for consistent
 *  audio confirmation on every button press regardless of the device's "Touch sounds" system
 *  setting or its current volume level. */
@Composable
fun withClickSound(onClick: () -> Unit): () -> Unit {
    val context = LocalContext.current
    return {
        ClickTone.play(context)
        onClick()
    }
}
