package com.devstrike.henosis.insights.internal

import com.devstrike.henosis.insights.InsightsConfig
import org.json.JSONObject
import java.io.File
import java.util.Collections

internal const val TEST_WRITE_KEY = "hi_test_0123456789abcdef0123456789abcdef"
internal const val CUSTOMER_HASH = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

internal fun testConfig(
    customerKey: String = CUSTOMER_HASH,
    sdkName: String = "acme-payments",
    prefixes: List<String> = listOf("com.acme.payments."),
    permissions: List<String> = emptyList(),
    sampleRate: Double = 1.0,
) = InsightsConfig(
    writeKey = TEST_WRITE_KEY,
    sdkName = sdkName,
    sdkVersion = "4.2.0",
    customerKey = customerKey,
    sdkPackagePrefixes = prefixes,
    requiredPermissions = permissions,
    sampleRate = sampleRate,
    endpoint = "http://127.0.0.1:9/v1/batches",
)

internal class MapStore : KeyValueStore {
    val map: MutableMap<String, String> = Collections.synchronizedMap(HashMap())

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

internal class FakePlatform(
    private val dir: File,
    var wifi: Boolean? = false,
    var granted: Set<String> = emptySet(),
    var optOutInManifest: Boolean = false,
    var mainThread: Boolean = false,
    override val prefs: MapStore = MapStore(),
    override val globalPrefs: MapStore = MapStore(),
) : Platform {
    val logs: MutableList<String> = Collections.synchronizedList(ArrayList())

    override val appId: String = "com.example.shop"
    override val appVersion: String? = "12.3.1"
    override val osVersion: String? = "15"
    override val deviceClass: String? = "phone"

    override fun isMainThread(): Boolean = mainThread

    override fun isOnWifi(): Boolean? = wifi

    override fun hasPermission(permission: String): Boolean = permission in granted

    override fun manifestOptOut(): Boolean = optOutInManifest

    override fun storageDir(): File = dir

    override fun log(message: String, throwable: Throwable?) {
        logs += message + (throwable?.let { " :: ${it.javaClass.simpleName}" } ?: "")
    }
}

/** Records every body; answers with [result] (or throws [failure]). */
internal class FakeTransport(
    @Volatile var result: SendResult = SendResult.Accepted,
    @Volatile var failure: Throwable? = null,
) : Transport {
    val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
    val keys: MutableList<String> = Collections.synchronizedList(ArrayList())

    override fun send(endpoint: String, writeKey: String, body: ByteArray): SendResult {
        failure?.let { throw it }
        bodies += String(body, Charsets.UTF_8)
        keys += writeKey
        return result
    }

    fun batches(): List<JSONObject> = bodies.toList().map { JSONObject(it) }

    fun events(): List<JSONObject> = batches().flatMap { b -> b.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }

    fun kinds(): List<String> = events().map { it.getString("kind") }
}

/** Runs every task immediately on the calling thread: deterministic engine tests. */
internal class DirectWorker : Worker {
    var shutdown = false

    override fun post(task: Runnable) {
        check(!shutdown) { "worker is shut down" }
        task.run()
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun awaitIdle(timeoutMs: Long): Boolean = true
}

internal class FakeClock(var now: Long) : Clock {
    override fun now(): Long = now

    fun advanceMinutes(m: Long) {
        now += m * 60_000
    }

    fun advanceHours(h: Long) {
        now += h * 3_600_000
    }
}

/** 2026-10-09T10:00:00Z */
internal const val T0 = 1_791_540_000_000L
