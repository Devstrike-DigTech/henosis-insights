# What Henosis Insights collects

Henosis Insights tells an SDK vendor how their SDK is **integrated**: which versions and wrappers
their customers run, whether the integration is healthy, and which errors come from the SDK. It
never collects anything about the people using the apps.

This page is the plain-English version of [`schema/v1.json`](../schema/v1.json). The schema is the
authority: every object in it is closed, so the ingest rejects any field that is not listed, and every
client's tests check that their payloads only use these fields.

## Collected

Sent with every batch:

| Field | What it is | Example |
| --- | --- | --- |
| `writeKey` | Identifies the SDK vendor's project, not the app or the person | `hi_live_…` |
| `client` | Insights client language, version and mode | `kotlin`, `0.1.0`, `bridged` |
| `sdk` | The SDK's name and version | `acme-payments`, `4.2.0` |
| `wrapper` | How the SDK is consumed: native, Flutter, React Native, web, Node, other; and the wrapper's version | `flutter`, `2.0.3` |
| `customerKey` | A **hash** of the SDK vendor's own customer key (the app developer's account with the vendor). Never the raw key; a key that is not already a hash is hashed on the device | `9f86d0…` |
| `app.id` | The app's package name or bundle id; a web page's hostname; a Node package's name. Omitted where it cannot be known | `com.example.shop` |
| `app.version` | The app's version string, where known | `12.3.1` |
| `device.os`, `device.osVersion` | Operating system family and version | `android`, `16` |
| `device.class` | `phone`, `tablet` or `desktop`, only where the platform says so | `phone` |
| `sentAt` | When the batch was sent | ISO 8601 time |

Events inside a batch:

| Event | What it carries |
| --- | --- |
| `init` | Whether the SDK started correctly, and the result of each start-up check (by fixed check name) |
| `calls` | How many times each SDK method was called in a time window (method name → count) |
| `error` | An SDK error **code** chosen by the vendor, the SDK method, the layer (`native` or `wrapper`), and stack frames **only from the SDK's own code** (class, function, line) |
| `health` | A named check, pass or fail, and a detail from a fixed list (`missing`, `invalid_format`, `not_initialized`, `wrong_thread`, `permission_missing`, `unsupported_platform`, `other`) |
| `deprecated` | How many times a deprecated SDK method was used |
| `heartbeat` | Nothing; one per app per day, so the vendor knows the integration is active |

Added by the ingest: `country`, a two-letter country code. It is read from a header the CDN in front of
the ingest derives from the connecting IP address. The ingest never reads, logs or stores the IP address
itself, and has no column that could hold one.

## Never collected

- Device or advertising identifiers: IDFA, Android advertising ID, IDFV, Android ID, IMEI, MAC address, serial number.
- User identifiers, names, email addresses, phone numbers, account ids.
- IP addresses (at rest or in logs), precise location, or location derived from an IP address at the city level or finer.
- Method arguments, request or response payloads, form contents, screen contents.
- Exception messages, exception types, and stack frames from the app or from other libraries.
- Cookies or anything that tracks a person across apps or websites.

The identifiers in a batch (`customerKey`, `app.id`) describe the business that built the app, not the
person using it.

## On the device

- Events wait in a queue of at most 1 MB before upload; the oldest are dropped when it is full.
  Android keeps it in the app's private storage; Dart and JavaScript keep it in memory unless the SDK
  vendor plugs in persistent storage.
- Uploads go over HTTPS to the vendor's Insights project. No background jobs, alarms or wake-ups: uploads
  happen while the SDK is in use.
- The Android client needs no permission beyond `INTERNET`.

## Turning it off

The app developer can switch Insights off for every SDK in the app with one flag; see
[`docs/opt-out.md`](../docs/opt-out.md). Nothing is queued or sent while it is off, and anything already
queued is deleted. SDK vendors can also sample a fraction of installs (`sampleRate`).

## Retention

Raw events are deleted after **90 days**. Daily aggregates (counts per version, wrapper, customer and
method) are kept for 400 days.

## Store and legal disclosures

- Apple: [`PrivacyInfo.xcprivacy`](PrivacyInfo.xcprivacy) template.
- Google Play: [`play-data-safety.md`](play-data-safety.md).

Because nothing identifies a person, this data should fall outside "personal data" under the GDPR and
Nigeria's NDPA. That is our reading, not legal advice: have your privacy counsel review this list, and
ask us for a data processing agreement if you need one.
