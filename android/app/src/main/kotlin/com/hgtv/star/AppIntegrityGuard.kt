// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import java.lang.ref.WeakReference
import java.security.MessageDigest

/**
 * 累计前台运行三十分钟后校验一次包名与签名。
 *
 * 这是二次打包的初级防线，不是不可绕过的 DRM：攻击者仍可修改客户端代码。
 * 它的目标是让普通改包、换签名的安装包在长时间使用后明确暴露，而不是静默运行。
 */
internal object AppIntegrityGuard : Application.ActivityLifecycleCallbacks {
    private const val CHECK_AFTER_MS = 30L * 60L * 1000L
    private const val OFFICIAL_PACKAGE = "com.hgtv.star"
    private const val OFFICIAL_CERT_SHA256 =
        "27da90725c2bcec6c334096ee438877b480875dfaa1339b5dbedc5fd9fcee019"
    private const val DOWNLOAD_URL = "https://github.com/pan8664716/HGTV/releases/latest"
    private const val HEX = "0123456789abcdef"

    private val main = Handler(Looper.getMainLooper())
    private val resumedActivities = linkedSetOf<Activity>()
    private var currentActivity: WeakReference<Activity>? = null
    private var foregroundSince = 0L
    private var foregroundElapsed = 0L
    private var installed = false
    private var checked = false
    private var invalid = false
    private var dialog: AlertDialog? = null
    private var dialogActivity: WeakReference<Activity>? = null

    private val checkRunnable = Runnable { checkAndShow() }

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
        val enteringForeground = resumedActivities.isEmpty()
        resumedActivities += activity
        if (enteringForeground) foregroundSince = SystemClock.elapsedRealtime()
        when {
            checked && invalid -> showInvalidDialog(activity)
            !checked -> scheduleCheck()
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
        leaveForeground(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
        leaveForeground(activity)
        if (dialogActivity?.get() === activity) {
            dialog?.dismiss()
            dialog = null
            dialogActivity = null
        }
    }

    private fun leaveForeground(activity: Activity) {
        if (!resumedActivities.remove(activity) || resumedActivities.isNotEmpty()) return
        if (foregroundSince > 0L) {
            foregroundElapsed += SystemClock.elapsedRealtime() - foregroundSince
            foregroundSince = 0L
        }
        main.removeCallbacks(checkRunnable)
    }

    private fun elapsedForeground(): Long =
        foregroundElapsed + if (foregroundSince > 0L) {
            SystemClock.elapsedRealtime() - foregroundSince
        } else {
            0L
        }

    private fun scheduleCheck() {
        main.removeCallbacks(checkRunnable)
        if (checked || resumedActivities.isEmpty()) return
        val remaining = (CHECK_AFTER_MS - elapsedForeground()).coerceAtLeast(0L)
        if (remaining == 0L) main.post(checkRunnable)
        else main.postDelayed(checkRunnable, remaining)
    }

    private fun checkAndShow() {
        if (checked || resumedActivities.isEmpty()) return
        val activity = currentActivity?.get() ?: return
        checked = true
        invalid = !runCatching { isOfficial(activity) }.getOrDefault(false)
        if (invalid) showInvalidDialog(activity)
    }

    private fun isOfficial(context: Context): Boolean {
        val packageName = context.packageName
        if (packageName != OFFICIAL_PACKAGE) return false
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNATURES,
        )
        if (info.packageName != OFFICIAL_PACKAGE) return false
        @Suppress("DEPRECATION")
        val signatures = info.signatures
        if (signatures.isNullOrEmpty()) return false
        return signatures.any { signature ->
            sha256(signature.toByteArray()).equals(OFFICIAL_CERT_SHA256, ignoreCase = true)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val text = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            text.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return text.toString()
    }

    private fun showInvalidDialog(activity: Activity) {
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) {
            return
        }
        if (dialog?.isShowing == true && dialogActivity?.get() === activity) return
        dialog?.dismiss()
        val next = AlertDialog.Builder(activity)
            .setTitle("非正版应用")
            .setMessage(
                "当前应用不是正版应用，可能被二次打包并修改了包名或签名。\n\n" +
                    "请前往 GitHub Releases 下载正版：\n$DOWNLOAD_URL"
            )
            .setCancelable(false)
            .create()
        next.setCanceledOnTouchOutside(false)
        next.setOnKeyListener { _, keyCode, _ ->
            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
        }
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                dialogActivity = null
            }
        }
        dialog = next
        dialogActivity = WeakReference(activity)
        runCatching { next.show() }.onFailure {
            if (dialog === next) {
                dialog = null
                dialogActivity = null
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
