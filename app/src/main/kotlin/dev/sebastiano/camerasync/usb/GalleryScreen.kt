package dev.sebastiano.camerasync.usb

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import dev.sebastiano.camerasync.R
import dev.sebastiano.camerasync.ui.theme.CameraSyncTheme
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val selectionCount = viewModel.selectedCount
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
                selectionCount = selectionCount,
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
                visible = s is GalleryState.Browsing && selectionCount > 0,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                BottomAppBar {
                    Button(
                        onClick = { showPreview = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.usb_action_transfer_count, selectionCount))
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
private fun GalleryTopBar(
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
private fun DisconnectedContent() {
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
private fun ConnectingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.usb_status_connecting), fontSize = 15.sp)
        }
    }
}

@Composable
private fun LoadingContent(state: GalleryState.Loading) {
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

    suspend fun downloadFullPhoto(handle: Int): File?
}

@Composable
private fun BrowsingContent(
    state: GalleryState.Browsing,
    host: GalleryScreenHost,
    isRoot: Boolean,
    onFolderClick: (GalleryEntry.Folder) -> Unit = {},
    onTransferAllNew: () -> Unit = {},
) {
    val entries = state.entries
    val photos = entries.filterIsInstance<GalleryEntry.PhotoGroup>()
    val folders = entries.filterIsInstance<GalleryEntry.Folder>()
    val dateSections = entries.filterIsInstance<GalleryEntry.DateSection>()

    if (entries.isEmpty()) {
        EmptyCameraContent()
        return
    }

    val haptic = LocalHapticFeedback.current
    val rawCount = photos.count { it.hasRaw }
    val jpgCount = photos.count { it.jpg != null }
    val newCount = host.getNewPhotoCount()
    val filteredPhotos = host.getFilteredGroups()
    val isFlatMode = host.groupingMode != UsbSyncPreferences.PhotoGrouping.BY_FOLDER

    var detailGroup by remember { mutableStateOf<GalleryEntry.PhotoGroup?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        StorageStatusBar(state.storages)
        FilterChipsRow(
            currentFilter = host.filterMode,
            newCount = newCount,
            rawCount = rawCount,
            jpgCount = jpgCount,
            onFilterChange = host::setFilter,
        )

        // Primary CTA: transfer all new photos in one tap (P1-2). Root view only — the folder view
        // keeps selection-driven transfer via the bottom bar.
        if (isRoot && newCount > 0) {
            Button(
                onClick = onTransferAllNew,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.usb_transfer_all_new, newCount), fontSize = 16.sp)
            }
        }

        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { host.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(host.gridColumns),
                contentPadding = PaddingValues(bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalItemSpacing = 2.dp,
            ) {
                // Device info — full width (root only)
                if (isRoot) {
                    state.cameraInfo?.let { info ->
                        item(span = StaggeredGridItemSpan.FullLine) {
                            DeviceInfoCard(info, state.storages)
                        }
                    }
                }

                // Folders section — full width (BY_FOLDER mode only)
                if (folders.isNotEmpty() && !isFlatMode) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Text(
                            stringResource(R.string.usb_label_folders),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    for (i in folders.indices) {
                        val folder = folders[i]
                        item(
                            key = "f_${folder.storageId}_${folder.info.handle}",
                            span = StaggeredGridItemSpan.FullLine,
                        ) {
                            FolderRow(folder, onClick = { onFolderClick(folder) })
                        }
                    }
                }

                // BY_DATE mode: render date sections + photos interleaved
                if (dateSections.isNotEmpty()) {
                    for (section in dateSections) {
                        item(key = "ds_${section.date}", span = StaggeredGridItemSpan.FullLine) {
                            Text(
                                "${section.date}  (${section.count})",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        // Find photos for this date section
                        val datePhotos =
                            filteredPhotos.filter { group ->
                                val ts =
                                    maxOf(
                                        group.raw?.dateModified ?: 0L,
                                        group.jpg?.dateModified ?: 0L,
                                    )
                                val dateFmt =
                                    java.text.SimpleDateFormat(
                                        "yyyy-MM-dd",
                                        java.util.Locale.getDefault(),
                                    )
                                dateFmt.format(java.util.Date(ts)) == section.date
                            }
                        items(datePhotos, key = { it.baseName }) { group ->
                            PhotoCell(
                                group = group,
                                isSelected = host.isGroupSelected(group),
                                isImported = host.isGroupImported(group),
                                onToggle = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    host.toggleSelection(group)
                                },
                                getThumbnail = host::getThumbnail,
                                getOrientation = host::getOrientation,
                                bitmapCache = host.bitmapCache,
                                onPhotoClick = {
                                    if (host.selectedCount > 0) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        host.toggleSelection(group)
                                    } else {
                                        detailGroup = group
                                    }
                                },
                            )
                        }
                    }
                } else {
                    // BY_FOLDER or FLAT: show photos
                    if (filteredPhotos.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Text(
                                stringResource(R.string.usb_section_photos, filteredPhotos.size),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }

                    items(filteredPhotos, key = { it.baseName }) { group ->
                        PhotoCell(
                            group = group,
                            isSelected = host.isGroupSelected(group),
                            isImported = host.isGroupImported(group),
                            onToggle = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                host.toggleSelection(group)
                            },
                            getThumbnail = host::getThumbnail,
                            getOrientation = host::getOrientation,
                            bitmapCache = host.bitmapCache,
                            onPhotoClick = {
                                if (host.selectedCount > 0) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    host.toggleSelection(group)
                                } else {
                                    detailGroup = group
                                }
                            },
                        )
                    }
                }
            } // LazyVerticalStaggeredGrid
        } // PullToRefreshBox
    } // Column

    detailGroup?.let { group ->
        val photo = group.jpg ?: group.raw ?: return@let
        val handle = group.previewHandle ?: return@let
        val thumbBytes = host.getThumbnail(handle)
        val orientation = host.getOrientation(handle)
        PhotoDetailSheet(
            onDownloadFullPhoto = host::downloadFullPhoto,
            photoInfo = photo,
            thumbnailBytes = thumbBytes,
            orientationFallback = orientation,
            onDismiss = { detailGroup = null },
        )
    }
}

// ── Device Info Card ───────────────────────────────────────────────────────

@Composable
private fun DeviceInfoCard(
    info: NikonUsbManager.CameraInfo,
    storages: List<NikonUsbManager.StorageInfo>,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable {
            expanded = !expanded
        },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_photo_camera_48dp),
                    null,
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${info.manufacturer} ${info.model}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    val storageLine =
                        storages.joinToString("  ") {
                            "${it.description}  ${formatFileSize(it.freeSpace)} / ${formatFileSize(it.maxCapacity)}"
                        }
                    Text(
                        storageLine,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    painterResource(
                        if (expanded) R.drawable.ic_collapse_24dp else R.drawable.ic_expand_24dp
                    ),
                    null,
                    Modifier.size(20.dp).padding(top = 4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    info.serialNumber?.let {
                        DetailRow(stringResource(R.string.usb_info_serial), it)
                    }
                    info.deviceVersion?.let {
                        DetailRow(stringResource(R.string.usb_label_firmware), it)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Folder Row ─────────────────────────────────────────────────────────────

@Composable
private fun FolderRow(folder: GalleryEntry.Folder, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clickable {
            onClick()
        },
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_filter_list_24dp),
                null,
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                folder.info.name,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(R.drawable.ic_arrow_back_24dp),
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

// ── Thumbnail Image ────────────────────────────────────────────────────────

/**
 * Loads and displays an MTP thumbnail for [handle].
 *
 * Uses [bitmapCache] (in ViewModel) to avoid re-decoding + re-rotating when LazyGrid recycles
 * cells. Falls back to [getThumbnail] + [BitmapFactory] for cache misses.
 */
@Composable
private fun ThumbnailImage(
    handle: Int,
    getThumbnail: suspend (Int) -> ByteArray?,
    getOrientation: (Int) -> Int?,
    bitmapCache: MutableMap<Int, Bitmap>,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadingFailed by remember { mutableStateOf(false) }

    LaunchedEffect(handle) {
        // Check decoded bitmap cache first — instant when the cell was recycled.
        val cached = bitmapCache[handle]
        if (cached != null) {
            thumb = cached.asImageBitmap()
            return@LaunchedEffect
        }

        val bytes = withContext(Dispatchers.IO) { getThumbnail(handle) }
        if (bytes == null) {
            loadingFailed = true
            return@LaunchedEffect
        }

        val raw =
            withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                ?: run {
                    loadingFailed = true
                    return@LaunchedEffect
                }

        val fallback = getOrientation(handle)
        val needsRotation =
            fallback == null ||
                when (fallback) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270 -> raw.width > raw.height
                    else -> true
                }

        val rotated =
            withContext(Dispatchers.IO) {
                if (needsRotation) rotateByExif(raw, bytes, fallback) else raw
            }
        // Store decoded bitmap for recycling cells.
        bitmapCache[handle] = rotated
        thumb = rotated.asImageBitmap()
    }

    if (thumb != null) {
        Image(
            bitmap = thumb!!,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else if (loadingFailed) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

// ── Photo Cell ─────────────────────────────────────────────────────────────

@Composable
private fun PhotoCell(
    group: GalleryEntry.PhotoGroup,
    isSelected: Boolean,
    isImported: Boolean = false,
    onToggle: () -> Unit,
    getThumbnail: suspend (Int) -> ByteArray?,
    getOrientation: (Int) -> Int?,
    bitmapCache: MutableMap<Int, Bitmap>,
    onPhotoClick: (() -> Unit)? = null,
) {
    val handle = group.previewHandle ?: return
    val cachedOri = getOrientation(handle)

    // Compute initial aspect ratio from the actual full-resolution dimensions
    // (imagePixWidth/Height from MtpObjectInfo). These reflect the real photo
    // proportions (3:2 for Nikon Z30: 5568×3712 landscape, 3712×5568 portrait).
    // thumbPix is 160×120 (4:3) which would make all cells slightly too square.
    val photoInfo = group.jpg ?: group.raw
    val imgW = photoInfo?.imagePixWidth ?: 0
    val imgH = photoInfo?.imagePixHeight ?: 0
    val thumbW = photoInfo?.thumbPixWidth ?: 0
    val thumbH = photoInfo?.thumbPixHeight ?: 0
    val pixW = if (imgW > 0) imgW else thumbW
    val pixH = if (imgH > 0) imgH else thumbH
    val baseAspect =
        when {
            cachedOri != null -> orientationToAspect(cachedOri, pixW, pixH)
            pixW > 0 && pixH > 0 -> {
                val rawAspect = pixW.toFloat() / pixH.toFloat()
                // When orientation is unknown: imagePix on Z30 always reports sensor
                // dimensions (5568×3712, landscape). If thumbPix says portrait
                // (120×160, width < height), override to prevent landscape-shaped
                // cells for portrait photos.
                val hasPortraitThumb = thumbW > 0 && thumbH > 0 && thumbW < thumbH
                if (rawAspect > 1f && hasPortraitThumb) {
                    thumbW.toFloat() / thumbH.toFloat()
                } else {
                    rawAspect
                }
            }
            else -> 3f / 2f
        }

    // Use ONLY the calculated base aspect — never let the thumbnail's pixel
    // dimensions override the correct 3:2 ratio from imagePix. The MTP thumbnail
    // is 160×120 (4:3), not 3:2, so letting it change the cell size would make
    // all cells slightly too square and cause visible re-layout flicker.
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(baseAspect)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = { onPhotoClick?.invoke() },
                    onLongClick = { onToggle() },
                )
    ) {
        ThumbnailImage(
            handle = handle,
            getThumbnail = getThumbnail,
            getOrientation = getOrientation,
            bitmapCache = bitmapCache,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        if (isSelected) {
            Box(
                Modifier.fillMaxSize()
                    .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            )

            Box(
                Modifier.align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Already-imported indicator — subtle green badge at top-right
        if (isImported && !isSelected) {
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .background(Color(0xFF4CAF50).copy(alpha = 0.85f), CircleShape),
                Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Rotates [bitmap] according to EXIF orientation in [jpegBytes]. If the thumbnail's own EXIF says
 * NORMAL (or is missing) but we have [fallbackOrientation] (e.g. extracted earlier from the JPEG
 * counterpart), the fallback is used instead.
 */
internal fun rotateByExif(
    bitmap: Bitmap,
    jpegBytes: ByteArray,
    fallbackOrientation: Int? = null,
): Bitmap {
    return try {
        val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
        var orientation =
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        // If the thumbnail doesn't specify a rotation but we have prior knowledge
        // that the original photo IS rotated, use the fallback.
        if (
            orientation == ExifInterface.ORIENTATION_NORMAL &&
                fallbackOrientation != null &&
                fallbackOrientation != ExifInterface.ORIENTATION_NORMAL
        ) {
            orientation = fallbackOrientation
        }
        rotateByDegrees(bitmap, orientationToDegrees(orientation))
    } catch (_: Exception) {
        // EXIF read failed (e.g. TIFF thumbnail). Use fallback if available.
        rotateByDegrees(bitmap, orientationToDegrees(fallbackOrientation))
    }
}

/** Converts an EXIF orientation constant to clockwise rotation degrees (0 when not rotated). */
internal fun orientationToDegrees(orientation: Int?): Float =
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

/** Rotates [bitmap] by [degrees] clockwise, returning the same instance when [degrees] is 0. */
internal fun rotateByDegrees(bitmap: Bitmap, degrees: Float): Bitmap =
    if (degrees == 0f) bitmap
    else
        Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(degrees) },
            true,
        )

/**
 * Converts an EXIF orientation to an aspect ratio (width/height).
 *
 * [pixW]/[pixH] are the full-resolution pixel dimensions (imagePixWidth/Height from MtpObjectInfo,
 * NOT thumbPix). For rotated orientations, the dimensions are swapped so the aspect ratio reflects
 * the DISPLAY orientation.
 */
private fun orientationToAspect(orientation: Int, pixW: Int, pixH: Int): Float {
    if (pixW > 0 && pixH > 0) {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270 -> pixH.toFloat() / pixW.toFloat()
            else -> pixW.toFloat() / pixH.toFloat()
        }
    }
    // Fallback: 3:2 landscape or 2:3 portrait
    return when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270 -> 2f / 3f
        else -> 3f / 2f
    }
}

// ── Empty / Error / Transfer ───────────────────────────────────────────────

@Composable
private fun EmptyCameraContent() {
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
private fun ErrorContent(message: String, onRetry: () -> Unit) {
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
private fun StorageStatusBar(storages: List<NikonUsbManager.StorageInfo>) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(
    currentFilter: PhotoFilter,
    newCount: Int,
    rawCount: Int,
    jpgCount: Int,
    onFilterChange: (PhotoFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = currentFilter == PhotoFilter.ALL,
            onClick = { onFilterChange(PhotoFilter.ALL) },
            label = { Text(stringResource(R.string.usb_filter_all), fontSize = 13.sp) },
        )
        FilterChip(
            selected = currentFilter == PhotoFilter.NEW,
            onClick = { onFilterChange(PhotoFilter.NEW) },
            label = {
                Text("${stringResource(R.string.usb_filter_new)} ($newCount)", fontSize = 13.sp)
            },
        )
        if (rawCount > 0) {
            FilterChip(
                selected = currentFilter == PhotoFilter.RAW_ONLY,
                onClick = { onFilterChange(PhotoFilter.RAW_ONLY) },
                label = {
                    Text("${stringResource(R.string.usb_filter_raw)} ($rawCount)", fontSize = 13.sp)
                },
            )
        }
        if (jpgCount > 0) {
            FilterChip(
                selected = currentFilter == PhotoFilter.JPEG_ONLY,
                onClick = { onFilterChange(PhotoFilter.JPEG_ONLY) },
                label = {
                    Text(
                        "${stringResource(R.string.usb_filter_jpeg)} ($jpgCount)",
                        fontSize = 13.sp,
                    )
                },
            )
        }
    }
}

@Composable
private fun TransferringContent(s: GalleryState.Transferring) {
    val p = s.progress
    val speedText =
        when {
            p.speedBps >= 1_000_000 ->
                stringResource(R.string.transfer_speed_mb_per_s, p.speedBps / 1_000_000)
            p.speedBps >= 1_000 ->
                stringResource(R.string.transfer_speed_kb_per_s, (p.speedBps / 1_000).toInt())
            p.speedBps > 0 -> stringResource(R.string.transfer_speed_b_per_s, p.speedBps)
            else -> stringResource(R.string.transfer_calculating)
        }
    val etaText =
        when {
            p.etaSeconds < 0 -> stringResource(R.string.transfer_calculating)
            p.etaSeconds < 60 -> stringResource(R.string.transfer_eta_seconds, p.etaSeconds)
            else ->
                stringResource(
                    R.string.transfer_eta_minutes_seconds,
                    p.etaSeconds / 60,
                    p.etaSeconds % 60,
                )
        }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.usb_transferring_file, p.currentFile),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (p.total > 0) p.synced.toFloat() / p.total else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${p.synced} / ${p.total}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (p.speedBps > 0) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        speedText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        etaText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferDoneContent(
    s: GalleryState.TransferDone,
    failedHandles: List<Int>,
    lastTransferredHandles: List<Int>,
    onDismiss: () -> Unit,
    onRetryFailed: () -> Unit,
    onDeleteTransferred: suspend (List<Int>) -> Int,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val deleteSuccessTemplate = stringResource(R.string.usb_delete_success)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        sheetState.show()
    }

    if (sheetState.isVisible) {
        ModalBottomSheet(
            onDismissRequest = { if (showSummary) showSummary = false else onDismiss() },
            sheetState = sheetState,
        ) {
            if (showSummary) {
                TransferSummaryContent(savedUris = s.savedUris, onClose = { showSummary = false })
            } else {
                TransferDonePanel(
                    s = s,
                    failedHandles = failedHandles,
                    onContinue = onDismiss,
                    onRetryFailed = onRetryFailed,
                    onViewSummary = { showSummary = true },
                    onRequestDelete = { showDeleteConfirm = true },
                )
            }
        }
    }

    // Confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    stringResource(R.string.usb_delete_confirm_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = { Text(stringResource(R.string.usb_delete_confirm_body, s.synced)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch {
                            val deleted = onDeleteTransferred(lastTransferredHandles)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                        context,
                                        deleteSuccessTemplate.format(deleted),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                        }
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(stringResource(R.string.usb_delete_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            },
        )
    }
}

/**
 * The transfer-complete panel body (pure rendering — no sheet/dialog plumbing), kept separate so
 * the TransferDone state can be rendered in `@Preview` (R15, P5-3).
 */
@Composable
private fun TransferDonePanel(
    s: GalleryState.TransferDone,
    failedHandles: List<Int>,
    onContinue: () -> Unit,
    onRetryFailed: () -> Unit,
    onViewSummary: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.usb_share_chooser_title)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✨", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.usb_transfer_complete_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.usb_transfer_complete_count, s.synced),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        if (s.savedUris.isNotEmpty()) {
            OutlinedButton(onClick = onViewSummary, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(android.R.drawable.ic_menu_agenda), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.usb_action_view_summary))
            }
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val intent =
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(s.savedUris.first(), "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painterResource(android.R.drawable.ic_menu_gallery),
                    null,
                    Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.usb_action_view_in_gallery))
            }
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val intent =
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(s.savedUris))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(painterResource(android.R.drawable.ic_menu_share), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.usb_action_share))
            }
            Spacer(Modifier.height(8.dp))

            OutlinedButton(onClick = onRequestDelete, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(android.R.drawable.ic_menu_delete), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.usb_action_delete_from_camera))
            }
        }

        if (failedHandles.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetryFailed, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(android.R.drawable.ic_menu_revert), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.usb_retry_failed, failedHandles.size))
            }
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onContinue) {
            Text(stringResource(R.string.usb_action_continue_browsing))
        }
    }
}

// ── Per-session transfer summary (P1-3) ───────────────────────────────────

/**
 * List of files saved in the last transfer session. Each row shows a thumbnail + display name and
 * opens the photo in the system gallery (album locate). Shown inside the transfer-done sheet when
 * the user taps "本次传输清单".
 */
@Composable
private fun TransferSummaryContent(savedUris: List<Uri>, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.usb_summary_title, savedUris.size),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                    stringResource(R.string.general_back),
                )
            }
        }
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(bottom = 24.dp)) {
            items(savedUris, key = { it.toString() }) { uri -> TransferSummaryRow(uri) }
        }
    }
}

@Composable
private fun TransferSummaryRow(uri: Uri) {
    val context = LocalContext.current
    val fileName by
        produceState(initialValue = "", key1 = uri) { value = queryMediaDisplayName(context, uri) }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable {
                    runCatching {
                        val intent =
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        context.startActivity(intent)
                    }
                }
                .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(uri).size(72).build(),
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            fileName.ifBlank { uri.lastPathSegment ?: "photo" },
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painterResource(android.R.drawable.ic_menu_gallery),
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Resolves the [MediaStore] display name for a saved image [Uri], or "" if unavailable. */
private fun queryMediaDisplayName(context: Context, uri: Uri): String =
    runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else ""
                    } else ""
                } ?: ""
        }
        .getOrDefault("")

// ── Transfer Preview Sheet ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferPreviewSheet(
    viewModel: GalleryViewModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Collect selected photo groups for preview.
    // Use currentPhotos (unfiltered) so the preview shows ALL selected
    // photos regardless of the active filter chip (e.g. "仅 RAW").
    val selectedGroups =
        viewModel.currentPhotos
            .filter { viewModel.isGroupSelected(it) }
            .take(6) // show first 6 thumbnails

    val totalGroups = selectedGroups.size

    // Compute total size of all selected photos (unfiltered).
    val allSelectedPhotos =
        viewModel.currentPhotos
            .flatMap { listOfNotNull(it.raw, it.jpg) }
            .filter { photo -> viewModel.isSelected(photo.handle) }
            .distinctBy { it.handle }
    val totalSize = allSelectedPhotos.sumOf { it.size }
    val rawCount = allSelectedPhotos.count { it.formatName == "NEF(RAW)" }
    val jpgCount = allSelectedPhotos.count { it.formatName in setOf("JPEG", "EXIF_JPEG") }

    LaunchedEffect(Unit) { sheetState.show() }

    if (sheetState.isVisible) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Column(
                modifier =
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)
            ) {
                Text(
                    stringResource(R.string.usb_preview_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.usb_preview_summary,
                        allSelectedPhotos.size,
                        totalGroups,
                    ),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Format breakdown
                if (rawCount > 0 || jpgCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (jpgCount > 0)
                            Text(
                                "$jpgCount JPEG",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        if (rawCount > 0)
                            Text(
                                "$rawCount RAW",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }
                Text(
                    stringResource(R.string.usb_preview_total_size, formatFileSize(totalSize)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )

                // Thumbnail previews
                if (selectedGroups.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.usb_label_preview),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for (group in selectedGroups) {
                            val handle = group.previewHandle ?: continue
                            ThumbnailImage(
                                handle = handle,
                                getThumbnail = viewModel::getThumbnail,
                                getOrientation = viewModel::getOrientation,
                                bitmapCache = viewModel.bitmapCache,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        // "+N more" indicator
                        val remaining = totalGroups - selectedGroups.size
                        if (remaining > 0) {
                            Box(
                                modifier =
                                    Modifier.size(56.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "+$remaining",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Confirm / Cancel buttons
                Button(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onConfirm()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painterResource(android.R.drawable.ic_menu_save),
                        null,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.usb_action_start_transfer))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        }
    }
}

// ── Tab Content ─────────────────────────────────────────────────────────────

@Composable
private fun CameraTabContent(
    s: GalleryState,
    viewModel: GalleryViewModel,
    inFolder: Boolean,
    onFolderClick: (GalleryEntry.Folder) -> Unit,
    onNavigateBack: () -> Unit,
    onTransferAllNew: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        // Inline error banner — overlays on top instead of replacing the screen
        viewModel.errorBanner?.let { msg ->
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_error_24dp),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(msg, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 3)
                    IconButton(
                        onClick = { viewModel.clearErrorBanner() },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_close_24dp),
                            contentDescription = stringResource(R.string.content_desc_close),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        // Content
        when (s) {
            is GalleryState.Disconnected -> {
                if (inFolder) LaunchedEffect(Unit) { onNavigateBack() } else DisconnectedContent()
            }
            is GalleryState.Connecting -> {
                if (!inFolder) ConnectingContent()
            }
            is GalleryState.Loading -> LoadingContent(s)
            is GalleryState.Browsing ->
                BrowsingContent(s, viewModel, isRoot = !inFolder, onFolderClick, onTransferAllNew)
            is GalleryState.Empty -> EmptyCameraContent()
            is GalleryState.Error -> ErrorContent(s.message, viewModel::start)
            is GalleryState.Transferring -> TransferringContent(s)
            is GalleryState.TransferDone ->
                TransferDoneContent(
                    s = s,
                    failedHandles = viewModel.failedHandles,
                    lastTransferredHandles = viewModel.lastTransferredHandles,
                    onDismiss = viewModel::dismissTransferDone,
                    onRetryFailed = viewModel::retryFailedTransfers,
                    onDeleteTransferred = viewModel::deleteTransferredPhotos,
                )
        }
    }
}

// ── Local Photos Tab ────────────────────────────────────────────────────────

/**
 * Tab content for locally-stored photos under Pictures/CameraSync.
 *
 * Modes:
 * - **Folder list** (default): shows sub-directories as cards with photo counts.
 * - **Photo grid**: shown when user enters a folder — [AsyncImage]-backed grid.
 *
 * Uses Coil for all image loading — no hand-rolled [BitmapFactory].
 */
@Composable
private fun LocalTabContent(localVm: LocalPhotosViewModel, gridColumns: Int) {
    val folders = localVm.folders
    val groups = localVm.groups
    val loadState = localVm.loading.value
    val isRefreshing = localVm.isRefreshing
    val isBrowsingFolder = localVm.isBrowsingFolder
    val hasNoContent = folders.isEmpty() && groups.isEmpty()
    var detailGroup by remember { mutableStateOf<LocalPhotoGroup?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Breadcrumb (shown when inside a folder) ──
        if (isBrowsingFolder) {
            LocalBreadcrumb(
                currentPath = localVm.currentPath ?: "",
                onBack = { localVm.goBack() },
                onRefresh = { localVm.refresh() },
            )
        }

        if (loadState && !isRefreshing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (hasNoContent && !isRefreshing && !isBrowsingFolder) {
            // Root-level empty state
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.local_empty_title),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (isBrowsingFolder && hasNoContent) {
            // Folder-level empty state
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.local_empty_folder_title),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { localVm.refresh() }) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalItemSpacing = 2.dp,
                ) {
                    // ── Folder cards (only at root level when not browsing a folder) ──
                    if (!isBrowsingFolder && folders.isNotEmpty()) {
                        items(
                            folders,
                            key = { it.relativePath },
                            span = { StaggeredGridItemSpan.FullLine },
                        ) { folder ->
                            LocalFolderCell(
                                folder = folder,
                                onClick = { localVm.enterFolder(folder.relativePath) },
                            )
                        }
                    }

                    // ── Photo grid ──
                    items(groups, key = { it.cacheKey }) { group ->
                        LocalPhotoCell(group = group, onClick = { detailGroup = group })
                    }
                }
            }
        }
    }

    // Detail bottom sheet
    detailGroup?.let { group ->
        LocalPhotoDetail(group = group, onDismiss = { detailGroup = null })
    }
}

/** Breadcrumb bar showing current folder path with back button. */
@Composable
private fun LocalBreadcrumb(currentPath: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    val displayPath =
        currentPath.trimEnd('/').removePrefix("Pictures/CameraSync/").ifEmpty { "CameraSync" }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Text("←", fontSize = 18.sp)
        }
        Text(
            displayPath,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
            Text("↻", fontSize = 18.sp)
        }
    }
}

/** A tappable card representing a sub-folder with its photo count. */
@Composable
private fun LocalFolderCell(folder: LocalFolder, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(4.dp).combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📁", fontSize = 32.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                folder.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.label_photo_count, folder.photoCount),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Photo cell powered by Coil [AsyncImage]. Replaces the old hand-rolled [BitmapFactory] approach.
 *
 * Key improvements:
 * - Coil handles decoding, downsampling, memory cache, and EXIF orientation automatically (via
 *   [ExifInterface] built into its decoder).
 * - [AsyncImage] is lifecycle-aware via [LazyVerticalStaggeredGrid] — requests are cancelled when
 *   scrolled off-screen.
 * - Fallback to grey placeholder on decode failure (e.g., corrupted NEF preview).
 */
@Composable
private fun LocalPhotoCell(group: LocalPhotoGroup, onClick: () -> Unit) {
    val file = group.displayFile

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(3f / 2f) // reasonable default for camera photos
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(file).size(360).build(),
            contentDescription = group.baseName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            // Show a subdued placeholder while loading or on error
            placeholder =
                rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalContext.current).data(file).size(60).build()
                ),
            error =
                rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalContext.current).data(file).size(60).build()
                ),
        )

        // RAW badge
        if (group.raw != null) {
            Box(
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text("RAW", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Local Photo Detail ──────────────────────────────────────────────────────

/**
 * Detail bottom sheet for a local photo. Uses Coil [AsyncImage] for the full-resolution preview and
 * [ExifInterface] (path-based, no full read) for metadata.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalPhotoDetail(group: LocalPhotoGroup, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val file = group.jpg?.file ?: group.raw?.file ?: return

    var exifFields by remember { mutableStateOf<List<Pair<Int, ExifValue?>>>(emptyList()) }
    var exifLoaded by remember { mutableStateOf(false) }

    // Load EXIF in background (path-based constructor — efficient, no full file read)
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            val exif =
                try {
                    ExifInterface(file.absolutePath)
                } catch (_: Exception) {
                    null
                }
            exifFields = extractExifFromInterface(exif)
            exifLoaded = true
        }
    }

    LaunchedEffect(Unit) { sheetState.show() }

    if (sheetState.isVisible) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp)
            ) {
                // Full-resolution preview via Coil
                AsyncImage(
                    model = file,
                    contentDescription = group.baseName,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )

                Spacer(Modifier.height(16.dp))

                Text(group.baseName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    formatFileSize(file.length()),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!exifLoaded) {
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (exifFields.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.usb_exif_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    for ((label, value) in exifFields) {
                        if (label == R.string.usb_exif_filename) continue
                        if (value != null) {
                            val valueText = exifValueText(value)
                            if (valueText.isNotBlank()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        stringResource(label),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(valueText, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A resolved EXIF field value: either a plain string or a string-resource reference. */
internal sealed interface ExifValue {
    data class Text(val value: String) : ExifValue

    data class Resource(val resId: Int, val formatArgs: List<Any> = emptyList()) : ExifValue
}

/** Resolves an [ExifValue] to its display string (composable — invalidates on config changes). */
@Composable
internal fun exifValueText(value: ExifValue): String =
    when (value) {
        is ExifValue.Text -> value.value
        is ExifValue.Resource ->
            if (value.formatArgs.isEmpty()) stringResource(value.resId)
            else stringResource(value.resId, *value.formatArgs.toTypedArray())
    }

/**
 * Extracts human-readable EXIF fields from an already-opened [ExifInterface]. Mirrors [extractExif]
 * but takes the interface directly instead of raw bytes, so local file detail can use the
 * path-based constructor (no 26MB read).
 *
 * Labels are string-resource IDs and values are [ExifValue] (rendered via [exifValueText]) — the
 * extraction stays pure so it needs no [Resources] and can run on any dispatcher.
 */
internal fun extractExifFromInterface(exif: ExifInterface?): List<Pair<Int, ExifValue?>> {
    if (exif == null) return emptyList()
    fun text(value: String?): ExifValue? = value?.let { ExifValue.Text(it) }
    return try {
        listOf(
            R.string.usb_exif_filename to null,
            R.string.usb_exif_resolution to
                text(
                    formatResolution(
                        exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                        exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
                    )
                ),
            R.string.usb_exif_date to
                text(formatExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME))),
            R.string.usb_exif_shutter to
                text(
                    exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                        formatShutterSpeed(it)
                    }
                ),
            R.string.usb_exif_aperture to
                text(
                    exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let {
                        "f/${it.toDoubleOrNull()?.let { v -> "%.1f".format(v) } ?: it}"
                    }
                ),
            R.string.usb_exif_iso to
                text(
                    exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                        ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                ),
            R.string.usb_exif_focal_length to
                text(
                    exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let {
                        it.toDoubleOrNull()?.let { v -> "${"%.0f".format(v)}mm" } ?: "$it mm"
                    }
                ),
            R.string.usb_exif_35mm_equiv to
                text(
                    exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.let {
                        "${it}mm"
                    }
                ),
            R.string.usb_exif_lens to text(exif.getAttribute(ExifInterface.TAG_LENS_MODEL)),
            R.string.usb_exif_exposure_comp to text(formatExposureCompensation(exif)),
            R.string.usb_exif_metering_mode to getMeteringMode(exif),
            R.string.usb_exif_flash to getFlash(exif),
            R.string.usb_exif_orientation to getOrientation(exif),
            R.string.usb_exif_camera to
                text(
                    formatCamera(
                        exif.getAttribute(ExifInterface.TAG_MAKE),
                        exif.getAttribute(ExifInterface.TAG_MODEL),
                    )
                ),
            R.string.usb_exif_artist to text(exif.getAttribute(ExifInterface.TAG_ARTIST)),
            R.string.usb_exif_copyright to text(exif.getAttribute(ExifInterface.TAG_COPYRIGHT)),
            R.string.usb_exif_software to text(exif.getAttribute(ExifInterface.TAG_SOFTWARE)),
        )
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Formats an EXIF DATETIME ("yyyy:MM:dd HH:mm:ss") into a readable form, falling back to the raw
 * value when parsing fails. Extracted from the EXIF builders to keep their nesting shallow.
 */
private fun formatExifDate(raw: String?): String? {
    if (raw == null) return null
    return try {
        val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault()).parse(raw)
        if (parsed != null)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(parsed)
        else raw
    } catch (_: Exception) {
        raw
    }
}

// ── Previews (R15, P5-3) ────────────────────────────────────────────────────

// Each GalleryState gets a @Preview per CLAUDE.md 🔴 rule 2. The state content composables are
// private but live in this file, and the browsing/transfer-done states render against the
// [GalleryScreenHost] contract with a fake host — no ViewModel (needs an app context) required.

/** Preview-only fake of [GalleryScreenHost] — renders gallery states without a ViewModel (P5-3). */
@Suppress("EmptyFunctionBlock") // actions are intentionally no-ops in a fake
private class PreviewGalleryHost(private val photos: List<GalleryEntry.PhotoGroup>) :
    GalleryScreenHost {
    override val filterMode: PhotoFilter = PhotoFilter.NEW
    override val groupingMode: UsbSyncPreferences.PhotoGrouping =
        UsbSyncPreferences.PhotoGrouping.BY_FOLDER
    override val gridColumns: Int = 3
    override val selectedCount: Int = 0
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

    override suspend fun downloadFullPhoto(handle: Int): File? = null
}

private fun previewCameraInfo() =
    NikonUsbManager.CameraInfo("NIKON", "Z30", "S1234", "1.01", emptyList(), emptyList(), null)

private fun previewStorage() =
    NikonUsbManager.StorageInfo(
        id = 1,
        description = "SD CARD",
        maxCapacity = 100_000_000_000L,
        freeSpace = 40_000_000_000L,
    )

private fun previewPhoto(handle: Int, name: String) =
    NikonUsbManager.PhotoInfo(
        handle = handle,
        storageId = 1,
        name = name,
        size = 5_000_000L,
        dateModified = 1_750_000_000_000L,
        formatName = if (name.endsWith(".NEF", true)) "NEF(RAW)" else "JPEG",
        thumbPixWidth = 160,
        thumbPixHeight = 120,
        imagePixWidth = 5568,
        imagePixHeight = 3712,
    )

private fun previewGroups() =
    listOf(
        GalleryEntry.PhotoGroup(
            "DSC_0001",
            previewPhoto(1, "DSC_0001.NEF"),
            previewPhoto(2, "DSC_0001.JPG"),
        ),
        GalleryEntry.PhotoGroup("DSC_0002", null, previewPhoto(3, "DSC_0002.JPG")),
        GalleryEntry.PhotoGroup("DSC_0003", previewPhoto(4, "DSC_0003.NEF"), null),
    )

@Preview(name = "Gallery — Disconnected", showBackground = true)
@Composable
private fun GalleryDisconnectedPreview() {
    CameraSyncTheme { DisconnectedContent() }
}

@Preview(name = "Gallery — Connecting", showBackground = true)
@Composable
private fun GalleryConnectingPreview() {
    CameraSyncTheme { ConnectingContent() }
}

@Preview(name = "Gallery — Loading", showBackground = true)
@Composable
private fun GalleryLoadingPreview() {
    CameraSyncTheme {
        LoadingContent(
            GalleryState.Loading(stringResource(R.string.usb_status_loading_photos), 12, 100)
        )
    }
}

@Preview(name = "Gallery — Browsing", showBackground = true)
@Composable
private fun GalleryBrowsingPreview() {
    val groups = previewGroups()
    CameraSyncTheme {
        BrowsingContent(
            state = GalleryState.Browsing(previewCameraInfo(), listOf(previewStorage()), groups),
            host = PreviewGalleryHost(groups),
            isRoot = true,
        )
    }
}

@Preview(name = "Gallery — Empty", showBackground = true)
@Composable
private fun GalleryEmptyPreview() {
    CameraSyncTheme { EmptyCameraContent() }
}

@Preview(name = "Gallery — Error", showBackground = true)
@Composable
private fun GalleryErrorPreview() {
    CameraSyncTheme {
        ErrorContent(message = stringResource(R.string.usb_error_connect), onRetry = {})
    }
}

@Preview(name = "Gallery — Transferring", showBackground = true)
@Composable
private fun GalleryTransferringPreview() {
    CameraSyncTheme {
        TransferringContent(
            GalleryState.Transferring(
                TransferProgress(
                    synced = 2,
                    total = 5,
                    currentFile = "DSC_0003.NEF",
                    bytesTransferred = 10_000_000L,
                    totalBytes = 25_000_000L,
                    startTimeMillis = System.currentTimeMillis() - 5_000L,
                )
            )
        )
    }
}

@Preview(name = "Gallery — Transfer Done", showBackground = true)
@Composable
private fun GalleryTransferDonePreview() {
    CameraSyncTheme {
        TransferDonePanel(
            s =
                GalleryState.TransferDone(
                    synced = 3,
                    savedUris =
                        listOf(
                            Uri.parse("content://media/external/images/media/1"),
                            Uri.parse("content://media/external/images/media/2"),
                        ),
                ),
            failedHandles = listOf(4),
            onContinue = {},
            onRetryFailed = {},
            onViewSummary = {},
            onRequestDelete = {},
        )
    }
}
