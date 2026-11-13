// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.OfficialDeviceIds

/**
 * 本机真机设备档案。已有官方格式档案时只读；旧格式或首次使用才生成并同步落盘。
 */
internal fun Library.deviceProfileImpl(): Map<String,String> {
    ensureIdentityLayoutForDeviceProfile()
    restoreFromMirrorForRegistrationCookies()
    if(identityDiscarding) return emptyMap()
    val stored=identitySessionPrefs.getString("device-profile","").orEmpty()
    if(stored.isNotBlank()) {
        var map=runCatching {
            val json=org.json.JSONObject(stored)
            buildMap {
                for(key in json.keys()) {
                    json.optString(key).takeIf { it.isNotBlank() }?.let { put(key,it) }
                }
            }
        }.getOrNull()
        if(map!=null &&
            map["profile-format"]=="official-v1" &&
            map["cdid"].orEmpty().isNotBlank() &&
            map["openudid"].orEmpty().isNotBlank() &&
            map["clientudid"].orEmpty().isNotBlank()
        ) {
            val source=map["openudid-source"].orEmpty()
            if(source!="android_id" && source!="primary_random") {
                val androidId=readAndroidIdImpl()
                if(androidId!=null && androidId!=map["openudid"]) {
                    map=LinkedHashMap(map).apply {
                        put("openudid",androidId)
                        put("openudid-source","android_id")
                    }
                    val upgraded=org.json.JSONObject()
                    map.forEach { (key,value) -> upgraded.put(key,value) }
                    val json=upgraded.toString()
                    identitySessionPrefs.edit().putString("device-profile",json).commit()
                    identityMirror.put("device-profile",json)
                    identityMirror.flush()
                    identityCachedProfileStamp=null
                    android.util.Log.i("HGTV","Device openudid upgraded to Android ID")
                }
            }
            val current=org.json.JSONObject()
            map.forEach { (key,value) -> current.put(key,value) }
            mirrorSyncForAccount("device-profile",current.toString())
            return map
        }
    }
    val fresh=buildOfficialProfileImpl(false)
    val json=org.json.JSONObject()
    fresh.forEach { (key,value) -> json.put(key,value) }
    identitySessionPrefs.edit().putString("device-profile",json.toString()).commit()
    identityMirror.put("device-profile",json.toString())
    identityMirror.flush()
    identityCachedProfileStamp=null
    return fresh
}

/** 按官方默认链路生成一整套设备档案（机型 / 系统 / 分辨率 / 密度 / 三个标识）。 */
internal fun Library.buildOfficialProfileImpl(forceRandomOpenudid: Boolean): Map<String,String> {
    val app=identityApp
    val model=android.os.Build.MODEL.orEmpty().trim()
    val brand=android.os.Build.BRAND.orEmpty().trim()
    // 机型 / 品牌认不出来时**整体**回落到内置档案那一台（Redmi + 25053RT47C），
    // 而不是各自填一个默认值——那样会拼出「Intel 平台的三星机」这种自相矛盾的档案。
    val known=model.isNotBlank() && brand.isNotBlank()
    val metrics=app.resources.displayMetrics
    val configuration=app.resources.configuration
    val width=metrics.widthPixels
    val height=metrics.heightPixels
    val largeScreen=(configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >=
        android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
    val installTime=runCatching {
        app.packageManager.getPackageInfo(app.packageName,0).firstInstallTime
    }.getOrDefault(System.currentTimeMillis())
    val telephony=runCatching {
        app.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
    }.getOrNull()
    val androidId=readAndroidIdImpl()
    val carrier=runCatching { telephony?.networkOperatorName.orEmpty() }.getOrDefault("")
    val mccMnc=runCatching { telephony?.networkOperator.orEmpty() }.getOrDefault("")
    return linkedMapOf(
        // 官方默认链路：cdid/clientudid 各自随机，openudid 优先 Android ID。
        // 生成一次后随档案落盘，后续不能重算。
        "profile-format" to "official-v1",
        "cdid" to OfficialDeviceIds.cdid(),
        "openudid" to OfficialDeviceIds.openudid(if(forceRandomOpenudid) null else androidId),
        "openudid-source" to when {
            forceRandomOpenudid -> "forced_random"
            androidId!=null -> "android_id"
            else -> "fallback"
        },
        "clientudid" to OfficialDeviceIds.clientudid(),
        "device_type" to (if(known) model else "25053RT47C"),
        "device_brand" to (if(known) brand else "Redmi"),
        "channel" to "69310258a",
        "dragon_device_type" to "phone",
        "os_version" to android.os.Build.VERSION.RELEASE.orEmpty().ifBlank { "16" },
        "os_api" to android.os.Build.VERSION.SDK_INT.toString(),
        // `Build.DISPLAY` 是这台设备实际的固件版本串（官方 App 也读它当 `rom_version`）。
        "rom_version" to android.os.Build.DISPLAY.orEmpty().ifBlank { "W528JS release-keys" },
        "rom" to android.os.Build.VERSION.INCREMENTAL.orEmpty().ifBlank { "1135" },
        "carrier" to carrier.ifBlank { "CHINA MOBILE" },
        "mcc_mnc" to mccMnc.ifBlank { "46000" },
        // 分辨率与密度：与机型同源（都来自本机）。官方 query 用「长边*短边」，
        // 注册 body 再由 DeviceRegister 换成「短边x长边」。
        // 采集不到时回落到内置值（手机竖屏形态）。
        "resolution" to (if(width>0 && height>0) "${maxOf(width,height)}*${minOf(width,height)}" else "2772*1280"),
        "dpi" to (if(metrics.densityDpi>0) metrics.densityDpi.toString() else "520"),
        "is_android_pad_screen" to (if(largeScreen) "1" else "0"),
        // 重复注册时仍上报这台设备的真实首次安装时间，不能每次都写成“刚刚安装”。
        "apk_first_install_time" to installTime.toString()
    )
}

/** App 进程实际可见的 Android ID；官方 App 也是用这一个，而不是 shell 的全局值。 */
internal fun Library.readAndroidIdImpl(): String? = runCatching {
    android.provider.Settings.Secure.getString(
        identityApp.contentResolver,
        android.provider.Settings.Secure.ANDROID_ID,
    )?.trim()?.takeIf(OfficialDeviceIds::validAndroidId)
}.getOrNull()
