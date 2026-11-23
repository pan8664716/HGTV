// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.StreamInfo

/** 本地准备失败必须保留异常：调用方据此决定是否作废缓存后重取。 */
internal fun PhonePlayerActivity.prepareSource(
    episodeId: String,
    info: StreamInfo,
): RemoteVideo =
    playbackSource.prepare(episodeId, info)

/** 预热路径只需要结果，失败就丢掉；播放起播路径走 [prepareSource] 保留异常。 */
internal fun PhonePlayerActivity.prepare(
    episodeId: String,
    info: StreamInfo,
): RemoteVideo? =
    runCatching { prepareSource(episodeId, info) }.getOrNull()

/**
 * 完成从推荐页带进来的「全屏」意图。
 *
 * 不能一律转横屏：横屏推荐页里的竖版短剧点全屏时，应直接进入竖屏全屏；
 * 否则会先停在横屏播放页，用户还得再点一次全屏。
 */
internal fun PhonePlayerActivity.enterPendingFullscreen() {
    if (!listenerPendingFullscreen) return
    if (playerLandVideoWidth <= 0 || playerLandVideoHeight <= 0) return
    listenerPendingFullscreen = false
    if (playerLandscapeMode && playerLandVideoHeight > playerLandVideoWidth) {
        enterPortraitFullscreen()
    } else {
        // 从推荐页点「全屏」直接进来：返回键应当回推荐页，不该先转回竖屏。
        enterFullscreen(fromPortrait = false)
    }
}
