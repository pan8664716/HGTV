// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import com.hgtv.star.core.CategoryFilterOption
import com.hgtv.star.core.CategorySelection
import com.hgtv.star.core.Series
import com.hgtv.star.core.categoryFeed
import com.hgtv.star.shared.ui.LoadingDotsView
import com.hgtv.star.tv.TvStyle

/**
 * 本机兴趣画像（进推荐页时建一次，之后每页复用）。
 *
 * 读的是本机观看历史与收藏——推荐页自己不写历史，但用户从详情页 / 正式播放看过的那些
 * 都记在里面，正好是「这台机器喜欢什么」最可靠的来源。
 */
/**
 * 取一次分类接口的筛选面板，只留下「受众」那一行，交给调用方建芯片。
 *
 * 为什么要现取：男频 / 女频的 `selector_item_id` 是平台自己那一套，代码里没有可信的
 * 固定值（`docs/HONGGUO_API.md` 只记了体裁与题材）。猜错一个串的后果是推荐流一条都推不出来，
 * 因此宁可少一个开关，也不写死一个没验证过的标识。
 *
 * 取到之后抄进 [recommendGenderOptions]：同一次运行里再进设置页不再发请求。
 * 请求在途时直接返回（不回调）：这一屏就先不建那一行，下次进来再说。
 */
fun TvMainActivity.loadGenderOptions(onReady: (List<CategoryFilterOption>) -> Unit) {
    recommendGenderOptions?.let { onReady(it); return }
    if(recommendGenderLoading) return
    recommendGenderLoading=true
    io.submit {
        if(!library.deviceRegistered()) registerDeviceOnce()
        val options=runCatching {
            repository.categoryFeed(CategorySelection(),1,needPanel=true)
                .panel?.row(CategorySelection.TYPE_GENDER)?.options
        }.getOrNull()
        main.post {
            recommendGenderLoading=false
            if(options.isNullOrEmpty()) return@post
            recommendGenderOptions=options
            // 存着的那个 id 已经不在可选值里（平台换了标识）：退回「不限」，
            // 否则会一直带着一个接口不认的值去请求。
            if(library.recommendGender.isNotEmpty() && options.none { it.id==library.recommendGender }) {
                library.recommendGender=""
            }
            onReady(options)
        }
    }
}

/** 整屏说明（首次进入 / 取不到推荐时用；此时还没有播放画面）。 */
fun TvMainActivity.showRecommendMessage(message: String,loading: Boolean=false) {
    // 走到「整屏只有一行字」就说明这一屏没有画面：把播放器那一层收掉再清 root，
    // 否则换剧 / 补页失败时会留下「画面没了、声音还在放」。`screen` 不动（仍是 recommend），
    // 因此返回键照样回主页面、也不会写历史。
    dismissRecommendOverlay()
    dismissRecommendCover()
    if(playerView!=null) detachPlayerSurface()
    root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
    // 画面之外那一层也随这棵树一起没了（见 `overlayLayer`）。
    overlayLayer=null
    if(loading) {
        root.addView(
            LoadingDotsView(this).apply { label=message },
            FrameLayout.LayoutParams(-2,-2,Gravity.CENTER),
        )
    } else {
        val label=text(message,20f,TvStyle.playerText).apply {
            gravity=Gravity.CENTER
            setLineSpacing(dp(6).toFloat(),1f)
        }
        root.addView(label,FrameLayout.LayoutParams(-1,-1))
    }
    // 整棵树被换掉了：调试模式下面板照旧重挂一次（见 `DebugOverlay.kt`）。
    reattachDebugOverlay()
}

/**
 * 推荐页还没有播放器时的准备态。
 *
 * 标题从第一帧起就是剧名，加载状态只放在副标题里；这样详情回来、播放器挂上之后
 * 标题不会从“正在加载《剧名》…”突然变成“剧名”，只会有内容替换，没有文字闪动。
 */
fun TvMainActivity.showRecommendLoading(series: Series) {
    // 推荐换作已经有 ViewPager2 / 池化播放器时，只切 loading 覆盖层；
    // 不能走下面的 detachPlayerSurface + root.removeAllViews，否则会把刚预热好的
    // 目标 Surface 和池一起销毁，切换必然回到“黑屏等完整起播”的旧路径。
    if(tvPlaybackPager!=null) {
        showRecommendCover()
        attachRecommendOverlay(series)
        showCenterLoading("正在加载《${series.title}》…")
        return
    }
    dismissRecommendOverlay()
    dismissRecommendCover()
    if(playerView!=null) detachPlayerSurface()
    root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
    overlayLayer=null
    if(touchArmed) {
        val box=column().apply {
            gravity=Gravity.CENTER
            setPadding(dp(24),0,dp(24),0)
        }
        box.addView(text(series.title,28f,TvStyle.playerText).apply {
            gravity=Gravity.CENTER
            maxLines=2
        }, lp(-1,-2))
        // 等待统一用三点动画（与启动页 / 列表加载同一个控件），不再单独写一行「正在加载…」。
        box.addView(
            LoadingDotsView(this).apply { label="正在加载…" },
            lp(-2,-2).apply { topMargin=dp(10) },
        )
        root.addView(box,FrameLayout.LayoutParams(-1,-1))
    } else {
        // 电视端直接复用正式推荐浮层：标题位置从一开始就是最终位置。
        attachRecommendOverlay(series)
        // 等待放在**屏幕正中**的加载框里：浮层里加一行会把整块往上顶（它贴底）。
        showCenterLoading("正在加载《${series.title}》…")
    }
    reattachDebugOverlay()
}

/** 播放画面上的一行提示（已经有画面时用，不打断正在播的这一部）。 */
fun TvMainActivity.showRecommendToast(message: String) {
    recommendNotice=message
    recommendNoticeAt=android.os.SystemClock.elapsedRealtime()
    // 触屏的推荐页没有那块浮层（见 `attachRecommendOverlay`），这条临时提示改挂在手势层上：
    // 否则「快退 10 秒 · 00:12」「后面的推荐暂时取不到」这些反馈在触屏上完全看不见。
    if(touchArmed && recommendOverlay==null) flashSeekHint(message,true,centered=true)
    updateRecommendOverlay()
}
