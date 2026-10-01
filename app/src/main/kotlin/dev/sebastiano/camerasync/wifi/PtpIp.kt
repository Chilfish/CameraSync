package dev.sebastiano.camerasync.wifi

/**
 * PTP/IP (ISO 15740 over TCP) constants.
 *
 * Part of the WiFi transport POC (ADR-011): a second
 * [CameraSource][dev.sebastiano.camerasync.camera.CameraSource] implementation that speaks the
 * camera's native protocol over TCP port 15740. It is **not wired into the app** — no manifest
 * permission, no UI — so the USB path and the privacy posture are unchanged. See
 * `docs/planning/wireless-transfer.md`.
 */
object PtpIp {
    /** PTP/IP TCP port (equal to the ISO standard number). */
    const val PORT = 15740

    /** Packet header size: 4-byte length + 4-byte type. */
    const val HEADER_SIZE = 8

    /** GUID size in bytes (initiator id in the init packet). */
    const val GUID_SIZE = 16

    /** Parent handle meaning "root" in PTP object-handle queries. */
    const val ROOT_PARENT = -1 // 0xFFFFFFFF

    /** `GetObjectHandles` format filter meaning "all formats". */
    const val ALL_FORMATS = 0

    // ── Packet types ────────────────────────────────────────────────────────
    const val TYPE_INIT_COMMAND_REQUEST = 1
    const val TYPE_INIT_COMMAND_ACK = 2
    const val TYPE_INIT_EVENT_REQUEST = 3
    const val TYPE_INIT_EVENT_ACK = 4
    const val TYPE_INIT_FAIL = 5
    const val TYPE_COMMAND_REQUEST = 6
    const val TYPE_COMMAND_RESPONSE = 7
    const val TYPE_EVENT = 8
    const val TYPE_START_DATA = 9
    const val TYPE_DATA = 10
    const val TYPE_END_DATA = 12
    const val TYPE_PING = 13
    const val TYPE_PONG = 14

    // ── Operation codes ─────────────────────────────────────────────────────
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_GET_THUMB = 0x100A
    const val OP_DELETE_OBJECT = 0x100B
    const val OP_GET_PARTIAL_OBJECT = 0x101B

    // ── Response codes ──────────────────────────────────────────────────────
    const val RSP_OK = 0x2001
    const val RSP_GENERAL_ERROR = 0x2002

    // ── Event codes ─────────────────────────────────────────────────────────
    const val EVENT_OBJECT_ADDED = 0x4002
    const val EVENT_OBJECT_REMOVED = 0x4003
    const val EVENT_STORE_ADDED = 0x4004
    const val EVENT_STORE_REMOVED = 0x4005

    // ── Object formats ──────────────────────────────────────────────────────
    const val FORMAT_ASSOCIATION = 0x3001
    const val FORMAT_EXIF_JPEG = 0x3801
    const val FORMAT_JPEG = 0x3808
    const val FORMAT_TIFF = 0x380D
    const val FORMAT_NEF = 0xB103

    /** Association type marking a generic folder (used when the format is not 0x3001). */
    const val ASSOCIATION_GENERIC_FOLDER = 0x0001

    /** Maps a PTP object format code to a human-readable name (mirrors the USB transport). */
    fun formatName(code: Int): String =
        when (code) {
            FORMAT_ASSOCIATION -> "Folder"
            FORMAT_EXIF_JPEG -> "EXIF_JPEG"
            FORMAT_JPEG -> "JPEG"
            FORMAT_TIFF -> "TIFF"
            FORMAT_NEF -> "NEF(RAW)"
            else -> "fmt(0x${code.toString(16)})"
        }
}
