// SPDX-License-Identifier: GPL-3.0-only
//
// 签名移植的离线验证器：拿 Android 侧 `core/src/test/resources/signing.json`
// 的 12 组固定向量逐条比对 hash / gorgon / helios / medusa。
//
//   swiftc -O ios/HGTV/Core/Sign/*.swift ios/tools/signer_oracle.swift -o /tmp/signer_oracle
//   /tmp/signer_oracle android/core/src/test/resources/signing.json
//
// 退出码 0 = 全部一致；非 0 = 有偏差（逐行打印第一处不同）。

import Foundation

@main
struct SignerOracle {
    static func main() {
        let path = CommandLine.arguments.count > 1
            ? CommandLine.arguments[1]
            : "android/core/src/test/resources/signing.json"
        guard let raw = FileManager.default.contents(atPath: path),
              let rows = (try? JSONSerialization.jsonObject(with: raw)) as? [[String: Any]] else {
            print("无法读取向量文件：\(path)")
            exit(2)
        }

        print("sbox bytes = \(Vendor.sbox.count)（应为 1024）")
        var failures = 0
        for (index, row) in rows.enumerated() {
            let ts = Int32(truncatingIfNeeded: (row["ts"] as? Int) ?? 0)
            let url = (row["url"] as? String) ?? ""
            let query = url.split(separator: "?", maxSplits: 1).dropFirst().first.map(String.init) ?? ""
            let bodyText = row["body"] as? String
            let body: [UInt8]? = bodyText.map { [UInt8]($0.utf8) }

            var seed: Int64 = 17
            let signer = Signer(clock: { Int64(ts) * 1000 }, random: {
                seed = (seed &* 1664525 &+ 1013904223) & 0xFFFFFFFF
                return Double(seed) / 4_294_967_296.0
            })

            var problems: [String] = []
            if signer.branch(query: query, body: body, ts: ts) != 0 {
                problems.append("branch=\(signer.branch(query: query, body: body, ts: ts))")
            }
            let hash = Bytes.hex(signer.hashF13(query: Hash.sm3(Bytes.utf8(query)),
                                               body: body.map { Hash.md5($0) } ?? [UInt8](repeating: 0, count: 16),
                                               ts: ts))
            if hash != (row["hash"] as? String) {
                problems.append("hash got=\(hash) want=\(row["hash"] ?? "")")
            }
            let gorgon = signer.gorgon(query: query, body: body, ts: ts, random: 42131)
            if gorgon != (row["gorgon"] as? String) {
                problems.append("gorgon got=\(gorgon) want=\(row["gorgon"] ?? "")")
            }
            let helios = signer.helios(ts: ts)
            if helios != (row["helios"] as? String) {
                problems.append("helios got=\(helios) want=\(row["helios"] ?? "")")
            }
            let medusa = signer.medusa(url: url, body: body, ts: ts)
            if medusa != (row["medusa"] as? String) {
                problems.append("medusa got=\(medusa.prefix(48))… want=\((row["medusa"] as? String ?? "").prefix(48))…")
            }

            if problems.isEmpty {
                print("✓ row \(index) ts=\(ts)")
            } else {
                failures += 1
                print("✗ row \(index) ts=\(ts)")
                problems.forEach { print("    \($0)") }
            }
        }
        print(failures == 0 ? "全部一致（\(rows.count) 组）" : "\(failures)/\(rows.count) 组不一致")
        exit(failures == 0 ? 0 : 1)
    }
}
