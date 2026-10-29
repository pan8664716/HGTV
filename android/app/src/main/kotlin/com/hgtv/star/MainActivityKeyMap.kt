// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.view.KeyEvent
import android.view.View
import com.hgtv.star.core.KeyMap
import com.hgtv.star.core.PlayerKey
import com.hgtv.star.core.PlayerKeyAction
import com.hgtv.star.tv.TvDialog

// 本文件是从 MainActivity.kt 拆出来的「播放界面按键自定义：设置页那一栏的文案 / 选择弹窗，
// 以及播放时的按键分发」这一块。这些成员仍然属于 MainActivity，只是以扩展成员
// （MainActivity.xxx）的形式放在这里，逻辑与拆之前逐行一致；改这里等于改 Activity 本体。
//
// 三件事分得很清楚，改的时候别混：
// * 有哪些键、每个键能绑哪些动作、出厂绑定是什么 → `core/KeyMap.kt`（纯 Kotlin，可测）；
// * 设置页长什么样、点一格怎么改 → 本文件的 [keyRowLabel] / [showKeyActionPicker]
//   （排版在 [MainActivity.settings] 里，与其余设置项共用同一套分组与网格）；
// * 播放时按键怎么落地 → 本文件的 [playerKeyAction] 与 `MainActivity.dispatchKeyEvent`。

/**
 * 设置页里一个键那一格的文案：`上键  ·  播放设置`。
 *
 * 与相邻的「默认清晰度  ·  1080P」同一套写法（两个空格 + 间隔号 + 当前值），
 * 用户扫一眼就知道每一格现在是什么。
 */
internal fun MainActivity.keyRowLabel(key: PlayerKey)="${key.label}  ·  ${library.keyMap.action(key).label}"

/**
 * 「恢复默认按键」那一行的文案。
 *
 * 默认状态下也要写出来（而不是留空或灰掉）：这一行同时是「当前是不是默认」的读数，
 * 用户改过几项之后想确认自己有没有改回去，看这一行就够了。
 */
internal fun MainActivity.restoreKeysLabel(): String =
    if(library.keyMap.isDefault) "恢复默认按键  ·  当前为默认"
    else "恢复默认按键  ·  已改 ${library.keyMap.customized} 项"

/**
 * 选一个键要绑哪个动作。
 *
 * 用全站统一的 [TvDialog] + 列表型选项（整行左对齐的常规按钮，超过半屏自动滚动），
 * 当前生效的那一项是「按进去」的凹槽——与清晰度 / 倍速 / 画面模式那几个选择器完全一致。
 *
 * 每一项的说明取自 [PlayerKeyAction.hint]（「档位名 · 做什么」）：
 * 「控制条」和「播放设置」光看名字分不出差别，说明文字才说得清按下去会发生什么。
 * 选完立刻落盘（[com.hgtv.star.data.Library.keyMap]）并回调一次，由设置页刷新那一格的文案。
 */
internal fun MainActivity.showKeyActionPicker(key: PlayerKey,anchor: View,onChanged: ()->Unit={}) {
    val options=PlayerKeyAction.entries
    // 快进 / 快退的说明里写着具体秒数，而秒数现在由设置页的滑块决定（2..20，默认 10）：
    // 直接抄 [PlayerKeyAction.hint] 那个常量会在用户改成 20 秒之后仍然说「每次 10 秒」，
    // 因此这两项在弹窗里按当前设置现拼一次。
    val step=library.seekStep
    fun hintOf(action: PlayerKeyAction)=when(action) {
        PlayerKeyAction.SEEK_BACK -> "快退 · 每次 ${step} 秒，连按 5 次后每次 ${step*3} 秒"
        PlayerKeyAction.SEEK_FORWARD -> "快进 · 每次 ${step} 秒，连按 5 次后每次 ${step*3} 秒"
        else -> action.hint
    }
    val picker=TvDialog(this,"${key.label}的动作").choices(options.map(::hintOf),options.indexOf(library.keyMap.action(key))) { index ->
        library.keyMap=library.keyMap.customize(key,options[index])
        onChanged()
    }
    picker.button("取消", icon=R.drawable.ic_menu_close)
    picker.show(anchor)
}

/**
 * 播放界面里这个键当前绑定的动作；不是那六个可自定义的键就返回 null。
 *
 * 返回键与媒体键（播放 / 暂停 / 上一集 / 下一集 / 快进 / 快退）**不在自定义范围内**：
 * 前者任何绑定下都必须是「收起浮层 / 退出播放 / 回主界面」的出口，后者是遥控器 / 电视
 * 自己的媒体键，语义由系统约定，不该由本应用改写。因此这里只认这六个。
 */
internal fun MainActivity.playerKeyAction(keyCode: Int): PlayerKeyAction? = when(keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> library.keyMap.action(PlayerKey.UP)
    KeyEvent.KEYCODE_DPAD_DOWN -> library.keyMap.action(PlayerKey.DOWN)
    KeyEvent.KEYCODE_DPAD_LEFT -> library.keyMap.action(PlayerKey.LEFT)
    KeyEvent.KEYCODE_DPAD_RIGHT -> library.keyMap.action(PlayerKey.RIGHT)
    KeyEvent.KEYCODE_MENU -> library.keyMap.action(PlayerKey.MENU)
    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> library.keyMap.action(PlayerKey.OK)
    else -> null
}

/**
 * 把「这一下按键」交给当前绑定去做；返回 true 表示已经消化掉。
 *
 * 只处理按下（[KeyEvent.ACTION_DOWN]），且除快进 / 快退外都只在**第一次按下**（`repeatCount==0`）
 * 触发一次：长按方向键时系统会连发 DOWN，连开好几次菜单 / 反复切集都不是用户想要的。
 * 快进 / 快退保留连按（`repeatCount` 直接交给 [MainActivity.seek]）——那里连按本来就是加速。
 *
 * 「无」这一档什么都不做但仍然吃掉这一下：播放界面上没有可聚焦的按钮
 * （控制条上的图标与状态项都不抢焦点），把键放给焦点系统也只会在原地打转。
 * 返回键不在这里，用户任何时候都退得出播放界面。
 */
internal fun MainActivity.dispatchPlayerKey(action: PlayerKeyAction,event: KeyEvent): Boolean {
    if(event.action==KeyEvent.ACTION_DOWN) when(action) {
        PlayerKeyAction.SETTINGS -> if(event.repeatCount==0) showPlaybackMenu()
        PlayerKeyAction.EPISODES -> if(event.repeatCount==0) showEpisodePanel(hud)
        PlayerKeyAction.HUD -> if(event.repeatCount==0) { if(hud.visibility==View.VISIBLE) hidePlaybackOverlay() else showHud() }
        PlayerKeyAction.PLAY_PAUSE -> if(event.repeatCount==0) togglePlayback()
        PlayerKeyAction.SEEK_BACK -> seek(-1,event.repeatCount)
        PlayerKeyAction.SEEK_FORWARD -> seek(1,event.repeatCount)
        PlayerKeyAction.PREVIOUS -> if(event.repeatCount==0) skipEpisode(-1)
        PlayerKeyAction.NEXT -> if(event.repeatCount==0) skipEpisode(1)
        PlayerKeyAction.NONE -> Unit
    }
    return true
}
