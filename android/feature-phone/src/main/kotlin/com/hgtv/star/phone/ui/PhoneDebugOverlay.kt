// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 手机端的「调试模式」面板（设置 → 设备与性能 → 调试模式）。
 *
 * 与电视端那块面板同一份用途、同一批数据、同一套字段顺序：**用户报问题时的唯一现场**。
 * 正式包的 `Log.i` 被 proguard 删掉了，而「花屏 / 绿屏 / 有声无画 / 卡住」这类
 * 故障的成因全在设备侧——哪台机器、什么解码档、多少分辨率、缓冲到哪儿、刚才哪一步失败。
 *
 * 两端的差别只在**排版**：电视是 1280×720 的一列固定行，手机是右上角一块半透明小字面板
 * （屏小，压满就看不见画面了）。因此这里只做两件事：
 *
 * * [update]：把宿主算好的几行现场画出来（一行一条，等宽字体、自动换行）；
 * * [attach]：挂到宿主上并**不吃触摸**——面板是只读的，手指照样能点到下面的按钮。
 *
 * 数据从哪来：宿主通过 `PhoneDebugReport` 统一拼装页面、设备、解码、性能、TLS
 * 与播放现场；近况行来自 `:shared` 的 `DebugLog`，两端读的是同一条环形缓冲。
 */
class PhoneDebugOverlay(context: Context) : LinearLayout(context) {

    private val body = TextView(context).apply {
        setTextColor(0xFFB8E986.toInt())
        phoneSize(9.5f)
        typeface = Typeface.MONOSPACE
        setLineSpacing(0f, 1.12f)
        ellipsize = TextUtils.TruncateAt.END
    }

    init {
        orientation = VERTICAL
        setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
        background = roundedBackground(0xD9000000.toInt(), context.dp(6).toFloat())
        // **不吃触摸**：只读面板压在画面上，手指点下去要能穿到下面去。
        isClickable = false
        isFocusable = false
        isEnabled = false
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    /** 画这几行（为空则隐藏，避免留一块空面板）。 */
    fun update(lines: List<String>) {
        val text = lines.filter { it.isNotBlank() }.joinToString("\n")
        if (text.isEmpty()) {
            visibility = GONE
            return
        }
        if (body.text.toString() == text) return
        body.text = text
        visibility = VISIBLE
    }

    /**
     * 调试模式开关生效时挂上 / 摘下这块面板。
     *
     * 每一步都幂等：宿主每次 `onResume`、每秒钟都可以调一遍，不会重复挂。
     * 关掉调试模式时它会被直接摘下来——用户不需要为了一块面板再做一次设置。
     */
    fun sync(hostView: View, enabled: Boolean, lines: () -> List<String>) {
        if (!enabled) {
            if (visibility != GONE) visibility = GONE
            return
        }
        update(lines())
    }

    companion object {
        /** 挂到宿主的右上角（状态栏下方）。字段与 TV 对齐后，窄面板会把每行折成多条。 */
        fun place(overlay: PhoneDebugOverlay, host: android.widget.FrameLayout, topInset: Int) {
            if (overlay.parent === host) return
            val width = (host.resources.displayMetrics.widthPixels * .90f).toInt()
            host.addView(overlay, android.widget.FrameLayout.LayoutParams(
                width, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = topInset
                rightMargin = overlay.context.dp(8)
            })
        }
    }
}
