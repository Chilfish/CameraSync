package dev.sebastiano.camerasync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import dev.sebastiano.camerasync.di.AppGraph
import dev.sebastiano.camerasync.logging.LogViewerScreen
import dev.sebastiano.camerasync.logging.LogViewerViewModel
import dev.sebastiano.camerasync.settings.SettingsScreen
import dev.sebastiano.camerasync.ui.theme.CameraSyncTheme
import dev.sebastiano.camerasync.usb.FirstRunGuideScreen
import dev.sebastiano.camerasync.usb.GalleryFolderScreen
import dev.sebastiano.camerasync.usb.GalleryScreen
import dev.sebastiano.camerasync.usb.GalleryViewModel
import dev.sebastiano.camerasync.usb.LocalPhotosViewModel
import dev.sebastiano.camerasync.usb.TransferHistoryScreen
import dev.sebastiano.camerasync.usb.UsbSyncPreferences
import dev.zacsweers.metro.Inject

@Inject
class MainActivity : ComponentActivity() {

    private val appGraph: AppGraph by lazy { (application as CameraSyncApp).appGraph }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RootComposable(
                viewModelFactory = appGraph.viewModelFactory(),
                galleryViewModel = appGraph.galleryViewModel(),
                localPhotosViewModel = appGraph.localPhotosViewModel(),
            )
        }
    }
}

@Composable
private fun RootComposable(
    viewModelFactory: ViewModelProvider.Factory,
    galleryViewModel: GalleryViewModel,
    localPhotosViewModel: LocalPhotosViewModel,
) {
    val ctx = LocalContext.current
    val prefs = remember { UsbSyncPreferences(ctx) }
    // Compose-reactive copy of the persisted theme so the settings screen can switch it live
    // (R11, P4-3): SharedPreferences reads alone don't trigger recomposition.
    var themeMode by remember { mutableStateOf(prefs.getThemeMode()) }

    CameraSyncTheme(themeMode = themeMode) {

        // Pair the USB lifecycle with the root composition: a configuration change (rotation)
        // disposes the composition — closing the receiver and MtpDevice — before the new
        // composition starts it again, so a second MtpDevice can never open on the same connection
        // (R8, P4-1). The ViewModel itself is held by AppGraph (P5-2, 方案 A): the same instance
        // survives rotation and its stop() resets state, preserving the pre-DI fresh-instance UX.
        // Accepted tradeoff: brief reconnect on rotation (action-plan P4-1 option B).
        DisposableEffect(galleryViewModel) {
            galleryViewModel.start()
            onDispose { galleryViewModel.stop() }
        }

        val backStack =
            rememberSaveable(
                saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })
            ) {
                mutableStateListOf<NavRoute>(NavRoute.Gallery)
            }

        // Cold start: show the one-screen MTP guide before anything else. guideSeen is only set
        // once the guide is actually dismissed (onDone) — never before showing it, so a crash on
        // the guide screen doesn't permanently hide it (R18, P4-4).
        LaunchedEffect(Unit) {
            if (!prefs.guideSeen) {
                backStack.add(NavRoute.FirstRunGuide)
            }
        }

        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            transitionSpec = {
                (slideInHorizontally(initialOffsetX = { it / 4 }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { -it / 4 }) + fadeOut())
            },
            popTransitionSpec = {
                (slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { it / 4 }) + fadeOut())
            },
            predictivePopTransitionSpec = {
                (slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { it / 4 }) + fadeOut())
            },
        ) { key ->
            NavEntry(key) {
                when (key) {
                    NavRoute.Gallery -> {
                        GalleryScreen(
                            viewModel = galleryViewModel,
                            localPhotosViewModel = localPhotosViewModel,
                            onNavigateToLogs = { backStack.add(NavRoute.LogViewer) },
                            onNavigateToSettings = { backStack.add(NavRoute.Settings) },
                            onFolderClick = { folder ->
                                backStack.add(
                                    NavRoute.GalleryFolder(
                                        folder.storageId,
                                        folder.info.handle,
                                        folder.info.name,
                                    )
                                )
                            },
                        )
                    }

                    NavRoute.FirstRunGuide -> {
                        FirstRunGuideScreen(
                            onNavigateBack = { backStack.removeLastOrNull() },
                            onDone = {
                                prefs.guideSeen = true
                                backStack.removeLastOrNull()
                            },
                        )
                    }

                    is NavRoute.GalleryFolder -> {
                        GalleryFolderScreen(
                            viewModel = galleryViewModel,
                            storageId = key.storageId,
                            folderHandle = key.folderHandle,
                            folderName = key.folderName,
                            onNavigateBack = { backStack.removeLastOrNull() },
                            onFolderClick = { folder ->
                                backStack.add(
                                    NavRoute.GalleryFolder(
                                        folder.storageId,
                                        folder.info.handle,
                                        folder.info.name,
                                    )
                                )
                            },
                        )
                    }

                    NavRoute.LogViewer -> {
                        val logViewerViewModel: LogViewerViewModel =
                            viewModel(factory = viewModelFactory)

                        LogViewerScreen(
                            viewModel = logViewerViewModel,
                            onNavigateBack = { backStack.removeLastOrNull() },
                        )
                    }

                    NavRoute.Settings -> {
                        val p = remember { UsbSyncPreferences(ctx) }
                        SettingsScreen(
                            initialGridColumns = p.getGridColumns(),
                            initialGrouping = p.photoGrouping,
                            initialSorting = p.photoSorting,
                            initialDownloadFormat = p.downloadFormat,
                            initialThemeMode = p.getThemeMode(),
                            onNavigateBack = { backStack.removeLastOrNull() },
                            onNavigateToHistory = { backStack.add(NavRoute.TransferHistory) },
                            onNavigateToOnboarding = { backStack.add(NavRoute.FirstRunGuide) },
                            onGroupingChanged = {
                                p.photoGrouping = it
                                galleryViewModel.requestReload()
                            },
                            onSortingChanged = {
                                p.photoSorting = it
                                galleryViewModel.requestReload()
                            },
                            onDownloadFormatChanged = {
                                p.downloadFormat = it
                                galleryViewModel.requestReload()
                            },
                            onGridColumnsChanged = {
                                p.setGridColumns(it)
                                galleryViewModel.gridColumns = it
                            },
                            onThemeModeChanged = {
                                p.setThemeMode(it)
                                themeMode = it
                            },
                        )
                    }

                    NavRoute.TransferHistory -> {
                        val p = remember { UsbSyncPreferences(ctx) }
                        val records = remember { p.getTransferHistory() }
                        TransferHistoryScreen(
                            records = records,
                            onNavigateBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
        }
    }
}
