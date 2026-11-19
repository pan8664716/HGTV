// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.graphics.Color
import android.graphics.PixelFormat
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.exoplayer2.ui.PlayerView

/**
 * 电视播放页的三页窗口。
 *
 * ViewPager2 负责当前 / 前后页的 Surface 常驻与转场；播放中控仍然只面向当前活动
 * [com.google.android.exoplayer2.ExoPlayer]。相邻页的具体播放器由 [TvPlaybackPoolState]
 * 独立维护，切换时把命中的池实例提升为活动实例。
 */
internal class TvPlaybackPager(
    private val host: TvMainActivity,
) : FrameLayout(host) {
    var screen: String=host.screen
        private set
    val initialPage=TvPlaybackSurfacePage(host)
    private val adapter=TvPlaybackPageAdapter(host,initialPage) { screen }
    private val pager=ViewPager2(host).apply {
        orientation=ViewPager2.ORIENTATION_VERTICAL
        offscreenPageLimit=1
        overScrollMode=View.OVER_SCROLL_NEVER
        isUserInputEnabled=false
        adapter=this@TvPlaybackPager.adapter
    }

    private val pageRecycler: RecyclerView
        get()=pager.getChildAt(0) as RecyclerView

    val currentIndex: Int
        get()=pager.currentItem

    init {
        setBackgroundColor(Color.BLACK)
        addView(pager,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        pageRecycler.isNestedScrollingEnabled=false
        pageRecycler.itemAnimator=null
        pager.setCurrentItem(host.currentPlaybackPageIndex(),false)
    }

    fun setCurrentIndex(index: Int,smooth: Boolean=true) {
        val safe=index.coerceIn(0,(adapter.itemCount-1).coerceAtLeast(0))
        if(pager.currentItem!=safe) pager.setCurrentItem(safe,smooth)
    }

    /**
     * 推荐页与正式播放页共用同一个 pager 实例。
     *
     * 进入正片时只切换页数据与当前页，不拆掉整棵播放层；当前活动播放器仍由
     * [syncCurrentPlayer] 挂到目标页的独立 Surface 上。
     */
    fun switchScreen(next: String,index: Int) {
        screen=next
        adapter.notifyDataSetChanged()
        setCurrentIndex(index,false)
        post {
            if(isAttachedToWindow && screen==next && pager.currentItem==index) {
                syncCurrentPlayer()
            }
        }
    }

    fun refreshPages() {
        adapter.notifyDataSetChanged()
        if(adapter.itemCount>0) {
            setCurrentIndex(pager.currentItem.coerceIn(0,adapter.itemCount-1),false)
        }
    }

    fun pageAt(index: Int): TvPlaybackSurfacePage? {
        val recycler=pageRecycler
        (recycler.findViewHolderForAdapterPosition(index) as? TvPlaybackPageHolder)?.let {
            return it.page
        }
        for(childIndex in 0 until recycler.childCount) {
            val child=recycler.getChildAt(childIndex)
            if(recycler.getChildAdapterPosition(child)==index) {
                return child as? TvPlaybackSurfacePage
            }
        }
        return null
    }

    fun currentPage(): TvPlaybackSurfacePage? = pageAt(pager.currentItem)

    fun syncCurrentPlayer() {
        val page=currentPage() ?: return
        val active=host.player
        val recycler=pageRecycler
        for(index in 0 until recycler.childCount) {
            val other=recycler.getChildAt(index) as? TvPlaybackSurfacePage ?: continue
            if(other!==page) {
                if(active!=null && other.playerView.player===active) other.detachPlayerOutput()
                else if(other.playerView.player===host.player) other.detachPlayerOutput()
            }
        }
        host.playerView=page.playerView
        if(page.playerView.player!==host.player) page.playerView.player=host.player
    }

}

internal class TvPlaybackPageHolder(
    val page: TvPlaybackSurfacePage,
) : RecyclerView.ViewHolder(page)

private class TvPlaybackPageAdapter(
    private val host: TvMainActivity,
    private val initialPage: TvPlaybackSurfacePage,
    private val screen: ()->String,
) : RecyclerView.Adapter<TvPlaybackPageHolder>() {
    private var initialAvailable=true

    override fun getItemCount(): Int = when(screen()) {
        "recommend" -> host.recommendItems.size.coerceAtLeast(1)
        "player" -> host.detail?.episodes?.size?.coerceAtLeast(1) ?: 1
        else -> 1
    }

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): TvPlaybackPageHolder {
        val page=if(initialAvailable && viewType==host.currentPlaybackPageIndex()) {
            initialAvailable=false
            initialPage
        } else TvPlaybackSurfacePage(host)
        page.layoutParams=RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        return TvPlaybackPageHolder(page)
    }

    override fun onBindViewHolder(holder: TvPlaybackPageHolder,position: Int) {
        host.bindTvPlaybackPage(holder.page,position)
    }

    override fun onViewRecycled(holder: TvPlaybackPageHolder) {
        host.detachTvPlaybackPage(holder.page)
    }
}

internal class TvPlaybackSurfacePage(
    host: TvMainActivity,
) : FrameLayout(host) {
    private val rotatedSurface=host.library.autoFullscreen && host.autoFullscreenAvailable()
    val playerView: PlayerView=if(rotatedSurface) {
        host.layoutInflater.inflate(R.layout.player_surface_texture,this,false) as PlayerView
    } else {
        FixedFirstFramePlayerView(host)
    }.apply {
        useController=false
        resizeMode=host.frameResizeMode()
        isFocusable=false
        setKeepContentOnPlayerReset(true)
        setShutterBackgroundColor(Color.BLACK)
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        setAspectRatioListener { _,_,_ ->
            if(host.playerView===this) host.syncPlaybackLetterbox()
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(playerView,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        runCatching {
            val surface=playerView.videoSurfaceView as? SurfaceView ?: return@runCatching
            surface.setZOrderOnTop(false)
            surface.setZOrderMediaOverlay(false)
            surface.holder.setFormat(PixelFormat.OPAQUE)
            surface.setBackgroundColor(Color.BLACK)
        }
    }

    fun detachPlayerOutput() {
        playerView.setKeepContentOnPlayerReset(false)
        playerView.player=null
        playerView.setKeepContentOnPlayerReset(true)
    }
}
