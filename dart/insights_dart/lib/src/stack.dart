import 'names.dart';

typedef Frame = ({String cls, String fn, int line});

const _maxFrames = 64;

// VM / AOT: "#0      AcmeKyc.upload (package:acme_kyc/src/kyc.dart:42:7)"
final _vm = RegExp(r'^#\d+\s+(.+?) \((.+?):(\d+)(?::\d+)?\)$');
// Dart dev compiler (debug web): "packages/acme_kyc/src/kyc.dart 42:7   upload"
final _ddc = RegExp(r'^(\S+)\s+(\d+):\d+\s+(.+)$');

/// Keep only frames inside the SDK's own libraries. The error's message, its type, and every other
/// frame (the app's, Flutter's) are discarded here and never read into an event.
List<Frame> sdkFrames(StackTrace? trace, List<String> prefixes) {
  if (trace == null || prefixes.isEmpty) return const [];
  // `package:acme_kyc/` also appears as `packages/acme_kyc/` in web debug traces.
  final matchers = <String>{
    for (final p in prefixes.where((p) => p.isNotEmpty)) ...[p, p.replaceFirst('package:', 'packages/')],
  };
  final out = <Frame>[];
  for (final line in trace.toString().split('\n')) {
    String? file, fn;
    int? ln;
    final vm = _vm.firstMatch(line.trim());
    if (vm != null) {
      fn = vm.group(1);
      file = vm.group(2);
      ln = int.tryParse(vm.group(3)!);
    } else {
      final ddc = _ddc.firstMatch(line.trim());
      if (ddc != null) {
        file = ddc.group(1);
        ln = int.tryParse(ddc.group(2)!);
        fn = ddc.group(3);
      }
    }
    if (file == null || !matchers.any(file.startsWith)) continue;
    final cls = Names.frameClass(file.replaceFirst(RegExp(r'^packages?[:/]'), ''));
    if (cls == null) continue;
    out.add((cls: cls, fn: Names.frameFn(fn ?? '<anonymous>'), line: ln ?? 0));
    if (out.length >= _maxFrames) break;
  }
  return out;
}
