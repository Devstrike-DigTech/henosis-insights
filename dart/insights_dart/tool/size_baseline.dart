// A small app that already uses what every real Flutter/Dart web app ships: async/await, JSON,
// RegExp, typed data, UTF-8, DateTime, collections and JS interop. tool/check_size.dart subtracts
// this from the probe, so the budget measures what the client itself adds.
import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'dart:typed_data';

Future<void> main() async {
  final data = <String, Object?>{
    'at': DateTime.now().toUtc().toIso8601String(),
    'n': [1, 2, 3]
  };
  final text = jsonEncode(data);
  final back = jsonDecode(text) as Map<String, Object?>;
  final bytes = Uint8List.fromList(utf8.encode(text));
  final view = ByteData.sublistView(bytes);
  final ok = RegExp(r'^[a-z]+$').hasMatch('abc');
  final done = Completer<void>();
  scheduleMicrotask(done.complete);
  await done.future.timeout(const Duration(seconds: 1));
  final r = (a: 1, b: ok);
  globalContext['x'] = '${back.length} ${view.lengthInBytes} ${r.a}'.toJS;
  try {
    await Future<void>.delayed(Duration.zero);
  } catch (_) {}
}
