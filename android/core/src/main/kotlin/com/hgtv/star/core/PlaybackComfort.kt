// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 视频解码方式。
 *
 * 硬件解码走芯片自带的解码器（省电、发热低、4K 更稳），软件解码走 CPU 解码
 * （兼容性更好：花屏、绿屏、音画不同步、黑屏有声这类片源问题通常能绕过）。
 *
 * 选择在**创建播放器时**生效：切换会重建播放器并从当前位置续播，见
 * `MainActivity.applyDecoderMode`。
 */
enum class VideoDecoderMode(val label: String) {
    HARDWARE("硬件解码"), SOFTWARE("软件解码");
    companion object { fun fromStored(value: String?)=entries.firstOrNull { it.name==value } ?: HARDWARE }
}

enum class VideoFrameMode(val label: String) {
    FIT("完整画面"), ZOOM("等比铺满"), FILL("拉伸铺满");
    companion object { fun fromStored(value: String?)=entries.firstOrNull { it.name==value } ?: FIT }
}

/**
 * 画面最终怎么摆：**策略**，与平台无关。
 *
 * 「竖屏片源一律铺满、横屏片源按用户选的模式」这条规则原先在电视与手机里各写了两三份
 * `when (frameMode) { … }`，任何一端多一档、或者改一次默认值，另一头就悄悄不一致。
 * 现在判定只在这里：[VideoFrameMode.toFit] 产出 [VideoFit]，各端只保留一句
 * 「[VideoFit] → 平台常量」的绑定（见两端的 `resizeMode()` 扩展）。
 *
 * 为什么只做「策略」不直接返回 media3 的 `RESIZE_MODE_*`：`:core` 是纯 JVM 模块，
 * 不碰 Android / media3；而这套映射本身是有业务含义的（竖屏片源忽略用户选择），
 * 正是需要单测覆盖的那一部分。
 */
enum class VideoFit { FIT, ZOOM, FILL }

/**
 * 用户选择的 [mode] 与片源方向合成最终画法。
 *
 * @param verticalSource 片源本身是不是竖屏（不是屏幕方向）。竖屏短剧放横屏时若按 FIT，
 *   9:16 只占中间一条，因此这一档**无视用户选择**直接铺满——与电视端 `frameResizeMode`
 *   加上「竖屏片源一律 ZOOM」的既有行为等价。
 */
fun VideoFrameMode.toFit(verticalSource: Boolean = false): VideoFit =
    if (verticalSource) VideoFit.ZOOM
    else when (this) {
        VideoFrameMode.FIT -> VideoFit.FIT
        VideoFrameMode.ZOOM -> VideoFit.ZOOM
        VideoFrameMode.FILL -> VideoFit.FILL
    }

/**
 * 快进 / 快退的步长与边界：**电视上已经调好的那一套手感**，手机沿用同一份。
 *
 * 三条规则原先只写在电视的 `MainActivity.seek` / `seekRecommend` 里，手机在推荐流与
 * 播放页各写了一份「按秒数直接 seek」的简版，于是同一部剧在两端的快进手感不同：
 *
 * * **合并窗**：相邻两次操作在 [MERGE_WINDOW_MS] 内时只跳一次（取最后的目标）。
 *   逐次 seek 会让解码器反复 flush，连按三次就卡三下；电视上早就是这个做法。
 * * **连按加速**：第 [ACCEL_AFTER_TAPS] 次之后每次 3 倍步长。短剧一集两分钟，
 *   10 秒档连按几下就能到片尾，不必真的按住不放。
 * * **片尾留 [TAIL_GUARD_MS]**：正好落在最后一帧会立刻触发 `STATE_ENDED`，
 *   自动连播在用户还没看清结尾时就跳走了；电视上因此停在片尾前 0.5 秒。
 */
object SeekStep {

    /** 设置页「快进快退」的档位范围（与电视同一个滑块）。 */
    const val MIN_SECONDS = 2
    const val MAX_SECONDS = 20

    /** 相邻操作的合并窗：窗内的多次点击只发一次 seek。 */
    const val MERGE_WINDOW_MS = 250L

    /** 片尾保护：不跳到最后一帧，避免提前触发自动连播。 */
    const val TAIL_GUARD_MS = 500L

    /** 从第几次操作起加速（判据是 `tap > ACCEL_AFTER_TAPS`）。 */
    const val ACCEL_AFTER_TAPS = 5

    /** 加速倍数（与电视端一致：10 秒档连按之后每次 30 秒）。 */
    const val ACCEL_FACTOR = 3

    /**
     * 单次步长（毫秒）。
     *
     * @param tap 这一次是**第几次连续操作**，从 1 开始；单次点击传 1。
     *   电视端原来传按键的 repeat 计数（首次为 0），这里换算成「次数」，
     *   两端读同一套语义，不会再出现「电视按 5 次加速、手机按 5 次不加速」。
     */
    fun stepMs(seconds: Int, tap: Int = 1): Long {
        val base = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS) * 1000L
        return if (tap > ACCEL_AFTER_TAPS) base * ACCEL_FACTOR else base
    }

    /** 片尾之前最后一个可停的位置。 */
    fun limitMs(durationMs: Long): Long = (durationMs - TAIL_GUARD_MS).coerceAtLeast(0)

    /**
     * 从 [fromMs] 往 [direction]（-1 退 / +1 进）跳一步之后的位置。
     *
     * 边界停在片头 0 与片尾前 [TAIL_GUARD_MS]：越界时贴住端点而不是回弹，
     * 用户连按到底时读到的就是「到头了」。
     */
    fun target(fromMs: Long, direction: Int, stepMs: Long, durationMs: Long): Long =
        (fromMs + (if (direction >= 0) stepMs else -stepMs)).coerceIn(0, limitMs(durationMs))

    /**
     * 连按计数：与上一次操作间隔在合并窗内算「同一次连按」，否则重新从 1 数。
     *
     * 放在 `:core` 而不是两端各写一个 `if (now - last < 250)`：加速判据依赖它，
     * 两端一旦对「什么算一次连按」有分歧，手感就又分叉了。
     */
    fun nextTap(previousTap: Int, sinceLastMs: Long): Int =
        if (previousTap > 0 && sinceLastMs in 0..MERGE_WINDOW_MS) previousTap + 1 else 1
}

/** Session-only timer driven by a monotonic clock, independent of playback speed. */
class SleepTimer(private val now: ()->Long) {
    private var deadline: Long?=null
    var episodesLeft=0; private set
    val active get()=deadline!=null || episodesLeft>0
    fun cancel() { deadline=null; episodesLeft=0 }
    fun afterMinutes(minutes: Int) { require(minutes in 1..180); cancel(); deadline=now()+minutes*60_000L }
    fun afterEpisodes(count: Int) { require(count in 1..10); cancel(); episodesLeft=count }
    fun remainingMillis()=deadline?.let { (it-now()).coerceAtLeast(0) }
    fun poll(): Boolean { if(deadline?.let { now()>=it }!=true) return false; cancel(); return true }
    fun episodeEnded(): Boolean {
        if(poll()) return true
        if(episodesLeft<=0) return false
        episodesLeft--
        return episodesLeft==0
    }
    fun label(): String = remainingMillis()?.let { "${(it+59_999)/60_000} 分钟后" }
        ?: if(episodesLeft>0) "再播完 $episodesLeft 集" else "关闭"

    companion object {
        /**
         * 「定时关闭」的八档，**两端逐字相同**。
         *
         * 早先电视与手机各写一份字面量：用户在电视上记住的是「本集播完 / 再 3 集」，
         * 换到手机上看到「播完这一集」会以为不是同一个功能。放在这里之后，
         * 选项、顺序与落盘语义只有一份。
         */
        val LABELS = listOf(
            "关闭", "15 分钟", "30 分钟", "60 分钟", "90 分钟",
            "本集播完", "再 3 集", "再 5 集",
        )

        /** 前四档按分钟计（[LABELS] 下标 1..4）。 */
        private val MINUTES = listOf(15, 30, 60, 90)

        /** 后三档按集计（[LABELS] 下标 5..7）。 */
        private val EPISODES = listOf(1, 3, 5)

        /**
         * 把 [LABELS] 的第 [index] 项落到计时器上。
         *
         * 返回 true 表示已生效（非「关闭」），调用方据此决定要不要提示 / 摘掉队列里的下一集。
         * 越界按「关闭」处理：调用方传进来的下标来自 UI，宁可停掉也不要抛。
         */
        fun applyChoice(timer: SleepTimer, index: Int): Boolean {
            timer.cancel()
            when (index) {
                in 1..MINUTES.size -> timer.afterMinutes(MINUTES[index - 1])
                in (MINUTES.size + 1)..LABELS.lastIndex ->
                    timer.afterEpisodes(EPISODES[index - MINUTES.size - 1])
                else -> return false
            }
            return true
        }
    }
}
