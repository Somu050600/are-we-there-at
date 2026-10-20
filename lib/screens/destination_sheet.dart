import 'package:flutter/material.dart';
import 'package:uuid/uuid.dart';
import '../models/destination.dart';
import '../services/error_message.dart';
import '../services/native_service.dart';
import '../services/trip_controller.dart';
import '../util/haptics.dart';
import '../util/radius_scale.dart';
import '../widgets/destination_icon.dart';

/// Bottom sheet to save a shared place ([shared]) or edit [existing].
///
/// With [pending], the sheet opens at once and says it is finding the place
/// while the share is read. If reading fails, it closes and returns the error.
Future<Object?> showDestinationSheet(
  BuildContext context, {
  required TripController controller,
  Destination? existing,
  SharedLocation? shared,
  Future<SharedLocation?>? pending,
}) {
  assert([existing, shared, pending].where((it) => it != null).length == 1);
  return showModalBottomSheet<Object?>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (_) => DestinationSheet(
      controller: controller,
      existing: existing,
      shared: shared,
      pending: pending,
    ),
  );
}

class DestinationSheet extends StatefulWidget {
  const DestinationSheet({
    super.key,
    required this.controller,
    this.existing,
    this.shared,
    this.pending,
  });

  final TripController controller;
  final Destination? existing;
  final SharedLocation? shared;
  final Future<SharedLocation?>? pending;

  @override
  State<DestinationSheet> createState() => _DestinationSheetState();
}

class _DestinationSheetState extends State<DestinationSheet> {
  final _name = TextEditingController();
  late Destination _draft;
  SharedLocation? _shared;
  bool _loading = false;
  bool _busy = false;
  String? _error;

  bool get _editing => widget.existing != null;

  @override
  void initState() {
    super.initState();
    final existing = widget.existing;
    if (existing != null) {
      _show(existing);
    } else if (widget.shared != null) {
      _showShared(widget.shared!);
    } else {
      _loading = true;
      widget.pending!.then(
        (shared) {
          if (!mounted) return;
          // Taken by another reader in the meantime: nothing to show.
          if (shared == null) return Navigator.of(context).pop();
          setState(() {
            _loading = false;
            _showShared(shared);
          });
        },
        onError: (Object error) {
          if (mounted) Navigator.of(context).pop(error);
        },
      );
    }
  }

  void _show(Destination destination) {
    _draft = destination;
    _name.text = destination.name ?? '';
  }

  void _showShared(SharedLocation shared) {
    _shared = shared;
    _show(
      Destination(
        id: const Uuid().v4(),
        name: shared.name,
        latitude: shared.lat,
        longitude: shared.lng,
        radius: 200,
        createdAt: DateTime.now(),
        sharedLink: shared.sharedLink,
      ),
    );
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Destination _current() {
    final name = _name.text.trim();
    return _draft.copyWith(name: name.isEmpty ? '' : name);
  }

  /// [trip] marks actions that start a trip, confirmed with a soft pulse.
  Future<void> _submit(
    Future<void> Function(Destination) action, {
    bool trip = false,
  }) async {
    if (_busy) return;
    Haptics.tap();
    setState(() {
      _busy = true;
      _error = null;
    });
    final navigator = Navigator.of(context);
    try {
      await action(_current());
      if (trip) Haptics.trip();
      if (mounted) navigator.pop();
    } catch (error) {
      Haptics.error();
      // Shown inside the sheet: a snackbar would sit behind the modal barrier.
      if (mounted) setState(() => _error = errorMessage(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _finding(ThemeData theme) => Padding(
    padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          'New Destination',
          style: theme.textTheme.titleLarge?.copyWith(
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 20),
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: theme.colorScheme.surfaceContainerHigh,
            borderRadius: BorderRadius.circular(24),
          ),
          child: Row(
            children: [
              const SizedBox.square(
                dimension: 24,
                child: CircularProgressIndicator(strokeWidth: 3),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Text(
                  'Finding the place…',
                  style: theme.textTheme.bodyLarge,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        _cancelButton(),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (_loading) return _finding(theme);
    final controller = widget.controller;
    // A destination saved with a larger radius keeps it on the scale.
    final stops = radiusStops(
      [
        controller.alarmSettings.value.maxRadiusMeters,
        _draft.radius,
      ].reduce((a, b) => a > b ? a : b),
    );
    final stop = nearestStop(stops, _draft.radius);
    return PopScope(
      canPop: !_busy,
      child: Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.viewInsetsOf(context).bottom,
        ),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                _editing ? 'Edit Destination' : 'New Destination',
                style: theme.textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 20),
              Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: theme.colorScheme.surfaceContainerHigh,
                  borderRadius: BorderRadius.circular(24),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    TextField(
                      controller: _name,
                      enabled: !_busy,
                      textCapitalization: TextCapitalization.words,
                      decoration: InputDecoration(
                        labelText: 'Name',
                        hintText: _draft.coordinates,
                      ),
                    ),
                    const SizedBox(height: 20),
                    Text('Pick an icon', style: theme.textTheme.bodySmall),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        for (final icon in DestinationIcon.values) ...[
                          if (icon != DestinationIcon.values.first)
                            const SizedBox(width: 8),
                          Expanded(
                            child: _IconChoice(
                              icon: icon,
                              selected: _draft.icon == icon,
                              onTap: _busy
                                  ? null
                                  : () {
                                      Haptics.tap();
                                      setState(
                                        () => _draft = _draft.copyWith(
                                          icon: icon,
                                        ),
                                      );
                                    },
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 20),
                    Text('Coordinates', style: theme.textTheme.bodySmall),
                    Text(
                      _draft.coordinates,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    if (_shared?.approximate ?? false)
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text(
                          'Approximate — found from the place name. '
                          'Check it before you rely on the alarm.',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.tertiary,
                          ),
                        ),
                      ),
                    const SizedBox(height: 16),
                    Text(
                      'Radius: ${formatRadius(_draft.radius)}',
                      style: theme.textTheme.bodyMedium,
                    ),
                    Slider(
                      value: stop.toDouble(),
                      max: (stops.length - 1).toDouble(),
                      divisions: stops.length - 1,
                      label: formatRadius(stops[stop]),
                      onChanged: _busy
                          ? null
                          : (value) {
                              final radius = stops[value.round()];
                              if (radius == _draft.radius) return;
                              // One click per step, not per frame.
                              Haptics.tap();
                              setState(
                                () => _draft = _draft.copyWith(radius: radius),
                              );
                            },
                    ),
                    Text(
                      'Also ring before arriving',
                      style: theme.textTheme.bodyMedium,
                    ),
                    const SizedBox(height: 4),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        for (final minutes in [null, ...alertMinuteChoices])
                          ChoiceChip(
                            visualDensity: VisualDensity.compact,
                            tooltip: minutes == null
                                ? null
                                : 'Ring when about $minutes min are left',
                            label: Text(
                              minutes == null ? 'Off' : '$minutes min',
                            ),
                            selected: _draft.alertMinutes == minutes,
                            onSelected: _busy
                                ? null
                                : (_) {
                                    Haptics.tap();
                                    setState(
                                      () => _draft = _draft.copyWith(
                                        alertMinutes: () => minutes,
                                      ),
                                    );
                                  },
                          ),
                      ],
                    ),
                  ],
                ),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 16),
                  child: Text(
                    _error!,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.error,
                    ),
                  ),
                ),
              const SizedBox(height: 20),
              if (_editing)
                Row(
                  children: [
                    IconButton.filled(
                      tooltip: 'Delete destination',
                      constraints: const BoxConstraints.tightFor(
                        width: 56,
                        height: 56,
                      ),
                      style: IconButton.styleFrom(
                        backgroundColor: theme.colorScheme.errorContainer,
                        foregroundColor: theme.colorScheme.onErrorContainer,
                      ),
                      onPressed: _busy
                          ? null
                          : () => _submit(controller.delete),
                      icon: const Icon(Icons.delete_outline),
                    ),
                    const SizedBox(width: 12),
                    Expanded(child: _cancelButton()),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton(
                        onPressed: _busy
                            ? null
                            : () => _submit(controller.save),
                        child: const Text('Save'),
                      ),
                    ),
                  ],
                )
              else ...[
                FilledButton.icon(
                  onPressed: _busy
                      ? null
                      : () => _submit(controller.saveAndStart, trip: true),
                  icon: const Icon(Icons.navigation),
                  label: Text(_busy ? 'Starting…' : 'Start'),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(child: _cancelButton()),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton.tonal(
                        style: FilledButton.styleFrom(
                          minimumSize: const Size(64, 56),
                          shape: const StadiumBorder(),
                        ),
                        onPressed: _busy
                            ? null
                            : () => _submit(controller.save),
                        child: const Text('Save'),
                      ),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _cancelButton() => OutlinedButton(
    onPressed: _busy
        ? null
        : () {
            Haptics.tap();
            Navigator.of(context).pop();
          },
    child: const Text('Cancel'),
  );
}

class _IconChoice extends StatelessWidget {
  const _IconChoice({
    required this.icon,
    required this.selected,
    required this.onTap,
  });

  final DestinationIcon icon;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Semantics(
      button: true,
      selected: selected,
      label: iconLabel(icon),
      child: Material(
        color: selected
            ? scheme.secondaryContainer
            : scheme.surfaceContainerLowest,
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: onTap,
          child: SizedBox(
            height: 56,
            child: Icon(
              iconFor(icon),
              color: selected ? scheme.onSecondaryContainer : scheme.onSurface,
            ),
          ),
        ),
      ),
    );
  }
}
