// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import com.hgtv.star.shared.debug.StartupTrace

internal fun TvMainActivity.initializeMainShell(state: Bundle?) {
    // 启动链路的第二个锚点（第一个是 `LaunchActivity` 的 `route ->`）。
    // 现场「点图标没反应」时要能一眼分开三种情况：路由没跑 / 路由跑了但目标没起来 /
    // 目标起来了但画面没上屏——这一条就是中间那个分界。
    android.util.Log.w("HGTV-BOOT", "tv.TvMainActivity onCreate")
    // 进程死亡后的现场：真正的恢复要等 `library.isLoaded`（onResume 里那次），
    // 这里先存下来。`state` 只在重建时非空，正常冷启动是 null。
    resumeRestoreBundle=state
    window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    // 第一阶段只建一个不依赖书库 / 主题文件的动态加载壳，先让窗口尽快出第一帧。
    // 版本、主题、运行时、网络与书库读取全部延后到下一帧。
    root=FrameLayout(this).apply {
        setBackgroundColor(0xFF1E2228.toInt())
        clipChildren=false
        clipToPadding=false
    }
    setContentView(root)
    showStartupShell()
    StartupTrace.done("main.onCreateShell",startupCreateTraceAt)
    root.postDelayed({
        if(!isDestroyed) initializeMainAfterFirstFrameImpl(state)
    },32L)
}
