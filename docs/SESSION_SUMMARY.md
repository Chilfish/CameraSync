# Session Summary — 2026-05-06/07/08

> **Next session**: Read this first. It captures everything done, learned, and the current state.

## TL;DR

Sprint 4 delivered. 5 deferred features done. Then deep code review found and fixed 13 runtime bugs + 8 architecture issues. 19 messy commits cleaned to 7. PhotoDetailSheet now downloads full RAW for preview with EXIF. Cache and import indicator added.

Three sprints delivered. CameraSync is now a polished USB photo sync app for Nikon cameras with 16 features across 3 sprints. All merged to `master`. Ready for next phase.

## What Was Built

### Documentation Overhaul (Pre-Sprint)
- **Deleted** 4 obsolete BLE/WiFi docs (`draft.md`, `BLE_GPS_SYNC.md`, `NIKON_VENDOR_ADAPTATION.md`, `PTP_IMAGE_TRANSFER.md`)
- **Rewrote** README.md → USB-first, Nikon series (not just Z30)
- **Created** CONTRIBUTING.md (388 lines: dev setup, Mermaid architecture, code style, testing)
- **Updated** AGENTS.md with AI Navigation Guide (intent→file mapping, code patterns)
- **All** ASCII boxes → Mermaid diagrams (GitHub-renderable)
- **Softened** "Nikon Z30" → "Nikon series (tested with Z30)" everywhere

### Sprint 1 — Delight & Closure (v2.0)
| F# | Feature | Key Files |
|----|---------|-----------|
| F1 | Transfer speed & ETA | `GalleryViewModel.kt` (TransferProgress), `GalleryScreen.kt`, `NikonUsbManager.kt` |
| F2 | Post-transfer action sheet | `GalleryScreen.kt` (ModalBottomSheet: View/Share/Delete) |
| F3 | Haptic feedback | `GalleryScreen.kt` (LaunchedEffect + LongPress) |
| F4 | Storage status bar | `GalleryScreen.kt` (StorageStatusBar, color-coded) |
| F5 | Filter chips | `GalleryViewModel.kt` (PhotoFilter), `GalleryScreen.kt` (FilterChipsRow) |
| F6 | Rich notification | `UsbSyncService.kt` (BigPictureStyle) |

### Sprint 2 — Pro Photographer (v2.1)
| F# | Feature | Key Files |
|----|---------|-----------|
| F7 | EXIF detail sheet | NEW `PhotoDetailSheet.kt`, `GalleryScreen.kt` (tap→detail, long-press→select) |
| F8 | Delete from camera | `NikonUsbManager.kt` (deleteObject), `GalleryViewModel.kt` (deleteTransferredPhotos) |
| F9 | Onboarding | NEW `OnboardingScreen.kt` + `OnboardingViewModel.kt`, `NavRoute.kt`, `MainActivity.kt` |
| F10 | Grid density | `UsbSyncPreferences.kt`, `GalleryScreen.kt` (2/3/4 columns cycle) |

### Sprint 3 — Polish & Trust (v2.2)
| F# | Feature | Key Files |
|----|---------|-----------|
| F12 | Battery indicator | `NikonUsbManager.kt` (best-effort, null default), `GalleryScreen.kt` |
| F13 | Transfer history | NEW `TransferHistoryScreen.kt`, `UsbSyncPreferences.kt` (JSON persistence) |
| F14 | Retry failed | `GalleryViewModel.kt` (refactored performTransfer, retryFailedTransfers) |
| F15 | Settings screen | NEW `SettingsScreen.kt`, `NavRoute.kt`, `MainActivity.kt` |
| F16 | Dark theme | `Theme.kt` (themeMode param), `UsbSyncPreferences.kt`, `MainActivity.kt` |

## Current Architecture

```
CameraSync (master)
├── usb/                              ★ PRIMARY — USB photo sync
│   ├── GalleryScreen.kt              (650→~900 lines, all UI states)
│   ├── GalleryViewModel.kt           (state machine + transfer + filters + retry)
│   ├── NikonUsbManager.kt            (MTP wrapper: open/close/list/download/delete/battery)
│   ├── PhotoSyncManager.kt           (dedup via SharedPreferences)
│   ├── PhotoDetailSheet.kt           NEW — EXIF bottom sheet
│   ├── TransferHistoryScreen.kt      NEW — sync history
│   ├── UsbSyncService.kt             (foreground service + BigPictureStyle)
│   ├── UsbSyncCoordinator.kt         (auto-sync lifecycle)
│   └── UsbSyncPreferences.kt         (all user prefs: auto-sync, grid, theme, history)
├── onboarding/                       NEW — first-launch flow
│   ├── OnboardingScreen.kt           (3-page HorizontalPager)
│   └── OnboardingViewModel.kt        (SharedPreferences flag)
├── settings/                         NEW — settings page
│   └── SettingsScreen.kt             (toggles + navigation links)
├── vendors/                          BLE GPS sync (secondary)
│   ├── ricoh/, sony/                 (Ricoh GR, Sony Alpha — working)
│   └── nikon/                        ❌ REMOVED — dead BLE SnapBridge code deleted
├── devicesync/                       BLE multi-device coordination
├── NavRoute.kt                       sealed interface: Gallery, GalleryFolder, Settings,
│                                     TransferHistory, Onboarding, DevicesList, Pairing, LogViewer
└── MainActivity.kt                   Single-activity, NavDisplay stack-based navigation
```

## Key Technical Patterns (for AI agents)

### Navigation
- Custom stack: `SnapshotStateList<NavRoute>` + `NavDisplay` + `NavEntry { when(route) }`
- NO Jetpack Navigation library
- Push: `backStack.add(NavRoute.X)`, Pop: `backStack.removeLastOrNull()`

### State Management
- ViewModel: `mutableStateOf<SealedInterface>` → Screen: `.value` → `when` branch
- GalleryState: Disconnected → Connecting → Loading → Browsing → Transferring → TransferDone
- TransferProgress: speedBps, speedFormatted, etaSeconds, etaFormatted computed properties

### Coroutines
- ViewModel scope: `CoroutineScope(Dispatchers.IO + SupervisorJob())`
- `scope.cancel()` in `stop()`
- Dispatcher injection for testability

### BroadcastReceiver
- Register in `start()` with `RECEIVER_EXPORTED`
- Unregister in `stop()` with try/catch

### UI Language
- Chinese strings in `strings.xml`, Compose via `stringResource(R.string.xxx)`
- Log messages and code comments in English
- Khronicle logging (`com.juul.khronicle.Log`), not `android.util.Log`

## Git History (on master)

```
093c03f docs: overhaul for USB-first Nikon series, Mermaid diagrams, AI navigation
c085c4d feat(sprint-1): transfer progress, post-transfer sheet, storage bar, filter chips, rich notification
ac86992 feat(sprint-2): EXIF detail sheet, camera delete, onboarding, grid density
e90034b feat(sprint-3): transfer history, settings, dark theme, battery, retry
```

### Sprint 4 — Cleanup & Preferences (v2.3) ✅ 2026-05-07

| # | Task | Files Changed |
|---|------|---------------|
| C1 | Remove Nikon BLE dead code | Deleted `vendors/nikon/*` (4 src + 3 test), updated `AppGraph.kt` |
| F17 | Download format preference | `UsbSyncPreferences.kt`, `GalleryViewModel.kt`, `SettingsScreen.kt` |
| F18 | Photo grouping (folder/date/flat) | `GalleryViewModel.kt` (DateSection, loadRoot), `GalleryScreen.kt`, `SettingsScreen.kt` |
| F19 | Photo sorting (date/name/size) | `GalleryViewModel.kt` (applySorting), `GalleryScreen.kt`, `SettingsScreen.kt` |
| F20 | Transfer preview sheet | `GalleryScreen.kt` (TransferPreviewSheet — thumbnails, counts, size breakdown) |

## What's Deferred

- Cloud backup integration
- Video file support

## Documentation Reference

| Doc | Purpose |
|-----|---------|
| `README.md` | Project overview, features, quick start |
| `CONTRIBUTING.md` | Dev setup, architecture, how to add cameras |
| `AGENTS.md` | AI navigation guide, intent→file mapping, code patterns |
| `docs/PRD.md` | v2 product requirements (Apple PM style) |
| `docs/SPRINT_1_PLAN.md` | Sprint 1 execution plan |
| `docs/TODO.md` | Task log + current status |
| `docs/nikon/README.md` | Nikon USB sync index |
| `docs/nikon/USB_SYNC.md` | USB MTP technical reference |
| `docs/MULTI_VENDOR_SUPPORT.md` | BLE vendor architecture + USB parallel transport |
| `docs/MULTI_DEVICE_ARCHITECTURE.md` | Full architecture with USB + BLE layers |
| `docs/SESSION_SUMMARY.md` | This file — session context for next conversation |

---

## Post-Sprint-4: Code Review & Bug Fixes (2026-05-07 late)

### Architecture Refactoring
- Strings migration: 80% hardcoded Chinese → stringResource (+25 keys)
- Screen merge: GalleryScreen + GalleryFolderScreen → 1 parametrized composable (-400 duplicate lines)
- Compose State: filterMode, groupingMode, sortingMode, gridColumns, currentPhotos → mutableStateOf
- Shared composables: ThumbnailImage, formatFileSize consolidation
- OnboardingScreen now uses Scaffold, navigation unified to removeLastOrNull()

### Runtime Bug Fixes (6 + 7 = 13 total)
**Crash fixes:**
- thumbCache thread-safety (LinkedHashMap → synchronizedMap)
- getThumbnail race condition vs closeMtp (local capture + try/catch)

**Layout/Display:**
- StorageBar/FilterChips/grid stacking fix (wrap in Column with weight)
- Date grouping sorted newest-first
- refresh() folder context preservation
- Portrait NEF orientation from thumbPix dimensions + imagePixWidth/Height

**Selection/Preview:**
- Selection mode tap behavior (select instead of preview)
- TransferPreviewSheet uses unfiltered currentPhotos
- PhotoDetailSheet downloads full RAW (NEF) via MTP importFile for preview + complete EXIF
- Expand EXIF to 13 fields (aperture, ISO, focal length, lens model, etc.)

**Download:**
- Remove date folder from MediaStore path

### Photo Preview Cache + Import Indicator (2026-05-08)
- fullPhotoCache: LRU 12 entries (~300MB max), cleared on disconnect
- Green ✓ badge on already-imported photos in gallery grid
- isGroupImported() helper in ViewModel

### Commit History Cleanup
- 19 commits (4 garbage merges) → 7 clean commits
- git rebase -i (drop merges, fixup squash) + filter-branch (rewrite messages)
- Each commit has complete body describing features/bugs

### Known UX Issues (TODO)
- RAW+JPEG selection should follow download format preference
- Settings changes (grid columns) require app restart to take effect

## Environment Notes
- User uses **PowerShell** on Windows (`;` not `&&` for chaining)
- Project root: `I:\CameraSync`
- Test device: Pixel 9 + Android 15 + Nikon Z30 (C2C cable)
- exiftool at `D:\Scoop\apps\exifglass\current\exiftool.exe` for NEF metadata verification
