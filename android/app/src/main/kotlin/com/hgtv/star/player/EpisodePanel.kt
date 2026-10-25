// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

import com.hgtv.star.touchFocusHost
import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.Window
import com.hgtv.star.tv.tvDp

/**
 * 播放器底部的选集面板：贴底、整宽、约 300dp 高，只负责把 [EpisodeGrid]
 * 放进一个透明弹窗里。
 *
 * 内容与简介页的选集共用同一份实现，因此两处的标题、集数行、分页行、单元格尺寸、
 * 翻页与跳转行为完全一致；这里不再有第二套网格代码。
 *
 * 高度按「两行单元格」的 300dp 起步，多页时再加上分页行的高度（见 `EpisodeGrid.pagerExtra`）。
 */
internal class EpisodePanel(
    private val activity: Activity,
    private val grid: EpisodeGrid,
    private val closed: () -> Unit
) {
    private val dialog = Dialog(activity)
    private val dp = { value: Number -> activity.tvDp(value) }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        // 触摸外壳：弹窗是独立窗口，Activity 的触摸预处理管不到它（见 MainActivityTouch）。
        dialog.setContentView(touchFocusHost(grid, fillHeight = true))
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(
                activity.resources.displayMetrics.widthPixels,
                // 300dp 是「标题 + 集数 + 两行单元格」的高度；多页时网格顶上还有一行
                // 「1-20 / 21-40 …」分页芯片，高度跟着它一起长，网格才不会被挤扁。
                dp(300 + grid.pagerExtra)
            )
            // 选集是「挑一集」的浮层，不是模态框：**上方的画面不压暗**。
            // 普通 Dialog 默认带 FLAG_DIM_BEHIND（系统对话框主题的 60% 黑），
            // 那会在面板之上盖一层灰，把正在播的画面整体压暗；这里清掉这个标志并把
            // dimAmount 归零，画面保持原样，可读性交给面板自己的渐变背板。
            setDimAmount(0f)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        // 弹窗布局完成后再落焦点，保证落在当前集而不是第一格。
        grid.post { grid.focusCurrent() }
    }

    fun dismiss() = dialog.dismiss()
}
