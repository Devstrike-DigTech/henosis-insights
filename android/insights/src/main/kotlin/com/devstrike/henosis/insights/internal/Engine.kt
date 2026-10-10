package com.devstrike.henosis.insights.internal

import com.devstrike.henosis.insights.HealthCheck
import com.devstrike.henosis.insights.HealthDetail
import com.devstrike.henosis.insights.HenosisInsights
import com.devstrike.henosis.insights.InsightsConfig
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * The Insights engine for one SDK. All state lives on the worker thread; public entry points only
 * post. Nothing here throws to the caller: every task is wrapped.
 *
 * Upload policy (CLAUDE.md rule 4): uploads happen on SDK activity only. An upload is attempted when
 * there is something to send and either the device is on Wi-Fi (and at least [MIN_WIFI_INTERVAL_MS]
 * passed since the last attempt) or [MAX_INTERVAL_MS] (6 h) passed since the last successful upload.
 * There are no timers, alarms or background jobs.
 */
internal class Engine(
    val config: InsightsConfig,
    private val platform: Platform,
    private val transport: Transport,
    private val worker: Worker,
    private val clock: Clock,
    /**
     * At the start of a session the first automatic upload waits this long unless the wrapper is
     * already known, so a Flutter / React Native wrapper can register over the bridge first and the
     * first batch is tagged with it. `flush()` is never delayed.
     */
    private val startupGraceMs: Long = STARTUP_GRACE_MS,
) {
    private var sessionStart = 0L
    private val prefixes = config.sdkPackagePrefixes.filter { it.isNotBlank() }
    private val customerKey: String
    private val customerKeyOk: Boolean
    private val customerKeyMissing: Boolean = config.customerKey.isBlank()

    private lateinit var queue: EventQueue
    private val callCounts = HashMap<String, Int>()
    private val deprecatedCounts = HashMap<String, Int>()
    private var windowFrom = 0L
    private var lastUploadAt = 0L
    private var lastAttemptAt = 0L
    private var nextAttemptAt = 0L
    @Volatile private var enabled = false
    @Volatile private var stopped = false
    private var wrapper: Events.Wrapper? = null

    init {
        val (key, ok) = Names.customerKey(config.customerKey)
        customerKey = key
        customerKeyOk = ok
    }

    fun sameAs(other: InsightsConfig): Boolean =
        other.writeKey == config.writeKey && other.sdkName == config.sdkName

    fun isMainThread(): Boolean = platform.isMainThread()

    fun isEnabled(): Boolean = enabled && !stopped

    fun log(message: String, throwable: Throwable?) {
        try {
            platform.log(message, throwable)
        } catch (_: Throwable) {
        }
    }

    // ---- public entry points: post to the worker ------------------------------------------------

    fun start() = post {
        sessionStart = clock.now()
        enabled = computeEnabled()
        queue = EventQueue(if (enabled) File(platform.storageDir(), "queue.jsonl") else null, log = ::log)
        loadCounters()
        loadWrapper()
        lastUploadAt = platform.prefs.get(PREF_LAST_UPLOAD)?.toLongOrNull() ?: 0L
        if (!enabled) {
            log("disabled (opt-out or sampled out)", null)
            return@post
        }
        val checks = startupChecks()
        enqueue(Events.init(checks))
        for (c in checks) if (!c.ok) enqueue(Events.health(c))
        heartbeatIfDue()
        maybeUpload(force = false)
    }

    fun countCall(method: String) = post {
        if (!enabled) return@post
        val m = Names.method(method)
        if (callCounts.size >= Events.MAX_COUNTS && m !in callCounts) return@post
        if (windowFrom == 0L) windowFrom = clock.now()
        callCounts[m] = (callCounts[m] ?: 0) + 1
        countersDirty = true
        onActivity()
    }

    fun reportError(method: String, code: String, throwable: Throwable? = null) = post {
        if (!enabled) return@post
        // Frames are filtered on the worker: only the vendor's own classes survive. The message,
        // the exception type and any app frames are never read into the event.
        enqueue(Events.error(method, code, "native", Events.frames(throwable, prefixes)))
        onActivity()
    }

    fun health(check: HealthCheck) = post {
        if (!enabled) return@post
        enqueue(Events.health(check))
        onActivity()
    }

    fun markDeprecated(method: String) = post {
        if (!enabled) return@post
        val m = Names.method(method)
        if (deprecatedCounts.size >= Events.MAX_COUNTS && m !in deprecatedCounts) return@post
        deprecatedCounts[m] = (deprecatedCounts[m] ?: 0) + 1
        countersDirty = true
        onActivity()
    }

    /**
     * A message forwarded by a Dart / TypeScript client running in bridged mode inside a wrapper
     * (see docs/bridge-protocol.md). Parsed and applied on the worker; anything malformed or meant for
     * another SDK is ignored. Wrapper errors are always tagged `layer: wrapper`.
     */
    fun receiveBridge(json: String) = post {
        if (json.length > MAX_BRIDGE_CHARS) return@post
        val msg = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return@post
        }
        if (msg.optInt("bridge", -1) != BRIDGE_PROTOCOL) return@post
        if (msg.optString("writeKey") != config.writeKey || msg.optString("sdkName") != config.sdkName) return@post
        msg.optJSONObject("wrapper")?.let { w ->
            val kind = w.optString("kind")
            if (kind in Events.Wrapper.KINDS) setWrapper(Events.Wrapper(kind, Names.version(w.optString("version").takeIf { it.isNotEmpty() })))
        }
        if (!enabled) return@post
        mergeCounts(msg.optJSONObject("calls"), callCounts, startsWindow = true)
        mergeCounts(msg.optJSONObject("deprecated"), deprecatedCounts, startsWindow = false)
        val events = msg.optJSONArray("events")
        if (events != null) {
            for (i in 0 until minOf(events.length(), Events.MAX_EVENTS_PER_BATCH)) {
                val e = events.optJSONObject(i) ?: continue
                when (e.optString("kind")) {
                    "error" -> enqueue(Events.error(e.optString("method"), e.optString("code"), "wrapper", bridgeFrames(e.optJSONArray("frames"))))
                    "health" -> {
                        val ok = e.optBoolean("ok", false)
                        val detail = HealthDetail.fromWire(e.optString("detail")) ?: HealthDetail.OTHER
                        enqueue(Events.health(HealthCheck.Custom(e.optString("check"), ok, detail)))
                    }
                    // init, heartbeat and calls belong to the native client; anything else is unknown.
                    else -> Unit
                }
            }
        }
        onActivity()
    }

    private fun mergeCounts(obj: JSONObject?, into: HashMap<String, Int>, startsWindow: Boolean) {
        if (obj == null) return
        for (key in obj.keys()) {
            val n = obj.optInt(key, 0).coerceIn(0, MAX_BRIDGE_COUNT)
            if (n == 0) continue
            val m = Names.method(key)
            if (into.size >= Events.MAX_COUNTS && m !in into) continue
            if (startsWindow && windowFrom == 0L) windowFrom = clock.now()
            into[m] = ((into[m] ?: 0).toLong() + n).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            countersDirty = true
        }
    }

    private fun bridgeFrames(arr: org.json.JSONArray?): List<Events.Frame> {
        if (arr == null) return emptyList()
        val out = ArrayList<Events.Frame>()
        for (i in 0 until minOf(arr.length(), Events.MAX_FRAMES)) {
            val f = arr.optJSONObject(i) ?: continue
            val cls = Names.frameClass(f.optString("cls")) ?: continue
            val fn = Names.frameFn(f.optString("fn").ifEmpty { "<anonymous>" }) ?: continue
            out.add(Events.Frame(cls, fn, f.optInt("line", 0).coerceAtLeast(0)))
        }
        return out
    }

    private fun setWrapper(w: Events.Wrapper) {
        if (w == wrapper) return
        wrapper = w
        platform.prefs.put(PREF_WRAPPER, w.toJson().toString())
    }

    /** The wrapper seen on an earlier launch, so this launch's first batch already carries it. */
    private fun loadWrapper() {
        try {
            val raw = platform.prefs.get(PREF_WRAPPER) ?: return
            val o = JSONObject(raw)
            val kind = o.optString("kind")
            if (kind in Events.Wrapper.KINDS) wrapper = Events.Wrapper(kind, Names.version(o.optString("version").takeIf { it.isNotEmpty() }))
        } catch (_: Throwable) {
        }
    }

    /** Host-app opt-out. Applies to every SDK in the app, so it lives in the app-wide store. */
    fun setHostEnabled(value: Boolean) = post {
        platform.globalPrefs.put(PREF_HOST_ENABLED, value.toString())
        if (!this::queue.isInitialized) return@post
        val now = computeEnabled()
        if (!now && enabled) {
            // Opt-out: forget everything, immediately.
            callCounts.clear()
            deprecatedCounts.clear()
            windowFrom = 0L
            queue.clear()
            platform.prefs.put(PREF_COUNTERS, null)
        }
        enabled = now
    }

    fun flush() = post {
        if (!enabled) return@post
        heartbeatIfDue()
        maybeUpload(force = true)
    }

    /** Persist and stop. Returns immediately; already-posted work finishes on the worker. */
    fun stop() {
        post {
            persistCounters()
            if (this::queue.isInitialized) queue.persist()
        }
        stopped = true
        worker.shutdown()
    }

    /** Tests only: wait until the worker has drained. */
    fun awaitIdle(timeoutMs: Long = 5_000): Boolean = worker.awaitIdle(timeoutMs)

    // ---- worker-side logic ---------------------------------------------------------------------

    private fun post(task: () -> Unit) {
        if (stopped) return
        try {
            worker.post {
                try {
                    task()
                } catch (t: Throwable) {
                    log("Insights task failed", t)
                }
            }
        } catch (t: Throwable) {
            log("Insights could not schedule task", t)
        }
    }

    private fun computeEnabled(): Boolean {
        if (platform.manifestOptOut()) return false
        if (platform.globalPrefs.get(PREF_HOST_ENABLED) == "false") return false
        return sampledIn()
    }

    private fun sampledIn(): Boolean {
        val rate = config.sampleRate.coerceIn(0.0, 1.0)
        if (rate >= 1.0) return true
        if (rate <= 0.0) return false
        val stored = platform.prefs.get(PREF_SAMPLE)?.toDoubleOrNull()
        val draw = stored ?: Random.nextDouble().also { platform.prefs.put(PREF_SAMPLE, it.toString()) }
        return draw < rate
    }

    private fun startupChecks(): List<HealthCheck> {
        val checks = ArrayList<HealthCheck>()
        checks.add(if (customerKeyMissing) HealthCheck.CustomerKeyMissing() else HealthCheck.CustomerKeyFormat(customerKeyOk))
        for (p in config.requiredPermissions.distinct()) checks.add(HealthCheck.PermissionGranted(p, platform.hasPermission(p)))
        return checks
    }

    private fun enqueue(event: JSONObject) {
        queue.offer(event.toString())
    }

    private fun onActivity() {
        heartbeatIfDue()
        maybeUpload(force = false)
        queue.persist()
        persistCounters()
    }

    private fun heartbeatIfDue() {
        val today = Events.utcDay(clock.now())
        if (platform.prefs.get(PREF_HEARTBEAT_DAY) == today) return
        enqueue(Events.heartbeat())
        platform.prefs.put(PREF_HEARTBEAT_DAY, today)
    }

    private fun hasPending(): Boolean = queue.size > 0 || callCounts.isNotEmpty() || deprecatedCounts.isNotEmpty()

    private fun shouldUpload(now: Long, force: Boolean): Boolean {
        if (!hasPending()) return false
        if (now < nextAttemptAt) return false
        if (force) return true
        if (wrapper == null && now - sessionStart < startupGraceMs) return false
        if (now - lastUploadAt >= MAX_INTERVAL_MS) return true
        return platform.isOnWifi() == true && now - lastAttemptAt >= MIN_WIFI_INTERVAL_MS
    }

    private fun maybeUpload(force: Boolean) {
        val now = clock.now()
        if (!shouldUpload(now, force)) return
        lastAttemptAt = now
        flushCountersToQueue(now)
        val events = queue.peek(Events.MAX_EVENTS_PER_BATCH).map { JSONObject(it) }
        if (events.isEmpty()) return
        val batch = Events.batch(config, platform, HenosisInsights.VERSION, customerKey, now, events, wrapper)
        val body = batch.toString().toByteArray(Charsets.UTF_8)
        when (val result = transport.send(config.endpoint, config.writeKey, body)) {
            SendResult.Accepted -> {
                queue.remove(events.size)
                lastUploadAt = now
                nextAttemptAt = 0L
                platform.prefs.put(PREF_LAST_UPLOAD, now.toString())
                log("uploaded ${events.size} events", null)
                if (queue.size > 0) maybeUpload(force = true)
            }
            is SendResult.Rejected -> {
                // The ingest will never accept these; keep the rest.
                queue.remove(events.size)
                log("batch rejected with ${result.status}; ${events.size} events dropped", null)
            }
            is SendResult.Retry -> {
                nextAttemptAt = now + RETRY_BACKOFF_MS
                log("upload deferred (${result.status ?: "network"})", result.cause)
            }
        }
        queue.persist()
    }

    private fun flushCountersToQueue(now: Long) {
        if (callCounts.isNotEmpty()) {
            enqueue(Events.calls(if (windowFrom == 0L) now else windowFrom, now, callCounts))
            callCounts.clear()
            windowFrom = 0L
            countersDirty = true
        }
        if (deprecatedCounts.isNotEmpty()) {
            for ((m, n) in deprecatedCounts) enqueue(Events.deprecated(m, n))
            deprecatedCounts.clear()
            countersDirty = true
        }
        persistCounters()
    }

    // Counters survive process death via prefs so a short-lived app still reports its calls.
    private var countersDirty = false

    private fun persistCounters() {
        if (!countersDirty) return
        countersDirty = false
        try {
            if (callCounts.isEmpty() && deprecatedCounts.isEmpty()) {
                platform.prefs.put(PREF_COUNTERS, null)
                return
            }
            val calls = JSONObject()
            for ((k, v) in callCounts) calls.put(k, v)
            val dep = JSONObject()
            for ((k, v) in deprecatedCounts) dep.put(k, v)
            platform.prefs.put(PREF_COUNTERS, JSONObject().put("from", windowFrom).put("calls", calls).put("deprecated", dep).toString())
        } catch (t: Throwable) {
            log("counter persist failed", t)
        }
    }

    private fun loadCounters() {
        try {
            val raw = platform.prefs.get(PREF_COUNTERS) ?: return
            val obj = JSONObject(raw)
            windowFrom = obj.optLong("from", 0L)
            val calls = obj.optJSONObject("calls")
            if (calls != null) for (k in calls.keys()) callCounts[k] = calls.optInt(k, 0)
            val dep = obj.optJSONObject("deprecated")
            if (dep != null) for (k in dep.keys()) deprecatedCounts[k] = dep.optInt(k, 0)
        } catch (t: Throwable) {
            log("counter load failed", t)
            platform.prefs.put(PREF_COUNTERS, null)
        }
    }

    internal companion object {
        const val MAX_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val MIN_WIFI_INTERVAL_MS = 60L * 1000
        const val STARTUP_GRACE_MS = 5_000L
        const val RETRY_BACKOFF_MS = 5L * 60 * 1000
        const val PREF_HOST_ENABLED = "host_enabled"
        const val PREF_SAMPLE = "sample"
        const val PREF_HEARTBEAT_DAY = "heartbeat_day"
        const val PREF_LAST_UPLOAD = "last_upload"
        const val PREF_COUNTERS = "counters"
        const val PREF_WRAPPER = "wrapper"
        const val BRIDGE_PROTOCOL = 1
        const val MAX_BRIDGE_CHARS = 1_000_000
        const val MAX_BRIDGE_COUNT = 1_000_000
    }
}
