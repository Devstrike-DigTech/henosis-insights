import 'dart:async';
import 'dart:convert';

import 'package:insights_dart/src/engine.dart';
import 'package:insights_dart/src/types.dart';
import 'package:test/test.dart' hide Retry;

import 'helpers.dart';

/// Stands in for the wrapper's MethodChannel to the native SDK.
class FakeBridge implements BridgeTransport {
  FakeBridge({this.runningAfterProbes = 1, this.failSend = false, this.throwOnProbe = false});

  /// Native reports running from this probe on (1 = first probe); 0 = never.
  final int runningAfterProbes;
  final bool failSend;
  final bool throwOnProbe;
  int probes = 0;
  final List<String> sent = [];

  @override
  Future<bool> isNativeRunning(String writeKey, String sdkName) async {
    probes++;
    if (throwOnProbe) throw StateError('MissingPluginException');
    return runningAfterProbes > 0 && probes >= runningAfterProbes;
  }

  @override
  FutureOr<void> send(String message) {
    if (failSend) throw StateError('channel closed');
    sent.add(message);
  }

  List<Map<String, Object?>> messages() => [for (final m in sent) jsonDecode(m) as Map<String, Object?>];
}

InsightsConfig wrapped({InsightsMode mode = InsightsMode.auto, BridgeTransport? bridge}) => InsightsConfig(
      writeKey: writeKey,
      sdkName: 'acme-payments',
      sdkVersion: '4.2.0',
      wrapperVersion: '2.0.3',
      customerKey: customerHash,
      sdkPackagePrefixes: const ['package:acme_payments_flutter/'],
      mode: mode,
      bridge: bridge,
    );

void main() {
  final engines = <Engine>[];
  tearDown(() {
    for (final e in engines) {
      e.stop();
    }
    engines.clear();
  });

  Future<(Engine, FakeTransport)> start(InsightsConfig cfg, {bool envOptOut = false}) async {
    final t = FakeTransport();
    final e = Engine(
      cfg,
      EngineDeps(
        transport: t,
        platform: flutterAndroid,
        now: Clock().now,
        random: () => 0.5,
        envOptOut: () => envOptOut,
        probeDelays: const [Duration.zero, Duration.zero, Duration.zero],
      ),
    );
    engines.add(e);
    await e.loaded;
    await settle();
    return (e, t);
  }

  group('auto mode', () {
    test('with the native client running: bridged, and this client never uploads (one stream)', () async {
      final bridge = FakeBridge();
      final (e, t) = await start(wrapped(bridge: bridge));
      expect(e.activeMode, InsightsMode.bridged);
      e
        ..countCall('Wrapper.ready')
        ..countCall('Wrapper.ready')
        ..markDeprecated('chargeLegacy')
        ..reportError(
            'charge',
            'INVALID_CURRENCY',
            ArgumentError('currency XX for ada@example.com'),
            StackTrace.fromString(
              '#0      AcmePayments.charge (package:acme_payments_flutter/src/api.dart:12:5)\n'
              '#1      CheckoutPage.pay (package:shop_app/checkout.dart:3:1)',
            ))
        ..check(HealthCheck.custom('currency_supported', false, HealthDetail.unsupportedPlatform));
      await e.flush();
      await settle();
      expect(t.bodies, isEmpty, reason: 'bridged mode must never also send directly');

      final msgs = bridge.messages();
      expect(msgs.first['wrapper'], {'kind': 'flutter', 'version': '2.0.3'}, reason: 'registration first');
      expect(msgs.first['events'], isEmpty);
      final all = msgs.skip(1).toList();
      final calls = <String, int>{};
      for (final m in all) {
        (m['calls'] as Map).forEach((k, v) => calls[k as String] = (calls[k] ?? 0) + (v as int));
      }
      expect(calls, {'Wrapper.ready': 2});
      final events = [for (final m in all) ...(m['events'] as List).cast<Map<String, Object?>>()];
      final error = events.singleWhere((e) => e['kind'] == 'error');
      expect(error['layer'], 'wrapper');
      expect(error['frames'], [
        {'cls': 'acme_payments_flutter/src/api.dart', 'fn': 'AcmePayments.charge', 'line': 12},
      ]);
      expect(jsonEncode(msgs), isNot(contains('ada@example.com')));
      expect(jsonEncode(msgs), isNot(contains('shop_app')));
      expect(events.singleWhere((e) => e['kind'] == 'health')['detail'], 'unsupported_platform');
      expect(msgs.every((m) => m['writeKey'] == writeKey && m['sdkName'] == 'acme-payments'), isTrue);
    });

    test('native found on a later probe: still bridged; calls made meanwhile are kept', () async {
      final bridge = FakeBridge(runningAfterProbes: 3);
      final t = FakeTransport();
      final e = Engine(
        wrapped(bridge: bridge),
        EngineDeps(
          transport: t,
          platform: flutterAndroid,
          now: Clock().now,
          random: () => 0.5,
          envOptOut: () => false,
          probeDelays: const [Duration.zero, Duration(milliseconds: 5), Duration(milliseconds: 5)],
        ),
      );
      engines.add(e);
      e.countCall('early');
      expect(e.activeMode, isNull, reason: 'still probing');
      await e.loaded;
      await e.flush();
      await settle();
      expect(bridge.probes, 3);
      expect(e.activeMode, InsightsMode.bridged);
      expect(t.bodies, isEmpty);
      expect(bridge.messages().any((m) => (m['calls'] as Map)['early'] == 1), isTrue);
    });

    test('native never running (its SDK does not embed Insights): direct, bridge unused after probing', () async {
      final bridge = FakeBridge(runningAfterProbes: 0);
      final (e, t) = await start(wrapped(bridge: bridge));
      expect(bridge.probes, 3);
      expect(e.activeMode, InsightsMode.direct);
      expect(t.kinds(), ['init', 'heartbeat']);
      e.countCall('x');
      await e.flush();
      expect(bridge.sent, isEmpty);
    });

    test('a channel without the bridge methods counts as not running', () async {
      final (e, _) = await start(wrapped(bridge: FakeBridge(throwOnProbe: true)));
      expect(e.activeMode, InsightsMode.direct);
    });

    test('no bridge at all: direct, without probing', () async {
      final (e, t) = await start(wrapped());
      expect(e.activeMode, InsightsMode.direct);
      expect(t.bodies, isNotEmpty);
    });
  });

  group('forced modes', () {
    test('direct ignores a bridge', () async {
      final bridge = FakeBridge();
      final (e, t) = await start(wrapped(mode: InsightsMode.direct, bridge: bridge));
      expect(e.activeMode, InsightsMode.direct);
      expect(bridge.probes, 0);
      expect(bridge.sent, isEmpty);
      expect(t.bodies, isNotEmpty);
    });

    test('bridged never sends directly, even when the native client is absent', () async {
      final bridge = FakeBridge(runningAfterProbes: 0);
      final (e, t) = await start(wrapped(mode: InsightsMode.bridged, bridge: bridge));
      e.countCall('x');
      await e.flush();
      expect(t.bodies, isEmpty);
      expect(bridge.sent, isNotEmpty);
    });

    test('bridged without a bridge is disabled', () async {
      final (e, t) = await start(wrapped(mode: InsightsMode.bridged));
      e.countCall('x');
      await e.flush();
      expect(e.isEnabled, isFalse);
      expect(t.bodies, isEmpty);
    });
  });

  group('opt-out and failures', () {
    test('host opt-out stops forwarding and drops what was pending', () async {
      final bridge = FakeBridge();
      final (e, t) = await start(wrapped(bridge: bridge));
      bridge.sent.clear();
      e
        ..countCall('x')
        ..setHostEnabled(false);
      await e.flush();
      await settle();
      expect(bridge.sent, isEmpty);
      expect(t.bodies, isEmpty);
    });

    test('an environment opt-out forwards nothing, not even the registration', () async {
      final bridge = FakeBridge();
      await start(wrapped(bridge: bridge), envOptOut: true);
      expect(bridge.sent, isEmpty);
    });

    test('a failing send never throws and never falls back to direct', () async {
      final bridge = FakeBridge(failSend: true);
      final (e, t) = await start(wrapped(bridge: bridge));
      e.countCall('x');
      await e.flush();
      await settle();
      expect(t.bodies, isEmpty);
      expect(e.activeMode, InsightsMode.bridged);
    });
  });
}
