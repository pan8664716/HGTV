// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.AccountData
import com.hgtv.star.core.AccountCacheEntry
import com.hgtv.star.core.AccountSnapshot
import com.hgtv.star.core.AppConfig
import com.hgtv.star.core.CatalogPage
import com.hgtv.star.core.CatalogSnapshot
import com.hgtv.star.core.ComicRanking
import com.hgtv.star.core.LatestWriteQueue
import com.hgtv.star.core.RankBoard
import com.hgtv.star.core.Series

internal class LibraryHomeCacheStore(
    private val writes: LatestWriteQueue<String, () -> Any?>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val homes = mutableMapOf<RankBoard, CatalogPage>()
    private val savedAt = mutableMapOf<RankBoard, Long>()

    fun cacheKey(board: RankBoard): String = "home-${board.storedValue}-v3"

    fun replace(cached: Map<RankBoard, CatalogPage>, stamps: Map<RankBoard, Long>) {
        homes.putAll(cached)
        savedAt.putAll(stamps)
    }

    fun cached(board: RankBoard): CatalogPage? {
        val age = clock() - (savedAt[board] ?: return null)
        return homes[board].takeIf { age in 0..AppConfig.Library.HOME_CACHE_TTL_MS }
    }

    fun cache(
        board: RankBoard,
        items: List<Series>,
        hasMore: Boolean,
        ranking: ComicRanking? = null,
    ) {
        if (items.isEmpty()) return
        val now = clock()
        val retained = items.take(AppConfig.Library.HOME_CACHE_MAX_ITEMS)
            .map { it.copy(description = it.description.take(1500)) }
        homes[board] = CatalogPage(retained, hasMore, ranking)
        savedAt[board] = now
        writes.submit(mapOf(cacheKey(board) to {
            CatalogSnapshot.encode(retained, hasMore, now, ranking)
        }))
    }
}

internal class LibraryAccountCacheStore(
    private val writes: LatestWriteQueue<String, () -> Any?>,
    private val identityKey: () -> String,
    private val identityAvailable: () -> Boolean,
) {
    private var cached: AccountCacheEntry? = null
    private var stamp = ""

    fun replace(entry: AccountCacheEntry?) {
        cached = entry
        stamp = fingerprint()
    }

    fun current(): AccountCacheEntry? {
        // 指纹对不上就是换了账号（或退出了）：那份缓存不属于当前登录态，直接丢掉。
        if (stamp != fingerprint()) {
            cached = null
            stamp = fingerprint()
        }
        // 没有任何可隔离的缓存身份时一律返回 null。
        if (cached != null && !identityAvailable()) {
            cached = null
            return null
        }
        return cached
    }

    fun cache(data: AccountData, now: Long = System.currentTimeMillis()) {
        val entry = AccountCacheEntry(AccountSnapshot.cacheable(data), now)
        cached = entry
        stamp = fingerprint()
        runCatching {
            writes.submit(mapOf("account" to {
                AccountSnapshot.encode(entry.data, entry.savedAt)
            }))
        }
    }

    fun clear() {
        cached = null
        stamp = fingerprint()
        runCatching { writes.submit(mapOf("account" to { null })) }
    }

    private fun fingerprint(): String {
        val identity = identityKey()
        if (identity.isBlank()) return ""
        var hash = 1125899906842597L
        identity.forEach { char -> hash = 31 * hash + char.code }
        return "%016x".format(hash)
    }
}
