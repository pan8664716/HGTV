// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 本机观看进度（[BackupProgress]）与账号侧观看历史（[AccountHistory]）的转换规则。
 *
 * 登录后账号是唯一真源：远端历史整表覆盖本机缓存，本机只保留远端存在的那几条。
 * 用户主动播放产生的进度仍会直接写远端，但这里不再做「本机更超前就补传」的对账。
 *
 * ## 时间单位
 *
 * 账号侧的 `last_watch_time` 实测是**秒**（平台其它时间字段都是秒），而本机
 * `updatedAt` 是 `System.currentTimeMillis()`（毫秒）。两者直接比较会得到
 * 「本机永远更新」的错误结论，因此统一用 [millis] 归一化。
 */
object WatchSync {
    /** 超过这个值就当成毫秒（1e11 毫秒 ≈ 1973 年，秒级时间戳远达不到）。 */
    private const val EPOCH_MS=100_000_000_000L

    /** 平台时间戳归一成毫秒：秒级 ×1000，毫秒级原样，缺省 / 负数当 0（未知）。 */
    fun millis(raw: Long): Long = when {
        raw<=0 -> 0
        raw<EPOCH_MS -> raw*1000
        else -> raw
    }

    /** 一份进度的时间戳；未知时用 [now]（刚拉回来的账号记录就是「此刻才知道」的）。 */
    fun at(raw: Long,now: Long): Long = millis(raw).let { if(it>0) it else now }

    /**
     * 用户主动播放的本机进度 → 账号缓存那一行。
     *
     * 保留账号那一行的总集数与元数据：`episodes` 是「全 N 集」与「已看完」的判据，
     * 本机只在账号没给时才用自己的总集数兜底。
     */
    fun toAccount(local: BackupProgress,row: AccountHistory?): AccountHistory = AccountHistory(
        series=row?.series?.takeIf { it.title.isNotBlank() } ?: local.series,
        episodeIndex=local.episodeIndex,
        episodeId=local.episodeId,
        updatedAt=local.updatedAt,
        episodes=row?.episodes?.takeIf { it>0 } ?: local.series.episodeCount,
        position=local.position
    )
}
