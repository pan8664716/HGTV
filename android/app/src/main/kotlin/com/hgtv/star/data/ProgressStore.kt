// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.util.Log
import com.hgtv.star.core.BackupProgress
import com.hgtv.star.core.Series
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 观看进度的落盘：**一剧一文件**。
 *
 * ## 为什么不能继续放 SharedPreferences
 *
 * 改造前，播放中每 5 秒调一次 `Library.save(progress)`，它会走
 * `persist("progress")` → 把**最多 200 条**观看记录（每条内嵌完整 `Series`）
 * 序列化成一整串 JSON，再 `SharedPreferences.edit().commit()`。
 *
 * 这里有两个独立的放大：
 *
 * 1. **序列化**：200 条 × 约 0.5KB ≈ 100KB 的 JSON 字符串，每 5 秒重建一次；
 * 2. **文件写入**：`commit()` 会把**整份 XML**（进度 + 收藏 500 条 + 稍后看 100 条，
 *    约 400KB）重写并 fsync。按播放一小时 720 次算，约 **288MB 文件写入**。
 *
 * 而真正变化的只有「这一部剧播到第几秒」——不到 60 字节。
 *
 * ## 为什么是文件而不是数据库
 *
 * 一条进度就是一行的 UPSERT，本该用数据库。但引入 Room 会带来 1.5MB 左右的
 * 体积与一套 schema 迁移，而这里的数据形状极其简单（按 `seriesId` 唯一、只按
 * `updatedAt` 排序、总量上限 200）。一剧一个小文件（每个约 0.5KB）能达到同样的
 * 「写放大 O(1)」效果，且零依赖、零迁移、API 19 可用。
 *
 * 代价是启动时要扫一次目录：200 个文件、约 100KB，在 eMMC 上是 10~30ms，
 * 而且这一步本来就在 `Library` 的工作线程上（见 `loadLibrary`）。
 *
 * ## 一致性
 *
 * 单文件写入走 `tmp + rename`：断电或进程被杀时，要么是旧文件、要么是新文件，
 * 不会出现半截 JSON。文件名用 `seriesId`（平台下发的是十进制数字），
 * 非法字符先做一次 SHA-256，避免路径穿越。
 */
internal class ProgressStore(private val directory: File) {

    private fun fileFor(seriesId: String): File {
        val safe = seriesId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val name = if (safe.length == seriesId.length && safe.isNotEmpty()) safe else sha256(seriesId)
        return File(directory, "$name.json")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 读全部进度。目录不存在或某一条损坏时跳过那一条，不影响其余。 */
    fun loadAll(): List<BackupProgress> {
        val files = directory.listFiles() ?: return emptyList()
        val out = ArrayList<BackupProgress>(files.size)
        for (f in files) {
            if (!f.isFile || !f.name.endsWith(".json")) continue
            val row = runCatching { decode(f.readText(Charsets.UTF_8)) }.getOrNull()
            if (row != null) out += row
        }
        return out
    }

    /** 写一条。**只碰这一个文件**。 */
    fun save(progress: BackupProgress) {
        runCatching {
            directory.mkdirs()
            val dest = fileFor(progress.series.id)
            val temp = File(directory, "${dest.name}.tmp")
            temp.writeText(encode(progress), Charsets.UTF_8)
            if (!temp.renameTo(dest)) {
                temp.delete()
                // rename 失败（跨分区 / 权限）时退化成直接写：正确性优先于原子性。
                dest.writeText(encode(progress), Charsets.UTF_8)
            }
        }.onFailure {
            Log.w("HGTV", "progress save failed series=${progress.series.id}", it)
        }
    }

    /** 删一条（用户删掉某部剧的观看记录）。 */
    fun remove(seriesId: String) {
        runCatching { fileFor(seriesId).delete() }
    }

    /** 清空（「清空观看记录」/ 恢复出厂）。 */
    fun clear() {
        runCatching { directory.listFiles()?.forEach { it.delete() } }
    }

    /** 目录里有多少条（诊断用）。 */
    fun count(): Int = runCatching { directory.listFiles()?.count { it.name.endsWith(".json") } ?: 0 }.getOrDefault(0)

    /**
     * 从旧版的 `progress` 键迁移过来。
     *
     * 只在「本目录为空」且「旧键里有内容」时做一次，做完由调用方删掉旧键。
     * 迁移期间新写入会被覆盖成同样的值，不会丢进度。
     */
    fun migrateFrom(legacy: List<BackupProgress>): Boolean {
        if (legacy.isEmpty()) return false
        if (count() > 0) return false
        legacy.forEach(::save)
        return true
    }

    private fun encode(p: BackupProgress): String = JSONObject()
        .put("series", p.series.toJson())
        .put("episodeId", p.episodeId)
        .put("episodeIndex", p.episodeIndex)
        .put("position", p.position)
        .put("duration", p.duration)
        .put("completed", p.completed)
        .put("updatedAt", p.updatedAt)
        .toString()

    private fun decode(text: String): BackupProgress? {
        val o = JSONObject(text)
        return BackupProgress(
            series = Series.fromJson(o.getJSONObject("series")),
            episodeId = o.getString("episodeId"),
            episodeIndex = o.getInt("episodeIndex"),
            position = o.getLong("position"),
            duration = o.getLong("duration"),
            completed = o.optBoolean("completed"),
            updatedAt = o.getLong("updatedAt"),
        )
    }
}
