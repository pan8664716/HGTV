// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import com.hgtv.star.shared.architecture.AppRelaunch
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.compat.DeviceFormSelection
import com.hgtv.star.shared.data.RecoveryPermissions
import com.hgtv.star.shared.data.RecoverySnapshotManager

internal fun TvMainActivity.handleActivityResultImpl(
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
) {
    if(requestCode==REQUEST_DEVICE_FORM) {
        if(resultCode==Activity.RESULT_OK &&
            data?.getBooleanExtra(DeviceFormSelection.EXTRA_CHANGED,false)==true
        ) {
            val selected=DeviceForm.ofStored(
                data?.getStringExtra(DeviceFormSelection.EXTRA_SELECTED_FORM).orEmpty()
            )
            showHint("正在切换到${selected?.label ?: "所选"}界面")
            AppRelaunch.switchDeviceForm(this)
        }
        return
    }
    if(requestCode==REQUEST_VOICE) {
        voiceLaunching=false
        voiceReturned=true
        // 语音界面返回后页面会重建：这一次别让输入框抢焦点、别弹输入法。
        suppressSearchFocusOnce=true
        val spoken=if(resultCode==Activity.RESULT_OK)
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }
        else null
        val clean=normalizeVoiceQuery(spoken.orEmpty())
        if(clean.isEmpty()) {
            noteVoice(if(resultCode==Activity.RESULT_OK) "empty" else "cancel")
            if(resultCode==Activity.RESULT_OK) showHint("没有听清，请再说一次")
        } else {
            pendingVoiceQuery=clean
            noteVoice("success")
        }
        // 识别界面如果是对话框（本页没有 onStop），onResume 不一定还会来一次；
        // 这里也投一次，`applyVoiceReturn` 自己保证只消费一次。
        main.post { applyVoiceReturn() }
        return
    }
    if(requestCode==REQUEST_RECOVERY_EXPORT) {
        if(resultCode!=Activity.RESULT_OK) return
        val uri=data?.data ?: return
        val payload=pendingRecoveryExport ?: return
        pendingRecoveryExport=null
        val ok=runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                ?: error("no stream")
        }.isSuccess
        tvTools.toast(if(ok) "备份已导出" else "写入失败")
        return
    }
    if(requestCode==REQUEST_RECOVERY_IMPORT) {
        if(resultCode!=Activity.RESULT_OK) return
        val uri=data?.data ?: return
        val text=runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer=ByteArray(RecoverySnapshotManager.MAX_BYTES+1)
                var read=0
                while(read<buffer.size) {
                    val count=input.read(buffer,read,buffer.size-read)
                    if(count<=0) break
                    read+=count
                }
                String(buffer,0,read,Charsets.UTF_8)
            }
        }.getOrNull()
        if(text.isNullOrBlank() || !RecoverySnapshotManager.importText(this,text)) {
            tvTools.toast("备份格式不对")
            return
        }
        tvTools.toast("恢复完成，正在重启")
        AppRelaunch.relaunchAndExit(this)
        return
    }
    if(requestCode!=REQUEST_ACCOUNT_FILE || resultCode!=Activity.RESULT_OK) return
    pendingAccountUri=data?.data
}

fun TvMainActivity.exportRecoveryFile() {
    val payload=runCatching { RecoverySnapshotManager.exportText(this) }.getOrNull()
    if(payload.isNullOrBlank()) {
        tvTools.toast("本机记录暂时无法导出")
        return
    }
    pendingRecoveryExport=payload
    val name="hgtv-recovery-${System.currentTimeMillis()/1000}.json"
    val started=runCatching {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE,name),
            REQUEST_RECOVERY_EXPORT,
        )
    }.isSuccess
    if(!started) {
        pendingRecoveryExport=null
        tvTools.toast("这台设备没有文件选择器")
    }
}

fun TvMainActivity.importRecoveryFile() {
    val started=runCatching {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"),
            REQUEST_RECOVERY_IMPORT,
        )
    }.isSuccess
    if(!started) tvTools.toast("这台设备没有文件选择器")
}

internal fun TvMainActivity.handleRequestPermissionsResultImpl(
    requestCode: Int,
    results: IntArray,
) {
    if(requestCode==RecoveryPermissions.RESET_REQUEST_CODE) {
        RecoverySnapshotManager.onPermissionChanged(this,RecoveryPermissions.isGranted(results))
        performLocalReset()
    }
}
