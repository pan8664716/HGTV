// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import java.io.File

/**
 * **调试包专用**的故障注入：把「解码失败」这类只能等用户碰上的事，变成可以按需复现的一条命令。
 *
 * 为什么需要它：解码失败的前提是「这台机器的解码器恰好不行」，而开发机 / 模拟器几乎都行。
 * 没有这个开关，上报链路就只能靠线上用户去验证——那等于没验证过。
 *
 * 用法（整条链路都在 adb 里，不需要改代码、不需要重装）：
 *
 * ```
 * adb shell run-as com.hgtv.star sh -c 'echo DECODER_BOGUS > files/debug-fault'
 * # 进任意一集播放，失败后看友盟 U-APM 后台（或 adb logcat）
 * adb shell run-as com.hgtv.star rm files/debug-fault     # 恢复
 * ```
 *
 * 可选值见 [Mode]。文件不存在时一切照常，因此这个类在正常调试里是零影响。
 *
 * **只有 debug 包会读它**：判据是 `BuildConfig.DEBUG`，正式包里整个对象被 R8 裁掉
 * （调用点都是 `if(BuildConfig.DEBUG)` 包着的常量条件）。
 */
internal object DebugFaults {
    enum class Mode {
        /** 选一颗不存在的解码器 → `DecoderInitializationException` → 4001。 */
        DECODER_BOGUS,
        /** 一颗解码器都不给 → `DecoderQueryException` → 4002 / 4005。 */
        DECODER_NONE,
        /** 本地准备阶段直接失败（容器头 / 索引）→ `playback_prepare`。 */
        PREPARE_FAIL,
        /** 详情页取内容直接失败 → `content_detail`（验证错误页那条上报链路）。 */
        CONTENT_FAIL,
        /** 画面照常出，但不给「已出帧」记账 → 起播看门狗报「有声音无画面」。 */
        STALL_FRAME,
        /** 启动后主线程卡死 60 秒 → 真 ANR，由系统与 U-APM 各自捕获。 */
        ANR,
        /** 让 native 段直接崩一次 → 验证 U-APM 的 native 采集链路。 */
        NATIVE_CRASH
    }

    private const val FILE="debug-fault"

    /**
     * 启动期故障：ANR / Native 崩溃只能由进程自己造，且**必须晚一点**——太早会把
     * 上报 SDK 自己的初始化一起打断，测的就不是上报链路了。
     *
     * 只读一次（启动时），因为这两种故障会把进程带走，改文件后重开应用即可。
     */
    fun startupFault(app: android.content.Context): Mode? = mode(app)?.takeIf {
        it==Mode.ANR || it==Mode.NATIVE_CRASH
    }

    /** 每次调用都读一次：改了开关文件不必重启应用，下一集就生效。 */
    fun mode(app: android.content.Context): Mode? = if(!BuildConfig.DEBUG) null else runCatching {
        val file=File(app.filesDir,FILE)
        if(!file.exists()) return@runCatching null
        Mode.entries.firstOrNull { it.name.equals(file.readText().trim(),true) }
    }.getOrNull()
}
