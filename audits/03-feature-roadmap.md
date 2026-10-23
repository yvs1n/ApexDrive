# Audit 3: Feature roadmap — making ApexDrive actually useful

Status: proposal. Documentation only, no code changes. Companion documents: Audit 1 (`audits/01-production-readiness.md`, owns the `P-xx` finding IDs) and Audit 2 (UX and onboarding).

## 0. How to read this

**Target hardware.** An aftermarket Allwinner T3L head unit, 1024x600 mdpi landscape, Android API 21-28 (the app is `minSdk 21`, `targetSdk 28`, `compileSdk 33`). Bluetooth on such units is frequently run by a separate MCU, so standard Android Bluetooth APIs may not tell the truth (see `AGENT_HANDOFF.md` section 4A). Native apps, XML views and ViewBinding only; no third-party UI libraries.

**Screen names** follow the new vocabulary: Home (today's DASH), Phone (to be renamed Connect), Maps, Audio, Apps, Settings (today's SETUP), plus the first-run flow.

**Each feature has:** user value, how it works on this hardware (APIs for API 21-28, with API 31+/33+ alternatives noted separately), effort (S ≈ days, M ≈ 1-2 weeks, L ≈ weeks), dependencies, risks, the screen it lands on, and a **Verify in the car** note where the result depends on the head unit rather than on Android.

**Cross-references.** Bugs are not repeated here; they are cited as Audit 1 IDs (`P-xx`). Where a feature is only sound after a bug is fixed, the dependency says so. Audit 1 section G (vehicle and head-unit assumptions) is the reason F-01 to F-03 exist.

> Cross-references: Audit 1 section G is P-72 to P-81 (P-72 car identity, P-73 personal data, P-74 factory PIN and CAN dialog, P-75 region/language, P-76 vendor broadcast shotgun, P-77 guessed vendor keys, P-78 mis-assigned package lists, P-79 1024x600-only layouts, P-80 replaceable HOME and no capability probe, P-81 single-unit testing). Related: P-04 (BT false positives), P-06 (dead auto-connect), P-14 (notification access UI), P-38 (location consent), P-54 (crash loop as HOME). Audit 1 section G.3 proposes a `HeadUnitProfile` (vendor stack) separate from a `VehicleProfile` (labels, CAN, region); F-01 follows that split: its `headunits/` catalog is the HeadUnitProfile, `vehicles/` plus `regions/` are the VehicleProfile, and the make/model selector (F-02) fills the latter.

**What is hard-coded today** (from `origin/main` at `6b31f21`, this is the starting point for F-01):

| Assumption | Where |
| --- | --- |
| Header pill `SANTA FE · IX45`, `HYUNDAI SANTA FE · IX45 COCKPIT`, `SANTAFEMR`, Santa Fe/ZLink text | `res/layout/activity_main.xml`, `view_dashboard.xml`, `view_carplay.xml` |
| Expected Bluetooth name `SantafemR` (`bt_default_device`; "Pair via SantafemR (PIN 0000)") | `res/values/strings.xml`, `BluetoothWatcher.java` |
| Factory dialog: PIN `123456`, "CANbus 03 Simple Soft (XP), Profile 61 Hyundai Kia / 01 All new Santafe" | `MainActivity.openFactorySettings()` |
| Radio, media-key, power, recents broadcasts sent to every vendor (`com.ts.*`, `com.microntek.*`, `com.allwinner.*`, `com.syu.*`, `action.hct.*`, `com.android.fm.*`) | `MainActivity` (radio tune/power/stop, recents, `com.microntek.kill`), `BluetoothWatcher` (connect broadcasts) |
| Vendor system properties and `Settings.System` keys probed for BT state | `BluetoothWatcher` (`sys.bt.connected`, `ts.bt.state`, `bt_device_name`, ...) |
| UAE map instructions ("sideload OsmAnd 4.5.10 and United-arab-emirates_asia.obf") | `MainActivity.launchOfflineMap()` dialog |
| CarPlay/ZLink package list and explicit ZLink intent | `PreferenceHelper.DEFAULT_CARPLAY_PKG`, `MainActivity` |
| No Day/Night implementation exists; the accent colour is applied in code, and `recreate()` is used by "restart launcher" | `MainActivity` |

---

## Tier 1 — Make the core loop work

The core loop is: ignition on, phone connects, CarPlay (or music) is one tap away, now-playing is truthful, the screen is readable day and night, and none of it depends on the car being a Santa Fe.

### F-01 Vehicle Profile data model (bundled JSON, community-extensible)

**User value.** One resolved "Vehicle Profile" replaces every hard-coded Santa Fe/UAE/vendor assumption. The launcher header shows the driver's own car, the right vendor commands are used (and only those), and an unlisted car still works through a Generic profile. Because profiles are data, someone with a Kia Sportage on a Joying unit can add it with a pull request and no Java.

**How it works.**

Three small catalogs in `app/src/main/assets/`, so each contribution touches one file and is easy to review:

```
assets/profiles/
  schema/vehicle-profile.schema.json    # JSON Schema (draft 2020-12) for the three catalogs
  vehicles/hyundai.json                  # one file per make
  vehicles/_generic.json                 # "Other / not listed"
  headunits/allwinner-t3l.json           # one file per head-unit family
  headunits/_generic.json                # "Generic Android unit"
  regions/ae.json  regions/default.json  # units, map hints, optional extras
```

No index file is needed: `AssetManager.list("profiles/vehicles")` enumerates the makes at run time, so adding a file is the entire change. Parsing uses `org.json` (part of the platform since API 1), so no Gson or other dependency. A Node script (`tools/validate-profiles.js`, Node is already available to contributors and CI) validates every file against the schema and checks that every `headUnit.commands.*.actions` entry is a well-formed action; it runs as a CI step so a broken profile PR fails before review.

*Vehicle catalog* (`vehicles/hyundai.json`):

```json
{
  "schema": 1,
  "make": { "id": "hyundai", "name": "Hyundai" },
  "models": [
    {
      "id": "santa-fe-dm",
      "name": "Santa Fe",
      "years": "2012-2018",
      "generation": "DM (ix45 in some markets)",
      "headerName": "SANTA FE · IX45",
      "btNameHints": ["SantafemR", "Santa Fe*"],
      "suggestedHeadUnits": ["allwinner-t3l"],
      "defaultApps": {
        "carplay": ["com.zjinnova.zlink", "com.zlink.carplay", "cn.manstep.phonemirrorbox"],
        "navigation": ["net.osmand.plus", "net.osmand"]
      },
      "notes": "Factory-fit 7-inch replacement; CANbus decoder settings are on the head unit, not here.",
      "canbusHint": "Simple Soft (XP) · Hyundai/Kia profile (see head-unit factory menu)"
    }
  ]
}
```

*Head-unit catalog* (`headunits/allwinner-t3l.json`). `commands` is the key idea: each logical command lists exactly the broadcasts/keys that this family responds to, instead of the launcher shotgunning every vendor:

```json
{
  "schema": 1,
  "id": "allwinner-t3l",
  "name": "Allwinner T3L",
  "verified": "maintainer",
  "detect": {
    "buildProps": { "ro.board.platform": ["t3", "t3l"] },
    "packagesPresent": ["com.zjinnova.zlink"],
    "weight": 0.8
  },
  "bluetooth": {
    "androidStackReliable": false,
    "settingsKeys": ["bt_device_name", "bt_connected_dev_name", "current_connect_bt_addr"],
    "systemProps": ["sys.bt.connected"],
    "connectActions": []
  },
  "commands": {
    "radioPower": [{ "type": "broadcast", "action": "com.allwinner.radio.power" }],
    "radioTune":  [{ "type": "broadcast", "action": "com.allwinner.radio.tune", "extras": { "freq": "$freqKhz" } }],
    "radioStop":  [{ "type": "broadcast", "action": "com.allwinner.radio.stop" }],
    "mediaPlay":  [{ "type": "key", "keycode": "KEYCODE_MEDIA_PLAY" }],
    "recents":    [{ "type": "broadcast", "action": "com.android.systemui.recent.action.TOGGLE_RECENTS" }],
    "factorySettings": [{ "type": "activity", "action": "com.ts.factory" }]
  },
  "factoryPinHint": null,
  "carplayLaunch": [
    { "type": "activity", "action": "zjinnova.android.intent.action.ZLINK_MAIN", "extras": { "page": "LINK", "feature": "carplay" } }
  ]
}
```

*The example above is illustrative.* Which actions the T3L really answers is to be confirmed with F-03 ("Test my unit") before the entry is marked `"verified": "maintainer"`. Until then the shipped entry carries the union that works today, flagged `"verified": "unverified"`, and the Test screen narrows it. `verified` has three values: `maintainer` (tested by the project owner in a car), `community` (reported by a contributor with a Test-my-unit report attached), `unverified` (copied from forum knowledge).

*Generic fallbacks.* `headunits/_generic.json` uses only portable Android: media keys via `AudioManager.dispatchMediaKeyEvent` (API 19+), `Intent.ACTION_MAIN` launches, no vendor broadcasts, `bluetooth.androidStackReliable: true`. `vehicles/_generic.json` has `headerName: "ApexDrive"` and no model hints. A user who picks "Other" gets a launcher that works on any Android unit and simply hides controls that have no command (see F-03).

*Region catalog* (`regions/ae.json`) carries what is currently UAE-specific text and units:

```json
{
  "schema": 1,
  "id": "ae",
  "name": "United Arab Emirates",
  "units": { "speed": "kmh", "distance": "km", "temperature": "c", "clock24h": true },
  "defaultLatLon": [25.2048, 55.2708],
  "navigation": {
    "mapHint": { "string": "map_hint_ae", "obfName": "United-arab-emirates_asia.obf" }
  },
  "extras": []
}
```

*Resolved profile.* At the end of the selector the launcher writes one JSON blob to `SharedPreferences` (`vehicle_profile`) and everything reads it through a new `VehicleProfile` object (immutable, loaded once). It holds ids (`makeId`, `modelId`, `headUnitId`, `regionId`) plus the resolved values, plus the user's overrides and the results of the Test screen:

```json
{
  "schema": 1,
  "makeId": "hyundai", "modelId": "santa-fe-dm", "headUnitId": "allwinner-t3l", "regionId": "ae",
  "displayName": "SANTA FE · IX45",
  "units": { "speed": "kmh", "distance": "km", "temperature": "c" },
  "btNameHints": ["SantafemR"],
  "capabilities": { "radioTune": "ok", "mediaPlay": "ok", "recents": "failed", "btStateRead": "ok" },
  "overrides": { "carplayPackage": null, "navigationPackage": null },
  "setupCompleted": true
}
```

*Dispatcher.* A single `VendorCommands` class (name indicative) turns a logical command into the listed intents and sends only those. The scattered `sendBroadcast` blocks in `MainActivity` (radio, recents, kill) and the connect broadcasts in `BluetoothWatcher` collapse into calls like `vendorCommands.send(RADIO_TUNE, freqKhz)`. Commands whose capability is `failed` are hidden in the UI rather than shown as dead buttons.

**Effort.** M. Catalog schema, loader, validator and replacing the strings is straightforward; the careful work is collapsing the existing shotgun broadcasts without losing coverage on the owner's own unit, which F-03 provides the evidence for.

**Dependencies.** Resolves Audit 1 P-72 to P-78 and P-80/P-81 in full (P-79 is a layout issue outside this audit): it is the data layer for removing the Santa Fe/UAE/shotgun assumptions. F-02 and F-03 are its UI. Audit 2 onboarding step "vehicle & head unit".

**Risks.**
- Profile bloat and unverified entries giving false confidence. Mitigation: the `verified` field is shown in the confirm screen ("Community-verified" / "Not yet verified on a real car") and unverified units default to the Test screen being offered.
- Schema churn. Mitigation: `schema` integer on every file and in the stored profile; the loader ignores unknown fields and refuses newer schemas with a clear message; a migration step handles older stored profiles.
- The factory PIN (`123456`) currently shown in a dialog is a head-unit-specific fact, not a vehicle fact; it moves to `factoryPinHint` and is shown only for units where it is documented.
- Command actions copied from forums might be wrong or intrusive (for example `com.microntek.kill`). Mitigation: reviewer checklist in `CONTRIBUTING`; the Test screen is the proof.

**Screen.** None itself; consumed by Home (header), Phone/Connect (BT name, pairing text), Maps (map hint), Audio (radio and media commands), Settings (change vehicle), first-run flow.

**Verify in the car.** Every command list entry for the owner's T3L unit, via F-03.

---

### F-02 First-run vehicle and head-unit selector

**User value.** On the very first launch the driver says what car and what stereo they have, in at most three taps, and the launcher stops pretending to be a Santa Fe. Everything is skippable, and "Other / not listed" always yields a working launcher.

**How it works.**

Position in the first-run flow (Audit 2 order): language/units, **vehicle and head unit**, driver phone, apps, permissions (with a reason each). F-02 is the second step. It opens before the main dashboard and nothing else is shown until it is completed or skipped; it is a full-screen `Fragment`/layout inside `MainActivity` (no new Activity needed, which also avoids a launcher-as-HOME double-launch, see `AGENT_HANDOFF.md` section 4C).

Screens (1024x600, 64 dp tiles, no keyboard needed):

1. **Make.** A grid of make tiles, most-installed first, with "Other / not listed" and "Skip for now" always present. Tap 1.
2. **Model.** Models of that make as a list (name plus years), "Not listed" last. Tap 2.
3. **Head unit.** Four family tiles: Allwinner T3L, Microntek, SYU/Joying, "Other / unknown" (the catalog may list more). The family suggested by the model (`suggestedHeadUnits`) or by auto-detection (`detect.buildProps`/`packagesPresent`, matched against `Build.MANUFACTURER`/`Build.MODEL`/`Build.BOARD`, `android.os.SystemProperties` read by reflection as `BluetoothWatcher` already does, and installed packages) is pre-selected. When detection is confident (single match with weight above a threshold) this screen is skipped and its result shown on the confirm card. Tap 3 otherwise.
4. **Confirm footer** (part of screen 3, see tap count below): display name, head unit, a verified badge, the Bluetooth name hint ("your phone will see the unit as: ..." with a Change button), and "Test my unit" (F-03, optional).

Tap count: three. Make (1), Model (2), then the head-unit screen, which has the suggested family pre-selected and a primary Continue button that writes the profile (3). The confirm summary (display name, verified badge, Bluetooth name hint with a Change button, optional "Test my unit") is the footer of that head-unit screen, not a fourth screen. When auto-detection is confident the head-unit tiles are replaced by that summary alone, so the flow is still three taps. Skipping is one tap ("Skip for now" on the first screen) and writes the `_generic` vehicle and head unit.

Units and region are not asked here: they are Audit 2's first step ("language/units"). That step passes `regionId` and the unit choices in; the selector only reads them. If the driver arrives here without having made that choice, the region defaults to the device locale's country (`Locale.getDefault().getCountry()`), falling back to `default`.

What is skippable: all of it. Make, model, head unit each have "Other / not listed". Skipping never blocks CarPlay launch (the rule in `AGENT_HANDOFF.md` section 7.3: never block the CarPlay launch). A skipped setup keeps `setupCompleted=false` and Home shows a one-line, dismissible reminder card ("Finish vehicle setup") rather than a modal.

Writes the Vehicle Profile from F-01: `displayName` (replaces the hard-coded header `SANTA FE · IX45`; a model with no `headerName` falls back to "Make Model" and Generic falls back to "APEXDRIVE"), `btNameHints` (replaces `SantafemR` in strings and in the `BluetoothWatcher` pairing message; also editable, because the Bluetooth name belongs to the head unit and the owner can rename it, it is not a property of the car), the list of vendor commands from the head-unit catalog, default CarPlay/navigation app suggestions (still verified against installed packages, never assumed present), and the region's map hint (replaces the UAE-specific dialog text).

Changing it later: Settings has a "Vehicle & head unit" row showing the current profile. Tapping it re-runs the same screens. Changing the head unit offers to re-run the Test (F-03). A "Reset vehicle profile" action returns to the Generic profile without touching other settings.

**An unlisted car.** "Other / not listed" at Make or Model: the driver may optionally type a display name (the head unit's on-screen keyboard; the field is optional and can be left empty). The head-unit step still runs, because commands depend on the stereo, not on the car. The confirm card shows a "Not in our list yet" note with a "Share my setup" button that writes a prefilled profile stub and the Test-my-unit results to USB (F-08's writer) and shows the project's GitHub new-issue URL as plain text (no QR library, no network call). Nothing is sent automatically; the launcher is frequently offline and the profile may contain the unit's Bluetooth name.

**Effort.** M. The UI is simple lists; head-unit detection and the confirm logic are a little work; most effort is shared with F-01.

**Dependencies.** F-01 (data). Audit 2 first-run onboarding (order and shell; F-07). F-03 for the optional test. Audit 1 P-72, P-75, P-80.

**Risks.**
- Wrong head-unit pick leads to commands that do nothing. Mitigation: pre-selection, the verified badge, F-03, and the capability gating of buttons.
- First-run double-launch or lost state: the selector must survive `recreate()` and a process kill mid-flow (store the draft in `onSaveInstanceState` and persist after each tap).
- The existing user base (this owner's unit) upgrades from a version with no profile. Mitigation: if `vehicle_profile` is absent and the Santa Fe header/BT name preference exists, migrate silently to `hyundai/santa-fe-dm` + `allwinner-t3l` and mark setup complete; only new installs see the flow.

**Screen.** First-run flow; entry in Settings.

**Verify in the car.** Auto-detection fields (`Build.*`, board properties, installed vendor packages) on the owner's T3L, so that the catalog's `detect` block matches reality.

---

### F-03 "Test my unit" capability screen

**User value.** The driver (parked) learns which controls their head unit actually responds to, the launcher hides the ones that do not, and a contributor gets a report that proves a head-unit entry works. This is what turns "forum folklore" broadcasts into verified ones.

**How it works.**

Reached from the confirm card (F-02) and from Settings > Vehicle & head unit > Test my unit. One command per row, one at a time (never a burst, the opposite of today's shotgun):

| Test | What it does | Result source |
| --- | --- | --- |
| Radio on / tune to a station / off | `radioPower`, `radioTune`, `radioStop` via the profile's action list | Driver taps "It worked" / "Nothing happened" |
| Media play/pause/next | `AudioManager.dispatchMediaKeyEvent` (API 19+) and the profile's key actions | Driver |
| Recents | profile action | Driver |
| Factory settings | profile intent (opens another screen, so offered last; marked "will leave the launcher") | Driver |
| Bluetooth state | Reads the profile's `settingsKeys` and `systemProps` and shows the raw values next to the Android Bluetooth state | Automatic: shows what each channel returned and whether it matches the driver-confirmed "my phone is connected" |
| CarPlay app launch | the profile's `carplayLaunch` list | Driver |

Each row has a Send button and, after it, "It worked" / "No effect" / "Skip". Results persist in the profile under `capabilities` (`ok`/`failed`/`untested`). `failed` hides that control in the UI (for example no radio tuner on Audio when `radioTune` failed), and a hidden control is always reachable again from the Test screen. Broadcast sends are fire-and-forget (there is no acknowledgement from `sendBroadcast`), which is why the driver confirms the effect.

Safety: tests run only while parked. The screen reads speed from `GpsTelemetryManager` and, above ~3 km/h, locks with "Test only when parked". This also avoids the legal issue of operating a screen while driving.

Output: "Copy report" writes a JSON/Markdown report to USB (F-08 writer) with model, head unit, `Build.*`, which commands passed, and the raw Bluetooth channel values. The text explains how to attach it to a GitHub issue to promote a head-unit entry to `community` verified.

**Effort.** M. A list screen and a result store; the value is in having F-01's dispatcher in place first.

**Dependencies.** F-01. F-08 file writer for the report. Audit 1 P-76, P-77, P-80 for why the shotgun must go.

**Risks.**
- A test broadcast can have side effects on a poorly understood unit (for example a "power" action that switches the radio hardware off). Mitigation: a short warning, no automatic tests, the Factory screen not tested by default.
- "It worked" is subjective and the effect may be delayed. Mitigation: wording ("did the radio start within 5 seconds?").
- Results can go stale after a head-unit firmware update; the Settings entry shows the date tested and offers retest.

**Screen.** Settings (and first-run confirm card). Results gate Audio (radio), Home (recents), Phone/Connect (BT).

**Verify in the car.** This entire feature *is* the verification tool; its first run is on the owner's T3L, and its output seeds the T3L catalog entry.

---

### F-04 Reliable phone detection and optional CarPlay auto-launch

**User value.** When the driver's phone connects, ApexDrive knows (and says so truthfully), and, only if the driver turned it on, opens the CarPlay/Android Auto app for them. Today's detection can say "connected" falsely or miss a connection that happened before the launcher started.

**How it works.**

1. **Detection signals** (merged into one state `NOT_CONNECTED / CONNECTING / CONNECTED_VERIFIED / CONNECTED_UNVERIFIED`), exactly the existing multi-channel idea, but driven by the profile instead of fixed lists: `BluetoothA2dp`/`BluetoothHeadset` profile proxies (`BluetoothAdapter.getProfileProxy`, API 11+), `BluetoothDevice.ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` (API 1), `AudioManager.isBluetoothA2dpOn()`/`isBluetoothScoOn()`, and the head-unit's `settingsKeys`/`systemProps` from the profile. Only the channels listed for the selected head unit are polled, which also reduces boot-time work. The existing bugs in this engine (boot race, false "connected", duplicate load) are Audit 1's (P-04 false positives, P-05 proxies not created when Bluetooth is off at start, P-06 dead auto-connect, P-07 silent enable/createBond, P-46 main-thread polling, P-59 hidden-API reflection); this feature depends on them being fixed and does not duplicate them.
2. **Driver phone.** The selected driver device (`KEY_SELECTED_DRIVER_DEVICE`, set in the driver-phone step of the first-run flow) is the identity everything keys on. A connection by a different paired device is shown as "Other device" and never auto-launches.
3. **Auto-launch policy** (Settings > Phone/Connect, **off by default**): `Off` / `Ask` (a 10-second banner "Open CarPlay? [Open] [Dismiss]") / `Auto`. `Auto` requires: the driver enabled it; the connected device is the selected driver phone; detection is `CONNECTED_VERIFIED`, or `CONNECTED_UNVERIFIED` only with an explicit sub-toggle; the connection has been stable for N seconds (default 5, so a flapping link does not launch); it has not already launched in this ignition cycle (a cool-down of several minutes, because the launcher cannot reliably know about ignition); the CarPlay app is not already foreground (`ActivityManager.getRunningTasks` is deprecated from API 21 and returns only own tasks, so use the CarPlay launch result and a "launched at" timestamp instead); and the user has not tapped anything in the first ~3 seconds after the connect event (a tap means "I'm doing something else").
4. **Launch.** The profile's `carplayLaunch` action list, falling back to the installed package from the profile's `defaultApps.carplay` via `PackageManager.getLaunchIntentForPackage`. The existing explicit ZLink intent (`zjinnova.android.intent.action.ZLINK_MAIN` with `page`/`feature`) is data in the T3L entry, not code.
5. **API 29+ note.** Android 10 introduced restrictions on starting activities from the background that apply regardless of `targetSdkVersion`; a launcher that is currently the visible HOME app is normally in the foreground at connect time and is fine, but a launch from a background service is not guaranteed on Android 10+ ROMs. This hardware is API 21-28 so it is not a blocker today. Alternatives if needed on newer units: a high-priority notification with a full-screen intent (`USE_FULL_SCREEN_INTENT`, with its own permission rules from API 34) or the `SYSTEM_ALERT_WINDOW` exemption.

**Effort.** M. The engine exists; the work is the state machine, the policy and the per-device scoping.

**Dependencies.** Audit 1 P-04, P-05, P-06 and P-59 (and P-57 for ACC sleep/wake, which decides the cold-boot ordering) must be fixed first; F-01 (profile channels and launch lists); the driver-phone step of the first-run flow (Audit 2); F-23 (per-driver) later reuses the per-device key.

**Risks.**
- Surprising the driver: launching an app they did not ask for is intrusive. Mitigation: off by default, visible Ask mode, once per cycle.
- Boot ordering: the phone often connects *before* the launcher's UI is up. The state must be computed on start, not only from events.
- Unreliable "connected" on MCU Bluetooth: auto-launch with `CONNECTED_UNVERIFIED` could launch with no phone. Hence the stricter default.

**Screen.** Phone (Connect) for the toggle and status; Home shows the connection state; first-run driver-phone step offers to enable it.

**Verify in the car.** Which signals actually fire on the T3L for (a) a phone that connects before the launcher starts, (b) a phone connecting while the launcher is open, (c) a manual connect from the head unit's own Bluetooth app; and whether the CarPlay app can be started over a running Bluetooth audio session without dropping it.

---

### F-05 Real now-playing from MediaSession, with album art

**User value.** The Audio and Home screens show what is really playing, with cover art, so the launcher looks like a car stereo instead of a text label.

**How it works.**

Today `MediaSyncService` (a `NotificationListenerService`, needed so `MediaSessionManager.getActiveSessions(ComponentName)` is allowed, API 21+) holds the active `MediaController`/`MediaMetadata` but only reads `METADATA_KEY_TITLE` and `METADATA_KEY_ARTIST`, then sends an `ACTION_MEDIA_UPDATED` broadcast with strings.

- **Art keys** (all API 21+): `MediaMetadata.METADATA_KEY_ALBUM_ART`, `METADATA_KEY_ART` (Bitmaps via `getBitmap`), and `METADATA_KEY_ALBUM_ART_URI`, `METADATA_KEY_ART_URI`, `METADATA_KEY_DISPLAY_ICON_URI` (strings; `content://` or `file://` readable through `ContentResolver.openInputStream`; do not fetch `http(s)` URIs without a deliberate network feature). Order: `ALBUM_ART`, `ART`, `DISPLAY_ICON`, then the URI keys.
- **Do not put the Bitmap in the broadcast.** An `Intent` extra goes through Binder, whose per-transaction limit is about 1 MB, shared by all transactions in progress, and a large cover can throw `TransactionTooLargeException` or silently drop the update. Decode and **downscale in the service first** (for example to 256x256: `BitmapFactory` with `inSampleSize` from a bounds-only pass for URIs, `Bitmap.createScaledBitmap` for ready bitmaps), then hand it to the UI through an **in-process** holder: a `NowPlayingStore` singleton with a volatile snapshot (`title`, `artist`, `album`, `art`, `isPlaying`, `sourcePackage`, a monotonically increasing version) and a listener interface. The service and the Activity run in the same process (no `android:process` in the manifest), so no IPC is involved. The existing text broadcast may remain for compatibility but is no longer the data path. A 256x256 ARGB_8888 bitmap is 256 KB; keep exactly one current art and recycle the previous.
- **Fallback chain** when a session has no art: the large icon of the media notification (the listener already sees posted notifications; `Notification.EXTRA_LARGE_ICON` bitmap on API 19+, `Notification.getLargeIcon()` as an `Icon` from API 23), then the source app's launcher icon via `controller.getPackageName()` (API 21) and `PackageManager.getApplicationIcon`, then a generic placeholder.
- **Sources with no session or no art.** FM radio and any source that only emits a vendor broadcast (the existing `com.microntek.bt.music`, `com.goc.bluetooth.avrcp`, `com.ts.bt.track` receivers) have no art: show the source icon plus text (for radio: the frequency large, the station name if the broadcast has one). The vendor list of broadcast actions to listen for comes from the head-unit profile instead of a fixed list.
- **CarPlay/ZLink and Bluetooth AVRCP.** Whether art is available depends on whether the dongle app or the unit's Bluetooth app publishes a `MediaSession` (then everything above works) or only a vendor broadcast (title/artist at best). AVRCP 1.4 cover art exists in the protocol but head-unit MCUs usually forward only text. This must be measured, not assumed (below).
- **Controls and seek.** Transport buttons keep using the controller's `TransportControls` (API 21) when a session exists and fall back to the profile's media-key commands otherwise. Progress uses `PlaybackState.getPosition()` extrapolated with `getPlaybackSpeed()` and `getLastPositionUpdateTime()`.
- **API 31+/33+.** `getActiveSessions` still requires notification-listener access. On Android 13 (API 33) a sideloaded APK cannot be granted notification access until the user allows "restricted settings" in App info; this matters for a USB-installed launcher on newer units and must be in the permissions step's copy (Audit 2). Android 13 also adds a media-notification-based path; none of this changes the code for the API 21-28 target.

**Effort.** M. Art decode and holder are small; the fallback chain and race handling (art arriving after metadata, session changes) are the care points.

**Dependencies.** Audit 1 P-13 (album art), P-14 (notification access UI), P-11 and P-12 (duplicate callbacks, placeholder overwrite), P-58 (listener lifecycle) and P-40 (metadata broadcast spoofing, another reason to move the data path in-process). F-01 for the vendor action list. Notification access granted (the permissions step).

**Risks.**
- Memory pressure on a 1 GB unit: bounded by one 256 KB bitmap and off-main-thread decode.
- Wrong session chosen when several are active (ZLink plus Bluetooth plus a leftover music player). Needs a defined priority: playing session first, then most recently changed.
- Stale art after skipping a track whose new art loads later: version counter and "clear art on title change".
- Sideloaded notification access on API 33+ as above.

**Screen.** Audio (full art and controls), Home (compact tile).

**Verify in the car.** For each of Spotify/local player on the unit, the Bluetooth music source, the ZLink CarPlay app and FM radio: does a `MediaSession` appear in `getActiveSessions`, and does it carry art or only text. Record this in the F-03 report so the catalog can state per head unit which sources have art.

---

### F-06 Automatic Day/Night theme

**User value.** The cockpit palette is readable in bright sun and not blinding at night, without the driver touching it, and tunnels do not flip it back and forth.

**How it works.**

1. **Three modes in Settings: Auto (default), Day, Night.** A manual Day or Night **always wins** over everything below.
2. **Auto decision order** (highest priority first):
   1. Head-unit illumination signal, only if the profile's `illumination` block exists *and* the driver enabled "Follow headlights/illumination" in Settings. Vendor-specific (a `Settings.System` key or a broadcast from the MCU when the lights or backlight dimmer change); the catalog stores the key/action, and nothing is assumed. See Verify below.
   2. **Sunrise/sunset computed offline** from the last GPS fix. The NOAA solar-position equations (or the equivalent simplified sunrise equation) need only latitude, longitude, the date and the time zone, and run in microseconds with no network. Night begins at civil twilight end or sunset (choose sunset plus a few minutes), day begins at sunrise. The location comes, in this order, from the current `GpsTelemetryManager` fix, then the last saved location (persist the last fix to `SharedPreferences` whenever the position moves more than a few kilometres), then the region's `defaultLatLon` from F-01, and if there is none:
   3. **Clock fallback**: Night from 18:00 to 06:00.
   Polar summer/winter (the equation returns no sunrise/sunset) also falls to the clock rule.
3. **Hysteresis: 30 seconds.** A change of the computed state must hold for 30 s before applying, so a tunnel (GPS lost, or illumination briefly on) does not flip the theme. In a long tunnel the illumination signal could still hold past 30 s; the sun-based path is immune because it uses the last fix; the setting "Follow headlights" is therefore opt-in and documented.
4. **Time source.** Head units without a SIM or Wi-Fi often have a wrong system clock. When the system year is obviously wrong (before the app's build year), derive the date and UTC time from `Location.getTime()` (GPS time) and the device time zone offset.
5. **Applying the theme without losing place.** The accent is applied in code today (no Day/Night resources exist). The Day palette needs a full token set (surfaces, text, borders, status colours); the design-system proposal in progress must define Day values for each token. Two implementations are possible: (a) `AppCompatDelegate.setDefaultNightMode` plus `values-night` resources, which **recreates the Activity** on a change, or (b) swapping palette values at runtime and re-tinting views (the approach already used for the accent). Either way, the current tab is saved in `onSaveInstanceState` and restored, and the setup flow, the BT card dialog countdown and any playing UI state are preserved; the user must never be thrown back to Home because the sun set. Recommendation: (b) for the launcher (no flash, no recreate, which also avoids the audio-screen flicker); keep (a) only if the Day palette is expressed purely in resources.
6. **Brightness.** The theme is not the backlight; backlight is the head unit's. Do not touch `Settings.System.SCREEN_BRIGHTNESS` (needs `WRITE_SETTINGS`); use `WindowManager.LayoutParams.screenBrightness` on the own window only if a "dim at night" option is added.
7. **API 29+.** Android's own dark theme (`Configuration.UI_MODE_NIGHT_YES`, `AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM`) is available; on newer units "follow system" can be an extra mode, but head-unit ROMs rarely drive it, so it is not the default.

**Effort.** M. The solar calculation is ~50 lines; the bulk is the Day palette and the safe re-theme.

**Dependencies.** The Day tokens from the design system. F-01 (illumination block and `defaultLatLon`). `GpsTelemetryManager` for the fix (Audit 1 GPS lifecycle findings, if any, and P-38 for location consent).

**Risks.**
- Wrong clock or time zone gives wrong day/night. Mitigation: GPS time and the fallbacks above, and the manual override.
- A Day palette that fails contrast at 1024x600 in sunlight; needs an on-device check.
- Recreate during a call or while the settings dialog is open (if approach (a) is used).

**Screen.** All; the control is in Settings.

**Verify in the car.** (1) Whether the T3L exposes an illumination/headlight signal at all and under which key or broadcast name (capture with `adb shell settings list system`, `adb shell dumpsys activity broadcasts` before and after switching the headlights; community contributes the finding to the head-unit catalog). (2) Whether the system clock is correct at cold start with no network. (3) Whether GPS gets a fix in time for Auto to be right within a minute of start.

---

### F-07 First-run setup flow

**User value.** A new install walks the driver through the few things that matter, in a sensible order, then never shows it again; each step can be skipped and resumed.

**How it works.** This feature is the shell; Audit 2 owns the content and order (language/units, vehicle & head unit, driver phone, apps, permissions with a reason each). This audit contributes the mechanics:

- A `SetupState` stored in `SharedPreferences`: current step, per-step status `pending/done/skipped`, and `completed`. It persists after every step so a process kill or `recreate()` resumes in the right place.
- The shell is a single full-screen container inside `MainActivity` with a step indicator and a persistent "Skip this step" button; "Skip all" lands on Home with the Generic profile. Skipped steps surface as one dismissible card on Home ("2 setup steps skipped") and in Settings > Setup, where each can be run later.
- F-02 is step 2 and writes the Vehicle Profile; the permission step requests location (for F-06, F-14 and GPS), notification-listener access for F-05 (with the Android 13 restricted-settings note), and Bluetooth, each with its own sentence.
- Re-running: Settings > Setup > "Run setup again".

**Effort.** S-M.

**Dependencies.** Audit 2 (content and order). F-02, F-04 (driver phone), F-05 (notification access).

**Risks.** Becoming a wall of screens (cap the flow at five short steps and keep every step skippable). Permission prompts differ by Android version; see the permissions step in Audit 2.

**Screen.** First-run flow.

**Verify in the car.** That a cold boot on the unit (where the launcher may start before Bluetooth and GPS are up) does not leave the flow in a half-state.

---

### F-08 Settings export/import to USB

**User value.** Re-flashing the head unit, swapping the unit, or helping a friend with the same car: settings come back in one step.

**How it works.** A single versioned JSON file `apexdrive-settings-v1.json` containing the preferences (accent, units, theme mode, CarPlay and navigation packages, auto-launch policy), the Vehicle Profile from F-01, and later the favourites (F-16), saved destinations (F-13) and driver profiles (F-23). The driver's Bluetooth address and any contact numbers are included only if the driver ticks "Include personal data" (default off), because the file lives on a USB stick.

Writing and reading a file on removable storage on a head unit is the hard part:
1. Prefer the Storage Access Framework (`Intent.ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`, API 19+), no storage permission needed; but many head-unit ROMs ship without a DocumentsUI, so check `resolveActivity` first.
2. Fallback: enumerate removable volumes with `getExternalFilesDirs(null)` (API 19+; returns an app-specific directory per volume, writable without a permission) and, for the default `targetSdk 28` path, scan `/storage/*` for mounted USB volumes with `WRITE_EXTERNAL_STORAGE`. The USB path varies by vendor (`/storage/usbotg`, `/mnt/usb_storage`, `/storage/<uuid>`); a "choose the stick" list shows what was found.
3. Import validates `schema` (refuses newer), shows a diff summary (what will change), and applies atomically.

API 30+ scoped storage makes path-scanning unavailable and SAF the main route; API 21-28 allows both.

**Effort.** S-M (M if the USB scanning needs several vendor paths).

**Dependencies.** F-01 for the profile section. Audit 2 for where Export/Import sits in Settings.

**Risks.** Privacy of the file (above); importing a file for a different head unit (warn, offer "keep my current head-unit profile"); USB volumes not mounting at all on some units.

**Screen.** Settings.

**Verify in the car.** Whether a USB stick is visible to the app, at which path, and whether the SAF picker exists on the T3L ROM.

---

### F-09 In-app update check (GitHub Releases)

**User value.** The driver is told a newer ApexDrive exists instead of never finding out; for open source this is how bug fixes reach cars.

**How it works.** `GET https://api.github.com/repos/yvs1n/ApexDrive/releases/latest` over `HttpsURLConnection` (no library), parse `tag_name`, `html_url`, `body` and the APK asset `browser_download_url` with `org.json`. Compare against `BuildConfig.VERSION_NAME` using a simple semantic-version comparison. Run at most once a day and only when connected (`ConnectivityManager`, `ACCESS_NETWORK_STATE` already declared; `INTERNET` too). Unauthenticated GitHub API calls are rate-limited (60 per hour per IP), which is plenty at one a day; send a `User-Agent` and use `If-None-Match` (ETag) to be polite.

Delivery: many head units have no internet, so the primary path is "download on a phone, copy to USB": the update card shows the version, the changelog text, and the release URL. Optional direct path: `DownloadManager` plus an `ACTION_VIEW` install intent for the APK; on API 24+ the file must be shared through a `FileProvider` and, from API 26, the app needs `REQUEST_INSTALL_PACKAGES`; on API 21-23 a `file://` URI works. Verify the download against the release's SHA-256 if the release notes publish one (a small convention to adopt in the release workflow). Nothing is installed without the driver's tap, and the check is a Settings toggle (on by default but disclosed in first run).

**Effort.** S for check and notify; M with in-app install.

**Dependencies.** A release process that tags versions and attaches an APK (the CI currently produces an artifact on push, not a Release; the release workflow is part of v1.0). Audit 1 release/signing findings (no ID cited here; see Audit 1)..

**Risks.**
- Debug-signed APKs: an update must be signed with the same key as the installed one or Android refuses it. Today CI builds `app-debug.apk`; releases must use a stable signing key (a release-engineering item for Audit 1).
- TLS on old ROMs: API 21 supports TLS 1.2 but an old CA store or a wrong clock can fail the handshake; the check must fail silently and quietly.
- Trust: the check discloses the IP address to GitHub; state it in Settings.

**Screen.** Settings (and a small badge on Home).

**Verify in the car.** Whether the unit has internet at all and whether HTTPS to `api.github.com` succeeds given its clock and CA store.

---

### F-10 Crash and log export to USB

**User value.** When the launcher misbehaves in a car, the driver or a contributor can hand over a log instead of "it just crashed", which is the difference between a fixable issue and a lost one in an open-source project.

**How it works.** A `Thread.setDefaultUncaughtExceptionHandler` that writes the stack trace, app version, `Build.*`, the profile ids (no personal data) and the last ~200 lines of the app's own log to a rotating file in internal storage (a few files, size-capped), then rethrows to the previous handler. For ordinary logs, `Runtime.exec("logcat -d -t 500")` returns only the app's own process lines on API 16+ (no `READ_LOGS` permission needed; the app cannot read other apps' logs on those versions), enough for support. Export reuses F-08's USB writer; "Copy report" shows a short path and a one-line GitHub new-issue hint. A "Share on your phone" route is not possible without a network, so USB is the path. The log must be scrubbed: no device addresses or contact numbers unless "include personal data" is ticked.

**Effort.** S.

**Dependencies.** F-08 writer. P-73 (personal data in the codebase and logs) and P-54 (crash loop as HOME, which the crash flag in this feature also helps with).

**Risks.** Logs may contain the driver's Bluetooth device names; scrub or tick-gate. Handler loops on crash-on-startup: write once and set a "last crash" flag so Settings can show "ApexDrive crashed last time, export log?".

**Screen.** Settings.

**Verify in the car.** That a deliberate crash writes the file on the unit's flash and that the USB export finds the stick (shared with F-08).

---

## Tier 2 — Daily usefulness

The loop works; now the launcher earns a place on the dashboard every drive.

### F-11 OsmAnd next-turn on Home via its AIDL API

**User value.** The next manoeuvre (arrow, street, distance, ETA) appears on Home and Maps while OsmAnd is in the background or in another window, so the driver does not have to keep the navigation app in front.

**How it works.** OsmAnd (`net.osmand.plus` / `net.osmand`) exposes a bound-service AIDL API, `IOsmAndAidlInterface` (with a callback interface `IOsmAndAidlCallback`), published in the OsmAnd-API project. ApexDrive copies the `.aidl` files into `app/src/main/aidl/net/osmand/aidl/` (so the package names match), enables AIDL in Gradle (`buildFeatures { aidl true }`), and binds with `bindService` using an explicit `Intent` whose package is the installed OsmAnd and whose action is the one the API documents for its service (`net.osmand.aidl.OsmandAidlServiceV2` at the time of writing; confirm against the OsmAnd-API sources when implementing). Navigation updates are requested by registering a callback for navigation updates and receive directional information (distance to the next turn, turn type, left-hand/right-hand side) and route totals (time/distance left). The exact method and field names differ by OsmAnd version (the app's own dialog already mentions 4.5.10), so a thin `OsmAndBridge` wrapper isolates them, and Home shows nothing if binding fails. The AIDL files are tiny interface definitions, not a UI library.

A fallback with no AIDL: none that is reliable. OsmAnd's `geo:` intent (already used for search) starts navigation but does not report it back, so the feature simply does not appear when binding fails.

API 30+ package visibility: on Android 11+ binding to another package needs `<queries>` in the manifest (or `QUERY_ALL_PACKAGES`, which the app already holds). Android 14+ adds foreground-service type constraints for long-running services; the bound connection itself is fine.

**Effort.** M.

**Dependencies.** OsmAnd installed and the AIDL service available; F-01 (navigation package from the profile, region map hint)..

**Risks.** OsmAnd API drift between versions; OsmAnd's own power-saving killing the bound service; the head unit's low RAM killing OsmAnd in the background (Android will; a head unit with 1 GB may not keep both alive). Treat as optional and degrade quietly.

**Screen.** Home (compact turn card) and Maps (larger card plus the existing "Launch" button).

**Verify in the car.** That OsmAnd's service binds on the unit, that updates keep arriving with OsmAnd in the background, and the update rate; memory headroom with OsmAnd, the CarPlay app and ApexDrive together.

---

### F-12 Speed-limit and camera alerts

**User value.** A warning when over the limit and a heads-up before a fixed speed camera or hazard.

**How it works.** Two separate sources, independent of each other:
1. **Speed limit.** OsmAnd already has a speed-limit widget; the cheapest correct path is to let OsmAnd show it and have ApexDrive only highlight the speed number when it exceeds a limit that OsmAnd reports, if the AIDL API of the installed version exposes it (verify when implementing; do not assume). Otherwise offer a manual "alert me above N km/h" threshold (S-effort, no data). Offline road-limit data in the launcher would mean bundling or parsing OSM extracts, which is out of scope.
2. **Camera alerts from a bundled regional file.** `assets/profiles/regions/<id>-cameras.csv` (`lat,lon,type,limit,heading`) contributed by the community per region. A lightweight index (a grid keyed by 0.1° cell) is loaded at start; on each GPS fix (`GpsTelemetryManager`, already smoothing speed and heading) look up the neighbouring cells and alert within a distance that scales with speed (for example 8 seconds of travel), only for cameras whose bearing is within about 30° of the vehicle heading. A tone through `ToneGenerator` or `SoundPool` plus a visual banner on Home; audio focus requested with `AudioManager.requestAudioFocus` (API 8+) using a transient-may-duck request so music ducks rather than stops.

**Legality.** Camera alerts are restricted or banned in some countries. The region catalog carries `extras.cameraAlerts: "allowed" | "restricted" | "forbidden"`, the feature is off by default, hidden where `forbidden`, and the first-run text never implies it works everywhere. Treat this as a hard requirement for open-source distribution.

**Effort.** M (camera alerts), S (manual threshold).

**Dependencies.** F-01 region catalog; GPS lifecycle (Audit 1 GPS findings; P-38 location consent); F-11 if OsmAnd reports limits.

**Risks.** Stale data causing false alerts; legal exposure per country (above); GPS jitter near the camera; alert fatigue.

**Screen.** Home (speed tile and banner); Settings for the toggle and threshold.

**Verify in the car.** GPS update rate and accuracy at speed on the T3L; audio ducking behaviour across the head unit's audio paths (the MCU may mix its own sources).

---

### F-13 Saved destinations

**User value.** Home, Work and a few favourites one tap away, handed to the navigation app, with no typing at the dashboard.

**How it works.** A small list (name, lat/lon, optional address text) stored in `SharedPreferences` JSON (or a tiny SQLite table shared with F-14/F-15), created from (a) the current location ("Save here", which also works for parking), (b) a typed place name resolved through the navigation app, not through a geocoder, to avoid needing a network service, and (c) import via F-08. Navigation hand-off uses the `geo:lat,lon` intent already used for OsmAnd search, targeted to the profile's navigation package (`Intent.setPackage`). Home shows the first two as large tiles; Maps shows the full list.

**Effort.** S.

**Dependencies.** F-01 (navigation package), F-08 (portability).

**Risks.** The `geo:` intent behaves differently across navigation apps (some start navigation, some only show the point); the label shown on the tile must say what will happen ("Show on map" vs "Navigate") per app, tested per package.

**Screen.** Maps and Home.

**Verify in the car.** `geo:` hand-off in the owner's navigation app starting guidance from the head unit.

---

### F-14 Where I parked

**User value.** "Where did I leave the car?" answered by the phone or by the unit at the next start, with a map link.

**How it works.** The launcher cannot rely on a shutdown callback: when ACC is cut the unit can power down in seconds and `ACTION_SHUTDOWN`/`ACTION_SCREEN_OFF` may not be delivered to a launcher, so the design is **continuous checkpointing, not an ignition-off event**. `GpsTelemetryManager` stores the last fix (lat, lon, accuracy, time) to `SharedPreferences` every ~30 s while moving and, when speed has been under ~3 km/h for a few minutes, marks it as "parked here" with a time. At the next start, the launcher shows "Parked at <place/coords> since <time>" until the car has moved. Optionally, the vendor ignition/ACC broadcast from the head-unit profile (if the catalog lists one, verified through F-03) triggers an immediate flush. Display: a card on Home, "Parked at <place or coordinates> since <time>", with a "Navigate to my car" button that hands the saved point to the navigation app through F-13. There is no phone hand-off without a network, so a share link or QR code is out of scope.

Reverse geocoding (`android.location.Geocoder`) needs a backend service that head units lack, so the card shows coordinates and, if the user saved places (F-13), the nearest saved place name.

**Effort.** S.

**Dependencies.** GPS lifecycle fixes (Audit 1 GPS findings; P-38 location consent; P-57 ACC sleep/wake), F-13, optionally F-01 ignition action.

**Risks.** Flash wear from frequent writes (use `apply()` at a modest interval and write only when the position changed); a GPS fix that was stale when ACC went off (store accuracy and age and show "approximate").

**Screen.** Home.

**Verify in the car.** How long after ACC off the launcher process actually stays alive (this decides how late the last checkpoint is); whether the unit keeps GPS powered after ignition off.

---

### F-15 Trip computer with history

**User value.** Distance, driving time, average and maximum speed, and a short history (today, this week, per trip), instead of a single reset-only counter.

**How it works.** `GpsTelemetryManager` already computes speed smoothing and trip distance. Add a trip recorder: a trip starts when speed rises above ~5 km/h and ends after about 5 minutes below ~3 km/h (or on a vendor ignition-off action if the profile has one); on end, one row is written to a small SQLite table via `SQLiteOpenHelper` (id, start, end, distance, moving time, max speed, average speed, optionally start/end coordinates). A screen lists trips with totals; "reset" becomes "start a new trip counter" without deleting history. Distance uses the sum of fix-to-fix distances (`Location.distanceTo`) with a minimum-accuracy and minimum-step filter to avoid creeping while parked.

Units come from the Vehicle Profile (`units.distance`, `units.speed`), not a hard-coded km/h, which also removes any hard-coded unit assumption flagged in Audit 1 (P-75). If the OBD-II feature (F-19) is present, fuel use per trip is added.

**Effort.** M.

**Dependencies.** GPS lifecycle and units fixes (Audit 1 P-75, P-38); F-01 units; F-19 optionally.

**Risks.** GPS drift counting phantom distance; trips split across tunnel gaps (apply a merge window); unbounded table growth (cap at, for example, the last 1000 trips with an export through F-08).

**Screen.** Home (current trip tile) and a Trips list (Settings or Maps).

**Verify in the car.** GPS accuracy at low speed and in underground parking; odometer comparison against the dashboard over a known distance.

---

### F-16 Favourite contacts and quick-dial

**User value.** Call "Mum" with one tap on the head unit without hunting through the vendor Bluetooth app.

**How it works.** On an MCU-Bluetooth head unit, the phone book (if the unit even synchronises it by PBAP) lives in the vendor's app, not in Android's `ContactsContract`. So the reliable design is a **launcher-owned favourites list**: the driver enters up to 8 entries (name, number; optionally a photo from the card art, no contact read permission needed), stored in `SharedPreferences` or the F-13 database. Dialling goes through whatever this unit supports, discovered by F-03: (1) a vendor `dial` command in the head-unit profile if it has one, (2) `Intent.ACTION_CALL` with a `tel:` URI (requires the `CALL_PHONE` runtime permission from API 23; `ACTION_DIAL` needs no permission but opens a dial screen and requires one more tap), and (3) falling back to opening the vendor Bluetooth app. If the unit's HFP is handled by the MCU, option (1) is the one that works; Android's telephony may not exist on a Wi-Fi-only unit.

If the unit does expose contacts (`ContactsContract`), an optional "import favourites" reads `STARRED` contacts with `READ_CONTACTS`. The permission is requested only then, with a reason (Audit 2 permissions pattern).

**Effort.** M (S for the manual list; M because of per-unit dialling).

**Dependencies.** F-01, F-03 (dial capability), F-23 (per-driver favourites), Audit 2 Phone/Connect screen layout.

**Risks.** A dial action that does nothing on a given unit makes the feature dead; hide it when `dial` is `failed` in F-03. Privacy of numbers in exports (F-08 include-personal-data toggle). Driver distraction: contact entry only while parked.

**Screen.** Phone (Connect); Home tile for the top two.

**Verify in the car.** Which of the three dial routes the unit honours during a real HFP call and whether the call audio goes through the car speakers.

---

### F-17 Home widgets

**User value.** The driver chooses what Home shows (clock, a calendar or weather widget from an installed app), as on any launcher.

**How it works.** The launcher hosts `AppWidgetHost` (API 3+) and `AppWidgetManager`. Adding a widget: `AppWidgetHost.allocateAppWidgetId()`, request binding with `AppWidgetManager.bindAppWidgetIdIfAllowed` (API 16+) or the `ACTION_APPWIDGET_BIND` consent intent (system dialogs may be missing on a head-unit ROM; the full permission path `BIND_APPWIDGET` is signature-level on most ROMs), then a configure activity if the widget has one, then `AppWidgetHost.createView`. A restricted "dashboard slots" model fits 1024x600: two or three fixed slots whose sizes are chosen by the design system, not a free-form grid, so touch targets stay at 48-64 dp.

Widgets are only available when the installed apps provide any; a head-unit ROM with few apps may have none, and some ROMs' widget pickers or providers are stripped. This is the lowest-confidence item of tier 2.

**Effort.** L (host plumbing, picker UI, persistence, layout per slot).

**Dependencies.** Design system slot layouts; F-23 if widgets are per driver.

**Risks.** Widget processes consuming memory on a 1 GB unit; widgets drawn with unreadable fonts at car distances; ROM without widget support (check `PackageManager.hasSystemFeature(FEATURE_APP_WIDGETS)`, API 21+).

**Screen.** Home.

**Verify in the car.** Whether `AppWidgetManager.getInstalledProviders()` returns anything useful on the unit and whether binding works without a system consent dialog.

---

### F-18 Split view / glanceable overlay

**User value.** See navigation and music (or the camera) at once.

**How it works.** True multi-window is a platform feature from Android 7.0 (API 24): `ActivityOptions.setLaunchBounds` and `Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT` (API 24) place a second activity beside the first when the ROM enables split-screen or freeform. Many head-unit ROMs on API 21-23, and many on 24+, either lack it or implement their own vendor split screen, so it cannot be a core feature. Plan: (a) on API 24+ units where `Activity.isInMultiWindowMode()` is supported, offer "Open next to this" for the nav app, via a Maps screen action; (b) a **launcher-owned overlay** that works on all versions: a small always-on-top strip with the next turn (F-11) and now-playing (F-05), implemented with a `WindowManager` overlay window (`TYPE_PHONE` on API 21-25, `TYPE_APPLICATION_OVERLAY` from API 26) and `SYSTEM_ALERT_WINDOW` permission, which a user must grant via `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (API 23+). Overlays are touch-sensitive and could cover controls of other apps; keep it small and draggable off.

**Effort.** L (split) / M (overlay).

**Dependencies.** F-11, F-05. Overlay permission step in the permissions list.

**Risks.** ROM overlay restrictions; interfering with the CarPlay app's touch handling; getting in the way of the factory reverse-camera screen (must hide when the reverse camera app is foreground; F-25).

**Screen.** Maps and Home; Settings for the overlay toggle.

**Verify in the car.** Whether the ROM supports multi-window at all, and whether an overlay window appears above the ZLink CarPlay surface.

## Tier 3 — Vehicle integration

The launcher starts to know the car itself. Everything in this tier is more hardware-dependent than tiers 1-2 and ships behind explicit opt-in.

### F-19 OBD-II over a Bluetooth ELM327 adapter

**User value.** Real coolant temperature, RPM, battery voltage, fuel level, engine load and fault codes on Home, read from the car's diagnostic port, independent of the head unit's CAN decoder box.

**How it works.** An ELM327 dongle is a serial device. Over Bluetooth Classic it is an SPP (RFCOMM) endpoint: `BluetoothDevice.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))`, `connect()` on a background thread, then ASCII text over the socket's streams. A minimal session: `ATZ` (reset), `ATE0` (echo off), `ATL0`, `ATS0`, `ATH0`, `ATSP0` (automatic protocol), then Mode 01 PIDs polled round-robin at a modest rate: `010C` RPM = (256*A+B)/4, `010D` speed = A km/h, `0105` coolant = A-40 °C, `0104` engine load = A*100/255 %, `012F` fuel level = A*100/255 % (not all cars support it), `0142` control-module voltage = (256*A+B)/1000 V. Supported PIDs are discovered with `0100`, `0120`, ... (bitmasks). Mode 03 reads stored fault codes and Mode 04 clears them (clear behind a confirmation; never run silently). Every response must be tolerant: `NO DATA`, `SEARCHING...`, `UNABLE TO CONNECT`, `?`, and echoed prompts (`>`) occur.

**The key hardware question.** The head unit's Bluetooth may be an MCU/UART link (`AGENT_HANDOFF.md` section 4A), in which case the Android Bluetooth stack may not be able to open an RFCOMM socket to a third-party device at all (the adapter may be absent or only used for profiles). Alternatives: a **Wi-Fi ELM327** (TCP to the dongle's address, commonly `192.168.0.10:35000`; the unit's Wi-Fi is often busy with the CarPlay wireless bridge, so this may not be possible at the same time); or a **USB** OBD adapter (needs a USB-serial driver, which would mean a third-party library, so it is out of scope). The head-unit profile's `bluetooth.androidStackReliable` (F-01) decides whether the feature is offered.

API notes: `BLUETOOTH`/`BLUETOOTH_ADMIN` are already declared; from API 31 `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` replace them (not needed on API 21-28). From API 23 discovery needs a location permission, already held. Pairing an ELM327 usually uses PIN 1234 or 0000, which the app can try only through the system pairing dialog; a head unit whose settings app owns pairing may need the adapter paired there.

**Effort.** L (parser, PID table, connection lifecycle, reconnect, UI).

**Dependencies.** F-01 (is Android Bluetooth trustworthy on this unit); F-15 (trip fuel); F-21 (maintenance by odometer).

**Risks.**
- Cheap ELM327 clones return wrong or truncated data and can wedge the vehicle's diagnostic bus in rare cases; polling must be conservative, never write anything except Mode 04 on explicit request.
- An always-connected adapter drains the car battery on some vehicles; document unplugging, and disconnect when the screen is off.
- Safety and warranty: never display fault codes in a way that suggests it is safe to drive; keep clearing a deliberate action.
- Bluetooth contention: A2DP/HFP to the phone plus an RFCOMM link to the dongle may overload an MCU or a weak Bluetooth chip.

**Screen.** Home (gauges) and a Diagnostics page in Settings.

**Verify in the car.** Can the head-unit's Android stack open an RFCOMM connection to an ELM327 at all; which protocol the Hyundai uses (ISO 15765-4 CAN is typical on this generation of Santa Fe, but verify) and which PIDs it answers; whether simultaneous phone audio and OBD stay stable.

---

### F-20 CAN-bus data (vehicle data the head unit already has)

**User value.** Door-open warnings, reverse and handbrake state, outside temperature, steering angle, and sometimes vehicle speed or fuel level, with no extra dongle.

**How it works.** Aftermarket units connect to the car's CAN bus through a decoder box ("CANbus: Simple Soft (XP), Hyundai/Kia profile" in the existing factory-settings dialog). The decoded data is held by the vendor's own service; the Android side sees it only if the vendor exposes it through broadcasts, `Settings.System`/properties, or a proprietary service. There is no standard Android API for it. The existing `CarThermometerWatcher` is a small example of this pattern (ambient temperature read from vendor channels). So: the head-unit catalog (F-01) gains a `canbus` section listing, per field, the channel type and name (broadcast action and extra keys, or `Settings.System` key), with a `verified` state like every other entry. The launcher reads only the channels listed for the selected head unit, which replaces the current probe-everything approach.

Contributors discover channels on a PC: `adb shell dumpsys activity broadcasts` while opening a door, `adb shell settings list system` before and after, `adb shell getprop`. F-03's report can include a "watch these keys for changes" mode that lists any `Settings.System` values that change while the driver opens a door, so contributors do not need a PC (reading `Settings.System` needs no permission; arbitrary other apps' broadcasts cannot be enumerated).

**Effort.** M per head-unit family (L overall, mostly community-driven).

**Dependencies.** F-01, F-03; F-19 as the dongle alternative where the vendor does not expose anything. Audit 1 P-77 for the existing thermometer probing.

**Risks.** Fragile: vendor firmware updates change keys; wrong decoding shows false data (a "door open" warning that never clears); anything safety-related must be shown as informational only.

**Screen.** Home (warnings and gauges).

**Verify in the car.** The whole feature: which fields the T3L exposes for the Hyundai profile and under what names.

---

### F-21 Fuel and maintenance log

**User value.** Fill-ups, fuel economy and service reminders (oil, tyres, insurance and registration dates) without a separate app.

**How it works.** Local storage (SQLite via `SQLiteOpenHelper`). A fill-up entry: date, odometer (typed or suggested from the F-15 trip total plus the last known odometer; OBD odometer is not standardised, so do not assume it), litres/gallons and price, "full tank" flag. Fuel economy is computed full-to-full. Service reminders are rules (every N km or N months) evaluated at start and shown as a Home card. Units and currency from the Vehicle Profile/region (F-01). Entry is parked-only (like F-03) and large-target; the primary entry path is realistically voice or phone, which is out of scope, so keep the form to a few numeric fields. Export through F-08.

**Effort.** M.

**Dependencies.** F-01 (units), F-15 (distance), F-08 (backup).

**Risks.** A data-entry feature on a car screen is only useful if entry is very quick; without good defaults it will go unused. No network backup means a re-flash loses the log unless it is exported.

**Screen.** Settings > Vehicle (log), Home (reminder card).

**Verify in the car.** Nothing hardware-specific; test on-screen entry ergonomics while parked.

---

### F-22 Steering-wheel key mapping

**User value.** The wheel buttons do what the driver wants (next track, voice, launch navigation, answer), including on units where the factory mapping is poor.

**How it works.** The unit's MCU turns the resistor-ladder button presses into Android key events (media keys, volume) or into vendor broadcasts, depending on the ROM; the factory-settings screen has a "learn keys" function on many units. A launcher only sees key events while it has focus (`Activity.onKeyDown`/`dispatchKeyEvent`). For global handling, use an `AccessibilityService` that requests `FLAG_REQUEST_FILTER_KEY_EVENTS` (API 18+) and implements `onKeyEvent`, which sees key events regardless of the foreground app and can consume them. The service is enabled by the user in Accessibility settings (and on Android 13 sideloaded apps need "restricted settings" allowed first). A "learn" mode records the next `KeyEvent` (`keyCode`, `scanCode`) and binds it to an action from a list: media controls (through the F-05 controller), launch an app, open CarPlay, voice (F-24), or "nothing". Actions that arrive as vendor broadcasts instead of key events are caught by registering the profile's key-broadcast actions (F-01).

**Effort.** L.

**Dependencies.** F-01/F-03 (which key path the unit uses); the accessibility permission step in Audit 2; F-24.

**Risks.**
- Accessibility services have a heavy privacy implication; keep the service minimal (key events only, `canRetrieveWindowContent=false`) and say so plainly. Some app stores restrict this permission, but this is sideloaded.
- Conflicts with the unit's own key handling (double actions). Mitigation: consume mapped keys, let others through.
- Not every wheel button produces an Android event at all.

**Screen.** Settings > Steering wheel.

**Verify in the car.** Which events each button produces (`keyCode`/`scanCode` or broadcast) on the T3L, and whether the service sees them while CarPlay is in front.

---

### F-23 Per-driver profiles tied to each phone

**User value.** When the second driver's phone connects, the launcher switches to their accent colour, favourites, home destinations and preferred apps.

**How it works.** A driver profile is keyed by the paired phone's Bluetooth address (the existing selected-driver-device concept in `PreferenceHelper`, widened from one value to a list): `{id, name, deviceAddress, accent, favouriteContacts, savedDestinations, carplayPackage, autoLaunchPolicy}`. F-04's detection result selects the active profile; an unknown device offers "Create a profile for this phone" (parked only) or "Use default". Vehicle and head-unit settings are not per driver. F-08 exports the profiles (addresses and numbers only with the personal-data toggle).

**Effort.** M.

**Dependencies.** F-04 (identity), F-13, F-16, F-08, Audit 2 (driver-phone step becomes "add the first driver").

**Risks.** Misidentifying the driver when two phones are in range (then the launcher must wait for the audio-connected one, not merely paired); storing Bluetooth addresses of personal devices; profile sprawl.

**Screen.** Settings > Drivers; Phone (Connect) shows the active driver.

**Verify in the car.** Whether the unit reports which of two phones holds the audio/HFP link, and how fast.

---


## Tier 4 — Polish

### F-24 Voice assistant button

**User value.** One big button (and a wheel key via F-22) to talk to the phone's assistant instead of touching the phone.

**How it works.** Options in order of usefulness: (1) **Bluetooth voice recognition** through the phone: `BluetoothHeadset.startVoiceRecognition(BluetoothDevice)` (API 11+) asks the connected phone to start its assistant over HFP, so the driver talks through the car's microphone and hears the answer on the speakers. It works only if the unit exposes HFP to Android rather than handling it in the MCU; on MCU units the vendor has its own voice button; (2) `Intent.ACTION_VOICE_COMMAND` (API 4) or `Intent.ACTION_ASSIST` to start an assistant installed on the unit, rare on aftermarket ROMs; (3) CarPlay/Android Auto's own voice key, delivered by the dongle app, outside the launcher's control (the profile can name the key if the dongle has one). The button chooses the first that the profile says works and hides if none passed in F-03. No on-device speech recognition is attempted (`SpeechRecognizer` needs a recogniser service the unit usually lacks). API 31+ requires the `BLUETOOTH_CONNECT` permission for the headset call.

**Effort.** S-M.

**Dependencies.** F-01, F-03, F-04, F-22.

**Risks.** Fails silently on units where HFP is MCU-only; microphone permission and routing differences.

**Screen.** Home and Phone (Connect).

**Verify in the car.** Whether `startVoiceRecognition` triggers the phone's assistant and routes the microphone and speaker correctly.

---

### F-25 Dashcam and reverse-camera shortcuts

**User value.** One tap to the dashcam app or the 360° camera view the driver installed.

**How it works.** Shortcuts only. The reverse camera is switched in hardware/firmware by the reverse signal and the launcher must neither intercept nor cover it; it only offers a "Camera" shortcut that launches the unit's camera app package (listed in the profile, for example a vendor camera app) and a user-selectable dashcam package. Where a USB UVC camera is attached, live view needs a UVC library, which is out of scope. The overlay (F-18) hides while the reverse-camera app is foreground.

**Effort.** S.

**Dependencies.** F-01 (package names), Apps screen app picker.

**Risks.** Launching a camera app while reversing could take over a signal the driver relies on; test that the factory reverse switching still takes priority.

**Screen.** Apps and Home tile.

**Verify in the car.** That reverse still preempts the launcher and the shortcut returns cleanly.

---

### F-26 Parked screensaver clock

**User value.** A calm, dim clock (and temperature) when the car is on but idle, rather than a bright dashboard.

**How it works.** After N minutes with speed zero and no touch, the launcher shows a dark full-screen clock inside its own window (not `DreamService`, which needs user setup and ROM support), with the own-window brightness lowered through `WindowManager.LayoutParams.screenBrightness`, moving slightly every minute to avoid burn-in on the panel. A touch or movement dismisses it. The head unit's ACC-off behaviour (sleep or shutdown) is separate and not affected.

**Effort.** S.

**Dependencies.** Design system tokens; the Night palette from F-06.

**Risks.** Hiding useful info (an incoming call must dismiss it); some units keep the screen on regardless of brightness.

**Screen.** Home (idle state).

**Verify in the car.** Whether per-window brightness works on the unit's backlight driver.

---

### F-27 Localization including Arabic and RTL

**User value.** The launcher in the driver's own language; Arabic is a first-class case for the owner's region.

**How it works.** Move every user-visible string out of Java and layouts into `strings.xml` (many labels like `RECENTS`, `LAUNCH ANYWAY` and the dialogs are hard-coded today, which is also an Audit 1 P-75 and Audit 2 topic), add `values-ar` and a few others, declare `android:supportsRtl="true"` (API 17+) in the manifest, and convert `left/right` attributes to `start/end` (`marginStart`, `paddingEnd`, `layout_gravity="start"`) so layouts mirror. Icons with direction (back arrows, next-turn arrows) need `autoMirrored` or per-direction assets; media transport controls conventionally do not mirror. Numbers: choose between Western and Arabic-Indic digits as a setting (`NumberFormat` with a locale). Language is picked in the first run (Audit 2's "language/units" step) and applied through a per-app `Configuration` override (`createConfigurationContext`, API 17+) because API 21-32 has no per-app language setting (API 33 adds `LocaleManager`/`AppCompatDelegate.setApplicationLocales` for an official path). Fonts: verify that the display font in the design system has Arabic glyphs, otherwise fall back to a system font for Arabic.

**Effort.** M (string extraction is the bulk), plus per-language translation.

**Dependencies.** Audit 2 (language step); design system (fonts and mirroring rules).

**Risks.** Layout overflow in long languages at 1024x600; hard-coded unit strings (`KM/H`) in code; contributor translations of safety-relevant text.

**Screen.** All; the picker is in the first-run flow and Settings.

**Verify in the car.** Arabic text shaping and RTL layout with the unit's stock font rendering, and the font's glyph coverage.

---

### F-28 Optional regional extras

**User value.** Local things a driver in one place values and a driver elsewhere never wants.

**How it works.** The region catalog (F-01) gains an `extras` list; each extra is an opt-in module that is off unless its region is selected. Candidates: prayer times and Hijri date for regions that use them (computed offline from the last fix and a calculation-method setting, as F-06 does for the sun; the Hijri date by a tabular algorithm, or `android.icu.util.IslamicCalendar` on API 24+, no network); the camera-alert policy and data from F-12; regional speed units; a local map-hint text (replacing the UAE instructions with data). Rule: an extra never ships on by default and never changes the core layout, so the launcher stays the same product everywhere.

**Effort.** S per extra.

**Dependencies.** F-01, F-06, F-12, F-27.

**Risks.** Maintenance burden and correctness of religious or legal content (prayer time method, camera law): require a named regional maintainer in the catalog file.

**Screen.** Home (optional tile) and Settings.

**Verify in the car.** None beyond location accuracy.


## Release sequence and summary

### How F-01 to F-03 relate to the other audits

- **Audit 1 §G (vehicle and head-unit assumptions, P-72 to P-81).** Audit 1 lists the assumptions as defects (Santa Fe text, `SantafemR`, UAE map text, vendor broadcasts sent to every vendor, probing every vendor's properties). F-01 is the single fix for that whole group: the assumptions become data in a Vehicle Profile and the shotgun becomes a per-head-unit command list. Audit 1 should keep its findings as the defect record and point to F-01 as the remedy; this audit does not repeat them. Reverting any of them without F-01 would leave the launcher correct only for the owner's car.
- **Audit 2 first-run onboarding.** Order: language/units, then vehicle and head unit (F-02), then driver phone, then apps, then permissions with a reason each. F-07 provides the resumable shell; F-02 fills step 2; F-03 is an optional sub-step of step 2 and a permanent entry in Settings. Audit 2 owns copy, layout and the permission wording (including the Android 13 restricted-settings note for notification access and accessibility).
- **The design system.** Day/Night (F-06) needs Day tokens, F-02 needs tile and list components, F-17 needs slot layouts; these are requirements on the design-system proposal rather than work inside this audit.

### Release sequence

Releases assume Audit 1's P0/P1 fixes land first or alongside; this audit does not replace them.

| Release | Contents | Why this grouping |
| --- | --- | --- |
| **v1.0** | Audit 1 P0/P1 fixes; **F-01, F-02, F-03 (basic: send, confirm, hide), F-07**; F-04 (detection only, auto-launch behind a switch, off); F-05 (art with fallbacks); F-06 (Auto/Day/Night, no illumination signal yet); F-08; F-10; a release workflow with a stable signing key | The car-agnostic core: a stranger with a different car and stereo gets a working launcher. Also gives contributors the profile format, the Test report and a log to attach before the first outside PR |
| **v1.1** | F-09 (update check); F-04 auto-launch Ask/Auto modes after car testing; F-13, F-14, F-15; F-16 (manual favourites); F-06 illumination option where verified | Daily usefulness that needs only GPS and local storage |
| **v1.2** | F-11, F-12 (manual threshold and, where legal, regional camera data); F-22; F-27 (Arabic/RTL and string extraction, may start earlier because extraction touches every file) | Features that depend on external apps or accessibility permission, and the language work |
| **v1.3** | F-23; F-20 (first head units verified); F-17; F-26; F-25 | Per-driver and vehicle data once the profile and favourites exist |
| **v2.0** | F-19 (OBD-II), F-21, F-18, F-24, F-28 | Hardware-dependent or large; gated on community reports from F-03 |

Rationale for the order: F-27's string extraction is cheap to do early and expensive to do late, so Audit 2 may pull it into v1.0 as an enabling refactor even if translations ship later. F-08 and F-10 are in v1.0 because every later feature adds data worth backing up and every bug report is cheaper with a log.

### Summary table

Value = benefit to a typical driver (H/M/L); effort S ≈ days, M ≈ 1-2 weeks, L ≈ weeks; HW = depends on head-unit behaviour that must be verified in the car.

| ID | Feature | Tier | Value | Effort | HW | Release |
| --- | --- | --- | --- | --- | --- | --- |
| F-01 | Vehicle Profile data model (JSON assets) | 1 | H | M | Y | v1.0 |
| F-02 | First-run vehicle and head-unit selector | 1 | H | M | Y | v1.0 |
| F-03 | "Test my unit" capability screen | 1 | H | M | Y | v1.0 |
| F-04 | Reliable phone detection and optional CarPlay auto-launch | 1 | H | M | Y | v1.0 / v1.1 |
| F-05 | Real now-playing with album art | 1 | H | M | Y | v1.0 |
| F-06 | Automatic Day/Night | 1 | H | M | Y | v1.0 |
| F-07 | First-run setup flow (shell) | 1 | M | S-M | N | v1.0 |
| F-08 | Settings export/import to USB | 1 | M | S-M | Y | v1.0 |
| F-09 | In-app update check | 1 | M | S-M | Y | v1.1 |
| F-10 | Crash and log export | 1 | M | S | Y | v1.0 |
| F-11 | OsmAnd next-turn (AIDL) | 2 | H | M | Y | v1.2 |
| F-12 | Speed-limit and camera alerts | 2 | M | M | Y | v1.2 |
| F-13 | Saved destinations | 2 | M | S | Y | v1.1 |
| F-14 | Where I parked | 2 | M | S | Y | v1.1 |
| F-15 | Trip computer with history | 2 | M | M | Y | v1.1 |
| F-16 | Favourite contacts and quick-dial | 2 | H | M | Y | v1.1 |
| F-17 | Home widgets | 2 | L | L | Y | v1.3 |
| F-18 | Split view / overlay | 2 | L | L | Y | v2.0 |
| F-19 | OBD-II over Bluetooth ELM327 | 3 | M | L | Y | v2.0 |
| F-20 | CAN-bus data from the head unit | 3 | M | L | Y | v1.3 |
| F-21 | Fuel and maintenance log | 3 | L | M | N | v2.0 |
| F-22 | Steering-wheel key mapping | 3 | H | L | Y | v1.2 |
| F-23 | Per-driver profiles tied to phones | 3 | M | M | Y | v1.3 |
| F-24 | Voice assistant button | 4 | M | S-M | Y | v2.0 |
| F-25 | Dashcam / reverse-camera shortcuts | 4 | L | S | Y | v1.3 |
| F-26 | Parked screensaver clock | 4 | L | S | Y | v1.3 |
| F-27 | Localization incl. Arabic/RTL | 4 | H | M | N | v1.2 (extraction earlier) |
| F-28 | Optional regional extras | 4 | L | S each | N | v2.0 |

### Open questions

1. Audit 1 IDs for GPS lifecycle and release signing are not cited because audit1's final list (81 findings) has no dedicated finding for them; add them if they appear.
2. Which Hyundai/Kia models and head-unit families are worth shipping in the first catalog beyond the owner's own, and who maintains each (a `maintainers` field per catalog file is suggested).
3. Whether the project wants camera alerts at all given the legal variation (F-12), or only the manual threshold.
4. Release signing: who holds the key (F-09, v1.0 release workflow).

