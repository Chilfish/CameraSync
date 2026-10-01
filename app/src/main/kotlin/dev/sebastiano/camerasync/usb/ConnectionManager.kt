package dev.sebastiano.camerasync.usb

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juul.khronicle.Log
import dev.sebastiano.camerasync.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ConnectionManager"
private const val ACTION_USB_PERMISSION = "dev.sebastiano.camerasync.USB_PERMISSION"

/**
 * Owns the USB lifecycle and gallery browsing: broadcast receiver, connect/browse jobs, storage and
 * folder enumeration, and the device/state that the UI reads (P2-1 extraction from
 * [GalleryViewModel]).
 *
 * Writes flow into [stateMachine] and [thumbnails] (both owned by the ViewModel); [transferEngine]
 * is referenced only to cancel an in-flight transfer when the device detaches.
 */
class ConnectionManager(
    private val app: Application,
    private val usbManager: UsbManager,
    private val nikon: NikonUsbManager,
    private val scope: () -> CoroutineScope,
    private val prefs: UsbSyncPreferences,
    private val stateMachine: GalleryStateMachine,
    private val thumbnails: ThumbnailProvider,
    private val transferEngine: TransferEngine,
) {

    var mtp: MtpDevice? = null
        private set

    /** Device info (populated once on connect). */
    var cameraInfo: NikonUsbManager.CameraInfo? = null
        private set

    var storages = emptyList<NikonUsbManager.StorageInfo>()
        private set

    /** Inline error banner message — shown above content instead of replacing the entire screen. */
    var errorBanner by mutableStateOf<String?>(null)
        private set

    /** Set to true by [requestReload] to signal the UI to reload the gallery. */
    var needsReload by mutableStateOf(false)

    private var started = false

    /** Active connect/load job — cancelled before a transfer starts (one active operation). */
    private var syncJob: Job? = null

    // Folder navigation context — (storageId, folderHandle), null when at root.
    // Used by refresh() to reload the current folder instead of jumping to root.
    private var currentFolder: Pair<Int, Int>? = null

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
                        else
                            stateMachine.setState(
                                GalleryState.Error(
                                    app.getString(R.string.usb_status_permission_denied)
                                )
                            )
                    }
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun getDevice(i: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

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

    /**
     * Stops the connection: cancels in-flight work, unregisters the receiver and closes MTP.
     *
     * Paired with [start] from the composable's `DisposableEffect` so a configuration change
     * (rotation) closes the old instance's `MtpDevice` before the new instance opens it again —
     * prevents two `MtpDevice`s open on the same physical connection (R8). State/selection/caches
     * are reset exactly like a detach ([closeMtpAndClear]) so a DI-held singleton behaves like a
     * fresh instance after recreation (P5-2, 方案 A).
     */
    fun stop() {
        runCatching { app.unregisterReceiver(receiver) }
        closeMtpAndClear()
        started = false
    }

    /** Cancels the active connect/load job (called before a transfer starts). */
    fun cancelActiveLoad() {
        syncJob?.cancel()
    }

    /** Requests a gallery reload when the user returns from settings, etc. */
    fun requestReload() {
        needsReload = true
    }

    fun clearErrorBanner() {
        errorBanner = null
    }

    /** Closes MTP and clears all state. Called on USB detach. */
    private fun closeMtpAndClear() {
        syncJob?.cancel()
        transferEngine.cancelTransfer()
        closeMtp()
        stateMachine.deselectAll()
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

    /**
     * USB framework throws a mix of RuntimeException/RemoteException/IllegalStateException at the
     * coroutine boundary; enumerating them isn't feasible, so catch broadly and surface a
     * user-facing error.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun connectAndBrowse() {
        syncJob?.cancel()
        stateMachine.setState(GalleryState.Loading(app.getString(R.string.usb_status_connecting)))
        syncJob =
            scope().launch {
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
                                stateMachine.setState(
                                    GalleryState.Error(
                                        app.getString(R.string.usb_error_connect_failed)
                                    )
                                )
                                return@launch
                            }
                    mtp = m

                    Log.info(tag = TAG) { "Getting camera info..." }
                    cameraInfo = nikon.getCameraInfo(m)
                    Log.info(tag = TAG) { "CameraInfo: ${cameraInfo}" }

                    Log.info(tag = TAG) { "Getting storages..." }
                    storages = nikon.getStorages(m)
                    Log.info(tag = TAG) { "Found ${storages.size} storage(s)" }

                    stateMachine.deselectAll()
                    errorBanner = null

                    Log.info(tag = TAG) { "Starting loadRoot..." }
                    loadRoot()
                    Log.info(tag = TAG) { "loadRoot completed" }
                } catch (e: Exception) {
                    Log.error(tag = TAG, throwable = e) { "connectAndBrowse failed" }
                    if (currentCoroutineContext().isActive) {
                        // If we already have camera info, show banner instead of replacing screen
                        if (cameraInfo != null) {
                            errorBanner =
                                app.getString(
                                    R.string.usb_error_load_photos,
                                    e.localizedMessage
                                        ?: app.getString(R.string.label_unknown_error),
                                )
                        } else {
                            stateMachine.setState(
                                GalleryState.Error(
                                    e.localizedMessage ?: app.getString(R.string.usb_error_connect)
                                )
                            )
                        }
                    }
                }
            }
    }

    /** Load root level: all storages → folders + loose photos, progressively. */
    @Suppress("TooGenericExceptionCaught")
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
            errorBanner =
                app.getString(
                    R.string.usb_error_load_partial,
                    e.localizedMessage ?: app.getString(R.string.label_unknown_error),
                )
            // If we haven't entered browsing yet, show empty
            if (stateMachine.state.value !is GalleryState.Browsing) {
                stateMachine.setState(GalleryState.Empty)
            }
        }
    }

    /**
     * Progressive loader for BY_DATE and FLAT modes.
     * 1. Enumerates photos with a progress callback.
     * 2. After 30 photos: uses [ThumbnailProvider.populateOrientationsFromDimensions] (instant, no
     *    MTP calls) to detect portrait/landscape, then transitions to Browsing — the user sees
     *    photos immediately.
     * 3. Remaining photos continue streaming in; [GalleryStateMachine.currentPhotos] updates
     *    incrementally.
     * 4. When done: kicks off concurrent [ThumbnailProvider.preloadThumbnails] in background to
     *    discover accurate EXIF orientations (does not block the UI).
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
        var enteredBrowsing = false

        for (s in storages) {
            val prevSize = accumPhotos.size
            nikon.listPhotos(
                m,
                s.id,
                accumulator = accumPhotos,
                onProgress = { scanned ->
                    globalScanned = prevSize + scanned

                    if (!enteredBrowsing && accumPhotos.size >= 30) {
                        enteredBrowsing = true
                        val partial = GalleryViewModel.groupByBaseFilename(accumPhotos.toList())
                        stateMachine.updateCurrentPhotos(partial)
                        thumbnails.populateOrientationsFromDimensions()
                        stateMachine.setState(
                            GalleryState.Browsing(
                                cameraInfo,
                                storages,
                                buildEntries(partial, accumPhotos.toList()),
                            )
                        )
                        // Kick off background thumbnail preloading — orientation extraction
                        // happens automatically via extractOrientation() in getThumbnail().
                        thumbnails.preloadThumbnails(partial.size.coerceAtMost(50))
                    } else if (!enteredBrowsing) {
                        stateMachine.setState(
                            GalleryState.Loading(
                                app.getString(R.string.usb_status_scanning, globalScanned)
                            )
                        )
                    }
                },
            )
        }

        // All photos collected — finalize groups, update state silently.
        val groups = GalleryViewModel.groupByBaseFilename(accumPhotos)
        stateMachine.updateCurrentPhotos(groups)
        val entries = buildEntries(groups, accumPhotos)
        // Only re-set Browsing state if we never entered it (fewer than 30 photos total).
        if (!enteredBrowsing) {
            thumbnails.populateOrientationsFromDimensions()
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
        thumbnails.preloadThumbnails(groups.size.coerceAtMost(50))
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
        stateMachine.setState(
            GalleryState.Loading(app.getString(R.string.usb_status_loading_folder))
        )
        stateMachine.updateCurrentPhotos(emptyList())
        stateMachine.setState(GalleryState.Browsing(cameraInfo, storages, entries.toList()))

        // Phase 2: load root-level photos, updating the grid as they come in
        for (s in storages) {
            nikon.listPhotosInFolder(m, s.id, 0).let { photos ->
                allRootPhotos.addAll(photos)
                stateMachine.updateCurrentPhotos(
                    GalleryViewModel.groupByBaseFilename(allRootPhotos)
                )
                entries.addAll(stateMachine.currentPhotos)
            }
        }
        // Final update — populate orientations from dimensions (instant), then
        // kick off background thumbnail preloading for accurate EXIF.
        stateMachine.updateCurrentPhotos(GalleryViewModel.groupByBaseFilename(allRootPhotos))
        thumbnails.populateOrientationsFromDimensions()
        val finalEntries = mutableListOf<GalleryEntry>()
        for (s in storages) {
            val folders = nikon.listFolders(m, s.id, 0)
            finalEntries.addAll(folders.map { GalleryEntry.Folder(it, s.id) })
        }
        finalEntries.addAll(stateMachine.currentPhotos)
        stateMachine.setState(GalleryState.Browsing(cameraInfo, storages, finalEntries))
        thumbnails.preloadThumbnails(stateMachine.currentPhotos.size.coerceAtMost(50))
    }

    /** Build date-section entries from grouped photos. */
    private fun buildDateSections(groups: List<GalleryEntry.PhotoGroup>): List<GalleryEntry> {
        val entries = mutableListOf<GalleryEntry>()
        val dateGroups =
            groups.groupBy { group ->
                val ts = maxOf(group.raw?.dateModified ?: 0L, group.jpg?.dateModified ?: 0L)
                dateKey(ts)
            }
        // ISO "yyyy-MM-dd" keys sort lexicographically === chronologically (newest first).
        val sortedDates = dateGroups.entries.sortedByDescending { (date, _) -> date }
        for ((date, gs) in sortedDates) {
            entries.add(GalleryEntry.DateSection(date, gs.size))
            entries.addAll(gs)
        }
        return entries
    }

    /** Load photos inside a specific folder. Called when entering a folder route. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun loadFolder(storageId: Int, folderHandle: Int) {
        currentFolder = storageId to folderHandle
        val m = mtp ?: return
        errorBanner = null
        stateMachine.setState(
            GalleryState.Loading(app.getString(R.string.usb_status_loading_folder))
        )

        try {
            // Show sub-folders first (cheap)
            val subFolders = nikon.listFolders(m, storageId, folderHandle)
            stateMachine.setState(
                GalleryState.Loading(app.getString(R.string.usb_status_loading_photos), 0, 0)
            )

            val photos = nikon.listPhotosInFolder(m, storageId, folderHandle)
            stateMachine.updateCurrentPhotos(GalleryViewModel.groupByBaseFilename(photos))
            val entries = mutableListOf<GalleryEntry>()
            entries.addAll(subFolders.map { GalleryEntry.Folder(it, storageId) })
            entries.addAll(stateMachine.currentPhotos)
            // Use dimensions (instant, no MTP calls) for aspect ratios;
            // accurate EXIF orientations come from background preload.
            thumbnails.populateOrientationsFromDimensions()
            stateMachine.setState(GalleryState.Browsing(cameraInfo, storages, entries))
            thumbnails.preloadThumbnails(stateMachine.currentPhotos.size.coerceAtMost(50))
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "loadFolder failed: ${e.message}" }
            errorBanner =
                app.getString(
                    R.string.usb_error_load_folder,
                    e.localizedMessage ?: app.getString(R.string.label_unknown_error),
                )
            if (stateMachine.state.value !is GalleryState.Browsing) {
                stateMachine.setState(GalleryState.Empty)
            }
        }
    }

    /** Pull-to-refresh: reload current level without jumping to root. */
    fun refresh() {
        scope().launch {
            val folder = currentFolder
            if (folder != null) loadFolder(folder.first, folder.second) else loadRoot()
        }
    }

    fun dismissTransferDone() {
        scope().launch { loadRoot() }
    }

    /** Closes MTP and clears the full-photo download cache. Called on USB detach. */
    private fun closeMtp() {
        nikon.closeMtpDevice()
        mtp = null
        thumbnails.clearFullPhotoCache()
    }
}
