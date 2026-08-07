# 3D Measure — Android App

An Android app that uses the camera to **measure the real-world size and speed of objects** via reference-object calibration. Place a coin, credit card, or custom-sized object next to the target, and the app calculates dimensions using OpenCV contour detection. Track moving objects to measure their speed. Images and measurement data are saved locally to SQLite (Room).

## Features

- **Dashboard** — home screen with quick access to Measure Size and Measure Speed
- **Measure Size** — live camera preview with real-time contour overlay (green = reference, red = target)
- **Reference object calibration** — enter a custom reference size for pixel-to-real-world conversion
- **Real-time 3D dimension calculation** in mm, cm, m, or inches
- **Measure Speed** — track a moving object and measure its speed in m/s and km/h
- **Capture & save** — photo + dimensions + timestamp stored in Room/SQLite
- **Speed tracking save** — max speed, average speed, distance, and duration stored in Room/SQLite
- **Measurement history** with separate Size and Speed tabs
- **Detail view** showing full data and metadata, with delete option

## Screens

| Screen | Purpose |
|--------|---------|
| Dashboard | Home screen with Measure Size and Measure Speed buttons |
| Measure Size | Live preview, contour overlay, calibration, capture button |
| Measure Speed | Live preview, tap-to-select object, track speed in m/s + km/h |
| History | Tabs for Size and Speed measurements |
| Size Detail | Full image + 3D dimensions + metadata |
| Speed Detail | Max/avg speed, distance, duration + metadata |

## Tech Stack

- **Kotlin + Jetpack Compose** — modern declarative UI
- **CameraX** — camera preview, image capture, and frame analysis
- **OpenCV 4.9.0** — contour detection and shape analysis
- **Room** — SQLite persistence
- **Hilt** — dependency injection
- **Navigation Compose** — screen navigation
- **Coil** — image loading

## Measurement Algorithm

1. User selects a reference object of known real-world size
2. Camera frames are processed via OpenCV (grayscale → blur → Canny edges → contour detection)
3. Reference object is identified by shape matching (aspect ratio)
4. Pixels-per-mm ratio is calculated from the reference
5. Target object area is computed in real units
6. Accuracy: approximately ±10-20% (depends on lighting, angle, and camera distance)

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17**
- **Android SDK** with:
  - Compile SDK 34+
  - Build Tools 34.0.0+
  - Platform: `android-34` or newer
- **NDK** (for OpenCV native libraries — included with Android Studio)
- A **physical Android device** (emulator camera won't detect real objects)

## Build Method

### Option 1: Android Studio (Recommended)

1. **Clone or open** the project in Android Studio
2. **Sync Gradle** — Android Studio will download dependencies automatically
3. **Connect a physical device** via USB (enable Developer Options → USB Debugging)
4. **Run** → Select your device → Click **Run 'app'** (Shift+F10)

The APK will be installed and launched on the device.

### Option 2: Command Line

```bash
# Navigate to project root
cd d:\dev/android

# Build debug APK
./gradlew assembleDebug

# APK output location:
# app/build/outputs/apk/debug/app-debug.apk
```

To install directly on a connected device:

```bash
./gradlew installDebug
```

### Option 3: Generate APK File Manually

After building, transfer the APK to your device:

```bash
# Copy APK to device storage, then install
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Option 4: Build a Signed Release APK

The project includes a release signing configuration. Build a signed release APK:

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

The release APK is automatically signed using the keystore at `release.keystore`.

**Keystore details:**
| Property | Value |
|----------|-------|
| Keystore file | `release.keystore` |
| Key alias | `areameasure-key` |
| Store password | `areameasure123` |
| Key password | `areameasure123` |
| Validity | 10,000 days |
| Algorithm | RSA 2048-bit |

> **Security note:** The keystore password is committed here for local development convenience. For production distribution, generate your own keystore with a strong password and keep it secure — never commit it to version control.

**To verify the signature:**

```bash
# Using apksigner (from Android SDK build-tools)
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

# Using jarsigner
jarsigner -verify -verbose app/build/outputs/apk/release/app-release.apk
```

**To install the release APK on a device:**

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## Project Structure

```
AreaMeasure/
├── app/                          # Main application module
│   ├── build.gradle.kts          # App build config (deps, SDK, Compose)
│   └── src/main/
│       ├── AndroidManifest.xml   # Permissions, activities
│       ├── java/com/example/areameasure/
│       │   ├── MainActivity.kt           # Entry point
│       │   ├── AreaMeasureApplication.kt # App class (OpenCV init)
│       │   ├── di/AppModule.kt           # Hilt dependency injection
│       │   ├── data/
│       │   │   ├── local/        # Room database, DAO, entities
│       │   │   ├── model/        # Domain models (Measurement, ReferenceObject, UnitOfMeasure)
│       │   │   └── repository/   # MeasurementRepository
│       │   ├── domain/
│       │   │   └── AreaCalculator.kt     # Pure area calculation logic
│       │   ├── processing/
│       │   │   └── ImageProcessor.kt     # OpenCV contour detection pipeline
│       │   ├── camera/
│       │   │   ├── CameraManager.kt      # CameraX lifecycle management
│       │   │   └── CameraFrameAnalyzer.kt # Frame-by-frame analysis
│       │   └── ui/
│       │       ├── theme/        # Compose theme
│       │       ├── navigation/   # NavGraph (Camera → History → Detail)
│       │       ├── camera/       # CameraScreen + CameraViewModel
│       │       ├── history/      # HistoryScreen + HistoryViewModel
│       │       └── detail/       # DetailScreen + DetailViewModel
│       └── res/                  # Launcher icons, strings, themes
├── opencv/                       # OpenCV 4.9.0 Android SDK (module)
├── build.gradle.kts              # Root build config (AGP, Kotlin, Hilt plugins)
├── settings.gradle.kts           # Module includes
├── gradle.properties             # Gradle JVM args, AndroidX
├── local.properties              # SDK path (auto-configured)
├── gradlew / gradlew.bat         # Gradle wrapper scripts
└── gradle/wrapper/               # Gradle wrapper JAR + properties
```

## Configuration

| Setting | Value | File |
|---------|-------|------|
| Compile SDK | 34 | `app/build.gradle.kts` |
| Target SDK | 34 | `app/build.gradle.kts` |
| Min SDK | 26 | `app/build.gradle.kts` |
| Application ID | `com.example.areameasure` | `app/build.gradle.kts` |
| Kotlin | 1.9.22 | `build.gradle.kts` |
| AGP | 8.2.2 | `build.gradle.kts` |
| Compose Compiler | 1.5.8 | `app/build.gradle.kts` |
| OpenCV | 4.9.0 | `opencv/` module |

## Permissions

The app requires:

- **`CAMERA`** — for live preview and capture (requested at runtime)

No internet or storage permissions needed (images saved to app-internal storage).

## Usage

### Measuring Size

1. Launch the app and **grant camera permission**
2. Tap **Measure Size** on the dashboard
3. Place your **reference object** (e.g., a quarter) next to the object you want to measure
4. Hold the camera so both objects are clearly visible and well-lit
5. Watch for the **contour outlines** on screen — tap the target object to select it
6. **Calibrate** by entering the real-world width of the selected object
7. When the 3D dimensions stabilize, tap **Capture**
8. Enter a **name** for the measurement and save

### Measuring Speed

1. Launch the app and **grant camera permission**
2. Tap **Measure Speed** on the dashboard
3. Point the camera at a moving object and tap it to select
4. **Calibrate** by entering the real-world width of the object (for scale)
5. Tap **Track** — the app follows the object and displays live speed
6. View real-time speed in **m/s** (primary) and **km/h** (secondary)
7. Tap **Stop & Save** to save the measurement with max/avg speed, distance, and duration

### History

- View all saved measurements in **History** with separate **Size** and **Speed** tabs
- Tap any entry to see its detail view
- Delete measurements from the detail view

## Build Outputs

| APK | Path | Size |
|-----|------|------|
| Debug (unsigned) | `app/build/outputs/apk/debug/app-debug.apk` | ~146 MB |
| Release (signed) | `app/build/outputs/apk/release/app-release.apk` | ~140 MB |

| Artifact | Path |
|----------|------|
| Keystore | `release.keystore` |
| Database | App-private storage: `databases/area_measure_db` |
| Images | App-internal storage: `measure_<timestamp>.jpg` |

## Notes

- The large APK size is due to OpenCV native libraries for all CPU architectures (arm64-v8a, armeabi-v7a, x86, x86_64). Use [APK splits](https://developer.android.com/studio/build/configure-apk-splits) or [App Bundles](https://developer.android.com/guide/app-bundle) to reduce download size for distribution.
- Measurement accuracy depends on camera angle (top-down view is best), lighting, and the contrast between objects and background.
- For best results, place objects on a flat, contrasting surface.
