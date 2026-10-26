// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

import com.hgtv.star.touchFocusHost
import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.Window
import android.widget.FrameLayout
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
    /**
     * 面板要跟着画面转多少度（`0f` = 不转，电视上恒为 0）。
     *
     * 「短剧自动全屏」把画面转 90° 时，播放页上的标题条 / 控制条 / 弹幕都跟着转了
     *（见 `MainActivity.applyOverlayGeometry`）。这个面板是**独立窗口**，转不了父级，
     * 因此自己按同一个角度摆一次——不然它就是这张转了 90° 的界面里唯一还横着的一块。
     */
    private val spin: Float = 0f,
    private val closed: () -> Unit
) {
    private val dialog = Dialog(activity)
    private val dp = { value: Number -> activity.tvDp(value) }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        // 触摸外壳：弹窗是独立窗口，Activity 的触摸预处理管不到它（见 MainActivityTouch）。
        val content=touchFocusHost(grid, fillHeight = true)
        // 300dp 是「标题 + 集数 + 两行单元格」的高度；多页时网格顶上还有一行
        // 「1-20 / 21-40 …」分页芯片，高度跟着它一起长，网格才不会被挤扁。
        val thickness=dp(300 + grid.pagerExtra)
        if(spin==0f) {
            dialog.setContentView(content)
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setGravity(Gravity.BOTTOM)
                setLayout(activity.resources.displayMetrics.widthPixels,thickness)
                undim()
            }
        } else {
            showRotated(content,thickness)
        }
        // 弹窗布局完成后再落焦点，保证落在当前集而不是第一格。
        grid.post { grid.focusCurrent() }
    }

    /**
     * 跟着画面转 90° 的那一版：**窗口贴着屏幕的一条竖边**，内容在窗口里转过来。
     *
     * 为什么窗口不铺满全屏：铺满之后「点面板外面收起」就失效了（窗口内没有「外面」）。
     * 这里把窗口做成转过来之后那条竖带的实际矩形——宽 = 面板厚度、高 = 屏幕高，
     * 贴左或贴右——点画面仍然落在窗口之外，系统照样按「点外面」收起，行为与原来一致。
     *
     * 贴哪一边由角度决定（顺时针 90° 时，界面底边转到屏幕左边；逆时针则转到右边）。
     */
    private fun showRotated(content: android.view.View,thickness: Int) {
        val screenH=activity.resources.displayMetrics.heightPixels
        // 与播放页那一层同一套算法：转 90° 之后「宽 = 屏高、高 = 屏宽」。
        val frameW=screenH
        val strip=FrameLayout(activity).apply {
            addView(content,FrameLayout.LayoutParams(-1,-1))
        }
        // 转之前的那一整条：宽 = 屏高（就是屏幕上那条竖带的高），高 = 面板厚度。
        // 它居中放进窗口之后旋转，视觉矩形正好等于窗口，一个像素都不差。
        val band=FrameLayout(activity).apply {
            addView(strip,FrameLayout.LayoutParams(-1,thickness,Gravity.BOTTOM))
            rotation=spin
        }
        val host=FrameLayout(activity).apply {
            addView(band,FrameLayout.LayoutParams(frameW,thickness,Gravity.CENTER))
        }
        dialog.setContentView(host)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(if(spin>0f) Gravity.LEFT or Gravity.CENTER_VERTICAL else Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            setLayout(thickness,screenH)
            undim()
        }
    }

    /**
     * 选集是「挑一集」的浮层，不是模态框：**上方的画面不压暗**。
     *
     * 普通 Dialog 默认带 FLAG_DIM_BEHIND（系统对话框主题的 60% 黑），
     * 那会在面板之上盖一层灰，把正在播的画面整体压暗；这里清掉这个标志并把
     * dimAmount 归零，画面保持原样，可读性交给面板自己的渐变背板。
     */
    private fun Window.undim() {
        setDimAmount(0f)
        clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    fun dismiss() = dialog.dismiss()
}
