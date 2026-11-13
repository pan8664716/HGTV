// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Space
import com.hgtv.star.core.HeatLabel
import com.hgtv.star.core.Series
import com.hgtv.star.tv.SeekIndicator
import com.hgtv.star.tv.TvStyle

/** 推荐页底部那块信息浮层：进度 + 剧名 + 操作说明。 */
fun TvMainActivity.attachRecommendOverlay(series: Series) {
    dismissRecommendOverlay()
    // **触屏设备不再建这块浮层**（需求 1）：推荐页改用播放界面那一条控制条
    //（顶部信息条 + 底部进度 / 状态项 / 六枚可点按钮，见 `attachTouchBar` / `showHud`），
    // 轻点画面唤出。浮层如果还留着，它就压在控制条上面，而且那排「看正片 / 暂停 / 换一部」
    // 与六键控制条功能重叠（前者已被用户点名去掉）。
    // 电视上 `touchArmed` 恒为 false，这条提前返回不会执行，浮层与今天逐行一致。
    if(touchArmed) return
    // 详情接口回来的 `series` **不带推荐流的运营角标**（爆剧 / 新剧 / 热度排名）：
    // 起播之后这块浮层是按 `detail.series` 重挂的（见 `playEpisode`），于是标签整排消失——
    // 用户读到的就是「第一次加载能看到、一开始播就没了」。这里用推荐流里同一部剧那一份
    // 把空字段补齐（详情自己有值就以详情为准）。
    val feed=recommendItems.getOrNull(recommendIndex)
    val info=if(feed!=null && feed.id==series.id) series.copy(
        label=series.label.ifEmpty { feed.label },
        labelBg=series.labelBg.ifEmpty { feed.labelBg },
        recommendRanking=series.recommendRanking.ifEmpty { feed.recommendRanking },
        recommendRankingBg=series.recommendRankingBg.ifEmpty { feed.recommendRankingBg },
        tags=series.tags.ifEmpty { feed.tags },
        // 热度同理：详情接口不给，只有推荐流那一份带着。
        heat=if(series.heat>0L) series.heat else feed.heat,
    ) else series
    val bar=column().apply {
        setPadding(dp(78),dp(28),dp(78),dp(56))
        background=TvStyle.gradient(Color.TRANSPARENT,0xCC000000.toInt())
        clipChildren=false
    }
    // 进度那一行：与正式播放的底部控制条同一套——`SeekIndicator` + 右侧 `00:04 / 01:08`，
    // 连左右内边距（右侧 26、时间不占权重）都一样，两页看上去是同一条。
    // 电视上这一行也在：这不是触屏专属的东西，遥控器快进同样要知道播到哪儿。
    // 与状态提示之间留一格：提示在上、进度在下，两行读起来是一组读数。
    val transport=row().apply { setPadding(0,dp(12),0,0) }
    val progress=SeekIndicator(this).apply {
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable=false
        progress=0f; bufferedProgress=0f
    }
    transport.addView(progress,lp(0,dp(43)).apply { weight=1f; rightMargin=dp(26) })
    // 与正式播放一样可拖：拖动期间只改显示，抬手才真跳（见 [onRecommendScrub]）。
    progress.onScrub={ fraction,finished -> onRecommendScrub(fraction,finished) }
    val span=text("",18f,TvStyle.playerText).apply { setShadowLayer(4f,0f,1f,0x80000000.toInt()) }
    transport.addView(span,lp(-2,-2))
    recommendProgress=progress; recommendSpan=span

    val titleLine=row()
    val label=info.label.trim()
    if(label.isNotEmpty()) {
        titleLine.addView(
            recommendChip(label,info.labelBg,16f),
            lp(-2,-2).apply { rightMargin=dp(12) },
        )
    }
    val title=text(series.title,32f,TvStyle.playerText).apply {
        setTypeface(typeface,Typeface.BOLD)
        maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
        setShadowLayer(6f,0f,2f,0x99000000.toInt())
    }
    titleLine.addView(title,lp(0,-2).apply { weight=1f })
    val details=row()
    val categoryTags=info.tags.trim()
    if(categoryTags.isNotEmpty()) {
        val category=text(categoryTags,17f,TvStyle.playerDim).apply {
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
            // **不给 weight**：给了它会撑满整行，把后面的热度 / 排名角标顶到最右边。
            // 类型标签按内容宽排，长到一定宽度就省略，角标紧跟在它后面。
            maxWidth=(resources.displayMetrics.widthPixels*0.55f).toInt()
            setShadowLayer(5f,0f,2f,0x99000000.toInt())
        }
        details.addView(category,lp(-2,-2))
    }
    // 热度：紧跟在剧集类型标签后面（`🔥9014万`）。**不套角标底板**——它和类型标签是同一行文字，
    // 加上背景反而把它读成一枚「可点的按钮」。接口没给数值时整枚收起。
    val heat=HeatLabel.badge(null,info.heat)
    var hasBefore=categoryTags.isNotEmpty()
    if(heat.isNotEmpty()) {
        val heatLabel=text(heat,17f,TvStyle.playerDim).apply {
            maxLines=1
            setShadowLayer(5f,0f,2f,0x99000000.toInt())
        }
        details.addView(heatLabel,lp(-2,-2).apply { if(hasBefore) leftMargin=dp(12) })
        hasBefore=true
    }
    val ranking=info.recommendRanking.trim()
    if(ranking.isNotEmpty()) {
        details.addView(
            recommendChip(ranking,info.recommendRankingBg,16f),
            lp(-2,-2).apply { if(hasBefore) leftMargin=dp(12) },
        )
    }
    // 尾巴留一段弹性空白：这一组（类型 / 热度 / 排名）因此从左边起排，
    // 而不是被撑到行尾贴右边。
    details.addView(Space(this),lp(0,1).apply { weight=1f })
    val hint=text("确定 追剧 · 上下 换剧 · 左右 快进退 · 菜单 播放设置 · 返回 主页面",16f,TvStyle.playerDim).apply {
        setPadding(0,dp(14),0,0)
        setShadowLayer(5f,0f,2f,0x99000000.toInt())
    }
    // 自上而下的阅读顺序：剧名 → 类型 / 热度 / 排名 → 播到哪儿 → 怎么操作。
    //
    // **这里不再有「状态行」**（原来那行会写「正在加载…」/「已暂停」/ 临时提示）：
    // 这块浮层贴底（`Gravity.BOTTOM`），多一行它整体就往上长一截，标题和标签跟着被顶上去；
    // 那一行时有时无，用户读到的就是「界面在跳」。等待一律放到**屏幕正中**的三点
    // （`showCenterLoading`，与进入第一屏那个加载同一个样式），不占浮层的布局位置。
    bar.addView(titleLine,lp(-1,-2))
    if(categoryTags.isNotEmpty() || heat.isNotEmpty() || ranking.isNotEmpty()) {
        bar.addView(details,lp(-1,-2).apply { topMargin=dp(6) })
    }
    bar.addView(transport,lp(-1,-2))
    bar.addView(hint,lp(-1,-2))
    recommendTitle=title; recommendOverlay=bar
    // 播放设置开着时浮层让位（菜单贴底占了下三分之一），收起菜单时再露出来。
    bar.visibility=if(playbackMenu!=null) View.GONE else View.VISIBLE
    // 挂进「画面之外那一层」：竖屏短剧全屏把画面转 90° 时，这块浮层要跟着一起转，
    // 否则剧名 / 进度 / 操作提示会横在转了过来的画面上。电视上不旋转时两者完全等价。
    (overlayLayer ?: root).addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    // 首帧就把内容填满：时间、进度都在这一刻算出来，不等下一次每秒 tick。
    // 上一版这里是空的——刚进推荐页时浮层比轻点唤出来的那一次「少一行字」，
    // 两条路看上去像两块不同的东西（用户反馈的就是这一条）。
    updateRecommendOverlay()
    // 换剧时从切换方向滑进来一点：上下键那一下读起来才是「换了一部」，而不是文字原地跳变。
    // 这个位移只服务「换剧」这一下，用完即清零：否则下一次进推荐页（不是换剧）
    // 也会莫名其妙地再滑一次，与轻点唤出来的那一块又不一样了。
    val shift=recommendShift
    recommendShift=0
    if(TvStyle.animate && shift!=0) {
        bar.translationY=dp(24).toFloat()*shift
        bar.alpha=0f
        bar.animate().translationY(0f).alpha(1f).setDuration(TvStyle.DURATION).start()
    }
}

/**
 * 推荐信息里的官方小标签。
 *
 * `bg` 是服务端原始颜色串（例如爆剧的
 * `#FF6868,#FF4C6DF7,#EFB662`），按从左到右渐变绘制；没有配色时用半透明深色兜底。
 */
private fun TvMainActivity.recommendChip(value: String,bg: String,size: Float): android.widget.TextView {
    val chip=text(value,size,Color.WHITE).apply {
        maxLines=1
        ellipsize=android.text.TextUtils.TruncateAt.END
        gravity=Gravity.CENTER
        setPadding(dp(10),dp(4),dp(10),dp(4))
        includeFontPadding=false
    }
    val colors=bg.split(',')
        .mapNotNull { runCatching { Color.parseColor(it.trim()) }.getOrNull() }
    chip.background=if(colors.isEmpty()) {
        GradientDrawable().apply {
            setColor(0xCC303030.toInt())
            cornerRadius=dp(6).toFloat()
        }
    } else if(colors.size==1) {
        GradientDrawable().apply {
            setColor(colors[0])
            cornerRadius=dp(6).toFloat()
        }
    } else {
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            colors.toIntArray(),
        ).apply {
            cornerRadius=dp(6).toFloat()
        }
    }
    return chip
}

fun TvMainActivity.dismissRecommendOverlay() {
    recommendOverlay?.let { overlay -> (overlay.parent as? android.view.ViewGroup)?.removeView(overlay) }
    recommendOverlay=null; recommendTitle=null
    // 进度条是浮层的子控件，随浮层一起消失；引用一并清掉，免得下一帧刷到已摘下的控件。
    recommendProgress=null; recommendSpan=null
}

/** 刷新浮层上的状态 / 进度那一行。 */
fun TvMainActivity.updateRecommendOverlay() {
    if(screen!="recommend" || detail==null) return
    val p=player
    // 触屏的推荐页没有浮层：它的读数（进度、时间、清晰度 / 倍速 / 画面 / 弹幕 / 定时）
    // 全在控制条上，走的是与播放界面同一段 `updatePlaybackText`。
    // 放在下面那些空安全的浮层写入之前：这一段是这一页**唯一**会刷新控制条的地方。
    //
    // 控制条收起来的时候不刷那一排读数：每秒一次、写的是看不见的控件，而这条函数正是
    // 每秒 tick 都会走到的地方（触屏上「刷着刷着就有点发涩」有一份来自这里）。
    // 露出来的时候 tick 已经先刷过一遍（见 `tick` 里的 `hud.visibility==VISIBLE`），
    // 轻点唤出那一下由 `showHud` 自己刷，因此这里跳过不会少一次刷新。
    if(touchArmed) {
        syncRecommendEntry()
        if(runCatching { hud.visibility==View.VISIBLE }.getOrDefault(false)) updatePlaybackText()
    }
    // 手指正在拖：这一秒的刷新不能把进度条与时间顶回播放器报的位置（同正式播放的 `scrubbing`）。
    if(recommendScrubbing) return
    // 进度与时间先刷：与正式播放同一套读数（拖动合并窗里显示目标位置）。
    val duration=p?.duration?.coerceAtLeast(0) ?: 0L
    val position=pendingSeek ?: p?.currentPosition ?: 0L
    recommendProgress?.progress=if(duration>0) position.toFloat()/duration else 0f
    recommendProgress?.bufferedProgress=if(duration>0) {
        (p?.bufferedPosition ?: 0L).coerceIn(0,duration).toFloat()/duration
    } else 0f
    recommendSpan?.let { line ->
        val text=if(duration>0) "${formatTime(position)} / ${formatTime(duration)}" else "--:-- / --:--"
        if(line.text.toString()!=text) line.text=text
    }
    // 出画面了就把「正在加载」的框收掉：它浮在屏幕正中，不占浮层的位置。
    if(playbackReady) hideCenterLoading()
}
