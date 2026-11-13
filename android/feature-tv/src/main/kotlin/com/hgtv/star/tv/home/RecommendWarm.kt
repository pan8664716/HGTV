// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

/**
 * 一次在途的「相邻一部预热」。
 *
 * 带自己的取消域 [scope]：取消的时候不只是丢弃结果，连它正在拉的那 1MB 也一起掐掉。
 * 手机上一划一部，被作废的预热很多；只丢结果的话这些连接还会把带宽占满，
 * 正在放的那一部反而更卡。
 */
class RecommendWarm {
    @Volatile internal var future: java.util.concurrent.Future<*>?=null
    val scope=com.hgtv.star.core.RequestScope()
    val startedAt=android.os.SystemClock.elapsedRealtime()
}
