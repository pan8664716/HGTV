// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.architecture

import com.hgtv.star.core.WorkLanes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 线程预算的 Android 侧入口。
 *
 * 车道本身定义在 `core` 的 [WorkLanes]（`ContentRepository` 也要用同一份预算），
 * 这里只补两样只有 Android 才知道的东西：主线程调度器，以及图片解码的并行度。
 *
 * 改造前的状况：`MainActivity` 一家 7 个池、`ContentRepository` 4 个、加上图片 / 弹幕表情 /
 * 写盘 / 心跳 / DNS 等零散单线程池，**峰值 40 条以上**，而且这些池属于 Activity 实例，
 * 每次重建新建一批、`onDestroy` 再关掉。对 2GB 的电视盒子，这既是 40MB 的线程栈，
 * 也是「一边滚列表一边播就卡」的直接原因：8 个解码线程 + 6 个账号请求 + 4 个榜单请求
 * 同时挤在 4 核 A53 上，播放线程拿不到稳定时间片。
 */
object AppExecutors {

    /** 内容接口 / 账号 / 推荐 / 预取 / 播放地址。 */
    val network: WorkLanes.Lane get() = WorkLanes.network

    /** 扇出型并发。 */
    val fanout: WorkLanes.Lane get() = WorkLanes.fanout

    /** 建播放器 / 枚举解码器 / 预热下一集。 */
    val player: WorkLanes.Lane get() = WorkLanes.player

    /** 弹幕分页拉取。 */
    val danmaku: WorkLanes.Lane get() = WorkLanes.danmaku

    /** 本地记录与缓存落盘。 */
    val disk: WorkLanes.Lane get() = WorkLanes.disk

    /** 主线程。由 `kotlinx-coroutines-android` 提供。 */
    val main: CoroutineDispatcher get() = Dispatchers.Main

    /**
     * 图片解码**不并入上面的道**，这是刻意的。
     *
     * 它有自己的管线：优先级队列（焦点卡先出）+ 任务去重 + 逐任务取消，
     * 换成「按道提交协程」会丢掉优先级，焦点卡会和预取图排同一个队。
     * 并行度由 `ArtworkLoader` 按设备判（低内存 3 / 小堆 4 / 其余 8），
     * 那条判据已经带着实测依据（8 个并发在 4 核 A53 上吞吐反而下降）。
     *
     * 这里只登记**上限**，让「全进程最多几条解码线程」有唯一答案。
     */
    const val MAX_IMAGE_WORKERS = 8

    /** 诊断用的一行摘要。 */
    fun describe(): String = WorkLanes.describe() + " image<=" + MAX_IMAGE_WORKERS
}
