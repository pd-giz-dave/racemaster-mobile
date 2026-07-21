package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.hasRealEntries
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HistoryItemUi {
    // Always this device's own race — see RaceRepository.observeAllRaces, which only ever
    // returns races this installation itself created. Shown as "From {name} (self)" so it
    // reads consistently alongside a MuleSource entry's "From {name}" once both appear in the
    // same list. [isActive] is per isRaceActive — the centralized definition (see its own
    // doc) — not merely "this is the currently-selected race". RaceHistoryScreen disables
    // deleting it accordingly (see RaceRepository.deleteRace's own backstop, which uses the
    // same definition via isRaceCurrentlyActive).
    data class LocalRace(val id: Long, val label: String, val createdByDeviceName: String, val isActive: Boolean) : HistoryItemUi
    // A race pulled via Mule from a genuinely different physical device — self-pulled rows are
    // excluded upstream (see PulledRecordDao), so this is never just an echo of a LocalRace
    // entry above.
    data class MuleSource(val raceLabel: String, val deviceName: String) : HistoryItemUi
}

@OptIn(ExperimentalCoroutinesApi::class)
class RaceHistoryViewModel(
    private val raceRepository: RaceRepository,
    muleRepository: MuleRepository,
    bibsModeRepository: BibsModeRepository,
) : ViewModel() {

    val historyItems: StateFlow<List<HistoryItemUi>> = combine(
        raceRepository.observeAllRaces(),
        muleRepository.sourceSummaries,
    ) { races, sourceSummaries -> races to sourceSummaries }
        .flatMapLatest { (races, sourceSummaries) ->
            val muleItems = sourceSummaries.map { HistoryItemUi.MuleSource(it.sourceRaceLabel, it.deviceName) }
            if (races.isEmpty()) {
                flowOf(muleItems)
            } else {
                // One flow per race (its own current Bibs segment, to know if Bibs has real
                // activity) — needed alongside the race's own Time-mode field to tell "active"
                // apart from "merely selected", same distinction RaceRepository.deleteRace's
                // own backstop makes.
                combine(
                    races.map { race ->
                        bibsModeRepository.observeCurrentSegmentEntries(race.id).map { bibsEntries ->
                            HistoryItemUi.LocalRace(
                                id = race.id,
                                label = race.label,
                                createdByDeviceName = race.createdByDeviceName,
                                isActive = isRaceActive(race.timeModeStartedAtMillis, bibsEntries.hasRealEntries()),
                            )
                        }
                    },
                ) { localRaces -> localRaces.toList() + muleItems }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Permanently erases a local race — see RaceRepository.deleteRace's own doc. Irreversible;
    // RaceHistoryScreen only calls this after its own confirmation dialog (and never offers it
    // at all for an active race — see HistoryItemUi.LocalRace.isActive).
    fun deleteRace(raceId: Long) {
        viewModelScope.launch { raceRepository.deleteRace(raceId) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                RaceHistoryViewModel(container.raceRepository, container.muleRepository, container.bibsModeRepository)
            }
        }
    }
}
