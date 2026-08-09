package dev.sebastiano.camerasync.usb

import android.app.Application
import android.mtp.MtpDevice
import androidx.exifinterface.media.ExifInterface
import com.juul.khronicle.Log
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val TAG = "ThumbnailProvider"

/**
 * Owns the four MTP caches (thumbnail bytes, full-photo bytes, EXIF orientation, decoded bitmaps)
 * plus the orientation-detection and thumbnail-preload logic (P2-1 extraction from
 * [GalleryViewModel]).
 *
 * [mtp] and [currentPhotos] are read via accessors so the provider stays decoupled from the USB
 * lifecycle; the ViewModel wires them to its own state.
 */
class ThumbnailProvider(
    private val scope: CoroutineScope,
    private val app: Application,
    private val mtp: () -> MtpDevice?,
    private val currentPhotos: () -> List<GalleryEntry.PhotoGroup>,
) {

    // Thumbnail cache (shared across folder navigations).
    // Wrapped in synchronizedMap because getThumbnail() is called concurrently
    // from multiple coroutines on the IO dispatcher (prefetchOrientations,
    // preloadThumbnails, PhotoCell LaunchedEffect, TransferPreviewSheet).
    private val thumbCache =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<Int, ByteArray>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) =
                    size > 128
            }
        )

    // Full-photo download cache — keyed by handle, stores full NEF/JPEG bytes (up to 26MB each).
    // LRU eviction at 12 entries to cap memory at ~300MB. Cleared on disconnect.
    // Thread-safe: accessed from PhotoDetailSheet coroutine (IO) and cleared from main thread.
    private val fullPhotoCache =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<Int, ByteArray>(12, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) =
                    size > 12
            }
        )

    // EXIF orientation cache — extracted from MTP thumbnail bytes, keyed by handle.
    // Uses ExifInterface.ORIENTATION_* constants. Populated by getThumbnail().
    // ConcurrentHashMap because writes (IO) and reads (main) happen on different threads.
    private val orientationCache = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** Limits concurrent MTP calls (getThumbnail, importFile) to avoid USB bandwidth contention. */
    private val mtpSemaphore = Semaphore(3)

    /**
     * LRU cache of decoded [android.graphics.Bitmap] instances keyed by MTP handle. Prevents
     * re-decoding + re-rotation when LazyGrid recycles [PhotoCell] composables. Cleared on
     * disconnect via [clearAll].
     */
    val bitmapCache =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<Int, android.graphics.Bitmap>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Int, android.graphics.Bitmap>?
                ) = size > 96
            }
        )

    fun getOrientation(handle: Int): Int? = orientationCache[handle]

    /**
     * Populates [orientationCache] from [android.mtp.MtpObjectInfo] dimensions and, for the first
     * [count] handles without a cached orientation, fetches their MTP thumbnail to extract EXIF
     * orientation. Does NOT block the caller — launches a background coroutine for thumbnail
     * fetching.
     *
     * Call this AFTER entering Browsing state so the user sees the grid immediately.
     */
    fun prefetchOrientations(count: Int) {
        // Step 1: instant — use dimensions for all uncached handles.
        populateOrientationsFromDimensions()
        // Step 2: background — fetch thumbnails for accurate EXIF.
        preloadThumbnails(count)
    }

    /**
     * Background thumbnail preloader with concurrent MTP calls.
     *
     * Uses [mtpSemaphore] (max 3 concurrent MTP operations) to avoid USB bandwidth contention while
     * loading thumbnails faster than serial. Orientation is extracted as a side effect of each
     * [getThumbnail] call.
     */
    fun preloadThumbnails(count: Int = 30) {
        val handles = currentPhotos().take(count).mapNotNull { it.previewHandle }
        scope.launch {
            try {
                coroutineScope {
                    handles.map { h ->
                        async {
                            if (!currentCoroutineContext().isActive) return@async
                            mtpSemaphore.withPermit { getThumbnail(h) }
                        }
                    }
                }
            } catch (_: Exception) {
                /* best-effort */
            }
        }
    }

    fun getThumbnail(handle: Int): ByteArray? {
        thumbCache[handle]?.let {
            return it
        }
        // Capture mtp in a local variable to avoid a race with closeMtp()
        // (which sets the device to null on the main thread while this runs on IO).
        val device = mtp() ?: return null
        // MtpDevice may have been closed by the time the native call executes — gracefully
        // return null rather than crashing.
        return runCatching {
                device.getThumbnail(handle)?.also { bytes ->
                    thumbCache[handle] = bytes
                    extractOrientation(handle, bytes)
                }
            }
            .getOrNull()
    }

    private fun extractOrientation(handle: Int, thumbBytes: ByteArray) {
        if (orientationCache.containsKey(handle)) return
        try {
            val exif = ExifInterface(ByteArrayInputStream(thumbBytes))
            val ori =
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            // Only cache REAL rotations. NORMAL means either:
            // 1. The thumbnail truly has no rotation needed (landscape, pre-rotated)
            // 2. The MTP thumbnail has no EXIF orientation tag at all
            // In case 2, caching NORMAL would block dimension-based fallback.
            if (ori != ExifInterface.ORIENTATION_NORMAL) {
                orientationCache[handle] = ori
            }
        } catch (_: Exception) {
            /* ExifInterface failed (TIFF thumbnail) — leave uncached */
        }
    }

    /**
     * Populates [orientationCache] from [android.mtp.MtpObjectInfo.imagePixWidth/imagePixHeight]
     * for handles whose EXIF orientation is still unknown (cache miss).
     *
     * [android.mtp.MtpObjectInfo] dimensions are more reliable than thumbnail-based heuristics
     * because they reflect the actual full-resolution image orientation. Nikon Z30 reports
     * 5568×3712 for landscape and 3712×5568 for portrait.
     *
     * Call this after [GalleryViewModel.groupByBaseFilename] so each [GalleryEntry.PhotoGroup] has
     * its [NikonUsbManager.PhotoInfo] with imagePix dimensions available.
     */
    fun populateOrientationsFromDimensions() {
        for (group in currentPhotos()) {
            val handle = group.previewHandle ?: continue
            val cached = orientationCache[handle]
            // Only skip if a REAL rotation (not NORMAL) is already cached.
            // NORMAL means "no rotation found" (either the thumbnail had no
            // EXIF tags, or the camera pre-rotated the pixel data). In both
            // cases we should still try to detect portrait from dimensions.
            if (cached != null && cached != ExifInterface.ORIENTATION_NORMAL) continue
            val info = group.jpg ?: group.raw ?: continue
            // Nikon Z30 always reports sensor dimensions (5568×3712) for imagePix,
            // so imagePix alone won't detect portrait. Use imagePix first, then
            // fall back to thumbPix (which IS swapped for portrait on Z30: 120×160).
            val isPortrait =
                (info.imagePixWidth > 0 &&
                    info.imagePixHeight > 0 &&
                    info.imagePixWidth < info.imagePixHeight) ||
                    (info.thumbPixWidth > 0 &&
                        info.thumbPixHeight > 0 &&
                        info.thumbPixWidth < info.thumbPixHeight)
            if (isPortrait) {
                orientationCache[handle] = ExifInterface.ORIENTATION_ROTATE_90
            }
        }
        // For RAW+JPEG pairs: copy any known JPEG orientation to the RAW handle
        // so NEF previews get correct rotation even when the NEF MTP thumbnail
        // is TIFF-based (no EXIF) or pre-rotated.
        for (group in currentPhotos()) {
            val jpgHandle = group.jpg?.handle ?: continue
            val rawHandle = group.raw?.handle ?: continue
            val jpgOri = orientationCache[jpgHandle] ?: continue
            if (!orientationCache.containsKey(rawHandle)) {
                orientationCache[rawHandle] = jpgOri
            }
        }
    }

    /**
     * Downloads the full photo file (NEF/JPEG/etc) to a ByteArray for EXIF extraction. Uses MTP
     * importFile to temp, reads bytes, deletes temp. Returns null on failure.
     */
    suspend fun downloadFullPhoto(handle: Int): ByteArray? {
        // Check cache first — avoids re-downloading 26MB NEF files
        fullPhotoCache[handle]?.let {
            return it
        }

        val m = mtp() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                    val tempFile = File(app.cacheDir, "detail_$handle")
                    tempFile.parentFile?.mkdirs()
                    val ok = m.importFile(handle, tempFile.absolutePath)
                    if (!ok) return@runCatching null
                    val bytes = tempFile.readBytes()
                    tempFile.delete()
                    fullPhotoCache[handle] = bytes
                    bytes
                }
                .getOrElse { e ->
                    Log.error(tag = TAG, throwable = e) { "downloadFullPhoto failed" }
                    null
                }
        }
    }

    /** Clears the full-photo download cache (called when the MTP device is closed). */
    fun clearFullPhotoCache() {
        fullPhotoCache.clear()
    }

    /** Clears all four caches (called on USB disconnect). */
    fun clearAll() {
        thumbCache.clear()
        fullPhotoCache.clear()
        orientationCache.clear()
        bitmapCache.clear()
    }
}
