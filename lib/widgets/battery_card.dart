import 'package:flutter/material.dart';

/// Shown while Android battery-optimizes the app, which can stop long trips.
class BatteryCard extends StatelessWidget {
  const BatteryCard({super.key, required this.onAllow});

  final VoidCallback onAllow;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 16, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.battery_alert),
                const SizedBox(width: 20),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Battery optimization is on',
                        style: theme.textTheme.titleSmall,
                      ),
                      Text(
                        'Android may stop tracking during long or overnight trips. Let the app run without battery restrictions.',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(onPressed: onAllow, child: const Text('Allow')),
            ),
          ],
        ),
      ),
    );
  }
}
