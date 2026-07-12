package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class MulePulledRecordUi(
    val recordUuid: String,
    val action: String,
    val number: Int?,
    val splitNumber: Int,
    val elapsedMillis: Long,
    val note: String?,
    val deviceName: String,
    val synced: Boolean,
)

data class MuleSourceDetailUiState(
    val raceLabel: String = "",
    val deviceRole: String = "",
    val records: List<MulePulledRecordUi> = emptyList(),
)

class MuleSourceDetailViewModel(
    deviceRole: String,
    raceLabel: String,
    muleRepository: MuleRepository,
) : ViewModel() {

    val uiState: StateFlow<MuleSourceDetailUiState> = muleRepository.observeRecordsForSource(deviceRole, raceLabel)
        .map { records ->
            MuleSourceDetailUiState(
                raceLabel = raceLabel,
                deviceRole = deviceRole,
                records = records.map {
                    MulePulledRecordUi(
                        recordUuid = it.record.recordUuid,
                        action = it.record.action,
                        number = it.record.number,
                        splitNumber = it.record.splitNumber ?: 0,
                        elapsedMillis = parseElapsedClock(it.record.time),
                        note = it.record.note,
                        deviceName = it.record.deviceName,
                        synced = it.syncedAtMillis != null,
                    )
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MuleSourceDetailUiState())

    companion object {
        fun factory(deviceRole: String, raceLabel: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MuleSourceDetailViewModel(deviceRole, raceLabel, appContainer().muleRepository)
            }
        }
    }
}

// Inverse of SyncRecordMapping's formatElapsedAsClock — the wire format only carries elapsed
// time as an "HH:MM:SS" string (matching the server's finisher-time convention), so a pulled
// Time Mode record's centiseconds aren't recoverable here; this reuses the same SplitRow the
// live screen and local race history use, at whatever precision the wire actually carried.
private fun parseElapsedClock(time: String?): Long {
    val parts = time?.split(":")?.mapNotNull { it.toIntOrNull() } ?: return 0L
    if (parts.size != 3) return 0L
    val (hours, minutes, seconds) = parts
    return ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L
}
