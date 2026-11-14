// SPDX-License-Identifier: GPL-3.0-only
//
// 我的页：账号状态 + 历史 / 收藏 / 稍后看 三栏 + 内容网格，右上角设置入口。
//
// 与 Android `PhoneMinePage` 同一套语义：
// * 默认落在「历史」（用户进这一页最常见的是接着看，不是先看收藏）；
// * 历史不画进度条，状态行只显示集数与时间；收藏画「看到第 N 集」，稍后看不占位；
// * 历史可筛已看完 / 未看完（点已选中的那一枚 = 取消筛选，没有第三枚「全部」）；
// * 长按海报 = 电视端的菜单键（继续观看 / 收藏 / 稍后看 / 详情 / 删除记录）。

import SwiftUI

struct MinePage: View {
    let onRoute: (AppRoute) -> Void

    @ObservedObject private var library = Library.shared
    @EnvironmentObject private var session: SessionStore
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @Environment(\.safeAreaInsets) private var insets

    @State private var tab = 0
    @State private var historyFilter: Bool?

    private var tabs: [String] { ["历史", "收藏", "稍后看"] }

    var body: some View {
        VStack(spacing: 0) {
            TopBar(
                title: "我的",
                actionIcon: "gearshape",
                actionLabel: "设置",
                action: { onRoute(.settings(.playback)) }
            )

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    accountRow
                    tabRow
                    if tabs[tab] == "历史" { filterRow }
                    SeriesGrid(
                        items: rows,
                        columns: library.prefs.gridColumns,
                        statusOf: { series in
                            if tabs[tab] == "收藏" { return library.favoriteLabel(series.id) ?? "" }
                            if tabs[tab] == "历史", let progress = library.progress(series.id) {
                                return "第 \(progress.episodeIndex + 1) 集 · \(PlaybackInfoLine.time(progress.position))"
                            }
                            return ""
                        },
                        statusOnly: tabs[tab] == "历史",
                        onOpen: { onRoute(.detail(seriesId: $0.id, title: $0.title)) },
                        onPlay: { series in
                            onRoute(.player(seriesId: series.id, title: series.title,
                                            episode: library.progress(series.id)?.episodeIndex,
                                            fromRecommend: false))
                        },
                        onMenu: { _ in },
                        menuInHistory: tabs[tab] == "历史"
                    )
                    .padding(.top, m.dp(4))

                    if rows.isEmpty {
                        EmptyState(text: tabs[tab] == "历史" ? "还没有观看记录" : "还没有收藏")
                    }

                    Text("HGTV  ·  收藏与进度保存在本机")
                        .font(.system(size: m.sp(11)))
                        .foregroundStyle(theme.textFaint)
                        .padding(.top, m.dp(18))
                        .padding(.horizontal, m.dp(6))

                    Spacer(minLength: m.dp(PhoneDimen.tabBar) + insets.bottom + m.dp(8))
                }
            }
            .scrollIndicators(.hidden)
        }
        .background(theme.baseColor.ignoresSafeArea())
    }

    private var rows: [Series] {
        switch tabs[tab] {
        case "历史":
            return library.history(filter: historyFilter).map(\.series)
        case "收藏":
            return library.data.favorites
        default:
            return library.data.later
        }
    }

    // MARK: 账号

    private var accountRow: some View {
        Button {
            onRoute(.account)
        } label: {
            HStack(spacing: m.dp(14)) {
                Text(avatarText)
                    .font(.system(size: m.sp(16)))
                    .foregroundStyle(theme.text)
                    .frame(width: m.dp(42), height: m.dp(42))
                    .soft(.raised, fill: theme.surfaceHigh, radius: m.dp(20), depth: max(1, m.dp(3)))

                VStack(alignment: .leading, spacing: m.dp(3)) {
                    Text(session.signedIn ? (session.session.nickname.isEmpty ? "已登录" : session.session.nickname) : "未登录")
                        .font(.system(size: m.sp(17), weight: .bold))
                        .foregroundStyle(theme.text)
                        .lineLimit(1)
                    Text(session.signedIn
                         ? (session.session.displayId.isEmpty ? "点这里查看账号" : "平台号 \(session.session.displayId)")
                         : "点这里登录 · 记录仅保存在本机")
                        .font(.system(size: m.sp(12)))
                        .foregroundStyle(theme.textFaint)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: m.sp(12), weight: .semibold))
                    .foregroundStyle(theme.textFaint)
            }
            .padding(.horizontal, m.dp(14))
            .padding(.vertical, m.dp(10))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var avatarText: String {
        if session.signedIn, let first = session.session.nickname.first { return String(first) }
        return "红"
    }

    // MARK: 栏目

    private var tabRow: some View {
        HStack(spacing: 0) {
            ForEach(tabs.indices, id: \.self) { index in
                Button {
                    tab = index
                } label: {
                    Text(tabs[index])
                        .font(.system(size: m.sp(16), weight: index == tab ? .bold : .regular))
                        .foregroundStyle(index == tab ? theme.text : theme.textFaint)
                        .padding(.vertical, m.dp(6))
                        .padding(.trailing, m.dp(24))
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, m.dp(16))
        .padding(.vertical, m.dp(6))
    }

    private var filterRow: some View {
        HStack(spacing: m.dp(8)) {
            ForEach([true, false], id: \.self) { value in
                let on = historyFilter == value
                Button {
                    historyFilter = on ? nil : value
                } label: {
                    Text(value ? "已看完" : "未看完")
                        .font(.system(size: m.sp(12)))
                        .foregroundStyle(on ? theme.onLit : theme.text)
                        .padding(.horizontal, m.dp(10))
                        .padding(.vertical, m.dp(3))
                        .soft(.raised, fill: on ? theme.lit : theme.surface,
                              radius: m.dp(PhoneDimen.radiusPill), depth: max(1, m.dp(3)))
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, m.dp(16))
        .padding(.bottom, m.dp(4))
    }
}
