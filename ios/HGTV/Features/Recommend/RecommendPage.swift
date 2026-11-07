// SPDX-License-Identifier: GPL-3.0-only
//
// 推荐页：四条件裁横向滑动，每页一条独立的竖屏视频流。
//
// ```
// 推荐  ←→  漫剧  ←→  AI剧  ←→  真人  （顶部左对齐，与分类 / 榜单一致）
//        [全屏视频流，一屏一集]     （右上角：搜索）
// ```
//
// 四条体裁流是并列的，用户对它们的预期是「左右翻」而不是「点一下重载」，
// 因此用横向分页而不是切 tab：每一页的列表位置都保留（与 Android 的 pager 一致）。
// 设置里关掉「推荐页左右滑动」后，只能点顶部体裁换流。

import SwiftUI

struct RecommendPage: View {
    let isActive: Bool
    let onRoute: (AppRoute) -> Void

    @EnvironmentObject private var library: Library
    @Environment(\.metrics) private var m
    @Environment(\.safeAreaInsets) private var insets
    @Environment(\.theme) private var theme

    @StateObject private var model = RecommendModel()
    @State private var currentGenre: String?

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                Color.black.ignoresSafeArea()

                ScrollView(.horizontal) {
                    LazyHStack(spacing: 0) {
                        ForEach(model.genres) { board in
                            FeedView(
                                store: model.store(board),
                                isActive: isActive && (currentGenre ?? model.genres.first?.id) == board.id,
                                bottomInset: m.dp(PhoneDimen.tabBar) + insets.bottom,
                                onOpenDetail: { series in onRoute(.detail(seriesId: series.id, title: series.title)) },
                                onOpenPlayer: { series, episode in
                                    onRoute(.player(seriesId: series.id, title: series.title,
                                                    episode: episode, fromRecommend: true))
                                }
                            )
                            .frame(width: geo.size.width, height: geo.size.height)
                            .id(board.id)
                        }
                    }
                    .scrollTargetLayout()
                }
                .scrollTargetBehavior(.paging)
                .scrollPosition(id: $currentGenre)
                .scrollIndicators(.hidden)
                .scrollDisabled(!library.prefs.recommendSwipe)

                TopBar(
                    tabs: model.genres.map(\.label),
                    selected: model.genres.firstIndex { $0.id == (currentGenre ?? model.genres.first?.id) } ?? 0,
                    onSelect: { index in
                        guard model.genres.indices.contains(index) else { return }
                        currentGenre = model.genres[index].id
                    },
                    actionIcon: "magnifyingglass",
                    actionLabel: "搜索",
                    action: { onRoute(.search) },
                    immersive: true,
                    overlay: true
                )
            }
        }
        .task {
            model.applySettings(library: library)
            if currentGenre == nil { currentGenre = model.genres.first?.id }
        }
        .onChange(of: isActive) { _, active in
            model.setActive(active, genre: currentGenre)
        }
        .onChange(of: currentGenre) { _, _ in
            model.setActive(isActive, genre: currentGenre)
        }
    }
}

@MainActor
final class RecommendModel: ObservableObject {
    @Published private(set) var genres: [RankBoard] = RankBoard.navOrder
    private var stores: [String: FeedStore] = [:]

    init() {
        for board in RankBoard.navOrder {
            stores[board.id] = FeedStore(genre: board)
        }
    }

    func store(_ board: RankBoard) -> FeedStore {
        if let existing = stores[board.id] { return existing }
        let created = FeedStore(genre: board)
        stores[board.id] = created
        return created
    }

    /// 「推荐范围」：关掉的体裁不再铺页；一条都不剩时退回全开（与 Android 同一条兜底）。
    func applySettings(library: Library) {
        let typed: [RankBoard] = [.comic, .ai, .real]
        let enabled = typed.filter { board in
            guard let id = board.genre else { return true }
            return library.prefs.genreEnabled(id)
        }
        genres = [.new] + (enabled.isEmpty ? typed : enabled)
        genres.forEach { store($0).loadFirstPage() }
    }

    func setActive(_ active: Bool, genre: String?) {
        let id = genre ?? genres.first?.id
        for board in genres {
            store(board).setActive(active && board.id == id)
        }
    }
}

struct FeedView: View {
    @ObservedObject var store: FeedStore
    let isActive: Bool
    let bottomInset: CGFloat
    let onOpenDetail: (Series) -> Void
    let onOpenPlayer: (Series, Int) -> Void

    @State private var currentId: String?

    var body: some View {
        GeometryReader { geo in
            ScrollView(.vertical) {
                LazyVStack(spacing: 0) {
                    ForEach(store.items) { series in
                        VideoCardView(
                            series: series,
                            store: store,
                            isActive: isActive && store.current?.id == series.id,
                            bottomInset: bottomInset,
                            onOpenDetail: { onOpenDetail(series) },
                            onOpenPlayer: { onOpenPlayer(series, store.episodeIndex) }
                        )
                        .frame(width: geo.size.width, height: geo.size.height)
                        .id(series.id)
                    }
                    if store.items.isEmpty {
                        VStack(spacing: 12) {
                            if store.loading {
                                ProgressView().tint(.white)
                                Text("正在加载推荐…").foregroundStyle(.white.opacity(0.7))
                            } else if let error = store.errorText {
                                Text(error).foregroundStyle(.white.opacity(0.7))
                                Button("重试") { store.reset() }
                                    .foregroundStyle(.white)
                            }
                        }
                        .font(.system(size: 14))
                        .frame(width: geo.size.width, height: geo.size.height)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $currentId)
            .scrollIndicators(.hidden)
            .onAppear {
                store.loadFirstPage()
                if currentId == nil, let first = store.items.first?.id {
                    currentId = first
                    store.setCurrent(0, active: isActive)
                }
            }
            .onChange(of: currentId) { _, value in
                guard let value, let index = store.items.firstIndex(where: { $0.id == value }) else { return }
                store.setCurrent(index, active: isActive)
            }
            .onChange(of: store.items.count) { _, _ in
                if currentId == nil, let first = store.items.first?.id {
                    currentId = first
                    store.setCurrent(0, active: isActive)
                }
            }
            .onChange(of: isActive) { _, active in
                store.setActive(active)
            }
        }
    }
}
