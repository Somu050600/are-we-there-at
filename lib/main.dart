import 'package:flutter/material.dart';

import 'models/alarm.dart';
import 'screens/alarm_detail_screen.dart';
import 'screens/configure_alarm_screen.dart';
import 'screens/home_screen.dart';
import 'services/native_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Are We There Yet',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
      ),
      home: const _EntryPoint(),
      onGenerateRoute: (settings) {
        if (settings.name == '/home') {
          return MaterialPageRoute(builder: (_) => const HomeScreen());
        }
        if (settings.name == AlarmDetailScreen.detailRouteName) {
          final alarm = settings.arguments as Alarm;
          return MaterialPageRoute(
            builder: (_) => AlarmDetailScreen(alarm: alarm),
          );
        }
        return null;
      },
    );
  }
}

/// Decides whether to show HomeScreen or ConfigureAlarmScreen
/// based on whether the app was launched via a share intent.
class _EntryPoint extends StatefulWidget {
  const _EntryPoint();

  @override
  State<_EntryPoint> createState() => _EntryPointState();
}

class _EntryPointState extends State<_EntryPoint> {
  bool _resolved = false;
  SharedLocation? _shared;

  @override
  void initState() {
    super.initState();
    _checkIntent();
  }

  Future<void> _checkIntent() async {
    try {
      _shared = await NativeService.getSharedLocation();
    } catch (_) {
      // Normal launch if native call fails
    }
    if (mounted) setState(() => _resolved = true);
  }

  @override
  Widget build(BuildContext context) {
    if (!_resolved) {
      // Brief loading while we check for a share intent
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }

    if (_shared != null) {
      return ConfigureAlarmScreen(
        lat: _shared!.lat,
        lng: _shared!.lng,
        name: _shared!.name,
        sharedLink: _shared!.sharedLink,
      );
    }

    return const HomeScreen();
  }
}
