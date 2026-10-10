import 'dart:async';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'names.dart';
import 'transport.dart';
import 'types.dart';

const bool _isFlutter = bool.fromEnvironment('dart.library.ui');

T? _try<T>(T? Function() f) {
  try {
    return f();
  } catch (_) {
    return null;
  }
}

JSObject? _obj(JSObject? parent, String name) => _try(() => parent?[name] as JSObject?);

PlatformInfo detectPlatform() {
  final hostname = _try(() => (_obj(globalContext, 'location')?['hostname'] as JSString?)?.toDart);
  final mobile = _try(() => (_obj(_obj(globalContext, 'navigator'), 'userAgentData')?['mobile'] as JSBoolean?)?.toDart);
  return PlatformInfo(
    wrapperKind: _isFlutter ? 'flutter' : 'web',
    os: 'web',
    deviceClass: mobile == null ? null : (mobile ? 'phone' : 'desktop'),
    appId: Names.appId(hostname),
  );
}

Future<PlatformInfo> refinePlatform(PlatformInfo p) async => p;

bool envOptOut() => _try(() => (globalContext['HENOSIS_INSIGHTS_DISABLED'] as JSBoolean?)?.toDart == true) ?? false;

/// Calls [callback] when the page is hidden or unloaded, so queued events can leave with it.
void Function()? onPageHide(void Function() callback) => _try(() {
      final document = _obj(globalContext, 'document');
      if (document == null) return null;
      final onHide = ((JSAny? _) => callback()).toJS;
      final onVisibility = ((JSAny? _) {
        if ((document['visibilityState'] as JSString?)?.toDart == 'hidden') callback();
      }).toJS;
      globalContext.callMethod<JSAny?>('addEventListener'.toJS, 'pagehide'.toJS, onHide);
      document.callMethod<JSAny?>('addEventListener'.toJS, 'visibilitychange'.toJS, onVisibility);
      return () {
        _try(() => globalContext.callMethod<JSAny?>('removeEventListener'.toJS, 'pagehide'.toJS, onHide));
        _try(() => document.callMethod<JSAny?>('removeEventListener'.toJS, 'visibilitychange'.toJS, onVisibility));
      };
    });

/// The browser's `fetch`. No third-party HTTP package.
final class HostTransport implements Transport {
  const HostTransport();

  @override
  Future<SendResult> send(String endpoint, String writeKey, String body, {required bool keepalive}) async {
    try {
      final headers = JSObject()
        ..['content-type'] = 'application/json'.toJS
        ..['authorization'] = 'Bearer $writeKey'.toJS;
      final init = JSObject()
        ..['method'] = 'POST'.toJS
        ..['headers'] = headers
        ..['body'] = body.toJS
        ..['keepalive'] = (keepalive && body.length <= keepaliveMaxBytes).toJS
        ..['credentials'] = 'omit'.toJS;
      final promise = globalContext.callMethod<JSPromise<JSObject>>('fetch'.toJS, endpoint.toJS, init);
      final response = await promise.toDart.timeout(requestTimeout);
      return classify((response['status'] as JSNumber).toDartInt);
    } catch (_) {
      return const Retry();
    }
  }
}
