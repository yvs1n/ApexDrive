# Audit 1: Production Readiness

| | |
|---|---|
| Repo | `yvs1n/ApexDrive` @ `6b31f21` (main) |
| Date | 2026-10-21 |
| Scope | Bugs, fake status labels, security and privacy, performance, lifecycle, build and release, and every hardcoded vehicle or head-unit assumption |
| Method | Static read of every Java, XML and Gradle file in the tree, with every `file:line` below re-checked against `6b31f21`. Nothing was compiled or run: there is no Android SDK, emulator or head unit in this environment |
| Out of scope | UI/UX (Audit 2) and the feature roadmap (Audit 3). Where a finding touches them it points to the other audit instead of designing the fix here |

## How to read this document

Each finding is one block:

> **P-xx · Severity · `path:line`**
> **What happens:** observable behaviour. **Why it matters:** impact on the driver or the project. **Fix:** smallest change that works.

**Severity**

| Level | Meaning |
|---|---|
| Critical | Can leave the head unit unusable or leak data outside the car with no way to stop it |
| High | Wrong or unsafe behaviour on the main flows, or blocks open-source contribution or release |
| Med | Wrong behaviour on a secondary flow, or real cost in battery, CPU, memory or maintenance |
| Low | Polish, hygiene, future risk |

**Tags.** `[verify in car]` marks anything whose real behaviour depends on the head unit (Bluetooth stack, radio tuner, CAN bus, vendor broadcasts, vendor Settings keys, `Runtime.exec` permissions). I could only read the code, so those findings state what the code assumes and what to observe, not what the unit does. `[inferred]` marks a conclusion drawn from naming or history rather than from code.

**Fix constraints.** Every fix below works on API 21 to 28 with the current dependencies (AppCompat 1.4.2, Material 1.6.1, ConstraintLayout 2.1.4, CardView 1.0.0, which pulls in `androidx.core` 1.7) unless the fix says it needs a newer dependency.

**What is already fine.** No analytics, ad or crash-reporting SDK, no WebView, no `exec` with user-controlled input, no secrets in the tree, `MediaSyncService` is correctly protected by `BIND_NOTIFICATION_LISTENER_SERVICE`, `BootReceiver` correctly does not call `startActivity`, and `onBackPressed` correctly never leaves the launcher.

<!-- SUMMARY -->

---

## A. Functional bugs

### CarPlay launch and "connected" state

**P-01 · High · `MainActivity.java:2308-2317`**
- **What happens:** `launchCarPlay()` reads the user's chosen package (`:2304`) but fires `zjinnova.android.intent.action.ZLINK_MAIN` first (`:2308-2313`) and returns on success. The chosen app (`:2317`) is only used when Zlink is not installed.
- **Why it matters:** On a unit that has Zlink installed, choosing DiPlay, AutoKit or SpeedPlay in Setup (`showCarPlayPicker`, `:1115`) has no effect on the launch path. The Setup button label says one app and the launcher starts another.
- **Fix:** Resolve in this order: the saved package, then (only if the saved package is Zlink) the `ZLINK_MAIN` intent, then the fallback list. Extract a `CarPlayLauncher` class so this is unit-testable.

**P-02 · Med · `MainActivity.java:2331-2335`, `PreferenceHelper.java:26-27`**
- **What happens:** When the fallback loop launches any package from `commonCarPlayPackages`, the code saves that package but always saves the name `"TLink 5.3"`. The preference default is `"Zlink 5.3"`, so the same app has two names depending on the path. `tvCarPlayProtocol` and the Setup button are not refreshed on this path.
- **Why it matters:** Launching DiPlay through the fallback relabels it "TLink 5.3" for the rest of the install.
- **Fix:** Take the label from `PackageManager.getApplicationLabel()` and call the existing `saveCarPlayApp()` (`:1152`) so the UI refreshes too.

**P-03 · High · `MainActivity.java:2299`, `:2262-2267`, `:1612`**
- **What happens:** `isCarPlayConnected` is set to `true` unconditionally at the top of `launchCarPlay()`, and again by the media receiver whenever any track is "playing" with a title that does not start with `"FM "` (`:2262`). The only thing that resets it is a Bluetooth callback (`:1612`). Tapping the Bluetooth pill or card launches CarPlay whenever this flag or any Bluetooth guess (P-04) is true (`:251-253`, `:546-548`).
- **Why it matters:** Playing Spotify from the head unit's own USB player marks "CarPlay connected" and shows the saved driver's name with "WIRELESS PROJECTION ACTIVE" (`:2266`). A single tap on the pill, even to a launch that fails, makes the header read as connected.
- **Fix:** Keep two separate pieces of state: `phoneLinkState` (from `BluetoothWatcher` only) and `projectionAppLaunchedAt` (timestamp, for the "LAUNCHING..." text only). Do not infer connection from audio metadata.

### Bluetooth detection (`BluetoothWatcher`) `[verify in car]`

**P-04 · High · `BluetoothWatcher.java:369-372`, `:388-417`, `:362-368`, `:710-714`, `:687-723`**
- **What happens:** Three independent guesses can report "connected" with no evidence about the specific phone:
  1. `isDeviceCurrentlyConnected()` returns `true` for **any** device when `AudioManager.isBluetoothA2dpOn()` is true (`:369-372`), so with two paired phones both show CONNECTED.
  2. `checkVendorBtConnected()` returns `true` if the `bluetooth_state` Settings value is 1 or 2 (`:403-404`), or if `bt_device_name` is merely non-empty (`:406-407`). `[inferred]` On many units `bt_device_name` is the **head unit's own** Bluetooth name (the string phones pair to; the app itself says "Pair via SantafemR (PIN 0000)", `:731`), which would make this permanently true.
  3. When exactly one device is bonded and any adapter-level signal is true, that device is declared connected (`:364`, `:710-714`). With none of the above, a vendor flag alone yields the generic `"Phone Connected"` (`:722`).
- **Why it matters:** The dashboard, the header pill and the `LAUNCH` pill (`MainActivity.java:2062`) show a connected phone that may be absent, and CarPlay launch is gated on this state.
- **Fix:** Trust only per-device evidence: profile proxy `getConnectedDevices()` (`:288`, `:303`), `ACL_CONNECTED/DISCONNECTED` and profile state broadcasts. Treat vendor Settings and properties as hints that must match a bonded device's MAC or name before they count. Add a hidden diagnostics line that records which signal fired, so a bug report from a car says why. Verify in car: log every signal while the phone is off, paired but not connected, and connected.

**P-05 · Med · `BluetoothWatcher.java:190-195`, `:161`, `:91-124`**
- **What happens:** `initProfileProxies()` returns immediately if Bluetooth is off at start (`:191`) and is never called again. `ACTION_STATE_CHANGED` is in the receiver's filter (`:161`) but matches neither the connected nor the disconnected condition (`:100-124`), so it is ignored.
- **Why it matters:** The code's own comments describe the cold-boot case where the stack is not ready (`MainActivity.java:1989`). In that case the A2DP and Headset proxies stay `null` for the whole session and detection falls back to the guesses in P-04.
- **Fix:** On `STATE_ON`, call `initProfileProxies()`; on `STATE_OFF`, drop the proxies.

**P-06 · Med · `MainActivity.java:1930-1959`, call sites `:255 :259 :550 :554 :862 :983 :1020`; `dialog_device_selector.xml:31`**
- **What happens:** `showDeviceSelectorDialog(boolean isAutoDismiss)` is only ever called with `false`. The `CountDownTimer` branch (`:1931-1959`) that would auto-connect the saved driver phone after 8 seconds, and the `"Auto in 8s"` label, are dead code.
- **Why it matters:** The handoff doc promises a "driver phone" flow, but at ignition nothing reconnects the saved phone. `getSelectedDriverDevice()` is only used to label cards (`:1644`, `:2048`, `:2264`, `:2300`) and by this dead branch (`:1946`).
- **Fix:** Decide the product behaviour. Either call the auto path from `onCreate` when a saved driver exists and is not connected (with a visible cancel), or delete the branch and the label. Audit 3 owns the product decision.

**P-07 · Med · `MainActivity.java:1904-1909`, `BluetoothWatcher.java:482`**
- **What happens:** Opening the "Select driver phone" dialog silently calls `BluetoothAdapter.enable()` if Bluetooth is off. `signalConnect()` calls `createBond()` on any unbonded target (`:481-483`), and the auto-connect path passes a saved MAC to it (`:1951-1953`).
- **Why it matters:** Opening a picker changes system Bluetooth state, and a stale saved MAC can start a pairing request on the phone.
- **Fix:** Show a prompt with an Enable button (`BluetoothAdapter.ACTION_REQUEST_ENABLE`, works on API 21+), and never `createBond()` from a saved MAC.

**P-08 · Low · `BluetoothWatcher.java:479` and `:572`, `:586-616`**
- **What happens:** A single connect attempt calls `broadcastHeadUnitConnect()` twice (once from `signalConnect()` at `:479`, once at `:572`): 22 broadcasts. A temporary A2DP proxy is created per attempt when the persistent one is null (`:548-559`) and never closed.
- **Why it matters:** Duplicate vendor commands, and a Binder proxy leak per retry.
- **Fix:** Remove one call; call `closeProfileProxy` after use. The broadcast list itself is addressed by P-76.

### Media control and metadata (`MediaSyncService`)

**P-09 · High · `MediaSyncService.java:223-276`, callers `MainActivity.java:568-603`** `[verify in car]`
- **What happens:** Every key press (prev, next, play, pause) is delivered five ways: `AudioManager.dispatchMediaKeyEvent` down and up (`:230-231`); two `ACTION_MEDIA_BUTTON` broadcasts (`:237-243`); three string-command broadcasts to Zlink, DiPlay and GOC (`:256-258`); four vendor key broadcasts (`:264-267`); and `Runtime.exec("input keyevent N")` (`:271-275`).
- **Why it matters:** A player that listens to a media session and to the `MEDIA_BUTTON` broadcast can receive NEXT twice and skip two tracks. The `input` binary is a separate Java VM start (hundreds of ms on this SoC) and cannot inject events from an app uid anyway. The event times use `System.currentTimeMillis()` (`:225`) but `KeyEvent` expects `SystemClock.uptimeMillis()`, so some receivers may treat events as stale.
- **Fix:** One path. Use `MediaController.getTransportControls()` when a session exists; otherwise one `dispatchMediaKeyEvent` down and up pair using `uptimeMillis()`. Anything vendor-specific moves into the profile (P-76).

**P-10 · High · `MediaSyncService.java:196-202`** `[verify in car]`
- **What happens:** Starting playback calls `controller.play()`, then `sendMediaKey(KEYCODE_MEDIA_PLAY)` (126), then `sendMediaKey(KEYCODE_MEDIA_PLAY_PAUSE)` (85), plus the vendor and string broadcasts.
- **Why it matters:** PLAY_PAUSE is a toggle. A player that honours PLAY and then PLAY_PAUSE ends up paused again. `sIsPlaying` is flipped optimistically (`:191`, `:218-220`), so the button text shows PAUSE while audio may be stopped.
- **Fix:** Send one command only. Derive the button text from `PlaybackState`, not from the tap.

**P-11 · Med · `MediaSyncService.java:55-71`**
- **What happens:** `updateActiveController()` registers a new `MediaController.Callback` on every session-list change and never unregisters the old one (`:58`). It always uses `controllers.get(0)` rather than the controller that is `STATE_PLAYING`, and stores it in a `static` field (`:26`) that is never cleared.
- **Why it matters:** After a few session changes each metadata event fires several broadcasts. Controls can target a session that is not the one playing.
- **Fix:** Keep one callback instance, unregister it from the previous controller before switching, pick the playing session, and clear the static in `onDestroy` / `onListenerDisconnected`.

**P-12 · Med · `MediaSyncService.java:128-151`, `:61-66`, `:99`**
- **What happens:** `broadcastMetadata()` starts from the placeholders `"Track / Radio"` and `"Tap Play to resume Spotify, Radio or CarPlay"` (`:129-130`). A playback-state callback while `sActiveController` is null (`:66`) passes `null` metadata and overwrites a real title with the placeholder. The vendor-broadcast path invents the artist `"Bluetooth / CarPlay Audio"` (`:99`).
- **Why it matters:** Now-playing text flickers back to a placeholder, and invented artist text looks like data.
- **Fix:** Send only fields that changed, and show an empty state in the UI instead of fake text.

**P-13 · Med · `MediaSyncService.java:134-135`, `README.md:61-62`**
- **What happens:** Only `METADATA_KEY_TITLE` and `METADATA_KEY_ARTIST` are read. Album art (`METADATA_KEY_ALBUM_ART`, `METADATA_KEY_ART`, `METADATA_KEY_ART_URI`) is available from the same `MediaMetadata` but unused, although the README promises "album art".
- **Why it matters:** Feature gap versus the docs, and a visible one on a 1024x600 dash.
- **Fix:** Art cannot travel in the broadcast: a `Bitmap` extra would hit the roughly 1 MB Binder transaction limit and `TransactionTooLargeException`. `NotificationListenerService` runs in the app's own process, so replace the broadcast with an in-process observer (a singleton with a listener list) and downscale to 256 px before handing it over. This also removes the leak in P-40.

**P-14 · Med · `MainActivity.java:2294`, `MediaSyncService.java:50-52`, `README.md:60-62`**
- **What happens:** `startService()` on a `NotificationListenerService` starts the object but does not grant notification access. Without that grant `getActiveSessions()` throws `SecurityException`, which is swallowed (`:50`). Nothing in the UI shows or requests the grant; only the README mentions it.
- **Why it matters:** The "live media" feature is silently off on a fresh install and the user has no hint why.
- **Fix:** Add a Setup row showing grant state (`NotificationManagerCompat.getEnabledListenerPackages(ctx)`, `androidx.core`, already on the classpath) with a button that opens `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` (the constant exists from API 22; use the string literal on API 21). `[verify in car]` many units remove that settings page; the row should then explain the `adb` alternative.

### Task switcher

**P-15 · High · `MainActivity.java:296-308`, `:372-377`, `:418-423`, `:383`, `:430`**
- **What happens:**
  - `getRunningAppProcesses()` returns only the caller's own processes for non-system apps from API 22, so the "RUNNING IN BACKGROUND" list is mostly empty or only the stored recents.
  - `Runtime.exec("am force-stop " + pkg)` cannot work from an app uid (it needs `FORCE_STOP_PACKAGES`, a signature permission) and the process result is never read.
  - `killBackgroundProcesses()` only kills cached, non-foreground processes.
  - The toasts still say "Memory released" and "Head unit RAM freed".
  - `CLOSE ALL` kills every package in the list (`:416-423`) with no exclusion for the current CarPlay bridge, media player or radio app.
- **Why it matters:** A false claim on screen, and `CLOSE ALL` can drop an active projection session or audio when it does run. `[verify in car]` the platform may be rooted or signed with the system key, which would change the first two points; the code should not depend on that.
- **Fix:** Drop the `exec` calls and the "RAM freed" wording. Show only what is knowable (recents the launcher itself started). Exclude the saved CarPlay, radio and map packages from `CLOSE ALL`. See P-35 for the labels.

**P-16 · Med · `MainActivity.java:470-475`, `MediaSyncService.java:271-275`**
- **What happens:** `triggerSystemRecents()` runs `Runtime.exec("input keyevent 187")` and `return`s. `exec` throws only when the binary cannot be spawned, so the code on `:477-507` (status-bar service reflection and six vendor broadcasts) is unreachable even when the key event is rejected.
- **Why it matters:** The "SYSTEM UI" button can silently do nothing, and every press spawns a VM.
- **Fix:** Remove `exec`. Try the vendor broadcasts from the profile (P-76) first, or use an `AccessibilityService` with `GLOBAL_ACTION_RECENTS` (API 21+) behind an explicit user opt-in. Otherwise remove the button.

### Clock, GPS and units

**P-17 · Med · `MainActivity.java:1529-1536`**
- **What happens:** The clock uses the fixed pattern `"h:mm"` (12-hour, no AM/PM) with a `SimpleDateFormat` built once. Time-zone changes are not reflected, and the user's 24-hour setting is ignored.
- **Why it matters:** Wrong in most of the world; `12:45` could mean midnight or noon. A trip across zones shows the old zone until restart.
- **Fix:** `android.text.format.DateFormat.getTimeFormat(context)` re-created on `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED`, refreshed on `ACTION_TIME_TICK` (see P-47).

**P-18 · Low · `MainActivity.java:1565`**
- **What happens:** Coordinates are printed as `"%.4f° N, %.4f° E"` for any sign, so a position in the southern or western hemisphere reads, for example, `-33.8688° N`.
- **Why it matters:** Wrong data on screen for roughly half the planet.
- **Fix:** Use sign to choose N/S and E/W and print the absolute value.

**P-19 · Med · `GpsTelemetryManager.java:62-78`, `:108-115`, `:147-153`**
- **What happens:** GPS and network fixes feed the same handler. Network fixes have no speed, so speed is derived from the distance to `lastLocation`, which may be a GPS fix (`:109-115`). The trip odometer also adds distance between fixes from different providers (`:147-153`). Integer-second `timeDeltaSec` (`:111`) makes small deltas coarse.
- **Why it matters:** Mixed-provider hops of tens of metres add phantom distance and produce speed spikes. A network fix can also overwrite a better GPS `lastLocation`.
- **Fix:** Use `GPS_PROVIDER` only for speed, heading and trip. Use network or passive fixes only for the coarse weather location (see P-38). Reject fixes with `accuracy` worse than a threshold or older than a few seconds.

**P-20 · Med · `GpsTelemetryManager.java:25-27`, `:149`, `:155`; `MainActivity.java:1021`, `:1540`**
- **What happens:** The trip total and start time live only in `GpsTelemetryManager` fields. A gap of more than 300 m between consecutive fixes is dropped (`:149`), so a tunnel or a dropout loses that distance. `elapsedMinutes` is wall-clock since the manager started or was reset, not driving time. Process death, or the "RESTART APEXDRIVE" button (`recreate()` creates a new manager), zeroes the trip.
- **Why it matters:** On a 2 GB unit the launcher process is a likely victim of the low-memory killer, so the trip meter resets without warning.
- **Fix:** Persist `totalDistanceMeters` and `tripStartTime` in `PreferenceHelper` every few seconds and on pause, restore on start. Count gaps with `Location.distanceTo` when the time delta is consistent with a plausible speed.

**P-21 · Med · `MainActivity.java:1033-1034`, `CarThermometerWatcher.java:40`, `:59`**
- **What happens:** `toggleSpeedUnit()` also switches temperature to Fahrenheit (the thermometer field is literally named `isMph`). Km/h with °F or mph with °C is impossible.
- **Why it matters:** Common real-world combination (UK: mph and °C) cannot be set.
- **Fix:** Separate preference and Setup row for temperature unit.

### Thermometer (`CarThermometerWatcher`) `[verify in car]`

**P-22 · Med · `CarThermometerWatcher.java:245-247`, `:313`, `:383`, `:420`, `:95-98`; `MainActivity.java:1835-1838`**
- **What happens:**
  - The calibration offset is applied only to the weather result (`:246-247`). CAN broadcast (`:313`), Settings/property (`:383`) and built-in sensor (`:420`) readings ignore it.
  - The calibration dialog computes `offset = target - getRawTempCelsius()`, but `rawTempC` is only updated by the weather path (`:245`). If the only source is CAN or sensor, `raw` is the stale default 32.0 and the offset is wrong.
  - Every source overwrites the others with no priority, so a 10-minute weather refresh replaces a CAN reading (`:245-248`).
  - `clearManualCalibration()` resets the offset but does not recompute `currentTempC`.
- **Why it matters:** The value the driver calibrated against the instrument cluster is lost whenever a different source reports.
- **Fix:** One `TempSource` priority (CAN, then sensor, then weather), keep the raw value per source, apply the offset once in `notifyTemp`, and compute the dialog offset from the currently displayed source.

**P-23 · Med · `CarThermometerWatcher.java:302`, `:312`, `:379-382`**
- **What happens:** Generic extra keys `"temp"`, `"temperature"`, `"celsius"` and `"car_temp"` are accepted from any CAN or vendor broadcast (`:302`), so coolant or cabin temperature would be shown as outside temperature. Anything between 55 and 130 from Settings or properties is converted as Fahrenheit (`:380-382`); a genuine 56 °C hot-surface reading becomes 13 °C. The intent path accepts -40 to 65 (`:312`).
- **Why it matters:** A plausible-looking wrong number is worse than "--".
- **Fix:** Units and key names come from the vehicle/head-unit profile (P-76); unknown keys are ignored.

**P-24 · Med · `CarThermometerWatcher.java:215`, `:213-261`**
- **What happens:** `lastOnlineFetchTime` is set when a fetch starts, not when it succeeds (`:215`). A failed fetch (no signal, tunnel) suppresses retries for 10 minutes (`:176`) while the last value stays on screen, with the city name, as if live.
- **Why it matters:** Stale weather looks current.
- **Fix:** Record success time, retry with backoff, and show an age or grey the value after a threshold.

### Apps, pickers and radio

**P-25 · Med · `MainActivity.java:941`, `:959`, `:963-972`**
- **What happens:** The app list loads once in `setupAppsWidgets()` and again only after a failed launch. There is no package-change receiver.
- **Why it matters:** The app itself tells the user to sideload OsmAnd from USB (`:697-701`); after installing it the app does not appear until restart.
- **Fix:** Register for `ACTION_PACKAGE_ADDED/REMOVED/REPLACED` (with `addDataScheme("package")`) and refresh.

**P-26 · Low · `MainActivity.java:1146`, `:1201`, `:1280`; `AppsGridAdapter.java:76-80`, `MainActivity.java:938-939`**
- **What happens:** Pickers store `names.get(which).split(" \\(")[0]` as the label, so an app named `Maps (Go)` is saved as `Maps`. The grid sets a click listener on each tile (`AppsGridAdapter.java:76`) and also an `OnItemClickListener` (`MainActivity.java:939`); the second never fires because the tile consumes the click.
- **Why it matters:** Wrong labels persisted; dead code.
- **Fix:** Keep a parallel list of labels; remove one of the two listeners.

**P-27 · Med · `MainActivity.java:899-905`, `:920-926`, `:1368`, `:1391`, `:888-928`** `[verify in car]`
- **What happens:**
  - Frequency up/down buttons send four tune broadcasts on every press even when the radio is stopped (the `try` at `:899` is outside the `isRadioPlaying` checks).
  - `com.microntek.radio.power` is sent in both the start (`:1368`) and the stop (`:1391`) paths; by its name it is a power toggle, so "pause" could power the tuner on.
  - The frequency is clamped to 87.5 to 108.0 MHz (`:891`, `:912`) in 0.1 steps and nothing is ever read back from the tuner.
- **Why it matters:** The on-screen frequency can differ from the real tuner, and the labels say "SEEK" (`view_audio.xml:159`, `:185`) for what is a manual step. See P-31.
- **Fix:** Gate tune broadcasts behind a playing radio, send only the active profile's actions, and rename the buttons "TUNE -/+" until a real seek exists.

**P-28 · Low · `view_maps.xml:226`, `MainActivity.java:635`**
- **What happens:** The button labelled REST AREA runs `launchPoiSearch("coffee")`. Search terms are English literals (`:633-636`).
- **Why it matters:** Label and action disagree; the query is wrong in non-English map apps.
- **Fix:** Pick one meaning, and pass `geo:` queries through string resources.

---

## B. Fake or never-changing status labels

Several labels look like live status but are static XML text that no Java code ever binds. `grep` for `tvCarPlayReadyBadge` and `tvMapStatusBadge` in `src/main/java` returns nothing. All UI text lives in layouts and Java literals: of the 20 entries in `strings.xml` only `app_name` is referenced anywhere (including `bt_default_device`, `strings.xml:14`).

**P-29 · High · `view_dashboard.xml:76`, `view_carplay.xml:99`, `:144`, `:189`, `view_dashboard.xml:50`**
- **What happens:** Green, always-on text: `●  PHONE LINK ACTIVE` (id `tvCarPlayReadyBadge`, never bound), `PROTOCOL: ZLINK 5.3 ACTIVE`, `PORT STATUS: USB 2.0 READY`, `AUDIO STREAM: I2S HARDWARE SYNC`. The `ZLINK 5.3` badge is overwritten with the saved CarPlay name at runtime (`MainActivity.java:519`), but that name can itself be wrong (P-02).
- **Why it matters:** With no phone, no dongle and no USB device, the screen says the link is active and the port ready. A driver cannot tell a dead link from a live one.
- **Fix:** Bind these to real state or remove them. If projection state is unknowable on a unit, show "Not detected" (grey) instead of green. Wording and colours are Audit 2's call.

**P-30 · Med · `view_maps.xml:281`, `view_setup.xml:221`, `MainActivity.java:1006-1013`, `:660`**
- **What happens:** `● RADARS · ROAD BUMPS · OFFLINE LANES ACTIVE` (id `tvMapStatusBadge`, never bound) is shown whether or not a map app is installed, and describes the external navigation app's features. `LIVE 20Hz` is a button whose handler only calls `gpsManager.start()` and toasts `"GPS Stream refreshed at 20Hz"`; another toast says `"Real-time 20Hz"` (`:660`).
- **Why it matters:** Nothing in the code can promise 20 Hz. The request is `minTime=50 ms` (`GpsTelemetryManager.java:63-68`), which is a hint to the framework; most GNSS receivers report at 1 Hz `[verify in car]`.
- **Fix:** Remove the badge. Replace the button with a read-only measured rate (fixes per second over the last 5 s) or remove it.

**P-31 · Med · `view_audio.xml:95`, `:110`, `:159`, `:185`; `MainActivity.java:782`, `:806-808`, `:795-801`**
- **What happens:** `FM RADIO TUNER (SI473X IC)` is a hardcoded chip name; `PRESET 01` is static text; the on-screen state (`ACTIVE · 87.5 MHZ`, the dashboard title `FM 87.5 MHz`) is set the moment the button is tapped. `isRadioPlaying` (`:112`) is purely local; the tuner never reports back.
- **Why it matters:** The launcher shows "ACTIVE" for a tuner it cannot see. The chip name is true, at best, for the author's unit `[inferred]`. Other units have other tuner ICs. "SEEK" is a manual 0.1 MHz step (P-27).
- **Fix:** Remove the chip name and preset text. Label the state "SENT" or read it back from the radio app if the profile supports it.

**P-32 · Med · `GpsTelemetryManager.java:106`, `:169`, `:143-144`; `MainActivity.java:1574`, `:1604`; `view_maps.xml:47`**
- **What happens:** Every fix, and every `onProviderEnabled`, reports `onGpsStatusChanged(true, 8)`: a hardcoded 8 satellites, even when there is no fix. Missing accuracy defaults to `5.0 m` and missing altitude to `0.0 m` (`:143-144`) and is displayed as real. `● 3D Fix · ±x m` is printed for any location, including a network fix; the layout placeholder reads `±3m` (`view_maps.xml:47`).
- **Why it matters:** Invented numbers shown as measurements. The Activity currently ignores `onGpsStatusChanged` (`:1604`), but the interface contract is false and will mislead the next contributor.
- **Fix:** Use `GnssStatus.Callback` (API 24+) or `GpsStatus.Listener` (API 21 to 23) for the real count, expose "no fix" as a state, and show `--` for missing altitude and accuracy.

**P-33 · Med · `activity_main.xml:113`, `CarThermometerWatcher.java:41-43`, `PreferenceHelper.java:79-81`, `:87-89`, `MainActivity.java:1714`**
- **What happens:** The header shows `26°C · OUTSIDE` in XML, `32°C · OUTSIDE` as the Java fallback and as the persisted default, before any source has reported. Anything parsed from a property or Settings key is labelled `VEHICLE SENSOR` (`:384`) even if it came from an unrelated key.
- **Why it matters:** A first-launch reading of 26 or 32 °C is fabricated, and two different fabricated values exist.
- **Fix:** Start empty (`--°`), and label sources by what was actually read.

**P-34 · Med · `BluetoothWatcher.java:768`, `:722`; `view_dashboard.xml:153`, `:164`, `:179`; `MainActivity.java:1635`, `:2063`**
- **What happens:** The connected callback always sends `"A2DP Audio & Calls Active"` whatever profiles are connected (`:768`). The dashboard card ships with `SANTAFEMR`, `A2DP AUDIO · TELEPHONY LINK` and a green `CONNECTED` badge until the first callback. The device list shows `CONNECTED · READY FOR CARPLAY` (`:2063`) even if no projection app is installed.
- **Why it matters:** Profile detail and readiness are asserted, not detected.
- **Fix:** Read the profiles from the proxies (A2DP, HFP) and print only those; default the card to an empty state.

**P-35 · Med · `MainActivity.java:383`, `:386`, `:404`, `:428-430`; `dialog_task_switcher.xml:39`; `item_task_card.xml:31`**
- **What happens:** Toasts and badges say `"Memory released"`, `"All background tasks closed · Head unit RAM freed"`, `OPTIMIZED`, `RAM OPTIMIZED`. The XML placeholders `3 RUNNING` and `CarPlay` are visible in the layout editor and in the preview.
- **Why it matters:** The claims are untrue (P-15). The previous audit (`anti-slop/audit-002-2026-10-13.md`, R-17 and R-36) marks the process count as "verified" and the kill methods as "accurate"; neither holds.
- **Fix:** Remove the claims with P-15. Retire or relabel the anti-slop audits as historical (P-68).

**P-36 · Med · `dialog_device_selector.xml:41`, `PreferenceHelper.java:67-73`**
- **What happens:** The picker says "Tap your phone to lock Bluetooth & CarPlay. Prevents hijacking other phones in the car." The selection only stores a MAC string. `getSelectedDriverDevice()` is used for labels and the dead auto-connect path (P-06), never to block or ignore other phones.
- **Why it matters:** A security-sounding promise with no enforcement.
- **Fix:** Reword to "Remember this phone", or implement the enforcement and test it. Audit 3 decides.

---

## C. Security and privacy

Threat model: the unit is a shared, always-on Android device with a data connection (phone hotspot or SIM), other sideloaded apps (CarPlay bridge, map, vendor tools), and a public GitHub repository. The realistic risks are privacy leaks to third parties and other apps, and spoofed inputs shown to the driver. There is no authentication surface.

**P-37 · High · `CarThermometerWatcher.java:186`, `AndroidManifest.xml:32`, `MainActivity.java:137-140`, `PreferenceHelper.java:124-126`**
- **What happens:** When there is no last-known location, the launcher calls `http://ip-api.com/json` in cleartext (`:186`) and trusts the returned latitude, longitude and city (`:197-205`). It runs on the first launch, before the user has answered the location prompt: `checkPermissions()` and `initCarThermometer()` run in the same `onCreate`, `getLastKnownLocation` throws `SecurityException`, and the `catch` at `:166` goes to the IP lookup. `usesCleartextTraffic="true"` exists to allow exactly this.
- **Why it matters:** The unit's public IP is sent in the clear to a third party, and a hotspot-side attacker can change the reply to set any location. Android 9+ blocks cleartext by default for `targetSdk` 28, so the manifest flag is the only reason it works.
- **Fix:** Drop the IP lookup (use the saved location or ask the user for a city). Remove `usesCleartextTraffic` and add a `network_security_config` that denies cleartext. If an IP lookup is wanted, use an HTTPS endpoint behind the consent in P-38.

**P-38 · High · `CarThermometerWatcher.java:221-222`, `:265-266`, `:147-149`, `:171-181`, `PreferenceHelper.java:124-130`, `MainActivity.java:1822`**
- **What happens:** Latitude and longitude with 4 decimals (about 11 m) go to `api.open-meteo.com` and `api.bigdatacloud.net` at start-up and then every 10 minutes or 3 km. Auto-weather defaults to `true`. The only code that writes the preference is `setAutoWeatherEnabled(true)` (`MainActivity.java:1822`); nothing can turn it off. There is no consent screen and no disclosure in the README.
- **Why it matters:** A continuous precise-location feed to two third parties, with no off switch. It is also an open-source project that other people will install in their cars.
- **Fix:** First-run opt-in with plain wording (what is sent, to whom). A Setup toggle that calls `setAutoWeatherEnabled(false)` and stops all network use. Round to 2 decimals (about 1 km), which is enough for weather. List the endpoints in the README. Keep the city name optional.

**P-39 · Med · `BluetoothWatcher.java:586-616`** `[verify in car]`
- **What happens:** `broadcastHeadUnitConnect()` sends the phone's MAC, name and the whole `BluetoothDevice` parcelable as extras (`address`, `mac`, `name`, `dev_name`, `device`) in implicit broadcasts to 11 action strings, twice per attempt (P-08).
- **Why it matters:** Any installed app can register for those public strings and collect the driver's phone identity. In the case of a vendor system app that reads them, the data goes to an app the launcher author never reviewed.
- **Fix:** Send only to the receiver the active profile names (`setPackage` or `setComponent`), and only the fields that receiver needs. With no profile, send nothing (P-76).

**P-40 · Med · `MediaSyncService.java:21`, `:97-101`, `:146-150`, `:180-182`, `:218-220`; `MainActivity.java:2272`, `:2290`**
- **What happens:** Track title, artist and play state are broadcast app-wide with an implicit intent. The receiver in `MainActivity` is registered with no permission, so any app can send `com.apexdrive.launcher.MEDIA_UPDATED` to set the track text, the play state, and (through `:2262`) the "CarPlay connected" state.
- **Why it matters:** Metadata leaks to other apps, and the dashboard can be spoofed.
- **Fix:** Preferred: replace the broadcast with an in-process observer (this also unlocks album art, P-13). Otherwise `setPackage(getPackageName())` on every send, and a signature-level `<permission>` on the receiver. `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` needs `androidx.core` 1.9 or later.

**P-41 · Low · `CarThermometerWatcher.java:125-136`, `BluetoothWatcher.java:156-180`, `MediaSyncService.java:106-121`**
- **What happens:** Vendor and CAN actions are accepted from any sender, so any app can set the outside temperature, the Bluetooth state or the track.
- **Why it matters:** Integrity of what the driver reads. Impact is limited because nothing here controls the car.
- **Fix:** System and vendor actions cannot be protected with a permission. Limit the exposure by registering only the actions of the active profile (P-76) and by range-checking values (P-23).

**P-42 · Med · `AndroidManifest.xml:19-22`, `:70-76`, `BootReceiver.java:12-19`**
- **What happens:**
  - `BootReceiver` is `exported="true"` but does nothing except log, yet requires `RECEIVE_BOOT_COMPLETED`.
  - `REORDER_TASKS` (`:20`) and `ACCESS_NETWORK_STATE` (`:16`) are not used by any code (`moveTaskToFront` and `ConnectivityManager` do not appear in the sources).
  - `QUERY_ALL_PACKAGES` (`:22`) has no effect at `targetSdk` 28 (package visibility filtering starts at 30).
  - `KILL_BACKGROUND_PROCESSES` supports the weak feature in P-15.
- **Why it matters:** Over-broad permissions in a public project invite scrutiny and make the later `targetSdk` upgrade (P-71) harder. `[verify in car]` ACC wake without reboot does not fire `BOOT_COMPLETED` (P-57), so this receiver does not deliver the "instant ignition wake" its comment promises.
- **Fix:** Delete `BootReceiver`, `RECEIVE_BOOT_COMPLETED`, `REORDER_TASKS`, `ACCESS_NETWORK_STATE`. Keep `QUERY_ALL_PACKAGES` only with a comment that a launcher needs it from `targetSdk` 30, or replace it with a `<queries>` block for `MAIN/LAUNCHER`.

**P-43 · Low · `AndroidManifest.xml:25`**
- **What happens:** `allowBackup="true"` lets `adb backup` and cloud backup copy the preferences, which include the driver phone's MAC (`PreferenceHelper.java:14`) and the recent-apps list.
- **Why it matters:** Phone identity in backups.
- **Fix:** `android:allowBackup="false"`.

**P-44 · Med · `MainActivity.java:137`, `:2422-2448`**
- **What happens:** `checkPermissions()` requests location (fine and coarse) and the two Bluetooth permissions in `onCreate`, with no rationale. The Bluetooth ones are install-time "normal" permissions, so they are never prompted. A denial is not handled; the result callback only restarts GPS.
- **Why it matters:** The first thing a new user sees is a permission dialog over an empty cockpit. After a denial the Maps screen reads "Searching Satellite Coordinates..." forever (`view_maps.xml:85`), with nothing explaining why. `README.md:59` tells the user to pick "Allow all the time", an option that does not exist for `targetSdk` 28.
- **Fix:** Ask in context on the first Maps or speed tap, explain why, and show an explicit "Location permission denied" state with a button. Fix the README. `[verify in car]` some units hide or auto-deny the permission dialog; the app should then say how to grant it with `adb shell pm grant`.

---

## D. Performance and battery

Target: Allwinner T3L (quad Cortex-A7 class), 2 GB RAM, Mali-400 GPU, 1024x600. **Nothing here was measured**; the findings are costs visible in the code. Before and after each fix, capture on the unit over `adb` (Wi-Fi): `dumpsys meminfo com.apexdrive.launcher`, `dumpsys gfxinfo com.apexdrive.launcher framestats`, `top -m 5 -n 1` while on the dash, and a 10-minute `simpleperf` or systrace trace with the app in the background. Battery is a secondary concern in a car; CPU heat, jank in the foreground navigation app, and Binder/broadcast traffic are the real costs.

**P-45 · High · `GpsTelemetryManager.java:63-78`, `MainActivity.java:2451-2456`, `:2458-2475`**
- **What happens:** GPS is requested at `minTime=50 ms, minDistance=0` and the network provider at 500 ms. Every fix runs five listener callbacks, each wrapped in `runOnUiThread` (already on the main thread because no looper is given), with several `String.format` calls and about a dozen `setText` calls (`MainActivity.java:1544-1601`). `onResume` calls `gpsManager.start()` again (`:2454`), and nothing stops GPS until `onDestroy` (`:2462`). The launcher is the HOME app, so it sits behind the navigation app almost all the time.
- **Why it matters:** Continuous main-thread work and location wakeups for views nobody can see, on a SoC that is also decoding the navigation app and the CarPlay stream.
- **Fix:** Request `GPS_PROVIDER` at 1000 ms and stop updates in `onPause` (or `onStop`); only push to the UI when the displayed value changes. If the Maps screen needs more, request faster rates only while it is visible.

**P-46 · Med · `BluetoothWatcher.java:618-627`, `:634-738`, `:389-399`, `:451-459`, `MainActivity.java:1611-1618`**
- **What happens:** A main-thread poll every 3 s runs `checkCurrentState()`: two profile `getConnectedDevices()` Binder calls, `getBondedDevices()`, reflection `isConnected` per bonded device, three `getProfileConnectionState` calls, eight `SystemProperties` reflection lookups and up to five `Settings.System` reads. Every branch ends in `notifyConnected()` regardless of change, and the listener refreshes the header, card and badge (`setText`, `setColorFilter`) each time.
- **Why it matters:** Steady main-thread Binder traffic and redundant redraws, forever.
- **Fix:** Event-driven first (the broadcasts and proxy callbacks already exist). Keep a fallback poll at 15 to 30 s on a `HandlerThread`, de-duplicate state (`if (same) return`), and cache the reflected `Method` objects (P-53).

**P-47 · Low · `MainActivity.java:1528-1537`, `:2461`**
- **What happens:** The clock runnable fires every second and calls `setText` with minutes-only text, so 59 of 60 updates are no-ops. It is removed only in `onDestroy`.
- **Why it matters:** 86,400 pointless wakeups a day, plus formatting work.
- **Fix:** Register a dynamic `ACTION_TIME_TICK` receiver (once a minute, also covers P-17), or schedule the next tick at the minute boundary.

**P-48 · Med · `CarThermometerWatcher.java:350-372`, `:393-402`**
- **What happens:** Every 15 s, `checkSystemProperties()` starts a new thread and, for up to five property names, runs `Runtime.exec("getprop <name>")`. A unit that has none of those properties (most of them) spawns five processes every 15 s, about 20 per minute, for the whole session.
- **Why it matters:** Process spawns are expensive on this CPU, and the work is pointless where the properties do not exist.
- **Fix:** Read properties through the reflection already written in `BluetoothWatcher.java:451-459`, only for names in the active profile, at most once a minute. Better: when a CAN broadcast has been seen, stop polling.

**P-49 · Med · `MainActivity.java:1116`, `:1165`, `:1240`, `:1332-1346`, `:2403-2420`**
- **What happens:** `showCarPlayPicker`, `showRadioPicker` and `showMapPicker` call `loadInstalledApps()` on the UI thread, which queries all launcher activities and calls `loadLabel` and `loadIcon` for each, although pickers show only text. `findInstalledRadioPackage()` (`:1332-1346`, reached from `launchRadioApp`) loads labels for every app on the UI thread too.
- **Why it matters:** With a couple of hundred apps this can block the main thread long enough to feel hung or trigger an ANR (not measured).
- **Fix:** Keep one cached, labels-only list built off the UI thread and refreshed by the package receiver (P-25). Load icons lazily for the Apps grid only.

**P-50 · Low · `MainActivity.java:1481-1487`, `:1400-1470`**
- **What happens:** `showView()` calls `applyAccentColor()` on every navigation tap. That writes the preference (`prefHelper.setAccentColor`, `:1401`), does 12 `findViewById` calls and a few dozen `setTextColor` / `setColorFilter` calls, then runs `updateRailAppearance()` a second time (`:1469`, `:1485`).
- **Why it matters:** Avoidable work in the one interaction that must feel instant.
- **Fix:** Apply the accent when the colour changes and when a view is first attached; cache the view references.

**P-51 · Med · `MainActivity.java:234-247`, `:146-149`, `AppsGridAdapter.java:22-24`, `AppInfo.java:9`**
- **What happens:** All six screens are inflated synchronously in `onCreate` and stay in memory. The startup loader is hidden immediately (`:147-149`), so it never shows. The apps grid keeps a `Drawable` per installed app for the lifetime of the process.
- **Why it matters:** Slower cold start (the launcher restarts on every Home press if the process was killed) and a bigger heap on a 2 GB unit.
- **Fix:** Inflate each screen on first visit (or use `ViewStub`), load icons through a small `LruCache`, and delete the dead loader views or make them real.

**P-52 · Med · `MediaSyncService.java:153-276`, `MainActivity.java:1350-1398`, `BluetoothWatcher.java:586-616`**
- **What happens:** Single taps fan out into dozens of operations: pause is about 19 broadcasts, key dispatches and one `exec`; play is about 33; connecting a phone is 22 broadcasts; starting the radio is 15 broadcasts. Every implicit broadcast wakes every matching receiver on the device, including the vendor MCU services, and `sendMediaKey` starts a new thread and a new process per call.
- **Why it matters:** UI jank on tap, and unpredictable side effects in other apps (see P-09, P-10, P-76).
- **Fix:** One action per command, targeted at the active profile's receiver (P-76).

**P-53 · Low · `BluetoothWatcher.java:317`, `:451-459`, `:464`, `:486`; `CarThermometerWatcher.java:184`, `:219`, `:351`; `MainActivity.java:964`**
- **What happens:** `getMethod(...)` is repeated on every call, and each background job is a bare `new Thread` with no pool, naming or cancellation. HTTP connections are never `disconnect()`ed and responses are read with no size cap.
- **Why it matters:** Minor CPU churn, leaked threads after `stop()`, and a late result can post to a stopped watcher (`:256`, `:284`).
- **Fix:** Cache `Method`s in static fields, use one single-thread `ExecutorService` per component and shut it down in `stop()`, and call `disconnect()` in `finally`.

---

## E. Lifecycle and robustness

**P-54 · High · `MainActivity.java:127-150`, whole app (no `UncaughtExceptionHandler`)**
- **What happens:** The app is the HOME activity. If `onCreate` throws (an unguarded `findViewById` such as `:568-569`, `:836`, `:1003`, an inflation `OutOfMemoryError` on a loaded 2 GB unit, a missing resource after a bad update), Android restarts HOME immediately and the unit enters a crash loop with no UI. The git history mentions "global error handling" (`287286f`), but there is no `setDefaultUncaughtExceptionHandler` anywhere in the tree.
- **Why it matters:** For a head unit, a launcher crash loop means no navigation, no radio and no way to reach Settings without `adb` or a factory reset. This is the single worst failure mode of a HOME app.
- **Fix:** (1) Document recovery in the README: keep the stock launcher installed, how to clear the default (`Settings > Apps > ApexDrive > Clear defaults`) and the `adb` commands. (2) Add a crash-loop guard: record crash timestamps in `SharedPreferences`; if the app crashed more than twice in 60 s, start the stock HOME activity (query `ACTION_MAIN/CATEGORY_HOME` and skip this package) and show a one-line explanation. (3) Null-check or `@NonNull` the `findViewById` results that are used without guards.

**P-55 · Med · 85 `catch (Exception|Throwable)` sites, 76 lines naming `ignored`; only log call is `BootReceiver.java:17`**
- **What happens:** Almost every platform call is wrapped in `catch (Exception ignored)`. `sendBroadcast` does not throw for missing receivers, so those catches protect nothing, and the real failures (`SecurityException`, `ActivityNotFoundException`, reflection errors) are swallowed with no `Log` line. Across all Java files there is one `Log.d`.
- **Why it matters:** The design relies on silent failure (the "shotgun" in P-76), so nothing can be diagnosed from a car. A bug report cannot say which signal fired or which call failed.
- **Fix:** Log every catch with a tag at `Log.w` minimum, distinguishing `SecurityException` from `ActivityNotFoundException`. Add a Setup toggle for a rolling in-app diagnostics log (last 200 lines) the user can show or export, and use it for the "which signal fired" lines in P-04.

**P-56 · Med · `MainActivity.java:653-665`, `:2443-2448`, `view_maps.xml:85`, `GpsTelemetryManager.java:62`, `:71`**
- **What happens:** If location permission is denied or the GPS provider is off, `requestLocationUpdates` throws `SecurityException` (caught, `printStackTrace`) or is skipped silently. The only prompt is a toast after tapping the speed readout (`:653-660`). The UI stays on "Searching Satellite Coordinates...".
- **Why it matters:** No way to tell "no signal yet" from "you denied permission" from "GPS disabled in the vendor settings".
- **Fix:** Expose explicit states (`NO_PERMISSION`, `PROVIDER_OFF`, `SEARCHING`, `FIX`) from `GpsTelemetryManager` and render each. Same pattern for network (P-24) and notification access (P-14).

**P-57 · Med · `MainActivity.java:2450-2456`, `BootReceiver.java:12-19`** `[verify in car]`
- **What happens:** Cold boot is handled by the HOME intent. Waking from ACC-off sleep without a reboot is handled only by whatever `onResume` and the 3 s Bluetooth poll happen to do. No `ACTION_SCREEN_ON`, `ACTION_USER_PRESENT` or vendor "ACC on" action is registered, and `BOOT_COMPLETED` does not fire on a sleep/wake cycle on most units.
- **Why it matters:** After a stop at the shops, the dash may show state from before sleep (stale trip, phone "connected", old weather) until something else triggers a refresh.
- **Fix:** On `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT`, refresh Bluetooth and weather and re-check GPS. Add the unit's own wake action to the profile (P-76) once it is known by observation.

**P-58 · Med · `MediaSyncService.java:41-53`, `:278-286`**
- **What happens:** The `OnActiveSessionsChangedListener` is added with a lambda (`:45-46`) and never removed; `onDestroy` removes only the vendor receiver. There is no `onListenerConnected` / `onListenerDisconnected` override, so when the system unbinds the listener (for example after the grant is revoked, or on a rebind) it is never re-requested. State sits in `static` fields (`:26-27`) that survive the service.
- **Why it matters:** Stale controller after a restart of the media app, and duplicate listeners over time (compounds P-11).
- **Fix:** Register in `onListenerConnected`, unregister in `onListenerDisconnected` and `onDestroy`, call `requestRebind(componentName)` (API 24+; guard for 21 to 23), and clear the statics.

**P-59 · Med · `BluetoothWatcher.java:317`, `:453`, `:464`, `:486`, `:543`, `:552`** `[verify in car]`
- **What happens:** `BluetoothDevice.isConnected()`, `BluetoothDevice.connect()`, `BluetoothAdapter.getConnectionState()`, the profile proxies' `connect(BluetoothDevice)` and `SystemProperties.get` are reached by reflection. Android 9+ applies non-SDK interface restrictions to apps regardless of `targetSdk`; behaviour differs by Android version and by vendor ROM, and each failure is swallowed (P-55).
- **Why it matters:** "Works on the author's unit" does not predict other units, and it can stop working with no visible error after a ROM update.
- **Fix:** Keep reflection behind one `HiddenApi` helper that logs outcome once per session, and record the Android version and result in the diagnostics log so a reader can see which units rely on which path.

**P-60 · Low · `MainActivity.java:268-271`, `:695-718`, `:1819`, `:1017-1018`, `:1066`, `:2467-2469`**
- **What happens:** Only `deviceSelectorDialog` is dismissed in `onDestroy`; the task switcher, OsmAnd, calibration and CarPlay-not-found dialogs are not tracked, so `recreate()` or a Home press can leak a window. Two `startActivity` calls have no `try/catch` (`:1017-1018` Android Settings, `:1066` fallback); on a stripped ROM without a Settings activity they crash.
- **Why it matters:** Logcat "WindowLeaked" noise, and a crash path in a HOME app (P-54).
- **Fix:** Keep one `currentDialog` reference and dismiss it in `onPause`; wrap the two calls.

**P-61 · Low · `CarThermometerWatcher.java:41-46`, `:245-250`, `:202`**
- **What happens:** `currentTempC`, `rawTempC`, `currentCondition` and `currentCity` are written from worker threads and read from the main thread without `volatile` or synchronisation.
- **Why it matters:** Visibility bugs are unlikely to bite, but the code is one refactor away from showing a half-updated pair.
- **Fix:** Post results to the main thread through one method that sets all fields together (the `pollHandler.post` at `:256` already exists; do the field writes inside it).
