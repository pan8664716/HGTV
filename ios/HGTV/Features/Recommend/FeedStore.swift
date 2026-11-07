// SPDX-License-Identifier: GPL-3.0-only
//
// 推荐流的数据与播放：一条体裁流一个实例（推荐 / 漫剧 / AI剧 / 真人）。
//
// 与 Android `PhoneFeedView` 同一条策略：
// * 一屏一集、上下滑切换、进屏即播；
// * 信息流**不写历史**（只在会话里记位置，从这一屏点进正式播放页时能接着播）；
// * 连续几部放不了就自动换下一部，不摆错误页；
// * 曝光窗口 1200 条 / 7 天，剧名也进窗口，避免刷一会儿就重复。

import AVFoundation
import SwiftUI

@MainActor
final class FeedStore: ObservableObject {
    let genre: RankBoard

    @Published private(set) var items: [Series] = []
    @Published private(set) var currentIndex = 0
    @Published private(set) var loading = false
    @Published private(set) var errorText: String?
    @Published private(set) var isPlaying = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var episodeIndex = 0
    @Published private(set) var episodeCount = 0
    @Published private(set) var buffering = false

    let player = AVPlayer()

    private var sessionId = ""
    private var offset = 0
    private var hasMore = true
    private var window = RecommendWindow()
    private var details: [String: SeriesDetail] = [:]
    private var loadTask: Task<Void, Never>?
    private var playTask: Task<Void, Never>?
    private var timeObserver: Any?
    private var failures = 0
    private var temporarySpeed = false
    private var userSpeed: Float = 1

    init(genre: RankBoard) {
        self.genre = genre
        player.actionAtItemEnd = .pause
        player.automaticallyWaitsToMinimizeStalling = true
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
                                                      queue: .main) { [weak self] time in
            Task { @MainActor in self?.tick(time) }
        }
        NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime,
                                               object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.handleEnded() }
        }
    }

    deinit {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
    }

    var current: Series? { items.indices.contains(currentIndex) ? items[currentIndex] : nil }

    /// 播放设置面板要显示的当前倍速。
    var currentSpeed: Float { userSpeed }

    /// 清晰度 / 画面模式改完之后重新取一次地址。
    func reloadStream() { playCurrent() }

    // MARK: - 数据

    func loadFirstPage() {
        guard items.isEmpty else { return }
        loadMore(force: true)
    }

    func loadMore(force: Bool = false) {
        guard !loading, hasMore || force else { return }
        loading = true
        errorText = nil
        let offset = self.offset
        let session = self.sessionId
        let genres = [genre.genre].compactMap { $0 }
        let gender = Library.shared.prefs.recommendGender
        let filterIds = window.filterIds
        loadTask = Task { [weak self] in
            guard let self else { return }
            do {
                let feed = try await ContentRepository.shared.recommendFeed(
                    sessionId: session, offset: offset, genres: genres,
                    gender: gender, filterIds: filterIds)
                await MainActor.run {
                    self.loading = false
                    self.sessionId = feed.sessionId
                    self.offset = feed.nextOffset
                    self.hasMore = feed.page.hasMore
                    var added = 0
                    for series in feed.page.items {
                        if self.items.contains(where: { $0.id == series.id }) { continue }
                        if self.window.contains(series) && !force { continue }
                        self.window.remember(series)
                        self.items.append(series)
                        added += 1
                    }
                    if added == 0 && self.hasMore { self.loadMore() }
                    if self.items.isEmpty { self.errorText = "推荐暂时没有内容" }
                }
            } catch {
                await MainActor.run {
                    self.loading = false
                    self.hasMore = false
                    if self.items.isEmpty { self.errorText = "推荐加载失败，下拉重试" }
                }
            }
        }
    }

    func reset() {
        loadTask?.cancel()
        playTask?.cancel()
        player.pause()
        items = []
        sessionId = ""
        offset = 0
        hasMore = true
        errorText = nil
        currentIndex = 0
        loadFirstPage()
    }

    // MARK: - 播放

    func setCurrent(_ index: Int, active: Bool) {
        guard items.indices.contains(index) else { return }
        if index != currentIndex {
            currentIndex = index
            if active { playCurrent() }
        } else if active && !isPlaying {
            playCurrent()
        }
        // 接近末尾：提前取下一页。
        if index >= items.count - 3 { loadMore() }
    }

    func setActive(_ active: Bool) {
        if active {
            playCurrent()
        } else {
            player.pause()
            isPlaying = false
        }
    }

    func playCurrent() {
        guard let series = current else { return }
        playTask?.cancel()
        playTask = Task { [weak self] in
            guard let self else { return }
            self.errorText = nil
            do {
                let detail: SeriesDetail
                if let cached = self.details[series.id] {
                    detail = cached
                } else {
                    detail = try await ContentRepository.shared.detail(id: series.id)
                    self.details[series.id] = detail
                }
                guard !detail.episodes.isEmpty else {
                    // 预告片没有分集：跳过这一部，换下一部。
                    self.skipToNext()
                    return
                }
                let saved = Library.shared.progress(series.id)
                let index = saved.flatMap { detail.episodes.firstIndex(of: $0.episodeId) } ?? 0
                let vid = detail.episodes[min(index, detail.episodes.count - 1)]
                self.episodeIndex = index
                self.episodeCount = detail.episodes.count
                let info = try await ContentRepository.shared.stream(
                    id: vid, maxQuality: Library.shared.prefs.maxQuality)
                let local = try await OfflineVideo.shared.prepare(vid: vid, quality: info.quality, info: info)
                guard let url = URL(string: local.absoluteString) else {
                    throw ContentError.message("播放地址格式异常")
                }
                await MainActor.run {
                    self.buffering = true
                    let item = AVPlayerItem(url: url)
                    self.player.replaceCurrentItem(with: item)
                    self.player.playImmediately(atRate: self.userSpeed)
                    self.isPlaying = true
                    self.failures = 0
                }
            } catch {
                await MainActor.run {
                    self.buffering = false
                    self.failures += 1
                    if self.failures <= 2 {
                        self.skipToNext()
                    } else {
                        self.errorText = "这一条流暂时放不了，上滑换一部"
                    }
                }
            }
        }
    }

    func togglePlay() {
        if isPlaying {
            player.pause()
            isPlaying = false
        } else {
            player.play()
            isPlaying = true
        }
    }

    /// 长按倍速：松手恢复（与 Android 的 2× 长按同一条）。
    func setTemporarySpeed(_ fast: Bool) {
        temporarySpeed = fast
        player.rate = fast ? 2 : userSpeed
    }

    func setSpeed(_ value: Float) {
        userSpeed = value
        if !temporarySpeed { player.rate = value }
    }

    func seek(to fraction: Double) {
        guard duration > 0 else { return }
        let target = CMTime(seconds: duration * min(1, max(0, fraction)), preferredTimescale: 600)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
        position = target.seconds
    }

    func skipToNext() {
        if currentIndex + 1 < items.count {
            currentIndex += 1
            playCurrent()
        } else {
            loadMore(force: true)
        }
    }

    private func tick(_ time: CMTime) {
        position = time.seconds.isFinite ? time.seconds : 0
        let itemDuration = player.currentItem?.duration.seconds ?? 0
        if itemDuration.isFinite && itemDuration > 0 { duration = itemDuration }
        buffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
    }

    private func handleEnded() {
        // 一集播完自动接下一集；没有下一集就换下一部（与 Android 的自动连播一致）。
        guard Library.shared.prefs.autoNext, let series = current else { return }
        Task { [weak self] in
            guard let self else { return }
            guard let detail = self.details[series.id] else { return }
            if self.episodeIndex + 1 < detail.episodes.count {
                self.episodeIndex += 1
                do {
                    let vid = detail.episodes[self.episodeIndex]
                    let info = try await ContentRepository.shared.stream(
                        id: vid, maxQuality: Library.shared.prefs.maxQuality)
                    let local = try await OfflineVideo.shared.prepare(vid: vid, quality: info.quality, info: info)
                    if let url = URL(string: local.absoluteString) {
                        self.player.replaceCurrentItem(with: AVPlayerItem(url: url))
                        self.player.play()
                    }
                } catch {
                    self.skipToNext()
                }
            } else {
                self.skipToNext()
            }
        }
    }
}
