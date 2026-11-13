// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import android.widget.LinearLayout
import com.hgtv.star.core.BackupData
import com.hgtv.star.shared.architecture.AppRelaunch
import com.hgtv.star.shared.feature.lan.LanSyncController
import com.hgtv.star.shared.lan.DeviceSyncSelection
import com.hgtv.star.tv.list.showCatalog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private var lanSyncUiJob: Job? = null
private var lanSyncUiDialog: TvDialog? = null
private var lanSyncUiStep: LanSyncController.Step? = null
private var lanSyncUiStatus: android.widget.TextView? = null

fun TvMainActivity.closeLanSyncSession() {
    lanSyncUiJob?.cancel()
    lanSyncUiJob = null
    lanSyncUiDialog?.dismiss()
    lanSyncUiDialog = null
    lanSyncUiStep = null
    lanSyncUiStatus = null
    lanSyncController.reset()
}

fun TvMainActivity.showLanLibrarySync(anchor: View) {
    closeLanSyncSession()
    val dialog = TvDialog(this, "同步其他设备数据")
    dialog.message("请选择这台设备在本次同步中的角色。")
    dialog.choices(
        listOf(
            "我要同步其他设备的数据",
            "其他设备要同步我的数据",
        ),
        onChoice = { index -> startLanSyncRole(anchor, sourceOnly = index == 1) },
    )
    dialog.button("取消", icon = R.drawable.ic_menu_close)
    dialog.show(anchor)
}

private fun TvMainActivity.startLanSyncRole(anchor: View, sourceOnly: Boolean) {
    closeLanSyncSession()
    lanSyncUiJob = appScope.launch {
        lanSyncController.state.collect { state -> renderLanSync(anchor, state) }
    }
    if (sourceOnly) lanSyncController.startSource() else lanSyncController.startReceiver()
}

private fun TvMainActivity.renderLanSync(anchor: View, state: LanSyncController.State) {
    when (state.step) {
        LanSyncController.Step.IDLE -> Unit
        LanSyncController.Step.SERVING, LanSyncController.Step.DISCOVERING -> {
            if (lanSyncUiStep != state.step) {
                lanSyncUiDialog?.dismiss()
                lanSyncUiStep = state.step
                val dialog = TvDialog(
                    this,
                    if (state.role == LanSyncController.Role.SOURCE) "本机作为同步源" else "同步其他设备数据",
                ).message(
                    if (state.role == LanSyncController.Role.SOURCE) {
                        "本机将一直出现在同一局域网的设备列表中。请保持此页面打开。"
                    } else {
                        "正在通过局域网查找其他设备。请在另一台设备上也打开这个功能。"
                    }
                )
                val status = tvText("", 15f, TvStyle.textStrong)
                lanSyncUiStatus = status
                dialog.content.addView(status, LinearLayout.LayoutParams(-1, -2))
                dialog.button("取消", icon = R.drawable.ic_menu_close)
                dialog.setOnDismissExtra {
                    if (lanSyncUiDialog === dialog) closeLanSyncSession()
                }
                lanSyncUiDialog = dialog
                dialog.show(anchor)
            }
            lanSyncUiStatus?.text = state.message.ifBlank {
                if (state.role == LanSyncController.Role.SOURCE) "正在开启同步源…" else "正在查找设备…"
            }
        }
        LanSyncController.Step.PEERS -> {
            if (lanSyncUiStep == state.step) return
            lanSyncUiStep = state.step
            lanSyncUiDialog?.dismiss()
            lanSyncUiDialog = null
            val dialog = TvDialog(this, "选择同步设备")
            dialog.message("读取登录信息、设备参数、内容数据和全部设置。读取后仍需在本机确认。")
            dialog.choices(
                state.peers.map { "${it.displayName}  ·  ${it.host}" },
                onChoice = { index -> state.peers.getOrNull(index)?.let(lanSyncController::loadPeer) },
            )
            dialog.button("重新查找", icon = R.drawable.ic_menu_restore) {
                main.post { startLanSyncRole(anchor, sourceOnly = false) }
            }
            dialog.button("取消", icon = R.drawable.ic_menu_close)
            dialog.show(anchor)
        }
        LanSyncController.Step.LOADING, LanSyncController.Step.APPLYING -> {
            if (lanSyncUiStep == state.step) return
            lanSyncUiStep = state.step
            lanSyncUiDialog?.dismiss()
            val dialog = TvDialog(
                this,
                if (state.step == LanSyncController.Step.LOADING) "正在同步" else "正在应用同步数据",
            ).message(state.message)
            dialog.button("取消", icon = R.drawable.ic_menu_close) { closeLanSyncSession() }
            lanSyncUiDialog = dialog
            dialog.show(anchor)
        }
        LanSyncController.Step.READY_TO_APPLY -> {
            if (lanSyncUiStep == state.step) return
            lanSyncUiStep = state.step
            lanSyncUiDialog?.dismiss()
            lanSyncUiDialog = null
            showLanSyncRestoreConfirm(anchor)
        }
        LanSyncController.Step.ERROR -> {
            lanSyncUiDialog?.dismiss()
            lanSyncUiDialog = null
            if (state.message == "没有发现同步设备") {
                closeLanSyncSession()
                showLanSyncNoPeers(anchor)
            } else {
                lanSyncUiStatus?.text = state.error
                if (state.error.isNotBlank()) tvTools.toast(state.error)
            }
        }
        LanSyncController.Step.DONE -> {
            lanSyncUiDialog?.dismiss()
            lanSyncUiDialog = null
            showHint("同步完成，正在重启应用")
            main.postDelayed({
                if (!isDestroyed) AppRelaunch.relaunchAndExit(this)
            }, 800L)
        }
    }
}

private fun TvMainActivity.showLanSyncNoPeers(anchor: View) {
    val dialog = TvDialog(this, "未发现同步设备")
    dialog.message("请确认两台设备连接同一局域网，并同时打开「同步其他设备数据」。")
    dialog.button("重新查找", icon = R.drawable.ic_menu_restore) {
        main.post { startLanSyncRole(anchor, sourceOnly = false) }
    }
    dialog.button("取消", icon = R.drawable.ic_menu_close)
    dialog.show(anchor)
}

private fun TvMainActivity.showLanSyncRestoreConfirm(anchor: View) {
    var login = true
    var device = true
    var content = true
    var settings = true
    val dialog = TvDialog(this, "选择要同步的数据")
    dialog.message("登录、设备与设置会覆盖本机；播放和收藏等记录会合并，并保留进度更高的一项。")
    listOf(
        TvSwitchRow(this, "登录信息", true) { login = it },
        TvSwitchRow(this, "设备参数", true) { device = it },
        TvSwitchRow(this, "播放、收藏等记录", true) { content = it },
        TvSwitchRow(this, "设置信息", true) { settings = it },
    ).forEachIndexed { index, row ->
        dialog.content.addView(
            row,
            LinearLayout.LayoutParams(-1, -2).apply {
                if (index == 0) topMargin = dp(TvStyle.GAP_BLOCK)
                else topMargin = dp(TvStyle.GAP_INLINE)
            },
        )
    }
    dialog.buttonsInRow()
    dialog.button("取消", icon = R.drawable.ic_menu_close)
    dialog.button("同步所选", icon = R.drawable.ic_menu_restore) {
        val selection = DeviceSyncSelection(login, device, content, settings)
        if (!selection.hasAny) {
            tvTools.info("未选择同步内容", "请至少选择一类内容后再同步。", anchor)
        } else {
            lanSyncController.apply(selection)
        }
    }
    dialog.show(anchor)
}

fun TvMainActivity.applyLibraryBackup(payload: BackupData, restoreSettings: Boolean) {
    favoriteMonitor.stop()
    library.restore(payload, restoreSettings)
    applyPerformanceProfile()
    tabState.clear()
    tabFocus.clear()
    catalogFocus = ""
    page = 1
    showCatalog()
    favoriteMonitor.check()
    showHint("记录已合并恢复")
}
