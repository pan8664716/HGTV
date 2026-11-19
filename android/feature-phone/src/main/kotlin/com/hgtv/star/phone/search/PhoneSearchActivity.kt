// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hgtv.star.core.SearchSource
import com.hgtv.star.core.SearchSuggestion
import com.hgtv.star.phone.ui.PhoneChipRow
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhoneCardMenu
import com.hgtv.star.phone.ui.PhonePosterFeed
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneSoft.applyWell
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.applyPhoneNavigationBar
import com.hgtv.star.phone.ui.bindPhoneInsets
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneBottomInsetPx
import com.hgtv.star.phone.ui.phoneEdgeToEdge
import com.hgtv.star.phone.ui.phoneNavigationBarUsesLightIcons
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.shared.feature.search.SearchController
import com.hgtv.star.shared.ui.SoftStyle
import com.hgtv.star.shared.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：输入框 + 提示区 + 两列结果。
 *
 * ## 提示区（与 TV 端同一套语义）
 *
 * | 输入框 | 提示区内容 |
 * | --- | --- |
 * | 空 | 本机搜索记录在前，热门搜索在后 |
 * | 有字 | 服务端联想词（边打字边发，去抖 220ms） |
 * | 已经在看结果 | 整块收起，把屏幕让给结果 |
 *
 * 与 TV 的差别只有版式：电视是输入框下面**一行**横向芯片，手机是**换行铺开**的芯片
 * （一行放不下要横着蹭，用户看不到还有多少），联想词是竖排列表（手指从上往下扫比横着划准）。
 *
 * 点任意一枚 = 用这个词搜索，不直接跳详情：那一区叫「搜索建议」，用户点它想搜这个词，
 * 不是想跳过结果页（与 TV 的 `runSearch` 同一条规则）。
 *
 * ## 数据都走已有的那一份
 *
 * 搜索记录读写 `Library.searches()/rememberSearch()/clearSearches()`，
 * 联想词与热搜词走 `ContentRepository.suggestions()/hotSearches()`，
 * 搜索源跟设置页的 `searchSource`。这些都与 TV 端**同一份落盘值、同一个接口**，没有第二套实现。
 */
class PhoneSearchActivity : PhoneActivity() {

    private lateinit var root: FrameLayout
    private lateinit var input: EditText
    private lateinit var feed: PhonePosterFeed
    private lateinit var hintPanel: ScrollView
    private lateinit var hintColumn: LinearLayout
    private lateinit var historyHeader: LinearLayout
    private lateinit var historyChips: PhoneChipRow
    private lateinit var hotHeader: TextView
    private lateinit var hotChips: PhoneChipRow
    private lateinit var suggestHeader: TextView
    private lateinit var suggestList: LinearLayout

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val searchController by lazy {
        SearchController(
            repository = PhoneServices.repository(this),
            library = PhoneServices.library(this),
            scope = scope,
        )
    }
    private var page = 1
    private var keyword = ""
    /** 已经搜过至少一次：这时提示区收起，结果占满屏幕。 */
    private var showingResults = false
    /** 程序化改输入框内容（点芯片、回填关键词）时不要触发联想请求。 */
    private var suppressWatch = false
    private val source: SearchSource
        get() = runCatching {
            PhoneServices.library(this).takeIf { it.isLoaded }?.searchSource
        }.getOrNull() ?: SearchSource.APP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneStyle.ensure(this)
        // 状态栏透明：否则它会用自己的底色盖在页面顶部，与石墨底拼成两条颜色。
        phoneEdgeToEdge()
        applyPhoneNavigationBar(phoneNavigationBarUsesLightIcons())
        buildUi()
        scope.launch {
            searchController.state.collect { state ->
                if (showingResults) return@collect
                val typed = input.text?.toString()?.trim().orEmpty()
                if (typed.isEmpty()) refreshHints() else renderSuggestions(state.suggestions)
            }
        }
        refreshHints()
    }

    // ---------------------------------------------------------------- 界面

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(PhoneStyle.background) }

        val status = statusBarHeight()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), status + dp(6), dp(12), dp(8))
        }

        input = EditText(this).apply {
            hint = "搜剧名、演员、题材"
            setHintTextColor(PhoneStyle.textFaint)
            setTextColor(PhoneStyle.text)
            phoneSize(14)
            setSingleLine()
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), 0, dp(14), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (suppressWatch) return
                    // 用户改了词：先回到提示区，结果不再占着屏幕（否则「搜过」之后
                    // 再敲字会被旧结果挡住，看不到联想词）。
                    showingResults = false
                    scheduleSuggestions(s?.toString().orEmpty())
                }
            })
        }
        // 新拟态凹陷槽：输入框是「按进去」的那一档
        val box = FrameLayout(this).apply {
            applyWell(PhoneStyle.well, 22f, 4f)
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { leftMargin = dp(6); rightMargin = dp(6) })
        }
        bar.addView(box, LinearLayout.LayoutParams(0, dp(34), 1f))

        bar.addView(TextView(this).apply {
            text = "搜索"
            setTextColor(PhoneStyle.accent)
            phoneSize(16)
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setOnClickListener { search() }
        })
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        buildHints()
        root.addView(hintPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { topMargin = status + dp(58) })

        feed = PhonePosterFeed(this).apply {
            // 搜索结果也是海报栅格：列数跟着设置里的「列表每行」，与分类 / 榜单一致。
            applyColumnsFromSettings()
            bottomInset = phoneBottomInsetPx() + dp(8)
            visibility = View.GONE
            // 点结果先进详情页（与 TV 一致）。
            onOpen = { series ->
                phoneNav().open(Route.Detail(series.id, series.title))
            }
            // 长按：搜索结果同样是「一部剧」，菜单与分类页一致（继续观看 / 收藏 / 稍后看 / 详情）。
            // 收藏 / 稍后看只重绑可见项，不重跑搜索。
            onLongPress = { series ->
                PhoneCardMenu.show(this, series, onItemChanged = { refreshItems() })
            }
        }
        root.addView(feed, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { topMargin = status + dp(58) })

        setContentView(root)
        root.bindPhoneInsets { bottom -> applyBottomInset(bottom) }
        input.requestFocus()
    }

    private fun applyBottomInset(bottomInsetPx: Int) {
        feed.bottomInset = bottomInsetPx + dp(8)
        hintColumn.setPadding(dp(16), dp(6), dp(16), bottomInsetPx + dp(12))
    }

    private fun buildHints() {
        hintColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), phoneBottomInsetPx() + dp(12))
        }

        // —— 最近搜索 ——
        historyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        historyHeader.addView(TextView(this).apply {
            text = "最近搜索"
            setTextColor(PhoneStyle.textDim)
            phoneSize(13)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        historyHeader.addView(TextView(this).apply {
            text = "清空"
            setTextColor(PhoneStyle.textFaint)
            phoneSize(12)
            setPadding(dp(10), dp(6), dp(2), dp(6))
            setOnClickListener {
                searchController.clearHistory()
                refreshHints()
            }
        })
        hintColumn.addView(historyHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(10) })

        historyChips = PhoneChipRow(this)
        hintColumn.addView(historyChips, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // —— 热门搜索（本机还没有记录时铺这一块）——
        hotHeader = TextView(this).apply {
            text = "热门搜索"
            setTextColor(PhoneStyle.textDim)
            phoneSize(13)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        hintColumn.addView(hotHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(10) })

        hotChips = PhoneChipRow(this)
        hintColumn.addView(hotChips, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // —— 联想词 ——
        suggestHeader = TextView(this).apply {
            text = "猜你想搜"
            setTextColor(PhoneStyle.textDim)
            phoneSize(13)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        hintColumn.addView(suggestHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(6) })

        suggestList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hintColumn.addView(suggestList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        hintPanel = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(hintColumn, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    // ---------------------------------------------------------------- 提示区

    /**
     * 按「输入框是否为空」重铺提示区。
     *
     * 空 → 本机搜索记录（没有记录时铺热门搜索）；
     * 有字 → 留给联想词（由 [scheduleSuggestions] 填），历史与热搜一起收起来。
     */
    private fun refreshHints() {
        if (showingResults) return
        hintPanel.visibility = View.VISIBLE
        feed.visibility = View.GONE

        val typed = input.text?.toString()?.trim().orEmpty()
        val state = searchController.state.value
        val history = state.history

        val showHistory = typed.isEmpty() && history.isNotEmpty()
        historyHeader.visibility = if (showHistory) View.VISIBLE else View.GONE
        historyChips.visibility = if (showHistory) View.VISIBLE else View.GONE
        if (showHistory) {
            historyChips.setChips(history) { word -> runSearch(word) }
        }

        // 空输入时历史与热门一起出现：历史在前，系统推荐在后。
        val showHot = typed.isEmpty()
        val hot = state.hot
        hotHeader.visibility = if (showHot && (hot.isNotEmpty() || state.hotLoading)) View.VISIBLE else View.GONE
        hotChips.visibility = if (showHot && hot.isNotEmpty()) View.VISIBLE else View.GONE
        if (showHot) loadHotSearches()

        val showSuggest = typed.isNotEmpty()
        suggestHeader.visibility = if (showSuggest) View.VISIBLE else View.GONE
        suggestList.visibility = if (showSuggest) View.VISIBLE else View.GONE
    }

    /** 取一次热门搜索（走 [com.hgtv.star.core.ContentRepository.hotSearches]，与 TV 的探针同一个接口）。 */
    private fun loadHotSearches() {
        searchController.loadHot()
    }

    /**
     * 边打字边取联想词（去抖 220ms，与 TV 的 `SUGGEST_DEBOUNCE` 同值）。
     *
     * 清空输入立刻换回历史 / 热搜，不发请求。回包先比对「此刻输入框里的词」，
     * 晚回来的旧结果直接丢掉——否则用户已经删到一半，列表还在跳上一批词。
     */
    private fun scheduleSuggestions(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            suggestList.removeAllViews()
            refreshHints()
            return
        }
        if (!showingResults) {
            hintPanel.visibility = View.VISIBLE
            feed.visibility = View.GONE
            historyHeader.visibility = View.GONE
            historyChips.visibility = View.GONE
            hotHeader.visibility = View.GONE
            hotChips.visibility = View.GONE
            suggestHeader.visibility = View.VISIBLE
            suggestList.visibility = View.VISIBLE
        }
        searchController.scheduleSuggestions(clean)
    }

    private fun renderSuggestions(rows: List<SearchSuggestion>) {
        suggestList.removeAllViews()
        rows.forEach { row ->
            suggestList.addView(suggestionRow(row))
        }
        suggestHeader.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        suggestList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * 一条联想词。
     *
     * 左边放大镜、中间词、右边一行灰字说明这一条是什么（剧名 / 题材 / 常见搜索）。
     * 说明文字只是提示，不影响点击行为：点哪一条都是用那个词去搜。
     */
    private fun suggestionRow(row: SearchSuggestion): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(11), dp(6), dp(11))
            addView(PhoneGlyph(this@PhoneSearchActivity, PhoneGlyph.Kind.SEARCH).apply {
                tint = PhoneStyle.textFaint
            }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(10) })
            addView(TextView(this@PhoneSearchActivity).apply {
                text = row.name
                setTextColor(PhoneStyle.text)
                phoneSize(14)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val hint = wordTypeLabel(row.wordType)
            if (hint.isNotEmpty()) {
                addView(TextView(this@PhoneSearchActivity).apply {
                    text = hint
                    setTextColor(PhoneStyle.textFaint)
                    phoneSize(11)
                    setPadding(dp(8), 0, dp(2), 0)
                })
            }
            setOnClickListener { runSearch(row.name) }
        }

    /** 服务端的 `word_type` → 手机上那一行灰字。认不出来的不显示。 */
    private fun wordTypeLabel(wordType: String): String = when (wordType) {
        "short_play_name", "title", "album" -> "剧名"
        "category" -> "题材"
        "common_query" -> "大家在搜"
        else -> ""
    }

    // ---------------------------------------------------------------- 搜索

    /** 点「搜索」按钮 / 键盘确认。 */
    private fun search() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        runSearch(text)
    }

    /**
     * 用这个词搜一次。
     *
     * 顺手记进本机搜索记录（与 TV 的 `runSearch` 同一个入口 `library.rememberSearch`）：
     * 不记的话「最近搜索」永远是空的，那个区就成了摆设。
     */
    private fun runSearch(word: String) {
        val clean = word.trim()
        if (clean.isEmpty()) return
        keyword = clean
        page = 1
        // 回填关键词要在置位**之前**、并且屏蔽监听：否则 afterTextChanged 会把
        // `showingResults` 重新打回 false，提示区又盖住刚拿到的结果，还多发一次联想请求。
        if (input.text?.toString() != clean) {
            suppressWatch = true
            input.setText(clean)
            input.setSelection(clean.length)
            suppressWatch = false
        }
        showingResults = true
        input.clearFocus()
        // 收键盘：结果要占满屏幕，键盘挡着看不见。
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
        }
        searchController.remember(clean)

        hintPanel.visibility = View.GONE
        feed.visibility = View.VISIBLE
        // 搜索源（设置 → 内容管理与来源）：App 接口 / 官网接口。
        // 与 TV 的 `library.searchSource` 同一份落盘值，只影响搜索（分类 / 榜单 / 播放不走它）。
        feed.load { p, _ ->
            val result = searchController.page(keyword, p, source)
            // 这一次是不是「App 接口没数据、自动改走官网」（见 `ContentRepository.search`）。
            // 只提示第 1 页，翻页时不重复打扰。
            if (p == 1 && com.hgtv.star.core.SearchDiag.last?.degraded == true) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@PhoneSearchActivity,
                        "App 搜索暂不可用，已改用官网搜索",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            result
        }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
