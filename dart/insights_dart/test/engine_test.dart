import 'package:insights_dart/src/engine.dart';
import 'package:insights_dart/src/sha256.dart';
import 'package:insights_dart/src/transport.dart';
import 'package:insights_dart/src/types.dart';
import 'package:test/test.dart' hide Retry;

import 'helpers.dart';

void main() {
  final engines = <Engine>[];
  tearDown(() {
    for (final e in engines) {
      e.stop();
    }
    engines.clear();
  });

  Future<({Engine e, FakeTransport transport, Clock clock})> start({
    InsightsConfig? cfg,
    FakeTransport? transport,
    Clock? clock,
    double Function()? random,
    bool Function()? envOptOut,
    void Function()? Function(void Function())? onPageHide,
  }) async {
    final t = transport ?? FakeTransport();
    final c = clock ?? Clock();
    final e = Engine(cfg ?? config(), deps(t, c, random: random, envOptOut: envOptOut, onPageHide: onPageHide));
    engines.add(e);
    await e.loaded;
    await settle();
    return (e: e, transport: t, clock: c);
  }

  group('startup', () {
    test('uploads init and a heartbeat on first start', () async {
      final s = await start();
      expect(s.transport.kinds(), ['init', 'heartbeat']);
      final b = s.transport.batches().single;
      expect(b['schema'], 1);
      expect(b['writeKey'], writeKey);
      expect(b['client'], {'lang': 'dart', 'version': '0.1.0', 'mode': 'direct'});
      expect(b['wrapper'], {'kind': 'flutter', 'version': '1.8.0'});
      expect(b['device'], {'os': 'android', 'osVersion': '16'});
      expect(b['app'], {'id': 'com.example.verify'});
    });

    test('hashes a raw customer key and flags it; a blank key is missing', () async {
      final raw = await start(cfg: config(customerKey: 'sk live 1'));
      expect(raw.transport.batches().first['customerKey'], sha256Hex('sk live 1'));
      expect(raw.transport.events().firstWhere((e) => e['kind'] == 'health')['detail'], 'invalid_format');
      final blank = await start(cfg: config(customerKey: '', sdkName: 'other'));
      expect(blank.transport.events().firstWhere((e) => e['kind'] == 'health')['detail'], 'missing');
    });

    test('asynchronously refined platform fields are used; a failing refine is ignored', () async {
      Future<Map<String, Object?>> deviceWith(Future<PlatformInfo> Function(PlatformInfo) refine, String sdk) async {
        final t = FakeTransport();
        final e = Engine(
          config(sdkName: sdk),
          EngineDeps(
            transport: t,
            platform: const PlatformInfo(wrapperKind: 'flutter', os: 'android'),
            now: Clock().now,
            random: () => 0.5,
            envOptOut: () => false,
            refinePlatform: refine,
          ),
        );
        engines.add(e);
        await e.loaded;
        await settle();
        return t.batches().first['device'] as Map<String, Object?>;
      }

      expect(await deviceWith((p) async => p.merge(const PlatformInfo(osVersion: '17')), 'refined'), {
        'os': 'android',
        'osVersion': '17',
      });
      expect(await deviceWith((p) => Future.error(StateError('no getprop')), 'refine-fails'), {'os': 'android'});
    });

    test('config.platform overrides detection', () async {
      final s = await start(cfg: config(platform: const PlatformInfo(appId: 'com.override.app', appVersion: '2.0.0')));
      expect(s.transport.batches().first['app'], {'id': 'com.override.app', 'version': '2.0.0'});
    });
  });

  group('upload policy', () {
    test('waits six hours after the last upload, then sends calls as one event', () async {
      final s = await start();
      s.transport.clear();
      s.e.countCall('startVerification');
      s.e.countCall('startVerification');
      s.clock.advance(5 * hour);
      s.e.countCall('uploadDocument');
      await settle();
      expect(s.transport.bodies, isEmpty);
      s.clock.advance(hour);
      s.e.countCall('startVerification');
      await settle();
      final calls = s.transport.events().firstWhere((e) => e['kind'] == 'calls');
      expect(calls['counts'], {'startVerification': 3, 'uploadDocument': 1});
      expect(calls['from'], DateTime.fromMillisecondsSinceEpoch(t0, isUtc: true).toIso8601String());
    });

    test('flush forces an upload', () async {
      final s = await start();
      s.transport.clear();
      s.e.countCall('x');
      await s.e.flush();
      expect(s.transport.kinds(), ['calls']);
    });

    test('a retryable failure keeps events and backs off; a rejection drops them', () async {
      final t = FakeTransport()..result = const Retry(503);
      final s = await start(transport: t);
      await s.e.flush();
      expect(t.bodies, hasLength(1));
      s.clock.advance(retryBackoffMs);
      t.result = const Accepted();
      await s.e.flush();
      expect(t.kinds(), ['init', 'heartbeat', 'init', 'heartbeat']);
      t
        ..clear()
        ..result = const Rejected(400);
      s.e.reportError('x', 'Y');
      await s.e.flush();
      t
        ..result = const Accepted()
        ..clear();
      await s.e.flush();
      expect(t.bodies, isEmpty);
    });

    test('sends large queues in batches of at most 500 events', () async {
      final t = FakeTransport()..result = const Retry();
      final s = await start(transport: t);
      for (var i = 0; i < 1200; i++) {
        s.e.reportError('m', 'E_$i');
      }
      t
        ..result = const Accepted()
        ..clear();
      s.clock.advance(retryBackoffMs);
      await s.e.flush();
      expect([for (final b in t.batches()) (b['events'] as List).length], [500, 500, 202]);
    });

    test('a page-hide flush uses keepalive within the 64 KB limit', () async {
      late void Function() hide;
      final s = await start(onPageHide: (cb) {
        hide = cb;
        return () {};
      });
      s.transport.clear();
      for (var i = 0; i < 400; i++) {
        s.e.reportError('method_$i', 'CODE_$i');
      }
      hide();
      await settle();
      expect(s.transport.keepalive.every((k) => k), isTrue);
      expect(s.transport.bodies.every((b) => b.length <= keepaliveMaxBytes), isTrue);
      expect(s.transport.kinds().where((k) => k == 'error'), hasLength(400));
    });
  });

  group('persistence', () {
    test('queue and counters survive a restart through storage', () async {
      final storage = MemoryInsightsStorage();
      final t1 = FakeTransport()..result = const Retry();
      final first = await start(cfg: config(storage: storage), transport: t1);
      first.e.reportError('upload', 'UPLOAD_FAILED');
      first.e.countCall('upload');
      first.e.stop();
      await settle();
      final t2 = FakeTransport();
      final second = await start(cfg: config(storage: storage), transport: t2, clock: Clock(t0 + 600000));
      await second.e.flush();
      expect(t2.kinds(), contains('error'));
      expect(t2.events().firstWhere((e) => e['kind'] == 'calls')['counts'], {'upload': 1});
    });

    test('async storage works and calls before load are replayed', () async {
      final storage = _AsyncStorage();
      final t = FakeTransport();
      final e = Engine(config(storage: storage), deps(t, Clock()));
      engines.add(e);
      e.countCall('early');
      await e.loaded;
      await e.flush();
      await settle();
      expect(t.kinds(), containsAll(['init', 'heartbeat', 'calls']));
      expect(storage.values.keys.any((k) => k.endsWith(':state')), isTrue);
    });

    test('one heartbeat per UTC day', () async {
      final s = await start();
      s.clock.advance(6 * hour);
      s.e.countCall('a');
      await settle();
      expect(s.transport.kinds().where((k) => k == 'heartbeat'), hasLength(1));
      s.clock.advance(14 * hour);
      await s.e.flush();
      expect(s.transport.kinds().where((k) => k == 'heartbeat'), hasLength(2));
    });
  });

  group('opt-out and sampling', () {
    test('host opt-out deletes everything; opting back in resumes', () async {
      final storage = MemoryInsightsStorage();
      final t = FakeTransport()..result = const Retry();
      final s = await start(cfg: config(storage: storage), transport: t);
      s.e
        ..countCall('a')
        ..reportError('a', 'B')
        ..setHostEnabled(false);
      await settle();
      expect(s.e.isEnabled, isFalse);
      expect(storage.values.keys.where((k) => k.endsWith(':state')), isEmpty);
      t
        ..result = const Accepted()
        ..clear();
      s.clock.advance(retryBackoffMs);
      s.e.countCall('a');
      await s.e.flush();
      expect(t.bodies, isEmpty);
      s.e
        ..setHostEnabled(true)
        ..countCall('a');
      await s.e.flush();
      expect(t.kinds(), contains('calls'));
    });

    test('the host opt-out is remembered for the next start', () async {
      final storage = MemoryInsightsStorage();
      final first = await start(cfg: config(storage: storage));
      first.e.setHostEnabled(false);
      first.e.stop();
      final t = FakeTransport();
      final second = await start(cfg: config(storage: storage, sdkName: 'other'), transport: t);
      expect(second.e.isEnabled, isFalse);
      expect(t.bodies, isEmpty);
    });

    test('an environment opt-out sends nothing', () async {
      final s = await start(envOptOut: () => true);
      s.e.countCall('a');
      await s.e.flush();
      expect(s.transport.bodies, isEmpty);
    });

    test('sampling decides once and remembers the draw', () async {
      final storage = MemoryInsightsStorage();
      final off = await start(cfg: config(storage: storage, sampleRate: 0.3), random: () => 0.5);
      expect(off.e.isEnabled, isFalse);
      await settle();
      off.e.stop();
      final again = await start(cfg: config(storage: storage, sampleRate: 0.3), random: () => 0.0);
      expect(again.e.isEnabled, isFalse);
      expect((await start(cfg: config(sampleRate: 0, sdkName: 'z'))).e.isEnabled, isFalse);
    });
  });

  group('errors', () {
    test('only SDK frames are kept; messages, types and app frames never are', () async {
      final s = await start();
      s.transport.clear();
      final trace = StackTrace.fromString([
        '#0      AcmeKyc.upload (package:acme_kyc/src/kyc.dart:42:7)',
        '#1      VerifyScreen.onTap (package:verify_app/screens/verify.dart:10:3)',
        '#2      AcmeKyc.start.<anonymous closure> (package:acme_kyc/src/kyc.dart:12:9)',
        '<asynchronous suspension>',
        '#3      _rootRun (dart:async/zone.dart:1:1)',
      ].join('\n'));
      s.e.reportError('upload', 'UPLOAD_FAILED', StateError('passport AB123456 for ada@example.com'), trace);
      await s.e.flush();
      final body = s.transport.bodies.join();
      for (final forbidden in [
        'AB123456',
        'ada@example.com',
        'StateError',
        'verify_app',
        'VerifyScreen',
        'dart:async'
      ]) {
        expect(body, isNot(contains(forbidden)));
      }
      expect(s.transport.events().single['frames'], [
        {'cls': 'acme_kyc/src/kyc.dart', 'fn': 'AcmeKyc.upload', 'line': 42},
        {'cls': 'acme_kyc/src/kyc.dart', 'fn': 'AcmeKyc.start.<anonymous_closure>', 'line': 12},
      ]);
    });
  });
}

class _AsyncStorage implements InsightsStorage {
  final Map<String, String> values = {};

  @override
  Future<String?> read(String key) async {
    await Future<void>.delayed(const Duration(milliseconds: 5));
    return values[key];
  }

  @override
  Future<void> write(String key, String value) async => values[key] = value;

  @override
  Future<void> delete(String key) async => values.remove(key);
}
