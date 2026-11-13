// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.util.Locale

object PlaybackSpeed {
    const val MIN=0.5f
    const val MAX=3f
    const val STEP=0.05f

    /**
     * 两端共用的合法档位：0.50× 到 3.00×，每 0.05× 一档。
     */
    val options = List(Math.round((MAX-MIN)/STEP).toInt()+1) { index ->
        round(MIN+index*STEP)
    }

    /** 手机倍速快捷按钮使用的粗档位；完整区间仍由播放设置里的滑杆提供。 */
    val quickOptions = listOf(0.5f,1f,1.5f,2f,2.5f,3f)

    fun normalize(value: Float): Float {
        if(!value.isFinite()) return MIN
        val index=Math.round((value-MIN)/STEP)
        return round(MIN+index*STEP).coerceIn(MIN,MAX)
    }
    fun isValid(value: Float): Boolean =
        value.isFinite() && kotlin.math.abs(value-normalize(value))<0.0001f
    fun label(value: Float): String =
        String.format(Locale.ROOT,"%.2f×",normalize(value))

    private fun round(value: Float): Float = Math.round(value*100f)/100f
}
