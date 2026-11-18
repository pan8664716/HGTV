// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.hgtv.star.tv.R

/** TV 顶栏更新入口：没有新版时整枚入口隐藏，有新版时才显示并亮红点。 */
internal fun TvMainActivity.bindUpdateEntry() {
    val show=updateState.hasUpdate
    updateHolder?.visibility = if(show) View.VISIBLE else View.GONE
    updateIcon?.visibility = if(show) View.VISIBLE else View.GONE
    updateDot?.visibility = if(show) View.VISIBLE else View.GONE
    settingsUpdateButton?.setLabel(settingsUpdateLabel())
    if(show) {
        val left=nav.lastOrNull() ?: recommendIcon
        val icon=updateIcon
        if(left!=null && icon!=null) {
            left.nextFocusRightId=icon.id
            icon.nextFocusLeftId=left.id
            icon.nextFocusRightId=icon.id
        }
    }
}

internal fun TvMainActivity.settingsUpdateLabel(): String = when {
    updateState.hasUpdate -> "检查更新  ·  发现新版本"
    updateState.checking -> "检查更新  ·  正在检查"
    updateState.error.isNotBlank() -> "检查更新  ·  检查失败"
    else -> "检查更新"
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
            dialog.button("重新检查",icon=R.drawable.ic_toolbar_update) {
                updateController.check(force=true)
            }
        }
        update==null -> {
            dialog.message("当前版本 ${AppVersion.name} 已是最新")
            dialog.button("检查更新",icon=R.drawable.ic_toolbar_update) {
                updateController.check(force=true)
            }
        }
        else -> {
            dialog.message("${update.versionName} · ${"%.1f".format(update.size / 1048576.0)} MB")
            dialog.message(update.notes.ifBlank { "新版已发布。" })
            if (state.ready) {
                dialog.button("安装更新",tone=ButtonTone.PRIMARY,icon=R.drawable.ic_toolbar_update) {
                    updateController.install()
                }
            } else if (state.downloading) {
                dialog.message("正在下载更新 · ${state.progress}%")
            } else {
                dialog.button("下载并安装",tone=ButtonTone.PRIMARY,icon=R.drawable.ic_toolbar_update) {
                    updateController.downloadAndInstall()
                }
            }
            if (state.error.isNotBlank()) dialog.message(state.error)
        }
    }
    dialog.button("关闭",icon=R.drawable.ic_menu_close)
    dialog.show(anchor)
}
