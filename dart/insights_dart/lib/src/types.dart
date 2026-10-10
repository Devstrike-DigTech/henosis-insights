import 'dart:async';

import 'names.dart';

/// Fixed values a health check may attach. No free text, so no end-user data can ride along.
enum HealthDetail {
  missing('missing'),
  invalidFormat('invalid_format'),
  notInitialized('not_initialized'),
  wrongThread('wrong_thread'),
  permissionMissing('permission_missing'),
  unsupportedPlatform('unsupported_platform'),
  other('other');

  const HealthDetail(this.wire);

  /// The value sent on the wire.
  final String wire;
}

/// A health check result. Built-in checks have fixed names; [HealthCheck.custom] normalises its name.
final class HealthCheck {
  HealthCheck._(this.name, this.ok, this.detail);

  /// The SDK's own init was (or was not) called before first use.
  factory HealthCheck.initBeforeUse(bool ok) =>
      HealthCheck._('init_before_use', ok, ok ? null : HealthDetail.notInitialized);

  /// The customer key the host app supplied has (or has not) the expected format.
  factory HealthCheck.customerKeyFormat(bool ok) =>
      HealthCheck._('customer_key_format', ok, ok ? null : HealthDetail.invalidFormat);

  /// A permission or capability the SDK needs is (or is not) available.
  factory HealthCheck.permissionGranted(String permission, bool ok) =>
      HealthCheck._(Names.permissionCheck(permission), ok, ok ? null : HealthDetail.permissionMissing);

  /// A vendor-defined check. [detail] comes from the fixed enum; there is no free-text field.
  factory HealthCheck.custom(String name, bool ok, [HealthDetail? detail]) =>
      HealthCheck._(Names.checkName(name), ok, ok ? null : (detail ?? HealthDetail.other));

  final String name;
  final bool ok;
  final HealthDetail? detail;
}

/// Where the client runs. Unknown fields stay null and are omitted from the payload.
final class PlatformInfo {
  const PlatformInfo({
    this.wrapperKind,
    this.os,
    this.osVersion,
    this.deviceClass,
    this.appId,
    this.appVersion,
  });

  /// `flutter`, `web`, `node` or `other`.
  final String? wrapperKind;

  /// `android`, `ios`, `web`, `macos`, `windows`, `linux` or `other`.
  final String? os;
  final String? osVersion;

  /// `phone`, `tablet` or `desktop`.
  final String? deviceClass;

  /// Bundle id / package name on mobile, hostname on web.
  final String? appId;
  final String? appVersion;

  /// Fields of [override] win over this one's.
  PlatformInfo merge(PlatformInfo? override) => override == null
      ? this
      : PlatformInfo(
          wrapperKind: override.wrapperKind ?? wrapperKind,
          os: override.os ?? os,
          osVersion: override.osVersion ?? osVersion,
          deviceClass: override.deviceClass ?? deviceClass,
          appId: override.appId ?? appId,
          appVersion: override.appVersion ?? appVersion,
        );
}

/// Pluggable persistence for the offline queue and state. Without one, everything stays in memory
/// for the life of the process. Wrap `shared_preferences` or a file to persist across launches.
abstract interface class InsightsStorage {
  FutureOr<String?> read(String key);
  FutureOr<void> write(String key, String value);
  FutureOr<void> delete(String key);
}

/// The default storage: an in-memory map.
final class MemoryInsightsStorage implements InsightsStorage {
  final Map<String, String> values = {};

  @override
  String? read(String key) => values[key];

  @override
  void write(String key, String value) => values[key] = value;

  @override
  void delete(String key) => values.remove(key);
}

/// How the client reports. See docs/bridge-protocol.md.
enum InsightsMode {
  /// Bridge when [InsightsConfig.bridge] is set and the native client is running; otherwise direct.
  auto,

  /// Upload batches itself.
  direct,

  /// Forward everything to the native client through [InsightsConfig.bridge]; never upload.
  bridged,
}

/// Supplied by a wrapper package (a Flutter plugin around a native SDK) over its own MethodChannel.
/// The native side calls `HenosisInsights.bridge.isRunning` and `HenosisInsights.bridge.receive`.
abstract interface class BridgeTransport {
  /// Whether the native Insights client runs for this write key and SDK.
  FutureOr<bool> isNativeRunning(String writeKey, String sdkName);

  /// Deliver one bridge message (JSON, schema/bridge-v1.json) to the native client.
  FutureOr<void> send(String message);
}

/// Configuration a vendor SDK passes once to [HenosisInsights.start].
final class InsightsConfig {
  const InsightsConfig({
    required this.writeKey,
    required this.sdkName,
    required this.sdkVersion,
    required this.customerKey,
    this.sdkPackagePrefixes = const [],
    this.wrapperVersion,
    this.sampleRate = 1.0,
    this.endpoint = defaultEndpoint,
    this.storage,
    this.platform,
    this.debug = false,
    this.mode = InsightsMode.auto,
    this.bridge,
  });

  static const String defaultEndpoint = 'https://in.insights.henosis.dev/v1/batches';

  /// Identifies the vendor's SDK project (`hi_live_…` / `hi_test_…`).
  final String writeKey;

  /// The SDK's package name as registered in the dashboard, e.g. `acme_kyc`.
  final String sdkName;
  final String sdkVersion;

  /// A HASH of the vendor's customer key, never the raw key. Non-hash values are hashed and flagged.
  final String customerKey;

  /// Library URI prefixes that identify the SDK's own frames, e.g. `['package:acme_kyc/']`.
  /// Only frames under these are reported. Empty means no frames at all.
  final List<String> sdkPackagePrefixes;

  /// The wrapper package's version; defaults to [sdkVersion] (a pure package is its own wrapper).
  final String? wrapperVersion;

  /// Fraction of installs that report, 0–1, decided once and remembered through [storage].
  final double sampleRate;
  final String endpoint;
  final InsightsStorage? storage;

  /// Overrides for detected platform fields, e.g. an `appId` a Flutter package read natively.
  final PlatformInfo? platform;

  /// Print Insights' own failures. Never prints payload contents.
  final bool debug;

  /// [InsightsMode.auto] by default: bridged inside a wrapper whose native SDK runs Insights.
  final InsightsMode mode;

  /// The wrapper's channel to its native SDK. Only wrappers of a native SDK set this.
  final BridgeTransport? bridge;
}
