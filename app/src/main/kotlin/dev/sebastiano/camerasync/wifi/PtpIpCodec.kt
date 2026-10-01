@file:Suppress("MagicNumber", "TooManyFunctions")

package dev.sebastiano.camerasync.wifi

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoded PTP/IP packets. The codec is symmetric (it encodes and decodes both directions) so the
 * same layer can back an in-process mock camera in tests.
 */
sealed interface PtpIpPacket {
    // ── Initiator → responder (requests) ────────────────────────────────────
    data class InitCommandRequest(val guid: ByteArray, val hostName: String) : PtpIpPacket

    data class InitEventRequest(val sessionId: Int) : PtpIpPacket

    data class CommandRequest(val opCode: Int, val transactionId: Int, val params: List<Int>) :
        PtpIpPacket

    // ── Responder → initiator (responses / events) ──────────────────────────
    data class InitCommandAck(val sessionId: Int, val cameraName: String) : PtpIpPacket

    data object InitEventAck : PtpIpPacket

    data class InitFail(val errorCode: Int) : PtpIpPacket

    data class CommandResponse(val code: Int, val transactionId: Int, val params: List<Int>) :
        PtpIpPacket

    data class Event(val code: Int, val transactionId: Int) : PtpIpPacket

    data class StartData(val transactionId: Int, val totalSize: Int) : PtpIpPacket

    data class DataChunk(val transactionId: Int, val bytes: ByteArray) : PtpIpPacket

    data class EndData(val transactionId: Int, val bytes: ByteArray) : PtpIpPacket

    data object Ping : PtpIpPacket

    data object Pong : PtpIpPacket
}

/**
 * Encoder/decoder for the PTP/IP transfer layer.
 *
 * Every packet is `[u32 length][u32 type][payload]` in little-endian, where `length` covers the
 * whole packet (header included). Reverse-engineered reference: `gphoto.org/doc/ptpip.php` and
 * `lst.de/~mm/cameras/p2/ptpip.html`.
 */
object PtpIpCodec {
    private const val INT_SIZE = 4
    private const val SHORT_SIZE = 2

    /** Fixed part of a `Cmd_Request` payload: unknown(4) + opcode(2) + transactionId(4). */
    private const val COMMAND_REQUEST_FIXED = 10

    // ── Requests (initiator side) ───────────────────────────────────────────

    fun writeInitCommandRequest(out: OutputStream, guid: ByteArray, hostName: String) {
        val name = hostName.toUtf16LeWithNul()
        val payload = ByteBuffer.allocate(guid.size + name.size).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(guid)
        payload.put(name)
        writePacket(out, PtpIp.TYPE_INIT_COMMAND_REQUEST, payload.array())
    }

    fun writeInitEventRequest(out: OutputStream, sessionId: Int) {
        writePacket(out, PtpIp.TYPE_INIT_EVENT_REQUEST, intPayload(sessionId))
    }

    fun writeCommandRequest(out: OutputStream, opCode: Int, transactionId: Int, params: List<Int>) {
        val payload =
            ByteBuffer.allocate(COMMAND_REQUEST_FIXED + params.size * INT_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(1) // unknown field; the reference sets it to 1
        payload.putShort(opCode.toShort())
        payload.putInt(transactionId)
        params.forEach { payload.putInt(it) }
        writePacket(out, PtpIp.TYPE_COMMAND_REQUEST, payload.array())
    }

    fun writePing(out: OutputStream) {
        writePacket(out, PtpIp.TYPE_PING, ByteArray(0))
    }

    // ── Responses (responder side) ──────────────────────────────────────────

    fun writeInitCommandAck(out: OutputStream, sessionId: Int, cameraName: String) {
        val name = cameraName.toUtf16LeWithNul()
        val payload =
            ByteBuffer.allocate(INT_SIZE + PtpIp.GUID_SIZE + name.size)
                .order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(sessionId)
        payload.put(ByteArray(PtpIp.GUID_SIZE))
        payload.put(name)
        writePacket(out, PtpIp.TYPE_INIT_COMMAND_ACK, payload.array())
    }

    fun writeInitEventAck(out: OutputStream) {
        writePacket(out, PtpIp.TYPE_INIT_EVENT_ACK, ByteArray(0))
    }

    fun writeCommandResponse(
        out: OutputStream,
        code: Int,
        transactionId: Int,
        params: List<Int> = emptyList(),
    ) {
        val payload =
            ByteBuffer.allocate(SHORT_SIZE + INT_SIZE + params.size * INT_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(code.toShort())
        payload.putInt(transactionId)
        params.forEach { payload.putInt(it) }
        writePacket(out, PtpIp.TYPE_COMMAND_RESPONSE, payload.array())
    }

    fun writeEvent(out: OutputStream, code: Int, transactionId: Int) {
        val payload = ByteBuffer.allocate(SHORT_SIZE + INT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(code.toShort())
        payload.putInt(transactionId)
        writePacket(out, PtpIp.TYPE_EVENT, payload.array())
    }

    fun writeStartData(out: OutputStream, transactionId: Int, totalSize: Int) {
        val payload = ByteBuffer.allocate(INT_SIZE * 2).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(transactionId)
        payload.putInt(totalSize)
        writePacket(out, PtpIp.TYPE_START_DATA, payload.array())
    }

    fun writeData(out: OutputStream, transactionId: Int, bytes: ByteArray) {
        writePacket(out, PtpIp.TYPE_DATA, dataPayload(transactionId, bytes))
    }

    fun writeEndData(out: OutputStream, transactionId: Int, bytes: ByteArray = ByteArray(0)) {
        writePacket(out, PtpIp.TYPE_END_DATA, dataPayload(transactionId, bytes))
    }

    fun writePong(out: OutputStream) {
        writePacket(out, PtpIp.TYPE_PONG, ByteArray(0))
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    fun readPacket(input: InputStream): PtpIpPacket {
        val header = readFully(input, PtpIp.HEADER_SIZE)
        val length = leInt(header, 0)
        if (length < PtpIp.HEADER_SIZE) throw IOException("Bad PTP/IP packet length: $length")
        val type = leInt(header, INT_SIZE)
        val payload = readFully(input, length - PtpIp.HEADER_SIZE)
        return parse(type, payload)
    }

    private fun parse(type: Int, payload: ByteArray): PtpIpPacket =
        when (type) {
            PtpIp.TYPE_INIT_COMMAND_REQUEST ->
                PtpIpPacket.InitCommandRequest(
                    guid = payload.copyOfRange(0, PtpIp.GUID_SIZE),
                    hostName = utf16Le(payload, PtpIp.GUID_SIZE),
                )
            PtpIp.TYPE_INIT_COMMAND_ACK ->
                PtpIpPacket.InitCommandAck(
                    sessionId = leInt(payload, 0),
                    cameraName = utf16Le(payload, PtpIp.GUID_SIZE + INT_SIZE),
                )
            PtpIp.TYPE_INIT_EVENT_REQUEST -> PtpIpPacket.InitEventRequest(leInt(payload, 0))
            PtpIp.TYPE_INIT_EVENT_ACK -> PtpIpPacket.InitEventAck
            PtpIp.TYPE_INIT_FAIL -> PtpIpPacket.InitFail(leInt(payload, 0))
            PtpIp.TYPE_COMMAND_REQUEST ->
                PtpIpPacket.CommandRequest(
                    opCode = leShort(payload, INT_SIZE),
                    transactionId = leInt(payload, INT_SIZE + SHORT_SIZE),
                    params = leInts(payload, COMMAND_REQUEST_FIXED),
                )
            PtpIp.TYPE_COMMAND_RESPONSE ->
                PtpIpPacket.CommandResponse(
                    code = leShort(payload, 0),
                    transactionId = leInt(payload, SHORT_SIZE),
                    params = leInts(payload, SHORT_SIZE + INT_SIZE),
                )
            PtpIp.TYPE_EVENT ->
                PtpIpPacket.Event(
                    code = leShort(payload, 0),
                    transactionId = leInt(payload, SHORT_SIZE),
                )
            PtpIp.TYPE_START_DATA ->
                PtpIpPacket.StartData(
                    transactionId = leInt(payload, 0),
                    totalSize = leInt(payload, INT_SIZE),
                )
            PtpIp.TYPE_DATA ->
                PtpIpPacket.DataChunk(
                    leInt(payload, 0),
                    payload.copyOfRange(INT_SIZE, payload.size),
                )
            PtpIp.TYPE_END_DATA ->
                PtpIpPacket.EndData(leInt(payload, 0), payload.copyOfRange(INT_SIZE, payload.size))
            PtpIp.TYPE_PING -> PtpIpPacket.Ping
            PtpIp.TYPE_PONG -> PtpIpPacket.Pong
            else -> throw IOException("Unknown PTP/IP packet type: $type")
        }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        val buffer =
            ByteBuffer.allocate(PtpIp.HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PtpIp.HEADER_SIZE + payload.size)
        buffer.putInt(type)
        buffer.put(payload)
        out.write(buffer.array())
        out.flush()
    }

    private fun dataPayload(transactionId: Int, bytes: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(INT_SIZE + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(transactionId)
        buffer.put(bytes)
        return buffer.array()
    }

    private fun intPayload(value: Int): ByteArray =
        ByteBuffer.allocate(INT_SIZE).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun leInts(bytes: ByteArray, offset: Int): List<Int> {
        val values = mutableListOf<Int>()
        var i = offset
        while (i + INT_SIZE <= bytes.size) {
            values.add(leInt(bytes, i))
            i += INT_SIZE
        }
        return values
    }

    private fun utf16Le(bytes: ByteArray, offset: Int): String {
        val text = StringBuilder()
        var i = offset
        while (i + SHORT_SIZE <= bytes.size) {
            val value = leShort(bytes, i)
            if (value == 0) break
            text.append(value.toChar())
            i += SHORT_SIZE
        }
        return text.toString()
    }

    private fun String.toUtf16LeWithNul(): ByteArray {
        val buffer = ByteBuffer.allocate((length + 1) * SHORT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        forEach { buffer.putShort(it.code.toShort()) }
        buffer.putShort(0)
        return buffer.array()
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        if (size == 0) return ByteArray(0)
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) throw EOFException("Stream closed after $read/$size bytes")
            read += n
        }
        return buffer
    }
}
