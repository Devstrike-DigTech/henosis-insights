import 'dart:async';
import 'dart:convert';

import 'package:insights_dart/src/engine.dart';
import 'package:insights_dart/src/transport.dart';
import 'package:insights_dart/src/types.dart';

const writeKey = 'hi_test_0123456789abcdef0123456789abcdef';
const customerHash = 'fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9';

/// 2026-10-10T10:00:00Z
final int t0 = DateTime.utc(2026, 10, 10, 10).millisecondsSinceEpoch;
const int hour = 3600000;

const flutterAndroid =
    PlatformInfo(wrapperKind: 'flutter', os: 'android', osVersion: '16', appId: 'com.example.verify');

InsightsConfig config({
  String customerKey = customerHash,
  String sdkName = 'acme_kyc',
  List<String> prefixes = const ['package:acme_kyc/'],
  double sampleRate = 1.0,
  InsightsStorage? storage,
  PlatformInfo? platform,
}) =>
    InsightsConfig(
      writeKey: writeKey,
      sdkName: sdkName,
      sdkVersion: '1.8.0',
      customerKey: customerKey,
      sdkPackagePrefixes: prefixes,
      sampleRate: sampleRate,
      endpoint: 'http://127.0.0.1:9/v1/batches',
      storage: storage,
      platform: platform,
    );

class FakeTransport implements Transport {
  final List<String> bodies = [];
  final List<bool> keepalive = [];
  SendResult result = const Accepted();
  Object? failure;

  @override
  Future<SendResult> send(String endpoint, String writeKey, String body, {required bool keepalive}) async {
    final f = failure;
    if (f != null) throw f;
    bodies.add(body);
    this.keepalive.add(keepalive);
    return result;
  }

  List<Map<String, Object?>> batches() => [for (final b in bodies) jsonDecode(b) as Map<String, Object?>];

  List<Map<String, Object?>> events() =>
      [for (final b in batches()) ...(b['events'] as List<Object?>).cast<Map<String, Object?>>()];

  List<String> kinds() => [for (final e in events()) e['kind'] as String];

  void clear() {
    bodies.clear();
    keepalive.clear();
  }
}

class Clock {
  Clock([int? start]) : t = start ?? t0;
  int t;
  int now() => t;
  void advance(int ms) => t += ms;
}

EngineDeps deps(
  FakeTransport transport,
  Clock clock, {
  PlatformInfo platform = flutterAndroid,
  double Function()? random,
  bool Function()? envOptOut,
  void Function()? Function(void Function())? onPageHide,
}) =>
    EngineDeps(
      transport: transport,
      platform: platform,
      now: clock.now,
      random: random ?? () => 0.5,
      envOptOut: envOptOut ?? () => false,
      onPageHide: onPageHide,
    );

/// Let queued microtasks and fake-transport futures settle.
Future<void> settle() async {
  for (var i = 0; i < 10; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

/// Every key in [payload] except the method names inside `counts`.
Set<String> fieldNames(Object? payload, [String? parent, Set<String>? out]) {
  final names = out ?? <String>{};
  if (payload is List) {
    for (final p in payload) {
      fieldNames(p, parent, names);
    }
  } else if (payload is Map) {
    payload.forEach((k, v) {
      if (parent != 'counts') names.add(k as String);
      fieldNames(v, k as String, names);
    });
  }
  return names;
}

extension Wait on Future<void> {
  Future<void> get done => then((_) => settle());
}

FutureOr<void> ignore(Object? _) {}
