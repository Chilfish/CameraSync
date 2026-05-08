package dev.sebastiano.camerasync.usb

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import com.juul.khronicle.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
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

class LocalPhotosViewModel(private val app: Application) {

    var groups by mutableStateOf<List<LocalPhotoGroup>>(emptyList())
        private set

    private val _loading = mutableStateOf(false)
    val loading: State<Boolean> = _loading

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** EXIF orientation cache, keyed by cacheKey (derived from file path). */
    private val orientationCache = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    fun getOrientation(cacheKey: Int): Int? = orientationCache[cacheKey]

    fun load() {
        scope.launch {
            _loading.value = true
            val result = withContext(Dispatchers.IO) { scanDirectories() }
            groups = result
            // Pre-load EXIF orientations in parallel
            preloadOrientations(result)
            _loading.value = false
        }
    }

    fun stop() {
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    // ── Directory scanning ──────────────────────────────────────────────────

    private fun scanDirectories(): List<LocalPhotoGroup> {
        val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CameraSync")
        if (!base.exists() || !base.isDirectory) {
            Log.info(tag = TAG) { "CameraSync directory not found: ${base.absolutePath}" }
            return emptyList()
        }

        // CameraSync/{camera model}/{date folder}/*.jpg|*.nef
        val allFiles = mutableListOf<File>()
        base.listFiles()?.filter { it.isDirectory }?.forEach { cameraDir ->
            walkDateDirs(cameraDir, allFiles)
        }

        return groupByBaseName(allFiles).sortedByDescending { group ->
            maxOf(
                group.jpg?.dateModified ?: 0L,
                group.raw?.dateModified ?: 0L,
            )
        }
    }

    private fun walkDateDirs(dir: File, out: MutableList<File>) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                walkDateDirs(child, out)
            } else {
                val name = child.name.lowercase()
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".nef")) {
                    out.add(child)
                }
            }
        }
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
                baseName = list.first().name.let {
                    it.substringBeforeLast(".").replace("_", " ")
                },
                jpg = jpg,
                raw = raw,
                cacheKey = cacheKey,
            )
        }
    }

    private fun toLocalPhoto(file: File) = LocalPhoto(
        file = file,
        name = file.name,
        dateModified = file.lastModified(),
        size = file.length(),
        isRaw = file.extension.lowercase() == "nef",
    )

    // ── EXIF orientation ────────────────────────────────────────────────────

    /** Reads EXIF orientation from JPEG (or RAW as fallback) in parallel. */
    private suspend fun preloadOrientations(groups: List<LocalPhotoGroup>) {
        groups.map { group ->
            scope.async {
                if (orientationCache.containsKey(group.cacheKey)) return@async
                // Try JPEG first — reliable EXIF
                val file = group.jpg?.file ?: group.raw?.file ?: return@async
                try {
                    val exif = ExifInterface(FileInputStream(file))
                    val ori = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                    if (ori != ExifInterface.ORIENTATION_NORMAL) {
                        orientationCache[group.cacheKey] = ori
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }.awaitAll()
        Log.info(tag = TAG) { "Preloaded ${orientationCache.size} orientations" }
    }

    // ── Thumbnail loading ───────────────────────────────────────────────────

    /**
     * Loads a downscaled thumbnail from a local file. Fast — local FS, no MTP overhead.
     */
    suspend fun loadThumbnail(file: File, maxSize: Int = 360): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, maxSize)
                opts.inJustDecodeBounds = false
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@withContext null
                // Compress to JPEG bytes for display
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                bitmap.recycle()
                out.toByteArray()
            } catch (_: Exception) { null }
        }

    private fun calculateInSampleSize(w: Int, h: Int, maxDim: Int): Int {
        var size = 1
        while (w / size > maxDim || h / size > maxDim) size *= 2
        return size
    }
}
