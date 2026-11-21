// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.content.Context
import org.json.JSONObject

/**
 * 旧版 `hgtv-session.json` 到新版会话偏好的迁移。
 *
 * 旧版可能同时留下私有目录、`.hgtv`、Documents/.hgtv、Android/data 和 MediaStore
 * 五份副本；这里只取 `saved_at` 最新的一份，并只补本机缺失的键，避免覆盖升级后
 * 已经更新的值。
 */
internal object LegacyRecoveryMigration {
    private val longKeys = setOf("account-expires", "last-beat-at")

    fun merge(context: Context, store: RecoverySnapshotStore): Boolean {
        val latest = store.readLegacySessionCandidates()
            .mapNotNull(::decode)
            .maxByOrNull { it.savedAt }
            ?: return false
        val prefs = context.applicationContext
            .getSharedPreferences("native-session-v1", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        var changed = false
        val keys = latest.values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (prefs.contains(key)) continue
            val value = latest.values.opt(key) ?: continue
            when {
                key in longKeys -> {
                    val parsed = when (value) {
                        is Number -> value.toLong()
                        else -> value.toString().toLongOrNull() ?: continue
                    }
                    edit.putLong(key, parsed)
                }
                value is String -> edit.putString(key, value)
                else -> edit.putString(key, value.toString())
            }
            changed = true
        }
        if (changed) edit.commit()
        return changed
    }

    fun deleteLegacyFiles(store: RecoverySnapshotStore): Boolean =
        store.deleteLegacySessionFiles()

    private fun decode(raw: String): Snapshot? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("version", 0) != 1) return@runCatching null
        val values = root.optJSONObject("values") ?: return@runCatching null
        Snapshot(root.optLong("saved_at", 0L), values)
    }.getOrNull()

    private data class Snapshot(
        val savedAt: Long,
        val values: JSONObject,
    )
}
