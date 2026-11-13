// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.os.Build
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.shared.compat.detectDeviceForm
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.R

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
//   · `dispatchKeyEvent` 的遥控器动作一行不改；唯一动过的是推荐页里那一排触屏按钮的焦点分支
//     （那一排已经整个去掉，见 `attachRecommendOverlay`），按键语义因此回到「左右快进退」。
//
// 四块内容：
//   1. 手势层 [attachTouchLayer]：单击切显隐；双击按三宫格分区（左 1/3 快退、中 1/3 暂停/播放、
//      右 1/3 快进）；长按右半屏 = 2 倍速，抬手恢复（见 [setPlaybackBoost]）；
//      推荐页另外还有上滑 / 下拉换剧（见 [canSwipeRecommend]）。
//      **两页的行为完全一致**：推荐页不再是「另一套触屏手势」。
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
fun TvMainActivity.onTouchSeen() {
    if(touchArmed) return
    // 形态已经定下来了（用户手选的，或算法判出来的）就按它跑：
    // 电视档不点亮手势层，哪怕这台机器真的能点（触屏一体机 / 盒子接了触摸屏）；
    // 手机 / 平板档在 [applyDeviceForm] 里就已经点亮了，走不到这里。
    // 只有「未判定」才靠真实手指兜底。
    if(deviceMode()!=com.hgtv.star.shared.compat.DeviceForm.UNKNOWN) return
    touchArmed=true
    wireSystemInsets()
    // 正停在播放页：这一下手指多半就是「点在了画面上」，因此补完这三样之后
    // 顺手把控制条亮出来——否则这一下点击没有任何可见反馈（新挂上的手势层
    // 来不及收到本次事件，它是在事件分发途中才被加进树里的）。
    if(screen=="player" || screen=="recommend") {
        attachTouchLayer(); attachTouchBar(); wireSeekTouch()
        // 推荐页：这一页在触屏上**不建**底部那块浮层（见 `attachRecommendOverlay`
        // 开头那条提前返回），因此这里也不再重挂它——把已经挂上的那一块摘掉即可。
        if(screen=="recommend") dismissRecommendOverlay()
        if(hud.visibility!=View.VISIBLE) showHud()
    }
}

/**
 * 启动时就把「这台设备有触摸屏」这件事确认下来。
 *
 * 为什么不能在等手指：等手指意味着**第一次点画面之前**建出来的界面是电视形态
 * （推荐页挂着电视那块底部浮层、播放页没有手势层），点下去之后整块重建成触屏形态——
 * 用户看到的就是「刚一打开的样子」和「点一下之后的样子」不一样（高度都变了）。
 *
 * 判据不用 `Configuration.touchscreen`：国内盒子谎报 FINGER 能力值是常态。
 * 这里直接问输入子系统「有没有注册过 `SOURCE_TOUCHSCREEN` 的设备」——谎报配置容易，
 * 谎报一台不存在的输入设备没有意义（真报了，系统里也就真有一块能点的屏）。
 */
fun TvMainActivity.armTouchIfScreenExists() {
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
 * 按当前形态决定这一轮要不要触屏适配。
 *
 * **规则只有一条：形态是什么，就按什么跑。**
 *
 * 1. 用户没设过（`auto`）：形态 = 算法判定（`detectDeviceForm()` 的打分结果）；
 * 2. 用户设过（手机 / 平板 / 电视）：形态 = 他选的那一档，算法结果只留给调试面板看。
 *
 * - 电视 → 不建手势层（遥控器 + 底部不出现触屏控制条）；
 * - 手机 / 平板 → 一律点亮（手势层 + 底部控制条 + 上滑下拉换剧）；
 * - 未判定（算法自己都没跑起来）→ 退回输入子系统探测 [armTouchIfScreenExists]。
 *
 * 耗时：`detectDeviceForm()` 只读几次系统属性 / 能力位 / 输入设备列表，
 * 实测在 2GB 盒子上是一次亚毫秒级的调用，放在建第一屏之前没有代价。
 */
fun TvMainActivity.applyDeviceForm() {
    val profile=runCatching { detectDeviceForm() }.getOrNull()
    deviceProfile=profile
    when(deviceMode()) {
        // 设计画布**不按形态分档**：所有形态共用 1280×720（见 `designScale` 的说明）。
        com.hgtv.star.shared.compat.DeviceForm.TV -> touchArmed=false
        com.hgtv.star.shared.compat.DeviceForm.PHONE,
        com.hgtv.star.shared.compat.DeviceForm.TABLET -> {
            touchArmed=true
            wireSystemInsets()
        }
        // 算法都跑不出结果：才退回输入子系统（真有一块屏就点亮）。
        com.hgtv.star.shared.compat.DeviceForm.UNKNOWN -> if(!touchArmed) armTouchIfScreenExists()
    }
}

/**
 * 这一轮实际生效的形态：手动选择优先，其次是启动时的探测结果。
 *
 * 只在「需要按形态分叉」的地方用（旋转方向、设置页读数），不要再据此去算触屏与否——
 * 那件事的唯一判据是 [touchArmed]，两者可能不一样（触屏电视：形态是电视、却真有一块屏）。
 */
fun TvMainActivity.deviceMode(): com.hgtv.star.shared.compat.DeviceForm =
    com.hgtv.star.shared.compat.DeviceForm.ofStored(library.deviceForm)
        ?: deviceProfile?.form
        ?: com.hgtv.star.shared.compat.DeviceForm.UNKNOWN

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
fun TvMainActivity.wireSystemInsets() {
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
fun TvMainActivity.attachTouchLayer() {
    if(touchLayer!=null || (screen!="player" && screen!="recommend")) return
    // 竖向滑动的记账（**只有推荐页用得上**，播放页那两条分支一次都不会走到这里）：
    //   [swipeStartX]/[swipeStartY] 这一次手势的落点；
    //   [swipeGesture] 这一次手势已经被判成「竖向滑动」——从判定那一刻起直到抬手，
    //                  事件都不再交给 GestureDetector，否则抬手会被它当成一次轻点，
    //                  刚换完剧又把刚唤出来的控制条收起来了。
    //
    // 放在这个函数里而不是 Activity 字段上：同一次 attach 就是同一份状态，
    // 重建手势层（换集 / 换分辨率）时自然归零，不需要额外复位代码。
    var swipeStartX=0f
    var swipeStartY=0f
    var swipeGesture=false
    // —— 左右滑动调进度（播放页与推荐页同一套）——
    //
    // [scrub] 这一串手势已经判成「拖进度」；[scrubLastX] 上一帧的横坐标（增量累加用）；
    // [scrubFrom]/[scrubTarget]/[scrubDuration] 起手位置、当前目标、这一集总长。
    // 三者都放在这一次 attach 里：重建手势层（换集 / 换分辨率）即归零，不需要额外复位。
    var scrub=false
    var scrubLastX=0f
    var scrubFrom=0L
    var scrubTarget=0L
    var scrubDuration=0L
    // 速度测量：判「滑得快还是慢」，据此缩放每一帧挪动的时间（慢拖细调、快滑大步）。
    // 每次按下重新 obtain，抬手 / 取消时 recycle——一个手势一份，不跨手势复用旧速度。
    var tracker: android.view.VelocityTracker?=null
    // 方向判定用的系统阈值（与控件自带的 `touchSlop` 同一个值，这里只取一次）。
    val swipeSlop=ViewConfiguration.get(this).scaledTouchSlop
    val layer=FrameLayout(this).apply {
        // 不挡无障碍：这一层是透明的手势区，不是内容。
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // 单击切显隐；双击按三宫格分区；长按右半屏 2 倍速。
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
                // 推荐页与播放页现在是**同一条控制条**（见 [attachTouchBar] / `showHud`），
                // 因此轻点的语义也完全一致：露着就收、收着就露。
                if(!onPlaybackPage()) return false
                if(playbackMenu!=null) { dismissPlaybackMenu(); hidePlaybackOverlay(); return true }
                if(hud.visibility==View.VISIBLE) hidePlaybackOverlay() else showHud()
                return true
            }
            /**
             * 双击：**按三宫格分区**（推荐页与播放页同一套，见需求「其他的触屏行为和播放界面保持一致」）。
             *
             * * 左 1/3：快退 10 秒；
             * * 中 1/3：暂停 / 播放；
             * * 右 1/3：快进 10 秒。
             *
             * 分区用整屏宽现算（不是写死的 dp）：手机竖着、平板横着、外接屏都能对上手指位置。
             * 快进 / 快退走 [seekStep]：它按页面分流（推荐页用 `seekRecommend`），
             * 用户不必知道两页背后是两条函数。
             */
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if(!onPlaybackPage()) return false
                // 播放设置菜单开着时不吃双击：那时面板占着下半屏，用户是在点面板。
                if(playbackMenu!=null) return false
                val third=width/3f
                when {
                    event.x<third -> {
                        seekStep(-1)
                        flashSeekHint("快退 10 秒",false)
                    }
                    event.x>third*2f -> {
                        seekStep(1)
                        flashSeekHint("快进 10 秒",true)
                    }
                    else -> {
                        togglePlayback()
                        val playing=player?.playWhenReady==true
                        flashSeekHint(if(playing) "播放" else "暂停",true,centered=true)
                    }
                }
                return true
            }
            /**
             * 长按：**按住右半屏 = 2 倍速**（抬手恢复，见 [setPlaybackBoost]）。
             *
             * 左半屏长按不做事：快退已经有双击，再叠一层长按容易误触；
             * 而「按住加速看过去」是手机上最自然的一个手势，放在右半屏也正好与
             * 「右侧双击快进」的方向一致。
             */
            override fun onLongPress(event: MotionEvent) {
                if(!onPlaybackPage() || playbackMenu!=null) return
                if(event.x<width/2f) return
                setPlaybackBoost(true)
            }
        })
        setOnTouchListener { _,event ->
            val action=event.actionMasked
            // 按下那一刻才把「这是触屏设备」这件事点亮（见 [onTouchSeen]）。
            if(action==MotionEvent.ACTION_DOWN && !touchArmed) onTouchSeen()
            // 抬手 / 事件被系统收走：长按倍速到这里结束（不管有没有触发过长按，调用是幂等的）。
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) setPlaybackBoost(false)
            // 这一帧要不要交给 GestureDetector（默认要：轻点 / 双击 / 长按全在它那里）。
            var deliver=true
            when(action) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX=event.x
                    swipeStartY=event.y
                    swipeGesture=false
                    scrub=false
                    scrubLastX=event.x
                    tracker?.recycle()
                    tracker=android.view.VelocityTracker.obtain().apply { addMovement(event) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    if(swipeGesture) {
                        // 已经判成竖向滑动：后续帧不再进 detector（它会误判成轻点 / 拖拽）。
                        deliver=false
                    } else if(scrub) {
                        // 拖进度中：这一串事件不再交给 detector（否则抬手会被当成轻点，
                        // 刚唤出来的控制条又收起来）。
                        deliver=false
                        scrubTarget=scrubTargetOf(event.x,scrubLastX,tracker,scrubTarget,scrubDuration,width)
                        scrubLastX=event.x
                        val fraction=scrubTarget.toFloat()/scrubDuration
                        // 走现成的拖动入口：它按页面分流（播放页 / 推荐页两条进度条），
                        // 并且会挡住每秒一次的刷新把读数顶回去。
                        onScrubProgress(fraction,false)
                        showScrubHint(scrubTarget,scrubFrom,scrubDuration)
                        // 推荐页那条路径不会自己露控制条（见 `onRecommendScrub`），
                        // 这里每帧都叫一次：既保证进度条一直看得见，也顺带把自动收起的
                        // 计时重新排上——拖多久，控制条就留多久。
                        showHud()
                    } else if(canSwipeRecommend()) {
                        val dy=event.y-swipeStartY
                        val dx=event.x-swipeStartX
                        // 方向判定：竖向位移既要过系统的 touch slop，又要明显大于横向位移。
                        // 后者是给「双击左边快退」那一下留的余地——那一串事件也会经过这里。
                        if(Math.abs(dy)>swipeSlop &&
                            Math.abs(dy)>Math.abs(dx)*1.2f) {
                            swipeGesture=true
                            // **这一帧仍然交给 detector**：GestureDetector 正是在收到「超出 slop 的 MOVE」
                            // 时才取消自己排着的长按定时器（AOSP 里 `mAlwaysInTapRegion` 那一段）。
                            // 不交的话，用户滑到一半会先冒出「2 倍速播放中」——长按 500ms 照样会触发。
                            deliver=true
                        }
                    }
                    // 横向滑动调进度：门槛用 1.5 倍 slop，且必须明显比竖向位移大，
                    // 这样「竖向换剧」（推荐页）与「横向拖动」不会互相抢。
                    if(!swipeGesture && !scrub) {
                        val dx=event.x-swipeStartX
                        val dy=event.y-swipeStartY
                        val duration=scrubDurationOrZero()
                        if(duration>0L && Math.abs(dx)>swipeSlop*1.5f && Math.abs(dx)>Math.abs(dy)*1.5f) {
                            scrub=true
                            scrubLastX=event.x
                            scrubDuration=duration
                            scrubFrom=currentPosition().coerceIn(0L,scrubDuration)
                            scrubTarget=scrubFrom
                            // 这一帧**仍然交给 detector**：只有收到一个超出 slop 的 MOVE，
                            // GestureDetector 才会取消自己排着的长按定时器（同竖向滑动那一段）。
                            // 不交的话，按住右半屏拖进度会在 500ms 时冒出「2 倍速播放中」。
                            deliver=true
                            // 真的触发过长按也已经结束了：拖动期间不需要倍速。
                            setPlaybackBoost(false)
                            showScrubHint(scrubTarget,scrubFrom,scrubDuration)
                            // 起手就落一次「拖动中」：这一帧之后每秒一次的那次刷新
                            // 不能把读数顶回去（播放页看 `scrubbing`、推荐页看 `recommendScrubbing`）。
                            onScrubProgress(scrubFrom.toFloat()/scrubDuration,false)
                            // 一判成拖动就把控制条亮出来：用户要看到的就是那条进度条。
                            showHud()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if(scrub) {
                        deliver=false
                        scrub=false
                        hideScrubHint()
                        // 抬手才真跳：走与拖动进度条同一条提交路径（播放页走 pendingSeek 合并窗，
                        // 推荐页走它自己那条 seek）。
                        onScrubProgress(scrubTarget.toFloat()/scrubDuration,true)
                    } else if(swipeGesture) {
                        deliver=false
                        swipeGesture=false
                        val dy=event.y-swipeStartY
                        // 抬手速度：**快速一划**不该要求划满 [recommendSwipeDistance]。
                        // 那一条是按「屏幕高度的 7%」算的（手机竖屏约 170px），抖一下就走完，
                        // 但真正的「快速上下切换」是一甩几十像素就抬手——早先这种一甩被判成没滑够，
                        // 用户读到的是「划了没反应、再划一下才换」。
                        // 门槛只在手势本身判成竖向之后才用得上（见上面 `swipeGesture` 的判定）。
                        val speed=runCatching {
                            tracker?.computeCurrentVelocity(1000)
                            Math.abs(tracker?.yVelocity ?: 0f)
                        }.getOrDefault(0f)
                        // 上滑（手指向上，dy<0）= 下一部；下拉（dy>0）= 上一部。
                        // 走的是和「上下键」同一条路（[switchRecommend]）：补页 / 环绕 / 过渡黑场全都不用重写。
                        val threshold=if(speed>=FLING_SWIPE_SPEED) recommendSwipeDistanceFling() else recommendSwipeDistance()
                        if(Math.abs(dy)>=threshold) switchRecommend(if(dy<0f) 1 else -1)
                    }
                    tracker?.recycle(); tracker=null
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 被系统收走（来电、手势条）：这一串作废，下一次按下重新判。
                    swipeGesture=false
                    if(scrub) {
                        scrub=false
                        // 不跳：把读数还给播放器报的位置（见 [cancelScrubUi]）。
                        cancelScrubUi()
                    }
                    tracker?.recycle(); tracker=null
                }
            }
            if(deliver) detector.onTouchEvent(event)
            true
        }
    }
    touchLayer=layer
    // 插在**弹幕之上、其余一切之下**：播放页的弹幕 / 信息条 / 控制条都装在同一层里
    //（见 `playEpisode` 的 `overlayLayer`），那一层的第 0 个子控件永远是弹幕层，
    // 因此 index=1 是确定的。
    //
    // 不插进标题条 / 时钟 / 控制条之上：`FrameLayout` 按子控件**倒序**做命中测试，
    // 位置越靠后优先级越高，控制条上的按钮必须继续先拿到事件；反过来，弹幕层
    // （index=0）不可点、不处理 `MotionEvent`，因此压在它上面不会让手势失效——
    // 事件会一路上浮到这一层。
    //
    // 挂在这一层里而不是 `root` 上：竖屏短剧全屏转画面时，这一层跟着一起转，
    // 手指按到的位置与看到的控件才对得上（旋转后的命中测试由 `ViewGroup` 反解矩阵完成）。
    val host=overlayLayer ?: root
    host.addView(layer,1.coerceAtMost(host.childCount),FrameLayout.LayoutParams(-1,-1))
}

/**
 * 推荐页的竖向滑动（上滑下一部 / 下拉上一部）现在能不能吃。
 *
 * 三个前提：
 * * 真的是在推荐页（播放页那三宫格手势与竖向滑动无关）；
 * * 播放设置面板没有开着——那一块自己就是可滚动的，竖向拖动属于它；
 * * 画面上确实有东西（`playerView` 非空）。停在「取不到推荐」那行字上时不响应滑动，
 *   否则用户以为在翻页，实际是重新取数，观感像「滑不动还闪了一下」。
 */
fun TvMainActivity.canSwipeRecommend(): Boolean =
    screen=="recommend" && playbackMenu==null && playerView!=null

/**
 * 这一集能不能横滑调进度：能就返回总时长，不能返回 0。
 *
 * 三个前提与拖进度条那条路径完全一致（见 [onScrubProgress]）：
 * 在播放页、没开播放设置面板、**播放器已经准备好且报得出时长**。
 * 最后一条同时兼作「手势门槛」以外的第二道保险：还没出画面的那一两秒里横滑，
 * 面板会闪出来又没有可拖的东西，读起来像「滑了一下什么都没发生」。
 */
private fun TvMainActivity.scrubDurationOrZero(): Long {
    if(!onPlaybackPage() || playbackMenu!=null) return 0L
    val p=player ?: return 0L
    if(!playbackReady || p.duration<=0) return 0L
    return p.duration
}

/**
 * 横滑一帧要挪多少时间 —— **按滑动快慢自动缩放**。
 *
 * ## 为什么不能是一条固定比例
 *
 * 固定「屏宽 = 整集」在两种极端上都不能用：
 *
 * * 短剧一集 90 秒：手指还没走出十分之一屏，进度已经跑完 9 秒，抖一下就到头；
 * * 长片一集 45 分钟：想挪 10 秒，手指得移动不到屏宽的 0.4%——根本对不准。
 *
 * 因此这里按**这一帧的横向速度**给一个系数：慢拖时系数压到 0.35（细调，一屏只走三分之一集），
 * 快滑时放大到 4（甩一下就能跨大半集）。系数乘在「这一帧的位移」上，
 * 逐帧累加，手感上就是「慢慢蹭 = 一点点挪，快速甩 = 大步跳」。
 *
 * 参考速度取**每秒扫过 60% 屏宽**：这是正常滑动的量级，此时系数正好是 1。
 *
 * 累加而不是「绝对位置映射」还有一个原因：绝对映射下手指必须一直在屏幕里，
 * 快滑到边缘就得松手重来；累加式可以在同一位置反复甩。
 *
 * 结果夹在 `[0, 时长 − 500ms]`：留半秒，避免拖到最末尾触发 `STATE_ENDED` 自动换集。
 */
private fun scrubTargetOf(
    x: Float,
    lastX: Float,
    tracker: android.view.VelocityTracker?,
    target: Long,
    duration: Long,
    width: Int
): Long {
    val w=width.coerceAtLeast(1).toFloat()
    // 速度取「像素/秒」：computeCurrentVelocity 里那一千是单位换算，不是采样窗口。
    runCatching { tracker?.computeCurrentVelocity(1000) }
    val speed=Math.abs(tracker?.xVelocity ?: 0f)
    val factor=(speed/(w*0.6f)).coerceIn(0.35f,4f)
    val dx=(x-lastX).toDouble()
    val delta=(dx*factor*duration.toDouble()/w).toLong()
    return (target+delta).coerceIn(0L,(duration-500L).coerceAtLeast(0L))
}

/**
 * 横滑调进度时那块读数：`快进 00:00:35` + `03:12 / 12:40`。
 *
 * 与底部那条进度条**同时**出现（滑动一开始就 [showHud]）：进度条给「在整集里的位置」，
 * 这块给「这一次挪了多少」，两者一起读才知道自己甩了多远。
 *
 * 只建一次、之后只改文字：拖动时每秒几十帧，每帧 new 一个带柔光底板的 TextView
 * 是在主线程上做无谓的测量与绘制。
 */
fun TvMainActivity.showScrubHint(target: Long,from: Long,duration: Long) {
    val host=touchLayer ?: return
    val delta=target-from
    val content=(if(delta>=0) "快进 " else "快退 ")+formatSpan(Math.abs(delta))+"\n"+
        formatSpan(target)+" / "+formatSpan(duration)
    var label=touchScrubHint
    if(label==null) {
        label=text("",17f,TvStyle.playerText).apply {
            gravity=Gravity.CENTER
            setLineSpacing(dp(4).toFloat(),1f)
            setPadding(dp(26),dp(14),dp(26),dp(14))
            background=TvStyle.shape(this@showScrubHint,0x88000000.toInt(),radius=TvStyle.RADIUS_PILL)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        touchScrubHint=label
        host.addView(label,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER).apply {
            // 略高于正中：不压住画面主体，也不与「2 倍速播放中」那块（-120）打架。
            topMargin=-dp(150)
        })
    }
    if(label.text.toString()!=content) label.text=content
}

/** 抬手 / 取消：把那块读数摘掉（它不自己消失）。 */
fun TvMainActivity.hideScrubHint() {
    touchScrubHint?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
    touchScrubHint=null
}

/**
 * 手势被系统收走（来电 / 手势条 / 返回）：**不跳**，把读数还给播放器报的位置。
 *
 * 两个「拖动中」标记都要清：播放页用 [scrubbing]，推荐页用 `recommendScrubbing`
 *（见 [onScrubProgress] / `onRecommendScrub` 的分流）。留一个没清，这一页的进度条
 * 就会一直停在手指最后一次的位置，直到下一次真正拖动。
 */
fun TvMainActivity.cancelScrubUi() {
    scrubbing=false
    recommendScrubbing=false
    hideScrubHint()
    updatePlaybackText()
}

/**
 * 竖向滑动换剧的距离阈值：**屏高的 7%，下限 56 设计单位**。
 *
 * 不写死成固定 dp：手机竖屏（约 2400px 高）7% ≈ 170px 差不多是两指宽的一划，
 * 平板横屏（约 1600px 高）≈ 112px 也正好是「明确地划一下」的手感。
 * 写死小值会让手机上轻轻一蹭就换台，写死大值会让平板上怎么划都不动。
 */
fun TvMainActivity.recommendSwipeDistance(): Float =
    maxOf(dp(56).toFloat(),resources.displayMetrics.heightPixels*0.07f)

/**
 * 「快速一甩」的抬手速度门槛（px/s）。
 *
 * 800 px/s 大约是「一秒划过大半屏」那一档：再低会把慢拖也当成甩，
 * 再高就要求用户真的甩出去。这一档与常见短视频客户端的判据同一量级。
 */
private const val FLING_SWIPE_SPEED=800f

/**
 * 快速一甩时要求的位移：比慢拖小得多（约 1/3）。
 *
 * 甩的那一下位移本来就短（手指走了 40~80px 就抬起），要求 170px 等于「甩了不算」。
 * 只认速度够快的那一批，因此不会把误触（手指抖一下）判成换剧——
 * 竖向手势的门槛（touch slop）已经在判定 `swipeGesture` 时过了一遍。
 */
fun TvMainActivity.recommendSwipeDistanceFling(): Float =
    maxOf(dp(28).toFloat(),resources.displayMetrics.heightPixels*0.025f)

/**
 * 手势层上的瞬时提示（`快进 10 秒`）。
 *
 * 直接挂在手势层上、2 秒后自己撤掉：它只用来说明「刚才那一下做了什么」，
 * 不参与焦点、不参与点击，也不该被 `hidePlaybackOverlay` 收走（那时画面已经暗了，
 * 用户正需要看到这条反馈）。
 */
fun TvMainActivity.flashSeekHint(message: String,forward: Boolean,centered: Boolean=false) {
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
        // [centered] 时（中 1/3 双击暂停 / 播放）就摆在正中。
        if(!centered) { if(forward) leftMargin=dp(360) else rightMargin=dp(360) }
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
 * 触屏「按住右半屏 = 2 倍速」。
 *
 * 只在**正在播放**时生效：暂停状态下按住不该把片子拽着往前走。
 * 恢复用的是 `library.playbackSpeed`（用户在倍速设置里选的那一档），
 * 不是写死的 1x——用户本来就选了 1.5x，抬手后应该回到 1.5x。
 *
 * 幂等：没触发过长按时调用 `false` 是空操作，因此手势层可以在每次抬手时无脑调一次，
 * 不必先判断「刚才那一下到底是不是长按」。
 */
fun TvMainActivity.setPlaybackBoost(active: Boolean) {
    if(active) {
        if(playbackBoost) return
        if(player?.playWhenReady!=true) return
        playbackBoost=true
        player?.setPlaybackSpeed(2f)
        showBoostHint(true)
        updatePlaybackText()
        return
    }
    if(!playbackBoost) return
    playbackBoost=false
    // 注意恢复的是设置里那一档：`setPlaybackSpeed` 会被 `requestPlayback` / 起播再次对齐，
    // 这里先落回去，屏幕上那行「倍速」也就跟着回到原值（见 `updatePlaybackText`）。
    player?.setPlaybackSpeed(library.playbackSpeed)
    showBoostHint(false)
    updatePlaybackText()
}

/**
 * 「2 倍速播放中」那块常驻小标签：按下时挂在手势层正中偏上，抬手即撤。
 *
 * 与 [flashSeekHint] 的区别是**不会自己消失**：倍速一直生效到手指抬起，
 * 提示留到那时才收，用户才不会误以为「闪一下就没了，速度也回去了」。
 */
fun TvMainActivity.showBoostHint(show: Boolean) {
    val host=touchLayer ?: return
    if(!show) {
        touchBoostHint?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        touchBoostHint=null
        return
    }
    if(touchBoostHint!=null) return
    val label=text("2 倍速播放中",17f,TvStyle.playerText).apply {
        gravity=Gravity.CENTER
        setPadding(dp(22),dp(10),dp(22),dp(10))
        background=TvStyle.shape(this@showBoostHint,0x66000000,radius=TvStyle.RADIUS_PILL)
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    touchBoostHint=label
    host.addView(label,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER).apply {
        // 略高于正中：不压住画面主体，也不和下面那条「快退 / 快进」提示重叠。
        topMargin=-dp(120)
    })
}

/**
 * 快退 / 快进 10 秒：**按页面分流**。
 *
 * 两页背后是两条函数（`seek` 走 `pendingSeek` 合并窗，`seekRecommend` 走它自己那一套
 * 每秒 tick 的读数刷新），但对手指来说这只是同一件事。触摸层的三处调用点
 * （双击左右 1/3、控制条的快退 / 快进按钮）因此都走这里，
 * 将来再加一处手势也不必各自记得判断页面。
 */
fun TvMainActivity.seekStep(direction: Int) {
    if(screen=="recommend") { seekRecommend(direction,0); return }
    seek(direction,0)
}

/**
 * 控制条底部那一块（**按页面分流**）。
 *
 * 为什么不是复用现有的控制条：现有 `hud` 是照着遥控器设计的——只有一枚播放图标、
 * 一条不能拖的进度、一排只显示当前值的文字。触屏设备上它缺三件事：
 * 选集没有入口（只靠底部那枚小箭头）、快进快退没有入口、进度不能拖。
 * 这里补一块，**只在触屏设备上挂**，`hud` 本身的布局一个像素都不动。
 *
 * * 播放页：六枚按钮（选集 / 快退 / 播放 / 快进 / 设置 / 返回），与遥控器的六键一一对应
 *   （见 `navigation/TvKeyMap.kt` 的出厂绑定），因此两端的操作心智一致，用户不会学到两套；
 * * 推荐页：**不摆这六枚**，只放一枚「剧名」标题，点它进正片（见 [attachRecommendEntry]）。
 */
fun TvMainActivity.attachTouchBar() {
    // 两页共用 `hud`（轻点画面唤出），但**底部这一块内容不同**：
    //   · 播放页：选集 / 快退 / 播放 / 快进 / 设置 / 返回 六枚按钮；
    //   · 推荐页：只有一枚「剧名」标题，点它进正片（见 [attachRecommendEntry]）。
    //     推荐页是「刷」，把播放页那排按键搬过来会让人以为在正片里。
    // 电视上 `touchArmed` 恒为 false，这条路径不存在，两页的观感一个像素都不变。
    if(touchBar!=null || !onPlaybackPage()) return
    // 底部那枚「下拉 → 选集」箭头：播放页要，推荐页不要（那一页只有一枚剧名入口）。
    hudEpisodeArrow?.visibility=if(screen=="recommend") View.GONE else View.VISIBLE
    if(screen=="recommend") { attachRecommendEntry(); return }
    // `hud` 是 lateinit：走到这里时播放页已经建好（调用点都在 `playEpisode` 建完视图之后），
    // 因此不需要再判一次——而且 lateinit 的 `isInitialized` 在扩展函数里取不到。
    val bar=row().apply { setPadding(0,dp(14),0,0) }
    // 六枚按钮各带一枚图标（与文字同一个控件里的两段内容，见 `TvButton` 的 `iconRes`）：
    // 图标是「动作是什么」的快速识别，文字是兜底说明，两者一起读比只有文字快得多。
    fun item(label: String,icon: Int,onClick: ()->Unit) {
        addButton(bar,label,size=ButtonSize.COMPACT,weight=1f,icon=icon,onClick=onClick)
    }
    item("选集",R.drawable.ic_player_episodes) { showEpisodePanel(hud) }
    // 快退 / 快进走 [seekStep]（按页面分流，将来这一排若也出现在推荐页不必再改）。
    item("快退",R.drawable.ic_player_rewind) { seekStep(-1); flashSeekHint("快退 10 秒",false) }
    // 播放 / 暂停：文案与图标都在 [updateTouchPlayLabel] 里跟着播放状态改。
    val played=addButton(bar,"播放",tone=ButtonTone.PRIMARY,size=ButtonSize.COMPACT,weight=1f,icon=R.drawable.ic_player_play) { togglePlayback() }
    touchPlayLabel=played
    item("快进",R.drawable.ic_player_forward) { seekStep(1); flashSeekHint("快进 10 秒",true) }
    item("设置",R.drawable.ic_toolbar_settings) { showPlaybackMenu() }
    item("返回",R.drawable.ic_player_back) { returnToDetail() }
    hud.addView(bar,lp(-1,-2))
    touchBar=bar
    updateTouchPlayLabel()
}

/**
 * 推荐页底部那一块：**只有一枚剧名**，点它进正片。
 *
 * ## 为什么不是按钮
 *
 * 这一页在触屏上不放播放页那排按键（选集 / 快退 / 播放 / 快进 / 设置 / 返回）：
 * 推荐页是「一部接一部地刷」，摆一排只对某一集有意义的按键会让人误以为已经进了正片。
 * 但「进正片」这个入口必须留着——它不只是换个页面，它才写观看历史、才清「已看完」标记
 *（`saveProgress` 里有 `if(screen=="recommend") return` 的守卫）。
 *
 * 因此这里做成一枚**标题**：大号粗体剧名 + 下面一行「点击进入正片」，
 * 右侧一个箭头。没有圆角底板、没有凸起柔光——读起来就是这一部剧的名字，
 * 光标（手指）停上去也不会变成「一颗按钮」。整块都是点击区，随手指点哪都行。
 *
 * 视觉上参照简介页那一块：大标题压在画面上、靠 `setShadowLayer` 立住字，
 * 底下那层渐变来自 `hud` 自己的 `TvStyle.panel()`，因此不需要再铺一块背板。
 */
