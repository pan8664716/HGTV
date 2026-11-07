// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import android.widget.LinearLayout
import com.hgtv.star.core.BackupData
import com.hgtv.star.core.LibraryBackup
import com.hgtv.star.shared.architecture.AppRelaunch
import com.hgtv.star.shared.lan.DeviceSyncPackage
import com.hgtv.star.shared.lan.DeviceSyncPayload
import com.hgtv.star.shared.lan.DeviceSyncSelection
import com.hgtv.star.shared.lan.LanLibrarySyncSession
import com.hgtv.star.shared.lan.LanSyncPeer
import com.hgtv.star.tv.list.showCatalog

private const val LAN_SYNC_SOURCE_TTL_MS = Long.MAX_VALUE

fun MainActivity.closeLanSyncSession() {
    lanSyncToken.incrementAndGet()
    lanSyncSession?.close()
    lanSyncSession = null
}

fun MainActivity.showLanLibrarySync(anchor: View) {
    closeLanSyncSession()
    val dialog = TvDialog(this, "同步其他设备数据")
    dialog.message("请选择这台设备在本次同步中的角色。")
    dialog.choices(
        listOf(
            "我要同步其他设备的数据",
            "其他设备要同步我的数据",
        ),
        onChoice = { index ->
            startLanSyncRole(anchor, sourceOnly = index == 1)
        },
    )
    dialog.button("取消", icon = R.drawable.ic_menu_close)
    dialog.show(anchor)
}

private fun MainActivity.startLanSyncRole(anchor: View, sourceOnly: Boolean) {
    closeLanSyncSession()
    val address = tvTools.lanAddress()
    if (address == null) {
        tvTools.info("同步其他设备数据", "请让两台设备连接同一个局域网（Wi-Fi 或有线网络）后重试。", anchor)
        return
    }
    val encoded = runCatching {
        DeviceSyncPackage.encode(DeviceSyncPackage.capture(this, library))
    }.getOrElse {
        tvTools.info("无法同步", "本机记录暂时无法生成同步数据，请重试。", anchor)
        return
    }

    if (sourceOnly) {
        showLanSyncSource(address, encoded, anchor)
        return
    }
    showLanSyncReceiver(address, encoded, anchor)
}

private fun MainActivity.showLanSyncSource(address: String, encoded: String, anchor: View) {
    val status = TvDialog(this, "本机作为同步源")
    status.message("本机将一直出现在同一局域网的设备列表中。请保持此页面打开，让另一台设备选择「我要同步其他设备的数据」。")
    val state = tvText("正在开启同步源…", 15f, TvStyle.textStrong)
    status.content.addView(
        state,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TvStyle.GAP_INLINE) },
    )
    var downloadedCount = 0
    lateinit var session: LanLibrarySyncSession
    session = LanLibrarySyncSession(
        context = this,
        address = address,
        payload = encoded,
        onPeers = {},
        onError = { message -> if (lanSyncSession === session) state.text = message },
        discoverPeers = false,
        onServing = {
            if (lanSyncSession === session) state.text = "同步源已开启 · 等待其他设备连接"
        },
        onDownloaded = {
            if (lanSyncSession === session) {
                downloadedCount++
                state.text = "已被 $downloadedCount 台设备读取 · 继续等待其他设备"
            }
        },
        ttlMillis = LAN_SYNC_SOURCE_TTL_MS,
    )
    lanSyncSession = session
    status.setOnDismissExtra {
        if (lanSyncSession === session) closeLanSyncSession()
    }
    status.button("停止同步源", icon = R.drawable.ic_menu_close)
    status.show(anchor)
    runCatching { session.start() }.onFailure {
        closeLanSyncSession()
        state.text = "无法开启同步源"
    }
}

private fun MainActivity.showLanSyncReceiver(address: String, encoded: String, anchor: View) {
    val found = linkedMapOf<String, LanSyncPeer>()
    val status = TvDialog(this, "同步其他设备数据")
    status.message("正在通过局域网查找其他设备。请在另一台设备上也打开「同步其他设备数据」。")
    val count = tvText("已发现 0 台设备", 15f, TvStyle.textStrong)
    status.content.addView(
        count,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TvStyle.GAP_INLINE) },
    )
    var keepSession = false
    lateinit var session: LanLibrarySyncSession
    session = LanLibrarySyncSession(
        context = this,
        address = address,
        payload = encoded,
        onPeers = { list ->
            found.clear()
            list.forEach { found[it.serviceName] = it }
            count.text = "已发现 ${found.size} 台设备"
        },
        onError = { message ->
            if (lanSyncSession === session) count.text = message
        },
    )
    lanSyncSession = session
    status.setOnDismissExtra {
        if (!keepSession && lanSyncSession === session) closeLanSyncSession()
    }
    status.button("取消", icon = R.drawable.ic_menu_close)
    status.show(anchor)
    runCatching { session.start() }.onFailure {
        closeLanSyncSession()
        count.text = "无法开启局域网同步"
    }
    main.postDelayed({
        if (lanSyncSession !== session) return@postDelayed
        session.stopDiscovery()
        val list = found.values.toList()
        if (list.isEmpty()) {
            status.dismiss()
            showLanSyncNoPeers(anchor)
        } else {
            keepSession = true
            status.dismiss()
            showLanSyncPeerPicker(list, session, anchor)
        }
    }, 6_000L)
}

private fun MainActivity.showLanSyncNoPeers(anchor: View) {
    val dialog = TvDialog(this, "未发现同步设备")
    dialog.message("没有在局域网里找到已打开同步的 HGTV。请确认两台设备连接同一网络，并同时打开这个功能。")
    dialog.button("重新查找", icon = R.drawable.ic_menu_restore) {
        main.post { startLanSyncRole(anchor, sourceOnly = false) }
    }
    dialog.button("取消", icon = R.drawable.ic_menu_close)
    dialog.show(anchor)
}

private fun MainActivity.showLanSyncPeerPicker(
    peers: List<LanSyncPeer>,
    session: LanLibrarySyncSession,
    anchor: View,
) {
    var peerSelected = false
    val dialog = TvDialog(this, "选择同步设备")
    dialog.message("将读取登录信息、设备参数、内容数据和全部设置。读取后仍需在本机确认，确认后会覆盖本机登录与设备身份。")
    dialog.choices(
        peers.map { "${it.displayName}  ·  ${it.host}" },
        onChoice = { index ->
            peers.getOrNull(index)?.let { peer ->
                peerSelected = true
                session.close()
                if (lanSyncSession === session) lanSyncSession = null
                loadLanSyncBackup(peer, anchor)
            }
        },
    )
    dialog.button("重新查找", icon = R.drawable.ic_menu_restore) {
        main.post { startLanSyncRole(anchor, sourceOnly = false) }
    }
    dialog.button("取消", icon = R.drawable.ic_menu_close)
    dialog.setOnDismissExtra {
        if (!peerSelected && lanSyncSession === session) closeLanSyncSession()
    }
    dialog.show(anchor)
}

private fun MainActivity.loadLanSyncBackup(peer: LanSyncPeer, anchor: View) {
    val request = lanSyncToken.incrementAndGet()
    val loading = TvDialog(this, "正在同步")
    loading.message("正在从 ${peer.displayName} 读取数据…")
    loading.button("取消", icon = R.drawable.ic_menu_close)
    loading.setOnDismissExtra { lanSyncToken.incrementAndGet() }
    loading.show(anchor)
    io.submit {
        val result = runCatching { LanLibrarySyncSession.fetchPayload(peer) }
        main.post {
            if (isDestroyed || request != lanSyncToken.get()) return@post
            loading.dismiss()
            result.onSuccess { showLanSyncRestoreConfirm(it, anchor) }
                .onFailure { tvTools.info("同步失败", it.message.orEmpty().ifBlank { "无法读取其他设备的数据" }, anchor) }
        }
    }
}

private fun MainActivity.showLanSyncRestoreConfirm(payload: DeviceSyncPayload, anchor: View) {
    val dialog = TvDialog(this, "选择要同步的数据")
    dialog.message("登录、设备与设置会覆盖本机；播放和收藏等记录会合并，并保留进度更高的一项。")
    var login = true
    var device = true
    var content = true
    var settings = true
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
        val selection = DeviceSyncSelection(
            login = login,
            device = device,
            content = content,
            settings = settings,
        )
        if (!selection.hasAny) {
            tvTools.info("未选择同步内容", "请至少选择一类内容后再同步。", anchor)
        } else {
            applyLanDeviceSync(payload, selection, anchor)
        }
    }
    dialog.show(anchor)
}

private fun MainActivity.applyLanDeviceSync(
    payload: DeviceSyncPayload,
    selection: DeviceSyncSelection,
    anchor: View,
) {
    favoriteMonitor.stop()
    val loading = TvDialog(this, "正在应用同步数据")
    loading.message("正在恢复所选类别…")
    loading.button("取消", icon = R.drawable.ic_menu_close)
    loading.show(anchor)
    io.submit {
        val result = runCatching { DeviceSyncPackage.restore(this, library, payload, selection) }
        main.post {
            if (isDestroyed) return@post
            loading.dismiss()
            result.onSuccess { saved ->
                if (!saved) {
                    tvTools.info("同步失败", "部分数据未能写入本机，请重试。", anchor)
                    return@onSuccess
                }
                showHint("同步完成，正在重启应用")
                main.postDelayed({
                    if (!isDestroyed) AppRelaunch.relaunchAndExit(this)
                }, 800L)
            }.onFailure {
                tvTools.info("同步失败", it.message.orEmpty().ifBlank { "无法应用其他设备的数据" }, anchor)
            }
        }
    }
}

fun MainActivity.applyLibraryBackup(payload: BackupData, restoreSettings: Boolean) {
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
