import 'package:flutter/services.dart';
import '../models/destination.dart';
import 'trip_state.dart';

class SharedLocation {
  final double lat;
  final double lng;
  final String? name;
  final String? sharedLink;

  /// Found by looking up the shared place's name or address, not read from
  /// coordinates in the share.
  final bool approximate;
  const SharedLocation({
    required this.lat,
    required this.lng,
    this.name,
    this.sharedLink,
    this.approximate = false,
  });
}

class NativeService {
  /// The largest radius Android accepts; a setting limits the sheet further.
  static const maxRadius = 100000.0;

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

  /// Whether a share is waiting, without reading it (reading takes a moment).
  static Future<bool> hasSharedLocation() async =>
      await _channel.invokeMethod<bool>('hasSharedLocation') ?? false;

  static Future<SharedLocation?> getSharedLocation() async => _sharedLocation(
    await _channel.invokeMethod<Object?>('getSharedLocation'),
  );

  /// Reads again the share that failed with `UNREACHABLE`; null once it is
  /// gone (the app was closed in between).
  static Future<SharedLocation?> retrySharedLocation() async => _sharedLocation(
    await _channel.invokeMethod<Object?>('retrySharedLocation'),
  );

  static SharedLocation? _sharedLocation(Object? result) {
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
      approximate: result['approximate'] == true,
    );
  }

  static void validate({
    required String id,
    required double lat,
    required double lng,
    required double radius,
    int? alertMinutes,
  }) {
    if (id.trim().isEmpty ||
        id.length > 100 ||
        !lat.isFinite ||
        !lng.isFinite ||
        lat.abs() > 90 ||
        lng.abs() > 180 ||
        !radius.isFinite ||
        radius < 50 ||
        radius > maxRadius) {
      throw ArgumentError(
        'Choose valid coordinates and a radius between 50 m and 100 km.',
      );
    }
    if (alertMinutes != null && (alertMinutes < 1 || alertMinutes > 120)) {
      throw ArgumentError('Choose an alert between 1 and 120 minutes.');
    }
  }

  static Future<void> openSettings() => _channel.invokeMethod('openSettings');

  static Future<void> startTrip(Destination destination) async {
    validate(
      id: destination.id,
      lat: destination.latitude,
      lng: destination.longitude,
      radius: destination.radius,
      alertMinutes: destination.alertMinutes,
    );
    await _channel.invokeMethod('startTrip', {
      'id': destination.id,
      'name': destination.label,
      'lat': destination.latitude,
      'lng': destination.longitude,
      'radius': destination.radius,
      'alertMinutes': destination.alertMinutes,
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

  static Future<AlarmSettings> setMaxRadius(double meters) async =>
      AlarmSettings.fromMap(
        await _channel.invokeMethod<Object?>('setMaxRadius', {
          'meters': meters,
        }),
      );

  static Future<AlarmSettings> setVibrateOnly(bool enabled) async =>
      AlarmSettings.fromMap(
        await _channel.invokeMethod<Object?>('setVibrateOnly', {
          'enabled': enabled,
        }),
      );

  /// The phone maker's family, lowercased (e.g. "xiaomi" for Redmi).
  static Future<String> getPhoneMaker() async =>
      await _channel.invokeMethod<String>('getPhoneMaker') ?? '';

  /// The maker's own background-app settings, or the app's details page.
  static Future<void> openPhoneMakerSettings() =>
      _channel.invokeMethod('openPhoneMakerSettings');

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

  /// Asks Android to stop battery-optimizing the app.
  static Future<void> requestBatteryExemption() =>
      _channel.invokeMethod('requestBatteryExemption');
}
