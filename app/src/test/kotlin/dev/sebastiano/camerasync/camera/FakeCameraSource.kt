package dev.sebastiano.camerasync.camera

import java.io.File
import java.io.OutputStream

/**
 * In-memory [CameraSource] for tests (Fakes over Mocks). Download/delete behaviour is configurable
 * and every request is recorded so tests can assert on interactions.
 */
class FakeCameraSource(
    override val cameraInfo: CameraInfo? = null,
    override val storages: List<StorageInfo> = emptyList(),
) : CameraSource {

    /** Bytes returned by [download]; when null the photo's own size is used. */
    var downloadBytes: Long? = null

    /** When set, [download] throws this instead of succeeding. */
    var downloadError: Throwable? = null

    val downloadedHandles = mutableListOf<Int>()

    /** Result of [delete] for a given handle. */
    var deleteResult: (Int) -> Boolean = { true }

    val deletedHandles = mutableListOf<Int>()

    var closed = false
        private set

    override fun listPhotos(
        storageId: Int,
        accumulator: MutableList<PhotoInfo>?,
        onProgress: ((scanned: Int) -> Unit)?,
        onDiagnostic: (String) -> Unit,
    ): List<PhotoInfo> = accumulator ?: mutableListOf()

    override fun listFolders(storageId: Int, parentHandle: Int): List<FolderInfo> = emptyList()

    override fun listPhotosInFolder(storageId: Int, parentHandle: Int): List<PhotoInfo> =
        emptyList()

    override fun getThumbnail(handle: Int): ByteArray? = null

    override suspend fun download(photo: PhotoInfo, outputStream: OutputStream): Long? {
        downloadError?.let { throw it }
        downloadedHandles.add(photo.handle)
        return downloadBytes ?: photo.size
    }

    override suspend fun downloadToFile(handle: Int, destFile: File): Boolean = false

    override fun delete(handle: Int): Boolean {
        deletedHandles.add(handle)
        return deleteResult(handle)
    }

    override fun close() {
        closed = true
    }
}
