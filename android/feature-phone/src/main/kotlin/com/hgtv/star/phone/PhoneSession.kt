// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hgtv.star.core.Detail
import com.hgtv.star.core.PlaybackCache
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.Series

/**
 * 手机端「这一台机器现在在放什么」的进程级事实。
 *
 * ## 为什么需要它
 *
 * 推荐信息流与正式播放页是两个 Activity。推荐页内部用有上限的播放器池，
 * 当前活动实例登记为 [PhoneServices.player]；正式播放页接手的是这一台。早先两边各管各的：
 *
 * * 从推荐进播放页 → 播放页重新取详情、重新取地址、重新 prepare，从头开始；
 * * 从播放页退回推荐 → 信息流再走一遍 `playIndex`，同样重新取地址、重新 prepare。
 *
 * 用户看到的就是「每切一次页面都重新加载、重新起播」，与 TV 上那种「画面不断、只是控制层换了」
 * 完全是两回事。TV 端之所以顺，是因为两端在同一个 Activity 里、共用同一份
 * `detail / episodeIndex / video`（见 `TvMainActivity` 的 `playEpisode` 与 `onEpisodeAdvanced`）。
 *
 * 这里把那份状态搬到进程级：谁设了媒体源，谁就把「在放哪一集、用哪个 [RemoteVideo]」
 * 记在这里；另一个界面接手时**先看这里**，能接就接，接不上才自己取。
 *
 * ## 谁负责关 remote
 *
 * `RemoteVideo` 里挂着未完成的 Range 请求与首块缓存，提前关掉会让正在播的这一集直接断流。
 * 因此约定：**换掉且没有池槽继续使用时才关旧的**（[publish] 的 `closePrevious`）。
 * 推荐池仍保留的上一页不会被关闭，池淘汰该页时才延后关闭。
 */
object PhoneSession {

    enum class Owner { NONE, FEED, PLAYER }

    /** 现在这一集属于谁（哪个界面设的媒体源）。 */
    @Volatile var owner: Owner = Owner.NONE
        private set

    /**
     * 正式播放页当前是否在前台。
     *
     * 播放页是半透明窗口，转屏或部分 ROM 的窗口重排会让下层推荐页收到一次
     * `onResume`。推荐页此时不能接手播放器，也不能注销借 Surface 的入口；
     * 否则两个界面会抢同一个视频输出，解码器随后停在旧帧。
     */
    @Volatile var playerForeground: Boolean = false

    @Volatile var seriesId: String = ""
        private set
    /** 从推荐流带进来的原始卡片，保留 recommend_info 供行为上报。 */
    @Volatile var recommendSeries: Series? = null
        private set
    @Volatile var detail: Detail? = null
        private set
    @Volatile var index: Int = -1
        private set
    @Volatile var remote: RemoteVideo? = null
        private set
    @Volatile var quality: String = ""
        private set

    /**
     * 这一路属于推荐页的哪一条体裁流（`PhoneFeedView.cacheKey()`）。
     *
     * 三条体裁流各自维护播放器池，但它们是**三条独立的流**：漫剧的第 44 部、
     * 真人的第 12 部、AI剧的第 30 部在服务端碰巧可能是同一部剧。早先接手只看
     * `seriesId`，切 tab 时新流会把旧流正在播的那一部当成“自己的当前项”接过来，
     * 于是三个 tab 永远放同一个视频（用户报的就是这一条）。
     *
     * 由信息流 `publish` 时写入；播放页接手 / 换集时**不覆盖**（它放的是从某条流
     * 进去的那一部，退回时仍应回到那条流）。空串 = 电视端 / 尚未起播。
     */
    @Volatile var feedKey: String = ""
        private set

    /** 最近一次读到的播放位置：接手时用它判断「是不是同一次播放」。 */
    @Volatile var positionMs: Long = 0
        private set

    // ---------------------------------------------------------------- 下一集预热

    /** 已经预热好的下一集（`RemoteVideo` 实例，含地址与首块）。 */
    @Volatile var nextEpisodeId: String = ""
        private set
    @Volatile var nextRemote: RemoteVideo? = null
        private set
    @Volatile private var nextAt: Long = 0

    /**
     * 预热那一集的**实际档位**。
     *
     * 自动降档之后「预热好的 1080」不能再挂给 720 的播放：地址是另一条、索引也是另一份，
     * 挂上去等于把刚降下来的档位又顶回去，再撞一次同样的解码失败（TV 的预热键
     * `集:上限` 就是为了这件事，见 `TvMainActivity.preparedKeyFor`）。
     */
    @Volatile private var nextQuality: Int = 0

    /** 下一集是否已经挂进播放列表（挂进去之后由 ExoPlayer 自己无感推进）。 */
    @Volatile var nextQueued: Boolean = false

    /**
     * 最近一次取到的「相关剧集」。
     *
     * 与电视端的 `relatedWorks` / `relatedFor` 同一职责：详情页取好之后，播放页的
     * 「换一部接着看」直接铺这一份，**不再发第二次请求**（那一次要 1 秒多，
     * 片尾弹出来时用户正等着看下一部）。
     */
    @Volatile private var relatedKey: String = ""
    @Volatile private var relatedValue: com.hgtv.star.core.RelatedWorks? = null

    fun rememberRelated(seriesId: String, works: com.hgtv.star.core.RelatedWorks) {
        relatedKey = seriesId
        relatedValue = works
    }

    fun rememberRecommendSeries(series: Series,feed: String = "") {
        recommendSeries=series
        if(feed.isNotEmpty()) feedKey=feed
    }

    /** 这一部剧的相关剧集（没取过 / 取的是别的剧就返回 null）。 */
    fun relatedWorks(seriesId: String): com.hgtv.star.core.RelatedWorks? =
        if (relatedKey == seriesId) relatedValue else null

    /**
     * 推荐页那块 Surface 的「重新接上」入口。
     *
     * 为什么需要它：正式播放页在竖屏时**不挂自己的视频层**，画面由下层推荐页的
     * `SurfaceView` 继续出（见 `PhonePlayerActivity` 的 `feedUnderlay`）。从横屏退回
     * 竖屏时，播放器的输出还挂在横屏那个视图上，必须显式让它把 Surface 交回推荐页，
     * 否则退回来是一块黑。
     *
     * 只由**正在前台的那一条流**登记（[PhoneFeedView.onHidden] 登记、[PhoneFeedView.onShown]
     * 注销）：三条件裁各有一个信息流，谁都可以写就会把画面交到看不见的那一条上。
     */
    @Volatile var feedSurface: ((Boolean) -> Boolean)? = null

    /**
     * 推荐页那一层的「控制层显隐」入口（顶部体裁、右上角搜索、底部导航栏）。
     *
     * 播放页竖屏借下层 Surface 出画面时，下层的标题 / 进度条 / 弹幕必须收起：
     * 播放页自己会画一份，双层叠着就是两套标题、两条进度条。
     */
    @Volatile var feedChrome: ((Boolean) -> Unit)? = null

    /**
     * 播放器现在的视频输出是不是**仍然挂在推荐页那一块 Surface 上**。
     *
     * 播放页竖屏借下层出画面时它是 true（播放器连输出都没换过）；一旦播放页
     * 自己挂了视频层（横屏、或下层不可用），就变 false。
     *
     * 推荐页接手时要靠它决定「要不要强制重挂」：
     * * true → 那一块从来没被摘过，重挂反而会先清一次屏（闪黑）；
     * * false → 输出已被播放页拿走，必须重挂才能把画面收回来。
     */
    @Volatile var underlayActive: Boolean = false

    /**
     * 上下滑换集时，把**推荐页那一块视频**按同一个位移量平移。
     *
     * 播放页竖屏借下层出画面，视频不在自己的视图树里，翻页动画就动不了它。
     * 推荐页在交接时把这个入口登记下来：播放页拖动多少像素，这里就把那块
     * `PlayerView` 平移多少——两页一起跟手，与推荐页的翻页逐帧一致。
     */
    @Volatile var feedVideoOffset: ((Float) -> Unit)? = null

    fun setFeedVideoOffset(dy: Float) {
        feedVideoOffset?.invoke(dy)
    }

    /** 借下层出画面时，画面模式（FIT / ZOOM / FILL）要落到**那一块**上。 */
    @Volatile var feedVideoResize: ((Int) -> Unit)? = null

    fun setFeedVideoResize(mode: Int) {
        feedVideoResize?.invoke(mode)
    }

    /** 借下层出画面时，「画面缩放」也要落到**那一块**上。 */
    @Volatile var feedVideoScale: ((Float) -> Unit)? = null

    fun setFeedVideoScale(ratio: Float) {
        feedVideoScale?.invoke(ratio)
    }

    /** 正式播放换集时，下层推荐页那块 PlayerView 也要冻结旧画面比例。 */
    @Volatile var feedHoldFirstFrameAspectRatio: (() -> Unit)? = null
    @Volatile var feedCompleteFirstFrameAspectRatio: (() -> Unit)? = null

    fun holdFeedFirstFrameAspectRatio() {
        feedHoldFirstFrameAspectRatio?.invoke()
    }

    fun completeFeedFirstFrameAspectRatio() {
        feedCompleteFirstFrameAspectRatio?.invoke()
    }

    /** 登记 / 注销「推荐页 Surface 重新接上」的入口。 */
    fun bindFeedSurface(binder: ((Boolean) -> Boolean)?) {
        feedSurface = binder
    }

    /** 有几个播放页正压在同一块推荐页上（相关剧集会一个接一个地开）。 */
    private var feedChromeDepth = 0

    /**
     * 推荐页的控制层现在是不是**已经收起**。
     *
     * 为什么不能只看 [feedChromeDepth]：退回推荐页时控制层由**推荐页自己**在接完这一路
     * 之后恢复（那时集号 / 进度条已经对齐），播放页最后的 `onDestroy` 再还一次就会
     * 把它重新叫出来——用户在推荐页上点过「清屏」，回来又冒出一套控制层。
     */
    private var feedChromeHidden = false

    /**
     * 让推荐页收起自己的控制层。
     *
     * 用**计数**而不是布尔：播放页里再开一个播放页（相关剧集）时，旧的
     * `onDestroy` 会晚于新的 `onCreate` 跑到——直接置 true 就会在**新**播放页
     * 底下把推荐页的标题与进度条放出来。
     */
    fun hideFeedChrome() {
        feedChromeDepth++
        feedChromeHidden = true
        feedChrome?.invoke(false)
    }

    /** 重新借回下层 Surface 时重申收起状态，不重复增加 [feedChromeDepth]。 */
    fun reassertFeedChromeHidden() {
        feedChromeDepth = feedChromeDepth.coerceAtLeast(1)
        feedChromeHidden = true
        feedChrome?.invoke(false)
    }

    /** 还回推荐页的控制层；还有别的播放页压着时不放。 */
    fun restoreFeedChrome() {
        feedChromeDepth = (feedChromeDepth - 1).coerceAtLeast(0)
        if (feedChromeDepth == 0 && feedChromeHidden) {
            feedChromeHidden = false
            feedChrome?.invoke(true)
        }
    }

    /**
     * 推荐页接手完成、**数据也对齐之后**强制把控制层要回来。
     *
     * 为什么不在播放页 `onPause` 里还：那一刻 `A.onResume` 还没跑，推荐页手上还是
     * 上一次的集号与进度，先亮出来就是「标题 / 集号 / 进度条先跳一下再对上」。
     * 由接手方在自己 `onShown()` 的最后调这一句，计数一并归零（后面谁再还都是空操作）。
     */
    fun ensureFeedChrome() {
        feedChromeDepth = 0
        if (feedChromeHidden) {
            feedChromeHidden = false
            feedChrome?.invoke(true)
        }
    }

    /** 宿主界面重建 / 退出：计数归零（旧的播放页已经不属于这一份界面）。 */
    fun resetFeedChrome() {
        feedChromeDepth = 0
        feedChromeHidden = false
    }

    /**
     * 让播放器把视频输出交回推荐页的 Surface；没有可用的推荐页时返回 false。
     *
     * @param force true = 输出已被别的视图拿走（横屏那一层），必须重挂；
     *   false = 只是确认一次（进场时它本来就在出画面，重挂会白闪一下）。
     */
    fun attachFeedSurface(force: Boolean): Boolean = feedSurface?.invoke(force) ?: false

    /**
     * 竖屏时是否可以直接用下层推荐页的 Surface 出画面。
     *
     * 三条同时成立才成立：
     * * Android 9+（8.x 及以下播放页窗口必须不透明，下层根本看不见）；
     * * 这次是从推荐页进来的（[PhonePlayerActivity.EXTRA_RECOMMEND]）；
     * * 推荐页那一块 Surface 现在还活着（会话里这一路还在、且归推荐页所有）。
     *
     * 判据是 [live] 而不是 [adoptable]：借的是**那一块 Surface**，与地址新不新鲜无关。
     * 用 `adoptable` 的话，停久了再进来会被判成「下层不可用」→ 播放页自己挂一层视频
     * → 换输出端那一下就是用户看到的闪黑。
     */
    fun feedUnderlayUsable(seriesId: String): Boolean {
        if (owner != Owner.FEED && owner != Owner.PLAYER) return false
        if (!live(seriesId)) return false
        return feedSurface != null
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * 记下「现在在放这一集」。
     *
     * 只有 [remote] 真的换了才关旧的：同一集重复发布（例如返回信息流后又 bind 一次）
     * 不该把正在播的那一份关掉。
     */
    fun publish(
        detail: Detail,
        index: Int,
        remote: RemoteVideo,
        quality: String,
        by: Owner,
        /** 推荐流的体裁键；`null` = 不改变现有的归属（播放页换集走这一条）。 */
        feed: String? = null,
        /**
         * 是否关闭上一条 [RemoteVideo]。
         *
         * 推荐页播放器池会让上一页继续持有自己的播放器与媒体源；切到下一页时
         * 不能在这里把上一页仍在使用的 RemoteVideo 关掉，否则滑回去就无法继续播。
         * 池外回收时由播放器池负责关闭。
         */
        closePrevious: Boolean = true,
    ) {
        if (feed != null) this.feedKey = feed
        val previous = this.remote
        // 同一集重新发布（原地换地址、来回切页后又 bind 一次）时**不要**把位置清零：
        // 那一份读数正是接手方「补 prepare 之后要 seek 回哪儿」的依据。清零的话，
        // 换完地址立刻切页就会从 0 起播——用户读到的就是「退回来又从头开始」。
        val sameEpisode = this.seriesId == detail.series.id && this.index == index
        this.detail = detail
        this.index = index
        this.remote = remote
        this.quality = quality
        this.seriesId = detail.series.id
        this.owner = by
        if (!sameEpisode) this.positionMs = 0
        if (previous != null && previous !== remote && closePrevious) {
            // 延后关：旧 MediaSource 可能还在 release，立刻关会让它报「读取被中断」。
            main.postDelayed(
                { runCatching { previous.close() } },
                com.hgtv.star.core.AppConfig.Playback.PHONE_PREVIOUS_CLOSE_DELAY_MS,
            )
        }
    }

    /**
     * 把「现在这一路由谁在用」交给另一个界面。
     *
     * 为什么需要它：Activity 的返回顺序是 `B.onPause → A.onResume → B.onStop → B.onDestroy`，
     * 也就是**信息流会先一步接手**，播放页的 `onDestroy` 才跑到。播放页收尾时如果无条件
     * `pause()`，就会把信息流刚刚恢复的播放按停——用户读到的就是「退回去之后不动了」。
     * 因此收尾前先看 [owner]：已经被别人接手就只解绑 Surface，不再动播放状态。
     */
    fun takeOver(by: Owner) {
        owner = by
    }

    /**
     * 下一个接手方要不要自动开始播。
     *
     * 用户在播放页里主动按了暂停再退出去，回到信息流时不该自己动起来——
     * 那会变成「我明明暂停了，它又自己播了」。离开播放页时把当时的 `playWhenReady`
     * 记在这里，接手方按它决定。
     */
    @Volatile var resumeOnAdopt: Boolean = true

    /**
     * 交接窗口：从「A 把这一路交给 B」到「B 真的接上」之间，谁都不许暂停。
     *
     * 为什么需要它：两个界面是两个 Activity，Android 的返回顺序是
     * `B.onPause → A.onResume → B.onStop → B.onDestroy`。B 在 `onPause` 里无条件
     * `pause()` 的话，这一路会在 A 接手**之前**就停住，A 再 `playWhenReady = true`
     * 又要重新缓冲一次——用户读到的就是「退回推荐页时内容断了一下，然后从头缓」。
     *
     * 因此：谁发起交接谁 [beginHandoff]；交接窗口内两边的收尾逻辑都不暂停播放器，
     * 只解绑 Surface、摘监听。窗口过期后恢复正常（真的退到后台仍然要停）。
     */
    @Volatile private var handoffUntil: Long = 0

    private const val HANDOFF_WINDOW_MS = com.hgtv.star.core.AppConfig.Ui.HANDOFF_WINDOW_MS

    /** 声明「现在正在把这一路交给另一个界面」：窗口期内不允许暂停。 */
    fun beginHandoff() {
        handoffUntil = SystemClock.elapsedRealtime() + HANDOFF_WINDOW_MS
    }

    /** 是否处在交接窗口内（见 [beginHandoff]）。 */
    fun inHandoff(): Boolean = SystemClock.elapsedRealtime() < handoffUntil

    /** 交接完成：立刻结束窗口，之后照常按前后台决定暂停。 */
    fun endHandoff() {
        handoffUntil = 0
    }

    fun markPosition(positionMs: Long) {
        this.positionMs = positionMs.coerceAtLeast(0)
    }

    /**
     * 会话里这一路**还活着**：媒体源没被换掉、而且是同一部剧。
     *
     * 与 [adoptable] 的差别只在「地址新不新鲜」：这一条**不看时间**。
     * 为什么需要分开：切页只是把 Surface 换个宿主，媒体源与解码器一直在跑，
     * 这时候「地址老了」并不等于「不能接」——接上去照样出帧。真正的分岔是
     * 「要不要换一条地址」（见 [urlExpired] 与各端 `reopenAtCurrentPosition`）：
     * 早先两条判据合成一条，于是暂停久一点再退回来就被判成「接不上」，
     * 走的却是重取地址、重新 prepare、从片头重来——用户读到的就是「内容断了」。
     */
    fun live(seriesId: String): Boolean {
        if (remote == null) return false
        return this.seriesId == seriesId
    }

    /** 这一份状态能不能**直接接手**（活着、同一部剧）。 */
    fun adoptable(seriesId: String): Boolean = live(seriesId)

    /** 清掉「下一集」的预热结果（换剧、退出或真实播放失败时调）。 */
    fun clearNext(close: Boolean = true) {
        val previous = nextRemote
        nextRemote = null
        nextEpisodeId = ""
        nextAt = 0
        nextQuality = 0
        nextQueued = false
        if (close && previous != null) {
            main.postDelayed(
                { runCatching { previous.close() } },
                com.hgtv.star.core.AppConfig.Playback.PHONE_REPLACE_CLOSE_DELAY_MS,
            )
        }
    }

    /**
     * 记下预热好的下一集。真实播放错误时再显式清空并重取地址。
     */
    fun putNext(episodeId: String, remote: RemoteVideo) {
        clearNext()
        nextEpisodeId = episodeId
        nextRemote = remote
        nextQuality = remote.info.actualQuality.takeIf { it > 0 } ?: remote.info.requestedQuality
        nextAt = SystemClock.elapsedRealtime()
    }

    /**
     * 取走预热好的下一集（取走即清空槽位，避免同一份被挂两次）。
     *
     * [maxQuality] 是本次播放**实际生效**的上限：预热那一份比它还高时一律不接——
     * 自动降档之后接过来就是又一次同档失败（见 [nextQuality]）。
     */
    fun takeNext(episodeId: String, maxQuality: Int = 0): RemoteVideo? {
        if (nextEpisodeId != episodeId) return null
        if (nextAt <= 0) return null
        if (maxQuality > 0 && nextQuality > maxQuality) {
            clearNext()
            return null
        }
        val remote = nextRemote ?: return null
        nextRemote = null
        nextEpisodeId = ""
        nextAt = 0
        nextQuality = 0
        return remote
    }

    /** 完全清空（进程退出 / 换剧）。 */
    fun reset() {
        clearNext()
        handoffUntil = 0
        playerForeground = false
        owner = Owner.NONE
        seriesId = ""
        feedKey = ""
        recommendSeries = null
        detail = null
        index = -1
        remote = null
        quality = ""
        positionMs = 0
        feedSurface = null
        feedVideoOffset = null
        feedVideoResize = null
        feedVideoScale = null
        underlayActive = false
    }
}
