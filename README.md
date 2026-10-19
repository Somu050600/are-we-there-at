# Are We There Yet

Android location alarms. Share a destination from a maps app, choose a radius,
and receive an arrival notification when Android detects entry into the area.
Requires Google Play Services.

## Using the app

1. Share a Google Maps destination, a `geo:` URI, or coordinates with this app.
2. Check the destination, enter an optional title, and choose 50 m–10 km.
3. Save and complete the precise location, background location and notification
   permission prompts. Android may require enabling “Allow all the time” in settings.
4. Use the switch to stop or resume monitoring. Swipe a card to delete it, or tap
   it to inspect the destination and open/copy the original shared link.

Alarms are recurring: entering the area can alert again on a later visit. Disable
or delete an alarm when it is no longer needed. Dismissing an arrival notification
stops that notification, but keeps the destination enabled.

If the list shows “Monitoring needs attention”, use Retry after fixing the
reported setting. Failed saves and switches display errors. The app reconciles
saved alarms with Android monitoring when reopened.

Google Maps short links need a network connection. Only links with a recoverable
coordinate destination can be used; some links describe a place without exposing
coordinates. An unresolvable share should show an error instead of guessing a
location.

Background geofences are not exact arrival timers. Delivery can be delayed and
small radii can be affected by location accuracy and battery restrictions. Choose
enough travel buffer and complete the [device checklist](docs/device-test-checklist.md)
before relying on the app on a trip.

## Development

Flutter 3.38.9 / Dart 3.10.8, Java 17, Android SDK 36. Flutter owns the UI and
Drift SQLite database; Kotlin handles incoming shares, permissions, geofences,
notifications and restoration. Database schema version 2 preserves existing data.

```bash
flutter pub get
flutter analyze
flutter test --coverage
flutter build apk --debug
cd android
./gradlew testDebugUnitTest
```

On Linux, SQLite tests require `libsqlite3-dev`. The generated Drift file is
checked in; after changing the database definition, regenerate it with
`dart run build_runner build --delete-conflicting-outputs`.

GitHub Actions runs analysis, functional tests and the debug APK build. Unit and
widget tests exercise failure recovery as well as normal flows. Android unit tests
use a simulated Android environment; actual GPS, permission dialogs, Doze and OEM
battery behavior still require the physical device checklist. The debug APK is a
development build. Store distribution needs a production signing configuration.
