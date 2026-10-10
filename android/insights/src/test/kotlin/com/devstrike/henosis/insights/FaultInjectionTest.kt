package com.devstrike.henosis.insights

import com.devstrike.henosis.insights.internal.Clock
import com.devstrike.henosis.insights.internal.DirectWorker
import com.devstrike.henosis.insights.internal.ExecutorWorker
import com.devstrike.henosis.insights.internal.FakeClock
import com.devstrike.henosis.insights.internal.FakePlatform
import com.devstrike.henosis.insights.internal.FakeTransport
import com.devstrike.henosis.insights.internal.KeyValueStore
import com.devstrike.henosis.insights.internal.Platform
import com.devstrike.henosis.insights.internal.SendResult
import com.devstrike.henosis.insights.internal.T0
import com.devstrike.henosis.insights.internal.Transport
import com.devstrike.henosis.insights.internal.Worker
import com.devstrike.henosis.insights.internal.testConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * CLAUDE.md rule 6: an Insights failure must never throw into the vendor SDK or the app.
 * Each test breaks one dependency and drives the whole public API through it.
 */
class FaultInjectionTest {
    @TempDir lateinit var dir: File

    @AfterEach
    fun reset() = HenosisInsights.resetForTests()

    private class Boom : RuntimeException("injected")

    /** Every method throws. */
    private class ExplodingPlatform : Platform {
        override val appId: String get() = throw Boom()
        override val appVersion: String? get() = throw Boom()
        override val osVersion: String? get() = throw Boom()
        override val deviceClass: String? get() = throw Boom()
        override fun isMainThread(): Boolean = throw Boom()
        override fun isOnWifi(): Boolean? = throw Boom()
        override fun hasPermission(permission: String): Boolean = throw Boom()
        override fun manifestOptOut(): Boolean = throw Boom()
        override fun storageDir(): File = throw Boom()
        override val prefs: KeyValueStore get() = throw Boom()
        override val globalPrefs: KeyValueStore get() = throw Boom()
        override fun log(message: String, throwable: Throwable?): Unit = throw Boom()
    }

    private class ExplodingStore : KeyValueStore {
        override fun get(key: String): String? = throw Boom()
        override fun put(key: String, value: String?): Unit = throw Boom()
    }

    private class RejectingWorker : Worker {
        override fun post(task: Runnable): Unit = throw RejectedExecutionException("full")
        override fun shutdown(): Unit = throw Boom()
        override fun awaitIdle(timeoutMs: Long): Boolean = true
    }

    /** Drives every public entry point, statically and through the handle, with hostile inputs. */
    private fun exercise(client: InsightsClient) {
        val nasty = listOf("", " ", "x".repeat(10_000), "ünïcödé\n\u0000", "charge")
        for (s in nasty) {
            client.countCall(s)
            client.markDeprecated(s)
            client.checkWorkerThread(s)
            client.reportError(s, s, RuntimeException(s))
            client.reportError(s, s, null)
            client.check(HealthCheck.Custom(s, ok = false))
            client.check(HealthCheck.PermissionGranted(s, ok = false))
            HenosisInsights.countCall(s)
            HenosisInsights.markDeprecated(s)
            HenosisInsights.checkWorkerThread(s)
            HenosisInsights.reportError(s, s, StackOverflowError())
            HenosisInsights.check(HealthCheck.InitBeforeUse(false))
        }
        client.flush()
        client.isEnabled()
        HenosisInsights.flush()
        HenosisInsights.isEnabled()
        HenosisInsights.setEnabled(false)
        HenosisInsights.setEnabled(true)
    }

    private fun start(
        platform: Platform = FakePlatform(dir),
        transport: Transport = FakeTransport(),
        worker: Worker = DirectWorker(),
        clock: Clock = FakeClock(T0),
    ): InsightsClient = HenosisInsights.start(platform, testConfig(), transport, worker, clock)

    @Test
    fun `calls before start and after stop are no-ops`() {
        exercise(InsightsClient.NOOP)
        HenosisInsights.stop()
        exercise(InsightsClient.NOOP)
    }

    @Test
    fun `a platform that throws everywhere`() {
        val client = start(platform = ExplodingPlatform())
        exercise(client)
        HenosisInsights.stop()
    }

    @Test
    fun `preferences that throw`() {
        val client = start(platform = FakePlatform(dir, prefs = com.devstrike.henosis.insights.internal.MapStore()).let { p ->
            object : Platform by p {
                override val prefs: KeyValueStore = ExplodingStore()
                override val globalPrefs: KeyValueStore = ExplodingStore()
            }
        })
        exercise(client)
    }

    @Test
    fun `a transport that throws, including Errors`() {
        val transport = FakeTransport(failure = Boom())
        exercise(start(transport = transport))
        transport.failure = OutOfMemoryError("injected")
        exercise(HenosisInsights.start(FakePlatform(File(dir, "b")), testConfig(sdkName = "other"), transport, DirectWorker(), FakeClock(T0)))
    }

    @Test
    fun `a worker that rejects every task`() {
        exercise(start(worker = RejectingWorker()))
        HenosisInsights.stop()
    }

    @Test
    fun `a storage directory that is not writable`() {
        val blocker = File(dir, "blocked").apply { writeText("I am a file, not a directory") }
        val transport = FakeTransport(result = SendResult.Retry(null))
        val client = start(platform = FakePlatform(blocker), transport = transport)
        exercise(client)
        // Still works from memory.
        transport.result = SendResult.Accepted
        client.flush()
    }

    @Test
    fun `the main thread only enqueues, the transport runs on the worker`() {
        val callerThread = Thread.currentThread()
        val sendThreads = mutableListOf<Thread>()
        val blockSend = CountDownLatch(1)
        val transport = object : Transport {
            override fun send(endpoint: String, writeKey: String, body: ByteArray): SendResult {
                sendThreads += Thread.currentThread()
                blockSend.await(5, TimeUnit.SECONDS)
                return SendResult.Accepted
            }
        }
        val worker = ExecutorWorker()
        val client = start(transport = transport, worker = worker)
        val started = System.nanoTime()
        repeat(1000) { client.countCall("charge") }
        client.flush()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 1_000, "caller blocked for $elapsedMs ms while the upload was stuck")
        blockSend.countDown()
        assertTrue(worker.awaitIdle())
        assertTrue(sendThreads.isNotEmpty())
        assertTrue(sendThreads.none { it === callerThread })
    }

    @Test
    fun `concurrent callers lose no counts`() {
        val transport = FakeTransport()
        val worker = ExecutorWorker()
        val client = start(transport = transport, worker = worker)
        val threads = (1..16).map { Thread { repeat(1_000) { client.countCall("charge") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        client.flush()
        assertTrue(worker.awaitIdle())
        val total = transport.events().filter { it.getString("kind") == "calls" }.sumOf { it.getJSONObject("counts").getInt("charge") }
        assertEquals(16_000, total)
        assertFalse(transport.bodies.isEmpty())
    }
}
