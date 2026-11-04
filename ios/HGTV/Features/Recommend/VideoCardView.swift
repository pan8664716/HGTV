// SPDX-License-Identifier: GPL-3.0-only
//
// 竖屏播放版面：推荐流与正式播放页共用这一块（与 Android `PhoneVideoPage` 同构）。
//
// 版面（自下而上）：
//   进度条
//   观看完整短剧 / 选集 全N集   ← 通栏方角操作条
//   第 x 集 ｜ 简介
//   标签（第N季 · 题材）
//   标题（带下划线，点进详情）
//
// 手势：单击播放/暂停；长按左右 1/3 = 2 倍速，长按中间 1/3 = 播放设置；
// 左右拖 = 调进度（与竖向翻页互不干扰：只认横向占优的那一段）。

import SwiftUI
import AVFoundation

struct VideoCardView: View {
    let series: Series
    @ObservedObject var store: FeedStore
    let isActive: Bool
    let bottomInset: CGFloat
    let onOpenDetail: () -> Void
    let onOpenPlayer: () -> Void

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @ObservedObject private var library = Library.shared

    @State private var showPlayIcon = false
    @State private var hint: String?
    @State private var dragStart: Double?
    @State private var dragFraction: Double?
    @State private var longPressed = false
    @State private var showSettings = false
    @State private var pressPoint: CGPoint = .zero

    private var displayPosition: Double {
        if let dragFraction { return dragFraction * store.duration }
        return store.position
    }

    private var fraction: Double {
        guard store.duration > 0 else { return 0 }
        return min(1, max(0, displayPosition / store.duration))
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottom) {
                Color.black

                if isActive {
                    VideoSurface(player: store.player,
                                 gravity: gravity,
                                 scale: CGFloat(Library.shared.prefs.videoScale) / 100)
                        .ignoresSafeArea()
                }

                BottomScrim()
                    .frame(height: geo.size.height * 0.5)
                    .allowsHitTesting(false)

                infoColumn(width: geo.size.width)
                    .padding(.bottom, bottomInset + m.dp(32) + m.dp(6))
                    .allowsHitTesting(true)

                actionBar
                    .padding(.bottom, bottomInset)

                if showPlayIcon {
                    Image(systemName: store.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: m.sp(22), weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: m.dp(44), height: m.dp(44))
                        .background(Color.black.opacity(0.32), in: Circle())
                        .transition(.opacity)
                }

                if let hint {
                    Text(hint)
                        .font(.system(size: m.sp(13)))
                        .foregroundStyle(.white)
                        .padding(.horizontal, m.dp(14))
                        .padding(.vertical, m.dp(7))
                        .background(Color.black.opacity(0.55), in: Capsule())
                        .padding(.bottom, geo.size.height * 0.42)
                }

                if store.buffering && isActive {
                    ProgressView().tint(.white).padding(.bottom, geo.size.height * 0.5)
                }

                if let error = store.errorText, isActive {
                    Text(error)
                        .font(.system(size: m.sp(13)))
                        .foregroundStyle(.white.opacity(0.9))
                        .padding(.horizontal, m.dp(14))
                        .padding(.vertical, m.dp(7))
                        .background(Color.black.opacity(0.55), in: Capsule())
                        .padding(.bottom, geo.size.height * 0.46)
                }
            }
            .contentShape(Rectangle())
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if dragStart == nil { pressPoint = value.startLocation }
                    }
            )
            .onTapGesture { handleTap() }
            .onLongPressGesture(minimumDuration: 0.35) {
                handleLongPress(width: geo.size.width)
            } onPressingChanged: { pressing in
                if !pressing && longPressed {
                    longPressed = false
                    store.setTemporarySpeed(false)
                    hint = nil
                }
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 12)
                    .onChanged { value in
                        guard store.duration > 0 else { return }
                        guard abs(value.translation.width) > abs(value.translation.height) * 1.4 else { return }
                        if dragStart == nil {
                            dragStart = store.position
                            showPlayIcon = false
                        }
                        let delta = Double(value.translation.width) / Double(max(1, geo.size.width)) * store.duration
                        dragFraction = min(1, max(0, ((dragStart ?? 0) + delta) / store.duration))
                        hint = PlaybackInfoLine.time(Int64((dragFraction ?? 0) * store.duration))
                    }
                    .onEnded { _ in
                        if let value = dragFraction {
                            store.seek(to: value)
                        }
                        dragStart = nil
                        dragFraction = nil
                        hint = nil
                    }
            )
            .sheet(isPresented: $showSettings) {
                PlaybackSettingsSheet(speed: store.currentSpeed,
                                      onSpeed: { store.setSpeed($0) },
                                      onQuality: { _ in store.reloadStream() })
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
    }

    private var gravity: AVLayerVideoGravity {
        switch Library.shared.prefs.frameMode {
        case .fit: return .resizeAspect
        case .zoom: return .resizeAspectFill
        case .fill: return .resize
        }
    }

    private func handleTap() {
        store.togglePlay()
        withAnimation(.easeOut(duration: 0.15)) { showPlayIcon = true }
        Task {
            try? await Task.sleep(nanoseconds: 500_000_000)
            withAnimation(.easeOut(duration: 0.2)) { showPlayIcon = false }
        }
    }

    private func handleLongPress(width: CGFloat) {
        let ratio = width > 0 ? pressPoint.x / width : 0.5
        if ratio > 0.34 && ratio < 0.66 {
            // 中间 1/3：播放设置（倍速 / 清晰度 / 画面模式），与 Android 的分区一致。
            showSettings = true
            return
        }
        longPressed = true
        store.setTemporarySpeed(true)
        hint = "2× 快进中"
    }

    // MARK: 信息区

    private func infoColumn(width: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: m.dp(6)) {
            Button(action: onOpenDetail) {
                Text(series.title)
                    .font(.system(size: m.sp(17), weight: .bold))
                    .foregroundStyle(.white)
                    .underline()
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)

            HStack(spacing: m.dp(6)) {
                if !tagText.isEmpty {
                    Text(tagText)
                        .font(.system(size: m.sp(12)))
                        .foregroundStyle(.white.opacity(0.85))
                }
            }

            HStack(spacing: 0) {
                Text("第\(store.episodeIndex + 1)集")
                    .font(.system(size: m.sp(13), weight: .bold))
                    .foregroundStyle(.white)
                Text(" ｜ ")
                    .font(.system(size: m.sp(13)))
                    .foregroundStyle(.white.opacity(0.5))
                Text(series.description.replacingOccurrences(of: "\n", with: " "))
                    .font(.system(size: m.sp(13)))
                    .foregroundStyle(.white.opacity(0.8))
                    .lineLimit(1)
            }

            progressBar
                .padding(.top, m.dp(2))
        }
        .padding(.horizontal, m.dp(6))
        .padding(.bottom, m.dp(8))
    }

    private var tagText: String {
        let season = SeriesSeasons.badge(series)
        let tags = PosterText.tags(series.tags)
        return [season, tags].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private var progressBar: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.25))
                Capsule().fill(theme.accent)
                    .frame(width: geo.size.width * fraction)
                Circle()
                    .fill(theme.accent)
                    .frame(width: m.dp(8), height: m.dp(8))
                    .offset(x: max(0, geo.size.width * fraction - m.dp(4)))
            }
            .frame(height: m.dp(12))
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard store.duration > 0 else { return }
                        dragFraction = min(1, max(0, value.location.x / max(1, geo.size.width)))
                        hint = PlaybackInfoLine.time(Int64((dragFraction ?? 0) * store.duration))
                    }
                    .onEnded { _ in
                        if let value = dragFraction { store.seek(to: value) }
                        dragFraction = nil
                        hint = nil
                    }
            )
        }
        .frame(height: m.dp(12))
    }

    private var actionBar: some View {
        HStack(spacing: m.dp(6)) {
            Button(action: onOpenPlayer) {
                HStack(spacing: m.dp(6)) {
                    PlayGlyph(tint: theme.accent)
                        .frame(width: m.dp(12), height: m.dp(12))
                    Text("观看完整短剧")
                        .font(.system(size: m.sp(14), weight: .bold))
                        .foregroundStyle(.white)
                    Spacer(minLength: 0)
                    if store.episodeCount > 0 {
                        Text("选集 全\(store.episodeCount)集")
                            .font(.system(size: m.sp(12)))
                            .foregroundStyle(.white.opacity(0.72))
                    }
                }
                .padding(.leading, m.dp(2))
                .padding(.trailing, m.dp(14))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
        }
        .frame(height: m.dp(32))
        .background {
            theme.surface.opacity(0.42)
                .overlay(alignment: .top) {
                    Rectangle().fill(theme.shadowLight.opacity(0.3)).frame(height: 0.5)
                }
                .ignoresSafeArea(edges: .horizontal)
        }
    }
}
