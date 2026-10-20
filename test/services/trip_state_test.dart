import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/services/trip_state.dart';

void main() {
  test('parses every native state', () {
    final tracking = TripState.fromMap({
      'state': 'tracking',
      'destinationId': 'a',
      'name': 'Station',
      'distanceMeters': 4230.5,
      'progress': 0.63,
    });
    expect(tracking.status, TripStatus.tracking);
    expect(tracking.destinationId, 'a');
    expect(tracking.distanceMeters, 4230.5);
    expect(tracking.progress, 0.63);
    expect(tracking.isActive, isTrue);

    expect(TripState.fromMap({'state': 'paused'}).isActive, isTrue);
    expect(TripState.fromMap({'state': 'arrived'}).isActive, isFalse);
    expect(TripState.fromMap({'state': 'idle'}).isActive, isFalse);
  });

  test('tolerates missing or malformed values', () {
    final state = TripState.fromMap({'state': 'tracking', 'progress': 'x'});
    expect(state.progress, isNull);
    expect(state.distanceMeters, isNull);
    expect(TripState.fromMap(null).status, TripStatus.idle);
    expect(TripState.fromMap({'state': 'flying'}).status, TripStatus.idle);
    expect(TripState.fromMap({'state': 'tracking', 'progress': 3}).progress, 1);
  });

  test('alarm settings default to enabled and allowed', () {
    final settings = AlarmSettings.fromMap({'fullScreen': false});
    expect(settings.enabled, isFalse);
    expect(settings.allowed, isTrue);
    expect(settings.batteryUnrestricted, isTrue);
    expect(settings.maxRadiusMeters, 10000);
    expect(
      AlarmSettings.fromMap({'maxRadiusMeters': 25000}).maxRadiusMeters,
      25000,
    );
    expect(
      AlarmSettings.fromMap({'maxRadiusMeters': 12345}).maxRadiusMeters,
      10000,
    );
    expect(
      AlarmSettings.fromMap({'batteryUnrestricted': false}).batteryUnrestricted,
      isFalse,
    );
  });

  test('parses position, speed, bearing and eta', () {
    final state = TripState.fromMap({
      'state': 'tracking',
      'lat': 12.99,
      'lng': 77.57,
      'speedMps': 11.5,
      'bearingDegrees': 182.0,
      'etaSeconds': 420.0,
    });
    expect(state.lat, 12.99);
    expect(state.lng, 77.57);
    expect(state.speedMps, 11.5);
    expect(state.bearingDegrees, 182.0);
    expect(state.etaSeconds, 420.0);
    expect(state.fixTime, isNull);
    expect(state.waitingForSignal, isFalse);
  });

  test('parses the last fix time and signal state', () {
    final state = TripState.fromMap({
      'state': 'tracking',
      'fixTimeMillis': 1760000000000,
      'waitingForSignal': true,
    });
    expect(state.fixTime, DateTime.fromMillisecondsSinceEpoch(1760000000000));
    expect(state.waitingForSignal, isTrue);
  });
}
