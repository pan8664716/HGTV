// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 某一层「锦上添花」的推荐筛选项（受众 / `filter_ids` / 体裁）在服务端回空页时的降级状态。
 *
 * ## 为什么需要它
 *
 * 推荐接口对某些参数是**会整页回空**的：实测本机带 `gender=male` 时，
 * 流头（`offset=0`）也一条都不回——推荐页于是停在「推荐暂时取不到」，
 * 而用户只是先在设置里选了「男频」。这类筛选本来只该「让推荐更准」，
 * 不该让整屏内容消失。
 *
 * `filter_ids` 早就有这一套两次即关的兜底（电视 `recommendFilterMiss`），
 * 但**受众**没有：手机端干脆一直不带它（设置项形同虚设），电视端带了它就整页空。
 * 这里把「带它回空 → 不带它重试 → 两次仍空就永久关掉」抽成一份状态机，两端共用。
 *
 * ## 语义
 *
 * * [onEmpty]：带这一层却拿到空页。第一次返回 `true`（调用方应**不带这一层**立刻重取一次）；
 *   累计到 [limit] 次后 [off] 变 true，之后调用方直接不带它，不再浪费一次往返。
 * * [onFilled]：带这一层正常拿回内容 → 计数清零（偶发空页不该累积成「永久关掉」）。
 *
 * 线程：只在主线程（取页回调）里用，不加锁。
 */
class OptionalFilter(
    /** 只用于日志 / 调试面板，不影响行为。 */
    val name: String,
    private val limit: Int = 2,
) {
    /** 连续「带了它却回空页」的次数。 */
    var misses: Int = 0
        private set

    /** 已判定本机不可用：调用方不要再带这一层。 */
    var off: Boolean = false
        private set

    /** 记一次空页。返回 true = 这次应当「不带它」重取一次。 */
    fun onEmpty(): Boolean {
        misses++
        if (misses >= limit) off = true
        return true
    }

    /** 正常拿回内容：连续空页的计数清零。 */
    fun onFilled() {
        misses = 0
    }

    /** 换了推荐范围 / 用户改了设置：重新给这一层一次机会。 */
    fun reset() {
        misses = 0
        off = false
    }
}
