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
    /// `cell/change` 下一页游标；首页榜单与榜单页都原样带回。
    var nextOffset: Int = 0
    var rankVersion: String = ""
    var sessionId: String = ""
    var filterIds: String = ""
}

// MARK: - 榜单

enum RankBoard: String, CaseIterable, Identifiable {
    case recommend, hot, subscribe
    case newRelease = "new-release"
    case hotSearch = "hot-search"
    case chase
    case mustWatch = "must-watch"
    case followed

    /// 顶部榜单顺序与 HAR 的 `cell_selector` 一致。
    static let navOrder: [RankBoard] = AppConfig.Navigation.rankBoardOrder.compactMap {
        RankBoard(rawValue: $0)
    }

    var id: String { rawValue }

    var label: String {
        switch self {
        case .recommend: return "推荐"
        case .hot: return "热播"
        case .subscribe: return "预约"
        case .newRelease: return "新剧"
        case .hotSearch: return "热搜"
        case .chase: return "追更"
        case .mustWatch: return "必看"
        case .followed: return "收藏"
        }
    }

    var apiValue: String {
        switch self {
        case .recommend: return "ranklist_hot_sc"
        case .hot: return "ranklist_hot_play_sc"
        case .subscribe: return "ranklist_subscribe"
        case .newRelease: return "ranklist_new_rank_sc"
        case .hotSearch: return "ranklist_hot_search_sc"
        case .chase: return "ranklist_chase_update"
        case .mustWatch: return "ranklist_must_watch"
        case .followed: return "ranklist_followed"
        }
    }
}

/// 推荐流与分类页共用的体裁入口，不再复用榜单枚举。
enum RecommendGenre: String, CaseIterable, Identifiable {
    case recommend, comic, ai, real

    var id: String { rawValue }

    var label: String {
        switch self {
        case .recommend: return "推荐"
        case .comic: return "漫剧"
        case .ai: return "AI剧"
        case .real: return "真人"
        }
    }

    var genre: String? {
        switch self {
        case .recommend: return nil
        case .comic: return "comic_series"
        case .ai: return "ai_series"
        case .real: return "short_play"
        }
    }

    static let typed: [RecommendGenre] = [.comic, .ai, .real]
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
    static let options = AppConfig.Playback.qualityOptions
    static func normalize(_ value: Int) -> Int { options.contains(value) ? value : 1080 }
}

enum PlaybackSpeed {
    static let options = AppConfig.Playback.speedOptions
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
    case hidden, single, two, three, four, top, full
    var label: String {
        switch self {
        case .hidden: return "隐藏"
        case .single: return "一行"
        case .two: return "两行"
        case .three: return "三行"
        case .four: return "四行"
        case .top: return "上方"
        case .full: return "全屏"
        }
    }
    /// 隐藏档下字号 / 透明度 / 速度都没有意义，设置页要把这几项收起来。
    var showsStyle: Bool { self != .hidden }
    /// 一行到四行档的固定轨道上限；上方 / 全屏由可用高度决定。
    var lineLimit: Int? {
        switch self {
        case .single: return 1
        case .two: return 2
        case .three: return 3
        case .four: return 4
        case .hidden, .top, .full: return nil
        }
    }
    var sliderValue: Double {
        Double(Self.allCases.firstIndex(of: self) ?? 0)
    }
    static var sliderMax: Double {
        Double(allCases.count - 1)
    }
    static func fromSlider(_ value: Double) -> Self {
        let index = min(max(Int(value.rounded()), 0), allCases.count - 1)
        return allCases[index]
    }
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
    static let phoneOptions = AppConfig.LibraryDefaults.episodePageSizeOptions
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
    static let completeTailMs: Int64 = AppConfig.Playback.completeTailMs

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
