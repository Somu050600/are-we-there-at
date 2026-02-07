import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:uuid/uuid.dart';

import '../db/alarm_database.dart';
import '../models/alarm.dart';
import '../services/native_service.dart';

/// Screen shown when a location is shared into the app.
/// Lets the user configure a radius and save the alarm.
class ConfigureAlarmScreen extends StatefulWidget {
  final double lat;
  final double lng;
  final String? name;
  final String? sharedLink;

  const ConfigureAlarmScreen({
    super.key,
    required this.lat,
    required this.lng,
    this.name,
    this.sharedLink,
  });

  @override
  State<ConfigureAlarmScreen> createState() => _ConfigureAlarmScreenState();
}

class _ConfigureAlarmScreenState extends State<ConfigureAlarmScreen> {
  double _radius = 200;
  bool _saving = false;
  late final TextEditingController _titleController;

  @override
  void initState() {
    super.initState();
    _titleController = TextEditingController(text: widget.name ?? '');
  }

  @override
  void dispose() {
    _titleController.dispose();
    super.dispose();
  }

  String _formatRadius(double meters) {
    if (meters >= 1000) {
      final km = meters / 1000;
      return km == km.roundToDouble()
          ? '${km.toInt()} km'
          : '${km.toStringAsFixed(1)} km';
    }
    return '${meters.toInt()} m';
  }

  Future<void> _save() async {
    setState(() => _saving = true);

    final id = const Uuid().v4();

    try {
      // 1. Register geofence via native
      await NativeService.addGeofence(
        id: id,
        lat: widget.lat,
        lng: widget.lng,
        radius: _radius,
      );

      // 2. Persist to local DB (title: user input > parsed name > coordinates)
      final title = _titleController.text.trim();
      final effectiveName = title.isNotEmpty
          ? title
          : (widget.name?.trim().isNotEmpty == true ? widget.name!.trim() : null);
      final displayName = effectiveName ??
          '${widget.lat.toStringAsFixed(5)}, ${widget.lng.toStringAsFixed(5)}';

      await AlarmDatabase.instance.insertAlarm(Alarm(
        id: id,
        name: displayName,
        latitude: widget.lat,
        longitude: widget.lng,
        radius: _radius,
        createdAt: DateTime.now(),
        sharedLink: widget.sharedLink,
      ));

      if (!mounted) return;

      // 3. Navigate to home (clear stack so back doesn't return here)
      Navigator.of(context).pushNamedAndRemoveUntil('/home', (_) => false);
    } on PlatformException catch (e) {
      if (!mounted) return;
      _showError(e.message ?? 'Failed to register geofence.');
    } catch (e) {
      if (!mounted) return;
      _showError('Unexpected error: $e');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _showError(String msg) {
    final colors = Theme.of(context).colorScheme;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg, style: TextStyle(color: colors.onErrorContainer)),
      backgroundColor: colors.errorContainer,
      behavior: SnackBarBehavior.floating,
    ));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final coords = '${widget.lat.toStringAsFixed(5)}, ${widget.lng.toStringAsFixed(5)}';
    final cardLabel = _titleController.text.trim().isNotEmpty
        ? _titleController.text.trim()
        : (widget.name?.trim().isNotEmpty == true ? widget.name!.trim() : null);

    return Scaffold(
      appBar: AppBar(title: const Text('Configure Alarm')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Location card
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    Icon(Icons.place, color: theme.colorScheme.primary, size: 32),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (cardLabel != null)
                            Text(cardLabel, style: theme.textTheme.titleMedium),
                          Text(coords, style: theme.textTheme.bodySmall),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 24),

            // Optional alarm title
            Text('Alarm title (optional)', style: theme.textTheme.titleSmall),
            const SizedBox(height: 8),
            TextField(
              controller: _titleController,
              decoration: InputDecoration(
                hintText: widget.name != null
                    ? null
                    : 'e.g. Home, Office',
                border: const OutlineInputBorder(),
                filled: true,
              ),
              textCapitalization: TextCapitalization.words,
              onChanged: (_) => setState(() {}),
            ),

            const SizedBox(height: 24),

            // Radius section
            Text('Alert radius', style: theme.textTheme.titleSmall),
            const SizedBox(height: 8),
            Row(
              children: [
                const Text('50 m'),
                Expanded(
                  child: Slider(
                    value: _radius,
                    min: 50,
                    max: 10000,
                    divisions: 199,
                    label: _formatRadius(_radius),
                    onChanged: (v) {
                      HapticFeedback.selectionClick();
                      setState(() => _radius = v);
                    },
                  ),
                ),
                const Text('10 km'),
              ],
            ),
            Center(
              child: Text(
                _formatRadius(_radius),
                style: theme.textTheme.headlineSmall,
              ),
            ),

            const Spacer(),

            // Actions
            FilledButton.icon(
              onPressed: _saving ? null : _save,
              icon: _saving
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.check),
              label: Text(_saving ? 'Saving...' : 'Save Alarm'),
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
              ),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: _saving ? null : () => Navigator.pop(context),
              child: const Text('Cancel'),
            ),
          ],
        ),
      ),
    );
  }
}
