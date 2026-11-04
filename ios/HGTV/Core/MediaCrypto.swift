// SPDX-License-Identifier: GPL-3.0-only
//
// 播放地址解密：与 Android `core/MediaCrypto.kt` 的 `decryptUrl` 逐行对应。
//
// 播放接口下发的 `main_url` 不是明文 URL，而是一段 base64 密文：
//   `SHA-512(SHA-512(key_seed) + 固定盐)` 的前 32 字节拆成 AES-128-CBC 的 key / iv，
//   解开后去掉 PKCS7 填充与尾部 0，才是真正的地址。
// 这一步不做，界面拿到的就是一串「不像地址的地址」。

import Foundation

enum MediaCrypto {
    private static let salt = Bytes.hex(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )

    static func decryptUrl(_ value: String, seed: [UInt8]) -> String {
        let raw = Bytes.base64(value)
        guard raw.count >= 20, raw[0] == 0xa8, raw[2] == 1, raw[3] == 0 else { return value }
        let digest = Hash.sha512(Hash.sha512(seed) + salt)
        let body = Array(raw[4..<(4 + (raw.count - 4) / 16 * 16)])
        var bytes = AES.cbcDecrypt(key: Array(digest[0..<16]), iv: Array(digest[16..<32]), data: body)
        guard !bytes.isEmpty else { return value }
        let padding = Int(bytes.last ?? 0)
        if padding >= 1 && padding <= 16 && padding <= bytes.count {
            bytes.removeLast(padding)
        }
        while let last = bytes.last, last == 0 { bytes.removeLast() }
        let text = String(decoding: bytes, as: UTF8.self)
        return text.hasPrefix("http") ? text : value
    }
}
