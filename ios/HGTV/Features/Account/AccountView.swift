// SPDX-License-Identifier: GPL-3.0-only
//
// 账号页：扫码登录 + 本机记录概览 + 退出登录。
//
// 与 Android 手机端同一条主路径（扫码登录）：出码 → 每 1.1 秒轮询 →
// 手机确认后会话 Cookie 直接落地；二维码过期时平台在响应里自带新码，原地续期。
//
// 手机号验证码登录依赖 Passport 的签名链（`core/PassportSigner.kt`，另一套头与
// 加密），本轮没有移植，见 docs/IOS_PORT.md「已知边界」；扫码是等价入口。

import SwiftUI

struct AccountView: View {
    @ObservedObject private var library = Library.shared
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @Environment(\.safeAreaInsets) private var insets

    @StateObject private var qr = QrLoginService()
    @State private var code: QrLoginService.Code?
    @State private var image: UIImage?
    @State private var status = "正在获取二维码…"
    @State private var scanning = false

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: m.dp(14)) {
                    if session.signedIn {
                        signedInPanel
                    } else {
                        loginPanel
                    }
                    localPanel
                    Text("登录只用来同步云端收藏、历史与预约；不登录也能浏览与播放。")
                        .font(.system(size: m.sp(11)))
                        .foregroundStyle(theme.textFaint)
                        .padding(.top, m.dp(4))
                    Spacer(minLength: m.dp(30) + insets.bottom)
                }
                .padding(.horizontal, m.dp(16))
                .padding(.top, m.dp(8))
            }
            .scrollIndicators(.hidden)
        }
        .background(theme.baseColor.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
        .task { await startLogin() }
    }

    private var header: some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: m.sp(16), weight: .semibold))
                    .foregroundStyle(theme.text)
                    .frame(width: m.dp(34), height: m.dp(34))
            }
            .buttonStyle(.plain)
            Text("账号")
                .font(.system(size: m.sp(19), weight: .bold))
                .foregroundStyle(theme.text)
            Spacer()
        }
        .padding(.horizontal, m.dp(10))
        .padding(.top, insets.top + m.dp(6))
        .padding(.bottom, m.dp(8))
    }

    private var loginPanel: some View {
        VStack(alignment: .leading, spacing: m.dp(10)) {
            Text("扫码登录")
                .font(.system(size: m.sp(16), weight: .bold))
                .foregroundStyle(theme.text)
            Text("用红果 App 扫一扫，手机上确认后即登录。")
                .font(.system(size: m.sp(12)))
                .foregroundStyle(theme.textDim)

            HStack {
                Spacer()
                ZStack {
                    RoundedRectangle(cornerRadius: m.dp(12), style: .continuous)
                        .fill(Color.white)
                        .frame(width: m.dp(180), height: m.dp(180))
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .interpolation(.none)
                            .frame(width: m.dp(164), height: m.dp(164))
                    } else {
                        ProgressView()
                    }
                }
                Spacer()
            }
            .padding(.vertical, m.dp(6))

            HStack(spacing: m.dp(8)) {
                Text(status)
                    .font(.system(size: m.sp(12)))
                    .foregroundStyle(theme.textFaint)
                Spacer()
                Button("刷新二维码") {
                    Task { await startLogin(force: true) }
                }
                .font(.system(size: m.sp(12)))
                .foregroundStyle(theme.accent)
                .buttonStyle(.plain)
            }
        }
        .padding(m.dp(14))
        .soft(.raised, fill: theme.surface, radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(3)))
    }

    private var signedInPanel: some View {
        VStack(alignment: .leading, spacing: m.dp(12)) {
            HStack(spacing: m.dp(12)) {
                Text(String(session.session.nickname.prefix(1)).isEmpty ? "红" : String(session.session.nickname.prefix(1)))
                    .font(.system(size: m.sp(18)))
                    .foregroundStyle(theme.text)
                    .frame(width: m.dp(48), height: m.dp(48))
                    .soft(.raised, fill: theme.surfaceHigh, radius: m.dp(24), depth: max(1, m.dp(3)))
                VStack(alignment: .leading, spacing: m.dp(3)) {
                    Text(session.session.nickname.isEmpty ? "已登录" : session.session.nickname)
                        .font(.system(size: m.sp(17), weight: .bold))
                        .foregroundStyle(theme.text)
                    Text(session.session.displayId.isEmpty ? "会话已保存在本机" : "平台号 \(session.session.displayId)")
                        .font(.system(size: m.sp(12)))
                        .foregroundStyle(theme.textFaint)
                }
                Spacer()
            }

            Button {
                session.signOut()
                status = "已退出登录"
                code = nil
                image = nil
            } label: {
                Text("退出登录")
                    .font(.system(size: m.sp(13)))
                    .foregroundStyle(theme.danger)
                    .frame(maxWidth: .infinity)
                    .frame(height: m.dp(38))
                    .soft(.raised, fill: theme.surface, radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(3)))
            }
            .buttonStyle(.plain)
        }
        .padding(m.dp(14))
        .soft(.raised, fill: theme.surface, radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(3)))
    }

    private var localPanel: some View {
        VStack(alignment: .leading, spacing: m.dp(8)) {
            Text("本机记录")
                .font(.system(size: m.sp(14), weight: .semibold))
                .foregroundStyle(theme.text)
            HStack(spacing: 0) {
                stat("\(library.data.history.count)", "观看")
                stat("\(library.data.favorites.count)", "收藏")
                stat("\(library.data.later.count)", "稍后看")
            }
        }
        .padding(m.dp(14))
        .soft(.raised, fill: theme.surface, radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(3)))
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: m.dp(3)) {
            Text(value)
                .font(.system(size: m.sp(18), weight: .bold))
                .foregroundStyle(theme.text)
                .monospacedDigit()
            Text(label)
                .font(.system(size: m.sp(11)))
                .foregroundStyle(theme.textFaint)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: 扫码

    private func startLogin(force: Bool = false) async {
        if session.signedIn { return }
        if scanning && !force { return }
        scanning = true
        defer { scanning = false }
        status = "正在获取二维码…"
        guard let fresh = await qr.fetchCode() else {
            status = "二维码获取失败，请检查网络后重试"
            return
        }
        apply(fresh)
        await poll()
    }

    private func apply(_ fresh: QrLoginService.Code) {
        code = fresh
        image = UIImage(data: fresh.png)
        status = "等待手机扫码"
    }

    private func poll() async {
        while !Task.isCancelled {
            guard let token = code?.token else { return }
            let outcome = await qr.check(token: token)
            switch outcome {
            case .waiting:
                status = "等待手机扫码"
            case .scanned:
                status = "已扫码，请在手机上确认"
            case .renewed(let fresh):
                apply(fresh)
                status = "二维码已续期，继续等待"
            case .confirmed(let account):
                session.update(account)
                status = "登录成功"
                image = nil
                return
            case .failed(let message):
                status = message
                return
            }
            try? await Task.sleep(nanoseconds: 1_100_000_000)
        }
    }
}
