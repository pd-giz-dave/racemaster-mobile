package mobile.racemaster.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import mobile.racemaster.RacemasterApplication

/** Resolves the [AppContainer] from a ViewModel factory's [CreationExtras]. */
fun CreationExtras.appContainer(): AppContainer =
    (this[APPLICATION_KEY] as RacemasterApplication).container

/** Resolves the [Context] from a ViewModel factory's [CreationExtras]. */
fun CreationExtras.applicationContext(): Context =
    this[APPLICATION_KEY] as RacemasterApplication