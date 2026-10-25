// 联网实测：默认跳过，只有 HONGGUO_LIVE=1 时才真的发请求。
// 会**真的发一条短信**，因此只在明确要验收登录链路时跑：
//   HONGGUO_LIVE=1 HONGGUO_LIVE_PHONE=176xxxxxxxx \
//   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
//   ./gradlew :core:test --tests 'com.hgtv.star.core.LivePhoneLoginTest' --info
package com.hgtv.star.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 手机号登录链路的联网验收：注册设备 → 发码 → （人工给码后）换会话。
 *
 * 判据是「签名与参数被平台接受、进入业务分支」：发码回 `message=success`、
 * 登录回明确的成功或 MFA，而不是 `error_code=16 该应用无权限`（签名不对）
 * 或 `error_code=1003 手机号错误`（编码不对）。
 */
class LivePhoneLoginTest {
    private val live = System.getenv("HONGGUO_LIVE") == "1"
    private val phone = System.getenv("HONGGUO_LIVE_PHONE").orEmpty()
    private val code = System.getenv("HONGGUO_LIVE_CODE").orEmpty()

    /** 本机设备档案（真机机型 / 系统 / 分辨率），与 `Library.deviceProfile` 同一套字段。 */
    private fun device() = linkedMapOf(
        "cdid" to java.util.UUID.randomUUID().toString(),
        "openudid" to "%016x".format(kotlin.random.Random.nextLong()),
        "device_type" to (System.getProperty("os.arch").orEmpty().ifBlank { "25053RT47C" }),
        "device_brand" to "Redmi",
        "os_version" to "16",
        "os_api" to "36",
        "rom_version" to "BP2A.250605.031.A3",
        "resolution" to "1280*2772",
        "dpi" to "520"
    )

    @Test fun sendCodeReachesTheBusinessBranch() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", live)
        val login = PhoneLogin()
        login.device = device()
        val registered = DeviceRegister.Registered("0", "0") // 占位，真实注册由回调做
        val result = login.sendCode(phone.ifBlank { "10000000000" }) { profile ->
            // 用真实的注册接口换一对设备身份；这里直接复用 DeviceRegister 的组装。
            val repo = ContentRepository()
            repo.registerProfile = profile
            repo.registerDevice()
        }
        println("[live-phone] sendCode → $result retryAfter=${login.retryAfterSeconds}")
        // 用错号码时也应当只到业务分支（不是签名被拒），因此这里只要求不是「无权限」。
        when (result) {
            is PhoneLogin.Result.Sent -> assertTrue("发码成功", true)
            is PhoneLogin.Result.Failed -> {
                println("[live-phone] 失败原因：${result.message}")
                assertTrue("签名被拒（该应用无权限）", !result.message.contains("无权限"))
            }
            else -> assertTrue("非预期结果", false)
        }
    }

    @Test fun loginWithCodeReturnsSessionOrMfa() {
        assumeTrue("需要 HONGGUO_LIVE=1 与 HONGGUO_LIVE_CODE 才联网实测", live && code.isNotBlank())
        val login = PhoneLogin()
        login.device = device()
        login.sendCode(phone) { profile ->
            val repo = ContentRepository()
            repo.registerProfile = profile
            repo.registerDevice()
        }
        val result = login.login(phone, code)
        println("[live-phone] login → $result")
        when (result) {
            is PhoneLogin.Result.LoggedIn -> {
                println("[live-phone] cookie=${result.credentials.cookie.take(120)}")
                println("[live-phone] 剩余天数=${result.credentials.remainingDays()}")
                assertTrue("会话 Cookie 必须含 sessionid", result.credentials.cookie.contains("sessionid="))
            }
            is PhoneLogin.Result.NeedUpsms -> {
                println("[live-phone] 要求上行短信：${result.number} / ${result.content}")
                assertTrue("上行短信号码与内容都要有", result.number.isNotBlank() && result.content.isNotBlank())
            }
            is PhoneLogin.Result.Failed -> assertTrue("登录失败：${result.message}", false)
            else -> assertTrue("非预期结果", false)
        }
    }
}
