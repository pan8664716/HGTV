// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.compat.isMeteredConnection
import com.hgtv.star.tv.TvStyle

// 「调试模式」：设置里打开后，右上角常驻一块现场面板。
//
// 为什么做成开关而不是「黑屏就自动铺一块」：自动铺的那一块会在正常播放时也可能误触发，
// 而这块面板的读者是**要给客服/开发描述问题的人**——让他自己决定什么时候开、什么时候关，
// 比我们猜「这一刻算不算故障」可靠得多。
//
// 面板只读、不抢焦点、不吃按键：电视上方向键照样落到内容上，开着调试也能正常用。
// 内容分两块：
// * 播放页：解码器、编码、分辨率帧率、首帧/丢帧/欠载/解码错、播放状态与缓冲；
// * 其它页：这一页在干什么（搜索 / 登录 / 接口）、最近几条失败与网络状态。
// 两者都带设备与版本，用户拍一张照就能定位。

/** 面板刷新间隔：状态里的秒数要跟着走，1 秒够用。 */
private const val DEBUG_TICK_MS=1_000L
/**
 * 内容没变时的退避间隔。
 *
 * 面板每 1 秒重建一遍行数据、比较一遍文字，本身很轻，但「一点不影响界面」的要求下
 * 静态页面（首页、设置、我的）不该每秒醒一次：连续 [DEBUG_IDLE_BACKOFF] 轮没有变化之后
 * 改到 2 秒，再 [DEBUG_IDLE_DEEP] 轮改到 5 秒。播放页位置每秒都在变，永远走 1 秒这一档。
 */
private const val DEBUG_TICK_IDLE_MS=2_000L
private const val DEBUG_TICK_SLOW_MS=5_000L
private const val DEBUG_IDLE_BACKOFF=3
private const val DEBUG_IDLE_DEEP=8

/**
 * 面板上那几个「读系统状态」的值：**缓存几秒**，不每秒去问一次。
 *
 * 它们各自都是实打实的开销，而且每秒都不会变：
 * * `isMeteredConnection()` 是一次 Binder IPC 到 ConnectivityManager（播放页一行里还要问两次）；
 * * `deviceRegistered()` 要读三次 SharedPreferences，还会把注册下发的 Cookie JSON **重新解析一遍**。
 *
 * 每秒各问一次在电视盒子上是能感觉到的（尤其播放时），而它们的变化以「分钟」计。
 * 5 秒足够跟上「刚注册成功」「刚切到移动网络」这类事件，代价是面板最多滞后 5 秒。
 */
private const val DEBUG_ENV_TTL_MS=5_000L

/**
 * 「性能」那一行的标头。
 *
 * 单独提成常量：它有两个用处——面板按标签找这一行做**定向刷新**（见 [updateSampledRows]），
 * 以及 [refreshDebugOverlay] 判断「这一行的变化算不算内容变化」。
 */
private const val PERF_LABEL="性能"

internal object DebugEnv {
    /**
     * 两项**各自**记时间戳。
     *
     * 不能共用一个：`metered` 先被问到时会把时间戳刷新，紧接着问 `registered` 就会被
     * 判成「还在有效期内」而直接返回初始的 false——面板上永远是「设备 未注册」。
     */
    private var meteredAt=0L
    private var metered=false
    private var registeredAt=0L
    private var registered=false

    private fun fresh(now: Long,at: Long)=at>0L && now-at in 0..DEBUG_ENV_TTL_MS

    fun metered(activity: MainActivity): Boolean {
        val now=SystemClock.elapsedRealtime()
        if(fresh(now,meteredAt)) return metered
        metered=runCatching { activity.connectivity.isMeteredConnection() }.getOrDefault(false)
        meteredAt=now
        return metered
    }

    fun registered(activity: MainActivity): Boolean {
        val now=SystemClock.elapsedRealtime()
        if(fresh(now,registeredAt)) return registered
        registered=runCatching { activity.library.deviceRegistered() }.getOrDefault(false)
        registeredAt=now
        return registered
    }

    /**
     * 注册刚完成 / 换了网络时由调用方主动作废。
     *
     * 缓存本来最多滞后 5 秒，但这两个时刻面板正被用户盯着看（刚点完「刷新」、刚切网络），
     * 让他等 5 秒才看到「已注册」会以为又失败了。
     */
    fun invalidate() { meteredAt=0L; registeredAt=0L }
}

/**
 * 面板底色：**近黑、约 35% 不透明**（`0x59` = 89/255 ≈ 0.35，即约 65% 透明）。
 *
 * 面板要同时做两件事——读上面的字、看底下的画面还在不在。全不透明读得最清楚，
 * 但会把「黑屏 / 花屏 / 卡在某一帧」这些现象本身盖住；用户看现场照片时，
 * 第一眼要判的恰恰是**画面到底有没有**。因此按用户的两次反馈一路调淡：
 * 0.60 → 0.50 → 0.35，现在底下的画面基本一眼可见。
 *
 * 文字不靠底色保证对比度了：值那一列是纯白 `F4F3F1`，压在亮画面上仍可读；
 * 再往下调（<0.25）就要给文字加描边才不糊，那时候观感与现在的单据感也不一样了。
 */
private const val DEBUG_PANEL_BG=0x590D0D0D.toInt()

/**
 * 「TLS」那一行的标头。
 *
 * 这一行现在同时带网速（`支持 1.0~1.3 · 启用 1.3/1.2 · ↓1.2M/s ↑8K/s`）：
 * 一条连接协商成什么样、这条连接现在跑了多少，是同一个问题的两半，
 * 分两行会把面板拉高，读的时候眼睛还要跨行配对。
 *
 * 因为网速每轮都在跳，这一行与 [PERF_LABEL] 一样**不算面板内容变化**
 * （否则空闲退避永远起不来），由 [updateSampledRows] 定向换字。
 */
private const val TLS_LABEL="TLS"

/**
 * 面板宽度（设计单位）。
 *
 * 560 是「贴住右边、又能把异常链读完」的折中：早先 490 时，`问题` / `错误` 那两行
 * 每行只放得下约 60 个等宽字符，两行就把 `PlaybackException ... Caused by: ...`
 * 的后半截省略掉了，用户只能看到一个开头。1280 画布上占 44%。
 */
private const val DEBUG_PANEL_WIDTH=560

/** 面板贴住右上角的边距（设计单位）：只留一点呼吸位，读起来就是「钉在角上」。 */
private const val DEBUG_PANEL_MARGIN=14

/** 正文字号（设计单位）。10.5 是「软解那一行 5 种编码仍能挤进一行」的上限。 */
private const val DEBUG_TEXT_SIZE=10.5f

/**
 * 每条「标头」那一列的固定宽度（设计单位）。
 *
 * 恰好容下最长的三个汉字（「搜索源」「清晰度」）：10.5 号等宽字下 3 字 ≈ 33，
 * 留到 36 才不折行。早先给 84，每个 2 字标头（机型 / 硬解）后面都拖着一大块空白，
 * 值被推到面板右半边，看起来像另一列独立的内容。
 */
private const val DEBUG_ROW_LABEL_W=36

/**
 * 播放页同样贴右上角（用户要求「进播放器也要靠右上角」）。
 *
 * 播放画面的时间 / 网速现在都在**左上角那一条**里，与右上角的面板互不遮挡，
 * 因此不再需要把时钟往左挪（原来那套 [shiftPlayerClock] 已随右上角时钟一起删掉）。
 */
private const val DEBUG_PANEL_PLAYER_TOP=DEBUG_PANEL_MARGIN

/** 最近事件保留条数：多了会把播放页那块面板顶出屏幕，少了又看不出「刚才发生了什么」。 */
private const val DEBUG_LOG_LIMIT=40

/**
 * 从清晰度字符串里抠出档位数字（`1080P（兼容资源）` → `1080`）。
 *
 * 提成常量：原先这行在 `playerDebugRows()` 里写 `Regex(...)`，而那个函数**每秒**跑一遍，
 * 等于每秒现编一个 `Pattern`。编译一次的正则放这里，之后只做匹配。
 */
private val QUALITY_TIER=Regex("([0-9]{3,4})P")

/**
 * 最近发生的事：网络、接口、登录、播放失败。
 *
 * 只在**打开调试模式**时才写（见 [DebugLog.record] 的判据由调用方给），
 * 因此正常使用时它连一次字符串拼接都没有。
 */
internal object DebugLog {
    private val entries=ArrayDeque<String>()

    /**
     * 调试模式是否打开。
     *
     * [record] 的调用点里有每一条 HTTP 状态（见 `NetLog`），关掉时必须**先判这一位就返回**，
     * 否则正常使用也会为每一条网络日志拼一次字符串。
     */
    @Volatile var enabled=false

    /**
     * 记一条近况。
     *
     * [force] 用于**播放失败**这一类：它们不常发生（一次启动最多几条），而用户往往是
     * 「先看到错误码、之后才去开调试模式」。关着就丢的话，打开面板时那条已经没了，
     * 用户报的错误码对不上任何现场。因此这几条无条件留下，其余照旧只在调试模式里记。
     */
    @Synchronized fun record(tag: String,line: String,force: Boolean=false) {
        if(!enabled && !force) return
        val clean=line.replace('\n',' ').take(220)
        entries.addLast("${clock()} $tag $clean")
        while(entries.size>DEBUG_LOG_LIMIT) entries.removeFirst()
    }

    /**
     * 取最近 [count] 条。
     *
     * 只拷要用的这几条：原先 `entries.toList().takeLast(count)` 每一轮都把整圈（40 条）
     * 复制成一个新 List 再丢掉 35 条，而面板播放页是每秒问一次。
     */
    @Synchronized fun lines(count: Int): List<String> {
        if(count<=0 || entries.isEmpty()) return emptyList()
        val from=(entries.size-count).coerceAtLeast(0)
        val out=ArrayList<String>(entries.size-from)
        for(index in from until entries.size) out.add(entries[index])
        return out
    }

    @Synchronized fun clear() = entries.clear()

    /**
     * 日志时刻：**墙上时间**（`HH:mm:ss`）。
     *
     * 早先用的是 `SystemClock.elapsedRealtime()`（开机以来的时长，按 `mm:ss` 显示）：
     * 面板上出现的是一串与用户手表完全无关的数字，报「几点开始卡的」时对不上，
     * 分钟位还会在 60 分钟处绕回 00。近况是给客服对时用的，必须是真实时刻。
     */
    private fun clock(): String = WALL_CLOCK.format(java.util.Date())

    private val WALL_CLOCK=java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.CHINA)
}

/**
 * 本机 TLS 能力（面板「TLS」那一行）。
 *
 * 由 `HgtvTls` 在第一次建 socket 时通过 `NetLog` 报一次，`MainActivity` 的 NetLog 出口
 * 把它抄到这里。抄一份而不是每次从「近况」里翻：面板第一屏就要看到，
 * 不能指望那条日志还在 40 条环形缓冲里。
 */
internal object DebugTls {
    @Volatile var summary: String=""
}

/** 调试模式开着没有。`library` 是 lateinit，读它之前必须先兜住「还没建好」的那一刻。 */
private fun MainActivity.debugModeOn(): Boolean = runCatching { library.debugMode }.getOrDefault(false).also { DebugLog.enabled=it }

/** 打开调试模式时，把面板挂到 root 上并开始刷新；关掉时摘掉它。 */
internal fun MainActivity.syncDebugOverlay() {
    // 用 runCatching 而不是 `::library.isInitialized`：lateinit 的 `isInitialized`
    // 在扩展函数里取不到（Kotlin 只允许在声明类内部用），这里包一层即可。
    if(debugModeOn()) showDebugOverlay() else {
        // 关掉时连「记不记」一起关：`NetLog` 每条网络日志都会调 [DebugLog.record]。
        DebugLog.enabled=false
        hideDebugOverlay()
    }
}

private fun MainActivity.showDebugOverlay() {
    if(isDestroyed) return
    val panel=debugPanel
    if(panel==null || panel.parent==null) {
        hideDebugOverlay()
        val next=buildDebugPanel()
        debugPanel=next
        // 挂在 root 最后：压在页面内容之上。它不是焦点控件、也不可点，因此不会影响操作。
        root.addView(next,FrameLayout.LayoutParams(dp(DEBUG_PANEL_WIDTH),-2,Gravity.TOP or Gravity.END).apply {
            rightMargin=dp(DEBUG_PANEL_MARGIN)
            topMargin=dp(if(screen=="player") DEBUG_PANEL_PLAYER_TOP else DEBUG_PANEL_MARGIN)
        })
    }
    debugPanel?.visibility=View.VISIBLE
    startCapabilityProbe()
    startPerfProbe()
    debugIdleTicks=0
    refreshDebugOverlay()
    if(debugTick==null) {
        val tick=object: Runnable {
            override fun run() {
                if(debugTick!==this) return
                if(!debugModeOn()) { debugTick=null; return }
                // 后台 / 息屏时不做任何事：面板用户看不见，采样与重排都是白烧。
                // 回到前台之后这一轮自己恢复（`foreground` 变回 true），不必谁去唤醒。
                if(!foreground) { main.postDelayed(this,DEBUG_TICK_IDLE_MS); return }
                // 性能采样跟着面板一起走：面板在，就按间隔补采；关掉之后这里不再被调用。
                startPerfProbe()
                val changed=refreshDebugOverlay()
                // 退避：连续几轮都没有变化（静态页面、暂停中的播放页）就降频，
                // 把「每秒一次重排 + 比较」压到「两秒一次」「五秒一次」。
                debugIdleTicks=if(changed) 0 else debugIdleTicks+1
                main.postDelayed(this,debugTickInterval())
            }
        }
        debugTick=tick
        main.postDelayed(tick,DEBUG_TICK_MS)
    }
}

/** 当前该用的刷新间隔：有变化就 1 秒，静态一会儿 2 秒，长时间不动 5 秒。 */
private fun MainActivity.debugTickInterval(): Long = when {
    debugIdleTicks>=DEBUG_IDLE_DEEP -> DEBUG_TICK_SLOW_MS
    debugIdleTicks>=DEBUG_IDLE_BACKOFF -> DEBUG_TICK_IDLE_MS
    else -> DEBUG_TICK_MS
}

internal fun MainActivity.hideDebugOverlay() {
    debugTick?.let { main.removeCallbacks(it) }
    debugTick=null
    // 面板关掉：CPU 的累计基线一起清掉。留着的话下次打开第一轮会拿「很久以前的累计值」
    // 去算差值，得到一个毫无意义的百分比。
    PerfStats.reset()
    NetSpeed.reset()
    val panel=debugPanel ?: return
    debugPanel=null; debugBody=null
    debugRowLabels=emptyList(); debugRowValues.clear()
    (panel.parent as? android.view.ViewGroup)?.removeView(panel)
}


/** 页面重建（`root.removeAllViews()`）之后重新挂上；没打开调试模式时什么都不做。 */
internal fun MainActivity.reattachDebugOverlay() {
    // 先按正常路径摘掉：`root.removeAllViews()` 之后它本来就不在树上了，但换集复用播放器
    // 的那条路不会清 root，不摘就会留下上一块面板、再叠一块新的。
    hideDebugOverlay()
    if(debugModeOn()) showDebugOverlay()
}

private fun MainActivity.buildDebugPanel(): LinearLayout {
    val panel=column().apply {
        setPadding(dp(10),dp(8),dp(12),dp(10))
        // 背景固定**半透明**（见 [DEBUG_PANEL_BG]，约 35% 不透明）：
        // 用户报「黑屏 / 花屏 / 卡住」时，面板盖住的那块画面本身就是证据，
        // 底色越淡越能一眼看出「到底有没有画面」。代价是这块区域每帧多做一次 alpha 混合，
        // 量级远小于一次视频解码，为能看清现场值得。
        background=TvStyle.shape(this@buildDebugPanel,DEBUG_PANEL_BG,0x1FF4F3F1,TvStyle.RADIUS_CARD,1)
        isFocusable=false
        isFocusableInTouchMode=false
        isClickable=false
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    panel.addView(text("调试 · HGTV ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",14f,TvStyle.playerText).apply {
        setTypeface(null,Typeface.BOLD)
        // 与下面每一条的标头同一条起始线：整块面板从左边一列对齐。
        gravity=Gravity.START
        textAlignment=View.TEXT_ALIGNMENT_VIEW_START
    },lp(-1,-2))
    val body=column().apply { isFocusable=false }
    debugBody=body
    panel.addView(body,lp(-1,-2).apply { topMargin=dp(4) })
    return panel
}

/** 面板里的一条：标头 + 值。两者分开成两列，值才能真的贴右边。 */
private data class DebugRow(val label: String,val value: String)

/** 播放位置 / 时长：`分:秒`（超过一小时不折成小时，写成 `70:12` 更贴近剧集时长）。 */
private fun clockText(millis: Long): String {
    val seconds=(millis.coerceAtLeast(0L))/1000
    val minutes=seconds/60
    val rest=seconds%60
    // 手拼而不是 `"%02d:%02d".format(...)`：播放页每秒要算三遍（位置 / 时长 / 缓冲），
    // 一次 format 就要 new 一个 Formatter。这里只是两个两位数字，用不着那套东西。
    val out=StringBuilder(7)
    if(minutes<10) out.append('0')
    out.append(minutes).append(':')
    if(rest<10) out.append('0')
    out.append(rest)
    return out.toString()
}

/**
 * 把当前现场写进面板。播放页与其它页各一套内容，同一块面板。
 *
 * 返回 true 表示这一轮**真的改了屏幕上的字**（或重建了行结构）。调用方用它做退避：
 * 静态页面上一连几轮都没变化时，就不必每秒醒一次（见 [DEBUG_TICK_IDLE_MS]）。
 */
internal fun MainActivity.refreshDebugOverlay(): Boolean {
    val body=debugBody ?: return false
    // 推荐页是一条独立的播放链路（`screen == "recommend"`，见 MainActivityRecommend.kt），
    // 但用户看到的就是一个播放画面：面板必须给同一套播放现场（剧集 / 状态 / 解码 / 画面 / 帧数），
    // 不能退回「页面」那一套。早先只判 `player`，进推荐后整块面板就变成了首页那种页面信息。
    val rows=if(screen=="player" || screen=="recommend") playerDebugRows() else pageDebugRows()
    val labels=rows.map { it.label }
    var changed=false
    if(labels!=debugRowLabels) {
        changed=true
        // 行的组成变了（换页 / 出现错误 / 多了一条近况）才重建视图；每秒只改文字，不重建。
        debugRowLabels=labels
        debugRowValues.clear()
        body.removeAllViews()
        rows.forEach { row ->
            val line=row().apply { isFocusable=false }
            // 标头固定一列、左对齐：面板里所有标头从同一条竖线起头，值紧跟其后。
            line.addView(text(row.label,DEBUG_TEXT_SIZE,TvStyle.playerDim).apply {
                gravity=Gravity.START
                textAlignment=View.TEXT_ALIGNMENT_VIEW_START
                maxLines=1
                ellipsize=TextUtils.TruncateAt.END
                setTypeface(Typeface.MONOSPACE)
            },lp(dp(DEBUG_ROW_LABEL_W),-2))
            val value=text("",DEBUG_TEXT_SIZE,TvStyle.playerText).apply {
                // 值紧跟标头、左对齐：一行里先看标头再看值，读起来是一句话，
                // 不再让值飘到面板右侧（那样眼睛要在两列之间来回跳）。
                gravity=Gravity.START
                textAlignment=View.TEXT_ALIGNMENT_VIEW_START
                // 5 行：异常链（「播放 解码失败 · PlaybackException: ... Caused by: ...」）
                // 两行放不下，早先的 2 行会把结论后面的原因整段省略掉，只剩一个开头。
                // 只有 `问题` / `错误` / `近况` 这几条真的会用满，短行仍然只占一行。
                maxLines=5
                ellipsize=TextUtils.TruncateAt.END
                setTypeface(Typeface.MONOSPACE)
            }
            line.addView(value,lp(0,-2).apply { weight=1f; leftMargin=dp(8) })
            body.addView(line,lp(-1,-2).apply { topMargin=dp(2) })
            debugRowValues.add(value)
        }
    }
    // 只在真的变了才写：每秒都 setText 会让整棵树每秒标脏一次（无障碍事件永远不空闲，
    // 也白白多一次测量 + 布局）。秒数没跳的那几秒这里一行都不写。
    rows.forEachIndexed { index,row ->
        val view=debugRowValues.getOrNull(index) ?: return@forEachIndexed
        if(view.text.toString()!=row.value) {
            view.text=row.value
            // 「性能」「TLS」**不算内容变化**：CPU 百分比与网速每轮都在跳，算进去的话
            // 退避永远起不来，静态页面也会一直按 1 秒醒。这两行有独立的刷新路径
            // （见 [updateSampledRows]）。
            if(row.label!=PERF_LABEL && row.label!=TLS_LABEL) changed=true
        }
    }
    return changed
}

/**
 * 记一条「用户此刻能看见的问题」。
 *
 * 面板上的 [DebugLog] 近况是流水账（每条网络、每条异常都记），这一条只留**最后一件
 * 真的让页面空掉 / 搜不出东西**的事：用户报「这页打不开」时，一眼就能看到原因与发生时刻。
 */
internal fun MainActivity.noteDebugProblem(text: String) {
    val clean=text.replace('\n',' ').trim().take(400)
    if(clean.isEmpty()) return
    // 同一件事反复触发（重试按钮连按、页面重建）只更新时间戳，不再往近况里堆重复行。
    if(clean==debugProblem) {
        debugProblemAt=SystemClock.elapsedRealtime(); debugProblemPage=pageKey()
        refreshDebugOverlay(); return
    }
    debugProblem=clean
    debugProblemAt=SystemClock.elapsedRealtime()
    debugProblemPage=pageKey()
    DebugLog.record("问题",clean)
    refreshDebugOverlay()
}

/**
 * 这一页已经正常出内容了，把上一次的页面级问题擦掉。
 *
 * 不擦的话，用户按一次「重试」已经好了，面板上还挂着一条「首页加载失败」——
 * 拍给客服的照片就会指错方向。历史仍留在「近况」里。
 */
internal fun MainActivity.clearDebugProblem() {
    if(debugProblem.isEmpty()) return
    debugProblem=""; debugProblemAt=0L; debugProblemPage=""
    refreshDebugOverlay()
}

/**
 * 错误页出现时记一条「这页为什么空」。
 *
 * [stage] 与日志用的是同一套键（`content_home` / `content_search` …），这里翻成用户读得懂的
 * 页面名；后面跟日志里那句常量标题与真实异常链，面板上一行就够定位了。
 */
internal fun MainActivity.notePageFailure(stage: String,problem: Throwable) {
    val page=when(stage) {
        "content_home" -> "首页"
        "content_rank" -> "榜单"
        "content_search" -> "搜索"
        "content_detail" -> "详情"
        "library_load" -> "本机记录"
        else -> stage
    }
    if(stage=="content_search") {
        // 搜索失败：用接口现场判读（设备未注册 / 业务码 / 网络），比异常类名有用得多。
        noteDebugProblem("搜索失败 · ${com.hgtv.star.core.SearchDiag.explain()}")
        return
    }
    noteDebugProblem("${page}加载失败 · ${com.hgtv.star.Diagnostics.chainOf(problem)}")
}

/** 当前页面的标识：问题只在自己那一页显示，翻页之后不该继续挂着。 */
private fun MainActivity.pageKey(): String = listOf(screen,tab,collection.orEmpty(),query).joinToString("|")

/**
 * 搜索页的「问题」：**按接口状态判读**，不是抄页面文案。
 *
 * 页面上「没有找到相关剧集」这一句，可能是「确实没匹配」，也可能是「设备没注册」
 * 「接口回业务码」「官网 500」。用户要报的是后者，因此这里读 [SearchDiag] 的现场，
 * 由它给出结论（关键词一致时才算这一页的）。
 */
private fun MainActivity.searchProblemText(): String {
    val outcome=com.hgtv.star.core.SearchDiag.last ?: return "还没有搜索过"
    if(outcome.keyword!=query.trim()) return "还没有搜索过「${query.trim().take(12)}」"
    return com.hgtv.star.core.SearchDiag.explain(outcome)
}

/** 「问题」那一条的值：优先显示播放侧的问题，其次才是页面级问题。 */
private fun MainActivity.problemText(primary: String=""): String {
    if(primary.isNotBlank()) return primary
    if(debugProblem.isBlank() || debugProblemPage!=pageKey()) return "无"
    val age=((SystemClock.elapsedRealtime()-debugProblemAt).coerceAtLeast(0))/1000
    val whenText=if(age<60) "${age}s前" else "${age/60}m前"
    return "$whenText $debugProblem"
}

/**
 * 本机解码能力：**硬解一行、软解一行**。
 *
 * 分开列的理由：同一台机器上 HEVC 可能是硬解、VP9 只有软解，混成一行读不出「哪个会掉帧」；
 * 而软解 1080 在盒子上基本必掉帧，这正是「某部剧黑屏 / 卡顿」最常见的成因。
 */
private fun MainActivity.capabilityRows(): List<DebugRow> {
    val cap=debugCapability ?: return listOf(DebugRow("硬解","探测中…"),DebugRow("软解","探测中…"))
    return listOf(DebugRow("硬解",cap.hardware),DebugRow("软解",cap.software))
}

/**
 * 性能那一行：CPU / 内存 / 线程 / GPU / 温度 / 刷新率。
 *
 * 放在 TLS **之上**（用户要求的顺序）：这几项是「这台机器本身够不够用」的现场，
 * 比一次握手协商的结果更常被问起。采样在 io 线程上做，主线程只读 [PerfStats.summary]。
 */
private fun perfRow()=DebugRow(PERF_LABEL,PerfStats.summary.ifBlank { "采样中…" })

/**
 * 只给「性能」「网速」这两行换字，**不重排整块面板**。
 *
 * 采样落地（io → 主线程）之后走这一条。若仍走 [refreshDebugOverlay]，
 * 等于每 2 秒把二十来行重新拼一遍、逐字比较一遍；而这里只需要两个 TextView 换字。
 * 面板上还没有这些行（刚打开、行结构还没建）时什么都不做，乱了就等下一轮采样。
 */
private fun MainActivity.updateSampledRows() {
    setRowText(PERF_LABEL,PerfStats.summary)
    // TLS 与网速同一行：速率变了要连 TLS 那一半一起重给，否则会被旧值覆盖回去。
    setRowText(TLS_LABEL,tlsNetValue())
}

/** 按标头找到那一行的值视图，文字真的变了才写。找不到（换页了 / 还没建）就跳过。 */
private fun MainActivity.setRowText(label: String,value: String) {
    if(value.isBlank()) return
    val index=debugRowLabels.indexOf(label)
    if(index<0) return
    val view=debugRowValues.getOrNull(index) ?: return
    if(view.text.toString()!=value) view.text=value
}

/**
 * 启动性能采样：每 [PerfStats.INTERVAL_MS] 采一次。
 *
 * 采样要读 `/proc`、第一次还要建一次离屏 EGL 上下文读 GPU 名——都不能在主线程做。
 * 因此这里只在「到点了」的时候丢一个任务给 io，落地后让面板自己刷一轮。
 * 面板关掉之后 tick 就停了，采样也跟着停（没有别的调用点）。
 */
private fun MainActivity.startPerfProbe() {
    if(!PerfStats.due()) return
    PerfStats.sampledAt=android.os.SystemClock.elapsedRealtime()
    io.submit {
        val text=runCatching { PerfStats.sample(this) }.getOrDefault("")
        // 网速与性能同一次任务里采：两者都是 2 秒一轮，合成一次 io 提交，少一次线程切换。
        runCatching { NetSpeed.sample() }
        if(text.isBlank()) return@submit
        main.post { if(!isDestroyed && debugPanel!=null) updateSampledRows() }
    }
}

/** 面板第一行：机型 + 系统。放在最上面，是因为客服第一句总是问「什么设备」。 */
private fun deviceRow()=DebugRow("机型","${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT})")

/**
 * 形态那一行：这一轮按手机 / 平板 / 电视里的哪一档在跑，以及那一次判定的明细。
 *
 * 明细（触屏 / 短边 / 对角线 / 分数 / 命中的证据）是给「判错了」这种情况用的：
 * 只有一行结论时，除了让用户去设置里手动改一档，没有任何线索可查；
 * 带上分数与命中项，一眼就能看出是哪条证据把电视认成了平板。
 */
private fun MainActivity.deviceFormRow(): DebugRow {
    val profile=deviceProfile ?: return DebugRow("形态","检测中…")
    val manual=library.deviceForm
    val source=if(manual=="phone"||manual=="tablet"||manual=="tv") "手动" else "自动"
    return DebugRow("形态","${deviceMode().label}（$source） · ${profile.summary}")
}

/**
 * TLS 那一行：**握手结果 + 当前网速**，同一行（用户要求）。
 *
 * 还没建过连接时 TLS 一半给一句明确的「未建立」，而不是空白；
 * 网速第一轮没有基线，写「采样中…」，2 秒后换成真实速率。
 */
private fun tlsRow()=DebugRow(TLS_LABEL,tlsNetValue())

/** 上面那一行的值：`支持 1.0~1.3 · 启用 1.3/1.2 · ↓1.2M/s ↑8K/s`。 */
private fun tlsNetValue(): String =
    DebugTls.summary.ifBlank { "尚未建立连接" }+" · "+NetSpeed.summary.ifBlank { "采样中…" }

private fun MainActivity.startCapabilityProbe() {
    if(debugCapability!=null || debugCapabilityLoading) return
    debugCapabilityLoading=true
    io.submit {
        val summary=com.hgtv.star.data.PlaybackCapability.debugSummary()
        main.post {
            debugCapability=summary
            debugCapabilityLoading=false
            refreshDebugOverlay()
        }
    }
}

/**
 * 近况：标签做标头、内容做值，时间放在值的最前面。
 *
 * 标头那一列只有 84dp，把「09:39 网络」整个塞进去会折成两行；拆开之后标头统一是
 * 网络 / 账号 / 搜索 / 播放这几个词，右边缘依旧是齐的。
 */
private fun recentRows(count: Int): List<DebugRow> = DebugLog.lines(count).map { line ->
    val parts=line.split(' ',limit=3)
    if(parts.size>=3) DebugRow(parts[1],parts[0]+" "+parts[2])
    else DebugRow("近况",line)
}

private fun MainActivity.playerDebugRows(): List<DebugRow> {
    val d=PlaybackDiag
    val data=detail
    val title=data?.series?.title.orEmpty().take(14)
    val p=player
    val state=when(p?.playbackState) {
        com.google.android.exoplayer2.Player.STATE_IDLE -> "idle"
        com.google.android.exoplayer2.Player.STATE_BUFFERING -> "buffering"
        com.google.android.exoplayer2.Player.STATE_READY -> "ready"
        com.google.android.exoplayer2.Player.STATE_ENDED -> "ended"
        else -> "none"
    }
    val position=p?.currentPosition ?: 0L
    val duration=(p?.duration ?: 0L).coerceAtLeast(0L)
    val buffered=(p?.bufferedPosition ?: 0L).coerceAtLeast(0L)
    val init=if(d.initMs>=0) " ${d.initMs}ms" else ""
    val res=if(d.width>0 && d.height>0) "${d.width}x${d.height}" else "?"
    val fps=if(d.frameRate>0f) "@"+Math.round(d.frameRate) else ""
    val bitrate=if(d.bitrate>0) " ${d.bitrate/1000}k" else ""
    // 错误码只在**确实有当前问题**时才带出去（见下面的「问题」行）：没有问题时它属于历史。
    val code=if(d.lastCode!=0) " · 错误码 ${d.lastCode}" else ""
    // 帧龄 = 距上一帧上屏过了多久。它比「状态 ready」更能说明画面是不是真的在走：
    // 「无逐帧回调」表示这台设备不走 `VideoFrameMetadataListener`，此时停帧看门狗会让路。
    val frameAge=if(d.lastFrameAt<=0L) "无逐帧回调"
        else "${(SystemClock.elapsedRealtime()-d.lastFrameAt).coerceAtLeast(0)/1000}s 前"
    val codecs=d.codecs.take(70)
    // 实际播放的清晰度（`720P` / `1080P（兼容资源）` 里的那个数字）。
    // 与设置里的「默认清晰度上限」不是一回事：CDN 只给到 720P 时，上限 1080P 也放不出 1080P，
    // 这一条要能让用户自己看出来（见下面的「默认上限」行与「问题」行）。
    val actualQuality=QUALITY_TIER.find(d.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val maxQuality=runCatching { library.maxQuality }.getOrDefault(0)
    val qualityProblem=when {
        actualQuality<=0 -> ""      // 还没取到地址 / 正在缓冲：不报
        maxQuality<=0 -> ""
        // CDN 里最高只到 actualQuality：用户设的 1080P 根本没有这个档。
        actualQuality<maxQuality -> "CDN 中没有 ${maxQuality}P 质量的视频，实际 ${actualQuality}P"
        // 反向：连「≤上限」的档都没有，被抬到更高的兼容资源（清晰度行会带「兼容资源」）。
        actualQuality>maxQuality -> "CDN 中没有 ≤${maxQuality}P 的视频，回退到 ${actualQuality}P"
        else -> ""
    }
    return buildList {
        // **第一行固定是「页面 · 账号 · 设备 · 网络 · 搜索源」**（与首页 / 详情页同一份）：
        // 播放界面记问题时，先要说清在哪个播放页、登没登录、什么网络。
        add(pageStatusRow())
        // 随后：机型 → 本机解码（硬解 / 软解）→ 性能 → TLS（含网速）。这些与「哪一集」无关，
        // 是用户报问题时紧随其后的现场。
        add(deviceRow())
        add(deviceFormRow())
        addAll(capabilityRows())
        // 性能在 TLS 之上（与页面版同一位置）：两个播放界面的面板顺序保持一致。
        add(perfRow())
        add(tlsRow())
        add(DebugRow("剧集","$title · ${d.episodeIndex+1}/${d.episodeCount}集 · ${d.quality.ifBlank { "-" }}"))
        // 推荐页多一条「这是流里的第几部」：推荐页与正片长得一样，出问题时先要分清是哪条链路。
        if(screen=="recommend") add(DebugRow("推荐","第 ${recommendIndex+1}/${recommendItems.size} 部 · 第 ${recommendPage} 页${if(recommendHasMore) "" else " · 已到底"}"))
        // 位置 / 时长用 `分:秒`（超过一小时会自然写成 `70:12`），与播放界面上的时间同一套写法；
        // 早先直接印秒数（`123s/2341s`），读的人要自己换算，很容易被当成「时间显示错了」。
        add(DebugRow("状态","$state · ${clockText(position)}/${clockText(duration)} · 缓冲 ${clockText(buffered)}"))
        add(DebugRow("解码","${d.decoderMode.ifBlank { "-" }} ${d.decoderName.ifBlank { "-" }}$init"))
        add(DebugRow("编码",d.mime.ifBlank { "-" }+(if(codecs.isNotBlank()) " · $codecs" else "")))
        add(DebugRow("画面","$res$fps$bitrate · 帧龄 $frameAge"))
        // 「首帧」与「问题」行同一套判据：有逐帧心跳也算出过画面，
        // 否则会出现「帧龄 0s 前（帧在走）· 首帧=否」这种自相矛盾的读数。
        // 帧龄同样按这套判据给：注入模式下没有逐帧记账，那时不能显示成「0s 前」。
        add(DebugRow("帧数","首帧=${if(pictureUpNow()) "是" else "否"} · 丢帧=${d.droppedFrames} · 欠载=${d.underruns} · 解码错=${d.codecErrors}"))
        // 「网络」不再单独一行（用户要求去掉）：第一行 `页面` 里已经写了 `网络 wifi/移动`，
        // 这里是同一件事的第二份，既占高度又让人以为两处可能不一致。
        // 默认上限与实际播放的档位并排：正片与推荐页（两个播放界面）都走这一份行，
        // 因此两边都能看到「设置里是 1080P、实际放的是 720P」。
        add(DebugRow("默认上限","${if(maxQuality>0) "${maxQuality}P" else "-"} · 实际 ${if(actualQuality>0) "${actualQuality}P" else d.quality.ifBlank { "-" }}"))
        // 「问题」只写**现在仍然成立**的事（见 [livePlaybackProblem]）：画面已经回来了就不再挂着
        // 「有声音无画面」这类旧结论——那正是用户反馈的「面板显示得不对」。
        val live=livePlaybackProblem()
        add(DebugRow("问题",problemText(listOf(live+if(live.isBlank()) "" else code,qualityProblem)
            .filter { it.isNotBlank() }.joinToString(" · "))))
        // 「错误」那一行跟着「问题」走：没有当前问题时它也只是历史，留在「近况」里就够了。
        if(live.isNotBlank() && d.lastError.isNotBlank() && d.lastError!="-") add(DebugRow("错误",d.lastError.take(400)))
        addAll(recentRows(5))
    }
}

/**
 * 面板的第一行：**页面 · 账号 · 设备 · 网络 · 搜索源**。
 *
 * 五项都很短，各占一行会把面板拉得很高，又让左边一列标头占掉一半宽度；
 * 合成一行之后仍然**每一项都带标头**（`账号 已登录` 这样读），
 * 一眼扫过去是一条完整的状态句。
 *
 * 三个页面（首页 / 详情 / 两个播放界面）共用这一份，因此**播放页的第一行也是它**：
 * 用户在播放界面报问题时，最先要说的就是「在哪个页面、登没登录、设备注册没、什么网络、
 * 搜索走的是哪一路」，这些不该藏到面板中间去。
 */
private fun MainActivity.pageStatusRow(): DebugRow {
    val page=when(screen) {
        "detail" -> "详情"
        "player" -> "播放"
        "recommend" -> "推荐"
        "catalog" -> when {
            collection!=null -> "记录"
            else -> when(tab) {
                TAB_SEARCH -> "搜索"
                TAB_CATEGORY -> "分类"
                TAB_RANK -> "榜单"
                TAB_SETTINGS -> "设置"
                TAB_PROFILE -> "我的"
                else -> "首页"
            }
        }
        else -> screen
    }
    val signed=runCatching { repository.signedIn }.getOrDefault(false)
    // 这两项走 5 秒缓存（见 [DebugEnv]）：每秒各问一次是 Binder + JSON 解析，而它们不会每秒变。
    val registered=DebugEnv.registered(this)
    val metered=DebugEnv.metered(this)
    val searching=screen=="catalog" && tab==TAB_SEARCH && query.isNotBlank()
    return DebugRow("页面",buildString {
        append(page)
        if(searching) append(" · 「").append(query.take(12)).append('」')
        append(" · 账号 ").append(if(signed) "已登录" else "未登录")
        append(" · 设备 ").append(if(registered) "已注册" else "未注册")
        append(" · 网络 ").append(if(metered) "移动" else "wifi")
        append(" · 搜索源 ").append(library.searchSource.label)
    })
}

private fun MainActivity.pageDebugRows(): List<DebugRow> {
    val searching=screen=="catalog" && tab==TAB_SEARCH && query.isNotBlank()
    return buildList {
        // 与播放页同一套固定前几行：页面状态 → 机型 → 本机解码（硬 / 软）→ TLS。
        add(pageStatusRow())
        add(deviceRow())
        add(deviceFormRow())
        addAll(capabilityRows())
        // 性能在 TLS 之上：先看「这台机器够不够用」，再看一次握手协商的结果。
        add(perfRow())
        add(tlsRow())
        add(DebugRow("清晰度","上限 ${library.maxQuality}P"))
        add(DebugRow("问题",if(searching) searchProblemText() else problemText()))
        // 近况放在最后：前面的固定条目是「现在的现场」，这一段是「刚才发生过什么」。
        val recent=recentRows(8)
        if(recent.isEmpty()) add(DebugRow("近况","无")) else addAll(recent)
    }
}
