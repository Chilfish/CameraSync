package dev.sebastiano.camerasync.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpPacketTest {

    // === PtpIpPacket.read() ===

    @Test
    fun `read InitCommandRequest packet`() {
        // Simulate a camera InitCommandRequest: GUID + null + hostname + null
        val guid = "abc123-def456"
        val hostname = "NikonZ30"
        val guidBytes = guid.toByteArray(Charsets.UTF_8)
        val hostnameBytes = hostname.toByteArray(Charsets.UTF_8)
        val payload = guidBytes + byteArrayOf(0) + hostnameBytes + byteArrayOf(0)

        val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(8 + payload.size) // total length
        buf.putInt(PtpIpPacketType.INIT_COMMAND_REQUEST.code)
        buf.put(payload)
        buf.flip()

        val packet = PtpIpPacket.read(buf)
        assertEquals(PtpIpPacketType.INIT_COMMAND_REQUEST, packet.type)
        assertTrue(packet.payload.contentEquals(payload))
    }

    @Test
    fun `read packet with maximum theoretical payload`() {
        // A RESPONSE packet with parameters
        val buf = ByteBuffer.allocate(8 + 12).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(20) // 8 header + 12 payload
        buf.putInt(PtpIpPacketType.RESPONSE.code)
        buf.putInt(0x2001) // OK
        buf.putInt(1) // sessionId
        buf.putInt(0) // transactionId
        buf.flip()

        val packet = PtpIpPacket.read(buf)
        assertEquals(PtpIpPacketType.RESPONSE, packet.type)
        assertEquals(12, packet.payload.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `read packet with invalid length (less than 8)`() {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(7) // invalid: less than header size
        buf.putInt(1)
        buf.flip()
        PtpIpPacket.read(buf)
    }

    // === InitCommandRequest.fromPacket() ===

    @Test
    fun `parse InitCommandRequest from packet`() {
        val guid = "test-guid-1234"
        val hostname = "Z_30_8271278"
        val guidBytes = guid.toByteArray(Charsets.UTF_8)
        val hostnameBytes = hostname.toByteArray(Charsets.UTF_8)
        val payload = guidBytes + byteArrayOf(0) + hostnameBytes + byteArrayOf(0)

        val packet = PtpIpPacket(PtpIpPacketType.INIT_COMMAND_REQUEST, payload)
        val req = InitCommandRequest.fromPacket(packet)

        assertEquals(guid, req.cameraGuid)
        assertEquals(hostname, req.cameraHostname)
    }

    @Test
    fun `parse InitCommandRequest with GUID only (no hostname)`() {
        val guid = "simple-guid"
        val payload = guid.toByteArray(Charsets.UTF_8)

        val packet = PtpIpPacket(PtpIpPacketType.INIT_COMMAND_REQUEST, payload)
        val req = InitCommandRequest.fromPacket(packet)

        assertEquals(guid, req.cameraGuid)
        assertEquals("unknown", req.cameraHostname)
    }

    @Test
    fun `parse InitCommandRequest with empty hostname`() {
        val guid = "guid-value"
        val payload = guid.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + byteArrayOf(0)

        val packet = PtpIpPacket(PtpIpPacketType.INIT_COMMAND_REQUEST, payload)
        val req = InitCommandRequest.fromPacket(packet)

        assertEquals(guid, req.cameraGuid)
        assertEquals("unknown", req.cameraHostname)
    }

    // === buildInitCommandAck ===

    @Test
    fun `build InitCommandAck round-trips correctly`() {
        val ack = buildInitCommandAck(connectionNumber = 42, phoneGuid = "phone-guid", phoneHostname = "CameraSync")
        val buf = ByteBuffer.wrap(ack).order(ByteOrder.BIG_ENDIAN)

        // Read as a packet
        val packet = PtpIpPacket.read(buf)
        assertEquals(PtpIpPacketType.INIT_COMMAND_ACK, packet.type)

        // Payload should be: 4-byte connection number + GUID + null + hostname + null
        val payload = packet.payload
        val payloadBuf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val connNumber = payloadBuf.int
        assertEquals(42, connNumber)
    }

    // === buildInitEventAck ===

    @Test
    fun `build InitEventAck is valid packet`() {
        val ack = buildInitEventAck()
        val buf = ByteBuffer.wrap(ack).order(ByteOrder.BIG_ENDIAN)

        val packet = PtpIpPacket.read(buf)
        assertEquals(PtpIpPacketType.INIT_EVENT_ACK, packet.type)
        assertEquals(0, packet.payload.size) // InitEventAck has no payload
    }

    // === PtpCommandPacket ===

    @Test
    fun `PtpCommandPacket round-trip`() {
        val cmd = PtpCommandPacket(
            operation = PtpOperation.GET_DEVICE_INFO,
            sessionId = 1,
            transactionId = 0,
        )
        val bytes = cmd.toBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val packet = PtpIpPacket.read(buf)
        assertEquals(PtpIpPacketType.COMMAND, packet.type)

        val parsed = PtpCommandPacket.fromPacket(packet)
        assertEquals(PtpOperation.GET_DEVICE_INFO, parsed.operation)
        assertEquals(1, parsed.sessionId)
        assertEquals(0, parsed.transactionId)
        assertTrue(parsed.parameters.isEmpty())
    }

    @Test
    fun `PtpCommandPacket with parameters round-trip`() {
        val cmd = PtpCommandPacket(
            operation = PtpOperation.GET_OBJECT,
            sessionId = 2,
            transactionId = 5,
            parameters = listOf(0x1001, 0x2002),
        )
        val bytes = cmd.toBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val packet = PtpIpPacket.read(buf)
        val parsed = PtpCommandPacket.fromPacket(packet)

        assertEquals(PtpOperation.GET_OBJECT, parsed.operation)
        assertEquals(2, parsed.sessionId)
        assertEquals(5, parsed.transactionId)
        assertEquals(listOf(0x1001, 0x2002), parsed.parameters)
    }

    // === PtpResponsePacket ===

    @Test
    fun `parse OK response`() {
        val payload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        payload.putInt(0x2001) // OK
        payload.putInt(1) // sessionId
        payload.putInt(3) // transactionId
        val packet = PtpIpPacket(PtpIpPacketType.RESPONSE, payload.array())

        val resp = PtpResponsePacket.fromPacket(packet)
        assertTrue(resp.isOk)
        assertEquals(0x2001, resp.responseCode)
        assertEquals(1, resp.sessionId)
        assertEquals(3, resp.transactionId)
    }

    @Test
    fun `parse error response`() {
        val payload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        payload.putInt(0x2003) // SESSION_NOT_OPEN
        payload.putInt(0)
        payload.putInt(1)
        val packet = PtpIpPacket(PtpIpPacketType.RESPONSE, payload.array())

        val resp = PtpResponsePacket.fromPacket(packet)
        assertEquals(false, resp.isOk)
        assertEquals(0x2003, resp.responseCode)
    }

    // === PtpEventPacket ===

    @Test
    fun `parse ObjectAdded event`() {
        val payload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        payload.putInt(0x4002) // ObjectAdded
        payload.putInt(1) // sessionId
        payload.putInt(0) // transactionId
        payload.putInt(0x42) // object handle
        val packet = PtpIpPacket(PtpIpPacketType.EVENT, payload.array())

        val event = PtpEventPacket.fromPacket(packet)
        assertEquals(0x4002, event.eventCode)
        assertEquals(1, event.sessionId)
        assertEquals(listOf(0x42), event.parameters)
        assertNotNull(PtpEventCode.fromCode(event.eventCode))
    }

    // === hex dump utility ===

    @Test
    fun `hex dump short byte array`() {
        val bytes = byteArrayOf(0x12.toByte(), 0x34, 0x56.toByte(), 0x78.toByte())
        val dump = bytes.toHexDump()
        assertTrue(dump.contains("[4 bytes]"))
        assertTrue(dump.contains("12 34 56 78"))
    }

    @Test
    fun `hex dump truncates large arrays`() {
        val bytes = ByteArray(300) { it.toByte() }
        val dump = bytes.toHexDump()
        assertTrue(dump.contains("300 bytes"))
        assertTrue(dump.contains("... (44 more bytes)"))
    }

    // === enum lookups ===

    @Test
    fun `PtpIpPacketType fromCode covers all types`() {
        assertEquals(PtpIpPacketType.INIT_COMMAND_REQUEST, PtpIpPacketType.fromCode(1))
        assertEquals(PtpIpPacketType.COMMAND, PtpIpPacketType.fromCode(6))
        assertEquals(PtpIpPacketType.EVENT, PtpIpPacketType.fromCode(9))
        assertEquals(null, PtpIpPacketType.fromCode(999))
    }

    @Test
    fun `PtpOperation fromCode covers standard ops`() {
        assertEquals(PtpOperation.GET_DEVICE_INFO, PtpOperation.fromCode(0x1001))
        assertEquals(PtpOperation.OPEN_SESSION, PtpOperation.fromCode(0x1002))
        assertEquals(PtpOperation.GET_OBJECT, PtpOperation.fromCode(0x1009))
        assertEquals(PtpOperation.GET_PARTIAL_OBJECT, PtpOperation.fromCode(0x101B))
        assertEquals(null, PtpOperation.fromCode(0xFFFF))
    }
}
