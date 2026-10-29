// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import kotlin.math.exp
import kotlin.math.min

/**
 * 推荐流的**客户端那一半治理**：剧级去重窗口 + 本机兴趣排序 + 播放列表打散。
 *
 * 依据 `docs/红果短剧推荐流去重机制分析.md`：
 *
 * * 服务端的曝光去重（Bloom / Redis + 分层 TTL）、召回配额、探索比例，客户端改不了；
 * * 客户端能改的是文档 §6.7 那条「兜底去重」——**按 `series_id` 而不是 `vid`**、
 *   窗口要够长（官方默认只有 200 / 20，重度用户十几分钟就刷穿）、**冷启要能恢复**、
 *   合并时覆盖式去重；
 * * 另外文档 §6.6 / §6.3 的重排约束（同剧间距、同题材配额、同主演间距）在**这一端**
 *   同样能落地：分类接口一次给一整页，页面自己按约束重新排一遍，用户读到的顺序就散开了。
 *
 * 三条纪律，与文档 §0 的结论一致：
 *
 * 1. **去重不是越狠越好**：窗口内的条目到期自动失效（7 天），到期之后允许合法复访；
 * 2. 池子抽干时**宁可重复也不要空屏**：真正的兜底在调用方（`loadRecommendPage` 的
 *    `exhausted` 分支），这里只负责把「还有货」的那一页排得更耐看；
 * 3. 兴趣排序只在**很小的邻域内**挪动（[RecommendArranger.promoteSpan]），
 *    不推翻服务端的推荐序——本机的历史记录远不如服务端模型准，能做的只是「把明显
 *    对口的往前挪几格」。
 */
object RecommendFlow {
    const val DAY = 86_400_000L
    /**
     * 曝光窗口的存活时长（文档 §6.5 的「7 天」那层）。
     *
     * 官方客户端是 `filter_max_size=200` 的纯 LRU，没有时间维度；按条数算，重度用户
     * 半小时就刷穿，之后同一部剧能反复回来。这里改成**时间滑窗 + 条数上限**：
     * 7 天内推过的不再推，7 天后允许复访。
     */
    const val TTL_SEEN = 7 * DAY
    /**
     * 「一进来就划走」的冷却（文档 §6.4「负反馈」那一档，取比普通曝光更长的冷却）。
     *
     * 只延长、不缩短：同一条记录被反复标记时取两者中更晚的失效时刻（见 [RecommendMarks.mark]）。
     */
    const val TTL_SKIP = 14 * DAY
    /** 真的看了（推荐页停留够久 / 进了正片）：文档 §6.5 的「完播 30~90 天」取 30 天。 */
    const val TTL_DONE = 30 * DAY
    /**
     * 窗口最多记多少条。
     *
     * 官方那个 200 太少（一屏 6 条 ≈ 33 屏）；1200 条约等于 7 天重度使用（一页 20 条 × 60 页），
     * 落盘约 30KB，与已有的 800 条 id 记录同一量级。
     */
    const val MARK_LIMIT = 1200
    /**
     * 回传给服务端的 `filter_ids` 最多带几条（文档 §2.1：有上限、超限会被截断）。
     *
     * 带的是**最新的**那些：老条目本来也快到失效时刻了，截断它们的代价最小。
     */
    const val FILTER_IDS_LIMIT = 200
    /** 兴趣权重的半衰期（天）：半个月前的口味算一半。 */
    const val TASTE_HALF_LIFE_DAYS = 14.0
    /** 剧名归一化后的内部前缀：与剧 id 共用一个窗口，但绝不进 `filter_ids`。 */
    private const val TITLE_PREFIX = "#"

    /**
     * 剧名归一化键（`SeriesSeasons.normalize`，剥季号 + 去标点）。
     *
     * 用来挡文档 §5「原因 3 / 原因 9」那两种重复：**同一部剧的另一个切片**、
     * **同一 IP 的另一个版本**——它们的 `series_id` 不同，只按 id 去重是拦不住的。
     */
    fun titleKey(title: String): String {
        val key = SeriesSeasons.normalize(title)
        return if (key.isEmpty()) "" else TITLE_PREFIX + key
    }

    /** 题材标签拆分：卡片上是 `玄幻 · 逆袭`，个别接口给 `玄幻，逆袭`。 */
    fun tagsOf(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<String>(4)
        for (part in raw.split('·', '・', '，', ',', '、', '/', '|', '；', ';', ' ', '\u3000')) {
            val tag = part.trim()
            if (tag.isEmpty() || tag.length > 12) continue
            if (tag.all { it.isDigit() }) continue
            if (tag !in out) out += tag
        }
        return out
    }
}

/**
 * 剧级曝光窗口（文档 §6.7 的 `FeedDedup`，只保留它真正需要的两部分）。
 *
 * 一条记录 = `键 @ 失效时刻`。键有两种：
 * * **剧 id**：同时用于本地过滤与回传 `filter_ids`；
 * * `#归一化剧名`：只用于本地过滤（同剧不同季 / 同 IP 多版本），服务端不认识这种键。
 *
 * 线程：调用方按需加锁，本类内部每个入口都 `@Synchronized`——`library.recommendSeen()`
 * 在主线程读，`filterIds()` 在取流的工作线程读，两边都可能与「曝光时写入」交错。
 */
class RecommendMarks(private val limit: Int = RecommendFlow.MARK_LIMIT) {
    /** 插入顺序即「推出的先后」，淘汰从队头开始（与文档 §6.7 的 LRU 同一套含义）。 */
    private val entries = LinkedHashMap<String, Long>()

    /** 从落盘的串恢复；旧版本只有一串逗号分隔的 id（没有 `@失效时刻`），按新曝光补一个 7 天。 */
    @Synchronized
    fun restore(raw: String, now: Long = System.currentTimeMillis()) {
        entries.clear()
        for (token in raw.split(',')) {
            val text = token.trim()
            if (text.isEmpty() || text.length > 96) continue
            val at = text.lastIndexOf('@')
            val key = if (at > 0) text.substring(0, at) else text
            val expire = if (at > 0) text.substring(at + 1).toLongOrNull() ?: 0L else now + RecommendFlow.TTL_SEEN
            if (key.isEmpty() || expire <= now) continue
            entries[key] = expire
        }
        prune(now)
    }

    /** 落盘串：只写还没失效的那些。 */
    @Synchronized
    fun encode(now: Long = System.currentTimeMillis()): String {
        prune(now)
        if (entries.isEmpty()) return ""
        val builder = StringBuilder(entries.size * 24)
        entries.forEach { (key, expire) ->
            if (builder.isNotEmpty()) builder.append(',')
            builder.append(key).append('@').append(expire)
        }
        return builder.toString()
    }

    /**
     * 记下这些键，[ttl] 之后失效。
     *
     * **只延长不缩短**：同一条先按「曝光」记 7 天，再按「一进来就划走」记 14 天，取 14 天；
     * 反过来（先划走、后又看了）也不会把它提前放回来。
     */
    /** @return 有没有真的改动（调用方据此决定要不要落盘）。 */
    @Synchronized
    fun mark(keys: Collection<String>, ttl: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (keys.isEmpty()) return false
        prune(now)
        var changed = false
        val expire = now + ttl
        for (raw in keys) {
            val key = raw.trim()
            if (key.isEmpty() || key.length > 64 || key.any { it == ',' || it == '@' || it.isISOControl() }) continue
            val current = entries[key]
            if (current != null && current >= expire) continue
            entries.remove(key)
            entries[key] = expire
            changed = true
        }
        if (changed) prune(now)
        return changed
    }

    /** 这个剧 id 在窗口内（还没到期）。 */
    @Synchronized
    fun contains(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val expire = entries[key] ?: return false
        if (expire > now) return true
        entries.remove(key)
        return false
    }

    /** 窗口内的剧 id 快照（不含 `#剧名` 那种键）。调用方只读。 */
    @Synchronized
    fun ids(now: Long = System.currentTimeMillis()): Set<String> {
        prune(now)
        if (entries.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>(entries.size)
        entries.keys.forEach { if (!it.startsWith('#')) out += it }
        return out
    }

    /**
     * 回传给服务端的 `filter_ids`：最新的 [limit] 条剧 id，逗号分隔（没有就是空串）。
     *
     * 官方客户端就是这么回传的（文档 §4.4 的 `m6` 那个 LRU 队列）；空串是「不带」，
     * 与旧版请求逐字节一致。
     */
    @Synchronized
    fun filterIds(limit: Int = RecommendFlow.FILTER_IDS_LIMIT, now: Long = System.currentTimeMillis()): String {
        prune(now)
        if (entries.isEmpty()) return ""
        val rows = ArrayList<String>(min(entries.size, limit))
        val iterator = entries.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key.startsWith('#')) continue
            rows += key
        }
        if (rows.isEmpty()) return ""
        return rows.takeLast(limit.coerceAtLeast(1)).joinToString(",")
    }

    @Synchronized
    fun size(now: Long = System.currentTimeMillis()): Int {
        prune(now)
        return entries.size
    }

    /** 丢掉到期的条目，再按条数上限从队头淘汰。 */
    private fun prune(now: Long) {
        if (entries.isEmpty()) return
        val dead = entries.entries.filter { it.value <= now }.map { it.key }
        if (dead.isNotEmpty()) dead.forEach { entries.remove(it) }
        while (entries.size > limit) {
            val head = entries.keys.iterator()
            if (!head.hasNext()) break
            head.next()
            head.remove()
        }
    }
}

/**
 * 本机兴趣画像：题材 / 出品方 → 权重。
 *
 * 只吃**本机已经发生的动作**（文档 §3.2「实时行为特征」的客户端等价物）：
 * * 观看历史：看完 3 分、看了大半 2 分、刚点开 0.5 分，按 [RecommendFlow.TASTE_HALF_LIFE_DAYS] 衰减；
 * * 收藏 2 分（收藏比看完更能说明「这一类我要」）。
 *
 * 负反馈不在这里：负反馈是「别推这一部」，由 [RecommendMarks] 的时间窗负责；
 * 把「划走一部」折算成「讨厌这个题材」是过度解读，会把整类内容误伤掉（文档 §6.1 的完播率会掉）。
 */
class RecommendTaste {
    private val tags = HashMap<String, Float>()
    private val authors = HashMap<String, Float>()
    /**
     * 这次会话里「一进来就划走」的题材计数（文档 §3.2 的实时负反馈）。
     *
     * 只活在这次会话里：划走一两次说明不了什么（封面不好看、正好在接电话），
     * 但同一类连划好几部，这一屏就该把它往后放。**不删**——删了池子会更快抽干。
     * 也不落盘：跨天之后口味可能完全变了，为这点信号留一份磁盘状态不值得。
     */
    private val skips = HashMap<String, Int>()

    val isEmpty: Boolean get() = tags.isEmpty() && authors.isEmpty() && skips.isEmpty()

    fun learn(series: Series, weight: Float) {
        if (weight <= 0f) return
        for (tag in RecommendFlow.tagsOf(series.tags)) {
            tags[tag] = ((tags[tag] ?: 0f) + weight).coerceAtMost(12f)
        }
        val author = series.author.trim()
        if (author.isNotEmpty() && author.length <= 24) {
            authors[author] = ((authors[author] ?: 0f) + weight).coerceAtMost(12f)
        }
    }

    /**
     * 这一部与本机口味的匹配分（0 = 完全不搭）。
     *
     * 命中多个标签时按 `1 + 0.25·(n-1)` 除掉一部分：五个标签全中不代表比一个强五倍，
     * 否则标签多的剧会稳定压过标签少的剧，排序就变成了「按标签个数排」。
     */
    fun score(series: Series): Float {
        if (isEmpty) return 0f
        var sum = 0f
        var hits = 0
        for (tag in RecommendFlow.tagsOf(series.tags)) {
            val weight = tags[tag] ?: continue
            sum += weight
            hits++
        }
        val author = series.author.trim()
        if (author.isNotEmpty()) {
            val weight = authors[author]
            if (weight != null) {
                sum += weight * 0.6f
                hits++
            }
        }
        if (hits == 0) return 0f
        return (sum / (1f + 0.25f * (hits - 1))).coerceAtMost(6f)
    }

    /** 记一次「一进来就划走」。 */
    fun skipped(series: Series) {
        for (tag in RecommendFlow.tagsOf(series.tags)) {
            skips[tag] = ((skips[tag] ?: 0) + 1).coerceAtMost(6)
        }
    }

    /** 这一部的题材被划走过几次（取命中的最大值）。 */
    fun distaste(series: Series): Int {
        if (skips.isEmpty()) return 0
        var worst = 0
        for (tag in RecommendFlow.tagsOf(series.tags)) {
            val count = skips[tag] ?: continue
            if (count > worst) worst = count
        }
        return worst
    }

    /**
     * 排序用的偏好值：口味加分 − 划走扣分。
     *
     * 扣分比加分更陡（1.4 / 次）：划走是**用户主动**表达「不要这一部」，
     * 而历史里的权重只是「看过」，两者的确信度不在一个量级。
     */
    fun preference(series: Series): Float = score(series) - 1.4f * distaste(series)

    companion object {
        /** 从本机历史 + 收藏建一份画像。纯内存计算，不碰磁盘。 */
        fun of(
            history: List<BackupProgress>,
            favorites: List<Series>,
            now: Long = System.currentTimeMillis()
        ): RecommendTaste {
            val taste = RecommendTaste()
            for (row in history) {
                val ratio = if (row.duration > 0) row.position.toFloat() / row.duration else 0f
                val base = when {
                    row.completed -> 3f
                    ratio >= 0.6f -> 2f
                    ratio >= 0.25f -> 1.2f
                    else -> 0.5f
                }
                val age = (now - row.updatedAt).coerceAtLeast(0L).toDouble()
                val halfLife = RecommendFlow.TASTE_HALF_LIFE_DAYS * RecommendFlow.DAY
                // 太老的口味留一个地板值 0.25：换季之后口味会变，但不会一夜清空。
                val decay = exp(-age / halfLife * 0.6931471805599453).toFloat().coerceAtLeast(0.25f)
                taste.learn(row.series, base * decay)
            }
            for (series in favorites) taste.learn(series, 2f)
            return taste
        }
    }
}

/**
 * 一页推荐的本地重排（文档 §6.6 那几条约束的客户端版本）。
 *
 * 做两件事，顺序不能反：
 * 1. **兴趣上浮**：在 [promoteSpan] 格的邻域内，把匹配分更高的那一部换到前面。
 *    只在小窗口里挪，服务端的排序大体保留——本机画像不如服务端模型准。
 * 2. **打散**：同题材每 [tagWindow] 条最多 [tagMax] 条、同出品方间隔 [authorGap]、
 *    同剧名（另一季 / 同 IP）间隔 [titleGap]。让不出位置的不删，按原顺序贴到这一页末尾：
 *    删了就是把池子抽干，最后只会更重复。
 *
 * 追加的时候把列表尾部当作上下文传进来（[tail]），新的一页才会和已经刷过的那几条
 * 接得上——否则每次补页都是「从零开始的多样性」，页与页的接缝上最容易撞同题材。
 */
class RecommendArranger(
    val tagWindow: Int = 10,
    val tagMax: Int = 2,
    val authorGap: Int = 12,
    val titleGap: Int = 20,
    val promoteSpan: Int = 4,
    val promoteMargin: Float = 0.25f
) {
    fun arrange(page: List<Series>, taste: RecommendTaste, tail: List<Series> = emptyList()): List<Series> {
        if (page.isEmpty()) return page
        val promoted = if (taste.isEmpty) page else promote(page, taste)
        val lookback = maxOf(tagWindow, authorGap, titleGap)
        val context = if (tail.isEmpty()) emptyList() else tail.takeLast(lookback)
        val pending = ArrayList<Series>(promoted.size)
        val knownIds = HashSet<String>(promoted.size * 2)
        val knownTitles = HashSet<String>()
        context.forEach {
            knownIds += it.id
            val key = RecommendFlow.titleKey(it.title)
            if (key.isNotEmpty()) knownTitles += key
        }
        // 用「上下文 + 已排好的」量间距；下标与真实位置一致（上下文占前面那几格）。
        val slots = ArrayList<Series>(context.size + promoted.size)
        slots.addAll(context)
        val start = slots.size
        for (item in promoted) {
            if (item.id.isEmpty() || !knownIds.add(item.id)) continue
            val title = RecommendFlow.titleKey(item.title)
            if (title.isNotEmpty() && !knownTitles.add(title)) continue
            if (fits(slots, item, title)) slots += item else pending += item
        }
        val result = ArrayList<Series>(slots.size - start + pending.size)
        result.addAll(slots.subList(start, slots.size))
        pending.forEach { item -> if (result.none { it.id == item.id }) result += item }
        return result
    }

    /** 邻域上浮：只看后面 [promoteSpan] 格，挑出明显更对口的那一部换到当前位置。 */
    private fun promote(items: List<Series>, taste: RecommendTaste): List<Series> {
        if (items.size < 2 || promoteSpan <= 0) return items
        val list = ArrayList(items)
        var i = 0
        while (i < list.size - 1) {
            var best = i
            var bestScore = taste.preference(list[i])
            val end = min(list.size - 1, i + promoteSpan)
            var j = i + 1
            while (j <= end) {
                val score = taste.preference(list[j])
                if (score > bestScore + promoteMargin) {
                    best = j
                    bestScore = score
                }
                j++
            }
            if (best != i) list.add(i, list.removeAt(best))
            i++
        }
        return list
    }

    /** 这一部放在 [slots] 末尾会不会撞上刚放过的同类。 */
    private fun fits(slots: List<Series>, item: Series, title: String): Boolean {
        val pos = slots.size
        var sameTags = 0
        val from = (pos - tagWindow).coerceAtLeast(0)
        var i = from
        while (i < pos) {
            if (samePrimaryTag(slots[i], item)) sameTags++
            i++
        }
        if (sameTags >= tagMax) return false
        val author = item.author.trim()
        if (author.isNotEmpty()) {
            val authorFrom = (pos - authorGap).coerceAtLeast(0)
            var j = authorFrom
            while (j < pos) {
                if (slots[j].author.trim() == author) return false
                j++
            }
        }
        if (title.isNotEmpty()) {
            val titleFrom = (pos - titleGap).coerceAtLeast(0)
            var k = titleFrom
            while (k < pos) {
                if (RecommendFlow.titleKey(slots[k].title) == title) return false
                k++
            }
        }
        return true
    }

    /**
     * 只看**第一个**题材（卡片第二行的第一段，也就是这一部的主类型）。
     *
     * 为什么不做「任意标签相同」：短剧的标签几乎每条都带「逆袭 / 剧情 / 都市」这类大词，
     * 任意交集会让配额形同虚设地卡住整页，几乎每条都让不出位置，重排就退化成原序。
     * 主类型相同才算「同类扎堆」，这与用户读卡片时的感受一致。
     */
    private fun samePrimaryTag(left: Series, right: Series): Boolean {
        val a = RecommendFlow.tagsOf(left.tags).firstOrNull() ?: return false
        val b = RecommendFlow.tagsOf(right.tags).firstOrNull() ?: return false
        return a == b
    }
}
