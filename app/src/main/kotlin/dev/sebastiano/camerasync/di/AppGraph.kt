package dev.sebastiano.camerasync.di

import android.app.Application
import android.content.Context
import dev.sebastiano.camerasync.MainActivity
import dev.sebastiano.camerasync.logging.LogRepository
import dev.sebastiano.camerasync.logging.LogViewerViewModel
import dev.sebastiano.camerasync.logging.LogcatLogRepository
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@DependencyGraph
@SingleIn(AppGraph::class)
interface AppGraph {
    fun mainActivity(): MainActivity

    fun logViewerViewModel(): LogViewerViewModel

    fun viewModelFactory(): MetroViewModelFactory = MetroViewModelFactory(this)

    @Provides
    @SingleIn(AppGraph::class)
    fun provideApplicationContext(application: Application): Context = application

    @Provides
    @SingleIn(AppGraph::class)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @SingleIn(AppGraph::class)
    fun provideLogRepository(context: Context): LogRepository = LogcatLogRepository(context)

    @Provides
    fun provideLogViewerViewModel(
        logRepository: LogRepository,
        ioDispatcher: CoroutineDispatcher,
    ): LogViewerViewModel = LogViewerViewModel(logRepository, ioDispatcher)

    @DependencyGraph.Factory
    interface Factory {
        fun create(@Provides application: Application): AppGraph
    }
}
