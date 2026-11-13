// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.hgtv.star.shared.compat.vectorDrawable
import com.hgtv.star.shared.ui.SoftDrawable
import com.hgtv.star.shared.ui.SoftStyle

/**
 * 弹窗里的选项行（设计稿的 `.mitem`）：整行胶囊，左边一枚线性图标，右边文字。
 *
 * 与页面上的 [TvButton] 刻意不同，因为弹窗是一块独立的浮层：
 *
 * - **常态是「压暗的」**：底板与面板同色（只有一圈柔光把它从面板上托起来），
 *   文字压到 44% 不透明度。一列选项因此读起来是一组安静的候选，而不是六个抢眼的按钮。
 * - **焦点是「按进去 + 亮起来」**：底板换成凹陷槽，外面补一圈焦点色细环与外发光，
 *   文字回到正文色，图标跟着字色一起亮。焦点在哪儿不需要看第二眼。
 * - **危险项**（删除 / 清空）常态是暗红，聚焦时是亮红 + 红环，与普通项一眼分得开。
 *
 * 图标与文字画在**同一个控件**里：图标不是 compound drawable（那样在居中 / 左对齐切换时
 * 会贴在内容区边缘），而是像 [TvButton] 一样按「图标 + 间距 + 文字」整块排版，
 * 因此焦点放大、点击区域、`contentDescription` 都只有一份。
 */
class DialogItem(
    context: Context,
    label: String,
    private val iconRes: Int? = null,
    private val danger: Boolean = false,
    private val alignStart: Boolean = true,
    /**
     * 危险项换成**红色描边**的外观：常态就是一圈红环 + 红字红图标，而不是「暗红字 + 普通面板」。
     *
     * 给「使用须知」这类**前置声明**用：那枚按钮不是删除动作，但用户按下它就是在承担风险，
     * 因此要一眼看出它的分量。普通危险项（删除 / 清空）仍然走 [danger] 那一档——
     * 它们出现在列表里，满屏红环反而会盖过内容。
     */
    private val outlined: Boolean = false,
    private val action: () -> Unit = {}
) : TextView(context) {
    private val dp = { value: Number -> context.tvDp(value) }
    private val band = context.softBand()
    private val pill = TvStyle.RADIUS_PILL
    private var selected = false
    private var enabledState = true
    /** 凸 / 凹两块底板，复用（见 `paintButton` 的 `plates`）。 */
    private val plates = arrayOfNulls<SoftDrawable>(2)
    private var icon: Drawable? = null
    private var iconGap = 0

    init {
        text = label
        gravity = if (alignStart) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // 列表型选项（左对齐）文案过长时：**拿到焦点**的那一项跑马灯，其余项省略号收尾。
        //
        // 居中的动作按钮（取消 / 关闭 / 确认）不能开单行滚动：Layout 会按「很宽的一行」量，
        // 居中文字起点落到控件外，按钮就变成「只剩图标、没有字」。
        if (alignStart) {
            setSingleLine(true)
            marqueeRepeatLimit = -1
        }
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        iconRes?.let { res ->
            val drawable = context.vectorDrawable(res)?.mutate()
            if (drawable != null) {
                val side = dp(TvStyle.DIALOG_ITEM_ICON)
                drawable.setBounds(0, 0, side, side)
                icon = drawable
                iconGap = dp(TvStyle.DIALOG_ITEM_ICON_GAP)
            }
        }
        paint(false)
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            paint(focused)
            TvStyle.focus(view, focused)
            marquee(focused)
        }
    }

    /**
     * 焦点进 / 出时切换「滚动 / 省略号」。
     *
     * 居中的动作按钮（`alignStart = false`）不跑马灯：单行滚动的 Layout 是按
     * 「很宽的一行」建的，居中排版的文字起点会落到控件外——表现就是「按钮在、字没了」。
     */
    private fun marquee(focused: Boolean) {
        if (!alignStart) return
        isSelected = false
        ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
        if (focused) {
            isSelected = true
            // 复用 / 重复获得焦点时 isSelected 没变化，TextView 不会重新开始滚：拉回行首。
            scrollTo(0, 0)
        }
    }

    /** 当前值那一项：常驻的凹陷槽（不带焦点环），与聚焦态区分开。 */
    fun setSelectedState(value: Boolean) {
        if (selected == value) return
        selected = value
        paint(hasFocus())
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        enabledState = enabled
        isFocusable = enabled
        paint(hasFocus())
    }

    private fun paint(focused: Boolean) {
        val ctx = context
        designTextSize(TvStyle.DIALOG_ITEM_TEXT)
        setPadding(dp(TvStyle.DIALOG_ITEM_PAD) + band, band, dp(TvStyle.DIALOG_ITEM_PAD) + band, band)
        minimumHeight = dp(TvStyle.DIALOG_ITEM_HEIGHT + 2 * TvStyle.SOFT_BAND)
        setTypeface(typeface, Typeface.NORMAL)
        // 三档状态与页面上每一枚按钮同源，颜色全部取自当前主题（2026-10-27 弹窗配色改版）：
        //   常态（凸起） = surface      —— 比弹窗面板的 surfaceHigh 低一档，按钮才从面板上浮出来；
        //   焦点（打亮） = lit + onLit  —— 与页面按钮的焦点同一档，深色主题是亮面近黑字、浅色主题是白面深墨字；
        //   选中（按进去） = selectedWell + focusRing —— 与设置页的主题芯片、全站的开关同一档。
        // 早先常态直接取 surfaceHigh（与面板完全同色），按钮只剩下 3–5% 的明度差贴在面板上，
        // 焦点又用「凹陷」表达，于是「选中」和「焦点」长得一模一样——这正是弹窗看起来不像本应用的原因。
        setTextColor(
            when {
                !enabledState -> TvStyle.muted
                // 亮面板上的字：危险项用状态色（红底上够清楚），其余用亮面的近黑字。
                focused -> if (danger || outlined) TvStyle.danger else TvStyle.onLit
                outlined -> TvStyle.danger
                danger -> TvStyle.danger
                selected -> TvStyle.text
                else -> TvStyle.textDim
            }
        )
        // 描边项**永远**用凸起那块底板：空心按钮没有「按进去」这一档，
        // 而 INSET 底板会把内阴影画在透明填充上，看起来像一块脏掉的凹陷。
        // 焦点**不再**走凹陷（凹陷在按钮上读不出「焦点」，只剩一片比面板更深的槽），
        // 因此只有「选中且没有焦点」才需要那块 INSET 底板。
        val useInset = selected && !focused && enabledState && !outlined
        val plate = plates[if (useInset) 1 else 0] ?: run {
            val made = when {
                useInset -> TvStyle.well(ctx, TvStyle.well, pill, TvStyle.SOFT_BAND, depth = 1.15f)
                // 描边项只画那一圈环：RAISED 会在空心按钮四周留一圈投影，
                // 看起来像一块「被挖空的凸起面板」，与「边框按钮」的语义相反。
                outlined -> TvStyle.soft(ctx, Color.TRANSPARENT, pill, TvStyle.SOFT_BAND, SoftStyle.RING, depth = 1f)
                else -> TvStyle.soft(ctx, TvStyle.surface, pill, TvStyle.SOFT_BAND, SoftStyle.RAISED, depth = 1.1f)
            }
            plates[if (useInset) 1 else 0] = made
            made
        }
        val ringColor = when {
            !enabledState -> Color.TRANSPARENT
            // 描边危险项：红环常驻，聚焦时换成更亮的那一档红。
            outlined -> if (focused) brighten(TvStyle.danger) else dim(TvStyle.danger, .80f)
            focused -> if (danger) TvStyle.danger else TvStyle.focusRing
            selected -> if (danger) dim(TvStyle.danger, .85f) else TvStyle.focusRing
            else -> Color.TRANSPARENT
        }
        // 描边项的环宽也常驻（聚焦时再粗一档），「边框按钮」这个形态才立得住。
        val ringWidth = when {
            !enabledState -> 0f
            outlined -> ctx.designPx(if (focused) TvStyle.RING else 1).toFloat()
            focused || selected -> ctx.designPx(TvStyle.RING).toFloat()
            else -> 0f
        }
        plate.restyle(
            fill = when {
                !enabledState -> TvStyle.wellDeep
                // 描边项常态**不填底色**：只留一圈红环，看起来才是「空心描边按钮」。
                // 聚焦时补一层极淡的红：空心按钮没有「打亮」那一档，不给一点填充的话
                // 焦点只剩一圈变粗的红环，在深色面板上不够明显。
                outlined -> if (focused) dim(TvStyle.danger, .18f) else Color.TRANSPARENT
                useInset -> TvStyle.selectedWell
                // 焦点 = 打亮的那一档（与页面按钮一致）；常态 = 凸起面，比弹窗面板低一档。
                focused -> TvStyle.lit
                else -> TvStyle.surface
            },
            ring = ringColor,
            ringWidth = ringWidth,
            glow = when {
                focused && (danger || outlined) -> dim(TvStyle.danger, .30f)
                focused -> TvStyle.litGlow
                else -> Color.TRANSPARENT
            },
            depth = when {
                useInset -> 1.15f
                focused -> 1.25f
                else -> 1.1f
            }
        )
        if (background !== plate) background = plate
        alpha = if (enabledState) 1f else TvStyle.DISABLED_ALPHA
        icon?.setColorFilter(currentTextColor, PorterDuff.Mode.SRC_IN)
    }

    /** 图标不是 compound drawable（见类注释）：自己量宽、自己画，与文字整块对齐。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val glyph = icon ?: return
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) return
        val extra = glyph.bounds.width() + iconGap
        if (extra <= 0) return
        val desired = measuredWidth + extra
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST)
            minOf(desired, MeasureSpec.getSize(widthMeasureSpec)) else desired
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        val glyph = icon
        if (glyph == null) {
            super.onDraw(canvas)
            return
        }
        val contentWidth = (width - paddingLeft - paddingRight).toFloat()
        val textWidth = layout?.let { if (it.lineCount > 0) it.getLineWidth(0) else 0f } ?: 0f
        val glyphWidth = glyph.bounds.width().toFloat()
        val block = glyphWidth + iconGap + textWidth
        if (textWidth <= 0f) {
            super.onDraw(canvas)
            return
        }
        // 放不下「图标 + 间距 + 文字」时：图标照旧留在左边，文字在**它右边**那段里
        // 由 TextView 自己滚（MARQUEE）或省略。直接退回纯文字会把图标整枚吞掉，
        // 一列选项里就那一项没有图标，看起来像少了什么。
        if (block > contentWidth && alignStart) {
            val shift = glyphWidth + iconGap
            val top = (height - glyph.bounds.height()) / 2f
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), top)
            glyph.draw(canvas)
            canvas.restore()
            canvas.save()
            canvas.clipRect(paddingLeft + shift, 0f, width - paddingRight.toFloat(), height.toFloat())
            canvas.translate(shift, 0f)
            super.onDraw(canvas)
            canvas.restore()
            return
        }
        if (block > contentWidth) {
            super.onDraw(canvas)
            return
        }
        if (alignStart) {
            // 左对齐：文字由 TextView 正常画，图标占掉前面那段，再把文字右移。
            // 裁在文字自己那一段里：跑马灯滚动时文字不会跑到图标下面去。
            val shift = glyphWidth + iconGap
            canvas.save()
            canvas.clipRect(paddingLeft + shift, 0f, width - paddingRight.toFloat(), height.toFloat())
            canvas.translate(shift, 0f)
            super.onDraw(canvas)
            canvas.restore()
            val top = (height - glyph.bounds.height()) / 2f
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), top)
            glyph.draw(canvas)
            canvas.restore()
        } else {
            // 居中：图标 + 文字整体居中（与 TvButton 的做法一致）。
            // 同样裁在内容区里：跑马灯滚动时文字不会画到图标上。
            val shift = (glyphWidth + iconGap) / 2f
            canvas.save()
            canvas.clipRect(paddingLeft.toFloat(), 0f, width - paddingRight.toFloat(), height.toFloat())
            canvas.translate(shift, 0f)
            super.onDraw(canvas)
            canvas.restore()
            val left = paddingLeft + (contentWidth - block) / 2f
            val top = (height - glyph.bounds.height()) / 2f
            canvas.save()
            canvas.translate(left, top)
            glyph.draw(canvas)
            canvas.restore()
        }
    }

    private fun dim(color: Int, ratio: Float) = (color and 0x00FFFFFF) or
        (((ratio * 255f).toInt().coerceIn(0, 255)) shl 24)

    /** 危险项聚焦时的「亮红」：往白色提一档，深色面板上才亮得起来。 */
    private fun brighten(color: Int) = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * .45f).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * .45f).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * .45f).toInt()
    )
}
