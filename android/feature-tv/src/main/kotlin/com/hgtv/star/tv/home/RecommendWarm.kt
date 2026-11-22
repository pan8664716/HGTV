// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.PreparedSlot
import com.hgtv.star.core.RemoteVideo

/**
 * 一次在途的「相邻一部预热」。
 *
 * 带自己的取消域 [scope]：取消的时候不只是丢弃结果，连它正在拉的那 1MB 也一起掐掉。
 * 手机上一划一部，被作废的预热很多；只丢结果的话这些连接还会把带宽占满，
 * 正在放的那一部反而更卡。
 */
class RecommendWarm {
    @Volatile internal var future: java.util.concurrent.Future<*>?=null
    val scope=com.hgtv.star.core.RequestScope()
    val startedAt=android.os.SystemClock.elapsedRealtime()
    /** 这一任务顺带准备的完整片源键；仅地址预热时为空。 */
    var preparedKey=""
}

/**
 * 推荐页主方向最近一部的完整片源槽。
 *
 * 只保留一份 `RemoteVideo`，不创建相邻播放器、解码器或 Surface。所有权规则与
 * [PreparedSlot] 一致：完成后可由起播线程取走；方向变化、离开推荐页、内存吃紧或
 * 新目标覆盖时关闭旧值，迟到的结果也会自行关闭。
 */
class RecommendPreparedSource(
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val slot=PreparedSlot<RemoteVideo>(clock)
    private var activeWarm: RecommendWarm?=null
    private var key=""

    @Synchronized fun begin(warm: RecommendWarm,key: String): Long {
        cancel()
        activeWarm=warm
        this.key=key
        return slot.begin(key)
    }

    @Synchronized fun complete(
        warm: RecommendWarm,
        ticket: Long,
        key: String,
        remote: RemoteVideo,
    ): Boolean {
        if(activeWarm!==warm || this.key!=key) {
            remote.close()
            return false
        }
        activeWarm=null
        val accepted=slot.complete(ticket,key,remote)
        if(!accepted) this.key=""
        return accepted
    }

    @Synchronized fun fail(warm: RecommendWarm,key: String) {
        if(activeWarm!==warm || this.key!=key) return
        activeWarm=null
        this.key=""
        slot.clear()
    }

    @Synchronized fun matches(key: String): Boolean =
        this.key==key && (activeWarm!=null || slot.isFresh(key))

    @Synchronized fun take(key: String): RemoteVideo? {
        if(this.key!=key) return null
        val remote=slot.take(key)
        // 仍在准备时保留 key，让调用方的 cancel(key) 能准确停掉在途任务；
        // 已经完成或过期时槽里不会再有值，顺手清掉元数据。
        if(remote!=null || activeWarm==null) this.key=""
        return remote
    }

    @Synchronized fun cancel(key: String) {
        if(this.key!=key) return
        cancelLocked()
    }

    @Synchronized fun cancel() {
        cancelLocked()
    }

    private fun cancelLocked() {
        val warm=activeWarm
        activeWarm=null
        key=""
        warm?.future?.cancel(true)
        warm?.scope?.cancel()
        slot.clear()
    }
}
