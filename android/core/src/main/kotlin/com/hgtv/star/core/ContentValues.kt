// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

internal fun ContentRepository.writeValuesImpl(): LinkedHashMap<String,String> =
    accountValuesImpl("73932","7.3.9.32")

internal fun ContentRepository.appValuesImpl(): LinkedHashMap<String,String> =
    deviceValuesImpl("72232","7.2.2.32")

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
    identity: ContentRepository.PrimaryIdentity,
): LinkedHashMap<String,String> =
    LinkedHashMap(identity.profile).apply {
        putAll(protocolValuesValue)
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
            this[key]=CATEGORY_VERSION_CODE
        }
        this["version_name"]=CATEGORY_VERSION_NAME
    }
