# Henosis Insights event format, version 1

An open format for integration telemetry: what an SDK reports about how it is integrated, and
nothing about the people using the apps. The Kotlin, Dart and TypeScript clients in this repository
implement it; anyone may write a client for another language from this document.

- Machine-readable schema: [`v1.json`](v1.json) (JSON Schema, draft 2020-12). It is normative: where
  this text and the schema differ, the schema wins.
- Wrapper-to-native bridge messages: [`bridge-v1.json`](bridge-v1.json) and
  [`../docs/bridge-protocol.md`](../docs/bridge-protocol.md).
- What may and may not be collected, in plain English: [`../privacy/DATA.md`](../privacy/DATA.md).

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

## 1. Transport

```
POST /v1/batches HTTP/1.1
Host: <ingest host>
Content-Type: application/json
Authorization: Bearer <writeKey>

{ "schema": 1, "writeKey": "<writeKey>", … }
```

- One batch per request, UTF-8 JSON, at most 256 KB. HTTPS only, except to a local development ingest.
- The `Authorization` write key MUST equal the batch's `writeKey`.
- Clients MUST NOT send cookies or other credentials (`credentials: omit` in browsers). The ingest
  allows cross-origin requests from any origin; the write key is the only gate.
- Clients MUST NOT add headers that identify the device or person (no device ids, no user agent
  details beyond a generic client name).

### Responses

| Status | Body | Client action |
| --- | --- | --- |
| `202` | `{"accepted": <n>, "batchId": "<uuid>"}` | Remove the batch's events from the queue |
| `400` | `{"error": "invalid_json" \| "invalid_batch" \| "unsupported_schema", "supported": [1], "issues": [{"path", "message"}]}` | Drop the batch (retrying cannot help) |
| `401` | `{"error": "missing_write_key" \| "invalid_write_key" \| "write_key_mismatch"}` | Drop the batch |
| `413` | `{"error": "batch_too_large"}` | Drop it, and send smaller batches |
| `415` | `{"error": "unsupported_media_type"}` | Drop the batch |
| `408`, `429`, `5xx`, network failure | | Keep the events; back off (the reference clients wait 5 minutes) |

Rate limits apply per write key, never per IP address.

## 2. The batch

Every object is **closed**: a field not listed here is rejected with `400 invalid_batch`. Fields a
platform cannot know MUST be omitted, never guessed or filled with placeholders.

| Field | Type | Rules |
| --- | --- | --- |
| `schema` | integer | Always `1`. Any other value gets `400 unsupported_schema` |
| `writeKey` | string | `^hi_(live\|test)_[a-f0-9]{32}$`; issued per project in the dashboard |
| `client.lang` | enum | `kotlin`, `dart`, `ts`. A new client language needs a schema change |
| `client.version` | version | The client library's version |
| `client.mode` | enum | `native` (native client, no wrapper), `bridged` (native client carrying a wrapper's events), `direct` (a pure Dart / JS package sending itself) |
| `sdk.name` | string | `^[a-z0-9][a-z0-9._-]{0,63}$`, the SDK's package name |
| `sdk.version` | version | The SDK's version |
| `wrapper.kind` | enum | `native`, `flutter`, `react-native`, `web`, `node`, `other` |
| `wrapper.version` | version, optional | Omitted for `native` |
| `customerKey` | string | `^[A-Za-z0-9_-]{8,128}$`. A **hash** (e.g. SHA-256 hex) of the vendor's customer key, never the raw key. Clients SHOULD hash any value that does not already match, and report a failed `customer_key_format` check |
| `app.id` | string, optional | Package name / bundle id on mobile; hostname on the web; package name on Node. `^[A-Za-z0-9@][A-Za-z0-9._:/-]*$`, ≤ 256 |
| `app.version` | version, optional | |
| `device.os` | enum | `android`, `ios`, `web`, `macos`, `windows`, `linux`, `node`, `other` |
| `device.osVersion` | version, optional | The OS version (the Node version for `node`) |
| `device.class` | enum, optional | `phone`, `tablet`, `desktop`, only when the platform says so |
| `sentAt` | string | ISO 8601 date-time with offset, e.g. `2026-10-09T10:15:30.000Z` |
| `events` | array | 1 to 500 events |

A *version* is `^[A-Za-z0-9+._-]+$`, 1 to 64 characters.

## 3. Events

Each event has a `kind`. Events carry no timestamp of their own: the batch's `sentAt` is their time,
and `calls` carries its own window.

### `init`

```json
{ "kind": "init", "ok": true, "checks": [{ "name": "customer_key_format", "ok": true }] }
```

Sent once per start of the SDK. `checks` has at most 50 entries; `ok` is false when any check failed.
In bridged mode only the native client sends `init`.

### `calls`

```json
{ "kind": "calls", "from": "<ISO time>", "to": "<ISO time>", "counts": { "charge": 42, "Client.refund": 3 } }
```

Calls are **counted on the device and sent as one event per window**, never one event per call.
`from` ≤ `to`. At most 500 methods per event; counts are integers ≥ 0.

### `error`

```json
{ "kind": "error", "method": "charge", "code": "CARD_DECLINED_CONFIG", "layer": "native",
  "frames": [{ "cls": "com.acme.payments.Client", "fn": "charge", "line": 118 }] }
```

- `code` is chosen by the vendor: `^[A-Z][A-Z0-9_]{0,63}$`. It is never the exception's message.
- `layer` is `native` for errors in the native SDK, or in a pure Dart / JS package that is itself the
  SDK; `wrapper` for errors in a Flutter / React Native wrapper of a native SDK.
- `frames` (at most 64) MUST contain **only frames from the SDK's own code**, selected by package or
  path prefixes the vendor configures. App frames, framework frames and other libraries' frames MUST
  be dropped on the device. `cls` is the class or file path from the prefix on; `fn` the function; `line` ≥ 0.

### `health`

```json
{ "kind": "health", "check": "customer_key_format", "ok": false, "detail": "invalid_format" }
```

`check` is `^[a-z][a-z0-9_]{0,63}$`. `detail` is optional and MUST be one of `missing`,
`invalid_format`, `not_initialized`, `wrong_thread`, `permission_missing`, `unsupported_platform`,
`other`; there is no free-text field. Conventional check names: `customer_key_format`,
`init_before_use`, `permission_<name>`, `main_thread_<method>`.

### `deprecated`

```json
{ "kind": "deprecated", "method": "chargeLegacy", "count": 7 }
```

Counted on the device like calls; `count` ≥ 1.

### `heartbeat`

```json
{ "kind": "heartbeat" }
```

At most one per app per UTC day, so the vendor can tell an integration is still active.

## 4. Identifiers must be bounded

Method names, codes, check names and frame names all have the patterns above so that no free text,
and therefore no user data, can ride along. Clients SHOULD normalise rather than drop: replace
characters outside the pattern with `_`, prefix where the first character is not allowed, and
truncate to the length limit. The reference clients' normalisers do exactly this.

## 5. Client behaviour

A conforming client:

1. **Never breaks the host.** No call throws or rejects into the SDK or the app. Failures are
   swallowed (optionally logged in a debug mode, never with payload contents).
2. **Does no work on the caller's thread beyond enqueuing.** Serialisation and I/O happen elsewhere
   (a background thread, or asynchronously in single-threaded runtimes).
3. **Queues offline, bounded.** At most 1 MB of queued events; when full, drop the oldest.
4. **Uploads only on SDK activity.** No background jobs, alarms or wake-ups. The reference policy:
   on Wi-Fi at most once a minute; otherwise once 6 hours have passed since the last upload; on an
   explicit `flush()`; and, on the web, when the page is hidden (`fetch` with `keepalive`, ≤ 64 KB).
5. **Honours opt-out.** The host app can disable Insights for every SDK in the app with one flag.
   While disabled nothing is queued or sent, and anything queued is deleted.
6. **Samples per install** when the vendor sets a sample rate, and remembers the decision.
7. **Sends one stream per SDK per app.** A Dart / TS client inside a wrapper of a native SDK that
   runs Insights forwards over the bridge (`client.mode = bridged` on the native side) and MUST NOT
   also send directly.
8. **Collects nothing outside this document.** In particular no device, advertising or user
   identifiers, no IP-derived location, no exception messages, no arguments.

## 6. Ingest guarantees

- Validates every batch against [`v1.json`](v1.json) and rejects unknown schema versions with a clear error.
- Derives `country` (ISO 3166-1 alpha-2) from a CDN header and discards the IP address; never logs or stores it.
- Stores raw events for 90 days and daily aggregates for 400 days.

## 7. Versioning

`schema: 1` is stable: fields will not be removed or change meaning. Additions that old clients can
ignore may come as optional fields in `v1`; anything else is a new schema version, accepted alongside
`1` for a published transition period. A client sends exactly one version per batch.

## 8. Conformance checklist for a new client

- [ ] Every batch you produce validates against `v1.json` (the reference clients test this in CI).
- [ ] Your test suite asserts no field outside the schema appears in any payload.
- [ ] Error frames are filtered to the SDK's prefixes, and messages are never read.
- [ ] Identifiers are normalised to the patterns in section 4.
- [ ] A fault-injection test shows no client call can throw into the host.
- [ ] The opt-out flag stops queuing and sending and clears the queue.
- [ ] Inside a wrapper with a running native client, nothing is sent directly.
