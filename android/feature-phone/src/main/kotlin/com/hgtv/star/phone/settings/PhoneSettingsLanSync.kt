// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

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
    PhoneDialog(this, "同步其他设备数据")
        .message("请选择这台设备在本次同步中的角色。")
        .choices(
            listOf(
                "我要同步其他设备的数据",
                "其他设备要同步我的数据",
            ),
        ) { index -> startLanSyncRoleImpl(sourceOnly = index == 1) }
        .button("取消")
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
    PhoneDialog(this, "未发现同步设备")
        .message("请确认两台设备连接同一局域网，并同时打开「同步其他设备数据」。")
        .button("重新查找") { startLanSyncRoleImpl(sourceOnly = false) }
        .button("取消")
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
    val dialog = PhoneDialog(this, "选择要同步的数据")
        .message("登录、设备与设置会覆盖本机；播放和收藏等记录会合并，并保留进度更高的一项。")
        .content(syncSwitch("登录信息", true) { login = it })
        .content(syncSwitch("设备参数", true) { device = it })
        .content(syncSwitch("播放、收藏等记录", true) { content = it })
        .content(syncSwitch("设置信息", true) { settings = it })
    dialog.buttonsInRow()
    dialog.button("取消")
    dialog.button("同步所选", primary = true) {
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
                dialog.button("取消")
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
            PhoneDialog(this, "选择同步设备")
                .message("读取登录信息、设备参数、内容数据和全部设置。读取后仍需在本机确认，确认后会覆盖本机登录与设备身份。")
                .choices(peers.map { "${it.displayName}  ·  ${it.host}" }) { index ->
                    peers.getOrNull(index)?.let(settingsLanSyncController::loadPeer)
                }
                .button("重新查找") { startLanSyncRoleImpl(sourceOnly = false) }
                .button("取消") { closeLanSyncSessionImpl() }
                .show()
        }
        LanSyncController.Step.LOADING, LanSyncController.Step.APPLYING -> {
            if (settingsLanSyncStep == state.step) return
            settingsLanSyncStep = state.step
            settingsLanSyncDialog?.dismiss()
            val dialog = PhoneDialog(
                this,
                if (state.step == LanSyncController.Step.LOADING) "正在同步" else "正在应用同步数据",
            ).message(state.message).button("取消") { closeLanSyncSessionImpl() }
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
