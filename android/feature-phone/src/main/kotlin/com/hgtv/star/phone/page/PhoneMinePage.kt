// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.page

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.Series
import com.hgtv.star.phone.PhoneAccountActivity
import com.hgtv.star.phone.PhoneAccountSync
import com.hgtv.star.phone.PhoneConsent
import com.hgtv.star.phone.PhonePlayerActivity
import com.hgtv.star.phone.PhoneServices
import com.hgtv.star.phone.PhoneSettingsActivity
import com.hgtv.star.phone.PhoneSettingsPage
import com.hgtv.star.phone.phoneNav
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.phone.ui.PhoneCardMenu
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhonePosterFeed
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneTopBar
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.tabBarInset
import com.hgtv.star.shared.ui.SoftStyle
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 我的页：账号状态 + 历史 / 收藏栏目 + 内容网格，右上角提供设置入口。
 *
 * 与 TV 端的分工一致：账号在顶部，「收藏 / 历史」等内容栏在下面；
 * 设置入口放在顶栏右侧，点进去后再按 TV 的四个大项分页。
 */
class PhoneMinePage(context: Context) : LinearLayout(context) {

    private val topBar = PhoneTopBar(context)
    private val library = PhoneServices.library(context)
    /** 账号、历史/收藏等栏目作为列表第一项，和海报一起滚动，不自动隐藏。 */
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val feed = PhonePosterFeed(context)

    /** 默认落在「历史」：用户进「我的」最常见的是接着看，不是先看收藏。 */
    private var tab = 0
    /** 历史栏的完成度筛选：null = 全部，true = 已看完，false = 未看完（与 TV 的 `accountHistoryFilter` 同一语义）。 */
    private var historyFilter: Boolean? = null
    /** 预约栏的上线状态筛选：null = 全部。 */
    private var reserveFilter: Boolean? = null
    /**
     * 收藏更新检查（`:shared` 的 [FavoriteMonitor]，与 TV 端同一份实现）。
     *
     * 电视上它挂在主界面的 `onResume` 上；手机是「我的」页出现时跑一次——
     * 那一页正是用户看「追的剧更新了没有」的地方。检查本身有 1 小时的新鲜度门槛，
     * 反复进出不会反复打接口。
     */
    /** 这一页自己的协程：账号刷新与收藏检查都挂在它上面，页面销毁时一起取消。 */
    private val loadScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
        )

    private val monitor by lazy {
        com.hgtv.star.shared.data.FavoriteMonitor(
            library,
            PhoneServices.repository(context),
        ) { post { if (tabs.getOrNull(tab) == "收藏") renderContent() } }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PhoneStyle.background)

        feed.bottomInset = context.tabBarInset() + context.dp(8)
        // 收藏那一栏的第二行换成「更新至 N 集」：那一栏回答的是「追的剧有没有更新」，
        // 集数与热度在这里没有意义（与 TV 的 `favoriteLabel` 同一份落盘值）。
        feed.subtitleOf = { series ->
            if (tabs.getOrNull(tab) == "收藏") library.favoriteLabel(series.id) else null
        }
        // 长按海报 = 电视端的菜单键。菜单项由 `:core` 的 `CardMenu` 算（与 TV 同一份规则）：
        // 历史栏多「删除记录」，收藏栏的收藏项自动写成「取消收藏」，稍后看写「移出稍后观看」。
        // 早先这里只有一个「删除这一条」的确认框，收藏 / 稍后看 / 继续观看都得绕回详情页。
        feed.applyColumnsFromSettings()
        feed.onLongPress = { series ->
            PhoneCardMenu.show(
                anchor = feed,
                series = series,
                inHistory = tabs.getOrNull(tab) == "历史",
            ) { render() }
        }
        // 点海报先进详情页（与 TV 一致）。
        feed.onOpen = { series ->
            context.phoneNav().open(Route.Detail(series.id, series.title))
        }

        // 账号与栏目交给网格当第一项：它们和海报共用同一个滚动容器，
        // 上滑时自然滚出屏幕，不再有独立页头的自动收起 / 展开。
        feed.setHeader(body)
        topBar.setTitle("我的")
        topBar.setAction(PhoneGlyph.Kind.SETTINGS, "设置") {
            PhoneSettingsActivity.open(context, PhoneSettingsPage.PLAYBACK)
        }
        // 顶栏固定，下面整块是列表（第一项就是账号与栏目）。
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(feed, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 页面被系统回收（或换主题重建）时停掉在途的账号刷新，别让它回来改一张已经没人看的界面。
        loadScope.cancel()
    }

    /**
     * 由宿主在回到这一页时调用（从账号页 / 设置页 / 播放页返回）。
     *
     * 必须重铺一次：登录状态、账号侧那两栏、收藏更新状态都可能已经变了，
     * 而 `onAttachedToWindow` 只在「这一页第一次挂上来」时跑一次。
     */
    fun onShown() {
        // 设置页可能刚改过「列表每行」：这一页一直留在 Activity 里，
        // 不重新挂窗口时不会走 `PhonePosterFeed.onAttachedToWindow`，必须在这里主动对齐列数。
        feed.applyColumnsFromSettings()
        refresh()
        // 登录着的时候顺手刷一次账号数据（与 TV 的 `loadAccount` 同一条路）：
        // 不进这一步，账号侧的收藏 / 历史永远不会出现在这一页上。
        if (runCatching { PhoneServices.repository(context).signedIn }.getOrDefault(false)) {
            post {
                loadScope.launch {
                    PhoneAccountSync.refresh(context)
                    render()
                }
            }
        }
    }

    private fun refresh() {
        // 身份 / 凭据灌进仓库：从别的入口直接进「我的」时也要成立
        //（正常情况下主界面读完书库已经灌过一次，这里是幂等的兜底）。
        if (library.isLoaded) com.hgtv.star.phone.PhoneIdentity.apply(context, library)
        // 使用须知：**进这一页就交代一次账号风险**（与电视端同一条语义，见 `PhoneConsent`）。
        // 放在最前面：用户在这一页能点到「账号」「收藏」，先说清楚比事后补一句更有意义。
        (context as? android.app.Activity)?.let { activity ->
            if (library.isLoaded) PhoneConsent.ensureUsage(activity, library)
        }
        // 第一次挂上来时也要补一次账号数据：`onShown` 只在「回到这一页」时被调用，
        // 首次进入走的是 `onAttachedToWindow`。
        if (runCatching { PhoneServices.repository(context).signedIn }.getOrDefault(false)) {
            loadScope.launch { PhoneAccountSync.refresh(context); render() }
        }
        if (!library.isLoaded) {
            library.loadAsync(onFailure = { post { render() } }, onReady = { post { render() } })
            return
        }
        render()
        // 进这一页顺手检查一次收藏更新（有 1 小时门槛，不会反复请求）。
        runCatching { monitor.check() }
    }

    private fun render() {
        // 账号两栏在退出登录后消失：栏号越界时退回「历史」，否则整页会是空的。
        // 默认页也是历史（见字段 `tab`），这里保持同一个回退目标。
        if (tab >= tabs.size) tab = 0
        body.removeAllViews()
        body.addView(buildAccount())
        body.addView(buildTabs())
        buildFilterRow()?.let { body.addView(it) }
        buildFavoriteRefresh()?.let { body.addView(it) }
        feed.refreshHeader()
        renderContent()
    }

    // ---------------------------------------------------------------- 账号

    /**
     * 账号行：登录了显示昵称 / 平台号，没登录显示「未登录 · 点这里登录」。
     *
     * 整行都是入口（进 [PhoneAccountActivity]）：手机上「我的」页的账号区是登录与退出的唯一入口，
     * 与 TV 端顶部那枚账号芯片同一个角色，位置按手机习惯放在最上面一行。
     */
    private fun buildAccount(): View {
        // 内存那一份最新（刚刷新过），没有才读缓存。
        val cached = PhoneAccountSync.data ?: runCatching { library.accountCache() }.getOrNull()?.data
        val profile = cached?.profile
        val signedIn = runCatching { library.account().configured }.getOrDefault(false) ||
            PhoneServices.repository(context).signedIn

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(8))
            setOnClickListener {
                context.phoneNav().open(Route.Account)
            }
        }
        val avatar = TextView(context).apply {
            text = if (signedIn) (profile?.nickname?.take(1) ?: "红") else "红"
            setTextColor(PhoneStyle.text)
            phoneSize(16)
            gravity = Gravity.CENTER
            applySoft(SoftStyle.RAISED, PhoneStyle.surfaceHigh, 20f, 3f)
        }
        row.addView(avatar, LinearLayout.LayoutParams(context.dp(42), context.dp(42)))

        val names = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), 0, 0, 0)
        }
        names.addView(TextView(context).apply {
            text = when {
                !signedIn -> "未登录"
                !profile?.nickname.isNullOrBlank() -> profile?.nickname.orEmpty()
                else -> "已登录"
            }
            setTextColor(PhoneStyle.text)
            phoneSize(17)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val sub = when {
            !signedIn -> "点这里登录 · 记录仅保存在本机"
            !profile?.displayId.isNullOrBlank() -> "平台号 ${profile?.displayId}"
            else -> "点这里查看账号"
        }
        names.addView(TextView(context).apply {
            text = sub
            setTextColor(PhoneStyle.textFaint)
            phoneSize(12)
            setPadding(0, context.dp(3), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(PhoneGlyph(context, PhoneGlyph.Kind.CHEVRON).apply {
            tint = PhoneStyle.textFaint
        }, LinearLayout.LayoutParams(context.dp(14), context.dp(14)))
        return row
    }

    /**
     * 五个内容入口。
     *
     * 前三个是本机的（历史 / 收藏 / 稍后看，与 TV 首页那三栏同名，历史排第一），
     * 后两个是账号的（点赞 / 预约）：TV 的「我的」页把这两栏铺在同一处，
     * 手机上不铺的话，登录之后用户在手机上看不到自己的点赞与预约。
     *
     * 未登录时这两栏不出现——它们的数据只可能来自账号，摆一个永远空的入口没有意义。
     */
    /** 历史排第一：默认页与首个 tab 都是它，用户进来不用先点一下。 */
    private val tabsLocal = listOf("历史", "收藏", "稍后看")
    private val tabsAccount = listOf("点赞", "预约")
    private val tabs: List<String>
        get() = tabsLocal + if (signedIn()) tabsAccount else emptyList()

    /**
     * 筛选芯片（历史：已看完 / 未看完；预约：已上线 / 待上线）。
     *
     * 点已选中的那一枚 = 取消筛选回到「全部」，与 TV 的 `ToggleChip` 同一条语义：
     * 一个筛选只该有两种状态（选中 / 不选），不需要第三枚「全部」占地方。
     */
    private fun buildFilterRow(): View? {
        val options: List<Pair<Boolean, String>> = when (tabs.getOrNull(tab)) {
            "历史" -> listOf(true to "已看完", false to "未看完")
            "预约" -> listOf(true to "已上线", false to "待上线")
            else -> return null
        }
        val current = if (tabs.getOrNull(tab) == "历史") historyFilter else reserveFilter
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), 0, context.dp(16), context.dp(4))
        }
        options.forEach { (value, label) ->
            val on = current == value
            row.addView(TextView(context).apply {
                text = label
                setTextColor(if (on) PhoneStyle.onLit else PhoneStyle.text)
                // 次级筛选：**必须比上面那排 tab（16 号字 / 33dp）小一档**。
                // 之前这里是 12 号字 + 7dp 内边距 + 6dp 柔光带 = 39dp，
                // 比它要筛的那排 tab 还高，层级是反的：用户第一眼看到的是筛选器。
                phoneSize(12)
                setPadding(context.dp(10), context.dp(3), context.dp(10), context.dp(3))
                applySoft(
                    SoftStyle.RAISED,
                    if (on) PhoneStyle.lit else PhoneStyle.surface,
                    PhoneStyle.RADIUS_PILL, 3f,
                )
                setOnClickListener {
                    val next = if (on) null else value
                    if (tabs.getOrNull(tab) == "历史") historyFilter = next else reserveFilter = next
                    render()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = context.dp(8) })
        }
        return row
    }

    /**
     * 收藏栏的「检查更新」入口。
     *
     * TV 上是收藏栏行末的一张底板 + 页头提示；手机上放在栏目行的右侧，
     * 检查中显示进度（`monitor.status` 就是那句话），完成后就地刷新副标题。
     */
    private fun buildFavoriteRefresh(): View? {
        if (tabs.getOrNull(tab) != "收藏") return null
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), 0, context.dp(16), context.dp(2))
        }
        val label = TextView(context).apply {
            text = monitor.status
            setTextColor(PhoneStyle.textFaint)
            phoneSize(11)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = "检查更新"
            setTextColor(PhoneStyle.accent)
            phoneSize(12)
            setPadding(context.dp(10), context.dp(6), context.dp(2), context.dp(6))
            setOnClickListener {
                runCatching { monitor.check(force = true) }
                // 状态由 [FavoriteMonitor] 自己推（它的回调会重建这一栏）。
                postDelayed({ label.text = monitor.status }, 120)
            }
        })
        return row
    }

    private fun buildTabs(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(6), context.dp(16), context.dp(6))
        }
        tabs.forEachIndexed { index, label ->
            row.addView(TextView(context).apply {
                text = label
                phoneSize(16)
                setTextColor(if (index == tab) PhoneStyle.text else PhoneStyle.textFaint)
                setTypeface(
                    typeface,
                    if (index == tab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                setPadding(0, context.dp(6), context.dp(24), context.dp(6))
                setOnClickListener {
                    if (tab == index) return@setOnClickListener
                    tab = index
                    render()
                }
            })
        }
        return row
    }

    /**
     * 内容网格：**本机记录 + 账号记录**合并后显示。
     *
     * 合并规则与 TV 端同一份（`AccountMerge`）：本机在前（带着更新状态与进度），
     * 账号侧补在后面，同一部剧只出现一次。不合并的话，登录之后用户会以为
     * 「我在手机上收藏的那些不见了」——其实它们还在账号里，只是这一页只读了本机那一半。
     *
     * 账号侧的历史要滤掉「本机删过」的（[com.hgtv.star.shared.data.Library.historyDeleted]）：
     * 平台对短剧进度列表的删除是软删，不滤就会「删了过一会儿又回来」。
     */
    private fun renderContent() {
        val cached = PhoneAccountSync.data ?: runCatching { library.accountCache() }.getOrNull()?.data
        val rows = when (tabs.getOrNull(tab) ?: "历史") {
            "历史" -> com.hgtv.star.shared.account.AccountMerge.series(
                runCatching { library.history() }.getOrDefault(emptyList())
                    .filter { p -> historyFilter?.let { want -> p.completed == want } ?: true }
                    .map { it.series },
                cached?.history
                    ?.filterNot { library.historyDeleted(it.series.id) }
                    ?.filter { row -> historyFilter?.let { want -> historyFinished(row) == want } ?: true }
                    // 平台那条记录里「看到第几集 / 集内秒数」是单独字段，卡片只认 `badge`：
                    // 写进那一行，用户才看得出自己看到哪儿了（与本机进度那半份的语义一致）。
                    ?.map { row ->
                        row.series.copy(
                            badge = "第 ${row.episodeIndex + 1} 集" +
                                if (row.position > 0) " · ${clock(row.position)}" else ""
                        )
                    }.orEmpty(),
            )
            "收藏" -> com.hgtv.star.shared.account.AccountMerge.series(
                runCatching { library.favorites() }.getOrDefault(emptyList()),
                cached?.favorites?.map { it.series }.orEmpty(),
            )
            "稍后看" -> runCatching { library.later() }.getOrDefault(emptyList())
            "点赞" -> cached?.likes?.map { it.series }.orEmpty()
            else -> cached?.reserves
                ?.filter { row -> reserveFilter?.let { want -> row.online == want } ?: true }
                ?.map { it.series }.orEmpty()
        }
        feed.submit(rows)
    }

    /** 本机有一条同剧进度且标着 `completed`，或平台说看到的就是最后一集。 */
    private fun historyFinished(row: com.hgtv.star.core.AccountHistory): Boolean {
        if (runCatching { library.progress(row.series.id)?.completed }.getOrNull() == true) return true
        return row.episodes > 0 && row.episodeIndex + 1 >= row.episodes
    }

    /** 位置读数：与电视端 `formatTime` 同一份（`:core` 的 `PlaybackInfoLine.time`）。 */
    private fun clock(ms: Long): String = com.hgtv.star.core.PlaybackInfoLine.time(ms)

    private fun signedIn(): Boolean = runCatching {
        library.account().configured || PhoneServices.repository(context).signedIn
    }.getOrDefault(false)

    fun scrollToTop() {
        feed.scrollToTop()
    }

}
