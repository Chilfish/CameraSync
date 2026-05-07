package dev.sebastiano.camerasync.usb

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Bottom sheet showing photo preview and EXIF metadata from the MTP thumbnail (JPEG).
 * [thumbnailBytes] — the cached MTP thumbnail, already a valid JPEG with EXIF for NEF.
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

    // Decode the thumbnail for preview
    val bitmap = remember(thumbnailBytes) {
        thumbnailBytes?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size)
        }
    }

    // Extract EXIF from the thumbnail bytes (valid JPEG for NEF)
    val exifFields = remember(thumbnailBytes) { extractExif(thumbnailBytes) }

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
                // Photo preview — from MTP thumbnail
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = photoInfo.name,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(16.dp))
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "无预览",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

                    for (field in exifFields) {
                        val label = field.first
                        val value = field.second
                        if (value != null && value.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    label,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(value, fontSize = 14.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}

/**
 * Extracts human-readable EXIF fields from a JPEG byte array. Returns ordered list of (label,
 * value) pairs. Fields with null or blank values are filtered out by the caller.
 */
private fun extractExif(jpegBytes: ByteArray?): List<Pair<String, String?>> {
    if (jpegBytes == null) return emptyList()
    return try {
        val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
        val dateStr =
            exif.getAttribute(ExifInterface.TAG_DATETIME)?.let { raw ->
                try {
                    val parsed =
                        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault()).parse(raw)
                    if (parsed != null)
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(parsed)
                    else raw
                } catch (_: Exception) {
                    raw
                }
            }
        listOf(
            "分辨率" to
                formatResolution(
                    exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                    exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
                ),
            "日期" to dateStr,
            "快门" to
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { formatShutterSpeed(it) },
            "光圈" to
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let {
                    "f/${it.toDoubleOrNull()?.let { v -> "%.1f".format(v) } ?: it}"
                },
            "ISO" to
                (exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)),
            "焦距" to
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let {
                    it.toDoubleOrNull()?.let { v -> "${"%.0f".format(v)}mm" } ?: "$it mm"
                },
            "35mm 等效" to
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.let { "${it}mm" },
            "镜头" to exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
            "曝光补偿" to formatExposureCompensation(exif),
            "测光模式" to getMeteringMode(exif),
            "闪光灯" to getFlash(exif),
            "方向" to getOrientation(exif),
            "相机" to
                formatCamera(
                    exif.getAttribute(ExifInterface.TAG_MAKE),
                    exif.getAttribute(ExifInterface.TAG_MODEL),
                ),
        )
    } catch (_: Exception) {
        emptyList()
    }
}

/** Formats EXIF image dimensions as "W×H". Returns null if both are 0. */
private fun formatResolution(width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) return null
    return "${width}×${height}"
}

/** Formats an EXIF exposure time string (e.g. "1/250" or "0.0025") into a human-readable form. */
private fun formatShutterSpeed(value: String): String {
    val d = value.toDoubleOrNull() ?: return value
    if (d >= 1.0) return "${"%.1f".format(d)}s"
    val denominator = (1.0 / d).toInt()
    return "1/$denominator"
}

/** Formats exposure compensation from the EXIF rational string. */
private fun formatExposureCompensation(exif: ExifInterface): String? {
    val raw = exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE) ?: return null
    val d = raw.toDoubleOrNull() ?: return raw
    return if (d >= 0) "+${"%.1f".format(d)}" else "${"%.1f".format(d)}"
}

/** Converts EXIF metering mode code to a human-readable Chinese label. */
private fun getMeteringMode(exif: ExifInterface): String? {
    val code = exif.getAttributeInt(ExifInterface.TAG_METERING_MODE, -1)
    return when (code) {
        0 -> "未知"
        1 -> "平均测光"
        2 -> "中央重点测光"
        3 -> "点测光"
        4 -> "多点测光"
        5 -> "矩阵测光"
        6 -> "局部测光"
        255 -> "其他"
        -1 -> null
        else -> "模式 $code"
    }
}

/** Converts EXIF flash code to a human-readable Chinese label. */
private fun getFlash(exif: ExifInterface): String? {
    val raw = exif.getAttribute(ExifInterface.TAG_FLASH)
    if (raw == null) return null
    val code = raw.toIntOrNull() ?: return raw
    return when {
        code and 1 == 0 -> "未闪光"
        code and 1 == 1 -> "闪光"
        else -> raw
    }
}

/** Converts EXIF orientation code to a human-readable Chinese label. */
private fun getOrientation(exif: ExifInterface): String? {
    return when (
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_NORMAL -> null // don't show "正常" — it's the default
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "水平翻转"
        ExifInterface.ORIENTATION_ROTATE_180 -> "旋转 180°"
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> "垂直翻转"
        ExifInterface.ORIENTATION_TRANSPOSE -> "对角翻转"
        ExifInterface.ORIENTATION_ROTATE_90 -> "旋转 90°"
        ExifInterface.ORIENTATION_TRANSVERSE -> "反对角翻转"
        ExifInterface.ORIENTATION_ROTATE_270 -> "旋转 270°"
        ExifInterface.ORIENTATION_UNDEFINED -> null
        else -> null
    }
}

/** Formats camera make and model into a single string, filtering nulls. */
private fun formatCamera(make: String?, model: String?): String? {
    if (make == null && model == null) return null
    if (make == null) return model
    if (model == null) return make
    return if (model.startsWith(make)) model else "$make $model"
}
