// SPDX-License-Identifier: GPL-3.0-only
//
// 接口链路的冒烟测试（本机命令行跑，不进 App）：
// 设备注册 → 榜单 → 分类（含筛选面板）→ 详情 → 播放地址 → 搜索。
//
//   swiftc -O ios/HGTV/Core/Sign/*.swift ios/HGTV/Core/API/*.swift \
//          ios/HGTV/Core/Models.swift ios/HGTV/Core/Rules.swift \
//          ios/HGTV/Data/Session.swift ios/tools/api_smoke.swift -o /tmp/api_smoke
//   /tmp/api_smoke

import Foundation

@main
struct Smoke {
    static func main() async {
        let repo = ContentRepository.shared
        print("① 设备注册…")
        await repo.bootstrap()
        if let identity = IdentityStore.load() {
            print("   设备 \(identity.deviceId.suffix(8)) / 安装 \(identity.installId.suffix(6)) / Cookie \(identity.cookies.keys.sorted())")
        } else {
            print("   ⚠️ 注册失败（继续试内容接口，可能回空）")
        }

        do {
            let page = try await repo.rank(page: 1, board: .comic)
            print("② 榜单：\(page.items.count) 条，首条 = \(page.items.first?.title ?? "-")，名次 = \(page.ranking?.positions.count ?? 0) 个")
        } catch {
            print("② 榜单失败：\(error.localizedDescription)")
        }

        var firstId = ""
        var firstVid = ""
        do {
            let result = try await repo.categoryFeed(CategorySelection(), page: 1, needPanel: true)
            print("③ 分类：\(result.page.items.count) 条，筛选行 = \(result.panel?.rows.map(\.type).joined(separator: ",") ?? "无")")
            if let first = result.page.items.first {
                firstId = first.id
                print("   首条 \(first.title) / \(first.episodeCount) 集 / 热度 \(first.heat)")
            }
        } catch {
            print("③ 分类失败：\(error.localizedDescription)")
        }

        if !firstId.isEmpty {
            try? await Task.sleep(nanoseconds: 400_000_000)
            do {
                let detail = try await repo.detail(id: firstId)
                firstVid = detail.episodes.first ?? ""
                print("④ 详情：\(detail.episodes.count) 集，第 1 集 vid = \(firstVid)")
            } catch {
                print("④ 详情失败：\(error.localizedDescription)")
            }
        }

        if !firstVid.isEmpty {
            try? await Task.sleep(nanoseconds: 400_000_000)
            do {
                let info = try await repo.stream(id: firstVid, maxQuality: 720)
                print("⑤ 播放地址：\(info.quality)P \(info.url)")
            } catch {
                print("⑤ 播放失败：\(error.localizedDescription)")
            }
        }

        do {
            let result = try await repo.search(keyword: "修仙", page: 1, source: .app)
            print("⑥ 搜索：\(result.items.count) 条，首条 = \(result.items.first?.title ?? "-")")
        } catch {
            print("⑥ 搜索失败：\(error.localizedDescription)")
        }
    }
}
