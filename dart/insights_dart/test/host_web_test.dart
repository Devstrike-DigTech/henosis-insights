@TestOn('browser')
library;

import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:insights_dart/src/host_web.dart';
import 'package:insights_dart/src/transport.dart';
import 'package:test/test.dart' hide Retry;

// A CORS-enabled ingest stand-in, run on the VM next to the browser.
const _server = '''
import 'dart:convert';
import 'dart:io';
import 'package:stream_channel/stream_channel.dart';

Future<void> hybridMain(StreamChannel<Object?> channel) async {
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  server.listen((req) async {
    req.response.headers
      ..set('access-control-allow-origin', '*')
      ..set('access-control-allow-headers', 'authorization,content-type')
      ..set('access-control-allow-methods', 'POST');
    if (req.method == 'OPTIONS') {
      req.response.statusCode = 204;
    } else {
      final body = await utf8.decoder.bind(req).join();
      channel.sink.add({'body': body, 'auth': req.headers.value('authorization')});
      req.response.statusCode = 202;
    }
    await req.response.close();
  });
  channel.sink.add(server.port);
}
''';

void main() {
  test('reports the page hostname as app id and web as os', () {
    final p = detectPlatform();
    expect(p.os, 'web');
    expect(p.wrapperKind, 'web');
    expect(p.appId, Uri.base.host);
  });

  test('fetch posts cross-origin with the bearer write key and no cookies', () async {
    final channel = spawnHybridCode(_server);
    final messages = StreamQueue(channel.stream);
    final port = await messages.next as int;
    final result = await const HostTransport()
        .send('http://127.0.0.1:$port/v1/batches', 'hi_test_web', '{"schema":1}', keepalive: true);
    expect(result, isA<Accepted>());
    final seen = await messages.next as Map;
    expect(seen['body'], '{"schema":1}');
    expect(seen['auth'], 'Bearer hi_test_web');
  });

  test('an unreachable endpoint is retryable, not thrown', () async {
    expect(
        await const HostTransport().send('http://127.0.0.1:9/v1/batches', 'k', '{}', keepalive: false), isA<Retry>());
  });

  test('the global opt-out flag is honoured', () {
    expect(envOptOut(), isFalse);
    globalContext['HENOSIS_INSIGHTS_DISABLED'] = true.toJS;
    expect(envOptOut(), isTrue);
    globalContext.delete('HENOSIS_INSIGHTS_DISABLED'.toJS);
  });

  test('page hide fires on visibilitychange to hidden', () {
    var fired = 0;
    final unregister = onPageHide(() => fired++);
    expect(unregister, isNotNull);
    globalContext.callMethod<JSAny?>('dispatchEvent'.toJS, _event('pagehide'));
    expect(fired, 1);
    unregister!();
    globalContext.callMethod<JSAny?>('dispatchEvent'.toJS, _event('pagehide'));
    expect(fired, 1);
  });
}

JSObject _event(String type) => (globalContext['Event'] as JSFunction).callAsConstructor<JSObject>(type.toJS);

class StreamQueue<T> {
  StreamQueue(Stream<T> stream) {
    stream.listen(_items.add);
  }
  final List<T> _items = [];
  Future<T> get next async {
    while (_items.isEmpty) {
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
    return _items.removeAt(0);
  }
}
