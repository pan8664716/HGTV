// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv
import com.hgtv.star.shared.debug.DebugLog

import android.app.Activity
import android.view.*
import android.widget.*
import com.hgtv.star.core.*
import com.hgtv.star.tv.list.showCatalog
import com.hgtv.star.shared.data.PlaybackCapability
import com.hgtv.star.shared.data.account
import com.hgtv.star.shared.data.accountCache
import com.hgtv.star.shared.data.clearRegistrationPending
import com.hgtv.star.shared.data.commitPendingProfile
import com.hgtv.star.shared.data.hasPendingProfile
import com.hgtv.star.shared.data.registrationNeedsRefresh
import com.hgtv.star.shared.data.registrationPending
import com.hgtv.star.shared.data.registrationProfile
import com.hgtv.star.shared.data.qualityChosenByUser
import com.hgtv.star.shared.data.registrationCookies
import com.hgtv.star.shared.data.saveAccount
import com.hgtv.star.shared.debug.Diagnostics
import com.hgtv.star.shared.debug.StartupTrace
import com.hgtv.star.shared.debug.devicePrefix
import kotlinx.coroutines.launch

// 本文件是从 TvMainActivity.kt 拆出来的「账号参数导入（本机文件 / 手机扫码）与设备注册」这一块。
// 这些成员仍然属于 TvMainActivity，只是以扩展成员（TvMainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

fun TvMainActivity.pickAccountFile() {
    val intent=android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(android.content.Intent.CATEGORY_OPENABLE)
        type="*/*"
        putExtra(android.content.Intent.EXTRA_MIME_TYPES,arrayOf("text/xml","application/xml","text/plain","*/*"))
    }
    runCatching { startActivityForResult(intent,REQUEST_ACCOUNT_FILE) }
        .onFailure { tvTools.info("本机文件","这台设备上没有可用的文件选择器。可以改用「手机扫码」上传。",null) }
}
/**
 * 读回选中的文件内容并导入（在 onResume 收尾之后调用，见 [pickAccountFile] 的说明）。
 *
 * 大小上限与手机上传那条路一致（[AccountImportServer.MAX_BYTES]）：`cookieStore.xml`
 * 实测约 180 KB，超限的肯定不是这个文件。
 */
fun TvMainActivity.consumePendingAccountFile() {
    val uri=pendingAccountUri ?: return
    pendingAccountUri=null
    io.submit {
        val text=runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8) }
        main.post {
            if(isDestroyed) return@post
            text.onSuccess { value ->
                if(value.isNullOrBlank()) tvTools.info("导入失败","文件是空的，或读不到内容。",null)
                else importAccount(value,null)
            }.onFailure { failure ->
                tvTools.info("导入失败","读取文件失败：${failure.message.orEmpty().take(120)}",null)
            }
        }
    }
}
/**
 * 导入账号参数：把手机上送来的文本（cookieStore.xml 或整段 Cookie）解析成凭据并存到本机。
 *
 * 解析在后台线程跑（XML 最大约 500 KB），解析结果立刻做一次账号校验：
 * 通过就刷新「我的」页与首页两栏；不通过就如实提示，**不写入**本机（免得留一份坏凭据）。
 *
 * **不走 [work]**：与 [loadAccount] 同一个理由——`work` 的回调会被任何一次
 * [invalidatePage] 丢掉，导入这条路上恰好每次都会经过一次页面重建。
 */
fun TvMainActivity.importAccount(text: String,anchor: View?) {
    // 需要联网校验：等待期间显示屏幕正中的加载框，校验完（成功 / 失败）再收掉。
    showCenterLoading("正在验证账号参数…")
    io.submit {
        val result=runCatching { parseCredentials(text) }
        main.post {
            if(isDestroyed) return@post
            result.onSuccess { credentials ->
                library.saveAccount(credentials)
                io.submit {
                    library.flushBlocking()
                    main.post {
                        if(isDestroyed) return@post
                        applyAccount()
                        // 导入的会话只有 cookie、没有 `X-Tt-Token`：立刻打一次 boot 心跳，
                        // 让服务端在响应头里把 token 发回来（拦截器收到即落盘）。
                        com.hgtv.star.shared.data.SessionBootstrap.onAppStart(force=true)
                        hideCenterLoading()
                        showHint("账号参数已导入")
                        // 登录成功直接落在「我的 → 历史」：只取第一页历史，其他栏目等点击再取。
                        accountTab="history"
                        loadAccountHistory(reset=true,force=true)
                        showCatalog()
                    }
                }
            }.onFailure { failure ->
                DebugLog.record("账号","导入失败 · ${failure.message.orEmpty().take(140)}")
                hideCenterLoading()
                showHint("账号参数导入失败")
                tvTools.info("导入失败",failure.message.orEmpty().take(200),anchor)
            }
        }
    }
}
/**
 * 把手机上送来的文本解析成凭据：`cookieStore.xml` 走 XML 解析，其余按 `key=value` 串处理。
 *
 * 解析完立刻做一次账号校验：通过才写入本机，免得留一份坏凭据。
 */
fun TvMainActivity.parseCredentials(text: String): AccountCredentials =
    // 解析 + 校验整段搬到了 `:shared`（`AccountLogin.importText`）：手机端走同一个入口，
    // 两边的「坏凭据要挡住」规则只写一份。
    com.hgtv.star.shared.account.AccountLogin.importText(text, repository, library)

/**
 * 拉取账号数据（资料 + 四条列表），成功后重建「我的」页与首页两栏。
 *
 * **不走 [work]**：`work` 的回调挂在「当前页次」上，任何一次 [invalidatePage]
 * （切页、重建、回前台时的 showCatalog）都会把在途的回调丢掉；而 [accountLoading]
 * 是在回调里复位的，于是它一旦被丢掉就永远停在 true——之后每次进这一页都直接早退，
 * 界面上就是「一直显示正在读取，很久没有结果」。这里自己管一个序号：
 * 只有最后一次请求的回调会落地，且无论成功失败都会把 [accountLoading] 放回去。
 */
fun TvMainActivity.loadAccount(
    force: Boolean=false,
    columns: Set<String> = AccountSnapshot.KEYS,
) {
    if(accountLoading && !force && accountLoadingColumns==columns) return
    val requestedColumnEmpty = columns.any { column ->
        when(column) {
            "资料" -> accountData?.profile==null
            "收藏" -> accountData?.favorites.isNullOrEmpty()
            "历史" -> accountData?.history.isNullOrEmpty()
            "点赞" -> accountData?.likes.isNullOrEmpty()
            "预约" -> accountData?.reserves.isNullOrEmpty()
            else -> false
        }
    }
    val cache=library.accountCache()
    val now=System.currentTimeMillis()
    // 先铺本地缓存，再继续发网络请求做增量刷新。缓存只负责首屏速度，不能替代网络。
    if(!force && cache!=null && now-cache.savedAt in 0..ACCOUNT_FRESH_MS) {
        val first=accountData==null
        accountData=cache.data; accountError=null
        // 缓存是**这一页刚打开时**拿到的（原先这一页要等一次请求才敢铺内容）：现在要补一次重建。
        // 必须 post 到下一轮：调用方此刻多半正在 [showCatalog] 里铺这一页，
        // 在渲染过程中再调一次 showCatalog 会边建边拆。
        if(first && (cache.data.usable || cache.data.history.isNotEmpty() ||
                cache.data.favorites.isNotEmpty() || cache.data.reserves.isNotEmpty())) {
            main.post { if(!isDestroyed && foreground) refreshAccountViews(AccountSnapshot.KEYS) }
        }
    }
    // 真正要发请求了才留一行日志：账号数据这一路在电视上没法调试，
    // 「启动时到底有没有去拉」只有这一行说得清。
    android.util.Log.i(
        "HGTV",
        "Account fetch force=$force columns=$columns cache=${cache!=null} tab=$tab screen=$screen",
    )
    accountLoading=true
    accountLoadingColumns=columns
    if(tab==TAB_PROFILE && accountTabWaiting(accountTab)) {
        main.post {
            if(!isDestroyed && tab==TAB_PROFILE && accountTabWaiting(accountTab)) {
                refreshAccountListIfVisible()
            }
        }
    }
    // 「用户按了刷新按钮」与「别处顺手强制拉一次（导入 / 登录 / 换集）」要分开：
    // 只有前者才改按钮文案、才额外报一句结果，否则会把「账号参数已导入」这类提示顶掉。
    val manualRefresh=force && accountRefreshing
    val token=++accountToken
    appScope.launch {
        val result=accountController.refresh(columns)
        if(isDestroyed || token!=accountToken) {
            // 这一发被后来的一次作废了：标志与加载框都不能留着（否则它会一直转）。
            if(!isDestroyed && manualRefresh) {
                finishProfileRefresh()
                hideCenterLoading()
            }
            return@launch
        }
        accountLoading=false
        accountLoadingColumns=emptySet()
        val failure=result.error
        android.util.Log.i(
            "HGTV",
            "Account fetch done columns=$columns error=${failure?.message.orEmpty()} " +
                "favorites=${accountData?.favorites?.size ?: -1} profile=${accountData?.profile!=null}",
        )
        if(failure==null) {
            accountError=null
            accountRetry=0
            if(result.changed.isNotEmpty()) refreshAccountViews(result.changed)
            else refreshCurrentAccountTabIfEmpty()
            if(result.localWrites>0) refreshLocalHistoryViews()
            // 手动刷新给一句结果。`changed` 为空时界面上不会有任何变化（那本来就是对的），
            // 没有这句话用户只会读到「按了刷新没反应」。
            if(manualRefresh) {
                // 成功不再报一句「已更新 / 已是最新」：那是纯状态确认，页面自己会变，
                // 多一句提示只是噪音（早先那一行还会把内容挤下去）。
                finishProfileRefresh()
                hideCenterLoading()
            }
            return@launch
        }
        android.util.Log.w("HGTV","accountData failed: ${failure.message}",failure)
        DebugLog.record("账号","资料读取失败 · ${failure.message.orEmpty().take(140)}")
        accountError=failure
        accountFailureText(failure)?.let { tvTools.toast(it) }
        // 手动刷新失败也要说一句：否则同样是「按了没反应」。
        if(manualRefresh) {
            finishProfileRefresh()
            hideCenterLoading()
            showHint(accountFailureText(failure) ?: "账号数据刷新失败，请稍后再试")
        }
        if(accountData==null && (tab==TAB_PROFILE || collection!=null)) {
            showCatalog()
        } else {
            refreshCurrentAccountTabIfEmpty()
        }
        if(requestedColumnEmpty && accountRetry<ACCOUNT_RETRIES) {
            accountRetry++
            main.postDelayed({
                if(!isDestroyed && foreground) loadAccount(columns=columns)
            },ACCOUNT_RETRY_MS)
        }
    }
}

/** 空列表没有 changed 差异，也必须重画一次，才能把等待态换成空态或错误态。 */
private fun TvMainActivity.refreshCurrentAccountTabIfEmpty() {
    if(tab==TAB_PROFILE && screen=="catalog" && accountItems(accountTab).isEmpty()) {
        refreshAccountListIfVisible()
    }
}

/**
 * 只加载「我的」页当前选中的栏目。
 *
 * 历史走分页接口，其余三栏只请求各自那一列；缓存由 `applyAccount()` 预先铺好，
 * 因此切栏时先看到已有内容，没有缓存才显示加载态。
 */
fun TvMainActivity.loadCurrentAccountTab(force: Boolean=false) {
    when(accountTab) {
        "history" -> {
            loadAccountHistory(reset=true,force=force)
            if(force || accountData?.profile==null) {
                loadAccount(force=force,columns=setOf("资料"))
            }
        }
        "favorites" -> loadAccount(force=force,columns=setOf("收藏"))
        "likes" -> loadAccount(force=force,columns=setOf("点赞"))
        "reserves" -> loadAccount(force=force,columns=setOf("预约"))
    }
}

fun TvMainActivity.loadMoreAccountHistory() {
    loadAccountHistory(reset=false)
}

/**
 * 账号历史首屏 / 继续加载。每次只取 [ACCOUNT_HISTORY_PAGE_SIZE] 条，并按服务端
 * 原始条数推进 offset；本地墓碑过滤不会让下一页重复。
 */
fun TvMainActivity.loadAccountHistory(reset: Boolean,force: Boolean=false) {
    if(accountHistoryLoading && !force) return
    if(!reset && !accountHistoryHasMore) return
    val offset=if(reset) 0 else accountHistoryOffset
    val manualRefresh=force && accountRefreshing
    if(reset) {
        accountHistoryOffset=0
        accountHistoryVisibleCount=maxOf(ACCOUNT_HISTORY_PAGE_SIZE,library.history().size)
        accountHistoryEmptyPages=0
    }
    accountHistoryLoading=true
    accountError=null
    if(accountData?.history.isNullOrEmpty()) {
        main.post {
            if(!isDestroyed && accountHistoryPaging() && accountData?.history.isNullOrEmpty()) {
                refreshAccountListIfVisible()
            }
        }
    }
    val token=++accountHistoryToken
    android.util.Log.i(
        "HGTV",
        "Account history fetch offset=$offset limit=$ACCOUNT_HISTORY_PAGE_SIZE reset=$reset",
    )
    appScope.launch {
        val result=accountController.refreshHistory(offset,ACCOUNT_HISTORY_PAGE_SIZE)
        if(isDestroyed || token!=accountHistoryToken) return@launch
        accountHistoryLoading=false
        val failure=result.error
        if(failure!=null) {
            accountError=failure
            DebugLog.record("账号","历史读取失败 · ${failure.message.orEmpty().take(140)}")
            if(tab==TAB_PROFILE && accountData?.history.isNullOrEmpty()) {
                refreshAccountListIfVisible()
            }
            if(manualRefresh) {
                finishProfileRefresh()
                hideCenterLoading()
                showHint(accountFailureText(failure) ?: "历史记录加载失败，请稍后再试")
            }
            return@launch
        }
        val page=result.page
        if(page!=null) {
            accountHistoryOffset=page.nextOffset
            accountHistoryHasMore=page.hasMore
            if(page.rows.isEmpty() && page.hasMore) {
                if(accountHistoryEmptyPages<ACCOUNT_HISTORY_EMPTY_PAGE_LIMIT) {
                    accountHistoryEmptyPages++
                    accountHistoryLoading=false
                    loadAccountHistory(reset=false,force=manualRefresh)
                    return@launch
                }
                accountHistoryHasMore=false
                accountError=IllegalStateException("历史记录分页未返回可显示内容")
                refreshAccountListIfVisible()
                if(manualRefresh) {
                    finishProfileRefresh()
                    hideCenterLoading()
                    showHint("历史记录分页异常，请稍后再试")
                }
                return@launch
            }
            accountHistoryEmptyPages=0
            accountHistoryVisibleCount=if(reset) {
                maxOf(ACCOUNT_HISTORY_PAGE_SIZE,library.history().size+page.rows.size)
            } else {
                accountHistoryVisibleCount+page.rows.size
            }
            accountError=null
            accountRetry=0
            refreshAccountViews(setOf("历史"))
        }
        if(manualRefresh) {
            finishProfileRefresh()
            hideCenterLoading()
        }
    }
}
/** 手动刷新收尾：清掉「刷新中」标志（等待框由调用方在 [loadAccount] 里收）。 */
private fun TvMainActivity.finishProfileRefresh() {
    accountRefreshing=false
}

/**
 * 账号数据落地后，按「变了哪几栏」决定重画哪几处。
 *
 * 三处界面读的都是账号同步后的本机缓存，因此谁都不能少：
 * 「我的」页那四个栏目、首页的两栏、以及本机记录集合页（收藏 / 历史）。
 * 但**没变的那几处不必重画**——这正是原先每次刷新都整页重建的代价来源。
 *
 * 收藏与历史栏由共享控制器整表镜像到本机，所以账号侧变了时只需重建正在显示的那一页，
 * 别的页面下次进来自己会读到新数据。
 */
fun TvMainActivity.refreshAccountViews(changed: Set<String>) {
    if(tab==TAB_PROFILE) {
        // 资料变了：头像与昵称长在页头那一行里，只有整页重建才换得掉。
        if("资料" in changed) { showCatalog(); return }
        // 只有**当前这一栏**变了才重画（切到别的栏目时它自己会读新数据）。
        val column=when(accountTab) {
            "favorites" -> "收藏"
            "history" -> "历史"
            "likes" -> "点赞"
            "reserves" -> "预约"
            else -> ""
        }
        if(column in changed) refreshAccountListIfVisible()
        return
    }
    if(screen!="catalog") return
    // 本机记录集合页：收藏 / 历史两栏现在是账号同步后的缓存。
    if(collection=="favorites" && "收藏" in changed) { showCatalog(); return }
    if(collection=="history" && "历史" in changed) { showCatalog(); return }
    // 首页：两个剧架读账号同步后的缓存；只有这两栏变了才重建（榜单剧架与账号无关）。
    if(tab==TAB_HOME && screen=="catalog" && ("收藏" in changed || "历史" in changed)) refreshLibraryCards("")
}
/**
 * 账号参数变化后同步到仓库。
 *
 * 首页「我的收藏」「历史记录」两栏读的是账号同步后的本机缓存，
 * 因此这里先把账号缓存镜像进仓库，首页重建时自然带上远端条目。
 */
fun TvMainActivity.applyAccount() {
    // 设备身份 / 账号凭据 / 注册 Cookie / 心跳档案 / 搜索口令：装配只有一份
    // （`:shared` 的 `IdentityBinder`），手机端用的是同一个函数。
    // 随后异步补一次注册（匿名）：拿到之后搜索与播放都走这同一份身份。
    com.hgtv.star.shared.data.IdentityBinder.bind(library,repository)
    ensureDeviceRegistered()
    // 旧版本注册下来的身份：后台换一套新的（见 [refreshRegistrationIdentity]）。
    // 与上面那句不冲突——没有身份时它什么都不做，只有「有身份但比当前版本旧」才换。
    refreshRegistrationIdentity()
    applyPlaybackCapability()
    // 换凭据之后先用**缓存**顶上（同账号时它还在，指纹对不上时 [Library.accountCache] 会丢掉它），
    // 界面因此立刻有内容可铺，不必先空一屏再等请求回来。
    // 没有凭据时缓存按主设备身份隔离；页头是否显示登录态仍由 credentials 判断。
    // 缓存里那一份先整表镜像到本机：登录后首页与「我的 → 历史」都读同一份远端缓存。
    accountData=accountController.bindCredentials()
    accountError=null
    accountHistoryFilter=null
    accountHistoryVisibleCount=maxOf(ACCOUNT_HISTORY_PAGE_SIZE,library.history().size)
    accountHistoryOffset=0
    accountHistoryHasMore=true
    accountHistoryEmptyPages=0
    // 换凭据就把在途的那一次作废，并把「正在读」放回去：否则新凭据要等旧请求的超时才发。
    accountToken++
    accountHistoryToken++
    accountLoading=false
    accountLoadingColumns=emptySet()
    accountHistoryLoading=false
    // 换凭据就等于「换了一个人」：上一次账号那几次重试计数作废（见 [loadAccount] 的失败分支）。
    accountRetry=0
}
/**
 * 按这台设备的解码能力配好播放：首次启动的默认清晰度 + 「有没有 HEVC 硬解」。
 *
 * ## 为什么 `device_level` 不在这里给了
 *
 * `device_level` 固定为 4，不再因空响应动态升档（见 `ContentRepository.deviceLevel`）。
 * 它与本机解码能力**无关**——
 * 两档下发的都是 HEVC，能不能播由硬解决定，而不是由档位决定。
 * 1/2/3 档的低清晰度是 `bytevc2`，Android 没有解码器，因此不参与播放请求。
 *
 * ## 为什么默认清晰度仍要按能力给
 *
 * 只在**用户还没自己选过**时给（`Library.qualityChosenByUser`）：没有 HEVC 硬解的盒子
 * 默认 1080 只会让每次起播都先失败一次再降档。
 *
 * ## 探测是 Binder 调用
 *
 * `MediaCodecList` 在电视盒子上能卡 1–3 秒，因此放在 io 线程，结果由
 * [PlaybackCapability] 落盘——之后每次启动都是纯内存读。
 */
fun TvMainActivity.applyPlaybackCapability() {
    val chosen=library.qualityChosenByUser()
    io.submit {
        val quality=if(chosen) 0 else PlaybackCapability.defaultQuality(this)
        val hevc=PlaybackCapability.canDecodeHevc(this)
        PlaybackCapability.warmHardwareHevc(this)
        val hardwareHevc=PlaybackCapability.cachedHardwareHevc() ?: true
        main.post {
            if(isDestroyed) return@post
            // 有没有 HEVC 硬解：只用于提示（起播 toast / 错误页按钮 / 面板读数），
            // **不自动改解码方式**（见 `playerDecoderMode`）。
            hardwareHevcAvailable=hardwareHevc
            // 用户选过就不动他的设置；没选过才按能力给一个默认值（写进 `quality` 键，
            // 之后 `qualityChosenByUser` 就是 true，不会再被覆盖）。
            if(quality>0 && !library.qualityChosenByUser()) library.maxQuality=quality
            playbackHevcSupported=hevc
        }
    }
}
/**
 * 确保本机有一台**平台注册过的**设备；没有就注册一台并落盘。
 *
 * 注册是匿名的（不需要账号 / 密码 / 短信），一次请求即可。它在后台跑，
 * 不阻塞首屏；成功之后把身份交给仓库，后续搜索与播放都走 App 接口那条快路径。
 * 失败不弹窗——搜索会报「接口暂无响应」、播放会报「暂不可用」，下次启动再试。
 */
fun TvMainActivity.ensureDeviceRegistered() {
    // 什么时候需要注册：本机还没有身份，或者上一次「换新」没成功（`registration-pending`）。
    //
    // 换新的方式是「**先注册新身份、成功才替换**」（见 [registerDeviceOnce]）：失败时旧档案、
    // 旧身份原样留着，用户仍然能看片；下一次启动 / 下一次取播放地址前再试。
    if(deviceRegistering) return
    val pending=library.registrationPending()
    val registered=library.deviceRegistered()
    val needsRegistration=pending || !registered
    if(!needsRegistration) {
        com.hgtv.star.shared.data.DeviceRegistration.ensureBound(library,repository)
        return
    }
    deviceRegistering=true
    // 用 io 池直接跑，**不走 [work]**：那个入口会 cancelWork()，把在途的页面请求一起掐掉。
    io.submit {
        val ready=registerDeviceOnce(force=pending)
        main.post {
            deviceRegistering=false
            if(!ready || isDestroyed) return@post
            if(startupInitializing) return@post
            // 注册成功后把身份重新交给仓库，并把当前这一页按新身份重取一次——
            // 首屏很可能已经用「没注册」的身份请求过了（App 搜索会空响应）。
            repository.deviceIdentity=library.deviceIdentity()
            if(!foreground || screen!="catalog") return@post
            when {
                // 首页：八个榜单剧架按新身份重取。
                tab==TAB_HOME -> showCatalog(load=true)
                // 搜索页：用户可能正停在「重试」上等这台设备初始化，注册一落地就替他重搜。
                tab==TAB_SEARCH && query.isNotBlank() -> showCatalog(load=true)
                else -> Unit
            }
        }
    }
}
/**
 * 注册一次并落盘（**并发调用只发一次请求**），成功返回 true。
 *
 * 内容接口（App 搜索 / 播放 / 弹幕）认的是**平台注册过的** `device_id` + `install_id`，
 * 本机不再自己编随机身份，因此「用之前先确保注册过」只有这一处实现：
 * 启动时异步调一次（[ensureDeviceRegistered]），播放取地址之前再同步兜一次
 * （见 [playEpisode] 的取地址那一段与 [maybePrefetch]）。
 */
fun TvMainActivity.registerDeviceOnce(force: Boolean = false, regenerate: Boolean = false): Boolean {
    val traceStarted=StartupTrace.now()
    StartupTrace.mark("device.register.start", "force=$force regenerate=$regenerate")
    synchronized(deviceRegisterLock) {
        // 注册的全部细节（先注册成功才替换、候选档案转正、Cookie 落盘、失败记待重试）
        // 在 `:shared` 的 [com.hgtv.star.shared.data.DeviceRegistration] 里，手机端用的是同一份。
        val outcome=com.hgtv.star.shared.data.DeviceRegistration.registerOnce(
            library=library,
            repository=repository,
            force=force,
            regenerate=regenerate,
            // 现场前缀（机型 / 页面）由这一端拼：注册失败时它是唯一的定位线索。
            detail=devicePrefix(this),
        )
        StartupTrace.done("device.register",traceStarted, "outcome=$outcome")
        if(outcome==com.hgtv.star.shared.data.DeviceRegistration.Outcome.FAILED) {
            // 失败也要留在面板上：用户拍的那张图上原本只有「设备正在初始化」，
            // 真实原因（HTTP 码 / 解析失败 / 被拒）完全看不到。
            noteDebugProblem("设备注册失败 · "+Diagnostics.chainOf(repository.lastRegisterError))
            return false
        }
        if(outcome==com.hgtv.star.shared.data.DeviceRegistration.Outcome.ALREADY) return true
        // 面板上的「设备」是 5 秒缓存：刚注册成功就作废，免得用户以为还没注册上。
        DebugEnv.invalidate()
        return true
    }
}

/**
 * 升级迁移：把**旧版本注册下来的**设备身份在后台换一套新的。
 *
 * 判据是 [Library.registrationNeedsRefresh]（盘上那份注册结果没有当前版本的档案指纹）。
 * 1.2.3 换了注册线路与上报参数，旧身份在内容接口上会被当成陌生设备：搜索回 `200 + 0 字节`、
 * 播放回 `101000`；而「设置 → 清空缓存」能修好，正是因为它把这些数字删了重新注册。
 *
 * 启动时发现身份比当前版本旧，就悄悄换一套；换新成功才替换，
 * 失败（网络 / 服务端拒绝）就继续用旧的那一对——不能因为一次注册失败让用户连首页都看不了。
 */
fun TvMainActivity.refreshRegistrationIdentity() {
    if(deviceRegistering) return
    if(!library.registrationNeedsRefresh()) return
    deviceRegistering=true
    io.submit {
        // 与 [registerDeviceOnce] 同一条：有待换的候选档案时按候选注册，成功才转正。
        val pendingProfile=library.hasPendingProfile()
        val fresh=runCatching {
            repository.registerDevice(library.registrationProfile(),withIdentity=!pendingProfile)
        }.getOrNull()
        main.post {
            deviceRegistering=false
            if(isDestroyed || fresh==null) return@post
            if(pendingProfile) library.commitPendingProfile()
            library.clearRegistrationPending()
            // 与普通注册同一条收尾：落身份 / Cookie。
            com.hgtv.star.shared.data.DeviceRegistration.commit(library,repository,fresh)
            DebugEnv.invalidate()
            DebugLog.record("设备","旧版本注册身份已换新")
            if(startupInitializing) return@post
            // 当前这一页很可能是按旧身份取的：换新之后重取一次。
            if(!foreground || screen!="catalog") return@post
            when {
                tab==TAB_HOME -> showCatalog(load=true)
                tab==TAB_SEARCH && query.isNotBlank() -> showCatalog(load=true)
                else -> Unit
            }
        }
    }
}

/**
 * 手机号登录的「注册设备」这一步：拿一份设备档案，匿名注册一台设备并把结果交给调用方。
 *
 * 与 [registerDeviceOnce] 的分工：那个只回「成没成」（它内部自己取档案、自己落盘），
 * 手机号登录需要**注册下发的 `device_id` / `install_id` 与 Cookie**（Passport 请求要带上它们），
 * 因此这里单独走一遍并如实返回 [com.hgtv.star.core.DeviceRegister.Registered]。
 *
 * 成功后同时落盘（后续搜索 / 播放复用这台设备），失败返回 null。
 */
fun TvMainActivity.registerForPhoneLogin(profile: Map<String,String>): com.hgtv.star.core.DeviceRegister.Registered? {
    val registered=com.hgtv.star.shared.data.DeviceRegistration.registerLoginDevice(
        library,
        repository,
        profile,
    ) ?: run {
        val problem=repository.lastRegisterError
        Diagnostics.fail("device_register_login","登录时设备注册失败","${devicePrefix(this)} cause=${Diagnostics.chainOf(problem)}",problem)
        return null
    }
    DebugEnv.invalidate()
    return registered
}

/**
 * 手机号登录成功后：把凭据落盘、交给仓库、刷新「我的」页。
 *
 * 走的是与导入 `cookieStore.xml` **同一条**落地路径（[importAccount] 的校验那一半也照做）：
 * 先拉一次账号资料确认这份会话真能用——平台对无效会话不报错、只把请求降级成匿名设备，
 * 不挡住的话「登录成功」会是假的（界面上只是「一条记录都没有」）。
 */
fun TvMainActivity.savePhoneLogin(credentials: AccountCredentials,anchor: View?) {
    // 同上：这一步要打一次账号资料确认会话有效，等待期间显示加载框。
    showCenterLoading("正在验证登录状态…")
    io.submit {
        val result=runCatching {
            val prepared=com.hgtv.star.shared.data.DeviceRegistration.prepareLogin(
                library,repository,credentials,
            )
            repository.credentials=prepared
            val profile=repository.accountProfile()
            require(profile!=null) { "平台没有返回账号资料，请稍后重试" }
            require(!profile.anonymous) { "这份登录态无效：平台把它当成了匿名设备" }
            prepared
        }
        main.post {
            if(isDestroyed) return@post
            result.onSuccess { valid ->
                library.saveAccount(valid)
                // 登录成功后先等镜像队列真正落盘，再收起等待框。否则用户看到“已登录”
                // 就立刻卸载时，250ms 的异步镜像写入可能还没到盘，重装后只剩设备身份。
                io.submit {
                    library.flushBlocking()
                    main.post {
                        if(isDestroyed) return@post
                        applyAccount()
                        // 登录成功立刻打一次 boot 心跳 + account/info：新会话要被服务端确认一次，
                        // 导入来的 cookie 更是只有 cookie、要靠这次响应把 `X-Tt-Token` 换回来。
                        com.hgtv.star.shared.data.SessionBootstrap.onAppStart(force=true)
                        hideCenterLoading()
                        showHint("已登录")
                        accountTab="history"
                        loadAccountHistory(reset=true,force=true)
                        showCatalog()
                    }
                }
            }.onFailure { failure ->
                repository.credentials=library.account().takeIf { it.configured }
                DebugLog.record("账号","登录失败 · ${failure.message.orEmpty().take(140)}")
                hideCenterLoading()
                showHint("登录失败")
                tvTools.info("登录失败",failure.message.orEmpty().take(200).ifBlank { "请稍后重试" },anchor)
            }
        }
    }
}

/**
 * 手机号登录的两条路：手机扫码（推荐）或电视上数字键盘输入。
 *
 * 两条路走的是**同一份** [com.hgtv.star.core.PhoneLogin] 协议实现与同一套落盘逻辑
 * （见 [savePhoneLogin]），区别只是「在哪儿敲那串手机号」。
 */
fun TvMainActivity.phoneLoginChoice(anchor: View?) {
    // 直接进表单：手机号与验证码用系统键盘输入，版面与手机上的登录页一致。
    // 手机扫码那条路留给「上行短信」那一步（那一步本来就必须用手机）。
    tvTools.phoneLoginForm(
        anchor,
        LinkedHashMap(library.registrationProfile()).apply { putAll(library.deviceIdentity()) },
        library.registrationCookies(),
        { profile -> registerForPhoneLogin(profile) },
        { credentials -> savePhoneLogin(credentials,anchor) },
    )
}
