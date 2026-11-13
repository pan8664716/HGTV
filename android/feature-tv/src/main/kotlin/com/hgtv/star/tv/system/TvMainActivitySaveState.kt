// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.os.Bundle

internal fun TvMainActivity.saveMainState(outState: Bundle) {
    if(!mainInitialized || !library.isLoaded) return
    outState.putString(STATE_SCREEN,screen)
    outState.putInt(STATE_TAB,tab)
    outState.putString(STATE_COLLECTION,collection.orEmpty())
    // 详情页用 `detail`，播放页 / 推荐页可能还没进详情，退回 `pendingDetail`。
    outState.putString(STATE_SERIES,detail?.series?.id ?: pendingDetail?.id.orEmpty())
    outState.putInt(STATE_EPISODE,episodeIndex)
    outState.putLong(STATE_POSITION,runCatching { currentPosition() }.getOrDefault(startPosition))
}
