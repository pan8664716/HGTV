// SPDX-License-Identifier: GPL-3.0-only
//
// TNC (TTNet config) subset shared by content, login and device registration.
// The on-disk snapshot is loaded synchronously; refresh runs in the background and
// never blocks a user request. Static bootstrap and fallback hosts are only used
// when TNC has no matching role.

import Foundation

struct TncSnapshot: Codable {
    var fetchedAt: TimeInterval
    var summary: String
    var etag: String
    var updateInterval: TimeInterval
    var tncHosts: [String]
    var localHosts: [String]
    var apiHosts: [String]
    var preconnectHosts: [String]
    var httpDns: [String: [String]]
    var lastGoodHost: String
}

final class TncConfigStore: @unchecked Sendable {
    static let shared = TncConfigStore()

    static let bootstrapHosts = [
        "tnc3-aliec2.zijieapi.com",
        "tnc3-alisc1.zijieapi.com",
        "tnc3-bjlgy.zijieapi.com",
        "tnc11-aliec2.zijieapi.com",
        "tnc3-aliec2.bytedance.com"
    ]

    private static let fallbackApiHosts = [
        "api5-normal-sinfonlinea.fqnovel.com",
        "api5-normal-sinfonlineb.fqnovel.com",
        "api5-normal-sinfonlinec.fqnovel.com",
        "api5-normal-lf.fqnovel.com",
        "api5-normal-lq.fqnovel.com",
        "api5-normal-hl.fqnovel.com"
    ]

    private static let fallbackLogHosts = [
        "log.snssdk.com",
        "log5-applog-lf.fqnovel.com",
        "log5-applog-lq.fqnovel.com",
        "log5-applog-hl.fqnovel.com",
        "is.snssdk.com"
    ]

    private let lock = NSLock()
    private let storeURL: URL?
    private var snapshot: TncSnapshot?
    private var refreshing = false

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first
        storeURL = base?.appendingPathComponent("tnc-v1.json")
        snapshot = loadSnapshot()
    }

    func current() -> TncSnapshot? {
        lock.lock()
        defer { lock.unlock() }
        return snapshot
    }

    func apiHosts() -> [String] {
        let configured = current()?.apiHosts ?? []
        return configured.isEmpty ? Self.fallbackApiHosts : configured
    }

    func orderedApiHosts() -> [String] {
        let hosts = apiHosts()
        guard let last = current()?.lastGoodHost, !last.isEmpty, hosts.contains(last) else {
            return hosts
        }
        return [last] + hosts.filter { $0 != last }
    }

    func preferredHost(_ roleHint: String? = nil, preferLastGood: Bool = true) -> String {
        let hosts = orderedApiHosts()
        let last = current()?.lastGoodHost ?? ""
        if preferLastGood, !last.isEmpty, hosts.contains(last) { return last }
        if let roleHint, !roleHint.isEmpty,
           let match = hosts.first(where: { $0.localizedCaseInsensitiveContains(roleHint) }) {
            return match
        }
        return hosts.first ?? Self.fallbackApiHosts[0]
    }

    /// Select a TNC local host by role. Never substitute an unrelated local host.
    func localHost(_ roleHint: String, fallback: String) -> String {
        let hosts = current()?.localHosts ?? []
        if let exact = hosts.first(where: { $0.caseInsensitiveCompare(roleHint) == .orderedSame }) {
            return exact
        }
        return hosts.first(where: { $0.localizedCaseInsensitiveContains(roleHint) }) ?? fallback
    }

    func logHosts() -> [String] {
        var result: [String] = []
        for host in current()?.localHosts ?? [] where host.lowercased().hasPrefix("log5-applog-") {
            appendUnique(host, to: &result)
        }
        for host in Self.fallbackLogHosts { appendUnique(host, to: &result) }
        return result
    }

    func tncHosts() -> [String] {
        let configured = current()?.tncHosts ?? []
        return configured.isEmpty ? Self.bootstrapHosts : configured
    }

    func markHostSuccess(_ host: String) {
        let value = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        lock.lock()
        guard var latest = snapshot, latest.lastGoodHost != value else {
            lock.unlock()
            return
        }
        latest.lastGoodHost = value
        snapshot = latest
        lock.unlock()
        persist(latest)
    }

    /// Refresh TNC unless a fresh snapshot already exists. Failures keep the previous snapshot.
    @discardableResult
    func refresh(device: [String: String], force: Bool = false) async -> Bool {
        let now = Date().timeIntervalSince1970
        if !force, let latest = current(), now - latest.fetchedAt < latest.updateInterval {
            return true
        }

        lock.lock()
        if refreshing {
            lock.unlock()
            return false
        }
        refreshing = true
        lock.unlock()
        defer {
            lock.lock()
            refreshing = false
            lock.unlock()
        }

        var values = device
        for key in ["device_id", "iid", "install_id", "klink_egdi", "cdid"] {
            values.removeValue(forKey: key)
        }
        values["abi"] = device["host_abi"].flatMap { $0.isEmpty ? nil : $0 } ?? "arm64-v8a"
        values["tnc_src"] = "1"
        values["okhttp_version"] = "4.2.243.17-hongguo"
        values["use_store_region_cookie"] = "1"
        for key in ["version_code", "manifest_version_code", "update_version_code", "pv_player"] {
            values[key] = "71332"
        }
        values["version_name"] = "7.1.3.32"

        for host in tncHosts() {
            var components = URLComponents()
            components.scheme = "https"
            components.host = host
            components.path = "/get_domains/v5/"
            components.queryItems = values.sorted { $0.key < $1.key }
                .map { URLQueryItem(name: $0.key, value: $0.value) }
            guard let url = components.url else { continue }

            var request = URLRequest(url: url)
            request.timeoutInterval = 8
            request.setValue(Vendor.userAgent(values, video: true), forHTTPHeaderField: "User-Agent")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.setValue("8662", forHTTPHeaderField: "X-SS-DP")
            request.setValue("101", forHTTPHeaderField: "lc")
            request.setValue("2", forHTTPHeaderField: "sdk-version")
            request.setValue("5051451", forHTTPHeaderField: "passport-sdk-version")
            request.setValue("4.0.3.cn", forHTTPHeaderField: "x-vc-bdturing-sdk-version")
            request.setValue("1", forHTTPHeaderField: "X-HGTV-Anonymous")
            let summary = current()?.summary ?? ""
            if !summary.isEmpty {
                request.setValue(summary, forHTTPHeaderField: "x-tt-tnc-summary")
            }

            guard let (data, response) = try? await URLSession.shared.data(for: request),
                  let http = response as? HTTPURLResponse else { continue }
            let control = http.value(forHTTPHeaderField: "x-tt-tnc-control") ?? ""
            if http.statusCode == 304 || control == "1" {
                return applyNotModified(etag: http.value(forHTTPHeaderField: "x-ss-etag") ?? "")
            }
            guard (200..<300).contains(http.statusCode),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  (root["message"] as? String) == "success",
                  let payload = root["data"] as? [String: Any] else { continue }
            guard let next = parse(root: root, payload: payload,
                                   etag: http.value(forHTTPHeaderField: "x-ss-etag") ?? "") else {
                continue
            }
            apply(next)
            return true
        }
        return false
    }

    private func parse(root: [String: Any], payload: [String: Any], etag: String) -> TncSnapshot? {
        let config = payload["tnc_config"] as? [String: Any]
        let localHosts = stringList(config?["local_host_filter"])
        let httpDns = parseHttpDns(payload["ttnet_http_dns_addr"])
        var apiHosts: [String] = []
        for host in localHosts + Array(httpDns.keys) where isApiHost(host) {
            appendUnique(host, to: &apiHosts)
        }
        guard !apiHosts.isEmpty else { return nil }

        let tncHosts = stringList(payload["send_tnc_host_arrays"])
            .filter { $0.lowercased().hasSuffix(".zijieapi.com") }
            .reduce(into: [String]()) { appendUnique($1, to: &$0) }
        let seconds = number(payload["tnc_update_interval"]) ?? 3600
        let interval = min(max(seconds, 300), 86_400)
        let previousLastGood = current()?.lastGoodHost ?? ""
        return TncSnapshot(
            fetchedAt: Date().timeIntervalSince1970,
            summary: (root["summary"] as? String) ?? "",
            etag: etag,
            updateInterval: interval,
            tncHosts: tncHosts,
            localHosts: localHosts,
            apiHosts: apiHosts,
            preconnectHosts: parsePreconnectHosts(payload["ttnet_preconnect_urls"]),
            httpDns: httpDns,
            lastGoodHost: previousLastGood
        )
    }

    private func parseHttpDns(_ raw: Any?) -> [String: [String]] {
        guard let rows = raw as? [String: Any] else { return [:] }
        var result: [String: [String]] = [:]
        for (host, value) in rows {
            let addresses = stringList(value)
                .flatMap { $0.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) } }
                .filter { !$0.isEmpty }
                .reduce(into: [String]()) { appendUnique($1, to: &$0) }
            if !addresses.isEmpty { result[host] = addresses }
        }
        return result
    }

    private func parsePreconnectHosts(_ raw: Any?) -> [String] {
        guard let rows = raw as? [String: Any] else { return [] }
        return rows.keys.compactMap { URL(string: $0)?.host }
            .reduce(into: [String]()) { appendUnique($1, to: &$0) }
    }

    private func applyNotModified(etag: String) -> Bool {
        lock.lock()
        guard var latest = snapshot else {
            lock.unlock()
            return false
        }
        latest.fetchedAt = Date().timeIntervalSince1970
        if !etag.isEmpty { latest.etag = etag }
        snapshot = latest
        lock.unlock()
        persist(latest)
        return true
    }

    private func apply(_ next: TncSnapshot) {
        lock.lock()
        snapshot = next
        lock.unlock()
        persist(next)
    }

    private func loadSnapshot() -> TncSnapshot? {
        guard let storeURL,
              let data = try? Data(contentsOf: storeURL),
              let value = try? JSONDecoder().decode(TncSnapshot.self, from: data),
              !value.apiHosts.isEmpty else { return nil }
        return value
    }

    private func persist(_ value: TncSnapshot) {
        guard let storeURL,
              let data = try? JSONEncoder().encode(value) else { return }
        try? FileManager.default.createDirectory(at: storeURL.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        try? data.write(to: storeURL, options: .atomic)
    }

    private func stringList(_ value: Any?) -> [String] {
        if let list = value as? [Any] {
            return list.compactMap { $0 as? String }
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
        }
        if let text = value as? String {
            let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return clean.isEmpty ? [] : [clean]
        }
        return []
    }

    private func number(_ value: Any?) -> TimeInterval? {
        if let value = value as? NSNumber { return value.doubleValue }
        if let value = value as? String { return TimeInterval(value) }
        return nil
    }

    private func isApiHost(_ host: String) -> Bool {
        let value = host.lowercased()
        return value.hasSuffix(".fqnovel.com") &&
            (value.hasPrefix("api5-normal-") ||
             value.hasPrefix("api3-normal-") ||
             value.hasPrefix("api5-sinfnormal"))
    }

    private func appendUnique(_ value: String, to list: inout [String]) {
        guard !value.isEmpty, !list.contains(value) else { return }
        list.append(value)
    }
}
