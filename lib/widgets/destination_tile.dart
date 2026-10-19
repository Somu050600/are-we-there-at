import 'package:flutter/material.dart';
import '../models/destination.dart';
import '../util/relative_time.dart';
import 'destination_icon.dart';

class DestinationTile extends StatelessWidget {
  const DestinationTile({
    super.key,
    required this.destination,
    required this.now,
    required this.active,
    required this.onTap,
    required this.onStart,
    required this.onStop,
  });

  final Destination destination;
  final DateTime now;

  /// Whether the running trip goes to this destination.
  final bool active;
  final VoidCallback onTap;
  final VoidCallback onStart;
  final VoidCallback onStop;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 12, 12, 12),
          child: Row(
            children: [
              Icon(iconFor(destination.icon)),
              const SizedBox(width: 24),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      destination.label,
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    Text(
                      active
                          ? 'Trip in progress'
                          : lastUsedLabel(destination.lastUsedAt, now),
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              IconButton.filledTonal(
                iconSize: 28,
                constraints: const BoxConstraints.tightFor(
                  width: 56,
                  height: 56,
                ),
                tooltip: active
                    ? 'Stop trip to ${destination.label}'
                    : 'Start trip to ${destination.label}',
                onPressed: active ? onStop : onStart,
                icon: Icon(active ? Icons.stop : Icons.play_arrow_outlined),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
