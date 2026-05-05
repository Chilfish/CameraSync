# BLE GPS & Date/Time Synchronization for Nikon Z30

This document describes how CameraSync synchronizes GPS location and date/time to the Nikon Z30 over BLE using the SnapBridge protocol.

## Protocol Context

The Nikon Z30 uses **SnapBridge** — Nikon's proprietary BLE protocol — for persistent phone-camera communication. Unlike traditional BLE GATT designs where the phone reads/writes individual characteristics, SnapBridge uses a command–response exchange over specific BLE characteristics.

### Key Concepts

- **Persistent bonding**: Once paired, the phone remains connected even when the camera is off (low-power advertising)
- **Command/response exchange**: The phone writes commands to a "control point" characteristic and receives responses via notification on a "response" characteristic
- **GPS inject**: The phone pushes GPS data to the camera; the camera does NOT poll
- **Auto time sync**: Time is synchronized during connection establishment, then periodically

## BLE Service Architecture (SnapBridge)

```
┌────────────────────────────────────────────────────────┐
│  Nikon Z30 BLE Profile (SnapBridge)                      │
├────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────────────────────────────────┐          │
│  │ Device Information Service (0x180A)        │          │
│  │  ├ Model Number String (0x2A24)            │          │
│  │  ├ Serial Number String (0x2A25)           │          │
│  │  ├ Firmware Revision String (0x2A26)       │          │
│  │  ├ Hardware Revision String (0x2A27)       │          │
│  │  ├ Manufacturer Name String (0x2A29)      │          │
│  │  └ Software Revision String (0x2A28)       │          │
│  └──────────────────────────────────────────┘          │
│                                                          │
│  ┌──────────────────────────────────────────┐          │
│  │ Battery Service (0x180F)                   │          │
│  │  └ Battery Level (0x2A19)                  │          │
│  └──────────────────────────────────────────┘          │
│                                                          │
│  ┌──────────────────────────────────────────┐          │
│  │ SnapBridge Control Service                 │          │
│  │  ├ Control Point (Write)                   │          │
│  │  │  └ Write commands (GPS, time, auth)     │          │
│  │  └ Notification (Notify)                    │          │
│  │     └ Receive responses & camera events    │          │
│  └──────────────────────────────────────────┘          │
│                                                          │
└────────────────────────────────────────────────────────┘
```

**Note**: The exact SnapBridge service and characteristic UUIDs are proprietary and require BLE packet capture for full documentation. Nikon uses obfuscated UUIDs. The table above shows the logical architecture.

## GPS Location Synchronization

### Data Flow

```
Phone (CameraSync)                    Camera (Nikon Z30)
─────────────────                    ──────────────────
                                      1. Connect BLE
                                      2. Authenticate (SnapBridge)
                                      3. Subscribe to notifications

4. Get GPS fix (FusedLocationProvider)
5. Encode location data
6. Write to Control Point ──────────▶ 7. Receive location
8. Wait for confirmation ◀────────── 8. Send ACK via notify
                                      9. Apply GPS to EXIF
10. Schedule next sync (60s interval)

                                      10. Auto-send images (optional)
```

### GPS Data Encoding

Nikon SnapBridge uses a compact binary format for GPS data. Based on community reverse engineering, the format is approximately 18 bytes:

| Byte Offset | Field | Type | Notes |
|-------------|-------|------|-------|
| 0-3 | Latitude | IEEE 754 float (BE) | Decimal degrees |
| 4-7 | Longitude | IEEE 754 float (BE) | Decimal degrees |
| 8-11 | Altitude | IEEE 754 float (BE) | Meters |
| 12-13 | Year | UInt16 (LE) | |
| 14 | Month | UInt8 | 1-12 |
| 15 | Day | UInt8 | 1-31 |
| 16 | Hour | UInt8 | 0-23 (UTC) |
| 17 | Minute | UInt8 | 0-59 |
| 18 | Second | UInt8 | 0-59 |

**Important**: Nikon cameras use UTC for GPS timestamps. The phone must convert local time to UTC before encoding.

### Sync Interval

SnapBridge updates GPS on the following schedule:
- **Every 15 seconds** when SnapBridge is in "active" mode (user has app open)
- **Every 60 seconds** in background mode (camera in standby)
- **On-demand** when the camera wakes from standby

CameraSync should align its GPS sync interval (60s) with the background mode interval to minimize unnecessary writes.

## Date/Time Synchronization

### When It Happens

Nikon SnapBridge synchronizes time during:
1. **Initial connection** — immediately after BLE bonding + authentication
2. **Periodic refresh** — every 5 minutes while connected
3. **Camera wake** — when the camera exits standby mode

### Time Encoding

SnapBridge uses a simple 7-byte format, identical to the Ricoh format:

| Byte | Field | Type | Notes |
|------|-------|------|-------|
| 0-1 | Year | Int16 (LE) | e.g., 2026 |
| 2 | Month | UInt8 | 1-12 |
| 3 | Day | UInt8 | 1-31 |
| 4 | Hour | UInt8 | 0-23 (local time) |
| 5 | Minute | UInt8 | 0-59 |
| 6 | Second | UInt8 | 0-59 |

**Note**: Unlike GPS timestamps, date/time sync uses **local time** (not UTC). The camera handles timezone information separately.

## SnapBridge Authentication

Before GPS or time sync can occur, SnapBridge requires an authentication exchange. This is the Nikon equivalent of Sony's `EE01` pairing initialization.

### Authentication Flow

```
1. BLE Bonding (Android OS level)
   └─ Standard BLE pairing (Just Works or Passkey)

2. SnapBridge Authentication (App level)
   ├─ Phone writes auth init command to Control Point
   ├─ Camera sends challenge via Notification
   ├─ Phone computes response (based on shared secret + challenge)
   ├─ Phone writes response to Control Point
   └─ Camera confirms authentication via Notification

3. Post-Auth
   ├─ Read device info (model, firmware, serial)
   ├─ Sync time
   └─ Enable GPS sync
```

### Vendor Pairing in CameraSync

The SnapBridge authentication maps to CameraSync's `requiresVendorPairing` flag:

```kotlin
// NikonCameraVendor.kt
override fun getCapabilities(): CameraCapabilities = CameraCapabilities(
    requiresVendorPairing = true,  // SnapBridge auth required
    // ...
)
```

The `NikonProtocol.getPairingInitData()` returns the SnapBridge auth init command:

```kotlin
override fun getPairingInitData(): ByteArray? =
    byteArrayOf(0x06, 0x00, 0x01, 0x00, 0x00, 0x00)
```

The full auth handshake is handled by `NikonConnectionDelegate.onConnected()`.

## Implementation in CameraSync's Architecture

The Nikon BLE sync flow maps directly to CameraSync's existing architecture:

```
┌───────────────────────────────────────────────┐
│  MultiDeviceSyncCoordinator                     │
│  (vendor-agnostic, 60s sync loop)               │
└───────────────────┬───────────────────────────┘
                    │
                    ▼
┌───────────────────────────────────────────────┐
│  KableCameraConnection                          │
│  ├─ syncDateTime() → delegate.syncDateTime()    │
│  ├─ syncLocation() → delegate.syncLocation()    │
│  └─ initializePairing() → SnapBridge auth       │
└───────────────────┬───────────────────────────┘
                    │
                    ▼
┌───────────────────────────────────────────────┐
│  NikonConnectionDelegate                        │
│  ├─ onConnected() → auth + subscribe            │
│  ├─ syncLocation() → GATT write GPS data        │
│  └─ syncDateTime() → GATT write time data       │
└───────────────────────────────────────────────┘
```

## Comparison with Other Vendors

| Feature | Ricoh | Sony | Nikon Z30 |
|---------|-------|------|-----------|
| GPS service | Dedicated GeoTag service | DD Location service | SnapBridge control point |
| GPS format | 32 bytes (double, mixed endian) | 91/95 bytes (int, BE) | 18 bytes (float, BE) |
| Time format | 7 bytes (LE/BE mix) | 13 bytes (BE, CC13) | 7 bytes (LE/BE mix) |
| Geo-tagging toggle | Yes (1 byte) | No (built into DD11) | No |
| Auth required | No | Yes (EE01) | Yes (SnapBridge) |
| MTU | Default | 158 bytes | Default (20 bytes) |
| Notification subscribe | Handshake notify | DD01 status | SnapBridge notify |

## Test Specifications

### GPS Encode/Decode Round-trip Test

```kotlin
@Test
fun `GPS round trip preserves latitude longitude altitude`() {
    val original = GpsLocation(35.6895, 139.6917, 40.0, ZonedDateTime.now(ZoneOffset.UTC))
    val encoded = NikonProtocol.encodeLocation(original)
    val decoded = NikonProtocol.decodeLocation(encoded)

    assertTrue(decoded.contains("35.689"))
    assertTrue(decoded.contains("139.691"))
    assertTrue(decoded.contains("40.0"))
}
```

### DateTime Round-trip Test

```kotlin
@Test
fun `dateTime round trip is correct`() {
    val original = ZonedDateTime.of(2026, 5, 5, 14, 30, 0, 0, ZoneId.systemDefault())
    val encoded = NikonProtocol.encodeDateTime(original)
    val decoded = NikonProtocol.decodeDateTime(encoded)

    assertEquals("2026-05-05 14:30:00", decoded)
}
```

## References

- [Nikon SnapBridge API documentation](https://www.nikon.com/snapbridge/) — Official overview
- BLE HCI Snoop Log — Capture on Android Developer Options for UUID discovery
- [PTP_IMAGE_TRANSFER.md](PTP_IMAGE_TRANSFER.md) — Wi‑Fi image transfer after BLE sync is established
