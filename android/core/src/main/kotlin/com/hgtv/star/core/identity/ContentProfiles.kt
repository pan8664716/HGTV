// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

internal fun ContentRepository.deviceProfileImpl(): Map<String,String> =
    if(tvDeviceProfile.isNotEmpty()) {
        LinkedHashMap(tvDeviceProfile).apply { putAll(deviceIdentity) }
    } else {
        LinkedHashMap(VendorConstants.device).apply {
            putAll(registerProfile)
            putAll(deviceIdentity)
        }
    }

internal fun ContentRepository.accountDeviceProfileImpl(
    account: AccountCredentials,
): Map<String,String> {
    if(tvDeviceProfile.isNotEmpty()) {
        return LinkedHashMap(tvDeviceProfile).apply {
            account.device.forEach { (key,value) ->
                if(key in TV_ACCOUNT_IDENTITY_KEYS && value.isNotBlank()) this[key]=value
            }
        }
    }
    return LinkedHashMap(VendorConstants.device).apply {
        account.device.forEach { (key,value) ->
            if(value.isNotBlank()) this[key]=value
        }
    }
}

private val TV_ACCOUNT_IDENTITY_KEYS=setOf(
    "device_id","iid","install_id","device_token","klink_egdi",
)

internal fun ContentRepository.relatedHostsImpl(): List<String> =
    TncConfigStore.orderedApiHosts()

internal fun ContentRepository.commentHostsImpl(): List<String> =
    TncConfigStore.orderedApiHosts()
