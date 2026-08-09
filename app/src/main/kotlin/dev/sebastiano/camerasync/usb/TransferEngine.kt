package dev.sebastiano.camerasync.usb

import android.app.Application
import android.content.ContentValues
import android.mtp.MtpDevice
import android.net.Uri
import android.provider.MediaStore
import com.juul.khronicle.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TransferEngine"

/**
 * Owns transfer orchestration: building the transfer list from the current selection, running the
 * per-photo download loop, saving to MediaStore, and camera deletion (P2-1 extraction from
 * [GalleryViewModel]).
 *
 * [mtp] and [cameraInfo] are read via accessors so the engine stays decoupled from the USB
 * lifecycle. [cancelPendingWork] cancels any in-flight connect/load job before a transfer starts —
 * preserves the pre-split "one active operation" guarantee.
 */
class TransferEngine(
    private val scope: CoroutineScope,
    private val app: Application,
    private val nikon: NikonUsbManager,
    private val photoSyncManager: PhotoSyncManager,
    private val stateMachine: GalleryStateMachine,
    private val prefs: UsbSyncPreferences,
    private val mtp: () -> MtpDevice?,
    private val cameraInfo: () -> NikonUsbManager.CameraInfo?,
    private val cancelPendingWork: () -> Unit,
) {

    /** Handles that were successfully transferred in the last [startTransfer] call. */
    var lastTransferredHandles: List<Int> = emptyList()
        private set

    /** Handles that failed during the last transfer attempt. Populated in [performTransfer]. */
    var failedHandles: List<Int> = emptyList()
        private set

    private var transferJob: Job? = null

    /** Cancels an in-flight transfer (called on stop/USB detach). */
    fun cancelTransfer() {
        transferJob?.cancel()
    }

    fun startTransfer() {
        val toTransfer = buildTransferList { it in stateMachine.selected }
        if (toTransfer.isEmpty()) {
            stateMachine.setState(GalleryState.TransferDone(0))
            return
        }

        failedHandles = emptyList()
        cancelPendingWork()
        transferJob?.cancel()
        transferJob = scope.launch { performTransfer(toTransfer) }
    }

    fun retryFailedTransfers() {
        if (failedHandles.isEmpty()) return
        val toRetry = buildTransferList { it in failedHandles }
        if (toRetry.isEmpty()) return

        stateMachine.selected.clear()
        failedHandles = emptyList()
        cancelPendingWork()
        transferJob?.cancel()
        transferJob = scope.launch { performTransfer(toRetry) }
    }

    /**
     * Deletes the given photo handles from the camera via MTP. Returns the number of successfully
     * deleted photos.
     */
    fun deletePhotos(handles: List<Int>): Int {
        val m = mtp() ?: return 0
        return handles.count { handle -> nikon.deletePhoto(m, handle) }
    }

    /**
     * Deletes photos that were just transferred (using saved handles from TransferDone). Returns
     * the number of deleted photos.
     */
    suspend fun deleteTransferredPhotos(handles: List<Int>): Int =
        withContext(Dispatchers.IO) { deletePhotos(handles) }

    /** Builds the transfer list by filtering [stateMachine.currentPhotos] with [handleFilter]. */
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

    /** Core transfer loop. Updates [stateMachine.state], selection, and [failedHandles]. */
    private suspend fun performTransfer(toTransfer: List<Pair<NikonUsbManager.PhotoInfo, Int>>) {
        val m = mtp() ?: return
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
            prefs.addTransferRecord(ok, cameraInfo()?.model ?: "Nikon")
        }
        stateMachine.setState(GalleryState.TransferDone(ok, savedUris.toList()))
    }

    private suspend fun saveToMediaStore(
        m: MtpDevice,
        photo: NikonUsbManager.PhotoInfo,
    ): android.net.Uri? {
        val path = "Pictures/CameraSync/${cameraInfo()?.model ?: "Nikon"}"
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
        return runCatching {
                val bytes =
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        nikon.downloadPhoto(m, photo, out, app.cacheDir)
                    } ?: 0L
                if (bytes <= 0L) {
                    app.contentResolver.delete(uri, null, null)
                    return@runCatching null
                }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                app.contentResolver.update(uri, cv, null, null)
                uri
            }
            .getOrElse { e ->
                Log.error(tag = TAG, throwable = e) { "Transfer failed: ${photo.name}" }
                app.contentResolver.delete(uri, null, null)
                null
            }
    }
}
