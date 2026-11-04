// SPDX-License-Identifier: GPL-3.0-only
//
// 厂商常量：与 Android `core/VendorConstants.kt` 同源。
// 设备档案默认值就是那台 Redmi（25053RT47C / Android 16 / 1280*2772），
// 注册成功后会被平台下发的新身份覆盖（见 DeviceRegister / ContentRepository）。

import Foundation

enum Vendor {
    static let videoVersionCode = "71332"
    static let appVersionCode = "72232"
    private static let defaultBuild = "BP2A.250605.031.A3"

    /// 设备档案（query 里的 `device` 就是这一份）。
    static let device: [String: String] = [
        "iid": "1905892595382586",
        "device_id": "1905892595378490",
        "ac": "wifi",
        "channel": "update_64",
        "aid": "8662",
        "app_name": "novelread",
        "version_code": "71332",
        "version_name": "7.1.3.32",
        "device_platform": "android",
        "os": "android",
        "ssmix": "a",
        "device_type": "25053RT47C",
        "device_brand": "Redmi",
        "language": "zh",
        "os_api": "36",
        "os_version": "16",
        "manifest_version_code": "71332",
        "resolution": "1280*2772",
        "dpi": "520",
        "update_version_code": "71332",
        "host_abi": "arm64-v8a",
        "dragon_device_type": "phone",
        "pv_player": "71332",
        "compliance_status": "0",
        "need_personal_recommend": "1",
        "player_so_load": "1",
        "is_android_pad_screen": "0"
    ]

    /// App 路径（搜索 / 分类 / 账号）的五项版本号改成 72232。
    static let appDevice: [String: String] = {
        var values = device
        values["version_code"] = "72232"
        values["version_name"] = "7.2.2.32"
        values["manifest_version_code"] = "72232"
        values["update_version_code"] = "72232"
        values["pv_player"] = "72232"
        return values
    }()

    /// 只留可打印 ASCII：官改 ROM 的机型 / 固件串带汉字时，HTTP 头会直接抛错。
    static func headerSafe(_ value: String?, _ fallback: String) -> String {
        let filtered = (value ?? "").filter { $0.isASCII && $0.asciiValue! >= 0x20 && $0.asciiValue! <= 0x7e }
            .trimmingCharacters(in: .whitespaces)
        return filtered.isEmpty ? fallback : filtered
    }

    static func userAgent(_ device: [String: String], video: Bool) -> String {
        let version = video ? videoVersionCode : appVersionCode
        let os = headerSafe(device["os_version"], "16")
        let model = headerSafe(device["device_type"], "25053RT47C")
        let build = headerSafe(device["rom_version"], defaultBuild)
            .split(separator: " ").first.map(String.init) ?? defaultBuild
        if video {
            return "com.phoenix.read/\(version) (Linux; U; Android \(os); zh_CN; \(model); Build/\(build); Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
        }
        return "com.phoenix.read/\(version) (Linux; U; Android \(os); zh_CN; \(model); Build/\(build))"
    }

    /// AesV3 用的 4×256 字节 sbox（base64）。
    static let sbox: [UInt8] = Bytes.base64(
        "+n0Ia5xZs0sEXznQOEqRmQBnpiCf9U2Ccybu3xhmgzOAAxn72f6uqqmwUsYL83klTni0NqxdGieeiNu9PGPsSRXBMB/cuFbUbM3KCUPINaPvHvSW0vwOcnuUhNHqRVpiAj/TEoE0K91+5ijypUYTATsh9mE3KSoN7Yyvv51cuyR2D3XkU4nhmI2xmmVwT1RMWKtub4sjxAcRDLrPoKSO2AU9FLLadMPX577Wf95IFj6FkKFVt3dCIsmGUC4X+WQxLJvxbRxEaOPpqJOXyzJX6+Vxaq3AzMfF/WAdoi1Hp+JRaV56zgpBtpWP97mH4DoGEIq1+FvV8LyS/3wvwugbQOwb2r26mJEMsiuDQTRn+wrYdrVGBVlhI3WQhyrjUBVMrLF5667llUcEaPCGPVGLD8qO5LlO8hKCvA7V9+8oJc9bXelqVQLhM76T5/WtnT45JKji+hdX0HoNCDDWuKON/QeaxB5uImSX0h2wv0VmP2zd2yeApxHcpsVS+MC2yFwAc2B7oBkTqsk1SEvTpM2fmfMQREBUfin0Bh+iq6EvPPavhWI2IX9e3yAas7Tm/3KEj2UmlFp36kN4x0rMLBRrxuh0U/zUHM4xcAMYjJY4MonxOl/X+alpt2M3WMI7w3HLnpIBigtNiJu7T21v4P6lSd5WFgntnMEt7oF92XzRLkJbTcGmXepE/UVOG6E/0YnhfS+q26utWcuxzpooyeD2cDlK1/8w9d28VzsRjbLuALbmGlp8+d7EzS6Au7lMpZ+ECMZvQmzwJ+eLOpxR+2chdUExp8ogQyq3v9l68rX4jCwjg0+PYKAEEzcU4wHFY2ZcdIHfWL1okD3SszT0GZMyKdZJrg1L2AeerB4tC0C4crp2EHGo5FYdSP7lwkeR2ocmnR+Ia8CYviUJlzOjhRZef9xuVOn3qcjow3fQgivsAmKKkg4+sA8F8/GWeDiGNhg8JM8KtFPMYWWkx5TVFX5t73kiNRJqjlIGVXtGZFCV4gzt0xcDopuZ6xz8r9Rzafpf9ywev8jh8592gHFIqpStZPuJxmDDMrNN0uBE3V+oscdoIzTJbRJ/t+sVvqnReJOgDJKk10fjisJwqyZBmnmn2BSFj8BvVtCMEbkuPOKdzw7eA11GPs04Qw8zWtkaZWwiO/wwpojqN6K0jY5RnNZA7vn4hPSul+nKCkVnVwQvg1zVxcSCtqORmB9KrJaBbssbCQivGJVJfVTt+hYxOtq4ZvWl8f4QAQZ0zGPffCgl9s6yT4vlvIdpu4YhBwA25wtQWZsc6GJYGWHyvSdeuh3mmUI9DSq13Fsp8C1MU3tqc04/df9LoTUXVXI5INOw/e8C7Hd+5CvbkMEFnnrUUmskEw=="
    )

    static let hashTable: [Int32] = ([
        0xebb64faf, 0x7aadcc2, 0xcf3187bf, 0xe01138ff, 0x6d0bfcff, 0x5a30a3be, 0xb41ad638, 0x34180eb8,        0xf233eb6f, 0xb1a584cc, 0xccc30dc7, 0x47d1db51, 0xd55653de, 0x70a84fa1, 0x57473c12, 0xf76f0288,
        0x2c077f0a, 0xda0dcad0, 0xfbb86f6c, 0xfdc4cf00, 0x688a020d, 0xe676c6a6, 0x8cd6338b, 0x1a3c8d0e,
        0xcce8b06b, 0x6ad0ed0b, 0xa0522717, 0xdc71ac83, 0x2285db71, 0xd5b4dda6, 0x736f8650, 0x6560306c,
        0x617ce2a6, 0xe423417e, 0xa40e143, 0x544e4032, 0x88dffb2a, 0x716c1ae0, 0x4c467a88, 0x5b23bb3,
        0xe1d0b866, 0xbaa3dcb8, 0xae3374d3, 0xc3381a50, 0x1702f75b, 0xfe6da368, 0xf0b4cf48, 0x4e0ffbb8,
        0x72aad10d, 0x26c53a3d, 0xf2bce0f6, 0xb4557581, 0x4a257fdd, 0x8c3182a2, 0xab0b3b86, 0x3d5dfb14,
        0x4f103634, 0xd37b52d7, 0x444eff16, 0xeb0a33d1, 0x6ca86f6e, 0x284ba7, 0x8387cfa, 0x5fb37586
    ].map { Int32(bitPattern: UInt32($0)) })
}
