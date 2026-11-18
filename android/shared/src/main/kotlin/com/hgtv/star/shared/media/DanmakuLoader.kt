// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media

import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.Danmaku
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.danmaku
import com.hgtv.star.shared.architecture.danmaku
import kotlinx.coroutines.CoroutineScope

/**
 * 「这一集拉到哪一步」的弹幕加载内核（电视端与手机端共用一份）。
 *
 * ## 为什么抽出来
 *
 * 这段逻辑原先写了三遍（电视播放页、手机播放页、手机信息流），三份的差异全是**意外**：
 * 有的忘了把位置指针重置回开头、有的没有「同一集不重拉」、有的把空结果当成没拉到。
 * 而它们要表达的是同一件事：
 *
 * ```
 * 换集 → 作废旧请求（代次 +1）→ 手动换集立即清屏 / 片尾连播保留旧画面 → 连续拉页
 * ```
 *
 * ## 三条容易写错的规则（都在这里）
 *
 * * **同一集不重拉**：换清晰度、播放重试会重复进同一条路，重拉一次等于把已经画在屏上的
 *   弹幕清空再重来，用户看到的是「画面一卡、弹幕闪没了」。空结果也算「拿到」——
 *   这一集确实没有弹幕，不该每换一次清晰度就问一遍。
 * * **手动换集立即清空**：上下滑动、点击选集不能残留上一集弹幕；片尾自动连播仍保留过渡。
 * * **旧回包一律丢弃**：用代次（不是「清空列表」）判断，晚回来的那一份不能盖住新一集。
 * * **隐藏弹幕时不发请求**：那是纯浪费流量；打开弹幕时由调用方按 [loaded] 决定要不要补拉。
 */
class DanmakuLoader(
    private val scope: CoroutineScope,
    private val repository: () -> ContentRepository,
    /** 当前的显示方式（来自设置）：隐藏时不发请求。 */
    private val mode: () -> DanmakuMode,
    /** 往哪一层的 `DanmakuView` 上画。页面重建后这个值会变，因此每次现取。 */
    private val target: () -> DanmakuView?,
    /** 回主线程：`setDanmaku` / `appendDanmaku` 要碰 View，网络回包可能在任意线程。 */
    private val post: (() -> Unit) -> Unit,
    /** 页面还活着吗（销毁 / 已切走时不要再碰界面）。 */
    private val alive: () -> Boolean = { true },
    /** 全量到齐：参数是这一集与条数（电视端用它更新状态行 / 提示「无弹幕」）。 */
    private val onLoaded: (episodeId: String, count: Int) -> Unit = { _, _ -> },
    /** 拉取失败：弹幕取不到不影响播放，由调用方决定要不要留现场。 */
    private val onFailed: (episodeId: String, error: Throwable) -> Unit = { _, _ -> },
) {

    /** 已经拿到（或正在拿）的是哪一集。 */
    var episode: String = ""
        private set

    /** 这一集拿到几条；-1 = 还没拿到。 */
    var count: Int = -1
        private set

    /** 这一集的弹幕是否已经在手上（含「确实一条都没有」）。 */
    val loaded: Boolean get() = count >= 0

    /** 请求代次：换集就 +1，旧回包凭它作废。 */
    private var token = 0L
    /** 是否已经铺过首批弹幕；后续窗口只追加，不重排。 */
    @Volatile private var lastShownCount = 0

    /**
     * 最近一次铺到屏幕上的那一份弹幕（含边拉边画的中间态）。
     *
     * 为什么要留一份：手机端竖屏 / 横屏是两个**不同的** `DanmakuView`（各在自己的容器里），
     * 数据是塞在视图内部的。用户竖屏看着有弹幕、一点全屏就"没了"，就是新那一层还是空的。
     * 切形态时用 [replay] 把这份数据重放一次，两边就接得上。
     */
    private var lastItems: List<Danmaku> = emptyList()

    /**
     * 上一次把弹幕铺到**哪一层**。
     *
     * 目标层是会换的（竖屏 ↔ 横屏、页面重建、播放页从信息流接手）。换了之后若只
     * `resetCursor()` 而不重铺，新那一层永远是空的——「转个屏弹幕就没了」正是这一条。
     */
    private var lastTarget: DanmakuView? = null

    /**
     * 拉这一集。
     *
     * @param force true = 无视「同一集不重拉」直接重来（用户刚把弹幕从隐藏打开时用）。
     * @param clearOnSwitch true = 用户主动滑动换集 / 手动选集，立即清掉上一集弹幕；
     *   false = 片尾自动连播，等新一集首批弹幕到达后再接管。
     */
    fun load(
        episodeId: String,
        seriesId: String,
        force: Boolean = false,
        clearOnSwitch: Boolean = false,
    ) {
        if (episodeId.isBlank() || seriesId.isBlank()) return
        val view = target() ?: return
        val current = mode()
        view.mode = current
        if (!force && episode == episodeId && loaded) {
            // 同一集重复进来（换清晰度 / 重试）：位置指针回到开头重新对齐。
            view.resetCursor()
            // 目标层换过（竖屏 ↔ 横屏、页面重建、播放页接手）：把手上这一份铺到新那一层。
            // 只对齐游标不铺数据的话，新那一层永远是空的——用户读到的就是
            // 「转个屏 / 进播放页，弹幕就没了」。
            if (view !== lastTarget) {
                view.setDanmaku(lastItems)
                lastTarget=view
            }
            return
        }
        val switchingEpisode = episode != episodeId
        // 首次进入、手动换集、主动清屏：都要先清掉上一集的数据，再把首批弹幕挂起，
        // 等视频真正出帧开播（running=true 的 setClock）才铺开。
        //
        // 原来只有换集走 beginTransition，首次进入直接 setDanmaku：那会儿视频还在 buffering，
        // 时钟停在 0，弹幕先按 0 时刻显示；等 READY 后时钟跳到真实位置又重排一次，
        // 用户读到的就是「弹幕先跑出来、视频开始播又清空重跑」。Phone / TV 同源。
        val holdUntilPlayback = !switchingEpisode || clearOnSwitch
        if (holdUntilPlayback) {
            view.clear()
            if (clearOnSwitch) forgetClock()
        } else {
            // 片尾自动连播：保留旧画面滚动到首批新弹幕到达，不清屏。
            view.resetCursor()
        }
        if (holdUntilPlayback || switchingEpisode) {
            view.beginTransition()
            // 过渡期间首批弹幕先挂起，要等**视频开播的时钟**落到视图上才铺开（见 `DanmakuView.setClock`）。
            // syncClock 对「位置 / 状态 / 倍速都没变」的调用去重：新一集从 0 开始、状态也
            // 「正在播」时与上次采样一样，setClock 一次都不会落地——挂起的那份永远等不到。
            // 这里清掉去重基准，保证下一次 syncClock 一定打到视图上。
            forgetClock()
        }
        val ticket = ++token
        episode = episodeId
        count = -1
        lastItems = emptyList()
        lastShownCount = 0
        if (current == DanmakuMode.HIDDEN) return
        scope.danmaku {
            val items = runCatching {
                // 一个线程连续拉取；首屏排一次，后续页面只追加，不清空当前轨道。
                repository().danmaku(
                    episodeId, seriesId, 0, { token != ticket },
                    onWindow = onWindow@{ partial ->
                        post {
                            if (!alive() || token != ticket) return@post
                            lastItems = partial
                            target()?.let { view ->
                                if(lastShownCount==0 || view!==lastTarget) {
                                    view.setDanmaku(partial)
                                } else {
                                    view.appendDanmaku(partial)
                                }
                                lastTarget = view
                                lastShownCount = partial.size
                            }
                        }
                    }
                )
            }.onFailure { problem -> onFailed(episodeId, problem) }
                .getOrDefault(emptyList())
            post {
                if (!alive() || token != ticket) return@post
                count = items.size
                lastItems = items
                target()?.let { view ->
                    if(lastShownCount==0 || view!==lastTarget) {
                        view.setDanmaku(items)
                    } else {
                        view.appendDanmaku(items)
                    }
                    lastTarget = view
                    lastShownCount = items.size
                }
                onLoaded(episodeId, items.size)
            }
        }
    }

    /**
     * 立即清空当前这一层并忘记已加载状态。
     *
     * 用户主动换剧 / 换集时调用；下一集的首批弹幕到达前不会继续显示上一集内容。
     */
    fun clear() {
        token++
        episode = ""
        count = -1
        lastItems = emptyList()
        lastShownCount = 0
        lastTarget = null
        target()?.clear()
        forgetClock()
    }

    /**
     * 目标层换了就把手上这一份重新铺上去（不重新请求、不动时钟）。
     *
     * 给「页面重建 / 切竖横屏之后宿主只调了设置、没有重放」的那条路兜底：
     * 已经拉到手的弹幕不该因为换了一层 View 就凭空消失。
     */
    fun reattach() {
        val view = target() ?: return
        if (view === lastTarget || !loaded) return
        view.mode = mode()
        view.resetCursor()
        view.setDanmaku(lastItems)
        lastTarget = view
    }

    /**
     * 把手上这一集重放到当前的 [target] 上（切换竖屏 / 横屏时用）。
     *
     * 只重放，不重新请求：位置指针回到开头、显示方式按当前设置、时钟用调用方给的现值，
     * 新那一层立刻就是用户刚才看到的那一份。
     */
    fun replay(positionMs: Long, running: Boolean, speed: Float) {
        val view = target() ?: return
        view.mode = mode()
        view.resetCursor()
        view.setDanmaku(lastItems)
        view.setClock(positionMs, running, speed)
        lastTarget = view
        clockAt = positionMs
        runningState = running
        clockSpeedState = speed
    }

    /** 换剧 / 离开页面：作废在途请求、清掉「已拿到」的记忆（下次进来重新拉）。 */
    fun reset() {
        token++
        episode = ""
        count = -1
        lastShownCount = 0
        forgetClock()
    }

    /**
     * 视频真正出了首帧：放行挂起的首批弹幕，并按首帧位置对齐时钟。
     *
     * 必须由播放端在 onRenderedFirstFrame 调用。不能用 STATE_READY 代替——
     * 真机上 READY 之后还可能回 BUFFERING 好几秒，提前放行会让弹幕先跑出来、出帧后再重排。
     */
    fun onFrameRendered(positionMs: Long) {
        target()?.markFrameRendered(positionMs)
    }

    /** 把播放位置 / 播放状态 / 倍速推给弹幕层（每帧只读，不回调播放器）。 */
    fun syncClock(positionMs: Long, running: Boolean, speed: Float) {
        if (positionMs == clockAt && running == runningState && speed == clockSpeedState) return
        val view = target()
        if (view != null) {
            if (runningState && !running) {
                // 暂停：冻结当前屏幕位置，不把已经外推出的那几十毫秒倒回去。
                view.pauseClock()
                clockAt = positionMs
                runningState = false
                clockSpeedState = speed
                return
            }
            if (!runningState && running && clockAt >= 0L) {
                val displayed = view.clockPositionMillis()
                val drift = displayed - positionMs
                if (drift in 0L..200L) {
                    // 恢复播放：从暂停时看到的位置继续，避免恢复瞬间倒跳。
                    view.setClock(displayed, true, speed)
                    clockAt = displayed
                    runningState = true
                    clockSpeedState = speed
                    return
                }
            }
            if (running && runningState && speed != clockSpeedState) {
                // 倍速变化只换斜率，不改变当前屏幕位置。
                val displayed = view.clockPositionMillis()
                view.setClock(displayed, true, speed)
                clockAt = displayed
                clockSpeedState = speed
                return
            }
            if (running && runningState) {
                val displayed = view.clockPositionMillis()
                val behind = displayed - positionMs
                if (behind in 1L..CLOCK_BACKWARD_TOLERANCE_MS) {
                    // 播放器采样偶尔落后于屏幕外推时钟。小幅差异不重置，避免弹幕周期性回退；
                    // 只有明显向后跳转（用户 seek）才接受新的播放位置。
                    return
                }
            }
        }
        clockAt = positionMs
        runningState = running
        clockSpeedState = speed
        view?.setClock(positionMs, running, speed)
    }

    /** 位置、播放状态、倍速任一变化都要立刻重校，否则暂停或切倍速会短暂脱节。 */
    @Volatile private var clockAt = -1L
    private var runningState = false
    private var clockSpeedState = 1f

    private fun forgetClock() {
        clockAt = -1L
        runningState = false
        clockSpeedState = 1f
    }

    private companion object {
        const val CLOCK_BACKWARD_TOLERANCE_MS=500L
    }
}
