// SPDX-License-Identifier: GPL-3.0-only.
package com.hgtv.star.core

import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.NetworkInterface
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
 * x-neptune: -11|50:51:59
 * accept-encoding: gzip, deflate, br
 * x-ss-stub: <body 的 MD5，大写>
 * body: ttEncrypt(gzip(JSON))
 * ```
 *
 * 响应：`{"server_time":…,"device_id":…,"install_id":…,"klink_egdi":"…","device_token":"…","new_user":0|1}`
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
    /** 首次无 TNC 配置时，官方 7.4.3.32 冷启动注册使用的线路。 */
    private const val HOST = "log5-applog-lf.fqnovel.com"
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
        val klinkEgdi: String = "",
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

    /** 官方注册会上报本机非回环 IPv6；拿不到时保持空数组。 */
    private fun ipv6List(): org.json.JSONArray {
        val list=org.json.JSONArray()
        runCatching {
            val addresses=NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet6Address>()
                .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                .toList()
            // 官方只上报一个 `client_anpi`，优先链路本地地址，和 App 的行为一致。
            val address=(addresses.firstOrNull { it.isLinkLocalAddress } ?: addresses.firstOrNull())
                ?.hostAddress.orEmpty().substringBefore('%')
            if(address.isNotBlank()) {
                list.put(org.json.JSONObject().put("type","client_anpi").put("value",compressIpv6(address)))
            }
        }
        return list
    }

    /** Java 的 IPv6 文本常把零段展开；压回官方使用的 `FE80::...` 形式。 */
    private fun compressIpv6(value: String): String {
        val parts=value.split(':')
        if(parts.size!=8) return value.uppercase()
        var bestAt=-1; var bestLen=0; var at=0
        while(at<parts.size) {
            if(parts[at]!="0") { at++; continue }
            var end=at
            while(end<parts.size && parts[end]=="0") end++
            if(end-at>bestLen) { bestAt=at; bestLen=end-at }
            at=end
        }
        if(bestLen<2) return value.uppercase()
        val left=parts.take(bestAt).joinToString(":")
        val right=parts.drop(bestAt+bestLen).joinToString(":")
        return "$left::$right".uppercase()
    }

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
        // 官方 query 是「长边*短边」，注册 body 是「短边x长边」。两者由同一份档案换算，
        // 不能各写一套，否则同一次请求里会同时出现横屏和竖屏两个尺寸。
        val resolution = run {
            val parts = value("resolution","2772*1280").split('*','x')
            val a = parts.getOrNull(0)?.toIntOrNull()
            val b = parts.getOrNull(1)?.toIntOrNull()
            if(a!=null && b!=null) "${minOf(a,b)}x${maxOf(a,b)}" else "1280x2772"
        }
        val dpi = value("dpi","520").toIntOrNull() ?: 520
        val json = org.json.JSONObject()
        json.put("magic_tag","ss_app_log")
        json.put("header", org.json.JSONObject().apply {
            put("display_name","红果免费短剧")
            put("update_version_code",74332); put("manifest_version_code",74332)
            // 2026-11-06 官方 7.4.3.32 冷启动抓包里这一项是完整版本号。
            put("app_version_minor","7.4.3.32")
            put("aid",8662)
            put("channel", value("channel","69310258a")); put("package","com.phoenix.read")
            put("app_version","7.4.3.32"); put("version_code",74332)
            put("sdk_version","3.7.0-rc.34-fanqie-xiaoshuo"); put("sdk_target_version",29)
            // `os_version` 必须是**字符串**：写成数字（`12`）服务端直接回 `device_id=0`
            // （实测字符串 6/6 成功、数字 0/6）。`os_api` / `density_dpi` 两种都收。
            put("os","Android"); put("os_version", value("os_version","12"))
            put("os_api", value("os_api","32").toIntOrNull() ?: 32)
            put("device_model", model)
            put("device_brand", brand)
            put("device_manufacturer", manufacturer); put("cpu_abi","arm64-v8a")
            put("density_dpi", dpi)
            put("display_density", device["display_density"]?.takeIf { it.isNotBlank() } ?: "mdpi")
            put("resolution", resolution)
            put("language","zh"); put("timezone",8); put("access","wifi"); put("not_request_sender",0)
            // 这四项在官方 7.4.3.32 注册 body 中始终存在。真实档案优先；
            // 模拟器 / 无电话服务时使用抓包中的官方默认值，避免字段直接缺失。
            put("carrier", value("carrier","CHINA MOBILE"))
            put("mcc_mnc", value("mcc_mnc","46000"))
            put("rom", value("rom","1135"))
            put("rom_version", value("rom_version","W528JS release-keys"))
            put("git_hash","3a88f4e")
            put("sig_hash","56a962410c494bbaf0b58dba20cae56f")
            put("release_build","b5e187f")
            put("cdid", value("cdid", java.util.UUID.randomUUID().toString()))
            put("openudid", value("openudid", OfficialDeviceIds.openudid(null)))
            put("clientudid", value("clientudid", java.util.UUID.randomUUID().toString()))
            // 已有设备重注册时，官方会把上一轮身份一起带回；首次注册时这几项为空。
            device["device_id"]?.takeIf { it.isNotBlank() }?.let { put("device_id",it) }
            (device["iid"] ?: device["install_id"])?.takeIf { it.isNotBlank() }?.let { put("install_id",it) }
            device["klink_egdi"]?.takeIf { it.isNotBlank() }?.let { put("klink_egdi",it) }
            put("ipv6_list", ipv6List())
            put("region","CN"); put("tz_name","Asia/Shanghai"); put("tz_offset",28800)
            put("sim_region","cn"); put("sim_serial_number", org.json.JSONArray())
            // 官方 7.4.3.32 冷启动抓包里是 false。
            put("oaid_may_support",false)
            put("req_id", java.util.UUID.randomUUID().toString())
            put("device_platform","android")
            put("custom", org.json.JSONObject().apply {
                put("account_region_src","fallback"); put("host_bit",64)
                put("account_region","cn")
                put("dragon_device_type", value("dragon_device_type","phone"))
            })
            put("pre_installed_channel","")
            put("apk_first_install_time", device["apk_first_install_time"]?.toLongOrNull() ?: now)
            put("is_system_app",0); put("sdk_flavor","china"); put("guest_mode",0)
        })
        json.put("_gen_time", now)
        return ttEncrypt(gzip(json.toString().toByteArray(Charsets.UTF_8)))
    }

    /**
     * 注册请求头，逐项对齐官方冷启动抓包。**刻意不加签名头**。
     *
     * 官方 7.4.3.32 的 HTTP/2 抓包里 `x-neptune` 是
     * `-11|50:51:59`（不是早期的 `-8|...`）。`Accept-Encoding` 不能手动设置：
     * OkHttp 只有在自己添加这个头时才会透明解压响应，否则压缩 JSON 会被直接
     * 当 UTF-8 解析，表现为 `device_id=0` / 注册被拒绝。
     */
    fun headers(body: ByteArray, device: Map<String,String>, now: Long = System.currentTimeMillis()): Map<String,String> = linkedMapOf(
        "log-encode-type" to "gzip",
        "x-ss-req-ticket" to now.toString(),
        "x-vc-bdturing-sdk-version" to "4.0.3.cn",
        "Content-Type" to "application/octet-stream;tt-data=a",
        "x-ss-stub" to digest("MD5", body).hex().uppercase(),
        "x-ss-dp" to "8662",
        "x-tt-trace-id" to VendorConstants.traceId(),
        "User-Agent" to VendorConstants.headerSafe(
            device["user_agent"], VendorConstants.userAgent(
                LinkedHashMap(device).apply { put("version_code","74332") },
                video = true,
            )),
        "x-neptune" to "-11|50:51:59",
    )

    fun url(
        device: Map<String,String> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        host: String = HOST,
    ): String =
        "https://$host" + PATH + "?tt_data=a&cronet_version=8d40f833_2026-03-03"+
            "&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1"

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
        return Registered(
            device,
            install,
            json.optString("device_token",""),
            json.optString("klink_egdi",""),
            json.optInt("new_user",0)==1,
            cookies,
        )
    }

}
