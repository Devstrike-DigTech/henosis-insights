# Bridge protocol (v1)

How a Dart or TypeScript client inside a **wrapper** of a native SDK (a Flutter plugin or a React
Native module around an Android SDK) reports through the native client, so the app sends exactly one
stream per SDK (CLAUDE.md rule 5).

```
Dart / TS client (bridged) ──BridgeTransport──► wrapper's own channel ──► HenosisInsights.bridge (Kotlin) ──► one batch stream
                                                (MethodChannel / TurboModule)
```

## What the wrapper author writes

The wrapper already has a platform channel to its native SDK. It adds two calls to it.

| Dart / TS `BridgeTransport` | Native side calls |
| --- | --- |
| `isNativeRunning(writeKey, sdkName)` → bool | `HenosisInsights.bridge.isRunning(writeKey, sdkName)` |
| `send(message)` | `HenosisInsights.bridge.receive(message)` |

Start the Dart / TS client **after** the native SDK's init has run (normally right after awaiting
the wrapper's own `init` call), with `mode: auto` (the default) and the bridge transport.

## Mode selection

| `mode` | Bridge given | Native running | Result |
| --- | --- | --- | --- |
| `auto` | no | – | `direct` |
| `auto` | yes | yes (within ~2.5 s of start, 3 probes) | `bridged` |
| `auto` | yes | no | `direct` (the native SDK does not embed Insights) |
| `direct` | – | – | `direct`; the bridge is never used |
| `bridged` | yes | – | `bridged`; never sends directly, even if native is absent |
| `bridged` | no | – | disabled |

`isRunning` stays true while the host app has opted out, so a wrapper never starts a second,
direct stream behind the opt-out. Calls made while the mode is being decided are kept and applied
once it is.

## In bridged mode the Dart / TS client

- never uploads, never writes storage, and sends no `init` or `heartbeat` (the native client does);
- sends one registration message as soon as it is bridged, so the native stream carries the
  wrapper's kind and version from then on (the native client remembers it for the next launch);
- coalesces calls, deprecated uses, errors and health checks and forwards them on the next tick
  and on `flush()`;
- tags every error `layer: wrapper`, with frames filtered to its own `sdkPackagePrefixes`.

## The message

JSON, validated by [`schema/bridge-v1.json`](../schema/bridge-v1.json):

```json
{
  "bridge": 1,
  "writeKey": "hi_live_…",
  "sdkName": "acme-payments",
  "wrapper": { "kind": "flutter", "version": "2.0.3" },
  "client": { "lang": "dart", "version": "0.1.0" },
  "calls": { "Wrapper.charge": 2 },
  "deprecated": {},
  "events": [
    { "kind": "error", "method": "charge", "code": "INVALID_CURRENCY", "layer": "wrapper",
      "frames": [{ "cls": "acme_payments_flutter/src/api.dart", "fn": "AcmePayments.charge", "line": 12 }] },
    { "kind": "health", "check": "currency_supported", "ok": false, "detail": "unsupported_platform" }
  ]
}
```

The native client ignores messages for another `writeKey` / `sdkName` or another protocol version,
drops `init`, `heartbeat`, `calls` and unknown kinds inside `events`, forces `layer: wrapper`,
normalises names, and never throws.

## Counting calls

Count each public method once. If the wrapper's `charge()` calls the native `charge()`, and the
native SDK already counts `charge`, the wrapper should not count it too; counts from both sides are
added together. Count wrapper-only methods (or none) in the wrapper.
