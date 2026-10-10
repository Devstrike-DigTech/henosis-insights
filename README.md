# Henosis Insights

Privacy-first, cross-platform telemetry for SDK vendors. See every integration of your SDK, per customer,
on every platform, without collecting anything about end users.

A product line of [Henosis](https://github.com/Devstrike-DigTech/henosis) by Devstrike Digital Limited.
Client libraries in this repo are Apache-2.0. The hosted ingest and dashboard live in a private repo.

## Status

| Milestone | Status | Where |
| --- | --- | --- |
| M1 Schema, ingest, storage | ✅ done | cloud repo; the published event schema is [`schema/v1.json`](schema/v1.json) |
| M2 Android core (`insights-android`) | ✅ done | [`android/`](android/) |
| M3 Pure Dart and TypeScript clients | ✅ done | [`dart/insights_dart`](dart/insights_dart), [`js/insights-js`](js/insights-js) |
| M4 Bridged and auto modes | ✅ done | [`examples/wrapped-sdk`](examples/wrapped-sdk), [`docs/bridge-protocol.md`](docs/bridge-protocol.md) |
| M5 Dashboard + alerts | ⬜ | cloud repo |
| M6 Privacy kit, open spec, partner readiness | ✅ done | [`privacy/`](privacy), [`schema/SPEC.md`](schema/SPEC.md), [`docs/`](docs) |

## Start here

- **Integrating your SDK:** [`docs/integration-guide.md`](docs/integration-guide.md), one section per mode.
- **What is collected:** [`privacy/DATA.md`](privacy/DATA.md), plus [Google Play data-safety answers](privacy/play-data-safety.md)
  and an [Apple privacy manifest template](privacy/PrivacyInfo.xcprivacy).
- **For app developers:** [turning Insights off](docs/opt-out.md).
- **Writing a client in another language:** [`schema/SPEC.md`](schema/SPEC.md), the open event format.

## What a client sends

Only integration data, never end-user data. The full allowed list is the schema itself:
[`schema/v1.json`](schema/v1.json) (JSON Schema, draft 2020-12). Every object is closed, so a field that
is not in the schema is rejected by the ingest. Plain-English list: [`privacy/DATA.md`](privacy/DATA.md).

## Repository layout

```
android/      insights-android (Kotlin) + sample SDK + sample app
dart/         insights_dart (pure Dart) + examples/acme_kyc: a pure-Dart SDK and its Flutter app
js/           @devstrike/insights-js (pure TS) + examples: acme-chat SDK, Node, web page, React Native app
examples/     wrapped-sdk: native SDK with Flutter and RN wrappers in bridged mode (M4)
schema/       JSON Schema v1 (from the cloud repo), bridge-v1.json (wrapper → native), SPEC.md (the open format)
privacy/      DATA.md, PrivacyInfo.xcprivacy template, play-data-safety.md
docs/         integration-guide.md, opt-out.md, bridge-protocol.md, roadmap.md
```
