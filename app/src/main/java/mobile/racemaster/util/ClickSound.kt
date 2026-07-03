package mobile.racemaster.util

import android.view.SoundEffectConstants
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/**
 * Wraps [onClick] to also play the standard keyboard-click sound first, for consistent audio
 * confirmation on every button press. Material3's Button/OutlinedButton/TextButton don't consult
 * LocalIndication for their ripple in this Compose version, so this has to be applied at each
 * call site rather than centrally.
 */
@Composable
fun withClickSound(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return {
        view.playSoundEffect(SoundEffectConstants.CLICK)
        onClick()
    }
}
