// Size budget (CLAUDE.md rule 4): bytes the client adds to an optimised (-O4) JavaScript build,
// gzipped, measured as probe minus a baseline app that already uses the Dart runtime features every
// real web app ships (see tool/size_baseline.dart).
import 'dart:io';

const budgetGzipBytes = 16 * 1024;

Future<int> gzippedJs(String entry) async {
  final out = '${Directory.systemTemp.createTempSync('insights_size').path}/out.js';
  final r = await Process.run(Platform.resolvedExecutable, ['compile', 'js', '-O4', '-o', out, entry]);
  if (r.exitCode != 0) throw StateError('dart compile js failed:\n${r.stdout}\n${r.stderr}');
  return gzip.encode(File(out).readAsBytesSync()).length;
}

Future<void> main() async {
  final probe = await gzippedJs('tool/size_probe.dart');
  final baseline = await gzippedJs('tool/size_baseline.dart');
  final added = probe - baseline;
  print('insights_dart adds $added bytes gzipped to a dart2js -O4 build (budget $budgetGzipBytes); '
      'probe $probe, baseline $baseline');
  if (added > budgetGzipBytes) {
    stderr.writeln('over budget by ${added - budgetGzipBytes} bytes');
    exitCode = 1;
  }
}
