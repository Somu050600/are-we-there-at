import 'dart:async';
import 'package:flutter/material.dart';
import '../models/destination.dart';
import '../services/error_message.dart';
import '../services/native_service.dart';
import '../services/trip_controller.dart';
import '../services/trip_state.dart';
import '../util/direction.dart';
import '../util/haptics.dart';
import '../util/relative_time.dart';

/// Find My–style view of the running trip: arrival time, an arrow towards the
/// destination, distance and time left.
class DirectionScreen extends StatefulWidget {
  const DirectionScreen({
    super.key,
    required this.controller,
    required this.destination,
    this.headings,
    this.now,
  });

  final TripController controller;
  final Destination? destination;

  /// Compass headings; defaults to the device sensor. Replaced in tests.
  final Stream<double>? headings;
  final DateTime Function()? now;

  @override
  State<DirectionScreen> createState() => _DirectionScreenState();
}

class _DirectionScreenState extends State<DirectionScreen> {
  static const _foreground = Color(0xFFC5CADB);
  StreamSubscription<double>? _headingSubscription;
  double? _heading;
  bool _noCompass = false;
  Timer? _compassTimeout;
  double _turns = 0;
  final _alignment = AlignmentTracker();
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    widget.controller.trip.addListener(_turn);
    // A locked phone keeps this route open: without this, 1 s GPS and the
    // compass would run until the trip ends.
    _lifecycle = AppLifecycleListener(onHide: _sleep, onShow: _wake);
    _wake();
    _turn();
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    widget.controller.trip.removeListener(_turn);
    _sleep();
    super.dispose();
  }

  /// Starts 1 s location updates and the compass.
  void _wake() {
    NativeService.setFastUpdates(true).catchError((Object _) {});
    _headingSubscription ??= (widget.headings ?? NativeService.headings())
        .listen(
          (heading) {
            _heading = heading;
            _compassTimeout?.cancel();
            if (_noCompass) setState(() => _noCompass = false);
            _turn();
          },
          onError: (Object _) {
            if (mounted) setState(() => _noCompass = true);
          },
        );
    // Some sensors register but never report; say so instead of silently
    // pointing relative to north.
    _compassTimeout?.cancel();
    _compassTimeout = Timer(const Duration(seconds: 3), () {
      if (mounted && _heading == null) {
        setState(() => _noCompass = true);
        _turn();
      }
    });
  }

  void _sleep() {
    _compassTimeout?.cancel();
    _headingSubscription?.cancel();
    _headingSubscription = null;
    NativeService.setFastUpdates(false).catchError((Object _) {});
  }

  void _turn() {
    final bearing = widget.controller.trip.value.bearingDegrees;
    if (bearing == null || !mounted) return;
    final angle = relativeAngle(bearing, _noCompass ? null : _heading);
    if (_heading != null && _alignment.update(angle)) Haptics.aligned();
    setState(() => _turns = nextTurns(_turns, angle));
  }

  Future<void> _openMaps() async {
    final destination = widget.destination;
    if (destination == null) return;
    Haptics.tap();
    try {
      await NativeService.openInMaps(destination);
    } catch (error) {
      Haptics.error();
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(errorMessage(error))));
    }
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: ValueListenableBuilder<TripState>(
          valueListenable: widget.controller.trip,
          builder: (context, trip, _) {
            final now = (widget.now ?? DateTime.now)();
            final name = widget.destination?.label ?? trip.name ?? '';
            if (!trip.isActive) return _Finished(trip: trip, name: name);
            final paused = trip.status == TripStatus.paused;
            final distance = trip.distanceMeters;
            final remaining = formatRemaining(trip.etaSeconds);
            final caption = paused
                ? 'Paused'
                : trip.waitingForSignal
                ? 'Waiting for GPS…'
                : _noCompass
                ? 'Compass unavailable · arrow points from north'
                : null;
            return Column(
              children: [
                const SizedBox(height: 56),
                Text(
                  paused ? '—' : arrivalClock(now, trip.etaSeconds),
                  semanticsLabel: trip.etaSeconds == null || paused
                      ? 'Arrival time not known yet'
                      : 'Arriving at ${arrivalClock(now, trip.etaSeconds)}',
                  style: text.displaySmall?.copyWith(
                    color: _foreground,
                    fontFeatures: const [FontFeature.tabularFigures()],
                  ),
                ),
                const SizedBox(height: 8),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 32),
                  child: Text(
                    name,
                    textAlign: TextAlign.center,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: text.titleMedium?.copyWith(
                      color: _foreground.withValues(alpha: 0.7),
                    ),
                  ),
                ),
                Expanded(
                  child: Center(
                    child: trip.bearingDegrees == null
                        ? Text(
                            'Calculating…',
                            style: text.headlineSmall?.copyWith(
                              color: _foreground,
                            ),
                          )
                        : AnimatedOpacity(
                            opacity: paused ? 0.35 : 1,
                            duration: const Duration(milliseconds: 300),
                            child: AnimatedRotation(
                              turns: _turns,
                              duration: const Duration(milliseconds: 250),
                              curve: Curves.easeOut,
                              child: Semantics(
                                label: 'Direction to destination',
                                child: SizedBox.square(
                                  dimension: 220,
                                  child: CustomPaint(
                                    painter: _ArrowPainter(_foreground),
                                  ),
                                ),
                              ),
                            ),
                          ),
                  ),
                ),
                if (distance != null)
                  Text(
                    remaining == null
                        ? formatDistance(distance)
                        : '${formatDistance(distance)} · $remaining',
                    style: text.titleLarge?.copyWith(color: _foreground),
                  ),
                if (caption != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      caption,
                      style: text.bodySmall?.copyWith(
                        color: _foreground.withValues(alpha: 0.7),
                      ),
                    ),
                  ),
                const SizedBox(height: 32),
                _RoundButton(
                  icon: Icons.map_outlined,
                  label: 'Open in Maps',
                  onPressed: widget.destination == null ? null : _openMaps,
                ),
                const SizedBox(height: 32),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _Finished extends StatelessWidget {
  const _Finished({required this.trip, required this.name});
  final TripState trip;
  final String name;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final arrived = trip.status == TripStatus.arrived;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              arrived ? Icons.check_circle_outline : Icons.flag_outlined,
              size: 72,
              color: _DirectionScreenState._foreground,
            ),
            const SizedBox(height: 16),
            Text(
              arrived ? "You've arrived" : 'Trip ended',
              style: text.headlineSmall?.copyWith(
                color: _DirectionScreenState._foreground,
              ),
            ),
            if (name.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(
                name,
                textAlign: TextAlign.center,
                style: text.titleMedium?.copyWith(color: Colors.white70),
              ),
            ],
            const SizedBox(height: 32),
            FilledButton(
              onPressed: () {
                Haptics.tap();
                Navigator.of(context).pop();
              },
              child: const Text('Done'),
            ),
          ],
        ),
      ),
    );
  }
}

class _RoundButton extends StatelessWidget {
  const _RoundButton({
    required this.icon,
    required this.label,
    required this.onPressed,
  });
  final IconData icon;
  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => IconButton(
    tooltip: label,
    onPressed: onPressed,
    constraints: const BoxConstraints.tightFor(width: 64, height: 64),
    style: IconButton.styleFrom(
      foregroundColor: _DirectionScreenState._foreground,
      side: const BorderSide(color: _DirectionScreenState._foreground),
      shape: const CircleBorder(),
    ),
    icon: Icon(icon),
  );
}

/// A rounded "up" arrow; rotation points it at the destination.
class _ArrowPainter extends CustomPainter {
  const _ArrowPainter(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final stroke = size.width * 0.11;
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    final centerX = size.width / 2;
    final top = size.height * 0.18;
    final bottom = size.height * 0.82;
    final wing = size.width * 0.27;
    canvas.drawLine(Offset(centerX, top), Offset(centerX, bottom), paint);
    canvas.drawPath(
      Path()
        ..moveTo(centerX - wing, top + wing)
        ..lineTo(centerX, top)
        ..lineTo(centerX + wing, top + wing),
      paint,
    );
  }

  @override
  bool shouldRepaint(_ArrowPainter oldDelegate) => oldDelegate.color != color;
}
