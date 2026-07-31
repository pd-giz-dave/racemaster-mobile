package mobile.racemaster.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Suppresses the current-segment list's row taps for a brief window right after the main
 * "record an entry" button (Split/Submit/Pass/Retire) fires. Recording is asynchronous — a DB
 * write followed by a Flow round trip — before the new row actually lands at the top of the
 * list, so a fast second tap aimed at "the row that should now be there" (e.g. to add a note
 * to the split just recorded) is dispatched against whatever's still rendered at that instant:
 * the previous top row, or with even less margin, the Stop/Reset button directly above it.
 * Confirmed in the field on all three entry-logging modes (Time/Bibs/CP): a quick
 * record-then-tap-to-edit gesture reliably mis-edited the wrong entry.
 *
 * This is a genuine timing race, not a layout bug — the tap is correctly hit-testing real
 * content that just hasn't been replaced yet. Nothing reacting to the list's own state (e.g. a
 * LaunchedEffect keyed on the newest entry's id) can guard against it: by the time such an
 * effect observes the new entry, the correct row is already showing, which means any stale tap
 * this would have caught has already fired. The only point early enough is the button tap
 * itself — [ListClickGuard.trigger] must be called there, before the async record call, not
 * reactively.
 */
@Composable
fun rememberListClickGuard(window: Duration = 400.milliseconds): ListClickGuard {
    val state = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    return remember { ListClickGuard(state, scope, window) }
}

@Stable
class ListClickGuard internal constructor(
    private val state: MutableState<Boolean>,
    private val scope: CoroutineScope,
    private val window: Duration,
) {
    val isSuppressed: Boolean get() = state.value

    fun trigger() {
        state.value = true
        scope.launch {
            delay(window)
            state.value = false
        }
    }
}
