// SPDX-License-Identifier: GPL-3.0-only
//
// 新拟态柔光。Android 端把「右下深投影 + 左上浅高光」预渲染成半分辨率位图
// （`shared/ui/Soft.kt`），因为 GradientDrawable 没有模糊；iOS 的 CALayer 阴影
// 本身就是模糊的，所以这里直接画两层阴影，层次与 Android 一一对应：
//
//   凸起 raised = surface 底 + 右下 shadowDark + 左上 shadowLight + 顶部 1px 高光
//   凹陷 inset  = well 底 + 内侧左上 shadowDark + 内侧右下 shadowLight
//   描边 ring   = 透明底 + 一圈 outline（用于压在封面上的角标）
//   暗角 shade  = 透明底 + 顶部到底部的黑色渐变（海报井）
//
// 差别只有一处：Android 把柔光画在控件自己的 padding 里（受滚动容器裁剪限制），
// iOS 的阴影默认画在边界之外，因此这里不再额外留柔光带——同样的视觉，更少的空白。

import SwiftUI

enum SoftKind {
    case raised
    case inset
    case ring
    case shade
}

struct SoftModifier: ViewModifier {
    let kind: SoftKind
    let fill: Color
    let radius: CGFloat
    let depth: CGFloat

    @Environment(\.theme) private var theme

    func body(content: Content) -> some View {
        switch kind {
        case .raised:
            content
                .background {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .fill(fill)
                        .shadow(color: theme.shadowDark.opacity(0.9),
                                radius: depth * 2.2, x: depth * 0.8, y: depth * 1.1)
                        .shadow(color: theme.shadowLight.opacity(0.85),
                                radius: depth * 2.0, x: -depth * 0.8, y: -depth * 0.8)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .strokeBorder(theme.shadowLight.opacity(0.28), lineWidth: 0.6)
                }
        case .inset:
            content
                .background {
                    RoundedRectangle(cornerRadius: radius, style: .continuous).fill(fill)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .strokeBorder(theme.innerDark.opacity(0.55), lineWidth: depth * 1.6)
                        .blur(radius: depth * 1.2)
                        .offset(x: depth * 0.6, y: depth * 0.6)
                        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
                }
                .overlay {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .strokeBorder(theme.innerLight.opacity(0.55), lineWidth: depth * 1.2)
                        .blur(radius: depth)
                        .offset(x: -depth * 0.5, y: -depth * 0.5)
                        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
                }
        case .ring:
            content
                .background {
                    RoundedRectangle(cornerRadius: radius, style: .continuous).fill(fill)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .strokeBorder(theme.outline, lineWidth: 1)
                }
        case .shade:
            content
                .background {
                    RoundedRectangle(cornerRadius: radius, style: .continuous).fill(fill)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .fill(LinearGradient(colors: [.black.opacity(0.55), .clear],
                                             startPoint: .bottom, endPoint: .top))
                }
        }
    }
}

extension View {
    func soft(_ kind: SoftKind = .raised,
              fill: Color,
              radius: CGFloat,
              depth: CGFloat = 1) -> some View {
        modifier(SoftModifier(kind: kind, fill: fill, radius: radius, depth: depth))
    }
}

/// 可按压的凸起面板：按下时轻微变暗并「按进去」一格（与 Android 的 `alpha=.9` 对应）。
struct SoftPressStyle: ButtonStyle {
    var radius: CGFloat
    var fill: Color
    @Environment(\.theme) private var theme

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .soft(.raised, fill: configuration.isPressed ? theme.surfaceHigh : fill, radius: radius)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: AppConfig.Ui.pressAnimationDuration),
                       value: configuration.isPressed)
    }
}

/// 底部/顶部的压暗遮罩（与 `bottomScrim` / `topScrim` 的不等距色标一致）。
struct BottomScrim: View {
    var alpha: Double = 0.92
    var body: some View {
        LinearGradient(stops: [
            .init(color: .black.opacity(alpha), location: 0.0),
            .init(color: .black.opacity(alpha * 0.55), location: 0.45),
            .init(color: .black.opacity(alpha * 0.18), location: 0.78),
            .init(color: .clear, location: 1.0)
        ], startPoint: .bottom, endPoint: .top)
    }
}

struct TopScrim: View {
    var alpha: Double = 0.6
    var body: some View {
        LinearGradient(colors: [.black.opacity(alpha), .clear],
                       startPoint: .top, endPoint: .bottom)
    }
}
