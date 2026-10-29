// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 正式播放页**按集计**的全部重试额度与账本：一处持有、三个入口重置。
 *
 * ## 为什么收敛到一处
 *
 * 这几份额度早先散落在 `MainActivity` 的字段里，各自有各自的归零条件：
 * 自动重试看 `!recovering`、画面重建看 `onEpisodeAdvanced`、队列重开与地址自愈
 * 在 `playEpisode` 里各写一句、手动重试又抄了第三份。结果是同一类 bug 反复出现：
 *
 * * 换集之后没有自动重试（`RecoveryBudget` 没跟着清）；
 * * 错误页顶着上一集的「请切换为软件解码」（`manualHint` 没跟着清）；
 * * 同一集的地址自愈每隔一次就再来一遍（失败时把标记清空，下一次又能救）。
 *
 * 现在读写都走这里，重置只有三个入口，新增一条重试路径时不必再记「我该清哪几个字段」。
 *
 * ## 三个入口的语义
 *
 * | 入口 | 什么时候 | 清什么 |
 * | --- | --- | --- |
 * | [onEpisodeStart] | 换了一集（手动选集 / 起播 / 片尾推进） | 全部 |
 * | [onEpisodeSwitch] | 换到另一集去重来，但连播链还没接上 | 除 [autoNextFailures] 外全部 |
 * | [onManualRetry] | 用户按了「立即重试」（同一集） | 自动重试 + 画面重建 + 队列重开 |
 *
 * 其余情形（自动降档 / 同档重建 / 网络自愈 / 画面重建）**一律不动**——
 * 它们正是这些额度要限制的对象。
 */
class PlaybackAttemptLedger {
    private val recovery=RecoveryBudget()

    private var pictureRecoveries=0
    private var pictureAt=0L

    private var queuedKey=""
    private var queuedUsed=0

    private var nextFailures=0

    /**
     * 本集已经「自愈」过的地址键（`集:上限`）。
     *
     * 早先只有一个字符串：撞到同一个键时先把它清空、再让这一轮落到错误页，
     * 于是下一次同样的失败又能救一次——「每集只救一次」实际上是「隔一次救一次」。
     * 现在按集记一份，[onEpisodeStart] 清空；一集之内最多两三个键，不需要上限。
     */
    private val streamRetries=HashSet<String>()

    // —— 自动重试：3 次，间隔 3s / 6s / 12s ——

    /** 本集已经自动重试过几次。 */
    val attempts get()=recovery.attempts

    /** 还能不能排下一次自动重试。 */
    val canAttempt get()=recovery.remaining

    /** 下一次自动重试等多久；额度用尽时为 null。 */
    val delayMillis get()=recovery.delayMillis

    /** 记一次自动重试；额度用尽返回 false（调用方据此不再排）。 */
    fun consumeAttempt(): Boolean = recovery.consume()

    // —— 画面停住后重建播放层 ——

    /** 本集已经重建过几次（见 `recoverFormalPicture`）。 */
    val formalPictureRecoveries get()=pictureRecoveries

    /** 上一次重建之后是否还在冷却窗口里（挡掉两条看门狗同时命中）。 */
    fun pictureCoolingDown(now: Long,cooldownMs: Long)=pictureAt!=0L && now-pictureAt<cooldownMs

    fun notePictureRecovery(now: Long) { pictureAt=now; pictureRecoveries++ }

    // —— 队列里那一集出错后「静默重开当前集」——

    /** 按「集 + 当前上限」计的次数：换了集或换了档位就自然从 0 开始。 */
    fun queuedRecoveries(key: String)=if(queuedKey==key) queuedUsed else 0

    fun noteQueuedRecovery(key: String,used: Int) { queuedKey=key; queuedUsed=used }

    // —— 片尾自动推进 ——

    /** 连播链连续断了几次。成功接上（[onEpisodeStart]）才清零。 */
    val autoNextFailures get()=nextFailures

    fun noteAutoNextFailure() { nextFailures++ }

    // —— 地址过期 / 资源变化的自愈 ——

    fun streamRetried(key: String)=key in streamRetries

    fun noteStreamRetry(key: String) { streamRetries+=key }

    /** 换了一集：全部归零。 */
    fun onEpisodeStart() {
        resetAttemptScopes()
        nextFailures=0
        streamRetries.clear()
    }

    /**
     * 换到**另一集**去重来，但连播链还没接上（见 `restartAnotherEpisode`）。
     *
     * 与 [onEpisodeStart] 的唯一差别是**不清** [autoNextFailures]：这条路本身就是
     * 「片尾推进失败 → 从下一集重来」，清掉计数就变成了无限自动推进。
     */
    fun onEpisodeSwitch() = resetAttemptScopes()

    /**
     * 用户按了「立即重试」：还掉自动重试 / 画面重建 / 队列重开。
     *
     * **不**清 [autoNextFailures]（那是连播链的事，与用户手动重试无关），
     * **不**清 [streamRetried]（同一条地址在本集已经救过一次，再救还是同一条）。
     */
    fun onManualRetry() = resetAttemptScopes()

    private fun resetAttemptScopes() {
        recovery.reset()
        pictureRecoveries=0; pictureAt=0L
        queuedKey=""; queuedUsed=0
    }
}
