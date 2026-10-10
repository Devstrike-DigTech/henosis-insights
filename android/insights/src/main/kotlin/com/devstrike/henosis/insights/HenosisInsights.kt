package com.devstrike.henosis.insights

import android.content.Context
import com.devstrike.henosis.insights.internal.AndroidPlatform
import com.devstrike.henosis.insights.internal.Clock
import com.devstrike.henosis.insights.internal.Engine
import com.devstrike.henosis.insights.internal.HttpTransport
import com.devstrike.henosis.insights.internal.Platform
import com.devstrike.henosis.insights.internal.SystemClock
import com.devstrike.henosis.insights.internal.Transport
import com.devstrike.henosis.insights.internal.Worker

/**
 * Henosis Insights: integration telemetry for SDK vendors, with no end-user data.
 *
 * A vendor SDK calls [start] once from its own init and keeps the returned [InsightsClient].
 * The static methods here mirror the client's and apply to the single running SDK (see
 * [InsightsClient] for apps that embed more than one Insights-enabled SDK).
 *
 * Every call is cheap, thread-safe and never throws: an Insights failure is swallowed (and logged
 * when `debug = true`), so it can never break the vendor SDK or the host app. Nothing runs on the
 * caller's thread except enqueuing.
 *
 * Host apps can opt out with [setEnabled] or a manifest entry; either applies to every SDK in the app:
 * ```xml
 * <meta-data android:name="com.devstrike.henosis.insights.ENABLED" android:value="false" />
 * ```
 */
public object HenosisInsights {
    /** Version of this client library, reported as `client.version`. */
    public const val VERSION: String = "0.1.0"

    /** Manifest `<meta-data>` key a host app sets to `false` to disable Insights for every SDK in the app. */
    public const val MANIFEST_ENABLED_KEY: String = "com.devstrike.henosis.insights.ENABLED"

    private val clients = LinkedHashMap<String, InsightsClient>()

    /** Host opt-out requested before any engine could persist it; applied at the next start. */
    @Volatile private var pendingHostEnabled: Boolean? = null

    /** Receiver for wrapper packages running the Dart / TypeScript client in bridged mode. */
    @JvmStatic
    public val bridge: InsightsBridge
        get() = InsightsBridge

    /**
     * Start reporting for one SDK and return its handle. Calling it again for the same write key and
     * SDK name returns the running handle. Never throws; on failure returns a no-op handle.
     */
    @JvmStatic
    public fun start(context: Context, config: InsightsConfig): InsightsClient =
        try {
            val platform = AndroidPlatform(context.applicationContext ?: context, config)
            start(platform, config, HttpTransport(), Worker.create(), SystemClock)
        } catch (_: Throwable) {
            InsightsClient.NOOP
        }

    @JvmStatic
    public fun countCall(method: String) {
        single().countCall(method)
    }

    @JvmStatic
    @JvmOverloads
    public fun reportError(method: String, code: String, throwable: Throwable? = null) {
        single().reportError(method, code, throwable)
    }

    @JvmStatic
    public fun check(check: HealthCheck) {
        single().check(check)
    }

    @JvmStatic
    public fun checkWorkerThread(method: String) {
        single().checkWorkerThread(method)
    }

    @JvmStatic
    public fun markDeprecated(method: String) {
        single().markDeprecated(method)
    }

    @JvmStatic
    public fun flush() {
        single().flush()
    }

    /**
     * Host-app opt-out for every SDK in the app. `false` stops collection immediately, deletes anything
     * queued, and is remembered across launches. May be called before any SDK has started.
     */
    @JvmStatic
    public fun setEnabled(enabled: Boolean) {
        try {
            pendingHostEnabled = enabled
            for (client in snapshot()) client.engine?.setHostEnabled(enabled)
        } catch (_: Throwable) {
        }
    }

    /** Whether the single running SDK's stream is collecting. False when none or several are running. */
    @JvmStatic
    public fun isEnabled(): Boolean = single().isEnabled()

    /**
     * Stop every running stream. Queued events stay on disk and are sent after the next [start].
     * For tests and SDKs with an explicit shutdown; returns immediately.
     */
    @JvmStatic
    public fun stop() {
        try {
            val all = synchronized(clients) {
                val list = clients.values.toList()
                clients.clear()
                list
            }
            for (c in all) c.engine?.stop()
        } catch (_: Throwable) {
        }
    }

    // ---- internals -------------------------------------------------------------------------------

    internal fun start(
        platform: Platform,
        config: InsightsConfig,
        transport: Transport,
        worker: Worker,
        clock: Clock,
        startupGraceMs: Long = Engine.STARTUP_GRACE_MS,
    ): InsightsClient =
        synchronized(clients) {
            val key = "${config.writeKey}|${config.sdkName}"
            clients[key]?.let {
                worker.shutdown()
                return it
            }
            val engine = Engine(config, platform, transport, worker, clock, startupGraceMs)
            pendingHostEnabled?.let { engine.setHostEnabled(it) }
            engine.start()
            InsightsClient(engine).also { clients[key] = it }
        }

    internal fun runningCount(): Int = synchronized(clients) { clients.size }

    internal fun engineFor(writeKey: String, sdkName: String): Engine? =
        synchronized(clients) { clients["$writeKey|$sdkName"]?.engine }

    internal fun runningEngines(): List<Engine> = synchronized(clients) { clients.values.mapNotNull { it.engine } }

    internal fun resetForTests() {
        stop()
        pendingHostEnabled = null
    }

    private fun snapshot(): List<InsightsClient> = synchronized(clients) { clients.values.toList() }

    private fun single(): InsightsClient =
        try {
            synchronized(clients) { if (clients.size == 1) clients.values.first() else null }
                ?: InsightsClient.NOOP
        } catch (_: Throwable) {
            InsightsClient.NOOP
        }
}
