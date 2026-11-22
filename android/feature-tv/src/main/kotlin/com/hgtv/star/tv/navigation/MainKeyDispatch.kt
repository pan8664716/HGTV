// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.hgtv.star.tv.list.showCatalog

/**
 * 处理返回键导航。Activity 的 `onBackPressed` 只保留系统回调外壳。
 */
internal fun TvMainActivity.goBackImpl() {
    if(!mainInitialized || !library.isLoaded) { confirmExitImpl(); return }
    when(screen) {
        // 播放设置开着时先收菜单（浮层，返回键收浮层是通用手势）；
        // 从推荐页进的正片返回时直接回首页，不经过简介页或推荐流。
        "player" -> when {
            playbackMenu!=null -> hidePlaybackOverlay()
            playbackFromRecommend -> leaveRecommendPlaybackToHome()
            hudReady && hud.visibility==View.VISIBLE && !playError -> hidePlaybackOverlay()
            else -> returnToDetail()
        }
        // 推荐播放界面：返回键回主页面（需求 2 / 5）——不写历史、不回详情。
        // 打开着的浮层（播放设置 / 触屏那排按钮）由 `dispatchKeyEvent` 先一层层收掉，
        // 走到这里说明屏幕上已经空了，这一下才是真的「回主页」。
        "recommend" -> exitRecommendToHome()
        "detail" -> showCatalog()
        // 品牌不再是按钮，返回键就是唯一的「回主界面」入口：集合页回到它所在的标签（首页），
        // 搜索 / 排行榜 / 设置直接切回首页；已经在首页时再按就是**退出**（按两次，见 [confirmExitImpl]）。
        //
        // 首页那一下不再先把焦点搬到顶部入口：那一步只是把焦点挪到「搜索」图标上，用户读到的
        // 是「按返回焦点乱跑」；页头收起时那一行的高度是 0，焦点请求根本没有可见的落点，
        // 连下一次「焦点在入口上」的判断都成立不了——表现出来就是「按多少次返回都没反应、
        // 也退不出去」。因此首页的返回一律直接走「再按一次退出」。
        else -> when {
            collection!=null -> { collection=null; catalogFocus=""; showCatalog() }
            tab!=TAB_HOME -> switchTab(TAB_HOME)
            else -> confirmExitImpl()
        }
    }
}

/**
 * 主界面按返回的退出：**按两次**——第一次只弹一条提示，[EXIT_CONFIRM_WINDOW_MS] 之内
 * 再按一次才 [finish]。
 *
 * 不再弹确认框：电视上没有指针，确认框每次都要用户把焦点从「取消」挪到「退出」，
 * 比再按一下返回慢；而「再按一次退出」是 Android 上最熟的手势，第一次的提示已经把
 * 「要按两次」说清楚了。退出前也不需要再确认一遍数据：本机记录每次改动都即时落盘，
 * 播放进度在上屏 / 退出播放时已经写过（见 [saveProgress]）。
 */
internal fun TvMainActivity.confirmExitImpl() {
    val now=android.os.SystemClock.elapsedRealtime()
    if(exitArmedAt!=0L && now-exitArmedAt<=EXIT_CONFIRM_WINDOW_MS) {
        exitArmedAt=0L
        finish()
        return
    }
    exitArmedAt=now
    tvTools.toast(EXIT_TOAST)
}

/**
 * 第一次收到手指，就把触屏适配点亮（见 `MainActivityTouch.onTouchSeen`）。
 *
 * **与按键路径完全无关**：这里只读 `MotionEvent`，遥控器的 `KeyEvent` 一个字节都不碰。
 * 电视 / 盒子永远收不到 `ACTION_DOWN`，因此 `touchArmed` 在它们上面恒为 false，
 * 触摸层的三块代码（手势层 / 控制条 / 拖动进度）一次都不会构造。
 */
internal fun TvMainActivity.prepareDispatchTouchEvent(event: MotionEvent) {
    if(event.actionMasked==MotionEvent.ACTION_DOWN) {
        if(!touchArmed) onTouchSeen()
        // 「第一下就生效」：先按坐标把焦点交给手指下面那个控件（见 [focusUnderTouch]）。
        // 放在 super 之前是关键——控件在 ACTION_UP 里看到自己已经 isFocused()，
        // 就不会走 AOSP 那条「取到焦点则吞掉这次点击」的分支。
        focusUnderTouch(event.x,event.y)
    }
    // 播放设置面板或底部控制条上的一次触摸也算「操作」：无操作自动消失的计时从这一下重新开始。
    // 拖动滑块 / 拖进度条 / 滚动面板只发 MOVE，因此按下的同时也要看 MOVE——否则手指按着
    // 好几秒、到点就被收走。选集面板是独立窗口，触摸到不了这里，由它自己的触摸外壳通知。
    if((playbackMenu!=null || hudShown()) &&
        (event.actionMasked==MotionEvent.ACTION_DOWN || event.actionMasked==MotionEvent.ACTION_MOVE))
        scheduleOverlayAutoHide()
}

/**
 * 按键分发；返回 null 表示原实现会交回 `super.dispatchKeyEvent(event)`。
 */
internal fun TvMainActivity.handleDispatchKeyEvent(event: KeyEvent): Boolean? {
    // 正在清除本地数据：所有写盘队列已经关掉，这时任何一次收藏 / 设置改动都会抛异常；
    // 进程马上就会被结束，直接把按键吞掉即可（也避免界面在杀进程前闪一下错误）。
    if(resetting) return true
    // 播放设置面板 / 控制条开着时的任何一下按键都算「操作」：自动消失的计时从这一下重新开始。
    // 选集面板是独立窗口，按键走它自己的窗口监听（见 `EpisodePanel`），不经过这里。
    if(playbackMenu!=null || hudShown()) scheduleOverlayAutoHide()
    // 遥控器「搜索」键（84）：M6 / 10P 这类盒子的 PhoneWindow 会直接调用
    // launchDefaultSearch，把 ACTION_SEARCH 解析到厂商预置、但实际并不存在的
    // com.imtv.speechdemo；该 ROM 又不捕获 ActivityNotFoundException，按键会闪退。
    // 因此任何页面都先吞掉这一下，再按页面决定去向。
    if(event.keyCode==KeyEvent.KEYCODE_SEARCH) {
        if(event.action==KeyEvent.ACTION_UP) when {
            // 播放页 / 推荐页：只是别把系统搜索框叫起来，不打断正在看的片子。
            screen=="player" || screen=="recommend" -> Unit
            // 搜索页：搜索键只把焦点交给输入框（语音入口已经去掉）。
            screen=="catalog" && tab==TAB_SEARCH &&
                !(tvToolsReady && tvTools.showing) ->
                main.post { focusHongguoKeyboard() }
            else -> {
                switchTab(TAB_SEARCH)
                // 搜索页带着上一次的关键词进来时，输入框不会自动取焦点（见 searchBar 的
                // requestFocus 条件）；用户按搜索键的意图是「来打字 / 说话」，这里补一次。
                main.post { focusHongguoKeyboard() }
            }
        }
        return true
    }
    // 遥控器麦克风键：搜索页不再接管（语音入口已经去掉），一律交给系统语音助手。
    // 导航栏圆形入口整排关掉之后，菜单键补成「前往某页」：那条排入口是切换标签的唯一常驻
    // 入口，关掉之后返回键只回首页、搜索键只进搜索页，分类 / 榜单 / 我的 / 设置没有别的路。
    // **焦点在卡片上时不接管**：那一下是长按菜单（收藏 / 稍后看 / 隐藏），由卡片自己的监听器吃。
    if(event.keyCode==KeyEvent.KEYCODE_MENU && event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 &&
        screen=="catalog" && library.isLoaded && nav.isEmpty() && !focusOnCard()) {
        showNavPicker(currentFocus ?: navEntry())
        return true
    }
    // 推荐播放界面（类似抖音）：上下换剧、确定进正片、菜单开播放设置、返回回主页面。
    //
    // 这一层**必须在播放器分支之前**：上下键不能落进 `playerKeyAction`（那里上=播放设置、
    // 下=选集），而推荐页明确要求这两下只换作品。播放设置菜单打开时不再接管方向键，
    // 交给菜单自己的焦点链（下面播放器分支里 `!panel` 的条件就是为这个留的）。
    if(screen=="recommend") {
        val key=event.keyCode
        if(key==KeyEvent.KEYCODE_BACK) {
            if(event.action==KeyEvent.ACTION_UP) {
                // 返回键收的是一层浮层，**一次一层**，收完最后一层才回主页面：
                //   1. 播放设置（菜单键打开的那块竖向面板）——它盖住了下半屏，
                //      用户按返回的意图几乎总是「把这块收掉」，而不是退出这一页；
                //   2. 触屏那边唤出来的控制条（与播放界面同一条，见 `showHud`）——
                //      电视上它从不出现，这一支在遥控器上不存在；
                //   3. 都收干净了，才走 [goBackImpl] 回主页面。
                // 以前这里没有第 1 层：菜单开着按返回会直接退出推荐页，用户看到的
                // 是「设置还在屏幕上、画面却换了」。
                when {
                    playbackMenu!=null -> hidePlaybackOverlay()
                    hudReady && hud.visibility==View.VISIBLE -> hidePlaybackOverlay()
                    else -> goBackImpl()
                }
            }
            return true
        }
        if(playbackMenu!=null) {
            // 播放设置打开时：菜单键收起它（按下 / 抬起都吃掉，别让系统菜单插一脚），
            // 方向键与确定键交给菜单自己的焦点链。
            //
            // **打开菜单的那一次按键不能顺手把它收起**：短按菜单键会先来 DOWN（下面
            // 那一支开菜单）、再来同一次按键的 UP。以前这里对 UP 无条件收起，于是菜单
            // 一闪就没了，用户必须长按住（UP 来得晚）才看得见——正是「播放设置显示出来
            // 就隐藏了」。用 [playbackMenuOpenPress] 认掉这一次抬起：只吞键、不收起。
            if(key==KeyEvent.KEYCODE_MENU) {
                if(event.action==KeyEvent.ACTION_UP) {
                    if(playbackMenuOpenPress) playbackMenuOpenPress=false
                    else hidePlaybackOverlay()
                }
                return true
            }
        } else {
            if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                // 上下：只换剧（需求 2）。按住不连发：换一部要等详情 / 起播，
                // 连发只会把中间几部白白跳过。
                KeyEvent.KEYCODE_DPAD_UP -> if(event.repeatCount==0) { switchRecommend(-1); return true } else Unit
                KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) { switchRecommend(1); return true } else Unit
                // 左右：快退 / 快进（触屏那边唤出来的控制条属于手指，遥控器不必去选它）。
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekRecommend(-1,event.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekRecommend(1,event.repeatCount); return true }
                // 确定键：进入正式播放界面（追剧）。
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> {
                    enterFormalPlayback(); return true
                }
                // 记下「菜单是被这一下按开的」：它的 UP 随后就到，不能当成再按一次。
                // 只有真的开出来了才记——停在「正在加载 / 取不到推荐」那一屏时没有播放层，
                // showPlaybackMenu() 会直接返回，这一位留着没有意义。
                KeyEvent.KEYCODE_MENU -> {
                    showPlaybackMenu()
                    playbackMenuOpenPress=playbackMenu!=null
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { switchRecommend(1); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { switchRecommend(-1); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { requestPlayback(true); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { requestPlayback(false); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekRecommend(1,0); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekRecommend(-1,0); return true }
            }
            // 推荐页没有可聚焦控件：方向键 / 确定 / 菜单一律吞掉，避免焦点跑出画面。
            // 音量、电源这类系统键必须交回框架，否则遥控器就调不了音量了。
            if(key in listOf(
                    KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_MENU
                )) return true
        }
    }
    if(screen=="player" || screen=="recommend") {
        val key=event.keyCode
        if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBackImpl(); return true }
        if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                KeyEvent.KEYCODE_MEDIA_NEXT -> if(screen=="recommend") switchRecommend(1) else skipEpisode(1)
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if(screen=="recommend") switchRecommend(-1) else skipEpisode(-1)
                KeyEvent.KEYCODE_MEDIA_STOP -> when {
                    screen=="recommend" -> exitRecommendToHome()
                    // 从推荐进的正片：停止 = 回首页（与返回键同一条路）。
                    playbackFromRecommend -> leaveRecommendPlaybackToHome()
                    else -> returnToDetail()
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                else -> togglePlayback()
            }
            return true
        }
        // 播放设置菜单打开时（[panel]）：方向键一律交给焦点系统，PlaybackMenu.wire() 已把四向
        // 锁在菜单内部，停在最后一行（定时）再按下键，焦点按 nextFocusDownId 落在自己身上、
        // 原地不动。这里**不能**再接管下键去弹选集：菜单是「上键」打开的浮层，用户在里面按到
        // 边缘只应该表示「到头了」，而不是顺带换一个界面（选集仍然可以由菜单关闭后的下键、
        // 底部居中的下拉箭头，以及返回键回到简介页进入——下键绑成什么由按键设置决定）。
        //
        // 六个方向键 / 菜单键 / 确定键在播放界面里**不做焦点移动**，一律按当前绑定去执行动作
        //（绑定与出厂值见 [com.hgtv.star.core.KeyMap]：上 / 菜单 = 播放设置、下 = 选集、
        // 左 = 快退、右 = 快进、确定 = 播放 / 暂停）。这一条以前是写死的 `when(key)`，
        // 现在按键设置页可以改，改完立刻生效、并随本机偏好与备份一起走。
        //
        // 播放界面上本来就没有可聚焦的按钮（控制条的图标与状态项都不抢焦点），
        // 因此这里不去「把键放给焦点系统」，直接把这一下消化掉：
        // 绑成「无」的键也只是什么都不做，不会让焦点跑到别处去。
        // 返回键在上面已经单独处理，任何绑定下都退得出播放界面。
        if(!panel) playerKeyAction(key)?.let { return dispatchPlayerKey(it,event) }
    }
    return null
}

/**
 * 焦点是不是落在某张剧卡上（含卡片内部的子控件）。
 *
 * 卡片自己接管菜单键 = 长按菜单；导航兜底不能抢这一下（见 [handleDispatchKeyEvent]）。
 */
internal fun TvMainActivity.focusOnCard(): Boolean {
    var view=currentFocus ?: return false
    while(true) {
        if(view is PosterCard) return true
        val parent=view.parent as? View ?: return false
        if(parent===root) return false
        view=parent
    }
}
