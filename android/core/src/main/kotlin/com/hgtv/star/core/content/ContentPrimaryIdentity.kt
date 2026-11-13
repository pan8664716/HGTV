// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 当前唯一的主身份快照。
 *
 * 未登录时是主设备；登录后如果账号设备与主设备不同，以账号设备为准。
 * profile / Cookie / token 必须从同一份快照读取，不能逐项回落到另一套身份。
 */
internal data class ContentPrimaryIdentity(
    val account: Boolean,
    val profile: Map<String,String>,
    val cookie: String,
    val token: String,
)

internal fun ContentRepository.primaryIdentity(): ContentPrimaryIdentity {
    return primaryIdentityImpl()
}

internal fun ContentRepository.recommendationValues(identity: ContentPrimaryIdentity): LinkedHashMap<String,String> =
    recommendationValuesImpl(identity)
