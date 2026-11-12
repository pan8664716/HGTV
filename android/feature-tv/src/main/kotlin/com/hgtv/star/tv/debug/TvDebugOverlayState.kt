// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.shared.data.PlaybackCapability

/**
 * 调试面板在 Activity 生命周期内保留的可变状态。
 *
 * 面板的布局、采样与刷新逻辑仍由 `DebugOverlay.kt` 负责；这里只集中保存跨刷新复用的
 * 视图、节流计数和最近问题，避免这些字段继续占用主 Activity 的状态区。
 */
internal class TvDebugOverlayState {
    var panel: LinearLayout?=null
    var body: LinearLayout?=null
    var rowLabels: List<String> = emptyList()
    val rowValues=mutableListOf<TextView>()
    var tick: Runnable?=null
    var idleTicks=0
    var problem: String=""
    var problemAt: Long=0L
    var problemPage: String=""
    var capability: PlaybackCapability.DebugCapability?=null
    var capabilityLoading=false
}
