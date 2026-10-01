package dev.sebastiano.camerasync.usb

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.exifinterface.media.ExifInterface
import dev.sebastiano.camerasync.R
import java.text.SimpleDateFormat
import java.util.Locale

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
internal fun formatExifDate(raw: String?): String? {
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
