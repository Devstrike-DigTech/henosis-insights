# Henosis Insights — Project Brief for Claude Code

> **Henosis Insights**: privacy-first, cross-platform telemetry for SDK vendors. See every integration of your SDK, per customer, on every platform, without collecting anything about end users.
> Product line of **Henosis** (`github.com/devstrike/henosis`). Owner: Devstrike Digital Limited.
> Client libraries: Apache-2.0, public repo `github.com/devstrike/henosis-insights`. Ingest and dashboard: private repo `github.com/devstrike/henosis-insights-cloud`.

Put this file at the root of **both** repos as `CLAUDE.md`. It is the source of truth for scope. If a request conflicts with this file, stop and ask.

---

## 1. What we are building

1. **Client libraries** that an SDK vendor embeds inside an SDK they have **already built** (Henosis not required). All speak the same open event format (schema v1):
   - `insights-android` (Kotlin): native Android SDKs
   - `insights_dart` (pure Dart, no Flutter-plugin dependency): any Flutter package on any platform (mobile, web, desktop) and Dart server packages
   - `insights-js` (pure TypeScript, no native dependency): any React Native package, web JS SDKs, Node packages, Capacitor/Cordova plugins

   **Three integration modes, all first-class in v0.1:**
   | Mode | Example | Behaviour |
   | --- | --- | --- |
   | Native only | Kotlin SDK used by native apps | Kotlin client sends |
   | Native SDK with wrappers | Kotlin SDK shipped through Flutter and RN wrapper packages | Kotlin client sends; the Dart/TS client inside each wrapper runs in **bridged** mode and forwards its events plus wrapper kind/version to the Kotlin client over the wrapper's existing platform channel / TurboModule |
   | Pure package | Pure-Dart Flutter package, pure-JS RN package, on any platform | Dart/TS client sends itself (**direct** mode) |

   Dart/TS clients default to **auto** mode: if the native client is running for the same `writeKey` + `sdkName`, bridge; otherwise send directly. Vendors may force `bridged` or `direct`.
2. **Ingest service** that receives batched events, validates them, strips anything identifying, stores them.
3. **Dashboard** where the vendor sees versions, wrappers, customers, integration health and errors, and gets email alerts.
4. **Henosis hook**: a `telemetry:` block in `henosis.yaml` makes Henosis-generated packages call the client automatically. (Implemented in the Henosis repo after M3 here; this repo provides the API it calls.)

```
[ customer app ]  vendor SDK → insights client (checks, counters) → offline queue ──HTTPS batch──► ingest ──► ClickHouse ──► dashboard + alerts ──► SDK vendor
                                                                                               (drops IP, validates)  (roll-ups)
Henosis ── telemetry: block ──► generated Flutter / RN packages call the client
```

---

## 2. Hard rules

1. **Integration data only.** Never collect end-user data. The full allowed list is in section 5; anything not on it is forbidden. A test asserts the serialized payload contains only allowed fields.
2. **Forbidden always:** advertising IDs (IDFA, AAID), IDFV, Android ID, IMEI, MAC, user ids, names, emails, phone numbers, precise or IP-derived location stored at rest, method arguments, app screen content, app-owned stack frames.
3. **IP addresses:** ingest may read the connecting IP only to derive a country code at the edge, then discards it. Never log or store IPs.
4. **Tiny and quiet.** Kotlin client adds < 100 KB to the vendor's AAR (CI-enforced); Dart and TS clients have no required dependencies beyond the platform HTTP client (`package:http` allowed for Dart, `fetch` for JS) and a size budget checked in CI. No background wake-ups, no WorkManager periodic jobs, no extra permissions beyond `INTERNET`. Uploads happen on SDK activity: when on Wi-Fi, or at most every 6 hours. Offline queue capped at 1 MB; drop oldest when full.
5. **One stream per SDK per app.** Bridged mode must never also send directly; a test proves a wrapped SDK produces exactly one batch stream.
6. **Never break the host.** Every client call is wrapped: an Insights failure must never throw into the vendor SDK or the app. No work on the main thread except enqueuing.
7. **One-flag opt-out.** The host app can disable Insights (`HenosisInsights.setEnabled(false)` and a manifest meta-data key). When disabled, nothing is queued or sent.
8. **Deterministic, versioned schema.** Event schema is versioned (`schema: 1`); ingest rejects unknown versions with a clear error.
9. **Clean room.** Never copy code, assets or internal knowledge from any employer or client.
10. **Use current stable versions** of every library, toolchain and API. Verify when you add them; do not rely on remembered versions.

---

## 3. Tech decisions (decided; do not revisit without asking)

| Area | Decision |
| --- | --- |
| Android core | Kotlin, minSdk 21, no third-party runtime dependencies (use `HttpURLConnection`, `org.json`); coroutines **not** allowed in the core (avoid forcing a dependency on the host) |
| Dart client | Pure Dart package (`insights_dart`), no Flutter or plugin dependencies; offline queue in memory by default with a pluggable `InsightsStorage` adapter; bridged mode uses a `BridgeTransport` the wrapper supplies (wraps its own MethodChannel) |
| TS client | Pure TypeScript package (`@devstrike/insights-js`), ESM + CJS builds, no runtime deps; in-memory queue + pluggable storage; bridged mode via a `BridgeTransport` the wrapper supplies (wraps its own TurboModule) |
| Kotlin bridge receiver | `HenosisInsights.bridge.receive(json)` accepts forwarded wrapper events and wrapper kind/version |
| Ingest | TypeScript, Fastify, zod validation, deployed as a container |
| Event store | ClickHouse (raw events with 90-day TTL + daily roll-up tables via materialized views) |
| Accounts store | Postgres (orgs, users, projects, SDKs, write keys, alert rules) + Drizzle |
| Dashboard | Next.js App Router, Tailwind, server components reading ClickHouse; auth by email magic link |
| Alerts | Scheduled job (every 15 min) evaluating rules; email via a transactional email provider (chosen at M4) |
| Monorepo (cloud) | pnpm workspaces: `apps/ingest`, `apps/dashboard`, `apps/alerts`, `packages/schema`, `packages/db` |
| Schema source of truth | `packages/schema` (zod) generates JSON Schema; client libraries have golden tests against it |
| Tests | JUnit5 + Robolectric (Android), flutter_test, Jest/Vitest (RN, cloud) |
| CI | GitHub Actions: build, tests, AAR size check, schema golden tests |

---

## 4. Client API (Android core)

```kotlin
// Native client (Kotlin). Dart and TS clients expose the same calls.
// Called once by the vendor SDK, typically inside its own init()
HenosisInsights.start(
    context = appContext,
    config = InsightsConfig(
        writeKey = "hi_live_…",          // identifies the vendor's SDK project, not the customer
        sdkName = "acme-payments",
        sdkVersion = "4.2.0",
        customerKey = customerApiKeyHash, // vendor passes a HASH of its customer's API key, never the raw key
        // wrapper kind/version arrive from bridged Dart/TS clients; native-only SDKs leave it as native
        sampleRate = 1.0,
    )
)

HenosisInsights.countCall("charge")                     // batched counter
HenosisInsights.reportError("charge", code = "CARD_DECLINED_CONFIG", throwable = e) // SDK frames only
HenosisInsights.check(HealthCheck.InitBeforeUse(ok = initialized))
HenosisInsights.markDeprecated("chargeLegacy")          // records deprecated-method usage
HenosisInsights.setEnabled(false)                       // host app opt-out
```

Built-in automatic checks: invalid or missing `customerKey` format, call on main thread for methods the vendor marks `@WorkerThread`, required permissions the vendor declares as missing at runtime.

---

## 5. Event schema v1 (the allowed list)

Every batch: `{ schema: 1, writeKey, client: {lang: "kotlin"|"dart"|"ts", version, mode: "native"|"bridged"|"direct"}, sdk: {name, version}, wrapper: {kind: "native"|"flutter"|"react-native"|"web"|"node"|"other", version}, customerKey, app: {id, version}, device: {os, osVersion, class?: "phone"|"tablet"|"desktop"}, sentAt, events: [...] }`

`app.id` is the bundle id / package name on mobile, the site's hostname on web, and the package name on Node. Fields a platform cannot know are omitted, never guessed.

Event kinds:
- `init` — `{ok, checks: [{name, ok}]}`
- `calls` — `{counts: {methodName: int}}` for a time window (`from`, `to`)
- `error` — `{method, code, layer: "native"|"wrapper", frames: [{cls, fn, line}]}` with **only frames whose class starts with the vendor's package prefix(es)** from config
- `health` — `{check, ok, detail?}` where `detail` is from a fixed enum, never free text
- `deprecated` — `{method, count}`
- `heartbeat` — `{}` once per day per app

Ingest adds `country` (ISO code) from the edge and drops the IP. Nothing else is added or stored.

---

## 6. Repository layouts

```
henosis-insights/                 # public, Apache-2.0
├── CLAUDE.md
├── android/                      # insights-android core + sample app
├── dart/                         # insights_dart (pure Dart) + examples: pure-Dart Flutter package, Flutter web
├── js/                           # @devstrike/insights-js (pure TS) + examples: pure-JS RN package, web, Node
├── examples/wrapped-sdk/         # sample native SDK + Flutter and RN wrappers using bridged mode
├── schema/                       # JSON Schema v1 (generated from cloud packages/schema, copied on release)
├── privacy/                      # DATA.md (the allowed list in plain English), PrivacyInfo.xcprivacy template (for v0.2 iOS), play-data-safety.md
└── docs/                         # integration guide, roadmap.md

henosis-insights-cloud/           # private
├── CLAUDE.md
├── apps/ingest  apps/dashboard  apps/alerts
├── packages/schema  packages/db
├── clickhouse/                   # DDL, materialized views, TTLs
└── fixtures/                     # recorded batches from the sample SDK
```

---

## 7. Milestones and acceptance criteria

Work **one milestone at a time**. At the end of each: tests green, README status table updated, summarize, stop.

**M1 — Schema, ingest, storage** (week 1)
- `packages/schema` (zod → JSON Schema), ingest endpoint with write-key auth, rate limits, IP → country then discard, ClickHouse tables + TTL + roll-ups.
- ✅ Recorded fixture batches accepted; malformed and unknown-schema batches rejected; test proves no IP is written anywhere (logs included).

**M2 — Android core** (week 2)
- API from section 4, counters, offline queue (1 MB cap), upload policy, opt-out, built-in checks, SDK-frame filtering.
- ✅ AAR size < 100 KB (CI check); payload test: only allowed fields; Insights failures never throw into host (fault-injection tests); sample SDK events land in ClickHouse.

**M3 — Pure Dart and TypeScript clients, direct mode** (week 3)
- Same API shape as the Kotlin client; in-memory queue + storage adapter; platform detection (mobile, web, desktop, Node).
- ✅ A pure-Dart Flutter package (mobile + web) and a pure-JS RN package (and a web page) report correctly; payload tests: only allowed fields; golden tests against schema v1.

**M4 — Bridged and auto modes** (week 4)
- `BridgeTransport` in Dart/TS, `bridge.receive` in Kotlin, auto-detection, `layer` on errors; `examples/wrapped-sdk` with Flutter and RN wrappers.
- ✅ The wrapped sample SDK produces exactly one stream per app, with correct `wrapper.kind`, `client.mode = bridged` events merged, and wrapper-layer errors tagged `layer: wrapper`.

**M5 — Dashboard + alerts** (week 5)
- Projects and write keys; pages: Overview (active integrations, versions, wrappers), Customers (health status, last seen, version), Errors (by code, version and layer), Deprecated usage, Customer drill-down; alert rules: error-rate jump on a new version, customer turns unhealthy.
- ✅ "Who runs version X, through which wrapper?" answerable in one click; alert email fires on a seeded fixture scenario.

**M6 — Privacy kit, open event spec, partner readiness** (week 6)
- `privacy/DATA.md`, Google Play data-safety answers, `schema/SPEC.md` (the open event format, so others can write clients), integration guide, opt-out docs; onboarding flow for a design partner.
- ✅ A fresh vendor can integrate each of the three modes by following the guide only.

---

## 8. Out of scope for v0.1 (record in `docs/roadmap.md`)

Swift client (native iOS) and privacy manifest shipping · C# client (.NET MAUI, Unity) · Kotlin Multiplatform packaging · customer-facing status pages · Slack/webhook alerts · EU region and self-hosting · method latency timing · SSO.

---

## 9. First prompt to start

> Read CLAUDE.md fully. Start Milestone M1 only, in the cloud repo: set up the pnpm monorepo, `packages/schema` with schema v1 from section 5, the Fastify ingest service with write-key auth, rate limiting and IP-to-country-then-discard, and the ClickHouse DDL with TTLs and daily roll-ups. Verify current stable versions of each dependency before adding it. When M1's acceptance criteria pass, summarize and stop.
