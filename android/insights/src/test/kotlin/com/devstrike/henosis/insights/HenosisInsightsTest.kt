package com.devstrike.henosis.insights

import com.devstrike.henosis.insights.internal.DirectWorker
import com.devstrike.henosis.insights.internal.FakeClock
import com.devstrike.henosis.insights.internal.FakePlatform
import com.devstrike.henosis.insights.internal.FakeTransport
import com.devstrike.henosis.insights.internal.MapStore
import com.devstrike.henosis.insights.internal.T0
import com.devstrike.henosis.insights.internal.testConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HenosisInsightsTest {
    @TempDir lateinit var dir: File

    @AfterEach
    fun reset() = HenosisInsights.resetForTests()

    private fun start(sdk: String, transport: FakeTransport, global: MapStore = MapStore()) =
        HenosisInsights.start(
            FakePlatform(File(dir, sdk), globalPrefs = global),
            testConfig(sdkName = sdk),
            transport,
            DirectWorker(),
            FakeClock(T0),
        )

    private fun FakeTransport.callCounts(): Int =
        events().filter { it.getString("kind") == "calls" }.sumOf { it.getJSONObject("counts").getInt("charge") }

    @Test
    fun `starting twice for the same SDK returns the same handle`() {
        val t = FakeTransport()
        val a = start("acme-payments", t)
        val b = start("acme-payments", t)
        assertSame(a, b)
        assertEquals(1, HenosisInsights.runningCount())
    }

    @Test
    fun `static calls reach the only running SDK`() {
        val t = FakeTransport()
        start("acme-payments", t)
        HenosisInsights.countCall("charge")
        HenosisInsights.flush()
        assertEquals(1, t.callCounts())
        assertTrue(HenosisInsights.isEnabled())
    }

    @Test
    fun `with two SDKs, static calls are dropped and handles keep streams separate`() {
        val ta = FakeTransport()
        val tb = FakeTransport()
        val a = start("acme-payments", ta)
        val b = start("acme-kyc", tb)
        assertNotSame(a, b)
        HenosisInsights.countCall("charge")
        HenosisInsights.flush()
        assertEquals(0, ta.callCounts() + tb.callCounts(), "static call was misattributed")
        a.countCall("charge")
        b.countCall("charge")
        b.countCall("charge")
        a.flush()
        b.flush()
        assertEquals(1, ta.callCounts())
        assertEquals(2, tb.callCounts())
        assertTrue(ta.batches().all { it.getJSONObject("sdk").getString("name") == "acme-payments" })
        assertTrue(tb.batches().all { it.getJSONObject("sdk").getString("name") == "acme-kyc" })
        assertFalse(HenosisInsights.isEnabled(), "ambiguous with two SDKs")
    }

    @Test
    fun `host opt-out before any SDK starts is honoured`() {
        HenosisInsights.setEnabled(false)
        val t = FakeTransport()
        val client = start("acme-payments", t)
        client.countCall("charge")
        client.flush()
        assertFalse(client.isEnabled())
        assertTrue(t.bodies.isEmpty())
    }

    @Test
    fun `host opt-out applies to every running SDK`() {
        val global = MapStore()
        val ta = FakeTransport()
        val tb = FakeTransport()
        val a = start("acme-payments", ta, global)
        val b = start("acme-kyc", tb, global)
        ta.bodies.clear()
        tb.bodies.clear()
        HenosisInsights.setEnabled(false)
        a.countCall("charge")
        b.countCall("charge")
        a.flush()
        b.flush()
        assertTrue(ta.bodies.isEmpty() && tb.bodies.isEmpty())
        assertFalse(a.isEnabled() || b.isEnabled())
    }
}
