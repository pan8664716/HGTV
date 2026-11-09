// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feature.search

import com.hgtv.star.core.CatalogPage
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.SearchDiag
import com.hgtv.star.core.SearchSource
import com.hgtv.star.core.SearchSuggestion
import com.hgtv.star.shared.data.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页的行为层。
 *
 * 统一搜索源、历史、热搜、联想词去抖与结果分页。页面只保留输入法、焦点、
 * 结果列表和提示区渲染。
 */
class SearchController(
    private val repository: ContentRepository,
    private val library: Library,
    private val scope: CoroutineScope,
) {
    data class State(
        val query: String = "",
        val source: SearchSource = SearchSource.APP,
        val history: List<String> = emptyList(),
        val hot: List<String> = emptyList(),
        val suggestions: List<SearchSuggestion> = emptyList(),
        val hotLoading: Boolean = false,
        val suggestionsLoading: Boolean = false,
        val degraded: Boolean = false,
        val error: Throwable? = null,
    )

    private val _state = MutableStateFlow(State(source = library.searchSource))
    val state: StateFlow<State> = _state.asStateFlow()

    private var hotJob: Job? = null
    private var suggestJob: Job? = null
    private var hotLoadedFor: SearchSource? = null

    init {
        refreshHistory()
    }

    fun refreshHistory() {
        _state.update { it.copy(history = library.searches().map { row -> row.query }) }
    }

    fun clearHistory() {
        library.clearSearches()
        _state.update { it.copy(history = emptyList()) }
    }

    fun remember(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        library.rememberSearch(clean)
        refreshHistory()
    }

    fun setSource(source: SearchSource) {
        if (_state.value.source == source) return
        library.searchSource = source
        hotJob?.cancel()
        hotJob = null
        hotLoadedFor = null
        _state.update {
            it.copy(source = source, hot = emptyList(), suggestions = emptyList(), error = null)
        }
    }

    fun loadHot(limit: Int = 12, force: Boolean = false) {
        val source = _state.value.source
        if (!force && hotLoadedFor == source) return
        if (hotJob?.isActive == true) return
        hotLoadedFor = source
        _state.update { it.copy(hotLoading = true, error = null) }
        hotJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.hotSearches(limit, source) }
            }
            _state.update {
                it.copy(
                    hot = result.getOrDefault(emptyList()),
                    hotLoading = false,
                    error = result.exceptionOrNull(),
                )
            }
        }
    }

    /**
     * 输入变化后的联想词请求。空白输入会取消在途请求并切回历史 / 热搜。
     */
    fun scheduleSuggestions(
        text: String,
        count: Int = 10,
        debounceMs: Long = 220L,
    ) {
        suggestJob?.cancel()
        val clean = text.trim()
        _state.update { it.copy(query = clean) }
        if (clean.isEmpty()) {
            _state.update { it.copy(suggestions = emptyList(), suggestionsLoading = false) }
            if (_state.value.history.isEmpty()) loadHot()
            return
        }
        suggestJob = scope.launch {
            _state.update { it.copy(suggestionsLoading = true, error = null) }
            delay(debounceMs)
            val source = _state.value.source
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.suggestions(clean, count, source) }
            }
            if (_state.value.query != clean) return@launch
            _state.update {
                it.copy(
                    suggestions = result.getOrDefault(emptyList()),
                    suggestionsLoading = false,
                    error = result.exceptionOrNull(),
                )
            }
        }
    }

    /**
     * 取一页搜索结果。页面滚动 / 加载更多只调用这里。
     */
    suspend fun page(query: String, page: Int, source: SearchSource? = null): CatalogPage {
        val clean = query.trim()
        require(clean.isNotEmpty()) { "搜索词不能为空" }
        val use = source ?: _state.value.source
        val result = withContext(Dispatchers.IO) { repository.search(clean, page, use) }
        _state.update {
            it.copy(
                query = clean,
                source = use,
                degraded = SearchDiag.last?.degraded == true,
                error = null,
            )
        }
        return result
    }

    /** 兼容仍在线程池里同步取页的页面；调用方必须已在后台线程。 */
    fun pageBlocking(query: String, page: Int, source: SearchSource? = null): CatalogPage {
        val clean = query.trim()
        require(clean.isNotEmpty()) { "搜索词不能为空" }
        val use = source ?: _state.value.source
        val result = repository.search(clean, page, use)
        _state.update {
            it.copy(
                query = clean,
                source = use,
                degraded = SearchDiag.last?.degraded == true,
                error = null,
            )
        }
        return result
    }

    fun cancelSuggestions() {
        suggestJob?.cancel()
        suggestJob = null
        _state.update { it.copy(suggestionsLoading = false) }
    }

    fun clear() {
        hotJob?.cancel()
        suggestJob?.cancel()
        hotJob = null
        suggestJob = null
        hotLoadedFor = null
        _state.value = State(source = library.searchSource)
        refreshHistory()
    }
}
