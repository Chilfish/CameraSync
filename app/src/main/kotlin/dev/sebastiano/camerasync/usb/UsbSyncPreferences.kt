package dev.sebastiano.camerasync.usb

import android.content.Context
import android.content.SharedPreferences

/** Persisted preferences for USB photo sync. */
class UsbSyncPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** If true, the sync service auto-starts when a USB camera is attached. */
    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    /** Which photo formats to include when listing photos. */
    var downloadFormat: DownloadFormat
        get() =
            prefs.getString(KEY_FORMAT, DownloadFormat.ALL.name)?.let {
                try {
                    DownloadFormat.valueOf(it)
                } catch (_: Exception) {
                    DownloadFormat.ALL
                }
            } ?: DownloadFormat.ALL
        set(value) = prefs.edit().putString(KEY_FORMAT, value.name).apply()

    /** How photos are grouped in the gallery view. */
    var photoGrouping: PhotoGrouping
        get() =
            prefs.getString(KEY_GROUPING, PhotoGrouping.BY_FOLDER.name)?.let {
                try {
                    PhotoGrouping.valueOf(it)
                } catch (_: Exception) {
                    PhotoGrouping.BY_FOLDER
                }
            } ?: PhotoGrouping.BY_FOLDER
        set(value) = prefs.edit().putString(KEY_GROUPING, value.name).apply()

    /** How photos are sorted in the gallery view. */
    var photoSorting: PhotoSorting
        get() =
            prefs.getString(KEY_SORTING, PhotoSorting.DATE_DESC.name)?.let {
                try {
                    PhotoSorting.valueOf(it)
                } catch (_: Exception) {
                    PhotoSorting.DATE_DESC
                }
            } ?: PhotoSorting.DATE_DESC
        set(value) = prefs.edit().putString(KEY_SORTING, value.name).apply()

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

    /** How photos are grouped in the gallery grid. */
    enum class PhotoGrouping {
        /** Group by MTP folder hierarchy (default). */
        BY_FOLDER,
        /** Group by capture date (YYYY-MM-DD). */
        BY_DATE,
        /** No grouping — show all photos in a flat list. */
        FLAT,
    }

    /** How photos are sorted within groups. */
    enum class PhotoSorting {
        /** Newest first (default). */
        DATE_DESC,
        /** Oldest first. */
        DATE_ASC,
        /** Alphabetical by filename (A-Z). */
        NAME_ASC,
        /** Alphabetical by filename (Z-A). */
        NAME_DESC,
        /** Largest file size first. */
        SIZE_DESC,
    }

    /** Theme mode: "system" | "light" | "dark". */
    fun getThemeMode(): String = prefs.getString("theme_mode", "system") ?: "system"

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
    }

    /** Grid column count for photo gallery (2, 3, or 4). */
    fun getGridColumns(): Int = prefs.getInt("grid_columns", GRID_COLUMNS_DEFAULT)

    fun setGridColumns(columns: Int) {
        prefs.edit().putInt("grid_columns", columns).apply()
    }

    /** Transfer history — persisted as a simple delimited string. */
    fun getTransferHistory(): List<TransferRecord> {
        val json = prefs.getString(TRANSFER_HISTORY_KEY, null) ?: return emptyList()
        return try {
            // Simple format: "date1|count1|camera1;date2|count2|camera2"
            json
                .split(";")
                .filter { it.isNotBlank() }
                .map { part ->
                    val parts = part.split("|")
                    TransferRecord(parts[0], parts[1].toInt(), parts.getOrElse(2) { "Nikon" })
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addTransferRecord(count: Int, cameraModel: String = "Nikon") {
        val date =
            java.text
                .SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
        val newEntry = "$date|$count|$cameraModel"
        val existing = prefs.getString(TRANSFER_HISTORY_KEY, null) ?: ""
        val updated = "$newEntry;$existing".take(5000) // keep last ~50 entries
        prefs.edit().putString(TRANSFER_HISTORY_KEY, updated).apply()
    }

    companion object {
        private const val PREFS_NAME = "camera_sync_usb_prefs"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_FORMAT = "download_format"
        private const val KEY_GROUPING = "photo_grouping"
        private const val KEY_SORTING = "photo_sorting"
        const val GRID_COLUMNS_DEFAULT = 3
        private const val TRANSFER_HISTORY_KEY = "transfer_history"
    }
}
