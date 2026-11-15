// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.hgtv.star.core.LibraryBackup
import com.hgtv.star.shared.architecture.AppRelaunch
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.compat.DeviceFormSelection

/** 横屏设置页使用沉浸式：状态栏和底部系统导航都隐藏，只保留一小条分区导航。 */
@Suppress("DEPRECATION")
internal fun PhoneSettingsActivity.applyLandscapeImmersive() {
    if (!settingsLandscape) return
    if (android.os.Build.VERSION.SDK_INT >= 21) {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
    window.decorView.systemUiVisibility = (
        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let { controller ->
            controller.hide(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
            controller.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    window.decorView.post {
        if (!settingsLandscape || isFinishing || isDestroyed) return@post
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        }
    }
}

internal fun PhoneSettingsActivity.handleActivityResultImpl(
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
) {
    if(requestCode==PhoneSettingsActivity.REQUEST_DEVICE_FORM) {
        if(resultCode==Activity.RESULT_OK &&
            data?.getBooleanExtra(DeviceFormSelection.EXTRA_CHANGED,false)==true
        ) {
            val selected=DeviceForm.ofStored(
                data?.getStringExtra(DeviceFormSelection.EXTRA_SELECTED_FORM).orEmpty()
            )
            toastImpl("正在切换到 ${selected?.label ?: "所选"} 界面")
            AppRelaunch.switchDeviceForm(this)
        } else {
            renderSettings()
        }
        return
    }
    if (resultCode != Activity.RESULT_OK) return
    val uri: Uri = data?.data ?: return
    when (requestCode) {
        PhoneSettingsActivity.REQUEST_EXPORT -> {
            val payload = settingsPendingExport ?: return
            settingsPendingExport = null
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
                    ?: error("no stream")
            }.isSuccess
            toastImpl(if (ok) "备份已导出" else "写入失败")
        }
        PhoneSettingsActivity.REQUEST_IMPORT -> {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(LibraryBackup.MAX_BYTES + 1)
                    var read = 0
                    while (read < buffer.size) {
                        val n = input.read(buffer, read, buffer.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buffer, 0, read, Charsets.UTF_8)
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                toastImpl("读不到这个文件")
                return
            }
            val backup = runCatching { LibraryBackup.decode(text) }.getOrNull()
            if (backup == null) {
                toastImpl("备份格式不对")
                return
            }
            runCatching { settingsLibrary.restore(backup, true) }
                .onSuccess {
                    toastImpl("记录已合并恢复")
                    renderSettings()
                }
                .onFailure { toastImpl("恢复失败") }
        }
    }
}
