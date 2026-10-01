package dev.sebastiano.camerasync.wifi

import com.juul.khronicle.Log
import dev.sebastiano.camerasync.camera.CameraInfo
import dev.sebastiano.camerasync.camera.CameraSource
import dev.sebastiano.camerasync.camera.FolderInfo
import dev.sebastiano.camerasync.camera.PhotoInfo
import dev.sebastiano.camerasync.camera.StorageInfo
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "WifiCameraSource"
private const val MAX_FOLDER_DEPTH = 8
private const val GET_OBJECT_FULL = 0

/**
 * `CameraSource` over WiFi/PTP-IP — the second transport for the POC (ADR-011).
 *
 * Exercises the seam extracted in Phase 0: the browsing, thumbnail and transfer code above
 * [CameraSource] can drive this exactly like
 * [UsbCameraSource][dev.sebastiano.camerasync.usb.UsbCameraSource].
 *
 * Not wired into the app; see `docs/planning/wireless-transfer.md` for scope and limitations.
 */
class WifiCameraSource(private val client: PtpIpClient) : CameraSource {

    override var cameraInfo: CameraInfo? = null
        private set

    override var storages: List<StorageInfo> = emptyList()
        private set

    /** Connects and reads camera info + storages. Returns false on failure. */
    @Suppress(
        "TooGenericExceptionCaught"
    ) // socket + protocol layers throw a mix of unchecked errors
    fun open(): Boolean =
        try {
            client.connect()
            cameraInfo = readCameraInfo()
            storages = readStorages()
            true
        } catch (e: Exception) {
            Log.error(tag = TAG, throwable = e) { "WiFi connect failed" }
            false
        }

    override fun listPhotos(
        storageId: Int,
        accumulator: MutableList<PhotoInfo>?,
        onProgress: ((scanned: Int) -> Unit)?,
        onDiagnostic: (String) -> Unit,
    ): List<PhotoInfo> {
        val photos = accumulator ?: mutableListOf()
        val queue = ArrayDeque<Pair<Int, Int>>() // parent handle → depth
        queue.add(PtpIp.ROOT_PARENT to 0)

        while (queue.isNotEmpty()) {
            val (parent, depth) = queue.removeFirst()
            val handles = getObjectHandles(storageId, parent)
            onDiagnostic("  parent=$parent ⇒ handles ${handles.size}")
            for (handle in handles) {
                val info = getObjectInfo(handle) ?: continue
                if (!info.isFolder) {
                    photos.add(toPhoto(info, handle, storageId, parent))
                    onProgress?.invoke(photos.size)
                } else if (depth < MAX_FOLDER_DEPTH) {
                    queue.add(handle to (depth + 1))
                }
            }
        }
        photos.sortByDescending { it.dateModified }
        return photos
    }

    override fun listFolders(storageId: Int, parentHandle: Int): List<FolderInfo> =
        getObjectHandles(storageId, parentHandle).mapNotNull { handle ->
            getObjectInfo(handle)
                ?.takeIf { it.isFolder }
                ?.let { FolderInfo(handle, it.fileName, parsePtpDate(it.captureDate)) }
        }

    override fun listPhotosInFolder(storageId: Int, parentHandle: Int): List<PhotoInfo> =
        getObjectHandles(storageId, parentHandle)
            .mapNotNull { handle ->
                getObjectInfo(handle)
                    ?.takeIf { !it.isFolder }
                    ?.let { toPhoto(it, handle, storageId, parentHandle) }
            }
            .sortedByDescending { it.dateModified }

    override fun getThumbnail(handle: Int): ByteArray? {
        val result = client.execute(PtpIp.OP_GET_THUMB, intArrayOf(handle), expectData = true)
        return if (result.isOk) result.data else null
    }

    override suspend fun download(photo: PhotoInfo, outputStream: OutputStream): Long? {
        val data = getObject(photo.handle) ?: return null
        outputStream.write(data)
        return data.size.toLong()
    }

    override suspend fun downloadToFile(handle: Int, destFile: File): Boolean {
        val data = getObject(handle) ?: return false
        destFile.parentFile?.mkdirs()
        destFile.writeBytes(data)
        return true
    }

    override fun delete(handle: Int): Boolean =
        client.execute(PtpIp.OP_DELETE_OBJECT, intArrayOf(handle)).isOk

    override fun close() {
        client.close()
    }

    private fun readCameraInfo(): CameraInfo? {
        val data = client.execute(PtpIp.OP_GET_DEVICE_INFO, expectData = true).data ?: return null
        val info = PtpDatasets.parseDeviceInfo(data)
        return CameraInfo(
            manufacturer = info.manufacturer,
            model = info.model,
            serialNumber = info.serialNumber,
            deviceVersion = info.deviceVersion,
            supportedOps = emptyList(),
            supportedEvents = emptyList(),
            vendorExtension = null,
        )
    }

    private fun readStorages(): List<StorageInfo> {
        val ids =
            client.execute(PtpIp.OP_GET_STORAGE_IDS, expectData = true).data?.let {
                PtpDatasets.parseU32Array(it)
            } ?: return emptyList()

        return ids.mapNotNull { id ->
            val data =
                client.execute(PtpIp.OP_GET_STORAGE_INFO, intArrayOf(id), expectData = true).data
                    ?: return@mapNotNull null
            val info = PtpDatasets.parseStorageInfo(data)
            StorageInfo(
                id = id,
                description = info.description,
                maxCapacity = info.maxCapacity,
                freeSpace = info.freeSpace,
            )
        }
    }

    private fun getObjectHandles(storageId: Int, parent: Int): List<Int> {
        val result =
            client.execute(
                PtpIp.OP_GET_OBJECT_HANDLES,
                intArrayOf(storageId, PtpIp.ALL_FORMATS, parent),
                expectData = true,
            )
        return if (result.isOk) result.data?.let { PtpDatasets.parseU32Array(it) } ?: emptyList()
        else emptyList()
    }

    private fun getObjectInfo(handle: Int): PtpObjectInfo? {
        val data =
            client.execute(PtpIp.OP_GET_OBJECT_INFO, intArrayOf(handle), expectData = true).data
        return if (data != null && data.isNotEmpty()) PtpDatasets.parseObjectInfo(data) else null
    }

    private fun getObject(handle: Int): ByteArray? {
        val result =
            client.execute(
                PtpIp.OP_GET_OBJECT,
                intArrayOf(handle, GET_OBJECT_FULL),
                expectData = true,
            )
        return if (result.isOk) result.data else null
    }

    private fun toPhoto(info: PtpObjectInfo, handle: Int, storageId: Int, parent: Int): PhotoInfo =
        PhotoInfo(
            handle = handle,
            storageId = storageId,
            name = info.fileName,
            size = info.compressedSize,
            dateModified = parsePtpDate(info.captureDate),
            formatName = PtpIp.formatName(info.format),
            thumbPixWidth = info.thumbPixWidth,
            thumbPixHeight = info.thumbPixHeight,
            imagePixWidth = info.imagePixWidth,
            imagePixHeight = info.imagePixHeight,
            parentHandle = parent,
        )

    private fun parsePtpDate(value: String): Long =
        runCatching {
                LocalDateTime.parse(value, PTP_DATE_FORMAT)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
            .getOrDefault(0L)

    private companion object {
        val PTP_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US)
    }
}
