// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.list

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hgtv.star.core.Series
import com.hgtv.star.compat.onScrollYChanged
import com.hgtv.star.tv.PosterCard
import com.hgtv.star.tv.SectionHeading
import com.hgtv.star.tv.SoftStyle
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.focusBleed
import com.hgtv.star.tv.softBand
import com.hgtv.star.tv.tvColumn
import com.hgtv.star.tv.tvDp
import com.hgtv.star.tv.tvScrollHost
import com.hgtv.star.tv.tvText
import com.hgtv.star.compat.attachedToWindow
import com.hgtv.star.compat.laidOut

/**
 * 首页内容区：全部由竖版海报架子组成，七个栏目固定顺序为
 * 历史记录 / 我的收藏 / 稍后观看 / 热播漫剧 / 热播真人 / 热播AI / 热播上新
 * （本机没有记录的三栏、远端取不到的榜单都不占位）。
 * 每栏最多十部，本机没有记录的架子不占位，由 [render] 过滤空架子。
 *
 * 排版对齐网易爆米花：整页左右安全边距 78dp，一屏 6 张海报（设置里可选 4 / 5 / 6 / 7 / 8）、行内间距 14dp，
 * 分区标题就是一行 20sp 粗体 80% 白文字（没有装饰条、没有副标题），
 * 剧名与状态放在卡片下方，因此整页不需要额外的大图预览区。
 * 一行放不下的分区在行末留一张「查看全部」底板：按右键直接进入集合页，点按同样可以进入。
 * 焦点按数据 key 保存，行内横向位置与整页纵向位置分开记忆，返回后原位恢复。
 */
class HomeScreen(
    context: Context,
    private val top: View,
    private val inset: Int,
    private val loadCover: (ImageView, String) -> Unit,
    private val open: (Series, Boolean) -> Unit,
    /** 长按 / 菜单键打开卡片菜单；[shelf] 是卡片所在剧架的 key，菜单据此决定有没有「删除记录」。 */
    private val actions: (Series, View, String) -> Unit,
    private val remember: (String) -> Unit,
    /**
     * 每一行放几张卡片：4 / 5 / 6 / 7 / 8（设置页可改，默认 6）。
     *
     * 列数变少时卡片变宽，封面与卡片文字按同一比例放大（见 [textScale]），
     * 否则 4 列的大海报下面会吊着两行 6 列时的字号，比例是错的。
     */
    private val columns: Int = 6
) : LinearLayout(context) {
    data class Entry(
        val series: Series,
        val label: String,
        val resume: Boolean = false,
        val progress: Int? = null,
        val time: String = "",
        /** 海报右下角的备用角标（名次 / 热度）；有总集数时右下角显示集数，不看这一项。 */
        val badge: String = ""
    )

    data class Shelf(
        val key: String,
        val title: String,
        val entries: List<Entry>,
        /**
         * 行末那张「查看全部」底板。为空时这一行**不放**这张卡——
         * 卡片宽度是按「一屏 6 张」定死的，多一张底板只是让这一行能横向滚动，不会把海报挤窄，
         * 因此**只要有内容就给**这张卡：它是这一栏进二级页的入口，
         * 也是行末再按右键的去处（本机记录那三栏与热播四栏同一条规则）。
         */
        val more: (() -> Unit)? = null
    )

    data class Position(
        val focus: String,
        val shelf: String?,
        val column: Int,
        val row: Int,
        val vertical: Int,
        val horizontal: Map<String, Int>
    )

    private class Card(val key: String, val view: View, var entry: Entry?) {
        var image: ImageView? = null
        var poster: PosterCard? = null
        var pending = false
        var repaint: (Boolean) -> Unit = {}
    }

    private class Row(
        val key: String,
        val block: LinearLayout,
        val horizontal: HorizontalScrollView,
        val cards: List<Card>,
        val slotWidth: Int
    )

    private fun dp(value: Number) = context.tvDp(value)

    /**
     * 整块重建之前把每一张卡的封面摘掉。
     *
     * 首页的剧架每次刷新都会 `removeAllViews()` 重建，旧卡要等 GC 才真正释放；
     * 在这之前它们仍然钉着几十张位图（每张约 0.5 MB），与内存缓存叠加就是双份。
     * 主动摘掉之后，这批位图立刻回到「只由缓存持有」的状态。
     */
    private fun releaseCards() {
        cards.values.forEach { card -> card.poster?.releaseCover() }
    }

    /**
     * 剧架滚动容器：左右各留 [TvStyle.FOCUS_BLEED]。
     * 卡片就贴在容器左边缘，焦点放大后第一张卡的左描边会落到容器外面，
     * 被容器自己裁掉——这就是「第一张卡左边框看不见」的原因。
     */
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        // 容器自己不裁剪：卡片要能画进 focusBleed() 留出来的焦点余量里，
        // 否则第一行 / 最左边那张卡放大后的描边会被切掉。
        // 把滚动内容关在可视区里的裁剪由外面的 tvScrollHost 提供（裁剪边界取的是直接父级）。
        clipChildren = false
        clipToPadding = false
        focusBleed()
        // 滚动位置报给宿主：宿主据此收起 / 展开顶部工具条（首页与二级页同一套行为）。
        // 恢复上次位置的那一次不算，免得一进页面页头就闪一下。
        onScrollYChanged { y -> if (!restoring) onScroll?.invoke(y) }
    }
    /** 滚动容器的外壳：把滚动内容关在可视区里的裁剪由它做（见 TvComponents.tvScrollHost）。 */
    private val scrollHost = context.tvScrollHost(scroll)
    private val contents = LinearLayout(context).apply {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val rows = mutableListOf<Row>()

    private val cards = linkedMapOf<String, Card>()
    private var shelves = emptyList<Shelf>()
    private var lastFocus = ""
    private var lastRow: String? = null
    private var restoring = false
    private var revision = 0
    /**
     * 还没建出来的栏目（见 [render] 的分批渲染）。
     *
     * 首页一次最多 7 栏 × 11 张卡，整份建完是 70 多张卡、700 多个 View，低端盒子上
     * 首帧就要几百毫秒到一两秒。首屏只建 [HOME_FIRST_BATCH] 栏，其余在这里排队，
     * 之后按 [HOME_BATCH_BUDGET_MS] 的时间预算逐帧补（不按栏数切，见 [buildNextBatch]）。
     */
    private val pendingRows = ArrayDeque<Shelf>()
    /**
     * 本次渲染要恢复的位置。
     *
     * 分批渲染时后面的栏目还没建出来，[restoreOffsets] 跑的那一刻它们不存在；
     * 等它们建好再把纵向 / 横向位置补一次，否则「返回首页」会掉到顶部。
     */
    private var pendingPosition: Position? = null
    /**
     * 位置恢复完成那一刻的纵向位置。
     *
     * 分批渲染时后面的栏目还没建出来，内容高度不足、`scrollTo` 会被夹住；等全部建好要再摆一次。
     * 但如果这期间用户自己滚过（滚动位置已经不等于这个值），就**不能**再按旧位置把他拽回去。
     */
    private var restoredScrollY = -1
    /**
     * 紧凑模式（详情页「相关剧集」）：整块高度按内容走，因此 [fitArtwork] 不能参与——
     * 它是按「控件自身高度」去反算海报高度的，在这里会「收窄海报 → 控件变矮 → 再收窄」地越缩越小。
     */
    private var compact = false

    /** 剧架滚动时把纵向位置报给宿主：宿主用它决定顶部工具条收起还是展开。 */
    var onScroll: ((Int) -> Unit)? = null

    /**
     * 第一栏再按上键时，把焦点交给页头里的控件（由宿主决定是谁，并负责先把页头展开）。
     *
     * 返回 true 表示这一下已经被接管。宿主没接（返回 false）时退回框架默认的
     * `nextFocusUpId` 行为。
     *
     * 为什么不由 HomeScreen 自己 `requestFocus`：页头收着的时候那一行高度是 0、
     * alpha 也是 0，直接落焦点会停在一个看不见的控件上——用户读到的就是「按上没反应」。
     * 展开页头这件事只有宿主（[MainActivity]）能做，因此这里只上报意图。
     */
    var onHeadUp: (() -> Boolean)? = null

    /**
     * 渲染收尾（位置恢复、内容高度变化引起的夹取都做完了）时，把**最终**的纵向位置报给宿主。
     *
     * 与 [onScroll] 分开：[onScroll] 在 `restoring` 期间是静默的（那期间的滚动不是用户滚的），
     * 于是「这次重建把列表摆在哪儿」宿主永远收不到通知。首页因此会停在
     * 「列表其实还在顶部、页头却已经收着」的状态——表现出来就是顶部导航栏不显示、
     * 只能靠把焦点挪到第一栏（历史记录）把列表带回顶部才恢复。
     */
    var onSettled: ((Int) -> Unit)? = null

    /**
     * 详情页「相关剧集」用的紧凑模式：整块只画一栏、高度按内容走、不参与整页滚动。
     *
     * 详情页把这一块放在海报右下方（原来的选集位置），因此它不能像首页那样
     * 「滚动容器 + 弹簧撑满」，只能是一块自带横向滚动的内容。
     */
    fun renderCompact(shelves: List<Shelf>, focus: String, requestFocus: Boolean) {
        compact = true
        this.shelves = shelves
        revision++
        val ticket = revision
        restoring = false
        releaseCards()
        contents.removeAllViews()
        rows.clear()
        cards.clear()
        lastRow = null
        shelves.filter { it.entries.isNotEmpty() }.forEach { shelf ->
            val block = LinearLayout(context).apply {
                orientation = VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            block.addView(SectionHeading(context, shelf.title), LinearLayout.LayoutParams(-1, -2))
            val horizontal = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                setPadding(dp(2), 0, dp(2), 0)
            }
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
                clipChildren = false
                clipToPadding = false
            }
            horizontal.addView(line)
            block.addView(horizontal, LinearLayout.LayoutParams(-1, -2))
            val slotWidth = slotWidth()
            val rowCards = mutableListOf<Card>()
            shelf.entries.forEach { addCard(shelf, line, rowCards, it, null, ticket, slotWidth) }
            contents.addView(block, LinearLayout.LayoutParams(-1, -2))
            rows += Row(shelf.key, block, horizontal, rowCards, slotWidth)
        }
        rows.forEachIndexed { i, row ->
            row.cards.forEachIndexed { j, card ->
                card.view.nextFocusLeftId = row.cards[(j - 1).coerceAtLeast(0)].view.id
                card.view.nextFocusRightId = row.cards[(j + 1).coerceAtMost(row.cards.lastIndex)].view.id
                card.view.nextFocusUpId =
                    if (i == 0) top.id else rows[i - 1].cards[j.coerceAtMost(rows[i - 1].cards.lastIndex)].view.id
                card.view.nextFocusDownId =
                    if (i == rows.lastIndex) card.view.id
                    else rows[i + 1].cards[j.coerceAtMost(rows[i + 1].cards.lastIndex)].view.id
            }
        }
        val target = target(focus, null)
        if (requestFocus) target.requestFocus()
        post layout@{
            if (revision != ticket) return@layout
            loadNearbySelection()
        }
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        scroll.addView(contents)
        // 滚动容器挂在自己的外壳上：外壳裁剪，容器留余量。
        addView(scrollHost, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun capturePosition(): Position {
        val key = findFocus()?.tag?.toString()?.takeIf { it in cards } ?: lastFocus
        val row = rows.indexOfFirst { item -> item.cards.any { it.key == key } }
        val column = rows.getOrNull(row)?.cards?.indexOfFirst { it.key == key } ?: 0
        return Position(
            key,
            rows.getOrNull(row)?.key,
            column.coerceAtLeast(0),
            row.coerceAtLeast(0),
            scroll.scrollY,
            rows.associate { it.key to it.horizontal.scrollX }
        )
    }

    /**
     * 按 key 就地刷新**已有卡片的状态行**，不重建任何视图、不动焦点。
     *
     * 给「长按菜单里改了一下这部剧的本机状态（收藏 / 稍后观看）」这类**局部**变化用：
     * 走 [updateShelves] 会整块重建剧架（`contents.removeAllViews()` + 重新量海报高度），
     * 观感就是「画面闪缩一下」。这里只改那几张卡的文字，代价与剧架长度无关。
     *
     * 返回**没能就地更新**的 key（新出现的剧不在剧架里，需要调用方决定要不要重建）；
     * 全部命中时返回空。
     */
    fun refreshEntries(next: List<Shelf>): List<String> {
        val wanted = next.flatMap { shelf -> shelf.entries.map { shelf.key to it } }
        val missing = mutableListOf<String>()
        for ((shelfKey, entry) in wanted) {
            if (updateEntry(shelfKey, entry)) continue
            // 还没建出来的栏目：直接改排队里的那一份，等 [buildNextBatch] 建出来时就是新数据。
            val at = pendingRows.indexOfFirst { it.key == shelfKey }
            if (at >= 0) {
                val shelf = pendingRows[at]
                pendingRows[at] = shelf.copy(entries = shelf.entries.map { if (it.series.id == entry.series.id) entry else it })
                continue
            }
            missing += "$shelfKey:${entry.series.id}"
        }
        return missing
    }

    /**
     * 当前剧架里所有卡片的 key（`<栏>:<剧集 id>`）；调用方据此判断结构有没有变。
     *
     * **包含还没建出来的栏目**（[pendingRows]）：分批渲染期间，结构比较必须按「最终会有哪些卡」
     * 来算，否则一次网络落地就会被判成「结构变了」，把刚省下的整块重建又做一遍。
     */
    fun cardKeys(): Set<String> = buildSet {
        addAll(cards.keys)
        pendingRows.forEach { shelf ->
            shelf.entries.forEach { add("${shelf.key}:${it.series.id}") }
            if (shelf.more != null) add("${shelf.key}:more")
        }
    }

    /** 当前纵向滚动位置。宿主用它把页头的收 / 展状态与真实位置摆一致（见 MainActivity 的页头判据）。 */
    fun scrollY(): Int = scroll.scrollY

    /**
     * 焦点当前落在第几栏（从 0 起）；焦点不在剧架上时返回 -1。
     *
     * 宿主的页头判据要用它：**焦点走到第二栏及以后，页头就该让开**。
     * 只看滚动位置是不够的——首页的栏高是按「一屏放得下一栏」算的，第二栏往往不用滚动
     * 就已经露出下半截，那时位置还是 0，页头却应该收起（与「滚下去才收」是同一件事的两种表达）。
     */
    fun focusedRowIndex(): Int = rows.indexOfFirst { row -> row.cards.any { it.view.hasFocus() } }

    /** 只重建这一块有界区域，并尽量保留原卡片或它的邻居。 */
    fun updateShelves(next: List<Shelf>, requestFocus: Boolean = hasFocus()) {
        val position = capturePosition()
        render(next, position.focus, requestFocus, position)
    }

    /** 不重建视图、不移动焦点的局部更新；结构性变化走 updateShelves。 */
    fun updateEntry(shelfKey: String, entry: Entry): Boolean {
        val card = cards["$shelfKey:${entry.series.id}"] ?: return false
        card.entry = entry
        card.poster?.let { poster ->
            poster.title.text = entry.series.title
            poster.setStatus(entry.label)
            poster.setProgress(entry.progress)
            poster.setCover(entry.series.cover)
            // 类型标签与集数也要跟着刷新：收藏 / 历史变化时这一条可能换了剧（同 id 但数据更新）。
            poster.setTags(entry.series.tags)
            poster.setEpisodeBadge(entry.series.episodeCount)
        }
        card.view.contentDescription = listOf(
            shelves.firstOrNull { it.key == shelfKey }?.title.orEmpty(),
            entry.series.title,
            entry.label
        ).filter { it.isNotBlank() }.joinToString("，")
        shelves = shelves.map { shelf ->
            if (shelf.key == shelfKey)
                shelf.copy(entries = shelf.entries.map { if (it.series.id == entry.series.id) entry else it })
            else shelf
        }
        loadNearbySelection()
        return true
    }

    /** 页面或生命周期变化取消封面请求后，重新加载可见卡片。 */
    fun reloadArtwork() {
        loadNearbySelection()
    }

    /**
     * 低内存专用：摘掉**视口之外**那些卡的封面。
     *
     * 首页是未窗口化的（7 栏常驻、约 77 张位图 = 44MB），这部分内存不受 `ArtworkLoader`
     * 的缓存预算约束——位图挂在 `ImageView` 上，LRU 清空也收不回来。
     * 因此内存压力要由界面自己响应：不可见的那些先放开，用户走到那里时读盘只要 3~10ms。
     *
     * 焦点卡与视口内的卡一概不动（`getGlobalVisibleRect` 为 true），否则眼前会闪一片灰框。
     */
    /** 有封面被低内存策略摘着：只有这时滚动 / 焦点回调才需要检查「是不是回来了」。 */
    private var suspendedAny = false

    fun suspendCoversOutside(rect: android.graphics.Rect): Int {
        var count = 0
        cards.values.forEach { card ->
            val image = card.image ?: return@forEach
            val poster = card.poster ?: return@forEach
            if (image.getGlobalVisibleRect(rect)) return@forEach
            if (poster.suspendCover()) count++
        }
        if (count > 0) suspendedAny = true
        return count
    }

    /**
     * 把重新回到视口里的封面挂回去（低内存摘掉之后，滚动 / 焦点移动时调）。
     *
     * 返回这一次真的挂回去了几张。只在「有被摘掉的卡」时才被调用（见 MainActivity 的计数），
     * 平时滚动这条路径的代价是 0。
     */
    fun resumeCoversInside(rect: android.graphics.Rect): Int {
        var count = 0
        cards.values.forEach { card ->
            val poster = card.poster ?: return@forEach
            if (!poster.coverSuspended) return@forEach
            val image = card.image ?: return@forEach
            if (image.getGlobalVisibleRect(rect) && poster.resumeCover()) count++
        }
        return count
    }

    /** 这一页还有多少张封面被低内存策略摘着。 */
    fun suspendedCoverCount(): Int = cards.values.count { it.poster?.coverSuspended == true }

    /**
     * 把重新回到视口里的封面挂回去。平时（没有被摘掉的卡）代价是 0。
     *
     * 挂在 [loadNearbySelection] 之前：焦点走到那一栏、或列表滚到那里时，
     * 这一栏的图会先被挂回来（内存命中 0ms），不会出现「滚回来还是灰框」。
     */
    private fun resumeVisibleCovers() {
        if (!suspendedAny) return
        val rect = android.graphics.Rect()
        resumeCoversInside(rect)
        if (suspendedCoverCount() == 0) suspendedAny = false
    }

    fun render(
        shelves: List<Shelf>,
        focus: String,
        requestFocus: Boolean,
        position: Position? = null
    ) {
        val previous = position ?: if (rows.isNotEmpty()) capturePosition() else null
        pendingPosition = previous
        compact = false
        this.shelves = shelves
        revision++
        val ticket = revision
        restoring = true
        pendingRows.clear()
        releaseCards()
        contents.removeAllViews()
        rows.clear()
        cards.clear()
        lastRow = null

        val visible = shelves.filter { it.entries.isNotEmpty() || it.more != null }
        // 优先用 [Position.focus]：它是**卡片 key**（`<栏>:<剧集 id>`），精确到栏。
        // 从详情页返回时 [MainActivity.catalogFocus] 只记了纯剧集 id（见 `openSeries`），
        // 同一部剧同时出现在「历史记录 / 我的收藏」与「热播…」两栏时，按 id 会认到前面那一栏：
        // 焦点跑到别的行，原来那一栏又被排到后续批次里（横向位置因此丢失），
        // 用户看到的就是「返回首页后这一栏停在 1–6 张，焦点却还在第 7–10 张」。
        // [target] 两种形状都认，因此这里优先 key，缺失时才退回调用方给的 focus。
        val preferred = previous?.focus?.takeIf { it.isNotBlank() }
            ?: focus.ifBlank { previous?.focus.orEmpty() }
        // 焦点所在的那一栏必须和首屏一起建出来，否则 `requestFocus` 会落空、
        // 用户看到的是「焦点在页头」而不是上次停留的那张卡。
        // `preferred` 有两种形状：卡片 key（`history:123`）与纯剧集 id（`123`）——
        // 从详情页返回时 [MainActivity.catalogFocus] 记的是后者，两种都要认，
        // 否则焦点会退回位置恢复的落点（常常是行末那张「查看全部」）。
        val focusRow = visible.indexOfFirst { shelf ->
            shelf.entries.any { it.series.id == preferred || "${shelf.key}:${it.series.id}" == preferred }
        }
        val firstBatch = maxOf(HOME_FIRST_BATCH, focusRow + 1).coerceAtMost(visible.size)
        visible.take(firstBatch).forEach { shelf -> buildShelf(shelf, ticket) }

        if (cards.isEmpty() && visible.isEmpty())
            contents.addView(
                context.tvText("还没有本机记录，热门剧加载后可选择观看。", 16f, TvStyle.textDim)
                    .apply { setPadding(0, dp(20), 0, dp(20)) },
                LinearLayout.LayoutParams(-1, -2)
            )
        wireFocus()
        top.nextFocusDownId = firstId()

        val target = target(preferred, previous)
        if (requestFocus) target.requestFocus()
        // 剩下的栏目分帧补：首屏先出前几栏，用户立刻能按键；后面的栏目在下一个消息里
        // 建出来（这时页面已经可交互，不会让首帧承担 70 张卡的全部工作）。
        pendingRows.addAll(visible.drop(firstBatch))
        if (pendingRows.isNotEmpty()) post { buildNextBatch(ticket) }
        post layout@{
            if (revision != ticket) return@layout
            fitArtwork()
            post restore@{
                if (revision != ticket) return@restore
                if (previous != null) restoreOffsets(previous)
                restoring = false
                if (requestFocus && previous == null)
                    rows.firstOrNull { row -> row.cards.any { it.view === target } }?.let { revealSelection(it) }
                // 焦点请求可能发生在视图还没挂上窗口的那一帧，会静默失败：
                // 这里等布局完成后补一次，并把焦点卡滚进可视区。用户已经自己移动过焦点时不抢。
                if (requestFocus && rows.none { row -> row.cards.any { it.view.hasFocus() } }) target.requestFocus()
                rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let { revealSelection(it) }
                // 剧架刚量完宽度的那一帧，横向 ScrollView 的上限还可能是 0，上面那次
                // `scrollTo` 会被夹回起点。下一帧等宽度稳定后再把焦点卡滚进可视区一次。
                scroll.post {
                    if (revision != ticket) return@post
                    rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let { revealSelection(it) }
                }
                loadNearbySelection()
                // 位置恢复、revealSelection、内容夹取都做完了，把最终位置报给宿主一次。
                onSettled?.invoke(scroll.scrollY)
                // 布局刚跑完这一帧时 ScrollView 还可能再夹一次位置（海报高度到这一帧才最终确定），
                // 因此下一帧把最终位置**再报一次**：宿主据此把页头状态与真实位置摆一致。
                // 少了这一下，进页面 / 返回首页时页头可能与列表位置对不上（位置在顶部、页头却收着）。
                scroll.post { if (revision == ticket) onSettled?.invoke(scroll.scrollY) }
            }
        }
    }

    /** 建一栏（标题 + 横排剧架 + 卡片），插在 `contents` 末尾并登记到 [rows]。 */
    private fun buildShelf(shelf: Shelf, ticket: Int) {
        val block = LinearLayout(context).apply {
            orientation = VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        block.addView(
            // 分区标题就是一行文字：不再标注「更多 ›」。行末那张「查看全部」底板
            // （以及行内按右键直达集合页）已经把这个入口表达清楚，标题再写一次是重复的。
            SectionHeading(context, shelf.title),
            LinearLayout.LayoutParams(-1, -2)
        )
        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(2), 0, dp(2), 0)
        }
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        horizontal.addView(line)
        block.addView(horizontal, LinearLayout.LayoutParams(-1, -2))
        val slotWidth = slotWidth()
        val rowCards = mutableListOf<Card>()
        shelf.entries.forEach { addCard(shelf, line, rowCards, it, null, ticket, slotWidth) }
        shelf.more?.let { action -> addCard(shelf, line, rowCards, null, action, ticket, slotWidth) }
        contents.addView(
            block,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        )
        val row = Row(shelf.key, block, horizontal, rowCards, slotWidth)
        rows += row
        // 分批渲染：这一栏可能是在位置恢复之后才建出来的，横向位置在这里补。
        // 不能直接 `horizontal.scrollTo()`：这一行此刻还没量过宽度，会被夹回 0（见 [restoreRowOffset]）。
        pendingPosition?.horizontal?.get(shelf.key)?.let { x -> restoreRowOffset(row, x, ticket) }
    }

    /**
     * 下一批栏目：**按时间预算切**，不再按「栏数」切。
     *
     * 一栏 11 张卡 × 约 12 个 View ≈ 130 个 View，原先 `HOME_BATCH = 2` 意味着
     * 单帧物化 260+ 个 View（再加 44 个 TextView 首次布局、66 个压暗层 GradientDrawable、
     * 每张卡的柔光位图首次查表），1GB 盒子上单帧 60~150ms。
     *
     * 换成时间预算之后：机器快就一次多建几栏（首屏更快补齐），机器慢就一栏拆成几帧，
     * 首帧永远可控。低内存机型（`TvStyle.animate == false`，见 MainActivity.onCreate）
     * 用更小的预算。
     */
    private fun buildNextBatch(ticket: Int) {
        if (revision != ticket || pendingRows.isEmpty()) return
        // 页面已经被换掉（换标签 / 进详情）：剩下的栏目不再建，避免给一棵离树的视图白干活。
        if (!attachedToWindow) { pendingRows.clear(); return }
        val budget = if (TvStyle.animate) HOME_BATCH_BUDGET_MS else HOME_BATCH_BUDGET_LOW_RAM_MS
        val deadline = android.os.SystemClock.uptimeMillis() + budget
        while (pendingRows.isNotEmpty()) {
            buildShelf(pendingRows.removeFirst(), ticket)
            if (android.os.SystemClock.uptimeMillis() >= deadline) break
        }
        wireFocus()
        // `fitArtwork()` 会把**每一张已建出来的卡**写一次 `layoutParams.height`（一次全页 layout）。
        // 它因此只在「全部栏目都建完」那一帧做，不再每片都做一遍。
        if (pendingRows.isNotEmpty()) {
            loadNearbySelection()
            post { buildNextBatch(ticket) }
        } else {
            fitArtwork()
            loadNearbySelection()
            // 全部栏目都建出来了：再按目标位置摆一次（此时内容高度才是最终值）。
            val settled = pendingPosition
            settled?.let { position ->
                // 只有「用户没动过」才补这一次：内容高度到这一帧才是最终值，之前那次 scrollTo 被夹住了。
                if (scroll.scrollY == restoredScrollY && scroll.scrollY != position.vertical)
                    scroll.scrollTo(0, position.vertical)
                // 后建出来的栏目统一补一次横向：它们建出来时还没量过宽度，
                // 当时那次 `scrollTo` 已经被夹成 0（见 [restoreRowOffset]）。
                rows.forEach { row -> restoreRowOffset(row, position.horizontal[row.key] ?: 0, ticket) }
                rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let { revealSelection(it) }
            }
            pendingPosition = null
            onSettled?.invoke(scroll.scrollY)
        }
    }

    /** 按当前 [rows] 重接四向焦点。分批建栏时每批之后都要重来一次。 */
    private fun wireFocus() {
        rows.forEachIndexed { i, row ->
            row.cards.forEachIndexed { j, card ->
                card.view.nextFocusLeftId = row.cards[(j - 1).coerceAtLeast(0)].view.id
                card.view.nextFocusRightId = row.cards[(j + 1).coerceAtMost(row.cards.lastIndex)].view.id
                card.view.nextFocusUpId =
                    if (i == 0) top.id
                    else rows[i - 1].cards[j.coerceAtMost(rows[i - 1].cards.lastIndex)].view.id
                // 最后一行按下键停在原地：下面已经没有东西了，焦点不会跑到框外。
                card.view.nextFocusDownId =
                    if (i == rows.lastIndex) card.view.id
                    else rows[i + 1].cards[j.coerceAtMost(rows[i + 1].cards.lastIndex)].view.id
            }
        }
        top.nextFocusDownId = firstId()
    }

    /** 第一张卡片的 id；一栏都没有时退到顶部入口，焦点始终有落点。 */
    fun firstId() = rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id

    /** 第一张卡片本身：详情页展开「相关剧集」之后要把焦点落上去。 */
    fun firstCard() = rows.firstOrNull()?.cards?.firstOrNull()?.view

    /** 行内可用宽度 = 屏宽 − 页面左右边距 − 剧架自身左右内边距。 */
    private fun contentWidth() =
        (resources.displayMetrics.widthPixels - 2 * inset - 2 * dp(2)).coerceAtLeast(dp(320))

    /**
     * 与爆米花一致：整行铺满，一屏 6 张竖版海报（约 175×233dp，间距 14dp），
     * 1280dp 画布下与爆米花宫格基本重合。所有分区同一尺寸，行与行之间节奏一致。
     */
    private fun slotWidth(): Int {
        // 卡片自己四周有 SOFT_BAND 的柔光带，行内间距因此收到 10dp，
        // 视觉上的间隔与改版前的 14dp 一致；一行几张由设置里的 [columns] 决定。
        val gap = dp(10)
        val count = columns.coerceIn(4, 8)
        return ((contentWidth() - gap * (count - 1)) / count).coerceAtLeast(dp(90))
    }

    /**
     * 卡片文字（剧名 / 状态 / 类型标签 / 角标）的缩放比例。
     *
     * 以「一行 6 张」的卡片宽度为 1.0：4 列约 1.5 倍、5 列约 1.2 倍，
     * 与封面放大的比例一致。上限 1.45 是为了防止超宽屏上文字过大把两行文字挤成三行；
     * 下限 0.85 是给 7 / 8 列用的：卡片更窄，字号跟着收一点才不会只剩两三个字。
     */
    private fun textScale(width: Int = slotWidth()): Float {
        val gap = dp(10)
        val base = ((contentWidth() - gap * 5) / 6).coerceAtLeast(dp(60))
        return (width.toFloat() / base).coerceIn(0.85f, 1.45f)
    }

    /**
     * 卡片内封面高度：按 134:196 换算，要先扣掉卡片自身的 5dp 内边距，
     * 否则封面会露出底色。
     */
    private fun slotHeight(width: Int): Int {
        // 扣掉三样东西才轮到封面：卡片自己的柔光带（两侧 SOFT_BAND）+ 海报井的 4dp 内衬。
        val inner = (width - dp(2 * TvStyle.SOFT_BAND + 8)).coerceAtLeast(dp(60))
        return (inner / TvStyle.POSTER_ASPECT).toInt()
    }

    /**
     * 右下角的**备用**角标：只有在剧集没有总集数时才用得上（见 `PosterCard.setEpisodeBadge`）。
     *
     * 不再回退到 `series.badge`——那串就是「全 N 集」，而集数现在统一画在右下角，
     * 拿它当备用只会把同一句话写两遍。`entry.badge` 仍保留（详情页用它标注季号之类的场合）。
     */
    private fun badgeFor(entry: Entry): String {
        // 未上架没有集数：右下角画「预告」，否则那一角空着，卡片看起来像数据缺了。
        if (entry.series.upcoming && entry.badge.isBlank()) return "预告"
        val badge = entry.badge.trim()
        if (badge.isBlank()) return ""
        return if (badge.length <= 9) badge else badge.take(8) + "…"
    }

    private fun addCard(
        shelf: Shelf,
        line: LinearLayout,
        rowCards: MutableList<Card>,
        entry: Entry?,
        moreAction: (() -> Unit)?,
        ticket: Int,
        slotWidth: Int
    ) {
        val key = if (entry != null) "${shelf.key}:${entry.series.id}" else "${shelf.key}:more"
        // 行末「查看全部」卡与海报同高，否则会把整行撑高、把后续分区挤出屏幕。
        val height = slotHeight(slotWidth)
        val card =
            if (entry == null) moreCard(key, height)
            else posterCard(key, height, entry, preferStatus = shelf.key == "history")
        val view = card.view
        view.tag = key
        view.setOnFocusChangeListener { _, focused ->
            card.poster?.setFocused(focused)
            card.repaint(focused)
            if (focused) {
                lastFocus = key
                remember(key)
                if (!restoring) {
                    loadNearbySelection()
                    if (lastRow != shelf.key)
                        scroll.post {
                            if (!(revision == ticket && view.hasFocus())) return@post
                            // 低配不做平滑滚动：250ms 里每一帧都要重放整屏 18 张卡
                            // （柔光位图 + 径向暗角 + 封面），而 `reveal` 的目标往往只差几十像素，
                            // 瞬时到位反而更「跟手」。
                            reveal(blockOf(shelf.key), smooth = TvStyle.animate)
                        }
                }
                lastRow = shelf.key
            }
        }
        view.setOnClickListener {
            lastFocus = key
            remember(key)
            card.entry?.let { open(it.series, it.resume) } ?: moreAction?.invoke()
        }
        // 第一栏的卡片按上键：交给宿主去展开页头再落焦点（见 [onHeadUp]）。
        // 首页的卡片本来靠 `nextFocusUpId` 指到页头控件，但**页头收起时那一行高度是 0**，
        // 焦点请求会成功却停在看不见的地方，页头也不会展开——这正是「从收藏页返回首页后
        // 按上键永远出不来页头」的原因。非第一栏按上键是行内移动，不受影响。
        view.setOnKeyListener { _, code, event ->
            if (code != KeyEvent.KEYCODE_DPAD_UP || event.action != KeyEvent.ACTION_DOWN) false
            else {
                val first = rows.firstOrNull() ?: return@setOnKeyListener false
                if (first.key != shelf.key) false
                else {
                    lastFocus = key
                    remember(key)
                    onHeadUp?.invoke() ?: false
                }
            }
        }
        if (entry != null) {
            view.setOnLongClickListener {
                lastFocus = key
                remember(key)
                card.entry?.let { actions(it.series, view, shelf.key) }
                true
            }
            view.setOnKeyListener { target, code, event ->
                when {
                    code == KeyEvent.KEYCODE_MENU -> {
                        if (event.action == KeyEvent.ACTION_UP) {
                            lastFocus = key
                            remember(key)
                            card.entry?.let { actions(it.series, target, shelf.key) }
                        }
                        true
                    }
                    // 行末再按右键直接进二级页，不必先把焦点移到「查看全部」底板再确认。
                    // **只要这一栏确实有底板就接管**（有内容就有底板，见 [Shelf.more]）：
                    // 本机记录那三栏与热播四栏走的是同一条规则，都是右键直达。
                    code == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN &&
                        event.repeatCount == 0 && rowCards.lastOrNull { it.entry != null } === card ->
                        shelves.firstOrNull { it.key == shelf.key }?.more?.let { go ->
                            lastFocus = key
                            remember(key)
                            go()
                            true
                        } ?: false
                    else -> false
                }
            }
        }
        line.addView(view, LinearLayout.LayoutParams(slotWidth, -2).apply { rightMargin = dp(10) })
        rowCards += card
        cards[key] = card
    }

    private fun blockOf(shelfKey: String): View =
        rows.firstOrNull { it.key == shelfKey }?.block ?: contents

    /** 行末「查看全部」卡：与海报卡同一套材质（凸起底板 + 焦点抬升），只是里面没有封面。 */
    private fun moreCard(key: String, height: Int): Card {
        val band = context.softBand()
        val plate = FrameLayout(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            id = View.generateViewId()
            clipChildren = false
            clipToPadding = false
            setPadding(band, band, band, band)
        }
        // 每张底板自己的 Drawable（Drawable 的 bounds 属于使用它的那个 View，不能跨 View 共用），
        // 但焦点切换只在它身上改配色，不再新建。
        val plateBg = TvStyle.soft(
            // heavy = true：与海报卡同一套（一屏十几张、整卡面积），低配同样关掉预渲染柔光位图。
            context, TvStyle.surface, TvStyle.RADIUS_CARD, TvStyle.SOFT_BAND, SoftStyle.RAISED,
            depth = 1.2f, heavy = true
        )
        plate.background = plateBg
        val stack = context.tvColumn().apply { gravity = Gravity.CENTER }
        // 与海报卡同一套比例：4 / 5 列时这张底板也跟着放大，行末不会孤零零地小一号。
        val scale = textScale()
        stack.addView(context.tvText("›", 44f * scale, TvStyle.textDim, bold = true))
        stack.addView(
            context.tvText("查看全部", 14f * scale, TvStyle.textDim),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) }
        )
        plate.addView(stack, FrameLayout.LayoutParams(-1, height, Gravity.CENTER))
        paintMore(plate, plateBg, false)
        return Card(key, plate, null).apply {
            repaint = { focused -> paintMore(plate, plateBg, focused) }
        }
    }

    /**
     * 「查看全部」底板：常态凸起面，焦点换成抬升面 + 更深的柔光 + 光晕（与海报卡同一套）。
     * 2026-10-07 主题改版之后焦点不再画强调色细环；2026-10-18 起海报卡补回了这一圈
     * （见 [PosterCard] 的 applyFocus），这里跟着一致，免得同一行里两种焦点样式。
     */
    private fun paintMore(plate: FrameLayout, background: com.hgtv.star.tv.SoftDrawable, focused: Boolean) {
        // 与海报卡同一条：就地改配色，不新建 Drawable（见 [SoftDrawable.restyle]）。
        background.restyle(
            fill = if (focused) TvStyle.surfaceHigh else TvStyle.surface,
            ring = if (focused) TvStyle.focusRing else Color.TRANSPARENT,
            ringWidth = if (focused) context.tvDp(TvStyle.RING).toFloat() else 0f,
            glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth = if (focused) 1.35f else 1f
        )
        TvStyle.focus(plate, focused)
    }

    private fun posterCard(key: String, height: Int, entry: Entry, preferStatus: Boolean = false): Card {
        val poster = PosterCard(context, height, loadCover, textScale())
        // 右上角季号在所有页面都显示（含详情页的「相关剧集」紧凑模式）：
        // 用户要求「统一」，两种模式不一致会让人以为某一页少了信息。
        // 首页没有名次（榜单页才有），左上角留空；历史栏的第二行优先显示进度。
        poster.bind(entry.series, entry.label, badgeFor(entry), entry.progress, false,
            seasonBadge = true, preferStatus = preferStatus)
        return Card(key, poster, entry).apply {
            this.poster = poster
            image = poster.image
        }
    }

    private fun target(key: String, position: Position?): View {
        cards[key]?.let { return it.view }
        // 纯剧集 id：从详情页返回时 catalogFocus 只记了 id，按它找回同一张卡。
        // 同一部剧可能同时出现在两栏（本机记录 + 热播榜），因此先认位置记下的那一栏，
        // 否则会跳到前面那一栏的同名卡上。
        if (key.isNotBlank()) {
            position?.let { p ->
                rows.firstOrNull { it.key == p.shelf }
                    ?.cards?.firstOrNull { it.entry?.series?.id == key }
                    ?.let { return it.view }
            }
            cards.values.firstOrNull { it.entry?.series?.id == key }?.let { return it.view }
        }
        if (position != null) {
            val row = rows.firstOrNull { it.key == position.shelf }
                ?: rows.getOrNull(position.row.coerceAtMost(rows.lastIndex))
            row?.cards?.getOrNull(position.column.coerceAtMost(row.cards.lastIndex))?.let { return it.view }
        }
        return cards.values.firstOrNull()?.view ?: top
    }

    /**
     * 恢复纵向位置与每一栏的横向位置。
     *
     * **必须重试**：列表刚重建时 `contents` 还没量过高度，而 `ScrollView.scrollTo()` 的上限
     * 是按子控件高度算的（`computeVerticalScrollRange`）——那时上限是 0，位置会被**夹成 0**。
     * 后果不是「只是位置没恢复」这么简单：位置回到顶部、焦点却还停在下面那一行，
     * 于是页头显示着（位置在顶部）、而按上键回到第一行时列表被 reveal 滚动、页头反而收起，
     * 用户看到的就是**状态与位置反过来**。这里等下一帧（布局完成后）再补，最多三次。
     */
    private fun restoreOffsets(position: Position, attempt: Int = 0) {
        rows.forEach { row -> restoreRowOffset(row, position.horizontal[row.key] ?: 0, revision) }
        scroll.scrollTo(0, position.vertical)
        restoredScrollY = scroll.scrollY
        // 「要恢复到非零位置、却仍是 0，而且内容还没量出高度」= 这一下被夹掉了，下一帧再试。
        val clamped = position.vertical > 0 && scroll.scrollY == 0 && contents.height <= 0
        // 横向同理：内容还没量出宽度时 `scrollTo` 会被夹到 0，表现为返回首页后
        // 焦点在第 7–10 张、画面却停在最左边。宽度为 0 的那些行由 [restoreRowOffset]
        // 等布局完成后再摆，这里只重试**已经量过宽度却仍没到位**的行。
        val horizontalPending = rows.any { row ->
            val want = position.horizontal[row.key] ?: 0
            want > 0 && row.horizontal.width > 0 && row.horizontal.scrollX != want
        }
        if ((clamped || horizontalPending) && attempt < 3) {
            val ticket = revision
            scroll.post { if (revision == ticket) restoreOffsets(position, attempt + 1) }
        }
        // 被移除的分区可能把最近的幸存卡片留在旧视口之外；位置没恢复成功时这一步会把
        // 焦点所在的那一行滚进视口（页头因此收起，与位置一致）。
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let { revealSelection(it) }
    }

    /**
     * 把一栏的横向位置摆回去。
     *
     * **不能在还没量过宽度时直接 `scrollTo`**：`HorizontalScrollView.scrollTo()` 的上限
     * 是按它自己的宽度算的，宽度为 0 时会被夹回起点。首页首屏只建前几栏，后面的栏目是
     * 分帧建出来的，它们建出来的那一条消息里也还没有走过布局——直接 `scrollTo` 一律被夹成 0，
     * 返回首页后这一栏就停在 1–6 张（焦点却还在第 7–10 张）。
     *
     * 因此这里**等这一栏真正拿到宽度之后**再摆（`OnLayoutChangeListener`），
     * 并在下一帧补一次（卡片宽度到那一帧才是最终值）。
     */
    private fun restoreRowOffset(row: Row, want: Int, ticket: Int) {
        if (want <= 0) return
        val apply = {
            if (revision == ticket && row.horizontal.width > 0) {
                row.horizontal.scrollTo(want, 0)
                if (row.horizontal.scrollX != want) row.horizontal.post {
                    if (revision == ticket && row.horizontal.width > 0) row.horizontal.scrollTo(want, 0)
                }
            }
        }
        if (row.horizontal.laidOut && row.horizontal.width > 0) apply()
        else row.horizontal.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                if (right - left <= 0) return
                v.removeOnLayoutChangeListener(this)
                apply()
            }
        })
    }

    private fun revealSelection(row: Row, attempt: Int = 0) {
        val card = row.cards.firstOrNull { it.view.hasFocus() }?.view
        val horizontal = row.horizontal
        if (card != null && horizontal.width <= 0) {
            // 宽度还没量出来：这时算出来的右边界是负的，`scrollTo` 会落到一个错的位置。
            // 等下一帧（布局之后）再试一次，别拿错坐标硬摆。
            if (attempt < 2) {
                val ticket = revision
                horizontal.post { if (revision == ticket) revealSelection(row, attempt + 1) }
            }
        } else if (card != null) {
            val left = horizontal.scrollX
            val right = left + horizontal.width - horizontal.paddingRight
            val target = when {
                card.left < left -> card.left
                card.right > right -> card.right - horizontal.width + horizontal.paddingRight
                else -> left
            }
            if (target != left) horizontal.scrollTo(target.coerceAtLeast(0), 0)
        }
        reveal(row.block, false)
    }

    private fun reveal(block: View, smooth: Boolean = true) {
        val target = if (smooth) block.top else when {
            block.top < scroll.scrollY -> block.top
            block.bottom > scroll.scrollY + scroll.height ->
                if (block.height >= scroll.height) block.top else block.bottom - scroll.height
            else -> scroll.scrollY
        }.coerceAtLeast(0)
        if (target != scroll.scrollY) {
            if (smooth) scroll.smoothScrollTo(0, target) else scroll.scrollTo(0, target)
        }
    }

    /**
     * 把「当前这一栏」与「下一栏」的封面都排上队。
     *
     * 首页的剧架是**横排**的（一栏最多 10 张，一屏能看全），因此这里不需要像栅格那样
     * 按行开窗口——「当前栏 ±几列 + 下一栏」就是全部要看的内容。
     *
     * 每一栏自己也会横向滚动（10 张在一屏放不下时会滚），因此当前栏要按列做前后各 6 张的
     * 窗口，而不是只取前几张。这一条是实测逼出来的：早先只取「当前列 ±6」，焦点在栏末时
     * 前面那一截（已经看过的）反而没被覆盖，往左回滚就现取。
     */
    private fun loadNearbySelection() {
        if (restoring) return
        // 低内存时被摘掉的封面：回到视口就挂回来（见 [suspendCoversOutside]）。
        resumeVisibleCovers()
        val row = rows.indexOfFirst { it.cards.any { card -> card.key == lastFocus } }.coerceAtLeast(0)
        val column = rows.getOrNull(row)?.cards?.indexOfFirst { it.key == lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items ->
            for (index in (column - 1).coerceAtLeast(0)..(column + 6).coerceAtMost(items.lastIndex))
                requestCover(items[index])
        }
        // 下一栏整栏都排上（用户按下键就到它了，一栏最多 10 张，排满也不亏）。
        rows.getOrNull(row + 1)?.cards?.forEach(::requestCover)
        // 再下一栏的前半截：按住下键连跳两栏时也不至于空着。
        rows.getOrNull(row + 2)?.cards?.take(6)?.forEach(::requestCover)
    }

    private fun requestCover(card: Card) {
        val image = card.image ?: return
        // 被低内存策略摘掉的卡不在这里重挂：它此刻不在视口里，
        // 回到视口时由 [resumeVisibleCovers] 负责（那条路径才知道「是不是真的看得见」）。
        if (card.poster?.coverSuspended == true) return
        if (card.pending || card.entry?.series?.cover.isNullOrBlank()) return
        val ticket = revision
        fun start() {
            card.pending = false
            if (ticket != revision || !image.attachedToWindow || image.width <= 0 || image.height <= 0) return
            loadCover(image, card.entry!!.series.cover)
        }
        if (image.laidOut && !image.isLayoutRequested) start()
        else {
            card.pending = true
            image.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    image.removeOnLayoutChangeListener(this)
                    start()
                }
            })
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { reloadArtwork() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 只有宽度变化才重新排版封面：高度变化来自顶部工具条收展，跟着重算会让整屏海报抖一下。
        if (w != oldw) post {
            fitArtwork()
            loadNearbySelection()
        }
    }

    /** 屏高不足时按可用高度收窄卡片，保证「整行 + 分区标题」始终完整可见。 */
    private fun fitArtwork(retry: Boolean = true) {
        if (compact || height <= 0) return
        // 标题行还没量到高度时，下面算出来的「可用高度」会偏大（把标题那一段当成了 0），
        // 海报于是先按大的排、下一帧再缩回去——观感就是「画面闪缩一下」。
        // 这一帧跳过、等下一帧量准了再排；只重试一次，量不出来（异常布局）就按当前值排。
        if (retry && rows.any { it.block.getChildAt(0).measuredHeight <= 0 }) {
            post { fitArtwork(false) }
            return
        }
        rows.forEach { row ->
            val heading = row.block.getChildAt(0).measuredHeight
            // 卡片下方两行文字的高度随列数放大（见 [textScale]）：4 列时字号更大，
            // 这里预留的高度也要跟着长，否则海报会被文字挤出可见区域。
            val textBlock = (64 * textScale(row.slotWidth)).toInt()
            val available = (height - heading - dp(textBlock + 2 * TvStyle.SOFT_BAND + 8) - dp(24)).coerceAtLeast(dp(110))
            // 6 列时仍然按「整行 + 标题都能落在屏幕里」收口；
            // 4 / 5 列是用户主动要的大卡片：不再按整屏高度压缩封面，
            // 否则海报会被压回 6 列的高度，只剩文字变大、图没变大（与这个设置的本意相反）。
            val target = if (columns >= 6) minOf(slotHeight(row.slotWidth), available) else slotHeight(row.slotWidth)
            row.cards.forEach { card ->
                card.poster?.resizePoster(target)
            }
        }
    }
}

/**
 * 首屏先建的栏目数。
 *
 * 首页一栏约占 1/3 屏高（标题 + 海报 + 两行文字），3 栏正好铺满首屏、
 * 并且让「第一栏按上键回页头」的焦点链在首帧就是完整的。剩下的栏目在
 * 后续帧补上（见 [HomeScreen.render] 的分批渲染）。
 */
private const val HOME_FIRST_BATCH = 3

/**
 * 分批渲染的**时间预算**（毫秒）：一片最多占主线程这么久。
 *
 * 一栏 ≈ 11 张卡 ≈ 130 个 View；按栏数切无法适应机器差异（快的机器白白多排几帧，
 * 慢的机器单帧就把 260 个 View 堆上去）。按时间切之后上限是确定的。
 */
private const val HOME_BATCH_BUDGET_MS = 6L
/** 低内存机型（`TvStyle.animate == false`）的预算：这类机器建 View 的代价是两三倍。 */
private const val HOME_BATCH_BUDGET_LOW_RAM_MS = 3L
