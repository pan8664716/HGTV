// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hgtv.star.data.LocalReset

// 本文件是从 MainActivity.kt 拆出来的「清空应用缓存（恢复出厂）」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

/**
 * 设置页里的「清空应用缓存」。
 *
 * 与「手机备份与恢复」不同：那一条是把记录搬到另一台设备，这一条是**把本机恢复成刚装好**——
 * 收藏 / 历史 / 搜索词 / 偏好 / 设备身份 / 登录会话，以及 `Android/data/<包名>`、
 * `/sdcard/.hgtv` 里那几份镜像，全部删掉；下次启动会重新注册设备、重新生成标识。
 *
 * 名字叫「缓存」是用户能理解的说法（反复登录失败时最常用的处置）；确认框里则**如实**
 * 说明它连收藏与记录一起清掉，并写清「不可撤销」——这是唯一一个删完找不回来的操作。
 */
internal fun MainActivity.clearLocalData(anchor: View?) {
    tvTools.confirm(
        "清空应用缓存",
        "用于遇到反复无法登录、账号状态异常、页面数据错乱等情况：清空后应用会重新注册设备、重新生成标识，\n" +
            "多数登录问题都能因此恢复。\n\n" +
            "注意：本机的收藏、观看记录、搜索历史与设置也会一并清空，且无法找回。" +
            "如需保留，请先到「手机备份与恢复」导出备份。\n\n" +
            "清空完成后应用会自动重启，回到第一次安装的样子。",
        anchor,
        danger=true
    ) { performLocalReset() }
}

/**
 * 本次进程里已经为「清除本地数据」要过一次外部存储权限（用户拒绝后不再纠缠）。
 *
 * 与 [requestMirrorStorage] 的那一次分开记：那一条只在启动时问、拿到权限后还要顺带恢复会话；
 * 这里问的目的只有一个——把 `/sdcard/.hgtv` 删掉。
 */
private var resetStorageAsked=false

/**
 * 真正执行清除。
 *
 * 顺序是刻意的：
 *
 * 1. **先停所有会写盘的活**（播放器、收藏检查、更新、预取、封面线程、落盘队列）——
 *    只要还有一个写线程在跑，删掉的目录就可能被它重新建出来；
 * 2. **再删**（后台线程；主线程只做那一次点击的收尾）；
 * 3. **删两遍**，中间隔一小段：三方 SDK 的 `apply()` 是异步落盘的，
 *    第一遍删完可能刚好被它补写回来；
 * 4. **最后重启应用并杀进程**（见 [LocalReset.relaunchAndExit]）。
 *
 * 为什么必须杀进程而不是只清文件：`SharedPreferences` 在进程内按文件名缓存，
 * 文件删掉之后内存里那份仍然在，任何一次提交都会把它原样写回去；杀进程是唯一可靠的办法。
 */
internal fun MainActivity.performLocalReset() {
    if (resetting) return
    // `/sdcard/.hgtv` 在 Android 6–10 上要运行时权限才删得掉（targetSdk 29 + 传统存储模式，
    // 见 manifest）。没授权就先要一次：用户拒绝也照样继续（沙盒与 Android/data 仍然清空），
    // 只是那一份旧镜像可能留在外部存储里——那种情况由 `.reset-pending` 标记兜住。
    if(Build.VERSION.SDK_INT in 23..29 && !resetStorageGranted()) {
        if(!resetStorageAsked) {
            resetStorageAsked=true
            runCatching {
                ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),REQUEST_RESET_STORAGE)
            }
            return
        }
    }
    resetting = true
    // 从这一刻起不再落盘：tick 每秒都会调 saveProgress，队列关掉之后再提交会直接抛异常。
    tvTools.close()
    favoriteMonitor.stop()
    appUpdater.pause()
    mediaSession?.deactivate()
    cancelRecovery()
    cancelPrefetch()
    releasePlayer()
    // releasePlayer 会把 foreground 还原成调用前的值，因此这一行必须放在它后面：
    // tick 下一次进入看到 false 就直接返回，不再重排自己，也不会去碰已经释放的播放器。
    foreground=false
    tvTools.toast("正在清空应用缓存…")

    Thread({
        // 封面线程可能正在写 cache/artwork-v4：等它真的停下再删，否则它会在删完之后
        // 把那个目录重新建出来。两个方法都不碰 View，因此可以在这里调。
        runCatching { artwork.abandonForReset(2000) }
        // 丢掉待写快照并停掉写线程：**不能**走 close()，那一条会先把待写内容冲盘。
        runCatching { library.discardPendingWrites() }
        runCatching { LocalReset.wipe(this) }
        // 三方 SDK 的异步 apply 可能刚好补写回来：隔一小段再扫一遍。
        // `commit()` 本身不可中断，给它留足落盘时间再删第二次。
        runCatching { Thread.sleep(350) }
        // 最后一遍放在主线程、紧贴杀进程：这一遍与 `killProcess` 之间只隔两条语句，
        // 三方 SDK 的异步落盘几乎没有机会在中间插进来。此时大文件（封面缓存）已经删过，
        // 这一遍基本只是扫一遍空目录，不会卡住界面。
        main.post {
            runCatching { LocalReset.wipe(this) }
            // 标记必须在最后一遍删除之后写（见 [LocalReset.markPendingReset]）。
            LocalReset.markPendingReset(this)
            LocalReset.relaunchAndExit(this)
        }
    }, "hgtv-local-reset").start()
}

private fun MainActivity.resetStorageGranted(): Boolean =
    ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED

/** 设置页里那一枚按钮的文案：整栏宽的一行，与其它设置项同一档。 */
internal fun resetLabel() = "清空应用缓存"
