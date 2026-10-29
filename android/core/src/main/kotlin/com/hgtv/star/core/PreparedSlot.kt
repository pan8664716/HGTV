// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.Closeable

/**
 * One cancellable speculative result. Claim transfers ownership; stale completions close themselves.
 *
 * [ttl] 默认 2 分钟，**比 [PlaybackCache.PREPARED_FRESH_MS]（10 分钟）更严格**，这是有意的：
 * 这个槽位装的是 `RemoteVideo`（已打开的数据源 + 512KB 首块），它只服务于「片尾 30 秒
 * 预热 → 自动推进」这一条节奏，2 分钟足够；放久了既占内存，又可能让一份旧地址绕过
 * 队列的年龄校验直接进播放器。
 *
 * 10 分钟是**上限**，不是目标值：任何一条持有地址的路径都不得超过它。
 */
class PreparedSlot<T: Closeable>(private val clock: ()->Long,private val ttl: Long=120_000) {
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
    @Synchronized fun clear() { revision++; value?.close(); value=null; key="" }
}
