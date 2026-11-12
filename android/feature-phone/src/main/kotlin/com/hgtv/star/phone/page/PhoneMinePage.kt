// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.page

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.hgtv.star.core.Series
import com.hgtv.star.phone.PhoneAccountSync
import com.hgtv.star.phone.PhoneConsent
import com.hgtv.star.phone.PhonePlayerActivity
import com.hgtv.star.phone.PhoneServices
import com.hgtv.star.phone.PhoneSettingsActivity
import com.hgtv.star.phone.PhoneSettingsPage
import com.hgtv.star.phone.phoneNav
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.phone.ui.PhoneCardMenu
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhonePosterFeed
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneTopBar
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.tabBarInset
import com.hgtv.star.shared.ui.LoadingDotsView
import com.hgtv.star.shared.ui.SoftStyle
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 我的页：顶部账号入口 + 历史 / 收藏栏目 + 内容网格，右上角提供设置入口。
 *
 * 与 TV 端的分工一致：账号在顶部，「收藏 / 历史」等内容栏在下面；
 * 设置入口放在顶栏右侧，点进去后再按 TV 的四个大项分页。
 */
class PhoneMinePage(
    context: Context,
    initialState: State? = null,
) : FrameLayout(context) {

    data class State(
        val tab: Int,
        val historyFilter: Boolean?,
        val reserveFilter: Boolean?,
        val feed: PhonePosterFeed.Snapshot,
    )

    private val compactTopBar = context.resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val topBar = PhoneTopBar(context)
    private val pageContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topAvatar = TextView(context).apply {
        val size = context.dp(if (compactTopBar) 32 else 44)
        text = "红"
        setTextColor(PhoneStyle.text)
        phoneSize(if (compactTopBar) 14 else 17)
        gravity = Gravity.CENTER
        minWidth = size
        minimumHeight = size
        applySoft(SoftStyle.RAISED, PhoneStyle.surfaceHigh, size / 2f, 3f)
        contentDescription = "账号"
    }
    private val topName = TextView(context).apply {
        text = "未登录"
        setTextColor(PhoneStyle.text)
        phoneSize(if (compactTopBar) 15 else 17)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 2
        ellipsize = null
    }
    private val topSub = TextView(context).apply {
        text = "点这里登录 · 记录仅保存在本机"
        setTextColor(PhoneStyle.textFaint)
        phoneSize(if (compactTopBar) 10 else 12)
        setPadding(0, context.dp(2), 0, 0)
        maxLines = 2
        ellipsize = null
    }
    private val topRefresh = LinearLayout(context).apply {
        val size = context.dp(if (compactTopBar) 24 else 28)
        gravity = Gravity.CENTER
        contentDescription = "刷新账号数据"
        background = roundedBackground(PhoneStyle.surfaceHigh, size / 2f)
        addView(PhoneGlyph(context, PhoneGlyph.Kind.REFRESH).apply {
            tint = PhoneStyle.text
            contentDescription = "刷新账号数据"
        }, LinearLayout.LayoutParams(context.dp(16), context.dp(16)))
        setOnClickListener { refreshData() }
    }
    private val topAccount = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(topAvatar, LinearLayout.LayoutParams(
            context.dp(if (compactTopBar) 32 else 44),
            context.dp(if (compactTopBar) 32 else 44),
        ))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(10), 0, 0, 0)
            addView(topName, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(topSub)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        addView(topRefresh, LinearLayout.LayoutParams(
            context.dp(if (compactTopBar) 24 else 28),
            context.dp(if (compactTopBar) 24 else 28),
        ).apply { leftMargin = context.dp(8) })
        contentDescription = "账号，未登录"
        setOnClickListener { context.phoneNav().open(Route.Account) }
    }
    private val library = PhoneServices.library(context)
    /** 账号、历史/收藏等栏目作为列表第一项，和海报一起滚动，不自动隐藏。 */
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val feed = PhonePosterFeed(context)

    /** 默认落在「历史」：用户进「我的」最常见的是接着看，不是先看收藏。 */
    private var tab = initialState?.tab ?: 0
    /** 历史栏的完成度筛选：null = 全部，true = 已看完，false = 未看完（与 TV 的 `accountHistoryFilter` 同一语义）。 */
    private var historyFilter: Boolean? = initialState?.historyFilter
    /** 预约栏的上线状态筛选：null = 全部。 */
    private var reserveFilter: Boolean? = initialState?.reserveFilter
    /** 转屏重建带来的内容快照：首次挂载直接复用，不再拉账号数据。 */
    private var restoredFeed: PhonePosterFeed.Snapshot? = initialState?.feed
    private var skipNextOnShownRefresh = initialState != null
    private var refreshing = false
    private var refreshOverlay: FrameLayout? = null
    /** 当前栏目是否处于批量选择模式；切换栏目 / 筛选或删除后退出。 */
    private var batchMode = false
    private var batchBar: LinearLayout? = null
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
        pageContent.orientation = LinearLayout.VERTICAL
        setBackgroundColor(PhoneStyle.background)

        feed.bottomInset = context.tabBarInset() + context.dp(8)
        // 收藏那一栏的第二行换成「更新至 N 集」：那一栏回答的是「追的剧有没有更新」，
        // 集数与热度在这里没有意义（与 TV 的 `favoriteLabel` 同一份落盘值）。
        feed.subtitleOf = { series ->
            if (tabs.getOrNull(tab) == "收藏") library.favoriteLabel(series.id) else null
        }
        // 历史与 TV 首页一样画已播进度；收藏 / 稍后看没有播放进度时不占位。
        feed.progressOf = { series ->
            if (tabs.getOrNull(tab) != "历史") null
            else runCatching {
                library.progress(series.id)?.let { p ->
                    when {
                        p.completed -> 100
                        p.duration > 0 -> (p.position * 100 / p.duration).toInt().coerceIn(0, 100)
                        else -> null
                    }
                }
            }.getOrNull()
        }
        // 长按海报 = 电视端的菜单键。菜单项由 `:core` 的 `CardMenu` 算（与 TV 同一份规则）：
        // 历史栏多「删除记录」，收藏栏的收藏项自动写成「取消收藏」，稍后看写「移出稍后观看」。
        // 早先这里只有一个「删除这一条」的确认框，收藏 / 稍后看 / 继续观看都得绕回详情页。
        feed.applyColumnsFromSettings()
        feed.onLongPress = { series ->
            if (!batchMode) {
                PhoneCardMenu.show(
                    anchor = feed,
                    series = series,
                    inHistory = tabs.getOrNull(tab) == "历史",
                    // 删除记录会改变列表成员：整页重铺一次。
                    onChanged = { render() },
                    // 收藏 / 稍后看只是状态变化：只重绑可见项，页面不闪、滚动位置不动。
                    onItemChanged = { feed.refreshItems() },
                )
            }
        }
        // 点海报先进详情页（与 TV 一致）。
        feed.onOpen = { series ->
            context.phoneNav().open(Route.Detail(series.id, series.title))
        }
        feed.onSelectionChanged = { updateBatchBar() }

        // 栏目交给网格当第一项：它和海报共用同一个滚动容器，
        // 上滑时自然滚出屏幕，不再有独立页头的自动收起 / 展开。
        feed.setHeader(body)
        topBar.setLeadingView(topAccount, context.dp(if (compactTopBar) 56 else 76))
        // 底部 2dp 留白收掉：账号区与栏目之间不再叠一层。
        topBar.tightenBottom()
        topBar.setAction(PhoneGlyph.Kind.SETTINGS, "设置") {
            PhoneSettingsActivity.open(context, PhoneSettingsPage.PLAYBACK)
        }
        // 顶栏固定，下面整块是列表（第一项就是栏目）。
        pageContent.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        pageContent.addView(feed, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        addView(pageContent, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val restored = restoredFeed
        if (restored?.loaded == true) {
            restoredFeed = null
            render(restored)
        } else {
            restoredFeed = null
            refresh()
        }
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
        exitBatchMode()
        if (skipNextOnShownRefresh) {
            skipNextOnShownRefresh = false
            return
        }
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

    /** 与 TV 一致：保留当前列表，居中显示等待，账号数据落地后按变化重绘。 */
    private fun refreshData() {
        if (refreshing) return
        refreshing = true
        showRefreshLoading()
        if (library.isLoaded) com.hgtv.star.phone.PhoneIdentity.apply(context, library)
        val signedIn = runCatching { PhoneServices.repository(context).signedIn }.getOrDefault(false)
        if (!signedIn) {
            finishRefresh(null)
            return
        }
        loadScope.launch {
            val result = runCatching {
                com.hgtv.star.shared.data.AppRuntime.accountController(context).refresh()
            }
            post { finishRefresh(result.getOrNull()?.error) }
        }
    }

    private fun showRefreshLoading() {
        if (refreshOverlay != null) return
        val dots = LoadingDotsView(context).apply { label = "正在刷新账号数据…" }
        val overlay = FrameLayout(context).apply {
            isClickable = true
            contentDescription = "正在刷新账号数据"
            addView(dots, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
        refreshOverlay = overlay
        addView(overlay, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun finishRefresh(failure: Throwable?) {
        refreshOverlay?.let { overlay ->
            removeView(overlay)
        }
        refreshOverlay = null
        refreshing = false
        render()
        if (failure != null) {
            android.widget.Toast.makeText(
                context, "账号数据刷新失败，请稍后再试", android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun render(contentSnapshot: PhonePosterFeed.Snapshot? = null) {
        // 账号两栏在退出登录后消失：栏号越界时退回「历史」，否则整页会是空的。
        // 默认页也是历史（见字段 `tab`），这里保持同一个回退目标。
        if (tab >= tabs.size) tab = 0
        updateTopAccount()
        body.removeAllViews()
        body.addView(buildTabs())
        batchBar = buildBatchBar()
        batchBar?.let { body.addView(it) }
        feed.refreshHeader()
        if (contentSnapshot != null) feed.restore(contentSnapshot) else renderContent()
    }

    fun snapshotState(): State = State(
        tab = tab,
        historyFilter = historyFilter,
        reserveFilter = reserveFilter,
        feed = feed.snapshot(),
    )

    // ---------------------------------------------------------------- 账号

    private data class AccountState(
        val signedIn: Boolean,
        val nickname: String?,
        val displayId: String?,
    )

    private fun accountState(): AccountState {
        val cached = PhoneAccountSync.data ?: runCatching { library.accountCache() }.getOrNull()?.data
        val profile = cached?.profile
        val signedIn = runCatching { library.account().configured }.getOrDefault(false)
        return AccountState(signedIn, profile?.nickname, profile?.displayId)
    }

    private fun updateTopAccount() {
        val account = accountState()
        topRefresh.visibility = if (account.signedIn) View.VISIBLE else View.GONE
        topAvatar.text = if (account.signedIn) {
            account.nickname?.take(1)?.takeIf { it.isNotBlank() } ?: "红"
        } else {
            "红"
        }
        topName.text = when {
            !account.signedIn -> "未登录"
            !account.nickname.isNullOrBlank() -> account.nickname.orEmpty()
            else -> "已登录"
        }
        topSub.text = when {
            !account.signedIn -> "点这里登录 · 记录仅保存在本机"
            !account.displayId.isNullOrBlank() -> "平台号 ${account.displayId}"
            else -> "点这里查看账号"
        }
        topAccount.contentDescription = if (account.signedIn) {
            "账号${account.nickname.orEmpty()}"
        } else {
            "未登录，点击登录"
        }
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
    private fun filterOptions(): List<Pair<Boolean, String>> = when (tabs.getOrNull(tab)) {
            "历史" -> listOf(true to "已看完", false to "未看完")
            "预约" -> listOf(true to "已上线", false to "待上线")
            else -> emptyList()
        }

    private fun currentFilter(): Boolean? =
        if (tabs.getOrNull(tab) == "历史") historyFilter else reserveFilter

    private fun buildTabs(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 上 6 → 4：与账号行的下留白（4dp）一起把「账号栏 → 历史」压到 14dp，
            // 与顶栏头像到账号栏的间距保持接近。
            setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(2))
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
                setPadding(0, context.dp(6), context.dp(24), context.dp(4))
                setOnClickListener {
                    if (tab == index) return@setOnClickListener
                    exitBatchMode()
                    tab = index
                    render()
                }
            })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            post {
                val selected = row.getChildAt(tab) ?: return@post
                val edge = context.dp(16)
                when {
                    selected.left < scrollX -> smoothScrollTo(selected.left - edge, 0)
                    selected.right > scrollX + width ->
                        smoothScrollTo(selected.right - width + edge, 0)
                }
            }
        }
    }

    /**
     * 栏目标题下一行的筛选 / 批量操作栏。
     *
     * 普通态筛选按钮靠左、「批量操作」靠右；进入选择态后筛选按钮隐藏，
     * 同一行只保留全选 / 删除 / 取消。选择范围只属于当前栏目。
     */
    private fun buildBatchBar(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(context.dp(16), 0, context.dp(16), context.dp(2))
        }
        batchBar = row
        updateBatchBar()
        return row
    }

    private fun updateBatchBar() {
        val row = batchBar ?: return
        row.removeAllViews()
        row.gravity = Gravity.CENTER_VERTICAL
        fun action(label: String, color: Int, run: () -> Unit) = TextView(context).apply {
            text = label
            setTextColor(color)
            phoneSize(13)
            setPadding(context.dp(10), context.dp(6), context.dp(2), context.dp(6))
            setOnClickListener { run() }
        }
        if (batchMode) {
            val count = feed.selectedCount()
            val allSelected = feed.allSelected()
            row.addView(Space(context), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            row.addView(action(if (allSelected) "取消全选" else "全选", PhoneStyle.accent) {
                if (allSelected) feed.clearSelection() else feed.selectAll()
            })
            row.addView(action(
                "删除",
                if (count > 0) PhoneStyle.tips else PhoneStyle.textFaint,
            ) {
                if (count > 0) deleteSelected()
            })
            row.addView(action("取消", PhoneStyle.textDim) { exitBatchMode() })
        } else {
            val current = currentFilter()
            filterOptions().forEach { (value, label) ->
                val on = current == value
                row.addView(TextView(context).apply {
                    text = label
                    setTextColor(if (on) PhoneStyle.accent else PhoneStyle.textDim)
                    phoneSize(12)
                    setTypeface(
                        typeface,
                        if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
                    )
                    setPadding(0, context.dp(4), context.dp(18), context.dp(4))
                    setOnClickListener {
                        val next = if (on) null else value
                        if (tabs.getOrNull(tab) == "历史") historyFilter = next else reserveFilter = next
                        render()
                    }
                })
            }
            row.addView(Space(context), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            row.addView(action("批量操作", PhoneStyle.accent) { enterBatchMode() })
        }
        row.requestLayout()
        feed.refreshHeader()
    }

    private fun enterBatchMode() {
        if (batchMode) return
        batchMode = true
        feed.setBatchSelection(true)
        updateBatchBar()
    }

    private fun exitBatchMode() {
        if (!batchMode) return
        batchMode = false
        feed.setBatchSelection(false)
        updateBatchBar()
    }

    private fun deleteSelected() {
        val selected = feed.selectedItems()
        if (selected.isEmpty()) return
        val activity = context as? android.app.Activity ?: return
        PhoneDialog(activity, "批量删除")
            .message("确定删除选中的 ${selected.size} 项吗？")
            .button("取消")
            .button("删除", danger = true) { performBatchDelete(selected) }
            .show()
    }

    private fun performBatchDelete(selected: List<Series>) {
        val ids = selected.map { it.id }
        when (tabs.getOrNull(tab)) {
            "历史" -> {
                library.removeHistory(ids)
                PhoneAccountSync.historyDeleted(context, ids)
            }
            "收藏" -> {
                selected.forEach { series ->
                    if (runCatching { library.favorite(series.id) }.getOrDefault(false)) {
                        library.toggle(series)
                    }
                }
                PhoneAccountSync.favoritesDeleted(context, selected)
            }
            "稍后看" -> selected.forEach { series ->
                if (runCatching { library.queued(series.id) }.getOrDefault(false)) {
                    library.toggleLater(series)
                }
            }
            "点赞" -> PhoneAccountSync.likesDeleted(context, ids)
            "预约" -> {
                selected.forEach { library.setReserved(it.id, false) }
                PhoneAccountSync.reservationsDeleted(context, ids)
            }
        }
        android.widget.Toast.makeText(
            context,
            "已删除 ${selected.size} 项",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        exitBatchMode()
        render()
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
        val waitingForAccount = runCatching {
            PhoneServices.repository(context).signedIn && cached == null
        }.getOrDefault(false)
        feed.submit(rows, loading = waitingForAccount)
        updateBatchBar()
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
