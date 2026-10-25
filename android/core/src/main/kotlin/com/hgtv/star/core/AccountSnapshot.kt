// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject

/** 一份落盘的账号数据（[AccountSnapshot] 的产物）与它的写入时刻。 */
data class AccountCacheEntry(val data: AccountData, val savedAt: Long)

/**
 * 账号数据（资料 + 收藏 / 历史 / 点赞 / 预约）的**磁盘快照**。
 *
 * 为什么要有它：这五份数据原先只活在内存里（`MainActivity.accountData`），进程一重启就没了，
 * 于是每次打开 App 都要重新发 5 个并发请求才敢铺「我的」页与收藏 / 历史集合页——
 * 用户读到的是「进这一页总要等一下，转一圈才出内容」。
 *
 * 有了快照，启动时先把上次的内容**立刻**铺出来，再在后台刷一次；刷新结果与快照逐栏比对，
 * **哪一栏真的变了才重画哪一栏**（见 [changedKeys]）。
 *
 * 三个必须守住的边界：
 * * **体积**：一份快照上限 [MAX_BYTES]，超出的一律当坏数据丢掉（不写、也不读）；
 * * **时效**：超过 [MAX_AGE] 的快照直接不用——账号数据会过期，翻出七天前的内容只会误导；
 * * **账号归属**：快照本身不带身份，由调用方（`Library`）按 Cookie 指纹对号，换账号即作废。
 *
 * 与备份文件（[LibraryBackup]）的分工：那份是**本机记录**、可跨设备迁移；这份是**账号侧缓存**，
 * 不进备份、不迁移，账号一退就清。
 */
object AccountSnapshot {
    /** 一份快照的体积上限。实测 200 条收藏 + 200 条历史约 120 KB，留足余量。 */
    const val MAX_BYTES = 512 * 1024
    /** 快照的保留上限：比这更旧的直接当没有。 */
    const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
    /** 每一栏最多缓存多少条（接口单页就是 50 条量级，多出来的只是冗余）。 */
    const val MAX_ROWS = 200
    private const val VERSION = 1
    /** 五栏的名字（[AccountData.errors] 的键名），也是 [changedKeys] 的取值域。 */
    val KEYS = setOf("资料", "收藏", "历史", "点赞", "预约")
    /** 简介在快照里只留开头这么多字：卡片上显示不了那么多，缓存里也不必带着。 */
    private const val DESCRIPTION = 400
    private val idPattern = Regex("[0-9]{1,30}")

    // —— 编码 ——

    fun encode(data: AccountData, savedAt: Long): String = JSONObject()
        .put("version", VERSION)
        .put("savedAt", savedAt)
        .put("profile", data.profile?.let(::profileJson) ?: JSONObject.NULL)
        .put("favorites", JSONArray(data.favorites.take(MAX_ROWS).map(::favoriteJson)))
        .put("history", JSONArray(data.history.take(MAX_ROWS).map(::historyJson)))
        .put("likes", JSONArray(data.likes.take(MAX_ROWS).map(::likeJson)))
        .put("reserves", JSONArray(data.reserves.take(MAX_ROWS).map(::reserveJson)))
        .toString()

    private fun profileJson(profile: AccountProfile) = JSONObject()
        .put("userId", profile.userId)
        .put("nickname", profile.nickname)
        .put("avatar", profile.avatar)
        .put("displayId", profile.displayId)
        .put("signature", profile.signature.take(200))
        .put("follows", profile.follows ?: JSONObject.NULL)
        .put("fans", profile.fans ?: JSONObject.NULL)
        .put("likes", profile.likes ?: JSONObject.NULL)
        .put("anonymous", profile.anonymous)

    private fun seriesJson(series: Series) = series
        .copy(description = series.description.take(DESCRIPTION), cover = series.cover.take(2048))
        .toJson()

    private fun favoriteJson(row: AccountFavorite) = JSONObject()
        .put("series", seriesJson(row.series)).put("collectTime", row.collectTime).put("episodes", row.episodes)

    private fun historyJson(row: AccountHistory) = JSONObject()
        .put("series", seriesJson(row.series)).put("episodeIndex", row.episodeIndex)
        .put("episodeId", row.episodeId).put("updatedAt", row.updatedAt).put("episodes", row.episodes)
        .put("position", row.position)

    private fun likeJson(row: AccountLike) = JSONObject()
        .put("series", seriesJson(row.series)).put("updatedAt", row.updatedAt)

    private fun reserveJson(row: AccountReserve) = JSONObject()
        .put("series", seriesJson(row.series)).put("updatedAt", row.updatedAt).put("online", row.online)

    // —— 解码 ——

    /** 读回一份快照；版本不符 / 过期 / 结构坏掉 / 体积超限一律返回 null（调用方当作没有缓存）。 */
    fun decode(raw: String, now: Long): AccountCacheEntry? = runCatching {
        require(raw.isNotEmpty() && raw.length <= MAX_BYTES) { "账号缓存体积异常" }
        val root = JSONObject(raw)
        require(root.optInt("version") == VERSION) { "账号缓存版本不支持" }
        val savedAt = root.optLong("savedAt")
        require(now - savedAt in 0..MAX_AGE) { "账号缓存已过期" }
        AccountCacheEntry(
            AccountData(
                root.optJSONObject("profile")?.let(::profile),
                rows(root, "favorites", ::favorite),
                rows(root, "history", ::history),
                rows(root, "likes", ::like),
                rows(root, "reserves", ::reserve)
            ),
            savedAt
        )
    }.getOrNull()

    private fun <T> rows(root: JSONObject, key: String, parse: (JSONObject) -> T?): List<T> {
        val array = root.optJSONArray(key) ?: return emptyList()
        require(array.length() <= MAX_ROWS) { "账号缓存条目过多" }
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(parse) }
    }

    private fun profile(row: JSONObject): AccountProfile? {
        val nickname = row.str("nickname")
        val avatar = row.str("avatar")
        // 昵称与头像都空的那一条不算资料（与 [AccountParser.profile] 同一条判据）。
        if (nickname.isEmpty() && avatar.isEmpty()) return null
        return AccountProfile(row.str("userId"), nickname, cleanUrl(avatar), row.str("displayId"),
            row.str("signature"), row.intOrNull("follows"), row.intOrNull("fans"), row.intOrNull("likes"),
            row.optBoolean("anonymous", false))
    }

    private fun series(row: JSONObject?): Series? = row
        ?.let { runCatching { Series.fromJson(it) }.getOrNull() }
        ?.takeIf { idPattern.matches(it.id) && it.title.isNotBlank() }

    private fun favorite(row: JSONObject) = series(row.optJSONObject("series"))
        ?.let { AccountFavorite(it, row.optLong("collectTime"), row.optInt("episodes")) }

    private fun history(row: JSONObject) = series(row.optJSONObject("series"))?.let {
        AccountHistory(it, row.optInt("episodeIndex").coerceAtLeast(0), row.str("episodeId"),
            row.optLong("updatedAt"), row.optInt("episodes"), row.optLong("position").coerceAtLeast(0))
    }

    private fun like(row: JSONObject) = series(row.optJSONObject("series"))
        ?.let { AccountLike(it, row.optLong("updatedAt")) }

    private fun reserve(row: JSONObject) = series(row.optJSONObject("series"))
        ?.let { AccountReserve(it, row.optLong("updatedAt"), row.optBoolean("online", false)) }

    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

    // —— 刷新结果的比对与合并 ——

    /**
     * 刷新结果里哪几栏**真的变了**（[AccountData.errors] 的键名：资料 / 收藏 / 历史 / 点赞 / 预约）。
     *
     * 只比「界面上看得见的东西」：id、标题、封面、集数、看到第几集、时间。
     * 不比整条记录的所有字段——平台偶尔会把简介截断、把封面参数重排，那类差异不该让整页重建
     * （重建一次就要重铺栅格、重取封面、还可能动到滚动位置，用户读到的是「每次刷新都闪一下」）。
     *
     * [previous] 为 null（还没有缓存、这是第一次拿到数据）时返回全部五栏：界面必须铺一次。
     */
    fun changedKeys(previous: AccountData?, next: AccountData): Set<String> {
        if (previous == null) return KEYS
        val changed = linkedSetOf<String>()
        if (profileKey(previous.profile) != profileKey(next.profile)) changed += "资料"
        if (previous.favorites.map(::favoriteKey) != next.favorites.map(::favoriteKey)) changed += "收藏"
        if (previous.history.map(::historyKey) != next.history.map(::historyKey)) changed += "历史"
        if (previous.likes.map(::likeKey) != next.likes.map(::likeKey)) changed += "点赞"
        if (previous.reserves.map(::reserveKey) != next.reserves.map(::reserveKey)) changed += "预约"
        return changed
    }

    private fun profileKey(profile: AccountProfile?) = profile?.let {
        listOf(it.nickname, it.avatar, it.displayId, it.signature, it.follows, it.fans, it.likes, it.anonymous)
    }

    private fun seriesKey(series: Series) = listOf(series.id, series.title, series.cover, series.badge, series.tags)

    private fun favoriteKey(row: AccountFavorite) = seriesKey(row.series) + row.episodes.toString()
    private fun historyKey(row: AccountHistory) =
        seriesKey(row.series) + listOf(
            row.episodeIndex.toString(), row.episodes.toString(), row.updatedAt.toString(),
            // 集内位置也算「界面上看得见的东西」：历史卡片的状态行要跟着它变。
            row.position.toString()
        )
    private fun likeKey(row: AccountLike) = seriesKey(row.series)
    private fun reserveKey(row: AccountReserve) = seriesKey(row.series) + row.online.toString()

    /**
     * 把一次刷新并进已有数据：**这一栏请求失败时保留上一份**，不要用空列表覆盖。
     *
     * 五栏是五个独立请求（见 `ContentRepository.accountData`），实测任一条都可能被网关挡掉回空
     * （`errors` 里记着原因）。直接整份替换的后果是「刷新一次，收藏栏空了」——
     * 用户看到的是「我的收藏被清空了」，而真实原因只是这一次请求没回来。
     */
    fun merge(previous: AccountData?, fresh: AccountData): AccountData {
        if (previous == null || fresh.errors.isEmpty()) return fresh
        return fresh.copy(
            profile = if ("资料" in fresh.errors) previous.profile else fresh.profile,
            favorites = if ("收藏" in fresh.errors) previous.favorites else fresh.favorites,
            history = if ("历史" in fresh.errors) previous.history else fresh.history,
            likes = if ("点赞" in fresh.errors) previous.likes else fresh.likes,
            reserves = if ("预约" in fresh.errors) previous.reserves else fresh.reserves
        )
    }

    /** 落盘的那一份不带 [AccountData.errors]：失败原因是一次性的，不该跟着缓存活到下次启动。 */
    fun cacheable(data: AccountData) = if (data.errors.isEmpty()) data else data.copy(errors = emptyMap())
}
