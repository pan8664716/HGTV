// SPDX-License-Identifier: GPL-3.0-only
//
// 主题令牌：与 Android 端 `shared/ui/TvTheme.kt` 逐值一致（20 套新拟态配色）。
// 一套配色由八个语义色定义，其余档位全部由 base/light/dark 派生——
// 两端共用同一条派生规则，换主题只换色，不动几何。

import SwiftUI

// MARK: - 颜色工具

extension Color {
    init(rgb: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((rgb >> 16) & 0xFF) / 255,
                  green: Double((rgb >> 8) & 0xFF) / 255,
                  blue: Double(rgb & 0xFF) / 255,
                  opacity: alpha)
    }

    /// 两色线性混合：`t = 0` 取 from，`t = 1` 取 to（对应 Kotlin 的 `mix`）。
    static func mix(_ from: UInt32, _ to: UInt32, _ t: Double) -> UInt32 {
        let fr = Double((from >> 16) & 0xFF), fg = Double((from >> 8) & 0xFF), fb = Double(from & 0xFF)
        let tr = Double((to >> 16) & 0xFF), tg = Double((to >> 8) & 0xFF), tb = Double(to & 0xFF)
        let r = UInt32((fr + (tr - fr) * t).rounded())
        let g = UInt32((fg + (tg - fg) * t).rounded())
        let b = UInt32((fb + (tb - fb) * t).rounded())
        return (r << 16) | (g << 8) | b
    }
}

// MARK: - 主题

enum PhoneTheme: String, CaseIterable, Identifiable {
    case deepSeaBlue, graphite, midnightPurple, pineGreen, warmBrown, wineRed,
         darkGold, deepTeal, steelBlue, inkPurple,
         hazeBlue, cream, mint, sakura, lavender, skyBlue, oatGray, lemon, coral, sage

    var id: String { storedValue }

    /// 设置里显示的名字。
    var label: String {
        switch self {
        case .deepSeaBlue: return "深海蓝"
        case .graphite: return "石墨灰"
        case .midnightPurple: return "午夜紫"
        case .pineGreen: return "松林绿"
        case .warmBrown: return "暖棕夜"
        case .wineRed: return "酒红夜"
        case .darkGold: return "暗金岩"
        case .deepTeal: return "深海青"
        case .steelBlue: return "钢蓝夜"
        case .inkPurple: return "墨紫夜"
        case .hazeBlue: return "雾霾蓝"
        case .cream: return "奶油米"
        case .mint: return "薄荷浅"
        case .sakura: return "樱粉浅"
        case .lavender: return "薰衣浅"
        case .skyBlue: return "天青浅"
        case .oatGray: return "燕麦灰"
        case .lemon: return "浅柠檬"
        case .coral: return "浅珊瑚"
        case .sage: return "浅灰绿"
        }
    }

    /// 气质 / 适用场景（设置页那行说明）。
    var scene: String {
        switch self {
        case .deepSeaBlue: return "默认 · 冷调深色"
        case .graphite: return "中性深灰"
        case .midnightPurple: return "冷紫夜色"
        case .pineGreen: return "墨绿夜色"
        case .warmBrown: return "暖棕夜色"
        case .wineRed: return "酒红夜色"
        case .darkGold: return "暗金岩色"
        case .deepTeal: return "青蓝深色"
        case .steelBlue: return "钢蓝深色"
        case .inkPurple: return "墨紫深色"
        case .hazeBlue: return "雾霾蓝 · 浅色"
        case .cream: return "奶油米 · 浅色"
        case .mint: return "薄荷浅 · 浅色"
        case .sakura: return "樱粉浅 · 浅色"
        case .lavender: return "薰衣浅 · 浅色"
        case .skyBlue: return "天青浅 · 浅色"
        case .oatGray: return "燕麦灰 · 浅色"
        case .lemon: return "浅柠檬 · 浅色"
        case .coral: return "浅珊瑚 · 浅色"
        case .sage: return "浅灰绿 · 浅色"
        }
    }

    /// 落盘用的稳定字符串：改显示名不会丢用户的选择。
    var storedValue: String {
        switch self {
        case .deepSeaBlue: return "deep-sea-blue"
        case .graphite: return "graphite"
        case .midnightPurple: return "midnight-purple"
        case .pineGreen: return "pine-green"
        case .warmBrown: return "warm-brown"
        case .wineRed: return "wine-red"
        case .darkGold: return "dark-gold"
        case .deepTeal: return "deep-teal"
        case .steelBlue: return "steel-blue"
        case .inkPurple: return "ink-purple"
        case .hazeBlue: return "haze-blue"
        case .cream: return "cream"
        case .mint: return "mint"
        case .sakura: return "sakura"
        case .lavender: return "lavender"
        case .skyBlue: return "sky-blue"
        case .oatGray: return "oat-gray"
        case .lemon: return "lemon"
        case .coral: return "coral"
        case .sage: return "sage"
        }
    }

    var isDark: Bool { !Self.lightThemes.contains(self) }

    private static let lightThemes: Set<PhoneTheme> = [
        .hazeBlue, .cream, .mint, .sakura, .lavender, .skyBlue, .oatGray, .lemon, .coral, .sage
    ]

    /// base / light / dark / text / sub / focus / accent / danger（顺序与 Kotlin 构造函数一致）。
    var palette: (base: UInt32, light: UInt32, dark: UInt32, text: UInt32, sub: UInt32,
                  focus: UInt32, accent: UInt32, danger: UInt32) {
        switch self {
        case .deepSeaBlue:
            return (0x1E2228, 0x2A3038, 0x12151A, 0xF2F5F8, 0xAAB4C0, 0x4DA3FF, 0x5B8DEF, 0xFF6B6B)
        case .graphite:
            return (0x23272C, 0x2F343A, 0x171A1E, 0xF4F6F8, 0xA6AFB8, 0x7FD1FF, 0x6FA8FF, 0xFF7A7A)
        case .midnightPurple:
            return (0x211F2B, 0x2E2B3A, 0x15131C, 0xF3F1FA, 0xADA8C2, 0xB98CFF, 0x9B7BFF, 0xFF6B8A)
        case .pineGreen:
            return (0x1B2320, 0x26302C, 0x111715, 0xEFF6F2, 0xA3B5AC, 0x4FE0A8, 0x3ECF98, 0xFF7A7A)
        case .warmBrown:
            return (0x241E1A, 0x312822, 0x16110E, 0xF7F0EA, 0xC0ADA0, 0xFFB27A, 0xFF9A5C, 0xFF6B6B)
        case .wineRed:
            return (0x251A1F, 0x33242B, 0x170F13, 0xFAF0F3, 0xC3A6AE, 0xFF7EA8, 0xFF5C8A, 0xFF4D4D)
        case .darkGold:
            return (0x221F18, 0x2F2B22, 0x15130E, 0xF7F3EA, 0xBFB5A0, 0xE8C36A, 0xD9B154, 0xFF7A6B)
        case .deepTeal:
            return (0x182128, 0x232F37, 0x0F161B, 0xEAF4F8, 0x9FB6C2, 0x38D2E0, 0x2EC4D6, 0xFF6B7A)
        case .steelBlue:
            return (0x1A1F2B, 0x252C3A, 0x10141C, 0xEEF2FA, 0x9FAABF, 0x7A9CFF, 0x5B7CE0, 0xFF6B7A)
        case .inkPurple:
            return (0x1C1A26, 0x272433, 0x111018, 0xF0EEFA, 0xA8A3BE, 0xC77DFF, 0xA55BFF, 0xFF6B9A)
        case .hazeBlue:
            return (0xE0E5EC, 0xFFFFFF, 0xA3B1C6, 0x3A4553, 0x6B7788, 0x4A7DFF, 0x5B8DEF, 0xE5484D)
        case .cream:
            return (0xEFEAE3, 0xFFFFFF, 0xC3B8A8, 0x3B352C, 0x7A7164, 0xE08A3C, 0xD9822B, 0xD64545)
        case .mint:
            return (0xE3EEE9, 0xFFFFFF, 0xAABFB6, 0x2F3E38, 0x66796F, 0x12A67A, 0x0E9A70, 0xD64545)
        case .sakura:
            return (0xF0E6EA, 0xFFFFFF, 0xC9B2BC, 0x3E2F35, 0x7C6670, 0xD6468C, 0xC93C82, 0xD64545)
        case .lavender:
            return (0xE9E6F2, 0xFFFFFF, 0xB8B0CE, 0x35304A, 0x6F6888, 0x7C5CFF, 0x6B4DFF, 0xD64545)
        case .skyBlue:
            return (0xE2EDF2, 0xFFFFFF, 0xA6BCC8, 0x2C3C45, 0x627885, 0x1A9BC7, 0x128FBA, 0xD64545)
        case .oatGray:
            return (0xECE9E4, 0xFFFFFF, 0xBFB9AF, 0x3A362F, 0x787167, 0x8A7B5E, 0x7A6C4F, 0xD64545)
        case .lemon:
            return (0xF1EFE0, 0xFFFFFF, 0xC8C2A6, 0x3A3826, 0x7B775D, 0xB6A21E, 0xA38F12, 0xD64545)
        case .coral:
            return (0xF3E7E4, 0xFFFFFF, 0xCDB5AF, 0x432F2B, 0x876E68, 0xE2654A, 0xD9543A, 0xC0392B)
        case .sage:
            return (0xE8EDE6, 0xFFFFFF, 0xB5C0B0, 0x333D2F, 0x6E7868, 0x5A9A45, 0x4C8C39, 0xD64545)
        }
    }

    // MARK: 派生档位（与 Kotlin 逐条对应）

    var baseColor: Color { Color(rgb: palette.base) }
    var surface: Color { Color(rgb: Color.mix(palette.base, palette.light, 0.45)) }
    var surfaceHigh: Color { Color(rgb: Color.mix(palette.base, palette.light, 0.72)) }
    var well: Color { Color(rgb: Color.mix(palette.base, palette.dark, 0.45)) }
    var wellDeep: Color { Color(rgb: Color.mix(palette.base, palette.dark, 0.72)) }
    var lit: Color { Color(rgb: isDark ? Color.mix(palette.light, 0xFFFFFF, 0.82) : palette.light) }
    var onLit: Color {
        Color(rgb: isDark ? 0x15181C : Color.mix(palette.text, 0x0B0D10, 0.42))
    }
    var text: Color { Color(rgb: palette.text) }
    var textDim: Color { Color(rgb: Color.mix(palette.text, palette.sub, 0.55)) }
    var textFaint: Color { Color(rgb: palette.sub, alpha: 0.72) }
    var accent: Color { Color(rgb: palette.accent) }
    var danger: Color { Color(rgb: palette.danger) }
    var tips: Color { Color(rgb: palette.accent) }
    var separator: Color { Color(rgb: palette.text, alpha: 0.08) }
    var outline: Color { Color(rgb: palette.text, alpha: 0.10) }
    var focusRing: Color { Color(rgb: palette.focus, alpha: 0.90) }
    var shadowDark: Color { Color(rgb: palette.dark, alpha: isDark ? 0.62 : 0.72) }
    var shadowLight: Color { Color(rgb: palette.light, alpha: isDark ? 0.62 : 0.72) }
    var innerDark: Color { Color(rgb: palette.dark, alpha: isDark ? 0.50 : 0.72) }
    var innerLight: Color { Color(rgb: palette.light, alpha: isDark ? 0.10 : 0.50) }

    /// 播放界面压在视频上的字，永远暖白，不跟主题。
    static let playerText = Color(rgb: 0xF5F2EC)
    static let playerDim = Color(rgb: 0xF5F2EC, alpha: 0.70)
    static let playerLine = Color.white.opacity(0.20)

    static func of(_ stored: String?) -> PhoneTheme {
        guard let stored else { return .deepSeaBlue }
        // 旧版本落盘的键 → 新主题（与 Kotlin 的 legacy 表一致）。
        let legacy: [String: PhoneTheme] = [
            "soft-gray": .hazeBlue, "creamy-milk": .cream, "deep-dark": .deepSeaBlue,
            "cool-mint": .mint, "porcelain": .oatGray, "moonstone": .hazeBlue,
            "ivory-sand": .oatGray, "silver-sage": .sage, "blue-haze": .hazeBlue,
            "champagne-silk": .oatGray, "dusty-mauve": .sakura, "pale-olive": .sage,
            "graphite": .graphite, "midnight-blue": .steelBlue
        ]
        if let hit = PhoneTheme.allCases.first(where: { $0.storedValue == stored }) { return hit }
        return legacy[stored] ?? .deepSeaBlue
    }
}

// MARK: - 几何令牌（与 `PhoneStyle` / `PhoneDimen` 同值）

enum PhoneDimen {
    /// 海报网格左右边距：0，卡片直接顶到屏幕边缘。
    static let pageInset = AppConfig.Ui.Phone.pageInset
    static let tabInset = AppConfig.Ui.Phone.tabInset
    static let cardGap = AppConfig.Ui.Phone.cardGap
    static let posterGap = AppConfig.Ui.Phone.posterGap
    static let chipRow = AppConfig.Ui.Phone.chipRow
    static let posterWidth = AppConfig.Ui.Phone.posterWidth
    static let actionItem = AppConfig.Ui.Phone.actionItem

    /// 柔光带：控件的 padding，投影与高光全部画在这条带里。
    static let softBand = AppConfig.Ui.Phone.softBand
    static let softBandLarge = AppConfig.Ui.Phone.softBandLarge
    static let radiusCard = AppConfig.Ui.Phone.radiusCard
    static let radiusPill = AppConfig.Ui.Phone.radiusPill
    static let radiusSheet = AppConfig.Ui.Phone.radiusSheet
    static let trackHeight = AppConfig.Ui.Phone.trackHeight
    /// 底部导航栏自身高度（设计单位）。
    static let tabBar = AppConfig.Ui.Phone.tabBar
}

// MARK: - 画布与字号

/// 手机端设计画布：竖屏 360×640、横屏 640×360（与 `PhoneTheme.kt` 同一套基准）。
///
/// 与 Android 的差别只有一处，而且是有意的：Android 在画布比例上又乘了 1.6 的
/// 界面倍率（那套 dp 值在手机屏上偏小，靠倍率补回来）。iOS 的 point 本身就是
/// 与密度无关的抽象单位，再乘 1.6 会把 16 的内边距变成 28pt——那是平板的手感。
/// 因此这里只保留一个 1.25 的几何系数：版面比例与 Android 一致（16 → 21.8pt ≈ 屏宽 5.5%），
/// 字号按画布 1:1 换算（13 → 14.2pt），正好落在 iOS 正文的常规区间。
struct PhoneMetrics {
    let size: CGSize
    let uiScale: CGFloat

    var isPortrait: Bool { size.height >= size.width }
    var designWidth: CGFloat { isPortrait ? 360 : 640 }
    var designHeight: CGFloat { isPortrait ? 640 : 360 }

    /// 屏幕像素（point）→ 设计单位。
    var raw: CGFloat {
        guard size.width > 0, size.height > 0 else { return 1 }
        return min(size.width / designWidth, size.height / designHeight)
    }

    /// 设计单位 → point（几何）。
    var unit: CGFloat { raw * 1.25 * uiScale }

    /// 设计单位 → point（字号）。不乘几何系数，只跟画布与用户选的界面倍率走。
    var textUnit: CGFloat { raw * uiScale }

    func dp(_ value: CGFloat) -> CGFloat { value * unit }
    func sp(_ value: CGFloat) -> CGFloat { value * textUnit }

    /// 底部安全区 + 导航栏总高，全屏内容要按这个数往上留白。
    func tabBarInset(_ safeBottom: CGFloat) -> CGFloat { dp(PhoneDimen.tabBar) + safeBottom }
}

// MARK: - 环境注入

private struct MetricsKey: EnvironmentKey {
    static let defaultValue = PhoneMetrics(size: CGSize(width: 390, height: 844), uiScale: 1)
}

private struct ThemeKey: EnvironmentKey {
    static let defaultValue = PhoneTheme.deepSeaBlue
}

extension EnvironmentValues {
    var metrics: PhoneMetrics {
        get { self[MetricsKey.self] }
        set { self[MetricsKey.self] = newValue }
    }
    var theme: PhoneTheme {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}
