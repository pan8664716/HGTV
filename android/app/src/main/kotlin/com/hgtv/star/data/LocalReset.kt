// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import com.hgtv.star.MainActivity
import java.io.File

/**
 * 本机数据的「恢复出厂」：把应用自己留下的所有东西删干净。
 *
 * 删的不只是缓存——设备身份、登录会话、收藏 / 历史 / 设置都在这几处：
 *
 * 1. **沙盒**：`files/`、`cache/`、`shared_prefs/`、`databases/`、`no_backup/`；
 * 2. **外部私有目录**：`Android/data/<包名>/`（`getExternalFilesDir` 与 `getExternalCacheDir` 的父目录）；
 * 3. **自建公共目录**：`/sdcard/.hgtv/`（会话镜像，卸载不删的那一份）。
 *
 * 为什么删完必须杀进程：Android 在进程内按文件名缓存了 `SharedPreferences` 实例，
 * 只删 XML 文件的话下一次 `getSharedPreferences` 拿到的仍是内存里那份旧值，
 * 并且任何一次提交都会把旧文件重新写出来；会话、设备标识这些对象也都还在内存里，随时会被写回。
 * 杀进程是唯一可靠、且不依赖反射的办法。
 * 杀之前先把主界面重新拉起来（见 [relaunchAndExit]）：用户看到的就是
 * 「应用自己重启并回到第一次安装的状态」。
 */
object LocalReset {
    /** 与 [SessionMirror] 共用的「刚清除过」标记文件名（见 [markPendingReset]）。 */
    const val RESET_MARKER=".reset-pending"

    /**
     * 正在清空本机数据（从点下确认到进程被杀）。
     *
     * 为什么需要它：删除是「删两遍 + 隔 350 ms」，而**共享内存里的 `SharedPreferences`
     * 实例不受删文件影响**——上一次 `commit()` 之后系统内存里那份仍然在，
     * 任何一次 `apply()` 都会把它原样写回刚清空的目录。
     *
     * 网络回调正好是这类写入方：心跳每 10 分钟一次、`SessionInterceptor` 每收到一个
     * `Set-Cookie` / `X-Tt-Token` 都会回写 `native-session-v1`。删完到杀进程之间那约 1 秒里
     * 只要命中一次，`account-cookie` / `session-token` / `registered-cookies` 就复活了，
     * 重启后用户看到的还是「登录着」或者「设备身份半新半旧」。
     *
     * 因此：这条置位之后，所有会话写盘入口一律丢弃（见 `AppSessionStore` 的每个写方法）。
     * 只在真正开始删之前置位（权限被拒而提前返回时**不能**置位，否则这次操作等于什么都没做）。
     */
    @Volatile var resetting=false

    /**
     * 删除全部本机数据。返回删不掉的路径（仅用于日志；正常应为空）。
     *
     * 必须在所有写盘任务停下之后调用（见 `Library.discardPendingWrites` 与
     * `ArtworkLoader.abandonForReset`），否则刚删掉的目录会被还在排队的写入重新建出来。
     */
    fun wipe(context: Context): List<String> {
        val app = context.applicationContext
        val failures = mutableListOf<String>()

        fun remove(target: File?) {
            if (target == null) return
            val ok = runCatching { if (target.exists()) target.deleteRecursively() else true }
                .onFailure { Log.w("HGTV", "Local reset cannot delete $target", it) }
                .getOrDefault(false)
            if (!ok) failures += target.absolutePath
        }

        // 沙盒：只删这几类目录，**不要**整份删除 dataDir——里面的 lib 是指向系统
        // nativeLibraryDir 的链接，跟过去删会把安装包里的 so 一起删掉。
        val dataDir = File(app.applicationInfo.dataDir)
        val sandbox = mutableListOf(
            app.filesDir,
            app.cacheDir,
            File(dataDir, "shared_prefs"),
            File(dataDir, "databases")
        )
        // getNoBackupFilesDir 是 API 21 才有的；minSdk 19 上不能直接调。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            sandbox += app.noBackupFilesDir
        }
        sandbox.forEach { dir -> dir?.listFiles()?.forEach(::remove) }

        // 外部私有目录：每个存储卷上的 Android/data/<包名> 一起删（files 与 cache 都在里面）。
        // 先清空自己的两个子目录，再试着删父目录：Android 11+ 上父目录可能被系统拒绝删除，
        // 那时至少内容已经清空，不会留下设备身份 / 会话镜像那一份 JSON。
        val external = mutableListOf<File>()
        if (hasMultiVolumeDirs) {
            runCatching { app.getExternalFilesDirs(null) }.getOrNull()?.filterNotNull()?.let { external += it }
            runCatching { app.getExternalCacheDirs() }.getOrNull()?.filterNotNull()?.let { external += it }
        } else {
            // 这两个「多卷」版本是 API 19 才有的：4.2 / 4.3 上直接调会抛 NoSuchMethodError。
            // 不能用 `SDK_INT` 判——线上有把版本号报成 19、框架其实是 4.2 的盒子
            // （见 `compat/Platform.kt` 里 `hasIsAttachedToWindow` 的注释）。
            runCatching { app.getExternalFilesDir(null) }.getOrNull()?.let { external += it }
            runCatching { app.externalCacheDir }.getOrNull()?.let { external += it }
        }
        val externalDirs = external.distinctBy { it.absolutePath }
        externalDirs.forEach { dir ->
            dir.listFiles()?.forEach(::remove)
            runCatching { dir.delete() }
        }
        externalDirs.mapNotNull { it.parentFile }.distinctBy { it.absolutePath }.forEach(::remove)

        // 自建公共目录：卸载不删的那一份会话镜像就在这里。
        runCatching { File(Environment.getExternalStorageDirectory(), ".hgtv") }.getOrNull()?.let(::remove)

        return failures
    }

    /**
     * 在沙盒里留一个「刚清除过」的标记。
     *
     * 为什么需要：`/sdcard/.hgtv` 在用户拒绝外部存储权限时删不掉，而它是**读优先级最高**的
     * 那一份镜像——下次启动会用它把刚清掉的设备身份与登录会话原样救回来。
     * 这个标记让 [SessionMirror] 在下次启动时**跳过镜像恢复**，并顺手再试着删一次那份旧文件；
     * 删干净之后标记自己消失，之后一切照常（新身份会被写进新的镜像）。
     *
     * 必须在**最后一遍删除之后**写：写早了会被自己删掉。
     */
    fun markPendingReset(context: Context) {
        runCatching {
            val dir = context.applicationContext.filesDir ?: return
            dir.mkdirs()
            File(dir, RESET_MARKER).writeText("1")
        }.onFailure { Log.w("HGTV", "Local reset cannot write its marker", it) }
    }

    /**
     * 重启应用并结束当前进程。
     *
     * 顺序必须是「先 startActivity、再杀进程」：此刻应用还在前台，这次启动是允许的；
     * `startActivity` 是同步的 Binder 调用，返回到这里时请求已经交给系统，
     * 之后即使本进程被杀，系统仍会把主界面拉起来。
     *
     * 不用闹钟延迟启动：Android 10 起后台启动 Activity 受限制，闹钟到点时应用已在后台，
     * 那次启动可能被系统直接丢掉。
     */
    fun relaunchAndExit(context: Context) {
        val app = context.applicationContext
        runCatching {
            val intent = Intent(app, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            app.startActivity(intent)
        }.onFailure { Log.w("HGTV", "Local reset cannot relaunch", it) }
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/**
 * 框架里有没有多卷版本的 `getExternalFilesDirs()` / `getExternalCacheDirs()`（API 19 才有）。
 *
 * 只探一次，理由同 `compat/Platform.kt` 里那几个探测：`SDK_INT` 会被盒子报高，
 * 反射探到的才是真实框架。
 */
private val hasMultiVolumeDirs: Boolean = runCatching {
    Context::class.java.getMethod("getExternalFilesDirs", String::class.java)
}.isSuccess
