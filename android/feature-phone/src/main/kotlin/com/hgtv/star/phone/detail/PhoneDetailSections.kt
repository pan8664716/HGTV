// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.Detail
import com.hgtv.star.core.Series
import com.hgtv.star.phone.ui.PhoneCards
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground

internal fun PhoneDetailActivity.prefetchRelated(s: Series) {
    detailController.ensureRelated(s.id, s.title)
}

internal fun PhoneDetailActivity.renderRelated() {
    val s = detail?.series
    relatedBox.removeAllViews()
    if (s == null || (!relatedLoading && !relatedFailed && relatedItems.isEmpty())) {
        relatedBox.visibility = View.GONE
        return
    }
    relatedBox.visibility = View.VISIBLE
    val status = when {
        relatedLoading -> "正在查找…"
        relatedFailed -> "加载失败，点此重试"
        else -> "共 ${relatedItems.size} 部"
    }
    val heading = if (relatedSeasons > 1) "同系列 ${relatedSeasons} 季 · 相关剧集" else "相关剧集"
    relatedBox.addView(sectionHead(heading, status, relatedFailed) {
        prefetchRelated(s)
    })
    if (relatedItems.isNotEmpty()) {
        relatedBox.addView(cardRow(relatedItems, s.id), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
}

internal fun PhoneDetailActivity.prefetchAuthorWorks(s: Series) {
    detailController.ensureAuthorWorks(s)
}

internal fun PhoneDetailActivity.renderAuthorWorks() {
    val s = detail?.series
    authorBox.removeAllViews()
    if (s == null || authorFor.isBlank()) {
        authorBox.visibility = View.GONE
        return
    }
    authorBox.visibility = View.VISIBLE
    val name = authorFor
    val items = listOf(s) + authorItems.filter { it.id != s.id }

    val head = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(16), dp(2), dp(8))
    }
    val avatar = TextView(this).apply {
        text = name.take(1)
        setTextColor(Color.WHITE)
        phoneSize(24)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        background = roundedBackground(authorColor(name), dp(15).toFloat())
        contentDescription = name
    }
    head.addView(avatar, LinearLayout.LayoutParams(dp(30), dp(30)))
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), 0, 0, 0)
    }
    texts.addView(TextView(this).apply {
        text = name
        setTextColor(PhoneStyle.text)
        phoneSize(15)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    val hint = when {
        authorLoading -> "正在查找该作者的作品…"
        authorFailed -> "没能取到该作者的作品，点此重试"
        items.size <= 1 -> "这一位暂时只有这一部作品"
        else -> "共 ${items.size} 部作品"
    }
    texts.addView(TextView(this).apply {
        text = hint
        setTextColor(if (authorFailed) PhoneStyle.accent else PhoneStyle.textFaint)
        phoneSize(11)
        setPadding(0, dp(3), 0, 0)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (authorFailed) setOnClickListener { prefetchAuthorWorks(s) }
    })
    head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    authorBox.addView(head, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))

    authorBox.addView(cardRow(items, s.id), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))
}

private fun PhoneDetailActivity.sectionHead(
    title: String,
    status: String,
    retry: Boolean,
    onRetry: () -> Unit,
): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(16), dp(2), dp(6))
    }
    row.addView(TextView(this).apply {
        text = title
        setTextColor(PhoneStyle.textDim)
        phoneSize(12)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(TextView(this).apply {
        text = status
        setTextColor(if (retry) PhoneStyle.accent else PhoneStyle.textFaint)
        phoneSize(11)
        if (retry) {
            setPadding(dp(8), dp(2), dp(2), dp(2))
            setOnClickListener { onRetry() }
        }
    })
    return row
}

private fun PhoneDetailActivity.cardRow(items: List<Series>, currentId: String): View {
    val base = library?.gridColumns ?: 2
    val columns = base.coerceIn(1, 3) + if (landscape) 3 else 0
    val available = (resources.displayMetrics.widthPixels - contentEdge * 2).coerceAtLeast(dp(200))
    return PhoneCards.row(
        this,
        PhoneServices.artwork(this),
        items,
        currentId,
        columns = columns,
        availableWidthPx = available,
    ) { item ->
        openDetail(item)
    }
}

private fun PhoneDetailActivity.authorColor(name: String): Int =
    com.hgtv.star.shared.ui.authorColor(name)

internal fun PhoneDetailActivity.sameDetailContent(a: Detail, b: Detail): Boolean =
    a.episodes == b.episodes && a.upcoming == b.upcoming && a.reserved == b.reserved &&
        a.series.copy(author = "") == b.series.copy(author = "")

internal fun PhoneDetailActivity.loadDetailAuthor(current: Detail) {
    detailController.loadAuthor(current)
}

internal fun PhoneDetailActivity.paintMeta(d: Detail) {
    val s = d.series
    val meta = ArrayList<String>(4)
    if (s.seasonIndex > 0) meta += "第${s.seasonIndex}季"
    if (d.upcoming) meta += "尚未上线"
    else if (d.episodes.isNotEmpty()) meta += "全${d.episodes.size}集"
    if (s.author.isNotBlank()) meta += s.author
    metaView.text = meta.joinToString("  ·  ")
    metaView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
}

internal fun PhoneDetailActivity.reservedState(d: Detail): Boolean {
    val lib = library ?: return d.reserved
    return runCatching {
        if (lib.reservedKnown(d.series.id)) lib.reserved(d.series.id) else d.reserved
    }.getOrDefault(d.reserved)
}
