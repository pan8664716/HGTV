// SPDX-License-Identifier: GPL-3.0-only
//
// 内容接口：与 Android `core/ContentRepository.kt` 同一套路径、同一套参数、
// 同一套解析规则（字段名逐条对照）。
//
// 只保留 iOS 端用到的六条：分类 / 榜单（同一接口）、推荐流、详情、相关作品、
// 搜索（App + 官网两条源）、播放地址、弹幕。账号侧的收藏 / 历史同步不在本轮
// （见 docs/IOS_PORT.md「已知边界」），本机记录与 Android 完全同一套语义。

import Foundation

enum ContentError: LocalizedError {
    case device
    case message(String)

    var errorDescription: String? {
        switch self {
        case .device: return "设备未完成注册，内容暂不可用"
        case .message(let text): return text
        }
    }
}

struct StreamInfo {
    var url: String
    var backup: String
    var quality: Int
    /// `spade_a`：CENC 的内容密钥（空 = 明文流）。
    var key: String = ""
}

struct DanmakuItem: Identifiable, Hashable {
    let id = UUID()
    let offsetMs: Int64
    let text: String
}

struct RecommendFeed {
    var page: CatalogPage
    var sessionId: String
    var nextOffset: Int
}

actor ContentRepository {
    static let shared = ContentRepository()

    private let signer = Signer(device: Vendor.device)
    private var identity: DeviceIdentity?
    private var registering = false
    private var registered = false
    private struct SearchCursor {
        let searchId: String
        let passback: String
        let offset: Int
        let at: TimeInterval
    }
    private var searchCursors: [String: SearchCursor] = [:]

    private var categoryHost: String {
        let host = TncConfigStore.shared.preferredHost("sinfonlineb")
        return "https://\(host)"
    }
    private var appHost: String {
        let host = TncConfigStore.shared.preferredHost("sinfonlinea")
        return "https://\(host)"
    }
    private var siteHost: String {
        let host = TncConfigStore.shared.localHost("hongguoduanju.com",
                                                   fallback: "hongguoduanju.com")
        return "https://\(host)"
    }

    private let categoryPath = "/reading/distribution/category/landpage/v/"
    private let detailPath = "/novel/player/video_detail/v1/"
    private let searchPath = "/reading/bookapi/search/tab/v"
    private let planPath = "/reading/bookapi/plan/v:version/"
    private let modelPath = "/novel/player/multi_video_model/v1/"

    // MARK: - 设备

    func bootstrap() async {
        if identity == nil { identity = IdentityStore.load() }
        if let identity {
            // 身份从磁盘读回来时，签名器也要跟着换：`x-medusa` 里编了 device_id，
            // query 里是新身份、密文里还是内置那台设备，平台会回 110001。
            registered = true
            var profile = Vendor.device
            identity.profile.forEach { profile[$0.key] = $0.value }
            profile["device_id"] = identity.deviceId
            profile["iid"] = identity.installId
            profile["cdid"] = identity.cdid
            profile["openudid"] = identity.openudid
            signer.updateDevice(profile)
            refreshTncInBackground(profile)
            return
        }
        if await registerIfNeeded(), let identity {
            var profile = Vendor.device
            identity.profile.forEach { profile[$0.key] = $0.value }
            profile["device_id"] = identity.deviceId
            profile["iid"] = identity.installId
            profile["cdid"] = identity.cdid
            profile["openudid"] = identity.openudid
            refreshTncInBackground(profile)
        }
    }

    @discardableResult
    func registerIfNeeded() async -> Bool {
        if registered { return true }
        if registering { return false }
        registering = true
        defer { registering = false }

        let seed = IdentityStore.ensureSeed()
        var device = Vendor.device
        device["cdid"] = seed.cdid
        device["openudid"] = seed.openudid
        if let result = await DeviceRegister.register(device: device) {
            let profile = device.merging(result.cookies) { current, _ in current }
            let identity = DeviceIdentity(deviceId: result.deviceId, installId: result.installId,
                                          cdid: seed.cdid, openudid: seed.openudid,
                                          profile: profile, cookies: result.cookies)
            IdentityStore.save(identity)
            self.identity = identity
            registered = true
            var signing = profile
            signing["device_id"] = result.deviceId
            signing["iid"] = result.installId
            signer.updateDevice(signing)
            return true
        }
        return false
    }

    /// 内容请求的 query：设备档案 + 本机身份；版本号由各调用方按接口指定。
    private func values(versionCode: String, versionName: String) -> OrderedParams {
        var device = versionCode == Vendor.videoVersionCode ? Vendor.device : Vendor.appDevice
        if let identity {
            // **顺序不能反**：档案里带着注册前的内置 device_id，先铺档案再盖身份，
            // 否则 query 里是内置那台旧设备，而签名器用的是新身份 → 平台回 110001 / 空响应。
            identity.profile.forEach { device[$0.key] = $0.value }
            device["device_id"] = identity.deviceId
            device["iid"] = identity.installId
            device["cdid"] = identity.cdid
            device["openudid"] = identity.openudid
        }
        device["version_code"] = versionCode
        device["version_name"] = versionName
        device["manifest_version_code"] = versionCode
        device["update_version_code"] = versionCode
        device["pv_player"] = versionCode
        return OrderedParams(device.keys.sorted().map { ($0, device[$0] ?? "") })
    }

    private func appValues() -> OrderedParams { values(versionCode: "72232", versionName: "7.2.2.32") }
    /** 官方 7.4.3.32 综合搜索所用的版本、上下文参数。 */
    private func searchValues() -> OrderedParams {
        var values = OrderedParams(Vendor.device.keys.sorted().map { ($0, Vendor.device[$0] ?? "") })
        if let identity {
            identity.profile.forEach { values[$0.key] = $0.value }
            values["device_id"] = identity.deviceId
            values["iid"] = identity.installId
            values["cdid"] = identity.cdid
            values["openudid"] = identity.openudid
        }
        ["version_code": "74332", "version_name": "7.4.3.32",
         "manifest_version_code": "74332", "update_version_code": "74332",
         "pv_player": "74332"].forEach { values[$0.key] = $0.value }
        let context: [(String, String)] = [
            ("search_source", "1"), ("request_mode", "0"), ("is_first_enter_search", "true"),
            ("only_feed", "false"), ("from_rs", "false"), ("from_half_screen", "false"),
            ("use_lynx", "false"), ("bookstore_tab", "16"), ("tab_name", "feed"),
            ("last_search_page_interval", "0"), ("last_book_consume_time", "0"),
            ("last_consume_interval", "0"), ("app_launch_time", "0"),
            ("live_room_id", "0"), ("product_id", "0"), ("seed_product_id", "0"),
            ("line_words_num", "0"), ("pad_column_cover", "0"), ("only_large_card", "false"),
            ("biz_config_ctx_infos", ""), ("report_info", "")
        ]
        context.forEach { values[$0.0] = $0.1 }
        return values
    }
    private func searchRequest(_ params: OrderedParams) -> Signer.Request {
        signer.sign(base: appHost + searchPath, params: params, method: "GET",
                    deviceOverride: params.dictionary)
    }
    private func videoValues() -> OrderedParams { values(versionCode: "71332", versionName: "7.1.3.32") }

    private func cookieHeader() async -> String {
        let session = await MainActor.run { SessionStore.shared.cookieHeader }
        if !session.isEmpty { return session }
        // 没登录时带注册下发的 Cookie：搜索这类接口会按它判设备。
        guard let cookies = identity?.cookies, !cookies.isEmpty else { return "" }
        return cookies.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
    }

    private func send(_ request: Signer.Request) async throws -> String {
        var headers = request.headers
        let cookie = await cookieHeader()
        if !cookie.isEmpty { headers["Cookie"] = cookie }
        let text = try await Http.shared.send(Signer.Request(url: request.url, headers: headers,
                                                             body: request.body, method: request.method))
        if let host = URL(string: request.url)?.host {
            TncConfigStore.shared.markHostSuccess(host)
        }
        return text
    }

    private func refreshTncInBackground(_ profile: [String: String]) {
        Task.detached {
            await TncConfigStore.shared.refresh(device: profile)
        }
    }

    // MARK: - 分类 / 榜单 / 推荐

    /// 分类接口的一页。`needPanel` 为真时同一次请求把筛选面板一起带回来。
    func categoryFeed(_ selection: CategorySelection, page: Int, needPanel: Bool) async throws -> (page: CatalogPage, panel: CategoryPanel?) {
        // 「全部推荐」前两页走推荐序，第 3 页起接 hot_score（与 Android 同一条规则）。
        let recommendation = selection.sort == nil && selection.hasNoDetailFilters
        let fallback = selection.sort == nil &&
            (!recommendation || page >= AppConfig.Catalog.categoryFallbackStartPage)
        var body: [String: Any] = [
            "filter_ids": "",
            "req_scene": "default",
            "offset": offset(selection, page),
            "need_selector_panel": needPanel,
            "limit": page <= 1 ? AppConfig.Catalog.categoryFirstLimit : AppConfig.Catalog.categoryPageLimit,
            "select_items": selectItems(selection, sort: fallback ? "hot_score" : selection.sort),
            "session_id": "",
            "req_type": needPanel ? "default" : "only_content",
            "client_req_type": 3
        ]
        if page <= 1 && !needPanel { body["limit"] = AppConfig.Catalog.categoryFirstLimit }
        let text = try await postCategory(body)
        let root = try JSON.parse(text)
        guard root.int("code", -1) == 0 else { throw ContentError.message("分类接口暂不可用") }
        let payload = root.obj("data") ?? [:]
        let items = payload.arr("video_data").map(card).filter { !$0.id.isEmpty }
        let serverMore = payload.bool("has_more")
        let more = (
            serverMore ||
            (recommendation && page <= AppConfig.Catalog.categoryRecommendationPages)
            ) && page < AppConfig.Catalog.maxPage
        let panel = needPanel ? parsePanel(payload.arr("selector_rows")) : nil
        return (CatalogPage(items: items, hasMore: more, ranking: nil), panel)
    }

    func rank(page: Int, board: RankBoard) async throws -> CatalogPage {
        var selection = CategorySelection()
        selection.genre = board.genre
        selection.sort = board.sort
        let limit = AppConfig.Catalog.rankLimit
        let body: [String: Any] = [
            "filter_ids": "",
            "req_scene": "default",
            "offset": (page - 1) * limit,
            "need_selector_panel": false,
            "limit": limit,
            "select_items": selectItems(selection, sort: board.sort),
            "session_id": "",
            "req_type": "only_content",
            "client_req_type": 3
        ]
        let text = try await postCategory(body)
        let root = try JSON.parse(text)
        guard root.int("code", -1) == 0 else { throw ContentError.message("榜单接口暂不可用") }
        let payload = root.obj("data") ?? [:]
        let rows = payload.arr("video_data")
        var positions: [String: RankPosition] = [:]
        let base = (page - 1) * limit
        for (index, row) in rows.enumerated() {
            let series = card(row)
            guard !series.id.isEmpty else { continue }
            let info = row.objectOrJSON("recommend_info") ?? [:]
            let local = info.int("rank", 0)
            let heat = row.objectOrJSON("recommend_reason_list")?
                .arr("sub_title_list")
                .first { $0.int("data_type", -1) == 27 }?.str("content") ?? ""
            if local > 0 || !heat.isEmpty {
                positions[series.id] = RankPosition(rank: base + (local > 0 ? local : index + 1), heat: heat)
            }
        }
        let items = rows.map(card).filter { !$0.id.isEmpty }
        return CatalogPage(items: items, hasMore: payload.bool("has_more", items.count >= limit),
                           ranking: CatalogRanking(positions: positions))
    }

    func recommendFeed(sessionId: String, offset: Int, genres: [String], gender: String,
                       filterIds: String) async throws -> RecommendFeed {
        let limit = AppConfig.Catalog.recommendLimit
        var selection = CategorySelection()
        selection.gender = gender.isEmpty ? nil : gender
        let body: [String: Any] = [
            "filter_ids": filterIds,
            "req_scene": "default",
            "offset": offset,
            "need_selector_panel": false,
            "limit": limit,
            "select_items": selectItems(selection, sort: nil, genres: genres),
            "session_id": sessionId,
            "req_type": "only_content",
            "client_req_type": 3
        ]
        let text = try await postCategory(body)
        let root = try JSON.parse(text)
        guard root.int("code", -1) == 0 else { throw ContentError.message("推荐接口暂不可用") }
        let payload = root.obj("data") ?? [:]
        let items = payload.arr("video_data").map(card).filter { !$0.id.isEmpty }
        let reported = payload.int("next_offset", -1)
        let next = reported > offset ? reported : (items.isEmpty ? offset + limit : offset + items.count)
        let more = payload.bool("has_more", !items.isEmpty)
        return RecommendFeed(page: CatalogPage(items: items, hasMore: more, ranking: nil),
                             sessionId: payload.str("session_id").isEmpty ? sessionId : payload.str("session_id"),
                             nextOffset: next)
    }

    private func offset(_ selection: CategorySelection, _ page: Int) -> Int {
        if selection.sort == nil && selection.hasNoDetailFilters {
            if page <= 1 { return 0 }
            if page == 2 { return AppConfig.Catalog.categoryFirstLimit }
            return (page - AppConfig.Catalog.categoryFallbackStartPage) *
                AppConfig.Catalog.categoryOffsetStep
        }
        return page <= 1
            ? 0
            : AppConfig.Catalog.categoryFirstLimit +
                (page - 2) * AppConfig.Catalog.categoryOffsetStep
    }

    private func selectItems(_ selection: CategorySelection, sort: String?, genres: [String] = []) -> [String: Any] {
        let picked = genres.isEmpty ? [selection.genre].compactMap { $0 } : genres
        var items: [String: Any] = ["genre": picked]
        func put(_ type: String, _ value: String?) {
            if let value, !value.isEmpty { items[type] = [value] }
        }
        put(CategorySelection.typeTheme, selection.theme)
        put(CategorySelection.typeRole, selection.role)
        put(CategorySelection.typeEpoch, selection.epoch)
        put(CategorySelection.typeGender, selection.gender)
        put(CategorySelection.typeOnlineTime, selection.onlineTime)
        put(CategorySelection.typeCreationStatus, selection.creationStatus)
        put(CategorySelection.typeSort, sort)
        return items
    }

    private func postCategory(_ body: [String: Any]) async throws -> String {
        let payload = JSON.body(body)
        var lastError: Error = ContentError.message("分类接口暂无响应")
        for attempt in 0..<AppConfig.Catalog.categoryAttempts {
            if attempt > 0 {
                try? await Task.sleep(nanoseconds: AppConfig.Catalog.categoryRetryDelayNanoseconds)
            }
            do {
                let signed = signer.sign(base: categoryHost + categoryPath, params: appValues(),
                                         body: payload, method: "POST")
                return try await send(signed)
            } catch let error as HttpError {
                if case .status(let code) = error, code >= 400 && code < 500 && code != 429 { throw error }
                lastError = error
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    // MARK: - 详情

    func detail(id: String) async throws -> SeriesDetail {
        var params = videoValues()
        params["series_id"] = id
        let body = JSON.body([:])
        var request = signer.sign(base: categoryHost + detailPath, params: params, body: body)
        var text: String
        do {
            text = try await send(request)
        } catch HttpError.empty {
            // 空响应先按 GET 再问一次（与 Android 同一条经验：签名不覆盖方法）。
            request = signer.sign(base: categoryHost + detailPath, params: params, method: "GET")
            text = try await send(request)
        }
        let root = try JSON.parse(text)
        guard root.int("code", -1) == 0 else {
            throw ContentError.message("剧集详情暂不可用（\(root.int("code"))）")
        }
        guard let data = root.obj("data")?.obj("video_data") else {
            throw ContentError.message("剧集详情结构已变化")
        }
        let rows = data.arr("video_list").sorted { $0.int("vid_index") < $1.int("vid_index") }
        let episodes = rows.map { $0.str("vid") }.filter { !$0.isEmpty && $0.allSatisfy(\.isNumber) }
        let count = data.int("episode_cnt", episodes.count)
        if episodes.isEmpty && count > 0 { throw ContentError.message("分集数据异常") }
        let tags = tagsOf(data)
        let upcoming = episodes.isEmpty
        let notice = upcoming ? "尚未上线" : ""
        let series = Series(
            id: id,
            title: data.str("series_title"),
            cover: cleanUrl(data.str("series_cover")),
            description: data.str("series_intro"),
            badge: EpisodeLabel.normalize(data.str("episode_right_text")).isEmpty
                ? EpisodeLabel.of(count) : EpisodeLabel.normalize(data.str("episode_right_text")),
            tags: tags,
            author: data.str("author"),
            episodeCount: count,
            seasonIndex: data.int("season_index"),
            upcoming: upcoming,
            notice: notice
        )
        return SeriesDetail(series: series, episodes: episodes, upcoming: upcoming,
                            reserved: data.bool("online_subscribed"))
    }

    /// 相关作品：一次请求拿全（同系列各季在前，其余按接口顺序）。
    func related(id: String, title: String) async throws -> [Series] {
        var params = appValues()
        params["scene"] = "10"
        params["from"] = "detail_page_more_related"
        params["book_id"] = id
        let signed = signer.sign(base: categoryHost + planPath, params: params, method: "GET")
        let text = try await send(signed)
        let root = try JSON.parse(text)
        var items: [Series] = []
        for cell in root.arr("data") {
            let rows = cell.arr("video_data")
            for row in rows {
                let series = card(row)
                if !series.id.isEmpty && series.id != id { items.append(series) }
            }
        }
        // 同系列各季排前面：基名相同的优先，其次按季号升序。
        let base = SeriesSeasons.parse(title).base
        var seen = Set<String>()
        let unique = items.filter { seen.insert($0.id).inserted }
        let siblings = unique.filter { SeriesSeasons.parse($0.title).base == base && !base.isEmpty }
            .sorted { SeriesSeasons.parse($0.title).season < SeriesSeasons.parse($1.title).season }
        let others = unique.filter { !siblings.contains($0) }
        return siblings + others
    }

    // MARK: - 搜索

    func search(keyword: String, page: Int, source: SearchSource) async throws -> CatalogPage {
        let clean = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return CatalogPage(items: [], hasMore: false, ranking: nil) }
        if source == .web { return try await webSearch(keyword: clean, page: page) }
        return try await appSearch(keyword: clean, page: page)
    }

    private func appSearch(keyword: String, page: Int) async throws -> CatalogPage {
        let count = AppConfig.Catalog.appSearchPageSize
        var params = searchValues()
        params["query"] = keyword
        params["count"] = "\(count)"
        params["offset"] = "\((page - 1) * count)"
        params["bookshelf_search_plan"] = "4"
        params["use_correct"] = "true"
        let signedIn = await MainActor.run { SessionStore.shared.signedIn }
        params["user_is_login"] = signedIn ? "1" : "0"
        let now = Date().timeIntervalSince1970
        searchCursors = searchCursors.filter { now - $0.value.at < 300 }
        if page == 1 { searchCursors.removeValue(forKey: keyword) }
        if page > 1, let cursor = searchCursors[keyword] {
            if !cursor.searchId.isEmpty { params["search_id"] = cursor.searchId }
            if !cursor.passback.isEmpty { params["passback"] = cursor.passback }
            params["offset"] = "\(cursor.offset)"
        } else if page > 1 {
            throw ContentError.message("搜索结果已过期，请重新搜索")
        }
        let signed = searchRequest(params)
        let text = try await send(signed)
        let root = try JSON.parse(text)
        guard root.int("code", -1) == 0 else { throw ContentError.message("搜索接口暂不可用（\(root.int("code"))）") }
        let tabs = root.arr("search_tabs").isEmpty ? (root.obj("data")?.arr("search_tabs") ?? []) : root.arr("search_tabs")
        guard let tab = tabs.first(where: { !$0.arr("data").isEmpty }) ?? tabs.first else {
            throw ContentError.message("搜索接口没有返回结果")
        }
        let nextOffset = tab.int("next_offset", (page - 1) * count + tab.arr("data").count)
        searchCursors[keyword] = SearchCursor(searchId: tab.str("search_id"),
                                              passback: tab.str("passback"),
                                              offset: nextOffset, at: now)
        var items: [Series] = []
        for cell in tab.arr("data") {
            var rows = [cell]
            if let lynx = cell.str("lynx_data").data(using: .utf8),
               let json = (try? JSONSerialization.jsonObject(with: lynx)) as? [String: Any],
               let inner = json["cell_data"] as? [[String: Any]] {
                rows += inner
            }
            for row in rows {
                if let video = row.arr("video_data").first {
                    let id = video.str("series_id").isEmpty ? row.str("book_id") : video.str("series_id")
                    let title = row.obj("search_high_light")?.obj("title")?.str("text") ?? video.str("title")
                    let count = video.int("episode_cnt")
                    items.append(Series(
                        id: id,
                        title: title.isEmpty ? row.str("cell_name") : title,
                        cover: cleanUrl(video.str("cover").isEmpty ? video.str("cover_url") : video.str("cover")),
                        description: video.str("video_desc"),
                        badge: EpisodeLabel.of(count).isEmpty ? video.str("rec_text") : EpisodeLabel.of(count),
                        tags: tagsOf(video),
                        author: video.str("copyright"),
                        episodeCount: count,
                        heat: heatValue(video.str("rec_text")),
                        playCount: video.int("play_cnt")
                    ))
                    continue
                }
                if let sub = row.arr("subscribe_items").first {
                    let id = sub.str("item_id").isEmpty ? sub.str("item_id_str") : sub.str("item_id")
                    items.append(Series(
                        id: id, title: sub.str("name"), cover: cleanUrl(sub.str("cover")),
                        description: sub.str("item_desc"),
                        badge: sub.str("sub_info"), tags: tagsOf(sub),
                        upcoming: true,
                        notice: sub.str("sub_info").isEmpty ? sub.obj("tag_info")?.str("text") ?? "" : sub.str("sub_info")
                    ))
                }
            }
        }
        return CatalogPage(items: items.filter { !$0.id.isEmpty },
                           hasMore: tab.bool("has_more") && page < AppConfig.Catalog.maxPage, ranking: nil)
    }

    /// 官网搜索（不需要签名，字段与详情同源）。
    private func webSearch(keyword: String, page: Int) async throws -> CatalogPage {
        let encoded = Signer.encode(keyword)
        let url = "\(siteHost)/api/search/result?query=\(encoded)&page=\(page)"
        let (data, _) = try await Http.shared.get(url, headers: [
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
            "Accept": "application/json"
        ])
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw ContentError.message("官网搜索暂不可用")
        }
        if let code = root["code"] as? String {
            if code == "invalid_query" { return CatalogPage(items: [], hasMore: false, ranking: nil) }
            throw ContentError.message("官网搜索暂不可用（\(code)）")
        }
        let rows = root.arr("searchList")
        let items = rows.compactMap { row -> Series? in
            let video = row.obj("video_data") ?? row
            let id = video.str("series_id").isEmpty ? row.str("keyword") : video.str("series_id")
            guard !id.isEmpty else { return nil }
            let count = video.int("episode_cnt")
            return Series(id: id,
                          title: video.str("series_title").isEmpty ? row.str("name") : video.str("series_title"),
                          cover: cleanUrl(video.str("series_cover")),
                          description: video.str("series_intro"),
                          badge: EpisodeLabel.normalize(video.str("episode_right_text")).isEmpty
                              ? EpisodeLabel.of(count) : EpisodeLabel.normalize(video.str("episode_right_text")),
                          tags: tagsOf(video, key: "category_list"),
                          episodeCount: count)
        }
        let totalPages = max(
            1,
            min(root.int("totalPages", 1), AppConfig.Catalog.webSearchMaxPage)
        )
        return CatalogPage(items: items,
                           hasMore: (root.bool("hasMore", page < totalPages)) && page < totalPages,
                           ranking: nil)
    }

    func hotSearches() async throws -> [String] {
        var params = appValues()
        params["count"] = "\(AppConfig.Catalog.hotSearchRequestCount)"
        let signed = signer.sign(base: appHost + "/reading/bookapi/search/rank/v", params: params, method: "GET")
        let text = try await send(signed)
        let root = try JSON.parse(text)
        return root.arr("data").compactMap { row -> String? in
            let word = row.str("word").isEmpty ? row.str("query") : row.str("word")
            return word.isEmpty ? nil : word
        }
    }

    // MARK: - 播放

    /// 取一集的播放地址。
    ///
    /// 与 Android 同一条策略：播放接口按 `device_id` 计额度，**本机注册身份往往已经被打满**，
    /// 因此每次请求现造一个 15 位随机 id（`iid` 仍用注册下发的那一个），空响应就换号重试。
    /// 不换号的话，表现就是「所有片子都放不了」（`200 + 0 字节`）。
    func stream(id: String, maxQuality: Int) async throws -> StreamInfo {
        let biz: [String: Any] = [
            "detail_page_version": 0,
            "device_level": 4,
            "disable_digg_stat": false,
            "need_all_video_definition": true,
            "need_mp4_align": false,
            "use_os_player": false,
            "use_server_dns": false,
            "video_platform": 1024
        ]
        let body = JSON.body([
            "biz_param": biz,
            "mixed_video_id_map": ["1004": [id]]
        ])

        var model: [String: Any]?
        var lastError: Error = ContentError.message("播放接口暂无响应")
        for _ in 0..<AppConfig.Catalog.streamModelAttempts {
            let modelId = String(Int64.random(in: 100_000_000_000_000...999_999_999_999_999))
            var params = videoValues()
            params["device_id"] = modelId
            var override = Vendor.device
            identity?.profile.forEach { override[$0.key] = $0.value }
            override["device_id"] = modelId
            if let identity {
                override["iid"] = identity.installId
                override["cdid"] = identity.cdid
                override["openudid"] = identity.openudid
            }
            let signed = signer.sign(base: categoryHost + modelPath, params: params,
                                     body: body, method: "POST", deviceOverride: override)
            do {
                let text = try await send(signed)
                let root = try JSON.parse(text)
                let code = root.int("Code", root.int("code", 0))
                if code != 0 {
                    lastError = ContentError.message("播放接口暂不可用（\(code)）")
                    if code == 101000 { continue }
                    throw lastError
                }
                guard let data = root.obj("data") else {
                    lastError = ContentError.message("播放接口没有返回模型")
                    continue
                }
                model = extractModel(data)
                if model != nil { break }
                lastError = ContentError.message("播放接口没有返回模型")
            } catch HttpError.empty {
                // 200 + 0 字节 = 这个 device_id 的额度用完了：换号再试（与 Android 同一条处置）。
                lastError = ContentError.message("播放接口暂无响应")
                try? await Task.sleep(nanoseconds: AppConfig.Catalog.streamRetryDelayNanoseconds)
            }
        }
        guard let model else { throw lastError }

        var fallback = model.str("fallback_api")
        if fallback.isEmpty, let obj = model.obj("fallback_api") { fallback = obj.str("fallback_api") }
        guard fallback.hasPrefix("https://") else { throw ContentError.message("播放地址格式异常") }

        let (data2, _) = try await Http.shared.get(fallback, headers: [
            "User-Agent": Vendor.userAgent(Vendor.device, video: true)
        ])
        guard let info = (try? JSONSerialization.jsonObject(with: data2)) as? [String: Any],
              let list = info.obj("video_info")?.obj("data")?.obj("video_list") else {
            throw ContentError.message("没有播放资源")
        }

        let seed = info.obj("video_info")?.obj("data")?.str("key_seed") ?? ""
        func resolved(_ raw: String) -> String {
            let cleaned = cleanUrl(raw)
            guard !seed.isEmpty, !cleaned.hasPrefix("http") else { return cleaned }
            return MediaCrypto.decryptUrl(cleaned, seed: Bytes.base64(seed))
        }

        var best: (quality: Int, url: String, backup: String, key: String)?
        for key in list.keys {
            guard let row = list.obj(key) else { continue }
            let quality = qualityOf(row, key: key)
            let url = resolved(row.str("main_url").isEmpty ? row.str("url") : row.str("main_url"))
            guard url.hasPrefix("http") else { continue }
            let backup = resolved(row.str("backup_url"))
            if quality > 0 && quality <= maxQuality {
                if best == nil || quality > best!.quality {
                    best = (quality, url, backup, row.str("spade_a"))
                }
            }
        }
        if best == nil {
            // 上限之内一档都没有：退回最低的一档（兼容资源，与 Android 同一条兜底）。
            for key in list.keys {
                guard let row = list.obj(key) else { continue }
                let url = resolved(row.str("main_url").isEmpty ? row.str("url") : row.str("main_url"))
                guard url.hasPrefix("http") else { continue }
                let quality = qualityOf(row, key: key)
                if best == nil || quality < best!.quality {
                    best = (quality, url, resolved(row.str("backup_url")), row.str("spade_a"))
                }
            }
        }
        guard let best else { throw ContentError.message("没有可用清晰度") }
        return StreamInfo(url: best.url, backup: best.backup, quality: best.quality, key: best.key)
    }

    /// `video_model` 可能在 `data.video_model`，也可能在 `data[<vid>].video_model`；
    /// 值可能是对象，也可能是**字符串化的 JSON**（与 Android 的 `videoModelOf` 一致）。
    private func extractModel(_ data: [String: Any]) -> [String: Any]? {
        if let direct = data.obj("video_model") { return direct }
        if let parsed = parseModelString(data.str("video_model")) { return parsed }
        for key in data.keys {
            guard let entry = data.obj(key) else { continue }
            if let inner = entry.obj("video_model") { return inner }
            if let parsed = parseModelString(entry.str("video_model")) { return parsed }
        }
        return nil
    }

    private func parseModelString(_ raw: String) -> [String: Any]? {
        guard raw.hasPrefix("{"), let data = raw.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private func qualityOf(_ row: [String: Any], key: String) -> Int {
        // 档位按「短边」判：接口给的 `vwidth` / `vheight` 是真实像素，
        // `quality_desc` 有时是 `1080p`、有时是 `normal`（这一档只表示「普通」）。
        let short = min(row.int("vwidth"), row.int("vheight"))
        if short > 0 {
            for token in [2160, 1440, 1080, 720, 576, 540, 480, 360] where short >= token - 24 {
                return token
            }
        }
        for token in ["2160", "1440", "1080", "720", "576", "540", "480", "360"] {
            if row.str("definition").contains(token) || row.str("quality_desc").contains(token) {
                return Int(token) ?? 0
            }
        }
        if let value = Int(key.replacingOccurrences(of: "video_", with: "")) { return value }
        let width = row.int("width")
        switch width {
        case 1920...: return 1080
        case 1280..<1920: return 720
        case 1024..<1280: return 576
        case 854..<1024: return 480
        default: return 0
        }
    }

    // MARK: - 弹幕

    func danmaku(vid: String, seriesId: String) async throws -> [DanmakuItem] {
        let body = JSON.body([
            "comment_source": 1,
            "server_channel": 1,
            "group_id": vid,
            "group_type": 30,
            "comment_type": 20,
            "sort": 1,
            "business_param": ["book_id": seriesId, "start_offset_time": 0, "playlet_item_duration": 0],
            "count": 50,
            "cursor": "",
            "aid": 8662,
            "compliance_status": 0
        ])
        var headers = ["comment-source": "1", "server-channel": "1", "x-ss-stub": ""]
        let signed = signer.sign(base: appHost + "/novel/commentapi/comment/list/\(vid)/v1/",
                                 params: appValues(), body: body)
        headers.merge(signed.headers) { current, _ in current }
        let text = try await Http.shared.send(Signer.Request(url: signed.url, headers: headers,
                                                             body: body, method: "POST"))
        let root = try JSON.parse(text)
        guard root.int("code", -1) == 0 else { throw ContentError.message("弹幕接口暂不可用") }
        let rows = root.obj("data")?.arr("data_list") ?? []
        return rows.compactMap { row -> DanmakuItem? in
            let text = row.str("content").isEmpty
                ? (row.obj("comment")?.str("content") ?? row.str("text"))
                : row.str("content")
            guard !text.isEmpty else { return nil }
            let offset = row.int64("offset_time", row.int64("start_time", row.int64("offset")))
            return DanmakuItem(offsetMs: offset, text: text)
        }
    }

    // MARK: - 解析工具

    private func cleanUrl(_ value: String) -> String {
        value.replacingOccurrences(of: "\\/", with: "/")
            .replacingOccurrences(of: "\\u002F", with: "/")
            .replacingOccurrences(of: "\\u0026", with: "&")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    /// 分类 / 榜单 / 推荐共用的卡片。
    private func card(_ row: [String: Any]) -> Series {
        let count = row.int("episode_cnt")
        let tag = row.objectOrJSON("tag_info")
            ?? row.objectOrJSON("recommend_reason_list")?.objectOrJSON("tag_info")
        let bgColors = (tag?["bg_color"] as? [Any])?.compactMap { $0 as? String } ?? []
        return Series(
            id: row.str("series_id"),
            title: row.str("title"),
            cover: cleanUrl(row.str("cover")),
            description: row.str("video_desc"),
            badge: EpisodeLabel.of(count),
            tags: tagsOf(row),
            author: row.str("copyright"),
            episodeCount: count,
            seasonIndex: seasonOf(row),
            vertical: row.bool("vertical"),
            heat: heatOf(row),
            label: tag?.str("text") ?? "",
            labelBg: bgColors.joined(separator: ",")
        )
    }

    /// 题材：`category_schema` 里最多 5 个，` · ` 连接。
    private func tagsOf(_ row: [String: Any], key: String = "category_schema") -> String {
        var names: [String] = []
        for item in row.arr(key) {
            let name = item.str("name").isEmpty ? item.str("show_name") : item.str("name")
            if !name.isEmpty && !names.contains(name) { names.append(name) }
        }
        return names.prefix(AppConfig.Catalog.labelItemLimit).joined(separator: " · ")
    }

    /// 季号：`sub_title_list` 里 `data_type == 0` 的那条「第N季」，或 `season_index`。
    private func seasonOf(_ row: [String: Any]) -> Int {
        if row.int("season_index") > 0 { return row.int("season_index") }
        for item in row.arr("sub_title_list") where item.int("data_type", -1) == 0 {
            let text = item.str("content")
            let number = text.replacingOccurrences(of: "第", with: "")
                .replacingOccurrences(of: "季", with: "")
            if let value = SeriesSeasons.chineseNumber(number), value > 0 { return value }
        }
        return 0
    }

    private func heatOf(_ row: [String: Any]) -> Int64 {
        for item in row.arr("sub_title_list") where item.int("data_type", -1) == 27 {
            let value = heatValue(item.str("content"))
            if value > 0 { return value }
        }
        return heatValue(row.str("rec_text"))
    }

    /// 「9014万」/「1204万热度」/「1.2亿」→ 数字。
    func heatValue(_ raw: String) -> Int64 {
        let text = raw.replacingOccurrences(of: "热度", with: "").trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return 0 }
        let pattern = "([0-9]+(?:\\.[0-9]+)?)\\s*(亿|万)?"
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let numberRange = Range(match.range(at: 1), in: text),
              let value = Double(text[numberRange]) else { return 0 }
        var unit: Double = 1
        if match.range(at: 2).location != NSNotFound, let unitRange = Range(match.range(at: 2), in: text) {
            unit = text[unitRange] == "亿" ? 100_000_000 : 10_000
        }
        return Int64(value * unit)
    }

    private func parsePanel(_ rows: [[String: Any]]) -> CategoryPanel? {
        let parsed = rows.compactMap { row -> CategoryFilterRow? in
            let type = row.str("type")
            let options = row.arr("items").compactMap { item -> CategoryFilterOption? in
                let id = item.str("selector_item_id")
                let label = item.str("show_name")
                guard !id.isEmpty, !label.isEmpty else { return nil }
                return CategoryFilterOption(id: id, label: label)
            }
            guard !type.isEmpty, !options.isEmpty else { return nil }
            return CategoryFilterRow(type: type, title: row.str("row_name"), options: options)
        }
        return parsed.isEmpty ? nil : CategoryPanel(rows: parsed)
    }
}
