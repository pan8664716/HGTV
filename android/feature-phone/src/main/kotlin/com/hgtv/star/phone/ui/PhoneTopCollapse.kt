// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * 列表上滑时把页头收起来、回到顶部再放出来（TV 的 `onListScroll` / `setTopAreaHidden` 同一条语义）。
 *
 * ## 为什么收**高度**而不是 `translationY`
 *
 * TV 端注释里已经写过一次：`translationY` 只是把控件挪出去，布局里的那块位置还在，
 * 顶部会留一条空白，列表也没真的多出一截可视区。手机屏幕更矮，这条空白尤其明显。
 * 收高度之后列表真的往上顶，用户多看到一整行海报——这才是「收起」该有的收益。
 *
 * ## 收起看方向，展开看位置
 *
 * 只按绝对位置判断会在页头高度动画期间来回抖：页头收掉之后列表可视高度变大，
 * `computeVerticalScrollOffset()` 会跟着变化，同一段手势里偏移可能从阈值上方掉到下方，
 * 旧逻辑于是立刻反向展开；展开又把偏移推回阈值上方，页头便一直闪。
 *
 * 现在向上滑且位置超过 [collapseAt] 才收，收起后只在**真正回到顶部**时展开；
 * 动画进行期间也不再接收滚动回调，避免布局变化引起的中途反向。
 *
 * 阈值只取一小段（[collapseAt]）：普通上滑就能触发，又不会因为手指轻抖就抽走标题。
 *
 * ## 与重建的配合
 *
 * 页面重建（换主题 / 改字号 / 改分辨率）之后页头是展开态、列表回到 0，
 * 因此不必保存收起状态——[add] 之后第一次 `onScroll` / [sync] 会按真实位置纠正。
 */
class PhoneTopCollapse(private val host: View) {

    private val rows = ArrayList<View>()
    /** 展开态的高度（像素）。收起过程中 `height` 会变成 0，所以要自己记一份。 */
    private val expanded = HashMap<View, Int>()
    /** 登记时的 `layoutParams.height`（通常是 `WRAP_CONTENT`）：展开动画结束后要还回去。 */
    private val original = HashMap<View, Int>()
    private var animator: ValueAnimator? = null
    private var hidden = false
    /** 高度动画期间忽略滚动回调：动画本身会改变列表视口，偏移读数不能当手势。 */
    private var animating = false

    /** 向上滑过这一小段就收；比旧版 28dp 更跟手。 */
    private val collapseAt: Int = host.context.dp(10)

    /** 只有回到顶部附近才展开，和收起阈值留出一段滞回区，避免在边缘反复切换。 */
    private val expandAt: Int = host.context.dp(2)

    /** 登记一行页头。可以多次调用（页面重铺时重建一份 helper 更省事）。 */
    fun add(row: View): PhoneTopCollapse {
        rows += row
        original[row] = row.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        expanded[row] = row.layoutParams?.height?.takeIf { it > 0 } ?: 0
        if (hidden) applyHidden(row, true, animate = false)
        return this
    }

    /**
     * 列表滚动回调：`y` 是列表当前的 scrollY，`dy` 是这一帧的滚动增量（向上滑为正）。
     */
    fun onScroll(y: Int, dy: Int) {
        if (animating) return
        if (hidden) {
            // 只有真的向下滑到顶部才展开；布局变化带来的 `dy == 0` 不能当成回顶手势。
            if (y <= expandAt && dy < 0) setHidden(false)
            return
        }
        if (dy > 0 && y > collapseAt) setHidden(true)
    }

    /** 立即同步到当前位置（页面重新铺完、数据换了一批之后调用）。 */
    fun sync(y: Int) {
        animator?.cancel()
        animating = false
        val next = y > collapseAt
        if (next != hidden) setHidden(next, animate = false)
    }

    /**
     * 收起 / 展开。动画期间**只改高度**：位置、颜色、点击都不动，
     * 因此收一半时点到露出来的那半截仍然是原来的控件。
     */
    private fun setHidden(hide: Boolean, animate: Boolean = true) {
        // 先把当前真实高度记下来（展开态），再算目标高度。
        if (hide) {
            rows.forEach { row ->
                val height = row.height.takeIf { it > 0 }
                    ?: row.measuredHeight.takeIf { it > 0 }
                    ?: 0
                if (height > 0) expanded[row] = height
            }
            // 还没完成首次测量时不收：否则 `expanded` 会停在 0，之后展开反而把页头压成 0。
            if (rows.any { (expanded[it] ?: 0) <= 0 }) return
        }
        hidden = hide

        animator?.cancel()
        animator = null
        val from = rows.map { it.height }
        val to = rows.map { row ->
            if (hide) 0 else expanded[row]?.takeIf { it > 0 }
                ?: row.height.takeIf { it > 0 }
                ?: row.measuredHeight.takeIf { it > 0 }
                ?: 0
        }

        if (!animate) {
            animating = false
            rows.forEachIndexed { i, row -> setHeight(row, to[i]) }
            if (!hide) rows.forEach { restore(it) }
            return
        }
        if (from == to) {
            animating = false
            if (!hide) rows.forEach { restore(it) }
            return
        }

        animating = true
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (hide) 180L else 220L
            // 收起快、展开稍慢：收起是「让开」，要利落；展开是「回来」，慢一点不突兀。
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                rows.forEachIndexed { i, row ->
                    setHeight(row, (from[i] + (to[i] - from[i]) * t).toInt())
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator !== animation) return
                    animator = null
                    animating = false
                    // 展开到底之后把高度还成 `WRAP_CONTENT`：页头里的内容会变
                    //（分类页换体裁会重建筛选行、我的页登录后会多两栏），
                    // 钉死一个旧像素值会让新内容被裁掉。
                    if (!hide) rows.forEach { restore(it) }
                }
            })
        }
        animator = anim
        anim.start()
    }

    private fun applyHidden(row: View, hide: Boolean, animate: Boolean) {
        if (hide) setHeight(row, 0) else setHeight(row, expanded[row] ?: 0)
    }

    private fun restore(row: View) {
        val lp = row.layoutParams as? ViewGroup.LayoutParams ?: return
        val h = original[row] ?: ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.height == h) return
        lp.height = h
        row.layoutParams = lp
    }

    private fun setHeight(row: View, px: Int) {
        val lp = row.layoutParams as? ViewGroup.LayoutParams ?: return
        if (lp.height == px) return
        lp.height = px
        row.layoutParams = lp
    }
}
