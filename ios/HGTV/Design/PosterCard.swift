// SPDX-License-Identifier: GPL-3.0-only
//
// 海报卡：结构与 Android `shared/ui/PosterCardView.kt` 一致。
//
// ┌ 海报井 ────────────────────────────┐
// │ [系统标签 / 名次]          [第 N 季] │
// │              封面                   │
// │ [🔥热度]              [共 N 集]      │
// │ 已播进度条（可选）                    │
// └────────────────────────────────────┘
//   剧名
//   类型标签                     状态

import SwiftUI

struct PosterCardView: View {
    let series: Series
    var rank: Int? = nil
    var rankTop: Bool = false
    var statusText: String = ""
    var progress: Int? = nil
    var heatServerText: String? = nil

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    private var content: (episode: String, season: String, heat: String, corner: String, tags: String, status: String) {
        let tags = PosterText.tags(series.tags)
        let status = EpisodeLabel.cardStatus(statusText)
        let episode = EpisodeLabel.total(series.episodeCount)
        let season = SeriesSeasons.badge(series)
        let heat = HeatLabel.badge(serverText: heatServerText, value: series.heat)
        let corner = series.label.isEmpty ? (rank.map { "第 \($0) 名" } ?? "") : series.label
        return (episode, season, heat, corner, tags, status)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            cover
            Text(series.title)
                .font(.system(size: m.sp(14), weight: .bold))
                .foregroundStyle(theme.textDim)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .padding(.horizontal, m.dp(4))
                .padding(.top, m.dp(5))
            HStack(spacing: m.dp(4)) {
                Text(content.tags)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text(content.status)
                    .lineLimit(1)
            }
            .font(.system(size: m.sp(11.5)))
            .foregroundStyle(theme.textFaint)
            .padding(.horizontal, m.dp(4))
            .padding(.top, m.dp(2))
            .padding(.bottom, m.dp(5))
            .frame(height: m.dp(20), alignment: .top)
        }
        .background {
            RoundedRectangle(cornerRadius: m.dp(PhoneDimen.radiusCard), style: .continuous)
                .fill(theme.surface)
                .shadow(color: theme.shadowDark.opacity(0.55), radius: m.dp(2.5), x: m.dp(0.6), y: m.dp(1))
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel([content.corner, content.episode, series.title, content.tags]
            .filter { !$0.isEmpty }.joined(separator: "，"))
    }

    private var cover: some View {
        ZStack {
            RoundedRectangle(cornerRadius: m.dp(PhoneDimen.radiusCard), style: .continuous)
                .fill(theme.well)
            ArtworkView(url: series.cover)
                .clipShape(RoundedRectangle(cornerRadius: m.dp(PhoneDimen.radiusCard), style: .continuous))

            VStack {
                HStack(alignment: .top) {
                    if !content.corner.isEmpty {
                        Text(content.corner)
                            .font(.system(size: m.sp(10.5), weight: .semibold))
                            .foregroundStyle(PhoneTheme.playerText)
                            .padding(.horizontal, m.dp(5))
                            .padding(.vertical, m.dp(2))
                            .background(cornerBackground, in: RoundedRectangle(cornerRadius: m.dp(4)))
                    }
                    Spacer(minLength: 0)
                    if !content.season.isEmpty {
                        Text(content.season)
                            .font(.system(size: m.sp(10)))
                            .foregroundStyle(PhoneTheme.playerDim)
                    }
                }
                Spacer(minLength: 0)
                HStack(alignment: .bottom) {
                    if !content.heat.isEmpty {
                        Text(content.heat)
                            .font(.system(size: m.sp(10)))
                            .foregroundStyle(PhoneTheme.playerText)
                    }
                    Spacer(minLength: 0)
                    if !content.episode.isEmpty {
                        Text(content.episode)
                            .font(.system(size: m.sp(10)))
                            .foregroundStyle(PhoneTheme.playerText)
                    }
                }
            }
            .padding(m.dp(5))
            .background {
                LinearGradient(colors: [.black.opacity(0.28), .clear, .black.opacity(0.34)],
                               startPoint: .top, endPoint: .bottom)
                    .clipShape(RoundedRectangle(cornerRadius: m.dp(PhoneDimen.radiusCard), style: .continuous))
            }

            if let progress {
                VStack {
                    Spacer()
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Rectangle().fill(Color.white.opacity(0.22))
                            Rectangle().fill(theme.accent)
                                .frame(width: geo.size.width * CGFloat(min(100, max(0, progress))) / 100)
                        }
                    }
                    .frame(height: max(2, m.dp(PhoneDimen.trackHeight)))
                }
                .clipShape(RoundedRectangle(cornerRadius: m.dp(PhoneDimen.radiusCard), style: .continuous))
            }
        }
        .aspectRatio(3.0 / 4.0, contentMode: .fit)
        .clipped()
    }

    private var cornerBackground: LinearGradient {
        let colors = LabelStyle.colors(series.label, series.labelBg).map { Color(rgb: $0) }
        if colors.count >= 2 {
            return LinearGradient(colors: Array(colors.prefix(2)), startPoint: .topLeading, endPoint: .bottomTrailing)
        }
        if let first = colors.first {
            return LinearGradient(colors: [first, first], startPoint: .top, endPoint: .bottom)
        }
        return LinearGradient(colors: [.black.opacity(0.45), .black.opacity(0.45)],
                              startPoint: .top, endPoint: .bottom)
    }
}

enum PosterText {
    /// 第二行左侧的类型标签：最多三个、` · ` 连接（与 `PosterCardContent.tags` 同一条规则）。
    static func tags(_ raw: String) -> String {
        raw.split(whereSeparator: { ",·、|".contains($0) })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.contains("集") }
            .prefix(3)
            .joined(separator: " · ")
    }
}

/// 自适应海报网格：列数 1 / 2 / 3，间距与页边距都按设计令牌。
struct PosterGrid<Content: View>: View {
    let columns: Int
    var spacing: CGFloat = PhoneDimen.posterGap
    @ViewBuilder var content: Content

    @Environment(\.metrics) private var m

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: m.dp(spacing)),
                                 count: max(1, min(3, columns))),
                  spacing: m.dp(spacing)) {
            content
        }
        .padding(.horizontal, m.dp(PhoneDimen.pageInset))
    }
}
