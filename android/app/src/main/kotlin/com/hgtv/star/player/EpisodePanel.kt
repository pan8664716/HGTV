// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

import com.hgtv.star.touchFocusHost
import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.Window
import android.widget.FrameLayout
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.usableScreenHeight
import com.hgtv.star.tv.tvDp

/**
 * 播放器底部的选集面板：贴底、整宽、约 300dp 高，只负责把 [EpisodeGrid]
 * 放进一个透明弹窗里。
 *
 * 内容与简介页的选集共用同一份实现，因此两处的标题、集数行、分页行、单元格尺寸、
 * 翻页与跳转行为完全一致；这里不再有第二套网格代码。
 *
 * 高度固定取 [TvStyle.PLAYER_PANEL_HEIGHT]，与播放设置面板**同高**（两块浮层贴在同一条底边上）。
 */
internal class EpisodePanel(
    private val activity: Activity,
    private val grid: EpisodeGrid,
    /**
     * 面板要跟着画面转多少度（`0f` = 不转，电视上恒为 0）。
     *
     * 「竖屏短剧全屏」把画面转 90° 时，播放页上的标题条 / 控制条 / 弹幕都跟着转了
     *（见 `MainActivity.applyOverlayGeometry`）。这个面板是**独立窗口**，转不了父级，
     * 因此自己按同一个角度摆一次——不然它就是这张转了 90° 的界面里唯一还横着的一块。
     */
    private val spin: Float = 0f,
    private val closed: () -> Unit
) {
    private val dialog = Dialog(activity)
    private val dp = { value: Number -> activity.tvDp(value) }
    /**
     * 面板上发生了用户操作（按键 / 触摸）。
     *
     * 面板是**独立窗口**，按键与触摸都不经过 Activity，因此「无操作自动消失」的计时
     * 只能由这里通知宿主重新开始数（见 `MainActivity.scheduleOverlayAutoHide`）。
     */
    var onActivity: () -> Unit = {}

    init {
        // 窗口级的按键监听：返回 false 表示「照常处理」，这里只借这一下把计时器重新排一遍。
        dialog.setOnKeyListener { _, _, _ -> onActivity(); false }
    }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        // 触摸外壳：弹窗是独立窗口，Activity 的触摸预处理管不到它（见 MainActivityTouch）。
        val content=touchFocusHost(grid, fillHeight = true) { onActivity() }
        // 高度：**最小**与播放设置面板一致（[TvStyle.PLAYER_PANEL_HEIGHT]），一页集数多时**加高**。
        //
        // 早先是固定 300dp、放不下就让网格内部滚动；用户要的是「不压缩选集按钮，改加高面板」：
        // 格子尺寸与简介页完全一致（同一套宽度/行数算法，见 `EpisodeGrid.fitCells`），
        // 五行放不下就把这一块顶上去。两块浮层仍然贴在同一条底边上，只是选集面板更高。
        // 面板厚度本身也要收进可用高度之内（一页集数少时用不到，但小屏 / 高系统栏的设备上
        // 300 设计单位的固定厚度可能已经接近整屏，加高之前先把起点压住）。
        val thickness=dp(TvStyle.PLAYER_PANEL_HEIGHT).coerceAtMost(activity.usableScreenHeight())
        if(spin==0f) {
            dialog.setContentView(content)
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setGravity(Gravity.BOTTOM)
                // 宽度交给窗口自己吃满（MATCH_PARENT）：系统按**可用区域**摆窗口，
                // 安卓 14 上侧边导航栏 / 手势条占着的那一条不会被面板盖住，右端也不会被裁。
                setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,thickness)
                undim()
            }
        } else {
            showRotated(content,thickness)
        }
        // 弹窗布局完成后再落焦点，保证落在当前集而不是第一格。
        grid.post {
            growToFit()
            grid.focusCurrent()
        }
        // 格子尺寸是布局完成之后才量准的（见 `EpisodeGrid.fitCells`）：量完由网格回调一次，
        // 面板这时再决定自己该多高——分页集数多时加高，不压缩按钮。
        grid.onCellSizeChanged={ growToFit() }
    }

    /**
     * 一页集数多时把面板加高到网格的自然高度（不小于默认厚度 [TvStyle.PLAYER_PANEL_HEIGHT]）。
     *
     * 上限取屏高的 [MAX_SCREEN_SHARE]：再加高就会盖住画面大半，
     * 真到那一步交回网格内部滚动（格子已经在下限之上，滚动只发生在极端窄屏）。
     */
    private fun growToFit() {
        val base=dp(TvStyle.PLAYER_PANEL_HEIGHT)
        val need=grid.naturalHeight()
        if(need<=0) return
        // 用**可用**高度（扣掉系统栏）当分母：整屏高度算出来的上限在 Android 14 上
        // 会把面板顶到系统栏底下，最后那几行集数被遮住。
        val screenH=activity.usableScreenHeight()
        val target=minOf(need,(screenH*MAX_SCREEN_SHARE).toInt())
            .coerceAtLeast(minOf(base,screenH))
        val window=dialog.window ?: return
        // 转 90° 那一版：厚度是「竖带」的宽度，窗口是 (厚度 × 屏高)，跟着换一个维度。
        if(spin==0f) {
            if(window.attributes.height==target) return
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,target)
        } else {
            if(window.attributes.width==target) return
            window.setLayout(target,screenH)
        }
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

/**
 * 选集面板加高的上限：屏高的 78%。
 *
 * 一页 50 集在 1280×720 画布上约需 430dp（屏高的 60%），留一档余量到 78%：
 * 再高就盖住画面大半，不如让网格内部滚动。
 */
private const val MAX_SCREEN_SHARE = .78f
