# 红果手机号验证码登录 · 完整实现与注意事项

> 适用对象：本项目 `com.hgtv.star`（TV 端）的账号登录模块
> 核心实现：`android/core/src/main/kotlin/com/hgtv/star/core/PhoneLogin.kt`
> 设备注册：`android/core/src/main/kotlin/com/hgtv/star/core/DeviceRegister.kt`
> 签名：`android/core/src/main/kotlin/com/hgtv/star/core/PassportSigner.kt`
> UI 流程：`android/app/src/main/kotlin/com/hgtv/star/tv/TvTools.kt`
> 最后验证：2026-10-21 21:34，真机 + 真短信，一次通过

---

## 0. 一句话结论

红果的手机号登录是**三步 + 一个可选的二次验证**：

```
设备注册 → 发验证码 → 验证码换会话
                     └─ 2046 时插入「上行短信二次验证」→ 通过后重放「验证码换会话」
```

二次验证有**两套完全不同的实现**，必须按回包结构分流：

| 结构特征 | 归属 | 确认方式 | 是否换票 |
|---|---|---|---|
| `data.verify_center_secondary_decision_conf` | BdTuring（rmc 通用页） | `gen_verify_ticket` → `verify` | 是 |
| `data.url` + `data.verify_ways` | 红果自有页 `passport-second-verification.html` | 直接 `verify` | **否** |

红果线上走的是**第二套**。把它当成第一套，就会永远卡在
`gen_verify_ticket → 1 会话过期`，然后 `verify → 1045 认证失败`。

---

## 1. 总体时序

```
┌────────┐          ┌───────────────────────────────┐          ┌──────────┐
│ 客户端  │          │ Passport (api5-normal-lq...)  │          │ 用户手机  │
└───┬────┘          └───────────────┬───────────────┘          └────┬─────┘
    │ ① device_register             │                               │
    │──────────────────────────────>│                               │
    │<── device_id / install_id ────│                               │
    │                               │                               │
    │ ② POST /passport/mobile/send_code/v1/                         │
    │──────────────────────────────>│                               │
    │<── success + mobile_ticket ───│──── 下发短信 ─────────────────>│
    │                               │                               │
    │ ③ POST /passport/mobile/sms_login/  (code)                    │
    │──────────────────────────────>│                               │
    │                               │                               │
    │   ┌── 正常：Set-Cookie sessionid / X-Tt-Token → 登录成功       │
    │   └── 2046：要求二次验证                                      │
    │<── error_code=2046 ───────────│                               │
    │                               │                               │
    │ ④ 弹出「发送 YZ 到 9515211003」 ──────────────────────────────>│
    │                               │<──── 用户发送 YZ ─────────────│
    │                               │                               │
    │ ⑤ POST /passport/upsms/verify/ (轮询)                         │
    │──────────────────────────────>│                               │
    │<── 1045 认证失败（还没看到）──│                               │
    │<── success + ticket ──────────│                               │
    │                               │                               │
    │ ⑥ 重放 ③，带 passport_mfa_retry_tag + sms_code_key            │
    │──────────────────────────────>│                               │
    │<── success + Set-Cookie ──────│                               │
    └───────────────────────────────┘
```

三种终态：

1. **直接登录**：③ 成功，拿到 `sessionid` / `X-Tt-Token`。
2. **二次验证后登录**：③ 回 2046 → ⑤ 拿到 ticket → ⑥ 重放成功。
3. **发码阶段就要二次验证**：② 就回 2046 → 用户发短信 → 通过后**重新发码**，再用新码走 ③。

---

## 2. 身份与设备模型

### 2.1 必须稳定的字段

| 字段 | 来源 | 作用 |
|---|---|---|
| `device_id` | `device_register` 响应 | Passport 认设备的主键 |
| `iid`（install_id） | `device_register` 响应 | 安装实例主键 |
| `cdid` / `openudid` | 本机生成/持久化 | 设备指纹补充 |
| `store-region` Cookie | `device_register` 的 `Set-Cookie` | 区域；后续 `X-Tt-Store-Region` 必须与它一致 |

> **不要每次登录都重新注册。** 设备指纹一变，风控评分立刻升高，2046 的概率显著上升。
> 本项目把注册结果持久化在 `AccountCredentials.device` 里，登录时复用。

### 2.2 应用标识

```
aid          = 8662
app_name     = novelread
version_code = 73532
version_name = 7.3.5.32
package      = com.phoenix.read（官方） / com.hgtv.star（本项目）
```

### 2.3 会话凭据

登录成功后可能同时拿到：

- `Set-Cookie`: `sessionid`、`sid_guard`、`uid_tt`、`d_ticket`、`sid_tt` …
- 响应头 `X-Tt-Token`

两者是并行凭证，都要保存。`AccountCredentials` 与导入 `cookieStore.xml` 的那份同构，
可以直接交给 `ContentRepository.credentials`。

---

## 3. 逐步协议

### 3.0 设备注册（前置，只需一次）

```
POST https://log.snssdk.com/service/2/device_register/
Content-Type: application/octet-stream;tt-data=a
log-encode-type: gzip
x-ss-dp: 8662
x-ss-stub: <body 的 MD5，大写>
body: ttEncrypt(gzip(JSON))
```

响应：

```json
{"server_time":...,"device_id":"...","install_id":"...","device_token":"...","new_user":0}
```

并下发 `store-region` / `store-region-src=did` / `install_id` / `ttreq` 等 Cookie。

细节见 `DeviceRegister.kt` 的类注释。三个坑：

1. body 是原始二进制，不是 base64 文本；
2. 响应可能 brotli 压缩；
3. **不要加签名头**，这条请求官方就是没有签名的。

---

### 3.1 发验证码

```
POST /passport/mobile/send_code/v1/
```

表单（全部 `application/x-www-form-urlencoded`）：

| 字段 | 值 | 说明 |
|---|---|---|
| `mobile` | `encodeField("+86" + phone)` | XOR 0x05 后转 hex |
| `account_sdk_source` | `app` | 固定 |
| `passport_support_flow` | `captcha,verify` | 必须含 `verify` |
| `mix_mode` | `1` | 影响后续 `sms_code_key` 编码 |
| `auto_read` | `0` | 固定 |
| `unbind_exist` | `34` | 实测固定值 |
| `type` | `encodeField("24")` → `3731` | 登录场景 |

成功响应：

```json
{"data":{"log_id":"...","mobile":"176******36","mobile_ticket":"...","retry_time":60},"message":"success"}
```

注意：

- `mobile_ticket` 是**一次性**的，会在下一步 `sms_login` 被消费。
- `retry_time` 是平台要求的重发间隔，必须遵守。
- 这一步也可能直接回 2046（发码阶段就要二次验证），处理方式见 3.3。

---

### 3.2 验证码换会话

```
POST /passport/mobile/sms_login/
```

首次请求：

| 字段 | 值 |
|---|---|
| `mobile` | `encodeField("+86" + phone)` |
| `code` | `encodeField(code)` |
| `account_sdk_source` | `app` |
| `passport_support_flow` | `captcha,verify` |
| `mix_mode` | `1` |

成功：响应 `data` 带 `user_id` 等，同时 `Set-Cookie` 下发 `sessionid`。

失败但要求二次验证：

```json
{
  "data": {
    "biz_params": {"passport_mfa_retry_tag":"1","sms_code_key":"..."},
    "common_params": {
      "copywriting":"{...}",
      "copywriting_key":"sms_login",
      "ies_safety_diversion_tag":"mfa",
      "new_verify_flow":""
    },
    "encrypt_uid":"...",
    "error_code":2046,
    "event_params":{"log_id":"...","verify_reason":"ato","verify_scene":"sms_login"},
    "extra_params":{"fold_verify_way":"0","log_id":"...","use_server_sort":"0"},
    "mobile_ticket":"...",
    "pack_verify_way":true,
    "passport_scene":"login",
    "schema":"dragon8662://webview?...",
    "sms_code_key":"...",
    "url":"https://.../passport-second-verification.html",
    "user_info":{...},
    "verify_scene_desc":"为保证帐号安全，请完成身份验证",
    "verify_ways":[
      {"verify_way":"mobile_up_sms_verify","channel_mobile":"9515211003",
       "mobile":"176******36","sms_content":"YZ"}
    ]
  },
  "message":"error"
}
```

**关键：这张验证码在第一次 `sms_login` 时已经被消费。** 后面的重放不是「再登一次」，
而是平台明确允许的带 `biz_params` 重放（见 3.6）。

---

### 3.3 2046 分流：先看回包长什么样

```
data.verify_center_secondary_decision_conf 存在？
├─ 是 → 走 BdTuring rmc 链路（3.5）
└─ 否 → 看 data.url / data.verify_ways
        └─ url 含 passport-second-verification → 红果自有链路（3.4，主线）
```

判定代码（`parseUpsms`）：

```kotlin
val novelread = data.optString("url").contains("passport-second-verification")
    || (data.optString("url").isNotBlank() && ways != null)
```

两条链路**不能混用**：

- 红果链路没有 `gen_verify_ticket`；
- rmc 链路没有 `verify_ways` / `biz_params`。

---

### 3.4 红果自有二次验证（主线）

#### 3.4.1 页面信息

```
url    = https://lf-normal-gr-sourcecdn.bytegecko.com/.../passport-second-verification.html
schema = dragon8662://webview?hideStatusBar=1&hideNavigationBar=1&...
```

页面 JS（`passport-second-verification.*.js`）里**没有** `gen_verify_ticket`。
它把原生传进来的 `data` 直接当作 `verify_decision`：

```js
commonParams = {
  encrypt_uid:    data.encrypt_uid,
  verify_ticket:  data.verify_ticket || "",
  biz_params:     data.biz_params,
  verify_scene:   data.event_params.verify_scene,    // "sms_login"
  verify_reason:  data.event_params.verify_reason,   // "ato"
  ...data.common_params 去掉 copywriting,
  new_authn_sdk_version: "1.1.31",
  ...data.event_params 每项加 "new_authn_sdk_" 前缀
}
```

#### 3.4.2 三个端点怎么选

| 条件 | 端点 | 额外字段 |
|---|---|---|
| `verify_ways[].is_oper_staff_user == "true"` | `/passport/upsms/chain_mobile/verify/` | `upstream_verify_type=1` |
| `data.is_complex_sms == true` | `/passport/upsms/safe_mobile/verify/` | `upstream_verify_type=1` |
| 其它（默认） | `/passport/upsms/verify/` | 无 |

#### 3.4.3 请求体（实测有效）

```
encrypt_uid=...
verify_ticket=                         ← 通常为空串，不是 mobile_ticket
biz_params={"passport_mfa_retry_tag":"1","sms_code_key":"..."}
verify_scene=sms_login
verify_reason=ato
copywriting_key=sms_login
ies_safety_diversion_tag=mfa
new_verify_flow=
new_authn_sdk_version=1.1.31
new_authn_sdk_log_id=...
new_authn_sdk_verify_reason=ato
new_authn_sdk_verify_scene=sms_login
```

> 没有 `verify_data`，没有 `aid/did`（它们在 query 里），也没有 `verify_host`。

#### 3.4.4 响应语义

| 响应 | 含义 | 处理 |
|---|---|---|
| `error_code=1045 认证失败` | 平台找到了这次挑战，但还没看到短信 | 继续轮询 |
| `{"data":{"registered":true,"ticket":"..."},"message":"success"}` | 认到了 | 拿 ticket 重放 sms_login |
| `error_code=3 缺少参数` | body 形状不对（例如 encrypt_uid 假的/缺失） | 报错，不要无限重试 |
| `error_code=1 会话过期` | 挑战已死 | 让用户重新发码 |

实测对照：

```
真实 encrypt_uid + 正确 body → 1045
伪造 encrypt_uid + 同样 body → 3
```

#### 3.4.5 轮询节奏

官方页面：点「我已发送」后 **2 秒一次，最多 4 次**。
本项目（电视端）：页面出现后 **8 秒**才第一次问，之后 **4 秒**一次，最多 **6 次**（≈24 秒）。

为什么首问要延迟：用户扫码 → 切短信 → 按发送需要时间。页面一出现就问，会在用户还没动作时
就消耗掉重试次数；一旦失败还会把页面关掉（历史 bug：页面只显示 53 毫秒）。

---

### 3.5 BdTuring rmc 链路（兼容分支，红果线上不走）

只有当回包带 `data.verify_center_secondary_decision_conf` 时才用：

```
verify_ticket 存在？
├─ 是 → 直接 POST /passport/upsms/verify/
└─ 否 → POST /passport/upsms/gen_verify_ticket/（body = 整份 N，不带 verify_ticket）
         → 拿返回的 verify_ticket 再 POST /passport/upsms/verify/
```

要点：

1. `verify_data` 必须是 `risk_info` 的**原始字符串**，不能重新序列化。
2. 换票请求**不能**带 `mobile_ticket`（它已被 `sms_login` 消费，带上必回 `1 会话过期`）。
3. `verify_data` 为空时才拼最小 JSON。
4. H5 的 N 参数来自原生 `vb0.c.a()`：`aid/lang/app_name/locale/ch/channel/iid/vc/app_verison/did/region/...`。
   本项目只为兼容保留，主线不需要。

---

### 3.6 通过后的重放

官方逻辑（`ai1.c` → `com.bytedance.sdk.account.impl.w.a(map,true,true)`）：

- 把 `data.biz_params`（+ H5 的 `extraBizParams`）**并回原始请求参数表**；
- 若 `mix_mode=1` 且存在 `sms_code_key`，对该值做 **XOR 0x05 编码**；
- 重放原请求（也就是 `sms_login`）；
- **不使用** verify 返回的 `verify_ticket`。

所以重放请求是：

```
POST /passport/mobile/sms_login/
mobile=<原手机号编码>
code=<原验证码编码>
account_sdk_source=app
passport_support_flow=captcha,verify
mix_mode=1
passport_mfa_retry_tag=1
sms_code_key=<biz_params.sms_code_key 的 XOR 编码>
```

成功即拿到 `sessionid`。

---

### 3.7 凭据落地

```kotlin
Result.LoggedIn(AccountCredentials(
    cookie = <cookies 拼接>,
    deviceId = device["device_id"],
    installId = device["iid"],
    cdid = device["cdid"],
    openudid = device["openudid"],
    expiresAt = <sid_guard 的过期时间>,
    device = device
))
```

与导入 `cookieStore.xml` 的结构完全一致，后续内容接口 / 云同步直接复用。

---

## 4. 编码、签名与请求头

### 4.1 XOR 0x05 + hex

```kotlin
fun encodeField(value: String): String =
    value.toByteArray(Charsets.UTF_8)
        .map { (it.toInt() xor 5).toByte() }
        .toByteArray()
        .joinToString("") { "%02x".format(it.toInt() and 255) }
```

示例：

```
"24"          → "3731"
"+8617600351236" → 2e3d333432333535363034373633
"4263"        → 313c3036
```

`sms_code_key` 在重放时对**字符串本身**再做一次 XOR：

```
"KEY123" → "4e405c343736"
```

### 4.2 签名

Passport 请求必须走 **branch 2** 签名（`PassportSigner`）：

- `X-Gorgon`：modified RC4 + 半字节交换 + 按位反转
- `X-Khronos`：时间戳
- `X-Argus` / `X-Ladon`：按需
- query 里带 `ts` / `_rticket`

branch 0 打 Passport 会回 `16 该应用无权限`。

### 4.3 固定请求头

```
User-Agent: <VendorConstants.userAgent(device)>
Accept: application/json
X-XS-From-Web: 0
Sdk-Version: 2
Passport-Sdk-Version: 5051452
X-VC-Bdturing-Sdk-Version: 4.0.3.cn
Lc: 101
X-SS-DP: 8662
X-Tt-Store-Region: <store-region Cookie 或 cn>
X-Tt-Store-Region-Src: did
X-Tt-Token: <登录后>
Cookie: <累积的 Cookie>
```

二次验证请求额外带：

```
X-Use-Secondary-Verify-Sdk: 1
```

### 4.4 主机

| 用途 | 主机 |
|---|---|
| 设备注册 | `https://log.snssdk.com` |
| Passport（发码 / 登录 / 红果二次验证） | `https://api5-normal-lq.fqnovel.com` |
| rmc 二次验证（兼容） | 挑战里的 `verify_host`，常见 `https://i.snssdk.com` |

---

## 5. 错误码与处理策略

| 错误码 | 文案 | 含义 | 策略 |
|---|---|---|---|
| `0` | success | 成功 | 取 ticket / 会话 |
| `1` | 会话过期，请重新登录 | 挑战/会话已失效 | 直接失败，让用户重新发码 |
| `3` | 缺少参数 | body 形状不对 | 直接失败（重试无意义） |
| `7` | 访问太频繁 | 限流 | 原样提示，等 `retry_time` |
| `22` | 非法应用 | aid/app 参数不对 | 直接失败 |
| `1045` | 认证失败 | **平台还没认到短信**（pending） | 继续轮询，有上限 |
| `2046` | 为保证账号安全… | 要求二次验证 | 解析 MFA 并弹页 |
| `9001` | 长时间未操作，验证已过期 | 挑战结束 | 停止轮询，重新发码 |

> 最容易误判的是 `1045`：它不是失败，也不是「票据错」，而是「还没看到短信」。

---

## 6. 状态机

```
Idle
 ├─ sendCode ──> Sent
 │                 └─ 2046 ──> NeedUpsms(sendCode 来源)
 └─ login(code) ──> LoggedIn
                   └─ 2046 ──> NeedUpsms(login 来源)
                                  │
                                  ├─ verify → 1045 ──> Pending（轮询）
                                  ├─ verify → ticket ─> 重放 sms_login
                                  ├─ verify → 1/3/22 ─> Failed
                                  └─ verify → 9001 ──> Failed（重新发码）
```

- `mfaDeadline`：挑战到达后 10 分钟。
- `busy`：一次只允许一个动作在途（遥控器连点会重复消费验证码）。
- `consumedCode`：已被 `sms_login` 消费的码，没有票据时本地拦截重放。

---

## 7. 代码索引

| 位置 | 作用 |
|---|---|
| `PhoneLogin.HOST` | Passport 主机 |
| `PhoneLogin.VERIFY_HOST` | rmc 二次验证主机（兼容） |
| `PhoneLogin.sendCode()` | 发码；可能返回 `NeedUpsms` |
| `PhoneLogin.login()` | 验证码换会话；带 ticket 时是重放 |
| `PhoneLogin.verifyUpsms()` | 轮询一次二次验证 |
| `PhoneLogin.parseUpsms()` | 解析两种 2046 结构 |
| `PhoneLogin.askNovelread()` | 红果自有二次验证 |
| `PhoneLogin.askUpsms()` | 兼容 rmc 换票链路 |
| `PhoneLogin.secondaryParams()` | rmc 的 N 参数（兼容） |
| `PhoneLogin.encodeField()` | XOR 0x05 + hex |
| `PhoneLogin.query()` | Passport 公共 query |
| `PhoneLogin.postTo()` | 带签名的 POST + Cookie/Token 收集 |
| `TvTools.showUpsms()` | 上行短信页 + 轮询 |
| `DeviceRegister` | 设备注册 |
| `PassportSigner` | branch-2 签名 |

常量：

```
SMS_TYPE = "24"
MFA_WINDOW_MS = 10 分钟
CODE_PENDING = "1045"
UPSMS_ERROR_LIMIT = 4
UPSMS_PENDING_LIMIT = 6
UPSMS_FIRST_POLL_MS = 8000
UPSMS_POLL_MS = 4000
```

---

## 8. 验证与排障

### 8.1 成功日志（2026-10-21 21:34）

```
21:34:05.403  MFA 挑战：number=9515211003 content=YZ verifyTicket=<空>
              mobileTicket=有 scene=sms_login complex=false novelread=true biz=有 rawLength=0
21:34:05.406  打开上行短信页 number=9515211003 content=YZ fromSendCode=false
21:34:13.450  → POST /passport/upsms/verify/ form={encrypt_uid=..., verify_ticket=,
              biz_params={"passport_mfa_retry_tag":"1","sms_code_key":"..."},
              verify_scene=sms_login, verify_reason=ato, ...}
21:34:13.593  ← {"data":{"description":"认证失败","error_code":1045}}
21:34:17.751  ← 1045
21:34:21.955  ← {"data":{"registered":true,"ticket":"***"},"message":"success"}
21:34:22.558  → POST /passport/mobile/sms_login/ ... passport_mfa_retry_tag=1
21:34:22.558  ← success + Set-Cookie sessionid
```

### 8.2 三分钟判据

```bash
adb logcat -s HGTV-LOGIN
```

| 看到什么 | 说明 |
|---|---|
| `novelread=true` + `POST /passport/upsms/verify/` | 走对链路了 |
| `gen_verify_ticket` | 走错了（这是 rmc 链路） |
| `1045` 连续出现 | 正常等待，短信还没到 |
| `3 缺少参数` | body 缺字段 / encrypt_uid 不对 |
| `1 会话过期` | 挑战已死，重新发码 |
| `Ticket` + 重放 sms_login | 通过 |

### 8.3 常见故障表

| 现象 | 根因 | 修复 |
|---|---|---|
| 页面一闪就关 | 首轮询太早 + 换票失败 | 首问延迟 8 秒；不要走换票 |
| 一直「平台还没看到短信」 | 1045 无上限 | `UPSMS_PENDING_LIMIT` |
| 发完短信仍 1045 | 短信正文被改写 / 发信号不是登录号 / 双卡副卡 | 严格原样发送 |
| `gen_verify_ticket 1 会话过期` | 用了 `mobile_ticket` 或走错链路 | 红果链路不换票 |
| `verify 1045` 但短信已发 | body 少了 `encrypt_uid`/`biz_params` | 按 3.4.3 全量发 |
| 登录成功但没有数据 | 只存了 Cookie 没存 `X-Tt-Token` / 设备档案 | 用 `AccountCredentials` |
| `16 该应用无权限` | 用了 branch 0 签名 | 改 branch 2 |
| 频繁 2046 | 设备指纹变化 / 代理 / 高频发码 | 稳定设备、遵守 `retry_time` |

---

## 9. 注意事项（重点）

### 9.1 协议层

1. **`mobile_ticket` 是一次性的。** 它在 `sms_login` 那一刻被消费；再拿去换票/验证只会得到
   `1 会话过期`。它只能用于展示或日志，不参与二次验证。
2. **先判结构，再选链路。** 看见 `verify_ways` + `url` 就走红果自有 verify；看见
   `verify_center_secondary_decision_conf` 才走换票。两者不能互相套用。
3. **`verify_scene` 用 `event_params.verify_scene`（`sms_login`），不是 risk_info 里的 `login`。**
4. **`verify_data` 只在 rmc 链路存在，且必须原样回传。** 红果链路根本没有这个字段。
5. **`verify_ticket` 为空时不要伪造。** 红果链路就是空串；rmc 链路没票就先换票。
6. **重放用的是 `biz_params`，不是 verify 返回的 ticket。** `sms_code_key` 要做 XOR 编码。
7. **`1045` 是 pending。** 不要当失败，也不要不设上限。
8. **`9001` 是结束。** 不要再重试同一个挑战，直接让用户重新发码。

### 9.2 设备与风控

1. 保持 `device_id` / `iid` / `cdid` / `openudid` / `oaid` 稳定，不要每次登录重建。
2. 不要在链路里做 TLS 中间人：mitmproxy 会改变 JA3/JA4，证书链异常本身就是高风险信号。
3. 用官方 TTNet/sscronet 栈或等价的签名实现，保留 `X-Argus` / `X-Gorgon` / `X-Ladon` / `X-Khronos`。
4. 遵守 `retry_time`，同一号码/IP 不要高频发码。
5. 避免模拟器、云手机、多开、代理 IP 池。
6. 设备注册只需一次；重复注册会稀释设备信誉。

### 9.3 UI/交互

1. 上行短信页必须**原样展示** `channel_mobile` 与 `sms_content`（包括大小写、不要加空格/换行）。
2. 提供 `sms:` deep link 和可复制的文本兜底。
3. 页面出现后**不要立刻轮询**（本项目 8 秒后再问）。
4. 轮询失败时不要拿同一张验证码去重登——它已经被消费了。
5. 成功后如果是「发码阶段」触发的二次验证，应当**重新发一张新码**；如果是「登录阶段」触发的，
   才带 `biz_params` 重放。
6. 失败文案要带平台原文（已脱敏），否则线上只能靠猜。

### 9.4 安全与隐私

1. 日志里的 `ticket` / `verify_ticket` / `mobile_ticket` / `sms_code_key` / `encrypt_uid` /
   `sessionid` / `sid_tt` 必须脱敏。
2. 不要把完整手机号写进日志（本项目显示为 `176******36`）。
3. `biz_params` 里含 `sms_code_key`，日志打印前要过滤。
4. 凭据文件按敏感数据对待，不要进版本库。

### 9.5 兼容性

1. 红果版本升级后，`passport-second-verification.html` 的字段可能变化；
   以 `data.url` / `data.verify_ways` / `data.event_params` 为准，不要硬编码页面路径以外的假设。
2. `verify_ways` 可能包含多种方式（短信回填、语音、密码、第三方），
   只有 `verify_way == "mobile_up_sms_verify"` 才走本文的上行短信页。
3. 若平台改回 rmc 链路，代码里的兼容分支仍可用，但要重新验证 `verify_host` 与 `verify_data`。

---

## 10. 参考与变更

- 抓包与逆向依据：
  - `com.bytedance.bdturing.verify.request.*`（rmc 链路）
  - `cc0.e` / `cc0.d`（`second_verify.fetch` 原生桥）
  - `ai1.f` / `ai1.c` / `com.bytedance.sdk.account.impl.w`（二次验证回调与重放）
  - `passport-second-verification.*.js`（红果自有页）
- 相关文档：
  - `docs/红果短信登录分析.md`
  - `docs/红果上行短信二次验证分析.md`
- 变更：
  - 2026-10-21：确认红果走自有二次验证页；移除该链路的 `gen_verify_ticket`；
    重放改用 `biz_params`；首轮询延迟 8 秒；真机一次通过。
