// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 卸载重装恢复快照。
 *
 * 快照刻意保持明文：产品要求重装后恢复登录、设备 ID 和全部本机配置。
 * 格式带 schemaVersion、appVersionCode 和 SHA-256，避免把半份 JSON 当成有效快照。
 */
data class RecoverySnapshot(
    val createdAt: Long,
    val appVersionCode: Int,
    val prefs: Map<String, Map<String, Any>>,
    val files: Map<String, String>,
)

class NewerRecoverySnapshotException :
    IllegalStateException("恢复快照来自更高版本")

object RecoverySnapshotCodec {
    const val SCHEMA_VERSION = 1

    fun encode(snapshot: RecoverySnapshot): String {
        val prefs = JSONObject()
        snapshot.prefs.toSortedMap().forEach { (fileName, values) ->
            val file = JSONObject()
            values.toSortedMap().forEach { (key, value) ->
                file.put(key, encodeValue(value))
            }
            prefs.put(fileName, file)
        }
        val files = JSONObject()
        snapshot.files.toSortedMap().forEach { (path, value) ->
            files.put(path, value)
        }
        val payload = JSONObject()
            .put("prefs", prefs)
            .put("files", files)
            .toString()
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("appVersionCode", snapshot.appVersionCode)
            .put("createdAt", snapshot.createdAt)
            .put("payload", payload)
            .put("sha256", sha256(payload))
            .toString()
    }

    fun decode(raw: String): RecoverySnapshot {
        val root = JSONObject(raw)
        val schema = root.getInt("schemaVersion")
        if (schema > SCHEMA_VERSION) throw NewerRecoverySnapshotException()
        require(schema == SCHEMA_VERSION) { "不支持的恢复快照版本" }
        val payload = root.getString("payload")
        require(sha256(payload) == root.getString("sha256")) { "恢复快照校验失败" }
        val body = JSONObject(payload)
        val prefsJson = body.getJSONObject("prefs")
        val prefs = buildMap {
            val names = prefsJson.keys()
            while (names.hasNext()) {
                val fileName = names.next()
                val file = prefsJson.getJSONObject(fileName)
                val values = buildMap {
                    val keys = file.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, decodeValue(file.getJSONObject(key)))
                    }
                }
                put(fileName, values)
            }
        }
        val filesJson = body.getJSONObject("files")
        val files = buildMap {
            val paths = filesJson.keys()
            while (paths.hasNext()) {
                val path = paths.next()
                put(path, filesJson.getString(path))
            }
        }
        return RecoverySnapshot(
            createdAt = root.optLong("createdAt", 0L),
            appVersionCode = root.optInt("appVersionCode", 0),
            prefs = prefs,
            files = files,
        )
    }

    private fun encodeValue(value: Any): JSONObject = when (value) {
        is String -> JSONObject().put("type", "string").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value.toString())
        is Float -> JSONObject().put("type", "float").put("value", value.toString())
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Set<*> -> JSONObject().put("type", "string_set").put(
            "value",
            JSONArray(value.filterIsInstance<String>().sorted()),
        )
        else -> error("不支持的偏好类型: ${value.javaClass.name}")
    }

    private fun decodeValue(value: JSONObject): Any = when (value.getString("type")) {
        "string" -> value.getString("value")
        "int" -> value.getInt("value")
        "long" -> value.getString("value").toLong()
        "float" -> value.getString("value").toFloat()
        "boolean" -> value.getBoolean("value")
        "string_set" -> {
            val array = value.getJSONArray("value")
            buildSet {
                for (index in 0 until array.length()) add(array.getString(index))
            }
        }
        else -> error("不支持的偏好类型")
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val hex = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val valueAt = byte.toInt() and 0xFF
            hex[index * 2] = digits[valueAt ushr 4]
            hex[index * 2 + 1] = digits[valueAt and 0x0F]
        }
        return String(hex)
    }
}
