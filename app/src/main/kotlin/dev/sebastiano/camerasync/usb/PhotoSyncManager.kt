package dev.sebastiano.camerasync.usb

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Dedup key for an MTP photo: storageId + handle. Both are needed because MTP handles are only
 * unique within a storage, and the pair is the single source of truth shared by the foreground UI
 * and the background sync pipeline.
 */
data class DedupKey(val storageId: Int, val handle: Int)

/**
 * Tracks which MTP photo handles have already been imported, enabling deduplication across sync
 * sessions.
 *
 * Handles are tied to a specific USB session — if the camera's session changes, old handles become
 * invalid and will be pruned automatically.
 */
class PhotoSyncManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns true if the photo has already been imported in a previous session. */
    fun isAlreadyImported(dedupKey: DedupKey): Boolean = prefs.getBoolean(key(dedupKey), false)

    /** Marks a photo as imported so future syncs skip it. */
    fun markAsImported(dedupKey: DedupKey) {
        prefs.edit { putBoolean(key(dedupKey), true) }
    }

    /** Clears all imported handles (e.g., when camera storage is reformatted). */
    fun clearAll() {
        prefs.edit { clear() }
    }

    /** Removes tracked handles for a specific storage. */
    fun clearStorage(storageId: Int) {
        prefs.edit {
            val prefix = "s${storageId}_"
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }
    }

    /** Returns the total number of tracked handles. */
    val trackedCount: Int
        get() = prefs.all.size

    private fun key(dedupKey: DedupKey): String = "s${dedupKey.storageId}_h${dedupKey.handle}"

    companion object {
        private const val PREFS_NAME = "camera_sync_usb_imports"
    }
}
