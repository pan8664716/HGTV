// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.widget.TextView

/**
 * 设计画布：1280 × 720（像素），也就是「720P 电视上一比一」的那块版面。
 *
 * 整套 UI 的尺寸令牌（边距、按钮高度、海报、圆角、柔光带、字号）都是按这块画布写的。
 * 换到别的分辨率时不再让 dp / sp 各自去乘 density，而是把**整块画布**等比缩放到屏幕上：
 *
 * ```
 * 缩放比例 = min(屏宽 / 1280, 屏高 / 720)
 * ```
 *
 * 这样做的原因：density 是厂商随手填的一个数（同一个 1080P 屏可以是 240 / 320 / 540dpi），
 * 跟着 density 走就会出现「同一台机器改一下 dpi，界面就整体放大两倍、全部溢出」——
 * 这正是 1080×1920 @540dpi 上看到的那一堆问题的根源。
 * 取宽高两个比例的较小值，保证两个方向都装得下，窄窗口（手机横屏、分屏）也不会横向溢出。
 *
 * 结果：任何分辨率 / 任何 dpi 下，界面拿到的都是同一块 1280×720 的版面，
 * 只是每一格被画成了 [designScale] 倍大。比例、留白、字号关系全部保持不变。
 */
internal const val DESIGN_WIDTH_PX = 1280f

/** 设计画布高度（像素），见 [DESIGN_WIDTH_PX]。 */
internal const val DESIGN_HEIGHT_PX = 720f

/** 缩放下限：再小的屏也至少按这个比例画，避免整块版面缩到看不清。 */
internal const val DESIGN_SCALE_MIN = .45f

/** 缩放上限：超大画布（4K / 8K）上不再无限放大，一屏仍能放下足够多的内容。 */
internal const val DESIGN_SCALE_MAX = 3.2f

/**
 * 当前屏幕的缩放比例：取「宽 / 1280」与「高 / 720」里**较小的那个**，
 * 夹在 [DESIGN_SCALE_MIN]–[DESIGN_SCALE_MAX]。
 *
 * 取较小值而不是只按高度算：设计画布是 1280×720，必须两个方向都装得下，
 * 否则窄窗口（手机横屏、分屏）上横向还是会溢出。
 *
 * 只读像素尺寸，**不读 density**，也不做缓存：模拟器上 `wm size` / `wm density`
 * 改完立刻生效，不需要重启应用。
 */
internal fun Context.designScale(): Float {
    val metrics = resources.displayMetrics
    val width = metrics.widthPixels
    val height = metrics.heightPixels
    if (width <= 0 || height <= 0) return 1f
    val byWidth = width / DESIGN_WIDTH_PX
    val byHeight = height / DESIGN_HEIGHT_PX
    return minOf(byWidth, byHeight).coerceIn(DESIGN_SCALE_MIN, DESIGN_SCALE_MAX)
}

/** 设计尺寸（dp / sp 数值）→ 像素（浮点，供 Paint 用）。 */
internal fun Context.designSize(value: Number): Float = value.toFloat() * designScale()

/** 设计 dp → 像素。全站所有 dp 换算的唯一出口。 */
internal fun Context.designPx(value: Number): Int = designSize(value).toInt()

/**
 * 设计字号 → 实际字号。
 *
 * 直接按像素设，不再走 sp：sp 会再乘一次 density，等于把「dpi 变了」这件事又带回来一遍。
 * 系统无障碍字号（`fontScale`）仍然尊重，但夹在 1.0–1.3 之间——
 * 再大就会把按钮里的单行文案挤成省略号，那不是用户想要的「看得清」。
 */
internal fun TextView.designTextSize(value: Float): TextView = apply {
    val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.3f)
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, value * context.designScale() * fontScale)
}
