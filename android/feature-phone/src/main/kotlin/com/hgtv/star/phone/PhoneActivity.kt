// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import com.hgtv.star.phone.ui.PhoneUiScale

/**
 * 手机端 Activity 基类：普通页面由系统在屏幕尺寸或方向变化时重建整页。
 *
 * 手机端的尺寸令牌（边距 / 卡片 / 字号）都在建视图那一刻由 `phoneScale()` 算成像素
 * （见 `PhoneTheme`）。普通页面不在 manifest 里声明方向 / 屏幕尺寸的 `configChanges`，
 * 交给系统走标准重建，避免 HyperOS 在本地 `recreate()` 时留下未挂载的 DecorView。
 *
 * 主界面是例外：它接管方向变化，先把分类 / 榜单等页面快照存到进程内，再延后
 * `recreate()`，这样转屏只重建视图，不重新请求列表，也不丢滚动位置。
 *
 * 播放页单独声明方向变化并覆盖 [rebuildOnSizeChange]，由它原地切换横竖屏。
 * 这里的重建兜底只处理个别 ROM 仍直接回调尺寸变化、但系统没有重建的情况。
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

    private val rebuildAfterConfig = Runnable {
        if (!screenRebuildPending) return@Runnable
        if (isFinishing || isDestroyed) {
            screenRebuildPending = false
            return@Runnable
        }
        recreate()
    }

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
            // 不要在 onConfigurationChanged 的事务内直接 recreate：MIUI/HyperOS 会把
            // 本地重建误当成配置事务，随后留下未挂载的 DecorView。等当前主线程消息
            // 处理完再重建，连续的方向回调也会被 screenRebuildPending 合并。
            window.decorView.post(rebuildAfterConfig)
            return
        }
        captureScreen()
    }

    /** 普通手机页面跟随方向重建；播放页覆盖此方法，原地切换横竖屏。 */
    protected open fun rebuildOnSizeChange(newConfig: Configuration): Boolean = true
}
