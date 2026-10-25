// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hgtv.star.compat.attachedToWindow
import com.hgtv.star.touchFocusHost

/**
 * 应用内唯一的模态框：标题 + 内容 + 一排 [TvButton]。
 *
 * 之前所有弹窗都交给系统 AlertDialog，按钮是 Material 的文字按钮——高度、圆角、焦点反馈
 * 和页面上的胶囊按钮都不是一套，看起来像另一个应用。这里把弹窗内容做成普通 View，
 * 按钮直接复用全站按钮组件，因此模态框内外完全一致。
 *
 * 用法：
 * ```
 * TvDialog(this, "删除这条记录")
 *     .message("删除《…》的本机观看进度？收藏会保留。")
 *     .button("取消") {}
 *     .button("确认") { library.removeHistory(id) }
 *     .show(anchor)
 * ```
 * 列表型弹窗用 [choices]：整列居中，每项都是弹窗档按钮（[ButtonSize.DIALOG]），
 * 宽度固定为弹窗宽度的 60%，超过半屏由 [ScrollView] 接管。弹窗里的一切——标题、正文、选项、动作按钮——都比页面上小一圈，
 * 因此整框看起来是一块精致的小面板，而不是把页面控件原样搬进浮层。
 * 三个区块（内容 / 列表 / 按钮行）只有真正有东西时才占位，空着不留间距。
 */
internal class TvDialog(private val activity: Activity, title: String) {

    private companion object {
        /** 弹窗标题字号（设计稿 20）。 */
        const val TITLE_SIZE = TvStyle.DIALOG_TITLE_TEXT

        /** 正文默认字号：与页面正文同一档（弹窗里不再另外放大，读起来仍是正文）。 */
        const val BODY_SIZE = 15f

        /** 面板四周的内容留白（设计 dp）。 */
        const val PANEL_INSET = TvStyle.DIALOG_PAD_SIDE

        /**
         * 弹窗中部可滚动区域（内容区 + 列表区）合计最多占屏幕高度的比例。
         *
         * 更新日志这类长文本原先**不封顶也不滚动**，一长就把按钮行顶出画面——
         * 用户看得到日志、看不到「更新 / 暂不更新」。现在这一块封顶，超出部分各自滚动，
         * 按钮行始终留在画面里。
         *
         * 两块**共用一个预算**（而不是各占一个比例）：同时出现内容与列表的弹窗
         * （备份恢复：说明文字 + 选项）加起来才不会顶出一屏。取 .5 加上标题、
         * 按钮行与弹窗四周留白，整框仍在一屏之内，内容区也还读得下十来行。
         */
        const val SCROLLABLE_MAX_FRACTION = .5f
    }

    private val dp = { value: Number -> activity.tvDp(value) }
    /** 弹窗面板的柔光带：面板比窗口边界内缩这么多，投影 / 高光画在这条带里。 */
    private val band = activity.softBandLarge()
    // 容器比内容早 FOCUS_BLEED 开始：列表项 / 按钮行放大后的焦点底色才有地方画；
    // 再加上面板的柔光带，弹窗内容仍然落在同一条 24dp 内容线上。
    // 标题、内容、按钮行各自补回 FOCUS_BLEED，因此弹窗里所有元素对齐。
    private val body = activity.tvColumn().apply {
        clipChildren = false
        clipToPadding = false
        // 面板内边距（设计稿 26 / 22 / 24）：上宽下窄一点，标题与选项之间的呼吸才自然。
        val side = dp(PANEL_INSET)
        val bleed = dp(TvStyle.FOCUS_BLEED)
        setPadding(
            side - bleed + band,
            dp(TvStyle.DIALOG_PAD_TOP) + band,
            side - bleed + band,
            dp(TvStyle.DIALOG_PAD_BOTTOM) + band
        )
    }

    /** 调用方可以直接往里塞二维码、复选框这类自定义内容。 */
    val content: LinearLayout = activity.tvColumn().apply {
        clipChildren = false
        clipToPadding = false
    }

    /**
     * 内容区的滚动容器。
     *
     * 裁剪由 [contentHost] 提供（与列表同一套规则）。内边距**不在这里写死**：
     * 上下那点 [TvStyle.FOCUS_BLEED] 只在内容里真有可聚焦控件时（备份恢复弹窗那个开关）
     * 才补上——纯文字弹窗留了只是白占高度；左右不留，内容自己已经有左右余量，
     * 这里再加一份会让正文比标题多缩进一条，两处对不齐。
     */
    private val contentScroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
    }
    /**
     * 内容区的裁剪外壳：在**测量阶段**就把高度压到上限之内。
     *
     * 为什么不用「布局完成后再改 LayoutParams」那种做法：那要依赖 `OnLayoutChangeListener`，
     * 而它的时机跟「弹窗是第一次弹出、还是同一个位置再弹一次」有关——
     * 第二次弹出时内容**一开始就是长的**（状态文字上一次检查后就已经是整段更新日志），
     * 于是 `show()` 里那次 `post` 量到的还是 0 高度、判定成「不溢出」，
     * 而随后的布局回调也没能把按钮救回来，表现出来就是「第一次能滚、再打开按钮又被顶没」。
     * 放在测量阶段就没有这个时序问题：无论内容何时变长，量的时候就已经封顶了。
     */
    private inner class ContentHost : FrameLayout(activity) {
        init {
            clipChildren = true
            clipToPadding = true
        }

        /** 当前是不是「内容超出上限、正在滚动」的状态。 */
        private var overflow = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // 先按自然高度量一次。`ScrollView` 给子控件传的是 UNSPECIFIED，
            // 因此这一次量到的就是内容的真实高度，不会被上限反过来压小。
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val natural = measuredHeight
            val cap = contentCap()
            val next = natural > cap
            if (next) super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.EXACTLY))
            if (next != overflow) {
                overflow = next
                // 测量里不改焦点链（那是布局的事），推到下一帧做。
                post { if (isShowing) wireContentFocus(next) }
            }
        }
    }

    private val contentHost = ContentHost().apply {
        addView(contentScroll, FrameLayout.LayoutParams(-1, -1))
    }

    /** 顶部信息区（封面缩略图 + 标题 + 副标题）：只在调用方给了封面 / 副标题时才出现。 */
    private val head = activity.tvRow().apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }
    private val headThumb = android.widget.ImageView(activity).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        // 缩略图是一块嵌进面板的凹槽：圆角与高度同档（60 高 → 30 半径，两端正好半圆）。
        background = TvStyle.shape(activity, TvStyle.well, radius = TvStyle.DIALOG_THUMB_RADIUS)
        roundCorners(this, TvStyle.DIALOG_THUMB_RADIUS)
    }
    private val headTitle = activity.tvText("", TvStyle.DIALOG_TITLE_TEXT, TvStyle.text, bold = true)
        .apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val headSub = activity.tvText("", TvStyle.DIALOG_SUB_TEXT, TvStyle.muted)
        .apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }
    private val headText = activity.tvColumn().apply {
        addView(headTitle, LinearLayout.LayoutParams(-1, -2))
        addView(headSub, LinearLayout.LayoutParams(-1, -2))
    }
    /** 面板标题（没有封面时只显示这一行）。 */
    private val titleView = activity.tvText(title, TITLE_SIZE, TvStyle.textStrong, bold = true)
        .apply { setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0) }

    private val items = activity.tvColumn().apply {
        clipChildren = false
        clipToPadding = false
        // 选项整体居中：宽度固定为弹窗宽度的 60%，左右两侧留白对称。
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val scroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        visibility = View.GONE
        focusBleed()
    }
    /** 列表滚动的外壳：裁剪由它做，列表项放大后的底色才能画进容器自己的焦点余量里。 */
    private val listHost = activity.tvScrollHost(scroll)
    /**
     * 底部动作按钮：**与选项同一套整行胶囊**，竖排、等宽。
     *
     * 设计稿里弹窗只有一列胶囊，没有「右下角一排小按钮」这种写法；
     * 取消 / 确认也照这一列排下来，整框从上到下只有一种节奏。
     */
    private val actions = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }
    private val buttons = mutableListOf<DialogItem>()
    private var listHeight = 0
    /** 焦点是否优先落内容区（见 [focusContentFirst]）。 */
    private var contentFirst = false
    private var dialog: AlertDialog? = null
    private var dismissed: (() -> Unit)? = null
    /** 调用方另外挂的关闭收尾（见 [setOnDismissExtra]）。 */
    private var extraDismiss: (() -> Unit)? = null
    private var anchor: View? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    init {
        // 顶部信息区：封面 96×60 的圆角缩略图 + 剧名 + 一行副标题（集数 / 时长）。
        // 三者都在时才摆，否则退回一行面板标题——纯文字弹窗（更新日志、二次确认）不需要封面。
        head.addView(
            headThumb,
            LinearLayout.LayoutParams(dp(TvStyle.DIALOG_THUMB_WIDTH), dp(TvStyle.DIALOG_THUMB_HEIGHT))
        )
        head.addView(
            headText,
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(TvStyle.GAP_BLOCK) }
        )
        body.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(TvStyle.GAP_BLOCK) })
        body.addView(titleView)
        content.setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0)
        contentScroll.addView(content)
        body.addView(contentHost, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(items)
        body.addView(listHost, LinearLayout.LayoutParams(-1, -2))
        actions.setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0)
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))
        // 内容区可能在建好之后才被填、也可能被清空重填（手机输入 / 备份恢复那两处会 removeAllViews）。
        // 因此这里**不用 visibility 开关**：把宿主设成 GONE 之后它的子控件不再被测量，
        // 这条布局回调也就不会再触发，清空后重填的内容会永远不显示。
        // 改成始终可见、只按内容数量调间距——内容为空时 [content] 的自然高度是 0，
        // 宿主跟着是 0 高，同样不留空槽。
        content.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val column = view as? LinearLayout ?: return@addOnLayoutChangeListener
            val params = contentHost.layoutParams as? LinearLayout.LayoutParams ?: return@addOnLayoutChangeListener
            val wanted = if (column.childCount > 0) dp(TvStyle.GAP_BLOCK) else 0
            if (params.topMargin != wanted) {
                params.topMargin = wanted
                contentHost.layoutParams = params
            }
            // 上下焦点余量**只在内容里真有可聚焦控件时**才留（见 [contentScroll]）：
            // 纯文字弹窗（更新日志、开源许可、二次确认）留了也是白占高度。
            // 封顶不在这里做——那件事已经交给 [ContentHost] 的测量（见那里的注释）。
            val bleed = if (column.anyFocusable()) dp(TvStyle.FOCUS_BLEED) else 0
            if (contentScroll.paddingTop != bleed || contentScroll.paddingBottom != bleed) {
                contentScroll.setPadding(0, bleed, 0, bleed)
            }
        }
    }

    /** 一段说明文字。多段就多调一次，行距与行高由同一套令牌决定。 */
    fun message(text: String, size: Float = BODY_SIZE, color: Int = TvStyle.textDim): TvDialog {
        content.addView(
            activity.tvText(text, size, color).apply { setLineSpacing(dp(TvStyle.GAP_TIGHT).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { if (content.childCount > 0) topMargin = dp(TvStyle.GAP_INLINE) }
        )
        return this
    }

    /**
     * 顶部信息区：封面缩略图 + 剧名 + 一行副标题。
     *
     * 卡片长按菜单是「对这张卡做什么」——把那张卡的封面和剧名摆在最上面，
     * 用户一眼确认自己按对了哪一部，比一行纯文字标题有用得多。
     * 没有封面时（设置页的选项、二次确认）不调这个方法，弹窗就只显示面板标题。
     */
    fun header(title: String, subtitle: String = "", cover: String = "", loadCover: ((android.widget.ImageView, String) -> Unit)? = null): TvDialog {
        headTitle.text = title
        headSub.text = subtitle
        headSub.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        if (cover.isNotBlank() && loadCover != null) {
            headThumb.visibility = View.VISIBLE
            loadCover(headThumb, cover)
        } else {
            headThumb.visibility = View.GONE
        }
        head.visibility = View.VISIBLE
        // 有信息区时不再重复一行面板标题：剧名已经写在封面右边了。
        titleView.visibility = View.GONE
        (head.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        return this
    }

    /**
     * 列表型选项：整行胶囊，左边一枚线性图标，右边文字。
     * 常态压暗（安静的候选），聚焦时凹进 + 焦点色细环 + 外发光。
     * [selected] 的那一项用常驻的凹槽标出；[danger] 里的下标用红字红环（删除 / 清空）。
     */
    fun choices(
        labels: List<String>,
        selected: Int = -1,
        icons: List<Int?> = emptyList(),
        danger: Set<Int> = emptySet(),
        onChoice: (Int) -> Unit
    ): TvDialog {
        labels.forEachIndexed { index, label ->
            val item = DialogItem(
                activity, label,
                iconRes = icons.getOrNull(index),
                danger = index in danger,
                alignStart = true,
                action = {
                    dismiss()
                    onChoice(index)
                }
            )
            item.setSelectedState(index == selected)
            item.contentDescription = label + if (index == selected) "，当前选择" else ""
            // 整行铺满面板宽度：设计稿里的选项是一条条横贯面板的胶囊，
            // 左边图标、右边文字，一列读下来像一份菜单，而不是几个悬浮的小按钮。
            items.addView(
                item,
                LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = dp(TvStyle.DIALOG_ITEM_GAP)
                }
            )
        }
        if (labels.isNotEmpty()) {
            scroll.visibility = View.VISIBLE
            // 列表与上面的内容（或标题）之间用区块间距；内容区自己已经带了一条 GAP_BLOCK，
            // 两块同时出现时这里退一档，避免两条间距叠起来。
            (listHost.layoutParams as LinearLayout.LayoutParams).topMargin =
                dp(if (content.childCount > 0) TvStyle.GAP_INLINE else TvStyle.GAP_BLOCK)
            // 列表滚动的可视高度要把滚动容器自身的焦点余量（上下各 FOCUS_BLEED）一起算进去，
            // 否则第一项 / 最后一项选中时的底色会被列表裁掉一条边。
            // 每一项都是弹窗档按钮，高度含柔光带；间距按 GAP_INLINE + 两条柔光带算，
            // 否则列表高度会低估，第一项 / 最后一项的柔光与焦点环会被列表裁掉。
            val itemHeight = dp(TvStyle.DIALOG_ITEM_HEIGHT + 2 * TvStyle.SOFT_BAND)
            val itemGap = dp(TvStyle.DIALOG_ITEM_GAP + 2 * TvStyle.SOFT_BAND)
            listHeight = labels.size * itemHeight + (labels.size - 1) * itemGap +
                2 * dp(TvStyle.FOCUS_BLEED_VERTICAL)
        }
        return this
    }

    /**
     * 底部动作按钮：整行胶囊、文字居中。默认点完就关
     * （[keepOpen] 用于「检查更新」这种点完还要留在框里刷新状态的）。
     * [icon] 给「取消 / 关闭 / 确认 / 删除」这类一眼能认的动作配一枚线性图标。
     * 返回按钮本身，调用方可以后续改文案或禁用。
     */
    fun button(
        label: String,
        tone: ButtonTone = ButtonTone.SECONDARY,
        keepOpen: Boolean = false,
        icon: Int? = null,
        /**
         * 空心红描边（见 [DialogItem]）：给「使用须知」这种**需要用户明确承担风险**的单枚按钮。
         *
         * 与 [ButtonTone.DANGER] 的区别：那一档是「暗红字 + 普通面板」，适合删除 / 清空这类
         * 夹在一列选项里的动作；这一档常驻一圈红环 + 红字红图标，是页面里唯一抢眼的那一件，
         * 因此**一个弹窗里最多用一枚**。
         */
        outlined: Boolean = false,
        run: () -> Unit = {}
    ): DialogItem {
        val view = DialogItem(
            activity, label,
            iconRes = icon,
            danger = tone == ButtonTone.DANGER,
            alignStart = false,
            outlined = outlined,
            action = {
                if (!keepOpen) dismiss()
                run()
            }
        )
        actions.addView(
            view,
            LinearLayout.LayoutParams(-1, -2).apply { if (actions.childCount > 0) topMargin = dp(TvStyle.DIALOG_ITEM_GAP) }
        )
        if (actions.childCount == 1) (actions.layoutParams as LinearLayout.LayoutParams).topMargin = dp(TvStyle.GAP_BLOCK)
        buttons += view
        // 动作按钮竖排：上下键在这一列里走，第一枚按上、最后一枚按下都不出去
        // （出去的出口由 [wireListExit] / [wireContentFocus] 负责接到选项或内容区）。
        buttons.forEachIndexed { index, item ->
            item.nextFocusDownId = buttons.getOrNull(index + 1)?.id ?: item.id
            item.nextFocusUpId = buttons.getOrNull(index - 1)?.id ?: item.id
        }
        return view
    }

    /**
     * 让焦点先落在**内容区**的第一个可聚焦控件上（而不是按钮行）。
     *
     * 默认落点是按钮行（「确认」永远不会是默认落点，见 [show]）。但内容里带了要用户
     * 直接开始输入的控件时（手机号 / 验证码输入框），落点必须在那一栏里——否则用户要先按
     * 方向键从按钮行挪进去，而且初始焦点停在「关闭」上容易被误按。
     */
    fun focusContentFirst(): TvDialog {
        contentFirst = true
        return this
    }

    /**
     * 追加一条关闭回调（与 [show] 传入的那条并存）。
     *
     * [TvTools.open] 会用 `show` 的参数做「关弹窗时收尾」（关掉在途的一次性页面等），
     * 调用方另外还要收自己的尾时（例如停掉倒计时）用这个，不必去覆盖 `show` 的回调。
     */
    fun setOnDismissExtra(action: () -> Unit) {
        extraDismiss = action
    }

    fun show(anchor: View? = null, onDismiss: () -> Unit = {}) {
        if (isShowing) return
        this.anchor = anchor
        dismissed = onDismiss
        val metrics = activity.resources.displayMetrics
        // 套一层触摸外壳：弹窗是独立窗口，Activity 的 `dispatchTouchEvent` 管不到它。
        // 不套的话，弹窗里点一枚「当前没有焦点」的按钮仍然是两下（见 MainActivityTouch 的说明）。
        val next = AlertDialog.Builder(activity).setView(touchFocusHost(body, fillHeight = false)).create()
        // 遥控器「搜索」键：弹窗有独立的 PhoneWindow，按键不经过 Activity.dispatchKeyEvent。
        // 部分电视 ROM 会在这里直接拉起不存在的 com.imtv.speechdemo 并闪退；
        // Dialog.dispatchKeyEvent 会先问这个监听器，因此在弹窗这一层也吞掉。
        next.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_SEARCH }
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                val done = dismissed
                dismissed = null
                done?.invoke()
                val extra = extraDismiss
                extraDismiss = null
                extra?.invoke()
            }
            // 关闭后**只把焦点还给 anchor**，且仅在「当前没人持有焦点」时还：
            // 全站的焦点原则是不主动抢焦点（见 MainActivity 里的说明），
            // 关掉弹窗时如果焦点已经被别处接走（例如内容重建后落到第一张卡），就不要夺回来。
            val current = activity.window?.decorView?.findFocus()
            if (anchor?.attachedToWindow == true && current == null) anchor.requestFocus()
        }
        dialog = next
        next.show()
        next.window?.apply {
            // 弹窗是一块独立的大圆角面板：超大圆角（56）+ 深投影 + 一圈极淡的焦点色描边。
            // 描边不是「线框」，只是把面板从压暗的背景里勾出来一档（设计稿 `0 0 0 1px --focus 13%`）。
            setBackgroundDrawable(
                TvStyle.soft(
                    activity, TvStyle.surfaceHigh, TvStyle.RADIUS_DIALOG_PANEL, TvStyle.SOFT_BAND_LARGE,
                    SoftStyle.RAISED,
                    ring = (TvStyle.focus and 0x00FFFFFF) or (0x21 shl 24),
                    ringWidth = 1,
                    depth = 1.6f
                )
            )
            // 背景压暗是柔和的：新拟态靠同色明度差表达层次，压得太黑面板就看不出是浮层了。
            setDimAmount(.66f)
            setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // 列表超过半屏就滚动，避免弹窗被长列表顶出画面。
        // 列表与内容区共用 [SCROLLABLE_MAX_FRACTION] 这一份预算（见 [contentCap]）：
        // 两块都在时各分一半，只有一块时那一块独占，加起来始终在一屏之内。
        if (listHeight > 0) {
            val limit = (metrics.heightPixels * SCROLLABLE_MAX_FRACTION).toInt()
            listHost.layoutParams = listHost.layoutParams.apply { height = listHeight.coerceAtMost(limit) }
        }
        // 焦点落在第一项（列表优先），因此「确认」永远不会是默认落点，误触不会造成破坏。
        // 调用方声明了 [focusContentFirst] 时优先落内容区（数字键盘这类自成一块的输入控件）。
        body.post {
            if (!isShowing) return@post
            val landing = if (contentFirst) content.firstFocusable() ?: items.firstChildOrNull() else null
            (landing ?: if (items.childCount > 0) items.getChildAt(0) else buttons.firstOrNull())?.requestFocus()
            // 封顶由 [ContentHost] 的测量负责，这里只补一次焦点接线兜底
            // （内容为空时测量里不会报「溢出」，也就不会有人来接线）。
            wireContentFocus(content.height > contentCap())
            wireListExit()
        }
    }

    /**
     * 列表（[items] 那一块）与按钮行之间的上下出口。
     *
     * **不能只写 `nextFocusDownId`，也不能把监听器挂在 ScrollView 上。**
     *
     * `ScrollView.dispatchKeyEvent()` 是 `super.dispatchKeyEvent(event) || executeKeyEvent(event)`：
     * 焦点在列表项上时 `super` 返回 false（列表项不吃方向键），于是 `executeKeyEvent` 接管；
     * 它里面的 `FocusFinder.findNextFocus(this, ...)` 把搜索根限定成 **ScrollView 自己**——
     * 底部按钮行是它的兄弟节点，不在它的子树里，永远搜不到。于是 `nextFocused == null`，
     * 退回「滚一屏」并 `return true` **把按键吃掉**，焦点停在列表最后一项，
     * 按向下只是列表滚动，到不了「取消」（搜索历史弹窗就是这样）。
     *
     * 监听器挂在 ScrollView 上也没用：`ViewGroup.dispatchKeyEvent()` 在有子控件持有焦点时
     * 只把事件交给那个子控件，自己那份 `OnKeyListener` 不会被问。
     *
     * 因此唯一可靠的拦截点是**列表项自己**：`View.dispatchKeyEvent` 会先问它的
     * `OnKeyListener`，早于 ScrollView 的 `executeKeyEvent`。只在「最后一项按下 / 第一项按上」
     * 这两处返回 true，中间的项仍交回 ScrollView，正常滚动与移动焦点的手感不变。
     */
    private fun wireListExit() {
        // 落到「取消」而不是第一枚：`choices()` 的按钮行可能是 [额外动作, 取消]，
        // 第一枚是「清空历史」这类会再弹一层确认的动作；直接落在取消上，
        // 用户按一下就能关掉，想清空再按左键。
        val firstButton = buttons.firstOrNull { it.text.toString() == "取消" }
            ?: buttons.firstOrNull()
            ?: return
        val count = items.childCount
        if (count == 0) return
        val lastItem = items.getChildAt(count - 1)
        // 按钮行按上键回列表最后一项：按钮不在滚动容器里，普通 nextFocusUpId 就够。
        // 内容区参与焦点链时（有可聚焦控件，或文字溢出后滚动容器自己可聚焦）**不覆盖**
        // [wireContentFocus] 接好的「上键回内容区」——那块在列表上方，方向上是它先到。
        val contentInFocusChain = content.firstFocusable() != null || contentScroll.isFocusable
        // 只有**第一枚**动作按钮的「上」接回列表最后一项：动作按钮是竖排的一列，
        // 后面的几枚已经由 [button] 接成上下相邻，覆盖掉就会让焦点链断开。
        if (!contentInFocusChain) {
            buttons.firstOrNull()?.nextFocusUpId = lastItem.id
        }
        // 最后一项按下也写一条出口：万一某台设备的 ScrollView 没吃掉按键，这条仍然生效。
        lastItem.nextFocusDownId = firstButton.id
        for (i in 0 until count) {
            val item = items.getChildAt(i)
            val isFirst = i == 0
            val isLast = i == count - 1
            item.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN ->
                        if (isLast) { firstButton.requestFocus(); true } else false
                    KeyEvent.KEYCODE_DPAD_UP ->
                        if (isFirst) {
                            // 内容区有可聚焦控件（备份恢复那个开关）就回去，否则停在原地。
                            val up = content.firstFocusable()
                            if (up != null) { up.requestFocus(); true } else true
                        } else false
                    else -> false
                }
            }
        }
    }

    /**
     * 内容区可用的高度上限（见 [ContentHost]）。
     *
     * 内容区与列表区共用 [SCROLLABLE_MAX_FRACTION] 这一份预算：另一块已经占了高度时，
     * 这一块只能用剩下的部分；两块都短就各自按自然高度摆，谁都不封顶。
     */
    private fun contentCap(): Int {
        val metrics = activity.resources.displayMetrics
        val budget = (metrics.heightPixels * SCROLLABLE_MAX_FRACTION).toInt()
        val used = if (listHeight > 0) listHost.layoutParams.height.coerceAtLeast(0) else 0
        return (budget - used).coerceAtLeast((metrics.heightPixels * .18f).toInt())
    }

    /**
     * 内容区溢出时的焦点接线。
     *
     * **只有真的溢出时才让内容区可聚焦**：那时按钮行按上键能进日志区上下滚动、
     * 按下键回按钮行；短内容不参与焦点链，行为与以前完全一致。
     * 内容里有可聚焦控件时（备份恢复弹窗那个开关），焦点交给那个控件自己——
     * 让滚动容器抢焦点会把那个控件挡在焦点链之外（`ScrollView` 会自动把获得焦点的子控件滚进可视区）。
     */
    private fun wireContentFocus(overflow: Boolean) {
        val innerFocusable = content.anyFocusable()
        val scrollFocusable = overflow && !innerFocusable
        contentScroll.isFocusable = scrollFocusable
        contentScroll.isFocusableInTouchMode = scrollFocusable
        if (!overflow) return
        if (contentScroll.id == View.NO_ID) contentScroll.id = View.generateViewId()
        val up = if (scrollFocusable) contentScroll else content.firstFocusable() ?: contentScroll
        // 同样只接第一枚（见 [wireListExit]）：动作按钮竖排，内部已经上下相连。
        buttons.firstOrNull()?.nextFocusUpId = up.id
        contentScroll.nextFocusDownId = buttons.firstOrNull()?.id ?: contentScroll.id
    }

    /**
     * 弹窗宽度（像素）：屏宽的 46%，夹在 360dp–640dp 之间。
     *
     * 选项按钮按这个宽度的 60% 排布，因此必须在 `choices()` 建按钮时就能算出来——
     * 弹窗窗口要到 `show()` 才存在，这里直接按同一套规则从屏幕尺寸推。
     * 窄屏（720P / 640dp 宽、或手机横屏 360dp 宽）上这个下界会顶到甚至超过屏宽，
     * 因此先把下界压到「屏宽 − 两侧留白」以内，避免 coerceIn 的 min>max。
     */
    private fun dialogWidth(): Int {
        val metrics = activity.resources.displayMetrics
        // 设计稿是 472 设计单位的固定宽度面板；窄屏（手机横屏 / 分屏）上按「屏宽 − 两侧留白」收一次。
        val max = (metrics.widthPixels - dp(48)).coerceAtLeast(dp(240))
        return dp(TvStyle.DIALOG_WIDTH).coerceAtMost(max)
    }

    fun dismiss() {
        dialog?.dismiss()
    }
}

/** 容器里的第一个子控件；没有就返回 null。 */
private fun ViewGroup.firstChildOrNull(): View? = if (childCount > 0) getChildAt(0) else null

/** 子树里有没有可聚焦的控件（跳过不可见的）。 */
private fun View.anyFocusable(): Boolean {
    if (visibility != View.VISIBLE) return false
    if (isFocusable) return true
    val group = this as? ViewGroup ?: return false
    return (0 until group.childCount).any { group.getChildAt(it).anyFocusable() }
}

/** 子树里第一个可聚焦的控件（跳过不可见的），没有就返回 null。 */
private fun View.firstFocusable(): View? {
    if (visibility != View.VISIBLE) return null
    if (isFocusable) return this
    val group = this as? ViewGroup ?: return null
    for (i in 0 until group.childCount) group.getChildAt(i).firstFocusable()?.let { return it }
    return null
}
