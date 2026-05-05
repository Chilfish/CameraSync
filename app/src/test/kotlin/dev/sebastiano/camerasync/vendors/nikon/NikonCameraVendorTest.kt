package dev.sebastiano.camerasync.vendors.nikon

import kotlin.uuid.ExperimentalUuidApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalUuidApi::class)
class NikonCameraVendorTest {

    @Test
    fun `vendorId is nikon`() {
        assertEquals("nikon", NikonCameraVendor.vendorId)
    }

    @Test
    fun `vendorName is Nikon`() {
        assertEquals("Nikon", NikonCameraVendor.vendorName)
    }

    // region Device recognition by manufacturer data

    @Test
    fun `recognizes device by Nikon manufacturer data with known device type`() {
        // Device type 0x01 = Z series
        val mfrData = mapOf(NikonCameraVendor.NIKON_MANUFACTURER_ID to byteArrayOf(0x01))
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = null,
                serviceUuids = emptyList(),
                manufacturerData = mfrData,
            )
        )
    }

    @Test
    fun `recognizes device by Nikon manufacturer data regardless of size`() {
        // Some Nikon cameras advertise 2+ bytes of manufacturer data
        val mfrData =
            mapOf(NikonCameraVendor.NIKON_MANUFACTURER_ID to byteArrayOf(0x01, 0x00, 0x05))
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = null,
                serviceUuids = emptyList(),
                manufacturerData = mfrData,
            )
        )
    }

    @Test
    fun `does not recognize device with non-Nikon manufacturer data`() {
        val mfrData = mapOf(0x012D to byteArrayOf(0x03, 0x00)) // Sony
        assertFalse(
            NikonCameraVendor.recognizesDevice(
                deviceName = null,
                serviceUuids = emptyList(),
                manufacturerData = mfrData,
            )
        )
    }

    @Test
    fun `does not recognize device with empty manufacturer data`() {
        val mfrData = mapOf(NikonCameraVendor.NIKON_MANUFACTURER_ID to byteArrayOf())
        assertFalse(
            NikonCameraVendor.recognizesDevice(
                deviceName = null,
                serviceUuids = emptyList(),
                manufacturerData = mfrData,
            )
        )
    }

    // endregion

    // region Device recognition by name

    @Test
    fun `recognizes Z30 by device name`() {
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = "Nikon Z 30",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `recognizes Z6II by device name`() {
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = "Nikon Z 6II",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `recognizes Z9 by device name`() {
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = "Z 9",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `recognizes Z30 by device name with underscore`() {
        // Z30 advertises as "Z_30_XXXXXXX" on the actual device
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = "Z_30_8271278",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `recognizes Z50 by device name without space`() {
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = "Z50",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `recognizes Nikon DSLR by name`() {
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = "Nikon D850",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `recognizes DSLR by D-prefix name only`() {
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = "D780",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `recognizes DSC prefixed Coolpix`() {
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = "DSC_W300",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `does not recognize non-Nikon devices by name`() {
        assertFalse(
            NikonCameraVendor.recognizesDevice(
                deviceName = "RICOH GR IIIx",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
        assertFalse(
            NikonCameraVendor.recognizesDevice(
                deviceName = "ILCE-7M4",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    // endregion

    // region Device recognition by service UUID

    @Test
    fun `recognizes device by Device Information Service UUID`() {
        val serviceUuids = listOf(NikonGattSpec.DEVICE_INFORMATION_SERVICE_UUID)
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = null,
                serviceUuids = serviceUuids,
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `recognizes device by Battery Service UUID`() {
        val serviceUuids = listOf(NikonGattSpec.BATTERY_SERVICE_UUID)
        assertTrue(
            NikonCameraVendor.recognizesDevice(
                deviceName = null,
                serviceUuids = serviceUuids,
                manufacturerData = emptyMap(),
            )
        )
    }

    // endregion

    // region Model extraction

    @Test
    fun `extracts Z30 model from default Nikon name`() {
        assertEquals("Z30", NikonCameraVendor.extractModelFromPairingName("Nikon Z 30"))
    }

    @Test
    fun `extracts Z6II model from default name`() {
        assertEquals("Z6II", NikonCameraVendor.extractModelFromPairingName("Nikon Z 6II"))
    }

    @Test
    fun `extracts Z8 model from short name`() {
        assertEquals("Z8", NikonCameraVendor.extractModelFromPairingName("Z 8"))
    }

    @Test
    fun `extracts Z50 model without space`() {
        assertEquals("Z50", NikonCameraVendor.extractModelFromPairingName("Z50"))
    }

    @Test
    fun `extracts Z30 model from Z_30 format`() {
        // Actual Z30 BLE advertisement name format
        assertEquals("Z30", NikonCameraVendor.extractModelFromPairingName("Z_30_8271278"))
    }

    @Test
    fun `extracts model from user-customized name`() {
        assertEquals("Z30", NikonCameraVendor.extractModelFromPairingName("My Z 30 Camera"))
    }

    @Test
    fun `extracts DSLR model`() {
        assertEquals("D850", NikonCameraVendor.extractModelFromPairingName("Nikon D850"))
    }

    @Test
    fun `strips Nikon prefix for unknown model`() {
        assertEquals("Coolpix A", NikonCameraVendor.extractModelFromPairingName("Nikon Coolpix A"))
    }

    @Test
    fun `returns Unknown for null pairing name`() {
        assertEquals("Unknown", NikonCameraVendor.extractModelFromPairingName(null))
    }

    @Test
    fun `returns pairing name as-is when no pattern matches`() {
        assertEquals("Some Camera", NikonCameraVendor.extractModelFromPairingName("Some Camera"))
    }

    // endregion

    // region Capabilities

    @Test
    fun `capabilities reflect POC state`() {
        val caps = NikonCameraVendor.getCapabilities()
        assertTrue(caps.supportsFirmwareVersion)
        assertTrue(caps.supportsHardwareRevision)
        assertTrue(caps.requiresVendorPairing)
        assertFalse(caps.supportsDeviceName)
        assertFalse(caps.supportsGeoTagging)
        // GPS/time sync disabled until SnapBridge UUIDs are added
        assertFalse(caps.supportsLocationSync)
        assertFalse(caps.supportsDateTimeSync)
    }

    // endregion

    // region Advertisement metadata

    @Test
    fun `parses device type from manufacturer data`() {
        val mfrData = mapOf(NikonCameraVendor.NIKON_MANUFACTURER_ID to byteArrayOf(0x01, 0x02))
        val metadata = NikonCameraVendor.parseAdvertisementMetadata(mfrData)

        assertEquals(0x01, metadata["deviceType"])
        assertEquals(0x02, metadata["deviceVariant"])
    }

    @Test
    fun `parses partial manufacturer data`() {
        val mfrData = mapOf(NikonCameraVendor.NIKON_MANUFACTURER_ID to byteArrayOf(0x01))
        val metadata = NikonCameraVendor.parseAdvertisementMetadata(mfrData)

        assertEquals(0x01, metadata["deviceType"])
        assertFalse(metadata.containsKey("deviceVariant"))
    }

    @Test
    fun `returns empty metadata for non-Nikon data`() {
        val mfrData = mapOf(0x012D to byteArrayOf(0x03, 0x00))
        val metadata = NikonCameraVendor.parseAdvertisementMetadata(mfrData)

        assertTrue(metadata.isEmpty())
    }

    // endregion

    // region Connection delegate

    @Test
    fun `creates NikonConnectionDelegate`() {
        val delegate = NikonCameraVendor.createConnectionDelegate()
        assertTrue(delegate is NikonConnectionDelegate)
    }

    // endregion

    // region Companion device filters

    @Test(expected = RuntimeException::class)
    fun `companion device filters require Android runtime`() {
        // BluetoothLeDeviceFilter requires Android classes — throws in unit tests.
        // This test documents the expected behavior; the actual filter is tested via
        // integration tests on a device/emulator.
        NikonCameraVendor.getCompanionDeviceFilters()
    }

    // endregion
}
