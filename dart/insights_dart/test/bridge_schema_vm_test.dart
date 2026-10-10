@TestOn('vm')
library;

import 'dart:convert';
import 'dart:io';

import 'package:insights_dart/src/engine.dart';
import 'package:insights_dart/src/types.dart';
import 'package:json_schema/json_schema.dart';
import 'package:test/test.dart' hide Retry;

import 'bridge_test.dart' show FakeBridge, wrapped;
import 'helpers.dart';

void main() {
  test('every bridge message matches schema/bridge-v1.json', () async {
    final schema = JsonSchema.create(jsonDecode(File('../../schema/bridge-v1.json').readAsStringSync()) as Object);
    final bridge = FakeBridge();
    final e = Engine(
      wrapped(bridge: bridge),
      EngineDeps(
        transport: FakeTransport(),
        platform: flutterAndroid,
        now: Clock().now,
        random: () => 0.5,
        envOptOut: () => false,
        probeDelays: const [Duration.zero],
      ),
    );
    await e.loaded;
    e
      ..countCall('Wrapper.ready')
      ..countCall('weird name / ü')
      ..markDeprecated('chargeLegacy')
      ..reportError('charge', 'invalid currency!', StateError('x'), StackTrace.current)
      ..reportError('', '')
      ..check(HealthCheck.custom('Currency Supported?', false))
      ..check(HealthCheck.initBeforeUse(true));
    await e.flush();
    await settle();
    expect(bridge.sent, hasLength(greaterThan(1)));
    for (final m in bridge.sent) {
      final r = schema.validate(jsonDecode(m));
      expect(r.isValid, isTrue, reason: '${r.errors}\n$m');
    }
    // And the schema rejects what the native side must never accept from a wrapper.
    final bad = jsonDecode(bridge.sent.last) as Map<String, Object?>;
    (bad['events'] as List).add({'kind': 'heartbeat'});
    expect(schema.validate(bad).isValid, isFalse);
    e.stop();
  });
}
