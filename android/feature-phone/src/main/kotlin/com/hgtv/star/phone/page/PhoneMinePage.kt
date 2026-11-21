// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.page

import com.hgtv.star.shared.ui.UiText
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.hgtv.star.core.Series
import com.hgtv.star.core.isFinished
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
import com.hgtv.star.phone.ui.PhoneMainTabSwipeTarget
import com.hgtv.star.phone.ui.PhonePosterFeed
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneTabSwipeHost
import com.hgtv.star.phone.ui.PhoneTopBar
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.tabBarInset
import com.hgtv.star.shared.compat.clipToCircleCompat
import com.hgtv.star.shared.data.accountCache
import com.hgtv.star.shared.data.account
import com.hgtv.star.shared.update.AppUpdateController
import com.hgtv.star.shared.ui.LoadingDotsView
import com.hgtv.star.shared.ui.SoftStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
) : FrameLayout(context), PhoneMainTabSwipeTarget {

    data class State(
        val tab: Int,
        val historyFilter: Boolean?,
        val reserveFilter: Boolean?,
        val feed: PhonePosterFeed.Snapshot,
    )

    private val compactTopBar = context.resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val avatarSize = context.dp(32)
    private val leadingRowHeight = context.dp(if (compactTopBar) 56 else 76)
    private val topBar = PhoneTopBar(context)
    private val pageContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topAvatarImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToCircleCompat(this)
    }
    private val topAvatar = FrameLayout(context).apply {
        applySoft(SoftStyle.RAISED, PhoneStyle.surfaceHigh, avatarSize / 2f, 3f)
        addView(topAvatarImage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        contentDescription = UiText.get(com.hgtv.star.shared.R.string.ui_0232)
    }
    private val topName = TextView(context).apply {
        text = UiText.get(com.hgtv.star.shared.R.string.ui_0135)
        setTextColor(PhoneStyle.text)
        phoneSize(if (compactTopBar) 15 else 17)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 2
        ellipsize = null
    }
    private val topRefresh = LinearLayout(context).apply {
        val size = context.dp(24)
        gravity = Gravity.CENTER
        contentDescription = UiText.get(com.hgtv.star.shared.R.string.ui_0040)
        background = null
        addView(PhoneGlyph(context, PhoneGlyph.Kind.REFRESH).apply {
            tint = PhoneStyle.text
            contentDescription = UiText.get(com.hgtv.star.shared.R.string.ui_0040)
        }, LinearLayout.LayoutParams(context.dp(16), context.dp(16)))
        setOnClickListener { refreshData() }
    }
    private val topTextColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(10), 0, 0, 0)
        addView(topName, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }
    private val topAccount = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(topAvatar, LinearLayout.LayoutParams(
            avatarSize,
            avatarSize,
        ))
        addView(topTextColumn, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ))
        addView(topRefresh, LinearLayout.LayoutParams(
            context.dp(24),
            context.dp(24),
        ).apply { leftMargin = context.dp(8) })
        contentDescription = UiText.get(com.hgtv.star.shared.R.string.ui_0233)
        setOnClickListener { context.phoneNav().open(Route.Account) }
    }
    private val library = PhoneServices.library(context)
    /** 账号、历史/收藏等栏目作为列表第一项，和海报一起滚动，不自动隐藏。 */
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val feed = PhonePosterFeed(context)
    private val swipeHost = PhoneTabSwipeHost(context)
    override var canSwipeToMainTab: ((Int) -> Boolean)? = null
    override var onSwipeToMainTab: ((Int) -> Unit)? = null

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
    private var accountLoading = false
    private var accountLoadingColumns: Set<String> = emptySet()
    private var accountError: Throwable? = null
    private var accountRetry = 0
    private var accountToken = 0
    private var historyToken = 0
    private var accountRetryTask: Runnable? = null
    private var historyVisibleCount = HISTORY_PAGE_SIZE
    private var historyOffset = 0
    private var historyHasMore = true
    private var historyLoading = false
    private var historyEmptyPages = 0
    private var avatarUrl: String = ""
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
    /**
     * 这一页自己的协程：账号刷新与收藏检查都挂在它上面。
     *
     * 主界面会保留页面实例、切走时只 detach，回来时复用同一实例。因此不能在 detach 后
     * 永久留下一个已取消的 Scope，否则后续 `launch` 全是空操作，账号历史只剩本机那份。
     */
    private var loadScope = newScope()
    private var updateBadgeJob: Job? = null

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun ensureLoadScope() {
        if (loadScope.coroutineContext[Job]?.isActive != true) loadScope = newScope()
    }

    private val monitor by lazy {
        com.hgtv.star.shared.data.FavoriteMonitor(
            library,
            PhoneServices.repository(context),
        ) { post { if (tabs.getOrNull(tab) == "收藏") renderContent() } }
    }

    init {
        pageContent.orientation = LinearLayout.VERTICAL
        setBackgroundColor(PhoneStyle.background)

        applySystemBottomInset(0)
        // 历史与电视端一样把账号进度写在第二行；收藏则写「更新至 N 集」。
        feed.subtitleOf = { series ->
            subtitleFor(tab, series)
        }
        // 「我的」卡片只显示状态文字，不画播放进度条。
        feed.progressOf = null
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
        feed.onLoadMore = {
            if (tabs.getOrNull(tab) == "历史") loadHistory(reset = false)
        }

        // 栏目交给网格当第一项：它和海报共用同一个滚动容器，
        // 上滑时自然滚出屏幕，不再有独立页头的自动收起 / 展开。
        feed.setHeader(body)
        swipeHost.setContent(feed)
        swipeHost.canSwipe = { direction -> tab + direction in tabs.indices }
        swipeHost.onSwipe = { direction ->
            selectTab((tab + direction).coerceIn(tabs.indices))
        }
        swipeHost.onFocusDirection = { direction ->
            topBar.applyTabSelection((tab + direction).coerceIn(tabs.indices))
        }
        swipeHost.previewProvider = ::previewFor
        swipeHost.previewReleaser = { (it.tag as? PhonePosterFeed)?.release() }
        swipeHost.canHandOffSwipe = { direction ->
            onSwipeToMainTab != null && canSwipeToMainTab?.invoke(direction) == true
        }
        swipeHost.onHandOffSwipe = { direction -> onSwipeToMainTab?.invoke(direction) }
        swipeHost.refreshSwipeSetting()
        // 头像之外的上下留白缩到 20%，状态栏安全区由顶栏单独保留。
        topBar.setLeadingView(
            topAccount,
            maxOf(avatarSize, context.dp(40), avatarSize + (leadingRowHeight - avatarSize) / 5),
            fillWidth = true,
        )
        // 底部 2dp 留白收掉：账号区与栏目之间不再叠一层。
        topBar.tightenBottom()
        topBar.tightenLeadingTop()
        topBar.setAction(PhoneGlyph.Kind.SETTINGS, "设置") {
            PhoneSettingsActivity.open(context, PhoneSettingsPage.PLAYBACK)
        }
        // 顶栏固定，下面整块是列表（第一项就是栏目）。
        pageContent.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        pageContent.addView(swipeHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        addView(pageContent, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    internal fun applySystemBottomInset(@Suppress("UNUSED_PARAMETER") bottomInsetPx: Int) {
        feed.bottomInset = context.tabBarInset() + context.dp(8)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureLoadScope()
        updateBadgeJob?.cancel()
        updateBadgeJob = loadScope.launch {
            AppUpdateController.get(context).state.collect { state ->
                topBar.setActionBadge(state.hasUpdate)
            }
        }
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
        // 切到别的 tab 时页面会被复用，只停掉这一轮在途请求；重新挂载时由 ensureLoadScope 恢复。
        accountRetryTask?.let(::removeCallbacks)
        accountRetryTask = null
        historyToken++
        accountToken++
        historyLoading = false
        accountLoading = false
        accountLoadingColumns = emptySet()
        swipeHost.cancelSwipe()
        updateBadgeJob?.cancel()
        updateBadgeJob = null
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
        swipeHost.refreshSwipeSetting()
        exitBatchMode()
        val showHistory = consumeHistoryOnNextShow()
        if (showHistory) {
            tab = 0
            historyFilter = null
            resetHistoryPaging()
        }
        if (skipNextOnShownRefresh) {
            skipNextOnShownRefresh = false
            if (showHistory) {
                render()
                loadCurrentTab()
            }
            return
        }
        refresh()
    }

    private fun refresh() {
        ensureLoadScope()
        // 身份 / 凭据灌进仓库：从别的入口直接进「我的」时也要成立
        //（正常情况下主界面读完书库已经灌过一次，这里是幂等的兜底）。
        if (library.isLoaded) {
            com.hgtv.star.phone.PhoneIdentity.apply(context, library)
            runCatching {
                com.hgtv.star.shared.data.AppRuntime.accountController(context).ensureBoundCredentials()
            }
        }
        // 使用须知：**进这一页就交代一次账号风险**（与电视端同一条语义，见 `PhoneConsent`）。
        // 放在最前面：用户在这一页能点到「账号」「收藏」，先说清楚比事后补一句更有意义。
        (context as? android.app.Activity)?.let { activity ->
            if (library.isLoaded) PhoneConsent.ensureUsage(activity, library)
        }
        if (!library.isLoaded) {
            library.loadAsync(onFailure = { post { render() } }, onReady = { post { render() } })
            return
        }
        render()
        // 进这一页顺手检查一次收藏更新（有 1 小时门槛，不会反复请求）。
        runCatching { monitor.check() }
        loadCurrentTab()
    }

    /**
     * 与电视端 [com.hgtv.star.tv.loadAccount] 同一套同步顺序：
     * 缓存先铺、网络刷新、失败重试、数据落地后重绘。
     */
    private fun loadCurrentTab(force: Boolean = false, manual: Boolean = false) {
        val currentTab = tabs.getOrNull(tab) ?: "历史"
        if (currentTab == "历史") {
            accountToken++
            accountLoading = false
            accountLoadingColumns = emptySet()
        } else {
            historyToken++
            historyLoading = false
        }
        when (currentTab) {
            "历史" -> {
                loadHistory(reset = true, manual = manual, force = force)
                // 历史首屏不等待资料，但头像 / 昵称仍要在后台补齐。
                // 首次登录没有磁盘快照，因此这里必须主动取一次；有缓存时不重复请求。
                if (force || PhoneAccountSync.data?.profile == null) {
                    loadAccount(
                        force = force,
                        columns = setOf("资料"),
                        manual = false,
                    )
                }
            }
            "收藏" -> loadAccount(
                force = force,
                columns = setOf("收藏"),
                manual = manual,
            )
            "稍后看" -> if (manual) finishRefresh(null)
            "点赞" -> loadAccount(
                force = force,
                columns = setOf("点赞"),
                manual = manual,
            )
            "预约" -> loadAccount(
                force = force,
                columns = setOf("预约"),
                manual = manual,
            )
        }
    }

    private fun loadHistory(reset: Boolean, manual: Boolean = false, force: Boolean = false) {
        if (historyLoading && !force) return
        if (!reset && (!historyHasMore || historyVisibleCount <= 0)) return
        ensureLoadScope()
        if (reset) {
            historyVisibleCount = maxOf(HISTORY_PAGE_SIZE, library.history().size)
            historyOffset = 0
            historyHasMore = true
            historyEmptyPages = 0
        }
        historyLoading = true
        if (PhoneAccountSync.data?.history.isNullOrEmpty()) renderContent()
        val offset = if (reset) 0 else historyOffset
        val token = ++historyToken
        loadScope.launch {
            val result = PhoneAccountSync.historyPage(context, offset, HISTORY_PAGE_SIZE)
            if (token != historyToken || !isAttachedToWindow ||
                tabs.getOrNull(tab) != "历史"
            ) return@launch
            historyLoading = false
            accountError = result.error
            val page = result.page
            if (page != null) {
                val count = page.rows.size
                historyOffset = page.nextOffset
                historyHasMore = page.hasMore
                if (count == 0 && page.hasMore) {
                    if (historyEmptyPages < HISTORY_EMPTY_PAGE_LIMIT) {
                        historyEmptyPages++
                        historyLoading = false
                        loadHistory(reset = false, manual = manual)
                        return@launch
                    }
                    historyHasMore = false
                    accountError = IllegalStateException("历史记录分页未返回可显示内容")
                    renderContent()
                    if (manual) finishRefresh(accountError)
                    return@launch
                }
                historyEmptyPages = 0
                historyVisibleCount = if (reset) {
                    maxOf(HISTORY_PAGE_SIZE, library.history().size + count)
                } else {
                    historyVisibleCount + count
                }
                renderContent()
            }
            if (manual) finishRefresh(result.error)
        }
    }

    private fun loadAccount(
        force: Boolean = false,
        columns: Set<String> = com.hgtv.star.core.AccountSnapshot.KEYS,
        manual: Boolean = false,
    ) {
        if (!library.isLoaded) return
        if (accountLoading && !force && columns == accountLoadingColumns) return
        val controller = com.hgtv.star.shared.data.AppRuntime.accountController(context)
        controller.ensureBoundCredentials()
        accountLoading = true
        accountLoadingColumns = columns
        android.util.Log.i("HGTV-PHONE", "account refresh start columns=$columns")
        val requestedColumnEmpty = columns.any { column ->
            when (column) {
                "资料" -> controller.data?.profile == null
                "收藏" -> controller.data?.favorites.isNullOrEmpty()
                "历史" -> controller.data?.history.isNullOrEmpty()
                "点赞" -> controller.data?.likes.isNullOrEmpty()
                "预约" -> controller.data?.reserves.isNullOrEmpty()
                else -> false
            }
        }
        if (requestedColumnEmpty && columns != setOf("资料")) {
            renderContent()
        }
        val token = ++accountToken
        loadScope.launch {
            var failure: Throwable? = null
            try {
                val result = controller.refresh(columns)
                if (token != accountToken || !isAttachedToWindow) return@launch
                // 重绘前先结束加载态：收藏为空时若仍带着 true，会一直显示 loading，
                // 而 finally 清掉标志后没有第二次重绘。
                accountLoading = false
                accountLoadingColumns = emptySet()
                failure = result.error
                android.util.Log.i(
                    "HGTV-PHONE",
                    "account refresh done columns=$columns error=${failure?.message.orEmpty()} " +
                        "favorites=${controller.data?.favorites?.size ?: -1} " +
                        "profile=${controller.data?.profile != null}",
                )
                if (failure == null) {
                    accountError = null
                    accountRetry = 0
                    accountRetryTask?.let(::removeCallbacks)
                    accountRetryTask = null
                    if (columns == setOf("资料")) updateTopAccount() else render()
                } else {
                    accountError = failure
                    com.hgtv.star.shared.debug.DebugLog.record(
                        "账号",
                        "资料读取失败 · ${failure.message.orEmpty().take(140)}",
                    )
                    if (columns == setOf("资料")) updateTopAccount() else render()
                    if (requestedColumnEmpty && accountRetry < ACCOUNT_RETRIES) {
                        accountRetry++
                        accountRetryTask?.let(::removeCallbacks)
                        val task = Runnable {
                            accountRetryTask = null
                            if (isAttachedToWindow && accountError != null) {
                                loadAccount(columns = columns)
                            }
                        }
                        accountRetryTask = task
                        postDelayed(task, ACCOUNT_RETRY_MS)
                    }
                }
            } finally {
                if (token == accountToken) {
                    accountLoading = false
                    accountLoadingColumns = emptySet()
                }
                if (manual && token == accountToken) finishRefresh(failure)
            }
        }
    }

    /** 与 TV 一致：保留当前列表，居中显示等待，账号数据落地后重绘。 */
    private fun refreshData() {
        if (refreshing) return
        ensureLoadScope()
        refreshing = true
        showRefreshLoading()
        loadCurrentTab(force = true, manual = true)
    }

    private fun showRefreshLoading() {
        if (refreshOverlay != null) return
        val dots = LoadingDotsView(context).apply { label = UiText.get(com.hgtv.star.shared.R.string.ui_0326) }
        val overlay = FrameLayout(context).apply {
            isClickable = true
            contentDescription = UiText.get(com.hgtv.star.shared.R.string.ui_0146)
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
        feed.hideTags = tabs.getOrNull(tab) == "历史"
        body.removeAllViews()
        body.addView(buildTabs(tab, interactive = true))
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
    )

    private fun accountState(): AccountState {
        val cached = PhoneAccountSync.data ?: runCatching { library.accountCache() }.getOrNull()?.data
        val profile = cached?.profile
        val signedIn = runCatching { library.account().configured }.getOrDefault(false) ||
            PhoneServices.repository(context).signedIn
        return AccountState(signedIn, profile?.nickname)
    }

    private fun accountAvatarUrl(): String =
        (PhoneAccountSync.data ?: runCatching { library.accountCache() }.getOrNull()?.data)
            ?.profile?.avatar.orEmpty()

    private fun updateTopAccount() {
        val account = accountState()
        topRefresh.visibility = if (account.signedIn) View.VISIBLE else View.GONE
        val avatarUrl = if (account.signedIn) accountAvatarUrl() else ""
        if (avatarUrl != this.avatarUrl) {
            this.avatarUrl = avatarUrl
            if (avatarUrl.isBlank()) {
                topAvatarImage.setImageDrawable(null)
            } else {
                PhoneServices.artwork(context).load(
                    topAvatarImage,
                    avatarUrl,
                    widthPx = avatarSize,
                    heightPx = avatarSize,
                )
            }
        }
        topName.text = when {
            !account.signedIn -> "未登录"
            !account.nickname.isNullOrBlank() -> account.nickname.orEmpty()
            else -> "已登录"
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

    private fun tabLabel(key: String): String = when (key) {
        "历史" -> UiText.get(com.hgtv.star.shared.R.string.ui_0356)
        "收藏" -> UiText.get(com.hgtv.star.shared.R.string.ui_0357)
        "稍后看" -> UiText.get(com.hgtv.star.shared.R.string.ui_0358)
        "点赞" -> UiText.get(com.hgtv.star.shared.R.string.ui_0359)
        "预约" -> UiText.get(com.hgtv.star.shared.R.string.ui_0360)
        else -> key
    }

    /**
     * 筛选芯片（历史：已看完 / 未看完；预约：已上线 / 待上线）。
     *
     * 点已选中的那一枚 = 取消筛选回到「全部」，与 TV 的 `ToggleChip` 同一条语义：
     * 一个筛选只该有两种状态（选中 / 不选），不需要第三枚「全部」占地方。
     */
    private fun filterOptions(tabIndex: Int = tab): List<Pair<Boolean, String>> =
        when (tabs.getOrNull(tabIndex)) {
            "历史" -> listOf(true to UiText.get(com.hgtv.star.shared.R.string.ui_0310), false to UiText.get(com.hgtv.star.shared.R.string.ui_0323))
            "预约" -> listOf(true to UiText.get(com.hgtv.star.shared.R.string.ui_0309), false to UiText.get(com.hgtv.star.shared.R.string.ui_0316))
            else -> emptyList()
        }

    private fun currentFilter(tabIndex: Int = tab): Boolean? =
        if (tabs.getOrNull(tabIndex) == "历史") historyFilter else reserveFilter

    private fun selectTab(index: Int) {
        if (index == tab || index !in tabs.indices) return
        exitBatchMode()
        tab = index
        render()
        loadCurrentTab()
    }

    private fun buildTabs(selected: Int, interactive: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 账号行与栏目之间的上下留白同样缩到 20%。
            setPadding(context.dp(16), context.dp(4) / 5, context.dp(16), context.dp(2) / 5)
        }
        tabs.forEachIndexed { index, label ->
            row.addView(TextView(context).apply {
                text = tabLabel(label)
                phoneSize(16)
                setTextColor(if (index == selected) PhoneStyle.text else PhoneStyle.textFaint)
                setTypeface(
                    typeface,
                    if (index == selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                setPadding(0, context.dp(6), context.dp(24), context.dp(4))
                if (interactive) {
                    setOnClickListener { selectTab(index) }
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
                val selectedView = row.getChildAt(selected) ?: return@post
                val edge = context.dp(16)
                when {
                    selectedView.left < scrollX -> smoothScrollTo(selectedView.left - edge, 0)
                    selectedView.right > scrollX + width ->
                        smoothScrollTo(selectedView.right - width + edge, 0)
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
        PhoneDialog(activity, UiText.get(com.hgtv.star.shared.R.string.ui_0108))
            .message(UiText.get(com.hgtv.star.shared.R.string.ui_0197, selected.size))
            .button(UiText.get(com.hgtv.star.shared.R.string.ui_0046))
            .button(UiText.get(com.hgtv.star.shared.R.string.ui_0039), danger = true) { performBatchDelete(selected) }
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

    private fun previewFor(direction: Int): View? {
        val target = tab + direction
        if (target !in tabs.indices) return null
        val preview = PhonePosterFeed(context).apply {
            applyColumnsFromSettings()
            bottomInset = context.tabBarInset() + context.dp(8)
            subtitleOf = { series -> subtitleFor(target, series) }
            progressOf = null
            hideTags = tabs.getOrNull(target) == "历史"
        }
        preview.setHeader(buildPreviewHeader(target))
        val rows = rowsFor(target)
        preview.submit(rows, loading = waitingForAccount(target, rows))
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PhoneStyle.background)
            tag = preview
            addView(preview, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }
    }

    private fun buildPreviewHeader(target: Int): View {
        val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        header.addView(buildTabs(target, interactive = false))
        buildPreviewFilterRow(target)?.let(header::addView)
        return header
    }

    private fun buildPreviewFilterRow(target: Int): View? {
        val options = filterOptions(target)
        if (options.isEmpty()) return null
        val selected = currentFilter(target)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), 0, context.dp(16), context.dp(2))
            options.forEach { (value, label) ->
                addView(TextView(context).apply {
                    text = label
                    phoneSize(12)
                    setTextColor(if (selected == value) PhoneStyle.accent else PhoneStyle.textDim)
                    setTypeface(
                        typeface,
                        if (selected == value) android.graphics.Typeface.BOLD
                        else android.graphics.Typeface.NORMAL,
                    )
                    setPadding(0, context.dp(4), context.dp(18), context.dp(4))
                })
            }
        }
    }

    /**
     * 内容网格。
     *
     * 历史与电视端「我的 → 历史」一致，只读账号侧；收藏仍合并本机与账号，
     * 避免手机端已收藏但账号侧尚未回包的条目短暂消失。
     *
     * 账号侧历史要滤掉「本机删过」的（[com.hgtv.star.shared.data.Library.historyDeleted]）：
     * 平台对短剧进度列表的删除是软删，不滤就会「删了过一会儿又回来」。
     */
    private fun renderContent() {
        val rows = rowsFor(tab)
        val currentTab = tabs.getOrNull(tab) ?: "历史"
        feed.submit(
            rows,
            more = currentTab == "历史" && historyHasMore,
            loading = waitingForAccount(tab, rows),
        )
        updateBatchBar()
    }

    private fun rowsFor(tabIndex: Int): List<Series> {
        val cached = PhoneAccountSync.data ?: runCatching { library.accountCache() }.getOrNull()?.data
        return when (tabs.getOrNull(tabIndex) ?: "历史") {
            "历史" -> com.hgtv.star.shared.account.AccountMerge.history(
                library.history(),
                cached?.history.orEmpty(),
            )
                .filterNot { library.historyDeleted(it.series.id) }
                .filter { row -> historyFilter?.let { want -> historyFinished(row) == want } ?: true }
                .take(historyVisibleCount)
                .map { row ->
                    row.series.copy(
                        badge = if (row.finished) "看完了" else
                            "第 ${row.episodeIndex + 1} 集" +
                                if (row.position > 0) " · ${clock(row.position)}" else "",
                    )
                }
            "收藏" -> com.hgtv.star.shared.account.AccountMerge.favorites(
                runCatching { library.favorites() }.getOrDefault(emptyList()),
                cached?.favorites.orEmpty(),
            )
            "稍后看" -> runCatching { library.later() }.getOrDefault(emptyList())
            "点赞" -> cached?.likes?.map { it.series }.orEmpty()
            else -> cached?.reserves
                ?.filter { row -> reserveFilter?.let { want -> row.online == want } ?: true }
                ?.map { it.series }.orEmpty()
        }
    }

    private fun waitingForAccount(tabIndex: Int, rows: List<Series>): Boolean {
        if (rows.isNotEmpty()) return false
        return when (tabs.getOrNull(tabIndex) ?: "历史") {
            "历史" -> historyLoading || PhoneAccountSync.data?.history == null
            "稍后看" -> false
            else -> accountLoading || PhoneAccountSync.data == null
        }
    }

    private fun subtitleFor(tabIndex: Int, series: Series): String? =
        when (tabs.getOrNull(tabIndex)) {
            "历史" -> series.badge.takeIf { it.isNotBlank() }
            "收藏" -> library.favoriteLabel(series.id)
            else -> null
        }

    /** 本机同剧进度或平台记录表明当前季已经看完。 */
    private fun historyFinished(row: com.hgtv.star.core.AccountHistory): Boolean {
        val local = runCatching { library.progress(row.series.id) }.getOrNull()
        val total = row.episodes.takeIf { it > 0 } ?: row.series.episodeCount
        return local?.isFinished(total) == true || row.isFinished()
    }

    private fun historyFinished(row: com.hgtv.star.shared.account.MergedHistory): Boolean =
        row.finished

    /** 位置读数：与电视端 `formatTime` 同一份（`:core` 的 `PlaybackInfoLine.time`）。 */
    private fun clock(ms: Long): String = com.hgtv.star.core.PlaybackInfoLine.time(ms)

    private fun signedIn(): Boolean = runCatching {
        library.account().configured || PhoneServices.repository(context).signedIn
    }.getOrDefault(false)

    fun scrollToTop() {
        feed.scrollToTop()
    }

    private fun resetHistoryPaging() {
        historyToken++
        historyLoading = false
        historyVisibleCount = maxOf(HISTORY_PAGE_SIZE, library.history().size)
        historyOffset = 0
        historyHasMore = true
        historyEmptyPages = 0
    }

    companion object {
        private var showHistoryOnNextShow = false

        internal fun requestHistoryOnNextShow() {
            showHistoryOnNextShow = true
        }

        private fun consumeHistoryOnNextShow(): Boolean {
            val requested = showHistoryOnNextShow
            showHistoryOnNextShow = false
            return requested
        }

        private const val HISTORY_PAGE_SIZE = 20
        private const val HISTORY_EMPTY_PAGE_LIMIT = 3
        private const val ACCOUNT_RETRY_MS = com.hgtv.star.core.AppConfig.Ui.ACCOUNT_RETRY_MS
        private const val ACCOUNT_RETRIES = com.hgtv.star.core.AppConfig.Ui.ACCOUNT_RETRIES
    }
}
