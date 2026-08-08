# Session Summary — 2026-05-06 → 2026-08-02

> **Next session**: Read this first. It captures everything done, learned, and the current state.

## TL;DR

CameraSync 生产就绪。所有 Sprint + bug 修复 + UX 打磨已完成。0 个已知问题。

## 最新更新 (2026-08-02)

### Recent Commits (on master)

```
4042515 fix: add kotlinx-atomicfu dependency for Khronicle logger
a385378 refactor: remove BLE GPS sync and companion device subsystems
fcd4f8c refactor: migrate local photos to Coil + fix USB MTP enumeration crashes
52a550d fix: local photos display — scoped storage, EXIF orientation, preview, refresh
90cc77b fix: query MediaStore.Files for NEF — Images table excludes RAW
```

### PhotoCell EXIF Orientation Bug 修复 (2026-08-02)
- Nikon Z30 MTP 缩略图被相机固件预旋转，而代码用 `readImageInfo` (inJustDecodeBounds + 手动 EXIF orientation swap) 计算 `cellAspect`
- 导致双重校正：aspect ratio 和图片都被错误转换，竖构图在网格中横着显示 + 黑边
- 修复：删除 `readImageInfo` 和 `ImageInfo` 数据类，从 rotation 后的 bitmap 直接计算 `cellAspect`
- 文件：`GalleryScreen.kt`

### 本地相册 Coil 迁移 (2026-06-19)
- 将 `LocalPhotosViewModel` + `LocalPhotoCell` 从裸 `BitmapFactory` 迁移到 Coil 3.x
- 解决 OOM 崩溃（30+ LaunchedEffect 同时 decodeFile）
- 自动并发控制、内存/磁盘缓存、EXIF 方向处理
- 文件：`LocalPhotosViewModel.kt`, `GalleryScreen.kt` (LocalTabContent), `CameraSyncApp.kt` (ImageLoader 配置)

### BLE GPS 同步子系统移除 (2026-08-02)
- 移除 `vendors/ricoh/`, `vendors/sony/`, `devicesync/` 等 BLE GPS 同步代码
- 移除 Companion Device Manager 配对子系统
- USB 照片同步现在是唯一功能路径
- 文件：删除 ~15 个源文件，更新 `AppGraph.kt`, `MainActivity.kt`, `NavRoute.kt`

---

## What Was Built

### Documentation Overhaul (Pre-Sprint)
- **Deleted** 4 obsolete BLE/WiFi docs
- **Rewrote** README.md → USB-first, Nikon series
- **Created** CONTRIBUTING.md (dev setup, Mermaid architecture, code style)
- **Updated** AGENTS.md with AI Navigation Guide

### Sprint 1 — Delight & Closure (v2.0) ✅
| F# | Feature | Key Files |
|----|---------|-----------|
| F1 | Transfer speed & ETA | `GalleryViewModel.kt`, `GalleryScreen.kt`, `NikonUsbManager.kt` |
| F2 | Post-transfer action sheet | `GalleryScreen.kt` |
| F3 | Haptic feedback | `GalleryScreen.kt` |
| F4 | Storage status bar | `GalleryScreen.kt` |
| F5 | Filter chips | `GalleryViewModel.kt`, `GalleryScreen.kt` |
| F6 | Rich notification | `UsbSyncService.kt` |

### Sprint 2 — Pro Photographer (v2.1) ✅
| F# | Feature | Key Files |
|----|---------|-----------|
| F7 | EXIF detail sheet | `PhotoDetailSheet.kt`, `GalleryScreen.kt` |
| F8 | Delete from camera | `NikonUsbManager.kt`, `GalleryViewModel.kt` |
| F9 | Onboarding | `OnboardingScreen.kt`, `OnboardingViewModel.kt` |
| F10 | Grid density | `UsbSyncPreferences.kt`, `GalleryScreen.kt` |

### Sprint 3 — Polish & Trust (v2.2) ✅
| F# | Feature | Key Files |
|----|---------|-----------|
| F12 | Battery indicator | `NikonUsbManager.kt`, `GalleryScreen.kt` |
| F13 | Transfer history | `TransferHistoryScreen.kt`, `UsbSyncPreferences.kt` |
| F14 | Retry failed | `GalleryViewModel.kt` |
| F15 | Settings screen | `SettingsScreen.kt` |
| F16 | Dark theme | `Theme.kt`, `UsbSyncPreferences.kt`, `MainActivity.kt` |

### Sprint 4 — Cleanup & Preferences (v2.3) ✅
| F# | Task |
|----|------|
| C1 | Remove Nikon BLE dead code |
| F17 | Download format preference |
| F18 | Photo grouping (folder/date/flat) |
| F19 | Photo sorting (date/name/size) |
| F20 | Transfer preview sheet |

### Post-Sprint: Bug Fixes & Refactoring
- 13+ runtime bug fixes (crash, layout, selection, orientation, EXIF)
- Strings migration: 80% hardcoded Chinese → stringResource
- Screen merge: GalleryScreen + GalleryFolderScreen → 1 parametrized composable
- Progressive photo loading (30-photo batches with streaming)
- Coil migration for local photos
- BLE GPS sync subsystem removal

---

## Current Architecture (2026-08-02)

```
CameraSync (master)
├── usb/                              ★ USB photo sync (唯一功能路径)
│   ├── GalleryScreen.kt              (~1500 lines, 所有 UI 状态)
│   ├── GalleryViewModel.kt           (状态机 + 传输 + 筛选 + 重试)
│   ├── NikonUsbManager.kt            (MTP 封装)
│   ├── PhotoSyncManager.kt           (去重)
│   ├── PhotoDetailSheet.kt           (EXIF 详情面板)
│   ├── TransferHistoryScreen.kt      (传输历史)
│   ├── LocalPhotosViewModel.kt       (本地相册 — MediaStore + Coil)
│   ├── UsbSyncService.kt             (前台服务)
│   ├── UsbSyncCoordinator.kt         (自动同步)
│   └── UsbSyncPreferences.kt         (用户偏好)
├── settings/
│   └── SettingsScreen.kt             (设置页面)
├── ui/theme/                         (Material 3 + Google Sans Flex)
├── logging/                          (Khronicle 日志引擎)
├── di/                               (Metro DI)
├── NavRoute.kt                       (类型安全路由)
└── MainActivity.kt                   (单 Activity + NavDisplay)
```

---

## Key Technical Patterns

### Navigation
- Custom stack: `SnapshotStateList<NavRoute>` + `NavDisplay`
- NO Jetpack Navigation library

### State Management
- ViewModel: `mutableStateOf<SealedInterface>` → UI: `.value` → `when` branches
- GalleryState: Disconnected → Connecting → Loading → Browsing → Transferring → TransferDone

### Coroutines
- ViewModel scope: `CoroutineScope(Dispatchers.IO + SupervisorJob())`
- Dispatcher injection for testability

### UI Language
- Chinese strings via `stringResource()`
- Logs in English via Khronicle

### Image Loading
- MTP thumbnails: `MtpDevice.getThumbnail()` + `rotateByExif()` with orientation cache
- Local photos: Coil `AsyncImage` with `ImageLoader` (SingletonImageLoader.Factory)

---

## Environment Notes
- User uses PowerShell on Windows
- Project root: `I:\dev\CameraSync`
- Test device: Pixel 9 + Android 15 + Nikon Z30 (C2C cable)
- exiftool at `D:\Scoop\apps\exifglass\current\exiftool.exe`

## Documentation Reference

| Doc | Purpose |
|-----|---------|
| `README.md` | 项目概览、功能、快速开始 |
| `AGENTS.md` | AI 导航指南、意图→文件映射、代码模式 |
| `docs/PRD.md` | v2 产品需求文档 |
| `docs/TODO.md` | 当前状态 & 未来规划 |
| `docs/nikon/USB_SYNC.md` | USB MTP 技术参考 |
| `docs/REFACTOR_LOCAL_PHOTOS.md` | 本地相册 Coil 迁移方案 |
| `docs/BUG_FIX_PLAN.md` | 历史 bug 修复记录 |
| `docs/SESSION_SUMMARY.md` | 本文件 — 会话上下文 |
