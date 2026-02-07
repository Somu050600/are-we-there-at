import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import '../models/alarm.dart';

/// Read-only detail screen for a saved alarm (name, coords, radius, date, shared link).
class AlarmDetailScreen extends StatelessWidget {
  final Alarm alarm;

  const AlarmDetailScreen({super.key, required this.alarm});

  static const detailRouteName = '/alarm/detail';

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final displayName = alarm.name ??
        '${alarm.latitude.toStringAsFixed(5)}, ${alarm.longitude.toStringAsFixed(5)}';
    final coords = '${alarm.latitude.toStringAsFixed(5)}, ${alarm.longitude.toStringAsFixed(5)}';
    final radiusText = alarm.radius >= 1000
        ? '${(alarm.radius / 1000).toStringAsFixed(1)} km'
        : '${alarm.radius.toInt()} m';
    final created = _formatDate(alarm.createdAt);

    return Scaffold(
      appBar: AppBar(title: const Text('Alarm details')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.place, color: theme.colorScheme.primary, size: 28),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          displayName,
                          style: theme.textTheme.titleLarge,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  _DetailRow(label: 'Coordinates', value: coords),
                  _DetailRow(label: 'Radius', value: radiusText),
                  _DetailRow(label: 'Created', value: created),
                ],
              ),
            ),
          ),
          if (alarm.sharedLink != null && alarm.sharedLink!.trim().isNotEmpty) ...[
            const SizedBox(height: 24),
            Text('Shared link', style: theme.textTheme.titleSmall),
            const SizedBox(height: 8),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    SelectableText(
                      alarm.sharedLink!,
                      style: theme.textTheme.bodySmall,
                    ),
                    const SizedBox(height: 12),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        TextButton.icon(
                          onPressed: () => _copyLink(context, alarm.sharedLink!),
                          icon: const Icon(Icons.copy),
                          label: const Text('Copy'),
                        ),
                        const SizedBox(width: 8),
                        FilledButton.icon(
                          onPressed: () => _openLink(alarm.sharedLink!),
                          icon: const Icon(Icons.open_in_browser),
                          label: const Text('Open'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  String _formatDate(DateTime d) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final date = DateTime(d.year, d.month, d.day);
    if (date == today) {
      return 'Today, ${_formatTime(d)}';
    }
    final yesterday = today.subtract(const Duration(days: 1));
    if (date == yesterday) {
      return 'Yesterday, ${_formatTime(d)}';
    }
    return '${d.day}/${d.month}/${d.year}, ${_formatTime(d)}';
  }

  String _formatTime(DateTime d) {
    final h = d.hour.toString().padLeft(2, '0');
    final m = d.minute.toString().padLeft(2, '0');
    return '$h:$m';
  }

  Future<void> _copyLink(BuildContext context, String link) async {
    await Clipboard.setData(ClipboardData(text: link));
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Link copied'), behavior: SnackBarBehavior.floating),
      );
    }
  }

  Future<void> _openLink(String link) async {
    final uri = Uri.tryParse(link);
    if (uri == null) return;
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }
}

class _DetailRow extends StatelessWidget {
  final String label;
  final String value;

  const _DetailRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 100,
            child: Text(label, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ),
          Expanded(child: Text(value, style: theme.textTheme.bodyMedium)),
        ],
      ),
    );
  }
}
