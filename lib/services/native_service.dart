import 'package:flutter/services.dart';

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

class NativeService {
  static const _channel = MethodChannel('are_we_there/native');

  static void onSharedLocation(Future<void> Function()? callback) {
    _channel.setMethodCallHandler(
      callback == null
          ? null
          : (call) async {
              if (call.method == 'sharedLocationAvailable') await callback();
            },
    );
  }

  static Future<SharedLocation?> getSharedLocation() async {
    final result = await _channel.invokeMethod<Object?>('getSharedLocation');
    if (result == null) return null;
    if (result is! Map || result['lat'] is! num || result['lng'] is! num) {
      throw const FormatException('The shared location could not be read.');
    }
    final lat = (result['lat'] as num).toDouble();
    final lng = (result['lng'] as num).toDouble();
    if (!lat.isFinite || !lng.isFinite || lat.abs() > 90 || lng.abs() > 180) {
      throw const FormatException('The shared coordinates are invalid.');
    }
    if ((result['name'] != null && result['name'] is! String) ||
        (result['sharedLink'] != null && result['sharedLink'] is! String)) {
      throw const FormatException('The shared location could not be read.');
    }
    return SharedLocation(
      lat: lat,
      lng: lng,
      name: result['name'] as String?,
      sharedLink: result['sharedLink'] as String?,
    );
  }

  static void validate({
    required String id,
    required double lat,
    required double lng,
    required double radius,
  }) {
    if (id.trim().isEmpty ||
        id.length > 100 ||
        !lat.isFinite ||
        !lng.isFinite ||
        lat.abs() > 90 ||
        lng.abs() > 180 ||
        !radius.isFinite ||
        radius < 50 ||
        radius > 10000) {
      throw ArgumentError(
        'Choose valid coordinates and a radius between 50 m and 10 km.',
      );
    }
  }

  static Future<String> addGeofence({
    required String id,
    required double lat,
    required double lng,
    required double radius,
    String? name,
  }) async {
    validate(id: id, lat: lat, lng: lng, radius: radius);
    final result = await _channel.invokeMethod('addGeofence', {
      'id': id,
      'lat': lat,
      'lng': lng,
      'radius': radius,
      'name': name,
    });
    return result.toString();
  }

  static Future<void> removeGeofence(String id) =>
      _channel.invokeMethod('removeGeofence', {'id': id});
  static Future<void> syncGeofences(
    List<Map<String, Object?>> alarms, {
    List<String> inactiveIds = const [],
  }) => _channel.invokeMethod('syncGeofences', {
    'alarms': alarms,
    'inactiveIds': inactiveIds,
  });
  static Future<void> openSettings() => _channel.invokeMethod('openSettings');
}
