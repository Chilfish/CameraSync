package dev.sebastiano.camerasync.usb

import android.content.Context
import android.content.SharedPreferences

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
    fun isAlreadyImported(storageId: Int, handle: Int): Boolean {
        return prefs.getBoolean(key(storageId, handle), false)
    }

    /** Marks a photo as imported so future syncs skip it. */
    fun markAsImported(storageId: Int, handle: Int) {
        prefs.edit().putBoolean(key(storageId, handle), true).apply()
    }

    /** Clears all imported handles (e.g., when camera storage is reformatted). */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** Removes tracked handles for a specific storage. */
    fun clearStorage(storageId: Int) {
        val editor = prefs.edit()
        val prefix = "s${storageId}_"
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Returns the total number of tracked handles. */
    val trackedCount: Int
        get() = prefs.all.size

    private fun key(storageId: Int, handle: Int): String = "s${storageId}_h$handle"

    companion object {
        private const val PREFS_NAME = "camera_sync_usb_imports"
    }
}
