// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.shared.media.DanmakuView
import com.hgtv.star.core.Series
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneTouch
import com.hgtv.star.phone.ui.bottomScrim
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.phoneTabBarHeightPx
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.topScrim

/**
 * 竖屏播放版面：**推荐（信息流）与正式播放页共用这一块**。
 *
 * 两个入口的版式必须一致（用户要求「行为和样式都保持一致」），差别只有一处，
 * 都由构造参数控制：
 *
 * | 差别 | 信息流 | 正式播放页 |
 * | --- | --- | --- |
 * | 底部导航留白 | 让开 60dp 的 tab 栏 | 只让开系统导航条 |
 *
 * ## 版面（自下而上）
 *
 * ```
 * 进度条
 * 观看完整短剧 / 选集 全N集｜第N集
 * 第x集 | 简介
 * 标签（第N季 · 题材）
 * 标题
 * ```
 *
 * 为什么是这个顺序：视线从画面落下来，先要知道「这是什么剧」（标题），再是「讲什么」
 * （标签 / 简介），最后才是「要不要继续看」（按钮）与「看到哪了」（进度条）。
 *
 * ## 手势
 *
 * 全部交给 [PhoneTouch]，页面只把语义翻译成回调（[onTap] / [onLongPress] / …）。
 * 手势挂在**本视图**上而不是盖一层透明 View：Android 的事件分发是子 View 先消费，
 * 按钮的点击不会被手势吃掉；反过来，如果盖一层全屏 View，按钮就永远点不到。
 */
class PhoneVideoPage(
    context: Context,
    /** 首页是信息流，底部有 60dp 的导航栏；播放页没有。 */
    private val withTabBar: Boolean = true,
    /** 是否显示「全屏」入口（信息流和正式播放页的竖屏版面共用）。 */
    private val fullscreenEntry: Boolean = false,
    /**
     * 上下滑是否接管为「上一集 / 下一集」。
     *
     * 只有**正式播放页**开：信息流里竖滑是翻下一条视频（由外面的列表处理），
     * 这里接管会把翻流的手势吃掉。
     */
    private val verticalSwipe: Boolean = false,
    /**
     * 横滑是否接管为「拖进度」。
     *
     * **推荐页关掉**（用户要求）：信息流里左右滑不是调进度，而是换体裁（外层横向 pager）。
     * 更要紧的是，横滑一旦被这一层接住就会 `requestDisallowInterceptTouchEvent(true)`，
     * 手指上滑时开头那一点横向抖动足以让它抢走手势——用户读到的是「上滑没翻到下一条，
     * 进度条反而跳了」。
     *
     * 正式播放页仍然开：那里没有外层 pager，横滑调进度是常规操作。
     */
    private val dragSeek: Boolean = true,
    /**
     * 竖屏时是否**不挂自己的视频层**，直接把画面交给下层推荐页的 Surface。
     *
     * 正式播放页从推荐页进来时打开（见 `PhonePlayerActivity.feedUnderlay`）：
     * 窗口是半透明的、本页底板也是透明的，下层推荐页那一块 SurfaceView 一直在出帧，
     * 播放器根本不用换 Surface——用户看到的是同一路画面，控制层换了而已。
     *
     * 从这个入口以外进来（详情页、长按菜单、搜索结果）时没有下层可借，本页必须
     * 自己出画面，否则就是一整块透明。
     */
    private val underlay: Boolean = false,
    // 默认完整画面；真正的档位由宿主按用户的「画面模式」随后覆盖，
    // 不再按片源方向强制铺满。
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
) : FrameLayout(context) {

    /**
     * 弹幕层：直接复用电视端那份 [DanmakuView]（在 `:shared` 里，同一套排版与聚合算法）。
     *
     * 压画面之上、底部信息栏之下：弹幕不压标题、不压进度条。画布单位按 density 走
     * （`unitScale` 留 0）；手机设置里的 100% 对应弹幕层原先的最小档，
     * 具体映射见 [PhoneDanmakuSize]。
     */
    val danmakuView: DanmakuView = DanmakuView(context).apply {
        mode = DanmakuMode.FULL
        // 手机端弹幕字号以原先的最小档为 100%，还要允许再调到 50%。
        minTextSizeDp = 4f
        setClock(0, false)
    }

    val playerView: PlayerView = PlayerView(context).apply {
        useController = false
        this.resizeMode = resizeMode
        // 借下层 Surface 时本层不出画面：快门也必须是透明的，否则它自己就是一块黑。
        setShutterBackgroundColor(if (underlay) Color.TRANSPARENT else Color.BLACK)
        // 换媒体源时**保留上一帧**：片尾进正片 / 换清晰度重开这一集时，
        // 画面不会先黑一下再出新帧（用户读到的「闪黑屏」有一半来自这里）。
        setKeepContentOnPlayerReset(true)
        if (underlay) visibility = GONE
        setBackgroundColor(if (underlay) Color.TRANSPARENT else Color.BLACK)
    }

    // ------------------------------------------------------------------ 底部信息

    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        phoneSize(17)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(context.dp(INFO_EDGE_DP), 0, context.dp(INFO_EDGE_DP), 0)
        // 标题是进简介页的入口：只给它一条细下划线，不套按钮底板。
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
    }

    private val tagRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(INFO_EDGE_DP), 0, 0, 0)
    }

    private val episodeIndex = TextView(context).apply {
        setTextColor(Color.WHITE)
        phoneSize(13)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private val episodeDesc = TextView(context).apply {
        setTextColor(0xCCFFFFFF.toInt())
        phoneSize(13)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private val watchLabel = TextView(context).apply {
        setTextColor(Color.WHITE)
        // 14 → 12：通栏高度收到 26dp 之后，14 号字在条里几乎没有上下留白，看着像「字顶到了边」。
        phoneSize(12)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private val watchGlyph = PhoneGlyph(context, PhoneGlyph.Kind.PLAY).apply {
        tint = Color.WHITE
        filled = true
    }

    private companion object {
        /**
         * 通栏操作条高度（dp）。
         *
         * 32 → **26**（用户要求「太高了」）：它是「选集 / 观看完整短剧」的出口，
         * 一条窄栏就够，不需要占掉画面下方一整条。
         */
        const val ACTION_BAR_DP = 26
        /** 标题与底部操作图标的同一条左边线：只留一点点，避免信息区贴死屏幕。 */
        const val INFO_EDGE_DP = 6
        /** 播放三角自身在 12dp 画布里左侧留了约 4dp，容器再收 4dp 才能和文字真正对齐。 */
        const val ACTION_GLYPH_EDGE_DP = 2
        /** 播放页「选集」那条通栏的底板不透明度。 */
        const val ACTION_BAR_ALPHA = .4f
    }

    private val fullscreenGlyph = PhoneGlyph(context, PhoneGlyph.Kind.FULLSCREEN).apply {
        tint = Color.WHITE
    }

    private lateinit var seekBar: SeekBar
    /** 底部通栏操作条（选集 / 观看完整短剧）。 */
    private lateinit var actionBar: LinearLayout
    /** 浮在画面下边缘黑边里的「全屏」按钮（横屏片源才有）。 */
    private lateinit var fullscreenBox: LinearLayout
    private var wantsFullscreen = false
    private var videoWidth = 0
    private var videoHeight = 0
    private lateinit var infoColumn: LinearLayout
    private lateinit var bottomShade: View
    private lateinit var topShade: View

    private val centerGlyph = PhoneGlyph(context, PhoneGlyph.Kind.PAUSE).apply {
        tint = Color.WHITE
        filled = true
    }
    private lateinit var centerBox: FrameLayout

    private var series: Series? = null
    private var chromeVisible = true
    private var userDragging = false
    private var centerHideAt = 0L

    // ------------------------------------------------------------------ 回调

    /** 单击：竖屏 = 播放 / 暂停；横屏由宿主改成「显隐界面」。 */
    var onTap: (() -> Unit)? = null

    /** 双击（只有横屏会触发）。 */
    var onDoubleTap: (() -> Unit)? = null

    /** 长按：竖屏按左 / 中 / 右分区，横屏任意区域。 */
    var onLongPress: ((PhoneTouch.Zone) -> Unit)? = null

    /** 长按抬手。 */
    var onLongPressEnd: (() -> Unit)? = null

    /** 手势横滑：开始 / 位移（占屏宽比例，可正可负）/ 松手。 */
    var onDragStart: (() -> Unit)? = null
    var onDragDelta: ((Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    /**
     * 上下滑换集：开始 / 位移（占屏高比例，负 = 向上）/ 松手。
     *
     * 松手的参数是 [PhoneTouch.DIRECTION_NEXT]（下一集）、[PhoneTouch.DIRECTION_PREV]（上一集）
     * 或 [PhoneTouch.DIRECTION_NONE]（位移不够，当作没发生）。
     */
    var onVerticalStart: (() -> Unit)? = null
    var onVerticalDelta: ((Float) -> Unit)? = null
    var onVerticalEnd: ((direction: Int, velocityY: Float) -> Unit)? = null

    /** 进度条开始拖 / 拖动中 / 松手。fraction 是 0..1。 */
    var onSeekStart: (() -> Unit)? = null
    var onSeek: ((Float) -> Unit)? = null
    var onSeekEnd: ((Float) -> Unit)? = null

    /** 「观看完整短剧」/「选集 全N集」按钮。 */
    var onWatch: (() -> Unit)? = null

    /** 点标题（进详情）。 */
    var onTitle: (() -> Unit)? = null

    /** 点全屏（只有信息流有）。 */
    var onFullscreen: (() -> Unit)? = null

    // ------------------------------------------------------------------ 构建

    init {
        // 借下层 Surface 时整页透明：只画遮罩与信息栏，画面由推荐页那一层透上来。
        setBackgroundColor(if (underlay) Color.TRANSPARENT else Color.BLACK)
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 弹幕压在画面之上（在遮罩与信息栏之前加，因此它永远在这些层下面）。
        addView(danmakuView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 限高到屏高的一半：整屏铺开时哪怕渐变再陡，画面中间也会被压暗一层。
        // 真实高度在 onSizeChanged 里按本页高度算（信息流每一页的高度不一样）。
        bottomShade = View(context).apply { background = bottomScrim(235) }
        addView(bottomShade, LayoutParams(LayoutParams.MATCH_PARENT, 0).apply {
            gravity = Gravity.BOTTOM
        })
        topShade = View(context).apply { background = topScrim(130) }
        // 默认不画：这一页顶部本来什么都没有（竖屏的「播放显示」那一行是**兄弟层**
        // `portraitTop`，不在本页里）。渐变留着就是画面上沿一层黑雾，
        // 只有真的要为那一行垫底时才由宿主调 [setTopScrimVisible] 打开。
        topShade.visibility = GONE
        addView(topShade, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(110)).apply {
            gravity = Gravity.TOP
        })

        // 正式播放页的纵滑换集要整页稳定接管：PlayerView / 弹幕层都是全屏子 View，
        // 手势如果只挂在根页面上，触摸会先被子 View 吃掉，状态机经常收不到 DOWN。
        // 这一层压在画面与弹幕之上、信息栏与按钮之下：视频区域始终能收到手势，
        // 底部按钮和进度条仍然优先响应。
        val touch = PhoneTouch(
            host = this,
            // 竖屏版面永远是竖屏：横滑与双击在这里都不成立。
            isLandscape = { false },
            onTap = { onTap?.invoke() },
            onDoubleTap = { onDoubleTap?.invoke() },
            onLongPress = { zone -> onLongPress?.invoke(zone) },
            onLongPressEnd = { onLongPressEnd?.invoke() },
            dragEnabled = dragSeek,
            onDragStart = { onDragStart?.invoke() },
            onDrag = { delta -> onDragDelta?.invoke(delta) },
            onDragEnd = { onDragEnd?.invoke() },
            verticalEnabled = verticalSwipe,
            onVerticalStart = { onVerticalStart?.invoke() },
            onVertical = { delta -> onVerticalDelta?.invoke(delta) },
            onVerticalEnd = { direction, velocity -> onVerticalEnd?.invoke(direction, velocity) },
        )
        if (verticalSwipe) {
            addView(View(context).apply {
                setOnTouchListener(touch)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            // 信息流的竖向翻页交给外层列表；这里保持原来的根页面手势。
            setOnTouchListener(touch)
        }

        buildInfo()
        buildCenter()
    }

    private fun buildInfo() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 竖屏底部信息通栏：推荐页与正式播放页都不留左右边距。
            setPadding(0, 0, 0, 0)
        }

        // 标题
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 没接标题点击时，落到「点屏幕」上：否则这一块会把点击吃掉，什么都不发生。
            setOnClickListener { if (onTitle != null) onTitle?.invoke() else onTap?.invoke() }
        }
        // 标题只按文字实际长度占宽；短标题不再被撑满整行。
        titleRow.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        column.addView(titleRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 标签
        column.addView(tagRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(6) })

        // 第x集 | 简介
        val episodeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(INFO_EDGE_DP), 0, 0, 0)
        }
        episodeRow.addView(episodeIndex)
        episodeRow.addView(TextView(context).apply {
            text = "｜"
            setTextColor(0x80FFFFFF.toInt())
            phoneSize(13)
        })
        episodeRow.addView(episodeDesc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(episodeRow, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(6) })

        // 观看完整短剧：**通栏方角操作条**，左右贴屏幕边缘、不留内缩（用户规范）。
        //
        // 早先是 16dp 内缩 + 19dp 圆角的半透明胶囊，和上面「标题 / 标签 / 简介」
        // 那段文字块同属一个视觉层级——看起来像又一张卡片。改成通栏方角之后它变成
        // 一条**操作栏**：边界与屏幕对齐，材质与下方 tab 栏同一档（`surface`），
        // 视觉上「这一条是出口，上面那一块是内容」。
        //
        actionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = actionBarBackground()
        }
        val watch = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 播放三角在图标画布内有左侧留白：容器用 2dp，三角的可见左缘才落在标题的 6dp 边线上。
            setPadding(context.dp(ACTION_GLYPH_EDGE_DP), 0, context.dp(14), 0)
            setOnClickListener { onWatch?.invoke() }
        }
        watchGlyph.tint = PhoneStyle.accent
        watch.addView(watchGlyph, LinearLayout.LayoutParams(context.dp(11), context.dp(11)))
        watch.addView(watchLabel, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { leftMargin = context.dp(6) })
        actionBar.addView(watch, LinearLayout.LayoutParams(
            0, LayoutParams.MATCH_PARENT, 1f
        ))

        // 进度条
        seekBar = SeekBar(context).apply {
            max = 1000
            // 进度条必须**自己吃掉横向拖动**：信息流与推荐页外面套着横向翻页的 RecyclerView
            // （PagerSnapHelper），不拦住的话手指一拖就被父容器当成翻页，进度条看着「推不动」。
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            if (Build.VERSION.SDK_INT >= 21) runCatching {
                progressTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
                thumbTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x4DFFFFFF)
            }
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    onSeek?.invoke(value / 1000f)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    userDragging = true
                    onSeekStart?.invoke()
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    userDragging = false
                    onSeekEnd?.invoke(sb.progress / 1000f)
                }
            })
        }
        column.addView(seekBar, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, context.dp(18)
        ).apply { topMargin = context.dp(2) })

        infoColumn = column
        // 信息块要让开下面那根通栏条：条高 + 6dp 间隙。
        addView(column, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = bottomInset() + context.dp(ACTION_BAR_DP) + context.dp(6)
        })

        // 通栏条本身：贴底、贴左右边，**没有外边距**——「完全覆盖屏幕宽度」。
        addView(actionBar, LayoutParams(
            LayoutParams.MATCH_PARENT, context.dp(ACTION_BAR_DP)
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = bottomInset()
        })

        // 全屏按钮：**浮在画面下边缘那条黑边里**，不是塞进底部按钮行。
        // 横屏片源按 FIT 显示时画面只占中间一条、上下都是黑的，按钮跟着画面走才不会
        // 「离视频很远」。位置由 [updateVideoFrame] 按实测画面高度算。
        if (fullscreenEntry) {
            fullscreenBox = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                // 再收一档：图标 11 → 10dp、字 10 → 9 号、内边距 6/2 → 5/1、圆角 10 → 8。
                // 它是浮在画面下边缘的辅助入口，越小越好，别抢画面。
                setPadding(context.dp(5), context.dp(1), context.dp(5), context.dp(1))
                background = roundedBackground(0x33FFFFFF, context.dp(8).toFloat())
                addView(fullscreenGlyph, LinearLayout.LayoutParams(context.dp(10), context.dp(10)))
                addView(TextView(context).apply {
                    text = "全屏"
                    setTextColor(Color.WHITE)
                    phoneSize(9)
                    setPadding(context.dp(3), 0, 0, 0)
                })
                setOnClickListener { onFullscreen?.invoke() }
                visibility = GONE
            }
            addView(fullscreenBox, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = context.dp(200)
            })
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 底部遮罩只盖下面一半：再高就会把画面中间也压暗（用户读到的「有一层遮罩」）。
        val params = bottomShade.layoutParams
        val target = (h * .5f).toInt()
        if (params.height != target) {
            params.height = target
            bottomShade.layoutParams = params
        }
        // 尺寸变了：缩放是按屏宽高算的，必须按新尺寸重排一次。
        applyVideoScale()
        // 转屏 / 换版面之后画面尺寸变了，按钮要重新贴到画面下边缘。
        if (videoWidth > 0 && videoHeight > 0) placeFullscreenButton()
    }

    /**
     * 视频真实尺寸到手之后，把全屏按钮摆到**画面下边缘之下 10dp**。
     *
     * 只在横屏片源上出现：竖屏片源在竖屏里已经铺满整屏，没有「黑边全屏」这回事。
     */
    fun updateVideoFrame(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoWidth = width
        videoHeight = height
        placeFullscreenButton()
    }

    private fun placeFullscreenButton() {
        if (!fullscreenEntry || !::fullscreenBox.isInitialized) return
        if (videoWidth <= 0 || videoHeight <= 0) return
        if (this.width <= 0 || this.height <= 0) return
        // 用**实际画面比例**判断，而不是卡片上的 `vertical` 标记：
        // 接口的卡片与详情偶尔对同一部剧给出不同的 `vertical`（实测有），
        // 按标记判断会出现「推荐页有全屏按钮、进了播放页却没有」。
        // 横版视频在竖屏里有黑边，需要全屏；竖版视频在**横屏版面**里也有左右黑边，
        // 同样需要入口（推荐页横屏时点它进正式播放页）。
        val wideVideo = videoWidth > videoHeight
        val landscapePage = isLandscapePage()
        // 横屏只给竖版片源补入口，竖屏只给横版片源补入口。
        wantsFullscreen = if (landscapePage) !wideVideo else wideVideo
        fullscreenBox.visibility = if (wantsFullscreen && chromeVisible) VISIBLE else GONE
        val ratio = videoWidth.toFloat() / videoHeight
        // FIT 后实际画面高度：横版视频受屏宽限制，竖版视频在横屏版面里受屏高限制。
        val viewRatio = width.toFloat() / height
        val pictureHeight = if (ratio >= viewRatio) width / ratio else height.toFloat()
        val pictureBottom = (this.height + pictureHeight) / 2f
        val lp = fullscreenBox.layoutParams as LayoutParams
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL

        val gap = context.dp(10)
        // 按钮实际高度还没量出来时按一行小字估：字体档位调大后它也会跟着变高。
        val buttonHeight = fullscreenBox.measuredHeight.takeIf { it > 0 } ?: context.dp(14)
        // 下方信息块（标题 / 标签 / 简介 / 进度条）的最上沿。字号调大时它会往上顶，
        // 顶到按钮所在的这条黑边上就会把按钮盖住。
        val infoTop = if (::infoColumn.isInitialized && infoColumn.measuredHeight > 0) {
            (height - bottomInset() - context.dp(ACTION_BAR_DP) - context.dp(6) -
                infoColumn.measuredHeight).toFloat()
        } else height.toFloat()

        val below = pictureBottom + gap
        // 竖版画面在横屏里会占满整屏高度，`pictureBottom` 已经落到信息层后面。
        // 此时只翻到画面内还不够，必须继续限制在信息块上沿，否则按钮仍会被底栏盖住。
        val maxTop = infoTop - buttonHeight - gap
        val want = below.coerceAtMost(maxTop).toInt().coerceAtLeast(0)
        if (lp.topMargin != want) {
            lp.topMargin = want
            fullscreenBox.layoutParams = lp
        }
        // 首次布局时信息块可能还没量出高度（`measuredHeight` 为 0），上面会按「不会被遮挡」算。
        // 等这一帧排完再对一次；量到高度之后就不会再进来（不会无限 post）。
        if (::infoColumn.isInitialized && infoColumn.measuredHeight <= 0) {
            fullscreenBox.post { if (isAttachedToWindow) placeFullscreenButton() }
        }
    }

    private fun buildCenter() {
        centerBox = FrameLayout(context).apply {
            // 只留图标本身（用户要求：不要外面那圈全圆底板）。20 → 18dp。
            addView(centerGlyph, FrameLayout.LayoutParams(
                context.dp(18), context.dp(18), Gravity.CENTER
            ))
            visibility = GONE
        }
        addView(centerBox, LayoutParams(context.dp(36), context.dp(36)).apply {
            gravity = Gravity.CENTER
        })
    }

    /** 通栏操作条的底板：主题 `surface` + 顶部一条 1px 高光。 */
    private fun actionBarBackground(): android.graphics.drawable.Drawable {
        val alpha = ACTION_BAR_ALPHA
        return TopLineDrawable(
            withAlpha(PhoneStyle.surface, alpha),
            withAlpha(PhoneStyle.shadowLight, alpha),
            1
        )
    }

    /** 给一个 ARGB 色乘上不透明度（保留它自己的 alpha）。 */
    private fun withAlpha(color: Int, alpha: Float): Int =
        android.graphics.Color.argb(
            (android.graphics.Color.alpha(color) * alpha).toInt(),
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )

    /** 底色 + 顶部一条 1px 高光。方角、通栏，没有圆角与描边。 */
    private class TopLineDrawable(
        private val fill: Int,
        private val line: Int,
        private val linePx: Int,
    ) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: android.graphics.Canvas) {
            paint.color = fill
            canvas.drawRect(bounds, paint)
            paint.color = line
            canvas.drawRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), (bounds.top + linePx).toFloat(), paint
            )
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * 底部让开多少：信息流让开 tab 栏，正式播放页 **一点都不让**。
     *
     * 播放页是全屏沉浸（`enterImmersive` 把状态栏与导航条一起藏了），
     * 再按 `navigation_bar_height` 留白就会在条子下面空出一条 48dp 的黑带——
     * 用户读到的就是「信息悬在半空、底下空一格」。让开量归零之后条子贴住屏幕底边。
     */
    private fun bottomInset(): Int =
        if (withTabBar) context.phoneTabBarHeightPx() else 0

    private fun isLandscapePage(): Boolean =
        if (width > 0 && height > 0) {
            width > height
        } else {
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }

    // ------------------------------------------------------------------ 绑定

    fun bind(series: Series, episodeLabel: String, watchText: String? = null) {
        // 同一部剧重新 bind（从播放页退回来、换集）时**不要**清掉已知画面尺寸：
        // 清了之后「全屏」按钮会先消失、等下一次 `onVideoSizeChanged` 才回来，
        // 用户看到的是按钮闪一下。换剧才重置。
        val sameSeries = this.series?.id == series.id
        this.series = series
        // bind 只给一个安全的初值：完整画面。真正的档位由宿主按**实际视频尺寸**随后覆盖；
        // 卡片上的 `vertical` 偶有标错，不能在这里先按它把横屏片放大。
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        // 尺寸回调前先用卡片方向给一个与当前版面一致的初值；尺寸到手后仍以实际画面为准。
        wantsFullscreen = if (isLandscapePage()) series.vertical else !series.vertical
        if (!sameSeries) {
            videoWidth = 0
            videoHeight = 0
            // 换剧就清掉上一屏留下的暂停图标：ViewHolder 会被复用，`centerBox` 的状态
            // 跟着同一个 View 走。不清的话，用户在上一屏按过暂停，滑到下一屏（复用同一页）
            // 会先看到一枚暂停按钮，读起来就是「切过来是暂停的」。
            hideCenterIcon()
        }
        if (fullscreenEntry && ::fullscreenBox.isInitialized) {
            if (sameSeries && videoWidth > 0 && videoHeight > 0) {
                placeFullscreenButton()
            } else {
                fullscreenBox.visibility = GONE
            }
        }

        title.text = series.title

        tagRow.removeAllViews()
        val tags = ArrayList<String>(5)
        if (series.seasonIndex > 0) tags += "第${series.seasonIndex}季"
        series.tags.split(',', '·', '、', '|').map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("集") }
            .take(4).forEach { tags += it }
        tags.forEach { tag ->
            tagRow.addView(TextView(context).apply {
                text = tag
                setTextColor(Color.WHITE)
                phoneSize(12)
                setPadding(context.dp(6), context.dp(2), context.dp(6), context.dp(2))
                background = roundedBackground(0x33FFFFFF, context.dp(4).toFloat())
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = context.dp(5) })
        }
        tagRow.visibility = if (tags.isEmpty()) GONE else VISIBLE

        episodeIndex.text = episodeLabel.ifBlank { "第1集" }
        episodeDesc.text = series.description.replace('\n', ' ').trim()
        episodeDesc.visibility = if (episodeDesc.text.isBlank()) GONE else VISIBLE

        watchLabel.text = watchText ?: if (series.episodeCount > 0) {
            "观看完整短剧 · 全${series.episodeCount}集"
        } else {
            "观看完整短剧"
        }
    }

    fun currentSeries(): Series? = series

    /** 当前画面缩放比例（1f = 铺满）。布局尺寸变化时按它重新算一次。 */
    private var videoScale = 1f

    /**
     * 画面缩放（设置 → 播放偏好，1–100%）。
     *
     * **改的是 `PlayerView` 的布局宽高，不是 `scaleX/scaleY`**。
     *
     * 为什么不能用 View 缩放：`PlayerView` 里面是 `SurfaceView`，它单独占一块
     * Surface 图层。`scaleX/scaleY` 只改变 View 的绘制矩阵，Surface 图层不跟着变：
     * 缩小之后原来那一块大画面仍留在屏幕上，缩小的新画面只是叠在上面——用户读到的
     * 就是「原来的画面残留，把缩放后的画面挡住了」。
     *
     * 改布局宽高时 Surface 会按新尺寸重新分配缓冲区，旧内容没有地方可留；
     * 缩小后四周露出的是本页底板（整屏黑），与电视端的缩放效果一致。
     */
    fun setVideoScale(ratio: Float) {
        videoScale = ratio.coerceIn(.1f, 1f)
        applyVideoScale()
    }

    private fun applyVideoScale() {
        // 先把 View 变换清零：早期版本用 scaleX/scaleY 做过缩放，残留的矩阵会让
        // 新布局再被缩一次（小上加小，四周留白反而更大）。
        playerView.scaleX = 1f
        playerView.scaleY = 1f
        val lp = playerView.layoutParams as? LayoutParams ?: return
        if (videoScale >= .999f) {
            lp.width = LayoutParams.MATCH_PARENT
            lp.height = LayoutParams.MATCH_PARENT
        } else {
            val w = width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            val h = height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
            lp.width = (w * videoScale).toInt().coerceAtLeast(1)
            lp.height = (h * videoScale).toInt().coerceAtLeast(1)
        }
        lp.gravity = Gravity.CENTER
        playerView.layoutParams = lp
        playerView.requestLayout()
    }

    /** 播放进度（0..1）。用户正在拖进度条时不覆盖他的手。 */
    fun setProgress(fraction: Float) {
        if (userDragging) return
        val value = (fraction.coerceIn(0f, 1f) * 1000).toInt()
        if (seekBar.progress != value) seekBar.progress = value
    }

    // ------------------------------------------------------------------ 界面状态

    /** 整块信息（含两条渐变遮罩）显不显示。 */
    fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        infoColumn.visibility = if (visible) VISIBLE else GONE
        // 通栏条也是「信息」的一部分：只在 `infoColumn` 上切显隐的话，
        // 借给正式播放页时它会留在下面，和上面那一条叠成两层（用户看到的「重影」）。
        if (::actionBar.isInitialized) actionBar.visibility = if (visible) VISIBLE else GONE
        bottomShade.visibility = if (visible) VISIBLE else GONE
        if (fullscreenEntry && ::fullscreenBox.isInitialized) {
            fullscreenBox.visibility = if (visible && wantsFullscreen) VISIBLE else GONE
        }
        if (!visible) hideCenterIcon()
    }

    /**
     * 顶部那条压暗渐变要不要画（**只给「顶部真的有字」的时候**）。
     *
     * 早先它跟 [setChromeVisible] 一起开关：竖屏播放页只要控制层露着就画，
     * 而这一页顶部其实什么都没有——「播放显示」那一行是兄弟层 `portraitTop`，
     * 不在本页里。于是画面顶沿平白压了一条 110dp 的「51% 黑 → 透明」渐变，
     * 缓得只有在亮场画面上仔细对比才看得出（用户反馈的「全屏顶部有一些黑雾」）。
     *
     * 现在由宿主按「那一行是不是真的在显示」来决定：有字才垫底，没字就不画。
     * 推荐页（`underlay`）从不调用它，因此整页不会有这条暗带。
     */
    fun setTopScrimVisible(visible: Boolean) {
        topShade.visibility = if (visible) VISIBLE else GONE
    }

    fun isChromeVisible(): Boolean = chromeVisible

    /**
     * 本页只留画面，把 Surface 借给正式播放页（或收回）。
     *
     * 借出去时信息栏、弹幕、遮罩全收：播放页那边会画一份同样的控制层，
     * 两层叠着会看到两套标题、两条进度条、两层弹幕。
     */
    fun setUnderlayMode(on: Boolean) {
        if (on) {
            setChromeVisible(false)
            danmakuView.visibility = GONE
            hideCenterIcon()
            if (fullscreenEntry && ::fullscreenBox.isInitialized) fullscreenBox.visibility = GONE
        } else {
            setChromeVisible(true)
            // 弹幕的显隐由宿主按设置重刷（`applyDanmakuSettings`）：这里写死 VISIBLE
            // 会把用户关掉的弹幕又打开。
        }
    }

    /**
     * 屏幕中心的播放 / 暂停图标。
     *
     * 暂停时**一直显示**（用户需要看到「这里是暂停状态」），播放中只闪一下就走。
     */
    fun showCenterIcon(playing: Boolean, autoHideMs: Long = 700L) {
        centerGlyph.kind = if (playing) PhoneGlyph.Kind.PAUSE else PhoneGlyph.Kind.PLAY
        centerBox.visibility = VISIBLE
        centerBox.bringToFront()
        centerHideAt = System.currentTimeMillis() + autoHideMs
        if (playing) {
            postDelayed({
                if (System.currentTimeMillis() >= centerHideAt) centerBox.visibility = GONE
            }, autoHideMs)
        }
    }

    fun hideCenterIcon() {
        centerBox.visibility = GONE
        centerHideAt = 0L
    }

    fun setResizeMode(mode: Int) {
        playerView.resizeMode = mode
    }

    /**
     * 本层视频要不要显示。
     *
     * 借下层 Surface 时本层一直藏着；一旦下层不可用（横竖切换、推荐页被系统收走），
     * 宿主把本层打开并把播放器挂回来，画面才不会丢。
     */
    fun setVideoSurfaceVisible(visible: Boolean) {
        playerView.visibility = if (visible) VISIBLE else GONE
        // 从「借下层」退回「自己出画面」时，本页要重新变成不透明黑底，
        // 否则透过去的是下层另一部剧的画面，看起来像串台。
        setBackgroundColor(if (visible) Color.BLACK else Color.TRANSPARENT)
        if (visible) {
            playerView.setShutterBackgroundColor(Color.BLACK)
            playerView.setBackgroundColor(Color.BLACK)
        }
    }

}
