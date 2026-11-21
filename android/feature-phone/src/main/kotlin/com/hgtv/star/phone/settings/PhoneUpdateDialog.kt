// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import com.hgtv.star.phone.ui.PhoneDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Phone 设置里的被动更新入口。
 *
 * 更新检查不会主动弹窗；这里只在用户点击设置行后显示版本信息和操作。
 * 更新地址写死在 AppUpdateSource.BUILT_IN，没有设置入口。
 * 下载完成后交给共享安装层，由系统会话或原生安装器完成覆盖安装。
 */
internal fun PhoneSettingsActivity.showUpdateDialog() {
    val controller=settingsUpdateController
    if(!controller.state.value.checked && !controller.state.value.checking) controller.check()
    val state=controller.state.value
    if(state.checking) {
        showUpdateCheckingDialog()
        return
    }
    val dialog=PhoneDialog(this,UiText.get(com.hgtv.star.shared.R.string.ui_0172))
    val update=state.available
    when {
        update==null && (state.checking || !state.checked) ->
            dialog.message(UiText.get(com.hgtv.star.shared.R.string.ui_0152))
        update==null && state.error.isNotBlank() -> {
            dialog.message(state.error)
            dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0259),primary=true) {
                settingsUpdateController.check(force=true)
            }
        }
        update==null -> {
            dialog.message(UiText.get(com.hgtv.star.shared.R.string.ui_0100, appVersionName()))
            dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0143),primary=true) {
                settingsUpdateController.check(force=true)
            }
        }
        else -> {
            dialog.message("${update.versionName} · ${"%.1f".format(update.size / 1048576.0)} MB")
            dialog.message(update.notes.ifBlank { UiText.get(com.hgtv.star.shared.R.string.ui_0122) })
            if(state.installing) {
                dialog.message("正在等待系统安装")
            } else if(state.ready) {
                val label=if(state.permissionRequired) "去授权" else UiText.get(com.hgtv.star.shared.R.string.ui_0058)
                dialog.button(label,primary=true) {
                    dialog.dismiss()
                    settingsUpdateController.install()
                }
            } else if(state.downloading) {
                dialog.message(UiText.get(com.hgtv.star.shared.R.string.ui_0144, state.progress))
            } else {
                dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0018),primary=true) {
                    settingsUpdateController.downloadAndInstall()
                }
            }
            if(state.error.isNotBlank()) dialog.message(state.error)
        }
    }
    dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0035))
    dialog.show()
}

private fun PhoneSettingsActivity.showUpdateCheckingDialog() {
    val dialog=PhoneDialog(this,UiText.get(com.hgtv.star.shared.R.string.ui_0172))
    dialog.message(UiText.get(com.hgtv.star.shared.R.string.ui_0153))
    dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0035))
    var advance=false
    val job=settingsScope.launch {
        settingsUpdateController.state.first { !it.checking }
        if(!dialog.isShowing) return@launch
        advance=true
        dialog.dismiss()
    }
    dialog.show {
        if(advance && !isFinishing && !isDestroyed) showUpdateDialog() else job.cancel()
    }
}
