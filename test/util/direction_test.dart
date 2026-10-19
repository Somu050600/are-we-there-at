import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/util/direction.dart';

void main() {
  test('relative angle is the shortest turn from heading to bearing', () {
    expect(relativeAngle(90, 0), 90);
    expect(relativeAngle(10, 350), 20);
    expect(relativeAngle(350, 10), -20);
    expect(relativeAngle(180, 0), 180);
    expect(relativeAngle(0, 180), 180);
  });

  test('without a heading the arrow is relative to north', () {
    expect(relativeAngle(135, null), 135);
  });

  test('alignment fires once on entering 10 degrees and re-arms past 15', () {
    final tracker = AlignmentTracker();
    expect(tracker.update(30), isFalse);
    expect(tracker.update(9), isTrue);
    expect(tracker.update(-5), isFalse);
    expect(tracker.update(12), isFalse);
    expect(tracker.update(8), isFalse);
    expect(tracker.update(16), isFalse);
    expect(tracker.update(-9), isTrue);
  });

  test('turns accumulate along the shortest path', () {
    expect(nextTurns(0, 350), closeTo(-10 / 360, 1e-9));
    expect(nextTurns(-10 / 360, 10), closeTo(10 / 360, 1e-9));
    expect(nextTurns(1, 90), closeTo(1.25, 1e-9));
  });

  test('arrival clock and remaining time', () {
    final now = DateTime(2026, 10, 19, 17, 24);
    expect(arrivalClock(now, 23 * 60), '17:47');
    expect(arrivalClock(now, null), '—');
    expect(formatRemaining(20), '<1 min');
    expect(formatRemaining(23 * 60 + 10), '23 min');
    expect(formatRemaining(65 * 60), '1 h 5 min');
    expect(formatRemaining(null), isNull);
  });
}
