# Integration guide

Add Henosis Insights to an SDK you have already built, in about fifteen minutes per mode. Pick the
section that matches how you ship:

| You ship | Mode | Section |
| --- | --- | --- |
| An Android SDK (Kotlin or Java) used by native apps | Native only | [A](#a-native-android-sdk) |
| An Android SDK, plus Flutter and/or React Native wrapper packages around it | Native SDK with wrappers | [A](#a-native-android-sdk), then [B](#b-flutter-and-react-native-wrappers-of-your-android-sdk) |
| A Flutter / Dart package written in Dart only (any platform) | Pure package | [C](#c-pure-dart-or-flutter-package) |
| A React Native, web or Node package written in JavaScript / TypeScript only | Pure package | [D](#d-pure-javascript-or-typescript-package) |

Before you start, read [`privacy/DATA.md`](../privacy/DATA.md): it lists everything Insights can send.

---

## 0. Before any code

1. Sign in to the dashboard with your work email (you get a sign-in link by email).
2. Create a project for your SDK. It opens on **Setup**, which walks you through the rest and turns
   green when the first event arrives.
3. Create a **write key**: `hi_test_…` for development, `hi_live_…` for releases. It is shown once;
   it identifies your project, not your customers, and it ships inside your SDK.
4. Decide your **SDK name** (lowercase, e.g. `acme-payments`). Use exactly the same string in every
   client of the same SDK: native, Flutter wrapper and React Native wrapper.
5. Decide how to compute the **customer key**: a hash of your customer's API key (or of whatever
   identifies the business using your SDK). Never pass the raw key. Each section shows how.

### The endpoint

Every client sends to `https://in.insights.henosis.dev/v1/batches` unless you pass `endpoint`
(`InsightsConfig.DEFAULT_ENDPOINT` in Kotlin, `InsightsConfig.defaultEndpoint` in Dart, omitted in
TypeScript). Your SDK's public `init` usually does not take an endpoint, so give it an **optional**
parameter that defaults to the real one; your sample app (and your wrappers) can then point it at a
staging or local ingest:

```kotlin
fun init(context: Context, apiKey: String, insightsEndpoint: String = InsightsConfig.DEFAULT_ENDPOINT)
```

### Getting the client libraries

| Client | Released | Before release (design partners) |
| --- | --- | --- |
| Android | Maven: `com.devstrike.henosis:insights:0.1.0` | Clone this repo, run `cd android && ./gradlew :insights:publishToMavenLocal`, and add `mavenLocal()` to the repositories of your SDK **and** of every app or wrapper that builds against it |
| Dart | pub.dev: `insights_dart: ^0.1.0` | Git dependency (below) |
| TypeScript | npm: `@devstrike/insights-js` | Clone this repo, run `cd js && pnpm install && pnpm build && cd insights-js && npm pack --pack-destination /tmp`, then `npm install /tmp/devstrike-insights-js-0.1.0.tgz` |

```yaml
# Dart, before release. Pin a commit: without `ref` you get whatever is on main.
dependencies:
  insights_dart:
    git:
      url: https://github.com/Devstrike-DigTech/henosis-insights.git
      path: dart/insights_dart
      ref: <commit sha>
```

Pre-release dependencies have two consequences. A package with a Git dependency cannot be published
to pub.dev, so add `publish_to: none` to its pubspec until `insights_dart` is on pub.dev. And
`npm install <tarball>` records a `file:` dependency; replace it with `"@devstrike/insights-js": "^0.1.0"`
before you publish your own package.

---

## A. Native Android SDK

**1. Add the dependency** to your SDK module:

```kotlin
dependencies {
    implementation("com.devstrike.henosis:insights:0.1.0")
}
```

It adds under 100 KB, no runtime dependency other than the Kotlin stdlib (Kotlin 2.2+), works from
`minSdk 21`, asks consumers for compileSdk 23 or newer only, and needs only the `INTERNET` permission,
which it declares itself.

**2. Start it in your SDK's init** and keep the handle:

```kotlin
package com.acme.payments

import android.content.Context
import com.devstrike.henosis.insights.HenosisInsights
import com.devstrike.henosis.insights.InsightsClient
import com.devstrike.henosis.insights.InsightsConfig
import java.security.MessageDigest

object AcmePayments {
    const val VERSION = "4.2.0"
    private var insights: InsightsClient? = null

    @JvmStatic
    @JvmOverloads
    fun init(context: Context, apiKey: String, insightsEndpoint: String = InsightsConfig.DEFAULT_ENDPOINT) {
        insights = HenosisInsights.start(
            context,
            InsightsConfig(
                writeKey = "hi_live_…",                              // your project's write key
                sdkName = "acme-payments",
                sdkVersion = VERSION,
                customerKey = sha256(apiKey),                        // never the raw key
                sdkPackagePrefixes = listOf("com.acme.payments."),   // only these stack frames are sent
                requiredPermissions = listOf("android.permission.CAMERA"), // optional, checked at start
                endpoint = insightsEndpoint,
                debug = false,                                       // true logs to logcat, tag HenosisInsights
            ),
        )
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
```

`sdkPackagePrefixes` is a plain prefix of the class name. Make it specific to the SDK's code: if your
sample app's package is under it (`com.acme.payments.sample`), the sample's frames match too. Give
sample apps another package, or list the SDK's sub-packages.

**3. Report from your public methods:**

```kotlin
import com.devstrike.henosis.insights.HealthCheck

private var initChecked = false

fun charge(amount: Long): String {
    insights?.countCall("charge")                  // counted on the device, sent as one event per window
    insights?.checkWorkerThread("charge")          // only if charge() must not run on the main thread
    if (!initChecked) {                            // report a check once (or when its result changes),
        initChecked = true                         // not on every call: each check is one event
        insights?.check(HealthCheck.InitBeforeUse(ok = insights != null))
    }
    return try {
        gateway.post(amount)
    } catch (e: IllegalStateException) {
        insights?.reportError("charge", code = "CARD_DECLINED_CONFIG", throwable = e) // message never sent
        "declined"
    }
}

@Deprecated("Use charge()")
fun chargeLegacy(amount: Long): String {
    insights?.markDeprecated("chargeLegacy")
    return charge(amount)
}
```

Use your own stable **error codes** (`UPPER_SNAKE_CASE`): they are what the dashboard groups by.
`HealthCheck.Custom("webhook_reachable", ok, HealthDetail.OTHER)` adds your own checks.

**4. Keep your class names in minified apps.** Add to your SDK's `consumer-rules.pro` (and
`consumerProguardFiles("consumer-rules.pro")` in its `defaultConfig`), or your frames are obfuscated
and dropped:

```proguard
-keepnames class com.acme.payments.** { *; }
```

If your SDK also ships Flutter or React Native wrappers, ask consumers for a modest compileSdk too:
AGP 9 otherwise stamps your own `compileSdk` into the AAR, and current Flutter apps compile against 36.

```kotlin
android { defaultConfig { aarMetadata { minCompileSdk = 23 } } }
```

**5. Check it.** In your sample app, pass a local or staging `insightsEndpoint` and `debug = true`,
then watch logcat for `HenosisInsights`: `uploaded N events` means the batch was accepted, and
`upload deferred (…)` or `batch rejected with …` say why not (see [Troubleshooting](#troubleshooting)).
The first automatic upload goes out on SDK activity a few seconds after start; `insights?.flush()`
asks for one now. `flush()` returns immediately and the upload happens on Insights' own thread. In a
JVM or Robolectric test, use `insights.flushBlocking(5_000)`, which waits for the attempt (never call it
on the main thread). The dashboard's Setup page turns green when the first batch arrives.

Everything Insights does is wrapped: a failure inside it never throws into your SDK or the app.

---

## B. Flutter and React Native wrappers of your Android SDK

Your Android SDK already reports (section A). Each wrapper adds the Dart or TypeScript client in
**bridged** mode: it hands its events to your native SDK's Insights client over the channel your
wrapper already has, so each app sends **one** stream for your SDK, tagged with the wrapper's kind
and version. Errors the wrapper reports are tagged `layer: wrapper`.

**1. Native side of the wrapper** (Kotlin, in your plugin or module). Depend on your SDK and on the
**same** Insights version it uses, then add two calls to your existing channel handler:

```kotlin
// plugin's build.gradle(.kts); before release also add mavenLocal() here and in the example app
dependencies {
    implementation("com.acme:payments:4.2.0")
    implementation("com.devstrike.henosis:insights:0.1.0")
}
```

```kotlin
import com.devstrike.henosis.insights.HenosisInsights

// Flutter MethodChannel handler (onMethodCall)
"insightsIsRunning" -> result.success(
    HenosisInsights.bridge.isRunning(call.argument<String>("writeKey") ?: "", call.argument<String>("sdkName") ?: ""),
)
"insightsForward" -> {
    HenosisInsights.bridge.receive(call.argument<String>("message") ?: "")
    result.success(null)
}
```

For a React Native TurboModule, declare `insightsIsRunning(writeKey: string, sdkName: string): boolean`
and `insightsForward(message: string): void` in your spec and implement them with the same two calls.

**2. Flutter (Dart side).** Add `insights_dart` to the plugin's `pubspec.yaml`, then start the client
**after** your native init has run:

```dart
import 'package:flutter/services.dart';
import 'package:insights_dart/insights_dart.dart';

const MethodChannel _channel = MethodChannel('acme_payments_flutter');
InsightsClient? _insights;

class _ChannelBridge implements BridgeTransport {
  const _ChannelBridge(this._channel);
  final MethodChannel _channel;

  @override
  Future<bool> isNativeRunning(String writeKey, String sdkName) async =>
      await _channel.invokeMethod<bool>('insightsIsRunning', {'writeKey': writeKey, 'sdkName': sdkName}) ?? false;

  @override
  Future<void> send(String message) => _channel.invokeMethod<void>('insightsForward', {'message': message});
}

Future<void> init(String apiKey, {String insightsEndpoint = InsightsConfig.defaultEndpoint}) async {
  await _channel.invokeMethod('init', {'apiKey': apiKey, 'insightsEndpoint': insightsEndpoint}); // native starts Insights
  _insights = HenosisInsights.start(InsightsConfig(
    writeKey: 'hi_live_…',                 // the same write key as the native SDK
    sdkName: 'acme-payments',              // exactly the native SDK's name
    sdkVersion: '4.2.0',                   // the native SDK version you ship
    wrapperVersion: '2.0.3',               // this plugin's version
    customerKey: HenosisInsights.hashCustomerKey(apiKey), // used only if it falls back to direct
    sdkPackagePrefixes: const ['package:acme_payments_flutter/'],
    endpoint: insightsEndpoint,            // used only if it falls back to direct
    bridge: const _ChannelBridge(_channel),
  ));
}
```

**3. React Native (TypeScript side).** Add `@devstrike/insights-js` to the package, then:

```ts
import { HenosisInsights, hashCustomerKey, type InsightsClient } from '@devstrike/insights-js';
import Native from './NativeAcmePayments';

let insights: InsightsClient | undefined;

export async function init(apiKey: string, insightsEndpoint?: string) {
  await Native.init(apiKey, insightsEndpoint ?? ''); // your native SDK starts Insights here
  insights = HenosisInsights.start({
    writeKey: 'hi_live_…',
    sdkName: 'acme-payments',
    sdkVersion: '4.2.0',
    wrapperVersion: '0.9.4',
    customerKey: hashCustomerKey(apiKey),
    sdkPackagePrefixes: ['@acme/payments-react-native/'],
    ...(insightsEndpoint ? { endpoint: insightsEndpoint } : {}),
    bridge: {
      isNativeRunning: (writeKey, sdkName) => Native.insightsIsRunning(writeKey, sdkName),
      send: (message) => Native.insightsForward(message),
    },
  });
}
```

**4. Report from the wrapper** only what the native SDK cannot see: validation in the wrapper,
wrapper-only helpers. Do **not** count a call in both layers: if the wrapper's `charge()` calls the
native `charge()` that already counts `charge`, the wrapper should not count it. A validation error
that you detect (rather than catch) still needs a stack trace for its frames:

```dart
if (!RegExp(r'^[A-Z]{3}$').hasMatch(currency)) {
  _insights?.reportError('charge', 'INVALID_CURRENCY', ArgumentError.value(currency), StackTrace.current);
  return 'invalid_currency';
}
```

```ts
if (!/^[A-Z]{3}$/.test(currency)) {
  insights?.reportError('charge', 'INVALID_CURRENCY', new Error('invalid currency')); // new Error() carries the stack
  return 'invalid_currency';
}
```

**5. Check it.** The client asks the native side whether it runs (up to three times over about 2.5
seconds) and then picks `bridged`, or `direct` if your native SDK does not run Insights (so wrappers
keep working with older native versions). `await insights.flush()` completes only after that
decision, so this is a reliable check in your example app:

```dart
await _insights!.flush();
print(_insights!.activeMode);   // InsightsMode.bridged
```

```ts
await insights?.flush();
console.log(insights?.activeMode); // 'bridged'
```

In the dashboard the integration shows *through a wrapper (bridged)* with your wrapper's kind and
version. With `debug: true` the Dart and TS clients print `mode: bridged`.

A complete working example of both wrappers: [`examples/wrapped-sdk`](../examples/wrapped-sdk).
Protocol details: [`bridge-protocol.md`](bridge-protocol.md).

---

## C. Pure Dart or Flutter package

For a package written in Dart only, used on Android, iOS, web, desktop or a Dart server. The client
sends directly; it needs no plugin and no dependency.

```dart
import 'package:insights_dart/insights_dart.dart';

InsightsClient? _insights;
bool _initChecked = false;

void init({required String apiKey, String insightsEndpoint = InsightsConfig.defaultEndpoint}) {
  _insights = HenosisInsights.start(InsightsConfig(
    writeKey: 'hi_live_…',
    sdkName: 'acme_kyc',
    sdkVersion: '1.8.0',
    customerKey: HenosisInsights.hashCustomerKey(apiKey),
    sdkPackagePrefixes: const ['package:acme_kyc/'],
    endpoint: insightsEndpoint,
    debug: false,                                   // true prints mode, uploads and failures
  ));
}

String startVerification() {
  _insights?.countCall('startVerification');
  if (!_initChecked) {
    _initChecked = true;
    _insights?.check(HealthCheck.initBeforeUse(_insights != null));
  }
  …
}

void upload(List<int> bytes) {
  try {
    …
  } catch (e, st) {
    _insights?.reportError('uploadDocument', 'DOCUMENT_REJECTED', e, st); // message never sent
  }
}
```

It detects the platform itself (the app id on Android, iOS and macOS, the hostname on the web) and
reports `wrapper.kind = flutter` inside Flutter apps, `other` in plain Dart programs.

**Storage.** By default the queue lives in memory, so events not yet uploaded are lost when the app
quits, and each launch uploads on its first activity. To keep them across launches, implement
`InsightsStorage` (`read`, `write`, `delete` of strings) over a store your package already uses and
pass it as `storage:`.

**Check it.** `await _insights?.flush()` uploads now and completes when the attempt is done; with
`debug: true` it prints `[HenosisInsights] uploaded N events`. The Setup page turns green.

---

## D. Pure JavaScript or TypeScript package

For a React Native package written in JS/TS only, a browser SDK, or a Node package. ESM and CommonJS,
no dependencies, about 7 KB gzipped.

```ts
import { HealthCheck, HenosisInsights, hashCustomerKey, type InsightsClient } from '@devstrike/insights-js';

let insights: InsightsClient | undefined;
let initChecked = false;

export function init(options: { apiKey: string; insightsEndpoint?: string }) {
  insights = HenosisInsights.start({
    writeKey: 'hi_live_…',
    sdkName: 'acme-chat',
    sdkVersion: '0.9.4',
    customerKey: hashCustomerKey(options.apiKey),
    sdkPackagePrefixes: ['@acme/chat/'],             // path fragment of your package in stack traces
    ...(options.insightsEndpoint ? { endpoint: options.insightsEndpoint } : {}),
    // storage: AsyncStorage,                        // optional: React Native AsyncStorage or window.localStorage
    // debug: true,                                  // console.warn for mode, uploads and failures
  });
}

export function sendMessage(room: string, text: string) {
  insights?.countCall('sendMessage');
  if (!initChecked) {
    initChecked = true;
    insights?.check(HealthCheck.initBeforeUse(insights !== undefined));
  }
  try {
    …
  } catch (error) {
    insights?.reportError('sendMessage', 'ROOM_NOT_FOUND', error); // message never sent
  }
}
```

What it detects:

- **React Native:** `wrapper.kind = react-native`, the OS and its version. It cannot read the app's
  bundle id from JavaScript; pass `platform: { appId }` if your package knows it.
- **Browser:** `wrapper.kind = web`, the page's hostname as the app id. It uploads when the page is
  hidden, so events are not lost on navigation.
- **Node:** `wrapper.kind = node`, the app's package name and version from `package.json`.

Stack frames match `sdkPackagePrefixes` against file paths, so they match when your package is
installed under `node_modules/@acme/chat/`. A symlinked install (`npm link`, `file:../dir`) resolves to
the real folder and will not match; test frames with a packed tarball.

**Check it.** `await insights?.flush()` uploads now; it never rejects.

---

## Testing against a local ingest

- **Endpoint:** `http://127.0.0.1:<port>/v1/batches` from your computer; `http://10.0.2.2:<port>/v1/batches`
  from the Android emulator.
- **Cleartext:** allow plain HTTP to that host in your **sample app** only (never in your SDK):

  ```xml
  <!-- res/xml/network_security_config.xml -->
  <network-security-config>
      <domain-config cleartextTrafficPermitted="true">
          <domain includeSubdomains="false">10.0.2.2</domain>
      </domain-config>
  </network-security-config>
  ```

  and `android:networkSecurityConfig="@xml/network_security_config"` on the sample app's `<application>`.
- **Android 17 (API 37):** apps targeting it need the local-network permission to reach `10.0.2.2`.
  Declare `<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />` in the sample
  app and grant it (`adb shell pm grant <your.sample.app> android.permission.ACCESS_LOCAL_NETWORK`).
  The hosted ingest is on the internet and needs neither.
- **Browsers:** the ingest must allow cross-origin requests (the hosted one does).

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `upload deferred (network)` | Endpoint unreachable: wrong host, cleartext HTTP blocked, or (Android 17 target) the local-network permission missing |
| `batch rejected with 401` | Wrong or revoked write key, or a different key in the body and header |
| `batch rejected with 400` | A field broke the schema; send us the batch, it is a client bug |
| Wrapper integration shows `native` / no `bridged` | Wrapper started before the native init, different `sdkName` or `writeKey`, or the two bridge calls are missing on the native side |
| Error frames are empty | `sdkPackagePrefixes` not set or not matching; minified builds (add the keep rule; web and React Native release bundles have no package paths); a symlinked JS install |
| Frames from your sample app appear | The sample's package is under `sdkPackagePrefixes`; move it or narrow the prefixes |
| Many `health` events | A check called on every method call; report it once or when the result changes |
| `customer_key_format` fails | You passed a raw key: hash it first |
| Flutter app fails `checkReleaseAarMetadata` | Your SDK's AAR asks for a newer compileSdk; set `aarMetadata { minCompileSdk = 23 }` |

## Turning it off

Tell your customers how: [`opt-out.md`](opt-out.md).
