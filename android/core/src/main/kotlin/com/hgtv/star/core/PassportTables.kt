// Derived from github.com/aston314/hongguo-tv (passport_constants.go); GPL-3.0-only.
// Branch-2 (F13) signature tables. Values are byte-identical to the reference client.
package com.hgtv.star.core

/** F13 每轮的 16 个消息字下标：5 组 × 64 轮。 */
internal val PASSPORT_ORDERS = intArrayOf(
    15, 7, 4, 0, 9, 8, 3, 10, 6, 11, 5, 13, 14, 1, 12, 2,
    15, 5, 8, 12, 0, 9, 2, 1, 3, 7, 14, 6, 11, 10, 13, 4,
    6, 5, 0, 7, 12, 0, 10, 4, 8, 15, 1, 11, 13, 9, 2, 14,
    6, 11, 2, 5, 4, 3, 8, 1, 1, 7, 10, 0, 13, 12, 9, 14,
    13, 7, 14, 15, 11, 2, 8, 3, 12, 5, 9, 1, 0, 4, 6, 10,
    13, 9, 2, 6, 15, 11, 10, 4, 8, 7, 0, 12, 5, 3, 1, 14,
    12, 9, 15, 7, 6, 15, 3, 14, 2, 13, 4, 5, 1, 11, 10, 0,
    12, 5, 10, 9, 14, 8, 2, 4, 4, 7, 3, 15, 1, 6, 11, 0,
    11, 15, 4, 8, 2, 10, 7, 0, 9, 13, 6, 1, 14, 3, 5, 12,
    11, 6, 10, 5, 8, 2, 12, 3, 7, 15, 14, 9, 13, 0, 1, 4,
    9, 6, 8, 15, 5, 8, 0, 4, 10, 11, 3, 13, 1, 2, 12, 14,
    9, 13, 12, 6, 4, 7, 10, 3, 3, 15, 0, 8, 1, 5, 2, 14,
    1, 0, 13, 15, 9, 10, 11, 14, 4, 2, 8, 7, 3, 6, 12, 5,
    1, 8, 10, 12, 15, 9, 5, 6, 11, 0, 3, 4, 2, 14, 7, 13,
    4, 8, 15, 0, 12, 15, 14, 13, 10, 1, 6, 2, 7, 9, 5, 3,
    4, 2, 5, 8, 13, 11, 10, 6, 6, 0, 14, 15, 7, 12, 9, 3,
    10, 8, 4, 15, 0, 11, 1, 6, 13, 12, 7, 9, 3, 14, 5, 2,
    10, 7, 11, 5, 15, 0, 2, 14, 1, 8, 3, 13, 12, 6, 9, 4,
    13, 7, 15, 8, 5, 15, 6, 4, 11, 10, 14, 12, 9, 0, 2, 3,
    13, 12, 2, 7, 4, 1, 11, 14, 14, 8, 6, 15, 9, 5, 0, 3,
)

/** F13 每轮用到的 64 个常量（参考客户端里是独立的表，不是标准 MD5 的 T 表）。 */
internal val PASSPORT_MD5_CONSTANTS = intArrayOf(
    0xa7aefe20.toInt(), 0x7149f1d6.toInt(), 0x47e4ca07.toInt(), 0xe9b58f67.toInt(), 0x93b924de.toInt(), 0xc614d0f5.toInt(), 0x38afe0ef.toInt(), 0xb2bbad73.toInt(),
    0xe24444c3.toInt(), 0x9d3aec9b.toInt(), 0xdf7b37e4.toInt(), 0xd8b16d40.toInt(), 0xf8ac31b8.toInt(), 0x76b9a90b.toInt(), 0x31d833ee.toInt(), 0x953fce64.toInt(),
    0x353595a4.toInt(), 0x4609c13b.toInt(), 0x36925008.toInt(), 0x8c6d0925.toInt(), 0x5df5c177.toInt(), 0x1cfbf52b.toInt(), 0x8a4fa7f0.toInt(), 0x114ca35e.toInt(),
    0x8193f984.toInt(), 0x7a7a8733.toInt(), 0x316ab4d5.toInt(), 0x3c20cfc9.toInt(), 0xa6d84453.toInt(), 0x3a18500c.toInt(), 0x798ec47a.toInt(), 0x97a76b28.toInt(),
    0x66c4ff96.toInt(), 0x51716443.toInt(), 0x0dd2fc3b.toInt(), 0xb5696da7.toInt(), 0xbbeb3ac5.toInt(), 0x5c53d204.toInt(), 0xd32608ce.toInt(), 0x7279b9ec.toInt(),
    0xf4188ecf.toInt(), 0xf7d793db.toInt(), 0x332cc491.toInt(), 0xab76ae15.toInt(), 0x9bebe727.toInt(), 0x18a01384.toInt(), 0x5be9f8a7.toInt(), 0x5f90a754.toInt(),
    0x39b663c0.toInt(), 0x36673c83.toInt(), 0x7c92f514.toInt(), 0x9d7d94d7.toInt(), 0xe2e8d9aa.toInt(), 0x5f7e9ea9.toInt(), 0x7abd4551.toInt(), 0x569e05da.toInt(),
    0x40a25632.toInt(), 0x3df5a9a5.toInt(), 0xbab37d80.toInt(), 0x454286dc.toInt(), 0x3f5d4e78.toInt(), 0x03d7b75d.toInt(), 0xb1fe4af7.toInt(), 0xa5ab26a3.toInt(),
)
