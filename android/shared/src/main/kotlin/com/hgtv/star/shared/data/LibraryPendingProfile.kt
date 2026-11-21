// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.util.Log
import com.hgtv.star.core.VendorConstants
import org.json.JSONObject

/**
 * 「换新身份」用的**候选档案**：派生出来但还没被平台接受的那一份。
 *
 * 只在 `deviceProfile` 发现老版本随机档案时生成；注册成功之后由 `commitPendingProfile`
 * 变成正式档案。返回 null 表示当前没有待换的档案（绝大多数情况下都是 null）。
 */
internal fun Library.pendingProfileImpl(): Map<String,String>? {
    val raw=identitySessionPrefs.getString("device-profile-pending","").orEmpty()
    if(raw.isBlank()) return null
    return runCatching {
        val json=JSONObject(raw)
        var map=buildMap { for(key in json.keys()) json.optString(key).takeIf { it.isNotBlank() }?.let { put(key,it) } }
            .takeIf { it["cdid"].orEmpty().isNotBlank() && it["openudid"].orEmpty().isNotBlank() }
        if(map!=null && map["openudid-source"]=="forced_random") {
            val androidId=readAndroidIdForPendingProfile()
            if(androidId!=null && androidId!=map["openudid"]) {
                map=LinkedHashMap(map).apply {
                    put("openudid",androidId)
                    put("openudid-source","android_id")
                }
                storePendingProfileImpl(
                    map,
                    identitySessionPrefs.getString("device-profile-pending-seed","").orEmpty(),
                )
            }
        }
        map
    }.getOrNull()
}

/** 把候选档案连同**它的种子**一起落盘（只在注册成功之后才会变成正式档案）。 */
internal fun Library.storePendingProfileImpl(profile: Map<String,String>,seed: String = "") {
    if(identityDiscarding) return
    val json=JSONObject(); profile.forEach { (key,value) -> json.put(key,value) }
    identitySessionPrefs.edit().putString("device-profile-pending",json.toString())
        .putString("device-profile-pending-seed",seed).apply()
    RecoverySnapshotManager.schedule(identityApp)
}

/**
 * 注册成功之后把候选档案转正（档案与种指纹一起换）。
 *
 * **必须与 `saveDeviceIdentity` 一起调用**（先这一条、再那一条）：身份是按候选档案注册出来的，
 * 只换身份不换档案，下一次启动 `deviceRegistered` 就会发现「身份与档案对不上」。
 */
internal fun Library.commitPendingProfileImpl() {
    if(identityDiscarding) return
    val pending=pendingProfileImpl() ?: return
    val seed=identitySessionPrefs.getString("device-profile-pending-seed","").orEmpty()
    val json=JSONObject(); pending.forEach { (key,value) -> json.put(key,value) }
    // `-pending-stamp` 是老版本的键（那时用指纹而不是种子），一并清掉，别留孤儿。
    val edit=identitySessionPrefs.edit().putString("device-profile",json.toString())
        .remove("device-profile-pending").remove("device-profile-pending-seed")
        .remove("device-profile-pending-stamp")
    // 种子与档案必须**一起**换：只换档案不换种子，下一次要派生时用的还是旧那一套。
    if(seed.isNotBlank()) edit.putString("device-seed",seed)
    edit.commit()
    identityMirror.put("device-profile",json.toString())
    if(seed.isNotBlank()) identityMirror.put("device-seed",seed)
    identityMirror.flush()
    identityCachedProfileStamp=null
    RecoverySnapshotManager.schedule(identityApp)
    Log.i("HGTV","Pending device profile promoted after successful registration")
}

/**
 * 注册请求该用哪一套档案：有待换的候选就用候选，否则用当前档案。
 *
 * 老版本随机档案升级时，请求必须**按候选档案**发出去，平台才会把新的 `device_id` 绑在
 * 新档案上；而其它请求（搜索 / 播放）此刻仍用旧档案，旧身份因此继续可用。
 */
internal fun Library.registrationProfileImpl(): Map<String,String> {
    if(isTvDeviceForProfile()) {
        return VendorConstants.tvProfile(seed = deviceProfile()["cdid"].orEmpty())
    }
    pendingProfileImpl()?.let { return it }
    return deviceProfile()
}

/** 有没有待转正的候选档案（见 [registrationProfileImpl]）。 */
internal fun Library.hasPendingProfileImpl(): Boolean = pendingProfileImpl()!=null
