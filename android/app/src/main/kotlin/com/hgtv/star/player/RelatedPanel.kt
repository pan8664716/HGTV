// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

import com.hgtv.star.touchFocusHost
import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.hgtv.star.tv.TvStyle

/**
 * 播放界面底部的「换一部接着看」浮层。
 *
 * 与选集面板（[EpisodePanel]）同一套做法：贴底、整宽、只放调用方给的内容
 * （一栏相关剧集卡片，或一句说明）。两点与普通模态框不同：
 *
 * * **不压暗画面**：清掉 `FLAG_DIM_BEHIND` 并把 `dimAmount` 归零，上方正在放的那一帧保持原样；
 * * 可读性交给内容自己的**透明阴影背板**（[TvStyle.overlayPanel]：往上渐隐的深色渐变，
 *   底边最深），因此浮层与画面之间是「压了一层阴影」而不是盖了一块板子。
 */
internal class RelatedPanel(
    private val activity: Activity,
    private val content: View,
    private val closed: ()->Unit
) {
    private val dialog=Dialog(activity)

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        // 触摸外壳：同上。
        dialog.setContentView(touchFocusHost(content, fillHeight = true))
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(activity.resources.displayMetrics.widthPixels,ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    fun dismiss() { runCatching { dialog.dismiss() } }
}
