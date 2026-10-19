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
    required this.onLongPress,
    required this.onStart,
    required this.onStop,
    this.paused = false,
    this.onPause,
    this.onResume,
  });

  final Destination destination;
  final DateTime now;

  /// Whether the running trip goes to this destination.
  final bool active;
  final VoidCallback onTap;

  /// Edits the destination, including the one being travelled to.
  final VoidCallback onLongPress;
  final VoidCallback onStart;
  final VoidCallback onStop;

  /// Only used while [active].
  final bool paused;
  final VoidCallback? onPause;
  final VoidCallback? onResume;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
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
                          ? (paused ? 'Paused' : 'Tracking now')
                          : lastUsedLabel(destination.lastUsedAt, now),
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              if (active) ...[
                IconButton.filledTonal(
                  constraints: const BoxConstraints.tightFor(
                    width: 48,
                    height: 48,
                  ),
                  tooltip: paused ? 'Resume trip' : 'Pause trip',
                  onPressed: paused ? onResume : onPause,
                  icon: Icon(paused ? Icons.play_arrow : Icons.pause),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  constraints: const BoxConstraints.tightFor(
                    width: 48,
                    height: 48,
                  ),
                  style: IconButton.styleFrom(
                    backgroundColor: theme.colorScheme.errorContainer,
                    foregroundColor: theme.colorScheme.onErrorContainer,
                  ),
                  tooltip: 'Stop trip to ${destination.label}',
                  onPressed: onStop,
                  icon: const Icon(Icons.close),
                ),
              ] else
                IconButton.filledTonal(
                  iconSize: 28,
                  constraints: const BoxConstraints.tightFor(
                    width: 56,
                    height: 56,
                  ),
                  tooltip: 'Start trip to ${destination.label}',
                  onPressed: onStart,
                  icon: const Icon(Icons.play_arrow_outlined),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
