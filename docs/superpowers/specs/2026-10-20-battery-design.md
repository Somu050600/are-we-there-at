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

| Distance to the radius edge | Minimum interval | Accuracy |
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
- **Speed.** The table gives minimums. The interval is lengthened to the time
  it would take to cover *half* the distance left, accelerating at 2 m/s² from
  the current smoothed speed up to a top speed of 1.5× the fastest speed seen
  this trip (90–300 km/h; 180 km/h before any speed is known), capped at
  10 minutes. Halving leaves room for going twice as fast as assumed, such as
  boarding a train after a bus. A stopped train 10 km out checks every
  3.6 minutes instead of every minute; 60 km out, every 10 minutes.
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

## Time alert

A destination can also ring a set time before arriving: Off, 5, 10, 15 or
30 minutes (schema v4 column `alert_minutes`, `startTrip` argument
`alertMinutes`, 1–120). It is in addition to the radius, not instead of it.

- It rings once, on the arrival alarm's channel and id (full screen when
  allowed, "About 10 min to X"), when the ETA (straight-line distance over
  smoothed speed) reaches the alert time. Tracking continues and the radius
  alarm still rings on arrival. `Trip.alerted` is stored so a recovered
  service does not ring it again.
- Only a GPS fix rings it; a rough fix that says it is due switches to GPS.
  Within twice the alert time the plan uses GPS anyway.
- While it is due, fixes are at most half the alert time apart, and half the
  time left until it once the ETA is known (but at least 15 s), so it rings
  at most half its window late. With the ETA unknown (stopped) it can only
  ring after the trip moves again.

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

## Settings, long trips and alarm options

- **Settings screen** (gear on Home): the full-screen alarm toggle (moved from
  Home), Vibrate only, battery optimization status with **Allow**, and the
  phone maker's own setting.
- **Long trip prompt.** While tracking with battery optimization on, the first
  fix 30 km or more away asks once per trip whether to allow the exemption.
- **Phone makers.** `getPhoneMaker` returns the maker's family (Redmi and
  POCO → xiaomi, realme → oppo, iQOO → vivo, Honor → huawei). Xiaomi, Samsung,
  OPPO/OnePlus, vivo and Huawei get written steps and **Open settings**, which
  tries the maker's own screens (they move between versions), then app info.
- **Snooze** on the alarm notification and the full-screen page silences it
  and rings the same alarm again in 5 minutes: an exact alarm
  (`USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` up to Android 12L), inexact if
  not allowed. A new alarm or trip cancels a pending snooze.
- **Vibrate only** posts alarms on a second high-importance channel without
  sound (`arrival_vibrate_v1`); still insistent, so the vibration repeats.
- Not done: **rising volume**. Channels cannot fade a sound in; it needs the
  app to play the alarm itself and stay alive while it rings, which is a
  change to the most safety-critical path and should be tested on devices.

## Smaller savings

- The progress notification is reposted from a fix only when its text
  changes (at most every 5 s) or a minute has passed, not on every fix.
- Home rebuilds only the top card on each fix; destination rows rebuild when
  the active destination or its paused state changes.
- `TripStore.active()` caches the parsed trip, keyed by its stored text, so
  every instance (service, activity, boot receiver) still sees the latest.
- Not done: batching fixes (`setMaxUpdateDelayMillis`). Far away, fixes are
  already 1–10 minutes apart, so batching saves almost nothing, and it delays
  acting on a fix, eating the margin the interval plan relies on.

## Testing

Kotlin: tier table and the 180 km/h crossing check, edge distance, fallback
to GPS on the alarm or on unavailability, the alarm moved by fixes and
cancelled by pause and stop, a rough fix inside the radius not ringing,
battery thresholds, re-arming and broadcast parsing, battery notices from the
service. Dart: `batteryUnrestricted` parsing, the Home card and its channel
call.
