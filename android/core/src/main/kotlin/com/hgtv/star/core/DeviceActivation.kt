// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * 设备注册后的激活握手。
 *
 * `device_register` 只创建身份；平台还要收到一次 App 日志 SDK 的启动上报或日志配置拉取，
 * `app_alert_check` 的 `data.is_activated` 才会变成 1。只注册不激活的设备会先临时可用，
 * 之后被平台回收，表现为内容接口持续 `HTTP 200 + 空 body`。
 *
 * 这里只保留官方启动链路里最小、实测有效的两条请求：
 * `POST /service/2/app_log/`（launch）与 `POST /service/2/log_settings/`。
 */
object DeviceActivation {
    const val HOST = "https://log5-applog.fqnovel.com"
    const val APP_LOG_PATH = "/service/2/app_log/"
    const val LOG_SETTINGS_PATH = "/service/2/log_settings/"

    private const val AB_SDK_VERSION = "18281636,17820223,17330565"
    private const val DISPLAY_NAME = "红果免费短剧"
    private const val PACKAGE_NAME = "com.phoenix.read"
    private const val APP_VERSION = "7.3.9.32"
    private const val VERSION_CODE = 73932
    private const val SIG_HASH = "56a962410c494bbaf0b58dba20cae56f"
    private const val RELEASE_BUILD = "b5e187f"
    private const val SDK_VERSION = "2.14.0"

    fun appLogPayload(device: Map<String, String>, now: Long = System.currentTimeMillis()): ByteArray {
        val session = UUID.randomUUID().toString()
        val datetime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date(now))
        val launch = JSONArray().put(JSONObject().apply {
            put("datetime", datetime)
            put("session_id", "$session#0")
            put("local_time_ms", now)
            put("tea_event_index", 0)
            put("is_background", true)
            put("ab_sdk_version", AB_SDK_VERSION)
        })
        val json = JSONObject().apply {
            put("launch", launch)
            put("magic_tag", "ss_app_log")
            put("header", header(device))
            put("_gen_time", now)
        }
        return DeviceRegister.ttEncrypt(gzip(json.toString().toByteArray(Charsets.UTF_8)))
    }

    fun logSettingsPayload(device: Map<String, String>, now: Long = System.currentTimeMillis()): ByteArray {
        val json = JSONObject().apply {
            put("magic_tag", "ss_app_log")
            put("header", header(device))
            put("_gen_time", now)
        }
        return DeviceRegister.ttEncrypt(gzip(json.toString().toByteArray(Charsets.UTF_8)))
    }

    fun headers(body: ByteArray, device: Map<String, String>, now: Long = System.currentTimeMillis()): Map<String, String> =
        linkedMapOf(
            "log-encode-type" to "gzip",
            "x-ss-req-ticket" to now.toString(),
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "Content-Type" to "application/octet-stream;tt-data=a",
            "x-ss-stub" to digest("MD5", body).hex().uppercase(),
            "x-ss-dp" to "8662",
            "x-tt-trace-id" to VendorConstants.traceId(),
            "User-Agent" to VendorConstants.headerSafe(
                device["user_agent"],
                VendorConstants.userAgent(
                    LinkedHashMap(device).apply { put("version_code", VERSION_CODE.toString()) },
                    video = true,
                ),
            ),
        )

    fun url(path: String, device: Map<String, String>): String {
        val id = device["device_id"].orEmpty()
        val iid = device["iid"].orEmpty().ifBlank { device["install_id"].orEmpty() }
        return HOST + path + "?version_code=$VERSION_CODE&device_platform=android" +
            "&device_id=$id&aid=8662&iid=$iid&tt_data=a"
    }

    /** 两种响应都只认 `ss_app_log` 的成功语义；`log_settings` 不带 `message`。 */
    fun accepted(body: String): Boolean {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        return json.optString("message").equals("success", ignoreCase = true) ||
            json.optString("magic_tag") == "ss_app_log"
    }

    private fun header(device: Map<String, String>): JSONObject {
        fun value(key: String, fallback: String) = device[key]?.takeIf { it.isNotBlank() } ?: fallback
        val brand = value("device_brand", "Xiaomi")
        val manufacturer = device["device_manufacturer"]?.takeIf { it.isNotBlank() } ?: when {
            brand.contains("samsung", ignoreCase = true) -> "Samsung"
            brand.contains("xiaomi", ignoreCase = true) || brand.contains("redmi", ignoreCase = true) -> "Xiaomi"
            brand.contains("huawei", ignoreCase = true) || brand.contains("honor", ignoreCase = true) -> "HUAWEI"
            brand.contains("oppo", ignoreCase = true) -> "OPPO"
            brand.contains("vivo", ignoreCase = true) -> "vivo"
            else -> brand
        }
        val resolution = run {
            val parts = value("resolution", "2160*3840").split('*', 'x')
            val a = parts.getOrNull(0)?.toIntOrNull()
            val b = parts.getOrNull(1)?.toIntOrNull()
            if (a != null && b != null) "${minOf(a, b)}x${maxOf(a, b)}" else "2160x3840"
        }
        return JSONObject().apply {
            put("openudid", value("openudid", ""))
            put("sdk_version", SDK_VERSION)
            put("package", PACKAGE_NAME)
            put("channel", value("channel", "69310258a"))
            put("display_name", DISPLAY_NAME)
            put("app_version", APP_VERSION)
            put("version_code", VERSION_CODE)
            put("timezone", 8)
            put("access", "wifi")
            put("os", "Android")
            put("os_version", value("os_version", "12"))
            put("os_api", value("os_api", "32").toIntOrNull() ?: 32)
            put("device_model", value("device_type", "2304FPN6DC"))
            put("device_brand", brand)
            put("device_manufacturer", manufacturer)
            put("language", "zh")
            put("resolution", resolution)
            put("display_density", value("display_density", "mdpi"))
            put("density_dpi", value("dpi", "540").toIntOrNull() ?: 540)
            put("carrier", value("carrier", "CHINA MOBILE"))
            put("mcc_mnc", value("mcc_mnc", "46000"))
            put("clientudid", value("clientudid", ""))
            put("install_id", value("iid", value("install_id", "")))
            put("device_id", value("device_id", ""))
            put("sig_hash", SIG_HASH)
            put("aid", 8662)
            put("push_sdk", JSONArray(listOf(1, 2, 7, 8, 9)))
            put("rom", value("rom", "1135"))
            put("release_build", RELEASE_BUILD)
            put("update_version_code", VERSION_CODE)
            put("manifest_version_code", VERSION_CODE)
            put("app_version_minor", APP_VERSION)
            put("cpu_abi", "arm64-v8a")
            put("not_request_sender", 0)
            put("rom_version", value("rom_version", "W528JS release-keys"))
            put("region", "CN")
            put("tz_name", "Asia/Shanghai")
            put("tz_offset", 28800)
            put("sim_region", "cn")
            put("custom", JSONObject().apply {
                put("account_region_src", "fallback")
                put("host_bit", 64)
                put("account_region", "cn")
                put("dragon_device_type", value("dragon_device_type", "phone"))
            })
            put("platform", "Android")
            put("cdid", value("cdid", ""))
            put("sim_serial_number", JSONArray())
            put("version", 1785490561)
        }
    }

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(data) }
        }.toByteArray()
}
