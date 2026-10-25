// 联网实测：默认跳过，只有 HONGGUO_LIVE=1 时才真的发请求。
// 运行：HONGGUO_LIVE=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
//       ./gradlew :core:test --tests 'com.hgtv.star.core.LivePassportSignTest' --info
package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 用 branch-2 签名打真实的 Passport 端点，验证签名**被接受**。
 *
 * 判据是「平台是否进到业务分支」，不是「业务是否成功」：
 * * 签名被拒 → `error_code=16 该应用无权限`（用 branch 0 打就是这个结果）；
 * * 签名通过 → `error_code=1003 手机号错误` 这类参数校验（手机号故意给错，
 *   因此**不会真的发出短信**，也不消耗任何配额）。
 */
class LivePassportSignTest {
    private val host = "https://api5-normal-lq.fqnovel.com"

    private fun post(path: String, values: Map<String, String>, body: ByteArray?, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val signer = PassportSigner()
        val signed = signer.sign(host + path, values, body)
        val builder = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("User-Agent", VendorConstants.APP_UA)
        builder.header("Accept", "application/json")
        builder.header("X-XS-From-Web", "0")
        builder.header("Sdk-Version", "2")
        builder.header("Passport-Sdk-Version", "5051452")
        builder.header("X-VC-Bdturing-Sdk-Version", "4.0.3.cn")
        builder.header("Lc", "101")
        builder.header("X-SS-DP", "8662")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
        body?.let { builder.post(RequestBody.create(MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"), it)) }
        client.newCall(builder.build()).execute().use { response ->
            return response.code() to (response.body()?.string() ?: "")
        }
    }

    /** Passport 的 query：aid / app_name / 设备参数一整套（照参考实现的 `passportQuery`）。 */
    private fun passportQuery() = linkedMapOf(
        "ac" to "wifi", "aid" to "8662", "app_name" to "novelread", "channel" to "update_64",
        "device_brand" to "Redmi", "device_id" to "0", "device_platform" to "android",
        "device_type" to "25053RT47C", "dpi" to "520", "iid" to "0", "language" to "zh",
        "manifest_version_code" to "73532", "os" to "android", "os_api" to "36", "os_version" to "16",
        "resolution" to "1280*2772", "ssmix" to "a", "update_version_code" to "73532",
        "version_code" to "73532", "version_name" to "7.3.5.32"
    )

    /** 手机号 / 验证码的编码：加 `+86` 后逐字节 XOR 0x05 再 hex。 */
    private fun xorHex(value: String) = value.toByteArray().map { (it.toInt() xor 5).toByte() }
        .toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun signedSendCodeReachesBusinessBranch() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", System.getenv("HONGGUO_LIVE") == "1")
        // 故意用一个**格式错误**的手机号：平台会回参数校验错误，不会真的发短信。
        val form = listOf(
            "mobile" to xorHex("+8600000000000"),
            "account_sdk_source" to "app",
            "passport_support_flow" to "captcha,verify",
            "mix_mode" to "1",
            "auto_read" to "0",
            "unbind_exist" to "34",
            "type" to xorHex("24")
        )
        val body = form.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }.toByteArray()
        val (status, text) = post("/passport/mobile/send_code/v1/", passportQuery(), body)
        println("[live-passport] HTTP $status ${text.length}B ${text.take(200)}")
        assertEquals(200, status)
        assertTrue("响应为空", text.isNotBlank())
        val json = JSONObject(text)
        val code = json.optJSONObject("data")?.optString("error_code").orEmpty().ifEmpty { json.optString("error_code") }
        val description = json.optJSONObject("data")?.optString("description").orEmpty()
        println("[live-passport] error_code=$code description=$description")
        // 16 = 该应用无权限（签名 / 分支不对）；只要不是 16 就说明签名被平台接受了。
        assertTrue("签名被拒（error_code=16 该应用无权限）", code != "16")
    }
}
