// SPDX-License-Identifier: GPL-3.0-only
//
// 签名器：Android `core/Signer.kt` 的逐行移植（来源 drpys 22261ad，GPL-3.0）。
//
// 一条请求要带六枚签名头：x-gorgon（RC4 变体）、x-helios（34 轮伪随机流）、
// x-medusa（protobuf 环境信息 + AesV3）、x-ladon / x-argus（ts 的两种字节序）、
// 以及试出来的 _rticket（只有 branch == 0 的请求才会被服务端接受）。
//
// 正确性不靠「看起来像」：`ios/tools/signer_oracle.swift` 拿 Android 侧的
// `core/src/test/resources/signing.json` 逐条比对 hash / gorgon / helios / medusa，
// 12 组向量全部一致才算移植完成（见文档「验证」一节）。

import Foundation

/// 有序参数表：签名覆盖 query 原文，顺序必须与调用方写入的一致。
struct OrderedParams {
    private(set) var items: [(String, String)] = []

    init() {}
    init(_ pairs: [(String, String)]) { items = pairs }

    subscript(_ key: String) -> String? {
        get { items.first { $0.0 == key }?.1 }
        set {
            if let index = items.firstIndex(where: { $0.0 == key }) {
                if let newValue { items[index].1 = newValue } else { items.remove(at: index) }
            } else if let newValue {
                items.append((key, newValue))
            }
        }
    }

    /// 与 Kotlin 的 `values + mapOf(...)` 同义：已有键原地替换，新键追加。
    func merging(_ pairs: [(String, String)]) -> OrderedParams {
        var next = self
        for (key, value) in pairs { next[key] = value }
        return next
    }

    var encoded: String {
        items.map { "\(Signer.encode($0.0))=\(Signer.encode($0.1))" }.joined(separator: "&")
    }
}

final class Signer {
    struct Request {
        let url: String
        let headers: [String: String]
        let body: [UInt8]?
        let method: String
    }

    private let clock: () -> Int64
    private let random: () -> Double
    private var device: [String: String]

    init(clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
         random: @escaping () -> Double = { Double.random(in: 0..<1) },
         device: [String: String] = [:]) {
        self.clock = clock
        self.random = random
        self.device = device.filter { !$0.value.isEmpty }
    }

    func updateDevice(_ values: [String: String]) {
        device = values.filter { !$0.value.isEmpty }
    }

    // MARK: - 基础

    private func rand() -> Int32 {
        Int32(truncatingIfNeeded: Int64(random() * 4_294_967_296.0))
    }

    private func unsigned(_ value: Int32) -> UInt32 { UInt32(bitPattern: value) }

    private func deviceValue(_ d: [String: String], _ key: String, _ fallback: String) -> String {
        if let value = d[key], !value.isEmpty { return value }
        return fallback
    }

    private func deviceId(_ d: [String: String]) -> String {
        deviceValue(d, "device_id", Vendor.device["device_id"] ?? "")
    }

    private func deviceType(_ d: [String: String]) -> String { deviceValue(d, "device_type", "25053RT47C") }
    private func deviceBrand(_ d: [String: String]) -> String { deviceValue(d, "device_brand", "Redmi") }

    private func manufacturer(_ d: [String: String]) -> String {
        let brand = deviceBrand(d)
        if brand.lowercased().contains("samsung") { return "Samsung" }
        if brand.lowercased().contains("redmi") || brand.lowercased().contains("xiaomi") { return "Xiaomi" }
        return brand
    }

    // MARK: - protobuf

    private enum FieldValue {
        case sint(Int64)
        case int(Int32)
        case string(String)
        case bytes([UInt8])
        case float(Float)
    }

    private struct Field {
        let tag: Int
        let value: FieldValue
    }

    private func f(_ tag: Int, _ value: String) -> Field { Field(tag: tag, value: .string(value)) }
    private func f(_ tag: Int, _ value: [UInt8]) -> Field { Field(tag: tag, value: .bytes(value)) }
    private func sint(_ tag: Int, _ value: Int64) -> Field { Field(tag: tag, value: .sint(value)) }
    private func sint(_ tag: Int, _ value: Int) -> Field { Field(tag: tag, value: .sint(Int64(value))) }
    private func flt(_ tag: Int, _ value: Float) -> Field { Field(tag: tag, value: .float(value)) }

    private func varint(_ value: Int64) -> [UInt8] {
        var n = UInt64(bitPattern: value)
        var out = [UInt8]()
        while n > 127 {
            out.append(UInt8((n & 127) | 128))
            n >>= 7
        }
        out.append(UInt8(n))
        return out
    }

    private func proto(_ fields: [Field]) -> [UInt8] {
        var out = [UInt8]()
        for field in fields {
            switch field.value {
            case .sint(let value):
                out += varint(Int64(field.tag) << 3)
                out += varint(value < 0 ? -value * 2 - 1 : value * 2)
            case .int(let value):
                out += varint(Int64(field.tag) << 3)
                out += varint(Int64(value))
            case .float(let value):
                out += varint((Int64(field.tag) << 3) | 5)
                var bits = value.bitPattern.littleEndian
                withUnsafeBytes(of: &bits) { out += Array($0) }
            case .string(let value):
                let bytes = Bytes.utf8(value)
                if !bytes.isEmpty {
                    out += varint((Int64(field.tag) << 3) | 2)
                    out += varint(Int64(bytes.count))
                    out += bytes
                }
            case .bytes(let bytes):
                if !bytes.isEmpty {
                    out += varint((Int64(field.tag) << 3) | 2)
                    out += varint(Int64(bytes.count))
                    out += bytes
                }
            }
        }
        return out
    }

    // MARK: - 分支号

    private func getIv(_ initial: Int32, _ data: [UInt8]) -> Int32 {
        var value = initial
        for i in data.indices {
            let v = UInt32(bitPattern: value)
            let b = UInt32(data[i])
            if i & 1 == 0 {
                value = Int32(bitPattern: (v >> 4) ^ v ^ (v << 6) ^ b)
            } else {
                value = Int32(bitPattern: ~((v >> 7) ^ v ^ (b | (v << 12))))
            }
        }
        return value
    }

    private func iv(query: [UInt8], body: [UInt8], ts: Int32) -> Int32 {
        let step = getIv(0x20230928, query)
        let step2 = getIv(step, body)
        return getIv(step2, Bytes.le32(ts))
    }

    func branch(query: String, body: [UInt8]?, ts: Int32) -> Int {
        let bodyDigest = body.map { Hash.md5($0) } ?? [UInt8](repeating: 0, count: 16)
        return Int(iv(query: Hash.sm3(Bytes.utf8(query)), body: bodyDigest, ts: ts) & 15) % 3
    }

    private func sumMd5(_ data: [UInt8]) -> Int32 {
        var check: UInt32 = 0x20220420
        for i in 0..<12 {
            let temp = (check >> (i & 1 == 0 ? 3 : 5)) ^ check
            if i & 1 == 0 {
                check = UInt32(data[i]) ^ (check << 7)
            } else {
                check = ~(UInt32(data[i]) | (check << 11))
            }
            check ^= temp
        }
        return Int32(bitPattern: (check | 4) ^ 0x0100_0000)
    }

    func hashF13(query: [UInt8], body: [UInt8], ts: Int32) -> [UInt8] {
        let raw = iv(query: query, body: body, ts: ts)
        let index = Int((raw & 15)) / 3
        precondition((raw & 15) % 3 == 0, "Unsupported hash branch")
        let ivV1 = [Int32(bitPattern: 0xc4a78580), Int32(bitPattern: 0xb3c0fd39),
                    Int32(bitPattern: 0xc58c5686), Int32(bitPattern: 0xc9aa3ba7),
                    Int32(bitPattern: 0xf5a7adf2), Int32(bitPattern: 0x963c2ed1)][index]
        let count = ivV1 &+ ts
        let tt = Vendor.hashTable.map { Bytes.ror($0, Int(count &+ 1) & 255) }
        let shift = Int(count &+ 2) & 7
        let pad: [UInt8] = [0xfa, 0x45, 0x61, 0xd7].map { raw in
            let value = Int32(raw) | (Int32(raw) << 8)
            return UInt8(truncatingIfNeeded: value >> Int32(shift))
        }
        let data = query + body + Bytes.le32(ts) + pad + Bytes.hex("00000000000001a0")
        var di = stride(from: 0, to: data.count, by: 4).map { Bytes.i32(data, $0) }
        for i in 0..<112 {
            let x = di[i + 1]
            let y = di[i + 14]
            let xr = UInt32(bitPattern: x) >> 3
            let yr = UInt32(bitPattern: y) >> 10
            let part = Bytes.rol(x, 14) ^ Bytes.rol(x, 25) ^ Int32(bitPattern: xr)
            let part2 = Bytes.rol(y, 13) ^ Bytes.rol(y, 15) ^ Int32(bitPattern: yr)
            di.append(di[i] &+ di[i + 9] &+ part &+ part2)
        }
        let initialSeed: [Int32] = [
            Int32(bitPattern: 0x7aba4fc8), Int32(bitPattern: 0x67166507),
            Int32(bitPattern: 0x6403fa00), Int32(bitPattern: 0x340f512f),
            984304912, Int32(bitPattern: UInt32(3005047866)), Int32(bitPattern: UInt32(2874125293)),
            Int32(bitPattern: UInt32(2152413264))
        ]
        let initial = initialSeed.map { Bytes.ror($0, Int(count)) }
        let chosen = [
            [101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3],
            [96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4],
            [96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5],
            [99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6],
            [96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4],
            [100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5]
        ][index]
        var d = initial
        for i in 0..<chosen[0] {
            let base = ivV1 &+ Int32(i)
            let n1 = ((d[chosen[3]] ^ d[chosen[4]]) & d[chosen[1]]) ^ d[chosen[3]]
            let n2 = Bytes.rol(d[chosen[1]], 26) ^ Bytes.rol(d[chosen[1]], 21) ^ Bytes.rol(d[chosen[1]], 7)
            let n4 = di[Int(base & 127)] &+ n1 &+ n2 &+ tt[Int(base & 63)] &+ d[chosen[5]]
            let n5 = Bytes.rol(d[chosen[2]], 30) ^ Bytes.rol(d[chosen[2]], 19) ^ Bytes.rol(d[chosen[2]], 10)
            let n6 = (d[chosen[2]] & d[chosen[6]]) | ((d[chosen[2]] | d[chosen[6]]) & d[chosen[7]])
            let old = d[chosen[9]]
            d.insert(d.remove(at: 7), at: 0)
            d[chosen[10]] = n5 &+ n6 &+ n4
            d[chosen[8]] = old &+ n4
        }
        var ret = [UInt8]()
        for i in 0..<8 { ret += Bytes.be32(d[i] &+ initial[i]) }
        let folded = Bytes.xor(Array(ret[0..<16]), Array(ret[16..<32]))
        return folded + Bytes.le32(sumMd5(folded))
    }

    // MARK: - gorgon

    func gorgon(query: String, body: [UInt8]?, ts: Int32, random: Int32) -> String {
        var input = Array(Hash.md5(Bytes.utf8(query)).prefix(4))
        input += body.map { Array(Hash.md5($0).prefix(4)) } ?? [UInt8](repeating: 0, count: 4)
        input += [UInt8](repeating: 0, count: 4)
        input += Bytes.le32(67503104)
        input += Bytes.be32(ts)

        let key: [Int] = [0x4a, 0x40, 0x16, Int((random >> 8) & 255), 0x47, 0x6c, 1, Int(random & 255)]
        var s = (0...255).map { $0 }
        var j = 0
        for i in 0...255 {
            j = (j + s[i] + key[i % 8]) & 255
            s[i] = s[j]
        }
        j = 0
        var out = [UInt8](repeating: 0, count: input.count)
        for n in input.indices {
            let i = n + 1
            j = (j + s[i]) & 255
            let y = s[j & 255]
            s[i] = y
            out[n] = input[n] ^ UInt8(s[(y + y) & 255])
        }
        for i in out.indices {
            let value = ((out[i] >> 4) | (out[i] << 4)) & 255
            let next = out[i + 1 < out.count ? i + 1 : 0]
            let reversed = Bytes.reverseByte(next ^ value)
            out[i] = ~(reversed ^ 20)
        }
        return Bytes.hex(Bytes.hex("8404") + [UInt8(truncatingIfNeeded: random),
                                              UInt8(truncatingIfNeeded: random >> 8), 0x40, 1] + out)
    }

    // MARK: - helios

    func helios(ts: Int32) -> String {
        let random = rand()
        let ascii = Bytes.utf8(Bytes.hex(Hash.md5(Bytes.le32(random) + Bytes.utf8("8662"))))
        var words = (0..<4).map { Bytes.i64(ascii, $0 * 8, little: true) }
        var table: [Int64] = [words[0]]
        var b0 = words.removeFirst()
        var b8 = words.removeFirst()
        for i in 0..<34 {
            var x8 = (ror64(b8, 8) &+ b0) ^ Int64(i)
            words.append(x8)
            x8 = x8 ^ ror64(b0, 61)
            table.append(x8)
            b0 = x8
            b8 = words.removeFirst()
        }
        let text = Bytes.utf8("\(ts)-1588093228-8662")
        let padding = 16 - text.count % 16
        let data = text + [UInt8](repeating: UInt8(padding), count: padding)
        var output = Bytes.le32(random)
        for at in stride(from: 0, to: data.count, by: 16) {
            var a = Bytes.i64(data, at, little: true)
            var b = Bytes.i64(data, at + 8, little: true)
            for i in 0..<34 {
                b = table[i] ^ (a &+ ror64(b, 8))
                a = b ^ ror64(a, 61)
            }
            output += Bytes.le64(a) + Bytes.le64(b)
        }
        return Bytes.base64(output)
    }

    private func ror64(_ value: Int64, _ n: Int) -> Int64 {
        let v = UInt64(bitPattern: value)
        let s = UInt64(n & 63)
        if s == 0 { return value }
        return Int64(bitPattern: (v >> s) | (v << (64 - s)))
    }

    // MARK: - medusa

    private func deviceProto(_ d: [String: String]) -> [UInt8] {
        let osVersion = deviceValue(d, "os_version", "16")
        let osApi = Int(deviceValue(d, "os_api", "36")) ?? 36
        return proto([
            sint(1, 1), sint(2, 2), f(3, "8662"), f(4, deviceId(d)),
            f(5, "Ai6svO3PyrwDOUSmO6ZcResxu"), f(6, "!noperm!"), sint(7, -888888),
            sint(8, -888888), sint(9, 3), sint(10, -888888), f(11, "!notset!"),
            f(12, "Asia/Shanghai,8"), f(13, "zh_CN"), sint(14, 4),
            flt(16, 255.24993896484375), flt(17, 35.58599090576172),
            flt(18, 3.467449188232422), flt(19, 3.467449188232422),
            flt(20, 255.1754913330078), flt(21, 42.17544174194336),
            f(22, osVersion), sint(23, 41), sint(24, osApi),
            sint(25, 1728388016635), sint(26, 1728388016635), sint(27, 1728388016635),
            sint(28, 1728388016637), sint(29, -1), f(30, deviceType(d)),
            f(31, deviceBrand(d)), f(32, deviceType(d)), f(33, deviceType(d)),
            f(34, manufacturer(d)), f(35, deviceBrand(d)), f(36, deviceBrand(d)),
            sint(38, 31)
        ])
    }

    private func xmxor(_ data: [UInt8], _ key: [UInt8]) -> [UInt8] {
        var encoded = [Int](repeating: 0, count: data.count)
        for i in data.indices {
            let at = (i * 4) & 28
            let d0 = Int(key[at])
            let d1 = Int(key[at + 1])
            let v = Int(data[i])
            var d2 = ((((v << 4) | (v >> 4)) & 255) + d0)
            d2 = (~d2) ^ d1
            d2 = (((d2 & 255) << 3) | ((d2 & 255) >> 5)) & 255
            d2 = (d2 + d1) & 255
            d2 = (d2 ^ d0) & 255
            encoded[data.count - i - 1] = (~d2) & 255
        }
        let last = encoded[encoded.count - 1] ^ encoded[encoded.count - 2]
        let first = encoded[0]
        encoded[0] = ((~last) + first) & 255
        encoded[1] = ((encoded[0] ^ encoded[encoded.count - 1] ^ 254) + encoded[1]) & 255
        encoded[2] = (encoded[2] + ((last &- first)
            ^ (((encoded[1] << 3) | (encoded[1] >> 5)) & 255) ^ 2)) & 255
        for i in 0..<(encoded.count - 4) {
            let temp = (((encoded[i + 2] << 3) | (encoded[i + 2] >> 5)) & 255)
                ^ encoded[i + 1] ^ (i + 3)
            encoded[i + 3] = ((~temp) + encoded[i + 3]) & 255
        }
        encoded[encoded.count - 1] = encoded[encoded.count - 1] ^ encoded[encoded.count - 2]
        encoded[0] = ((encoded[0] ^ encoded[1]) + encoded.dropFirst().reduce(0, +)) & 255
        return encoded.map { UInt8($0 & 255) }
    }

    func medusa(url: String, body: [UInt8]?, ts: Int32, device override: [String: String]? = nil) -> String {
        let d = (override ?? device).filter { !$0.value.isEmpty }
        let bodyMd5 = body.map { Hash.md5($0) } ?? [UInt8](repeating: 0, count: 16)
        let query = Bytes.utf8(url.components(separatedBy: "?").dropFirst().joined(separator: "?"))
        let querySm3 = Hash.sm3(query)
        let queryBodyTs = hashF13(query: querySm3, body: bodyMd5, ts: ts)

        let nested = proto([sint(1, 111), sint(2, 10), sint(3, 694367), sint(5, 586952199)])
        let messageRand = unsigned(rand())
        let envLaunch = Int32(random() * 21) + 100
        let envPid = Int32(random() * 2000) + 10001
        let version = deviceValue(d, "version_name", Vendor.device["version_name"] ?? "7.1.3.32")
        let env = proto([
            sint(1, Int(envLaunch)), sint(2, 146331399), sint(3, 146331396), sint(5, 7),
            f(6, "v04.06.04.03-bugfix"), sint(7, Int(envPid)), f(12, deviceProto(d)),
            f(13, proto([sint(1, Int(clock() / 1000)), sint(2, -2), sint(4, 200)])),
            f(14, version)
        ])
        let queryHash = Hash.sm3(query + bodyMd5 + Bytes.utf8("none"))
        let message = proto([
            f(1, Bytes.hex("f7e85ffad7d7dc3bd62ac87057cf6118")),
            sint(2, 3), sint(3, Int(messageRand)), f(4, "8662"), f(5, deviceId(d)),
            f(6, "1588093228"), f(7, version), f(8, "v04.06.04-ml-android"),
            sint(9, 67503104), f(10, Bytes.hex("4001000000000000")),
            sint(12, Int(ts)), f(13, queryBodyTs), f(14, Array(querySm3.prefix(6))),
            f(15, nested), f(16, "AXYQOS6n2m60x1fVZHIrH3iol"), sint(17, Int(ts)),
            f(19, queryHash), f(20, "none"), sint(21, 312), f(23, env),
            f(24, "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0,\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}")
        ])

        let r = rand()
        let signKey = Bytes.hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
        let hash = Hash.sm3(signKey + Bytes.le32(r) + signKey)
        let d1 = Int32((r >> 16) & 255)
        let rU = UInt32(bitPattern: r)
        let mixed = ((UInt32(bitPattern: d1) << 11) | (rU >> 24))
            ^ UInt32(bitPattern: d1 >> 5) ^ UInt32(bitPattern: d1)
        let seed = Bytes.le32(Int32(bitPattern: ~mixed))

        let transformed = (Bytes.hex("4001000000000000") + xmxor(message, hash)).reversed() as [UInt8]
        var scrambled = Array(transformed)
        for i in scrambled.indices {
            scrambled[i] ^= seed[(~i) & 3]
        }
        let check = ((Int32(querySm3[0]) & 63) << 14) | 0x18000001 | ((Int32(queryBodyTs[0]) & 63) << 8)
        let packed = [UInt8(0x35)] + Bytes.le32(rand()) + Bytes.le32(check) + scrambled
            + [UInt8(truncatingIfNeeded: r >> 16), UInt8(truncatingIfNeeded: r >> 24)]
        let aes = AesV3(key: Bytes.hex("f1593376766ea98d34f31b057a9d5be4"), timestamp: ts)
        let encrypted = aes.encrypt(packed, iv: Bytes.hex("1fe109a4125283f418de9e051a969e12"))

        let versionBytes = Bytes.hex("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        var prefix = [UInt8]()
        for i in stride(from: 0, to: 20, by: 4) {
            prefix += Bytes.le32(Bytes.i32(versionBytes, i, little: true) ^ ts)
        }
        return Bytes.base64(prefix + [UInt8(truncatingIfNeeded: r),
                                      UInt8(truncatingIfNeeded: r >> 8), 0, 1] + encrypted)
    }

    // MARK: - 组装

    func sign(base: String,
              params: OrderedParams,
              body: [UInt8]? = nil,
              method: String? = nil,
              deviceOverride: [String: String]? = nil) -> Request {
        let d = (deviceOverride ?? device).filter { !$0.value.isEmpty }
        let now = clock()
        let ts = Int32(truncatingIfNeeded: now / 1000)
        var query = ""
        var ticket = now

        for offset in 0...127 {
            ticket = now + Int64(offset)
            let merged = params.merging([("ts", "\(ts)"), ("_rticket", "\(ticket)")])
            query = merged.encoded
            if branch(query: query, body: body, ts: ts) == 0 {
                let url = "\(base)?\(query)"
                let random = Int32(random() * 65536)
                var headers: [String: String] = [
                    "User-Agent": Vendor.userAgent(d, video: body != nil),
                    "Accept": "application/json; charset=utf-8,application/x-protobuf",
                    "x-ss-req-ticket": "\(ticket)",
                    "x-tt-request-tag": "t=0;n=0",
                    "sdk-version": "2",
                    "passport-sdk-version": base.contains("/passport/") ? "5051452" : "6031190",
                    "x-xs-from-web": "0",
                    "x-khronos": "\(ts)",
                    "x-ladon": Bytes.base64(Bytes.be32(ts)),
                    "x-argus": Bytes.base64(Bytes.le32(ts)),
                    "x-gorgon": gorgon(query: query, body: body, ts: ts, random: random),
                    "x-helios": helios(ts: ts),
                    "x-medusa": medusa(url: url, body: body, ts: ts, device: d),
                    "x-tt-dt": ""
                ]
                let passport = base.contains("/passport/")
                if passport {
                    headers["x-vc-bdturing-sdk-version"] = "4.0.3.cn"
                    headers["lc"] = "101"
                    headers["x-ss-dp"] = "8662"
                }
                if let body {
                    headers["Content-Type"] = "application/json; charset=UTF-8"
                    headers["x-vc-bdturing-sdk-version"] = "3.7.2.cn"
                    headers["x-ss-stub"] = Bytes.hex(Hash.md5(body)).uppercased()
                }
                let actualMethod = method ?? (body == nil ? "GET" : "POST")
                return Request(url: url, headers: headers, body: body, method: actualMethod)
            }
        }
        fatalError("无法生成请求签名")
    }

    static func encode(_ value: String) -> String {
        var out = ""
        for byte in value.utf8 {
            let isDigit = byte >= 0x30 && byte <= 0x39
            let isUpper = byte >= 0x41 && byte <= 0x5A
            let isLower = byte >= 0x61 && byte <= 0x7A
            let isSafe = isDigit || isUpper || isLower
                || byte == 0x2D || byte == 0x5F || byte == 0x2E || byte == 0x2A
            if isSafe {
                out.append(Character(UnicodeScalar(byte)))
            } else if byte == 0x20 {
                out.append("%20")
            } else {
                out.append(String(format: "%%%02X", byte))
            }
        }
        return out
    }
}
