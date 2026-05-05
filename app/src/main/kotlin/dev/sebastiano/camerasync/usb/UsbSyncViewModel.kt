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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juul.khronicle.Log
import dev.sebastiano.camerasync.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "UsbSyncVM"
private const val ACTION_USB_PERMISSION = "dev.sebastiano.camerasync.USB_PERMISSION"

sealed interface UsbSyncState {
    data object Idle : UsbSyncState

    data class DeviceDetected(val deviceName: String, val hasPermission: Boolean) : UsbSyncState

    data object PermissionDenied : UsbSyncState

    data object Connecting : UsbSyncState

    data class Connected(
        val cameraInfo: NikonUsbManager.CameraInfo,
        val storages: List<NikonUsbManager.StorageInfo>,
        val photos: List<NikonUsbManager.PhotoInfo>,
        val isDownloading: Boolean = false,
        val downloadProgress: DownloadProgress? = null,
        val lastSpeedBytesPerSec: Long = 0L,
    ) : UsbSyncState

    data class Syncing(
        val cameraInfo: NikonUsbManager.CameraInfo,
        val photos: List<NikonUsbManager.PhotoInfo>,
        val downloadProgress: DownloadProgress,
        val lastSpeedBytesPerSec: Long = 0L,
    ) : UsbSyncState

    data class Error(val message: String) : UsbSyncState
}

data class DownloadProgress(val current: Int, val total: Int, val currentFileName: String)

class UsbSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
    private val nikonUsbManager = NikonUsbManager(usbManager)
    val prefs = UsbSyncPreferences(application)

    private val _state = mutableStateOf<UsbSyncState>(UsbSyncState.Idle)
    val state: State<UsbSyncState> = _state

    private val _selectedHandles = mutableSetOf<Int>()
    private val _selectionMode = mutableStateOf(false)
    val selectionMode: State<Boolean> = _selectionMode
    val selectedCount
        get() = _selectedHandles.size

    private var currentUsbDevice: UsbDevice? = null
    private var currentMtpDevice: MtpDevice? = null
    private var downloadJob: Job? = null

    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = getDeviceExtra(intent)
                        if (device != null) {
                            Log.info(tag = TAG) { "USB attached: ${device.deviceName}" }
                            onDeviceFound(device)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Log.info(tag = TAG) { "USB detached" }
                        closeConnection()
                        _state.value = UsbSyncState.Idle
                    }
                    ACTION_USB_PERMISSION -> {
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val device = getDeviceExtra(intent)
                        if (granted && device != null) {
                            Log.info(tag = TAG) { "USB permission granted" }
                            currentUsbDevice = device
                            connectMtp()
                        } else {
                            Log.warn(tag = TAG) { "USB permission denied" }
                            _state.value = UsbSyncState.PermissionDenied
                        }
                    }
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun getDeviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    init {
        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
        getApplication<Application>()
            .registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        checkForExistingDevice()
    }

    private fun checkForExistingDevice() {
        val devices = usbManager.deviceList
        if (devices.isEmpty()) return

        Log.info(tag = TAG) { "Existing USB devices: ${devices.size}" }
        for (d in devices.values) {
            Log.info(tag = TAG) {
                "${d.deviceName} VID:0x${d.vendorId.toString(16)} PID:0x${d.productId.toString(16)}"
            }
        }

        val camera = devices.values.firstOrNull { it.vendorId == 0x04B0 }
        if (camera != null) onDeviceFound(camera)
    }

    private fun onDeviceFound(device: UsbDevice) {
        currentUsbDevice = device
        val hasPerm = usbManager.hasPermission(device)
        _state.value =
            UsbSyncState.DeviceDetected(deviceName = device.deviceName, hasPermission = hasPerm)
        Log.info(tag = TAG) { "Camera detected: ${device.deviceName} (permission=$hasPerm)" }
    }

    // ── Guided sync flow (Phase 7.1) ──────────────────────────────────────

    /**
     * Single-tap "Start Sync" flow: request permission → connect → list → download selected. Skips
     * manual permission step if already granted.
     */
    fun startSyncFlow() {
        when (val s = _state.value) {
            is UsbSyncState.Idle -> return
            is UsbSyncState.DeviceDetected -> {
                if (s.hasPermission) {
                    connectMtp()
                } else {
                    requestPermission()
                }
            }
            is UsbSyncState.PermissionDenied -> requestPermission()
            is UsbSyncState.Connecting -> {
                /* already connecting */
            }
            is UsbSyncState.Connected -> {
                if (s.photos.isEmpty()) {
                    listPhotos()
                } else {
                    downloadSelected()
                }
            }
            is UsbSyncState.Syncing -> cancelDownload()
            is UsbSyncState.Error -> reset()
        }
    }

    /** Returns a label for the primary action button based on current state. */
    fun primaryActionLabel(): String {
        val ctx = getApplication<Application>()
        return when (_state.value) {
            is UsbSyncState.Idle -> ctx.getString(R.string.usb_status_waiting)
            is UsbSyncState.DeviceDetected -> ctx.getString(R.string.usb_action_connect)
            is UsbSyncState.PermissionDenied ->
                ctx.getString(R.string.usb_action_request_permission)
            is UsbSyncState.Connecting -> ctx.getString(R.string.usb_status_connecting)
            is UsbSyncState.Connected -> {
                val s = _state.value as UsbSyncState.Connected
                if (s.photos.isEmpty()) ctx.getString(R.string.usb_action_list_photos)
                else ctx.getString(R.string.usb_action_download_selected, _selectedHandles.size)
            }
            is UsbSyncState.Syncing -> ctx.getString(R.string.usb_action_cancel_download)
            is UsbSyncState.Error -> ctx.getString(R.string.usb_action_retry)
        }
    }

    // ── Selection (Phase 7.1) ──────────────────────────────────────────────

    fun togglePhotoSelection(handle: Int) {
        if (_selectedHandles.contains(handle)) _selectedHandles.remove(handle)
        else _selectedHandles.add(handle)
        _selectionMode.value = _selectedHandles.isNotEmpty()
    }

    fun selectAll() {
        val photos = (_state.value as? UsbSyncState.Connected)?.photos ?: return
        _selectedHandles.addAll(photos.map { it.handle })
        _selectionMode.value = true
    }

    fun deselectAll() {
        _selectedHandles.clear()
        _selectionMode.value = false
    }

    fun isSelected(handle: Int): Boolean = handle in _selectedHandles

    fun downloadSelected() {
        val mtp = currentMtpDevice ?: return
        val current = _state.value as? UsbSyncState.Connected ?: return
        val toDownload =
            if (_selectedHandles.isEmpty()) current.photos
            else current.photos.filter { it.handle in _selectedHandles }

        if (toDownload.isEmpty()) return

        downloadJob?.cancel()
        downloadJob =
            viewModelScope.launch(Dispatchers.IO) {
                val total = toDownload.size
                var ok = 0
                var fail = 0

                Log.info(tag = TAG) { "Downloading $total photos" }

                for ((i, photo) in toDownload.withIndex()) {
                    _state.value =
                        UsbSyncState.Syncing(
                            cameraInfo = current.cameraInfo,
                            photos = current.photos,
                            downloadProgress = DownloadProgress(i + 1, total, photo.name),
                            lastSpeedBytesPerSec = 0L,
                        )

                    val startNanos = System.nanoTime()
                    val success = saveToMediaStore(mtp, photo)
                    val elapsed = System.nanoTime() - startNanos

                    if (success) {
                        ok++
                        val bytesPerSec =
                            if (elapsed > 0) photo.size * 1_000_000_000L / elapsed else 0L
                        _state.value =
                            UsbSyncState.Syncing(
                                cameraInfo = current.cameraInfo,
                                photos = current.photos,
                                downloadProgress = DownloadProgress(i + 1, total, photo.name),
                                lastSpeedBytesPerSec = bytesPerSec,
                            )
                    } else {
                        fail++
                    }
                }

                Log.info(tag = TAG) { "Download done: $ok ok, $fail fail, $total total" }
                _selectedHandles.clear()
                _selectionMode.value = false
                _state.value = current.copy(isDownloading = false, downloadProgress = null)
            }
    }

    // ── Permission & connection ────────────────────────────────────────────

    fun requestPermission() {
        val device =
            currentUsbDevice
                ?: run {
                    Log.warn(tag = TAG) { "requestPermission: no device" }
                    return
                }
        val intent =
            Intent(ACTION_USB_PERMISSION).apply {
                setPackage(getApplication<Application>().packageName)
            }
        val pi =
            PendingIntent.getBroadcast(
                getApplication(),
                0,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        usbManager.requestPermission(device, pi)
    }

    fun connectMtp() {
        val device =
            currentUsbDevice
                ?: run {
                    Log.warn(tag = TAG) { "connectMtp: no device" }
                    return
                }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.value = UsbSyncState.Connecting

                val mtp = nikonUsbManager.openMtpDevice(device)
                if (mtp == null) {
                    _state.value = UsbSyncState.Error("MTP 连接失败 — 请确认相机 USB 模式为 MTP/PTP")
                    return@launch
                }
                currentMtpDevice = mtp

                val info = nikonUsbManager.getCameraInfo(mtp)
                if (info == null) {
                    closeConnection()
                    _state.value = UsbSyncState.Error("无法读取相机信息")
                    return@launch
                }

                Log.info(tag = TAG) { "MTP connected: ${info.manufacturer} ${info.model}" }

                val storages = nikonUsbManager.getStorages(mtp)
                for (s in storages) {
                    Log.info(tag = TAG) {
                        "Storage: ${s.description} ${formatFileSize(s.freeSpace)}/${formatFileSize(s.maxCapacity)}"
                    }
                }

                _state.value =
                    UsbSyncState.Connected(
                        cameraInfo = info,
                        storages = storages,
                        photos = emptyList(),
                    )
            } catch (e: Exception) {
                Log.error(tag = TAG, throwable = e) { "connectMtp failed" }
                _state.value = UsbSyncState.Error("连接失败: ${e.message}")
            }
        }
    }

    fun listPhotos() {
        val mtp = currentMtpDevice ?: return
        val current = _state.value as? UsbSyncState.Connected ?: return
        if (current.storages.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val allPhotos = mutableListOf<NikonUsbManager.PhotoInfo>()

            for (storage in current.storages) {
                Log.info(tag = TAG) { "Scanning storage #${storage.id}: ${storage.description}" }
                val photos = nikonUsbManager.listPhotos(mtp, storage.id)
                allPhotos.addAll(photos)
                Log.info(tag = TAG) { "  ${photos.size} files on ${storage.description}" }
            }

            // Apply format filter
            val filtered = applyFormatFilter(allPhotos)
            if (filtered.size < allPhotos.size) {
                Log.info(tag = TAG) {
                    "Format filter (${prefs.downloadFormat}): ${allPhotos.size} → ${filtered.size}"
                }
            }

            Log.info(tag = TAG) { "Total: ${filtered.size} files" }
            _selectedHandles.clear()
            _selectionMode.value = false
            _state.value = current.copy(photos = filtered)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val current = _state.value
        when (current) {
            is UsbSyncState.Connected ->
                _state.value = current.copy(isDownloading = false, downloadProgress = null)
            is UsbSyncState.Syncing ->
                _state.value =
                    UsbSyncState.Connected(
                        cameraInfo = current.cameraInfo,
                        storages = emptyList(),
                        photos = current.photos,
                    )
            else -> {}
        }
        Log.info(tag = TAG) { "Download cancelled" }
    }

    // ── MediaStore ──────────────────────────────────────────────────────────

    private suspend fun saveToMediaStore(
        mtp: MtpDevice,
        photo: NikonUsbManager.PhotoInfo,
    ): Boolean {
        val ctx = getApplication<Application>()
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(photo.dateModified))
        val path = "Pictures/CameraSync/Nikon Z30/$dateFolder"

        val mime =
            when {
                photo.name.endsWith(".NEF", ignoreCase = true) -> "image/x-nikon-nef"
                photo.name.endsWith(".HEIC", ignoreCase = true) -> "image/heic"
                photo.name.endsWith(".PNG", ignoreCase = true) -> "image/png"
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
            ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return false

        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                nikonUsbManager.downloadPhoto(mtp, photo, out, ctx.cacheDir)
            }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, cv, null, null)
            true
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "MediaStore save failed: ${photo.name}" }
            ctx.contentResolver.delete(uri, null, null)
            false
        }
    }

    // ── Format filtering ────────────────────────────────────────────────────

    private fun applyFormatFilter(
        photos: List<NikonUsbManager.PhotoInfo>,
    ): List<NikonUsbManager.PhotoInfo> {
        return when (prefs.downloadFormat) {
            UsbSyncPreferences.DownloadFormat.ALL -> photos
            UsbSyncPreferences.DownloadFormat.JPEG_ONLY -> photos.filter { p ->
                !p.name.endsWith(".NEF", ignoreCase = true)
            }
            UsbSyncPreferences.DownloadFormat.RAW_ONLY -> photos.filter { p ->
                p.name.endsWith(".NEF", ignoreCase = true)
            }
        }
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    private fun closeConnection() {
        nikonUsbManager.closeMtpDevice()
        currentMtpDevice = null
        currentUsbDevice = null
    }

    fun reset() {
        downloadJob?.cancel()
        downloadJob = null
        _selectedHandles.clear()
        _selectionMode.value = false
        closeConnection()
        _state.value = UsbSyncState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        reset()
        try {
            getApplication<Application>().unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }
}
