import '../services/trip_state.dart';

/// "Last: 4h ago" style label for when a destination was last used.
String lastUsedLabel(DateTime? lastUsed, DateTime now) {
  if (lastUsed == null) return 'Never used';
  final elapsed = now.difference(lastUsed);
  if (elapsed.inMinutes < 1) return 'Last: just now';
  if (elapsed.inHours < 1) return 'Last: ${elapsed.inMinutes}m ago';
  if (elapsed.inDays < 1) return 'Last: ${elapsed.inHours}h ago';
  return 'Last: ${elapsed.inDays}d ago';
}

/// Says when tracking has gone quiet: no signal, or a fix older than two
/// minutes (far away, fixes are minutes apart). Null while fresh.
String? freshnessLabel(TripState trip, DateTime now) {
  if (trip.status != TripStatus.tracking) return null;
  if (trip.waitingForSignal) return 'Waiting for GPS…';
  final fixTime = trip.fixTime;
  if (fixTime == null) return null;
  final age = now.difference(fixTime);
  if (age.inMinutes < 2) return null;
  return age.inHours < 1
      ? 'Updated ${age.inMinutes} min ago'
      : 'Updated ${age.inHours} h ago';
}

String formatDistance(double meters) => meters >= 1000
    ? '${(meters / 1000).toStringAsFixed(1)} km'
    : '${meters.round()} m';
