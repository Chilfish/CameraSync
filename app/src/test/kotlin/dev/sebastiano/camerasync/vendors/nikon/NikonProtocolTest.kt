package dev.sebastiano.camerasync.vendors.nikon

import dev.sebastiano.camerasync.domain.model.GpsLocation
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NikonProtocolTest {

    // region DateTime encoding/decoding

    @Test
    fun `encodeDateTime produces 7 bytes`() {
        val dt = ZonedDateTime.of(2026, 5, 5, 14, 30, 45, 0, ZoneId.systemDefault())
        val encoded = NikonProtocol.encodeDateTime(dt)
        assertEquals(NikonProtocol.DATE_TIME_SIZE, encoded.size)
    }

    @Test
    fun `dateTime round trip is correct`() {
        val dt = ZonedDateTime.of(2026, 5, 5, 14, 30, 45, 0, ZoneOffset.UTC)
        val encoded = NikonProtocol.encodeDateTime(dt)
        val decoded = NikonProtocol.decodeDateTime(encoded)
        assertTrue(decoded.contains("2026"))
        assertTrue(decoded.contains("05"))
        assertTrue(decoded.contains("14:30:45"))
    }

    @Test
    fun `dateTime preserves midnight values`() {
        val dt = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val encoded = NikonProtocol.encodeDateTime(dt)
        val decoded = NikonProtocol.decodeDateTime(encoded)
        assertTrue(decoded.contains("00:00:00"))
    }

    @Test
    fun `dateTime preserves end of year values`() {
        val dt = ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)
        val encoded = NikonProtocol.encodeDateTime(dt)
        val decoded = NikonProtocol.decodeDateTime(encoded)
        assertTrue(decoded.contains("12"))
        assertTrue(decoded.contains("23:59:59"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decodeDateTime with too few bytes throws`() {
        NikonProtocol.decodeDateTime(byteArrayOf(0x01, 0x02, 0x03))
    }

    // endregion

    // region GPS Location encoding/decoding

    @Test
    fun `encodeLocation produces 18 bytes`() {
        val location =
            GpsLocation(
                latitude = 35.6895,
                longitude = 139.6917,
                altitude = 40.0,
                timestamp = ZonedDateTime.now(ZoneOffset.UTC),
            )
        val encoded = NikonProtocol.encodeLocation(location)
        assertEquals(NikonProtocol.LOCATION_SIZE, encoded.size)
    }

    @Test
    fun `location round trip preserves coordinates`() {
        val dt = ZonedDateTime.of(2026, 5, 5, 14, 30, 45, 0, ZoneOffset.UTC)
        val location = GpsLocation(35.6895, 139.6917, 40.0, timestamp = dt)
        val encoded = NikonProtocol.encodeLocation(location)
        val decoded = NikonProtocol.decodeLocation(encoded)

        // Float precision is ~6 decimal places for 32-bit IEEE 754
        assertTrue(decoded.contains("35.68"))
        assertTrue(decoded.contains("139.69"))
        assertTrue(decoded.contains("40."))
        assertTrue(decoded.contains("2026"))
    }

    @Test
    fun `location converts to UTC timestamps`() {
        // Tokyo is UTC+9
        val jst = ZoneId.of("Asia/Tokyo")
        val localTime = ZonedDateTime.of(2026, 5, 5, 14, 0, 0, 0, jst)
        val location = GpsLocation(35.0, 139.0, 0.0, timestamp = localTime)
        val encoded = NikonProtocol.encodeLocation(location)
        val decoded = NikonProtocol.decodeLocation(encoded)

        // Should show UTC time (05:00), not JST (14:00)
        assertTrue(decoded.contains("05:00"))
    }

    @Test
    fun `location handles negative coordinates`() {
        val dt = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val location = GpsLocation(-33.8688, 151.2093, 15.0, timestamp = dt) // Sydney
        val encoded = NikonProtocol.encodeLocation(location)
        val decoded = NikonProtocol.decodeLocation(encoded)

        assertTrue(decoded.contains("-33."))
        assertTrue(decoded.contains("151."))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decodeLocation with too few bytes throws`() {
        NikonProtocol.decodeLocation(byteArrayOf(0x01, 0x02))
    }

    // endregion

    // region GeoTagging (not supported)

    @Test
    fun `geo tagging is always disabled for Nikon`() {
        assertTrue(NikonProtocol.encodeGeoTaggingEnabled(true).isEmpty())
        assertTrue(NikonProtocol.encodeGeoTaggingEnabled(false).isEmpty())
        assertFalse(NikonProtocol.decodeGeoTaggingEnabled(byteArrayOf(0x01)))
        assertFalse(NikonProtocol.decodeGeoTaggingEnabled(byteArrayOf(0x00)))
    }

    // endregion

    // region Pairing init data

    @Test
    fun `pairing init data is null for POC`() {
        // Will return actual data once SnapBridge auth protocol is reverse-engineered
        assertEquals(null, NikonProtocol.getPairingInitData())
    }

    // endregion
}
