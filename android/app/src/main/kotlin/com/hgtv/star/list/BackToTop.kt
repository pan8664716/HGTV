// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.list

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.hgtv.star.MainActivity
import com.hgtv.star.R
import com.hgtv.star.core.BACK_TO_TOP_ALWAYS
import com.hgtv.star.core.BACK_TO_TOP_NEVER
import com.hgtv.star.core.BACK_TO_TOP_ROWS5
import com.hgtv.star.core.BACK_TO_TOP_ROWS8
import com.hgtv.star.dp
import com.hgtv.star.navEntry
import com.hgtv.star.tv.IconButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.compat.attachedToWindow

/**
 * 「回到顶部」浮层：栅格列表页上的一枚快捷按钮，固定在**右下角**。
 *
 * ## 什么时候出现
 *
 * 只在**栅格列表页**（榜单 / 分类 / 集合 / 搜索 / 我的，即 [catalogScroll] 存在且
 * 首页剧架 [homeScreen] 不存在）出现；这些页面上它秀不秀，由设置页「布局启动 → 返回顶部」
 * 的四档决定（见 [backToTopVisible]）：
 *
 * - **始终显示**（出厂档）：一进列表就看得见，滚到哪都一样；
 * - **下拉 5 行 / 8 行后显示**：列表滚过 5 / 8 行的高度才出现（按 [rowStride] 算行高，
 *   与分辨率、每行几张卡无关）；滚回顶部它自己收起；
 * - **始终不显示**：整个列表页都不挂这枚浮层（焦点也不会多出一条右键出口）。
 *
 * 早先它要「滚过若干个条目」才出现、回到顶部又自己收起，而且阈值写死在代码里；
 * 现在阈值交给用户，默认仍是「始终显示」。
 *
 * **首页不显示**：首页是剧架（横向滑 + 纵向滚），回到顶部没有意义，按钮还会压在
 * 最后一栏的右下角挡住卡片。详情页 / 播放页 / 推荐页同理没有栅格，也不显示
 * （它们都不经过 [attachBackToTop] 的调用点 `base()`，或者那时列表引用已被
 * [releaseCatalogViews] 清空）。
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
 * 位置是**固定**的：整枚按钮距屏幕右沿、下沿各 [BACK_TO_TOP_EDGE_GAP]，落在内容线右侧
 * 那条 78dp 的留白里（卡片右边框在 `屏幕宽 - pageInset()`），不压卡片、也不随滚动移动。
 *
 * ## 焦点：只有一条路进来
 *
 * 电视上没有「点一下」这回事，浮层必须能用方向键走到。规则是**唯一**的一条：
 * 焦点在**本行最后一张卡**上时，按**右键**选中它。
 * 「最后一张卡」按**条目**算，不看它是不是贴着右边——最后一行不满时它就停在列中间。
 * 不限定「必须是屏幕最底下那一行」：本行最后一张卡按右键本来什么都不做
 * （`nextFocusRightId` 指回自己），把这一下交给浮层不会抢走任何已有手势；
 * 而按几何判「底行」在实机上并不可靠（列表底部有留白、底行下面露着一条细边，
 * 用户停在看得见的最后一张卡上按右键却毫无反应）。
 * 反方向（浮层的上 / 左）回到用户离开列表时的那张卡（见 [backToTopReturn]）。
 * 浮层自己不往别处指，因此它不会插进页面原本的方向顺序里。
 */

/** 挂上浮层（页面重建之后调一次）。同一枚控件复用，只是重新挂回 [MainActivity.root]。 */
internal fun MainActivity.attachBackToTop() {
    // 圆形图标按钮：48dp 的圆盘 + 一圈柔光带（[IconButton] 自己撑到 60dp），图标用箭头。
    val view=backToTopView ?: IconButton(this,R.drawable.ic_arrow_up,"回到顶部") { backToTop() }.also { backToTopView=it }
    if(view.parent!==root) {
        // 位置不用边距 / 重力：外框放在左上角，实际位置每次用 translation 摆过去。
        // translation 不触发 layout，摆位只是两次赋值，不会让列表重排。
        val size=dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)
        root.addView(view,FrameLayout.LayoutParams(size,size,Gravity.TOP or Gravity.START))
    }
    // 新页面一定是从顶部开始的：先按「收着」摆好，随后的滚动回调再按真实位置决定显隐。
    backToTopShown=false
    backToTopReturn=View.NO_ID
    view.visibility=View.GONE
    view.nextFocusLeftId=view.id
    view.nextFocusUpId=view.id
    view.nextFocusRightId=view.id
    view.nextFocusDownId=view.id
    // **绝不在这里关 isFocusable**：`View.setFocusable(false)` 会连带清掉
    // `FOCUSABLE_IN_TOUCH_MODE`（AOSP 里那句 `setFlags(0, FOCUSABLE_IN_TOUCH_MODE)`），
    // 之后在**触屏设备**上再 `setFocusable(true)` 也不会把它加回来——
    // 而 `requestFocus()` 在触摸模式下要求这一位，否则直接返回 false。
    // 表现就是「手机上怎么按都进不去」；电视盒子（非触摸）反而不受影响。
    // 浮层该不该显示只由 visibility 决定（GONE 的控件本来也拿不到焦点）。
    //
    // **这里不再「布局一落地就显示」**（早先是 `root.post { updateBackToTop() }`）。
    //
    // 那个 post 排在内容区自己的 `scroll.post` **之前**，于是列表页刚重建完的那一帧里，
    // 整棵树还没有任何控件握着焦点（`base()` 把 root 清空过），而它这一下
    // GONE→VISIBLE 正好把框架的「首个可聚焦控件」抢到自己身上——接着内容区的
    // `ensureFocus` 一看 `focusHeld()` 已经是 true，就不再给第一张卡落焦点了。
    //
    // 结果就是用户看到的：进榜单页，右下角的回到顶部按钮是亮的（它握着焦点），
    // 而它的 `nextFocusDownId` 指回自己，所以按向下**毫无反应**。
    //
    // 显隐改由**内容落地之后**的 [updateBackToTop] 决定（`seriesList` 的 `scroll.post`、
    // 空页分支、首页的 `onSettled`），那时焦点已经落在内容上，浮层再怎么露面也抢不走。
}

/**
 * 按当前页面与设置页那一档（[BACK_TO_TOP_MODES]）决定浮层显隐。
 * 滚动、位置恢复、页面重建之后都要调一次。
 *
 * 这里是**纯显隐**，不动焦点：唯一会让浮层**握着焦点**时消失的两条路径是换页
 * （[backToTop] 自己负责把焦点交给第一张卡）与「下拉 N 行」档位下滚回顶部
 * （那时焦点本来就该跟着列表走，兜底交给第一张卡）。
 */
internal fun MainActivity.updateBackToTop() {
    val view=backToTopView ?: return
    // **必须已经有别的控件握着焦点才允许露面。**
    //
    // 这是「进榜单页，按钮自己亮着、按向下没反应」那条反馈的正面修复：整页重建时
    // `base()` 把 root 清空过，这一帧里没有任何控件握焦点；此时把浮层从 GONE 改成 VISIBLE，
    // 框架的「首个可聚焦控件」就会落到它身上（`focusableViewAvailable`），随后内容区
    // `ensureFocus` 一看焦点已经有主，就不再给第一张卡落点——用户于是看到一个亮着的
    // 回到顶部按钮，而它的下键指回自己，按下去毫无反应。
    //
    // 判据用「此刻有没有焦点」而不是「第几帧」：不管 `updateBackToTop` 的调用点排在
    // 焦点落地之前还是之后都成立，浮层永远只在内容已经有人握着焦点时才出现。
    val show=homeScreen==null && catalogScroll!=null && backToTopVisible() && root.findFocus()!=null
    if(show!=backToTopShown) {
        backToTopShown=show
        if(show) {
            // 记下用户离开列表时的那一张卡：浮层的「上 / 左」按回那里，而不是被带到列表最底。
            backToTopReturn=root.findFocus()?.takeIf { it!==view && it.attachedToWindow }?.id ?: backToTopReturn
            wireBackToTop()
        } else {
            val held=view.hasFocus()
            view.visibility=View.GONE
            // 兜底：万一焦点真的还压在一枚已经隐藏的按钮上，就交给列表第一张卡。
            if(held) firstCardView()?.requestFocus()
        }
    }
    // 这里**不动 isFocusable**（原因见 [attachBackToTop]）：它就是建出来时的「可聚焦 +
    // 触摸模式可聚焦」，靠 visibility 表达显隐。框架自己的方向键搜索不会把它抢走——
    // 栅格卡的 nextFocusRightId 都指回自己（见 `wireGridFocus`），页头 / 底部按钮行
    // 各有显式落点；进入它只有 [backToTopTarget] 那一条路。
    // 位置只由屏幕尺寸决定（见 [positionBackToTop]）：滚动帧里这一次调用算出来的是同一对数，
    // 写回去不触发重绘。**显示也交给它**：窗口还没量到时不显示，免得在左上角闪一帧。
    if(show) positionBackToTop()
}

/**
 * 设置页那一档要不要在**当前滚动位置**露出浮层。
 *
 * 判据是「列表已经滚过几行」：阈值 = N × [rowStride]（一行的像素高度，含行间距）。
 * 不看具体卡片、也不看屏幕高度——一屏放几行随分辨率变，用户说的是「往下翻了几行」。
 * 「始终显示」直接 true；「始终不显示」直接 false；列表还没建出来时按「不显示」处理。
 */
internal fun MainActivity.backToTopVisible(): Boolean {
    val mode=if(library.isLoaded) library.backToTop else BACK_TO_TOP_ALWAYS
    val rows=when(mode) {
        BACK_TO_TOP_NEVER -> return false
        BACK_TO_TOP_ROWS5 -> 5
        BACK_TO_TOP_ROWS8 -> 8
        else -> 0
    }
    if(rows<=0) return true
    val y=catalogScroll?.scrollY ?: return false
    return y>=rows*rowStride()
}

/** 浮层的可见圆盘与屏幕下沿至少留出的距离（dp）：它要待在**右下角**，但不贴边。 */
private const val BACK_TO_TOP_EDGE_GAP = 16

/**
 * 把浮层摆在**屏幕右下角**（固定位置，与滚动无关）。
 *
 * 横向落在内容线右侧的留白里：卡片右边框在 `屏幕宽 - pageInset()`（78dp），
 * 这里只占最右边 `[BACK_TO_TOP_EDGE_GAP]` + 圆盘宽（48dp），整枚按钮都在那条留白里，
 * 不会压住任何一张卡。纵向同样只留 [BACK_TO_TOP_EDGE_GAP] 的余量。
 *
 * ## 为什么不再跟着「最后一行卡片」走
 *
 * 早先它的纵向位置对齐「屏幕最下面那一行卡片」的下边框。滚动时那一行每跨过一行就换一次，
 * 按钮于是「跟着这一行往上滑、换行时又跳回下一行的下边框」——一屏滚下来要跳十几次，
 * 看起来就是一枚不停闪烁、像在被反复重建的按钮（用户反馈的原话）。
 * 而且那一行的卡片是窗口化滚动里被回收 / 复用的，锚点卡时不时「挂着但还没量到」，
 * 那一帧按它算出来的坐标是错的（会被夹到屏幕顶），于是还要再藏一帧等布局——
 * 闪烁就是这么来的。
 *
 * 现在位置是屏幕尺寸的纯函数：滚动、翻页、恢复位置、换分辨率都不会让它在两帧之间跳，
 * 也不再需要等卡片布局、不需要「先藏起来再摆」那一套。
 */
internal fun MainActivity.positionBackToTop() {
    val view=backToTopView ?: return
    if(!backToTopShown) return
    // 窗口尺寸还没量到（第一帧）：不在这里摆，等下一次滚动 / 布局回调再来一次
    // （`attachBackToTop` 的 post、`root` 的布局回调都会调它）。
    if(root.width<=0 || root.height<=0) return
    val size=dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)
    val gap=dp(BACK_TO_TOP_EDGE_GAP)
    // 极小屏（root 比按钮还小）时夹到 0，不出现负数坐标。
    view.translationX=(root.width-size-gap).coerceAtLeast(0).toFloat()
    view.translationY=(root.height-size-gap).coerceAtLeast(0).toFloat()
    view.visibility=View.VISIBLE
}

/**
 * 浮层的方向键入口：当前是栅格列表页、且浮层**正显示着**时返回它，否则 null。
 *
 * 显隐已经由 [backToTopVisible] 算过（`visibility` 就是结论），这里只认那一份：
 * 「下拉 N 行」档还没滚到位时返回 null，卡片右键落回原来的行为（进详情页）；
 * 「始终不显示」档同理，列表页上不会多出一条右键出口。
 */
internal fun MainActivity.backToTopTarget(): View? {
    val view=backToTopView ?: return null
    if(view.visibility!=View.VISIBLE) return null
    if(catalogScroll==null) return null
    // 真正要进去的这一刻才接焦点链：如果每次滚动都重算，「上 / 左」的落点
    // 要在滚动帧里做一次 findViewById（窗口里几百个 View），低端盒子上是白花的开销。
    // 焦点一变全局监听就会更新 [MainActivity.backToTopReturn]，因此这里取到的总是最新的那张卡。
    wireBackToTop()
    return view
}

/** 列表第一张卡：栅格第一行第一张（窗口没物化时返回 null）。 */
internal fun MainActivity.firstCardView(): View? =
    gridRows.minByOrNull { it.index }?.cards?.firstOrNull()

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
    // → 顶部入口。**一定要落到某个控件上**：列表页上窗口里总归有卡片；
    // 万一全部落空（空页），焦点会停在浮层上出不去，因此最后退到顶部入口。
    val up=tracked ?: bottom
        ?: gridRows.maxByOrNull { it.index }?.cards?.firstOrNull()
        ?: gridRows.minByOrNull { it.index }?.cards?.firstOrNull()
        ?: navEntry()
    view.nextFocusLeftId=up?.id ?: view.id
    view.nextFocusUpId=up?.id ?: view.id
    // 下键也回内容区，**不再指回自己**。
    //
    // 指回自己等于「按了没反应」：浮层是浮在右下角的一枚按钮，用户看不到任何「到底了」的
    // 提示，只会以为遥控器失灵。浮层本身就贴着屏幕下沿，从这里往下本来也没有别的去处，
    // 因此下键回到内容区（与上 / 左同一个落点）比原地不动更符合直觉：
    // 万一把焦点带到这里，按一下就回来了。
    view.nextFocusDownId=up?.id ?: view.id
    // 只接**回程**：进入浮层的那一下由卡片自己的右键处理（见 SeriesGrid.wireGridCard）——
    // 浮层只有「本行最后一张卡按右键」这一条入口，
    // 别的控件不许把它指出来，否则它就会插进页面原本的方向顺序里。
}

/**
 * 回到顶部：滚动位置归零、页头展开、焦点交给第一张卡。
 * 浮层按当前档位重算一次显隐：「始终显示」档原地不动；「下拉 N 行」档此时回到顶部、
 * 阈值不再满足，于是自己收起（焦点已经交给第一张卡，不会压在一枚隐藏的按钮上）。
 *
 * 这是**用户显式动作**，因此可以直接指定落点（全站「不主动抢焦点」那条原则里
 * 「用户自己的动作可以指定焦点落点」的那一类）。
 *
 * 位置是**直接跳**而不是平滑滚动：列表是窗口化栅格（视口外的行根本没建出来），
 * 平滑滚动一路经过几百行等于让窗口把每一行都物化再回收一遍；4.4 与低端盒子上
 * 那几秒的画面会一路抖动。跳过去之后再按新位置同步窗口，代价与「刚开始在顶部」一样。
 */
internal fun MainActivity.backToTop() {
    val scroll=catalogScroll ?: return
    scroll.scrollTo(0,0)
    listScrollY=0
    setTopAreaHidden(false,animate=false)
    syncGridWindow(force=true,focusIndex=0)
    // 先把焦点交给第一张卡，再按新位置重算显隐：「下拉 N 行」档下回到顶部就不再满足阈值，
    // 浮层会收起；若顺序反过来，收起那一刻焦点还压在浮层上，而这时窗口刚重建、
    // [firstCardView] 可能一张卡都还没物化，焦点就悬在一枚 GONE 控件上了。
    focusGridIndex(0,true)
    updateBackToTop()
    syncHeaderState()
}
