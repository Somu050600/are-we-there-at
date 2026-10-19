import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/models/destination.dart';
import 'package:are_we_there_yet/services/native_service.dart';

Destination _trip({
  String id = 'a',
  double lat = 12,
  double lng = 77,
  double radius = 200,
}) => Destination(
  id: id,
  name: 'Place',
  latitude: lat,
  longitude: lng,
  radius: radius,
  createdAt: DateTime(2026),
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('are_we_there/native');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
  test('rejects invalid coordinates received from Android', () async {
    for (final lat in [double.nan, double.infinity, -91.0, 91.0]) {
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => {
          'lat': lat,
          'lng': 77.5,
          'name': null,
          'sharedLink': null,
        },
      );
      await expectLater(
        NativeService.getSharedLocation(),
        throwsFormatException,
      );
    }
  });
  test('rejects malformed native payloads', () async {
    for (final value in [
      42,
      {'lat': '12', 'lng': 77},
      {'lat': 12, 'lng': 77, 'name': 3},
    ]) {
      messenger.setMockMethodCallHandler(channel, (_) async => value);
      await expectLater(
        NativeService.getSharedLocation(),
        throwsFormatException,
      );
    }
  });
  test('invalid radii never reach Android', () async {
    var called = false;
    messenger.setMockMethodCallHandler(channel, (_) async {
      called = true;
      return 'ok';
    });
    for (final radius in [
      -1.0,
      0.0,
      49.0,
      10001.0,
      double.nan,
      double.infinity,
    ]) {
      await expectLater(
        NativeService.startTrip(_trip(radius: radius)),
        throwsArgumentError,
      );
    }
    expect(called, isFalse);
  });
  test('allows the exact coordinate and radius boundaries', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return 'ok';
    });
    await NativeService.startTrip(
      _trip(id: 'north', lat: 90, lng: 180, radius: 50),
    );
    await NativeService.startTrip(
      _trip(id: 'south', lat: -90, lng: -180, radius: 10000),
    );
    expect(calls.map((c) => c.arguments['radius']), [50.0, 10000.0]);
  });
  test('preserves platform permission failures', () async {
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => throw PlatformException(code: 'PERMISSION_DENIED'),
    );
    await expectLater(
      NativeService.startTrip(_trip()),
      throwsA(isA<PlatformException>()),
    );
  });
}
