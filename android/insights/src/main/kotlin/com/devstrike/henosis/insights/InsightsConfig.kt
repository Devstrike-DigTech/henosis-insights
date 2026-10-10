package com.devstrike.henosis.insights

/**
 * Configuration the vendor SDK passes once to [HenosisInsights.start].
 *
 * @property writeKey Identifies the vendor's SDK project in the Insights dashboard (`hi_live_…` or `hi_test_…`).
 *   It is not a secret in the strict sense but should not be shared between SDKs.
 * @property sdkName The SDK's package name as registered in the dashboard, e.g. `acme-payments`.
 * @property sdkVersion The SDK's own version, e.g. `4.2.0`.
 * @property customerKey A **hash** of the vendor's customer API key, never the raw key. Must be 8–128
 *   URL-safe characters. If it is not, Insights hashes it (SHA-256) and reports the `customer_key_format` check as failed.
 * @property sdkPackagePrefixes Class-name prefixes that belong to the SDK, e.g. `listOf("com.acme.payments.")`.
 *   Only stack frames whose class starts with one of these are ever reported. Empty means no frames.
 * @property requiredPermissions Android permissions the SDK needs at runtime, e.g. `android.permission.CAMERA`.
 *   Each is checked at start and reported as an `init` check named `permission_<suffix>`.
 * @property sampleRate Fraction of app installs that report, 0.0–1.0. Decided once per install and remembered.
 * @property endpoint Ingest URL. Override for self-hosted or test ingests.
 * @property debug Log Insights' own activity to logcat (tag `HenosisInsights`). Never logs payload contents.
 */
public class InsightsConfig(
    public val writeKey: String,
    public val sdkName: String,
    public val sdkVersion: String,
    public val customerKey: String,
    public val sdkPackagePrefixes: List<String> = emptyList(),
    public val requiredPermissions: List<String> = emptyList(),
    public val sampleRate: Double = 1.0,
    public val endpoint: String = DEFAULT_ENDPOINT,
    public val debug: Boolean = false,
) {
    public companion object {
        public const val DEFAULT_ENDPOINT: String = "https://in.insights.henosis.dev/v1/batches"
    }
}
