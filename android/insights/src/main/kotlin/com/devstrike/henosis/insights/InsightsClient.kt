package com.devstrike.henosis.insights

import com.devstrike.henosis.insights.internal.Engine

/**
 * Handle for one SDK's Insights stream, returned by [HenosisInsights.start].
 *
 * Prefer calling through this handle from inside your SDK. The static calls on [HenosisInsights]
 * work only while exactly one SDK in the app has started Insights; if a host app embeds two SDKs that
 * both use Insights, static calls are dropped (never misattributed) and only handles report.
 *
 * Every method is thread-safe, returns immediately and never throws.
 */
public class InsightsClient internal constructor(
    internal val engine: Engine?,
) {
    /** Count one call of [method]. Counts are batched into one `calls` event per upload window. */
    public fun countCall(method: String) {
        guard { engine?.countCall(method) }
    }

    /**
     * Report an SDK-originated error. Only frames whose class starts with one of
     * [InsightsConfig.sdkPackagePrefixes] are kept; the message and any arguments are never sent.
     */
    @JvmOverloads
    public fun reportError(method: String, code: String, throwable: Throwable? = null) {
        guard { engine?.reportError(method, code, throwable) }
    }

    /** Report a health check result. */
    public fun check(check: HealthCheck) {
        guard { engine?.health(check) }
    }

    /**
     * Call at the top of a method you document as worker-thread-only. Records a failed
     * `main_thread_<method>` check when invoked on the main thread; records nothing otherwise.
     */
    public fun checkWorkerThread(method: String) {
        guard {
            val e = engine ?: return
            if (e.isMainThread()) e.health(HealthCheck.WorkerThread(method, ok = false))
        }
    }

    /** Record one use of a deprecated [method]. Counts are batched like calls. */
    public fun markDeprecated(method: String) {
        guard { engine?.markDeprecated(method) }
    }

    /** Ask for an upload attempt now, regardless of the Wi-Fi / 6-hour policy. Asynchronous. */
    public fun flush() {
        guard { engine?.flush() }
    }

    /** Whether this stream is collecting (started, not opted out, sampled in). */
    public fun isEnabled(): Boolean =
        try {
            engine?.isEnabled() ?: false
        } catch (_: Throwable) {
            false
        }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            try {
                engine?.log("Insights call failed", t)
            } catch (_: Throwable) {
            }
        }
    }

    internal companion object {
        /** Returned when start fails or Insights is unavailable: every call is a no-op. */
        val NOOP = InsightsClient(null)
    }
}
