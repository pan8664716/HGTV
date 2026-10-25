// Derived from drpys 22261ad; GPL-3.0. See NOTICE.md.
package com.hgtv.star.core

internal object VendorConstants {
    /**
     * 两个 UA 的**模板**：机型 / 系统版本 / 固件串三处按设备档案填。
     *
     * 官方 App 的 UA 形如
     * `com.phoenix.read/<version_code> (Linux; U; Android <os_version>; zh_CN; <机型>; Build/<固件>)`，
     * 也就是「同一台设备」的又一处体现。早先这里把机型写死成 `25053RT47C`、系统写死 `Android 16`，
     * 与本机真实设备（以及 query 里的 `device_type`）对不上——同一次会话里出现两台设备。
     *
     * 模板里的三个占位符由 [userAgent] 填：
     * * `%VC%` —— 版本号（视频路径 71332 / App 路径 72232）；
     * * `%OS%` —— `os_version`；
     * * `%MODEL%` —— `device_type`（机型）；
     * * `%BUILD%` —— 固件串（`rom_version`），认不出来时回落到模板里的默认值。
     */
    private const val VIDEO_UA_TEMPLATE = "com.phoenix.read/%VC% (Linux; U; Android %OS%; zh_CN; %MODEL%; Build/%BUILD%; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    private const val APP_UA_TEMPLATE = "com.phoenix.read/%VC% (Linux; U; Android %OS%; zh_CN; %MODEL%; Build/%BUILD%)"
    /** 视频路径的版本号（`version_code`）。 */
    const val VIDEO_VERSION_CODE = "71332"
    /** App 路径（搜索 / 账号 / 分类）的版本号。 */
    const val APP_VERSION_CODE = "72232"
    /** 档案里没有固件串时 UA 里用的那个（与内置档案同源）。 */
    private const val DEFAULT_BUILD = "BP2A.250605.031.A3"
    /**
     * 把档案里的串收成「能放进 HTTP 头」的形态：只留可打印 ASCII（`0x20..0x7e`）。
     *
     * 为什么必须有这一层：OkHttp 的 `Headers.checkValue` 只认这一段，头值里出现
     * `0x7eaa`（「纪」）这类字符时，`Request.Builder.header()` 会直接抛
     * `IllegalArgumentException: Unexpected char 0x7eaa at 93 in User-Agent`，
     * 请求根本发不出去——注册就表现为「设备注册失败」，根因却只是这台机器的
     * `Build.DISPLAY` / `Build.MODEL` 带了汉字（官改 ROM、杂牌机常见）。
     * 过滤后为空则回落 [fallback]：宁可报成内置那台设备，也不拼出畸形 UA。
     */
    fun headerSafe(value: String?, fallback: String): String =
        value.orEmpty().filter { it in '\u0020'..'\u007e' }.trim().ifBlank { fallback }

    /**
     * 按设备档案拼 UA；[video] 决定用哪条路径的版本号。
     *
     * 档案里没有的字段一律回落到内置值（机型 `25053RT47C` / 系统 `16` / 固件 [DEFAULT_BUILD]），
     * 因此不注入档案时行为与改动前逐字节一致。
     *
     * 三个占位符都过 [headerSafe]：本机档案来自 `Build.*`，官改 ROM 会往里塞汉字，
     * 一旦原样进头就是上面那条 `Unexpected char`（见 [headerSafe] 的注释）。
     */
    fun userAgent(device: Map<String,String>, video: Boolean): String {
        val version = if(video) VIDEO_VERSION_CODE else APP_VERSION_CODE
        val os = headerSafe(device["os_version"], "16")
        val model = headerSafe(device["device_type"], "25053RT47C")
        // `rom_version` 是 `Build.DISPLAY`（形如 `BP2A.250605.031.A3`）；
        // 它有时带 `release-keys` 后缀（`W528JS release-keys`），UA 里不带，因此截掉。
        // 截断放在过滤之后：`P230_纪念版 N2G47H` 过滤成 `P230_ N2G47H`，再取首个 token。
        val build = headerSafe(device["rom_version"], DEFAULT_BUILD)
            .substringBefore(" ").ifBlank { DEFAULT_BUILD }
        return (if(video) VIDEO_UA_TEMPLATE else APP_UA_TEMPLATE)
            .replace("%VC%", version).replace("%OS%", os)
            .replace("%MODEL%", model).replace("%BUILD%", build)
    }
    /** 没有档案时的兜底 UA（内置那一台设备）。 */
    val VIDEO_UA: String get() = userAgent(emptyMap(), video = true)
    val APP_UA: String get() = userAgent(emptyMap(), video = false)
    val device = linkedMapOf(
        "iid" to "1905892595382586",
        "device_id" to "1905892595378490",
        "ac" to "wifi",
        "channel" to "update_64",
        "aid" to "8662",
        "app_name" to "novelread",
        "version_code" to "71332",
        "version_name" to "7.1.3.32",
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "device_type" to "25053RT47C",
        "device_brand" to "Redmi",
        "language" to "zh",
        "os_api" to "36",
        "os_version" to "16",
        "manifest_version_code" to "71332",
        "resolution" to "1280*2772",
        "dpi" to "520",
        "update_version_code" to "71332",
        "host_abi" to "arm64-v8a",
        "dragon_device_type" to "phone",
        "pv_player" to "71332",
        "compliance_status" to "0",
        "need_personal_recommend" to "1",
        "player_so_load" to "1",
        "is_android_pad_screen" to "0"
    )
    val sbox = decode64("+n0Ia5xZs0sEXznQOEqRmQBnpiCf9U2Ccybu3xhmgzOAAxn72f6uqqmwUsYL83klTni0NqxdGieeiNu9PGPsSRXBMB/cuFbUbM3KCUPINaPvHvSW0vwOcnuUhNHqRVpiAj/TEoE0K91+5ijypUYTATsh9mE3KSoN7Yyvv51cuyR2D3XkU4nhmI2xmmVwT1RMWKtub4sjxAcRDLrPoKSO2AU9FLLadMPX577Wf95IFj6FkKFVt3dCIsmGUC4X+WQxLJvxbRxEaOPpqJOXyzJX6+Vxaq3AzMfF/WAdoi1Hp+JRaV56zgpBtpWP97mH4DoGEIq1+FvV8LyS/3wvwugbQOwb2r26mJEMsiuDQTRn+wrYdrVGBVlhI3WQhyrjUBVMrLF5667llUcEaPCGPVGLD8qO5LlO8hKCvA7V9+8oJc9bXelqVQLhM76T5/WtnT45JKji+hdX0HoNCDDWuKON/QeaxB5uImSX0h2wv0VmP2zd2yeApxHcpsVS+MC2yFwAc2B7oBkTqsk1SEvTpM2fmfMQREBUfin0Bh+iq6EvPPavhWI2IX9e3yAas7Tm/3KEj2UmlFp36kN4x0rMLBRrxuh0U/zUHM4xcAMYjJY4MonxOl/X+alpt2M3WMI7w3HLnpIBigtNiJu7T21v4P6lSd5WFgntnMEt7oF92XzRLkJbTcGmXepE/UVOG6E/0YnhfS+q26utWcuxzpooyeD2cDlK1/8w9d28VzsRjbLuALbmGlp8+d7EzS6Au7lMpZ+ECMZvQmzwJ+eLOpxR+2chdUExp8ogQyq3v9l68rX4jCwjg0+PYKAEEzcU4wHFY2ZcdIHfWL1okD3SszT0GZMyKdZJrg1L2AeerB4tC0C4crp2EHGo5FYdSP7lwkeR2ocmnR+Ia8CYviUJlzOjhRZef9xuVOn3qcjow3fQgivsAmKKkg4+sA8F8/GWeDiGNhg8JM8KtFPMYWWkx5TVFX5t73kiNRJqjlIGVXtGZFCV4gzt0xcDopuZ6xz8r9Rzafpf9ywev8jh8592gHFIqpStZPuJxmDDMrNN0uBE3V+oscdoIzTJbRJ/t+sVvqnReJOgDJKk10fjisJwqyZBmnmn2BSFj8BvVtCMEbkuPOKdzw7eA11GPs04Qw8zWtkaZWwiO/wwpojqN6K0jY5RnNZA7vn4hPSul+nKCkVnVwQvg1zVxcSCtqORmB9KrJaBbssbCQivGJVJfVTt+hYxOtq4ZvWl8f4QAQZ0zGPffCgl9s6yT4vlvIdpu4YhBwA25wtQWZsc6GJYGWHyvSdeuh3mmUI9DSq13Fsp8C1MU3tqc04/df9LoTUXVXI5INOw/e8C7Hd+5CvbkMEFnnrUUmskEw==")
    val hashTable = intArrayOf(0xebb64faf.toInt(),0x7aadcc2.toInt(),0xcf3187bf.toInt(),0xe01138ff.toInt(),0x6d0bfcff.toInt(),0x5a30a3be.toInt(),0xb41ad638.toInt(),0x34180eb8.toInt(),0xf233eb6f.toInt(),0xb1a584cc.toInt(),0xccc30dc7.toInt(),0x47d1db51.toInt(),0xd55653de.toInt(),0x70a84fa1.toInt(),0x57473c12.toInt(),0xf76f0288.toInt(),0x2c077f0a.toInt(),0xda0dcad0.toInt(),0xfbb86f6c.toInt(),0xfdc4cf00.toInt(),0x688a020d.toInt(),0xe676c6a6.toInt(),0x8cd6338b.toInt(),0x1a3c8d0e.toInt(),0xcce8b06b.toInt(),0x6ad0ed0b.toInt(),0xa0522717.toInt(),0xdc71ac83.toInt(),0x2285db71.toInt(),0xd5b4dda6.toInt(),0x736f8650.toInt(),0x6560306c.toInt(),0x617ce2a6.toInt(),0xe423417e.toInt(),0xa40e143.toInt(),0x544e4032.toInt(),0x88dffb2a.toInt(),0x716c1ae0.toInt(),0x4c467a88.toInt(),0x5b23bb3.toInt(),0xe1d0b866.toInt(),0xbaa3dcb8.toInt(),0xae3374d3.toInt(),0xc3381a50.toInt(),0x1702f75b.toInt(),0xfe6da368.toInt(),0xf0b4cf48.toInt(),0x4e0ffbb8.toInt(),0x72aad10d.toInt(),0x26c53a3d.toInt(),0xf2bce0f6.toInt(),0xb4557581.toInt(),0x4a257fdd.toInt(),0x8c3182a2.toInt(),0xab0b3b86.toInt(),0x3d5dfb14.toInt(),0x4f103634.toInt(),0xd37b52d7.toInt(),0x444eff16.toInt(),0xeb0a33d1.toInt(),0x6ca86f6e.toInt(),0x284ba7.toInt(),0x8387cfa.toInt(),0x5fb37586.toInt())
}
