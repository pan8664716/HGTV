// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.compat

import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider

/**
 * 圆角 / 圆形裁剪。
 *
 * `clipToOutline`、`ViewOutlineProvider`、`Outline` 都是 API 21 才有的类型。它们被放进
 * 单独的 `RoundCornersApi21`，只在 21+ 被加载：Android 4.4 上如果把这些类型直接写进
 * 常驻方法的签名或字段，Dalvik 在类校验阶段就会因为找不到类而抛 `NoClassDefFoundError`。
 */
internal fun roundCornersCompat(view: View, radiusPx: Float) {
    if (Build.VERSION.SDK_INT >= 21) RoundCornersApi21.apply(view, radiusPx)
}

internal fun clipToCircleCompat(view: View) {
    if (Build.VERSION.SDK_INT >= 21) RoundCornersApi21.circle(view)
}

@android.annotation.TargetApi(21)
private object RoundCornersApi21 {
    fun apply(view: View, radiusPx: Float) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setRoundRect(0, 0, target.width, target.height, radiusPx)
            }
        }
        view.addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ -> target.invalidateOutline() }
    }

    fun circle(view: View) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setOval(0, 0, target.width, target.height)
            }
        }
    }
}
