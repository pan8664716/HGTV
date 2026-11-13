// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/** 剧卡菜单里的一项：文案 + 语义动作。 */
data class CardMenuItem(val label: String, val action: CardAction)

/** 剧卡菜单的动作。文案随状态变（收藏 / 取消收藏），动作不变，因此派发按动作走。 */
enum class CardAction { RESUME, FAVORITE, LATER, DETAIL, DELETE_HISTORY, HIDE_FROM_HOT }

/**
 * 剧卡菜单（长按确认 / 遥控器菜单键）的选项顺序。
 *
 * 这里只算「有哪些项、什么顺序、文案是什么」，不依赖 Android，因此可以被单元测试锁住。
 * 菜单项是按页面动态增删的：历史上下文多出「删除记录」，
 * 热播榜（首页四个「热播…」剧架与排行榜页）才有「不在热播榜中显示」。
 * 顺序与文案收敛成一个纯函数之后，中间插一项不会再让后面的分支整体错位。
 */
object CardMenu {
    /**
     * @param hasProgress 这部剧本机有观看进度（决定第一项是「继续观看」还是「直接播放」）
     * @param inHistory 当前上下文是不是观看记录：首页历史剧架、历史集合页、
     *   以及「我的 → 历史」那一栏（账号侧的历史也在里面，同样要能删）
     * @param hotList 当前上下文是不是热播榜（首页四个「热播…」剧架或排行榜页）
     */
    fun items(
        hasProgress: Boolean,
        favorite: Boolean,
        queued: Boolean,
        inHistory: Boolean,
        hotList: Boolean
    ): List<CardMenuItem> {
        val rows = mutableListOf(
            CardMenuItem(if (hasProgress) "继续观看" else "直接播放", CardAction.RESUME),
            CardMenuItem(if (favorite) "取消收藏" else "收藏这部剧", CardAction.FAVORITE),
            CardMenuItem(if (queued) "移出稍后观看" else "加入稍后观看", CardAction.LATER),
            CardMenuItem("查看剧集详情", CardAction.DETAIL)
        )
        if (inHistory) rows += CardMenuItem("删除记录", CardAction.DELETE_HISTORY)
        if (hotList) rows += CardMenuItem("不在热播榜中显示", CardAction.HIDE_FROM_HOT)
        return rows
    }
}
