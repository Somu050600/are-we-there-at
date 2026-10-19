import 'package:flutter_test/flutter_test.dart';
import 'package:are_we_there_yet/services/shared_link.dart';

void main() {
  test('extracts a map URL from a title plus shared text', () {
    expect(
      sharedLinkUri(
        'My railway station\nhttps://maps.app.goo.gl/abc123\nSent from Maps',
      ).toString(),
      'https://maps.app.goo.gl/abc123',
    );
  });
  test('preserves coordinates and encoded query', () {
    expect(
      sharedLinkUri('geo:0,0?q=12.5%2C77.5').toString(),
      'geo:0,0?q=12.5%2C77.5',
    );
  });
  test(
    'plain coordinates and unsupported schemes are not treated as browser links',
    () {
      for (final raw in [
        '12,77',
        'javascript:alert(1)',
        'file:///etc/passwd',
        'https://',
      ]) {
        expect(sharedLinkUri(raw), isNull);
      }
    },
  );
}
