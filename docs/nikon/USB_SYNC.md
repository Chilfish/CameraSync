# USB Wired Photo Sync — Nikon Z30

> **Status**: MVP verified (2026-05-05). Next: integrate into app architecture.

This document describes the USB wired photo sync subsystem for Nikon Z30 — the third and successful approach after BLE SnapBridge (private protocol, can't reverse) and Wi‑Fi PTP‑IP (Z30 lacks Infrastructure mode).

## 1. Why USB Wins

| Approach | Protocol | Auth | Z30 Support | Status |
|----------|----------|------|-------------|--------|
| BLE SnapBridge | Proprietary BLE | 8-digit pairing + auth handshake | ❌ Requires reverse engineering obfuscated code | Abandoned |
| Wi‑Fi PTP‑IP | PTP/IP over TCP :15740 | WTU GUID + MD5 challenge | ❌ Z30 lacks Infrastructure mode | Abandoned |
| **USB MTP/PTP** | Standard MTP over USB | **None** (physical = secure) | ✅ Works like 像素蛋糕 | ✅ MVP verified |

Android's built-in `android.mtp.MtpDevice` API handles the MTP/PTP protocol natively — no protocol reverse-engineering, no pairing, no auth.

## 2. USB Permission Flow (Critical)

### 2.1 The Problem

Android 14+ enforces strict rules for USB access. The camera **must** go through the system USB permission dialog before the app can open an MTP connection. Key pitfalls:

| Pitfall | Symptom | Fix |
|---------|---------|-----|
| Implicit PendingIntent | Crash on `requestPermission()` | `Intent.setPackage(packageName)` |
| `RECEIVER_NOT_EXPORTED` | Permission broadcast blocked | Must use `RECEIVER_EXPORTED` — the system sends the permission result via PendingIntent, which counts as "external" |
| `FLAG_MUTABLE` alone | May fail on some OEMs | Use `FLAG_MUTABLE \| FLAG_UPDATE_CURRENT` |
| Skipping the dialog | `hasPermission()` returns false, `openDevice()` fails silently | Always call `requestPermission()` first |

### 2.2 Correct Permission Flow

```kotlin
// 1. Detect device
val device: UsbDevice = ...

// 2. Build explicit PendingIntent
val intent = Intent(ACTION_USB_PERMISSION).apply {
    setPackage(context.packageName) // MUST be explicit
}
val pi = PendingIntent.getBroadcast(
    context, 0, intent,
    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

// 3. Request — system shows dialog
usbManager.requestPermission(device, pi)

// 4. Register receiver with RECEIVER_EXPORTED
context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)

// 5. On permission granted → open MTP
val conn = usbManager.openDevice(device)
val mtp = MtpDevice(device)
mtp.open(conn)
```

### 2.3 Manifest Requirements

```xml
<!-- Feature: NOT required (phones without USB host can still install) -->
<uses-feature android:name="android.hardware.usb.host" android:required="false" />

<!-- On MainActivity -->
<intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
</intent-filter>
<meta-data
    android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
    android:resource="@xml/nikon_usb_device_filter" />
```

## 3. MTP Photo Enumeration (Critical)

### 3.1 The `getObjectHandles` Trap

`MtpDevice.getObjectHandles(storageId, format, parentHandle)` returns **only direct children** of `parentHandle`. It does NOT recurse.

```kotlin
// ❌ WRONG — only root-level objects (the DCIM folder itself, not photos inside)
mtp.getObjectHandles(storageId, 0, 0)

// ❌ ALSO WRONG — there is no ALL_OBJECTS sentinel for the parent parameter; -1 is invalid
mtp.getObjectHandles(storageId, 0, -1)
```

### 3.2 Correct: BFS Folder Traversal

```kotlin
fun listPhotos(mtp: MtpDevice, storageId: Int): List<PhotoInfo> {
    val photos = mutableListOf<PhotoInfo>()
    val folderQueue = ArrayDeque<Int>()
    folderQueue.add(0) // start from root

    while (folderQueue.isNotEmpty()) {
        val parent = folderQueue.removeFirst()
        // format=0 = all formats, parent=actual folder handle
        val handles = mtp.getObjectHandles(storageId, 0, parent) ?: continue

        for (handle in handles) {
            val info = mtp.getObjectInfo(handle) ?: continue
            if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
                folderQueue.add(handle) // recurse into subfolder
            } else {
                photos.add(info.toPhotoInfo(handle))
            }
        }
    }
    return photos
}
```

### 3.3 Multiple Storages

The Z30 may expose multiple storage units (internal memory + SD card). You must scan ALL of them:

```kotlin
val storageIds = mtp.storageIds ?: intArrayOf()
for (id in storageIds) {
    val photos = listPhotos(mtp, id)
    // ...
}
```

## 4. Photo Download

### 4.1 Import + Scoped Storage

On API 33+, writing to external storage requires `MediaStore`. The approach:

```kotlin
// 1. Create MediaStore entry as PENDING
val cv = ContentValues().apply {
    put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraSync/Nikon Z30/$dateFolder")
    put(MediaStore.Images.Media.IS_PENDING, 1)
}
val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)

// 2. Download to temp file via importFile()
mtp.importFile(photo.handle, tempFile.absolutePath)

// 3. Copy to MediaStore output stream
resolver.openOutputStream(uri)?.use { output ->
    FileInputStream(tempFile).use { it.copyTo(output) }
}

// 4. Commit
cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
resolver.update(uri, cv, null, null)
```

### 4.2 File Size Handling

`MtpObjectInfo.compressedSize` is `Int` (32-bit), but photos can exceed 2GB. Use unsigned conversion:

```kotlin
val size: Long = info.compressedSize.toLong() and 0xFFFFFFFFL
```

## 5. MTP API Quirks

### 5.1 `MtpDeviceInfo` Fields

| Field | Access | Notes |
|-------|--------|-------|
| `manufacturer` | `.manufacturer` | Non-null String |
| `model` | `.model` | Non-null String |
| `serialNumber` | `.serialNumber` | Nullable String |
| `version` | `.version` | String (firmware version) |
| `operationsSupported` | `.operationsSupported` | `IntArray?` — PTP operation codes |
| `eventsSupported` | `.eventsSupported` | `IntArray?` — PTP event codes |
| `vendorExtension*` | N/A | Not exposed in public API |

### 5.2 Missing Constants

Some format constants are only defined in newer API levels:

| Format | Value | SDK constant |
|--------|-------|-------------|
| HEIF | 0x380C | `MtpConstants.FORMAT_HEIF` (API 30+) |
| NEF (Nikon RAW) | 0xB103 | Not in SDK — use raw |

### 5.3 `open()` Requires `UsbDeviceConnection`

```kotlin
val conn = usbManager.openDevice(usbDevice) // Get UsbDeviceConnection first
val mtp = MtpDevice(usbDevice)
mtp.open(conn) // Pass connection, not device
```

## 6. Nikon Z30 USB Identifiers

| Property | Value |
|----------|-------|
| USB Vendor ID | `0x04B0` (1200) — Nikon Corporation |
| USB Class | Typically 0 (per-interface) or 6 (imaging) |
| MTP Interface | Class 6 (Imaging), Subclass 0x01, Protocol 0x01 |
| PTP Interface | Class 6 (Imaging), Subclass 0x01, Protocol 0x01 (same as MTP) |

## 7. File Organization

```
Pictures/CameraSync/Nikon Z30/
├── 2026-05-01/
│   ├── DSC_0001.JPG
│   ├── DSC_0001.NEF
│   └── ...
├── 2026-05-02/
│   └── ...
└── ...
```

- Date folder derived from EXIF/creation date (not download date)
- MIME types: `image/jpeg`, `image/x-nikon-nef`, `image/heic`, `image/png`

## 8. Architecture: Current vs Target

### Current (MVP / Debug Screen)
```
UsbSyncScreen (debug UI)
  └── UsbSyncViewModel (AndroidViewModel, manual ops)
      └── NikonUsbManager (raw MTP wrapper)
```

### Target (Integrated)
```
DevicesListScreen → shows USB camera status
  └── UsbSyncService (Foreground Service)
      ├── UsbSyncCoordinator (auto-sync lifecycle)
      │   ├── NikonUsbManager (MTP operations)
      │   └── PhotoSyncManager (dedup, storage, metadata)
      └── Notification (sync progress)
```

## 9. Key Files

| File | Role |
|------|------|
| `usb/NikonUsbManager.kt` | All MTP operations: open/close, camera info, storage, photo list, download |
| `usb/UsbSyncViewModel.kt` | USB detection, permission flow, MTP state machine, MediaStore save |
| `usb/UsbSyncScreen.kt` | Debug UI: status, camera info, photo list, download, log console |
| `res/xml/nikon_usb_device_filter.xml` | USB device filter (Nikon VID 0x04B0) |
| `res/drawable/ic_usb_24dp.xml` | USB icon |

## 10. Verified With

- Device: Nikon Z30
- Cable: USB-C to USB-C (C2C)
- Phone: Xiaomi (MIUI / Android 14)
- Camera USB mode: MTP/PTP
