package dev.sebastiano.camerasync.usb

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juul.khronicle.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "GalleryVM"
private const val ACTION_USB_PERMISSION = "dev.sebastiano.camerasync.USB_PERMISSION"

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

// ── ViewModel ──────────────────────────────────────────────────────────────

class GalleryViewModel(private val app: Application) {
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val nikon = NikonUsbManager(usbManager)
    private val photoSyncManager = PhotoSyncManager(app)

    /** Preferences (auto-sync, format, grouping, sorting, theme, history). */
    val prefs = UsbSyncPreferences(app)

    /** Pure state + selection + filter/sort logic (P2-1 extraction). */
    private val stateMachine =
        GalleryStateMachine(photoSyncManager, prefs.photoGrouping, prefs.photoSorting)

    val state: State<GalleryState>
        get() = stateMachine.state

    // SnapshotStateList — any composable reading this list automatically
    // recomposes when the list is modified (no manual trigger needed).
    val selectedCount: Int
        get() = stateMachine.selectedCount

    /** Handles that were successfully transferred in the last [startTransfer] call. */
    var lastTransferredHandles: List<Int> = emptyList()
        private set

    /** Handles that failed during the last transfer attempt. Populated in [performTransfer]. */
    var failedHandles: List<Int> = emptyList()
        private set

    /** Camera battery level (0–100), or null if the device doesn't report it. */
    var batteryLevel: Int? = null
        private set

    /**
     * Current grid column count (2, 3, or 4). Compose-reactive so the LazyVerticalStaggeredGrid
     * recomposes when columns change. Initialized from [prefs] so the last chosen value survives
     * app restarts.
     */
    var gridColumns by mutableStateOf(prefs.getGridColumns())

    /** Set to true by [requestReload] to signal the UI to reload the gallery. */
    var needsReload by mutableStateOf(false)

    /** Requests a gallery reload when the user returns from settings, etc. */
    fun requestReload() {
        needsReload = true
    }

    /** Inline error banner message — shown above content instead of replacing the entire screen. */
    var errorBanner by mutableStateOf<String?>(null)
        private set

    fun clearErrorBanner() {
        errorBanner = null
    }

    /** Current photo grouping mode. */
    val groupingMode: UsbSyncPreferences.PhotoGrouping
        get() = stateMachine.groupingMode

    /** Current photo sorting mode. */
    val sortingMode: UsbSyncPreferences.PhotoSorting
        get() = stateMachine.sortingMode

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var mtp: MtpDevice? = null
        private set

    private var syncJob: Job? = null

    // Folder navigation context — (storageId, folderHandle), null when at root.
    // Used by refresh() to reload the current folder instead of jumping to root.
    private var currentFolder: Pair<Int, Int>? = null

    // Device info (populated once on connect)
    var cameraInfo: NikonUsbManager.CameraInfo? = null
        private set

    var storages = emptyList<NikonUsbManager.StorageInfo>()
        private set

    // Photo groups for the current view. Compose-reactive so that
    // getFilteredGroups() / getNewPhotoCount() / filter chips recompose
    // when the underlying list changes.
    val currentPhotos: List<GalleryEntry.PhotoGroup>
        get() = stateMachine.currentPhotos

    // Thumbnail + orientation caches, preloading, and full-photo download (P2-1 extraction).
    private val thumbnails = ThumbnailProvider(scope, app, { mtp }, { stateMachine.currentPhotos })

    fun getOrientation(handle: Int): Int? = thumbnails.getOrientation(handle)

    /** Decoded-bitmap cache exposed to the grid so recycled cells skip re-decoding. */
    val bitmapCache: MutableMap<Int, android.graphics.Bitmap>
        get() = thumbnails.bitmapCache

    // ── Receiver & USB lifecycle ────────────────────────────────────────────

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                        getDevice(intent)?.let { onPlugged(it) }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> closeMtpAndClear()
                    ACTION_USB_PERMISSION -> {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                            getDevice(intent)?.let { connectAndBrowse() }
                        else stateMachine.setState(GalleryState.Error("USB 权限被拒绝"))
                    }
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun getDevice(i: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private var started = false

    fun start() {
        if (started) return
        started = true
        app.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            },
            Context.RECEIVER_EXPORTED,
        )
        usbManager.deviceList.values.firstOrNull { it.vendorId == 0x04B0 }?.let { onPlugged(it) }
    }

    /** Only unregisters the receiver — does NOT close MTP. */
    fun stop() {
        syncJob?.cancel()
        scope.cancel()
        try {
            app.unregisterReceiver(receiver)
        } catch (_: Exception) {}
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        started = false
    }

    /** Closes MTP and clears all state. Called on USB detach. */
    private fun closeMtpAndClear() {
        syncJob?.cancel()
        closeMtp()
        stateMachine.selected.clear()
        stateMachine.setState(GalleryState.Disconnected)
        stateMachine.updateCurrentPhotos(emptyList())
        thumbnails.clearAll()
    }

    private fun onPlugged(device: UsbDevice) {
        if (usbManager.hasPermission(device)) connectAndBrowse()
        else {
            stateMachine.setState(GalleryState.Connecting)
            val i = Intent(ACTION_USB_PERMISSION).apply { setPackage(app.packageName) }
            usbManager.requestPermission(
                device,
                PendingIntent.getBroadcast(
                    app,
                    0,
                    i,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
    }

    // ── Connection & browsing ───────────────────────────────────────────────

    private fun connectAndBrowse() {
        syncJob?.cancel()
        stateMachine.setState(GalleryState.Loading("正在连接相机…"))
        syncJob =
            scope.launch {
                try {
                    val device =
                        usbManager.deviceList.values.firstOrNull { it.vendorId == 0x04B0 }
                            ?: run {
                                Log.warn(tag = TAG) { "No Nikon device in deviceList" }
                                stateMachine.setState(GalleryState.Disconnected)
                                return@launch
                            }
                    Log.info(tag = TAG) { "Found device: ${device.deviceName}" }

                    val m =
                        nikon.openMtpDevice(device)
                            ?: run {
                                Log.error(tag = TAG) { "MTP open failed for ${device.deviceName}" }
                                stateMachine.setState(GalleryState.Error("无法连接相机，请重试"))
                                return@launch
                            }
                    mtp = m

                    Log.info(tag = TAG) { "Getting camera info..." }
                    cameraInfo = nikon.getCameraInfo(m)
                    Log.info(tag = TAG) { "CameraInfo: ${cameraInfo}" }

                    Log.info(tag = TAG) { "Getting storages..." }
                    storages = nikon.getStorages(m)
                    Log.info(tag = TAG) { "Found ${storages.size} storage(s)" }

                    batteryLevel = nikon.getBatteryLevel(m)
                    stateMachine.selected.clear()
                    errorBanner = null

                    Log.info(tag = TAG) { "Starting loadRoot..." }
                    loadRoot()
                    Log.info(tag = TAG) { "loadRoot completed" }
                } catch (e: Exception) {
                    Log.error(tag = TAG, throwable = e) { "connectAndBrowse failed" }
                    if (currentCoroutineContext().isActive) {
                        // If we already have camera info, show banner instead of replacing screen
                        if (cameraInfo != null) {
                            errorBanner = "加载照片时出错: ${e.localizedMessage ?: "未知错误"}"
                        } else {
                            stateMachine.setState(GalleryState.Error(e.localizedMessage ?: "连接失败"))
                        }
                    }
                }
            }
    }

    /** Load root level: all storages → folders + loose photos, progressively. */
    suspend fun loadRoot() {
        currentFolder = null
        val m = mtp ?: return

        if (storages.isEmpty()) {
            stateMachine.setState(GalleryState.Empty)
            return
        }

        errorBanner = null

        // Re-read preferences on each load so settings take effect immediately
        stateMachine.refreshModes(prefs.photoGrouping, prefs.photoSorting)

        try {
            when (stateMachine.groupingMode) {
                UsbSyncPreferences.PhotoGrouping.BY_FOLDER -> loadRootByFolder(m)
                UsbSyncPreferences.PhotoGrouping.BY_DATE ->
                    loadRootProgressive(m) { groups, _ -> buildDateSections(groups) }
                UsbSyncPreferences.PhotoGrouping.FLAT ->
                    loadRootProgressive(m) { groups, _ -> groups }
            }
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "loadRoot failed: ${e.message}" }
            // Show inline banner instead of replacing the entire screen
            errorBanner = "部分照片加载失败: ${e.localizedMessage ?: "未知错误"}"
            // If we haven't entered browsing yet, show empty
            if (stateMachine.state.value !is GalleryState.Browsing) {
                stateMachine.setState(GalleryState.Empty)
            }
        }
    }

    /**
     * Progressive loader for BY_DATE and FLAT modes.
     * 1. Enumerates photos with a progress callback.
     * 2. After 30 photos: uses [populateOrientationsFromDimensions] (instant, no MTP calls) to
     *    detect portrait/landscape, then transitions to Browsing — the user sees photos
     *    immediately.
     * 3. Remaining photos continue streaming in; [currentPhotos] updates incrementally.
     * 4. When done: kicks off concurrent [preloadThumbnails] in background to discover accurate
     *    EXIF orientations (does not block the UI — the grid already has correct aspect ratios from
     *    dimensions).
     */
    private suspend fun loadRootProgressive(
        m: MtpDevice,
        buildEntries:
            (
                groups: List<GalleryEntry.PhotoGroup>, allPhotos: List<NikonUsbManager.PhotoInfo>,
            ) -> List<GalleryEntry>,
    ) {
        val accumPhotos = mutableListOf<NikonUsbManager.PhotoInfo>()
        var globalScanned = 0
        var globalTotal = 0
        var enteredBrowsing = false

        for (s in storages) {
            val prevSize = accumPhotos.size
            nikon.listPhotos(
                m,
                s.id,
                accumulator = accumPhotos,
                onProgress = { scanned, total ->
                    globalScanned = prevSize + scanned
                    globalTotal = prevSize + total

                    if (!enteredBrowsing && accumPhotos.size >= 30) {
                        enteredBrowsing = true
                        val partial = groupByBaseFilename(accumPhotos.toList())
                        stateMachine.updateCurrentPhotos(partial)
                        populateOrientationsFromDimensions()
                        stateMachine.setState(
                            GalleryState.Browsing(
                                cameraInfo,
                                storages,
                                buildEntries(partial, accumPhotos.toList()),
                            )
                        )
                        // Kick off background thumbnail preloading — orientation extraction
                        // happens automatically via extractOrientation() in getThumbnail().
                        preloadThumbnails(partial.size.coerceAtMost(50))
                    } else if (!enteredBrowsing) {
                        stateMachine.setState(
                            GalleryState.Loading("正在扫描…", globalScanned, globalTotal)
                        )
                    }
                },
            )
        }

        // All photos collected — finalize groups, update state silently.
        val groups = groupByBaseFilename(accumPhotos)
        stateMachine.updateCurrentPhotos(groups)
        val entries = buildEntries(groups, accumPhotos)
        // Only re-set Browsing state if we never entered it (fewer than 30 photos total).
        if (!enteredBrowsing) {
            populateOrientationsFromDimensions()
            stateMachine.setState(GalleryState.Browsing(cameraInfo, storages, entries))
        } else {
            // Just update the entries list on the existing Browsing state without
            // creating a new state object that would trigger a full recomposition.
            stateMachine.setState(
                (stateMachine.state.value as GalleryState.Browsing).let { old ->
                    old.copy(entries = entries)
                }
            )
        }
        preloadThumbnails(groups.size.coerceAtMost(50))
    }

    /** Fast folder-first loading: show folder list immediately, load root-level photos after. */
    private suspend fun loadRootByFolder(m: MtpDevice) {
        val entries = mutableListOf<GalleryEntry>()
        val allRootPhotos = mutableListOf<NikonUsbManager.PhotoInfo>()

        // Phase 1: collect folders (cheap — just list folder names)
        for (s in storages) {
            val folders = nikon.listFolders(m, s.id, 0)
            entries.addAll(folders.map { GalleryEntry.Folder(it, s.id) })
        }
        // Show folders immediately even before photos are enumerated
        stateMachine.setState(GalleryState.Loading("正在读取文件夹…"))
        stateMachine.updateCurrentPhotos(emptyList())
        stateMachine.setState(GalleryState.Browsing(cameraInfo, storages, entries.toList()))

        // Phase 2: load root-level photos, updating the grid as they come in
        for (s in storages) {
            nikon.listPhotosInFolder(m, s.id, 0).let { photos ->
                allRootPhotos.addAll(photos)
                stateMachine.updateCurrentPhotos(groupByBaseFilename(allRootPhotos))
                entries.addAll(stateMachine.currentPhotos)
            }
        }
        // Final update — populate orientations from dimensions (instant), then
        // kick off background thumbnail preloading for accurate EXIF.
        stateMachine.updateCurrentPhotos(groupByBaseFilename(allRootPhotos))
        populateOrientationsFromDimensions()
        val finalEntries = mutableListOf<GalleryEntry>()
        for (s in storages) {
            val folders = nikon.listFolders(m, s.id, 0)
            finalEntries.addAll(folders.map { GalleryEntry.Folder(it, s.id) })
        }
        finalEntries.addAll(stateMachine.currentPhotos)
        stateMachine.setState(GalleryState.Browsing(cameraInfo, storages, finalEntries))
        preloadThumbnails(stateMachine.currentPhotos.size.coerceAtMost(50))
    }

    /** Build date-section entries from grouped photos. */
    private fun buildDateSections(groups: List<GalleryEntry.PhotoGroup>): List<GalleryEntry> {
        val entries = mutableListOf<GalleryEntry>()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val dateGroups =
            groups.groupBy { group ->
                val ts = maxOf(group.raw?.dateModified ?: 0L, group.jpg?.dateModified ?: 0L)
                dateFormat.format(java.util.Date(ts))
            }
        val sortedDates =
            dateGroups.entries.sortedByDescending { (date, _) ->
                try {
                    dateFormat.parse(date)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }
        for ((date, gs) in sortedDates) {
            entries.add(GalleryEntry.DateSection(date, gs.size))
            entries.addAll(gs)
        }
        return entries
    }

    /** Load photos inside a specific folder. Called when entering a folder route. */
    suspend fun loadFolder(storageId: Int, folderHandle: Int) {
        currentFolder = storageId to folderHandle
        val m = mtp ?: return
        errorBanner = null
        stateMachine.setState(GalleryState.Loading("正在读取文件夹…"))

        try {
            // Show sub-folders first (cheap)
            val subFolders = nikon.listFolders(m, storageId, folderHandle)
            stateMachine.setState(GalleryState.Loading("正在读取照片…", 0, 0))

            val photos = nikon.listPhotosInFolder(m, storageId, folderHandle)
            stateMachine.updateCurrentPhotos(groupByBaseFilename(photos))
            val entries = mutableListOf<GalleryEntry>()
            entries.addAll(subFolders.map { GalleryEntry.Folder(it, storageId) })
            entries.addAll(stateMachine.currentPhotos)
            // Use dimensions (instant, no MTP calls) for aspect ratios;
            // accurate EXIF orientations come from background preload.
            populateOrientationsFromDimensions()
            stateMachine.setState(GalleryState.Browsing(cameraInfo, storages, entries))
            preloadThumbnails(stateMachine.currentPhotos.size.coerceAtMost(50))
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "loadFolder failed: ${e.message}" }
            errorBanner = "读取文件夹失败: ${e.localizedMessage ?: "未知错误"}"
            if (stateMachine.state.value !is GalleryState.Browsing) {
                stateMachine.setState(GalleryState.Empty)
            }
        }
    }

    /** Populates orientations from dimensions, then preloads [count] thumbnails in background. */
    fun prefetchOrientations(count: Int) {
        thumbnails.prefetchOrientations(count)
    }

    /** Background thumbnail preloader with concurrent MTP calls (see [ThumbnailProvider]). */
    fun preloadThumbnails(count: Int = 30) {
        thumbnails.preloadThumbnails(count)
    }

    fun getThumbnail(handle: Int): ByteArray? = thumbnails.getThumbnail(handle)

    fun populateOrientationsFromDimensions() {
        thumbnails.populateOrientationsFromDimensions()
    }

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

    // ── Grouping ───────────────────────────────────────────────────────────

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

    fun getNewPhotoCount(): Int = stateMachine.getNewPhotoCount()

    // ── Transfer ────────────────────────────────────────────────────────────

    /** Builds the transfer list by filtering [currentPhotos] with [handleFilter]. */
    private fun buildTransferList(
        handleFilter: (Int) -> Boolean
    ): List<Pair<NikonUsbManager.PhotoInfo, Int>> {
        return stateMachine.currentPhotos.mapNotNull { g ->
            val h =
                if (g.raw != null && handleFilter(g.raw.handle)) g.raw.handle
                else if (g.jpg != null && handleFilter(g.jpg.handle)) g.jpg.handle
                else return@mapNotNull null
            val photo =
                listOfNotNull(g.raw, g.jpg).find { it.handle == h } ?: return@mapNotNull null
            if (photoSyncManager.isAlreadyImported(photo)) {
                return@mapNotNull null
            }
            photo to h
        }
    }

    /** Core transfer loop. Updates [state], [selected], and [failedHandles]. */
    private suspend fun performTransfer(toTransfer: List<Pair<NikonUsbManager.PhotoInfo, Int>>) {
        val m = mtp ?: return
        val totalBytes = toTransfer.sumOf { it.first.size }
        val startTime = System.currentTimeMillis()
        val savedUris = mutableListOf<Uri>()
        val transferredHandles = mutableListOf<Int>()
        val failedList = mutableListOf<Int>()

        var ok = 0
        var bytesAcc = 0L
        for ((i, p) in toTransfer.withIndex()) {
            if (!currentCoroutineContext().isActive) return
            stateMachine.setState(
                GalleryState.Transferring(
                    TransferProgress(
                        synced = i + 1,
                        total = toTransfer.size,
                        currentFile = p.first.name,
                        bytesTransferred = bytesAcc,
                        totalBytes = totalBytes,
                        startTimeMillis = startTime,
                    )
                )
            )
            val uri = saveToMediaStore(m, p.first)
            if (uri != null) {
                ok++
                stateMachine.selected.remove(p.second)
                bytesAcc += p.first.size
                savedUris.add(uri)
                transferredHandles.add(p.second)
                photoSyncManager.markAsImported(p.first)
            } else {
                failedList.add(p.second)
            }
        }
        lastTransferredHandles = transferredHandles.toList()
        failedHandles = failedList.toList()
        if (ok > 0) {
            prefs.addTransferRecord(ok, cameraInfo?.model ?: "Nikon")
        }
        stateMachine.setState(GalleryState.TransferDone(ok, savedUris.toList()))
    }

    fun startTransfer() {
        val toTransfer = buildTransferList { it in stateMachine.selected }
        if (toTransfer.isEmpty()) {
            stateMachine.setState(GalleryState.TransferDone(0))
            return
        }

        failedHandles = emptyList()
        syncJob?.cancel()
        syncJob = scope.launch { performTransfer(toTransfer) }
    }

    fun retryFailedTransfers() {
        if (failedHandles.isEmpty()) return
        val toRetry = buildTransferList { it in failedHandles }
        if (toRetry.isEmpty()) return

        stateMachine.selected.clear()
        failedHandles = emptyList()
        syncJob?.cancel()
        syncJob = scope.launch { performTransfer(toRetry) }
    }

    /** Pull-to-refresh: reload current level without jumping to root. */
    fun refresh() {
        scope.launch {
            val folder = currentFolder
            if (folder != null) loadFolder(folder.first, folder.second) else loadRoot()
        }
    }

    fun dismissTransferDone() {
        scope.launch { loadRoot() }
    }

    /**
     * Deletes the given photo handles from the camera via MTP. Returns the number of successfully
     * deleted photos.
     */
    fun deletePhotos(handles: List<Int>): Int {
        val m = mtp ?: return 0
        return handles.count { handle -> nikon.deletePhoto(m, handle) }
    }

    /**
     * Deletes photos that were just transferred (using saved handles from TransferDone). Returns
     * the number of deleted photos.
     */
    suspend fun deleteTransferredPhotos(handles: List<Int>): Int =
        withContext(Dispatchers.IO) { deletePhotos(handles) }

    fun closeMtp() {
        nikon.closeMtpDevice()
        mtp = null
        thumbnails.clearFullPhotoCache()
    }

    // ── MediaStore ──────────────────────────────────────────────────────────

    private suspend fun saveToMediaStore(
        m: MtpDevice,
        photo: NikonUsbManager.PhotoInfo,
    ): android.net.Uri? {
        val path = "Pictures/CameraSync/${cameraInfo?.model ?: "Nikon"}"
        val mime =
            when {
                photo.name.endsWith(".NEF", true) -> "image/x-nikon-nef"
                photo.name.endsWith(".HEIC", true) -> "image/heic"
                photo.name.endsWith(".PNG", true) -> "image/png"
                else -> "image/jpeg"
            }
        val cv =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, path)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val uri =
            app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return null
        return try {
            val bytes =
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    nikon.downloadPhoto(m, photo, out, app.cacheDir)
                } ?: 0L
            if (bytes <= 0L) {
                app.contentResolver.delete(uri, null, null)
                return null
            }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            app.contentResolver.update(uri, cv, null, null)
            uri
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "Transfer failed: ${photo.name}" }
            app.contentResolver.delete(uri, null, null)
            null
        }
    }

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
