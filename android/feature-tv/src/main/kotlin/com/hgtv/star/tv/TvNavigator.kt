// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.Series
import com.hgtv.star.shared.nav.Navigator
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.tv.list.showCatalog

/**
 * 电视端的路由落地：把 [Route] 翻译成「切 `screen` + 重建视图树」。
 *
 * ## 为什么这里的实现比手机长
 *
 * 手机一个页面就是一个 `Activity`，跳页 = 发一个 `Intent`，没有别的动作。
 * 电视只有一个 `Activity`，四个页面共用同一棵视图树，所以「去某一页」永远包含两件事：
 * **把上一页收干净** + **把这一页铺出来**。收尾那部分（写进度、摘播放画面、作废在途回调、
 * 关面板）已经在返回键那条路上写好了，这里只调用它，不另写一套。
 *
 * ## 只认本机历史里有的剧
 *
 * 详情页与播放页需要完整的 [Series]：那个对象带着列表接口才有的 `notice`
 *（「预计11月6日上线」），只传 id 会把它丢掉，详情页的预告文案会退化。
 * 因此这里按 id 去本机历史里反查；查不到就返回 `false`，让调用方退回首页——
 * 打开一个没有标题的空壳详情页比退回首页更糟。
 *
 * 将来要支持「从外部深链直接进详情」，需要先给 `ContentRepository` 加一个
 * 「按 id 取摘要」的接口，再把这里补上，而不是在这里伪造一个只有 id 的 `Series`。
 */
class TvNavigator(private val host: MainActivity) : Navigator {

    override fun open(route: Route): Boolean {
        when (route) {
            // 推荐流是电视端唯一「自己会取数据」的整屏页面，入口已经完整。
            Route.Recommend -> {
                host.openRecommend()
                return true
            }

            is Route.Detail -> {
                val series = knownSeries(route.seriesId) ?: return false
                // autoplay=false：进详情页不自动起播，与卡片点进来的行为一致。
                host.openDetail(series, autoplay = false)
                return true
            }

            is Route.Player -> {
                val series = knownSeries(route.seriesId) ?: return false
                // resume=true：集数与位置都由本机进度决定（`openDetail` 里的 `resumeTargetFor`）。
                // 电视端没有「按集号直接起播」的入口，`route.episode` 在这里不生效——
                // 这是两端真实差异，见 [Route.Player] 的说明。
                host.openDetail(series, resume = true, autoplay = false)
                return true
            }

            // 下面四个在电视上不是独立页面，而是 `catalog` 里的标签。
            Route.Home -> toTab(TAB_HOME)
            Route.Search -> toTab(TAB_SEARCH)
            Route.Settings -> toTab(TAB_SETTINGS)
            Route.Account -> toTab(TAB_PROFILE)
        }
        return true
    }

    /**
     * 先离开当前页，再切到 [tab]。
     *
     * 每一步都用返回键那条路上**已经存在**的函数，因此收尾动作（保存进度、摘掉播放画面、
     * 作废在途回调、关浮层）与用户自己按返回完全一致，没有第二套逻辑要维护。
     *
     * 三个分支都是同步重建视图树：中间那一棵不会被画出来，所以不会看到闪一下。
     *
     * 已知的一处浪费：从推荐页切到搜索 / 设置 / 账号时，`exitRecommendToHome()` 会顺带
     * 取一次首页数据，随后 `switchTab` 再取目标标签的数据。这两个入口目前**没有调用方**
     *（电视端没有从推荐页直接跳标签的手势），真要用时给 `goHomeFromRecommend` 加一个
     * 「只收尾不取数据」的开关即可。
     */
    private fun toTab(tab: Int) {
        when (host.screen) {
            // 播放页：从推荐进来的直接回主页，其余回详情页——与返回键同一套判断。
            "player" -> if (host.playbackFromRecommend) host.leaveRecommendPlaybackToHome() else host.returnToDetail()
            "recommend" -> host.exitRecommendToHome()
            // 详情页：`switchTab` 内部会经 `showCatalog` 重建，等价于在详情页按一次返回。
            "detail" -> host.showCatalog()
        }
        host.switchTab(tab)
    }

    /**
     * 按 id 找这一部剧。
     *
     * 顺序：当前详情 > 待加载的详情 > 本机历史。
     * 进程恢复时前两个都是空的，实际走的是历史——与改造前 `restoreLastScreen` 完全一致。
     */
    private fun knownSeries(id: String): Series? {
        if (id.isEmpty()) return null
        host.detail?.series?.takeIf { it.id == id }?.let { return it }
        host.pendingDetail?.takeIf { it.id == id }?.let { return it }
        if (!host.libraryReady) return null
        return host.library.history().firstOrNull { it.series.id == id }?.series
    }
}
