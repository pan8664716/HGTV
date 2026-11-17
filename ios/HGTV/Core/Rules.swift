// SPDX-License-Identifier: GPL-3.0-only
//
// 纯业务规则：与 Android 的 `core` 同一条实现，界面只消费结果。
// 这些函数之所以单独成文件，是因为它们最容易被两端各写一遍、然后悄悄走偏。

import Foundation

// MARK: - 热度

enum HeatLabel {
    private static let wan: Int64 = 10_000
    private static let yi: Int64 = 100_000_000
    static let fire = "🔥"

    /// 服务端原串优先（它已经排好序、带单位），没有才用数字换算。
    static func text(serverText: String?, value: Int64) -> String {
        let raw = (serverText ?? "").trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "热度", with: "")
            .trimmingCharacters(in: .whitespaces)
        if !raw.isEmpty { return raw }
        return format(value)
    }

    /// 角标上的文案：`🔥9014万`；没有热度时为空串，调用方整枚收起。
    static func badge(serverText: String?, value: Int64) -> String {
        let text = text(serverText: serverText, value: value)
        return text.isEmpty ? "" : fire + text
    }

    static func format(_ value: Int64) -> String {
        if value <= 0 { return "" }
        if value >= yi {
            let tenths = (value + yi / 20) / (yi / 10)
            return tenths % 10 == 0 ? "\(tenths / 10)亿" : "\(tenths / 10).\(tenths % 10)亿"
        }
        if value >= wan * 100 { return "\(value / wan)万" }
        if value >= wan {
            let tenths = (value + wan / 20) / (wan / 10)
            return tenths % 10 == 0 ? "\(tenths / 10)万" : "\(tenths / 10).\(tenths % 10)万"
        }
        return "\(value)"
    }
}

// MARK: - 集数文案

enum EpisodeLabel {
    static func of(_ count: Int) -> String { count > 0 ? "\(count) 集" : "" }
    static func total(_ count: Int) -> String { count > 0 ? "共 \(count) 集" : "" }

    /// 只把「全 N 集」归一成「N 集」，其余文案原样返回。
    static func normalize(_ raw: String) -> String {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return "" }
        let pattern = "^全?\\s*([0-9]+)\\s*集$"
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let range = Range(match.range(at: 1), in: text) else { return text }
        return "\(text[range]) 集"
    }

    static func cardStatus(_ text: String) -> String { normalize(text) }
}

enum EpisodePaging {
    static func pages(_ count: Int, perPage: Int) -> Int {
        guard perPage > 0 else { return 1 }
        return max(1, (count + perPage - 1) / perPage)
    }

    /// 第 page 页（0 基）的闭区间集号（0 基）。
    static func range(_ count: Int, perPage: Int, page: Int) -> ClosedRange<Int>? {
        guard count > 0, perPage > 0 else { return nil }
        let total = pages(count, perPage: perPage)
        let safe = min(max(page, 0), total - 1)
        let from = safe * perPage
        let to = min(from + perPage, count) - 1
        guard from <= to else { return nil }
        return from...to
    }

    static func rangeText(_ count: Int, perPage: Int, page: Int) -> String {
        guard let r = range(count, perPage: perPage, page: page) else { return "" }
        return "第 \(r.lowerBound + 1)—\(r.upperBound + 1) 集 · 共 \(count) 集"
    }

    static func pageText(_ count: Int, perPage: Int, page: Int) -> String {
        "第 \(min(page + 1, pages(count, perPage: perPage)))/\(pages(count, perPage: perPage)) 页"
    }
}

// MARK: - 季号

enum SeriesSeasons {
    private static let cnDigits: [Character: Int] = [
        "零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
        "五": 5, "六": 6, "七": 7, "八": 8, "九": 9
    ]

    private static let suffix = try! NSRegularExpression(
        pattern: "[\\s·\\-—_]*(?:第\\s*([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})\\s*[季部期]|([0-9]{1,2})\\s*[季部期]|(?i:season)\\s*([0-9]{1,2})|(?i:s)\\s*([0-9]{1,2}))\\s*$"
    )

    static func chineseNumber(_ value: String) -> Int? {
        if value.isEmpty { return nil }
        if value.allSatisfy({ $0.isNumber }) { return Int(value) }
        if let ten = value.firstIndex(of: "十") {
            let head = String(value[value.startIndex..<ten])
            let tail = String(value[value.index(after: ten)...])
            let tens = head.isEmpty ? 1 : (head.first.flatMap { cnDigits[$0] } ?? -1)
            let ones = tail.isEmpty ? 0 : (tail.first.flatMap { cnDigits[$0] } ?? -1)
            if tens < 0 || ones < 0 { return nil }
            return tens * 10 + ones
        }
        var sum = 0
        for ch in value {
            guard let digit = cnDigits[ch] else { return nil }
            sum = sum * 10 + digit
        }
        return sum
    }

    /// 剧名里是否自带季号；没有季号即第 1 季。
    static func parse(_ title: String) -> (base: String, season: Int, matched: Bool) {
        let clean = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let ns = clean as NSString
        guard let match = suffix.firstMatch(in: clean, range: NSRange(location: 0, length: ns.length)) else {
            return (clean, 1, false)
        }
        var raw = ""
        for i in 1...4 {
            let r = match.range(at: i)
            if r.location != NSNotFound, let swiftRange = Range(r, in: clean) {
                raw = String(clean[swiftRange])
                break
            }
        }
        guard let number = chineseNumber(raw), number > 0, number <= 99 else {
            return (clean, 1, false)
        }
        let base = ns.substring(to: match.range.location).trimmingCharacters(in: .whitespacesAndNewlines)
        return (base, number, true)
    }

    /// 卡片右上角的「第 N 季」：接口给了季号就用它，没给才按剧名解析。
    static func badge(_ series: Series) -> String {
        let season = series.seasonIndex > 0 ? series.seasonIndex : parse(series.title).season
        return season >= 1 ? "第\(season)季" : ""
    }
}

// MARK: - 角标配色

enum LabelStyle {
    static let green: UInt32 = 0x00AE83
    static let redGradient: [UInt32] = [0xE8402A, 0x9C1B10]

    /// 服务端给了色就用它（最多两色 = 渐变），没给按文案兜底。
    static func colors(_ text: String, _ bg: String) -> [UInt32] {
        let fromServer = bg.split(separator: ",").compactMap { parseHex(String($0)) }.prefix(2)
        if !fromServer.isEmpty { return Array(fromServer) }
        if text.contains("上新") || text.contains("新剧") { return [green] }
        if text.contains("爆") { return redGradient }
        return []
    }

    private static func parseHex(_ raw: String) -> UInt32? {
        let body = raw.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "")
        guard let value = UInt32(body, radix: 16) else { return nil }
        switch body.count {
        case 6: return value
        case 8: return value & 0x00FF_FFFF
        default: return nil
        }
    }
}

/// 榜单前三名的金银铜渐变。
enum RankBadgeStyle {
    static let gold: [UInt32] = [0xD6A21A, 0x7A4D00]
    static let silver: [UInt32] = [0xBFC8D4, 0x5F6B7A]
    static let bronze: [UInt32] = [0xC47A42, 0x6A351B]

    static func colors(_ rank: Int) -> [UInt32] {
        switch rank {
        case 1: return gold
        case 2: return silver
        case 3: return bronze
        default: return []
        }
    }
}

// MARK: - 播放读数

enum PlaybackInfoLine {
    /// 毫秒 → `mm:ss` / `h:mm:ss`（与电视端 `formatTime` 同一份）。
    static func time(_ ms: Int64) -> String {
        let total = max(0, ms / 1000)
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, s) }
        return String(format: "%02d:%02d", m, s)
    }

    static func speed(_ bytesPerSecond: Double) -> String {
        if bytesPerSecond <= 0 { return "0 KB/s" }
        if bytesPerSecond >= 1024 * 1024 {
            return String(format: "%.1f MB/s", bytesPerSecond / 1024 / 1024)
        }
        return String(format: "%.0f KB/s", bytesPerSecond / 1024)
    }
}

// MARK: - 列表过滤

enum CatalogFilter {
    /// 长按菜单隐藏过的剧不出现在任何列表里（与 TV 的 `SeriesGrid` 同一条规则）。
    static func visible(_ page: CatalogPage, hidden: Set<String>) -> CatalogPage {
        guard !hidden.isEmpty else { return page }
        var next = page
        next.items = page.items.filter { !hidden.contains($0.id) }
        return next
    }

    static func visible(_ items: [Series], hidden: Set<String>) -> [Series] {
        hidden.isEmpty ? items : items.filter { !hidden.contains($0.id) }
    }
}

// MARK: - 推荐去重窗口

/// 推荐流的曝光窗口：条数上限 1200、7 天过期，剧名也进窗口。
///
/// 官方客户端只有 200 条纯 LRU，那是「刷一会儿就开始重复」的头号原因；
/// Android 端换成「条数 + 时间窗」，这里保持同一套判据。
struct RecommendWindow {
    private struct Entry { let id: String; let title: String; let at: TimeInterval }
    private var entries: [Entry] = []
    private let limit = AppConfig.Recommend.windowLimit
    private let ttl: TimeInterval = AppConfig.Recommend.windowTTL

    mutating func remember(_ series: Series) {
        let now = Date().timeIntervalSince1970
        entries.removeAll { now - $0.at > ttl }
        entries.append(Entry(id: series.id, title: SeriesSeasons.parse(series.title).base, at: now))
        if entries.count > limit { entries.removeFirst(entries.count - limit) }
    }

    func contains(_ series: Series) -> Bool {
        entries.contains { $0.id == series.id }
    }

    func containsTitle(_ series: Series) -> Bool {
        let base = SeriesSeasons.parse(series.title).base
        return entries.contains { $0.title == base }
    }

    /// 推给服务端的 `filter_ids`：最近看过的剧 id（长度按平台的 4KB 量级留足余量）。
    var filterIds: String {
        let ids = entries.suffix(AppConfig.Recommend.filterIdsLimit).map { $0.id }
        return ids.joined(separator: ",")
    }

    mutating func reset() { entries.removeAll() }
}

// MARK: - 播放降级

/// 播放失败时的自愈决策（与 `core/PlaybackDegradation.kt` 同一份）：
/// 4001 / 4003 先同档重建，4004 / 4005 才降档，每集最多降两档，绝不改用户设置。
enum PlaybackDegradation {
    struct State {
        var rebuilds = 0
        var drops = 0
        var level: Int
    }

    enum Action {
        case rebuild
        case drop(to: Int)
        case fail
    }

    static func decide(code: Int, state: State) -> Action {
        switch code {
        case 4001, 4003:
            if state.rebuilds < AppConfig.Playback.sameQualityRebuildLimit { return .rebuild }
            if state.drops < AppConfig.Playback.qualityDownLimit, let next = lower(state.level) {
                return .drop(to: next)
            }
            return .fail
        case 4004, 4005:
            if state.drops < AppConfig.Playback.qualityDownLimit, let next = lower(state.level) {
                return .drop(to: next)
            }
            return .fail
        default:
            return .fail
        }
    }

    private static func lower(_ level: Int) -> Int? {
        switch level {
        case 1080: return 720
        case 720: return 576
        case 576: return 480
        case 480: return 360
        default: return nil
        }
    }
}
