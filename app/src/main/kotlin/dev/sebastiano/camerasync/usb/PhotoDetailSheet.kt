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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Bottom sheet: preview image from MTP thumbnail (instant), EXIF from full RAW file (loaded async).
 *
 * On open, downloads the full NEF/JPEG via [viewModel] to extract complete EXIF metadata.
 * The preview image uses the cached MTP thumbnail for instant display — no waiting.
 * EXIF fields appear with a loading spinner until the full file is downloaded.
 *
 * [viewModel] — used to download the full photo file for EXIF extraction.
 * [photoInfo] — basic photo metadata (name, size, format, handle for full download).
 * [thumbnailBytes] — cached MTP thumbnail for instant preview (may be null for unsupported formats).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailSheet(
    viewModel: GalleryViewModel,
    photoInfo: NikonUsbManager.PhotoInfo,
    thumbnailBytes: ByteArray?,  // instant MTP thumbnail for display while loading
    orientationFallback: Int? = null,  // from orientationCache for EXIF rotation
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Instant: MTP thumbnail preview. Camera may pre-rotate the pixel data,
    // so skip extra rotation if the bitmap already matches the expected orientation.
    val thumbBitmap = remember(thumbnailBytes, orientationFallback) {
        thumbnailBytes?.let {
            val raw = BitmapFactory.decodeByteArray(it, 0, it.size) ?: return@let null
            val needsRotation = orientationFallback == null ||
                when (orientationFallback) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    -> raw.width > raw.height
                    else -> true
                }
            if (needsRotation) rotateByExif(raw, it, orientationFallback) else raw
        }
    }

    // Async: download full NEF/JPEG for complete EXIF
    var fullImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var exifFields by remember { mutableStateOf<List<Pair<String, String?>>>(emptyList()) }
    var exifLoading by remember { mutableStateOf(true) }
    var downloadError by remember { mutableStateOf(false) }

    LaunchedEffect(photoInfo.handle) {
        exifLoading = true
        downloadError = false
        val bytes = withContext(Dispatchers.IO) { viewModel.downloadFullPhoto(photoInfo.handle) }

        if (bytes == null) {
            downloadError = true
            exifLoading = false
            return@LaunchedEffect
        }

        // Extract EXIF from the full RAW/JPEG bytes
        val fields = withContext(Dispatchers.IO) { extractExif(bytes) }
        exifFields = fields

        // Try to decode a high-quality preview from the full file bytes,
        // rotating by EXIF. For NEF, ExifInterface on the full file may
        // not find orientation in the TIFF structure, so pass the cached
        // orientation from the JPEG counterpart as fallback.
        val cachedOri = orientationFallback
        val decoded = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            raw?.let { rotateByExif(it, bytes, cachedOri) }
        }
        fullImage = decoded?.asImageBitmap()
        exifLoading = false
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
                // Preview image: full-resolution if available, else MTP thumbnail, else placeholder
                val displayBitmap = fullImage ?: thumbBitmap?.asImageBitmap()
                if (displayBitmap != null) {
                    Image(
                        bitmap = displayBitmap,
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

                // Filename and basic info
                Text(photoInfo.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${photoInfo.formatName} · ${formatFileSize(photoInfo.size)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // EXIF section
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("EXIF 信息", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                if (downloadError) {
                    Text(
                        "读取失败 — 请确认相机仍处于连接状态",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else if (exifLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.padding(8.dp))
                        Text("正在读取 RAW 文件…", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (exifFields.isNotEmpty()) {
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
 * Extracts human-readable EXIF fields from a RAW/JPEG byte array.
 * Works on both NEF (TIFF-based) and JPEG — Android's ExifInterface handles both.
 */
internal fun extractExif(fileBytes: ByteArray?): List<Pair<String, String?>> {
    if (fileBytes == null) return emptyList()
    return try {
        val exif = ExifInterface(ByteArrayInputStream(fileBytes))
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
        // Build full field list; null/blank values filtered by caller
        listOf(
            "文件名" to null,
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
            "拍摄者" to exif.getAttribute(ExifInterface.TAG_ARTIST),
            "版权" to exif.getAttribute(ExifInterface.TAG_COPYRIGHT),
            "软件" to exif.getAttribute(ExifInterface.TAG_SOFTWARE),
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
        ExifInterface.ORIENTATION_NORMAL -> null
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
