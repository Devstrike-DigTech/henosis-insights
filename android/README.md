# insights-android

Henosis Insights client for native Android SDKs. Kotlin, `minSdk 21`, no runtime dependency beyond the
Kotlin stdlib, no permission beyond `INTERNET`, under 100 KB (enforced by `./gradlew checkFootprint`).

## Add it to your SDK

```kotlin
object AcmePayments {
    private lateinit var insights: InsightsClient

    fun init(context: Context, apiKey: String) {
        insights = HenosisInsights.start(
            context,
            InsightsConfig(
                writeKey = "hi_live_…",                    // from the Insights dashboard
                sdkName = "acme-payments",
                sdkVersion = BuildConfig.VERSION,
                customerKey = sha256(apiKey),               // a HASH of your customer's key, never the key
                sdkPackagePrefixes = listOf("com.acme.payments."), // only these frames are ever reported
                requiredPermissions = listOf("android.permission.CAMERA"),
            ),
        )
    }

    fun charge(amount: Long): Result {
        insights.countCall("charge")
        insights.checkWorkerThread("charge")          // records a failure if called on the main thread
        insights.check(HealthCheck.InitBeforeUse(ok = initialized))
        try { … } catch (e: GatewayException) {
            insights.reportError("charge", code = "CARD_DECLINED_CONFIG", throwable = e)
        }
    }

    @Deprecated("Use charge()") fun chargeLegacy() { insights.markDeprecated("chargeLegacy"); … }
}
```

`HenosisInsights.countCall(…)` and the other static calls work too, as long as only one SDK in the host
app uses Insights. If two do, static calls are dropped rather than credited to the wrong SDK, so
prefer the handle `start()` returns.

See [`sample-sdk`](sample-sdk/src/main/kotlin/com/acme/payments/AcmePayments.kt) and
[`sample-app`](sample-app).

## What it sends

Only schema v1 fields ([`../schema/v1.json`](../schema/v1.json)). Every payload in the tests is validated
against that schema and checked for unknown field names. Never sent: device or advertising IDs, user data,
exception messages, method arguments, or stack frames outside `sdkPackagePrefixes`.

Automatic checks at start: `customer_key_format` (a missing or non-hash key fails, and a non-hash key is
hashed before it leaves the device) and one `permission_<name>` check per `requiredPermissions` entry.

## Behaviour

| Rule | How |
| --- | --- |
| Never break the host | Every call is wrapped; failures are swallowed (logged to logcat only with `debug = true`) |
| Nothing on the caller's thread but enqueuing | One low-priority daemon thread per SDK owns all state and I/O |
| No background wake-ups | No WorkManager, alarms, services or timers. Uploads happen only on SDK activity |
| Upload policy | On Wi-Fi at most once a minute; otherwise once 6 h have passed since the last upload. `flush()` forces one |
| Wi-Fi detection | Only if the host app already holds `ACCESS_NETWORK_STATE`; otherwise the 6 h rule applies |
| Offline queue | On disk, capped at 1 MB, oldest dropped first; survives restarts. Batches of at most 500 events |
| Retries | 408, 429, 5xx and network errors back off 5 min and keep the events; other 4xx drop the batch |
| Opt-out | `HenosisInsights.setEnabled(false)` or `<meta-data android:name="com.devstrike.henosis.insights.ENABLED" android:value="false"/>`. Applies to every SDK in the app, deletes anything queued, remembered across launches |
| Sampling | `sampleRate` decided once per install and remembered |
| Kotlin compatibility | Compiled at language/API level 2.2 against stdlib 2.2.21, so SDKs on Kotlin 2.2+ can consume it |

## Build and test

Requires JDK 21 and the Android SDK (platform 37).

```bash
./gradlew check                      # unit + Robolectric tests, lint, footprint gate
./gradlew :insights:checkFootprint   # AAR size and runtime-dependency check only
```

End-to-end (sample SDK → ingest → ClickHouse) needs the cloud repo's ingest running:

```bash
INSIGHTS_E2E_ENDPOINT=http://127.0.0.1:8787/v1/batches \
INSIGHTS_E2E_WRITE_KEY=hi_test_… \
INSIGHTS_E2E_CLICKHOUSE_URL=http://localhost:8123 INSIGHTS_E2E_CLICKHOUSE_PASSWORD=insights \
./gradlew :sample-sdk:testDebugUnitTest
```
