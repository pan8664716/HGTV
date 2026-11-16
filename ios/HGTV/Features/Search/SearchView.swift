// SPDX-License-Identifier: GPL-3.0-only
//
// 搜索页：输入框 + 提示区 + 海报结果。
//
// | 输入框 | 提示区内容 |
// | --- | --- |
// | 空 | 本机搜索记录（没有记录时铺热门搜索） |
// | 有字 | 本机记录 + 热门词里匹配的候选（点任意一枚 = 用这个词搜索） |
// | 已经在看结果 | 提示区收起，把屏幕让给结果 |
//
// 与 Android 的差别只有联想词来源：那一层是服务端接口，iOS 本轮用本机记录与热搜
// 做候选（见 docs/IOS_PORT.md「已知边界」），点任意一枚的行为完全一致。

import SwiftUI

struct SearchView: View {
    @ObservedObject private var library = Library.shared
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @Environment(\.safeAreaInsets) private var insets

    @State private var keyword = ""
    @State private var submitted = ""
    @State private var items: [Series] = []
    @State private var page = 1
    @State private var hasMore = false
    @State private var loading = false
    @State private var showingResults = false
    @State private var errorText: String?
    @State private var hot: [String] = []
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            if showingResults {
                results
            } else {
                hints
            }
        }
        .background(theme.baseColor.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
        .task {
            focused = true
            hot = (try? await ContentRepository.shared.hotSearches()) ?? []
        }
    }

    private var searchBar: some View {
        HStack(spacing: m.dp(8)) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: m.sp(16), weight: .semibold))
                    .foregroundStyle(theme.text)
                    .frame(width: m.dp(30), height: m.dp(34))
            }
            .buttonStyle(.plain)

            HStack(spacing: 0) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: m.sp(13)))
                    .foregroundStyle(theme.textFaint)
                    .padding(.leading, m.dp(10))
                TextField("搜剧名、演员、题材", text: $keyword)
                    .font(.system(size: m.sp(14)))
                    .foregroundStyle(theme.text)
                    .focused($focused)
                    .submitLabel(.search)
                    .onSubmit { runSearch() }
                    .onChange(of: keyword) { _, _ in showingResults = false }
                if !keyword.isEmpty {
                    Button { keyword = "" } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: m.sp(13)))
                            .foregroundStyle(theme.textFaint)
                    }
                    .buttonStyle(.plain)
                    .padding(.trailing, m.dp(10))
                }
            }
            .frame(height: m.dp(34))
            .soft(.inset, fill: theme.well, radius: m.dp(17), depth: max(1, m.dp(3)))

            Button("搜索") { runSearch() }
                .font(.system(size: m.sp(16)))
                .foregroundStyle(theme.accent)
                .buttonStyle(.plain)
                .padding(.trailing, m.dp(4))
        }
        .padding(.horizontal, m.dp(8))
        .padding(.top, insets.top + m.dp(6))
        .padding(.bottom, m.dp(8))
    }

    // MARK: 提示区

    private var hints: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: m.dp(10)) {
                if !library.data.searches.isEmpty {
                    HStack {
                        Text("最近搜索")
                            .font(.system(size: m.sp(13)))
                            .foregroundStyle(theme.textDim)
                        Spacer()
                        Button("清空") {
                            library.clearSearches()
                        }
                        .font(.system(size: m.sp(12)))
                        .foregroundStyle(theme.textFaint)
                        .buttonStyle(.plain)
                    }
                    chipGrid(library.data.searches)
                }

                if !hot.isEmpty {
                    Text("热门搜索")
                        .font(.system(size: m.sp(13)))
                        .foregroundStyle(theme.textDim)
                        .padding(.top, m.dp(4))
                    chipGrid(hot)
                }

                if !keyword.isEmpty {
                    let candidates = candidatesForKeyword
                    if !candidates.isEmpty {
                        Text("搜索建议")
                            .font(.system(size: m.sp(13)))
                            .foregroundStyle(theme.textDim)
                            .padding(.top, m.dp(4))
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(candidates, id: \.self) { word in
                                Button {
                                    keyword = word
                                    runSearch()
                                } label: {
                                    HStack(spacing: m.dp(8)) {
                                        Image(systemName: "magnifyingglass")
                                            .font(.system(size: m.sp(12)))
                                            .foregroundStyle(theme.textFaint)
                                        Text(word)
                                            .font(.system(size: m.sp(14)))
                                            .foregroundStyle(theme.text)
                                        Spacer()
                                    }
                                    .padding(.vertical, m.dp(10))
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
                Spacer(minLength: m.dp(40))
            }
            .padding(.horizontal, m.dp(16))
            .padding(.top, m.dp(6))
        }
        .scrollIndicators(.hidden)
    }

    private var candidatesForKeyword: [String] {
        let word = keyword.trimmingCharacters(in: .whitespaces)
        guard !word.isEmpty else { return [] }
        let pool = library.data.searches + hot
        var seen = Set<String>()
        return pool
            .filter { $0.contains(word) && seen.insert($0).inserted }
            .prefix(AppConfig.Ui.searchSuggestionLimit)
            .map { $0 }
    }

    private func chipGrid(_ words: [String]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: m.dp(76)), spacing: m.dp(8))],
                  alignment: .leading, spacing: m.dp(8)) {
            ForEach(words, id: \.self) { word in
                Button {
                    keyword = word
                    runSearch()
                } label: {
                    Text(word)
                        .font(.system(size: m.sp(13)))
                        .foregroundStyle(theme.text)
                        .lineLimit(1)
                        .padding(.horizontal, m.dp(10))
                        .padding(.vertical, m.dp(6))
                        .frame(maxWidth: .infinity)
                        .soft(.raised, fill: theme.surface, radius: m.dp(14), depth: max(1, m.dp(2)))
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: 结果

    private var results: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let errorText, items.isEmpty {
                    EmptyState(text: errorText)
                }
                SeriesGrid(
                    items: items,
                    columns: library.prefs.gridColumns,
                    onOpen: { router.openDetail($0) },
                    onPlay: { series in
                        router.openPlayer(seriesId: series.id, title: series.title,
                                          episode: library.progress(series.id)?.episodeIndex)
                    },
                    onMenu: { _ in }
                )
                LoadMoreFooter(
                    loading: loading,
                    hasMore: hasMore,
                    emptyText: items.isEmpty && !loading && errorText == nil ? "没有找到相关剧集，换个关键词试试" : nil,
                    onLoad: { loadMore() }
                )
                .padding(.bottom, m.dp(20) + insets.bottom)
            }
        }
        .scrollIndicators(.hidden)
    }

    private func runSearch() {
        let word = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !word.isEmpty else { return }
        library.rememberSearch(word)
        submitted = word
        showingResults = true
        focused = false
        items = []
        page = 1
        hasMore = false
        errorText = nil
        Task { await load(reset: true) }
    }

    private func load(reset: Bool) async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            let result = try await ContentRepository.shared.search(
                keyword: submitted, page: reset ? 1 : page,
                source: library.prefs.searchSource)
            if reset {
                items = result.items
                page = 2
            } else {
                let existing = Set(items.map(\.id))
                items += result.items.filter { !existing.contains($0.id) }
                page += 1
            }
            hasMore = result.hasMore
            errorText = nil
        } catch {
            if items.isEmpty { errorText = "搜索失败，稍后再试" }
            hasMore = false
        }
    }

    private func loadMore() {
        guard hasMore, !loading else { return }
        Task { await load(reset: false) }
    }
}
