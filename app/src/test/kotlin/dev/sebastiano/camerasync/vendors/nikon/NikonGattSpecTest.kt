package dev.sebastiano.camerasync.vendors.nikon

import kotlin.uuid.ExperimentalUuidApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalUuidApi::class)
class NikonGattSpecTest {

    @Test
    fun `scan filter service UUIDs include SnapBridge`() {
        val uuids = NikonGattSpec.scanFilterServiceUuids
        assertTrue(uuids.isNotEmpty())
        assertTrue(uuids.contains(NikonGattSpec.SNAPBRIDGE_SERVICE_UUID))
        assertTrue(uuids.contains(NikonGattSpec.DEVICE_INFORMATION_SERVICE_UUID))
        assertTrue(uuids.contains(NikonGattSpec.BATTERY_SERVICE_UUID))
    }

    @Test
    fun `scan filter device names include Nikon prefixes`() {
        val names = NikonGattSpec.scanFilterDeviceNames
        assertTrue(names.contains("Nikon"))
        assertTrue(names.contains("Z "))
        assertTrue(names.contains("Z_"))
        assertTrue(names.contains("D8"))
    }

    @Test
    fun `firmware service and characteristic are provided`() {
        assertNotNull(NikonGattSpec.firmwareServiceUuid)
        assertNotNull(NikonGattSpec.firmwareVersionCharacteristicUuid)
    }

    @Test
    fun `device name service is not supported`() {
        assertNull(NikonGattSpec.deviceNameServiceUuid)
        assertNull(NikonGattSpec.deviceNameCharacteristicUuid)
    }

    @Test
    fun `geo tagging characteristic is not supported`() {
        assertNull(NikonGattSpec.geoTaggingCharacteristicUuid)
    }

    @Test
    fun `SnapBridge service UUID is known, characteristics are TBD`() {
        // Service UUID confirmed from Z30 advertisement (2026-05-05)
        assertNotNull(NikonGattSpec.SnapBridge.SERVICE_UUID)
        // Characteristic UUIDs still need reverse engineering
        assertNull(NikonGattSpec.SnapBridge.GPS_CHARACTERISTIC_UUID)
        assertNull(NikonGattSpec.SnapBridge.DATE_TIME_CHARACTERISTIC_UUID)
        assertNull(NikonGattSpec.SnapBridge.AUTH_CHARACTERISTIC_UUID)
    }

    @Test
    fun `location service UUID is known, characteristic is TBD`() {
        assertNotNull(NikonGattSpec.locationServiceUuid)
        assertNull(NikonGattSpec.locationCharacteristicUuid)
    }

    @Test
    fun `dateTime service UUID is known, characteristic is TBD`() {
        assertNotNull(NikonGattSpec.dateTimeServiceUuid)
        assertNull(NikonGattSpec.dateTimeCharacteristicUuid)
    }

    @Test
    fun `pairing service UUID is known, characteristic is TBD`() {
        assertNotNull(NikonGattSpec.pairingServiceUuid)
        assertNull(NikonGattSpec.pairingCharacteristicUuid)
    }

    @Test
    fun `hardware revision uses standard DIS`() {
        assertNotNull(NikonGattSpec.hardwareRevisionServiceUuid)
        assertNotNull(NikonGattSpec.hardwareRevisionCharacteristicUuid)
        assertEquals(
            "0000180a-0000-1000-8000-00805f9b34fb",
            NikonGattSpec.hardwareRevisionServiceUuid!!.toString(),
        )
    }
}
