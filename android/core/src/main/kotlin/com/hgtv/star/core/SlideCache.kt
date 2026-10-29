// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 缓存分级（文档第 3 节）。
 *
 * 顺序即包含关系：`FULL` 已经含 `META`，`META` 已经含 `ADDRESS`。
 */
enum class CacheLevel { NONE, ADDRESS, META, FULL }

/**
 * 双向滑动的窗口与预加载决策——**纯逻辑，不碰 Android、不发请求**。
 *
 * ## 它解决什么
 *
 * 推荐页原来只预热「正在走的方向两步 → 反方向一步」，三个不确定性都没处理：
 *
 * * **方向**：下滑回看时窗口里没有货，回看就是冷启动；
 * * **速度**：一秒划三部时每部都去拉 1MB，带宽互相抢，正在放的那一部反而卡；
 * * **停留**：滑过去又滑回来，那一部恰恰是用户最想看的，却已经被淘汰。
 *
 * 这里把三者拆成可测的规则：
 *
 * | 输入 | 输出 |
 * |---|---|
 * | 换了一部（下标 / 方向 / 时间） | 该预取哪些下标、各自到哪一级 |
 * | 停留满 1 秒 | 把主方向那一步升到 [CacheLevel.FULL] |
 * | 3 秒内滑回来 | 视为关注：主方向两步都升 [CacheLevel.FULL] |
 * | 连续 3 次 <500ms 换一部 | 进入 [Mode.FLING]：只留地址预取 |
 *
 * ## 非对称
 *
 * 信息流主方向是「往上滑看新的」，因此向前 3 条、向后 2 条。方向可以翻：
 * 用户正在往回滑时，[plan] 会把窗口镜像过来（回看方向变成主方向），
 * 这样「按了两下返回键」也不会落在没有预热的那一条上。
 *
 * ## 为什么不做 ML
 *
 * 这台机器（S905L3B / 2G）上，一条 `HashMap` 读写都是真金白银；规则版只有几次整数比较。
 */
class SlideCacheManager(
    /** 主方向窗口（含 L1）。 */
    private val forwardWindow: Int = 3,
    /** 反方向窗口。 */
    private val backwardWindow: Int = 2,
    /** 主方向前几步做完整预热（地址 + 索引 + 首块）。 */
    private val l1Depth: Int = 1,
    /** 主方向前几步做元数据预热（地址 + 索引，不取首块）。 */
    private val l2Depth: Int = 2,
    /** 两次换剧间隔小于它就记一次「快滑」。 */
    private val flingPerItemMs: Long = 500L,
    /** 连续快滑几次进入 [Mode.FLING]。 */
    private val flingStreak: Int = 3,
    /** 停留超过它才算「慢滑 / 停留」。 */
    private val slowSlideMs: Long = 1_000L,
    /** 滑回来多快算「回看」（升 L1）。 */
    private val revisitMs: Long = 3_000L,
    /** 停留多久算「确认」（升 L1）。 */
    private val dwellPromoteMs: Long = 1_000L,
    /** 首帧之后延迟多久开始预热。 */
    private val firstFrameDelayMs: Long = 2_000L,
) {
    data class Target(val index: Int, val level: CacheLevel)
    enum class Mode { NORMAL, FLING }
    enum class Reason { WINDOW, DWELL, REVISIT, FLING, TRIM }
    data class Plan(val anchor: Int, val mode: Mode, val reason: Reason, val targets: List<Target>)

    /** 最近 5 次滑动方向（+1 / -1）。 */
    private val directions=ArrayDeque<Int>()
    private var lastChangeAt=0L
    private var fastStreak=0
    private var dir=1
    private var trimmed=false
    /** 每一条最后一次上屏的时刻（判「滑回来」）。 */
    private val lastSeen=HashMap<Int,Long>()
    /** 每一条停留了多久（淘汰评分用）。 */
    private val dwell=HashMap<Int,Long>()

    val mode: Mode get()=if(fastStreak>=flingStreak) Mode.FLING else Mode.NORMAL
    /** 当前主方向：+1 = 往列表后面走，-1 = 回看。 */
    val direction: Int get()=dir

    /**
     * 换到 [index] 了（[direction] 为 0 表示「同一部重试 / 首次进入」，不算滑动）。
     *
     * 这是**唯一的入口**：速度、方向、回看三个判定都在这里更新，
     * 返回的 [Plan] 直接就是这一次要做的预加载清单。
     */
    fun onItemChanged(index: Int, direction: Int, now: Long): Plan {
        val gap=if(lastChangeAt==0L) Long.MAX_VALUE else now-lastChangeAt
        lastChangeAt=now
        if(direction!=0) {
            dir=if(direction>0) 1 else -1
            if(directions.size>=5) directions.removeFirst()
            directions.addLast(dir)
            if(gap<flingPerItemMs) fastStreak++ else fastStreak=0
        }
        val previous=lastSeen.put(index,now)
        val revisit=previous!=null && now-previous<=revisitMs
        val reason=when {
            mode==Mode.FLING -> Reason.FLING
            revisit -> Reason.REVISIT
            else -> Reason.WINDOW
        }
        return plan(index,reason)
    }

    /**
     * 在当前这一部上停留满了（≥[dwellPromoteMs]）：主方向那一步升到完整预热。
     *
     * 只在 [Mode.FLING] 之后有意义——正常模式下主方向第一步本来就是 FULL，
     * 这一步只是把「刚才被降级掉的那一条」补回来。
     */
    fun onDwell(index: Int, now: Long): Plan {
        val previous=lastSeen[index]
        if(previous!=null) dwell[index]=(now-previous).coerceAtLeast(dwell[index] ?: 0L)
        if(mode==Mode.FLING && now-lastChangeAt>=dwellPromoteMs) fastStreak=0
        return plan(index,Reason.DWELL)
    }

    /** 首帧上屏：返回「等多久再预热」。 */
    fun firstFrameDelay(): Long = firstFrameDelayMs

    /** 内存吃紧：只保留主方向第一步，其余全部不做。 */
    fun onTrimMemory(severe: Boolean) {
        trimmed=true
        if(severe) fastStreak=0
    }

    /** 内存恢复（`onTrimMemory` 之后下一次正常换剧时调）。 */
    fun resetTrim() { trimmed=false }

    /**
     * 按当前锚点算预加载清单。
     *
     * 顺序即优先级：主方向由近到远，再是反方向由近到远。
     */
    fun plan(anchor: Int, reason: Reason = Reason.WINDOW): Plan {
        if(trimmed) return Plan(anchor, mode, Reason.TRIM, listOf(Target(anchor+dir, CacheLevel.FULL)))
        val step=if(dir>=0) 1 else -1
        val targets=ArrayList<Target>(forwardWindow+backwardWindow)
        // 方向序列预测：连续同向 ≥3 次，主方向窗口再放远一条（只到 ADDRESS，成本 20KB）。
        val extend=if(sameDirectionStreak()>=3) 1 else 0
        val mainWindow=forwardWindow+extend
        for(k in 1..mainWindow) {
            val level=when {
                mode==Mode.FLING -> CacheLevel.ADDRESS
                k<=l1Depth -> CacheLevel.FULL
                // 滑回来的那一部：主方向第二步也升到完整预热（文档 4.2 的 promoteToL1）。
                k<=l2Depth && reason==Reason.REVISIT -> CacheLevel.FULL
                k<=l2Depth -> CacheLevel.META
                else -> CacheLevel.ADDRESS
            }
            targets+=Target(anchor+k*step,level)
        }
        for(k in 1..backwardWindow) {
            val level=when {
                mode==Mode.FLING -> CacheLevel.ADDRESS
                // 回看：用户刚滑回来，反方向那一条就是「下一步很可能去的地方」。
                reason==Reason.REVISIT && k==1 -> CacheLevel.FULL
                k==1 -> CacheLevel.META
                else -> CacheLevel.ADDRESS
            }
            targets+=Target(anchor-k*step,level)
        }
        return Plan(anchor,mode,reason,targets)
    }

    private fun sameDirectionStreak(): Int {
        if(directions.size<3) return 0
        val last=directions.last()
        var streak=0
        for(i in directions.indices.reversed()) {
            if(directions[i]!=last) break
            streak++
        }
        return streak
    }

    /** 某一条的累计停留时长（淘汰评分用；没停留过就是 0）。 */
    fun dwellOf(index: Int): Long = dwell[index] ?: 0L

    /** 淘汰评分用的一条缓存。 */
    data class Entry(val key: String,val index: Int,val lastAccessAt: Long,val dwellMs: Long,val directionSeen: Int)

    /**
     * 方向偏置 LRU：返回**该先淘汰的 key 顺序**（升序 = 最先淘汰）。
     *
     * ```
     * score = lastAccessAt
     *       + directionBias     前进方向 +30s，回看方向 -10s
     *       + dwellBonus        停留 ≥2s 的 +60s
     *       - distancePenalty   距当前位置每远一条 -20s
     * ```
     *
     * **窗口内（`anchor-backwardWindow .. anchor+forwardWindow`）永远不淘汰**：
     * 那些正是用户下一步最可能去的地方，淘汰它们等于把预加载白做。
     */
    fun evictionOrder(entries: List<Entry>, anchor: Int, now: Long): List<String> {
        val step=if(dir>=0) 1 else -1
        fun inWindow(index: Int): Boolean {
            val offset=(index-anchor)*step
            return offset in -backwardWindow..forwardWindow
        }
        return entries.filterNot { inWindow(it.index) }
            .sortedBy { score(it,anchor,now) }
            .map { it.key }
    }

    private fun score(entry: Entry,anchor: Int,now: Long): Long {
        val step=if(dir>=0) 1 else -1
        val forward=(entry.index-anchor)*step>0 || entry.directionSeen==step
        val bias=if(forward) 30_000L else -10_000L
        val bonus=if(entry.dwellMs>=2_000L) 60_000L else 0L
        val distance=20_000L*Math.abs(entry.index-anchor).toLong()
        return entry.lastAccessAt+bias+bonus-distance
    }
}
