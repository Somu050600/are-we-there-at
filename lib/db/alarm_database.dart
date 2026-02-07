import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../models/alarm.dart' as model;

part 'alarm_database.g.dart';

/// Drift table definition for alarms.
/// Generated row class is named AlarmEntry to avoid clashing with model.Alarm.
@DataClassName('AlarmEntry')
class Alarms extends Table {
  TextColumn get id => text()();
  TextColumn get name => text().nullable()();
  RealColumn get latitude => real()();
  RealColumn get longitude => real()();
  RealColumn get radius => real()();
  DateTimeColumn get createdAt => dateTime()();
  BoolColumn get isActive => boolean().withDefault(const Constant(true))();
  TextColumn get sharedLink => text().nullable()();

  @override
  Set<Column> get primaryKey => {id};
}

@DriftDatabase(tables: [Alarms])
class AlarmDatabase extends _$AlarmDatabase {
  AlarmDatabase._internal(super.e);

  static AlarmDatabase? _instance;

  /// Singleton accessor.
  static AlarmDatabase get instance {
    _instance ??= AlarmDatabase._internal(_openConnection());
    return _instance!;
  }

  @override
  int get schemaVersion => 2;

  @override
  MigrationStrategy get migration => MigrationStrategy(
        onUpgrade: (migrator, from, to) async {
          if (from < 2) {
            await migrator.addColumn(alarms, alarms.sharedLink);
          }
        },
      );

  // ── Queries ──────────────────────────────────────────────────

  /// Reactive stream of all alarms, newest first.
  Stream<List<model.Alarm>> watchAllAlarms() {
    final query = select(alarms)
      ..orderBy([(t) => OrderingTerm.desc(t.createdAt)]);
    return query.watch().map(
      (rows) => rows.map(_toModel).toList(),
    );
  }

  /// Insert a new alarm.
  Future<void> insertAlarm(model.Alarm alarm) {
    return into(alarms).insert(AlarmsCompanion.insert(
      id: alarm.id,
      name: Value(alarm.name),
      latitude: alarm.latitude,
      longitude: alarm.longitude,
      radius: alarm.radius,
      createdAt: alarm.createdAt,
      isActive: Value(alarm.isActive),
      sharedLink: Value(alarm.sharedLink),
    ));
  }

  /// Delete an alarm by id.
  Future<void> deleteAlarm(String id) {
    return (delete(alarms)..where((t) => t.id.equals(id))).go();
  }

  /// Toggle active state.
  Future<void> toggleAlarm(String id, bool active) {
    return (update(alarms)..where((t) => t.id.equals(id)))
        .write(AlarmsCompanion(isActive: Value(active)));
  }

  // ── Mapping ──────────────────────────────────────────────────

  model.Alarm _toModel(AlarmEntry row) => model.Alarm(
        id: row.id,
        name: row.name,
        latitude: row.latitude,
        longitude: row.longitude,
        radius: row.radius,
        createdAt: row.createdAt,
        isActive: row.isActive,
        sharedLink: row.sharedLink,
      );
}

LazyDatabase _openConnection() {
  return LazyDatabase(() async {
    final dir = await getApplicationDocumentsDirectory();
    final file = File(p.join(dir.path, 'alarms.sqlite'));
    return NativeDatabase.createInBackground(file);
  });
}
