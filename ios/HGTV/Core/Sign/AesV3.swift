// SPDX-License-Identifier: GPL-3.0-only
//
// 红果自己的分组变换（不是标准 AES）：4 张 256 字节 sbox、固定初始常量、8→12 轮密钥扩展。
// 与 Android `core/AesV3.kt` 逐行对应；`x-medusa` 的密文就是它算出来的。

import Foundation

struct AesV3 {
    private let box: [Int]
    private let con: [Int]
    private let order: [Int]
    private let keys: [[Int]]

    init(key: [UInt8], timestamp: Int32) {
        let word = Int(timestamp & 3)
        box = (0..<256).map { Int(Vendor.sbox[word * 256 + $0]) }
        con = [[1, 0, 2, 3], [2, 0, 3, 1], [0, 1, 3, 2], [1, 0, 2, 3]][word]
        order = [
            [0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3],
            [0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11],
            [0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15],
            [0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3]
        ][word]
        let initial = [Int32(bitPattern: 0xca025ddc), Int32(bitPattern: 0x823dc546),
                       Int32(bitPattern: 0xc9420583), Int32(bitPattern: 0xc298225f)][word]

        let seed = Bytes.le32(initial) + Bytes.le32(initial) + Bytes.le32(initial) + Bytes.le32(initial)
        var expanded = Bytes.xor(seed, key).map { Int($0) }
        expanded += [Int](repeating: 0, count: 32)

        var rounds = 8
        for i in 4..<12 {
            let at = 4 * (i - 1)
            var a = expanded[at]
            var b = expanded[at + 1]
            var c = expanded[at + 2]
            var d = expanded[at + 3]
            if i & 3 == 0 {
                let t = ((Int(initial) >> (rounds & 24)) ^ box[b]) & 255
                b = box[c]
                c = box[d]
                d = box[a]
                a = t
            }
            rounds += 2
            expanded[at + 4] = a ^ expanded[at - 12]
            expanded[at + 5] = b ^ expanded[at - 11]
            expanded[at + 6] = c ^ expanded[at - 10]
            expanded[at + 7] = d ^ expanded[at - 9]
        }
        keys = (0..<12).map { Array(expanded[($0 * 4)..<($0 * 4 + 4)]) }
    }

    private func block(_ value: [UInt8]) -> [UInt8] {
        var rows = (0..<4).map { i in (0..<4).map { j in Int(value[i * 4 + j]) } }

        func addCon(_ offset: Int) {
            for i in 0..<4 {
                for j in 0..<4 {
                    rows[i][j] ^= keys[offset + i][con[j]]
                }
            }
        }

        func xt(_ x: Int) -> Int {
            ((x << 1) ^ ((x & 128) != 0 ? 0x1b : 0)) & 255
        }

        addCon(0)
        for round in 1...2 {
            for i in 0..<4 { for j in 0..<4 { rows[i][j] = box[rows[i][j]] } }
            let before = rows
            for i in 0..<4 { rows[i] = before[con[i]] }
            let flat = rows.flatMap { $0 }
            for i in 0..<16 { rows[i / 4][i % 4] = flat[order[i]] }
            if round == 1 {
                for i in 0..<4 {
                    let old = rows[i]
                    rows[i] = (0..<4).map { old[con[$0]] }
                }
                for i in 0..<4 {
                    let t = rows[0][i] ^ rows[1][i] ^ rows[2][i] ^ rows[3][i]
                    let u = rows[0][i]
                    rows[0][i] ^= t ^ xt(rows[0][i] ^ rows[1][i])
                    rows[1][i] ^= t ^ xt(rows[1][i] ^ rows[2][i])
                    rows[2][i] ^= t ^ xt(rows[2][i] ^ rows[3][i])
                    rows[3][i] ^= t ^ xt(rows[3][i] ^ u)
                }
            }
            addCon(round * 4)
        }
        for i in 0..<4 { for j in 0..<4 { rows[i][j] ^= keys[4 + i][j] } }
        return rows.flatMap { $0 }.map { UInt8($0 & 255) }
    }

    func encrypt(_ source: [UInt8], iv: [UInt8]) -> [UInt8] {
        precondition(source.count >= 248, "AesV3 只处理 248 字节以上的明文")
        var plaintext = [UInt8](repeating: 0, count: 32)
        for i in 0..<31 {
            let at = i * 8
            plaintext[i] = (((source[at] >> 4) & 2)
                | (source[at + 1] & 64)
                | ((source[at + 2] >> 2) & 1)
                | ((source[at + 3] << 3) & 128)
                | ((source[at + 4] >> 1) & 4)
                | ((source[at + 5] << 3) & 16)
                | ((source[at + 6] << 5) & 32)
                | ((source[at + 7] >> 4) & 8))
        }
        plaintext[31] = 1
        let first = block(Bytes.xor(Array(plaintext[0..<16]), iv))
        let key = first + block(Bytes.xor(Array(plaintext[16..<32]), first))

        var out = source
        for i in 0..<31 {
            let at = i * 8
            let k = Int(key[i])
            out[at] = (out[at] & 0xdf) | UInt8((k << 4) & 32)
            out[at + 1] = (out[at + 1] & 0xbf) | UInt8(k & 64)
            out[at + 2] = (out[at + 2] & 0xfb) | UInt8((k << 2) & 4)
            out[at + 3] = (out[at + 3] & 0xef) | UInt8((k >> 3) & 16)
            out[at + 4] = (out[at + 4] & 0xf7) | UInt8((k + k) & 8)
            out[at + 5] = (out[at + 5] & 0xfd) | UInt8((k >> 3) & 2)
            out[at + 6] = (out[at + 6] & 0xfe) | UInt8((k >> 5) & 1)
            out[at + 7] = (out[at + 7] & 0x7f) | UInt8((k << 4) & 128)
        }
        return [key.last ?? 0] + out
    }
}
