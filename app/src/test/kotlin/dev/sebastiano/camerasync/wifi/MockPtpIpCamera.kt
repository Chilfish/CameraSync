@file:Suppress("MagicNumber")

package dev.sebastiano.camerasync.wifi

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

internal const val MOCK_STORAGE_ID = 1
internal const val MOCK_ROOT_FOLDER = 100
private const val MOCK_SESSION_ID = 1

internal data class MockObject(
    val handle: Int,
    val parent: Int,
    val name: String,
    val format: Int,
    val size: Int,
    val content: ByteArray = ByteArray(0),
    val thumbnail: ByteArray = ByteArray(0),
    val captureDate: String = "20260901T101500",
    val imageWidth: Int = 5568,
    val imageHeight: Int = 3712,
    val thumbWidth: Int = 160,
    val thumbHeight: Int = 120,
)

/**
 * In-process mock Nikon camera speaking PTP/IP over localhost, so the WiFi transport POC can be
 * exercised end-to-end without hardware (ADR-011). Two channels are served: the command/data
 * connection we drive, and the event connection the camera pushes on.
 */
internal class MockPtpIpCamera(
    private val objects: List<MockObject> = defaultObjects(),
    private val cameraModel: String = "Z30",
) : Closeable {

    private val server = ServerSocket(0)
    val port: Int = server.localPort

    val deletedHandles = CopyOnWriteArrayList<Int>()

    @Volatile private var eventOut: OutputStream? = null

    init {
        Thread({ serve() }, "mock-ptpip").apply {
            isDaemon = true
            start()
        }
    }

    /** Pushes an event (e.g. `ObjectAdded`) to whoever is on the event channel. */
    fun pushEvent(code: Int) {
        eventOut?.let { PtpIpCodec.writeEvent(it, code, 0) }
    }

    override fun close() {
        runCatching { server.close() }
    }

    private fun serve() {
        runCatching {
            val command = server.accept()
            val commandIn = command.getInputStream()
            val commandOut = command.getOutputStream()

            PtpIpCodec.readPacket(commandIn) // Init_Command_Request
            PtpIpCodec.writeInitCommandAck(commandOut, MOCK_SESSION_ID, cameraModel)

            val event = server.accept()
            val eventIn = event.getInputStream()
            val out = event.getOutputStream()
            eventOut = out
            PtpIpCodec.readPacket(eventIn) // Init_Event_Request
            PtpIpCodec.writeInitEventAck(out)

            // Drain the event channel (the client's keep-alive pings) so its buffer never fills.
            Thread({ runCatching { while (true) PtpIpCodec.readPacket(eventIn) } }, "mock-ptpip-ev")
                .apply {
                    isDaemon = true
                    start()
                }

            while (true) {
                val request =
                    PtpIpCodec.readPacket(commandIn) as? PtpIpPacket.CommandRequest ?: continue
                handle(commandOut, request)
            }
        }
    }

    private fun handle(out: OutputStream, request: PtpIpPacket.CommandRequest) {
        when (request.opCode) {
            PtpIp.OP_OPEN_SESSION,
            PtpIp.OP_CLOSE_SESSION ->
                PtpIpCodec.writeCommandResponse(out, PtpIp.RSP_OK, request.transactionId)
            PtpIp.OP_GET_DEVICE_INFO -> respondWithData(out, request, deviceInfo(cameraModel))
            PtpIp.OP_GET_STORAGE_IDS ->
                respondWithData(out, request, u32Array(listOf(MOCK_STORAGE_ID)))
            PtpIp.OP_GET_STORAGE_INFO -> respondWithData(out, request, storageInfo())
            PtpIp.OP_GET_OBJECT_HANDLES -> {
                val parent = request.params.getOrElse(2) { PtpIp.ROOT_PARENT }
                val handles = objects.filter { it.parent == parent }.map { it.handle }
                respondWithData(out, request, u32Array(handles))
            }
            PtpIp.OP_GET_OBJECT_INFO -> {
                val obj = objects.find { it.handle == request.params.firstOrNull() }
                if (obj == null) respondError(out, request)
                else respondWithData(out, request, objectInfo(obj))
            }
            PtpIp.OP_GET_THUMB -> {
                val obj = objects.find { it.handle == request.params.firstOrNull() }
                if (obj == null || obj.thumbnail.isEmpty()) respondError(out, request)
                else respondWithData(out, request, obj.thumbnail)
            }
            PtpIp.OP_GET_OBJECT -> {
                val obj = objects.find { it.handle == request.params.firstOrNull() }
                if (obj == null) respondError(out, request)
                else respondWithData(out, request, obj.content)
            }
            PtpIp.OP_DELETE_OBJECT -> {
                request.params.firstOrNull()?.let { deletedHandles.add(it) }
                PtpIpCodec.writeCommandResponse(out, PtpIp.RSP_OK, request.transactionId)
            }
            else -> respondError(out, request)
        }
    }

    private fun respondWithData(
        out: OutputStream,
        request: PtpIpPacket.CommandRequest,
        data: ByteArray,
    ) {
        PtpIpCodec.writeCommandResponse(out, PtpIp.RSP_OK, request.transactionId)
        PtpIpCodec.writeStartData(out, request.transactionId, data.size)
        PtpIpCodec.writeData(out, request.transactionId, data)
        PtpIpCodec.writeEndData(out, request.transactionId)
    }

    private fun respondError(out: OutputStream, request: PtpIpPacket.CommandRequest) {
        PtpIpCodec.writeCommandResponse(out, PtpIp.RSP_GENERAL_ERROR, request.transactionId)
    }

    private companion object {
        fun defaultObjects(): List<MockObject> =
            listOf(
                MockObject(
                    handle = MOCK_ROOT_FOLDER,
                    parent = PtpIp.ROOT_PARENT,
                    name = "100NIKON",
                    format = PtpIp.FORMAT_ASSOCIATION,
                    size = 0,
                ),
                MockObject(
                    handle = 1,
                    parent = MOCK_ROOT_FOLDER,
                    name = "DSC_0001.JPG",
                    format = PtpIp.FORMAT_JPEG,
                    size = 4 * 1024 * 1024,
                    content = ByteArray(16) { 1 },
                    thumbnail = ByteArray(8) { 2 },
                ),
                MockObject(
                    handle = 2,
                    parent = MOCK_ROOT_FOLDER,
                    name = "DSC_0002.NEF",
                    format = PtpIp.FORMAT_NEF,
                    size = 24 * 1024 * 1024,
                    content = ByteArray(32) { 3 },
                    thumbnail = ByteArray(8) { 4 },
                ),
            )

        fun u32Array(values: List<Int>): ByteArray {
            val out = ByteArrayOutputStream()
            writeU32(out, values.size)
            values.forEach { writeU32(out, it) }
            return out.toByteArray()
        }

        fun deviceInfo(model: String): ByteArray {
            val out = ByteArrayOutputStream()
            writeU16(out, 100) // StandardVersion
            writeU32(out, 0) // VendorExtensionID
            writeU16(out, 0) // VendorExtensionVersion
            writePtpString(out, "") // VendorExtensionDesc
            writeU16(out, 0) // FunctionalMode
            writeU16Array(out, listOf(PtpIp.OP_GET_DEVICE_INFO, PtpIp.OP_GET_OBJECT))
            writeU16Array(out, listOf(PtpIp.EVENT_OBJECT_ADDED))
            writeU16Array(out, emptyList())
            writeU16Array(out, emptyList())
            writeU16Array(out, listOf(PtpIp.FORMAT_JPEG))
            writePtpString(out, "NIKON")
            writePtpString(out, model)
            writePtpString(out, "1.00")
            writePtpString(out, "6001234")
            return out.toByteArray()
        }

        fun storageInfo(): ByteArray {
            val out = ByteArrayOutputStream()
            writeU16(out, 3) // StorageType
            writeU16(out, 2) // FilesystemType
            writeU16(out, 0) // AccessCapability
            writeU64(out, 128_000_000_000L) // MaxCapacity
            writeU64(out, 64_000_000_000L) // FreeSpaceInBytes
            writeU32(out, 1000) // FreeSpaceInImages
            writePtpString(out, "SD Card")
            writePtpString(out, "NIKON SD")
            return out.toByteArray()
        }

        fun objectInfo(obj: MockObject): ByteArray {
            val out = ByteArrayOutputStream()
            writeU32(out, MOCK_STORAGE_ID)
            writeU16(out, obj.format)
            writeU16(out, 0) // ProtectionStatus
            writeU32(out, obj.size)
            writeU16(out, PtpIp.FORMAT_JPEG) // ThumbFormat
            writeU32(out, obj.thumbnail.size)
            writeU32(out, obj.thumbWidth)
            writeU32(out, obj.thumbHeight)
            writeU32(out, obj.imageWidth)
            writeU32(out, obj.imageHeight)
            writeU32(out, obj.parent)
            writeU16(out, 0) // AssociationType
            writeU32(out, 0) // AssociationDesc
            writeU32(out, 0) // SequenceNumber
            writePtpString(out, obj.name)
            writePtpString(out, obj.captureDate)
            writePtpString(out, obj.captureDate)
            writePtpString(out, "")
            return out.toByteArray()
        }

        fun writeU16(out: OutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        fun writeU32(out: OutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        fun writeU64(out: OutputStream, value: Long) {
            repeat(8) { i -> out.write(((value shr (8 * i)) and 0xFF).toInt()) }
        }

        fun writeU16Array(out: OutputStream, values: List<Int>) {
            writeU32(out, values.size)
            values.forEach { writeU16(out, it) }
        }

        fun writePtpString(out: OutputStream, value: String) {
            out.write(value.length + 1)
            value.forEach { writeU16(out, it.code) }
            writeU16(out, 0)
        }
    }
}
