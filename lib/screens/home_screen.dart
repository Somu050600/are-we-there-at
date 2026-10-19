import 'package:flutter/material.dart';
import '../models/alarm.dart';
import '../services/alarm_service.dart';
import '../services/native_service.dart';
import '../services/error_message.dart';
import 'alarm_detail_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.service});
  final AlarmService service;
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  late final Stream<List<Alarm>> _alarms;
  String? _monitoringError;
  bool _restoring = false;
  @override
  void initState() {
    super.initState();
    _alarms = widget.service.database.watchAllAlarms();
    WidgetsBinding.instance.addObserver(this);
    widget.service.monitoringIssue.addListener(_monitoringIssueChanged);
    _restore();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.service.monitoringIssue.removeListener(_monitoringIssueChanged);
    super.dispose();
  }

  void _monitoringIssueChanged() {
    if (mounted) setState(() {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _restore();
  }

  Future<void> _restore() async {
    if (_restoring) return;
    _restoring = true;
    try {
      await widget.service.synchronize();
      if (mounted) setState(() => _monitoringError = null);
    } catch (error) {
      if (mounted) setState(() => _monitoringError = errorMessage(error));
    } finally {
      _restoring = false;
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Are We There Yet')),
    body: Column(
      children: [
        if (_monitoringError != null ||
            widget.service.monitoringIssue.value != null)
          MaterialBanner(
            content: Text(
              'Monitoring needs attention. ${_monitoringError ?? errorMessage(widget.service.monitoringIssue.value!)}',
            ),
            actions: [
              TextButton(
                onPressed: () async {
                  try {
                    await NativeService.openSettings();
                  } catch (error) {
                    if (context.mounted)
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text(errorMessage(error))),
                      );
                  }
                },
                child: const Text('Settings'),
              ),
              TextButton(onPressed: _restore, child: const Text('Retry')),
            ],
          ),
        Expanded(
          child: StreamBuilder<List<Alarm>>(
            stream: _alarms,
            builder: (context, snapshot) {
              if (snapshot.hasError)
                return const Center(
                  child: Text(
                    'Could not load saved alarms. Please reopen the app.',
                  ),
                );
              if (!snapshot.hasData)
                return const Center(child: CircularProgressIndicator());
              final alarms = snapshot.data!;
              if (alarms.isEmpty) {
                return const Center(
                  child: Padding(
                    padding: EdgeInsets.all(32),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.location_on, size: 64),
                        SizedBox(height: 24),
                        Text('No alarms yet', style: TextStyle(fontSize: 24)),
                        SizedBox(height: 8),
                        Text(
                          'Open Google Maps, select a location, tap Share, and choose this app to create a location alarm.',
                          textAlign: TextAlign.center,
                        ),
                      ],
                    ),
                  ),
                );
              }
              return ListView.builder(
                itemCount: alarms.length,
                padding: const EdgeInsets.symmetric(vertical: 8),
                itemBuilder: (_, index) => _AlarmCard(
                  key: ValueKey(alarms[index].id),
                  alarm: alarms[index],
                  service: widget.service,
                ),
              );
            },
          ),
        ),
      ],
    ),
  );
}

class _AlarmCard extends StatefulWidget {
  const _AlarmCard({super.key, required this.alarm, required this.service});
  final Alarm alarm;
  final AlarmService service;
  @override
  State<_AlarmCard> createState() => _AlarmCardState();
}

class _AlarmCardState extends State<_AlarmCard> {
  bool _busy = false;
  Future<bool> _change(Future<void> Function() operation) async {
    if (_busy) return false;
    setState(() => _busy = true);
    try {
      await operation();
      return true;
    } catch (error) {
      if (mounted)
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(errorMessage(error))));
      return false;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final alarm = widget.alarm;
    final theme = Theme.of(context);
    return Dismissible(
      key: ValueKey(alarm.id),
      direction: _busy ? DismissDirection.none : DismissDirection.endToStart,
      // The database stream owns removal after a successful commit.
      confirmDismiss: (_) async {
        await _change(() => widget.service.delete(alarm));
        return false;
      },
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 24),
        color: theme.colorScheme.errorContainer,
        child: const Icon(Icons.delete),
      ),
      child: Card(
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
        child: ListTile(
          leading: Icon(
            alarm.isActive
                ? Icons.notifications_active
                : Icons.notifications_off,
          ),
          title: Text(
            alarm.name ??
                '${alarm.latitude.toStringAsFixed(4)}, ${alarm.longitude.toStringAsFixed(4)}',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          subtitle: Text('${alarm.radius.toInt()} m radius'),
          trailing: Switch(
            value: alarm.isActive,
            onChanged: _busy
                ? null
                : (active) =>
                      _change(() => widget.service.setActive(alarm, active)),
          ),
          onTap: () => Navigator.of(
            context,
          ).pushNamed(AlarmDetailScreen.detailRouteName, arguments: alarm),
        ),
      ),
    );
  }
}
