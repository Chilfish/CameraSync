package dev.sebastiano.camerasync.wifi

import com.juul.khronicle.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "PtpIpClient"
private const val DEFAULT_HOST_NAME = "CameraSync"
private const val CONNECT_TIMEOUT_MS = 5_000
private const val KEEP_ALIVE_MS = 8_000L

/** Result of one PTP operation: response code, response parameters and (optional) data payload. */
data class PtpIpResult(val code: Int, val params: List<Int>, val data: ByteArray?) {
    val isOk: Boolean
        get() = code == PtpIp.RSP_OK
}

/**
 * A PTP/IP session (WiFi transport POC, ADR-011).
 *
 * The protocol uses two TCP connections: a command/data channel that we drive, and an event channel
 * the camera pushes notifications on. The camera drops an idle session after ~10s, so the event
 * channel is kept alive with a periodic `Ping`.
 *
 * Blocking I/O by design — callers run it on an IO dispatcher, matching
 * [CameraSource][dev.sebastiano.camerasync.camera.CameraSource]'s synchronous shape.
 */
class PtpIpClient(
    private val host: String,
    private val port: Int = PtpIp.PORT,
    private val hostName: String = DEFAULT_HOST_NAME,
    private val socketFactory: (InetSocketAddress) -> Socket = { address ->
        Socket().apply {
            connect(address, CONNECT_TIMEOUT_MS)
            tcpNoDelay = true
        }
    },
) : Closeable {

    /** Camera name reported in the init handshake. */
    var cameraName: String = ""
        private set

    private var commandSocket: Socket? = null
    private var commandIn: InputStream? = null
    private var commandOut: java.io.OutputStream? = null
    private var eventSocket: Socket? = null

    private var sessionId = 0
    private var transactionId = 0
    private val commandLock = Any()
    private val events = LinkedBlockingQueue<Int>()

    @Volatile private var running = false

    /** Opens both channels and completes the PTP/IP handshake. */
    fun connect() {
        val command = socketFactory(InetSocketAddress(host, port))
        commandSocket = command
        val input = command.getInputStream()
        val output = command.getOutputStream()
        commandIn = input
        commandOut = output

        val guid = ByteArray(PtpIp.GUID_SIZE).also { SecureRandom().nextBytes(it) }
        PtpIpCodec.writeInitCommandRequest(output, guid, hostName)
        when (val ack = PtpIpCodec.readPacket(input)) {
            is PtpIpPacket.InitCommandAck -> {
                sessionId = ack.sessionId
                cameraName = ack.cameraName
            }
            is PtpIpPacket.InitFail -> throw IOException("PTP/IP init failed (${ack.errorCode})")
            else -> throw IOException("Unexpected init response: $ack")
        }

        val event = socketFactory(InetSocketAddress(host, port))
        eventSocket = event
        PtpIpCodec.writeInitEventRequest(event.getOutputStream(), sessionId)
        val eventAck = PtpIpCodec.readPacket(event.getInputStream())
        if (eventAck !is PtpIpPacket.InitEventAck) {
            throw IOException("Event channel init failed: $eventAck")
        }

        running = true
        startEventChannel(event)

        // Best-effort: some bodies need an explicit OpenSession, others accept commands directly.
        try {
            execute(PtpIp.OP_OPEN_SESSION, intArrayOf(sessionId))
        } catch (e: IOException) {
            Log.warn(tag = TAG, throwable = e) { "OpenSession failed" }
        }
    }

    /**
     * Sends one operation and reads its response. When [expectData] is set and the camera answers
     * OK, the following data phase (`Start_Data` / `Data` / `End_Data`) is collected too.
     */
    fun execute(
        opCode: Int,
        params: IntArray = IntArray(0),
        expectData: Boolean = false,
    ): PtpIpResult =
        synchronized(commandLock) {
            val output = commandOut ?: throw IOException("Not connected")
            val input = commandIn ?: throw IOException("Not connected")
            transactionId += 1
            PtpIpCodec.writeCommandRequest(output, opCode, transactionId, params.toList())
            val response = readResponse(input)
            val data =
                if (expectData && response.code == PtpIp.RSP_OK) readDataPhase(input) else null
            PtpIpResult(response.code, response.params, data)
        }

    /** Returns the next camera event code, or null if none arrives within [timeoutMs]. */
    fun pollEvent(timeoutMs: Long): Int? = events.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        running = false
        runCatching {
            synchronized(commandLock) {
                commandOut?.let { output ->
                    transactionId += 1
                    PtpIpCodec.writeCommandRequest(
                        output,
                        PtpIp.OP_CLOSE_SESSION,
                        transactionId,
                        listOf(sessionId),
                    )
                }
            }
        }
        runCatching { commandSocket?.close() }
        runCatching { eventSocket?.close() }
    }

    private fun readResponse(input: InputStream): PtpIpPacket.CommandResponse {
        while (true) {
            when (val packet = PtpIpCodec.readPacket(input)) {
                is PtpIpPacket.CommandResponse -> return packet
                is PtpIpPacket.Ping,
                is PtpIpPacket.Pong -> Unit // ignore keep-alive traffic
                else -> throw IOException("Unexpected packet while awaiting response: $packet")
            }
        }
    }

    private fun readDataPhase(input: InputStream): ByteArray {
        if (PtpIpCodec.readPacket(input) !is PtpIpPacket.StartData) return ByteArray(0)
        val out = ByteArrayOutputStream()
        while (true) {
            when (val packet = PtpIpCodec.readPacket(input)) {
                is PtpIpPacket.DataChunk -> out.write(packet.bytes)
                is PtpIpPacket.EndData -> {
                    out.write(packet.bytes)
                    return out.toByteArray()
                }
                else -> return out.toByteArray()
            }
        }
    }

    private fun startEventChannel(socket: Socket) {
        val input = socket.getInputStream()
        Thread(
                {
                    while (running) {
                        try {
                            when (val packet = PtpIpCodec.readPacket(input)) {
                                is PtpIpPacket.Event -> events.offer(packet.code)
                                else -> Unit
                            }
                        } catch (e: IOException) {
                            if (running)
                                Log.warn(tag = TAG) { "Event channel closed: ${e.message}" }
                            return@Thread
                        }
                    }
                },
                "ptpip-events",
            )
            .apply {
                isDaemon = true
                start()
            }

        val output = socket.getOutputStream()
        Thread(
                {
                    while (running) {
                        runCatching { PtpIpCodec.writePing(output) }
                        runCatching { Thread.sleep(KEEP_ALIVE_MS) }
                    }
                },
                "ptpip-keepalive",
            )
            .apply {
                isDaemon = true
                start()
            }
    }
}
