package dev.sebastiano.camerasync.usb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import dev.sebastiano.camerasync.R
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

@Composable
internal fun BrowsingContent(
    state: GalleryState.Browsing,
    host: GalleryScreenHost,
    isRoot: Boolean,
    onFolderClick: (GalleryEntry.Folder) -> Unit = {},
    onTransferAllNew: () -> Unit = {},
) {
    val entries = state.entries

    if (entries.isEmpty()) {
        EmptyCameraContent()
        return
    }

    // Derive splits, counts and imported flags from the entry list only — recomputed when the list
    // changes (scan progress, filter/sort refresh), never on a mere selection toggle (R24).
    val photos = remember(entries) { entries.filterIsInstance<GalleryEntry.PhotoGroup>() }
    val folders = remember(entries) { entries.filterIsInstance<GalleryEntry.Folder>() }
    val dateSections = remember(entries) { entries.filterIsInstance<GalleryEntry.DateSection>() }
    val rawCount = remember(photos) { photos.count { it.hasRaw } }
    val jpgCount = remember(photos) { photos.count { it.jpg != null } }
    val newCount = remember(entries) { host.getNewPhotoCount() }
    val importedByKey =
        remember(entries) { photos.associate { it.key to host.isGroupImported(it) } }

    val haptic = LocalHapticFeedback.current
    val filteredPhotos = host.getFilteredGroups()
    val isFlatMode = host.groupingMode != UsbSyncPreferences.PhotoGrouping.BY_FOLDER

    // Pre-bucket by date once instead of re-filtering the whole list per section with a fresh
    // SimpleDateFormat per photo (R25).
    val photosByDate =
        remember(filteredPhotos) {
            filteredPhotos.groupBy { group ->
                dateKey(maxOf(group.raw?.dateModified ?: 0L, group.jpg?.dateModified ?: 0L))
            }
        }

    val gridState = rememberLazyStaggeredGridState()

    // Preload thumbnails for the visible window (plus a margin) as the user scrolls, instead of
    // only the first N photos once at load time (R28).
    val indexToHandle =
        remember(
            isRoot,
            state.cameraInfo,
            folders,
            dateSections,
            photosByDate,
            filteredPhotos,
            isFlatMode,
        ) {
            buildIndexToHandle(
                isRoot = isRoot,
                hasCameraInfo = state.cameraInfo != null,
                folders = folders,
                dateSections = dateSections,
                photosByDate = photosByDate,
                filteredPhotos = filteredPhotos,
                isFlatMode = isFlatMode,
            )
        }

    LaunchedEffect(gridState, indexToHandle) {
        snapshotFlow {
                val visible = gridState.layoutInfo.visibleItemsInfo
                if (visible.isEmpty()) {
                    null
                } else {
                    val first = (visible.first().index - PRELOAD_MARGIN).coerceAtLeast(0)
                    val last =
                        (visible.last().index + PRELOAD_MARGIN).coerceAtMost(
                            indexToHandle.lastIndex
                        )
                    if (first > last) null
                    else (first..last).mapNotNull { indexToHandle.getOrNull(it) }
                }
            }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { host.preloadThumbnails(it) }
    }

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

        // Ongoing scan indicator — the grid keeps growing while the card is enumerated (R29).
        state.scanProgress?.let { scanned ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            Text(
                stringResource(R.string.usb_status_scanning_count, scanned.toString()),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        PullToRefreshBox(
            isRefreshing = host.isRefreshing,
            onRefresh = { host.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            LazyVerticalStaggeredGrid(
                state = gridState,
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
                        items(photosByDate[section.date].orEmpty(), key = { it.key }) { group ->
                            PhotoCell(
                                group = group,
                                isSelected = { host.isGroupSelected(group) },
                                isImported = importedByKey[group.key] == true,
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

                    items(filteredPhotos, key = { it.key }) { group ->
                        PhotoCell(
                            group = group,
                            isSelected = { host.isGroupSelected(group) },
                            isImported = importedByKey[group.key] == true,
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

/** Extra grid items preloaded on each side of the visible window (R28). */
internal const val PRELOAD_MARGIN = 12

/**
 * Flattens the browsing grid's item order into index → preview handle (null for headers, folders
 * and the device card), so the visible window's indices map back to handles without duplicating the
 * item-building logic. Must mirror the item order in [BrowsingContent] (R28).
 */
internal fun buildIndexToHandle(
    isRoot: Boolean,
    hasCameraInfo: Boolean,
    folders: List<GalleryEntry.Folder>,
    dateSections: List<GalleryEntry.DateSection>,
    photosByDate: Map<String, List<GalleryEntry.PhotoGroup>>,
    filteredPhotos: List<GalleryEntry.PhotoGroup>,
    isFlatMode: Boolean,
): List<Int?> {
    val handles = mutableListOf<Int?>()
    if (isRoot && hasCameraInfo) handles.add(null)
    if (folders.isNotEmpty() && !isFlatMode) {
        handles.add(null)
        folders.forEach { handles.add(null) }
    }
    if (dateSections.isNotEmpty()) {
        for (section in dateSections) {
            handles.add(null)
            photosByDate[section.date].orEmpty().forEach { handles.add(it.previewHandle) }
        }
    } else {
        if (filteredPhotos.isNotEmpty()) handles.add(null)
        filteredPhotos.forEach { handles.add(it.previewHandle) }
    }
    return handles
}

// ── Device Info Card ───────────────────────────────────────────────────────

@Composable
internal fun DeviceInfoCard(
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
internal fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Folder Row ─────────────────────────────────────────────────────────────

@Composable
internal fun FolderRow(folder: GalleryEntry.Folder, onClick: () -> Unit) {
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
internal fun ThumbnailImage(
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
                val out = if (needsRotation) rotateByExif(raw, bytes, fallback) else raw
                if (out !== raw) raw.recycle() // rotation allocated a new bitmap (R28)
                out
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
internal fun PhotoCell(
    group: GalleryEntry.PhotoGroup,
    isSelected: () -> Boolean,
    isImported: Boolean = false,
    onToggle: () -> Unit,
    getThumbnail: suspend (Int) -> ByteArray?,
    getOrientation: (Int) -> Int?,
    bitmapCache: MutableMap<Int, Bitmap>,
    onPhotoClick: (() -> Unit)? = null,
) {
    val handle = group.previewHandle ?: return
    // Read selection inside the cell's own scope so only the toggled cell recomposes (R24).
    val selected = isSelected()
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

        if (selected) {
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
        if (isImported && !selected) {
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
internal fun orientationToAspect(orientation: Int, pixW: Int, pixH: Int): Float {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterChipsRow(
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
