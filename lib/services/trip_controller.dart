import 'dart:async';
import 'package:flutter/foundation.dart';
import '../db/alarm_database.dart';
import '../models/destination.dart';
import 'native_service.dart';
import 'trip_state.dart';

/// Saved destinations live in SQLite; the active trip lives in Android.
/// Operations are serialized so, for example, a delete cannot race a start.
class TripController {
  TripController(
    this.database, {
    Stream<TripState>? states,
    DateTime Function()? now,
  }) : _states = states,
       _now = now ?? DateTime.now;

  final AlarmDatabase database;
  final Stream<TripState>? _states;
  final DateTime Function() _now;
  final trip = ValueNotifier<TripState>(TripState.idle);
  final alarmSettings = ValueNotifier<AlarmSettings>(AlarmSettings.defaults);
  StreamSubscription<TripState>? _subscription;
  Future<void> _tail = Future.value();

  Future<T> _run<T>(Future<T> Function() operation) {
    final result = Completer<T>();
    _tail = _tail.then((_) async {
      try {
        result.complete(await operation());
      } catch (error, stack) {
        result.completeError(error, stack);
      }
    });
    return result.future;
  }

  Future<void> init() async {
    try {
      trip.value = await NativeService.getTripState();
    } catch (_) {
      // The stream below delivers the state once Android answers.
    }
    _subscription ??= (_states ?? NativeService.tripStates()).listen(
      (state) => trip.value = state,
      onError: (Object _) {},
    );
    try {
      alarmSettings.value = await NativeService.getAlarmSettings();
    } catch (_) {
      // Keep defaults; the toggle still works.
    }
    try {
      await NativeService.removeLegacyGeofences();
    } catch (_) {
      // Retried on the next launch; Android also retries after app updates.
    }
  }

  Future<void> save(Destination destination) => _run(() => _save(destination));

  /// Saves first, so a refused permission still keeps the destination.
  Future<void> saveAndStart(Destination destination) => _run(() async {
    await _save(destination);
    await _start(destination);
  });

  Future<void> start(Destination destination) =>
      _run(() => _start(destination));

  Future<void> pause() => _run(NativeService.pauseTrip);
  Future<void> resume() => _run(NativeService.resumeTrip);
  Future<void> stop() => _run(NativeService.stopTrip);

  Future<void> delete(Destination destination) => _run(() async {
    final current = trip.value;
    if (current.isActive && current.destinationId == destination.id) {
      await NativeService.stopTrip();
    }
    await database.deleteDestination(destination.id);
  });

  Future<void> setFullScreen(bool enabled) => _run(() async {
    alarmSettings.value = await NativeService.setFullScreenAlarm(enabled);
  });

  Future<void> setMaxRadius(double meters) => _run(() async {
    alarmSettings.value = await NativeService.setMaxRadius(meters);
  });

  Future<void> setGoogleSearch(bool enabled) => _run(() async {
    alarmSettings.value = await NativeService.setGoogleSearch(enabled);
  });

  Future<void> setPlacesSearchFallback(bool enabled) => _run(() async {
    alarmSettings.value = await NativeService.setPlacesSearchFallback(enabled);
  });

  Future<void> setVibrateOnly(bool enabled) => _run(() async {
    alarmSettings.value = await NativeService.setVibrateOnly(enabled);
  });

  Future<void> refreshAlarmSettings() => _run(() async {
    alarmSettings.value = await NativeService.getAlarmSettings();
  });

  Future<void> _save(Destination destination) async {
    NativeService.validate(
      id: destination.id,
      lat: destination.latitude,
      lng: destination.longitude,
      radius: destination.radius,
      alertMinutes: destination.alertMinutes,
    );
    if (await database.getDestination(destination.id) == null) {
      await database.insertDestination(destination);
    } else {
      await database.updateDestination(destination);
    }
  }

  Future<void> _start(Destination destination) async {
    await NativeService.startTrip(destination);
    await database.markUsed(destination.id, _now());
  }

  Future<void> dispose() async {
    await _subscription?.cancel();
    _subscription = null;
  }
}
