// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

internal fun TvMainActivity.attachRecommendEntry() {
    val card=column().apply {
        setPadding(0,dp(18),0,dp(6))
        isClickable=true
        setOnClickListener { enterFormalPlayback() }
    }
    val head=row().apply { gravity=Gravity.CENTER_VERTICAL }
    val title=text("",34f,TvStyle.playerText).apply {
        setTypeface(typeface,Typeface.BOLD)
        maxLines=1
        ellipsize=android.text.TextUtils.TruncateAt.END
        setShadowLayer(6f,0f,2f,0x99000000.toInt())
    }
    head.addView(title,lp(0,-2).apply { weight=1f })
    head.addView(
        text("›",30f,TvStyle.playerText).apply {
            setPadding(dp(12),0,0,0)
            setShadowLayer(6f,0f,2f,0x99000000.toInt())
        },
        lp(-2,-2)
    )
    card.addView(head,lp(-1,-2))
    val hint=text("",16f,TvStyle.playerDim).apply {
        setPadding(0,dp(6),0,0)
        maxLines=1
        ellipsize=android.text.TextUtils.TruncateAt.END
        setShadowLayer(5f,0f,2f,0x99000000.toInt())
    }
    card.addView(hint,lp(-1,-2))
    hud.addView(card,lp(-1,-2))
    touchBar=card
    touchEntryTitle=title; touchEntryHint=hint
    syncRecommendEntry()
}

fun TvMainActivity.syncRecommendEntry() {
    val data=detail ?: return
    touchEntryTitle?.let { view ->
        val next=data.series.title
        if(view.text.toString()!=next) view.text=next
    }
    touchEntryHint?.let { view ->
        val next="点击进入正片"
        if(view.text.toString()!=next) view.text=next
    }
}

fun TvMainActivity.resetTouchBar() {
    touchBar?.let { bar -> (bar.parent as? ViewGroup)?.removeView(bar) }
    touchBar=null; touchPlayLabel=null
    touchEntryTitle=null; touchEntryHint=null
}

fun TvMainActivity.updateTouchPlayLabel() {
    val label=touchPlayLabel ?: return
    val (next,icon)=when {
        playError -> "重试" to R.drawable.ic_action_restore
        playbackReady && player?.playWhenReady==true -> "暂停" to R.drawable.ic_player_pause
        else -> "播放" to R.drawable.ic_player_play
    }
    if(label.text.toString()!=next) label.text=next
    label.setIcon(icon)
}

fun TvMainActivity.wireSeekTouch() {
    progressBar.onScrub={ fraction,finished -> onScrubProgress(fraction,finished) }
}

fun TvMainActivity.onScrubProgress(fraction: Float,finished: Boolean) {
    if(screen=="recommend") { onRecommendScrub(fraction,finished); return }
    val p=player ?: return
    val duration=p.duration
    if(duration<=0 || !playbackReady) return
    val target=(duration*fraction).toLong().coerceIn(0,(duration-500).coerceAtLeast(0))
    if(!finished) {
        scrubbing=true
        progressBar.progress=fraction
        playbackText.text="${formatSpan(target)} / ${formatSpan(duration)}"
        showHud()
        return
    }
    scrubbing=false
    seekTo(target)
}

fun TvMainActivity.focusUnderTouch(x: Float,y: Float) {
    val host=window?.decorView as? View ?: return
    focusUnderTouch(host,x,y)
}

fun focusUnderTouch(host: View,x: Float,y: Float) {
    val target=hitTestFocusable(host,x,y) ?: return
    if(!target.isFocusableInTouchMode || target.isFocused) return
    target.requestFocus()
}

private fun hitTestFocusable(view: View,x: Float,y: Float,fallback: View?=null): View? {
    if(view.visibility!=View.VISIBLE) return null
    val candidate=if(view.isFocusableInTouchMode || view.isClickable) view else fallback
    if(view is ViewGroup) {
        for(index in view.childCount-1 downTo 0) {
            val child=view.getChildAt(index)
            val cx=x+view.scrollX-child.left
            val cy=y+view.scrollY-child.top
            if(cx<0f || cy<0f || cx>=child.width || cy>=child.height) continue
            hitTestFocusable(child,cx,cy,candidate)?.let { return it }
        }
    }
    return candidate
}

class TouchFocusHost(context: android.content.Context) : FrameLayout(context) {
    var onTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN || event.actionMasked==MotionEvent.ACTION_MOVE) onTouch?.invoke()
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            focusUnderTouch(this,event.x,event.y)
        }
        return super.dispatchTouchEvent(event)
    }
}

fun touchFocusHost(content: View,fillHeight: Boolean=false,onTouch: (() -> Unit)?=null): View {
    val host=TouchFocusHost(content.context)
    host.onTouch=onTouch
    host.addView(content,FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        if(fillHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    return host
}
