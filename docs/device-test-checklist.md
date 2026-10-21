# Physical Android validation

Automated and emulator tests cannot prove real GPS delivery, Doze behavior or
OEM battery restrictions. Test on a phone with Google Play Services before
relying on a trip. Record model, Android version, app commit, result and the
distance at which the alarm rang.

- Fresh install and update from an older build. Old geofence alarms must not
  ring after the update.
- Share from Google Maps: a place, a dropped pin and a shortened link (online
  and offline). The sheet opens once per share with name and coordinates.
- Share a short link on a slow connection: the sheet opens at once with
  "Finding the place…", then shows the place; Cancel while it loads saves
  nothing.
- Settings → Largest alarm radius 50 km: the sheet's slider reaches 50 km and
  a trip with that radius rings on entering it.
- With a release build: share a named place from the Google Maps app. The
  sheet shows it without "Approximate" and at the place Maps showed (try one
  with a same-named place nearby).
- On mobile data (Jio 5G), share a named place from Google Maps: the exact
  place shows. With mobile data off, the sheet says "Couldn't reach Google
  Maps" with Retry; turn data back on and Retry shows the place.
- Start: deny precise location, allow approximate only, then allow precise.
  Deny notifications on Android 13+, then allow. A refused Start keeps the
  destination saved.
- Turn system location off, Start, accept the prompt.
- During a trip: progress notification updates while the screen is off; Pause
  stops updates; Resume continues; Stop Tracking ends the trip.
- Arrival with the phone locked and in a pocket: full-screen page (or heads-up
  when full-screen alerts are not allowed), ringtone repeats until Stop,
  vibration. Repeat with the full-screen toggle off.
- Revoke "full-screen notifications" in Settings; the toggle card shows the
  warning and opens Settings.
- Start a second destination during a trip; the first trip ends.
- Delete the destination of a running trip; tracking stops.
- Reboot mid-trip: "Tap to resume" notice; tapping it resumes the trip.
- Force-stop mid-trip, then open the app: the trip resumes.
- Long trip (30+ min) with battery saver and Doze: note battery use and any
  gaps in updates.
- Trip to a place 50+ km away: the notification updates every few minutes at
  first, then more often from 10 km; note battery use per hour. Repeat with
  Wi-Fi scanning and network location off: updates must continue (GPS
  fallback after about 2–6 minutes).
- Time alert of 10 minutes on a 20+ minute ride: "About 10 min to X" rings
  once with roughly 10 minutes left (note how many), tracking continues and
  the arrival alarm still rings at the radius.
- Snooze from the notification and from the full-screen page: the alarm
  stops and rings again about 5 minutes later. With Vibrate only on, alarms
  vibrate repeatedly without sound.
- With battery optimization on, a trip 30+ km away asks once to allow the
  exemption. On Xiaomi, Samsung, OPPO, vivo or Huawei, Settings → Open
  settings reaches the maker's background-app screen (note which one).
- With battery optimization on, Home shows the battery card; **Allow** opens
  the system dialog and the card disappears after accepting. On Xiaomi, Oppo,
  Vivo and Samsung also set the app's own battery or autostart setting.
- Start a trip below 20% battery, unplugged: a "Battery at N%" notice appears;
  plugging in clears it. Again at 10%.
- Small screen, large text, landscape, keyboard open in the sheet.

The distance is straight-line, not along roads. A 50 m radius may be smaller
than GPS accuracy; choose a radius that gives enough warning.

References:
- https://developer.android.com/develop/sensors-and-location/location/request-updates
- https://developer.android.com/develop/background-work/services/fgs/service-types#location
- https://developer.android.com/develop/ui/views/notifications/notification-permission
