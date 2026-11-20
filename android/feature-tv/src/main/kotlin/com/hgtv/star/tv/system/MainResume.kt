// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.shared.debug.StartupTrace
import com.hgtv.star.tv.list.showCatalog

/**
 * `onResume` 在调用 `super` 之后的页面恢复编排。
 *
 * 调用方保留启动追踪锚点与 `super.onResume()` 的原始先后顺序；这里只承接恢复页面、
 * 延迟任务和前台状态同步。
 */
internal fun TvMainActivity.resumeAfterSuper(resumeStarted: Long) {
    // 清除本地数据期间：写盘队列已经关掉，这一轮页面重建里的任何一次缓存写入都会抛异常。
    // 进程马上就会被结束，这里直接跳过即可（授权弹窗关闭后会走到这里）。
    if(resetting) return
    foreground=true
    if(!mainInitialized) {
        // onCreate 的第二阶段还在等待首帧；书库 / 网络 / 页面对象尚未创建。
        // 初始化完成后的 loadLibrary 回调会按 foreground 状态继续打开首屏。
        StartupTrace.mark("main.onResume.deferred")
        StartupTrace.done("main.onResume",resumeStarted, "deferred=true")
        return
    }
    // 从语音界面回来：这一次重建不要把焦点交给输入框（否则软键盘会弹出来）。
    // 结果本身可能在 `onActivityResult` 里晚一步到，这里只先压住焦点。
    if(voiceLaunching) suppressSearchFocusOnce=true
    // 个别 ROM 不回调 onActivityResult：按取消收尾，别让「语音」按钮永久失效。
    // 延迟判定而不是当场判定——`onActivityResult` 通常先于 `onResume` 到达，
    // 但顺序并非所有 ROM 都一致；留一小段时间给它，避免把刚拿到的结果当成取消。
    if(voiceLaunching) main.postDelayed({
        if(voiceLaunching) {
            voiceLaunching=false; voiceReturned=true
            noteVoice("cancel")
        }
    },800L)
    main.removeCallbacks(initializationTick); main.post(initializationTick)
    // 回前台补一次心跳：设备休眠 / 厂商省电会让后台定时整体推迟，
    // 后台待两小时醒来时上一次心跳可能已隔了几小时。
    com.hgtv.star.shared.data.SessionBootstrap.onForeground()
    if(library.isLoaded) retryMirrorRestoreIfPermitted()
    // 回前台补一次账号数据：用户在手机 / 电脑上看过的剧，电视这边的「我的 → 历史」
    // 与首页历史栏都靠这一次刷新才跟上。[loadAccount] 自己按 [ACCOUNT_FRESH_MS] 节流
    // （一分钟内拉过就跳过），因此按 Home 再来一次不会多发请求；
    // 拉回来之后由 [refreshAccountViews] / [reconcileHistory] 只重画真的变了的那几处。
    if(library.isLoaded && repository.signedIn) {
        if(tab==TAB_PROFILE) loadCurrentAccountTab()
        else if(tab==TAB_HOME) loadAccount(columns=setOf("历史","收藏"))
    }
    if(library.isLoaded) {
        ensureTvLaunchRecommendDefault(library)
        val contentStarted=StartupTrace.now()
        StartupTrace.mark("main.onResume.content.start")
        // TV 默认落在首页；只有用户在设置里主动开启“打开应用进入推荐”才进推荐。
        //
        // 但**进程被回收后重建**是例外：这时用户按 Home 之前停在哪一页，就回哪一页
        //（见 [restoreLastScreen]）。改造前这里没有这一支，回来永远是首页。
        if(initialCatalogPending) {
            // 启动页可能已提前读完书库，此时 loadLibrary 的回调早于 onResume。
            // 首屏前必须再装配一次身份与账号，否则播放会拿仓库里的空身份回落到内置旧 ID。
            applySessionToken(); applyAccount()
            startupInitializing=false
            val saved=resumeRestoreBundle
            resumeRestoreBundle=null
            initialCatalogPending=false
            if(saved!=null && restoreLastScreenForResume(saved)) {
                // 已经按现场恢复，走正常启动路径会把它覆盖掉。
            } else if(library.launchRecommend) openRecommend() else showCatalog(load=true)
        }
        else if(restoreStoppedPage) {
            restoreStoppedPage=false
            when(screen) {
                // `recovering=playError`：错误页上退到后台再回来，**不是**一次全新尝试。
                // 传 false 会走 `onEpisodeStart`，把本集的自动重试 / 降档预算全部还回去
                // ——用户按一次 Home 再回来就能白得一轮「重建 1 次 + 降档 2 次」，
                // 「每集有界」当场失效。正常播放时 `playError` 是 false，照旧全量归零。
                "player" -> playEpisode(episodeIndex,startPosition,autoplay=false,recovering=playError)
                // 推荐页：列表还在就接着播这一部；列表空了（进程内的推荐状态被别处清过）
                // 就重新拉一次，别让用户回到一片黑屏上。
                // 与正式播放一致：从后台回来不自动接着播（用户可能已经按下暂停才切走的）。
                "recommend" -> if(recommendItems.isEmpty()) openRecommend()
                    else playRecommendAt(recommendIndex,startPosition,autoplay=false)
                "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                // 首页判空看的是八个剧架（[homeFeed]）：它的 catalog 本来就是空的，
                // 用 catalog 判断会让每次回到前台都重打八个榜单。
                else -> showCatalog(load=when {
                    // 集合页的内容是「本机 + 账号」合并出来的：回前台只重建（栅格分支里会
                    // 触发一次 loadAccount，账号那边新收藏 / 新看的剧随之出现）。
                    localCatalog() -> false
                    tab==TAB_HOME -> homeFeed.isEmpty()
                    tab==TAB_RANK -> catalog.isEmpty()
                    tab==TAB_CATEGORY -> catalog.isEmpty()
                    tab==TAB_SEARCH -> query.isNotBlank() && catalog.isEmpty()
                    else -> false
                })
            }
        } else {
            val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
        }
        StartupTrace.done(
            "main.onResume.content",
            contentStarted,
            "screen=$screen tab=$tab recommend=$launchRecommendPending",
        )
        root.post { StartupTrace.mark("main.onResume.firstFrame") }
        if(screen=="catalog") favoriteMonitor.check()
        if(screen=="player") syncMediaSession()
    } else libraryLoadError?.let(::showLibraryError)
    // 文件选择器回来的那一次：等上面这一轮页面重建（它会 cancelWork）走完，再读文件导入。
    if(pendingAccountUri!=null) main.post { consumePendingAccountFile() }
    // 语音结果同样等这一轮恢复（可能含一次页面重建）走完再落地。
    if(voiceReturned) main.post { applyVoiceReturn() }
    StartupTrace.done("main.onResume",resumeStarted, "libraryLoaded=${library.isLoaded}")
}
