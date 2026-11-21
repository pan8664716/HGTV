// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

/**
 * 本机设备身份：`device_id` 与 `iid`。
 *
 * 只有一个来源：**平台注册结果**（`registered-device-id` / `registered-install-id`）。
 *
 * 内容接口（搜索、播放、弹幕）认的是「服务端注册过的 `device_id` + `install_id` 这一对」，
 * 随手编的数字拿不到数据，因此本机不再自己生成随机身份——播放也用注册下发的那一对。
 * 返回空表示这台设备还没注册过：调用方先注册（`TvMainActivity.ensureDeviceRegistered`，
 * 播放前另有一次同步补注册），注册成功后 [saveDeviceIdentity] 落盘，这里就能给出来。
 */
internal fun Library.deviceIdentityImpl(): Map<String,String> {
    ensureIdentityLayoutImpl()
    restoreFromMirrorForRegistrationCookies()
    val registered=identitySessionPrefs.getString("registered-device-id","").orEmpty()
    val registeredInstall=identitySessionPrefs.getString("registered-install-id","").orEmpty()
    if(isDeviceNumberForIdentity(registered) && isDeviceNumberForIdentity(registeredInstall)) {
        // 老版本装过、镜像里还没有这一对的：读的时候补进去，卸载重装才认得回这台设备。
        mirrorSyncForAccount("registered-device-id",registered)
        mirrorSyncForAccount("registered-install-id",registeredInstall)
        val identity=linkedMapOf("device_id" to registered,"iid" to registeredInstall)
        identitySessionPrefs.getString("registered-device-token","").orEmpty()
            .takeIf { it.isNotBlank() }?.let {
                mirrorSyncForAccount("registered-device-token",it)
                identity["device_token"]=it
            }
        identitySessionPrefs.getString("registered-klink-egdi","").orEmpty()
            .takeIf { it.isNotBlank() }?.let {
                mirrorSyncForAccount("registered-klink-egdi",it)
                identity["klink_egdi"]=it
            }
        return identity
    }
    return emptyMap()
}

/**
 * 是否**真**在平台注册过（只有注册过搜索才可能出数据）。
 *
 * 除了「有没有那一对数字」，还要看**这份注册结果是不是按当前档案注册出来的**：
 * 平台按 `cdid` / `openudid` 认设备，档案换了而 `device_id` 没换，两者对不上号，
 * 请求会被当成陌生设备（搜索 `200 + 0 字节`、播放 `101000`）。
 * 这一条是「升级到 1.2.3 后搜索 / 播放都不能用」的正面判据——
 * 没有它，[deviceRegistered] 只看到数字形状对就答 true，永远不会重注册。
 */
internal fun Library.deviceRegisteredImpl(): Boolean {
    ensureIdentityLayoutImpl()
    restoreFromMirrorForRegistrationCookies()
    // **不判 `registrationPending`**：那份「待换新」只是「档案想升级」，旧身份在平台上
    // 仍然是好的（搜索 / 播放都还能用）。判成「没注册」会让每次搜索都同步发一次注册请求，
    // 换新失败时用户每搜一次就多等一个 RTT。换新只由启动那一次（[TvMainActivity.ensureDeviceRegistered]）
    // 与播放前那一次兜底负责。
    // **先取档案指纹**：这一步可能把老版本的随机档案重建成派生值，
    // 那时 [invalidateRegistration] 会当场抹掉注册结果。因此读身份必须放在它之后，
    // 否则读到的是「刚被抹掉之前」那一对，判成已注册。
    val stamp=profileStampImpl()
    val registered=identitySessionPrefs.getString("registered-device-id","").orEmpty()
    val install=identitySessionPrefs.getString("registered-install-id","").orEmpty()
    if(!isDeviceNumberForIdentity(registered) || !isDeviceNumberForIdentity(install)) return false
    // 注册下发的 Cookie 里的 `install_id` 必须与这一对身份一致：它们是同一次注册的产物。
    // 从外部镜像救回来的旧 Cookie（重装 / 升级时那一份）与新的 `registered-install-id`
    // 对不上时，请求里会同时带「新 device_id」与「旧 install_id」，服务端按陌生设备处理——
    // 现象与「没注册过」一样（搜索空响应、播放 101000），只有清缓存能好。
    val cookieInstall=registrationCookiesImpl()["install_id"].orEmpty()
    if(cookieInstall.isNotBlank() && cookieInstall!=install) {
        // 顺手把这份对不上的 Cookie 抹掉：留着它，重新注册时 `putAll` 只会覆盖新下发的键，
        // 旧的 `install_id` 仍在，下一次判断还是 false——那就成了每次请求都重注册一次。
        clearRegistrationCookiesImpl()
        return false
    }
    val known=identitySessionPrefs.getString("registered-profile-stamp","").orEmpty()
    // 老版本没有这一项：不因为缺项就判死（那一版注册出来的身份在服务端是好的），
    // 档案真的换过的那条路由 [invalidateRegistration] 当场作废，不靠这里兜。
    // **不在读的时候补写**：缺项本身是「这份身份比当前版本旧」的判据，
    // 由 [registrationNeedsRefresh] 用一次后台换新把它消掉。
    return known.isBlank() || known==stamp
}

/**
 * 这份注册结果是不是**当前版本注册出来的**（缺 `registered-profile-stamp` = 不是）。
 *
 * 用来做一次升级迁移：1.2.3 换了注册线路与上报参数，旧版本注册下来的 `device_id` / `iid`
 * 在内容接口上会被当成陌生设备——搜索回 `200 + 0 字节`、播放回 `101000`，
 * 而「设置 → 清空缓存」之所以能修好，正是因为它把这些数字删了重新注册。
 *
 * 换新是**后台、可失败**的（见 `TvMainActivity.refreshRegistrationIdentity`）：
 * 换新成功才替换身份，失败就继续用旧的那一对，用户不会因此连首页都看不了。
 */
internal fun Library.registrationNeedsRefreshImpl(): Boolean {
    ensureIdentityLayoutImpl()
    restoreFromMirrorForRegistrationCookies()
    val registered=identitySessionPrefs.getString("registered-device-id","").orEmpty()
    val install=identitySessionPrefs.getString("registered-install-id","").orEmpty()
    if(!isDeviceNumberForIdentity(registered) || !isDeviceNumberForIdentity(install)) return false
    return identitySessionPrefs.getString("registered-profile-stamp","").orEmpty().isBlank()
}

/** 当前档案的指纹（`cdid|openudid`）：只用来判断「注册结果与档案是不是同一套」。 */
internal fun Library.profileStampImpl(): String {
    identityCachedProfileStamp?.let { return it }
    val profile=deviceProfile()
    val stamp=profile["cdid"].orEmpty()+"|"+profile["openudid"].orEmpty()
    // 清除数据期间 `deviceProfile()` 返回空表：这一份不能缓存，否则清完缓存
    // （同一个进程里还会读几次）会一直拿着 `"|"` 去比对。
    if(profile.isEmpty()) return stamp
    identityCachedProfileStamp=stamp
    return stamp
}

/**
 * 作废本机的注册结果（设备身份 + 下发 Cookie + 档案指纹）。
 *
 * 与 [saveDeviceIdentity] 传空同义，但多清一项指纹——三者必须一起走，
 * 少清一项都会让下一次 [deviceRegistered] 判成「还是好的」。
 */
internal fun Library.invalidateRegistrationImpl() {
    if(identityDiscarding) return
    identitySessionPrefs.edit().remove("registered-device-id").remove("registered-install-id")
        .remove("registered-device-token")
        .remove("registered-klink-egdi")
        .remove("registered-profile-stamp")
        .remove("registered-cookies")
        .remove("registration-pending").apply()
    listOf("registered-device-id","registered-install-id","registered-device-token",
        "registered-klink-egdi","registered-profile-stamp",
        "registered-cookies")
        .forEach(identityMirror::remove)
    identityCachedProfileStamp=null
    RecoverySnapshotManager.schedule(identityApp)
}

/** 落盘注册结果；传空表示清除（下次启动重新注册）。 */
internal fun Library.saveDeviceIdentityImpl(
    deviceId: String,
    installId: String,
    klinkEgdi: String = "",
    deviceToken: String = "",
) {
    if(identityDiscarding) return
    if(deviceId.isBlank() || installId.isBlank()) { invalidateRegistrationImpl(); return }
    // 连同「这份身份是按哪套档案注册出来的」一起落盘：档案一变就能立刻判出来
    // （见 [deviceRegistered]）。
    val stamp=profileStampImpl()
    // **同步 commit**（不是 apply）：`apply()` 的落盘由系统在后台排一次，
    // 进程被强杀（电视盒子拔电 / 用户划掉任务）时那一次可能还没写下去。
    // 身份丢了就等于「这台设备从没注册过」——搜索空响应、播放 110001。
    // 这几项只在注册成功时写一次，同步写一次磁盘的代价可以忽略。
    identitySessionPrefs.edit().putString("registered-device-id",deviceId).putString("registered-install-id",installId)
        .putString("registered-device-token",deviceToken)
        .putString("registered-klink-egdi",klinkEgdi)
        .putString("registered-profile-stamp",stamp).commit()
    // 身份一变（首次注册 / 重新注册）镜像立刻跟上：卸载重装后要先认回这台设备，再谈别的。
    identityMirror.put("registered-device-id",deviceId)
    identityMirror.put("registered-install-id",installId)
    if(deviceToken.isNotBlank()) identityMirror.put("registered-device-token",deviceToken)
    else identityMirror.remove("registered-device-token")
    if(klinkEgdi.isNotBlank()) identityMirror.put("registered-klink-egdi",klinkEgdi)
    else identityMirror.remove("registered-klink-egdi")
    identityMirror.put("registered-profile-stamp",stamp)
    // 不等 250 ms 的合并窗口：注册成功后用户可能马上就退出 / 强杀，
    // 那一次写没落盘，下一次进来就又成了「未注册设备」——正是用户报的那个现象。
    identityMirror.flush()
    RecoverySnapshotManager.schedule(identityApp)
}
