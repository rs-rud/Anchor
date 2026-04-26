# Anchor

Anchor is an Android focus app that **blocks distracting apps when you're physically inside a "focus zone"** (a geofence you define by address). When a blocked app is opened inside the zone, Anchor takes over the screen and runs a "ritual" — either a 10‑second guided breathing exercise or an automatic redirect to a "good app" of your choice — before optionally letting you bypass the block for 10 minutes.

The repo also contains a small React Native prototype (`App.tsx`) that experiments with BLE‑fingerprint based room presence detection as an alternative to GPS geofencing.

---

## High‑level architecture

```text
┌────────────────────────────────────────────────────────────────────┐
│  Android app (com.example.anchor) — Kotlin, Material 3, single APK │
└────────────────────────────────────────────────────────────────────┘

User picks address ─► Geocoder ─► GeofenceManager
                                       │
                                       ▼
                       Play Services Geofencing API + WorkManager
                                       │
                  ENTER / DWELL / EXIT  │  (+ 15‑min fallback poll)
                                       ▼
                       SharedPreferences (anchor_prefs)
                                       │
                                       ▼
            AppBlockerService (AccessibilityService)
                                       │
                            blocked app launched?
                                       ▼
                              BlockActivity
                          (breathing OR redirect)
                                       │
                              ▼                 ▼
                          Go Home          Open anyway (10 min)

  TelemetryTracker  ──►  Supabase (Postgrest)   [event log]
  PaywallActivity   ──►  RevenueCat             [pro entitlement]
```

Two state machines do the heavy lifting:

1. **Location state machine** — `GeofenceManager` + `GeofenceBroadcastReceiver` + `GeofenceLocationRefreshWorker` keep `KEY_IS_INSIDE_GEOFENCE` in `SharedPreferences` accurate.
2. **Block state machine** — `AppBlockerService` reads that flag on every `TYPE_WINDOW_STATE_CHANGED` event, and if the foreground app is in the blocked set, launches `BlockActivity`.

Everything is wired together through the `AnchorPrefs` keys — there is no database; `SharedPreferences` is the single source of truth.

---

## Project layout

```text
Anchor/
├── App.tsx                       # React Native BLE prototype (standalone, not part of the APK)
├── build.gradle.kts              # Root Gradle config
├── settings.gradle.kts           # Module includes
├── gradle/                       # Gradle wrapper + version catalog
└── app/
    ├── build.gradle.kts          # App module: SDK, deps, BuildConfig fields
    └── src/main/
        ├── AndroidManifest.xml   # Permissions, components, accessibility service
        ├── java/com/example/anchor/   # All Kotlin source (described below)
        └── res/                  # Layouts, drawables, strings, themes, menu, icons
```

---

## Kotlin source — file by file

All files live under `app/src/main/java/com/example/anchor/`.

### `MainActivity.kt`
The single host activity. It:

- Configures **edge‑to‑edge**, applies system bar insets to `R.id.main`, and renders a `BottomNavigationView` with three tabs (`BlockedAppsFragment`, `GeofenceFragment`, `RitualFragment`).
- Initializes **RevenueCat** with `BuildConfig.REVENUECAT_KEY`, fetches `CustomerInfo`, and writes the `pro_access` entitlement state into `is_pro_unlocked` in `anchor_prefs`.
- Logs `app_opened` and registers an anonymous device with `TelemetryTracker.registerDeviceIfNeeded`.
- Drives **runtime permission flow**: requests `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`, then on Android Q+ requests `ACCESS_BACKGROUND_LOCATION` via a Material dialog.
- On every `onResume()`, checks if `AppBlockerService` is enabled in Accessibility settings; if not, shows a blocking dialog that deep‑links to `Settings.ACTION_ACCESSIBILITY_SETTINGS`.

### `AnchorPrefs.kt`
A small `object` that centralizes every `SharedPreferences` key/constant used by the app — the file name (`anchor_prefs`), blocked‑app set key, geofence keys (lat/lng/radius/active/address/inside), ritual type, good app package, and the 10‑minute jailbreak duration. It also exposes the per‑package `jailbreakUntilKey(packageName)` helper.

### `AppBlockerService.kt`
The **core of the blocker** — an `AccessibilityService` listening to `TYPE_WINDOW_STATE_CHANGED`. For each event it:

1. Ignores its own package, system UI, launchers, and IMEs.
2. Reads `KEY_GEOFENCE_ACTIVE` and `KEY_IS_INSIDE_GEOFENCE` from prefs — short‑circuits unless both are true.
3. Checks if the foreground package is in `KEY_BLOCKED_APPS`.
4. Honours a per‑package "jailbreak window" (`jailbreakUntilKey(pkg)`) — if a jailbreak timestamp is in the future, the app is allowed; if it's in the past, it's cleared.
5. Otherwise launches `BlockActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` and emits an `app_blocked` telemetry event.

It also fires structured `AnchorDebugLog` entries describing each block decision.

### `BlockActivity.kt`
The **takeover screen** shown over a blocked app. It has three phases controlled by visibility on `R.id.breathingPhase`, `R.id.focusPhase`, and `R.id.redirectPhase`:

- **Breathing phase** — runs an infinite reverse `ObjectAnimator` on `R.id.breathingCircle` (scale 0.72→1.12, alpha 0.35→0.78, 4 s cycle) plus a 10‑second `Handler.postDelayed` countdown.
- **Focus phase** — fades in after the countdown; shows the "Go Home" / "Open app anyway" buttons.
- **Redirect phase** — shown immediately when the user's ritual is `RITUAL_GOOD_APP` and a valid good‑app package is set. Loads the good app's icon/label, waits 1.5 s, then launches it via `getLaunchIntentForPackage`.

`onSaveInstanceState` persists which phase was visible plus countdown progress so rotation doesn't reset the ritual.

The "Open app anyway" path writes `now + JAILBREAK_DURATION_MS` to `jailbreakUntilKey(pkg)` (using `commit()` rather than `apply()` so the value lands before the launch intent fires) and emits a `block_bypassed` telemetry event. Back press routes to a Home‑category intent so the user lands on the launcher, not the blocked app.

### `BlockedAppsFragment.kt`
The first tab. Loads installed launcher apps with `PackageManager.queryIntentActivities(MAIN/LAUNCHER)` on a worker thread, filters out Anchor itself, sorts blocked apps to the top, and feeds them into `AppListAdapter`. Toggling a switch mutates the in‑memory `blockedApps: MutableSet<String>` and writes it back with `putStringSet(KEY_BLOCKED_APPS, ...)`. If the toggled package is also the current "good app," the good‑app key is cleared. Includes a debounced (`TextWatcher`) case‑insensitive name filter and a header counter (`tvBlockedCount`).

### `AppListAdapter.kt`
A `ListAdapter<AppInfo, …>` for the blocked‑apps RecyclerView. Each row inflates `R.layout.item_app`, binds icon/name, drives a `MaterialSwitch`, shows/hides the "blocked" indicator, and forwards toggles via the `(packageName, isBlocked) -> Unit` callback. Tapping anywhere on the card flips the switch.

### `AppInfo.kt`
A trivial `data class` with `name: String`, `packageName: String`, `icon: Drawable`. Used by both adapters.

### `GeofenceFragment.kt`
The "Focus Zone" tab. Reads/writes geofence keys in `anchor_prefs` and:

- Renders a hero status pill (`hero_idle_label` / `hero_active_label`) tied to `KEY_GEOFENCE_ACTIVE`.
- Lets the user enter an address into a `TextInputEditText` and pick a radius via a `Slider`.
- On "Set focus zone," runs a `Geocoder.getFromLocationName` lookup on a `Thread`, and on success calls `GeofenceManager.addGeofence(lat, lng, radius)`. The address text is saved separately to `KEY_GEOFENCE_ADDRESS` for display.
- On "Remove focus zone," calls `GeofenceManager.removeGeofence` and resets the UI.
- `onResume()` re‑reads prefs to display the live inside/outside status (`KEY_IS_INSIDE_GEOFENCE`).

### `GeofenceManager.kt`
Thin wrapper over **Play Services Geofencing**. Responsibilities:

- Builds a `Geofence` for ENTER + EXIT + DWELL with `setLoiteringDelay(30 s)` and `NEVER_EXPIRE`, plus a `GeofencingRequest` with `INITIAL_TRIGGER_ENTER | INITIAL_TRIGGER_DWELL`.
- Owns a singleton `PendingIntent.getBroadcast(...)` to `GeofenceBroadcastReceiver` (mutable on Android S+).
- Saves the geofence into `anchor_prefs` (`KEY_GEOFENCE_LAT/LNG/RADIUS/ACTIVE`) on success.
- **Closes the cold‑start gap**: `INITIAL_TRIGGER_ENTER` from Play Services can lag minutes, so it also reads `fusedLocationClient.lastLocation` and immediately sets `KEY_IS_INSIDE_GEOFENCE` if the user is already inside.
- Schedules a `PeriodicWorkRequest` (`GeofenceLocationRefreshWorker`, every 15 min) as a fallback poller.
- `reRegisterFromPrefs()` is called from `BootCompletedReceiver` to restore geofences after a reboot.
- The shared helper `applyInsideStateFromLocation(...)` computes `Location.distanceTo` and writes `KEY_IS_INSIDE_GEOFENCE`.

### `GeofenceBroadcastReceiver.kt`
Receives the `PendingIntent` fired by Play Services. Maps `GEOFENCE_TRANSITION_ENTER` and `…_DWELL` to `is_inside_geofence = true`, `…_EXIT` to `false`, and emits `geofence_triggered` telemetry with the transition type. Errors are logged via `geofence_error`.

### `GeofenceLocationRefreshWorker.kt`
A `CoroutineWorker` that runs at WorkManager's minimum interval (~15 min) **only while a geofence is active**. It calls `fused.getCurrentLocation(PRIORITY_BALANCED_POWER_ACCURACY, …).await()` (with `lastLocation` as a fallback) and then delegates to `GeofenceManager.applyInsideStateFromLocation(...)`. This is a safety net for cases where the OS suppresses geofence transitions (Doze, killed services, location toggles, etc.).

### `BootCompletedReceiver.kt`
Listens to `ACTION_BOOT_COMPLETED` (declared in the manifest with `RECEIVE_BOOT_COMPLETED` permission) and calls `GeofenceManager(context).reRegisterFromPrefs()` so the user's focus zone survives reboots.

### `RitualFragment.kt`
The "Ritual" tab. Renders a `MaterialButtonToggleGroup` between **Breathe** and **Open good app**, and persists the choice into `KEY_RITUAL_TYPE`. When "Open good app" is selected, it shows a card displaying the currently selected good app (icon + name) read from `KEY_GOOD_APP_PACKAGE`. Tapping "Change app" launches `GoodAppPickerActivity`. If the saved good app no longer exists or is itself in the blocked set, the key is cleared and an empty‑state message is shown. The `suppressToggleEvents` flag prevents `onResume()` re‑sync from re‑emitting `ritual_changed` telemetry.

### `GoodAppPickerActivity.kt`
A standalone full‑screen picker for the "good app." Loads launcher apps the same way `BlockedAppsFragment` does, but **filters out anything currently in `KEY_BLOCKED_APPS`** so the user can't redirect to a blocked app. Selecting a row writes the package to `KEY_GOOD_APP_PACKAGE`, emits a `good_app_selected` telemetry event, and finishes.

### `GoodAppPickerAdapter.kt`
Sister adapter to `AppListAdapter` but read‑only — each row inflates `R.layout.item_good_app`, binds icon/name, and forwards taps via `(AppInfo) -> Unit`.

### `PaywallActivity.kt`
RevenueCat‑powered paywall. On create:

1. Logs `paywall_viewed`.
2. Calls `Purchases.sharedInstance.getOfferingsWith` and binds the first available package to the subscribe button as `"Unlock Pro - <formatted price>"`.
3. Subscribe button → `Purchases.sharedInstance.purchaseWith(PurchaseParams.Builder(this, pkg).build(), …)`. On success, if the `pro_access` entitlement is active, writes `is_pro_unlocked = true` to prefs and emits `purchase_successful`.
4. "Restore purchases" → `Purchases.sharedInstance.restorePurchasesWith(...)` with the same entitlement check.

Failures and cancellations are surfaced as Toasts, and `purchase_failed` telemetry is emitted on real errors (not user‑cancelled).

### `TelemetryTracker.kt`
A singleton `object` that lazily instantiates a Supabase client (`createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) { install(Postgrest) }`). Two entry points:

- `registerDeviceIfNeeded(context)` — generates a UUID, persists it in `anchor_telemetry` prefs, and inserts an `app_installations` row (`user_id`, `device_sdk_int`, `ab_variant`).
- `logEvent(eventType, metadata)` — fires a coroutine on `Dispatchers.IO` that inserts into `telemetry_events` with the user id, event type, and a `JsonObject` metadata bag. Failures are swallowed so telemetry never crashes the app.

### `AnchorDebugLog.kt`
A development‑only NDJSON logger used by `AppBlockerService` and `BlockActivity` to send structured "hypothesis" events (sessionId, hypothesisId, location, message, timestamp, runId, …data) to `http://10.0.2.2:7897/ingest/<id>` (the host machine from an emulator). All transport happens in a fire‑and‑forget `Thread`; failures are silent.

---

## Resources (`app/src/main/res/`)

- **`AndroidManifest.xml`** — declares 5 permissions (fine/coarse/background location, internet, boot completed), the four activities (`MainActivity` is launcher, `BlockActivity` and `GoodAppPickerActivity` are internal, `PaywallActivity` exists in code), the `AppBlockerService` (with `BIND_ACCESSIBILITY_SERVICE`), and the two `BroadcastReceiver`s.
- **`xml/accessibility_service_config.xml`** — limits the accessibility service to `typeWindowStateChanged` events with a 100 ms notification timeout, no window content retrieval (privacy‑preserving).
- **`layout/`** — `activity_main.xml`, `activity_block.xml` (with `breathingPhase` / `focusPhase` / `redirectPhase` containers), `activity_paywall.xml`, `activity_good_app_picker.xml`, plus the three fragment layouts (`fragment_blocked_apps`, `fragment_geofence`, `fragment_ritual`) and two row layouts (`item_app`, `item_good_app`).
- **`drawable/`** — vector icons (anchor, location, block, shield, search, apps, radius), gradient and pill backgrounds for the hero card, breathing circle, and status dots (idle/active).
- **`menu/bottom_nav_menu.xml`** — items for Blocked / Focus Zone / Ritual.
- **`values/`** — `colors.xml`, `themes.xml` (+ `values-night/themes.xml` for dark mode), and `strings.xml` (all user‑facing copy, including ritual hints, dialog text, and format strings like `geofence_active_format`).
- **`xml/backup_rules.xml` / `data_extraction_rules.xml`** — auto‑backup configuration.

---

## Build configuration

`app/build.gradle.kts`:

- `compileSdk = 36`, `minSdk = 24`, `targetSdk = 36`
- Java 11 source/target compatibility, Kotlin 1.9 with kotlinx.serialization plugin
- Reads three secrets out of `local.properties` and exposes them as `BuildConfig` fields:
  - `SUPABASE_URL`
  - `SUPABASE_ANON_KEY`
  - `REVENUECAT_KEY`
- Key dependencies:
  - AndroidX core‑ktx, appcompat, activity, fragment, constraintlayout, work‑runtime‑ktx
  - Google Material 3
  - `play-services-location` (geofencing + fused location)
  - `kotlinx-coroutines-play-services` (`.await()` on Play Services Tasks)
  - `io.github.jan-tennert.supabase:postgrest-kt` + `ktor-client-android` + `kotlinx-serialization-json` (telemetry)
  - `com.revenuecat.purchases:purchases` (paywall)

`local.properties` (not committed) must define:

```properties
SUPABASE_URL=https://<your-project>.supabase.co
SUPABASE_ANON_KEY=<your-anon-key>
REVENUECAT_KEY=<your-revenuecat-android-key>
```

---

## React Native prototype — `App.tsx`

Standalone exploration unrelated to the APK build. It uses `react-native-ble-plx` to:

1. Continuously scan BLE advertisements and aggregate `(deviceId → max RSSI)` into a 4‑second buffer.
2. In **calibrate** mode, push each buffered fingerprint into a list of "saved fingerprints."
3. In **monitor** mode, compute Euclidean distance (in RSSI space) between the live fingerprint and every saved fingerprint, take the minimum, and flag `inRoom = true` when it falls under `DISTANCE_THRESHOLD = 50`. Anchors with fewer than `MIN_SHARED_ANCHORS = 2` matches are treated as Infinity.

The UI is a simple SafeAreaView with status rows, calibration/monitor controls, a "Clear Dataset" button, and a live BLE feed list. It's not wired into the Android module — think of it as a research notebook for an indoor‑precision alternative to the GPS geofence used by the production app.

---

## Runtime flow (end‑to‑end)

1. User installs the app, opens it → `MainActivity` shows the accessibility prompt and runs the location permission gauntlet.
2. User goes to the **Focus Zone** tab, types an address, picks a radius, and taps "Set focus zone." Geocoder resolves it; `GeofenceManager` registers the geofence and writes coordinates + `KEY_GEOFENCE_ACTIVE = true` to prefs.
3. User goes to the **Blocked** tab and toggles the apps they want paused. Each toggle updates `KEY_BLOCKED_APPS`.
4. (Optional) User goes to the **Ritual** tab and switches from "Breathe (10 s)" to "Open good app," then picks a productive app via `GoodAppPickerActivity`.
5. When the user enters the geofence, Play Services fires `GeofenceBroadcastReceiver` → `KEY_IS_INSIDE_GEOFENCE = true`. (The 15‑min worker keeps this honest if Play Services hiccups.)
6. The user opens, say, Instagram. `AppBlockerService` sees `TYPE_WINDOW_STATE_CHANGED`, confirms `(geofenceActive && inside && instagram in blockedSet && !jailbreakActive)`, and launches `BlockActivity`.
7. `BlockActivity` either runs the 10‑second breathing animation or redirects the user to their good app. If they tap "Open this app anyway," a 10‑minute jailbreak token is committed for that package and the original app is launched.
8. Throughout, `TelemetryTracker` reports anonymous events (`app_blocked`, `block_bypassed`, `geofence_triggered`, `ritual_changed`, `paywall_viewed`, `purchase_successful`, …) to Supabase Postgrest.

---

## Required permissions

| Permission | Why |
| --- | --- |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Geofence registration and `lastLocation` reads |
| `ACCESS_BACKGROUND_LOCATION` (Q+) | So geofence transitions fire when the app is closed |
| `RECEIVE_BOOT_COMPLETED` | Re‑register geofences after reboot via `BootCompletedReceiver` |
| `INTERNET` | Telemetry to Supabase + RevenueCat + Geocoder |
| `BIND_ACCESSIBILITY_SERVICE` | Required by `AppBlockerService` to read foreground app changes |

The user must additionally enable **Anchor App Blocker** in *Settings → Accessibility* — `MainActivity` checks this on every resume and prompts via `MaterialAlertDialogBuilder`.
