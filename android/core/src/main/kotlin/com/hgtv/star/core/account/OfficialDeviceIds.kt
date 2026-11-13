// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.math.BigInteger
import java.security.SecureRandom
import java.util.UUID

/**
 * 官方 App 默认设备标识生成规则。
 *
 * 与 [StableDeviceIds] 的旧版「种子派生」方案不同，这里按官方链路分别生成：
 *
 * * cdid: UUID.randomUUID()
 * * clientudid: UUID.randomUUID()
 * * openudid: 优先使用有效 Android ID；否则使用 80-bit SecureRandom 十六进制值，
 *   长度不足 13 位时左侧补 `F`
 *
 * 生成结果必须由调用方持久化，不能每次请求重新生成。
 */
object OfficialDeviceIds {
    const val ANDROID_ID_PLACEHOLDER = "9774d56d682e549c"
    private val random = SecureRandom()

    fun validAndroidId(value: String?): Boolean {
        val id = value?.trim().orEmpty()
        return id.length >= 13 &&
            !id.equals(ANDROID_ID_PLACEHOLDER, ignoreCase = true) &&
            id.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    fun openudid(androidId: String?): String {
        val id = androidId?.trim().orEmpty()
        if (validAndroidId(id)) return id

        var value = BigInteger(80, random).toString(16)
        if (value.startsWith("-")) value = value.substring(1)
        if (value.length < 13) value = "F".repeat(13 - value.length) + value
        return value
    }

    fun cdid(): String = UUID.randomUUID().toString()

    fun clientudid(): String = UUID.randomUUID().toString()
}
