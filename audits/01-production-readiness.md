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

---

## F. Build, release and process

The repository is built only by GitHub Actions (`.github/workflows/build.yml`). The findings below were checked against the file list of `6b31f21` (`git ls-files`) and the workflow; the build itself was not run.

**P-62 · High · `gradle/wrapper/gradle-wrapper.properties:4`, `.github/workflows/build.yml:35`, `README.md:33`, `AGENT_HANDOFF.md:24`, `:124`**
- **What happens:** The repo has `gradle-wrapper.properties` (Gradle **7.5.1**) but no `gradlew`, no `gradlew.bat` and no `gradle-wrapper.jar`. CI ignores the wrapper and installs Gradle **8.2** (`gradle-version: '8.2'`). The README and handoff tell contributors to run `./gradlew assembleDebug`, which does not exist, and the handoff says the build system is "Gradle 8.2".
- **Why it matters:** A contributor cannot reproduce the CI build locally, and three documents disagree about the Gradle version.
- **Fix:** Commit a wrapper generated with the version CI is known to pass on (`gradle wrapper --gradle-version 8.2` with Android Gradle Plugin 7.4.2 as in `build.gradle:8`), make CI call `./gradlew`, and drop the `gradle-version` input.

**P-63 · High · `.github/workflows/build.yml:38-47`** `[verify by installing two CI builds]`
- **What happens:** CI builds `assembleDebug` and uploads the debug APK. A fresh GitHub runner generates a new `~/.android/debug.keystore`, so every CI build is signed with a different key.
- **Why it matters:** Installing build N+1 over build N on the head unit fails with a signature mismatch ("App not installed"), so each update means uninstalling first and losing preferences (driver phone, map, accent colour).
- **Fix:** Sign with one stable key. For an open-source project the usual options are a keystore in repository secrets with a `release` signing config, or a committed, clearly labelled debug keystore referenced by `signingConfigs.debug.storeFile`.

**P-64 · Med · `app/build.gradle:13-14`, `:19-24`; no `app/proguard-rules.pro`**
- **What happens:** `versionCode 1` and `versionName "1.0.0"` are literals. `release` has `minifyEnabled false`, references `proguard-rules.pro` (`:22`) which is not in the repository, and has no signing config, so `assembleRelease` would produce an unsigned APK that cannot be installed. CI never builds release.
- **Why it matters:** Installers on head units often refuse or ignore a package whose `versionCode` has not increased, and there is nowhere to ship a signed, versioned build from. Turning on minify later fails on the missing file.
- **Fix:** Derive `versionCode` from `GITHUB_RUN_NUMBER` or the commit count, add an empty `proguard-rules.pro`, add the signing config from P-63, and publish tagged releases (GitHub Releases) instead of build artifacts only. R8 is optional; the reflection in this app targets framework classes, which R8 does not rename.

**P-65 · Med · `.github/workflows/build.yml:3-6`, `:14`, `:17`, `:23`, `:32`, `:42`**
- **What happens:** The workflow runs on `push` to any branch and `workflow_dispatch`, with no `pull_request` trigger, no lint, no unit tests, no APK name or retention policy, and all actions pinned by tag (`@v3`, `@v4`) rather than by commit.
- **Why it matters:** Pull requests from forks do not run the base repository's workflow on push, so outside contributors get no CI signal. Nothing checks style, lint or tests before merge. The handoff says "Automatically builds on push to `main`" (`AGENT_HANDOFF.md:25`), which understates what the file does.
- **Fix:** Add `pull_request`, run `./gradlew lintDebug testDebugUnitTest assembleDebug`, pin actions by SHA with Dependabot for updates, set `retention-days`, and require the check on `main`.

**P-66 · High · repository root**
- **What happens:** There is no `.gitignore`, `LICENSE`, `CONTRIBUTING.md`, `SECURITY.md` or pull-request template.
- **Why it matters:** The first Android Studio sync would stage `build/`, `.gradle/`, `.idea/` and `local.properties` (which holds a developer's SDK path). Without a license the code is legally "all rights reserved", so outside contributions have no stated terms. The project is meant to be open source.
- **Fix:** Add the standard Android `.gitignore`, a license chosen by the owner, and a short `CONTRIBUTING.md` with the commit and branch rules (Conventional Commits, one branch per change, CI must pass).

**P-67 · Low · `app/build.gradle:31-33`, `:38-40`; `gradle.properties:3`**
- **What happens:** Material 1.6.1, ConstraintLayout 2.1.4 and CardView 1.0.0 are declared, but no Java or XML file references `Material`, `ConstraintLayout` or `CardView`, and the theme parent is `Theme.AppCompat.NoActionBar` (`styles.xml:2`). `viewBinding true` is on but no binding class is used (everything is `findViewById`), while the README and handoff both advertise ViewBinding. `android.enableJetifier=true` has nothing left to convert.
- **Why it matters:** Larger APK, slower CI, and the docs describe code that is not there.
- **Fix:** Remove the three libraries and Jetifier (AppCompat 1.4.2 alone covers everything used on API 21 to 28), then either adopt ViewBinding or switch it off and correct the docs. If Material is wanted for dialogs later, 1.6.1 is the right version for this `compileSdk`.

**P-68 · Med · `AGENT_HANDOFF.md`, `README.md`, `launcher_preview.html`, `anti-slop/`**
- **What happens:** Documentation has drifted:
  - `AGENT_HANDOFF.md:57-62` lists `view_cockpit_dashboard.xml`, `view_cockpit_maps.xml`, `view_cockpit_media.xml`, `view_cockpit_apps.xml` and `view_cockpit_settings.xml`, none of which exist. The real files are `view_dashboard`, `view_carplay`, `view_maps`, `view_audio`, `view_apps` and `view_setup`.
  - `AGENT_HANDOFF.md:24` says Gradle 8.2; the wrapper says 7.5.1 (P-62).
  - `README.md:13` says "8-point compass"; the code has 16 directions (`GpsTelemetryManager.java:162`).
  - `README.md:61` promises album art (P-13); `:59` says "Allow all the time" (P-44); `:17` claims "<35 MB RAM" with no measurement.
  - `launcher_preview.html` (52 KB, last touched 2026-10-13) is a hand-made mock that mentions "Santa Fe" five times and is not generated from the app.
  - The two `anti-slop` audits self-certify items that are false (P-35).
- **Why it matters:** The docs steer new contributors and AI agents to files and behaviours that do not exist.
- **Fix:** Correct the docs when the code changes, delete or clearly mark `launcher_preview.html` and `anti-slop/` as historical, and see the personal-data cleanup in P-73.

**P-69 · Med · repository (no `src/test`, no `src/androidTest`)**
- **What happens:** There are zero tests. `build.gradle:16` sets `AndroidJUnitRunner` but no `androidx.test` dependency exists.
- **Why it matters:** Every fix in this audit is unverifiable without the unit.
- **Fix:** Start with plain JVM unit tests that need no Android SDK mocking: bearing-to-compass conversion (`GpsTelemetryManager.bearingToDirection`, currently private), temperature parsing (`parseAndSetTemp`), the recents-list codec in `PreferenceHelper`, the speed and distance conversions, and the future profile loader (P-76). Add `testImplementation 'junit:junit:4.13.2'`.

**P-70 · Low · `MainActivity.java` (2,476 lines)**
- **What happens:** One class owns navigation, wiring for six screens, Bluetooth UI, the task switcher, radio, maps, pickers, calibration and dialogs.
- **Why it matters:** Contributors will conflict on every change and nothing is unit-testable. It is also why single findings in this audit span hundreds of lines.
- **Fix:** Split by responsibility, not all at once: extract `CarPlayLauncher`, `AppRepository`, `RadioController` and a `TaskSwitcherDialog` first (each is called from several places), then one controller per screen.

**P-71 · Low · `app/build.gradle:7`, `:12`**
- **What happens:** `compileSdk 33` with `targetSdk 28` is deliberate (it keeps the legacy permission and background rules) but is documented nowhere.
- **Why it matters:** The next contributor raising `targetSdk` will break the app in ways this audit already lists: background location (29), package visibility (30), `BLUETOOTH_CONNECT` and mandatory explicit `exported` (31), notification permission (33).
- **Fix:** Add a short "Why targetSdk 28" paragraph to the README with this list.

---

## G. Vehicle and head-unit assumptions

The app was built for one car (a Hyundai Santa Fe, the code and docs say "IX45" and "2015", while the CAN profile string says "13 All new Santafe" `[inferred]` the same generation) on one head unit (Allwinner T3L with the Zlink 5.3 CarPlay box and a Topway-style vendor stack `[inferred]` from the `com.ts.*` names). Almost nothing is labelled as an assumption; it is compiled into layouts and Java literals. This section lists all of it, then proposes one fix direction.

Audit 3 plans a first-run car make/model selector. That selector should set a **vehicle profile** (labels, CAN decoding), and a separate **head-unit profile** should hold the vendor stack (broadcasts, Settings keys, packages). They change for different reasons and should not share a file.

### G.1 Car-specific hardcoded values

| Value | Where | Finding |
|---|---|---|
| `SANTA FE · IX45` header pill | `activity_main.xml:68-74` (id `tvVehicleModel`) | P-72 |
| `HYUNDAI SANTA FE · IX45 COCKPIT` splash text | `activity_main.xml:408` | P-72 |
| `ZLINK 5.3 HARDWARE PROJECTION · HYUNDAI SANTA FE` | `view_carplay.xml:36` | P-72 |
| `Direct USB OTG link via Santa Fe center console or glove box port` | `view_carplay.xml:131` | P-72 |
| `Connects over Bluetooth (SantafemR)` | `view_carplay.xml:86` | P-72, P-73 |
| `SANTAFEMR` default device name | `view_dashboard.xml:153`, `strings.xml:14` (unused) | P-72, P-73 |
| `Pair via SantafemR (PIN 0000)` | `BluetoothWatcher.java:731` | P-72, P-73 |
| `Calibrate to match your Hyundai instrument cluster` | `MainActivity.java:1746` | P-72 |
| `accent_hyundai_blue` colour name ("Hyundai N-Line") | `colors.xml:17` | P-72 |
| Factory PINs `123456` and `7890`, `CANbus: 03: Simple Soft (XP)`, `Profile: 61: Hyundai Kia / 01: 13 All new Santafe` | `MainActivity.java:1055` | P-74 |
| `02 · FACTORY SETTINGS (PIN 123456)` button | `view_setup.xml:281` | P-74 |
| Same PINs, decoder and profile, plus "2015 Hyundai Santa Fe" | `README.md:2`, `:40-76` | P-74 |
| `Yassin's iPhone` sample device name | `item_device_card.xml:32` | P-73 |
| `c:\Users\yassi\Documents\Android Launcher` | `README.md:25`; `file:///c:/Users/yassi/...` links at `AGENT_HANDOFF.md:77`, `:92`, `:97`, `:136-140` | P-73 |
| `Santa Fe` x5 in the preview mock | `launcher_preview.html` | P-68, P-72 |
| Protocol and hardware claims: `ZLINK 5.3`, `USB 2.0`, `I2S`, `Rohm/SI473X ICs`, `Rohm37XXX` | `view_carplay.xml:99`, `:144`, `:176`, `:189`; `view_audio.xml:95`; `anti-slop/audit-001-2026-10-10.md:32` | P-29, P-31 |
| OsmAnd 4.5.10 and the UAE map file `United-arab-emirates_asia.obf` | `MainActivity.java:697-701` | P-75 |
| Default map `net.osmand.plus` / `OsmAnd Offline Maps`; default CarPlay `com.zjinnova.zlink` / `Zlink 5.3` | `PreferenceHelper.java:26-27`, `:144`, `:152`; `view_setup.xml:103`, `:163` | P-75 |
| `RADARS · ROAD BUMPS · OFFLINE LANES` (OsmAnd's Gulf data) | `view_maps.xml:281` | P-30, P-75 |
| FM band 87.5 to 108.0 MHz | `MainActivity.java:891`, `:912`, `:1352` | P-75 |
| 1024x600 layout assumption | `AGENT_HANDOFF.md:11`; sizes in `view_apps.xml:28`, `view_audio.xml:10`; dialog width `MainActivity.java:449-450` | P-79 |

**P-72 · High · `activity_main.xml:68-74`, `:408`; `view_carplay.xml:36`, `:86`, `:131`; `BluetoothWatcher.java:731`; `MainActivity.java:1746`**
- **What happens:** A Hyundai Santa Fe identity is shown in the header of every screen and in four other places, whatever car the unit is in. See the table above.
- **Why it matters:** A different driver sees another car's name on the first line of their dash, which makes the app unusable as a shared open-source launcher.
- **Fix:** Move the label to the vehicle profile; with no profile, show nothing in the header. Replace "Santa Fe", "Hyundai" and "SantafemR" in descriptions with neutral text or a profile field.

**P-73 · High · `item_device_card.xml:32`, `README.md:25`, `AGENT_HANDOFF.md:77`, `:92`, `:97`, `:136-140`, `strings.xml:14`, `view_dashboard.xml:153`, `view_carplay.xml:86`, `BluetoothWatcher.java:731`**
- **What happens:** The repository contains a person's first name (`Yassin's iPhone`), a Windows user-profile path (`c:\Users\yassi\...`, nine links), and the head unit's own Bluetooth name as default UI text. `[inferred]` `SantafemR` is the name phones see when pairing with the unit: the code tells the user to "Pair via SantafemR (PIN 0000)", so it is not a phone name.
- **Why it matters:** Personal data in a public repository that will gain contributors. The sample name is only visible in the layout editor and in the preview mock, but it ships in the APK resources. The Bluetooth PIN `0000` is also the vendor default, not a universal value.
- **Fix:** Replace the sample with a neutral string (`Phone`). Delete or relativise the paths and links. Make the unit name a profile or detected value (`BluetoothAdapter.getName()` when available). Git history keeps the old text; deciding whether to rewrite history is the owner's call, and I do not recommend it for a handful of strings.

**P-74 · High · `MainActivity.java:1052-1070`, `view_setup.xml:274-281`, `README.md:66-76`**
- **What happens:** `FACTORY SETTINGS` shows "Factory PIN: 123456 / Alternative PIN: 7890" and a CAN decoder (`03: Simple Soft (XP)`) and car profile (`61: Hyundai Kia / 01: 13 All new Santafe`) as if they were facts about every unit, and the button label repeats the PIN. The README repeats it as setup instructions for "your Santa Fe".
- **Why it matters:** On another unit those numbers are wrong at best and, at worst, steer a user toward the wrong decoder setting (the decoder and the car profile determine how CAN frames are interpreted). Vendor factory codes differ between head-unit makers and firmware.
- **Fix:** Move this to an optional `factoryHint` text in the profile; default to none. Keep the intent targets (`com.ts.factory`, `com.microntek.factorysettings`, `com.syu.settings`, `:1057-1062`) in the profile too, because they are vendor packages (P-78).

**P-75 · Med · `MainActivity.java:695-701`, `:891`, `:912`, `:1352`, `:633-636`, `:1565`, `:1529`; `PreferenceHelper.java:44`, `:144`, `:152`; `view_maps.xml:281`; layouts and Java literals**
- **What happens:** Region and language choices are baked in: the missing-OsmAnd dialog names a UAE map file; the FM band is 87.5 to 108.0 MHz at 0.1 steps (Japan uses 76 to 95, and some regions step 0.05); POI searches use English words; the default unit is km/h; the clock is 12-hour (P-17) and coordinates assume N/E (P-18). Of 20 entries in `strings.xml` only `app_name` is used; every other label is a literal in a layout or in Java, so nothing is translatable and `supportsRtl="true"` (`AndroidManifest.xml:29`) is untested.
- **Why it matters:** The launcher cannot be used outside the author's region without editing code.
- **Fix:** Region data (FM band, step, units, map guidance) goes into the vehicle profile; user-visible text moves to `strings.xml`. Do the string move mechanically, one screen per commit, so Audit 2's layout changes do not conflict.

### G.2 Head-unit vendor assumptions

The launcher talks to the head unit's vendor stack by **broadcasting to every vendor's guessed action name** and **listening to every vendor's guessed action**, in code that swallows all failures (P-55). Eight vendor families appear in the strings: `ts` (Topway), `microntek`, `syu` (FYT/Joying), `allwinner`, `goc`, `hct`, `zlink` (`com.zjinnova.*`) and `diplay` (`com.shihab.diplay`), plus `ecar`, `forfan`, `manstep` (AutoKit), `hiworld` and `raise`.

| Direction | What is sent or listened for | Where |
|---|---|---|
| **Bluetooth connect (send)** | 11 actions: `com.allwinner.bt.connect`, `com.allwinner.bluetooth.connect`, `com.goc.bluetooth.connect`, `com.android.ecar.bluetooth.connect`, `com.syu.bt.connect`, `action.hct.bt.connect`, `com.ts.bt.connect`, `com.microntek.bt.connect`, `cn.manstep.phonemirrorbox.bt.connect`, `com.zjinnova.zlink.bt.connect`, `com.forfan.bt.connect`; each with `address`, `mac`, `name`, `dev_name`, `device` extras; sent twice per attempt | `BluetoothWatcher.java:591-613`, `:479`, `:572` |
| **Bluetooth state (listen)** | 16 vendor actions registered; 10 are matched as "connected" and 7 as "disconnected", and one of those 7 (`com.allwinner.bluetooth.disconnected`) is never registered, so it can never fire | `BluetoothWatcher.java:103-124`, `:165-180` |
| **Bluetooth state (read)** | Properties `sys.bt.connected`, `persist.sys.bt.connected`, `vendor.bt.connected`, `ts.bt.state`, `goc.bt.connected`, `sys.fyt.bluetooth`, `bt.connected.name`, `bt.connected.mac`; Settings.System keys `bluetooth_state`, `bt_device_name`, `bt_connected_dev_name`, `current_connect_bt_addr`, `current_connect_bt_name` | `BluetoothWatcher.java:389-412`, `:420-448` |
| **Media metadata (listen)** | 9 vendor actions: `com.goc.bluetooth.track`, `com.goc.bluetooth.avrcp`, `com.microntek.bt.music`, `com.ts.bt.track`, `action.hct.bt.track`, `com.zlink.metadata`, `com.zlink.music_info`, `com.shihab.diplay.METADATA`, `com.shihab.diplay.PLAY_STATE`; the same list registered twice (service and activity), with differing extras keys `track/title/song/name`, `artist/singer/album` | `MediaSyncService.java:107-121`, `MainActivity.java:2273-2287` |
| **Media control (send)** | `com.zlink.command`, `com.shihab.diplay.MEDIA_CONTROL`, `com.goc.bluetooth.avrcp`, `action.hct.media.key`, `com.ts.media.key`, `com.microntek.media.key`, `com.syu.music.key`, `com.android.music.musicservicecommand`, plus `input keyevent` | `MediaSyncService.java:168-177`, `:206-216`, `:256-267`, `:273` |
| **Radio (send)** | 14 start actions (`com.ts.radio.*`, `com.allwinner.radio.*`, `com.microntek.radio.*`, `com.syu.radio.*`, `action.hct.media.key`, `com.android.fm.play`, `com.android.radio.PLAY`), 10 stop actions, 4 tune actions per press; `com.microntek.radio.power` sent in both start and stop | `MainActivity.java:1350-1398`, `:900-904`, `:921-925` |
| **System recents (send)** | `action.hct.recents`, `com.syu.recents`, `com.ts.recents`, `com.microntek.recents`, two AOSP names; `com.microntek.kill` | `MainActivity.java:495-502`, `:379` |
| **Outside temperature (listen)** | 11 actions (`com.canbus.temperature`, `com.microntek.canbus.*`, `com.ts.can.temperature`, `action.hct.canbus.temp`, `com.syu.canbus`, `com.hiworld.canbus.carinfo`, `com.raise.canbus`, `com.android.settings.CANBUS_CAR_INFO`, `android.intent.action.AIR_CONDITION_CHANGED`, `com.android.ecar.cannews`, `com.microntek.canbus.display`); extras `out_temp`, `temperature`, `ext_temp`, `ambient_temp`, `temp`, `celsius`, `car_temp`; Settings keys `can_temperature`, `out_temperature`, `car_out_temp`, `canbus_temp`, `exterior_temp`, `mcu_out_temp`; properties `persist.sys.can.temp`, `sys.car.temp`, `sys.car.outtemp`, `persist.fyt.temp`, `sys.canbus.out_temp` | `CarThermometerWatcher.java:125-136`, `:302`, `:332-339`, `:352-358` |
| **Packages launched** | CarPlay bridges (5 in the picker, 8 in the launch fallback), BT managers (10), radio apps (6 in the picker, 23 in the detector), factory settings (4), equaliser (4), USB music (5), file managers (6) | `MainActivity.java:1120-1133`, `:2320-2329`, `:1093-1104`, `:1169-1184`, `:1300-1323`, `:1057-1062`, `:1073-1078`, `:874`, `:703-706` |
| **CarPlay launch** | Explicit action `zjinnova.android.intent.action.ZLINK_MAIN` with extras `page=LINK`, `feature=carplay` | `MainActivity.java:2308-2312` |

**P-76 · High · `BluetoothWatcher.java:586-616`, `MediaSyncService.java:153-276`, `MainActivity.java:1350-1398`, `:495-507`, `:379`** `[verify in car]`
- **What happens:** Every control action (connect, media key, radio, recents, kill) is sent to all vendor namespaces at once with `sendBroadcast`, wrapped in `catch (Exception ignored)`. `sendBroadcast` does not throw when nobody listens, so the `catch` is dead and the only visible outcome is whatever a matching receiver does.
- **Why it matters:**
  - On the author's unit most of these names match nothing and cost CPU and Binder traffic (P-52).
  - On another vendor's unit, an action name that coincidentally matches does something unreviewed. `com.microntek.radio.power` is named like a toggle and is sent when the user presses pause (`:1391`).
  - The BT connect burst leaks the phone's MAC and name to any app (P-39).
  - There is no way for a contributor to know which names were ever observed to work.
- **Fix:** See **G.3**. In short: a **per-unit profile** that names exactly the actions, extras, Settings keys and packages that were observed on that unit, a Generic profile that sends nothing vendor-specific, and the same tables used for both sending and listening.

**P-77 · Med · `BluetoothWatcher.java:389-412`, `CarThermometerWatcher.java:332-358`** `[verify in car]`
- **What happens:** The property and `Settings.System` key names come from no documented source in the repo. Reads of missing keys return null or "", which is harmless, but a key that exists on another unit with a different meaning (for example `bluetooth_state`) flips the detection logic (P-04).
- **Why it matters:** The same name can mean different things on different firmware; a wrong "true" is displayed as fact.
- **Fix:** Profile entries carry the key, the meaning and the unit (for example `{ "key": "can_temperature", "unit": "C" }`), and a `verifiedOn` list (model, firmware, date, contributor).

**P-78 · Med · `MainActivity.java:1093-1104`, `:874`, `:1073-1078`, `:703-706`**
- **What happens:** Several lists are plainly mis-assigned: the Bluetooth manager list begins with `com.allwinner.bluetooth` and then includes `com.allwinner.radio` (a radio app) at `:1095` and `cn.manstep.phonemirrorbox` (the AutoKit CarPlay app) at `:1102`; the USB player list ends with `com.android.musicfx` (an audio-effects package, `:874`); the equaliser list contains `com.android.sound` (`:1077`); the factory list falls back to `com.android.settings`.
- **Why it matters:** The BT button can open the radio or the CarPlay app; evidence that these lists were assembled by copy, not tested.
- **Fix:** Resolve by intent first: `ACTION_BLUETOOTH_SETTINGS`, `Intent.makeMainSelectorActivity(ACTION_MAIN, CATEGORY_APP_MUSIC)`, `AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`; fall back to profile packages only if the intent does not resolve.

**P-79 · Med · `res/` (only `layout/`, `drawable/`, `values/`), `MainActivity.java:449-450`, `view_apps.xml:28`, `view_audio.xml:10`, `AndroidManifest.xml:42-43`**
- **What happens:** The UI is one fixed landscape layout set tuned for 1024x600 at mdpi. There are no `layout-sw*`, `layout-land`, `-hdpi` or `-xhdpi` variants, widths are fixed in dp (`260dp`, `220dp`, `58dp` buttons), the dialog has a 540 px minimum width, and orientation is locked to landscape. `AGENT_HANDOFF.md:11` mentions 1280x720 as a second target, but nothing in the repo covers it.
- **Why it matters:** Common head-unit panels include 800x480, 1280x720 and 1920x720; a layout that fits one panel can clip or leave dead space on the others. `[verify in car]` nothing here was run on any panel other than the author's.
- **Fix:** Test the six screens on 800x480, 1280x720 and 1920x720 (Android Studio can emulate those sizes; a physical unit is not needed for layout). Convert hard sizes to weights or `dimens.xml` per size class. Audit 2 owns the design.

**P-80 · High · `AndroidManifest.xml:46-50`, `README.md:50-52`, `AGENT_HANDOFF.md:74-76`**
- **What happens:** The app only works as intended on an Android head unit whose firmware lets the user choose a different HOME app and whose Bluetooth, CAN and radio are reachable through the Android-level calls the app tries. The handoff itself says Bluetooth on these units runs on an MCU over UART and the standard Android API may report nothing. There is no capability check: the app does not tell the user whether HOME replacement worked, whether Bluetooth is reachable, whether a GPS exists, or which vendor stack was recognised.
- **Why it matters:** On a unit with a locked launcher or a different stack the symptoms are the ones listed in section A (permanent "connected", dead controls), and the user cannot tell why. It is also not an Android Automotive OS (AAOS) app; the README never says so.
- **Fix:** A first-run capability probe that reports in plain language: `HOME` role resolved, Bluetooth adapter and bonded devices visible, location provider present, notification access (P-14), vendor stack recognised or "generic". Store the result in the diagnostics log (P-55). State in the README that it targets aftermarket Android head units, not AAOS and not phones.

**P-81 · Med · repository-wide** `[inferred]`
- **What happens:** The evidence is that **only one Allwinner T3L unit in one car was used**. The README and handoff describe "your Santa Fe" and that unit's CAN settings; the commit messages speak of "1024x600 tuning"; the audit calls the hardware claims accurate "for the Allwinner T3L Santa Fe" (`anti-slop/audit-001-2026-10-10.md:32`); and the vendor lists read like a union of namespaces seen in other Chinese head-unit apps rather than a record of what each unit sent. The only paths with specific, first-hand detail are the Zlink intent (`zjinnova.android.intent.action.ZLINK_MAIN`, `page=LINK`, `feature=carplay`), the DiPlay package and actions, the `com.ts.*` / `com.allwinner.*` radio actions and the Simple Soft (XP) CAN decoder.
- **Why it matters:** Every other vendor name in the tables above is unverified, and so is behaviour on any other unit, car or panel.
- **Fix:** Label unverified entries as such (`verifiedOn: []`), and make "tested on" a column in the profile file. No contribution adds a vendor entry without a model, firmware version and the log line that proved it.

### G.3 Fix direction: a per-unit profile instead of send-to-everyone

**Principle:** never send a vendor broadcast, read a vendor key, or launch a vendor package unless the active profile names it.

1. **Add the types, change no behaviour.** `HeadUnitProfile` (vendor stack) and `VehicleProfile` (labels, CAN meaning, region data), loaded from `assets/profiles/*.json` with a plain-Java parser (`org.json` is in the framework, so no dependency is needed). Unit-test the loader on the JVM (P-69).
2. **Ship two profiles.** `generic.json`: standard Android only (profile proxies, `AudioManager`, `MediaSession`, standard intents), no vendor strings. `allwinner-t3l-zlink.json`: **exactly what is hard-coded today**, copied verbatim, with `verifiedOn` empty except for the entries that are known to work (P-81). Existing installs select the second one on upgrade, so nothing changes for the author.
3. **Route every call site through the profile.** The eleven call sites in the table above call `profile.bt.connectActions`, `profile.media.controlActions` and so on, targeted with `setPackage` where the profile names a package (P-39, P-40, P-52). Listening registers only the profile's actions (P-41).
4. **Select and detect.** Pick by user choice in the first-run selector (Audit 3) with a suggested default from `Build.MANUFACTURER`, `Build.MODEL`, `Build.HARDWARE` and installed vendor packages. Never auto-send on a guess.
5. **Make contributions cheap.** A Setup screen "Learn this unit" that listens to *all* broadcasts for 60 s while the user pairs a phone, plays a track and turns on the radio, then writes a candidate profile and the diagnostics log to a file. A contributor can then send a profile plus the log line for each entry instead of editing Java.

Sketch of the shape (illustrative, not a final schema):

```json
{
  "id": "allwinner-t3l-zlink",
  "label": "Allwinner T3L + Zlink",
  "verifiedOn": [{ "model": "...", "firmware": "...", "date": "...", "by": "..." }],
  "bluetooth": {
    "connectedActions": ["com.ts.bt.connected"],
    "connectAction":   { "action": "com.ts.bt.connect", "package": "com.ts.bt" },
    "stateSettings":   [{ "key": "current_connect_bt_addr", "means": "connectedMac" }]
  },
  "media":   { "controlAction": { "action": "com.ts.media.key", "extra": "keycode" } },
  "radio":   { "package": "com.ts.radio", "bandMhz": [87.5, 108.0], "stepMhz": 0.1 },
  "packages": { "carplay": ["com.zjinnova.zlink"], "factory": ["com.ts.factory"] }
}
```
