// SPDX-License-Identifier: GPL-3.0-only
//
// 榜单页：推荐榜 / 热播榜 / 预约榜 / 新剧榜 / 热搜榜 / 追更榜 / 必看榜 / 收藏榜。
//
// 八个榜单与 Android 共用 HAR 的 `cell/change` 参数；下一页依赖服务端游标。

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
    @State private var cursor: CatalogRanking?
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
                        ranked: true,
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
        cursor = nil
        Task { await load(reset: true) }
    }

    private func load(reset: Bool) async {
        guard !loading else { return }
        loading = true
        if reset { errorText = nil }
        defer { loading = false }
        do {
            let result = try await ContentRepository.shared.rank(
                page: reset ? 1 : page, board: boards[boardIndex],
                cursor: reset ? nil : cursor)
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
            cursor = result.ranking
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
