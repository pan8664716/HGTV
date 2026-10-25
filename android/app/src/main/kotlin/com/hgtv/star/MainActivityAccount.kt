// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.view.*
import android.widget.*
import com.google.android.exoplayer2.*
import com.hgtv.star.core.*
import com.hgtv.star.list.showCatalog
import com.hgtv.star.data.PlaybackCapability

// 本文件是从 MainActivity.kt 拆出来的「账号参数导入（本机文件 / 手机扫码）与设备注册」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

/**
 * 请求「会话镜像」要用的外部存储权限（Android 6 及以上）。
 *
 * 镜像（见 [com.hgtv.star.data.SessionMirror]）把 `device_id` / `iid` 与 `sessionid` 那一串
 * 放到 `/sdcard/.hgtv/`（自建公共目录，卸载不删），卸载重装后靠它把设备身份与会话捞回来，
 * 省掉重新注册、重新登录、再发一次上行短信这一整轮。
 *
 * 写 `/sdcard` 需要 `WRITE_EXTERNAL_STORAGE`：4.4–5.1 是安装期权限（装上就有），
 * **6 及以上要运行时授权**。这正是 `targetSdk 29` + `requestLegacyExternalStorage` 的用途——
 * 传统存储模式下这一个权限就等于整个公共存储的读写权（见 manifest）。
 * 不给，这一层就是空的，用户每次重装都得重新验证一遍本人。
 *
 * 一个进程只问一次（[mirrorStorageAsked]）：用户拒绝之后不再纠缠，镜像退化为不可用，
 * 其他功能完全不受影响（`SessionMirror` 写不进去只留一行日志）。
 */
private var mirrorStorageAsked=false
internal fun MainActivity.requestMirrorStorage() {
    if(android.os.Build.VERSION.SDK_INT<23 || mirrorStorageAsked) return
    mirrorStorageAsked=true
    val granted=android.content.pm.PackageManager.PERMISSION_GRANTED==
        androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    if(granted) return
    runCatching {
        androidx.core.app.ActivityCompat.requestPermissions(
            this,arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),REQUEST_MIRROR_STORAGE)
    }
}

/**
 * 外部存储授权回来之后的收尾。
 *
 * 拿到权限的**那一刻**才是 `/sdcard/.hgtv` 第一次真读得到的时候，而启动时那次恢复已经跑过了
 * （那时读不到，等于什么都没补），所以这里必须重试一次：
 * 补到内容就立刻重新应用凭据并重画页面——**新装的应用因此可以直接回到登录状态**，
 * 不必重新注册设备、重新收短信验证。拒绝授权就什么都不做（镜像退化为 Android/data 那一份）。
 */
internal fun MainActivity.onMirrorStorageResult(granted: Boolean) {
    if(!granted) return
    // 读盘交给 loader 线程：权限回调在主线程，这里不能碰 `/sdcard`（见 Library.retryMirrorRestore）。
    library.retryMirrorRestore { restored ->
        if(!restored || isDestroyed) return@retryMirrorRestore
        // **不弹窗**：这一步是启动流程的一部分，用户没做任何操作，不需要被告知。
        // 「恢复了什么」只留在日志里（`logcat -s HGTV`），界面上直接就是登录状态。
        applySessionToken(); applyAccount(); showCatalog(load=true)
    }
}

internal fun MainActivity.pickAccountFile() {
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
internal fun MainActivity.consumePendingAccountFile() {
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
internal fun MainActivity.importAccount(text: String,anchor: View?) {
    showHint("正在验证账号参数…")
    io.submit {
        val result=runCatching { parseCredentials(text) }
        main.post {
            if(isDestroyed) return@post
            result.onSuccess { credentials ->
                library.saveAccount(credentials)
                applyAccount()
                // 导入的会话只有 cookie、没有 `X-Tt-Token`：立刻打一次 boot 心跳，
                // 让服务端在响应头里把 token 发回来（拦截器收到即落盘）。
                com.hgtv.star.data.SessionBootstrap.onAppStart(force=true)
                showHint("账号参数已导入")
                showCatalog()
                loadAccount(force=true)
            }.onFailure { failure ->
                DebugLog.record("账号","导入失败 · ${failure.message.orEmpty().take(140)}")
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
internal fun MainActivity.parseCredentials(text: String): AccountCredentials {
    val trimmed=text.trim()
    // `device_profile.json`（dump_device.py 的产物）单独走一条路：它没有 Cookie，
    // 只把设备档案存下来，签名（x-medusa）要用。
    if(trimmed.startsWith("{") && trimmed.contains("device_id")) {
        val device=AccountCredentialsParser.parseDeviceProfile(trimmed)
        require(device["device_id"].orEmpty().isNotBlank()) { "这份档案里没有 device_id，确认传的是 device_profile.json" }
        library.saveAccountDevice(device)
        val current=library.account()
        require(current.configured) { "设备档案已保存。但还没有账号参数，请再传一份 cookieStore.xml。" }
        repository.credentials=current
        return current
    }
    val xml=trimmed.contains("<map")
    val credentials=if(xml) {
        AccountCredentialsParser.parseCredentials(trimmed,library.accountDevice())
    } else {
        // 「输入 sessionid」那一条路送来的可能是纯值、`sessionid=…`、整段 Cookie，
        // 也可能是 `cookieStore.xml` 里那一整行（或它的十六进制值本身）——
        // 统一由 [AccountCredentialsParser.sessionIdOf] 归一。
        val id=AccountCredentialsParser.sessionIdOf(trimmed)
        require(AccountCredentialsParser.looksLikeSessionId(id)) {
            if(id.isEmpty()) "没有解析到 sessionid" else "这不是一个 sessionid（要 32 位十六进制）"
        }
        AccountCredentialsParser.parseHeader("sessionid=$id")
    }
    require(credentials.configured) { "没有解析到 sessionid，请确认上传的是红果 App 的 cookieStore.xml" }
    repository.credentials=credentials
    val profile=repository.accountProfile()
    require(profile!=null) { "平台没有返回账号资料，参数可能已过期（cookieStore.xml 要取最新的那一份）" }
    // 平台对无效 / 过期的 sessionid **不报错**，只把请求降级成匿名设备并返回一个临时账号。
    // 不挡住的话，一份坏凭据会「导入成功」，而界面上只是一条记录都没有——
    // 这正是「导入后没有任何记录」最常见的原因。
    require(!profile.anonymous) {
        "这份 sessionid 无效或已过期：平台把它当成了匿名设备（返回的账号是「${profile.nickname}」）。\n" +
        "请重新从红果 App 取一份最新的 cookieStore.xml。"
    }
    return credentials
}
/**
 * 拉取账号数据（资料 + 四条列表），成功后重建「我的」页与首页两栏。
 *
 * **不走 [work]**：`work` 的回调挂在「当前页次」上，任何一次 [invalidatePage]
 * （切页、重建、回前台时的 showCatalog）都会把在途的回调丢掉；而 [accountLoading]
 * 是在回调里复位的，于是它一旦被丢掉就永远停在 true——之后每次进这一页都直接早退，
 * 界面上就是「一直显示正在读取，很久没有结果」。这里自己管一个序号：
 * 只有最后一次请求的回调会落地，且无论成功失败都会把 [accountLoading] 放回去。
 */
internal fun MainActivity.loadAccount(force: Boolean=false) {
    if(!repository.signedIn) return
    if(accountLoading && !force) return
    val cache=library.accountCache()
    val now=System.currentTimeMillis()
    // 缓存**还新鲜**就不发请求：账号侧这五条数据不是每秒在变，进一次「我的」页就打五个请求
    // 换来的只是「页面转一圈」。想立刻要最新的就用页头那枚「刷新」（它走 force）。
    if(!force && cache!=null && now-cache.savedAt in 0..ACCOUNT_FRESH_MS) {
        val first=accountData==null
        accountData=cache.data; accountError=null
        // 缓存是**这一页刚打开时**拿到的（原先这一页要等一次请求才敢铺内容）：现在要补一次重建。
        // 必须 post 到下一轮：调用方此刻多半正在 [showCatalog] 里铺这一页，
        // 在渲染过程中再调一次 showCatalog 会边建边拆。
        if(first && cache.data.usable) main.post { if(!isDestroyed && foreground) refreshAccountViews(AccountSnapshot.KEYS) }
        return
    }
    // 真正要发请求了才留一行日志：账号数据这一路在电视上没法调试，
    // 「启动时到底有没有去拉」只有这一行说得清。
    android.util.Log.i("HGTV","Account fetch force=$force cache=${cache!=null} tab=$tab screen=$screen")
    accountLoading=true
    val token=++accountToken
    io.submit {
        val result=runCatching { repository.accountData() }
        main.post {
            if(isDestroyed || token!=accountToken) return@post
            accountLoading=false
            result.onSuccess { fresh ->
                // 某一栏这次没回来（[AccountData.errors] 记着原因）就保留上一份，
                // 不要用空列表把它盖掉——那在界面上读作「我的收藏被清空了」。
                // 本机删过的观看记录不再进缓存：平台对短剧进度列表的软删不一定生效，
                // 不在这里滤一次，下次启动读缓存时那几条又回来了（见 [Library.historyDeleted]）。
                val data=AccountSnapshot.merge(accountData, fresh).let { merged ->
                    val kept=merged.history.filterNot { library.historyDeleted(it.series.id) }
                    if(kept.size==merged.history.size) merged else merged.copy(history=kept)
                }
                // **对账**：账号这份与本机进度逐条比「谁更超前」，同一部剧两边只留一份
                // （见 [reconcileHistory]）。不这么做的话，机器 2 看到的第 400 集只会停在
                // 「我的 → 历史」里，首页历史栏与详情页续播读的本机进度还是第 200 集——
                // 用户看到的就是「两个历史各说各的」。
                val merge=reconcileHistory(data)
                // 变了哪几栏就重画哪几处；**一栏都没变时一个视图都不动**。
                // 这里是最容易写出「每次刷新都闪一下」的地方：账号数据落地后无条件整页重建，
                // 会把栅格、封面、滚动位置全部重来一遍，而内容其实一模一样。
                val changed=AccountSnapshot.changedKeys(accountData, merge.data)
                accountData=merge.data; accountError=null
                accountRetry=0
                library.cacheAccount(merge.data)
                if(changed.isNotEmpty()) refreshAccountViews(changed)
                // 本机进度被账号那一份改写过：首页历史栏 / 历史集合页读的是本机那份，也要重画。
                if(merge.localWrites>0) refreshLocalHistoryViews()
                // 反过来，本机更超前的几条要推回云端：否则第三台设备重登之后又拿回旧的，
                // 下一次同步再把这台机器改回去，永远在那两个集数之间来回。
                // 这一栏这次没拉回来时不上传：那时手上这份账号数据是旧的，比不出谁更超前。
                if("历史" !in fresh.errors) pushHistoryAhead(merge.uploads)
            }.onFailure { failure ->
                android.util.Log.w("HGTV","accountData failed: ${failure.message}",failure)
                DebugLog.record("账号","资料读取失败 · ${failure.message.orEmpty().take(140)}")
                accountError=failure
                // 需要登录态的接口失败时给一条短提示（见 [accountFailureText]）：
                // 这一页本身会把原因写在内容区，但用户可能正在别的页面等着结果，
                // 那种「按了没反应」的体感只能靠这条即时反馈补上。
                accountFailureText(failure)?.let { tvTools.toast(it) }
                // 失败时只在**手上什么都没有**的情况下重画（内容区要换成失败说明与「重试」）；
                // 已经有缓存铺在屏幕上时不动它——把用户正在看的收藏 / 历史换成一页错误说明，
                // 比一次后台刷新失败严重得多。
                if(accountData==null && (tab==TAB_PROFILE || collection!=null)) showCatalog()
                // **首页开局那一次失败要自己再试**：刚恢复的登录态 + 首屏那一批请求挤在一起时
                // 第一次账号拉取失败并不罕见，而用户手上什么都没有，界面上就是「历史 / 收藏两栏是空的」。
                // 原先这种情况必须先去点一次「我的」才能把数据逼出来——这里自动补两次，
                // 用户不必知道有这回事（[accountRetry] 在成功与换凭据时归零）。
                if(accountData==null && accountRetry<ACCOUNT_RETRIES) {
                    accountRetry++
                    main.postDelayed({ if(!isDestroyed && foreground) loadAccount() },ACCOUNT_RETRY_MS)
                }
            }
        }
    }
}
/**
 * 账号数据落地后，按「变了哪几栏」决定重画哪几处。
 *
 * 三处界面读的都是**本机 + 账号**合并出来的列表，因此谁都不能少：
 * 「我的」页那四个栏目、首页的两栏、以及本机记录集合页（收藏 / 历史）。
 * 但**没变的那几处不必重画**——这正是原先每次刷新都整页重建的代价来源。
 *
 * 收藏与历史栏的本机那半份也在列表里（合并规则见 [localItems]），所以只有账号侧变了时
 * 也只需重建正在显示的那一页，别的页面下次进来自己会读到新数据。
 */
internal fun MainActivity.refreshAccountViews(changed: Set<String>) {
    if(tab==TAB_PROFILE) {
        // 资料变了：头像与昵称长在页头那一行里，只有整页重建才换得掉。
        if("资料" in changed) { showCatalog(); return }
        // 只有**当前这一栏**变了才重画（切到别的栏目时它自己会读新数据）。
        if(accountTab in changed) refreshAccountListIfVisible()
        return
    }
    if(screen!="catalog") return
    // 本机记录集合页：收藏 / 历史两栏的内容里有账号那一半。
    if(collection=="favorites" && "收藏" in changed) { showCatalog(); return }
    if(collection=="history" && "历史" in changed) { showCatalog(); return }
    // 首页：两个剧架里有账号那一半；只有这两栏变了才重建（榜单剧架与账号无关）。
    if(tab==TAB_HOME && screen=="catalog" && ("收藏" in changed || "历史" in changed)) refreshLibraryCards("")
}
/**
 * 账号参数变化后同步到仓库。
 *
 * 首页「我的收藏」「历史记录」两栏是**本机 + 账号**合并的结果（见 [historyRows] / [favoriteRows]），
 * 因此这里只把凭据交给仓库，首页重建时自然带上账号里的条目。
 */
internal fun MainActivity.applyAccount() {
    // 设备身份：只有平台注册下发的那一对（`deviceIdentity()` 没注册过时是空表，不再自己编随机值）。
    // 随后异步补一次注册（匿名）：拿到之后搜索与播放都走这同一份身份。
    repository.registerProfile=library.deviceProfile()
    repository.deviceIdentity=library.deviceIdentity()
    repository.credentials=library.account().takeIf { it.configured }
    // 注册下发的 Cookie（`install_id` / `ttreq` / `store-region`）落过盘，这里灌回仓库：
    // 少了它，新进程的第一轮请求是「裸设备」，平台按陌生设备处理。
    repository.restoreDeviceCookies(library.registrationCookies())
    // 心跳的设备头要用**这台机器**的档案（机型 / 系统 / cdid），与业务请求同源。
    com.hgtv.star.data.SessionBootstrap.updateDevice(library.accountDevice())
    ensureDeviceRegistered()
    // 旧版本注册下来的身份：后台换一套新的（见 [refreshRegistrationIdentity]）。
    // 与上面那句不冲突——没有身份时它什么都不做，只有「有身份但比当前版本旧」才换。
    refreshRegistrationIdentity()
    applyPlaybackCapability()
    // 换凭据之后先用**缓存**顶上（同账号时它还在，指纹对不上时 [Library.accountCache] 会丢掉它），
    // 界面因此立刻有内容可铺，不必先空一屏再等请求回来。
    // 没有凭据时**必须把内存里那份资料清掉**：退出登录 / 会话失效之后 `accountData`
    // 还留着上一个账号的昵称与四个栏目，页头照着它铺昵称，界面上就是「已经退出了却还是
    // 登录状态」。清掉之后页头读到的就是「未登录」，内容区也回到登录卡。
    // 缓存里那一份也先对账一次：重启之后首页读的是本机进度、
    // 「我的 → 历史」读的是缓存，两边不一致的话用户一开电视就看到两个集数。
    accountData=if(repository.credentials==null) null else library.accountCache()?.data?.let { cached ->
        if(library.isLoaded) reconcileHistory(cached).data else cached
    }
    if(repository.credentials==null) library.clearAccountCache()
    accountError=null
    // 换凭据就把在途的那一次作废，并把「正在读」放回去：否则新凭据要等旧请求的超时才发。
    accountToken++
    accountLoading=false
    // 换凭据就等于「换了一个人」：上一次账号那几次重试计数作废（见 [loadAccount] 的失败分支）。
    accountRetry=0
}
/**
 * 按这台设备的解码能力配好播放：`device_level` 与「首次启动的默认清晰度」。
 *
 * ## 为什么 `device_level` 要按能力给
 *
 * 播放接口只下发两种编码（`bytevc1`=HEVC、`bytevc2`=字节自家的 BVC2），而平台按
 * `device_level` 挑「编码族」：报 3（官方值）时 360/480/540/720 全是 BVC2、只有 1080 是 HEVC；
 * 报 4 时五档全是 HEVC。BVC2 在 Android 上没有解码器，于是 3 档时低清晰度会被
 * `stream()` 全部过滤掉、只剩 1080P——用户把默认清晰度设成 480P 也照样放 1080P，
 * 而且因为每集的编码集合不同，会表现成「一集 480P、一集 1080P」来回跳。
 *
 * 因此：能解 HEVC 报 4，解不了就报 3（报 4 只会拿到一套同样解不了的 HEVC）。
 *
 * ## 为什么默认清晰度也要按能力给
 *
 * 只在**用户还没自己选过**时给（`Library.qualityChosenByUser`）：解不了 1080 的盒子
 * 默认 1080 只会让每次起播都先失败一次再自动降档。
 *
 * 探测本身是 Binder 调用（`MediaCodecList`，电视盒子上能卡 1–3 秒），因此放在 io 线程，
 * 且结果由 [PlaybackCapability] 落盘——之后每次启动都是纯内存读。
 */
internal fun MainActivity.applyPlaybackCapability() {
    val chosen=library.qualityChosenByUser()
    io.submit {
        val level=PlaybackCapability.deviceLevel(this)
        val quality=if(chosen) 0 else PlaybackCapability.defaultQuality(this)
        val hevc=PlaybackCapability.canDecodeHevc(this)
        main.post {
            if(isDestroyed) return@post
            // 仓库里那份档位是给播放模型请求用的：解不了 HEVC 时报 3，与官方 App 一致。
            repository.deviceLevel=level
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
internal fun MainActivity.ensureDeviceRegistered() {
    // 设备档案要从「老版本的随机档案」换成派生值时，注册结果也必须跟着换一套：
    // 平台按 `cdid` / `openudid` 认设备，档案换了而 `device_id` 没换，两边对不上号，
    // 搜索回空响应、播放回 101000/110001。
    //
    // 但换法是「**先注册新身份、成功才替换**」（见 [registerDeviceOnce]）：失败时旧档案、
    // 旧身份原样留着，用户仍然能看片；下一次启动 / 下一次取播放地址前再试。
    //
    // 顺序要紧：注册已经在途时**先返回**，不要把这个一次性标记吃掉，
    // 否则这一次重建会被静默丢弃，身份一直卡在「档案换了、device_id 没换」的状态。
    if(deviceRegistering) return
    val profileRebuilt=library.consumeProfileRebuilt()
    // 「上一次换新失败」也算没注册好：那时旧身份还留着，先补一次换新再谈别的。
    val pending=library.registrationPending()
    if(!profileRebuilt && !pending && library.deviceRegistered()) return
    deviceRegistering=true
    // 用 io 池直接跑，**不走 [work]**：那个入口会 cancelWork()，把在途的页面请求一起掐掉。
    io.submit {
        if(profileRebuilt) {
            DebugLog.record("设备","设备档案已重建 · 强制重新注册")
            Diagnostics.warn("device_identity","设备档案由旧版随机值重建为派生值，强制重新注册")
        }
        val registered=registerDeviceOnce(force=profileRebuilt || pending)
        main.post {
            deviceRegistering=false
            if(!registered || isDestroyed) return@post
            // 注册成功后把身份重新交给仓库，并把当前这一页按新身份重取一次——
            // 首屏很可能已经用「没注册」的身份请求过了（App 搜索会空响应）。
            repository.deviceIdentity=library.deviceIdentity()
            if(!foreground || screen!="catalog") return@post
            when {
                // 首页：四个剧架按新身份重取。
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
internal fun MainActivity.registerDeviceOnce(force: Boolean = false): Boolean {
    synchronized(deviceRegisterLock) {
        // **不先丢旧身份**：换新是「先注册、成功才替换」。
        //
        // 早先这里在注册之前就把 `device_id` / `iid` / Cookie 一起抹掉，注册一旦失败
        //（无网络、被风控、服务端抖动）本机就变成「未注册设备」——搜索回 `200 + 0 字节`、
        // 播放回 `110001`，用户看到的是「升级之后什么都放不了」，而旧身份本来是能用的。
        // 现在失败只是记一个待重试标记（见 [Library.markRegistrationPending]），
        // 旧身份继续用，下次启动 / 下次取播放地址前再换。
        if(!force && library.deviceRegistered()) return true
        // 设备注册失败是「搜索空响应 / 播放取不到地址 / 弹幕一条没有」的共同根因，
        // 而这一行以前是 `getOrNull() ?: return false`——异常被吃掉，只剩一个 false，
        // 线上完全看不出为什么。用户报「什么都搜不到」时，这条是唯一线索。
        // 有待换的候选档案时按候选注册（见 `Library.registrationProfile`）：
        // 平台才会把新身份绑在新档案上，而当前请求仍用旧档案 + 旧身份继续可用。
        val pendingProfile=library.hasPendingProfile()
        // 换新时不带旧身份：带着它，平台会原样回同一对 `device_id` / `iid`（见 `registerDevice`）。
        val registered=repository.registerDevice(library.registrationProfile(),withIdentity=!pendingProfile) ?: run {
            // 原因从仓库的 `lastRegisterError` 拿：`registerDevice` 的契约是「失败返回 null」，
            // 异常在函数内部就被吃掉了，这里再包 runCatching 只会拿到 null、什么也报不出来。
            val problem=repository.lastRegisterError
            Diagnostics.fail("device_register","设备注册失败","${devicePrefix(this)} cause=${Diagnostics.chainOf(problem)}",problem)
            // 失败也要留在面板上：用户拍的那张图上原本只有「设备正在初始化」，
            // 真实原因（HTTP 码 / 解析失败 / 被拒）完全看不到。
            noteDebugProblem("设备注册失败 · ${Diagnostics.chainOf(problem)}")
            // 记一个待重试标记：下次启动 / 下次取播放地址前会再试一次换新。
            library.markRegistrationPending()
            return false
        }
        library.clearRegistrationPending()
        // 先转正候选档案、再落身份：顺序反了的话 `saveDeviceIdentity` 会把旧档案的指纹写进去，
        // 下一次启动就判成「身份与档案对不上」又去重注册。
        if(pendingProfile) library.commitPendingProfile()
        library.saveDeviceIdentity(registered.deviceId,registered.installId)
        // 面板上的「设备」是 5 秒缓存：刚注册成功就作废，免得用户以为还没注册上。
        DebugEnv.invalidate()
        repository.deviceIdentity=library.deviceIdentity()
        // 注册下发的 Cookie（`install_id` / `ttreq` / `store-region`）落盘：
        // 它们原本只活在这个进程的内存里，重启后第一条请求就会变回「裸设备」。
        val cookies=repository.deviceCookiesSnapshot()
        if(cookies.isNotEmpty()) {
            library.saveRegistrationCookies(cookies)
            com.hgtv.star.data.SessionBootstrap.sessionStore?.saveDeviceCookies(cookies)
        }
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
 * 与 [healDeviceIdentity] 的分工：
 *
 * * 这一条是**预防**：启动时发现身份比当前版本旧，就悄悄换一套；换新成功才替换，
 *   失败（网络 / 服务端拒绝）就继续用旧的那一对——不能因为一次注册失败让用户连首页都看不了。
 * * 那一条是**补救**：真被服务端拒了才丢身份重注册，并自动重试原来的请求。
 */
internal fun MainActivity.refreshRegistrationIdentity() {
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
            library.saveDeviceIdentity(fresh.deviceId,fresh.installId)
            DebugEnv.invalidate()
            val cookies=repository.deviceCookiesSnapshot()
            if(cookies.isNotEmpty()) {
                library.saveRegistrationCookies(cookies)
                com.hgtv.star.data.SessionBootstrap.sessionStore?.saveDeviceCookies(cookies)
            }
            repository.deviceIdentity=library.deviceIdentity()
            DebugLog.record("设备","旧版本注册身份已换新")
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
 * 设备身份自愈：服务端把本机这份 `device_id` / `iid` 当成「没注册过」时，丢掉它重注册一台。
 *
 * ## 为什么需要这一步
 *
 * 升级到 1.2.3 后有一批老用户「搜索搜不出、播放放不了」，去「设置 → 清空缓存」就好了。
 * 清缓存做的事是**删掉本机身份重新注册**，所以症状等于：旧的注册结果还在，
 * 但平台已经不认它了。
 *
 * 不认的原因是注册身份与设备档案必须自洽——平台按 `cdid` / `openudid` 认设备，
 * 而 1.2.3 把这两项从「随机值」改成了「由系统标识符派生」（见 `StableDeviceIds`）。
 * 老用户盘上留着旧随机档案 + 旧 `device_id`，升级后档案按新规则重建，
 * 注册结果却还是旧的那一份；两者对不上，平台一律按陌生设备处理：
 * App 搜索回 `200 + 0 字节`、播放回 `101000 服务异常`。
 *
 * 而代码只判「本机有没有 `registered-device-id`」——有，就不再注册。于是这条状态会一直卡住，
 * 只有清缓存能出来。
 *
 * ## 判据与节流
 *
 * 判据是 [ContentRepository.deviceRejected]（服务端明确不认这份身份的次数），不是异常文案。
 * 每次进程最多重建 2 次、两次之间至少隔 20 秒：重建本身要发一次注册请求，
 * 服务端真挂了的时候不能变成死循环。
 *
 * [retry] 在重建成功之后跑在主线程上——调用方（搜索页 / 播放页）把「重取这一页」交给它，
 * 用户看到的是一次自动恢复，而不是一个错误页。
 *
 * @return true 表示已经接手（调用方应当**直接返回**，不要再显示错误界面）。
 */
internal fun MainActivity.healDeviceIdentity(reason: String,retry: ()->Unit): Boolean {
    val rejected=repository.deviceRejected
    if(rejected<=deviceHealHandled) return false
    val now=android.os.SystemClock.elapsedRealtime()
    if(deviceHealTried>=DEVICE_HEAL_LIMIT || (deviceHealAt>0 && now-deviceHealAt<DEVICE_HEAL_GAP_MS)) return false
    deviceHealHandled=rejected
    deviceHealTried++; deviceHealAt=now
    DebugLog.record("设备","$reason · 服务端不认这份身份，重建一次")
    noteDebugProblem("设备身份被拒 · 正在自动重新注册")
    Diagnostics.warn("device_identity","服务端不认这份设备身份，自动重建 reason=$reason rejected=$rejected")
    io.submit {
        // 档案也要按**现在**这台机器重新给一次：注册身份与档案必须自洽，用旧档案注册出来
        // 还是一对不上号的组合（见 [Library.deviceProfile]）。
        repository.registerProfile=library.deviceProfile()
        val healed=registerDeviceOnce(force=true)
        main.post {
            if(isDestroyed) return@post
            if(!healed) {
                // 重建失败（网络 / 服务端拒绝）：把原因留在面板上，错误页照常显示。
                noteDebugProblem("设备身份重建失败 · ${Diagnostics.chainOf(repository.lastRegisterError)}")
                return@post
            }
            // 新身份交给仓库（搜索 / 播放共用这一份），注册 Cookie 也换成新的。
            repository.deviceIdentity=library.deviceIdentity()
            repository.restoreDeviceCookies(library.registrationCookies())
            DebugLog.record("设备","身份已重建 · 重试原请求")
            retry()
        }
    }
    return true
}

/**
 * 这个失败是不是「服务端不认这台设备」。
 *
 * 只看**接口明确的语义**，不看中文文案的措辞：
 *
 * * 搜索那条是 [DeviceNotRegisteredException]（`200 + 0 字节`）——平台对注册过的设备
 *   **从不**回空响应，因此它是强信号；
 * * 播放那条没有专门类型，只认 `101000`（「服务异常」＝没注册过）。
 *   播放的空响应**不算**：那条路径上它本来就有约一半概率偶发（见 `PLAYBACK_RETRIES`），
 *   拿它当判据会把好设备的身份反复换掉。
 */
internal fun deviceRejectionOf(problem: Throwable?): Boolean {
    if(problem==null) return false
    if(problem is DeviceNotRegisteredException) return true
    return problem.message.orEmpty().contains("101000")
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
internal fun MainActivity.registerForPhoneLogin(profile: Map<String,String>): com.hgtv.star.core.DeviceRegister.Registered? {
    repository.registerProfile=profile
    val registered=repository.registerDevice() ?: run {
        val problem=repository.lastRegisterError
        Diagnostics.fail("device_register_login","登录时设备注册失败","${devicePrefix(this)} cause=${Diagnostics.chainOf(problem)}",problem)
        return null
    }
    library.saveDeviceIdentity(registered.deviceId,registered.installId)
    DebugEnv.invalidate()
    repository.deviceIdentity=library.deviceIdentity()
        // 注册下发的 Cookie 一起落盘（`install_id` / `ttreq` / `store-region`）：
        // 它们原本只活在这个进程的内存里，重启后就没了。
        library.saveRegistrationCookies(repository.deviceCookiesSnapshot())
        com.hgtv.star.data.SessionBootstrap.sessionStore?.saveDeviceCookies(repository.deviceCookiesSnapshot())
    return registered
}

/**
 * 手机号登录成功后：把凭据落盘、交给仓库、刷新「我的」页。
 *
 * 走的是与导入 `cookieStore.xml` **同一条**落地路径（[importAccount] 的校验那一半也照做）：
 * 先拉一次账号资料确认这份会话真能用——平台对无效会话不报错、只把请求降级成匿名设备，
 * 不挡住的话「登录成功」会是假的（界面上只是「一条记录都没有」）。
 */
internal fun MainActivity.savePhoneLogin(credentials: AccountCredentials,anchor: View?) {
    showHint("正在验证登录状态…")
    io.submit {
        val result=runCatching {
            repository.credentials=credentials
            val profile=repository.accountProfile()
            require(profile!=null) { "平台没有返回账号资料，请稍后重试" }
            require(!profile.anonymous) { "这份登录态无效：平台把它当成了匿名设备" }
            credentials
        }
        main.post {
            if(isDestroyed) return@post
            result.onSuccess { valid ->
                library.saveAccount(valid)
                applyAccount()
                // 登录成功立刻打一次 boot 心跳 + account/info：新会话要被服务端确认一次，
                // 导入来的 cookie 更是只有 cookie、要靠这次响应把 `X-Tt-Token` 换回来。
                com.hgtv.star.data.SessionBootstrap.onAppStart(force=true)
                showHint("已登录")
                showCatalog()
                loadAccount(force=true)
            }.onFailure { failure ->
                repository.credentials=library.account().takeIf { it.configured }
                DebugLog.record("账号","登录失败 · ${failure.message.orEmpty().take(140)}")
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
internal fun MainActivity.phoneLoginChoice(anchor: View?) {
    // 直接进表单：手机号与验证码用系统键盘输入，版面与手机上的登录页一致。
    // 手机扫码那条路留给「上行短信」那一步（那一步本来就必须用手机）。
    tvTools.phoneLoginForm(anchor,library.deviceProfile(),
        { profile -> registerForPhoneLogin(profile) },
        { credentials -> savePhoneLogin(credentials,anchor) })
}
