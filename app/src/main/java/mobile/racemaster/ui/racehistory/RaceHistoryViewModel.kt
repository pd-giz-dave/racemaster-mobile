package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface HistoryItemUi {
    data class LocalRace(val id: Long, val label: String) : HistoryItemUi
    data class MuleSource(
        val deviceRole: String,
        val raceLabel: String,
        val syncedCount: Int,
        val totalCount: Int,
    ) : HistoryItemUi
}

class RaceHistoryViewModel(
    raceRepository: RaceRepository,
    muleRepository: MuleRepository,
) : ViewModel() {

    val historyItems: StateFlow<List<HistoryItemUi>> = combine(
        raceRepository.observeAllRaces(),
        muleRepository.sourceSummaries,
    ) { races, sourceSummaries ->
        races.map { HistoryItemUi.LocalRace(it.id, it.label) } +
            sourceSummaries.map {
                HistoryItemUi.MuleSource(it.sourceDeviceRole, it.sourceRaceLabel, it.syncedCount, it.totalCount)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                RaceHistoryViewModel(container.raceRepository, container.muleRepository)
            }
        }
    }
}
