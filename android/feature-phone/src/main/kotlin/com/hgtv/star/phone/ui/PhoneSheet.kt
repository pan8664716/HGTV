// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 播放界面上的浮层（播放设置 / 选集共用一套壳）。
 *
 * | 形态 | 位置与尺寸 |
 * | --- | --- |
 * | 竖屏 | 底部整宽、直角 |
 * | 横屏 | 底部整宽、限高、直角 |
 *
 * 横屏也不再铺成整高右抽屉：选集/设置内容多时右侧整高会显得过大，
 * 底部限高浮层更接近 TV 端，网格放不下时只在面板内部滚动。
 */
class PhoneSheet(private val host: FrameLayout) {

    private var scrim: View? = null
    private var sheet: View? = null
    private var notifyHostOnUserDismiss = true

    var isOpen = false
        private set

    /** 任何一次收起（含程序性收起，例如换了一集）。 */
    var onDismiss: (() -> Unit)? = null

    /** 面板内任意一次按下：宿主据此把自动收起计时从这一次操作重新算起。 */
    var onInteraction: (() -> Unit)? = null

    /**
     * **用户主动收起**：点空白处 / 点右上角「关闭」。
     *
     * 为什么要和 [onDismiss] 分开：换集、换清晰度、转屏时是程序性收起，那一刻控制层
     * 该留着（用户还在看这一页）；而用户点空白处的语义是「让开，我要看画面」——
     * 与电视端菜单打开时收起 HUD、关闭后画面干净是同一条。只 dismiss 面板、
     * 把标题 / 集号 / 进度条留在屏幕上，正是用户报的「点空白处还残留所有信息」。
     */
    var onUserDismiss: (() -> Unit)? = null

    /**
     * 面板底部的额外留白（px）。
     *
     * 播放页的浮层贴着画面底，`0` 就够；而从**主界面**（分类 / 榜单 / 搜索 / 我的）弹出的浮层
     * 是挂在 decor 上的，会一直贴到屏幕最下沿，被系统导航条压住最后一行。
     * 调用方传 `navigationBarPx()` 即可，横屏与手势导航（没有导航条）时它本来就是 0。
     */
    var bottomInset: Int = 0

    /**
     * 打开浮层。[body] 往里塞内容，[title] 是标题行。
     *
     * [dim] 为 false 时不压暗画面：电视端的「换一部接着看」就是这种浮层
     * （`RelatedPanel` 把 `dimAmount` 归零），上方正在放的那一帧保持原样，
     * 可读性交给面板自己的背板。播放设置 / 选集仍然要压暗（用户此刻是在操作，不是在观看）。
     */
    fun show(
        title: String,
        landscape: Boolean,
        dim: Boolean = true,
        /**
         * 面板**整体**最多占屏高的比例（含标题行与内边距，横竖屏共用）。
         *
         * 播放设置通常传 1/3 到 1/2：那一屏底下还在放视频，面板铺到 90% 就等于把画面关掉了；
         * 限高 + 内部滚动，既能看到当前几项，也不挡画面。
         * 选集面板传默认值（内容多，需要更大）。
         */
        heightFraction: Float = .78f,
        /**
         * 点空白 / 点「关闭」时是否通知宿主一起收起控制层。
         *
         * 选集是播放页上的独立浮层：用户点空白只想收起选集，标题、进度条和返回键应继续留着。
         */
        notifyHostOnUserDismiss: Boolean = true,
        body: (LinearLayout) -> Unit,
    ) {
        dismiss()
        this.notifyHostOnUserDismiss = notifyHostOnUserDismiss
        // 与全站同一块设计画布：不读 density（见 `PhoneTheme.phoneScale`）。
        fun dp(value: Number) = host.context.dp(value)

        val panel = object : LinearLayout(host.context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) onInteraction?.invoke()
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            // 面板本身吃掉空白处的点击：否则点在两行之间会穿到背后的遮罩上、误收面板。
            isClickable = true
            // 横屏改成底部整宽后，左右留白仍收得比竖屏小，给选集 10 列留出空间。
            // 底部**不留导航条高度的空带**：面板是一张贴底的材料，
            // 底下多一截纯色 padding 读起来就是「面板没铺到底」。
            setPadding(
                dp(if (landscape) 10 else 16), dp(10),
                dp(if (landscape) 10 else 16), dp(12) + bottomInset
            )
            background = sheetBackground()
        }

        val head = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(host.context).apply {
            text = title
            setTextColor(PhoneStyle.text)
            phoneSize(if (landscape) 15 else 16)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(host.context).apply {
            text = "关闭"
            setTextColor(PhoneStyle.textDim)
            phoneSize(13)
            setPadding(dp(8), dp(5), 0, dp(5))
            // 关闭也要有按下的反馈：它是最常点的一个，没有反馈会让人以为点空了。
            setOnClickListener { dismissByUser() }
            background = pressOnly(dp(6f).toFloat())
        })
        panel.addView(head)

        // 内容放进 ScrollView：横屏可用高度只有三四百 dp，九行设置各两行选项会顶出屏幕。
        val inner = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }
        body(inner)
        // 竖屏要**限高**：面板是 WRAP_CONTENT 贴底，内容比屏幕高时整块会往上溢出屏幕，
        // 顶部那几行（标题、倍速）直接看不见，而且 ScrollView 自己也没有可滚的余量——
        // 这正是「显示不全、也滑不动」。限高之后，多出来的部分由滚动承担。
        // 减掉标题行与上下内边距：比例说的是**整块面板**，不是滚动区。
        val chrome = dp(10) + dp(12) + bottomInset + dp(34)
        val cap = (screenHeight() * heightFraction).toInt().minus(chrome).coerceAtLeast(dp(96))
        val scroller = CappedScrollView(host.context, cap).apply {
            addView(inner, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        panel.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 空白背板：既是「点空白处收起」的落点，也是面板外面那一片的触摸拦截。
        //
        // **不能用 `setOnClickListener`**：`View` 的点击判定只检查手指有没有移出控件边界，
        // 背板是整屏的，左右滑一下手指仍在界内，抬手就会被判成一次点击——面板被收起，
        // 用户看到的是「一滑设置就没了」。这里自己做点击判定：位移超过 touchSlop 就不算点击，
        // 同时把所有事件都吃掉，横向滑动不会漏到底下的 pager 上。
        val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        val shade = View(host.context).apply {
            // 不压暗时仍然要有一层透明背板：它是「点空白处收起」的落点，
            // 同时也是面板外面那一片的触摸拦截（否则会点到底下的播放控件上）。
            setBackgroundColor(if (dim) 0x4D000000 else 0x00000000)
            isClickable = true
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        onInteraction?.invoke()
                    }
                    MotionEvent.ACTION_UP -> {
                        if (Math.abs(e.x - downX) <= touchSlop && Math.abs(e.y - downY) <= touchSlop) {
                            dismissByUser()
                        }
                    }
                }
                true
            }
        }
        host.addView(shade, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        host.addView(panel, lp)
        scrim = shade
        sheet = panel
        isOpen = true
    }

    fun dismiss() {
        if (!isOpen) return
        (scrim?.let { host.removeView(it) })
        (sheet?.let { host.removeView(it) })
        scrim = null
        sheet = null
        isOpen = false
        onDismiss?.invoke()
    }

    /** 用户点空白 / 点关闭：先收面板，再通知宿主（宿主据此把控制层一并收掉）。 */
    fun dismissByUser() {
        if (!isOpen) return
        val notifyHost = notifyHostOnUserDismiss
        dismiss()
        if (notifyHost) onUserDismiss?.invoke()
    }

    private fun screenHeight(): Int =
        host.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels

    /**
     * 限高滚动容器：`maxPx <= 0` 时与普通 ScrollView 完全一样。
     *
     * 只改测量：内容不超过 [maxPx] 时高度就是内容高度（面板看起来还是一张贴底的小卡），
     * 超过就停在 [maxPx] 并变成可滚动——两种情形共用同一份布局，不用两套代码。
     */
    private class CappedScrollView(context: Context, private val maxPx: Int) : ScrollView(context) {
        /**
         * 面板可能挂在会滚动的宿主里（推荐信息流外面套着竖向 RecyclerView）。
         * 从手指按下起就沿父链锁住拦截，设置列表的竖向滑动才不会被外层翻页吃掉。
         */
        private fun lockParentIntercept(lock: Boolean) {
            var parent = parent
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(lock)
                parent = parent.parent
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> lockParentIntercept(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> lockParentIntercept(false)
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> lockParentIntercept(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> lockParentIntercept(false)
            }
            return super.onTouchEvent(ev)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (maxPx <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST)
            )
        }
    }

    /** 面板底：播放界面的浮层统一使用直角，贴边时边界与画面完全对齐。 */
    private fun sheetBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // 跟着主题走（二十套配色）：写死石墨色时，浅色主题下面板会是一块突兀的黑。
            setColor((PhoneStyle.surface and 0x00FFFFFF) or (0xF2 shl 24))
            cornerRadius = 0f
            setStroke(1, 0x1AFFFFFF)
        }

    companion object {
        /**
         * 一行**动作**（不是选项）：左侧标题 + 说明，右侧一个箭头。
         *
         * 与设置页那些「点进去再做点什么」的入口同一套视觉：选项行是「一排按钮」，
         * 动作行是「这一行本身可以按」。两类混在一列里，用户一眼就能分清哪种按下去会换页。
         */
        fun actionRow(host: View, title: String, hint: String, onTap: () -> Unit): LinearLayout {
            val context = host.context
            val dp = { value: Number -> context.dp(value) }
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4))
                isClickable = true
                setOnClickListener { onTap() }
                background = pillBackground(Color.TRANSPARENT, 0x14FFFFFF, dp(8).toFloat())
            }
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(context).apply {
                text = title
                setTextColor(PhoneStyle.text)
                phoneSize(14)
            })
            column.addView(TextView(context).apply {
                text = hint
                setTextColor(PhoneStyle.textFaint)
                phoneSize(12)
            })
            box.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(PhoneGlyph(context, PhoneGlyph.Kind.CHEVRON).apply {
                tint = PhoneStyle.textDim
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            return box
        }

        /**
         * 一行设置：左侧项目名，右侧一排选项。
         *
         * 选项**点下去立刻换高亮**（[paint] 在点击时先跑一遍），再回调业务。
         * 之前只回调业务、不重画：用户看到的是「点了没反应，但画面确实变了」。
         *
         * [perLine] > 0 时按每行固定个数折行（横屏底部浮层里，六档倍速一行放不下）；
         * 0 表示全部排在一行、等分铺满。
         */
        fun row(
            host: View,
            title: String,
            labels: List<String>,
            selected: Int,
            perLine: Int = 0,
            onPick: (Int) -> Unit,
        ): LinearLayout {
            val context = host.context
            val dp = { value: Number -> context.dp(value) }
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            box.addView(TextView(context).apply {
                text = title
                setTextColor(PhoneStyle.textDim)
                phoneSize(13)
            })

            val chips = ArrayList<TextView>()
            val radius = dp(8).toFloat()

            fun paint(index: Int) {
                chips.forEachIndexed { i, chip ->
                    val on = i == index
                    chip.background = choiceBackground(on, radius)
                    chip.setTextColor(if (on) Color.WHITE else PhoneStyle.textDim)
                    chip.isSelected = on
                    chip.contentDescription = chip.text.toString() + if (on) "，已选中" else ""
                }
            }

            fun makeLine(from: Int, to: Int): LinearLayout {
                val line = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                for (i in from until to) {
                    val chip = TextView(context).apply {
                        text = labels[i]
                        phoneSize(13)
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(6), dp(5), dp(6), dp(5))
                        setOnClickListener {
                            // 先换高亮再执行业务：立刻可见，不必等重取地址 / 重建面板。
                            paint(i)
                            onPick(i)
                        }
                    }
                    chips += chip
                    line.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > from) leftMargin = dp(5)
                    })
                }
                return line
            }

            if (perLine <= 0 || labels.size <= perLine) {
                box.addView(makeLine(0, labels.size), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            } else {
                var from = 0
                while (from < labels.size) {
                    val to = minOf(from + perLine, labels.size)
                    box.addView(makeLine(from, to), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (from == 0) dp(5) else dp(3) })
                    from = to
                }
            }

            paint(selected.coerceIn(0, labels.lastIndex.coerceAtLeast(0)))
            return box
        }

        /**
         * 一行滑动条：上行「项名 —— 读数」，下行是可拖的 SeekBar。
         *
         * 与 TV 的 `SettingSlider` 对应（那边是遥控器一格一格拨，这边是手指拖），
         * 区间、步长与读数格式完全一致，落盘也是同一份值。
         */
        fun sliderRow(
            host: View,
            title: String,
            min: Float,
            max: Float,
            step: Float,
            value: Float,
            format: (Float) -> String,
            onChange: (Float) -> Unit,
        ): LinearLayout {
            val context = host.context
            val dp = { v: Number -> context.dp(v) }
            val steps = ((max - min) / step).toInt().coerceAtLeast(1)
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(context).apply {
                text = title
                setTextColor(PhoneStyle.textDim)
                phoneSize(13)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val readout = TextView(context).apply {
                text = format(value)
                setTextColor(PhoneStyle.accent)
                phoneSize(13)
            }
            head.addView(readout)
            box.addView(head)
            box.addView(android.widget.SeekBar(context).apply {
                this.max = steps
                progress = ((value - min) / step).toInt().coerceIn(0, steps)
                setPadding(0, dp(2), 0, 0)
                if (android.os.Build.VERSION.SDK_INT >= 21) runCatching {
                    progressTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
                    thumbTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
                    progressBackgroundTintList =
                        android.content.res.ColorStateList.valueOf(PhoneStyle.wellDeep)
                }
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                        val next = min + progress * step
                        readout.text = format(next)
                        if (fromUser) onChange(next)
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return box
        }

        /** 选项底：选中 = 强调色；按下 = 比常态亮一档。两态都有，点哪一下都看得见。 */
        private fun choiceBackground(selected: Boolean, radius: Float): StateListDrawable {
            val normal = roundedBackground(if (selected) PhoneStyle.accent else PhoneStyle.surface, radius)
            val pressed = roundedBackground(
                if (selected) 0xFF7CBEFF.toInt() else 0xFF3D4652.toInt(), radius
            )
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }
        }

        /**
         * 通用「常态 + 按下」两态底。
         *
         * 播放界面上的胶囊按钮（倍速 / 清晰度 / 选集、选集数字格）都用它：
         * 这些按钮点下去会立刻换页或弹面板，没有按下态时用户分不清「点到了」还是「点空了」。
         */
        fun pillBackground(normal: Int, pressed: Int, radius: Float): StateListDrawable {
            val rest = roundedBackground(normal, radius)
            val down = roundedBackground(pressed, radius)
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), down)
                addState(intArrayOf(), rest)
            }
        }

        /** 只带按下态、常态透明的底（关闭按钮这种文字控件用）。 */
        private fun pressOnly(radius: Float): StateListDrawable {
            val normal = roundedBackground(Color.TRANSPARENT, radius)
            val pressed = roundedBackground(0x26FFFFFF, radius)
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }
        }
    }
}
