// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * 调试面板上的「性能」一行：CPU / GPU / 内存 / 线程 / 温度 / 刷新率。
 *
 * ## 为什么要这一行
 *
 * 「这台电视卡」是一句没法直接查的话：可能是 CPU 一直满载（盒子上还跑着别的服务）、
 * 内存被顶到极限（系统开始杀后台）、GPU 是颗只能解 1080 的老 Mali、
 * 或者机身已经烫到降频。这些在日志里都看不出来，但**一眼就能读数**。
 *
 * ## 为什么单独一个对象
 *
 * 采样要读 `/proc`、还要建一次离屏 EGL 上下文，都是「不能每秒在主线程做一遍」的事。
 * 因此：GPU 名**只在第一次采**（之后纯内存读），CPU 用两次采样之间的差值算占比，
 * 整个 [sample] 只在 [MainActivity] 的 io 线程上跑（见 `DebugOverlay.kt` 的 `startPerfProbe`）。
 *
 * ## 单位
 *
 * * CPU 百分比：**整机**口径（全部核加起来算 100%），进程那一项单独给，便于判断「是这一支在吃」；
 * * 内存：进程 RSS / 整机总内存，两者都用 M（超过 1024M 显示成 G）；
 * * 温度：取所有 `thermal_zone` 里最高的那个可读值，读不到就不显示这一项。
 */
internal object PerfStats {
    /** 采样间隔：CPU 占比要看一段时间内的变化，1 秒抖动太大、3 秒又跟不上。 */
    const val INTERVAL_MS=2_000L
    /** GPU 探测最多试几次（每次都要建一次离屏 EGL 上下文）。 */
    private const val GPU_RETRIES=3
    /**
     * 温度与刷新率隔几次采样读一遍。
     *
     * 温度要扫一遍 `/sys/class/thermal`（盒子上二三十个 zone，每个一次 open+read），
     * 是这一行里最重的两项之一；而机身温度以「秒」为单位变化，每 2 秒读一次没有意义。
     */
    private const val SLOW_EVERY=3

    /** 面板上那一行的值；第一次采样完成之前是「采样中…」。 */
    @Volatile var summary: String=""

    /**
     * GPU 名只探一次（建一次 EGL 上下文是重活）。
     *
     * 名字截到 14 字：`PowerVR Rogue GE8300` 这类长名字会把这一行顶出面板宽度，
     * 后面的温度 / 刷新率就被省略号吃掉了——而那两项同样是要看的。
     */
    @Volatile private var gpu: String?=null
    /** GPU 探测失败过几次：失败不缓存，但最多重试 [GPU_RETRIES] 次（每次都建上下文太重）。 */
    private var gpuAttempts=0

    /**
     * 切分 `/proc` 那几行用的正则。
     *
     * 每次 `split(Regex("\\s+"))` 都会现编一个 `Pattern`：这一行每两秒要走三四个文件，
     * 现编的开销比读文件本身还大。提到常量里，只编一次。
     */
    private val SPACES=Regex("\\s+")

    private var lastTotal=0.0
    private var lastIdle=0.0
    private var lastProc=0.0
    /** 采样轮次：用来决定这一轮要不要读温度这类「重但变化慢」的项。 */
    private var rounds=0
    /**
     * 屏幕**支持的最高**刷新率（面板上 `当前/屏幕` 里后面那个数）。
     *
     * 只算一次：它由 HDMI 协商 / 面板能力决定，运行中不会变。
     * 0 表示没读到（少数盒子 `supportedModes` 为空），此时只报当前值。
     */
    private var screenRate=0.0
    /** 上一次读到的温度（隔轮复用它，面板上不会出现空档）。 */
    private var temperature: String?=null

    /** 采一次样并更新 [summary]。**只能在 io 线程调用**（会读 /proc、建 EGL 上下文）。 */
    @Synchronized fun sample(context: Context): String {
        if(gpu==null && gpuAttempts<GPU_RETRIES) {
            gpuAttempts++
            // 只缓存**真的读到**的名字：EGL 上下文建不起来时（极少数盒子）留着 null 下次再试。
            gpu=gpuName()?.take(14)
        }
        rounds++
        // 当前刷新率每轮现读：动态刷新屏（VRR / 按内容切 24-50-60）会变，
        // 只读一次的话面板会一直停在打开面板那一刻的档位上。
        val refresh=refreshText(context)
        // 温度隔 [SLOW_EVERY] 轮读一次；不读的那几轮沿用上一次的值，面板不会闪空。
        if(rounds%SLOW_EVERY==1) temperature=readTemperature()
        val text=buildString {
            append("CPU ").append(cpuText())
            append(" · 内存 ").append(memoryText(context))
            append(" · 线程 ").append(threadCount())
            // 读不到 GL_RENDERER 时退回 SoC 平台名（`Build.HARDWARE`），至少能区分芯片方案。
            append(" · GPU ").append(gpu ?: "${Build.HARDWARE}/${Build.BOARD}".trim('/').ifBlank { "-" })
            temperature?.let { append(" · ").append(it) }
            refresh?.let { append(" · ").append(it) }
        }
        summary=text
        return text
    }

    // —— CPU ——

    /**
     * 整机占用 + 本进程占用。
     *
     * 用两次采样之间的差值：`/proc/stat` 给的是开机以来的累计节拍，
     * 直接拿它算百分比得到的是「开机以来的平均值」，看不出「刚才那一下是不是满载」。
     * 第一次采样没有上一次可比，只能给平均值——那一轮前面加个 `≈`，不冒充瞬时值。
     */
    private fun cpuText(): String {
        val (total,idle)=procStat() ?: return "-"
        val proc=procTicks() ?: 0.0
        val first=lastTotal<=0.0
        val totalDelta=total-lastTotal
        val idleDelta=idle-lastIdle
        val procDelta=proc-lastProc
        lastTotal=total; lastIdle=idle; lastProc=proc
        if(totalDelta<=0.0) return "-"
        val system=((totalDelta-idleDelta)/totalDelta*100).coerceIn(0.0,100.0)
        val app=(procDelta/totalDelta*100).coerceIn(0.0,100.0)
        // 第一次没有上一次可比：那一轮是「开机以来的平均」，用 `≈` 标一下，
        // 不冒充瞬时值（也省掉「均值」两个字，这一行还要塞下 GPU 与温度）。
        val prefix=if(first) "≈" else ""
        return "%s%.0f%%(本机%.0f%%)".format(prefix,system,app)
    }

    /** `/proc/stat` 第一行：总节拍与空闲节拍（idle + iowait）。 */
    private fun procStat(): Pair<Double,Double>? = runCatching {
        val line=File("/proc/stat").useLines { lines -> lines.firstOrNull { it.startsWith("cpu ") } }
            ?: return@runCatching null
        val parts=line.trim().split(SPACES).drop(1).mapNotNull { it.toDoubleOrNull() }
        if(parts.size<4) return@runCatching null
        val idle=(parts.getOrElse(3) { 0.0 })+(parts.getOrElse(4) { 0.0 })
        parts.sum() to idle
    }.getOrNull()

    /** 本进程累计节拍：`/proc/self/stat` 的第 14、15 项（utime + stime）。 */
    private fun procTicks(): Double? = runCatching {
        val raw=File("/proc/self/stat").readText()
        // 第 2 项是带括号的进程名，里面可能有空格，先跳过它再切。
        val fields=raw.substringAfter(") ").trim().split(SPACES)
        // 跳过进程名之后，utime / stime 分别是第 12、13 项（原第 14、15 项减 2）。
        (fields.getOrNull(11)?.toDoubleOrNull() ?: return@runCatching null)+
            (fields.getOrNull(12)?.toDoubleOrNull() ?: 0.0)
    }.getOrNull()

    // —— 内存 / 线程 ——

    /** 本进程 RSS / 整机总内存。RSS 读 `/proc/self/statm`，避免为一行读数分配一堆对象。 */
    private fun memoryText(context: Context): String {
        val app=rssBytes()
        val info=ActivityManager.MemoryInfo()
        val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        runCatching { manager?.getMemoryInfo(info) }
        return if(info.totalMem>0) "${megabytes(app)}/${megabytes(info.totalMem)}" else megabytes(app)
    }

    private fun rssBytes(): Long = runCatching {
        val pages=File("/proc/self/statm").readText().trim().split(SPACES).getOrNull(1)?.toLongOrNull()
            ?: return@runCatching 0L
        pages*4096L
    }.getOrDefault(0L)

    private fun megabytes(bytes: Long): String = when {
        bytes<=0L -> "-"
        bytes>=1024L*1024*1024 -> "%.1fG".format(bytes.toDouble()/(1024*1024*1024))
        else -> "${bytes/(1024*1024)}M"
    }

    /**
     * 线程数。
     *
     * 用 `useLines` 而不是 `readLines`：`/proc/self/status` 有几十行，我们要的那一行
     * 就在前几行，短路之后不再把整份文件读成一个 List。
     */
    private fun threadCount(): String = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Threads:") }?.substringAfter(':')?.trim()
        }
    }.getOrNull().orEmpty().ifBlank { "-" }

    // —— GPU ——

    /**
     * GPU 型号：建一个 1×1 的离屏上下文读 `GL_RENDERER`。
     *
     * 这是唯一不依赖厂商私有属性的读法；电视盒子上常见结果是 `Mali-G31`、`PowerVR Rogue GE8300`。
     * 返回 null 表示这台机器建不起离屏上下文（极少数盒子），由调用方退回 SoC 平台名。
     */
    private fun gpuName(): String? = runCatching {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version=IntArray(2)
        if(!EGL14.eglInitialize(display,version,0,version,1)) return@runCatching null
        try {
            val configs=arrayOfNulls<EGLConfig>(1); val count=IntArray(1)
            // 参数顺序：display, attribs, attribOffset, configs, configsOffset, size, count, countOffset。
            EGL14.eglChooseConfig(display,intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            ),0,configs,0,1,count,0)
            val config=configs[0] ?: return@runCatching null
            val context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            val surface=EGL14.eglCreatePbufferSurface(display,config,
                intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
            try {
                if(!EGL14.eglMakeCurrent(display,surface,surface,context)) return@runCatching null
                GLES20.glGetString(GLES20.GL_RENDERER)
            } finally {
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                if(surface!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface)
                if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
            }
        } finally { EGL14.eglTerminate(display) }
    }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

    // —— 温度 / 刷新率 ——

    /**
     * 机身温度：扫一遍 `thermal_zone`，取最高的那个**看起来像温度**的值。
     *
     * 各厂商的单位不统一（毫度 / 度都有），因此大于 1000 的按毫度处理；
     * 落在 10–120 之外的一律当噪声丢掉（有的 zone 报的是电压 / 电流）。
     */
    private fun readTemperature(): String? = runCatching {
        val zones=File("/sys/class/thermal").listFiles { file -> file.name.startsWith("thermal_zone") }
            ?: return@runCatching null
        zones.mapNotNull { zone ->
            val raw=File(zone,"temp").takeIf { it.canRead() }?.readText()?.trim()?.toDoubleOrNull()
                ?: return@mapNotNull null
            val value=if(raw>1000) raw/1000 else raw
            value.takeIf { it in 10.0..120.0 }
        }.maxOrNull()
    }.getOrNull()?.let { "%.0f°C".format(it) }

    /**
     * 刷新率：写成 **当前 / 屏幕**（`60/120Hz`）。
     *
     * 只报一个数字会有歧义：同一台机器这一轮 60、下一轮 50，读的人分不清
     * 「屏幕本来就只有 50」还是「掉到了 50」。动态刷新屏（VRR、按内容切 24/25/50/60）
     * 恰恰是「看着卡但不丢帧」最常见的来源，因此两个都必须给：
     * * 当前：每轮现读 `Display.getRefreshRate()`。读的是进程内的 `DisplayInfo` 副本，没有 IPC；
     * * 屏幕：取 `supportedModes` 里的最高值（含每档的 `alternativeRefreshRates`），只算一次。
     *
     * 读不到模式表时退回只报当前值；两个都读不到就不显示这一项。
     */
    private fun refreshText(context: Context): String? = runCatching {
        val manager=context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        @Suppress("DEPRECATION")
        val display=manager?.defaultDisplay ?: return@runCatching null
        if(screenRate<=0.0) screenRate=maxRefreshRate(display)
        @Suppress("DEPRECATION")
        val current=display.refreshRate.toDouble()
        if(current<=0.0) return@runCatching null
        if(screenRate<=0.0) return@runCatching rateText(current)+"Hz"
        rateText(current)+"/"+rateText(screenRate)+"Hz"
    }.getOrNull()

    /**
     * 屏幕能跑到的最高刷新率。
     *
     * `supportedModes` 是 API 23 才有的，低版本（这台项目的 minSdk 是 19）只能放弃这一项。
     * 每一档还要看 `alternativeRefreshRates`：部分电视把 24/25/50 挂在 60Hz 那一档下面，
     * 不看就会把 120Hz 的屏报成 60Hz。
     */
    private fun maxRefreshRate(display: android.view.Display): Double {
        if(Build.VERSION.SDK_INT<23) return 0.0
        val modes=runCatching { display.supportedModes }.getOrNull() ?: return 0.0
        var max=0.0
        modes.forEach { mode ->
            val rate=mode.refreshRate.toDouble()
            if(rate>max) max=rate
            runCatching { mode.alternativeRefreshRates }.getOrNull()?.forEach { alt ->
                val value=alt.toDouble()
                if(value>max) max=value
            }
        }
        return max
    }

    /**
     * 刷新率的写法：整数就写整数（`60`），带小数才保留一位（`59.9`）。
     *
     * 电视上 59.94 / 50 这两种很常见：统一 `%.1f` 会写成 `60.0/60.0`，
     * 统一 `%.0f` 又会把 59.94 与真 60 混成一个数——而「是不是真的 60」正是要看的。
     */
    private fun rateText(rate: Double): String {
        val rounded=Math.round(rate).toDouble()
        return if(Math.abs(rate-rounded)<0.15) rounded.toInt().toString() else "%.1f".format(rate)
    }

    /** 仅供测试：把累计值清掉，下一次采样重新按「开机以来的平均」算。 */
    fun resetForTest() {
        lastTotal=0.0; lastIdle=0.0; lastProc=0.0; summary=""; gpu=null; gpuAttempts=0
        rounds=0; screenRate=0.0; temperature=null
    }

    /** 上一次采样的时刻（`elapsedRealtime`），用来做节流。 */
    @Volatile var sampledAt=0L
    fun due(now: Long = SystemClock.elapsedRealtime()): Boolean = now-sampledAt>=INTERVAL_MS

    /**
     * 面板关掉 / 退到后台：把累计值清空，下次打开重新按「开机以来的平均」起算。
     *
     * [summary] 一起清掉：不清的话下次刚打开面板那 1 秒会显示**上一次**的读数
     * （可能是几分钟前、另一个页面时的 CPU 占比），读数写着当前时间却对不上现场。
     */
    fun reset() {
        lastTotal=0.0; lastIdle=0.0; lastProc=0.0
        sampledAt=0L
        summary=""
    }
}
