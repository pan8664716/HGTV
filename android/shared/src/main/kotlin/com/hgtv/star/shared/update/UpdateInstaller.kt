// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File

/**
 * SmartTube 式 APK 安装入口。
 *
 * APK 从内部缓存目录通过 FileProvider 暴露成 `content://` URI，
 * 再用 [Intent.ACTION_VIEW] 拉起系统安装器，不创建 PackageInstaller 会话，
 * 不预检查“安装未知应用”权限，也不等待安装回执。仅当系统没有可用的
 * 安装器 Activity 时，才回退到 [PackageInstaller] 会话。
 */
internal object UpdateInstaller {
    const val ACTION_INSTALL_STATUS = "com.hgtv.star.shared.update.INSTALL_STATUS"

    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val APK_NAME = "update.apk"

    fun failureMessage(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消安装"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统阻止了安装"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装包与已安装应用冲突"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包与当前设备不兼容"
        PackageInstaller.STATUS_FAILURE_INVALID -> "安装包损坏或签名不匹配"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足，无法安装"
        else -> detail?.takeIf { it.isNotBlank() } ?: "系统安装失败"
    }

    @Suppress("DEPRECATION")
    fun install(context: Context, file: File): Boolean {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.update_provider",
                file,
            )
        } else {
            file.setReadable(true, false)
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun installSession(context: Context, file: File) {
        val manager = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(AppUpdate.PACKAGE)
            setSize(file.length())
            setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
            if (Build.VERSION.SDK_INT >= 23) setOriginatingUid(Process.myUid())
            if (Build.VERSION.SDK_INT >= 24) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                setInstallerPackageName(context.packageName)
                setRequestUpdateOwnership(true)
            }
        }
        val sessionId = manager.createSession(params)
        val session = manager.openSession(sessionId)
        try {
            file.inputStream().use { input ->
                session.openWrite(APK_NAME, 0, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                pendingFlags,
            )
            session.commit(pending.intentSender)
        } catch (error: Exception) {
            runCatching { session.abandon() }
            throw error
        } finally {
            runCatching { session.close() }
        }
    }
}

/**
 * [PackageInstaller] 回退安装的回执。
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_INSTALL_STATUS) return
        val controller = AppUpdateController.get(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        controller.onInstallStatus(status, message)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return

        val confirmation = pendingConfirmation(intent)
        if (confirmation == null) {
            controller.onInstallStatus(
                PackageInstaller.STATUS_FAILURE,
                "系统没有返回安装确认页",
            )
            return
        }
        confirmation
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
        runCatching { context.startActivity(confirmation) }
            .onFailure {
                controller.onInstallStatus(
                    PackageInstaller.STATUS_FAILURE,
                    it.message ?: "无法打开系统安装确认页",
                )
            }
    }

    @Suppress("DEPRECATION")
    private fun pendingConfirmation(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
