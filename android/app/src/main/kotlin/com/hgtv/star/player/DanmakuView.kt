// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

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
import com.hgtv.star.tv.designSize
import com.hgtv.star.compat.attachedToWindow

/**
 * 压在播放画面上的弹幕层。
 *
 * 数据来自 [com.hgtv.star.core.ContentRepository.danmaku]，就是「原 App」播放时看到的那一份。
 * 这里只负责画：布局（哪一条落在哪一轨、什么时候进画）在 [relayout] 里一次性算好，
 * 每帧只按当前播放位置求 x，不再排序、不再测量，低端电视上也能跟上。
 *
 * 四种显示方式由 [DanmakuMode] 决定：
 * - [DanmakuMode.SINGLE] 只显一行：整屏只有一条轨道，多密也不会上下叠字；
 * - [DanmakuMode.TOP] 上方显示：只在屏幕最上面四分之一内滚动，贴着顶边往下铺；
 * - [DanmakuMode.FULL] 全屏显示：整幅画面从上到下都是弹幕轨道；
 * - [DanmakuMode.HIDDEN] 隐藏弹幕：整层不可见，也不参与绘制。
 *
 * **不叠字的做法**（需求二）：
 * 1. 同一条轨道上，后来的那条要等前一条的**尾部离开画面右边**才能进画
 *    （`freeAt = start + (宽度 + 间距) / 速度`），因此后一条永远追不上前一条；
 * 2. 所有弹幕**同速**（[DanmakuSpeed] 只改整体速度，不改「谁快谁慢」），宽度不同的两条
 *    之间也不会出现追尾——这是「提高速度不增加碰撞」的关键；
 * 3. 轨道高度按**字体实际高度 + 描边 + 表情图边长**算，不按字号乘一个系数，
 *    大字体下相邻两轨的字也不会碰在一起；
 * 4. 排不下的时候先换轨道（挑最早空出来的那条），所有轨道都排不下才往后延迟；
 *    延迟超过 [MAX_DELAY_MS] 就跳过这一条，不做强行叠加。
 *
 * **重复弹幕聚合**（需求四）：整集数据先按内容聚合成一组一组（[repeatGroups]），
 * 同一组只画一次、文案带数量标记；其中数量最多的那几组按 [planHighlights] 排好的次序
 * 上屏幕中央突出显示。中央是**竖排的几格**（见 [highlightSlots]）：只显一行时一格，
 * 上方显示两格、全屏显示三格，被聚合的弹幕可以同时出现好几条。
 *
 * 位置与播放状态都由宿主（MainActivity）通过 [setClock] 推过来，绘制时只用系统时钟外推；
 * 暂停、跳转、倍速都会重新校准，因此弹幕不会和播放器脱节，绘制线程也不会回调播放器。
 *
 * 文本里的表情标签（`[笑哭]`）在 [relayout] 里就被 [DanmakuEmoji.parts] 切成
 * 「文字片段 + 表情片段」：文字照常按字体画，表情按 [DanmakuEmojiStore] 里的图片画。
 * 图片还没解好时先用字形顶着，解好后 [DanmakuEmojiStore.Listener] 会把这一帧重画一次换上去。
 */
internal class DanmakuView(context: Context) : View(context),DanmakuEmojiStore.Listener {
    private companion object {
        /**
         * 横穿一屏的基准时长（秒）。
         *
         * 「标准」档按它算速度，其余档位在这个基准上乘系数（见 [DanmakuSpeed.factor]）：
         * 速度 = 屏宽 / 这个基准。比按下发时长更早的实现（原来固定 9 秒）快一档，
         * 因此屏幕上的弹幕更跟得上播放进度，同一屏能过更多条。
         */
        const val BASE_SCROLL_SECONDS=6f
        /** 全屏弹幕：顶部标题条（96dp）之下才开始铺轨道，弹幕不压剧名。 */
        const val TOP_INSET_DP=104
        /** 上方弹幕：整层只占屏幕高度的这一份，其余留白。 */
        const val TOP_MODE_SHARE=0.25f
        /** 上方弹幕 / 只显一行离顶边留的安全边距，躲开电视过扫描。 */
        const val TOP_MODE_INSET_DP=20
        /** 底部控制层之上收住，弹幕不压进度条。 */
        const val BOTTOM_INSET_DP=120
        /** 单帧最多画多少条，给低端 GPU 一个上限。 */
        const val MAX_LINES_PER_FRAME=160
        /** 同一轨道上前后两条之间至少留这么宽（按字号算），字与字不贴着。 */
        const val LANE_GAP_TEXT=0.9f
        /** 相邻轨道之间至少留这么高（按字号算）：描边、投影与表情图都算在这一档里。 */
        const val LANE_GAP_VERTICAL=0.35f
        /** 一条弹幕最多允许比自己的时间点晚多久出现；再晚就跳过，不做强行叠加。 */
        const val MAX_DELAY_MS=4_000L
        /** 中央突出显示的字号倍率与底板留白（dp）。 */
        const val HIGHLIGHT_SCALE=1.25f
        const val HIGHLIGHT_PAD_DP=13
        /** 表情图相对字号的大小：比汉字略大一点，跟系统 emoji 的观感一致。 */
        const val EMOJI_SCALE=1.2f
        /**
         * 片段缓存 / 宽度缓存的条数上限。
         *
         * 一集最多 3000 条（见 `ContentRepository.MAX_DANMAKU`），按这个量级封顶，
         * 让「边拉边画」的 40 个窗口都能命中前 39 次排好的结果。
         */
        const val CACHE_LINES=4096
        /** 中央那一块宽度缓存的空值（作废时填回去，不用每次 new 一个空数组）。 */
        val EMPTY_WIDTHS=FloatArray(0)
    }

    /**
     * 一条已经排好轨道的普通弹幕：起点（秒）、横穿时长（秒）、整条宽度、所在轨道、切好的片段。
     *
     * [partWidths] 与 [parts] 一一对应，在排版阶段算好。绘制时**只读**它——
     * 绝不再调 `measureText`（见 [drawParts]）。
     */
    private class Line(
        val start: Float,
        val duration: Float,
        val width: Float,
        val lane: Int,
        val parts: List<DanmakuPart>,
        val partWidths: FloatArray
    )

    private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.WHITE
        typeface=Typeface.DEFAULT_BOLD
    }
    private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xB3000000.toInt()
        style=Paint.Style.STROKE
        typeface=Typeface.DEFAULT_BOLD
        strokeJoin=Paint.Join.ROUND
    }
    /** 中央突出显示的两支笔：字号比普通弹幕大一档，其余与普通弹幕同一套。 */
    private val hlFill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.WHITE
        typeface=Typeface.DEFAULT_BOLD
    }
    private val hlEdge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xCC000000.toInt()
        style=Paint.Style.STROKE
        typeface=Typeface.DEFAULT_BOLD
        strokeJoin=Paint.Join.ROUND
    }
    /** 中央那一块的底板：弹幕密集时后面的字幕会从它下面穿过，给一层半透明底才读得清。 */
    private val plate=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0x99000000.toInt()
        style=Paint.Style.FILL
    }
    /** 画表情图用：只要 alpha（跟着弹幕透明度走），颜色和描边都不掺和。 */
    private val emojiPaint=Paint(Paint.FILTER_BITMAP_FLAG)
    /** 表情图 / 底板的目标矩形，每帧复用，绘制循环里不新建对象。 */
    private val emojiRect=RectF()
    private val plateRect=RectF()
    private val emoji=DanmakuEmojiStore.get(context)
    /**
     * 一个「设计单位」等于多少像素。
     *
     * 弹幕是自绘的，字号 / 边距全在画布上直接算像素，因此这里不能再乘 density——
     * 那等于把「设备 dpi」这件事又带回来一次（540dpi 的机器上弹幕会大到铺满屏）。
     * 与全站其它控件一样，统一按设计画布缩放。
     */
    private val density=context.designSize(1f)
    private val lines=mutableListOf<Line>()
    /** 这一集拿到的全部弹幕（按时间排好）；换显示方式、换分辨率、换速度都从它重排。 */
    private var raw: List<Danmaku> = emptyList()
    /** 聚合出来的每一组（同一组只画一次，文案带数量标记），按时间排好。 */
    private var groups: List<DanmakuRepeat> = emptyList()
    /** 中央突出显示的次序（同一条只上一次，不同格之间可以重叠）；数据补上来时重排一次，见 [planHighlights]。 */
    private var highlights: List<DanmakuHighlightRound> = emptyList()
    /**
     * 切好的片段缓存：同一条文案（含数量标记）只切一次，避免每帧走正则。
     *
     * **不随每次 relayout 清空**：数据是一窗一窗补上来的（40 个窗口），
     * 每窗清一次就等于把整集文案重新切一遍，而实际新增的只有一个窗口。
     * 只按条数封顶做 LRU，换集时在 [clear] 里整份丢掉。
     */
    // 淘汰走 `lruPut`，**不用匿名子类重写 `removeEldestEntry`**：那种写法会生成一条
    // `invoke-super LinkedHashMap.getOrDefault`（API 24）的桥方法，4.4 上是颗雷（见 core 的 `lruPut`）。
    private val partsCache=LinkedHashMap<String,List<DanmakuPart>>(64,.75f,true)
    /**
     * 文案 → 每段的像素宽（排版阶段的 `measureText` 结果）。
     *
     * 与 [partsCache] 同寿命：只在字号变了（[applyTextMetrics]）或换集时作废。
     * 没有它，每个 30 秒窗口都要把整集每一段重新量一遍。
     */
    private val widthCache=LinkedHashMap<String,FloatArray>(64,.75f,true)
    /** 已经排过表情解码的文本：只把**新出现**的送进 `emoji.prefetch`，不再每窗全量扫一遍。 */
    private val prefetched=mutableSetOf<String>()
    /** 中央那一块的排版缓存（它每帧都在画，但一轮之内宽度不会变）：一格一份。 */
    private val hlKeys=Array(DanmakuHighlight.MAX_SLOTS){""}
    private val hlWidths=Array(DanmakuHighlight.MAX_SLOTS){FloatArray(0)}
    private val hlBodyWidths=FloatArray(DanmakuHighlight.MAX_SLOTS)
    /** 当前帧还要画的第一条：按时间排好序，播放推进时只往前走，不回头。 */
    private var cursor=0
    /** 中央那一块当前在画第几轮；只往前挪，跳转时归零。 */
    private var highlightCursor=0
    private var lastPosition=-1L
    private var laneCount=0
    private var laneHeight=0f
    private var laneTop=0f
    /** 同轨道前后两条之间的水平间距（像素），按字号算。 */
    private var laneGap=0f
    /** 按屏高算出来的「原始」字号（1 倍缩放）；缩放与透明度都在它上面叠加。 */
    private var baseTextSize=0f
    /** 突出显示用的字号（普通字号 × [HIGHLIGHT_SCALE]，同样夹在上下限之内）。 */
    private var highlightSize=0f
    /** 描边笔的原始不透明度（0xB3 = 70% 黑）。 */
    private val edgeAlpha=0xB3

    var mode: DanmakuMode=DanmakuMode.FULL
        set(value) {
            if(field==value) return
            field=value
            // 轨道数量跟着档位变（上方显示只有最上面四分之一、只显一行只有一条），必须整批重排，
            // 否则原来落在下半屏的弹幕会跑到「上方」区域外面去。
            relayout()
        }

    /**
     * 字号缩放（1f = 原始大小）。
     *
     * 字号变了轨道高度也变，必须整批重排：不然行距还是旧字号算出来的，
     * 放大之后两行字会叠在一起、缩小之后轨道之间会空一大截。
     */
    var textScale: Float=1f
        set(value) {
            if(field==value) return
            field=value
            applyTextMetrics()
            relayout()
        }

    /**
     * 相同弹幕是否聚合。
     *
     * 默认开启：同一句文字在 [com.hgtv.star.core.DANMAKU_REPEAT_WINDOW_MS] 内合并成一组，
     * 只画一次并带 `×N`；关闭后每一条都当作独立弹幕，数量标记与中央突出显示一并消失。
     * 聚合会影响整批数据的分组，因此改档要整批重排。
     */
    var mergeRepeats: Boolean=true
        set(value) {
            if(field==value) return
            field=value
            relayout()
        }

    /**
     * 相同弹幕达到几条才聚合（2..10，设置页那条滑动条）。
     *
     * 窗口里同一句不到这个条数就不合并：逐条原样滚过去、不带 `×N`、也不进中央；
     * 达到才合成一条。默认 2 = 同一句出现两次就合并（与加这一项之前一致）。
     * 阈值会改变整批数据的分组，因此改档要整批重排。
     */
    var mergeMinCount: Int=2
        set(value) {
            val next=value.coerceIn(DANMAKU_MERGE_MIN_RANGE)
            if(field==next) return
            field=next
            relayout()
        }

    /**
     * 文字不透明度（1f = 完全不透明）。
     *
     * 只改笔的 alpha，不动字号，因此不用重排轨道，重画一帧就够。
     * 表情图跟着一起淡：字淡了、表情还是全彩，看上去就像贴上去的贴纸。
     */
    var textAlpha: Float=1f
        set(value) {
            if(field==value) return
            field=value
            applyTextMetrics()
            invalidate()
        }

    /**
     * 移动速度档位（[DanmakuSpeed.NORMAL] 为默认）。
     *
     * 速度只影响「一条弹幕横穿画面用多久」与同轨道能排多密，因此要整批重排：
     * 换档之后前后两条的间距按新速度重算，才不会有某一条比新速度慢半拍而追尾。
     */
    var speed: DanmakuSpeed=DanmakuSpeed.NORMAL
        set(value) {
            if(field==value) return
            field=value
            relayout()
        }

    /**
     * 中央那一块同时能放几条：只显一行只有唯一那一行、放一格；
     * 上方显示放两格、全屏显示放三格（竖着排开，见 [highlightSlots] 的用法）。
     *
     * 原来三档都只有一格，几条被聚合的弹幕只能一条接一条轮流上，屏幕上永远只有一句；
     * 现在同时能看到的聚合弹幕数量跟着画面能用的高度走。
     */
    private val highlightSlots: Int
        get() = when(mode) {
            DanmakuMode.SINGLE -> 1
            DanmakuMode.TOP -> 2
            DanmakuMode.FULL -> DanmakuHighlight.MAX_SLOTS
            DanmakuMode.HIDDEN -> 1
        }

    /**
     * 播放时钟：宿主推进，绘制路径上不再回调播放器。
     *
     * 原来 [onDraw] 每帧都去读 `player.currentPosition` / `player.isPlaying`：
     * 播放器内部一旦出现异常状态（线上见过 `PlaybackInfo.timeline` 为 null），
     * 异常就从渲染线程直接冒到 `AndroidRuntime`，整个进程被带走。
     * 现在宿主每秒、以及播放状态 / 跳转 / 倍速变化时调用 [setClock] 校准一次，
     * [clockPositionMillis] 只用系统时钟外推，绘制线程不碰播放器。
     */
    private var clockBaseMs=0L
    private var clockBaseAt=0L
    private var clockSpeed=1f
    private var clockRunning=false

    /**
     * 校准播放时钟。
     *
     * [positionMs] 是这一瞬间的媒体位置，[running] 表示是否在推进，
     * [speed] 用于外推时把倍速算进去（1.5× / 2× 下弹幕速度才不会落后）。
     */
    fun setClock(positionMs: Long,running: Boolean,speed: Float=clockSpeed) {
        clockBaseMs=positionMs.coerceAtLeast(0)
        clockBaseAt=SystemClock.elapsedRealtime()
        clockSpeed=speed.coerceIn(0.25f,4f)
        clockRunning=running
        if(running && visibility==VISIBLE && attachedToWindow) postInvalidateOnAnimation()
    }

    /** 当前媒体位置：暂停时停在基准点，播放时按系统时钟与倍速外推。 */
    fun clockPositionMillis(): Long {
        if(!clockRunning) return clockBaseMs
        val elapsed=(SystemClock.elapsedRealtime()-clockBaseAt).coerceAtLeast(0L)
        return clockBaseMs+(elapsed*clockSpeed).toLong()
    }

    /** 离场：停掉外推并回到 0，之后即使还有一帧没画完也不会继续读播放器。 */
    fun stopClock() {
        clockRunning=false
        clockBaseMs=0L
        clockBaseAt=SystemClock.elapsedRealtime()
    }
    init {
        isFocusable=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        // 空态用 INVISIBLE 而不是 GONE：GONE 的视图不参与测量，width/height 永远是 0，
        // relayout() 里「宽高齐了才排轨道」的条件就永远不成立，弹幕层会把自己锁死在空态。
        // INVISIBLE 照样被测量、被布局，只是不绘制，取到弹幕时能立刻按真实尺寸排轨道。
        visibility=INVISIBLE
    }

    /** 换集 / 拉到新的一批弹幕：重排轨道并回到第一条。 */
    fun setDanmaku(items: List<Danmaku>) {
        raw=items.sortedBy { it.offsetMs }
        relayout()
    }

    /** 清空这一集的弹幕（换集、切到隐藏档）。 */
    fun clear() {
        raw=emptyList()
        // 换集才整份丢掉三个缓存：纹理与宽度都只跟这一集的文案有关。
        partsCache.clear()
        widthCache.clear()
        prefetched.clear()
        clearHighlightMetrics()
        relayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        emoji.addListener(this)
    }

    override fun onDetachedFromWindow() {
        emoji.removeListener(this)
        super.onDetachedFromWindow()
    }

    /** 有一张表情图解好了：重画一帧，把顶着的字形换成图片。可能在解码线程上被叫到。 */
    override fun onEmojiDecoded() {
        if(visibility==VISIBLE) postInvalidateOnAnimation()
    }

    /**
     * 按当前档位、字号与速度把数据重排一次；每次重排都从头开始画。
     *
     * 数据是**一窗一窗补上来的**（见 `ContentRepository.danmaku` 的 `onWindow`），
     * 因此这个方法会被反复调用：每次都当作「手上这一份就是全部」重排，
     * 已经画过的位置不回补，只有后面的内容按新数据重排。
     */
    private fun relayout() {
        lines.clear()
        // `partsCache` / `widthCache` **不清**：它们只跟文案与字号有关，
        // 与轨道、速度、显示档位都无关（字号变化由 [applyTextMetrics] 负责作废）。
        cursor=0
        lastPosition=-1L
        groups=if(mergeRepeats) repeatGroups(raw,minCount=mergeMinCount) else raw.map { DanmakuRepeat(it.text,listOf(it.offsetMs)) }
        // 中央突出显示的次序：传上一份，正在展示的那几条不会被新数据挤掉（见 planHighlights）。
        highlights=planHighlights(groups,highlights,highlightSlots,mergeMinCount)
        highlightCursor=0
        layoutLanes()
        val lanes=if(mode==DanmakuMode.HIDDEN) 0 else laneCount
        if(lanes>0 && width>0 && height>0) schedule(lanes)
        // 表情图只把**新出现**的文本排进解码队列：每窗全量传一遍等于每 30 秒做几千次带锁查表，
        // 而那些图早就解好了。等画到它们的时候图在缓存里，屏幕上不会先闪一下字形再换成图片。
        val fresh=mutableListOf<String>()
        groups.forEach { if(prefetched.add(it.text)) fresh+=it.text }
        if(fresh.isNotEmpty()) emoji.prefetch(fresh)
        // 可见性只看「有没有数据」，不看「排没排成轨道」：轨道要等 onSizeChanged 之后才算得出来，
        // 若在这里用 lines.isEmpty() 判空，视图会因为「没尺寸 → 排不出轨道 → 保持不可见 → 不测量」
        // 形成死循环，弹幕接口就算返回几千条也一条都不显示。
        visibility=if(mode==DanmakuMode.HIDDEN || raw.isEmpty()) INVISIBLE else VISIBLE
        invalidate()
    }

    /**
     * 把每一组排到轨道上：挑「最早能空出来」的那条轨道，并算出它什么时候进画。
     *
     * 同一轨道上前后两条的关系是**同速追不上**：后一条要在前一条的尾部离开画面右边之后
     * 才能进画（`间距 / 速度`），两条相对速度为零，因此永远不会叠在一起。
     * 所有轨道都排不下就往后延迟；延迟超过 [MAX_DELAY_MS] 的跳过——
     * 宁可少一条，也不要在同一处硬叠两条（需求二）。
     */
    private fun schedule(lanes: Int) {
        val viewWidth=width.toFloat()
        val pixelsPerSecond=speedPx(viewWidth/BASE_SCROLL_SECONDS)
        val laneFree=FloatArray(lanes)
        // 已经排上中央的那几组不再进普通轨道：它们由中央那一块展示，避免同一句同时出现两次。
        // 其余弹幕**照常排**，包括「只显一行」那唯一一条轨道：中央的底板只是盖在上面，
        // 其它弹幕从右边滚进来、从底板后面穿过去（原来整条轨道被中央占着，
        // 聚合期间屏幕上一条普通弹幕都没有，看着像弹幕停了）。
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
            // 排不下：不强行叠加，直接跳过这一条（需求二「不应强行叠加显示」）。
            if((start-want)*1000f>MAX_DELAY_MS) return@forEach
            laneFree[lane]=start+(width+laneGap)/pixelsPerSecond
            lines+=Line(start,duration,width,lane,parts,widths)
        }
        lines.sortBy { it.start }
    }

    /** 速度档位换算成每秒多少像素：屏宽定的基准速度 × 档位系数。 */
    private fun speedPx(pixelsPerSecond: Float)=pixelsPerSecond*speed.factor

    /** 一条弹幕的片段（切成「文字 + 表情」），同一条文案只切一次。 */
    private fun partsOf(text: String): List<DanmakuPart> =
        partsCache[text] ?: DanmakuEmoji.parts(text).also { partsCache.lruPut(CACHE_LINES,text,it) }

    /**
     * 每段的像素宽（与 [parts] 一一对应）。
     *
     * 结果进 [widthCache]：渲染循环里绝不能再 `measureText`——那要按当前 `Paint`
     * 走一次 shaping，`MAX_LINES_PER_FRAME=160` 条 × 平均 3 段 × 60 fps 就是
     * 每秒近三万次，老 GPU 上直接吃掉半帧预算（视频层是硬件独立合成，用户看到的就是
     * 「画面很顺、弹幕卡」）。文案相同则宽度一定相同，缓存命中率很高。
     */
    private fun widthsOf(label: String,parts: List<DanmakuPart>,emojiSize: Float): FloatArray =
        widthCache[label] ?: run {
            FloatArray(parts.size) { index ->
                val part=parts[index]
                if(part.asset==null) fill.measureText(part.text) else emojiSize
            }.also { widthCache.lruPut(CACHE_LINES,label,it) }
        }

    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        // 轨道数量按屏高现算：电视上换分辨率、放大字号都会重建视图。
        // 字号按屏高给（1080p 电视约 18sp），再夹住上下限：720p 与 4K 都不会过大或过小。
        baseTextSize=(h*0.034f).coerceIn(density*18f,density*34f)
        applyTextMetrics()
        relayout()
    }

    /**
     * 把「屏高定出的基准字号 + 用户缩放 / 透明度」落到几支笔上。
     *
     * 缩放后的字号仍然夹一次上下限（10dp–72dp）：把 2 倍字号铺到 4K 屏上会盖住大半画面，
     * 0.6 倍铺到 720p 上会小到读不出来，两头都收住。
     * 描边与表情图的不透明度跟着文字一起缩：字淡了、描边还全黑、表情还是全彩，
     * 看上去就是一圈黑边和一堆贴纸浮在画面上。
     */
    private fun applyTextMetrics() {
        val size=(baseTextSize*textScale).coerceIn(density*10f,density*72f)
        val alpha=textAlpha.coerceIn(0f,1f)
        fill.textSize=size
        edge.textSize=size
        edge.strokeWidth=size*0.14f
        // 中央那一块大一号，同样收在 72dp 之内：它本来就要「突出」，不必再被屏宽限制。
        highlightSize=(size*HIGHLIGHT_SCALE).coerceAtMost(density*72f)
        hlFill.textSize=highlightSize
        hlEdge.textSize=highlightSize
        hlEdge.strokeWidth=highlightSize*0.16f
        fill.alpha=(255*alpha).toInt().coerceIn(0,255)
        edge.alpha=(edgeAlpha*alpha).toInt().coerceIn(0,255)
        hlFill.alpha=(255*alpha).toInt().coerceIn(0,255)
        hlEdge.alpha=(0xCC*alpha).toInt().coerceIn(0,255)
        emojiPaint.alpha=(255*alpha).toInt().coerceIn(0,255)
        // 底板跟着透明度淡一点：全黑底在暗画面上会把弹幕压成一块黑砖。
        plate.alpha=(0x99*alpha).toInt().coerceIn(0,255)
        // 字号变了，所有排版阶段的宽度结果作废（透明度不用：它不影响宽度）。
        widthCache.clear()
        clearHighlightMetrics()
    }

    /** 中央那一块的宽度缓存整份作废（换集、改字号时）。 */
    private fun clearHighlightMetrics() {
        hlKeys.fill("")
        hlWidths.fill(EMPTY_WIDTHS)
        hlBodyWidths.fill(0f)
    }

    /**
     * 重新量一次可用轨道。
     *
     * * 全屏显示铺满整幅画面，只让开顶部标题条与底部控制层；
     * * 上方显示只占屏幕最上面四分之一，紧贴顶边往下铺，留白在下面；
     * * 只显一行只留一条轨道（轨道高度按突出字号算，中央那一块也放得下）。
     *
     * 轨道高度按**字体实际高度**（ascent..descent）+ 描边 + 垂直间距算，再与表情图边长取大：
     * 原来按「字号 × 1.25」估，大字号下字形加上描边就超过这个高度，相邻两行会压在一起。
     */
    private fun layoutLanes() {
        val metrics=fill.fontMetrics
        val textBox=(metrics.descent-metrics.ascent)+fill.strokeWidth
        val emojiBox=fill.textSize*EMOJI_SCALE
        laneGap=fill.textSize*LANE_GAP_TEXT
        laneHeight=maxOf(textBox,emojiBox)+fill.textSize*LANE_GAP_VERTICAL
        if(mode==DanmakuMode.HIDDEN || laneHeight<=0f) { laneCount=0; return }
        val usable=when(mode) {
            DanmakuMode.SINGLE -> {
                // 唯一那一行：顶边留出的边距要装得下中央那一块——它比普通弹幕大一档，
                // 底板上下还各有一圈留白，按普通档的 20dp 摆上去上面那半截会被屏幕顶边切掉。
                laneTop=maxOf(TOP_MODE_INSET_DP*density,
                    highlightSize*0.6f+HIGHLIGHT_PAD_DP*density*0.5f)
                laneHeight
            }
            DanmakuMode.TOP -> {
                laneTop=TOP_MODE_INSET_DP*density
                (height*TOP_MODE_SHARE-laneTop).coerceAtLeast(0f)
            }
            else -> {
                laneTop=TOP_INSET_DP*density
                (height-BOTTOM_INSET_DP*density).coerceAtLeast(laneTop+1f)-laneTop
            }
        }
        laneCount=(usable/laneHeight).toInt().coerceIn(0,24)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(mode==DanmakuMode.HIDDEN || laneCount<=0) return
        if(lines.isEmpty() && highlights.isEmpty()) return
        val viewWidth=width.toFloat()
        val now=clockPositionMillis()
        val seconds=now/1000f
        // 跳转（前进或后退）之后指针作废，从头找一次；正常播放只往前挪。
        if(now<lastPosition) { cursor=0; highlightCursor=0 }
        lastPosition=now
        while(cursor<lines.size && seconds-lines[cursor].start>lines[cursor].duration) cursor++
        var drawn=0
        var index=cursor
        while(index<lines.size && drawn<MAX_LINES_PER_FRAME) {
            val line=lines[index]
            val elapsed=seconds-line.start
            if(elapsed<0f) break
            if(elapsed<=line.duration) {
                val x=viewWidth-elapsed/line.duration*(viewWidth+line.width)
                val baseline=laneTop+(line.lane+1)*laneHeight-fill.textSize*0.28f
                drawParts(canvas,line.parts,line.partWidths,x,baseline,fill,edge,fill.textSize*EMOJI_SCALE)
                drawn++
            }
            index++
        }
        drawHighlights(canvas,now)
        // 播放中才继续要下一帧：暂停时停在原地，退出播放时视图被移除，循环自然停下。
        if(clockRunning && visibility==VISIBLE && attachedToWindow) postInvalidateOnAnimation()
    }

    /**
     * 画中央突出显示的那几块（需求 §4.3）。
     *
     * * 一格一条：指针 [highlightCursor] 只跳过**已经结束**的轮次；同一时刻其它格里的轮次
     *   也一起画出来，因此被聚合的弹幕可以同时出现好几条（原来只画指针这一条）；
     * * 数量标记跟着播放往上走：`countUpTo(now)` 是「到现在为止已经出现几条」，
     *   重复次数在停留期间继续增加时，标记直接变大（需求 §4.3 规则三）；
     * * 位置按档位定：只显一行在唯一那一行上，上方显示在上方区域内，全屏显示在画面正中，
     *   多格以这个位置为中心上下排开（见 [highlightSlots]）；
     * * 隐藏档整层不画，这里根本不会被执行到。
     */
    private fun drawHighlights(canvas: Canvas,nowMs: Long) {
        if(mode==DanmakuMode.HIDDEN || highlights.isEmpty()) return
        while(highlightCursor<highlights.size && highlights[highlightCursor].endMs<=nowMs) highlightCursor++
        var index=highlightCursor
        // 扫的条数封顶：正常播放时指针附近只有正在展示的那几轮，
        // 这个上限只是防止异常数据（大量重叠轮次）把一帧的预算吃光。
        var scanned=0
        while(index<highlights.size && scanned<32) {
            val round=highlights[index]
            if(round.startMs>nowMs) break
            if(nowMs<round.endMs) drawHighlightRound(canvas,round,nowMs)
            index++
            scanned++
        }
    }

    /** 画一格中央弹幕：正文按 [hlFill]，数量标记接在正文后面，底下垫一层半透明底板。 */
    private fun drawHighlightRound(canvas: Canvas,round: DanmakuHighlightRound,nowMs: Long) {
        val slot=round.slot.coerceIn(0,DanmakuHighlight.MAX_SLOTS-1)
        val parts=partsOf(round.group.text)
        val counter=" ×${round.group.countUpTo(nowMs).coerceAtLeast(DanmakuHighlight.MIN_COUNT)}"
        val emojiSize=highlightSize*EMOJI_SCALE
        // 中央这一块每帧都在画，但正文宽度在一轮之内不会变：量一次就够（每格各缓存一份）。
        // 只有数量标记会变，它每次重画一次 `measureText` 是可接受的（几条）。
        val widths=highlightWidths(round,parts,emojiSize,slot)
        val bodyWidth=hlBodyWidths[slot]
        val counterWidth=hlFill.measureText(counter)
        val total=bodyWidth+counterWidth
        val centerY=highlightCenterY(slot)
        val left=((width-total)/2f).coerceAtLeast(density*8f)
        val padX=HIGHLIGHT_PAD_DP*density
        val padY=HIGHLIGHT_PAD_DP*density*0.5f
        val box=highlightSize*0.95f
        plateRect.set(left-padX,centerY-box/2f-padY,left+total+padX,centerY+box/2f+padY)
        canvas.drawRoundRect(plateRect,density*10f,density*10f,plate)
        val metrics=hlFill.fontMetrics
        val baseline=centerY-(metrics.ascent+metrics.descent)/2f
        drawParts(canvas,parts,widths,left,baseline,hlFill,hlEdge,emojiSize)
        // 数量标记接在正文后面：重画一次基线，宽高与正文同一档。
        canvas.drawText(counter,left+bodyWidth,baseline,hlEdge)
        canvas.drawText(counter,left+bodyWidth,baseline,hlFill)
    }

    /**
     * 第 [slot] 格的竖向中心。
     *
     * 先按档位定「这一组格子的中心」（只显一行是唯一那一行、上方显示是上方区域的中心、
     * 全屏显示是画面正中），再把各格以它为中心上下排开：
     * 三格时分别是中心上方一格、中心、中心下方一格，两格时是中心上下一格。
     * 格子位置固定，不会因为此刻有几条在展示而跳动。
     */
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

    /** 中央两格之间要留的高度：底板高 + 一点缝，大字号下几块底板也不会贴在一起。 */
    private fun highlightRowHeight(): Float=highlightSize*0.95f+HIGHLIGHT_PAD_DP*density+density*6f

    /**
     * 第 [slot] 格里那一块的每段宽度：同一轮 + 同一字号只量一次。
     *
     * 返回值与 `hlWidths[slot]` 是同一份数组；`hlBodyWidths[slot]` 是它的总和。
     * 缓存按格分开：几格同时在画不同的文案，共用一个缓存会每帧互相冲掉、退化成每帧都量。
     */
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

    /**
     * 画一段内容：文字片段按字体画（先描边后填充），表情片段按图片画。
     *
     * [widths] 是排版阶段算好的每段像素宽（见 [widthsOf]）。这里**只读**它，
     * 绝不再调 `measureText`：那要按当前 `Paint` 走一次 shaping，而这条路径每帧
     * 要走上千次（160 条 × 平均 3 段 × 60 fps），老 GPU 上直接吃掉半帧预算。
     *
     * 表情图贴着文字行的中线居中：字有上伸下伸，图片是方的，按基线直接画会整体偏上。
     * 图片没解好时退回画字形（[DanmakuPart.text] 就是字形本身），宽度早就按图片边长算过，
     * 所以换成图片之后这一条的位置和长度都不会跳。
     */
    private fun drawParts(
        canvas: Canvas,
        parts: List<DanmakuPart>,
        widths: FloatArray,
        x: Float,
        baseline: Float,
        textPaint: Paint,
        strokePaint: Paint,
        emojiSize: Float
    ) {
        val metrics=textPaint.fontMetrics
        val emojiTop=baseline+(metrics.ascent+metrics.descent)/2f-emojiSize/2f
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

    /** 播放位置可能被快进，指针要跟着重算；宿主在 seek 之后调一次。 */
    fun resetCursor() { cursor=0; highlightCursor=0; lastPosition=-1L }
}
