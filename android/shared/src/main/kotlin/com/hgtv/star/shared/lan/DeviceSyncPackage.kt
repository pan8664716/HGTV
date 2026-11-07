// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.lan

import android.content.Context
import com.hgtv.star.core.BackupData
import com.hgtv.star.core.LibraryBackup
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.data.LibrarySyncSessionKeys
import com.hgtv.star.shared.ui.TvTheme
import com.hgtv.star.shared.ui.TvThemeStore
import org.json.JSONObject
import kotlin.math.roundToInt

data class DeviceSyncSelection(
    val login: Boolean = true,
    val device: Boolean = true,
    val content: Boolean = true,
    val settings: Boolean = true,
) {
    val hasAny: Boolean get() = login || device || content || settings
}

data class DeviceSyncPreferences(
    val theme: String,
    val phoneUiScale: Float,
    val phoneDanmakuPercent: Int,
    val deviceForm: String,
)

data class DeviceSyncPayload(
    val library: BackupData,
    val session: Map<String, String>,
    val preferences: DeviceSyncPreferences,
    val createdAt: Long,
)

/**
 * NSD 局域网同步的完整传输包。
 *
 * 内容数据仍复用 [LibraryBackup] 的校验与合并规则；登录、设备身份和显示偏好
 * 走单独白名单，不把源码、发布文件或本机删除墓碑带过去。
 */
object DeviceSyncPackage {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val FORMAT = "hgtv.lan-sync"
    private const val VERSION = 1
    private const val PHONE_DISPLAY_PREFS = "phone-display-v1"
    private const val PHONE_UI_SCALE_KEY = "uiScale"
    private const val PHONE_UI_SCALE_MIGRATED = "hugeDefaultMigrated"
    private const val PHONE_DANMAKU_PREFS = "phone-danmaku-v1"
    private const val PHONE_DANMAKU_KEY = "sizePercent"

    fun capture(context: Context, library: Library): DeviceSyncPayload {
        val app = context.applicationContext
        val display = app.getSharedPreferences(PHONE_DISPLAY_PREFS, Context.MODE_PRIVATE)
        val danmaku = app.getSharedPreferences(PHONE_DANMAKU_PREFS, Context.MODE_PRIVATE)
        return DeviceSyncPayload(
            library = library.snapshot(),
            session = library.exportSyncSession(),
            preferences = DeviceSyncPreferences(
                theme = TvThemeStore.load(app).storedValue,
                phoneUiScale = display.getFloat(PHONE_UI_SCALE_KEY, 1.6f).coerceIn(1.3f, 1.9f),
                phoneDanmakuPercent = snapDanmakuPercent(danmaku.getInt(PHONE_DANMAKU_KEY, 100)),
                deviceForm = library.deviceForm,
            ),
            createdAt = System.currentTimeMillis(),
        )
    }

    fun encode(payload: DeviceSyncPayload): String {
        LibrarySyncSessionKeys.validate(payload.session)
        val library = JSONObject(LibraryBackup.encode(payload.library))
        val session = JSONObject().apply {
            payload.session.toSortedMap().forEach { (key, value) -> put(key, value) }
        }
        val preferences = JSONObject()
            .put("theme", payload.preferences.theme)
            .put("phoneUiScale", payload.preferences.phoneUiScale.toDouble())
            .put("phoneDanmakuPercent", payload.preferences.phoneDanmakuPercent)
            .put("deviceForm", payload.preferences.deviceForm)
        val encoded = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", payload.createdAt)
            .put("library", library)
            .put("session", session)
            .put("preferences", preferences)
            .toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "同步数据超过 4 MB" }
        return encoded
    }

    fun decode(raw: String): DeviceSyncPayload {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "同步数据超过 4 MB" }
        val root = JSONObject(raw)
        require(root.getString("format") == FORMAT && root.optInt("version", 0) == VERSION) {
            "不支持的同步数据格式或版本"
        }
        val library = LibraryBackup.decode(root.getJSONObject("library").toString())
        val sessionJson = root.getJSONObject("session")
        val session = buildMap {
            val keys = sessionJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, sessionJson.getString(key))
            }
        }
        LibrarySyncSessionKeys.validate(session)
        val preferencesJson = root.getJSONObject("preferences")
        val scale = preferencesJson.get("phoneUiScale").let {
            require(it is Number && it.toDouble().isFinite()) { "手机字号无效" }
            it.toFloat().coerceIn(1.3f, 1.9f)
        }
        val danmaku = preferencesJson.get("phoneDanmakuPercent").let {
            require(it is Number) { "手机弹幕字号无效" }
            snapDanmakuPercent(it.toInt())
        }
        val deviceForm = preferencesJson.getString("deviceForm")
            .takeIf { it in setOf("auto", "phone", "tablet", "tv") } ?: "auto"
        return DeviceSyncPayload(
            library = library,
            session = session,
            preferences = DeviceSyncPreferences(
                theme = TvTheme.of(preferencesJson.getString("theme")).storedValue,
                phoneUiScale = scale,
                phoneDanmakuPercent = danmaku,
                deviceForm = deviceForm,
            ),
            createdAt = root.optLong("createdAt", 0L),
        )
    }

    /**
     * 按用户选择应用同步包。返回前等待内容、设置与会话镜像全部落盘，
     * 调用方随后重启进程即可让登录态、主题和设备类型一次性生效。
     */
    fun restore(
        context: Context,
        library: Library,
        payload: DeviceSyncPayload,
        selection: DeviceSyncSelection,
    ): Boolean {
        require(selection.hasAny) { "至少选择一类同步内容" }
        val selectedSessionKeys=buildSet {
            if(selection.login) addAll(LibrarySyncSessionKeys.LOGIN)
            if(selection.device) addAll(LibrarySyncSessionKeys.DEVICE)
        }
        if(selectedSessionKeys.isNotEmpty()) {
            library.importSyncSession(
                values=payload.session.filterKeys { it in selectedSessionKeys },
                selectedKeys=selectedSessionKeys,
            )
        }
        if(selection.content || selection.settings) {
            val incoming=if(selection.content) {
                payload.library
            } else {
                payload.library.copy(
                    favorites=emptyList(),
                    history=emptyList(),
                    watched=emptySet(),
                    searches=emptyList(),
                    later=emptyList(),
                    hidden=emptySet(),
                    historyDeleted=emptySet(),
                )
            }
            library.restore(incoming, restoreSettings=selection.settings)
        }
        val app = context.applicationContext
        val themeSaved = !selection.settings || TvThemeStore.saveNow(app, TvTheme.of(payload.preferences.theme))
        val displaySaved = !selection.settings || app.getSharedPreferences(PHONE_DISPLAY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(PHONE_UI_SCALE_KEY, payload.preferences.phoneUiScale.coerceIn(1.3f, 1.9f))
            .putBoolean(PHONE_UI_SCALE_MIGRATED, true)
            .commit()
        val danmakuSaved = !selection.settings || app.getSharedPreferences(PHONE_DANMAKU_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(PHONE_DANMAKU_KEY, snapDanmakuPercent(payload.preferences.phoneDanmakuPercent))
            .commit()
        val formSaved = !selection.device || library.setDeviceFormNow(payload.preferences.deviceForm)
        return themeSaved && displaySaved && danmakuSaved && formSaved && library.flushBlocking()
    }

    private fun snapDanmakuPercent(value: Int): Int {
        val step = ((value - 50f) / 5f).roundToInt() * 5 + 50
        return step.coerceIn(50, 150)
    }
}
