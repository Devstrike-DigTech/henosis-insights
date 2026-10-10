# Roadmap

Out of scope for v0.1 (CLAUDE.md section 8). Each item here is deliberately not in the code yet.

| Item | Why later | Earliest |
| --- | --- | --- |
| Swift client (native iOS SDKs) and shipping the Apple privacy manifest | v0.1 proves the model on Android + Flutter + RN first | v0.2 |
| C# client (.NET MAUI, Unity) | same | v0.2 |
| Kotlin Multiplatform packaging of the Kotlin client | pure-Dart/TS clients cover non-Android platforms for now | after v0.2 |
| Customer-facing status page ("your integration is healthy") | needs the dashboard (M5) and a design-partner ask | Business plan |
| Slack and webhook alerts | email alerts first (M5) | Business plan |
| EU data region and self-hosting | one region keeps v0.1 operable by one person | Enterprise plan |
| Method latency timing | adds payload and a privacy review item; call counts come first | TBD |
| SSO | magic-link auth first | Enterprise plan |

Launch tasks (after the design-partner phase):

- Publish the clients: `com.devstrike.henosis:insights` to Maven Central, `insights_dart` to pub.dev,
  `@devstrike/insights-js` to npm. Until then partners install from mavenLocal, the Git path and a packed
  tarball (integration guide, "Getting the client libraries").
- Deploy the ingest behind a CDN that sets a country header, and the dashboard and alerts with Resend
  over SMTP.
- Lawyer review of `privacy/DATA.md`, the data-safety answers and the privacy manifest.

Decisions deferred inside v0.1:

- Per-event timestamps: events carry no `at`; the batch `sentAt` is the event time and `calls` has its own window. Revisit if dashboards need sub-batch ordering.
- Country: resolved only from an edge header the CDN sets. No GeoIP database inside the ingest.
- Wi-Fi detection on Android uses `ACCESS_NETWORK_STATE` only if the host app already holds it; otherwise the client falls back to the 6-hour upload interval.
- Multiple Insights-enabled SDKs in one app: supported through the per-SDK handle `start()` returns. The static calls from CLAUDE.md section 4 apply only while one SDK is running; with several they are dropped instead of misattributed.
- `app.id` is optional in schema v1. A pure-JS React Native package cannot read the host app's bundle id without native code, so it omits it (bridged mode in M4 gets it from the native client). Vendors can pass `platform: { appId }` when they know it.
- Pure Dart and TypeScript clients cannot see Wi-Fi state, so they upload on SDK activity every 6 hours, on `flush()`, and when a web page is hidden. With the default in-memory storage the first activity of each launch also uploads; vendors who want fewer uploads plug in persistent storage.
- On a session's start the native client holds its first automatic upload for up to 5 s unless it already knows its wrapper, so a first launch of a wrapped app is tagged correctly from the first batch. A native-only app's first upload therefore waits for SDK activity after those 5 s (or a `flush()`).
- Bridged mode: the native client owns sampling, storage, init and heartbeat; the wrapper forwards calls, deprecated uses, wrapper-layer errors and health checks. Count each method once (see docs/bridge-protocol.md).
- The Henosis `telemetry:` hook (generated wrappers using bridged mode automatically) is implemented in the Henosis repo, on top of the bridge API here.
- The ingest allows CORS from any origin (no cookies; the write key is the gate) so web pages and Flutter web can post.
- SDK stack frames in R8-minified host apps: obfuscated class names cannot be matched to `sdkPackagePrefixes` and are dropped, unless the vendor SDK keeps its names via consumer rules (documented). De-obfuscation with uploaded mapping files is a later option.
