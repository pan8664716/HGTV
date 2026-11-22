// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.google.android.exoplayer2.analytics.AnalyticsListener

/**
 * 播放链路的可观测点：只回答三个问题。
 *
 * 1. 换集时解码器有没有重建 —— `onVideoDecoderInitialized` 每次初始化记一行。
 *    同一集内只出现一次 = 复用；每次切集都出现 = 重建，那才是卡顿的根。
 * 2. 下一集第一帧什么时候上屏。
 * 3. 有没有真的丢帧 / 音频断流。
 *
 * 这些都是 `AnalyticsListener` 的既有事件，不改变播放行为；只在 debug 包挂。
 */
fun TvMainActivity.playbackAnalytics()=object: AnalyticsListener {
    private fun tag()=player?.currentMediaItem?.mediaId ?: "-"
    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime,decoderName: String,initializedTimestampMs: Long,initializationDurationMs: Long) {
        android.util.Log.i("HGTV","Decoder init media=${tag()} name=$decoderName durationMs=$initializationDurationMs")
        if(recommendSwitchStartedAt>0L) {
            android.util.Log.i(
                "HGTV-SWITCH",
                "decoder-init +${android.os.SystemClock.elapsedRealtime()-recommendSwitchStartedAt}ms "+
                    "durationMs=$initializationDurationMs name=$decoderName",
            )
        }
    }
    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime,surface: Any,renderTimeMs: Long) {
        android.util.Log.i("HGTV","Rendered first frame media=${tag()} renderTimeMs=$renderTimeMs at=${android.os.SystemClock.elapsedRealtime()}")
    }
    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime,droppedFrames: Int,elapsedMs: Long) {
        if(droppedFrames>0) android.util.Log.w("HGTV","Dropped frames=$droppedFrames in ${elapsedMs}ms media=${tag()}")
    }
    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime,bufferSize: Int,bufferSizeMs: Long,elapsedSinceLastFeedMs: Long) {
        android.util.Log.w("HGTV","Audio underrun ${bufferSizeMs}ms media=${tag()}")
    }
}
