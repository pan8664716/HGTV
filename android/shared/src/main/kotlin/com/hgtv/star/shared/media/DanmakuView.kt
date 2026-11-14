// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.hgtv.star.core.Danmaku
import com.hgtv.star.core.DanmakuEmoji
import com.hgtv.star.core.DanmakuHighlight
import com.hgtv.star.core.DanmakuHighlightRound
import com.hgtv.star.core.DANMAKU_MERGE_MIN_RANGE
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.DanmakuPart
import com.hgtv.star.core.DanmakuRepeat
import com.hgtv.star.core.lruPut
import com.hgtv.star.core.DanmakuSpeed
import com.hgtv.star.core.planHighlights
import com.hgtv.star.core.repeatGroups
import com.hgtv.star.shared.compat.attachedToWindow
import com.hgtv.star.shared.compat.lowEndTv

class DanmakuView(context: Context) : View(context),DanmakuEmojiStore.Listener {
    private companion object {
        const val BASE_SCROLL_SECONDS=6f
        const val TOP_INSET_DP=104
        const val TOP_MODE_SHARE=0.25f
        const val TOP_MODE_INSET_DP=20
        const val BOTTOM_INSET_DP=120
        const val MAX_LINES_PER_FRAME=160
        const val LOW_END_MAX_LINES_PER_FRAME=64
        const val LOW_END_FRAME_INTERVAL_MS=33L
        const val LANE_GAP_TEXT=0.9f
        const val LANE_GAP_VERTICAL=0.35f
        const val MAX_DELAY_MS=4_000L
        const val HIGHLIGHT_SCALE=1.25f
        const val HIGHLIGHT_PAD_DP=13
        const val HIGHLIGHT_COUNTER_FULL_COUNT=40
        const val HIGHLIGHT_COUNTER_MAX_SCALE=1.4f
        const val HIGHLIGHT_COUNTER_WARM_COLOR=0xFFFFC857.toInt()
        const val HIGHLIGHT_COUNTER_HOT_COLOR=0xFFFF5722.toInt()
        const val HIGHLIGHT_COUNTER_PULSE_MIN_COUNT=20
        const val HIGHLIGHT_COUNTER_PULSE_DURATION_MS=420L
        const val HIGHLIGHT_COUNTER_PULSE_MAX_SCALE=1.6f
        const val EMOJI_SCALE=1.2f
        const val CACHE_LINES=4096
        val EMPTY_WIDTHS=FloatArray(0)
        const val SPRITE_AHEAD_MS=60_000L
        const val SPRITE_BEHIND_MS=8_000L
        const val SPRITE_REFRESH_MS=30_000L
    }

    private class Line(
        val start: Float,
        val duration: Float,
        val width: Float,
        val lane: Int,
        val parts: List<DanmakuPart>,
        val partWidths: FloatArray,
        val spriteText: String?
    )

    private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.WHITE
        typeface=Typeface.DEFAULT_BOLD
        isSubpixelText=true
    }
    private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xB3000000.toInt()
        style=Paint.Style.STROKE
        typeface=Typeface.DEFAULT_BOLD
        strokeJoin=Paint.Join.ROUND
        isSubpixelText=true
    }
    private val hlFill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.WHITE
        typeface=Typeface.DEFAULT_BOLD
        isSubpixelText=true
    }
    private val hlEdge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xCC000000.toInt()
        style=Paint.Style.STROKE
        typeface=Typeface.DEFAULT_BOLD
        strokeJoin=Paint.Join.ROUND
        isSubpixelText=true
    }
    private val counterFill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val counterEdge=Paint(Paint.ANTI_ALIAS_FLAG)
    private val plate=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0x99000000.toInt()
        style=Paint.Style.FILL
    }
    private val emojiPaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val spritePaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val spriteDst=RectF()
    private val emojiRect=RectF()
    private val plateRect=RectF()
    private val emoji=DanmakuEmojiStore.get(context)
    private val fillMetrics=Paint.FontMetrics()
    private val hlMetrics=Paint.FontMetrics()
    private val counterMetrics=Paint.FontMetrics()
    private val lowEnd=context.applicationContext.lowEndTv()
    // 一集约 150~300 条弹幕；1024 宽只能装下一部分，剩余每条每帧都要重做描边字形。
    // 多占 4MB 位图内存，把低配电视的常见窗口完整放进图集，换来的是持续播放时更低的 CPU。
    private val spriteAtlasWidth=2048
    private val spriteAtlasHeight=1024
    private val maxLinesPerFrame=if(lowEnd) LOW_END_MAX_LINES_PER_FRAME else MAX_LINES_PER_FRAME
    private val frameIntervalMs=if(lowEnd) LOW_END_FRAME_INTERVAL_MS else 0L
    private var spriteAtlas: DanmakuSpriteAtlas?=null
    private var nextSpriteRefreshAt=0L
    private var lastDrawAt=0L
    private var fillEmojiCenter=0f
    private var hlEmojiCenter=0f
    private val density: Float
        get() = if (unitScale > 0f) unitScale else resources.displayMetrics.density

    var unitScale: Float = 0f
    private val lines=mutableListOf<Line>()
    private var raw: List<Danmaku> = emptyList()
    private var groups: List<DanmakuRepeat> = emptyList()
    private var highlights: List<DanmakuHighlightRound> = emptyList()
    private val partsCache=LinkedHashMap<String,List<DanmakuPart>>(64,.75f,true)
    private val widthCache=LinkedHashMap<String,FloatArray>(64,.75f,true)
    private val prefetched=mutableSetOf<String>()
    private val hlKeys=Array(DanmakuHighlight.MAX_SLOTS){""}
    private val hlWidths=Array(DanmakuHighlight.MAX_SLOTS){FloatArray(0)}
    private val hlBodyWidths=FloatArray(DanmakuHighlight.MAX_SLOTS)
    private var cursor=0
    private var highlightCursor=0
    private var lastPosition=-1L
    private var laneCount=0
    private var laneHeight=0f
    private var laneTop=0f
    private var laneGap=0f
    private var baseTextSize=0f
    private var highlightSize=0f
    private val edgeAlpha=0xB3

    var mode: DanmakuMode=DanmakuMode.DEFAULT
        set(value) {
            if(field==value) return
            field=value
            relayout()
        }

    var textScale: Float=1f
        set(value) {
            if(field==value) return
            field=value
            applyTextMetrics()
            relayout()
        }

    var minTextSizeDp: Float=10f
        set(value) {
            val next=value.coerceIn(1f,72f)
            if(field==next) return
            field=next
            applyTextMetrics()
            relayout()
        }

    var sizeFollowsLongEdge: Boolean=false
        set(value) {
            if(field==value) return
            field=value
            if(width>0 && height>0) {
                baseTextSize=baseSizeFor(width,height)
                applyTextMetrics()
                relayout()
            }
        }

    var mergeRepeats: Boolean=true
        set(value) {
            if(field==value) return
            field=value
            relayout()
        }

    var mergeMinCount: Int=2
        set(value) {
            val next=value.coerceIn(DANMAKU_MERGE_MIN_RANGE)
            if(field==next) return
            field=next
            relayout()
        }

    var effectsEnabled: Boolean=true
        set(value) {
            if(field==value) return
            field=value
            invalidate()
        }

    var topInsetPx: Int=-1
        set(value) {
            if(field==value) return
            field=value
            relayout()
        }

    var textAlpha: Float=1f
        set(value) {
            if(field==value) return
            field=value
            applyTextMetrics()
            invalidate()
        }

    var speed: DanmakuSpeed=DanmakuSpeed.NORMAL
        set(value) {
            if(field==value) return
            field=value
            relayout()
        }

    private val highlightSlots: Int
        get() = when(mode) {
            DanmakuMode.SINGLE -> 1
            DanmakuMode.TOP -> 2
            DanmakuMode.FULL -> DanmakuHighlight.MAX_SLOTS
            DanmakuMode.HIDDEN -> 1
        }

    private var clockBaseMs=0L
    private var clockBaseAt=0L
    private var clockSpeed=1f
    private var clockRunning=false
    private var transitionHold=false
    private var transitionBaseMs=0L
    private var transitionBaseAt=0L
    private var transitionSpeed=1f
    private var transitionRunning=false
    private var pendingDanmaku: List<Danmaku>?=null
    private var transitionClockReady=false

    fun setClock(positionMs: Long,running: Boolean,speed: Float=clockSpeed) {
        clockBaseMs=positionMs.coerceAtLeast(0)
        clockBaseAt=SystemClock.elapsedRealtime()
        clockSpeed=speed.coerceIn(0.25f,4f)
        clockRunning=running
        if(transitionHold) {
            transitionClockReady=true
            pendingDanmaku?.let { items ->
                pendingDanmaku=null
                transitionHold=false
                transitionClockReady=false
                raw=items.sortedBy { it.offsetMs }
                relayout()
            }
        }
        if(visibility==VISIBLE && attachedToWindow) postInvalidateOnAnimation()
    }

    fun clockPositionMillis(): Long {
        if(transitionHold) {
            if(!transitionRunning) return transitionBaseMs
            val elapsed=(SystemClock.elapsedRealtime()-transitionBaseAt).coerceAtLeast(0L)
            return transitionBaseMs+(elapsed*transitionSpeed).toLong()
        }
        if(!clockRunning) return clockBaseMs
        val elapsed=(SystemClock.elapsedRealtime()-clockBaseAt).coerceAtLeast(0L)
        return clockBaseMs+(elapsed*clockSpeed).toLong()
    }

    fun pauseClock() {
        if(!clockRunning) return
        clockBaseMs=clockPositionMillis()
        clockBaseAt=SystemClock.elapsedRealtime()
        clockRunning=false
        if(visibility==VISIBLE && attachedToWindow) postInvalidateOnAnimation()
    }

    fun beginTransition() {
        if(transitionHold) return
        transitionBaseMs=clockPositionMillis()
        transitionBaseAt=SystemClock.elapsedRealtime()
        transitionSpeed=clockSpeed
        transitionRunning=clockRunning
        transitionHold=true
        transitionClockReady=false
        pendingDanmaku=null
        if(transitionRunning && visibility==VISIBLE && attachedToWindow) postInvalidateOnAnimation()
    }

    fun stopClock() {
        clockRunning=false
        clockBaseMs=0L
        clockBaseAt=SystemClock.elapsedRealtime()
    }
    init {
        isFocusable=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility=INVISIBLE
    }

    fun setDanmaku(items: List<Danmaku>) {
        if(transitionHold && !transitionClockReady) {
            pendingDanmaku=items
            return
        }
        transitionHold=false
        transitionClockReady=false
        pendingDanmaku=null
        raw=items.sortedBy { it.offsetMs }
        relayout()
    }

    fun clear() {
        stopClock()
        transitionHold=false
        transitionClockReady=false
        pendingDanmaku=null
        raw=emptyList()
        highlights=emptyList()
        highlightCursor=0
        partsCache.clear()
        widthCache.clear()
        prefetched.clear()
        spriteAtlas?.invalidate()
        nextSpriteRefreshAt=0L
        clearHighlightMetrics()
        relayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        emoji.addListener(this)
        ensureSpriteAtlas()
        nextSpriteRefreshAt=0L
        refreshSpriteAtlas(clockPositionMillis())
    }

    override fun onDetachedFromWindow() {
        emoji.removeListener(this)
        spriteAtlas?.close()
        spriteAtlas=null
        super.onDetachedFromWindow()
    }

    override fun onEmojiDecoded() {
        if(visibility==VISIBLE) postInvalidateOnAnimation()
    }

    private fun relayout() {
        lines.clear()
        cursor=0
        lastPosition=-1L
        groups=if(mergeRepeats) repeatGroups(raw,minCount=mergeMinCount) else raw.map { DanmakuRepeat(it.text,listOf(it.offsetMs)) }
        highlights=if(mode==DanmakuMode.HIDDEN) emptyList()
        else planHighlights(
            groups,
            highlights,
            highlightSlots,
            mergeMinCount,
            clockPositionMillis(),
        )
        highlightCursor=0
        layoutLanes()
        val lanes=if(mode==DanmakuMode.HIDDEN) 0 else laneCount
        if(lanes>0 && width>0 && height>0) schedule(lanes)
        nextSpriteRefreshAt=0L
        refreshSpriteAtlas(clockPositionMillis())
        val fresh=mutableListOf<String>()
        groups.forEach { if(prefetched.add(it.text)) fresh+=it.text }
        if(fresh.isNotEmpty()) emoji.prefetch(fresh)
        visibility=if(mode==DanmakuMode.HIDDEN || raw.isEmpty()) INVISIBLE else VISIBLE
        invalidate()
    }

    private fun schedule(lanes: Int) {
        val viewWidth=width.toFloat()
        val pixelsPerSecond=speedPx(viewWidth/BASE_SCROLL_SECONDS)
        val laneFree=FloatArray(lanes)
        val planned=highlights.map { it.group.key }.toSet()
        val emojiSize=fill.textSize*EMOJI_SCALE
        groups.filterNot { it.key in planned }.forEach { group ->
            val parts=partsOf(group.label)
            val widths=widthsOf(group.label,parts,emojiSize)
            var width=0f
            widths.forEach { width+=it }
            val want=group.firstMs/1000f
            val duration=(viewWidth+width)/pixelsPerSecond
            var lane=0
            var start=Float.MAX_VALUE
            for(index in 0 until lanes) {
                val candidate=maxOf(laneFree[index],want)
                if(candidate<start) { start=candidate; lane=index }
            }
            if((start-want)*1000f>MAX_DELAY_MS) return@forEach
            val startMs=(start*1000f).toLong()
            val endMs=((start+duration)*1000f).toLong()
            if(mode==DanmakuMode.SINGLE &&
                highlights.any { it.startMs<endMs && it.endMs>startMs }) return@forEach
            laneFree[lane]=start+(width+laneGap)/pixelsPerSecond
            val spriteText=parts.singleOrNull()?.takeIf { it.asset==null }?.text
            lines+=Line(start,duration,width,lane,parts,widths,spriteText)
        }
        lines.sortBy { it.start }
    }

    private fun speedPx(pixelsPerSecond: Float)=pixelsPerSecond*speed.factor

    private fun partsOf(text: String): List<DanmakuPart> =
        partsCache[text] ?: DanmakuEmoji.parts(text).also { partsCache.lruPut(CACHE_LINES,text,it) }

    private fun widthsOf(label: String,parts: List<DanmakuPart>,emojiSize: Float): FloatArray =
        widthCache[label] ?: run {
            FloatArray(parts.size) { index ->
                val part=parts[index]
                if(part.asset==null) fill.measureText(part.text) else emojiSize
            }.also { widthCache.lruPut(CACHE_LINES,label,it) }
        }

    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        baseTextSize=baseSizeFor(w,h)
        applyTextMetrics()
        relayout()
    }

    private fun baseSizeFor(w: Int,h: Int): Float {
        val basis = if(sizeFollowsLongEdge) maxOf(w,h) else h
        return (basis*0.034f).coerceIn(density*18f,density*34f)
    }

    private fun applyTextMetrics() {
        val size=(baseTextSize*textScale).coerceIn(density*minTextSizeDp,density*72f)
        val alpha=textAlpha.coerceIn(0f,1f)
        fill.textSize=size
        edge.textSize=size
        edge.strokeWidth=size*0.14f
        fill.getFontMetrics(fillMetrics)
        fillEmojiCenter=(fillMetrics.ascent+fillMetrics.descent)/2f-size*EMOJI_SCALE/2f
        highlightSize=(size*HIGHLIGHT_SCALE).coerceAtMost(density*72f)
        hlFill.textSize=highlightSize
        hlEdge.textSize=highlightSize
        hlEdge.strokeWidth=highlightSize*0.16f
        hlFill.getFontMetrics(hlMetrics)
        hlEmojiCenter=(hlMetrics.ascent+hlMetrics.descent)/2f-highlightSize*EMOJI_SCALE/2f
        fill.alpha=(255*alpha).toInt().coerceIn(0,255)
        edge.alpha=(edgeAlpha*alpha).toInt().coerceIn(0,255)
        hlFill.alpha=(255*alpha).toInt().coerceIn(0,255)
        hlEdge.alpha=(0xCC*alpha).toInt().coerceIn(0,255)
        emojiPaint.alpha=(255*alpha).toInt().coerceIn(0,255)
        plate.alpha=(0x99*alpha).toInt().coerceIn(0,255)
        spritePaint.alpha=fill.alpha
        spriteAtlas?.setStyle(size,edge.strokeWidth)
        widthCache.clear()
        clearHighlightMetrics()
    }

    private fun clearHighlightMetrics() {
        hlKeys.fill("")
        hlWidths.fill(EMPTY_WIDTHS)
        hlBodyWidths.fill(0f)
    }

    private fun layoutLanes() {
        val textBox=(fillMetrics.descent-fillMetrics.ascent)+fill.strokeWidth
        val emojiBox=fill.textSize*EMOJI_SCALE
        laneGap=fill.textSize*LANE_GAP_TEXT
        laneHeight=maxOf(textBox,emojiBox)+fill.textSize*LANE_GAP_VERTICAL
        if(mode==DanmakuMode.HIDDEN || laneHeight<=0f) { laneCount=0; return }
        val usable=when(mode) {
            DanmakuMode.SINGLE -> {
                laneTop=maxOf(
                    if(topInsetPx>=0) topInsetPx.toFloat() else TOP_MODE_INSET_DP*density,
                    highlightSize*0.6f+HIGHLIGHT_PAD_DP*density*0.5f)
                laneHeight
            }
            DanmakuMode.TOP -> {
                laneTop=if(topInsetPx>=0) topInsetPx.toFloat() else TOP_MODE_INSET_DP*density
                (height*TOP_MODE_SHARE-laneTop).coerceAtLeast(0f)
            }
            else -> {
                laneTop=if(topInsetPx>=0) topInsetPx.toFloat() else TOP_INSET_DP*density
                (height-BOTTOM_INSET_DP*density).coerceAtLeast(laneTop+1f)-laneTop
            }
        }
        laneCount=(usable/laneHeight).toInt().coerceIn(0,24)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(mode==DanmakuMode.HIDDEN || laneCount<=0) return
        if(lines.isEmpty() && highlights.isEmpty()) return
        lastDrawAt=SystemClock.uptimeMillis()
        val viewWidth=width.toFloat()
        val now=clockPositionMillis()
        val seconds=now/1000f
        if(now<lastPosition) {
            cursor=0
            highlightCursor=0
            nextSpriteRefreshAt=0L
        }
        lastPosition=now
        if(now>=nextSpriteRefreshAt) refreshSpriteAtlas(now)
        while(cursor<lines.size && seconds-lines[cursor].start>lines[cursor].duration) cursor++
        var drawn=0
        var index=cursor
        while(index<lines.size && drawn<maxLinesPerFrame) {
            val line=lines[index]
            val elapsed=seconds-line.start
            if(elapsed<0f) break
            if(elapsed<=line.duration) {
                val x=viewWidth-elapsed/line.duration*(viewWidth+line.width)
                val baseline=laneTop+(line.lane+1)*laneHeight-fill.textSize*0.28f
                val atlas=spriteAtlas
                val sprite=line.spriteText?.let { atlas?.get(it) }
                if(sprite==null) {
                    drawParts(
                        canvas,
                        line.parts,
                        line.partWidths,
                        x,
                        baseline,
                        fill,
                        edge,
                        fill.textSize*EMOJI_SCALE,
                        fillEmojiCenter,
                    )
                } else {
                    atlas?.draw(canvas,sprite,x,baseline,spritePaint,spriteDst)
                }
                drawn++
            }
            index++
        }
        drawHighlights(canvas,now)
        scheduleNextFrame()
    }

    /**
     * 低配电视的弹幕只按 30fps 重绘。
     *
     * 这台 2GB 盒子实测弹幕开启时进程长期吃 140%–290% CPU，关闭弹幕只占约 12%；
     * simpleperf 的热点集中在 RenderThread 的字体轮廓和描边路径生成。弹幕位置由系统时钟
     * 外推，不需要跟播放器逐帧同步，因此把整屏重绘降到 30fps 不会改变速度或时间轴。
     * 高端设备继续用 Choreographer 的 60fps。
     */
    private fun scheduleNextFrame() {
        if(!clockRunning || visibility!=VISIBLE || !attachedToWindow) return
        if(frameIntervalMs<=0L) {
            postInvalidateOnAnimation()
            return
        }
        val elapsed=SystemClock.uptimeMillis()-lastDrawAt
        postInvalidateDelayed((frameIntervalMs-elapsed).coerceAtLeast(1L))
    }

    private fun drawHighlights(canvas: Canvas,nowMs: Long) {
        if(mode==DanmakuMode.HIDDEN || highlights.isEmpty()) return
        while(highlightCursor<highlights.size && highlights[highlightCursor].endMs<=nowMs) highlightCursor++
        var index=highlightCursor
        var scanned=0
        while(index<highlights.size && scanned<32) {
            val round=highlights[index]
            if(round.startMs>nowMs) break
            if(nowMs<round.endMs) drawHighlightRound(canvas,round,nowMs)
            index++
            scanned++
        }
    }

    private fun drawHighlightRound(canvas: Canvas,round: DanmakuHighlightRound,nowMs: Long) {
        val slot=round.slot.coerceIn(0,DanmakuHighlight.MAX_SLOTS-1)
        val parts=partsOf(round.group.text)
        val count=round.group.countUpTo(nowMs).coerceAtLeast(DanmakuHighlight.MIN_COUNT)
        val counter=" ×$count"
        val heat=if(effectsEnabled) {
            ((count-DanmakuHighlight.MIN_COUNT).toFloat() /
                (HIGHLIGHT_COUNTER_FULL_COUNT-DanmakuHighlight.MIN_COUNT)).coerceIn(0f,1f)
        } else 0f
        val counterScale=if(effectsEnabled) {
            1f+heat*(HIGHLIGHT_COUNTER_MAX_SCALE-1f)
        } else 1f
        counterFill.set(hlFill)
        val counterAlpha=counterFill.alpha
        counterFill.color=when {
            !effectsEnabled -> Color.WHITE
            heat<=0.5f -> blendColor(Color.WHITE,HIGHLIGHT_COUNTER_WARM_COLOR,heat*2f)
            else -> blendColor(HIGHLIGHT_COUNTER_WARM_COLOR,HIGHLIGHT_COUNTER_HOT_COLOR,(heat-0.5f)*2f)
        }
        counterFill.alpha=counterAlpha
        counterFill.textSize=highlightSize*counterScale
        counterEdge.set(hlEdge)
        counterEdge.textSize=counterFill.textSize
        counterEdge.strokeWidth=counterFill.textSize*0.16f
        val emojiSize=highlightSize*EMOJI_SCALE
        val widths=highlightWidths(round,parts,emojiSize,slot)
        val bodyWidth=hlBodyWidths[slot]
        val counterWidth=counterFill.measureText(counter)
        val total=bodyWidth+counterWidth
        val centerY=highlightCenterY(slot)
        val left=((width-total)/2f).coerceAtLeast(density*8f)
        val padX=HIGHLIGHT_PAD_DP*density
        val padY=HIGHLIGHT_PAD_DP*density*0.5f
        counterFill.getFontMetrics(counterMetrics)
        val counterHeight=counterMetrics.descent-counterMetrics.ascent
        val box=maxOf(highlightSize*0.95f,counterHeight)
        plateRect.set(left-padX,centerY-box/2f-padY,left+total+padX,centerY+box/2f+padY)
        canvas.drawRoundRect(plateRect,density*10f,density*10f,plate)
        val lastOffset=round.group.offsets[count-1]
        val pulseElapsed=nowMs-lastOffset
        val pulsing=effectsEnabled && count>=HIGHLIGHT_COUNTER_PULSE_MIN_COUNT &&
            pulseElapsed in 0 until HIGHLIGHT_COUNTER_PULSE_DURATION_MS
        val pulseProgress=if(pulsing) {
            pulseElapsed.toFloat()/HIGHLIGHT_COUNTER_PULSE_DURATION_MS
        } else 1f
        val pulseScale=if(pulsing) {
            val attack=0.18f
            val shape=if(pulseProgress<attack) {
                pulseProgress/attack
            } else {
                (1f-pulseProgress)/(1f-attack)
            }.coerceIn(0f,1f)
            val eased=shape*shape*(3f-2f*shape)
            1f+(HIGHLIGHT_COUNTER_PULSE_MAX_SCALE-1f)*eased
        } else 1f
        val baseline=centerY-(hlMetrics.ascent+hlMetrics.descent)/2f
        drawParts(canvas,parts,widths,left,baseline,hlFill,hlEdge,emojiSize,hlEmojiCenter)
        val counterBaseline=centerY-(counterMetrics.ascent+counterMetrics.descent)/2f
        val counterLeft=left+bodyWidth
        val counterCenterX=counterLeft+counterWidth/2f
        canvas.save()
        canvas.scale(pulseScale,pulseScale,counterCenterX,centerY)
        canvas.drawText(counter,counterLeft,counterBaseline,counterEdge)
        canvas.drawText(counter,counterLeft,counterBaseline,counterFill)
        canvas.restore()
    }

    private fun blendColor(from: Int,to: Int,progress: Float): Int {
        val value=progress.coerceIn(0f,1f)
        fun channel(shift: Int): Int {
            val start=(from ushr shift) and 0xFF
            val end=(to ushr shift) and 0xFF
            return Math.round(start+(end-start)*value)
        }
        return Color.rgb(channel(16),channel(8),channel(0))
    }

    private fun highlightCenterY(slot: Int): Float {
        val anchor=when(mode) {
            DanmakuMode.SINGLE -> laneTop+laneHeight/2f
            DanmakuMode.TOP -> laneTop+(height*TOP_MODE_SHARE-laneTop)/2f
            else -> height/2f
        }
        val lanes=highlightSlots
        if(lanes<=1) return anchor
        val row=highlightRowHeight()
        return anchor+(slot-(lanes-1)/2f)*row
    }

    private fun highlightRowHeight(): Float=highlightSize*0.95f+HIGHLIGHT_PAD_DP*density+density*6f

    private fun highlightWidths(round: DanmakuHighlightRound,parts: List<DanmakuPart>,emojiSize: Float,slot: Int): FloatArray {
        val key="${round.group.key}@$highlightSize@$emojiSize"
        if(key==hlKeys[slot]) return hlWidths[slot]
        val widths=FloatArray(parts.size)
        var total=0f
        parts.forEachIndexed { index,part ->
            val width=if(part.asset==null) hlFill.measureText(part.text) else emojiSize
            widths[index]=width; total+=width
        }
        hlKeys[slot]=key; hlWidths[slot]=widths; hlBodyWidths[slot]=total
        return widths
    }

    private fun drawParts(
        canvas: Canvas,
        parts: List<DanmakuPart>,
        widths: FloatArray,
        x: Float,
        baseline: Float,
        textPaint: Paint,
        strokePaint: Paint,
        emojiSize: Float,
        emojiCenter: Float,
    ) {
        val emojiTop=baseline+emojiCenter
        var cursorX=x
        parts.forEachIndexed { index,part ->
            val asset=part.asset
            if(asset==null) {
                canvas.drawText(part.text,cursorX,baseline,strokePaint)
                canvas.drawText(part.text,cursorX,baseline,textPaint)
            } else {
                val image: Bitmap?=emoji.bitmap(asset)
                if(image==null) {
                    canvas.drawText(part.text,cursorX,baseline,strokePaint)
                    canvas.drawText(part.text,cursorX,baseline,textPaint)
                } else {
                    emojiRect.set(cursorX,emojiTop,cursorX+emojiSize,emojiTop+emojiSize)
                    canvas.drawBitmap(image,null,emojiRect,emojiPaint)
                }
            }
            cursorX+=widths[index]
        }
    }

    private fun ensureSpriteAtlas(): DanmakuSpriteAtlas? {
        spriteAtlas?.let { return it }
        if(!attachedToWindow) return null
        return DanmakuSpriteAtlas(
            spriteAtlasWidth,
            spriteAtlasHeight,
            onReady = { if(visibility==VISIBLE) postInvalidateOnAnimation() },
        ).also {
            spriteAtlas=it
            it.setStyle(fill.textSize,edge.strokeWidth)
        }
    }

    private fun refreshSpriteAtlas(nowMs: Long) {
        if(lines.isEmpty() || width<=0 || height<=0) return
        val wanted=LinkedHashSet<String>()
        val until=nowMs+SPRITE_AHEAD_MS
        val since=nowMs-SPRITE_BEHIND_MS
        var index=cursor
        while(index<lines.size) {
            val line=lines[index]
            val start=(line.start*1000f).toLong()
            if(start>until) break
            val end=((line.start+line.duration)*1000f).toLong()
            if(end>=since) line.spriteText?.let { wanted+=it }
            index++
        }
        nextSpriteRefreshAt=nowMs+SPRITE_REFRESH_MS
        if(wanted.isNotEmpty()) ensureSpriteAtlas()?.request(wanted)
    }

    fun resetCursor() {
        cursor=0
        highlightCursor=0
        lastPosition=-1L
        nextSpriteRefreshAt=0L
    }
}
