import 'package:flutter/material.dart';
import 'package:uuid/uuid.dart';
import '../models/destination.dart';
import '../services/error_message.dart';
import '../services/native_service.dart';
import '../services/trip_controller.dart';
import '../util/haptics.dart';
import '../widgets/destination_icon.dart';

/// Bottom sheet to save a shared place ([shared]) or edit [existing].
Future<void> showDestinationSheet(
  BuildContext context, {
  required TripController controller,
  Destination? existing,
  SharedLocation? shared,
}) {
  assert((existing == null) != (shared == null));
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (_) => DestinationSheet(
      controller: controller,
      existing: existing,
      shared: shared,
    ),
  );
}

class DestinationSheet extends StatefulWidget {
  const DestinationSheet({
    super.key,
    required this.controller,
    this.existing,
    this.shared,
  });

  final TripController controller;
  final Destination? existing;
  final SharedLocation? shared;

  @override
  State<DestinationSheet> createState() => _DestinationSheetState();
}

class _DestinationSheetState extends State<DestinationSheet> {
  late final TextEditingController _name;
  late Destination _draft;
  bool _busy = false;
  String? _error;

  bool get _editing => widget.existing != null;

  @override
  void initState() {
    super.initState();
    final shared = widget.shared;
    _draft =
        widget.existing ??
        Destination(
          id: const Uuid().v4(),
          name: shared!.name,
          latitude: shared.lat,
          longitude: shared.lng,
          radius: 200,
          createdAt: DateTime.now(),
          sharedLink: shared.sharedLink,
        );
    _name = TextEditingController(text: _draft.name ?? '');
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

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final controller = widget.controller;
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
                    if (widget.shared?.approximate ?? false)
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
                      'Radius: ${_draft.radius.round()} m',
                      style: theme.textTheme.bodyMedium,
                    ),
                    Slider(
                      value: _draft.radius.clamp(50, 10000).toDouble(),
                      min: 50,
                      max: 10000,
                      divisions: 199,
                      label: '${_draft.radius.round()} m',
                      onChanged: _busy
                          ? null
                          : (value) {
                              // One click per 50 m step, not per frame.
                              if (value.round() != _draft.radius.round()) {
                                Haptics.tap();
                              }
                              setState(
                                () => _draft = _draft.copyWith(radius: value),
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
