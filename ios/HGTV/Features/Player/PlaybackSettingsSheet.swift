// SPDX-License-Identifier: GPL-3.0-only
//
// 播放设置抽屉：倍速 / 清晰度 / 画面模式。
// 与 Android `PhoneSheet` 的「播放设置」面板同一组项目与同一条生效时机
// （清晰度改完重新取地址，画面模式立刻生效，倍速立刻生效）。

import SwiftUI

struct PlaybackSettingsSheet: View {
    @ObservedObject private var library = Library.shared
    @Environment(\.dismiss) private var dismiss
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var speed: Float
    var onSpeed: (Float) -> Void
    var onQuality: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: m.dp(10)) {
            HStack {
                Text("播放设置")
                    .font(.system(size: m.sp(16), weight: .bold))
                    .foregroundStyle(theme.text)
                Spacer()
                Button { dismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: m.sp(13), weight: .semibold))
                        .foregroundStyle(theme.textDim)
                }
                .buttonStyle(.plain)
            }

            Text("倍速")
                .font(.system(size: m.sp(12)))
                .foregroundStyle(theme.textDim)
            HStack(spacing: m.dp(8)) {
                ForEach(PlaybackSpeed.options, id: \.self) { value in
                    Button {
                        onSpeed(value)
                    } label: {
                        ChipLabel(text: PlaybackSpeed.label(value), selected: abs(speed - value) < 0.01, inset: true)
                    }
                    .buttonStyle(.plain)
                }
            }

            Text("清晰度上限")
                .font(.system(size: m.sp(12)))
                .foregroundStyle(theme.textDim)
                .padding(.top, m.dp(4))
            HStack(spacing: m.dp(8)) {
                ForEach(PlaybackQuality.options, id: \.self) { value in
                    Button {
                        library.prefs.maxQuality = value
                        onQuality(value)
                    } label: {
                        ChipLabel(text: "\(value)P", selected: library.prefs.maxQuality == value, inset: true)
                    }
                    .buttonStyle(.plain)
                }
            }

            Text("画面模式")
                .font(.system(size: m.sp(12)))
                .foregroundStyle(theme.textDim)
                .padding(.top, m.dp(4))
            HStack(spacing: m.dp(8)) {
                ForEach(VideoFrameMode.allCases, id: \.self) { value in
                    Button {
                        library.prefs.frameMode = value
                    } label: {
                        ChipLabel(text: value.label, selected: library.prefs.frameMode == value, inset: true)
                    }
                    .buttonStyle(.plain)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(m.dp(18))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(theme.baseColor)
    }
}
