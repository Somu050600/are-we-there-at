# Add place and open in maps: design

Date: 2026-10-22. Extends `2026-10-20-exact-shared-places-design.md`.

## Goal

Add a destination without sharing from another app, open any destination in
the user's maps app, and keep Google Places within its free monthly usage.

## Add place

- Home has a **+** button (tooltip "Add place") that opens a full-screen
  search with a paste button. Picking a place opens the usual destination
  sheet.
- Text that looks like a link, `geo:` URI, coordinates or a plus code shows
  **Use this link**, which `parsePlace` reads exactly like a share (short
  links, exact places, Retry on unreachable).
- Anything else is searched after a pause (3+ characters) or on the keyboard's
  search key:
  - **Default:** Android's geocoder (`searchPlaces`, `google: false`), free and
    with coordinates, up to 5 results. Below them, **Search Google for "…"**
    when the build has a Places key.
  - **Settings → Place search → Search Google as you type:** Google
    suggestions while typing (400 ms pause).
- Google suggestions are Places Autocomplete (New), biased to 50 km around
  the last known location when location is allowed. Each search screen visit
  is one session token (UUID); picking a suggestion calls `placeLocation`,
  Place Details with field mask `location` and the same token, which ends the
  session. Sessions that end in Place Details don't charge for the
  suggestions. "Powered by Google" is shown under Google results.

## Open in maps

`openInMaps` opens a `geo:lat,lng?q=lat,lng(name)` intent in Android's chooser,
so the user picks any installed maps app ("No maps app is installed" when none
handles `geo:`; the manifest declares the query). It is on the destination
sheet (next to the coordinates), the direction screen, and the menu a long
press on a saved destination opens (Open in maps, Edit).

## Settings

- **Place search:** Search Google as you type (off; disabled without a key).
- **Advanced** (collapsed): Search for shared places by name, the Text Search
  fallback for shares (on).

## Testing

Kotlin: place id from feature id (Google's example and Beyond Bajji), Place
Details and Autocomplete parsing, one details request then the cache, the
fallback on and off, the cache's eviction, argument checks. Dart: geocoder
search and save, Search Google with one session up to the pick, Google as you
type, no Google without a key, a pasted link read as a share, open in maps from
the sheet and a row, the settings toggles. Not verifiable here: real Google
responses; check with the release APK.
