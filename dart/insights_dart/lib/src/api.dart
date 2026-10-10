import 'dart:async';
import 'dart:math';

import 'engine.dart';
import 'host.dart' as host;
import 'sha256.dart';
import 'types.dart';

/// Handle for one SDK's stream, returned by [HenosisInsights.start].
///
/// Prefer calling through this handle from inside your SDK. The static calls on [HenosisInsights]
/// work only while exactly one SDK in the app has started Insights; with several they are dropped
/// (never misattributed). Every method returns immediately and never throws.
final class InsightsClient {
  InsightsClient._(this._engine);

  static final InsightsClient _noop = InsightsClient._(null);

  final Engine? _engine;

  /// Count one call of [method]. Counts are batched into one `calls` event per upload window.
  void countCall(String method) => _guard(() => _engine?.countCall(method));

  /// Report an SDK error. Only frames under `sdkPackagePrefixes` are kept; the error's message and
  /// type are never sent. Pass [stackTrace] from `catch (e, st)`.
  void reportError(String method, String code, [Object? error, StackTrace? stackTrace]) =>
      _guard(() => _engine?.reportError(method, code, error, stackTrace));

  void check(HealthCheck result) => _guard(() => _engine?.check(result));

  void markDeprecated(String method) => _guard(() => _engine?.markDeprecated(method));

  /// Upload now. Completes when the attempt finishes; never completes with an error.
  Future<void> flush() {
    try {
      return _engine?.flush().catchError((Object _) {}) ?? Future<void>.value();
    } catch (_) {
      return Future<void>.value();
    }
  }

  bool get isEnabled {
    try {
      return _engine?.isEnabled ?? false;
    } catch (_) {
      return false;
    }
  }

  /// `direct` or `bridged` once decided (null while `auto` mode is still asking the native client).
  InsightsMode? get activeMode {
    try {
      return _engine?.activeMode;
    } catch (_) {
      return null;
    }
  }
}

void _guard(void Function() f) {
  try {
    f();
  } catch (_) {}
}

/// Entry point. See the library documentation.
abstract final class HenosisInsights {
  /// Version of this client, reported as `client.version`.
  static const String version = clientVersion;

  static final Map<String, InsightsClient> _clients = {};
  static bool? _hostEnabled;

  /// SHA-256 hex of a customer's API key, for `customerKey`. The raw key never leaves the device.
  static String hashCustomerKey(String rawKey) => sha256Hex(rawKey);

  /// Start reporting for one SDK and return its handle. The same write key and SDK name return the
  /// same handle. Never throws; on failure returns a handle whose calls do nothing.
  static InsightsClient start(InsightsConfig config) => startWith(config);

  /// Visible for tests: inject a transport, clock and platform.
  static InsightsClient startWith(InsightsConfig config, [EngineDeps? deps]) {
    try {
      final key = '${config.writeKey}|${config.sdkName}';
      final existing = _clients[key];
      if (existing != null) return existing;
      final random = Random();
      final engine = Engine(
        config,
        deps ??
            EngineDeps(
              transport: const host.HostTransport(),
              platform: host.detectPlatform(),
              now: () => DateTime.now().millisecondsSinceEpoch,
              random: random.nextDouble,
              envOptOut: host.envOptOut,
              onPageHide: host.onPageHide,
              refinePlatform: host.refinePlatform,
            ),
      );
      final pending = _hostEnabled;
      if (pending != null) engine.setHostEnabled(pending);
      return _clients[key] = InsightsClient._(engine);
    } catch (_) {
      return InsightsClient._noop;
    }
  }

  static InsightsClient get _single => _clients.length == 1 ? _clients.values.first : InsightsClient._noop;

  static void countCall(String method) => _single.countCall(method);

  static void reportError(String method, String code, [Object? error, StackTrace? stackTrace]) =>
      _single.reportError(method, code, error, stackTrace);

  static void check(HealthCheck result) => _single.check(result);

  static void markDeprecated(String method) => _single.markDeprecated(method);

  static Future<void> flush() => _single.flush();

  /// False when no SDK, or more than one, is running (use the handle then).
  static bool get isEnabled => _single.isEnabled;

  /// Host-app opt-out for every SDK in the app. `false` deletes anything queued; remembered through
  /// each SDK's storage. May be called before any SDK starts.
  static void setEnabled(bool enabled) => _guard(() {
        _hostEnabled = enabled;
        for (final c in _clients.values) {
          c._engine?.setHostEnabled(enabled);
        }
      });

  /// Stop every stream (tests, explicit shutdown). Queued events stay in storage.
  static void stop() => _guard(() {
        for (final c in _clients.values) {
          c._engine?.stop();
        }
        _clients.clear();
      });

  /// Visible for tests.
  static void resetForTests() {
    stop();
    _hostEnabled = null;
  }

  /// Visible for tests.
  static Engine? engineOf(InsightsClient client) => client._engine;
}
