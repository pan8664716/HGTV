// SPDX-License-Identifier: GPL-3.0-only
//
// 设备注册：`POST /service/2/device_register/`（协议见 docs/DEVICE_REGISTER.md）。
//
// 平台按「服务端注册过的 device_id + install_id 这一对」放行内容接口：
// 没注册的设备，App 搜索回 `HTTP 200 + 0 字节`、播放回 `101000 服务异常`。
// 注册本身是匿名的，请求里**不带任何签名头**（带了反而被判异常客户端）。

import Foundation

enum DeviceRegister {
    struct Registered {
        var deviceId: String
        var installId: String
        var deviceToken: String = ""
        var newUser: Bool = false
        var cookies: [String: String] = [:]
    }

    private static let path = "/service/2/device_register/"
    /// ttEncrypt 的固定前缀（libEncryptor.so 的魔数）。
    private static let magic: [UInt8] = [0x74, 0x63, 0x05, 0x10, 0x00, 0x00]
    /// ttEncrypt 的 KDF 盐。
    private static let kdfSalt = Bytes.hex(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )

    /// `MAGIC || rnd[32] || AES-128-CBC(SHA512(SHA512(rnd) + salt)[0:32], PKCS7(SHA512(data) + data))`
    static func ttEncrypt(_ data: [UInt8]) -> [UInt8] {
        var rnd = [UInt8](repeating: 0, count: 32)
        for i in 0..<32 { rnd[i] = UInt8.random(in: 0...255) }
        let derived = Hash.sha512(Hash.sha512(rnd) + kdfSalt)
        var body = Hash.sha512(data) + data
        let padding = 16 - body.count % 16
        body += [UInt8](repeating: UInt8(padding), count: padding)
        let encrypted = AES.cbcEncrypt(key: Array(derived[0..<16]), iv: Array(derived[16..<32]), data: body)
        return magic + rnd + encrypted
    }

    private static func value(_ device: [String: String], _ key: String, _ fallback: String) -> String {
        if let v = device[key], !v.isEmpty { return v }
        return fallback
    }

    static func payload(device: [String: String], now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> [UInt8] {
        let brand = value(device, "device_brand", "Redmi")
        let model = value(device, "device_type", "25053RT47C")
        let manufacturer: String
        switch brand.lowercased() {
        case let b where b.contains("samsung"): manufacturer = "Samsung"
        case let b where b.contains("redmi") || b.contains("xiaomi"): manufacturer = "Xiaomi"
        case let b where b.contains("huawei") || b.contains("honor"): manufacturer = "HUAWEI"
        case let b where b.contains("oppo"): manufacturer = "OPPO"
        case let b where b.contains("vivo"): manufacturer = "vivo"
        default: manufacturer = brand
        }
        let resolution = value(device, "resolution", "1280*2772").replacingOccurrences(of: "*", with: "x")
        let dpi = Int(value(device, "dpi", "520")) ?? 520
        let density: String
        if dpi >= 560 { density = "xxxhdpi" }
        else if dpi >= 440 { density = "xxhdpi" }
        else if dpi >= 280 { density = "xhdpi" }
        else if dpi >= 200 { density = "hdpi" }
        else { density = "mdpi" }

        var header: [String: Any] = [
            "display_name": "红果免费短剧",
            "update_version_code": 73532,
            "manifest_version_code": 73532,
            "app_version_minor": "",
            "aid": 8662,
            "channel": value(device, "channel", "update_64"),
            "package": "com.phoenix.read",
            "app_version": "7.3.5.32",
            "version_code": 73532,
            "sdk_version": "3.7.2",
            "sdk_target_version": 29,
            "os": "Android",
            "os_version": value(device, "os_version", "12"),
            "os_api": Int(value(device, "os_api", "32")) ?? 32,
            "device_model": model,
            "device_brand": brand,
            "device_manufacturer": manufacturer,
            "cpu_abi": "arm64-v8a",
            "density_dpi": dpi,
            "display_density": density,
            "resolution": resolution,
            "language": "zh",
            "timezone": 8,
            "access": "wifi",
            "not_request_sender": 0,
            "cdid": value(device, "cdid", UUID().uuidString),
            "openudid": value(device, "openudid",
                              String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))),
            "clientudid": value(device, "clientudid", UUID().uuidString),
            "ipv6_list": [],
            "region": "CN",
            "tz_name": "Asia/Shanghai",
            "tz_offset": 28800,
            "sim_region": "cn",
            "sim_serial_number": [],
            "oaid_may_support": true,
            "req_id": UUID().uuidString,
            "device_platform": "android",
            "custom": [
                "account_region_src": "fallback",
                "host_bit": 64,
                "account_region": "cn",
                "dragon_device_type": value(device, "dragon_device_type", "pad"),
                "is_android_fold": 0,
                "filter_warn": 0,
                "web_ua": "",
                "is_android_pad": 0
            ] as [String: Any],
            "pre_installed_channel": "",
            "apk_first_install_time": now,
            "is_system_app": 0,
            "sdk_flavor": "china",
            "guest_mode": 0
        ]
        if let rom = device["rom_version"], !rom.isEmpty { header["rom_version"] = rom }
        let json: [String: Any] = ["magic_tag": "ss_app_log", "header": header, "_gen_time": now]
        let data = (try? JSONSerialization.data(withJSONObject: json, options: [])) ?? Data()
        return ttEncrypt(Gzip.wrap([UInt8](data)))
    }

    static func headers(body: [UInt8], device: [String: String],
                        now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> [String: String] {
        [
            "User-Agent": Vendor.headerSafe(device["user_agent"], Vendor.userAgent(device, video: false)),
            "Content-Type": "application/octet-stream;tt-data=a",
            "Accept": "application/json; charset=utf-8,application/x-protobuf",
            "Content-Encoding": "gzip",
            "log-encode-type": "gzip",
            "X-Tt-Request-Tag": "t=0;n=1",
            "x-ss-dp": "8662",
            "x-ss-req-ticket": "\(now)",
            "x-vc-bdturing-sdk-version": "4.0.3.cn",
            "lc": "102",
            "sdk-version": "2",
            "passport-sdk-version": "60755",
            "passport-sdk-settings": "device_transfer_s_0,device_transfer_ab_1",
            "x-tt-store-region": "cn",
            "x-tt-store-region-src": "did",
            "x-neptune": "-8|50:51:59:30:40:47:49:39",
            "x-ss-stub": Bytes.hex(Hash.md5(body)).uppercased()
        ]
    }

    static func url(device: [String: String],
                    now: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
                    host: String? = nil) -> String {
        var query: [(String, String)] = [
            ("ac", "wifi"), ("aid", "8662"), ("app_name", "novelread"),
            ("channel", value(device, "channel", "update_64")),
            ("device_brand", value(device, "device_brand", "Redmi")),
            ("device_platform", "android"),
            ("device_type", value(device, "device_type", "25053RT47C")),
            ("dpi", value(device, "dpi", "520")), ("language", "zh"),
            ("manifest_version_code", value(device, "manifest_version_code", "73532")),
            ("os", "android"), ("os_api", value(device, "os_api", "36")),
            ("os_version", value(device, "os_version", "16")),
            ("resolution", value(device, "resolution", "1280*2772")),
            ("ssmix", "a"),
            ("update_version_code", value(device, "update_version_code", "73532")),
            ("version_code", value(device, "version_code", "73532")),
            ("version_name", value(device, "version_name", "7.3.5.32"))
        ]
        if let cdid = device["cdid"], !cdid.isEmpty { query.append(("cdid", cdid)) }
        if let openudid = device["openudid"], !openudid.isEmpty { query.append(("openudid", openudid)) }
        query += [("tt_data", "a"), ("package", "com.phoenix.read"),
                  ("ts", "\(now / 1000)"), ("_rticket", "\(now)")]
        let baseHost = host ?? TncConfigStore.shared.logHosts().first ?? "log.snssdk.com"
        return "https://" + baseHost + path + "?" +
            query.map { "\($0.0)=\($0.1)" }.joined(separator: "&")
    }

    static func parse(body: String, setCookies: [String] = []) -> Registered? {
        guard let data = body.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let device = (json["device_id_str"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? String(describing: json["device_id"] ?? "")
        let install = (json["install_id_str"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? String(describing: json["install_id"] ?? "")
        if device.isEmpty || install.isEmpty || device == "0" || install == "0" { return nil }
        var cookies: [String: String] = [:]
        for header in setCookies {
            guard let first = header.split(separator: ";").first else { continue }
            let pair = first.trimmingCharacters(in: .whitespaces)
            guard let eq = pair.firstIndex(of: "=") else { continue }
            let name = String(pair[pair.startIndex..<eq]).trimmingCharacters(in: .whitespaces)
            let value = String(pair[pair.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
            if !name.isEmpty && !value.isEmpty { cookies[name] = value }
        }
        return Registered(deviceId: device, installId: install,
                          deviceToken: json["device_token"] as? String ?? "",
                          newUser: (json["new_user"] as? Int ?? 0) == 1,
                          cookies: cookies)
    }

    /// 真正发一次注册请求。失败返回 nil，调用方继续用内置身份（会缺内容，但不崩）。
    static func register(device: [String: String]) async -> Registered? {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let body = payload(device: device, now: now)
        for host in TncConfigStore.shared.logHosts() {
            guard let url = URL(string: url(device: device, now: now, host: host)) else { continue }
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.httpBody = Data(body)
            request.timeoutInterval = AppConfig.Network.deviceRegisterTimeout
            for (key, value) in headers(body: body, device: device, now: now) {
                request.setValue(value, forHTTPHeaderField: key)
            }
            guard let (data, response) = try? await URLSession.shared.data(for: request),
                  let http = response as? HTTPURLResponse, http.statusCode < 400,
                  let text = String(data: data, encoding: .utf8),
                  let registered = parse(body: text, setCookies: []) else { continue }
            var cookies: [String: String] = [:]
            HTTPCookieStorage.shared.cookies(for: url)?.forEach { cookies[$0.name] = $0.value }
            return Registered(deviceId: registered.deviceId,
                              installId: registered.installId,
                              deviceToken: registered.deviceToken,
                              newUser: registered.newUser,
                              cookies: cookies)
        }
        return nil
    }
}
