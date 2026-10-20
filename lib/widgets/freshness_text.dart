import 'dart:async';
import 'package:flutter/material.dart';
import '../services/trip_state.dart';
import '../util/relative_time.dart';

/// "Waiting for GPS…" or "Updated 4 min ago". Ticks on its own: far away,
/// fixes (and so trip events) are minutes apart.
class FreshnessText extends StatefulWidget {
  const FreshnessText({super.key, required this.trip, required this.clock});

  final TripState trip;
  final DateTime Function() clock;

  @override
  State<FreshnessText> createState() => _FreshnessTextState();
}

class _FreshnessTextState extends State<FreshnessText> {
  late final Timer _tick;

  @override
  void initState() {
    super.initState();
    _tick = Timer.periodic(const Duration(seconds: 30), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _tick.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final label = freshnessLabel(widget.trip, widget.clock());
    if (label == null) return const SizedBox.shrink();
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Text(
        label,
        style: theme.textTheme.bodySmall?.copyWith(
          color: theme.colorScheme.tertiary,
        ),
      ),
    );
  }
}
