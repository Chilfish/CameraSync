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
import androidx.compose.runtime.mutableStateOf
import com.juul.khronicle.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "GalleryVM"
private const val ACTION_USB_PERMISSION = "dev.sebastiano.camerasync.USB_PERMISSION"

// ── State ──────────────────────────────────────────────────────────────────

sealed interface GalleryState {
    data object Disconnected : GalleryState
    data object RequestingPermission : GalleryState
    data object Connecting : GalleryState
    data class Loading(val loaded: Int, val total: Int) : GalleryState
    data class Browsing(
        val cameraInfo: NikonUsbManager.CameraInfo,
        val groups: List<PhotoGroup>,
        val page: Int,
    ) : GalleryState
    data object Empty : GalleryState
    data class Error(val message: String) : GalleryState
    data class Transferring(
        val synced: Int, val total: Int, val currentFile: String,
    ) : GalleryState
    data class TransferDone(val synced: Int) : GalleryState
}

data class PhotoGroup(
    val baseName: String,
    val raw: NikonUsbManager.PhotoInfo?,
    val jpg: NikonUsbManager.PhotoInfo?,
) {
    /** The handle to use for thumbnail loading (prefer JPEG). */
    val previewHandle: Int get() = jpg?.handle ?: raw!!.handle
    /** Display name (base + format extension). */
    val displayName: String get() = "$baseName.${raw?.formatName ?: jpg?.formatName ?: "?"}"
}

// ── ViewModel ──────────────────────────────────────────────────────────────

class GalleryViewModel(private val app: Application) {
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val nikon = NikonUsbManager(usbManager)
    val prefs = UsbSyncPreferences(app)

    private val _state = mutableStateOf<GalleryState>(GalleryState.Disconnected)
    val state: State<GalleryState> = _state

    private val _selected = mutableSetOf<Int>()
    private val _selectionMode = mutableStateOf(false)
    val selectionMode get() = _selectionMode.value
    val selectedCount get() = _selected.size

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var usbDevice: UsbDevice? = null
    private var mtp: MtpDevice? = null
    private var syncJob: Job? = null

    // Pagination
    private var allHandles = emptyList<Int>()
    private var currentGroups = emptyList<PhotoGroup>()
    private var currentPage = 0
    private val pageSize = 30

    // Thumbnail cache
    private val thumbCache = object : LinkedHashMap<Int, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) =
            size > 128
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    getDevice(intent)?.let { onDeviceFound(it) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    syncJob?.cancel(); closeMtp()
                    allHandles = emptyList(); currentGroups = emptyList()
                    _state.value = GalleryState.Disconnected
                }
                ACTION_USB_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                        getDevice(intent)?.let { usbDevice = it; connectAndLoad() }
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
        findAndConnect()
    }

    fun stop() {
        syncJob?.cancel()
        scope.cancel()
        closeMtp()
        try { app.unregisterReceiver(receiver) } catch (_: Exception) {}
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private fun findAndConnect() {
        usbManager.deviceList.values.firstOrNull { it.vendorId == 0x04B0 }
            ?.let { onDeviceFound(it) }
    }

    private fun onDeviceFound(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            usbDevice = device
            connectAndLoad()
        } else {
            _state.value = GalleryState.RequestingPermission
            val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(app.packageName) }
            val pi = PendingIntent.getBroadcast(
                app, 0, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(device, pi)
        }
    }

    // ── Connect & paginate ─────────────────────────────────────────────────

    private fun connectAndLoad() {
        val device = usbDevice ?: return
        syncJob?.cancel()
        _state.value = GalleryState.Connecting

        syncJob = scope.launch {
            try {
                val m = nikon.openMtpDevice(device)
                if (m == null || !isActive) {
                    if (isActive) _state.value = GalleryState.Error("MTP 连接失败")
                    return@launch
                }
                mtp = m

                val info = nikon.getCameraInfo(m)
                if (info == null || !isActive) {
                    closeMtp()
                    if (isActive) _state.value = GalleryState.Error("无法读取相机信息")
                    return@launch
                }
                Log.info(tag = TAG) { "MTP connected: ${info.manufacturer} ${info.model}" }

                // Collect all handles from all storages
                val handles = mutableListOf<Int>()
                for (s in nikon.getStorages(m)) {
                    val photos = nikon.listPhotos(m, s.id)
                    handles.addAll(photos.map { it.handle })
                }
                allHandles = handles.distinct()
                currentPage = 0
                currentGroups = emptyList()
                _selected.clear()
                _selectionMode.value = false

                if (allHandles.isEmpty()) {
                    _state.value = GalleryState.Empty
                    return@launch
                }

                // Load first page
                loadPage(0)
            } catch (e: Exception) {
                Log.error(tag = TAG, throwable = e) { "connectAndLoad failed" }
                if (isActive) _state.value = GalleryState.Error(e.message ?: "连接失败")
            }
        }
    }

    fun loadNextPage() {
        if (_state.value !is GalleryState.Browsing) return
        val next = currentPage + 1
        val maxPages = (allHandles.size + pageSize - 1) / pageSize
        if (next >= maxPages) return
        scope.launch { loadPage(next) }
    }

    private suspend fun loadPage(page: Int) {
        val start = page * pageSize
        val end = minOf(start + pageSize, allHandles.size)
        val handles = allHandles.subList(start, end)
        currentPage = page

        if (handles.isEmpty()) {
            _state.value = if (currentGroups.isEmpty()) GalleryState.Empty
            else (state.value as? GalleryState.Browsing)?.copy(page = page)
                ?: GalleryState.Empty
            return
        }

        _state.value = GalleryState.Loading(
            if (page == 0) 0 else start, allHandles.size,
        )

        // Fetch object info for this batch
        val m = mtp ?: return
        val newPhotos = mutableListOf<NikonUsbManager.PhotoInfo>()
        for (h in handles) {
            if (!currentCoroutineContext().isActive) return
            m.getObjectInfo(h)?.let { objInfo ->
                newPhotos.add(
                    NikonUsbManager.PhotoInfo(
                        handle = h,
                        name = objInfo.name,
                        size = compressedSizeLong(objInfo),
                        dateModified = objInfo.dateCreated * 1000L,
                        formatName = formatName(objInfo.format),
                    )
                )
            }
        }

        val newGroups = groupByBaseFilename(newPhotos)
        currentGroups = if (page == 0) newGroups else currentGroups + newGroups

        val mInfo = (state.value as? GalleryState.Browsing)?.cameraInfo
            ?: nikon.getCameraInfo(m) ?: return

        _state.value = GalleryState.Browsing(mInfo, currentGroups, page)
    }

    fun getThumbnail(handle: Int): ByteArray? {
        thumbCache[handle]?.let { return it }
        val m = mtp ?: return null
        return m.getThumbnail(handle)?.also { thumbCache[handle] = it }
    }

    // ── Selection ──────────────────────────────────────────────────────────

    fun toggleSelection(handle: Int) {
        if (_selected.contains(handle)) _selected.remove(handle) else _selected.add(handle)
        _selectionMode.value = _selected.isNotEmpty()
    }

    fun selectAll() {
        _selected.addAll(currentGroups.flatMap { g -> listOfNotNull(g.raw?.handle, g.jpg?.handle) })
        _selectionMode.value = true
    }

    fun deselectAll() { _selected.clear(); _selectionMode.value = false }
    fun isSelected(h: Int) = h in _selected

    /** Returns the handle to transfer (prefer RAW). */
    private fun transferHandle(group: PhotoGroup): Int? =
        if (group.raw != null && group.raw.handle in _selected) group.raw.handle
        else if (group.jpg != null && group.jpg.handle in _selected) group.jpg.handle
        else null

    // ── Transfer ────────────────────────────────────────────────────────────

    fun startTransfer() {
        val m = mtp ?: return
        val groups = currentGroups
        if (groups.isEmpty()) return

        val toTransfer = groups.mapNotNull { g ->
            val h = transferHandle(g)
            if (h != null) {
                val photo = listOfNotNull(g.raw, g.jpg).find { it.handle == h }
                if (photo != null) photo to h else null
            } else null
        }
        if (toTransfer.isEmpty()) return

        syncJob?.cancel()
        syncJob = scope.launch {
            var ok = 0
            for ((i, pair) in toTransfer.withIndex()) {
                val (photo, handle) = pair
                if (!currentCoroutineContext().isActive) return@launch
                _state.value = GalleryState.Transferring(i + 1, toTransfer.size, photo.name)

                if (saveToMediaStore(m, photo)) {
                    ok++
                    _selected.remove(handle)
                }
            }
            _selectionMode.value = false
            _state.value = GalleryState.TransferDone(ok)
        }
    }

    fun dismissTransferDone() {
        _state.value = if (currentGroups.isEmpty()) GalleryState.Empty
        else GalleryState.Browsing(
            (state.value as? GalleryState.Browsing)?.cameraInfo
                ?: NikonUsbManager.CameraInfo("Nikon", "Z30", null, null, emptyList(), emptyList(), null),
            currentGroups, currentPage,
        )
    }

    fun closeMtp() { nikon.closeMtpDevice(); mtp = null }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun saveToMediaStore(
        m: MtpDevice, photo: NikonUsbManager.PhotoInfo,
    ): Boolean {
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
        /** Group MTP photos into RAW+JPEG pairs by base filename. */
        fun groupByBaseFilename(
            photos: List<NikonUsbManager.PhotoInfo>,
        ): List<PhotoGroup> {
            val map = linkedMapOf<String, MutableList<NikonUsbManager.PhotoInfo>>()
            for (p in photos) {
                val base = p.name.substringBeforeLast(".")
                map.getOrPut(base) { mutableListOf() }.add(p)
            }
            return map.map { (base, list) ->
                PhotoGroup(
                    baseName = base,
                    raw = list.find { it.formatName == "NEF(RAW)" || it.name.endsWith(".NEF", true) },
                    jpg = list.find { it.formatName == "JPEG" || it.name.endsWith(".JPG", true) },
                )
            }
        }

        private fun compressedSizeLong(info: android.mtp.MtpObjectInfo): Long =
            info.compressedSize.toLong() and 0xFFFFFFFFL

        private fun formatName(code: Int): String = when (code) {
            android.mtp.MtpConstants.FORMAT_JFIF -> "JPEG"
            android.mtp.MtpConstants.FORMAT_EXIF_JPEG -> "EXIF_JPEG"
            0x380C -> "HEIF"
            0xB103 -> "NEF(RAW)"
            else -> "fmt(0x${code.toString(16)})"
        }
    }
}
