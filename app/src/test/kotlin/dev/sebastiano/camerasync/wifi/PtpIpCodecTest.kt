@file:Suppress("MagicNumber")

package dev.sebastiano.camerasync.wifi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip tests for the PTP/IP transfer layer (POC, ADR-011). */
class PtpIpCodecTest {

    private fun roundTrip(write: (ByteArrayOutputStream) -> Unit): PtpIpPacket {
        val out = ByteArrayOutputStream()
        write(out)
        return PtpIpCodec.readPacket(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `init command request round-trips guid and host name`() {
        val guid = ByteArray(16) { it.toByte() }
        val packet =
            roundTrip { PtpIpCodec.writeInitCommandRequest(it, guid, "CameraSync") }
                as PtpIpPacket.InitCommandRequest
        assertTrue(guid.contentEquals(packet.guid))
        assertEquals("CameraSync", packet.hostName)
    }

    @Test
    fun `init command ack round-trips session and camera name`() {
        val packet =
            roundTrip { PtpIpCodec.writeInitCommandAck(it, 5, "Z30") } as PtpIpPacket.InitCommandAck
        assertEquals(5, packet.sessionId)
        assertEquals("Z30", packet.cameraName)
    }

    @Test
    fun `command request round-trips opcode, transaction and params`() {
        val packet =
            roundTrip {
                PtpIpCodec.writeCommandRequest(it, PtpIp.OP_GET_OBJECT_HANDLES, 7, listOf(1, 0, -1))
            }
                as PtpIpPacket.CommandRequest
        assertEquals(PtpIp.OP_GET_OBJECT_HANDLES, packet.opCode)
        assertEquals(7, packet.transactionId)
        assertEquals(listOf(1, 0, -1), packet.params)
    }

    @Test
    fun `command response round-trips code and params`() {
        val packet =
            roundTrip { PtpIpCodec.writeCommandResponse(it, PtpIp.RSP_OK, 3, listOf(42)) }
                as PtpIpPacket.CommandResponse
        assertEquals(PtpIp.RSP_OK, packet.code)
        assertEquals(3, packet.transactionId)
        assertEquals(listOf(42), packet.params)
    }

    @Test
    fun `event round-trips the event code`() {
        val packet =
            roundTrip { PtpIpCodec.writeEvent(it, PtpIp.EVENT_OBJECT_ADDED, 0) }
                as PtpIpPacket.Event
        assertEquals(PtpIp.EVENT_OBJECT_ADDED, packet.code)
    }

    @Test
    fun `data phase packets round-trip their payload`() {
        val start = roundTrip { PtpIpCodec.writeStartData(it, 9, 3) } as PtpIpPacket.StartData
        assertEquals(9, start.transactionId)
        assertEquals(3, start.totalSize)

        val chunk =
            roundTrip { PtpIpCodec.writeData(it, 9, byteArrayOf(1, 2, 3)) } as PtpIpPacket.DataChunk
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(chunk.bytes))
    }

    @Test
    fun `keep-alive packets are recognised`() {
        assertTrue(roundTrip { PtpIpCodec.writePing(it) } is PtpIpPacket.Ping)
        assertTrue(roundTrip { PtpIpCodec.writePong(it) } is PtpIpPacket.Pong)
    }
}
