// SPDX-License-Identifier: GPL-3.0-only
//
// 选集网格：详情页与播放页的选集抽屉共用一份（与 Android 的 `PhoneEpisodeGrid` 同构）。
//
// 每行 5 个、分页集数跟设置（20 / 30 / 50 / 80 / 100），页码行给出
// 「第 1—20 集 · 共 99 集」与可横滑的页码 chips。

import SwiftUI

struct EpisodeGrid: View {
    let count: Int
    var current: Int = 0
    var pageSize: Int = AppConfig.LibraryDefaults.episodePageSize
    var compact: Bool = false
    var onSelect: (Int) -> Void

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    @State private var page = 0

    private var perPage: Int { max(5, pageSize) }
    private var pages: Int { EpisodePaging.pages(count, perPage: perPage) }

    var body: some View {
        VStack(alignment: .leading, spacing: m.dp(6)) {
            if pages > 1 {
                HStack(spacing: m.dp(6)) {
                    Text(EpisodePaging.rangeText(count, perPage: perPage, page: page))
                        .font(.system(size: m.sp(11)))
                        .foregroundStyle(theme.textFaint)
                        .lineLimit(1)
                    Spacer(minLength: m.dp(4))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: m.dp(4)) {
                            ForEach(0..<pages, id: \.self) { index in
                                Button {
                                    page = index
                                } label: {
                                    Text(pageLabel(index))
                                        .font(.system(size: m.sp(11)))
                                        .foregroundStyle(index == page ? theme.onLit : theme.textDim)
                                        .padding(.horizontal, m.dp(7))
                                        .padding(.vertical, m.dp(2))
                                        .background {
                                            Capsule().fill(index == page ? theme.lit : theme.surface)
                                        }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .frame(maxWidth: m.dp(compact ? 140 : 200))
                }
            }

            let range = EpisodePaging.range(count, perPage: perPage, page: page)
            if let range {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: m.dp(4)), count: 5),
                          spacing: m.dp(4)) {
                    ForEach(Array(range), id: \.self) { index in
                        Button {
                            onSelect(index)
                        } label: {
                            Text("\(index + 1)")
                                .font(.system(size: m.sp(compact ? 12 : 13),
                                              weight: index == current ? .bold : .regular))
                                .foregroundStyle(index == current ? theme.accent : theme.text)
                                .frame(maxWidth: .infinity)
                                .frame(height: m.dp(compact ? 30 : 34))
                                .background {
                                    RoundedRectangle(cornerRadius: m.dp(6), style: .continuous)
                                        .fill(index == current ? theme.surfaceHigh : theme.surface)
                                }
                                .overlay {
                                    if index == current {
                                        RoundedRectangle(cornerRadius: m.dp(6), style: .continuous)
                                            .strokeBorder(theme.accent, lineWidth: 1)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .onAppear { page = min(pages - 1, max(0, current / perPage)) }
        .onChange(of: current) { _, value in
            let target = min(pages - 1, max(0, value / perPage))
            if target != page { page = target }
        }
    }

    private func pageLabel(_ index: Int) -> String {
        let from = index * perPage + 1
        let to = min(count, (index + 1) * perPage)
        return "\(from)-\(to)"
    }
}
