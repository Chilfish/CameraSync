---
description:
globs: NONE.jola
alwaysApply: false
---

# CameraSync - Claude AI Assistant Guide

## Project Overview

CameraSync is an Android application with **two independent subsystems**:

1. **USB Photo Sync (Primary)** — Sync photos from Nikon cameras to an Android phone
   over a USB-C cable using Android's built-in `android.mtp.MtpDevice` API. Browse,
   select, and transfer photos with RAW+JPEG grouping and deduplication.

2. **BLE GPS Sync (Secondary / Legacy)** — Synchronize GPS location and date/time from
   the phone to cameras via Bluetooth Low Energy using the Kable library. Supports Ricoh
   GR series and Sony Alpha cameras, plus Nikon BLE device recognition.

The app was forked from a BLE GPS sync project; the original BLE architecture for Ricoh/Sony
still exists as secondary code, while the USB photo sync is now the primary feature.

## Tech Stack

- **Language**: Kotlin 2.3.0
- **UI**: Jetpack Compose with Material 3
- **Build**: Gradle Kotlin DSL with version catalog (`gradle/libs.versions.toml`)
- **BLE**: Kable library
- **Persistence**: Proto DataStore (paired devices) + SharedPreferences (USB dedup, prefs)
- **DI**: Metro (compile-time dependency injection)
- **Logging**: Khronicle (`com.juul.khronicle.Log`)
- **Target**: Android 13+ (API 33), tested on Pixel 9 + Android 15
- **UI Language**: Chinese (`res/values/strings.xml`)
- **Test camera**: Nikon Z30 (USB); Ricoh GR IIIx, Sony Alpha (BLE)

## Project Structure

```
app/src/main/kotlin/dev/sebastiano/camerasync/
├── usb/                        # ★ USB photo sync (PRIMARY)
│   ├── NikonUsbManager.kt      # MTP open/close, camera info, storage enum, photo listing, download
│   ├── GalleryViewModel.kt     # USB detection, permission flow, state machine, selection, transfer
│   ├── GalleryScreen.kt        # 3-column staggered grid, folder browsing, long-press selection, progress
│   ├── PhotoSyncManager.kt     # Import deduplication via SharedPreferences
│   ├── UsbSyncService.kt       # Short-lived foreground service for background auto-sync
│   ├── UsbSyncCoordinator.kt   # Reusable auto-sync lifecycle (used by service + UI)
│   └── UsbSyncPreferences.kt   # Per-camera USB sync preferences (auto-sync toggle, format filter)
├── vendors/                    # BLE vendor implementations (SECONDARY)
│   ├── ricoh/                  # Ricoh GR series — GPS + time sync (tested)
│   ├── sony/                   # Sony Alpha series — GPS + time sync with DD30/DD31 locking
│   └── nikon/                  # Nikon BLE advertisement recognition only (no GPS sync via BLE)
├── devicesync/                 # BLE multi-device sync coordination
├── devices/                    # Paired devices list UI & ViewModel
├── pairing/                    # BLE scanning & pairing UI
├── domain/                     # Shared domain models & vendor strategy interfaces
├── data/                       # Proto DataStore repositories
├── di/                         # Metro DI graph (AppGraph.kt) & ViewModel factory
├── ui/theme/                   # Material 3 theme
├── firmware/                   # Firmware update checker & WorkManager workers
├── feedback/                   # In-app issue reporter
├── logging/                    # Khronicle log engine & log viewer screen
├── widget/                     # Home screen sync toggle widget
├── work/                       # Scan restart worker
├── MainActivity.kt             # Single-activity entry point
├── CameraSyncApp.kt            # Application class
├── NavRoute.kt                 # Navigation route definitions (USB Gallery + BLE screens)
└── *.kt                        # Permissions, BLE extensions
```

## Architecture: USB Photo Sync (Primary)

The USB photo sync uses Android's standard `android.mtp.MtpDevice` API — no protocol
reverse-engineering is needed. The camera presents as an MTP/PTP USB device, and Android
handles all low-level communication.

### Key Source Files

| File | Responsibility |
|---|---|
| `NikonUsbManager.kt` | Wraps `MtpDevice`: open/close, `getCameraInfo()`, `getStorages()`, `listPhotos()`, `listFolders()`, `downloadPhoto()` |
| `GalleryViewModel.kt` | USB device detection via `UsbManager`, permission broadcast receiver, connection lifecycle, folder navigation, selection tracking, photo transfer to MediaStore |
| `GalleryScreen.kt` | Compose UI: `LazyVerticalStaggeredGrid` with 3 columns, folder hierarchy breadcrumb, long-press multi-select, transfer progress bar, pull-to-refresh |
| `PhotoSyncManager.kt` | `SharedPreferences`-backed dedup: tracks `s{storageId}_h{handle}` keys |
| `UsbSyncService.kt` | Short-lived foreground service (`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`). Starts on `USB_DEVICE_ATTACHED`, runs sync, stops itself |
| `UsbSyncCoordinator.kt` | Reusable sync logic: find Nikon device (VID 0x04B0), open MTP, enumerate storages + photos, download new ones, return `SyncResult` |
| `UsbSyncPreferences.kt` | Per-camera prefs: `autoSyncEnabled`, `downloadFormat` (ALL / JPEG_ONLY / RAW_ONLY) |

### USB Permission Flow

```
USB_DEVICE_ATTACHED broadcast detected by GalleryViewModel
  → UsbManager.requestPermission(device) via PendingIntent
  → System dialog: "Allow CameraSync to access Nikon Z30?"
  → User grants → BroadcastReceiver receives USB_PERMISSION_GRANTED
  → UsbManager.openDevice(device) → UsbDeviceConnection
  → MtpDevice(usbDevice).open(connection) → ready
```

### GalleryViewModel State Machine

`GalleryState` is a `sealed interface` driving the Compose UI:

```
Disconnected ──USB attached──→ Connecting ──MTP open──→ Loading
                                                            │
                                     ┌──────────────────────┤
                                     ▼                      ▼
                                 Browsing                 Empty / Error
                                     │
                          ┌──────────┼──────────┐
                          ▼                     ▼
                     Transferring          TransferDone
```

- **Disconnected**: No USB camera. UI shows "connect camera" prompt.
- **Connecting**: `MtpDevice.open()` in progress.
- **Loading**: MTP connected, enumerating storages and photo handles via BFS folder walk.
- **Browsing**: Folder list or photo grid displayed. User can navigate folders, long-press to
  select, tap "Download" to transfer.
- **Empty**: Camera connected but no photos found.
- **Error**: Connection or enumeration failed. Show retry button.
- **Transferring**: `MtpDevice.importFile()` in progress. `LinearProgressIndicator` shows
  `synced / total`.
- **TransferDone**: All selected photos saved. Shows count of newly imported files.

### Photo Transfer Pipeline

```
MtpDevice.importFile(handle) → temp file in cache dir
  → ExifInterface read for date extraction
  → MIME type determined from file extension (NEF→image/x-nikon-nef, HEIC→image/heic, etc.)
  → MediaStore.insert() with IS_PENDING=1
  → Copy temp file bytes to ContentResolver output stream
  → MediaStore.update() with IS_PENDING=0
  → Delete temp file
  → PhotoSyncManager.markAsImported(storageId, handle)
```

Photos are saved to: `Pictures/CameraSync/Nikon Z30/YYYY-MM-DD/`

### RAW + JPEG Grouping

Nikon cameras store NEF (RAW) and JPEG pairs with the same base name (e.g., `DSC_0001.NEF`
+ `DSC_0001.JPG`). `GalleryViewModel` groups these into `PhotoGroup` entries — one card per
capture, with a "RAW" badge when NEF exists. Both files are transferred together.

### Deduplication

`PhotoSyncManager` uses `SharedPreferences` with keys `s{storageId}_h{handle}`. MTP handles
are session-scoped (invalidated on USB disconnect), so stale handles are harmless — they
simply won't match future enumerations.

### Background Auto-Sync

When `autoSyncEnabled` is true in `UsbSyncPreferences`:
1. `USB_DEVICE_ATTACHED` broadcast triggers `UsbSyncService`.
2. Service starts foreground notification ("Nikon USB 同步") and calls `UsbSyncCoordinator.syncOnce()`.
3. All new photos are downloaded silently.
4. Notification updates with "同步完成" and count, then service stops after 3s.

### USB Device Filter

`res/xml/nikon_usb_device_filter.xml` matches Nikon's USB Vendor ID (0x04B0 / 1200 decimal):
```xml
<usb-device vendor-id="1200" />
```
This is used in `AndroidManifest.xml` for the USB accessory filter.

## Architecture: BLE GPS Sync (Secondary / Legacy)

The BLE subsystem uses a **Strategy Pattern** to support multiple camera vendors through
a common abstraction layer.

### Key Components

| Component | Path | Purpose |
|---|---|---|
| `CameraVendor` | `domain/vendor/CameraVendor.kt` | Strategy interface: GATT spec, protocol, device recognition |
| `CameraVendorRegistry` | `domain/vendor/CameraVendorRegistry.kt` | Registry of all vendors, aggregates scan filter UUIDs |
| `VendorConnectionDelegate` | `domain/vendor/VendorConnectionDelegate.kt` | Encapsulates per-vendor connection/sync lifecycle |
| `DefaultConnectionDelegate` | `domain/vendor/DefaultConnectionDelegate.kt` | Standard delegate implementation |
| `MultiDeviceSyncCoordinator` | `devicesync/MultiDeviceSyncCoordinator.kt` | Core sync logic, vendor-agnostic |
| `MultiDeviceSyncService` | `devicesync/MultiDeviceSyncService.kt` | Long-running foreground service for BLE sync |

### Vendor Implementations

- **Ricoh** (`vendors/ricoh/`): `RicohGattSpec`, `RicohProtocol`, `RicohCameraVendor`.
  GPS + date/time sync for GR III, GR IIIx (tested).
- **Sony** (`vendors/sony/`): `SonyGattSpec`, `SonyProtocol`, `SonyCameraVendor`,
  `SonyConnectionDelegate`. GPS + time sync for Alpha series with DD30/DD31 locking
  handshake.
- **Nikon** (`vendors/nikon/`): `NikonGattSpec`, `NikonProtocol`, `NikonCameraVendor`,
  `NikonConnectionDelegate`. BLE advertisement recognition only — does not perform GPS
  sync. The actual Nikon photo sync goes through USB/MTP.

### Data Layer

- `PairedDevicesRepository` — Interface for managing paired devices (add, remove, enable/disable)
- `DataStorePairedDevicesRepository` — Proto-based persistence implementation
- `CameraRepository` / `KableCameraRepository` — BLE scanning and connection management
- `LocationRepository` / `FusedLocationRepository` — GPS location from Fused Location Provider
- `SyncStatusRepository` — Centralized sync status tracking

### BLE Multi-Vendor Pattern

To add a new BLE vendor:
1. Create vendor package in `vendors/[vendor-name]/`
2. Implement `CameraGattSpec` with BLE UUIDs
3. Implement `CameraProtocol` with encoding/decoding logic
4. Implement `VendorConnectionDelegate` (or use `DefaultConnectionDelegate`)
5. Implement `CameraVendor` with device recognition and delegate creation
6. Register the vendor in `AppGraph.kt`'s `provideVendorRegistry()` method

For full details, see `docs/MULTI_VENDOR_SUPPORT.md`.

## Key Architectural Notes

- **Single Activity**: `MainActivity.kt` hosts all screens via `NavRoute` sealed interface.
  Navigation: Gallery (primary) → GalleryFolder, DevicesList → Pairing, LogViewer.
- **Metro DI**: `AppGraph.kt` is the compile-time DI graph. All singletons and factories
  are defined there. ViewModels are created by `MetroViewModelFactory`.
- **Chinese UI**: All user-facing strings are in Chinese in `res/values/strings.xml`. Use
  `stringResource()` or `context.getString()`. Log messages and code comments are in English.
- **USB and BLE are independent**: The two subsystems share only the `MainActivity` shell,
  the `NavRoute` enum, and the Application class. They have separate services, coordinators,
  ViewModels, and preferences.
- **Mermaid diagrams**: Architecture documentation (CONTRIBUTING.md, docs/) uses Mermaid
  flowcharts. GitHub renders these natively.

### Dispatcher Injection for Testability

**Always inject dispatchers** into ViewModels and other classes that launch coroutines on
`Dispatchers.IO` or `Dispatchers.Default`. This allows tests to control time advancement
with `runTest` and `advanceUntilIdle()`.

#### Pattern

```kotlin
class MyViewModel(
    private val repository: MyRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    fun doSomething() {
        viewModelScope.launch(ioDispatcher) {
            repository.fetchData()
        }
    }
}

// In tests
@Test
fun `my test`() = runTest {
    val testDispatcher = UnconfinedTestDispatcher()
    val viewModel = MyViewModel(
        repository = fakeRepository,
        ioDispatcher = testDispatcher,
    )
    viewModel.doSomething()
    advanceUntilIdle()
    // assertions...
}
```

#### Why
When using `runTest`, time is virtual. Coroutines launched on `Dispatchers.IO` use **real time**,
causing `advanceUntilIdle()` to not wait for IO operations. By injecting the dispatcher, tests
can pass `UnconfinedTestDispatcher()` or `StandardTestDispatcher()`, making all coroutines use
virtual time.

#### Examples in this codebase
- `DevicesListViewModel`: Accepts `ioDispatcher` parameter
- `PairingViewModel`: Same pattern for pairing operations
- `MultiDeviceSyncCoordinator`: Accepts `CoroutineScope` for complete control in tests

## Development Guidelines

### Code Style
- Follow Kotlin coding conventions
- Use `ktfmt` with `kotlinlang` style: run `./gradlew ktfmtFormat` before committing
- Use Android Architecture Components where applicable
- All new interfaces should have corresponding fake implementations for testing
- Logging via `com.juul.khronicle.Log`, not `android.util.Log`
- `TAG` constants at file level, private

### Key Features (All)
1. **USB Photo Transfer** (primary) — Browse, select, download photos from Nikon cameras via USB-C
2. **RAW+JPEG Grouping** — NEF/JPEG pairs displayed as a single item with RAW badge
3. **Background USB Sync** — Foreground service auto-downloads new photos when camera is plugged in
4. **Deduplication** — Already-imported photos are automatically skipped
5. **Multi-Device BLE Support** (secondary) — Pair and sync multiple BLE cameras simultaneously
6. **Multi-Vendor BLE** (secondary) — Works with Ricoh, Sony, and extensible
7. **Auto-reconnection BLE** — Auto-reconnect to enabled devices when in range
8. **Battery Optimization Warnings** — Proactive UI warnings with OEM-specific settings links
9. **Issue Reporting** — Integrated feedback with system info, BLE metadata, and app logs
10. **Firmware Updates** — Automatic detection and notification of firmware updates for paired cameras

## Building & Running

```bash
./gradlew build                  # Full build
./gradlew installDebug           # Install on connected device
./gradlew test                   # Run all unit tests
./gradlew test --tests "fully.qualified.TestClassName"  # Single test class
./gradlew ktfmtFormat            # Format code
```

## Testing

### Unit Tests
- Use `kotlinx.coroutines.test.runTest` with virtual time
- Fake implementations live in `app/src/test/kotlin/`
- `TestGraphFactory` provides fake dependencies
- Inject dispatchers so tests can use `UnconfinedTestDispatcher` (see pattern above)
- Each vendor has dedicated tests: `RicohCameraVendorTest`, `SonyCameraVendorTest`, etc.
- `CameraVendorRegistryTest` tests registry logic
- Protocol encoding/decoding has dedicated test classes

### USB Integration Testing
USB MTP requires a physical camera:
1. `./gradlew installDebug`
2. Connect Nikon Z30 (or other MTP camera) via USB-C cable
3. Grant USB permission when prompted
4. Verify gallery loads, photos are selectable, transfer works
5. Check files appear in `Pictures/CameraSync/`
6. Monitor logcat: `adb logcat | grep -E "NikonUsbManager|GalleryVM|UsbSync"`

### Test Device
- **Primary**: Pixel 9 + Android 15 + Nikon Z30 (USB) + Ricoh GR IIIx (BLE)
- **Also tested**: Sony Alpha ILCE-7M4 (BLE)

## Common Tasks

### Adding USB support for a new Nikon model
Most Nikon cameras use VID 0x04B0 and standard MTP — they should work out of the box.
If a model behaves differently:
1. Verify VID matches in `res/xml/nikon_usb_device_filter.xml`
2. Test MTP enumeration and download
3. Update `README.md` supported cameras list

### Adding USB support for another brand
1. Create a new USB manager class (like `CanonUsbManager.kt`) following `NikonUsbManager.kt` pattern
2. Add USB device filter XML with the brand's VID
3. Update `GalleryViewModel` to detect new VID and instantiate correct manager
4. Update `UsbSyncCoordinator` and `UsbSyncService` for the new brand
5. Add tests

### Adding a new BLE vendor
1. Create `vendors/[name]/` package
2. Implement `CameraGattSpec`, `CameraProtocol`, `VendorConnectionDelegate`, `CameraVendor`
3. Register in `AppGraph.kt`'s `provideVendorRegistry()`
4. Add tests following existing vendor test patterns

## AI Navigation Guide: Intent → Files

### Quick Lookup: "I need to..."

| User says... | Primary files | What to do |
|---|---|---|
| 加个筛选/排序功能 | `usb/GalleryScreen.kt` (+ GalleryViewModel.kt) | Add filter UI + ViewModel filter logic |
| 修改照片传输逻辑 | `usb/NikonUsbManager.kt` → `usb/GalleryViewModel.kt` | MTP ops → orchestration |
| 加个设置开关 | `usb/UsbSyncPreferences.kt` → `res/values/strings.xml` → `usb/GalleryScreen.kt` | SharedPreferences key → string → Switch composable |
| 加新页面/屏幕 | `NavRoute.kt` → `MainActivity.kt` (NavEntry) → new `Screen.kt` + `ViewModel.kt` | sealed interface route → when branch |
| 改 USB 连接逻辑 | `usb/GalleryViewModel.kt` (onPlugged, connectAndBrowse, receiver) | BroadcastReceiver + UsbManager |
| 改 MTP 枚举/下载 | `usb/NikonUsbManager.kt` | MtpDevice API wrappers |
| 改相册网格布局 | `usb/GalleryScreen.kt` (BrowsingContent, PhotoCell) | LazyVerticalStaggeredGrid |
| 加 raw/jpg 分组逻辑 | `usb/GalleryViewModel.kt` (groupByBaseFilename) | File extension grouping |
| 改导航/路由 | `NavRoute.kt` → `MainActivity.kt` (backStack, NavEntry) | sealed interface + NavDisplay |
| 改主题/颜色 | `ui/theme/` → `GalleryScreen.kt` (MaterialTheme) | ColorScheme + shapes |
| 加 BLE 厂商支持 | `vendors/[name]/` (GattSpec, Protocol, CameraVendor, ConnectionDelegate) → `di/AppGraph.kt` | Strategy pattern |
| 改通知文本 | `usb/UsbSyncService.kt` → `res/values/strings.xml` | Notification channel + strings |

### Feature Area → Code Location

| Feature | Package | Key Entry Points |
|---|---|---|
| USB 照片同步 | `usb/` | GalleryScreen, GalleryViewModel, NikonUsbManager |
| USB 后台同步 | `usb/` | UsbSyncService, UsbSyncCoordinator |
| USB 偏好设置 | `usb/` | UsbSyncPreferences |
| 去重 | `usb/` | PhotoSyncManager |
| BLE 设备管理 | `devices/` | DevicesListScreen, DevicesListViewModel |
| BLE 配对 | `pairing/` | PairingScreen, PairingViewModel |
| BLE GPS 同步 | `devicesync/` | MultiDeviceSyncCoordinator, MultiDeviceSyncService |
| BLE 厂商实现 | `vendors/ricoh/`, `vendors/sony/`, `vendors/nikon/` | CameraVendor implementations |
| 固件更新 | `firmware/` | FirmwareUpdateCheckWorker |
| 导航 | 根包 | NavRoute, MainActivity |
| DI / 依赖 | `di/` | AppGraph |
| 主题 | `ui/theme/` | Theme, Color, Type |
| 日志 | `logging/` | Khronicle Log engine |
| 权限 | `permissions/` | PermissionsScreen |

### How to Add a New Composable Screen

**Pattern** (extracted from GalleryScreen):

1. Add route to `NavRoute.kt`:
```kotlin
@Parcelize @Serializable data object MyNewScreen : NavRoute
```

2. Create ViewModel (in appropriate package):
```kotlin
class MyNewViewModel(private val app: Application) {
    // State as mutableStateOf
    // CoroutineScope(Dispatchers.IO + SupervisorJob())
    // BroadcastReceiver for system events
    fun start() { /* register receivers */ }
    fun stop() { scope.cancel(); /* unregister */ }
}
```

3. Create Composable screen:
```kotlin
@Composable
fun MyNewScreen(viewModel: MyNewViewModel, onNavigateBack: () -> Unit) {
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    Scaffold(topBar = { TopAppBar(...) }) { padding ->
        // when(state) { ... }
    }
}
```

4. Wire in `MainActivity.kt` NavEntry `when` block:
```kotlin
is NavRoute.MyNewScreen -> {
    val vm = remember { MyNewViewModel(app) }
    MyNewScreen(viewModel = vm, onNavigateBack = { backStack.removeLastOrNull() })
}
```

5. Navigate from other screens:
```kotlin
backStack.add(NavRoute.MyNewScreen)
```

### State Management Pattern

This project uses the pattern: **ViewModel holds `mutableStateOf<SealedInterface>` → Composable observes via `.value` → `when` branch renders UI**.

```kotlin
// ViewModel
private val _state = mutableStateOf<GalleryState>(GalleryState.Disconnected)
val state: State<GalleryState> = _state

// Screen
val s = viewModel.state.value
when (s) {
    is GalleryState.Browsing -> BrowsingContent(s, viewModel)
    is GalleryState.Loading -> LoadingContent(s.message)
    // ...
}
```

### Coroutine Pattern

ViewModels use: `private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())`.
- `scope.launch { ... }` for background work
- `scope.cancel()` in `stop()` for cleanup
- `withContext(currentCoroutineContext())` (NOT `this.coroutineContext`) to check `isActive`

### BroadcastReceiver Pattern

Register in `start()`, unregister in `stop()`:
```kotlin
private val receiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> onPlugged(...)
            UsbManager.ACTION_USB_DEVICE_DETACHED -> { cleanup() }
        }
    }
}
fun start() {
    app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
}
fun stop() {
    try { app.unregisterReceiver(receiver) } catch (_: Exception) {}
}
```

### Navigation Pattern

The project uses a custom stack-based navigation with `NavDisplay`:
- `backStack: SnapshotStateList<NavRoute>` — a simple list stack
- `NavRoute` is a `sealed interface : Parcelable` — type-safe routes
- `NavDisplay` renders the top of stack via `NavEntry { when(route) {...} }`
- Push: `backStack.add(NavRoute.X)`, Pop: `backStack.removeLastOrNull()`
- Transition: slide horizontally + fade for push/pop
- **No Jetpack Navigation library** — custom lightweight implementation

### Adding a String (Chinese UI)

1. Add to `res/values/strings.xml`:
```xml
<string name="usb_my_label">我的标签</string>
```
2. Use in Compose: `stringResource(R.string.usb_my_label)`
3. Use in non-Compose: `context.getString(R.string.usb_my_label)`

### Adding a Preference

1. Add key + getter/setter to `usb/UsbSyncPreferences.kt`
2. Add UI toggle in `GalleryScreen.kt` (or settings section)
3. Update ViewModel to read preference on action

### File Modification Impact

When you change a file, here's what else might need updating:

| File changed | Likely cascading changes |
|---|---|
| `NavRoute.kt` | `MainActivity.kt` (NavEntry when) |
| `NikonUsbManager.kt` (new method) | `GalleryViewModel.kt` (caller) |
| `GalleryViewModel.kt` (state) | `GalleryScreen.kt` (UI rendering) |
| `UsbSyncPreferences.kt` (new key) | `GalleryScreen.kt` (UI toggle), `strings.xml` (label) |
| `strings.xml` (new string) | Compose: `stringResource()`, Non-Compose: `getString()` |
| `AppGraph.kt` (new binding) | Any file that uses the new dependency |
| `AndroidManifest.xml` (new intent-filter) | Corresponding `BroadcastReceiver` registration |

## Important Considerations

### USB
- The camera must be in **MTP/PTP** USB mode (set on the camera)
- Use a USB-C to USB-C cable (C2C); some A-to-C cables don't support MTP
- USB permission must be granted each time the device is attached (Android security model)
- MTP handles are session-scoped; they become invalid when the camera is disconnected
- Large NEF files (20-40 MB each) take several seconds to transfer over USB 2.0 speeds

### BLE
- Devices must have Bluetooth pairing enabled on the camera side
- Background operation requires proper battery optimization exemptions
- Location permissions are critical for GPS sync (ACCESS_FINE_LOCATION)
- Location collection runs at 60-second intervals when devices are connected
- Each camera vendor may have different capabilities and requirements

### Battery Optimization
The app displays a warning card when battery optimizations are active, as they can interfere
with background BLE connections and foreground service reliability. OEM-specific settings
are supported for Xiaomi, Huawei, Oppo, Samsung, and others.

## License

Apache License 2.0 — See [LICENSE](LICENSE) file for details.

---

*This document is maintained to help AI assistants understand the project context and provide relevant assistance.*
