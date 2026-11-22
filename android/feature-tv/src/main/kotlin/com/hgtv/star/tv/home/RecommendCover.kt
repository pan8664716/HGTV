// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.hgtv.star.shared.data.ArtworkLoader
import com.hgtv.star.tv.TvStyle

/**
 * 切换过渡：按键瞬间全黑（把上一部收住）/ 新片源首帧后淡出（把这一部放出来）。
 *
 * 换作瞬间必须**立即全黑**，不能把上一部保留在 Surface 上的旧帧露出来；
 * 新片源真实出帧后仍用 260ms 淡出，保留换台的柔和过渡。
 */
private const val COVER_OUT_MS=260L
/**
 * 连划时的淡出时长（只用于触屏，见 [revealRecommendCover]）。
 *
 * 电视保持 260ms 不动：遥控器一秒一下，过渡要读得出「换了一部」。
 */
private const val COVER_OUT_FAST_MS=120L
/**
 * 低内存设备关闭动画时，首帧回调也不等于 SurfaceFlinger 已经完成下一次合成。
 * 至少保留两帧左右，避免旧 buffer 在新画面合成前露出来。
 */
private const val COVER_MIN_HOLD_MS=64L
/**
 * 换剧那一下**立即**把目标集封面盖上去（没有封面时退回黑底），
 * 不能看到上一部旧帧被拉伸。
 *
 * 时机是**按键那一刻**，不是新源准备好之后：先盖目标集封面（无图则黑底），再换源、
 * 等新源第一帧，由 [revealRecommendCover] 淡回来。这里不做淡入：无缓存时取地址 / 准备索引要 1 秒以上，
 * 如果黑场还在半透明阶段，PlayerView 会保留上一部内容并按新布局显示，用户就会看到旧帧被拉伸。
 *
 * 不抓上一部帧、不翻页；只有新片源真实出帧后才淡出。
 *
 * 这一层是普通 View（画在窗口层里），不是 SurfaceView，因此它的 alpha 动画在电视盒子
 * 上也一定生效；盖住期间不参与焦点、不拦截按键（推荐页的按键本来都由 `dispatchKeyEvent` 消化）。
 */
fun TvMainActivity.showRecommendCover(imageUrl: String?=null) {
    // 推进令牌会取消上一轮尚未完成的淡出，保证这次换作不会被上一部提前揭开。
    recommendCoverToken++
    val existing=recommendCover
    if(existing!=null) {
        // 已经盖着（连着换剧 / 上一部还在取详情）：保持黑，只取消上一次排好的淡出。
        // 画面层可能在两次换剧之间被重建过（整棵树换掉），这时把这一层重新挂回去。
        if(existing.parent==null) attachRecommendCoverView(existing)
        existing.animate().cancel()
        existing.alpha=1f
        if(existing is ImageView) {
            if(imageUrl.isNullOrBlank()) existing.setImageDrawable(null)
            else artwork.load(
                existing,
                imageUrl,
                ArtworkLoader.PRIORITY_FOCUSED,
                root.width.coerceAtLeast(640),
                root.height.coerceAtLeast(360),
            )
        }
        if(BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-RATIO",
                "cover show existing parent=${existing.parent!=null} "+
                    "size=${existing.width}x${existing.height} alpha=${existing.alpha} "+
                    "root=${root.width}x${root.height} children=${root.childCount}",
            )
        }
    } else {
        val cover=ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType=ImageView.ScaleType.FIT_CENTER
            isFocusable=false; isClickable=false
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // 不做淡入：从按键这一帧起就必须挡住 PlayerView 保留的上一部内容。
            alpha=1f
        }
        if(!imageUrl.isNullOrBlank()) {
            artwork.load(
                cover,
                imageUrl,
                ArtworkLoader.PRIORITY_FOCUSED,
                root.width.coerceAtLeast(640),
                root.height.coerceAtLeast(360),
            )
        }
        attachRecommendCoverView(cover)
        recommendCover=cover
        if(BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-RATIO",
                "cover show new parent=${cover.parent!=null} "+
                    "size=${cover.width}x${cover.height} alpha=${cover.alpha} "+
                    "root=${root.width}x${root.height} children=${root.childCount}",
            )
        }
    }
    recommendCoverShownAt=android.os.SystemClock.elapsedRealtime()
}

/**
 * 推荐页「有声音、没画面」的自愈：就地重建播放层。
 *
 * 触发点是两条看门狗（见 `PlaybackDiagnostics.kt` 的 `armPlaybackWatchdog` /
 * `armFrameStallWatchdog`）：起播后迟迟不出帧，或播着播着不再出帧。这两条路**都不抛异常**，
 * [playerFailure] 收不到，界面上也不会挂出错误页，用户看到的就是「黑屏、有声音、
 * 快进快退都不好使」——拖动进度条同样要解码器吐帧，它已经不吐了，所以拖也没用。
 *
 * 修法与用户自己试出来的完全一致：进正片、换一集，画面就回来了。那一步的差别不是换了片源，
 * 而是**整棵树重建**——换掉 `PlayerView` / Surface、放掉 MediaCodec 再重建
 *（[playEpisode] 的 `recovering=true` 正是走这条路）。因此这里在原位做同一件事：
 * 不跳剧、不写历史、不改 `screen`，从当前位置续播。
 *
 * 不无限重建：同一部片最多 [PICTURE_RECOVERY_LIMIT] 次、两次之间至少隔
 * [PICTURE_RECOVERY_COOLDOWN_MS]。真的解不出画面的片源，重建几次都是黑的，
 * 那种情形应当交给「换下一部」，而不是每 12 秒闪一下。
 */
private const val PICTURE_RECOVERY_LIMIT=com.hgtv.star.core.AppConfig.Playback.PICTURE_RECOVERY_LIMIT
private const val PICTURE_RECOVERY_COOLDOWN_MS=com.hgtv.star.core.AppConfig.Playback.PICTURE_RECOVERY_COOLDOWN_MS

fun TvMainActivity.recoverRecommendPicture(reason: String) {
    // 只在推荐页动手：正式播放页有自己的错误界面与「重试 / 换清晰度」，不该被静默重建。
    if(screen!="recommend" || !foreground || playError) return
    val data=detail ?: return
    if(episodeIndex !in data.episodes.indices) return
    val p=player ?: return
    val now=android.os.SystemClock.elapsedRealtime()
    if(pictureRecoveries>=PICTURE_RECOVERY_LIMIT || (pictureRecoveryAt!=0L && now-pictureRecoveryAt<PICTURE_RECOVERY_COOLDOWN_MS)) return
    pictureRecoveryAt=now; pictureRecoveries++
    val position=currentPosition()
    val autoplay=p.playWhenReady
    android.util.Log.w("HGTV","Recommend picture lost ($reason) episode=${episodeIndex+1} at=${position}ms try=$pictureRecoveries")
    // 下面这一下会把画面层整个换掉：先盖黑，别让旧画面按新比例被拉一下再消失。
    showRecommendCover()
    playEpisode(episodeIndex,position,autoplay,recovering=true)
}

/**
 * 新一集的**第一帧已经上屏**：把黑场淡出，露出画面。
 *
 * 由播放监听器的 `onRenderedFirstFrame` 调用（见 MainActivityPlayer），因此「淡出」与
 * 「真的能看见画面」是同一时刻，不会出现「黑场淡完还是黑的、再等半秒才出画面」。
 *
 * 行为与 1.2.4.2 一致：就是一次淡出，不做抓帧翻页。
 */
fun TvMainActivity.revealRecommendCover(onRevealed: (() -> Unit)? = null) {
    val cover=recommendCover
    if(cover==null) {
        playerView?.forceShutterHidden()
        onRevealed?.invoke()
        return
    }
    val revealToken=++recommendCoverToken
    if(BuildConfig.DEBUG) {
        android.util.Log.i(
            "HGTV-RATIO",
            "cover reveal start size=${cover.width}x${cover.height} "+
                "alpha=${cover.alpha} parent=${cover.parent!=null} children=${root.childCount}",
        )
    }
    cover.animate().cancel()
    if(!TvStyle.animate) {
        main.postDelayed({
            if(recommendCover===cover && revealToken==recommendCoverToken) {
                playerView?.forceShutterHidden()
                dismissRecommendCover()
                onRevealed?.invoke()
            }
        },COVER_MIN_HOLD_MS)
        return
    }
    // 连划（触屏）时淡出收短：一秒划两三部的时候，每一次都在「换源 0.2~0.6s + 淡出 0.26s」
    // 之后才重新看到画面，读起来就是「卡」。淡出只是收尾的柔和度，连划时短一点更跟手。
    // 电视不受影响（`touchArmed` 恒为 false，淡出仍是 260ms，与 1.2.4.2 逐帧一致）。
    val rapid=touchArmed && recommendLastSwitchAt!=0L &&
        android.os.SystemClock.elapsedRealtime()-recommendLastSwitchAt<900L
    val duration=if(rapid) COVER_OUT_FAST_MS else COVER_OUT_MS
    cover.animate().alpha(0f).setDuration(duration).withEndAction {
        if(recommendCover===cover && revealToken==recommendCoverToken) {
            playerView?.forceShutterHidden()
            dismissRecommendCover()
            onRevealed?.invoke()
        }
    }.start()
}

/**
 * 把过渡黑场挂到当前这棵树里：插在底部信息浮层**下面**。
 *
 * 因此过渡期间剧名仍然看得见，只是画面在黑场里换；浮层自身不受影响。
 */
private fun TvMainActivity.attachRecommendCoverView(cover: View) {
    (cover.parent as? android.view.ViewGroup)?.removeView(cover)
    // 黑场挂在 root 上（**不进画面之外那一层**）：那一层会跟着画面转 90°，
    // 黑场进去也会跟着转，转过去之后四个角会露出没盖住的画面。
    // 它是一整屏封面（无图时黑底），挂在 root 上盖住全屏就够了。
    //
    // 插在画面那一层**之前**：画面被黑场盖住，而压在画面上的剧名 / 进度 / 控制条
    // 仍然看得见（过渡期间用户还在看「换到第几部了」）。
    val layer=overlayLayer
    val index=if(layer!=null && layer.parent===root) root.indexOfChild(layer) else root.childCount
    root.addView(cover,index.coerceIn(0,root.childCount),FrameLayout.LayoutParams(-1,-1))
}

/**
 * 画面层被重建之后（`playEpisode` 整棵树换掉）把黑场补回来。
 *
 * 换剧走的是「复用播放器」那条路，通常不会重建；但换清晰度 / 换解码方式 / 出错重试会。
 * 少了这一步，过渡中途重建就会露出正在被拉伸的旧画面。
 */
fun TvMainActivity.reattachRecommendCover() {
    val cover=recommendCover ?: return
    if(cover.parent!=null) return
    attachRecommendCoverView(cover)
    cover.animate().cancel(); cover.alpha=1f
}

/** 立刻收掉过渡黑场（离开推荐页、切到整屏提示、重建画面时用）。 */
fun TvMainActivity.dismissRecommendCover() {
    recommendCoverToken++
    if(BuildConfig.DEBUG) {
        val cover=recommendCover
        android.util.Log.i(
            "HGTV-RATIO",
            "cover dismiss size=${cover?.width ?: 0}x${cover?.height ?: 0} "+
                "parent=${cover?.parent!=null} children=${root.childCount}",
        )
    }
    recommendCover?.let { cover ->
        cover.animate().cancel()
        (cover.parent as? android.view.ViewGroup)?.removeView(cover)
    }
    recommendCover=null
}
