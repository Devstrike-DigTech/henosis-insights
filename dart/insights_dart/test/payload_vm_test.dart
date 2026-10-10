@TestOn('vm')
library;

import 'dart:convert';
import 'dart:io';

import 'package:insights_dart/insights_dart.dart';
import 'package:insights_dart/src/engine.dart';
import 'package:json_schema/json_schema.dart';
import 'package:test/test.dart';

import 'helpers.dart';

void main() {
  final schemaJson = jsonDecode(File('../../schema/v1.json').readAsStringSync()) as Map<String, Object?>;
  final schema = JsonSchema.create(schemaJson);
  final allowed = <String>{};
  void walk(Object? n) {
    if (n is List) {
      n.forEach(walk);
    } else if (n is Map) {
      if (n['properties'] is Map) allowed.addAll((n['properties'] as Map).keys.cast<String>());
      n.values.forEach(walk);
    }
  }

  walk(schemaJson);

  test('the published schema really rejects unknown fields (so the next test is meaningful)', () {
    final bad = {
      'schema': 1,
      'writeKey': writeKey,
      'client': {'lang': 'dart', 'version': '0.1.0', 'mode': 'direct'},
      'sdk': {'name': 'acme_kyc', 'version': '1.8.0'},
      'wrapper': {'kind': 'flutter'},
      'customerKey': customerHash,
      'app': {},
      'device': {'os': 'web', 'userId': 'u1'},
      'sentAt': '2026-10-10T10:00:00.000Z',
      'events': [
        {'kind': 'heartbeat'},
      ],
    };
    expect(schema.validate(bad).isValid, isFalse);
  });

  test('every batch matches schema v1 and uses only allowed field names', () async {
    final t = FakeTransport();
    final e = Engine(config(customerKey: 'not a hash'), deps(t, Clock()));
    await e.loaded;
    e
      ..countCall('startVerification')
      ..countCall('bad name / ünicode')
      ..reportError('upload', 'upload failed!', StateError('x'), StackTrace.current)
      ..reportError('', '', null, null)
      ..check(HealthCheck.initBeforeUse(false))
      ..check(HealthCheck.permissionGranted('android.permission.CAMERA', false))
      ..check(HealthCheck.custom('Webhook Reachable!', false))
      ..check(HealthCheck.custom('fine', true))
      ..markDeprecated('startLegacy');
    await e.flush();
    expect(t.kinds().toSet(), {'init', 'heartbeat', 'health', 'calls', 'error', 'deprecated'});
    for (final body in t.bodies) {
      final result = schema.validate(jsonDecode(body));
      expect(result.isValid, isTrue, reason: '${result.errors}\n$body');
      expect(fieldNames(jsonDecode(body)).difference(allowed), isEmpty);
    }
    e.stop();
  });
}
