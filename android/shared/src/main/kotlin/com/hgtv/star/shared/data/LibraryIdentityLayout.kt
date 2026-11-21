// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.util.Log
import org.json.JSONObject

/**
 * 一次性把旧版设备身份归档，并清空当前主设备身份。
 *
 * 归档而不是覆盖：旧设备以后仍可从 `legacy-*` 找回；新版主设备则按全新档案注册。
 * 登录后的账号设备由 `account-device` / `account-cookie` 保存；它是当前主身份，
 * 但不会覆盖未登录时使用的主设备档案。
 */
internal fun Library.ensureIdentityLayoutImpl() {
    if(identityLayoutInitialized || identityDiscarding) return
    synchronized(this) {
        if(identityLayoutInitialized || identityDiscarding) return
        val alreadySplit=identitySessionPrefs.contains(identityLayoutKeyValue) ||
            identityMirror.string(identityLayoutKeyValue)=="1"
        if(!alreadySplit) {
            val edit=identitySessionPrefs.edit()
            // 旧版本把登录设备与主设备共用。升级时先把旧设备 Cookie 并进已登录账号，
            // 否则切到独立主设备后，账号心跳会少掉 `install_id` / `ttreq`。
            val accountCookie=identitySessionPrefs.getString("account-cookie",null)
                ?: identityMirror.string("account-cookie").orEmpty()
            val legacyCookies=identitySessionPrefs.getString("registered-cookies",null)
                ?: identityMirror.string("registered-cookies").orEmpty()
            if(accountCookie.contains("sessionid=") && legacyCookies.isNotBlank()) {
                runCatching {
                    val merged=com.hgtv.star.core.session.CookieRecord.headerToMap(accountCookie)
                    val json=JSONObject(legacyCookies)
                    for(key in json.keys()) {
                        json.optString(key).takeIf { it.isNotBlank() }?.let { merged[key]=it }
                    }
                    val header=com.hgtv.star.core.session.CookieRecord.mapToHeader(merged)
                    edit.putString("account-cookie",header)
                    identityMirror.put("account-cookie",header)
                }
            }
            legacyIdentityKeysValue.forEach { key ->
                val value=identitySessionPrefs.all[key]?.toString() ?: identityMirror.string(key)
                if(value!=null) {
                    edit.putString("legacy-$key",value)
                    identityMirror.put("legacy-$key",value)
                }
                edit.remove(key)
                identityMirror.remove(key)
            }
            // 新主设备使用独立档案：物理机型 / 系统不变，cdid / openudid / clientudid
            // 重新生成，避免平台把新主设备直接认回旧设备。
            val fresh=LinkedHashMap(buildOfficialProfileForIdentityLayout(forceRandomOpenudid=true)).apply {
                put("openudid-source","primary_random")
            }
            val freshJson=JSONObject().apply {
                fresh.forEach { (key,value) -> put(key,value) }
            }.toString()
            edit.putString("device-profile",freshJson)
            identityMirror.put("device-profile",freshJson)
            edit.putString(identityLayoutKeyValue,"1").commit()
            identityMirror.put(identityLayoutKeyValue,"1")
            identityMirror.flush()
            identityCachedProfileStamp=null
            RecoverySnapshotManager.schedule(identityApp)
            Log.i("HGTV","Legacy device identity archived; new main identity will be registered")
        } else if(!identitySessionPrefs.contains(identityLayoutKeyValue)) {
            identitySessionPrefs.edit().putString(identityLayoutKeyValue,"1").commit()
            RecoverySnapshotManager.schedule(identityApp)
        }
        // 手动 device_id 已下线：升级时把旧值和旧镜像一起清掉。
        if(identitySessionPrefs.contains("manual-device-id")) {
            identitySessionPrefs.edit().remove("manual-device-id").apply()
            identityMirror.remove("manual-device-id")
            RecoverySnapshotManager.schedule(identityApp)
        }
        identityLayoutInitialized=true
    }
}
