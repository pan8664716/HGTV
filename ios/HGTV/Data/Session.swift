// SPDX-License-Identifier: GPL-3.0-only
//
// 本机身份与会话。
//
// 与 Android 的落盘键同名（`registered-device-id` / `registered-install-id` / `device-profile`），
// 语义也一致：设备注册下发的那一对身份要能跨启动复用，否则每次冷启动都要重新注册，
// 平台会把请求看成一台新设备（搜索空响应、播放 101000）。

import Foundation
import Combine

// MARK: - 设备身份

struct DeviceIdentity: Codable {
    var deviceId: String
    var installId: String
    var cdid: String
    var openudid: String
    var profile: [String: String]
    /// 注册响应下发的 Cookie（`store-region` / `install_id` / `ttreq` …）。
    /// 与 Android 的 `registrationCookies` 同一角色：搜索这类接口会按它判设备。
    var cookies: [String: String] = [:]
}

enum IdentityStore {
    private static let deviceKey = "registered-device-id"
    private static let installKey = "registered-install-id"
    private static let cdidKey = "device-cdid"
    private static let openudidKey = "device-openudid"
    private static let profileKey = "device-profile"
    private static let cookiesKey = "registered-cookies"

    static var defaults: UserDefaults { .standard }

    static func load() -> DeviceIdentity? {
        let device = defaults.string(forKey: deviceKey) ?? ""
        let install = defaults.string(forKey: installKey) ?? ""
        guard !device.isEmpty, !install.isEmpty else { return nil }
        let profile = defaults.data(forKey: profileKey)
            .flatMap { try? JSONDecoder().decode([String: String].self, from: $0) } ?? [:]
        let cookies = defaults.data(forKey: cookiesKey)
            .flatMap { try? JSONDecoder().decode([String: String].self, from: $0) } ?? [:]
        return DeviceIdentity(deviceId: device, installId: install,
                              cdid: defaults.string(forKey: cdidKey) ?? "",
                              openudid: defaults.string(forKey: openudidKey) ?? "",
                              profile: profile, cookies: cookies)
    }

    static func save(_ identity: DeviceIdentity) {
        defaults.set(identity.deviceId, forKey: deviceKey)
        defaults.set(identity.installId, forKey: installKey)
        defaults.set(identity.cdid, forKey: cdidKey)
        defaults.set(identity.openudid, forKey: openudidKey)
        if let data = try? JSONEncoder().encode(identity.profile) {
            defaults.set(data, forKey: profileKey)
        }
        if let data = try? JSONEncoder().encode(identity.cookies) {
            defaults.set(data, forKey: cookiesKey)
        }
    }

    static func clear() {
        [deviceKey, installKey, cdidKey, openudidKey, profileKey, cookiesKey]
            .forEach { defaults.removeObject(forKey: $0) }
    }

    /// 首次启动时生成，之后固定：平台靠它们做设备去重。
    static func ensureSeed() -> (cdid: String, openudid: String) {
        var cdid = defaults.string(forKey: cdidKey) ?? ""
        var openudid = defaults.string(forKey: openudidKey) ?? ""
        if cdid.isEmpty {
            cdid = UUID().uuidString
            defaults.set(cdid, forKey: cdidKey)
        }
        if openudid.isEmpty {
            openudid = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))
            defaults.set(openudid, forKey: openudidKey)
        }
        return (cdid, openudid)
    }
}

// MARK: - 账号会话

struct AccountSession: Codable {
    var cookie: String = ""
    var token: String = ""
    var nickname: String = ""
    var displayId: String = ""
    var expiresAt: Int64 = 0
    var savedAt: Int64 = 0

    var configured: Bool { !cookie.isEmpty || !token.isEmpty }
    /// 平台会话大约 30 天；过期后由心跳续期，这里只做「明显过期」的兜底。
    var alive: Bool {
        configured && (expiresAt <= 0 || expiresAt > Int64(Date().timeIntervalSince1970))
    }
}

@MainActor
final class SessionStore: ObservableObject {
    static let shared = SessionStore()

    @Published private(set) var session: AccountSession

    private let key = "account-session-v1"

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let saved = try? JSONDecoder().decode(AccountSession.self, from: data) {
            session = saved
        } else {
            session = AccountSession()
        }
    }

    var signedIn: Bool { session.alive }

    func update(_ next: AccountSession) {
        session = next
        if let data = try? JSONEncoder().encode(next) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    func signOut() {
        update(AccountSession())
        // 退出登录时把账号域的 Cookie 一并清掉，否则下次启动又被「救回来」。
        HTTPCookieStorage.shared.cookies?.forEach { cookie in
            if cookie.domain.contains("fqnovel.com") { HTTPCookieStorage.shared.deleteCookie(cookie) }
        }
    }

    /// 内容接口要带的那一条 Cookie 头。
    var cookieHeader: String {
        if !session.cookie.isEmpty { return session.cookie }
        let cookies = HTTPCookieStorage.shared.cookies ?? []
        return cookies.filter { $0.domain.contains("fqnovel.com") }
            .map { "\($0.name)=\($0.value)" }
            .joined(separator: "; ")
    }
}

// MARK: - 扫码登录

@MainActor
final class QrLoginService: ObservableObject {
    static var host: String {
        let value = TncConfigStore.shared.preferredHost("lq")
        return "https://\(value)"
    }
    static let aid = "513755"

    struct Code {
        var token: String
        var png: Data
        var expireAt: Int64
    }

    enum Outcome {
        case waiting
        case scanned
        case confirmed(AccountSession)
        case renewed(Code)
        case failed(String)
    }

    private var jar: [String: String] = [:]
    private var token: String = ""

    private func endpoint(_ path: String, _ params: [(String, String)]) -> String {
        var comps = URLComponents(string: QrLoginService.host + path)!
        comps.queryItems = params.map { URLQueryItem(name: $0.0, value: $0.1) }
        return comps.url?.absoluteString ?? (QrLoginService.host + path)
    }

    private func request(_ path: String, _ params: [(String, String)]) async -> [String: Any]? {
        let url = endpoint(path, params)
        var request = URLRequest(url: URL(string: url)!)
        request.timeoutInterval = 20
        request.setValue(Vendor.userAgent(Vendor.appDevice, video: false), forHTTPHeaderField: "User-Agent")
        request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        request.setValue(QrLoginService.host + "/", forHTTPHeaderField: "Referer")
        if !jar.isEmpty {
            request.setValue(jar.map { "\($0.key)=\($0.value)" }.joined(separator: "; "), forHTTPHeaderField: "Cookie")
        }
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse else { return nil }
        // 响应里的 Set-Cookie 由系统 CookieStorage 收下，这里再读一遍补进本地 jar。
        HTTPCookieStorage.shared.cookies(for: URL(string: url)!)?.forEach { jar[$0.name] = $0.value }
        if let token = http.value(forHTTPHeaderField: "X-Tt-Token"), !token.isEmpty {
            self.token = token
        }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        return json
    }

    func fetchCode() async -> Code? {
        guard let json = await request("/passport/web/get_qrcode/",
                                       [("next", QrLoginService.host + "/"), ("aid", QrLoginService.aid), ("language", "zh")]),
              let data = json.obj("data") else { return nil }
        let error = data.str("error_code")
        if !error.isEmpty && error != "0" { return nil }
        guard let png = decodeImage(data.str("qrcode")) else { return nil }
        return Code(token: data.str("token"), png: png, expireAt: data.int64("expire_time"))
    }

    func check(token: String) async -> Outcome {
        guard !token.isEmpty else { return .failed("二维码已失效，请刷新") }
        guard let json = await request("/passport/web/check_qrconnect/",
                                       [("next", QrLoginService.host + "/"), ("token", token), ("aid", QrLoginService.aid)]),
              let data = json.obj("data") else { return .failed("网络不可用，请稍后重试") }
        let error = data.str("error_code")
        if !error.isEmpty && error != "0" {
            return .failed(data.str("description").isEmpty ? "扫码状态读取失败（\(error)）" : data.str("description"))
        }
        switch data.str("status").lowercased() {
        case "1", "new":
            return .waiting
        case "2", "scanned":
            return .scanned
        case "3", "confirmed":
            let user = data.obj("user_data") ?? [:]
            let nickname = user.str("screen_name").isEmpty ? user.str("name") : user.str("screen_name")
            let session = AccountSession(cookie: cookieHeader(), token: token,
                                         nickname: nickname, displayId: "",
                                         expiresAt: Int64(Date().timeIntervalSince1970) + 30 * 24 * 3600,
                                         savedAt: Int64(Date().timeIntervalSince1970))
            return .confirmed(session)
        case "4", "5", "expired":
            guard let png = decodeImage(data.str("qrcode")), !data.str("token").isEmpty else {
                return .failed("二维码已失效，请刷新")
            }
            return .renewed(Code(token: data.str("token"), png: png, expireAt: data.int64("expire_time")))
        default:
            return .waiting
        }
    }

    private func cookieHeader() -> String {
        let cookies = HTTPCookieStorage.shared.cookies ?? []
        var merged = jar
        for cookie in cookies where cookie.domain.contains("fqnovel.com") {
            merged[cookie.name] = cookie.value
        }
        return merged.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
    }

    private func decodeImage(_ value: String) -> Data? {
        guard !value.isEmpty else { return nil }
        let payload = value.components(separatedBy: "base64,").last ?? value
        return Data(base64Encoded: payload, options: .ignoreUnknownCharacters)
    }
}
