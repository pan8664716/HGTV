// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feature.detail

import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.Detail
import com.hgtv.star.core.DetailCache
import com.hgtv.star.core.RelatedWorks
import com.hgtv.star.core.ResumePlayback
import com.hgtv.star.core.ResumeTarget
import com.hgtv.star.core.Series
import com.hgtv.star.core.authorsOf
import com.hgtv.star.core.detail
import com.hgtv.star.core.related
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.account.AccountMerge
import com.hgtv.star.shared.feature.account.AccountController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情页的行为层。
 *
 * 负责详情缓存优先、网络合并、作者补齐、相关剧集、作者作品两段交付和续播目标。
 * 不持有 Activity / View；页面订阅 [state] 后只负责渲染和上报用户动作。
 */
class DetailController(
    private val repository: ContentRepository,
    private val library: Library,
    private val scope: CoroutineScope,
    private val account: AccountController? = null,
) {
    data class State(
        val seriesId: String = "",
        val detail: Detail? = null,
        val loading: Boolean = false,
        val error: Throwable? = null,
        val authorLoading: Boolean = false,
        val related: RelatedWorks? = null,
        val relatedLoading: Boolean = false,
        val relatedError: Throwable? = null,
        val relatedFor: String = "",
        val authorFor: String = "",
        val authorWorks: List<Series>? = null,
        val authorWorksLoading: Boolean = false,
        val authorWorksError: Throwable? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var authorJob: Job? = null
    private var relatedJob: Job? = null
    private var authorWorksJob: Job? = null
    private var loadToken = 0
    private var authorToken = 0
    private var relatedToken = 0
    private var authorWorksToken = 0

    /**
     * 打开详情。
     *
     * [onCached] / [onLoaded] 都在主线程回调；[onLoaded] 的 Boolean 表示主体内容是否变化。
     * 网络失败时保留已经上屏的缓存，只通过 [onError] 通知页面。
     */
    fun load(
        series: Series,
        onCached: (Detail) -> Unit = {},
        onLoaded: (Detail, ResumeTarget, Boolean) -> Unit = { _, _, _ -> },
        onError: (Throwable) -> Unit = {},
        loadDetail: (() -> Detail)? = null,
    ) {
        val token = ++loadToken
        loadJob?.cancel()
        authorJob?.cancel()
        relatedJob?.cancel()
        authorWorksJob?.cancel()
        _state.value = State(seriesId = series.id, loading = true)

        loadJob = scope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching { DetailCache.load(series.id) }.getOrNull()
            }
            if (token != loadToken) return@launch
            if (cached != null) {
                _state.update { it.copy(detail = cached) }
                onCached(cached)
                prefetchRelated(cached.series.id, cached.series.title)
                if (cached.series.author.isNotBlank()) {
                    prefetchAuthorWorks(cached.series)
                } else {
                    loadAuthor(cached)
                }
            }

            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    loadDetail?.invoke() ?: repository.detail(series.id, includeAuthor = false)
                }
            }
            if (token != loadToken) return@launch
            val loaded = fetched.getOrElse { problem ->
                _state.update { it.copy(loading = false, error = problem) }
                onError(problem)
                return@launch
            }

            val knownAuthor = _state.value.detail?.series?.author?.trim().orEmpty()
            val notice = series.notice.takeIf { it.contains("上线") } ?: loaded.series.notice
            val withNotice = if (loaded.upcoming && notice != loaded.series.notice) {
                loaded.copy(series = loaded.series.copy(notice = notice))
            } else {
                loaded
            }
            val result = if (knownAuthor.isBlank()) withNotice
            else withNotice.copy(series = withNotice.series.copy(author = knownAuthor))

            val previous = _state.value.detail
            val changed = previous == null || !sameContent(previous, result)
            _state.update { it.copy(detail = result, loading = false, error = null) }
            if (knownAuthor.isNotBlank()) runCatching { DetailCache.save(result) }
            if (result.episodes.isNotEmpty()) {
                library.observeFavorite(
                    series.id,
                    result.episodes.size,
                    System.currentTimeMillis(),
                    acknowledge = true,
                )
            }
            if (result.upcoming && !library.reservedKnown(series.id)) {
                library.setReserved(series.id, result.reserved)
            }

            val target = resumeTarget(series.id, result.episodes)
            onLoaded(result, target, changed)
            prefetchRelated(result.series.id, result.series.title)
            if (result.series.author.isNotBlank()) prefetchAuthorWorks(result.series)
            else loadAuthor(result)
        }
    }

    /** 续播目标：本机与账号历史按“集号 → 集内位置 → 更新时间”合并后取胜出记录。 */
    fun resumeTarget(seriesId: String, episodes: List<String>): ResumeTarget {
        if (episodes.isEmpty()) return ResumeTarget(0, 0)
        if (library.historyDeleted(seriesId)) return ResumeTarget(0, 0)
        val merged = AccountMerge.historyForSeries(
            library.history(),
            account?.data?.history.orEmpty(),
            seriesId,
        ) ?: return ResumeTarget(0, 0)
        return ResumePlayback.target(
            episodes = episodes,
            episodeId = merged.episodeId,
            position = merged.position,
            completed = false,
            fallbackIndex = merged.episodeIndex,
        )
    }

    /** 作者只是附加信息：主体详情先上屏，再后台补 `multi-detail`。 */
    fun loadAuthor(current: Detail) {
        val id = current.series.id
        if (current.series.author.isNotBlank()) {
            prefetchAuthorWorks(current.series)
            return
        }
        if (_state.value.authorLoading) return
        val token = ++authorToken
        _state.update { it.copy(authorLoading = true) }
        authorJob?.cancel()
        authorJob = scope.launch {
            try {
                val author = withContext(Dispatchers.IO) {
                    runCatching { repository.authorsOf(listOf(id))[id].orEmpty() }.getOrDefault("")
                }
                if (token != authorToken) return@launch
                _state.update { it.copy(authorLoading = false) }
                if (author.isBlank()) return@launch
                val detail = _state.value.detail?.takeIf { it.series.id == id } ?: return@launch
                val updated = detail.copy(series = detail.series.copy(author = author))
                _state.update { it.copy(detail = updated) }
                runCatching { DetailCache.save(updated) }
                prefetchAuthorWorks(updated.series)
            } finally {
                // 协程被取消（换剧 / 换页）时上面的收尾一行都跑不到：把「加载中」放回去，
                // 否则那一栏永远停在「正在查找…」——用户读到的就是「一直没有结果」。
                if (token == authorToken && _state.value.authorLoading) {
                    _state.update { it.copy(authorLoading = false) }
                }
            }
        }
    }

    fun ensureRelated(id: String, title: String) {
        val current = _state.value
        if (current.related != null) return
        if (current.relatedLoading && current.relatedFor == id) return
        prefetchRelated(id, title)
    }

    fun prefetchRelated(id: String, title: String) {
        val current = _state.value
        if (current.relatedFor == id && (current.related != null || current.relatedLoading)) return
        val token = ++relatedToken
        relatedJob?.cancel()
        _state.update {
            it.copy(relatedFor = id, relatedLoading = true, relatedError = null)
        }
        relatedJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { repository.related(id, title) }
                }
                if (token != relatedToken) return@launch
                result.onSuccess { works ->
                    _state.update {
                        it.copy(related = works, relatedLoading = false, relatedError = null)
                    }
                }.onFailure { problem ->
                    _state.update {
                        it.copy(relatedLoading = false, relatedError = problem)
                    }
                }
            } finally {
                // 被取消时别把「加载中」留在界面上（同 [loadAuthor] 的说明）。
                if (token == relatedToken && _state.value.relatedLoading) {
                    _state.update { it.copy(relatedLoading = false) }
                }
            }
        }
    }

    fun ensureAuthorWorks(series: Series) {
        val name = series.author.trim()
        if (name.isEmpty()) {
            // 作者还没解析出来（详情是 `includeAuthor=false` 取的）：先补查作者。
            // 拿到之后 [loadAuthor] 会自己再走一遍 [prefetchAuthorWorks]，不会停在这里。
            _state.value.detail?.let { loadAuthor(it) }
            return
        }
        val current = _state.value
        if (current.authorWorks != null) return
        if (current.authorWorksLoading && current.authorFor == name) return
        prefetchAuthorWorks(series)
    }

    /** 作者作品两段交付：搜索阶段先给一批，补季完成后再换完整结果。 */
    fun prefetchAuthorWorks(series: Series) {
        val name = series.author.trim()
        if (name.isEmpty()) {
            // **这里不能直接 return**：请求根本没发出去，而界面那边「works 为空」就等于「还在加载」，
            // 于是那一栏永远停在「正在查找该作者的作品…」上（用户报的「一直没有结果」）。
            // 先补查作者，拿到之后 [loadAuthor] 会带着非空名字再调一次这里。
            _state.value.detail?.let { loadAuthor(it) }
            return
        }
        val current = _state.value
        if (current.authorFor == name && (current.authorWorks != null || current.authorWorksLoading)) return
        val token = ++authorWorksToken
        authorWorksJob?.cancel()
        _state.update {
            it.copy(
                authorFor = name,
                authorWorks = null,
                authorWorksLoading = true,
                authorWorksError = null,
            )
        }
        authorWorksJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        repository.authorWorks(name, series) { partial ->
                            if (partial.isEmpty()) return@authorWorks
                            scope.launch {
                                if (token != authorWorksToken) return@launch
                                _state.update {
                                    it.copy(
                                        authorWorks = partial,
                                        authorWorksLoading = false,
                                        authorWorksError = null,
                                    )
                                }
                            }
                        }
                    }
                }
                if (token != authorWorksToken) return@launch
                result.onSuccess { works ->
                    _state.update {
                        it.copy(
                            authorWorks = works,
                            authorWorksLoading = false,
                            authorWorksError = null,
                        )
                    }
                }.onFailure { problem ->
                    _state.update {
                        it.copy(authorWorksLoading = false, authorWorksError = problem)
                    }
                }
            } finally {
                // 同上：被取消也要收掉「加载中」，否则界面永远停在「正在查找该作者的作品…」。
                if (token == authorWorksToken && _state.value.authorWorksLoading) {
                    _state.update { it.copy(authorWorksLoading = false) }
                }
            }
        }
    }

    fun clear() {
        loadToken++
        authorToken++
        relatedToken++
        authorWorksToken++
        loadJob?.cancel()
        authorJob?.cancel()
        relatedJob?.cancel()
        authorWorksJob?.cancel()
        loadJob = null
        authorJob = null
        relatedJob = null
        authorWorksJob = null
        _state.value = State()
    }

    private fun sameContent(a: Detail, b: Detail): Boolean =
        a.episodes == b.episodes &&
            a.upcoming == b.upcoming &&
            a.reserved == b.reserved &&
            a.series.copy(author = "") == b.series.copy(author = "")
}
