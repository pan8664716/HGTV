// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

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
internal fun parseDanmakuRows(rows: org.json.JSONArray?): List<Danmaku> =
    rawDanmakuRows(rows).mapNotNull { row ->
        val comment=row.optJSONObject("comment") ?: return@mapNotNull null
        val text=comment.optJSONObject("common")?.optJSONObject("content")?.let { if(it.isNull("text")) "" else it.optString("text","") }.orEmpty().trim()
        val offset=comment.optJSONObject("expand")?.optLong("offset_time",-1L) ?: -1L
        if(text.isEmpty() || offset<0) null
        else Danmaku(offset,text,comment.optJSONObject("stat")?.optInt("digg_count",0) ?: 0)
    }

/** 一个窗口里的一行原始记录（含 `comment` 那一层）。调试表情表时用它看接口发了什么。 */
internal fun rawDanmakuRows(rows: org.json.JSONArray?): List<org.json.JSONObject> =
    (0 until (rows?.length() ?: 0)).mapNotNull { index -> rows?.optJSONObject(index) }

/**
 * 弹幕在播放画面上的显示方式。存进本机设置，换集与重开应用都保持。
 *
 * 顺序就是设置页 / 播放设置面板里的排列顺序（`entries` 直接被两个页面当作选项表用）：
 * 只显一行 → 上方显示 → 全屏显示 → 隐藏弹幕。
 *
 * `storedValue` 是**磁盘上的值**：后加的档位只能新增，已有档位的值不能改，否则用户存过的
 * 偏好会在升级后读回默认档（旧备份里没有的档位按 [fromStored] 的兜底处理）。
 */
enum class DanmakuMode(val storedValue: String, val label: String, val hint: String) {
    /** 只显一行：整屏只有一条轨道，多密也不会上下叠字。 */
    SINGLE("single","只显一行","只显一行 · 屏幕内只滚动一行，多密也不会上下叠字"),
    TOP("top","上方显示","上方显示 · 只在画面上方区域滚动"),
    FULL("full","全屏显示","全屏显示 · 整幅画面从上到下都能滚"),
    HIDDEN("hidden","隐藏弹幕","隐藏弹幕 · 完全不显示弹幕");

    /**
     * 这一档下弹幕文字的外观（字号 / 透明度 / 速度）还有没有意义。
     *
     * 隐藏档整层不画、也不发请求，字号、透明度、速度改完看不到任何变化，因此设置页与播放
     * 设置面板都要把这几项收起来——留着一排调不出效果的滑动条，只会让人以为功能坏了。
     * 判定放在这里而不是各页面各写一次：两处必须同时收同时放，规则只能有一份。
     */
    val showsStyle get() = this != HIDDEN

    companion object { fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: FULL }
}

/**
 * 弹幕的移动速度档位。
 *
 * [factor] 是「标准档速度」的倍数：弹幕画面上所有弹幕**同速**（见 [com.hgtv.star.player.DanmakuView]），
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
 * 一轮「重复弹幕」聚合：同样的文字在 [DANMAKU_REPEAT_WINDOW_MS] 之内出现了 [count] 次。
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
    val eligibleMs get() = offsets[minOf(DanmakuHighlight.MIN_COUNT,count)-1]
}

/**
 * 一轮聚合的时间窗口：从这一轮的第一条开始算，窗口内出现的同内容都算进这一轮。
 *
 * 超出窗口之后再次出现，就是新的一轮（需求「超出聚合时间窗口后，再次出现的内容可作为新的一轮聚合」）。
 * 窗口从第一条起算而不是「相邻两条间隔」：不然一句被刷屏的话会一路串到底，整集都只算一轮。
 */
const val DANMAKU_REPEAT_WINDOW_MS=20_000L

/**
 * 把整集弹幕按内容聚合：同一句文字在窗口内出现多次合成一组，按时间排序。
 *
 * 纯函数，与画面无关：弹幕层拿它决定「哪些内容只画一次、数量是多少」，
 * 顺带把首尾空格这类不影响语义的格式差异抹掉（[Danmaku.text] 解析时已经 trim 过一次，
 * 这里再 trim 一次是给别的来源兜底）。
 */
fun repeatGroups(items: List<Danmaku>,windowMs: Long=DANMAKU_REPEAT_WINDOW_MS): List<DanmakuRepeat> {
    val groups=mutableListOf<DanmakuRepeat>()
    items.sortedBy { it.offsetMs }.groupBy { it.text.trim() }.forEach { (text,list) ->
        if(text.isEmpty()) return@forEach
        var bucket=mutableListOf<Long>()
        var windowStart=Long.MIN_VALUE
        list.forEach { item ->
            // 第一条开一个窗口；窗口内继续累计；超出窗口就是新的一轮。
            if(bucket.isNotEmpty() && item.offsetMs>windowStart+windowMs) {
                groups+=DanmakuRepeat(text,bucket)
                bucket=mutableListOf()
            }
            if(bucket.isEmpty()) windowStart=item.offsetMs
            bucket+=item.offsetMs
        }
        if(bucket.isNotEmpty()) groups+=DanmakuRepeat(text,bucket)
    }
    return groups.sortedWith(compareBy({ it.firstMs },{ it.text }))
}

/**
 * 中央突出显示的规则参数与排序规则。
 *
 * 需求 §4.3：同一时间只有一条弹幕获得中央突出显示；未获得资格的仍然正常滚动（只是不进中央）；
 * 中央这一条要稳（数量涨了只更新标记、不切走），并且停留时间有上限，不会长期霸占屏幕中央。
 * 参数集中放在这里：弹幕层按它跑，改档位 / 改时长只动这一处。
 */
object DanmakuHighlight {
    /** 至少重复这么多次才算「重复弹幕」，才有资格进中央。 */
    const val MIN_COUNT=2
    /** 一条中央弹幕至少停留多久。 */
    const val MIN_DWELL_MS=5_000L
    /** 停留期间每多出现一条重复，延长多久（需求「重复次数增加时，可以适当延长…停留时间」）。 */
    const val EXTEND_MS=1_200L
    /** 停留上限：再热也不会一直占着中央（需求「应设置最大停留时长」）。 */
    const val MAX_DWELL_MS=12_000L
    /** 一轮聚合结束后还能被选上中央的尾巴：超过这个时间就不再补展示（跳转之后那些旧聚合）。 */
    const val TAIL_MS=6_000L
}

/** 中央突出显示的一轮：[group] 从 [startMs] 展示到 [endMs]（播放时间轴上的毫秒）。 */
data class DanmakuHighlightRound(val group: DanmakuRepeat,val startMs: Long,val endMs: Long)

/**
 * 排一遍中央突出显示的次序（纯函数，无副作用，可以单测）。
 *
 * 规则（需求 §4.3）：
 * * 一轮结束后再选下一条，一轮之内不换（「不应立即频繁切换」）；
 * * 选「到那一刻为止累计数量最多」的那一组，数量相同选更早出现的（[DanmakuRepeat.firstMs]）；
 * * 每轮停留 = 基础停留 + 期间每多一条重复延长一点，封顶 [DanmakuHighlight.MAX_DWELL_MS]；
 * * [previous] 是上一份数据排出来的次序：数据是一窗一窗补上来的，某一条正在中央展示时
 *   重新排一次，只要它还在候选里就**接着排它**，这样补数据不会把中央那一条换掉。
 */
fun planHighlights(groups: List<DanmakuRepeat>,previous: List<DanmakuHighlightRound> = emptyList()): List<DanmakuHighlightRound> {
    val pending=groups.filter { it.count>=DanmakuHighlight.MIN_COUNT }
    if(pending.isEmpty()) return emptyList()
    val used=mutableSetOf<String>()
    val rounds=mutableListOf<DanmakuHighlightRound>()
    var cursor=0L
    while(true) {
        // 已经过去太久的那些（快进之后才拿到的旧聚合）不再补展示，直接作废。
        pending.filter { it.key !in used && cursor>it.lastMs+DanmakuHighlight.TAIL_MS }.forEach { used+=it.key }
        val showing=previous.firstOrNull { cursor>=it.startMs && cursor<it.endMs }?.group?.key
        val carried=showing?.let { key -> pending.firstOrNull { it.key==key && it.key !in used && it.eligibleMs<=cursor } }
        val ready=pending.filter { it.key !in used && it.eligibleMs<=cursor }
        // 数量优先、相同数量先到先得：`compareBy(数量, -起始时间)` 的 max 就是「最多且最早」。
        val best=carried ?: ready.filter { it.countUpTo(cursor)>=DanmakuHighlight.MIN_COUNT }
            .maxWithOrNull(compareBy({ it.countUpTo(cursor) },{ -it.firstMs }))
        if(best==null) {
            // 现在没人够格：把时间推到「下一组够格的时刻」，再选一次。
            val next=pending.filter { it.key !in used }.map { maxOf(it.eligibleMs,it.firstMs) }.filter { it>cursor }.minOrNull() ?: break
            cursor=next
            continue
        }
        val end=roundEnd(best,cursor)
        rounds+=DanmakuHighlightRound(best,cursor,end)
        used+=best.key
        cursor=end
    }
    return rounds
}

/** 一轮的结束时刻：基础停留 + 这期间每多一条重复延长一点，封顶在最大停留时长。 */
private fun roundEnd(group: DanmakuRepeat,startMs: Long): Long {
    var end=startMs+DanmakuHighlight.MIN_DWELL_MS
    while(true) {
        val counted=group.countUpTo(end)
        val wanted=startMs+DanmakuHighlight.MIN_DWELL_MS+(counted-DanmakuHighlight.MIN_COUNT)*DanmakuHighlight.EXTEND_MS
        val next=minOf(wanted,startMs+DanmakuHighlight.MAX_DWELL_MS)
        if(next<=end) return end
        end=next
    }
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
