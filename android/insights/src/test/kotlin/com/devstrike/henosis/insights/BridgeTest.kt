package com.devstrike.henosis.insights

import com.devstrike.henosis.insights.internal.DirectWorker
import com.devstrike.henosis.insights.internal.FakeClock
import com.devstrike.henosis.insights.internal.FakePlatform
import com.devstrike.henosis.insights.internal.FakeTransport
import com.devstrike.henosis.insights.internal.MapStore
import com.devstrike.henosis.insights.internal.SchemaCheck
import com.devstrike.henosis.insights.internal.T0
import com.devstrike.henosis.insights.internal.TEST_WRITE_KEY
import com.devstrike.henosis.insights.internal.testConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BridgeTest {
    @TempDir lateinit var dir: File

    @AfterEach
    fun reset() = HenosisInsights.resetForTests()

    private fun start(
        transport: FakeTransport = FakeTransport(),
        prefs: MapStore = MapStore(),
        global: MapStore = MapStore(),
        sdk: String = "acme-payments",
    ): Pair<InsightsClient, FakeTransport> {
        val client = HenosisInsights.start(
            FakePlatform(File(dir, sdk), prefs = prefs, globalPrefs = global),
            testConfig(sdkName = sdk),
            transport,
            DirectWorker(),
            FakeClock(T0),
            startupGraceMs = 0,
        )
        return client to transport
    }

    private fun message(
        calls: Map<String, Int> = emptyMap(),
        events: List<JSONObject> = emptyList(),
        wrapper: JSONObject? = JSONObject().put("kind", "flutter").put("version", "2.0.3"),
        sdk: String = "acme-payments",
        writeKey: String = TEST_WRITE_KEY,
    ): String = JSONObject()
        .put("bridge", 1)
        .put("writeKey", writeKey)
        .put("sdkName", sdk)
        .put("client", JSONObject().put("lang", "dart").put("version", "0.1.0"))
        .apply { if (wrapper != null) put("wrapper", wrapper) }
        .put("calls", JSONObject(calls))
        .put("events", JSONArray(events))
        .toString()

    private fun wrapperError(code: String = "INVALID_CURRENCY") = JSONObject()
        .put("kind", "error")
        .put("method", "charge")
        .put("code", code)
        .put("layer", "native") // a wrapper cannot claim the native layer
        .put("frames", JSONArray().put(JSONObject().put("cls", "acme_payments_flutter/src/api.dart").put("fn", "AcmePayments.charge").put("line", 12)))

    @Test
    fun `isRunning reports the native stream, even while the host has opted out`() {
        assertFalse(HenosisInsights.bridge.isRunning(TEST_WRITE_KEY, "acme-payments"))
        start()
        assertTrue(HenosisInsights.bridge.isRunning(TEST_WRITE_KEY, "acme-payments"))
        assertFalse(HenosisInsights.bridge.isRunning(TEST_WRITE_KEY, "other-sdk"))
        assertFalse(HenosisInsights.bridge.isRunning("hi_test_ffffffffffffffffffffffffffffffff", "acme-payments"))
        HenosisInsights.setEnabled(false)
        assertTrue(HenosisInsights.bridge.isRunning(TEST_WRITE_KEY, "acme-payments"), "the wrapper must not fall back to direct")
    }

    @Test
    fun `wrapper events join the single native stream as a bridged flutter stream`() {
        val (client, t) = start()
        t.bodies.clear()
        client.countCall("charge")
        client.reportError("charge", "CARD_DECLINED_CONFIG")
        assertTrue(HenosisInsights.bridge.receive(message(calls = mapOf("charge" to 2, "Wrapper.ready" to 1), events = listOf(wrapperError()))))
        client.flush()

        val batch = t.batches().single()
        assertEquals("kotlin", batch.getJSONObject("client").getString("lang"))
        assertEquals("bridged", batch.getJSONObject("client").getString("mode"))
        assertEquals("flutter", batch.getJSONObject("wrapper").getString("kind"))
        assertEquals("2.0.3", batch.getJSONObject("wrapper").getString("version"))

        val calls = t.events().single { it.getString("kind") == "calls" }.getJSONObject("counts")
        assertEquals(3, calls.getInt("charge"), "native 1 + wrapper 2, merged")
        assertEquals(1, calls.getInt("Wrapper.ready"))

        val errors = t.events().filter { it.getString("kind") == "error" }
        assertEquals(listOf("native" to "CARD_DECLINED_CONFIG", "wrapper" to "INVALID_CURRENCY"), errors.map { it.getString("layer") to it.getString("code") })
        assertEquals("acme_payments_flutter/src/api.dart", errors[1].getJSONArray("frames").getJSONObject(0).getString("cls"))

        for (body in t.bodies) assertTrue(SchemaCheck.errors(body).isEmpty(), body)
    }

    @Test
    fun `a registration with no events is enough to tag the stream`() {
        val (client, t) = start()
        HenosisInsights.bridge.receive(message(wrapper = JSONObject().put("kind", "react-native").put("version", "0.9.4")))
        client.countCall("charge")
        client.flush()
        val batch = t.batches().last()
        assertEquals("react-native", batch.getJSONObject("wrapper").getString("kind"))
        assertEquals("bridged", batch.getJSONObject("client").getString("mode"))
    }

    @Test
    fun `the wrapper is remembered so the next launch's first batch already carries it`() {
        val prefs = MapStore()
        start(prefs = prefs)
        HenosisInsights.bridge.receive(message())
        HenosisInsights.stop()
        val (client2, t2) = start(prefs = prefs, transport = FakeTransport())
        client2.flush() // the first launch uploaded recently, so this one waits for activity or a flush
        val first = t2.batches().first()
        assertEquals("flutter", first.getJSONObject("wrapper").getString("kind"))
        assertEquals("bridged", first.getJSONObject("client").getString("mode"))
    }

    @Test
    fun `messages for another SDK or write key, or of another protocol, are ignored`() {
        val (client, t) = start()
        HenosisInsights.bridge.receive(message(calls = mapOf("x" to 5), sdk = "other-sdk"))
        HenosisInsights.bridge.receive(message(calls = mapOf("x" to 5), writeKey = "hi_test_ffffffffffffffffffffffffffffffff"))
        HenosisInsights.bridge.receive(JSONObject(message(calls = mapOf("x" to 5))).put("bridge", 2).toString())
        t.bodies.clear()
        client.countCall("y")
        client.flush()
        assertFalse(t.bodies.joinToString().contains("\"x\""))
        assertEquals("native", t.batches().single().getJSONObject("wrapper").getString("kind"))
    }

    @Test
    fun `native-only kinds and invalid fields from a wrapper are dropped or normalised`() {
        val (client, t) = start()
        t.bodies.clear()
        val events = listOf(
            JSONObject().put("kind", "init").put("ok", true).put("checks", JSONArray()),
            JSONObject().put("kind", "heartbeat"),
            JSONObject().put("kind", "calls").put("counts", JSONObject().put("z", 1)),
            JSONObject().put("kind", "screen").put("name", "Checkout"),
            JSONObject().put("kind", "health").put("check", "Bridge Ready!").put("ok", false).put("detail", "free text with an email a@b.co"),
            JSONObject().put("kind", "error").put("method", "pay card 4111").put("code", "bad code").put("frames", JSONArray().put("junk")),
        )
        HenosisInsights.bridge.receive(message(calls = mapOf("charge" to -5, "ok" to 2), events = events, wrapper = JSONObject().put("kind", "native")))
        client.flush()
        val body = t.bodies.joinToString()
        assertEquals(listOf("calls", "error", "health"), t.kinds().sorted())
        assertFalse(body.contains("a@b.co"))
        assertFalse(body.contains("4111 "))
        assertFalse(body.contains("Checkout"))
        assertEquals("native", t.batches().single().getJSONObject("wrapper").getString("kind"), "a wrapper cannot claim kind native")
        val health = t.events().single { it.getString("kind") == "health" }
        assertEquals("bridge_ready", health.getString("check"))
        assertEquals("other", health.getString("detail"))
        for (b in t.bodies) assertTrue(SchemaCheck.errors(b).isEmpty(), b)
    }

    @Test
    fun `on a first launch the first batch waits briefly so a wrapper can register, and then carries it`() {
        val clock = FakeClock(T0)
        val t = FakeTransport()
        val client = HenosisInsights.start(FakePlatform(dir), testConfig(), t, DirectWorker(), clock)
        assertTrue(t.bodies.isEmpty(), "no upload at start while the wrapper may still register")
        // The registration is activity: with the wrapper now known, the waiting batch goes out tagged.
        HenosisInsights.bridge.receive(message())
        val first = t.batches().single()
        assertEquals("flutter", first.getJSONObject("wrapper").getString("kind"))
        assertEquals("bridged", first.getJSONObject("client").getString("mode"))
        assertEquals(listOf("init", "heartbeat"), t.kinds())
        clock.now += 1_000
        client.countCall("charge")
        client.flush()
        assertTrue(t.batches().all { it.getJSONObject("wrapper").getString("kind") == "flutter" })
    }

    @Test
    fun `a native-only app uploads on the first activity after the grace period, and flush is never delayed`() {
        val clock = FakeClock(T0)
        val t = FakeTransport()
        val client = HenosisInsights.start(FakePlatform(dir), testConfig(), t, DirectWorker(), clock)
        client.countCall("charge")
        assertTrue(t.bodies.isEmpty())
        clock.now += 5_000
        client.countCall("charge")
        assertEquals("native", t.batches().single().getJSONObject("wrapper").getString("kind"))

        HenosisInsights.resetForTests()
        val t2 = FakeTransport()
        val c2 = HenosisInsights.start(FakePlatform(File(dir, "x")), testConfig(sdkName = "x"), t2, DirectWorker(), FakeClock(T0))
        c2.flush()
        assertEquals(1, t2.bodies.size)
    }

    @Test
    fun `garbage never throws`() {
        for (junk in listOf("", "{", "null", "[]", "{\"bridge\":1}", "x".repeat(2_000_000), message().replace("\"calls\":{}", "\"calls\":[1,2]"))) {
            HenosisInsights.bridge.receive(junk)
        }
        HenosisInsights.bridge.isRunning("", "")
        start()
        for (junk in listOf("", "{", "{\"bridge\":\"1\"}", "{\"bridge\":1,\"writeKey\":null}")) HenosisInsights.bridge.receive(junk)
    }

    @Test
    fun `with the host opted out, wrapper events are dropped too`() {
        val (client, t) = start()
        HenosisInsights.setEnabled(false)
        t.bodies.clear()
        HenosisInsights.bridge.receive(message(calls = mapOf("charge" to 3), events = listOf(wrapperError())))
        client.flush()
        assertTrue(t.bodies.isEmpty())
    }
}
