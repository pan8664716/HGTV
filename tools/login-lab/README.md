# 登录试跑台（本机网页）

一个**只在你本机跑**的网页工具，用来试红果（`com.phoenix.read`）的登录、搜索与同步接口。
接口清单与签名材料来自 [`pan8664716/hgApi`](https://github.com/pan8664716/hgApi)；
签名实现与当前 APK 的 `android/core/.../Signer.kt` 逐行对齐。

目录约定见 [`tools/README.md`](../README.md)：本目录的基础网页工具与早期探针入库；
`frida/`、`hgmedusa/`、`re/` 以及一次性探针属于本机实验；登录态和采集产物统一放
`.runtime/`，三者都不入库。

## 结论先行（2026-10-15 实测）

**手机号验证码 / 扫码都走不通，能走通的是「在模拟器里用官方 App 登录，再把凭据取出来」**。
所以本工具的主路径是 `dump_device.py`：

```bash
python3 tools/login-lab/dump_device.py            # 从已登录的模拟器取凭据
python3 tools/login-lab/server.py --port 8770     # 起网页
# 浏览器打开 http://127.0.0.1:8770/ → 「登录」页 → 「从模拟器导入登录态」
```

拿到凭据后：**搜索接口一次 20 条、可翻页**，同步接口全部 `code=0 SUCCESS`。

## 为什么验证码 / 扫码走不通

| 路径 | 实测结果 |
| --- | --- |
| `send_code`（`type=1/3/8/24`…） | **能发出短信**。`type=24` = 登录码、`type=8` = 换密码、`type=19` = 初始密码、`type=1/3` = 绑定/换绑手机号 |
| `validate_code/v1` 换登录态 | 一律 `error_code=1 会话过期，请重新登录` —— 缺真机那套 passport 会话（`device_register` 后才有），补 `mobile_ticket` / `passport_csrf_token` / CSRF 头、换 GET/POST/form 都不变 |
| `login/v1`、`login/v2`、`login_or_register`、`register`、`combine_login`… | 一律 `error_code=16 该应用无权限`。aid 从 1 扫到 400、host 换 `is.snssdk.com` / `www.douyin.com` / `api5-normal-*`、加签名，返回一字不变 |
| 官方扫码 `/passport/open/qrcode/get/`、`/passport/web/get_qrcode/` | `error_code=16 该应用无权限`；aid=1128 是「此功能不支持当前 app」，6383/2906 判「网站存在安全风险」。`sso.snssdk.com` 这个老域名**已经不在 DNS 里**；`sso.douyin.com` 对 Python 请求一律 403（指纹层） |
| 你那个号 | 已绑定到「阴阳穿梭者吴远」，走 `type=1/3` 直接回 `1001 请先解除原有绑定关系，再换绑到当前用户账号` —— 这条线本来就是**绑定**手机号，不是登录 |

**结论**：`aid=8662` 的 passport 手机号流程是给已登录账号**绑手机**用的；手机号登录本身不在这条线上。
（`sso.douyin.com` 用真浏览器能过指纹层，网页版登录页也确实弹出了「扫码登录」——那条路要驱动浏览器，
本工具没做，留在下面「还能往下做的」里。）

## 凭据会不会过期 / 能不能自动刷新

**会过期，而且是「签发时间 + 固定时长」，不会滑动续期。**

`sid_guard` 的四段是 `sessionid | 签发时间戳 | max-age | 人类可读过期时间`：

```
cf98cf3cd41f0b56188dd3f2226a10c4 | 1792041020 | 5183999 | Mon, 14-Dec-2026 05:10:19 GMT
   sessionid                        签发 2026-10-15 05:10:20 UTC   +60 天      = 签发 + max-age
```

**注意第三段不是过期时间戳**，过期时间 = 第二段 + 第三段（实测两者相加正好等于第四段）。
当前这份凭据的有效期到 **2026-12-14 13:10:19（北京时间）**，约 60 天。

能不能自动续期？实测结论是**不能自动滑动**：

| 做法 | 结果 |
| --- | --- |
| 业务接口（`read_history` / `bookshelf` …） | **不回 `Set-Cookie`**，`sid_guard` 一字不变 |
| 唤醒模拟器里的 App 让它自己发一堆请求 | `sid_guard` **不变**（对比前后 cookieStore.xml 完全相同） |
| `/passport/session/refresh/` | 回 `{"message":"success"}`，但**不回任何 cookie**，是个空壳 |
| `/passport/account/refresh/`、`/passport/token/refresh/`、`/passport/sso/refresh/`、`/passport/web/refresh/` 等 | `error_code=16 该应用无权限` 或 404 |

所以本工具的刷新方式是：**在模拟器里的 App 还没掉登录时，重新 dump 一次凭据**。
`hgclient.refresh_from_emulator(min_days=7)` 就是这个逻辑——剩余天数低于阈值才重取，
`min_days=0` 强制重取。网页侧栏「重取凭据」按钮、或 `POST /api/refresh` 都是它。

侧栏会显示**剩余天数**（< 7 天标黄、过期标红）和**具体过期时间**。

> 到期之后怎么办：在模拟器里重新登录一次官方 App，再点「重取凭据」即可。
> 这也是为什么这条路比「手机号登录」现实——平台不给第三方客户端发会话。

## 从模拟器取凭据：取的是什么

前提：模拟器已用官方红果 App 登录、`adb` 连得上（MuMu 默认 `127.0.0.1:16384`，需要 root）。

| 来源文件 | 取出的东西 |
| --- | --- |
| `shared_prefs/cookieStore.xml` | `sessionid` / `sessionid_ss` / `sid_tt` / `uid_tt` / `d_ticket` / `sid_guard` / `session_tlb_tag` / `odin_tt` / `n_mh` / `ttreq` / `store-region` |
| `shared_prefs/applog_stats.xml` | `device_id`、`install_id`、`device_token`、`klink_egdi` |
| `shared_prefs/com.ss.android.deviceregister.utils.Cdid.xml` | `cdid` |
| `shared_prefs/snssdk_openudid.xml` | `openudid`（`clientudid`） |

`cookieStore.xml` 里存的是 Java 序列化的 `SerializableHttpCookie`，字段按
`TC_STRING(0x74) + u2 长度 + UTF-8` 排列，形如 `[name, value, domain, path]`
（`dump_device.py` 里的 `tc_strings()` 就是干这个的）。

### 两个坑

1. **设备档案不要换成模拟器那套**。实测用模拟器的 `device_id` / `SM-S9180` 机型反而会被平台限流
   （搜索回空 body），**仓库里那套固定设备参数 + 这份登录 Cookie 才是稳的**。
   所以 `dump_device.py` 只取凭据，签名仍用仓库档案（`device_profile.json` 留作记录）。
2. **搜索接口会间歇性回 `PARAM_INVALID` / 空 body**（约一半概率，与设备档案无关，同一请求重发即可）。
   `hgclient.search()` 内置了重试（每个 host 最多 6 次），实测 6/6 通过。

## 网页三块功能

| 页签 | 干什么 | 走的接口 |
| --- | --- | --- |
| 登录 | **从模拟器导入**（主路径）、粘 `x-tt-token` / 整段 Cookie、手机号+验证码、扫码（平台接口 + 本机中继） | 见上表 |
| 搜索 | 关键词搜索、`tab_type` 19/11、可翻页 | `GET /reading/bookapi/search/tab/v1/` |
| 同步 | 进度同步 / 进度列表 / 阅读历史 / 书架 / 书签 / 用户资料，一把抓 | `/reading/bookapi/read_progress/{sync,list}/v1/`、`/reading/bookapi/read_history/list/v1/`、`/reading/bookapi/bookshelf/list/v1/`、`/reading/reader/bookmark/list/`、`/reading/user/profile/get/v1/` |

侧栏显示会话状态、设备档案来源、请求日志（含 `x-tt-logid` 与 `X-Tt-Agw-Login`）。

登录态只写在 `tools/login-lab/.runtime/`，清空即删；不上传、不进备份。

## 同步接口实测（带登录态）

| 接口 | 结果 |
| --- | --- |
| `read_progress/sync`、`read_progress/list` | `code=0 SUCCESS`（内容为空：这个号还没看过剧） |
| `read_history/list` | `code=0 SUCCESS`，`total=0` |
| `bookshelf/list` | `code=0 SUCCESS` |
| `bookmark/list` | 必须带 `book_id`，缺了回 `PARAM_INVALID`；`sync_all` 会先从阅读历史里取一本 |
| `user/profile/get` | `code=100107 network err`（要带 `userId`） |

## 文件

| 文件 | 说明 |
| --- | --- |
| `dump_device.py` | 从模拟器 / 真机 dump 登录凭据与设备档案 |
| `server.py` | 本机 HTTP 服务：静态页 + `/api/*` + 手机扫码中继页 |
| `static/index.html` | 网页界面（登录 / 搜索 / 同步 + 会话与请求日志侧栏） |
| `hgclient.py` | 会话与接口封装：登录、搜索（含重试）、同步 |
| `hgsign.py` | 签名：`Signer.kt` 的 Python 移植（gorgon 分支）+ hgApi 的 `ss_queries` 模式 + `ttEncrypt` |
| `hgvendor.py` | 从 `VendorConstants.kt` 机械提取的设备参数、UA、hash 表、sbox |

启动参数：`--mode ss` 切到 hgApi 的 `ss_queries` 签名；`--device emulator` 用模拟器 dump 的设备档案
（默认 `repo`，理由见上）。

## 还能往下做的

* **真浏览器扫码**：`sso.douyin.com` 的网页登录页在真浏览器里能出「扫码登录」，可以驱动浏览器取 QR、
  轮询状态，最后把 `sessionid` 回灌到本工具（这条路不依赖模拟器）。
* **Frida 直取**：模拟器里已有 `frida-server`，挂 `hook_hongguo.js` 可以直接 dump 每次请求的
  `ss_queries` / `ttEncrypt` 明文密文，用于对拍 `hgsign.py`。
* **书签 / 收藏写入**：现在只读；写回要先在真机上确认字段（`book_id` / `identify_data` 等）。
