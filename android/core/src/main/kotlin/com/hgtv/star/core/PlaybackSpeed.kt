// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

object PlaybackSpeed {
    /**
     * 电视端与设置页共用的档位。
     *
     * 电视上停在 2×：再快就没法看了，遥控器也不方便来回调。
     */
    val options = listOf(0.75f,1f,1.25f,1.5f,1.75f,2f)

    /**
     * 手机 / 平板多一档 3×。
     *
     * 短剧节奏慢，手机上有不少人用 2× 追完再往上加；这一档**只进选择列表**。
     * 校验（[normalize] / [isValid]）认的是全部合法值，否则同一份设置在
     * 「手机设成 3×、换到电视打开一次」之后会被悄悄改回 1×。
     */
    val phoneOptions = options + 3f

    /** 全部合法值（校验用）：两端可能出现过的档位并集。 */
    private val valid = phoneOptions

    fun normalize(value: Float) = value.takeIf { it in valid } ?: 1f
    fun isValid(value: Float) = value in valid
    fun label(value: Float) = value.toString().removeSuffix(".0")+"×"
}
