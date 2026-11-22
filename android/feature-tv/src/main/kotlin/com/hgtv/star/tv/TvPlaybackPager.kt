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
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ui.PlayerView

/**
 * 电视推荐页的三页窗口。
 *
 * ViewPager2 负责当前 / 前后页的 Surface 常驻与转场；播放中控仍然只面向当前活动
 * [com.google.android.exoplayer2.ExoPlayer]。相邻页的具体播放器由 [TvPlaybackPoolState]
 * 独立维护，切换时把命中的池实例提升为活动实例。正式播放页不创建这个容器。
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
        pageRecycler.recycledViewPool.setMaxRecycledViews(0,2)
        pager.setCurrentItem(host.currentPlaybackPageIndex(),false)
    }

    fun setCurrentIndex(index: Int,smooth: Boolean=true) {
        val safe=index.coerceIn(0,(adapter.itemCount-1).coerceAtLeast(0))
        if(BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-SWITCH",
                "pager.set index=$safe smooth=$smooth current=${pager.currentItem} "+
                    "count=${adapter.itemCount} children=${pageRecycler.childCount}",
            )
        }
        if(pager.currentItem!=safe) pager.setCurrentItem(safe,smooth)
        if(BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-SWITCH",
                "pager.done index=$safe current=${pager.currentItem} children=${pageRecycler.childCount}",
            )
        }
    }

    /**
     * 切换 pager 当前的数据页并同步活动播放器。
     *
     * 当前仅推荐页使用；正式播放页会在进入时释放 pager，恢复单个 PlayerView。
     */
    fun switchScreen(next: String,index: Int) {
        screen=next
        adapter.lastItemCount=adapter.itemCount
        adapter.notifyDataSetChanged()
        setCurrentIndex(index,false)
        post {
            if(isAttachedToWindow && screen==next && pager.currentItem==index) {
                syncCurrentPlayerWhenReady(index)
            }
        }
    }

    fun refreshPages() {
        if(adapter.itemCount!=adapter.lastItemCount) {
            adapter.lastItemCount=adapter.itemCount
            adapter.notifyDataSetChanged()
        }
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

    /**
     * 把某个播放器从所有现存页面上解绑。
     *
     * 池回收不能只依赖 `pageAt(slot.pageIndex)`：页面可能已经被 ViewPager2 回收，
     * 但它的 `PlayerView` 仍持有旧播放器。先扫全部子页面再 release，避免旧 Surface
     * 回调落到已经进入 `Uninitialized` 的 MediaCodec 上。
     */
    fun detachPlayer(player: ExoPlayer?) {
        if (player == null) return
        val recycler = pager.getChildAt(0) as? RecyclerView ?: return
        for (index in 0 until recycler.childCount) {
            val page = recycler.getChildAt(index) as? TvPlaybackSurfacePage ?: continue
            if (page.playerView.player === player) page.detachPlayerOutput()
        }
    }

    /**
     * 等 ViewPager2 真正创建目标页后再启动该页的播放器。
     *
     * 推荐页换作会同时触发 `notifyDataSetChanged` / `setCurrentItem`，ViewHolder
     * 不保证在下一帧就存在。Phone 端的 `awaitPlayerPagerPage` 也在这里等页，
     * 不能把“播放器已经准备好”误当成“目标 Surface 已经可挂载”。
     */
    fun awaitPage(index: Int,attempts: Int=60,onReady: ()->Unit) {
        val startedAt=android.os.SystemClock.elapsedRealtime()
        if(attempts==60 && BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-POOL",
                "await page=$index attached=$isAttachedToWindow found=${pageAt(index)!=null}",
            )
        }
        if(isAttachedToWindow && pageAt(index)!=null) {
            if(BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-SWITCH",
                    "await.ready page=$index elapsed=${android.os.SystemClock.elapsedRealtime()-startedAt}ms",
                )
            }
            onReady()
            return
        }
        if(attempts<=0) {
            if(BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-SWITCH",
                    "await.timeout page=$index elapsed=${android.os.SystemClock.elapsedRealtime()-startedAt}ms",
                )
            }
            onReady()
            return
        }
        postDelayed({
            // 不能因为 pager 正在切树 / 暂时 detached 就把这次起播静默丢掉。
            // 等不到 ViewHolder 也必须回到播放中控，让它在下一帧继续建层或恢复。
            if(!isAttachedToWindow) {
                onReady()
                return@postDelayed
            }
            awaitPage(index,attempts-1,onReady)
        },16L)
    }

    fun syncCurrentPlayer() {
        syncCurrentPlayerAt(pager.currentItem)
    }

    /**
     * 页面可能刚经历 notifyDataSetChanged / 平滑翻页，ViewHolder 要等下一次布局才可用。
     * Phone 端的 `awaitPlayerPagerPage` 就是同一套思路；这里用有限次 post 重试，
     * 找到目标页后立即把活动播放器挂到它自己的 Surface 上。
     */
    fun syncCurrentPlayerWhenReady(index: Int=pager.currentItem,attempts: Int=8) {
        if(syncCurrentPlayerAt(index)) return
        if(attempts<=0) return
        post {
            if(!isAttachedToWindow) return@post
            syncCurrentPlayerWhenReady(index,attempts-1)
        }
    }

    private fun syncCurrentPlayerAt(index: Int): Boolean {
        val page=pageAt(index)
        if(BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-POOL",
                "sync page=$index found=${page!=null} current=${pager.currentItem} "+
                    "children=${pageRecycler.childCount} attached=$isAttachedToWindow "+
                    "player=${host.player?.hashCode()}",
            )
        }
        page ?: return false
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
        if(page.playerView.player!==host.player) {
            page.detachPlayerOutput()
            page.playerView.player=host.player
        }
        return true
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
    var lastItemCount=-1

    override fun getItemCount(): Int = when(screen()) {
        "recommend" -> host.recommendItems.size.coerceAtLeast(1)
        "player" -> host.detail?.episodes?.size?.coerceAtLeast(1) ?: 1
        else -> 1
    }

    // 所有作品页共用同一种 ViewHolder。按 position 生成 viewType 会让回收池把滑过的
    // 每一页都当成独立类型保留，低内存电视上最终耗尽 Java 堆。
    override fun getItemViewType(position: Int): Int = 0

    override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): TvPlaybackPageHolder {
        val page=if(initialAvailable) {
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
