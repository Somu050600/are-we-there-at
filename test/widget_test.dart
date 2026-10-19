import 'dart:async';
import 'package:drift/drift.dart' show DatabaseConnection;
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/main.dart';
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
  late List<Object> shares;
  Object? saveError;
  Object? removeError;
  Completer<void>? saveGate;
  // Build fixtures inside each test's FakeAsync zone. Futures created in
  // setUp belong to the real zone, so pumping would never complete them.
  void init() {
    // Widget tests run in FakeAsync; drift's delayed stream cleanup would
    // otherwise keep close() waiting on a timer that never fires.
    db = AlarmDatabase(
      DatabaseConnection(
        NativeDatabase.memory(),
        closeStreamsSynchronously: true,
      ),
    );
    service = AlarmService(db);
    calls = [];
    shares = [];
    saveError = null;
    removeError = null;
    saveGate = null;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'getSharedLocation') {
        if (shares.isEmpty) return null;
        final value = shares.removeAt(0);
        if (value is Exception) throw value;
        return value;
      }
      if (call.method == 'addGeofence') {
        if (saveGate != null) await saveGate!.future;
        if (saveError != null) throw saveError!;
      }
      if (call.method == 'removeGeofence' && removeError != null) {
        throw removeError!;
      }
      return null;
    });
  }

  void appTest(String description, WidgetTesterCallback body) =>
      testWidgets(description, (tester) async {
        init();
        await body(tester);
      });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
  Future<void> mount(WidgetTester tester) async {
    await tester.pumpWidget(MyApp(service: service));
    await tester.pumpAndSettle();
  }

  Future<void> finish(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 4));
    // The database was first opened inside this test's FakeAsync zone, so it
    // must be closed here; closing it from tearDown never completes.
    await db.close();
  }

  Future<void> warmShare(WidgetTester tester) async {
    tester.binding.channelBuffers.push(
      'are_we_there/native',
      const StandardMethodCodec().encodeMethodCall(
        const MethodCall('sharedLocationAvailable'),
      ),
      (_) {},
    );
    await tester.pumpAndSettle();
  }

  void share([String name = 'Station']) => shares.add({
    'lat': 12.9716,
    'lng': 77.5946,
    'name': name,
    'sharedLink': 'geo:12.9716,77.5946',
  });
  Future<void> save(WidgetTester tester) async {
    await tester.ensureVisible(find.text('Save Alarm'));
    await tester.tap(find.text('Save Alarm'));
    await tester.pumpAndSettle();
  }

  Future<void> seed() => db.insertAlarm(
    Alarm(
      id: 'one',
      name: 'Home',
      latitude: 12,
      longitude: 77,
      radius: 200,
      createdAt: DateTime(2026),
    ),
  );

  appTest(
    'normal launch shows the real empty state without permission requests',
    (tester) async {
      await mount(tester);
      expect(find.text('No alarms yet'), findsOneWidget);
      expect(calls.where((c) => c.method == 'addGeofence'), isEmpty);
      await finish(tester);
    },
  );
  appTest('cold share saves once and returns home', (tester) async {
    share();
    await mount(tester);
    expect(find.text('Configure Alarm'), findsOneWidget);
    await save(tester);
    expect(find.text('Configure Alarm'), findsNothing);
    expect((await db.getAllAlarms()).single.name, 'Station');
    expect(calls.where((c) => c.method == 'addGeofence').length, 1);
    await finish(tester);
  });
  appTest('canceling a cold share returns to the list', (tester) async {
    share();
    await mount(tester);
    await tester.ensureVisible(find.text('Cancel'));
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(find.text('No alarms yet'), findsOneWidget);
    expect(await db.getAllAlarms(), isEmpty);
    await finish(tester);
  });
  appTest('warm share opens configuration without restart', (tester) async {
    await mount(tester);
    share();
    await warmShare(tester);
    expect(find.text('Configure Alarm'), findsOneWidget);
    await finish(tester);
  });
  appTest('queued share waits until the current configuration closes', (
    tester,
  ) async {
    share();
    await mount(tester);
    share('Office');
    await warmShare(tester);
    expect(find.text('Office'), findsNothing);
    await tester.ensureVisible(find.text('Cancel'));
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(find.text('Configure Alarm'), findsOneWidget);
    expect(find.text('Office'), findsWidgets);
    await finish(tester);
  });
  appTest('invalid share does not block the next valid destination', (
    tester,
  ) async {
    shares.add(
      PlatformException(
        code: 'INVALID_SHARE',
        message: 'Could not read location',
      ),
    );
    share();
    await mount(tester);
    expect(find.text('Configure Alarm'), findsOneWidget);
    expect(find.text('Station'), findsWidgets);
    await finish(tester);
  });
  appTest('more than ten invalid shares do not strand a valid share', (
    tester,
  ) async {
    for (var index = 0; index < 12; index++) {
      shares.add(
        PlatformException(
          code: 'INVALID_SHARE',
          message: 'Could not read location',
        ),
      );
    }
    share();

    await mount(tester);

    expect(find.text('Configure Alarm'), findsOneWidget);
    expect(find.text('Station'), findsWidgets);
    expect(
      calls.where((call) => call.method == 'getSharedLocation').length,
      13,
    );
    await finish(tester);
  });
  appTest('a share transport error stops draining the queue', (tester) async {
    shares.add(
      PlatformException(
        code: 'CHANNEL_FAILED',
        message: 'Could not contact Android',
      ),
    );
    share();

    await mount(tester);

    expect(find.text('Configure Alarm'), findsNothing);
    expect(find.text('Could not contact Android'), findsOneWidget);
    expect(calls.where((call) => call.method == 'getSharedLocation').length, 1);
    await finish(tester);
  });
  appTest('permission denial shows error and permits retry', (tester) async {
    share();
    saveError = PlatformException(
      code: 'PERMISSION_DENIED',
      message: 'Enable precise location',
    );
    await mount(tester);
    await save(tester);
    expect(find.text('Enable precise location'), findsOneWidget);
    expect(find.text('Save Alarm'), findsOneWidget);
    expect(await db.getAllAlarms(), isEmpty);
    saveError = null;
    await save(tester);
    expect((await db.getAllAlarms()).single.isActive, isTrue);
    await finish(tester);
  });
  appTest('small screen with keyboard and large text has no overflow', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 280);
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    share();
    await mount(tester);
    await tester.tap(find.byType(TextField));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Save Alarm'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await finish(tester);
  });
  appTest('toggle updates native monitoring and persistence', (tester) async {
    await seed();
    await mount(tester);
    calls.clear();
    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    expect((await db.getAllAlarms()).single.isActive, isFalse);
    expect(calls.single.method, 'removeGeofence');
    await finish(tester);
  });
  appTest('failed toggle leaves the switch enabled and shows an error', (
    tester,
  ) async {
    await seed();
    await mount(tester);
    removeError = PlatformException(
      code: 'FAILED',
      message: 'Could not stop monitoring',
    );
    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    expect(tester.widget<Switch>(find.byType(Switch)).value, isTrue);
    expect(find.text('Could not stop monitoring'), findsOneWidget);
    await finish(tester);
  });
  appTest('retry clears the monitoring warning after a failed removal', (
    tester,
  ) async {
    await seed();
    await mount(tester);
    removeError = PlatformException(
      code: 'FAILED',
      message: 'Could not stop monitoring',
    );
    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    expect(find.textContaining('Monitoring needs attention'), findsOneWidget);

    removeError = null;
    calls.clear();
    await tester.tap(find.widgetWithText(TextButton, 'Retry'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Monitoring needs attention'), findsNothing);
    expect(calls.where((call) => call.method == 'syncGeofences').length, 1);
    await finish(tester);
  });
  appTest('resume retries monitoring and clears the warning', (tester) async {
    await seed();
    await mount(tester);
    removeError = PlatformException(
      code: 'FAILED',
      message: 'Could not stop monitoring',
    );
    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    expect(find.textContaining('Monitoring needs attention'), findsOneWidget);

    removeError = null;
    calls.clear();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();

    expect(find.textContaining('Monitoring needs attention'), findsNothing);
    expect(calls.where((call) => call.method == 'syncGeofences').length, 1);
    await finish(tester);
  });
  appTest('swipe delete removes the record without a dismissal exception', (
    tester,
  ) async {
    await seed();
    await mount(tester);
    await tester.drag(find.byType(Dismissible), const Offset(-700, 0));
    await tester.pumpAndSettle();
    expect(await db.getAllAlarms(), isEmpty);
    expect(find.text('No alarms yet'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await finish(tester);
  });
  appTest('failed delete leaves the card visible and retryable', (
    tester,
  ) async {
    await seed();
    await mount(tester);
    removeError = PlatformException(
      code: 'FAILED',
      message: 'Could not stop monitoring',
    );
    await tester.drag(find.byType(Dismissible), const Offset(-700, 0));
    await tester.pumpAndSettle();
    expect(find.text('Home'), findsOneWidget);
    expect((await db.getAllAlarms()).single.id, 'one');
    await finish(tester);
  });
  appTest('save is disabled while Android permission request is pending', (
    tester,
  ) async {
    share();
    await mount(tester);
    saveGate = Completer<void>();
    await tester.ensureVisible(find.text('Save Alarm'));
    await tester.tap(find.text('Save Alarm'));
    await tester.pump();
    expect(find.text('Saving...'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(
            find.byWidgetPredicate((widget) => widget is FilledButton),
          )
          .onPressed,
      isNull,
    );
    saveGate!.complete();
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'addGeofence').length, 1);
    await finish(tester);
  });
}
