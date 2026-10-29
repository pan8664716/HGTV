// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hgtv.star.compat.drawRoundRectCompat
import com.hgtv.star.core.EpisodeLabel
import com.hgtv.star.core.Series
import com.hgtv.star.core.SeriesSeasons
import com.hgtv.star.compat.setVectorIcon
import com.hgtv.star.compat.vectorDrawable

/**
 * 设计 dp → 像素。
 *
 * 不再直接乘 [android.util.DisplayMetrics.density]：1280dp 宽的设计画布装进更窄的屏幕时，
 * 整体按 [designScale] 等比缩小（见 TvMetrics.kt），边距 / 按钮 / 海报 / 圆角 / 柔光带
 * 才会一起缩，不会出现「字缩了、内边距没缩」这种错位。
 */
internal fun Context.tvDp(value: Number) = designPx(value)

/** 柔光带的像素值：控件自己的内边距，也是投影 / 高光能画到的范围。 */
internal fun Context.softBand() = tvDp(TvStyle.SOFT_BAND)

/** 大面板（弹窗、设置分组、播放器底座）的柔光带。 */
internal fun Context.softBandLarge() = tvDp(TvStyle.SOFT_BAND_LARGE)

internal fun Context.tvText(
    value: String,
    size: Float,
    color: Int = TvStyle.text,
    bold: Boolean = false
): TextView = TextView(this).apply {
    text = value
    designTextSize(size)
    setTextColor(color)
    includeFontPadding = false
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

/**
 * 行 / 列容器：一律允许子控件画到自己边界之外。
 *
 * 焦点放大会让控件溢出槽位约 [TvStyle.FOCUS_BLEED]（见该常量的推导），
 * 因此中间任何一层行 / 列都不能裁剪——一旦裁剪，
 * 就会把贴在它边缘的那张卡片 / 那个按钮的焦点描边切掉。
 * 唯一负责裁剪的是滚动容器的外壳，见 [tvScrollHost]。
 */
internal fun <T : ViewGroup> T.focusOverflow(): T = apply {
    clipChildren = false
    clipToPadding = false
}

/**
 * 滚动容器：四边各留 [TvStyle.FOCUS_BLEED]，**自己不做裁剪**。
 *
 * 三件事缺一不可：
 *
 * 1. **余量留在容器内部**（内边距），不能靠外面的 padding——外面加 padding 只是把裁剪边界一起挪走。
 *    四边都要留：第一行控件放大后的焦点底色会画到内容顶边之上，最后一行会画到底边之下，
 *    只补左右时就会出现「左边框补回来了、上边框还是被切平」；纵向按 [TvStyle.FOCUS_BLEED_VERTICAL] 留，
 *    因为海报卡是竖的，向上溢出的绝对值比横向大。
 * 2. **容器自己 `clipChildren = false`**。这个开关管的是「子控件会不会被裁到子控件自己的边界上」，
 *    打开它 = 把列表裁在列表自己的边界上。列表本来就有整份内容那么高，裁了等于没裁，
 *    却正好把第一行 / 最左边那张卡放大后画到列表边界之外的那条描边切掉——
 *    这就是「最左边的选中框只剩一半、上边框看不见」的真正原因。
 * 3. `clipToPadding = false`：不额外裁到内边距框，第 1 条的余量才真的可用。
 *
 * 把滚动内容关在可视区里的那一道裁剪由 [tvScrollHost] 提供，两者不能互相替代。
 * 一句话：余量做在容器内部，裁剪做在容器外面。
 *
 * 新拟态之后投影 / 高光不再画到控件边界之外（见 [SoftDrawable]），
 * 因此这条余量现在只管「焦点放大 1.05 倍溢出的 2.5%」这一件事，规则不变。
 */
internal fun ScrollView.focusBleed(): ScrollView = apply {
    clipChildren = false
    clipToPadding = false
    val bleed = context.tvDp(TvStyle.FOCUS_BLEED)
    val bleedVertical = context.tvDp(TvStyle.FOCUS_BLEED_VERTICAL)
    setPadding(
        paddingLeft.coerceAtLeast(bleed),
        paddingTop.coerceAtLeast(bleedVertical),
        paddingRight.coerceAtLeast(bleed),
        paddingBottom.coerceAtLeast(bleedVertical)
    )
}

/**
 * 滚动容器的外壳：**裁剪边界由它提供**。
 *
 * 裁剪边界取的是**直接父级**的 `clipChildren`，不是控件自己的：硬件绘制路径里
 * `View.setDisplayListProperties()` 就是
 * `renderNode.setClipToBounds(mParent instanceof ViewGroup && ((ViewGroup) mParent).getClipChildren())`，
 * 软件绘制路径同样只看 `parentFlags & FLAG_CLIP_CHILDREN`。
 * 所以「滚动内容不许画到可视区之外」这件事只能由滚动容器的父级来做：
 * 父级裁剪 = 滚动容器被裁到自己的边界上 = 上滚的卡片不可能再盖住页头那一排按钮。
 *
 * 外壳里只放滚动容器一个子控件，因此打开裁剪不会碰到任何按钮的焦点放大；
 * 焦点余量仍然由滚动容器自己的内边距负责（见 [focusBleed]）。
 */
internal fun Context.tvScrollHost(scroll: View): FrameLayout = FrameLayout(this).apply {
    clipChildren = true
    clipToPadding = true
    addView(scroll, FrameLayout.LayoutParams(-1, -1))
}

/**
 * 把一个**长期持有**的控件重新挂到新容器上：先把它从旧父级里摘出来，再挂。
 *
 * 视图树里一个控件只能有一个父级，`ViewGroup.addView()` 遇到已经有父级的子控件会直接抛
 * `IllegalStateException: The specified child already has a parent. You must call removeView()
 * on the child's parent first.`。列表整体重建时（`contents.removeAllViews()`）只把**直接**
 * 子控件从树上摘下来，行里面的按钮仍然挂在旧行上，于是「第一次重建没事、第二次必崩」——
 * 触发时机取决于收藏更新检查、长按管理这类异步事件，表现出来就是偶发崩溃。
 *
 * 结论：凡是复用一个长期持有的按钮（例如首页的「刷新热门」），一律走这里重新挂载。
 */
internal fun ViewGroup.rehost(view: View, params: ViewGroup.LayoutParams? = null): View {
    (view.parent as? ViewGroup)?.removeView(view)
    if (params == null) addView(view) else addView(view, params)
    return view
}

internal fun Context.tvColumn() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    focusOverflow()
}

internal fun Context.tvRow() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    focusOverflow()
}

/**
 * 圆角用 outline 裁剪，而不是叠加遮罩位图：不额外分配内存，缩放后依然锐利。
 *
 * API 19/20 没有这套裁剪（见 [com.hgtv.star.compat.roundCornersCompat]），那边直接跳过——
 * 底板本身的圆角仍然由背景 Drawable 画出来，只是压在里面的封面图不再被切掉四个角。
 */
internal fun roundCorners(view: View, radiusDp: Int = TvStyle.RADIUS_POSTER) {
    com.hgtv.star.compat.roundCornersCompat(view, view.context.designPx(radiusDp).toFloat())
}

/** 粗体正文，卡片标题与按钮共用同一种字重节奏。 */
internal fun TextView.applyTitleStyle() {
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
}

/**
 * 按钮尺寸档：高度、横向内边距、字号、最小宽度四件事绑在一起，调用方只选档位，
 * 不再自己拼 padding。全站只有这三档（40 / 48 / 56dp 的**底板**高度）。
 */
internal enum class ButtonSize(val height: Int, val pad: Int, val text: Float) {
    /** 分类页筛选行：比紧凑档再小一档（30dp 底板 / 10dp 内边距 / 13sp）。 */
    DENSE(30, 10, 13f),

    /**
     * 弹窗档：**只有弹窗里的按钮用这一档**（底部动作行 + 选项列表）。
     *
     * 弹窗浮在页面之上，里面的按钮与页面同样大就会显得又长又宽：
     * 一排两三个按钮各自铺到一百多 dp，整框的重心全落在按钮上。
     * 收一档之后（36dp 底板 / 14dp 内边距 / 14sp）弹窗里的一切都小一圈，
     * 标题与内容重新成为主体。
     */
    DIALOG(TvStyle.BUTTON_DIALOG, TvStyle.BUTTON_PAD_DIALOG, TvStyle.BUTTON_TEXT_DIALOG),

    /**
     * 收紧档：高度与字号都跟 [COMPACT] 一样，只把左右内边距收到 12dp。
     *
     * 搜索页导航栏里那两枚按钮（搜索 / 手机输入）用它：那一行还挤着七个圆形入口，
     * 横向上每一 dp 都要省，但高度必须与同一行的输入框对齐，否则整行看起来歪的。
     */
    TIGHT(TvStyle.BUTTON_COMPACT, TvStyle.BUTTON_PAD_TIGHT, TvStyle.BUTTON_TEXT_COMPACT),

    COMPACT(TvStyle.BUTTON_COMPACT, TvStyle.BUTTON_PAD_COMPACT, TvStyle.BUTTON_TEXT_COMPACT),
    NORMAL(TvStyle.BUTTON_NORMAL, TvStyle.BUTTON_PAD_NORMAL, TvStyle.BUTTON_TEXT_NORMAL),
    HERO(TvStyle.BUTTON_HERO, TvStyle.BUTTON_PAD_HERO, TvStyle.BUTTON_TEXT_HERO),

    /**
     * 分区芯片档：**高度与常规档完全一致**（[TvStyle.BUTTON_NORMAL] 底板 + 两条柔光带 = 60dp），
     * 只把字号与左右内边距各收一档。
     *
     * 设置页顶部那四枚大项（播放交互 / 外观导航 / 内容推荐 / 系统高级）用它：
     * 与榜单页的榜单芯片**同高**（同一行里看着是一排控件，不会一高一矮），
     * 但中文标签按内容宽收紧，四枚并排也不会各占四分之一屏、把右边的圆形入口顶出去。
     */
    CHIP(TvStyle.BUTTON_NORMAL, TvStyle.BUTTON_PAD_TIGHT, TvStyle.BUTTON_TEXT_CHIP)
}

/**
 * 按钮语义。整个应用只有这三种外观，任何新按钮都必须从里面挑一种。
 */
internal enum class ButtonTone {
    /** 主操作：常亮的亮面板（一屏最多一个）。 */
    PRIMARY,

    /** 常规操作：凸起面板 + 白字。返回、分页、重试、收藏、刷新。 */
    SECONDARY,

    /** 破坏性操作：正文色换成主题状态色。删除、清空、退出登录。 */
    DANGER,

    /** 文字按钮：无底，只有字色与字重。更多 ›、播放器状态项。 */
    GHOST
}

/**
 * 按钮绘制：尺寸、圆角、底板、字色、缩放只有这一份实现。
 *
 * 状态只有五种，全部靠「凹 / 凸 / 亮」三个台阶 + 一圈焦点色细环表达：
 * - 常态 = 凸起面板（[TvStyle.surface]，右下深投影 + 左上高光）+ 正文色；
 * - 选中 = **按进去**（[TvStyle.selectedWell] + [SoftStyle.INSET]）+ [TvStyle.focusRing] 细环；
 * - 焦点 = 亮面板（[TvStyle.lit] + [TvStyle.onLit] 近黑字）+ 焦点色细环 + [TvStyle.litGlow] 光晕；
 * - 主操作 = 常亮的亮面板（不用点也知道这是本屏的主操作）；
 * - 禁用 = 压平的凹槽 + 次文字色。
 *
 * 焦点环与选中环一律取当前主题的 `--focus`，因此二十套配色下环色都随主题走，
 * 不会出现「底色换了、选框还是另一套颜色」。
 *
 * 控件高度 = 底板高度 + 两侧柔光带（[TvStyle.SOFT_BAND]），内边距同理，
 * 因此调用方给的 `pad` 只是「文字到面板边缘」的距离，投影永远有自己的地方。
 */
internal fun paintButton(
    view: TextView,
    size: ButtonSize,
    tone: ButtonTone,
    focused: Boolean,
    selected: Boolean = false,
    enabled: Boolean = true,
    pad: Int = size.pad,
    /**
     * 这个控件自己的两块底板（凸 / 凹），由调用方持有并复用。
     *
     * 焦点 / 选中切换只就地改配色（[SoftDrawable.restyle]），不新建 Drawable——
     * 新建的 Drawable 第一次 `draw` 时要查/建柔光位图，全部落在 UI 线程。
     */
    plates: Array<SoftDrawable?> = arrayOfNulls(2)
) {
    val context = view.context
    val dp = { value: Number -> context.tvDp(value) }
    val band = context.softBand()
    val pill = TvStyle.RADIUS_PILL
    view.designTextSize(size.text)
    view.setPadding(dp(pad) + band, band, dp(pad) + band, band)
    view.minimumHeight = dp(size.height + 2 * TvStyle.SOFT_BAND)
    // 文字按钮按内容宽收紧，其余按钮留一个最小宽度，短文案（「收藏」）才不会瘦成一条。
    // 分类页的 DENSE 档是筛选行专用：最小宽度只留 52dp，否则每条筛选都占 100dp 以上，
    // 一行放不下两枚，「按钮缩小」就无从谈起。
    view.minWidth = when {
        tone == ButtonTone.GHOST -> 0
        size == ButtonSize.DENSE -> dp(52) + band * 2
        // 分区芯片按内容宽收紧（「播放交互」四个字 ≈ 56dp + 两侧 12dp 内边距），
        // 不撑到常规档的 92dp——四枚并排的宽度直接决定它能不能留在导航栏那一行里。
        size == ButtonSize.CHIP -> dp(56) + band * 2
        // 收紧档（搜索页那两枚）：按内容宽收紧，短文案不再撑到 92dp。
        size == ButtonSize.TIGHT -> dp(TvStyle.BUTTON_MIN_WIDTH_TIGHT) + band * 2
        // 弹窗档按内容宽收紧：短文案（「取消」「确认」）只有几十 dp，不再撑到常规档的 92dp。
        size == ButtonSize.DIALOG -> dp(64) + band * 2
        else -> dp(TvStyle.BUTTON_MIN_WIDTH) + band * 2
    }
    view.setTypeface(view.typeface, Typeface.BOLD)
    view.setTextColor(
        when {
            !enabled -> TvStyle.muted
            focused -> TvStyle.onLit
            tone == ButtonTone.PRIMARY -> TvStyle.onLit
            tone == ButtonTone.DANGER -> TvStyle.danger
            tone == ButtonTone.GHOST -> TvStyle.textDim
            else -> TvStyle.text
        }
    )
    // 底板只在按钮第一次绘制时建一次（凸 / 凹各一块），之后焦点变化只就地改配色：
    // 新建 Drawable 的代价不是分配本身，而是它第一次 `draw` 时要查/建柔光位图，全在 UI 线程。
    // 凸起与凹陷的 Layers 结构不同（位图 vs 渐变），因此两者各留一块、按需创建。
    val useInset = selected && enabled && !focused
    val plate = plates[if(useInset) 1 else 0] ?: run {
        val made = if(useInset)
            TvStyle.well(context, TvStyle.selectedWell, pill, TvStyle.SOFT_BAND, depth = 1.15f)
        else TvStyle.soft(context, TvStyle.surface, pill, TvStyle.SOFT_BAND, SoftStyle.RAISED, depth = 1.2f)
        plates[if(useInset) 1 else 0] = made
        made
    }
    plate.restyle(
        fill = when {
            !enabled -> TvStyle.well
            focused -> TvStyle.lit
            selected -> TvStyle.selectedWell
            tone == ButtonTone.PRIMARY -> TvStyle.lit
            tone == ButtonTone.SECONDARY -> TvStyle.surface
            tone == ButtonTone.DANGER -> TvStyle.surface
            else -> Color.TRANSPARENT
        },
        // 焦点与选中都补一圈主题焦点色：设计稿里按钮聚焦是 3px 环，
        // 电视端按 2dp 画，落在柔光带里，不改任何布局尺寸。
        ring = when {
            !enabled -> Color.TRANSPARENT
            focused || selected -> TvStyle.focusRing
            else -> Color.TRANSPARENT
        },
        ringWidth = if (enabled && (focused || selected))
            context.designPx(TvStyle.RING).toFloat() else 0f,
        glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
        // 深度也进了位图；保持改版前的观感（焦点 1.2 / 主操作 1.1 / 常态 1）。
        depth = when {
            !enabled -> .45f
            useInset -> 1.15f
            focused -> 1.2f
            tone == ButtonTone.PRIMARY -> 1.1f
            else -> 1f
        }
    )
    if (view.background !== plate) view.background = plate
    view.alpha = if (enabled) 1f else TvStyle.DISABLED_ALPHA
}

/**
 * 全站唯一的按钮实现。尺寸走 [ButtonSize]、外观走 [ButtonTone]，
 * 焦点反馈（打亮 + 外发光 + 放大）与间距（外层用 [TvStyle.GAP_INLINE]）都不再由调用方决定。
 *
 * [alignStart] 用于设置页那种整行左对齐的列表项；其余按钮文字居中。
 */
internal open class TvButton(
    context: Context,
    label: String,
    private val size: ButtonSize = ButtonSize.NORMAL,
    private var tone: ButtonTone = ButtonTone.SECONDARY,
    private val alignStart: Boolean = false,
    private val action: () -> Unit = {},
    /**
     * 覆盖 [ButtonSize] 自带的左右内边距。
     *
     * 简介页那一行是四个**等宽**按钮，每个的可用宽度比整行按钮窄得多（还要再扣掉两条柔光带），
     * 用 HERO 档的 36dp 内边距会把「继续第 12 集」这类长文案挤成省略号。
     * 这一行因此用更小的内边距把文字宽度让出来，字号与高度不变。
     */
    private val pad: Int? = null,
    /**
     * 文字左侧的可选图标。
     *
     * 图标与文字是**同一个控件**里的两段内容（compound drawable），不是外面再包一层
     * `LinearLayout`：包一层会让焦点放大、点击区域、`contentDescription` 都变成两个对象，
     * 遥控器上还会多出一次「焦点落在图标上还是文字上」的歧义。
     *
     * 颜色跟着字色走（见 [paint]），因此焦点打亮、主操作、禁用三态都不需要各自再配一份图标。
     */
    private val iconRes: Int? = null
) : TextView(context) {
    private var selected = false
    /** 凸 / 凹两块底板，复用同一个 Drawable（见 [paintButton] 的 `plates`）。 */
    private val plates = arrayOfNulls<SoftDrawable>(2)
    /** 已挂上的图标（只在构造 / [setIcon] 时取；颜色靠 [paint] 里的 ColorFilter 改）。 */
    private var icon: Drawable? = null
    /** 当前图标对应的资源 id：`setIcon` 用它挡掉「同一个图标再设一次」的重排。 */
    private var iconId: Int? = null
    /** 图标与文字之间的间距（像素）。 */
    private var iconGap = 0

    /**
     * 额外的焦点回调（首页要靠它记住最后停留的入口）。
     * 底色、字色与缩放由 [TvButton] 自己完成，调用方不要覆盖 OnFocusChangeListener。
     */
    var onFocus: ((Boolean) -> Unit)? = null

    init {
        text = label
        gravity = if (alignStart) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // 只有**左对齐**的按钮开单行跑马灯（设置项这类长文案）。
        //
        // 居中按钮绝不能 `setSingleLine(true)`：那会让 TextView 按「很宽的一行」建 Layout，
        // 居中排版的文字起点因此落到控件外——表现就是「按钮还在、字没了」。
        // 居中按钮的文案本来就短（取消 / 关闭 / 确认），超宽时省略号收尾就够。
        if (alignStart) {
            setSingleLine(true)
            marqueeRepeatLimit = -1
        }
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        // 图标边长按档位取：紧凑档约 18dp、常规 22dp、主操作 26dp，
        // 与各自字号（15 / 16 / 17sp）的比例接近，换档时不会出现「图标比字大一头」。
        iconRes?.let { res ->
            val drawable=context.vectorDrawable(res)?.mutate()
            if(drawable!=null) {
                val side=context.tvDp(size.height*.46f)
                drawable.setBounds(0,0,side,side)
                icon=drawable
                iconGap=context.tvDp(6)
                iconId=res
            }
        }
        paint(false)
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            paint(focused)
            TvStyle.focus(view, focused)
            marquee(focused)
            onFocus?.invoke(focused)
        }
    }

    /**
     * 焦点进 / 出时切换「滚动 / 省略号」。
     *
     * 文案放不下时（长分类名、长搜索词、长设置值）：**拿到焦点**的那一枚跑马灯，
     * 其余仍是省略号——一屏里只有一枚在动。触发条件是「单行 + MARQUEE + selected」三件事，
     * 先取消选中再选中，是为了让复用 / 重复获得焦点的按钮从头开始滚。
     *
     * [TvSettingButton] 自己画文字，不用这一套（它有自己的跑马灯，见那边的重写）。
     */
    protected open fun marquee(focused: Boolean) {
        // 居中按钮不跑马灯（见 [init] 的说明）：文案短，省略号就够。
        if (!alignStart) return
        isSelected = false
        ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
        if (focused) {
            isSelected = true
            scrollTo(0, 0)
        }
    }

    /** 选中态：按进去（凹），与常态的凸起形成纯柔光对比。 */
    fun setSelectedState(value: Boolean) {
        if (selected == value) return
        selected = value
        paint(hasFocus())
    }

    /**
     * 就地改文案（「检查更新」→「正在检查…」这类）。
     *
     * 已经挂进父容器的按钮才改：`TextView.setText` 会走 `checkForRelayout`，
     * 那里直接读 `getLayoutParams().width`，而**还没进过任何父容器的控件 LayoutParams 是 null**，
     * 会抛 `NullPointerException`。调用方通常是在「建好 → 量宽 → 再放进行里」这个中间态改文案，
     * 因此这里直接挡掉：没有 LayoutParams 时只记下文案（`text` 本身能正常赋值吗？不能——所以跳过）。
     */
    open fun setLabel(value: String) {
        if (text.toString() == value) return
        if (layoutParams == null) return
        text = value
    }

    /** 选中态由外部数据改写后重画一次（播放器菜单的选项就是这样）。 */
    fun refresh() = paint(hasFocus())

    /**
     * 就地换图标（播放 / 暂停这类文案与图标一起变的按钮用）。
     *
     * 图标不是 compound drawable，而是构造时取的一份 [Drawable]（见 [onDraw]），
     * 因此换图标要**连 bounds 一起重设**：不重设的话新图标会沿用上一枚的尺寸，
     * 换到 viewport 不同的图标上就会偏大或偏小。
     *
     * 换完重排一次：图标宽度参与 [onMeasure] 的「文字 + 图标 + 间距」计算。
     */
    fun setIcon(res: Int?) {
        // 同一个图标反复设（播放状态每帧都可能刷一次）不必重排：只认真正换了的那一次。
        if(iconId==res) return
        iconId=res
        val drawable=res?.let { context.vectorDrawable(it)?.mutate() }
        if(drawable==null) { icon=null; iconGap=0 } else {
            val side=context.tvDp(size.height*.46f)
            drawable.setBounds(0,0,side,side)
            icon=drawable
            iconGap=context.tvDp(6)
        }
        paint(hasFocus())
        requestLayout()
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        isFocusable = enabled
        paint(hasFocus())
    }

    private fun paint(focused: Boolean) {
        paintButton(this, size, tone, focused, selected, isEnabled, pad ?: size.pad, plates)
        // 图标颜色跟着字色：焦点（近黑）、主操作（近黑）、常态（正文色）、禁用（40%）都由
        // `paintButton` 算好，这里只取结果，不再单独维护一套状态判断。
        icon?.setColorFilter(currentTextColor, PorterDuff.Mode.SRC_IN)
    }

    /**
     * 量宽时把图标让出来。
     *
     * 图标不是 compound drawable（见 [onDraw] 的说明），TextView 自己量不到它；
     * 这里先按纯文字量一次，再按「文字 + 图标 + 间距」重排一次。
     * 只对**非固定宽度**生效：`weight=1` 的等分按钮宽度由父容器给死，不需要也不该改。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val glyph=icon ?: return
        if(MeasureSpec.getMode(widthMeasureSpec)==MeasureSpec.EXACTLY) return
        val extra=glyph.bounds.width()+iconGap
        if(extra<=0) return
        val desired=measuredWidth+extra
        val width=if(MeasureSpec.getMode(widthMeasureSpec)==MeasureSpec.AT_MOST)
            minOf(desired,MeasureSpec.getSize(widthMeasureSpec)) else desired
        super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),heightMeasureSpec)
    }

    /**
     * 图标 + 文字作为**一个整体**居中。
     *
     * 不用 `setCompoundDrawablesRelative`：TextView 的 compound drawable 是贴在内容区
     * 左右边缘的，只有 `wrap_content`（内容宽度正好等于「图标 + 文字」）时看起来才居中；
     * 简介页那四个按钮是 `weight=1` 等分，文字居中之后图标会被留在最左边，
     * 中间空出一大段，读起来像「图标属于这一行」而不是「属于这个按钮」。
     *
     * 这里自己画：文字由 `super.onDraw` 按居中画好，再把整块画布右移半个图标宽，
     * 文字的中心因此落在「图标 + 文字」这个整体的中心右侧；图标按整体左边界画。
     * 文字长到放不下「图标 + 间距 + 文字」时退回纯文字（宁可少一个图标，也不让字被截断）。
     */
    override fun onDraw(canvas: Canvas) {
        val glyph=icon
        if(glyph==null) { super.onDraw(canvas); return }
        val contentWidth=(width-paddingLeft-paddingRight).toFloat()
        val textWidth=layout?.let { if(it.lineCount>0) it.getLineWidth(0) else 0f } ?: 0f
        val glyphWidth=glyph.bounds.width().toFloat()
        val block=glyphWidth+iconGap+textWidth
        if(textWidth<=0f || block>contentWidth) { super.onDraw(canvas); return }
        val shift=(glyphWidth+iconGap)/2f
        canvas.save()
        canvas.translate(shift,0f)
        super.onDraw(canvas)
        canvas.restore()
        val left=paddingLeft+(contentWidth-block)/2f
        val top=(height-glyph.bounds.height())/2f
        canvas.save()
        canvas.translate(left,top)
        glyph.draw(canvas)
        canvas.restore()
    }
}

/**
 * 设置页的整行设置项：左边是项名，右边是当前选中的值 + 一个「>」。
 *
 * 视觉与 [TvButton] 完全一致（同一套底板、焦点、按下态），只是文字分两段排版：
 * 左段按可用宽度自动省略，右段固定贴右，值后面跟一枚「>」提示这里还能选别的。
 *
 * 文案仍然沿用全站统一的「项名  ·  当前值」写法：构造时和 [setLabel] 都按这组分隔符
 * 拆成两段，调用方不需要为每个设置项单独传两个字符串。没有值的项（备份、清空缓存）
 * 右边只留「>」，看起来仍是一条可进入的设置项。
 */
internal open class TvSettingButton(
    context: Context,
    label: String,
    size: ButtonSize = ButtonSize.NORMAL,
    tone: ButtonTone = ButtonTone.SECONDARY,
    /**
     * 覆盖档位自带的左右内边距；null = 用档位值。
     *
     * [com.hgtv.star.tv.SettingSlider] 放在播放界面那块浮层里时要用它：那里的其它行
     * 是「左端 0dp 起的项目名 + 右侧一排紧凑档选项」，滑动条这一行如果按常规档的 28dp
     * 内边距起头，项名就会比上下相邻的行往右缩一截。
     */
    pad: Int? = null,
    action: () -> Unit = {}
) : TvButton(context, label, size, tone, alignStart = true, action = action, pad = pad) {
    private var rowName = ""
    private var rowValue = ""

    init {
        applyLabel(label)
    }

    /**
     * 用两段式文案重画。
     *
     * 覆盖两参数版 [TextView.setText]：一参数版是 final，属性赋值最终也会走到这里。
     * 父类构造期间就会调用一次，因此这里只负责拆词，真正落字走 [super.setText]，
     * 字段在子类初始化后再由 [init] 里的 [applyLabel] 补一遍。
     */
    override fun setText(text: CharSequence?, type: BufferType?) {
        applyLabel(text?.toString().orEmpty())
    }

    override fun setLabel(value: String) {
        // 与 [TvButton.setLabel] 同一条边界：还没进过父容器时 setText 会读到 null 的
        // LayoutParams。构造期的文案走 [init] 里的 [applyLabel]，不受这里影响。
        if (layoutParams == null) return
        applyLabel(value)
        invalidate()
    }

    /**
     * 文字是自绘的，TextView 自己的跑马灯用不上：这里挡掉父类那套（它只会让
     * `isSelected` 触发一个看不见的滚动动画），改由 [marqueeTick] 推偏移量。
     */
    override fun marquee(focused: Boolean) = Unit

    private fun applyLabel(raw: String) {
        val parts = raw.split("  ·  ", limit = 2)
        rowName = parts[0]
        rowValue = parts.getOrElse(1) { "" }
        super.setText(rowName, BufferType.NORMAL)
        contentDescription = if (rowValue.isEmpty()) rowName else "$rowName，$rowValue"
        marqueeOffset = 0f
        invalidate()
    }

    /**
     * 项名过长时的跑马灯位移（像素）。
     *
     * 项名是自绘的（[onDraw] 里 `drawText`），TextView 自己的 MARQUEE 用不上，
     * 因此这里按帧推进一个偏移量：只有**拿到焦点且真的放不下**时才动，
     * 一屏里同时滚动的只有这一行，失焦立刻回到行首。
     */
    private var marqueeOffset = 0f
    /** 需要滚动的距离（0 = 放得下，不滚）。 */
    private var marqueeSpan = 0f
    /** 这一帧的滚动是否已经排进消息队列（避免每帧重复 post）。 */
    private var marqueeScheduled = false
    private val marqueeTick = object : Runnable {
        override fun run() {
            marqueeScheduled = false
            if (!hasFocus() || marqueeSpan <= 0f) { marqueeOffset = 0f; invalidate(); return }
            marqueeOffset += context.tvDp(1.6f)
            // 滚到尾之后停一拍再回到行首：直接跳回去读起来像闪了一下。
            if (marqueeOffset > marqueeSpan + context.tvDp(16)) marqueeOffset = 0f
            invalidate()
            scheduleMarquee()
        }
    }

    /**
     * 排下一帧。
     *
     * 由 [onDraw] 在「真的有溢出 + 有焦点」时调用：构造 / 换文案之后要等第一次量宽才知道
     * 到底溢不溢出，因此不能只靠 [onFocusChanged] 起跑。
     */
    private fun scheduleMarquee() {
        if (marqueeScheduled || !hasFocus() || marqueeSpan <= 0f) return
        marqueeScheduled = true
        postDelayed(marqueeTick, 16L)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        removeCallbacks(marqueeTick)
        marqueeScheduled = false
        marqueeOffset = 0f
        if (gainFocus) scheduleMarquee() else invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(marqueeTick)
        marqueeScheduled = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val textPaint = paint
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val fm = textPaint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        val gap = context.tvDp(10).toFloat()
        val arrow = ">"
        val arrowWidth = textPaint.measureText(arrow)
        // 值最多占可用宽度的一半多一点：整行再宽也不该让值把项名挤成两三个字。
        val rowWidth = (right - left).coerceAtLeast(0f)
        val valueMax = (rowWidth * .55f - arrowWidth - gap).coerceAtLeast(0f)
        val valueText = when {
            rowValue.isEmpty() -> ""
            textPaint.measureText(rowValue) <= valueMax -> rowValue
            // 值本身也放不下时先省略（它是读数，不该画到屏幕外）。
            else -> TextUtils.ellipsize(rowValue, textPaint, valueMax, TextUtils.TruncateAt.END).toString()
        }
        val valueWidth = if (valueText.isEmpty()) 0f else textPaint.measureText(valueText)
        val valueBlock = if (valueText.isEmpty()) arrowWidth else valueWidth + gap + arrowWidth
        val nameRight = right - valueBlock - gap
        drawRowName(canvas, left, nameRight, baseline)
        val base = currentTextColor
        val valueAlpha = if (hasFocus() || !isEnabled) 0xFF else 0xC0
        if (valueText.isNotEmpty()) {
            textPaint.color = withAlpha(base, valueAlpha)
            canvas.drawText(valueText, right - arrowWidth - gap - valueWidth, baseline, textPaint)
        }
        textPaint.color = withAlpha(base, if (hasFocus() || !isEnabled) 0xFF else 0x99)
        canvas.drawText(arrow, right - arrowWidth, baseline, textPaint)
        textPaint.color = base
    }

    /**
     * 画左边的项名：放得下就正常画，放不下时**有焦点才滚**（裁在项名自己那一段里），
     * 没焦点退回省略号——一屏里不该有几行同时滚。
     *
     * [TvSwitchRow] 也用它：那一行右边画的是滑块而不是「>」，但项名同一套规则。
     */
    protected fun drawRowName(canvas: Canvas, left: Float, right: Float, baseline: Float) {
        val avail = (right - left).coerceAtLeast(0f)
        if (avail <= 0f) return
        val textPaint = paint
        val width = textPaint.measureText(rowName)
        marqueeSpan = (width - avail).coerceAtLeast(0f)
        textPaint.color = currentTextColor
        if (marqueeSpan <= 0f) {
            val shown = TextUtils.ellipsize(rowName, textPaint, avail, TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, left, baseline, textPaint)
            return
        }
        if (!hasFocus()) {
            // 没焦点不滚：省略号收尾，读得出「后面还有」。
            val shown = TextUtils.ellipsize(rowName, textPaint, avail, TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, left, baseline, textPaint)
            return
        }
        canvas.save()
        canvas.clipRect(left, 0f, right, height.toFloat())
        canvas.drawText(rowName, left - marqueeOffset.coerceAtMost(marqueeSpan), baseline, textPaint)
        canvas.restore()
        scheduleMarquee()
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}

/**
 * 顶部导航的圆形图标入口（搜索 / 排行榜 / 设置）。
 * 常态是凸起的同色圆盘，焦点是打亮的圆盘 + 光晕，当前页是抬一档的面（图标与常态同为正文色，
 * 靠「抬起来」而不是靠颜色区分）。
 *
 * 控件尺寸由柔光带撑开：调用方给的 48dp 是**底板**直径，
 * 因此这里自己保证最小边长 = 底板 + 两条柔光带。
 */
internal class IconButton(
    context: Context,
    iconRes: Int,
    contentLabel: String,
    /** 圆盘直径（不含柔光带）；设置页头部的小圆钮用紧凑档。 */
    private val diameter: Int = TvStyle.BUTTON_NORMAL,
    private val action: () -> Unit
) : FrameLayout(context) {
    private val dp = { value: Number -> context.tvDp(value) }
    private val icon = ImageView(context)
    private var active = false
    /** 唯一的底板：焦点 / 当前页只就地改配色（见 [SoftDrawable.restyle]）。 */
    private val plate = TvStyle.soft(
        context, TvStyle.surface, TvStyle.RADIUS_PILL, TvStyle.SOFT_BAND, SoftStyle.RAISED, depth = 1.2f
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        contentDescription = contentLabel
        icon.setVectorIcon(iconRes)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyph = if (diameter >= TvStyle.BUTTON_NORMAL) 24 else 20
        addView(icon, LayoutParams(dp(glyph), dp(glyph), Gravity.CENTER))
        minimumWidth = dp(diameter + 2 * TvStyle.SOFT_BAND)
        minimumHeight = dp(diameter + 2 * TvStyle.SOFT_BAND)
        background = plate
        paint(false)
        setOnClickListener { action() }
        setOnFocusChangeListener { _, focused ->
            paint(focused)
            TvStyle.focus(this, focused)
        }
    }

    fun setActive(value: Boolean) {
        active = value
        paint(false)
    }

    private fun paint(focused: Boolean) {
        plate.restyle(
            fill = when {
                focused -> TvStyle.lit
                // 当前页：抬一档 + 更深的柔光（周围是凸起的常态，抬起来就是「我在这里」）。
                active -> TvStyle.selectedFace
                else -> TvStyle.surface
            },
            glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth = when {
                focused -> 1.2f
                active -> 1.3f
                else -> 1f
            }
        )
        icon.setColorFilter(
            when {
                focused -> TvStyle.onLit
                else -> TvStyle.text
            }
        )
    }
}

/**
 * 海报底边那条已播进度条。
 *
 * 原先用 `LinearLayout + 两个 weight 子 View` 表达「已播 / 未播」：一张卡多两个 View，
 * 而且每次改进度都要写 layoutParams、两次 requestLayout。这里改成自绘——
 * 一张卡只占一个 View，改进度只是重画（[percent] 变了才 invalidate）。
 */
internal class PosterProgress(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius = context.tvDp(TvStyle.RADIUS_POSTER).toFloat()
    private val track = RectF()

    /** 已播百分比（0..100）；只有真的变了才重画。 */
    var percent: Int = 0
        set(value) {
            val next = value.coerceIn(0, 100)
            if (next == field) return
            field = next
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        track.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        // 底槽：只圆下面两角（与海报圆角对齐）。
        paint.color = TvStyle.wellDeep
        canvas.drawRoundRect(track, radius, radius, paint)
        // 已播段：只圆左下角。
        val filled = width * (percent / 100f)
        if (filled > 0f) {
            paint.color = TvStyle.accent
            canvas.drawRoundRect(RectF(0f, 0f, filled, height.toFloat()), radius, radius, paint)
        }
    }
}

/**
 * 竖版海报卡：一块凸起底板，里面嵌着海报井（内暗角）、角标、进度条与两行文字。
 *
 * 卡片的焦点态和按钮不同：卡里是图片，底板换不成亮面，因此焦点由「抬一档 + 更深的柔光
 * + 主题焦点色细环 + 外发光 + 剧名转全白」共同表达。
 *
 * 新拟态的处理：
 * - 整卡是一块 [TvStyle.surface] 凸起面板（圆角 [TvStyle.RADIUS_CARD]），焦点时换成抬升面 + 焦点色细环 + 外发光；
 * - 海报四周留 4dp 内衬，井口用 [SoftStyle.SHADE] 压一圈暗角，读起来像「嵌进底板」而不是「贴上去」；
 * - 进度条底槽换成更深的凹槽色，已播部分仍是强调色。
 *
 * 卡片自己带 [TvStyle.SOFT_BAND] 的柔光带（就是它的内边距），投影因此不会跑出控件边界。
 */
/**
 * 海报井：封面 + 一圈暗角 + 压在上面的进度条 / 角标。
 *
 * 暗角原先是一个铺满整块的子 View（每个都带一个 `SoftStyle.SHADE` 的 SoftDrawable）。
 * 首页 77 张卡 + 栅格窗口 30 张 = 100 多个纯装饰 View，每个都要走一次 measure/layout/draw，
 * 而它画的只是一层径向渐变；现在收进自绘，**View 数直接少掉一成**。
 *
 * 绘制顺序必须与原来一致：先画封面（child 0）、再压暗角、然后才是进度条与三枚角标
 *（角标不能被暗角压暗，否则右下角那枚「共 N 集」会变灰）。
 */
internal class PosterWell(context: Context) : FrameLayout(context) {
    private val shade = TvStyle.soft(context, Color.TRANSPARENT, 0, 0, SoftStyle.SHADE)

    init {
        clipChildren = true
        clipToPadding = true
    }

    override fun dispatchDraw(canvas: Canvas) {
        val count = childCount
        if (count == 0 || width <= 0 || height <= 0) return
        // child 0 是封面，其余（进度条 / 三枚角标）画在暗角之上。
        drawChild(canvas, getChildAt(0), drawingTime)
        shade.setBounds(0, 0, width, height)
        shade.draw(canvas)
        for (index in 1 until count) drawChild(canvas, getChildAt(index), drawingTime)
    }
}

internal class PosterCard(
    context: Context,
    posterHeight: Int,
    private val loadCover: (ImageView, String) -> Unit,
    /**
     * 卡片文字的整体缩放：每行 4 / 5 张时卡片更宽，剧名与状态行按同一比例放大，
     * 否则大卡片下面会吊着两行小字。1f = 旧版 6 列时的字号。
     */
    val textScale: Float = 1f
) : LinearLayout(context) {
    private val dp = { value: Number -> context.tvDp(value) }
    private val band = context.softBand()
    /**
     * 这一张卡**现在**是谁。
     *
     * 分类页的栅格是固定窗口 + 行复用（见 `MainActivity.syncGridWindow`）：卡片会被滚出窗口、
     * 回收、再拿去显示别的剧集，因此监听器里不能再捕获某一次的 item，
     * 一律通过这个字段取「当前绑定的是哪一部」。
     */
    var series: Series? = null
    val image = ImageView(context)
    // 卡片下方的剧名与状态：字号比正文小一档，一屏六列时不会盖过海报本身。
    // 剧名 16 → 18、状态行 13 → 14：电视上远看时 16sp 仍然偏小，两行一起抬一档、
    // 保持「剧名比状态大一档」的层次（类型标签行跟着状态行走）。
    val title = context.tvText("", 18f * textScale, TvStyle.textDim, bold = true)
    val status = context.tvText("", 14f * textScale, TvStyle.textDim)
    /**
     * 短剧类型标签：卡片第二行，**靠左**、占满整行。
     *
     * 来自 [Series.tags]（`category_schema` 里的题材名，最多 5 个、` · ` 连接）：
     * 列表接口自带这一项，不需要再为卡片第二行额外发请求。
     * 拿不到标签时才退回状态行（见 [syncMeta]），两者都没有就整行收起。
     */
    private val tagLine = context.tvText("", 14f * textScale, TvStyle.textDim)
    /** 第二行（类型标签）的容器：没有标签时整行隐藏，卡片高度不留空档。 */
    private lateinit var meta: LinearLayout
    /** 第二行优先显示状态（历史栏专用，见 [bind] 的 `preferStatus`）。 */
    private var preferStatus = false
    private val poster = PosterWell(context)
    /** 右下角角标：**总集数**优先（`99 集`）；没有集数时才退回名次 / 热度这类文案。 */
    private val badge = TextView(context)
    /**
     * 左上角角标：榜单名次（`第 3 名`），只在榜单页出现。
     *
     * 与另外两枚共用同一套字号 / 压暗层，但压暗方向朝左上（贴边的那一侧）。
     */
    private val rank = TextView(context)
    /**
     * 右上角角标：这一部是**第几季**（所有页面统一显示）。
     *
     * 季号优先用接口给的字段（见 [SeriesSeasons.badge]）
     *（只有详情页的「相关作品」那一栏才有），剧名是列表侧唯一的来源，
     * 因此没有写季号的剧名按第 1 季标，让每张卡都能一眼读出季数。
     * 与右下角那枚共用一套字号、字重、压暗层与内边距，只是一左一右。
     */
    private val season = TextView(context)
    /** 已播进度条：自绘，一张卡只占一个 View（原先的 track/fill/rest 是三个）。 */
    private val progress = PosterProgress(context)
    /**
     * 卡片的底板：**一张卡只有这一个 Drawable**，焦点态只就地改配色。
     *
     * 以前每次焦点变化都 `background = TvStyle.soft(...)` 新建一个：18 张卡来回走一遍就是
     * 几十个 Drawable，而且每个新 Drawable 第一次 `draw` 时都要查/建柔光位图，全部落在 UI 线程。
     * 现在 fill / 描边是每次绘制现画的纯色，只有外发光进了位图；同尺寸的卡共用同一张位图，
     * 因此焦点移动不再产生任何分配。
     */
    private val plate = TvStyle.soft(
        context, TvStyle.surface, TvStyle.RADIUS_CARD, TvStyle.SOFT_BAND,
        // heavy = true：海报卡一屏十几张、每张整卡面积，低配上关掉预渲染柔光位图
        // （见 Soft.shadowBitmaps）。按钮 / 弹窗不走这个开关。
        SoftStyle.RAISED, depth = 1.2f, heavy = true
    )
    /**
     * 焦点卡是否跑马灯。
     *
     * 低配**整条关掉**，关掉的不只是那一条文字动画：`setEllipsize()` 内部是
     * `nullLayouts() + requestLayout() + invalidate()`，而 `requestLayout()` 会让
     * **整条祖先链**跳过 measure 缓存重新 `onMeasure`（`ViewGroup.measure` 在
     * `forceLayout` 时 `cacheIndex = -1`）。
     *
     * 首页是未窗口化的一整棵树（7 栏 × 11 卡 ≈ 700 个 View），而一次焦点移动要切两次
     * ellipsize（旧卡 END、新卡 MARQUEE）= **两次全页测量**，10~30ms。
     * 电视上的观感就是「按一下、顿一下」，而且左右切换也会顿——这是本案最直接的元凶。
     *
     * [TvStyle.animate] 与低配判据同源（见 `MainActivity.onCreate`）。
     */
    private val marqueeOn = TvStyle.animate
    private var percent = -1
    private var cover = ""
    /** 总集数（0 = 未知）；右下角角标优先显示它。 */
    private var episodeCount = 0
    /** 没有集数时右下角要显示的那段文案（名次 / 热度 / 「正在看」）。 */
    private var fallbackBadge = ""

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        setPadding(band, band, band, band)

        roundCorners(poster, TvStyle.RADIUS_POSTER)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // 海报自己的圆角裁剪**不再单独挂一个 outline**：`poster` 已经 clipToOutline，
        // 子控件画在它的画布里，同样被裁成圆角。一张卡少一个 OutlineProvider 与一层硬件裁剪。
        poster.addView(image, FrameLayout.LayoutParams(-1, -1))

        // 井口暗角不再是一个子 View：由 [PosterWell.dispatchDraw] 画在封面之上、角标之下。

        // 进度条：一个自绘 View 画「底槽 + 已播段」，替代原来的 track/fill/rest 三个 View。
        progress.visibility = View.GONE
        poster.addView(progress, FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM))

        // 两枚角标共用一套样式：同样的字号、字重、压暗层与内边距，只是一左一右。
        // 压在封面上，因此**不跟主题**：浅色主题下 TvStyle.text 是深墨，
        // 压在黑压暗层上会完全看不见（同播放界面的规则）。
        // 三枚角标共用一套样式：同样的字号、字重、压暗层与内边距，分别贴三个角。
        // 压在封面上，因此**不跟主题**：浅色主题下 TvStyle.text 是深墨，
        // 压在黑压暗层上会完全看不见（同播放界面的规则）。
        listOf(badge to (Gravity.BOTTOM or Gravity.END), season to (Gravity.TOP or Gravity.END),
            rank to (Gravity.TOP or Gravity.START)).forEach { (view, place) ->
            view.designTextSize(TvStyle.POSTER_BADGE_TEXT * textScale)
            view.setTextColor(TvStyle.playerText)
            view.includeFontPadding = false
            view.setTypeface(view.typeface, Typeface.BOLD)
            view.gravity = place
            // 压暗层就是角标自己的背景（原先是一个单独的 View 压在下面）：
            // 少一个 View，而且不用 `setShadowLayer`——它在 API 26/27 上会把文字整块拉进软件层再上传位图。
            // 压暗方向都朝**贴边的那一侧**：下角标最深的一头在下、上角标最深的一头在上。
            view.background = when {
                place == (Gravity.TOP or Gravity.END) -> TvStyle.scrimTop(context, 0x99000000.toInt(), Color.TRANSPARENT)
                place == (Gravity.TOP or Gravity.START) -> TvStyle.scrimTopStart(context, 0x99000000.toInt(), Color.TRANSPARENT)
                else -> TvStyle.scrim(context, Color.TRANSPARENT, 0x99000000.toInt())
            }
            view.setPadding(dp(6), dp(3), dp(8), dp(3))
            view.visibility = View.GONE
        }
        // 右下角集数角标：只占内容宽度、贴右下角（原先拉满整行，是为了放名次那种长文案；
        // 现在右下角统一是「99 集」，一条通栏暗带没有必要）。
        poster.addView(
            badge,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(5)
                bottomMargin = dp(5)
            }
        )
        // 左上角名次（只在榜单页显示）。
        poster.addView(
            rank,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(5)
                topMargin = dp(5)
            }
        )
        // 右上角那一枚只占内容宽度（不拉满整行）：它是一枚小标签，不该在封面上铺出一条暗带。
        poster.addView(
            season,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(5)
                topMargin = dp(5)
            }
        )

        // 海报井比底板再内缩 4dp：井口才有「嵌进去」的厚度。
        addView(
            poster,
            LinearLayout.LayoutParams(-1, posterHeight).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                topMargin = dp(2)
            }
        )

        // 标题：**焦点卡跑马灯**，其余卡省略号收尾。
        //
        // 跑马灯在 TextView 里由「单行 + MARQUEE + selected」三件事共同触发，
        // 缺一个都不会动；因此常态是 END 省略，拿到焦点时再切成 MARQUEE 并置 selected
        //（见 [applyFocus]）。这样一屏里只有一张卡在动，不会互相打架。
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.setSingleLine(true)
        title.marqueeRepeatLimit = -1
        // 内边距跟着字号走：4 / 5 列的大卡片上，9dp 的边距配放大的文字会显得字挤在边上。
        title.setPadding(dp(9 * textScale), dp(8 * textScale), dp(9 * textScale), 0)
        // 第二行（类型标签）：只省略，不滚动——滚动的只有标题那一行。
        status.maxLines = 1
        status.setSingleLine(true)
        status.ellipsize = TextUtils.TruncateAt.END
        tagLine.maxLines = 1
        tagLine.setSingleLine(true)
        tagLine.ellipsize = TextUtils.TruncateAt.END
        tagLine.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        meta = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(9 * textScale), dp(4 * textScale), dp(9 * textScale), dp(3 * textScale))
            // 没有类型标签时整行收起：留一条只有内边距的空行会白占高度。
            visibility = View.GONE
        }
        // 第二行：**类型标签靠左**（集数已经挪到封面右下角，季号在右上角）。
        // 标签拿不到时退回状态行（「第 3 集 · 12:34」/ 热度）——那一行不是集数，
        // 而是「看到哪了」，隐藏它会让历史栏的卡片失去唯一的时间信息。
        // 标签占满剩余宽度（超长自己省略）：-2 会让它按内容宽量，长标签直接被卡片边缘切掉。
        meta.addView(tagLine, LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
        meta.addView(status, LinearLayout.LayoutParams(-2, -2))
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(meta, LinearLayout.LayoutParams(-1, -2))

        background = plate
        applyFocus(false)
    }

    /**
     * 绑定一张卡。
     *
     * 右上角季号（优先接口字段，见 [SeriesSeasons.badge]）在**所有页面**都显示；
     * [seasonBadge] 只留给确实不需要季号的调用方，默认开。
     */
    fun bind(
        item: Series,
        statusText: String,
        badgeText: String,
        progressPercent: Int?,
        rankTop: Boolean,
        seasonBadge: Boolean = true,
        /** 榜单名次（`第 3 名`）；非榜单页传空串，左上角不显示。 */
        rankText: String = "",
        /**
         * 第二行优先显示状态而不是类型标签。
         *
         * 只给**历史栏**用：那一栏的第二行是「第 x 集 · xx:xx」，回答「我看到哪了」，
         * 比类型标签重要。其余页面一律标签优先。
         */
        preferStatus: Boolean = false
    ) {
        this.preferStatus = preferStatus
        series = item
        title.text = item.title
        // 第二行：类型标签（靠左）。历史栏例外——那一栏的第二行是「第 x 集 · xx:xx」，
        // 回答「我看到哪了」，见 [preferStatus]。集数不写在这里，统一在封面右下角。
        status.text = EpisodeLabel.cardStatus(statusText)
        // 类型标签：列表接口自带（`category_schema`），不需要额外请求。
        tagLine.text = item.tags.trim()
        syncMeta()
        // 季号只在剧名里真的写了「第 N 季 / S2 / Season 2」时才显示：
        // 平台绝大多数短剧没有分季，给每张卡都补一个「第 1 季」是凭空猜的，
        // 满屏同一个角标反而盖住封面。没有季号的剧名，这一角留空。
        // 季号优先用接口给的字段（见 [SeriesSeasons.badge]）：单季的剧不标，多季或剧名里写了才标。
        val seasonText = if (seasonBadge) SeriesSeasons.badge(item) else ""
        season.text = seasonText
        season.visibility = if (seasonText.isBlank()) View.GONE else View.VISIBLE
        contentDescription = listOf(badgeText, seasonText, item.title, tagLine.text.toString(), status.text.toString())
            .filter { it.isNotBlank() }.joinToString("，")
        // 左上角名次（只在榜单页）。
        rank.text = rankText
        rank.visibility = if (rankText.isBlank()) View.GONE else View.VISIBLE
        rank.setTextColor(if (rankTop) TvStyle.tips else TvStyle.playerText)
        // 右下角：统一「共 N 集」；没有集数时才退回调用方给的角标（名次 / 热度）。
        fallbackBadge = badgeText
        badge.setTextColor(TvStyle.playerText)
        setEpisodeBadge(item.episodeCount)
        setProgress(progressPercent)
        setCover(item.cover)
    }

    fun setCover(url: String) {
        // `coverSuspended` 时不早退：那一张的位图被低内存策略摘掉了，
        // 地址没变也必须重新挂一次（见 [suspendCover]）。
        if (url == cover && !coverSuspended) return
        coverSuspended = false
        cover = url
        image.setImageDrawable(null)
        // 空 URL 也要通知加载器：它会把这这张图原来的请求解绑、取消排队。
        // 少了这一步，滚出窗口（或最后一行不满）的卡片会继续为一张看不见的图占着队列与网络。
        loadCover(image, url)
    }

    /**
     * 把封面从 View 上摘下来：**位图本身可能还在内存缓存里**，这里只解除这张卡的引用。
     *
     * 为什么必须做：LRU 淘汰一张图只解除 LRU 自己的引用；只要它还挂在某个 `ImageView`
     * 上就仍然活着。栅格的行会进回收池复用，池里那几行如果带着图，
     * 等于在缓存之外又多出一份「看不见但释放不掉」的位图——线上 OOM 的一半就是它。
     *
     * `cover` 一并清空：否则这一行被复用、又恰好绑到同一部剧时，[setCover] 会因为
     * 「地址没变」直接返回，卡片就停在空白上。
     */
    fun releaseCover() {
        cover = ""
        coverSuspended = false
        image.setImageDrawable(null)
        loadCover(image, "")
    }

    /** 封面被低内存策略摘下来了（URL 还留着，见 [suspendCover] / [resumeCover]）。 */
    var coverSuspended = false
        private set

    /**
     * 低内存时把封面从 `ImageView` 上摘下来，但**保留地址**。
     *
     * 与 [releaseCover] 的区别：那个是「这一行要拿去显示别的剧」时的清理，地址必须清掉；
     * 这个只是「暂时看不见」，URL 留着，回来时 [resumeCover] 直接按原地址重挂。
     *
     * 必须摘，因为位图挂在 `ImageView` 上时不受 `ArtworkLoader` 的缓存预算约束：
     * 首页 77 张常驻 ≈ 44MB，`evictAll()` 一点也收不回来。
     */
    fun suspendCover(): Boolean {
        if (cover.isBlank() || coverSuspended) return false
        coverSuspended = true
        image.setImageDrawable(null)
        // 解绑请求：绑定还在的话，加载器仍然认为这张图「已经贴上了」。
        loadCover(image, "")
        return true
    }

    /** 视口回来时重新挂上：内存缓存命中是 0ms，最坏从磁盘读 3~10ms。 */
    fun resumeCover(): Boolean {
        if (!coverSuspended) return false
        coverSuspended = false
        loadCover(image, cover)
        return true
    }

    fun setStatus(value: String) {
        if (status.text.toString() != value) status.text = value
        syncMeta()
    }

    /**
     * 第二行显示谁（**唯一一处**决定卡片第二行内容的逻辑）。
     *
     * | 场景 | 显示 |
     * | --- | --- |
     * | 历史栏（`preferStatus`） | 「第 x 集 · xx:xx」，没有进度时才退到类型标签 |
     * | 其余页面 | 类型标签；没有标签才退到状态（榜单热度 / 收藏更新） |
     * | 两者都没有 | 整行隐藏，卡片高度不留空档 |
     *
     * 集数**不在**这里——它统一在封面右下角「共 N 集」。
     */
    private fun syncMeta() {
        val hasTag = tagLine.text.isNotBlank()
        val hasStatus = status.text.isNotBlank()
        // 历史栏：状态优先（「第 x 集 · xx:xx」），没有状态才退到类型标签；
        // 其余页面：类型标签优先，没有标签才退到状态（榜单热度 / 收藏更新）。
        val showStatus = hasStatus && (preferStatus || !hasTag)
        tagLine.visibility = if (!showStatus && hasTag) View.VISIBLE else View.GONE
        status.visibility = if (showStatus) View.VISIBLE else View.GONE
        meta.visibility = if (showStatus || hasTag) View.VISIBLE else View.GONE
    }

    /**
     * 类型标签（第二行，**靠左**）。
     *
     * 列表接口自带这一项；卡片被就地更新（收藏 / 历史变化、复用换绑）时用它改这一格，
     * 不重建卡片、不动焦点。
     */
    fun setTags(value: String) {
        val next = value.trim()
        if (tagLine.text.toString() == next) return
        tagLine.text = next
        syncMeta()
    }

    /**
     * 右下角角标：统一「共 N 集」；没有集数时才退回调用方给的角标（热度 / 「正在看」这类）。
     */
    fun setEpisodeBadge(count: Int) {
        episodeCount = count
        val text = EpisodeLabel.total(count).ifEmpty { fallbackBadge }
        badge.text = text
        badge.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun setProgress(value: Int?) {
        val next = value?.coerceIn(0, 100) ?: -1
        if (next == percent) return
        percent = next
        progress.visibility = if (next < 0) View.GONE else View.VISIBLE
        if (next >= 0) progress.percent = next
    }

    fun resizePoster(height: Int) {
        val params = poster.layoutParams
        if (params.height == height) return
        params.height = height
        poster.layoutParams = params
    }

    fun setFocused(focused: Boolean) = applyFocus(focused)

    private fun applyFocus(focused: Boolean) {
        // 焦点态 = 打亮一档的底板 + **主题焦点色的细描边** + 光晕。
        //
        // 只靠明度差在「封面是彩图」的海报卡上不够醒目（见 TvStyle.focusRing 的说明），
        // 因此补这一圈：颜色取自当前主题的 focus，压到 90% 不透明、宽度 2dp，
        // 与卡片圆角同一半径，画在柔光带里，不改变任何布局尺寸。
        // 就地改配色：不新建 Drawable，也不改布局。深度固定，焦点靠「亮面 + 细环 + 光晕」表达。
        plate.restyle(
            fill = if (focused) TvStyle.surfaceHigh else TvStyle.surface,
            ring = if (focused) TvStyle.focusRing else Color.TRANSPARENT,
            ringWidth = if (focused) context.designPx(TvStyle.RING).toFloat() else 0f,
            glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth = if (focused) 1.35f else 1f
        )
        title.setTextColor(if (focused) TvStyle.text else TvStyle.textDim)
        status.setTextColor(if (focused) TvStyle.textStrong else TvStyle.textDim)
        tagLine.setTextColor(TvStyle.textDim)
        // 标题跑马灯：只有拿到焦点的那一张卡滚动（第二行是集数 / 进度，不滚）。
        //
        // 跑马灯由「单行 + MARQUEE + selected」共同触发；先取消选中再重新选中，
        // 是为了让**复用卡片**换绑到另一部剧时从头开始滚，而不是接着上一次的偏移。
        //
        // **低配一律静态省略号**（[marqueeOn] == false）：`setEllipsize()` 会走
        // `nullLayouts() + requestLayout()`，而 `requestLayout()` 让整条祖先链跳过
        // measure 缓存——首页 700 多个 View 会被全量重测一次，一次焦点移动发生两次
        // （旧卡 END、新卡 MARQUEE）。把 ellipsize 固定写成 END 之后，
        // `setEllipsize` 内部 `mEllipsize == ellipsize` 直接 return，**零 requestLayout**。
        if (marqueeOn) {
            title.isSelected = false
            title.ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
            if (focused) {
                title.isSelected = true
                // 同一张卡连续获得焦点（弹窗关闭后焦点回来）时 `isSelected` 没有变化，
                // TextView 不会重新开始滚动；把滚动位置拉回行首，让它重新走一遍。
                title.scrollTo(0, 0)
            }
        } else {
            title.isSelected = false
            title.ellipsize = TextUtils.TruncateAt.END
        }
        TvStyle.focus(this, focused)
    }
}

/**
 * 分区标题：一行粗体文字，从内容线开始。
 *
 * 2026-10-12 起标题前不再有强调色圆点：整页的节奏交给「标题—剧架」的间距本身，
 * 标题只留字，不再带任何装饰（`hint` 仍是可选的一段灰字说明）。
 *
 * 标题行也不挂「更多 ›」文字入口：剧架本身的行末已经有「查看全部」底板，
 * 行内按右键也直达集合页，标题再写一次是重复的。
 */
internal class SectionHeading(
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
internal class DialogItem(
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

/**
 * 互斥开关（排行榜页的四个榜单、分类页的大类与小类）：就是统一按钮的选中态——按进去的凹槽。
 *
 * [size] 让同一套控件能表达层级：分类页的**大类**用默认档，**小类**用紧凑档——
 * 二级分类比一级分类整体小一档（高度 48→40dp、字号 16→15sp、左右内边距 28→18dp），
 * 这样一眼能看出「这是大类下面的子类」，而不是两行一样大的按钮。
 */
internal class ToggleChip(
    context: Context,
    label: String,
    active: Boolean,
    size: ButtonSize = ButtonSize.NORMAL,
    action: () -> Unit
) : TvButton(context, label, size, ButtonTone.SECONDARY, action = action) {
    init { setSelectedState(active) }

    fun setActive(value: Boolean) = setSelectedState(value)
}

/**
 * 设置页的整行开关：左边是项名，右边是一枚自绘的轨道 + 圆钮。
 *
 * 为什么不用系统 `Switch`：它的配色、尺寸、焦点反馈都是 ROM 自带的一套，
 * 与全站的柔光材质对不上（浅色主题下尤其明显）。这里用和 [TvSettingButton]
 * 完全一致的底板与焦点反馈，只是右端画一枚开 / 关的滑块。
 *
 * 文案沿用「项名  ·  当前值」：右端**不画**「>」，直接画滑块，
 * 当前值是「显示 / 隐藏」这类两个字的读数。
 */
internal class TvSwitchRow(
    context: Context,
    label: String,
    private var checked: Boolean,
    private val onToggle: (Boolean) -> Unit
) : TvSettingButton(context, label, ButtonSize.NORMAL, ButtonTone.SECONDARY) {
    init {
        setOnClickListener {
            checked = !checked
            onToggle(checked)
            invalidate()
        }
    }

    /** 外部改了状态（例如从别处恢复设置）时重画一次。 */
    fun setChecked(value: Boolean) {
        if (checked == value) return
        checked = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val textPaint = paint
        val right = (width - paddingRight).toFloat()
        val trackWidth = context.tvDp(52).toFloat()
        val trackHeight = context.tvDp(26).toFloat()
        val left = right - trackWidth
        val top = (height - trackHeight) / 2f
        // 轨道：关闭 = 凹槽色，打开 = 强调色（只做「小色块」，与进度条同一条规则）。
        trackPaint.color = if (checked) TvStyle.accent else TvStyle.wellDeep
        drawRoundRectCompat(canvas, left, top, right, top + trackHeight, trackHeight / 2f, trackPaint)
        // 滑块：白面圆钮，左右各留 3dp 内缩。
        val inset = context.tvDp(3).toFloat()
        val knob = trackHeight - inset * 2
        val knobLeft = if (checked) right - inset - knob else left + inset
        canvas.drawCircle(knobLeft + knob / 2f, top + trackHeight / 2f, knob / 2f, knobPaint)

        // 项名：与设置项同一条左边缘，右边让出滑块与一段间距；
        // 过长时与设置项同一套（有焦点才滚，失焦省略号，见 [drawRowName]）。
        val gap = context.tvDp(12).toFloat()
        val nameRight = left - gap
        val fm = textPaint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        drawRowName(canvas, paddingLeft.toFloat(), nameRight, baseline)
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TvStyle.lit }
}

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
internal class EpisodeCell(
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

/**
 * 播放进度条（对应播放器控制层，**不套新拟态**）。
 *
 * 4dp 圆角轨道 + 18% 白已播进度 + 10dp 圆形滑块：这是播放界面的原始样式，
 * 播放时它压在画面上，任何柔光底板都会和画面抢注意力，因此这里保持原样。
 * 电视端左右键已经负责 seek，这里只做显示，因此直接自绘一个 View，
 * 比套一层 SeekBar 省掉三份 drawable，也不会抢焦点。
 */
internal class SeekIndicator(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = { value: Number -> context.tvDp(value) }

    /**
     * 手指拖动进度：非 null 时这个控件可点可拖（**电视上永远是 null**）。
     *
     * 回调第一个参数是 0–1 的进度比例，第二个是「这一次是不是抬手」。
     * 拖动期间只改显示、抬手才真跳，原因见 `MainActivity.onScrubProgress`：
     * 连续 `seekTo` 会让 ExoPlayer 反复重置渲染管线。
     *
     * 做成可空回调而不是新增一个控件：电视端的进度条一个字节都不变，
     * 触摸设备也只是多一条 `onTouchEvent`，画法（底槽 + 已播段 + 圆钮）完全复用。
     */
    var onScrub: ((fraction: Float, finished: Boolean) -> Unit)? = null
    /** 这一串事件是不是本控件自己在拖（决定抬手的处理要不要执行）。 */
    private var scrubbing = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val listener = onScrub ?: return false
        if (width <= 0) return false
        val fraction = (event.x / width).coerceIn(0f, 1f)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                scrubbing = true
                // 不让外层 ScrollView 抢走横向拖动：控制条整个在一层 ScrollView 里。
                parent?.requestDisallowInterceptTouchEvent(true)
                listener(fraction, false)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!scrubbing) return false
                scrubbing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                listener(fraction, true)
                true
            }
            else -> false
        }
    }

    /** 0f–1f 的播放进度，越界自动收敛。 */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val middle = height / 2f
        val trackHeight = dp(4).toFloat().coerceAtLeast(1f)
        val radius = trackHeight / 2f
        val top = middle - radius
        val bottom = middle + radius
        paint.color = TvStyle.playerLine
        drawRoundRectCompat(canvas, 0f, top, width.toFloat(), bottom, radius, paint)
        val knobRadius = dp(5).toFloat()
        val edge = width.toFloat() - knobRadius
        val played = (width * progress).coerceIn(0f, width.toFloat())
        paint.color = TvStyle.playerText
        if (played > radius) drawRoundRectCompat(canvas, 0f, top, played, bottom, radius, paint)
        // 进度条比圆钮还窄时（极窄屏 / 布局未完成），上界会小于下界，coerceIn 会抛异常。
        if (width > 0) canvas.drawCircle(played.coerceIn(knobRadius, edge.coerceAtLeast(knobRadius)), middle, knobRadius, paint)
    }
}
