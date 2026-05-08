package dev.sebastiano.camerasync.usb

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import dev.sebastiano.camerasync.R
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Root Gallery Screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onNavigateToLogs: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onFolderClick: (GalleryEntry.Folder) -> Unit = {},
    // ── Folder-mode params (null → root gallery) ──────────────────────────
    storageId: Int? = null,
    folderHandle: Int? = null,
    folderName: String = "",
    onNavigateBack: () -> Unit = {},
) {
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

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
            when (s) {
                is GalleryState.Disconnected -> {
                    if (inFolder) LaunchedEffect(Unit) { onNavigateBack() }
                    else DisconnectedContent()
                }
                is GalleryState.Connecting -> {
                    if (!inFolder) ConnectingContent()
                }
                is GalleryState.Loading -> LoadingContent(s)
                is GalleryState.Browsing ->
                    BrowsingContent(s, viewModel, isRoot = !inFolder, onFolderClick)
                is GalleryState.Empty -> EmptyCameraContent()
                is GalleryState.Error -> ErrorContent(s.message, viewModel::start)
                is GalleryState.Transferring -> TransferringContent(s)
                is GalleryState.TransferDone ->
                    TransferDoneContent(s, viewModel::dismissTransferDone, viewModel)
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
) {
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
            IconButton(
                onClick = {
                    val next =
                        when (gridColumns) {
                            2 -> 3
                            3 -> 4
                            else -> 2
                        }
                    onGridChange(next)
                }
            ) {
                Icon(
                    painterResource(android.R.drawable.ic_menu_view),
                    stringResource(R.string.usb_grid_columns, gridColumns),
                )
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
                    "已扫描 ${state.progress} / ${state.total} 张",
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

@Composable
private fun BrowsingContent(
    state: GalleryState.Browsing,
    vm: GalleryViewModel,
    isRoot: Boolean,
    onFolderClick: (GalleryEntry.Folder) -> Unit = {},
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
    val newCount = vm.getNewPhotoCount()
    val filteredPhotos = vm.getFilteredGroups()
    val isFlatMode = vm.groupingMode != UsbSyncPreferences.PhotoGrouping.BY_FOLDER

    var detailGroup by remember { mutableStateOf<GalleryEntry.PhotoGroup?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        StorageStatusBar(state.storages, batteryLevel = vm.batteryLevel)
        FilterChipsRow(
            currentFilter = vm.filterMode,
            newCount = newCount,
            rawCount = rawCount,
            jpgCount = jpgCount,
            onFilterChange = vm::setFilter,
        )

        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { vm.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(vm.gridColumns),
                contentPadding = PaddingValues(bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalItemSpacing = 4.dp,
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
                                isSelected = vm.isGroupSelected(group),
                                isImported = vm.isGroupImported(group),
                                onToggle = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.toggleSelection(group)
                                },
                                getThumbnail = vm::getThumbnail,
                                getOrientation = vm::getOrientation,
                                onPhotoClick = {
                                    if (vm.selectedCount > 0) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.toggleSelection(group)
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
                            isSelected = vm.isGroupSelected(group),
                            isImported = vm.isGroupImported(group),
                            onToggle = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.toggleSelection(group)
                            },
                            getThumbnail = vm::getThumbnail,
                            getOrientation = vm::getOrientation,
                            onPhotoClick = {
                                if (vm.selectedCount > 0) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.toggleSelection(group)
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
        val thumbBytes = vm.getThumbnail(group.previewHandle)
        val orientation = vm.getOrientation(group.previewHandle)
        PhotoDetailSheet(
            viewModel = vm,
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

@Composable
private fun ThumbnailImage(
    handle: Int,
    getThumbnail: suspend (Int) -> ByteArray?,
    getOrientation: (Int) -> Int?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onAspectReady: ((Float) -> Unit)? = null,
) {
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadingFailed by remember { mutableStateOf(false) }

    LaunchedEffect(handle) {
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
        val rotated = withContext(Dispatchers.IO) { rotateByExif(raw, bytes, fallback) }
        thumb = rotated.asImageBitmap()
        onAspectReady?.invoke(rotated.width.toFloat() / rotated.height.toFloat())
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
    onPhotoClick: (() -> Unit)? = null,
) {
    val handle = group.previewHandle
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
                if (rawAspect > 1f && thumbW > 0 && thumbH > 0 && thumbW < thumbH) {
                    thumbW.toFloat() / thumbH.toFloat()
                } else {
                    rawAspect
                }
            }
            else -> 3f / 2f
        }

    // cellAspect: initially computed from orientation cache / thumbPix dimensions.
    // Once the real thumbnail loads and is rotated, the actual bitmap dimensions
    // take over (stored in loadedAspect). This avoids the staggered grid measuring
    // the cell at a wrong default ratio.
    var loadedAspect by remember { mutableStateOf<Float?>(null) }
    val cellAspect = loadedAspect ?: baseAspect

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(cellAspect)
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
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onAspectReady = { loadedAspect = it },
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
                Text(
                    "✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
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
    fun rotate(deg: Float) =
        if (deg == 0f) bitmap
        else
            Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(deg) }, true,
            )

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
        val degrees =
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        rotate(degrees)
    } catch (_: Exception) {
        // EXIF read failed (e.g. TIFF thumbnail). Use fallback if available.
        val degrees =
            when (fallbackOrientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        rotate(degrees)
    }
}

/** Converts an EXIF orientation to an aspect ratio (width/height).
 *
 * [pixW]/[pixH] are the full-resolution pixel dimensions (imagePixWidth/Height
 * from MtpObjectInfo, NOT thumbPix). For rotated orientations, the dimensions
 * are swapped so the aspect ratio reflects the DISPLAY orientation.
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
private fun StorageStatusBar(
    storages: List<NikonUsbManager.StorageInfo>,
    batteryLevel: Int? = null,
) {
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
        if (batteryLevel != null) {
            Text(
                stringResource(R.string.usb_battery_level, batteryLevel),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        }
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
                        p.speedFormatted,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        p.etaFormatted,
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
    onDismiss: () -> Unit,
    viewModel: GalleryViewModel,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        sheetState.show()
    }

    if (sheetState.isVisible) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Column(
                modifier =
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
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
                                    putParcelableArrayListExtra(
                                        Intent.EXTRA_STREAM,
                                        ArrayList(s.savedUris),
                                    )
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            context.startActivity(
                                Intent.createChooser(
                                    intent,
                                    context.getString(R.string.usb_share_chooser_title),
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painterResource(android.R.drawable.ic_menu_share),
                            null,
                            Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.usb_action_share))
                    }
                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painterResource(android.R.drawable.ic_menu_delete),
                            null,
                            Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.usb_action_delete_from_camera))
                    }
                }

                if (viewModel.failedHandles.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            viewModel.retryFailedTransfers()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painterResource(android.R.drawable.ic_menu_revert),
                            null,
                            Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.usb_retry_failed, viewModel.failedHandles.size)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.usb_action_continue_browsing))
                }
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
                            val deleted =
                                viewModel.deleteTransferredPhotos(viewModel.lastTransferredHandles)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                        context,
                                        context.getString(R.string.usb_delete_success, deleted),
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

    val totalSelected = viewModel.selectedCount
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
                            ThumbnailImage(
                                handle = group.previewHandle,
                                getThumbnail = viewModel::getThumbnail,
                                getOrientation = viewModel::getOrientation,
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
