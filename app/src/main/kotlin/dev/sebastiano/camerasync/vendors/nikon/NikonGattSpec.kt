package dev.sebastiano.camerasync.vendors.nikon

import dev.sebastiano.camerasync.domain.vendor.CameraGattSpec
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * GATT specification for Nikon Z-series cameras using SnapBridge protocol.
 *
 * Nikon SnapBridge uses obfuscated proprietary service UUIDs. For the initial POC, we rely on
 * manufacturer data and device names for recognition. Standard Device Information Service (0x180A)
 * provides firmware version and model name.
 *
 * TODO: Reverse-engineer actual SnapBridge service/characteristic UUIDs via BLE packet capture.
 */
@OptIn(ExperimentalUuidApi::class)
object NikonGattSpec : CameraGattSpec {

    /**
     * Standard Device Information Service UUID. Used as scan filter baseline since Nikon cameras
     * advertise this.
     */
    val DEVICE_INFORMATION_SERVICE_UUID: Uuid = Uuid.parse("0000180A-0000-1000-8000-00805F9B34FB")

    /** Standard Battery Service UUID. Nikon cameras advertise battery level via this service. */
    val BATTERY_SERVICE_UUID: Uuid = Uuid.parse("0000180F-0000-1000-8000-00805F9B34FB")

    /**
     * SnapBridge service UUID discovered from Z30 BLE advertisement.
     *
     * This is the primary service UUID advertised by Nikon Z-series cameras during scanning.
     * Discovered via CompanionDeviceManager scan result on 2026-05-05.
     */
    val SNAPBRIDGE_SERVICE_UUID: Uuid = Uuid.parse("0000DE00-3DD4-4255-8D62-6DC7B9BD5561")

    override val scanFilterServiceUuids: List<Uuid> =
        listOf(SNAPBRIDGE_SERVICE_UUID, DEVICE_INFORMATION_SERVICE_UUID, BATTERY_SERVICE_UUID)

    /** Device name prefixes for Nikon cameras (Z series, DSLR, Coolpix). */
    override val scanFilterDeviceNames: List<String> =
        listOf(
            "Nikon",
            "Z ",
            "Z_",
            "Z5",
            "Z6",
            "Z7",
            "Z8",
            "Z9",
            "Zf",
            "DSC",
            "D8",
            "D7",
            "D6",
            "D5",
        )

    /** Standard Device Information Service for firmware version. */
    object Firmware {
        val SERVICE_UUID: Uuid = Uuid.parse("0000180A-0000-1000-8000-00805F9B34FB")
        val VERSION_CHARACTERISTIC_UUID: Uuid = Uuid.parse("00002A26-0000-1000-8000-00805F9B34FB")
    }

    override val firmwareServiceUuid: Uuid = Firmware.SERVICE_UUID
    override val firmwareVersionCharacteristicUuid: Uuid = Firmware.VERSION_CHARACTERISTIC_UUID

    /** Standard Device Information Service for model number. */
    object DeviceName {
        val SERVICE_UUID: Uuid = Uuid.parse("0000180A-0000-1000-8000-00805F9B34FB")
        val MODEL_NUMBER_CHARACTERISTIC_UUID: Uuid =
            Uuid.parse("00002A24-0000-1000-8000-00805F9B34FB")
    }

    /** Nikon does not support setting paired device name via BLE. */
    override val deviceNameServiceUuid: Uuid? = null
    override val deviceNameCharacteristicUuid: Uuid? = null

    /**
     * SnapBridge service/characteristic UUIDs.
     *
     * The service UUID was confirmed from Z30 BLE advertisement (2026-05-05). Individual
     * characteristic UUIDs are still TBD and require deeper BLE service discovery after bonding.
     */
    object SnapBridge {
        val SERVICE_UUID: Uuid = SNAPBRIDGE_SERVICE_UUID
        val GPS_CHARACTERISTIC_UUID: Uuid? = null
        val DATE_TIME_CHARACTERISTIC_UUID: Uuid? = null
        val AUTH_CHARACTERISTIC_UUID: Uuid? = null
    }

    override val dateTimeServiceUuid: Uuid? = SnapBridge.SERVICE_UUID
    override val dateTimeCharacteristicUuid: Uuid? = SnapBridge.DATE_TIME_CHARACTERISTIC_UUID

    /** Nikon does not have a separate geo-tagging toggle. */
    override val geoTaggingCharacteristicUuid: Uuid? = null

    override val locationServiceUuid: Uuid? = SnapBridge.SERVICE_UUID
    override val locationCharacteristicUuid: Uuid? = SnapBridge.GPS_CHARACTERISTIC_UUID

    override val pairingServiceUuid: Uuid? = SnapBridge.SERVICE_UUID
    override val pairingCharacteristicUuid: Uuid? = SnapBridge.AUTH_CHARACTERISTIC_UUID
}
