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
    val time: String?,
    val note: String?,
    val timestampMillis: Long,
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
                        time = it.record.time,
                        note = it.record.note,
                        timestampMillis = it.record.timestampMillis,
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
