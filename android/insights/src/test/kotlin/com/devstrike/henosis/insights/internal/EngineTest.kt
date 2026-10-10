package com.devstrike.henosis.insights.internal

import com.devstrike.henosis.insights.HealthCheck
import com.devstrike.henosis.insights.HealthDetail
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EngineTest {
    @TempDir lateinit var dir: File

    private val clock = FakeClock(T0)
    private lateinit var platform: FakePlatform
    private val transport = FakeTransport()

    private fun engine(
        config: com.devstrike.henosis.insights.InsightsConfig = testConfig(),
        p: FakePlatform = FakePlatform(dir).also { platform = it },
        t: Transport = transport,
    ): Engine {
        platform = p
        return Engine(config, p, t, DirectWorker(), clock, startupGraceMs = 0).also { it.start() }
    }

    private fun lastEvents(): List<JSONObject> {
        val b = transport.batches().last().getJSONArray("events")
        return (0 until b.length()).map { b.getJSONObject(it) }
    }

    @Nested
    inner class Startup {
        @Test
        fun `first start uploads init and a heartbeat`() {
            engine()
            assertEquals(listOf("init", "heartbeat"), transport.kinds())
            val init = transport.events().first()
            assertTrue(init.getBoolean("ok"))
            assertEquals("customer_key_format", init.getJSONArray("checks").getJSONObject(0).getString("name"))
            val batch = transport.batches().single()
            assertEquals(1, batch.getInt("schema"))
            assertEquals(TEST_WRITE_KEY, transport.keys.single())
            val client = batch.getJSONObject("client")
            assertEquals(listOf("kotlin", "0.1.0", "native"), listOf(client.getString("lang"), client.getString("version"), client.getString("mode")))
            assertEquals("native", batch.getJSONObject("wrapper").getString("kind"))
            assertEquals("android", batch.getJSONObject("device").getString("os"))
            assertEquals("com.example.shop", batch.getJSONObject("app").getString("id"))
        }

        @Test
        fun `required permissions become init checks and failures become health events`() {
            val p = FakePlatform(dir, granted = setOf("android.permission.INTERNET"))
            engine(testConfig(permissions = listOf("android.permission.INTERNET", "android.permission.CAMERA")), p)
            val init = transport.events().first { it.getString("kind") == "init" }
            assertFalse(init.getBoolean("ok"))
            val checks = init.getJSONArray("checks")
            val byName = (0 until checks.length()).associate { checks.getJSONObject(it).let { c -> c.getString("name") to c.getBoolean("ok") } }
            assertEquals(mapOf("customer_key_format" to true, "permission_internet" to true, "permission_camera" to false), byName)
            val health = transport.events().single { it.getString("kind") == "health" }
            assertEquals("permission_camera", health.getString("check"))
            assertEquals("permission_missing", health.getString("detail"))
        }

        @Test
        fun `a raw-looking customer key is hashed and fails the format check`() {
            engine(testConfig(customerKey = "sk live 123"))
            val batch = transport.batches().single()
            assertEquals(Names.sha256("sk live 123"), batch.getString("customerKey"))
            val health = transport.events().single { it.getString("kind") == "health" }
            assertEquals("customer_key_format", health.getString("check"))
            assertEquals("invalid_format", health.getString("detail"))
        }

        @Test
        fun `a blank customer key is reported as missing`() {
            engine(testConfig(customerKey = ""))
            val health = transport.events().single { it.getString("kind") == "health" }
            assertEquals("missing", health.getString("detail"))
            assertTrue(transport.bodies.single().contains(Names.sha256("")))
        }
    }

    @Nested
    inner class UploadPolicy {
        @Test
        fun `off wifi, nothing is sent until six hours after the last upload`() {
            val e = engine()
            transport.bodies.clear()
            repeat(3) { e.countCall("charge") }
            clock.advanceHours(5)
            e.countCall("refund")
            assertTrue(transport.bodies.isEmpty())
            clock.advanceMinutes(60)
            e.countCall("charge")
            assertEquals(listOf("calls"), transport.kinds())
            val calls = transport.events().single()
            val counts = calls.getJSONObject("counts")
            assertEquals(mapOf("charge" to 4, "refund" to 1), counts.keys().asSequence().associateWith { counts.getInt(it) })
            assertEquals(Events.iso(T0), calls.getString("from"))
            assertEquals(Events.iso(T0 + 6 * 3_600_000L), calls.getString("to"))
        }

        @Test
        fun `on wifi, uploads on activity at most once a minute`() {
            val e = engine()
            platform.wifi = true
            transport.bodies.clear()
            e.countCall("charge")
            assertEquals(0, transport.bodies.size, "the start upload was less than a minute ago")
            clock.advanceMinutes(1)
            e.countCall("charge")
            assertEquals(1, transport.bodies.size)
            clock.now += 10_000
            e.countCall("charge")
            assertEquals(1, transport.bodies.size)
        }

        @Test
        fun `unknown network state falls back to the six hour interval`() {
            val e = engine()
            platform.wifi = null
            transport.bodies.clear()
            clock.advanceMinutes(10)
            e.countCall("charge")
            assertTrue(transport.bodies.isEmpty())
        }

        @Test
        fun `flush forces an upload`() {
            val e = engine()
            transport.bodies.clear()
            e.countCall("charge")
            e.flush()
            assertEquals(listOf("calls"), transport.kinds())
        }

        @Test
        fun `flush with nothing pending sends nothing`() {
            val e = engine()
            transport.bodies.clear()
            e.flush()
            assertTrue(transport.bodies.isEmpty())
        }

        @Test
        fun `a retryable failure keeps events and backs off`() {
            transport.result = SendResult.Retry(503)
            val e = engine()
            assertEquals(1, transport.bodies.size)
            e.flush()
            assertEquals(1, transport.bodies.size, "backing off")
            clock.advanceMinutes(5)
            transport.result = SendResult.Accepted
            e.flush()
            assertEquals(2, transport.bodies.size)
            assertEquals(listOf("init", "heartbeat"), lastEvents().map { it.getString("kind") })
            transport.bodies.clear()
            e.flush()
            assertTrue(transport.bodies.isEmpty(), "queue drained")
        }

        @Test
        fun `a rejected batch is dropped, not retried forever`() {
            transport.result = SendResult.Rejected(400)
            val e = engine()
            transport.result = SendResult.Accepted
            transport.bodies.clear()
            e.flush()
            assertTrue(transport.bodies.isEmpty())
        }

        @Test
        fun `large queues go out in batches of at most 500 events`() {
            transport.result = SendResult.Retry(503)
            val e = engine()
            repeat(1200) { e.reportError("charge", "E_$it") }
            transport.result = SendResult.Accepted
            transport.bodies.clear()
            clock.advanceMinutes(10)
            e.flush()
            val sizes = transport.batches().map { it.getJSONArray("events").length() }
            assertEquals(listOf(500, 500, 202), sizes)
        }
    }

    @Nested
    inner class Persistence {
        @Test
        fun `queued events survive a restart`() {
            val prefs = MapStore()
            val p1 = FakePlatform(dir, prefs = prefs)
            transport.result = SendResult.Retry(null)
            val e1 = engine(p = p1)
            e1.reportError("charge", "CARD_DECLINED_CONFIG")
            e1.stop()
            transport.result = SendResult.Accepted
            transport.bodies.clear()
            clock.advanceMinutes(10)
            val e2 = engine(p = FakePlatform(dir, prefs = prefs))
            e2.flush()
            assertTrue(transport.kinds().containsAll(listOf("init", "heartbeat", "error")), transport.kinds().toString())
        }

        @Test
        fun `call counters survive a restart`() {
            val prefs = MapStore()
            val e1 = engine(p = FakePlatform(dir, prefs = prefs))
            repeat(5) { e1.countCall("charge") }
            e1.stop()
            transport.bodies.clear()
            val e2 = engine(p = FakePlatform(dir, prefs = prefs))
            e2.flush()
            val calls = transport.events().single { it.getString("kind") == "calls" }
            assertEquals(5, calls.getJSONObject("counts").getInt("charge"))
        }

        @Test
        fun `heartbeat is sent once per UTC day`() {
            val e = engine()
            assertEquals(1, transport.kinds().count { it == "heartbeat" })
            clock.advanceHours(6)
            e.countCall("charge")
            assertEquals(1, transport.kinds().count { it == "heartbeat" })
            clock.advanceHours(14) // next UTC day
            e.flush()
            assertEquals(2, transport.kinds().count { it == "heartbeat" })
        }
    }

    @Nested
    inner class Errors {
        @Test
        fun `only SDK frames are reported and the message never is`() {
            val e = engine()
            transport.bodies.clear()
            val t = RuntimeException("card 4111 1111 1111 1111 for user@example.com")
            t.stackTrace = arrayOf(
                StackTraceElement("com.acme.payments.Client", "charge", "Client.kt", 118),
                StackTraceElement("com.example.checkout.CheckoutActivity", "onPay", "CheckoutActivity.kt", 42),
                StackTraceElement("com.acme.payments.internal.Gateway", "post", "Gateway.kt", 54),
                StackTraceElement("android.view.View", "performClick", "View.java", 7000),
            )
            val cause = IllegalStateException("token secret")
            cause.stackTrace = arrayOf(StackTraceElement("com.acme.payments.internal.Http", "send", "Http.kt", 9))
            t.initCause(cause)
            e.reportError("charge", "CARD_DECLINED_CONFIG", t)
            e.flush()
            val body = transport.bodies.single()
            val error = transport.events().single()
            val frames = error.getJSONArray("frames")
            assertEquals(
                listOf("com.acme.payments.Client", "com.acme.payments.internal.Gateway", "com.acme.payments.internal.Http"),
                (0 until frames.length()).map { frames.getJSONObject(it).getString("cls") },
            )
            for (forbidden in listOf("4111", "user@example.com", "token secret", "com.example.checkout", "CheckoutActivity", "android.view", "RuntimeException")) {
                assertFalse(body.contains(forbidden), "payload contains $forbidden")
            }
            assertEquals("native", error.getString("layer"))
        }

        @Test
        fun `no package prefixes means no frames at all`() {
            val e = engine(testConfig(prefixes = emptyList()))
            transport.bodies.clear()
            e.reportError("charge", "X", RuntimeException("boom"))
            e.flush()
            assertEquals(0, transport.events().single().getJSONArray("frames").length())
        }

        @Test
        fun `a self-referencing cause chain terminates`() {
            val e = engine()
            transport.bodies.clear()
            val a = RuntimeException("a")
            val b = RuntimeException("b", a)
            a.initCause(b)
            e.reportError("charge", "LOOP", a)
            e.flush()
            assertEquals("LOOP", transport.events().single().getString("code"))
        }
    }

    @Nested
    inner class OptOut {
        @Test
        fun `host opt-out deletes everything queued and stops collection`() {
            transport.result = SendResult.Retry(null)
            val e = engine()
            e.countCall("charge")
            e.reportError("charge", "X")
            e.setHostEnabled(false)
            assertFalse(e.isEnabled())
            transport.result = SendResult.Accepted
            transport.bodies.clear()
            e.countCall("charge")
            e.health(HealthCheck.InitBeforeUse(false))
            clock.advanceHours(7)
            e.flush()
            assertTrue(transport.bodies.isEmpty())
            assertFalse(File(dir, "queue.jsonl").exists() && File(dir, "queue.jsonl").readText().isNotBlank())
            assertNull(platform.prefs.get(Engine.PREF_COUNTERS))
        }

        @Test
        fun `host opt-out is remembered app-wide and applies to the next start`() {
            val global = MapStore()
            val e1 = engine(p = FakePlatform(dir, globalPrefs = global))
            e1.setHostEnabled(false)
            e1.stop()
            transport.bodies.clear()
            val e2 = engine(testConfig(sdkName = "other-sdk"), FakePlatform(File(dir, "other"), globalPrefs = global))
            assertFalse(e2.isEnabled())
            e2.countCall("x")
            e2.flush()
            assertTrue(transport.bodies.isEmpty())
            e2.setHostEnabled(true)
            assertTrue(e2.isEnabled())
        }

        @Test
        fun `manifest opt-out sends nothing, ever`() {
            val e = engine(p = FakePlatform(dir, optOutInManifest = true))
            e.countCall("charge")
            e.reportError("charge", "X")
            e.flush()
            assertTrue(transport.bodies.isEmpty())
            assertFalse(e.isEnabled())
            assertFalse(File(dir, "queue.jsonl").exists())
        }

        @Test
        fun `sample rate zero disables, and the draw is remembered`() {
            val e = engine(testConfig(sampleRate = 0.0))
            assertFalse(e.isEnabled())
            assertTrue(transport.bodies.isEmpty())

            val prefs = MapStore().apply { put(Engine.PREF_SAMPLE, "0.7") }
            assertFalse(engine(testConfig(sampleRate = 0.5), FakePlatform(File(dir, "a"), prefs = prefs)).isEnabled())
            assertTrue(engine(testConfig(sampleRate = 0.8), FakePlatform(File(dir, "b"), prefs = prefs)).isEnabled())
        }
    }

    @Nested
    inner class Payload {
        @Test
        fun `every batch matches schema v1 and uses only allowed field names`() {
            val p = FakePlatform(dir, granted = emptySet(), mainThread = true)
            val e = engine(testConfig(permissions = listOf("android.permission.CAMERA")), p)
            e.countCall("charge")
            e.countCall("Client.tokenize")
            e.countCall("bad name with spaces / and ünicode")
            e.reportError("charge", "card-declined config", RuntimeException("x"))
            e.reportError("", "", null)
            e.health(HealthCheck.InitBeforeUse(false))
            e.health(HealthCheck.Custom("Webhook Reachable!", ok = false, detail = HealthDetail.OTHER))
            e.health(HealthCheck.Custom("ok-check", ok = true))
            e.health(HealthCheck.WorkerThread("charge", ok = false))
            e.markDeprecated("chargeLegacy")
            e.markDeprecated("chargeLegacy")
            e.flush()

            assertTrue(transport.batches().size >= 2)
            val kinds = transport.kinds().toSet()
            assertEquals(setOf("init", "heartbeat", "health", "calls", "error", "deprecated"), kinds)
            for (body in transport.bodies) {
                val errors = SchemaCheck.errors(body)
                assertTrue(errors.isEmpty(), "schema errors: $errors\n$body")
                val unknown = SchemaCheck.fieldNames(JSONObject(body)) - SchemaCheck.allowedNames
                assertTrue(unknown.isEmpty(), "fields not in schema v1: $unknown")
            }
        }

        @Test
        fun `the payload carries no device or user identifiers`() {
            val e = engine()
            e.countCall("charge")
            e.flush()
            // Field names only: values such as device.class = "phone" are legitimate.
            val names = transport.batches().flatMap { SchemaCheck.fieldNames(it) }.map { it.lowercase() }.toSet()
            for (forbidden in listOf("androidid", "advertisingid", "idfa", "imei", "mac", "serial", "userid", "email", "phonenumber", "ip", "latitude", "longitude", "model", "manufacturer", "locale", "timezone")) {
                assertFalse(forbidden in names, "payload has a $forbidden field")
            }
        }
    }
}
