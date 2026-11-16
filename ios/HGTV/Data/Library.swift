// SPDX-License-Identifier: GPL-3.0-only
//
// 本机书库：收藏 / 稍后看 / 历史 / 进度 / 隐藏 / 搜索记录 + 全部偏好。
//
// 与 Android `shared/data/Library.kt` 的语义逐项一致：
// * 一剧一条进度，`completed` 的判据是「离片尾不到 3 秒」；
// * 收藏与稍后看存整张卡片（离线也能显示），历史存进度；
// * 偏好是同一套键名与同一套默认值，导出的备份可以互相合并。
//
// 落盘位置不同（Android 是 SharedPreferences + 一剧一文件，iOS 是 Application Support
// 下的两个 JSON），这是平台差异，不影响语义。

import Foundation
import SwiftUI

// MARK: - 偏好

struct LibraryPrefs: Codable, Equatable {
    var maxQuality: Int = AppConfig.LibraryDefaults.maxQuality
    var playbackSpeed: Float = AppConfig.LibraryDefaults.playbackSpeed
    var frameMode: VideoFrameMode = .fit
    var decoderMode: VideoDecoderMode = .hardware
    var videoScale: Float = AppConfig.LibraryDefaults.videoScale
    var autoNext: Bool = true
    var seekStep: Int = AppConfig.LibraryDefaults.seekStep
    var autoFullscreen: Bool = false

    var playerTitle: Bool = false
    var playerEpisode: Bool = false
    var playerClock: Bool = false
    var playerSpeed: Bool = false
    var playerPanelTimeout: Int = AppConfig.LibraryDefaults.playerPanelTimeout

    var danmakuMode: DanmakuMode = .full
    var danmakuSpeed: DanmakuSpeed = .normal
    var danmakuMerge: Bool = true
    var danmakuMergeMin: Int = AppConfig.LibraryDefaults.danmakuMergeMin
    var danmakuAlpha: Float = AppConfig.LibraryDefaults.danmakuAlpha
    var danmakuSizePercent: Int = AppConfig.LibraryDefaults.danmakuSizePercent

    var gridColumns: Int = AppConfig.LibraryDefaults.gridColumns
    var episodePageSize: Int = AppConfig.LibraryDefaults.episodePageSize
    var episodeEdgeTurn: Bool = true
    var searchSource: SearchSource = .app
    var reduceAnim: Bool = false
    var debugMode: Bool = false

    var launchRecommend: Bool = true
    var recommendSwipe: Bool = true
    var recommendGender: String = ""
    var recommendGenreOff: [String] = []

    var theme: String = PhoneTheme.deepSeaBlue.storedValue
    var uiScale: Float = AppConfig.LibraryDefaults.uiScale

    var isRecommendGenreEnabled: Bool { true }

    func genreEnabled(_ id: String) -> Bool { !recommendGenreOff.contains(id) }
}

// MARK: - 书库

struct LibraryData: Codable {
    var favorites: [Series] = []
    var later: [Series] = []
    var history: [WatchProgress] = []
    var hidden: [String] = []
    var searches: [String] = []
    var progress: [String: WatchProgress] = [:]
}

@MainActor
final class Library: ObservableObject {
    static let shared = Library()

    @Published private(set) var data: LibraryData
    @Published var prefs: LibraryPrefs {
        didSet { scheduleSave() }
    }

    private let folder: URL
    private let dataURL: URL
    private let prefsURL: URL
    private var saveTask: Task<Void, Never>?

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        folder = base.appendingPathComponent("HGTV", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        dataURL = folder.appendingPathComponent("library.json")
        prefsURL = folder.appendingPathComponent("prefs.json")

        let decoder = JSONDecoder()
        data = FileManager.default.contents(atPath: dataURL.path)
            .flatMap { try? decoder.decode(LibraryData.self, from: $0) } ?? LibraryData()
        prefs = FileManager.default.contents(atPath: prefsURL.path)
            .flatMap { try? decoder.decode(LibraryPrefs.self, from: $0) } ?? LibraryPrefs()
        migrateDefaultsIfNeeded()
    }

    /// 旧版默认倍率是 1.3，升级后只迁移这一个默认值（与 `PhoneUiScale` 同一条规则）。
    private func migrateDefaultsIfNeeded() {
        let key = "uiScaleDefaultMigrated"
        if !UserDefaults.standard.bool(forKey: key) {
            if abs(prefs.uiScale - AppConfig.LibraryDefaults.legacyUiScale) < 0.001 {
                prefs.uiScale = AppConfig.LibraryDefaults.uiScale
            }
            UserDefaults.standard.set(true, forKey: key)
            scheduleSave()
        }
    }

    // MARK: 落盘

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: AppConfig.LibraryDefaults.saveDebounceNanoseconds)
            guard !Task.isCancelled else { return }
            self?.saveNow()
        }
    }

    func saveNow() {
        let encoder = JSONEncoder()
        if let payload = try? encoder.encode(data) {
            try? payload.write(to: dataURL, options: .atomic)
        }
        if let payload = try? encoder.encode(prefs) {
            try? payload.write(to: prefsURL, options: .atomic)
        }
    }

    // MARK: 收藏 / 稍后看

    func isFavorite(_ id: String) -> Bool { data.favorites.contains { $0.id == id } }
    func isLater(_ id: String) -> Bool { data.later.contains { $0.id == id } }

    @discardableResult
    func toggleFavorite(_ series: Series) -> Bool {
        if let index = data.favorites.firstIndex(where: { $0.id == series.id }) {
            data.favorites.remove(at: index)
            scheduleSave()
            return false
        }
        data.favorites.insert(series, at: 0)
        scheduleSave()
        return true
    }

    @discardableResult
    func toggleLater(_ series: Series) -> Bool {
        if let index = data.later.firstIndex(where: { $0.id == series.id }) {
            data.later.remove(at: index)
            scheduleSave()
            return false
        }
        data.later.insert(series, at: 0)
        scheduleSave()
        return true
    }

    func removeFavorite(_ id: String) {
        data.favorites.removeAll { $0.id == id }
        scheduleSave()
    }

    func removeLater(_ id: String) {
        data.later.removeAll { $0.id == id }
        scheduleSave()
    }

    /// 收藏栏的第二行：这一部有没有更新。
    ///
    /// Android 侧由 `FavoriteMonitor` 定期比对服务端的集数；iOS 本轮不做账号侧同步，
    /// 因此这里只在「本机记录过更多集数」时给一句话，其余返回 nil 让卡片回落到默认行。
    func favoriteLabel(_ id: String) -> String? {
        guard let series = data.favorites.first(where: { $0.id == id }),
              let progress = data.progress[id] else { return nil }
        if progress.completed { return "已看完" }
        return "看到第 \(progress.episodeIndex + 1) 集"
    }

    // MARK: 进度 / 历史

    func progress(_ id: String) -> WatchProgress? { data.progress[id] }

    /// 记一条进度：同一部剧只保留最新一条，历史按更新时间倒序。
    func record(_ series: Series, episodeId: String, episodeIndex: Int,
                position: Int64, duration: Int64) {
        let completed = duration > 0 && position >= duration - WatchProgress.completeTailMs
        let entry = WatchProgress(series: series, episodeId: episodeId, episodeIndex: episodeIndex,
                                  position: position, duration: duration, completed: completed,
                                  updatedAt: Int64(Date().timeIntervalSince1970 * 1000))
        data.progress[series.id] = entry
        data.history.removeAll { $0.series.id == series.id }
        data.history.insert(entry, at: 0)
        if data.history.count > 200 { data.history = Array(data.history.prefix(200)) }
        scheduleSave()
    }

    func removeHistory(_ id: String) {
        data.history.removeAll { $0.series.id == id }
        data.progress.removeValue(forKey: id)
        scheduleSave()
    }

    func clearHistory() {
        data.history.removeAll()
        data.progress.removeAll()
        scheduleSave()
    }

    /// 历史筛选：`nil` = 全部。
    func history(filter: Bool?) -> [WatchProgress] {
        guard let filter else { return data.history }
        return data.history.filter { $0.completed == filter }
    }

    // MARK: 隐藏

    func hidden() -> Set<String> { Set(data.hidden) }

    func hide(_ id: String) {
        if !data.hidden.contains(id) { data.hidden.append(id) }
        scheduleSave()
    }

    func unhideAll() {
        data.hidden.removeAll()
        scheduleSave()
    }

    // MARK: 搜索记录

    func rememberSearch(_ keyword: String) {
        let clean = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        data.searches.removeAll { $0 == clean }
        data.searches.insert(clean, at: 0)
        if data.searches.count > 20 { data.searches = Array(data.searches.prefix(20)) }
        scheduleSave()
    }

    func clearSearches() {
        data.searches.removeAll()
        scheduleSave()
    }

    // MARK: 备份

    struct Backup: Codable {
        var version: Int = 1
        var exportedAt: Int64 = Int64(Date().timeIntervalSince1970)
        var data: LibraryData
        var prefs: LibraryPrefs
    }

    func exportBackup() -> Data? {
        try? JSONEncoder().encode(Backup(data: data, prefs: prefs))
    }

    /// 合并恢复：本机已有的条目优先，备份里的补充进来（与 Android 的 `restore(merge=true)` 同义）。
    @discardableResult
    func importBackup(_ payload: Data) -> Bool {
        guard let backup = try? JSONDecoder().decode(Backup.self, from: payload) else { return false }
        var merged = data
        for series in backup.data.favorites where !merged.favorites.contains(where: { $0.id == series.id }) {
            merged.favorites.append(series)
        }
        for series in backup.data.later where !merged.later.contains(where: { $0.id == series.id }) {
            merged.later.append(series)
        }
        for row in backup.data.history where !merged.history.contains(where: { $0.series.id == row.series.id }) {
            merged.history.append(row)
        }
        for (key, value) in backup.data.progress where merged.progress[key] == nil {
            merged.progress[key] = value
        }
        for id in backup.data.hidden where !merged.hidden.contains(id) {
            merged.hidden.append(id)
        }
        data = merged
        prefs = backup.prefs
        saveNow()
        return true
    }

    /// 恢复出厂：偏好与记录一起清空（与「清空应用缓存」同义）。
    func resetAll() {
        data = LibraryData()
        prefs = LibraryPrefs()
        saveNow()
        IdentityStore.clear()
        SessionStore.shared.signOut()
    }

    /// 只恢复默认设置，收藏与记录不动。
    func restoreDefaultPrefs() {
        let theme = prefs.theme
        let scale = prefs.uiScale
        prefs = LibraryPrefs()
        prefs.theme = theme
        prefs.uiScale = scale
        saveNow()
    }
}
