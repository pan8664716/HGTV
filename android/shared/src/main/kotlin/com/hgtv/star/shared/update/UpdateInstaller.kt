// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * SmartTube 式 APK 安装入口。
 *
 * APK 从内部缓存目录通过 FileProvider 暴露成 `content://` URI，
 * 再用 [Intent.ACTION_VIEW] 拉起系统安装器，不创建 PackageInstaller 会话，
 * 也不等待安装回执。
 */
internal object UpdateInstaller {
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    fun install(context: Context, file: File) {
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
        context.startActivity(intent)
    }
}
