# JavaScript examples

| Folder | What it is |
| --- | --- |
| `acme-chat` | A fictional pure-JS vendor SDK (`@acme/chat`) that embeds `@devstrike/insights-js` |
| `node` | A Node service using `@acme/chat` |
| `web` | A web page using `@acme/chat` through an import map |
| `react-native-app` | A React Native 0.87 app using `@acme/chat` (installed as packed copies, like a real app) |

All of them default to the public fixture write key and a local ingest on `http://127.0.0.1:8787`.
In the cloud repo, register that key and start the ingest:

```bash
SEED_WRITE_KEY=hi_test_0123456789abcdef0123456789abcdef pnpm db:seed
INGEST_HOST=0.0.0.0 pnpm dev:ingest
```

Then `node examples/verify-clickhouse.mjs <customerKeyHash>` shows what landed.

- **Node:** `pnpm install && pnpm build`, then `node examples/node/index.js`.
- **Web:** serve `js/` (`python3 -m http.server 5173`) and open `/examples/web/?auto=1`.
- **React Native (Android):** `npm install --install-links ../acme-chat` in `react-native-app`, then
  `cd android && ./gradlew assembleRelease`, install the APK on an emulator and launch it. The example's
  network security config allows plain HTTP only to `10.0.2.2` (the host machine).
