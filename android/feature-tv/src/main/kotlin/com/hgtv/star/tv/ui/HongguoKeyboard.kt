// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.graphics.PorterDuff
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.hgtv.star.shared.ui.SoftDrawable
import com.hgtv.star.shared.ui.SoftStyle

/**
 * HG键盘的字符表：**每行 6 个**，字母在前、数字在后，中间不断行。
 *
 * 第一行是六个功能键（删除 / 空格 / 清空 / 搜索 / 清空历史记录 / 切换键盘），全部用图标表示、
 * 等分整行；拿到焦点时图标下方会显示名称。往下是 26 个字母 + 10 个数字连续每 6 个一行——
 * 字母排到第 5 行只剩 `Y Z`，数字就从它们后面直接接着排，36 个字符正好铺满 6 行。
 *
 * 键盘上**没有**联想词条：联想词在中间那一列（见 `TvMainActivity.renderHongguoSideList`）。
 */
private const val LETTER_ROWS="ABCDEF|GHIJKL|MNOPQR|STUVWX|YZ"
private const val NUMBER_ROWS="123456|7890"

/** 每行固定 6 格。 */
private const val KEY_COLUMNS=6

/** 功能行的键数：删除 / 空格 / 清空 / 搜索 / 清空记录 / 切换键盘。 */
private const val FUNCTION_KEYS=6

/** 焦点底牌的圆角（设计 dp）：小圆角矩形，不是胶囊。 */
private const val FOCUS_RADIUS=8

/** 功能键图标的边长（设计 dp）。 */
private const val ICON_SIZE=22

/** 功能键名称从格子下沿再往下推的距离（设计 dp）：让它整个落在焦点框外面。 */
private const val CAPTION_OFFSET=16

/**
 * HG键盘：功能行 + 6 行字符，按 2/7 栏宽铺在搜索页左侧。
 *
 * **每一格的占用空间是正方形**（边长 = 栏宽 ÷ 每行格数），因此键不会被拉成长条；
 * 常态没有底板，只有字或图标；拿到焦点时按全站按钮的焦点样式点亮，圆角收成小圆角矩形。
 *
 * 只负责「把字符排好、把按键交给调用方」：字符怎么写进输入框、联想词怎么铺，
 * 全在 `detail/HongguoSearchPage.kt` 里。
 */
class HongguoKeyboard(
    private val activity: TvMainActivity,
    private val onLetter: (String)->Unit,
    private val onDelete: ()->Unit,
    private val onClear: ()->Unit,
    private val onSearch: ()->Unit,
    private val onClearHistory: ()->Unit,
    private val onSwitchKeyboard: ()->Unit,
) {
    private lateinit var keyRows: List<List<View>>
    private val rowViews=mutableListOf<LinearLayout>()
    private val grid: LinearLayout
    val view: LinearLayout

    /** 已输入的字符。键盘自己不再显示它（顶部搜索框负责显示），这里只留一份给调用方读写。 */
    var draft: String=""

    /** 上一次排版用的栏宽 / 栏高；两者都没变就不重复排版。 */
    private var laidOutWidth=0
    private var laidOutHeight=0

    init {
        grid=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            clipChildren=false; clipToPadding=false
        }
        view=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            clipChildren=false; clipToPadding=false
        }
        view.addView(grid,LinearLayout.LayoutParams(-1,0).apply { weight=1f })
        renderKeys()
        // 边长要等栏宽量出来才能定：先用「按屏宽估算的栏宽」摆好第一帧，
        // 布局跑完再按真实宽高校正一次。注意单位：估算值是设计 dp，
        // 必须先换成像素，才和 `view.width` 同一套单位。
        layoutCells(activity.dp(estimatedColumnWidth()),0)
        view.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> layoutCells(view.width,view.height) }
    }

    /** 键盘栏宽 = (屏幕宽 − 页面内容缩进 − 两条分隔线) × 2/7，与 `showCatalog` 里的权重一致。 */
    private fun estimatedColumnWidth(): Int {
        val inset=activity.pageInset()-activity.pageBleed()
        return ((activity.widthDp()-inset-2)*2f/7f).toInt()
    }

    /**
     * 每格排成**正方形**（边长 = 该行格宽），并把栏里**多出来的高度摊成行间距**。
     *
     * 正方形是硬约束：6 列 × 57dp 一共只有 411dp，而键盘栏有 600dp 左右。
     * 多出来的一百多 dp 如果全堆在底部，键盘看起来就像「没铺满」；这里把它平均分到各行之间，
     * 键盘因此铺满整栏、上下间距更松，而键本身仍然是正方形。
     *
     * 功能行只有 5 格（每格 1/5 栏宽）、字符行 6 格（1/6 栏宽），两行边长因此不同。
     */
    private fun layoutCells(width: Int,height: Int) {
        if(width<=0) return
        if(width==laidOutWidth && height==laidOutHeight) return
        laidOutWidth=width; laidOutHeight=height
        val functionSide=(width/FUNCTION_KEYS).coerceAtLeast(1)
        val charSide=(width/KEY_COLUMNS).coerceAtLeast(1)
        val rows=rowViews.size
        val sum=functionSide+charSide*(rows-1).coerceAtLeast(0)
        val gaps=(rows-1).coerceAtLeast(1)
        val gap=if(height>sum) (height-sum)/gaps else 0
        rowViews.forEachIndexed { index,row ->
            val params=row.layoutParams as? LinearLayout.LayoutParams ?: return@forEachIndexed
            val side=if(index==0) functionSide else charSide
            val top=if(index==0) 0 else gap
            if(params.height==side && params.topMargin==top) return@forEachIndexed
            params.height=side
            params.topMargin=top
            params.weight=0f
            row.layoutParams=params
        }
    }

    private fun charKey(label: String,size: Float,action: ()->Unit)=KeyCell(activity,label,size,null,action)

    private fun iconKey(iconRes: Int,label: String,action: ()->Unit)=KeyCell(activity,label,0f,iconRes,action)

    private fun renderKeys() {
        grid.removeAllViews()
        rowViews.clear()
        val lines=mutableListOf<MutableList<View>>()
        // 第一行：六个功能键，全部图标，等分整行（1/6，与字符格同宽）。
        // 顺序：删除 / 空格 / 清空输入 / 搜索 / 清空搜索记录 / 切换键盘。
        // 「清空输入」用叉、「清空记录」用垃圾桶：两枚都是清空，图标必须一眼分得开。
        // 每枚在拿到焦点时于图标下方显示自己的名称（见 [KeyCell]）。
        lines+=mutableListOf(
            iconKey(com.hgtv.star.tv.R.drawable.ic_key_delete,"删除") { onDelete() },
            iconKey(com.hgtv.star.tv.R.drawable.ic_key_space,"空格") { onLetter(" ") },
            iconKey(com.hgtv.star.tv.R.drawable.ic_key_clear_input,"清空") { onClear() },
            iconKey(com.hgtv.star.tv.R.drawable.ic_toolbar_search,"搜索") { onSearch() },
            iconKey(com.hgtv.star.tv.R.drawable.ic_key_clear,"清空历史") { onClearHistory() },
            iconKey(com.hgtv.star.tv.R.drawable.ic_toolbar_keyboard,"切换键盘") { onSwitchKeyboard() },
        )
        // 字母接数字，**连续每 6 个一行**：36 个字符正好 6 行、每行满格。
        val cells=mutableListOf<View>()
        (LETTER_ROWS+NUMBER_ROWS).replace("|","").forEach { char ->
            cells+=charKey(char.toString(),22f) { onLetter(char.toString()) }
        }
        cells.chunked(KEY_COLUMNS).forEach { lines+=it.toMutableList() }
        lines.forEachIndexed { rowIndex,cells ->
            val line=LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                clipChildren=false; clipToPadding=false
            }
            cells.forEach { cell -> line.addView(cell,LinearLayout.LayoutParams(0,-1,1f)) }
            // 不满 6 格的行用等宽空格补齐：每格仍然是 1/6，列与列因此上下对齐。
            if(rowIndex>0 && cells.size<KEY_COLUMNS) {
                repeat(KEY_COLUMNS-cells.size) {
                    line.addView(Space(activity),LinearLayout.LayoutParams(0,-1,1f))
                }
            }
            grid.addView(line,LinearLayout.LayoutParams(-1,0).apply { weight=1f })
            rowViews+=line
        }
        keyRows=lines
        wireKeys()
    }

    /**
     * 行内左右直连、行间上下直连；两端与上下边界先指向自己，由 [wireAcross] 接出去。
     *
     * 上下跨行时**按列号找，找不到就落到那一行的最后一格**：各行的格数并不一样
     * （功能行 5 格、字符行 6 格），不做这个兜底的话，从最后一列按上是「原地不动」。
     */
    private fun wireKeys() {
        keyRows.forEachIndexed { rowIndex,buttons ->
            buttons.forEachIndexed { column,button ->
                button.nextFocusLeftId=(buttons.getOrNull(column-1) ?: button).id
                button.nextFocusRightId=(buttons.getOrNull(column+1) ?: button).id
                val above=keyRows.getOrNull(rowIndex-1)?.let { it.getOrNull(column) ?: it.last() }
                val below=keyRows.getOrNull(rowIndex+1)?.let { it.getOrNull(column) ?: it.last() }
                button.nextFocusUpId=(above ?: button).id
                button.nextFocusDownId=(below ?: button).id
            }
        }
    }

    /** 键盘第一格（功能行的「删除」）的 id：进入页面时焦点落在这里。 */
    val firstKeyId: Int get()=keyRows.firstOrNull()?.firstOrNull()?.id ?: View.NO_ID

    /**
     * 把键盘与右侧两列接起来。
     *
     * * 每行**最后一格**按右 → 中间那一列的第一条（行内其余各格仍走左右相邻，见 [wireKeys]）；
     * * 中间那一列的每一条按左 → 键盘最后一行的第一格，按右 → 右列第一张卡；
     * * 第一行按上 → [up]（搜索框），最后一行按下 → 原地不动。
     *
     * [up] 必须是**真的能拿焦点**的控件：框架拿到 `nextFocusUpId` 后直接返回那个视图，
     * 不做可聚焦性检查，指到一个不可聚焦的控件上会抛
     * `focus search returned a view that wasn't able to take focus`。
     */
    fun wireAcross(side: List<View>,right: View?,up: View?) {
        val sideFirst=side.firstOrNull()
        val rightId=sideFirst?.id ?: right?.id ?: View.NO_ID
        // 只有行尾那一格跨出去：把**每一格**的右键都改成侧栏，会让键盘内部的左右移动失效。
        keyRows.forEach { row ->
            row.lastOrNull()?.let { last ->
                last.nextFocusRightId=if(rightId!=View.NO_ID) rightId else last.id
            }
        }
        val upId=up?.takeIf { it.isFocusable }?.id ?: View.NO_ID
        keyRows.firstOrNull()?.forEach { key -> key.nextFocusUpId=if(upId!=View.NO_ID) upId else key.id }
        // 键盘最下面一行按**下**停在原地：再往下没有东西了，跳去别的列只会让人迷惑。
        keyRows.lastOrNull()?.forEach { key -> key.nextFocusDownId=key.id }
        side.forEachIndexed { index,chip ->
            chip.nextFocusLeftId=(keyRows.lastOrNull()?.firstOrNull() ?: chip).id
            chip.nextFocusRightId=right?.id ?: chip.id
            chip.nextFocusUpId=(side.getOrNull(index-1) ?: chip).id
            chip.nextFocusDownId=(side.getOrNull(index+1) ?: chip).id
        }
    }

    /** 进入页面 / 点搜索键时把焦点交给键盘第一格（功能行第一排）。 */
    fun focusFirstKey() {
        keyRows.firstOrNull()?.firstOrNull()?.requestFocus()
    }

    /**
     * 中间那一列每条按左，回到与它**同一水平高度**的那一格（该行最后一格）。
     *
     * 不能一律指向最后一行第一格：那一格在键盘左下角，用户在列表中间按左，
     * 焦点会「斜着跳一大截」到最左下角去。这里按屏幕坐标比较纵向中心，
     * 取纵向最接近的那一行，横向落在它的最后一格——方向上是连续的一步。
     *
     * 只在两边都已经布局过之后调用（[TvMainActivity.wireHongguoKeyboardFocus] 是在 post 里跑的）。
     */
    fun wireSideLeft(side: List<View>) {
        if(side.isEmpty() || keyRows.isEmpty()) return
        val keyLoc=IntArray(2)
        val chipLoc=IntArray(2)
        side.forEach { chip ->
            if(!chip.isAttachedToWindow) return@forEach
            chip.getLocationOnScreen(chipLoc)
            val centerY=chipLoc[1]+chip.height/2
            var best: View?=null
            var bestDistance=Int.MAX_VALUE
            keyRows.forEach { row ->
                val key=row.lastOrNull() ?: return@forEach
                if(!key.isAttachedToWindow) return@forEach
                key.getLocationOnScreen(keyLoc)
                val distance=Math.abs(keyLoc[1]+key.height/2-centerY)
                if(distance<bestDistance) { bestDistance=distance; best=key }
            }
            best?.let { chip.nextFocusLeftId=it.id }
        }
    }
}

/**
 * 一格键：**没有底板**，只有字或图标；拿到焦点时按按钮的焦点样式点亮，圆角收成**小圆角矩形**。
 *
 * 焦点态就是全站按钮的那一套（亮面板 [TvStyle.lit] + 焦点色细环 + 外发光），
 * 只把圆角从胶囊收到 [FOCUS_RADIUS]：再圆就变成一颗颗药丸，方一点才读得出「格子」。
 *
 * 功能键（图标键）在拿到焦点时，会在图标**下方**显示自己的名称——
 * 图标本身不带文字，不标出来用户只能靠猜。
 */
private class KeyCell(
    activity: TvMainActivity,
    label: String,
    textSize: Float,
    iconRes: Int?,
    action: ()->Unit,
): FrameLayout(activity) {
    /** 焦点底牌：第一次拿到焦点时才建（一屏同一时刻只有一格用它，不必 40 多块一起建）。 */
    private var plate: SoftDrawable?=null

    private val glyph: TextView?=if(iconRes==null) TextView(activity).apply {
        text=label
        designTextSize(textSize)
        setTypeface(typeface,Typeface.BOLD)
        setTextColor(TvStyle.text)
        gravity=Gravity.CENTER
        includeFontPadding=false
        maxLines=1
        ellipsize=TextUtils.TruncateAt.END
    } else null

    private val icon: ImageView?=if(iconRes!=null) ImageView(activity).apply {
        setImageResource(iconRes)
        scaleType=ImageView.ScaleType.FIT_CENTER
        setColorFilter(TvStyle.text,PorterDuff.Mode.SRC_IN)
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    } else null

    /** 功能键的名称：**画在焦点框外面**（格子下沿再往下一点，落在行间距里），常态透明。 */
    private val caption: TextView?=if(iconRes!=null) TextView(activity).apply {
        text=label
        designTextSize(11f)
        setTextColor(TvStyle.text)
        gravity=Gravity.CENTER
        includeFontPadding=false
        maxLines=1
        ellipsize=TextUtils.TruncateAt.END
        alpha=0f
    } else null

    init {
        clipChildren=false; clipToPadding=false
        background=null
        isFocusable=true
        isFocusableInTouchMode=true
        id=View.generateViewId()
        contentDescription=label
        glyph?.let { addView(it,LayoutParams(-1,-1)) }
        icon?.let {
            val side=context.designPx(ICON_SIZE)
            addView(it,LayoutParams(side,side,Gravity.CENTER))
        }
        caption?.let {
            // 底对齐 + 再往下推一段：名称落在**焦点框之外**的下方（行间距里），
            // 框里只有图标，读起来才干净。
            addView(it,LayoutParams(-1,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            it.translationY=activity.dp(CAPTION_OFFSET).toFloat()
        }
        setOnClickListener { action() }
        setOnFocusChangeListener { _,focused -> paint(focused) }
    }

    private fun paint(focused: Boolean) {
        if(focused) {
            val made=plate ?: TvStyle.soft(
                context=context,
                fill=TvStyle.lit,
                radius=FOCUS_RADIUS,
                band=TvStyle.SOFT_BAND,
                style=SoftStyle.RAISED,
                ring=TvStyle.focusRing,
                ringWidth=TvStyle.RING,
                glow=TvStyle.litGlow,
                depth=1.2f
            ).also { plate=it }
            background=made
            glyph?.setTextColor(TvStyle.onLit)
            icon?.setColorFilter(TvStyle.onLit,PorterDuff.Mode.SRC_IN)
        } else {
            background=null
            glyph?.setTextColor(TvStyle.text)
            icon?.setColorFilter(TvStyle.text,PorterDuff.Mode.SRC_IN)
        }
        // 名称画在焦点框**外面**，底下是页面底色而不是亮面板，所以不能跟着用 onLit（近黑），
        // 否则在深色主题上就是一团黑。它始终取键盘字符的颜色（[TvStyle.text]）。
        caption?.setTextColor(TvStyle.text)
        caption?.alpha=if(focused) 1f else 0f
        TvStyle.focus(this,focused)
    }
}
