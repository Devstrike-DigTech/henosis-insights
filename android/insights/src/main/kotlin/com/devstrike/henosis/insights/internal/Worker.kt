package com.devstrike.henosis.insights.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * A single background thread that owns all of one engine's state. Public API calls only post to it.
 * Plain `java.util.concurrent`; no coroutines, no WorkManager, no timers or wake-ups: tasks run only
 * when the SDK is active.
 */
internal interface Worker {
    fun post(task: Runnable)

    /** Stop accepting work; already-posted tasks still run. Returns immediately. */
    fun shutdown()

    /** Block until every task posted so far has run. Tests only. */
    fun awaitIdle(timeoutMs: Long = 5_000): Boolean

    companion object {
        fun create(): Worker = ExecutorWorker()
    }
}

internal class ExecutorWorker : Worker {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor(
            ThreadFactory { r ->
                Thread(r, "henosis-insights").also {
                    it.isDaemon = true
                    it.priority = Thread.MIN_PRIORITY
                }
            },
        )

    override fun post(task: Runnable) {
        executor.execute(task)
    }

    override fun shutdown() {
        executor.shutdown()
    }

    override fun awaitIdle(timeoutMs: Long): Boolean {
        if (executor.isShutdown) return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
