// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import com.hgtv.star.core.Detail

/** 把「第几集」这件事刷到所有界面上（竖屏信息栏 / 横屏标题 / 选集按钮）。 */
internal fun PhonePlayerActivity.bindEpisodeUi(
    d: Detail,
    episodeIndex: Int,
    clearDanmakuOnSwitch: Boolean = false,
) {
    val label = UiText.get(com.hgtv.star.shared.R.string.ui_0342, episodeIndex + 1)
    playerPage.bind(
        d.series,
        label,
        watchText = "选集 ${episodeIndex + 1}/${d.episodes.size}",
    )
    // 横屏不再单独写一行「剧名 第x集」：这一份由 syncPlayInfo() 铺在顶部的信息行里。
    applyLandscapeResize()
    // bind 会把画面模式重置成默认值：换集 / 从横屏退回来都要按用户设置补一次，
    // 否则「选了等比铺满，一换集就变回原样」。
    syncFrameModeImpl()
    applyVideoScaleImpl()
    syncPlayInfoImpl()
    applyDanmakuSettings()
    // bind 会把已知画面尺寸清掉（换集后可能拿不到新的 onVideoSizeChanged），
    // 这里用播放器现有的尺寸补一次：否则「全屏」按钮会一直不出现。
    syncVideoFrameImpl()
    loadDanmaku(
        d.episodes[episodeIndex],
        d.series.id,
        clearOnSwitch = clearDanmakuOnSwitch,
    )
    // 换集不转屏：手机端播放页的方向只由用户的「全屏」点击决定，
    // 上滑看下一集时画面不会自己变成横屏（早先那条「竖屏短剧自动全屏」已经删掉）。
}
