// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索失败时的处置顺序：**先换设备重搜，再降级官网**。
 *
 * 为什么顺序不能反：App 搜索空响应最常见的原因就是「这台设备在服务端没注册过」。
 * 直接退到官网只是绕开了故障——设备仍然是坏的（播放照样 `101000`），而且异常根本
 * 传不到界面层，电视端那套 `healDeviceIdentity` 成了死代码（2026-11 现场确认）。
 *
 * 这一组用例钉住三件事：
 *
 * 1. 被拒之后**确实重搜了一次**（不是直接降级）；
 * 2. 自愈失败才降级；
 * 3. 自愈只做一次，不会在两次失败之间来回换设备。
 */
class SearchDeviceHealTest {

    /** App 搜索接口：这里一律回空响应体，就是「设备没注册」那一幕（HTTP 200 + 0 字节）。 */
    private val appSearchPath = "/reading/bookapi/search/tab/v"

    private var appCalls = 0
    private var webCalls = 0

    private fun repository() = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request: Request = chain.request()
            val body = when {
                request.url.encodedPath == appSearchPath -> { appCalls++; "" }
                request.url.host.contains("hongguoduanju.com") -> { webCalls++; "" }
                else -> ""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody(null)).build()
        }.build(),
        { System.currentTimeMillis() }, 0L, 0L,
    )

    @After
    fun tearDown() {
        ContentRepository.deviceHeal = null
    }

    /** 被拒 → 自愈 → **重搜一次**；重搜还是空才降级官网。 */
    @Test
    fun healsAndRetriesBeforeFallingBackToWeb() {
        var callsAtHeal = -1
        ContentRepository.deviceHeal = { _, reason ->
            callsAtHeal = appCalls
            assertEquals("搜索被拒", reason)
            true
        }
        val repo = repository().apply { deviceIdentity = mapOf("device_id" to "1", "iid" to "2") }
        runCatching { repo.search("季", 1) }

        assertTrue("被拒时必须先自愈一次", callsAtHeal >= 1)
        assertTrue("自愈之后必须重搜一次（实际 app=$appCalls, 自愈时=$callsAtHeal）", appCalls > callsAtHeal)
        assertTrue("重搜仍失败才降级官网", webCalls > 0)
    }

    /** 自愈失败（预算用尽 / 注册失败）：不重搜，直接降级官网。 */
    @Test
    fun fallsBackToWebWhenHealFails() {
        var callsAtHeal = -1
        var healCalls = 0
        ContentRepository.deviceHeal = { _, _ ->
            healCalls++
            callsAtHeal = appCalls
            false
        }
        val repo = repository().apply { deviceIdentity = mapOf("device_id" to "1", "iid" to "2") }
        runCatching { repo.search("季", 1) }

        assertEquals("自愈只尝试一次", 1, healCalls)
        assertEquals("自愈没成功就不该再打一次 App 搜索", callsAtHeal, appCalls)
        assertTrue("应当降级官网", webCalls > 0)
    }

    /** 两次都失败也只换一次设备：换设备要发注册请求，不能变成循环。 */
    @Test
    fun healsOnlyOncePerSearch() {
        var healCalls = 0
        ContentRepository.deviceHeal = { _, _ -> healCalls++; true }
        val repo = repository().apply { deviceIdentity = mapOf("device_id" to "1", "iid" to "2") }
        runCatching { repo.search("季", 1) }

        assertEquals("一次搜索里只允许换一次设备", 1, healCalls)
    }

    /** 官网搜索（用户自己选的）不触发换设备：那是另一条链路。 */
    @Test
    fun webSearchNeverHeals() {
        var healCalls = 0
        ContentRepository.deviceHeal = { _, _ -> healCalls++; true }
        val repo = repository()
        runCatching { repo.search("季", 1, SearchSource.WEB) }

        assertEquals(0, healCalls)
        assertEquals("官网搜索不该打 App 接口", 0, appCalls)
    }
}
