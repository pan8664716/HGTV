// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONObject

object PlaybackQuality {
    val options=listOf(480,720,1080)
    fun normalize(value: Int)=value.takeIf { it in options } ?: 1080
}

/** One automatic recovery budget per episode; only an explicit user retry resets it. */
class RecoveryBudget {
    var attempts=0; private set
    val remaining get()=attempts<3
    val delayMillis get()=listOf(1000L,2500L,5000L).getOrNull(attempts)
    fun consume(): Boolean { if(!remaining) return false; attempts++; return true }
    fun reset() { attempts=0 }
}

data class ResumeTarget(val index: Int,val position: Long)
object ResumePlayback {
    /**
     * 续播目标。
     *
     * [episodeId] 是本机记录里那一集的标识，按它在 [episodes] 里的下标定位；
     * 账号侧那条历史只有一个「第几集」的序号（`vid_index`），标识可能与本机这版选集对不上
     * （平台换过 `vid`、或本机缓存的是另一版），因此给一个 [fallbackIndex]：
     * 标识找不到时按它定位，而不是一律退回第 1 集——那正是「看了 12 集又从第一集开始」的来源。
     */
    fun target(episodes: List<String>,episodeId: String?,position: Long,completed: Boolean,fallbackIndex: Int = -1): ResumeTarget {
        require(episodes.isNotEmpty())
        val index=episodes.indexOf(episodeId).let { if(it>=0) it else fallbackIndex.coerceIn(-1,episodes.lastIndex) }
        if(index<0) return ResumeTarget(0,0)
        return if(completed) ResumeTarget((index+1).coerceAtMost(episodes.lastIndex),0)
            else ResumeTarget(index,position.coerceAtLeast(0))
    }
}

/** Seen count is a high-water mark, so temporary catalog shrinkage is not a new update. */
data class FavoriteUpdate(val total: Int,val seen: Int,val checkedAt: Long) {
    val added get()=(total-seen).coerceAtLeast(0)
    fun observe(count: Int,now: Long,acknowledge: Boolean=false): FavoriteUpdate {
        require(count>0)
        if(now<checkedAt) return this
        return FavoriteUpdate(count,if(acknowledge) maxOf(seen,count) else seen,now)
    }
    fun json()=JSONObject().put("total",total).put("seen",seen).put("checkedAt",checkedAt)
    companion object {
        fun first(count: Int,now: Long): FavoriteUpdate { require(count>0); return FavoriteUpdate(count,count,now) }
        fun decode(value: JSONObject?)=runCatching {
            require(value!=null)
            val total=value.getInt("total"); val seen=value.getInt("seen"); val time=value.getLong("checkedAt")
            require(total>0 && seen>0 && time>=0)
            FavoriteUpdate(total,seen,time)
        }.getOrNull()
    }
}
