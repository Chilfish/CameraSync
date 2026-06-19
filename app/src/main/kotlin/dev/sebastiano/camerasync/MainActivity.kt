package dev.sebastiano.camerasync

import android.app.Application
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.sebastiano.camerasync.usb.GalleryFolderScreen
import dev.sebastiano.camerasync.usb.GalleryScreen
import dev.sebastiano.camerasync.usb.GalleryViewModel
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
            )
        }
    }
}

@Composable
private fun RootComposable(
    viewModelFactory: ViewModelProvider.Factory,
) {
    val ctx = LocalContext.current
    val prefs = remember { UsbSyncPreferences(ctx) }
    val themeMode = prefs.getThemeMode()

    CameraSyncTheme(themeMode = themeMode) {
        val app = ctx.applicationContext as Application
        val galleryViewModel = remember { GalleryViewModel(app) }

        val backStack =
            rememberSaveable(
                saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })
            ) {
                mutableStateListOf<NavRoute>(NavRoute.Gallery)
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
                            prefs = p,
                            onNavigateBack = { backStack.removeLastOrNull() },
                            onNavigateToHistory = { backStack.add(NavRoute.TransferHistory) },
                            onNavigateToOnboarding = {},
                            onGroupingChanged = { galleryViewModel.requestReload() },
                            onSortingChanged = { galleryViewModel.requestReload() },
                            onDownloadFormatChanged = { galleryViewModel.requestReload() },
                            onGridColumnsChanged = { galleryViewModel.gridColumns = it },
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
