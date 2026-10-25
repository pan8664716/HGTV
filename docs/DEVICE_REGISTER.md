# 设备注册（`device_register`）

平台的内容接口**按设备放行**。所谓「不导入账号就用不了」，真实原因是这台设备没在服务端注册过；
注册本身是**匿名**的——不需要账号、密码或短信，一次请求即可。

本文件记录这条接口的协议、踩过的坑，以及本工程 `core/.../DeviceRegister.kt` 的实现依据。

## 1. 它解决什么问题

同一套签名、同一个请求，注册前后结果完全不同：

| 接口 | 未注册设备 | 注册后 |
| --- | --- | --- |
| App 搜索 `GET /reading/bookapi/search/tab/v` | `HTTP 200 + 0 字节`（从不返回数据） | `code 0`，一次 6–20 条、带 `has_more` 游标可翻页 |
| 播放 `POST /novel/player/video_model/v1/` | `101000 服务异常` | `code 0` + 完整播放模型 |

平台认的是 **`device_id` + `install_id` 这一对**，两者绑定：

- 用抓包里那对真机标识 → 搜索有数据；
- 只换其中任一个（哪怕形状完全正确）→ 立刻回空响应；
- 换任意一对全新标识 → 回空响应，**必须先注册**。

因此「随手编一对 16 位数字」不行：它只够让播放通过（见第 5 节），搜索一定拿不到数据。

## 2. 请求

```
POST https://log5-applog-lf.fqnovel.com/service/2/device_register/
     ?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1
```

`is.snssdk.com` 与 `log5-applog-lf.fqnovel.com` 等价，抓包里两个都在用。

### 请求头

| 头 | 值 |
| --- | --- |
| `Content-Type` | `application/octet-stream;tt-data=a` |
| `log-encode-type` | `gzip` |
| `x-ss-dp` | `8662` |
| `x-ss-req-ticket` | 毫秒时间戳 |
| `x-vc-bdturing-sdk-version` | `4.0.3.cn` |
| `lc` / `sdk-version` / `passport-sdk-version` | `101` / `2` / `5051452` |
| `x-neptune` | `-8|50:51:59:30:40:47:49:39` |
| `x-ss-stub` | **body 的 MD5，大写** |
| `User-Agent` | `com.phoenix.read/73932 (…)` |

**没有签名头**：这条请求不带 `x-gorgon` / `x-khronos` / `x-ladon` / `x-argus` / `x-helios` /
`x-medusa`，query 里也没有 `ts` / `_rticket`。加上反而会被判异常客户端。

### 请求体

`ttEncrypt(gzip(JSON))`，原始二进制（不是 base64）。

`ttEncrypt` 与 `MediaCrypto.decryptUrl` 用的是同一族常量：

```
74 63 05 10 00 00 || rnd[32] || AES-128-CBC(key, iv, PKCS7(SHA512(data) || data))
key || iv = SHA512( SHA512(rnd) || KDF_SALT )
KDF_SALT = 4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524
           f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58
```

JSON 结构（冷启动首注册的形态，50 个字段；`install_id` / `device_id` **不带**，由服务端下发）：

```json
{
  "magic_tag": "ss_app_log",
  "header": {
    "display_name": "红果免费短剧",
    "aid": 8662, "channel": "69310258a", "package": "com.phoenix.read",
    "version_code": 73932, "app_version": "7.3.9.32",
    "sdk_version": "3.7.0-rc.34-fanqie-xiaoshuo", "sdk_target_version": 29,
    "git_hash": "3a88f4e", "release_build": "b5e187f",
    "os": "Android", "os_version": "12", "os_api": 32,
    "device_model": "AOSP on Intel Platform", "device_brand": "SAMSUNG",
    "device_manufacturer": "Intel", "cpu_abi": "arm64-v8a",
    "density_dpi": 360, "display_density": "mdpi", "resolution": "1440x2560",
    "language": "zh", "timezone": 8, "access": "wifi",
    "carrier": "CHINA MOBILE", "mcc_mnc": "46000",
    "rom": "eng.root.20231116.160340", "rom_version": "W528JS release-keys",
    "cdid": "6f224cd8-…", "sig_hash": "56a962410c494bbaf0b58dba20cae56f",
    "openudid": "72390a64583f5577", "clientudid": "ddf2f77b-…",
    "region": "CN", "tz_name": "Asia/Shanghai", "tz_offset": 28800,
    "sim_region": "cn", "sim_serial_number": [],
    "oaid_may_support": false, "req_id": "ecd30b14-…",
    "device_platform": "android",
    "custom": { "account_region_src": "fallback", "host_bit": 64,
                "account_region": "cn", "dragon_device_type": "pad" },
    "pre_installed_channel": "", "apk_first_install_time": 1791533654675,
    "is_system_app": 0, "sdk_flavor": "china", "guest_mode": 0
  },
  "_gen_time": 1792085984275
}
```

## 3. 响应

```json
{
  "server_time": 1792086005,
  "device_id": 4175314086266699,
  "install_id": 4175314086270795,
  "device_id_str": "4175314086266699",
  "install_id_str": "4175314086270795",
  "device_token": "AAA3NH3APQNV2E4S7QLXYLLML36OQY4NYPALK5IHYFI7XKHBNQ7WJKTFAAG4FO4O5CPD6RXR6LTAE64WHPJ73E236BXFB276AIBLF7WUM6AC7WVEPYCJCXV37NQXSFT6VY27SKSPFB53SNBDVXVGRDI",
  "new_user": 0
}
```

同时下发 Cookie（**后续请求要带上**）：

```
store-region=cn-gd; store-region-src=did; install_id=4175314086270795; ttreq=1$1c2213245b92…
```

- `new_user` = 1 表示平台这次新建了一台设备；`0` 表示这台已存在。
- `device_id` / `install_id` 有时给数字、有时给字符串，两种都认；优先取 `*_str`。
- 判成功：两个值都存在且不为 `0`。**回 `device_id=0` 就是被拒**（body 形态或头不对）。

## 4. 三个踩过的坑

这三条都是照着抓包 HAR 复现时踩出来的，且都能稳定复现。

### 4.1 body 必须是原始二进制，不是 base64

抓包 HAR 里 `postData.text` 是 **base64 文本**，但同一条请求的 `content-length` 等于
**解码后**的字节数（1030 / 1222）。照着 `postData.text` 把 base64 文本当 body 发出去，
服务端一律回 `device_id=0`。

用原始二进制重放同一条抓包请求，立刻回真实 `device_id`：

```
base64 文本当 body  → {"device_id":0,"install_id":0}
原始二进制当 body    → {"device_id":4175314086266699,…,"device_token":"AAA3NH3A…"}
```

### 4.2 `os_version` 必须是字符串

写成数字就失败，且**只错这一个字段**：

| `os_version` | 结果 |
| --- | --- |
| `"12"`（字符串） | 成功 6/6 |
| `12`（数字） | 失败 6/6 |
| `"13"`（字符串） | 成功 6/6 |
| `13`（数字） | 失败 6/6 |

`os_api` / `density_dpi` / `sdk_target_version` 这些数字或字符串都收。

### 4.3 响应可能是压缩的

成功响应可能是 **brotli**（`Content-Encoding: br`）或 **gzip**；失败响应（`device_id=0`）反而是
明文 JSON。用 OkHttp 时**不要手动设 `accept-encoding`**：OkHttp 只在自己加这个头时才透明解压，
手动写死会让它跳过解压，body 到手上还是压缩字节、JSON 解析必失败。

## 5. 播放与搜索都只用注册下发的那一对

实测两者对身份的要求不同，但**本工程统一按注册身份来**：

| | 服务端的实际门槛 | 依据 |
| --- | --- | --- |
| 播放 | 只要 `device_id` **不在平台黑名单**里 | 随机 16 位数字 20/20 可播；仓库内置那个固定值（大量第三方共用）被拉黑，一律 `200 + 0 字节` |
| 搜索 | 必须是**服务端注册过的 `device_id` + `install_id`** | 随机值 0/6 命中；注册后 6/6 有数据 |

也就是说：播放对身份的**要求**比搜索松，但本机不再因此自己编一份随机身份——
`device_id` / `install_id` 只有一个来源，就是注册下发的这一对（`Library.deviceIdentity()`），
注册之前它是空表：

- 启动时异步注册一次（`MainActivity.ensureDeviceRegistered`）；
- **播放取播放地址之前再同步兜一次**（`registerDeviceOnce`；取地址那条路与下一集预取都走它），
  保证签名用的身份是注册过的这一对，而不是内置那份被拉黑的档案；
- 注册失败时播放照旧尝试（会走到「播放失败，可重试」那一步），下次启动或下一次播放再试。

并发调用由一把锁收敛成一次请求（启动那次与播放前那次可能同时进来）。

## 6. 第三方注册不出来吗

能注册出来。上面第 2 节那套头 + 原始二进制 body，就能稳定拿到全新设备：

```
全新 cdid / openudid / clientudid / req_id，连发 10 次 → 10 次都拿到新 device_id、new_user=1
```

早期「注册不出来」的记录（一律 `device_id=0`）全部是第 4.1 节那个 base64 body 的错。

## 7. 代码位置

| 位置 | 职责 |
| --- | --- |
| `core/.../DeviceRegister.kt` | `ttEncrypt` / 组 body / 组头 / 解析响应（无 Android 依赖） |
| `core/.../ContentRepository.kt` | `registerDevice()`：发请求、写回 `deviceIdentity` 与下发的 Cookie；搜索与播放共用这份身份。`inheritDevice()` 让播放预取那个仓库实例共用同一份身份与 Cookie |
| `app/.../Library.kt` | 落盘注册结果：`registered-device-id` / `registered-install-id`；`deviceIdentity()` 只认这一对，没注册过就是空表 |
| `app/.../MainActivity.kt` | `registerDeviceOnce()`：启动时异步补注册、播放前同步兜一次；成功后重新拉首页 |

### 升级兼容

身份只有一组键，判据是 `Library.deviceRegistered()`：

- `registered-device-id` / `registered-install-id`：只有注册成功才写。判据是
  `Library.deviceRegistered()`。

早期版本（未发布的那几版试跑）曾把「本机随机 `device-id` / `install-id`」当身份用，
播放与搜索都拿它签名。这两组键**不再被读取**（`Library` 里已经没有生成随机身份的代码），
升级上来的机器上遗留的那两个值就是一段死数据，不影响任何请求。

老版本升级上来时 `registered-device-id` 是空的，因此 `deviceRegistered()` 为 false：
启动后会补一次注册，播放前还会再兜一次；注册结果写进这一组键。
