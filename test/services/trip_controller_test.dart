import 'dart:async';
import 'package:drift/native.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/db/alarm_database.dart';
import 'package:are_we_there_yet/models/destination.dart';
import 'package:are_we_there_yet/services/trip_controller.dart';
import 'package:are_we_there_yet/services/trip_state.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('are_we_there/native');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late AlarmDatabase db;
  late StreamController<TripState> states;
  late TripController controller;
  late List<MethodCall> calls;
  Object? startError;
  final now = DateTime(2026, 10, 19, 12);

  Destination destination({String id = 'a', double lat = 12.97}) => Destination(
    id: id,
    name: 'Station',
    latitude: lat,
    longitude: 77.57,
    radius: 200,
    createdAt: DateTime(2026),
  );

  setUp(() {
    db = AlarmDatabase(NativeDatabase.memory());
    states = StreamController<TripState>.broadcast();
    controller = TripController(db, states: states.stream, now: () => now);
    calls = [];
    startError = null;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'startTrip':
          if (startError != null) throw startError!;
          return null;
        case 'getTripState':
          return {'state': 'idle'};
        case 'getAlarmSettings':
        case 'setFullScreenAlarm':
          return {
            'fullScreen': call.arguments?['enabled'] ?? true,
            'fullScreenAllowed': false,
          };
      }
      return null;
    });
  });
  tearDown(() async {
    messenger.setMockMethodCallHandler(channel, null);
    await controller.dispose();
    await states.close();
    await db.close();
  });

  List<String> methods() => calls.map((c) => c.method).toList();

  test(
    'init reads trip and alarm state and removes legacy geofences',
    () async {
      await controller.init();
      expect(
        methods(),
        containsAll([
          'getTripState',
          'getAlarmSettings',
          'removeLegacyGeofences',
        ]),
      );
      expect(controller.alarmSettings.value.allowed, isFalse);
    },
  );

  test('state stream updates the trip notifier', () async {
    await controller.init();
    states.add(
      TripState.fromMap({
        'state': 'tracking',
        'destinationId': 'a',
        'progress': 0.5,
      }),
    );
    await Future<void>.delayed(Duration.zero);
    expect(controller.trip.value.status, TripStatus.tracking);
    expect(controller.trip.value.progress, 0.5);
  });

  test('save and start persists, starts the trip and marks it used', () async {
    await controller.saveAndStart(destination());
    expect(methods(), ['startTrip']);
    expect(calls.single.arguments, {
      'id': 'a',
      'name': 'Station',
      'lat': 12.97,
      'lng': 77.57,
      'radius': 200.0,
    });
    expect((await db.getDestination('a'))!.lastUsedAt, now);
  });

  test('a failed start keeps the saved destination unused', () async {
    startError = PlatformException(code: 'PERMISSION_DENIED', message: 'no');
    await expectLater(
      controller.saveAndStart(destination()),
      throwsA(isA<PlatformException>()),
    );
    final saved = (await db.getDestination('a'))!;
    expect(saved.lastUsedAt, isNull);
  });

  test('save updates an existing destination instead of duplicating', () async {
    await controller.save(destination());
    await controller.save(
      destination().copyWith(name: 'Office', icon: DestinationIcon.office),
    );
    final all = await db.getAllDestinations();
    expect(all.single.name, 'Office');
    expect(all.single.icon, DestinationIcon.office);
    expect(calls, isEmpty);
  });

  test('deleting the active destination stops the trip first', () async {
    await controller.save(destination());
    await controller.init();
    states.add(TripState.fromMap({'state': 'tracking', 'destinationId': 'a'}));
    await Future<void>.delayed(Duration.zero);
    calls.clear();
    await controller.delete(destination());
    expect(methods(), ['stopTrip']);
    expect(await db.getAllDestinations(), isEmpty);
  });

  test('deleting another destination leaves the trip alone', () async {
    await controller.save(destination(id: 'b'));
    await controller.init();
    states.add(TripState.fromMap({'state': 'tracking', 'destinationId': 'a'}));
    await Future<void>.delayed(Duration.zero);
    calls.clear();
    await controller.delete(destination(id: 'b'));
    expect(calls, isEmpty);
  });

  test('invalid coordinates never reach Android or storage', () async {
    await expectLater(
      controller.saveAndStart(destination(lat: 91)),
      throwsArgumentError,
    );
    expect(calls, isEmpty);
    expect(await db.getAllDestinations(), isEmpty);
  });

  test('pause, resume, stop and full-screen calls go to Android', () async {
    await controller.pause();
    await controller.resume();
    await controller.stop();
    await controller.setFullScreen(false);
    expect(methods(), [
      'pauseTrip',
      'resumeTrip',
      'stopTrip',
      'setFullScreenAlarm',
    ]);
    expect(controller.alarmSettings.value.enabled, isFalse);
  });

  test('a failed operation does not block later ones', () async {
    startError = PlatformException(code: 'FAILED');
    await expectLater(controller.start(destination()), throwsA(anything));
    startError = null;
    await controller.save(destination());
    await controller.start(destination());
    expect((await db.getDestination('a'))!.lastUsedAt, now);
  });
}
