// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

import android.app.Activity
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.hgtv.star.core.DanmakuStyle
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.tvDp
import com.hgtv.star.tv.tvRow
import com.hgtv.star.tv.tvText

/**
 * 播放器的底部设置面板（遥控器「上」打开）。
 *
 * 贴在播放画面下方约三分之一处：整宽、无卡片边框，只在底部压一层
 * 「透明 → 60% 黑 → 88% 黑」的渐变，让画面自然暗下去，而不是盖一块不透明的浮层。
 *
 * 四个功能竖排，每个功能的选项在它右侧横向铺满整行：
 * 上下在功能之间移动（列号按上一行就近对齐），左右在选项之间移动，
 * 确认立即生效，返回收起。四向都锁在面板内部：停在最后一行再按下键焦点留在原地，
 * 不会跳出去（选集只能从菜单关闭后的下键或底部下拉箭头进入）。
 */
internal class PlaybackMenu(private val activity: Activity) : LinearLayout(activity) {

    /** 一个选项：`label` 是显示文字，`selected` 决定常态底色，`action` 在确认时执行。 */
    internal class Choice(val label: String, var selected: Boolean, val action: () -> Unit)

    private val dp = { value: Number -> activity.tvDp(value) }
    /**
     * 每一行的控件。
     *
     * 大多数行是一排 [Chip]（离散选项），弹幕字号 / 透明度那两行是一个 [DanmakuSlider]
     * （连续量，左右键滑动）。两者都是可聚焦的控件，四向串联只看这一份列表，
     * 因此滑动条行天然和选项行串在同一条上下路径上，不需要额外的特例。
     */
    private val rows = mutableListOf<MutableList<View>>()

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        // 播放界面里的浮层保持原来的样子：透明 → 60% 黑 → 88% 黑的渐变底座，
        // 压在画面上自然暗下去，而不是盖一块不透明的板子。
        // 这份渐变与选集面板共用 TvStyle.overlayPanel()，两处的透明度永远一致。
        background = TvStyle.overlayPanel()
        setPadding(dp(78), dp(24), dp(78), dp(18))
    }

    /** 追加一个功能：左侧项目名，右侧横向排列的选项，等分铺满整行。 */
    fun addRow(title: String, choices: List<Choice>): PlaybackMenu {
        val line = activity.tvRow().apply {
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        // 项目名压在画面上，固定用播放界面的暖白，不跟主题（浅色主题下也要看得清）。
        val label = activity.tvText(title, 18f, TvStyle.playerText, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        line.addView(label, LinearLayout.LayoutParams(dp(110), -2))
        val chips = mutableListOf<Chip>()
        choices.forEachIndexed { index, choice ->
            val chip = Chip(choice)
            chips += chip
            line.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(TvStyle.BUTTON_COMPACT + 2 * TvStyle.SOFT_BAND)).apply {
                    weight = 1f
                    if (index < choices.lastIndex) rightMargin = dp(TvStyle.GAP_TIGHT)
                }
            )
        }
        rows.add(ArrayList<View>(chips))
        addView(line)
        return this
    }

    /**
     * 追加一行「滑动条」：左侧项目名与选项行同宽（标签对齐），右侧一条可滑的控件。
     *
     * 值由 [DanmakuSlider] 自己归一后回调，面板只负责转发，因此这里不持有当前值——
     * 同一个值有两份副本就会出现「面板显示 90%、实际写盘 80%」这种对不上的情况。
     */
    fun addSlider(
        title: String,min: Float,max: Float,step: Float,value: Float,
        format: (Float) -> String = DanmakuStyle::percent,
        onValue: (Float) -> Unit
    ): PlaybackMenu {
        val line = activity.tvRow().apply {
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        val label = activity.tvText(title, 18f, TvStyle.playerText, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        line.addView(label, LinearLayout.LayoutParams(dp(110), -2))
        val slider = DanmakuSlider(activity, title, min, max, step, value, onValue, format)
        line.addView(
            slider,
            LinearLayout.LayoutParams(0, dp(TvStyle.BUTTON_COMPACT + 2 * TvStyle.SOFT_BAND)).apply { weight = 1f }
        )
        rows.add(arrayListOf<View>(slider))
        addView(line)
        return this
    }

    /** 某一行的离散选项（滑动条行没有 Chip，返回空列表）。 */
    private fun chips(row: Int) = rows.getOrNull(row)?.filterIsInstance<Chip>().orEmpty()

    /** 原地重绘：把某个功能的第 [index] 个选项设为选中，同功能其他选项取消选中。 */
    fun select(row: Int, index: Int) {
        val chips = chips(row)
        chips.forEachIndexed { position, chip ->
            chip.choice.selected = position == index
            chip.paint(chip.hasFocus())
        }
    }

    /**
     * 把某一行的选中态同步到 [index]，**不重建任何控件**。
     *
     * 面板被缓存复用时会走到这里（见 `MainActivityPlaybackMenu.kt` 的 [obtainPlaybackMenu]）：
     * 上次打开之后用户可能换过清晰度 / 倍速 / 显示方式，重新挂上去之前要按当前偏好刷一遍。
     * 滑动条行没有 Chip，`chips()` 为空，这一步自然跳过（它的值由 [sliderValue] 同步）。
     */
    fun sync(row: Int, index: Int) {
        val controls = chips(row)
        if (index !in controls.indices) return
        select(row, index)
    }

    /**
     * 把某一行的滑动条拨到 [value]（字号 / 透明度那两行）。
     *
     * 直接写控件自己的 `value`：那个 setter 只重画、不回调 `onValue`，
     * 因此同步显示不会把「打开菜单」变成「改设置」。
     */
    fun sliderValue(row: Int, value: Float) {
        (rows.getOrNull(row)?.firstOrNull() as? DanmakuSlider)?.value = value
    }

    /** 把焦点放到某个功能的第 [index] 个选项。 */
    fun focus(row: Int, index: Int) {
        val controls = rows.getOrNull(row) ?: return
        (controls.getOrNull(index) ?: controls.firstOrNull())?.requestFocus()
    }

    /** 把焦点放到某个功能当前选中的选项上（打开菜单时就是这种状态）。 */
    fun focusSelected(row: Int) {
        val controls = rows.getOrNull(row) ?: return
        // 滑动条行没有「选中项」，直接落在它唯一的那条控件上。
        (chips(row).firstOrNull { it.choice.selected } ?: controls.firstOrNull())?.requestFocus()
    }

    /**
     * 上下在功能之间移动，列号按上一行的下标就近对齐；
     * 左右在选项之间移动，两端停在原地，焦点不会跑出面板；
     * 最后一行按下的 nextFocusDownId 指回自己，因此下键不会把焦点丢给菜单以外的控件。
     */
    fun wire(): PlaybackMenu {
        rows.forEachIndexed { index, chips ->
            val above = rows.getOrNull(index - 1)
            val below = rows.getOrNull(index + 1)
            chips.forEachIndexed { position, chip ->
                chip.nextFocusLeftId = chips[if (position == 0) 0 else position - 1].id
                chip.nextFocusRightId = chips[if (position == chips.lastIndex) position else position + 1].id
                chip.nextFocusUpId = (above?.getOrNull(position.coerceAtMost(above.lastIndex)) ?: chip).id
                chip.nextFocusDownId = (below?.getOrNull(position.coerceAtMost(below.lastIndex)) ?: chip).id
            }
        }
        return this
    }

    /** 一个选项按钮：就是全站按钮的紧凑档，选中态是「按进去」（凹槽 + 内阴影），不带颜色。 */
    private inner class Chip(val choice: Choice) :
        TvButton(activity, choice.label, ButtonSize.COMPACT, ButtonTone.SECONDARY) {
        init {
            setSelectedState(choice.selected)
            describe()
            setOnClickListener { choice.action() }
            onFocus = { describe() }
        }

        /** 选中态由 [select] 改写后重画一次。 */
        fun paint(focused: Boolean) {
            setSelectedState(choice.selected)
            refresh()
            describe()
        }

        private fun describe() {
            contentDescription = choice.label + if (choice.selected) "，已选中" else ""
        }
    }
}
