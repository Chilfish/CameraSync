package dev.sebastiano.camerasync.camera

import java.io.File
import java.io.OutputStream

/**
 * The transport seam (ADR-011). Everything above this interface — browsing, thumbnails, transfer
 * and the screens — talks to the camera only through [CameraSource], never through
 * `android.mtp.MtpDevice` or any other transport type.
 *
 * [UsbCameraSource][dev.sebastiano.camerasync.usb.UsbCameraSource] is the current (and only)
 * implementation. A WiFi/PTP-IP implementation can be added later without changing callers.
 */
interface CameraSource {

    /** Camera identity, `null` until the transport is connected. */
    val cameraInfo: CameraInfo?

    /** Storage cards exposed by the camera; empty until connected. */
    val storages: List<StorageInfo>

    /**
     * Recursively enumerates all photo objects on [storageId], calling [onProgress] with the
     * running scanned count after each photo.
     */
    fun listPhotos(
        storageId: Int,
        accumulator: MutableList<PhotoInfo>? = null,
        onProgress: ((scanned: Int) -> Unit)? = null,
        onDiagnostic: (String) -> Unit = {},
    ): List<PhotoInfo>

    /** Lists only folders directly under [parentHandle]. */
    fun listFolders(storageId: Int, parentHandle: Int = 0): List<FolderInfo>

    /** Lists only photo files directly under [parentHandle]. Does not recurse. */
    fun listPhotosInFolder(storageId: Int, parentHandle: Int = 0): List<PhotoInfo>

    /** Fetches the camera-generated thumbnail for [handle], or `null` if unavailable. */
    fun getThumbnail(handle: Int): ByteArray?

    /**
     * Downloads [photo] to [outputStream].
     *
     * @return the number of bytes transferred, or `null` if the transfer failed.
     */
    suspend fun download(photo: PhotoInfo, outputStream: OutputStream): Long?

    /** Reads the full object for [handle] into [destFile]. Returns `false` on failure. */
    suspend fun downloadToFile(handle: Int, destFile: File): Boolean

    /** Deletes the object for [handle] from the camera. Returns `true` on success. */
    fun delete(handle: Int): Boolean

    /** Closes the transport and releases its resources. */
    fun close()
}
