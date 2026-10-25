// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import kotlin.math.abs

/**
 * 本机观看进度（[BackupProgress]）与账号侧观看历史（[AccountHistory]）的对账规则。
 *
 * ## 为什么需要它
 *
 * 界面上有两份「看到第几集」：本机那份（首页历史栏、详情页续播读它）与账号那份
 * （「我的 → 历史」、历史集合页读它）。原先两份各写各的：这台机器看到第 200 集、
 * 另一台看到第 400 集，账号侧刷新回来只覆盖账号那一份，本机那份一直是 200，
 * 于是同一个账号在两处页面显示不同的集数——用户读到的就是「历史同步乱了」。
 *
 * 规则只有一条：**同一部剧只认更超前的那一份，并且两边都写成它**。
 * 这里只放纯函数，界面侧（`MainActivity.reconcileHistory`）负责落盘与上传。
 *
 * ## 时间单位
 *
 * 账号侧的 `last_watch_time` 实测是**秒**（平台其它时间字段都是秒），而本机
 * `updatedAt` 是 `System.currentTimeMillis()`（毫秒）。两者直接比较会得到
 * 「本机永远更新」的错误结论，因此统一用 [millis] 归一化。
 */
object WatchSync {
    /** 两份时间差在这个范围内算「同一时刻」（毫秒）。平台时间戳只到秒，留一点余量。 */
    const val SAME_MS=2_000L
    /**
     * 同一集里判「谁更超前」时，时间要差到这个程度才以时间为准（毫秒）。
     *
     * 两台设备的时钟天然有几十秒偏差，进度上传又很密：不给一个死区，两边会互相把对方
     * 盖掉，云端那一行来回跳。死区之内一律以「集内位置更大」为准。
     */
    const val SKEW_MS=120_000L
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
     * [a] 这一份是不是比 [b] 更超前（更该被保留）。
     *
     * 判据按顺序：
     * 1. **集号**——集号是单调的，看过的集数不该因为另一台设备的时钟快几十秒就倒退。
     *    这正是用户反馈里「机器 1 看到 200 集、机器 2 看到 400 集」该取 400 的原因。
     * 2. 同一集里：时间（差出 [SKEW_MS] 才算数，避免时钟抖动）→ 集内位置。
     * 时间未知的两条也能比：那时直接看集号与集内位置。
     */
    fun ahead(aIndex: Int,aAt: Long,aPosition: Long,bIndex: Int,bAt: Long,bPosition: Long): Boolean {
        if(aIndex!=bIndex) return aIndex>bIndex
        val a=millis(aAt); val b=millis(bAt)
        if(a>0 && b>0 && abs(a-b)>=SKEW_MS) return a>b
        return aPosition>bPosition
    }

    /** 账号那一行与本机这一条说的**是不是同一件事**（同一集、同一位置、同一时刻）。 */
    fun matches(row: AccountHistory,progress: BackupProgress): Boolean =
        row.episodeId==progress.episodeId && row.episodeIndex==progress.episodeIndex &&
            row.position==progress.position &&
            abs(millis(row.updatedAt)-progress.updatedAt)<=SAME_MS

    /**
     * 账号那一行 → 本机进度（账号更超前时用）。
     *
     * 同一条剧、同一集时沿用本机的 `duration` 与 `completed`：账号那边不带时长，
     * 而首页历史栏的进度条与「整剧已看完」都靠它；换集 / 对不上时这两个字段只能保守取 0 / false。
     * 剧集元数据一律用本机那份（封面、简介、总集数都齐），本机没有才用账号里带回来的。
     */
    fun toLocal(row: AccountHistory,local: BackupProgress?,now: Long): BackupProgress {
        val same=local!=null && local.episodeId==row.episodeId && local.episodeIndex==row.episodeIndex
        return BackupProgress(
            series=local?.series ?: row.series,
            episodeId=row.episodeId,
            episodeIndex=row.episodeIndex,
            position=row.position.coerceAtLeast(0),
            duration=if(same) local!!.duration.coerceAtLeast(0) else 0,
            completed=same && local!!.completed,
            updatedAt=at(row.updatedAt,now)
        )
    }

    /**
     * 本机进度 → 账号那一行（本机更超前时用）。
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
