// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.security.MessageDigest
import java.util.UUID

/**
 * 由**系统标识符**派生的设备标识：`openudid` / `cdid` / `clientudid`。
 *
 * ## 为什么要派生，而不是随机
 *
 * 早先这三项是 `UUID.randomUUID()` / `Random.nextLong()`，而且只存在 App 私有目录里——
 * **卸载重装就是一台全新设备**。平台认设备靠这几项去重（注册请求就带 `cdid` / `openudid`），
 * 于是一个老账号出现在一台「新设备」上，风控就会再要一次上行短信确认本人。
 * 真机上的红果不这样：它的 `openudid` 由 `Settings.Secure.ANDROID_ID` 派生，
 * 卸载重装、升级、重启都不变，所以平台一直认这台设备。
 *
 * ## 种子由调用方采集
 *
 * `ANDROID_ID` → `Build.SERIAL`（8.0 起读不到）→ `Build.FINGERPRINT`，都拿不到才回落到随机
 * （见 `Library.deviceProfile`）。`ANDROID_ID` 在 8.0 起是「应用签名 + 用户」作用域：
 * 同签名同用户的**重装**不变，恢复出厂 / 换用户才变。
 *
 * ## 边界
 *
 * * 少数山寨 ROM 给出那个著名的假 `ANDROID_ID`（[PLACEHOLDER_ANDROID_ID]）或每次开机都变的
 *   假值：前者这里当「没有」处理，后者由调用方「档案已经确定就不再改」兜住（见 `Library`）。
 * * debug 与 release 签名不同，同一台机器上两种包派生的值不同——这是 8.0 起平台的规则。
 * * 派生用的摘要**不用于任何安全用途**（它只是把标识符摊成一个固定形状的串）。
 */
object StableDeviceIds {
    /** 早期 Android 上大量设备共用的假 `ANDROID_ID`：等于没有。 */
    const val PLACEHOLDER_ANDROID_ID="9774d56d682e549c"
    /** 系统读不到时给的字符串。 */
    private const val UNKNOWN="unknown"

    /**
     * 采集到的标识符 → 派生种子。
     *
     * 顺序就是优先级：`ANDROID_ID` 最稳（8.0 起重装不变），`SERIAL` 次之（只在 8.0 之前可读），
     * `FINGERPRINT` 最弱（同型号设备完全相同，只能保证「至少不是随机」）。
     * 三个都没有时才回落到随机——那时行为与旧版本一致（每次安装都是新设备）。
     */
    fun seed(androidId: String?,serial: String?,fingerprint: String?): String {
        for(candidate in listOf(androidId,serial,fingerprint)) {
            val value=candidate.orEmpty().trim()
            if(value.isEmpty()) continue
            if(value.equals(UNKNOWN,ignoreCase=true)) continue
            if(value.equals(PLACEHOLDER_ANDROID_ID,ignoreCase=true)) continue
            return value
        }
        return "random:"+UUID.randomUUID()
    }

    /** `openudid`：16 位 hex，与平台 query 里的形态一致（参考实现也带这一项）。 */
    fun openudid(seed: String): String = digest("openudid",seed).take(16)

    /**
     * `cdid`：UUID 形态（带横杠），与原先 `randomUUID()` 的形状一致。
     *
     * 用「按名字取 UUID」的算法从种子算出来：同一个种子永远同一个值，且不依赖 JVM / 语言环境。
     */
    fun cdid(seed: String): String = UUID.nameUUIDFromBytes(material("cdid",seed)).toString()

    /** `clientudid`：同样由种子派生，换一类名字，免得与 `cdid` 派生成同一个值。 */
    fun clientudid(seed: String): String = UUID.nameUUIDFromBytes(material("clientudid",seed)).toString()

    /**
     * 种子的指纹：**只**用来判断「这台设备的标识符有没有变过」（见 `Library.deviceProfile`），
     * 不参与任何请求，也不发给平台。
     */
    fun stamp(seed: String): String = digest("stamp",seed).take(16)

    private fun material(salt: String,seed: String): ByteArray = "hgtv/$salt/$seed".toByteArray(Charsets.UTF_8)
    private fun digest(salt: String,seed: String): String =
        MessageDigest.getInstance("SHA-256").digest(material(salt,seed))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
