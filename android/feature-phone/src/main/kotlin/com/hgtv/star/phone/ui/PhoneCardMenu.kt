// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.app.Activity
import android.view.View
import com.hgtv.star.core.CardAction
import com.hgtv.star.core.CardMenu
import com.hgtv.star.core.Series
import com.hgtv.star.phone.PhoneAccountSync
import com.hgtv.star.phone.phoneNav
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.phone.PhoneServices

/**
 * 剧卡菜单（长按海报）：手机上等价于电视端的「确认键 / 菜单键」。
 *
 * ## 为什么要复用 `:core` 的 [CardMenu]
 *
 * 「有哪些项、什么顺序、文案怎么随状态变」是一套纯规则：有进度时第一项是「继续观看」、
 * 已经收藏过就写「取消收藏」、历史上下文多一条「删除记录」、热播榜才多「不在热播榜中显示」。
 * 这套规则 TV 端已经写好并被单元测试锁住，手机端**只负责画出来和派发动作**，
 * 不另写一份——两边一旦分叉，同一个账号在两台设备上看到的菜单就会不一样。
 *
 * ## 行为
 *
 * 每一行点下去立刻收起浮层再执行：留在原地等动作跑完，用户会以为没点上。
 * 改动了本机数据的动作（收藏 / 稍后看 / 删除记录 / 隐藏）都会回一句轻提示——
 * 手机没有焦点高亮，不给反馈就分不清「点了」和「点空了」。
 */
object PhoneCardMenu {

    /**
     * 弹出菜单。
     *
     * @param anchor 任意一个能拿到 Activity 的 View（海报卡自己）
     * @param inHistory 当前上下文是不是观看历史（决定有没有「删除记录」）
     * @param hotList 当前上下文是不是热播榜（决定有没有「不在热播榜中显示」）
     * @param onChanged 本机数据被改动之后回调：调用方重铺自己的列表
     */
    fun show(
        anchor: View,
        series: Series,
        inHistory: Boolean = false,
        hotList: Boolean = false,
        onChanged: () -> Unit = {},
    ) {
        val activity = anchor.context as? Activity ?: return
        val lib = runCatching { PhoneServices.library(anchor.context) }.getOrNull() ?: return
        // 书库还没读完时读它会抛（`Load the local library before accessing it`）。
        // 这时候什么都不做比崩掉好；页面自己的 `awaitLibrary` 几百毫秒内就会回来。
        if (!lib.isLoaded) return

        val favorite = runCatching { lib.favorite(series.id) }.getOrDefault(false)
        val items = CardMenu.items(
            hasProgress = runCatching { lib.progress(series.id) != null }.getOrDefault(false),
            favorite = favorite,
            queued = runCatching { lib.queued(series.id) }.getOrDefault(false),
            inHistory = inHistory,
            hotList = hotList,
        )

        // 与电视端一样收进统一弹窗：选项是一列胶囊，动作派发规则仍由 `:core` 的 CardMenu 决定。
        PhoneDialog(activity, series.title)
            .choices(items.map { it.label }) { index ->
                dispatch(activity, series, items[index].action, favorite, onChanged)
            }
            .button("取消")
            .show()
    }

    /**
     * 派发动作。
     *
     * 「继续观看 / 直接播放」走**正式播放页**（它与推荐页共用同一个进程级播放器，
     * 本机有进度就从那一秒接着播），而不是详情页——菜单第一项写的是「播放」，
     * 点下去却进详情，是这一条最常见的误配。
     */
    private fun dispatch(
        activity: Activity,
        series: Series,
        action: CardAction,
        favorite: Boolean,
        onChanged: () -> Unit,
    ) {
        val lib = runCatching { PhoneServices.library(activity) }.getOrNull() ?: return
        when (action) {
            CardAction.RESUME -> {
                activity.phoneNav().open(Route.Player(series.id, series.title))
                return
            }

            CardAction.DETAIL -> {
                activity.phoneNav().open(Route.Detail(series.id, series.title))
                return
            }

            CardAction.FAVORITE -> {
                // 以 toggle 的真实返回值为准：菜单打开期间账号同步可能已经改过状态。
                val adding = runCatching { lib.toggle(series) }.getOrElse { !favorite }
                // 本机先改、账号侧随后推（未登录时那一半是空操作，这里不打扰用户）。
                runCatching { PhoneAccountSync.favorite(activity, series, adding) }
                toast(activity, if (adding) "已收藏" else "已取消收藏")
            }

            CardAction.LATER -> {
                val adding = runCatching { !lib.queued(series.id) }.getOrDefault(true)
                runCatching { lib.toggleLater(series) }
                toast(activity, if (adding) "已加入稍后观看" else "已移出稍后观看")
            }

            CardAction.DELETE_HISTORY -> {
                runCatching { lib.removeHistory(series.id) }
                // 记进墓碑：否则下一次账号同步会把平台侧那条又合并回来。
                runCatching { lib.markHistoryDeleted(listOf(series.id)) }
                runCatching { PhoneAccountSync.historyDeleted(activity, series) }
                toast(activity, "已删除这条记录")
            }

            CardAction.HIDE_FROM_HOT -> {
                runCatching { lib.hide(series.id) }
                toast(activity, "已不在热播榜中显示")
            }
        }
        onChanged()
    }

    private fun toast(activity: Activity, text: String) {
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}
