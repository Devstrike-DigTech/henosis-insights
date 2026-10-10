package com.devstrike.henosis.insights.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EventQueueTest {
    @TempDir lateinit var dir: File

    private fun ev(i: Int, pad: Int = 0) = """{"kind":"error","method":"m$i","code":"C${"X".repeat(pad)}","layer":"native","frames":[]}"""

    @Test
    fun `default cap is 1 MB and the oldest events are dropped first`() {
        val q = EventQueue(null)
        assertEquals(1_000_000, EventQueue.DEFAULT_CAP_BYTES)
        repeat(20_000) { q.offer(ev(it, pad = 40)) }
        assertTrue(q.sizeBytes <= 1_000_000, "size ${q.sizeBytes}")
        assertTrue(q.dropped > 0)
        val first = q.peek(1).single()
        val last = q.peek(q.size).last()
        assertTrue(first.contains("\"m${20_000 - q.size}\""), first)
        assertTrue(last.contains("\"m19999\""), last)
    }

    @Test
    fun `an event larger than the cap is dropped`() {
        val q = EventQueue(null, capBytes = 100)
        q.offer(ev(1, pad = 200))
        assertEquals(0, q.size)
        assertEquals(1, q.dropped)
    }

    @Test
    fun `persists and reloads, ignoring corrupt lines`() {
        val f = File(dir, "q.jsonl")
        val q = EventQueue(f)
        q.offer(ev(1))
        q.offer(ev(2))
        q.persist()
        f.appendText("{\"kind\":\"err\n") // torn write
        val reloaded = EventQueue(f)
        assertEquals(2, reloaded.size)
        reloaded.remove(1)
        reloaded.persist()
        assertEquals(1, EventQueue(f).size)
    }

    @Test
    fun `the on-disk file never exceeds the cap`() {
        val f = File(dir, "q.jsonl")
        val q = EventQueue(f, capBytes = 2_000)
        repeat(500) { q.offer(ev(it)) }
        q.persist()
        assertTrue(f.length() <= 2_000, "file is ${f.length()} bytes")
    }
}
