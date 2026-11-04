// SPDX-License-Identifier: GPL-3.0-only
//
// 传输层：URLSession + 显式 Cookie（账号会话跨主机共享）。
// 与 Android 的 OkHttp 配置对齐：15s 连接、30s 读、45s 整次调用上限。

import Foundation

enum HttpError: LocalizedError {
    case status(Int)
    case empty
    case network(String)
    case decode(String)

    var errorDescription: String? {
        switch self {
        case .status(let code): return "请求失败（HTTP \(code)）"
        case .empty: return "接口暂无响应"
        case .network(let text): return text
        case .decode(let text): return text
        }
    }
}

final class Http {
    static let shared = Http()

    private let session: URLSession

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 45
        config.httpShouldSetCookies = true
        config.httpCookieAcceptPolicy = .always
        config.httpCookieStorage = HTTPCookieStorage.shared
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.httpAdditionalHeaders = ["Accept-Language": "zh-CN,zh;q=0.9"]
        session = URLSession(configuration: config)
    }

    /// 发一条已经签好的请求，返回原始文本。
    func send(_ request: Signer.Request, timeout: TimeInterval? = nil) async throws -> String {
        guard let url = URL(string: request.url) else { throw HttpError.network("地址格式异常") }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method
        urlRequest.timeoutInterval = timeout ?? 30
        if let body = request.body { urlRequest.httpBody = Data(body) }
        for (key, value) in request.headers {
            urlRequest.setValue(value, forHTTPHeaderField: key)
        }
        do {
            let (data, response) = try await session.data(for: urlRequest)
            guard let http = response as? HTTPURLResponse else { throw HttpError.network("响应异常") }
            guard http.statusCode < 400 else { throw HttpError.status(http.statusCode) }
            let text = String(data: data, encoding: .utf8) ?? ""
            if text.isEmpty { throw HttpError.empty }
            return text
        } catch let error as HttpError {
            throw error
        } catch {
            throw HttpError.network(error.localizedDescription)
        }
    }

    /// 简单 GET（官网搜索、二维码图片这类不需要签名的请求）。
    func get(_ url: String, headers: [String: String] = [:], timeout: TimeInterval = 20) async throws -> (Data, [String: String]) {
        guard let link = URL(string: url) else { throw HttpError.network("地址格式异常") }
        var request = URLRequest(url: link)
        request.timeoutInterval = timeout
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw HttpError.network("响应异常") }
        guard http.statusCode < 400 else { throw HttpError.status(http.statusCode) }
        var cookies: [String: String] = [:]
        HTTPCookieStorage.shared.cookies(for: link)?.forEach { cookies[$0.name] = $0.value }
        return (data, cookies)
    }
}

// MARK: - JSON 取值

extension Dictionary where Key == String, Value == Any {
    func str(_ key: String) -> String {
        guard let value = self[key], !(value is NSNull) else { return "" }
        if let text = value as? String { return text.trimmingCharacters(in: .whitespacesAndNewlines) }
        if let number = value as? NSNumber { return number.stringValue }
        return ""
    }

    func int(_ key: String, _ fallback: Int = 0) -> Int {
        guard let value = self[key], !(value is NSNull) else { return fallback }
        if let number = value as? NSNumber { return number.intValue }
        if let text = value as? String { return Int(text) ?? fallback }
        return fallback
    }

    func int64(_ key: String, _ fallback: Int64 = 0) -> Int64 {
        guard let value = self[key], !(value is NSNull) else { return fallback }
        if let number = value as? NSNumber { return number.int64Value }
        if let text = value as? String { return Int64(text) ?? fallback }
        return fallback
    }

    func bool(_ key: String, _ fallback: Bool = false) -> Bool {
        guard let value = self[key], !(value is NSNull) else { return fallback }
        if let number = value as? NSNumber { return number.boolValue }
        if let text = value as? String { return text == "true" || text == "1" }
        return fallback
    }

    func obj(_ key: String) -> [String: Any]? { self[key] as? [String: Any] }

    func arr(_ key: String) -> [[String: Any]] { (self[key] as? [[String: Any]]) ?? [] }

    /// 有些字段是「JSON 的字符串副本」（`recommend_info` / `tag_info` / `video_model`）。
    func jsonObject(_ key: String) -> [String: Any]? {
        let raw = str(key)
        guard raw.hasPrefix("{"), let data = raw.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    func objectOrJSON(_ key: String) -> [String: Any]? {
        obj(key) ?? jsonObject(key)
    }
}

enum JSON {
    static func parse(_ text: String) throws -> [String: Any] {
        guard let data = text.data(using: .utf8),
              let value = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw HttpError.decode("响应不是合法 JSON")
        }
        return value
    }

    static func body(_ value: [String: Any]) -> [UInt8] {
        let data = (try? JSONSerialization.data(withJSONObject: value, options: [])) ?? Data()
        return [UInt8](data)
    }

    static func string(_ value: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: []),
              let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }
}
