// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.phone.ui.PhoneDialog

/**
 * Phone 设置里的被动更新入口。
 *
 * 更新检查不会主动弹窗；这里只在用户点击设置行后显示版本信息和操作。
 * 下载完成后直接交给系统安装器，不等待安装回执。
 */
internal fun PhoneSettingsActivity.showUpdateDialog() {
    val state=settingsUpdateState
    if(!state.checked && !state.checking) settingsUpdateController.check()
    val dialog=PhoneDialog(this,"版本更新")
    val update=state.available
    when {
        update==null && (state.checking || !state.checked) ->
            dialog.message("正在检查更新…")
        update==null && state.error.isNotBlank() -> {
            dialog.message(state.error)
            dialog.button("重新检查",primary=true) {
                settingsUpdateController.check(force=true)
            }
        }
        update==null -> {
            dialog.message("当前版本 ${appVersionName()} 已是最新")
            dialog.button("检查更新",primary=true) {
                settingsUpdateController.check(force=true)
            }
        }
        else -> {
            dialog.message("${update.versionName} · ${"%.1f".format(update.size / 1048576.0)} MB")
            dialog.message(update.notes.ifBlank { "新版已发布。" })
            if(state.ready) {
                dialog.button("安装更新",primary=true) {
                    settingsUpdateController.install()
                }
            } else if(state.downloading) {
                dialog.message("正在下载更新 · ${state.progress}%")
            } else {
                dialog.button("下载并安装",primary=true) {
                    settingsUpdateController.downloadAndInstall()
                }
            }
            if(state.error.isNotBlank()) dialog.message(state.error)
        }
    }
    dialog.button("关闭")
    dialog.show()
}
