package dev.sebastiano.camerasync.vendors.nikon

import android.bluetooth.le.ScanFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.DeviceFilter
import android.os.ParcelUuid
import dev.sebastiano.camerasync.domain.vendor.CameraCapabilities
import dev.sebastiano.camerasync.domain.vendor.CameraGattSpec
import dev.sebastiano.camerasync.domain.vendor.CameraProtocol
import dev.sebastiano.camerasync.domain.vendor.CameraVendor
import dev.sebastiano.camerasync.domain.vendor.VendorConnectionDelegate
import java.util.regex.Pattern
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Nikon camera vendor implementation.
 *
 * Supports Nikon cameras using the SnapBridge BLE protocol, including:
 * - Z series (Z30, Z50, Zfc, Z5, Z6/Z6II, Z7/Z7II, Z8, Z9)
 * - DSLR (D850, D780, D7500, etc.)
 * - Coolpix (W series, A series, P series)
 *
 * **POC Status**: Device recognition is implemented via manufacturer data and device name matching.
 * GPS/time sync and SnapBridge authentication require reverse-engineered UUIDs from BLE packet
 * capture of the official SnapBridge app.
 *
 * ## SnapBridge Pairing Flow
 *
 * When pairing a fresh camera with a new phone:
 * 1. The camera does NOT directly display a pairing code on its LCD
 * 2. The official SnapBridge app initiates the connection
 * 3. During the SnapBridge handshake, the camera sends a challenge; the app responds
 * 4. The camera then displays a verification screen; the app also shows a code
 * 5. Both sides confirm the match
 *
 * For CameraSync to replicate this, we need to implement the SnapBridge auth protocol (TBD via
 * reverse engineering). Until then, if the camera was previously paired via SnapBridge on the same
 * phone, standard BLE bonding should allow connection.
 */
@OptIn(ExperimentalUuidApi::class)
object NikonCameraVendor : CameraVendor {
    /**
     * Device type byte values observed in Nikon manufacturer data.
     *
     * Nikon uses various type IDs to distinguish camera families. Z cameras typically use different
     * type IDs than DSLRs. These values need verification via BLE packet capture.
     */
    private val NIKON_DEVICE_TYPES =
        setOf(
            0x01, // Z series (tentative)
            0x02, // DSLR (tentative)
            0x10, // Coolpix (tentative)
        )

    override val vendorId: String = "nikon"

    override val vendorName: String = "Nikon"

    override val gattSpec: CameraGattSpec = NikonGattSpec

    /**
     * Nikon's Bluetooth manufacturer ID (0x0399 = 921 decimal, verified from Z30 advertisement).
     */
    const val NIKON_MANUFACTURER_ID = 0x0399

    override val protocol: CameraProtocol = NikonProtocol

    override fun recognizesDevice(
        deviceName: String?,
        serviceUuids: List<Uuid>,
        manufacturerData: Map<Int, ByteArray>,
    ): Boolean {
        // Check 1: Nikon manufacturer data (most reliable)
        if (hasNikonManufacturerData(manufacturerData)) {
            return true
        }

        // Check 2: Device name pattern matching
        if (deviceName != null && matchesNikonNamePattern(deviceName)) {
            return true
        }

        // Check 3: Nikon-specific service UUIDs (once known)
        return serviceUuids.any { uuid -> NikonGattSpec.scanFilterServiceUuids.contains(uuid) }
    }

    /**
     * Checks if manufacturer data contains a Nikon manufacturer ID.
     *
     * Nikon BLE advertisements include manufacturer data with ID [NIKON_MANUFACTURER_ID] (0x0399).
     * Device type encoding is not yet fully understood, so any non-empty data for this ID is
     * considered a Nikon device.
     */
    private fun hasNikonManufacturerData(manufacturerData: Map<Int, ByteArray>): Boolean {
        val nikonData = manufacturerData[NIKON_MANUFACTURER_ID] ?: return false
        return nikonData.isNotEmpty()
    }

    /**
     * Matches Nikon device name patterns.
     *
     * Nikon cameras advertise with names like:
     * - "Nikon Z 30" (Z series)
     * - "Nikon D850" (DSLR)
     * - "DSC_W300" (Coolpix, via Wi-Fi)
     */
    private fun matchesNikonNamePattern(deviceName: String): Boolean {
        val name = deviceName.trim()
        return NikonGattSpec.scanFilterDeviceNames.any { prefix ->
            name.startsWith(prefix, ignoreCase = true)
        }
    }

    override fun parseAdvertisementMetadata(
        manufacturerData: Map<Int, ByteArray>
    ): Map<String, Any> {
        val nikonData = manufacturerData[NIKON_MANUFACTURER_ID] ?: return emptyMap()
        if (nikonData.isEmpty()) return emptyMap()

        val metadata = mutableMapOf<String, Any>()

        // Byte 0: device family/type
        val deviceType = nikonData[0].toInt() and 0xFF
        metadata["deviceType"] = deviceType

        // Byte 1+: model variant (if available)
        if (nikonData.size >= 2) {
            val variant = nikonData[1].toInt() and 0xFF
            metadata["deviceVariant"] = variant
        }

        return metadata
    }

    override fun createConnectionDelegate(): VendorConnectionDelegate = NikonConnectionDelegate()

    override fun getCapabilities(): CameraCapabilities {
        return CameraCapabilities(
            supportsFirmwareVersion = true, // Standard DIS (0x2A26)
            supportsDeviceName = false, // Nikon does not expose writable device name
            supportsDateTimeSync = false, // Requires SnapBridge UUIDs (POC limitation)
            supportsGeoTagging = false, // No separate toggle
            supportsLocationSync = false, // Requires SnapBridge UUIDs (POC limitation)
            requiresVendorPairing = true, // SnapBridge auth required
            supportsHardwareRevision = true, // Standard DIS (0x2A27)
        )
    }

    /**
     * Extracts the camera model from a pairing name.
     *
     * Handles user-customized names by searching for known Nikon model patterns:
     * - "Z 30" → returns "Z30"
     * - "Nikon Z 6II" → returns "Z6II"
     * - "D850" → returns "D850"
     */
    override fun extractModelFromPairingName(pairingName: String?): String {
        if (pairingName == null) return "Unknown"

        val name = pairingName.trim()

        // Z series: "Z 30", "Z_30", "Z50", "Z 6II", "Z6 II", "Z 8"
        // The Z30 advertises as "Z_30_XXXXXXX" — strip trailing serial after second underscore
        val zPattern = Regex("Z[_\\s]*(\\d+[A-Za-z]*)", RegexOption.IGNORE_CASE)
        zPattern.find(name)?.let { match ->
            return "Z${match.groupValues[1]}"
        }

        // DSLR: "D850", "D780", "D7500", "D500"
        val dPattern = Regex("D(\\d{3,4}[A-Za-z]*)", RegexOption.IGNORE_CASE)
        dPattern.find(name)?.let { match ->
            return "D${match.groupValues[1]}"
        }

        // "Nikon Something" → strip the Nikon prefix
        if (name.startsWith("Nikon", ignoreCase = true)) {
            val stripped = name.removePrefix("Nikon").removePrefix("NIKON").trim()
            if (stripped.isNotEmpty()) return stripped
        }

        return name
    }

    override fun getCompanionDeviceFilters(): List<DeviceFilter<*>> {
        val nameFilter =
            BluetoothLeDeviceFilter.Builder()
                .setNamePattern(Pattern.compile("(Nikon|Z[_ ]).*"))
                .build()

        val filters = mutableListOf<DeviceFilter<*>>(nameFilter)

        // Add service UUID filter using SnapBridge service (the UUID Z30 actually advertises)
        val serviceFilter =
            BluetoothLeDeviceFilter.Builder()
                .setScanFilter(
                    ScanFilter.Builder()
                        .setServiceUuid(
                            ParcelUuid(NikonGattSpec.SNAPBRIDGE_SERVICE_UUID.toJavaUuid())
                        )
                        .build()
                )
                .build()
        filters.add(serviceFilter)

        return filters
    }
}
