// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.Closeable

/**
 * One cancellable speculative result. Claim transfers ownership; stale completions close themselves.
 *
 * [ttl] 默认 2 分钟：
 * 这个槽位装的是 `RemoteVideo`（已打开的数据源 + 约 2.5MB 首块），它只服务于「片尾 30 秒
 * 预热 → 自动推进」这一条节奏，2 分钟足够；放久了只会白占内存。
 */
class PreparedSlot<T: Closeable>(
    private val clock: ()->Long,
    private val ttl: Long=AppConfig.Playback.PREPARED_SLOT_TTL_MS,
) {
    private var revision=0L
    private var key=""
    private var readyAt=0L
    private var value: T?=null
    @Synchronized fun begin(next: String): Long { clear(); key=next; return revision }
    @Synchronized fun complete(ticket: Long,next: String,result: T): Boolean {
        if(ticket!=revision || key!=next || value!=null) { result.close(); return false }
        value=result; readyAt=clock(); return true
    }
    @Synchronized fun take(next: String): T? {
        val result=value?.takeIf { key==next && clock()-readyAt in 0..ttl }
        if(result!=null) value=null
        clear()
        return result
    }
    @Synchronized fun isFresh(next: String): Boolean = value!=null && key==next && clock()-readyAt in 0..ttl

    /**
     * 这一份预热**取到地址**的时刻；没有 / 超过内存 TTL / 键不符时返回 0。
     * 该时刻只用于诊断与资源生命周期，不参与 CDN 地址失效判断。
     *
     * 必须在 [take] **之前**调：`take` 会清空槽位，之后就读不到了。
     */
    @Synchronized fun readyAtOf(next: String): Long =
        if(value!=null && key==next && clock()-readyAt in 0..ttl) readyAt else 0L

    @Synchronized fun clear() { revision++; value?.close(); value=null; key="" }
}
