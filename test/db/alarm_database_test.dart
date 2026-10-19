import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/db/alarm_database.dart';
import 'package:are_we_there_yet/models/destination.dart';

const _v1Table =
    'CREATE TABLE alarms (id TEXT NOT NULL PRIMARY KEY, name TEXT, latitude REAL NOT NULL, longitude REAL NOT NULL, radius REAL NOT NULL, created_at INTEGER NOT NULL, is_active INTEGER NOT NULL DEFAULT 1)';

void main() {
  late AlarmDatabase db;
  setUp(() => db = AlarmDatabase(NativeDatabase.memory()));
  tearDown(() => db.close());

  Destination destination(String id, {int day = 1, String? name}) =>
      Destination(
        id: id,
        name: name ?? 'Stop $id',
        latitude: 12.9,
        longitude: 77.5,
        radius: 300,
        createdAt: DateTime(2026, 1, day),
        sharedLink: 'geo:12.9,77.5',
      );

  AlarmDatabase upgraded(List<String> statements) => AlarmDatabase(
    NativeDatabase.memory(
      setup: (sqlite) {
        for (final statement in statements) {
          sqlite.execute(statement);
        }
      },
    ),
  );

  test('stores every field and defaults to the navigation icon', () async {
    await db.insertDestination(destination('a'));
    final saved = (await db.getAllDestinations()).single;
    expect(saved.name, 'Stop a');
    expect(saved.sharedLink, 'geo:12.9,77.5');
    expect(saved.icon, DestinationIcon.navigation);
    expect(saved.lastUsedAt, isNull);
  });

  test('destinations are ordered by last use, then newest created', () async {
    for (final day in [1, 3, 2]) {
      await db.insertDestination(destination('$day', day: day));
    }
    await db.markUsed('1', DateTime(2026, 2, 1));
    final rows = await db.watchDestinations().first;
    expect(rows.map((d) => d.id), ['1', '3', '2']);
  });

  test('update changes name, icon and radius; delete removes', () async {
    await db.insertDestination(destination('a'));
    await db.updateDestination(
      destination(
        'a',
        name: 'Office',
      ).copyWith(icon: DestinationIcon.office, radius: 500),
    );
    final updated = (await db.getDestination('a'))!;
    expect(updated.name, 'Office');
    expect(updated.icon, DestinationIcon.office);
    expect(updated.radius, 500);
    await db.deleteDestination('a');
    expect(await db.getAllDestinations(), isEmpty);
  });

  test('version one upgrade keeps rows and adds the new columns', () async {
    await db.close();
    db = upgraded([
      _v1Table,
      "INSERT INTO alarms VALUES ('old', 'Home', 12.9, 77.5, 200, 1700000000, 1)",
      'PRAGMA user_version = 1',
    ]);
    final old = (await db.getAllDestinations()).single;
    expect(old.id, 'old');
    expect(old.name, 'Home');
    expect(old.sharedLink, isNull);
    expect(old.icon, DestinationIcon.navigation);
  });

  test('version two upgrade keeps rows and shared links', () async {
    await db.close();
    db = upgraded([
      _v1Table,
      'ALTER TABLE alarms ADD COLUMN shared_link TEXT',
      "INSERT INTO alarms VALUES ('v2', 'Station', 12.97, 77.57, 250, 1700000000, 0, 'geo:12.97,77.57')",
      'PRAGMA user_version = 2',
    ]);
    final old = (await db.getAllDestinations()).single;
    expect(old.name, 'Station');
    expect(old.radius, 250);
    expect(old.sharedLink, 'geo:12.97,77.57');
    expect(old.icon, DestinationIcon.navigation);
    expect(old.lastUsedAt, isNull);
  });

  test('unknown stored icon names fall back to navigation', () async {
    await db.insertDestination(destination('a'));
    await db.customStatement("UPDATE alarms SET icon = 'rocket'");
    expect((await db.getDestination('a'))!.icon, DestinationIcon.navigation);
  });
}
