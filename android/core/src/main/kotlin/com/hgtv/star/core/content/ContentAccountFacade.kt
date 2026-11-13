// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

fun ContentRepository.accountProfile(): AccountProfile? = accountProfileImpl()
fun ContentRepository.accountFavorites(): List<AccountFavorite> = accountFavoritesImpl()
fun ContentRepository.accountHistory(): List<AccountHistory> = accountHistoryImpl()
fun ContentRepository.accountLikes(): List<AccountLike> = accountLikesImpl()
fun ContentRepository.accountReserves(): List<AccountReserve> = accountReservesImpl()
fun ContentRepository.accountReserve(seriesId: String, subscribe: Boolean): Boolean =
    accountReserveImpl(seriesId,subscribe)
fun ContentRepository.accountAddFavorite(seriesId: String): Boolean =
    accountAddFavoriteImpl(seriesId)
fun ContentRepository.accountRemoveFavorite(seriesId: String): Boolean =
    accountRemoveFavoriteImpl(seriesId)
fun ContentRepository.accountUploadProgress(
    seriesId: String,
    episodeId: String,
    episodeIndex: Int,
    position: Long,
    duration: Long,
    episodeCount: Int=0,
): Boolean =
    accountUploadProgressImpl(seriesId,episodeId,episodeIndex,position,duration,episodeCount)
fun ContentRepository.accountDeleteHistory(seriesId: String): Boolean =
    accountDeleteHistoryImpl(seriesId)
fun ContentRepository.accountDeleteHistories(seriesIds: List<String>): Boolean =
    accountDeleteHistoriesImpl(seriesIds)
fun ContentRepository.accountHistoryIds(): List<String> = accountHistoryIdsImpl()
fun ContentRepository.accountClearHistory(extra: List<String> = emptyList()): Boolean =
    accountClearHistoryImpl(extra)
fun ContentRepository.accountData(): AccountData = accountDataImpl()
internal fun ContentRepository.authorOf(id: String): String = authorOfImpl(id)
fun ContentRepository.authorsOf(ids: List<String>): Map<String,String> = authorsOfImpl(ids)
