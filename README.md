# CameraSync

USB wired photo sync for Nikon series cameras — quickly transfer photos from your camera to your Android phone over a USB-C cable.

> Based on [rock3r/CameraSync](https://github.com/rock3r/CameraSync) by Sebastiano Poggi (BLE GPS sync), rewritten and maintained by Chilfish with a new focus on USB photo transfer for Nikon cameras.

## Features

- **USB Wired Photo Sync**: Connect your Nikon camera via USB-C cable, browse photos, and download them directly to your phone.
- **Gallery Browsing**: 3-column grid with folder navigation on the camera's SD card and live preview thumbnails.
- **RAW+JPEG Grouping**: NEF and JPEG pairs are shown as a single grouped item so you can transfer both at once.
- **Selective Transfer**: Long-press to pick specific photos, or transfer an entire batch in one tap.
- **Auto-Detect**: The camera is detected automatically as soon as the USB cable is plugged in — no manual pairing flow.
- **Background Sync**: A foreground service keeps transfers running even when the app moves to the background.
- **Deduplication**: Photos already imported during a previous sync are automatically skipped.
- **Material 3 UI**: Modern interface built with Jetpack Compose.

## Supported Cameras

- **Nikon series cameras** (tested with Z30; other MTP-capable Nikon models should work)

## Requirements

### Hardware

- An Android device running Android 13 (API 33) or higher.
- A Nikon camera with MTP/PTP USB support.
- A USB-C to USB-C cable (C2C).
- Camera USB mode set to **MTP/PTP**.

### Permissions

- **USB**: For detecting and communicating with the camera.
- **Notifications**: For the foreground service that keeps background transfers running.
- **Photos/Media**: For saving transferred photos via MediaStore.

## How It Works

1. Plug your Nikon camera into your phone with a USB-C cable.
2. Grant USB permission when the system prompt appears.
3. The app auto-detects the camera and connects via MTP.
4. Browse photos stored on the camera's SD card.
5. Select the photos you want (or tap "Download All").
6. Photos are saved to `Pictures/CameraSync/{camera model}/YYYY-MM-DD/`.

## Architecture

The app uses Android's built-in `android.mtp.MtpDevice` API — no protocol reverse-engineering is required. The `usb/` package handles MTP connection, photo enumeration, and download.

> The original BLE GPS sync subsystem for Ricoh/Sony was **removed in 2026-08-02** (commit `a385378`). USB/MTP photo sync is the only feature path; BLE protocol docs remain in `docs/ricoh/` and `docs/sony/` for historical reference.

## Setup & Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/yourusername/CameraSync.git
   cd CameraSync
   ```

2. **Open in Android Studio**:
   Open the project in Android Studio (Ladybug or newer recommended).

3. **Build the project**:
   ```bash
   ./gradlew build
   ```

## Running the App

To install and run the debug version of the app on your connected device:

```bash
./gradlew installDebug
```

## Development & Scripts

This project uses Gradle with Kotlin DSL.

### Key Gradle Tasks

- `./gradlew assembleDebug`: Build the debug APK.
- `./gradlew bundleRelease`: Build the release App Bundle.
- `./gradlew test`: Run unit tests.
- `./gradlew connectedAndroidTest`: Run instrumented tests on a device.
- `./gradlew ktfmtFormat`: Format the code using ktfmt.

### Environment Variables

No specific environment variables are required for a standard build. Ensure `JAVA_HOME` is set to a
compatible JDK (JDK 11+).

## Project Structure

- `app/src/main/kotlin/dev/sebastiano/camerasync/usb/` — USB photo sync (Nikon series)
    - `NikonUsbManager.kt` — MTP device operations and photo enumeration
    - `GalleryViewModel.kt` — Connection lifecycle and transfer state management
    - `GalleryScreen.kt` — Primary UI (3-column grid, folder browsing, selection)
    - `PhotoSyncManager.kt` — Import deduplication
    - `LocalPhotosViewModel.kt` — Local photo browsing via Coil 3 + MediaStore
    - `UsbSyncService.kt` — Foreground service for background transfers
    - `UsbSyncCoordinator.kt` — Auto-sync lifecycle and hot-plug detection
    - `UsbSyncPreferences.kt` — User preferences
- `app/src/main/kotlin/dev/sebastiano/camerasync/logging/` — Khronicle log repository + log viewer
- `app/src/main/kotlin/dev/sebastiano/camerasync/settings/` — Settings screen
- `app/src/main/kotlin/dev/sebastiano/camerasync/di/` — Metro dependency graph (`AppGraph`)
- `app/src/main/kotlin/dev/sebastiano/camerasync/ui/theme/` — Material 3 theme (Color, Type, Theme)
- `app/src/test/` — Unit tests

## Technical Stack

- **Language**: [Kotlin](https://kotlinlang.org/) (2.3.0)
- **UI Framework**: [Jetpack Compose](https://developer.android.com/compose) + Material 3 + Navigation 3
- **Image Loading**: [Coil 3](https://coil-kt.github.io/coil/) (local photos via MediaStore)
- **Logging**: [Khronicle](https://github.com/JuulLabs/khronicle)
- **Persistence**: SharedPreferences (import deduplication + preferences)
- **Dependency Injection**: [Metro](https://github.com/ZacSweers/metro) (compile-time DI framework)
- **Format/Static Analysis**: ktfmt (kotlinlang) + [detekt](https://detekt.dev/) with compose-rules
- **Dependency Management**: Gradle Version Catalogs (`libs.versions.toml`)

## Testing

Run unit tests:

```bash
./gradlew test
```

> [!NOTE]
> Test camera: **Nikon Z30** (MTP/PTP). See [USB sync verification notes](docs/nikon/USB_SYNC.md#9-verified-with) for device specifics.

## License

Copyright 2024 Sebastiano Poggi  
Copyright 2026 Chilfish

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
