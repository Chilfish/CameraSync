package dev.sebastiano.camerasync.usb

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import java.io.ByteArrayInputStream
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
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import dev.sebastiano.camerasync.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Root Gallery Screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onNavigateToLogs: () -> Unit,
    onFolderClick: (GalleryEntry.Folder) -> Unit = {},
) {
    DisposableEffect(Unit) { viewModel.start(); onDispose { viewModel.stop() } }

    val s = viewModel.state.value
    val selectionCount = viewModel.selectedCount
    val context = LocalContext.current
    val prefs = remember { UsbSyncPreferences(context) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            GalleryTopBar(
                title = "USB 照片同步",
                hasBack = false,
                showLogs = true,
                selectionCount = selectionCount,
                onBackClick = {},
                onLogsClick = onNavigateToLogs,
                onDeselectAll = viewModel::deselectAll,
                gridColumns = viewModel.gridColumns,
                onGridChange = { viewModel.setGridColumns(it); prefs.setGridColumns(it) },
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
                        onClick = { viewModel.startTransfer() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { Text("传输 $selectionCount 张照片") }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (s) {
                is GalleryState.Disconnected -> DisconnectedContent()
                is GalleryState.Connecting -> ConnectingContent()
                is GalleryState.Loading -> LoadingContent(s.message)
                is GalleryState.Browsing -> BrowsingContent(s, viewModel, isRoot = true, onFolderClick)
                is GalleryState.Empty -> EmptyCameraContent()
                is GalleryState.Error -> ErrorContent(s.message, viewModel::start)
                is GalleryState.Transferring -> TransferringContent(s)
                is GalleryState.TransferDone -> TransferDoneContent(s, viewModel::dismissTransferDone, viewModel)
            }
        }
    }
}

// ── Folder Gallery Screen ──────────────────────────────────────────────────

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
    LaunchedEffect(storageId, folderHandle) {
        viewModel.loadFolder(storageId, folderHandle)
    }

    val s = viewModel.state.value
    val selectionCount = viewModel.selectedCount
    val context = LocalContext.current
    val prefs = remember { UsbSyncPreferences(context) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            GalleryTopBar(
                title = folderName,
                hasBack = true,
                showLogs = false,
                selectionCount = selectionCount,
                onBackClick = { viewModel.deselectAll(); onNavigateBack() },
                onLogsClick = {},
                onDeselectAll = viewModel::deselectAll,
                gridColumns = viewModel.gridColumns,
                onGridChange = { viewModel.setGridColumns(it); prefs.setGridColumns(it) },
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
                        onClick = { viewModel.startTransfer() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { Text("传输 $selectionCount 张照片") }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (s) {
                is GalleryState.Disconnected -> {
                    LaunchedEffect(Unit) { onNavigateBack() }
                }
                is GalleryState.Loading -> LoadingContent(s.message)
                is GalleryState.Browsing -> BrowsingContent(s, viewModel, isRoot = false, onFolderClick)
                is GalleryState.Empty -> EmptyCameraContent()
                is GalleryState.Error -> ErrorContent(s.message, viewModel::start)
                is GalleryState.Transferring -> TransferringContent(s)
                is GalleryState.TransferDone -> TransferDoneContent(s, viewModel::dismissTransferDone, viewModel)
                else -> {}
            }
        }
    }
}

// ── Top Bar ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    title: String,
    hasBack: Boolean,
    showLogs: Boolean,
    selectionCount: Int,
    onBackClick: () -> Unit,
    onLogsClick: () -> Unit,
    onDeselectAll: () -> Unit,
    gridColumns: Int = 3,
    onGridChange: (Int) -> Unit = {},
) {
    TopAppBar(
        title = {
            if (selectionCount > 0) Text("已选 $selectionCount 张", fontWeight = FontWeight.Bold)
            else Text(title, fontWeight = FontWeight.Bold)
        },
        navigationIcon = {
            if (hasBack) {
                IconButton(onClick = onBackClick) {
                    Icon(painterResource(R.drawable.ic_arrow_back_24dp), "返回")
                }
            }
        },
        actions = {
            IconButton(onClick = {
                val next = when (gridColumns) { 2 -> 3; 3 -> 4; else -> 2 }
                onGridChange(next)
            }) {
                Icon(painterResource(android.R.drawable.ic_menu_view), "列数: $gridColumns")
            }
            if (selectionCount > 0) {
                androidx.compose.material3.TextButton(onClick = onDeselectAll) {
                    Text("取消")
                }
            }
            if (showLogs) {
                IconButton(onClick = onLogsClick) {
                    Icon(painterResource(R.drawable.ic_settings_24dp), "设置")
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
            Icon(painterResource(R.drawable.ic_usb_24dp), null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("连接 Nikon Z30", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("通过 USB-C 数据线连接\n插入后将自动识别",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ConnectingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在连接相机…", fontSize = 15.sp)
        }
    }
}

@Composable
private fun LoadingContent(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(message, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    if (entries.isEmpty()) { EmptyCameraContent(); return }

    val haptic = LocalHapticFeedback.current
    val rawCount = photos.count { it.hasRaw }
    val jpgCount = photos.count { it.jpg != null }
    val newCount = vm.getNewPhotoCount()
    val filteredPhotos = vm.getFilteredGroups()

    var detailGroup by remember { mutableStateOf<GalleryEntry.PhotoGroup?>(null) }
    var detailThumbBytes by remember { mutableStateOf<ByteArray?>(null) }

    // Storage & filter UI above the grid
    Column {
        StorageStatusBar(state.storages)
        FilterChipsRow(
            currentFilter = vm.filterMode,
            newCount = newCount,
            rawCount = rawCount,
            jpgCount = jpgCount,
            onFilterChange = vm::setFilter,
        )
    }

    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { vm.refresh() },
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

        // Folders section — full width
        if (folders.isNotEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Text("文件夹", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            for (i in folders.indices) {
                val folder = folders[i]
                item(key = "f_${folder.storageId}_${folder.info.handle}",
                    span = StaggeredGridItemSpan.FullLine) {
                    FolderRow(folder, onClick = { onFolderClick(folder) })
                }
            }
        }

        // Photos header — full width
        if (filteredPhotos.isNotEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Text("照片 (${filteredPhotos.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }

        // Photos — 3-column grid (automatic)
        items(filteredPhotos, key = { it.baseName }) { group ->
            PhotoCell(
                group = group,
                isSelected = vm.isGroupSelected(group),
                onToggle = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.toggleSelection(group)
                },
                getThumbnail = vm::getThumbnail,
                getOrientation = vm::getOrientation,
                onPhotoClick = { detailGroup = group; detailThumbBytes = vm.getThumbnail(group.previewHandle) },
            )
        }
    }
    } // PullToRefreshBox

    detailGroup?.let { group ->
        val photo = group.jpg ?: group.raw ?: return@let
        PhotoDetailSheet(
            thumbnailBytes = detailThumbBytes,
            photoInfo = photo,
            onDismiss = { detailGroup = null; detailThumbBytes = null },
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
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_photo_camera_48dp), null,
                    Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${info.manufacturer} ${info.model}",
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    val storageLine = storages.joinToString("  ") {
                        "${it.description}  ${formatFileSize(it.freeSpace)} / ${formatFileSize(it.maxCapacity)}"
                    }
                    Text(storageLine, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    painterResource(if (expanded) R.drawable.ic_collapse_24dp
                        else R.drawable.ic_expand_24dp),
                    null, Modifier.size(20.dp).padding(top = 4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    info.serialNumber?.let { DetailRow("序列号", it) }
                    info.deviceVersion?.let { DetailRow("固件版本", it) }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label  ", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Folder Row ─────────────────────────────────────────────────────────────

@Composable
private fun FolderRow(folder: GalleryEntry.Folder, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_filter_list_24dp), null,
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            Spacer(Modifier.width(12.dp))
            Text(folder.info.name, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_arrow_back_24dp), null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}

// ── Photo Cell ─────────────────────────────────────────────────────────────

@Composable
private fun PhotoCell(
    group: GalleryEntry.PhotoGroup,
    isSelected: Boolean,
    onToggle: () -> Unit,
    getThumbnail: suspend (Int) -> ByteArray?,
    getOrientation: (Int) -> Int?,
    onPhotoClick: (() -> Unit)? = null,
) {
    val handle = group.previewHandle
    val cachedOri = getOrientation(handle)

    // Compute initial aspect ratio from available info so the staggered
    // grid measures the cell correctly before the thumbnail loads.
    val photoInfo = group.jpg ?: group.raw
    val thumbPixW = photoInfo?.thumbPixWidth ?: 0
    val thumbPixH = photoInfo?.thumbPixHeight ?: 0
    val baseAspect = when {
        cachedOri != null -> orientationToAspect(cachedOri, thumbPixW, thumbPixH)
        thumbPixW > 0 && thumbPixH > 0 -> thumbPixW.toFloat() / thumbPixH.toFloat()
        else -> 3f / 2f
    }

    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }

    // cellAspect: initially computed from orientation cache / thumbPix dimensions.
    // Once the real thumbnail loads and is rotated, the actual bitmap dimensions
    // take over (stored in loadedAspect). This avoids the staggered grid measuring
    // the cell at a wrong default ratio.
    var loadedAspect by remember { mutableStateOf<Float?>(null) }
    val cellAspect = loadedAspect ?: baseAspect

    LaunchedEffect(handle) {
        val bytes = withContext(Dispatchers.IO) { getThumbnail(handle) }
        if (bytes == null) return@LaunchedEffect

        val raw = withContext(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } ?: return@LaunchedEffect

        // Use orientation from cache as authoritative source;
        // falls back to the thumbnail's own EXIF if cache is empty.
        val fallback = getOrientation(handle)
        val rotated = withContext(Dispatchers.IO) { rotateByExif(raw, bytes, fallback) }
        thumb = rotated.asImageBitmap()
        loadedAspect = rotated.width.toFloat() / rotated.height.toFloat()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(cellAspect)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { onPhotoClick?.invoke() },
                onLongClick = { onToggle() },
            ),
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb!!,
                contentDescription = group.baseName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
                Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        if (isSelected) {
            Box(Modifier.fillMaxSize()
                .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            )

            Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
                Alignment.Center) {
                Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Rotates [bitmap] according to EXIF orientation in [jpegBytes].
 * If the thumbnail's own EXIF says NORMAL (or is missing) but we have
 * [fallbackOrientation] (e.g. extracted earlier from the JPEG counterpart),
 * the fallback is used instead.
 */
private fun rotateByExif(
    bitmap: Bitmap,
    jpegBytes: ByteArray,
    fallbackOrientation: Int? = null,
): Bitmap = try {
    val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
    var orientation = exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )
    // If the thumbnail doesn't specify a rotation but we have prior knowledge
    // that the original photo IS rotated, use the fallback.
    if (orientation == ExifInterface.ORIENTATION_NORMAL && fallbackOrientation != null &&
        fallbackOrientation != ExifInterface.ORIENTATION_NORMAL) {
        orientation = fallbackOrientation
    }
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) bitmap
    else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
        Matrix().apply { postRotate(degrees) }, true)
} catch (_: Exception) { bitmap }

/** Converts an EXIF orientation to an aspect ratio (width/height). */
private fun orientationToAspect(
    orientation: Int,
    thumbPixW: Int,
    thumbPixH: Int,
): Float {
    // If we have real thumbnail dimensions, swap them for rotated orientations
    if (thumbPixW > 0 && thumbPixH > 0) {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270 -> thumbPixH.toFloat() / thumbPixW.toFloat()
            else -> thumbPixW.toFloat() / thumbPixH.toFloat()
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
            Icon(painterResource(R.drawable.ic_photo_camera_24dp), null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text("相机中无照片", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("拍摄照片后会自动出现在这里", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_error_24dp), null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, fontSize = 14.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("重试") }
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
    val color = when {
        ratio < 0.8f -> MaterialTheme.colorScheme.primary
        ratio < 0.9f -> Color(0xFFFFA000) // amber
        else -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(android.R.drawable.ic_menu_save), null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            "已用 ${formatFileSize(usedBytes)} / ${formatFileSize(totalBytes)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ratio > 0.9f) {
            Spacer(Modifier.width(6.dp))
            Text("⚠️ 空间不足", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
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
            label = { Text("全部", fontSize = 13.sp) },
        )
        FilterChip(
            selected = currentFilter == PhotoFilter.NEW,
            onClick = { onFilterChange(PhotoFilter.NEW) },
            label = { Text("新照片 ($newCount)", fontSize = 13.sp) },
        )
        if (rawCount > 0) {
            FilterChip(
                selected = currentFilter == PhotoFilter.RAW_ONLY,
                onClick = { onFilterChange(PhotoFilter.RAW_ONLY) },
                label = { Text("RAW ($rawCount)", fontSize = 13.sp) },
            )
        }
        if (jpgCount > 0) {
            FilterChip(
                selected = currentFilter == PhotoFilter.JPEG_ONLY,
                onClick = { onFilterChange(PhotoFilter.JPEG_ONLY) },
                label = { Text("JPEG ($jpgCount)", fontSize = 13.sp) },
            )
        }
    }
}

@Composable
private fun TransferringContent(s: GalleryState.Transferring) {
    val p = s.progress
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
               modifier = Modifier.padding(32.dp).fillMaxWidth()) {
            CircularProgressIndicator()
            Spacer(Modifier.height(24.dp))
            Text("正在传输 ${p.currentFile}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (p.total > 0) p.synced.toFloat() / p.total else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text("${p.synced} / ${p.total}", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.speedBps > 0) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(p.speedFormatted, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.etaFormatted, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("✨", fontSize = 40.sp)
                Spacer(Modifier.height(12.dp))
                Text("同步完成", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("已保存 ${s.synced} 张照片", fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))

                if (s.savedUris.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(s.savedUris.first(), "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(android.R.drawable.ic_menu_gallery), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("在相册中查看")
                    }
                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "image/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM,
                                    ArrayList(s.savedUris))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享照片"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(android.R.drawable.ic_menu_share), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("分享")
                    }
                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(android.R.drawable.ic_menu_delete), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("从相机删除")
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) {
                    Text("继续浏览")
                }
            }
        }
    }

    // Confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
            text = { Text("这些照片已安全保存到手机。\n从相机删除后无法恢复。\n\n确定要删除 ${s.synced} 张照片吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch {
                            val deleted = viewModel.deleteTransferredPhotos(viewModel.lastTransferredHandles)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "已删除 $deleted 张", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}
