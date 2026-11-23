// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hgtv.star.core.Series
import com.hgtv.star.phone.ui.PhoneTouch
import com.hgtv.star.shared.nav.Route

internal class PhoneFeedAdapter(
    private val host: PhoneFeedView,
) : RecyclerView.Adapter<PageHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val page = PhoneVideoPage(
            host.context,
            withTabBar = true,
            fullscreenEntry = true,
            dragSeek = false,
            clearFrameOnRebind = true,
            // 前后各常驻一页：弹幕层不能浮到 media-overlay 上，否则相邻页会互相叠画。
            danmakuMediaOverlay = false,
        )
        page.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        page.onTap = {
            if (!page.isChromeVisible()) host.revealChromeForAdapter(page)
            else host.togglePlayForAdapter()
        }
        page.onLongPress = { zone ->
            when (zone) {
                PhoneTouch.Zone.CENTER -> {
                    if (!page.isChromeVisible()) host.revealChromeForAdapter(page)
                    host.openSettingsForAdapter()
                }
                else -> host.beginTempSpeedForAdapter()
            }
        }
        page.onLongPressEnd = { host.endTempSpeedForAdapter() }
        page.onFullscreen = {
            val series = host.feedItems.getOrNull(host.feedPlayingIndex)
                ?: page.currentSeries()
            if (series != null) {
                val episode = host.liveEpisodeOfForAdapter(
                    series,
                    host.feedPlayingIndex,
                )
                host.launchPlayerForAdapter(
                    series = series,
                    episode = episode,
                    fullscreen = true,
                    play = host.feedPlayer?.playWhenReady == true,
                )
            }
        }
        page.onWatch = { host.openPlayerForAdapter(page) }
        page.onTitle = {
            val series = host.feedItems.getOrNull(host.feedPlayingIndex)
                ?: page.currentSeries()
            if (series != null) {
                host.feedRecommendPlayback.reportEvent(
                    series,
                    com.hgtv.star.core.RecommendBehavior.Event.CLICK,
                    episodeId=host.feedCurrentEpisodeIds[host.feedPlayingIndex].orEmpty(),
                    episodeIndex=host.feedCurrentEpisodeIndex[host.feedPlayingIndex] ?: -1,
                    positionMs=runCatching {
                        host.feedPlayer?.currentPosition ?: 0L
                    }.getOrDefault(0L),
                    durationMs=runCatching {
                        host.feedPlayer?.duration ?: 0L
                    }.getOrDefault(0L),
                )
                host.context.phoneNav().open(Route.Detail(series.id, series.title))
            }
        }
        page.onSeekStart = { }
        page.onSeek = { fraction ->
            val duration = host.feedPlayer?.duration ?: 0L
            if (duration > 0) {
                host.feedHint.text =
                    "${host.clockForAdapter((duration * fraction).toLong())} / " +
                    host.clockForAdapter(duration)
                host.feedHint.visibility = View.VISIBLE
            }
        }
        page.onSeekEnd = { fraction ->
            host.feedPlayer?.let { p ->
                p.duration.takeIf { it > 0 }?.let { p.seekTo((it * fraction).toLong()) }
            }
            host.feedHint.visibility = View.GONE
        }
        return PageHolder(page)
    }

    override fun getItemCount(): Int =
        if (host.feedItems.isEmpty()) 1 else host.feedItems.size

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val page = holder.itemView as PhoneVideoPage
        if (host.feedItems.isEmpty()) {
            page.bind(
                Series(id = "", title = if (host.feedLoading) UiText.get(com.hgtv.star.shared.R.string.ui_0043) else UiText.get(com.hgtv.star.shared.R.string.ui_0128)),
                "",
            )
            return
        }
        val series = host.feedItems[position]
        val label = UiText.get(com.hgtv.star.shared.R.string.ui_0341, (host.feedCurrentEpisodeIndex[position] ?: 0) + 1)
        // **先验归属，再换内容**。ViewHolder 会被复用：这一页上可能还挂着别的
        // position 的播放器（它的画面此刻就在屏幕上）。如果不先摘掉，`bind` 把标题
        // 换成新剧之后，视频层还留着上一部——用户读到的就是「文字是这一部，
        // 画面既不是当前页也不是下一页」。
        val slot = host.pooledPlayerSlot(position)
        val attached = page.playerView.player
        // 会话里还是别的剧时，`feedPlayer` 属于上一部，不能算这一页的。
        val sessionMatches = PhoneSession.seriesId.isEmpty() ||
            PhoneSession.seriesId == series.id
        val owned = attached != null && sessionMatches && (
            slot?.player === attached ||
                (position == host.feedPlayingIndex && host.feedPlayer === attached))
        if (attached != null && !owned) {
            page.playerView.detachPooledOutput(false)
        }
        page.bind(series, label)
        if (position == host.feedPlayingIndex) {
            host.feedList.post { host.rebindCurrentPageForAdapter() }
        } else {
            host.attachPooledPlayerForAdapter(page, position)
        }
    }
}
