// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.shared.debug.StartupDeferredTasks

/** 取（或建）某一屏的弹幕加载器。 */
internal fun PhoneFeedView.danmakuLoaderOfImpl(
    index: Int,
): com.hgtv.star.shared.media.DanmakuLoader =
    feedDanmakuLoaders.getOrPut(index) {
        com.hgtv.star.shared.media.DanmakuLoader(
            scope = feedScope,
            repository = { PhoneServices.repository(context) },
            mode = { feedLibrary?.danmakuMode ?: DanmakuMode.DEFAULT },
            target = { feedPageAt(index)?.danmakuView },
            post = { block -> feedList.post(block) },
            alive = { isAttachedToWindow },
        )
    }

/** 把本机偏好落到这一页的弹幕层。 */
internal fun PhoneFeedView.applyDanmakuSettingsImpl(page: PhoneVideoPage) {
    val lib = feedLibrary ?: return
    val view = page.danmakuView
    view.alignBelowPhonePortraitChrome(
        bottomPx=feedDanmakuTopInsetPx,
        infoGapPx=context.dp(8),
    )
    view.mode = lib.danmakuMode
    view.sizeFollowsLongEdge = true
    view.textScale = PhoneDanmakuSize.renderScale(context)
    view.textAlpha = lib.danmakuAlpha
    view.speed = lib.danmakuSpeed
    view.mergeRepeats = lib.danmakuMerge
    view.mergeMinCount = lib.danmakuMergeMin
    view.effectsEnabled = lib.danmakuEffects
    view.visibility = if (lib.danmakuMode == DanmakuMode.HIDDEN) View.GONE else View.VISIBLE
}

/** 推荐页顶栏尺寸变化后，同时更新当前已铺出的相邻信息流页。 */
internal fun PhoneFeedView.applyDanmakuTopInsetImpl(insetPx: Int) {
    val inset = insetPx.coerceAtLeast(0)
    if (feedDanmakuTopInsetPx == inset) return
    feedDanmakuTopInsetPx = inset
    for (index in 0 until feedList.childCount) {
        val page = feedList.getChildAt(index) as? PhoneVideoPage ?: continue
        page.danmakuView.alignBelowPhonePortraitChrome(
            bottomPx=inset,
            infoGapPx=context.dp(8),
        )
    }
}

/** 拉这一屏这一集的弹幕。 */
internal fun PhoneFeedView.loadDanmakuImpl(
    page: PhoneVideoPage,
    index: Int,
    episodeId: String,
    seriesId: String,
    clearOnSwitch: Boolean = false,
) {
    // 主动换剧立刻停住旧弹幕：load 是延迟调度的，等它落地时缓冲期旧弹幕还在走。
    if (clearOnSwitch && index >= 0) danmakuLoaderOfImpl(index).clear()
    StartupDeferredTasks.scheduleLatestOnMain("danmaku.phone.feed", 30_000L) {
        val lib = feedLibrary ?: return@scheduleLatestOnMain
        page.danmakuView.mode = lib.danmakuMode
        if (index < 0) return@scheduleLatestOnMain
        danmakuLoaderOfImpl(index).load(episodeId, seriesId, clearOnSwitch = clearOnSwitch)
    }
}
