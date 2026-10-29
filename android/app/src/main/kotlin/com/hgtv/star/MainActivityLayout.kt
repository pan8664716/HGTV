// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.graphics.Color
import android.view.*
import android.widget.*
import com.hgtv.star.core.*
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.Soft
import com.hgtv.star.tv.SoftStyle
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.TvTheme
import com.hgtv.star.tv.TvThemeStore
import com.hgtv.star.tv.designPx
import com.hgtv.star.tv.designScale
import com.hgtv.star.tv.designTextSize
import com.hgtv.star.tv.focusOverflow
import com.hgtv.star.list.showCatalog
import com.hgtv.star.compat.activityManager
import com.hgtv.star.compat.lowEndTv
import com.hgtv.star.compat.totalMemoryMb

// 本文件是从 MainActivity.kt 拆出来的「尺寸换算、控件工厂（按钮 / 文本 / 行 / 页骨架）与窗口背景、主题切换」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

/**
 * 设计 dp → 像素。
 *
 * 所有页面尺寸都按 1280dp 宽的设计画布写，屏幕更窄时由 [designPx] 统一等比缩小
 * （见 `tv/TvMetrics.kt`）。这里不再直接乘 density，否则边距 / 按钮 / 海报各缩各的。
 */
internal fun MainActivity.dp(value: Number)=designPx(value)
/**
 * 当前画布宽度，单位是**设计单位**（屏宽像素 ÷ 设计缩放）。
 *
 * 整块版面是按比例缩放的，因此这里在 1280×720 的基准屏上等于 1280，
 * 在任何「宽度受限」的分辨率上也仍然是 1280。页面里凡是「信息列还放不放得下
 * 四个按钮」「页头入口要不要挪到单独一行」这类判断都用它：
 * 阈值就是设计稿上的数，换了分辨率、换了 dpi 都不会漂移。
 */
internal fun MainActivity.widthDp(): Float {
    val metrics=resources.displayMetrics
    val scale=designScale()
    return if(scale<=0f) metrics.widthPixels.toFloat() else metrics.widthPixels/scale
}
/**
 * 页面左右安全边距，对齐爆米花 77.5dp 栅格（1280dp 画布上左右各 78dp）。
 * 剧架按同一数值计算行内可用宽度，避免整行填不满。
 */
internal fun MainActivity.pageInset()=dp(78)

/**
 * 页面容器比内容边距再早开始 [TvStyle.FOCUS_BLEED]。
 *
 * 容器的边界就是裁剪边界：内容从 78dp 开始，容器从 71dp 开始，滚动容器再把这个余量补回来，
 * 于是最左 / 最右那一张卡片（或按钮）放大后的焦点描边仍有地方画，不会被容器裁掉。
 * 内容本身的位置完全不变。
 */
internal fun MainActivity.pageBleed()=dp(TvStyle.FOCUS_BLEED)

/**
 * 按「设备是否低配」与「用户是否要求减少动画」重算一次性能档位。
 *
 * 两件事必须由这一个函数统一决定，否则会出现半套降级（动画关了、柔光位图还全画）：
 *
 * | 项 | 关掉之后 |
 * | --- | --- |
 * | `TvStyle.animate` | 焦点不做缩放；跑马灯、跨栏平滑滚动、页头「焦点行」判据一并停掉 |
 * | `Soft.shadowBitmaps` | 只有焦点卡画预渲染柔光位图（一屏一张，而不是十八张） |
 * | `Soft.vignette` | 不画海报井的径向暗角 |
 * | `Soft.quality` | 柔光收紧一档（模糊半径小、生成更快） |
 *
 * 判据：
 * - `lowEndTv()`：设备自己就是 2GB 档电视（见 `compat/Platform.kt`）——**强制**开，与用户偏好无关；
 * - `library.reduceAnim`：非低配用户手动打开的那一项（见设置页「减少动画」）。
 *
 * 必须在 `library` 载入完成之后调用一次（`loadLibrary` 的回调里），
 * 否则读到的 `reduceAnim` 永远是默认值；`onCreate` 里那一次只按设备判据先摆好第一帧。
 */
internal fun MainActivity.applyPerformanceProfile() {
    val lowEnd=lowEndTv()
    val reduce=lowEnd || (libraryReady && library.isLoaded && library.reduceAnim)
    TvStyle.animate=!reduce
    Soft.shadowBitmaps=!reduce
    Soft.vignette=!reduce
    Soft.quality=if(reduce) .55f else 1f
    // 档位判据只在一处算、只打一行日志：用户回报「4GB 上开关不出现」时，
    // `adb logcat -s HGTV-PERF` 就能看出是内存条判错还是装的是旧包。
    android.util.Log.i(
        "HGTV-PERF",
        "lowEnd=$lowEnd totalMb=${totalMemoryMb()} heapMb=${activityManager()?.memoryClass} " +
            "userReduce=${libraryReady && library.isLoaded && library.reduceAnim} reduce=$reduce"
    )
}
internal fun MainActivity.lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)

/**
 * 在整棵视图树里按 `tag` 找第一枚可聚焦控件。
 *
 * 只用于「整页重建后把焦点落回原来那一行」这种场合（见 `settingsPendingFocus`）：
 * 重建会换掉所有 View 实例，原来的引用全部失效，`tag` 是唯一稳定的标识。
 */
internal fun findTagged(view: View,tag: String): View? {
    if(view.tag==tag) return view
    if(view !is ViewGroup) return null
    for(index in 0 until view.childCount) {
        findTagged(view.getChildAt(index),tag)?.let { return it }
    }
    return null
}
/**
 * 内容区一行卡片的可用宽度（就是栅格铺满一行的宽度）。
 *
 * 页面左右各让出 [pageInset]，再各留 [TvStyle.FOCUS_BLEED] 的一半余量，
 * 因此一行 6 张卡的左右边界就是「内容线 → 内容线 + 这个宽度」。
 * 分类页二级分类那一行也按它定宽，两块区域因此左右对齐。
 */
internal fun MainActivity.gridContentWidth()=(resources.displayMetrics.widthPixels-2*pageInset()-2*dp(2)).coerceAtLeast(dp(320))
/**
 * 分类页筛选行的宽度：**内容线 → 「我的」圆形按钮可见右缘**。
 *
 * 顶部导航栏最右那枚圆形按钮的可见右缘正好落在 `屏幕宽 - pageInset`（它两侧各有
 * 一条 [TvStyle.SOFT_BAND] 的透明带），所以这里直接用屏幕宽减两侧内容边距；
 * 页面渲染完后再用按钮的实际坐标微调一次（见 `showCatalog` 的分类页分支）。
 */
internal fun MainActivity.categoryFilterWidth()=(resources.displayMetrics.widthPixels-2*pageInset()).coerceAtLeast(dp(320))
/**
 * 新拟态底板：凸起 / 凹陷 / 只描边 / 压图暗角。
 * 参数顺序固定，调用方一律用具名参数，避免把 band 和 radius 写反。
 */
internal fun MainActivity.softPanel(
    fill: Int = TvStyle.surface,
    radius: Int = TvStyle.RADIUS_CARD,
    band: Int = TvStyle.SOFT_BAND,
    style: SoftStyle = SoftStyle.RAISED,
    ring: Int = Color.TRANSPARENT,
    ringWidth: Int = 0,
    glow: Int = Color.TRANSPARENT,
    depth: Float = 1f
) = TvStyle.soft(this,fill,radius,band,style,ring,ringWidth,glow,depth)
internal fun MainActivity.text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply {
    text=value
    designTextSize(size)
    setTextColor(color)
    includeFontPadding=false
}
internal fun MainActivity.column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; focusOverflow() }
internal fun MainActivity.row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; focusOverflow() }
/**
 * 全站按钮只有一个实现：[TvButton]。高度、内边距、字号、圆角、焦点反馈都来自
 * [ButtonSize] / [ButtonTone]，页面代码不再自己拼样式。
 */
internal fun MainActivity.button(label: String,selected: Boolean=false,tone: ButtonTone=ButtonTone.SECONDARY,size: ButtonSize=ButtonSize.NORMAL,pad: Int?=null,icon: Int?=null,onClick: ()->Unit): TvButton =
    TvButton(this,label,size,tone,action=onClick,pad=pad,iconRes=icon).apply {
        if(selected) setSelectedState(true)
        // 无障碍：全站按钮只有这一个实现，因此在这里统一补 `contentDescription`。
        //
        // 纯图标按钮（返回 / 搜索 / 设置）在屏幕阅读器里本来只是一个「按钮」，
        // 没有名字；补上之后读屏念的是「返回，按钮」。改造前 35,000 行 UI 代码里
        // 这类标注不足 50 处，而且分散在 20 个文件里，改一处漏一处。
        if(contentDescription.isNullOrEmpty() && label.isNotBlank()) contentDescription=label
    }
/**
 * 往容器里放一个按钮，并保证间距只有一个来源：横向容器加左边距、纵向容器加上边距，
 * 第一个子控件不留边距。因此不会再有「两个按钮贴在一起」或「行尾多出 8dp 把整行推偏」。
 */
/**
 * 行内按钮。三种宽度策略，按需选一种：
 *
 * - 默认：按文字宽度自适应（[LinearLayout.LayoutParams.WRAP_CONTENT]）；
 * - [width]：固定像素宽度（很少用，像素要自己算，容易算错）；
 * - [weight]：**等分父容器的剩余宽度**（简介页那一排入口用的就是它）。
 *   宽度由父容器实测后分配，因此不用去猜「这一行到底有多少可用宽度」——
 *   简介页的按钮行在海报右侧的信息列里，不是整屏宽，按整屏算就会溢出。
 */
internal fun MainActivity.addButton(parent: LinearLayout,label: String,selected: Boolean=false,tone: ButtonTone=ButtonTone.SECONDARY,size: ButtonSize=ButtonSize.NORMAL,gap: Int=TvStyle.GAP_INLINE,width: Int?=null,weight: Float?=null,pad: Int?=null,icon: Int?=null,onClick: ()->Unit): TvButton {
    val view=button(label,selected,tone,size,pad=pad,icon=icon,onClick=onClick)
    if(weight!=null) {
        // 等分时清掉最小宽度：`TvButton` 默认有 92dp 的最小宽度，等分到的宽度比它小时
        // 控件会按最小宽度量出来，四个加起来就顶破父容器（屏幕上就是「超出去了」）。
        // 这里让等分说了算，文字放不下由 ellipsize 收尾。
        view.minimumWidth=0
    }
    val params=lp().apply {
        if(weight!=null) { this.width=0; this.weight=weight } else this.width=width ?: LinearLayout.LayoutParams.WRAP_CONTENT
    }
    if(parent.childCount>0) {
        if(parent.orientation==LinearLayout.HORIZONTAL) params.leftMargin=dp(gap) else params.topMargin=dp(gap)
    }
    parent.addView(view,params); return view
}
/** 与 [addButton] 同一套间距规则，给需要自定义 LayoutParams 的行内控件用。 */
internal fun MainActivity.gapInto(row: LinearLayout,view: View,params: LinearLayout.LayoutParams,gap: Int=TvStyle.GAP_INLINE): View {
    if(row.childCount>0) {
        if(row.orientation==LinearLayout.HORIZONTAL) params.leftMargin=dp(gap) else params.topMargin=dp(gap)
    }
    row.addView(view,params); return view
}
internal fun MainActivity.applyWindowBackground() {
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(TvStyle.background))
}
/**
 * 切换主题：落盘 → 换色（含丢掉柔光位图缓存）→ 重建当前页面。
 *
 * 重建这一步不能省：已经建好的 View 上挂着旧的背景 Drawable，里面是上一套材质的
 * 投影位图与色值。设置页的重建很轻（`showCatalog()` 在设置页只重画设置页），
 * 因此这里直接重建，再把焦点按回刚点的那一行主题。
 */
internal fun MainActivity.switchTheme(value: TvTheme) {
    if(value==TvStyle.theme) { themeRows[value]?.requestFocus(); return }
    TvThemeStore.save(this,value)
    TvStyle.applyTheme(value)
    applyWindowBackground()
    showCatalog()
    // 重建之后把焦点按回刚点的那一行：先同步要一次，再在下一帧要一次——
    // 新视图要等这一帧布局完成，滚动容器才把它带进可见区（设置页在 720p 画布上可能刚好要滚一点）。
    themeRows[value]?.let { row -> row.requestFocus(); row.post { row.requestFocus() } }

}
/**
 * 「我的」页的页头：**圆形头像 + 昵称（小字）+ 登录入口 / 退出 / 刷新**。
 *
 * 这一行在导航栏下面（与分类页的二级分类同一层）：左边是圆形头像，右边是昵称，
 * 再往右是那两个只在换账号时才用的按钮。上面那一行是「看哪一栏」，这一行是「这是谁」。
 *
 * 未导入参数时：头像是灰底占位、昵称写「未导入账号参数」，右边只有一个「导入账号参数」按钮——
 * 没登录时「退出 / 刷新」没有意义。
 */
