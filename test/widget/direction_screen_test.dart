import 'dart:async';
import 'package:drift/drift.dart' show DatabaseConnection;
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/db/alarm_database.dart';
import 'package:are_we_there_yet/models/destination.dart';
import 'package:are_we_there_yet/screens/direction_screen.dart';
import 'package:are_we_there_yet/services/trip_controller.dart';
import 'package:are_we_there_yet/services/trip_state.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('are_we_there/native');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final now = DateTime(2026, 10, 19, 17, 24);
  final destination = Destination(
    id: 'a',
    name: 'Aurva Bangalore',
    latitude: 12.91,
    longitude: 77.65,
    radius: 200,
    createdAt: DateTime(2026),
  );
  late AlarmDatabase db;
  late TripController controller;
  late StreamController<double> headings;
  late List<String> haptics;
  late List<Object?> fastUpdates;

  void init() {
    db = AlarmDatabase(
      DatabaseConnection(
        NativeDatabase.memory(),
        closeStreamsSynchronously: true,
      ),
    );
    controller = TripController(
      db,
      states: const Stream<TripState>.empty(),
      now: () => now,
    );
    // Broadcast, so the screen can cancel and listen again.
    headings = StreamController<double>.broadcast();
    haptics = [];
    fastUpdates = [];
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'setFastUpdates') {
        fastUpdates.add((call.arguments as Map)['enabled']);
      }
      return null;
    });
    messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'HapticFeedback.vibrate') {
        haptics.add('${call.arguments}');
      }
      return null;
    });
  }

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    messenger.setMockMethodCallHandler(SystemChannels.platform, null);
  });

  void screenTest(String description, WidgetTesterCallback body) =>
      testWidgets(description, (tester) async {
        init();
        await body(tester);
        await tester.pumpWidget(const SizedBox());
        await controller.dispose();
        await db.close();
      });

  Future<void> show(WidgetTester tester, Map<String, Object?> state) async {
    controller.trip.value = TripState.fromMap(state);
    await tester.pumpWidget(
      MaterialApp(
        home: DirectionScreen(
          controller: controller,
          destination: destination,
          headings: headings.stream,
          now: () => now,
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 400));
  }

  double turns(WidgetTester tester) =>
      tester.widget<AnimatedRotation>(find.byType(AnimatedRotation)).turns;

  Map<String, Object?> tracking({double bearing = 90, double? eta = 1380}) => {
    'state': 'tracking',
    'destinationId': 'a',
    'distanceMeters': 4230.0,
    'progress': 0.4,
    'bearingDegrees': bearing,
    'etaSeconds': eta,
  };

  screenTest('shows arrival time, distance and time left', (tester) async {
    await show(tester, tracking());
    expect(find.text('17:47'), findsOneWidget);
    expect(find.text('Aurva Bangalore'), findsOneWidget);
    expect(find.text('4.2 km · 23 min'), findsOneWidget);
  });

  screenTest('without an eta the clock shows a dash', (tester) async {
    await show(tester, tracking(eta: null));
    expect(find.text('—'), findsOneWidget);
    expect(find.text('4.2 km'), findsOneWidget);
  });

  screenTest('the arrow turns with the phone heading', (tester) async {
    await show(tester, tracking(bearing: 90));
    expect(turns(tester), closeTo(0.25, 1e-9));
    headings.add(60);
    await tester.pump(const Duration(milliseconds: 400));
    expect(turns(tester), closeTo(30 / 360, 1e-9));
    headings.add(100);
    await tester.pump(const Duration(milliseconds: 400));
    expect(turns(tester), closeTo(-10 / 360, 1e-9));
  });

  screenTest('lining up with the destination ticks once', (tester) async {
    await show(tester, tracking(bearing: 90));
    headings.add(40);
    await tester.pump();
    expect(haptics, isEmpty);
    headings.add(85);
    await tester.pump();
    headings.add(92);
    await tester.pump();
    expect(haptics, ['HapticFeedbackType.lightImpact']);
  });

  screenTest('without a compass the arrow points from north', (tester) async {
    await show(tester, tracking(bearing: 135));
    headings.addError(PlatformException(code: 'NO_COMPASS'));
    await tester.pump(const Duration(milliseconds: 400));
    expect(
      find.text('Compass unavailable · arrow points from north'),
      findsOneWidget,
    );
    expect(turns(tester), closeTo(135 / 360, 1e-9));
  });

  screenTest('before the first fix it says calculating', (tester) async {
    await show(tester, {'state': 'tracking', 'destinationId': 'a'});
    expect(find.text('Calculating…'), findsOneWidget);
    expect(find.byType(AnimatedRotation), findsNothing);
  });

  screenTest('a paused trip is labelled and has no arrival time', (
    tester,
  ) async {
    await show(tester, {...tracking(), 'state': 'paused'});
    expect(find.text('Paused'), findsOneWidget);
    expect(find.text('—'), findsOneWidget);
  });

  screenTest('arrival replaces the arrow', (tester) async {
    await show(tester, tracking());
    controller.trip.value = TripState.fromMap({
      'state': 'arrived',
      'destinationId': 'a',
    });
    await tester.pump();
    expect(find.text("You've arrived"), findsOneWidget);
    expect(find.text('Done'), findsOneWidget);
  });

  screenTest('hiding the app stops fast updates and the compass', (
    tester,
  ) async {
    await show(tester, tracking(bearing: 90));
    expect(fastUpdates, [true]);
    expect(headings.hasListener, isTrue);

    for (final state in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
    ]) {
      tester.binding.handleAppLifecycleStateChanged(state);
    }
    await tester.pump();
    expect(fastUpdates, [true, false]);
    expect(headings.hasListener, isFalse);

    for (final state in [
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ]) {
      tester.binding.handleAppLifecycleStateChanged(state);
    }
    await tester.pump();
    expect(fastUpdates, [true, false, true]);
    expect(headings.hasListener, isTrue);
    headings.add(60);
    await tester.pump(const Duration(milliseconds: 400));
    expect(turns(tester), closeTo(30 / 360, 1e-9));

    await tester.pumpWidget(const SizedBox());
    expect(fastUpdates, [true, false, true, false]);
  });

  screenTest('a compass that never reports is treated as unavailable', (
    tester,
  ) async {
    await show(tester, tracking(bearing: 135));
    expect(
      find.text('Compass unavailable · arrow points from north'),
      findsNothing,
    );
    await tester.pump(const Duration(seconds: 3));
    expect(
      find.text('Compass unavailable · arrow points from north'),
      findsOneWidget,
    );
    headings.add(135);
    await tester.pump(const Duration(milliseconds: 400));
    expect(
      find.text('Compass unavailable · arrow points from north'),
      findsNothing,
    );
    expect(turns(tester), closeTo(0, 1e-9));
  });
}
