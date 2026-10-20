import 'package:flutter/material.dart';
import '../services/error_message.dart';
import '../services/native_service.dart';
import '../services/trip_controller.dart';
import '../services/trip_state.dart';
import '../util/haptics.dart';
import '../util/phone_maker.dart';
import '../util/radius_scale.dart';
import '../widgets/full_screen_alarm_card.dart';

/// Alarm options and what keeps a long trip from being stopped.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.controller});

  final TripController controller;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  late final Future<PhoneMakerGuide?> _guide;

  TripController get _controller => widget.controller;

  @override
  void initState() {
    super.initState();
    _guide = NativeService.getPhoneMaker()
        .then(PhoneMakerGuide.forMaker)
        .catchError((Object _) => null);
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Back from Android settings: the exemption or full-screen permission
    // may have changed.
    if (state == AppLifecycleState.resumed) {
      _controller.refreshAlarmSettings().catchError((Object _) {});
    }
  }

  Future<void> _attempt(Future<void> Function() action) async {
    Haptics.tap();
    try {
      await action();
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
    final theme = Theme.of(context);
    Widget header(String text) => Padding(
      padding: const EdgeInsets.fromLTRB(8, 24, 8, 12),
      child: Text(
        text,
        style: theme.textTheme.titleMedium?.copyWith(
          fontWeight: FontWeight.w600,
        ),
      ),
    );
    final muted = theme.textTheme.bodySmall?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ValueListenableBuilder<AlarmSettings>(
        valueListenable: _controller.alarmSettings,
        builder: (context, settings, _) => ListView(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 32),
          children: [
            header('Alarm'),
            FullScreenAlarmCard(
              settings: settings,
              onChanged: (enabled) =>
                  _attempt(() => _controller.setFullScreen(enabled)),
              onOpenSettings: () =>
                  _attempt(NativeService.openFullScreenSettings),
            ),
            const SizedBox(height: 8),
            Card(
              child: SwitchListTile(
                secondary: const Icon(Icons.vibration),
                title: const Text('Vibrate only'),
                subtitle: Text(
                  'No alarm sound, for quiet carriages. Make sure you will '
                  'feel it.',
                  style: muted,
                ),
                value: settings.vibrateOnly,
                onChanged: (enabled) =>
                    _attempt(() => _controller.setVibrateOnly(enabled)),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
              child: Text(
                'Snooze on a ringing alarm rings it again in 5 minutes.',
                style: muted,
              ),
            ),
            const SizedBox(height: 8),
            Card(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.radar),
                        const SizedBox(width: 16),
                        Expanded(
                          child: Text(
                            'Largest alarm radius',
                            style: theme.textTheme.titleSmall,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'How far the radius slider goes. A large radius rings '
                      'well before the stop, which suits long trips.',
                      style: muted,
                    ),
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        for (final meters in AlarmSettings.maxRadiusChoices)
                          ChoiceChip(
                            label: Text(formatRadius(meters)),
                            selected: settings.maxRadiusMeters == meters,
                            onSelected: (_) => _attempt(
                              () => _controller.setMaxRadius(meters),
                            ),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            header('Keep tracking alive'),
            Card(
              child: ListTile(
                leading: Icon(
                  settings.batteryUnrestricted
                      ? Icons.battery_full
                      : Icons.battery_alert,
                ),
                title: const Text('Battery optimization'),
                subtitle: Text(
                  settings.batteryUnrestricted
                      ? 'Off. Android will not stop the app to save battery.'
                      : 'On. Android may stop tracking on long trips.',
                  style: muted,
                ),
                trailing: settings.batteryUnrestricted
                    ? const Icon(Icons.check)
                    : TextButton(
                        onPressed: () =>
                            _attempt(NativeService.requestBatteryExemption),
                        child: const Text('Allow'),
                      ),
              ),
            ),
            FutureBuilder<PhoneMakerGuide?>(
              future: _guide,
              builder: (context, snapshot) {
                final guide = snapshot.data;
                if (guide == null) return const SizedBox.shrink();
                return Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Card(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '${guide.brand} phones',
                            style: theme.textTheme.titleSmall,
                          ),
                          const SizedBox(height: 4),
                          Text(
                            '${guide.brand} can stop apps in the background '
                            'even without battery optimization. ${guide.steps}',
                            style: muted,
                          ),
                          Align(
                            alignment: Alignment.centerRight,
                            child: TextButton(
                              onPressed: () => _attempt(
                                NativeService.openPhoneMakerSettings,
                              ),
                              child: const Text('Open settings'),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}
