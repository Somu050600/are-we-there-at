import 'package:flutter/services.dart';

String errorMessage(Object error) {
  if (error is PlatformException) {
    return error.message ??
        'Could not update the trip. Check permissions and try again.';
  }
  if (error is FormatException) return error.message;
  if (error is ArgumentError) {
    return error.message?.toString() ?? 'Check the location and radius.';
  }
  return 'Could not save the change. Please try again.';
}
