# Complete Android location alarms

Keep the existing Flutter/Kotlin architecture, application ID and SQLite schema.
Work on feature/complete-alarms-and-functional-tests. Implement reliable create,
activate, deactivate, delete, share intake, background notification and reboot
restoration. Preserve recurring ENTER behavior; no new maps backend or analytics.

1. Native lifecycle: method channel, modern permissions, durable active mirror,
   audible dismissible notifications, safe share parser and warm delivery, reboot
   recovery. Test parser, persistence, notifications and failure behavior.
2. Flutter lifecycle: serialized coordinator with SQLite/native compensation,
   lifecycle synchronization, share navigation, error recovery and scrollable
   keyboard-safe form. Test real SQLite and native channel boundaries and widgets.
3. Validate: analyzer, Flutter unit/widget suite, native tests and debug APK build;
   review concurrency and recovery; document actual device limitations and results.

Channel: are_we_there/native. getSharedLocation returns null or lat/lng/name/
sharedLink; addGeofence receives id/lat/lng/radius/name; removeGeofence receives id;
syncGeofences receives alarms (active records) and inactiveIds (legacy disabled IDs);
openSettings opens the app's settings. sharedLocationAvailable signals new input.
An incoming share is consumed once; coordinates must be finite and within bounds;
radius 50..10000 metres. Native callbacks return platform errors on failure.
