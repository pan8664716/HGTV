// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv.list
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
import com.hgtv.star.shared.compat.onScrollYChanged
import com.hgtv.star.tv.PosterCard
import com.hgtv.star.tv.SectionHeading
import com.hgtv.star.shared.ui.SoftStyle
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.focusBleed
import com.hgtv.star.tv.softBand
import com.hgtv.star.tv.tvColumn
import com.hgtv.star.tv.tvDp
import com.hgtv.star.tv.tvScrollHost
import com.hgtv.star.tv.tvText
import com.hgtv.star.shared.compat.attachedToWindow
import com.hgtv.star.shared.compat.laidOut
import com.hgtv.star.shared.ui.SoftDrawable

class HomeScreen(
    context: Context,
    private val top: View,
    private val inset: Int,
    private val loadCover: (ImageView, String) -> Unit,
    private val open: (Series, Boolean) -> Unit,
    private val actions: (Series, View, String) -> Unit,
    private val remember: (String) -> Unit,
    private val columns: Int = 6
) : LinearLayout(context) {
    data class Entry(
        val series: Series,
        val label: String,
        val resume: Boolean = false,
        val progress: Int? = null,
        val time: String = "",
        val badge: String = "",
        val heat: String = ""
    )

    data class Shelf(
        val key: String,
        val title: String,
        val entries: List<Entry>,
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

    private fun releaseCards() {
        cards.values.forEach { card -> card.poster?.releaseCover() }
    }

    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        focusBleed()
        onScrollYChanged { y -> if (!restoring) onScroll?.invoke(y) }
    }
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
    private val pendingRows = ArrayDeque<Shelf>()
    private var pendingPosition: Position? = null
    private var restoredScrollY = -1
    private var compact = false

    var onScroll: ((Int) -> Unit)? = null

    var onHeadUp: (() -> Boolean)? = null

    var onSettled: ((Int) -> Unit)? = null

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

    fun refreshEntries(next: List<Shelf>): List<String> {
        val wanted = next.flatMap { shelf -> shelf.entries.map { shelf.key to it } }
        val missing = mutableListOf<String>()
        for ((shelfKey, entry) in wanted) {
            if (updateEntry(shelfKey, entry)) continue
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

    fun cardKeys(): Set<String> = buildSet {
        addAll(cards.keys)
        pendingRows.forEach { shelf ->
            shelf.entries.forEach { add("${shelf.key}:${it.series.id}") }
            if (shelf.more != null) add("${shelf.key}:more")
        }
    }

    fun scrollY(): Int = scroll.scrollY

    fun focusedRowIndex(): Int = rows.indexOfFirst { row -> row.cards.any { it.view.hasFocus() } }

    fun updateShelves(next: List<Shelf>, requestFocus: Boolean = hasFocus()) {
        val position = capturePosition()
        render(next, position.focus, requestFocus, position)
    }

    fun updateEntry(shelfKey: String, entry: Entry): Boolean {
        val card = cards["$shelfKey:${entry.series.id}"] ?: return false
        card.entry = entry
        card.poster?.let { poster ->
            poster.title.text = entry.series.title
            poster.setStatus(entry.label)
            poster.setProgress(entry.progress)
            poster.setCover(entry.series.cover)
            poster.setTags(if(shelfKey=="history") "" else entry.series.tags)
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

    fun reloadArtwork() {
        loadNearbySelection()
    }

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

    fun suspendedCoverCount(): Int = cards.values.count { it.poster?.coverSuspended == true }

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
        val preferred = previous?.focus?.takeIf { it.isNotBlank() }
            ?: focus.ifBlank { previous?.focus.orEmpty() }
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
                if (requestFocus && rows.none { row -> row.cards.any { it.view.hasFocus() } }) target.requestFocus()
                rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let { revealSelection(it) }
                scroll.post {
                    if (revision != ticket) return@post
                    rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let { revealSelection(it) }
                }
                loadNearbySelection()
                onSettled?.invoke(scroll.scrollY)
                scroll.post { if (revision == ticket) onSettled?.invoke(scroll.scrollY) }
            }
        }
    }

    private fun buildShelf(shelf: Shelf, ticket: Int) {
        val block = LinearLayout(context).apply {
            orientation = VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        block.addView(
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
        pendingPosition?.horizontal?.get(shelf.key)?.let { x -> restoreRowOffset(row, x, ticket) }
    }

    private fun buildNextBatch(ticket: Int) {
        if (revision != ticket || pendingRows.isEmpty()) return
        if (!attachedToWindow) { pendingRows.clear(); return }
        val budget = if (TvStyle.animate) HOME_BATCH_BUDGET_MS else HOME_BATCH_BUDGET_LOW_RAM_MS
        val deadline = android.os.SystemClock.uptimeMillis() + budget
        while (pendingRows.isNotEmpty()) {
            buildShelf(pendingRows.removeFirst(), ticket)
            if (android.os.SystemClock.uptimeMillis() >= deadline) break
        }
        wireFocus()
        if (pendingRows.isNotEmpty()) {
            loadNearbySelection()
            post { buildNextBatch(ticket) }
        } else {
            fitArtwork()
            loadNearbySelection()
            val settled = pendingPosition
            settled?.let { position ->
                if (scroll.scrollY == restoredScrollY && scroll.scrollY != position.vertical)
                    scroll.scrollTo(0, position.vertical)
                rows.forEach { row -> restoreRowOffset(row, position.horizontal[row.key] ?: 0, ticket) }
                rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let { revealSelection(it) }
            }
            pendingPosition = null
            onSettled?.invoke(scroll.scrollY)
        }
    }

    private fun wireFocus() {
        rows.forEachIndexed { i, row ->
            row.cards.forEachIndexed { j, card ->
                card.view.nextFocusLeftId = row.cards[(j - 1).coerceAtLeast(0)].view.id
                card.view.nextFocusRightId = row.cards[(j + 1).coerceAtMost(row.cards.lastIndex)].view.id
                card.view.nextFocusUpId =
                    if (i == 0) top.id
                    else rows[i - 1].cards[j.coerceAtMost(rows[i - 1].cards.lastIndex)].view.id
                card.view.nextFocusDownId =
                    if (i == rows.lastIndex) card.view.id
                    else rows[i + 1].cards[j.coerceAtMost(rows[i + 1].cards.lastIndex)].view.id
            }
        }
        top.nextFocusDownId = firstId()
    }

    fun firstId() = rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id

    fun firstCard() = rows.firstOrNull()?.cards?.firstOrNull()?.view

    private fun contentWidth() =
        (resources.displayMetrics.widthPixels - 2 * inset - 2 * dp(2)).coerceAtLeast(dp(320))

    private fun slotWidth(): Int {
        val gap = dp(10)
        val count = columns.coerceIn(4, 8)
        return ((contentWidth() - gap * (count - 1)) / count).coerceAtLeast(dp(90))
    }

    private fun textScale(width: Int = slotWidth()): Float {
        val gap = dp(10)
        val base = ((contentWidth() - gap * 5) / 6).coerceAtLeast(dp(60))
        return (width.toFloat() / base).coerceIn(0.85f, 1.45f)
    }

    private fun slotHeight(width: Int): Int {
        val inner = (width - dp(2 * TvStyle.SOFT_BAND + 8)).coerceAtLeast(dp(60))
        return (inner / TvStyle.POSTER_ASPECT).toInt()
    }

    private fun badgeFor(entry: Entry): String {
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
        val height = slotHeight(slotWidth)
        val card =
            if (entry == null) moreCard(key, height)
            else posterCard(
                key,
                height,
                entry,
                preferStatus = shelf.key == "history",
                hideTags = shelf.key == "history",
            )
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
        val plateBg = TvStyle.soft(
            context, TvStyle.surface, TvStyle.RADIUS_CARD, TvStyle.SOFT_BAND, SoftStyle.RAISED,
            depth = 1.2f, heavy = true
        )
        plate.background = plateBg
        val stack = context.tvColumn().apply { gravity = Gravity.CENTER }
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

    private fun paintMore(plate: FrameLayout, background: com.hgtv.star.shared.ui.SoftDrawable, focused: Boolean) {
        background.restyle(
            fill = if (focused) TvStyle.surfaceHigh else TvStyle.surface,
            ring = if (focused) TvStyle.focusRing else Color.TRANSPARENT,
            ringWidth = if (focused) context.tvDp(TvStyle.RING).toFloat() else 0f,
            glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth = if (focused) 1.35f else 1f
        )
        TvStyle.focus(plate, focused)
    }

    private fun posterCard(
        key: String,
        height: Int,
        entry: Entry,
        preferStatus: Boolean = false,
        hideTags: Boolean = false,
    ): Card {
        val poster = PosterCard(context, height, loadCover, textScale())
        poster.bind(entry.series, entry.label, badgeFor(entry), entry.progress, false,
            seasonBadge = true, heatServerText = entry.heat, preferStatus = preferStatus, hideTags = hideTags)
        return Card(key, poster, entry).apply {
            this.poster = poster
            image = poster.image
        }
    }

    private fun target(key: String, position: Position?): View {
        cards[key]?.let { return it.view }
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

    private fun restoreOffsets(position: Position, attempt: Int = 0) {
        rows.forEach { row -> restoreRowOffset(row, position.horizontal[row.key] ?: 0, revision) }
        scroll.scrollTo(0, position.vertical)
        restoredScrollY = scroll.scrollY
        val clamped = position.vertical > 0 && scroll.scrollY == 0 && contents.height <= 0
        val horizontalPending = rows.any { row ->
            val want = position.horizontal[row.key] ?: 0
            want > 0 && row.horizontal.width > 0 && row.horizontal.scrollX != want
        }
        if ((clamped || horizontalPending) && attempt < 3) {
            val ticket = revision
            scroll.post { if (revision == ticket) restoreOffsets(position, attempt + 1) }
        }
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let { revealSelection(it) }
    }

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

    private fun loadNearbySelection() {
        if (restoring) return
        resumeVisibleCovers()
        val row = rows.indexOfFirst { it.cards.any { card -> card.key == lastFocus } }.coerceAtLeast(0)
        val column = rows.getOrNull(row)?.cards?.indexOfFirst { it.key == lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items ->
            for (index in (column - 1).coerceAtLeast(0)..(column + 6).coerceAtMost(items.lastIndex))
                requestCover(items[index])
        }
        rows.getOrNull(row + 1)?.cards?.forEach(::requestCover)
        rows.getOrNull(row + 2)?.cards?.take(6)?.forEach(::requestCover)
    }

    private fun requestCover(card: Card) {
        val image = card.image ?: return
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
        if (w != oldw) post {
            fitArtwork()
            loadNearbySelection()
        }
    }

    private fun fitArtwork(retry: Boolean = true) {
        if (compact || height <= 0) return
        if (retry && rows.any { it.block.getChildAt(0).measuredHeight <= 0 }) {
            post { fitArtwork(false) }
            return
        }
        rows.forEach { row ->
            val heading = row.block.getChildAt(0).measuredHeight
            val textBlock = (64 * textScale(row.slotWidth)).toInt()
            val available = (height - heading - dp(textBlock + 2 * TvStyle.SOFT_BAND + 8) - dp(24)).coerceAtLeast(dp(110))
            val target = if (columns >= 6) minOf(slotHeight(row.slotWidth), available) else slotHeight(row.slotWidth)
            row.cards.forEach { card ->
                card.poster?.resizePoster(target)
            }
        }
    }
}

private const val HOME_FIRST_BATCH = 3

private const val HOME_BATCH_BUDGET_MS = 6L
private const val HOME_BATCH_BUDGET_LOW_RAM_MS = 3L
