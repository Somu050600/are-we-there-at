# Are We There Yet

Arrival alarm for trips. Share a destination from a maps app, start a trip, and
the app shows live straight-line distance in a notification and rings when you
arrive. Requires Google Play Services.

## Using the app

1. Share a place with this app: a Google Maps place or dropped pin (including
   `maps.app.goo.gl` short links), an Apple Maps, OpenStreetMap, Waze, Bing,
   HERE or Yandex link, a `geo:` URI, a plus code, or coordinates such as
   `12.97, 77.59` or `12°58'17"N 77°35'40"E`. Surrounding text is ignored. A
   sheet opens at once and fills in when the place is found.
2. Name it, pick an icon and choose a radius from 50 m to 10 km (up to 100 km
   with **Largest alarm radius** in Settings). Optionally
   have it also ring 5, 10, 15 or 30 minutes before arriving.
3. Tap **Start** to save and start the trip, or **Save** to keep it for later.
   Allow precise location and notifications when asked.
4. While travelling, the notification shows the distance left and progress, with
   **Pause** and **Stop Tracking**. The Home screen shows the same.
5. On arrival the alarm rings until stopped, or snoozed for 5 minutes. With
   **Full-screen alarm** on, it also shows a full-screen page over the lock
   screen (Android 14+ may ask you to allow full-screen notifications).

**Settings** (gear on Home) has the full-screen alarm, **Vibrate only**, the
largest alarm radius, battery
optimization and, on Xiaomi, Samsung, OPPO, vivo and Huawei phones, a shortcut
to the maker's own background-app setting. For long or overnight trips, allow
the app to run without battery restrictions; a long trip asks once.

Home lists saved destinations with when each was last used; ▶ starts a trip and
tapping a card edits or deletes it. The top card restarts the last trip. One
trip runs at a time; starting another replaces it.

Location is only used during a trip, and less often the farther away and
slower the trip is (see the
[battery design](docs/superpowers/specs/2026-10-20-battery-design.md)). If the phone restarts or the app is updated
mid-trip, a notification asks you to reopen the app, which resumes the trip.

Google Maps short links need a network connection. When a share names a place
without coordinates (the Google Maps app does this for named places), release
builds ask Google Places for that exact place by the Maps id in the link (see
[Exact shared places](#exact-shared-places)). If that finds nothing, or the build
has no key, the place is looked up by its name and address with Android's
geocoder, and the sheet marks the result as approximate so you can check it. A
share that can't be located shows an error.

Distances are straight-line, not along roads, and GPS can be inaccurate. Choose a
radius with enough travel buffer and complete the
[device checklist](docs/device-test-checklist.md) before relying on the app.

## Development

Flutter 3.38.9 / Dart 3.10.8, Java 17, Android SDK 36. Flutter owns the UI and
Drift SQLite database of destinations (schema version 4; older databases are
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

## Releases

Publish a GitHub release with a tag such as `v0.2.0` (or `v0.2.0-rc.1` as a
pre-release):

```bash
gh release create v0.2.0 --target main --generate-notes
```

The Release workflow tests the code, builds an APK named after the tag, signs
it with the release key from the repository secrets (`ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`), checks
the signing certificate and attaches the APK to the release. Keep a backup of
the keystore and its password: updates only install over APKs signed with the
same key. Local `flutter build apk --release` uses the debug key unless
`ANDROID_KEYSTORE_PATH` and the same variables are set.

### Exact shared places

The Google Maps app shares a named place as a link with its name, address and
Maps id but no coordinates. With a Google Places API key, the app runs one
Places Text Search for the name and address and uses the result with the same
Maps id: the exact place, not a guess. Links that carry coordinates (dropped
pins) never call it. Text Search Pro has 5,000 free requests a month.

1. In the [Google Cloud console](https://console.cloud.google.com), link a
   billing account to a project and enable **Places API (New)**.
2. Create an API key under **APIs & Services → Credentials** and restrict it:
   - **Android apps**: package `com.example.are_we_there_yet`, SHA-1
     `E9:2F:5B:18:78:F8:B5:C2:CD:F0:D4:91:7D:9B:05:BA:BA:30:31:70` (the release
     key; add your debug key's SHA-1 to test debug builds).
   - **API restrictions**: Places API (New) only.
3. Cap the Text Search requests per day under the API's **Quotas** (for example
   200) and add a budget alert.
4. Save the key as the repository secret `PLACES_API_KEY`. The Release workflow
   builds it into the APK. For local builds, add `PLACES_API_KEY=…` to
   `android/local.properties` (gitignored).

Without a key, the app builds and works as before.

GitHub Actions runs analysis, functional tests and the debug APK build. Unit and
widget tests exercise failure recovery as well as normal flows. Android unit tests
use a simulated Android environment; actual GPS, permission dialogs, Doze and OEM
battery behavior still require the physical device checklist. The debug APK is a
development build.
