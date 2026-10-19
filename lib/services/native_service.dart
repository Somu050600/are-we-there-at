import 'package:flutter/services.dart';
import '../models/destination.dart';
import 'trip_state.dart';

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
  static const _trips = EventChannel('are_we_there/trip');
  static const _headings = EventChannel('are_we_there/heading');

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

  static Future<void> openSettings() => _channel.invokeMethod('openSettings');

  static Future<void> startTrip(Destination destination) async {
    validate(
      id: destination.id,
      lat: destination.latitude,
      lng: destination.longitude,
      radius: destination.radius,
    );
    await _channel.invokeMethod('startTrip', {
      'id': destination.id,
      'name': destination.label,
      'lat': destination.latitude,
      'lng': destination.longitude,
      'radius': destination.radius,
    });
  }

  static Future<void> pauseTrip() => _channel.invokeMethod('pauseTrip');
  static Future<void> resumeTrip() => _channel.invokeMethod('resumeTrip');
  static Future<void> stopTrip() => _channel.invokeMethod('stopTrip');

  static Future<TripState> getTripState() async =>
      TripState.fromMap(await _channel.invokeMethod<Object?>('getTripState'));

  /// Live trip reports; emits the current state on listen.
  static Stream<TripState> tripStates() =>
      _trips.receiveBroadcastStream().map(TripState.fromMap);

  /// Removes geofences left by versions before trips. Safe to repeat.
  static Future<void> removeLegacyGeofences() =>
      _channel.invokeMethod('removeLegacyGeofences');

  static Future<AlarmSettings> getAlarmSettings() async =>
      AlarmSettings.fromMap(
        await _channel.invokeMethod<Object?>('getAlarmSettings'),
      );

  static Future<AlarmSettings> setFullScreenAlarm(bool enabled) async =>
      AlarmSettings.fromMap(
        await _channel.invokeMethod<Object?>('setFullScreenAlarm', {
          'enabled': enabled,
        }),
      );

  /// 1 s location updates while the direction screen is visible.
  static Future<void> setFastUpdates(bool enabled) =>
      _channel.invokeMethod('setFastUpdates', {'enabled': enabled});

  /// Device heading in degrees clockwise from north; errors with `NO_COMPASS`.
  static Stream<double> headings() => _headings.receiveBroadcastStream().map(
    (value) => (value as num).toDouble(),
  );

  static Future<void> openInMaps(Destination destination) =>
      _channel.invokeMethod('openInMaps', {
        'lat': destination.latitude,
        'lng': destination.longitude,
        'name': destination.label,
      });

  static Future<void> openFullScreenSettings() =>
      _channel.invokeMethod('openFullScreenSettings');
}
