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
- `PlaceFinder` runs before the geocoder and gets the link's feature id.
- **Cache first.** `PlaceCache` keeps found places by CID (the 500 most
  recent), so a place shared again costs no request.
- **Place Details by converted id.** A `ChIJ…` place id is URL-safe base64 of
  a protobuf with the feature id's halves as fixed64 fields 1 and 2 (checked
  against Google's own example: Google Sydney's id and `?cid=`). The finder
  builds the id and sends `GET /v1/places/{id}` with field mask `location`
  (Place Details Essentials), `X-Android-Package` and `X-Android-Cert`.
- **Text Search fallback** when Google answers 400/404 for that id (or another
  error): one Text Search (New) request, `textQuery` = the name and address,
  field mask `places.location,places.googleMapsUri` (Pro). The result whose
  `googleMapsUri` has the same CID is used; without a match the top result,
  marked approximate. Settings → Advanced turns this off; then an unknown id
  fails the share with "Google Maps doesn't know this place any more".
- With no response (no key, offline, error) the geocoder runs as before.
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

At most one Place Details Essentials request per new place shared (10,000
free a month), none for a place shared before or for links with coordinates.
Text Search Pro (5,000 free a month, then about $32 per 1,000) only when Google
doesn't know the converted id. Daily quota caps on the key keep it free.

## Testing

Kotlin: CID from data and ftid links; exact match not first in the list; top
result approximate without a match; empty and broken responses; no key means
no finder; the Beyond Bajji share resolves exactly without the geocoder; no
answer falls back to the geocoder; links with coordinates never call Places.
Not verifiable here: a real key. Check on a phone with the release APK.
