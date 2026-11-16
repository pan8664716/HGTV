// SPDX-License-Identifier: GPL-3.0-only
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

class PhoneLogin(
    private val http: OkHttpClient = HgtvTls.configure(OkHttpClient.Builder())
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(REQUEST_TIMEOUT_SECONDS,TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS,TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SECONDS,TimeUnit.SECONDS)
        .callTimeout(REQUEST_TIMEOUT_SECONDS,TimeUnit.SECONDS)
        .build(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private companion object {
        fun host(): String = "https://${TncConfigStore.preferredHost("lq")}"
        fun verifyHost(): String =
            "https://${TncConfigStore.preferredLocalHost("i.snssdk.com")}"
        val VERIFY_HOST: String get() = verifyHost()
        const val SMS_TYPE = "24"
        const val PHONE_MIN_DIGITS = 6
        const val PHONE_MAX_DIGITS = 15
        const val MFA_WINDOW_MS = 10 * 60 * 1000L
        val CODE = Regex("[0-9]{4,8}")
        val MFA_ENDED = listOf(
            "验证已过期","验证过期","验证已失效","长时间未操作",
            "验证码已过期","验证码过期","验证码已失效","重新获取验证码"
        )
        const val CODE_PENDING = "1045"
        const val UPSMS_ERROR_LIMIT = 4
        const val UPSMS_PENDING_LIMIT = 6
        val SET_COOKIE_ATTRIBUTES = setOf(
            "path","domain","expires","max-age","secure","httponly","samesite","priority","partitioned"
        )
    }

    sealed class Result {
        object Sent: Result()
        data class LoggedIn(val credentials: AccountCredentials): Result()
        data class NeedUpsms(val number: String,val content: String): Result()
        data class Failed(val message: String): Result()
    }

    sealed class UpsmsResult {
        data class Ticket(val value: String): UpsmsResult()
        data class Pending(val code: String = CODE_PENDING,val note: String = ""): UpsmsResult()
        data class Failed(val message: String): UpsmsResult()
    }

    private sealed class UpsmsOutcome {
        object Waiting: UpsmsOutcome()
        data class Ticket(val value: String): UpsmsOutcome()
        data class Ended(val text: String): UpsmsOutcome()
        data class Error(val text: String): UpsmsOutcome()
        data class NoTicket(val text: String): UpsmsOutcome()
    }

    private val signer = PassportSigner(clock = clock)

    var log: (String) -> Unit = {}
    private fun trace(message: String) { runCatching { log(message) } }

    var device: Map<String,String> = emptyMap()
        set(value) { field=value.filterValues { it.isNotBlank() }; signer.updateDevice(field) }

    private var deviceCookies: Map<String,String> = emptyMap()

    fun useDeviceCookies(values: Map<String,String>) {
        deviceCookies=values.filterValues { it.isNotBlank() }
        cookies.putAll(deviceCookies)
    }

    private val cookies = linkedMapOf<String,String>()
    private var token: String = ""
    private var tsSign: String = ""
    private var mfa: Mfa? = null
    private var mfaDeadline = 0L
    private var consumedCode: String? = null
    private var consumedFor: String = ""
    private var errorStreak = 0
    private var genTicket: String = ""
    private var genNumber: String = ""
    private var genContent: String = ""
    private var genTried: Boolean = false
    private var genError: String = ""
    private var pendingStreak: Int = 0
    var lastDiagnostic: String = ""
        private set
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    var retryAfterSeconds: Int = 0
        private set

    val waitingUpsms: Boolean get() = mfa != null

    var mfaFromSendCode: Boolean = false
        private set

    private data class Mfa(
        val encryptUid: String,
        val codeKey: String,
        val logId: String,
        val number: String,
        val content: String,
        val verifyTicket: String = "",
        val mobileTicket: String = "",
        val scene: String = "",
        val raw: String = "",
        val complex: Boolean = false,
        val bizParams: String = "",
        val commonParams: String = "",
        val verifyReason: String = "",
        val novelread: Boolean = false,
        val operStaff: Boolean = false
    )

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

    private fun encodeField(value: String): String =
        value.toByteArray(Charsets.UTF_8).map { (it.toInt() xor 5).toByte() }.toByteArray()
            .joinToString("") { "%02x".format(it.toInt() and 255) }

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

    private fun post(path: String, form: Map<String,String>, extraQuery: Map<String,String> = emptyMap()): JSONObject {
        return postTo(host(),path,form,extraQuery)
    }

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
        if(secondaryVerify) builder.header("X-Use-Secondary-Verify-Sdk","1")
        cookies["store-region"]?.takeIf { it.isNotBlank() }?.let { builder.header("X-Tt-Store-Region",it) }
        cookies["store-region-src"]?.takeIf { it.isNotBlank() }?.let { builder.header("X-Tt-Store-Region-Src",it) }
        if(token.isNotBlank()) builder.header("X-Tt-Token",token)
        if(cookies.isNotEmpty()) builder.header("Cookie",cookies.entries.joinToString("; ") { (k,v) -> "$k=$v" })
        builder.post(body.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
        trace("→ POST $path form=$form")
        val response = RequestScope.execute(http.newCall(builder.build())) { call ->
            val raw = call.body?.string().orEmpty()
            call.headers("Set-Cookie").forEach { header -> collectCookies(header) }
            call.header("X-Tt-Token")?.takeIf { it.isNotBlank() }?.let { token=it }
            call.header("bd-ticket-guard-sign-res-static-ts-sign")?.takeIf { it.isNotBlank() }?.let { tsSign=it }
            raw
        }
        if(response.isBlank()) throw IOException("账号服务暂无响应，请稍后重试")
        trace("← $path resp=${redact(response)}")
        return JSONObject(response)
    }

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

    fun internationalPhone(raw: String): String? = e164Phone(raw)

    fun sendCode(phone: String, register: (Map<String,String>) -> DeviceRegister.Registered?): Result {
        if(phone.isNotBlank() && !busy.compareAndSet(false,true)) return Result.Failed("正在处理上一步，请稍候")
        try { return sendCodeOnce(phone,register) } finally { busy.set(false) }
    }
    private fun sendCodeOnce(phone: String, register: (Map<String,String>) -> DeviceRegister.Registered?): Result {
        val mobile=internationalPhone(phone) ?: return Result.Failed("请输入手机号")
        val registered=if(device["device_id"].orEmpty().isNotBlank() && device["iid"].orEmpty().isNotBlank()) null
                       else register(device)
        if(registered!=null) {
            deviceCookies=registered.cookies
            device=device+mapOf("device_id" to registered.deviceId,"iid" to registered.installId)
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
            consumedCode=null; consumedFor=""
            errorStreak=0
            Result.Sent
        } catch(e: Exception) {
            Result.Failed(e.message.orEmpty().ifBlank { "验证码发送失败，请稍后重试" })
        }
    }

    fun login(phone: String, code: String, ticket: String = ""): Result {
        if(!busy.compareAndSet(false,true)) return Result.Failed("正在处理上一步，请稍候")
        try { return loginOnce(phone,code,ticket) } finally { busy.set(false) }
    }
    private fun loginOnce(phone: String, code: String, ticket: String): Result {
        val mobile=internationalPhone(phone) ?: return Result.Failed("请输入手机号")
        if(!CODE.matches(code)) return Result.Failed("请输入 4—8 位验证码")
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
            parseUpsms(data,json)?.let {
                trace("MFA 挑战：number=${it.number} content=${it.content} " +
                    "verifyTicket=${if(it.verifyTicket.isBlank()) "<空>" else "有"} " +
                    "mobileTicket=${if(it.mobileTicket.isBlank()) "<空>" else "有"} " +
                    "scene=${it.scene} complex=${it.complex} novelread=${it.novelread} " +
                    "biz=${if(it.bizParams.isBlank()) "<空>" else "有"} rawLength=${it.raw.length}")
                trace("risk_info=${redact(it.raw).take(300)}")
                consumedCode=code; consumedFor=mobile
                mfa=it
                mfaFromSendCode=false
                mfaDeadline=clock()+MFA_WINDOW_MS
                errorStreak=0; pendingStreak=0
                genTicket=""; genTried=false; genNumber=""; genContent=""; genError=""
                return Result.NeedUpsms(it.number,it.content)
            }
            failed(json)?.let {
                if(errorCode(json)=="2046") { consumedCode=code; consumedFor=mobile }
                return Result.Failed(it)
            }
            if(cookies["sessionid"].isNullOrBlank() && token.isBlank())
                return Result.Failed("账号服务返回的内容不完整，请稍后重试")
            consumedCode=code; consumedFor=mobile
            Result.LoggedIn(credentials())
        } catch(e: Exception) {
            Result.Failed(e.message.orEmpty().ifBlank { "登录失败，请稍后重试" })
        }
    }

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

    fun verifyUpsms(): UpsmsResult {
        if(!busy.compareAndSet(false,true)) return UpsmsResult.Pending(CODE_PENDING)
        try { return verifyUpsmsOnce() } finally { busy.set(false) }
    }
    private fun verifyUpsmsOnce(): UpsmsResult {
        val m=mfa ?: return UpsmsResult.Failed("没有待验证的短信")
        if(clock()>mfaDeadline) {
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
            is UpsmsOutcome.Waiting -> {
                errorStreak=0
                pendingStreak++
                if(pendingStreak<UPSMS_PENDING_LIMIT) UpsmsResult.Pending()
                else {
                    mfa=null; mfaFromSendCode=false
                    UpsmsResult.Failed("平台一直没有认到这条短信（1045 认证失败），请重新获取验证码后再试")
                }
            }
            is UpsmsOutcome.NoTicket -> {
                mfa=null; mfaFromSendCode=false
                UpsmsResult.Failed(outcome.text)
            }
            is UpsmsOutcome.Error -> {
                errorStreak++
                if(errorStreak<UPSMS_ERROR_LIMIT)
                    return UpsmsResult.Pending(CODE_PENDING,outcome.text)
                mfa=null; mfaFromSendCode=false
                UpsmsResult.Failed(outcome.text)
            }
            is UpsmsOutcome.Ended -> {
                mfa=null; mfaFromSendCode=false
                UpsmsResult.Failed("HGTV已结束本次短信验证，请重新获取验证码：${outcome.text}")
            }
        }
    }

    private fun askUpsms(m: Mfa): UpsmsOutcome {
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
        val path=if(m.complex) "/passport/upsms/safe_mobile/verify/" else "/passport/upsms/verify/"
        val scene=if(m.complex) "assist_mobile_up_sms_verify" else m.scene.ifBlank { "login" }
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
            put("language","zh")
            if(m.complex) put("upstream_verify_type","1")
            if(m.logId.isNotBlank()) put("log_id",m.logId)
            if(m.encryptUid.isNotBlank()) put("encrypt_uid",m.encryptUid)
        }
        return try {
            val json=postTo(VERIFY_HOST,path,form,secondaryVerify=true)
            lastDiagnostic=redact(json.toString())
            if(errorCode(json)==CODE_PENDING) return UpsmsOutcome.Waiting
            failed(json)?.let { text ->
                val code=errorCode(json)
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
        if(m.logId.isNotBlank()) form["new_authn_sdk_log_id"]=m.logId
        if(m.verifyReason.isNotBlank()) form["new_authn_sdk_verify_reason"]=m.verifyReason
        if(m.scene.isNotBlank()) form["new_authn_sdk_verify_scene"]=m.scene
        if(m.complex || m.operStaff) form["upstream_verify_type"]="1"
        return try {
            val json=postTo(host(),path,form,secondaryVerify=true)
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

    private fun minimalVerifyData(m: Mfa): String = JSONObject().apply {
        put("subtype","mobile_up_sms_verify")
        put("channel_mobile",m.number)
        put("sms_content",m.content)
        if(m.scene.isNotBlank()) put("verify_scene",m.scene)
        if(m.logId.isNotBlank()) put("log_id",m.logId)
        if(m.encryptUid.isNotBlank()) put("encrypt_uid",m.encryptUid)
        if(m.verifyTicket.isNotBlank()) put("verify_ticket",m.verifyTicket)
    }.toString()

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

    private fun redact(text: String): String {
        val masked=Regex("\"(ticket|verify_ticket|mobile_ticket|sms_code_key|encrypt_uid|sessionid|sid_tt)\"\\s*:\\s*\"[^\"]*\"")
            .replace(text) { match -> "\"${match.groupValues[1]}\":\"***\"" }
        return if(masked.length>1200) masked.take(1200)+"…" else masked
    }

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

    val sessionToken: String get()=token

    val signedIn: Boolean get()=cookies["sessionid"].orEmpty().isNotBlank() || token.isNotBlank()
}

fun e164Phone(raw: String): String? {
    val text=raw.trim()
    val digits=text.filter { it.isDigit() }
    if(digits.length<6 || digits.length>15) return null
    if(text.startsWith("+")) return "+"+digits
    return if(digits.length==11 && digits.startsWith("1")) "+86"+digits else "+"+digits
}
