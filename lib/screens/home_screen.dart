import 'package:flutter/material.dart';

import '../db/alarm_database.dart';
import '../models/alarm.dart';
import 'alarm_detail_screen.dart';

/// Home screen showing the list of saved location alarms.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Are We There Yet')),
      body: StreamBuilder<List<Alarm>>(
        stream: AlarmDatabase.instance.watchAllAlarms(),
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }

          final alarms = snapshot.data ?? [];

          if (alarms.isEmpty) {
            return _EmptyState(theme: theme);
          }

          return ListView.builder(
            padding: const EdgeInsets.symmetric(vertical: 8),
            itemCount: alarms.length,
            itemBuilder: (context, index) {
              final alarm = alarms[index];
              return _AlarmCard(alarm: alarm, theme: theme);
            },
          );
        },
      ),
    );
  }
}

// ── Empty state ──────────────────────────────────────────────────

class _EmptyState extends StatelessWidget {
  final ThemeData theme;
  const _EmptyState({required this.theme});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.location_on, size: 64, color: theme.colorScheme.primary),
            const SizedBox(height: 24),
            Text('No alarms yet', style: theme.textTheme.headlineMedium),
            const SizedBox(height: 8),
            Text(
              'Open Google Maps, select a location, tap Share, '
              'and choose this app to create a location alarm.',
              style: theme.textTheme.bodyMedium,
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

// ── Alarm card ───────────────────────────────────────────────────

class _AlarmCard extends StatelessWidget {
  final Alarm alarm;
  final ThemeData theme;
  const _AlarmCard({required this.alarm, required this.theme});

  @override
  Widget build(BuildContext context) {
    final displayName = alarm.name ??
        '${alarm.latitude.toStringAsFixed(4)}, ${alarm.longitude.toStringAsFixed(4)}';

    return Dismissible(
      key: ValueKey(alarm.id),
      direction: DismissDirection.endToStart,
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 24),
        color: theme.colorScheme.errorContainer,
        child: Icon(Icons.delete, color: theme.colorScheme.onErrorContainer),
      ),
      onDismissed: (_) => AlarmDatabase.instance.deleteAlarm(alarm.id),
      child: Card(
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
        child: ListTile(
          leading: Icon(
            alarm.isActive ? Icons.notifications_active : Icons.notifications_off,
            color: alarm.isActive
                ? theme.colorScheme.primary
                : theme.colorScheme.outline,
          ),
          title: Text(displayName, maxLines: 1, overflow: TextOverflow.ellipsis),
          subtitle: Text('${alarm.radius.toInt()} m radius'),
          trailing: Switch(
            value: alarm.isActive,
            onChanged: (val) =>
                AlarmDatabase.instance.toggleAlarm(alarm.id, val),
          ),
          onTap: () => Navigator.of(context).pushNamed(
            AlarmDetailScreen.detailRouteName,
            arguments: alarm,
          ),
        ),
      ),
    );
  }
}
