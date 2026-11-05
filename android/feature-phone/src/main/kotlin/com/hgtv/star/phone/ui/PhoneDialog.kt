// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.shared.ui.DialogLayout
import com.hgtv.star.shared.ui.SoftStyle

/**
 * 手机端统一弹窗：与电视端 `TvDialog` 同一套结构。
 *
 * 结构固定为「标题 / 正文或选项 / 竖排动作按钮」，面板、选项和按钮都使用同一份
 * 新拟态材质。手机上不画遥控器焦点，其余尺寸、层级与电视端保持一致。
 */
class PhoneDialog(private val activity: Activity, private val title: String) {

    private val landscape = activity.resources.configuration.orientation ==
        Configuration.ORIENTATION_LANDSCAPE

    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = true
        clipToPadding = true
    }

    private val titleView = TextView(activity).apply {
        text = title
        setTextColor(PhoneStyle.text)
        phoneSize(if (landscape) 14 else 17)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(activity.dp(4), 0, activity.dp(4), activity.dp(if (landscape) 6 else 10))
    }

    /** 调用方追加的自定义内容（输入框等）。 */
    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val choices = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val actions = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private val middle = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val middleScroll = CappedScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = true
        clipToPadding = true
        addView(middle, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        visibility = View.GONE
    }

    private var dialog: AlertDialog? = null
    private var windowHost: android.widget.FrameLayout? = null
    private var dismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    init {
        val side = activity.dp(if (landscape) 10 else 14)
        val vertical = activity.dp(if (landscape) 10 else 14)
        body.setPadding(side, vertical, side, vertical)
        body.applySoft(
            SoftStyle.RAISED,
            PhoneStyle.surfaceHigh,
            20f,
            6f,
            ring = (PhoneStyle.focusRing and 0x00FFFFFF) or (0x22 shl 24),
            ringWidthDp = 1f,
        )

        body.addView(titleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        middle.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        middle.addView(choices, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(2) })
        body.addView(middleScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        body.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(if (landscape) 5 else 8) })
    }

    fun message(text: String, color: Int = PhoneStyle.textDim): PhoneDialog {
        content.addView(TextView(activity).apply {
            this.text = text
            setTextColor(color)
            phoneSize(if (landscape) 11 else 13)
            setLineSpacing(activity.dp(if (landscape) 2 else 3).toFloat(), 1f)
            setPadding(activity.dp(4), 0, activity.dp(4), activity.dp(if (landscape) 3 else 6))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            if (content.childCount > 1) topMargin = activity.dp(if (landscape) 3 else 6)
        })
        revealMiddle()
        return this
    }

    /** 往正文区塞一个自定义控件（目前用于导入账号参数的输入框）。 */
    fun content(view: View): PhoneDialog {
        content.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { if (content.childCount > 1) topMargin = activity.dp(6) })
        revealMiddle()
        return this
    }

    /**
     * 一列选项。选中项用按进去的凹槽标出，点任意一项立即收起并回调。
     */
    fun choices(labels: List<String>, selected: Int = -1, onChoice: (Int) -> Unit): PhoneDialog {
        labels.forEachIndexed { index, label ->
            val row = TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                phoneSize(if (landscape) 12 else 14)
                setTextColor(if (index == selected) PhoneStyle.accent else PhoneStyle.text)
                setPadding(activity.dp(if (landscape) 10 else 14), 0, activity.dp(if (landscape) 10 else 14), 0)
                applySoft(
                    if (index == selected) SoftStyle.INSET else SoftStyle.RAISED,
                    PhoneStyle.surface,
                    PhoneStyle.RADIUS_PILL,
                    4f,
                )
                contentDescription = label + if (index == selected) "，当前选择" else ""
                setOnClickListener {
                    dismiss()
                    onChoice(index)
                }
            }
            choices.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(if (landscape) 30 else 38)
            ).apply { bottomMargin = activity.dp(if (landscape) 4 else 6) })
        }
        if (labels.isNotEmpty()) {
            choices.setPadding(0, activity.dp(if (landscape) 3 else 6), 0, 0)
        }
        revealMiddle()
        return this
    }

    fun button(
        label: String,
        primary: Boolean = false,
        danger: Boolean = false,
        run: () -> Unit = {},
    ): PhoneDialog {
        val view = TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            phoneSize(if (landscape) 12 else 14)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            when {
                danger -> {
                    setTextColor(PhoneStyle.tips)
                    applySoft(
                        SoftStyle.RAISED,
                        PhoneStyle.surface,
                        PhoneStyle.RADIUS_PILL,
                        4f,
                        ring = PhoneStyle.tips,
                        ringWidthDp = 1f,
                    )
                }
                primary -> {
                    setTextColor(PhoneStyle.onLit)
                    applySoft(
                        SoftStyle.RAISED, PhoneStyle.lit, PhoneStyle.RADIUS_PILL, 4f
                    )
                }
                else -> {
                    setTextColor(PhoneStyle.text)
                    applySoft(
                        SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f
                    )
                }
            }
            setOnClickListener {
                dismiss()
                run()
            }
        }
        actions.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(if (landscape) 30 else 40)
        ).apply {
            if (actions.childCount > 0) topMargin = activity.dp(if (landscape) 4 else 6)
        })
        return this
    }

    fun show(onDismiss: () -> Unit = {}) {
        if (isShowing) return
        dismissed = onDismiss
        actions.visibility = if (actions.childCount > 0) View.VISIBLE else View.GONE
        // 滚动区的高度预算必须在 `show()` 里算好（见 [scrollBudget]）：
        // 弹窗窗口是 `WRAP_CONTENT`，内容一旦超过窗口能给的最高度，系统就会把窗口夹住，
        // 而面板比窗口高的那一截会被裁掉——读起来就是「上滑下滑都超过弹框」。
        middleScroll.maxHeightPx = scrollBudget()
        val padded = android.widget.FrameLayout(activity).apply {
            setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
            addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        windowHost = padded
        val next = AlertDialog.Builder(activity)
            .setView(padded)
            .create()
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                windowHost = null
                val done = dismissed
                dismissed = null
                done?.invoke()
            }
        }
        dialog = next
        next.show()
        next.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.66f)
            val metrics = activity.resources.displayMetrics
            // 宽度和高度预算由共享层计算：横屏不再按竖屏的 88% 撑满整屏。
            val viewport = DialogLayout.phoneViewport(
                metrics.widthPixels,
                metrics.heightPixels,
                landscape,
                activity.dp(4),
            )
            setLayout(viewport.widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        next.window?.decorView?.post { fitToWindow() }
    }

    /**
     * 滚动区最多能占多高：窗口上限减去「除滚动区以外」的那些固定开销。
     *
     * 早先 `CappedScrollView` 只按「屏高 62%」封顶，完全没算标题、按钮行与面板内边距——
     * 三者加起来可以到 120dp 以上。列表一长，总高就超过窗口能给的高度，窗口被系统夹住，
     * 面板底边连同最后一两行一起被裁在外面。现在改成先扣开销、再定上限。
     */
    private fun scrollBudget(): Int {
        val metrics = activity.resources.displayMetrics
        val viewport = DialogLayout.phoneViewport(
            metrics.widthPixels,
            metrics.heightPixels,
            landscape,
            activity.dp(4),
        )
        // 面板上下内边距、标题、外层 FrameLayout 与按钮行的固定开销。
        var reserve = activity.dp(if (landscape) 10 + 10 + 24 + 8 else 14 + 14 + 44 + 8)
        val buttons = actions.childCount
        if (buttons > 0) {
            val height = if (landscape) 30 else 40
            val gap = if (landscape) 4 else 6
            val actionsTop = if (landscape) 5 else 8
            reserve += activity.dp(height * buttons + gap * (buttons - 1) + actionsTop)
        }
        return DialogLayout.scrollHeightPx(
            viewport.maxHeightPx,
            reserve,
            activity.dp(if (landscape) 24 else 64),
        )
    }

    /** 布局完成后按真实固定区高度再收一次可滚动区，保证底部按钮永远留在窗口内。 */
    private fun fitToWindow() {
        val current = dialog ?: return
        val width = current.window?.attributes?.width?.takeIf { it > 0 } ?: return
        val metrics = activity.resources.displayMetrics
        val viewport = DialogLayout.phoneViewport(
            metrics.widthPixels,
            metrics.heightPixels,
            landscape,
            activity.dp(4),
        )
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        titleView.measure(widthSpec, heightSpec)
        actions.measure(widthSpec, heightSpec)
        val fixed = body.paddingTop + body.paddingBottom +
            titleView.measuredHeight + actions.measuredHeight +
            ((actions.layoutParams as? LinearLayout.LayoutParams)?.topMargin ?: 0) +
            activity.dp(8)
        val cap = DialogLayout.scrollHeightPx(
            viewport.maxHeightPx,
            fixed,
            activity.dp(if (landscape) 24 else 64),
        )
        var scrollCap = cap
        middleScroll.maxHeightPx = scrollCap
        // 关键：窗口不能继续按 WRAP_CONTENT 摆放。先按当前上限量出面板真实高度，
        // 再把窗口高度显式设成这个值；否则长选项列表会超出窗口，底部按钮虽然画在
        // 最后，却会和被裁掉的选项区叠在一起。
        val host = windowHost ?: return
        val hostPadding = host.paddingTop + host.paddingBottom
        val maxBodyHeight = (viewport.maxHeightPx - hostPadding).coerceAtLeast(activity.dp(48))
        var bodyHeight = 0
        repeat(3) {
            middleScroll.maxHeightPx = scrollCap
            val contentWidth = (width - body.paddingLeft - body.paddingRight).coerceAtLeast(1)
            middle.measure(
                View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
                heightSpec,
            )
            val middleHeight = middle.measuredHeight.coerceAtMost(scrollCap)
            val middleParams = middleScroll.layoutParams as? LinearLayout.LayoutParams
            if (middleParams != null && middleParams.height != middleHeight) {
                middleParams.height = middleHeight
                middleScroll.layoutParams = middleParams
            }
            bodyHeight = body.measure(widthSpec, heightSpec).let { body.measuredHeight }
            if (bodyHeight <= maxBodyHeight) return@repeat
            val overflow = bodyHeight - maxBodyHeight
            val nextCap = (scrollCap - overflow).coerceAtLeast(activity.dp(24))
            if (nextCap >= scrollCap) return@repeat
            scrollCap = nextCap
            if (middleParams != null) {
                middleParams.height = 0
                middleScroll.layoutParams = middleParams
            }
        }
        val targetHeight = (bodyHeight + hostPadding).coerceAtMost(viewport.maxHeightPx)
        val targetBodyHeight = (targetHeight - hostPadding).coerceAtLeast(1)
        val bodyParams = body.layoutParams
        if (bodyParams != null && bodyParams.height != targetBodyHeight) {
            bodyParams.height = targetBodyHeight
            body.layoutParams = bodyParams
        }
        val middleParams = middleScroll.layoutParams as? LinearLayout.LayoutParams
        if (middleParams != null && (middleParams.height != 0 || middleParams.weight != 1f)) {
            // 选项区占标题和动作区之间的剩余高度；动作区永远在底部，不会被滚动内容盖住。
            middleParams.height = 0
            middleParams.weight = 1f
            middleScroll.layoutParams = middleParams
        }
        if (current.window?.attributes?.height != targetHeight) {
            current.window?.setLayout(width, targetHeight)
        }
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun revealMiddle() {
        if (middleScroll.visibility == View.VISIBLE) return
        middleScroll.visibility = View.VISIBLE
    }

    /**
     * 正文与选项共用一块可滚区，长列表不会把按钮行顶出屏幕。
     *
     * 上限由 [PhoneDialog.scrollBudget] 在 `show()` 里算好并写进 [maxHeightPx]；
     * 没写（= 0）时才退回「屏高 62%」这个粗上限。
     */
    private class CappedScrollView(context: Context) : ScrollView(context) {

        /** 由宿主按「窗口上限 − 固定开销」算出的像素高度；0 = 用兜底比例。 */
        var maxHeightPx: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = if (maxHeightPx > 0) {
                maxHeightPx
            } else {
                (resources.displayMetrics.heightPixels * .62f).toInt()
            }
            // **必须尊重父容器给的高度**。
            //
            // 早先这里直接把高度量成 `AT_MOST(屏高 62%)`，把父容器的约束整个丢掉：
            // 窗口一旦比「标题 + 62% + 按钮行」矮（矮屏、大字号、界面缩放都够），
            // 这个可滚区仍然按 62% 撑开，整块面板就比窗口高——超出窗口的那一截被裁掉，
            // 读起来正是「上滑下滑都超过弹框」。
            val parentLimit = MeasureSpec.getSize(heightMeasureSpec)
            val limit = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> cap
                else -> minOf(cap, parentLimit)
            }
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST),
            )
        }
    }
}
