# PTP/IP Image Transfer for Nikon Z30 (Z-Flow)

> **Status**: Minimal transport + mDNS + WTU pairing layer implemented (2026-05-05).
> Package: `app/.../ptp/`. Next: deploy + verify with real Z30.

This document describes the Wi‑Fi / PTP‑IP image transfer subsystem for the Nikon Z30.

## Key Discovery (2026-05-05)

The Nikon WTU uses **mDNS/Bonjour** for service discovery. The service name is derived from
`NkPtpEnum.cfg` in the WTU installation (`svcName="NkPtpEnumWT3"`). The camera browses for
this service after connecting to Wi‑Fi in "Connect to computer" mode.

**Z30 limitation**: The Z30 does NOT support Infrastructure mode (connecting to existing
routers — that's a flagship-only feature). However, when connected to a phone hotspot,
the camera still browses for the `_NkPtpEnumWT3._tcp` service. If we register this
service on the phone, the camera can discover us and proceed with WTU pairing.

**No WTU desktop app required** — we implement the WTU responder protocol directly on Android. This is a new capability extending CameraSync beyond its current BLE-only GPS/time sync into automated image reception.

Based on the Z‑Flow technical design (see [draft.md](../draft.md)).

---

## 1. Overview

### 1.1 Objective

Replicate and optimize Nikon's official **Wireless Transmitter Utility (WTU)** functionality on Android, enabling silent, automated, high-performance image transfer from the Z30 to the phone.

### 1.2 Core Use Cases

- **Instant transfer**: Photographer presses shutter on Z30; image transfers silently to phone background within 2 seconds
- **Auto-connection**: App detects camera on Wi‑Fi network and auto-completes PTP/IP handshake
- **High-speed preview**: Built-in image cache provides RAW/JPEG browsing faster than system gallery

---

## 2. Protocol Stack

### 2.1 PTP/IP (ISO 15740-4)

CameraSync implements the **PTP/IP Responder** role (phone side). The Z30 acts as the PTP/IP Initiator (camera side).

```
┌─────────────────────────────────────────────────────────┐
│  Application Layer                                       │
│  ├ PTP Operations (GetDeviceInfo, GetObject, etc.)       │
│  ├ PTP Events (ObjectAdded, DevicePropChanged, etc.)     │
│  └ Nikon Proprietary Extensions (WTU pairing, GUID auth) │
├─────────────────────────────────────────────────────────┤
│  PTP/IP Transport Layer                                  │
│  ├ Init Command / Init Command Ack                       │
│  ├ Init Event / Init Event Ack                           │
│  └ Connection number negotiation                         │
├─────────────────────────────────────────────────────────┤
│  TCP/IP                                                  │
│  ├ Camera (Client): connects to phone's port 15740       │
│  ├ Phone (Server): listens on port 15740                 │
│  └ Data port: dynamically assigned (PTP/IP negotiation)   │
└─────────────────────────────────────────────────────────┘
```

| Parameter | Value |
|-----------|-------|
| Transport | TCP |
| Command port | 15740 |
| Data port | Dynamic (PTP/IP `InitCommandAck` negotiation) |
| Camera IP | DHCP from phone hotspot |
| Phone IP | 192.168.43.1 (Android hotspot default) |
| Wi‑Fi band | 5 GHz preferred (802.11ac) |
| Encryption | WPA2/WPA3 (network-level, not PTP-level) |

### 2.2 Network Topology

**Phone as hotspot, camera as Wi‑Fi client**:

```
                     ┌──────────────────────┐
                     │  Phone (Android)      │
                     │  Hotspot: 192.168.43.1│
                     │  mDNS: _NkPtpEnumWT3 │
                     │  TCP listen: :15740   │
                     └──────────┬───────────┘
                                │ Wi‑Fi
            ┌───────────────────┼───────────────┐
            │                   │               │
     ┌──────┴──────┐     ┌──────┴──────┐
     │ Nikon Z30   │     │ Nikon Z30   │
     │ Wi‑Fi Client│     │ (future)    │
     │ mDNS browse │     │             │
     │ TCP → :15740│     │             │
     └─────────────┘     └─────────────┘
```

### 2.3 mDNS Service Discovery

The camera discovers the WTU responder via **Apple Bonjour (mDNS)**:

- **Service type**: `_NkPtpEnumWT3._tcp` (from `NkPtpEnum.cfg`: `svcName="NkPtpEnumWT3"`)
- **Port**: 15740 (standard PTP/IP)
- **Discovery**: Camera browses for this service after Wi‑Fi connection

We register this service using Android's `NsdManager` (see `NikonMdnsRegistrar.kt`).

### 2.4 Difference from SnapBridge Smart Device Mode

| Aspect | SnapBridge (smart device) | WTU/PTP-IP (this design) |
|--------|--------------------------|--------------------------|
| Wi‑Fi AP | Camera | Phone (hotspot) |
| Phone IP | DHCP from camera | Fixed: 192.168.43.1 |
| Discovery | BLE handoff | mDNS/Bonjour |
| Transfer protocol | SnapBridge proprietary | Standard PTP/IP |
| Internet on phone | ❌ Lost | ✅ Maintained |
| Initial setup | BLE pairing + auth | Phone hotspot (no USB needed!) |

---

## 3. Pairing & Authentication (WTU Protocol)

### 3.1 GUID-Based Pairing Flow

Nikon's WTU uses a permanent GUID to identify paired devices, eliminating the need for repeated verification after initial pairing.

```
┌────────────┐                         ┌────────────┐
│  Camera    │                         │  Phone     │
│  (Z30)     │                         │  (App)     │
└─────┬──────┘                         └─────┬──────┘
      │                                       │
      │  1. PTP/IP InitCommandRequest         │
      │  (camera hostname, GUID)              │
      │──────────────────────────────────────▶│
      │                                       │
      │  2. InitCommandAck                    │
      │  (phone GUID, connection number)      │
      │◀──────────────────────────────────────│
      │                                       │
      │  3. InitEventRequest                  │
      │──────────────────────────────────────▶│
      │                                       │
      │  4. InitEventAck                      │
      │◀──────────────────────────────────────│
      │                                       │
      │  ┌─ IF first pairing ──────────┐      │
      │  │ 5. Camera displays 8-digit  │      │
      │  │    verification code         │      │
      │  │ 6. User enters code in app  │      │
      │  │ 7. App sends MD5 challenge  │      │
      │  │    response to camera       │      │
      │  │ 8. Camera confirms pairing  │      │
      │  │ 9. Both store GUID          │      │
      │  └─────────────────────────────┘      │
      │                                       │
      │  10. Subsequent connections:          │
      │  GUID match → skip verification       │
      │                                       │
```

### 3.2 GUID Generation & Storage

```kotlin
// NikonWtPairingHandler.kt
class NikonWtPairingHandler(
    private val sharedPreferences: SharedPreferences
) {
    companion object {
        private const val KEY_PHONE_GUID = "nikon_wt_phone_guid"
    }

    /** Generates a permanent GUID for this phone installation. */
    fun getOrCreatePhoneGuid(): String {
        return sharedPreferences.getString(KEY_PHONE_GUID, null)
            ?: UUID.randomUUID().toString().also { guid ->
                sharedPreferences.edit().putString(KEY_PHONE_GUID, guid).apply()
            }
    }

    /** Stores a camera's GUID in the paired devices repository. */
    fun storeCameraGuid(macAddress: String, cameraGuid: String) {
        // Persist to PairedDevicesRepository
    }
}
```

### 3.3 MD5 Challenge-Response (8-Digit Code)

When the user enters the camera's displayed 8-digit code:

```kotlin
fun computeChallengeResponse(
    phoneGuid: String,
    cameraGuid: String,
    verificationCode: String,
): String {
    // Nikon WTU uses MD5(phoneGUID + cameraGUID + verificationCode + salt)
    val input = "$phoneGuid:$cameraGuid:$verificationCode:nikon_wt"
    val md5 = MessageDigest.getInstance("MD5")
    val hash = md5.digest(input.toByteArray())
    return hash.toHexString()
}
```

**Note**: The exact salt and concatenation order require confirmation from packet capture or decompilation of the official WTU application.

---

## 4. Image Transfer Pipeline

### 4.1 Event-Driven Architecture

```
Camera (Z30)                          Phone (App)
─────────────                         ──────────

1. Shutter pressed
2. Image saved to SD card
3. PTP ObjectAdded Event ─────────▶  4. Event received
   (0x4002, objectHandle)               │
                                        ├─ 5. GetObjectInfo(objectHandle)
                                        │     → filename, size, format
                                        │
                                        ├─ 6. GetPartialObject(objectHandle, 0..65536)
                                        │     → Read header + EXIF
                                        │     → Verify file type (JPEG/RAW)
                                        │
                                        ├─ 7. GetObject(objectHandle)
                                        │     → Full file transfer
                                        │
                                        └─ 8. Save to Scoped Storage
                                              → /Pictures/CameraSync/Z30/2026-05-05/
                                              → Update MediaStore
                                              → Index metadata (EXIF snapshot)
```

### 4.2 PTP Operation Codes

Key PTP operations used by the image pipeline:

| Operation | Code | Direction | Purpose |
|-----------|------|-----------|---------|
| `GetDeviceInfo` | `0x1001` | Phone → Camera | Get camera capabilities |
| `OpenSession` | `0x1002` | Phone → Camera | Open PTP session |
| `CloseSession` | `0x1003` | Phone → Camera | Close PTP session |
| `GetObjectHandles` | `0x1007` | Phone → Camera | List all stored objects |
| `GetObjectInfo` | `0x1008` | Phone → Camera | Get file metadata |
| `GetObject` | `0x1009` | Phone → Camera | Download full file |
| `GetPartialObject` | `0x101B` | Phone → Camera | Download file segment |
| `InitiateCapture` | `0x100E` | Phone → Camera | Trigger remote capture |
| `ObjectAdded` | `0x4002` | Camera → Phone | New file created event |

### 4.3 Kotlin PTP Implementation Outline

```kotlin
// PtpCommand.kt
enum class PtpOperation(val code: Int) {
    GET_DEVICE_INFO(0x1001),
    OPEN_SESSION(0x1002),
    CLOSE_SESSION(0x1003),
    GET_OBJECT_INFO(0x1008),
    GET_OBJECT(0x1009),
    GET_PARTIAL_OBJECT(0x101B),
    INITIATE_CAPTURE(0x100E),
}

// PtpEvent.kt
enum class PtpEvent(val code: Int) {
    OBJECT_ADDED(0x4002),
    DEVICE_PROP_CHANGED(0x4006),
    STORE_FULL(0x400A),
}

// PtpIpTransport.kt
class PtpIpTransport(
    private val host: String = "192.168.43.1",
    private val port: Int = 15740,
) {
    private var commandSocket: Socket? = null
    private var dataSocket: Socket? = null

    suspend fun connect(): PtpIpSession {
        commandSocket = Socket(host, port)
        // PTP/IP: 4-byte length prefix + PTP/IP packet
        val initCommand = buildInitCommandRequest()
        commandSocket!!.getOutputStream().write(initCommand)

        val response = commandSocket!!.getInputStream().readBytes()
        val connectionNumber = parseInitCommandAck(response)

        // Data socket
        dataSocket = Socket(host, connectionNumber)
        return PtpIpSession(commandSocket!!, dataSocket!!, connectionNumber)
    }
}

// PtpIpSession.kt
class PtpIpSession(
    private val commandSocket: Socket,
    private val dataSocket: Socket,
    private val connectionNumber: Int,
) {
    private val commandWriter = commandSocket.getOutputStream()
    private val commandReader = commandSocket.getInputStream()

    suspend fun getObject(objectHandle: Int): ByteArray {
        // Build PTP command packet
        val ptpPacket = buildPtpCommand(PtpOperation.GET_OBJECT, objectHandle)
        // Send via PTP/IP: 4-byte length + PTP packet
        sendCommand(ptpPacket)

        // Read response: first 12 bytes = PTP response header
        val response = readResponse()
        checkSuccess(response)

        // Data phase: read from data socket
        val dataLength = response.dataLength
        return dataSocket.getInputStream().readNBytes(dataLength.toInt())
    }

    /** Listen for PTP events in a coroutine. */
    fun eventFlow(): Flow<PtpEvent> = callbackFlow {
        while (isActive) {
            val packet = readPacket()
            val event = parseEvent(packet)
            if (event != null) {
                trySend(event)
            }
        }
    }
}
```

### 4.4 Image Receiver & Storage

```kotlin
// NikonImageReceiver.kt
class NikonImageReceiver(
    private val context: Context,
    private val ptpSession: PtpIpSession,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Starts listening for ObjectAdded events and downloading images. */
    fun startReceiving() {
        scope.launch {
            ptpSession.eventFlow().collect { event ->
                when (event) {
                    is PtpEvent.OBJECT_ADDED -> {
                        downloadAndSave(event.objectHandle)
                    }
                    else -> { /* ignore other events */ }
                }
            }
        }
    }

    private suspend fun downloadAndSave(objectHandle: Int) {
        // 1. Get file metadata
        val objectInfo = ptpSession.getObjectInfo(objectHandle)

        // 2. Get file extension from format code
        val extension = objectInfo.fileExtension  // "JPG", "NEF", etc.

        // 3. Get partial header for EXIF preview
        val header = ptpSession.getPartialObject(objectHandle, 0, 65536)

        // 4. Get full file
        val fullFile = ptpSession.getObject(objectHandle)

        // 5. Save to Scoped Storage
        val cameraName = "Z30"
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val relativePath = "Pictures/CameraSync/$cameraName/$dateFolder"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, objectInfo.filename)
            put(MediaStore.Images.Media.MIME_TYPE, objectInfo.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return

        resolver.openOutputStream(uri)?.use { output ->
            output.write(fullFile)
        }
    }
}
```

---

## 5. File Storage & Organization

### 5.1 Storage Path Convention

```
Pictures/CameraSync/
├── Z30/
│   ├── 2026-05-01/
│   │   ├── DSC_0001.JPG      (JPEG preview)
│   │   ├── DSC_0001.NEF      (RAW file, if enabled)
│   │   ├── DSC_0002.JPG
│   │   └── ...
│   ├── 2026-05-02/
│   │   └── ...
│   └── ...
└── Z8/
    └── ...
```

### 5.2 File Conventions

| Format | Extension | MIME Type | Note |
|--------|-----------|-----------|------|
| JPEG | `.JPG` | `image/jpeg` | Full-size, camera-processed |
| RAW (NEF) | `.NEF` | `image/x-nikon-nef` | Nikon raw format |
| HEIF | `.HEIC` | `image/heic` | If camera set to HEIF mode |

### 5.3 Metadata Indexing

```kotlin
// Room entity for fast image browsing
@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val objectHandle: Int,
    val filename: String,
    val filePath: String,
    val sizeBytes: Long,
    val captureTime: Long,        // EXIF DateTimeOriginal
    val iso: Int?,
    val aperture: Float?,         // f-number
    val shutterSpeed: String?,    // "1/250"
    val syncStatus: SyncStatus,   // PENDING / SUCCESS / FAILED
)

enum class SyncStatus { PENDING, SUCCESS, FAILED }
```

---

## 6. Background Service Integration

### 6.1 Extended Service Architecture

The existing `MultiDeviceSyncService` is extended to manage PTP/IP connections:

```
┌─────────────────────────────────────────────────────────┐
│  MultiDeviceSyncService (Foreground Service)              │
│                                                           │
│  ┌─ LocationCollectionCoordinator (GPS)                   │
│  ├─ MultiDeviceSyncCoordinator (BLE sync)                 │
│  │   ├ RicohConnectionManager                             │
│  │   ├ SonyConnectionManager                              │
│  │   └ NikonConnectionManager  ← BLE GPS/time sync        │
│  │                                                         │
│  └─ PtpIpServiceManager (NEW)                              │
│      ├ PtpIpTransport (TCP 15740 listener)                 │
│      ├ NikonWtPairingHandler (GUID auth)                   │
│      └ NikonImageReceiver (download pipeline)              │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

### 6.2 Wi‑Fi Handoff Trigger

When the Nikon Z30 is within BLE range and the user has enabled image transfer:

```kotlin
// NikonConnectionDelegate
override suspend fun onConnected(peripheral: Peripheral, camera: Camera) {
    super.onConnected(peripheral, camera)

    // If Wi‑Fi transfer is enabled and camera supports auto-send:
    if (cameraPreferences.isImageTransferEnabled) {
        triggerWifiHandoff(camera)
    }
}

private suspend fun triggerWifiHandoff(camera: Camera) {
    // 1. Write Wi‑Fi wake-up command to SnapBridge control point
    // 2. Camera enables Wi‑Fi and connects to phone hotspot
    // 3. PtpIpServiceManager detects the TCP connection on port 15740
    // 4. WTU pairing completes (GUID-based, skip verification if already paired)
    // 5. Image receiver starts listening for ObjectAdded events
}
```

### 6.3 Power Management

| State | BLE | Wi‑Fi | Battery Impact |
|-------|-----|-------|---------------|
| Disconnected | Off | Off | None |
| BLE Connected (standby) | Active | Off | Minimal |
| Wi‑Fi Active (transferring) | Active | Active | High |
| Wi‑Fi Idle (no transfer for 5 min) | Active | Disconnect | Minimal |

---

## 7. Error Handling & Recovery

### 7.1 Connection Failures

| Error | Cause | Recovery |
|-------|-------|----------|
| TCP connection refused | Camera Wi‑Fi not ready | Exponential backoff retry (1s, 2s, 4s, 8s) |
| PTP session timeout | Camera entered standby | BLE wake command → reconnect |
| GUID mismatch | Camera re-paired or reset | Fall back to full 8-digit code pairing |
| MD5 challenge rejected | Wrong verification code | Prompt user to re-enter code |
| Write to Scoped Storage failed | Permission denied | Request MANAGE_EXTERNAL_STORAGE |
| Storage full | SD card / phone full | Notify user, pause reception |

### 7.2 TCP Keep-Alive

```kotlin
// PtpIpTransport.kt
private fun configureKeepAlive(socket: Socket) {
    // TCP keep-alive to detect camera sleep
    socket.keepAlive = true
    // Detect disconnection within ~30 seconds
    socket.soTimeout = 10_000  // Read timeout
}
```

### 7.3 Exponential Backoff Reconnection

```kotlin
suspend fun reconnectWithBackoff(maxAttempts: Int = 5) {
    var delay = 1_000L
    for (attempt in 1..maxAttempts) {
        try {
            connect()
            return
        } catch (e: IOException) {
            if (attempt == maxAttempts) throw e
            delay(delay)
            delay *= 2  // 1s → 2s → 4s → 8s → 16s
        }
    }
}
```

---

## 8. Performance Targets

| Metric | Target | Hardware Assumption |
|--------|--------|-------------------|
| JPEG transfer (8 MB) | < 1.5s | 5 GHz Wi‑Fi, < 1m distance |
| RAW transfer (25 MB) | < 5s | 5 GHz Wi‑Fi |
| PTP/IP handshake | < 500ms | After Wi‑Fi connection established |
| ObjectAdded latency | < 200ms | From shutter press to event at phone |
| BLE wake → Wi‑Fi ready | < 3s | Camera cold start |
| Continuous transfer | 30+ images/minute | Burst mode |

---

## 9. Development Milestones

### Phase 1: Foundation
- [ ] PTP/IP transport layer (TCP 15740 listener)
- [ ] PTP command/response packet marshalling
- [ ] Manual connection to camera (user enters IP)
- [ ] `GetDeviceInfo` and `GetObject` basic operations

### Phase 2: Pairing
- [ ] WTU GUID generation and storage
- [ ] 8-digit verification code UI
- [ ] MD5 challenge-response implementation
- [ ] Auto-reconnection using stored GUID

### Phase 3: Automated Sync
- [ ] `ObjectAdded` event listener
- [ ] Automated download pipeline (header → full file)
- [ ] Scoped Storage integration
- [ ] Background service Wi‑Fi management
- [ ] BLE-triggered Wi‑Fi handoff

### Phase 4: Optimization
- [ ] EXIF metadata indexing (Room database)
- [ ] Thumbnail cache for gallery browsing
- [ ] RAW preview support
- [ ] Battery-aware Wi‑Fi scheduling
- [ ] Hotspot auto-configuration (user setup wizard)

---

## 10. References

- [PTP/IP Specification (ISO 15740-4)](https://www.iso.org/standard/71944.html)
- [gPhoto2 libgphoto2](https://github.com/gphoto/libgphoto2) — Mature open-source PTP implementation
- [qDslrDashboard](https://dslrdashboard.info/) — Third-party app with Nikon WTU protocol support
- [Nikon Wireless Transmitter Utility](https://downloadcenter.nikonimglib.com/en/products/169/Wireless_Transmitter_Utility.html) — Reference implementation (Windows/macOS)
- [draft.md](../draft.md) — Original Z‑Flow technical design document
- [NIKON_VENDOR_ADAPTATION.md](NIKON_VENDOR_ADAPTATION.md) — How to integrate this with CameraSync's architecture
- [BLE_GPS_SYNC.md](BLE_GPS_SYNC.md) — BLE GPS/time sync (coexists with PTP/IP)
