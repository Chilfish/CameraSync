# CameraSync — Nikon Z30 Support: Task Plan & Progress Log

> Branch: `nikon-feature` | Started: 2026-05-05 | Updated: 2026-05-05

---

## Phase 0: POC — Device Scan & Recognition ✅

- [x] `NikonGattSpec` — standard DIS / Battery service UUIDs as scan filters
- [x] `NikonProtocol` — GPS (18-byte float) / DateTime (7-byte) encode/decode
- [x] `NikonConnectionDelegate` — extends `DefaultConnectionDelegate`, SnapBridge auth as TODO
- [x] `NikonCameraVendor` — mfr ID `0x0399` + name prefix recognition, Z/D-series model extraction
- [x] Register in `AppGraph.kt`
- [x] Unit tests (3 files, 454 total passing)

---

## Phase 1: CompanionDeviceManager Pairing Flow Fix ✅

- [x] **Bug**: CompanionDeviceManager returns type unknown — `extractCameraFromIntent` gets `null`
- [x] **Mfr ID**: fixed from `0x00E0` → `0x0399` (verified from Z30 advertisement)
- [x] **Name prefix**: added `Z_` to scan filter (Z30 advertises as `Z_30_8271278`)
- [x] **SnapBridge UUID**: discovered `0000DE00-3DD4-4255-8D62-6DC7B9BD5561` — added to scan filter
- [x] **Bonding rejected by camera** — Z30 refuses standard BLE bonding (requires SnapBridge auth)
  - → Deprioritized in favor of USB wired sync (Phase 6)

---

## Phase 6: USB Wired Photo Sync ✅

> **Strategy**: C2C data cable → Android USB Host → `android.mtp.MtpDevice` API.
> No protocol reverse-engineering, no pairing/auth — physical connection = trusted.
> Commercial precedent: 像素蛋糕 App supports Z30 via C2C.
>
> **Key pitfalls discovered & resolved:**
> - Android 14 `registerReceiver` must specify `RECEIVER_EXPORTED` (USB permission is sent by system)
> - `PendingIntent` for USB permission must use explicit Intent (`setPackage`)
> - `MtpDevice.getObjectHandles()` returns ONLY direct children — requires manual BFS recursion
> - `MtpDevice.open()` takes `UsbDeviceConnection`, not `UsbDevice`
> - Z30 may expose multiple storages (internal + SD); must scan all
> - Use `format=0` for "all formats" (not `0xFFFFFFFF`)
> - `MtpObjectInfo.compressedSize` is Int (may overflow for >2GB files)
>
> **Documentation**: See [docs/nikon/USB_SYNC.md](nikon/USB_SYNC.md)

### 6.1 MVP — Manual USB Connection + Photo Download ✅

- [x] `NikonUsbManager` — MTP open/close, camera info, storage enumeration, recursive photo listing, download
- [x] `UsbSyncViewModel` — dynamic BroadcastReceiver, USB permission flow, MTP state machine, MediaStore save
- [x] `UsbSyncScreen` — Debug UI: connection status card, camera info card (mfr/model/serial/version/ops/events/storage), photo list with format labels, download progress bar, log console with monospace fonts
- [x] `nikon_usb_device_filter.xml` — USB device filter (Nikon VID `0x04B0`)
- [x] `NavRoute.UsbSync` — route registration
- [x] `MainActivity` — wire UsbSyncScreen + "USB 同步" menu entry
- [x] `AndroidManifest.xml` — `usb.host` feature + `USB_DEVICE_ATTACHED` intent-filter + meta-data
- [x] `ic_usb_24dp.xml` — USB icon drawable (Material Design vector)
- [x] `strings.xml` — 20+ USB-related Chinese strings
- [x] MediaStore save path: `Pictures/CameraSync/Nikon Z30/YYYY-MM-DD/`
- [x] Verified with Z30 + C2C cable: connection ✓, camera info ✓, photo listing ✓, download ✓

---

## Phase 7: Integration — Formalize USB Sync ✅ COMPLETED

> **Goal**: Evolve the USB sync from a standalone debug screen into a proper feature that
> integrates with CameraSync's existing architecture. The app currently supports two BLE
> workflows (GPS/DateTime sync for Ricoh & Sony). USB photo sync for Nikon is a NEW transport
> and a NEW capability (image transfer, not location sync).
>
> **Architecture insight**: The existing `CameraVendor` interface is BLE-specific (uses
> `Peripheral`, `CameraGattSpec`, BLE UUIDs). USB/MTP is a fundamentally different transport.
> Rather than forcing it into the BLE vendor abstraction, USB sync will live as a parallel
> subsystem that integrates at the UI and service layers.
>
> **Integration points**:
> - `DevicesListScreen` — show USB camera status alongside BLE devices
> - `UsbSyncService` (Foreground Service) — auto-start on cable connect, silent sync
> - Notifications — sync progress, transfer complete
> - Settings — per-camera USB sync preferences

### 7.1 Formalize USB Sync Screen
- [x] Replace debug-style layout with proper settings-style UI
- [x] Persistent preferences: auto-sync toggle, last-synced timestamp
- [x] Transfer history: synced photo count, last sync time
- [x] Manual sync trigger button
- [x] Connection status indicator (disconnected / connected / syncing)
- [x] Error recovery UI (reconnect, retry)

### 7.2 Foreground Service Integration
- [x] `UsbSyncService` — follows `MultiDeviceSyncService` patterns:
  - `CoroutineScope(Dispatchers.IO + SupervisorJob)`
  - `ServiceCompat.startForeground()` with `connectedDevice` type
  - `ACTION_SYNC/STOP` intent actions
  - `Binder` inner class for activity binding
- [x] Auto-start on `ACTION_USB_DEVICE_ATTACHED`
- [x] Idle detection: stop service after USB disconnected

### 7.3 Notification Integration
- [x] Notification channel: `USB_SYNC_CHANNEL` (IMPORTANCE_LOW)
- [x] "Nikon USB 同步 — N 张照片" persistent notification
- [x] Transfer progress: "正在同步 (3/15)"
- [x] Completion: "同步完成 — 15 张照片"
- [x] Error: "USB 同步失败 — tap to retry"

### 7.4 Deduplication & Resume
- [x] Track imported photo handles in SharedPreferences
- [x] Skip already-imported photos on subsequent syncs
- [x] `PhotoSyncManager` — per-storage handle tracking

### 7.5 Settings & Preferences
- [x] Per-camera USB sync on/off
- [x] Download quality preference: JPEG only / RAW only / both
- [x] `UsbSyncPreferences` — SharedPreferences wrapper

### 7.6 Polish
- [x] Transfer speed display (bytes/sec)
- [x] Selective download UI (checkboxes in photo list)
- [x] Smart sync button (single-button guided flow)
- [x] NEF format recognition (0xB103)
- [ ] Photo preview thumbnail before download (deferred)
- [ ] EXIF metadata extraction and display (deferred)
- [ ] Auto-delete from camera after transfer (deferred)

---

## Phase 5: BLE GPS/Time Sync (SnapBridge) 📋 PAUSED

> **Status**: Blocked by SnapBridge proprietary auth. Resume only if SnapBridge protocol
> is successfully reverse-engineered (requires packet capture from official SnapBridge app).
> USB sync provides higher user value in the meantime.

---

## Deprecated: Wi‑Fi / PTP‑IP Approach (Phases 2–4)

> ❌ Abandoned — Z30 lacks Infrastructure Wi‑Fi mode (flagship-only feature).
> PTP/IP code in `ptp/` package is kept for reference but is not functional on Z30.
> See [docs/nikon/USB_SYNC.md](nikon/USB_SYNC.md) for the working USB approach.

---

## File Map

| Component | File | Status |
|-----------|------|--------|
| BLE recognition | `vendors/nikon/NikonCameraVendor.kt` | ✅ |
| BLE protocol | `vendors/nikon/NikonProtocol.kt` | ✅ POC |
| BLE GATT | `vendors/nikon/NikonGattSpec.kt` | ✅ |
| BLE delegate | `vendors/nikon/NikonConnectionDelegate.kt` | ✅ POC |
| USB MTP core | `usb/NikonUsbManager.kt` | ✅ |
| USB ViewModel | `usb/UsbSyncViewModel.kt` | ✅ |
| USB Screen | `usb/UsbSyncScreen.kt` | ✅ Production |
| USB filter | `res/xml/nikon_usb_device_filter.xml` | ✅ |
| USB icon | `res/drawable/ic_usb_24dp.xml` | ✅ |
| USB strings | `res/values/strings.xml` | ✅ |
| **Phase 7 additions** | | |
| USB Service | `usb/UsbSyncService.kt` | ✅ |
| USB Coordinator | `usb/UsbSyncCoordinator.kt` | ✅ |
| Photo Sync Mgr | `usb/PhotoSyncManager.kt` | ✅ |
| USB Preferences | `usb/UsbSyncPreferences.kt` | ✅ |
| Navigation | `NavRoute.kt` | ✅ |
| Wiring | `MainActivity.kt` | ✅ |
| Manifest | `AndroidManifest.xml` | ✅ |
| PTP/IP (deprecated) | `ptp/PtpIpSession.kt` + others | ⚠️ Archived |
