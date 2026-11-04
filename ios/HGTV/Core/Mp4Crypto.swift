// SPDX-License-Identifier: GPL-3.0-only
//
// 播放内容的 CENC 解密（对应 Android `core/MediaCrypto.kt`）。
//
// 平台的视频全部是 **CENC AES-CTR** 加密的 MP4：容器里是 `encv`，每个样本带 16 字节 IV，
// 明文密钥在接口的 `spade_a` 字段里（再经 `deriveKey` 变换）。
// Android 用「自绘数据源 + 边读边解」；iOS 走 AVFoundation，它不接受 `encv`，
// 因此这里做离线处理：整集取回 → 改包（encv 换回原编码、sinf 变 free）→ 逐样本解密 → 落临时文件。
//
// 短剧单集只有几十 MB，一次性处理换来的是「AVPlayer 能直接播」，比自建
// AVAssetResourceLoader 的数据源简单一个量级，也没有首帧路径上的解密抖动。

import Foundation

struct Mp4Sample {
    var start: Int
    var end: Int
    var iv: [UInt8]
    var encryptedOffset: Int
}

struct Mp4Prepared {
    var header: [UInt8]
    var samples: [Mp4Sample]
}

enum Mp4Crypto {
    // MARK: 密钥

    /// `spade_a` → 16 字节 AES key（与 `MediaCrypto.deriveKey` 同一条变换）。
    static func deriveKey(_ value: String) -> [UInt8]? {
        var raw = Bytes.base64(value)
        guard raw.count >= 3 else { return nil }
        let length = min(raw.count - (Int(raw[0]) ^ Int(raw[1]) ^ Int(raw[2])) + 47, raw.count - 1)
        guard length >= 33 else { return nil }
        var work = Array(raw[1..<(1 + length)])
        var a = 85
        var b = 246
        for i in work.indices {
            let old = Int(work[i])
            let previous = (i & 1) != 0 ? a : b
            if (i & 1) != 0 { a = old } else { b = old }
            work[i] = UInt8(truncatingIfNeeded: -21 - i.nonzeroBitCount + (previous ^ old))
        }
        let hexText = String(decoding: work[1..<33], as: UTF8.self)
        raw = Bytes.hex(hexText)
        return raw.count == 16 ? raw : nil
    }

    // MARK: 容器解析

    private struct Box {
        var start: Int
        var end: Int
        var body: Int
        var type: String
    }

    private static func u32(_ data: [UInt8], _ at: Int) -> Int {
        Int(data[at]) << 24 | Int(data[at + 1]) << 16 | Int(data[at + 2]) << 8 | Int(data[at + 3])
    }

    private static func i32(_ data: [UInt8], _ at: Int) -> Int32 {
        Int32(bitPattern: UInt32(u32(data, at)))
    }

    private static func i64(_ data: [UInt8], _ at: Int) -> Int64 {
        var value: Int64 = 0
        for i in 0..<8 { value = (value << 8) | Int64(data[at + i]) }
        return value
    }

    private static func box(_ data: [UInt8], _ at: Int, _ limit: Int? = nil) -> Box? {
        let bound = limit ?? data.count
        guard at >= 0, at + 8 <= bound else { return nil }
        var size = u32(data, at)
        var header = 8
        if size == 1 {
            guard at + 16 <= bound else { return nil }
            size = Int(i64(data, at + 8))
            header = 16
        }
        if size == 0 { size = bound - at }
        guard size >= header, size <= bound - at else { return nil }
        return Box(start: at, end: at + size, body: at + header,
                   type: String(decoding: data[(at + 4)..<(at + 8)], as: UTF8.self))
    }

    private static func find(_ data: [UInt8], _ type: String, _ start: Int, _ limit: Int) -> Box? {
        guard limit - 8 > start else { return nil }
        let first = Array(type.utf8)[0]
        for at in start...(limit - 8) where data[at + 4] == first {
            if let candidate = box(data, at, limit), candidate.type == type { return candidate }
        }
        return nil
    }

    // MARK: 样本表

    static func prepare(_ data: [UInt8], key: [UInt8]?) -> Mp4Prepared {
        guard key != nil, data.count > 16 else { return Mp4Prepared(header: data, samples: []) }
        guard let moov = box(data, u32(data, 0)), moov.type == "moov" else {
            return Mp4Prepared(header: data, samples: [])
        }
        var samples: [Mp4Sample] = []
        var header = data
        var cursor = moov.body

        while cursor < moov.end {
            guard let track = box(data, cursor, moov.end) else { break }
            cursor = track.end
            guard track.type == "trak",
                  let stbl = find(data, "stbl", track.body, track.end),
                  let saiz = find(data, "saiz", stbl.body, stbl.end),
                  let saio = find(data, "saio", stbl.body, stbl.end),
                  let stsz = find(data, "stsz", stbl.body, stbl.end),
                  let stsc = find(data, "stsc", stbl.body, stbl.end),
                  let chunks = find(data, "stco", stbl.body, stbl.end) ?? find(data, "co64", stbl.body, stbl.end)
            else { continue }

            func count(_ at: Int, _ limit: Int) -> Int {
                guard at + 4 <= limit else { return 0 }
                let value = u32(data, at)
                return value > 200_000 ? 0 : value
            }

            let sampleCount = count(stsz.body + 8, stsz.end)
            let defaultSize = u32(data, stsz.body + 4)
            guard sampleCount > 0 else { continue }
            var sizes = [Int](repeating: 0, count: sampleCount)
            for i in 0..<sampleCount {
                sizes[i] = defaultSize > 0 ? defaultSize : u32(data, stsz.body + 12 + i * 4)
            }

            let chunkCount = count(chunks.body + 4, chunks.end)
            let width = chunks.type == "co64" ? 8 : 4
            var offsets = [Int](repeating: 0, count: chunkCount)
            for i in 0..<chunkCount {
                let at = chunks.body + 8 + i * width
                offsets[i] = width == 8 ? Int(i64(data, at)) : u32(data, at)
            }

            let entryCount = count(stsc.body + 4, stsc.end)
            guard entryCount > 0 else { continue }
            var entries: [(Int, Int)] = []
            for i in 0..<entryCount {
                entries.append((u32(data, stsc.body + 8 + i * 12), u32(data, stsc.body + 12 + i * 12)))
            }

            let sizeBase = saiz.body + 4 + ((i32(data, saiz.body) & 1) != 0 ? 8 : 0)
            guard sizeBase + 5 <= saiz.end else { continue }
            let auxDefault = Int(data[sizeBase])
            let auxCount = count(sizeBase + 1, saiz.end)
            guard auxCount == sampleCount else { continue }
            var auxSizes = [Int](repeating: 0, count: sampleCount)
            for i in 0..<sampleCount {
                auxSizes[i] = auxDefault > 0 ? auxDefault : Int(data[sizeBase + 5 + i])
            }

            let offsetBase = saio.body + 4 + ((i32(data, saio.body) & 1) != 0 ? 8 : 0)
            let offsetWidth = Int(data[saio.body]) == 1 ? 8 : 4
            guard count(offsetBase, saio.end) == 1, offsetBase + 4 + offsetWidth <= saio.end else { continue }
            var aux = offsetWidth == 8 ? Int(i64(data, offsetBase + 4)) : u32(data, offsetBase + 4)
            let auxEnd = aux + auxSizes.reduce(0, +)
            guard aux >= 0, auxEnd <= data.count else { continue }

            var sampleIndex = 0
            var entry = 0
            for chunk in offsets.indices {
                while entry + 1 < entries.count && entries[entry + 1].0 <= chunk + 1 { entry += 1 }
                let perChunk = entries[entry].1
                guard perChunk <= sampleCount - sampleIndex, entries[entry].0 <= chunk + 1 else { break }
                var position = offsets[chunk]
                for _ in 0..<perChunk {
                    let length = sizes[sampleIndex]
                    let auxSize = auxSizes[sampleIndex]
                    guard position >= 0, length > 0, auxSize >= 8, aux + 8 <= data.count else { break }
                    var iv = [UInt8](repeating: 0, count: 16)
                    for k in 0..<8 { iv[k] = data[aux + k] }
                    if auxSize == 8 {
                        samples.append(Mp4Sample(start: position, end: position + length, iv: iv, encryptedOffset: 0))
                    } else if auxSize >= 10 {
                        let subs = Int(data[aux + 8]) << 8 | Int(data[aux + 9])
                        var offset = position
                        var encrypted = 0
                        for sub in 0..<subs {
                            let at = aux + 10 + sub * 6
                            guard at + 6 <= data.count else { break }
                            let clear = Int(data[at]) << 8 | Int(data[at + 1])
                            let size = u32(data, at + 2)
                            offset += clear
                            if size > 0 {
                                samples.append(Mp4Sample(start: offset, end: offset + size,
                                                         iv: iv, encryptedOffset: encrypted))
                            }
                            encrypted += size
                            offset += size
                        }
                    }
                    position += length
                    aux += auxSize
                    sampleIndex += 1
                }
            }

            // 改包：把 `encv` / `enca` 换回 `frma` 里记录的原始编码，`sinf` 改成 `free`。
            var at = stbl.body
            while at + 8 <= stbl.end {
                guard let sinf = find(data, "sinf", at, stbl.end),
                      let frma = find(data, "frma", sinf.body, sinf.end),
                      frma.body + 4 <= frma.end else { break }
                let original = Array(data[frma.body..<(frma.body + 4)])
                var entryAt = sinf.start - 1
                while entryAt >= stbl.body {
                    let type = String(decoding: data[entryAt..<(entryAt + 4)], as: UTF8.self)
                    if type == "encv" || type == "enca" {
                        header.replaceSubrange(entryAt..<(entryAt + 4), with: original)
                        break
                    }
                    entryAt -= 1
                }
                header.replaceSubrange((sinf.start + 4)..<(sinf.start + 8), with: Array("free".utf8))
                for i in sinf.body..<sinf.end { header[i] = 0 }
                at = sinf.end
            }
        }

        samples.sort { $0.start < $1.start }
        return Mp4Prepared(header: header, samples: samples)
    }

    // MARK: 解密

    /// 把整段数据解出来（`start = 0` 即整集）。keystream 用 AES-ECB 批量生成再异或，
    /// 与 Android 同一条算法（AES-CTR 的计数器块 = IV + 块序号，128 位大端自增）。
    static func decrypt(_ data: [UInt8], key: [UInt8], prepared: Mp4Prepared) -> [UInt8] {
        var result = data
        let headerSize = min(prepared.header.count, result.count)
        result.replaceSubrange(0..<headerSize, with: prepared.header[0..<headerSize])

        for sample in prepared.samples {
            let start = max(0, sample.start)
            let end = min(result.count, sample.end)
            guard start < end else { continue }
            let relative = start - sample.start + sample.encryptedOffset
            var skip = relative % 16
            var blockIndex = relative / 16
            var position = start
            var remaining = end - start

            while remaining > 0 {
                var chunk = min(remaining, 64 * 1024)
                if chunk < remaining {
                    let misalign = (skip + chunk) % 16
                    if misalign != 0 { chunk -= misalign }
                    if chunk <= 0 { chunk = min(remaining, 64 * 1024) }
                }
                let blocks = (skip + chunk + 15) / 16
                let keystream = aesStream(key: key, iv: sample.iv, base: blockIndex, blocks: blocks)
                for j in 0..<chunk {
                    result[position + j] ^= keystream[skip + j]
                }
                position += chunk
                remaining -= chunk
                blockIndex += blocks
                skip = 0
            }
        }
        return result
    }

    private static func aesStream(key: [UInt8], iv: [UInt8], base: Int, blocks: Int) -> [UInt8] {
        var counters = [UInt8](repeating: 0, count: blocks * 16)
        for k in 0..<blocks {
            var block = iv
            var add = base + k
            var i = 15
            while add != 0 && i >= 0 {
                let sum = Int(block[i]) + (add & 0xFF)
                block[i] = UInt8(sum & 0xFF)
                add = (add >> 8) + (sum >> 8)
                i -= 1
            }
            for j in 0..<16 { counters[k * 16 + j] = block[j] }
        }
        return AES.ecbEncrypt(key: key, data: counters)
    }
}
