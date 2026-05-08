# CameraSync — Nikon Camera Support: Task Plan & Progress Log

> Branch: `nikon-feature` | Started: 2026-05-05 | Updated: 2026-05-07

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

## Phase 5: BLE GPS/Time Sync (SnapBridge) ❌ PERMANENTLY ABANDONED

> **Status**: Blocked by SnapBridge proprietary auth. This workstream is permanently
> abandoned — USB photo sync provides far higher user value without requiring protocol
> reverse-engineering. The BLE vendor components for Nikon (`NikonCameraVendor`,
> `NikonGattSpec`, `NikonProtocol`, `NikonConnectionDelegate`) are retained solely
> for BLE device-name recognition (mfr ID `0x0399`, name prefix `Z_`). All GPS/date-time
> sync paths through these components are dead code and can be safely removed in a cleanup
> pass.

---

## Phase 8: Deep Integration — USB as First-Class Transport 🔧 IN PROGRESS

> **Goal**: USB is currently a standalone feature (hint card → separate photo screen).
> It must integrate into the existing device-centric architecture: reactive hot-plug
> detection, unified device card on the home screen, and parity with BLE-connected
> camera capabilities where applicable.

### 8.1 USB Hot-Plug Detection
- [x] `rememberUsbDeviceEntry()` registers BroadcastReceiver for real-time attach/detach
- [x] Compose state drives UI reactively — card updates without navigation or restart
- [x] USB card shows in Loading / Empty / HasDevices states

### 8.2 Unified Device Card
- [x] USB camera appears as a proper device card alongside BLE devices
- [x] Reuses existing UI patterns (Card, expandable details, status icon)
- [x] Connection status, camera model ("Nikon Z30"), expandable details
- [x] "管理照片" button in expanded view → navigates to UsbPhotoScreen

### 8.3 Gallery Screen (Phase 9–10)
- [x] GalleryScreen as primary UI (replaces DevicesList as default)
- [x] LazyVerticalGrid 3-column layout with MTP thumbnails
- [x] Client-side pagination (pageSize=30, load on scroll-to-bottom)
- [x] RAW+JPEG grouping by base filename
- [x] Transfer with progress indicator
- [x] All states: Disconnected/Connecting/Loading/Browsing/Empty/Error/Transferring/Done
- [x] BLE FAB removed from DevicesListScreen
- [x] DevicesListScreen accessible as secondary route for BLE features
- [x] Folder-based browsing (tap folder → enter, back → parent)
- [x] Device info collapsible card (model, storage, serial, firmware)
- [x] Long-press selection mode + bottom transfer bar
- [x] Subtle RAW indicator (not colored badge)
- [x] Pull-to-refresh
- [x] Transfer dedup integration (PhotoSyncManager)
- [x] Thumbnail preloading (first 30 after load)
- [x] EXIF metadata extraction and display — F7 PhotoDetailSheet (Sprint 2)
- [x] Haptic feedback for selection
- [x] Photo grouping configurable (by folder / by date / flat) — Sprint 4 ✅
- [x] Photo sorting configurable (by name / by date / by size) — Sprint 4 ✅
- [x] Grid columns configurable (2/3/4) — F10 (Sprint 2)
- [x] Download format preference (RAW+NEF / RAW only / JPEG only) — Sprint 4 ✅
- [x] Auto-delete from camera after transfer — F8 (Sprint 2)

---

---

## v2.0 Feature Delivery (Sprints 1–3) ✅ COMPLETED

> **2026-05-07**: Three sprints delivered 16 features. See `docs/PRD.md` for product spec.

### Sprint 1 — Delight & Closure (v2.0) ✅
- [x] F1 Transfer speed & ETA in TransferringContent
- [x] F2 Post-transfer action sheet (View/Share/Delete)
- [x] F3 Haptic feedback on transfer complete
- [x] F4 Camera storage bar (used/total, color-coded)
- [x] F5 Smart filter chips (全部/新照片/RAW/JPEG)
- [x] F6 Rich notification with BigPictureStyle

### Sprint 2 — Pro Photographer (v2.1) ✅
- [x] F7 EXIF detail sheet (shutter/aperture/ISO/focal length)
- [x] F8 Delete from camera after transfer (with safety confirmation)
- [x] F9 Onboarding flow (3-screen swipeable, first-launch only)
- [x] F10 Grid density toggle (2/3/4 columns)

### Sprint 3 — Polish & Trust (v2.2) ✅
- [x] F12 Camera battery indicator (best-effort via MTP)
- [x] F13 Transfer history timeline (SharedPreferences persistence)
- [x] F14 Retry failed transfers (extracted performTransfer loop)
- [x] F15 Settings screen (auto-sync, grid, history, theme links)
- [x] F16 Dark theme support (system/light/dark toggle)

### New Files Created
| File | Feature |
|------|---------|
| `usb/PhotoDetailSheet.kt` | F7 EXIF bottom sheet |
| `onboarding/OnboardingScreen.kt` | F9 Swipeable onboarding |
| `onboarding/OnboardingViewModel.kt` | F9 Onboarding state persistence |
| `usb/TransferHistoryScreen.kt` | F13 Transfer history list |
| `settings/SettingsScreen.kt` | F15 Settings page |

### Deferred to Future
- ~~Photo grouping configurable (by folder / by date / flat)~~ ✅ Sprint 4
- ~~Photo sorting configurable (by name / by date / by size)~~ ✅ Sprint 4
- ~~Download format preference (RAW+NEF / RAW only / JPEG only)~~ ✅ Sprint 4
- ~~Photo preview thumbnail before download~~ ✅ Sprint 4 (TransferPreviewSheet)
- ~~Code cleanup: Nikon BLE dead code removal~~ ✅ Sprint 4
- Cloud backup integration
- Video file support

## Known UX Issues

### RAW+JPEG selection follows format preference ✅ FIXED 2026-05-08
- ~~toggleSelection() / selectAll() always picked both raw+jpg handles~~
- Fixed: `handlesForFormat()` helper respects `prefs.downloadFormat`

### Settings not applying immediately ✅ FIXED 2026-05-08
- ~~Grid column count changes only took effect after app restart~~
- Fixed: `gridColumns` init from `prefs.getGridColumns()` + `onGridColumnsChanged` callback from Settings → ViewModel


---

## File Map

| Component | File | Status |
|-----------|------|--------|
| BLE recognition | `vendors/nikon/NikonCameraVendor.kt` | ✅ |
| BLE protocol | `vendors/nikon/NikonProtocol.kt` | ✅ POC |
| BLE GATT | `vendors/nikon/NikonGattSpec.kt` | ✅ |
| BLE delegate | `vendors/nikon/NikonConnectionDelegate.kt` | ✅ POC |
| USB MTP core | `usb/NikonUsbManager.kt` | ✅ |
| Gallery Screen | `usb/GalleryScreen.kt` | ✅ Primary UI (Phase 9) |
| Gallery ViewModel | `usb/GalleryViewModel.kt` | ✅ Pagination + transfer |
| USB Service | `usb/UsbSyncService.kt` | ✅ |
| USB Coordinator | `usb/UsbSyncCoordinator.kt` | ✅ |
| Photo Sync Mgr | `usb/PhotoSyncManager.kt` | ✅ |
| USB Preferences | `usb/UsbSyncPreferences.kt` | ✅ |
| USB filter | `res/xml/nikon_usb_device_filter.xml` | ✅ |
| USB icon | `res/drawable/ic_usb_24dp.xml` | ✅ |
| USB strings | `res/values/strings.xml` | ✅ |
| Navigation | `NavRoute.kt` | ✅ |
| Wiring | `MainActivity.kt` | ✅ |
| Manifest | `AndroidManifest.xml` | ✅ |
| **New (Sprint 1–3)** | | |
| Photo Detail | `usb/PhotoDetailSheet.kt` | ✅ F7 EXIF |
| Onboarding | `onboarding/OnboardingScreen.kt` | ✅ F9 |
| Onboarding VM | `onboarding/OnboardingViewModel.kt` | ✅ F9 |
| Transfer History | `usb/TransferHistoryScreen.kt` | ✅ F13 |
| Settings | `settings/SettingsScreen.kt` | ✅ F15 |
| **Deleted** | | |
| PTP/IP debug | `ptp/*` (8 files) | ❌ Deleted |
| Old USB debug | `usb/UsbSyncScreen.kt`, `UsbSyncViewModel.kt` | ❌ Deleted |
| Old USB photo | `usb/UsbPhotoScreen.kt` | ❌ Deleted (replaced by Gallery) |

---

## Code Cleanup Backlog

### Remove dead BLE/WiFi code ✅ DONE
- [x] **NikonGattSpec** / **NikonProtocol** / **NikonConnectionDelegate** / **NikonCameraVendor**:
  All 4 source files + 3 test files deleted. AppGraph updated to remove Nikon from vendor registry.
- [x] **PTP/IP debug remnants**: Verified all `ptp/*` files deleted, no stale imports remain.

### Delete remaining BLE documentation references from multi-vendor docs
- [ ] `MULTI_VENDOR_SUPPORT.md` Section 1 diagram and directory reference still show
  `vendors/nikon/` without distinguishing BLE-only vs. USB. These should be updated.
- [ ] `MULTI_DEVICE_ARCHITECTURE.md` references to `NikonCameraVendor` recognition should be
  cross-checked against the USB-centric reality.
