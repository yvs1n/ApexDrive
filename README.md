# ApexDrive — Minimalist Car Launcher
*Custom Cockpit Interface for 2015 Hyundai Santa Fe & Allwinner T3L Head Units*

---

## 1. Project Overview

ApexDrive is a lightweight, zero-lag Android automotive launcher engineered specifically for aftermarket **Allwinner T3L** head units (ARMv7-a quad-core, 2GB RAM).

### Key Features:
* **True Android Home Activity:** Uses `<category android:name="android.intent.category.HOME" />` so it resumes instantly upon car ignition.
* **1-Touch Apple CarPlay (Configurable Launcher):** Direct intent launcher with live connection status. You can switch between **Zlink 5.3**, **AutoKit / Carlinkit**, **TLink**, **SpeedPlay**, or any installed app directly inside the **Setup** menu!
* **Real GPS Speedometer & Telemetry:** Uses native Android `LocationManager.GPS_PROVIDER` (not heavy widget daemons). Shows live km/h, 8-point compass heading, and trip distance.
* **Live Media Controller:** Hooks into Android `MediaSessionManager` to display real song titles, artists, and playback controls for Spotify, Apple Music, YouTube Music, Radio, or CarPlay audio.
* **Bluetooth Connection Strip:** Compact, low-profile status card for your head unit's `SantafemR` Bluetooth connection.
* **Driver Ergonomics:** Left-hand drive navigation rail with touch targets $\ge 52\,\text{dp}$, glanceable typography, and customizable accent colors.
* **Low Memory Footprint:** Built with lightweight native Android views (<35 MB RAM footprint).

---

## 2. How to Build the APK

### Option A: Via Android Studio (Recommended)
1. Open **Android Studio**.
2. Select **Open** and choose this folder: `c:\Users\yassi\Documents\Android Launcher`.
3. Wait for Gradle sync to complete.
4. Go to **Build** $\rightarrow$ **Build Bundle(s) / APK(s)** $\rightarrow$ **Build APK(s)**.
5. Once compiled, Android Studio will show a popup with a link to `app-debug.apk` (located in `app/build/outputs/apk/debug/`).

### Option B: Via Command Line (Gradle)
Run in PowerShell:
```powershell
./gradlew assembleDebug
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
