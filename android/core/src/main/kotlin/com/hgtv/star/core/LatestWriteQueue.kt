// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Serial persistence with one pending value per key; serialization belongs inside [write]. */
class LatestWriteQueue<K, V>(
    name: String,
    private val delayMillis: Long = 250,
    private val write: (Map<K, V>) -> Boolean,
    private val failed: () -> Unit = {}
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, name) }
    private val pending = linkedMapOf<K, V>()
    private var scheduled: ScheduledFuture<*>? = null
    private var closed = false
    private var retries = 0

    @Synchronized fun submit(values: Map<K, V>) {
        check(!closed) { "Persistence queue is closed" }
        pending.putAll(values)
        if (scheduled == null) schedule(delayMillis)
    }
    @Synchronized fun flush() {
        if (closed || pending.isEmpty()) return
        scheduled?.cancel(false)
        schedule(0)
    }
    private fun schedule(delay: Long) { scheduled = worker.schedule({ drain() }, delay, TimeUnit.MILLISECONDS) }
    private fun drain() {
        val batch = synchronized(this) {
            scheduled = null
            pending.toMap().also { pending.clear() }
        }
        if (batch.isEmpty()) return
        val success = runCatching { write(batch) }.getOrDefault(false)
        synchronized(this) {
            if (success) retries = 0
            else {
                // A newer queued checkpoint must win over this failed, older snapshot.
                batch.forEach { (key, value) -> if (!pending.containsKey(key)) pending[key] = value }
                retries++
                failed()
                if (!closed && retries <= 2 && scheduled == null) schedule(1000)
            }
        }
    }
    /** Nonblocking close: already queued writes finish; do not interrupt an in-flight commit. */
    @Synchronized fun close() {
        if (closed) return
        closed = true
        scheduled?.cancel(false)
        scheduled = null
        worker.execute {
            drain()
            // One bounded final retry if storage briefly rejected the last commit.
            if (synchronized(this) { pending.isNotEmpty() }) drain()
        }
        worker.shutdown()
    }
    /**
     * Drop every pending snapshot and stop the worker **without writing**.
     *
     * Used only by the local reset: after this the on-disk state is about to be deleted,
     * so flushing one last snapshot would recreate exactly the files being removed.
     */
    @Synchronized fun discard() {
        if (closed) return
        closed = true
        scheduled?.cancel(false)
        scheduled = null
        pending.clear()
        worker.shutdownNow()
    }
    /** For deterministic JVM validation; never call this from an Android main thread. */
    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean = worker.awaitTermination(timeout, unit)
}
