// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.hgtv.star.shared.ui.SoftStyle

/**
 * 选集单元格：网格里的方块，高度、字号、焦点反馈与按钮共用同一套令牌。
 *
 * 常态是凸起的小面板；当前集是**常亮的亮面板**（和主操作同一档：一屏里那块「本来就是它」的亮色），
 * 焦点是亮面板 + 光晕。
 *
 * 为什么当前集不用「按进去」：网格背板本身就是一块凹陷面板，槽里再凹一层就分不清了；
 * 也不用「抬一档」——3% 的明度差在 20 个格子里读不出来，而「你在看第几集」是这张网格
 * 唯一必须一眼看到的信号。因此它借用主操作那一档（常亮 + 亮面上的字），
 * 与旧版的「金环 + 金字」一样醒目，但不带颜色。
 */
class EpisodeCell(
    context: Context,
    label: String,
    private val current: Boolean,
    action: () -> Unit
) : TextView(context) {
    private val dp = { value: Number -> context.tvDp(value) }

    /** 唯一的底板：焦点 / 当前集只就地改配色（选集面板可能有上百格）。 */
    private val plate = TvStyle.soft(
        context, TvStyle.surface, TvStyle.RADIUS_CARD, TvStyle.SOFT_BAND, SoftStyle.RAISED, depth = 1.2f
    )

    init {
        text = label
        designTextSize(TvStyle.BUTTON_TEXT_NORMAL)
        gravity = Gravity.CENTER
        applyTitleStyle()
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        minimumHeight = dp(TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND)
        background = plate
        paint(false)
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            paint(focused)
            TvStyle.focus(view, focused)
        }
    }

    /**
     * 取消「常规档 60dp」这个高度下限，交给宿主按实测尺寸给高度。
     *
     * 选集网格的格子高度是动态算的（见 `EpisodeGrid.fitCells`）：一页集数多、屏幕窄时
     * 会低于 60dp。`TextView` 测量时会把 `minimumHeight` 顶回来，只改 `LayoutParams` 不够，
     * 因此由网格在创建格子后调这里把下限清掉；字号与左右内边距不变。
     */
    fun allowCompactHeight() {
        minimumHeight = 0
    }

    private fun paint(focused: Boolean) {
        setTextColor(
            when {
                focused -> TvStyle.onLit
                current -> TvStyle.onLit
                else -> TvStyle.text
            }
        )
        plate.restyle(
            fill = when {
                focused || current -> TvStyle.lit
                else -> TvStyle.surface
            },
            glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth = when {
                focused -> 1.2f
                current -> 1.15f
                else -> 1f
            }
        )
    }
}
