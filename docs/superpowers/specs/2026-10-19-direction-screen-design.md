# Direction screen, card redesign and haptics: design

Date: 2026-10-19. Extends `2026-10-19-trip-ui-design.md`.

## Goal

1. A Find My–style direction screen for the active trip: arrival time, a big
   arrow pointing at the destination, distance and time left, open in Maps.
2. Active trip card and row restyled after the reference screenshot.
3. Subtle haptics throughout.

## Native

- `TripSnapshot` adds `lat`, `lng` (last fix), `speedMps` (smoothed),
  `bearingDegrees` (fix → destination, 0–360 from north) and `etaSeconds`.
- Speed: the fix's own speed when present, otherwise distance ÷ time between
  fixes; exponential smoothing (α = 0.3). ETA = distance ÷ speed, null below
  0.7 m/s or before a speed exists.
- `setFastUpdates {enabled}`: while enabled and tracking, 1 s high-accuracy
  updates; otherwise the 5 s / 30 s cadence. Paused trips ignore it.
- EventChannel `are_we_there/heading`: device azimuth in degrees from the
  rotation-vector sensor (geomagnetic rotation vector as fallback), corrected
  for display rotation, at most 20 events/s, registered only while listened.
  No sensor → the stream ends with error code `NO_COMPASS`.
- `openInMaps {lat, lng, name}`: `google.navigation:q=lat,lng`, falling back to
  `geo:lat,lng?q=lat,lng(name)`.

## Direction screen (Flutter)

- Opens from the active trip card or the active destination row.
- Black background in both themes; top: arrival clock (`HH:mm`) or `—`,
  destination name below; center: rounded arrow rotated by
  `bearing − heading`, animated along the shortest turn; below: `4.2 km ·
  23 min` (or `Calculating…`); bottom: round map button.
- Without a compass the arrow is drawn relative to north with the caption
  "Compass unavailable · arrow points from north".
- Paused: arrow dimmed, caption "Paused". Arrived/idle: "You've arrived" or
  "Trip ended" and a Done button.
- Enables fast updates on open, disables on close.

## Card and row

- Card while active: arrow icon, "Tracking Now" / "Paused", name, large
  distance (`4.2 km`) or `Calculating…`, then Pause/Resume pill (tonal) and
  Stop pill (error container). Tap opens the direction screen.
- Active row: round Pause/Resume and round red ✕; tap opens the direction
  screen. Inactive rows keep ▶ and tap-to-edit.

## Haptics

`Haptics` helper: `tap()` selection click (buttons, icon picks, slider steps,
switch), `trip()` light impact (start, pause, resume, stop), `error()` medium
impact, `aligned()` light impact when the arrow enters ±10° of the
destination (re-arms beyond ±15°).

## Testing

Kotlin: bearing, speed smoothing, ETA, snapshot map, fast-mode interval.
Dart: `TripState` new fields, heading-relative angle and alignment hysteresis,
direction screen with fake streams, card/row actions, haptic calls via the
platform channel mock.
