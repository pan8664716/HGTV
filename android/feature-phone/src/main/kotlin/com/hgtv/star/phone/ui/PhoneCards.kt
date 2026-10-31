// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.HeatLabel
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
 * │ 第2季  │ │        │ │        │   ← 右上角季号（接口给了才画）
 * │  海报   │ │  海报   │ │  海报   │
 * └────────┘ └────────┘ └────────┘
 *   剧名       剧名       剧名        ← 一行，超出省略
 *   正在看     预计11月6日上线         ← 状态行（没有就不占位）
 * ```
 *
 * 三条与电视端一致的规则：
 * * **不截断**：调用方给多少铺多少，横向滚动看全；
 * * **不过滤正在看的那一部**：它留在原位，状态行写「正在看」——用户一眼知道自己站在哪；
 * * 季号统一由卡片右上角画，不在标题里再写一遍。
 */
object PhoneCards {

    /** 卡片默认宽（dp）；海报按 7:10 竖版铺满它，与详情页头部封面同一档比例。 */
    const val WIDTH_DP = 104

    /**
     * 一张卡片。高度由宽度推出来（7:10），行高因此始终整齐。
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
        // 卡片内部的尺寸走全站设计画布（`PhoneTheme.phoneScale`），不单独乘 density。
        fun dp(value: Number) = context.dp(value)
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            // 按下的反馈：没有它，用户分不清「点到了」还是「点空了」——
            // 这一排卡片在播放页里是浮层上的唯一交互对象，反馈尤其要紧。
            background = PhoneSheet.pillBackground(
                Color.TRANSPARENT,
                0x1AFFFFFF,
                dp(PhoneStyle.RADIUS_CARD).toFloat(),
            )
            setOnClickListener { onOpen(series) }
        }

        val imageBox = FrameLayout(context).apply {
            background = roundedBackground(
                PhoneStyle.well,
                dp(PhoneStyle.RADIUS_CARD).toFloat(),
            )
            clipToOutline = true
        }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        imageBox.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        if (series.seasonIndex > 0) {
            imageBox.addView(TextView(context).apply {
                text = "第${series.seasonIndex}季"
                setTextColor(Color.WHITE)
                phoneSize(10)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = roundedBackground(0xB3000000.toInt(), dp(4).toFloat())
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, dp(4), dp(4), 0) })
        }
        // 左下角热度：只有接口真的给了才出现（这一排多是「相关剧集 / 作者作品」，
        // 作者作品来自搜索接口，带 `rec_text` 解析出来的数字）。
        val heatText = HeatLabel.badge(null, series.heat)
        if (heatText.isNotEmpty()) {
            imageBox.addView(TextView(context).apply {
                text = heatText
                setTextColor(Color.WHITE)
                phoneSize(10)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = roundedBackground(0xB3000000.toInt(), dp(4).toFloat())
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply { setMargins(dp(4), 0, 0, dp(4)) })
        }
        val heightPx = (widthDp * 10 / 7f).toInt()
        cell.addView(imageBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightPx)))
        runCatching {
            artwork.load(image, series.cover, widthPx = dp(widthDp), heightPx = dp(heightPx))
        }

        cell.addView(TextView(context).apply {
            text = series.title
            setTextColor(PhoneStyle.text)
            phoneSize(12)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(6), dp(2), 0)
        })
        val note = when {
            current -> "正在看"
            series.upcoming && series.notice.isNotBlank() -> series.notice
            else -> ""
        }
        if (note.isNotEmpty()) {
            cell.addView(TextView(context).apply {
                text = note
                setTextColor(if (current) PhoneStyle.accent else PhoneStyle.textFaint)
                phoneSize(10)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(2), dp(2), 0)
            })
        }
        return cell
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
                    rightMargin = dp(8)
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
