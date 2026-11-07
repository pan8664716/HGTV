// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.account

import com.hgtv.star.core.AccountCredentials
import com.hgtv.star.core.AccountCredentialsParser
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.shared.data.Library

/**
 * 账号凭据的落地校验（电视端与手机端共用一份）。
 *
 * ## 为什么必须校验
 *
 * 平台对无效 / 过期的 `sessionid` **不报错**，而是把请求当成匿名设备，返回一个按
 * `device_id` 现造的账号（昵称形如「用户19058925」）。不挡住的话，一份坏凭据会「导入成功」，
 * 而界面上只是一条记录都没有——这正是「导入后没有任何记录」最常见的原因。
 *
 * 因此所有入口（导入 `cookieStore.xml`、导入 `sessionid`、扫码登录、手机号登录）
 * 都必须先走这里：解析 → 拉一次账号资料 → 挡住匿名 → 才写进本机。
 *
 * ## 支持哪些输入
 *
 * * `cookieStore.xml` 全文（官方 App 的 SharedPreferences 导出）；
 * * `device_profile.json`（`dump_device.py` 的产物，只有设备档案、没有 Cookie）；
 * * 纯 `sessionid`、`sessionid=…`、一整段 Cookie 头，或 `cookieStore.xml` 里那一行。
 */
object AccountLogin {

    /**
     * 解析并校验一段导入文本。
     *
     * 成功时：设备档案已落盘（如果这份文本带）、[ContentRepository.credentials] 已换成本次凭据，
     * 返回可直接交给 `Library.saveAccount` 的凭据。
     *
     * 失败时抛 [IllegalArgumentException]，`message` 就是可以直接展示给用户的那句话。
     * 注意：失败时**可能**已经把设备档案写进去了（那份档案本身是有效的），
     * 但绝不会把凭据写进书库——写库由调用方在成功后做。
     */
    fun importText(
        text: String,
        repository: ContentRepository,
        library: Library,
    ): AccountCredentials {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "没有内容可导入" }

        // `device_profile.json` 单独走一条路：它没有 Cookie，只把设备档案存下来，签名要用。
        if (trimmed.startsWith("{") && trimmed.contains("device_id")) {
            val device = AccountCredentialsParser.parseDeviceProfile(trimmed)
            require(device["device_id"].orEmpty().isNotBlank()) {
                "这份档案里没有 device_id，确认传的是 device_profile.json"
            }
            library.saveAccountDevice(device)
            val current = library.account()
            require(current.configured) { "设备档案已保存。但还没有账号参数，请再传一份 cookieStore.xml。" }
            return com.hgtv.star.shared.data.DeviceRegistration.prepareLogin(
                library,repository,current,
            )
        }

        val xml = trimmed.contains("<map")
        val credentials = if (xml) {
            AccountCredentialsParser.parseCredentials(trimmed, library.accountDevice())
        } else {
            // 「输入 sessionid」那一条路送来的可能是纯值、`sessionid=…`、整段 Cookie，
            // 也可能是 `cookieStore.xml` 里那一整行（或它的十六进制值本身）——统一归一。
            val id = AccountCredentialsParser.sessionIdOf(trimmed)
            require(AccountCredentialsParser.looksLikeSessionId(id)) {
                if (id.isEmpty()) "没有解析到 sessionid" else "这不是一个 sessionid（要 32 位十六进制）"
            }
            AccountCredentialsParser.parseHeader("sessionid=$id")
        }
        require(credentials.configured) {
            "没有解析到 sessionid，请确认上传的是官方 App 的 cookieStore.xml"
        }
        val prepared = com.hgtv.star.shared.data.DeviceRegistration.prepareLogin(
            library,repository,credentials,
        )
        repository.credentials = prepared
        require(profileUsable(repository)) {
            "平台没有返回账号资料，参数可能已过期（cookieStore.xml 要取最新的那一份）"
        }
        return prepared
    }

    /**
     * 把一份登录得到的凭据交给仓库并确认它真的可用。
     *
     * 扫码登录与手机号登录都走这一条：协议那边说「成功」还不够，
     * 平台对无效会话同样不报错，只有拉到一份**非匿名**的资料才算真的登录上了。
     *
     * @return 失败原因（可直接展示）；成功返回 null。
     */
    fun verify(credentials: AccountCredentials, repository: ContentRepository): String? {
        repository.credentials = credentials
        val profile = runCatching { repository.accountProfile() }.getOrNull()
            ?: return "平台没有返回账号资料，请稍后重试"
        if (profile.anonymous) return "这份登录态无效：平台把它当成了匿名设备"
        return null
    }

    private fun profileUsable(repository: ContentRepository): Boolean {
        val profile = repository.accountProfile() ?: return false
        return !profile.anonymous
    }
}

/**
 * 本机记录与账号记录的合并（电视端与手机端共用一份）。
 *
 * 规则只有一条：**本机在前，账号侧补后面，同一部剧只出现一次**（以本机那条为准，
 * 因为本机那条带着更新状态 / 进度）。两端各写一遍的话，迟早会出现
 * 「电视上是 12 条、手机上 15 条」这种对不上的情况。
 */
object AccountMerge {

    fun series(local: List<com.hgtv.star.core.Series>, account: List<com.hgtv.star.core.Series>): List<com.hgtv.star.core.Series> {
        val seen = local.mapTo(HashSet()) { it.id }
        return local + account.filterNot { it.id in seen }
    }
}
