# insights_dart

Henosis Insights client for Dart and Flutter SDKs. Pure Dart with no package dependencies, so it works
in any Flutter package on Android, iOS, web and desktop, and in Dart server packages.

It reports how your SDK is integrated (versions, call counts, SDK errors, health checks) per customer,
and nothing about end users.

## Add it to your SDK

```dart
import 'package:insights_dart/insights_dart.dart';

final insights = HenosisInsights.start(InsightsConfig(
  writeKey: 'hi_live_…',                                  // from the Insights dashboard
  sdkName: 'acme_kyc',
  sdkVersion: '1.8.0',
  customerKey: HenosisInsights.hashCustomerKey(apiKey),   // a HASH, never the key
  sdkPackagePrefixes: const ['package:acme_kyc/'],        // only these frames are reported
));

insights.countCall('startVerification');
insights.check(HealthCheck.initBeforeUse(initialized));
try { … } catch (e, st) {
  insights.reportError('uploadDocument', 'DOCUMENT_REJECTED', e, st); // the message is never sent
}
insights.markDeprecated('startLegacy');
```

Prefer the handle `start()` returns over the static calls (they apply only while one SDK uses Insights).

## What it detects, without plugins

| Where | `wrapper.kind` | `app.id` | `osVersion` |
| --- | --- | --- | --- |
| Flutter on Android | `flutter` | package name from `/proc/self/cmdline` | `ro.build.version.release` when readable |
| Flutter on iOS / macOS | `flutter` | `CFBundleIdentifier` from the bundle's `Info.plist` (binary or XML) | from the OS version string |
| Flutter web | `flutter` | page hostname | omitted |
| Dart web (no Flutter) | `web` | page hostname | omitted |
| Dart server / CLI | `other` | `name` from `pubspec.yaml` in the working directory | from the OS version string |

Anything that cannot be known is omitted, never guessed. Override with `InsightsConfig.platform`.
Release web builds are minified, so error frames are usually empty there.

## Behaviour

- Every call returns immediately and never throws.
- Storage is in memory by default. Implement `InsightsStorage` (for example over `shared_preferences`)
  to keep the queue and state across launches.
- Uploads: on SDK activity once 6 h have passed since the last upload, on `flush()`, and when a web
  page is hidden. With in-memory storage the first activity of each launch uploads.
- Queue capped at 1 MB (oldest dropped), batches of at most 500 events, 5 min backoff on retryable errors.
- Host opt-out for every SDK in the app: `HenosisInsights.setEnabled(false)`,
  `HENOSIS_INSIGHTS_DISABLED=1` (native) or `globalThis.HENOSIS_INSIGHTS_DISABLED = true` (web).
- Size: adds about 14 KB gzipped to a dart2js `-O4` build over a typical app (budget 16 KB, CI-checked).

## Develop

```bash
dart pub get && dart test -p vm,chrome && dart run tool/check_size.dart
```

Example: `../examples/acme_kyc` (a pure-Dart SDK) and its Flutter app in `example/`.
