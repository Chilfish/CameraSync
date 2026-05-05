# Nikon Z30 Camera Support Documentation

## Document Index

| Document | Scope |
|----------|--------|
| **[USB_SYNC.md](USB_SYNC.md)** | **USB wired photo sync — the working approach.** Architecture, permission flow, MTP enumeration, file download, pitfalls. ✅ MVP verified. |
| **[NIKON_VENDOR_ADAPTATION.md](NIKON_VENDOR_ADAPTATION.md)** | **How to adapt Nikon Z30 to CameraSync's existing multi-vendor architecture.** BLE vendor implementation guide. |
| **[BLE_GPS_SYNC.md](BLE_GPS_SYNC.md)** | **BLE GPS/date-time synchronization.** SnapBridge protocol research notes (deprioritized). |
| **[PTP_IMAGE_TRANSFER.md](PTP_IMAGE_TRANSFER.md)** | **Wi‑Fi / PTP‑IP image transfer design.** TCP port 15740, WTU pairing — deprecated in favor of USB. |

## Current Status (2026-05-05)

### ✅ Working
- **USB wired photo sync**: Detect Z30 via USB, MTP connection, enumerate & download photos from SD card
- **BLE device recognition**: Z30 is recognized as a Nikon device via manufacturer ID 0x0399 + name prefix "Z_"
- **Debug UI**: Functional USB sync screen with camera info, photo list, download, log console

### 📋 Planned (Phase 7)
- Replace debug screen with proper settings UI
- Foreground service: auto-start USB sync on cable connect
- Background polling: detect new photos automatically
- Notification: transfer progress in notification shade
- Integration with `DevicesListScreen`: show USB camera status alongside BLE devices

### ❌ Deprecated
- **BLE GPS/time sync**: Requires reverse-engineering SnapBridge auth — not feasible without packet capture
- **Wi‑Fi PTP‑IP**: Z30 lacks Infrastructure mode — blocked at camera firmware level

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  CameraSync App                                           │
│                                                           │
│  ┌─ BLE Layer (Richo, Sony) ──────────────────────────┐  │
│  │ GPS sync • DateTime sync • Auto-reconnect            │  │
│  └─────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌─ USB Layer (Nikon Z30) ─── NEW ────────────────────┐  │
│  │ MTP/PTP over USB • Photo enumeration • Download     │  │
│  │ MediaStore save • Auto-detect on cable connect      │  │
│  └─────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌─ Common ───────────────────────────────────────────┐  │
│  │ DevicesList • Notifications • Foreground Service     │  │
│  │ Scoped Storage • Preferences                        │  │
│  └─────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

## Nikon Z30 Transport Comparison

| Feature | BLE (SnapBridge) | Wi‑Fi (PTP‑IP) | **USB (MTP)** |
|---------|-----------------|----------------|---------------|
| Image transfer | ❌ Proprietary | ❌ No Infra mode | ✅ Standard MTP |
| GPS sync | ❌ Auth required | — | N/A (EXIF metadata) |
| DateTime sync | ❌ Auth required | — | N/A |
| Pairing required | Yes (8-digit) | Yes (WTU GUID) | **No** (physical) |
| Protocol reverse needed | Yes | Partial | **No** (platform API) |

## Quick Reference: Z30 USB

- **USB Vendor ID**: `0x04B0` (Nikon Corporation)
- **Android API**: `android.mtp.MtpDevice` (API 24+, NOT deprecated)
- **Connection**: `UsbManager.openDevice()` → `MtpDevice.open(UsbDeviceConnection)`
- **Photo enumeration**: BFS folder traversal (NOT recursive by default)
- **Download**: `MtpDevice.importFile()` to temp → copy to `MediaStore`
- **Storage**: `Pictures/CameraSync/Nikon Z30/YYYY-MM-DD/`
