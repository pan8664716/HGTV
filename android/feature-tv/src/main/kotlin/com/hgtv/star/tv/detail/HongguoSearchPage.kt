// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.text.TextUtils
import android.view.View
import android.widget.EditText
import com.hgtv.star.core.SearchKeyboard
import com.hgtv.star.shared.ui.LoadingDotsView
import com.hgtv.star.tv.list.gridCards
import com.hgtv.star.tv.list.showCatalog

/** 当前搜索页是否用自绘键盘；书库未加载完成时沿用系统键盘页面。 */
fun TvMainActivity.hongguoKeyboardMode(): Boolean =
    libraryReady && library.isLoaded && library.searchKeyboard==SearchKeyboard.HONGGUO

/** 设置页与键盘上的切换：写盘之后立刻重建搜索页。 */
fun TvMainActivity.setSearchKeyboard(useHongguo: Boolean) {
    val next=if(useHongguo) SearchKeyboard.HONGGUO else SearchKeyboard.SYSTEM
    if(library.searchKeyboard==next) return
    library.searchKeyboard=next
    if(tab==TAB_SEARCH) {
        hongguoDraft=if(useHongguo) query else ""
        showCatalog()
    } else showHint("默认输入法已切换为${next.label}")
}

/**
 * 顶栏最左边那枚「切换键盘」圆形入口。
 *
 * 它占的是原来「搜索」标题的位置，但做成一枚圆形图标按钮，左缘与键盘最左侧那一列对齐
 * （负外边距由调用方给，见 `showCatalog`），因此整行看起来是从屏幕左缘起排的，
 * 搜索框也跟着往左平移了一段。
 */
fun TvMainActivity.keyboardSwitchButton(): View {
    val hongguo=hongguoKeyboardMode()
    val label=if(hongguo) "系统键盘" else "HG键盘"
    return IconButton(this,com.hgtv.star.tv.R.drawable.ic_toolbar_keyboard,"切换到$label") {
        setSearchKeyboard(!hongguo)
    }.apply {
        nextFocusUpId=id
    }
}

/** 自绘键盘只输入英文字符；字符写入搜索框后触发联想提示词。 */
fun TvMainActivity.appendHongguoDraft(value: String) {
    val next=(hongguoDraft+value).take(80)
    hongguoDraft=next
    hongguoKeyboard?.draft=next
    syncHongguoInput(next)
    updateHongguoSuggestions(next)
}

fun TvMainActivity.setHongguoDraft(value: String) {
    val next=value.take(80)
    hongguoDraft=next
    hongguoKeyboard?.draft=next
    syncHongguoInput(next)
    updateHongguoSuggestions(next)
}

/**
 * 把草稿同步到顶栏那个输入框。
 *
 * HG键盘模式下那个框**只负责显示**（不可聚焦、不弹系统输入法，见 [searchBar]），
 * 字符由左侧键盘写进来；因此这里必须跟着草稿走，否则用户看到的是一行不会变的字。
 */
private fun TvMainActivity.syncHongguoInput(value: String) {
    val input=searchInput as? EditText ?: return
    if(input.text.toString()==value) return
    input.setText(value)
    input.setSelection(input.text.length)
}

/** 输入框只跟着字符变化；联想词由 [renderHongguoSideList] 铺在中间那一列。 */
private fun TvMainActivity.updateHongguoSuggestions(text: String) {
    if(text.trim().isEmpty()) {
        searchController.scheduleSuggestions("",SUGGEST_COUNT,0L)
        renderHongguoSideList()
        if(searchController.state.value.hot.isEmpty()) searchController.loadHot()
    } else searchController.scheduleSuggestions(text,SUGGEST_COUNT,SUGGEST_DEBOUNCE)
}

fun TvMainActivity.searchHongguoDraft() {
    val clean=hongguoDraft.trim()
    if(clean.isEmpty()) { showSearchHistory(hongguoKeyboard?.view ?: root); return }
    hongguoDraft=clean
    runSearch(clean)
}

/**
 * 中间那一列（1/7）：**有输入时是联想词，没有输入时是本机搜索记录**。
 *
 * 记录为空时退到热搜词——那一列始终有东西可点，不会是一片空白。
 * 这一列不画标题：它只有 1/7 宽，标题占掉的是能放词的行。
 */
fun TvMainActivity.renderHongguoSideList() {
    val state=searchController.state.value
    val typed=hongguoDraft.trim()
    if(typed.isEmpty()) {
        val items=state.emptyPrompts()
        // 没有搜索记录时铺**推荐词**（热搜）：这一列不能空着——它正是用户
        // 「不知道该搜什么」时唯一的入口。热搜还没取过就顺手取一次（loadHot 自带去重）。
        if(items.isEmpty()) searchController.loadHot()
        renderHongguoList(
            items,
            if(state.hotLoading) "正在获取推荐词…" else "暂无记录",
            loading=state.hotLoading,
        )
        return
    }
    val ready=state.query==typed
    renderHongguoList(
        if(ready) state.suggestions.map { it.name } else emptyList(),
        if(ready) "没有匹配的词" else "正在联想…",
        loading=!ready,
    )
}

/**
 * 只重建中间那一列：换关键词、换列表都不动键盘与右侧结果。
 *
 * 字号 14sp：这一列只有 1/7 宽，17sp 时一行放不下几个字、长剧名全是省略号。
 * 拿到焦点时切成跑马灯——放不下的词自己滚一遍，不用等用户去别处确认它到底是什么。
 *
 * [loading] = 正在取（推荐词 / 联想词）：占位改用**三点动画**（与全站其它等待同一个控件），
 * 否则这一列会长时间只有一行静止的小字，看起来像「卡住了」。
 */
private fun TvMainActivity.renderHongguoList(items: List<String>,emptyHint: String,loading: Boolean=false) {
    val column=hongguoHistoryColumn ?: return
    column.removeAllViews()
    if(items.isEmpty()) {
        if(loading) {
            column.addView(
                LoadingDotsView(this).apply { label=emptyHint },
                lp(-1,-2).apply { topMargin=dp(6) },
            )
        } else {
            column.addView(text(emptyHint,13f,TvStyle.muted),lp(-1,-2))
        }
        wireHongguoKeyboardFocus()
        return
    }
    items.take(20).forEachIndexed { index,word ->
        val chip=text(word,14f,TvStyle.text).apply {
            setPadding(dp(4),dp(8),dp(4),dp(8))
            // 单行 + 跑马灯：`isSelected` 必须在拿到焦点那一刻重新置一次，
            // 否则复用的这一枚会沿用上一次的滚动状态、从头不滚。
            setSingleLine(true)
            ellipsize=TextUtils.TruncateAt.END
            isFocusable=true
            isFocusableInTouchMode=true
            id=View.generateViewId()
            setOnClickListener { pickHongguoSideWord(word) }
            setOnFocusChangeListener { view,_ ->
                val focused=view.hasFocus()
                setTextColor(if(focused) TvStyle.focusLine else TvStyle.text)
                isSelected=false
                ellipsize=if(focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
                if(focused) { isSelected=true; scrollTo(0,0) }
            }
            contentDescription="$word，搜索"
        }
        column.addView(chip,lp(-1,-2).apply { if(index>0) topMargin=dp(2) })
    }
    wireHongguoKeyboardFocus()
    // 这一列刚重建时芯片还没挂上窗口，[HongguoKeyboard.wireSideLeft] 要靠屏幕坐标，
    // 那一刻量不出来；等这一帧布局跑完再按真实位置接一次「按左回到同一高度那一格」。
    column.post { if(hongguoHistoryColumn===column) wireHongguoKeyboardFocus() }
}

/** 点中间那一列的一条：草稿换成这个词并立刻搜索。 */
private fun TvMainActivity.pickHongguoSideWord(word: String) {
    hongguoDraft=word
    hongguoKeyboard?.draft=word
    syncHongguoInput(word)
    runSearch(word)
}

/** 结果栅格异步铺好之后，把键盘、中间那一列与结果卡片的左右焦点接起来。 */
fun TvMainActivity.wireHongguoKeyboardFocus() {
    if(!hongguoKeyboardMode() || screen!="catalog" || tab!=TAB_SEARCH) return
    val keyboard=hongguoKeyboard ?: return
    val column=hongguoHistoryColumn ?: return
    val side=(0 until column.childCount).map { column.getChildAt(it) }.filter { it.isFocusable }
    val firstCard=gridCards().firstOrNull()
    val sideFirst=side.firstOrNull()
    // 键盘第一排按上 → 搜索框（方向上最近的一格就是它，不是右上角那排圆形入口）。
    keyboard.wireAcross(side,firstCard,searchInput ?: navEntry())
    // 中间那一列按左：回到与它同一高度的那个字符（不是键盘最左下角那一格）。
    keyboard.wireSideLeft(side)
    // 每一行**最左那一张卡**按左都回中间那一列：网格左缘之外没有别的卡片，
    // 不接的话框架会按几何位置去找，落到上一行的卡上（读起来就是「按左乱跳」）。
    if(sideFirst!=null) {
        gridRows.forEach { row -> row.cards.firstOrNull()?.nextFocusLeftId=sideFirst.id }
    }
    // 顶栏那几件按下时落进键盘第一格（功能行）：搜索框自己就是最上面那一格，
    // 按下去要回到键盘，而不是落到右侧结果的第一张卡上。
    val firstKey=keyboard.firstKeyId
    if(firstKey!=View.NO_ID) {
        listOfNotNull(hongguoSwitch,searchInput).forEach { it.nextFocusDownId=firstKey }
        searchActions.forEach { it.nextFocusDownId=firstKey }
    }
}

/**
 * HG键盘常驻时把系统输入法按下去。
 *
 * 这一页的字符全部由左侧自绘键盘输入，系统键盘一次都不该弹出来：
 * 它既会盖住下半屏的键盘，又会吃掉遥控器 / 外接键盘的按键。除了输入框本身不可聚焦、
 * `showSoftInputOnFocus=false` 之外，这里在页面建好之后再显式收一次——
 * 从「系统输入法」切过来的那一次，系统键盘往往还开着。
 */
fun TvMainActivity.hideSystemKeyboard() {
    cancelSearchInputMethod()
    val imm=getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    imm.hideSoftInputFromWindow(root.windowToken,0)
}
