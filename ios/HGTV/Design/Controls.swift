// SPDX-License-Identifier: GPL-3.0-only
//
// 通用控件：顶栏、底栏、胶囊、设置行、抽屉、提示。
//
// 结构照 Android 的 `PhoneTopBar` / `PhoneTabBar` / `PhoneSheet` / `PhoneSettingControls`：
// 顶栏 = 状态栏留白 + 主行（标题或大分类 tab）+ 右侧动作 + 可选副行 chips；
// 底栏 = 横向长条底板 + 四枚等尺寸圆形凸起入口；选中项只切换图标强调色。

import SwiftUI

// MARK: - 顶栏

struct TopBar: View {
    var title: String?
    var tabs: [String] = []
    var selected: Int = 0
    var onSelect: ((Int) -> Void)?
    var actionIcon: String = "magnifyingglass"
    var actionLabel: String = "搜索"
    var action: (() -> Void)?
    var chips: [String] = []
    var chipSelected: Int = 0
    var onChip: ((Int) -> Void)?
    var immersive: Bool = false
    var overlay: Bool = false

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @Environment(\.safeAreaInsets) private var insets

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                if let title {
                    Text(title)
                        .font(.system(size: m.sp(20), weight: .bold))
                        .foregroundStyle(theme.text)
                        .frame(height: m.dp(34))
                }
                if !tabs.isEmpty {
                    ScrollViewReader { proxy in
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 0) {
                                ForEach(tabs.indices, id: \.self) { index in
                                    Button {
                                        onSelect?(index)
                                    } label: {
                                        Text(tabs[index])
                                            .font(.system(size: m.sp(19), weight: index == selected ? .bold : .regular))
                                            .foregroundStyle(index == selected ? theme.text : theme.textFaint)
                                            .padding(.trailing, m.dp(18))
                                            .frame(height: m.dp(34))
                                            .id(index)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        .onChange(of: selected) { _, index in
                            withAnimation(.easeOut(duration: 0.2)) {
                                proxy.scrollTo(index, anchor: .center)
                            }
                        }
                    }
                }
                Spacer(minLength: 0)
                if let action {
                    Button(action: action) {
                        Image(systemName: actionIcon)
                            .font(.system(size: m.sp(16), weight: .regular))
                            .foregroundStyle(theme.text)
                            .frame(width: m.dp(28), height: m.dp(28))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(actionLabel)
                }
            }
            .padding(.leading, m.dp(16))
            .padding(.trailing, m.dp(10))

            if !chips.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: m.dp(8)) {
                        ForEach(chips.indices, id: \.self) { index in
                            Button { onChip?(index) } label: {
                                Text(chips[index])
                                    .font(.system(size: m.sp(15)))
                                    .foregroundStyle(index == chipSelected ? theme.text : theme.textDim)
                                    .padding(.horizontal, m.dp(12))
                                    .padding(.vertical, m.dp(4))
                                    .background {
                                        if index == chipSelected {
                                            Capsule().fill(theme.surfaceHigh)
                                        }
                                    }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, m.dp(16))
                }
                .padding(.top, m.dp(2))
            }
        }
        .padding(.top, immersive ? m.dp(10) : insets.top + m.dp(4))
        .padding(.bottom, m.dp(2))
        .background {
            if overlay {
                TopScrim(alpha: 0.68).ignoresSafeArea(edges: .top)
            }
        }
    }
}

// MARK: - 底栏

struct BottomTabBar: View {
    @Binding var selected: Int
    var onRepeatTap: ((Int) -> Void)?

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @Environment(\.safeAreaInsets) private var insets

    private let items: [(String, TabIcon)] = AppConfig.Navigation.phoneTabKeys.map { key in
        switch key {
        case "recommend": return ("推荐", .home)
        case "category": return ("分类", .grid)
        case "rank": return ("榜单", .rank)
        default: return ("我的", .mine)
        }
    }

    @State private var lastTapIndex = -1
    @State private var lastTapAt = Date.distantPast

    var body: some View {
        let barHeight = m.dp(PhoneDimen.tabBar)
        let circleSize = m.dp(PhoneDimen.tabBar * 0.62)
        let iconSize = m.dp(PhoneDimen.tabBar * 0.33)
        let panelRadius = m.dp(m.isPortrait ? 8 : 6)

        ZStack(alignment: .top) {
            HStack(spacing: 0) {
                ForEach(items.indices, id: \.self) { index in
                    Button {
                        let now = Date()
                        if index == lastTapIndex &&
                            now.timeIntervalSince(lastTapAt) <= AppConfig.Ui.tabDoubleTapWindow {
                            lastTapIndex = -1
                            onRepeatTap?(index)
                        } else {
                            selected = index
                        }
                        lastTapIndex = index
                        lastTapAt = now
                    } label: {
                        TabGlyph(
                            kind: items[index].1,
                            tint: index == selected ? theme.accent : theme.textDim,
                            lineWidth: max(1, m.dp(1.3))
                        )
                        .frame(width: iconSize, height: iconSize)
                        .frame(width: circleSize, height: circleSize)
                        .soft(
                            .raised,
                            fill: theme.surfaceHigh,
                            radius: circleSize / 2,
                            depth: max(1, m.dp(1.8))
                        )
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    .buttonStyle(.plain)
                    .contentShape(Rectangle())
                    .accessibilityLabel(items[index].0)
                    .accessibilityValue(index == selected ? "已选中" : "")
                }
            }
            .padding(.horizontal, m.dp(4))
            .frame(height: barHeight)
            .soft(
                .raised,
                fill: theme.surface,
                radius: panelRadius,
                depth: max(1, m.dp(2))
            )
            .padding(.horizontal, m.dp(PhoneDimen.tabInset))
        }
        .frame(height: barHeight + insets.bottom)
    }
}

// MARK: - 胶囊 / 分区 / 行

struct ChipLabel: View {
    let text: String
    var selected: Bool
    var inset: Bool = false

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var body: some View {
        Text(text)
            .font(.system(size: m.sp(13), weight: selected ? .semibold : .regular))
            .foregroundStyle(selected ? (inset ? theme.accent : theme.text) : theme.textDim)
            .padding(.horizontal, m.dp(12))
            .padding(.vertical, m.dp(6))
            .soft(selected ? (inset ? .inset : .raised) : .raised,
                  fill: theme.surface, radius: m.dp(PhoneDimen.radiusPill), depth: max(1, m.dp(2)))
    }
}

struct SectionTitle: View {
    let text: String
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var body: some View {
        Text(text)
            .font(.system(size: m.sp(12), weight: .semibold))
            .foregroundStyle(theme.textDim)
            .padding(.top, m.dp(18))
            .padding(.bottom, m.dp(4))
    }
}

struct HintText: View {
    let text: String
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var body: some View {
        Text(text)
            .font(.system(size: m.sp(11)))
            .foregroundStyle(theme.textFaint)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.vertical, m.dp(3))
    }
}

struct SettingRow: View {
    let label: String
    var value: String = ""
    var action: (() -> Void)?

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var body: some View {
        Button {
            action?()
        } label: {
            HStack(spacing: m.dp(8)) {
                Text(label)
                    .font(.system(size: m.sp(14)))
                    .foregroundStyle(theme.text)
                Spacer(minLength: m.dp(8))
                Text(value)
                    .font(.system(size: m.sp(13)))
                    .foregroundStyle(theme.textFaint)
                    .lineLimit(1)
                if action != nil {
                    Image(systemName: "chevron.right")
                        .font(.system(size: m.sp(11), weight: .semibold))
                        .foregroundStyle(theme.textFaint)
                }
            }
            .padding(.horizontal, m.dp(12))
            .frame(height: m.dp(44))
            .soft(.raised, fill: theme.surface, radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(2)))
        }
        .buttonStyle(.plain)
        .padding(.vertical, m.dp(2))
    }
}

struct SwitchRow: View {
    let label: String
    @Binding var isOn: Bool

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: m.sp(14)))
                .foregroundStyle(theme.text)
            Spacer()
            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(theme.accent)
        }
        .padding(.horizontal, m.dp(12))
        .frame(height: m.dp(44))
        .soft(.raised, fill: theme.surface, radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(2)))
        .padding(.vertical, m.dp(2))
    }
}

struct SliderRow: View {
    let label: String
    @Binding var value: Double
    var range: ClosedRange<Double>
    var step: Double
    var text: (Double) -> String

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(spacing: m.dp(2)) {
            HStack {
                Text(label)
                    .font(.system(size: m.sp(14)))
                    .foregroundStyle(theme.text)
                Spacer()
                Text(text(value))
                    .font(.system(size: m.sp(13)))
                    .foregroundStyle(theme.textDim)
                    .monospacedDigit()
            }
            Slider(value: $value, in: range, step: step)
                .tint(theme.accent)
        }
        .padding(.horizontal, m.dp(12))
        .padding(.vertical, m.dp(6))
        .soft(.raised, fill: theme.surface, radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(2)))
        .padding(.vertical, m.dp(2))
    }
}

struct ActionRow: View {
    let label: String
    var detail: String = ""
    var danger: Bool = false
    var action: () -> Void

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var body: some View {
        Button(action: action) {
            HStack(spacing: m.dp(8)) {
                Text(label)
                    .font(.system(size: m.sp(14)))
                    .foregroundStyle(danger ? theme.danger : theme.text)
                Spacer(minLength: m.dp(8))
                Text(detail)
                    .font(.system(size: m.sp(12)))
                    .foregroundStyle(theme.textFaint)
                    .lineLimit(1)
            }
            .padding(.horizontal, m.dp(12))
            .frame(height: m.dp(44))
            .soft(.raised, fill: theme.surface, radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(2)))
        }
        .buttonStyle(.plain)
        .padding(.vertical, m.dp(2))
    }
}

// MARK: - 提示

struct ToastView: View {
    let text: String
    @Environment(\.metrics) private var m

    var body: some View {
        Text(text)
            .font(.system(size: m.sp(13)))
            .foregroundStyle(.white)
            .padding(.horizontal, m.dp(14))
            .padding(.vertical, m.dp(7))
            .background(Color.black.opacity(0.78), in: Capsule())
            .transition(.opacity.combined(with: .scale(scale: 0.96)))
    }
}

struct EmptyState: View {
    let text: String
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var body: some View {
        Text(text)
            .font(.system(size: m.sp(13)))
            .foregroundStyle(theme.textFaint)
            .frame(maxWidth: .infinity)
            .padding(.vertical, m.dp(60))
    }
}

// MARK: - 安全区

private struct SafeInsetsKey: EnvironmentKey {
    static let defaultValue = EdgeInsets()
}

extension EnvironmentValues {
    var safeAreaInsets: EdgeInsets {
        get { self[SafeInsetsKey.self] }
        set { self[SafeInsetsKey.self] = newValue }
    }
}
