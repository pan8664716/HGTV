// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.hgtv.star.core.PhoneSearchServer
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
 * 安装按钮只调用 [com.hgtv.star.shared.update.AppUpdateController.install]，由系统
 * 安装器负责确认与覆盖安装，不在应用内等待安装结果。
 */
internal fun TvMainActivity.showUpdateDialog(anchor: View?) {
    if (!updateController.hasSource()) {
        showUpdateSourceDialog(anchor)
        return
    }
    if (!updateController.state.value.checked && !updateController.state.value.checking) {
        updateController.check()
    }
    val state=updateController.state.value
    if (state.checking) {
        showUpdateCheckingDialog(anchor)
        return
    }
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
            dialog.button("修改更新地址") { showUpdateSourceDialog(anchor) }
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
            dialog.button("修改更新地址") { showUpdateSourceDialog(anchor) }
            if (state.error.isNotBlank()) dialog.message(state.error)
        }
    }
    dialog.button("关闭",icon=R.drawable.ic_menu_close)
    dialog.show(anchor)
}

private fun TvMainActivity.showUpdateCheckingDialog(anchor: View?) {
    val dialog=TvDialog(this,"版本更新")
    dialog.message("正在检查版本更新…")
    dialog.button("关闭",icon=R.drawable.ic_menu_close)
    var advance=false
    val job=appScope.launch {
        updateController.state.first { !it.checking }
        if(!dialog.isShowing) return@launch
        advance=true
        dialog.dismiss()
    }
    dialog.show(anchor=anchor,onDismiss={
        if(advance && !isFinishing && !isDestroyed) showUpdateDialog(anchor) else job.cancel()
    })
}

/** TV 没有可用的文本输入法时，更新地址也走手机扫码的一次性表单。 */
private fun TvMainActivity.showUpdateSourceDialog(anchor: View?) {
    tvTools.phoneInput(
        anchor = anchor,
        title = "设置更新地址",
        instructions = "用手机相机扫下面的码，打开电视发的一次性页面\n" +
            "在手机上输入更新地址\n" +
            "保存后电视立即检查更新",
        form = PhoneSearchServer.Form.updateSource,
        validate = updateController::validateSource,
    ) { input ->
        updateController.setSource(input)
            .onSuccess {
                updateController.check(force = true)
                showUpdateDialog(anchor)
            }
            .onFailure { error ->
                tvTools.info(
                    "更新地址无效",
                    error.message ?: "请检查项目名或地址后重试。",
                    anchor,
                )
            }
    }
}
