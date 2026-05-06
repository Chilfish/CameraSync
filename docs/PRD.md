# CameraSync v2 — Product Requirements Document

> **Product Manager**: Alma  
> **Date**: 2026-05-06  
> **Status**: Drafting

## 1. Elevator Pitch

CameraSync is the fastest, most delightful way to get photos off your Nikon camera and onto your phone. Plug in a USB-C cable. That's it. No pairing, no Wi-Fi passwords, no proprietary apps. Browse, select, transfer — with the polish you expect from a first-party Apple product.

## 2. Current State Assessment

### What works (v1)
- USB MTP photo sync for Nikon series cameras (tested Z30)
- Gallery browsing with 3-column grid, folder navigation
- RAW+JPEG grouping with subtle RAW badge
- Long-press multi-select + batch transfer
- Background auto-sync via foreground service
- Deduplication across sessions

### Pain points (validated)
| # | Pain | Severity | Users affected |
|---|------|----------|----------------|
| P1 | No post-transfer action — users don't know where photos went or what to do next | 🔴 High | All |
| P2 | Transfer progress lacks ETA/speed — staring at a slow bar for 20s | 🔴 High | All |
| P3 | No camera status — can't see storage remaining, battery, or last sync time | 🟡 Medium | Power users |
| P4 | Cold first-run — no onboarding, no guidance on MTP mode requirement | 🟡 Medium | New users |
| P5 | No EXIF/metadata view — can't check shutter/aperture/ISO before transfer | 🟢 Low | Enthusiasts |
| P6 | Can't free camera SD card — must delete photos on camera body | 🟡 Medium | Frequent shooters |
| P7 | Transfer completion is silent — no haptic, no animation | 🟢 Low | All (delight gap) |
| P8 | No smart selection — can't filter "new only" or "RAW only" | 🟡 Medium | High-volume shooters |

## 3. User Journey (Target v2)

```
┌─────────────────────────────────────────────────────────────────┐
│  📱 First Launch                                                │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  🎬 Onboarding (3 cards, swipeable)                       │  │
│  │  ① "Connect your Nikon" — illustration of C2C cable       │  │
│  │  ② "Browse & Select" — screenshot of gallery grid         │  │
│  │  ③ "Transfer & Done" — transfer animation mockup          │  │
│  │                                [开始使用 →]                │  │
│  └───────────────────────────────────────────────────────────┘  │
│                        ↓                                         │
│  🔌 Plug in cable → system permission dialog →                  │
│     app detects camera → animated connection indicator           │
│                        ↓                                         │
│  🖼 Gallery loads with:                                          │
│     • Camera name + battery + storage bar at top                 │
│     • "新照片 (12)" chip for unimported photos                   │
│     • Grid with thumbnails + RAW badges                          │
│     • Pull-to-refresh                                            │
│                        ↓                                         │
│  👆 Tap photo → EXIF detail sheet (shutter, aperture, ISO, FL)  │
│     Long-press → multi-select mode with haptic                   │
│     Bottom bar: "传输 N 张 (共 245 MB)"                          │
│                        ↓                                         │
│  📤 Transfer sheet slides up:                                    │
│     • Progress bar with speed ("12.3 MB/s") and ETA ("还剩 8s") │
│     • Cancel button                                              │
│     • MediaStore save with IS_PENDING pattern                    │
│                        ↓                                         │
│  ✨ Transfer complete:                                           │
│     • Haptic feedback (HapticFeedbackType.LONG_PRESS)            │
│     • Confetti or subtle celebration animation                   │
│     • Bottom sheet: "已保存 15 张" with actions:                 │
│       [查看] [分享] [从相机删除]                                  │
│     • Notification: "同步完成 — 15 张照片" with tap-to-view      │
│                        ↓                                         │
│  🗑 Optional: "从相机删除这 15 张" with safety confirmation:     │
│     "这些照片已安全保存到手机。从相机删除后无法恢复。"            │
│     [取消] [删除]                                                │
└─────────────────────────────────────────────────────────────────┘
```

## 4. Feature Backlog

### Sprint 1 — "Delight & Closure" (v2.0)

| ID | Feature | Effort | Impact | Dependencies |
|----|---------|--------|--------|-------------|
| F1 | **Transfer progress with speed & ETA** — track bytes/time in GalleryViewModel, display speed and remaining time in TransferringContent | S | 🔴 | None |
| F2 | **Post-transfer action sheet** — new `TransferCompleteSheet` composable with "查看" (open system gallery), "分享" (share intent), "从相机删除" actions | M | 🔴 | F1 |
| F3 | **Haptic feedback on transfer complete** — `HapticFeedbackType.LONG_PRESS` at transfer end | XS | 🟢 | None |
| F4 | **Camera storage bar** — read `StorageInfo.freeSpace` / `maxCapacity`, render linear progress bar with "已用 12.3 / 32 GB" at top of gallery | S | 🟡 | None |
| F5 | **"Transfer New" smart chip** — filter photos to only those not in PhotoSyncManager, show as a tappable chip above the grid | S | 🟡 | None |
| F6 | **Rich notification with image thumbnail** — BigPictureStyle notification showing first transferred photo | M | 🟡 | F1 |

### Sprint 2 — "Pro Photographer" (v2.1)

| ID | Feature | Effort | Impact | Dependencies |
|----|---------|--------|--------|-------------|
| F7 | **Photo detail sheet with EXIF** — tap photo → bottom sheet with full image preview, EXIF fields (shutter speed, aperture, ISO, focal length), file size | L | 🟡 | None |
| F8 | **Delete from camera** — `MtpDevice.deleteObject(handle)` after transfer, with safety confirmation dialog | M | 🟡 | F2 |
| F9 | **Onboarding flow** — 3-screen swipeable onboarding with illustrations, only shown on first launch (SharedPreferences flag) | M | 🟡 | None |
| F10 | **Grid density toggle** — 2/3/4 column switch in top bar overflow menu | S | 🟢 | None |
| F11 | **Quick filter chips** — "RAW only" / "JPEG only" / "全部" segmented control or chip row | S | 🟡 | None |

### Sprint 3 — "Polish & Trust" (v2.2)

| ID | Feature | Effort | Impact | Dependencies |
|----|---------|--------|--------|-------------|
| F12 | **Camera battery indicator** — if MTP reports battery property (device-dependent), show battery icon + percentage | M | 🟢 | None |
| F13 | **Transfer history timeline** — chronological list of past sync sessions with photo counts and dates | M | 🟢 | DataStore |
| F14 | **Retry failed transfers** — track per-handle transfer status, offer "重试失败的 N 张" button | L | 🟡 | F1 |
| F15 | **Settings screen** — dedicated settings route with auto-sync toggle, save location, format preference, grid density, theme | M | 🟢 | None |
| F16 | **Dark theme support** — Material 3 dynamic color + manual dark/light toggle | M | 🟢 | None |

## 5. Technical Design Notes

### F1: Transfer Speed & ETA
```kotlin
// GalleryViewModel — track transfer progress
data class TransferProgress(
    val synced: Int,
    val total: Int,
    val currentFile: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val startTimeMillis: Long,
) {
    val speedBps: Double get() {
        val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000.0
        return if (elapsed > 0) bytesTransferred / elapsed else 0.0
    }
    val etaSeconds: Long get() {
        val remaining = totalBytes - bytesTransferred
        return if (speedBps > 0) (remaining / speedBps).toLong() else 0
    }
}
```

### F2: Post-Transfer Action Sheet
- New `TransferCompleteSheet` composable using `ModalBottomSheet`
- Three action buttons:
  - "查看" → `Intent(Intent.ACTION_VIEW)` with the last saved MediaStore URI
  - "分享" → `Intent(Intent.ACTION_SEND_MULTIPLE)` with transferred photo URIs
  - "从相机删除" → confirmation dialog → `MtpDevice.deleteObject()`

### F7: EXIF Detail Sheet
- `ExifInterface(ByteArrayInputStream(thumbnailBytes))` for metadata extraction
- Fields: shutter speed (formatted as "1/250"), aperture (f/2.8), ISO, focal length (mm), file size, dimensions
- MTP `getThumbnail()` for preview image (already cached in `thumbCache`)

### F8: Delete from Camera
```kotlin
// NikonUsbManager — add delete method
fun deletePhoto(mtpDevice: MtpDevice, handle: Int): Boolean {
    return mtpDevice.deleteObject(handle)
}
```
- Safety: only offer delete after successful transfer to MediaStore
- Confirmation dialog with irreversible warning
- Batch delete: iterate handles, track failures

### F5: "Transfer New" Smart Selection
- Query `PhotoSyncManager` for already-imported handles
- Filter `currentPhotos` to only those NOT in imported set
- Show count chip: "新照片 (12)"
- Tap chip → select all new photos

## 6. Success Metrics

| Metric | Current | Target |
|--------|---------|--------|
| Time from cable-plug to first photo transfer | ~8s | ~5s (optimize enumeration) |
| User completes post-transfer action (share/view) | 0% (no feature) | >40% |
| Transfer completion rate | 95% | >98% (retry logic) |
| Storage warning prevents full-card scenario | 0% (no feature) | Shown at <10% free |

## 7. Out of Scope (v2)

- Cloud backup integration (Google Photos, Dropbox)
- Wi-Fi transfer (Z30 lacks infra mode; other models may differ)
- RAW editing / lightroom-style adjustments
- Multi-camera concurrent USB (Android only supports one USB host device)
- Video file support (large files, different MTP handling needed)
- Background auto-sync with periodic polling (battery drain)

## 8. Open Questions

1. **Q**: Does MTP `deleteObject()` work on all Nikon models?  
   **A**: Need to test on Z30; behavior may differ on D-series DSLR bodies.

2. **Q**: Can battery level be read via MTP on Z30?  
   **A**: MTP spec defines `0xD303` (BatteryLevel) but not all devices implement it. Needs testing.

3. **Q**: Should "Delete from camera" be on-by-default or opt-in per transfer?  
   **A**: Opt-in per transfer with a "remember my choice" preference. Safety first.

4. **Q**: Should onboarding show on every fresh install or per-device?  
   **A**: Once per app install (SharedPreferences flag). Re-accessible from Settings → "查看引导".

---

*This PRD is a living document. Update as features ship and new insights emerge.*
