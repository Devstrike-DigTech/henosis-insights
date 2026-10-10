/// Flutter wrapper of the Acme Payments Android SDK.
///
/// The native SDK already reports through Henosis Insights. This wrapper adds the Dart client in
/// `auto` mode with a bridge over its own MethodChannel, so the app still sends exactly one stream
/// for `acme-payments`, tagged `wrapper.kind = flutter`, and errors caught here are tagged
/// `layer: wrapper`.
library;

import 'package:flutter/services.dart';
import 'package:insights_dart/insights_dart.dart';

abstract final class AcmePaymentsFlutter {
  /// This wrapper's version (reported as `wrapper.version`).
  static const String version = '2.0.3';

  /// Version of the native Acme Payments SDK this wrapper ships with.
  static const String nativeVersion = '4.2.0';

  /// Public fixture key; a real SDK ships the write key from its Insights dashboard.
  static const String defaultWriteKey = 'hi_test_0123456789abcdef0123456789abcdef';

  static const MethodChannel _channel = MethodChannel('acme_payments_flutter');
  static InsightsClient? _insights;

  static Future<void> init({
    required String apiKey,
    String insightsWriteKey = defaultWriteKey,
    String insightsEndpoint = InsightsConfig.defaultEndpoint,
  }) async {
    await _channel.invokeMethod<void>('init', {
      'apiKey': apiKey,
      'insightsWriteKey': insightsWriteKey,
      'insightsEndpoint': insightsEndpoint,
    });
    // Started after the native SDK's init, so the native Insights client is running and `auto`
    // mode picks bridged. The same write key and SDK name route the messages to that stream.
    _insights = HenosisInsights.start(InsightsConfig(
      writeKey: insightsWriteKey,
      sdkName: 'acme-payments',
      sdkVersion: nativeVersion,
      wrapperVersion: version,
      customerKey: HenosisInsights.hashCustomerKey(apiKey),
      sdkPackagePrefixes: const ['package:acme_payments_flutter/'],
      endpoint: insightsEndpoint,
      bridge: const _ChannelBridge(_channel),
    ));
  }

  /// Charges [amountMinor] in [currency]. Validation happens here, in the wrapper; the payment itself
  /// in the native SDK (which counts the call and reports its own errors).
  static Future<String> charge(int amountMinor, String currency) async {
    if (!RegExp(r'^[A-Z]{3}$').hasMatch(currency)) {
      try {
        throw ArgumentError.value(currency, 'currency', 'must be an ISO 4217 code');
      } catch (e, st) {
        _insights?.reportError('charge', 'INVALID_CURRENCY', e, st);
      }
      return 'invalid_currency';
    }
    return await _channel.invokeMethod<String>('charge', {'amountMinor': amountMinor, 'currency': currency}) ??
        'error';
  }

  /// A Dart-only helper the native SDK knows nothing about, so the wrapper counts it.
  static String formatAmount(int amountMinor, String currency) {
    _insights?.countCall('formatAmount');
    return '$currency ${(amountMinor / 100).toStringAsFixed(2)}';
  }

  @Deprecated('Use charge()')
  static Future<String> chargeLegacy(int amountMinor) async =>
      await _channel.invokeMethod<String>('chargeLegacy', {'amountMinor': amountMinor}) ?? 'error';

  /// Sends what is queued now, from both layers.
  static Future<void> flushTelemetry() async {
    await _insights?.flush();
    await _channel.invokeMethod<void>('flush');
  }

  /// Visible for the example app: `bridged` once the Dart client found the native one.
  static InsightsMode? get insightsMode => _insights?.activeMode;
}

/// The bridge over this plugin's own MethodChannel (see docs/bridge-protocol.md).
final class _ChannelBridge implements BridgeTransport {
  const _ChannelBridge(this._channel);

  final MethodChannel _channel;

  @override
  Future<bool> isNativeRunning(String writeKey, String sdkName) async =>
      await _channel.invokeMethod<bool>('insightsIsRunning', {'writeKey': writeKey, 'sdkName': sdkName}) ?? false;

  @override
  Future<void> send(String message) => _channel.invokeMethod<void>('insightsForward', {'message': message});
}
