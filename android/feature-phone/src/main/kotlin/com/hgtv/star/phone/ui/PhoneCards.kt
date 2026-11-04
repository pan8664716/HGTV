// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.hgtv.star.core.PosterCardContent
import com.hgtv.star.core.Series
import com.hgtv.star.shared.data.ArtworkLoader

/**
 * 「相关剧集 / 作者作品」那一排卡片：**详情页与播放页共用这一份**。
 *
 * 电视端这件事只有一个实现（`MainActivity.buildRelatedRow` → `HomeScreen`），
 * 因此详情页的「相关剧集」与播放器的「换一部接着看」看起来完全一样。
 * 手机上早先是各写各的：详情页有卡片，播放页只有一列剧名文字（还截断到 12 条），
 * 用户读到的就是「播放页里那几行字孤零零的」。现在同样收成一处：
 *
 * ```
 * ┌────────┐ ┌────────┐ ┌────────┐
 * │ 新剧    │ │        │ │        │   ← 左上角系统标签
 * │  海报   │ │  海报   │ │  海报   │
 * │ 第2季  │ │ 第1季  │ │ 第1季  │   ← 右上角季号（缺省第 1 季）
 * │ 🔥9014万│ │        │ │        │   ← 左下角热度
 * │        │ │        │ │ 共99集 │   ← 右下角集数
 * └────────┘ └────────┘ └────────┘
 *   剧名       剧名       剧名        ← 一行，超出省略
 *   类型标签                    状态   ← 状态可为「正在看」/上线时间
 * ```
 *
 * 三条与电视端一致的规则：
 * * **不截断**：调用方给多少铺多少，横向滚动看全；
 * * **不过滤正在看的那一部**：它留在原位，状态行写「正在看」——用户一眼知道自己站在哪；
 * * 季号统一由卡片右上角画，不在标题里再写一遍。
 */
object PhoneCards {

    /**
     * 卡片默认宽（dp）；直接复用网格卡的竖版海报与文字层级。
     *
     * 从 104 加到 116：横排卡片原先只有 312px（1080P），缩略图认不出是哪部剧；
     * 加宽后与网格卡的观感接近，一屏仍能露出两张半。
     */
    const val WIDTH_DP = 116

    /** 手机横屏播放页底部浮层用的小卡宽：避免卡片过高被底部面板裁切。 */
    const val COMPACT_WIDTH_DP = 84

    /**
     * 一张卡片。与分类 / 榜单 / 搜索页的网格卡共用 [PhonePosterCard]。
     *
     * [onOpen] 是点击回调：详情页进详情，播放页直接换一部接着看（见各自的调用处）。
     */
    fun card(
        context: Context,
        artwork: ArtworkLoader,
        series: Series,
        current: Boolean = false,
        widthDp: Int = WIDTH_DP,
        onOpen: (Series) -> Unit,
    ): View {
        val content = PosterCardContent.of(
            series = series,
            statusText = when {
                current -> "正在看"
                series.upcoming -> series.notice
                else -> ""
            },
            badgeFallback = if (series.upcoming) "预告" else "",
        )
        val card = PhonePosterCard(
            context = context,
            cardWidth = context.dp(widthDp),
            artwork = artwork,
            onOpen = onOpen,
        )
        card.bind(series, content)
        return card
    }

    /**
     * 一整排卡片（横向滚动，不显示滚动条）。
     *
     * [currentId] 是正在看的那一部：它不会从列表里被去掉，只是状态行写「正在看」。
     */
    fun row(
        context: Context,
        artwork: ArtworkLoader,
        items: List<Series>,
        currentId: String,
        widthDp: Int = WIDTH_DP,
        onOpen: (Series) -> Unit,
    ): HorizontalScrollView {
        fun dp(value: Number) = context.dp(value)
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { item ->
            strip.addView(
                card(context, artwork, item, current = item.id == currentId, widthDp = widthDp, onOpen = onOpen),
                LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    // 卡片加宽之后间距同步收一档，否则一屏露出的张数掉得太快。
                    rightMargin = dp(4)
                }
            )
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // 首尾卡片不贴着容器边缘被裁掉：浮层里那一排左右各留 2dp 的「出血」。
            clipToPadding = false
            addView(strip, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }
}
