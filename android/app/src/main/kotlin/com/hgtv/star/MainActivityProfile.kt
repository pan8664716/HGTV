// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.graphics.Typeface
import android.view.*
import android.widget.*
import com.hgtv.star.core.*
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.SoftStyle
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.list.showCatalog

// 本文件是从 MainActivity.kt 拆出来的「「我的」页：抬头、四个栏目、账号数据与登录提示」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.profileHead(row: LinearLayout) {
    val credentials=library.account().takeIf { it.configured }
    // 资料只在**真的登录着**时才算数：`accountData` 是内存里的上一份快照，退出 / 被动掉线
    // 之后它可能还没被清掉，照着它铺昵称与头像，用户看到的就是「明明退出了，一打开还是
    // 这个账号」。判据统一用凭据，与 [accountNotice] / 四个栏目的空态保持一致。
    val profile=if(credentials==null) null else accountData?.profile
    // 圆形头像：`ArtworkLoader` 只会把位图设进 ImageView，圆角靠一个圆形遮罩的
    // `SoftDrawable` 做不到（那是背景不是前景），因此这里给 ImageView 挂一个圆形裁剪的
    // `ViewOutlineProvider`（API 21+），加 `clipToOutline` 就得到正圆。
    val avatar=ImageView(this).apply {
        scaleType=ImageView.ScaleType.CENTER_CROP
        contentDescription="账号头像"
        // API 19/20 没有 outline 裁剪：那边头像是方形（背景仍是圆形凹槽），不额外画遮罩位图。
        com.hgtv.star.compat.clipToCircleCompat(this)
        // 还没读到资料时先给一块凹槽色：比空白好认，也不会让这一行忽高忽低。
        background=softPanel(TvStyle.well,TvStyle.RADIUS_PILL,style=SoftStyle.INSET)
    }
    profileAvatar=avatar
    profile?.avatar?.takeIf { it.isNotBlank() }?.let { loadCover(avatar,it) }
    row.addView(avatar,lp(dp(PROFILE_AVATAR),dp(PROFILE_AVATAR)))
    // 昵称小字（比标题小两档）：它是身份标注，不是这一页的主角。
    val name=profile?.nickname?.takeIf { it.isNotBlank() }
        ?: if(credentials==null) "未登录" else "正在读取…"
    val nameView=text(name,14f,if(credentials==null) muted else TvStyle.textDim).apply { maxLines=1 }
    gapInto(row,nameView,lp(-2,-2),TvStyle.GAP_INLINE)
    // 未登录：身份行不再放任何入口，手机号登录表单直接铺在下面（见 ListPage）。
    if(credentials==null) return
    // 「已看完 / 未看完」：只在**历史**这一栏出现，紧跟在昵称后面（头像｜昵称｜已看完｜未看完）。
    // 两枚是互斥开关：点已选中的那一枚 = 回到「全部」；点另一枚就换成那一档。
    // 这一行左边是身份、右边是账号动作（退出 / 刷新 / 清空），筛选夹在中间，读起来是
    // 「这是谁 → 看哪一类 → 账号操作」，方向与从左到右的阅读顺序一致。
    historyFilterButtons.clear()
    reserveFilterButtons.clear()
    var filterEnd: View?=null
    // 「我的」页的筛选芯片只有两处：历史看完成度、预约看上线状态。两处共用这一段：
    // 位置、间距、互斥语义与焦点链都一致，只是文案与状态字段不同。
    val filterChips: List<Pair<Boolean,String>>?=when(accountTab) {
        "history" -> listOf(true to "已看完",false to "未看完")
        "reserves" -> listOf(true to "已上线",false to "待上线")
        else -> null
    }
    if(filterChips!=null) {
        val buttons=if(accountTab=="history") historyFilterButtons else reserveFilterButtons
        fun current()=if(accountTab=="history") accountHistoryFilter else accountReserveFilter
        fun setCurrent(value: Boolean?) {
            if(accountTab=="history") accountHistoryFilter=value else accountReserveFilter=value
        }
        var previous: View?=null
        filterChips.forEach { (value,label) ->
            val chip=ToggleChip(this,label,current()==value,ButtonSize.COMPACT) {
                // 点已选中的那一枚 = 取消筛选，回到「全部」。
                setCurrent(if(current()==value) null else value)
                buttons.forEach { (state,view) -> (view as? ToggleChip)?.setActive(current()==state) }
                refreshAccountListIfVisible()
            }
            buttons[value]=chip
            gapInto(row,chip,lp(-2,-2),TvStyle.GAP_TIGHT)
            // 两枚之间左右直连：遥控器右移不会被右边那一段弹性空白或内容区抢走。
            previous?.let { prev ->
                prev.nextFocusRightId=chip.id
                chip.nextFocusLeftId=prev.id
            }
            previous=chip
            filterEnd=chip
        }
        buttons[filterChips.first().first]?.let { first -> first.nextFocusLeftId=first.id }
    }
    // 已登录：只留「退出」与「刷新」。
    // 一个吃掉剩余宽度的空位把这两个入口推到行尾：头像与昵称留在左边，
    // 退出 / 刷新贴右边缘，中间的长昵称也不会把它们挤到名字后面。
    row.addView(Space(this),lp(0,-2).apply { weight=1f })
    lateinit var logout: TvButton
    logout=addButton(row,"退出",size=ButtonSize.COMPACT) {
        tvTools.confirm("退出账号",
            "退出后「我的」页会回到未登录状态；本机的收藏与观看记录不受影响，随时可以再登录。",
            logout) {
            // 先停心跳再清凭证：反过来的话清完还会再打一次，且可能把刚退掉的会话又确认回来。
            com.hgtv.star.data.SessionBootstrap.onLogout()
            library.clearAccount()
            // 退出后回到默认的扫码登录（不是上次选的那一种）：用户下一次进来读到的
            // 就是「电视上什么都不用敲」的那条路。
            loginMode=ACCOUNT_PANEL_QR
            applyAccount()
            showCatalog()
        }
    }
    // 「刷新」：凭据没变、只是想重取一次账号数据时用（平台偶发空响应体，
    // 或者另一台设备上刚改过收藏 / 历史）。
    val refresh=addButton(row,"刷新",size=ButtonSize.COMPACT,gap=TvStyle.GAP_BLOCK) {
        // **不把已有内容清掉**：清空之后整页会退回「正在读取账号信息」，
        // 用户按一下刷新就看不到刚才那些收藏 / 记录了，等请求回来才恢复——观感是「刷没了」。
        // 现在旧内容留在原地，新数据落地后只重画真正变了的那一栏（见 [refreshAccountViews]）。
        accountError=null
        loadAccount(force=true)
        showHint("正在刷新账号信息…")
    }
    // 「清空全部记录」：只在**历史**这一栏、而且确实有记录时出现，排在「退出 / 刷新」之后。
    // 它是这一栏的批量动作（收藏 / 点赞 / 预约没有这种「一次清空」的语义），
    // 放在身份行里与「退出 / 刷新」同一组，比在内容区单独占一行更省地方。
    val clear=if(accountTab=="history") historyClearButton(row,ButtonSize.COMPACT,gap=TvStyle.GAP_BLOCK) else null
    // 这三枚在同一行里：左右按顺序走，两端停住。不接的话遥控器右移会被下面内容区抢走
    // （这一行上面没有别的可聚焦控件，框架会按坐标去内容区里找）。
    // 筛选芯片（历史栏才有）→ 退出 → 刷新 → 清空，同一行里左右直连，两端停住。
    val leftOfLogout=filterEnd
    if(clear!=null) {
        logout.nextFocusRightId=refresh.id
        refresh.nextFocusLeftId=logout.id
        refresh.nextFocusRightId=clear.id
        clear.nextFocusLeftId=refresh.id
        clear.nextFocusRightId=clear.id
        logout.nextFocusLeftId=(leftOfLogout ?: logout).id
    } else {
        logout.nextFocusRightId=refresh.id
        refresh.nextFocusLeftId=logout.id
        refresh.nextFocusRightId=refresh.id
        logout.nextFocusLeftId=(leftOfLogout ?: logout).id
    }
    leftOfLogout?.let { chip -> chip.nextFocusRightId=logout.id }
    // 资料还没到、而且**一次都还没试过**时主动拉一次（落地后页面自己重建）。
    // 失败过就不再自动重发：否则「失败 → showCatalog → 再拉」会变成无限重试，
    // 重试交给下面 [accountNotice] 里那个按钮。
    if(accountData==null && accountError==null) loadAccount()
}
/**
 * 「我的」页当前栏目里的条目（转成 [Series]，直接喂给统一的卡片栅格）。
 *
 * 四栏各自的数据来自 [accountData]；还没拉到（或那一栏为空）时返回空列表，
 * 界面走「本页没有更多内容」那一支的文案。
 */
internal fun MainActivity.accountItems(key: String): List<Series> {
    val data=accountData ?: return emptyList()
    return when(key) {
        "favorites" -> data.favorites.map { it.series }
        // 本机删过的那几条不再出现（见 [Library.historyDeleted]），再按「已看完 / 未看完」筛一次。
        "history" -> data.history
            .filterNot { library.historyDeleted(it.series.id) }
            .filter { row -> accountHistoryFilter?.let { want -> historyFinished(row)==want } ?: true }
            .map(::accountHistoryCard)
        "likes" -> data.likes.map { it.series }
        // 预约按「已上线 / 待上线」筛（见 [accountReserveFilter]）。
        "reserves" -> data.reserves
            .filter { row -> accountReserveFilter?.let { want -> row.online==want } ?: true }
            .map { it.series }
        else -> emptyList()
    }
}
/**
 * 账号侧一条历史 → 卡片上用的 [Series]。
 *
 * 平台那条记录里「看到第几集」与「集内秒数」是**单独的字段**（`vid_index` 与
 * `current_play_time`），而栅格里的卡片只认 [Series.badge] 这一个状态行，
 * 因此这里把它写进那一行：`第 12 集 · 03:21`。
 *
 * 不这么写的话，「我的 → 历史」与历史集合页上的卡片只会显示剧集自己的角标（「全 40 集」），
 * 用户看不出自己看到哪儿了——同步过来的记录尤其明显（这台设备上没有本机进度可显示）。
 */
/**
 * 一条账号历史是不是「已看完」。
 *
 * 两个来源任一成立就算看完：
 * * 平台给了总集数、而且看到的就是最后一集（`episodeIndex` 从 0 起，`episodes` 是总集数）；
 * * 本机有一条同剧进度、且标着 `completed`（这台设备上看完的，比云端那行更准）。
 *
 * 总集数未知（平台没给）时只能靠本机那一条，两者都没有就按「未看完」算——
 * 把不确定的算成「已看完」会让用户找不到自己还在追的剧。
 */
internal fun MainActivity.historyFinished(row: AccountHistory): Boolean {
    if(library.progress(row.series.id)?.completed==true) return true
    return row.episodes>0 && row.episodeIndex+1>=row.episodes
}
internal fun MainActivity.accountHistoryCard(row: AccountHistory): Series =
    row.series.copy(badge="第 ${row.episodeIndex+1} 集"+(if(row.position>0) " · ${formatTime(row.position)}" else ""))
/**
 * 切换「我的」页的栏目。
 *
 * 与榜单页的芯片同一条规则：焦点留在刚点的那一枚上（用户还在挑栏目），
 * 内容落地后也不许把焦点带去第一张卡。
 */
internal fun MainActivity.switchAccountTab(key: String) {
    if(key==accountTab) return
    accountTab=key
    accountButtons.forEach { (option,chip) -> (chip as? ToggleChip)?.setActive(option==key) }
    // 换栏目就是换一份列表：页码、记住的焦点卡、滚动位置都从头开始。
    page=1; catalogFocus=""
    // 焦点一律留在刚点的那枚芯片上：这一栏的内容就在内存里，切换是「同一页换一份列表」，
    // 用户还在挑栏目，不该被推进内容区（见 [showCatalog] 的 [keepChip]）。
    chipFocusKey="account:$key"
    focusFirstCardOnLanding=false
    showCatalog(keepChip=true)
}
/** 「我的」页身份行里所有可聚焦控件（按钮），按左右顺序。 */
internal fun MainActivity.profileHeadControls(): List<View> {
    val head=profileHeadRow as? LinearLayout ?: return emptyList()
    return (0 until head.childCount).mapNotNull { head.getChildAt(it) }.filter { it.isFocusable }
}
/** 身份行里第一个可聚焦控件；没有（理论上不会）时返回 null，调用方退回别的落点。 */
internal fun MainActivity.profileHeadControl(): View? = profileHeadControls().firstOrNull()
/** 「我的」页的一张凸起面板（未导入 / 读取失败 / 参数无效这三种状态用）。 */
internal fun MainActivity.profilePanel(parent: LinearLayout,title: String): LinearLayout {
    val band=dp(TvStyle.SOFT_BAND_LARGE)
    val panel=column().apply {
        setPadding(dp(24)+band,dp(20)+band,dp(24)+band,dp(20)+band)
        background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_DIALOG,band=TvStyle.SOFT_BAND_LARGE,depth=1.05f)
    }
    if(title.isNotBlank()) panel.addView(text(title,18f,TvStyle.text).apply { setTypeface(null,Typeface.BOLD) },lp(-2,-2))
    parent.addView(panel,lp(-1,-2).apply { if(parent.childCount>1) topMargin=dp(TvStyle.GAP_GROUP) })
    return panel
}
/**
 * 账号侧请求失败时给用户看的一句话；**不该打扰的返回 null**。
 *
 * 需要 `sessionid` 的接口（账号资料 / 收藏 / 历史 / 点赞 / 预约，以及带登录态的写接口）
 * 有两条必须说清楚的失败：
 *
 * * **还没导入**（[SignInRequiredException]）——用户不知道这些功能要凭据，得告诉他去哪里导入；
 * * **凭据失效**——平台不报错、只把请求降级成匿名设备，那种情况在导入时就被挡住了
 *   （见 [parseCredentials] 的 `anonymous` 校验），这里只兜底说一句「退出后重新登录」。
 *
 * 其余失败（网络抖动、网关挡掉）不弹：它们会自动重试，弹出来只是噪音。
 */
internal fun MainActivity.accountFailureText(failure: Throwable): String? = when {
    failure is com.hgtv.star.core.SignInRequiredException ->
        "需要先在「我的」页导入账号参数（sessionid）"
    failure.message.orEmpty().contains("空响应体") -> null
    failure is java.net.UnknownHostException -> "网络不可用，账号信息暂时取不到"
    else -> "账号信息读取失败：${failure.message.orEmpty().take(60)}"
}

/**
 * 「我的」页在资料还没拿到（或没通过验证）时，内容区显示什么。
 *
 * 三种情况分开写清楚，**不能停在一句「正在读取」上**：那样用户看到的就是
 * 「点了导入什么也没发生」（这正是之前的症状）。读取失败时把原因与重试都摆出来。
 */
internal fun MainActivity.accountNotice(parent: LinearLayout) {
    val credentials=library.account().takeIf { it.configured }
    // 未导入：**什么都不铺**。入口已经在上面的身份行里（「导入账号参数」按钮），
    // 这里再摆一整块说明只是把页面填满——用户第一眼要看的是按钮，不是说明书。
    if(credentials==null) return
    val problem=accountError
    if(problem!=null) {
        val card=profilePanel(parent,"读取账号信息失败")
        card.addView(text(problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(200)
            .ifBlank { "请检查电视的网络后重试。" },15f,TvStyle.textDim))
        addButton(card,"重试",tone=ButtonTone.PRIMARY) { accountError=null; loadAccount(force=true); showCatalog() }
        return
    }
    val data=accountData
    if(data==null) {
        val card=profilePanel(parent,"正在读取账号信息")
        card.addView(text("正在拉取资料与四个栏目，请稍候。",15f,TvStyle.textDim))
        loadAccount()
        return
    }
    val card=profilePanel(parent,"参数未通过验证")
    val reason=data.errors["资料"]?.takeIf { it.isNotBlank() } ?: "平台没有返回账号资料"
    card.addView(text("$reason\n\n登录状态可能已过期。请用页头的「退出」回到未登录状态，再重新登录。",15f,TvStyle.textDim))
}
internal fun MainActivity.shortDate(value: Long): String = if(value<=0) "时间未知" else java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.getDefault()).format(java.util.Date(value))
/**
 * 打开系统文件选择器挑 `cookieStore.xml`。
 *
 * `ACTION_OPEN_DOCUMENT`（而不是 GET_CONTENT）：它给的是持久可读的 Uri，
 * 电视上从 U 盘 / 下载目录里挑文件更稳，也不要求任何存储权限。
 *
 * **结果不在这里处理**：文件选择器会让本页走一遍 onStop → onResume，而 onResume 里的
 * [showCatalog] 会 `invalidatePage() → cancelWork()`，把紧随其后提交的导入任务一并作废
 * （症状就是「选完文件什么也没发生」）。因此这里只记下 Uri，等 onResume 收尾之后再消费。
 */
