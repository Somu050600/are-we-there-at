enum TripStatus { idle, tracking, paused, arrived }

/// Latest trip report from the Android trip service.
class TripState {
  const TripState({
    required this.status,
    this.destinationId,
    this.name,
    this.distanceMeters,
    this.progress,
    this.lat,
    this.lng,
    this.speedMps,
    this.bearingDegrees,
    this.etaSeconds,
    this.fixTime,
    this.waitingForSignal = false,
  });

  static const idle = TripState(status: TripStatus.idle);

  final TripStatus status;
  final String? destinationId;
  final String? name;
  final double? distanceMeters;

  /// 0..1 share of the starting distance covered; null before the first fix.
  final double? progress;

  /// Last location fix.
  final double? lat;
  final double? lng;
  final double? speedMps;

  /// From the last fix to the destination, clockwise from north.
  final double? bearingDegrees;
  final double? etaSeconds;

  /// When the last fix was taken.
  final DateTime? fixTime;

  /// Location is unavailable right now (tunnel, no sky view).
  final bool waitingForSignal;

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
      lat: number(raw['lat']),
      lng: number(raw['lng']),
      speedMps: number(raw['speedMps']),
      bearingDegrees: number(raw['bearingDegrees']),
      etaSeconds: number(raw['etaSeconds']),
      fixTime: switch (number(raw['fixTimeMillis'])) {
        final millis? => DateTime.fromMillisecondsSinceEpoch(millis.round()),
        null => null,
      },
      waitingForSignal: raw['waitingForSignal'] == true,
    );
  }
}

class AlarmSettings {
  const AlarmSettings({
    required this.enabled,
    required this.allowed,
    this.batteryUnrestricted = true,
    this.vibrateOnly = false,
    this.maxRadiusMeters = defaultMaxRadius,
  });

  static const defaultMaxRadius = 10000.0;

  /// Choices for [maxRadiusMeters].
  static const maxRadiusChoices = [10000.0, 25000.0, 50000.0, 100000.0];

  static const defaults = AlarmSettings(enabled: true, allowed: true);

  /// The user's full-screen alarm toggle.
  final bool enabled;

  /// Whether Android currently permits full-screen alerts for the app.
  final bool allowed;

  /// Whether the app is exempt from battery optimization, which can otherwise
  /// stop tracking on long trips.
  final bool batteryUnrestricted;

  /// Alarms vibrate without sound.
  final bool vibrateOnly;

  /// The largest radius the destination sheet offers.
  final double maxRadiusMeters;

  factory AlarmSettings.fromMap(Object? raw) {
    if (raw is! Map) return defaults;
    return AlarmSettings(
      enabled: raw['fullScreen'] as bool? ?? true,
      allowed: raw['fullScreenAllowed'] as bool? ?? true,
      batteryUnrestricted: raw['batteryUnrestricted'] as bool? ?? true,
      vibrateOnly: raw['vibrateOnly'] as bool? ?? false,
      maxRadiusMeters: switch (raw['maxRadiusMeters']) {
        final num meters when maxRadiusChoices.contains(meters.toDouble()) =>
          meters.toDouble(),
        _ => defaultMaxRadius,
      },
    );
  }
}
