import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:uuid/uuid.dart';
import '../services/error_message.dart';
import '../services/native_service.dart';
import '../services/trip_controller.dart';
import '../util/haptics.dart';

/// Finds a place to save: a pasted link or coordinates, or a search.
///
/// Search uses Android's free geocoder, with a button to ask Google instead;
/// with Google search on in Settings it suggests Google places as you type.
/// Pops with the [SharedLocation] picked.
class AddPlaceScreen extends StatefulWidget {
  const AddPlaceScreen({super.key, required this.controller});

  final TripController controller;

  @override
  State<AddPlaceScreen> createState() => _AddPlaceScreenState();
}

/// Text that is read like a share instead of searched for: links, `geo:`
/// URIs, coordinates and plus codes.
bool looksLikeLink(String text) {
  final value = text.trim();
  return RegExp(r'https?://|^geo:', caseSensitive: false).hasMatch(value) ||
      RegExp(r'^-?\d{1,2}(\.\d+)?\s*,\s*-?\d{1,3}(\.\d+)?$').hasMatch(value) ||
      RegExp(
        r'^[23456789CFGHJMPQRVWX]{4,8}\+[23456789CFGHJMPQRVWX]{0,3}\b',
        caseSensitive: false,
      ).hasMatch(value);
}

class _AddPlaceScreenState extends State<AddPlaceScreen> {
  static const _minQuery = 3;
  static const _googleDelay = Duration(milliseconds: 400);
  static const _freeDelay = Duration(milliseconds: 700);

  final _query = TextEditingController();
  Timer? _debounce;

  /// Drops answers to searches the user has typed past.
  int _serial = 0;
  List<PlaceSuggestion> _results = const [];
  bool _searching = false;
  bool _picking = false;
  String? _error;

  /// The query [_results] answer; null before the first search.
  String? _searched;

  /// One Google Autocomplete session runs from the first suggestion to the
  /// place picked, which makes the suggestions free.
  String? _session;

  bool get _googleAsYouType {
    final settings = widget.controller.alarmSettings.value;
    return settings.googleSearch && settings.placesAvailable;
  }

  bool get _googleAvailable =>
      widget.controller.alarmSettings.value.placesAvailable;

  @override
  void dispose() {
    _debounce?.cancel();
    _query.dispose();
    super.dispose();
  }

  void _changed(String text) {
    _debounce?.cancel();
    final query = text.trim();
    setState(() => _error = null);
    if (looksLikeLink(query) || query.length < _minQuery) {
      _serial++;
      setState(() {
        _results = const [];
        _searched = null;
        _searching = false;
      });
      return;
    }
    _debounce = Timer(
      _googleAsYouType ? _googleDelay : _freeDelay,
      () => _search(query, google: _googleAsYouType),
    );
  }

  void _submitted(String text) {
    _debounce?.cancel();
    final query = text.trim();
    if (query.isEmpty) return;
    if (looksLikeLink(query)) {
      _useLink(query);
    } else if (query.length >= 2) {
      _search(query, google: _googleAsYouType);
    }
  }

  Future<void> _search(String query, {required bool google}) async {
    final serial = ++_serial;
    if (google) _session ??= const Uuid().v4();
    setState(() {
      _searching = true;
      _error = null;
    });
    try {
      final results = await NativeService.searchPlaces(
        query,
        google: google,
        session: google ? _session : null,
      );
      if (!mounted || serial != _serial) return;
      setState(() {
        _results = results;
        _searched = query;
        _searching = false;
      });
    } catch (error) {
      if (!mounted || serial != _serial) return;
      setState(() {
        _results = const [];
        _searched = query;
        _searching = false;
        _error = errorMessage(error);
      });
    }
  }

  void _searchGoogle() {
    Haptics.tap();
    final query = _query.text.trim();
    if (query.length >= 2) _search(query, google: true);
  }

  Future<void> _pick(Future<SharedLocation?> Function() locate) async {
    if (_picking) return;
    Haptics.tap();
    _debounce?.cancel();
    _serial++;
    setState(() {
      _picking = true;
      _searching = false;
      _error = null;
    });
    try {
      final place = await locate();
      if (!mounted) return;
      if (place == null) {
        setState(() => _error = 'Nothing to add here.');
      } else {
        Navigator.of(context).pop(place);
      }
    } catch (error) {
      Haptics.error();
      if (mounted) setState(() => _error = errorMessage(error));
    } finally {
      if (mounted) setState(() => _picking = false);
    }
  }

  void _useLink(String text) => _pick(() => NativeService.parsePlace(text));

  void _useResult(PlaceSuggestion result) => _pick(() async {
    if (!result.fromGoogle) {
      return SharedLocation(
        lat: result.lat!,
        lng: result.lng!,
        name: result.name,
      );
    }
    final session = _session;
    // A new search after this starts a new session.
    _session = null;
    return NativeService.placeLocation(result, session: session);
  });

  Future<void> _paste() async {
    Haptics.tap();
    final text = (await Clipboard.getData(Clipboard.kTextPlain))?.text?.trim();
    if (text == null || text.isEmpty || !mounted) return;
    _query.text = text;
    _query.selection = TextSelection.collapsed(offset: text.length);
    _changed(text);
  }

  void _clear() {
    Haptics.tap();
    _query.clear();
    _changed('');
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    final text = _query.text.trim();
    final link = looksLikeLink(text);
    final fromGoogle = _results.any((result) => result.fromGoogle);
    final offerGoogle =
        !_googleAsYouType &&
        _googleAvailable &&
        !fromGoogle &&
        _searched != null &&
        _searched == text &&
        !_searching;
    return Scaffold(
      appBar: AppBar(title: const Text('Add place')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: [
          TextField(
            controller: _query,
            autofocus: true,
            enabled: !_picking,
            textInputAction: TextInputAction.search,
            onChanged: _changed,
            onSubmitted: _submitted,
            decoration: InputDecoration(
              prefixIcon: const Icon(Icons.search),
              hintText: 'Search or paste a link',
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(28),
              ),
              suffixIcon: text.isEmpty
                  ? IconButton(
                      tooltip: 'Paste',
                      onPressed: _picking ? null : _paste,
                      icon: const Icon(Icons.content_paste),
                    )
                  : IconButton(
                      tooltip: 'Clear',
                      onPressed: _picking ? null : _clear,
                      icon: const Icon(Icons.close),
                    ),
            ),
          ),
          if (_searching || _picking)
            const Padding(
              padding: EdgeInsets.only(top: 12),
              child: LinearProgressIndicator(),
            ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 16, 8, 0),
              child: Text(
                _error!,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.error,
                ),
              ),
            ),
          const SizedBox(height: 8),
          if (text.isEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 16, 8, 0),
              child: Text(
                'Paste a Google Maps link or coordinates like 12.97, 77.59, '
                'or search for a place or address.',
                style: muted,
              ),
            )
          else if (link)
            ListTile(
              leading: const Icon(Icons.link),
              title: const Text('Use this link'),
              subtitle: Text(
                text,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              enabled: !_picking,
              onTap: () => _useLink(text),
            )
          else ...[
            for (final result in _results)
              ListTile(
                leading: Icon(
                  result.fromGoogle ? Icons.place : Icons.place_outlined,
                ),
                title: Text(result.name),
                subtitle: result.address == null
                    ? null
                    : Text(
                        result.address!,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                enabled: !_picking,
                onTap: () => _useResult(result),
              ),
            if (_searched == text && _results.isEmpty && _error == null)
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
                child: Text('No places found.', style: muted),
              ),
            if (offerGoogle)
              ListTile(
                leading: const Icon(Icons.travel_explore),
                title: Text('Search Google for “$text”'),
                subtitle: const Text('Finds shops and places by name'),
                enabled: !_picking,
                onTap: _searchGoogle,
              ),
            if (fromGoogle)
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
                child: Text('Powered by Google', style: muted),
              ),
          ],
        ],
      ),
    );
  }
}
