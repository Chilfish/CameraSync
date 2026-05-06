package dev.sebastiano.camerasync

import android.app.Application
import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
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
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import dev.sebastiano.camerasync.devices.DevicesListScreen
import dev.sebastiano.camerasync.devices.DevicesListViewModel
import dev.sebastiano.camerasync.devicesync.MultiDeviceSyncService
import dev.sebastiano.camerasync.di.AppGraph
import dev.sebastiano.camerasync.logging.LogViewerScreen
import dev.sebastiano.camerasync.logging.LogViewerViewModel
import dev.sebastiano.camerasync.pairing.PairingScreen
import dev.sebastiano.camerasync.pairing.PairingViewModel
import dev.sebastiano.camerasync.onboarding.OnboardingScreen
import dev.sebastiano.camerasync.onboarding.OnboardingViewModel
import dev.sebastiano.camerasync.permissions.PermissionsScreen
import dev.sebastiano.camerasync.ui.theme.CameraSyncTheme
import dev.sebastiano.camerasync.usb.GalleryFolderScreen
import dev.sebastiano.camerasync.usb.GalleryScreen
import dev.sebastiano.camerasync.usb.GalleryViewModel
import dev.zacsweers.metro.Inject

@Inject
class MainActivity : ComponentActivity() {

    private val appGraph: AppGraph by lazy { (application as CameraSyncApp).appGraph }
    private var shouldShowPermissionsState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        // Notification channels are registered in CameraSyncApp.onCreate() to ensure they're
        // available before any service tries to use them

        shouldShowPermissionsState =
            intent.getBooleanExtra(MultiDeviceSyncService.EXTRA_SHOW_PERMISSIONS, false)

        setContent {
            RootComposable(
                viewModelFactory = appGraph.viewModelFactory(),
                shouldShowPermissions = shouldShowPermissionsState,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shouldShowPermissionsState =
            intent.getBooleanExtra(MultiDeviceSyncService.EXTRA_SHOW_PERMISSIONS, false)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun RootComposable(
    viewModelFactory: ViewModelProvider.Factory,
    shouldShowPermissions: Boolean = false,
) {
    CameraSyncTheme {
        // Shared GalleryViewModel — lives at Activity scope so it survives
        // folder navigation pushes/pops without reconnecting MTP.
        val ctx = LocalContext.current
        val app = ctx.applicationContext as Application
        val galleryViewModel = remember { GalleryViewModel(app) }

        // Onboarding — only shown on first launch
        val onboardingVm = remember { OnboardingViewModel(ctx) }
        val showOnboarding = !onboardingVm.hasCompleted

        val basePermissions =
            listOf(ACCESS_FINE_LOCATION, BLUETOOTH_SCAN, BLUETOOTH_CONNECT, POST_NOTIFICATIONS)
        val multiplePermissionsState =
            rememberMultiplePermissionsState(permissions = basePermissions)
        val backgroundPermissionState =
            rememberPermissionState(permission = ACCESS_BACKGROUND_LOCATION)

        // Initialize backStack - validate saved state against current permissions
        val backStack =
            rememberSaveable(
                saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })
            ) {
                mutableStateListOf<NavRoute>(
                    if (showOnboarding) NavRoute.Onboarding else NavRoute.NeedsPermissions
                )
            }

        // Validate saved backStack state: if we saved DevicesList but permissions are now missing,
        // reset to NeedsPermissions
        LaunchedEffect(
            multiplePermissionsState.allPermissionsGranted,
            backgroundPermissionState.status.isGranted,
        ) {
            val allPermissionsGranted =
                multiplePermissionsState.allPermissionsGranted &&
                    backgroundPermissionState.status.isGranted
            val currentRoute = backStack.firstOrNull()

            if (!allPermissionsGranted && currentRoute == NavRoute.DevicesList) {
                // Permissions were revoked or background location is missing - go back to
                // permissions screen
                backStack.clear()
                backStack.add(NavRoute.NeedsPermissions)
            } else if (allPermissionsGranted && currentRoute == NavRoute.NeedsPermissions) {
                // All permissions granted - navigate to Gallery
                backStack[0] = NavRoute.Gallery
            }
        }

        // Navigate to permissions screen if requested from notification
        LaunchedEffect(shouldShowPermissions) {
            if (shouldShowPermissions) {
                // Clear back stack and show permissions screen
                backStack.clear()
                backStack.add(NavRoute.NeedsPermissions)
            }
        }

        // Check if ALL permissions (including background location) are granted and navigate to
        // DevicesList
        // This handles both startup (when permissions are already granted) and runtime (when user
        // grants permissions)
        LaunchedEffect(
            multiplePermissionsState.allPermissionsGranted,
            backgroundPermissionState.status.isGranted,
        ) {
            val allPermissionsGranted =
                multiplePermissionsState.allPermissionsGranted &&
                    backgroundPermissionState.status.isGranted
            if (allPermissionsGranted && backStack.contains(NavRoute.NeedsPermissions)) {
                // Replace NeedsPermissions with DevicesList
                val needsPermissionsIndex = backStack.indexOf(NavRoute.NeedsPermissions)
                backStack[needsPermissionsIndex] = NavRoute.Gallery
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
                    NavRoute.Onboarding -> {
                        OnboardingScreen(onDone = {
                            onboardingVm.markCompleted()
                            backStack.clear()
                            backStack.add(NavRoute.NeedsPermissions)
                        })
                    }

                    NavRoute.NeedsPermissions -> {
                        PermissionsScreen(
                            onPermissionsGranted = {
                                backStack.add(NavRoute.Gallery)
                                backStack.remove(NavRoute.NeedsPermissions)
                            }
                        )
                    }

                    NavRoute.Gallery -> {
                        GalleryScreen(
                            viewModel = galleryViewModel,
                            onNavigateToLogs = { backStack.add(NavRoute.LogViewer) },
                            onFolderClick = { folder ->
                                backStack.add(
                                    NavRoute.GalleryFolder(folder.storageId, folder.info.handle, folder.info.name)
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
                            onNavigateBack = {
                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            },
                            onFolderClick = { folder ->
                                backStack.add(
                                    NavRoute.GalleryFolder(folder.storageId, folder.info.handle, folder.info.name)
                                )
                            },
                        )
                    }

                    NavRoute.DevicesList -> {
                        val devicesListViewModel: DevicesListViewModel =
                            viewModel(factory = viewModelFactory)

                        DevicesListScreen(
                            viewModel = devicesListViewModel,
                            onAddDeviceClick = { backStack.add(NavRoute.Pairing) },
                            onViewLogsClick = { backStack.add(NavRoute.LogViewer) },
                        )
                    }

                    NavRoute.LogViewer -> {
                        val logViewerViewModel: LogViewerViewModel =
                            viewModel(factory = viewModelFactory)

                        LogViewerScreen(
                            viewModel = logViewerViewModel,
                            onNavigateBack = {
                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            },
                        )
                    }

                    NavRoute.Pairing -> {
                        val pairingViewModel: PairingViewModel =
                            viewModel(factory = viewModelFactory)
                        val context = LocalContext.current

                        @SuppressLint("MissingPermission")
                        PairingScreen(
                            viewModel = pairingViewModel,
                            onNavigateBack = {
                                // Can't use removeLast before API 35
                                // Ensure we don't remove the last item (must keep at least one
                                // route)
                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            },
                            onDevicePaired = {
                                // Can't use removeLast before API 35
                                // Ensure we don't remove the last item (must keep at least one
                                // route)
                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                // Trigger a refresh so the newly paired device connects
                                // immediately.
                                androidx.core.content.ContextCompat.startForegroundService(
                                    context,
                                    MultiDeviceSyncService.createRefreshIntent(context),
                                )
                            },
                        )
                    }

                }
            }
        }
    }
}
