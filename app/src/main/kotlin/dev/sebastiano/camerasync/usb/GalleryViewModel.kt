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
    data class Transferring(val synced: Int, val total: Int, val currentFile: String) : GalleryState
    data class TransferDone(val synced: Int) : GalleryState
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

    // ── Transfer ────────────────────────────────────────────────────────────

    fun startTransfer() {
        val m = mtp ?: return
        val toTransfer = currentPhotos.mapNotNull { g ->
            val h = if (g.raw != null && g.raw.handle in _selected) g.raw.handle
                else if (g.jpg != null && g.jpg.handle in _selected) g.jpg.handle
                else return@mapNotNull null
            val photo = listOfNotNull(g.raw, g.jpg).find { it.handle == h }
                ?: return@mapNotNull null
            // Skip already-imported photos
            val storageId = 0 // we don't track storageId in PhotoSyncManager currently
            if (photoSyncManager.isAlreadyImported(storageId, photo.handle)) return@mapNotNull null
            photo to h
        }
        if (toTransfer.isEmpty()) { _state.value = GalleryState.TransferDone(0); return }

        syncJob?.cancel()
        syncJob = scope.launch {
            var ok = 0
            for ((i, p) in toTransfer.withIndex()) {
                if (!currentCoroutineContext().isActive) return@launch
                _state.value = GalleryState.Transferring(i + 1, toTransfer.size, p.first.name)
                if (saveToMediaStore(m, p.first)) {
                    ok++; _selected.remove(p.second)
                    photoSyncManager.markAsImported(0, p.first.handle)
                }
            }
            _state.value = GalleryState.TransferDone(ok)
        }
    }

    /** Pull-to-refresh: reload current level. */
    fun refresh() {
        scope.launch {
            if (currentPhotos.isEmpty()) loadRoot()
            else loadRoot() // go back to root on refresh
        }
    }

    fun dismissTransferDone() { scope.launch { loadRoot() } }

    fun closeMtp() { nikon.closeMtpDevice(); mtp = null }

    // ── MediaStore ──────────────────────────────────────────────────────────

    private suspend fun saveToMediaStore(m: MtpDevice, photo: NikonUsbManager.PhotoInfo): Boolean {
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
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
        return try {
            app.contentResolver.openOutputStream(uri)?.use { out ->
                nikon.downloadPhoto(m, photo, out, app.cacheDir)
            }
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            app.contentResolver.update(uri, cv, null, null)
            true
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "Transfer failed: ${photo.name}" }
            app.contentResolver.delete(uri, null, null)
            false
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
