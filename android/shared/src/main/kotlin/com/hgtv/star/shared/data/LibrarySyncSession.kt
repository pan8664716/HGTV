// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

/**
 * 局域网全量同步时允许跨设备的会话键白名单。
 *
 * 这里刻意只收身份、登录态和它们运行所需的参数；第三方协议同意、推荐游标、
 * 本机删除墓碑等设备现场不随同步迁移。
 */
internal object LibrarySyncSessionKeys {
    val LONG = setOf("account-expires", "last-beat-at")

    val LOGIN = setOf(
        "account-cookie",
        "account-device",
        "account-expires",
        "x-tt-token",
        "session-token",
        "ts-sign",
        "first-beat",
        "last-beat-at",
    )

    val DEVICE = setOf(
        "registered-device-id",
        "registered-install-id",
        "registered-device-token",
        "registered-klink-egdi",
        "registered-profile-stamp",
        "device-profile",
        "device-seed",
        "device-profile-seed",
        "device-profile-pending",
        "device-profile-pending-seed",
        "device-profile-pending-stamp",
        "device-profile-nonce",
        "registered-cookies",
        "identity-layout-v2",
        "legacy-registered-device-id",
        "legacy-registered-install-id",
        "legacy-registered-device-token",
        "legacy-registered-klink-egdi",
        "legacy-registered-profile-stamp",
        "legacy-registered-cookies",
        "legacy-device-profile",
    )

    val ALL = LOGIN + DEVICE
    private const val MAX_VALUE_BYTES = 128 * 1024
    private const val MAX_TOTAL_BYTES = 512 * 1024

    fun validate(values: Map<String, String>) {
        require(values.keys.all { it in ALL }) { "同步包含不支持的会话字段" }
        var total = 0
        values.forEach { (key, value) ->
            val bytes = value.toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_VALUE_BYTES && value.none(Char::isISOControl)) { "同步会话字段无效" }
            if (key in LONG) {
                require(value.toLongOrNull()?.let { it in 0..4_102_444_800_000L } == true) {
                    "同步时间字段无效"
                }
            }
            total += bytes + key.toByteArray(Charsets.UTF_8).size
        }
        require(total <= MAX_TOTAL_BYTES) { "同步会话数据过大" }
    }
}
