import 'dart:async';
import 'package:drift/native.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/db/alarm_database.dart';
import 'package:are_we_there_yet/models/alarm.dart';
import 'package:are_we_there_yet/services/alarm_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('are_we_there/native');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late AlarmDatabase db;
  late AlarmService service;
  late List<MethodCall> calls;
  // Channel encoding recreates PlatformException, so identity cannot hold.
  Matcher isPlatformError(String code) =>
      isA<PlatformException>().having((e) => e.code, 'code', code);
  Alarm alarm({String id = 'station', bool active = true}) => Alarm(
    id: id,
    name: 'Station',
    latitude: 12.9716,
    longitude: 77.5946,
    radius: 200,
    createdAt: DateTime(2026, 1, 1),
    isActive: active,
  );
  setUp(() {
    db = AlarmDatabase(NativeDatabase.memory());
    service = AlarmService(db);
    calls = [];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return 'ok';
    });
  });
  tearDown(() async {
    messenger.setMockMethodCallHandler(channel, null);
    await db.close();
  });

  test(
    'saving registers the named destination and persists it as active',
    () async {
      await service.create(alarm());
      final saved = (await db.getAllAlarms()).single;
      expect(saved.isActive, isTrue);
      expect(saved.latitude, 12.9716);
      expect(calls.single.method, 'addGeofence');
      expect(calls.single.arguments['name'], 'Station');
    },
  );
  test('denied permission leaves no saved phantom alarm', () async {
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => throw PlatformException(code: 'PERMISSION_DENIED'),
    );
    await expectLater(
      service.create(alarm()),
      throwsA(isA<PlatformException>()),
    );
    expect(await db.getAllAlarms(), isEmpty);
  });
  test('database activation failure unregisters the new geofence', () async {
    await db.customStatement(
      "CREATE TRIGGER reject_activation BEFORE UPDATE ON alarms WHEN NEW.is_active = 1 BEGIN SELECT RAISE(FAIL, 'disk failure'); END",
    );
    await expectLater(service.create(alarm()), throwsA(anything));
    expect(calls.map((c) => c.method), ['addGeofence', 'removeGeofence']);
    expect(await db.getAllAlarms(), isEmpty);
  });
  test(
    'activation and cleanup failures retain an inactive row for recovery',
    () async {
      await db.customStatement(
        "CREATE TRIGGER reject_activation BEFORE UPDATE ON alarms WHEN NEW.is_active = 1 BEGIN SELECT RAISE(FAIL, 'disk failure'); END",
      );
      final cleanupError = PlatformException(
        code: 'REMOVE_FAILED',
        message: 'Could not clean up monitoring',
      );
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'removeGeofence') throw cleanupError;
        return 'ok';
      });

      Object? thrown;
      try {
        await service.create(alarm());
      } catch (error) {
        thrown = error;
      }

      expect(thrown, isA<AlarmRecoveryException>());
      final recovery = thrown! as AlarmRecoveryException;
      expect(recovery.originalError, isNot(isA<PlatformException>()));
      expect(recovery.recoveryError, isPlatformError('REMOVE_FAILED'));
      expect(service.monitoringIssue.value, same(recovery));
      expect((await db.getAllAlarms()).single.isActive, isFalse);
      expect(calls.map((c) => c.method), ['addGeofence', 'removeGeofence']);
    },
  );
  test('disable removes monitoring and enable registers again', () async {
    await db.insertAlarm(alarm());
    await service.setActive(alarm(), false);
    expect((await db.getAllAlarms()).single.isActive, isFalse);
    await service.setActive(alarm(active: false), true);
    expect((await db.getAllAlarms()).single.isActive, isTrue);
    expect(calls.map((c) => c.method), ['removeGeofence', 'addGeofence']);
  });
  test('failed native removal keeps the database record', () async {
    await db.insertAlarm(alarm());
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => throw PlatformException(code: 'REMOVE_FAILED'),
    );
    await expectLater(
      service.delete(alarm()),
      throwsA(isA<PlatformException>()),
    );
    expect((await db.getAllAlarms()).single.isActive, isTrue);
    expect(service.monitoringIssue.value, isNotNull);
  });
  test('database deletion failure restores monitoring', () async {
    await db.insertAlarm(alarm());
    await db.customStatement(
      "CREATE TRIGGER reject_delete BEFORE DELETE ON alarms BEGIN SELECT RAISE(FAIL, 'disk failure'); END",
    );
    await expectLater(service.delete(alarm()), throwsA(anything));
    expect((await db.getAllAlarms()).single.isActive, isTrue);
    expect(calls.map((c) => c.method), ['removeGeofence', 'addGeofence']);
  });
  test('database toggle failure restores the previous native state', () async {
    await db.insertAlarm(alarm());
    await db.customStatement(
      "CREATE TRIGGER reject_toggle BEFORE UPDATE ON alarms BEGIN SELECT RAISE(FAIL, 'disk failure'); END",
    );
    await expectLater(service.setActive(alarm(), false), throwsA(anything));
    expect((await db.getAllAlarms()).single.isActive, isTrue);
    expect(calls.map((c) => c.method), ['removeGeofence', 'addGeofence']);
  });
  test('toggle and compensation failures report both errors', () async {
    await db.insertAlarm(alarm());
    await db.customStatement(
      "CREATE TRIGGER reject_toggle BEFORE UPDATE ON alarms BEGIN SELECT RAISE(FAIL, 'disk failure'); END",
    );
    final recoveryError = PlatformException(
      code: 'ADD_FAILED',
      message: 'Could not restore monitoring',
    );
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'addGeofence') throw recoveryError;
      return 'ok';
    });

    Object? thrown;
    try {
      await service.setActive(alarm(), false);
    } catch (error) {
      thrown = error;
    }

    expect(thrown, isA<AlarmRecoveryException>());
    final recovery = thrown! as AlarmRecoveryException;
    expect(recovery.originalError, isNot(isA<PlatformException>()));
    expect(recovery.recoveryError, isPlatformError('ADD_FAILED'));
    expect(service.monitoringIssue.value, same(recovery));
    expect((await db.getAllAlarms()).single.isActive, isTrue);
  });
  test('deleting removes monitoring and the row', () async {
    await db.insertAlarm(alarm());
    await service.delete(alarm());
    expect(await db.getAllAlarms(), isEmpty);
    expect(calls.single.method, 'removeGeofence');
  });
  test('queued deletion waits for activation then removes it', () async {
    await db.insertAlarm(alarm(active: false));
    final gate = Completer<void>();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'addGeofence') await gate.future;
      return 'ok';
    });
    final enable = service.setActive(alarm(active: false), true);
    final remove = service.delete(alarm(active: false));
    gate.complete();
    await Future.wait([enable, remove]);
    expect(calls.map((c) => c.method), ['addGeofence', 'removeGeofence']);
    expect(await db.getAllAlarms(), isEmpty);
  });
  test(
    'startup sync includes active destinations and legacy disabled ids',
    () async {
      await db.insertAlarm(alarm());
      await db.insertAlarm(alarm(id: 'off', active: false));
      await service.synchronize();
      expect(calls.single.method, 'syncGeofences');
      expect(
        (calls.single.arguments['alarms'] as List).single['id'],
        'station',
      );
      expect(calls.single.arguments['inactiveIds'], ['off']);
    },
  );
  test('only a successful synchronization clears a monitoring issue', () async {
    await db.insertAlarm(alarm());
    final removeError = PlatformException(code: 'REMOVE_FAILED');
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'removeGeofence') throw removeError;
      return null;
    });
    await expectLater(
      service.delete(alarm()),
      throwsA(isPlatformError('REMOVE_FAILED')),
    );
    final originalIssue = service.monitoringIssue.value;

    await service.setActive(alarm(), true);
    expect(service.monitoringIssue.value, same(originalIssue));

    final syncError = PlatformException(code: 'SYNC_FAILED');
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'syncGeofences') throw syncError;
      return null;
    });
    await expectLater(
      service.synchronize(),
      throwsA(isPlatformError('SYNC_FAILED')),
    );
    expect(service.monitoringIssue.value, isPlatformError('SYNC_FAILED'));

    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return null;
    });
    await service.synchronize();
    expect(service.monitoringIssue.value, isNull);
  });
  test('invalid coordinates do not touch storage or Android', () async {
    for (final lat in [double.nan, double.infinity, -91.0, 91.0]) {
      await expectLater(
        service.create(alarm().copyWith(latitude: lat)),
        throwsArgumentError,
      );
    }
    expect(calls, isEmpty);
    expect(await db.getAllAlarms(), isEmpty);
  });
  test('a failed operation does not poison the queue', () async {
    var fail = true;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (fail) {
        fail = false;
        throw PlatformException(code: 'FAILED');
      }
      return 'ok';
    });
    await expectLater(
      service.create(alarm()),
      throwsA(isA<PlatformException>()),
    );
    await service.create(alarm());
    expect((await db.getAllAlarms()).single.id, 'station');
  });
  test('stale UI actions cannot recreate a deleted record', () async {
    await service.setActive(alarm(), true);
    await service.delete(alarm());
    expect(calls, isEmpty);
    expect(await db.getAllAlarms(), isEmpty);
  });
  test('duplicate create cannot register over an existing id', () async {
    await db.insertAlarm(alarm());
    await expectLater(
      service.create(alarm().copyWith(latitude: 13)),
      throwsA(anything),
    );
    expect(calls, isEmpty);
    expect((await db.getAllAlarms()).single.latitude, 12.9716);
  });
}
