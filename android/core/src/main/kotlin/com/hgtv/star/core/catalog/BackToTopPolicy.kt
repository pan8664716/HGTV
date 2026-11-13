// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 「回到顶部」浮层的显隐规则（`Library.backToTop` 四档）。
 *
 * 电视与手机都有一枚回到顶部的浮层，但两端的控件完全不同：电视是遥控器焦点按钮，
 * 手机是右下角的一枚圆形按钮。**什么时候出现**却是同一件事——用户说的是
 * 「往下翻了几行」，不是「滚了多少像素」，更不是「一屏放几张卡」。
 *
 * 因此判据收在这里：阈值 = 行数 × 一行的高度（含行间距），与分辨率、屏宽、
 * 每行几张卡都无关。两端只管把「一行的像素高度」算出来传进来。
 *
 * 这是一个纯函数，`./gradlew test` 覆盖得住；两端不必各写一份再担心分叉。
 */
object BackToTopPolicy {

    /** 「始终显示」：[rows] 的返回值。 */
    const val ALWAYS = 0

    /** 「始终不显示」：[rows] 的返回值。 */
    const val NEVER = -1

    /**
     * 这一档要求先滚过几行。
     *
     * @return [ALWAYS] = 一进列表就显示；[NEVER] = 整个列表页都不挂浮层；
     *   其余是行数（5 / 8）。认不出的值按出厂档「始终显示」处理——
     *   与 `Library.backToTop` 的兜底一致，不让界面卡在「什么都不显示」。
     */
    fun rows(mode: String): Int = when (mode) {
        BACK_TO_TOP_NEVER -> NEVER
        BACK_TO_TOP_ROWS5 -> 5
        BACK_TO_TOP_ROWS8 -> 8
        else -> ALWAYS
    }

    /**
     * 当前滚动位置要不要显示浮层。
     *
     * @param scrollY 列表已经滚过的像素
     * @param rowStride 一行的像素高度（卡片高 + 行间距）；`<= 0` 表示还没量到，
     *   此时「下拉 N 行」档按**不显示**处理——否则第一帧会用 0 当阈值，
     *   浮层在列表顶部闪一下再消失。
     */
    fun visible(mode: String, scrollY: Int, rowStride: Int): Boolean {
        val rows = rows(mode)
        if (rows == NEVER) return false
        if (rows == ALWAYS) return true
        if (rowStride <= 0) return false
        return scrollY >= rows * rowStride
    }

    /**
     * 设置页那一格的**完整**说法（选择弹窗的选项文案）。
     *
     * 两端共用这一份字面量：用户在电视上记住的是「下拉 5 行列表后显示」，
     * 换到手机上看到「下拉 5 行后显示」会以为不是同一档。
     */
    fun label(mode: String): String = when (mode) {
        BACK_TO_TOP_ROWS5 -> "下拉 5 行列表后显示"
        BACK_TO_TOP_ROWS8 -> "下拉 8 行列表后显示"
        BACK_TO_TOP_NEVER -> "始终不显示"
        else -> "始终显示"
    }

    /**
     * 设置格上显示的**短**读数：一格只有半屏宽，完整文案会被省略号吃掉尾巴
     *（读出来就成了「下拉 8 行列表…」）。弹窗里的选项仍用 [label]。
     */
    fun shortLabel(mode: String): String = when (mode) {
        BACK_TO_TOP_ROWS5 -> "下拉 5 行"
        BACK_TO_TOP_ROWS8 -> "下拉 8 行"
        BACK_TO_TOP_NEVER -> "始终不显示"
        else -> "始终显示"
    }
}
