// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Intent

/**
 * 退出播放页：不要系统转场动画。
 *
 * 退回去时目标页（信息流）自己就是亮的画面，但转场那 200ms 里系统会把本页淡出、
 * 中间露出窗口底色——读起来就是「退出去也闪一下」。直接切过去更连贯。
 */
internal fun PhonePlayerActivity.preparePlayerFinish() {
    // 交给推荐页：交接窗口内两边都不暂停，播放器一直在跑，Surface 只是换了宿主。
    PhoneSession.playerForeground = false
    if (intent.getBooleanExtra(PhonePlayerActivity.EXTRA_RECOMMEND, false)) {
        playerReturningToFeed = true
        PhoneSession.beginHandoff()
    }
}

/**
 * 换一部接着看时的安全网。
 *
 * 本页是 singleTop：同栈顶再 startActivity 会复用当前实例而不是新建。
 * 正常情况下 openSeries 先 finish() 再开新的（旧实例已在收尾，不会被复用），
 * 但只要有一次被复用，intent 换了、detail 却还是上一部，页面就会「点了没反应」。
 * 这里按新 intent 就地重建：详情、集号、媒体源全部属于上一部，重建比原地改状态安全。
 */
internal fun PhonePlayerActivity.handleNewPlayerIntent(intent: Intent?) {
    intent ?: return
    setIntent(intent)
    val id = intent.getStringExtra(PhonePlayerActivity.EXTRA_SERIES_ID).orEmpty()
    if (id.isBlank()) return
    if (id == detail?.series?.id) return
    playerSheet.dismiss()
    dismissLandscapeSettingsImpl()
    recreate()
}
