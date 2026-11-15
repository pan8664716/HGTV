// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

internal fun ContentRepository.writeValuesImpl(): LinkedHashMap<String,String> =
    accountValuesImpl("73932","7.3.9.32")

internal fun ContentRepository.appValuesImpl(): LinkedHashMap<String,String> =
    deviceValuesImpl("72232","7.2.2.32")

/**
 * 弹幕固定使用官方内置设备档案。
 *
 * 热弹幕接口会按设备档案返回不同的“热评样本”：同一集、同一时间，电视的
 * `S905L3B` 只回 25 条，模拟器的手机档案回 738 条。弹幕是公共内容，不应由
 * 本机设备型号决定，因此这里固定使用 [VendorConstants.device]，让电视、手机和
 * 模拟器请求同一份样本；账号态也不覆盖它。
 */
internal fun ContentRepository.danmakuValuesImpl(): LinkedHashMap<String,String> =
    buildValues(VendorConstants.device,"72232","7.2.2.32")

internal fun ContentRepository.searchValuesImpl(): LinkedHashMap<String,String> {
    val values=deviceValuesImpl(searchVersionCodeValue,searchVersionNameValue)
    values.putAll(searchFeedContextValue)
    return values
}

internal fun ContentRepository.searchRequestImpl(
    values: LinkedHashMap<String,String>,
): Signer.Request =
    signer.sign(
        "https://${accountHost()}/$searchPathValue",
        values,
        method="GET",
        deviceOverride=values,
    )

internal fun ContentRepository.accountAppValuesImpl(): LinkedHashMap<String,String> =
    accountValuesImpl("72232","7.2.2.32")

internal fun ContentRepository.deviceValuesImpl(
    versionCode: String,
    versionName: String,
): LinkedHashMap<String,String> =
    buildValues(primaryProfile(), versionCode, versionName)

internal fun ContentRepository.accountValuesImpl(
    versionCode: String,
    versionName: String,
): LinkedHashMap<String,String> =
    buildValues(primaryProfile(), versionCode, versionName)

private fun ContentRepository.buildValues(
    profile: Map<String,String>,
    versionCode: String,
    versionName: String,
): LinkedHashMap<String,String> {
    val values=LinkedHashMap(profile)
    values.putAll(protocolValuesValue)
    for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
        values[key]=versionCode
    }
    values["version_name"]=versionName
    return values
}

internal fun ContentRepository.recommendationValuesImpl(
    identity: ContentPrimaryIdentity,
): LinkedHashMap<String,String> =
    LinkedHashMap(identity.profile).apply {
        putAll(protocolValuesValue)
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
            this[key]=CATEGORY_VERSION_CODE
        }
        this["version_name"]=CATEGORY_VERSION_NAME
    }
