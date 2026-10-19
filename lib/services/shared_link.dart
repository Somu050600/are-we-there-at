/// Maps often shares a place title plus a URL rather than a URL alone.
Uri? sharedLinkUri(String raw) {
  final match = RegExp(
    r'(?:https?://|geo:)[^\s<>]+',
    caseSensitive: false,
  ).firstMatch(raw);
  if (match == null) return null;
  final uri = Uri.tryParse(match.group(0)!);
  if (uri == null) return null;
  if (uri.scheme == 'geo') return uri.path.isEmpty ? null : uri;
  return (uri.scheme == 'https' || uri.scheme == 'http') && uri.host.isNotEmpty
      ? uri
      : null;
}
