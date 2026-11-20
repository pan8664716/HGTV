// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.widget.TextView
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneSwitchRow
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.shared.architecture.AppRelaunch
import com.hgtv.star.shared.feature.lan.LanSyncController
import com.hgtv.star.shared.lan.DeviceSyncSelection
import kotlinx.coroutines.launch

internal fun PhoneSettingsActivity.closeLanSyncSessionImpl() {
    settingsLanSyncJob?.cancel()
    settingsLanSyncJob = null
    settingsLanSyncDialog?.dismiss()
    settingsLanSyncDialog = null
    settingsLanSyncStatus = null
    settingsLanSyncStep = null
    settingsLanSyncController.reset()
}

internal fun PhoneSettingsActivity.showLanLibrarySync() {
    closeLanSyncSessionImpl()
    PhoneDialog(this, UiText.get(com.hgtv.star.shared.R.string.ui_0051))
        .message(UiText.get(com.hgtv.star.shared.R.string.ui_0227))
        .choices(
            listOf(
                "我要同步其他设备的数据",
                "其他设备要同步我的数据",
            ),
        ) { index -> startLanSyncRoleImpl(sourceOnly = index == 1) }
        .button(UiText.get(com.hgtv.star.shared.R.string.ui_0046))
        .show()
}

private fun PhoneSettingsActivity.startLanSyncRoleImpl(sourceOnly: Boolean) {
    closeLanSyncSessionImpl()
    settingsLanSyncJob = settingsScope.launch {
        settingsLanSyncController.state.collect { state -> renderLanSyncImpl(state) }
    }
    if (sourceOnly) {
        settingsLanSyncController.startSource()
    } else {
        settingsLanSyncController.startReceiver()
    }
}

private fun PhoneSettingsActivity.showLanSyncNoPeersImpl() {
    PhoneDialog(this, UiText.get(com.hgtv.star.shared.R.string.ui_0134))
        .message(UiText.get(com.hgtv.star.shared.R.string.ui_0226))
        .button(UiText.get(com.hgtv.star.shared.R.string.ui_0258)) { startLanSyncRoleImpl(sourceOnly = false) }
        .button(UiText.get(com.hgtv.star.shared.R.string.ui_0046))
        .show()
}

private fun PhoneSettingsActivity.showLanSyncRestoreConfirmImpl() {
    var login = true
    var device = true
    var content = true
    var settings = true
    fun syncSwitch(label: String, checked: Boolean, onToggle: (Boolean) -> Unit): PhoneSwitchRow =
        PhoneSwitchRow(this, label, checked, onToggle = onToggle).apply {
            minimumHeight = dp(if (settingsLandscape) 36 else 42)
        }
    val dialog = PhoneDialog(this, UiText.get(com.hgtv.star.shared.R.string.ui_0252))
        .message(UiText.get(com.hgtv.star.shared.R.string.ui_0181))
        .content(syncSwitch("登录信息", true) { login = it })
        .content(syncSwitch("设备参数", true) { device = it })
        .content(syncSwitch("播放、收藏等记录", true) { content = it })
        .content(syncSwitch("设置信息", true) { settings = it })
    dialog.buttonsInRow()
    dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0046))
    dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0052), primary = true) {
        val selection = DeviceSyncSelection(
            login = login,
            device = device,
            content = content,
            settings = settings,
        )
        if (!selection.hasAny) {
            toastImpl("请至少选择一类同步内容")
        } else {
            settingsLanSyncController.apply(selection) { saved ->
                if (!saved) toastImpl("部分数据未能写入本机，请重试")
            }
        }
    }
    dialog.show()
}

private fun PhoneSettingsActivity.renderLanSyncImpl(state: LanSyncController.State) {
    when (state.step) {
        LanSyncController.Step.IDLE -> Unit
        LanSyncController.Step.SERVING, LanSyncController.Step.DISCOVERING -> {
            if (settingsLanSyncStep != state.step) {
                settingsLanSyncDialog?.dismiss()
                settingsLanSyncStep = state.step
                val text = TextView(this).apply {
                    setTextColor(PhoneStyle.textDim)
                    phoneSize(if (settingsLandscape) 11 else 13)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                settingsLanSyncStatus = text
                val dialog = PhoneDialog(
                    this,
                    if (state.role == LanSyncController.Role.SOURCE) {
                        "本机作为同步源"
                    } else {
                        "同步其他设备数据"
                    },
                ).message(
                    if (state.role == LanSyncController.Role.SOURCE) {
                        "本机将一直出现在同一局域网的设备列表中。请保持此页面打开。"
                    } else {
                        "正在通过局域网查找其他设备。请在另一台设备上也打开这个功能。"
                    }
                ).content(text)
                dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0046))
                dialog.show {
                    if (settingsLanSyncDialog === dialog) closeLanSyncSessionImpl()
                }
                settingsLanSyncDialog = dialog
            }
            settingsLanSyncStatus?.text = state.message.ifBlank {
                if (state.role == LanSyncController.Role.SOURCE) {
                    "正在开启同步源…"
                } else {
                    "正在查找设备…"
                }
            }
        }
        LanSyncController.Step.PEERS -> {
            if (settingsLanSyncStep == state.step) return
            settingsLanSyncStep = state.step
            settingsLanSyncDialog?.dismiss()
            settingsLanSyncDialog = null
            val peers = state.peers
            PhoneDialog(this, UiText.get(com.hgtv.star.shared.R.string.ui_0250))
                .message(UiText.get(com.hgtv.star.shared.R.string.ui_0229))
                .choices(peers.map { "${it.displayName}  ·  ${it.host}" }) { index ->
                    peers.getOrNull(index)?.let(settingsLanSyncController::loadPeer)
                }
                .button(UiText.get(com.hgtv.star.shared.R.string.ui_0258)) { startLanSyncRoleImpl(sourceOnly = false) }
                .button(UiText.get(com.hgtv.star.shared.R.string.ui_0046)) { closeLanSyncSessionImpl() }
                .show()
        }
        LanSyncController.Step.LOADING, LanSyncController.Step.APPLYING -> {
            if (settingsLanSyncStep == state.step) return
            settingsLanSyncStep = state.step
            settingsLanSyncDialog?.dismiss()
            val dialog = PhoneDialog(
                this,
                if (state.step == LanSyncController.Step.LOADING) "正在同步" else "正在应用同步数据",
            ).message(state.message).button(UiText.get(com.hgtv.star.shared.R.string.ui_0046)) { closeLanSyncSessionImpl() }
            settingsLanSyncDialog = dialog
            dialog.show()
        }
        LanSyncController.Step.READY_TO_APPLY -> {
            if (settingsLanSyncStep == state.step) return
            settingsLanSyncStep = state.step
            settingsLanSyncDialog?.dismiss()
            settingsLanSyncDialog = null
            showLanSyncRestoreConfirmImpl()
        }
        LanSyncController.Step.ERROR -> {
            settingsLanSyncDialog?.dismiss()
            settingsLanSyncDialog = null
            if (state.message == "没有发现同步设备") {
                closeLanSyncSessionImpl()
                showLanSyncNoPeersImpl()
            } else {
                settingsLanSyncStatus?.text = state.error
                if (state.error.isNotBlank() &&
                    state.step != LanSyncController.Step.DISCOVERING
                ) {
                    toastImpl(state.error)
                }
            }
        }
        LanSyncController.Step.DONE -> {
            settingsLanSyncDialog?.dismiss()
            settingsLanSyncDialog = null
            toastImpl("同步完成，正在重启应用")
            settingsSyncHandler.postDelayed({
                if (!isDestroyed) AppRelaunch.relaunchAndExit(this)
            }, 800L)
        }
    }
}
