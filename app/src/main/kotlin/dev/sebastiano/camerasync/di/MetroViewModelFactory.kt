package dev.sebastiano.camerasync.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.sebastiano.camerasync.logging.LogViewerViewModel

class MetroViewModelFactory(private val appGraph: AppGraph) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(LogViewerViewModel::class.java) ->
                appGraph.logViewerViewModel() as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
