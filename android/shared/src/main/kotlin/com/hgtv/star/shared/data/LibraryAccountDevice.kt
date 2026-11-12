// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import org.json.JSONObject

/**
 * 设备档案：**只能从设备上读**的那批参数（`device_id` / 机型 / 系统版本 / cdid …）。
 *
 * 存成一条 JSON，字段名与 `tools/login-lab/dump_device.py` 产出的
 * `device_profile.json` 一致，导入那个文件即可整份灌进来。
 */
internal fun Library.accountDeviceImpl(): Map<String,String> {
    val raw = identitySessionPrefs.getString("account-device", "").orEmpty()
    if (raw.isBlank()) return emptyMap()
    return runCatching {
        val json = JSONObject(raw)
        buildMap { for (key in json.keys()) json.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) } }
    }.getOrDefault(emptyMap())
}

internal fun Library.saveAccountDeviceImpl(device: Map<String,String>) {
    if(identityDiscarding) return
    val clean = device.filterValues { it.isNotBlank() }
    if (clean.isEmpty()) return
    val json = JSONObject()
    clean.forEach { (key, value) -> json.put(key, value) }
    identitySessionPrefs.edit().putString("account-device", json.toString()).apply()
    // 这份档案是**写接口一致性判定**用的（平台拿它比对请求头里的机型 / cdid 那一套），
    // 重装后会话能不能真的用起来就看它有没有跟着回来，所以一并镜像。
    identityMirror.put("account-device",json.toString())
}
