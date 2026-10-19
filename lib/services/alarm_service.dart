import 'dart:async';
import 'package:flutter/foundation.dart';
import '../db/alarm_database.dart';
import '../models/alarm.dart';
import 'native_service.dart';

/// SQLite is the source of truth. Serialize mutations so a permission dialog
/// cannot race a deletion, and compensate native changes if persistence fails.
class AlarmService {
  AlarmService(this.database);
  final AlarmDatabase database;
  final monitoringIssue = ValueNotifier<Object?>(null);
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

  Future<void> _register(Alarm alarm) async {
    await NativeService.addGeofence(
      id: alarm.id,
      lat: alarm.latitude,
      lng: alarm.longitude,
      radius: alarm.radius,
      name: alarm.name,
    );
  }

  void _recordIssue(Object error) {
    if (monitoringIssue.value == null) monitoringIssue.value = error;
  }

  void _replaceIssue(Object error) => monitoringIssue.value = error;

  /// The persistent warning holds the combined failure so the banner tells
  /// the user to retry, rather than showing only the raw native error.
  AlarmRecoveryException _recoveryFailed(Object original, Object recovery) {
    final failure = AlarmRecoveryException(original, recovery);
    _recordIssue(failure);
    return failure;
  }

  Future<void> create(Alarm alarm) => _run(() async {
    NativeService.validate(
      id: alarm.id,
      lat: alarm.latitude,
      lng: alarm.longitude,
      radius: alarm.radius,
    );
    await database.insertAlarm(alarm.copyWith(isActive: false));
    var registered = false;
    try {
      await _register(alarm);
      registered = true;
      await database.toggleAlarm(alarm.id, true);
    } catch (original) {
      // Retain the inactive row if unregistering fails, for reconciliation.
      if (registered) {
        try {
          await NativeService.removeGeofence(alarm.id);
        } catch (recovery) {
          throw _recoveryFailed(original, recovery);
        }
      }
      await database.deleteAlarm(alarm.id);
      rethrow;
    }
  });

  Future<void> setActive(Alarm alarm, bool active) => _run(() async {
    final current = await database.getAlarm(alarm.id);
    if (current == null || current.isActive == active) return;
    try {
      if (active) {
        await _register(current);
      } else {
        await NativeService.removeGeofence(current.id);
      }
    } catch (error) {
      _recordIssue(error);
      rethrow;
    }
    try {
      await database.toggleAlarm(current.id, active);
    } catch (original) {
      try {
        if (current.isActive) {
          await _register(current);
        } else {
          await NativeService.removeGeofence(current.id);
        }
      } catch (recovery) {
        throw _recoveryFailed(original, recovery);
      }
      rethrow;
    }
  });

  Future<void> delete(Alarm alarm) => _run(() async {
    final current = await database.getAlarm(alarm.id);
    if (current == null) return;
    try {
      await NativeService.removeGeofence(current.id);
    } catch (error) {
      _recordIssue(error);
      rethrow;
    }
    try {
      await database.deleteAlarm(current.id);
    } catch (original) {
      try {
        if (current.isActive) await _register(current);
      } catch (recovery) {
        throw _recoveryFailed(original, recovery);
      }
      rethrow;
    }
  });

  Future<void> synchronize() => _run(() async {
    try {
      final alarms = await database.getAllAlarms();
      await NativeService.syncGeofences(
        [
          for (final alarm in alarms.where((a) => a.isActive))
            {
              'id': alarm.id,
              'lat': alarm.latitude,
              'lng': alarm.longitude,
              'radius': alarm.radius,
              'name': alarm.name,
            },
        ],
        inactiveIds: alarms.where((a) => !a.isActive).map((a) => a.id).toList(),
      );
      monitoringIssue.value = null;
    } catch (error) {
      _replaceIssue(error);
      rethrow;
    }
  });
}

class AlarmRecoveryException implements Exception {
  const AlarmRecoveryException(this.originalError, this.recoveryError);
  final Object originalError;
  final Object recoveryError;
  @override
  String toString() =>
      'Alarm monitoring could not be confirmed. Tap Retry on the alarm list before relying on it.';
}
