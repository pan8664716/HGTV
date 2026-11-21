// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import com.hgtv.star.core.AccountData
import com.hgtv.star.core.BackupProgress
import com.hgtv.star.core.Series
import com.hgtv.star.shared.data.AppRuntime

/**
 * Phone 侧的账号兼容壳。
 *
 * 真正的状态、远端覆盖、缓存写入和云端副作用已经收在
 * [com.hgtv.star.shared.feature.account.AccountController]。保留这个对象只是为了让
 * 现有页面在迁移期继续用短调用点，后续可直接删除。
 */
object PhoneAccountSync {

    val data: AccountData?
        get() = AppRuntime.accountControllerOrNull()?.data

    fun favorite(
        context: Context,
        series: Series,
        added: Boolean,
        onNeedSignIn: () -> Unit = {},
    ) = AppRuntime.accountController(context).favorite(series, added, onNeedSignIn)

    fun favoritesDeleted(context: Context, series: List<Series>) =
        AppRuntime.accountController(context).favoritesDeleted(series)

    fun progress(context: Context, progress: BackupProgress, episodeCount: Int = 0) =
        AppRuntime.accountController(context).progress(progress, episodeCount)

    fun historyDeleted(context: Context, series: Series) =
        AppRuntime.accountController(context).historyDeleted(series)

    fun historyDeleted(context: Context, seriesIds: Collection<String>) =
        AppRuntime.accountController(context).historyDeleted(seriesIds)

    fun reserve(
        context: Context,
        series: Series,
        reserved: Boolean,
        onNeedSignIn: () -> Unit = {},
        onDone: (Boolean) -> Unit = {},
    ) = AppRuntime.accountController(context).reserve(series, reserved, onNeedSignIn, onDone)

    fun reservationsDeleted(context: Context, seriesIds: Collection<String>) =
        AppRuntime.accountController(context).reservationsDeleted(seriesIds)

    fun likesDeleted(context: Context, seriesIds: Collection<String>) =
        AppRuntime.accountController(context).likesDeleted(seriesIds)

    suspend fun refresh(context: Context): AccountData? =
        AppRuntime.accountController(context).refresh().data

    suspend fun refresh(context: Context, columns: Set<String>): AccountData? =
        AppRuntime.accountController(context).refresh(columns).data

    suspend fun historyPage(
        context: Context,
        offset: Int,
        limit: Int,
    ) = AppRuntime.accountController(context).refreshHistory(offset, limit)

    fun store(context: Context, value: AccountData?) =
        AppRuntime.accountController(context).store(value)

    fun clear() = AppRuntime.accountControllerOrNull()?.clear()
}
