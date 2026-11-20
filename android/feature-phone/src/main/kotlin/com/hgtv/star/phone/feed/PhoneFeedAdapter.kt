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
        page.bind(series, label)
        // 快照恢复后先铺缓存封面，等播放器真正出首帧再撤掉。
        // 不能等 playIndex() 取完详情 / 地址才显示，否则冷启动首屏会先黑一段时间。
        if (host.pooledPlayerSlot(position)?.firstFrameRendered != true) {
            page.showEpisodeCover(series.cover)
        }
        if (position == host.feedPlayingIndex) {
            host.feedList.post { host.rebindCurrentPageForAdapter() }
        } else {
            host.attachPooledPlayerForAdapter(page, position)
        }
    }
}
