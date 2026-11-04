// SPDX-License-Identifier: GPL-3.0-only
//
// 详情页：与 TV / Android 手机端一一对应。
//
// ```
// ‹ 返回
// ┌─────────────┬──────────────────────────┐
// │             │ 剧名                      │
// │   封面       │ 题材标签                  │
// │  （3:4）     │ 第N季 · 全85集 · 作者      │
// │             │ [继续第3集] [收藏] [稍后看] │
// └─────────────┴──────────────────────────┘
// 简介（两行 + 展开）
// 选集 · 全85集
// 第 1—20 集 · 共 85 集            [1-20][21-40] 1/5
// 相关剧集（横向滚动）
// ```
//
// 三条与 TV 一致的规则：有观看记录时主按钮是「继续第 N 集」；选集网格与播放页
// 共用同一份实现；未上架的剧没有分集。

import SwiftUI

struct DetailView: View {
    let seriesId: String
    let title: String

    @ObservedObject private var library = Library.shared
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @Environment(\.safeAreaInsets) private var insets

    @State private var detail: SeriesDetail?
    @State private var related: [Series] = []
    @State private var errorText: String?
    @State private var descExpanded = false

    private var series: Series { detail?.series ?? Series(id: seriesId, title: title) }

    private var resumeIndex: Int? {
        guard let detail, let saved = library.progress(seriesId) else { return nil }
        return detail.episodes.firstIndex(of: saved.episodeId)
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            theme.baseColor.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    head
                    description
                    episodeSection
                    relatedSection
                    Spacer(minLength: m.dp(30) + insets.bottom)
                }
                .padding(.horizontal, m.dp(14))
                .padding(.top, insets.top + m.dp(44))
            }
            .scrollIndicators(.hidden)

            backButton
        }
        .navigationBarBackButtonHidden(true)
        .task { await load() }
    }

    private var backButton: some View {
        Button { dismiss() } label: {
            Image(systemName: "chevron.left")
                .font(.system(size: m.sp(16), weight: .semibold))
                .foregroundStyle(theme.text)
                .frame(width: m.dp(40), height: m.dp(40))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.leading, m.dp(6))
        .padding(.top, insets.top + m.dp(4))
    }

    // MARK: 头部

    private var head: some View {
        HStack(alignment: .top, spacing: m.dp(14)) {
            ArtworkView(url: series.cover)
                .frame(width: m.dp(104), height: m.dp(148))
                .background(theme.well)
                .clipShape(RoundedRectangle(cornerRadius: m.dp(PhoneDimen.radiusCard), style: .continuous))
                .soft(.raised, fill: theme.surface, radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(4)))

            VStack(alignment: .leading, spacing: m.dp(4)) {
                Text(series.title.isEmpty ? "加载中…" : series.title)
                    .font(.system(size: m.sp(18), weight: .bold))
                    .foregroundStyle(theme.text)
                    .lineLimit(2)

                if !series.tags.isEmpty {
                    Text(series.tags)
                        .font(.system(size: m.sp(12)))
                        .foregroundStyle(theme.textFaint)
                        .lineLimit(1)
                }

                Text(metaText)
                    .font(.system(size: m.sp(13)))
                    .foregroundStyle(theme.textDim)
                    .lineLimit(1)

                Spacer(minLength: 0)

                actionButton(icon: primaryIcon, label: primaryLabel, primary: true, active: false) {
                    primaryAction()
                }
                actionButton(icon: library.isFavorite(seriesId) ? "heart.fill" : "heart",
                             label: library.isFavorite(seriesId) ? "已收藏" : "收藏",
                             primary: false, active: library.isFavorite(seriesId)) {
                    library.toggleFavorite(series)
                }
                actionButton(icon: library.isLater(seriesId) ? "bookmark.fill" : "bookmark",
                             label: library.isLater(seriesId) ? "已加入" : "稍后看",
                             primary: false, active: library.isLater(seriesId)) {
                    library.toggleLater(series)
                }
            }
            .frame(height: m.dp(148))
        }
        .padding(.top, m.dp(4))
        .padding(.bottom, m.dp(6))
    }

    private var metaText: String {
        var parts: [String] = []
        if series.seasonIndex > 0 { parts.append("第\(series.seasonIndex)季") }
        if let detail {
            if detail.upcoming { parts.append("尚未上线") }
            else if !detail.episodes.isEmpty { parts.append("全\(detail.episodes.count)集") }
        } else if series.episodeCount > 0 {
            parts.append("全\(series.episodeCount)集")
        }
        if !series.author.isEmpty { parts.append(series.author) }
        return parts.joined(separator: "  ·  ")
    }

    private var primaryLabel: String {
        if detail?.upcoming == true { return "尚未上线" }
        if let index = resumeIndex { return "继续第 \(index + 1) 集" }
        return "播放"
    }

    private var primaryIcon: String {
        detail?.upcoming == true ? "clock" : "play.fill"
    }

    private func actionButton(icon: String, label: String, primary: Bool, active: Bool,
                              action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: m.dp(7)) {
                Image(systemName: icon)
                    .font(.system(size: m.sp(12), weight: .semibold))
                Text(label)
                    .font(.system(size: m.sp(12)))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .foregroundStyle(primary ? Color.white : (active ? theme.accent : theme.text))
            .padding(.horizontal, m.dp(8))
            .frame(height: m.dp(26))
            .background {
                RoundedRectangle(cornerRadius: m.dp(PhoneDimen.radiusCard), style: .continuous)
                    .fill(primary ? theme.accent : theme.surface)
            }
        }
        .buttonStyle(.plain)
        .disabled(detail?.upcoming == true && primary)
    }

    private func primaryAction() {
        guard detail?.upcoming != true else { return }
        router.openPlayer(seriesId: seriesId, title: series.title, episode: resumeIndex)
    }

    // MARK: 简介

    private var description: some View {
        VStack(alignment: .leading, spacing: m.dp(4)) {
            if !series.description.isEmpty {
                Text(series.description.replacingOccurrences(of: "\n", with: " "))
                    .font(.system(size: m.sp(13)))
                    .foregroundStyle(theme.textFaint)
                    .lineSpacing(m.dp(2))
                    .lineLimit(descExpanded ? nil : 2)
                Button(descExpanded ? "收起" : "展开") {
                    descExpanded.toggle()
                }
                .font(.system(size: m.sp(13), weight: .bold))
                .foregroundStyle(theme.accent)
                .buttonStyle(.plain)
            }
        }
        .padding(.top, m.dp(10))
    }

    // MARK: 选集

    @ViewBuilder
    private var episodeSection: some View {
        if let detail, !detail.episodes.isEmpty {
            VStack(alignment: .leading, spacing: m.dp(4)) {
                Text("选集 · 全\(detail.episodes.count)集")
                    .font(.system(size: m.sp(12)))
                    .foregroundStyle(theme.textDim)
                    .padding(.top, m.dp(14))

                EpisodeGrid(count: detail.episodes.count,
                            current: resumeIndex ?? 0,
                            pageSize: library.prefs.episodePageSize) { index in
                    router.openPlayer(seriesId: seriesId, title: series.title, episode: index)
                }
            }
        }
    }

    // MARK: 相关

    @ViewBuilder
    private var relatedSection: some View {
        if !related.isEmpty {
            VStack(alignment: .leading, spacing: m.dp(6)) {
                Text("相关剧集")
                    .font(.system(size: m.sp(12)))
                    .foregroundStyle(theme.textDim)
                    .padding(.top, m.dp(16))

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: m.dp(8)) {
                        ForEach(related) { item in
                            Button {
                                router.push(.detail(seriesId: item.id, title: item.title))
                            } label: {
                                PosterCardView(series: item)
                                    .frame(width: m.dp(104))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }

    private func load() async {
        do {
            let result = try await ContentRepository.shared.detail(id: seriesId)
            detail = result
            errorText = nil
            related = (try? await ContentRepository.shared.related(id: seriesId, title: result.series.title)) ?? []
        } catch {
            errorText = "详情加载失败"
            if detail == nil {
                ToastCenter.shared.show("详情加载失败，稍后再试")
            }
        }
    }
}
