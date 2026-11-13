// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import com.hgtv.star.core.Detail
import com.hgtv.star.phone.ui.PhoneEpisodeGrid
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.shared.data.Library

internal object PhonePlayerEpisodePanels {
    /** 竖屏选集：底部整宽浮层。每行 5 个，一页行数跟着设置走。 */
    fun showPortrait(
        context: Context,
        sheet: PhoneSheet,
        detail: Detail,
        index: Int,
        library: Library?,
        onPlay: (Int) -> Unit,
        onShown: () -> Unit,
    ) {
        sheet.show(
            "选集 · 正在看第 ${index + 1} 集",
            landscape = false,
            notifyHostOnUserDismiss = false,
        ) { panel ->
            panel.addView(
                PhoneEpisodeGrid(
                    context, detail.episodes.size, index,
                    pageSize = library?.episodePageSize ?: 20,
                    compact = false,
                    columns = 5,
                    maxGridHeight = Int.MAX_VALUE,
                    edgeTurn = library?.episodeEdgeTurn ?: true,
                    swipePages = true,
                ) { i ->
                    sheet.dismiss()
                    if (i != index) onPlay(i)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        onShown()
    }

    /** 横屏选集：TV 式底部限高浮层。每行 10 个。 */
    fun showLandscape(
        context: Context,
        sheet: PhoneSheet,
        detail: Detail,
        index: Int,
        library: Library?,
        onPlay: (Int) -> Unit,
        onShown: () -> Unit,
    ) {
        sheet.show(
            "选集",
            landscape = true,
            heightFraction = .78f,
            showHeader = false,
            notifyHostOnUserDismiss = false,
        ) { panel ->
            panel.addView(
                PhoneEpisodeGrid(
                    context, detail.episodes.size, index,
                    pageSize = library?.episodePageSize ?: 20,
                    compact = false,
                    columns = 10,
                    maxGridHeight = Int.MAX_VALUE,
                    edgeTurn = library?.episodeEdgeTurn ?: true,
                    swipePages = true,
                    tvStyle = true,
                    tvSideInsetDp = 29,
                ) { i ->
                    sheet.dismiss()
                    if (i != index) onPlay(i)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        onShown()
    }
}
