package com.devstrike.henosis.insights

/**
 * Receiver for Dart / TypeScript clients running in **bridged** mode inside a wrapper package
 * (Flutter plugin, React Native TurboModule) of this native SDK. The wrapper's own platform channel
 * calls these two methods; the wrapper's events then travel in this SDK's single native stream,
 * tagged with the wrapper's kind and version. Protocol: docs/bridge-protocol.md.
 *
 * Both methods are thread-safe, cheap on the calling thread (no parsing there) and never throw.
 */
public object InsightsBridge {
    /**
     * Whether a native Insights stream exists for [writeKey] + [sdkName]. Wrappers in `auto` mode use
     * this to choose bridged (true) or direct (false). It stays true while the host app has opted
     * out, so a wrapper never starts a second, direct stream behind the opt-out.
     */
    @JvmStatic
    public fun isRunning(writeKey: String, sdkName: String): Boolean =
        try {
            HenosisInsights.engineFor(writeKey, sdkName) != null
        } catch (_: Throwable) {
            false
        }

    /**
     * Deliver one bridge message (JSON). Returns true when a native stream for its SDK is running and
     * the message was queued for it; false otherwise. Malformed messages are dropped on the worker.
     */
    @JvmStatic
    public fun receive(json: String): Boolean =
        try {
            val engines = HenosisInsights.runningEngines()
            for (e in engines) e.receiveBridge(json)
            engines.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
}
