// SPDX-License-Identifier: GPL-3.0-only
//
// 分类页：与 Android `PhoneCategoryPage` 同一套两层筛选结构。
//
// ```
// ① 体裁（常驻）：全部 / 漫剧 / AI剧 / 真人      ← 顺序写死，不跟接口走
// ② 细分筛选行：主题 / 设定 / 背景 / 推荐 / 受众 / 时间 / 状态
// ③ 海报网格（列数跟设置）
// ```
//
// 三条规则与 TV 端一致：体裁顺序固定；换体裁时清掉随体裁变化的维度并重新取面板；
// 每行开头都有「全部」（语义是「不传这个维度」）。

import SwiftUI

struct CategoryPage: View {
    let onRoute: (AppRoute) -> Void

    @ObservedObject private var library = Library.shared
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @Environment(\.safeAreaInsets) private var insets

    @State private var genreIndex = 0
    @State private var selection = CategorySelection()
    @State private var panel: CategoryPanel?
    @State private var items: [Series] = []
    @State private var page = 1
    @State private var hasMore = false
    @State private var loading = false
    @State private var loaded = false
    @State private var errorText: String?

    private let genres = CategoryGenres.options

    var body: some View {
        VStack(spacing: 0) {
            TopBar(
                tabs: genres.map(\.label),
                selected: genreIndex,
                onSelect: selectGenre,
                actionIcon: "magnifyingglass",
                actionLabel: "搜索",
                action: { onRoute(.search) }
            )

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if let panel, !panel.rows.filter({ $0.type != CategorySelection.typeGenre }).isEmpty {
                        filterPanel(panel)
                    }

                    if let errorText, items.isEmpty {
                        EmptyState(text: errorText)
                    }

                    SeriesGrid(
                        items: items,
                        columns: library.prefs.gridColumns,
                        onOpen: { onRoute(.detail(seriesId: $0.id, title: $0.title)) },
                        onPlay: { series in
                            onRoute(.player(seriesId: series.id, title: series.title,
                                            episode: library.progress(series.id)?.episodeIndex,
                                            fromRecommend: false))
                        },
                        onMenu: { _ in }
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
            .refreshable { await load(reset: true, needPanel: true) }
        }
        .background(theme.baseColor.ignoresSafeArea())
        .task {
            if !loaded {
                loaded = true
                await load(reset: true, needPanel: true)
            }
        }
    }

    // MARK: 筛选行

    private func filterPanel(_ panel: CategoryPanel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(panel.rows.filter { $0.type != CategorySelection.typeGenre }) { row in
                HStack(alignment: .center, spacing: m.dp(4)) {
                    Text(row.label)
                        .font(.system(size: m.sp(11)))
                        .foregroundStyle(theme.textDim)
                        .frame(width: m.dp(38), alignment: .leading)
                        .lineLimit(1)

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: m.dp(3)) {
                            chipRow(row, id: nil, text: "全部")
                            ForEach(row.options) { option in
                                chipRow(row, id: option.id, text: option.label)
                            }
                        }
                        .padding(.trailing, m.dp(8))
                    }
                }
                .frame(minHeight: m.dp(30))
                .padding(.leading, m.dp(12))
            }
        }
        .padding(.bottom, m.dp(2))
    }

    private func chipRow(_ row: CategoryFilterRow, id: String?, text: String) -> some View {
        let on = selection.value(row.type) == id
        return Button {
            selection.set(row.type, id)
            Task { await load(reset: true, needPanel: false) }
        } label: {
            Text(text)
                .font(.system(size: m.sp(12)))
                .foregroundStyle(on ? theme.text : theme.textDim)
                .padding(.horizontal, m.dp(9))
                .padding(.vertical, m.dp(4))
                .background {
                    if on {
                        Capsule().fill(theme.surfaceHigh)
                    }
                }
        }
        .buttonStyle(.plain)
    }

    private func selectGenre(_ index: Int) {
        guard index != genreIndex else { return }
        genreIndex = index
        selection = selection.newGenre(genres[index].id)
        panel = nil
        Task { await load(reset: true, needPanel: true) }
    }

    // MARK: 数据

    private func load(reset: Bool, needPanel: Bool) async {
        guard !loading else { return }
        loading = true
        if reset { errorText = nil }
        defer { loading = false }
        do {
            let result = try await ContentRepository.shared.categoryFeed(
                selection, page: reset ? 1 : page, needPanel: needPanel)
            if reset {
                items = result.page.items
                page = 2
            } else {
                let existing = Set(items.map(\.id))
                items += result.page.items.filter { !existing.contains($0.id) }
                page += 1
            }
            if needPanel { panel = result.panel }
            hasMore = result.page.hasMore
            errorText = nil
        } catch {
            if items.isEmpty { errorText = "分类加载失败，下拉重试" }
            hasMore = false
        }
    }

    private func loadMore() {
        guard hasMore, !loading else { return }
        Task { await load(reset: false, needPanel: false) }
    }
}
