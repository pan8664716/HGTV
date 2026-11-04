// SPDX-License-Identifier: GPL-3.0-only
//
// 字节与哈希工具：与 Android `core/Bytes.kt` 逐函数对应。
// 签名算法用到的都是定长整数运算，这里统一按小端 / 大端显式拼装，
// 不依赖平台的字节序。

import Foundation
import CommonCrypto

enum Bytes {
    static func u8(_ value: UInt8) -> Int { Int(value) }

    static func le32(_ value: Int32) -> [UInt8] {
        let v = UInt32(bitPattern: value)
        return [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)]
    }

    static func be32(_ value: Int32) -> [UInt8] {
        let v = UInt32(bitPattern: value)
        return [UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)]
    }

    static func le64(_ value: Int64) -> [UInt8] {
        let v = UInt64(bitPattern: value)
        return (0..<8).map { UInt8((v >> (8 * UInt64($0))) & 0xFF) }
    }

    static func i32(_ data: [UInt8], _ at: Int, little: Bool = false) -> Int32 {
        var value: UInt32 = 0
        for i in 0..<4 {
            let byte = UInt32(data[at + i])
            if little { value |= byte << (8 * UInt32(i)) }
            else { value = (value << 8) | byte }
        }
        return Int32(bitPattern: value)
    }

    static func i64(_ data: [UInt8], _ at: Int, little: Bool = false) -> Int64 {
        var value: UInt64 = 0
        for i in 0..<8 {
            let byte = UInt64(data[at + i])
            if little { value |= byte << (8 * UInt64(i)) }
            else { value = (value << 8) | byte }
        }
        return Int64(bitPattern: value)
    }

    static func xor(_ a: [UInt8], _ b: [UInt8]) -> [UInt8] {
        guard !b.isEmpty else { return a }
        return (0..<a.count).map { a[$0] ^ b[$0 % b.count] }
    }

    /// 与 `Integer.rotateLeft` 一致：位移量按 32 取模。
    static func rol(_ value: Int32, _ n: Int) -> Int32 {
        let v = UInt32(bitPattern: value)
        let s = UInt32(n & 31)
        if s == 0 { return value }
        return Int32(bitPattern: (v << s) | (v >> (32 - s)))
    }

    static func ror(_ value: Int32, _ n: Int) -> Int32 {
        let v = UInt32(bitPattern: value)
        let s = UInt32(n & 31)
        if s == 0 { return value }
        return Int32(bitPattern: (v >> s) | (v << (32 - s)))
    }

    /// 逐字节反转（等价于 `Integer.reverse(v & 255) ushr 24`）。
    static func reverseByte(_ value: UInt8) -> UInt8 {
        var v = value
        var out: UInt8 = 0
        for _ in 0..<8 {
            out = (out << 1) | (v & 1)
            v >>= 1
        }
        return out
    }

    static func hex(_ data: [UInt8]) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func hex(_ text: String) -> [UInt8] {
        precondition(text.count % 2 == 0, "hex 长度必须是偶数")
        var out = [UInt8]()
        out.reserveCapacity(text.count / 2)
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            out.append(UInt8(text[index..<next], radix: 16) ?? 0)
            index = next
        }
        return out
    }

    static func base64(_ data: [UInt8]) -> String {
        Data(data).base64EncodedString()
    }

    static func base64(_ text: String) -> [UInt8] {
        [UInt8](Data(base64Encoded: text) ?? Data())
    }

    static func utf8(_ text: String) -> [UInt8] { [UInt8](text.utf8) }

    static func string(_ data: [UInt8]) -> String {
        String(decoding: data, as: UTF8.self)
    }
}

// MARK: - 哈希

enum Hash {
    static func md5(_ data: [UInt8]) -> [UInt8] {
        var digest = [UInt8](repeating: 0, count: Int(CC_MD5_DIGEST_LENGTH))
        data.withUnsafeBufferPointer { buffer in
            _ = CC_MD5(buffer.baseAddress, CC_LONG(buffer.count), &digest)
        }
        return digest
    }

    static func sha512(_ data: [UInt8]) -> [UInt8] {
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA512_DIGEST_LENGTH))
        data.withUnsafeBufferPointer { buffer in
            _ = CC_SHA512(buffer.baseAddress, CC_LONG(buffer.count), &digest)
        }
        return digest
    }

    /// SM3（GB/T 32905-2016）：签名分支号与 `x-medusa` 都要它。
    static func sm3(_ data: [UInt8]) -> [UInt8] {
        var iv: [UInt32] = [0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
                            0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e]
        var message = data
        let bitLength = UInt64(data.count) * 8
        message.append(0x80)
        while message.count % 64 != 56 { message.append(0) }
        for i in (0..<8).reversed() {
            message.append(UInt8((bitLength >> (8 * UInt64(i))) & 0xFF))
        }

        func p0(_ x: UInt32) -> UInt32 { x ^ rotl(x, 9) ^ rotl(x, 17) }
        func p1(_ x: UInt32) -> UInt32 { x ^ rotl(x, 15) ^ rotl(x, 23) }
        func rotl(_ x: UInt32, _ n: UInt32) -> UInt32 {
            let s = n & 31
            return s == 0 ? x : (x << s) | (x >> (32 - s))
        }

        for block in stride(from: 0, to: message.count, by: 64) {
            var w = [UInt32](repeating: 0, count: 68)
            for i in 0..<16 {
                let at = block + i * 4
                w[i] = (UInt32(message[at]) << 24) | (UInt32(message[at + 1]) << 16)
                    | (UInt32(message[at + 2]) << 8) | UInt32(message[at + 3])
            }
            for j in 16..<68 {
                w[j] = p1(w[j - 16] ^ w[j - 9] ^ rotl(w[j - 3], 15))
                    ^ rotl(w[j - 13], 7) ^ w[j - 6]
            }
            var a = iv[0], b = iv[1], c = iv[2], d = iv[3]
            var e = iv[4], f = iv[5], g = iv[6], h = iv[7]
            for j in 0..<64 {
                let t: UInt32 = j < 16 ? 0x79cc4519 : 0x7a879d8a
                let ss1 = rotl((rotl(a, 12) &+ e &+ rotl(t, UInt32(j))) & 0xFFFF_FFFF, 7)
                let ss2 = ss1 ^ rotl(a, 12)
                let ff: UInt32 = j < 16 ? (a ^ b ^ c) : ((a & b) | (a & c) | (b & c))
                let gg: UInt32 = j < 16 ? (e ^ f ^ g) : ((e & f) | (~e & g))
                let tt1 = ff &+ d &+ ss2 &+ (w[j] ^ w[j + 4])
                let tt2 = gg &+ h &+ ss1 &+ w[j]
                d = c
                c = rotl(b, 9)
                b = a
                a = tt1
                h = g
                g = rotl(f, 19)
                f = e
                e = p0(tt2)
            }
            iv[0] ^= a; iv[1] ^= b; iv[2] ^= c; iv[3] ^= d
            iv[4] ^= e; iv[5] ^= f; iv[6] ^= g; iv[7] ^= h
        }

        var out = [UInt8]()
        for value in iv {
            out += [UInt8((value >> 24) & 0xFF), UInt8((value >> 16) & 0xFF),
                    UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)]
        }
        return out
    }
}

// MARK: - AES（设备注册的 ttEncrypt 用）

enum AES {
    /// AES-CBC / 无填充：长度必须是 16 的整数倍，调用方自己补 PKCS7。
    static func cbcEncrypt(key: [UInt8], iv: [UInt8], data: [UInt8]) -> [UInt8] {
        precondition(key.count == 16 && iv.count == 16)
        var out = [UInt8](repeating: 0, count: data.count + kCCBlockSizeAES128)
        var moved = 0
        let status = key.withUnsafeBufferPointer { keyPtr in
            iv.withUnsafeBufferPointer { ivPtr in
                data.withUnsafeBufferPointer { dataPtr in
                    CCCrypt(CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(0), keyPtr.baseAddress, key.count,
                            ivPtr.baseAddress, dataPtr.baseAddress, data.count,
                            &out, out.count, &moved)
                }
            }
        }
        guard status == kCCSuccess else { return [] }
        return Array(out.prefix(moved))
    }
}

// MARK: - AES 解密（播放地址）

extension AES {
    /// AES-ECB（无 IV、无填充）：用来批量生成 CTR 的 keystream。
    static func ecbEncrypt(key: [UInt8], data: [UInt8]) -> [UInt8] {
        guard key.count == 16, !data.isEmpty, data.count % 16 == 0 else { return [] }
        var out = [UInt8](repeating: 0, count: data.count + kCCBlockSizeAES128)
        var moved = 0
        let status = key.withUnsafeBufferPointer { keyPtr in
            data.withUnsafeBufferPointer { dataPtr in
                CCCrypt(CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES),
                        CCOptions(kCCOptionECBMode), keyPtr.baseAddress, key.count, nil,
                        dataPtr.baseAddress, data.count, &out, out.count, &moved)
            }
        }
        guard status == kCCSuccess else { return [] }
        return Array(out.prefix(moved))
    }

    /// AES-CBC 解密，不做去填充（调用方自己按最后一个字节裁）。
    static func cbcDecrypt(key: [UInt8], iv: [UInt8], data: [UInt8]) -> [UInt8] {
        guard key.count == 16, iv.count == 16, data.count % 16 == 0 else { return [] }
        var out = [UInt8](repeating: 0, count: data.count + kCCBlockSizeAES128)
        var moved = 0
        let status = key.withUnsafeBufferPointer { keyPtr in
            iv.withUnsafeBufferPointer { ivPtr in
                data.withUnsafeBufferPointer { dataPtr in
                    CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(0), keyPtr.baseAddress, key.count,
                            ivPtr.baseAddress, dataPtr.baseAddress, data.count,
                            &out, out.count, &moved)
                }
            }
        }
        guard status == kCCSuccess else { return [] }
        return Array(out.prefix(moved))
    }
}

// MARK: - gzip

enum Gzip {
    /// gzip 的「stored」块：不做压缩，但格式完全合法。
    ///
    /// 设备注册的 body 是 `ttEncrypt(gzip(json))`，服务端只要求 gzip 容器；
    /// 为了不引入 zlib（iOS 侧没有现成的 gzip 模块），这里直接写不压缩的块——
    /// 体积大几百字节，对一次注册请求毫无影响，换来零依赖。
    static func wrap(_ data: [UInt8]) -> [UInt8] {
        var out: [UInt8] = [0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff]
        var offset = 0
        while offset < data.count {
            let chunk = min(65535, data.count - offset)
            let last = offset + chunk >= data.count
            out.append(last ? 1 : 0)
            out.append(UInt8(chunk & 0xFF))
            out.append(UInt8((chunk >> 8) & 0xFF))
            let complement = UInt16(chunk) ^ 0xFFFF
            out.append(UInt8(complement & 0xFF))
            out.append(UInt8((complement >> 8) & 0xFF))
            out += data[offset..<(offset + chunk)]
            offset += chunk
        }
        if data.isEmpty {
            out += [1, 0, 0, 0xFF, 0xFF]
        }
        out += Bytes.le32(Int32(bitPattern: crc32(data)))
        out += Bytes.le32(Int32(truncatingIfNeeded: data.count))
        return out
    }

    private static let table: [UInt32] = (0..<256).map { index in
        var c = UInt32(index)
        for _ in 0..<8 {
            c = (c & 1) != 0 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1)
        }
        return c
    }

    static func crc32(_ data: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}
