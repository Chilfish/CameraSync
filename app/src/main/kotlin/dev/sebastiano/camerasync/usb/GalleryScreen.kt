package dev.sebastiano.camerasync.usb

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.camerasync.R
import java.io.File

// ── Root Gallery Screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    localPhotosViewModel: LocalPhotosViewModel? = null,
    onNavigateToLogs: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onFolderClick: (GalleryEntry.Folder) -> Unit = {},
    // ── Folder-mode params (null → root gallery) ──────────────────────────
    storageId: Int? = null,
    folderHandle: Int? = null,
    folderName: String = "",
    onNavigateBack: () -> Unit = {},
) {
    // USB lifecycle (start/stop) is paired at the root composition in MainActivity — the shared
    // GalleryViewModel outlives individual screens (gallery vs folder), so starting/stopping here
    // would tear down MTP when navigating between them.

    // Load folder contents when in folder mode
    if (storageId != null && folderHandle != null) {
        LaunchedEffect(storageId, folderHandle) { viewModel.loadFolder(storageId, folderHandle) }
    }

    // Reload when settings change (e.g. grouping, sorting, download format)
    LaunchedEffect(viewModel.needsReload) {
        if (viewModel.needsReload) {
            viewModel.loadRoot()
            viewModel.needsReload = false
        }
    }

    val s = viewModel.state.value
    val context = LocalContext.current
    val prefs = remember { UsbSyncPreferences(context) }

    val inFolder = storageId != null

    // Local photos — auto-shown when camera is disconnected
    val app = context.applicationContext as Application
    val localVm = localPhotosViewModel ?: remember { LocalPhotosViewModel(app) }
    val showLocal = s is GalleryState.Disconnected && !inFolder

    // Reload local photos every time we enter the local view
    LaunchedEffect(showLocal) { if (showLocal) localVm.loadRoot() }

    // Preview bottom sheet state
    var showPreview by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            GalleryTopBar(
                title = if (inFolder) folderName else stringResource(R.string.usb_title),
                hasBack = inFolder,
                showSettings = !inFolder,
                showLogs = !inFolder,
                // Read inside the top-bar scope so a selection toggle recomposes the bar only.
                selectionCount = viewModel.selectedCount,
                onBackClick = {
                    viewModel.deselectAll()
                    onNavigateBack()
                },
                onLogsClick = onNavigateToLogs,
                onSettingsClick = onNavigateToSettings,
                onDeselectAll = viewModel::deselectAll,
                gridColumns = viewModel.gridColumns,
                onGridChange = {
                    viewModel.gridColumns = it
                    prefs.setGridColumns(it)
                },
                grouping = viewModel.groupingMode,
                onGroupingChange = {
                    viewModel.setGrouping(it)
                    viewModel.requestReload()
                },
                sorting = viewModel.sortingMode,
                onSortingChange = {
                    viewModel.setSorting(it)
                    viewModel.requestReload()
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = s is GalleryState.Browsing && viewModel.selectedCount > 0,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                BottomAppBar {
                    Button(
                        onClick = { showPreview = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.usb_action_transfer_count,
                                viewModel.selectedCount,
                            )
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            if (showLocal) {
                LocalTabContent(localVm, viewModel.gridColumns)
            } else {
                CameraTabContent(
                    s,
                    viewModel,
                    inFolder,
                    onFolderClick,
                    onNavigateBack,
                    onTransferAllNew = {
                        viewModel.selectAllNew()
                        showPreview = true
                    },
                )
            }
        }

        // Transfer preview confirmation
        if (showPreview && s is GalleryState.Browsing) {
            TransferPreviewSheet(
                viewModel = viewModel,
                onConfirm = {
                    showPreview = false
                    viewModel.startTransfer()
                },
                onDismiss = { showPreview = false },
            )
        }
    }
}

// ── Folder Gallery Screen (thin wrapper) ───────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryFolderScreen(
    viewModel: GalleryViewModel,
    storageId: Int,
    folderHandle: Int,
    folderName: String,
    onNavigateBack: () -> Unit,
    onFolderClick: (GalleryEntry.Folder) -> Unit = {},
) {
    GalleryScreen(
        viewModel = viewModel,
        storageId = storageId,
        folderHandle = folderHandle,
        folderName = folderName,
        onNavigateBack = onNavigateBack,
        onFolderClick = onFolderClick,
    )
}

// ── Top Bar ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryTopBar(
    title: String,
    hasBack: Boolean,
    showLogs: Boolean,
    showSettings: Boolean = false,
    selectionCount: Int,
    onBackClick: () -> Unit,
    onLogsClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onDeselectAll: () -> Unit,
    gridColumns: Int = 3,
    onGridChange: (Int) -> Unit = {},
    grouping: UsbSyncPreferences.PhotoGrouping = UsbSyncPreferences.PhotoGrouping.BY_FOLDER,
    onGroupingChange: (UsbSyncPreferences.PhotoGrouping) -> Unit = {},
    sorting: UsbSyncPreferences.PhotoSorting = UsbSyncPreferences.PhotoSorting.DATE_DESC,
    onSortingChange: (UsbSyncPreferences.PhotoSorting) -> Unit = {},
) {
    var viewMenuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            if (selectionCount > 0)
                Text(
                    stringResource(R.string.usb_selected_count, selectionCount),
                    fontWeight = FontWeight.Bold,
                )
            else Text(title, fontWeight = FontWeight.Bold)
        },
        navigationIcon = {
            if (hasBack) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back_24dp),
                        stringResource(R.string.general_back),
                    )
                }
            }
        },
        actions = {
            // View options: grid columns, grouping, sorting — one overflow menu (Apple 减法).
            Box {
                IconButton(onClick = { viewMenuExpanded = true }) {
                    Icon(
                        painterResource(android.R.drawable.ic_menu_more),
                        stringResource(R.string.usb_view_options),
                    )
                }
                DropdownMenu(
                    expanded = viewMenuExpanded,
                    onDismissRequest = { viewMenuExpanded = false },
                ) {
                    Text(
                        stringResource(R.string.usb_view_grid),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    listOf(2, 3, 4).forEach { cols ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.usb_grid_columns_n, cols)) },
                            onClick = {
                                viewMenuExpanded = false
                                onGridChange(cols)
                            },
                            trailingIcon = {
                                if (gridColumns == cols)
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                            },
                        )
                    }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.usb_view_grouping),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    listOf(
                            UsbSyncPreferences.PhotoGrouping.BY_FOLDER to
                                stringResource(R.string.usb_grouping_folder),
                            UsbSyncPreferences.PhotoGrouping.BY_DATE to
                                stringResource(R.string.usb_grouping_date),
                            UsbSyncPreferences.PhotoGrouping.FLAT to
                                stringResource(R.string.usb_grouping_flat),
                        )
                        .forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewMenuExpanded = false
                                    onGroupingChange(mode)
                                },
                                trailingIcon = {
                                    if (grouping == mode)
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                },
                            )
                        }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.usb_view_sorting),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    listOf(
                            UsbSyncPreferences.PhotoSorting.DATE_DESC to
                                stringResource(R.string.usb_sorting_newest),
                            UsbSyncPreferences.PhotoSorting.NAME_ASC to
                                stringResource(R.string.usb_sorting_name),
                            UsbSyncPreferences.PhotoSorting.SIZE_DESC to
                                stringResource(R.string.usb_sorting_size),
                        )
                        .forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewMenuExpanded = false
                                    onSortingChange(mode)
                                },
                                trailingIcon = {
                                    if (sorting == mode)
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                },
                            )
                        }
                }
            }
            if (selectionCount > 0) {
                androidx.compose.material3.TextButton(onClick = onDeselectAll) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
            if (showSettings) {
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        painterResource(R.drawable.ic_settings_24dp),
                        stringResource(R.string.settings_title),
                    )
                }
            }
            if (showLogs) {
                IconButton(onClick = onLogsClick) {
                    Icon(
                        painterResource(android.R.drawable.ic_menu_manage),
                        stringResource(R.string.label_logs),
                    )
                }
            }
        },
    )
}

// ── Disconnected / Connecting / Loading ────────────────────────────────────

@Composable
internal fun DisconnectedContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_usb_24dp),
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.usb_prompt_connect),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.usb_prompt_connect_desc),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun ConnectingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.usb_status_connecting), fontSize = 15.sp)
        }
    }
}

@Composable
internal fun LoadingContent(state: GalleryState.Loading) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.progress.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.usb_loading_scanned, state.progress, state.total),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(
                    state.message,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Browsing ───────────────────────────────────────────────────────────────

/**
 * Narrow contract the browsing UI needs from its host (implemented by [GalleryViewModel]).
 *
 * Kept separate from the concrete ViewModel so the gallery states can be rendered in `@Preview`
 * with a fake host — a real ViewModel can't be constructed outside an app context (R15, P5-3).
 */
internal interface GalleryScreenHost {
    val filterMode: PhotoFilter
    val groupingMode: UsbSyncPreferences.PhotoGrouping
    val gridColumns: Int
    val selectedCount: Int
    val isRefreshing: Boolean
    val bitmapCache: MutableMap<Int, Bitmap>

    fun getNewPhotoCount(): Int

    fun getFilteredGroups(): List<GalleryEntry.PhotoGroup>

    fun isGroupSelected(group: GalleryEntry.PhotoGroup): Boolean

    fun isGroupImported(group: GalleryEntry.PhotoGroup): Boolean

    fun toggleSelection(group: GalleryEntry.PhotoGroup)

    fun setFilter(mode: PhotoFilter)

    fun refresh()

    fun getOrientation(handle: Int): Int?

    fun getThumbnail(handle: Int): ByteArray?

    /** Preloads thumbnails for the given handles (visible grid window; R28). */
    fun preloadThumbnails(handles: List<Int>)

    suspend fun downloadFullPhoto(handle: Int): File?
}

// ── Empty / Error / Transfer ───────────────────────────────────────────────

@Composable
internal fun EmptyCameraContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_photo_camera_24dp),
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.usb_empty_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.usb_empty_subtitle),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_error_24dp),
                null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.usb_action_retry)) }
        }
    }
}

// ── Storage Status Bar ─────────────────────────────────────────────────────

@Composable
internal fun StorageStatusBar(storages: List<NikonUsbManager.StorageInfo>) {
    if (storages.isEmpty()) return
    val totalBytes = storages.sumOf { it.maxCapacity }
    val freeBytes = storages.sumOf { it.freeSpace }
    if (totalBytes <= 0) return

    val usedBytes = totalBytes - freeBytes
    val ratio = usedBytes.toFloat() / totalBytes
    val color =
        when {
            ratio < 0.8f -> MaterialTheme.colorScheme.primary
            ratio < 0.9f -> Color(0xFFFFA000) // amber
            else -> MaterialTheme.colorScheme.error
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(android.R.drawable.ic_menu_save),
            null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(
                R.string.usb_storage_used,
                formatFileSize(usedBytes),
                formatFileSize(totalBytes),
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ratio > 0.9f) {
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.usb_storage_low),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.weight(1f))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.width(80.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

// ── Filter Chips Row ───────────────────────────────────────────────────────

@Suppress("EmptyFunctionBlock") // actions are intentionally no-ops in a fake
internal class PreviewGalleryHost(private val photos: List<GalleryEntry.PhotoGroup>) :
    GalleryScreenHost {
    override val filterMode: PhotoFilter = PhotoFilter.NEW
    override val groupingMode: UsbSyncPreferences.PhotoGrouping =
        UsbSyncPreferences.PhotoGrouping.BY_FOLDER
    override val gridColumns: Int = 3
    override val selectedCount: Int = 0
    override val isRefreshing: Boolean = false
    override val bitmapCache: MutableMap<Int, Bitmap> = mutableMapOf()

    override fun getNewPhotoCount(): Int = photos.size

    override fun getFilteredGroups(): List<GalleryEntry.PhotoGroup> = photos

    override fun isGroupSelected(group: GalleryEntry.PhotoGroup): Boolean = false

    override fun isGroupImported(group: GalleryEntry.PhotoGroup): Boolean = false

    override fun toggleSelection(group: GalleryEntry.PhotoGroup) {}

    override fun setFilter(mode: PhotoFilter) {}

    override fun refresh() {}

    override fun getOrientation(handle: Int): Int? = null

    override fun getThumbnail(handle: Int): ByteArray? = null

    override fun preloadThumbnails(handles: List<Int>) {}

    override suspend fun downloadFullPhoto(handle: Int): File? = null
}
