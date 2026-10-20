import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/util/radius_scale.dart';

void main() {
  test('stops are fine up close and coarse far out', () {
    final stops = radiusStops(100000);
    expect(stops.first, 50);
    expect(stops.last, 100000);
    expect(stops.take(3), [50, 100, 150]);
    expect(stops, containsAllInOrder([1000, 1500, 10000, 15000, 50000, 60000]));
    expect(radiusStops(10000).last, 10000);
    expect(radiusStops(25000).last, 25000);
  });

  test('a radius off the scale shows at its nearest stop', () {
    final stops = radiusStops(10000);
    expect(stops[nearestStop(stops, 230)], 250);
    expect(stops[nearestStop(stops, 30000)], 10000);
  });

  test('radii read as metres or kilometres', () {
    expect(formatRadius(750), '750 m');
    expect(formatRadius(2500), '2.5 km');
    expect(formatRadius(25000), '25 km');
  });
}
