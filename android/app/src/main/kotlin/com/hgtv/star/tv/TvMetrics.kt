// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Rect
import android.view.View
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
 * **所有形态共用这一块画布**（电视 / 平板 / 手机都是 1280×720）。
 *
 * 曾经按形态分过档：手机 / 平板换成 960×540，让同一块版面整体放大 1.33 倍，
 * 理由是「手机横屏 2400×1080 上 16 设计 px 只有约 9.6sp，比系统正文小」。
 * 但真机装上去的观感是「整块界面明显变大」——版面上所有间距、圆角、按钮高度
 * 一起放大了，不只是字。用户要的是**原版尺寸**，因此这一档已整体撤销。
 *
 * 若将来确实要调手机上的可读性，正确做法是只放大字号（[TextView.designTextSize]
 * 里加一个形态系数），而不是动画布——动画布等于把整套版式等比放大。
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

/**
 * 系统栏（状态栏 + 导航栏）占掉的高度，像素。
 *
 * **为什么不用 `WindowInsets`**：`getInsets(Type.systemBars())` 要 API 30，
 * 老系统只有已废弃的 `systemWindowInsetTop/Bottom`；而且两条路给的都是
 * 「**当前这个窗口**被系统栏压住了多少」——窗口自己已经避让过（或者本来就是沉浸式）时
 * 返回 0，正是「拿不到真实可用高度」的那种情况。这里改成直接读系统资源里的
 * `status_bar_height` / `navigation_bar_height`：不论窗口怎么摆，这两个数就是系统栏实体尺寸。
 *
 * 电视 / 全屏沉浸式设备上这两个资源通常为 0（有的 ROM 干脆没有），结果就是「不扣」，
 * 与之前的行为一致；手机上会扣掉状态栏与导航栏 / 手势条，弹窗因此不会再被压到屏幕外。
 *
 * 宁可少算一点也不能多算：扣多了只是弹窗小一号，扣少了就是把按钮裁掉。
 */
internal fun Context.systemBarsHeightPx(): Int {
    var total = 0
    for (name in arrayOf("status_bar_height", "navigation_bar_height")) {
        val id = resources.getIdentifier(name, "dimen", "android")
        if (id > 0) total += resources.getDimensionPixelSize(id)
    }
    return total.coerceAtLeast(0)
}

/**
 * 弹窗 / 浮层真正能用的屏幕高度（像素）：整屏高度扣掉系统栏。

 * 全站原来一律拿 `resources.displayMetrics.heightPixels`（**整屏**，含状态栏、导航栏、
 * 刘海与多窗口的空白）当「能用多高」，于是「内容上限 = 整屏 × 50%」，两块内容各占 50% 时
 * 加起来就已经等于整屏，再加上标题、按钮行与内外留白必然溢出窗口——Android 14 上系统栏
 * 更高、越界裁剪更严格，表现出来就是「弹窗下面一截被遮盖」。
 *
 * 下限留 [MIN_SHARE] 是为了兜住算出离谱值的 ROM（例如把导航栏高度写成一整屏）：
 * 那时退回「至少按整屏的六成算」，弹窗照样能摆下标题与按钮。
 */
internal fun Context.usableScreenHeight(): Int {
    val full = resources.displayMetrics.heightPixels.coerceAtLeast(1)
    return (full - systemBarsHeightPx()).coerceAtLeast((full * MIN_SHARE).toInt()).coerceAtMost(full)
}

/** [usableScreenHeight] 的兜底下限：任何情况下都按整屏的这么一份给。 */
private const val MIN_SHARE = .6f

/**
 * 某个已经在屏幕上的窗口**当前**的可用高度（像素）。
 *
 * 与 [usableScreenHeight] 的区别：这条问的是「这一帧真正落在屏幕上的那一段有多高」，
 * 因此能反映分屏、悬浮窗、输入法这类运行期变化。用 `getWindowVisibleDisplayFrame`（API 1 起就有），
 * 拿不到合理值（0、负数、比整屏还大）时退回 [usableScreenHeight]。
 *
 * 只用来**收小**尺寸，绝不放大：调用方一律取两者里更小的那个。
 */
internal fun View.visibleWindowHeight(): Int {
    val usable = context.usableScreenHeight()
    val frame = Rect()
    getWindowVisibleDisplayFrame(frame)
    val visible = frame.height()
    // 太小（键盘把可视区压扁、或还没布局完）时不用它：那不是「窗口真实高度」。
    return if (visible >= usable / 2 && visible <= usable) visible else usable
}
