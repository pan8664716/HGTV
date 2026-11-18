// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.shared.compat.setVectorIcon
import com.hgtv.star.shared.media.DanmakuView
import com.hgtv.star.shared.ui.LoadingDotsView

internal fun TvMainActivity.buildPlaybackSurface() {
    root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
    touchLayer=null; touchBar=null; touchPlayLabel=null; touchSeekHint=null; touchBoostHint=null
    touchScrubHint=null
    scrubbing=false; recommendScrubbing=false
    overlayLayer=null
    touchEntryTitle=null; touchEntryHint=null
    playbackBoost=false
    val rotatedSurface=library.autoFullscreen && autoFullscreenAvailable()
    val view=(if(rotatedSurface) layoutInflater.inflate(R.layout.player_surface_texture,root,false) as PlayerView else FixedFirstFramePlayerView(this)).apply {
        useController=false; resizeMode=frameResizeMode(); isFocusable=false
        setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
    }
    view.player=player
    playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
    bufferingDots=LoadingDotsView(this).apply { visibility=View.GONE }
    root.addView(bufferingDots,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
    val layer=FrameLayout(this).apply { clipChildren=false; clipToPadding=false }
    overlayLayer=layer
    root.addView(layer,FrameLayout.LayoutParams(-1,-1))
    applyVideoGeometry()
    val overlay=DanmakuView(this).apply {
        unitScale=designSize(1f)
        topGapDp=16f
        mode=library.danmakuMode
        textScale=library.danmakuScale
        textAlpha=library.danmakuAlpha
        speed=library.danmakuSpeed
        mergeRepeats=library.danmakuMerge
        mergeMinCount=library.danmakuMergeMin
        effectsEnabled=library.danmakuEffects
        setClock(0,false)
    }
    danmakuView=overlay
    layer.addView(overlay,FrameLayout.LayoutParams(-1,-1))
    val notice=text("",18f,TvStyle.playerText).apply {
        gravity=Gravity.CENTER_VERTICAL or Gravity.START
        maxLines=1
        maxWidth=dp(520)
        ellipsize=TextUtils.TruncateAt.END
        setPadding(dp(18),dp(10),dp(18),dp(10))
        background=TvStyle.gradient(0x99000000.toInt(),0xCC000000.toInt()).apply {
            cornerRadius=dp(8).toFloat()
        }
        visibility=View.GONE
    }
    nextSeasonNotice=notice
    layer.addView(
        notice,
        FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply {
            leftMargin=dp(24)
            bottomMargin=dp(24)
        }
    )
    val info=row().apply {
        gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(78),0,dp(78),0)
    }
    val left=row().apply { gravity=Gravity.CENTER_VERTICAL }
    playbackTitle=text("",24f,TvStyle.playerText).apply {
        maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD)
        setShadowLayer(5f,0f,2f,0x80000000.toInt())
        setPadding(0,0,dp(18),0)
    }
    left.addView(playbackTitle,lp(-2,-2))
    val episode=text("",22f,TvStyle.playerText).apply {
        maxLines=1
        setShadowLayer(5f,0f,2f,0x80000000.toInt())
    }
    playbackEpisode=episode
    left.addView(episode,lp(-2,-2))
    left.addOnLayoutChangeListener { view,_,_,viewRight,_,_,_,_,_ ->
        val available=(viewRight-view.left-view.paddingLeft-view.paddingRight).coerceAtLeast(0)
        val episodeWidth=if(episode.visibility==View.VISIBLE) episode.width else 0
        val limit=(available-episodeWidth).coerceAtLeast(0)
        if(playbackTitle.maxWidth!=limit) { playbackTitle.maxWidth=limit; playbackTitle.requestLayout() }
    }
    info.addView(left,lp(0,-2).apply { weight=1f })
    val right=row().apply { gravity=Gravity.CENTER_VERTICAL }
    val speed=text("",22f,TvStyle.playerText).apply {
        maxLines=1
        setShadowLayer(5f,0f,2f,0x80000000.toInt())
        setPadding(0,0,dp(18),0)
    }
    playbackSpeedText=speed
    right.addView(speed,lp(-2,-2))
    val clock=text("",24f,TvStyle.playerText).apply {
        maxLines=1
        setShadowLayer(5f,0f,2f,0x80000000.toInt())
    }
    playerClock=clock
    right.addView(clock,lp(-2,-2))
    info.addView(right,lp(-2,-2))
    // 72dp 时文字中心约在 36dp，比原来的 96dp 再上移约 12dp；行高仍容得下 24sp 标题。
    layer.addView(info,FrameLayout.LayoutParams(-1,dp(72),Gravity.TOP))
    playbackInfo=info
    info.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
        applyDanmakuTopProtection()
    }
    applyDanmakuTopProtection()

    hud=column().apply {
        setPadding(dp(78),dp(70),dp(78),0)
        background=TvStyle.panel()
    }
    val transport=row()
    transportIcon=ImageView(this).apply {
        setVectorIcon(R.drawable.ic_player_play)
        scaleType=ImageView.ScaleType.CENTER_INSIDE
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { togglePlayback() }
    }
    transport.addView(transportIcon,lp(dp(43),dp(43)))
    progressBar=SeekIndicator(this)
    transport.addView(progressBar,lp(0,dp(43)).apply { weight=1f; leftMargin=dp(13); rightMargin=dp(26) })
    playbackText=text("",18f,TvStyle.playerText).apply { setShadowLayer(4f,0f,1f,0x80000000.toInt()) }
    transport.addView(playbackText,lp(-2,-2))
    hud.addView(transport,lp(-1,dp(47)))
    val options=row().apply { setPadding(0,dp(20),0,0) }
    fun option(label: String,action: ()->Unit): TextView {
        val item=text(label,20f,TvStyle.playerDim).apply {
            gravity=Gravity.CENTER; maxLines=1
            setOnClickListener { action() }
            setOnFocusChangeListener { view,focused -> (view as TextView).setTextColor(if(focused) TvStyle.playerText else TvStyle.playerDim) }
        }
        options.addView(item,lp(-2,-2).apply { rightMargin=dp(34) })
        return item
    }
    qualityOption=option("清晰度 ${playbackMaxQuality}P") { showPlaybackMenu(MENU_ROW_QUALITY) }
    speedOption=option("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}") { showPlaybackMenu(MENU_ROW_SPEED) }
    frameOption=option("画面 ${library.frameMode.label}") { showPlaybackMenu(MENU_ROW_FRAME) }
    danmakuOption=option("弹幕 ${library.danmakuMode.optionLabel}") { showPlaybackMenu(MENU_ROW_DANMAKU) }
    sleepOption=option("定时 ${sleepTimer.label()}") { showPlaybackMenu(sleepRow(library.danmakuMode.showsStyle)) }
    hud.addView(options,lp(-1,-2))
    hudEpisodeArrow=ImageView(this).apply {
        setVectorIcon(R.drawable.ic_chevron_down)
        scaleType=ImageView.ScaleType.CENTER_INSIDE
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { showEpisodePanel(hud) }
    }
    hud.addView(
        hudEpisodeArrow,
        lp(dp(75),dp(38)).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=dp(8) }
    )
    controls=row().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
    layer.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipChildren=false; clipToPadding=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    // 播放器 surface 重建会清空 root；推荐页从“加载详情”切到播放器时，
    // 中心 loading 必须跟着补挂回来，否则会提前露出黑场。正式播放页不挂这个等待。
    if(screen=="recommend") reattachCenterLoading()
}
