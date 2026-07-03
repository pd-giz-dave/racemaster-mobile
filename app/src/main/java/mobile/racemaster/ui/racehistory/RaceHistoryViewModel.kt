package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class RaceSummaryUi(val id: Long, val label: String)

class RaceHistoryViewModel(raceRepository: RaceRepository) : ViewModel() {

    val races: StateFlow<List<RaceSummaryUi>> = raceRepository.observeAllRaces()
        .map { races -> races.map { RaceSummaryUi(it.id, it.label) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { RaceHistoryViewModel(appContainer().raceRepository) }
        }
    }
}