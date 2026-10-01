package dev.sebastiano.camerasync.usb

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.sebastiano.camerasync.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TransferringContent(s: GalleryState.Transferring) {
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
internal fun TransferDoneContent(
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
internal fun TransferDonePanel(
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
internal fun TransferSummaryContent(savedUris: List<Uri>, onClose: () -> Unit) {
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
internal fun TransferSummaryRow(uri: Uri) {
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
internal fun queryMediaDisplayName(context: Context, uri: Uri): String =
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
internal fun TransferPreviewSheet(
    viewModel: GalleryViewModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Collect selected photo groups for preview.
    // Use currentPhotos (unfiltered) so the preview shows ALL selected
    // photos regardless of the active filter chip (e.g. "仅 RAW").
    // Count the full selection first, then cap only the thumbnails (R22).
    val allSelectedGroups = viewModel.currentPhotos.filter { viewModel.isGroupSelected(it) }

    val totalGroups = allSelectedGroups.size
    val selectedGroups = allSelectedGroups.take(6) // show first 6 thumbnails

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
