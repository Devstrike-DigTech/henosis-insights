import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'names.dart';
import 'plist.dart';
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

String? _firstVersion(String text) => RegExp(r'\d+(?:\.\d+)+').firstMatch(text)?.group(0);

/// Android: the process name is the package name (`com.example.app` or `com.example.app:remote`).
String? _androidPackage() => _try(() {
      final raw = File('/proc/self/cmdline').readAsStringSync();
      return raw.split('\u0000').first.split(':').first.trim();
    });

String? _androidRelease() => _try(() {
      for (final line in File('/system/build.prop').readAsLinesSync()) {
        if (line.startsWith('ro.build.version.release=')) return line.substring(25).trim();
      }
      return null;
    });

Map<String, String> _bundleInfo(String plistPath) =>
    _try(() => plistStrings(File(plistPath).readAsBytesSync(), {'CFBundleIdentifier', 'CFBundleShortVersionString'})) ??
    const {};

Map<String, String> _pubspec() =>
    _try(() {
      final text = File('${Directory.current.path}/pubspec.yaml').readAsStringSync();
      return {
        for (final key in ['name', 'version'])
          if (RegExp('^$key:\\s*["\']?([^"\'\\s#]+)', multiLine: true).firstMatch(text) case final m?) key: m.group(1)!,
      };
    }) ??
    const {};

String _exeName() => Platform.resolvedExecutable.split(Platform.pathSeparator).last.replaceFirst(RegExp(r'\.exe$'), '');

PlatformInfo detectPlatform() {
  final os = Platform.isAndroid
      ? 'android'
      : Platform.isIOS
          ? 'ios'
          : Platform.isMacOS
              ? 'macos'
              : Platform.isWindows
                  ? 'windows'
                  : Platform.isLinux
                      ? 'linux'
                      : 'other';
  String? appId, appVersion, osVersion;
  switch (os) {
    case 'android':
      appId = _androidPackage();
      osVersion = _androidRelease();
    case 'ios':
      final info = _bundleInfo('${File(Platform.resolvedExecutable).parent.path}/Info.plist');
      appId = info['CFBundleIdentifier'];
      appVersion = info['CFBundleShortVersionString'];
      osVersion = _firstVersion(Platform.operatingSystemVersion);
    case 'macos':
      // <App>.app/Contents/MacOS/<exe> -> <App>.app/Contents/Info.plist
      final info = _bundleInfo('${File(Platform.resolvedExecutable).parent.parent.path}/Info.plist');
      appId = info['CFBundleIdentifier'];
      appVersion = info['CFBundleShortVersionString'];
      osVersion = _firstVersion(Platform.operatingSystemVersion);
    default:
      osVersion = _firstVersion(Platform.operatingSystemVersion);
  }
  if (!_isFlutter) {
    // A Dart server or CLI: identify it by its package.
    final pubspec = _pubspec();
    appId ??= pubspec['name'];
    appVersion ??= pubspec['version'];
  } else if (appId == null && (os == 'windows' || os == 'linux')) {
    appId = _exeName();
  }
  final desktop = os == 'macos' || os == 'windows' || os == 'linux';
  return PlatformInfo(
    wrapperKind: _isFlutter ? 'flutter' : 'other',
    os: os,
    osVersion: Names.version(osVersion),
    deviceClass: _isFlutter && desktop ? 'desktop' : null,
    appId: Names.appId(appId),
    appVersion: Names.version(appVersion),
  );
}

/// Android hides `build.prop` from apps on recent versions; `getprop` still answers. Async, so it
/// never blocks the caller; runs at most once per start.
Future<PlatformInfo> refinePlatform(PlatformInfo p) async {
  if (p.os != 'android' || p.osVersion != null) return p;
  try {
    final r = await Process.run('getprop', ['ro.build.version.release']).timeout(const Duration(seconds: 2));
    final v = Names.version('${r.stdout}'.trim());
    return v == null ? p : p.merge(PlatformInfo(osVersion: v));
  } catch (_) {
    return p;
  }
}

bool envOptOut() =>
    _try(() {
      final v = Platform.environment['HENOSIS_INSIGHTS_DISABLED'];
      return v == '1' || v == 'true';
    }) ??
    false;

void Function()? onPageHide(void Function() callback) => null;

/// `dart:io` HttpClient only. No third-party HTTP package.
final class HostTransport implements Transport {
  const HostTransport();

  @override
  Future<SendResult> send(String endpoint, String writeKey, String body, {required bool keepalive}) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 10)
      ..userAgent = 'henosis-insights-dart';
    try {
      final request = await client.postUrl(Uri.parse(endpoint)).timeout(requestTimeout);
      final bytes = utf8.encode(body);
      request.headers
        ..set(HttpHeaders.contentTypeHeader, 'application/json')
        ..set(HttpHeaders.authorizationHeader, 'Bearer $writeKey');
      request.contentLength = bytes.length;
      request.add(bytes);
      final response = await request.close().timeout(requestTimeout);
      await response.drain<void>();
      return classify(response.statusCode);
    } catch (_) {
      return const Retry();
    } finally {
      client.close(force: true);
    }
  }
}
