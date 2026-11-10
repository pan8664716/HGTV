// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.debug

import android.os.SystemClock
import android.util.Log

/**
 * Temporary cold-start timing trace.
 *
 * Every line uses [TAG] and [Log.w], so it remains visible in release builds where
 * informational logs may be stripped by R8.
 */
object StartupTrace {
    const val TAG = "HGTV-STARTUP-PROF"

    private val bootAt = SystemClock.elapsedRealtime()

    fun now(): Long = SystemClock.elapsedRealtime()

    fun mark(phase: String, detail: String = "") {
        log(phase, elapsed = -1L, detail = detail)
    }

    fun done(phase: String, startedAt: Long, detail: String = "") {
        log(phase, elapsed = (now() - startedAt).coerceAtLeast(0L), detail = detail)
    }

    fun <T> section(phase: String, block: () -> T): T {
        val startedAt = now()
        return try {
            block()
        } finally {
            done(phase, startedAt)
        }
    }

    private fun log(phase: String, elapsed: Long, detail: String) {
        val now = now()
        val took = if (elapsed >= 0L) " dt=${elapsed}ms" else ""
        val extra = detail.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()
        Log.w(
            TAG,
            "t=+${now - bootAt}ms$took thread=${Thread.currentThread().name} | $phase$extra",
        )
    }
}
