// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import java.io.File
import java.security.MessageDigest

/**
 * 封面磁盘缓存：存的是**已经缩到卡片尺寸的 WebP**，不是原图。
 *
 * 为什么不是原图：海报源图是 640×854 左右的 JPEG，而卡片上的海报只有约 280×357。
 * 存原图的话每次命中都要「读 100 KB + 解 640×854 的 JPEG + 再缩一次」，
 * 弱 CPU 上一张 10~40 ms，一屏 24 张就是几百毫秒，用户滚动速度永远快于它。
 * 改成落盘前先缩到目标尺寸再编码成 WebP（一张 15~25 KB），命中时解的就是小图（2~4 ms）。
 *
 * 代价是「同一张封面在不同尺寸的槽位里会各存一份」，因此 key 里带上目标尺寸。
 * 但海报槽位在一台设备上是固定的那几种，实际条目数不会因此膨胀多少，
 * 而整份目录（几百部剧）的封面都进得了这个预算——来回滚动零网络、零大解码。
 *
 * 所有调用都跑在图片工作线程上。
 */
class ArtworkCache(private val directory: File) {
    companion object {
        /** 单条上限：目标尺寸的 WebP 远小于它，超过就是异常数据，不落盘。 */
        private const val MAX_ENTRY_BYTES = 512 * 1024

        /**
         * 总量与条数上限。
         *
         * 2026-10-17 起盘里存的是 **640 档的 webp**（请求尺寸固定 640，见 `ArtworkUrl` 的
         * 类注释），平均每张约 94 KB——不是早先那个按卡片尺寸取的 33 KB。
         * 因此 96 MiB 现在只能装约 **1000 张**（原先约 3000 张），
         * 条数上限（6000）基本不会再触发了，真正起作用的是字节数。
         *
         * 实测这个容量是够的：走到 2000 张时 `blankP50=34ms / blankP90=101ms`、
         * `diskHit=562`（回头滚已经在盘里），并没有因为清理而退化。
         * 之所以不跟着调大：磁盘缓存的作用是「回头滚不用重下」，
         * 而 1000 张已经覆盖了「一屏 + 预取窗口 + 往回滚几屏」的量；
         * 再大只是在电视那点存储上白占地方。
         */
        private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
        private const val MAX_ENTRIES = 6000

        /**
         * 清理的**低水位**：降到这个比例就停手。
         *
         * 为什么要一次多删一点：清理是「全目录 stat + 排序」，一次要几十毫秒。
         * 如果只删到刚好低于上限，那下一次写入又会超限、又得清理一次——
         * 实测这种「每写一张清理一次」会把单张耗时从 8ms 抬到 **91ms**（`totalAvg` 269ms 里
         * 有 83ms 是这个），而 `put` 是 `@Synchronized`，8 个 worker 全排在它后面。
         * 一次删到 70% 之后，接下来几百张写入都不用再扫目录。
         */
        private const val PRUNE_TARGET = 0.7

        /** [ArtworkCache.knownBytes] 的「还没数过」标记。 */
        private const val UNKNOWN = -1L
    }

    private fun file(key: String) = File(
        directory,
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    )

    /**
     * 磁盘里有没有这一条（只 stat，不读内容）。
     *
     * 给预取用：图已经在磁盘里时，预取**不必**再把它读进内存（见 `ArtworkLoader.work`）。
     */
    @Synchronized fun has(key: String): Boolean = runCatching {
        val f = file(key)
        f.isFile && f.length() in 1..MAX_ENTRY_BYTES &&
            System.currentTimeMillis() - f.lastModified() in 0..30L * 24 * 60 * 60 * 1000
    }.getOrDefault(false)

    @Synchronized fun get(key: String): ByteArray? = runCatching {
        val f = file(key)
        // 有效期拉长到 30 天：封面基本不变，早先的 7 天会让长列表「放几天回来又要重下」。
        if (!f.isFile || f.length() !in 1..MAX_ENTRY_BYTES ||
            System.currentTimeMillis() - f.lastModified() !in 0..30L * 24 * 60 * 60 * 1000) null
        else f.readBytes()
    }.getOrNull()

    /**
     * 已缓存的总字节数与条数（内存里的估算值；[UNKNOWN] 表示还没数过）。
     *
     * 为什么需要它：原先每次 [put] 都要 `listFiles()` 再排序，把整个目录 stat 一遍——
     * 一千多个文件时这一步要几十毫秒，而且它在锁里，**6 个图片线程全部排在它后面**。
     * 实测 `enc`（含这一步）均值 40ms、worker 忙碌率却只有 26%：时间都花在等锁上，
     * 表现出来就是「图排着队却下不来」。
     *
     * 现在只在「涨过上限」时才真的清理一次，平时只是加一个数。
     */
    private var knownBytes = UNKNOWN
    private var knownCount = 0

    @Synchronized fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) return
        runCatching {
            directory.mkdirs()
            val dest = file(key)
            val temp = File(directory, "${dest.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(dest)) { temp.delete(); return@runCatching }
            if (knownBytes == UNKNOWN) measure()
            else { knownBytes += bytes.size; knownCount++ }
            // 只有真的超了才扫目录。清理一次删到 [PRUNE_TARGET]，
            // 因此接下来几百张写入都不会再走到这里（见 [PRUNE_TARGET] 的说明）。
            if (knownBytes > MAX_TOTAL_BYTES || knownCount > MAX_ENTRIES) prune()
        }
    }

    /** 目录里有多少条（诊断用；没数过就现数一次）。 */
    @Synchronized fun count(): Int {
        if (knownBytes == UNKNOWN) measure()
        return knownCount
    }

    /** 数一次目录里的总量（只在第一次写入后与清理后调用）。 */
    private fun measure() {
        val files = directory.listFiles().orEmpty()
        knownCount = files.size
        knownBytes = files.sumOf { it.length() }
    }

    /**
     * 按最后修改时间从旧到新删，一直删到 [PRUNE_TARGET] 以下。
     *
     * 这是整个类里唯一的重活（全目录 stat + 排序），因此**必须少做**：
     * 删到 70% 之后，接下来几百张写入都不会再触发它。
     * 早先只删到「刚好低于上限」，结果每次写入都超限、每次都扫一遍目录——
     * 实测把单张耗时从 8ms 抬到 91ms，而 `put` 是 `@Synchronized`，
     * 8 个 worker 全堵在它后面等（那正是「前几百张好、后面退化」的直接原因）。
     */
    private fun prune() {
        val files = directory.listFiles().orEmpty().sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        var count = files.size
        val sizeFloor = (MAX_TOTAL_BYTES * PRUNE_TARGET).toLong()
        val countFloor = (MAX_ENTRIES * PRUNE_TARGET).toInt()
        for (f in files) {
            if (size <= sizeFloor && count <= countFloor) break
            val length = f.length()
            if (f.delete()) { size -= length; count-- }
        }
        knownBytes = size
        knownCount = count
    }
}
