package dev.sebastiano.camerasync.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpDeviceInfo
import android.mtp.MtpObjectInfo
import com.juul.khronicle.Log
import dev.sebastiano.camerasync.camera.CameraInfo
import dev.sebastiano.camerasync.camera.CameraSource
import dev.sebastiano.camerasync.camera.FolderInfo
import dev.sebastiano.camerasync.camera.PhotoInfo
import dev.sebastiano.camerasync.camera.StorageInfo
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "UsbCameraSource"

// getObjectHandles format parameter: 0 = all formats
private const val ALL_FORMATS = 0

/**
 * [CameraSource] implementation over USB using Android's built-in `android.mtp.MtpDevice` API.
 *
 * Owns the USB/MTP session ([open] / [close]) plus enumeration, thumbnail and download operations.
 * Everything above the transport (browsing, transfer, UI) depends only on [CameraSource] (ADR-011).
 */
class UsbCameraSource(private val usbManager: UsbManager, private val cacheDir: File) :
    CameraSource {

    override var cameraInfo: CameraInfo? = null
        private set

    override var storages: List<StorageInfo> = emptyList()
        private set

    private var mtpDevice: MtpDevice? = null
    private var usbConnection: UsbDeviceConnection? = null

    /**
     * Opens the USB/MTP session and reads camera info + storages. Returns `false` (after releasing
     * a partly opened connection) on failure.
     */
    fun open(usbDevice: UsbDevice): Boolean {
        val conn = usbManager.openDevice(usbDevice)
        if (conn == null) {
            Log.warn(tag = TAG) {
                "UsbManager.openDevice() returned null — device=${usbDevice.deviceName}"
            }
            return false
        }
        usbConnection = conn

        val mtp = MtpDevice(usbDevice)
        if (!mtp.open(conn)) {
            Log.warn(tag = TAG) {
                "MtpDevice.open() returned false — device=${usbDevice.deviceName}"
            }
            // Release the USB connection — a failed open used to leak it (R34).
            runCatching { conn.close() }
            usbConnection = null
            return false
        }
        mtpDevice = mtp
        Log.info(tag = TAG) { "MtpDevice opened: ${usbDevice.deviceName}" }

        cameraInfo = readCameraInfo(mtp)
        storages = readStorages(mtp)
        Log.info(tag = TAG) { "CameraInfo: $cameraInfo" }
        Log.info(tag = TAG) { "Found ${storages.size} storage(s)" }
        return true
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // MTP close throws unchecked exceptions from the native layer
    override fun close() {
        try {
            mtpDevice?.close()
            usbConnection?.close()
            // Android's MtpDevice/UsbDeviceConnection throw unchecked exceptions from the native
            // MTP layer; close must always proceed to the finally block regardless of failure.
        } catch (e: Exception) {
            Log.warn(tag = TAG, throwable = e) { "Error closing: ${e.message}" }
        } finally {
            mtpDevice = null
            usbConnection = null
            cameraInfo = null
            storages = emptyList()
        }
    }

    private fun readCameraInfo(mtpDevice: MtpDevice): CameraInfo? {
        val info =
            mtpDevice.deviceInfo
                ?: run {
                    Log.warn(tag = TAG) { "deviceInfo is null" }
                    return null
                }
        return CameraInfo(
            manufacturer = info.manufacturer,
            model = info.model,
            serialNumber = info.serialNumber,
            deviceVersion = info.version,
            supportedOps = formatOpCodes(info),
            supportedEvents = formatEventCodes(info),
            vendorExtension = null,
        )
    }

    private fun formatOpCodes(info: MtpDeviceInfo): List<String> {
        val ops = info.operationsSupported ?: return emptyList()
        return ops.toList().mapNotNull { code -> opCodeName(code) ?: "Op(0x${code.toString(16)})" }
    }

    private fun formatEventCodes(info: MtpDeviceInfo): List<String> {
        val events = info.eventsSupported ?: return emptyList()
        return events.toList().mapNotNull { code ->
            eventCodeName(code) ?: "Evt(0x${code.toString(16)})"
        }
    }

    private fun readStorages(mtpDevice: MtpDevice): List<StorageInfo> {
        val ids = mtpDevice.storageIds ?: intArrayOf()
        Log.info(tag = TAG) { "Storage IDs: ${ids.joinToString()}" }
        return ids.toList().mapNotNull { id ->
            val info = mtpDevice.getStorageInfo(id)
            if (info != null) {
                StorageInfo(
                    id = id,
                    description = info.description,
                    maxCapacity = info.maxCapacity,
                    freeSpace = info.freeSpace,
                )
            } else {
                Log.warn(tag = TAG) { "getStorageInfo($id) returned null" }
                null
            }
        }
    }

    /**
     * Recursively enumerates all photo objects via BFS folder traversal, calling [onProgress] with
     * the running scanned count after each photo so callers can show an indeterminate progress.
     *
     * `getObjectHandles(storageId, format, parentHandle)` returns only DIRECT children of
     * `parentHandle`. `parentHandle=0` means root. To get everything we must recurse into folders
     * (format=0x3001).
     */
    override fun listPhotos(
        storageId: Int,
        accumulator: MutableList<PhotoInfo>?,
        onProgress: ((scanned: Int) -> Unit)?,
        onDiagnostic: (String) -> Unit,
    ): List<PhotoInfo> {
        val photos = accumulator ?: mutableListOf<PhotoInfo>()
        val mtpDevice = mtpDevice ?: return photos
        val folderQueue = ArrayDeque<Int>()
        folderQueue.add(0) // root

        var folderCount = 0
        var fileCount = 0

        while (folderQueue.isNotEmpty()) {
            val parent = folderQueue.removeFirst()

            val handles = mtpDevice.getObjectHandles(storageId, ALL_FORMATS, parent)
            if (handles == null) {
                onDiagnostic("  getObjectHandles(storage=$storageId, parent=$parent) → null")
                continue
            }

            onDiagnostic("  parent=$parent ⇒ ${handles.size} children")

            for (handle in handles) {
                val info = mtpDevice.getObjectInfo(handle)
                if (info == null) {
                    onDiagnostic("    [$handle] getObjectInfo → null")
                    continue
                }

                if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
                    // It's a folder — recurse into it
                    folderCount++
                    onDiagnostic("    📁 ${info.name}")
                    folderQueue.add(handle)
                } else {
                    fileCount++
                    val fmtName = formatName(info.format)
                    onDiagnostic(
                        "    📷 ${info.name}  $fmtName  ${formatFileSize(safeCompressedSize(info))}"
                    )

                    photos.add(
                        PhotoInfo(
                            handle = handle,
                            storageId = storageId,
                            name = info.name,
                            size = safeCompressedSize(info),
                            dateModified = safeDateCreated(info),
                            formatName = fmtName,
                            thumbPixWidth = info.thumbPixWidth,
                            thumbPixHeight = info.thumbPixHeight,
                            imagePixWidth = info.imagePixWidth,
                            imagePixHeight = info.imagePixHeight,
                            parentHandle = parent,
                        )
                    )
                    onProgress?.invoke(fileCount)
                }
            }
        }

        Log.info(tag = TAG) {
            "Enumerated $fileCount files in $folderCount folders on storage $storageId"
        }
        photos.sortByDescending { it.dateModified }
        return photos
    }

    /**
     * Lists only folders (FORMAT_ASSOCIATION) directly under [parentHandle]. Use this for
     * folder-based navigation instead of recursive flattening.
     */
    override fun listFolders(storageId: Int, parentHandle: Int): List<FolderInfo> {
        val mtpDevice = mtpDevice ?: return emptyList()
        val handles =
            mtpDevice.getObjectHandles(storageId, MtpConstants.FORMAT_ASSOCIATION, parentHandle)
                ?: return emptyList()

        return handles
            .toList()
            .mapNotNull { h ->
                val info = mtpDevice.getObjectInfo(h) ?: return@mapNotNull null
                FolderInfo(handle = h, name = info.name, dateCreated = safeDateCreated(info))
            }
            .sortedByDescending { it.dateCreated }
    }

    /**
     * Lists only photo files (non-folders) directly under [parentHandle]. Does NOT recurse — this
     * is for folder-based browsing.
     */
    override fun listPhotosInFolder(storageId: Int, parentHandle: Int): List<PhotoInfo> {
        val mtpDevice = mtpDevice ?: return emptyList()
        val handles =
            mtpDevice.getObjectHandles(storageId, ALL_FORMATS, parentHandle) ?: return emptyList()

        return handles
            .toList()
            .mapNotNull { h ->
                val info = mtpDevice.getObjectInfo(h) ?: return@mapNotNull null
                if (info.format == MtpConstants.FORMAT_ASSOCIATION) return@mapNotNull null
                PhotoInfo(
                    handle = h,
                    storageId = storageId,
                    name = info.name,
                    size = safeCompressedSize(info),
                    dateModified = safeDateCreated(info),
                    formatName = formatName(info.format),
                    thumbPixWidth = info.thumbPixWidth,
                    thumbPixHeight = info.thumbPixHeight,
                    imagePixWidth = info.imagePixWidth,
                    imagePixHeight = info.imagePixHeight,
                    parentHandle = parentHandle,
                )
            }
            .sortedByDescending { it.dateModified }
    }

    /**
     * Fetches the camera-generated thumbnail for [handle]. `MtpDevice` may have been closed by the
     * time the native call executes — gracefully return null rather than crashing.
     */
    override fun getThumbnail(handle: Int): ByteArray? {
        val mtpDevice = mtpDevice ?: return null
        return runCatching { mtpDevice.getThumbnail(handle) }.getOrNull()
    }

    /**
     * Downloads a photo to [outputStream], using [cacheDir] as a temporary staging area.
     *
     * @return the number of bytes transferred, or `null` if the transfer failed.
     */
    @Suppress("TooGenericExceptionCaught") // MTP I/O throws unchecked exceptions from native layer
    override suspend fun download(photo: PhotoInfo, outputStream: OutputStream): Long? =
        withContext(Dispatchers.IO) {
            val mtpDevice = mtpDevice ?: return@withContext null
            val tempFile = File(cacheDir, "mtp_${photo.handle}")
            try {
                tempFile.parentFile?.mkdirs()

                val ok = mtpDevice.importFile(photo.handle, tempFile.absolutePath)
                if (!ok) {
                    Log.error(tag = TAG) { "importFile(${photo.handle}) → false" }
                    return@withContext null
                }

                var total = 0L
                FileInputStream(tempFile).use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        outputStream.write(buf, 0, n)
                        total += n
                    }
                }

                Log.info(tag = TAG) { "Downloaded ${photo.name}: $total bytes" }
                total
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.error(tag = TAG, throwable = e) {
                    "Download ${photo.name} failed: ${e.message}"
                }
                null
            } finally {
                // Always drop the staging file — failures/cancellations used to leak it (R32).
                tempFile.delete()
            }
        }

    /** Reads the full object for [handle] into [destFile] (e.g. for EXIF extraction). */
    override suspend fun downloadToFile(handle: Int, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val mtpDevice = mtpDevice ?: return@withContext false
            runCatching {
                    destFile.parentFile?.mkdirs()
                    mtpDevice.importFile(handle, destFile.absolutePath)
                }
                .getOrElse { e ->
                    Log.error(tag = TAG, throwable = e) { "downloadToFile($handle) failed" }
                    false
                }
        }

    /**
     * Deletes a photo from the camera. Returns true if deletion was successful. WARNING:
     * Irreversible. Only call after successful transfer to phone.
     */
    @Suppress("TooGenericExceptionCaught") // MTP throws unchecked exceptions from the native layer
    override fun delete(handle: Int): Boolean {
        val mtpDevice = mtpDevice ?: return false
        return try {
            val ok = mtpDevice.deleteObject(handle)
            if (ok) {
                Log.info(tag = TAG) { "Deleted handle $handle from camera" }
            } else {
                Log.warn(tag = TAG) { "deleteObject($handle) returned false" }
            }
            ok
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "deleteObject($handle) failed" }
            false
        }
    }
}

/**
 * Safely reads [MtpObjectInfo.compressedSize], returning 0 if the field is unavailable.
 *
 * Android's [MtpObjectInfo.getCompressedSize] throws [IllegalStateException] when the MTP driver
 * does not set this field (observed on some Nikon NEF/TIFF files). We catch and return 0 since file
 * size is only used for display/progress estimation — the actual transfer doesn't need it.
 */
private fun safeCompressedSize(info: MtpObjectInfo): Long =
    try {
        info.compressedSize.toLong() and 0xFFFFFFFFL
    } catch (_: IllegalStateException) {
        0L
    }

/**
 * Safely reads [MtpObjectInfo.dateCreated], returning 0 if the field is unavailable.
 *
 * Same defensive pattern as [safeCompressedSize] — [MtpObjectInfo.getDateCreated] also uses
 * [Preconditions.checkState] and can throw for files whose metadata is incomplete.
 */
private fun safeDateCreated(info: MtpObjectInfo): Long =
    try {
        info.dateCreated * 1000L
    } catch (_: IllegalStateException) {
        0L
    }

private fun formatName(code: Int): String =
    when (code) {
        MtpConstants.FORMAT_JFIF -> "JPEG"
        MtpConstants.FORMAT_EXIF_JPEG -> "EXIF_JPEG"
        MtpConstants.FORMAT_TIFF -> "TIFF"
        MtpConstants.FORMAT_TIFF_EP -> "TIFF_EP"
        MtpConstants.FORMAT_BMP -> "BMP"
        MtpConstants.FORMAT_PNG -> "PNG"
        0x380C -> "HEIF"
        0xB103 -> "NEF(RAW)"
        else -> "fmt(0x${code.toString(16)})"
    }

private fun opCodeName(code: Int): String? =
    when (code) {
        0x1001 -> "GetDeviceInfo"
        0x1002 -> "OpenSession"
        0x1003 -> "CloseSession"
        0x1004 -> "GetStorageIDs"
        0x1005 -> "GetStorageInfo"
        0x1007 -> "GetObjectHandles"
        0x1008 -> "GetObjectInfo"
        0x1009 -> "GetObject"
        0x100A -> "GetThumb"
        0x100B -> "DeleteObject"
        0x100E -> "InitiateCapture"
        0x101B -> "GetPartialObject"
        0x9801 -> "GetObjectPropsSupported"
        0x9803 -> "GetObjectPropValue"
        0x9804 -> "SetObjectPropValue"
        else -> null
    }

private fun eventCodeName(code: Int): String? =
    when (code) {
        0x4002 -> "ObjectAdded"
        0x4003 -> "ObjectRemoved"
        0x4004 -> "StoreAdded"
        0x4005 -> "StoreRemoved"
        0x4006 -> "DevicePropChanged"
        0x400C -> "CaptureComplete"
        else -> null
    }

internal fun formatFileSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
