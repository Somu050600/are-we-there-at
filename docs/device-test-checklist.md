# Physical Android validation

Automated tests cannot prove actual GPS delivery, Doze behavior, or OEM battery
restrictions. Test on a phone with Google Play Services before using it on a trip.
Record model, Android version, app commit, result and observed arrival delay.

- Fresh install, normal launch, cold share and warm share. Configuration opens
  once with selected destination, including encoded and shortened Maps links.
- Deny precise permission; try approximate only; then grant precise and retry.
- Deny background location; grant Allow all the time through settings and retry.
- Deny notifications on Android 13+; enable system notifications and the Arrival
  alarms channel, then retry. No failed Save should leave a phantom active alarm.
- Turn system location off, cancel the resolution prompt, then retry and accept.
- Share malformed input; share a short link offline; then share a valid location.
- Cross into an enabled destination radius: named audible notification, vibration,
  tap-to-open and dismiss button. Confirm both tap and dismiss stop the sound.
- Repeat locked and after removing the app from Recents. Test overlapping alarms.
- Disable, enter and verify silence; re-enable and test. Delete active alarm and
  verify it does not alert or reappear.
- Reboot, unlock once, wait for location acquisition, then cross the boundary.
  Repeat after installing an update over the same app.
- Revoke a permission with alarms enabled. Reopen, check warning, restore and retry.
- Weak GPS, offline mode, battery saver and extended Doze. Record delays.
- Force-stop in Android settings, then reopen to restore monitoring. Android blocks
  background delivery for force-stopped apps until launched again.
- Rapid Save taps, toggle then delete, Back during Save, landscape, keyboard open,
  small screen and large text. Test 100 active alarms and attempted alarm 101.

Background geofence events may be delayed by minutes. A 50 m radius may be smaller
than location accuracy. Choose a radius that provides enough travel buffer.

References:
- https://developer.android.com/develop/sensors-and-location/location/geofencing
- https://developer.android.com/develop/sensors-and-location/location/permissions/background
- https://developer.android.com/develop/ui/views/notifications/notification-permission
