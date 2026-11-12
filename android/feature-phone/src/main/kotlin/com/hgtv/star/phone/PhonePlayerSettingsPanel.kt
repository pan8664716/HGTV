// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize

internal object PhonePlayerSettingsPanel {
    fun showLandscape(
        activity: Activity,
        root: FrameLayout,
        buildRows: (LinearLayout) -> Unit,
        dismiss: () -> Unit,
    ): FrameLayout {
        val container = FrameLayout(activity)
        val screenW = activity.resources.displayMetrics.widthPixels
        val rightW = (screenW * .4f).toInt().coerceAtLeast(activity.dp(220))
        val leftW = screenW - rightW

        val leftDismiss = View(activity).apply {
            setOnClickListener { dismiss() }
        }

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor((PhoneStyle.surface and 0x00FFFFFF) or (0xF2 shl 24))
                cornerRadius = 0f
                setStroke(1, 0x1AFFFFFF)
            }
            // 面板本身吃掉点击：否则点在两行之间会穿到左侧的「收起」上。
            isClickable = true
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(activity).apply {
            text = "播放设置"
            setTextColor(PhoneStyle.text)
            phoneSize(14)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(activity).apply {
            text = "关闭"
            setTextColor(PhoneStyle.textDim)
            phoneSize(12)
            setPadding(activity.dp(6), activity.dp(4), 0, activity.dp(4))
            setOnClickListener { dismiss() }
        })
        panel.addView(head)

        val inner = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        buildRows(inner)
        panel.addView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(inner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        container.addView(leftDismiss, FrameLayout.LayoutParams(
            leftW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START
        ))
        container.addView(panel, FrameLayout.LayoutParams(
            rightW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
        ))
        root.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return container
    }
}
