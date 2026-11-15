// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import com.hgtv.star.core.DanmakuMode
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

/** 拉这一屏这一集的弹幕。 */
internal fun PhoneFeedView.loadDanmakuImpl(
    page: PhoneVideoPage,
    index: Int,
    episodeId: String,
    seriesId: String,
    clearOnSwitch: Boolean = false,
) {
    StartupDeferredTasks.scheduleLatestOnMain("danmaku.phone.feed", 30_000L) {
        val lib = feedLibrary ?: return@scheduleLatestOnMain
        page.danmakuView.mode = lib.danmakuMode
        if (index < 0) return@scheduleLatestOnMain
        danmakuLoaderOfImpl(index).load(episodeId, seriesId, clearOnSwitch = clearOnSwitch)
    }
}
