// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 本机进度与账号历史的**对账**（电视端与手机端共用一份，纯计算、可单测）。
 *
 * ## 为什么必须对账
 *
 * 历史有两个来源：本机进度（首页历史栏、详情页续播读它）与账号历史（「我的 → 历史」读它）。
 * 两边各写各的时候：机器 1 看到第 200 集、机器 2 看到第 400 集，回到机器 1 首页还是 200，
 * 进「我的」有时 400 有时 200——取决于账号侧这一栏有没有刷新回来。用户读到的就是
 * 「两个历史在打架」。
 *
 * ## 规则
 *
 * 逐条比「谁更超前」（[WatchSync.ahead]，时间差有死区、集号更大优先）：
 *
 * * 账号更超前 → 用账号那一份改写本机进度（[WatchSync.toLocal]），首页与续播立刻跟上；
 * * 本机更超前 → 用本机那一份改写账号那一行（[WatchSync.toAccount]），「我的 → 历史」
 *   不再显示旧集数；同时把这条本机进度交给调用方推回云端（[Result.uploads]），
 *   第三台设备不会又看到旧的。
 * * 只有一边存在 → 网络独有写入本机，本机独有补传网络，保证两端最终都有记录。
 *
 * 这里**只算不改**：要写盘的本机进度放在 [Result.writes]，由调用方自己落盘
 * （电视与手机的写队列不是同一个对象，但「写哪几条」这条判断必须只有一份）。
 */
object HistoryReconcile {

    data class Result(
        /** 对账之后的账号数据（历史已按时间倒序）。 */
        val data: AccountData,
        /** 本机更超前、需要推回云端的那几条（调用方按剧去重后上传）。 */
        val uploads: List<BackupProgress>,
        /** 账号更超前、需要写回本机的那几条（调用方逐条 `library.save`）。 */
        val writes: List<BackupProgress>,
    )

    /**
     * @param data 刚拉回来的账号数据
     * @param local 本机全部观看进度（调用方应先滤掉「本机删过」的剧）
     */
    fun run(data: AccountData, local: List<BackupProgress>, now: Long): Result {
        val mine = local.associateBy { it.series.id }
        val uploads = mutableListOf<BackupProgress>()
        val writes = mutableListOf<BackupProgress>()
        val rows = data.history.map { row ->
            val own = mine[row.series.id]
            if (own == null) {
                // 网络端独有：补一条本机进度，离线打开也能立即看到。
                writes += WatchSync.toLocal(row, null, now)
                return@map row
            }
            val accountAhead = WatchSync.ahead(
                row.episodeIndex, row.updatedAt, row.position,
                own.episodeIndex, own.updatedAt, own.position,
            )
            if (accountAhead) {
                val pulled = WatchSync.toLocal(row, own, now)
                // 只在**真的不一样**时才写：每次刷新都写一遍会把写队列刷满，还会把同一条
                // 记录的时间戳来回改，界面上的排序跟着抖。
                if (pulled != own) writes += pulled
                row
            } else {
                if (!WatchSync.matches(row, own)) uploads += own
                WatchSync.toAccount(own, row)
            }
        }
        val accountIds = data.history.mapTo(HashSet()) { it.series.id }
        local.filterNot { it.series.id in accountIds }.forEach { own ->
            // 本机独有：补传网络端，换设备后也能看到这条历史。
            uploads += own
        }
        // 顺序也统一成「按时间倒序」：账号那份是云端给的顺序，本机更超前的那条在云端还排在
        // 后面，不重排的话「我的 → 历史」里第 400 集那条会沉到第 200 集下面。
        return Result(
            data = data.copy(history = rows.sortedByDescending { WatchSync.millis(it.updatedAt) }),
            uploads = uploads,
            writes = writes,
        )
    }
}
