import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/db/alarm_database.dart';
import 'package:are_we_there_yet/models/alarm.dart';

void main() {
  late AlarmDatabase db;
  setUp(() => db = AlarmDatabase(NativeDatabase.memory()));
  tearDown(() => db.close());
  test('retains all metadata and lists newest alarms first', () async {
    for (final day in [1, 3, 2]) {
      await db.insertAlarm(
        Alarm(
          id: '$day',
          name: 'Stop $day',
          latitude: 12.9,
          longitude: 77.5,
          radius: 300,
          createdAt: DateTime(2026, 1, day),
          sharedLink: 'geo:12.9,77.5',
          isActive: day != 2,
        ),
      );
    }
    final rows = await db.watchAllAlarms().first;
    expect(rows.map((a) => a.id), ['3', '2', '1']);
    expect(rows[1].isActive, isFalse);
    expect(rows[0].sharedLink, 'geo:12.9,77.5');
  });
  test(
    'version one upgrade preserves existing alarms and adds shared link',
    () async {
      await db.close();
      db = AlarmDatabase(
        NativeDatabase.memory(
          setup: (sqlite) {
            sqlite.execute(
              'CREATE TABLE alarms (id TEXT NOT NULL PRIMARY KEY, name TEXT, latitude REAL NOT NULL, longitude REAL NOT NULL, radius REAL NOT NULL, created_at INTEGER NOT NULL, is_active INTEGER NOT NULL DEFAULT 1)',
            );
            sqlite.execute(
              "INSERT INTO alarms VALUES ('old', 'Home', 12.9, 77.5, 200, 1700000000, 1)",
            );
            sqlite.execute('PRAGMA user_version = 1');
          },
        ),
      );
      final alarm = (await db.getAllAlarms()).single;
      expect(alarm.id, 'old');
      expect(alarm.name, 'Home');
      expect(alarm.isActive, isTrue);
      expect(alarm.sharedLink, isNull);
    },
  );
}
