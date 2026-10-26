// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.content.Context
import android.content.SharedPreferences
import com.hgtv.star.core.AccountCredentialsParser
import com.hgtv.star.core.session.CookieRecord
import com.hgtv.star.core.session.SessionState
import org.json.JSONObject

/**
 * [SessionState] 的落盘实现。**读写的就是工程里已有的 `native-session-v1`**，不新建 SP。
 *
 * | 键 | 谁写 | 说明 |
 * |---|---|---|
 * | `account-cookie` | 本类 / `Library.saveAccount` | 扁平 Cookie 串 |
 * | `account-device` | `Library.saveAccountDevice` | 设备档案 JSON（不动） |
 * | `account-expires` | 本类 / `Library.saveAccount` | `sid_guard` 推算的过期**秒** |
 * | `session-token` | 本类 / `Library.saveAccount` | 账号会话的 `X-Tt-Token` |
 * | `ts-sign` | 本类 | 响应头 `bd-ticket-guard-sign-res-static-ts-sign`，可空 |
 * | `first-beat` | 本类 | 仅首次 `true`，成功 beat 后置 `false` |
 * | `registered-cookies` | 本类 | 设备注册下发的 cookie（JSON） |
 *
 * 为什么复用而不是新建 SP：`Library.SessionMirror` 已按 `MIRROR_KEYS` 把这份 SP 镜像到
 * `/sdcard/.hgtv/`，卸载重装后靠它认回设备与会话。新开一个 SP 等于让镜像少一半内容。
 */
class AppSessionStore(
    context: Context,
    /** 外部镜像写入（`Library.mirrorSession`）；为空则只写本机。 */
    private val mirror: (String,String?) -> Unit = { key,value -> SessionBootstrap.mirror?.invoke(key,value) }
) : SessionState {
    /**
     * 正在「清空应用缓存」：**所有写盘一律丢弃**。
     *
     * 这一类的写入口（[saveToken] / [mergeSetCookie] / [replaceCookie] / [saveDeviceCookies] /
     * [firstBeat] / [lastBeatAtMs]）与 `Library` 那两个队列不同，是**直写 SP** 的：
     * 心跳与 `SessionInterceptor` 都在网络线程上调它们。删完到杀进程之间那约 1 秒里
     * 命中一次，刚清掉的 `native-session-v1.xml` 就会被内存里那份 SP 实例原样写回来——
     * 结果是「恢复出厂之后还是登录着」。见 `LocalReset.resetting`。
     */
    private fun discarding(): Boolean = com.hgtv.star.data.LocalReset.resetting
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME,Context.MODE_PRIVATE)

    override val token: String
        get() = sp.getString(K_TOKEN,"").orEmpty().trim()

    override val cookieHeader: String
        get() = sp.getString(K_COOKIE,"").orEmpty()

    override val deviceCookies: Map<String,String>
        get() {
            val raw = sp.getString(K_REGISTERED_COOKIES,"").orEmpty()
            if(raw.isBlank()) return emptyMap()
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
            return buildMap {
                for(key in json.keys()) {
                    val value = json.optString(key).trim()
                    if(value.isNotEmpty()) put(key,value)
                }
            }
        }

    /** 真机设备档案：`account-device` 里的 JSON（由 `Library.saveAccountDevice` 写入）。 */
    override val deviceProfile: Map<String,String>
        get() {
            val raw = sp.getString(K_DEVICE_PROFILE,"").orEmpty()
            if(raw.isBlank()) return emptyMap()
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
            return buildMap {
                for(key in json.keys()) {
                    val value = json.optString(key).trim()
                    if(value.isNotEmpty()) put(key,value)
                }
            }
        }

    override val storeRegion: String
        get() = CookieRecord.value(cookieHeader,"store-region").ifBlank {
            deviceCookies["store-region"].orEmpty().ifBlank { "cn" }
        }

    /**
     * 会话到期时刻。读的就是 `Library` 一直在维护的 `account-expires`（**秒**），
     * 换算成毫秒返回；没有这一项时是 0（本机不知道）。
     */
    override val expiresAtMs: Long
        get() = sp.getLong(K_EXPIRES,0L).takeIf { it>0L }?.times(1000L) ?: 0L

    /**
     * 写 token。**空值 / 占位符直接忽略**（对齐原版：`change.token` 视为未登录），
     * 否则一次异常响应就能把好会话覆盖成空。
     */
    override fun saveToken(token: String,tsSign: String): Boolean {
        if(discarding()) return false
        val clean = token.trim()
        if(clean.isEmpty() || clean==TOKEN_PLACEHOLDER) return false
        if(clean==this.token && tsSign.trim()==sp.getString(K_TS_SIGN,"").orEmpty()) return false
        val edit = sp.edit().putString(K_TOKEN,clean)
        if(tsSign.isNotBlank()) edit.putString(K_TS_SIGN,tsSign.trim())
        edit.apply()
        mirror(K_TOKEN,clean)
        if(tsSign.isNotBlank()) mirror(K_TS_SIGN,tsSign.trim())
        return true
    }

    /**
     * 合并 `Set-Cookie`：会话响应里 `sid_guard` 会续期，必须跟着更新 `account-expires`。
     *
     * **只收会话凭据那一族**（见 [SESSION_COOKIE_KEYS]）：拦截器会把每个会话 host 的
     * `Set-Cookie` 都送进来，匿名设备注册下发的 `install_id` / `ttreq` 也在里面。
     * 全部收下的话，`account-cookie` 会被这些设备 cookie 填满，于是
     * `AccountCredentials.configured`（判据是 cookie 非空）把匿名状态误判成已登录——
     * 「我的」页会显示一个假账号，所有账号接口再静默失败。
     */
    override fun mergeSetCookie(setCookies: List<String>): Boolean {
        if(discarding()) return false
        if(setCookies.isEmpty()) return false
        val wanted = setCookies.filter { raw ->
            val name = raw.substringBefore('=').trim().lowercase()
            name in SESSION_COOKIE_KEYS
        }
        if(wanted.isEmpty()) return false
        val before = cookieHeader
        // 还没有会话时，只认能**证明**是会话的那几条（`sessionid` 那一族）。
        // 出码 / 注册阶段平台会下发 `passport_csrf_token`、`n_mh` 这类伴随 cookie，
        // 单独把它们写进 `account-cookie` 会让 `AccountCredentials.configured`
        // （判据是 cookie 非空）把匿名状态判成「已登录」。
        if(!before.contains("sessionid=") && wanted.none { it.startsWith("sessionid=") }) return false
        val merged = CookieRecord.merge(before,wanted)
        if(merged==before) return false
        // `sessionid` 变了 = 换了一份会话：上一份的 token 必须作废。
        // 平台对「token 与 cookie 不同源」不是忽略 token，而是把整份会话降级成匿名设备
        // （实测 ≥99 字符且内嵌别的 sessionid → 返回「用户20114780」式临时账号），
        // 留着它下一次请求就会「登录着却查不到任何数据」。
        val beforeSid = CookieRecord.value(before,"sessionid")
        val mergedSid = CookieRecord.value(merged,"sessionid")
        if(beforeSid.isNotBlank() && mergedSid.isNotBlank() && beforeSid!=mergedSid) {
            sp.edit().remove(K_TOKEN).remove(K_TS_SIGN).apply()
            mirror(K_TOKEN,null)
            mirror(K_TS_SIGN,null)
        }
        val expires = AccountCredentialsParser.expiryOf(CookieRecord.headerToMap(merged))
        val edit = sp.edit().putString(K_COOKIE,merged)
        if(expires>0L) edit.putLong(K_EXPIRES,expires)
        edit.apply()
        mirror(K_COOKIE,merged)
        if(expires>0L) mirror(K_EXPIRES,expires.toString())
        return true
    }

    /** 登录成功 / 导入 `cookieStore.xml` 时用整段串覆盖。 */
    fun replaceCookie(header: String): Boolean {
        if(discarding()) return false
        val clean = header.trim()
        if(clean.isEmpty() || clean==cookieHeader) return false
        val expires = AccountCredentialsParser.expiryOf(CookieRecord.headerToMap(clean))
        val edit = sp.edit().putString(K_COOKIE,clean)
        if(expires>0L) edit.putLong(K_EXPIRES,expires)
        edit.apply()
        mirror(K_COOKIE,clean)
        if(expires>0L) mirror(K_EXPIRES,expires.toString())
        return true
    }

    /** 设备注册成功后把 `install_id` / `ttreq` / `store-region` 存下来（匿名时也要带）。 */
    override fun saveDeviceCookies(cookies: Map<String,String>) {
        if(discarding()) return
        val clean = cookies.filterValues { it.isNotBlank() }
        if(clean.isEmpty()) return
        val json = JSONObject().apply { clean.forEach { (key,value) -> put(key,value) } }.toString()
        sp.edit().putString(K_REGISTERED_COOKIES,json).apply()
        mirror(K_REGISTERED_COOKIES,json)
    }

    /**
     * 存成字符串 `"true"/"false"` 而不是 Boolean，是为了迁就 `Library.restoreFromMirror`：
     * 它把外部镜像里除 `account-expires` 以外的键**一律按字符串**写回 SP，
     * 用 `getBoolean` 读会永远拿到默认值 `true`，于是每次冷启动都发一遍 `first_beat=true`。
     */
    override var firstBeat: Boolean
        get() = sp.getString(K_FIRST_BEAT,"").orEmpty().ifBlank { "true" }.toBoolean()
        set(value) {
            if(discarding()) return
            sp.edit().putString(K_FIRST_BEAT,value.toString()).apply()
        }

    /**
     * 上一次成功心跳的时刻。存 `Long`（与 `account-expires` 同一个特判口径），
     * 只有 `0` 表示「还没打过」——那时 [SessionKeeper] 会在开应用时补一发。
     */
    override var lastBeatAtMs: Long
        get() = sp.getLong(K_LAST_BEAT,0L)
        set(value) {
            if(discarding()) return
            // 10 分钟循环每打一次都会写这里，但落盘 + 镜像是一笔磁盘写：一分钟内的重复
            // 直接跳过（判据只用「多久以前」，秒级精度没有意义）。
            val previous = sp.getLong(K_LAST_BEAT,0L)
            if(value>previous && value-previous<60_000L) return
            sp.edit().putLong(K_LAST_BEAT,value).apply()
            mirror(K_LAST_BEAT,value.toString())
        }

    /** 只清会话，不动设备身份（`registered-*`）与设备档案。 */
    override fun clearSession() {
        if(discarding()) return
        sp.edit().remove(K_TOKEN).remove(K_TS_SIGN).remove(K_COOKIE).remove(K_EXPIRES).remove(K_LAST_BEAT).apply()
        mirror(K_TOKEN,null)
        mirror(K_TS_SIGN,null)
        // 退出登录 **必须**把 cookie 从镜像里也抹掉，否则下次启动会把刚退掉的账号救回来
        mirror(K_COOKIE,null)
        mirror(K_EXPIRES,null)
        mirror(K_LAST_BEAT,null)
        // `first-beat` 刻意保留：重登不代表「新设备首刷」
    }

    companion object {
        /** 与 `Library.sessionPrefs` 同一份文件。 */
        const val SP_NAME = "native-session-v1"
        /**
         * 账号会话的 token。
         *
         * 为什么不复用 `x-tt-token`：那个键是「搜索登录态」的用户设置（`Library.searchToken`），
         * 由用户在设置页手填，退出登录不该把它一起抹掉。
         */
        const val K_TOKEN = "session-token"
        const val K_TS_SIGN = "ts-sign"
        const val K_FIRST_BEAT = "first-beat"
        const val K_COOKIE = "account-cookie"
        const val K_EXPIRES = "account-expires"
        /** 上一次成功心跳的时刻（epoch 毫秒）。开应用时据此决定要不要补一发。 */
        const val K_LAST_BEAT = "last-beat-at"
        const val K_REGISTERED_COOKIES = "registered-cookies"
        /** 与 `Library` 的 `account-device` 同一个键，只读。 */
        const val K_DEVICE_PROFILE = "account-device"
        const val TOKEN_PLACEHOLDER = "change.token"
        /**
         * 会话凭据那一族（对齐原版 `WebCookieSecurityConfig` 的清单）。
         *
         * 设备侧的 cookie（`install_id` / `ttreq` / `store-region`）**不在这里**：
         * 它们走 `saveDeviceCookies`，落的是 `registered-cookies` 那个键。
         */
        val SESSION_COOKIE_KEYS = setOf(
            "sessionid","sessionid_ss","sid_tt","sid_guard","uid_tt","uid_tt_ss",
            "d_ticket","session_tlb_tag","odin_tt","n_mh","multi_sids"
        )
    }
}
