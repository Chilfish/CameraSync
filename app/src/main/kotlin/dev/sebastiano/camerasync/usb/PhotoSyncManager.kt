package dev.sebastiano.camerasync.usb

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Tracks which MTP photos have already been imported so future syncs skip them.
 *
 * Keys are (storageId, handle). MTP handles are session-scoped and can be reused after the camera
 * reboots or the card is reformatted, so each key stores a soft identity (name + size). A handle
 * that now points to a different photo no longer matches and is treated as not-yet-imported — this
 * is what prevents silently skipping a newly-shot photo that happened to receive a recycled handle,
 * without needing to prune old handles on reconnect.
 */
class PhotoSyncManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns true if this photo was imported in a previous session with the same identity. */
    fun isAlreadyImported(photo: NikonUsbManager.PhotoInfo): Boolean =
        prefs.getString(key(photo), null) == identity(photo)

    /** Marks a photo as imported so future syncs skip it. */
    fun markAsImported(photo: NikonUsbManager.PhotoInfo) {
        prefs.edit { putString(key(photo), identity(photo)) }
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

    /** Returns the total number of tracked photos. */
    val trackedCount: Int
        get() = prefs.all.size

    private fun key(photo: NikonUsbManager.PhotoInfo): String =
        "s${photo.storageId}_h${photo.handle}"

    private fun identity(photo: NikonUsbManager.PhotoInfo): String = "${photo.name}:${photo.size}"

    companion object {
        private const val PREFS_NAME = "camera_sync_usb_imports"
    }
}
