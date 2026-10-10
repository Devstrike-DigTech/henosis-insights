package com.devstrike.henosis.insights.internal

import java.io.File

/**
 * Offline queue of serialised events, capped by bytes. When full, the oldest events are dropped.
 * Persisted as one JSON object per line so a partial write loses at most one event.
 * Only ever touched from the worker thread.
 */
internal class EventQueue(
    private val file: File?,
    private val capBytes: Int = DEFAULT_CAP_BYTES,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val items = ArrayDeque<String>()
    private var bytes = 0
    private var dirty = false
    var dropped: Long = 0
        private set

    val size: Int get() = items.size
    val sizeBytes: Int get() = bytes

    init {
        load()
    }

    fun offer(json: String) {
        val cost = json.length + 1
        if (cost > capBytes) {
            dropped++
            return
        }
        while (bytes + cost > capBytes && items.isNotEmpty()) {
            bytes -= items.removeFirst().length + 1
            dropped++
        }
        items.addLast(json)
        bytes += cost
        dirty = true
    }

    fun peek(max: Int): List<String> = items.take(max)

    fun remove(count: Int) {
        repeat(minOf(count, items.size)) { bytes -= items.removeFirst().length + 1 }
        dirty = true
    }

    fun clear() {
        items.clear()
        bytes = 0
        dirty = true
        persist()
    }

    /** Write to disk if anything changed since the last persist. */
    fun persist() {
        if (!dirty || file == null) return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { w -> for (item in items) { w.write(item); w.write("\n") } }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            dirty = false
        } catch (t: Throwable) {
            log("queue persist failed", t)
        }
    }

    private fun load() {
        if (file == null || !file.exists()) return
        try {
            file.forEachLine { line ->
                if (line.isNotBlank() && line.startsWith("{") && line.endsWith("}")) offer(line)
            }
            dirty = false
        } catch (t: Throwable) {
            log("queue load failed", t)
        }
    }

    companion object {
        const val DEFAULT_CAP_BYTES = 1_000_000
    }
}
