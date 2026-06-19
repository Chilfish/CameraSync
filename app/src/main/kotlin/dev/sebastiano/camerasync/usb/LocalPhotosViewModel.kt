package dev.sebastiano.camerasync.usb

import android.app.Application
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juul.khronicle.Log
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LocalPhotosVM"

// ── Data Models ──────────────────────────────────────────────────────────────

/** A photo file found in the local export directory. */
data class LocalPhoto(
    val file: File,
    val uri: android.net.Uri,
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
    /** Stable key for LazyVerticalStaggeredGrid — derived from file path hash. */
    val cacheKey: Int,
    /** The preferred file to use as Coil/AsyncImage model (JPEG if available, else RAW). */
    val displayFile: File,
)

/** A folder/directory containing photos, for the directory browser view. */
data class LocalFolder(
    val name: String,
    val relativePath: String,
    val photoCount: Int,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

class LocalPhotosViewModel(
    private val app: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Current directory path (null = root: Pictures/CameraSync). */
    var currentPath by mutableStateOf<String?>(null)
        private set

    /** Whether we're showing the folder list (true) or photo grid (false). */
    val isBrowsingFolder: Boolean get() = currentPath != null

    /** List of sub-folders at current level. */
    var folders by mutableStateOf<List<LocalFolder>>(emptyList())
        private set

    /** Photo groups at current level. */
    var groups by mutableStateOf<List<LocalPhotoGroup>>(emptyList())
        private set

    private val _loading = mutableStateOf(false)
    val loading: State<Boolean> = _loading

    var isRefreshing by mutableStateOf(false)
        private set

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Prevents concurrent scans — MediaStore cursor queries must be serialized. */
    private val scanMutex = Mutex()

    /** The base directory: Pictures/CameraSync */
    private val baseDir =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "CameraSync",
        ).absolutePath + "/"

    /**
     * Load the root view: folders at Pictures/CameraSync + photos at root level.
     * Called on initial enter and pull-to-refresh.
     */
    fun loadRoot() {
        scope.launch {
            scanMutex.withLock {
                _loading.value = true
                isRefreshing = true
                currentPath = null
                try {
                    withContext(ioDispatcher) {
                        folders = queryFolders(null)
                        groups = queryPhotos(null)
                    }
                } catch (e: Exception) {
                    Log.warn(tag = TAG, throwable = e) { "loadRoot failed" }
                } finally {
                    _loading.value = false
                    isRefreshing = false
                }
            }
        }
    }

    /**
     * Enter a sub-folder: show photos within [relativePath] and its own sub-folders.
     *
     * @param relativePath e.g. "Pictures/CameraSync/Nikon Z30/"
     */
    fun enterFolder(relativePath: String) {
        scope.launch {
            scanMutex.withLock {
                _loading.value = true
                currentPath = relativePath
                try {
                    withContext(ioDispatcher) {
                        folders = queryFolders(relativePath)
                        groups = queryPhotos(relativePath)
                    }
                } catch (e: Exception) {
                    Log.warn(tag = TAG, throwable = e) { "enterFolder failed: $relativePath" }
                } finally {
                    _loading.value = false
                }
            }
        }
    }

    /** Go back to parent directory. If already at root, no-op. */
    fun goBack() {
        val path = currentPath ?: return
        // Strip trailing "/" then find parent: "Pictures/CameraSync/Nikon Z30/" → null (root)
        val clean = path.trimEnd('/')
        val lastSlash = clean.lastIndexOf('/')
        currentPath =
            if (lastSlash <= 0) null // back to root
            else clean.substring(0, lastSlash + 1)
        loadCurrent()
    }

    /** Re-load the current level. */
    fun refresh() = loadCurrent()

    fun stop() {
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private fun loadCurrent() {
        val path = currentPath
        if (path == null) loadRoot() else enterFolder(path)
    }

    // ── MediaStore Queries ────────────────────────────────────────────────────

    /**
     * Queries MediaStore for sub-directories under [parentPath].
     * Uses DISTINCT on RELATIVE_PATH to get unique folder names.
     *
     * @param parentPath null = root Pictures/CameraSync
     * @return sorted list of folders (alphabetically by name)
     */
    private fun queryFolders(parentPath: String?): List<LocalFolder> {
        val likePattern =
            if (parentPath == null) "Pictures/CameraSync/%"
            else "${parentPath}%"

        // Query 1: Images
        val imageFolders = mutableSetOf<String>()
        val imageCounts = mutableMapOf<String, Int>()

        try {
            app.contentResolver
                .query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media.RELATIVE_PATH),
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.RELATIVE_PATH} != ?",
                    arrayOf(likePattern, parentPath ?: "Pictures/CameraSync/"),
                    null,
                )
                ?.use { cursor ->
                    val pathCol =
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val fullPath = cursor.getString(pathCol) ?: continue
                        val subFolder = extractSubFolder(fullPath, parentPath) ?: continue
                        imageFolders.add(subFolder)
                        imageCounts[subFolder] = (imageCounts[subFolder] ?: 0) + 1
                    }
                }
        } catch (_: Exception) {
            /* MediaStore can fail */
        }

        // Query 2: Files (NEF — often indexed under MediaStore.Files, not Images)
        try {
            app.contentResolver
                .query(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH),
                    "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} != ?",
                    arrayOf(likePattern, parentPath ?: "Pictures/CameraSync/"),
                    null,
                )
                ?.use { cursor ->
                    val pathCol =
                        cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val fullPath = cursor.getString(pathCol) ?: continue
                        val subFolder = extractSubFolder(fullPath, parentPath) ?: continue
                        imageFolders.add(subFolder)
                    }
                }
        } catch (_: Exception) {
            /* MediaStore.Files can fail */
        }

        return imageFolders
            .map { folder ->
                LocalFolder(
                    name = folder.trimEnd('/').substringAfterLast('/'),
                    relativePath = folder,
                    photoCount = imageCounts[folder] ?: 0,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Given a full RELATIVE_PATH like "Pictures/CameraSync/Nikon Z30/DSC_0001.JPG"
     * and a parent like "Pictures/CameraSync/", returns the immediate child folder:
     * "Pictures/CameraSync/Nikon Z30/".
     *
     * Returns null if the path points to a file directly in the parent.
     */
    private fun extractSubFolder(fullPath: String, parentPath: String?): String? {
        val prefix = parentPath ?: "Pictures/CameraSync/"
        if (!fullPath.startsWith(prefix)) return null
        val relative = fullPath.removePrefix(prefix)
        val firstSlash = relative.indexOf('/')
        if (firstSlash <= 0) return null // file is directly in parent, not a sub-folder
        return prefix + relative.substring(0, firstSlash + 1)
    }

    /**
     * Queries MediaStore for photos (JPEG + NEF) under [parentPath].
     *
     * @param parentPath null = root Pictures/CameraSync
     * @return grouped RAW+JPEG pairs, sorted by date descending
     */
    private fun queryPhotos(parentPath: String?): List<LocalPhotoGroup> {
        val files = mutableSetOf<LocalPhoto>()

        val pathClause =
            if (parentPath == null) "Pictures/CameraSync/"
            else parentPath

        // Query 1: MediaStore.Images for JPEG
        try {
            app.contentResolver
                .query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PHOTO_PROJECTION,
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                    arrayOf(pathClause),
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
                )
                ?.use { cursor -> files.addAll(readPhotoCursor(cursor, false)) }
        } catch (_: Exception) {}
        Log.info(tag = TAG) { "MediaStore.Images found ${files.size} JPEGs in $pathClause" }

        // Query 2: MediaStore.Files for NEF
        try {
            app.contentResolver
                .query(
                    MediaStore.Files.getContentUri("external"),
                    FILE_PROJECTION,
                    "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} = ?",
                    arrayOf(pathClause, "image/x-nikon-nef"),
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
                )
                ?.use { cursor -> files.addAll(readFileCursor(cursor)) }
        } catch (_: Exception) {}
        Log.info(tag = TAG) { "Total files (Images + Files): ${files.size}" }

        return groupByBaseName(files.toList())
    }

    // ── Cursor Parsing ────────────────────────────────────────────────────────

    private fun readPhotoCursor(
        cursor: android.database.Cursor,
        isRaw: Boolean,
    ): List<LocalPhoto> {
        val results = mutableListOf<LocalPhoto>()
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        while (cursor.moveToNext()) {
            val data = cursor.getString(dataCol) ?: continue
            val id = cursor.getLong(idCol)
            val uri =
                android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
            results.add(
                LocalPhoto(
                    file = File(data),
                    uri = uri,
                    name = cursor.getString(nameCol) ?: "?",
                    dateModified = cursor.getLong(dateCol),
                    size = cursor.getLong(sizeCol),
                    isRaw = isRaw,
                )
            )
        }
        return results
    }

    private fun readFileCursor(cursor: android.database.Cursor): List<LocalPhoto> {
        val results = mutableListOf<LocalPhoto>()
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
        val nameCol =
            cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val dateCol =
            cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        while (cursor.moveToNext()) {
            val data = cursor.getString(dataCol) ?: continue
            val id = cursor.getLong(idCol)
            val uri =
                android.content.ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"),
                    id,
                )
            results.add(
                LocalPhoto(
                    file = File(data),
                    uri = uri,
                    name = cursor.getString(nameCol) ?: "?",
                    dateModified = cursor.getLong(dateCol),
                    size = cursor.getLong(sizeCol),
                    isRaw = true,
                )
            )
        }
        return results
    }

    // ── Grouping ──────────────────────────────────────────────────────────────

    private fun groupByBaseName(photos: List<LocalPhoto>): List<LocalPhotoGroup> {
        val map = linkedMapOf<String, MutableList<LocalPhoto>>()
        for (p in photos) {
            val base = p.file.nameWithoutExtension.uppercase()
            map.getOrPut(base) { mutableListOf() }.add(p)
        }
        return map.values.map { list ->
            val jpg = list.firstOrNull { !it.isRaw }
            val raw = list.firstOrNull { it.isRaw }
            val displayFile = jpg?.file ?: raw?.file ?: list.first().file
            LocalPhotoGroup(
                baseName =
                    list.first().name.let { it.substringBeforeLast(".").replace("_", " ") },
                jpg = jpg,
                raw = raw,
                cacheKey = displayFile.absolutePath.hashCode(),
                displayFile = displayFile,
            )
        }.sortedByDescending { group ->
            maxOf(group.jpg?.dateModified ?: 0L, group.raw?.dateModified ?: 0L)
        }
    }

    // ── Projection Constants ──────────────────────────────────────────────────

    companion object {
        private val PHOTO_PROJECTION =
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
            )

        private val FILE_PROJECTION =
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE,
            )
    }
}
