package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.BibEntryType
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ArchivedSplitUi(val id: Long, val splitNumber: Int, val elapsedMillis: Long, val label: String?)
data class ArchivedBibEntryUi(val id: Long, val bibNumber: Int, val splitNumber: Int?, val type: BibEntryType)

data class RaceHistoryDetailUiState(
    val raceLabel: String = "",
    val splits: List<ArchivedSplitUi> = emptyList(),
    val bibEntries: List<ArchivedBibEntryUi> = emptyList(),
)

class RaceHistoryDetailViewModel(
    raceId: Long,
    raceRepository: RaceRepository,
    timeModeRepository: TimeModeRepository,
    bibsModeRepository: BibsModeRepository,
) : ViewModel() {

    val uiState: StateFlow<RaceHistoryDetailUiState> = combine(
        raceRepository.observeRace(raceId),
        timeModeRepository.observeSplits(raceId),
        bibsModeRepository.observeEntries(raceId),
    ) { race, splits, entries ->
        val startedAt = race?.timeModeStartedAtMillis
        RaceHistoryDetailUiState(
            raceLabel = race?.label.orEmpty(),
            splits = splits.map {
                ArchivedSplitUi(
                    id = it.id,
                    splitNumber = it.splitNumber,
                    elapsedMillis = startedAt?.let { s -> it.timestampMillis - s } ?: 0L,
                    label = it.label,
                )
            },
            bibEntries = entries.map { ArchivedBibEntryUi(it.id, it.bibNumber, it.splitNumber, it.type) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RaceHistoryDetailUiState())

    companion object {
        fun factory(raceId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                RaceHistoryDetailViewModel(
                    raceId,
                    container.raceRepository,
                    container.timeModeRepository,
                    container.bibsModeRepository,
                )
            }
        }
    }
}