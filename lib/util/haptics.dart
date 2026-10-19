import 'package:flutter/services.dart';

/// Subtle haptics, kept in one place so they stay consistent.
abstract final class Haptics {
  /// Buttons, icon picks, slider steps and switches.
  static Future<void> tap() => HapticFeedback.selectionClick();

  /// A trip starts, pauses, resumes or stops.
  static Future<void> trip() => HapticFeedback.lightImpact();

  /// An action failed.
  static Future<void> error() => HapticFeedback.mediumImpact();

  /// The direction arrow lines up with the destination.
  static Future<void> aligned() => HapticFeedback.lightImpact();
}
