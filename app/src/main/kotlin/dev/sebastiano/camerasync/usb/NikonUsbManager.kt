package dev.sebastiano.camerasync.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpDeviceInfo
import android.mtp.MtpObjectInfo
import com.juul.khronicle.Log
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "NikonUsbManager"

// getObjectHandles format parameter: 0 = all formats
private const val ALL_FORMATS = 0

class NikonUsbManager(private val usbManager: UsbManager) {

    data class CameraInfo(
        val manufacturer: String,
        val model: String,
        val serialNumber: String?,
        val deviceVersion: String?,
        val supportedOps: List<String>,
        val supportedEvents: List<String>,
        val vendorExtension: String?,
    )

    data class StorageInfo(
        val id: Int,
        val description: String,
        val maxCapacity: Long,
        val freeSpace: Long,
    )

    data class PhotoInfo(
        val handle: Int,
        val name: String,
        val size: Long,
        val dateModified: Long,
        val formatName: String,
        /** Thumbnail pixel dimensions from MtpObjectInfo — may be 0 if unavailable. */
        val thumbPixWidth: Int = 0,
        val thumbPixHeight: Int = 0,
    )

    private var mtpDevice: MtpDevice? = null
    private var usbConnection: UsbDeviceConnection? = null

    fun openMtpDevice(usbDevice: UsbDevice): MtpDevice? {
        val conn = usbManager.openDevice(usbDevice)
        if (conn == null) {
            Log.warn(tag = TAG) {
                "UsbManager.openDevice() returned null — device=${usbDevice.deviceName}"
            }
            return null
        }
        usbConnection = conn

        val mtp = MtpDevice(usbDevice)
        val ok = mtp.open(conn)
        if (!ok) {
            Log.warn(tag = TAG) {
                "MtpDevice.open() returned false — device=${usbDevice.deviceName}"
            }
            return null
        }
        mtpDevice = mtp
        Log.info(tag = TAG) { "MtpDevice opened: ${usbDevice.deviceName}" }
        return mtp
    }

    fun closeMtpDevice() {
        try {
            mtpDevice?.close()
            usbConnection?.close()
        } catch (e: Exception) {
            Log.warn(tag = TAG, throwable = e) { "Error closing: ${e.message}" }
        } finally {
            mtpDevice = null
            usbConnection = null
        }
    }

    fun getCameraInfo(mtpDevice: MtpDevice): CameraInfo? {
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

    fun getStorages(mtpDevice: MtpDevice): List<StorageInfo> {
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
     * Recursively enumerates all photo objects via BFS folder traversal.
     *
     * `getObjectHandles(storageId, format, parentHandle)` returns only DIRECT children of
     * `parentHandle`. `parentHandle=0` means root. To get everything we must recurse into folders
     * (format=0x3001).
     */
    fun listPhotos(
        mtpDevice: MtpDevice,
        storageId: Int,
        onDiagnostic: (String) -> Unit = {},
    ): List<PhotoInfo> {
        val photos = mutableListOf<PhotoInfo>()
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

            onDiagnostic("  parent=$parent ⇒ ${handles.size} 个子对象")

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
                        "    📷 ${info.name}  $fmtName  ${formatFileSize(compressedSizeLong(info))}"
                    )

                    photos.add(
                        PhotoInfo(
                            handle = handle,
                            name = info.name,
                            size = compressedSizeLong(info),
                            dateModified = info.dateCreated * 1000L,
                            formatName = fmtName,
                            thumbPixWidth = info.thumbPixWidth,
                            thumbPixHeight = info.thumbPixHeight,
                        )
                    )
                }
            }
        }

        Log.info(tag = TAG) {
            "Enumerated $fileCount files in $folderCount folders on storage $storageId"
        }
        photos.sortByDescending { it.dateModified }
        return photos
    }

    data class FolderInfo(
        val handle: Int,
        val name: String,
        val dateCreated: Long,
    )

    /**
     * Lists only folders (FORMAT_ASSOCIATION) directly under [parentHandle].
     * Use this for folder-based navigation instead of recursive flattening.
     */
    fun listFolders(
        mtpDevice: MtpDevice,
        storageId: Int,
        parentHandle: Int = 0,
    ): List<FolderInfo> {
        val handles = mtpDevice.getObjectHandles(
            storageId, MtpConstants.FORMAT_ASSOCIATION, parentHandle,
        ) ?: return emptyList()

        return handles.toList().mapNotNull { h ->
            val info = mtpDevice.getObjectInfo(h) ?: return@mapNotNull null
            FolderInfo(handle = h, name = info.name, dateCreated = info.dateCreated * 1000L)
        }.sortedByDescending { it.dateCreated }
    }

    /**
     * Lists only photo files (non-folders) directly under [parentHandle].
     * Does NOT recurse — this is for folder-based browsing.
     */
    fun listPhotosInFolder(
        mtpDevice: MtpDevice,
        storageId: Int,
        parentHandle: Int = 0,
    ): List<PhotoInfo> {
        val handles = mtpDevice.getObjectHandles(storageId, ALL_FORMATS, parentHandle)
            ?: return emptyList()

        return handles.toList().mapNotNull { h ->
            val info = mtpDevice.getObjectInfo(h) ?: return@mapNotNull null
            if (info.format == MtpConstants.FORMAT_ASSOCIATION) return@mapNotNull null
            PhotoInfo(
                handle = h,
                name = info.name,
                size = compressedSizeLong(info),
                dateModified = info.dateCreated * 1000L,
                formatName = formatName(info.format),
                thumbPixWidth = info.thumbPixWidth,
                thumbPixHeight = info.thumbPixHeight,
            )
        }.sortedByDescending { it.dateModified }
    }

    /**
     * Deletes a photo from the camera via MTP.
     * Returns true if deletion was successful.
     * WARNING: Irreversible. Only call after successful transfer to phone.
     */
    fun deletePhoto(mtpDevice: MtpDevice, handle: Int): Boolean {
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

    /**
     * Downloads a photo from the MTP device to [outputStream], using [cacheDir]
     * as a temporary staging area.
     *
     * @return the number of bytes transferred, or `null` if the transfer failed.
     */
    suspend fun downloadPhoto(
        mtpDevice: MtpDevice,
        photoInfo: PhotoInfo,
        outputStream: OutputStream,
        cacheDir: File,
    ): Long? =
        withContext(Dispatchers.IO) {
            try {
                val tempFile = File(cacheDir, "mtp_${photoInfo.handle}")
                tempFile.parentFile?.mkdirs()

                val ok = mtpDevice.importFile(photoInfo.handle, tempFile.absolutePath)
                if (!ok) {
                    Log.error(tag = TAG) { "importFile(${photoInfo.handle}) → false" }
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
                tempFile.delete()

                Log.info(tag = TAG) { "Downloaded ${photoInfo.name}: $total bytes" }
                total
            } catch (e: Exception) {
                Log.error(tag = TAG, throwable = e) {
                    "Download ${photoInfo.name} failed: ${e.message}"
                }
                null
            }
        }
}

private fun compressedSizeLong(info: MtpObjectInfo): Long =
    info.compressedSize.toLong() and 0xFFFFFFFFL

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

fun formatFileSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
