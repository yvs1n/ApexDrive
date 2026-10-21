# Audit 2: UI/UX

Scope: every screen, dialog and flow in ApexDrive, judged for a driver using a 1024×600 head unit. Method: the apple-design-skill review (context, the 8 principles, then five lenses: accessibility, platform conventions, craft, interaction, writing), with the in-car standards that apply instead of Apple platform conventions (Android for Cars UX requirements, Google's cars design system, Apple's CarPlay HIG, NHTSA's visual-manual guidelines).

Companion documents: [01-production-readiness.md](01-production-readiness.md) (bugs; cited here as "Audit 1" by topic) and [03-feature-roadmap.md](03-feature-roadmap.md) (features, F-xx). The target look is [`design/design-system.html`](../design/design-system.html): tokens, components and Android resources. This audit says what each screen should become; the design system says what it looks like.

Hardware facts that matter for sizes: Allwinner T3L, 1024×600, mdpi (1 dp = 1 px). Assuming a 9″ panel, one pixel is 0.193 mm, 1.21× a standard dp. Viewing distance assumed 750 mm. Both are assumptions, marked *verify in the car*.

---

## 1. Summary

**Overall rating: Critical issues.**

**Thesis:** ApexDrive looks like an instrument cluster but behaves like a demo. It is too small to read while driving, shows status it doesn't have, and answers most taps with a toast that vanishes in two seconds.

### Measured numbers

| Measure | Today | Needed | Source |
|---|---|---|---|
| Text sizes in layouts under 20 sp | 114 of 119 `textSize` values | 0 (floor 20, glance text 28) | ISO 15008 at 9″/750 mm; design-system "Type" |
| Text at 13 sp or smaller | 102 of 119 | 0 | same |
| Cap height of a 10 sp label | 1.29 mm = 5.9′ | ≥ 16′ (3.5 mm) | ISO 15008 minimum |
| Font weight | `sans-serif-black` on 75 of 115 `fontFamily` attributes | ≤ Semibold; Black is "less legible" | Google cars design system |
| ALL-CAPS strings | 79 hardcoded caps strings or `textAllCaps` in layouts | 0 | Android for Cars writing guidance |
| Tertiary text `#525866` | 2.60:1 on panel, 2.13:1 on buttons | 4.5:1 | WCAG 1.4.3, Google cars (4.5:1) |
| Secondary text `#8B92A0` | 5.92:1 on panel, 4.65:1 on buttons | 4.5:1 | passes, barely |
| Button fill vs panel | 1.22:1 (border 1.46:1) | 3:1 for the boundary | WCAG 1.4.11 |
| Header pills (`activity_main.xml:29,89,124`) | 38 px tall = 7.3 mm | 12.1 mm (63 px) | Google cars, 76 dp |
| Rail buttons (`activity_main.xml:176`) | 80×64 px = 15.4×12.3 mm | 12.1 mm | **passes** |
| Toasts in `MainActivity.java` | 33 `Toast.makeText` | 0 (in-UI notification) | Android for Cars |
| Timed auto-action | 8 s countdown in the phone dialog (`MainActivity.java:1931`) | none | Apple HIG `accessibility.md`: avoid time-boxed UI |

### Top five problems

1. **Unreadable at a glance.** Almost all text is 10 to 13 sp Black weight in capitals on dark glass.
2. **Fake status.** "PHONE LINK ACTIVE", "ZLINK 5.3 ACTIVE", "USB 2.0 READY", "RADARS · ROAD BUMPS · OFFLINE LANES ACTIVE" never change. A driver can't trust any indicator. (Audit 1, section B.)
3. **Wrong information architecture.** The PHONE screen is a brochure for CarPlay, not a control. The Bluetooth pill launches CarPlay when connected and opens a picker when not. The weather pill opens calibration.
4. **Feedback by toast.** 33 toasts, no persistent state, no way to re-read one.
5. **No driving mode.** Search keyboard, a 15-item picker and pairing are all available at speed.

---

## 2. The eight principles, applied

| Principle | Verdict | Evidence |
|---|---|---|
| Purpose | Fails | Home shows a CarPlay marketing card (`view_dashboard.xml:35-112`) in half the screen. The driver's jobs (route, music, call) are secondary. |
| Agency | Fails | The device dialog counts down 8 s (`MainActivity.java:1931`), calls `adapter.enable()` without asking, and the app requests location at launch (`MainActivity.java:2438`). |
| Responsibility | Fails | Search keyboard, long app lists and pairing at speed. Location sent to three web services with no consent (Audit 1, section C). |
| Familiarity | Mixed | A left rail is right. Labels DASH/PHONE/MAPS/AUDIO/APPS/SETUP are close to the CarPlay and Android Auto pattern but "DASH" and "SETUP" are jargon. |
| Flexibility | Partial | Five accent swatches, but no Day mode, no 24 h clock (`MainActivity.java:1529`), no solid-background or contrast setting. |
| Simplicity | Fails | Start CarPlay is 1 tap on Home but 3 different buttons start it (dashboard card, phone screen, Bluetooth pill) with three different behaviours. |
| Craft | Fails | The "near-black with one acid accent" template, numbered "01 ·" labels on non-sequences (`view_carplay.xml:76,121,166`; `view_audio.xml:21-63`; `view_setup.xml:267-337`), Black weight everywhere. |
| Delight | n/a | There is no moment of care. The design system proposes one: glass bars over the driver's wallpaper. |

---

## 3. Findings

Format: **ID · Severity · Where · What · Why (standard) · Fix**. Severity: Critical (blocks safe use), High, Med, Low. Lenses follow the skill's order. Each fix names the design-system token or component to use.

### 3.1 Accessibility (Critical)

**U-01 · Critical · all layouts · Text far below minimum size.**
What: 114 of 119 `textSize` values are under 20 sp; 17 are 10 sp, 22 are 11 sp, 25 are 12 sp, 38 are 13 sp.
Why: a 10 sp capital is 5.9′ at 9″/750 mm, about a third of ISO 15008's 16′ minimum. Google cars: 24 dp minimum, 32 dp primary.
Fix: adopt the seven text styles (`speed 120`, `display 64`, `title 34`, `glance 28`, `label 22`, `body 22`, `caption 20`). Nothing below 20.

**U-02 · Critical · `colors.xml`, all layouts · Low-contrast text.**
What: tertiary text `#525866` is 2.60:1 on panel and 2.13:1 on buttons; it is used for captions and hints.
Why: WCAG 1.4.3 and Google cars need 4.5:1.
Fix: four label levels only. `label-3` passes 4.5:1 on all surfaces in both modes; `label-4` is for disabled controls only.

**U-03 · High · `bg_elevated_btn.xml`, `bg_panel.xml` · Controls don't read as controls.**
What: button fill `#14FFFFFF` over the panel gives a 1.22:1 fill and 1.46:1 border.
Why: WCAG 1.4.11 asks 3:1 for a control's boundary. In sunlight, a button is invisible.
Fix: buttons use `surface-3` fill with a 64 px height and capsule shape; primary uses the accent fill. Switches carry an outline at 4.5:1.

**U-04 · High · `activity_main.xml:29,89,124` · Header pills 38 px tall (7.3 mm).**
What: RECENTS, weather and Bluetooth pills are tap targets smaller than Google's 12.1 mm.
Fix: move status to non-tappable chips in the status bar; recents moves to a rail action or a long-press-free button of 64 px.

**U-05 · High · `view_setup.xml:42-62` · Accent swatches 56×52 px (10.8×10.0 mm) with no selected indicator.**
Why: below target size, and there is no way to tell which colour is active (`color.md`: don't rely on colour alone).
Fix: remove the picker. One accent (Lagoon); the system provides Day and Night variants. If a choice is wanted later, show 3 options with a check mark, 64 px each.

**U-06 · Med · everywhere · Status conveyed by colour alone.**
What: dots and text colours for connected/GPS states have no icon or word.
Fix: every status chip carries a word ("Connected", "Waiting for GPS") plus a dot; error and caution notifications also carry an icon in the badge.

**U-07 · Med · every view · No focus or pressed states for steering-wheel/D-pad use.**
Fix: every interactive component defines pressed (scale 0.97, `fill-pressed`) and focused (3 px accent ring) states; `android:focusable` and `nextFocus*` set on the rail and content. Needs the car's key events: *verify in the car*.

### 3.2 Platform conventions: in-car standards (High)

**U-08 · High · `activity_main.xml:158-362` · The rail mixes navigation and an action.**
What: five places plus SETUP at the bottom is fine, but RECENTS (an action) sits in the header as a pill, and the Bluetooth pill performs different actions by state.
Why: `tab-bars.md`: navigation, not actions; Android for Cars: predictable, one meaning per control.
Fix: rail = Home, Phone, Maps, Audio, Apps, Settings (one word each, nouns). Recents becomes a quiet icon button in the status bar region at 64 px.

**U-09 · High · `view_dashboard.xml:35-112` · Home spends half its area on the projection card.**
Why: CarPlay HIG: the most important content in the upper half. Android for Cars: 3 taps or fewer.
Fix: Home = Drive tile, Speed tile, Now playing tile, Phone tile (design-system "Screens"). The phone tile holds the one Open CarPlay button.

**U-10 · High · `view_apps.xml:31` · Search is a keyboard EditText at speed.**
Why: NHTSA lock-out: manual text entry; Google cars: hide search while driving.
Fix: parked = search + grid; driving = six favourites, search locked with an explanation (section 4).

**U-11 · High · `MainActivity.java:1931` · An 8-second auto-dismiss countdown.**
Why: Apple HIG `accessibility.md` (minimise time-boxed UI); Android for Cars: don't change content for less than 8 s. It is also dead code today (`showDeviceSelectorDialog(true)` is never called, Audit 1 section A).
Fix: no countdown anywhere. Auto-connecting is a Settings switch the driver enables.

**U-12 · Med · `MainActivity.java:2438` · Location permission requested at app start.**
Why: Apple HIG `privacy.md`: don't ask at launch without context.
Fix: ask in first-run with a one-line reason; if denied show the "Waiting for GPS" state with one "Turn on location" action.

**U-13 · Med · whole app · Always reopens on the first screen.**
Why: `launching.md`: restore the previous state.
Fix: persist the selected tab and restore it on launch and after a Day/Night recreate.

### 3.3 Visual craft (High/Med)

**U-14 · High · all layouts · The template look.**
What: near-black panels, one saturated accent, all caps, wide tracking, Black weight, numbered "01 ·" labels where order carries no meaning (`view_carplay.xml:76,121,166`, `view_audio.xml:21,35,49,63`, `view_setup.xml:267-337`).
Fix: sentence case, Semibold at most, no numbering. Numbers survive only for real sequences (first-run steps).

**U-15 · Med · all layouts · Everything is a panel.**
What: every region has the same border, fill and radius, so nothing is more important than anything else (`bg_panel.xml`: `#E8121419`, radius 14).
Fix: tiles on `surface-1` with a shadow, rows on `surface-2`, glass only on the status bar, rail, sheets and notifications.

**U-16 · Med · `colors.xml`, dialogs · No Day mode, hardcoded dark colours in code.**
What: the calibration dialog uses `#0F172A` and `#94A3B8` literals; there is no night resource folder.
Fix: tokens in `res/values` and `res/values-night`; no colour literal in Java.

**U-17 · Low · launcher icon · Dark icon background (`#0C0D10`).**
Why: CarPlay HIG: don't use black as an icon background.
Fix: Lagoon accent background with a light glyph.

### 3.4 Interaction (Med)

**U-18 · High · 33 `Toast.makeText` calls in `MainActivity.java` (e.g. :383, :430, :598, :660) · Feedback disappears.**
Why: Android for Cars: input feedback within 250 ms and persistent status; `feedback.md`: integrate status into the interface.
Fix: the notification component (glass capsule, status badge, at least 8 s, close button, one action). Some toasts become permanent state (Connected chip), some disappear entirely ("Paused all media" is visible in the play button).

**U-19 · High · `MainActivity.java:258,553,596,825` · Hidden long-presses.**
What: long-press on the Bluetooth pill/card opens the phone picker; long-press on play pauses all; long-press on radio play does something else.
Fix: every action is a visible control. The phone picker opens from the Phone tile ("Switch phone").

**U-20 · High · `activity_main.xml:124-146`, `MainActivity.java:255` · One pill, two meanings.**
What: the Bluetooth pill launches CarPlay when "connected" and opens the picker otherwise; the weather pill opens a calibration dialog.
Fix: the status bar shows state only. Actions live on tiles.

**U-21 · Med · `MainActivity.java:1400,1481` · Accent change is a mode with no preview and no undo.** Gone with U-05.

**U-22 · Med · `MainActivity.java:383,430` · Close-all-apps pretends to free memory.**
What: the task switcher says "Memory released" but cannot force-stop apps (Audit 1, section A).
Fix: rename to what it does ("Switch app"), remove the close claims, or show an honest result ("Closed 3 apps").

**U-23 · Med · `view_setup.xml:211-221` · "LIVE 20Hz" looks like a setting but only starts GPS.**
Fix: remove it. A GPS status chip shows the real state.

### 3.5 Content and writing (Med)

**U-24 · High · `view_carplay.xml:26-189` · The Phone screen is marketing copy.**
What: "WIRELESS PROJECTION… latency-free touch response", "DSP AUDIO TUNNEL… Rohm/SI473X ICs". It states hardware facts the app can't verify.
Why: Purpose and Responsibility; `writing.md`: say what happened and what to do.
Fix: replace with a Connect screen: connection state, one Open CarPlay button, an auto-open switch, other phones, a "CarPlay not starting?" help sheet.

**U-25 · High · `view_dashboard.xml:153`, `view_carplay.xml:36`, `activity_main.xml:74,408` · Car- and unit-specific strings.**
What: "SANTAFEMR", "ZLINK 5.3 HARDWARE PROJECTION · HYUNDAI SANTA FE", "SANTA FE · IX45", "HYUNDAI SANTA FE · IX45 COCKPIT".
Fix: read from the vehicle profile (F-01, F-02); hide the line when none is set.

**U-26 · High · `view_setup.xml:281` · Factory PIN shown in a button label.**
Fix: never display it. Put "Factory settings" under Advanced with a confirmation sheet that explains the risk; the PIN lives in the profile/docs.

**U-27 · Med · `dialog_device_selector.xml:75`, `view_maps.xml:85` · Jargon and noise.**
What: "INITIALIZING BLUETOOTH & DETECTING PHONES…", "Waking head unit Bluetooth stack", "Searching Satellite Coordinates…".
Fix: "Looking for phones…", "Waiting for GPS". Verb-first buttons of at most 3 words ("Open CarPlay", "Pair a phone", "Switch app").

**U-28 · Low · `view_maps.xml:226` · "REST AREA" button searches for "coffee".** Label and behaviour disagree. Fix: one label per action; make the saved places configurable (F-13).

**U-29 · Low · `view_audio.xml:110` · "PRESET 01" is static.** Remove until presets are real (F-05 and Audit 1 section B).

**U-30 · Low · copy · Mixed capitalisation, "&" everywhere, "KM/H".** Fix: sentence case, "and", "km/h".

---

## 4. Driving and parked states

The app is in one of two states, derived from GPS speed. Starting thresholds (tune in the car): enter *driving* above 8 km/h for 3 s; return to *parked* below 3 km/h for 10 s. If speed is unknown (no fix), the app behaves as *parked* but never offers pairing while the head unit reports the car as moving, if it exposes that signal (*verify in the car*).

Source of the lock-outs: NHTSA visual-manual guidelines (manual text entry, long lists, auto-scrolling text, non-driving images beyond album art), Google cars ("hide search while driving"), Android for Cars (task flows of five steps or fewer).

| Feature | Parked | Driving |
|---|---|---|
| Apps | Search plus full grid, scrolling | Six favourites, no search, no scrolling |
| Pairing a phone | Available | Locked ("Available when parked") |
| Settings | Everything | Display, Phone, Audio only; Advanced hidden |
| Pickers (default app, map engine) | Full list | Locked |
| Text longer than a line | Shown | Not shown; one line, 28 sp |
| Auto-scrolling text (track title) | Allowed (marquee) | Static, truncated with ellipsis |
| Album art | Full size | Full size (allowed while selecting media) |
| Video, web | Allowed through other apps | Not offered by ApexDrive |
| Notifications | At least 8 s, any text | At least 8 s, at most two lines |

---

## 5. Screen overhauls

Shell for every screen (design system, "Layout and shape"): a 96 px glass rail on the left (Home, Phone, Maps, Audio, Apps, Settings), a 52 px glass status bar on top (clock, outside temperature, GPS chip, phone chip), and a 896×520 content area on an 8 px grid with 24 px between tiles. Tiles are opaque. The wireframes are not to scale; sizes are in px.

Notation: `[ Button ]` = 64 px target, `( chip )` = non-tappable status, `▮` = switch.

### 5.1 Home (replaces DASH)

Today: `view_dashboard.xml`. Left column: a projection card with a marketing headline and LAUNCH PROJECTION (60 px), plus a Bluetooth card. Right column: speed (86 sp) and Now Playing. Three different "start CarPlay" paths exist.

Job: answer "where am I going, how fast, what's playing, is my phone connected" in one glance, and start the next action in one tap.

```
┌────┬──────────────────────────────────────────────────────────────┐
│rail│ 14:32            38°            ● GPS        ● iPhone        │ status bar (glass)
│    ├───────────────────────────────────────┬──────────────────────┤
│Home│ Drive                                 │ Speed                │
│    │ [ Home ] [ Work ] [ Fuel ]            │  87 km/h             │ upper half:
│Phone│                                      │ Trip 42.6 km  Avg 71 │ what the driver checks
│    │ [ Open Maps ]    Last: Dubai Mall     │                      │
│Maps ├──────────────────────────────────────┼──────────────────────┤
│    │ ┌────────┐ Long Way Home              │ Phone                │
│Audio│ │ art    │ Saeed and the Dunes        │ iPhone               │ lower half:
│    │ │ full   │ Bluetooth · iPhone         │ ● Connected          │ controls
│Apps │ │ height │ [⏮] [⏸] [⏭]               │ [ Open CarPlay ]     │
│    │ └────────┘                            │                      │
│Sett.│                                      │                      │
└────┴──────────────────────────────────────┴──────────────────────┘
```

Components: Drive tile, Speed tile, Now playing tile (art fills the full tile height, flush left), Phone tile, status chips.

States:
- Drive tile idle: three saved places (configurable, F-13) and Open Maps. Navigating (needs OsmAnd's API, F-11): next turn icon, distance (`display` 64), street, arrival time.
- Speed: GPS fix → number and Trip; no fix → "–" and "Waiting for GPS"; location off → "Turn on location" as the one action. Never "0" when speed is unknown.
- Now playing: with metadata → art, title, artist, source; nothing → "Nothing playing / Play something on your phone or pick a source" plus Radio. Art comes from the media session when the app provides it (F-05); otherwise the placeholder.
- Phone: Connected / Not connected (Connect phone) / Connecting (Cancel). Connected state must come from a verified signal (Audit 1, section A), not from "Bluetooth is on".

Driving: unchanged except the Drive tile hides "Open Maps" if the map app is full-screen already and notifications are limited to two lines. The tiles are already glanceable.

Tap counts from Home: open maps 1, start route to a saved place 1, start CarPlay 1, pause 1, skip 1.

Removes: projection card, "APPLE CARPLAY" and "WIRELESS & USB PROJECTION INTERFACE" copy, the A2DP caption, "UNIVERSAL AUDIO", all caps, north/compass line (`view_dashboard.xml:239`), the reset-by-tap trip row (moves to Trip history, F-15).

### 5.2 Phone (replaces PHONE, a brochure)

Today: `view_carplay.xml`. A header, a fullscreen launch button, and three numbered panels of copy about wireless, USB and DSP. No state.

Job: connect, switch and start CarPlay; fix it when it fails.

```
┌────┬──────────────────────────────────────────────────────────────┐
│rail│ status bar                                                   │
│    ├───────────────────────────────────────┬──────────────────────┤
│    │ ◯ iPhone is connected                 │ Other phones         │
│    │   ● Calls and audio                   │ ┌──────────────────┐ │
│    │                                       │ │ Galaxy S23       │ │
│    │ CarPlay runs over the USB cable.      │ │ Not nearby       │ │
│    │ Keep the phone plugged in.            │ ├──────────────────┤ │
│    │                                       │ │ Work iPhone      │ │
│    │ [        Open CarPlay        ]        │ │ Not nearby       │ │
│    │                                       │ ├──────────────────┤ │
│    │ ▮ Open automatically                  │ │ + Pair a phone   │ │
│    │   When iPhone connects                │ └──────────────────┘ │
│    │ > Phone settings                      │ CarPlay not starting?│
│    │   Bluetooth app on the head unit      │ [ Show steps ]       │
└────┴───────────────────────────────────────┴──────────────────────┘
```

Behaviour:
- **One** Open CarPlay button. It launches the app the driver chose in Settings (today `launchCarPlay()` ignores the choice, Audit 1 section A).
- The auto-open switch is off by default; it replaces the 8 s countdown (U-11).
- Tapping a phone in Other phones opens the "Choose a phone" sheet (5.7). Switching a phone is 2 taps.
- "Show steps" opens a help sheet: 1 Check the USB cable. 2 Unlock the phone. 3 Turn Bluetooth off and on. 4 Pair again. It is the only place that explains wired vs wireless and it makes no hardware claims.

States: Not connected (title "No phone", chip "Not connected", primary action "Connect phone"); Connecting (spinner, Cancel); Connected; Failed (notification "CarPlay didn't start. Check the USB cable." with Try again).

Driving: pairing is locked ("Available when parked"); Open CarPlay stays.

Removes: all three numbered panels, hardware claims, "SantafemR", the fullscreen button label.

### 5.3 Maps

Today: `view_maps.xml`. A telemetry panel (coordinates, altitude, trip), four POI buttons, "LAUNCH OSMAND OFFLINE", "CHANGE DEFAULT MAP ENGINE", and a static status line.

Job: start a route fast. The driver's map app does the real work.

```
┌────┬──────────────────────────────────────────────────────────────┐
│rail│ status bar                                                   │
│    ├───────────────────────────────────────┬──────────────────────┤
│    │ Where to?                             │ Here                 │
│    │ [ Home ]  [ Work ]                    │ Sheikh Zayed Rd      │
│    │ [ Fuel ]  [ Parking ]                 │ GPS ● good           │
│    │ [ Rest stop ] [ Hospital ]            │ Trip 42.6 km · 38 min│
│    │                                       │                      │
│    │ Recent                                │ [ Open map ]         │
│    │ Dubai Mall            12 km           │ OsmAnd               │
│    │ Mall of the Emirates  18 km           │ Change app           │
└────┴───────────────────────────────────────┴──────────────────────┘
```

Behaviour:
- Place buttons start a route in the chosen map app (OsmAnd intent) or search nearby if the app can't take a destination; label always matches action (today "REST AREA" searches "coffee").
- Recent and saved places are real (F-13). Without them the section shows "Places you visit appear here."
- GPS quality is a real word from the fix (Good, Weak, None). Coordinates and altitude move to Settings > About, not shown while driving.
- "Change app" is a Settings row, not a prominent button.

Driving: Recent list shows the three newest; no scrolling.

Removes: coordinates, the static "RADARS · ROAD BUMPS · OFFLINE LANES ACTIVE" (a claim OsmAnd, not ApexDrive, can make), "3D Fix · ±3m" unless it is read from the fix.

### 5.4 Audio

Today: `view_audio.xml`. Four numbered source buttons (FM radio, Spotify, Bluetooth, USB) 60 px tall, a radio tuner panel with frequency 88 sp, "PRESET 01" and three buttons.

Job: play something and control it. The real now-playing screen is the one missing screen.

```
┌────┬──────────────────────────────────────────────────────────────┐
│rail│ status bar                                                   │
│    ├──────────────────────────────────────────────────────────────┤
│    │ ┌──────────────┐  Long Way Home                              │
│    │ │              │  Saeed and the Dunes                        │
│    │ │   album art  │  Bluetooth · iPhone                         │
│    │ │   full height│  ━━━━━━━●────────────  1:42 / 3:51          │
│    │ │              │                                             │
│    │ │              │  [⏮]   [  ⏸  ]   [⏭]          Sources: [ ⌄ ]│
│    │ └──────────────┘                                             │
└────┴──────────────────────────────────────────────────────────────┘
```

Behaviour:
- Driven by the active media session (F-05): art, title, artist, progress, transport. Source chip names the real source.
- Sources: a segmented row (Radio, Phone, USB) of 64 px buttons, showing only what the head unit has. Radio shows frequency in `display` (64 px) with seek buttons and, once real, presets (F-05 and Audit 1, section B: remove the static "PRESET 01").
- Radio seek must reflect the tuner, not just text. Until the tuner state can be read (*verify in the car*), seek buttons are shown but labelled with the broadcast they send.
- Volume: use the system volume (`playing-audio.md`: don't repurpose audio controls).

Driving: marquee off; title truncated to one line at 28 sp; sources locked to the current one plus Radio.

### 5.5 Apps

Today: `view_apps.xml`. A heading, a 260 dp EditText, a five-column grid of 106 dp tiles. Every installed app, no favourites, no refresh when apps change.

Job: reach a specific app in 2 taps.

Parked:
```
┌────┬──────────────────────────────────────────────────────────────┐
│rail│ status bar                                                   │
│    ├──────────────────────────────────────────────────────────────┤
│    │ ( Search apps                      )   Favourites  All apps  │
│    │ [OsmAnd] [ZLink] [Radio] [Phone] [Music]                     │
│    │ [Podcast][Dashcam][Files][Weather][Calc]                     │
└────┴──────────────────────────────────────────────────────────────┘
```
Driving:
```
┌────┬──────────────────────────────────────────────────────────────┐
│rail│ ( 🔒 6 favourites while driving. Search and the full list     │
│    │      come back when you stop. )                              │
│    │ [OsmAnd]      [ZLink]      [Radio]                           │
│    │ [Phone]       [Music]      [Dashcam]                         │
└────┴──────────────────────────────────────────────────────────────┘
```

Behaviour: favourites first (long-press is not used; a visible "Edit favourites" action while parked); 160 px tiles with 72 px icons (the 5-column grid becomes 5 at 160 px only if the labels stay at 22 px, otherwise 4); the list refreshes on package install/removal (Audit 1, section A); icons load off the UI thread (Audit 1, section D).

Removes: the heading "INSTALLED VEHICLE APPS", keyboard-first search at speed.

### 5.6 Settings (replaces SETUP)

Today: `view_setup.xml`. Accent swatches, pickers for CarPlay app, radio app and map, unit toggle, "GPS Refresh Rate", and six numbered shortcuts including "FACTORY SETTINGS (PIN 123456)".

Job: set things once; find them later.

```
┌────┬─────────────────────┬────────────────────────────────────────┐
│rail│ Vehicle             │ Display                                │
│    │ Phone and CarPlay   │  Night mode            Auto  ›         │
│    │ Navigation          │  Units                 km/h | mph      │
│    │ Audio               │  Clock                 24-hour ▮       │
│    │ ▌Display            │  Solid backgrounds     ▮               │
│    │ Advanced            │  Stronger contrast     ▮               │
│    │ About               │  Brightness            ━━━━●──         │
└────┴─────────────────────┴────────────────────────────────────────┘
```

Groups: **Vehicle** (name, make and model from the vehicle profile, F-02; "Test my unit", F-03), **Phone and CarPlay** (driver phone, CarPlay app, open automatically), **Navigation** (map app, saved places), **Audio** (radio app, source defaults), **Display** (night mode Auto/Day/Night, units, clock, solid backgrounds, stronger contrast), **Advanced** (factory settings behind a confirmation sheet, system settings, DSP/equaliser shortcut, Bluetooth manager, restart ApexDrive, export logs F-10), **About** (version, update check F-09, licences).

Rules: rows are 72 px with a switch only in rows; the number of settings is minimal (`settings.md`); setup information that can be obtained elsewhere is not asked for (`settings.md`). Driving: Advanced and pickers hidden; Display, Phone and Audio stay.

Removes: accent picker, "SWISS COCKPIT ACCENT COLOR", "LIVE 20Hz", numbering, the PIN in the label, the 15-item `AlertDialog` list pickers (replaced by a sheet with large rows and search while parked).

### 5.7 Dialogs and sheets

**Choose a phone** (replaces `dialog_device_selector.xml`):
```
        ┌───────────────────────────────────────────┐
        │ Choose a phone                         ✕  │ glass sheet,
        │ ┌───────────────────────────────────────┐ │ scrim behind
        │ │ iPhone              Connected      ✓  │ │
        │ │ Galaxy S23    Last used 3 days ago [Connect] │
        │ │ Work iPhone         Not nearby        │ │
        │ └───────────────────────────────────────┘ │
        │ [ + Pair a phone ]                        │
        └───────────────────────────────────────────┘
```
No countdown, no `adapter.enable()` without a confirm (if Bluetooth is off the sheet says "Bluetooth is off" with a Turn on button), no "INITIALIZING…" spinner text: "Looking for phones…" with a spinner only after 2 s.

**Switch app** (replaces the task switcher): a row list of running apps with the app's name; actions "Switch" and (if allowed) "Close". No "Memory released" claim unless the close actually worked (Audit 1, section A). Title "Switch app", sentence case.

**Calibration** (temperature): a sheet with a stepper and "Use sensor" / "Use online"; uses tokens (no `#0F172A`/`#94A3B8`).

**Confirmation sheet**: title (question), one sentence, two buttons: the safe one on the left (secondary), the action on the right (primary only if it is the expected action; a destructive action uses text colour `alert` and is not the primary).

### 5.8 First run

Today: none. The app starts on DASH, asks for location immediately and shows an 8 s phone picker.

Order (design-system principle "Postpone nonessential setup"; ties to F-02 and F-07):

```
1 Language, units          →  Pick your language and units               [ Next ]
2 Vehicle and head unit    →  Make → Model → Head unit  (all skippable)  [ Next ]
3 Driver phone             →  Choose a phone / Skip for now              [ Next ]
4 Apps                     →  CarPlay app · Map app · Radio app          [ Next ]
5 Permissions              →  Location (why), Notification access (why)  [ Done ]
```

- Five steps, each at most one decision cluster, each with a reason line ("Location shows your speed and sets Night mode").
- Every step has Skip; nothing blocks reaching Home. A "Finish setup" chip remains in Settings if anything was skipped.
- Step 5 requests permissions one at a time, each with the system dialog opened from the button, not at launch.
- Parked only: first run is shown when the car is stopped; if the car is moving, defer to the next stop.
