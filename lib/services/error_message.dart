import 'package:flutter/services.dart';
import 'alarm_service.dart';

String errorMessage(Object error) {
  if (error is AlarmRecoveryException) return error.toString();
  if (error is PlatformException)
    return error.message ??
        'Could not update the alarm. Check permissions and try again.';
  if (error is FormatException) return error.message;
  if (error is ArgumentError)
    return error.message?.toString() ?? 'Check the location and radius.';
  return 'Could not save the change. Please try again.';
}
