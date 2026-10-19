import 'dart:async';
import 'package:drift/drift.dart' show DatabaseConnection;
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/main.dart';
import 'package:are_we_there_yet/db/alarm_database.dart';
import 'package:are_we_there_yet/models/destination.dart';
import 'package:are_we_there_yet/services/trip_controller.dart';
import 'package:are_we_there_yet/services/trip_state.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('are_we_there/native');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final now = DateTime(2026, 10, 19, 16);
  late AlarmDatabase db;
  late TripController controller;
  late List<MethodCall> calls;
  late List<Object> shares;
  Object? startError;
  Completer<void>? startGate;
  late bool fullScreenAllowed;

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
    // Trip and alarm state are set directly: init's channel round trips are
    // covered by trip_controller_test and stall under FakeAsync.
    controller = TripController(
      db,
      states: const Stream<TripState>.empty(),
      now: () => now,
    );
    calls = [];
    shares = [];
    startError = null;
    startGate = null;
    fullScreenAllowed = true;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'getSharedLocation':
          if (shares.isEmpty) return null;
          final value = shares.removeAt(0);
          if (value is Exception) throw value;
          return value;
        case 'startTrip':
          if (startGate != null) await startGate!.future;
          if (startError != null) throw startError!;
          return null;
        case 'getTripState':
          return {'state': 'idle'};
        case 'getAlarmSettings':
          return {'fullScreen': true, 'fullScreenAllowed': fullScreenAllowed};
        case 'setFullScreenAlarm':
          return {
            'fullScreen': call.arguments['enabled'],
            'fullScreenAllowed': fullScreenAllowed,
          };
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
    await tester.pumpWidget(MyApp(controller: controller, now: () => now));
    await tester.pumpAndSettle();
  }

  Future<void> finish(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 4));
    // The database was first opened inside this test's FakeAsync zone, so it
    // must be closed here; closing it from tearDown never completes.
    await controller.dispose();
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

  Future<void> trip(WidgetTester tester, Map<String, Object?> state) async {
    controller.trip.value = TripState.fromMap(state);
    // Not pumpAndSettle: the bar animates until the first location fix.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
  }

  void share([String name = 'Station']) => shares.add({
    'lat': 12.9716,
    'lng': 77.5946,
    'name': name,
    'sharedLink': 'geo:12.9716,77.5946',
  });

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder);
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  Future<void> seed({
    String id = 'one',
    String name = 'Home',
    DateTime? used,
  }) => db.insertDestination(
    Destination(
      id: id,
      name: name,
      latitude: 12,
      longitude: 77,
      radius: 200,
      createdAt: DateTime(2026),
      lastUsedAt: used,
    ),
  );

  Iterable<String> methods() => calls.map((c) => c.method);

  appTest('first launch shows the empty list and alarm toggle', (tester) async {
    await mount(tester);
    expect(find.text('Saved Destinations'), findsOneWidget);
    expect(find.text('No saved destinations yet.'), findsOneWidget);
    expect(find.text('Full-screen alarm'), findsOneWidget);
    expect(find.text('Last Trip'), findsNothing);
    expect(methods(), isNot(contains('startTrip')));
    await finish(tester);
  });

  appTest('a cold share opens the sheet and Start begins the trip', (
    tester,
  ) async {
    share();
    await mount(tester);
    expect(find.text('New Destination'), findsOneWidget);
    // Shown as the value and as the name field's hint.
    expect(find.text('12.97160, 77.59460'), findsWidgets);
    expect(find.widgetWithText(TextField, 'Station'), findsOneWidget);
    await tap(tester, find.text('Start'));
    expect(find.text('New Destination'), findsNothing);
    final saved = (await db.getAllDestinations()).single;
    expect(saved.name, 'Station');
    expect(saved.lastUsedAt, now);
    expect(methods().where((m) => m == 'startTrip').length, 1);
    await finish(tester);
  });

  appTest('Save stores the destination without starting a trip', (
    tester,
  ) async {
    share();
    await mount(tester);
    await tap(tester, find.widgetWithText(FilledButton, 'Save'));
    expect((await db.getAllDestinations()).single.lastUsedAt, isNull);
    expect(methods(), isNot(contains('startTrip')));
    expect(find.text('New Destination'), findsNothing);
    expect(find.text('Station'), findsOneWidget);
    await finish(tester);
  });

  appTest('Cancel stores nothing', (tester) async {
    share();
    await mount(tester);
    await tap(tester, find.text('Cancel'));
    expect(find.text('New Destination'), findsNothing);
    expect(await db.getAllDestinations(), isEmpty);
    await finish(tester);
  });

  appTest('the sheet saves the chosen name, icon and radius', (tester) async {
    share();
    await mount(tester);
    await tester.enterText(find.byType(TextField), 'Majestic');
    await tap(tester, find.bySemanticsLabel('Shopping'));
    await tester.drag(find.byType(Slider), const Offset(200, 0));
    await tester.pumpAndSettle();
    await tap(tester, find.widgetWithText(FilledButton, 'Save'));
    final saved = (await db.getAllDestinations()).single;
    expect(saved.name, 'Majestic');
    expect(saved.icon, DestinationIcon.shopping);
    expect(saved.radius, greaterThan(200));
    await finish(tester);
  });

  appTest('a warm share opens the sheet without a restart', (tester) async {
    await mount(tester);
    share();
    await warmShare(tester);
    expect(find.text('New Destination'), findsOneWidget);
    await finish(tester);
  });

  appTest('a queued share waits until the current sheet closes', (
    tester,
  ) async {
    share();
    await mount(tester);
    share('Office');
    await warmShare(tester);
    expect(find.widgetWithText(TextField, 'Office'), findsNothing);
    await tap(tester, find.text('Cancel'));
    expect(find.widgetWithText(TextField, 'Office'), findsOneWidget);
    await finish(tester);
  });

  appTest('an invalid share does not block the next valid one', (tester) async {
    shares.add(
      PlatformException(code: 'INVALID_SHARE', message: 'Could not read'),
    );
    share();
    await mount(tester);
    expect(find.widgetWithText(TextField, 'Station'), findsOneWidget);
    await finish(tester);
  });

  appTest('more than ten invalid shares do not strand a valid share', (
    tester,
  ) async {
    for (var index = 0; index < 12; index++) {
      shares.add(
        PlatformException(code: 'INVALID_SHARE', message: 'Could not read'),
      );
    }
    share();
    await mount(tester);
    expect(find.widgetWithText(TextField, 'Station'), findsOneWidget);
    expect(methods().where((m) => m == 'getSharedLocation').length, 13);
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
    expect(find.text('New Destination'), findsNothing);
    expect(find.text('Could not contact Android'), findsOneWidget);
    expect(methods().where((m) => m == 'getSharedLocation').length, 1);
    await finish(tester);
  });

  appTest('a refused permission keeps the sheet open, saved, and retryable', (
    tester,
  ) async {
    share();
    startError = PlatformException(
      code: 'PERMISSION_DENIED',
      message: 'Allow precise location to track your trip.',
    );
    await mount(tester);
    await tap(tester, find.text('Start'));
    expect(
      find.text('Allow precise location to track your trip.'),
      findsOneWidget,
    );
    expect(find.text('New Destination'), findsOneWidget);
    expect((await db.getAllDestinations()).single.lastUsedAt, isNull);
    startError = null;
    await tap(tester, find.text('Start'));
    expect(find.text('New Destination'), findsNothing);
    final all = await db.getAllDestinations();
    expect(all.single.lastUsedAt, now);
    await finish(tester);
  });

  appTest('buttons are disabled while Android is starting the trip', (
    tester,
  ) async {
    share();
    await mount(tester);
    startGate = Completer<void>();
    await tester.tap(find.text('Start'));
    await tester.pump();
    expect(find.text('Starting…'), findsOneWidget);
    expect(
      tester.widget<OutlinedButton>(find.byType(OutlinedButton)).onPressed,
      isNull,
    );
    startGate!.complete();
    await tester.pumpAndSettle();
    expect(methods().where((m) => m == 'startTrip').length, 1);
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
    await seed(used: now.subtract(const Duration(hours: 4)));
    share();
    await mount(tester);
    await tester.tap(find.byType(TextField));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Start'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await finish(tester);
  });

  appTest('the last trip card restarts the most recent destination', (
    tester,
  ) async {
    await seed(
      id: 'old',
      name: 'Old',
      used: now.subtract(const Duration(days: 2)),
    );
    await seed(
      id: 'recent',
      name: 'Aurva Bangalore',
      used: now.subtract(const Duration(hours: 4)),
    );
    await mount(tester);
    expect(find.text('Last Trip'), findsOneWidget);
    expect(find.text('Last: 4h ago'), findsOneWidget);
    expect(find.text('Last: 2d ago'), findsOneWidget);
    await tap(tester, find.text('Re-Start'));
    final start = calls.lastWhere((c) => c.method == 'startTrip');
    expect(start.arguments['id'], 'recent');
    await finish(tester);
  });

  appTest('the play button starts that destination', (tester) async {
    await seed();
    await mount(tester);
    expect(find.text('Never used'), findsOneWidget);
    await tap(tester, find.byTooltip('Start trip to Home'));
    expect(
      calls.lastWhere((c) => c.method == 'startTrip').arguments['id'],
      'one',
    );
    expect((await db.getDestination('one'))!.lastUsedAt, now);
    await finish(tester);
  });

  appTest('an active trip shows live progress with pause and stop', (
    tester,
  ) async {
    await seed(used: now);
    await mount(tester);
    await trip(tester, {
      'state': 'tracking',
      'destinationId': 'one',
      'distanceMeters': 4230.0,
      'progress': 0.634,
    });
    expect(find.text('Travelling to'), findsOneWidget);
    expect(find.text('4.2 km remaining (63%)'), findsOneWidget);
    expect(find.text('Trip in progress'), findsOneWidget);
    await tap(tester, find.text('Pause'));
    expect(methods().last, 'pauseTrip');
    await trip(tester, {'state': 'paused', 'destinationId': 'one'});
    await tap(tester, find.text('Resume'));
    expect(methods().last, 'resumeTrip');
    await tap(tester, find.widgetWithText(FilledButton, 'Stop'));
    expect(methods().last, 'stopTrip');
    await finish(tester);
  });

  appTest('before the first fix the card says calculating', (tester) async {
    await seed();
    await mount(tester);
    await trip(tester, {'state': 'tracking', 'destinationId': 'one'});
    expect(find.text('Calculating…'), findsOneWidget);
    await finish(tester);
  });

  appTest('tapping a destination edits it in the sheet', (tester) async {
    await seed();
    await mount(tester);
    await tap(tester, find.text('Home'));
    expect(find.text('Edit Destination'), findsOneWidget);
    await tester.enterText(find.byType(TextField), 'Office');
    await tap(tester, find.bySemanticsLabel('Office'));
    await tap(tester, find.widgetWithText(FilledButton, 'Save'));
    final updated = (await db.getDestination('one'))!;
    expect(updated.name, 'Office');
    expect(updated.icon, DestinationIcon.office);
    expect(find.text('Edit Destination'), findsNothing);
    await finish(tester);
  });

  appTest('deleting the active destination stops its trip', (tester) async {
    await seed(used: now);
    await mount(tester);
    await trip(tester, {
      'state': 'tracking',
      'destinationId': 'one',
      'distanceMeters': 900.0,
      'progress': 0.5,
    });
    await tap(tester, find.text('Home').last);
    await tap(tester, find.byTooltip('Delete destination'));
    expect(methods().last, 'stopTrip');
    expect(await db.getAllDestinations(), isEmpty);
    expect(find.text('No saved destinations yet.'), findsOneWidget);
    await finish(tester);
  });

  appTest('the full-screen toggle saves the preference', (tester) async {
    await mount(tester);
    await tap(tester, find.byType(Switch));
    final call = calls.lastWhere((c) => c.method == 'setFullScreenAlarm');
    expect(call.arguments, {'enabled': false});
    expect(tester.widget<Switch>(find.byType(Switch)).value, isFalse);
    await finish(tester);
  });

  appTest('blocked full-screen alerts link to Android settings', (
    tester,
  ) async {
    fullScreenAllowed = false;
    controller.alarmSettings.value = const AlarmSettings(
      enabled: true,
      allowed: false,
    );
    await mount(tester);
    await tap(tester, find.text('Allow in Settings'));
    expect(methods().last, 'openFullScreenSettings');
    await finish(tester);
  });
}
