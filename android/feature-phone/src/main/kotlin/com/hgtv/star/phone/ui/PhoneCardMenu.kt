// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.app.Activity
import android.view.View
import com.hgtv.star.core.CardAction
import com.hgtv.star.core.CardMenu
import com.hgtv.star.core.RecommendBehavior
import com.hgtv.star.core.Series
import com.hgtv.star.phone.PhoneAccountSync
import com.hgtv.star.phone.phoneNav
import com.hgtv.star.phone.reportRecommendBehavior
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

    /** 沿 `ContextWrapper` 链往上找 Activity：卡片不一定直接挂在 Activity 上。 */
    private fun findActivity(context: android.content.Context?): Activity? {
        var current = context
        while (current is android.content.ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    /**
     * 弹出菜单。
     *
     * @param anchor 任意一个能拿到 Activity 的 View（海报卡自己）
     * @param inHistory 当前上下文是不是观看历史（决定有没有「删除记录」）
     * @param hotList 当前上下文是不是热播榜（决定有没有「不在热播榜中显示」）
     * @param onChanged 本机数据被改动之后回调：调用方重铺自己的列表
     * @param onItemRemoved 「这一项要从当前列表里消失」的动作（不在热播榜中显示）走这里，
     *   参数是被移除的剧 id。给了它就不再调 [onChanged]——整页重取会闪一下并顶回顶部。
     */
    fun show(
        anchor: View,
        series: Series,
        inHistory: Boolean = false,
        hotList: Boolean = false,
        onChanged: () -> Unit = {},
        /**
         * 「只改状态、不改列表成员」的动作（收藏 / 稍后看）走这里。
         *
         * 默认等于 [onChanged]（兼容旧调用方）。列表页把它设成「只重绑可见项」，
         * 点一下收藏就不会把整页重铺一遍。
         */
        onItemChanged: (() -> Unit)? = null,
        onItemRemoved: ((String) -> Unit)? = null,
    ) {
        // 不要只做 `context as? Activity`：卡片有时挂在 `ContextThemeWrapper` 之类的
        // 包装 context 下，那样会静默返回，用户读到的就是「长按没反应」。
        val activity = findActivity(anchor.context)
        if (activity == null) {
            android.util.Log.w("HGTV-CARD", "menu skipped: no Activity in context chain")
            return
        }
        val lib = runCatching { PhoneServices.library(anchor.context) }.getOrNull()
        if (lib == null) {
            android.util.Log.w("HGTV-CARD", "menu skipped: library unavailable")
            return
        }
        // 书库还没读完时读它会抛（`Load the local library before accessing it`）。
        //
        // 早先这里直接 `return`：冷启动后几秒内长按卡片**一点反应都没有**，用户会以为
        // 「长按菜单坏了」。现在改成等它读完再弹一次——书库几百毫秒内就绪，
        // 用户感觉只是慢了半拍，而不是没响应。
        if (!lib.isLoaded) {
            android.util.Log.i("HGTV-CARD", "menu deferred: library not loaded yet")
            lib.loadAsync(onReady = {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    // 全部参数都要带上：早先这里只传了前几个，重试弹出的菜单
                    // 会丢掉 `onItemChanged`，点收藏又把整页重铺一遍。
                    show(
                        anchor = anchor,
                        series = series,
                        inHistory = inHistory,
                        hotList = hotList,
                        onChanged = onChanged,
                        onItemChanged = onItemChanged,
                        onItemRemoved = onItemRemoved,
                    )
                }
            })
            return
        }
        android.util.Log.i("HGTV-CARD", "menu open series=${series.id} history=$inHistory")

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
                dispatch(
                    activity, series, items[index].action, favorite,
                    onChanged = onChanged,
                    onItemChanged = onItemChanged ?: onChanged,
                    onItemRemoved = onItemRemoved,
                )
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
        onItemChanged: () -> Unit,
        onItemRemoved: ((String) -> Unit)?,
    ) {
        val lib = runCatching { PhoneServices.library(activity) }.getOrNull() ?: return
        // 「收藏 / 稍后看」是纯状态改动：列表成员没变，重绑可见项就够。
        // 「不在热播榜中显示」会从列表里少一项，但**不必重取整页**——
        // 调用方给了 [onItemRemoved] 就只摘掉那一格。
        // 其余动作（删除记录）仍走完整的 [onChanged]：历史栏还要重算「已看完 / 未看完」。
        var itemOnly = false
        var removedId: String? = null
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
                reportRecommendBehavior(
                    activity,
                    if(adding) RecommendBehavior.Event.FAVORITE else RecommendBehavior.Event.UNFAVORITE,
                    series.id,
                )
                toast(activity, if (adding) "已收藏" else "已取消收藏")
                itemOnly = true
            }

            CardAction.LATER -> {
                val adding = runCatching { !lib.queued(series.id) }.getOrDefault(true)
                runCatching { lib.toggleLater(series) }
                toast(activity, if (adding) "已加入稍后观看" else "已移出稍后观看")
                itemOnly = true
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
                reportRecommendBehavior(activity, RecommendBehavior.Event.HIDE, series.id)
                toast(activity, "已不在热播榜中显示")
                itemOnly = true
                removedId = series.id
            }
        }
        val removed = removedId
        when {
            removed != null && onItemRemoved != null -> onItemRemoved(removed)
            itemOnly -> onItemChanged()
            else -> onChanged()
        }
    }

    private fun toast(activity: Activity, text: String) {
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}
