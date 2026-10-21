# ApexDrive — Minimalist Car Launcher
*Custom Cockpit Interface for 2015 Hyundai Santa Fe & Allwinner T3L Head Units*

---

## 1. Project Overview

ApexDrive is a lightweight, zero-lag Android automotive launcher engineered specifically for aftermarket **Allwinner T3L** head units (ARMv7-a quad-core, 2GB RAM).

### Key Features:
* **True Android Home Activity:** Uses `<category android:name="android.intent.category.HOME" />` so it resumes instantly upon car ignition.
* **1-Touch Apple CarPlay (Configurable Launcher):** Direct intent launcher with live connection status. You can switch between **Zlink 5.3**, **AutoKit / Carlinkit**, **TLink**, **SpeedPlay**, or any installed app directly inside the **Setup** menu!
* **Real GPS Speedometer & Telemetry:** Uses native Android `LocationManager.GPS_PROVIDER` (not heavy widget daemons). Shows live km/h, 16-point compass heading, and trip distance.
* **Live Media Controller:** Hooks into Android `MediaSessionManager` to display real song titles, artists, and playback controls for Spotify, Apple Music, YouTube Music, Radio, or CarPlay audio.
* **Bluetooth Connection Strip:** Compact, low-profile status card for your head unit's `SantafemR` Bluetooth connection.
* **Driver Ergonomics:** Left-hand drive navigation rail with touch targets $\ge 52\,\text{dp}$, glanceable typography, and customizable accent colors.
* **Low Memory Footprint:** Built with lightweight native Android views (no unnecessary libraries; memory use has not been measured yet).

---

## 2. How to Build the APK

### Option A: Via Android Studio (Recommended)
1. Open **Android Studio**.
2. Select **Open** and choose the cloned repository folder.
3. Wait for Gradle sync to complete.
4. Go to **Build** $\rightarrow$ **Build Bundle(s) / APK(s)** $\rightarrow$ **Build APK(s)**.
5. Once compiled, Android Studio will show a popup with a link to `app-debug.apk` (located in `app/build/outputs/apk/debug/`).

### Option B: Via Command Line (Gradle)
Needs JDK 17 and the Android SDK. Run in a shell:
```bash
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```
The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 3. How to Install on Your Hyundai Santa Fe

1. **Copy to USB Thumb Drive:**
   * Rename `app-debug.apk` to `ApexDrive.apk` for simplicity.
   * Copy it to a FAT32-formatted USB flash drive.
2. **Plug into the Car:**
   * Insert the USB drive into your Santa Fe's center console or glove box USB port.
3. **Install via Head Unit File Manager:**
   * Open the head unit's **File Manager** (or **ApkInstaller**).
   * Tap on **USB Storage**, select `ApexDrive.apk`, and tap **Install**.
4. **Set as Default Launcher:**
   * Press the capacitive **Home** icon on the left bezel of your head unit.
   * When Android prompts *"Select a Home app"*, choose **ApexDrive** and select **Always**.

---

## 4. First-Time Setup Permissions

* **Location Permission (Speedometer):**  
  When prompted on first run, select **Allow all the time** so the GPS speedometer functions properly.
* **Music Sync (Notification Access):**  
  To allow the dashboard to display live Spotify / Apple Music track titles and album art:  
  Open **Head Unit Settings** $\rightarrow$ **Special App Access** $\rightarrow$ **Notification Access** $\rightarrow$ Toggle **ApexDrive** to **ON**.

---

## 5. Steering Wheel Controls Note (Simple Soft XP)

Your Santa Fe's head unit is configured with:
* **CAN Decoder:** `03: Simple Soft (XP)`
* **Vehicle Profile:** `61: Hyundai Kia / 01: 13 All new Santafe`
* **Factory Settings Code:** `123456` (or `7890`)

The built-in Android "SWC Study" app will not detect buttons because your buttons speak digital CAN packets to the MCU. To verify your steering buttons:
1. Open **Settings** $\rightarrow$ **Car Settings** $\rightarrow$ **Factory Settings** (`123456`).
2. Verify **Canbus** is set to `Simple Soft (XP) -> Hyundai Kia -> 13 All new Santafe`.
3. Check the CAN RX packet monitor when pressing the steering wheel Volume button.

---

## 6. Why targetSdk 28

`compileSdk` is 33 but `targetSdk` is deliberately **28**. On a head unit this keeps the legacy
permission and background rules the app relies on, and the vendor ROMs (Allwinner T3L and similar,
mostly Android 8 to 10) behave as the app expects. Raising it is a project of its own. Before you bump
`targetSdk`, plan for each of these:

| targetSdk | What changes | Impact on ApexDrive |
| :--- | :--- | :--- |
| 29 | Background location needs `ACCESS_BACKGROUND_LOCATION` as a separate runtime grant | GPS speedometer and trip data stop when the launcher is not in front |
| 30 | Package visibility filtering: `getInstalledApplications`, `queryIntentActivities` and `getLaunchIntentForPackage` only see packages declared in `<queries>` | App drawer, CarPlay dongle detection and map pickers come back empty unless `<queries>` is added (or `QUERY_ALL_PACKAGES`, which Play restricts) |
| 31 | `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` runtime permissions replace `BLUETOOTH`; every component with an intent filter needs an explicit `android:exported` | Bluetooth detection fails without the grant; the manifest does not build until `exported` is set on the launcher activity and receivers |
| 33 | `POST_NOTIFICATIONS` runtime permission | Any notification the app posts is dropped until granted |

Also re-check `MediaSessionManager` notification-listener access and reflection on `@hide`
Bluetooth methods, which later API levels restrict.

---

## 7. Signing and releases

Debug builds are signed with the debug key of the machine that built them. A fresh CI runner
generates a new debug key on every run, so installing one CI build over another fails with
"App not installed" (signature mismatch) and you would lose settings by uninstalling first.

To get builds that install over each other, CI signs with one stable key when these repository
**secrets** exist (nothing is ever committed):

| Secret | Value |
| :--- | :--- |
| `SIGNING_KEYSTORE_BASE64` | the keystore file, base64-encoded (`base64 -w0 release.jks`) |
| `SIGNING_KEYSTORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

Create the key once and keep it safe; if it is lost, existing installs cannot be updated:

```bash
keytool -genkeypair -v -keystore release.jks -alias apexdrive -keyalg RSA -keysize 2048 -validity 10000
```

Without the secrets (forks, pull requests, local builds) the build falls back to the normal debug key.
Locally you can sign the same way by exporting `SIGNING_KEYSTORE_FILE`, `SIGNING_KEYSTORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.

`versionCode` comes from the `VERSION_CODE` environment variable (CI sets it to the run number);
local builds use 1. A signed release APK is built with `./gradlew assembleRelease` when the signing
variables are present.
