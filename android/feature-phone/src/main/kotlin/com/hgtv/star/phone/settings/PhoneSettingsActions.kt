// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.content.Intent
import com.hgtv.star.core.LibraryBackup
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneUiScale
import com.hgtv.star.shared.ProjectInfo
import com.hgtv.star.shared.data.LocalReset
import com.hgtv.star.shared.ui.TvTheme

internal fun PhoneSettingsActivity.appVersionName(): String = appVersionNameImpl()

internal fun PhoneSettingsActivity.showAboutDialog() {
    PhoneDialog(this, UiText.get(com.hgtv.star.shared.R.string.ui_0034))
        .message(UiText.get(com.hgtv.star.shared.R.string.ui_0171, appVersionName()))
        .message("GitHub：${ProjectInfo.GITHUB}")
        .message(UiText.get(com.hgtv.star.shared.R.string.ui_0022, ProjectInfo.TELEGRAM))
        .message(UiText.get(com.hgtv.star.shared.R.string.ui_0132))
        .button(UiText.get(com.hgtv.star.shared.R.string.ui_0035)) {}
        .show()
}

/** 出厂值：与 BackupSettings 的默认参数逐项对齐。 */
internal fun PhoneSettingsActivity.restoreDefaults() {
    runCatching {
        settingsControllerInstance.restoreDefaults()
        settingsLibrary.autoNext = true
        settingsLibrary.seekStep = 10
        settingsLibrary.autoFullscreen = false
        settingsLibrary.playerTitle = false
        settingsLibrary.playerEpisode = false
        settingsLibrary.playerClock = false
        settingsLibrary.playerSpeed = false
        settingsLibrary.playerPanelTimeout = 6
        PhoneDanmakuSize.set(this, PhoneDanmakuSize.DEFAULT_PERCENT)
        settingsLibrary.launchRecommend = true
        settingsLibrary.episodePageSize = 20
        settingsLibrary.episodeEdgeTurn = true
    }
    PhoneUiScale.reset(this)
    PhoneStyle.applyTheme(TvTheme.DEEP_SEA_BLUE)
    toastImpl("已恢复默认设置")
    recreate()
}

/**
 * 导出：走系统的「创建文档」。
 *
 * 与 TV 的「手机备份与恢复」（局域网把 JSON 传给手机）不同——手机上导出再传给自己没有意义，
 * 因此这里直接落到用户选的文件里（下载目录 / 网盘 / 聊天工具都行）。
 * 用的是 SAF，不需要任何存储权限，4.4 起就有。
 */
internal fun PhoneSettingsActivity.exportBackup() {
    val encoded = runCatching {
        LibraryBackup.encode(settingsLibrary.snapshot())
    }.getOrNull()
    if (encoded == null) {
        toastImpl("本机记录暂时无法导出")
        return
    }
    settingsPendingExport = encoded
    val name = "hgtv-backup-${System.currentTimeMillis() / 1000}.json"
    runCatching {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, name),
            PhoneSettingsActivity.REQUEST_EXPORT
        )
    }.onFailure { toastImpl("这台设备没有文件选择器") }
}

/** 恢复：选一个 JSON，解码后按 TV 的同一套规则合并（设置一起带回来）。 */
internal fun PhoneSettingsActivity.importBackup() {
    runCatching {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"),
            PhoneSettingsActivity.REQUEST_IMPORT
        )
    }.onFailure { toastImpl("这台设备没有文件选择器") }
}

/**
 * 清空本机数据：与 TV 的 performLocalReset 同一条路（LocalReset）。
 *
 * 手机上少两步：不释放播放器（这个页面没有播放器）、不弹存储权限（targetSdk 29 的
 * 传统存储模式下 LocalReset.wipe 已经覆盖沙盒与外部私有目录；/sdcard/.hgtv 删不掉时
 * 会留在 .reset-pending 标记后面，下次启动不会再被捞回来）。
 */
internal fun PhoneSettingsActivity.performReset() {
    LocalReset.resetting = true
    runCatching { settingsLibrary.discardPendingWrites() }
    Thread({
        runCatching { LocalReset.wipe(this) }
        runCatching { Thread.sleep(350) }
        runOnUiThread {
            runCatching { LocalReset.wipe(this) }
            LocalReset.markPendingReset(this)
            LocalReset.relaunchAndExit(this)
        }
    }, "hgtv-phone-reset").start()
}
