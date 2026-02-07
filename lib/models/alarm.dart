/// Represents a saved location alarm.
class Alarm {
  final String id;
  final String? name;
  final double latitude;
  final double longitude;
  final double radius;
  final DateTime createdAt;
  final bool isActive;
  final String? sharedLink;

  const Alarm({
    required this.id,
    this.name,
    required this.latitude,
    required this.longitude,
    required this.radius,
    required this.createdAt,
    this.isActive = true,
    this.sharedLink,
  });

  Alarm copyWith({
    String? id,
    String? name,
    double? latitude,
    double? longitude,
    double? radius,
    DateTime? createdAt,
    bool? isActive,
    String? sharedLink,
  }) {
    return Alarm(
      id: id ?? this.id,
      name: name ?? this.name,
      latitude: latitude ?? this.latitude,
      longitude: longitude ?? this.longitude,
      radius: radius ?? this.radius,
      createdAt: createdAt ?? this.createdAt,
      isActive: isActive ?? this.isActive,
      sharedLink: sharedLink ?? this.sharedLink,
    );
  }
}
