package dev.sebastiano.camerasync.vendors.nikon

import dev.sebastiano.camerasync.domain.model.GpsLocation
import dev.sebastiano.camerasync.domain.vendor.CameraProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import okio.Buffer

/**
 * Protocol implementation for Nikon cameras via SnapBridge.
 *
 * Based on community reverse engineering of SnapBridge BLE traffic. Nikon uses a compact binary
 * format for GPS and date/time data. All multi-byte fields are big-endian except year (LE).
 *
 * This is a POC implementation. The exact byte layout may need adjustment after packet capture
 * verification against the official SnapBridge app.
 */
object NikonProtocol : CameraProtocol {

    /** Size of the encoded date/time data in bytes. */
    const val DATE_TIME_SIZE = 7

    /** Size of the encoded GPS location data in bytes. */
    const val LOCATION_SIZE = 18

    /**
     * Encodes a date/time value in Nikon SnapBridge format.
     *
     * Format (7 bytes, mixed endian):
     * - Bytes 0-1: Year (little-endian short)
     * - Byte 2: Month (1-12)
     * - Byte 3: Day (1-31)
     * - Byte 4: Hour (0-23, local time)
     * - Byte 5: Minute (0-59)
     * - Byte 6: Second (0-59)
     *
     * Note: Nikon SnapBridge uses local time for date/time sync (not UTC).
     */
    override fun encodeDateTime(dateTime: ZonedDateTime): ByteArray =
        Buffer()
            .writeShortLe(dateTime.year)
            .writeByte(dateTime.monthValue)
            .writeByte(dateTime.dayOfMonth)
            .writeByte(dateTime.hour)
            .writeByte(dateTime.minute)
            .writeByte(dateTime.second)
            .readByteArray()

    /**
     * Decodes a date/time value from Nikon SnapBridge format.
     *
     * @throws IllegalArgumentException if the byte array is not 7 bytes.
     */
    override fun decodeDateTime(bytes: ByteArray): String {
        require(bytes.size >= DATE_TIME_SIZE) {
            "DateTime data must be at least $DATE_TIME_SIZE bytes, got ${bytes.size}"
        }

        val buffer = ByteBuffer.wrap(bytes)

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val year = buffer.short.toInt()

        buffer.order(ByteOrder.BIG_ENDIAN)
        val month = buffer.get().toInt()
        val day = buffer.get().toInt()
        val hour = buffer.get().toInt()
        val minute = buffer.get().toInt()
        val second = buffer.get().toInt()

        val dt = ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC)
        return dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    /**
     * Encodes a GPS location in Nikon SnapBridge format.
     *
     * Format (18 bytes, mixed endian):
     * - Bytes 0-3: Latitude (big-endian float, IEEE 754)
     * - Bytes 4-7: Longitude (big-endian float, IEEE 754)
     * - Bytes 8-11: Altitude (big-endian float, IEEE 754)
     * - Bytes 12-13: Year (little-endian short)
     * - Byte 14: Month (1-12)
     * - Byte 15: Day (1-31)
     * - Byte 16: Hour (0-23)
     * - Byte 17: Minute (0-59)
     *
     * Note: Nikon SnapBridge uses float (32-bit) for coordinates instead of double (64-bit),
     * providing ~1 meter precision which is sufficient for GPS geotagging.
     */
    override fun encodeLocation(location: GpsLocation): ByteArray {
        val utcTimestamp = location.timestamp.withZoneSameInstant(ZoneOffset.UTC)

        return Buffer()
            .writeInt(java.lang.Float.floatToRawIntBits(location.latitude.toFloat()))
            .writeInt(java.lang.Float.floatToRawIntBits(location.longitude.toFloat()))
            .writeInt(java.lang.Float.floatToRawIntBits(location.altitude.toFloat()))
            .writeShortLe(utcTimestamp.year)
            .writeByte(utcTimestamp.monthValue)
            .writeByte(utcTimestamp.dayOfMonth)
            .writeByte(utcTimestamp.hour)
            .writeByte(utcTimestamp.minute)
            .readByteArray()
    }

    /**
     * Decodes a GPS location from Nikon SnapBridge format.
     *
     * @throws IllegalArgumentException if the byte array is not 18 bytes.
     */
    override fun decodeLocation(bytes: ByteArray): String {
        require(bytes.size >= LOCATION_SIZE) {
            "Location data must be at least $LOCATION_SIZE bytes, got ${bytes.size}"
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val latRaw = buffer.int
        val lonRaw = buffer.int
        val altRaw = buffer.int

        val latitude = java.lang.Float.intBitsToFloat(latRaw).toDouble()
        val longitude = java.lang.Float.intBitsToFloat(lonRaw).toDouble()
        val altitude = java.lang.Float.intBitsToFloat(altRaw).toDouble()

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val year = buffer.short.toInt()

        buffer.order(ByteOrder.BIG_ENDIAN)
        val month = buffer.get().toInt()
        val day = buffer.get().toInt()
        val hour = buffer.get().toInt()
        val minute = buffer.get().toInt()

        return "Lat: %.6f, Lon: %.6f, Alt: %.1fm, Time: %04d-%02d-%02d %02d:%02d UTC"
            .format(latitude, longitude, altitude, year, month, day, hour, minute)
    }

    /** Nikon SnapBridge does not use a separate geo-tagging toggle. */
    override fun encodeGeoTaggingEnabled(enabled: Boolean): ByteArray = byteArrayOf()

    /** Nikon SnapBridge does not use a separate geo-tagging toggle. */
    override fun decodeGeoTaggingEnabled(bytes: ByteArray): Boolean = false

    /**
     * SnapBridge authentication initialization data.
     *
     * Nikon SnapBridge requires an authentication exchange before GPS/time sync is permitted. The
     * exact command format requires reverse engineering of the official SnapBridge app.
     *
     * TODO: Replace with actual SnapBridge auth init command from packet capture.
     */
    override fun getPairingInitData(): ByteArray? = null
}
