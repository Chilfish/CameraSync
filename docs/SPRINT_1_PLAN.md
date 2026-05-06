# Sprint 1 — "Delight & Closure" (v2.0)

> **Sprint Goal**: Close the post-transfer loop. Users know where photos went, how fast they're going, and what to do next. Camera status is visible at a glance.

## Features

### F1: Transfer Progress with Speed & ETA
**Files**: `usb/GalleryViewModel.kt`, `usb/GalleryScreen.kt`  
**Pattern**: Add `TransferProgress` data class to `GalleryState.Transferring`

- Replace `Transferring(synced: Int, total: Int, currentFile: String)` with `Transferring(progress: TransferProgress)` where `TransferProgress` includes `bytesTransferred`, `totalBytes`, `startTimeMillis`
- Compute speed and ETA as derived properties
- `TransferringContent` composable: show speed ("12.3 MB/s") and ETA ("还剩 8s") below the progress bar
- Track bytes in `downloadPhoto` by returning byte count instead of Boolean, or accumulate in ViewModel
- Update `Transferring` state more frequently (currently only updates between files; add a callback in downloadPhoto to report incremental byte progress)

### F2: Post-Transfer Action Sheet
**Files**: `usb/GalleryScreen.kt` (new composable), `usb/GalleryViewModel.kt`  
**Pattern**: `ModalBottomSheet` triggered by `TransferDone` state

- New `GalleryState.TransferDone` holds transferred photo URIs (not just count)
- `GalleryViewModel` collects MediaStore URIs during transfer
- New `TransferCompleteSheet` composable:
  - "同步完成 ✨" title
  - "已保存 N 张照片" subtitle
  - Three action buttons in a column:
    - "在相册中查看" → `Intent(ACTION_VIEW, lastSavedUri)`
    - "分享" → `Intent(ACTION_SEND_MULTIPLE, uris)`
    - "从相机删除" (greyed if F8 not implemented yet, placeholder)
- Sheet dismisses on swipe-down or tapping outside

### F3: Haptic Feedback on Transfer Complete
**Files**: `usb/GalleryScreen.kt`  
**Pattern**: `LocalHapticFeedback` + `LaunchedEffect`

- In `TransferDoneContent`, add `LaunchedEffect(Unit)` that calls `haptic.performHapticFeedback(HapticFeedbackType.LongPress)`
- No new files needed, one-line addition

### F4: Camera Storage Bar
**Files**: `usb/GalleryScreen.kt`, `usb/GalleryViewModel.kt`  
**Pattern**: `LinearProgressIndicator` in gallery header

- `GalleryViewModel` already has `storages: List<StorageInfo>` with `freeSpace` and `maxCapacity`
- Add a composable `StorageStatusBar` to `BrowsingContent` header:
  - Shows "SD 卡: 已用 12.3 / 32 GB" with a determinate progress bar
  - Color: green >20%, yellow 10-20%, red <10%
  - Warning icon when free space <10%
- Compute aggregate across all storages, or show per-storage if multiple

### F5: "Transfer New" Smart Chip
**Files**: `usb/GalleryScreen.kt`, `usb/GalleryViewModel.kt`  
**Pattern**: `FilterChip` / `AssistChip` row above the grid

- `GalleryViewModel` computes: `newPhotoCount = currentPhotos.count { group -> group's handles are NOT in photoSyncManager }`
- Add `FilterChip` row in `BrowsingContent` below the header:
  - "全部" (default selected)
  - "新照片 (12)" — filters to only unimported photos
  - "RAW" — filters to groups that have NEF
  - "JPEG" — filters to groups that have JPG
- Add `filterMode` state to `GalleryViewModel` (enum: ALL, NEW, RAW_ONLY, JPEG_ONLY)
- Filter `currentPhotos` based on `filterMode` before rendering the grid

### F6: Rich Notification with Thumbnail
**Files**: `usb/UsbSyncService.kt`  
**Pattern**: `NotificationCompat.BigPictureStyle`

- After background sync completes, build a `BigPictureStyle` notification
- Use the first transferred photo's MediaStore URI to decode a thumbnail bitmap
- Show: "同步完成 — 15 张照片" with thumbnail preview
- Tap notification → opens app to gallery
- Only for background sync; foreground transfer uses the in-app sheet (F2)

## Affected Files Summary

| File | Changes |
|------|---------|
| `usb/GalleryViewModel.kt` | F1 (TransferProgress), F2 (URIs list), F4 (storage aggregates), F5 (filterMode) |
| `usb/GalleryScreen.kt` | F1 (TransferringContent), F2 (TransferCompleteSheet), F3 (haptic), F4 (StorageStatusBar), F5 (chips) |
| `usb/NikonUsbManager.kt` | F1 (byte-counting downloadPhoto) |
| `usb/UsbSyncService.kt` | F6 (BigPictureStyle notification) |
| `res/values/strings.xml` | New strings for all UI text |

## Acceptance Criteria

1. [ ] Transfer screen shows real-time speed in MB/s and estimated seconds remaining
2. [ ] Transfer complete sheet appears with "查看", "分享", "删除" actions
3. [ ] Haptic feedback fires on transfer complete (test on physical device)
4. [ ] Storage bar visible in gallery header, turns red below 10%
5. [ ] "新照片" chip filters to unimported photos; "RAW"/"JPEG" chips work
6. [ ] Background sync notification shows thumbnail preview
7. [ ] All existing tests pass (`./gradlew test`)
8. [ ] UI still works on Android 13+ (API 33)
9. [ ] No regressions in existing gallery browsing, selection, or transfer
10. [ ] Chinese strings in `strings.xml`, no hardcoded strings in Composables
