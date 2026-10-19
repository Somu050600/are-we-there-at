import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'db/alarm_database.dart';
import 'models/alarm.dart';
import 'screens/alarm_detail_screen.dart';
import 'screens/configure_alarm_screen.dart';
import 'screens/home_screen.dart';
import 'services/alarm_service.dart';
import 'services/native_service.dart';
import 'services/error_message.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key, this.service});
  final AlarmService? service;
  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _navigator = GlobalKey<NavigatorState>();
  final _messenger = GlobalKey<ScaffoldMessengerState>();
  late final AlarmService _service;
  bool _readingShare = false;
  bool _readAgain = false;
  @override
  void initState() {
    super.initState();
    _service = widget.service ?? AlarmService(AlarmDatabase.instance);
    NativeService.onSharedLocation(_readShares);
    WidgetsBinding.instance.addPostFrameCallback((_) => _readShares());
  }

  Future<void> _readShares() async {
    if (_readingShare) {
      _readAgain = true;
      return;
    }
    _readingShare = true;
    try {
      do {
        _readAgain = false;
        SharedLocation? location;
        try {
          location = await NativeService.getSharedLocation();
        } catch (error) {
          if (!mounted) return;
          _messenger.currentState?.showSnackBar(
            SnackBar(content: Text(errorMessage(error))),
          );
          // An invalid queued share is consumed by native; drain the next one.
          // Transport errors must not create an infinite retry loop.
          if (error is FormatException ||
              error is PlatformException && error.code == 'INVALID_SHARE') {
            _readAgain = true;
            continue;
          }
          break;
        }
        if (!mounted) return;
        if (location != null) {
          final shared = location;
          await _navigator.currentState!.push(
            MaterialPageRoute<void>(
              builder: (_) => ConfigureAlarmScreen(
                service: _service,
                lat: shared.lat,
                lng: shared.lng,
                name: shared.name,
                sharedLink: shared.sharedLink,
              ),
            ),
          );
          _readAgain = true;
        }
      } while (mounted && _readAgain);
    } finally {
      _readingShare = false;
    }
  }

  @override
  void dispose() {
    NativeService.onSharedLocation(null);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    navigatorKey: _navigator,
    scaffoldMessengerKey: _messenger,
    title: 'Are We There Yet',
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
    ),
    home: HomeScreen(service: _service),
    onGenerateRoute: (settings) {
      if (settings.name == AlarmDetailScreen.detailRouteName &&
          settings.arguments is Alarm) {
        return MaterialPageRoute(
          builder: (_) => AlarmDetailScreen(alarm: settings.arguments as Alarm),
        );
      }
      return null;
    },
  );
}
