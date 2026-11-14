// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core
import kotlin.math.roundToInt

/**
 * 一条弹幕。
 *
 * 数据来自HGTV / 番茄播放器自己的评论接口
 * `POST /novel/commentapi/comment/list/{vid}/v1/`（`comment_source=601`、`server_channel=1000`），
 * 与「原 App」播放时看到的是同一份数据：
 * 文本在 `comment.common.content.text`，时间点在 `comment.expand.offset_time`（毫秒），
 * 点赞数在 `comment.stat.digg_count`。
 */
data class Danmaku(val offsetMs: Long, val text: String, val diggCount: Int)

/**
 * 解析弹幕接口一个窗口里的 `data.data_list`。
 *
 * 每一条的结构是 `comment.common.content.text`（文本）、`comment.expand.offset_time`（毫秒）、
 * `comment.stat.digg_count`（点赞）。缺字段、空文本或时间点为负的条目直接跳过——
 * 平台上确实存在这类残缺记录，宁可少一条也不要把 0 毫秒的弹幕糊在片头。
 */
fun parseDanmakuRows(rows: org.json.JSONArray?): List<Danmaku> =
    rawDanmakuRows(rows).mapNotNull { row ->
        val comment=row.optJSONObject("comment") ?: return@mapNotNull null
        val text=comment.optJSONObject("common")?.optJSONObject("content")?.let { if(it.isNull("text")) "" else it.optString("text","") }.orEmpty().trim()
        val offset=comment.optJSONObject("expand")?.optLong("offset_time",-1L) ?: -1L
        if(text.isEmpty() || offset<0) null
        else Danmaku(offset,text,comment.optJSONObject("stat")?.optInt("digg_count",0) ?: 0)
    }

/** 一个窗口里的一行原始记录（含 `comment` 那一层）。调试表情表时用它看接口发了什么。 */
fun rawDanmakuRows(rows: org.json.JSONArray?): List<org.json.JSONObject> =
    (0 until (rows?.length() ?: 0)).mapNotNull { index -> rows?.optJSONObject(index) }

/**
 * 弹幕在播放画面上的显示方式。存进本机设置，换集与重开应用都保持。
 *
 * 顺序就是设置页 / 播放设置面板里的进度条顺序：
 * 隐藏 → 一行 → 两行 → 三行 → 四行 → 上方 → 全屏。
 *
 * `storedValue` 是**磁盘上的值**：后加的档位只能新增，已有档位的值不能改，否则用户存过的
 * 偏好会在升级后读回默认档（旧备份里没有的档位按 [fromStored] 的兜底处理）。
 */
enum class DanmakuMode(val storedValue: String, val label: String, val hint: String) {
    HIDDEN("hidden","隐藏","隐藏 · 完全不显示弹幕"),
    SINGLE("single","一行","一行 · 只在画面顶部滚动一行"),
    TWO("two","两行","两行 · 只在画面顶部滚动两行"),
    THREE("three","三行","三行 · 只在画面顶部滚动三行"),
    FOUR("four","四行","四行 · 只在画面顶部滚动四行"),
    TOP("top","上方","上方 · 只在画面上方区域滚动"),
    FULL("full","全屏","全屏 · 整幅画面从上到下都能滚");

    /**
     * 这一档下弹幕文字的外观（字号 / 透明度 / 速度）还有没有意义。
     *
     * 隐藏档整层不画、也不发请求，字号、透明度、速度改完看不到任何变化，因此设置页与播放
     * 设置面板都要把这几项收起来——留着一排调不出效果的滑动条，只会让人以为功能坏了。
     * 判定放在这里而不是各页面各写一次：两处必须同时收同时放，规则只能有一份。
     */
    val showsStyle get() = this != HIDDEN

    /** 一行到四行档的固定轨道上限；上方 / 全屏由可用高度决定。 */
    val lineLimit: Int?
        get() = when (this) {
            SINGLE -> 1
            TWO -> 2
            THREE -> 3
            FOUR -> 4
            TOP, FULL, HIDDEN -> null
        }

    /** 七档进度条上的位置。 */
    val sliderValue get() = ordinal.toFloat()

    /**
     * 下一档（快捷开关用）：全屏 → 上方 → 四行 → 三行 → 两行 → 一行 → 隐藏 → 全屏。
     *
     * 顺序不是枚举声明顺序，而是**用户最常用的方向**：先按「越来越不挡画面」排，
     * 关掉之后回到全屏（再点一下就是打开）。手机播放页底部那颗「弹幕」按钮
     * 一次点击走一格，落盘值仍然是同一份 `storedValue`，与设置页、播放设置面板完全一致。
     *
     * 放在 `:core` 而不是各端各写一遍：电视端将来若把弹幕按钮改成单击循环，
     * 走的就是这里同一套顺序，两端不会出现「电视上是全屏→隐藏、手机上是全屏→上方」。
     */
    fun next(): DanmakuMode = when (this) {
        FULL -> TOP
        TOP -> FOUR
        FOUR -> THREE
        THREE -> TWO
        TWO -> SINGLE
        SINGLE -> HIDDEN
        HIDDEN -> FULL
    }

    companion object {
        /** 首次启动、旧数据缺字段或值损坏时使用上方显示。 */
        val DEFAULT=TOP
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: DEFAULT
        fun fromSlider(value: Float): DanmakuMode =
            entries[value.roundToInt().coerceIn(0, entries.lastIndex)]
    }
}

/**
 * 弹幕的移动速度档位。
 *
 * [factor] 是「标准档速度」的倍数：弹幕画面上所有弹幕**同速**（见 [com.hgtv.star.shared.media.DanmakuView]），
 * 因此换档不会让快的追上慢的——只改变整体横穿画面的时间。
 * 慢速是给长句子留的阅读时间，快速是给弹幕特别密的视频留的通行能力。
 */
enum class DanmakuSpeed(val storedValue: String, val label: String, val factor: Float, val hint: String) {
    SLOW("slow","慢速",0.75f,"慢速 · 长句子读得完"),
    NORMAL("normal","标准",1f,"标准 · 默认速度"),
    FAST("fast","快速",1.5f,"快速 · 弹幕特别密时用");

    companion object { fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: NORMAL }
}

/**
 * 一轮「重复弹幕」聚合：同样的文字相邻两次间隔不超过
 * [DANMAKU_REPEAT_WINDOW_MS] 时，累计出现了 [count] 次。
 *
 * 同一条内容只显示一次，文案带上数量标记（`这个片段太精彩了 ×25`）；单条（没被重复过）
 * 就是原文，不加标记——每一条都挂个「×1」只会让画面更吵。
 */
data class DanmakuRepeat(val text: String,val offsets: List<Long>) {
    val count get() = offsets.size
    val firstMs get() = offsets.first()
    val lastMs get() = offsets.last()
    /**
     * 这一组的身份：同一句文字在后续窗口里还会聚合成新的一轮，因此 key 必须带上起始时间。
     *
     * **必须是构造期算好的 `val`，不能写成 getter**：`planHighlights` 每一轮循环都要拿它去查
     * `used` 集合（`pending.filter { it.key !in used }`），而一集 40 个窗口 × 每窗数百轮 ×
     * 数千组，写成 getter 就是在主线程上造几十万个短命字符串——CPU 与 GC 全落在 UI 线程，
     * 表现是「弹幕每半分钟莫名卡一下」。
     */
    val key = "$text@${offsets.first()}"
    /** 显示文案：重复过的带数量标记，没重复过的就是原文。 */
    val label get() = if(count>=2) "$text ×$count" else text
    /** 播放到 [nowMs] 为止已经出现过几条：中央那一块的数量标记跟着它往上走。 */
    fun countUpTo(nowMs: Long): Int {
        var low=0; var high=offsets.size
        while(low<high) { val mid=(low+high)/2; if(offsets[mid]<=nowMs) low=mid+1 else high=mid }
        return low
    }
    /** 够格进中央的时刻：第 [DanmakuHighlight.MIN_COUNT] 条出现的时候。 */
    val eligibleMs get() = eligibleMsAt(DanmakuHighlight.MIN_COUNT)
    /**
     * 够格进中央的时刻：第 [minCount] 条出现的时候。
     *
     * 聚合阈值可以在设置页调（2..10），因此「够格的时刻」不是一个定值：
     * 阈值 5 时，第 2 条出现只是刚刚开始聚合，要等第 5 条才算「够格上中央」。
     */
    fun eligibleMsAt(minCount: Int): Long = offsets[minOf(minCount,count)-1]
}

/**
 * 一轮聚合的空闲窗口：同一句弹幕与**上一条同内容弹幕**的间隔不超过这个值，就继续累计。
 *
 * 只有停发超过这个时间之后再次出现，才开启新的一轮。这样持续刷屏的同一句话不会在
 * 第 20 秒突然从 `×1` 重新计数；连续出现多久，计数就累计多久。
 */
const val DANMAKU_REPEAT_WINDOW_MS=20_000L

/**
 * 「相同弹幕达到几条才聚合」的取值范围：2..10（设置页那条滑动条的区间）。
 *
 * 下限是 2：一条弹幕谈不上「重复」，把它算成聚合只会给每条都挂一个 `×1`。
 * 上限 10：再往上调，屏幕上同一句话得刷十遍才合并，实际等于把聚合关掉了。
 */
val DANMAKU_MERGE_MIN_RANGE=2..10

/**
 * 把整集弹幕按内容聚合：同一句文字连续出现、相邻间隔不超过 [windowMs] 时合成一组，按时间排序。
 *
 * 纯函数，与画面无关：弹幕层拿它决定「哪些内容只画一次、数量是多少」，
 * 顺带把首尾空格这类不影响语义的格式差异抹掉（[Danmaku.text] 解析时已经 trim 过一次，
 * 这里再 trim 一次是给别的来源兜底）。
 *
 * [minCount] 是「达到几条才聚合」的阈值（设置页那条滑动条，见 [DANMAKU_MERGE_MIN_RANGE]）：
 * 一轮里同一句只出现 1..[minCount]-1 次时**不聚合**，逐条原样返回（各自成一组、不带数量标记）；
 * 达到 [minCount] 次才合成一组、带 `×N`。默认 2 = 同一句出现两次就合并。
 */
fun repeatGroups(items: List<Danmaku>,windowMs: Long=DANMAKU_REPEAT_WINDOW_MS,minCount: Int=2): List<DanmakuRepeat> {
    val threshold=minCount.coerceIn(DANMAKU_MERGE_MIN_RANGE)
    val idleWindow=windowMs.coerceAtLeast(0)
    val groups=mutableListOf<DanmakuRepeat>()
    items.sortedBy { it.offsetMs }.groupBy { it.text.trim() }.forEach { (text,list) ->
        if(text.isEmpty()) return@forEach
        var bucket=mutableListOf<Long>()
        list.forEach { item ->
            // 以「与上一条同内容弹幕的间隔」判轮次，而不是从第一条起算固定窗口。
            // 只要持续有人在刷同一句，计数就继续累加；停发超过窗口才重新开始。
            if(bucket.isNotEmpty() && item.offsetMs-bucket.last()>idleWindow) {
                groups+=bucketToGroups(text,bucket,threshold)
                bucket=mutableListOf()
            }
            bucket+=item.offsetMs
        }
        if(bucket.isNotEmpty()) groups+=bucketToGroups(text,bucket,threshold)
    }
    return groups.sortedWith(compareBy({ it.firstMs },{ it.text }))
}

/**
 * 一个窗口里攒下来的同一句：[bucket] 够 [threshold] 条就合成一组，
 * 不够就**逐条**返回（每条自成一组、数量为 1，因此不带 `×N`，也不会进中央）。
 *
 * 逐条返回而不是整桶丢掉：不到阈值只是「不合并」，这些弹幕本身还是要照常滚过去的。
 */
private fun bucketToGroups(text: String,bucket: List<Long>,threshold: Int): List<DanmakuRepeat> =
    if(bucket.size>=threshold) listOf(DanmakuRepeat(text,bucket))
    else bucket.map { DanmakuRepeat(text,listOf(it)) }

/**
 * 中央突出显示的规则参数与排序规则。
 *
 * 聚合弹幕按显示档位占用 1 / 2 / 3 个槽位；槽位满时，只有计数更大的新聚合可以替换
 * 当前计数最小的那一条。停发后按 7 秒停留时间退出，持续刷屏时计数继续累加。
 * 参数集中放在这里：弹幕层按它跑，改档位 / 改时长只动这一处。
 */
object DanmakuHighlight {
    /** 至少重复这么多次才算「重复弹幕」，才有资格进中央。 */
    const val MIN_COUNT=2
    /**
     * 中央最多同时放几条。
     *
     * 原来中央只有一格，几条被聚合的弹幕只能一条接一条地轮流上，屏幕上永远只有一句；
     * 现在一格放一条、竖着排开，上方显示放两格、全屏显示放三格，几条聚合弹幕可以同时看到。
     */
    const val MAX_SLOTS=3
    /**
     * 一条中央弹幕停留多久：**从最后一次 +1 起算**。
     *
     * 也就是说，聚合到 `×5` 的那一条正在展示时又来了一条变成 `×6`，消失时间就从这一条出现
     * 的那一刻重新开始算 7 秒；只有「数量又 +1」才会重新计时。
     */
    const val MIN_DWELL_MS=7_000L
}

/**
 * 中央突出显示的一轮：[group] 从 [startMs] 展示到 [endMs]（播放时间轴上的毫秒），占第 [slot] 格。
 *
 * [slot] 是竖排的格号（0 在最上面）：不同格里的轮次可以**同时**展示，同一格里不会重叠。
 */
data class DanmakuHighlightRound(val group: DanmakuRepeat,val startMs: Long,val endMs: Long,val slot: Int=0)

/** 排程中的当前占用槽；[endMs] 会随同一聚合后续 `+1` 往后推。 */
private data class ActiveDanmakuHighlight(
    val group: DanmakuRepeat,
    val startMs: Long,
    var endMs: Long,
    val slot: Int,
) {
    fun round()=DanmakuHighlightRound(group,startMs,endMs,slot)
}

/**
 * 排一遍中央突出显示的次序（纯函数，无副作用，可以单测）。
 *
 * 规则（需求 §4.3）：
 * * 一格一条、各格互不重叠；只显一行 / 上方 / 全屏分别使用 1 / 2 / 3 格；
 * * 槽位有空时按计数从高到低填入；槽位已满时，新聚合只要大于任意一条，
 *   就替换当前计数最小的一条；
 * * 选「到那一刻为止累计数量最多」的那一组，数量相同选更早出现的（[DanmakuRepeat.firstMs]）；
 * * 每轮停留从**最后一次 +1** 起算 7 秒（见 [roundEnd]）；
 * * 同一句文字的两轮不会同时占两格（否则屏幕上会并排出现两条一样的弹幕）；
 * * [previous] 只用于承接当前正在显示的轮次和已经播过的历史轮次；尚未开始的部分按
 *   新数据重新排，才能让后来补到的高计数聚合即时替换。
 */
fun planHighlights(
    groups: List<DanmakuRepeat>,
    previous: List<DanmakuHighlightRound> = emptyList(),
    slots: Int = 1,
    minCount: Int = DanmakuHighlight.MIN_COUNT,
    nowMs: Long = 0L,
): List<DanmakuHighlightRound> {
    val lanes=slots.coerceIn(1,DanmakuHighlight.MAX_SLOTS)
    // 阈值与聚合那一侧同一个：不到阈值的那几句根本没合并，也就不该上中央。
    val threshold=minCount.coerceIn(DANMAKU_MERGE_MIN_RANGE)
    val pending=groups.filter { it.count>=threshold }
    if(pending.isEmpty()) return emptyList()
    val byKey=pending.associateBy { it.key }
    val now=nowMs.coerceAtLeast(0L)
    val used=mutableSetOf<String>()
    val rounds=mutableListOf<DanmakuHighlightRound>()
    val active=arrayOfNulls<ActiveDanmakuHighlight>(lanes)

    // 已经结束的轮次保留下来供用户快退；当前仍在展示的轮次作为新排程的初始槽位。
    previous.forEach { old ->
        val group=byKey[old.group.key] ?: old.group
        val end=maxOf(old.endMs,roundEnd(group,old.startMs))
        if(end<=now) {
            if(group.key !in used) {
                rounds+=old.copy(group=group,endMs=end)
                used+=group.key
            }
            return@forEach
        }
        if(old.startMs>now || old.slot !in 0 until lanes || group.key in used) return@forEach
        val seeded=ActiveDanmakuHighlight(group,old.startMs,end,old.slot)
        val existing=active[old.slot]
        if(existing==null || seeded.endMs>existing.endMs ||
            (seeded.endMs==existing.endMs && seeded.group.countUpTo(now)>existing.group.countUpTo(now))) {
            active[old.slot]=seeded
        }
        used+=group.key
    }
    active.filterNotNull().forEach { used+=it.group.key }

    val pendingNow=linkedMapOf<String,DanmakuRepeat>()
    val eligibleAt=mutableMapOf<Long,MutableList<DanmakuRepeat>>()
    val eventTimes=sortedSetOf<Long>()
    eventTimes+=now
    pending.forEach { group ->
        if(group.key in used) return@forEach
        val eligible=group.eligibleMsAt(threshold)
        if(eligible<=now) pendingNow[group.key]=group
        else {
            eventTimes+=eligible
            eligibleAt.getOrPut(eligible){mutableListOf()}+=group
        }
        group.offsets.forEach { offset ->
            if(offset>=now) eventTimes+=offset
        }
    }
    active.filterNotNull().forEach { eventTimes+=it.endMs }

    fun activeTexts()=active.filterNotNull().mapTo(mutableSetOf()) { it.group.text }

    fun bestPending(atMs: Long): DanmakuRepeat? {
        val texts=activeTexts()
        return pendingNow.values
            .asSequence()
            .filter { it.lastMs+DanmakuHighlight.MIN_DWELL_MS>atMs }
            .filter { it.text !in texts }
            .filter { it.countUpTo(atMs)>=threshold }
            .maxWithOrNull(compareBy({ it.countUpTo(atMs) },{ -it.firstMs }))
    }

    eventTimes.forEach { atMs ->
        // 同一聚合在这条时间点又 +1：先延长结束时刻，再做替换判断。
        active.forEach { slot ->
            val round=slot ?: return@forEach
            if(round.group.offsets.binarySearch(atMs)>=0) {
                round.endMs=maxOf(round.endMs,atMs+DanmakuHighlight.MIN_DWELL_MS)
            }
        }
        // 已到结束时刻的槽位先释放。
        active.indices.forEach { index ->
            val round=active[index] ?: return@forEach
            if(round.endMs<=atMs) {
                rounds+=round.round()
                active[index]=null
            }
        }
        eligibleAt[atMs]?.forEach { group ->
            if(group.key !in used) pendingNow[group.key]=group
        }
        pendingNow.entries.removeAll { (_,group) ->
            group.lastMs+DanmakuHighlight.MIN_DWELL_MS<=atMs
        }

        // 先填满空槽；同一句已有聚合在显示时，不重复占第二个槽。
        while(true) {
            val free=active.indexOfFirst { it==null }
            if(free<0) break
            val best=bestPending(atMs) ?: break
            active[free]=ActiveDanmakuHighlight(
                best,
                atMs,
                roundEnd(best,atMs),
                free,
            )
            pendingNow.remove(best.key)
            used+=best.key
        }

        // 槽位已满时，新聚合只替换当前计数最小的一条；继续循环直到没有更大的候选。
        while(true) {
            val candidate=bestPending(atMs) ?: break
            val weakest=active.indices
                .filter { active[it]!=null }
                .minByOrNull { active[it]!!.group.countUpTo(atMs) }
                ?: break
            val current=active[weakest] ?: break
            if(candidate.countUpTo(atMs)<=current.group.countUpTo(atMs)) break
            rounds+=current.round().copy(endMs=atMs)
            active[weakest]=ActiveDanmakuHighlight(
                candidate,
                atMs,
                roundEnd(candidate,atMs),
                weakest,
            )
            pendingNow.remove(candidate.key)
            used+=candidate.key
        }
    }
    active.filterNotNull().forEach { rounds+=it.round() }
    return rounds.sortedWith(compareBy({ it.startMs },{ it.slot }))
}

/**
 * 一轮的结束时刻：**最后一次 +1 之后再过 [DanmakuHighlight.MIN_DWELL_MS]**。
 *
 * 每来一条新的重复，消失时间就从这一条重新算起，因此这个公式等价于「逐条重新计时」的结果：
 * 展示期间数量从 ×5 涨到 ×6，结束时刻就变成第 6 条的时刻 + 停留；没人再发就停在原地等它走完。
 * 持续刷屏时，新的一条会不断把结束时刻往后推；只有停发超过聚合空闲窗口后，
 * 这一轮才会在最后一条的 [DanmakuHighlight.MIN_DWELL_MS] 之后结束。
 *
 * [startMs] 是这一轮开始展示的时刻：数据里最后一条可能比它还早（补数据时重算），
 * 这时按「开始 + 停留」算，保证一轮至少露满 [DanmakuHighlight.MIN_DWELL_MS]。
 */
private fun roundEnd(group: DanmakuRepeat,startMs: Long): Long {
    val floor=startMs+DanmakuHighlight.MIN_DWELL_MS
    val restarted=group.lastMs+DanmakuHighlight.MIN_DWELL_MS
    return maxOf(floor,restarted)
}

/**
 * 弹幕文字的外观偏好：字号缩放与不透明度。
 *
 * 这两项是**连续量**（播放器设置面板里用滑动条调），不是离散档位，因此取值一律经过
 * [scale] / [alpha] 归一：先吸附到 0.1 的整数倍，再夹进上下限，最后抹掉浮点误差。
 * 归一放在 core 里，是为了让「面板上滑到 130%」和「从备份里读回 130%」得到的是同一个数，
 * 不会出现 1.3000001 这种值在面板上显示 130%、写盘却和 130% 不相等的情况。
 */
object DanmakuStyle {
    const val SCALE_MIN=0.6f
    const val SCALE_MAX=2f
    const val SCALE_STEP=0.1f
    const val ALPHA_MIN=0.2f
    const val ALPHA_MAX=1f
    const val ALPHA_STEP=0.1f

    fun scale(value: Float)=snap(value,SCALE_MIN,SCALE_MAX,SCALE_STEP)
    fun alpha(value: Float)=snap(value,ALPHA_MIN,ALPHA_MAX,ALPHA_STEP)

    /** 面板上的读数：`0.9f → "90%"`。字号与透明度共用同一套百分比显示。 */
    fun percent(value: Float)="${Math.round(value*100)}%"

    private fun snap(value: Float,min: Float,max: Float,step: Float): Float {
        // 非法值（NaN / 无穷）落回上限：读坏数据时宁可回到「100%」这一档。
        if(!value.isFinite()) return max
        val snapped=Math.round((value-min)/step)*step+min
        return (Math.round(snapped*100f)/100f).coerceIn(min,max)
    }
}
