package dev.sebastiano.camerasync.usb

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.sebastiano.camerasync.camera.PhotoInfo

/**
 * Tracks which MTP photos have already been imported so future syncs skip them.
 *
 * Keys are (storageId, handle). MTP handles are session-scoped and can be reused after the camera
 * reboots or the card is reformatted, so each key stores a soft identity (name + size). A handle
 * that now points to a different photo no longer matches and is treated as not-yet-imported — this
 * is what prevents silently skipping a newly-shot photo that happened to receive a recycled handle,
 * without needing to prune old handles on reconnect.
 *
 * Each record also carries a monotonic sequence (appended after [SEQUENCE_SEPARATOR]) so the table
 * can be capped: once it exceeds [maxEntries] the oldest records are evicted, keeping
 * SharedPreferences (loaded wholesale at process start) bounded over years of use (R30). The
 * identity comparison only looks at the part before the separator, so the format stays
 * backward-compatible with records written before sequences existed.
 */
class PhotoSyncManager(
    private val prefs: SharedPreferences,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    constructor(
        context: Context
    ) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /** Returns true if this photo was imported in a previous session with the same identity. */
    fun isAlreadyImported(photo: PhotoInfo): Boolean {
        val stored = prefs.getString(key(photo), null) ?: return false
        return stored.substringBefore(SEQUENCE_SEPARATOR) == identity(photo)
    }

    /** Marks a photo as imported so future syncs skip it, evicting the oldest records if needed. */
    fun markAsImported(photo: PhotoInfo) {
        prefs.edit { putString(key(photo), identity(photo) + SEQUENCE_SEPARATOR + nextSequence()) }
        pruneOldestIfNeeded()
    }

    /** Clears all imported records (e.g., when camera storage is reformatted). */
    fun clearAll() {
        prefs.edit { clear() }
    }

    /** Removes tracked records for a specific storage (e.g., when that card is swapped out). */
    fun clearStorage(storageId: Int) {
        prefs.edit {
            val prefix = "s${storageId}_"
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }
    }

    /** Total number of tracked photos (excludes the internal sequence counter). */
    val trackedCount: Int
        get() = prefs.all.keys.count { isPhotoKey(it) }

    private fun key(photo: PhotoInfo): String = "s${photo.storageId}_h${photo.handle}"

    private fun identity(photo: PhotoInfo): String = "${photo.name}:${photo.size}"

    private fun isPhotoKey(key: String): Boolean = PHOTO_KEY.matches(key)

    private fun nextSequence(): Long {
        val next = prefs.getLong(KEY_SEQUENCE, 0L) + 1
        prefs.edit { putLong(KEY_SEQUENCE, next) }
        return next
    }

    /** Evicts oldest-first once the table grows past [maxEntries] (R30). */
    private fun pruneOldestIfNeeded() {
        val allKeys = prefs.all.keys
        // Cheap on-device check (map size); only do the precise work when the cap is exceeded.
        if (allKeys.size <= maxEntries) return
        val photoKeys = allKeys.filter { isPhotoKey(it) }
        if (photoKeys.size <= maxEntries) return
        val oldestFirst =
            photoKeys.map { k -> k to sequenceOf(prefs.getString(k, null)) }.sortedBy { it.second }
        val toRemove = oldestFirst.take(photoKeys.size - maxEntries).map { it.first }
        prefs.edit { toRemove.forEach { remove(it) } }
    }

    private fun sequenceOf(stored: String?): Long =
        stored?.substringAfter(SEQUENCE_SEPARATOR, "")?.toLongOrNull() ?: 0L

    companion object {
        private const val PREFS_NAME = "camera_sync_usb_imports"
        private const val KEY_SEQUENCE = "__sequence"
        private const val SEQUENCE_SEPARATOR = "|"

        /** Upper bound on tracked records before the oldest are evicted. */
        private const val MAX_ENTRIES = 10_000

        private val PHOTO_KEY = Regex("^s\\d+_h\\d+$")
    }
}
