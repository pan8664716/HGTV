// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.architecture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 把协程挂到指定车道上的几个小工具。
 *
 * 存在的意义只有一个：**让「这段活儿跑在哪条道上」在调用点上一眼可见**。
 * 直接写 `scope.launch(Dispatchers.IO)` 时，读代码的人无法判断它会不会和播放抢线程；
 * 写成 `scope.network { }` 就一目了然，而且改预算只需要动 [AppExecutors] 一处。
 */

/** 内容接口 / 账号 / 推荐 / 预取。 */
internal fun CoroutineScope.network(block: suspend CoroutineScope.() -> Unit): Job =
    launch(AppExecutors.network.dispatcher, block = block)

/** 扇出型并发（一个调用方提交 N 个任务再 join）。 */
internal fun CoroutineScope.fanout(block: suspend CoroutineScope.() -> Unit): Job =
    launch(AppExecutors.fanout.dispatcher, block = block)

/** 建播放器 / 枚举解码器 / 预热下一集。 */
internal fun CoroutineScope.player(block: suspend CoroutineScope.() -> Unit): Job =
    launch(AppExecutors.player.dispatcher, block = block)

/** 弹幕分页拉取。 */
internal fun CoroutineScope.danmaku(block: suspend CoroutineScope.() -> Unit): Job =
    launch(AppExecutors.danmaku.dispatcher, block = block)

/** 本地记录与缓存落盘。 */
internal fun CoroutineScope.disk(block: suspend CoroutineScope.() -> Unit): Job =
    launch(AppExecutors.disk.dispatcher, block = block)
