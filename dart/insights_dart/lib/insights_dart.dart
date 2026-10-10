/// Henosis Insights for Dart and Flutter SDKs: integration telemetry with no end-user data.
///
/// A vendor SDK calls [HenosisInsights.start] once and keeps the returned [InsightsClient]. Every
/// call returns immediately and never throws; an Insights failure can never break the SDK or the
/// app. Hosts opt out with [HenosisInsights.setEnabled], the `HENOSIS_INSIGHTS_DISABLED=1`
/// environment variable (native), or `globalThis.HENOSIS_INSIGHTS_DISABLED = true` (web).
library;

export 'src/api.dart' show HenosisInsights, InsightsClient;
export 'src/types.dart'
    show
        BridgeTransport,
        HealthCheck,
        HealthDetail,
        InsightsConfig,
        InsightsMode,
        InsightsStorage,
        MemoryInsightsStorage,
        PlatformInfo;
