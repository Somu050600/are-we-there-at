/// Radius choices for the destination sheet: 50 m steps up close, coarser
/// steps farther out, so one slider spans 50 m to 100 km.
List<double> radiusStops(double maxMeters) => [
  for (var m = 50.0; m <= 1000; m += 50) m,
  for (var m = 1500.0; m <= 10000; m += 500) m,
  for (var m = 15000.0; m <= 50000; m += 5000) m,
  for (var m = 60000.0; m <= 100000; m += 10000) m,
].where((m) => m <= maxMeters).toList();

/// The stop closest to [meters], so a radius saved off the scale still shows.
int nearestStop(List<double> stops, double meters) {
  var best = 0;
  for (var i = 1; i < stops.length; i++) {
    if ((stops[i] - meters).abs() < (stops[best] - meters).abs()) best = i;
  }
  return best;
}

/// "750 m", "2.5 km", "25 km".
String formatRadius(double meters) {
  if (meters < 1000) return '${meters.round()} m';
  final km = meters / 1000;
  return km == km.roundToDouble()
      ? '${km.round()} km'
      : '${km.toStringAsFixed(1)} km';
}
