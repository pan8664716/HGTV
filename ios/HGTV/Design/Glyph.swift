// SPDX-License-Identifier: GPL-3.0-only
//
// 自绘图标：底部导航的四枚（首页 / 分类 / 榜单 / 我的）与播放界面的实心三角。
//
// 为什么不用 SF Symbols：底部导航是整套版面的骨架，Android 端那四枚图标是自绘的，
// 形状（屋顶 + 圆角门、四宫格、三根高低柱、头像 + 肩线）本身就是视觉识别的一部分。
// 换成系统图标，四个 tab 会立刻变成「另一个应用」。其余入口（搜索、设置、返回…）
// 用系统符号，那是 iOS 用户的肌肉记忆，不冲突。

import SwiftUI

enum TabIcon {
    case home, grid, rank, mine
}

struct TabGlyph: View {
    let kind: TabIcon
    var tint: Color
    var lineWidth: CGFloat = 1.4

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let stroke = StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)

            switch kind {
            case .home:
                var roof = Path()
                roof.move(to: CGPoint(x: w * 0.18, y: h * 0.48))
                roof.addLine(to: CGPoint(x: w * 0.5, y: h * 0.2))
                roof.addLine(to: CGPoint(x: w * 0.82, y: h * 0.48))
                ctx.stroke(roof, with: .color(tint), style: stroke)
                let door = CGRect(x: w * 0.26, y: h * 0.46, width: w * 0.48, height: h * 0.36)
                ctx.stroke(Path(roundedRect: door, cornerRadius: w * 0.06),
                           with: .color(tint), style: stroke)

            case .grid:
                let pad = w * 0.18
                let cell = (w - pad * 2) / 2
                let gap = cell * 0.16
                for r in 0..<2 {
                    for c in 0..<2 {
                        let rect = CGRect(x: pad + CGFloat(c) * (cell + gap),
                                          y: pad + CGFloat(r) * (cell + gap),
                                          width: cell - gap, height: cell - gap)
                        ctx.fill(Path(roundedRect: rect, cornerRadius: gap), with: .color(tint))
                    }
                }

            case .rank:
                let w3 = w / 3
                let base = h * 0.84
                let heights: [CGFloat] = [h * 0.34, h * 0.56, h * 0.24]
                for i in 0..<3 {
                    let rect = CGRect(x: w3 * CGFloat(i) + w3 * 0.18,
                                      y: base - heights[i],
                                      width: w3 * 0.64,
                                      height: heights[i])
                    ctx.fill(Path(roundedRect: rect, cornerRadius: w * 0.04), with: .color(tint))
                }

            case .mine:
                let head = CGRect(x: w * 0.35, y: h * 0.18, width: w * 0.30, height: w * 0.30)
                ctx.fill(Path(ellipseIn: head), with: .color(tint))
                var shoulders = Path()
                shoulders.addArc(center: CGPoint(x: w * 0.5, y: h * 0.52),
                                 radius: w * 0.28,
                                 startAngle: .degrees(180), endAngle: .degrees(0),
                                 clockwise: false)
                ctx.stroke(shoulders, with: .color(tint), style: stroke)
            }
        }
    }
}

/// 播放三角：Android 端在 12dp 画布里左侧留白 4dp，容器再收 2dp 才能和文字对齐。
struct PlayGlyph: View {
    var tint: Color
    var body: some View {
        Canvas { ctx, size in
            var path = Path()
            path.move(to: CGPoint(x: size.width * 0.28, y: size.height * 0.14))
            path.addLine(to: CGPoint(x: size.width * 0.88, y: size.height * 0.5))
            path.addLine(to: CGPoint(x: size.width * 0.28, y: size.height * 0.86))
            path.closeSubpath()
            ctx.fill(path, with: .color(tint))
        }
    }
}

struct PauseGlyph: View {
    var tint: Color
    var body: some View {
        HStack(spacing: 3) {
            RoundedRectangle(cornerRadius: 1).fill(tint).frame(width: 4)
            RoundedRectangle(cornerRadius: 1).fill(tint).frame(width: 4)
        }
        .padding(.vertical, 3)
    }
}
