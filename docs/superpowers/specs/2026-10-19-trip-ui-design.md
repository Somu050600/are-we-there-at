# Trip-based destinations and live progress: design

Date: 2026-10-19. Branch: `feature/complete-alarms-and-functional-tests`.

## Goal

Replace always-on recurring geofence alarms with a trip model and a new UI:

1. Sharing a place opens a bottom sheet, not a new page.
2. Home shows the last trip on top and saved destinations below.
3. An active trip shows a live progress notification and rings on arrival.

Keep the Flutter/Kotlin split, application ID, SQLite database file and the
`are_we_there/native` channel name.

## Decisions

| Topic | Decision |
| --- | --- |
| Monitoring | One active trip at a time, driven by a native foreground location service. GPS runs only during a trip. |
| After share | Sheet primary action **Start** saves and starts the trip; secondary **Save** only stores. |
| Full-screen alarm | Included, behind a Home toggle (default on). |
| Icons | Included: navigation, home, work, office, shopping. |
| Theme | Follow system; dark theme styled after the reference screenshots, matching light theme. |
| Distance | Straight-line distance; the UI says so. |

## Data

SQLite schema v2 → v3 on the existing `alarms` table (rows are now
"destinations"):

- add `icon TEXT NOT NULL DEFAULT 'navigation'`
- add `last_used_at INTEGER NULL` (set when a trip starts)
- `is_active` stays in the schema for compatibility and is no longer read.

Dart model `Alarm` becomes `Destination` (id, name, lat, lng, radius,
createdAt, sharedLink, icon, lastUsedAt). Migration is tested by opening a real
v2 SQLite file and upgrading it.

One-time cleanup: on first launch after upgrade, the app asks native to remove
all legacy geofences and clear the legacy `native_alarm_state_v1` store. The
geofence classes (`GeofenceManager`, `GeofenceReconciler`, `GeofenceReceiver`,
`AlarmStore`, `BootReceiver` restore path) are deleted after the cleanup call
exists.

## Native trip service (Kotlin)

`TripService` is a foreground service of type `location`.

- **Start** `startTrip(id, name, lat, lng, radius)`: requires precise location
  and, on Android 13+, notification permission. Background location is not
  needed because the service starts while the app is visible.
- **Location**: Fused provider, high accuracy. Interval 30 s when more than
  2 km away, 5 s within 2 km. (Balanced priority was dropped: it depends on
  network location, and with that off no fixes arrive.)
- **Progress**: `startDistance` is the first fix's distance.
  `progress = clamp(1 − distance / startDistance, 0, 1)`. Before the first fix
  the notification reads "Calculating…".
- **Pause**: removes location updates; the notification shows Resume and Stop.
- **Stop**: removes updates, clears the notification, ends the service.
- **Arrival** (`distance ≤ radius`): ends tracking and starts the alarm. The
  alarm loops the default alarm ringtone (`USAGE_ALARM`) with vibration until
  the user taps Stop, dismisses it, or 5 minutes pass.
- **Full-screen**: if the toggle is on and
  `NotificationManager.canUseFullScreenIntent()` allows it (Android 14+), the
  arrival notification carries a full-screen intent to a small native
  `ArrivalActivity` ("Arrived at X", Stop) that shows over the lock screen.
  Otherwise a heads-up notification with a Stop action is used.
- **Single trip**: starting a trip while one is active replaces it.
- **State**: `TripStore` (SharedPreferences, committed synchronously) holds the
  active trip so a restarted service or app can recover it, plus the last trip
  for the Home card. `last_used_at` in SQLite is the source for "Last: 4h ago".
- **Interrupted trips** (reboot, app update, force-stop): tracking cannot be
  restarted from the background. Boot and package-replaced post "Trip to X
  stopped by restart. Tap to resume" and keep the stored trip; whenever the app
  opens and finds a stored trip without a running service, it resumes it (or
  clears it if location permission was revoked).
- **Events**: an EventChannel `are_we_there/trip` streams
  `{state: idle|tracking|paused|arrived, destinationId, distanceMeters, progress}`.

### Notification

Channel `trip_progress_v1` (low importance, silent, ongoing) for progress;
channel `arrival_alarm_v2` (high importance, alarm sound) for arrival.

- Title "Travelling to X"; text "4.2 km remaining (63%)" or "Calculating…".
- Android 16+ (API 36): `Notification.ProgressStyle` with a tracker icon
  (navigation arrow) and live-update promotion where the platform allows.
- Older versions: `setProgress(100, percent, false)`.
- Actions: Pause/Resume and Stop. Tapping opens the app.

### Channel methods

Existing methods `getSharedLocation`, `openSettings` stay. Replaced:
`addGeofence`, `removeGeofence`, `syncGeofences` are removed in favour of
`startTrip`, `pauseTrip`, `resumeTrip`, `stopTrip`, `getTripState`,
`removeLegacyGeofences`, `canUseFullScreenAlarm`, `openFullScreenSettings`.
Permission prompts reuse the existing serialized prerequisite flow, minus the
background-location step.

## Flutter UI

- **Home**: Last Trip card (icon, "Last Trip", name, Re-Start). While a trip
  is active the card shows name, distance, a progress bar, Pause/Resume and
  Stop. "Saved Destinations" list of cards (icon, name, "Last: 4h ago" or
  "Never used", ▶). Hint text (share instructions, straight-line distance,
  accuracy caveat). Full-screen alarm toggle card. When the system blocks
  full-screen alerts, the toggle row says so and offers Settings.
- **Destination sheet** (modal bottom sheet, scrollable, keyboard-safe): name
  field, icon picker row, coordinates, radius slider (50 m–10 km). New: Cancel,
  Save, Start. Edit (tap a card): Delete, Cancel, Save.
- **Shares**: same queue and invalid-share handling as today; each valid share
  opens the sheet over Home, one at a time.
- **Theme**: Material 3 with a blue-grey seed, large radii (24) on cards and
  sheets, pill buttons; `ThemeMode.system`.

## Error handling

- Permission denial: snackbar with the existing readable messages; trip not
  started; destination still saved when Start was pressed (Start = save, then
  start).
- Location services off: prompt to enable, as today.
- Service start failure: error returned through the channel; Home shows idle.
- Deleting the destination of the active trip stops the trip first.

## Testing

- Dart: v2→v3 migration on real SQLite; destination repository; trip
  controller state from fake event stream; sheet (new/edit, keyboard and large
  text, validation); Home (last trip card states, list, toggle).
- Kotlin (Robolectric, SDK 35 sandbox): progress and arrival maths, interval
  selection, notification content and actions per API level, trip state
  machine (start, pause, resume, stop, arrival, replace), TripStore
  persistence, boot notice, legacy cleanup.
- Emulator (API 36): share → Start → mock GPS travel → notification progress →
  arrival alarm with full-screen and Stop; Pause/Resume; Stop; reboot notice.

## Out of scope

Road distance or ETA, multiple simultaneous trips, recurring always-on alarms,
analytics, maps rendering.
