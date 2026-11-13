// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import com.hgtv.star.core.DanmakuStyle

/**
 * 手机端弹幕字号。
 *
 * 电视端的 60% 是手机能看清的最小一档，因此在手机上把它当作 100%，
 * 允许用户在当前字号的基础上继续左右各调 50%。显示值单独落盘，避免把电视端
 * 的弹幕字号一起改小。
 */
object PhoneDanmakuSize {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 150
    const val STEP_PERCENT = 5
    const val DEFAULT_PERCENT = 100

    private const val PREFS = "phone-danmaku-v1"
    private const val KEY = "sizePercent"

    @Volatile private var current = DEFAULT_PERCENT
    @Volatile private var loaded = false

    fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            current = snap(
                context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY, DEFAULT_PERCENT),
            )
            loaded = true
        }
    }

    /** 当前界面读数：50–150，100 = 手机原先能看清的最小一档。 */
    fun percent(context: Context): Int {
        ensure(context)
        return current
    }

    fun set(context: Context, value: Number) {
        ensure(context)
        val next = snap(value.toInt())
        if (next == current) return
        synchronized(this) {
            current = next
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY, next)
                .apply()
        }
    }

    /** 映射到弹幕层：100% 就是原先的 60% 字号。 */
    fun renderScale(context: Context): Float =
        DanmakuStyle.SCALE_MIN * (percent(context) / 100f)

    fun label(value: Number): String = "${Math.round(value.toFloat())}%"

    private fun snap(value: Int): Int {
        val step = ((value - MIN_PERCENT).toFloat() / STEP_PERCENT)
            .let { Math.round(it) } * STEP_PERCENT + MIN_PERCENT
        return step.coerceIn(MIN_PERCENT, MAX_PERCENT)
    }
}
