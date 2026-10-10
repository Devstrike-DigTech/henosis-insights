import 'dart:async';
import 'dart:convert';

import 'names.dart';
import 'stack.dart';
import 'transport.dart';
import 'types.dart';

const String clientVersion = '0.1.0';
const int queueCapBytes = 1000000;
const int maxIntervalMs = 6 * 60 * 60 * 1000;
const int retryBackoffMs = 5 * 60 * 1000;
const int maxEventsPerBatch = 500;
const int _maxChecks = 50;
const int _maxCounts = 500;
const String hostEnabledKey = 'henosis-insights:host_enabled';

/// Everything the engine needs from outside, injectable for tests.
final class EngineDeps {
  EngineDeps({
    required this.transport,
    required this.platform,
    required this.now,
    required this.random,
    required this.envOptOut,
    this.onPageHide,
    this.refinePlatform,
    this.probeDelays = const [Duration.zero, Duration(milliseconds: 500), Duration(seconds: 2)],
  });

  final Transport transport;
  final PlatformInfo platform;
  final int Function() now;
  final double Function() random;
  final bool Function() envOptOut;
  final void Function()? Function(void Function() callback)? onPageHide;

  /// Fills platform fields that need asynchronous work (e.g. Android's OS version), off the caller's path.
  final Future<PlatformInfo> Function(PlatformInfo platform)? refinePlatform;

  /// When `auto` mode asks the native client whether it is running (each delay, then a probe).
  final List<Duration> probeDelays;
}

String _iso(int ms) => DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true).toIso8601String();
String _day(int ms) => _iso(ms).substring(0, 10);

/// The engine for one SDK. Every public method returns immediately and never throws; work runs
/// after stored state loads, and calls made before that are replayed in order.
final class Engine {
  Engine(this.config, this.deps)
      : key = '${config.writeKey}|${config.sdkName}',
        _storage = config.storage ?? MemoryInsightsStorage(),
        _customer = Names.customerKey(config.customerKey),
        _prefixes = config.sdkPackagePrefixes.where((p) => p.isNotEmpty).toList(),
        _platform = deps.platform,
        _scope = 'henosis-insights:${Names.sdkName(config.sdkName)}:'
            '${config.writeKey.length > 8 ? config.writeKey.substring(config.writeKey.length - 8) : config.writeKey}' {
    loaded = _load();
  }

  final InsightsConfig config;
  final EngineDeps deps;
  final String key;
  final InsightsStorage _storage;
  final ({String key, bool ok, bool missing}) _customer;
  final List<String> _prefixes;
  final String _scope;
  PlatformInfo _platform;
  late final Future<void> loaded;

  InsightsMode? _active;
  final Map<String, int> _bridgeCalls = {};
  final Map<String, int> _bridgeDeprecated = {};
  final List<Map<String, Object?>> _bridgeEvents = [];
  bool _forwardScheduled = false;
  bool _ready = false;
  bool _stopped = false;
  bool _enabled = false;
  bool _hostEnabled = true;
  final List<void Function()> _pending = [];

  List<String> _queue = [];
  int _queueBytes = 0;
  Map<String, int> _calls = {};
  Map<String, int> _deprecated = {};
  int _windowFrom = 0;
  int _lastUploadAt = 0;
  int _nextAttemptAt = 0;
  String _heartbeatDay = '';
  double? _sample;
  Future<void>? _uploading;
  bool _saveScheduled = false;
  void Function()? _unregisterPageHide;

  String get _stateKey => '$_scope:state';

  /// `direct` or `bridged` once decided; null while `auto` mode is still probing.
  InsightsMode? get activeMode => _active;

  bool get _bridged => _active == InsightsMode.bridged;

  // ---- public entry points --------------------------------------------------------------------

  void countCall(String method) => _run(() {
        final m = Names.method(method);
        if (_bridged) return _bridgeCount(_bridgeCalls, m);
        if (!_calls.containsKey(m) && _calls.length >= _maxCounts) return;
        if (_windowFrom == 0) _windowFrom = deps.now();
        _calls[m] = (_calls[m] ?? 0) + 1;
        _onActivity();
      });

  void reportError(String method, String code, [Object? error, StackTrace? stackTrace]) => _run(() {
        final trace = stackTrace ?? (error is Error ? error.stackTrace : null);
        final frames = sdkFrames(trace, _prefixes);
        final event = <String, Object?>{
          'kind': 'error',
          'method': Names.method(method),
          'code': Names.code(code),
          // Inside a wrapper of a native SDK, this client's errors come from the wrapper layer.
          'layer': _bridged ? 'wrapper' : 'native',
          'frames': [
            for (final f in frames) {'cls': f.cls, 'fn': f.fn, 'line': f.line}
          ],
        };
        if (_bridged) return _bridgeEvent(event);
        _enqueue(event);
        _onActivity();
      });

  void check(HealthCheck result) => _run(() {
        if (_bridged) return _bridgeEvent(_health(result.name, result.ok, result.detail));
        _enqueue(_health(result.name, result.ok, result.detail));
        _onActivity();
      });

  void markDeprecated(String method) => _run(() {
        final m = Names.method(method);
        if (_bridged) return _bridgeCount(_bridgeDeprecated, m);
        if (!_deprecated.containsKey(m) && _deprecated.length >= _maxCounts) return;
        _deprecated[m] = (_deprecated[m] ?? 0) + 1;
        _onActivity();
      });

  /// Upload now regardless of the 6-hour policy. Completes when the attempt finishes; never fails.
  Future<void> flush({bool keepalive = false}) {
    final done = Completer<void>();
    _run(() {
      if (_bridged) {
        _forward().whenComplete(done.complete);
        return;
      }
      _heartbeatIfDue();
      _maybeUpload(force: true, keepalive: keepalive).whenComplete(done.complete);
    }, onDropped: done.complete);
    return done.future;
  }

  /// Host opt-out, shared by every SDK. `false` deletes everything queued, immediately.
  void setHostEnabled(bool value) {
    _hostEnabled = value;
    _safe(() => _storage.write(hostEnabledKey, '$value'));
    _run(() {
      final was = _enabled;
      _enabled = _computeEnabled();
      if (was && !_enabled) {
        _queue = [];
        _queueBytes = 0;
        _calls = {};
        _deprecated = {};
        _windowFrom = 0;
        _bridgeCalls.clear();
        _bridgeDeprecated.clear();
        _bridgeEvents.clear();
      }
      if (!_bridged) _scheduleSave();
    }, always: true);
  }

  bool get isEnabled => _enabled && !_stopped;

  void stop() {
    _run(_scheduleSave);
    _stopped = true;
    _unregisterPageHide?.call();
  }

  void log(String message, [Object? error]) {
    if (!config.debug) return;
    try {
      // ignore: avoid_print
      print('[HenosisInsights] $message ${error ?? ''}');
    } catch (_) {}
  }

  // ---- internals -------------------------------------------------------------------------------

  void _safe(FutureOr<void> Function() f) {
    try {
      final r = f();
      if (r is Future<void>) r.catchError((Object e) => log('storage write failed', e));
    } catch (e) {
      log('Insights call failed', e);
    }
  }

  void _run(void Function() op, {void Function()? onDropped, bool always = false}) {
    if (_stopped) {
      onDropped?.call();
      return;
    }
    void wrapped() {
      try {
        if (_enabled || always) {
          op();
        } else {
          onDropped?.call();
        }
      } catch (e) {
        log('Insights call failed', e);
        onDropped?.call();
      }
    }

    if (_ready) {
      wrapped();
    } else if (_pending.length < 10000) {
      _pending.add(wrapped);
    } else {
      onDropped?.call();
    }
  }

  Future<String?> _read(String k) async {
    try {
      return await _storage.read(k);
    } catch (e) {
      log('storage read failed', e);
      return null;
    }
  }

  Future<void> _load() async {
    try {
      final refine = deps.refinePlatform;
      if (refine != null) {
        try {
          _platform = await refine(_platform);
        } catch (_) {}
      }
      final host = await _read(hostEnabledKey);
      if (host == 'false') _hostEnabled = false;
      _active = await _resolveMode();
      if (_bridged) {
        // The native client owns storage, sampling, init and heartbeat. Register the wrapper now.
        _enabled = config.bridge != null && _computeEnabled();
        if (_enabled) unawaited(_forward());
        return;
      }
      final state = await _read(_stateKey);
      if (state != null) _restore(state);
      _enabled = _computeEnabled();
      if (_enabled) {
        _startSession();
      } else {
        _scheduleSave();
      }
    } catch (e) {
      log('Insights start failed', e);
    } finally {
      _ready = true;
      final ops = List.of(_pending);
      _pending.clear();
      for (final op in ops) {
        op();
      }
      if (_enabled && !_stopped && !_bridged) {
        try {
          _unregisterPageHide = deps.onPageHide?.call(() => unawaited(flush(keepalive: true)));
        } catch (_) {}
      }
    }
  }

  Future<InsightsMode> _resolveMode() async {
    final bridge = config.bridge;
    switch (config.mode) {
      case InsightsMode.direct:
        return InsightsMode.direct;
      case InsightsMode.bridged:
        return InsightsMode.bridged;
      case InsightsMode.auto:
        if (bridge == null) return InsightsMode.direct;
        for (final delay in deps.probeDelays) {
          if (delay > Duration.zero) await Future<void>.delayed(delay);
          if (await _probe(bridge)) return InsightsMode.bridged;
        }
        return InsightsMode.direct;
    }
  }

  Future<bool> _probe(BridgeTransport bridge) async {
    try {
      return await Future<bool>.value(bridge.isNativeRunning(config.writeKey, config.sdkName))
          .timeout(const Duration(seconds: 2));
    } catch (e) {
      log('bridge probe failed', e);
      return false;
    }
  }

  void _bridgeCount(Map<String, int> into, String method) {
    if (!into.containsKey(method) && into.length >= _maxCounts) return;
    into[method] = ((into[method] ?? 0) + 1).clamp(1, 1000000);
    _scheduleForward();
  }

  void _bridgeEvent(Map<String, Object?> event) {
    if (_bridgeEvents.length >= maxEventsPerBatch) _bridgeEvents.removeAt(0);
    _bridgeEvents.add(event);
    _scheduleForward();
  }

  void _scheduleForward() {
    if (_forwardScheduled) return;
    _forwardScheduled = true;
    Timer.run(() {
      _forwardScheduled = false;
      unawaited(_forward());
    });
  }

  /// Sends what has accumulated (or just the wrapper registration) to the native client.
  Future<void> _forward() async {
    final bridge = config.bridge;
    if (bridge == null || !_enabled || _stopped) return;
    final kind = _platform.merge(config.platform).wrapperKind;
    final version = Names.version(config.wrapperVersion ?? config.sdkVersion);
    final message = jsonEncode({
      'bridge': 1,
      'writeKey': config.writeKey,
      'sdkName': config.sdkName,
      'wrapper': {
        'kind': const {'flutter', 'react-native', 'web', 'node'}.contains(kind) ? kind : 'other',
        if (version != null) 'version': version,
      },
      'client': {'lang': 'dart', 'version': clientVersion},
      'calls': Map.of(_bridgeCalls),
      'deprecated': Map.of(_bridgeDeprecated),
      'events': List.of(_bridgeEvents),
    });
    _bridgeCalls.clear();
    _bridgeDeprecated.clear();
    _bridgeEvents.clear();
    try {
      await bridge.send(message);
    } catch (e) {
      // Never fall back to sending directly: that would make a second stream.
      log('bridge send failed', e);
    }
  }

  void _restore(String raw) {
    try {
      final s = jsonDecode(raw) as Map<String, Object?>;
      for (final e in (s['queue'] as List<Object?>? ?? const [])) {
        if (e is String) _offer(e);
      }
      _calls = {
        for (final MapEntry(:key, :value) in (s['calls'] as Map<String, Object?>? ?? const {}).entries)
          if (value is int) key: value,
      };
      _deprecated = {
        for (final MapEntry(:key, :value) in (s['deprecated'] as Map<String, Object?>? ?? const {}).entries)
          if (value is int) key: value,
      };
      _windowFrom = (s['windowFrom'] as num?)?.toInt() ?? 0;
      _lastUploadAt = (s['lastUploadAt'] as num?)?.toInt() ?? 0;
      _heartbeatDay = s['heartbeatDay'] as String? ?? '';
      _sample = (s['sample'] as num?)?.toDouble();
    } catch (e) {
      log('stored state unreadable; starting fresh', e);
    }
  }

  bool _computeEnabled() {
    if (!_hostEnabled || deps.envOptOut()) return false;
    if (_bridged) return true; // sampling is the native client's decision
    final rate = config.sampleRate.isNaN ? 0.0 : config.sampleRate.clamp(0.0, 1.0);
    if (rate <= 0) return false;
    if (rate >= 1) return true;
    if (_sample == null) {
      _sample = deps.random();
      _scheduleSave();
    }
    return _sample! < rate;
  }

  Map<String, Object?> _health(String name, bool ok, HealthDetail? detail) => {
        'kind': 'health',
        'check': Names.checkName(name),
        'ok': ok,
        if (!ok && detail != null) 'detail': detail.wire,
      };

  void _startSession() {
    final keyCheck = _customer.missing
        ? (ok: false, detail: HealthDetail.missing)
        : (ok: _customer.ok, detail: _customer.ok ? null : HealthDetail.invalidFormat);
    final checks = [(name: 'customer_key_format', ok: keyCheck.ok, detail: keyCheck.detail)];
    _enqueue({
      'kind': 'init',
      'ok': checks.every((c) => c.ok),
      'checks': [
        for (final c in checks.take(_maxChecks)) {'name': c.name, 'ok': c.ok}
      ],
    });
    for (final c in checks) {
      if (!c.ok) _enqueue(_health(c.name, c.ok, c.detail));
    }
    _heartbeatIfDue();
    unawaited(_maybeUpload(force: false, keepalive: false));
    _scheduleSave();
  }

  void _offer(String json) {
    final cost = json.length + 1;
    if (cost > queueCapBytes) return;
    while (_queueBytes + cost > queueCapBytes && _queue.isNotEmpty) {
      _queueBytes -= _queue.removeAt(0).length + 1;
    }
    _queue.add(json);
    _queueBytes += cost;
  }

  void _enqueue(Map<String, Object?> event) => _offer(jsonEncode(event));

  void _heartbeatIfDue() {
    final today = _day(deps.now());
    if (_heartbeatDay == today) return;
    _enqueue({'kind': 'heartbeat'});
    _heartbeatDay = today;
  }

  void _onActivity() {
    _heartbeatIfDue();
    unawaited(_maybeUpload(force: false, keepalive: false));
    _scheduleSave();
  }

  bool get _hasPending => _queue.isNotEmpty || _calls.isNotEmpty || _deprecated.isNotEmpty;

  void _flushCounters(int now) {
    if (_calls.isNotEmpty) {
      final top = _calls.entries.toList()..sort((a, b) => b.value.compareTo(a.value));
      _enqueue({
        'kind': 'calls',
        'from': _iso(_windowFrom == 0 ? now : _windowFrom),
        'to': _iso(now),
        'counts': {for (final e in top.take(_maxCounts)) e.key: e.value},
      });
      _calls = {};
      _windowFrom = 0;
    }
    if (_deprecated.isNotEmpty) {
      for (final e in _deprecated.entries) {
        _enqueue({'kind': 'deprecated', 'method': e.key, 'count': e.value});
      }
      _deprecated = {};
    }
  }

  String _envelope(int sentAt, List<String> events) {
    final p = _platform.merge(config.platform);
    final appId = Names.appId(p.appId);
    final appVersion = Names.version(p.appVersion);
    final osVersion = Names.version(p.osVersion);
    final wrapperKind = p.wrapperKind ?? 'other';
    final wrapperVersion = Names.version(config.wrapperVersion ?? config.sdkVersion);
    final head = jsonEncode({
      'schema': 1,
      'writeKey': config.writeKey,
      'client': {'lang': 'dart', 'version': clientVersion, 'mode': 'direct'},
      'sdk': {'name': Names.sdkName(config.sdkName), 'version': Names.version(config.sdkVersion) ?? '0'},
      'wrapper': {
        'kind': wrapperKind,
        if (wrapperVersion != null && wrapperKind != 'native') 'version': wrapperVersion
      },
      'customerKey': _customer.key,
      'app': {if (appId != null) 'id': appId, if (appVersion != null) 'version': appVersion},
      'device': {
        'os': p.os ?? 'other',
        if (osVersion != null) 'osVersion': osVersion,
        if (p.deviceClass != null) 'class': p.deviceClass,
      },
      'sentAt': _iso(sentAt),
    });
    // Events are already serialised; splice them in rather than re-encoding.
    return '${head.substring(0, head.length - 1)},"events":[${events.join(',')}]}';
  }

  Future<void> _maybeUpload({required bool force, required bool keepalive}) {
    final running = _uploading;
    if (running != null) {
      return force ? running.then((_) => _maybeUpload(force: true, keepalive: keepalive)) : running;
    }
    final now = deps.now();
    if (!_hasPending || now < _nextAttemptAt) return Future.value();
    if (!force && now - _lastUploadAt < maxIntervalMs) return Future.value();
    _flushCounters(now);
    final upload = _uploadLoop(now, keepalive).whenComplete(() {
      _uploading = null;
      _scheduleSave();
    });
    _uploading = upload;
    return upload;
  }

  Future<void> _uploadLoop(int now, bool keepalive) async {
    try {
      while (_queue.isNotEmpty && _enabled && !_stopped) {
        var take = _queue.length < maxEventsPerBatch ? _queue.length : maxEventsPerBatch;
        var batch = _envelope(now, _queue.sublist(0, take));
        while (keepalive && batch.length > keepaliveMaxBytes && take > 1) {
          take = take ~/ 2;
          batch = _envelope(now, _queue.sublist(0, take));
        }
        final first = _queue.first;
        final result = await deps.transport.send(config.endpoint, config.writeKey, batch, keepalive: keepalive);
        if (result is Retry) {
          _nextAttemptAt = deps.now() + retryBackoffMs;
          log('upload deferred (${result.status ?? 'network'})');
          return;
        }
        if (_queue.isNotEmpty && identical(_queue.first, first)) {
          _queue.removeRange(0, take);
          _queueBytes = _queue.fold(0, (n, e) => n + e.length + 1);
        }
        if (result is Accepted) {
          _lastUploadAt = now;
          _nextAttemptAt = 0;
        } else if (result is Rejected) {
          log('batch rejected with ${result.status}; $take events dropped');
        }
      }
    } catch (e) {
      _nextAttemptAt = deps.now() + retryBackoffMs;
      log('upload failed', e);
    }
  }

  void _scheduleSave() {
    if (_saveScheduled) return;
    _saveScheduled = true;
    scheduleMicrotask(() {
      _saveScheduled = false;
      if (!_enabled) {
        // Keep nothing but the sampling draw, so a sampled-out install stays sampled out.
        final sample = _sample;
        _safe(() =>
            sample == null ? _storage.delete(_stateKey) : _storage.write(_stateKey, jsonEncode({'sample': sample})));
        return;
      }
      _safe(() => _storage.write(
            _stateKey,
            jsonEncode({
              'queue': _queue,
              'calls': _calls,
              'deprecated': _deprecated,
              'windowFrom': _windowFrom,
              'lastUploadAt': _lastUploadAt,
              'heartbeatDay': _heartbeatDay,
              if (_sample != null) 'sample': _sample,
            }),
          ));
    });
  }
}
