// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv.player
import android.app.Activity
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.hgtv.star.core.DanmakuStyle
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.SettingSlider
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.TvSwitchRow
import com.hgtv.star.tv.tvDp
import com.hgtv.star.tv.tvRow
import com.hgtv.star.tv.tvText

/**
 * 播放器的底部设置面板（遥控器「上」打开）。
 *
 * 贴在同一条屏幕底边上、与选集面板**同高**（[TvStyle.PLAYER_PANEL_HEIGHT]）：整宽、
 * 只圆上缘两角，铺一层**主题材质**的半透明背板（[TvStyle.sheet]，与选集、相关剧集同一份）。
 * 早先这里是一条「透明 → 60% 黑 → 88% 黑」的黑渐变，浅色主题下与按钮的凸 / 凹 / 亮三档对不上。
 *
 * 四个功能竖排，每个功能的选项在它右侧横向铺满整行：
 * 上下在功能之间移动（列号按上一行就近对齐），左右在选项之间移动，
 * 确认立即生效，返回收起。四向都锁在面板内部：停在最后一行再按下键焦点留在原地，
 * 不会跳出去（选集只能从菜单关闭后的下键或底部下拉箭头进入）。
 *
 * 最上面一行是**并列的动作**（相关剧集 / 作者作品 / 加入收藏 / 查看简介，见 [addActions]）：
 * 它没有项名列、按钮等分整行，与下面每一行同高同档，下方一条分隔线与设置项断开。
 * 放在第一行：这一排是「按下去就去做一件事」，不该等用户一路下键滚到底才看得见。
 */
class PlaybackMenu(private val activity: Activity) : LinearLayout(activity) {

    /** 一个选项：`label` 是显示文字，`selected` 决定常态底色，`action` 在确认时执行。 */
    class Choice(val label: String, var selected: Boolean, val action: () -> Unit)

    /**
     * 一枚**动作**按钮（不是「一个功能的某个档位」）：按下去就去做一件事，
     * 没有选中态、文案可以被改（「加入收藏」⟷「已收藏」）。
     */
    class Action(var label: String, val action: () -> Unit)

    private val dp = { value: Number -> activity.tvDp(value) }
    /**
     * 每一行的控件。
     *
     * 大多数行是一排 [Chip]（离散选项），弹幕字号 / 透明度 / 画面缩放那三行是一个
     * [SettingSlider]（连续量，左右键滑动）。两者都是可聚焦的控件，四向串联只看这一份列表，
     * 因此滑动条行天然和选项行串在同一条上下路径上，不需要额外的特例。
     */
    private val rows = mutableListOf<MutableList<View>>()

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        // 浮层背板与选集面板、相关剧集共用 [TvStyle.sheet]：主题抬升面 + 只圆上缘两角。
        // 播放界面过去铺的是黑渐变，浅色主题下那块冷黑与按钮的凸 / 凹 / 亮三档都对不上。
        // 压在画面上，因此取更透的那一档（[TvStyle.SHEET_ALPHA_VIDEO]）与直角（`radius = 0`），
        // 与选集面板一致：两块浮层整宽贴底，圆角会在上沿切出两个缺口。
        background = TvStyle.sheet(activity, alpha = TvStyle.SHEET_ALPHA_VIDEO, radius = 0)
        setPadding(dp(78), dp(24), dp(78), dp(18))
    }

    /** 追加一个功能：左侧项目名，右侧横向排列的选项，等分铺满整行。 */
    fun addRow(title: String, choices: List<Choice>): PlaybackMenu {
        val line = activity.tvRow().apply {
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        // 项目名跟着主题走：浮层已经是主题材质，这里再用播放界面的暖白，
        // 浅色主题下就会变成「白字压在浅灰面板上」。
        val label = activity.tvText(title, 18f, TvStyle.textStrong, bold = true).apply {
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
     * 追加一行**并列的动作按钮**（相关剧集 / 作者作品 / 加入收藏 / 查看简介）。
     *
     * 与 [addRow] 的区别只有两处：左边不留项名列（这一行不是「一个功能的几个档位」，
     * 而是四件平级的事），按钮等分整行宽度。高度、间距、档位与上面每一行完全一致，
     * 因此它读起来仍然是同一条菜单里的一行，而不是突然冒出来的一块工具栏。
     *
     * 这一行是**面板的第一行**（见 `buildPlaybackMenu`）：面板高度固定、超出的行靠滚动，
     * 挂在末尾时它永远是「要一路下键滚到底」才看得见的那一行。
     */
    fun addActions(actions: List<Action>): PlaybackMenu {
        val line = activity.tvRow().apply {
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        val buttons = mutableListOf<TvButton>()
        actions.forEachIndexed { index, action ->
            val button = ActionButton(action)
            buttons += button
            line.addView(
                button,
                LinearLayout.LayoutParams(0, dp(TvStyle.BUTTON_COMPACT + 2 * TvStyle.SOFT_BAND)).apply {
                    weight = 1f
                    if (index < actions.lastIndex) rightMargin = dp(TvStyle.GAP_TIGHT)
                }
            )
        }
        rows.add(ArrayList<View>(buttons))
        addView(line)
        // 与下面那些「设置项」之间画一条分隔线：这一行不是另一个档位，而是四件平级的事。
        // 用主题的分隔色（见 [TvStyle.separator]），只负责断开，不抢注意力。
        addView(
            View(activity).apply { setBackgroundColor(TvStyle.separator) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10) }
        )
        return this
    }

    /**
     * 改某一行动作按钮的文案（「加入收藏」⟷「已收藏」），**不重建任何控件**。
     *
     * 面板是缓存复用的，收藏状态可能在别处被改过（简介页收藏、卡片长按菜单），
     * 因此每次挂上去之前都按本机当前状态刷一遍，见 `syncPlaybackMenu`。
     */
    fun actionLabel(row: Int, index: Int, label: String) {
        val button = rows.getOrNull(row)?.getOrNull(index) as? ActionButton ?: return
        if (button.action.label == label) return
        button.action.label = label
        button.paint()
    }

    /**
     * 追加一行「滑动条」：**与 [addRow] 同一个骨架**——左边一条 110dp 的项名列，右边一枚
     * [SettingSlider]，只画轨道与读数（`showName = false`）。
     *
     * 早先这里是「整行一枚滑动条、项名画在底板里」：项名左边缘比上一行缩进一条柔光带，
     * 轨道起点又按各自的项名实测宽度算——「弹幕大小」「弹幕透明度」「画面缩放」三个名字
     * 长短不同，三条轨道于是从三个不同的位置起头，也都不在上一行的选项列上，整块面板看起来
     * 是歪的（用户反馈）。现在项名与选项列都沿用 [addRow] 的那两个位置：
     * 项名贴面板内容边距，底板与轨道从 110dp 起，与上面每一行的选项列对齐。
     *
     * 轨道两端仍然各自独立算（左端贴底板内边距、右端让开固定宽度的读数区，见 [SettingSlider]），
     * 因此读数跨过一位数时轨道也不会伸缩。
     *
     * 值由控件自己归一后回调，面板只负责转发，因此这里不持有当前值——
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
        // 项名列与 [addRow] 完全一致：同宽、同字号、同一条竖线。
        val label = activity.tvText(title, 18f, TvStyle.textStrong, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        line.addView(label, LinearLayout.LayoutParams(dp(110), -2))
        // 档位与上面几行的紧凑档选项对齐（52dp 高）；不画内部项名，轨道因此从底板内边距起算。
        val slider = SettingSlider(
            activity, title, min, max, step, value, onValue, format,
            size = ButtonSize.COMPACT, showName = false
        )
        line.addView(slider, LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
        rows.add(arrayListOf<View>(slider))
        addView(line)
        return this
    }

    /** 追加一行整行开关：左侧项名，右侧自绘轨道。 */
    fun addSwitch(title: String, checked: Boolean, onToggle: (Boolean) -> Unit): PlaybackMenu {
        val control = TvSwitchRow(activity, title, checked, onToggle)
        rows.add(arrayListOf(control))
        addView(control, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
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
     * 面板被缓存复用时会走到这里（见 `player/PlaybackMenuController.kt` 的 [obtainPlaybackMenu]）：
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
        (rows.getOrNull(row)?.firstOrNull() as? SettingSlider)?.value = value
    }

    /** 把某一行的开关同步到 [checked]，不触发点击回调。 */
    fun switchValue(row: Int, checked: Boolean) {
        (rows.getOrNull(row)?.firstOrNull() as? TvSwitchRow)?.setChecked(checked)
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

    /**
     * 一枚动作按钮：与选项同一档（紧凑档、次级色），但没有选中态。
     *
     * 底色 / 焦点放大 / 点击都由 [TvButton] 自己负责，这里只把文案与动作接上；
     * 文案随时可能被 [actionLabel] 改掉（收藏状态翻转），因此画的时候现读 [Action.label]。
     */
    private inner class ActionButton(val action: Action) :
        TvButton(activity, action.label, ButtonSize.COMPACT, ButtonTone.SECONDARY) {
        init {
            describe()
            setOnClickListener { action.action() }
            onFocus = { describe() }
        }

        fun paint() {
            text = action.label
            describe()
        }

        private fun describe() {
            contentDescription = action.label
        }
    }
}
