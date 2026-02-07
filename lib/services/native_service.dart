import 'package:flutter/services.dart';

/// Parsed location received from a share intent.
class SharedLocation {
  final double lat;
  final double lng;
  final String? name;
  final String? sharedLink;

  const SharedLocation({
    required this.lat,
    required this.lng,
    this.name,
    this.sharedLink,
  });
}

/// Wraps MethodChannel calls to native Kotlin code.
class NativeService {
  static const _channel = MethodChannel('are_we_there/native');

  /// Check if the app was launched via a share intent.
  /// Returns parsed coordinates, or null for a normal launch.
  static Future<SharedLocation?> getSharedLocation() async {
    final result = await _channel.invokeMethod('getSharedLocation');
    if (result == null) return null;

    final map = Map<String, dynamic>.from(result as Map);
    return SharedLocation(
      lat: (map['lat'] as num).toDouble(),
      lng: (map['lng'] as num).toDouble(),
      name: map['name'] as String?,
      sharedLink: map['sharedLink'] as String?,
    );
  }

  /// Register a geofence via native Android API.
  static Future<String> addGeofence({
    required String id,
    required double lat,
    required double lng,
    required double radius,
  }) async {
    final result = await _channel.invokeMethod('addGeofence', {
      'id': id,
      'lat': lat,
      'lng': lng,
      'radius': radius,
    });
    return result.toString();
  }
}
