// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 弹幕文字图集。
 *
 * 普通弹幕不再一条一张 Bitmap，而是由后台线程把当前窗口的文字统一画进一张透明图集。
 * 前台每帧只按源矩形贴图，避免重复生成描边字形，也避免大量独立纹理来回绑定。
 * 新图集完整生成后才替换旧图集，构建期间仍可继续使用上一张，不会让正在滚动的文字消失。
 */
internal class DanmakuSpriteAtlas(
    private val width: Int,
    private val height: Int,
    private val onReady: () -> Unit,
) {
    internal class Sprite(
        val bitmap: Bitmap,
        val source: Rect,
        val left: Float,
        val top: Float,
    )

    private data class Style(val textSize: Float,val strokeWidth: Float)
    private class Snapshot(val sprites: Map<String,Sprite>)

    private val main=Handler(Looper.getMainLooper())
    private val executor=ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(),
    ) { runnable ->
        Thread(runnable,"hgtv-danmaku-atlas").apply {
            isDaemon=true
            priority=Thread.NORM_PRIORITY-1
        }
    }

    private var style=Style(0f,0f)
    private var current: Snapshot?=null
    private var currentTexts: Set<String> = emptySet()
    private var pendingTexts: Set<String> = emptySet()
    @Volatile private var generation=0
    @Volatile private var closed=false

    /** 字号或描边变化后旧图集立即失效，下一次绘制先用文字路径顶着。 */
    fun setStyle(textSize: Float,strokeWidth: Float) {
        if(closed) return
        val next=Style(textSize,strokeWidth)
        if(next==style) return
        style=next
        invalidate()
    }

    /** 当前窗口的数据变了：清掉旧图集，但不打断正在进行的后台绘制。 */
    fun invalidate() {
        if(closed) return
        generation++
        current=null
        currentTexts=emptySet()
        pendingTexts=emptySet()
        executor.queue.clear()
    }

    /** 取一张已经生成好的弹幕；图集还没准备好时返回 null，由调用方走文字降级路径。 */
    fun get(text: String): Sprite?=current?.sprites?.get(text)

    fun draw(canvas: Canvas,sprite: Sprite,x: Float,baseline: Float,paint: Paint,dst: RectF) {
        dst.set(
            x+sprite.left,
            baseline+sprite.top,
            x+sprite.left+sprite.source.width(),
            baseline+sprite.top+sprite.source.height(),
        )
        canvas.drawBitmap(sprite.bitmap,sprite.source,dst,paint)
    }

    /**
     * 请求一组文字。集合相同则不重复构建；正在构建同一组时也只保留一份。
     * 调用方应传入按播放时间排好序的集合，让图集里的热点文字更集中。
     */
    fun request(texts: Collection<String>) {
        if(closed || style.textSize<=0f || texts.isEmpty()) return
        val wanted=LinkedHashSet<String>(texts.size)
        texts.forEach { text ->
            if(text.isNotEmpty() && text.none { Character.isSurrogate(it) }) wanted+=text
        }
        if(wanted.isEmpty()) return
        if(wanted==currentTexts && current!=null) {
            // 数据短暂回退到当前图集时，别让之前排队的另一窗口随后覆盖它。
            if(pendingTexts.isNotEmpty() && pendingTexts!=wanted) {
                pendingTexts=emptySet()
                generation++
                executor.queue.clear()
            }
            return
        }
        if(wanted==pendingTexts) return
        pendingTexts=wanted
        val ticket=++generation
        val buildStyle=style
        executor.queue.clear()
        executor.execute { build(wanted,buildStyle,ticket) }
    }

    private fun build(texts: Set<String>,buildStyle: Style,ticket: Int) {
        val page=runCatching { Page(width,height,buildStyle) }.getOrNull()
        if(page==null) {
            clearPending(ticket)
            return
        }
        val sprites=LinkedHashMap<String,Sprite>(texts.size)
        for(text in texts) {
            if(closed || ticket!=generation) return
            page.add(text)?.let { sprites[text]=it }
        }
        if(sprites.isEmpty()) {
            clearPending(ticket)
            return
        }
        main.post {
            if(closed || ticket!=generation) return@post
            current=Snapshot(sprites)
            currentTexts=texts
            pendingTexts=emptySet()
            onReady()
        }
    }

    private fun clearPending(ticket: Int) {
        main.post {
            if(!closed && ticket==generation) pendingTexts=emptySet()
        }
    }

    fun close() {
        if(closed) return
        closed=true
        generation++
        current=null
        currentTexts=emptySet()
        pendingTexts=emptySet()
        executor.queue.clear()
        executor.shutdownNow()
    }

    private class Page(
        private val width: Int,
        private val height: Int,
        spriteStyle: Style,
    ) {
        private companion object {
            const val PADDING=2
        }

        private val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        private val canvas=Canvas(bitmap)
        private val bounds=Rect()
        private val metrics=Paint.FontMetrics()
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.WHITE
            typeface=Typeface.DEFAULT_BOLD
            textSize=spriteStyle.textSize
            isSubpixelText=true
        }
        private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=0xB3000000.toInt()
            style=Paint.Style.STROKE
            typeface=Typeface.DEFAULT_BOLD
            textSize=spriteStyle.textSize
            strokeWidth=spriteStyle.strokeWidth
            strokeJoin=Paint.Join.ROUND
            isSubpixelText=true
        }
        private var shelfX=PADDING
        private var shelfY=PADDING
        private var shelfHeight=0

        fun add(text: String): Sprite? {
            fill.getTextBounds(text,0,text.length,bounds)
            fill.getFontMetrics(metrics)
            val pad=styleStrokePadding()
            val left=Math.floor(minOf(-pad,bounds.left-pad).toDouble()).toFloat()
            val right=Math.ceil(maxOf(fill.measureText(text)+pad,bounds.right+pad).toDouble()).toFloat()
            val top=Math.floor(minOf(metrics.ascent-pad,bounds.top-pad).toDouble()).toFloat()
            val bottom=Math.ceil(maxOf(metrics.descent+pad,bounds.bottom+pad).toDouble()).toFloat()
            val spriteWidth=(right-left).toInt()
            val spriteHeight=(bottom-top).toInt()
            if(spriteWidth<=0 || spriteHeight<=0 || spriteWidth>width-PADDING*2) return null
            if(shelfX+spriteWidth+PADDING>width) {
                shelfX=PADDING
                shelfY+=shelfHeight+PADDING
                shelfHeight=0
            }
            if(shelfY+spriteHeight+PADDING>height) return null
            val source=Rect(shelfX,shelfY,shelfX+spriteWidth,shelfY+spriteHeight)
            canvas.save()
            canvas.clipRect(source)
            val textX=shelfX-left
            val baseline=shelfY-top
            canvas.drawText(text,textX,baseline,edge)
            canvas.drawText(text,textX,baseline,fill)
            canvas.restore()
            shelfX+=spriteWidth+PADDING
            shelfHeight=maxOf(shelfHeight,spriteHeight)
            return Sprite(bitmap,source,left,top)
        }

        private fun styleStrokePadding(): Float=edge.strokeWidth/2f+1f
    }
}
