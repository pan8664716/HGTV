// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context

/**
 * 互斥开关（排行榜页的八个榜单、分类页的大类与小类）：就是统一按钮的选中态——按进去的凹槽。
 *
 * [size] 让同一套控件能表达层级：分类页的**大类**用默认档，**小类**用紧凑档——
 * 二级分类比一级分类整体小一档（高度 48→40dp、字号 16→15sp、左右内边距 28→18dp），
 * 这样一眼能看出「这是大类下面的子类」，而不是两行一样大的按钮。
 */
class ToggleChip(
    context: Context,
    label: String,
    active: Boolean,
    size: ButtonSize = ButtonSize.NORMAL,
    action: () -> Unit
) : TvButton(context, label, size, ButtonTone.SECONDARY, action = action) {
    init { setSelectedState(active) }

    fun setActive(value: Boolean) = setSelectedState(value)
}
