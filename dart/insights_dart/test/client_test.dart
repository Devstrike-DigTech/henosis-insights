import 'package:insights_dart/insights_dart.dart';
import 'package:insights_dart/src/transport.dart';
import 'package:test/test.dart';

import 'helpers.dart';

void main() {
  tearDown(HenosisInsights.resetForTests);

  InsightsClient start(String sdk, FakeTransport t, {InsightsStorage? storage}) =>
      HenosisInsights.startWith(config(sdkName: sdk, storage: storage), deps(t, Clock()));

  int callCount(FakeTransport t) =>
      t.events().where((e) => e['kind'] == 'calls').fold(0, (n, e) => n + ((e['counts'] as Map)['send'] as int? ?? 0));

  group('routing', () {
    test('same SDK, same handle; static calls reach the only one', () async {
      final t = FakeTransport();
      final a = start('acme_kyc', t);
      expect(identical(start('acme_kyc', t), a), isTrue);
      HenosisInsights.countCall('send');
      await HenosisInsights.flush();
      expect(callCount(t), 1);
    });

    test('with two SDKs, static calls are dropped and handles stay separate', () async {
      final ta = FakeTransport(), tb = FakeTransport();
      final a = start('acme_kyc', ta), b = start('acme_chat', tb);
      HenosisInsights.countCall('send');
      await HenosisInsights.flush();
      a.countCall('send');
      b
        ..countCall('send')
        ..countCall('send');
      await Future.wait([a.flush(), b.flush()]);
      expect(callCount(ta), 1);
      expect(callCount(tb), 2);
      expect(HenosisInsights.isEnabled, isFalse);
    });

    test('host opt-out before start is honoured', () async {
      HenosisInsights.setEnabled(false);
      final t = FakeTransport();
      final a = start('acme_kyc', t);
      a.countCall('send');
      await a.flush();
      expect(a.isEnabled, isFalse);
      expect(t.bodies, isEmpty);
    });
  });

  group('never throws', () {
    void exercise(InsightsClient c) {
      for (final s in ['', 'x' * 10000, 'ünï\u0000', 'ok']) {
        c
          ..countCall(s)
          ..markDeprecated(s)
          ..reportError(s, s, Exception(s), StackTrace.current)
          ..reportError(s, s)
          ..check(HealthCheck.custom(s, false));
        HenosisInsights.countCall(s);
        HenosisInsights.reportError(s, s, StackOverflowError());
      }
    }

    test('before start and after stop', () async {
      exercise(HenosisInsights.start(config()));
      HenosisInsights.stop();
      await HenosisInsights.flush();
      HenosisInsights.setEnabled(true);
    });

    test('with storage that throws and a transport that throws', () async {
      final t = FakeTransport()..failure = Exception('network down');
      final c = start('acme_kyc', t, storage: _ThrowingStorage());
      exercise(c);
      await c.flush();
      HenosisInsights.setEnabled(false);
      await settle();
    });

    test('a transport that never answers never blocks the caller', () async {
      final c = HenosisInsights.startWith(config(), deps(FakeTransport(), Clock()));
      final sw = Stopwatch()..start();
      for (var i = 0; i < 10000; i++) {
        c.countCall('send');
      }
      expect(sw.elapsedMilliseconds, lessThan(500));
      expect(classify(202), isA<Accepted>());
    });
  });
}

class _ThrowingStorage implements InsightsStorage {
  @override
  String? read(String key) => throw StateError('boom');
  @override
  Future<void> write(String key, String value) => Future.error(StateError('boom'));
  @override
  void delete(String key) => throw StateError('boom');
}
