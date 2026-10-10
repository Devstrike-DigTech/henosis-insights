# Wrapped SDK example (bridged mode)

A native Android SDK shipped to Flutter and React Native through wrapper packages, reporting through
Henosis Insights as **one stream per app** (CLAUDE.md integration mode 2, milestone M4).

| Piece | Where |
| --- | --- |
| Native SDK `com.acme:payments` (embeds `insights-android`) | [`android/sample-sdk`](../../android/sample-sdk) |
| Flutter wrapper `acme_payments_flutter` + example app | [`flutter/acme_payments_flutter`](flutter/acme_payments_flutter) |
| React Native wrapper `@acme/payments-react-native` (TurboModule) + example app | [`react-native`](react-native) |

Each wrapper:

1. calls the native SDK's `init` over its own channel (MethodChannel / TurboModule),
2. then starts the Dart / TS client in `auto` mode with a `BridgeTransport` over that same channel,
3. and on the native side wires two calls to `HenosisInsights.bridge.isRunning` / `.receive`.

The result, checked on an Android emulator against a local ingest and ClickHouse:

- every batch comes from the Kotlin client with `client.mode = bridged` and the wrapper's
  `wrapper.kind` / `wrapper.version`, including the first batch of a first launch;
- the Dart / TS client never uploads anything itself;
- errors caught in the wrapper are `layer: wrapper` (with Dart / JS frames), errors in the native SDK
  are `layer: native`;
- the wrapper's own call counts merge into the native `calls` event.

Protocol details: [`docs/bridge-protocol.md`](../../docs/bridge-protocol.md).

## Run it

```bash
# 1. Publish the native SDK and the Insights client to mavenLocal
cd android && ./gradlew :insights:publishToMavenLocal :sample-sdk:publishToMavenLocal

# 2. In the cloud repo: register the fixture key and start a local ingest reachable from the emulator
SEED_WRITE_KEY=hi_test_0123456789abcdef0123456789abcdef pnpm db:seed
INGEST_HOST=0.0.0.0 pnpm dev:ingest

# 3a. Flutter
cd examples/wrapped-sdk/flutter/acme_payments_flutter/example && flutter build apk --release
# 3b. React Native
cd examples/wrapped-sdk/react-native/example && npm install --install-links ../acme-payments-react-native \
  && cd android && ./gradlew assembleRelease
```

Install the APK on an emulator, launch it, then `node js/examples/verify-clickhouse.mjs <customerKeyHash>`.
