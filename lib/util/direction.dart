/// Angle to turn from [heading] to face [bearing], in (-180, 180].
/// Without a compass heading the angle is relative to north.
double relativeAngle(double bearing, double? heading) {
  final delta = ((bearing - (heading ?? 0) + 180) % 360 + 360) % 360 - 180;
  return delta == -180 ? 180 : delta;
}

/// New rotation in turns that reaches [targetDegrees] by the shortest path, so
/// an animated arrow never spins the long way round across north.
double nextTurns(double currentTurns, double targetDegrees) =>
    currentTurns + relativeAngle(targetDegrees, currentTurns * 360) / 360;

/// Reports when the arrow first lines up with the destination. It re-arms only
/// after drifting past a wider margin, so it does not buzz at the edge.
class AlignmentTracker {
  static const alignedDegrees = 10.0;
  static const releaseDegrees = 15.0;
  bool _aligned = false;

  /// Returns true when [angle] newly enters the aligned range.
  bool update(double angle) {
    final off = angle.abs();
    if (!_aligned && off <= alignedDegrees) {
      _aligned = true;
      return true;
    }
    if (_aligned && off > releaseDegrees) _aligned = false;
    return false;
  }
}

String arrivalClock(DateTime now, double? etaSeconds) {
  if (etaSeconds == null) return '—';
  final at = now.add(Duration(seconds: etaSeconds.round()));
  String two(int value) => value.toString().padLeft(2, '0');
  return '${two(at.hour)}:${two(at.minute)}';
}

String? formatRemaining(double? seconds) {
  if (seconds == null) return null;
  if (seconds < 60) return '<1 min';
  final minutes = (seconds / 60).round();
  if (minutes < 60) return '$minutes min';
  final rest = minutes % 60;
  return rest == 0 ? '${minutes ~/ 60} h' : '${minutes ~/ 60} h $rest min';
}
