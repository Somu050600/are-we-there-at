import 'package:flutter/material.dart';
import '../models/destination.dart';
import '../services/error_message.dart';
import '../services/native_service.dart';
import '../services/trip_controller.dart';
import '../services/trip_state.dart';
import '../widgets/destination_tile.dart';
import '../widgets/full_screen_alarm_card.dart';
import '../widgets/last_trip_card.dart';
import 'destination_sheet.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.controller, this.now});
  final TripController controller;

  /// Clock for "Last: 4h ago" labels; replaced in tests.
  final DateTime Function()? now;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  late final Stream<List<Destination>> _destinations;

  TripController get _controller => widget.controller;

  @override
  void initState() {
    super.initState();
    _destinations = _controller.database.watchDestinations();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The user may have changed the full-screen permission in Settings.
    if (state == AppLifecycleState.resumed) {
      _controller.refreshAlarmSettings().catchError((Object _) {});
    }
  }

  Future<void> _attempt(Future<void> Function() action) async {
    try {
      await action();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(errorMessage(error))));
    }
  }

  void _edit(Destination destination) => showDestinationSheet(
    context,
    controller: _controller,
    existing: destination,
  );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: SafeArea(
        child: StreamBuilder<List<Destination>>(
          stream: _destinations,
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return const Center(
                child: Text('Could not load saved destinations.'),
              );
            }
            if (!snapshot.hasData) {
              return const Center(child: CircularProgressIndicator());
            }
            final destinations = snapshot.data!;
            final now = (widget.now ?? DateTime.now)();
            return ValueListenableBuilder<TripState>(
              valueListenable: _controller.trip,
              builder: (context, trip, _) {
                final activeId = trip.isActive ? trip.destinationId : null;
                Destination? find(String? id) =>
                    destinations.where((d) => d.id == id).firstOrNull;
                final top = trip.isActive
                    ? find(activeId)
                    : destinations
                          .where((d) => d.lastUsedAt != null)
                          .firstOrNull;
                return ListView(
                  padding: const EdgeInsets.fromLTRB(16, 24, 16, 32),
                  children: [
                    if (trip.isActive || top != null) ...[
                      LastTripCard(
                        trip: trip,
                        destination: top,
                        onRestart: () =>
                            _attempt(() => _controller.start(top!)),
                        onPause: () => _attempt(_controller.pause),
                        onResume: () => _attempt(_controller.resume),
                        onStop: () => _attempt(_controller.stop),
                      ),
                      const SizedBox(height: 32),
                    ],
                    Padding(
                      padding: const EdgeInsets.only(left: 8, bottom: 12),
                      child: Text(
                        'Saved Destinations',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    if (destinations.isEmpty)
                      Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 8,
                        ),
                        child: Text(
                          'No saved destinations yet.',
                          style: theme.textTheme.bodyMedium,
                        ),
                      ),
                    for (final destination in destinations)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: DestinationTile(
                          key: ValueKey(destination.id),
                          destination: destination,
                          now: now,
                          active: destination.id == activeId,
                          onTap: () => _edit(destination),
                          onStart: () =>
                              _attempt(() => _controller.start(destination)),
                          onStop: () => _attempt(_controller.stop),
                        ),
                      ),
                    const SizedBox(height: 24),
                    const _Hints(),
                    const SizedBox(height: 24),
                    ValueListenableBuilder<AlarmSettings>(
                      valueListenable: _controller.alarmSettings,
                      builder: (context, settings, _) => FullScreenAlarmCard(
                        settings: settings,
                        onChanged: (enabled) =>
                            _attempt(() => _controller.setFullScreen(enabled)),
                        onOpenSettings: () =>
                            _attempt(NativeService.openFullScreenSettings),
                      ),
                    ),
                  ],
                );
              },
            );
          },
        ),
      ),
    );
  }
}

class _Hints extends StatelessWidget {
  const _Hints();

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.bodySmall?.copyWith(
      color: Theme.of(context).colorScheme.onSurfaceVariant,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Share a place (or a dropped pin) from Google Maps to this app to save it as a destination.',
            style: style,
          ),
          const SizedBox(height: 12),
          Text(
            'The distance shown is the direct distance to the destination, not the distance along the roads.',
            style: style,
          ),
          const SizedBox(height: 12),
          Text(
            'Take all calculations of time and distance with a grain of salt as they are not always accurate.',
            style: style,
          ),
        ],
      ),
    );
  }
}
