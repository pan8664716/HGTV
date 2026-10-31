// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画面模式的两端共用判定（[VideoFrameMode.toFit]）。
 *
 * 这条规则原先在电视与手机里各有两三份 `when (frameMode)`，所以单测盯的是
 * **合成规则本身**：竖屏片源忽略用户选择、横屏片源照用户选择。
 */
class VideoFitTest {

    @Test
    fun vertical_source_always_fills() {
        // 竖屏短剧放横屏时按「完整画面」只有中间一条，三档都必须是铺满。
        VideoFrameMode.entries.forEach { mode ->
            assertEquals("竖屏片源：$mode", VideoFit.ZOOM, mode.toFit(verticalSource = true))
        }
    }

    @Test
    fun landscape_source_follows_user_choice() {
        assertEquals(VideoFit.FIT, VideoFrameMode.FIT.toFit())
        assertEquals(VideoFit.ZOOM, VideoFrameMode.ZOOM.toFit())
        assertEquals(VideoFit.FILL, VideoFrameMode.FILL.toFit())
    }

    @Test
    fun default_parameter_is_landscape() {
        // 电视端是横屏设备，调 `toFit()` 不传方向时必须等价于「按用户选择」。
        VideoFrameMode.entries.forEach { mode ->
            assertEquals(mode.toFit(verticalSource = false), mode.toFit())
        }
    }

    @Test
    fun stored_value_falls_back_to_fit() {
        assertEquals(VideoFrameMode.FIT, VideoFrameMode.fromStored(null))
        assertEquals(VideoFrameMode.FIT, VideoFrameMode.fromStored("nonsense"))
        assertEquals(VideoFrameMode.FILL, VideoFrameMode.fromStored("FILL"))
    }
}
