// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import com.hgtv.star.core.session.SessionBridge
import com.hgtv.star.core.session.SessionInterceptor
import com.hgtv.star.core.session.SessionState
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 注册请求的「匿名」开关。
 *
 * 为什么必须有：注册主机是 `log.snssdk.com`，落在拦截器的会话 host 名单里，默认会被补上
 * `Cookie: install_id=…; sessionid=…` 与 `X-Tt-Token`。实测（2026-11）服务端**按这串 Cookie
 * 认设备**：`cdid` / `openudid` / `clientudid` 全换成新的，照样回 `new_user:0` 与原 `device_id`。
 * 于是「换一台设备」的注册在已登录 / 已有注册 Cookie 的机器上永远无效。
 *
 * 这一组用例钉住：打了标记头的那次请求**一个身份都不带**，标记头本身也不发给服务端。
 */
class SessionInterceptorTest {

    private class FakeState(
        override val token: String = "tok-123",
        override val cookieHeader: String = "sessionid=s-1; sid_tt=s-1",
        override val deviceCookies: Map<String,String> = mapOf("install_id" to "111","ttreq" to "t-1"),
        override val storeRegion: String = "cn",
        override val deviceProfile: Map<String,String> = emptyMap(),
        override val expiresAtMs: Long = 0,
        override var firstBeat: Boolean = false,
        override var lastBeatAtMs: Long = 0,
    ) : SessionState {
        override fun saveToken(token: String, tsSign: String): Boolean = false
        override fun mergeSetCookie(setCookies: List<String>): Boolean = false
        override fun saveDeviceCookies(cookies: Map<String,String>) = Unit
        override fun clearSession() = Unit
    }

    /** 最后一道拦截器：不真的发请求，把请求头原样交回来。 */
    private class Capture {
        var cookie: String? = null
        var token: String? = null
        var anonymous: String? = null
    }

    private lateinit var capture: Capture
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        SessionBridge.state = FakeState()
        capture = Capture()
        client = OkHttpClient.Builder()
            .addInterceptor(SessionInterceptor())
            .addInterceptor { chain ->
                val request = chain.request()
                capture.cookie = request.header("Cookie")
                capture.token = request.header("X-Tt-Token")
                capture.anonymous = request.header("X-HGTV-Anonymous")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @After
    fun tearDown() {
        SessionBridge.state = null
    }

    private fun call(anonymous: Boolean) {
        val builder = Request.Builder().url("https://log.snssdk.com/service/2/device_register/")
        if(anonymous) builder.header("X-HGTV-Anonymous","1")
        client.newCall(builder.build()).execute().close()
    }

    /** 普通注册：设备 Cookie 与会话 Cookie 都补上（平台按它认设备，这是「同一台设备」的连续性）。 */
    @Test
    fun normalRegistrationCarriesDeviceAndSessionCookies() {
        call(anonymous = false)
        val cookie = capture.cookie.orEmpty()
        assertTrue("install_id 必须带上：$cookie", cookie.contains("install_id=111"))
        assertTrue("sessionid 必须带上：$cookie", cookie.contains("sessionid=s-1"))
        assertEquals("tok-123", capture.token)
    }

    /** 换设备：一个身份都不带，标记头也不发出去。 */
    @Test
    fun anonymousRegistrationCarriesNoIdentity() {
        call(anonymous = true)
        assertNull("不能带 Cookie", capture.cookie)
        assertNull("不能带 X-Tt-Token", capture.token)
        assertNull("标记头只给拦截器看，不发给服务端", capture.anonymous)
    }
}
