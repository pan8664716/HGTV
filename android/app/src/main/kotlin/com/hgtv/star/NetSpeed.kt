// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock

/**
 * 调试面板上的「网速」一行：本进程**当前**的收发速率（`↓1.2M/s ↑8K/s`）。
 *
 * ## 为什么要量这一项
 *
 * 「卡」到底卡在哪儿，最容易分错的两头就是 **解码** 与 **网络**：画面糊、花屏是码率不够，
 * 转圈很久是下行掉到几十 K，而这两者在面板上原本都只表现为「buffering」。
 * 把实际速率摆在旁边，一眼就能分清「这条 CDN 只给了 200K」和「机器解不动 1080」。
 *
 * ## 为什么用 TrafficStats
 *
 * `TrafficStats.getUidRxBytes()` 统计的是**本进程**走过的字节数：视频、接口、图片都在里面，
 * 与「用户感觉到的下载速度」是同一件事。备选方案 `ConnectivityManager.getLinkDownstream-
 * BandwidthKbps()` 报的是**链路标称能力**（电视盒子上常直接返回 0 或 96K，没有意义），
 * 因此不用它。
 *
 * ## 开销
 *
 * 一次采样是两次静态调用读内核计数器，没有 IO、没有 Binder；只在调试面板打开时、
 * 跟着性能采样（[PerfStats.INTERVAL_MS]）一起跑，而且跑在 io 线程上。
 */
internal object NetSpeed {
    /** 面板上那一行的值；第一轮没有上一次可比，显示「采样中…」。 */
    @Volatile var summary: String=""

    private var lastRx=0L
    private var lastTx=0L
    private var lastAt=0L

    /**
     * 采一次。**只能在 io 线程调用**（与 [PerfStats.sample] 同一个任务里）。
     *
     * 速率按两次采样之间的**实测时长**算，不按名义间隔：io 线程可能被别的任务占住，
     * 用固定 2 秒去除会把瞬时速率算小一半。
     */
    @Synchronized fun sample(): String {
        val rx=rxBytes()
        val tx=txBytes()
        // 少数设备 / 内核不支持统计：明确写「-」，别一直停在「采样中…」让人以为在等。
        if(rx<0L || tx<0L) { summary="-"; return "-" }
        val now=SystemClock.elapsedRealtime()
        val prevAt=lastAt
        val prevRx=lastRx
        val prevTx=lastTx
        lastRx=rx; lastTx=tx; lastAt=now
        // 第一轮没有基线；计数器被清零（换机 / 极少数 ROM 重启统计）时也算没有基线。
        if(prevAt<=0L || now<=prevAt || rx<prevRx || tx<prevTx) return ""
        val seconds=(now-prevAt)/1000.0
        val down=(rx-prevRx)/seconds
        val up=(tx-prevTx)/seconds
        val text="↓"+rateText(down)+" ↑"+rateText(up)
        summary=text
        return text
    }

    /**
     * 本进程的累计收发字节数。
     *
     * 先按 uid 取（只算自己）；`TrafficStats` 在某些盒子上对 uid 查询返回
     * `UNSUPPORTED`，那时退回整机总量——读数会包含别的进程，但方向与量级仍然有用。
     */
    private fun rxBytes(): Long = TrafficStats.getUidRxBytes(Process.myUid())
        .takeIf { it!=TrafficStats.UNSUPPORTED.toLong() } ?: TrafficStats.getTotalRxBytes()

    private fun txBytes(): Long = TrafficStats.getUidTxBytes(Process.myUid())
        .takeIf { it!=TrafficStats.UNSUPPORTED.toLong() } ?: TrafficStats.getTotalTxBytes()

    /**
     * 每秒多少字节的写法：`820B/s` / `240K/s` / `1.2M/s`。
     *
     * 千进位用 1024（与 `TrafficStats`、与运营口头习惯一致）。
     * 小数只在 M 这一档保留一位：`240.0K/s` 那种写法又长又假精确。
     */
    private fun rateText(bytesPerSecond: Double): String = when {
        bytesPerSecond<1024.0 -> "${bytesPerSecond.toInt()}B/s"
        bytesPerSecond<1024.0*1024 -> "${(bytesPerSecond/1024).toInt()}K/s"
        else -> "%.1fM/s".format(bytesPerSecond/(1024.0*1024))
    }

    /** 面板关掉 / 重新打开：清基线，下次打开第一轮重新起算（与 [PerfStats.reset] 同一时机）。 */
    fun reset() {
        summary=""
        lastRx=0L; lastTx=0L; lastAt=0L
    }

    /** 仅供测试。 */
    fun resetForTest() = reset()

    /**
     * 播放画面「网速」那一项的读数：每秒调一次（与 tick 同一节奏）。
     *
     * 第一轮没有基线、或设备不支持统计时给 `↓ --`，不显示「采样中…」——
     * 画面上那一格很小，写四个字会把整行撑宽。
     */
    fun line(): String = sample().ifBlank { "↓ --" }
}
