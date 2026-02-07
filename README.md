# Are We There Yet

Location-based alarms: share a place from a map app, set a radius, and get notified when you arrive. Android only (uses geofences).

## Features

- **Share to create** — From Google Maps (or any app that shares a location), tap Share and choose this app. Supported inputs:
  - `geo:` URIs (with optional place label)
  - Google Maps URLs (including short links like `maps.app.goo.gl`; the app resolves them and scrapes coordinates when needed)
  - Plain text lat/lng
- **Configure alarm** — Set alert radius (50 m–10 km), optional alarm title (or use the parsed place name / coordinates as label).
- **Stored metadata** — The raw shared link is saved with each alarm so you can open or copy it later.
- **Alarm list** — Home screen lists all alarms with active toggle and swipe-to-delete.
- **Alarm details** — Tap an alarm to see name, coordinates, radius, created time, and the shared link (Open in browser / Copy).
- **Geofence notifications** — Entering the circle triggers the alarm (notification via Android).

## Tech

- **Flutter (Dart)** — UI, routing, local DB. Material 3.
- **Drift** — SQLite with a single `alarms` table (id, name, lat, lng, radius, createdAt, isActive, sharedLink); schema migration for adding `sharedLink`.
- **Kotlin (Android)** — Share/view intent handling, URL resolution (redirects + HTML scrape for short Maps links), place-name extraction from URL path and `og:title`, geofence registration (Google Play Services), MethodChannel to Flutter (`getSharedLocation`, `addGeofence`).
- **Other** — `url_launcher` for opening stored links, `path_provider` + `uuid` for DB and IDs.

## Getting started

```bash
flutter pub get
dart run build_runner build   # Drift codegen
flutter run
```

Requires Android with Google Play Services. Location (including background) permission is needed for geofences.
