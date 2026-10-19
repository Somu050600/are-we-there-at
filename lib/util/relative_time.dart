/// "Last: 4h ago" style label for when a destination was last used.
String lastUsedLabel(DateTime? lastUsed, DateTime now) {
  if (lastUsed == null) return 'Never used';
  final elapsed = now.difference(lastUsed);
  if (elapsed.inMinutes < 1) return 'Last: just now';
  if (elapsed.inHours < 1) return 'Last: ${elapsed.inMinutes}m ago';
  if (elapsed.inDays < 1) return 'Last: ${elapsed.inHours}h ago';
  return 'Last: ${elapsed.inDays}d ago';
}

String formatDistance(double meters) => meters >= 1000
    ? '${(meters / 1000).toStringAsFixed(1)} km'
    : '${meters.round()} m';
