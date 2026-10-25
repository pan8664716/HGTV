// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.list

import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.hgtv.star.BACK_TO_TOP_ITEMS
import com.hgtv.star.gridColumns
import com.hgtv.star.MainActivity
import com.hgtv.star.R
import com.hgtv.star.dp
import com.hgtv.star.navEntry
import com.hgtv.star.tv.IconButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.compat.attachedToWindow
import com.hgtv.star.compat.laidOut

/**
 * 「回到顶部」浮层：长列表向下翻了很远之后，在**右下角**给一枚快捷按钮。
 *
 * ## 什么时候出现
 *
 * 判据是**位置**，不是「加载了多少」（见 [itemsScrolledPast]）：沿着列表第一列往下数，
 * 已经滚过 [BACK_TO_TOP_ITEMS] 个条目才出现。因此回到顶部它自己就消失——
 * 这也正是需求里「点击后回到顶部…该按钮消失」想要的效果；反过来，只看「已加载条数」
 * 的话这个按钮一旦出现就再也不会消失（长列表永远满足条件），压在卡片上反而碍事。
 *
 * ## 为什么挂在 Activity 的 root 上
 *
 * 页内每一层（`container` / `scroll` / `body`）都在**重建时被整个换掉**（见 `base()` 里的
 * `root.removeAllViews()`），挂在里面的浮层会跟着消失。挂在 `root` 上并每次重建后重挂，
 * 浮层就能跨页存活，且始终停在**屏幕右下角**（不随列表滚动、不进列表的内容线以内）。
 *
 * ## 形状与位置
 *
 * 圆形图标按钮（[IconButton]，与顶部那排入口同一套材质），图标是向上的箭头。
 * 距屏幕两条边各 24dp：按钮自己还有一圈柔光带，圆的**可见边界**落在距边 30dp 处，
 * 正好贴住列表内容线外侧——不与卡片重叠，又明确属于「屏幕右下角」。
 *
 * ## 焦点：只有一条路进来
 *
 * 电视上没有「点一下」这回事，浮层必须能用方向键走到。规则是**唯一**的一条：
 * 焦点在**当前屏幕最后一行的最后一张卡**上时，按**右键**选中它。
 * 「最后一张卡」按**条目**算，不看它是不是贴着右边——最后一行不满时它就停在列中间。
 * 反方向（浮层的上 / 左）回到用户离开列表时的那张卡（见 [backToTopReturn]）。
 * 浮层自己不往别处指，因此它不会插进页面原本的方向顺序里。
 */

/** 挂上浮层（页面重建之后调一次）。同一枚控件复用，只是重新挂回 [MainActivity.root]。 */
internal fun MainActivity.attachBackToTop() {
    // 圆形图标按钮：48dp 的圆盘 + 一圈柔光带（[IconButton] 自己撑到 60dp），图标用箭头。
    val view=backToTopView ?: IconButton(this,R.drawable.ic_arrow_up,"回到顶部") { backToTop() }.also { backToTopView=it }
    if(view.parent!==root) {
        // 位置不用边距/重力：它要对齐「最右边那张卡」，卡片的位置随滚动变化，
        // 因此固定一个满尺寸的外框、每次用 translation 摆过去（translation 不触发 layout，
        // 滚动时跟着卡片走不会掉帧）。
        val size=dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)
        root.addView(view,FrameLayout.LayoutParams(size,size,Gravity.TOP or Gravity.START))
    }
    // 新页面一定是从顶部开始的：先按「收着」摆好，随后的滚动回调再按真实位置决定显隐。
    backToTopShown=false
    backToTopLayoutWaits=0
    backToTopReturn=View.NO_ID
    view.visibility=View.GONE
    view.nextFocusLeftId=view.id
    view.nextFocusUpId=view.id
    view.nextFocusRightId=view.id
    view.nextFocusDownId=view.id
}

/**
 * 按当前位置决定浮层显隐。滚动、位置恢复、页面重建之后都要调一次。
 *
 * 这里是**纯显隐**，不动焦点：唯一会让浮层**握着焦点**时消失的路径是用户按了它
 * （[backToTop] 自己负责把焦点交给第一张卡）。
 */
internal fun MainActivity.updateBackToTop() {
    val view=backToTopView ?: return
    val show=itemsScrolledPast()>=BACK_TO_TOP_ITEMS
    if(show!=backToTopShown) {
        backToTopShown=show
        if(show) {
            // 记下用户离开列表时的那一张卡：浮层的「上 / 左」按回那里，而不是被带到列表最底。
            backToTopReturn=root.findFocus()?.takeIf { it!==view && it.attachedToWindow }?.id ?: backToTopReturn
            wireBackToTop()
            view.visibility=View.VISIBLE
        } else {
            val held=view.hasFocus()
            view.visibility=View.GONE
            // 兜底：万一焦点真的还压在一枚已经隐藏的按钮上，就交给列表第一张卡。
            if(held) firstCardView()?.requestFocus()
        }
    }
    // 位置跟着「最右边那张卡」走：滚动时它的底边在动，这里每次滚动都重摆一遍。
    //
    // **这一句不能放进上面那个 if 里**：显隐状态没变时（例如恢复滚动位置之后用户不动）
    // 也必须重摆。早先它在「状态没变」的提前返回之后，于是位置一旦摆错就再没有机会纠正——
    // 表现就是「回到顶部」跑到右上角。
    if(show) positionBackToTop()
}

/** 浮层的可见圆盘与屏幕下沿至少留出的距离（dp）：它要待在**右下角**，但不贴边。 */
private const val BACK_TO_TOP_EDGE_GAP = 16

/**
 * 把浮层摆在**屏幕右下角**：
 *
 * * 水平 = **屏幕右缘与「最右边那张卡」右边框的正中**（这一条是定死的）；
 * * 垂直 = 「屏幕最下面那一行卡片」的下边框；那一行被屏幕截住时取屏幕下沿
 *   （只留 [BACK_TO_TOP_EDGE_GAP] 的余量）——**因此它始终待在右下角**。
 *
 * 卡片坐标直接从 View 的窗口坐标取——滚动、换页、不同分辨率都不用另算一套几何。
 */
internal fun MainActivity.positionBackToTop(force: Boolean=false) {
    val view=backToTopView ?: return
    if(!backToTopShown) return
    val card=backToTopAnchorCard()
    // 卡片刚物化 / 刚从回收池里挂回来时**还没走过布局**：宽高可能还是上一次的，
    // 但 `getLocationInWindow` 全是 0。按它算出来的垂直位置会被夹到 0，水平位置又被
    // 卡片宽度推到屏幕右缘——浮层就落在**右上角**。这时不能拿错坐标硬摆，也不能等下一次
    // 滚动：恢复位置之后用户不动，就再也没有回调来纠正它。
    // 先把自己藏起来（避免在错的位置闪一帧），挂一次布局回调，布局完成立刻重摆。
    val ready=card!=null && card.laidOut && card.width>0 && card.height>0 &&
        root.width>0 && root.height>0
    if(!ready && !force) {
        // 不藏一枚正握着焦点的按钮：那会让焦点停在一个看不见的控件上（等布局完成就会摆好）。
        if(view.visibility==View.VISIBLE && !view.hasFocus()) view.visibility=View.INVISIBLE
        settleBackToTopAfterLayout()
        return
    }
    // 窗口尺寸拿不到时没有可算的几何，保持隐藏，等下一次布局。
    if(root.width<=0 || root.height<=0) return
    val band=dp(TvStyle.SOFT_BAND)
    val size=dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)
    val circle=size-2*band
    val gap=dp(BACK_TO_TOP_EDGE_GAP)
    if(card==null || !card.laidOut || card.width<=0 || card.height<=0) {
        // 兜底（重试几次仍然没有可用的卡片坐标）：直接落在右下角，
        // 至少不会出现在右上角；下一次滚动 / 布局会把它摆回精确位置。
        view.translationX=(root.width-size-gap).toFloat()
        view.translationY=(root.height-size-gap).toFloat()
        view.visibility=View.VISIBLE
        return
    }
    val loc=IntArray(2); card.getLocationInWindow(loc)
    val rootLoc=IntArray(2); root.getLocationInWindow(rootLoc)
    // 卡片自己的柔光带是透明内边距，视觉上的右边框 / 下边框要把它扣掉。
    val right=loc[0]-rootLoc[0]+card.width-band
    val cardBottom=loc[1]-rootLoc[1]+card.height-band
    // 水平：屏幕右缘与卡片右边框的中点（圆盘正好落在卡片右侧的留白里）。
    // 卡片右边框理论上不会越过屏幕右缘；万一越过了（首页剧架横向滚过、卡片还没回收），
    // 中点会算到屏幕外，圆盘有一半看不见——这里再把结果夹回「贴边留 [gap]」的范围里。
    val x=((root.width+right)/2f)-size/2f
    val minX=(gap-band).toFloat()
    val maxX=(root.width-gap-size+band).toFloat()
    view.translationX=x.coerceIn(minX,maxOf(minX,maxX))
    // 垂直：圆盘的**可见底边**对齐那一行的下边框；被屏幕截住时夹在屏幕内。
    // 上界在极小屏上可能小于 0（root.height 比圆盘还小）：coerceIn 会因 min>max 抛异常，
    // 因此先把上界夹到 0。
    val circleTop=(cardBottom-circle).coerceIn(0,(root.height-circle-gap).coerceAtLeast(0))
    view.translationY=(circleTop-band).toFloat()
    // 坐标已经对了才显示：延迟定位期间它是 INVISIBLE 的（见上面那段）。
    view.visibility=View.VISIBLE
    backToTopLayoutWaits=0
}

/**
 * 等**这一次布局**跑完再摆一次位置（一次性的全局布局回调）。
 *
 * 进页面 / 恢复滚动位置的那一帧里，卡片是刚物化出来的，`getLocationInWindow` 还全是 0；
 * 按它摆出来的浮层在右上角。布局完成后卡片才有真实坐标，这里补上那一次重摆。
 * [MainActivity.backToTopLayoutPending] 保证同时只挂一个回调（滚动回调会反复进来）。
 */
private fun MainActivity.settleBackToTopAfterLayout() {
    if(backToTopLayoutPending) return
    backToTopLayoutPending=true
    root.viewTreeObserver.addOnGlobalLayoutListener(object: ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            val observer=root.viewTreeObserver
            if(observer.isAlive) observer.removeOnGlobalLayoutListener(this)
            backToTopLayoutPending=false
            // 布局期间页面可能已经换掉（root 被清空）：那就按新页面重新决定显隐。
            updateBackToTop()
            // 布局跑过了仍然没有可用的卡片坐标（窗口还没量到 / 卡片还没挂上）：
            // 重试两次之后就按右下角兜底摆好，不让它一直藏着一个本应可见的按钮。
            if(backToTopShown && backToTopView?.visibility!=View.VISIBLE) {
                backToTopLayoutWaits++
                if(backToTopLayoutWaits>=2) { backToTopLayoutWaits=0; positionBackToTop(force=true) }
            }
        }
    })
}

/**
 * 「最右边那张卡」= 屏幕**最下面那一行**的最右一张卡（首页是最后一栏；栅格最后一行不满时
 * 按「有数据的最后一张」取，不会取到空槽）。取不到时返回 null（浮层保持上一次的位置）。
 */
private fun MainActivity.backToTopAnchorCard(): View? =
    homeScreen?.lastVisibleRowCard() ?: gridBottomRowCard()

/**
 * 栅格里「屏幕最下面那一行」的最后一张卡（行号按视口底边落点算，见 [gridBottomVisibleRow]）。
 *
 * **不再往上找满行**：早先为了「按钮的右边框始终贴着内容线」，底部那一行不满时会回溯到
 * 上面最近的一行满行——于是列表尾巴只剩两三张卡时，按钮会跑到**上一行**的末尾，
 * 与「它在屏幕右下角、跟着最后一行」这个直觉对不上（用户反馈的就是这一条）。
 * 现在一律取底部这一行里**最后一张有数据的卡**：不满行时它停在列中间，
 * 按钮就跟着落在那一行的末尾（水平位置仍是「屏幕右缘与这张卡右边框的中点」）。
 *
 * 取不到时返回 null（浮层保持上一次的位置）。
 */
private fun MainActivity.gridBottomRowCard(): View? {
    val bottom=gridBottomVisibleRow()
    if(bottom<0) return null
    val holder=gridRows.firstOrNull { it.index==bottom } ?: return null
    return holder.cards.lastOrNull { it.series!=null }?.takeIf { it.attachedToWindow }
}

/**
 * 「沿着第一列往下数，已经滚过多少个条目」。
 *
 * 栅格：滚过的整行数 × 列数；首页剧架：整行已经完全滚出视口顶部的那些卡片数之和。
 * 按**条目**而不是像素或行数——六列栅格一行 6 个（[BACK_TO_TOP_ITEMS] = 50 大约是九行），
 * 换成别的列数 / 海报尺寸都不用改这个门槛。
 */
internal fun MainActivity.itemsScrolledPast(): Int {
    homeScreen?.let { home -> return home.cardsScrolledPast() }
    val scroll=catalogScroll ?: return 0
    val stride=rowStride()
    if(stride<=0) return 0
    return (scroll.scrollY/stride)*gridColumns
}

/** 列表第一张卡：首页第一栏第一张，其余页面是第一行第一张（窗口没物化时返回 null）。 */
internal fun MainActivity.firstCardView(): View? =
    homeScreen?.firstCard() ?: gridRows.minByOrNull { it.index }?.cards?.firstOrNull()

/**
 * 把浮层接进焦点链（每次它要显示时重算一遍）。
 *
 * 「上 / 左」的落点优先用**走到浮层之前那一张卡**：用户是顺着列表底部下来的，
 * 按回去就该回到原处，而不是被带到列表最底那一行。
 */
internal fun MainActivity.wireBackToTop() {
    val view=backToTopView ?: return
    val tracked=backToTopReturn.takeIf { it!=View.NO_ID }
        ?.let { id -> root.findViewById<View>(id) }
        ?.takeIf { it!==view && it.attachedToWindow }
    val bottom=moreButton?.takeIf { it.isFocusable }
    // 兜底顺序：走到浮层之前那一张卡 → 底部按钮行 → 窗口里最后一行第一张 → 窗口里第一行第一张
    // → 首页第一栏第一张 → 顶部入口。**一定要落到某个控件上**：浮层显示的时机就是
    // 「列表已经滚过 50 个条目」，那时窗口里必然有卡片；万一全部落空，焦点会停在浮层上出不去。
    val up=tracked ?: bottom
        ?: gridRows.maxByOrNull { it.index }?.cards?.firstOrNull()
        ?: gridRows.minByOrNull { it.index }?.cards?.firstOrNull()
        ?: homeScreen?.firstCard()
        ?: navEntry()
    view.nextFocusLeftId=up?.id ?: view.id
    view.nextFocusUpId=up?.id ?: view.id
    // 只接**回程**：进入浮层的那一下由卡片自己的右键处理（见 SeriesGrid.wireGridCard /
    // HomeScreen 的按键回调）——浮层只有「最后一行最后一张卡按右键」这一条入口，
    // 别的控件不许把它指出来，否则它就会插进页面原本的方向顺序里。
}

/**
 * 回到顶部：滚动位置归零、页头展开、焦点交给第一张卡、浮层收起。
 *
 * 这是**用户显式动作**，因此可以直接指定落点（全站「不主动抢焦点」那条原则里
 * 「用户自己的动作可以指定焦点落点」的那一类）。
 *
 * 位置是**直接跳**而不是平滑滚动：列表是窗口化栅格（视口外的行根本没建出来），
 * 平滑滚动一路经过几百行等于让窗口把每一行都物化再回收一遍；4.4 与低端盒子上
 * 那几秒的画面会一路抖动。跳过去之后再按新位置同步窗口，代价与「刚开始在顶部」一样。
 */
internal fun MainActivity.backToTop() {
    val home=homeScreen
    if(home!=null) {
        home.scrollToTop()
        listScrollY=0
        setTopAreaHidden(false,animate=false)
        updateBackToTop()
        home.firstCard()?.requestFocus()
        return
    }
    val scroll=catalogScroll ?: return
    scroll.scrollTo(0,0)
    listScrollY=0
    setTopAreaHidden(false,animate=false)
    syncGridWindow(force=true,focusIndex=0)
    updateBackToTop()
    focusGridIndex(0,true)
    syncHeaderState()
}
