import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../models/alarm.dart' as model;
import '../models/destination.dart';

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
  TextColumn get icon => text().withDefault(const Constant('navigation'))();
  DateTimeColumn get lastUsedAt => dateTime().nullable()();

  @override
  Set<Column> get primaryKey => {id};
}

@DriftDatabase(tables: [Alarms])
class AlarmDatabase extends _$AlarmDatabase {
  AlarmDatabase(super.e);

  static AlarmDatabase? _instance;

  /// Singleton accessor.
  static AlarmDatabase get instance {
    _instance ??= AlarmDatabase(_openConnection());
    return _instance!;
  }

  @override
  int get schemaVersion => 3;

  @override
  MigrationStrategy get migration => MigrationStrategy(
    onUpgrade: (migrator, from, to) async {
      if (from < 2) {
        await migrator.addColumn(alarms, alarms.sharedLink);
      }
      if (from < 3) {
        await migrator.addColumn(alarms, alarms.icon);
        await migrator.addColumn(alarms, alarms.lastUsedAt);
      }
    },
  );

  // ── Queries ──────────────────────────────────────────────────

  /// Saved destinations, most recently used first, then newest created.
  Stream<List<Destination>> watchDestinations() {
    final query = select(alarms)
      ..orderBy([
        (t) => OrderingTerm(
          expression: t.lastUsedAt,
          mode: OrderingMode.desc,
          nulls: NullsOrder.last,
        ),
        (t) => OrderingTerm.desc(t.createdAt),
      ]);
    return query.watch().map((rows) => rows.map(_toDestination).toList());
  }

  Future<List<Destination>> getAllDestinations() async =>
      (await select(alarms).get()).map(_toDestination).toList();

  Future<Destination?> getDestination(String id) async {
    final row = await (select(
      alarms,
    )..where((t) => t.id.equals(id))).getSingleOrNull();
    return row == null ? null : _toDestination(row);
  }

  Future<void> insertDestination(Destination destination) {
    return into(alarms).insert(
      AlarmsCompanion.insert(
        id: destination.id,
        name: Value(destination.name),
        latitude: destination.latitude,
        longitude: destination.longitude,
        radius: destination.radius,
        createdAt: destination.createdAt,
        // Legacy column: trips replaced always-on alarms.
        isActive: const Value(false),
        sharedLink: Value(destination.sharedLink),
        icon: Value(destination.icon.name),
        lastUsedAt: Value(destination.lastUsedAt),
      ),
    );
  }

  /// Updates the fields the edit sheet can change.
  Future<void> updateDestination(Destination destination) {
    return (update(
      alarms,
    )..where((t) => t.id.equals(destination.id))).write(
      AlarmsCompanion(
        name: Value(destination.name),
        radius: Value(destination.radius),
        icon: Value(destination.icon.name),
      ),
    );
  }

  Future<void> deleteDestination(String id) =>
      (delete(alarms)..where((t) => t.id.equals(id))).go();

  Future<void> markUsed(String id, DateTime when) {
    return (update(alarms)..where((t) => t.id.equals(id))).write(
      AlarmsCompanion(lastUsedAt: Value(when)),
    );
  }


  /// Reactive stream of all alarms, newest first.
  Stream<List<model.Alarm>> watchAllAlarms() {
    final query = select(alarms)
      ..orderBy([(t) => OrderingTerm.desc(t.createdAt)]);
    return query.watch().map((rows) => rows.map(_toModel).toList());
  }

  Future<List<model.Alarm>> getAllAlarms() async =>
      (await select(alarms).get()).map(_toModel).toList();

  Future<model.Alarm?> getAlarm(String id) async {
    final row = await (select(
      alarms,
    )..where((t) => t.id.equals(id))).getSingleOrNull();
    return row == null ? null : _toModel(row);
  }

  /// Insert a new alarm.
  Future<void> insertAlarm(model.Alarm alarm) {
    return into(alarms).insert(
      AlarmsCompanion.insert(
        id: alarm.id,
        name: Value(alarm.name),
        latitude: alarm.latitude,
        longitude: alarm.longitude,
        radius: alarm.radius,
        createdAt: alarm.createdAt,
        isActive: Value(alarm.isActive),
        sharedLink: Value(alarm.sharedLink),
      ),
    );
  }

  /// Delete an alarm by id.
  Future<void> deleteAlarm(String id) {
    return (delete(alarms)..where((t) => t.id.equals(id))).go();
  }

  /// Toggle active state.
  Future<void> toggleAlarm(String id, bool active) {
    return (update(alarms)..where((t) => t.id.equals(id))).write(
      AlarmsCompanion(isActive: Value(active)),
    );
  }

  // ── Mapping ──────────────────────────────────────────────────

  Destination _toDestination(AlarmEntry row) => Destination(
    id: row.id,
    name: row.name,
    latitude: row.latitude,
    longitude: row.longitude,
    radius: row.radius,
    createdAt: row.createdAt,
    sharedLink: row.sharedLink,
    icon: DestinationIcon.fromName(row.icon),
    lastUsedAt: row.lastUsedAt,
  );

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
