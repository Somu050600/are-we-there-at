# Battery use on long trips: design

Date: 2026-10-20. Extends `2026-10-19-trip-ui-design.md`.

## Goal

Make long and overnight trips (trains, buses) cheaper on battery and less
likely to be cut short:

1. Check less often, and without GPS, when the destination is far away.
2. Ask to be exempt from battery optimization.
3. Warn before the battery runs out during a trip.

## Location plan

The interval and accuracy depend on the distance left to the edge of the
arrival radius: `distance − radius − fix accuracy`. Counting the radius makes a
large radius reach the near rate before the alarm rings, not after; counting
the fix's error keeps a rough fix from looking farther away than it may be.

| Distance to the radius edge | Interval | Accuracy |
| --- | --- | --- |
| Before the first fix | 5 s | GPS |
| up to 2 km | 5 s | GPS |
| 2–10 km | 30 s | GPS |
| 10–50 km | 1 min | balanced (Wi-Fi and cell) |
| 50–100 km | 3 min | balanced |
| over 100 km | 5 min | balanced |
| Direction screen open | 1 s | GPS |

- Each interval is short enough that at 180 km/h the next fix still lands
  outside the radius (tested in `TripMathTest`).
- Balanced fixes can be off by a few kilometres, so they are only used 10 km
  or more from the edge. A balanced fix inside the radius does not ring: it
  switches to the near plan and the next GPS fix decides.
- **Fallback.** Balanced priority needs network location; with it off, or
  without signal, no fixes arrive at all (why the first design dropped it).
  The service switches to GPS for the rest of its life when Play services
  reports location unavailable, or when no fix arrives within one interval
  plus 60 s. That deadline is an `AlarmManager` wake-up alarm
  (`setAndAllowWhileIdle`), not a timer: timers stop while the phone sleeps,
  and with no fixes nothing would wake it. Every fix moves the alarm.

## Battery optimization

- `getAlarmSettings` adds `batteryUnrestricted`
  (`PowerManager.isIgnoringBatteryOptimizations`).
- `requestBatteryExemption` opens the system "Let app always run in
  background?" dialog (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, needs
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), falling back to the optimization
  list and then app details. Google Play restricts this permission; the app
  is distributed as an APK from GitHub releases.
- Home shows a "Battery optimization is on" card with **Allow** while the app
  is optimized. It refreshes when the app returns to the foreground.
- Phone makers' own killers (Xiaomi autostart, Samsung sleeping apps) are not
  covered by this exemption; the device checklist still asks to set them.

## Low battery warning

While a trip is active and the phone is not charging, the service posts a
"Battery at N%" notice (channel `trip_notices_v1`, id 1004) when the level
first reaches 20% and again at 10%. Plugging in clears it and re-arms both
warnings. The level is read when the trip starts and from
`ACTION_BATTERY_CHANGED` while the service runs. Stop and arrival clear it.

## Testing

Kotlin: tier table and the 180 km/h crossing check, edge distance, fallback
to GPS on the alarm or on unavailability, the alarm moved by fixes and
cancelled by pause and stop, a rough fix inside the radius not ringing,
battery thresholds, re-arming and broadcast parsing, battery notices from the
service. Dart: `batteryUnrestricted` parsing, the Home card and its channel
call.
