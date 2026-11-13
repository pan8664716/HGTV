// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.Detail
import com.hgtv.star.core.RelatedWorks
import com.hgtv.star.core.Series
import com.hgtv.star.core.related
import com.hgtv.star.phone.ui.PhoneCards
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.navigationBarPx
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.shared.nav.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PhonePlayerRelatedState {
    var relatedToken = 0
    var authorToken = 0
    var authorWorksFor = ""
    var authorWorks: List<Series>? = null
    var relatedSheetOpen = false
}

/**
 * 相关剧集浮层：播放设置里的「相关剧集」使用这一份。
 *
 * 摆法与电视端播放设置里的相关剧集（`buildRelatedRow`）一一对应：
 *
 * ```
 * 《剧名》已经看完 · 换一部接着看
 * ┌──────┐ ┌──────┐ ┌──────┐ …      ← 海报卡片行，横向滚动看全
 * │ 第2季 │ │      │ │      │
 * └──────┘ └──────┘ └──────┘
 *   剧名     剧名     剧名
 *   正在看   预计上线
 * ```
 *
 * 三处与电视端对齐，也是「孤零零几个文字」那版的问题所在：
 * * **卡片**而不是剧名文字：海报 + 季号角标 + 状态行，和详情页那一栏长得一样；
 * * **不截断**：早先 `take(12)` 之后就只能看到前十二部，现在是全部横向滚；
 * * **不压暗画面**：电视端的 `RelatedPanel` 把 `dimAmount` 归零，片尾那一帧仍然亮着，
 *   用户看得见「刚刚放完的是什么」。
 *
 * 数据与详情页共用 [PhoneSession.relatedWorks]（电视端同样共用 `relatedWorks`）：
 * 从详情页点进来的这一路已经取好了，打开浮层时是零等待。
 */
internal fun PhonePlayerActivity.showRelated(d: Detail, title: String, heading: String) {
    val series = d.series
    val token = ++relatedState.relatedToken
    // 作废上一轮（设置面板）排下的自动收起：这一层不自动收，但那个定时器还在路上，
    // 不换 epoch 它到点就会把刚打开的卡片条收走。
    relatedChromeEpoch++
    if (rootReady) root.removeCallbacks(relatedChromeHideRunnable)
    val cached = PhoneSession.relatedWorks(series.id)
    // 横竖屏都用**贴底整宽**的条：电视端的 RelatedPanel 就是这样，且横屏时
    // 右侧 1/3 抽屉只放得下两张卡，浏览「换一部」反而更费劲。
    playerSheet.bottomInset = if (playerLandscapeMode) navigationBarPx() else 0
    playerSheet.show(
        title,
        landscape = false,
        dim = false,
        heightFraction = if (playerLandscapeMode) .95f else .78f,
    ) { panel ->
        // 状态行：加载中 / 共 N 部（同系列多季时点明季数，与 TV 的标题说法一致）。
        val state = TextView(this).apply {
            text = if (cached != null) relatedHeadline(cached, heading) else "正在找相关剧集…"
            setTextColor(PhoneStyle.textDim)
            phoneSize(12)
            setPadding(dp(2), 0, dp(2), dp(10))
            tag = "related-state"
        }
        panel.addView(state, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(host, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        if (cached != null) {
            fillRelated(host, cached, series, state)
            return@show
        }
        // 没取过（从历史 / 搜索直接开播）：这一栏自己取一次，取到就地铺。
        // 走的是与详情页同一个接口、同一份进程缓存，因此第二次进来必然是零等待。
        val activity=this
        scope.launch {
            val works = withContext(Dispatchers.IO) {
                runCatching { PhoneServices.repository(activity).related(series.id, series.title) }
                    .getOrNull()
            }
            if (token != relatedState.relatedToken || isFinishing || isDestroyed) return@launch
            if (works == null) {
                state.text = "相关剧集没能取到，稍后再试。"
                return@launch
            }
            PhoneSession.rememberRelated(series.id, works)
            state.text = relatedHeadline(works, heading)
            fillRelated(host, works, series, state)
        }
    }
    relatedState.relatedSheetOpen = true
    // 这里**不**排自动收起：它是一层「挑下一部」的浏览浮层，用户正在横向翻卡片，
    // 到点收走等于把正在看的那一排抽掉（电视端的 `RelatedPanel` 也只在用户操作时收起）。
    // 上一轮排下的截止时刻已经作废——`relatedChromeEpoch` 每次重排都会换。
}

/** 状态行文案：与电视端 `buildRelatedRow` 的标题同一套说法。 */
internal fun PhonePlayerActivity.relatedHeadline(works: RelatedWorks, heading: String): String {
    val seasons = works.seasons.size
    return if (seasons > 1) "$heading · 同系列 $seasons 季 · 共 ${works.items.size} 部" else "$heading · 共 ${works.items.size} 部"
}

/**
 * 把相关剧集铺成一排卡片。
 *
 * 卡片是 [PhoneCards.row]，**详情页「相关剧集」那一栏用的是同一份**——
 * 两处因此不可能再分叉（电视端也是 `buildRelatedRow` 一处实现）。
 */
internal fun PhonePlayerActivity.fillRelated(
    host: LinearLayout,
    works: RelatedWorks,
    current: Series,
    state: TextView,
) {
    if (works.items.isEmpty()) {
        state.text = "暂时没有找到相关剧集，可以先返回看看别的。"
        return
    }
    host.removeAllViews()
    host.addView(
        PhoneCards.row(
            this,
            PhoneServices.artwork(this),
            works.items,
            current.id,
            widthDp = if (playerLandscapeMode) PhoneCards.COMPACT_WIDTH_DP else PhoneCards.WIDTH_DP,
        ) { item ->
            // 点卡片进详情页（与电视端的「换一部接着看」同一落点）。
            openSeries(item)
        },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
}

/**
 * 播放页作者作品：与详情页使用同一个 [ContentRepository.authorWorks] 数据源，
 * 以底部横排卡片展示；同一部剧重复打开时复用本次播放页的缓存。
 */
internal fun PhonePlayerActivity.showAuthorWorks(d: Detail) {
    val series = d.series
    val name = series.author.trim()
    if (name.isEmpty()) {
        toast("这一部没有作者信息")
        return
    }
    relatedChromeEpoch++
    if (rootReady) root.removeCallbacks(relatedChromeHideRunnable)
    val cached = relatedState.authorWorks.takeIf { relatedState.authorWorksFor == series.id }
    playerSheet.bottomInset = if (playerLandscapeMode) navigationBarPx() else 0
    playerSheet.show(
        "作者作品",
        landscape = false,
        dim = false,
        heightFraction = if (playerLandscapeMode) .95f else .78f,
    ) { panel ->
        val state = TextView(this).apply {
            text = if (cached != null) "正在读取作者作品…" else "正在查找 $name 的作品…"
            setTextColor(PhoneStyle.textDim)
            phoneSize(12)
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        panel.addView(state, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(host, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        fun fill(works: List<Series>) {
            val items = (listOf(series) + works.filterNot { it.id == series.id })
                .distinctBy { it.id }
            state.text = "$name · 共 ${items.size} 部作品"
            host.removeAllViews()
            if (items.size <= 1) {
                state.text = "$name · 暂时只有这一部作品"
                return
            }
            host.addView(
                PhoneCards.row(
                    this,
                    PhoneServices.artwork(this),
                    items,
                    series.id,
                    widthDp = if (playerLandscapeMode) PhoneCards.COMPACT_WIDTH_DP else PhoneCards.WIDTH_DP,
                ) { item ->
                    openSeries(item)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ),
            )
        }
        if (cached != null) {
            fill(cached)
            return@show
        }
        val token = ++relatedState.authorToken
        val activity=this
        scope.launch {
            val works = withContext(Dispatchers.IO) {
                runCatching {
                    PhoneServices.repository(activity)
                        .authorWorks(name, series)
                }.getOrNull()
            }
            if (token != relatedState.authorToken || isFinishing || isDestroyed) return@launch
            relatedState.authorWorksFor = series.id
            relatedState.authorWorks = works
            if (works == null) {
                state.text = "作者作品没能取到，稍后再试。"
                return@launch
            }
            fill(works)
        }
    }
    relatedState.relatedSheetOpen = true
}

/** 播放设置动作区里的收藏切换，状态同时写入本机与账号缓存。 */
internal fun PhonePlayerActivity.toggleFavorite(series: Series): Boolean {
    val lib = library ?: return false
    val accountFavorite = PhoneAccountSync.data?.favorites?.any { it.series.id == series.id } == true
    val current = runCatching { lib.favorite(series.id) }.getOrDefault(false) || accountFavorite
    if (current) {
        if (runCatching { lib.favorite(series.id) }.getOrDefault(false)) lib.toggle(series)
        PhoneAccountSync.favorite(this, series, false) { toast("登录后可同步收藏") }
        reportRecommendEvent(com.hgtv.star.core.RecommendBehavior.Event.UNFAVORITE)
        toast("已取消收藏")
        return false
    }
    if (!runCatching { lib.favorite(series.id) }.getOrDefault(false)) lib.toggle(series)
    PhoneAccountSync.favorite(this, series, true) { toast("登录后可同步收藏") }
    reportRecommendEvent(com.hgtv.star.core.RecommendBehavior.Event.FAVORITE)
    toast("已加入收藏")
    return true
}

/**
 * 点一张相关卡片：**进详情页**，与电视端 `showFinishedStrip` 里 `openDetail(target)` 同一语义。
 *
 * 为什么不直接起播：电视端那一栏点的就是详情页——用户要的是「这部是什么、有几集、
 * 从第几集接着看」，直接起播等于替他做了这个决定。
 *
 * 为什么**不** `finish()` 本页：电视端只有一个 Activity，从播放页进详情、按返回会回到
 * 播放界面同一位置。手机上如果先收掉播放页，返回就落到推荐流上，用户丢掉「刚才看到哪」。
 * 因此本页留在栈里（`onPause` 会存进度并暂停），详情页盖在上面，返回即可接着看。
 */
internal fun PhonePlayerActivity.openSeries(item: Series) {
    playerSheet.dismiss()
    dismissLandscapeSettingsForRelated()
    phoneNav().open(Route.Detail(item.id, item.title))
}
