import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'db/alarm_database.dart';
import 'screens/destination_sheet.dart';
import 'screens/home_screen.dart';
import 'services/error_message.dart';
import 'services/native_service.dart';
import 'services/trip_controller.dart';
import 'theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key, this.controller, this.now});
  final TripController? controller;
  final DateTime Function()? now;
  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _navigator = GlobalKey<NavigatorState>();
  final _messenger = GlobalKey<ScaffoldMessengerState>();
  late final TripController _controller;
  bool _readingShare = false;
  bool _readAgain = false;

  @override
  void initState() {
    super.initState();
    final injected = widget.controller;
    // An injected controller is initialized by its owner.
    _controller = injected ?? (TripController(AlarmDatabase.instance)..init());
    NativeService.onSharedLocation(_readShares);
    WidgetsBinding.instance.addPostFrameCallback((_) => _readShares());
  }

  /// Opens one sheet per queued share, in order.
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
        final context = _navigator.currentContext;
        if (location != null && context != null && context.mounted) {
          await showDestinationSheet(
            context,
            controller: _controller,
            shared: location,
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
    if (widget.controller == null) _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    navigatorKey: _navigator,
    scaffoldMessengerKey: _messenger,
    title: 'Are We There Yet',
    theme: buildTheme(Brightness.light),
    darkTheme: buildTheme(Brightness.dark),
    themeMode: ThemeMode.system,
    home: HomeScreen(controller: _controller, now: widget.now),
  );
}
