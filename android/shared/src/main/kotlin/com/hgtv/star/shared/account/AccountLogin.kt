// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.account

import com.hgtv.star.core.AccountCredentials
import com.hgtv.star.core.AccountCredentialsParser
import com.hgtv.star.core.AccountFavorite
import com.hgtv.star.core.AccountHistory
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.Series
import com.hgtv.star.core.WatchSync
import com.hgtv.star.core.accountProfile
import com.hgtv.star.core.isFinished
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.data.WatchProgress
import com.hgtv.star.shared.data.account
import com.hgtv.star.shared.data.accountDevice
import com.hgtv.star.shared.data.saveAccountDevice

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

/** 本机 + 账号合并后的一条历史；剧集、进度、集号和时间都来自实际胜出的那条记录。 */
data class MergedHistory(
    val series: Series,
    val episodeIndex: Int,
    val episodeId: String,
    val position: Long,
    val episodes: Int,
    val updatedAt: Long,
    val finished: Boolean,
)

/**
 * 本机记录与账号记录的合并（电视端与手机端共用一份）。
 *
 * 历史同一部剧只保留一条：先比集号，再比集内位置，最后比更新时间；
 * 合并后的排序统一按两边较新的更新时间倒序。收藏有网络收藏时间的按时间倒序，
 * 本地独有项保留本机原有顺序。
 */
object AccountMerge {

    fun series(local: List<Series>, account: List<Series>): List<Series> {
        val seen = local.mapTo(HashSet()) { it.id }
        return local + account.filterNot { it.id in seen }
    }

    fun history(local: List<WatchProgress>, account: List<AccountHistory>): List<MergedHistory> {
        val localById = linkedMapOf<String, WatchProgress>()
        local.forEach { row ->
            val previous = localById[row.series.id]
            if (previous == null || compareProgress(row, previous) > 0) {
                localById[row.series.id] = row
            }
        }
        val accountById = linkedMapOf<String, AccountHistory>()
        account.forEach { row ->
            val previous = accountById[row.series.id]
            if (previous == null || compareAccount(row, previous) > 0) {
                accountById[row.series.id] = row
            }
        }
        val ids = LinkedHashSet<String>(localById.size + accountById.size).apply {
            addAll(localById.keys)
            addAll(accountById.keys)
        }
        return ids.mapNotNull { id ->
            mergeHistory(id, localById[id], accountById[id])
        }.sortedWith(
            compareByDescending<MergedHistory> { it.updatedAt }
                .thenByDescending { it.episodeIndex }
                .thenBy { it.series.id }
        )
    }

    fun historyForSeries(
        local: List<WatchProgress>,
        account: List<AccountHistory>,
        seriesId: String,
    ): MergedHistory? = history(local, account).firstOrNull { it.series.id == seriesId }

    fun favorites(local: List<Series>, account: List<AccountFavorite>): List<Series> {
        val accountById = account.associateBy { it.series.id }
        val localIds = local.mapTo(HashSet()) { it.id }
        val rows = ArrayList<Pair<Series, Long>>(local.size + account.size)
        local.forEach { series ->
            rows += series to (accountById[series.id]?.collectTime ?: 0L)
        }
        account.forEach { row ->
            if (row.series.id !in localIds) rows += row.series to row.collectTime
        }
        return rows.sortedByDescending { it.second }.map { it.first }
    }

    private fun compareProgress(a: WatchProgress, b: WatchProgress): Int =
        compareValuesBy(a, b, { it.episodeIndex }, { it.position }, { it.updatedAt })

    private fun compareAccount(a: AccountHistory, b: AccountHistory): Int =
        compareValuesBy(
            a,
            b,
            { it.episodeIndex },
            { it.position },
            { WatchSync.millis(it.updatedAt) },
        )

    private fun mergeHistory(
        id: String,
        local: WatchProgress?,
        account: AccountHistory?,
    ): MergedHistory? {
        if (local == null && account == null) return null
        val localFinished = local?.isFinished() == true
        val accountFinished = account?.isFinished() == true
        val localWins = when {
            local == null -> false
            account == null -> true
            local.episodeIndex != account.episodeIndex -> local.episodeIndex > account.episodeIndex
            local.position != account.position -> local.position > account.position
            else -> local.updatedAt >= WatchSync.millis(account.updatedAt)
        }
        val total = maxOf(
            local?.series?.episodeCount ?: 0,
            account?.episodes ?: 0,
            account?.series?.episodeCount ?: 0,
        )
        val chosenSeries = when {
            localWins -> local!!.series
            account != null -> account.series
            else -> local!!.series
        }
        return MergedHistory(
            series = if (total > 0) chosenSeries.copy(episodeCount = total) else chosenSeries,
            episodeIndex = if (localWins) local!!.episodeIndex else account?.episodeIndex ?: 0,
            episodeId = if (localWins) local!!.episodeId else account?.episodeId.orEmpty(),
            position = if (localWins) local!!.position else account?.position ?: 0L,
            episodes = total,
            updatedAt = maxOf(
                local?.updatedAt ?: 0L,
                WatchSync.millis(account?.updatedAt ?: 0L),
            ),
            finished = localFinished || accountFinished,
        )
    }
}
