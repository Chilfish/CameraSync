package dev.sebastiano.camerasync.usb

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import dev.sebastiano.camerasync.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Tab Content ─────────────────────────────────────────────────────────────

@Composable
internal fun CameraTabContent(
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
internal fun LocalTabContent(localVm: LocalPhotosViewModel, gridColumns: Int) {
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
internal fun LocalBreadcrumb(currentPath: String, onBack: () -> Unit, onRefresh: () -> Unit) {
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
internal fun LocalFolderCell(folder: LocalFolder, onClick: () -> Unit) {
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
internal fun LocalPhotoCell(group: LocalPhotoGroup, onClick: () -> Unit) {
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
internal fun LocalPhotoDetail(group: LocalPhotoGroup, onDismiss: () -> Unit) {
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
