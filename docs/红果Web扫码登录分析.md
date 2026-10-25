# 红果短剧 PC 站 · 扫码登录全流程分析

> 目标站点：`https://hongguoduanju.com/`
> 抓取时间：2026-10-23 19:44
> 抓取方式：本机实跑 `check_qrconnect` 长轮询 + 真实手机（红果短剧 App）扫码确认
> 证据目录：`/Users/star/Downloads/hongguo/artifacts/qr/`

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 出码接口 | `GET /passport/web/get_qrcode/?next=&aid=513755` |
| 轮询接口 | `GET /passport/web/check_qrconnect/?next=&token=&aid=513755` |
| 轮询节奏 | **1.1 秒**（前端 `setTimeout(fn, 1000)` 递归，实测中位数 1.10s） |
| 二维码 TTL | **60 秒**，`expire_time - now ∈ [58, 60]` |
| 过期处理 | `check_qrconnect` **在同一次响应里直接下发新码**（`token` + `qrcode` + `expire_time`），前端原地替换，**不重新调 get_qrcode** |
| 状态机 | `new` → `scanned` → `confirmed`；旁支 `expired` / `4`(换码) |
| 登录态下发时机 | **`check_qrconnect` 返回 `confirmed` 的那一次响应**，一次性 `Set-Cookie` 下发 14 条，含 `sessionid` / `sid_guard` / `uid_tt` |
| `redirect_url` | 就是 `next` 原样回填，**无 ticket、无 sso 参数**，纯前端 `location.replace()` |
| 认证核心 | `aid=513755` + `token`，`a_bogus`/`msToken`/`account_sdk_source_info` **全部可省略** |
| 二维码内容 | 指向 `reading.snssdk.com` 的 H5 落地页，token 明文放在 query |

**一句话**：整个登录是「PC 拿 token 出码 → 手机 App 用 token 上报扫码 → PC 轮询拿 token 换 Cookie」，Cookie 由 Passport 在 `confirmed` 响应里直接种到 `hongguoduanju.com` 域上，页面跳转只是刷新视图。

---

## 1. 三方角色与时序

```
┌──────────────┐         ┌───────────────────────┐         ┌──────────────────┐
│  PC Web      │         │  Passport 中枢         │         │  手机 App        │
│ hongguoduanju│         │ passport/web (513755) │         │ 红果短剧          │
└──────┬───────┘         └───────────┬───────────┘         └────────┬─────────┘
       │                             │                              │
  ①   │ GET get_qrcode?next&aid=513755                              │
       ├────────────────────────────>│                              │
       │<────────────────────────────┤ token(32hex_lq) + qrcode(png)│
       │                             │ + expire_time(now+60)        │
       │  渲染 <img src="data:image/png;base64,...">                 │
       │                             │                              │
       │                        ② 用户用 App 扫二维码                 │
       │                             │<─────────────────────────────┤
       │                             │  POST /passport/open/scan_qrcode/
       │                             │  {token, scene, qr_source_aid}│
       │                             │─────────────────────────────>│
       │                             │                              │
  ③   │ GET check_qrconnect?token   │                              │
       ├────────────────────────────>│                              │
       │<────────────────────────────┤ status:"scanned"              │
       │  UI: "扫码成功"              │  scan_app_id:8662            │
       │  track: uc_login_submit     │  scan_user_info{...}         │
       │                             │                              │
       │                        ④ 手机点「确认登录」                  │
       │                             │<─────────────────────────────┤
       │                             │  POST /passport/mobile/confirm_qrcode/
       │                             │  {aid, token, decision:0,    │
       │                             │   csrf_token, csrf_ts}       │
       │                             │                              │
  ⑤   │ GET check_qrconnect?token   │                              │
       ├────────────────────────────>│                              │
       │<────────────────────────────┤ status:"confirmed"            │
       │                             │ + user_data{全量用户信息}      │
       │                             │ + **14 条 Set-Cookie**        │
       │  UI: 登录成功                │                              │
       │  track: uc_login_result      │                              │
  ⑥   │ setTimeout(1000) → location.replace(redirect_url)          │
       │                             │                              │
  ⑦   │ GET https://hongguoduanju.com/  (携带新 Cookie，已是登录态) │
       │<────────────────────────────┤ 200 SSR，页面渲染头像/昵称     │
```

**关键点**：步骤 ⑤ 是整个流程唯一的「发牌」时刻。`redirect_url` 不携带任何凭证，它的作用只是让前端从登录弹窗回到首页。

---

## 2. 接口清单

### 2.1 出码

```
GET https://hongguoduanju.com/passport/web/get_qrcode/
    ?next=https%3A%2F%2Fhongguoduanju.com%2F
    &aid=513755
    &language=zh
    &account_sdk_source=web
    &passport_jssdk_version=2.4.13
    &passport_jssdk_type=normal
```

**必填参数**：`aid`、`next`（非空即可，不校验域名）
**可选参数**：其余全部可省

**响应**（`data` 字段）：

```json
{
  "app_name": "",
  "captcha": "",
  "desc_url": "",
  "description": "",
  "error_code": 0,
  "expire_time": 1792755857,
  "is_frontier": false,
  "qrcode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAgAAAAIACAIAAAB7...",
  "qrcode_index_url": "https://reading.snssdk.com/reading_offline/novelread/page/scan-code.html?custom_brightness=1&hide_nav_bar=1&hide_status_bar=1&need_custom_brightness=1&next_url=...&qr_source_aid=513755&token=<token>&version_code=5.8.3",
  "status": "new",
  "token": "7de4850a373cbaac27d76eeae8b7f13c_lq",
  "web_name": "红果短剧PC站"
}
```

**响应头**：

```
Set-Cookie: passport_csrf_token=3f5c5ee7c65f466c3092a05c828b1078; Path=/; Domain=hongguoduanju.com; Max-Age=5184000; Secure; SameSite=None
Set-Cookie: passport_csrf_token_default=<同值>; Path=/; Domain=hongguoduanju.com; Max-Age=5184000
```

### 2.2 轮询

```
GET https://hongguoduanju.com/passport/web/check_qrconnect/
    ?next=https%3A%2F%2Fhongguoduanju.com%2F
    &token=7de4850a373cbaac27d76eeae8b7f13c_lq
    &aid=513755
```

注意：真实浏览器还会带 `a_bogus`、`msToken`、`account_sdk_source_info`、`p_js_v`、`biz_trace_id` 等十余个参数，但**实测全部可省**。

**响应头（每次都有）**：

```
Bd-Tt-Error-Code: 0
X-Ms-Token: -UfwqSNnf1nUUBzg-uy1KavCktLUdPDrOrOh5e1N2XYw01J1QMIQDqZtFnpN6aHYkCLENqbKMxLZ-ijYWLklZO8GYCJT6UtcknZE451X
Bd-Ticket-Guard-Sign-Res-Static-Ts-Sign: 1102
Bd-Ticket-Guard-Sign-Res-Static-Sign: 1102
Bd-Ticket-Guard-Key-Sign-Result: 2
x-tt-trace-id: 00-261023194456E4BE1B706FCB5A7DD43D-55FD6B4D6C65D902-00
```

`X-Ms-Token` 用于回填下一次请求的 `msToken` 参数（但服务端不校验）。

---

## 3. 状态机（五个分支）

```
                    ┌──────────────────────────────────────┐
                    │  status: "new" / "1"                 │
   出码成功 ────────>│  keys: captcha,desc_url,description, │
                    │        error_code,extra,scan_app_id, │
                    │        status                        │
                    └──────────────┬───────────────────────┘
                                   │ 手机扫了
                    ┌──────────────▼───────────────────────┐
                    │  status: "scanned" / "2"             │
                    │  + scan_app_id: 8662                 │
                    │  + scan_device_info{                 │
                    │      scan_device_display_name:"手机"}│
                    │  + scan_user_info{                   │
                    │      user_id:0, screen_name:"",      │
                    │      avatar_url:"..."}               │
                    │  UI: "扫码成功"                       │
                    │  track: uc_login_submit              │
                    └──────────────┬───────────────────────┘
                                   │ 手机点了确认
                    ┌──────────────▼───────────────────────┐
                    │  status: "confirmed" / "3"           │
                    │  + redirect_url: "https://..."       │
                    │  + user_data{全量}                    │
                    │  + 14 条 Set-Cookie  ★登录态落地★     │
                    │  track: uc_login_result(success)     │
                    │  setTimeout(1000) → location.replace │
                    └──────────────────────────────────────┘

   旁支：
   status "4"        → 换码，用响应 qrcode+token 原地替换，不重置状态
   status "expired"/"5" → ++qe <= L ? 用响应新码原地续期 : 显示"点击刷新二维码"
                          （L = refreshNumber，站方配置为 0，故首次过期即续期一次）
```

### 3.1 实测状态跳变时间线

```
19:44:35.571  new         token=7de4850a373cbaac27d76eeae8b7f13c_lq
19:44:54.517  scanned     scan_app_id=8662, scan_device_display_name="手机"
19:44:56.930  confirmed   user_id=1739875314044148, redirect_url=https://hongguoduanju.com/
```

**扫码到确认间隔 2.4 秒**，确认到 Cookie 落地 0 毫秒（同一次响应）。

### 3.2 `scanned` 完整响应

```json
{
  "captcha": "", "desc_url": "", "description": "", "error_code": 0, "extra": "",
  "scan_app_id": 8662,
  "scan_device_info": {
    "device_id": 0, "app_id": 0, "ip": "", "verify_time": 0,
    "verify_way": "", "scan_device_display_name": "手机"
  },
  "scan_user_info": {
    "user_id": 0, "screen_name": "",
    "avatar_url": "https://p3-novel.byteimg.com/img/novel-static/3abe6a50e39b4bf1ba7c29f33074e1a8~tplv-1yzifmftcy-expand:200:0:0.png"
  },
  "status": "scanned"
}
```

> 注意 `scan_user_info.user_id` 为 0、`screen_name` 为空——**扫码阶段不泄露用户身份**，只给一个匿名占位头像。这是设计上的隐私保护。

### 3.3 `expired` 完整响应（自带新码）

```json
{
  "app_name": "", "captcha": "", "desc_url": "", "description": "",
  "error_code": 0,
  "expire_time": 1792755857,
  "is_frontier": false,
  "qrcode": "data:image/png;base64,iVBORw0KGgo...",
  "qrcode_index_url": "https://reading.snssdk.com/...&token=ee1edf64b39d4d0eb6c269ca412f9ad1_lq&version_code=5.8.3",
  "status": "expired",
  "token": "ee1edf64b39d4d0eb6c269ca412f9ad1_lq",
  "web_name": "红果短剧PC站"
}
```

**这就是「永不过期」的实现基础**：客户端只要一直轮询，服务端会一直喂新码，全程不需要重新调 `get_qrcode`。

---

## 4. `confirmed` 响应与 Cookie 落地

### 4.1 完整响应体

```json
{
  "captcha": "", "desc_url": "", "description": "",
  "error_code": 0, "extra": "",
  "redirect_url": "https://hongguoduanju.com/",
  "scan_app_id": 8662,
  "status": "confirmed",
  "user_data": {
    "app_id": 513755,
    "user_id": 1739875314044148,
    "user_id_str": "1739875314044148",
    "odin_user_type": 12,
    "name": "用户1060261662197",
    "screen_name": "用户1060261662197",
    "avatar_url": "https://p3-passport.byteacctimg.com/img/mosaic-legacy/3795/3047680722~120x256.image",
    "mobile": "176******36",
    "country_code": 86,
    "gender": 3,
    "phone_collected": true,
    "email_collected": false,
    "is_blocked": 0, "is_blocking": 0,
    "can_be_found_by_phone": 1,
    "followings_count": 0, "followers_count": 0,
    "has_password": 0,
    "new_user": 0,
    "first_login_app": 0,
    "sec_user_id": "MS4wLjABAAAACqtYR7KJa1Qz6IRmd7CRQK-jzzuSXdh_C7CXbfi1r4eL6IIO356gt_0kfSn6awYR",
    "session_key": "",
    "connects": [{
      "platform": "aweme_v2",
      "platform_app_id": 7828,
      "platform_screen_name": "坤仔",
      "platform_uid": "61320411564",
      "sec_platform_uid": "MS4wLjABAAAAO9Uc5z3FOCSpQMn625OE70fVE5ZbwxQjuIUvimXGGWg",
      "expired_time": 1792770655,
      "modify_time": 1792749055,
      "profile_image_url": "https://p11.douyinpic.com/aweme/1080x1080/aweme-avatar/tos-cn-avt-0015_d215d60223792ca142ec05d946a6054b.jpeg"
    }],
    "hm_region": 1, "use_hm_region": true,
    "is_visitor_account": false, "is_kids_mode": 0,
    "source": "normal", "is_employee": false,
    "user_auth_status": 0,
    "need_ttwid_migration": 0,
    "app_user_info": {"assist_register": false}
  }
}
```

**关键字段**：
- `connects[].platform = "aweme_v2"` → 证明此账号是**抖音账号（AppID 7828）**，红果通过 passport 统一账号体系打通
- `session_key` 为空 → 真实会话凭证只在 Cookie 里，不在 body 里
- `new_user: 0` → 老用户，首登标记 `first_login_app: 0`

### 4.2 Set-Cookie 全量（14 条，一次响应）

| Cookie | 值 | Max-Age | 属性 |
|---|---|---|---|
| `sessionid` | `612d075f1cb57198ecf24a3b83a99ad8` | 5184000 (60d) | HttpOnly |
| `sessionid_ss` | 同上 | 5184000 | HttpOnly; Secure; SameSite=None |
| `sid_guard` | `612d...ad8\|1792755896\|5184000\|Tue, 22-Dec-2026 11:44:56 GMT` | 31104000 (360d) | HttpOnly |
| `sid_tt` | `612d075f1cb57198ecf24a3b83a99ad8` | 5184000 | HttpOnly |
| `uid_tt` | `ece15ac5c73d7e582b7e18208382413d` | 5184000 | HttpOnly |
| `uid_tt_ss` | 同上 | 5184000 | HttpOnly; Secure; SameSite=None |
| `session_tlb_tag` | `sttt\|19\|YS0HXxy1cZjs8ko7g6ma2P_________5NRx8ryyksfl5WbZkUQppsPqtQVbVpejfIJdadHGijSQ=` | 5184000 | HttpOnly; Secure; SameSite=None |
| `sid_ucp_v1` | `1.0.0-KGM0ZTFlZTQwNTg2MTEwY2ExOTYwNjVmMTVlOGFmYTY4YTY0ZjkxY2IK...` | 5184000 | HttpOnly; Secure |
| `ssid_ucp_v1` | 同上 | 5184000 | HttpOnly; Secure; SameSite=None |
| `odin_tt` | `63bab2067c4a2a1b15ce11330f1b20f410c8e6cd89b8c6998ada209654be8eaf1a14423ae53d3b85a69e77845e0031e897b5272e639b64b3beb96f61f3be4e76` | 31536000 (365d) | HttpOnly |
| `n_mh` | `ovnA6lUpBKAAU5ut29N7DIuWUz-D9OCvKRFXQOxjhIY` | 10368000 (120d) | HttpOnly |
| `is_staff_user` | `false` | 5184000 | HttpOnly |
| `has_biz_token` | `false` | 5184000 | HttpOnly |
| `reg-store-region` | 空值（删除） | 0 | HttpOnly; Secure |

**参数含义**：
- `sessionid` = `sid_tt` = `sessionid_ss` = **同一个 32 位 hex**，是 Passport 的会话主键
- `sid_guard` 三段：`sessionid|签发时间戳|有效期秒数|GMT 过期时间`，可用于离线判断会话剩余寿命
- `uid_tt` 是**独立的用户标识**（≠ user_id），服务端做 `uid_tt ↔ sessionid` 双向校验
- `session_tlb_tag` 是网关层的「会话表标签」，`sttt|19|` 前缀表示机房/集群编号，用于会话路由
- `sid_ucp_v1` / `ssid_ucp_v1` 是 UCP（统一账号协议）v1 的会话凭证，base64 内含 user_id 与时间戳

### 4.3 Playwright 复核：真实浏览器的轮询行为

用 Chromium 打真实站点，点「登录 → 扫码登录」，录 75 秒：

```
第 1 次 check : 0.27s
第 54 次 check: 59.36s      ← 最后一次
状态: new(0.4s) → expired(59.5s)   ← expired 响应里已带新 token 16e2125006364ffed28b00134f49d2bd_lq
60s 之后: 0 次请求
```

**结论**：真实前端在收到 `expired` 后**停止轮询**，新 token 只写进 ref 不渲染，必须用户手动点「点击刷新二维码」才会重新 `getQrcode`。所以「永续二维码」只在我们自己写的客户端里成立。

截图：`artifacts/qr/ui_after_60s.png`（60s 后的页面状态）
原始时间线：`artifacts/qr/browser_poll_timeline.json`

### 4.4 验证：Cookie 拿到即登录成功

用抓到的 14 条 Cookie 直接请求账号接口：

```
GET https://hongguoduanju.com/passport/account/info/v2/?aid=513755
```

**未登录**：
```json
{"data":{"description":"会话过期，请重新登录","error_code":13,"name":"account_info_error","session_key":"","user_id":0},"message":"error"}
```

**携带上述 Cookie**：
```json
{"data":{"app_id":513755,"user_id":1739875314044148,"name":"用户1060261662197",
"mobile":"176******36","gender":3,"odin_user_type":12,
"sec_user_id":"MS4wLjABAAAACqtYR7KJa1Qz6IRmd7CRQK-jzzuSXdh_C7CXbfi1r4eL6IIO356gt_0kfSn6awYR",...}}
```

首页 SSR 也已带出昵称（`坤仔` 出现在 HTML 中）。**至此登录态完全建立，与走短信验证码的最终状态一致。**

---

## 5. 前端源码位置

登录弹窗实现在 chunk `async/n.f2c793859c.js`，核心是 `V` 函数（约 50500 字节偏移处）：

```js
V = function () {
  return ze ? M.checkQRCode({token: ze, need_logo: O}).then(function (e) {
    var t = e.status, n = e.redirect_url, i = void 0 === n ? "" : n,
        o = e.qrcode, r = void 0 === o ? "" : o,
        s = e.token, l = void 0 === s ? "" : s;
    switch (t) {
      case "1": case "new":
        return f.current = !1, u(!1), h({type:"", text:"", buttonText:""}), Promise.resolve(!0);
      case "2": case "scanned":
        return f.current || (ee("uc_login_submit", {login_method:"qr_code", aid:E}), f.current = !0),
               c || u(!0),
               h({type:"success", text:_.scanSuccess, buttonText:_.scanSuccessBtnText}),
               Promise.resolve(!0);
      case "3": case "confirmed":
        return function(e){
          f.current || ee("uc_login_submit", {login_method:"qr_code", aid:E}),
          ee("uc_login_result", {status:"success", login_method:"qr_code", is_new_user:0, aid:E}, !0),
          R(), u(!0),
          h({type:"success", text:_.loginSuccess, buttonText:_.loginSuccessBtnText}),
          setTimeout(function(){ x ? x({redirect_url:e}) : window.location.replace(e) }, 1e3)
        }(i), Promise.resolve(!1);
      case "4":
        return f.current = !1, u(!1), h({type:"", text:"", buttonText:""}),
               a("data:image/jpeg;base64,".concat(r)), ze = l, Promise.resolve(!0);
      case "5": case "expired":
        return ze = l, ++qe <= L
          ? (a("data:image/jpeg;base64,".concat(r)), Promise.resolve(!0))
          : (u(!0), h({type:"refresh", text:_.refreshCode, buttonText:_.refreshBtnText}), Promise.resolve(!1));
      default: return Promise.resolve(!1);
    }
  })
}
```

### 轮询调度

```js
// 约 49981 字节处
case 0: return n.trys.push([0,2,,3]), [4, V()];
case 1: return e = n.sent(), console.log("<<<checkBaseQRCode:", e, g.current),
        e && g.current && setTimeout(function(){ $() }, 1e3),   // ← 1 秒递归
        [3,3];
```

### 参数定义

```js
// 约 48147 字节处
w = k(), E = w.aid,
C = w.ScanCodeDescription,
S = w.scanCodeNeedLoge, O = void 0 !== S && S,
x = w.success,
A = w.isStopCheckWhenHide, I = void 0 !== A && A,
P = w.refreshNumber, L = void 0 === P ? 0 : P
```

- `refreshNumber` 站点未配置 → `L = 0`。`case "5"` 分支先执行 `ze = l`（把服务端随 `expired` 下发的新 token 写进 ref），随后判断 `++qe(1) <= 0` 为 **false** → 走 else：**弹出「点击刷新二维码」按钮，停止自动轮询**。
- 实测印证：用 Playwright 跑真实浏览器 62 秒，页面 token 一直不变、轮询在 60s 后停止，UI 文案未自动切换为新码——**服务端虽然给了新码，前端也存下了，但不会自动渲染，必须用户点按钮触发 `D()`（重新走 `getQrcode`）**。这是源码与「看起来能无感续期」的直觉之间的差异点。
- 因此「无限续期」只在**我们自己写的客户端**里成立（Python 脚本读到 `expired` 后自行替换图片即可），真实 Web 前端不会这么做。
- `isStopCheckWhenHide` 站点配置为 `true` → 页面切到后台（`document.visibilityState === "hidden"`）时停止轮询。**这解释了为什么把浏览器标签页切走会导致扫码无效**。

### 埋点

| 事件 | 时机 | 参数 |
|---|---|---|
| `uc_login_submit` | 首次进入 `scanned` 或 `confirmed` | `{login_method:"qr_code", aid:513755}` |
| `uc_login_result` | `confirmed` | `{status:"success", login_method:"qr_code", is_new_user:0, aid:513755}` |
| `uc_login_exit` | 组件卸载 | `{aid:513755}` |

`f.current` ref 保证 `uc_login_submit` 只上报一次（`scanned` 上报后 `confirmed` 不重复）。

---

## 6. 二维码内容

用 `jsQR` 解码 `qr_001.png`：

```
https://reading.snssdk.com/reading_offline/novelread/page/scan-code.html
  ?custom_brightness=1
  &hide_nav_bar=1
  &hide_status_bar=1
  &need_custom_brightness=1
  &next_url=https%3A%2F%2Freading.snssdk.com%2Freading_offline%2Fnovelread%2Fpage%2Fscan-code.html
  &qr_source_aid=513755
  &token=7de4850a373cbaac27d76eeae8b7f13c_lq
  &version_code=5.8.3
```

**要点**：
1. `token` **明文**在 query 里，任何能读二维码的 App 都能拿到 token
2. `next_url` 也被固定成 landing 页自身，是个「自指」参数
3. `qr_source_aid=513755` 与 Web 侧 `aid` 一致，服务端据此区分来源
4. 落到 `reading.snssdk.com`（番茄/红果共用的小说阅读域），不是 `hongguoduanju.com`

---

## 7. 手机端的两步

### 7.1 扫码上报

App 内 `com/dragon/read/component/biz/impl/mine/scan/h.java`：

```java
// a() — 扫到码后上报
POST /passport/open/scan_qrcode/
  token=<从二维码解析>
  scene=<场景>
  qr_source_aid=513755
```

若手机未登录，`b()` 会先拉起登录流程；登录失败时 `d()` 弹「绑定失败」。

### 7.2 确认登录（H5 落地页路径）

`/tmp/hgc/scan-code.js`：

```js
// 查询码状态
GET /passport/mobile/scan_qrcode/   {aid, token, ...}

// 确认
POST /passport/mobile/confirm_qrcode/
  aid=<aid>
  token=<token>
  decision=0          // 0=确认登录, 1=放弃
  csrf_token=<从 confirmed_url 的 query 解析>
  csrf_ts=<同上>
```

**`csrf_token` / `csrf_ts` 的来源**：

```js
n.confirmed_url.split("?")[1]  →  qs.parse(...)  →  {csrf_token, csrf_ts}
```

即**这两个防 CSRF 参数由服务端在 `scan_qrcode` 响应里通过 `confirmed_url` 下发**，前端只是透传。这是「确认」接口唯一的鉴权手段（配合手机侧登录态 Cookie）。

### 7.3 界面

- 标题：「红果短剧PC站 请求登录」
- 正文：「确认登录 / 已阅读并同意用户协议和隐私政策 / 放弃登录」
- 隐私勾选后 `setTimeout(G(), 0)` 才真正发确认请求

---

## 8. 参数与风控实测

### 8.1 `aid` 强校验

| aid | 结果 |
|---|---|
| `513755` | ✅ 放行 |
| 缺失 | `{"error_code":22, "description":"非法应用"}` |
| `1` / `8662` / `2329` | `{"error_code":16, "description":"该应用无权限"}` |

`8662` 是 App 侧的 AppID（`scan_app_id: 8662`），但 **Web 接口只认 `513755`**。`check_qrconnect` 同样校验。

### 8.2 签名参数非必需

实测**省略以下全部参数仍成功**：

| 参数 | 来源 | 是否必需 |
|---|---|---|
| `a_bogus` | JS 逆向算法 | ❌ |
| `msToken` | `mssdk.bytedance.com/web/r/token?ms_appid=8662` | ❌（乱填也不影响） |
| `account_sdk_source_info` | 浏览器指纹 XOR 0x05 的 hex | ❌ |
| `x-tt-passport-csrf-token` | `passport_csrf_token` | ❌（空值可用） |
| `p_js_v` / `p_js_t` / `p_zt` / `p_ver` / `p_bd` | SDK 版本号 | ❌ |
| `biz_trace_id` | 8 位 hex | ❌ |

`account_sdk_source_info` 解码：JSON 指纹串的**每个字节 XOR 0x05** 后转 hex，可还原出浏览器特征。

### 8.3 `Bd-Ticket-Guard` 未强制

响应头有 `Bd-Ticket-Guard-Sign-Res-Static-Sign: 1102` / `Key-Sign-Result: 2`，SDK 配置：

```json
{"certType":"header", "scene":"web_protect", "signVersion":2,
 "providerPathList":["/passport/web/check_qrconnect/", ...]}
```

但**服务端未强制校验**——裸 HTTPS 请求（无签名、无 TLS 指纹伪装）一律 200。

### 8.4 `next` 不校验域名

`next=https://evil.example/` 也能成功出码，`redirect_url` 原样回填该值 → **存在开放重定向面**。唯一限制：`javascript:` 协议被 WAF 拦截（444）。

### 8.5 未知 token 静默降级

| 输入 token | 响应 |
|---|---|
| `deadbeef..._lq`（伪造） | `status:"expired"` + 下发全新有效码 |
| 改后缀（`_lf` ↔ `_lq`） | 同上 |
| 去掉后缀 | 同上 |
| 空值 | `{"error_code":3, "description":"缺少参数"}` |

**不报错、不区分「不存在」与「已过期」**，每次返回不同 token → 可无限刷码，且**无法通过接口探测 token 是否存在**。

### 8.6 无限流

- 单 IP 25 次连发 / 3 秒 → 全 200
- 10 并发 × 20 次 → 全 200
- 无 Cookie 也能 check

### 8.7 token 格式

```
7de4850a373cbaac27d76eeae8b7f13c_lq     ← 32 hex + "_lq"
f4044383df114556d956b2691ebc72c7_lf     ← 32 hex + "_lf"
```

后缀 `_lq` / `_lf` 疑为 login-quality / login-flow 标记，两者行为一致（都能出码、都能被扫）。

---

## 9. 安全观察点

| # | 观察 | 影响 |
|---|---|---|
| 1 | **`confirmed` 响应直接下发 `sessionid`** | 任何能调 `check_qrconnect` 的人，只要知道 token 就能拿 Cookie。token 在二维码里明文（`qrcode_index_url` 的 query） → 谁截到二维码谁就能抢先登录 |
| 2 | **`token` 无熵校验、无速率限制** | 32 hex = 128 bit，碰撞不可行；但**无速率限制 + 无失败计数**，配合「静默降级」使爆破无意义、也使滥用无感知 |
| 3 | **`next` 不校验域名** | 开放重定向。`redirect_url` 原样回填，可诱导用户完成登录后被跳到任意站（虽不带凭证，但可用于钓鱼衔接） |
| 4 | **`scanned` 阶段不泄露身份** | ✅ 正向设计：`scan_user_info.user_id = 0`，只给匿名头像 |
| 5 | **`confirm_qrcode` 依赖 `confirmed_url` 里的 csrf 参数** | 若该参数可预测/可重放，则确认动作可被伪造。实测需手机侧登录态，风险受限于此 |
| 6 | **`redirect_url` 不带一次性票据** | 意味着**登录态与会话完全绑定在 Cookie 上**，一旦 Cookie 泄露（XSS / 中间人 / 共享设备）即可无限期复用（`sessionid` 60 天，`sid_guard` 声明 360 天） |
| 7 | **`aid=513755` 硬编码且单一** | 无多租户隔离问题，但也意味着该 aid 是唯一入口，风控策略集中 |
| 8 | **`sid_guard` 明文含过期时间** | 可离线解析会话寿命，便于做会话管理审计 |
| 9 | **页面隐藏即停止轮询** | 非安全项，但影响可用性：切标签页会导致扫码后前端不感知 |
| 10 | **`a_bogus` / `msToken` 形同虚设** | 声称的 JS 签名风控未在服务端强制，客户端可完全绕过 |

---

## 10. 复现脚本

### 10.1 最小可用（出码 + 轮询 + 拿 Cookie）

```python
#!/usr/bin/env python3
import requests, time, json, base64, os

HOST = "https://hongguoduanju.com"
AID  = "513755"
UA   = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")

S = requests.Session()
S.headers.update({"User-Agent": UA, "Referer": HOST + "/"})

# 1. 出码
r = S.get(f"{HOST}/passport/web/get_qrcode/",
          params={"next": HOST + "/", "aid": AID}, timeout=15)
d = r.json()["data"]
token = d["token"]
png = base64.b64decode(d["qrcode"].split(",", 1)[1])
open("qr.png", "wb").write(png)
print("token:", token, "ttl:", d["expire_time"] - int(time.time()))
print("请用红果短剧 App 扫描 qr.png")

# 2. 轮询
while True:
    j = S.get(f"{HOST}/passport/web/check_qrconnect/",
              params={"next": HOST + "/", "token": token, "aid": AID},
              timeout=15).json()["data"]
    st = j["status"]
    if st in ("5", "expired"):
        token = j["token"]                       # 服务端自带新码，原地续期
        open("qr.png", "wb").write(base64.b64decode(j["qrcode"].split(",", 1)[1]))
        print("refreshed:", token)
    elif st in ("2", "scanned"):
        print("scanned by", j.get("scan_app_id"))
    elif st in ("3", "confirmed"):
        print("CONFIRMED user_id:", j["user_data"]["user_id"])
        print("cookies:", json.dumps(S.cookies.get_dict(), ensure_ascii=False))
        break                                        # Cookie 已在 jar 里
    time.sleep(1)

# 3. 验证登录态
me = S.get(f"{HOST}/passport/account/info/v2/", params={"aid": AID}).json()
print("account_info:", json.dumps(me["data"], ensure_ascii=False)[:300])
```

### 10.2 完整抓包版

`/Users/star/Downloads/hongguo/artifacts/qr/serve.py` —— 一体化服务：
- 后台线程出码 + 1.1s 轮询 + 自动续期
- HTTP `127.0.0.1:8899` 提供实时看板（二维码 + 倒计时 + 状态 + 日志）
- `confirmed` 时落盘 `confirmed.json` / `final.json`（含 14 条 Set-Cookie 与后续跳转链）

输出文件：

| 文件 | 内容 |
|---|---|
| `qr_current.png` | 当前有效二维码（随续期覆盖） |
| `state.json` | 实时状态 `{token, expire_time, status, idx}` |
| `flow.jsonl` | 全量请求/响应（163 条，含所有 Set-Cookie 与 `Bd-Ticket-Guard-*`） |
| `transitions.jsonl` | 状态跳变时间线（仅变化时记录） |
| `scanned.json` | `scanned` 原始响应 |
| `confirmed.json` | `confirmed` 原始响应（含 `user_data` 全量） |
| `final.json` | 跳转链 + Cookie 前后快照 + 登录态探针 |

---

## 11. 与其它登录方式的一致性

对照 `docs/红果短信登录分析.md`、`docs/红果手机号验证码登录-完整实现文档.md`：

| 维度 | 短信验证码 | 扫码 |
|---|---|---|
| 入口 | `/passport/web/sms/...` | `/passport/web/get_qrcode/` |
| 前置 | 滑块 / 图形验证码 | 无 |
| 中间凭证 | `sms_token` | `token`（32hex_lq） |
| 发 Cookie 接口 | `/passport/web/login/` 类 | `check_qrconnect` (confirmed) |
| Cookie 集合 | 同一套 14 条 | **完全相同** |
| `redirect_url` | 带 `next` 回填 | 同 |

**结论**：扫码登录只是「凭证获取方式」不同，**发牌逻辑完全复用 Passport 的会话签发**，因此最终的 `sessionid` / `uid_tt` / `sid_guard` 结构与短信登录一模一样。这也印证了红果 Web 站用的是字节统一的 Passport 中台，而非自研登录体系。

---

## 12. 附录：抓包原始数据

- `artifacts/qr/flow.jsonl` 163 行（`get_qrcode` × 5、`check_qrconnect` × 158），其中：
  - `new` × 153（正常等待态，1.1s/次）
  - `expired` × 2（自动续期两次，均由响应内新码替换）
  - `scanned` × 2（首次跳变 + 1 次重复确认）
  - `confirmed` × 1（登录成功，14 条 Set-Cookie）
- `artifacts/qr/qr_001.png` ~ `qr_003.png`：三张不同 token 的二维码
- 实测轮询间隔：中位数 **1.10s**，与源码 `setTimeout(..., 1000)` 吻合（网络 RTT ≈ 100ms）

**关键时间戳**（Unix）：

| 事件 | ts | 时刻 |
|---|---|---|
| 出码 | 1792755875.571 | 19:44:35 |
| 扫码 | 1792755894.517 | 19:44:54 |
| 确认 | 1792755896.930 | 19:44:56 |
| Cookie 落地 | 1792755896.930 | 同上（0 延迟） |
| 首页登录态验证 | 1792755900+ | 19:44:56+ |
