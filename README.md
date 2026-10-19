# Are We There Yet

Arrival alarm for trips. Share a destination from a maps app, start a trip, and
the app shows live straight-line distance in a notification and rings when you
arrive. Requires Google Play Services.

## Using the app

1. Share a Google Maps place or dropped pin, a `geo:` URI, or coordinates with
   this app. A sheet opens with the coordinates.
2. Name it, pick an icon and choose a radius from 50 m to 10 km.
3. Tap **Start** to save and start the trip, or **Save** to keep it for later.
   Allow precise location and notifications when asked.
4. While travelling, the notification shows the distance left and progress, with
   **Pause** and **Stop Tracking**. The Home screen shows the same.
5. On arrival the alarm rings until stopped. With **Full-screen alarm** on, it
   also shows a full-screen page over the lock screen (Android 14+ may ask you to
   allow full-screen notifications).

Home lists saved destinations with when each was last used; ▶ starts a trip and
tapping a card edits or deletes it. The top card restarts the last trip. One
trip runs at a time; starting another replaces it.

Location is only used during a trip. If the phone restarts or the app is updated
mid-trip, a notification asks you to reopen the app, which resumes the trip.

Google Maps short links need a network connection. Only links with a recoverable
coordinate destination can be used; an unresolvable share shows an error instead
of guessing a location.

Distances are straight-line, not along roads, and GPS can be inaccurate. Choose a
radius with enough travel buffer and complete the
[device checklist](docs/device-test-checklist.md) before relying on the app.

## Development

Flutter 3.38.9 / Dart 3.10.8, Java 17, Android SDK 36. Flutter owns the UI and
Drift SQLite database of destinations (schema version 3; older databases are
migrated). Kotlin handles incoming shares, permissions, the foreground trip
service, notifications and the arrival alarm.

```bash
flutter pub get
flutter analyze
flutter test --coverage
flutter build apk --debug
cd android
./gradlew :app:testDebugUnitTest
```

On Linux, SQLite tests require `libsqlite3-dev`. The generated Drift file is
checked in; after changing the database definition, regenerate it with
`dart run build_runner build --delete-conflicting-outputs`.

GitHub Actions runs analysis, functional tests and the debug APK build. Unit and
widget tests exercise failure recovery as well as normal flows. Android unit tests
use a simulated Android environment; actual GPS, permission dialogs, Doze and OEM
battery behavior still require the physical device checklist. The debug APK is a
development build. Store distribution needs a production signing configuration.
