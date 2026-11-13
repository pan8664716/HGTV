// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全进程的线程预算（纯 JVM 部分）。
 *
 * 定义放在 `core` 而不是 `app`：`ContentRepository` 自己也持有 4 个线程池
 * （账号 6 条、热搜 5 条、首页四栏每次新建 4 条、官网搜索每次新建 N 条），
 * 它们必须和界面共用同一份预算，否则「统一线程模型」只是界面层的一句空话。
 *
 * 主线程调度器（`Dispatchers.Main`）依赖 Android，放在 `app` 的
 * `AppExecutors` 里；其余道两边共用这一份。
 *
 * ## 分道规则
 *
 * | 道 | 线程 | 干什么 | 为什么是这么多 |
 * | --- | --- | --- | --- |
 * | [network] | 4 | 内容接口、账号、推荐流、预取、播放地址 | 冷启动时会同时有「首页 + 推荐 + 账号 + 预取」在跑；再多只是把带宽切成更碎的片 |
 * | [fanout] | 6 | 扇出型并发（首页四栏、热搜种子、账号七请求、官网搜索十页） | 这些任务互不依赖但由同一个调用方 join，必须容纳最大扇出，否则 join 会把并发退化成串行 |
 * | [player] | 2 | 建播放器、枚举解码器、预热下一集 | 建播放器实测 0.5 秒（`MediaCodecList` 全表扫描），不能排在网络后面 |
 * | [danmaku] | 1 | 弹幕分页拉取 | 一集要按 30 秒窗口连拉几十页 |
 * | [disk] | 1 | 本地记录与缓存落盘 | 串行落盘天然正确，且不和播放读盘抢 IO |
 *
 * 每个道同时暴露 [Lane.executor]（给 `submit` / `Future`）与 [Lane.dispatcher]（给协程），
 * 两者跑在同一批线程上，因此预算只有一份。
 *
 * ## 生命周期
 *
 * 守护线程、进程级。**不要在 Activity / 页面销毁时关闭**——关闭会让「重建后的新页面
 * 提交到已关闭的池」变成一类必然崩溃（改造前 7 个 `shutdownNow()` 正是这个隐患）。
 */
object WorkLanes {

    /**
     * 一条命名车道。
     *
     * @param name 线程名前缀：`adb shell ps -T` 里能直接看出这条线程属于哪条道
     * @param threads 固定线程数
     */
    class Lane(val name: String, private val threads: Int) {
        private val counter = AtomicInteger(0)

        private val factory = ThreadFactory { runnable ->
            Thread(runnable, "$name-${counter.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }

        /** 给 `submit` / `execute` / `Future` 用。 */
        val executor: ExecutorService = Executors.newFixedThreadPool(threads, factory)

        /** 给协程用，与 [executor] 共用同一批线程。 */
        val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

        /** 当前线程数（诊断用；`poolSize` 只会等于 [threads] 或更小）。 */
        val size: Int get() = (executor as? java.util.concurrent.ThreadPoolExecutor)?.poolSize ?: threads
    }

    /** 内容接口 / 账号 / 推荐 / 预取 / 播放地址。 */
    val network = Lane("hgtv-net", 4)

    /** 扇出型并发：一个调用方提交 N 个任务再 join。 */
    val fanout = Lane("hgtv-fanout", 6)

    /** 建播放器 / 枚举解码器 / 预热下一集。 */
    val player = Lane("hgtv-player", 2)

    /** 弹幕分页拉取。 */
    val danmaku = Lane("hgtv-danmaku", 1)

    /** 本地记录与缓存落盘。 */
    val disk = Lane("hgtv-disk", 1)

    /** 诊断用的一行摘要。 */
    fun describe(): String =
        "net=${network.size} fanout=${fanout.size} player=${player.size} danmaku=${danmaku.size} disk=${disk.size}"
}
