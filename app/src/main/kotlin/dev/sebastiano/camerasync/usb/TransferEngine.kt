package dev.sebastiano.camerasync.usb

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.juul.khronicle.Log
import dev.sebastiano.camerasync.camera.CameraInfo
import dev.sebastiano.camerasync.camera.CameraSource
import dev.sebastiano.camerasync.camera.PhotoInfo
import kotlinx.coroutines.CancellationException
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
 * [camera] and [cameraInfo] are read via accessors so the engine stays decoupled from the transport
 * (ADR-011). [cancelPendingWork] cancels any in-flight connect/load job before a transfer starts —
 * preserves the pre-split "one active operation" guarantee.
 */
class TransferEngine(
    private val scope: () -> CoroutineScope,
    private val app: Application,
    private val camera: () -> CameraSource?,
    private val photoSyncManager: PhotoSyncManager,
    private val stateMachine: GalleryStateMachine,
    private val prefs: UsbSyncPreferences,
    private val cameraInfo: () -> CameraInfo?,
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
        val toTransfer = buildTransferList { stateMachine.isSelected(it) }
        if (toTransfer.isEmpty()) {
            stateMachine.setState(GalleryState.TransferDone(0))
            return
        }

        failedHandles = emptyList()
        cancelPendingWork()
        transferJob?.cancel()
        transferJob = scope().launch { performTransfer(toTransfer) }
    }

    fun retryFailedTransfers() {
        if (failedHandles.isEmpty()) return
        val toRetry = buildTransferList { it in failedHandles }
        if (toRetry.isEmpty()) return

        stateMachine.deselectAll()
        failedHandles = emptyList()
        cancelPendingWork()
        transferJob?.cancel()
        transferJob = scope().launch { performTransfer(toRetry) }
    }

    /**
     * Deletes the given photo handles from the camera. Returns the number of successfully deleted
     * photos.
     */
    fun deletePhotos(handles: List<Int>): Int {
        val source = camera() ?: return 0
        return handles.count { handle -> source.delete(handle) }
    }

    /**
     * Deletes photos that were just transferred (using saved handles from TransferDone). Returns
     * the number of deleted photos.
     */
    suspend fun deleteTransferredPhotos(handles: List<Int>): Int =
        withContext(Dispatchers.IO) { deletePhotos(handles) }

    /** Builds the transfer list by filtering [stateMachine.currentPhotos] with [handleFilter]. */
    private fun buildTransferList(handleFilter: (Int) -> Boolean): List<Pair<PhotoInfo, Int>> {
        // Expand every selected handle of a group — a RAW+JPEG pair selected with ALL yields two
        // entries, so both files transfer (R21). Raw is listed first so ordering is raw → JPEG.
        return stateMachine.currentPhotos.flatMap { g ->
            listOfNotNull(g.raw, g.jpg)
                .filter { handleFilter(it.handle) && !photoSyncManager.isAlreadyImported(it) }
                .map { it to it.handle }
        }
    }

    /** Core transfer loop. Updates [stateMachine.state], selection, and [failedHandles]. */
    private suspend fun performTransfer(toTransfer: List<Pair<PhotoInfo, Int>>) {
        val source = camera() ?: return
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
            val uri = saveToMediaStore(source, p.first)
            if (uri != null) {
                ok++
                stateMachine.deselect(p.second)
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

    @Suppress("TooGenericExceptionCaught") // MTP/MediaStore throw mixed unchecked exceptions
    private suspend fun saveToMediaStore(source: CameraSource, photo: PhotoInfo): android.net.Uri? {
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
        return try {
            val bytes =
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    source.download(photo, out)
                } ?: 0L
            if (bytes <= 0L) {
                app.contentResolver.delete(uri, null, null)
                null
            } else {
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                app.contentResolver.update(uri, cv, null, null)
                uri
            }
        } catch (e: CancellationException) {
            // Cancellation must propagate instead of being recorded as a transfer failure; drop the
            // half-written pending row first (R32).
            app.contentResolver.delete(uri, null, null)
            throw e
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "Transfer failed: ${photo.name}" }
            app.contentResolver.delete(uri, null, null)
            null
        }
    }
}
