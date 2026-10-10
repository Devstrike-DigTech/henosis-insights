# @devstrike/insights-js

Henosis Insights client for JavaScript SDKs: React Native packages, web SDKs and Node packages.
Pure TypeScript, no runtime dependencies, about 7 KB gzipped (budget enforced in CI).

It reports how your SDK is integrated (versions, call counts, SDK errors, health checks) per
customer, and nothing about end users.

## Add it to your SDK

```ts
import { HealthCheck, HenosisInsights, hashCustomerKey } from '@devstrike/insights-js';

const insights = HenosisInsights.start({
  writeKey: 'hi_live_…',                     // from the Insights dashboard
  sdkName: 'acme-chat',
  sdkVersion: '0.9.4',
  customerKey: hashCustomerKey(apiKey),      // a HASH of your customer's key, never the key
  sdkPackagePrefixes: ['@acme/chat/'],       // only stack frames from these paths are reported
  storage: AsyncStorage,                     // optional: localStorage or React Native AsyncStorage
});

insights.countCall('sendMessage');
insights.check(HealthCheck.initBeforeUse(initialized));
insights.reportError('sendMessage', 'ROOM_NOT_FOUND', error); // the message is never sent
insights.markDeprecated('sendMessageSync');
```

Prefer the handle `start()` returns. The static `HenosisInsights.countCall(…)` etc. only apply while
one SDK in the app uses Insights; with several they are dropped rather than credited to the wrong SDK.

## What it detects

| Where | `wrapper.kind` | `device.os` | `app.id` |
| --- | --- | --- | --- |
| React Native | `react-native` | `ios` / `android` from the native PlatformConstants | omitted (pure JS cannot read it; pass `platform: { appId }` if your package can) |
| Browser | `web` | `web` | page hostname |
| Node | `node` | `node` (`osVersion` = Node version) | `name` from the working directory's `package.json` |

Anything that cannot be known is omitted, never guessed.

## Behaviour

- Every call returns immediately and never throws or rejects.
- Uploads happen on SDK activity once 6 h have passed since the last upload, on `flush()`, and when a
  web page is hidden (`fetch` with `keepalive`). The first activity of a process uploads if nothing
  was uploaded yet (with in-memory storage that is every launch).
- The queue is capped at 1 MB, oldest dropped first. Batches carry at most 500 events.
- Retries back off 5 min on 408, 429, 5xx and network errors. Other 4xx drop the batch.
- Host opt-out for every SDK in the app: `HenosisInsights.setEnabled(false)`,
  `HENOSIS_INSIGHTS_DISABLED=1` (Node) or `globalThis.HENOSIS_INSIGHTS_DISABLED = true` (web).
- `sampleRate` is decided once per install (remembered when storage is provided).

## Develop

```bash
pnpm install && pnpm build && pnpm test && pnpm size
```

Examples (`../examples`): `acme-chat` (a pure-JS SDK), `node`, `web`, `react-native-app`.
