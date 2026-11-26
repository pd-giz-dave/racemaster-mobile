package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.peersync.PeerSyncRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.ui.countmode.PeerActivityLineUi
import mobile.racemaster.ui.countmode.peerActivityFrom
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The "view" behind a Race History "Peers" list item (TODO.md: "the activity history should be
 *  a separate item (one per device) ... rather than embedded in the race history") — a one-shot
 *  look at one same-group peer's own raw reports, the peer-sync counterpart of
 *  [MuleSourceDetailViewModel]. raceLabel/originDeviceName aren't re-resolved here: the list
 *  already had both on hand (see RaceHistoryViewModel.historyItems' own HistoryItemUi.PeerActivity),
 *  so [PeerActivityDetailScreen] takes them as plain nav args instead of this ViewModel repeating
 *  the same raceGroupId resolution RaceHistoryViewModel.resolveRaceGroupLabel already did. */
class PeerActivityDetailViewModel(
    raceGroupId: String,
    mode: HistoryMode,
    originDeviceId: String,
    peerSyncRepository: PeerSyncRepository,
) : ViewModel() {
    val lines: StateFlow<List<PeerActivityLineUi>> = peerSyncRepository.observeOrigin(raceGroupId, mode, originDeviceId)
        .map { peerActivityFrom(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory(raceGroupId: String, mode: HistoryMode, originDeviceId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PeerActivityDetailViewModel(raceGroupId, mode, originDeviceId, appContainer().peerSyncRepository)
            }
        }
    }
}
