package dev.sebastiano.camerasync.usb

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import dev.sebastiano.camerasync.usb.formatFileSize
import java.io.ByteArrayInputStream

/**
 * Bottom sheet showing photo preview and EXIF metadata.
 * [thumbnailBytes] — the MTP thumbnail JPEG (from getThumbnail cache).
 * [photoInfo] — the PhotoInfo for this photo (filename, size, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailSheet(
    thumbnailBytes: ByteArray?,
    photoInfo: NikonUsbManager.PhotoInfo,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val exifFields = remember(thumbnailBytes) { extractExif(thumbnailBytes) }

    LaunchedEffect(Unit) { sheetState.show() }

    if (sheetState.isVisible) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                // Photo preview
                if (thumbnailBytes != null) {
                    val bitmap = remember(thumbnailBytes) {
                        BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = photoInfo.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // Filename
                Text(photoInfo.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${photoInfo.formatName} · ${formatFileSize(photoInfo.size)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (exifFields.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("EXIF 信息", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))

                    exifFields.forEach { (label, value) ->
                        if (value != null && value.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    label, fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(value, fontSize = 14.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭")
                }
            }
        }
    }
}

/**
 * Extracts human-readable EXIF fields from a JPEG byte array.
 * Returns ordered list of (label, value) pairs.
 */
private fun extractExif(jpegBytes: ByteArray?): List<Pair<String, String?>> {
    if (jpegBytes == null) return emptyList()
    return try {
        val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
        listOf(
            "快门速度" to exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                ?.let { formatShutterSpeed(it) },
            "光圈" to exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                ?.let { "f/${it.toDoubleOrNull()?.let { v -> "%.1f".format(v) } ?: it}" },
            "ISO" to exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS),
            "焦距" to exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                ?.let { it.toDoubleOrNull()?.let { v -> "${"%.0f".format(v)}mm" } ?: "$it mm" },
            "拍摄时间" to exif.getAttribute(ExifInterface.TAG_DATETIME),
            "相机型号" to exif.getAttribute(ExifInterface.TAG_MODEL),
            "闪光灯" to exif.getAttribute(ExifInterface.TAG_FLASH)
                ?.let { if (it == "0") "关闭" else if (it == "1") "开启" else it },
        )
    } catch (_: Exception) {
        emptyList()
    }
}

/** Formats an EXIF exposure time string (e.g. "1/250" or "0.0025") into a human-readable form. */
private fun formatShutterSpeed(value: String): String {
    val d = value.toDoubleOrNull() ?: return value
    if (d >= 1.0) return "${"%.1f".format(d)}s"
    val denominator = (1.0 / d).toInt()
    return "1/$denominator"
}
