package mobile.racemaster.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Emits the current wall-clock time every 100ms, for driving live elapsed-time displays. */
val tickerFlow: Flow<Long> = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(100)
    }
}