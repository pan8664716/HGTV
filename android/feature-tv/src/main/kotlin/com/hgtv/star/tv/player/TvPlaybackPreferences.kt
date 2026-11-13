// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.VideoDecoderMode

/**
 * 本次播放**实际生效**的清晰度上限。
 *
 * 优先级：会话层降档结果 > 用户设置。所有「取地址 / 预热 / 面板显示 / 索引缓存」的
 * **读取**都必须走这里，否则自动降档只在当前这一集生效，下一集立刻回到用户设置、
 * 再撞一次同样的失败（这正是要修的循环）。
 *
 * 写 `library.maxQuality` 的地方只允许是用户手动选择（见 `MainActivityPlaybackMenu`）。
 */
val TvMainActivity.playbackMaxQuality: Int
    get()=playbackSessionState.effectiveMaxQuality ?: library.maxQuality

/**
 * 本次播放实际生效的解码方式：**就是用户设置**。
 *
 * 降级不再自动改这一档（全站只有 HEVC，软解是用户的知情选择）：
 * 切软解的唯一入口是错误页上的「切换为软解」按钮与播放设置里的「解码」一行，
 * 两者都写 `library.decoderMode`（见 [applyDecoderMode]）。
 */
val TvMainActivity.playbackDecoderMode: VideoDecoderMode
    get()=library.decoderMode

/**
 * 下一集预热槽的键：集 + 上限。
 *
 * 三处（预取写入 / 挂队列取出 / tick 兜底）必须用**同一个**字符串。
 * 全站只有 HEVC 一种编码，所以键里不再需要编码族那一段。
 */
fun TvMainActivity.preparedKeyFor(id: String): String =
    "$id:${playbackMaxQuality}"

/**
 * 建播放器时该用的解码方式。
 *
 * **就等于用户设置**（[playbackDecoderMode]）：本机有没有 HEVC 硬解**不再自动改这一档**。
 *
 * 需求口径是「优先硬解，不支持硬解时提示用户换成软解，由用户手动换」。
 * 早先这里会在探测到「本机没有视频硬解」时静默改成软解：用户以为开着硬解，实际在软解，
 * 而且一旦软解也放不动，界面上没有任何线索说明「是硬解根本没有」。
 * 现在硬解筛空 → 4002 → 错误页给出「切换软解 + 建议档位」的按钮，动作由用户发起。
 */
fun TvMainActivity.playerDecoderMode(): VideoDecoderMode = playbackDecoderMode
