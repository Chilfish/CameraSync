package dev.sebastiano.camerasync.usb

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted preferences for USB photo sync.
 */
class UsbSyncPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** If true, the sync service auto-starts when a USB camera is attached. */
    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    /** Which photo formats to include when listing photos. */
    var downloadFormat: DownloadFormat
        get() {
            val name = prefs.getString(KEY_FORMAT, DownloadFormat.ALL.name)
            return try {
                DownloadFormat.valueOf(name!!)
            } catch (_: Exception) {
                DownloadFormat.ALL
            }
        }
        set(value) = prefs.edit().putString(KEY_FORMAT, value.name).apply()

    /** Internal flag for auto-sync toggle changes. */
    val autoSyncFlow: SharedPreferences
        get() = prefs

    /** Which image formats to download. */
    enum class DownloadFormat {
        /** Download all recognized image formats. */
        ALL,
        /** Download JPEG only (skip NEF, TIFF, etc.). */
        JPEG_ONLY,
        /** Download RAW only (NEF). */
        RAW_ONLY,
    }

    companion object {
        private const val PREFS_NAME = "camera_sync_usb_prefs"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_FORMAT = "download_format"
    }
}
