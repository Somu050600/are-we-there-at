import 'package:flutter/material.dart';
import '../models/destination.dart';
import '../services/trip_state.dart';
import '../util/direction.dart';
import '../util/relative_time.dart';
import 'destination_icon.dart';

/// Top card: the running trip with controls, or the last trip to restart.
class LastTripCard extends StatelessWidget {
  const LastTripCard({
    super.key,
    required this.trip,
    required this.destination,
    required this.onRestart,
    required this.onPause,
    required this.onResume,
    required this.onStop,
    required this.onOpen,
  });

  final TripState trip;

  /// The trip's destination when active, otherwise the last used one.
  final Destination? destination;
  final VoidCallback onRestart;
  final VoidCallback onPause;
  final VoidCallback onResume;
  final VoidCallback onStop;

  /// Opens the direction screen for the running trip.
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final name = destination?.label ?? trip.name ?? 'Destination';
    final icon = iconFor(destination?.icon ?? DestinationIcon.navigation);
    final active = trip.isActive;
    final paused = trip.status == TripStatus.paused;
    final distance = trip.distanceMeters;
    final percent = ((trip.progress ?? 0) * 100).floor();
    final remaining = formatRemaining(trip.etaSeconds);
    final stopColors = FilledButton.styleFrom(
      backgroundColor: theme.colorScheme.errorContainer,
      foregroundColor: theme.colorScheme.onErrorContainer,
    );
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: active ? onOpen : null,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 28, 24, 24),
          child: Column(
            children: [
              Icon(icon, size: 36, color: theme.colorScheme.onSurfaceVariant),
              const SizedBox(height: 16),
              Text(
                active ? (paused ? 'Paused' : 'Tracking Now') : 'Last Trip',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                name,
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              if (active) ...[
                const SizedBox(height: 20),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    distance == null
                        ? 'Calculating…'
                        : formatDistance(distance),
                    style: theme.textTheme.displayMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                      color: theme.colorScheme.onSurface.withValues(
                        alpha: 0.85,
                      ),
                    ),
                  ),
                ),
                if (distance != null) ...[
                  const SizedBox(height: 4),
                  Text(
                    remaining == null
                        ? '$percent% there'
                        : '$percent% there · $remaining',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
                const SizedBox(height: 16),
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: LinearProgressIndicator(
                    // Indeterminate only while waiting for the first fix.
                    value: paused
                        ? (trip.progress ?? 0)
                        : (distance == null ? null : trip.progress),
                    minHeight: 6,
                  ),
                ),
                const SizedBox(height: 20),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton.tonalIcon(
                        style: FilledButton.styleFrom(
                          minimumSize: const Size(64, 56),
                          shape: const StadiumBorder(),
                        ),
                        onPressed: paused ? onResume : onPause,
                        icon: Icon(paused ? Icons.play_arrow : Icons.pause),
                        label: Text(paused ? 'Resume' : 'Pause'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton.icon(
                        style: stopColors,
                        onPressed: onStop,
                        icon: const Icon(Icons.close),
                        label: const Text('Stop'),
                      ),
                    ),
                  ],
                ),
              ] else ...[
                const SizedBox(height: 20),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: onRestart,
                    child: const Text('Re-Start'),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
