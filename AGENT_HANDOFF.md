# ApexDrive — Agent & Developer Handoff Guide

> **Target Audience:** AI Coding Agents (Claude Code, Antigravity, Cursor, Copilot) and Human Developers onboarding onto the **ApexDrive** project.

---

## 1. Project Overview & Mission

**ApexDrive** is a custom, automotive-grade Android launcher tailored specifically for aftermarket car head units (e.g., Hyundai Santa Fe running Allwinner T3 / TS / FYT / GOC automotive ROMs).

* **Display Target:** Fixed horizontal landscape orientation (`1024×600` or `1280×720`).
* **Design Language:** Matte Cockpit Aesthetic — deep blacks (`#0C0D10`), dark charcoal panels (`#13151A`), subtle slate borders (`#232730`), and switchable automotive gauge accents (Default Warm Amber `#E5A93C`, Cobalt Racing Blue `#3B82F6`, Crimson Sport `#EF4444`, Emerald `#10B981`, Platinum White `#E2E8F0`).
* **Core Philosophy:** High performance, zero bloat, instant boot without double-loading or hijacking the screen, rock-solid automotive Bluetooth detection, and seamless handoff to Apple CarPlay / Android Auto dongle apps and offline navigation.

---

## 2. Technical Stack & Build Environment

| Component | Specification | Notes |
| :--- | :--- | :--- |
| **Language** | Java 8 / Java 17 | Clean, low-overhead native Android code |
| **Target OS** | Android (API 21 to 33) | `minSdk 21`, `targetSdk 28`, `compileSdk 33` |
| **UI Framework** | Native Android XML Views (`findViewById`) | AppCompat only; ViewBinding is not used |
| **Build System** | Gradle 8.2 & AGP 7.4.2 | Single unified flavor: `ApexDrive-APK` |
| **CI / CD Pipeline** | GitHub Actions (`.github/workflows/build.yml`) | Builds on every push and pull request; the wrapper pins Gradle 8.2 |
| **Repository** | `https://github.com/yvs1n/ApexDrive` | Public GitHub repo |

---

## 3. Architecture & File Structure

```
ApexDrive/
├── .github/workflows/
│   └── build.yml               # Automated CI build producing ApexDrive-APK artifact
├── app/
│   ├── build.gradle            # App-level build configs (minSdk 21, compileSdk 33)
│   └── src/test/               # JVM unit tests (run with ./gradlew testDebugUnitTest)
│   └── src/main/
│       ├── AndroidManifest.xml # Single launcher activity (android.intent.category.HOME)
│       ├── java/com/apexdrive/launcher/
│       │   ├── MainActivity.java           # Central controller: nav rail, telemetry, UI switching
│       │   ├── adapters/
│       │   │   └── AppsGridAdapter.java    # App drawer launcher grid adapter
│       │   ├── models/
│       │   │   └── AppInfo.java            # App metadata model (label, package, icon)
│       │   ├── services/
│       │   │   ├── BluetoothWatcher.java    # Automotive multi-channel Bluetooth engine
│       │   │   ├── BootReceiver.java        # Passive boot listener (no UI hijacking)
│       │   │   ├── CarThermometerWatcher.java # Ambient temperature reader
│       │   │   ├── GpsTelemetryManager.java # GPS speed, heading, altitude, trip odometer
│       │   │   └── MediaSyncService.java    # AVRCP / automotive media state tracking
│       │   └── utils/
│       │       └── PreferenceHelper.java    # SharedPreferences persistence (driver device, theme)
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml            # Shell container with side navigation rail
│           │   ├── view_dashboard.xml           # Gauges, speed, clock, Bluetooth status card
│           │   ├── view_carplay.xml             # CarPlay / dongle launch screen
│           │   ├── view_maps.xml                # Offline navigation view (OsmAnd integration)
│           │   ├── view_audio.xml               # Media controller / radio tuner
│           │   ├── view_apps.xml                # App drawer grid
│           │   ├── view_setup.xml               # Theme accents, driver device selector
│           │   ├── dialog_device_selector.xml / dialog_task_switcher.xml
│           │   ├── item_app_tile.xml / item_task_card.xml
│           │   └── item_device_card.xml         # Bluetooth device card with LAUNCH / CONNECT pill
│           └── values/
│               ├── colors.xml                   # Cockpit surfaces, status colors, gauge accents
│               └── strings.xml                  # Localized string keys
```

---

## 4. Hardware Quirks & Automotive Constraints (CRITICAL)

When working on this codebase, **do not assume standard Android phone behavior**. Automotive head units have specific hardware traits:

### A. Proprietary UART Bluetooth
* **The Quirk:** Most Chinese automotive head units (Allwinner T3, TS9, FYT, Topway, Microntek) do **not** route Bluetooth through the Android Linux HAL. Instead, Bluetooth is handled by a separate MCU hardware chip communicating over internal serial UART.
* **The Pitfall:** Standard Android API calls like `BluetoothDevice.isConnected()` throw `NoSuchMethodException` or return `false` even while audio is actively streaming. Furthermore, when a phone is manually connected, the system rarely fires `BluetoothDevice.ACTION_ACL_CONNECTED`.
* **The Solution ([`BluetoothWatcher.java`](app/src/main/java/com/apexdrive/launcher/services/BluetoothWatcher.java)):**
  1. Persistent `BluetoothA2dp` and `BluetoothHeadset` profile proxies via `getProfileProxy()`.
  2. Active audio stream verification: `AudioManager.isBluetoothA2dpOn()` and `isBluetoothScoOn()`.
  3. Head unit system property checks via reflection: `sys.bt.connected`, `persist.sys.bt.connected`, `ts.bt.state`, `goc.bt.connected`, `bt.connected.name`, `bt.connected.mac`.
  4. Database checks via `Settings.System`: `bt_device_name`, `bt_connected_dev_name`, `current_connect_bt_addr`.
  5. **Bypass Option:** In the device selector dialog, if an automated handshake times out, the card switches to `"CONNECTED IN CAR? TAP TO LAUNCH ANYWAY"` with a bright **`LAUNCH ANYWAY`** button so the driver is never locked out of CarPlay.

### B. Prohibited Hidden APIs (`@hide` / `@SystemApi`)
* **Rule:** Standard Android SDK compilation against `platforms;android-33` will fail with `cannot find symbol` if you use:
  * `bluetoothAdapter.getConnectionState()` → **Must call via reflection.**
  * `BluetoothAdapter.STATE_CONNECTED` → **Use `BluetoothProfile.STATE_CONNECTED` (int 2).**
  * `BluetoothAdapter.STATE_DISCONNECTED` → **Use `BluetoothProfile.STATE_DISCONNECTED` (int 0).**
* Always annotate Bluetooth classes and card rendering methods with `@SuppressLint("MissingPermission")` because runtime permissions on car units are managed system-level or pre-granted.

### C. Boot Receiver & Launch Hijacking
* **Rule:** [`BootReceiver.java`](app/src/main/java/com/apexdrive/launcher/services/BootReceiver.java) must **never** call `context.startActivity()` on `ACTION_BOOT_COMPLETED`.
* Android's window manager already boots the app via the `android.intent.category.HOME` intent filter in `AndroidManifest.xml`. Calling `startActivity` in the receiver triggers a jarring double-launch reload.

### D. Apple CarPlay / Android Auto Integration
* The launcher does not implement the CarPlay protocol directly; it interfaces with companion USB/Wireless dongles and their apps.
* Target packages launched by [`MainActivity.java`](app/src/main/java/com/apexdrive/launcher/MainActivity.java):
  * **AutoKit / Box:** `cn.manstep.phonemirrorbox`
  * **ZLink:** `com.zjinnova.zlink`
  * **CarLink / SpeedPlay:** `com.android.ecar`, `com.ts.bt`

### E. Offline Maps
* Default offline navigation is integrated with **OsmAnd** (`net.osmand.plus` / `net.osmand`), with fallbacks to Organic Maps, Maps.me, Here WeGo, or 2GIS.

---

## 5. Development Workflows

### How to Build via CI/CD (Recommended & Zero Setup)
1. Commit and push your code to `main`:
   ```bash
   git add -A
   git commit -m "feat/fix: your commit message"
   git push origin main
   ```
2. Navigate to [GitHub Actions on yvs1n/ApexDrive](https://github.com/yvs1n/ApexDrive/actions).
3. The `Build ApexDrive APK` workflow will finish in ~1.5 minutes.
4. Download the **`ApexDrive-APK`** artifact, unzip it, and copy `app-debug.apk` to a USB drive to test on the car.

### How to Build Locally
If you have Android Studio or Gradle installed locally:
```bash
# Clean and assemble debug APK
./gradlew assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 6. Current Key Files & Navigation Map

| File | Purpose |
| :--- | :--- |
| [`MainActivity.java`](app/src/main/java/com/apexdrive/launcher/MainActivity.java) | Orchestrates navigation rail switching, telemetry cards, driver selection dialog, and CarPlay launch gating. |
| [`BluetoothWatcher.java`](app/src/main/java/com/apexdrive/launcher/services/BluetoothWatcher.java) | Multi-channel automotive Bluetooth link detector and head unit broadcast dispatcher. |
| [`GpsTelemetryManager.java`](app/src/main/java/com/apexdrive/launcher/services/GpsTelemetryManager.java) | Manages GPS location updates, real-time speed smoothing, trip distance, and heading calculation. |
| [`PreferenceHelper.java`](app/src/main/java/com/apexdrive/launcher/utils/PreferenceHelper.java) | Persists user settings (selected driver device MAC, theme accent color, map choice, speed units). |
| [`build.yml`](.github/workflows/build.yml) | GitHub Actions workflow ensuring reliable cloud APK compilation with build failure log uploads. |

---

## 7. Guidelines for AI Agents Making Future Changes

1. **Keep it a Single Unified APK:** Avoid splitting the app into product flavors unless specifically requested. Everything runs inside `ApexDrive-APK`.
2. **Preserve Automotive Ergonomics:** Large touch targets (minimum 48–64dp), high contrast typography (`sans-serif-black` for headers), zero tiny buttons that cannot be tapped while driving.
3. **Never Block CarPlay Launch:** If Bluetooth detection encounters an unexpected car ROM, always ensure the manual fallback ("LAUNCH ANYWAY") allows the user to proceed.
4. **Test Build Verification:** After pushing changes, monitor the GitHub Actions CI run using the GitHub API or web browser to confirm `Assemble Debug APK` succeeds.
