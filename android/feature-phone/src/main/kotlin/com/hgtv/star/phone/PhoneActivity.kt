// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import com.hgtv.star.phone.ui.PhoneUiScale

/**
 * 手机端 Activity 基类：**屏幕像素尺寸变了就重建整页**。
 *
 * 手机端的尺寸令牌（边距 / 卡片 / 字号）都在建视图那一刻由 `phoneScale()` 算成像素
 * （见 `PhoneTheme`）。各 Activity 在 manifest 里声明了 `configChanges=screenSize`，
 * 系统不会替我们重建；如果不自己重建，`wm size` 改完只有容器变大，里面的字号与卡片
 * 仍是旧像素，表现就是「一半大一半小」。
 *
 * dpi 变化不在 `configChanges` 里，系统本来就会重建，这里只兜像素尺寸。
 * 方向变化默认也重建（竖屏页不会发生）；播放页自己拒绝，交给它原地切形态。
 */
open class PhoneActivity : Activity() {

    private var builtWidth = 0
    private var builtHeight = 0

    /** 建这一页时的方向。播放页靠它区分「转屏」与「改分辨率」。 */
    protected var builtOrientation = Configuration.ORIENTATION_UNDEFINED
        private set

    /** 已经排了一次重建：重建完成前不再重复排。 */
    protected var screenRebuildPending = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneUiScale.ensure(this)
        PhoneDanmakuSize.ensure(this)
        captureScreen()
    }

    private fun captureScreen() {
        val metrics = resources.displayMetrics
        builtWidth = metrics.widthPixels
        builtHeight = metrics.heightPixels
        builtOrientation = resources.configuration.orientation
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (screenRebuildPending) return
        val metrics = resources.displayMetrics
        val sizeChanged = metrics.widthPixels != builtWidth || metrics.heightPixels != builtHeight
        if (sizeChanged && rebuildOnSizeChange(newConfig)) {
            screenRebuildPending = true
            recreate()
            return
        }
        captureScreen()
    }

    /**
     * 只有**方向没变**、单纯像素尺寸变了才重建。
     *
     * 转屏时宽高必然对调：那不是「换了分辨率」，而是同一块屏转了个方向。
     * 播放页自己原地切形态；它下面的推荐页更不能重建——那一页的 Surface 正借给
     * 播放页出画面，重建等于把正在播的这一路连根拔掉（用户会看到黑一下再重来）。
     */
    protected open fun rebuildOnSizeChange(newConfig: Configuration): Boolean =
        newConfig.orientation == builtOrientation
}
