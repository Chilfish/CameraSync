package dev.sebastiano.camerasync.usb

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import com.juul.khronicle.Log
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LocalPhotosVM"

/** A photo file found in the local export directory. */
data class LocalPhoto(
    val file: File,
    val name: String,
    val dateModified: Long,
    val size: Long,
    val isRaw: Boolean,
)

/** Grouped RAW+JPEG pair or single photo. */
data class LocalPhotoGroup(
    val baseName: String,
    val jpg: LocalPhoto?,
    val raw: LocalPhoto?,
    /** Handle-style key for orientation cache (derived from file path hash). */
    val cacheKey: Int,
)

class LocalPhotosViewModel(
    private val app: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    var groups by mutableStateOf<List<LocalPhotoGroup>>(emptyList())
        private set

    private val _loading = mutableStateOf(false)
    val loading: State<Boolean> = _loading

    var isRefreshing by mutableStateOf(false)
        private set

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** EXIF orientation cache, keyed by cacheKey (derived from file path). */
    private val orientationCache = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    fun getOrientation(cacheKey: Int): Int? = orientationCache[cacheKey]

    fun load() {
        scope.launch {
            _loading.value = true
            isRefreshing = true
            try {
                val result = withContext(ioDispatcher) { scanDirectories() }
                groups = result
                // Pre-load EXIF orientations in parallel, then dimension-based fallback
                preloadOrientations(result)
                populateOrientationsFromDimensions(result)
            } finally {
                _loading.value = false
                isRefreshing = false
            }
        }
    }

    fun stop() {
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    // ── Directory scanning ──────────────────────────────────────────────────

    /**
     * Scans exported photos using a dual-path approach:
     * 1. [File.listFiles] — fast, works with MANAGE_EXTERNAL_STORAGE or for app-owned files.
     * 2. [MediaStore] query — fallback for scoped storage where listFiles may return empty.
     */
    private fun scanDirectories(): List<LocalPhotoGroup> {
        val base =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "CameraSync",
            )
        val fileSet = linkedSetOf<File>()

        // Path 1: File.listFiles() — fast, works with MANAGE_EXTERNAL_STORAGE or for app-owned
        // files
        if (base.exists() && base.isDirectory) {
            collectFiles(base, fileSet)
        }
        Log.info(tag = TAG) { "listFiles() found ${fileSet.size} files" }

        // Path 2: MediaStore query — catches files visible to MediaStore (JPEG + NEF exported by
        // app)
        // This is essential on scoped storage where listFiles() may return empty
        queryMediaStore(fileSet)
        Log.info(tag = TAG) { "Total files (with MediaStore): ${fileSet.size}" }

        if (fileSet.isEmpty()) {
            Log.warn(tag = TAG) {
                "No files found via either path. Directory: ${base.absolutePath}"
            }
            return emptyList()
        }

        val grouped = groupByBaseName(fileSet.toList())
        Log.info(tag = TAG) { "Grouped into ${grouped.size} photo groups" }

        return grouped.sortedByDescending { group ->
            maxOf(group.jpg?.dateModified ?: 0L, group.raw?.dateModified ?: 0L)
        }
    }

    /**
     * Recursively walks [dir] collecting .jpg, .jpeg, and .nef files into [out]. Returns early if
     * [dir] is not readable (scoped storage edge case).
     */
    private fun collectFiles(dir: File, out: MutableSet<File>) {
        val children =
            try {
                dir.listFiles()
            } catch (e: SecurityException) {
                Log.warn(tag = TAG, throwable = e) { "Cannot list: ${dir.absolutePath}" }
                return
            }
                ?: run {
                    Log.warn(tag = TAG) { "listFiles() returned null for: ${dir.absolutePath}" }
                    return
                }
        for (child in children) {
            if (child.isDirectory) {
                collectFiles(child, out)
            } else {
                val name = child.name.lowercase()
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".nef")) {
                    out.add(child)
                }
            }
        }
    }

    /**
     * Queries MediaStore for photos under Pictures/CameraSync. Uses RELATIVE_PATH as primary filter
     * (works for app-exported files). Uses DATA pattern as fallback for NEF files that MediaStore
     * indexed but didn't set RELATIVE_PATH properly.
     */
    private fun queryMediaStore(out: MutableSet<File>) {
        // Query 1: MediaStore.Images for JPEG/PNG/etc. — reliable RELATIVE_PATH
        val imagesProjection =
            arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
            )
        val imageSelection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val imageArgs = arrayOf("Pictures/CameraSync/%")

        try {
            app.contentResolver
                .query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imagesProjection,
                    imageSelection,
                    imageArgs,
                    null,
                )
                ?.use { cursor ->
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    while (cursor.moveToNext()) {
                        val data = cursor.getString(dataCol)
                        if (!data.isNullOrBlank()) {
                            val file = File(data)
                            if (file.exists()) out.add(file)
                        }
                    }
                }
        } catch (_: Exception) {
            /* MediaStore query can fail */
        }
        Log.info(tag = TAG) { "MediaStore.Images found ${out.size} files" }

        // Query 2: MediaStore.Files for NEF/RAW files
        // NEF files exported by the app have RELATIVE_PATH set via ContentValues.
        // For NEF files not in MediaStore (manually copied), this won't help —
        // those need MANAGE_EXTERNAL_STORAGE + listFiles().
        val filesUri = MediaStore.Files.getContentUri("external")
        val filesProjection =
            arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
            )
        val filesSelection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val filesArgs = arrayOf("Pictures/CameraSync/%")

        try {
            app.contentResolver
                .query(filesUri, filesProjection, filesSelection, filesArgs, null)
                ?.use { cursor ->
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                    while (cursor.moveToNext()) {
                        val data = cursor.getString(dataCol)
                        if (!data.isNullOrBlank()) {
                            val file = File(data)
                            if (file.exists()) out.add(file)
                        }
                    }
                }
        } catch (_: Exception) {
            /* MediaStore query can fail */
        }
        Log.info(tag = TAG) { "MediaStore.Files total: ${out.size} files" }
    }

    private fun groupByBaseName(files: List<File>): List<LocalPhotoGroup> {
        val map = linkedMapOf<String, MutableList<LocalPhoto>>()
        for (f in files) {
            val base = f.nameWithoutExtension.uppercase()
            map.getOrPut(base) { mutableListOf() }.add(toLocalPhoto(f))
        }
        return map.values.map { list ->
            val jpg = list.firstOrNull { !it.isRaw }
            val raw = list.firstOrNull { it.isRaw }
            val cacheKey = (jpg?.file?.absolutePath ?: raw?.file?.absolutePath ?: "").hashCode()
            LocalPhotoGroup(
                baseName = list.first().name.let { it.substringBeforeLast(".").replace("_", " ") },
                jpg = jpg,
                raw = raw,
                cacheKey = cacheKey,
            )
        }
    }

    private fun toLocalPhoto(file: File) =
        LocalPhoto(
            file = file,
            name = file.name,
            dateModified = file.lastModified(),
            size = file.length(),
            isRaw = file.extension.lowercase() == "nef",
        )

    // ── EXIF orientation ────────────────────────────────────────────────────

    /**
     * Reads EXIF orientation from JPEG files via [ExifInterface]. RAW (NEF) files are skipped
     * because Android's ExifInterface doesn't reliably parse TIFF-based proprietary EXIF — a
     * dimension-based fallback in [populateOrientationsFromDimensions] handles these instead.
     */
    private suspend fun preloadOrientations(groups: List<LocalPhotoGroup>) {
        groups
            .map { group ->
                scope.async {
                    if (orientationCache.containsKey(group.cacheKey)) return@async
                    // Try JPEG first — reliable EXIF
                    val file = group.jpg?.file ?: return@async
                    try {
                        val exif = ExifInterface(FileInputStream(file))
                        val ori =
                            exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            )
                        if (ori != ExifInterface.ORIENTATION_NORMAL) {
                            orientationCache[group.cacheKey] = ori
                        }
                    } catch (_: Exception) {
                        /* ignore */
                    }
                }
            }
            .awaitAll()
        Log.info(tag = TAG) { "EXIF orientations preloaded: ${orientationCache.size}" }
    }

    /**
     * Dimension-based orientation fallback for groups whose EXIF orientation is still unknown
     * (e.g., RAW-only photos where NEF EXIF parsing failed).
     *
     * Uses [BitmapFactory.Options.inJustDecodeBounds] to read the image dimensions without decoding
     * the full pixel data — fast and works on both JPEG and NEF (via the embedded preview).
     */
    private fun populateOrientationsFromDimensions(groups: List<LocalPhotoGroup>) {
        var filled = 0
        for (group in groups) {
            if (orientationCache.containsKey(group.cacheKey)) continue
            val file = group.jpg?.file ?: group.raw?.file ?: continue
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0 && opts.outWidth < opts.outHeight) {
                    orientationCache[group.cacheKey] = ExifInterface.ORIENTATION_ROTATE_90
                    filled++
                }
            } catch (_: Exception) {
                /* skip */
            }
        }
        if (filled > 0) {
            Log.info(tag = TAG) { "Dimension-based orientations filled: $filled" }
        }
    }

    // ── Thumbnail loading ───────────────────────────────────────────────────

    /** Loads a downscaled thumbnail from a local file. Fast — local FS, no MTP overhead. */
    suspend fun loadThumbnail(file: File, maxSize: Int = 360): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, maxSize)
                opts.inJustDecodeBounds = false
                val bitmap =
                    BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@withContext null
                // Compress to JPEG bytes for display
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                bitmap.recycle()
                out.toByteArray()
            } catch (_: Exception) {
                null
            }
        }

    private fun calculateInSampleSize(w: Int, h: Int, maxDim: Int): Int {
        var size = 1
        while (w / size > maxDim || h / size > maxDim) size *= 2
        return size
    }
}
