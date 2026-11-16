// SPDX-License-Identifier: GPL-3.0-only
//
// 海报网格与长按菜单：分类 / 榜单 / 搜索 / 我的 四个页面共用一份，
// 与 Android `PhonePosterFeed` + `PhoneCardMenu` 的分工一致。

import SwiftUI

struct SeriesGrid: View {
    let items: [Series]
    var columns: Int = AppConfig.LibraryDefaults.gridColumns
    var ranked: Bool = false
    /// 榜单页的名次与热度由服务端给（`CatalogRanking`），这里按 id 取。
    var ranking: [String: RankPosition] = [:]
    var progressOf: ((Series) -> Int?)? = nil
    var statusOf: ((Series) -> String)? = nil
    /// 状态行优先：隐藏分类标签，只显示状态文字（「我的」页使用）。
    var statusOnly: Bool = false
    var onOpen: (Series) -> Void
    var onPlay: ((Series) -> Void)? = nil
    var onMenu: ((Series) -> Void)? = nil
    /// 长按菜单里的上下文：历史栏多「删除这条记录」，榜单多「不在热播榜中显示」。
    var menuInHistory: Bool = false
    var menuHotList: Bool = false

    @Environment(\.metrics) private var m

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: m.dp(PhoneDimen.posterGap)),
                                 count: max(1, min(AppConfig.LibraryDefaults.maxPosterColumns, columns))),
                  spacing: m.dp(PhoneDimen.posterGap)) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, series in
                Button {
                    onOpen(series)
                } label: {
                    PosterCardView(
                        series: series,
                        rank: ranked ? (ranking[series.id]?.rank ?? index + 1) : nil,
                        rankTop: index < 3,
                        statusText: statusOf?(series) ?? "",
                        progress: progressOf?(series),
                        heatServerText: ranking[series.id]?.heat,
                        statusOnly: statusOnly
                    )
                }
                .buttonStyle(.plain)
                .contextMenu {
                    if let onMenu {
                        CardMenuItems(
                            series: series,
                            inHistory: menuInHistory,
                            hotList: menuHotList,
                            onPlay: { onPlay?(series) },
                            onDetail: { onOpen(series) },
                            onChanged: { onMenu(series) }
                        )
                    }
                }
            }
        }
        .padding(.horizontal, m.dp(PhoneDimen.pageInset))
    }
}

/// 长按菜单的菜单项。与 Android `CardMenu` 的条目一致，按当前上下文增删。
struct CardMenuItems: View {
    let series: Series
    var inHistory: Bool = false
    var hotList: Bool = false
    /// 三个回调都由页面注入：菜单不自己找路由，避免在 `contextMenu` 的环境里
    /// 拿不到 `Router` 时整块菜单失效（这类问题只在真机上暴露）。
    var onPlay: (() -> Void)? = nil
    var onDetail: (() -> Void)? = nil
    var onChanged: (() -> Void)? = nil

    @ObservedObject private var library = Library.shared

    var body: some View {
        if let onPlay {
            Button(action: onPlay) {
                Label("继续观看", systemImage: "play.fill")
            }
        }

        Button {
            library.toggleFavorite(series)
            onChanged?()
        } label: {
            Label(library.isFavorite(series.id) ? "取消收藏" : "收藏",
                  systemImage: library.isFavorite(series.id) ? "heart.fill" : "heart")
        }

        Button {
            library.toggleLater(series)
            onChanged?()
        } label: {
            Label(library.isLater(series.id) ? "移出稍后观看" : "稍后看",
                  systemImage: library.isLater(series.id) ? "bookmark.fill" : "bookmark")
        }

        if let onDetail {
            Button(action: onDetail) {
                Label("查看详情", systemImage: "info.circle")
            }
        }

        if inHistory {
            Button(role: .destructive) {
                library.removeHistory(series.id)
                onChanged?()
            } label: {
                Label("删除这条记录", systemImage: "trash")
            }
        }

        if hotList {
            Button(role: .destructive) {
                library.hide(series.id)
                onChanged?()
            } label: {
                Label("不在热播榜中显示", systemImage: "eye.slash")
            }
        }
    }
}

/// 网格底部的「加载更多」占位：进入视口就触发下一页（与 Android 的「最后 5 条时预取」同义）。
struct LoadMoreFooter: View {
    var loading: Bool
    var hasMore: Bool
    var emptyText: String?
    var onLoad: () -> Void

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var body: some View {
        Group {
            if let emptyText {
                Text(emptyText)
                    .font(.system(size: m.sp(13)))
                    .foregroundStyle(theme.textFaint)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, m.dp(50))
            } else if loading {
                HStack(spacing: m.dp(8)) {
                    ProgressView().tint(theme.textDim)
                    Text("加载中…")
                        .font(.system(size: m.sp(12)))
                        .foregroundStyle(theme.textFaint)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, m.dp(18))
            } else if hasMore {
                Color.clear.frame(height: m.dp(1)).onAppear(perform: onLoad)
            }
        }
    }
}
