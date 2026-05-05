# Nikon Z30 Vendor Adaptation Guide

This document explains how to add Nikon Z30 support to CameraSync's existing multi-vendor architecture, following the patterns established by Ricoh and Sony implementations.

## Overview

Adding the Nikon Z30 requires:

1. **BLE Layer** (fits into existing architecture):
   - `NikonCameraVendor` — implements `CameraVendor`
   - `NikonGattSpec` — implements `CameraGattSpec`
   - `NikonProtocol` — implements `CameraProtocol`
   - `NikonConnectionDelegate` — implements `VendorConnectionDelegate`

2. **Wi‑Fi/PTP‑IP Layer** (new subsystem):
   - `PtpIpTransport` — TCP socket management for port 15740
   - `PtpIpSession` — PTP/IP init and command/event handling
   - `NikonImageReceiver` — image download and Scoped Storage persistence
   - `NikonWtPairingHandler` — GUID-based 8-digit verification pairing

3. **Firmware Check Layer** (fits into existing architecture):
   - `NikonFirmwareUpdateChecker` — implements `FirmwareUpdateChecker`

## Step 1: BLE Vendor Implementation

### 1.1 NikonGattSpec

Create `vendors/nikon/NikonGattSpec.kt`:

```kotlin
@OptIn(ExperimentalUuidApi::class)
object NikonGattSpec : CameraGattSpec {

    // SnapBridge uses standard Device Information Service for discovery
    // Nikon cameras advertise specific manufacturer data (ID 0x00E0)
    val SCAN_FILTER_SERVICE_UUID: Uuid = Uuid.parse("0000180A-0000-1000-8000-00805F9B34FB")

    override val scanFilterServiceUuids: List<Uuid> = listOf(SCAN_FILTER_SERVICE_UUID)

    // Nikon Z cameras advertise with name starting with "Nikon" or "Z "
    override val scanFilterDeviceNames: List<String> = listOf("Nikon", "Z ")

    // --- GPS/Date-Time Service (SnapBridge) ---
    // Specific UUIDs need to be identified via reverse engineering.
    // Placeholder UUIDs shown below; real values come from packet capture.

    object GpsService {
        val SERVICE_UUID: Uuid = Uuid.parse("0000XXXX-0000-1000-8000-00805F9B34FB")
        val LOCATION_CHARACTERISTIC_UUID: Uuid = Uuid.parse("0000XXXX-0000-1000-8000-00805F9B34FB")
        val DATE_TIME_CHARACTERISTIC_UUID: Uuid = Uuid.parse("0000XXXX-0000-1000-8000-00805F9B34FB")
    }

    override val firmwareServiceUuid: Uuid = Uuid.parse("0000180A-0000-1000-8000-00805F9B34FB")
    override val firmwareVersionCharacteristicUuid: Uuid
        get() = Uuid.parse("00002A26-0000-1000-8000-00805F9B34FB") // Standard FW Revision

    // Nikon uses SnapBridge for GPS: phone pushes to camera
    override val locationServiceUuid: Uuid = GpsService.SERVICE_UUID
    override val locationCharacteristicUuid: Uuid = GpsService.LOCATION_CHARACTERISTIC_UUID

    override val dateTimeServiceUuid: Uuid = GpsService.SERVICE_UUID
    override val dateTimeCharacteristicUuid: Uuid = GpsService.DATE_TIME_CHARACTERISTIC_UUID

    // Nikon SnapBridge does not use a separate geo-tagging toggle
    override val geoTaggingCharacteristicUuid: Uuid? = null

    override val deviceNameServiceUuid: Uuid? = null
    override val deviceNameCharacteristicUuid: Uuid? = null
}
```

### 1.2 NikonProtocol

Create `vendors/nikon/NikonProtocol.kt`.

Nikon SnapBridge GPS/date-time encoding (based on community reverse engineering):

```kotlin
object NikonProtocol : CameraProtocol {

    // Date/Time: 7 bytes, same structure as Ricoh (little-endian year, big-endian fields)
    // Byte 0-1: Year (LE short)
    // Byte 2: Month
    // Byte 3: Day
    // Byte 4: Hour
    // Byte 5: Minute
    // Byte 6: Second

    override fun encodeDateTime(dateTime: ZonedDateTime): ByteArray =
        Buffer()
            .writeShortLe(dateTime.year)
            .writeByte(dateTime.monthValue)
            .writeByte(dateTime.dayOfMonth)
            .writeByte(dateTime.hour)
            .writeByte(dateTime.minute)
            .writeByte(dateTime.second)
            .readByteArray()

    override fun decodeDateTime(bytes: ByteArray): String {
        require(bytes.size >= 7)
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val year = buffer.short.toInt()
        buffer.order(ByteOrder.BIG_ENDIAN)
        val month = buffer.get().toInt()
        val day = buffer.get().toInt()
        val hour = buffer.get().toInt()
        val minute = buffer.get().toInt()
        val second = buffer.get().toInt()
        return "$year-${month.pad()}-${day.pad()} ${hour.pad()}:${minute.pad()}:${second.pad()}"
    }

    // GPS Location: 18 bytes (simplified SnapBridge format)
    // Bytes 0-3:  Latitude (float, BE)
    // Bytes 4-7:  Longitude (float, BE)
    // Bytes 8-11: Altitude (float, BE)
    // Bytes 12-13: Year (LE short)
    // Bytes 14-17: Month, Day, Hour, Minute, Second

    override fun encodeLocation(location: GpsLocation): ByteArray =
        Buffer()
            .writeInt(location.latitude.toRawBits())
            .writeInt(location.longitude.toRawBits())
            .writeInt(location.altitude.toRawBits())
            .writeShortLe(location.timestamp.year)
            .writeByte(location.timestamp.monthValue)
            .writeByte(location.timestamp.dayOfMonth)
            .writeByte(location.timestamp.hour)
            .writeByte(location.timestamp.minute)
            .writeByte(location.timestamp.second)
            .readByteArray()

    override fun decodeLocation(bytes: ByteArray): String {
        // Implementation mirrors encodeLocation in reverse
        // ...
    }

    // Nikon does not use a separate geo-tagging toggle
    override fun encodeGeoTaggingEnabled(enabled: Boolean): ByteArray = byteArrayOf()
    override fun decodeGeoTaggingEnabled(bytes: ByteArray): Boolean = false

    // Nikon requires SnapBridge authentication exchange
    override fun getPairingInitData(): ByteArray? =
        // SnapBridge auth init command — 6 bytes
        byteArrayOf(0x06, 0x00, 0x01, 0x00, 0x00, 0x00)
}
```

### 1.3 NikonConnectionDelegate

Create `vendors/nikon/NikonConnectionDelegate.kt`.

Nikon SnapBridge has a moderate complexity: simpler than Sony's DD30/DD31, but requires authentication exchange:

```kotlin
class NikonConnectionDelegate : DefaultConnectionDelegate() {

    // SnapBridge uses 20-byte MTU (standard BLE)
    override val mtu: Int? = null

    private var snapBridgeAuthenticated = false

    override suspend fun onConnected(peripheral: Peripheral, camera: Camera) {
        // 1. Discover services
        // 2. Read firmware/hardware info (standard DIS)
        // 3. Perform SnapBridge authentication exchange:
        //    a. Write auth init command to auth characteristic
        //    b. Subscribe to auth notification characteristic
        //    c. Wait for auth response
        //    d. Camera displays 8-digit code → app sends confirmation
        snapBridgeAuthenticated = true
    }

    override suspend fun syncLocation(
        peripheral: Peripheral,
        camera: Camera,
        location: GpsLocation
    ) {
        // Same pattern as DefaultConnectionDelegate:
        // Look up service/characteristic → encode → write
        // Uses standard GATT write — SnapBridge is simpler than Sony's DD30/DD31
        super.syncLocation(peripheral, camera, location)
    }

    override suspend fun onDisconnecting(peripheral: Peripheral) {
        // Optional: send disconnect notification to camera
        snapBridgeAuthenticated = false
    }
}
```

### 1.4 NikonCameraVendor

Create `vendors/nikon/NikonCameraVendor.kt`:

```kotlin
@OptIn(ExperimentalUuidApi::class)
object NikonCameraVendor : CameraVendor {

    /** Nikon's Bluetooth manufacturer ID (0x00E0 = 224 decimal). */
    const val NIKON_MANUFACTURER_ID = 0x00E0

    override val vendorId: String = "nikon"
    override val vendorName: String = "Nikon"
    override val gattSpec: CameraGattSpec = NikonGattSpec
    override val protocol: CameraProtocol = NikonProtocol

    override fun recognizesDevice(
        deviceName: String?,
        serviceUuids: List<Uuid>,
        manufacturerData: Map<Int, ByteArray>,
    ): Boolean {
        // Check 1: Nikon manufacturer data (most reliable)
        // Nikon Z30 manufacturer data: ID 0x00E0, data contains camera model info
        if (manufacturerData.containsKey(NIKON_MANUFACTURER_ID)) {
            return true
        }

        // Check 2: Device name starts with "Nikon", "Z ", or "DSC"
        if (deviceName != null) {
            val normalized = deviceName.trim()
            if (normalized.startsWith("Nikon", ignoreCase = true) ||
                normalized.startsWith("Z ", ignoreCase = true) ||
                normalized.startsWith("DSC", ignoreCase = true)
            ) {
                return true
            }
        }

        // Check 3: Service UUIDs
        return serviceUuids.any { uuid ->
            NikonGattSpec.scanFilterServiceUuids.contains(uuid)
        }
    }

    override fun parseAdvertisementMetadata(
        manufacturerData: Map<Int, ByteArray>
    ): Map<String, Any> {
        val nikonData = manufacturerData[NIKON_MANUFACTURER_ID] ?: return emptyMap()
        // First byte encodes camera family, second byte encodes variant
        if (nikonData.size >= 2) {
            val family = nikonData[0].toInt() and 0xFF
            val variant = nikonData[1].toInt() and 0xFF
            return mapOf(
                "cameraFamily" to family,
                "cameraVariant" to variant,
            )
        }
        return emptyMap()
    }

    override fun createConnectionDelegate(): VendorConnectionDelegate =
        NikonConnectionDelegate()

    override fun getCapabilities(): CameraCapabilities = CameraCapabilities(
        supportsFirmwareVersion = true,
        supportsDeviceName = false,
        supportsDateTimeSync = true,
        supportsGeoTagging = false,
        supportsLocationSync = true,
        requiresVendorPairing = true,  // SnapBridge auth exchange
        supportsHardwareRevision = true,
    )

    override fun extractModelFromPairingName(pairingName: String?): String {
        if (pairingName == null) return "Unknown"
        val name = pairingName.trim()

        // Nikon Z series: "Z 30", "Z 50", "Z 6II", "Z 8", "Z 9"
        val zPattern = Regex("Z\\s*(\\d+[A-Za-z]*)", RegexOption.IGNORE_CASE)
        zPattern.find(name)?.let { match ->
            return "Z ${match.groupValues[1]}"
        }

        // Nikon DSLR: "D####"
        val dPattern = Regex("D(\\d{3,4}[A-Za-z]*)", RegexOption.IGNORE_CASE)
        dPattern.find(name)?.let { match ->
            return "D${match.groupValues[1]}"
        }

        if (name.startsWith("Nikon", ignoreCase = true)) {
            // Strip "Nikon" prefix for model name
            return name.removePrefix("Nikon").removePrefix("NIKON").trim()
        }

        return name
    }

    override fun getCompanionDeviceFilters(): List<DeviceFilter<*>> {
        val nameFilter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("(Nikon|Z).*"))
            .build()
        return listOf(nameFilter)
    }
}
```

## Step 2: Wi‑Fi / PTP‑IP Image Transfer Layer

This is a **new subsystem** beyond CameraSync's current BLE-only architecture. See [PTP_IMAGE_TRANSFER.md](PTP_IMAGE_TRANSFER.md) for the full design.

### Key Components

```
app/src/main/kotlin/dev/sebastiano/camerasync/ptp/
├── PtpIpTransport.kt          # TCP socket manager (port 15740)
├── PtpIpSession.kt            # PTP/IP init, command/event dispatch
├── PtpCommand.kt              # PTP operation codes (GetDeviceInfo, GetObject, etc.)
├── PtpEvent.kt                # PTP event codes (ObjectAdded, etc.)
├── NikonImageReceiver.kt      # Image download → Scoped Storage
└── NikonWtPairingHandler.kt   # WTU GUID-based pairing
```

### Integration Points

The Wi‑Fi/PTP‑IP layer integrates with the existing architecture at two points:

1. **MultiDeviceSyncService**: Extended to manage PTP/IP connections alongside BLE connections. When a paired Nikon camera is in range, the service can initiate Wi‑Fi handoff and begin image reception.

2. **DeviceConnectionManager**: Extended with a `PtpIpConnection` state type, tracking whether the PTP/IP session is active.

## Step 3: Registration in AppGraph

```kotlin
// In AppGraph.kt

@Provides
@SingleIn(AppGraph::class)
fun provideVendorRegistry(): CameraVendorRegistry =
    DefaultCameraVendorRegistry(
        vendors = listOf(
            RicohCameraVendor,
            SonyCameraVendor,
            NikonCameraVendor,          // <-- Add Nikon
        )
    )

@Provides
@SingleIn(AppGraph::class)
fun provideFirmwareUpdateCheckers(context: Context): List<FirmwareUpdateChecker> =
    listOf(
        SonyFirmwareUpdateChecker(context),
        RicohFirmwareUpdateChecker(context),
        NikonFirmwareUpdateChecker(context),  // <-- Add Nikon
    )
```

## Step 4: Testing

Create test files under `app/src/test/`:

```
vendors/nikon/
├── NikonCameraVendorTest.kt
├── NikonGattSpecTest.kt
├── NikonProtocolTest.kt
└── NikonConnectionDelegateTest.kt

firmware/nikon/
└── NikonFirmwareUpdateCheckerTest.kt

ptp/
├── PtpIpSessionTest.kt
├── NikonImageReceiverTest.kt
└── NikonWtPairingHandlerTest.kt
```

### NikonCameraVendorTest Example

```kotlin
@OptIn(ExperimentalUuidApi::class)
class NikonCameraVendorTest {

    private val vendor = NikonCameraVendor

    @Test
    fun `recognizes Z30 by manufacturer data`() {
        val mfrData = mapOf(
            NikonCameraVendor.NIKON_MANUFACTURER_ID to byteArrayOf(0x01, 0x00)
        )
        assertTrue(
            vendor.recognizesDevice(
                deviceName = null,
                serviceUuids = emptyList(),
                manufacturerData = mfrData,
            )
        )
    }

    @Test
    fun `recognizes Z30 by device name`() {
        assertTrue(
            vendor.recognizesDevice(
                deviceName = "Nikon Z 30",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `recognizes Nikon DSLR by name`() {
        assertTrue(
            vendor.recognizesDevice(
                deviceName = "D850",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `extracts model from pairing name`() {
        assertEquals("Z 30", vendor.extractModelFromPairingName("Nikon Z 30"))
        assertEquals("Z 6II", vendor.extractModelFromPairingName("My Z 6II"))
        assertEquals("D850", vendor.extractModelFromPairingName("Nikon D850"))
    }

    @Test
    fun `does not recognize non-Nikon devices`() {
        assertFalse(
            vendor.recognizesDevice(
                deviceName = "GR IIIx",
                serviceUuids = emptyList(),
                manufacturerData = emptyMap(),
            )
        )
    }

    @Test
    fun `capabilities match Nikon Z30 features`() {
        val caps = vendor.getCapabilities()
        assertTrue(caps.supportsFirmwareVersion)
        assertTrue(caps.supportsDateTimeSync)
        assertTrue(caps.supportsLocationSync)
        assertTrue(caps.requiresVendorPairing)
        assertFalse(caps.supportsGeoTagging)
        assertFalse(caps.supportsDeviceName)
    }
}
```

## Architecture Diff: What Changes vs What's Reused

| Component | Reused from Existing | Must be Created |
|-----------|---------------------|-----------------|
| `CameraVendor` interface | ✅ | |
| `CameraGattSpec` interface | ✅ | |
| `CameraProtocol` interface | ✅ | |
| `VendorConnectionDelegate` interface | ✅ | |
| `DefaultConnectionDelegate` | ✅ (base class for Nikon delegate) | |
| `CameraVendorRegistry` | ✅ (no changes needed) | |
| `KableCameraRepository` | ✅ (no changes needed) | |
| `KableCameraConnection` | ✅ (no changes needed) | |
| `MultiDeviceSyncCoordinator` | ✅ (no changes needed for BLE sync) | |
| `FirmwareUpdateChecker` interface | ✅ | |
| `NikonCameraVendor` | | ✅ New |
| `NikonGattSpec` | | ✅ New |
| `NikonProtocol` | | ✅ New |
| `NikonConnectionDelegate` | | ✅ New |
| `NikonFirmwareUpdateChecker` | | ✅ New |
| `PtpIpTransport` | | ✅ New (Wi‑Fi subsystem) |
| `PtpIpSession` | | ✅ New |
| `NikonImageReceiver` | | ✅ New |
| `NikonWtPairingHandler` | | ✅ New |
| `AppGraph.kt` | Minor change (2 lines) | |

## Known Challenges

1. **SnapBridge UUID reversal**: Nikon uses obfuscated code in their official app. Exact BLE UUIDs must be captured via BLE packet sniffing (e.g., Wireshark with BLE dongle, or Android HCI snoop log).

2. **PTP/IP negotiation**: The WTU pairing protocol uses MD5-based challenge-response. The specific MD5 input format (salt, concatenation order) requires reverse engineering of the official WTU application.

3. **Wi‑Fi handoff**: CameraSync currently has no Wi‑Fi management layer. A new component must handle:
   - Requesting Android to connect to camera Wi‑Fi
   - Binding process to camera network (Android `bindProcessToNetwork`)
   - Handling network transitions gracefully

4. **Foreground Service battery impact**: Wi‑Fi PTP/IP sessions consume significantly more power than BLE-only sync. The service should only activate Wi‑Fi when image transfer is requested.

5. **Scoped Storage**: Android 11+ requires careful handling of file write permissions. Images must be saved to `MediaStore` or app-specific directories with proper content URIs.

## Verification Checklist

- [ ] BLE scanning detects Nikon Z30 (verify via `recognizesDevice()`)
- [ ] BLE bonding and SnapBridge authentication completes
- [ ] GPS location data written to camera correctly
- [ ] Date/time synchronized to camera correctly
- [ ] Firmware version read via standard DIS
- [ ] Firmware update check via Nikon API (if available)
- [ ] Wi‑Fi connection to camera established
- [ ] PTP/IP InitCommandAck handshake completed
- [ ] WTU GUID pairing with 8-digit code
- [ ] ObjectAdded event received after photo capture
- [ ] Image downloaded and saved to Scoped Storage
- [ ] Background service maintains connection
- [ ] Disconnection recovery and reconnection
- [ ] All unit tests pass (`./gradlew test --tests "*Nikon*"`)
