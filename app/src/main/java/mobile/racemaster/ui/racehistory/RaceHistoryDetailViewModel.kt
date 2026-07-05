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
import mobile.racemaster.data.repository.findDuplicateSplitRefs
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ArchivedSplitUi(
    val id: Long,
    val splitNumber: Int,
    val elapsedMillis: Long,
    val note: String?,
    val timestampMillis: Long,
    val synced: Boolean,
)
data class ArchivedBibEntryUi(
    val id: Long,
    val bibNumber: Int?,
    val splitNumber: Int,
    val type: BibEntryType,
    val note: String?,
    val dupSplitRefs: List<Int>,
    val timestampMillis: Long,
    val synced: Boolean,
)

data class RaceHistoryDetailUiState(
    val raceLabel: String = "",
    val splits: List<ArchivedSplitUi> = emptyList(),
    val bibEntries: List<ArchivedBibEntryUi> = emptyList(),
    val lastSyncedAtMillis: Long? = null,
)

class RaceHistoryDetailViewModel(
    raceId: Long,
    raceRepository: RaceRepository,
    timeModeRepository: TimeModeRepository,
    bibsModeRepository: BibsModeRepository,
) : ViewModel() {

    private val lastSyncedFlow = combine(
        timeModeRepository.observeLastSyncedAtMillis(raceId),
        bibsModeRepository.observeLastSyncedAtMillis(raceId),
    ) { timeSynced, bibsSynced -> maxOfNullable(timeSynced, bibsSynced) }

    val uiState: StateFlow<RaceHistoryDetailUiState> = combine(
        raceRepository.observeRace(raceId),
        timeModeRepository.observeSplits(raceId),
        bibsModeRepository.observeEntries(raceId),
        lastSyncedFlow,
    ) { race, splits, entries, lastSyncedAtMillis ->
        val startedAt = race?.timeModeStartedAtMillis
        val dupRefs = findDuplicateSplitRefs(entries)
        RaceHistoryDetailUiState(
            raceLabel = race?.label.orEmpty(),
            splits = splits.map {
                ArchivedSplitUi(
                    id = it.id,
                    splitNumber = it.splitNumber,
                    elapsedMillis = startedAt?.let { s -> it.timestampMillis - s } ?: 0L,
                    note = it.note,
                    timestampMillis = it.timestampMillis,
                    synced = it.syncedAtMillis != null,
                )
            },
            bibEntries = entries.map {
                ArchivedBibEntryUi(
                    it.id, it.bibNumber, it.splitNumber, it.type, it.note,
                    dupRefs[it.id].orEmpty(), it.timestampMillis, it.syncedAtMillis != null,
                )
            },
            lastSyncedAtMillis = lastSyncedAtMillis,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RaceHistoryDetailUiState())

    companion object {
        // Time Mode and Bibs Mode each track their own "last pulled by a Mule" timestamp
        // independently — this race's overall status is whichever happened more recently.
        private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }

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