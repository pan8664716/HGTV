// SPDX-License-Identifier: GPL-3.0-only
//
// 启动入口。对应 Android 的 `HgtvApplication` + `LaunchActivity`：
// 启动时把设备身份 / 会话 / 书库读出来，并按设置决定首页（推荐或分类）。

import SwiftUI
import AVFoundation

@main
struct HGTVApp: App {
    @StateObject private var library = Library.shared

    init() {
        // 与 Android 一致：启动即开始读本机身份，内容请求会在后台等它。
        Task { await ContentRepository.shared.bootstrap() }
        configureAudioSession()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(library)
                .preferredColorScheme(PhoneTheme.of(library.prefs.theme).isDark ? .dark : .light)
                .tint(PhoneTheme.of(library.prefs.theme).accent)
        }
    }

    /// 播放要能在锁屏 / 切后台时继续出声（与 ExoPlayer 的 `setWakeMode` + 音频焦点同一条）。
    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .moviePlayback, options: [])
        try? session.setActive(true)
    }
}
