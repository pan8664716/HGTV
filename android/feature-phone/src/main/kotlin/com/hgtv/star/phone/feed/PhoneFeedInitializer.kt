// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hgtv.star.phone.ui.PhoneColor
import com.hgtv.star.phone.ui.PhonePagerSnapHelper
import com.hgtv.star.phone.ui.dp
import kotlinx.coroutines.launch

internal fun PhoneFeedView.initializeFeed() {
    setBackgroundColor(PhoneColor.BG)
    feedList.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    feedList.adapter = feedAdapter
    feedList.overScrollMode = View.OVER_SCROLL_NEVER
    /*
     * 关掉这一层列表的**嵌套滚动**。
     *
     * 推荐页外面是横向 pager，本列表只吃纵向位移，横向那一份会被
     * `dispatchNestedPreScroll` 交给 pager——那条分发不经过 `onInterceptTouchEvent`，
     * 于是 pager 的「锁住横滑」形同虚设：设置里关掉「推荐页左右滑动」后，
     * 手指在视频上横着一划照样整页翻走，用户读到的就是「关了没生效」。
     *
     * 关掉它不影响任何纵向行为：上下滑、翻页吸附、惯性都是列表自己算的；
     * 横向手势此后只走标准的触摸分发（父 pager 的 `onInterceptTouchEvent`），
     * 那条路上才有锁。
     */
    feedList.isNestedScrollingEnabled = false
    PhonePagerSnapHelper { feedList.height }.attachToRecyclerView(feedList)
    feedList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
            if (state != RecyclerView.SCROLL_STATE_IDLE) return
            val index = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            val userScroll = feedList.userScroll
            feedList.userScroll = false
            if (userScroll && index >= 0 && index != feedPlayingIndex) {
                playIndexForSettings(index, -1L, clearDanmakuOnSwitch = true)
            }
            if (index >= 0 &&
                com.hgtv.star.shared.feed.RecommendFeedPolicy.shouldPrefetch(
                    feedItems.size - index - 1,
                )
            ) loadMore()
        }
    })
    addView(
        feedList,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    addView(
        feedHint,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = context.dp(64)
        },
    )

    feedScope.launch {
        feedLibrarySlot = PhoneServices.awaitLibrary(context)
        feedUserSpeed = feedLibrary?.playbackSpeed ?: 1f
        scheduleChromeHideImpl()
    }
    // 冷启动先铺落盘快照（电视端 RecommendCache 的同一条路）：
    // 列表与「上一次停在哪」都在盘上，首屏不必等取流那 1 秒，也不会每次都从同一部开始。
    restoreSnapshot()
    // 当前页由 PhoneRecommendPage.onShown() 触发加载；相邻体裁页先不取流，
    // 避免首屏同时发出多条推荐请求。
}
