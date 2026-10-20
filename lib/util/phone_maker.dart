/// Where a phone maker hides the setting that lets apps keep running in the
/// background, beyond Android's own battery optimization.
class PhoneMakerGuide {
  const PhoneMakerGuide(this.brand, this.steps);

  final String brand;
  final String steps;

  /// Null for makers without extra restrictions (Pixel, Motorola, …).
  static PhoneMakerGuide? forMaker(String family) => switch (family) {
    'xiaomi' => const PhoneMakerGuide(
      'Xiaomi',
      'Turn on Autostart for the app, and set its Battery saver to '
          'No restrictions (App info → Battery saver).',
    ),
    'samsung' => const PhoneMakerGuide(
      'Samsung',
      'Remove the app from Sleeping and Deep sleeping apps '
          '(Battery → Background usage limits), and set its Battery to '
          'Unrestricted.',
    ),
    'oppo' || 'oneplus' => const PhoneMakerGuide(
      'OPPO, realme and OnePlus',
      'Allow auto launch and background activity for the app '
          '(App info → Battery usage).',
    ),
    'vivo' => const PhoneMakerGuide(
      'vivo',
      'Allow high background power consumption for the app '
          '(Battery → Background power consumption management), and turn '
          'on Autostart.',
    ),
    'huawei' => const PhoneMakerGuide(
      'Huawei and Honor',
      'Set the app to Manage manually with every switch on '
          '(Battery → App launch).',
    ),
    _ => null,
  };
}
