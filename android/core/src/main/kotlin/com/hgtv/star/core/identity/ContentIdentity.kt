// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import com.hgtv.star.core.session.CookieRecord
import com.hgtv.star.core.session.SessionBridge

internal fun ContentRepository.primaryIdentityImpl(): ContentPrimaryIdentity {
    val account=credentials?.takeIf {
        it.configured &&
            it.device["device_id"].orEmpty().isNotBlank() &&
            (
                it.device["iid"].orEmpty().isNotBlank() ||
                    it.device["install_id"].orEmpty().isNotBlank()
                )
    }
        ?: return ContentPrimaryIdentity(
            account=false,
            profile=deviceProfileImpl(),
            cookie=CookieRecord.mapToHeader(registrationCookies),
            token="",
        )
    val live=SessionBridge.state
    val liveCookie=live?.cookieHeader.orEmpty()
    val liveSid=CookieRecord.value(liveCookie,"sessionid")
    val accountSid=CookieRecord.value(account.cookie,"sessionid")
    val sameSession=liveSid.isNotBlank() && liveSid==accountSid
    val cookie=CookieRecord.headerToMap(account.cookie).toMutableMap().apply {
        if(sameSession) putAll(CookieRecord.headerToMap(liveCookie))
    }
    val accountIid=account.device["iid"].orEmpty()
        .ifBlank { account.device["install_id"].orEmpty() }
    if(accountIid.isNotBlank() && cookie["install_id"].isNullOrBlank()) {
        cookie["install_id"]=accountIid
    }
    return ContentPrimaryIdentity(
        account=true,
        profile=accountDeviceProfileImpl(account),
        cookie=CookieRecord.mapToHeader(cookie),
        token=if(sameSession) live?.token.orEmpty().ifBlank { account.token } else account.token,
    )
}

internal fun ContentRepository.primaryProfileImpl(): Map<String,String> =
    primaryIdentityImpl().profile

internal fun ContentRepository.primaryCookieHeaderImpl(): String =
    primaryIdentityImpl().cookie

internal fun Signer.Request.withSessionImpl(repository: ContentRepository): Signer.Request {
    val identity=repository.primaryIdentityImpl()
    val extra=linkedMapOf<String,String>()
    if(identity.cookie.isNotBlank()) extra["Cookie"]=identity.cookie
    if(identity.token.isNotBlank()) extra["x-tt-token"]=identity.token
    if(identity.account) extra["X-HGTV-Identity"]="account"
    return if(extra.isEmpty()) this else copy(headers=headers+extra)
}

internal fun Signer.Request.withAccountSessionImpl(repository: ContentRepository): Signer.Request {
    val identity=repository.primaryIdentityImpl()
    if(!identity.account) throw SignInRequiredException()
    val extra=linkedMapOf<String,String>()
    if(identity.token.isNotBlank()) extra["x-tt-token"]=identity.token
    if(identity.cookie.isNotBlank()) extra["Cookie"]=identity.cookie
    extra["X-HGTV-Identity"]="account"
    return copy(headers=headers+extra)
}
