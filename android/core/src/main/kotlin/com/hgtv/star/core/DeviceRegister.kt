// SPDX-License-Identifier: GPL-3.0-only.
package com.hgtv.star.core

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * 设备注册（`POST /service/2/device_register/`）。
 *
 * ## 为什么需要它
 *
 * 平台的**内容接口按设备放行**，而且认的是「服务端注册过的 `device_id` + `install_id` 这一对」：
 *
 * * App 搜索 `GET /reading/bookapi/search/tab/v` —— 未注册设备一律 `HTTP 200 + 0 字节`；
 *   注册过的设备回 `code 0`（第 1 页 6/20 条、带 `has_more` 游标可翻页）或间歇性 `100103`，
 *   **从不回空响应**。实测：同一套签名，注册前 0/6 命中，注册后 6/6 有数据。
 * * 播放 `POST /novel/player/video_model/v1/` —— 未注册设备回 `101000 服务异常`，
 *   注册后回 `code 0` + 完整播放模型。
 *
 * 也就是说「导入 sessionid 才能用」这个印象，其实是「设备没注册」。注册是**匿名**的：
 * 不需要账号、不需要密码、不需要短信，请求里连签名头都没有。
 *
 * ## 协议（照抓包逐字段复现）
 *
 * ```
 * POST https://log5-applog-lf.fqnovel.com/service/2/device_register/
 *      ?tt_data=a&cronet_version=…&ttnet_version=…&use_store_region_cookie=1
 * Content-Type: application/octet-stream;tt-data=a
 * log-encode-type: gzip
 * x-ss-dp: 8662
 * x-neptune: -8|50:51:59:30:40:47:49:39
 * x-ss-stub: <body 的 MD5，大写>
 * body: ttEncrypt(gzip(JSON))
 * ```
 *
 * 响应：`{"server_time":…,"device_id":…,"install_id":…,"device_token":"…","new_user":0|1}`
 * 并 `Set-Cookie` 下发 `store-region` / `store-region-src=did` / `install_id` / `ttreq`。
 *
 * ## 三个容易踩的坑（都是实测踩出来的）
 *
 * 1. **body 是原始二进制**，不是 base64 文本。抓包 HAR 里 `postData.text` 是 base64，
 *    但 `content-length` 等于解码后的字节数——照着 `postData.text` 发 base64 文本，
 *    服务端一律回 `device_id=0`（这正是本仓库早期「注册不出来」的原因）。
 * 2. **响应可能被 brotli 压缩**（`Content-Encoding: br`）。`Accept-Encoding` 里不带 `br`
 *    也能拿到未压缩的 JSON，但带上就必须自己解，否则 JSON 解析失败。
 * 3. **不要加签名头**。官方这条请求没有 `x-gorgon` / `x-khronos` / `x-ladon` / `x-argus` /
 *    `x-helios` / `x-medusa`，query 里也没有 `ts` / `_rticket`。带上签名头反而会被判异常客户端。
 */
object DeviceRegister {
    /**
     * 注册接口的域名。
     *
     * `log.snssdk.com` 是参考实现（`github.com/aston314/hongguo-tv` 的 `passport_device.go`）实测可用的那一条；
     * `is.snssdk.com` 与它等价。`log5-applog-lf.fqnovel.com` 也能通，但那是「日志」域，
     * 与 Passport 的注册路径不是同一条线路，注册出来的身份在账号侧不一定认。
     */
    private const val HOST = "https://log.snssdk.com"
    private const val PATH = "/service/2/device_register/"
    /** ttEncrypt 的固定前缀（`libEncryptor.so` 的魔数）。 */
    private val MAGIC = byteArrayOf(0x74,0x63,0x05,0x10,0x00,0x00)
    /** ttEncrypt 的 KDF 盐；与 `MediaCrypto.decryptUrl` 用的是同一份常量。 */
    private val KDF_SALT = hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")

    /** 注册结果：平台下发的设备身份 + 需要回写的那几个 Cookie。 */
    data class Registered(
        val deviceId: String,
        val installId: String,
        val deviceToken: String = "",
        /** `new_user=1` 表示平台这次新建了一台设备。 */
        val newUser: Boolean = false,
        /** `Set-Cookie` 里可直接回写的那几项（`install_id` / `ttreq` / `store-region` …）。 */
        val cookies: Map<String,String> = emptyMap()
    )

    /** ttEncrypt：`MAGIC || rnd[32] || AES-128-CBC(SHA512(SHA512(rnd)+KDF_SALT) 前 32 字节, PKCS7(SHA512(data)||data))`。 */
    fun ttEncrypt(data: ByteArray): ByteArray {
        val rnd = ByteArray(32).also { java.util.Random().nextBytes(it) }
        val derived = digest("SHA-512", digest("SHA-512", rnd) + KDF_SALT)
        val body = digest("SHA-512", data) + data
        val padding = 16 - (body.size % 16)
        val padded = body + ByteArray(padding) { padding.toByte() }
        val encrypted = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(derived.copyOfRange(0,16),"AES"), javax.crypto.spec.IvParameterSpec(derived.copyOfRange(16,32)))
            doFinal(padded)
        }
        return MAGIC + rnd + encrypted
    }

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    /**
     * 组装注册请求体：`ttEncrypt(gzip(JSON))`。
     *
     * 字段与抓包里那份逐一对齐；`install_id` / `device_id` **不带**（冷启动首注册就是不带，
     * 由服务端下发）。`_gen_time` 是毫秒时间戳。
     */
    fun payload(device: Map<String,String>, now: Long = System.currentTimeMillis()): ByteArray {
        fun value(key: String, fallback: String) = device[key]?.takeIf { it.isNotBlank() } ?: fallback
        val brand = value("device_brand","Redmi")
        val model = value("device_type","25053RT47C")
        // 厂商按品牌推：参考实现里 `device_brand` 与 `device_manufacturer` 是同一个值（Redmi/Redmi）。
        // 早先这里硬编码 "Intel"，与 `device_brand=SAMSUNG`、`device_model=SM-S9180` 三者互相矛盾——
        // 平台的风控会拿这几个字段做一致性判定，矛盾档案会被按异常客户端处理。
        val manufacturer = when {
            brand.contains("samsung",ignoreCase=true) -> "Samsung"
            brand.contains("redmi",ignoreCase=true) || brand.contains("xiaomi",ignoreCase=true) -> "Xiaomi"
            brand.contains("huawei",ignoreCase=true) || brand.contains("honor",ignoreCase=true) -> "HUAWEI"
            brand.contains("oppo",ignoreCase=true) -> "OPPO"
            brand.contains("vivo",ignoreCase=true) -> "vivo"
            else -> brand
        }
        // 分辨率：与 query 用同一份值。早先 body 硬编码 "1440x2560"、query 是 "1280*2772"，
        // 同一次请求里两个分辨率，服务端只会认其中一个。
        val resolution = value("resolution","1280*2772").replace('*','x')
        // 密度与档位必须自洽：160=mdpi / 240=hdpi / 320=xhdpi / 480=xxhdpi / 640=xxxhdpi。
        val dpi = value("dpi","520").toIntOrNull() ?: 520
        val densityName = when {
            dpi >= 560 -> "xxxhdpi"
            dpi >= 440 -> "xxhdpi"
            dpi >= 280 -> "xhdpi"
            dpi >= 200 -> "hdpi"
            else -> "mdpi"
        }
        val json = org.json.JSONObject()
        json.put("magic_tag","ss_app_log")
        json.put("header", org.json.JSONObject().apply {
            put("display_name","红果免费短剧")
            put("update_version_code",73532); put("manifest_version_code",73532)
            // 官方这里就是**空串**（参考实现 `app_version_minor: ""`），填版本号是错的。
            put("app_version_minor","")
            put("aid",8662)
            put("channel", value("channel","update_64")); put("package","com.phoenix.read")
            put("app_version","7.3.5.32"); put("version_code",73532)
            put("sdk_version","3.7.2"); put("sdk_target_version",29)
            // `os_version` 必须是**字符串**：写成数字（`12`）服务端直接回 `device_id=0`
            // （实测字符串 6/6 成功、数字 0/6）。`os_api` / `density_dpi` 两种都收。
            put("os","Android"); put("os_version", value("os_version","12"))
            put("os_api", value("os_api","32").toIntOrNull() ?: 32)
            put("device_model", model)
            put("device_brand", brand)
            put("device_manufacturer", manufacturer); put("cpu_abi","arm64-v8a")
            put("density_dpi", dpi)
            put("display_density", densityName)
            put("resolution", resolution)
            put("language","zh"); put("timezone",8); put("access","wifi"); put("not_request_sender",0)
            // `rom` / `rom_version` / `carrier` / `mcc_mnc` 早先是硬编码的（`eng.root.…` / `W528JS`
            // / `CHINA MOBILE`），与档案里的机型对不上。现在只跟着档案走，档案没有就不发。
            device["rom_version"]?.takeIf { it.isNotBlank() }?.let { put("rom_version", it) }
            put("cdid", value("cdid", java.util.UUID.randomUUID().toString()))
            put("openudid", value("openudid", java.util.UUID.randomUUID().toString().replace("-","").take(16)))
            put("clientudid", value("clientudid", java.util.UUID.randomUUID().toString()))
            put("ipv6_list", org.json.JSONArray())
            put("region","CN"); put("tz_name","Asia/Shanghai"); put("tz_offset",28800)
            put("sim_region","cn"); put("sim_serial_number", org.json.JSONArray())
            // 参考实现是 true；早先写 false，与官方包行为不一致。
            put("oaid_may_support",true)
            put("req_id", java.util.UUID.randomUUID().toString())
            put("device_platform","android")
            put("custom", org.json.JSONObject().apply {
                put("account_region_src","fallback"); put("host_bit",64)
                put("account_region","cn")
                put("dragon_device_type", value("dragon_device_type","pad"))
                put("is_android_fold",0); put("filter_warn",0); put("web_ua",""); put("is_android_pad",0)
            })
            put("pre_installed_channel","")
            put("apk_first_install_time", now)
            put("is_system_app",0); put("sdk_flavor","china"); put("guest_mode",0)
        })
        json.put("_gen_time", now)
        return ttEncrypt(gzip(json.toString().toByteArray(Charsets.UTF_8)))
    }

    /**
     * 注册请求的头。**刻意不加签名头**（见类注释第 3 条）。
     *
     * 版本号与 `Lc` 按参考实现（`passport_device.go` 的 `registerDevice`）对齐：
     * `passport-sdk-version` 是 **60755**（早先写的 5051452 是 Passport 普通接口那一档，
     * 注册这条要更高）、`lc` 是 **102**、另外要声明 `Content-Encoding: gzip` 与
     * `X-Tt-Request-Tag: t=0;n=1`（注册专用；普通接口是 `t=0;n=0`）。
     */
    fun headers(body: ByteArray, device: Map<String,String>, now: Long = System.currentTimeMillis()): Map<String,String> = linkedMapOf(
        // `user_agent` 来自导入的档案，同样可能带汉字（见 `VendorConstants.headerSafe`）：
        // 原样塞进头会在 `Request.Builder.header()` 抛 `Unexpected char 0x… in User-Agent`。
        "User-Agent" to VendorConstants.headerSafe(
            device["user_agent"], VendorConstants.userAgent(device, video = false)),
        "Content-Type" to "application/octet-stream;tt-data=a",
        "Accept" to "application/json; charset=utf-8,application/x-protobuf",
        "Content-Encoding" to "gzip",
        "log-encode-type" to "gzip",
        "X-Tt-Request-Tag" to "t=0;n=1",
        "x-ss-dp" to "8662",
        "x-ss-req-ticket" to now.toString(),
        "x-vc-bdturing-sdk-version" to "4.0.3.cn",
        "lc" to "102",
        "sdk-version" to "2",
        "passport-sdk-version" to "60755",
        "passport-sdk-settings" to "device_transfer_s_0,device_transfer_ab_1",
        "x-tt-store-region" to "cn",
        "x-tt-store-region-src" to "did",
        // **不要手动设 `accept-encoding`**：OkHttp 只在「自己加的」这个头时才透明解压。
        // 手动写成 `gzip, deflate` 会让它跳过解压，body 到手上还是压缩字节，JSON 解析必失败。
        // 交给 OkHttp 自己加 `gzip`，它顺手把响应解好。
        "x-neptune" to "-8|50:51:59:30:40:47:49:39",
        "x-ss-stub" to digest("MD5", body).hex().uppercase()
    )

    fun url(device: Map<String,String> = emptyMap(), now: Long = System.currentTimeMillis()): String {
        fun value(key: String, fallback: String) = device[key]?.takeIf { it.isNotBlank() } ?: fallback
        val query = linkedMapOf(
            "ac" to "wifi",
            "aid" to "8662",
            "app_name" to "novelread",
            "channel" to value("channel","update_64"),
            "device_brand" to value("device_brand","Redmi"),
            "device_platform" to "android",
            "device_type" to value("device_type","25053RT47C"),
            "dpi" to value("dpi","520"),
            "language" to "zh",
            "manifest_version_code" to value("manifest_version_code","73532"),
            "os" to "android",
            "os_api" to value("os_api","36"),
            "os_version" to value("os_version","16"),
            "resolution" to value("resolution","1280*2772"),
            "ssmix" to "a",
            "update_version_code" to value("update_version_code","73532"),
            "version_code" to value("version_code","73532"),
            "version_name" to value("version_name","7.3.5.32")
        )
        // 首次注册**不带** device_id / iid（它们由服务端下发），也不带 cdid / openudid
        // 之外的身份字段；`cdid` / `openudid` 要带上，服务端靠它们做设备去重。
        device["cdid"]?.takeIf { it.isNotBlank() }?.let { query["cdid"] = it }
        device["openudid"]?.takeIf { it.isNotBlank() }?.let { query["openudid"] = it }
        // 时间字段与注册专用参数。
        query["tt_data"] = "a"
        query["package"] = "com.phoenix.read"
        query["ts"] = (now/1000).toString()
        query["_rticket"] = now.toString()
        return HOST + PATH + "?" + query.entries.joinToString("&") { (k,v) -> "$k=$v" }
    }

    /**
     * 解析注册响应。
     *
     * `device_id` / `install_id` 平台有时给数字、有时给字符串，两种都认；
     * 全为 `0` 表示服务端拒了这次注册（body 形态或头不对），返回 null 由调用方放弃。
     */
    fun parse(body: String, setCookies: List<String> = emptyList()): Registered? {
        val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
        val device = json.opt("device_id_str")?.toString()?.takeIf { it.isNotBlank() }
            ?: json.opt("device_id")?.toString().orEmpty()
        val install = json.opt("install_id_str")?.toString()?.takeIf { it.isNotBlank() }
            ?: json.opt("install_id")?.toString().orEmpty()
        if(device.isBlank() || install.isBlank() || device=="0" || install=="0") return null
        val cookies = linkedMapOf<String,String>()
        setCookies.forEach { header ->
            header.split(';').firstOrNull()?.trim()?.takeIf { it.contains('=') }?.let { pair ->
                val name = pair.substringBefore('=').trim(); val value = pair.substringAfter('=').trim()
                if(name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
        }
        return Registered(device, install, json.optString("device_token",""), json.optInt("new_user",0)==1, cookies)
    }
}
