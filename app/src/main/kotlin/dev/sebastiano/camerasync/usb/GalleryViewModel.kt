package dev.sebastiano.camerasync.usb

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// ── State ──────────────────────────────────────────────────────────────────

sealed interface GalleryState {
    data object Disconnected : GalleryState

    data object Connecting : GalleryState

    data class Loading(val message: String, val progress: Int = 0, val total: Int = 0) :
        GalleryState

    data class Browsing(
        val cameraInfo: NikonUsbManager.CameraInfo?,
        val storages: List<NikonUsbManager.StorageInfo>,
        val entries: List<GalleryEntry>,
    ) : GalleryState

    data object Empty : GalleryState

    data class Error(val message: String) : GalleryState

    data class Transferring(val progress: TransferProgress) : GalleryState

    data class TransferDone(val synced: Int, val savedUris: List<android.net.Uri> = emptyList()) :
        GalleryState
}

data class TransferProgress(
    val synced: Int,
    val total: Int,
    val currentFile: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val startTimeMillis: Long,
) {
    val speedBps: Double
        get() {
            val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000.0
            return if (elapsed > 2 && bytesTransferred > 0) bytesTransferred / elapsed else 0.0
        }

    val speedFormatted: String
        get() =
            when {
                speedBps >= 1_000_000 -> "%.1f MB/s".format(speedBps / 1_000_000)
                speedBps >= 1_000 -> "%d KB/s".format((speedBps / 1_000).toInt())
                speedBps > 0 -> "%.0f B/s".format(speedBps)
                else -> "计算中…"
            }

    val etaSeconds: Long
        get() {
            val remaining = totalBytes - bytesTransferred
            return if (speedBps > 0) (remaining / speedBps).toLong() else -1
        }

    val etaFormatted: String
        get() =
            when {
                etaSeconds < 0 -> "计算中…"
                etaSeconds < 60 -> "还剩 ${etaSeconds}s"
                else -> "还剩 ${etaSeconds / 60}m ${etaSeconds % 60}s"
            }
}

sealed interface GalleryEntry {
    data class Folder(val info: NikonUsbManager.FolderInfo, val storageId: Int) : GalleryEntry

    data class DateSection(val date: String, val count: Int) : GalleryEntry

    data class PhotoGroup(
        val baseName: String,
        val raw: NikonUsbManager.PhotoInfo?,
        val jpg: NikonUsbManager.PhotoInfo?,
    ) : GalleryEntry {
        /**
         * Handle to use for thumbnail preview — JPEG if available, else RAW. null if group is
         * empty.
         */
        val previewHandle: Int?
            get() = jpg?.handle ?: raw?.handle

        val hasRaw: Boolean
            get() = raw != null
    }
}

enum class PhotoFilter {
    ALL,
    NEW,
    RAW_ONLY,
    JPEG_ONLY,
}

// ── ViewModel facade ───────────────────────────────────────────────────────

/**
 * Facade over the four focused modules extracted in P2-1: [GalleryStateMachine] (state +
 * selection/filter/sort), [ThumbnailProvider] (caches + EXIF), [TransferEngine] (transfer
 * orchestration) and [ConnectionManager] (USB lifecycle + browsing). Keeps the exact public API the
 * screens consume.
 */
class GalleryViewModel(private val app: Application) {
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val nikon = NikonUsbManager(usbManager)
    private val photoSyncManager = PhotoSyncManager(app)

    /** Preferences (auto-sync, format, grouping, sorting, theme, history). */
    val prefs = UsbSyncPreferences(app)

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val stateMachine =
        GalleryStateMachine(photoSyncManager, prefs.photoGrouping, prefs.photoSorting)

    private val thumbnails =
        ThumbnailProvider(scope, app, { connection.mtp }, { stateMachine.currentPhotos })

    private val transferEngine =
        TransferEngine(
            scope,
            app,
            nikon,
            photoSyncManager,
            stateMachine,
            prefs,
            { connection.mtp },
            { connection.cameraInfo },
            { connection.cancelActiveLoad() },
        )

    private val connection: ConnectionManager =
        ConnectionManager(
            app,
            usbManager,
            nikon,
            scope,
            prefs,
            stateMachine,
            thumbnails,
            transferEngine,
        )

    // ── State & selection ──────────────────────────────────────────────────

    val state: State<GalleryState>
        get() = stateMachine.state

    // SnapshotStateList — any composable reading this list automatically
    // recomposes when the list is modified (no manual trigger needed).
    val selectedCount: Int
        get() = stateMachine.selectedCount

    /** Handles that were successfully transferred in the last [startTransfer] call. */
    val lastTransferredHandles: List<Int>
        get() = transferEngine.lastTransferredHandles

    /** Handles that failed during the last transfer attempt. Populated in [performTransfer]. */
    val failedHandles: List<Int>
        get() = transferEngine.failedHandles

    /** Camera battery level (0–100), or null if the device doesn't report it. */
    val batteryLevel: Int?
        get() = connection.batteryLevel

    // ── UI state & reload ──────────────────────────────────────────────────

    /**
     * Current grid column count (2, 3, or 4). Compose-reactive so the LazyVerticalStaggeredGrid
     * recomposes when columns change. Initialized from [prefs] so the last chosen value survives
     * app restarts.
     */
    var gridColumns by mutableStateOf(prefs.getGridColumns())

    /** Set to true by [requestReload] to signal the UI to reload the gallery. */
    var needsReload: Boolean
        get() = connection.needsReload
        set(value) {
            connection.needsReload = value
        }

    /** Requests a gallery reload when the user returns from settings, etc. */
    fun requestReload() {
        connection.requestReload()
    }

    /** Inline error banner message — shown above content instead of replacing the entire screen. */
    val errorBanner: String?
        get() = connection.errorBanner

    fun clearErrorBanner() {
        connection.clearErrorBanner()
    }

    /** Current photo grouping mode. */
    val groupingMode: UsbSyncPreferences.PhotoGrouping
        get() = stateMachine.groupingMode

    /** Current photo sorting mode. */
    val sortingMode: UsbSyncPreferences.PhotoSorting
        get() = stateMachine.sortingMode

    /**
     * Photo groups for the current view. Compose-reactive so the grid recomposes when they change.
     */
    val currentPhotos: List<GalleryEntry.PhotoGroup>
        get() = stateMachine.currentPhotos

    // ── Lifecycle & browsing ───────────────────────────────────────────────

    fun start() {
        connection.start()
    }

    /** Only unregisters the receiver — does NOT close MTP. */
    fun stop() {
        connection.stop()
        transferEngine.cancelTransfer()
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    /** Load root level: all storages → folders + loose photos, progressively. */
    suspend fun loadRoot() {
        connection.loadRoot()
    }

    /** Load photos inside a specific folder. Called when entering a folder route. */
    suspend fun loadFolder(storageId: Int, folderHandle: Int) {
        connection.loadFolder(storageId, folderHandle)
    }

    /** Pull-to-refresh: reload current level without jumping to root. */
    fun refresh() {
        connection.refresh()
    }

    fun dismissTransferDone() {
        connection.dismissTransferDone()
    }

    // ── Thumbnails & full-photo download ───────────────────────────────────

    fun getOrientation(handle: Int): Int? = thumbnails.getOrientation(handle)

    /** Decoded-bitmap cache exposed to the grid so recycled cells skip re-decoding. */
    val bitmapCache: MutableMap<Int, android.graphics.Bitmap>
        get() = thumbnails.bitmapCache

    fun getThumbnail(handle: Int): ByteArray? = thumbnails.getThumbnail(handle)

    /**
     * Downloads the full photo file (NEF/JPEG/etc) to a ByteArray for EXIF extraction. See
     * [ThumbnailProvider.downloadFullPhoto].
     */
    suspend fun downloadFullPhoto(handle: Int): ByteArray? = thumbnails.downloadFullPhoto(handle)

    // ── Selection & filtering (delegated to GalleryStateMachine) ────────────

    /** Returns true if any photo in the group has already been imported. */
    fun isGroupImported(group: GalleryEntry.PhotoGroup): Boolean =
        stateMachine.isGroupImported(group)

    fun toggleSelection(group: GalleryEntry.PhotoGroup) {
        stateMachine.toggleSelection(group, prefs.downloadFormat)
    }

    fun selectAll() {
        stateMachine.selectAll(prefs.downloadFormat)
    }

    /**
     * Selects the transferable handles of all not-yet-imported groups, respecting download format.
     */
    fun selectAllNew() {
        stateMachine.selectAllNew(prefs.downloadFormat)
    }

    fun deselectAll() {
        stateMachine.deselectAll()
    }

    fun isSelected(h: Int) = stateMachine.isSelected(h)

    fun isGroupSelected(group: GalleryEntry.PhotoGroup): Boolean =
        stateMachine.isGroupSelected(group)

    // ── Filtering ──────────────────────────────────────────────────────────

    // Default view is the new-photos filter: connecting lands on what's ready to transfer (P1-2).
    val filterMode: PhotoFilter
        get() = stateMachine.filterMode

    fun setFilter(mode: PhotoFilter) {
        stateMachine.setFilter(mode)
    }

    fun getFilteredGroups(): List<GalleryEntry.PhotoGroup> = stateMachine.getFilteredGroups()

    fun getNewPhotoCount(): Int = stateMachine.getNewPhotoCount()

    // ── Grouping & format ──────────────────────────────────────────────────

    fun setGrouping(mode: UsbSyncPreferences.PhotoGrouping) {
        stateMachine.setGrouping(mode)
        prefs.photoGrouping = mode
    }

    fun setSorting(mode: UsbSyncPreferences.PhotoSorting) {
        stateMachine.setSorting(mode)
        prefs.photoSorting = mode
    }

    fun setDownloadFormat(format: UsbSyncPreferences.DownloadFormat) {
        // Download format controls which photos transfer (see handlesForFormat); it no longer
        // drives the default filter — new photos are the default view (P1-2).
        prefs.downloadFormat = format
    }

    // ── Transfer (delegated to TransferEngine) ─────────────────────────────

    fun startTransfer() {
        transferEngine.startTransfer()
    }

    fun retryFailedTransfers() {
        transferEngine.retryFailedTransfers()
    }

    /**
     * Deletes photos that were just transferred (using saved handles from TransferDone). Returns
     * the number of deleted photos.
     */
    suspend fun deleteTransferredPhotos(handles: List<Int>): Int =
        transferEngine.deleteTransferredPhotos(handles)

    companion object {
        fun groupByBaseFilename(
            photos: List<NikonUsbManager.PhotoInfo>
        ): List<GalleryEntry.PhotoGroup> {
            val map = linkedMapOf<String, MutableList<NikonUsbManager.PhotoInfo>>()
            for (p in photos) {
                val base = p.name.substringBeforeLast(".")
                map.getOrPut(base) { mutableListOf() }.add(p)
            }
            return map.map { (base, list) ->
                    GalleryEntry.PhotoGroup(
                        baseName = base,
                        raw =
                            list.find {
                                it.formatName == "NEF(RAW)" || it.name.endsWith(".NEF", true)
                            },
                        jpg =
                            list.find {
                                it.formatName in setOf("JPEG", "EXIF_JPEG") ||
                                    it.name.endsWith(".JPG", true)
                            },
                    )
                }
                // Filter out groups with no recognizable photo format (videos, system files, etc.)
                .filter { it.raw != null || it.jpg != null }
        }
    }
}
