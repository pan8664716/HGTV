// SPDX-License-Identifier: GPL-3.0-only
//
// 内容模型：字段名与语义逐条对齐 Android 的 `core/ContentRepository.kt`。
// 只保留 iOS 端真正用到的那部分（没有广告、金币、任务、会员——两端都不搬那套）。

import Foundation

// MARK: - 剧集

struct Series: Identifiable, Codable, Hashable {
    var id: String
    var title: String
    var cover: String = ""
    var description: String = ""
    /// 接口给的集数文案（`99 集`），卡片右下角用它兜底。
    var badge: String = ""
    var tags: String = ""
    /// 出品方（详情 / 分类接口的 `copyright` 字段）。
    var author: String = ""
    var episodeCount: Int = 0
    /// 第几季（0 = 接口没给）。
    var seasonIndex: Int = 0
    /// 还没上架（预告 / 预约中）：详情接口照常给封面简介，但没有分集。
    var upcoming: Bool = false
    var notice: String = ""
    var vertical: Bool = false
    var heat: Int64 = 0
    var playCount: Int = 0
    /// 系统角标文案（`新剧` / `红果首发` / `爆剧`）。
    var label: String = ""
    /// 角标底色：`#00AE83`，两个用逗号分隔表示渐变。
    var labelBg: String = ""

    static let empty = Series(id: "", title: "")
}

struct SeriesDetail {
    var series: Series
    /// 分集 id（播放与弹幕要的 `vid`），已按集号排序。
    var episodes: [String]
    var upcoming: Bool = false
    var reserved: Bool = false
}

struct CatalogPage {
    var items: [Series]
    var hasMore: Bool
    var ranking: CatalogRanking?
}

struct RankPosition {
    var rank: Int
    var heat: String
}

struct CatalogRanking {
    /// 剧 id → 名次与热度（服务端原串 `9014万`）。
    var positions: [String: RankPosition]
}

// MARK: - 榜单

enum RankBoard: String, CaseIterable, Identifiable {
    case comic, real, ai, new

    /// 顶部榜单顺序：推荐（原上新）/ 漫剧 / AI剧 / 真人。
    static let navOrder: [RankBoard] = [.new, .comic, .ai, .real]

    var id: String { rawValue }

    var label: String {
        switch self {
        case .comic: return "漫剧"
        case .real: return "真人"
        case .ai: return "AI剧"
        case .new: return "推荐"
        }
    }

    var homeTitle: String {
        switch self {
        case .comic: return "热播漫剧"
        case .real: return "热播真人"
        case .ai: return "热播AI"
        case .new: return "热播上新"
        }
    }

    /// 分类接口的 `select_items.genre`；`nil` = 全站综合（不筛分类）。
    var genre: String? {
        switch self {
        case .comic: return "comic_series"
        case .real: return "short_play"
        case .ai: return "ai_series"
        case .new: return nil
        }
    }

    var sort: String {
        self == .new ? "online_time" : "hot_score"
    }

    /// 时间序的「推荐」（原上新）不画名次。
    var ranked: Bool { self != .new }
}

// MARK: - 分类

enum CategoryGenres {
    struct Option: Identifiable {
        let id: String?
        let label: String
    }

    /// 顶部体裁的固定顺序（需求钉死，不跟接口返回顺序走）：推荐 / 漫剧 / AI剧 / 真人。
    static let options: [Option] = [
        Option(id: nil, label: "推荐"),
        Option(id: "comic_series", label: "漫剧"),
        Option(id: "ai_series", label: "AI剧"),
        Option(id: "short_play", label: "真人")
    ]

    /// 设置页「推荐范围」里能开关的那三类。
    static let recommendable: [Option] = options.filter { $0.id != nil }
    static let recommendableIds: [String] = recommendable.compactMap(\.id)
}

struct CategoryFilterOption: Identifiable {
    let id: String
    let label: String
}

struct CategoryFilterRow: Identifiable {
    /// 同时是 `select_items` 的键（`category_dim_theme` / `sort` …）。
    let type: String
    let title: String
    let options: [CategoryFilterOption]

    var id: String { type }
    var label: String { title.replacingOccurrences(of: "全部", with: "") }
}

struct CategoryPanel {
    var rows: [CategoryFilterRow]
    func row(_ type: String) -> CategoryFilterRow? { rows.first { $0.type == type } }
    static let empty = CategoryPanel(rows: [])
}

/// 分类页当前选中的筛选条件。空值 = 「全部」（不传这个维度）。
struct CategorySelection: Equatable {
    static let typeGenre = "genre"
    static let typeTheme = "category_dim_theme"
    static let typeRole = "category_dim_role"
    static let typeEpoch = "category_dim_epoch"
    static let typeSort = "sort"
    static let typeGender = "category_dim_gender"
    static let typeOnlineTime = "category_dim_online_time"
    static let typeCreationStatus = "category_dim_creation_status"

    var genre: String?
    var theme: String?
    var role: String?
    var epoch: String?
    var sort: String?
    var gender: String?
    var onlineTime: String?
    var creationStatus: String?

    init(genre: String? = nil, theme: String? = nil, role: String? = nil,
         epoch: String? = nil, sort: String? = nil, gender: String? = nil,
         onlineTime: String? = nil, creationStatus: String? = nil) {
        self.genre = genre
        self.theme = theme
        self.role = role
        self.epoch = epoch
        self.sort = sort
        self.gender = gender
        self.onlineTime = onlineTime
        self.creationStatus = creationStatus
    }

    func value(_ type: String) -> String? {
        switch type {
        case Self.typeGenre: return genre
        case Self.typeTheme: return theme
        case Self.typeRole: return role
        case Self.typeEpoch: return epoch
        case Self.typeSort: return sort
        case Self.typeGender: return gender
        case Self.typeOnlineTime: return onlineTime
        case Self.typeCreationStatus: return creationStatus
        default: return nil
        }
    }

    mutating func set(_ type: String, _ value: String?) {
        switch type {
        case Self.typeGenre: genre = value
        case Self.typeTheme: theme = value
        case Self.typeRole: role = value
        case Self.typeEpoch: epoch = value
        case Self.typeSort: sort = value
        case Self.typeGender: gender = value
        case Self.typeOnlineTime: onlineTime = value
        case Self.typeCreationStatus: creationStatus = value
        default: break
        }
    }

    /// 换体裁时清掉随体裁变化的维度（与 Android 的 `newGenre` 同一条规则）。
    func newGenre(_ value: String?) -> CategorySelection {
        var next = self
        next.genre = value
        next.theme = nil
        next.role = nil
        next.epoch = nil
        next.creationStatus = nil
        return next
    }

    /// 一个细分维度都没选（推荐流只在这种状态下有效）。
    var hasNoDetailFilters: Bool {
        theme == nil && role == nil && epoch == nil && gender == nil
            && onlineTime == nil && creationStatus == nil
    }
}

// MARK: - 播放相关枚举

enum PlaybackQuality {
    /// 五档与平台下发的 360/480/576/720/1080 一致。
    static let options = [360, 480, 576, 720, 1080]
    static func normalize(_ value: Int) -> Int { options.contains(value) ? value : 1080 }
}

enum PlaybackSpeed {
    static let options: [Float] = [0.75, 1, 1.25, 1.5, 1.75, 2]
    static func normalize(_ value: Float) -> Float { options.contains(value) ? value : 1 }
    static func label(_ value: Float) -> String {
        let text = value == value.rounded() ? String(Int(value)) : String(format: "%.2f", value)
        return text + "×"
    }
}

enum VideoFrameMode: String, Codable, CaseIterable {
    case fit, zoom, fill
    var label: String {
        switch self {
        case .fit: return "完整画面"
        case .zoom: return "等比铺满"
        case .fill: return "拉伸铺满"
        }
    }
}

enum VideoDecoderMode: String, Codable, CaseIterable {
    case hardware, software
    var label: String { self == .hardware ? "硬件解码" : "软件解码" }
}

enum DanmakuMode: String, Codable, CaseIterable {
    case single, top, full, hidden
    var label: String {
        switch self {
        case .single: return "只显一行"
        case .top: return "上方显示"
        case .full: return "全屏显示"
        case .hidden: return "隐藏弹幕"
        }
    }
    /// 隐藏档下字号 / 透明度 / 速度都没有意义，设置页要把这几项收起来。
    var showsStyle: Bool { self != .hidden }
}

enum DanmakuSpeed: String, Codable, CaseIterable {
    case slow, normal, fast
    var label: String {
        switch self {
        case .slow: return "慢速"
        case .normal: return "标准"
        case .fast: return "快速"
        }
    }
    var factor: Float {
        switch self {
        case .slow: return 0.75
        case .normal: return 1
        case .fast: return 1.5
        }
    }
}

enum SearchSource: String, Codable, CaseIterable {
    case app, web
    var label: String { self == .app ? "App" : "官网" }
    var hint: String { self == .app ? "接口稳定、结果全" : "官网兜底、偶有缺页" }
}

enum EpisodePageSize {
    static let phoneOptions = [20, 30, 50, 80, 100]
    static func normalize(_ value: Int) -> Int { phoneOptions.contains(value) ? value : 20 }
}

// MARK: - 观看进度

struct WatchProgress: Codable {
    var series: Series
    var episodeId: String
    var episodeIndex: Int
    var position: Int64
    var duration: Int64
    var completed: Bool
    var updatedAt: Int64

    /// 离片尾不到 3 秒算看完（与 Android 的 `COMPLETE_TAIL_MS` 同值）。
    static let completeTailMs: Int64 = 3000

    var fraction: Double {
        guard duration > 0 else { return 0 }
        return min(1, max(0, Double(position) / Double(duration)))
    }

    var percent: Int { Int((fraction * 100).rounded()) }
}

// MARK: - 账号

struct AccountProfile: Codable {
    var nickname: String = ""
    var displayId: String = ""
    var avatar: String = ""
}

struct AccountSnapshot: Codable {
    var profile: AccountProfile?
    var favorites: [Series] = []
    var history: [AccountHistoryRow] = []
    var likes: [Series] = []
    var reserves: [ReserveRow] = []
    var fetchedAt: Int64 = 0
}

struct AccountHistoryRow: Codable {
    var series: Series
    var episodeIndex: Int = 0
    var position: Int64 = 0
    var episodes: Int = 0
}

struct ReserveRow: Codable {
    var series: Series
    var online: Bool = false
}

// MARK: - 提示词

struct SearchSuggestion: Identifiable {
    let id = UUID()
    let text: String
    let hint: String
}
