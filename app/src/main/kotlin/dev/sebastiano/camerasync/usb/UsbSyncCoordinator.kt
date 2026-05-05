package dev.sebastiano.camerasync.usb

import android.content.ContentValues
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.provider.MediaStore
import com.juul.khronicle.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "UsbSyncCoordinator"

sealed interface UsbSyncServiceState {
    data object Idle : UsbSyncServiceState
    data object Stopped : UsbSyncServiceState
    data class Syncing(val synced: Int, val total: Int, val currentFile: String)
        : UsbSyncServiceState
    data class Completed(val synced: Int, val total: Int) : UsbSyncServiceState
    data class Error(val message: String) : UsbSyncServiceState
}

/**
 * Coordinates the USB MTP sync lifecycle: connect, enumerate, detect new photos,
 * and download them.
 *
 * Used by [UsbSyncService] for background auto-sync. Can also be used by the
 * foreground UI (via [UsbSyncViewModel]) for manual sync.
 */
class UsbSyncCoordinator(
    private val context: Context,
    private val usbManager: UsbManager,
    private val nikonUsbManager: NikonUsbManager,
    private val photoSyncManager: PhotoSyncManager,
    scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<UsbSyncServiceState>(UsbSyncServiceState.Idle)
    val state: StateFlow<UsbSyncServiceState> = _state.asStateFlow()

    private var mtpDevice: MtpDevice? = null
    private var usbDevice: UsbDevice? = null

    /**
     * Attempts to find and connect to a Nikon USB camera, list its photos,
     * and download any new ones.
     *
     * @return the number of newly imported photos (0 if already synced).
     */
    suspend fun syncOnce(): SyncResult {
        try {
            // Find the camera
            val device = usbManager.deviceList.values
                .firstOrNull { it.vendorId == 0x04B0 }
            if (device == null) {
                Log.warn(tag = TAG) { "No Nikon USB device found" }
                return SyncResult(0, 0, 0, "No camera found")
            }
            usbDevice = device

            // Open MTP
            val mtp = nikonUsbManager.openMtpDevice(device)
            if (mtp == null) {
                Log.warn(tag = TAG) { "Failed to open MTP device" }
                return SyncResult(0, 0, 0, "MTP open failed")
            }
            mtpDevice = mtp

            try {
                // Get camera info for logging
                nikonUsbManager.getCameraInfo(mtp)?.let { cam ->
                    Log.info(tag = TAG) { "Connected: ${cam.manufacturer} ${cam.model}" }
                }

                // Enumerate all storages
                val storages = nikonUsbManager.getStorages(mtp)
                Log.info(tag = TAG) { "Found ${storages.size} storage(s)" }

                var totalPhotos = 0
                var newPhotos = 0
                var synced = 0
                var failed = 0

                for (storage in storages) {
                    val photos = nikonUsbManager.listPhotos(mtp, storage.id)
                    totalPhotos += photos.size

                    val newOnes = photos.filter { !photoSyncManager.isAlreadyImported(storage.id, it.handle) }
                    newPhotos += newOnes.size

                    if (newOnes.isEmpty()) {
                        Log.info(tag = TAG) { "Storage ${storage.id}: ${photos.size} photos, 0 new" }
                        continue
                    }

                    Log.info(tag = TAG) {
                        "Storage ${storage.id}: ${photos.size} photos, ${newOnes.size} new"
                    }

                    for ((idx, photo) in newOnes.withIndex()) {
                        val i = synced + idx + 1
                        _state.value = UsbSyncServiceState.Syncing(i, newOnes.size, photo.name)

                        if (saveToMediaStore(mtp, photo)) {
                            photoSyncManager.markAsImported(storage.id, photo.handle)
                            synced++
                        } else {
                            failed++
                        }
                    }
                }

                Log.info(tag = TAG) {
                    "Sync done: $synced new imported, $failed failed, $totalPhotos total"
                }

                _state.value = UsbSyncServiceState.Completed(synced, totalPhotos)
                return SyncResult(synced, failed, totalPhotos, null)
            } finally {
                closeMtp()
            }
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "syncOnce failed: ${e.message}" }
            _state.value = UsbSyncServiceState.Error(e.message ?: "Unknown error")
            return SyncResult(0, 0, 0, e.message)
        }
    }

    fun reset() {
        closeMtp()
        _state.value = UsbSyncServiceState.Idle
    }

    private fun closeMtp() {
        nikonUsbManager.closeMtpDevice()
        mtpDevice = null
    }

    private suspend fun saveToMediaStore(mtp: MtpDevice, photo: NikonUsbManager.PhotoInfo): Boolean {
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(photo.dateModified))
        val path = "Pictures/CameraSync/Nikon Z30/$dateFolder"

        val mime = when {
            photo.name.endsWith(".NEF", ignoreCase = true) -> "image/x-nikon-nef"
            photo.name.endsWith(".HEIC", ignoreCase = true) -> "image/heic"
            photo.name.endsWith(".PNG", ignoreCase = true) -> "image/png"
            else -> "image/jpeg"
        }

        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = context.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return false

        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                nikonUsbManager.downloadPhoto(mtp, photo, out, context.cacheDir)
            }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, cv, null, null)
            true
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "MediaStore save failed: ${photo.name}" }
            context.contentResolver.delete(uri, null, null)
            false
        }
    }
}

data class SyncResult(
    val synced: Int,
    val failed: Int,
    val total: Int,
    val error: String?,
) {
    val isSuccess: Boolean get() = error == null
}
