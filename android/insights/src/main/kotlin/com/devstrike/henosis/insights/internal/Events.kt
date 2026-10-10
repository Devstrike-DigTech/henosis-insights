package com.devstrike.henosis.insights.internal

import com.devstrike.henosis.insights.HealthCheck
import com.devstrike.henosis.insights.InsightsConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Builders for schema v1 events and the batch envelope. `org.json` only. */
internal object Events {
    const val MAX_FRAMES = 64
    const val MAX_CHECKS = 50
    const val MAX_COUNTS = 500
    const val MAX_EVENTS_PER_BATCH = 500

    fun iso(millis: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(millis))
    }

    fun utcDay(millis: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(millis))
    }

    fun init(checks: List<HealthCheck>): JSONObject {
        val arr = JSONArray()
        for (c in checks.take(MAX_CHECKS)) arr.put(JSONObject().put("name", c.name).put("ok", c.ok))
        return JSONObject().put("kind", "init").put("ok", checks.all { it.ok }).put("checks", arr)
    }

    fun calls(from: Long, to: Long, counts: Map<String, Int>): JSONObject {
        val obj = JSONObject()
        for ((method, n) in counts.entries.sortedByDescending { it.value }.take(MAX_COUNTS)) obj.put(method, n)
        return JSONObject().put("kind", "calls").put("from", iso(from)).put("to", iso(to)).put("counts", obj)
    }

    fun error(method: String, code: String, layer: String, frames: List<Frame>): JSONObject {
        val arr = JSONArray()
        for (f in frames.take(MAX_FRAMES)) arr.put(JSONObject().put("cls", f.cls).put("fn", f.fn).put("line", f.line))
        return JSONObject()
            .put("kind", "error")
            .put("method", Names.method(method))
            .put("code", Names.code(code))
            .put("layer", layer)
            .put("frames", arr)
    }

    fun health(check: HealthCheck): JSONObject {
        val obj = JSONObject().put("kind", "health").put("check", check.name).put("ok", check.ok)
        if (!check.ok && check.detail != null) obj.put("detail", check.detail.wire)
        return obj
    }

    fun deprecated(method: String, count: Int): JSONObject =
        JSONObject().put("kind", "deprecated").put("method", Names.method(method)).put("count", count)

    fun heartbeat(): JSONObject = JSONObject().put("kind", "heartbeat")

    data class Frame(val cls: String, val fn: String, val line: Int)

    /** A Flutter / React Native / web wrapper that forwards its events to this native client. */
    data class Wrapper(val kind: String, val version: String?) {
        fun toJson(): JSONObject = JSONObject().put("kind", kind).also { o -> version?.let { o.put("version", it) } }

        companion object {
            val KINDS = setOf("flutter", "react-native", "web", "node", "other")
        }
    }

    /** Keep only frames inside the vendor's own packages. App frames are never reported. */
    fun frames(throwable: Throwable?, prefixes: List<String>): List<Frame> {
        if (throwable == null || prefixes.isEmpty()) return emptyList()
        val out = ArrayList<Frame>()
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < 8 && out.size < MAX_FRAMES) {
            for (el in t.stackTrace) {
                val cls = el.className ?: continue
                if (prefixes.none { cls.startsWith(it) }) continue
                val c = Names.frameClass(cls) ?: continue
                val fn = Names.frameFn(el.methodName ?: "<unknown>") ?: continue
                out.add(Frame(c, fn, el.lineNumber.coerceAtLeast(0)))
                if (out.size >= MAX_FRAMES) break
            }
            t = t.cause?.takeIf { it !== t }
            depth++
        }
        return out
    }

    fun batch(
        config: InsightsConfig,
        platform: Platform,
        clientVersion: String,
        customerKey: String,
        sentAt: Long,
        events: List<JSONObject>,
        wrapper: Wrapper? = null,
    ): JSONObject {
        val device = JSONObject().put("os", "android")
        Names.version(platform.osVersion)?.let { device.put("osVersion", it) }
        platform.deviceClass?.let { device.put("class", it) }
        val app = JSONObject().put("id", platform.appId)
        Names.version(platform.appVersion)?.let { app.put("version", it) }
        val arr = JSONArray()
        for (e in events) arr.put(e)
        return JSONObject()
            .put("schema", 1)
            .put("writeKey", config.writeKey)
            // A wrapper that registered over the bridge makes this a bridged stream for that wrapper.
            .put("client", JSONObject().put("lang", "kotlin").put("version", clientVersion).put("mode", if (wrapper != null) "bridged" else "native"))
            .put("sdk", JSONObject().put("name", Names.sdkName(config.sdkName)).put("version", Names.version(config.sdkVersion) ?: "0"))
            .put("wrapper", wrapper?.toJson() ?: JSONObject().put("kind", "native"))
            .put("customerKey", customerKey)
            .put("app", app)
            .put("device", device)
            .put("sentAt", iso(sentAt))
            .put("events", arr)
    }
}
