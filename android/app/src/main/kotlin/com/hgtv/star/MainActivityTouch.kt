// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.TvStyle

// 本文件：触屏适配（平板 / 手机 / 触屏一体机）。
//
// 设计原则只有一条：**电视上这里一行都不执行**。
//
// 开关不是 `Configuration.touchscreen`（国内盒子谎报能力值是常态，一台谎报 FINGER 的盒子
// 会整段启用触摸层），而是「真的收到过一次手指」——见 [onTouchSeen]。没有手指的设备
// 永远收不到 `ACTION_DOWN`，因此这条路径对它是死代码。
//
// 与遥控器路径的关系：**只加 MotionEvent，绝不动按键**。
//   · 手势层只重写 `onTouchEvent` / `setOnTouchListener`，没有 `setOnKeyListener`；
//   · 控制条上的按钮是 [com.hgtv.star.tv.TvButton]，本来就可点（`setOnClickListener`），
//     它们在遥控器上会进入焦点链，但触摸层只在 `touchArmed` 时才建，TV 上根本不存在；
//   · `dispatchKeyEvent` 一行不改，遥控器按键到不了这里（Activity 先吃掉）。
//
// 三块内容：
//   1. 手势层 [attachTouchLayer]：单击切显隐、单击暂停、双击左右快退/快进；
//   2. 控制条 [attachTouchBar]：选集 / 快退 / 播放 / 快进 / 设置 / 返回 六枚可点按钮；
//   3. 进度拖动 [wireSeekTouch]：拖动期间只改显示，抬手才真跳（走现成的 pendingSeek 去抖）；
//   4. 系统栏 / 手势条 / 任务栏 inset [wireSystemInsets]：只在触屏设备上给 root 补内边距。

/**
 * 收到触摸事件时调用：第一次收到手指才把这条适配路径点亮。
 *
 * 只在 `ACTION_DOWN` 上判断一次，之后直接返回（热路径上只是一个布尔读取）。
 * 点亮时如果正停在播放页，顺手把这一页该补的东西补上——用户是先点了画面，
 * 才把「这是一台触屏设备」这件事告诉我们的，不能等到下一次进播放页才生效。
 */
internal fun MainActivity.onTouchSeen() {
    if(touchArmed) return
    touchArmed=true
    wireSystemInsets()
    // 正停在播放页：这一下手指多半就是「点在了画面上」，因此补完这三样之后
    // 顺手把控制条亮出来——否则这一下点击没有任何可见反馈（新挂上的手势层
    // 来不及收到本次事件，它是在事件分发途中才被加进树里的）。
    if(screen=="player" || screen=="recommend") {
        attachTouchLayer(); attachTouchBar(); wireSeekTouch()
        // 推荐页：这一页的浮层是「按键节奏」建的（只有三行文字），点亮触屏后要重挂一次
        // 才会长出手指能按的那三枚按钮（见 `attachRecommendOverlay`）。
        if(screen=="recommend") detail?.let { attachRecommendOverlay(it.series) }
        if(hud.visibility!=View.VISIBLE) showHud()
    }
}

/**
 * 启动时就把「这台设备有触摸屏」这件事确认下来。
 *
 * 为什么不能在等手指：等手指意味着**第一次点画面之前**建出来的界面是电视形态
 * （推荐页浮层没有那排按钮、播放页没有手势层），点下去之后整块重建成触屏形态——
 * 用户看到的就是「刚一打开的样子」和「点一下之后的样子」不一样（高度都变了）。
 *
 * 判据不用 `Configuration.touchscreen`：国内盒子谎报 FINGER 能力值是常态。
 * 这里直接问输入子系统「有没有注册过 `SOURCE_TOUCHSCREEN` 的设备」——谎报配置容易，
 * 谎报一台不存在的输入设备没有意义（真报了，系统里也就真有一块能点的屏）。
 */
internal fun MainActivity.armTouchIfScreenExists() {
    if(touchArmed) return
    val hasScreen=runCatching {
        android.view.InputDevice.getDeviceIds().any { id ->
            val device=android.view.InputDevice.getDevice(id) ?: return@any false
            (device.sources and android.view.InputDevice.SOURCE_TOUCHSCREEN)==android.view.InputDevice.SOURCE_TOUCHSCREEN
        }
    }.getOrDefault(false)
    if(!hasScreen) return
    touchArmed=true
    wireSystemInsets()
}

/**
 * 系统栏 / 手势条 / 平板上那条任务栏占掉的边。
 *
 * 只在触屏设备上执行（[touchArmed]），且只在 API 20+ 上执行：
 * `systemWindowInsetBottom` 是 20 才有的，4.4 的盒子也没有手势条。
 *
 * 用 root 的内边距而不是给每个页面加：全站页面都是 `root` 的整屏子控件，
 * 一处内边距就把所有页面（含播放页）一起让开，不用改任何页面的布局代码。
 *
 * 沉浸式那三个标志（`SYSTEM_UI_FLAG_FULLSCREEN` 等，见 `onCreate`）**不动**：
 * 电视上的全屏是硬需求，改错会让页头被系统栏压住。这里只处理「系统明确报了边距」
 * 的情况——此时那三条标志本来就已经失效（例如平板上划出了导航栏）。
 */
internal fun MainActivity.wireSystemInsets() {
    if(!touchArmed) return
    if(Build.VERSION.SDK_INT<Build.VERSION_CODES.KITKAT_WATCH) return
    root.setOnApplyWindowInsetsListener { view,insets ->
        // **只让出底部，左右一律不动。**
        //
        // 左右不动是刻意的：整站的栅格宽度是按 `displayMetrics.widthPixels` 算的
        // （见 `gridContentWidth` / `categoryFilterWidth`），而这些函数不知道 root 有内边距。
        // 一旦在这里让出左右，卡片会按「整屏宽」排出来、再被 root 裁掉右边一截
        // （横屏平板上三键导航在右侧时尤其明显）。沉浸式那三条标志本来就藏着导航栏，
        // 因此左右内边距实际上几乎恒为 0，不动它们没有任何损失。
        //
        // 底部让一半：手势条所在的那一条是透明的，全让开会把画面顶上去一截、
        // 在播放页留出一条黑边；一半既让控制条的可点区域躲开手势区，又不改变构图。
        val bottom=insets.systemWindowInsetBottom/2
        if(view.paddingBottom!=bottom) view.setPadding(0,0,0,bottom)
        insets
    }
    root.requestApplyInsets()
}

/**
 * 播放页的手势层：压在画面上、控制条之下，负责「点一下」这件事。
 *
 * 为什么必须有它：`PlayerView` 是 `useController=false` + `isFocusable=false`
 *（见 `playEpisode`），不挂这一层的话，手指点画面**没有任何反应**——
 * 而 `hud`（控制条）默认是 `GONE`，只能由遥控器的「控制条」键唤出。
 * 也就是说没有这一层，平板用户在播放页里连暂停都做不到。
 *
 * 层级：`root` 里 `playerView`(0) → 弹幕层(1) → **手势层(2)** → 标题条 / 时钟 / 控制条。
 * 因此控制条上的按钮永远先拿到事件，手势层只吃「空白画面」上的那一下。
 */
internal fun MainActivity.attachTouchLayer() {
    if(touchLayer!=null || (screen!="player" && screen!="recommend")) return
    val layer=FrameLayout(this).apply {
        // 不挡无障碍：这一层是透明的手势区，不是内容。
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // 单击 / 双击切显隐、双击左右半屏快退快进。
        //
        // 用 `GestureDetector` 而不是自己数两次 DOWN 的间隔：双击的判定还与
        // 「两次落点距离」「系统双击超时」有关，自己写必然和系统输入法 / 手势冲突。
        // `setOnTouchListener` 而不是继承 View：这一层不需要自绘，少一个类。
        val detector=GestureDetector(this@attachTouchLayer,object: GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent)=true
            /**
             * 单击：切显隐。
             *
             * 用 [GestureDetector.OnGestureListener.onSingleTapConfirmed] 而不是 `onSingleTapUp`：
             * 后者在双击的第一下就会触发，用户双击快进时会先看到控制条闪一下再收起。
             */
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                // 推荐页（整屏连播）：这一页没有控制条，轻点只切底部那块信息浮层。
                if(screen=="recommend") { toggleRecommendBar(); return true }
                if(screen!="player") return false
                if(playbackMenu!=null) { dismissPlaybackMenu(); hidePlaybackOverlay(); return true }
                if(hud.visibility==View.VISIBLE) hidePlaybackOverlay() else showHud()
                return true
            }
            /** 双击：左半屏退 10 秒、右半屏进 10 秒。 */
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if(screen!="player" && screen!="recommend") return false
                val forward=event.x>=width/2f
                if(screen=="recommend") seekRecommend(if(forward) 1 else -1,0) else seek(if(forward) 1 else -1,0)
                flashSeekHint(if(forward) "快进 10 秒" else "快退 10 秒",forward)
                return true
            }
            /**
             * 长按：按住屏幕不放 = 暂停（与手机上「按住看细节」的习惯相反，
             * 但对「一只手拿平板」的场景更顺手）。这里只做暂停，不做倍速——
             * 倍速已经有设置项，长按再叠一层容易误触。
             */
            override fun onLongPress(event: MotionEvent) {
                if((screen!="player" && screen!="recommend") || playbackMenu!=null) return
                if(player?.playWhenReady==true) requestPlayback(false)
            }
        })
        setOnTouchListener { _,event ->
            // 按下那一刻才把「这是触屏设备」这件事点亮（见 [onTouchSeen]）。
            if(event.actionMasked==MotionEvent.ACTION_DOWN && !touchArmed) onTouchSeen()
            detector.onTouchEvent(event)
            true
        }
    }
    touchLayer=layer
    // 插在**画面之上、其余一切之下**：`root` 里第一个子控件永远是 `PlayerView`
    //（见 `playEpisode` 的 `!reuse` 分支），因此 index=1 是确定的。
    //
    // 不插进标题条 / 时钟 / 控制条之上：`FrameLayout` 按子控件**倒序**做命中测试，
    // 位置越靠后优先级越高，控制条上的按钮必须继续先拿到事件；反过来，弹幕层
    // （原 index=1，现在被挤到 2）不可点、不处理 `MotionEvent`，因此压在它下面
    // 不会让手势失效——事件会一路上浮到这一层。
    root.addView(layer,1.coerceAtMost(root.childCount),FrameLayout.LayoutParams(-1,-1))
}

/**
 * 手势层上的瞬时提示（`快进 10 秒`）。
 *
 * 直接挂在手势层上、2 秒后自己撤掉：它只用来说明「刚才那一下做了什么」，
 * 不参与焦点、不参与点击，也不该被 `hidePlaybackOverlay` 收走（那时画面已经暗了，
 * 用户正需要看到这条反馈）。
 */
internal fun MainActivity.flashSeekHint(message: String,forward: Boolean) {
    val host=touchLayer ?: return
    touchSeekHint?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
    val label=text(message,17f,TvStyle.playerText).apply {
        gravity=Gravity.CENTER
        setPadding(dp(22),dp(10),dp(22),dp(10))
        background=TvStyle.shape(this@flashSeekHint,0x66000000,radius=TvStyle.RADIUS_PILL)
        alpha=0f
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    touchSeekHint=label
    host.addView(label,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER).apply {
        // 左右各偏一格半屏宽：提示落在用户点的那一侧，不压住画面正中。
        if(forward) leftMargin=dp(360) else rightMargin=dp(360)
    })
    label.animate().alpha(1f).setDuration(90).start()
    label.postDelayed({
        label.animate().alpha(0f).setDuration(220).withEndAction {
            (label.parent as? ViewGroup)?.removeView(label)
            if(touchSeekHint===label) touchSeekHint=null
        }.start()
    },900)
}

/**
 * 推荐页那块浮层（剧名 / 集数 / 那一排按钮）现在是不是**露着**。
 *
 * 三个条件缺一不可：手指已经点亮过触屏（否则这一排根本不存在）、用户没有轻点收起、
 * 播放设置菜单没有把它顶掉。
 *
 * 这个判断同时决定了方向键的语义：露着时左右是「在这一排按钮里挪焦点」，
 * 收起时回到「快退 / 快进」；返回键也是「先收它、再退出这一页」。
 */
internal fun MainActivity.recommendBarShown(): Boolean =
    touchArmed && !recommendBarHidden && recommendOverlay?.visibility==View.VISIBLE

/** 焦点此刻是不是落在那一排按钮上（确定键要先给按钮，而不是直接追剧）。 */
internal fun MainActivity.recommendBarFocused(): Boolean {
    val actions=recommendActions ?: return false
    return currentFocus?.parent===actions
}

/**
 * 左右键在「看正片 / 暂停 / 换一部」之间挪一格。
 *
 * 焦点还不在这一排时，右键落在第一枚、左键落在最后一枚——与焦点的常规手感一致，
 * 不会让「这一排露着但按左右没反应」再出现一次。
 *
 * 到两端就停住（`coerceIn`）：这一排只有三枚，环绕会让「按了右反而跑到最左」更难理解。
 */
internal fun MainActivity.moveRecommendBarFocus(delta: Int) {
    val actions=recommendActions ?: return
    if(actions.childCount==0) return
    val focused=currentFocus
    val index=(0 until actions.childCount).firstOrNull { actions.getChildAt(it)===focused } ?: -1
    val next=if(index<0) (if(delta>0) 0 else actions.childCount-1)
    else (index+delta).coerceIn(0,actions.childCount-1)
    actions.getChildAt(next).requestFocus()
}

/**
 * 推荐页浮层的显隐（轻点画面 / 返回键都走这里）。
 *
 * 用 alpha 淡出而不是直接 `GONE`：0.14 秒的淡出读起来才像「响应了」。
 * 收起时把焦点从按钮上收回：留在看不见的按钮上，下一次左右键会像是「按了没反应」。
 */
internal fun MainActivity.setRecommendBar(show: Boolean) {
    val bar=recommendOverlay ?: return
    recommendBarHidden=!show
    bar.animate().cancel()
    if(show) {
        bar.visibility=View.VISIBLE; bar.alpha=0f
        bar.animate().alpha(1f).setDuration(140).start()
    } else {
        if(recommendBarFocused()) currentFocus?.clearFocus()
        bar.animate().alpha(0f).setDuration(140).withEndAction {
            if(bar.alpha==0f) bar.visibility=View.GONE
        }.start()
    }
}

/** 轻点画面：露着就收起、收起就露回来。 */
internal fun MainActivity.toggleRecommendBar() {
    // 播放设置菜单占着下半屏时不动它：这时浮层是让位状态，点画面不该把它从菜单底下拽出来。
    if(playbackMenu!=null) return
    setRecommendBar(recommendBarHidden || recommendOverlay?.visibility!=View.VISIBLE)
}

/**
 * 播放控制条上那一排**可点**按钮。
 *
 * 为什么不是复用现有的控制条：现有 `hud` 是照着遥控器设计的——只有一枚播放图标、
 * 一条不能拖的进度、一排只显示当前值的文字。触屏设备上它缺三件事：
 * 选集没有入口（只靠底部那枚小箭头）、快进快退没有入口、进度不能拖。
 * 这里补一排按钮，**只在触屏设备上挂**，`hud` 本身的布局一个像素都不动。
 *
 * 六个动作与遥控器的六键一一对应（见 `MainActivityKeyMap.kt` 的出厂绑定），
 * 因此两端的操作心智一致，用户不会学到两套。
 */
internal fun MainActivity.attachTouchBar() {
    if(touchBar!=null || screen!="player") return
    // `hud` 是 lateinit：走到这里时播放页已经建好（调用点都在 `playEpisode` 建完视图之后），
    // 因此不需要再判一次——而且 lateinit 的 `isInitialized` 在扩展函数里取不到。
    val bar=row().apply { setPadding(0,dp(14),0,0) }
    // 六枚按钮各带一枚图标（与文字同一个控件里的两段内容，见 `TvButton` 的 `iconRes`）：
    // 图标是「动作是什么」的快速识别，文字是兜底说明，两者一起读比只有文字快得多。
    fun item(label: String,icon: Int,onClick: ()->Unit) {
        addButton(bar,label,size=ButtonSize.COMPACT,weight=1f,icon=icon,onClick=onClick)
    }
    item("选集",R.drawable.ic_player_episodes) { showEpisodePanel(hud) }
    item("快退",R.drawable.ic_player_rewind) { seek(-1,0); flashSeekHint("快退 10 秒",false) }
    // 播放 / 暂停：文案与图标都在 [updateTouchPlayLabel] 里跟着播放状态改。
    val played=addButton(bar,"播放",tone=ButtonTone.PRIMARY,size=ButtonSize.COMPACT,weight=1f,icon=R.drawable.ic_player_play) { togglePlayback() }
    touchPlayLabel=played
    item("快进",R.drawable.ic_player_forward) { seek(1,0); flashSeekHint("快进 10 秒",true) }
    item("设置",R.drawable.ic_toolbar_settings) { showPlaybackMenu() }
    item("返回",R.drawable.ic_player_back) { returnToDetail() }
    hud.addView(bar,lp(-1,-2))
    touchBar=bar
    updateTouchPlayLabel()
}

/**
 * 播放键的文案**与图标**跟着播放状态走（`播放` / `暂停` / 出错时 `重试`）。
 *
 * 图标不跟着换的话，暂停之后按钮上还是一枚「播放」三角，与文字「暂停」自相矛盾；
 * 「重试」借用「恢复默认」那枚环形箭头——它表达的就是「再来一次」。
 */
internal fun MainActivity.updateTouchPlayLabel() {
    val label=touchPlayLabel ?: return
    val (next,icon)=when {
        playError -> "重试" to R.drawable.ic_action_restore
        playbackReady && player?.playWhenReady==true -> "暂停" to R.drawable.ic_player_pause
        else -> "播放" to R.drawable.ic_player_play
    }
    if(label.text.toString()!=next) label.text=next
    label.setIcon(icon)
}

/**
 * 把控制条上那条进度条接成「可拖」。
 *
 * 拖动期间**只改显示**：`progressBar.progress` 与时间文案由手指决定，
 * 真跳要等抬手。理由和遥控器那边一样（见 `seek` / `seekRunnable`）：
 * 拖动过程中每一次 `player.seekTo()` 都会触发一次 `onPositionDiscontinuity`
 * 与一次缓冲判断，连续几十次会让 ExoPlayer 反复 reset 渲染管线，画面直接卡死。
 *
 * 抬手那一下走 [MainActivity.seekTo]：它会清掉 pendingSeek 并真跳一次，
 * 因此拖动不会和遥控器的「连续快进」叠在一起。
 */
internal fun MainActivity.wireSeekTouch() {
    // **不按 screen 判断**。这一条曾经写成 `if(screen!="player") return`，
    // 于是「推荐页 → 看正片」这条路（进正片时不复用 `playEpisode`）永远接不上：
    // 控件是推荐页那一次建的，接线时 screen 还是 `recommend`，直接跳过，
    // 到了正式播放里那条进度条就一直是死的（平板用户反馈的正是这一条）。
    // 真正按页分流的地方在 [onScrubProgress]：拖动发生时 `screen` 已经是最终值。
    // `progressBar` 是 lateinit，但所有调用点都在 `playEpisode` 建完控制条之后
    //（与 `attachTouchBar` 直接用 `hud` 同一个前提），因此这里不再判空。
    progressBar.onScrub={ fraction,finished -> onScrubProgress(fraction,finished) }
}

/** 拖动进度条的一帧：[finished] 为 true 表示手指抬起，这一刻才真跳。 */
internal fun MainActivity.onScrubProgress(fraction: Float,finished: Boolean) {
    // 推荐页用的是另一条进度条、另一套刷新（每秒 tick 而不是 `updatePlaybackText`），
    // 因此这里按「此刻在哪一页」分流，而不是按「接线时在哪一页」。
    if(screen=="recommend") { onRecommendScrub(fraction,finished); return }
    val p=player ?: return
    val duration=p.duration
    if(duration<=0 || !playbackReady) return
    val target=(duration*fraction).toLong().coerceIn(0,(duration-500).coerceAtLeast(0))
    if(!finished) {
        scrubbing=true
        progressBar.progress=fraction
        playbackText.text="${formatSpan(target)} / ${formatSpan(duration)}"
        showHud()
        return
    }
    scrubbing=false
    seekTo(target)
}

/**
 * 触屏「第一下就生效」的关键：**在 ACTION_DOWN 之前把焦点交给手指下面那个控件**。
 *
 * ## 为什么需要
 *
 * `View.onTouchEvent()` 的 ACTION_UP 分支有一句（AOSP 原文）：
 *
 * ```java
 * boolean focusTaken = false;
 * if (isFocusable() && isFocusableInTouchMode() && !isFocused()) {
 *     focusTaken = requestFocus();
 * }
 * ...
 * if (!focusTaken) performClick();      // ← 取到焦点的那一次**不**点击
 * ```
 *
 * 也就是说：一个 `isFocusableInTouchMode = true` 的控件在**没有焦点**时，
 * 第一次点击只会取走焦点、不触发点击，第二次才 `performClick`。
 * 用户读到的就是「点一下选中、再点一下才按下」。
 *
 * 全站的按钮 / 卡片 / 选集格都是 `isFocusableInTouchMode = true`——这个标志是给
 * **电视**写的（让焦点系统能把焦点送进去）。但电视不在触摸模式，该标志对遥控器
 * 毫无作用；到了平板上它让每一次「点了没焦点的那一张」都变成两下。
 *
 * ## 做法
 *
 * 控件属性一个都不改，只在事件分发**之前**按坐标找到目标并 `requestFocusFromTouch()`。
 * 事件真正送到目标时它已经 `isFocused()`，UP 里 `focusTaken` 为 false，一次点击生效。
 *
 * 因此：
 * * 遥控器路径**一行不变**（`ACTION_DOWN` 只有手指会产生）；
 * * 控件仍然 `isFocusableInTouchMode=true`，电视的焦点环、焦点链、`nextFocus*` 全部原样；
 * * 平板上点击照样有焦点高亮（"选中"与"按下"合成为一次动作，观感更接近手机）。
 */
internal fun MainActivity.focusUnderTouch(x: Float,y: Float) {
    val host=window?.decorView as? View ?: return
    focusUnderTouch(host,x,y)
}

/**
 * 在 [host] 这个视图树里按窗口坐标找「最深的、能聚焦或能点击的」控件，并把焦点交给它。
 *
 * 坐标逐层换算（`child` 的局部坐标 = 父级坐标 + 父级 scroll − child 的 left/top），
 * 因此滚动容器里滚到一半的卡片也能算对。
 *
 * 找不到就不动（点在空白处 / 不可点的容器上），这与「点击本身什么都不会发生」一致。
 */
internal fun focusUnderTouch(host: View,x: Float,y: Float) {
    val target=hitTestFocusable(host,x,y) ?: return
    if(!target.isFocusableInTouchMode || target.isFocused) return
    // 用 `requestFocus()` 而不是 `requestFocusFromTouch()`：
    // 后者内部会先 `ensureTouchMode(false)` **退出触摸模式**，随后手指这一下再把它切回来——
    // 一次点击里模式来回翻转，输入框是否弹键盘、hover 行为都会跟着抖。
    // `View.onTouchEvent` 自己也是调的 `requestFocus()`，这里与框架完全同路径，只是提前了一步。
    target.requestFocus()
}

/**
 * 命中测试：返回该坐标下最深的可聚焦 / 可点击控件。
 *
 * [fallback] 是「最近一个能聚焦或能点击的祖先」——点在卡片里的文字上时，
 * 命中会一路下钻到 `TextView`（它既不可聚焦也不可点），这时要回到外层那张卡片。
 */
private fun hitTestFocusable(view: View,x: Float,y: Float,fallback: View?=null): View? {
    if(view.visibility!=View.VISIBLE) return null
    val candidate=if(view.isFocusableInTouchMode || view.isClickable) view else fallback
    if(view is ViewGroup) {
        // 倒序遍历：后加进来的子控件画在上层，命中优先级更高（与 FrameLayout 的语义一致）。
        for(index in view.childCount-1 downTo 0) {
            val child=view.getChildAt(index)
            val cx=x+view.scrollX-child.left
            val cy=y+view.scrollY-child.top
            if(cx<0f || cy<0f || cx>=child.width || cy>=child.height) continue
            hitTestFocusable(child,cx,cy,candidate)?.let { return it }
        }
    }
    return candidate
}

/**
 * 弹窗用的触摸外壳：把「DOWN 之前先聚焦」这件事带到**独立窗口**里。
 *
 * 为什么弹窗要单独做一层：`Activity.dispatchTouchEvent` 只覆盖 Activity 自己的窗口。
 * `TvDialog` / 选集面板 / 相关推荐都是 `AlertDialog` 或 `Dialog`，各自有独立的
 * `PhoneWindow`，触摸事件根本不经过 Activity。不套这一层的话，弹窗里点一枚
 * **当前没有焦点**的按钮仍然是两下（同一个 `focusTaken` 机制）。
 *
 * 用法只有一个：`wrapper(content)`。它把内容原样包一层，布局行为与直接
 * `setContentView(content)` 一致（`FrameLayout` + MATCH_PARENT 的内容）。
 */
internal class TouchFocusHost(context: android.content.Context) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 与 Activity 里同一条规则：DOWN 之前先把焦点交给手指下面那个控件。
        // 事件真正送到目标时它已经 isFocused()，UP 里 `focusTaken` 为 false，一次点击生效。
        if(event.actionMasked==MotionEvent.ACTION_DOWN) focusUnderTouch(this,event.x,event.y)
        return super.dispatchTouchEvent(event)
    }
}

/**
 * 把 [content] 包进 [TouchFocusHost]，供弹窗使用（见该类的说明）。
 *
 * [fillHeight] 是给「自己撑满弹窗」的内容用的（选集面板的网格、相关推荐的卡片条）：
 * 它们原来由 `Dialog.setContentView` 直接给 MATCH_PARENT，套一层壳之后要显式保留，
 * 否则会塌成 wrap_content、底部留出一块透明。
 *
 * 普通弹窗（`TvDialog`）用默认值：内容宽度撑满、高度交给内容自己，与套壳之前完全一致。
 */
internal fun touchFocusHost(content: View,fillHeight: Boolean=false): View {
    val host=TouchFocusHost(content.context)
    host.addView(content,FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        if(fillHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    return host
}
