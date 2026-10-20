import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/services/trip_state.dart';
import 'package:are_we_there_yet/util/relative_time.dart';

void main() {
  final now = DateTime(2026, 10, 20, 2);
  TripState tracking({Duration? age, bool waiting = false}) => TripState(
    status: TripStatus.tracking,
    fixTime: age == null ? null : now.subtract(age),
    waitingForSignal: waiting,
  );

  test('fresh fixes need no label', () {
    expect(freshnessLabel(tracking(), now), isNull);
    expect(
      freshnessLabel(tracking(age: const Duration(seconds: 90)), now),
      isNull,
    );
  });

  test('old fixes say how old they are', () {
    expect(
      freshnessLabel(tracking(age: const Duration(minutes: 5)), now),
      'Updated 5 min ago',
    );
    expect(
      freshnessLabel(tracking(age: const Duration(minutes: 130)), now),
      'Updated 2 h ago',
    );
  });

  test('no signal wins, and only tracking trips are labelled', () {
    expect(
      freshnessLabel(tracking(age: Duration.zero, waiting: true), now),
      'Waiting for GPS…',
    );
    expect(
      freshnessLabel(
        TripState(
          status: TripStatus.paused,
          fixTime: now.subtract(const Duration(hours: 1)),
        ),
        now,
      ),
      isNull,
    );
  });
}
