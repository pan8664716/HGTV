// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import kotlin.math.roundToInt

class DanmakuView(context: Context) : SurfaceView(context),DanmakuEmojiStore.Listener,SurfaceHolder.Callback {
    private companion object {
        const val BASE_SCROLL_SECONDS=6f
        const val TOP_INSET_DP=104
        const val BOTTOM_INSET_DP=120
        const val LANE_GAP_TEXT=0.9f
        const val LANE_GAP_VERTICAL=0.35f
        const val LANE_MIN_GAP_DP=12
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
        const val SCHEDULE_AHEAD_MS=30_000L
        const val LINE_RETENTION_MS=1_000L
        const val REPLACE_ACTIVE_GRACE_MS=500L
        const val SEEK_BACK_TOLERANCE_MS=250L
        const val LINE_POOL_LIMIT=256
        const val LOW_END_SURFACE_MAX_WIDTH=1280
        const val LOW_END_SURFACE_MAX_HEIGHT=720
    }

    private class Line {
        var startMs=0L
        var durationMs=0L
        var width=0f
        var lane=0
        var parts: List<DanmakuPart> = emptyList()
        var partWidths: FloatArray = EMPTY_WIDTHS
        var spriteText: String?=null
        var entered=false
        var skipped=false

        val endMs get()=startMs+durationMs

        fun bind(
            startMs: Long,
            durationMs: Long,
            width: Float,
            lane: Int,
            parts: List<DanmakuPart>,
            partWidths: FloatArray,
            spriteText: String?,
        ) {
            this.startMs=startMs
            this.durationMs=durationMs
            this.width=width
            this.lane=lane
            this.parts=parts
            this.partWidths=partWidths
            this.spriteText=spriteText
            entered=false
            skipped=false
        }

        fun clear() {
            startMs=0L
            durationMs=0L
            width=0f
            lane=0
            parts=emptyList()
            partWidths=EMPTY_WIDTHS
            spriteText=null
            entered=false
            skipped=false
        }
    }

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
    private var spriteAtlas: DanmakuSpriteAtlas?=null
    private var nextSpriteRefreshAt=0L
    private var fillEmojiCenter=0f
    private var hlEmojiCenter=0f
    private val density: Float
        get() = if (unitScale > 0f) unitScale else resources.displayMetrics.density

    var unitScale: Float = 0f
    // 参照 ByteDance DanmakuRenderEngine 的分层与缓冲思路：
    // 滚动层只保留播放点附近的数据，高亮层独立排程；行对象循环复用，避免重排时整集分配。
    private val lines=mutableListOf<Line>()
    private val linePool=java.util.ArrayDeque<Line>(64)
    private var raw: List<Danmaku> = emptyList()
    private var groups: List<DanmakuRepeat> = emptyList()
    private var highlights: List<DanmakuHighlightRound> = emptyList()
    private var highlightedKeys: Set<String> = emptySet()
    private val partsCache=LinkedHashMap<String,List<DanmakuPart>>(64,.75f,true)
    private val widthCache=LinkedHashMap<String,FloatArray>(64,.75f,true)
    private val prefetched=mutableSetOf<String>()
    private val hlKeys=Array(DanmakuHighlight.MAX_SLOTS){""}
    private val hlWidths=Array(DanmakuHighlight.MAX_SLOTS){FloatArray(0)}
    private val hlBodyWidths=FloatArray(DanmakuHighlight.MAX_SLOTS)
    private var highlightCursor=0
    private var lastPosition=-1L
    private var lastSchedulePositionMs=-1L
    private var nextGroupIndex=0
    private val scheduledGroupKeys=mutableSetOf<String>()
    private var laneFreeMs=LongArray(0)
    private var laneEntryFreeMs=LongArray(0)
    private var backlogCutoffMs=Long.MIN_VALUE
    private var scheduleDirty=true
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
            requestFrame()
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
            requestFrame()
        }

    var speed: DanmakuSpeed=DanmakuSpeed.NORMAL
        set(value) {
            if(field==value) return
            field=value
            relayout()
        }

    private val highlightSlots: Int
        get() = mode.lineLimit.coerceIn(1,DanmakuHighlight.MAX_SLOTS)

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
    private var surfaceReady=false
    private var frameScheduled=false
    private var fixedSurfaceWidth=0
    private var fixedSurfaceHeight=0
    private val choreographer=Choreographer.getInstance()
    private val frameCallback=object: Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled=false
            if(!surfaceReady || visibility!=VISIBLE || !attachedToWindow) return
            drawSurfaceFrame()
        }
    }

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
                replaceDanmaku(items)
            }
        }
        requestFrame()
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
        requestFrame()
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
        requestFrame()
    }

    fun stopClock() {
        clockRunning=false
        clockBaseMs=0L
        clockBaseAt=SystemClock.elapsedRealtime()
    }
    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
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
        replaceDanmaku(items)
    }

    /**
     * 追加后台线程陆续取到的弹幕。
     *
     * 只更新数据缓冲并排入当前播放点之后的新内容，不清空 [lines]，也不重排已经上屏的弹幕。
     */
    fun appendDanmaku(items: List<Danmaku>) {
        if(items.isEmpty()) return
        val merged=LinkedHashMap<String,Danmaku>()
        raw.forEach { merged["${it.offsetMs}:${it.text}"]=it }
        items.forEach { merged["${it.offsetMs}:${it.text}"]=it }
        val nextRaw=merged.values.sortedBy { it.offsetMs }
        if(nextRaw.size==raw.size) return
        raw=nextRaw
        groups=if(mergeRepeats) repeatGroups(raw,minCount=mergeMinCount) else raw.map { DanmakuRepeat(it.text,listOf(it.offsetMs)) }
        val fresh=mutableListOf<String>()
        groups.forEach { if(prefetched.add(it.text)) fresh+=it.text }
        if(fresh.isNotEmpty()) emoji.prefetch(fresh)
        if(mode==DanmakuMode.HIDDEN) {
            visibility=INVISIBLE
            return
        }
        val nowMs=clockPositionMillis()
        backlogCutoffMs=nowMs-REPLACE_ACTIVE_GRACE_MS
        nextGroupIndex=groups.indexOfFirst { it.firstMs>=backlogCutoffMs }.let { if(it<0) groups.size else it }
        scheduleGroupsThrough(nowMs+SCHEDULE_AHEAD_MS,nowMs)
        backlogCutoffMs=Long.MIN_VALUE
        visibility=VISIBLE
        requestFrame()
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
        requestFrame()
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled=false
        surfaceReady=false
        emoji.removeListener(this)
        spriteAtlas?.close()
        spriteAtlas=null
        super.onDetachedFromWindow()
    }

    override fun onEmojiDecoded() {
        requestFrame()
    }

    private fun replaceDanmaku(items: List<Danmaku>) {
        val replacingExistingData=raw.isNotEmpty()
        raw=items.sortedBy { it.offsetMs }
        relayout(suppressBacklog=replacingExistingData)
    }

    private fun relayout(suppressBacklog: Boolean=false) {
        clearScrollLayer()
        nextGroupIndex=0
        scheduledGroupKeys.clear()
        laneFreeMs=LongArray(0)
        lastSchedulePositionMs=-1L
        scheduleDirty=true
        lastPosition=-1L
        val nowMs=clockPositionMillis()
        backlogCutoffMs=if(suppressBacklog) nowMs-REPLACE_ACTIVE_GRACE_MS else Long.MIN_VALUE
        groups=if(mergeRepeats) repeatGroups(raw,minCount=mergeMinCount) else raw.map { DanmakuRepeat(it.text,listOf(it.offsetMs)) }
        highlights=if(mode==DanmakuMode.HIDDEN) emptyList()
        else planHighlights(
            groups,
            highlights,
            highlightSlots,
            mergeMinCount,
            nowMs,
        )
        highlightedKeys=highlights.mapTo(HashSet()) { it.group.key }
        highlightCursor=0
        layoutLanes()
        laneEntryFreeMs=LongArray(laneCount)
        val lanes=if(mode==DanmakuMode.HIDDEN) 0 else laneCount
        if(lanes>0 && width>0 && height>0) scheduleScrollWindow(nowMs)
        nextSpriteRefreshAt=0L
        refreshSpriteAtlas(nowMs)
        val fresh=mutableListOf<String>()
        groups.forEach { if(prefetched.add(it.text)) fresh+=it.text }
        if(fresh.isNotEmpty()) emoji.prefetch(fresh)
        visibility=if(mode==DanmakuMode.HIDDEN || raw.isEmpty()) INVISIBLE else VISIBLE
        requestFrame()
    }

    private fun clearScrollLayer() {
        lines.forEach { recycleLine(it) }
        lines.clear()
    }

    private fun obtainLine(): Line =
        if(linePool.isEmpty()) Line() else linePool.removeLast()

    private fun recycleLine(line: Line) {
        line.clear()
        if(linePool.size<LINE_POOL_LIMIT) linePool.addLast(line)
    }

    /**
     * 缓冲只保留当前播放点之后 30 秒的滚动弹幕；已经结束的行立即回收。
     *
     * 正常播放时只增量排入新进入窗口的数据，补页不会清空 [lines]；跳转时才重建
     * 当前窗口内的 [Line]，不会重新持有整集对象。
     */
    private fun scheduleScrollWindow(nowMs: Long) {
        if(laneCount<=0) return
        if(scheduleDirty || nowMs+SEEK_BACK_TOLERANCE_MS<lastSchedulePositionMs) {
            rebaseScrollSchedule(nowMs)
            return
        }
        releaseExpiredLines(nowMs)
        scheduleGroupsThrough(nowMs+SCHEDULE_AHEAD_MS,nowMs)
        lastSchedulePositionMs=nowMs
    }

    private fun rebaseScrollSchedule(nowMs: Long) {
        clearScrollLayer()
        nextGroupIndex=0
        scheduledGroupKeys.clear()
        laneFreeMs=LongArray(laneCount)
        laneEntryFreeMs=LongArray(laneCount)
        scheduleDirty=false
        lastSchedulePositionMs=nowMs
        scheduleGroupsThrough(nowMs+SCHEDULE_AHEAD_MS,nowMs)
        backlogCutoffMs=Long.MIN_VALUE
    }

    private fun releaseExpiredLines(nowMs: Long) {
        val cutoff=nowMs-LINE_RETENTION_MS
        for(index in lines.lastIndex downTo 0) {
            if(lines[index].endMs<cutoff) recycleLine(lines.removeAt(index))
        }
    }

    private fun scheduleGroupsThrough(
        untilMs: Long,
        nowMs: Long,
        notBeforeMs: Long=nowMs,
    ) {
        if(nextGroupIndex>=groups.size || groups[nextGroupIndex].firstMs>untilMs) return
        val viewWidth=width.toFloat()
        val pixelsPerSecond=speedPx(viewWidth/BASE_SCROLL_SECONDS)
        val emojiSize=fill.textSize*EMOJI_SCALE
        while(nextGroupIndex<groups.size && groups[nextGroupIndex].firstMs<=untilMs) {
            val group=groups[nextGroupIndex++]
            if(!scheduledGroupKeys.add(group.key)) continue
            if(group.key in highlightedKeys) continue
            val parts=partsOf(group.label)
            val widths=widthsOf(group.label,parts,emojiSize)
            var width=0f
            widths.forEach { width+=it }
            val durationMs=(((viewWidth+width)/pixelsPerSecond)*1000f).toLong().coerceAtLeast(1L)
            var lane=0
            var startMs=Long.MAX_VALUE
            for(index in laneFreeMs.indices) {
                // 新排入的行必须从当前帧开始向右移动；若补页回包晚于弹幕时间，
                // 不能把 startMs 回填到过去，否则第一帧会直接出现在屏幕中间。
                val candidate=maxOf(laneFreeMs[index],group.firstMs,notBeforeMs)
                if(candidate<startMs) { startMs=candidate; lane=index }
            }
            if(startMs-group.firstMs>MAX_DELAY_MS) continue
            val endMs=startMs+durationMs
            laneFreeMs[lane]=startMs+(((width+laneGap)/pixelsPerSecond)*1000f).toLong()
            if(startMs<backlogCutoffMs) continue
            if(endMs<nowMs-LINE_RETENTION_MS) continue
            // 纯文本即使被表情解析器拆成多段，也可以拼回一张图集贴图；
            // 只有真正含表情资源的行才留在逐帧文字路径上。
            val spriteText=parts.takeIf { parts.all { it.asset==null } }
                ?.joinToString(separator="") { it.text }
                ?.takeIf { it.isNotEmpty() }
            lines+=obtainLine().apply {
                bind(startMs,durationMs,width,lane,parts,widths,spriteText)
            }
        }
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
        configureSurfaceBuffer(w,h)
        baseTextSize=baseSizeFor(w,h)
        applyTextMetrics()
        relayout()
    }

    /**
     * 低配电视的透明弹幕层由 HWC 走 Client 合成，1080P 缓冲每帧都要额外清屏和整屏混合。
     * 内部降到 720P 后仍按视图坐标绘制，最终由 SurfaceFlinger 放大铺满画面；帧率不降。
     */
    private fun configureSurfaceBuffer(viewWidth: Int,viewHeight: Int) {
        if(!lowEnd || viewWidth<=0 || viewHeight<=0) return
        val scale=minOf(
            1f,
            LOW_END_SURFACE_MAX_WIDTH.toFloat()/viewWidth,
            LOW_END_SURFACE_MAX_HEIGHT.toFloat()/viewHeight,
        )
        val surfaceWidth=(viewWidth*scale).roundToInt().coerceAtLeast(1)
        val surfaceHeight=(viewHeight*scale).roundToInt().coerceAtLeast(1)
        if(surfaceWidth==fixedSurfaceWidth && surfaceHeight==fixedSurfaceHeight) return
        fixedSurfaceWidth=surfaceWidth
        fixedSurfaceHeight=surfaceHeight
        holder.setFixedSize(surfaceWidth,surfaceHeight)
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
        val verticalGap=maxOf(fill.textSize*LANE_GAP_VERTICAL,density*LANE_MIN_GAP_DP)
        laneHeight=maxOf(textBox,emojiBox)+verticalGap
        if(mode==DanmakuMode.HIDDEN || laneHeight<=0f) { laneCount=0; return }
        laneTop=maxOf(
            if(topInsetPx>=0) topInsetPx.toFloat() else TOP_INSET_DP*density,
            highlightSize*0.6f+HIGHLIGHT_PAD_DP*density*0.5f,
        )
        val bottom=height-BOTTOM_INSET_DP*density
        val byHeight=((bottom-laneTop).coerceAtLeast(0f)/laneHeight).toInt().coerceIn(0,24)
        laneCount=minOf(mode.lineLimit,byHeight)
    }

    private fun drawSurfaceFrame() {
        val canvas=runCatching {
            if(Build.VERSION.SDK_INT>=26) holder.lockHardwareCanvas() else holder.lockCanvas()
        }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
            if(canvas.width!=width || canvas.height!=height) {
                canvas.save()
                canvas.scale(
                    canvas.width.toFloat()/width.coerceAtLeast(1),
                    canvas.height.toFloat()/height.coerceAtLeast(1),
                )
                drawFrame(canvas)
                canvas.restore()
            } else {
                drawFrame(canvas)
            }
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawFrame(canvas: Canvas) {
        if(mode==DanmakuMode.HIDDEN || laneCount<=0) return
        val viewWidth=width.toFloat()
        val now=clockPositionMillis()
        if(now+SEEK_BACK_TOLERANCE_MS<lastPosition) {
            scheduleDirty=true
            highlightCursor=0
            nextSpriteRefreshAt=0L
        }
        lastPosition=now
        scheduleScrollWindow(now)
        if(now>=nextSpriteRefreshAt) refreshSpriteAtlas(now)
        val pixelsPerSecond=speedPx(viewWidth/BASE_SCROLL_SECONDS)
        var drawn=0
        for(line in lines) {
            if(line.skipped || line.startMs>now || line.endMs<now) continue
            // 排程起点可能早于首次真正上屏；首次绘制时重新锚定到右边缘，
            // 同轨按实际进入时间保留间距，避免弹幕第一次出现就落在屏幕中间。
            if(!line.entered) {
                val lane=line.lane.coerceIn(0,laneEntryFreeMs.lastIndex)
                val actualStart=maxOf(now,laneEntryFreeMs[lane])
                if(actualStart-line.startMs>MAX_DELAY_MS) {
                    line.skipped=true
                    continue
                }
                line.startMs=actualStart
                line.entered=true
                laneEntryFreeMs[lane]=actualStart+
                    (((line.width+laneGap)/pixelsPerSecond)*1000f).toLong()
                if(actualStart>now) continue
            }
            val elapsed=now-line.startMs
            val duration=line.durationMs.coerceAtLeast(1L)
            val x=viewWidth-elapsed.toFloat()/duration*(viewWidth+line.width)
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
        val highlightDrawn=drawHighlights(canvas,now)
        scheduleNextFrame(now,drawn>0 || highlightDrawn>0)
    }

    /**
     * 有弹幕时跟随屏幕刷新率；没有活动弹幕时停到下一批弹幕的起播点。
     * 低配电视同样保持 60fps，空闲期不再持续做透明层重绘。
     */
    private fun scheduleNextFrame(nowMs: Long,hasActive: Boolean) {
        if(!clockRunning || visibility!=VISIBLE || !attachedToWindow || !surfaceReady) return
        if(hasActive) {
            requestFrame()
            return
        }
        val nextMs=nextEventMs(nowMs) ?: return
        val playbackDelay=(nextMs-nowMs).coerceAtLeast(1L)
        val wallDelay=(playbackDelay/clockSpeed).toLong().coerceAtLeast(1L)
        if(wallDelay<=16L) requestFrame() else requestFrame(wallDelay)
    }

    private fun nextEventMs(nowMs: Long): Long? {
        var next: Long?=null
        for(line in lines) {
            if(line.skipped || line.endMs<nowMs || line.startMs<=nowMs) continue
            if(next==null || line.startMs<next) next=line.startMs
        }
        var index=highlightCursor
        while(index<highlights.size && highlights[index].endMs<=nowMs) index++
        while(index<highlights.size) {
            val round=highlights[index++]
            if(round.startMs<=nowMs) continue
            if(next==null || round.startMs<next) next=round.startMs
            break
        }
        if(next==null) {
            var index=nextGroupIndex
            while(index<groups.size && groups[index].key in scheduledGroupKeys) index++
            if(index<groups.size) next=groups[index].firstMs
        }
        return next
    }

    private fun drawHighlights(canvas: Canvas,nowMs: Long): Int {
        if(mode==DanmakuMode.HIDDEN || highlights.isEmpty()) return 0
        while(highlightCursor<highlights.size && highlights[highlightCursor].endMs<=nowMs) highlightCursor++
        var index=highlightCursor
        var scanned=0
        var drawn=0
        while(index<highlights.size && scanned<32) {
            val round=highlights[index]
            if(round.startMs>nowMs) break
            if(nowMs<round.endMs) {
                drawHighlightRound(canvas,round,nowMs)
                drawn++
            }
            index++
            scanned++
        }
        return drawn
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
        val atlas=spriteAtlas
        val sprite=atlas?.get(round.group.text)
        if(sprite!=null) {
            atlas.draw(
                canvas,
                sprite,
                left,
                baseline,
                spritePaint,
                spriteDst,
                highlightSize/fill.textSize.coerceAtLeast(1f),
            )
        } else {
            drawParts(canvas,parts,widths,left,baseline,hlFill,hlEdge,emojiSize,hlEmojiCenter)
        }
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
            DanmakuMode.TWO -> laneTop+laneHeight
            DanmakuMode.THREE -> laneTop+laneHeight*1.5f
            else -> laneTop+laneHeight*2f
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
            onReady = { requestFrame() },
        ).also {
            spriteAtlas=it
            it.setStyle(fill.textSize,edge.strokeWidth)
        }
    }

    private fun refreshSpriteAtlas(nowMs: Long) {
        if((lines.isEmpty() && highlights.isEmpty()) || width<=0 || height<=0) return
        val wanted=LinkedHashSet<String>()
        val until=nowMs+SPRITE_AHEAD_MS
        val since=nowMs-SPRITE_BEHIND_MS
        var index=0
        while(index<lines.size) {
            val line=lines[index]
            val start=line.startMs
            if(start>until) break
            val end=line.endMs
            if(end>=since) line.spriteText?.let { wanted+=it }
            index++
        }
        var highlightIndex=highlightCursor
        while(highlightIndex<highlights.size) {
            val round=highlights[highlightIndex]
            if(round.startMs>until) break
            if(round.endMs>=since) {
                val text=round.group.text
                if(text.isNotEmpty() && DanmakuEmoji.parts(text).all { it.asset==null }) {
                    wanted+=text
                }
            }
            highlightIndex++
        }
        nextSpriteRefreshAt=nowMs+SPRITE_REFRESH_MS
        if(wanted.isNotEmpty()) ensureSpriteAtlas()?.request(wanted)
    }

    fun resetCursor() {
        scheduleDirty=true
        highlightCursor=0
        lastPosition=-1L
        lastSchedulePositionMs=-1L
        nextSpriteRefreshAt=0L
        requestFrame()
    }

    private fun requestFrame(delayMs: Long=0L) {
        if(!surfaceReady || visibility!=VISIBLE || !attachedToWindow) return
        if(frameScheduled) {
            if(delayMs>0L) return
            choreographer.removeFrameCallback(frameCallback)
        }
        frameScheduled=true
        if(delayMs<=0L) choreographer.postFrameCallback(frameCallback)
        else choreographer.postFrameCallbackDelayed(frameCallback,delayMs)
    }

    override fun onVisibilityChanged(changedView: View,visibility: Int) {
        super.onVisibilityChanged(changedView,visibility)
        if(visibility!=VISIBLE) {
            choreographer.removeFrameCallback(frameCallback)
            frameScheduled=false
        } else {
            requestFrame()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady=true
        ensureSpriteAtlas()
        nextSpriteRefreshAt=0L
        refreshSpriteAtlas(clockPositionMillis())
        requestFrame()
    }

    override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) {
        requestFrame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady=false
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled=false
    }
}
