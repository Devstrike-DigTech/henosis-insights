@TestOn('vm')
library;

import 'dart:convert';
import 'dart:io';

import 'package:insights_dart/src/host_io.dart';
import 'package:insights_dart/src/transport.dart';
import 'package:test/test.dart' hide Retry;

void main() {
  test('a Dart program (not Flutter) is identified by its pubspec, never by a device id', () {
    final p = detectPlatform();
    expect(p.wrapperKind, 'other');
    expect(
        p.os,
        Platform.isMacOS
            ? 'macos'
            : Platform.isLinux
                ? 'linux'
                : isNotNull);
    expect(p.appId, 'insights_dart');
    expect(p.appVersion, '0.1.0');
    expect(p.deviceClass, isNull, reason: 'a server is not a desktop device');
    expect(p.osVersion, anyOf(isNull, matches(RegExp(r'^\d+(\.\d+)+$'))));
  });

  group('HostTransport (dart:io)', () {
    late HttpServer server;
    final seen = <({String body, String? auth, String? type})>[];
    var status = 202;

    setUp(() async {
      seen.clear();
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((req) async {
        seen.add((
          body: await utf8.decoder.bind(req).join(),
          auth: req.headers.value('authorization'),
          type: req.headers.value('content-type'),
        ));
        req.response.statusCode = status;
        await req.response.close();
      });
    });
    tearDown(() => server.close(force: true));

    String url() => 'http://127.0.0.1:${server.port}/v1/batches';

    test('posts JSON with the write key as a bearer token', () async {
      status = 202;
      final r = await const HostTransport().send(url(), 'hi_test_x', '{"schema":1}', keepalive: false);
      expect(r, isA<Accepted>());
      expect(seen.single.body, '{"schema":1}');
      expect(seen.single.auth, 'Bearer hi_test_x');
      expect(seen.single.type, 'application/json');
    });

    test('classifies failures and never throws on a refused connection', () async {
      status = 400;
      expect(await const HostTransport().send(url(), 'k', '{}', keepalive: false), isA<Rejected>());
      status = 503;
      expect(await const HostTransport().send(url(), 'k', '{}', keepalive: false), isA<Retry>());
      expect(
          await const HostTransport().send('http://127.0.0.1:9/v1/batches', 'k', '{}', keepalive: false), isA<Retry>());
    });
  });

  test('HENOSIS_INSIGHTS_DISABLED is read from the environment', () {
    expect(envOptOut(), Platform.environment['HENOSIS_INSIGHTS_DISABLED'] == '1');
  });
}
