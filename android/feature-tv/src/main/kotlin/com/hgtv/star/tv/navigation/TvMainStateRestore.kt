// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.os.Bundle
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.tv.list.showCatalog

/**
 * 按 `onSaveInstanceState` 存下的现场回到原来的页面。
 *
 * @return 是否真的恢复了。false 表示现场不可用（没存过、或那一部已经不在本机历史里），
 *         调用方按正常启动路径处理。
 */
internal fun TvMainActivity.restoreLastScreenForResume(state: Bundle): Boolean {
    val savedScreen=state.getString(STATE_SCREEN).orEmpty()
    if(savedScreen.isEmpty()) return false
    val seriesId=state.getString(STATE_SERIES).orEmpty()
    // 播放页 / 详情页 / 推荐页：回到那一部的详情并接着上次那一集。
    //
    // `autoplay=false` 是刻意的：用户可能是按 Home 离开的，回来时不该自动出声。
    // 位置由本机历史提供（`openDetail` 的 `resumeTargetFor`），与 Bundle 里那份一致。
    //
    // 三页都走同一条路由：`Route.Player` 在电视端就是「按本机进度接着播」，
    // 与改造前那句 `openDetail(series, resume=true)` 逐字等价（详情页也是 resume，
    // 这是既有行为，本次重构不改）。
    //
    // `open()` 返回 false = 这一部已经不在本机历史里（用户可能清过历史）。
    // 那种情况按「现场不可用」处理、落回首页——而不是打开一个没有标题的空壳详情页。
    val restored=when(savedScreen) {
        "player","detail","recommend" -> navigator.open(Route.Player(seriesId))
        else -> false
    }
    if(restored) return true
    // 其它页面（含上面「现场不可用」的情况）：回到原来的标签 / 集合页，再按正常路径铺内容。
    tab=state.getInt(STATE_TAB,TAB_HOME)
    state.getString(STATE_COLLECTION).orEmpty().takeIf { it.isNotEmpty() }?.let { collection=it }
    showCatalog(load=true)
    return true
}
