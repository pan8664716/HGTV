// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.shared.ui.UiText
import android.view.View
import com.hgtv.star.tv.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
 * 更新地址写死在 AppUpdateSource.BUILT_IN，没有设置入口。
 * 安装按钮只调用 [com.hgtv.star.shared.update.AppUpdateController.install]，由系统
 * 安装器负责确认与覆盖安装，不在应用内等待安装结果。
 */
internal fun TvMainActivity.showUpdateDialog(anchor: View?) {
    if (!updateController.state.value.checked && !updateController.state.value.checking) {
        updateController.check()
    }
    val state=updateController.state.value
    if (state.checking) {
        showUpdateCheckingDialog(anchor)
        return
    }
    val dialog=TvDialog(this,UiText.get(com.hgtv.star.shared.R.string.ui_0172))
    val update=state.available
    when {
        update==null && (state.checking || !state.checked) ->
            dialog.message(UiText.get(com.hgtv.star.shared.R.string.ui_0152))
        update==null && state.error.isNotBlank() -> {
            dialog.message(state.error)
            dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0259),icon=R.drawable.ic_toolbar_update) {
                updateController.check(force=true)
            }
        }
        update==null -> {
            dialog.message(UiText.get(com.hgtv.star.shared.R.string.ui_0099, AppVersion.name))
            dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0143),icon=R.drawable.ic_toolbar_update) {
                updateController.check(force=true)
            }
        }
        else -> {
            dialog.message("${update.versionName} · ${"%.1f".format(update.size / 1048576.0)} MB")
            dialog.message(update.notes.ifBlank { UiText.get(com.hgtv.star.shared.R.string.ui_0122) })
            if (state.ready) {
                dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0058),tone=ButtonTone.PRIMARY,icon=R.drawable.ic_toolbar_update) {
                    updateController.install()
                }
            } else if (state.downloading) {
                dialog.message(UiText.get(com.hgtv.star.shared.R.string.ui_0144, state.progress))
            } else {
                dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0018),tone=ButtonTone.PRIMARY,icon=R.drawable.ic_toolbar_update) {
                    updateController.downloadAndInstall()
                }
            }
            if (state.error.isNotBlank()) dialog.message(state.error)
        }
    }
    dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0035),icon=R.drawable.ic_menu_close)
    dialog.show(anchor)
}

private fun TvMainActivity.showUpdateCheckingDialog(anchor: View?) {
    val dialog=TvDialog(this,UiText.get(com.hgtv.star.shared.R.string.ui_0172))
    dialog.message(UiText.get(com.hgtv.star.shared.R.string.ui_0153))
    dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0035),icon=R.drawable.ic_menu_close)
    var advance=false
    val job=appScope.launch {
        updateController.state.first { !it.checking }
        if(!dialog.isShowing) return@launch
        advance=true
        dialog.dismiss()
    }
    dialog.show(anchor=anchor,onDismiss={
        if(advance && !isFinishing && !isDestroyed) {
            showUpdateDialog(anchor)
        } else {
            job.cancel()
        }
    })
}
