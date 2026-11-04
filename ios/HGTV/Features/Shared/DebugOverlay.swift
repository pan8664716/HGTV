// SPDX-License-Identifier: GPL-3.0-only
//
// 调试面板：设置 → 系统高级 → 调试模式。
// 与 Android 的 `PhoneDebugOverlay` 同一个角色：页面角落常驻一小块现场读数，
// 用户报「搜不到 / 放不了」时先看这里——设备是否注册、走的哪条线路、当前主题与形态。

import SwiftUI

struct DebugOverlay: View {
    @Environment(\.metrics) private var m

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            VStack(alignment: .leading, spacing: 2) {
                ForEach(readouts, id: \.self) { line in
                    Text(line)
                        .font(.system(size: max(8, m.sp(9)), design: .monospaced))
                        .foregroundStyle(.white.opacity(0.86))
                }
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 4)
            .background(Color.black.opacity(0.62), in: RoundedRectangle(cornerRadius: 5))
        }
        .allowsHitTesting(false)
    }

    private var readouts: [String] {
        let identity = IdentityStore.load()
        let library = Library.shared
        return [
            "HGTV iOS 1.3.0",
            "设备 \(identity?.deviceId.suffix(8) ?? "未注册")",
            "安装 \(identity?.installId.suffix(6) ?? "-")",
            "主题 \(PhoneTheme.of(library.prefs.theme).label) · 列 \(library.prefs.gridColumns)",
            "清晰度 \(library.prefs.maxQuality)P · 倍速 \(PlaybackSpeed.label(library.prefs.playbackSpeed))",
            "记录 历\(library.data.history.count) 收\(library.data.favorites.count) 后\(library.data.later.count)",
            "登录 \(SessionStore.shared.signedIn ? "是" : "否") · 隐藏 \(library.data.hidden.count)"
        ]
    }
}
