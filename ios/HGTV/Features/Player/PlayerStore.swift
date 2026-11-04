// SPDX-License-Identifier: GPL-3.0-only
//
// 正式播放页的数据与状态：与 Android `PhonePlayerActivity` 的核心链路一致。
//
// 起播顺序：详情（拿分集 vid）→ 取地址（上限取设置里的清晰度）→ 播放；
// 进度每 5 秒落盘一次 + 离开时落一次；一集播完按「自动连播」接下一集；
// 失败按 `PlaybackDegradation` 的规则先同档重建、再降档，绝不改用户设置。

import AVFoundation
import SwiftUI

@MainActor
final class PlayerStore: ObservableObject {
    let seriesId: String
    let player = AVPlayer()

    @Published private(set) var series: Series
    @Published private(set) var episodes: [String] = []
    @Published private(set) var index = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var buffering = false
    @Published private(set) var errorText: String?
    @Published private(set) var danmaku: [DanmakuItem] = []
    @Published var chromeVisible = true
    @Published private(set) var speed: Float = 1
    @Published private(set) var quality: Int = 1080
    @Published private(set) var sleepDeadline: Date?

    private var timeObserver: Any?
    private var saveTimer: Timer?
    private var hideTask: Task<Void, Never>?
    private var playTask: Task<Void, Never>?
    private var danmakuTask: Task<Void, Never>?
    private var degradation = PlaybackDegradation.State(rebuilds: 0, drops: 0, level: 1080)
    private var requestedEpisode: Int?

    init(seriesId: String, title: String, episode: Int?) {
        self.seriesId = seriesId
        self.series = Series(id: seriesId, title: title)
        self.requestedEpisode = episode
        self.speed = Library.shared.prefs.playbackSpeed
        self.quality = Library.shared.prefs.maxQuality
        player.actionAtItemEnd = .pause
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
                                                      queue: .main) { [weak self] time in
            Task { @MainActor in self?.tick(time) }
        }
        NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime,
                                               object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.handleEnded() }
        }
        saveTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.saveProgress() }
        }
    }

    deinit {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        saveTimer?.invalidate()
    }

    // MARK: 起播

    func start() async {
        do {
            let detail = try await ContentRepository.shared.detail(id: seriesId)
            series = detail.series
            episodes = detail.episodes
            guard !episodes.isEmpty else {
                errorText = "这部还没有可播放的分集"
                return
            }
            let resume = Library.shared.progress(seriesId)
            var start = requestedEpisode
                ?? resume.flatMap { detail.episodes.firstIndex(of: $0.episodeId) }
                ?? 0
            start = min(max(0, start), episodes.count - 1)
            index = start
            await play(index: start, resumeAt: requestedEpisode == nil ? (resume?.position ?? 0) : 0)
        } catch {
            errorText = "详情加载失败，请重试"
        }
    }

    func play(index target: Int, resumeAt: Int64 = 0) async {
        guard episodes.indices.contains(target) else { return }
        playTask?.cancel()
        errorText = nil
        index = target
        let vid = episodes[target]
        playTask = Task { [weak self] in
            guard let self else { return }
            self.buffering = true
            do {
                let info = try await ContentRepository.shared.stream(id: vid, maxQuality: self.quality)
                // CENC 加密的流 AVFoundation 不认：整集取回、改包、解密，再播本地文件。
                let local = try await OfflineVideo.shared.prepare(vid: vid, quality: info.quality, info: info)
                guard let url = URL(string: local.absoluteString) else {
                    throw ContentError.message("播放地址格式异常")
                }
                await MainActor.run {
                    self.player.replaceCurrentItem(with: AVPlayerItem(url: url))
                    if resumeAt > 0 {
                        self.player.seek(to: CMTime(value: resumeAt, timescale: 1000),
                                         toleranceBefore: .zero, toleranceAfter: .zero)
                    }
                    self.player.playImmediately(atRate: self.speed)
                    self.isPlaying = true
                    self.buffering = false
                    self.degradation = PlaybackDegradation.State(rebuilds: 0, drops: 0, level: self.quality)
                    self.scheduleChromeHide()
                }
                await self.loadDanmaku(vid: vid)
            } catch {
                await MainActor.run {
                    self.buffering = false
                    self.handlePlaybackFailure(error)
                }
            }
        }
    }

    private func handlePlaybackFailure(_ error: Error) {
        // 先同档重建一次；再失败就按规则降档（每集最多两档），绝不改用户设置。
        switch PlaybackDegradation.decide(code: 4001, state: degradation) {
        case .rebuild:
            degradation.rebuilds += 1
            Task { await play(index: index, resumeAt: Int64(position)) }
        case .drop(let level):
            degradation.drops += 1
            degradation.level = level
            Task {
                do {
                    let vid = episodes[index]
                    let info = try await ContentRepository.shared.stream(id: vid, maxQuality: level)
                    if let url = URL(string: info.url) {
                        player.replaceCurrentItem(with: AVPlayerItem(url: url))
                        player.playImmediately(atRate: speed)
                    }
                } catch {
                    errorText = "这一集暂时放不了，换一集或稍后再试"
                }
            }
        case .fail:
            errorText = error.localizedDescription.isEmpty ? "播放失败" : error.localizedDescription
        }
    }

    private func loadDanmaku(vid: String) async {
        danmakuTask?.cancel()
        danmakuTask = Task { [weak self] in
            let items = (try? await ContentRepository.shared.danmaku(vid: vid, seriesId: self?.seriesId ?? "")) ?? []
            await MainActor.run { self?.danmaku = items }
        }
    }

    // MARK: 播放控制

    func togglePlay() {
        if isPlaying {
            player.pause()
            isPlaying = false
        } else {
            player.play()
            isPlaying = true
        }
        scheduleChromeHide()
    }

    func setSpeed(_ value: Float) {
        speed = value
        player.rate = value
        Library.shared.prefs.playbackSpeed = value
    }

    func setQuality(_ value: Int) {
        quality = value
        Library.shared.prefs.maxQuality = value
        Task { await play(index: index, resumeAt: Int64(position)) }
    }

    func seek(to fraction: Double) {
        guard duration > 0 else { return }
        let target = duration * min(1, max(0, fraction))
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero)
        position = target
        scheduleChromeHide()
    }

    func next() {
        guard index + 1 < episodes.count else {
            ToastCenter.shared.show("已经是最后一集")
            return
        }
        saveProgress()
        Task { await play(index: index + 1) }
    }

    func previous() {
        guard index > 0 else {
            ToastCenter.shared.show("已经是第一集")
            return
        }
        saveProgress()
        Task { await play(index: index - 1) }
    }

    func toggleChrome() {
        withAnimation(.easeOut(duration: 0.18)) { chromeVisible.toggle() }
        if chromeVisible { scheduleChromeHide() }
    }

    func scheduleChromeHide() {
        hideTask?.cancel()
        let seconds = max(2, Library.shared.prefs.playerPanelTimeout)
        hideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, self.isPlaying else { return }
                withAnimation(.easeOut(duration: 0.18)) { self.chromeVisible = false }
            }
        }
    }

    /// 定时停止：到点暂停并保存进度（与 Android 的按分钟档一致）。
    func setSleepTimer(minutes: Int?) {
        guard let minutes else {
            sleepDeadline = nil
            return
        }
        sleepDeadline = Date().addingTimeInterval(Double(minutes) * 60)
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(minutes) * 60 * 1_000_000_000)
            await MainActor.run {
                guard let self, self.sleepDeadline != nil else { return }
                self.player.pause()
                self.isPlaying = false
                self.saveProgress()
                self.sleepDeadline = nil
                ToastCenter.shared.show("已按定时停止")
            }
        }
    }

    // MARK: 进度

    private func tick(_ time: CMTime) {
        position = time.seconds.isFinite ? max(0, time.seconds) : 0
        let total = player.currentItem?.duration.seconds ?? 0
        if total.isFinite && total > 0 { duration = total }
        buffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        isPlaying = player.timeControlStatus == .playing
    }

    func saveProgress() {
        guard !episodes.isEmpty, duration > 0, position > 0 else { return }
        Library.shared.record(series, episodeId: episodes[index], episodeIndex: index,
                              position: Int64(position), duration: Int64(duration))
    }

    private func handleEnded() {
        saveProgress()
        guard Library.shared.prefs.autoNext else { return }
        if index + 1 < episodes.count {
            Task { await play(index: index + 1) }
        } else {
            ToastCenter.shared.show("全剧播完")
        }
    }

    func stop() {
        saveProgress()
        player.pause()
        hideTask?.cancel()
        playTask?.cancel()
        danmakuTask?.cancel()
    }

    var currentEpisodeTitle: String {
        episodes.isEmpty ? "" : "第\(index + 1)集"
    }
}
