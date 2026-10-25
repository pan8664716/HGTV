// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import com.hgtv.star.compat.activityManager
import com.hgtv.star.compat.connectivityManager
import com.hgtv.star.compat.isMeteredConnection

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.net.ConnectivityManager
import android.widget.*
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.*
import java.util.concurrent.Executors
import com.hgtv.star.data.AppUpdater
import com.hgtv.star.data.ArtworkLoader
import com.hgtv.star.player.DanmakuView
import com.hgtv.star.player.EpisodePanel
import com.hgtv.star.data.FavoriteMonitor
import com.hgtv.star.list.HomeScreen
import com.hgtv.star.data.Library
import com.hgtv.star.player.PlaybackMenu
import com.hgtv.star.tv.PosterCard
import com.hgtv.star.tv.SeekIndicator
import com.hgtv.star.tv.Soft
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.player.TvMediaSession
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.TvTheme
import com.hgtv.star.tv.TvThemeStore
import com.hgtv.star.tv.TvTools
import com.hgtv.star.list.gridFocusIndex
import com.hgtv.star.list.nearGridEnd
import com.hgtv.star.list.prefetchNextPage
import com.hgtv.star.list.showCatalog
import com.hgtv.star.list.updateBackToTop
import com.hgtv.star.compat.Cancellable
import com.hgtv.star.compat.attachedToWindow
import com.hgtv.star.compat.lowEndTv

/**
 * 全站焦点原则：**不主动抢焦点**。
 *
 * 只有**用户自己的动作**可以指定焦点落点：
 *
 * * 点 / 按了一枚芯片（榜单、分类、我的页的四个栏目）→ 焦点留在那一枚上；
 * * 点了「展示更多」→ 焦点落到新出现的第一张卡；
 * * 点了播放 / 收藏 / 完整简介 → 焦点按这次动作的去向落（进播放器、回到同一枚按钮）；
 * * 显式带着落点意图的重建（`focusNav` / `focusType` / `focusFirstCardOnLanding`）。
 *
 * 除此之外的一切——列表重建、异步数据落地（账号数据、封面、剧集数）、空页兜底、
 * 位置恢复、弹窗关闭——**一律不许动焦点**。这类场合用 [ensureFocus] / [ensureFocusView]：
 * 只有当焦点**真的丢了**（持有焦点的控件已被摘掉）才补一个确定的落点，
 * 否则保持用户正按着的地方不动。
 *
 * 违反这条原则的症状是同一类：用户没按方向键，焦点却跳走了——「切栏目焦点跑进内容区」
 * 「收藏后焦点跑到上面」「内容落地后焦点从芯片跳到第一张卡」都是它。
 */


class MainActivity: Activity() {
    /*
     * 本类的代码按功能拆在 MainActivity* 这一组文件里（本文件保留：状态字段、生命周期、
     * 返回键与系统回调）。拆出去的那部分写成了 MainActivity 的扩展成员，因此它们在同一个
     * 包、同一个模块内互相可见——被别的文件用到的字段 / 方法在这里一律是 internal，
     * 只有确实只在本文件内部使用的才是 private（例如 goBack / confirmExit / tick）。
     *
     * 也就是说：**出现在别的 MainActivity* 文件里的东西不能改回 private**，否则编译不过。
     * 反过来说，看一眼可见性就知道这个字段有没有跨文件共享，排错时省一步全局搜索。
     */
    // 主题可以在设置里随时切换，因此这几个高频令牌写成 getter 而不是构造期快照：
    // 换主题之后重建页面，新控件立刻读到新色值（旧控件的背景 Drawable 会被整页重建换掉）。
    internal val bg get()=TvStyle.background
    internal val white get()=TvStyle.text
    internal val muted get()=TvStyle.muted
    /** 设置页里主题外观分组的四行：换主题后重建页面时按 [TvTheme] 重新填充。 */
    internal val themeRows=linkedMapOf<TvTheme,TvButton>()
    internal val main=Handler(Looper.getMainLooper())
    internal val io=Executors.newFixedThreadPool(3)
    // 网络失败诊断：客户设备上「首页空白」时，这行日志给出主机名与异常类型。
    //
    // 注意：正式包里 `Log.i` 被 R8 整段删掉（见 proguard-rules.pro），只写 logcat 等于线上
    // 什么都不剩。因此这三处出口都另外走一遍 [Diagnostics]——诊断层会在异常发生时
    // 把现场带进 U-APM 的自定义异常，平时不产生任何网络开销。
    init {
        com.hgtv.star.core.NetLog.log={ line ->
            android.util.Log.i("HGTV-NET",line)
            Diagnostics.trace("HGTV-NET",line)
            // 调试模式打开时，网络层的每一行都进右上角那块面板：
            // 「搜索接口暂不可用」这类问题在页面上只剩一句中文，真正的原因（哪个主机、什么码）只在这里。
            // TLS 那一条单独归到「TLS」标头：它是平台支持的协议版本与真正启用的那两档，
            // 与「某次请求挂了」不是一回事，混在「网络」里读起来像是又一次失败。
            if(line.startsWith("TLS ")) {
                // TLS 能力是**固定一行**（面板第三行），不再抄进「近况」：
                // 同一条信息出现两次，用户会以为那是两次不同的连接结果。
                DebugTls.summary=compactTls(line.removePrefix("TLS "))
            } else if(!line.contains("interrupted",ignoreCase=true) && !line.contains("Canceled",ignoreCase=true)) {
                // 「线程被打断 / 主动取消」不记：切页、换剧、退出时取消域会打断工作线程，
                // 每一手都会留下一条 `InterruptedIOException: interrupted`。
                // 这类不是网络故障，记下来只会把真正的失败挤出那 40 条近况。
                DebugLog.record("网络",line)
            }
        }
    }
    internal val repository=ContentRepository().apply {
        // 写接口（收藏 / 进度 / 历史）的诊断日志：`core` 是纯 Kotlin 模块，日志出口由这里接上。
        writeLog={ line ->
            android.util.Log.i("HGTV",line)
            Diagnostics.trace("HGTV-WRITE",line)
        }
    }
    /**
     * 把 `可用版本=[TLSv1, TLSv1.1, TLSv1.2, TLSv1.3] 启用=[TLSv1.3, TLSv1.2]`
     * 压成 `支持 1.0~1.3 · 启用 1.3/1.2`。
     *
     * 面板上这一行要占满一行（用户要求前三行各自一行），原串 40 多个字符会折成两行；
     * 版本号本身是唯一的判读信息（「只支持 1.0」就是连不上的原因），因此只压格式、不丢信息。
     */
    private fun compactTls(raw: String): String {
        fun versions(part: String): List<String> =
            Regex("TLSv(\\d(?:\\.\\d)?)").findAll(part).map { it.groupValues[1] }.toList()
        val supported=versions(raw.substringBefore("启用"))
        val enabled=versions(raw.substringAfter("启用",raw))
        if(supported.isEmpty()) return raw.take(60)
        // `TLSv1` 在安卓上就是 TLS 1.0（Java 的旧命名），直接写「1」会读成 1.0 还是 1.x 分不清。
        fun nice(v: String)=if(v=="1") "1.0" else v
        val span=if(supported.size==1) nice(supported.first()) else "${nice(supported.first())}~${nice(supported.last())}"
        return "支持 $span · 启用 ${enabled.map(::nice).joinToString("/").ifEmpty { "无" }}"
    }

    internal val networkCleanup=Executors.newSingleThreadExecutor()
    /**
     * 会话被服务端判死（`data.error_name == session_expired`）时的界面收尾。
     *
     * 心跳（`SessionKeeper`）在确认失效那一刻已经清掉凭证，并广播到这里；这里只做界面动作。
     * **不调 `library.clearAccount()`**：那是「用户主动退出」该做的事（会连设备档案与
     * 账号缓存一起清）。被动掉线保留设备档案与已缓存的收藏 / 历史，用户重登后立刻有内容。
     */
    private val sessionExpiredReceiver=object: android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context,intent: android.content.Intent) {
            if(intent.action!=com.hgtv.star.data.SessionEvents.ACTION_SESSION_EXPIRED) return
            val reason=intent.getStringExtra(com.hgtv.star.data.SessionEvents.EXTRA_REASON).orEmpty()
            android.util.Log.w("HGTV-SESSION","session expired: $reason")
            if(isDestroyed) return
            // 凭证已由心跳清掉；这里把仓库与界面同步到未登录态。
            repository.credentials=null
            repository.sessionToken=null
            applySessionToken(); applyAccount()
            showCatalog()
            runCatching { tvTools.toast("登录已失效，请重新登录") }
        }
    }
    internal val prefetchWorker=Executors.newSingleThreadExecutor()
    /** 弹幕单独一条线程：它要按 30 秒窗口连着拉几页，不能占住内容请求的线程池。 */
    internal val danmakuWorker=Executors.newSingleThreadExecutor()
    /** 每次换集自增；在途的弹幕请求发现 token 变了就立刻收手。 */
    internal val danmakuToken=java.util.concurrent.atomic.AtomicLong(0)
    internal var prefetchHttp: okhttp3.OkHttpClient?=null
    internal var playbackHttp: okhttp3.OkHttpClient?=null
    internal val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    internal var prefetchJob: java.util.concurrent.Future<*>?=null
    internal var prefetchScope: RequestScope?=null
    internal var prefetchedFor=""
    internal var readySince=0L
    /**
     * 无感切集（见 [playEpisode] 里的 `seamless`）：下一集已经预热好时，切集不清播放器，
     * 新源直接顶替旧源，画面停在上一集最后一帧上等下一集的第一帧。
     *
     * [bufferingSuppressed]：过渡那一两百毫秒临时关掉缓冲指示（见 [suppressBufferingUi]）。
     * [retiringVideo] / [retiringHttp]：旧一集的视频与传输**不当场关**——旧源这时可能还在收尾，
     * 当场关掉会让它读到一个已关闭的视频对象而报错；等新源 READY 再收（见 [retirePreviousEpisode]）。
     */
    internal var bufferingSuppressed=false
    internal var retiringVideo: RemoteVideo?=null
    internal var retiringHttp: okhttp3.OkHttpClient?=null
    /**
     * 队列里的下一集：已经作为**第二个 MediaItem** 挂进播放器，片尾由 ExoPlayer 自己推进
     * （`DISCONTINUITY_REASON_AUTO_TRANSITION`）。
     *
     * 这是「真无感」与旧路径的分界：旧路径在片尾调 [playEpisode]，会 `setMediaSource` →
     * reset → disableRenderer → 重建 MediaCodec → BUFFERING；挂进队列则只是换 period，
     * 渲染器与解码器都留着，边界处只换一帧。
     *
     * [queuedVideo] / [queuedHttp] 归队列所有，切集 / 退出播放器时一起收掉。
     */
    internal var queuedEpisodeId: String?=null
    internal var queuedVideo: RemoteVideo?=null
    internal var queuedHttp: okhttp3.OkHttpClient?=null
    internal var queuedQuality: String=""
    /** 下一集挂进队列的时刻：只用于日志里量「挂了多久才真的用上」。 */
    internal var queuedAt=0L
    /** 片尾自动推进发生的时刻：用来量「切过去到出画面」的间隔。 */
    internal var advancedAt=0L
    /**
     * 播放会话号：每次 [playEpisode] / [releasePlayer] 自增。
     *
     * 它补的是「去掉 mediaId 判据」之后的空档：`generation` 只在换页时变，
     * 而同一个播放器里连续切集时它不变。没有这一位，上一次 `playEpisode` 的
     * 异步回调（取地址的 `work`、播放器监听器）就可能落到新的这一集上。
     */
    internal var playbackSession=0
    /**
     * 同一次播放会话里的故障只处理一次。
     *
     * `STATE_ENDED` 与 `onPlayerError` 可能针对同一次故障先后到达，两条路都去
     * 「切下一集 / 弹错误界面」就是重复动作。记下已处理的会话号与时刻：
     * 同一会话 1.5 秒内的第二次直接忽略；新会话（[playbackSession] 变了）不受影响，
     * 因此不会把下一集紧接着发生的真实错误一起吞掉。
     */
    internal var failureHandledSession=-1
    internal var failureHandledAt=0L
    /** 队列里的下一集连续失败次数：只用来决定「还值不值得再自动重切一次」。 */
    internal var autoNextFailures=0
    /**
     * 播完的那一项是否还挂在播放列表里（等 800ms 后摘）。
     *
     * 置位期间不关旧视频：那一项的数据源还在列表里，关早了 ExoPlayer 重新 prepare 时
     * 会读到已关闭对象（见 [retirePreviousEpisode]）。
     */
    internal var playedItemPending=false
    /** 待摘那一项的 mediaId：3 秒兜底时用它确认「确实已经不在列表里」再关资源。 */
    internal var playedItemId=""
    /**
     * 摘不掉、又不能当场关的资源（列表正在重建等极端情况）。
     *
     * 只增不减、随 [releasePlayer] 一起释放：正常情况下永远是空的。
     */
    internal val orphanResources=mutableListOf<Pair<RemoteVideo?,okhttp3.OkHttpClient?>>()
    /** 上一集最后一次读到的时长：自动推进时 `STATE_ENDED` 不再触发，用它补一条 completed 记录。 */
    internal var lastEpisodeDuration=0L
    internal lateinit var artwork: ArtworkLoader
    internal data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    /**
     * 栅格里的一行：行容器 + 它这一行里固定 [gridColumns] 张卡片。
     *
     * 卡片数与行容器一一对应，因此「换一行数据」就是给这几张卡重新 [bindGridCard]，
     * View 本身（以及它们身上的监听器、Drawable）全部复用，不再随滚动 / 追加新建。
     * [index] 是这一行在整份列表里的行号，窗口滑动时它跟着变。
     */
    internal class GridRow(val line: LinearLayout) {
        var index=0
        val cards=mutableListOf<PosterCard>()
    }
    internal var deferredWork: DeferredUi?=null
    internal var initialCatalogPending=true
    internal var restoreStoppedPage=false
    internal var pendingDetail: Series?=null
    internal var detailAutoplay=true
    internal var libraryLoadError: Throwable?=null
    internal var workScope: RequestScope?=null
    internal var workJob: java.util.concurrent.Future<*>?=null
    internal var playerListener: Player.Listener?=null
    /**
     * 待决的解码器错误上报（见 [playbackDiagnostics] 的 `onVideoCodecError`）。
     *
     * 延迟 1.5 秒等 `onPlayerError`：后者到了就说明这条是同一件事的重复，
     * 取消掉；没到就说明被 fallback 接住了，只有这条能记下来。
     */
    internal var pendingCodecReport: Runnable?=null
    /** 起播看门狗（见 [armPlaybackWatchdog]）：捕获「READY 了但一帧都没出」这类无异常的黑屏。 */
    internal var playbackWatchdog: Runnable?=null
    internal var playbackWatchdogSession=-1
    internal var playbackWatchdogAt=0L
    /** 进入 READY 的时刻：黑屏判据从这一刻起算（解码器初始化 + 首帧渲染都在这一段里）。 */
    internal var playbackWatchdogReadyAt=0L
    /**
     * 调试模式那块右上角面板（见 `DebugOverlay.kt`）。
     *
     * 设置里打开「调试模式」之后才建；关掉 / 离开时摘掉。
     * 它与播放页的 [playbackText] 分工：那一行是给用户看的一句人话，
     * 这一块是给客服与开发看的完整现场。
     */
    internal var debugPanel: LinearLayout?=null
    /**
     * 面板正文：一行一条，每条都是「标头 + 值」两列（见 `DebugOverlay.kt` 的 `syncDebugRows`）。
     *
     * 用一组行视图而不是一整块 TextView：整块文字只有一个对齐方式，而这里要求
     * **每个条目都有自己的标头**、值又统一贴右——只有两列布局能同时做到。
     * 每秒刷新时只改值那一列的文字，不重建视图。
     */
    internal var debugBody: LinearLayout?=null
    internal var debugRowLabels: List<String> = emptyList()
    internal val debugRowValues=mutableListOf<TextView>()
    internal var debugTick: Runnable?=null
    /**
     * 面板连续多少轮**一个字都没变**（见 `DebugOverlay.kt` 的 `debugTickInterval`）。
     *
     * 用来退避：静态页面上没必要每秒醒一次。任何一次文字变化都会把它清零。
     */
    internal var debugIdleTicks=0
    /**
     * 面板上的「问题」一行：**最后一件用户能看见的事**（页面打不开 / 搜不出结果 / 详情失败）。
     *
     * 与 [DebugLog] 的流水账分工：近况记「发生过什么」，这一条记「现在为什么不好用」，
     * 因此后写的覆盖先写的，并带一个时刻，读的人能判断是不是刚才那一下。
     */
    internal var debugProblem: String=""
    internal var debugProblemAt: Long=0L
    /**
     * 上面那条问题属于哪一页（`screen|tab|collection|query`）。
     *
     * 面板是跨页常驻的：在搜索页记下的「搜不到」不能跟着用户回到首页继续挂着，
     * 否则客服看到的就是一条与当前页无关的结论。切页后这一位对不上，面板就显示「无」。
     */
    internal var debugProblemPage: String=""
    /** 本机解码能力（后台探好后缓存，主线程只读，见 `DebugOverlay.kt`）。 */
    internal var debugCapability: com.hgtv.star.data.PlaybackCapability.DebugCapability?=null
    internal var debugCapabilityLoading=false
    /** 「画面停住」看门狗（见 [armFrameStallWatchdog]）：已经在播却突然不再出帧。 */
    internal var playbackStallWatchdog: Runnable?=null
    internal lateinit var playbackTitle: TextView
    internal var playerClock: TextView?=null
    /** 顶部标题条（剧名 · 第几集 + 时钟）：播放时不打扰，只在唤出播放界面时露出来。 */
    internal var playbackTopBar: View?=null
    internal val clockFormat=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault())
    internal var directPlayback=false
    /**
     * 本机记录集合页（收藏 / 历史 / 稍后观看）。
     *
     * 「热门集合页」整个去掉了：首页四个「热播…」剧架的行末「查看全部」直接进**对应的榜单页**
     * （见 [rankBoard]），与排行榜页读的是同一份数据，不再有第二套远端列表。
     */
    internal var collection: String?=null
    /**
     * 首页「再按一次退出」第一次按下的时刻（`SystemClock.elapsedRealtime()`；0 = 没按过）。
     *
     * 只记时刻、不记布尔：第二次按下是否算数由「距上一次是否超过 [EXIT_CONFIRM_WINDOW_MS]」
     * 判定，因此不需要额外的定时器去复位——超时之后的下一按自然就是新的第一次。
     */
    internal var exitArmedAt=0L
    /**
     * 「我的」页当前看的那一栏（[ACCOUNT_TABS] 的 key）。
     *
     * 默认是**第一栏「历史」**（与芯片顺序一致）：进这一页时用户最常看的是「我看到哪了」，
     * 而收藏是主动行为、频次低。默认值与芯片顺序不一致时，进页面会看到
     * 「焦点在第一枚芯片上、内容却是第二栏的」这种错位。
     */
    internal var accountTab="history"
    /**
     * 「我的 → 历史」的完成度筛选：`null` = 全部，`true` = 只看已看完，`false` = 只看未看完。
     *
     * 只作用于这一栏（收藏 / 点赞 / 预约没有「看完」这个概念），切换栏目时保留，
     * 换回历史仍然按用户上次选的那一档显示；两枚芯片就在身份行里，随时可以再切。
     */
    internal var accountHistoryFilter: Boolean?=null
    /** 「已看完 / 未看完」两枚芯片（按状态索引），筛选切换后就地改选中态，不重建整页。 */
    internal val historyFilterButtons=mutableMapOf<Boolean,View>()
    /**
     * 「我的 → 预约」的上线状态筛选：`null` = 全部，`true` = 只看已上线，`false` = 只看待上线。
     *
     * 与 [accountHistoryFilter] 同一套语义（点已选中的那一枚 = 回到全部），
     * 只作用于预约这一栏；两栏各自记住自己那一档，互不影响。
     */
    internal var accountReserveFilter: Boolean?=null
    /** 「已上线 / 待上线」两枚芯片（按状态索引）。 */
    internal val reserveFilterButtons=mutableMapOf<Boolean,View>()
    /**
     * 列表「一页」的条数——**只是给界面显示用的估算**，真正的分页由 `core` 的
     * `LOGICAL_PAGE_SIZE` 决定（现在各条路统一成 100 条一个逻辑页）。
     *
     * 这个值只在一处用到：榜单底部那行「已显示 N / M 部」按「总页数 × 每页条数」估算总数。
     * 改了 `core` 那边的一页大小，这里要跟着改，否则那个总数会差好几倍。
     */
    internal val pageSize=100
    /**
     * 「回到顶部」浮层：右下角那一枚按钮（见 `list/BackToTop.kt`）。
     *
     * 引用留在这里而不是页面里：它是**整页重建之后重新挂上的同一枚控件**
     * （`base()` 每次都会 `root.removeAllViews()`），因此不能跟着页面一起丢。
     */
    internal var backToTopView: View?=null
    /** 浮层当前是否显示（避免每次滚动都写一遍 visibility）。 */
    internal var backToTopShown=false
    /**
     * 浮层是不是正等「这一次布局」跑完再摆位置。
     *
     * 进页面 / 恢复滚动位置的那一帧里卡片刚物化，窗口坐标还全是 0，按它摆会把浮层
     * 摆到右上角（见 `list/BackToTop.kt` 的 [positionBackToTop]）；那时挂一个一次性的
     * 全局布局回调，布局完成立刻重摆，并靠这一位防止重复挂。
     */
    internal var backToTopLayoutPending=false
    /** 为了等布局已经重试了几次（见 `list/BackToTop.kt` 的 [positionBackToTop]）。 */
    internal var backToTopLayoutWaits=0
    /** 走到浮层之前焦点在哪一张卡（浮层的「上 / 左」按回那里）。 */
    internal var backToTopReturn=View.NO_ID
    internal var catalogScroll: ScrollView?=null
    internal var catalogBody: LinearLayout?=null
    internal var homePosition: HomeScreen.Position?=null
    internal var catalogPageKey=""
    /**
     * 页面内的一行提示（替代 Toast）。
     *
     * 应用里不再弹系统 Toast：它是独立于界面的一层浮窗，样式、字号、焦点行为都不受本应用控制，
     * 电视上还会盖在画面正中。需要「刚刚发生了什么」这类反馈时，改成在列表上方显示一行淡字
     * （[catalogHint]），随下一次翻页 / 刷新自动消失；不需要额外说明的（收藏、切主题、删除记录等）
     * 直接靠界面本身的变化表达。
     */
    /**
     * 账号侧的缓存：资料 + 收藏 / 历史 / 点赞 / 预约。
     *
     * null 表示「还没拉过」——个人信息页据此显示「正在读取…」并触发一次 [loadAccount]。
     * 它是内存缓存：账号数据每次进这一页都可以重取，不值得落盘（也可能过期）。
     */
    internal var accountData: AccountData?=null
    /** 文件选择器回传的 Uri；等 onResume 收尾之后再读（见 [pickAccountFile]）。 */
    internal var pendingAccountUri: android.net.Uri?=null
    internal var accountError: Throwable?=null
    internal var accountLoading=false
    /** 账号拉取的序号：只有最后一次请求的回调会落地（见 [loadAccount]）。 */
    internal var accountToken=0
    /** 启动那一次账号拉取失败后的自动重试次数（见 [loadAccount] 的失败分支）。 */
    internal var accountRetry=0
    /**
     * 「这条本机进度已经推回云端」的标记（剧集 ID → 推上去的时间戳）。
     *
     * 历史对账（见 `reconcileHistory`）在本机更超前时会把那一份推回云端；账号数据每次进
     * 「我的」页都会重拉一遍，不记一下就变成「每刷新一次就上传一次」，平台的 `last_watch_time`
     * 被反复改写，多台设备之间还会来回拉扯。
     */
    internal val historyPushedAt=mutableMapOf<String,Long>()
    /** 设备注册是否在途：避免每次进前台都重复注册（见 [ensureDeviceRegistered]）。 */
    internal var deviceRegistering=false
    /**
     * 这台设备能不能解 HEVC。
     *
     * 播放接口只下发 HEVC 与 BVC2 两种编码，而 BVC2 在 Android 上没有解码器，
     * 因此它等价于「能不能看」。由 [applyPlaybackCapability] 在后台探一次，
     * 出错界面据此把「无法解码」说清楚（见 [playerFailure]）。
     * 默认 true：探测还没回来时按「能」处理，避免一进播放页就先把用户吓一跳。
     */
    internal var playbackHevcSupported=true
    /**
     * 「第三方客户端使用须知」是不是正在显示。
     *
     * 两个入口（进「我的」页、点「获取验证码」）都会调 [ensureThirdPartyAccepted]，
     * 而进「我的」页那一次是在页面重建之后异步弹的，点「获取验证码」又可能紧接着触发：
     * 没有这个标记就会叠出两个一样的弹窗，用户确认掉一个、另一个还挡在上面。
     */
    internal var consentShowing=false
    /**
     * 本次进程里是不是已经弹过「使用须知」。
     *
     * 与 [Library.thirdPartyAccepted] 的区别：那个是「确认过没有」（落盘、跨启动），
     * 这个只防**同一次运行里**重复弹——用户在弹窗上按了返回键把它关掉时，
     * 确认状态仍是 false，切走再切回来就会又弹一次，读起来像「这弹窗关不掉」。
     * 因此每个进程只主动弹一次，之后要等用户真正点「同意」才落盘。
     */
    internal var consentShownThisRun=false
    /** 本次「往下接」已经连着预取了几页（见 [PREFETCH_PAGES]）；用户主动加载更多时归零。 */
    internal var prefetchChain=0
    /**
     * 上一次预取窗口的中心行（-1 = 还没有过）。
     *
     * 焦点在同一行内左右移动时窗口内容完全一样，没必要重算——重算一次要遍历
     * 前后各 [PREFETCH_ROWS] 行的所有封面（约 150 张）并逐个改写 URL、比对队列，
     * 而这一切都在 UI 线程上（焦点移动是按键事件）。
     * 实测不去重的话 `enq` 会从 4291 涨到 14829、`qAvg` 从 125ms 涨到 340ms——
     * 按一次键就要做一遍这份工作，用户读到的就是「按键越来越钝」。
     */
    /**
     * 上一次预取窗口的中心行（-1 = 还没有过）。
     *
     * 中心行**只有一个来源**（焦点行；焦点不在栅格里时用视口中心），由
     * [MainActivity.prefetchAroundWindow] 自己算——不再由调用方传进来。
     * 传进来的那版有两个来源（视口底行 / 焦点行）交替触发，窗口会在两个位置之间来回翻，
     * 绝大多数预取任务被反复撤销重排（实测 77%）。
     */
    internal var prefetchCenterRow=-1
    /** 注册的互斥锁：启动时那次异步注册与播放前那一次同步注册可能同时进来，只发一次请求。 */
    internal val deviceRegisterLock=Any()
    /** 设备身份自愈（见 [healDeviceIdentity]）：已经处理过的「被拒」计数，避免同一件事重建多次。 */
    internal var deviceHealHandled=0
    /** 本次进程里已经重建过几次（上限 [DEVICE_HEAL_LIMIT]）。 */
    internal var deviceHealTried=0
    /** 上一次重建的时刻（[android.os.SystemClock.elapsedRealtime]）；0 = 还没重建过。 */
    internal var deviceHealAt=0L
    internal var catalogHint: String?=null
    internal var catalogHintView: TextView?=null
    internal val hideHint=Runnable { catalogHint=null; catalogHintView?.visibility=View.GONE }
    internal val scrollPositions=mutableMapOf<String,Int>()

    /** 记住一页列表的滚动位置；超出 [SCROLL_MEMORY] 条就丢掉最早的一条。 */
    internal fun rememberScroll(key: String,value: Int) {
        if(key.isBlank()) return
        if(key !in scrollPositions && scrollPositions.size>=SCROLL_MEMORY) scrollPositions.keys.firstOrNull()?.let(scrollPositions::remove)
        scrollPositions[key]=value
    }

    // —— 页头区域随列表滚动收起 ——
    /** 页头区域里的每一行：工具条、搜索行、榜单标题行、集合页标题行。 */
    internal val topArea=mutableListOf<View>()
    /** 每行展开时的高度（像素），收起动画的起点、展开动画的终点。 */
    internal val topAreaHeights=mutableMapOf<View,Int>()
    /** 每行展开时应该还原的 LayoutParams 高度（工具条是固定 TOOLBAR_HEIGHT，其余是 WRAP_CONTENT）。 */
    internal val topAreaRestore=mutableMapOf<View,Int>()
    internal val topAreaAnimators=mutableMapOf<View,android.animation.ValueAnimator>()
    internal var topAreaHidden=false
    /**
     * 「加载更多」是同一页的原地重建：这一次重建要保留页头当前的收 / 展状态，
     * 否则每追加一页页头都会弹回来一次。切换标签 / 分类这类换页仍然从展开态开始。
     */
    internal var keepTopAreaState=false
    internal var listScrollY=0
    internal var topAreaSettleUntil=0L

    /** 把一行页头登记进「随滚动收起」的区域。 */
    internal fun registerTopAreaRow(row: View) {
        topArea+=row
        val restore=row.layoutParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT
        topAreaRestore[row]=restore
        if(topAreaHidden) {
            // 页头正收着，这一行也必须立刻收起来：先量出展开高度当基准，再按收起状态摆好，
            // 中间不留一帧展开态，否则重建时页头会闪一下。
            expandedHeightOf(row)
            applyTopAreaState(row,true)
        } else {
            // 布局完成后再量一次真实高度：收起动画的起点、展开动画的终点都按它算。
            row.post { if(topArea.contains(row) && row.height>0) topAreaHeights[row]=row.height }
        }
    }

    /**
     * 列表滚动 → 页头收展。
     *
     * 收起用高度动画而不是 translationY：收掉之后列表真的多占这一条高度，顶部不会留一条空白，
     * 也不会出现「按钮浮在列表上面」的叠层。只有列表回到最上边、或者焦点回到页头里的控件时才展开：
     * 列表中间向上滚只保持当前状态，不会半路把页头弹回来。收起 / 展开之后有一小段静默期，
     * 避开视口变化引起的 scrollY 回夹，否则会在底部来回抖。
     */
    internal fun onListScroll(y: Int,force: Boolean=false) {
        // 静默期里只更新位置、不改收展状态：这期间的滚动是位置恢复 / 内容夹取，不是用户滚的。
        if(!force && android.os.SystemClock.elapsedRealtime()<topAreaSettleUntil) { listScrollY=y; updateBackToTop(); return }
        listScrollY=y
        // 低内存时被摘掉的封面：滚回视口就挂回来（`suspendedCovers == 0` 时是一次整数比较）。
        resumeVisibleCovers()
        setTopAreaHidden(headerShouldHide(y),animate=!force)
        updateBackToTop()
        // 手指 / 遥控滚到离底部还有小半屏时就先把下一页取回来：
        // 真滚到底的时候内容已经在手上，接上是瞬间的，用户看不到「正在加载」。
        // 触发点统一交给 [nearGridEnd]（按「已消耗 2/3」算）：这里只管页头收展。
        if(nearGridEnd()) prefetchNextPage()
    }

    /**
     * 把页头的收 / 展状态按**当前位置与焦点**重算一次。
     *
     * 页头状态是「位置 + 焦点」的纯函数（见 [onListScroll]）：任何可能改变这两者的事件
     * 之后都该重算一次，否则状态会与事实脱节——**焦点移动**就是最容易被漏掉的那一类：
     * 焦点从第二行回到第一行时列表被 `reveal` 滚回顶部，那次滚动常常落在静默期或
     * `restoring` 里被吞掉，于是「位置已经回到顶部、页头却还收着」或者反过来。
     * 用户读到的就是「按上键页头反而隐藏」这种**状态反过来**的现象。
     */
    internal fun syncHeaderState() {
        if(screen!="catalog" || topArea.isEmpty()) return
        val y=homeScreen?.scrollY() ?: catalogScroll?.scrollY ?: return
        listScrollY=y
        setTopAreaHidden(headerShouldHide(y),animate=false)
        updateBackToTop()
    }

    /**
     * 页头该不该收起——**位置与焦点的纯函数**。
     *
     * 两条判据任一成立就收起：
     *
     * 1. **列表滚过顶部的余量**（`y > HEADER_SHOW_THRESHOLD`）：向下浏览时把页头让开，
     *    这是它本来的职责（滚回最上边就展开）；
     * 2. **焦点已经离开第一栏**（`focusedRowIndex() >= 1`）：首页的栏高是按「一屏放得下一栏」
     *    算的，焦点走到第二栏时往往**位置还是 0**（第二栏的下半截本来就在屏幕上），
     *    只看位置会判成「该展开」，而用户看到的是「焦点明明已经往下走了、页头却还占着地方」。
     *
     * 例外：焦点在页头里时**永远展开**——收着的话那一行高度是 0，焦点会停在一个看不见的控件上。
     *
     * 早先这里用「比上一次大就是向下滚」这种**增量**启发式，它依赖基线 [listScrollY] 正确；
     * 而基线在每次重建时都会被重置（[renderHome] 写死 0、栅格路径取新建 ScrollView 的 0），
     * 恢复位置之后基线与事实对不上，判据就给出与位置无关的结果——表现出来就是
     * 「焦点在第二行、页头却展开；按上键回到第一行、页头反而收起」这种**状态反过来**的现象。
     * 纯函数判据不依赖任何基线，任何时刻算出来的都与事实一致。
     */
    internal fun headerShouldHide(y: Int): Boolean {
        if(topAreaHasFocus()) return false
        if(y>dp(HEADER_SHOW_THRESHOLD)) return true
        // 「焦点走到第二栏就收」这条判据在低配上关掉。
        //
        // 它只按**焦点**算、不看滚动位置：焦点一进第二栏，页头高度 88dp→0，
        // 而页头挂在带 weight 的 body 之上——body 高度一变，首页 ScrollView 的视口高度跟着变，
        // 它子控件的 heightSpec 就从 AT_MOST(H) 变成 AT_MOST(H+88dp)，
        // measure 缓存全失效，700 多个 View 全量重测一次（10~30ms）。
        // 而这一刻 `reveal()` 正在 smoothScroll 250ms 里，这一顿正好插在滚动动画中间。
        // 低配改为只看位置：真正滚下去之后页头照样收，且只收一次。
        if(!TvStyle.animate) return false
        return focusedContentRow()>=1
    }

    /**
     * 焦点当前落在内容区的第几行（从 0 起）；焦点不在内容上时返回 -1。
     *
     * 两种内容分别算：首页是剧架（[HomeScreen.focusedRowIndex]），
     * 其余页面是卡片栅格（[gridFocusIndex] 除以列数）。两者都按「第 1 行是页头正下方那一行」算。
     */
    private fun focusedContentRow(): Int {
        homeScreen?.let { home -> return home.focusedRowIndex() }
        val index=gridFocusIndex() ?: return -1
        return index/gridColumns
    }

    /** 列表是不是已经滚到距底部 [slack] 像素以内。没有栅格（首页剧架、设置页）时永远为 false。 */
    internal fun gridAtBottom(slack: Int): Boolean {
        val scroll=gridScroll ?: return false
        val list=gridList ?: return false
        if(scroll.height<=0 || list.height<=0) return false
        return scroll.scrollY+scroll.height+slack>=list.height
    }

    private fun topAreaHasFocus()=topArea.any { it.findFocus()!=null }

    /**
     * 一行页头展开时的高度。工具条这类固定高度的行直接用 LayoutParams，
     * 其余（搜索行、榜单标题行、集合页标题行）按页面栅格宽度量一次自然高度，不用等布局。
     */
    private fun expandedHeightOf(row: View): Int {
        val restore=topAreaRestore[row] ?: LinearLayout.LayoutParams.WRAP_CONTENT
        topAreaHeights[row]?.takeIf { it>0 }?.let { return it }
        if(restore>0) { topAreaHeights[row]=restore; return restore }
        // 收起状态不影响这次测量：量的是内容在页面栅格宽度下的自然高度。
        val width=if(row.width>0) row.width
            else (resources.displayMetrics.widthPixels-2*(pageInset()-pageBleed())).coerceAtLeast(1)
        row.measure(
            View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        )
        val measured=row.measuredHeight
        if(measured>0) topAreaHeights[row]=measured
        // 量不出来（内容还没建好）就退回当前高度：这一行不做动画、直接到位，不会朝 0 缩。
        return measured.takeIf { it>0 } ?: row.height
    }

    /** 把一行页头直接摆到目标状态（不排队动画）：收起 = 高度归零 + 淡出 + 打开裁剪；展开反之。 */
    private fun applyTopAreaState(row: View,hide: Boolean) {
        (row as? android.view.ViewGroup)?.let { group -> group.clipChildren=hide; group.clipToPadding=hide }
        row.animate().cancel()
        row.alpha=if(hide) 0f else 1f
        val restore=topAreaRestore[row] ?: LinearLayout.LayoutParams.WRAP_CONTENT
        row.layoutParams=row.layoutParams.apply { height=if(hide) 0 else restore }
    }

    internal fun setTopAreaHidden(hide: Boolean,animate: Boolean=true) {
        if(topAreaHidden==hide || topArea.isEmpty()) return
        topAreaHidden=hide
        // 搜索页：页头收起会把提示词那一行压成 0 高，而输入框的 `nextFocusDownId` 还指着
        // 那些芯片——按回车时 `focusSearch` 会把它交回来、`requestFocus` 却必然失败，
        // 于是 `TextView` 抛「focus search returned a view that wasn't able to take focus!」。
        // 收展状态一变就按当前实际可聚焦情况重接一次。
        if(tab==TAB_SEARCH) wireSearchHeadDown(suggestButtons.firstOrNull())
        // [animate] 为 false 时直接到位：用于「按当前滚动位置把状态摆正」那一次
        // （进页面 / 恢复位置之后），那时做动画反而会让页头闪一下。
        // 首页是**未窗口化**的一整棵树（7 栏 ≈ 77 张卡 ≈ 800 个 View）：高度动画每帧都要
        // 整页 relayout，200ms = 12 次全页布局，正好叠在「按住下键滚动 → 页头收起」这个
        // 最高频动作上。这里直接到位，只丢一点动画感；栅格路径已经窗口化（约 30 个 View），
        // 照旧做动画。
        val span=if(animate && TvStyle.animate && homeScreen==null) TvStyle.DURATION else 0L
        topAreaSettleUntil=android.os.SystemClock.elapsedRealtime()+span+120
        topArea.forEach { row ->
            val from=row.height
            val to=if(hide) 0 else expandedHeightOf(row)
            // 先摘掉再取消：被取消的那条动画结束回调不会再回头改这一行的高度。
            topAreaAnimators.remove(row)?.cancel()
            row.animate().cancel()
            row.animate().alpha(if(hide) 0f else 1f).setDuration(span).start()
            // 收起过程中不再让子控件画到行外，展开时立刻恢复（焦点描边要继续画得出来）。
            (row as? android.view.ViewGroup)?.let { group -> group.clipChildren=hide; group.clipToPadding=hide }
            // 低内存设备（TvStyle.animate=false）直接到位，不排队动画；没有高度差时也一样。
            if(span==0L || from==to) { settleTopAreaRow(row,hide); return@forEach }
            topAreaAnimators[row]=android.animation.ValueAnimator.ofInt(from,to).apply {
                duration=span
                interpolator=android.view.animation.DecelerateInterpolator()
                addUpdateListener { animation ->
                    row.layoutParams=row.layoutParams.apply { height=animation.animatedValue as Int }
                }
                addListener(object: android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // 摘掉自己这一步必须是原子的：这条回调也可能由 cancel() 同步触发。
                        if(topAreaAnimators.remove(row)===this@apply) settleTopAreaRow(row,hide)
                    }
                })
                start()
            }
        }
    }

    /** 动画结束：收起的行高度归零，展开的行还原成登记时的 LayoutParams 高度。 */
    private fun settleTopAreaRow(row: View,hide: Boolean) {
        applyTopAreaState(row,hide)
        // 展开到位后重新量一次真实高度，作为下一次收起 / 展开的基准。
        if(!hide) row.post { if(topArea.contains(row) && row.height>0) topAreaHeights[row]=row.height }
    }
    internal var prefetchAttemptAt=0L
    internal var prefetchAttempts=0
    internal var homeScreen: HomeScreen?=null
    internal var homeFromCache=false

    internal var homeUpdateCount=0
    internal var launchStarted=android.os.SystemClock.elapsedRealtime()
    internal val activityStarted=launchStarted
    internal var homeContentLogged=false
    internal lateinit var library: Library
    /**
     * [library] 是否已经建好。
     *
     * `lateinit` 的 `isInitialized` 只能在本类体内用：扩展成员函数（本项目大量界面代码
     * 都是 `MainActivity.xxx()` 这种扩展）拿不到 backing field，编译器会直接报
     * 「Backing field is not accessible at this point」。因此统一开这一个成员出来，
     * 扩展里一律走它（见 `MainActivity.applyPerformanceProfile`）。
     */
    internal val libraryReady: Boolean get() = ::library.isInitialized
    internal lateinit var tvTools: TvTools
    /**
     * 媒体会话按需创建（见 [ensureMediaSession]）。容器里 `media_session` 可能返回空 binder，
     * 平台 MediaSession 构造会对它直接解引用并崩溃，所以启动阶段不再创建。
     */
    internal var mediaSession: TvMediaSession?=null
    internal var mediaSessionFailed=false
    /** 应用内更新：前台空闲时检查新版，下载校验后交给系统安装器。 */
    internal lateinit var appUpdater: AppUpdater
    internal val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    internal var sleepStopped=false
    internal var playerView: PlayerView?=null
    /** 播放画面上的弹幕层；数据来自原 App 的评论接口（见 ContentRepository.danmaku）。 */
    internal var danmakuView: DanmakuView?=null
    /** 已经拿到弹幕的那一集：换清晰度、播放重试这类「同一集重进」不重复拉一遍。 */
    internal var danmakuFor=""
    internal var danmakuLoaded=false
    /** 这一集取到多少条弹幕；-1 表示还没取回来。播放界面的状态行会显示它，便于确认弹幕到底有没有到。 */
    internal var danmakuCount=-1
    internal lateinit var favoriteMonitor: FavoriteMonitor
    /**
     * 窗口化之后**不再**按剧集 id 记住「收藏状态那一行文字控件」。
     *
     * 卡片会被滚出窗口、回收、再拿去显示别的剧集，按 id 存下来的 TextView 引用会指到
     * 已经换了内容的控件上。收藏标签改成「重绑当前窗口那几行」（见 [rebindGridRows]），
     * 代价只有一屏的卡片数。
     */
    internal var favoriteStatus: TextView?=null
    /**
     * 历史页（历史集合页与「我的 → 历史」）页头那枚「清空全部记录」。
     *
     * 单独存一份引用是为了接焦点链：它就在内容上方，第一行按上要落到它、
     * 导航栏那排圆形入口按下要先经过它（见 `wireGridFocus`）。没有可清空的记录时不建。
     */
    internal var historyClear: View?=null
    /**
     * 「清空全部记录」是不是在**顶部导航栏那一行里**（历史集合页）。
     *
     * 在导航栏里时它本身就是页头控件，导航栏那排圆形入口按下要直接进内容，
     * 不能先落到它身上（那时它在同一行，方向上是「向右」而不是「向下」）。
     * 「我的 → 历史」那一枚在身份行里，这一位是 false。
     */
    internal var historyClearInline=false
    internal var episodePanel: EpisodePanel?=null
    internal val recovery=RecoveryBudget()
    internal var retryPending=false
    internal var autoRecovery=false
    internal var startPosition=0L
    internal var retryPosition=0L
    internal var requestedAutoplay=true
    internal var retryAutoplay=true
    internal var foreground=false
    internal lateinit var connectivity: ConnectivityManager
    /** 网络变化的注册句柄：API 19 是广播接收器，21+ 是 NetworkCallback，由 [com.hgtv.star.compat.NetworkWatcher] 统一。 */
    private var networkWatch: Cancellable?=null
    internal val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HGTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    /**
     * 正在执行「清空应用缓存」（本机数据恢复出厂）。
     *
     * 置位之后所有会落盘的路径（播放进度、收藏、设置）都要停手：数据马上就要删掉，
     * 队列也已经关掉，再提交一次只会抛异常或把刚删的文件建回来。
     */
    internal var resetting=false
    internal lateinit var root: FrameLayout
    internal var generation=0
    internal var screen="catalog"
    // —— 推荐流（类似抖音的上下切换）——
    /**
     * 当前是不是「推荐播放界面」。
     *
     * 推荐页复用同一套播放内核（[playEpisode] / 播放器 / 弹幕 / 播放设置菜单），
     * 只在这几位上分开：
     *
     * * [screen] 是 `recommend` 而不是 `player`：返回键回主页面、上下键换剧、确定键进正片；
     * * **不写观看历史**（见 `saveProgress` 的守卫）：只有进入正式播放界面那一刻才写；
     * * 不挂「下一集」队列（见 `maybePrefetch` / `enqueuePreparedNext`）：一集播完就进正片，
     *   由正片从下一集接着播。
     */
    /**
     * 冷启动时是否还要自动进入推荐页（需求 1：打开应用后自动进入推荐页面）。
     *
     * 只在**第一次**把本机记录读起来之后消费一次；之后无论怎么返回主页面都不再自动跳，
     * 否则用户从推荐页返回首页、按一下方向键，页面又会自己跳回推荐。
     */
    internal var launchRecommendPending=true
    /** 推荐列表（来自分类接口的「全部推荐」流，已滤掉本机隐藏的剧）。 */
    internal var recommendItems: List<Series> = emptyList()
    internal var recommendIndex=0
    internal var recommendPage=0
    internal var recommendHasMore=true
    internal var recommendLoading=false
    /**
     * 推荐流的续流游标（`session_id` + `next_offset`，见 `ContentRepository.recommendFeed`）。
     *
     * 接口是**有状态**的：`session_id` 传空、`offset` 每次归零，服务端永远回同一套默认列表。
     * 首次进入时从 [com.hgtv.star.data.Library.recommendCursor]（落盘）读起点，
     * 取完一页立刻把新的游标写回去，下次打开应用就接着往下刷。
     */
    internal var recommendSession=""
    internal var recommendNext=0
    /** 已经取过的推荐详情（只留最近几部，够左右相邻切换即可）。 */
    internal val recommendDetails=object: LinkedHashMap<String,Detail>(8,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Detail>?)=size>6
    }
    /** 正在取详情的推荐剧（避免同一部被上下键连按时重复发请求）。只在主线程读写。 */
    internal val recommendDetailLoading=mutableSetOf<String>()
    /**
     * 已经预热好的「相邻一部的第 1 集」（键 = `集id:清晰度`）。
     *
     * 上下键换剧时详情与首集数据都在本地，`playEpisode` 拿到它就直接起播，不用再等
     * 取地址 + 读索引 + 首批数据那几百毫秒——这是「上下切换不卡」的关键。
     * 只留最近 3 部（约 3MB），所有权在交给播放器时转移。
     */
    internal val recommendPrepared=LinkedHashMap<String,RemoteVideo>()
    /** 正在预热的那一集的键（空 = 没有在途任务）；同一时刻只预热一部，避免抢带宽。 */
    internal var recommendWarming=""
    internal var recommendWarmJob: java.util.concurrent.Future<*>?=null
    /** 预热任务的令牌：取消 / 换目标之后，在途那一次回来时必须自己关掉，不能再塞进缓存。 */
    internal val recommendWarmToken=java.util.concurrent.atomic.AtomicLong(0)
    /** 取详情的令牌：换剧、退出时作废在途结果。 */
    internal val recommendToken=java.util.concurrent.atomic.AtomicLong(0)
    /** 取下一页推荐流的令牌（与详情那条分开：翻页不该作废正在取的那一部详情）。 */
    internal val recommendPageToken=java.util.concurrent.atomic.AtomicLong(0)
    /** 从推荐进入正片时还没写出去的那条历史（起播未 READY 时补写，见播放监听器的 READY 分支）。 */
    internal var recommendHistoryPending=false
    /** 这一次换剧的方向（+1 下一部 / -1 上一部），只用于切换时那一下滑动方向。 */
    internal var recommendShift=0
    /**
     * 打开播放设置的那一次菜单键按键还没抬起。
     *
     * 短按菜单键会先来 DOWN（这里打开菜单）、再来**同一次按键**的 UP；以前 UP 被当成
     * 「菜单开着又按了一次菜单键」，于是菜单一闪就没了，用户必须长按住才留得住。
     * 现在用这一位把「打开它的那一下」的抬起认出来：只吞掉，不收起。
     */
    internal var playbackMenuOpenPress=false
    /** 推荐页画面停住之后自动重建播放层的次数 / 上次时刻（见 [recoverRecommendPicture]）。 */
    internal var pictureRecoveries=0
    internal var pictureRecoveryAt=0L
    /**
     * 推荐页切换过渡用的那一层黑。
     *
     * 为什么需要：换剧时播放器要重挂媒体源，`SurfaceView` 在新一集出帧之前会一直显示上一帧，
     * 而内容框的宽高比要等新源报出视频尺寸才更新——中间那一段旧画面会被按新比例拉伸，
     * 用户看到的就是「先拉一下再恢复」。这一层盖在画面（含弹幕）之上、底部浮层之下：
     * 切换时淡入，新一集**第一帧真正渲染出来**之后再淡出，拉伸与黑屏都被盖住。
     */
    internal var recommendCover: View?=null
    internal var recommendCoverShownAt=0L
    /** 过渡令牌：连着换剧时，上一次排好的「淡出」必须作废。 */
    internal var recommendCoverToken=0
    internal var recommendCoverReveal: Runnable?=null
    /** 推荐页底部那块信息浮层（剧名 / 集数 / 操作提示）。 */
    internal var recommendOverlay: View?=null
    internal var recommendTitle: TextView?=null
    internal var recommendMeta: TextView?=null
    /**
     * 推荐页浮层里那条播放进度。
     *
     * 与正式播放的底部控制条**同一个控件、同一种画法**（`SeekIndicator` 的原始样式：
     * 4dp 轨道 + 圆钮 + 右侧时间），只是宿主从 `hud` 换成了这块信息浮层——
     * 两侧页面读起来是一套东西，用户不用在两处学两种进度显示。
     */
    internal var recommendProgress: com.hgtv.star.tv.SeekIndicator?=null
    /** 与进度条同一行的 `00:04 / 01:08`（字色字号同正式播放）。 */
    internal var recommendSpan: TextView?=null
    /** 手指正在拖推荐页那条进度：每秒一次的刷新不能把显示顶回播放器报的位置。 */
    internal var recommendScrubbing=false
    /**
     * 触屏设备上浮层里那一排「看正片 / 暂停 / 换一部」按钮。
     *
     * 电视上恒为 null（这一排只在 `touchArmed` 时建）。它存在就说明：这一页的方向键
     * **先服务这排按钮**——左右在三个按钮之间移动焦点、确定按下当前那一枚，
     * 不再劫去快进快退（见 `dispatchKeyEvent` 的推荐页分支）。
     */
    internal var recommendActions: android.widget.LinearLayout?=null
    /** 浮层是否被用户轻点收起了。收起时左右键回到「快进退」、返回键回到「回主页面」。 */
    internal var recommendBarHidden=false
    /** 浮层上那条临时提示（例如「后面的推荐暂时取不到」），几秒内优先于集数那一行显示。 */
    internal var recommendNotice=""
    internal var recommendNoticeAt=0L
    /** 推荐页连续起播失败的次数：失败就换下一部，连换几次仍失败才停在提示上（见 `playerFailure`）。 */
    internal var recommendPlaybackFailures=0
    /** 导航栏里那枚圆形「推荐」入口（不属于五个标签，见 `navIcons()`）。 */
    internal var recommendIcon: View?=null
    /**
     * 当前这一次正式播放是不是「从推荐页按确定 / 自动连播」进来的。
     *
     * 需求：从这里进正片后按返回要**直接回主页**（而不是先回简介页、再回主页）。
     * 只影响返回键的去向，不改变播放本身的任何行为；离开播放界面 / 从别处进详情时清掉。
     */
    internal var playbackFromRecommend=false
    /**
     * 「问题反馈」整页是否打开（导航栏最右那枚圆形按钮进来的一页）。
     *
     * 它不是第五个标签：标签是「看哪一类内容」，这一页只是一张说明卡，
     * 因此用独立开关而不是 [tab]——切到任何标签都会关掉它（见 [switchTab]），
     * 返回键也直接回到原来那一页（见 `goBack`）。
     */
    internal var feedbackOpen=false
    /** 「问题反馈」页当前那三张卡片（重复点入口时把焦点交回第一张，不重建页面）。 */
    internal var feedbackCards: List<View> = emptyList()
    internal var tab=0
    internal var page=1
    internal var query=""
    internal var catalog=emptyList<Series>()
    internal var hasMore=false
    internal var catalogFocus=""
    internal data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?)
    internal var ranking: ComicRanking?=null
    /**
     * 排行榜页当前看的是哪一个榜单。首页四个剧架的「查看全部」也会改它，
     * 因此「从首页进榜单」与「在榜单页里切换」是同一条路径、同一份数据。
     */
    internal var rankBoard=RankBoard.COMIC
    /** 首页四个剧架的内容（榜单第 1 页），按榜单分别存。 */
    internal var homeFeed=mutableMapOf<RankBoard,CatalogPage>()
    /**
     * 这一页的内容落地后，焦点是不是要落到第一张卡上。
     *
     * **不要靠「现在焦点在哪」去猜**：进榜单页 / 切榜单 / 切分类时，中间那段「正在加载…」
     * 曾经把焦点临时交给页头的控件，落地时再看「页头有没有焦点」就会把焦点又交回页头——
     * 表现出来就是「进榜单、切榜单之后焦点停在榜单按钮上」。因此改成显式的意图：
     * 换了一份列表（换榜单 / 换分类 / 从首页剧架进榜单）就置 true，落地时落到第一张卡并清掉。
     */
    internal var focusFirstCardOnLanding=false
    /**
     * 换榜单 / 换分类之后，焦点要留在**用户刚点的那一枚**芯片上。
     *
     * 芯片在页面重建时是新建的，旧引用已经离树（对它 requestFocus 一定失败），
     * 因此这里记的是 key，重建后由 [chipControl] 查回新的那一枚。
     * 取值 `board:<storedValue>` / `group:<path>` / `tag:<path>`；为空表示这次换列表不是「点了芯片」。
     * 内容落地（或加载失败）时消费掉。
     */
    internal var chipFocusKey: String?=null
    /** 剧架 key → 榜单（[HomeLayout.board]）：首页四个「热播…」栏目与排行榜页共用同一个映射。 */
    internal var rankSubtitle: TextView?=null
    internal var moreButton: View?=null
    /** 向下追加一页后焦点要落到的位置：新卡片 id、底部按钮（load:more）或 null（不指定）。 */
    internal var moreFocus: String?=null

    // —— 向下追加（无感加载）——
    /**
     * 当前栅格列表的活引用。
     *
     * 「展示更多」过去是 `page++` 之后整页重建：页头、分类开关、每一张卡都重画一遍，
     * 再靠记下来的 scrollY 把位置恢复回去。两次整页重建之间位置一夹不准就跳回顶部，
     * 焦点又会在恢复位置和「新卡片」之间来回抢，表现出来就是加载时抖、加载完乱。
     * 现在追加只在这张列表的末尾插新行：视口上方的像素一个都不动，
     * ScrollView 不需要任何位置恢复，也就没有「恢复得对不对」这回事。
     */
    internal var gridList: LinearLayout?=null
    internal var gridScroll: ScrollView?=null
    /** 底部那一行（进度文字 + 「展示更多」），追加时先摘下来再放回去，始终待在最后。 */
    internal var gridPaging: LinearLayout?=null
    internal var moreCount: TextView?=null
    /** 底部那一行当前是不是「还有下一页」的形态；只有它翻转时才需要重建这一行。 */
    internal var pagingHasMore=false
    /**
     * 栅格是**固定窗口**：只物化视口附近那几行，行与卡片都复用。
     *
     * 这是「加载几百个卡片之后越来越卡」的根治手段。窗口化之前是
     * `ScrollView + LinearLayout` 一行一个 View 全量常驻：600 张卡就是 600 行、
     * 每行 6 张卡、每张卡 11 个 View（约 6600 个 View），一次都不回收。
     * 于是每张卡片的成本是**永久的**：内存只增不减、每次追加 / 每次焦点移动都要在
     * 全部卡片上过一遍（`wireGridFocus` 重写全部焦点链、`gridCards()` 全量 flatMap），
     * 堆上活对象越多 GC 也越贵。加载越多越卡就是这么来的。
     *
     * 现在列表高度由两个 Space 撑出来（纯算术，不需要测量），中间只放
     * `[gridWindowStart, gridWindowStart + gridRows.size)` 这几行。因此：
     * View 数、每次追加的工作量、每次滚动的重排量都与「已加载多少」无关，只与屏幕高度有关。
     */
    internal var gridWindow: LinearLayout?=null
    internal var gridTopSpacer: View?=null
    internal var gridBottomSpacer: View?=null
    /** 当前显示的全部条目（窗口化之后不再是一行一个 View）。 */
    internal var gridItems: List<Series> = emptyList()
    /** 一行的像素高度（含行间距），首帧量一次；两个 spacer 的高度按它算。 */
    internal var gridRowStride=0
    /** 当前物化的第一行在整份列表里的行号；[gridRows] 与它连续。 */
    internal var gridWindowStart=-1
    /**
     * 正在同步窗口。
     *
     * [syncGridWindow] 会被三条路径重入：滚动回调、[focusGridIndex] 里的滚动 + 自己的同步、
     * 以及 `requestFocus` 经 `ScrollView.requestChildFocus → scrollTo` 再回到滚动回调。
     * 内层调用如果照常挪窗口，就可能把「正要获得焦点的那一行」回收掉，焦点随即落空。
     * 用这把标志把内层调用挡掉：外层那次算出来的窗口已经包含了焦点行。
     */
    internal var gridSyncing=false
    /** 当前物化的行，按屏幕顺序；焦点链只在这几行之间维护。 */
    internal val gridRows=mutableListOf<GridRow>()
    /** 回收的行：滚出窗口的行进这里，滚进来的行优先从这里取，不再新建 View。 */
    internal val gridRowPool=mutableListOf<GridRow>()
    internal var gridCardWidth=0
    internal var gridGap=0
    internal var gridPosterHeight=0
    /** 列表重建期间产生的滚动（位置恢复、焦点回带、内容夹取）都不算用户滚动。 */
    internal var gridRestoring=true
    /** 下一页请求在途：静默预取与用户按下共用这一把锁，同一页不会请求两次。 */
    internal var moreInFlight=false
    internal var moreInFlightPage=0
    /** 这次在途的请求是用户按出来的（底部显示「正在加载更多…」），还是静默预取（不打扰）。 */
    internal var moreVisible=false
    /** 在途的请求落地之后要不要把焦点交给新行的第一张卡（静默预取途中被用户按下时也会打开）。 */
    internal var moreFocusWanted=false
    /** 预取失败：底部改成重试文案，不再自动重试，免得断网时反复打接口。 */
    internal var moreFailed=false
    internal var gridPrefetchJob: java.util.concurrent.Future<*>?=null
    internal val tabState=mutableMapOf<Int,CatalogState>()
    internal val tabFocus=mutableMapOf<Int,String>()
    /**
     * 本次运行里已经进过哪些标签页。
     *
     * 用来判断「首次进入」：分类页与榜单页的**首次**落点是页头那枚芯片
     * （分类页是二级分类的「全部」、榜单页是当前榜单「漫剧」），而不是内容第一张卡——
     * 用户先要在这两页的页头挑一个，再往下看内容（见 [switchTab]）。
     * 从首页剧架进榜单、点芯片换分类也算「进过」（见 [switchRankBoard] / [selectCategory]），
     * 因此第二次进同一页时走原来的落点（内容第一张卡）。
     */
    internal val tabVisited=mutableSetOf<Int>()
    internal val nav=mutableListOf<View>()
    /** 圆形入口按 key（search / category / …）索引的那一份，与 [nav] 指向同一批控件。 */
    internal val navKeyed=linkedMapOf<String,View>()
    /** 排行榜页的四个榜单切换芯片（漫剧 / 真人 / AI剧 / 上新）。 */
    internal val boardButtons=mutableMapOf<RankBoard,View>()
    /** 「我的」页的四个栏目切换芯片（收藏 / 历史 / 点赞 / 预约）。 */
    internal val accountButtons=mutableMapOf<String,View>()
    /**
     * 「我的」页那一行身份控件（头像 / 昵称 / 退出 / 刷新）。
     *
     * 它在导航栏下面、内容区上面，因此焦点链是「芯片 ↓ 这一行 ↓ 第一张卡」，
     * 而不是直接跳到内容——方向上是连续的（见 [wireGridFocus]）。
     */
    internal var profileHeadRow: View?=null
    /**
     * 「我的」页那一枚圆形头像。
     *
     * 单独留一份引用是因为它**不在内容区里**：切换栏目 / 切筛选只重建内容区
     * （见 [refreshAccountListIfVisible]），而那条路径会 `artwork.cancelPage()`
     * 把所有已绑定的 ImageView 清空——头像也在这份名单里，没人重新加载就会变成空白。
     */
    internal var profileAvatar: android.widget.ImageView?=null
    /** 「我的」页身份行下方的登录 / 导入内嵌面板（不是弹窗，默认收起）。 */
    internal var accountPanelHost: android.widget.LinearLayout?=null
    /** 内嵌登录面板当前模式：`null` 表示收起，取值见 [ACCOUNT_PANEL_QR] / [ACCOUNT_PANEL_PHONE]。 */
    internal var accountPanelMode: String?=null
    /**
     * 未登录时「我的」页选中的登录方式（二维码 / 手机号）。
     *
     * 与 [accountPanelMode] 分开记：面板收起后仍然保留上一次的选择，
     * 页面整体重建（切标签、退出账号）时用户看到的还是刚才那一种。
     */
    internal var loginMode: String=ACCOUNT_PANEL_QR
    /** 「二维码登录 / 手机登录」两枚入口（mode → 芯片），焦点链与选中态都按它接线。 */
    internal val loginModeChips=linkedMapOf<String,View>()
    /** 打开面板的那枚按钮：收起时把焦点还回去。 */
    internal var accountPanelTrigger: View?=null
    /** 面板里的一次性局域网会话（手机扫码上传）：切换 / 收起时立刻关掉。 */
    internal var accountPanelSession: java.io.Closeable?=null
    /** 面板重建计数：异步回调据此判断自己那一份面板是否还在屏幕上。 */
    internal var accountPanelGeneration=0
    /**
     * 分类页当前选中的筛选条件（体裁 / 主题 / 设定 / 背景 / 推荐 / 受众 / 时间 / 状态）。
     * 默认是官方 App 的「真人剧 + 全部推荐」。
     */
    internal var categorySelection=CategorySelection()
    /**
     * 分类页的筛选面板。由分类接口一次返回（`selector_rows`），
     * 行与选项都不再写死在客户端。
     */
    internal var categoryPanel: CategoryPanel?=null
    /** 分类页每一行筛选项的芯片：type → selector_item_id → 芯片。 */
    internal val categoryFilterButtons=mutableMapOf<String,MutableMap<String,View>>()
    internal var searchInput: View?=null
    internal var searchButton: View?=null
    internal var voiceButton: View?=null
    internal val searchActions=mutableListOf<View>()
    /** 语音界面正在前台：防止连按重复拉起。 */
    internal var voiceLaunching=false
    /** 语音界面已经返回，等待页面重建后消费结果（见 [applyVoiceReturn]）。 */
    internal var voiceReturned=false
    /** 拉起语音前的输入框草稿：取消 / 失败时原样还回去。 */
    internal var voiceDraft: String?=null
    /** 识别出的搜索词，等页面重建完成后再执行。 */
    internal var pendingVoiceQuery: String?=null
    /**
     * 这一次搜索页重建不要抢输入框焦点。
     *
     * 语音界面返回时输入框一旦 `requestFocus`，系统输入法会跟着弹出来——
     * 用户只是说了句话，不该看到键盘。由 [searchBar] 消费一次。
     */
    internal var suppressSearchFocusOnce=false
    // —— 搜索提示词（联想词）——
    /**
     * 提示词那一行（横向可滚的芯片行）与它里面的按钮。
     *
     * 输入框每变一次就往后台丢一次请求（[SUGGEST_DEBOUNCE] 去抖），结果回来时
     * **只重建这一行**，不整页重画——否则每敲一个字页面都要闪一下。
     */
    internal var suggestRow: HorizontalScrollView?=null
    /** 提示词那一行的**硬裁剪视口**（与分类页筛选行同一个类）：显隐与高度都改它。 */
    internal var suggestViewport: View?=null
    internal var suggestLine: LinearLayout?=null
    internal val suggestButtons=mutableListOf<View>()
    /** 这一行的请求令牌：晚回来的旧结果直接丢掉，不会盖住新关键词的提示词。 */
    internal val suggestToken=java.util.concurrent.atomic.AtomicLong(0)
    internal var suggestJob: java.util.concurrent.Future<*>?=null
    internal val suggestRun=java.util.concurrent.atomic.AtomicReference<Runnable?>(null)
    internal var detail: Detail?=null
    internal var episodeIndex=0
    internal var synopsisExpanded=false
    /** 详情页是否正在看「相关剧集」：展开时海报缩到与「开始观看」底边平齐，下方的选集换成相关剧集。 */
    internal var relatedOpen=false
    /**
     * 相关剧集的取数状态（后台预取，用户无感）。
     *
     * 流程：**详情一取回来就先渲染内容**，同时把相关剧集丢到后台去查（[prefetchRelated]）。
     * 用户点「相关剧集」时不再发新请求——取好了直接铺，还在途中就等它落地
     * （那点时间差通常察觉不到）。相关剧集现在是 App 详情页那一个「相关作品」接口
     * （`GET /reading/bookapi/plan/v:version/`，见 [ContentRepository.related]），
     * 一次请求就带回全部条目，整页的可见时间不受影响。
     */
    internal var relatedWorks: RelatedWorks?=null
    internal var relatedLoading=false
    internal var relatedFailed=false
    /** 相关剧集这条请求自己的令牌与句柄：它不能走 [work]（那条路会被本页重排取消掉）。 */
    internal val relatedToken=java.util.concurrent.atomic.AtomicLong(0)
    internal var relatedJob: java.util.concurrent.Future<*>?=null
    /** 正在预取 / 已预取的是哪一部剧，避免同一部剧重复发请求。 */
    internal var relatedFor=""
    internal var player: ExoPlayer?=null
    internal var video: RemoteVideo?=null
    internal var playbackReady=false
    internal var pausedForLifecycle=false
    internal lateinit var hud: LinearLayout
    internal lateinit var playbackText: TextView
    internal lateinit var progressBar: SeekIndicator
    internal lateinit var controls: LinearLayout
    internal var transportIcon: ImageView?=null
    internal var qualityOption: TextView?=null
    internal var speedOption: TextView?=null
    internal var frameOption: TextView?=null
    internal var danmakuOption: TextView?=null
    internal var sleepOption: TextView?=null
    // ---- 触屏适配（P0）：只有真的收到过一根手指才会被点亮，电视上恒为 false ----
    /** 这一次运行里是否已经收到过 `ACTION_DOWN`（见 [onTouchSeen]）。 */
    internal var touchArmed=false
    /** 播放页手势层（`null` = 电视，或还没进过播放页）。 */
    internal var touchLayer: FrameLayout?=null
    /** 播放控制条上那一排可点按钮（只挂在触屏设备上）。 */
    internal var touchBar: LinearLayout?=null
    /** 控制条上的播放 / 暂停键：文案与图标都跟着播放状态走（见 `updateTouchPlayLabel`）。 */
    internal var touchPlayLabel: com.hgtv.star.tv.TvButton?=null
    /** 手势层上「快进 10 秒」那条瞬时提示。 */
    internal var touchSeekHint: TextView?=null
    /** 手指正在拖进度条：这一段时间进度与时间由手指决定，刷新不能顶回去。 */
    internal var scrubbing=false
    internal var panel=false
    internal var playbackMenu: PlaybackMenu?=null
    /** 播放设置面板的外壳（固定屏幕下三分之一、负责裁剪与背板渐变）；摘浮层要摘这一层。 */
    internal var playbackMenuShell: View?=null
    /**
     * 播放设置面板的**实例缓存**（见 `MainActivityPlaybackMenu.kt` 的 [obtainPlaybackMenu]）。
     *
     * 面板一次要建八九行、三十来枚按钮，每枚都是一块新拟态底板——全在 UI 线程。
     * 短按菜单键那一下就是弹幕停一下、遥控器迟一拍。缓存之后重复打开只剩「挂上去 +
     * 同步选中态」，不再重建控件。
     */
    internal var playbackMenuCache: PlaybackMenu?=null
    /**
     * 缓存那一份面板的行结构指纹（见 `menuShape`）：`s1c1` 这类两位编码，
     * 第一位 = 有没有弹幕外观那三行，第二位 = 有没有「时间」那一行（只有正片有）。
     * 指纹不同就不能复用——行数不一样，焦点下标会全错位。
     */
    internal var playbackMenuCacheShape: String?=null
    /** 预热播放设置面板的排队任务（见 [warmPlaybackMenu]）：同一时刻只留一个。 */
    internal var warmPlaybackMenuTask: Runnable?=null
    /** 本次观看选中的定时项目下标（0=关闭），只用来决定菜单里的选中态。 */
    internal var sleepChoice=0
    internal val sleepLabels=listOf("关闭","15 分钟","30 分钟","60 分钟","90 分钟","本集播完","再 3 集","再 5 集")
    internal var speedDialog: TvDialog?=null
    /** 设置页「每行卡片」选择弹窗（4 / 5 / 6 / 7 / 8 张，全局）；与其余选择器一样防重复打开。 */
    internal var homeColumnsDialog: TvDialog?=null
    /**
     * 设置页重建后要落回的那一行（按 `tag` 匹配）。
     *
     * 目前只有「导航栏圆形按钮」这个开关用到：它改的是页面级结构（导航栏整排建 / 不建），
     * 必须整页重建；不记落点的话焦点会被系统交给新布局里第一个可聚焦控件。
     */
    internal var settingsPendingFocus: String?=null
    /**
     * 「全剧已播完 → 换一部接着看」那一层：底部弹出的相关剧集卡片（见 `showSeriesFinished`）。
     *
     * 它只在播放界面出现，出现时播放器已经暂停在最后一帧上；[finishedNotice] 是同一件事的
     * 「正在查找相关剧集」提示（还没取到数据时的过渡层），两者不会同时存在。
     */
    internal var finishedPanel: com.hgtv.star.player.RelatedPanel?=null
    internal var finishedNotice: TvDialog?=null
    internal var pendingSeek: Long?=null
    internal var playError=false
    private var lastSaved=0L
    internal var quality=""
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            // 推荐页在「整屏一行提示」时还没有播放层（hud 是 `playEpisode` 建的），
            // 这一支必须先确认它已经存在，否则冷启动取不到推荐时每秒一次的 tick 会直接崩。
            if((screen=="player" || screen=="recommend") && ::hud.isInitialized) {
                if(sleepTimer.poll()) stopForSleep()
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                // 时间单独显示时（「始终显示时间」），控制层收起后也要每分钟跳到新的时间。
                else playerClock?.takeIf { it.visibility==View.VISIBLE }?.let { clock ->
                    val now=clockFormat.format(java.util.Date())
                    if(clock.text.toString()!=now) clock.text=now
                }
                maybePrefetch()
                maybeQueueNext()
                syncDanmakuClock()
                if(screen=="recommend") updateRecommendOverlay()
                val now=android.os.SystemClock.elapsedRealtime()
                // 时长按秒记一份：片尾自动推进时 `STATE_ENDED` 不会触发，补 completed 记录要用它。
                // 只在播放器报的确实是**当前这一集**时才记：片尾换 period 的那一瞬，
                // `duration` 已经是下一集的，照抄会把上一集的 completed 记录写错长度。
                val current=detail?.episodes?.getOrNull(episodeIndex)
                if(playbackReady && player?.currentMediaItem?.mediaId==current) {
                    player?.duration?.takeIf { it>0 }?.let { lastEpisodeDuration=it }
                }
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    internal val hideHud=Runnable { if(screen=="player" && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    internal val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    // ---- 播放过程中要按「同一个对象」重用的字段 ----
    // 这两枚 Runnable 会在 removeCallbacks 里按身份比对，必须是字段（扩展属性没有初始化器，
    // 写成 getter 会每次返回新对象、removeCallbacks 就失效了）。
    internal val restoreBuffering=Runnable { restoreBufferingUi() }
    internal val retireForced=Runnable {
        // 3 秒兜底。只有确认那一项**确实已经不在播放列表里**才关：
        // 还在列表里就关，ExoPlayer 重新 prepare 时会读到已关闭的数据源。
        val stillListed=playedItemId.isNotEmpty() && (0 until (player?.mediaItemCount ?: 0)).any { player?.getMediaItemAt(it)?.mediaId==playedItemId }
        if(!stillListed) { playedItemPending=false; playedItemId=""; retirePreviousEpisode() }
    }

    // ---- 生命周期与系统回调：扩展成员不能 override，只能留在 Activity 本体里 ----
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        // 主题要在建视图之前定下来：先落盘偏好 → 换色 → 丢掉柔光位图缓存（里面烤着上一套材质的投影）。
        // 窗口底色也跟着换，启动那一帧、页面切换的空档都不会闪出另一套材质。
        TvStyle.applyTheme(TvThemeStore.load(this))
        applyWindowBackground()
        // 性能档位：低配设备保留全部视觉层级，只做四件「纯减负」的事
        // （关焦点缩放 / 只给焦点卡画柔光位图 / 不画海报井暗角 / 柔光收紧一档）。
        //
        // 这里只按**设备**判据先摆好第一帧；本机库里那份「减少动画」偏好要等
        // `loadLibrary()` 读回来，由 [applyPerformanceProfile] 再算一次（见那里）。
        //
        // 判据用 [lowEndTv]，不是 `isLowRamDevice()`：后者来自 `ro.config.low_ram`，
        // 2GB 的电视盒子几乎一律是 false，只看它整个应用就按高端机跑
        // （这正是「按一下方向键就顿一下」的根源，见 compat/Platform.kt 的说明）。
        applyPerformanceProfile()
        library=Library(this); artwork=ArtworkLoader(this); tvTools=TvTools(this)
        // 内存吃紧时由界面把**视口之外**的封面从 ImageView 上摘掉：LRU 的 evictAll 只解除
        // 自己的引用，位图挂在 View 上就仍然活着——首页常驻 77 张（约 44MB）正是这一份。
        artwork.releaseHiddenCovers=::releaseOffscreenCovers
        // 调试模式上次开着的话，从这一刻起就把网络 / 接口的日志记进那块面板：
        // 启动阶段的那几十条（TLS、设备注册、首页接口）正是排查「打开就空白」要看的东西，
        // 等到第一页建好再打开就全错过了。
        DebugLog.enabled=runCatching { library.debugMode }.getOrDefault(false)
        // 掉线广播：心跳确认会话失效后发过来（见 [sessionExpiredReceiver]）。
        // targetSdk 29 上显式广播（`setPackage`）不需要 RECEIVER_NOT_EXPORTED 那一套。
        runCatching {
            registerReceiver(
                sessionExpiredReceiver,
                android.content.IntentFilter(com.hgtv.star.data.SessionEvents.ACTION_SESSION_EXPIRED)
            )
        }
        // 封面加载的计时汇总写到 filesDir：真机上抓 logcat 要连电脑、环形缓冲还会把
        // 前面的行冲掉，而「按住下键走几百张」之后那份汇总正是要看的东西。
        // 每次启动**清空重写**，因此文件里永远是最近这一次运行的数据。
        if(com.hgtv.star.BuildConfig.DEBUG) {
            val timingFile=java.io.File(filesDir,"artwork-timing.txt")
            runCatching { timingFile.writeText("") }
            artwork.attachTiming(timingFile)
        } else {
            // release **不生成**这个文件：它是调试用的计时汇总（真机上抓 logcat 不方便时看它）。
            // 这里顺手把调试版留下的那一份删掉——从 debug 换到 release 覆盖安装时它会留在
            // `filesDir` 里，既没用又占地方。
            runCatching { java.io.File(filesDir,"artwork-timing.txt").delete() }
            runCatching { java.io.File(filesDir,"verbose").deleteRecursively() }
        }
        root=FrameLayout(this).apply { setBackgroundColor(bg); clipChildren=false; clipToPadding=false }
        setContentView(root)
        // 有触摸屏就在建第一屏之前点亮触屏适配：否则第一屏、第一次点按之前建出来的
        // 推荐页浮层 / 播放页控制条都是电视形态，点一下之后整块重建成触屏形态，
        // 两次看到的样子不一样（见 [armTouchIfScreenExists]）。
        armTouchIfScreenExists()
        // 焦点回到页头（品牌 / 分类开关 / 顶部入口）时立刻展开，
        // 否则收起的页头高度是 0，焦点会落在看不见的按钮上。
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _,newFocus ->
            if(topAreaHidden && newFocus!=null && topArea.any { it===newFocus || it.findFocus()!=null }) setTopAreaHidden(false)
            // 记住最近一次焦点落在哪个控件上：右下角「回到顶部」的上 / 左要按回**用户离开列表时
            // 那一张卡**（见 list/BackToTop.kt 的 wireBackToTop）。浮层自己拿到焦点时不覆盖。
            if(newFocus!=null && newFocus!==backToTopView && newFocus.attachedToWindow) backToTopReturn=newFocus.id
            // 焦点移动常会带动列表滚动（reveal 把焦点那一行滚进视口），而那次滚动可能落在
            // 静默期 / `restoring` 里不被上报。等一帧再按真实位置把页头状态摆正（见 [syncHeaderState]）。
            root.post { syncHeaderState() }
        }
        connectivity=connectivityManager() ?: throw IllegalStateException("系统缺少网络服务")
        runCatching {
            networkWatch=com.hgtv.star.compat.NetworkWatcher.watch(this) {
                main.post {
                    // 面板上的「网络」是 5 秒缓存（见 DebugEnv）：切网要当场作废，
                    // 否则用户看着面板会以为还挂在旧网络上。
                    DebugEnv.invalidate()
                    if(connectivity.isMeteredConnection() || !networkAvailable()) cancelPrefetch()
                    if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery()
                }
            }
        }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        appUpdater=AppUpdater(this,{
            // 「空闲」= 在前台、不在播放界面、没有模态框。播放中与弹窗里绝不打扰。
            foreground && screen!="player" && screen!="recommend" && !tvTools.showing
        }, { anchor, cache, done -> tvTools.updateUpload(anchor,cache,done) })
        // 媒体会话不在这里创建：启动即建会把容器/ROM 的 media_session 故障放大成闪退，
        // 第一次同步播放状态时再按需创建（见 ensureMediaSession）。
        // Android 6–9 还要一次外部存储授权，会话镜像（设备身份 + sessionid）才写得出去——
        // 有了它，卸载重装才不必重新走一轮「注册 → 登录 → 发短信」。其他版本这里是空操作。
        requestMirrorStorage()
        // 调试包的启动期故障注入（见 [DebugFaults]）：正式包里 BuildConfig.DEBUG 是常量 false，
        // 整段被 R8 删掉。
        if(com.hgtv.star.BuildConfig.DEBUG) main.postDelayed({
            when(DebugFaults.startupFault(this)) {
                // 主线程卡死 60 秒。ANR 阈值是 5 秒，但模拟器/部分 ROM 会用
                // `ro.hw_timeout_multiplier` 把它放大（这台是 5 倍 = 25 秒），所以留足余量。
                // 注意：**必须同时有输入事件在排队**，系统才会判 ANR——注入期间持续按方向键。
                DebugFaults.Mode.ANR -> Thread.sleep(60_000)
                // 直接让 native 段崩一次（SIGSEGV）：验证 U-APM 的 native 采集链路。
                // 不用 SDK 自带的测试接口——友盟没有那个入口，而真实崩溃更接近线上形态。
                DebugFaults.Mode.NATIVE_CRASH -> {
                    val crash=(Class.forName("java.lang.Long").getField("TYPE").get(null) as Class<*>)
                    crash.cast(null)
                }
                else -> Unit
            }
        },4000)
        message(base(),"正在读取本机记录…")
        loadLibrary()
    }

    /** 文件选择器与系统语音的回传入口；真正的读取 / 搜索都交给 [onResume] 末尾消费。 */
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==REQUEST_VOICE) {
            voiceLaunching=false
            voiceReturned=true
            // 语音界面返回后页面会重建：这一次别让输入框抢焦点、别弹输入法。
            suppressSearchFocusOnce=true
            val spoken=if(resultCode==RESULT_OK)
                data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }
            else null
            val clean=normalizeVoiceQuery(spoken.orEmpty())
            if(clean.isEmpty()) {
                noteVoice(if(resultCode==RESULT_OK) "empty" else "cancel")
                if(resultCode==RESULT_OK) showHint("没有听清，请再说一次")
            } else {
                pendingVoiceQuery=clean
                noteVoice("success")
            }
            // 识别界面如果是对话框（本页没有 onStop），onResume 不一定还会来一次；
            // 这里也投一次，[applyVoiceReturn] 自己保证只消费一次。
            main.post { applyVoiceReturn() }
            return
        }
        if(requestCode!=REQUEST_ACCOUNT_FILE || resultCode!=RESULT_OK) return
        pendingAccountUri=data?.data
    }

    /** 外部存储授权回来：立刻重试一次镜像恢复（见 [onMirrorStorageResult]）。 */
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,results: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,results)
        // 清除本地数据：拿到权限（或被拒绝）都继续清，只是 `.hgtv` 那一份删不删得掉的区别。
        if(requestCode==REQUEST_RESET_STORAGE) { performLocalReset(); return }
        if(requestCode!=REQUEST_MIRROR_STORAGE) return
        onMirrorStorageResult(results.isNotEmpty() && results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    // ---- 导航与返回 ----
    internal fun returnToDetail(forceDetail: Boolean=false) {
        sleepTimer.cancel(); sleepChoice=0; sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); detachPlayerSurface()
        playbackFromRecommend=false
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
    }
    private fun goBack() {
        if(!library.isLoaded) { confirmExit(); return }
        when(screen) {
            // 播放设置开着时先收菜单（浮层，返回键收浮层是通用手势）；
            // 从推荐页进的正片返回时**直接回主页**（需求 4），不经过简介页。
            "player" -> when {
                playbackMenu!=null -> hidePlaybackOverlay()
                playbackFromRecommend -> leaveRecommendPlaybackToHome()
                hud.visibility==View.VISIBLE && !playError -> hidePlaybackOverlay()
                else -> returnToDetail()
            }
            // 推荐播放界面：返回键回主页面（需求 2 / 5）——不写历史、不回详情。
            // 打开着的浮层（播放设置 / 触屏那排按钮）由 `dispatchKeyEvent` 先一层层收掉，
            // 走到这里说明屏幕上已经空了，这一下才是真的「回主页」。
            "recommend" -> exitRecommendToHome()
            "detail" -> showCatalog()
            // 品牌不再是按钮，返回键就是唯一的「回主界面」入口：集合页回到它所在的标签（首页），
            // 搜索 / 排行榜 / 设置直接切回首页；已经在首页时再按就是**退出**（按两次，见 [confirmExit]）。
            //
            // 首页那一下不再先把焦点搬到顶部入口：那一步只是把焦点挪到「搜索」图标上，用户读到的
            // 是「按返回焦点乱跑」；页头收起时那一行的高度是 0，焦点请求根本没有可见的落点，
            // 连下一次「焦点在入口上」的判断都成立不了——表现出来就是「按多少次返回都没反应、
            // 也退不出去」。因此首页的返回一律直接走「再按一次退出」。
            // 「问题反馈」页：返回键回到进来之前那一页，不再往首页退。
            else -> when {
                feedbackOpen -> closeFeedbackPage()
                collection!=null -> { collection=null; catalogFocus=""; showCatalog() }
                tab!=TAB_HOME -> switchTab(TAB_HOME)
                else -> confirmExit()
            }
        }
    }
    /**
     * 主界面按返回的退出：**按两次**——第一次只弹一条提示，[EXIT_CONFIRM_WINDOW_MS] 之内
     * 再按一次才 [finish]。
     *
     * 不再弹确认框：电视上没有指针，确认框每次都要用户把焦点从「取消」挪到「退出」，
     * 比再按一下返回慢；而「再按一次退出」是 Android 上最熟的手势，第一次的提示已经把
     * 「要按两次」说清楚了。退出前也不需要再确认一遍数据：本机记录每次改动都即时落盘，
     * 播放进度在上屏 / 退出播放时已经写过（见 [saveProgress]）。
     */
    private fun confirmExit() {
        val now=android.os.SystemClock.elapsedRealtime()
        if(exitArmedAt!=0L && now-exitArmedAt<=EXIT_CONFIRM_WINDOW_MS) {
            exitArmedAt=0L
            finish()
            return
        }
        exitArmedAt=now
        tvTools.toast(EXIT_TOAST)
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    /**
     * 系统搜索入口一律不走：应用内自带搜索页。
     *
     * 部分电视 ROM 的 `PhoneWindow.launchDefaultSearch` 会把 `ACTION_SEARCH`
     * 解析到一个并不存在的 `com.imtv.speechdemo`，而且不捕获 `ActivityNotFoundException`。
     * 这里挡住「长按 / 系统搜索框」这类入口，遥控器搜索键另见 [dispatchKeyEvent]。
     */
    override fun onSearchRequested(): Boolean = false
    /**
     * 第一次收到手指，就把触屏适配点亮（见 `MainActivityTouch.onTouchSeen`）。
     *
     * **与按键路径完全无关**：这里只读 `MotionEvent`，遥控器的 `KeyEvent` 一个字节都不碰。
     * 电视 / 盒子永远收不到 `ACTION_DOWN`，因此 `touchArmed` 在它们上面恒为 false，
     * 触摸层的三块代码（手势层 / 控制条 / 拖动进度）一次都不会构造。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            if(!touchArmed) onTouchSeen()
            // 「第一下就生效」：先按坐标把焦点交给手指下面那个控件（见 [focusUnderTouch]）。
            // 放在 super 之前是关键——控件在 ACTION_UP 里看到自己已经 isFocused()，
            // 就不会走 AOSP 那条「取到焦点则吞掉这次点击」的分支。
            focusUnderTouch(event.x,event.y)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 正在清除本地数据：所有写盘队列已经关掉，这时任何一次收藏 / 设置改动都会抛异常；
        // 进程马上就会被结束，直接把按键吞掉即可（也避免界面在杀进程前闪一下错误）。
        if(resetting) return true
        // 遥控器「搜索」键（84）：M6 / 10P 这类盒子的 PhoneWindow 会直接调用
        // launchDefaultSearch，把 ACTION_SEARCH 解析到厂商预置、但实际并不存在的
        // com.imtv.speechdemo；该 ROM 又不捕获 ActivityNotFoundException，按键会闪退。
        // 因此任何页面都先吞掉这一下，再按页面决定去向。
        if(event.keyCode==KeyEvent.KEYCODE_SEARCH) {
            if(event.action==KeyEvent.ACTION_UP) when {
                // 播放页 / 推荐页：只是别把系统搜索框叫起来，不打断正在看的片子。
                screen=="player" || screen=="recommend" -> Unit
                // 搜索页：搜索键只把焦点交给输入框（语音入口已经去掉）。
                screen=="catalog" && tab==TAB_SEARCH &&
                    !(::tvTools.isInitialized && tvTools.showing) ->
                    main.post { searchInput?.takeIf { it.attachedToWindow }?.requestFocus() }
                else -> {
                    switchTab(TAB_SEARCH)
                    // 搜索页带着上一次的关键词进来时，输入框不会自动取焦点（见 searchBar 的
                    // requestFocus 条件）；用户按搜索键的意图是「来打字 / 说话」，这里补一次。
                    main.post { searchInput?.takeIf { it.attachedToWindow }?.requestFocus() }
                }
            }
            return true
        }
        // 遥控器麦克风键：搜索页不再接管（语音入口已经去掉），一律交给系统语音助手。
        // 导航栏圆形入口整排关掉之后，菜单键补成「前往某页」：那条排入口是切换标签的唯一常驻
        // 入口，关掉之后返回键只回首页、搜索键只进搜索页，分类 / 榜单 / 我的 / 设置没有别的路。
        // **焦点在卡片上时不接管**：那一下是长按菜单（收藏 / 稍后看 / 隐藏），由卡片自己的监听器吃。
        if(event.keyCode==KeyEvent.KEYCODE_MENU && event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 &&
            screen=="catalog" && library.isLoaded && nav.isEmpty() && !focusOnCard()) {
            showNavPicker(currentFocus ?: navEntry())
            return true
        }
        // 推荐播放界面（类似抖音）：上下换剧、确定进正片、菜单开播放设置、返回回主页面。
        //
        // 这一层**必须在播放器分支之前**：上下键不能落进 `playerKeyAction`（那里上=播放设置、
        // 下=选集），而推荐页明确要求这两下只换作品。播放设置菜单打开时不再接管方向键，
        // 交给菜单自己的焦点链（下面播放器分支里 `!panel` 的条件就是为这个留的）。
        if(screen=="recommend") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) {
                if(event.action==KeyEvent.ACTION_UP) {
                    // 返回键收的是一层浮层，**一次一层**，收完最后一层才回主页面：
                    //   1. 播放设置（菜单键打开的那块竖向面板）——它盖住了下半屏，
                    //      用户按返回的意图几乎总是「把这块收掉」，而不是退出这一页；
                    //   2. 触屏那一排按钮 / 剧名浮层（电视上没有这一排，`recommendBarShown()`
                    //      恒为 false，这一支在遥控器上不存在）；
                    //   3. 都收干净了，才走 [goBack] 回主页面。
                    // 以前这里没有第 1 层：菜单开着按返回会直接退出推荐页，用户看到的
                    // 是「设置还在屏幕上、画面却换了」。
                    when {
                        playbackMenu!=null -> hidePlaybackOverlay()
                        recommendBarShown() -> setRecommendBar(false)
                        else -> goBack()
                    }
                }
                return true
            }
            if(playbackMenu!=null) {
                // 播放设置打开时：菜单键收起它（按下 / 抬起都吃掉，别让系统菜单插一脚），
                // 方向键与确定键交给菜单自己的焦点链。
                //
                // **打开菜单的那一次按键不能顺手把它收起**：短按菜单键会先来 DOWN（下面
                // 那一支开菜单）、再来同一次按键的 UP。以前这里对 UP 无条件收起，于是菜单
                // 一闪就没了，用户必须长按住（UP 来得晚）才看得见——正是「播放设置显示出来
                // 就隐藏了」。用 [playbackMenuOpenPress] 认掉这一次抬起：只吞键、不收起。
                if(key==KeyEvent.KEYCODE_MENU) {
                    if(event.action==KeyEvent.ACTION_UP) {
                        if(playbackMenuOpenPress) playbackMenuOpenPress=false
                        else hidePlaybackOverlay()
                    }
                    return true
                }
            } else {
                if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                    // 上下：只换剧（需求 2）。按住不连发：换一部要等详情 / 起播，
                    // 连发只会把中间几部白白跳过。
                    KeyEvent.KEYCODE_DPAD_UP -> if(event.repeatCount==0) { switchRecommend(-1); return true } else Unit
                    KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) { switchRecommend(1); return true } else Unit
                    // 左右：浮层露着时是「在这排按钮里选」，收起时才是快退 / 快进（需求 3）。
                    // 同一枚键在同一页有两种含义，判据只有「看得见与否」——用户看到那排按钮，
                    // 就期待左右把它选过去，而不是画面在背后快进。
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if(recommendBarShown()) moveRecommendBarFocus(-1) else seekRecommend(-1,event.repeatCount)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if(recommendBarShown()) moveRecommendBarFocus(1) else seekRecommend(1,event.repeatCount)
                        return true
                    }
                    // 确定键：焦点已经落在那一排按钮上时**交回焦点系统**去按（返回 false 即可，
                    // 框架会对焦点控件做 performClick）；没落在按钮上才是「追剧」。
                    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> {
                        if(recommendBarShown() && recommendBarFocused()) return false
                        enterFormalPlayback(); return true
                    }
                    // 记下「菜单是被这一下按开的」：它的 UP 随后就到，不能当成再按一次。
                    // 只有真的开出来了才记——停在「正在加载 / 取不到推荐」那一屏时没有播放层，
                    // showPlaybackMenu() 会直接返回，这一位留着没有意义。
                    KeyEvent.KEYCODE_MENU -> {
                        showPlaybackMenu()
                        playbackMenuOpenPress=playbackMenu!=null
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { switchRecommend(1); return true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { switchRecommend(-1); return true }
                    KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { requestPlayback(true); return true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { requestPlayback(false); return true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekRecommend(1,0); return true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { seekRecommend(-1,0); return true }
                }
                // 确定键落在那一排按钮上时，按下与抬起**都**交回框架：只放行按下的话，
                // 框架收不到 ACTION_UP，按钮只会高亮而不会真的按下去。
                if((key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER) &&
                    recommendBarShown() && recommendBarFocused()) return false
                // 推荐页没有可聚焦控件：方向键 / 确定 / 菜单一律吞掉，避免焦点跑出画面。
                // 音量、电源这类系统键必须交回框架，否则遥控器就调不了音量了。
                if(key in listOf(
                        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_MENU
                    )) return true
            }
        }
        if(screen=="player" || screen=="recommend") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> if(screen=="recommend") switchRecommend(1) else skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if(screen=="recommend") switchRecommend(-1) else skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> when {
                        screen=="recommend" -> exitRecommendToHome()
                        // 从推荐进的正片：停止 = 回主页（与返回键同一条路，见需求 4）。
                        playbackFromRecommend -> leaveRecommendPlaybackToHome()
                        else -> returnToDetail()
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            // 播放设置菜单打开时（[panel]）：方向键一律交给焦点系统，PlaybackMenu.wire() 已把四向
            // 锁在菜单内部，停在最后一行（定时）再按下键，焦点按 nextFocusDownId 落在自己身上、
            // 原地不动。这里**不能**再接管下键去弹选集：菜单是「上键」打开的浮层，用户在里面按到
            // 边缘只应该表示「到头了」，而不是顺带换一个界面（选集仍然可以由菜单关闭后的下键、
            // 底部居中的下拉箭头，以及返回键回到简介页进入——下键绑成什么由按键设置决定）。
            //
            // 六个方向键 / 菜单键 / 确定键在播放界面里**不做焦点移动**，一律按当前绑定去执行动作
            //（绑定与出厂值见 [com.hgtv.star.core.KeyMap]：上 / 菜单 = 播放设置、下 = 选集、
            // 左 = 快退、右 = 快进、确定 = 播放 / 暂停）。这一条以前是写死的 `when(key)`，
            // 现在按键设置页可以改，改完立刻生效、并随本机偏好与备份一起走。
            //
            // 播放界面上本来就没有可聚焦的按钮（控制条的图标与状态项都不抢焦点），
            // 因此这里不去「把键放给焦点系统」，直接把这一下消化掉：
            // 绑成「无」的键也只是什么都不做，不会让焦点跑到别处去。
            // 返回键在上面已经单独处理，任何绑定下都退得出播放界面。
            if(!panel) playerKeyAction(key)?.let { return dispatchPlayerKey(it,event) }
        }
        return super.dispatchKeyEvent(event)
    }
    /**
     * 焦点是不是落在某张剧卡上（含卡片内部的子控件）。
     *
     * 卡片自己接管菜单键 = 长按菜单；导航兜底不能抢这一下（见 [dispatchKeyEvent]）。
     */
    private fun focusOnCard(): Boolean {
        var view=currentFocus ?: return false
        while(true) {
            if(view is PosterCard) return true
            val parent=view.parent as? View ?: return false
            if(parent===root) return false
            view=parent
        }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        // 屏幕参数变了（分辨率 / dpi / 语言）：面板与列表里的尺寸都是建那一刻的像素，
        // 缓存的那一份必须丢掉，否则会按旧的像素间距摆在新屏幕上（见 [dropPlaybackMenuCache]）。
        dropPlaybackMenuCache()
        when(screen) {
            "player" -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "recommend" -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "detail" -> if(detail!=null) showDetail(false)
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume()
        // 清除本地数据期间：写盘队列已经关掉，这一轮页面重建里的任何一次缓存写入都会抛异常。
        // 进程马上就会被结束，这里直接跳过即可（授权弹窗关闭后会走到这里）。
        if(resetting) return
        // 从语音界面回来：这一次重建不要把焦点交给输入框（否则软键盘会弹出来）。
        // 结果本身可能在 `onActivityResult` 里晚一步到，这里只先压住焦点。
        if(voiceLaunching) suppressSearchFocusOnce=true
        // 个别 ROM 不回调 onActivityResult：按取消收尾，别让「语音」按钮永久失效。
        // 延迟判定而不是当场判定——`onActivityResult` 通常先于 `onResume` 到达，
        // 但顺序并非所有 ROM 都一致；留一小段时间给它，避免把刚拿到的结果当成取消。
        if(voiceLaunching) main.postDelayed({
            if(voiceLaunching) {
                voiceLaunching=false; voiceReturned=true
                noteVoice("cancel")
            }
        },800L)
        foreground=true; main.removeCallbacks(tick); main.post(tick)
        // 回前台补一次心跳：设备休眠 / 厂商省电会让后台定时整体推迟，
        // 后台待两小时醒来时上一次心跳可能已隔了几小时。
        com.hgtv.star.data.SessionBootstrap.onForeground()
        appUpdater.resume()
        if(library.isLoaded) {
            // 冷启动落在推荐页（需求：打开应用自动进入推荐）；设置里关掉之后落点回首页。
            if(initialCatalogPending) {
                if(library.launchRecommend) openRecommend() else { initialCatalogPending=false; showCatalog(load=true) }
            }
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    // 推荐页：列表还在就接着播这一部；列表空了（进程内的推荐状态被别处清过）
                    // 就重新拉一次，别让用户回到一片黑屏上。
                    // 与正式播放一致：从后台回来不自动接着播（用户可能已经按下暂停才切走的）。
                    "recommend" -> if(recommendItems.isEmpty()) openRecommend()
                        else playRecommendAt(recommendIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                    // 首页判空看的是四个剧架（[homeFeed]）：它的 catalog 本来就是空的，
                    // 用 catalog 判断会让每次回到前台都重打四个榜单。
                    else -> showCatalog(load=when {
                        // 集合页的内容是「本机 + 账号」合并出来的：回前台只重建（栅格分支里会
                        // 触发一次 loadAccount，账号那边新收藏 / 新看的剧随之出现）。
                        localCatalog() -> false
                        tab==TAB_HOME -> homeFeed.isEmpty()
                        tab==TAB_RANK -> catalog.isEmpty()
                        tab==TAB_CATEGORY -> catalog.isEmpty()
                        tab==TAB_SEARCH -> query.isNotBlank() && catalog.isEmpty()
                        else -> false
                    })
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
        // 文件选择器回来的那一次：等上面这一轮页面重建（它会 cancelWork）走完，再读文件导入。
        if(pendingAccountUri!=null) main.post { consumePendingAccountFile() }
        // 语音结果同样等这一轮恢复（可能含一次页面重建）走完再落地。
        if(voiceReturned) main.post { applyVoiceReturn() }
    }
    override fun onPause() {
        if(resetting) { super.onPause(); return }
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession?.deactivate(); appUpdater.pause()
        // 退到后台：屏幕常亮跟着松开（见 `syncKeepScreenOn`）。
        syncKeepScreenOn()
        sleepTimer.cancel(); sleepChoice=0; favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); saveProgress(); library.flush(); super.onPause()
    }
    override fun onStop() {
        if(resetting) { super.onStop(); return }
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        // 退到后台**不销毁播放器**（进程内复用，见 [detachPlayerSurface] 的说明）：
        // 画面层、传输、弹幕一起收，但 ExoPlayer 实例留着。回到前台由 onResume 的
        // restoreStoppedPage 接着播，不再走一次 `ExoPlayer.Builder.build()`。
        if(screen=="player") { startPosition=currentPosition(); saveProgress(); detachPlayerSurface() }
        // 推荐页不写进度，只记下位置并卸掉画面；回到前台由 onResume 接着播。
        else if(screen=="recommend") { startPosition=currentPosition(); detachPlayerSurface() }
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    /** 栅格里被低内存策略摘掉封面的卡数：>0 时滚动 / 焦点回调才需要检查「是不是回来了」。 */
    internal var suspendedCovers=0

    /**
     * 把视口之外的封面从 ImageView 上摘掉（低内存专用，见 `ArtworkLoader.releaseHiddenCovers`）。
     *
     * 只摘「不在屏幕上」的那些：`getGlobalVisibleRect` 对滚出视口的卡返回 false，
     * 焦点卡与可见卡不动，否则用户眼前会闪一片灰框。摘掉之后位图才会真的落进
     * LRU 可淘汰的范围，`evictAll()` 才有意义（回读只要 3~10ms/张，且多数命中盘缓存）。
     *
     * **摘掉的是「显示」，不是「地址」**（见 `PosterCard.suspendCover`）：地址留着，
     * 回到视口时 [resumeVisibleCovers] 直接按原地址挂回来。
     */
    internal fun releaseOffscreenCovers() {
        runCatching {
            val rect=android.graphics.Rect()
            // 首页那一份由 HomeScreen 自己数（它的剧架是未窗口化的，77 张常驻，是大头）。
            suspendedCovers=homeScreen?.suspendCoversOutside(rect) ?: 0
            gridRows.forEach { holder ->
                holder.cards.forEach { card ->
                    if(!card.image.getGlobalVisibleRect(rect) && card.suspendCover()) suspendedCovers++
                }
            }
        }
    }

    /**
     * 把重新回到视口里的封面挂回去（滚动、焦点移动、窗口挪动之后都调一次）。
     *
     * `suspendedCovers == 0` 时是**一次整数比较**，平时滚动这条路径的代价为 0。
     */
    internal fun resumeVisibleCovers() {
        if(suspendedCovers<=0) return
        val rect=android.graphics.Rect()
        homeScreen?.resumeCoversInside(rect)
        gridRows.forEach { holder ->
            holder.cards.forEach { card ->
                if(!card.coverSuspended) return@forEach
                if(card.image.getGlobalVisibleRect(rect)) card.resumeCover()
            }
        }
        // 重新数一遍：首页与栅格各占一半，摘掉的那几张可能都回来了。
        suspendedCovers=(homeScreen?.suspendedCoverCount() ?: 0)+
            gridRows.sumOf { holder -> holder.cards.count { it.coverSuspended } }
    }

    /**
     * 4.4~7.x 上的低内存回调（`onTrimMemory` 之外的一条独立路径）。
     *
     * 不实现它，老电视上「整机内存告急」时只有系统日志，进程里什么都不做——
     * 而那正是最该放掉视口外封面的时刻。统一按最重的一档处理。
     */
    override fun onLowMemory() {
        super.onLowMemory()
        releaseOffscreenCovers()
        if(::artwork.isInitialized) artwork.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        runCatching { Soft.trimMemory(0.2f) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 柔光位图是可重建的纯缓存，内存吃紧时先收缩它。
        // **不能整份清空**：清空之后下一帧会把屏幕上每一张可见卡重新生成一遍，
        // 那是一次几百毫秒的主线程尖峰（线上那几条渲染 ANR 正是它）。按比例淘汰旧条目，
        // 可见的那一屏原样保留。
        // 低配只给焦点卡画柔光位图（见 [Soft.shadowBitmaps]），缓存里通常只有一两张，
        // 砍它没有任何收益，反而会把下一次焦点移动变成主线程上的位图重建
        // （`Soft.layers` → `build()` 是在 `draw()` 里同步跑的）。因此低配跳过这一步。
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && Soft.shadowBitmaps) Soft.trimMemory()
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            cancelPrefetch()
            // 推荐页的相邻预热（每部约 1MB）是可重建的纯缓存：内存吃紧时先放掉，
            // 下一次切换会重新预热，不会影响正在放的这一部。
            if(screen!="recommend") closeRecommendCache()
        }
    }
    override fun onDestroy() {
        generation++; cancelWork()
        runCatching { unregisterReceiver(sessionExpiredReceiver) }
        runCatching { networkWatch?.cancel() }; networkWatch=null
        recommendToken.incrementAndGet(); recommendPageToken.incrementAndGet(); closeRecommendCache()
        favoriteMonitor.destroy(); tvTools.destroy(); closeAccountPanelSession(); appUpdater.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession?.release(); mediaSession=null; main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); danmakuWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher().cancelAll(); repository.http.connectionPool().evictAll(); repository.http.dispatcher().executorService().shutdown() },"hgtv-network-cleanup").start()
        super.onDestroy()
    }
}
