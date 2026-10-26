// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.app.Activity
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「手机号 + 验证码」登录表单的视觉件：输入框、字段说明、按钮行。
 *
 * 全部按全站的新拟态规范做：凹陷槽 + 胶囊圆角 + 焦点加深，与搜索框 / 手填 sessionid 那一条
 * 是同一套外观；输入走**系统键盘**（手机号与验证码都是普通 `EditText`，不再自绘数字键盘）。
 *
 * 高度都按最紧档位算：弹窗内容区最多占屏幕一半（720p 是 360dp），
 * 这一页是「两行输入 + 一行说明 + 一行按钮」，1080p / 720p 都放得下。
 */
internal object LoginForm {
    /** 输入框的可见高度（设计稿 64），加两侧柔光带。 */
    const val FIELD_HEIGHT = TvStyle.LOGIN_FIELD_HEIGHT

    /**
     * 一个带标签的输入框（标签在框上方的小字，占位符在框里）。
     *
     * [numeric] 决定键盘类型：手机号与验证码都只要数字，用 `TYPE_CLASS_NUMBER` 直接给数字键盘；
     * 验证码另外关掉自动填充建议（`TYPE_TEXT_FLAG_NO_SUGGESTIONS`），免得弹出「保存密码」那一类提示。
     */
    fun field(
        activity: Activity,
        label: String,
        hint: String,
        numeric: Boolean,
        maxLength: Int,
        /**
         * 手机号输入框：键盘换成「电话」型，允许 `+`、空格、横线。
         *
         * 手机号不再只按中国大陆的 11 位数字收：很多地区的号码习惯带国家区号写
         *（`+852 9123 4567`）。纯数字键盘敲不出 `+`，用户就只能猜「要不要把区号去掉」；
         * 电话键盘把 `+` 放出来，写法与用户手机上一致，归一（去掉非数字）在提交时做。
         */
        phoneStyle: Boolean = false
    ): EditText {
        val band=activity.tvDp(TvStyle.SOFT_BAND)
        val view=EditText(activity).apply {
            id=View.generateViewId()
            this.hint=hint
            designTextSize(TvStyle.LOGIN_INPUT_TEXT)
            setTextColor(TvStyle.text); setHintTextColor(TvStyle.muted)
            isSingleLine=true; maxLines=1
            ellipsize=TextUtils.TruncateAt.END
            inputType=when {
                phoneStyle -> InputType.TYPE_CLASS_PHONE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            filters=arrayOf(InputFilter.LengthFilter(maxLength))
            includeFontPadding=false
            // 内边距按常规档按钮（28dp）+ 柔光带：槽里左右各留出投影的位置，文字才不会贴着边。
            setPadding(activity.tvDp(TvStyle.LOGIN_INPUT_PAD)+band,band,
                activity.tvDp(TvStyle.LOGIN_INPUT_PAD)+band,band)
            background=fieldBackground(activity,false)
            setOnFocusChangeListener { view,focused ->
                view.background=fieldBackground(activity,focused)
            }
            contentDescription=label
        }
        return view
    }

    /** 输入框的底：凹陷槽 + 描边；焦点态描边换成主题焦点色并加深一档。 */
    private fun fieldBackground(activity: Activity,focused: Boolean) =
        TvStyle.soft(activity,TvStyle.well,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,
            SoftStyle.INSET,ring=if(focused) TvStyle.focusLine else TvStyle.outline,
            ringWidth=if(focused) TvStyle.RING else 1,depth=if(focused) 1.6f else 1.1f)

    /**
     * 字段上方的小字标签 + 输入框 + 可选的一行说明，装成一条竖列。
     *
     * 标签用小字（13sp）而不是占位符：输入之后占位符就没了，用户回头看不出这一栏填的是什么。
     */
    fun fieldRow(
        activity: Activity,
        label: String,
        field: EditText,
        note: TextView? = null
    ): LinearLayout {
        val column=activity.tvColumn().apply {
            clipChildren=false
            clipToPadding=false
        }
        column.addView(
            activity.tvText(label,13f,TvStyle.muted),
            LinearLayout.LayoutParams(-1,-2)
        )
        column.addView(
            field,
            LinearLayout.LayoutParams(-1,activity.tvDp(FIELD_HEIGHT+2*TvStyle.SOFT_BAND))
                .apply { topMargin=activity.tvDp(TvStyle.GAP_TIGHT) }
        )
        if(note!=null) {
            column.addView(
                note,
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=activity.tvDp(TvStyle.GAP_TIGHT) }
            )
        }
        return column
    }

    /**
     * 「整行输入框 + 右侧按钮」的一行（例如 `[手机号__________] [获取验证码]`）。
     *
     * 标签仍在输入框上方，两行的框**等宽等高**：按钮占的是右边那一条固定宽度，
     * 因此上面那一行（带按钮的）与下面那一行（不带按钮的验证码框）宽度会差一截。
     * 为了两行看起来是一对，这里把按钮宽度也算进列宽——
     * 调用方给不带按钮的那一行传同样的 [buttonWidth]，由一条等宽的空白占位补齐。
     */
    fun fieldRowWithButton(
        activity: Activity,
        label: String,
        field: EditText,
        button: TvButton?,
        buttonWidth: Int,
        /** 输入框高度：弹窗里与弹窗按钮同档，页面表单用常规档。 */
        fieldHeight: Int = FIELD_HEIGHT
    ): LinearLayout {
        val column=activity.tvColumn().apply {
            clipChildren=false
            clipToPadding=false
        }
        column.addView(activity.tvText(label,13f,TvStyle.muted),LinearLayout.LayoutParams(-1,-2))
        val row=LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            clipChildren=false
            clipToPadding=false
        }
        row.addView(field,LinearLayout.LayoutParams(0,activity.tvDp(fieldHeight+2*TvStyle.SOFT_BAND),1f))
        if(button!=null) {
            row.addView(button,LinearLayout.LayoutParams(buttonWidth,-2).apply {
                leftMargin=activity.tvDp(TvStyle.GAP_INLINE)
            })
        } else {
            // 空占位：让两行的输入框右边缘对齐（下面那一行没有按钮，但框宽要一样）。
            row.addView(View(activity),LinearLayout.LayoutParams(buttonWidth,1).apply {
                leftMargin=activity.tvDp(TvStyle.GAP_INLINE)
            })
        }
        column.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=activity.tvDp(TvStyle.GAP_TIGHT) })
        return column
    }

    /** 一行说明文字（12sp、次级色）：用来交代规则或错误原因。 */
    fun note(activity: Activity,text: String): TextView =
        activity.tvText(text,12f,TvStyle.textDim).apply { id=View.generateViewId() }

    /** 错误提示那一行：强调色，字号与说明一致。 */
    fun error(activity: Activity,text: String = ""): TextView =
        activity.tvText(text,12f,TvStyle.focusLine).apply { id=View.generateViewId() }

    /**
     * 上行短信那一步要展示的「收件号码 / 短信内容」：两块白底大字的可读卡片。
     *
     * 这两串是要用户**照着发**的，因此用等宽字体、字号比正文大一档、居中，
     * 并且各自带一行小标题——用户一眼要能分清哪个是发给谁、哪个是发什么。
     */
    fun smsCard(activity: Activity,label: String,value: String): LinearLayout {
        val band=activity.tvDp(TvStyle.SOFT_BAND)
        val card=activity.tvColumn().apply {
            background=TvStyle.soft(activity,TvStyle.surfaceHigh,TvStyle.RADIUS_CARD,TvStyle.SOFT_BAND,
                SoftStyle.RAISED,depth=1.05f)
            setPadding(activity.tvDp(TvStyle.GAP_BLOCK)+band,activity.tvDp(TvStyle.GAP_INLINE)+band,
                activity.tvDp(TvStyle.GAP_BLOCK)+band,activity.tvDp(TvStyle.GAP_INLINE)+band)
            clipChildren=false
            clipToPadding=false
        }
        card.addView(activity.tvText(label,12f,TvStyle.muted))
        card.addView(
            activity.tvText(value,22f,TvStyle.text).apply {
                typeface=android.graphics.Typeface.MONOSPACE
                if(android.os.Build.VERSION.SDK_INT>=21) letterSpacing=0.06f
                gravity=Gravity.CENTER
                setPadding(0,activity.tvDp(TvStyle.GAP_TIGHT),0,0)
            },
            LinearLayout.LayoutParams(-1,-2)
        )
        return card
    }

    // —— 登录卡（设计稿：居中一张大圆角面板） ——

    /**
     * 登录卡本体：一块居中的大圆角面板（圆角 32），四周留出柔光带。
     *
     * 卡片里从上到下是「小号 logo → 大标题 → 一行说明 → 两个字段 → 一条整宽主按钮」，
     * 与设计稿同一条竖向节奏。调用方只负责往里塞内容，内边距与材质都由这里决定。
     */
    fun card(activity: Activity): LinearLayout {
        val band = activity.tvDp(TvStyle.SOFT_BAND_LARGE)
        return activity.tvColumn().apply {
            background = TvStyle.soft(
                activity, TvStyle.surfaceHigh, TvStyle.RADIUS_LOGIN_CARD, TvStyle.SOFT_BAND_LARGE,
                SoftStyle.RAISED, depth = 1.5f
            )
            setPadding(
                activity.tvDp(TvStyle.LOGIN_PAD_SIDE) + band,
                activity.tvDp(TvStyle.LOGIN_PAD_TOP) + band,
                activity.tvDp(TvStyle.LOGIN_PAD_SIDE) + band,
                activity.tvDp(TvStyle.LOGIN_PAD_BOTTOM) + band
            )
        }
    }

    /**
     * 卡片顶部：小号字母标识 + 大标题 + 一行说明。
     *
     * 标识行用主题强调色、字距拉开（设计稿的 `letter-spacing: 5px`），
     * 字号只有 12——它是这块面板的「标签」，不跟标题抢。
     */
    fun header(card: LinearLayout, title: String, subtitle: String, logo: String = "HGTV") {
        val activity = card.context as Activity
        val dp = { value: Number -> activity.tvDp(value) }
        if (logo.isNotBlank()) {
            card.addView(
                activity.tvText(logo, TvStyle.LOGIN_LOGO_TEXT, TvStyle.focus, bold = true).apply {
                    if (android.os.Build.VERSION.SDK_INT >= 21) letterSpacing = .38f
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            )
        }
        // 标题与说明**同一行**：`登录  使用手机号登录，同步你的收藏与观看记录`。
        // 说明单独占一行时，卡片顶上会多出一条空行的高度，标题也显得孤零零；
        // 并排之后标题仍是主角，说明贴在它右侧、按次文字色，读起来是「一句标题 + 一句补充」。
        val line = activity.tvRow().apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        line.addView(
            activity.tvText(title, TvStyle.LOGIN_TITLE_TEXT, TvStyle.text, bold = true),
            LinearLayout.LayoutParams(-2, -2)
        )
        if (subtitle.isNotBlank()) {
            line.addView(
                activity.tvText(subtitle, TvStyle.LOGIN_SUB_TEXT, TvStyle.muted).apply {
                    // 与标题基线对齐：标题是 30 号粗体，说明是 14 号，底对齐才像同一行写下来的。
                    setPadding(0, 0, 0, dp(4))
                },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) }
            )
        }
        card.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
    }

    /** 字段上方的小标签（13 号、次文字色、带一点左右内缩）。 */
    fun label(activity: Activity, text: String): TextView =
        activity.tvText(text, TvStyle.LOGIN_LABEL_TEXT, TvStyle.muted).apply {
            setPadding(activity.tvDp(10), 0, activity.tvDp(10), activity.tvDp(10))
        }

    /**
     * 登录主按钮：整宽、68 高、胶囊圆角、强调色底 + 近黑字。
     *
     * 与页面上的 [TvButton] 不同，它是这块面板里唯一的主操作，因此用强调色实心，
     * 焦点时补一圈白环 + 强调色外发光（设计稿的 `0 0 0 3px #fff`）。
     */
    class SubmitButton(
        context: android.content.Context,
        label: String,
        private val action: () -> Unit
    ) : TextView(context) {
        private val dp = { value: Number -> context.tvDp(value) }
        private val band = context.softBand()
        private val plate = TvStyle.soft(
            context, TvStyle.accent, TvStyle.RADIUS_PILL, TvStyle.SOFT_BAND,
            SoftStyle.RAISED, depth = 1.2f
        )

        init {
            text = label
            designTextSize(TvStyle.LOGIN_BUTTON_TEXT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            isFocusable = true
            isFocusableInTouchMode = true
            id = View.generateViewId()
            setPadding(dp(TvStyle.LOGIN_INPUT_PAD) + band, band, dp(TvStyle.LOGIN_INPUT_PAD) + band, band)
            minimumHeight = dp(TvStyle.LOGIN_BUTTON_HEIGHT + 2 * TvStyle.SOFT_BAND)
            setTextColor(TvStyle.onLit)
            if (android.os.Build.VERSION.SDK_INT >= 21) letterSpacing = .22f
            background = plate
            setOnClickListener { action() }
            setOnFocusChangeListener { view, focused ->
                paint(focused)
                TvStyle.focus(view, focused, 1.02f)
            }
        }

        private fun paint(focused: Boolean) {
            plate.restyle(
                fill = TvStyle.accent,
                ring = if (focused) TvStyle.lit else Color.TRANSPARENT,
                ringWidth = if (focused) context.designPx(TvStyle.RING).toFloat() else 0f,
                glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
                depth = if (focused) 1.3f else 1.2f
            )
            setTextColor(if (focused) TvStyle.onLit else TvStyle.onLit)
        }
    }

    /**
     * 「获取验证码」这类窄胶囊按钮：凸起面板 + 暗字，焦点时按进去 + 焦点环。
     *
     * 与 [TvButton] 的区别只有尺寸（设计稿 158 × 64）与字号（16.5），
     * 因此直接复用同一套 [SoftStyle] 绘制，不另写一套观感。
     */
    class PillButton(
        context: android.content.Context,
        label: String,
        private val widthDp: Int,
        private val action: () -> Unit
    ) : TextView(context) {
        private val dp = { value: Number -> context.tvDp(value) }
        private val band = context.softBand()
        private val plates = arrayOfNulls<SoftDrawable>(2)
        private var enabledState = true

        init {
            text = label
            designTextSize(16.5f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = true
            isFocusableInTouchMode = true
            id = View.generateViewId()
            paint(false)
            setOnClickListener { action() }
            setOnFocusChangeListener { view, focused ->
                paint(focused)
                TvStyle.focus(view, focused, 1.04f)
            }
        }

        val widthPx: Int get() = dp(widthDp + 2 * TvStyle.SOFT_BAND)

        fun setLabel(value: String) {
            if (text.toString() == value) return
            if (layoutParams == null) return
            text = value
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            enabledState = enabled
            isFocusable = enabled
            paint(hasFocus())
        }

        private fun paint(focused: Boolean) {
            setPadding(dp(TvStyle.LOGIN_INPUT_PAD - 6) + band, band, dp(TvStyle.LOGIN_INPUT_PAD - 6) + band, band)
            minimumHeight = dp(TvStyle.LOGIN_FIELD_HEIGHT + 2 * TvStyle.SOFT_BAND)
            val useInset = focused && enabledState
            val plate = plates[if (useInset) 1 else 0] ?: run {
                val made = if (useInset)
                    TvStyle.well(context, TvStyle.well, TvStyle.RADIUS_PILL, TvStyle.SOFT_BAND, depth = 1.15f)
                else TvStyle.soft(context, TvStyle.surfaceHigh, TvStyle.RADIUS_PILL, TvStyle.SOFT_BAND,
                    SoftStyle.RAISED, depth = 1.15f)
                plates[if (useInset) 1 else 0] = made
                made
            }
            plate.restyle(
                fill = if (!enabledState) TvStyle.well else if (useInset) TvStyle.well else TvStyle.surfaceHigh,
                ring = if (useInset) TvStyle.focusRing else Color.TRANSPARENT,
                ringWidth = if (useInset) context.designPx(TvStyle.RING).toFloat() else 0f,
                glow = if (useInset) TvStyle.litGlow else Color.TRANSPARENT,
                depth = if (useInset) 1.15f else 1.15f
            )
            if (background !== plate) background = plate
            setTextColor(
                when {
                    !enabledState -> dim(TvStyle.text, .28f)
                    focused -> TvStyle.text
                    else -> dim(TvStyle.text, .46f)
                }
            )
            alpha = if (enabledState) 1f else 1f
        }

        private fun dim(color: Int, ratio: Float) = (color and 0x00FFFFFF) or
            (((ratio * 255f).toInt().coerceIn(0, 255)) shl 24)
    }

    /**
     * 地区码按钮：与 [PillButton] 同高，但右侧多一枚向下的箭头。
     *
     * 箭头由自己画（而不是 compound drawable）：compound drawable 贴在文字右侧的内容边缘，
     * 文案变长（`+886`）时会跟着飘；这里固定画在面板右内边距处，宽度不变、位置不变。
     */
    class RegionButton(
        context: android.content.Context,
        label: String,
        private val action: () -> Unit
    ) : TextView(context) {
        private val dp = { value: Number -> context.tvDp(value) }
        private val band = context.softBand()
        private val plates = arrayOfNulls<SoftDrawable>(2)
        private val arrow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeWidth = dp(2.2).toFloat()
        }

        init {
            text = label
            designTextSize(19f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            isFocusable = true
            isFocusableInTouchMode = true
            id = View.generateViewId()
            paint(false)
            setOnClickListener { action() }
            setOnFocusChangeListener { view, focused ->
                paint(focused)
                TvStyle.focus(view, focused, 1.04f)
            }
        }

        private fun paint(focused: Boolean) {
            // 右侧多留一条给箭头，文字整体略偏左，看起来是「区号 + 展开」而不是「区号居中」。
            setPadding(dp(TvStyle.LOGIN_INPUT_PAD - 14) + band, band, dp(TvStyle.LOGIN_INPUT_PAD - 4) + band, band)
            minimumHeight = dp(TvStyle.LOGIN_FIELD_HEIGHT + 2 * TvStyle.SOFT_BAND)
            val useInset = focused
            val plate = plates[if (useInset) 1 else 0] ?: run {
                val made = if (useInset)
                    TvStyle.well(context, TvStyle.well, TvStyle.RADIUS_PILL, TvStyle.SOFT_BAND, depth = 1.15f)
                else TvStyle.soft(context, TvStyle.surfaceHigh, TvStyle.RADIUS_PILL, TvStyle.SOFT_BAND,
                    SoftStyle.RAISED, depth = 1.15f)
                plates[if (useInset) 1 else 0] = made
                made
            }
            plate.restyle(
                fill = if (useInset) TvStyle.well else TvStyle.surfaceHigh,
                ring = if (useInset) TvStyle.focusRing else Color.TRANSPARENT,
                ringWidth = if (useInset) context.designPx(TvStyle.RING).toFloat() else 0f,
                glow = if (useInset) TvStyle.litGlow else Color.TRANSPARENT,
                depth = 1.15f
            )
            if (background !== plate) background = plate
            setTextColor(
                when {
                    focused -> TvStyle.text
                    else -> dim(TvStyle.text, .50f)
                }
            )
            arrow.color = when {
                focused -> TvStyle.focus
                else -> dim(TvStyle.text, .28f)
            }
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val cx = (width - paddingRight + band * .4f)
            val cy = height / 2f
            val half = dp(4.2f)
            arrow.pathEffect = null
            val path = android.graphics.Path().apply {
                moveTo(cx - half, cy - half * .55f)
                lineTo(cx, cy + half * .55f)
                lineTo(cx + half, cy - half * .55f)
            }
            canvas.drawPath(path, arrow)
        }

        private fun dim(color: Int, ratio: Float) = (color and 0x00FFFFFF) or
            (((ratio * 255f).toInt().coerceIn(0, 255)) shl 24)
    }
}
