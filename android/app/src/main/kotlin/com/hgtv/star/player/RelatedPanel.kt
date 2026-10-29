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
import com.hgtv.star.tv.usableScreenHeight

/**
 * 播放界面底部的「换一部接着看」浮层。
 *
 * 与选集面板（[EpisodePanel]）同一套做法：贴底、整宽、只放调用方给的内容
 * （一栏相关剧集卡片，或一句说明）。两点与普通模态框不同：
 *
 * * **不压暗画面**：清掉 `FLAG_DIM_BEHIND` 并把 `dimAmount` 归零，上方正在放的那一帧保持原样；
 * * 可读性交给内容自己的**主题浮层背板**（[TvStyle.sheet]：抬升面 + 只圆上缘两角，
 *   留一点点透明），因此浮层与整个应用是同一块材质，按钮的凸 / 凹 / 亮三档在浮层里也成立。
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
            // 宽度吃满窗口（而不是写死整屏像素）：系统按可用区域摆窗口，
            // 侧边有导航栏 / 手势条时右端不会被盖住。
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        // 这一层是贴底浮层：内容比可用高度还高时（小屏横屏、系统栏特别高、系统字号放大），
        // 窗口按 WRAP_CONTENT 会被顶到屏幕之外，上面那一条说明文字整段看不见。
        // 量到真实高度之后把窗口收到可用高度之内——底部贴齐不动，被裁的只是最上面那条说明。
        dialog.window?.decorView?.post {
            val window=dialog.window ?: return@post
            val view=window.decorView
            val natural=view.height
            val usable=activity.usableScreenHeight()
            if(natural>usable && usable>0) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,usable)
            }
        }
    }

    fun dismiss() { runCatching { dialog.dismiss() } }
}
