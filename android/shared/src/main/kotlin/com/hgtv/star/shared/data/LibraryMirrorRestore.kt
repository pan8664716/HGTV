// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.util.Log

/**
 * 把外部镜像里本机缺的项补回会话偏好。本机已有的值优先，镜像只补缺失项。
 */
internal fun Library.restoreFromMirrorImpl(): Boolean {
    if(identityDiscarding) return false
    if(identityMirrorChecked) return false
    if(!identityMirror.isLoaded()) return false
    identityMirrorChecked=true
    val edit=identitySessionPrefs.edit()
    var changed=false
    identityMirrorKeys.forEach { key ->
        if(identitySessionPrefs.contains(key)) return@forEach
        val text=identityMirror.string(key) ?: return@forEach
        if(key=="account-expires" || key=="last-beat-at") {
            edit.putLong(key,text.toLongOrNull() ?: 0L)
        } else {
            edit.putString(key,text)
        }
        changed=true
    }
    if(changed) {
        edit.apply()
        Log.i("HGTV","Restored device identity / session from the external mirror")
        SessionBootstrap.onSessionRestored()
    }
    return changed
}

/**
 * 权限拿到后重新读镜像。读盘留在 loader 线程，结果回主线程。
 */
internal fun Library.retryMirrorRestoreImpl(onDone: (Boolean) -> Unit) {
    runCatching {
        identityLoader.execute {
            val restored=runCatching {
                identityMirror.reload()
                identityMirrorChecked=false
                val changed=restoreFromMirrorImpl()
                identityMirror.persistNow()
                changed
            }.getOrDefault(false)
            identityMainHandler.post { onDone(restored) }
        }
    }.onFailure { identityMainHandler.post { onDone(false) } }
}

/** 本机值补进镜像；空值表示本机还没有，不删除镜像里的旧副本。 */
internal fun Library.mirrorSyncImpl(key: String, value: String) {
    if(value.isBlank()) return
    if(identityMirror.string(key).orEmpty()==value) return
    identityMirror.put(key,value)
}

/** 数值项为 0 时表示本机还不知道，不写镜像。 */
internal fun Library.mirrorSyncImpl(key: String, value: Long) {
    if(value<=0) return
    if(identityMirror.string(key)==value.toString()) return
    identityMirror.put(key,value)
}
