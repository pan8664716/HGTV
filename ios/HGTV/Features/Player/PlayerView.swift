// SPDX-License-Identifier: GPL-3.0-only
//
// 正式播放页：竖屏全屏 + 手势 + 选集抽屉。
//
// | 手势 | 行为 |
// | --- | --- |
// | 单击 | 播放 / 暂停（中心出现播放 / 暂停按钮） |
// | 长按左 / 右 1/3 | 2 倍速，松手恢复 |
// | 长按中间 1/3 | 播放设置（倍速 / 清晰度 / 画面模式） |
// | 左右拖 | 调进度 |
// | 上下滑 | 上一集 / 下一集 |
//
// 与 Android 的区别只有平台习惯：转场用系统的 push/pop，抽屉用系统 sheet，
// 返回手势用 NavigationStack 的侧滑。

import SwiftUI
import AVFoundation

struct PlayerView: View {
    let seriesId: String
    let title: String
    let episode: Int?
    let fromRecommend: Bool

    @ObservedObject private var library = Library.shared
    @Environment(\.dismiss) private var dismiss
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @Environment(\.safeAreaInsets) private var insets

    @StateObject private var store: PlayerStore
    @State private var showEpisodes = false
    @State private var showSettings = false
    @State private var showPlayIcon = false
    @State private var hint: String?
    @State private var dragStart: Double?
    @State private var dragFraction: Double?
    @State private var pressPoint: CGPoint = .zero
    @State private var longPressed = false

    init(seriesId: String, title: String, episode: Int?, fromRecommend: Bool) {
        self.seriesId = seriesId
        self.title = title
        self.episode = episode
        self.fromRecommend = fromRecommend
        _store = StateObject(wrappedValue: PlayerStore(seriesId: seriesId, title: title, episode: episode))
    }

    private var fraction: Double {
        if let dragFraction { return dragFraction }
        guard store.duration > 0 else { return 0 }
        return min(1, max(0, store.position / store.duration))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VideoSurface(player: store.player, gravity: gravity,
                         scale: CGFloat(library.prefs.videoScale) / 100)
                .ignoresSafeArea()

            DanmakuLayer(items: store.danmaku, position: store.position)
                .ignoresSafeArea()

            gestureLayer
            topBar
            if store.chromeVisible { bottomChrome }
            centerIcon

            if let hint {
                Text(hint)
                    .font(.system(size: m.sp(14), weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, m.dp(14))
                    .padding(.vertical, m.dp(7))
                    .background(Color.black.opacity(0.55), in: Capsule())
            }

            if store.buffering {
                ProgressView().tint(.white)
            }

            if let error = store.errorText {
                errorOverlay(error)
            }

            if let deadline = store.sleepDeadline {
                VStack {
                    HStack {
                        Spacer()
                        Text("定时停止 · \(max(0, Int(deadline.timeIntervalSinceNow / 60))) 分钟")
                            .font(.system(size: m.sp(11)))
                            .foregroundStyle(.white.opacity(0.8))
                            .padding(.horizontal, m.dp(8))
                            .padding(.vertical, m.dp(4))
                            .background(Color.black.opacity(0.4), in: Capsule())
                            .padding(.trailing, m.dp(12))
                            .padding(.top, insets.top + m.dp(6))
                    }
                    Spacer()
                }
            }
        }
        .statusBarHidden(true)
        .navigationBarBackButtonHidden(true)
        .task { await store.start() }
        .onDisappear { store.stop() }
        .sheet(isPresented: $showEpisodes) {
            episodeSheet
                .presentationDetents([.height(m.dp(360)), .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showSettings) {
            playerSettings
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    private var gravity: AVLayerVideoGravity {
        switch library.prefs.frameMode {
        case .fit: return .resizeAspect
        case .zoom: return .resizeAspectFill
        case .fill: return .resize
        }
    }

    // MARK: 手势

    private var gestureLayer: some View {
        Color.clear
            .contentShape(Rectangle())
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in pressPoint = value.startLocation }
            )
            .onTapGesture {
                store.togglePlay()
                withAnimation(.easeOut(duration: 0.12)) { showPlayIcon = true }
                Task {
                    try? await Task.sleep(nanoseconds: 450_000_000)
                    withAnimation(.easeOut(duration: 0.18)) { showPlayIcon = false }
                }
            }
            .onLongPressGesture(minimumDuration: 0.35) {
                let ratio = pressPoint.x / max(1, UIScreen.main.bounds.width)
                if ratio > 0.34 && ratio < 0.66 {
                    showSettings = true
                    return
                }
                longPressed = true
                store.player.rate = 2
                hint = "2× 快进中"
            } onPressingChanged: { pressing in
                if !pressing && longPressed {
                    longPressed = false
                    store.player.rate = store.speed
                    hint = nil
                }
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 16)
                    .onChanged { value in
                        let dx = value.translation.width
                        let dy = value.translation.height
                        guard abs(dx) > abs(dy) * 1.3 else { return }
                        guard store.duration > 0 else { return }
                        if dragStart == nil { dragStart = store.position }
                        let delta = Double(dx) / Double(max(1, UIScreen.main.bounds.width)) * store.duration
                        dragFraction = min(1, max(0, ((dragStart ?? 0) + delta) / store.duration))
                        hint = PlaybackInfoLine.time(Int64((dragFraction ?? 0) * store.duration))
                    }
                    .onEnded { value in
                        let dx = value.translation.width
                        let dy = value.translation.height
                        if abs(dx) > abs(dy) * 1.3 {
                            if let value = dragFraction { store.seek(to: value) }
                        } else if abs(dy) > 60 {
                            dy < 0 ? store.next() : store.previous()
                        }
                        dragStart = nil
                        dragFraction = nil
                        hint = nil
                    }
            )
    }

    // MARK: 顶栏

    private var topBar: some View {
        VStack {
            HStack(alignment: .top, spacing: m.dp(8)) {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: m.sp(17), weight: .semibold))
                        .foregroundStyle(PhoneTheme.playerText)
                        .frame(width: m.dp(36), height: m.dp(36))
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: m.dp(2)) {
                    Text(store.series.title.isEmpty ? title : store.series.title)
                        .font(.system(size: m.sp(15), weight: .bold))
                        .foregroundStyle(PhoneTheme.playerText)
                        .lineLimit(1)
                    if library.prefs.playerEpisode || library.prefs.playerClock || library.prefs.playerSpeed {
                        HStack(spacing: m.dp(8)) {
                            if library.prefs.playerEpisode {
                                Text(store.currentEpisodeTitle)
                            }
                            if library.prefs.playerClock {
                                Text(PlaybackInfoLine.time(Int64(store.position)))
                            }
                            if library.prefs.playerSpeed {
                                Text(PlaybackSpeed.label(store.speed))
                            }
                        }
                        .font(.system(size: m.sp(11)))
                        .foregroundStyle(PhoneTheme.playerDim)
                        .monospacedDigit()
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, m.dp(8))
            .padding(.top, insets.top + m.dp(2))
            .padding(.bottom, m.dp(8))
            .background(TopScrim(alpha: 0.55).ignoresSafeArea(edges: .top))
            Spacer()
        }
        .opacity(store.chromeVisible ? 1 : 0)
        .animation(.easeOut(duration: 0.18), value: store.chromeVisible)
    }

    private var centerIcon: some View {
        Group {
            if showPlayIcon {
                Image(systemName: store.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: m.sp(22), weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: m.dp(48), height: m.dp(48))
                    .background(Color.black.opacity(0.32), in: Circle())
            }
        }
    }

    // MARK: 底部控制

    private var bottomChrome: some View {
        VStack {
            Spacer()
            VStack(spacing: m.dp(4)) {
                HStack(spacing: m.dp(8)) {
                    Text(PlaybackInfoLine.time(Int64(store.position)))
                        .font(.system(size: m.sp(11)))
                        .foregroundStyle(PhoneTheme.playerText)
                        .monospacedDigit()
                    progressBar
                    Text(PlaybackInfoLine.time(Int64(store.duration)))
                        .font(.system(size: m.sp(11)))
                        .foregroundStyle(PhoneTheme.playerDim)
                        .monospacedDigit()
                }
                .padding(.horizontal, m.dp(12))

                HStack(spacing: 0) {
                    control("选集", "list.bullet") { showEpisodes = true }
                    control("倍速", "speedometer") { showSettings = true }
                    control("弹幕", "text.bubble") {
                        let next: DanmakuMode = library.prefs.danmakuMode == .hidden ? .full : .hidden
                        library.prefs.danmakuMode = next
                        ToastCenter.shared.show(next == .hidden ? "弹幕已隐藏" : "弹幕已开启")
                    }
                    control("设置", "slider.horizontal.3") { showSettings = true }
                    control("定时", "moon.zzz") {
                        store.setSleepTimer(minutes: store.sleepDeadline == nil ? 30 : nil)
                        ToastCenter.shared.show(store.sleepDeadline == nil ? "已取消定时" : "30 分钟后停止")
                    }
                }
                .padding(.horizontal, m.dp(6))
                .padding(.bottom, insets.bottom + m.dp(6))
            }
            .padding(.top, m.dp(10))
            .background(BottomScrim(alpha: 0.85).ignoresSafeArea(edges: .bottom))
        }
        .opacity(store.chromeVisible ? 1 : 0)
        .animation(.easeOut(duration: 0.18), value: store.chromeVisible)
    }

    private func control(_ label: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: m.dp(3)) {
                Image(systemName: icon)
                    .font(.system(size: m.sp(14)))
                Text(label)
                    .font(.system(size: m.sp(10)))
            }
            .foregroundStyle(PhoneTheme.playerText)
            .frame(maxWidth: .infinity)
            .padding(.vertical, m.dp(4))
        }
        .buttonStyle(.plain)
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
            .frame(height: m.dp(14))
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
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
        .frame(height: m.dp(14))
    }

    // MARK: 抽屉

    private var episodeSheet: some View {
        VStack(alignment: .leading, spacing: m.dp(10)) {
            HStack {
                Text("选集 · 全\(store.episodes.count)集")
                    .font(.system(size: m.sp(15), weight: .bold))
                    .foregroundStyle(theme.text)
                Spacer()
                Button { showEpisodes = false } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: m.sp(13), weight: .semibold))
                        .foregroundStyle(theme.textDim)
                }
                .buttonStyle(.plain)
            }
            ScrollView {
                EpisodeGrid(count: store.episodes.count, current: store.index,
                            pageSize: library.prefs.episodePageSize, compact: true) { index in
                    showEpisodes = false
                    Task { await store.play(index: index) }
                }
            }
            .scrollIndicators(.hidden)
        }
        .padding(m.dp(16))
        .background(theme.baseColor)
    }

    private var playerSettings: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: m.dp(10)) {
                HStack {
                    Text("播放设置")
                        .font(.system(size: m.sp(16), weight: .bold))
                        .foregroundStyle(theme.text)
                    Spacer()
                    Button { showSettings = false } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: m.sp(13), weight: .semibold))
                            .foregroundStyle(theme.textDim)
                    }
                    .buttonStyle(.plain)
                }

                Text("倍速").font(.system(size: m.sp(12))).foregroundStyle(theme.textDim)
                HStack(spacing: m.dp(8)) {
                    ForEach(PlaybackSpeed.options, id: \.self) { value in
                        Button { store.setSpeed(value) } label: {
                            ChipLabel(text: PlaybackSpeed.label(value),
                                      selected: abs(store.speed - value) < 0.01, inset: true)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Text("清晰度上限").font(.system(size: m.sp(12))).foregroundStyle(theme.textDim)
                HStack(spacing: m.dp(8)) {
                    ForEach(PlaybackQuality.options, id: \.self) { value in
                        Button { store.setQuality(value) } label: {
                            ChipLabel(text: "\(value)P", selected: store.quality == value, inset: true)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Text("画面模式").font(.system(size: m.sp(12))).foregroundStyle(theme.textDim)
                HStack(spacing: m.dp(8)) {
                    ForEach(VideoFrameMode.allCases, id: \.self) { value in
                        Button { library.prefs.frameMode = value } label: {
                            ChipLabel(text: value.label, selected: library.prefs.frameMode == value, inset: true)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Text("弹幕显示").font(.system(size: m.sp(12))).foregroundStyle(theme.textDim)
                HStack(spacing: m.dp(8)) {
                    ForEach(DanmakuMode.allCases, id: \.self) { value in
                        Button { library.prefs.danmakuMode = value } label: {
                            ChipLabel(text: value.label, selected: library.prefs.danmakuMode == value, inset: true)
                        }
                        .buttonStyle(.plain)
                    }
                }

                SwitchRow(label: "调试模式", isOn: $library.prefs.debugMode)
            }
            .padding(m.dp(18))
        }
        .background(theme.baseColor)
    }

    private func errorOverlay(_ text: String) -> some View {
        VStack(spacing: m.dp(10)) {
            Text(text)
                .font(.system(size: m.sp(14)))
                .foregroundStyle(PhoneTheme.playerText)
            HStack(spacing: m.dp(10)) {
                Button("重试") {
                    Task { await store.play(index: store.index, resumeAt: Int64(store.position)) }
                }
                .buttonStyle(.borderedProminent)
                Button("返回") { dismiss() }
                    .buttonStyle(.bordered)
            }
        }
        .padding(m.dp(20))
        .background(Color.black.opacity(0.72), in: RoundedRectangle(cornerRadius: m.dp(12)))
    }
}
