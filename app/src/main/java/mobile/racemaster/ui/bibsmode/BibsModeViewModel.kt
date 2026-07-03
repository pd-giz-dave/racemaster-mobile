package mobile.racemaster.ui.bibsmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.BibEntryType
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.buildRaceLabel
import mobile.racemaster.data.repository.isRaceInProgress
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.di.applicationContext
import mobile.racemaster.util.Beeper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BibEntryUi(val id: Long, val bibNumber: Int, val splitNumber: Int?, val type: BibEntryType)

data class BibsModeUiState(
    val raceLabel: String = "",
    val currentDigits: String = "",
    val entries: List<BibEntryUi> = emptyList(),
    val canSubmit: Boolean = false,
    val canUndo: Boolean = false,
    val raceInProgress: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BibsModeViewModel(
    private val bibsModeRepository: BibsModeRepository,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
    private val beeper: Beeper,
) : ViewModel() {

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val digitsFlow = MutableStateFlow("")

    private val raceAndEntriesFlow = raceIdFlow.flatMapLatest { raceId ->
        if (raceId == null) {
            flowOf(null to emptyList<BibEntryUi>())
        } else {
            combine(
                raceRepository.observeRace(raceId),
                bibsModeRepository.observeEntries(raceId),
            ) { race, entries ->
                race to entries.map { BibEntryUi(it.id, it.bibNumber, it.splitNumber, it.type) }
            }
        }
    }

    val uiState: StateFlow<BibsModeUiState> = combine(raceAndEntriesFlow, digitsFlow) { (race, entries), digits ->
        BibsModeUiState(
            raceLabel = race?.label.orEmpty(),
            currentDigits = digits,
            entries = entries,
            canSubmit = digits.isNotEmpty(),
            canUndo = entries.isNotEmpty(),
            raceInProgress = isRaceInProgress(
                race?.timeModeStartedAtMillis,
                race?.timeModeStoppedAtMillis,
                entries.isNotEmpty(),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BibsModeUiState())

    fun onDigit(digit: Int) {
        if (digitsFlow.value.length >= MAX_BIB_DIGITS) return
        digitsFlow.value += digit.toString()
    }

    fun onBackspace() {
        digitsFlow.value = digitsFlow.value.dropLast(1)
    }

    fun onClear() {
        digitsFlow.value = ""
    }

    fun submit(type: BibEntryType) {
        val raceId = raceIdFlow.value ?: return
        val bib = digitsFlow.value.toIntOrNull() ?: return
        viewModelScope.launch {
            bibsModeRepository.recordEntry(raceId, bib, type)
            digitsFlow.value = ""
            beeper.beep()
        }
    }

    fun undoLast() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { bibsModeRepository.deleteMostRecent(raceId) }
    }

    fun startNewRace(name: String) {
        if (uiState.value.raceInProgress) return
        viewModelScope.launch {
            val newRaceId = raceRepository.startNewRace(buildRaceLabel(name))
            settingsRepository.setActiveRaceId(newRaceId)
        }
    }

    override fun onCleared() {
        beeper.release()
    }

    companion object {
        private const val MAX_BIB_DIGITS = 4

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                BibsModeViewModel(
                    container.bibsModeRepository,
                    container.raceRepository,
                    container.settingsRepository,
                    Beeper(applicationContext()),
                )
            }
        }
    }
}