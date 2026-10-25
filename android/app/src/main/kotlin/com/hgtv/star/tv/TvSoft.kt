// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.hgtv.star.compat.memoryBytes

/**
 * 新拟态的底板形态。
 *
 * 新拟态的全部层次都由「同一块底色的凸起 / 凹陷」表达，因此形状只有四种：
 * 凸起（[RAISED]）、凹陷（[INSET]）、只有一圈描边（[RING]）、压在图片上的内暗角（[SHADE]）。
 * 任何控件的外观都必须从这里挑一种，不再自己拼 GradientDrawable 的描边。
 */
internal enum class SoftStyle { RAISED, INSET, RING, SHADE }

/**
 * 柔光（新拟态）绘制核心。
 *
 * ## 为什么需要柔光带
 *
 * 新拟态的「凸起」= 右下一条深投影 + 左上一条浅高光；「凹陷」= 底板压暗 + 内阴影。
 * Android 的 `GradientDrawable` 没有模糊，`setShadowLayer` 又只在软件画布上生效
 * （直接用在硬件加速的 View 背景上不画阴影），`RenderEffect` 要 API 31 且每次绘制都要重新模糊。
 * 因此这里把柔光**预渲染成一张位图**，控件每帧只是画一张已经模糊好的图：
 *
 * - 位图按控件尺寸做 LRU 缓存，同一屏里尺寸相同的卡片（一架子海报、一排按钮）共用一张；
 * - 位图按 [SCALE] 半分辨率渲染再放大：投影本来就是模糊的，放大看不出来，显存和生成时间各省 4 倍；
 * - 低内存设备把 [quality] 调小：投影更紧、更淡，生成也更快，层次不丢。
 *
 * ## 为什么柔光画在控件**内部**的留白里
 *
 * 投影如果画到控件边界之外，就要求父级不裁剪；而滚动容器的裁剪规则（见 `tvScrollHost`）
 * 恰恰必须打开。两者会互相打架：要么描边被切掉，要么卡片盖住页头。
 * 因此每个控件四周留出 [TvStyle.SOFT_BAND] 的透明带（就是它自己的 padding），
 * 底板画在这条带里面。投影、高光、焦点环全部落在控件自己的边界内，
 * 滚动容器的裁剪与焦点余量都只负责「放大 1.05 倍溢出的 2.5%」这一件事。
 */
internal object Soft {
    /** 低内存设备在 onCreate 里调小；只影响柔光的紧致度，不影响层次数量。 */
    var quality = 1f

    /**
     * 凸起面是否绘制**预渲染柔光位图**（`SoftStyle.RAISED` 的那张半分辨率模糊图）。
     *
     * 关掉它是弱 GPU 上收益最大的一刀：
     *
     * - 位图是**半分辨率**的（[SCALE]），每帧按控件尺寸**双线性放大**铺满整张卡。
     *   1080p 上一张卡约 267×424 px，一屏 18 张卡 = **2.0 M 像素/帧**的纹理采样 + alpha 混合；
     * - 而它真正可见的只有四周 [TvStyle.SOFT_BAND] 的柔光带：中间整块立刻被不透明底板
     *   `fill()` 盖住，HWUI 没有 early-Z，被盖住的像素照样付带宽；
     * - 低配因此只在**焦点卡**上画（带外发光的那一档，`glow` 不为透明），
     *   非焦点卡退回纯色圆角矩形。卡片有彩色封面兜底，投影在深色底上本来就几乎看不出来，
     *   视觉损失接近零，而填充量从 18 张降到 1 张。
     *
     * **只作用于「大面积、一屏十几份」的底板**（见 [SoftDrawable.heavy]）：按钮与弹窗面板
     * 一屏只有三五个，画的代价可以忽略，而它们**没有封面兜底**——去掉柔光之后，
     * 只剩 3–5% 的明度差贴在底色上，电视上三米外看就是「按钮没了」。
     * 因此这一类底板必须原样保留。
     *
     * 由 `MainActivity.onCreate` 按 `lowEndTv()` 设定；**必须与 `TvStyle.animate` 同源**，
     * 否则会出现「动画关了、位图还全画」这种半套降级。
     */
    var shadowBitmaps = true

    /**
     * 海报井是否绘制**径向暗角**（`SoftStyle.SHADE`）。
     *
     * 它与柔光位图是同一量级：一屏 18 张 × 267×424 px = 又是 2.0 M 像素/帧，
     * 而且 `RadialGradient` 是逐像素求 sqrt 的着色器——弱 GPU 上单这一项就可能吃掉 16ms 预算。
     * 低配直接不画：暗角是纯装饰，三枚角标各自带压暗层，文字对比度不受影响。
     */
    var vignette = true

    /** 柔光位图相对控件尺寸的缩放。 */
    const val SCALE = .5f

    /** 缓存条数上限（次要约束，真正的上限是下面的字节预算）。 */
    private const val MAX_ENTRIES = 48

    /**
     * 缓存总字节预算。柔光是**可重建的纯缓存**，宁可多重建几次也不能把低端电视的内存吃掉：
     * 一张 4K 画布上的弹窗柔光位图约 2 MiB，8 MiB 够放三四个尺寸同时可见的面板。
     */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** 单张柔光位图的上限：超了就不画投影（宁可少一层，也不要为一个大面板分配巨图）。 */
    private const val MAX_BITMAP_BYTES = 3 * 1024 * 1024

    /** 一张控件的柔光图层：凸起是位图，凹陷 / 暗角是渐变。 */
    class Layers(val shadow: Bitmap?, val dark: Shader?, val light: Shader?) {
        val bytes: Int = (shadow?.memoryBytes ?: 0) + if (dark != null || light != null) 512 else 0
    }

    private val cache = LinkedHashMap<String, Layers>(32, .75f, true)
    private var bytes = 0

    fun layers(key: String, build: () -> Layers): Layers {
        cache[key]?.let { return it }
        val made = build()
        cache[key] = made
        bytes += made.bytes
        // 按访问顺序淘汰最旧的一条，直到回到预算内；至少留一条，免得同一帧里反复重建。
        val oldest = cache.entries.iterator()
        while (bytes > MAX_BYTES && cache.size > 1 && oldest.hasNext()) {
            val entry = oldest.next()
            bytes -= entry.value.bytes
            oldest.remove()
        }
        if (cache.size > MAX_ENTRIES) {
            val extra = cache.entries.iterator()
            while (cache.size > MAX_ENTRIES && extra.hasNext()) {
                val entry = extra.next()
                bytes -= entry.value.bytes
                extra.remove()
            }
        }
        return made
    }

    fun maxBitmapBytes() = MAX_BITMAP_BYTES

    /**
     * 低内存时**按比例**收缩柔光缓存，而不是整份清空。
     *
     * 整份清空的代价不是「少几张位图」，而是**下一帧把屏幕上每一张可见卡重新生成一遍**：
     * 低端盒子上这就是一次几百毫秒的主线程尖峰，正好落在 `nSyncAndDrawFrame` 上（线上那几条
     * 渲染 ANR）。缓存是访问序的，刚画过的条目在尾部，因此只从头部淘汰到预算的一半，
     * 可见的那一屏原样保留，真正省下的是滑出视口的那几屏。
     */
    fun trimMemory(keepRatio: Float = .5f) {
        val target = (MAX_BYTES * keepRatio.coerceIn(0f, 1f)).toInt()
        val oldest = cache.entries.iterator()
        while (bytes > target && cache.size > 1 && oldest.hasNext()) {
            val entry = oldest.next()
            bytes -= entry.value.bytes
            oldest.remove()
        }
    }

    /** 主题切换：整份清空（位图里烤着上一套材质的投影色，留着就是错的）。 */
    fun trim() {
        cache.clear()
        bytes = 0
    }
}

/**
 * 一块新拟态底板。用法：`view.background = TvStyle.soft(context, ...)`。
 *
 * 底板几何：`bounds` 四边各内缩 [bandPx]，剩下的圆角矩形就是「面板」。
 * 因此控件的 padding 必须与 [bandPx] 一致（见 `paintButton` / 各组件），
 * 否则面板会盖住内容或内容压到投影带里。
 *
 * 三种绘制路径：
 * - [SoftStyle.RAISED]：先画预渲染的柔光位图（右下深投影 + 左上高光 + 可选外发光），再画面板；
 * - [SoftStyle.INSET]：先画面板，再裁剪到面板内画两条方向相反的内阴影渐变，得到「压进去」的槽；
 * - [SoftStyle.RING]：只画面板（通常透明）与一圈描边，用作焦点环；
 * - [SoftStyle.SHADE]：面板 + 径向暗角，压在图片上做「嵌进底板的井」。
 */
internal class SoftDrawable(
    private var fill: Int,
    private val radiusPx: Float,
    private val bandPx: Float,
    private val style: SoftStyle,
    private var ringColor: Int = Color.TRANSPARENT,
    private var ringWidthPx: Float = 0f,
    private var glowColor: Int = Color.TRANSPARENT,
    private var depth: Float = 1f,
    /**
     * 「大面积、一屏十几份」的底板（海报卡、剧架底板）。
     *
     * 只有这一类会在低配上关掉预渲染柔光位图（见 [Soft.shadowBitmaps]）：
     * 它们是唯一「面积大 × 数量多」的组合（一屏 18 张 × 整卡面积 = 2.0 M 像素/帧）。
     * 按钮 / 弹窗面板一屏只有三五个，画的代价可以忽略，而它们没有彩色封面兜底，
     * 去掉柔光之后按钮会在深色底上「消失」——因此不受这个开关影响。
     */
    private val heavy: Boolean = false
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plate = RectF()
    private val dst = RectF()
    private var key: String? = null
    private var cached: Soft.Layers? = null

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /**
     * 就地换一套配色（焦点态 / 选中态），**不新建 Drawable**。
     *
     * 只有 [fill] 与描边是每次绘制现画的（纯色圆角矩形 / 描边），改它们只需 `invalidateSelf()`；
     * 只有外发光进了预渲染位图，改它才需要重新查一次缓存（同尺寸的控件共用同一张）。
     * 因此海报卡这类「焦点只换底色 + 描边」的控件，焦点移动不再产生任何位图分配。
     */
    fun restyle(
        fill: Int = this.fill,
        ring: Int = ringColor,
        ringWidth: Float = ringWidthPx,
        glow: Int = glowColor,
        depth: Float = this.depth
    ) {
        if (fill == this.fill && ring == ringColor && ringWidth == ringWidthPx &&
            glow == glowColor && depth == this.depth) return
        // 发光与投影深度都烤进了位图：它们变了才需要让下一次 draw 重新按 key 取一次。
        // 取的是**同尺寸共享**的那一张（见 layers 的 key），不会为每张卡各建一份。
        if (glow != glowColor || depth != this.depth) { key = null; cached = null }
        this.fill = fill
        ringColor = ring
        ringWidthPx = ringWidth
        glowColor = glow
        this.depth = depth
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        // 透明度由 fill / ring 颜色自带 alpha 决定；这里同步 paint 的 alpha 供外部整体淡出用。
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        bitmapPaint.colorFilter = colorFilter
    }

    override fun onBoundsChange(bounds: Rect) {
        plate.set(
            bounds.left + bandPx,
            bounds.top + bandPx,
            bounds.right - bandPx,
            bounds.bottom - bandPx
        )
        dst.set(bounds)
        key = null
        cached = null
    }

    override fun draw(canvas: Canvas) {
        val width = plate.width()
        val height = plate.height()
        if (width <= 0f || height <= 0f) return
        val radius = radiusPx.coerceAtMost(minOf(width, height) * .5f)
        when (style) {
            SoftStyle.RAISED -> {
                layers().shadow?.let { canvas.drawBitmap(it, null, dst, bitmapPaint) }
                fill(canvas, radius)
            }
            SoftStyle.INSET -> {
                fill(canvas, radius)
                inner(canvas, radius)
            }
            SoftStyle.SHADE -> {
                fill(canvas, radius)
                shade(canvas, radius)
            }
            SoftStyle.RING -> fill(canvas, radius)
        }
        if (ringWidthPx > 0f && Color.alpha(ringColor) > 0) {
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ringWidthPx
            paint.color = ringColor
            canvas.drawRoundRect(plate, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun fill(canvas: Canvas, radius: Float) {
        if (Color.alpha(fill) == 0) return
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(plate, radius, radius, paint)
    }

    /** 凹陷：裁剪到面板内，左上一条深色内阴影、右下一条浅色内阴影。 */
    private fun inner(canvas: Canvas, radius: Float) {
        val layer = layers()
        val dark = layer.dark ?: return
        // **不用 `clipPath`**：两份内阴影渐变都是按 `plate` 的绝对坐标构造的，
        // 直接按圆角矩形填充出来的像素与「裁成圆角再填满」完全一致；
        // 而 `clipPath` 在硬件画布上要走 stencil / `saveLayer`，是逐像素成本。
        // 一屏 100 多张卡每帧各来两三次，弱 GPU 的盒子上就是单帧 20ms+。
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = dark
        canvas.drawRoundRect(plate, radius, radius, paint)
        layer.light?.let {
            paint.shader = it
            canvas.drawRoundRect(plate, radius, radius, paint)
        }
        paint.shader = null
    }

    /** 暗角：压在图片上的井，四条边往里暗一圈。 */
    private fun shade(canvas: Canvas, radius: Float) {
        val layer = layers()
        val vignette = layer.light ?: return
        // 同 [inner]：径向渐变按 plate 的绝对坐标构造，`drawRoundRect` 与「裁圆角后填满」等价，
        // 但不需要 clipPath（见 [inner] 的说明）。
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = vignette
        canvas.drawRoundRect(plate, radius, radius, paint)
        paint.shader = null
    }

    private fun layers(): Soft.Layers {
        cached?.let { return it }
        val id = key ?: buildString {
            // **凸起 / 描边两档不带 bounds.left/top**：它们的 Layers 只是一张预渲染位图，
            // 位图只与「尺寸 + 圆角 + 柔光带 + 形态 + 颜色」有关，与控件在屏幕上的位置无关。
            // 带上坐标会让同一行 6 张同尺寸的卡各生成一张内容完全相同的位图
            // （18 张可见卡 = 18 张位图），4K 上直接超过缓存上限、每次焦点移动都重建。
            // 去掉坐标后它们共用同一张，18 张卡只剩 1~2 张。
            //
            // **凹陷 / 暗角两档必须带坐标**：它们的 Layers 里存的是按绝对坐标构造的
            // LinearGradient / RadialGradient（见 innerGradient / vignette），
            // 共用一份会让后画的控件拿到别人位置的渐变。
            if(style==SoftStyle.INSET || style==SoftStyle.SHADE)
                append(bounds.left).append('+').append(bounds.top).append(':')
            append(bounds.width()).append('x').append(bounds.height())
            append("r").append(radiusPx.toInt())
            append("b").append(bandPx.toInt())
            append("s").append(style.ordinal)
            append("g").append(glowColor)
            append("d").append((depth * 10).toInt())
            append("q").append((Soft.quality * 10).toInt())
            // 低配开关也必须进 key：同一个尺寸在「画位图 / 不画位图」两种模式下
            // 生成的 Layers 内容不同，混用会画出一张不该存在的投影。
            append("m").append(if (Soft.shadowBitmaps) 1 else 0)
            append("v").append(if (Soft.vignette) 1 else 0)
            if (heavy) append("h")
            // 主题：柔光位图里烤着当时的投影 / 高光色，换主题必须换 key，
            // 否则会拿浅色材质的位图画在深色底上（TvStyle.applyTheme 还会整体 trim 一次）。
            append("t").append(TvStyle.themeId)
        }.also { key = it }
        val made = Soft.layers(id) { build() }
        cached = made
        return made
    }

    private fun build(): Soft.Layers = when (style) {
        SoftStyle.INSET -> Soft.Layers(null, innerGradient(false), innerGradient(true))
        SoftStyle.SHADE ->
            // 暗角是整卡面积的径向渐变（逐像素 sqrt）：低配直接不画，见 [Soft.vignette]。
            if (Soft.vignette) Soft.Layers(null, null, vignette()) else Soft.Layers(null, null, null)
        else -> when {
            // 完全透明、又没有外发光的底板（文字按钮的常态）不画任何东西：
            // 免得为一块看不见的板子白生成一张柔光位图。
            Color.alpha(fill) == 0 && Color.alpha(glowColor) == 0 -> Soft.Layers(null, null, null)
            // 低配：只有带外发光的那一档（焦点卡）才付这张位图。
            // 非焦点卡一屏十几张，每帧各画一张整卡大小的半分辨率位图并双线性放大，
            // 弱 GPU 上光这一步就超过 16ms；投影本身在深色底上几乎看不出来。
            // **只对 heavy（一屏十几份的卡片）生效**，按钮 / 弹窗面板不受影响（见 [heavy]）。
            !Soft.shadowBitmaps && heavy && Color.alpha(glowColor) == 0 -> Soft.Layers(null, null, null)
            else -> Soft.Layers(shadowBitmap(), null, null)
        }
    }

    /** [light] = true 时返回右下那条浅色内阴影，否则是左上那条深色内阴影。 */
    private fun innerGradient(light: Boolean): LinearGradient {
        val reach = bandPx * 2.6f * depth
        return if (light) LinearGradient(
            plate.right, plate.bottom, plate.right - reach, plate.bottom - reach,
            TvStyle.innerLight, Color.TRANSPARENT, Shader.TileMode.CLAMP
        ) else LinearGradient(
            plate.left, plate.top, plate.left + reach, plate.top + reach,
            TvStyle.innerDark, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
    }

    private fun vignette(): RadialGradient {
        val cx = plate.centerX()
        val cy = plate.centerY()
        val radius = maxOf(plate.width(), plate.height()) * .62f
        return RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.TRANSPARENT, TvStyle.innerDark),
            floatArrayOf(.62f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /** 预渲染的柔光位图：外发光（可选）+ 右下深投影 + 左上浅高光，半分辨率。 */
    private fun shadowBitmap(): Bitmap? {
        val scale = Soft.SCALE
        val width = (bounds.width() * scale).toInt().coerceAtLeast(1)
        val height = (bounds.height() * scale).toInt().coerceAtLeast(1)
        if (width.toLong() * height * 4 > Soft.maxBitmapBytes()) return null
        val quality = Soft.quality
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val brush = Paint(Paint.ANTI_ALIAS_FLAG)
        val band = bandPx * scale
        val rect = RectF(band, band, width - band, height - band)
        val radius = radiusPx * scale
        if (rect.width() <= 0f || rect.height() <= 0f) return null
        if (Color.alpha(glowColor) > 0) {
            brush.color = glowColor
            brush.setShadowLayer(band * 1.35f * quality, 0f, 0f, glowColor)
            canvas.drawRoundRect(rect, radius, radius, brush)
        }
        brush.setShadowLayer(
            band * 1.05f * quality,
            band * .36f * depth * quality,
            band * .46f * depth * quality,
            TvStyle.shadowDark
        )
        brush.color = TvStyle.shadowDark
        canvas.drawRoundRect(rect, radius, radius, brush)
        brush.setShadowLayer(
            band * .95f * quality,
            -band * .32f * depth * quality,
            -band * .40f * depth * quality,
            TvStyle.shadowLight
        )
        brush.color = TvStyle.shadowLight
        canvas.drawRoundRect(rect, radius, radius, brush)
        return out
    }
}
