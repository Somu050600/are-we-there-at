import 'package:flutter/material.dart';
import '../services/trip_state.dart';

class FullScreenAlarmCard extends StatelessWidget {
  const FullScreenAlarmCard({
    super.key,
    required this.settings,
    required this.onChanged,
    required this.onOpenSettings,
  });

  final AlarmSettings settings;
  final ValueChanged<bool> onChanged;
  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final blocked = settings.enabled && !settings.allowed;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 16, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.alarm),
                const SizedBox(width: 20),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Full-screen alarm',
                        style: theme.textTheme.titleSmall,
                      ),
                      Text(
                        blocked
                            ? 'Android is blocking full-screen alerts. You will get a notification instead.'
                            : 'Play alarm ringtone and show full-screen alert upon arrival',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                Switch(value: settings.enabled, onChanged: onChanged),
              ],
            ),
            if (blocked)
              Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  onPressed: onOpenSettings,
                  child: const Text('Allow in Settings'),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
