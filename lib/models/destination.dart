/// Icons a destination can show; stored by [name] in SQLite.
enum DestinationIcon {
  navigation,
  home,
  work,
  office,
  shopping;

  static DestinationIcon fromName(String? value) => DestinationIcon.values
      .firstWhere((icon) => icon.name == value, orElse: () => navigation);
}

/// Choices for the time alert, in minutes.
const alertMinuteChoices = [5, 10, 15, 30];

/// A saved place a trip can be started to.
class Destination {
  final String id;
  final String? name;
  final double latitude;
  final double longitude;
  final double radius;
  final DateTime createdAt;
  final String? sharedLink;
  final DestinationIcon icon;
  final DateTime? lastUsedAt;

  /// Also ring this many minutes before arriving; null for the radius only.
  final int? alertMinutes;

  const Destination({
    required this.id,
    this.name,
    required this.latitude,
    required this.longitude,
    required this.radius,
    required this.createdAt,
    this.sharedLink,
    this.icon = DestinationIcon.navigation,
    this.lastUsedAt,
    this.alertMinutes,
  });

  String get coordinates =>
      '${latitude.toStringAsFixed(5)}, ${longitude.toStringAsFixed(5)}';

  /// The user's name for the place, or its coordinates.
  String get label {
    final trimmed = name?.trim();
    return trimmed == null || trimmed.isEmpty ? coordinates : trimmed;
  }

  Destination copyWith({
    String? name,
    double? latitude,
    double? longitude,
    double? radius,
    DestinationIcon? icon,
    DateTime? lastUsedAt,
    int? Function()? alertMinutes,
  }) {
    return Destination(
      id: id,
      name: name ?? this.name,
      latitude: latitude ?? this.latitude,
      longitude: longitude ?? this.longitude,
      radius: radius ?? this.radius,
      createdAt: createdAt,
      sharedLink: sharedLink,
      icon: icon ?? this.icon,
      lastUsedAt: lastUsedAt ?? this.lastUsedAt,
      alertMinutes: alertMinutes == null ? this.alertMinutes : alertMinutes(),
    );
  }
}
