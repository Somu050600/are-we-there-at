# Exact places from Google Maps shares: design

Date: 2026-10-20.

## Problem

The Google Maps app shares a named place as `maps.app.goo.gl/…`, which expands
to `/maps/place/<name, address>/data=!4m2!3m1!1s0x<a>:0x<b>…` with no
coordinates. The app looked the name up with Android's geocoder and took the
first result, which could be another place.

Checked against a real share (Beyond Bajji, Nellore):

- Google's place page served to a script is a shell; coordinates load later
  with JavaScript, so there is nothing to read.
- The first half of the feature id decodes as an S2 cell to a point 1.5 km
  from the place: not the place's location.
- The second half, unsigned (`0xf4638626f70b8787` → `17610066469860378503`),
  is the place's Maps id (CID), which Places API returns in `googleMapsUri`
  (`https://maps.google.com/?cid=…`).

## Design

- `IntentParser` reads the CID from any Google link it sees (the shared one or
  the expanded one), from `!1s0x…:0x…` or `ftid=0x…:0x…`.
- `PlaceFinder` runs before the geocoder. `GooglePlaces.finder` sends one
  Places Text Search (New) request: `textQuery` = the name and address from the
  link, `pageSize` 20, field mask `places.location,places.googleMapsUri`
  (Text Search Pro), with `X-Android-Package` and `X-Android-Cert` so an
  Android-restricted key works.
- The result whose `googleMapsUri` has the same CID is used and not marked
  approximate. Without a match, the top result is used, marked approximate.
  With no response (no key, offline, error) the geocoder runs as before.
- The key comes from `PLACES_API_KEY` (CI secret, or `android/local.properties`)
  into `BuildConfig`. Release builds warn when it is missing.

## Unreachable short links

On some mobile networks (seen on an IPv6-only 5G network, where the carrier's
DNS points `maps.app.goo.gl` at a carrier-hosted server) Android's plain
connection to the short link times out every time.

- `WebConnections` sends the short-link and Places requests through Cronet
  from Play services first (Chrome's stack: QUIC, hinted for these hosts so it
  is tried on the first request, and IPv6/IPv4 racing), then through Android's
  own stack. Timeouts are 8 s.
- If a later redirect hop fails, the Maps link already reached is used.
- If the short link itself can't be opened and the text has no coordinates,
  nothing is guessed: native answers `UNREACHABLE` and keeps the share. The
  sheet says "Couldn't reach Google Maps" with **Retry**, which calls
  `retrySharedLocation` to read the same share again. If the app was closed in
  between, Retry asks to share the place again.

## Cost

One request per share of a named place; none for links with coordinates.
Text Search Pro: 5,000 free requests a month, then about $32 per 1,000. A daily
quota cap on the key keeps it free.

## Testing

Kotlin: CID from data and ftid links; exact match not first in the list; top
result approximate without a match; empty and broken responses; no key means
no finder; the Beyond Bajji share resolves exactly without the geocoder; no
answer falls back to the geocoder; links with coordinates never call Places.
Not verifiable here: a real key. Check on a phone with the release APK.
