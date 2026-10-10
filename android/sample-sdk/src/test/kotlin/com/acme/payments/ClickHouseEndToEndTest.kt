package com.acme.payments

import com.devstrike.henosis.insights.HenosisInsights
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * M2 acceptance: the sample SDK's events land in ClickHouse.
 *
 * Needs a running ingest backed by Postgres + ClickHouse (see the cloud repo README) and:
 *   INSIGHTS_E2E_ENDPOINT          e.g. http://127.0.0.1:8787/v1/batches
 *   INSIGHTS_E2E_WRITE_KEY         a key from `pnpm db:seed`
 *   INSIGHTS_E2E_CLICKHOUSE_URL    e.g. http://localhost:8123
 *   INSIGHTS_E2E_CLICKHOUSE_PASSWORD, INSIGHTS_E2E_CLICKHOUSE_DATABASE (default "insights")
 */
@ExtendWith(RobolectricExtension::class)
@EnabledIfEnvironmentVariable(named = "INSIGHTS_E2E_ENDPOINT", matches = ".+")
class ClickHouseEndToEndTest {
    private val env = System.getenv()
    private val chUrl = env["INSIGHTS_E2E_CLICKHOUSE_URL"] ?: "http://localhost:8123"
    private val chPassword = env["INSIGHTS_E2E_CLICKHOUSE_PASSWORD"] ?: ""
    private val chDatabase = env["INSIGHTS_E2E_CLICKHOUSE_DATABASE"] ?: "insights"

    @AfterEach
    fun stop() = HenosisInsights.stop()

    @Test
    fun `sample SDK events land in ClickHouse`() {
        val app = RuntimeEnvironment.getApplication()
        val apiKey = "sk_test_e2e_${UUID.randomUUID()}"
        val customerKey = sha256(apiKey)

        AcmePayments.init(app, apiKey, insightsWriteKey = env.getValue("INSIGHTS_E2E_WRITE_KEY"), insightsEndpoint = env.getValue("INSIGHTS_E2E_ENDPOINT"))
        AcmePayments.charge(1_500, "NGN") // on the main thread: a wrong-thread health event
        AcmePayments.charge(-1, "NGN") // declined: an SDK error with SDK-only frames
        @Suppress("DEPRECATION")
        AcmePayments.chargeLegacy(100)
        AcmePayments.flushTelemetry()

        val kinds = poll(timeoutMs = 30_000) {
            query("SELECT kind, count() FROM events WHERE customer_key = '$customerKey' GROUP BY kind ORDER BY kind")
                .associate { it[0] to it[1].toInt() }
                .takeIf { it.keys.containsAll(listOf("init", "heartbeat", "calls", "error", "deprecated", "health")) }
        }
        assertEquals(1, kinds["init"])
        assertEquals(1, kinds["heartbeat"])
        assertEquals(1, kinds["error"])

        val envelope = query(
            "SELECT DISTINCT sdk_name, sdk_version, wrapper_kind, client_lang, client_mode, device_os, app_id FROM events WHERE customer_key = '$customerKey'",
        )
        assertEquals(listOf(listOf("acme-payments", "4.2.0", "native", "kotlin", "native", "android", app.packageName)), envelope)

        val calls = query("SELECT sum(counts['charge']) FROM events WHERE customer_key = '$customerKey' AND kind = 'calls'")
        assertEquals("3", calls.single().single(), "two charge() calls plus one via chargeLegacy()")

        val frames = query("SELECT arrayJoin(frames).cls FROM events WHERE customer_key = '$customerKey' AND kind = 'error'").map { it.single() }
        assertTrue(frames.isNotEmpty() && frames.all { it.startsWith("com.acme.payments.") }, frames.toString())

        val health = query("SELECT check_name, detail FROM events WHERE customer_key = '$customerKey' AND kind = 'health' ORDER BY check_name")
        assertTrue(listOf("main_thread_charge", "wrong_thread") in health, health.toString())
    }

    private fun query(sql: String): List<List<String>> {
        val url = URL("$chUrl/?database=${URLEncoder.encode(chDatabase, "UTF-8")}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("default:$chPassword".toByteArray()))
        conn.outputStream.use { it.write("$sql FORMAT TSV".toByteArray()) }
        val code = conn.responseCode
        val text = (if (code >= 400) conn.errorStream else conn.inputStream).bufferedReader().readText()
        check(code == 200) { "ClickHouse $code: $text" }
        return text.lines().filter { it.isNotEmpty() }.map { it.split('\t') }
    }

    private fun <T : Any> poll(timeoutMs: Long, block: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                block()?.let { return it }
            } catch (t: Throwable) {
                last = t
            }
            Thread.sleep(250)
        }
        throw AssertionError("events did not land in ClickHouse within $timeoutMs ms", last)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
