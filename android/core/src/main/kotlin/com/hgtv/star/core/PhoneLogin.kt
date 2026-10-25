// SPDX-License-Identifier: GPL-3.0-only
// 协议来自 github.com/aston314/hongguo-tv 的 account.go / passport_client.go（GPL-3.0）。
package com.hgtv.star.core

import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

/**
 * 手机号 + 验证码登录（Passport）。
 *
 * ## 与「导入 cookieStore.xml」的关系
 *
 * 那条路要用户自己在手机上翻 App 沙盒、导出文件、再传给电视；这里用手机号与短信直接登录，
 * 拿到的是**同一族会话**（`sessionid` / `sid_tt` / `uid_tt` / `d_ticket` + `X-Tt-Token`），
 * 因此后续的账号接口与云同步完全复用，见 [AccountCredentials]。
 *
 * ## 三步
 *
 * ```
 * ① 匿名注册设备   POST https://log.snssdk.com/service/2/device_register/
 *                  （见 [DeviceRegister]；**必须先做**，随机身份会被 Passport 拒）
 * ② 发验证码       POST /passport/mobile/send_code/v1/
 * ③ 验证码换会话   POST /passport/mobile/sms_login/
 * ```
 *
 * ## 三个容易踩的坑（都是实测出来的）
 *
 * 1. **手机号与验证码都要编码**：加 `+86` 前缀后**逐字节 XOR 0x05**，再 hex。
 *    直接发明文平台回参数错误。
 * 2. **登录端点是 `sms_login`，不是 `validate_code`**。后者要真机那一整套 Passport 会话，
 *    用第三方客户端打一律回 `error_code=1 会话过期`——这正是早期「手机号登录走不通」的原因。
 * 3. **签名必须走 branch 2**（见 [PassportSigner]）。内容接口用的 branch 0 打过来一律
 *    `error_code=16 该应用无权限`。
 *
 * ## 可能要求上行短信（MFA）
 *
 * 平台有时在验证码之外还要「用本机号码主动发一条短信」来确认本人操作。那种情况下
 * [login] 会返回 [LoginResult.NeedUpsms]，页面把平台给的收件号码与短信内容**原样展示**，
 * 用户发完再调 [verifyUpsms]。
 *
 * 这一步**不是一问就有答案**：短信刚发出、平台还没看到时它回 `error_code=1045`，
 * 要隔几秒再问（见 [UpsmsResult.Pending]）；把 1045 当成失败会让用户一按「我已发送」
 * 就看到「验证已过期」，而其实什么都没过期。
 */
class PhoneLogin(
    // 钉死 HTTP/1.1：见 ContentRepository.defaultHttpClient 的说明（平台 TLS 的 h2 读线程异常会打死进程）。
    private val http: OkHttpClient = HgtvTls.configure(OkHttpClient.Builder())
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private companion object {
        /** Passport 接口的域名（与参考实现一致；`api5-normal-*` 那几条也能通）。 */
        const val HOST = "https://api5-normal-lq.fqnovel.com"
        /**
         * 二次验证（`/passport/upsms/gen_verify_ticket` 与 `/passport/upsms/verify`）的主机。
         *
         * 它来自挑战里的 `verify_host`，H5 的 `rmc-second-verify-h5` 直接拿它当 baseURL
         * （`ge()`：`var t = e.verify_host ... return "https:" + i`）。两条域名都能通，
         * 但既然挑战点名了 `i.snssdk.com`，就按它的来，别让 `verify_host` 与真实主机对不上。
         */
        const val VERIFY_HOST = "https://i.snssdk.com"
        /** 登录验证码的 `type`（`"24"` 编码后送出去）。 */
        const val SMS_TYPE = "24"
        /**
         * 手机号的**数字位数范围**：6–15 位（E.164 上限 15 位，部分地区短号只有 6 位）。
         *
         * 这里刻意**不判断「11 位、以 1 开头」**：那只是中国大陆的写法，
         * 香港 / 澳门 8 位、日本 10–11 位、美国 10 位，按中国大陆的形状卡会把它们全拦下来。
         * 号码合法性交给平台判，它回的错误文案原样显示给用户。
         */
        const val PHONE_MIN_DIGITS = 6
        const val PHONE_MAX_DIGITS = 15
        /** 平台给这次上行短信验证的窗口：从**挑战到达**算起 10 分钟（与参考实现一致）。 */
        const val MFA_WINDOW_MS = 10 * 60 * 1000L
        /** 验证码形状：4–8 位数字（红果实测是 4 位）。 */
        val CODE = Regex("[0-9]{4,8}")
        /**
         * 平台在「这次验证已经结束」时给的措辞。
         *
         * 前四句与参考实现逐字一致；后面几句是**验证码那一侧**的过期说法
         * （`验证码已过期` 里没有 `验证已过期` 这个子串，漏掉它就会把用户卡在
         * 「重试同一个已经作废的验证码」上——重试多少次都不会成功）。最后一句是平台直接让人
         * 「重新获取验证码」，那就是最明确的信号。
         */
        val MFA_ENDED = listOf(
            "验证已过期","验证过期","验证已失效","长时间未操作",
            "验证码已过期","验证码过期","验证码已失效","重新获取验证码"
        )
        /** 「还没看到这条短信」的错误码：要等，不要当失败。 */
        const val CODE_PENDING = "1045"
        /**
         * 连续多少次「非 1045、也非明确结束」的确认请求之后才判定挑战作废。
         *
         * 单次网络抖动、或平台对某个参数回 `22 非法应用`，都不该让用户白丢一张验证码；
         * 但一直这样问下去也没有意义，因此给一个小上限（电视端 4 秒一拍 ≈ 16 秒）。
         */
        const val UPSMS_ERROR_LIMIT = 4
        /**
         * 连续多少次 `1045`（平台还没认到短信）之后收手。
         *
         * H5 的实现是 `retry 3 次 × 2 秒`，3 次都 1045 就抛错；
         * 我们按电视端 4 秒一拍，取 6 次 ≈ 24 秒，比官方宽松但一定会结束。
         * 以前这里**没有上限**，于是「平台还没看到这条短信」会一直转下去。
         */
        const val UPSMS_PENDING_LIMIT = 6
        /** `Set-Cookie` 的属性名：它们不是 Cookie，收进来会污染会话串。 */
        val SET_COOKIE_ATTRIBUTES = setOf(
            "path","domain","expires","max-age","secure","httponly","samesite","priority","partitioned"
        )
    }

    /** 发码 / 登录的结果。 */
    sealed class Result {
        /** 验证码已发出。 */
        object Sent: Result()
        /** 登录成功，拿到会话。 */
        data class LoggedIn(val credentials: AccountCredentials): Result()
        /** 平台要求先用本机号码发一条上行短信。 */
        data class NeedUpsms(val number: String,val content: String): Result()
        /** 失败：可直接展示给用户的一句话。 */
        data class Failed(val message: String): Result()
    }

    /**
     * 上行短信验证的结果。**不是「成功 / 失败」两态**：
     *
     * 平台还没有看到那条短信时回 `error_code=1045`。参考实现把它当「继续等」
     * （`verifyMFA` 收到 1045 不报错，轮询到挑战到期为止），**不是**失败——
     * 我们的老实现照着 `description` 显示成「验证已过期」，用户一按「我已发送」就看到「过期」，
     * 其实只是平台还没来得及认这条短信。
     */
    sealed class UpsmsResult {
        /** 平台认了这条短信，给了一张票据：带着它重发 [login] 才拿到会话。 */
        data class Ticket(val value: String): UpsmsResult()
        /**
         * 平台还没看到这条短信（[code] 是它给的那句理由的编号）：过几秒再问一次，别当失败。
         *
         * 带着编号是为了让界面在**等不到时**能如实说明「平台最近一次返回什么」——
         * 这类问题只能靠这一句话定位。
         */
        data class Pending(val code: String = CODE_PENDING,val note: String = ""): UpsmsResult()
        /** 失败：可直接展示给用户的一句话。 */
        data class Failed(val message: String): UpsmsResult()
    }

    /**
     * 一次上行短信确认问出来的四种东西（[verifyUpsms] 的内部形态）。
     *
     * 分这么细是因为「平台明确结束这次验证」与「网络抖动 / 未知错误码」要走完全不同的收尾：
     * 前者才该让用户重新获取验证码，后者应当继续问。
     */
    private sealed class UpsmsOutcome {
        /** 平台还没看到这条短信（`1045`）：继续问。 */
        object Waiting: UpsmsOutcome()
        /** 平台认了这条短信，给了票据。 */
        data class Ticket(val value: String): UpsmsOutcome()
        /** 平台明确结束这次验证（「长时间未操作，验证已过期」这类）。 */
        data class Ended(val text: String): UpsmsOutcome()
        /** 其它失败（网络抖动、未知错误码）：挑战可能还在。 */
        data class Error(val text: String): UpsmsOutcome()
        /**
         * 挑战里根本没有票据，连问都没法问。
         *
         * 这不是「再等等就好」：平台对空 `verify_ticket` 一律回 `3 缺少参数`，
         * 继续轮询只会把同一句错误重复给用户看。直接收手，让他重新获取验证码。
         */
        data class NoTicket(val text: String): UpsmsOutcome()
    }

    private val signer = PassportSigner(clock = clock)

    /**
     * 诊断日志出口。
     *
     * `core` 是纯 Kotlin 模块（没有 Android SDK），因此由界面层注入 `android.util.Log`；
     * 不注入就是空操作。上行短信这条路只有真机 + 真短信能走通，没有日志就只能靠猜。
     *
     * 用法：`adb logcat -s HGTV-LOGIN`
     */
    var log: (String) -> Unit = {}
    private fun trace(message: String) { runCatching { log(message) } }

    /**
     * 设备档案（机型 / 系统 / `cdid` / `openudid` …）。
     *
     * 注册与签名共用同一份，且**与内容接口那份保持同一个来源**
     * （见 `ContentRepository.deviceProfile`），否则同一次会话里会出现两台设备。
     */
    var device: Map<String,String> = emptyMap()
        set(value) { field=value.filterValues { it.isNotBlank() }; signer.updateDevice(field) }

    /** 注册结果下发的 Cookie（`install_id` / `ttreq` / `store-region` …）。 */
    private var deviceCookies: Map<String,String> = emptyMap()
    /** 会话 Cookie（登录成功后由 `Set-Cookie` 累积）。 */
    private val cookies = linkedMapOf<String,String>()
    /** 会话 Token（`X-Tt-Token` 响应头）。 */
    private var token: String = ""
    /** TicketGuard 静态签名（`bd-ticket-guard-sign-res-static-ts-sign` 响应头），用于回写落盘。 */
    private var tsSign: String = ""
    /** 上行短信验证要带的票据（平台在 MFA 响应里给）。 */
    private var mfa: Mfa? = null
    /** 这次挑战的到期时刻：从挑战到达算起 10 分钟，与平台的窗口一致。 */
    private var mfaDeadline = 0L
    /**
     * 已经被 `sms_login` 消费掉的那张验证码。
     *
     * 平台的验证码是一次性的：第一次 `sms_login`（不管回的是会话还是 MFA）就把它用掉了。
     * 之后再拿同一张码去打 `sms_login`，平台只会回「验证码已失效」——而这句话会被界面
     * 当成「挑战结束的原因」展示给用户，于是看起来像是平台莫名其妙作废了验证码。
     *
     * 因此这里记下它：**没有带 MFA 票据的重放一律在本地拦掉**，只留一句「请重新获取验证码」。
     * 带票据的那次重登是平台明确要求的（`passport_mfa_retry_tag=1`），不拦。
     */
    private var consumedCode: String? = null
    /** 上一张被消费的码属于哪个号：换号之后「已消费」的判定不能串到新号上。 */
    private var consumedFor: String = ""
    /**
     * 连续多少次「既不是 1045、也不是明确的结束」。
     *
     * 上行短信确认接口对未知参数会回 `22 非法应用` 这类硬错误；把它当成「挑战结束」会让
     * 用户白丢一张验证码。先按「可能还没同步」继续问，连续几次都这样才收手。
     */
    private var errorStreak = 0
    /**
     * 用 `mobile_ticket` 向 `/passport/upsms/gen_verify_ticket/` 换出来的**真票据**。
     *
     * 这是 H5 的固定流程（`rmc-second-verify-h5`）：URL 里带了 `verify_ticket` 就直接用它，
     * **没带就先 POST `gen_verify_ticket` 换一张**，再拿换来的那张去 `verify`。
     *
     * 我们之前把 `mobile_ticket` 直接当 `verify_ticket` 发，平台一律回 `1045 认证失败`
     * ——短信发得再对也永远匹配不上。换出来的这张才是 `verify` 认的那把钥匙。
     */
    private var genTicket: String = ""
    /** `gen_verify_ticket` 回包里可能带新的收件号码 / 短信内容（H5 就是拿回包整份覆盖页面的）。 */
    private var genNumber: String = ""
    private var genContent: String = ""
    /** 这一次挑战已经试过换票：失败也记下来，别每次轮询都再打一遍（那只会把同一句错误刷屏）。 */
    private var genTried: Boolean = false
    /** 换票失败时平台的原文：以前被静默吞掉，界面只能显示「平台还没看到短信」，根因全丢了。 */
    private var genError: String = ""
    /** 连续收到多少次 `1045`。H5 只重试 3 次（2 秒一次）就放弃，我们不能再无限轮询。 */
    private var pendingStreak: Int = 0
    /**
     * 最近一次平台原文（已脱敏、已截断），只用于失败时的自诊断。
     *
     * 上行短信这条路只有真机 + 真短信才能走通，服务端字段一旦对不上，唯一能定位的就是
     * 「平台到底回了什么」。界面在失败时把这一行摆出来，用户拍照就能把问题钉死。
     */
    var lastDiagnostic: String = ""
        private set
    /**
     * 一次只允许一个动作在途（与参考实现的 `busy` 同一作用）。
     *
     * 遥控器上的连点 / 重复键会把同一个动作发两遍，而平台的**验证码与挑战都是一次性的**：
     * 第二次 `sms_login` 会把第一张验证码消耗掉，用户随后点「我已发送」就会被判「验证已过期」。
     * 因此并发时直接拒绝，由调用方显示「正在处理，请稍候」。
     */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 发码成功后到可以重发之间的等待（秒）。 */
    var retryAfterSeconds: Int = 0
        private set

    /** 还有没有一次「等用户发短信」的挑战在进行中（界面据此决定是重试还是让用户重新获取验证码）。 */
    val waitingUpsms: Boolean get() = mfa != null

    /**
     * 这次上行短信挑战是在**哪一步**来的。
     *
     * 官方客户端在**发码**这一步就会弹 YZ 页面（`kg7.j` 的 2046 分支 → 1002 → 二次验证，
     * 通过后由登录页重新调一次发码），登录这一步也可能弹。两种来源的收尾不一样：
     *
     * * `true`（发码时）：通过之后**重新发一张新码**，用户填新码登录；
     * * `false`（登录时）：通过之后带着票据重发 `sms_login`。
     *
     * 搞反了就会拿一张根本不存在的验证码去登录，或者把已经消费的码再登一次。
     */
    var mfaFromSendCode: Boolean = false
        private set

    private data class Mfa(
        /** 官方 2046 的 risk_info 里没有这两项，允许为空；有就带、没有就不发。 */
        val encryptUid: String,
        val codeKey: String,
        val logId: String,
        val number: String,
        val content: String,
        /** 挑战自带的 `verify_ticket`：确认请求的主票据，**不能为空**（空则平台回 3 缺少参数）。 */
        val verifyTicket: String = "",
        /** 挑战里的另一张票据（`mobile_ticket`）：**不是** `verify_ticket`，只能拿去换票。 */
        val mobileTicket: String = "",
        /** 挑战自报的场景（`passport_scene`）：确认时可能要拿它当 `verify_scene`。 */
        val scene: String = "",
        /**
         * 挑战原文（`risk_info` 的 JSON 字符串）。
         *
         * 确认时 `verify_data` **必须原样回传**：H5 是把 URL 上的 `verify_data` 参数直接发回去的
         * （`data: {...N, verify_ticket}`），平台按它匹配「哪一次挑战」。
         * 我们之前用自己拼的 JSON 顶替，少了 `mobile` / `event_params` 这些字段，
         * 平台匹配不上，于是永远回 `1045 认证失败`。
         */
        val raw: String = "",
        /**
         * `is_complex_sms`：平台要求走 `/passport/upsms/safe_mobile/verify/`
         * 并且带 `upstream_verify_type=1`（H5 里就是这个分支，普通 `verify` 必然对不上）。
         */
        val complex: Boolean = false,
        /** `data.biz_params` 原文：红果页会把它原样塞进 verify 请求。 */
        val bizParams: String = "",
        /** `data.common_params`（去掉 copywriting）：原样带回去。 */
        val commonParams: String = "",
        /** `data.event_params.verify_reason`（实测 `ato`）。 */
        val verifyReason: String = "",
        /**
         * `true` = 红果自己的 `passport-second-verification.html` 链路
         * （`url`/`schema` + `verify_ways`），**没有** `gen_verify_ticket` 这一步。
         */
        val novelread: Boolean = false,
        /** `verify_ways[].is_oper_staff_user == "true"` → 走 `chain_mobile/verify`。 */
        val operStaff: Boolean = false
    )

    /**
     * 二次验证 H5 页面 URL 上的那一整份参数（`N`）。
     *
     * 这不是可选项：`rmc-second-verify-h5` 的 `gen_verify_ticket` / `verify` 都是
     * **把这份 N 原样当 body 发出去**。平台靠 `aid` / `did` / `iid` / `region` / `app_name`
     * 找到这次挑战的会话；少了它们，无论 `verify_ticket` 对不对，都会回
     * `1 会话过期，请重新登录`。
     *
     * 字段来源是原生拼 URL 的 `vb0.c.a()`（classes19.dex）：
     * aid / lang / app_name / locale / ch / channel / iid / vc / app_verison / did / region /
     * use_native_report / use_jsb_request / verify_cancellable / orientation / resolution /
     * sdk_version / os_version / device_brand / device_model / os_name / version_code /
     * version_name / device_type / device_platform / os_type。
     */
    private fun secondaryParams(): LinkedHashMap<String,String> {
        fun value(key: String, fallback: String)=device[key]?.takeIf { it.isNotBlank() } ?: fallback
        val channel=value("channel","update_64")
        val model=value("device_model",value("device_type","25053RT47C"))
        val versionName=value("version_name","7.3.5.32")
        return linkedMapOf(
            "aid" to value("aid","8662"),
            "lang" to "zh",
            "app_name" to "novelread",
            "locale" to "zh",
            "ch" to channel,
            "channel" to channel,
            "iid" to value("iid","0"),
            "vc" to value("version_code","73532"),
            "app_verison" to versionName,
            "did" to value("device_id","0"),
            "region" to "cn",
            "use_native_report" to "1",
            "use_jsb_request" to "1",
            "verify_cancellable" to "1",
            "orientation" to value("orientation","1"),
            "resolution" to value("resolution","1280*2772"),
            "sdk_version" to "4.0.3.cn",
            "os_version" to value("os_version","16"),
            "device_brand" to value("device_brand","Redmi"),
            "device_model" to model,
            "os_name" to "android",
            "version_code" to value("version_code","73532"),
            "version_name" to versionName,
            "device_type" to model,
            "device_platform" to "android",
            "os_type" to "0"
        )
    }

    /** 手机号加 `+86` 后逐字节 XOR 0x05 再 hex。 */
    private fun encodeField(value: String): String =
        value.toByteArray(Charsets.UTF_8).map { (it.toInt() xor 5).toByte() }.toByteArray()
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Passport 的 query：与参考实现的 `passportQuery` 同一套字段。 */
    private fun query(extra: Map<String,String> = emptyMap()): LinkedHashMap<String,String> {
        fun value(key: String, fallback: String) = device[key]?.takeIf { it.isNotBlank() } ?: fallback
        return linkedMapOf(
            "ac" to "wifi","aid" to "8662","app_name" to "novelread",
            "channel" to value("channel","update_64"),
            "device_brand" to value("device_brand","Redmi"),
            "device_id" to value("device_id","0"),
            "device_platform" to "android",
            "device_type" to value("device_type","25053RT47C"),
            "dpi" to value("dpi","520"),
            "iid" to value("iid","0"),
            "language" to "zh",
            "manifest_version_code" to "73532",
            "os" to "android",
            "os_api" to value("os_api","36"),
            "os_version" to value("os_version","16"),
            "resolution" to value("resolution","1280*2772"),
            "ssmix" to "a",
            "update_version_code" to "73532",
            "version_code" to "73532",
            "version_name" to "7.3.5.32"
        ).apply {
            device["cdid"]?.takeIf { it.isNotBlank() }?.let { put("cdid",it) }
            device["openudid"]?.takeIf { it.isNotBlank() }?.let { put("openudid",it) }
            putAll(extra)
        }
    }

    /** 一次带 branch-2 签名的 POST（表单体）。 */
    private fun post(path: String, form: Map<String,String>, extraQuery: Map<String,String> = emptyMap()): JSONObject {
        return postTo(HOST,path,form,extraQuery)
    }

    /**
     * 同 [post]，但主机可换。
     *
     * 二次验证（`upsms/gen_verify_ticket` / `upsms/verify`）**不在 Passport 主机上**：
     * H5 的 `verify_host` 参数就是它的 baseURL（`rmc-second-verify-h5` 的 `ge()`：
     * `var t = e.verify_host ... return "https:" + i`），所有二次验证请求都打到那儿。
     * 打错主机时平台找不到这张票据，回的就是 `1045 认证失败`。
     */
    private fun postTo(
        base: String,
        path: String,
        form: Map<String,String>,
        extraQuery: Map<String,String> = emptyMap(),
        secondaryVerify: Boolean = false
    ): JSONObject {
        val body = form.entries.joinToString("&") { (k,v) -> "$k=${URLEncoder.encode(v,"UTF-8")}" }.toByteArray()
        val signed = signer.sign(base + path, query(extraQuery), body)
        val builder = Request.Builder().url(signed.url)
        signed.headers.forEach { (k,v) -> builder.header(k,v) }
        builder.header("User-Agent",VendorConstants.userAgent(device, video = false))
        builder.header("Accept","application/json")
        builder.header("X-XS-From-Web","0")
        builder.header("Sdk-Version","2")
        builder.header("Passport-Sdk-Version","5051452")
        builder.header("X-VC-Bdturing-Sdk-Version","4.0.3.cn")
        builder.header("Lc","101")
        builder.header("X-SS-DP","8662")
        // 二次验证（gen_verify_ticket / verify）是 H5 SDK 的接口：H5 通过 `app.fetch` 打过去时
        // 固定带这个头（见 `sv.js` 的 `ve()`），平台靠它把这批请求认成二次验证流量。
        if(secondaryVerify) builder.header("X-Use-Secondary-Verify-Sdk","1")
        // 区域头跟着 `store-region` Cookie 走（与参考实现一致）：注册下发的那个值才是这台设备
        // 真正被认到的区域，写死 `cn` 会与 Cookie 里那一份对不上。
        builder.header("X-Tt-Store-Region",cookies["store-region"]?.takeIf { it.isNotBlank() } ?: "cn")
        builder.header("X-Tt-Store-Region-Src","did")
        if(token.isNotBlank()) builder.header("X-Tt-Token",token)
        if(cookies.isNotEmpty()) builder.header("Cookie",cookies.entries.joinToString("; ") { (k,v) -> "$k=$v" })
        builder.post(RequestBody.create(MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"),body))
        trace("→ POST $path form=$form")
        val response = RequestScope.execute(http.newCall(builder.build())) { call ->
            val raw = call.body()?.string().orEmpty()
            // 会话凭证随响应头回来：`Set-Cookie` 与 `X-Tt-Token`，两样都要收。
            call.headers("Set-Cookie").forEach { header -> collectCookies(header) }
            call.header("X-Tt-Token")?.takeIf { it.isNotBlank() }?.let { token=it }
            call.header("bd-ticket-guard-sign-res-static-ts-sign")?.takeIf { it.isNotBlank() }?.let { tsSign=it }
            raw
        }
        if(response.isBlank()) throw IOException("账号服务暂无响应，请稍后重试")
        // 响应原文原样打出来：定位「平台到底回了什么」只能靠它（票据由 [redact] 抹掉）。
        trace("← $path resp=${redact(response)}")
        return JSONObject(response)
    }

    /**
     * 从一个 `Set-Cookie` 头里收下所有 Cookie。
     *
     * 头里的第一段一定是 `name=value`，后面跟的是属性（`Path` / `Domain` / `Expires` / `Max-Age` …），
     * 那些**不能**当 Cookie 存。真实服务器通常一个头一个 Cookie，但把多个 `name=value` 拼在一个头里
     * 也合法（也便于测试），因此这里逐段判断：只收「有 `=` 且名字不是已知属性」的段。
     */
    private fun collectCookies(header: String) {
        header.split(';').forEachIndexed { index, raw ->
            val part=raw.trim()
            val at=part.indexOf('='); if(at<=0) return@forEachIndexed
            val name=part.substring(0,at).trim(); val value=part.substring(at+1).trim()
            if(name.isEmpty() || value.isEmpty()) return@forEachIndexed
            if(index>0 && name.lowercase(Locale.ROOT) in SET_COOKIE_ATTRIBUTES) return@forEachIndexed
            cookies[name]=value
        }
    }

    /** 平台的成功判据：`message=success` 且没有非零 `error_code`。 */
    private fun errorCode(json: JSONObject): String {
        val data=json.optJSONObject("data")
        return data?.optString("error_code").orEmpty().ifEmpty { json.optString("error_code") }
    }
    private fun failed(json: JSONObject): String? {
        val data=json.optJSONObject("data")
        val code=errorCode(json)
        if(code.isNotEmpty() && code!="0") {
            val description=data?.optString("description").orEmpty().ifEmpty { json.optString("description") }
            return if(description.isNotBlank()) "$description（$code）" else "验证失败，请稍后重试（$code）"
        }
        if(json.optString("message")!="success") return "验证失败，请检查验证码或稍后重试"
        if(data==null) return "账号服务返回的内容不完整，请稍后重试"
        return null
    }

    /**
     * 手机号 → E.164（带 `+`，平台认这一种写法）。
     *
     * 两种输入都接受：
     * - **11 位、以 1 开头**：中国大陆的写法，补上 `+86`；
     * - **其余**：用户已经带了国际区号（香港 852…、日本 81…、美国 1…），原样加 `+`。
     *
     * 不做「这是哪个国家」的判断——那需要一份区号表，而区号表既会过时，
     * 也会把「号码格式对不对」这件事从平台手里抢过来。这里只检查位数范围，
     * 真正的校验留给平台（它回的错误文案会原样显示在弹窗里）。
     */
    internal fun internationalPhone(raw: String): String? = e164Phone(raw)

    /**
     * 发验证码。
     *
     * 会**先匿名注册设备**（[DeviceRegister]），注册不成功就不发码——
     * 随机身份打 Passport 一律被拒（实测 HTTP 403 或 `error_code=16`）。
     */
    fun sendCode(phone: String, register: (Map<String,String>) -> DeviceRegister.Registered?): Result {
        if(phone.isNotBlank() && !busy.compareAndSet(false,true)) return Result.Failed("正在处理上一步，请稍候")
        try { return sendCodeOnce(phone,register) } finally { busy.set(false) }
    }
    private fun sendCodeOnce(phone: String, register: (Map<String,String>) -> DeviceRegister.Registered?): Result {
        val mobile=internationalPhone(phone) ?: return Result.Failed("请输入手机号")
        // 设备身份：本机已有注册结果就直接用，没有就现注册一台。
        val registered=if(device["device_id"].orEmpty().isNotBlank() && device["iid"].orEmpty().isNotBlank()) null
                       else register(device)
        if(registered!=null) {
            deviceCookies=registered.cookies
            device=device+mapOf("device_id" to registered.deviceId,"iid" to registered.installId)
            // 注册下发的 Cookie 要带上：平台靠它们把请求认到那台设备上。
            deviceCookies.forEach { (k,v) -> cookies[k]=v }
        }
        val form=linkedMapOf(
            "mobile" to encodeField(mobile),
            "account_sdk_source" to "app",
            "passport_support_flow" to "captcha,verify",
            "mix_mode" to "1",
            "auto_read" to "0",
            "unbind_exist" to "34",
            "type" to encodeField(SMS_TYPE)
        )
        return try {
            val json=post("/passport/mobile/send_code/v1/",form)
            // 发码这一步也可能先要一次二次验证：官方 App 就是在这里弹 YZ 短信页的
            // （`kg7.j` 收到 2046 → 包成 1002 → 登录页拉 BdTuring 二次验证）。把它交给界面，
            // 别当成「验证码没发出去」。
            parseUpsms(json.optJSONObject("data") ?: JSONObject(),json)?.let {
                mfa=it
                mfaDeadline=clock()+MFA_WINDOW_MS
                errorStreak=0; pendingStreak=0
                genTicket=""; genTried=false; genNumber=""; genContent=""; genError=""
                mfaFromSendCode=true
                return Result.NeedUpsms(it.number,it.content)
            }
            failed(json)?.let { return Result.Failed(it) }
            val retry=json.optJSONObject("data")?.optInt("retry_time",0) ?: 0
            retryAfterSeconds=if(retry in 1..3600) retry else 60
            // 新码下发，旧的那张「已消费」标记到此为止。
            consumedCode=null; consumedFor=""
            errorStreak=0
            Result.Sent
        } catch(e: Exception) {
            Result.Failed(e.message.orEmpty().ifBlank { "验证码发送失败，请稍后重试" })
        }
    }

    /**
     * 用验证码换会话。
     *
     * 成功后 [Result.LoggedIn] 里的凭据可以直接交给 `ContentRepository.credentials`，
     * 与导入 `cookieStore.xml` 得到的那份结构完全一致。
     */
    fun login(phone: String, code: String, ticket: String = ""): Result {
        if(!busy.compareAndSet(false,true)) return Result.Failed("正在处理上一步，请稍候")
        try { return loginOnce(phone,code,ticket) } finally { busy.set(false) }
    }
    private fun loginOnce(phone: String, code: String, ticket: String): Result {
        val mobile=internationalPhone(phone) ?: return Result.Failed("请输入手机号")
        if(!CODE.matches(code)) return Result.Failed("请输入 4—8 位验证码")
        // 没有 MFA 票据却重发同一张码：平台一定回「验证码已失效」，而这句会被界面当成
        // 「挑战结束的原因」展示出去。本地直接拦住，别把平台的原话浪费在必然失败的请求上。
        if(ticket.isBlank() && consumedCode==code && consumedFor==mobile)
            return Result.Failed("这张验证码已经用过了，请重新获取验证码")
        val form=linkedMapOf(
            "mobile" to encodeField(mobile),
            "code" to encodeField(code),
            "account_sdk_source" to "app",
            "passport_support_flow" to "captcha,verify",
            "mix_mode" to "1"
        )
        if(ticket.isNotBlank()) {
            // 二次验证通过后的「重放」：官方是把 `biz_params` 整份并回原始请求
            // （`ai1.c` → `w.a(map)`），并对 `sms_code_key` 做同样的 XOR 编码；
            // 不是把返回的 ticket 塞进 `sms_code_key`。
            val biz=runCatching { JSONObject(mfa?.bizParams.orEmpty()) }.getOrNull()
            if(biz!=null) for(k in biz.keys()) {
                val v=biz.optString(k)
                if(v.isNotBlank()) form[k]=if(k=="sms_code_key") encodeField(v) else v
            }
            form["passport_mfa_retry_tag"]="1"
            if(!form.containsKey("sms_code_key")) form["sms_code_key"]=encodeField(ticket)
        }
        return try {
            val json=post("/passport/mobile/sms_login/",form)
            val data=json.optJSONObject("data") ?: return Result.Failed("账号服务返回的内容不完整，请稍后重试")
            // 平台受理了这次请求，这张码就已经被消费（不管回的是会话、MFA 还是别的）。
            // 只有「请求本身被拒」（限流 / 参数错）才不算——那种情况用户还能拿同一张码再试。
            // 先看是不是要求上行短信：`verify_ways`（第三方参考实现）与 2046 的
            // `verify_center_secondary_decision_conf`（官方结构）两种都要认。
            parseUpsms(data,json)?.let {
                trace("MFA 挑战：number=${it.number} content=${it.content} " +
                    "verifyTicket=${if(it.verifyTicket.isBlank()) "<空>" else "有"} " +
                    "mobileTicket=${if(it.mobileTicket.isBlank()) "<空>" else "有"} " +
                    "scene=${it.scene} complex=${it.complex} novelread=${it.novelread} " +
                    "biz=${if(it.bizParams.isBlank()) "<空>" else "有"} rawLength=${it.raw.length}")
                // 原文（已脱敏）前 300 字：下一次再出问题，一眼就能看出 `verify_ticket`
                // 到底在不在原文里、字段名是什么，而不是再从截断的 2046 回包里猜。
                trace("risk_info=${redact(it.raw).take(300)}")
                consumedCode=code; consumedFor=mobile
                mfa=it
                mfaFromSendCode=false
                // 窗口从**挑战到达**开始算，与参考实现一致：用户填验证码慢一点不影响这十分钟。
                mfaDeadline=clock()+MFA_WINDOW_MS
                // 新挑战 = 新标识，重新从默认那套问起。
                errorStreak=0; pendingStreak=0
                genTicket=""; genTried=false; genNumber=""; genContent=""; genError=""
                return Result.NeedUpsms(it.number,it.content)
            }
            failed(json)?.let {
                // 2046 是「要二次验证」：即使 risk_info 的形态不认识、页面没法展开，
                // 这张码也已经交给平台核过了，不能再拿去重放。
                if(errorCode(json)=="2046") { consumedCode=code; consumedFor=mobile }
                return Result.Failed(it)
            }
            // 走到这里必须真的拿到会话：只凭 `message=success` 就当登录成功，会把一份空凭据
            // 存进「我的」页（表现是「登录成功但一条记录都没有」）。
            if(cookies["sessionid"].isNullOrBlank() && token.isBlank())
                return Result.Failed("账号服务返回的内容不完整，请稍后重试")
            consumedCode=code; consumedFor=mobile
            Result.LoggedIn(credentials())
        } catch(e: Exception) {
            Result.Failed(e.message.orEmpty().ifBlank { "登录失败，请稍后重试" })
        }
    }

    /**
     * 从 MFA 响应里取上行短信的收件号码与内容；不是 MFA 就返回 null。
     *
     * 两种结构都要认：
     *
     * 1. **官方 App 的结构**（`sms_login` 回 `error_code=2046`）：
     *    `data.verify_center_secondary_decision_conf` 是一段 JSON 字符串，里面
     *    `subtype=mobile_up_sms_verify`、`channel_mobile`、`sms_content`、`verify_ticket`。
     *    红果自己的登录页就是这么把它交给 `RiskInfoRequest` 的（见 `kg7.j`），
     *    它**没有** `verify_ways`，只认后者会让用户连「要发哪条短信」都看不到。
     * 2. **第三方参考实现的结构**：`data.verify_ways[]` 里挑 `verify_way=upsms` 的那一项。
     *
     * 两边的共同点只有 `channel_mobile` / `sms_content`；`encrypt_uid` / `sms_code_key`
     * 在官方结构里可能没有，因此它们**不是**判定条件——有就带着，没有就不发。
     */
    /** 在响应里按关键字递归找 `verify_center_secondary_decision_conf` / `risk_info`。 */
    private fun findConf(holder: JSONObject, depth: Int = 0): Pair<String,JSONObject>? {
        if(depth>3) return null
        for(key in holder.keys()) {
            if(!key.contains("decision_conf") && key!="risk_info") continue
            holder.optJSONObject(key)?.let { return key to it }
            val text=holder.optString(key)
            if(text.isNotBlank()) runCatching { JSONObject(text) }.getOrNull()?.let { return key to it }
        }
        for(key in holder.keys()) {
            val child=holder.optJSONObject(key) ?: continue
            findConf(child,depth+1)?.let { return it }
        }
        return null
    }

    private fun parseUpsms(data: JSONObject, root: JSONObject = data): Mfa? {
        // ① 官方结构：risk_info 可能在 data 里，也可能挂在根上；字符串与对象两种形态都收。
        var riskRaw: String = ""
        fun riskOf(holder: JSONObject?): JSONObject? {
            if(holder==null) return null
            holder.optJSONObject("verify_center_secondary_decision_conf")?.let {
                riskRaw=it.toString(); return it
            }
            val text=holder.optString("verify_center_secondary_decision_conf")
            if(text.isBlank()) return null
            riskRaw=text
            return runCatching { JSONObject(text) }.getOrNull()
        }
        var risk=riskOf(data) ?: riskOf(root) ?: riskOf(data.optJSONObject("data"))
        // 兜底：字段名偶尔会换（`risk_info` / 更深一层的 conf）。按关键字递归找一遍，
        // 找到就把它当作挑战原文——`verify_data` 必须原样回传，这一步不能靠猜字段路径。
        if(risk==null) {
            findConf(data)?.let { (key,obj) ->
                riskRaw=obj.toString()
                risk=obj
                trace("MFA 挑战来自兜底字段：$key")
            }
        }
        if(risk==null) {
            trace("sms_login data keys=${data.keys().asSequence().toList()}")
        }
        if(risk!=null) {
            val subtype=risk.optString("subtype")
            // `mobile_up_sms_verify` 才是「用本机号码发一条短信」；其它 subtype（滑块、人脸、
            // 短信验证码回填）不走这条页面，交给上层按原样展示，别在这里硬套。
            val number=risk.optString("channel_mobile")
            val content=risk.optString("sms_content")
            if(subtype=="mobile_up_sms_verify" && number.isNotBlank() && content.isNotBlank()) {
                return Mfa(
                    encryptUid=risk.optString("encrypt_uid"),
                    codeKey=risk.optString("sms_code_key"),
                    logId=risk.optString("log_id")
                        .ifBlank { risk.optJSONObject("event_params")?.optString("log_id").orEmpty() },
                    number=number,
                    content=content,
                    // 挑战自带的 `verify_ticket` 才是 `verify` 的钥匙；没有就交给
                    // `gen_verify_ticket` 换，绝不拿 `mobile_ticket` 顶替（它是被 sms_login
                    // 一次性消费掉的票，再发出去平台只会回 `1 会话过期`）。
                    verifyTicket=risk.optString("verify_ticket").ifBlank { risk.optString("ticket") },
                    mobileTicket=risk.optString("mobile_ticket"),
                    scene=risk.optString("verify_scene")
                        .ifBlank { risk.optString("passport_scene") }
                        .ifBlank { risk.optString("scene") },
                    raw=riskRaw,
                    complex=risk.optBoolean("is_complex_sms",false)
                )
            }
        }
        // ② 红果自有结构：`verify_ways[]` + `url`/`schema`（passport-second-verification.html）。
        //
        // 这一条**不是** BdTuring 的 rmc 页面：它没有 `gen_verify_ticket`，
        // 直接 POST `/passport/upsms/verify/`，body 只有
        // `encrypt_uid` / `verify_ticket`（通常为空）/ `biz_params` /
        // `verify_scene` / `verify_reason` + `common_params` 的其余字段。
        var number=""; var content=""
        var way: JSONObject?=null
        val ways=data.optJSONArray("verify_ways")
        if(ways!=null) for(i in 0 until ways.length()) {
            val one=ways.optJSONObject(i) ?: continue
            val text=one.optString("sms_content")
            if(one.optString("verify_way")=="mobile_up_sms_verify" || text.isNotBlank()) {
                content=text; number=one.optString("channel_mobile"); way=one; break
            }
        }
        if(number.isBlank() || content.isBlank()) return null
        val ep=data.optJSONObject("event_params")
        val cp=data.optJSONObject("common_params")
        val common=JSONObject()
        if(cp!=null) for(k in cp.keys()) if(k!="copywriting") common.put(k,cp.opt(k))
        val scene=ep?.optString("verify_scene").orEmpty()
            .ifBlank { data.optString("passport_scene") }
            .ifBlank { data.optString("verify_scene") }
        val complex=data.optBoolean("is_complex_sms",false) ||
            (way?.optBoolean("is_complex_sms",false) ?: false)
        return Mfa(
            encryptUid=data.optString("encrypt_uid"),
            codeKey=data.optString("sms_code_key")
                .ifBlank { data.optJSONObject("biz_params")?.optString("sms_code_key").orEmpty() },
            logId=ep?.optString("log_id").orEmpty()
                .ifBlank { data.optJSONObject("extra_params")?.optString("log_id").orEmpty() },
            number=number,
            content=content,
            verifyTicket=data.optString("verify_ticket").ifBlank { data.optString("ticket") },
            mobileTicket=data.optString("mobile_ticket"),
            scene=scene,
            // 这条链路不回 `verify_data`：`raw` 留空，走 [askNovelread]。
            raw="",
            complex=complex,
            bizParams=data.optJSONObject("biz_params")?.toString().orEmpty(),
            commonParams=common.toString(),
            verifyReason=ep?.optString("verify_reason").orEmpty(),
            novelread=data.optString("url").contains("passport-second-verification") ||
                data.optString("url").isNotBlank() && ways!=null,
            operStaff=way?.optString("is_oper_staff_user")=="true"
        )
    }

    /**
     * 上行短信验证：用户用本机号码发出平台给的那条短信之后调用。
     *
     * 成功时平台给一张 `ticket`，要带着它**重发** [login] 才拿到会话，
     * 因此这里返回票据、由调用方接着调 [login]。
     *
     * 三种结果见 [UpsmsResult]：**平台还没看到短信（[UpsmsResult.Pending]）既不是成功也不是失败**，
     * 调用方应当隔几秒再来问一次；只有平台明确结束这次验证（或已过 10 分钟窗口）才是真的失败。
     */
    fun verifyUpsms(): UpsmsResult {
        if(!busy.compareAndSet(false,true)) return UpsmsResult.Pending(CODE_PENDING)
        try { return verifyUpsmsOnce() } finally { busy.set(false) }
    }
    private fun verifyUpsmsOnce(): UpsmsResult {
        val m=mfa ?: return UpsmsResult.Failed("没有待验证的短信")
        if(clock()>mfaDeadline) {
            // 本地先判一次：过了平台给的 10 分钟窗口，再问也只会得到一句话，不如直接说清楚怎么办。
            mfa=null; mfaFromSendCode=false
            return UpsmsResult.Failed("这次短信验证已经超时（平台只给 10 分钟），请在电视上重新获取验证码")
        }
        val outcome=askUpsms(m)
        trace("upsms 轮询 → ${outcome::class.java.simpleName} " + when(outcome) {
            is UpsmsOutcome.Error -> outcome.text
            is UpsmsOutcome.Ended -> outcome.text
            is UpsmsOutcome.NoTicket -> outcome.text
            else -> ""
        })
        return when(outcome) {
            is UpsmsOutcome.Ticket -> { errorStreak=0; pendingStreak=0; UpsmsResult.Ticket(outcome.value) }
            // 1045 = 平台还没认到这条短信。**不是失败，但也不是可以永远等下去。**
            // H5 只重试 3 次（2 秒一次）就抛错；这里 4 秒一拍、最多 6 拍（≈24 秒），
            // 之后收手让用户重新发码——否则界面会永远停在「平台还没看到这条短信」。
            is UpsmsOutcome.Waiting -> {
                errorStreak=0
                pendingStreak++
                if(pendingStreak<UPSMS_PENDING_LIMIT) UpsmsResult.Pending()
                else {
                    mfa=null; mfaFromSendCode=false
                    UpsmsResult.Failed("平台一直没有认到这条短信（1045 认证失败），请重新获取验证码后再试")
                }
            }
            // 连票据都没有：再问多少次都是同一句「缺少参数」，直接收手。
            is UpsmsOutcome.NoTicket -> {
                mfa=null; mfaFromSendCode=false
                UpsmsResult.Failed(outcome.text)
            }
            // 网络抖动 / 未知错误码 / `22 非法应用` 都**不代表**挑战已经死了。
            is UpsmsOutcome.Error -> {
                errorStreak++
                if(errorStreak<UPSMS_ERROR_LIMIT)
                    return UpsmsResult.Pending(CODE_PENDING,outcome.text)
                mfa=null; mfaFromSendCode=false
                UpsmsResult.Failed(outcome.text)
            }
            // 平台明确结束了这次验证：清掉挑战，让界面引导用户重新获取验证码。
            is UpsmsOutcome.Ended -> {
                mfa=null; mfaFromSendCode=false
                UpsmsResult.Failed("红果已结束本次短信验证，请重新获取验证码：${outcome.text}")
            }
        }
    }

    /**
     * 问一次「平台看到这条短信了吗」。
     *
     * 完全按 `rmc-second-verify-h5` 的顺序：
     * 1. 挑战里自带 `verify_ticket` → 直接用；
     * 2. 没有 → `POST /passport/upsms/gen_verify_ticket/` 换一张；
     * 3. 换不到 → **不再拿 `mobile_ticket` 顶替**（它是 `sms_login` 一次性消费掉的票），直接报错。
     *
     * `verify_data` 一律发挑战原文（risk_info 字符串），不重新拼 JSON：
     * 平台按原文匹配这次挑战，重排字段 / 改转义就等于换了一次挑战。
     */
    private fun askUpsms(m: Mfa): UpsmsOutcome {
        // 红果自有页面：没有换票，直接 verify。
        if(m.novelread) return askNovelread(m)
        var ticket=m.verifyTicket
        if(ticket.isBlank()) ticket=genTicket
        if(ticket.isBlank()) {
            ensureGenTicket(m)
            ticket=m.verifyTicket.ifBlank { genTicket }
        }
        if(ticket.isBlank())
            return UpsmsOutcome.NoTicket(
                "二次验证拿不到票据，请重新获取验证码后再试" +
                    (if(genError.isNotBlank()) "（平台：$genError）" else "")
            )
        // `is_complex_sms` 的挑战 H5 走另一条端点 + `upstream_verify_type=1`：
        // 用普通 `/passport/upsms/verify/` 打过去，平台永远不认。
        val path=if(m.complex) "/passport/upsms/safe_mobile/verify/" else "/passport/upsms/verify/"
        val scene=if(m.complex) "assist_mobile_up_sms_verify" else m.scene.ifBlank { "login" }
        // `verify_data` 必须是挑战原文：H5 的 body 是 `{...N, verify_ticket}`，
        // 其中 `N.verify_data` 就是 risk_info 的原始字符串。自己重建 JSON 会改掉
        // 字段顺序与转义，平台按原文匹配，差一个字节都找不到这次挑战
        // （于是回 `1 会话过期` / `1045 认证失败`）。只有第三方结构没有原文时才拼一份最小的。
        val verifyData=m.raw.ifBlank { minimalVerifyData(m) }
        val form=secondaryParams().apply {
            put("verify_host",VERIFY_HOST)
            put("decision_config","block-upsms")
            put("is_turing","1")
            put("channel_mobile",m.number)
            put("sms_content",m.content)
            put("use_turing_bridge","1")
            put("use_sms_mode","6")
            put("verify_ticket",ticket)
            put("verify_data",verifyData)
            put("verify_scene",scene)
            put("request_tag_from","h5")
            // H5 的请求拦截器会给每个 body 补上 `language`（见 sv.js / `de()`）。
            put("language","zh")
            if(m.complex) put("upstream_verify_type","1")
            if(m.logId.isNotBlank()) put("log_id",m.logId)
            if(m.encryptUid.isNotBlank()) put("encrypt_uid",m.encryptUid)
        }
        return try {
            val json=postTo(VERIFY_HOST,path,form,secondaryVerify=true)
            lastDiagnostic=redact(json.toString())
            // 1045 = 平台还没看到这条短信（等几秒再问）。**不能**当成失败。
            if(errorCode(json)==CODE_PENDING) return UpsmsOutcome.Waiting
            failed(json)?.let { text ->
                val code=errorCode(json)
                // `3 缺少参数` / `22 非法应用` / `1 会话过期` 都是**请求本身有问题**，
                // 不是挑战结束；原样报给用户比反复轮询更有用。
                if(code=="3" || code=="22" || code=="1") return UpsmsOutcome.Error(text)
                val ended=MFA_ENDED.any { value -> text.contains(value) }
                return if(ended) UpsmsOutcome.Ended(text) else UpsmsOutcome.Error(text)
            }
            val data=json.optJSONObject("data") ?: return UpsmsOutcome.Error("验证响应缺少内容")
            val t=data.optString("ticket").ifEmpty { data.optString("verify_ticket") }
            if(t.isBlank()) UpsmsOutcome.Error("验证响应缺少登录票据") else UpsmsOutcome.Ticket(t)
        } catch(e: Exception) {
            lastDiagnostic=redact(e.message.orEmpty())
            UpsmsOutcome.Error(e.message.orEmpty().ifBlank { "验证失败，请稍后重试" })
        }
    }

    /**
     * 红果 `passport-second-verification.html` 的确认请求（**与 BdTuring 那条完全不同**）。
     *
     * 页面源码（`passport-second-verification.0c646e272f8d5af702e3.js`）里根本没有
     * `gen_verify_ticket`；点「我已发送」后直接：
     *
     * ```js
     * data = {
     *   encrypt_uid, verify_ticket, biz_params,
     *   verify_scene, verify_reason,
     *   ...common_params,        // 去掉 copywriting
     *   new_authn_sdk_version: "1.1.31",
     *   ...event_params 的 new_authn_sdk_* 副本
     * }
     * POST /passport/upsms/verify/            (普通)
     * POST /passport/upsms/safe_mobile/verify/ (+upstream_verify_type=1，is_complex_sms)
     * POST /passport/upsms/chain_mobile/verify/(+upstream_verify_type=1，is_oper_staff_user)
     * ```
     *
     * 实测这条 body 打过去，真实 `encrypt_uid` 回 `1045`（平台在等短信），
     * 假 `encrypt_uid` 回 `3 缺少参数`——说明请求形状对了。
     */
    private fun askNovelread(m: Mfa): UpsmsOutcome {
        val path=when {
            m.operStaff -> "/passport/upsms/chain_mobile/verify/"
            m.complex -> "/passport/upsms/safe_mobile/verify/"
            else -> "/passport/upsms/verify/"
        }
        val form=linkedMapOf(
            "encrypt_uid" to m.encryptUid,
            "verify_ticket" to m.verifyTicket,
            "biz_params" to m.bizParams.ifBlank { "{}" },
            "verify_scene" to m.scene,
            "verify_reason" to m.verifyReason,
            "new_authn_sdk_version" to "1.1.31"
        )
        runCatching { JSONObject(m.commonParams) }.getOrNull()?.let { o ->
            for(k in o.keys()) form[k]=o.optString(k)
        }
        // `window.$$secondVerificationRequestCommonParams` = event_params 逐项加 `new_authn_sdk_` 前缀。
        if(m.logId.isNotBlank()) form["new_authn_sdk_log_id"]=m.logId
        if(m.verifyReason.isNotBlank()) form["new_authn_sdk_verify_reason"]=m.verifyReason
        if(m.scene.isNotBlank()) form["new_authn_sdk_verify_scene"]=m.scene
        if(m.complex || m.operStaff) form["upstream_verify_type"]="1"
        return try {
            // 这条走 App 自己的 Passport 主机（原生 `second_verify.fetch` 就是这个栈），
            // 不是 BdTuring 的 `verify_host`。
            val json=postTo(HOST,path,form,secondaryVerify=true)
            lastDiagnostic=redact(json.toString())
            if(errorCode(json)==CODE_PENDING) return UpsmsOutcome.Waiting
            failed(json)?.let { text ->
                val code=errorCode(json)
                if(code=="3" || code=="22" || code=="1") return UpsmsOutcome.Error(text)
                val ended=MFA_ENDED.any { text.contains(it) }
                return if(ended) UpsmsOutcome.Ended(text) else UpsmsOutcome.Error(text)
            }
            val data=json.optJSONObject("data") ?: return UpsmsOutcome.Error("验证响应缺少内容")
            val t=data.optString("ticket").ifEmpty { data.optString("verify_ticket") }
            if(t.isBlank()) UpsmsOutcome.Error("验证响应缺少登录票据") else UpsmsOutcome.Ticket(t)
        } catch(e: Exception) {
            lastDiagnostic=redact(e.message.orEmpty())
            UpsmsOutcome.Error(e.message.orEmpty().ifBlank { "验证失败，请稍后重试" })
        }
    }

    /** 第三方结构没有 `verify_data` 原文时，按已知字段拼一份最小的（H5 不会走这里）。 */
    private fun minimalVerifyData(m: Mfa): String = JSONObject().apply {
        put("subtype","mobile_up_sms_verify")
        put("channel_mobile",m.number)
        put("sms_content",m.content)
        if(m.scene.isNotBlank()) put("verify_scene",m.scene)
        if(m.logId.isNotBlank()) put("log_id",m.logId)
        if(m.encryptUid.isNotBlank()) put("encrypt_uid",m.encryptUid)
        if(m.verifyTicket.isNotBlank()) put("verify_ticket",m.verifyTicket)
    }.toString()

    /**
     * 用挑战原文向 `/passport/upsms/gen_verify_ticket/` 换一张正式票据。
     *
     * H5 的判定是 `if (N.verify_ticket && N.verify_data) {直接用} else {换票}`；
     * **换票请求的 body 就是 URL 上的 `N` 本身**——没有 `verify_ticket` 字段。
     *
     * 这条日志就是反例：我们用 `mobile_ticket_QVNY…` 顶了 `verify_ticket`，
     * 而那张票在 0.8 秒前已经被 `sms_login` 消费掉，平台回
     * `{"error_code":1,"description":"会话过期，请重新登录"}`。
     * 之后又拿同一张死票去 `verify`，于是永远 `1045 认证失败`。
     */
    private fun ensureGenTicket(m: Mfa): String {
        if(genTicket.isNotBlank()) return genTicket
        if(genTried) return ""
        genTried=true
        val verifyData=m.raw.ifBlank { minimalVerifyData(m) }
        val form=secondaryParams().apply {
            put("verify_host",VERIFY_HOST)
            put("decision_config","block-upsms")
            put("is_turing","1")
            put("channel_mobile",m.number)
            put("sms_content",m.content)
            put("use_turing_bridge","1")
            put("use_sms_mode","6")
            put("verify_scene",(if(m.complex) "assist_mobile_up_sms_verify" else m.scene.ifBlank { "login" }))
            put("verify_data",verifyData)
            put("request_tag_from","h5")
            put("language","zh")
            if(m.complex) put("scene","assist_mobile_up_sms_verify")
            // 关键：挑战没给 `verify_ticket` 就**不发**这个字段，绝不拿 `mobile_ticket` 顶替。
            if(m.verifyTicket.isNotBlank()) put("verify_ticket",m.verifyTicket)
            if(m.logId.isNotBlank()) put("log_id",m.logId)
            if(m.encryptUid.isNotBlank()) put("encrypt_uid",m.encryptUid)
        }
        return try {
            val json=postTo(VERIFY_HOST,"/passport/upsms/gen_verify_ticket/",form,secondaryVerify=true)
            val d=json.optJSONObject("data")
            val t=d?.optString("verify_ticket").orEmpty().ifEmpty { d?.optString("ticket").orEmpty() }
            if(t.isNotBlank()) {
                genTicket=t
                // 换票回包会把「要发哪条短信」重新下发一遍（H5 就是拿它覆盖页面）：
                // 挑战里那一份可能已经旧了，用户照着旧内容发就永远匹配不上。
                val number=d?.optString("channel_mobile").orEmpty()
                val content=d?.optString("sms_content").orEmpty()
                if(number.isNotBlank()) genNumber=number
                if(content.isNotBlank()) genContent=content
                if(number.isNotBlank() || content.isNotBlank())
                    mfa=mfa?.copy(
                        number=number.ifBlank { m.number },
                        content=content.ifBlank { m.content }
                    )
                trace("gen_verify_ticket → 换到票据" +
                    (if(genNumber.isNotBlank() || genContent.isNotBlank()) "，并重下号码 / 内容" else ""))
            } else {
                genError=redact(json.toString())
                trace("gen_verify_ticket 没给票据：$genError")
            }
            t
        } catch(e: Exception) {
            genError=e.message.orEmpty().ifBlank { "请求失败" }
            trace("gen_verify_ticket 失败：$genError")
            ""
        }
    }

    /**
     * 平台原文的脱敏 + 截断：只留「到底回了什么」这个信息，不留任何票据 / 会话值。
     */
    private fun redact(text: String): String {
        val masked=Regex("\"(ticket|verify_ticket|mobile_ticket|sms_code_key|encrypt_uid|sessionid|sid_tt)\"\\s*:\\s*\"[^\"]*\"")
            .replace(text) { match -> "\"${match.groupValues[1]}\":\"***\"" }
        // 360 太短会把 2046 回包里 `verify_center_secondary_decision_conf` 这一段直接切掉，
        // 结果日志里只剩 `event_para…`，反而看不见挑战原文的键名。放宽到 1200（票据已抹掉）。
        return if(masked.length>1200) masked.take(1200)+"…" else masked
    }

    /** 把当前会话组装成 [AccountCredentials]（与导入 `cookieStore.xml` 得到的那份同构）。 */
    private fun credentials(): AccountCredentials = AccountCredentials(
        cookie=AccountCredentialsParser.headerOf(cookies),
        token=token,
        tsSign=tsSign,
        deviceId=device["device_id"].orEmpty(),
        installId=device["iid"].orEmpty(),
        cdid=device["cdid"].orEmpty(),
        openudid=device["openudid"].orEmpty(),
        expiresAt=AccountCredentialsParser.expiryOf(cookies),
        device=device
    )

    /** 当前会话的 `X-Tt-Token`（搜索接口用得上；与 Cookie 是两套并行凭证）。 */
    val sessionToken: String get()=token

    /** 当前会话是否已经拿到凭证。 */
    val signedIn: Boolean get()=cookies["sessionid"].orEmpty().isNotBlank() || token.isNotBlank()
}

/**
 * 手机号 → E.164（带 `+`）。与 [PhoneLogin.internationalPhone] 同一份实现，
 * 单独提出来给界面层用：预填「上一次填过的号码」时要先把区号拆掉，
 * 而界面手里没有 [PhoneLogin] 实例（那一次登录会话已经结束了）。
 */
fun e164Phone(raw: String): String? {
    val text=raw.trim()
    val digits=text.filter { it.isDigit() }
    if(digits.length<6 || digits.length>15) return null
    if(text.startsWith("+")) return "+"+digits
    return if(digits.length==11 && digits.startsWith("1")) "+86"+digits else "+"+digits
}
