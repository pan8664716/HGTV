// SPDX-License-Identifier: GPL-3.0-only
//
// 弹幕层：自绘（Canvas + TimelineView），与 Android `DanmakuView` 的七档模式对应
// （隐藏 / 一行 / 两行 / 三行 / 四行 / 上方 / 全屏），相同弹幕按设置聚合。
//
// 弹幕数据来自内容接口；取不到就整层不画——它只是附加内容，不能影响播放。

import SwiftUI

struct DanmakuLayer: View {
    let items: [DanmakuItem]
    let position: Double

    @ObservedObject private var library = Library.shared
    @Environment(\.metrics) private var m

    /// 一条弹幕在屏幕上存活的时间（秒）：与 Android 的滚动时长同量级。
    private let life: Double = 8

    var body: some View {
        let mode = library.prefs.danmakuMode
        if mode == .hidden || items.isEmpty {
            EmptyView()
        } else {
            TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { _ in
                Canvas { context, size in
                    draw(context: &context, size: size, mode: mode)
                }
            }
            .allowsHitTesting(false)
        }
    }

    private func draw(context: inout GraphicsContext, size: CGSize, mode: DanmakuMode) {
        let fontSize = max(10, m.sp(15) * CGFloat(library.prefs.danmakuSizePercent) / 100)
        let opacity = Double(library.prefs.danmakuAlpha)
        let speedFactor = Double(library.prefs.danmakuSpeed.factor)
        let laneHeight = fontSize * 1.6
        let laneCount: Int
        let maxFullLanes = max(1, Int(size.height * 0.75 / laneHeight))
        switch mode {
        case .hidden: laneCount = 0
        case .single: laneCount = 1
        case .two: laneCount = min(2, maxFullLanes)
        case .three: laneCount = min(3, maxFullLanes)
        case .four: laneCount = min(4, maxFullLanes)
        case .top: laneCount = max(1, Int(size.height * 0.35 / laneHeight))
        case .full: laneCount = maxFullLanes
        }
        guard laneCount > 0 else { return }

        var lanes = [Int](repeating: 0, count: laneCount)
        let visible = items.filter { item in
            let seconds = Double(item.offsetMs) / 1000
            return seconds <= position && seconds > position - life
        }

        for (order, item) in visible.enumerated() {
            let seconds = Double(item.offsetMs) / 1000
            let progress = (position - seconds) / life / speedFactor
            guard progress >= 0, progress <= 1 else { continue }
            let text = mergedText(item, at: order)
            let resolved = context.resolve(
                Text(text)
                    .font(.system(size: fontSize, weight: .medium))
                    .foregroundColor(PhoneTheme.playerText.opacity(opacity))
            )
            let width = resolved.measure(in: size).width
            let x = size.width - progress * (size.width + width + 20)
            let lane = order % laneCount
            lanes[lane] += 1
            let y = laneHeight * CGFloat(lane) + laneHeight * 0.9 +
                (mode == .full ? size.height * 0.12 : size.height * 0.08)
            context.draw(resolved, at: CGPoint(x: x, y: y), anchor: .leading)
        }
    }

    /// 相同弹幕聚合：`这剧好看 ×4`（与 Android 的聚合口径一致：2–10 条起聚）。
    private func mergedText(_ item: DanmakuItem, at index: Int) -> String {
        guard library.prefs.danmakuMerge else { return item.text }
        let window = Double(max(2, library.prefs.danmakuMergeMin))
        let count = items.filter {
            $0.text == item.text && abs(Double($0.offsetMs - item.offsetMs)) / 1000 <= window
        }.count
        return count >= max(2, library.prefs.danmakuMergeMin) ? "\(item.text) ×\(count)" : item.text
    }
}
