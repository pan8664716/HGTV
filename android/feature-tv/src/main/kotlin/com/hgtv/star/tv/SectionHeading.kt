// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * 分区标题：一行粗体文字，从内容线开始。
 *
 * 2026-10-12 起标题前不再有强调色圆点：整页的节奏交给「标题—剧架」的间距本身，
 * 标题只留字，不再带任何装饰（`hint` 仍是可选的一段灰字说明）。
 *
 * 标题行也不挂「更多 ›」文字入口：剧架本身的行末已经有「查看全部」底板，
 * 行内按右键也直达集合页，标题再写一次是重复的。
 */
class SectionHeading(
    context: Context,
    title: String,
    hint: String = ""
) : LinearLayout(context) {
    private val dp = { value: Number -> context.tvDp(value) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, dp(18), 0, dp(10))
        addView(context.tvText(title, 24f, TvStyle.textStrong, bold = true), LinearLayout.LayoutParams(-2, -2))
        if (hint.isNotBlank())
            addView(
                context.tvText(hint, 14f, TvStyle.muted),
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) }
            )
        addView(View(context), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
    }
}
