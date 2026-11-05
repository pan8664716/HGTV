// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.abs

/**
 * 手机 / 平板的视觉令牌。
 *
 * **数值不是拍出来的**：全部来自官方 App（`com.phoenix.read` 7.3.x）在
 * 1080×1920 / 280dpi（1.75×）下的实测控件树，按 px ÷ 1.75 折成 dp：
 *
 * | 元素 | 官方 px | dp |
 * | --- | --- | --- |
 * | 页面左右边距 | 21 | 12 |
 * | 底部导航栏高 | 87 | 50 |
 * | 底部导航左右边距 | 14 | 8 |
 * | 频道 chip 行高 | 79 | 45 |
 * | 内容卡片间距 | 14 | 8 |
 * | 剧场卡片宽 | 512 | 293 |
 * | 剧名 | 38 | 22sp |
 * | 卡片标题 | 34 | 19sp |
 * | 标签 / 热评 | 24–34 | 14–19sp |
 * | 播放页右侧操作项高 | 113 | 65 |
 *
 * 品牌色 `#FE2C55` 取自官方 `res/values/colors.xml`（`ad` / `ba` / `bj` 三处同值）。
 */
object PhoneColor {
    /** 页面底色：石墨，也是柔光的基准色。 */
    val BG: Int get() = PhoneStyle.background
    /** 有内容页（剧场 / 我的）的底，与首页同一块材质。 */
    val BG_SURFACE: Int get() = PhoneStyle.background
    /** 卡片 / 按钮的凸起面。 */
    val SURFACE: Int get() = PhoneStyle.surface
    /** 抬一档的面（选中、浮层）。 */
    val SURFACE_HIGH: Int get() = PhoneStyle.surfaceHigh
    /** 品牌红：只做线与小色块。 */
    val PRIMARY: Int get() = PhoneStyle.accent
    val TEXT: Int get() = PhoneStyle.text
    val TEXT_DIM: Int get() = PhoneStyle.textDim
    val TEXT_FAINT: Int get() = PhoneStyle.textFaint
    val DIVIDER: Int get() = PhoneStyle.separator
    /**
     * 底部导航栏自身高度（dp）。
     *
     * 官方实测 87px ÷ 1.75 = 50dp，那是把状态栏也一起算进去的松版。
     * 字阶定到 50% 之后，一行 13 号字只有约 8dp 高：**16dp 图标 + 2dp 间隙 + 8dp 字
     * = 26dp 内容**，42dp 上下各留 8dp，图标压在格子的中线上、字贴底，
     * 是 iOS / Material 底栏的常规比例（图标 : 条高 ≈ 2 : 5）。
     *
     * **42 → 34**（用户要求「太高、整体小一点」）：图标 16 → 14dp、字 13 → 11 号、
     * 间隙 2 → 1dp，内容约 21dp，上下各留 6.5dp。整条比原来矮 19%。
     */
    const val TAB_BAR_DP = 34f
    /** 横屏沉浸时底栏进一步压扁。 */
    const val TAB_BAR_LANDSCAPE_DP = 24f
}

/** 与官方实测一致的尺寸令牌（dp）。 */
object PhoneDimen {
    /**
     * 海报网格的左右边距：**0**，卡片直接顶到屏幕边缘。
     *
     * 两列布局里这一圈白边完全是从卡宽里扣的；用户在连续几轮里都在要「卡片更大」，
     * 这里是最后一个可让的量。
     */
    const val PAGE_INSET = 0f
    /** 底部导航栏左右留白（官方 14px）。 */
    const val TAB_INSET = 8f
    /** 卡片间距（官方 14px）。 */
    const val CARD_GAP = 8f
    /**
     * 海报卡之间的间距（横纵同值）：**0**，两列直接贴合。
     *
     * 卡与卡之间靠卡片自带的柔光带（各 2dp）分开，看起来是一整块画面被一条细影切开。
     */
    const val POSTER_GAP = 0f
    /** 频道 chip 行高（官方 79px）。 */
    const val CHIP_ROW = 45f
    /** 剧场卡片宽（官方 512px）。 */
    const val POSTER_WIDTH = 293f
    /** 播放页右侧操作项高度（官方 113px）。 */
    const val ACTION_ITEM = 65f
}

/**
 * 手机端设计画布。与 TV 的 `TvMetrics` 同一条思路：**只按屏幕像素算，不读 density**。
 *
 * 竖屏设计画布 360 × 640，横屏 640 × 360（数值就是原来那套 dp 令牌的基准）。
 * 屏幕上的实际比例取 `min(屏宽 / 设计宽, 屏高 / 设计高)`：
 *
 * ```
 * 1080×1920  →  min(3.00, 3.00) = 3.00   （与 480dpi 下 12dp = 36px 完全一致）
 * 2160×3840  →  min(6.00, 6.00) = 6.00   （4K 上整套版面等比放大一倍）
 * 1920×1080  →  min(3.00, 3.00) = 3.00   （横屏用 640×360 画布）
 * ```
 *
 * 为什么不再乘 density：density 是厂商随手填的数，同一个 1080P 屏可以是 240 / 320 / 480 /
 * 540dpi。跟 density 走，`wm density` 改一档，字号与卡片就整体放大或缩小一截，而屏幕
 * 的像素尺寸根本没变。TV 端已经踩过这个坑（见 `TvMetrics` 注释），手机端沿用同一套：
 * 版面比例只跟屏幕像素走，density 变化不影响任何一块尺寸。
 */
fun Context.phoneScale(): Float {
    // **必须取整块屏幕的物理像素**，不能取 `resources.displayMetrics`：
    // 后者是「本窗口可用区域」，已经扣掉状态栏 / 导航条，而且扣掉的像素数
    // 本身又随 density 变（24dp 在 480dpi 与 540dpi 下不是同一个 px）。
    // 用它算比例时，同一块 2160×3840 屏只改 dpi，比例就会从 6.00 掉到 5.66，
    // 整套版面跟着缩 6%——用户读到的就是「改 dpi 大小变了」。
    val metrics = android.util.DisplayMetrics()
    val wm = getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
    val real = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bounds = wm?.currentWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
                true
            } else false
        } else false
    }.getOrDefault(false)
    if (!real) {
        @Suppress("DEPRECATION")
        val display = wm?.defaultDisplay
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            val fallback = resources.displayMetrics
            metrics.widthPixels = fallback.widthPixels
            metrics.heightPixels = fallback.heightPixels
        }
    }
    val width = metrics.widthPixels
    val height = metrics.heightPixels
    if (width <= 0 || height <= 0) return 1f
    val portrait = height >= width
    val designWidth = if (portrait) 360f else 640f
    val designHeight = if (portrait) 640f else 360f
    val screenScale = minOf(width / designWidth, height / designHeight).coerceIn(.45f, 8f)
    return screenScale * PhoneUiScale.value
}

/**
 * 手机端字体与控件大小的统一倍率。
 *
 * 只放大文字会让单行文案挤进原来的按钮，因此 [phoneScale] 把这一档同时乘进
 * 字号、按钮高度、图标、内边距与卡片尺寸。手机端以中间档为默认，
 * 两侧各留出调整空间，放大不会再让单个按钮独自变形。
 */
object PhoneUiScale {

    enum class Size(val scale: Float, val label: String) {
        TINY(1.3f, "超小"),
        SMALL(1.45f, "较小"),
        STANDARD(1.6f, "标准"),
        LARGE(1.75f, "较大"),
        HUGE(1.9f, "超大"),
    }

    val sizes = Size.entries
    val default = Size.STANDARD.scale

    private const val PREFS = "phone-display-v1"
    private const val KEY = "uiScale"
    private const val KEY_DEFAULT_MIGRATED = "hugeDefaultMigrated"
    private const val LEGACY_DEFAULT = 1.3f

    @Volatile private var current = default
    @Volatile private var loaded = false

    /** 当前倍率。Activity 建视图前会通过 [ensure] 从磁盘读一次。 */
    val value: Float get() = current

    fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getFloat(KEY, default)
            // 旧版的默认档是 1.3。升级后只迁移这一个默认值；
            // 用户明确选过的其他档位原样保留。
            val migrate = !prefs.getBoolean(KEY_DEFAULT_MIGRATED, false)
            current = if (migrate && (!prefs.contains(KEY) || abs(saved - LEGACY_DEFAULT) < .001f)) {
                default
            } else {
                normalize(saved)
            }
            if (migrate) {
                prefs.edit()
                    .putFloat(KEY, current)
                    .putBoolean(KEY_DEFAULT_MIGRATED, true)
                    .apply()
            }
            loaded = true
        }
    }

    /** 落盘并更新进程内的倍率；调用方负责重建界面，让已有控件按新尺寸重新创建。 */
    fun set(context: Context, value: Float) {
        val next = normalize(value)
        current = next
        loaded = true
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY, next)
            .putBoolean(KEY_DEFAULT_MIGRATED, true)
            .apply()
    }

    fun sizeOf(value: Float): Size =
        sizes.minByOrNull { abs(it.scale - value) } ?: Size.STANDARD

    fun label(value: Float): String = sizeOf(value).label

    private fun normalize(value: Float): Float =
        value.coerceIn(sizes.first().scale, sizes.last().scale)
}

/** 设计尺寸 → 像素。全站唯一的换算出口（TV 的 `designPx` 同一条）。 */
fun Context.dp(value: Number): Int =
    (value.toFloat() * phoneScale() + .5f).toInt()

/** View 里直接写 `dp(16)`：View 不是 Context，这里补一层转发。 */
fun android.view.View.dp(value: Number): Int = context.dp(value)

/**
 * 字阶轴心：设计字号围绕它做压缩，等于「正文档」。
 *
 * 取 15 是因为实测控件树里出现最多的字号是 12 / 13 / 15 / 17——
 * 正文（13）与副标题（15）正好跨在轴心两侧，压缩后两者仍然分得开。
 */
private const val TYPE_AXIS = 15f

/**
 * 字阶压缩比：`0.75` = 偏离轴心 4 档只走 3 档。
 *
 * 为什么不是「整体乘一个系数」：设计稿的字号是照官方 1080P 实测折出来的，
 * 直接乘系数会把**小字缩得更小**（12 × 0.9 = 10.8，等于把注释字压成蚂蚁），
 * 而标题本来就够大、再放大就抢画面。用户反馈的正是这两头：
 * 「文字太小」指的是正文 / 标签，「按钮太大」指的是按钮里的字只占按钮高度三成。
 *
 * 绕轴心压缩同时解决两头：
 *
 * | 设计字号 | 直接 ×0.9 | 轴心压缩 | 相对现在 |
 * | --- | --- | --- | --- |
 * | 10（角标） | 9.0 | 11.25 | +25% |
 * | 12（说明 / 标签） | 10.8 | 12.75 | +18% |
 * | 13（正文） | 11.7 | 13.50 | +15% |
 * | 15（副标题） | 13.5 | 15.00 | +11% |
 * | 17（页面标题） | 15.3 | 16.50 | +8% |
 * | 20（大标题） | 18.0 | 18.75 | +4% |
 *
 * 结果是一条 11–19 的紧字阶：小字抬起来读得清，大字收一点不压版面，
 * 层级（角标 < 说明 < 正文 < 副标题 < 标题）一档不减。
 */
private const val TYPE_COMPRESS = .75f

/**
 * 设计字号 → 像素。
 *
 * 先绕 [TYPE_AXIS] 做光学压缩，再乘 [phoneScale]。
 * **不乘 density**：density 是厂商随手填的数，同一个 1080P 屏可以是 240 / 480 / 540dpi，
 * 跟它走等于 `wm density` 一改字就整体变一档。这里只认屏幕真实像素，
 * 所以 1080×1920 与 2160×3840 是严格 2 倍，改 dpi 一个像素都不动。
 *
 * 最后乘固定的 [PHONE_TEXT_SCALE]（50%）与用户选的 [PhoneUiScale]：
 * 前者是整套手机版面的字号基准，后者让字体和控件一起缩放。
 *
 * 系统无障碍字号仍尊重，但夹在 1.0–1.3（与 TV 的 `designTextSize` 同一条）：
 * 再大就会把按钮里的单行文案挤成省略号。
 */
fun Context.sp(value: Number): Float {
    val optical = TYPE_AXIS + (value.toFloat() - TYPE_AXIS) * TYPE_COMPRESS
    return optical * phoneScale() * PHONE_TEXT_SCALE *
        resources.configuration.fontScale.coerceIn(1f, 1.3f)
}

/**
 * 手机端**全局字号基线：50%**。
 *
 * 设计稿的 13 / 15 / 17 是照官方 1080P 截图折出来的 dp，那套值在手机上一直偏大
 * （13 × 3 = 39px，正文占屏幕宽 3.6%，比微信正文还粗一圈）。实测的结论是
 * **整套字阶压到一半**才正常：13 → 6.5、15 → 7.5、17 → 8.5（设计单位），
 * 在 1080×1920 上分别是 19.5 / 22.5 / 25.5px，与主流阅读类 App 的正文密度对齐。
 *
 * 这是字号的固定基准；用户在设置里调的 [PhoneUiScale] 会同时乘到字号与全部 dp 尺寸上，
 * 按钮高度、内边距、图标和卡片因此一起变化，不会出现「字变大、按钮没变」。
 */
const val PHONE_TEXT_SCALE = .5f

fun TextView.phoneSize(value: Number): TextView =
    apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, context.sp(value)) }

/** 系统导航条高度（手势导航的机型为 0）。 */
fun Context.navigationBarPx(): Int {
    val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}

/**
 * 底部导航栏占掉的总高度：栏本身 + 系统导航条。
 *
 * 全屏内容要按这个数往上留白，否则最后一行会被 tab 栏压住。
 */
fun Context.phoneTabBarHeightPx(): Int {
    val landscape = resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    return dp(if (landscape) PhoneColor.TAB_BAR_LANDSCAPE_DP else PhoneColor.TAB_BAR_DP)
}

fun Context.tabBarInset(): Int {
    val landscape = resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    return phoneTabBarHeightPx() + if (landscape) 0 else navigationBarPx()
}

/** 圆角实心背景。 */
fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = radiusDp * 1f
}

/** 带描边的圆角背景。 */
fun strokedBackground(color: Int, strokeColor: Int, radiusDp: Float, strokeDp: Float = 1f): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        setStroke(strokeDp.toInt().coerceAtLeast(1), strokeColor)
        cornerRadius = radiusDp
    }

/**
 * 播放页左下信息区的渐变遮罩。
 *
 * 官方是「视频铺满 + 底部文字直接压在画面上」，没有遮罩时浅色画面上的白字会糊掉。
 *
 * **只在最下面一条里变黑**：早先这条渐变铺满整屏、三个色标等分，屏幕正中就已经是
 * 50% 黑——用户读到的「非全屏播放页像蒙了一层遮罩」就是它。
 * 现在用不等距色标（底部不透明 → 55% 处只剩三成 → 顶端全透明），
 * 配合调用方把这一层限高到屏高的一半，画面中间与上半部是干净的。
 */
fun bottomScrim(alpha: Int = 235): Drawable = VerticalGradientScrim(
    intArrayOf(
        Color.argb(alpha, 0, 0, 0),
        Color.argb((alpha * .55f).toInt(), 0, 0, 0),
        Color.argb((alpha * .18f).toInt(), 0, 0, 0),
        Color.TRANSPARENT
    ),
    floatArrayOf(0f, .45f, .78f, 1f)
)

/**
 * `GradientDrawable.setColors(int[], float[])` 是 API 29 才加的重载；
 * minSdk 19 上直接调用会在布局阶段抛 `NoSuchMethodError`。
 */
private class VerticalGradientScrim(
    private val colors: IntArray,
    private val positions: FloatArray,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        paint.shader = LinearGradient(
            0f,
            bounds.bottom.toFloat(),
            0f,
            bounds.top.toFloat(),
            colors,
            positions,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 顶部遮罩（沉浸式下返回按钮与集数压在画面上）。 */
fun topScrim(alpha: Int = 150): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(Color.argb(alpha, 0, 0, 0), Color.TRANSPARENT)
)
