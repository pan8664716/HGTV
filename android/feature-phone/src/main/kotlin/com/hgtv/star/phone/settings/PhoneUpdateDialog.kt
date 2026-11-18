// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.phone.ui.PhoneSoft.applyWell
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.shared.update.AppUpdateSource

/**
 * Phone 设置里的被动更新入口。
 *
 * 更新检查不会主动弹窗；这里只在用户点击设置行后显示版本信息和操作。
 * 下载完成后直接交给系统安装器，不等待安装回执。
 */
internal fun PhoneSettingsActivity.showUpdateDialog() {
    if(!settingsUpdateController.hasSource()) {
        showUpdateSourceDialog()
        return
    }
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
            dialog.button("修改更新地址") { showUpdateSourceDialog() }
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

private fun PhoneSettingsActivity.showUpdateSourceDialog(
    error: String?=null,
    initial: String="",
) {
    val input=EditText(this).apply {
        hint="owner/repo 或 update.json 地址"
        setText(initial)
        setSelection(text.length)
        setHintTextColor(PhoneStyle.textFaint)
        setTextColor(PhoneStyle.text)
        phoneSize(13)
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters=arrayOf(InputFilter.LengthFilter(AppUpdateSource.MAX_INPUT_LENGTH))
        minLines=1
        maxLines=3
        setPadding(dp(14),dp(10),dp(14),dp(10))
        applyWell(PhoneStyle.well,14f,4f)
    }
    val dialog=PhoneDialog(this,"设置更新地址")
    dialog.message("输入 GitHub 用户/项目（例如 owner/repo），或 update.json 更新地址。保存后会立即检查。")
    if(!error.isNullOrBlank()) dialog.message(error)
    dialog.content(input)
    dialog.button("取消")
    dialog.button("保存并检查",primary=true) {
        settingsUpdateController.setSource(input.text?.toString().orEmpty())
            .onSuccess {
                settingsUpdateController.check(force=true)
                showUpdateDialog()
            }
            .onFailure {
                showUpdateSourceDialog(
                    error=it.message ?: "更新地址无效，请重试。",
                    initial=input.text?.toString().orEmpty(),
                )
            }
    }
    dialog.show()
}
