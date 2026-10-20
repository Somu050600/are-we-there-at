import 'package:flutter/material.dart';
import '../models/destination.dart';
import '../services/error_message.dart';
import '../services/native_service.dart';
import '../services/trip_controller.dart';
import '../services/trip_state.dart';
import '../widgets/battery_card.dart';
import '../widgets/destination_tile.dart';
import '../widgets/full_screen_alarm_card.dart';
import '../util/haptics.dart';
import '../widgets/last_trip_card.dart';
import 'destination_sheet.dart';
import 'direction_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.controller, this.now});
  final TripController controller;

  /// Clock for "Last: 4h ago" labels; replaced in tests.
  final DateTime Function()? now;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

/// What the destination rows show of the trip; changes far less often than
/// the trip itself, which reports every fix.
typedef _ActiveTrip = ({String? id, bool paused});

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  late final Stream<List<Destination>> _destinations;
  late final ValueNotifier<_ActiveTrip> _active;

  TripController get _controller => widget.controller;

  @override
  void initState() {
    super.initState();
    _destinations = _controller.database.watchDestinations();
    _active = ValueNotifier(_activeOf(_controller.trip.value));
    _controller.trip.addListener(_syncActive);
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.trip.removeListener(_syncActive);
    _active.dispose();
    super.dispose();
  }

  static _ActiveTrip _activeOf(TripState trip) => (
    id: trip.isActive ? trip.destinationId : null,
    paused: trip.status == TripStatus.paused,
  );

  // Records compare by value, so rows rebuild only when this changes.
  void _syncActive() => _active.value = _activeOf(_controller.trip.value);

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The user may have changed the full-screen permission or battery
    // optimization in Settings.
    if (state == AppLifecycleState.resumed) {
      _controller.refreshAlarmSettings().catchError((Object _) {});
    }
  }

  /// Runs a tapped action; [trip] actions confirm success with a soft pulse.
  Future<void> _attempt(
    Future<void> Function() action, {
    bool trip = false,
  }) async {
    Haptics.tap();
    try {
      await action();
      if (trip) Haptics.trip();
    } catch (error) {
      Haptics.error();
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(errorMessage(error))));
    }
  }

  void _edit(Destination destination) {
    Haptics.tap();
    showDestinationSheet(
      context,
      controller: _controller,
      existing: destination,
    );
  }

  void _openDirection(Destination? destination) {
    Haptics.tap();
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => DirectionScreen(
          controller: _controller,
          destination: destination,
          now: widget.now,
        ),
      ),
    );
  }

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
            // Only the top card follows every fix; the list is built once per
            // change to the saved destinations.
            return ListView(
              padding: const EdgeInsets.fromLTRB(16, 24, 16, 32),
              children: [
                ValueListenableBuilder<TripState>(
                  valueListenable: _controller.trip,
                  builder: (context, trip, _) {
                    final top = trip.isActive
                        ? destinations
                              .where((d) => d.id == trip.destinationId)
                              .firstOrNull
                        : destinations
                              .where((d) => d.lastUsedAt != null)
                              .firstOrNull;
                    if (!trip.isActive && top == null) {
                      return const SizedBox.shrink();
                    }
                    return Padding(
                      padding: const EdgeInsets.only(bottom: 32),
                      child: LastTripCard(
                        trip: trip,
                        clock: widget.now ?? DateTime.now,
                        destination: top,
                        onRestart: () =>
                            _attempt(() => _controller.start(top!), trip: true),
                        onPause: () => _attempt(_controller.pause, trip: true),
                        onResume: () =>
                            _attempt(_controller.resume, trip: true),
                        onStop: () => _attempt(_controller.stop, trip: true),
                        onOpen: () => _openDirection(top),
                      ),
                    );
                  },
                ),
                ValueListenableBuilder<AlarmSettings>(
                  valueListenable: _controller.alarmSettings,
                  builder: (context, settings, _) =>
                      settings.batteryUnrestricted
                      ? const SizedBox.shrink()
                      : Padding(
                          padding: const EdgeInsets.only(bottom: 24),
                          child: BatteryCard(
                            onAllow: () =>
                                _attempt(NativeService.requestBatteryExemption),
                          ),
                        ),
                ),
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
                ValueListenableBuilder<_ActiveTrip>(
                  valueListenable: _active,
                  builder: (context, active, _) => Column(
                    children: [
                      for (final destination in destinations)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: DestinationTile(
                            key: ValueKey(destination.id),
                            destination: destination,
                            now: now,
                            active: destination.id == active.id,
                            paused: active.paused,
                            onTap: destination.id == active.id
                                ? () => _openDirection(destination)
                                : () => _edit(destination),
                            onLongPress: () => _edit(destination),
                            onStart: () => _attempt(
                              () => _controller.start(destination),
                              trip: true,
                            ),
                            onStop: () =>
                                _attempt(_controller.stop, trip: true),
                            onPause: () =>
                                _attempt(_controller.pause, trip: true),
                            onResume: () =>
                                _attempt(_controller.resume, trip: true),
                          ),
                        ),
                    ],
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
