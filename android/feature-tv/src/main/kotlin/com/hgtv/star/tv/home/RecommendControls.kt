// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.SeekStep

/**
 * 上下键换剧。
 *
 * 往下走到末尾时：还有下一页就先补一页再接着走（正常情况下这一页已经被
 * [ensureRecommendLookahead] 提前取好了，切换是即时的一帧）；真的取不到新内容就停在
 * 当前这一部并提示，**不环绕回第 1 部**——那会把刚刷过的那批从头重放一遍。
 * 往回按到头才环绕到末尾（往回刷重复一部不算问题，也不会打断观感）。
 */
fun TvMainActivity.switchRecommend(direction: Int) {
    if(screen!="recommend") return
    // 用户自己换了一部：上一次那部的失败不再累计。
    recommendPlaybackFailures=0
    if(playbackMenu!=null) hidePlaybackOverlay()
    if(recommendItems.isEmpty()) { openRecommend(); return }
    val from=recommendIndex
    val next=from+direction
    when {
        // 目标在列表里：交给 [playRecommendAt] 强制摘掉旧播放层，再准备新作品。
        next in recommendItems.indices -> {
            playRecommendAt(next,0L,direction)
        }
        // 走到末尾：先补一页再往下走。这条流已经到底（`has_more=false`）时，补页那一侧
        // 会换一条新流（见 `loadRecommendPage`）。补页发不出去 / 失败时回调会立刻到，
        // 这时列表没变长，就环绕回第一部（抖音式的无限流，不会停在「按了没反应」上）。
        direction>0 -> {
            val guard=recommendToken.get()
            // 这一下按的是「再往下」，但页还没到：把意图记下来，页回来后一次跨过去。
            recommendAdvancePending++
            val before=recommendItems.size
            // **不盖黑场**：页还没到，盖黑只会让用户对着一块黑屏（这就是「有一个画面没播放
            // 就被略过」的观感来源）。让当前这一部继续播，等页回来、真要换的时候才盖。
            // 正常情况下根本走不到这里——快刷到末尾时 [ensureRecommendLookahead] 已经把页取好了。
            loadRecommendPage(true) {
                if(recommendToken.get()!=guard) return@loadRecommendPage
                val steps=recommendAdvancePending.coerceAtLeast(1)
                recommendAdvancePending=0
                // 页真的长出新条目：按累计的按键次数往前跨（连按 10 下就跳 10 部），
                // 而不是只挪 1 部、也不是回到第 0 部把刚刷过的重放一遍。
                if(recommendItems.size>before) {
                    playRecommendAt((from+steps).coerceAtMost(recommendItems.lastIndex),0L,1)
                } else {
                    // 页没长：这条流确实没有新内容了。停在当前这一部并说明，
                    // **绝不环绕回第 1 部**——那正是用户看到的「又把刚才那批刷了一遍」。
                    showRecommendToast("这批推荐刷完了，稍后再来")
                }
            }
        }
        // 往上按到头：环绕到末尾（抖音式的无限流；往回刷重复一部不算问题）。
        else -> {
            playRecommendAt(recommendItems.lastIndex,0L,direction)
        }
    }
}

/**
 * 推荐页的左右键：快退 / 快进（需求 3）。
 *
 * 与正式播放的 [seek] 同一套步长与手感（短按按设置里的「快进快退时长」、连按 3 倍、250ms 内合并成一次跳转）。
 * 反馈位置按设备分：电视上写着底部浮层那一行（[showRecommendToast]），触屏上是一条瞬时提示
 * （同一条 toast 在触屏分支里改挂手势层，见 [showRecommendToast]）。
 * 这里**不调** `showHud()`：遥控器的左右键只服务于刷剧，不该顺手把控制条弹出来；
 * 触屏那边唤出控制条是「轻点画面」的事（见 `MainActivityTouch`）。
 */
fun TvMainActivity.seekRecommend(direction: Int,repeat: Int) {
    if(screen!="recommend") return
    val p=player ?: return
    if(!playbackReady) return
    val duration=p.duration
    if(duration<=0) return
    // 与正片同一份步长（`:core` 的 [SeekStep]，手机端也是它）：两页的左右键不能是两套手感。
    val step=SeekStep.stepMs(library.seekStep,repeat+1)
    val target=SeekStep.target(pendingSeek ?: p.currentPosition,direction,step,duration)
    pendingSeek=target
    main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250)
    updateRecommendOverlay()
    showRecommendToast("${if(direction>0) "快进" else "快退"} ${step/1000} 秒 · ${formatTime(target)}")
}
