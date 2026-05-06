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
import android.os.Build
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.exifinterface.media.ExifInterface
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
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "GalleryVM"
private const val ACTION_USB_PERMISSION = "dev.sebastiano.camerasync.USB_PERMISSION"

// ── State ──────────────────────────────────────────────────────────────────

sealed interface GalleryState {
    data object Disconnected : GalleryState
    data object Connecting : GalleryState
    data class Loading(val message: String) : GalleryState
    data class Browsing(
        val cameraInfo: NikonUsbManager.CameraInfo?,
        val storages: List<NikonUsbManager.StorageInfo>,
        val entries: List<GalleryEntry>,
    ) : GalleryState
    data object Empty : GalleryState
    data class Error(val message: String) : GalleryState
    data class Transferring(val progress: TransferProgress) : GalleryState
    data class TransferDone(val synced: Int, val savedUris: List<android.net.Uri> = emptyList()) : GalleryState
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
        get() = when {
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
        get() = when {
            etaSeconds < 0 -> "计算中…"
            etaSeconds < 60 -> "还剩 ${etaSeconds}s"
            else -> "还剩 ${etaSeconds / 60}m ${etaSeconds % 60}s"
        }
}

sealed interface GalleryEntry {
    data class Folder(val info: NikonUsbManager.FolderInfo, val storageId: Int) : GalleryEntry
    data class PhotoGroup(
        val baseName: String,
        val raw: NikonUsbManager.PhotoInfo?,
        val jpg: NikonUsbManager.PhotoInfo?,
    ) : GalleryEntry {
        val previewHandle: Int get() = jpg?.handle ?: raw!!.handle
        val hasRaw: Boolean get() = raw != null
    }
}

enum class PhotoFilter { ALL, NEW, RAW_ONLY, JPEG_ONLY }

// ── ViewModel ──────────────────────────────────────────────────────────────

class GalleryViewModel(private val app: Application) {
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val nikon = NikonUsbManager(usbManager)
    private val photoSyncManager = PhotoSyncManager(app)

    private val _state = mutableStateOf<GalleryState>(GalleryState.Disconnected)
    val state: State<GalleryState> = _state

    // SnapshotStateList — any composable reading this list automatically
    // recomposes when the list is modified (no manual trigger needed).
    private val _selected = mutableStateListOf<Int>()
    val selectedCount get() = _selected.size

    /** Handles that were successfully transferred in the last [startTransfer] call. */
    var lastTransferredHandles: List<Int> = emptyList()
        private set

    /** Handles that failed during the last transfer attempt. Populated in [performTransfer]. */
    var failedHandles: List<Int> = emptyList()
        private set

    /** Camera battery level (0–100), or null if the device doesn't report it. */
    var batteryLevel: Int? = null
        private set

    /** Current grid column count (2, 3, or 4). */
    var gridColumns: Int = 3
        private set

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var mtp: MtpDevice? = null; private set
    private var syncJob: Job? = null

    // Device info (populated once on connect)
    var cameraInfo: NikonUsbManager.CameraInfo? = null; private set
    var storages = emptyList<NikonUsbManager.StorageInfo>(); private set

    // Photo groups for the current view
    var currentPhotos = emptyList<GalleryEntry.PhotoGroup>(); private set

    // Thumbnail cache (shared across folder navigations)
    private val thumbCache = object : LinkedHashMap<Int, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > 128
    }

    // EXIF orientation cache — extracted from MTP thumbnail bytes, keyed by handle.
    // Uses ExifInterface.ORIENTATION_* constants. Populated by getThumbnail().
    // ConcurrentHashMap because writes (IO) and reads (main) happen on different threads.
    private val orientationCache = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    fun getOrientation(handle: Int): Int? = orientationCache[handle]

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    getDevice(intent)?.let { onPlugged(it) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    syncJob?.cancel(); closeMtp()
                    _selected.clear()
                    _state.value = GalleryState.Disconnected
                }
                ACTION_USB_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                        getDevice(intent)?.let { connectAndBrowse() }
                    else _state.value = GalleryState.Error("USB 权限被拒绝")
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
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }, Context.RECEIVER_EXPORTED)
        usbManager.deviceList.values.firstOrNull { it.vendorId == 0x04B0 }
            ?.let { onPlugged(it) }
    }

    fun stop() { syncJob?.cancel(); scope.cancel(); closeMtp()
        try { app.unregisterReceiver(receiver) } catch (_: Exception) {}
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    private fun onPlugged(device: UsbDevice) {
        if (usbManager.hasPermission(device)) connectAndBrowse()
        else {
            _state.value = GalleryState.Connecting
            val i = Intent(ACTION_USB_PERMISSION).apply { setPackage(app.packageName) }
            usbManager.requestPermission(device, PendingIntent.getBroadcast(
                app, 0, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }

    // ── Connection & browsing ───────────────────────────────────────────────

    private fun connectAndBrowse() {
        syncJob?.cancel()
        _state.value = GalleryState.Loading("正在连接相机…")
        syncJob = scope.launch {
            try {
                val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x04B0 }
                    ?: run { _state.value = GalleryState.Disconnected; return@launch }
                val m = nikon.openMtpDevice(device)
                    ?: run { _state.value = GalleryState.Error("MTP 连接失败"); return@launch }
                mtp = m

                cameraInfo = nikon.getCameraInfo(m)
                storages = nikon.getStorages(m)
                batteryLevel = nikon.getBatteryLevel(m)
                Log.info(tag = TAG) {
                    "Connected: ${cameraInfo?.manufacturer} ${cameraInfo?.model}"
                }
                _selected.clear()
                loadRoot()
            } catch (e: Exception) {
                Log.error(tag = TAG, throwable = e) { "connect failed" }
                if (currentCoroutineContext().isActive)
                    _state.value = GalleryState.Error(e.message ?: "连接失败")
            }
        }
    }

    /** Load root level: all storages → folders + loose photos */
    suspend fun loadRoot() {
        val m = mtp ?: return
        _state.value = GalleryState.Loading("正在读取…")

        if (storages.isEmpty()) { _state.value = GalleryState.Empty; return }
        val entries = mutableListOf<GalleryEntry>()
        for (s in storages) {
            val folders = nikon.listFolders(m, s.id, 0)
            entries.addAll(folders.map { GalleryEntry.Folder(it, s.id) })
            // Also show loose photos at root
            val photos = nikon.listPhotosInFolder(m, s.id, 0)
            currentPhotos = groupByBaseFilename(photos)
            entries.addAll(currentPhotos)
        }
        // Pre-fetch orientations for the first visible batch so PhotoCell
        // gets correct initial aspect ratios (avoids staggered-grid letterboxing).
        prefetchOrientations(50)
        _state.value = GalleryState.Browsing(cameraInfo, storages, entries)
        preloadThumbnails()
    }

    /** Load photos inside a specific folder. Called when entering a folder route. */
    suspend fun loadFolder(storageId: Int, folderHandle: Int) {
        val m = mtp ?: return
        _state.value = GalleryState.Loading("正在读取照片…")

        val photos = nikon.listPhotosInFolder(m, storageId, folderHandle)
        currentPhotos = groupByBaseFilename(photos)
        val entries = mutableListOf<GalleryEntry>()
        // Show sub-folders first
        val subFolders = nikon.listFolders(m, storageId, folderHandle)
        entries.addAll(subFolders.map { GalleryEntry.Folder(it, storageId) })
        entries.addAll(currentPhotos)
        // Pre-fetch orientations for the first visible batch so PhotoCell
        // gets correct initial aspect ratios (avoids staggered-grid letterboxing).
        prefetchOrientations(50)
        _state.value = GalleryState.Browsing(cameraInfo, storages, entries)
        preloadThumbnails()
    }

    /**
     * Pre-fetches MTP thumbnails for the first [count] photos in the current
     * view, extracting EXIF orientation into [orientationCache]. Called inline
     * *before* transitioning to [GalleryState.Browsing] so that [PhotoCell]
     * can read [getOrientation] during its first composition and set the
     * correct initial aspect ratio — avoiding the staggered grid measuring
     * the cell at a wrong default ratio.
     */
    private suspend fun prefetchOrientations(count: Int) {
        val handles = currentPhotos.take(count).map { it.previewHandle }
        for (h in handles) {
            // orientation already cached? skip
            if (orientationCache.containsKey(h)) continue
            try {
                getThumbnail(h)
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /** Preload thumbnails for the first [count] photo groups (background). */
    fun preloadThumbnails(count: Int = 30) {
        val handles = currentPhotos.take(count).map { it.previewHandle }
        scope.launch {
            for (h in handles) {
                if (!currentCoroutineContext().isActive) return@launch
                getThumbnail(h)
            }
        }
    }

    fun getThumbnail(handle: Int): ByteArray? {
        thumbCache[handle]?.let { return it }
        return mtp?.getThumbnail(handle)?.also { bytes ->
            thumbCache[handle] = bytes
            extractOrientation(handle, bytes)
        }
    }

    private fun extractOrientation(handle: Int, jpegBytes: ByteArray) {
        if (orientationCache.containsKey(handle)) return
        try {
            val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
            orientationCache[handle] = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) { /* can't extract, leave uncached */ }
    }

    // ── Selection ──────────────────────────────────────────────────────────

    fun toggleSelection(group: GalleryEntry.PhotoGroup) {
        val handles = listOfNotNull(group.raw?.handle, group.jpg?.handle)
        if (handles.isEmpty()) return
        if (handles.all { it in _selected }) handles.forEach { _selected.remove(it) }
        else handles.forEach { _selected.add(it) }
    }

    fun selectAll() {
        currentPhotos.flatMap { listOfNotNull(it.raw?.handle, it.jpg?.handle) }
            .forEach { if (it !in _selected) _selected.add(it) }
    }

    fun deselectAll() { _selected.clear() }
    fun isSelected(h: Int) = h in _selected

    fun isGroupSelected(group: GalleryEntry.PhotoGroup): Boolean {
        val handles = listOfNotNull(group.raw?.handle, group.jpg?.handle)
        return handles.isNotEmpty() && handles.any { it in _selected }
    }

    // ── Filtering ──────────────────────────────────────────────────────────

    var filterMode: PhotoFilter = PhotoFilter.ALL
        private set

    fun setFilter(mode: PhotoFilter) {
        filterMode = mode
    }

    fun getFilteredGroups(): List<GalleryEntry.PhotoGroup> {
        return when (filterMode) {
            PhotoFilter.ALL -> currentPhotos
            PhotoFilter.NEW -> currentPhotos.filter { group ->
                val handles = listOfNotNull(group.raw?.handle, group.jpg?.handle)
                handles.any { !photoSyncManager.isAlreadyImported(0, it) }
            }
            PhotoFilter.RAW_ONLY -> currentPhotos.filter { it.hasRaw }
            PhotoFilter.JPEG_ONLY -> currentPhotos.filter { it.jpg != null }
        }
    }

    fun getNewPhotoCount(): Int {
        return currentPhotos.count { group ->
            val handles = listOfNotNull(group.raw?.handle, group.jpg?.handle)
            handles.any { !photoSyncManager.isAlreadyImported(0, it) }
        }
    }

    // ── Transfer ────────────────────────────────────────────────────────────

    /** Builds the transfer list by filtering [currentPhotos] with [handleFilter]. */
    private fun buildTransferList(handleFilter: (Int) -> Boolean): List<Pair<NikonUsbManager.PhotoInfo, Int>> {
        return currentPhotos.mapNotNull { g ->
            val h = if (g.raw != null && handleFilter(g.raw.handle)) g.raw.handle
                else if (g.jpg != null && handleFilter(g.jpg.handle)) g.jpg.handle
                else return@mapNotNull null
            val photo = listOfNotNull(g.raw, g.jpg).find { it.handle == h }
                ?: return@mapNotNull null
            if (photoSyncManager.isAlreadyImported(0, photo.handle)) return@mapNotNull null
            photo to h
        }
    }

    /** Core transfer loop. Updates [_state], [_selected], and [failedHandles]. */
    private suspend fun performTransfer(
        toTransfer: List<Pair<NikonUsbManager.PhotoInfo, Int>>,
    ) {
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
            _state.value = GalleryState.Transferring(
                TransferProgress(
                    synced = i + 1,
                    total = toTransfer.size,
                    currentFile = p.first.name,
                    bytesTransferred = bytesAcc,
                    totalBytes = totalBytes,
                    startTimeMillis = startTime,
                )
            )
            val uri = saveToMediaStore(m, p.first)
            if (uri != null) {
                ok++; _selected.remove(p.second)
                bytesAcc += p.first.size
                savedUris.add(uri)
                transferredHandles.add(p.second)
                photoSyncManager.markAsImported(0, p.first.handle)
            } else {
                failedList.add(p.second)
            }
        }
        lastTransferredHandles = transferredHandles.toList()
        failedHandles = failedList.toList()
        if (ok > 0) {
            val prefs = UsbSyncPreferences(app)
            prefs.addTransferRecord(ok, cameraInfo?.model ?: "Nikon")
        }
        _state.value = GalleryState.TransferDone(ok, savedUris.toList())
    }

    fun startTransfer() {
        val toTransfer = buildTransferList { it in _selected }
        if (toTransfer.isEmpty()) { _state.value = GalleryState.TransferDone(0); return }

        failedHandles = emptyList()
        syncJob?.cancel()
        syncJob = scope.launch { performTransfer(toTransfer) }
    }

    fun retryFailedTransfers() {
        if (failedHandles.isEmpty()) return
        val toRetry = buildTransferList { it in failedHandles }
        if (toRetry.isEmpty()) return

        _selected.clear()
        failedHandles = emptyList()
        syncJob?.cancel()
        syncJob = scope.launch { performTransfer(toRetry) }
    }

    /** Pull-to-refresh: reload current level. */
    fun refresh() {
        scope.launch {
            if (currentPhotos.isEmpty()) loadRoot()
            else loadRoot() // go back to root on refresh
        }
    }

    fun dismissTransferDone() { scope.launch { loadRoot() } }

    /**
     * Deletes the given photo handles from the camera via MTP.
     * Returns the number of successfully deleted photos.
     */
    fun deletePhotos(handles: List<Int>): Int {
        val m = mtp ?: return 0
        return handles.count { handle -> nikon.deletePhoto(m, handle) }
    }

    /**
     * Deletes photos that were just transferred (using saved handles from TransferDone).
     * Returns the number of deleted photos.
     */
    suspend fun deleteTransferredPhotos(handles: List<Int>): Int = withContext(Dispatchers.IO) {
        deletePhotos(handles)
    }

    fun setGridColumns(cols: Int) {
        gridColumns = cols
    }

    fun closeMtp() { nikon.closeMtpDevice(); mtp = null }

    // ── MediaStore ──────────────────────────────────────────────────────────

    private suspend fun saveToMediaStore(m: MtpDevice, photo: NikonUsbManager.PhotoInfo): android.net.Uri? {
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(photo.dateModified))
        val path = "Pictures/CameraSync/Nikon Z30/$dateFolder"
        val mime = when {
            photo.name.endsWith(".NEF", true) -> "image/x-nikon-nef"
            photo.name.endsWith(".HEIC", true) -> "image/heic"
            photo.name.endsWith(".PNG", true) -> "image/png"
            else -> "image/jpeg"
        }
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = app.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        return try {
            val bytes = app.contentResolver.openOutputStream(uri)?.use { out ->
                nikon.downloadPhoto(m, photo, out, app.cacheDir)
            } ?: 0L
            if (bytes <= 0L) {
                app.contentResolver.delete(uri, null, null)
                return null
            }
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            app.contentResolver.update(uri, cv, null, null)
            uri
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "Transfer failed: ${photo.name}" }
            app.contentResolver.delete(uri, null, null)
            null
        }
    }

    companion object {
        fun groupByBaseFilename(photos: List<NikonUsbManager.PhotoInfo>): List<GalleryEntry.PhotoGroup> {
            val map = linkedMapOf<String, MutableList<NikonUsbManager.PhotoInfo>>()
            for (p in photos) {
                val base = p.name.substringBeforeLast(".")
                map.getOrPut(base) { mutableListOf() }.add(p)
            }
            return map.map { (base, list) ->
                GalleryEntry.PhotoGroup(
                    baseName = base,
                    raw = list.find { it.formatName == "NEF(RAW)" || it.name.endsWith(".NEF", true) },
                    jpg = list.find { it.formatName in setOf("JPEG", "EXIF_JPEG") || it.name.endsWith(".JPG", true) },
                )
            }
        }
    }
}
