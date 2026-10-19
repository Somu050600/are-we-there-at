enum TripStatus { idle, tracking, paused, arrived }

/// Latest trip report from the Android trip service.
class TripState {
  const TripState({
    required this.status,
    this.destinationId,
    this.name,
    this.distanceMeters,
    this.progress,
  });

  static const idle = TripState(status: TripStatus.idle);

  final TripStatus status;
  final String? destinationId;
  final String? name;
  final double? distanceMeters;

  /// 0..1 share of the starting distance covered; null before the first fix.
  final double? progress;

  bool get isActive =>
      status == TripStatus.tracking || status == TripStatus.paused;

  factory TripState.fromMap(Object? raw) {
    if (raw is! Map) return idle;
    final status = TripStatus.values.firstWhere(
      (value) => value.name == raw['state'],
      orElse: () => TripStatus.idle,
    );
    double? number(Object? value) =>
        value is num && value.isFinite ? value.toDouble() : null;
    return TripState(
      status: status,
      destinationId: raw['destinationId'] as String?,
      name: raw['name'] as String?,
      distanceMeters: number(raw['distanceMeters']),
      progress: number(raw['progress'])?.clamp(0, 1).toDouble(),
    );
  }
}

class AlarmSettings {
  const AlarmSettings({required this.enabled, required this.allowed});

  static const defaults = AlarmSettings(enabled: true, allowed: true);

  /// The user's full-screen alarm toggle.
  final bool enabled;

  /// Whether Android currently permits full-screen alerts for the app.
  final bool allowed;

  factory AlarmSettings.fromMap(Object? raw) {
    if (raw is! Map) return defaults;
    return AlarmSettings(
      enabled: raw['fullScreen'] as bool? ?? true,
      allowed: raw['fullScreenAllowed'] as bool? ?? true,
    );
  }
}
