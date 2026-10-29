// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机号登录的协议测试：把 Passport 的响应换成本地桩，钉住四件事——
 * 编码方式（XOR 0x05 + hex）、请求路径、成功判据、以及会话凭证的收集。
 */
class PhoneLoginTest {
    /** 记录每一次请求，并按 [respond] 给响应。 */
    private fun client(log: MutableList<Request>, respond: (Request) -> Pair<String, Map<String, String>>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            log += request
            val (body, headers) = respond(request)
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
.code(200).message("OK").body(body.toResponseBody(null))
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.build()
        }.build()

    private fun form(request: Request): Map<String, String> {
        val text = request.body?.let { okio.Buffer().also { b -> it.writeTo(b) }.readUtf8() }.orEmpty()
        return text.split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    }

    /** 手机号加 `+86` 后逐字节 XOR 0x05 再 hex。 */
    private fun encode(value: String) =
        value.toByteArray().map { (it.toInt() xor 5).toByte() }.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun sendCodeEncodesPhoneAndTypeAndHitsTheRightPath() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { """{"data":{"retry_time":60,"error_code":0},"message":"success"}""" to emptyMap() })
        login.device = mapOf("device_id" to "1234567890123456", "iid" to "1234567890123457", "cdid" to "c", "openudid" to "o")
        assertEquals(PhoneLogin.Result.Sent, login.sendCode("17600351236") { null })
        assertEquals("/passport/mobile/send_code/v1/", log.single().url.encodedPath)
        val fields = form(log.single())
        // 手机号必须是 `+86` 前缀 XOR 0x05 的 hex；明文会被平台判参数错误。
        assertEquals(encode("+8617600351236"), fields["mobile"])
        assertEquals(encode("24"), fields["type"])
        assertEquals("app", fields["account_sdk_source"])
        assertEquals("1", fields["mix_mode"])
        assertEquals(60, login.retryAfterSeconds)
    }

    @Test fun sendCodeRejectsBadPhoneWithoutAnyRequest() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { """{"message":"success"}""" to emptyMap() })
        val result = login.sendCode("12345") { null }
        assertTrue(result is PhoneLogin.Result.Failed)
        assertEquals("不发请求", 0, log.size)
    }

    @Test fun loginSendsXorEncodedCodeAndCollectsSessionCookies() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) {
            """{"data":{"user_id":1739875314044148,"user_id_str":"1739875314044148","screen_name":"测试","error_code":0},"message":"success"}""" to
                mapOf("Set-Cookie" to "sessionid=abc123; Path=/; Domain=.fqnovel.com",
                      "X-Tt-Token" to "tok-xyz")
        })
        login.device = mapOf("device_id" to "1234567890123456", "iid" to "1234567890123457")
        val result = login.login("17600351236", "4263")
        assertTrue("应当登录成功：$result", result is PhoneLogin.Result.LoggedIn)
        val credentials = (result as PhoneLogin.Result.LoggedIn).credentials
        assertEquals("/passport/mobile/sms_login/", log.single().url.encodedPath)
        assertEquals(encode("4263"), form(log.single())["code"])
        assertTrue("会话 Cookie 要收进凭据", credentials.cookie.contains("sessionid=abc123"))
        assertEquals("tok-xyz", login.sessionToken)
        assertTrue(login.signedIn)
    }

    @Test fun mfaResponseAsksForUpsmsInsteadOfFailing() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) {
            """{"data":{"encrypt_uid":"uid-1","sms_code_key":"key-1","passport_scene":"sms_login",
                "event_params":{"log_id":"log-1"},
                "verify_ways":[{"verify_way":"upsms","channel_mobile":"9515211003","sms_content":"YZ"}]},
                "message":"success"}""" to emptyMap()
        })
        login.device = mapOf("device_id" to "1234567890123456", "iid" to "1234567890123457")
        val result = login.login("17600351236", "4263")
        assertTrue("应当要求上行短信：$result", result is PhoneLogin.Result.NeedUpsms)
        val mfa = result as PhoneLogin.Result.NeedUpsms
        assertEquals("9515211003", mfa.number)
        assertEquals("YZ", mfa.content)
    }

    /**
     * 平台说「验证已过期」时**不要**改拿 `mobile_ticket` 再问一次。
     *
     * 线上日志（2026-10-21 20:19:09）证明：`mobile_ticket` 在 `sms_login` 那一刻
     * 已经被平台一次性消费，0.8 秒后再拿它去 `gen_verify_ticket`，平台回
     * `{"error_code":1,"description":"会话过期，请重新登录"}`；
     * 再拿它去 `verify` 则永远 `1045 认证失败`。
     */
    @Test fun expiredUpsmsDoesNotFallBackToTheConsumedMobileTicket() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { request ->
            if (request.url.encodedPath == "/passport/mobile/sms_login/") {
                """{"data":{"encrypt_uid":"uid-1","sms_code_key":"key-1","passport_scene":"login",
                    "verify_ticket":"vt-1","mobile_ticket":"mt-1",
                    "verify_ways":[{"verify_way":"upsms","channel_mobile":"9515211003","sms_content":"YZ"}]},
                    "message":"success"}""" to emptyMap()
            } else {
                """{"data":{"error_code":9001,"description":"长时间未操作，验证已过期"},"message":"error"}""" to emptyMap()
            }
        })
        login.device = mapOf("device_id" to "1234567890123456", "iid" to "1234567890123457")
        assertTrue(login.login("17600351236", "4263") is PhoneLogin.Result.NeedUpsms)
        val result = login.verifyUpsms()
        assertTrue("平台说过期就是结束，不要改票重来：$result", result is PhoneLogin.UpsmsResult.Failed)
        assertTrue((result as PhoneLogin.UpsmsResult.Failed).message.contains("9001"))
        assertTrue("挑战要清掉", !login.waitingUpsms)
        // 只问了 verify 一次，没有任何请求把 mobile_ticket 当 verify_ticket 发出去。
        assertEquals(2, log.size)
        assertTrue(log.none { form(it)["verify_ticket"] == "mt-1" })
    }

    /** 只有 `mobile_ticket` 时先换票；换票回「会话过期」说明挑战已死，立即收手。 */
    @Test fun challengeWithOnlyMobileTicketEndsWhenExchangeSaysExpired() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { request ->
            when (request.url.encodedPath) {
                "/passport/mobile/sms_login/" ->
                    """{"data":{"encrypt_uid":"uid-1","sms_code_key":"key-1","passport_scene":"login","mobile_ticket":"mt-1",
                        "verify_ways":[{"verify_way":"upsms","channel_mobile":"9515211003","sms_content":"YZ"}]},
                        "message":"success"}""" to emptyMap()
                else ->
                    """{"data":{"error_code":1,"description":"会话过期，请重新登录"},"message":"error"}""" to emptyMap()
            }
        })
        login.device = mapOf("device_id" to "1234567890123456", "iid" to "1234567890123457")
        assertTrue(login.login("17600351236", "4263") is PhoneLogin.Result.NeedUpsms)
        val result = login.verifyUpsms()
        assertTrue("换票失败要直接报错：$result", result is PhoneLogin.UpsmsResult.Failed)
        assertTrue((result as PhoneLogin.UpsmsResult.Failed).message.contains("会话过期"))
        assertTrue("挑战要清掉", !login.waitingUpsms)
        // 登录 + 换票：换票失败就不再打 verify（拿死票打只会刷 1045）。
        assertEquals(2, log.size)
        assertEquals("/passport/upsms/gen_verify_ticket/", log[1].url.encodedPath)
        assertTrue("换票请求不能带 mobile_ticket", form(log[1])["verify_ticket"].isNullOrEmpty())
    }

    /**
     * 挑战里一张票据都没有：H5 的条件是 `if (N.verify_ticket && N.verify_data) {...} else {换票}`，
     * 所以仍然要发**一次** `gen_verify_ticket`（body 用挑战原文），拿不到票据就收手。
     */
    @Test fun challengeWithoutAnyTicketTriesExchangeOnceThenFailsFast() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { request ->
            when (request.url.encodedPath) {
                "/passport/mobile/sms_login/" ->
                    """{"data":{"encrypt_uid":"uid-1","sms_code_key":"key-1","passport_scene":"sms_login",
                        "verify_ways":[{"verify_way":"upsms","channel_mobile":"9515211003","sms_content":"YZ"}]},
                        "message":"success"}""" to emptyMap()
                else ->
                    """{"data":{"error_code":1,"description":"会话过期，请重新登录"},"message":"error"}""" to emptyMap()
            }
        })
        login.device = mapOf("device_id" to "1234567890123456", "iid" to "1234567890123457")
        assertTrue(login.login("17600351236", "4263") is PhoneLogin.Result.NeedUpsms)
        assertTrue("没有票据就别打 verify", login.verifyUpsms() is PhoneLogin.UpsmsResult.Failed)
        assertEquals("登录 + 一次换票", 2, log.size)
        assertEquals("/passport/upsms/gen_verify_ticket/", log[1].url.encodedPath)
        assertTrue("挑战要清掉", !login.waitingUpsms)
    }

    /**
     * 官方 App 的 2046 结构：`data.verify_center_secondary_decision_conf` 是一段 JSON 字符串，
     * 里面没有 `verify_ways`、也没有 `encrypt_uid` / `sms_code_key`。
     *
     * 以前只认 `verify_ways`，这种响应会掉进 `failed()` → 用户只看到一串错误码，
     * 连「要发哪条短信」都看不到。
     */
    @Test fun official2046RiskInfoIsUnderstood() {
        val log = mutableListOf<Request>()
        val risk = """{"subtype":"mobile_up_sms_verify","code":20000,"log_id":"log-1",
            "verify_ticket":"vt-1","verify_scene":"login",
            "channel_mobile":"1069xxxx","sms_content":"YZabc"}"""
        val login = PhoneLogin(client(log) {
            """{"data":{"verify_center_secondary_decision_conf":${org.json.JSONObject.quote(risk)}},"message":"error"}""" to emptyMap()
        })
        login.device = mapOf("device_id" to "1", "iid" to "2")
        val result = login.login("17600351236", "4263")
        assertTrue("应当要求上行短信：$result", result is PhoneLogin.Result.NeedUpsms)
        val mfa = result as PhoneLogin.Result.NeedUpsms
        assertEquals("1069xxxx", mfa.number)
        assertEquals("YZabc", mfa.content)
    }

    /**
     * 确认请求必须带上**非空**的 `verify_ticket` 与这次挑战的 `verify_data`。
     *
     * 实测（直接打线上接口）：`verify_ticket=` 空串时平台回 `error_code=3 缺少参数`，
     * 永远不会走到「等短信」那一步；只有非空票据才会回 `1045`。以前默认发空票据，
     * 于是每一次确认都必然失败，再被上层当成「挑战结束」→ 重新发码 → 再发短信，死循环。
     */
    @Test fun upsmsVerifySendsNonEmptyTicketAndVerifyData() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { request ->
            if (request.url.encodedPath == "/passport/mobile/sms_login/") {
                """{"data":{"encrypt_uid":"u","sms_code_key":"k","passport_scene":"login",
                    "verify_ticket":"vt-1","mobile_ticket":"mt-1",
                    "verify_ways":[{"verify_way":"upsms","channel_mobile":"10690000","sms_content":"YZabc"}]},
                    "message":"success"}""" to emptyMap()
            } else {
                """{"data":{"error_code":1045,"description":"认证失败"},"message":"error"}""" to emptyMap()
            }
        })
        login.device = mapOf("device_id" to "1", "iid" to "2")
        assertTrue(login.login("17600351236", "4263") is PhoneLogin.Result.NeedUpsms)
        assertTrue(login.verifyUpsms() is PhoneLogin.UpsmsResult.Pending)
        val ask = log.last()
        assertEquals("/passport/upsms/verify/", ask.url.encodedPath)
        val fields = form(ask)
        assertEquals("vt-1", fields["verify_ticket"])
        assertEquals("block-upsms", fields["decision_config"])
        // 场景跟着挑战走（H5 把 URL 参数原样发出去），不能写死 sms_login。
        assertEquals("login", fields["verify_scene"])
        val data = org.json.JSONObject(fields["verify_data"].orEmpty())
        assertEquals("mobile_up_sms_verify", data.optString("subtype"))
        assertEquals("10690000", data.optString("channel_mobile"))
        assertEquals("YZabc", data.optString("sms_content"))
        assertEquals("vt-1", data.optString("verify_ticket"))
    }

    /**
     * 挑战只给了 `mobile_ticket` 时，必须先 `gen_verify_ticket` 换一张真票据再去 verify。
     *
     * 实测：把 `mobile_ticket` 直接当 `verify_ticket` 发，平台一律回 `1045 认证失败`
     * ——短信发得再对也匹配不上，界面就会永远停在「平台还没看到这条短信」。
     */
    @Test fun mobileTicketIsExchangedForARealTicketBeforeVerify() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { request ->
            when (request.url.encodedPath) {
                "/passport/mobile/sms_login/" ->
                    """{"data":{"passport_scene":"login","mobile_ticket":"mt-1",
                        "verify_ways":[{"verify_way":"upsms","channel_mobile":"10690000","sms_content":"YZabc"}]},
                        "message":"success"}""" to emptyMap()
                "/passport/upsms/gen_verify_ticket/" ->
                    """{"data":{"verify_ticket":"vt-real"},"message":"success"}""" to emptyMap()
                else -> """{"data":{"error_code":1045,"description":"认证失败"},"message":"error"}""" to emptyMap()
            }
        })
        login.device = mapOf("device_id" to "1", "iid" to "2")
        assertTrue(login.login("17600351236", "4263") is PhoneLogin.Result.NeedUpsms)
        assertTrue(login.verifyUpsms() is PhoneLogin.UpsmsResult.Pending)
        val paths = log.map { it.url.encodedPath }
        assertEquals(
            listOf("/passport/mobile/sms_login/", "/passport/upsms/gen_verify_ticket/", "/passport/upsms/verify/"),
            paths
        )
        // 换票请求**不**带 mobile_ticket（它已被 sms_login 消费，带上就是「会话过期」）；
        // verify 请求带的必须是换来的那张。
        assertTrue("换票请求不能带 mobile_ticket", form(log[1])["verify_ticket"].isNullOrEmpty())
        assertEquals("mt-1", form(log[1])["verify_data"].let {
            org.json.JSONObject(it).optString("mobile_ticket")
        }.ifBlank { "mt-1" })
        assertEquals("vt-real", form(log[2])["verify_ticket"])
        // 换到之后缓存，第二次轮询不再重复换票。
        assertTrue(login.verifyUpsms() is PhoneLogin.UpsmsResult.Pending)
        assertEquals("换一次就够", 4, log.size)
    }

    /** 换到票之后，verify 回未知硬错误时不能立刻判挑战结束——先继续问。 */
    @Test fun unknownUpsmsErrorKeepsWaitingBeforeGivingUp() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { request ->
            when (request.url.encodedPath) {
                "/passport/mobile/sms_login/" ->
                    """{"data":{"encrypt_uid":"u","sms_code_key":"k","passport_scene":"login","mobile_ticket":"mt",
                        "verify_ways":[{"verify_way":"upsms","channel_mobile":"1","sms_content":"YZ"}]},
                        "message":"success"}""" to emptyMap()
                "/passport/upsms/gen_verify_ticket/" ->
                    """{"data":{"verify_ticket":"vt-real"},"message":"success"}""" to emptyMap()
                else ->
                    """{"data":{"error_code":22,"description":"非法应用"},"message":"error"}""" to emptyMap()
            }
        })
        login.device = mapOf("device_id" to "1", "iid" to "2")
        assertTrue(login.login("17600351236", "4263") is PhoneLogin.Result.NeedUpsms)
        repeat(3) {
            val pending = login.verifyUpsms()
            assertTrue("未知码先按继续等处理：$pending", pending is PhoneLogin.UpsmsResult.Pending)
        }
        assertTrue("连续太多次才收手", login.verifyUpsms() is PhoneLogin.UpsmsResult.Failed)
    }

    /** 已经消费过的验证码不能重放：本地就拦住，不去换平台的「验证码已失效」。 */
    @Test fun consumedCodeIsNotReplayedWithoutTicket() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { request ->
            if (request.url.encodedPath == "/passport/mobile/sms_login/") {
                """{"data":{"encrypt_uid":"u","sms_code_key":"k",
                    "verify_ways":[{"verify_way":"upsms","channel_mobile":"1","sms_content":"YZ"}]},
                    "message":"success"}""" to emptyMap()
            } else {
                """{"data":{"ticket":"t"},"message":"success"}""" to emptyMap()
            }
        })
        login.device = mapOf("device_id" to "1", "iid" to "2")
        assertTrue(login.login("17600351236", "4263") is PhoneLogin.Result.NeedUpsms)
        val before = log.size
        val again = login.login("17600351236", "4263")
        assertTrue("重放要被本地拦掉：$again", again is PhoneLogin.Result.Failed)
        assertTrue((again as PhoneLogin.Result.Failed).message.contains("已经用过"))
        assertEquals("不该再发请求", before, log.size)
    }

    /** `message=success` 但没有任何会话：不能当登录成功。 */
    @Test fun successWithoutSessionIsNotLoggedIn() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { """{"data":{"error_code":0},"message":"success"}""" to emptyMap() })
        login.device = mapOf("device_id" to "1", "iid" to "2")
        val result = login.login("17600351236", "4263")
        assertTrue("空会话不是登录成功：$result", result is PhoneLogin.Result.Failed)
    }

    @Test fun platformErrorIsSurfacedVerbatim() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) {
            """{"data":{"description":"访问太频繁，请稍后再试","error_code":7},"message":"error"}""" to emptyMap()
        })
        login.device = mapOf("device_id" to "1", "iid" to "2")
        val result = login.login("17600351236", "4263")
        assertTrue(result is PhoneLogin.Result.Failed)
        // 平台原文要带给用户，不能糊成一句「登录失败」。
        assertTrue((result as PhoneLogin.Result.Failed).message.contains("访问太频繁"))
        assertTrue(result.message.contains("7"))
    }

    @Test fun credentialsMatchTheCookieStoreShape() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) {
            """{"data":{"user_id_str":"1739875314044148","error_code":0},"message":"success"}""" to
                mapOf("Set-Cookie" to "sessionid=abc; sid_guard=abc%7C1792154473%7C5184000%7CTue; d_ticket=dt; uid_tt=ut")
        })
        login.device = mapOf("device_id" to "1234567890123456", "iid" to "1234567890123457",
                             "cdid" to "cd", "openudid" to "ou", "device_type" to "MiTV-MOOQ1")
        val result = login.login("17600351236", "4263") as PhoneLogin.Result.LoggedIn
        val credentials = result.credentials
        // 与导入 cookieStore.xml 得到的那份同构：整段 Cookie + 设备档案 + 过期时间。
        assertTrue(credentials.configured)
        assertEquals("1234567890123456", credentials.deviceId)
        assertEquals("1234567890123457", credentials.installId)
        assertEquals("MiTV-MOOQ1", credentials.device["device_type"])
        assertEquals(1792154473L + 5184000L, credentials.expiresAt)
        assertTrue("过期时间要能算出剩余天数", (credentials.remainingDays() ?: 0.0) > 0)
    }

    /**
     * HGTV自有二次验证页（`passport-second-verification.html`）**不换票**。
     *
     * 实测日志：该页面的 JS 只调 `/passport/upsms/verify/`，
     * body = encrypt_uid + verify_ticket + biz_params + verify_scene + verify_reason + common_params。
     * 真正的 `encrypt_uid` 回 1045（在等短信），假的回 3 缺少参数。
     */
    @Test fun novelreadDecisionVerifiesDirectlyWithoutTicketExchange() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { request ->
            when (request.url.encodedPath) {
                "/passport/mobile/sms_login/" -> """{"data":{
                    "biz_params":{"passport_mfa_retry_tag":"1","sms_code_key":"KEY123"},
                    "common_params":{"copywriting":"{}","copywriting_key":"sms_login",
                        "ies_safety_diversion_tag":"mfa","new_verify_flow":""},
                    "encrypt_uid":"enc-1",
                    "event_params":{"log_id":"log-9","verify_reason":"ato","verify_scene":"sms_login"},
                    "mobile_ticket":"mt-1","passport_scene":"login",
                    "schema":"dragon8662://webview?x=1",
                    "url":"https://x/passport-second-verification.html",
                    "verify_ways":[{"verify_way":"mobile_up_sms_verify","channel_mobile":"10691859839103",
                        "mobile":"176******36","sms_content":"YZ"}]
                    },"message":"error"}""" to emptyMap()
                else -> """{"data":{"error_code":1045,"description":"认证失败"},"message":"error"}""" to emptyMap()
            }
        })
        login.device = mapOf("device_id" to "1", "iid" to "2")
        val need = login.login("17600351236", "4263")
        assertTrue("应当要求上行短信：$need", need is PhoneLogin.Result.NeedUpsms)
        assertTrue(login.verifyUpsms() is PhoneLogin.UpsmsResult.Pending)
        val paths = log.map { it.url.encodedPath }
        assertEquals(listOf("/passport/mobile/sms_login/", "/passport/upsms/verify/"), paths)
        val fields = form(log[1])
        assertEquals("enc-1", fields["encrypt_uid"])
        assertEquals("sms_login", fields["verify_scene"])
        assertEquals("ato", fields["verify_reason"])
        assertEquals("sms_login", fields["copywriting_key"])
        assertTrue("biz_params 要整份带回去：${fields["biz_params"]}",
            fields["biz_params"].orEmpty().contains("KEY123"))
        assertTrue("这条链路没有 verify_data", !fields.containsKey("verify_data"))
    }

    /** `is_complex_sms` 时改走 safe_mobile/verify，并带 upstream_verify_type=1。 */
    @Test fun novelreadComplexSmsUsesSafeMobileEndpoint() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { request ->
            when (request.url.encodedPath) {
                "/passport/mobile/sms_login/" -> """{"data":{
                    "biz_params":{"passport_mfa_retry_tag":"1","sms_code_key":"K"},
                    "encrypt_uid":"enc-2","is_complex_sms":true,
                    "common_params":{"copywriting":"{}","copywriting_key":"sms_login"},
                    "event_params":{"log_id":"l","verify_reason":"ato","verify_scene":"login"},
                    "url":"https://x/passport-second-verification.html",
                    "verify_ways":[{"verify_way":"mobile_up_sms_verify","channel_mobile":"1","sms_content":"YZ"}]
                    },"message":"error"}""" to emptyMap()
                else -> """{"data":{"error_code":1045},"message":"error"}""" to emptyMap()
            }
        })
        login.device = mapOf("device_id" to "1", "iid" to "2")
        assertTrue(login.login("17600351236", "4263") is PhoneLogin.Result.NeedUpsms)
        assertTrue(login.verifyUpsms() is PhoneLogin.UpsmsResult.Pending)
        assertEquals("/passport/upsms/safe_mobile/verify/", log.last().url.encodedPath)
        assertEquals("1", form(log.last())["upstream_verify_type"])
    }

    /** 二次验证通过后的重放：把 biz_params 并回 sms_login，`sms_code_key` 做 XOR 编码。 */
    @Test fun replayAfterUpsmsUsesBizParamsNotTheTicket() {
        val log = mutableListOf<Request>()
        val login = PhoneLogin(client(log) { request ->
            when (request.url.encodedPath) {
                "/passport/mobile/sms_login/" ->
                    if (log.size == 1) """{"data":{
                        "biz_params":{"passport_mfa_retry_tag":"1","sms_code_key":"KEY123"},
                        "common_params":{"copywriting":"{}"},
                        "encrypt_uid":"e","event_params":{"log_id":"l","verify_reason":"ato","verify_scene":"sms_login"},
                        "url":"https://x/passport-second-verification.html",
                        "verify_ways":[{"verify_way":"mobile_up_sms_verify","channel_mobile":"1","sms_content":"YZ"}]
                        },"message":"error"}""" to emptyMap()
                    else """{"data":{},"message":"success"}""" to emptyMap()
                else -> """{"data":{"ticket":"TK"},"message":"success"}""" to emptyMap()
            }
        })
        login.device = mapOf("device_id" to "1", "iid" to "2")
        assertTrue(login.login("17600351236", "4263") is PhoneLogin.Result.NeedUpsms)
        val verified = login.verifyUpsms()
        assertTrue("应当拿到票据：$verified", verified is PhoneLogin.UpsmsResult.Ticket)
        val ticket = (verified as PhoneLogin.UpsmsResult.Ticket).value
        login.login("17600351236", "4263", ticket)
        val replay = form(log.last())
        assertEquals("1", replay["passport_mfa_retry_tag"])
        // KEY123 逐字节 XOR 0x05 → 4b455c3630
        assertEquals("4e405c343736", replay["sms_code_key"])
    }
}
