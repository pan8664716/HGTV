// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

internal fun ContentRepository.deviceProfileImpl(): Map<String,String> =
    LinkedHashMap(VendorConstants.device).apply {
        putAll(registerProfile)
        putAll(deviceIdentity)
    }

internal fun ContentRepository.accountDeviceProfileImpl(
    account: AccountCredentials,
): Map<String,String> =
    LinkedHashMap(VendorConstants.device).apply {
        account.device.forEach { (key,value) ->
            if(value.isNotBlank()) this[key]=value
        }
    }

internal fun ContentRepository.relatedHostsImpl(): List<String> =
    TncConfigStore.orderedApiHosts()

internal fun ContentRepository.commentHostsImpl(): List<String> =
    TncConfigStore.orderedApiHosts()
