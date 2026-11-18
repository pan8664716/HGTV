// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.hgtv.star.tv.R

/** TV 顶栏更新入口的红点。 */
internal fun TvMainActivity.bindUpdateDot() {
    updateDot?.visibility = if (updateState.hasUpdate) View.VISIBLE else View.GONE
}

/**
 * 用户点击顶栏更新入口后才打开；启动检查本身不会弹窗。
 *
 * 安装按钮只调用 [com.hgtv.star.shared.update.AppUpdateController.install]，由系统
 * 安装器负责确认与覆盖安装，不在应用内等待安装结果。
 */
internal fun TvMainActivity.showUpdateDialog(anchor: View?) {
    val state=updateState
    if (!state.checked && !state.checking) updateController.check()
    val dialog=TvDialog(this,"版本更新")
    val update=state.available
    when {
        update==null && (state.checking || !state.checked) ->
            dialog.message("正在检查更新…")
        update==null && state.error.isNotBlank() -> {
            dialog.message(state.error)
            dialog.button("重新检查",icon=R.drawable.ic_action_restore) {
                updateController.check(force=true)
            }
        }
        update==null -> {
            dialog.message("当前版本 ${AppVersion.name} 已是最新")
            dialog.button("检查更新",icon=R.drawable.ic_action_restore) {
                updateController.check(force=true)
            }
        }
        else -> {
            dialog.message("${update.versionName} · ${"%.1f".format(update.size / 1048576.0)} MB")
            dialog.message(update.notes.ifBlank { "新版已发布。" })
            if (state.ready) {
                dialog.button("安装更新",tone=ButtonTone.PRIMARY,icon=R.drawable.ic_action_restore) {
                    updateController.install()
                }
            } else if (state.downloading) {
                dialog.message("正在下载更新 · ${state.progress}%")
            } else {
                dialog.button("下载并安装",tone=ButtonTone.PRIMARY,icon=R.drawable.ic_action_restore) {
                    updateController.downloadAndInstall()
                }
            }
            if (state.error.isNotBlank()) dialog.message(state.error)
        }
    }
    dialog.button("关闭",icon=R.drawable.ic_menu_close)
    dialog.show(anchor)
}
