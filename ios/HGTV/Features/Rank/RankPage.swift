// SPDX-License-Identifier: GPL-3.0-only
//
// 榜单页：四个榜单（推荐 / 漫剧 / AI剧 / 真人）+ 带名次的海报网格。
//
// 四个榜单就是 `RankBoard` 的四个枚举值，**与 TV 端完全同一套**：
// 接口的 genre 与 sort 都写在枚举里，页面不再拼参数。
// 「推荐」沿用原「上新」的时间序，不画名次（`RankBoard.ranked == false`）。

import SwiftUI

struct RankPage: View {
    let onRoute: (AppRoute) -> Void

    @ObservedObject private var library = Library.shared
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @Environment(\.safeAreaInsets) private var insets

    @State private var boardIndex = 0
    @State private var items: [Series] = []
    @State private var ranking: [String: RankPosition] = [:]
    @State private var page = 1
    @State private var hasMore = false
    @State private var loading = false
    @State private var loaded = false
    @State private var errorText: String?

    private let boards = RankBoard.navOrder

    var body: some View {
        VStack(spacing: 0) {
            TopBar(
                tabs: boards.map(\.label),
                selected: boardIndex,
                onSelect: selectBoard,
                actionIcon: "magnifyingglass",
                actionLabel: "搜索",
                action: { onRoute(.search) }
            )

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if let errorText, items.isEmpty {
                        EmptyState(text: errorText)
                    }

                    SeriesGrid(
                        items: items,
                        columns: library.prefs.gridColumns,
                        ranked: boards[boardIndex].ranked,
                        ranking: ranking,
                        onOpen: { onRoute(.detail(seriesId: $0.id, title: $0.title)) },
                        onPlay: { series in
                            onRoute(.player(seriesId: series.id, title: series.title,
                                            episode: library.progress(series.id)?.episodeIndex,
                                            fromRecommend: false))
                        },
                        onMenu: { _ in Task { await load(reset: true) } },
                        menuHotList: true
                    )

                    LoadMoreFooter(
                        loading: loading,
                        hasMore: hasMore,
                        emptyText: items.isEmpty && !loading && errorText == nil ? "暂无内容" : nil,
                        onLoad: { loadMore() }
                    )
                    .padding(.bottom, m.dp(PhoneDimen.tabBar) + insets.bottom + m.dp(8))
                }
            }
            .scrollIndicators(.hidden)
            .refreshable { await load(reset: true) }
        }
        .background(theme.baseColor.ignoresSafeArea())
        .task {
            if !loaded {
                loaded = true
                await load(reset: true)
            }
        }
    }

    private func selectBoard(_ index: Int) {
        guard index != boardIndex else { return }
        boardIndex = index
        items = []
        ranking = [:]
        Task { await load(reset: true) }
    }

    private func load(reset: Bool) async {
        guard !loading else { return }
        loading = true
        if reset { errorText = nil }
        defer { loading = false }
        do {
            let result = try await ContentRepository.shared.rank(
                page: reset ? 1 : page, board: boards[boardIndex])
            let hidden = library.hidden()
            let visible = CatalogFilter.visible(result.items, hidden: hidden)
            if reset {
                items = visible
                page = 2
            } else {
                let existing = Set(items.map(\.id))
                items += visible.filter { !existing.contains($0.id) }
                page += 1
            }
            ranking.merge(result.ranking?.positions ?? [:]) { _, new in new }
            hasMore = result.hasMore
            errorText = nil
        } catch {
            if items.isEmpty { errorText = "榜单加载失败，下拉重试" }
            hasMore = false
        }
    }

    private func loadMore() {
        guard hasMore, !loading else { return }
        Task { await load(reset: false) }
    }
}
