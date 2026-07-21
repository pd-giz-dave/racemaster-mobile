package mobile.racemaster.di

import android.app.Application
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import mobile.racemaster.RacemasterApplication

/** Resolves the [AppContainer] from a ViewModel factory's [CreationExtras]. */
fun CreationExtras.appContainer(): AppContainer =
    (this[APPLICATION_KEY] as RacemasterApplication).container

/** Resolves the process-lifetime [Application] from a ViewModel factory's [CreationExtras] —
 *  typed as [Application] specifically (not the more general [android.content.Context]) so a
 *  ViewModel field holding onto it doesn't trip Lint's StaticFieldLeak check, which otherwise
 *  can't tell an Application-scoped Context apart from one that would actually outlive its
 *  Activity. */
fun CreationExtras.applicationContext(): Application =
    this[APPLICATION_KEY] as RacemasterApplication