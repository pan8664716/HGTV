// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

object PrefetchPolicy {
    /** Wall-clock time to the end matters: long episodes must not outlive the slot TTL. */
    fun nearEnd(position: Long, duration: Long, speed: Float): Boolean {
        if (duration <= 0 || position < 0 || position >= duration || !speed.isFinite() || speed <= 0) return false
        return (duration - position) / speed <= 30_000f
    }
}
