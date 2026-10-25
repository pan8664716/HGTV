# 红果 App 端请求签名算法

适用对象：`https://api5-normal-sinfonlinea.fqnovel.com`、`https://api5-normal-sinfonlineb.fqnovel.com`
这类字节系 App 接口（分类与榜单那条自 2026-10-21 起也要签名，见 [HONGGUO_API.md](HONGGUO_API.md) 4.7）。

算法来源与落点：

| 位置 | 说明 |
| --- | --- |
| `android/core/src/main/kotlin/com/hgtv/star/core/Signer.kt` | Android/TV 端 Kotlin 移植，**只发 branch 0** |
| `android/core/src/main/kotlin/com/hgtv/star/core/AesV3.kt` | medusa 用的分组变换 |
| `android/core/src/main/kotlin/com/hgtv/star/core/Bytes.kt` | SM3 / MD5 / 端序 / 位运算原语 |
| `android/core/src/main/kotlin/com/hgtv/star/core/VendorConstants.kt` | 设备参数、UA、sbox、hashTable |
| `android/core/src/test/resources/signing.json` | 固定向量（开发期从上游 JS 参考实现逐组核对后固化） |

移植上游：`woshishiq1/drpys` commit `22261adfa31435e3b3ef5a730b8a2a75bcaf1715`（GPL-3.0）。

---

## 1. 总览

签名只需要三样输入：

1. `query`：URL `?` 之后的完整字符串（**不含** base、不含 `?`），已 URL 编码，参数顺序即拼接顺序；
2. `body`：POST 请求体字节；GET 时为 `null`（等价于 16 字节 0 的 MD5）；
3. `ts`：秒级时间戳（头里叫 `x-khronos`）。

```
设备参数 values ─┐
ts / _rticket   ─┴─→ query ──┬─→ branch(query, md5(body), ts) == 0 ? 放行 : _rticket+1 重试(≤128)
                             ├─→ x-gorgon = f(query, body, ts, rand16)
                             ├─→ x-helios = f(ts)
                             └─→ x-medusa = f(url, body, ts)   ← 内部再算一次 hashF13
```

五个签名头彼此独立：`x-gorgon` 与 `x-medusa` 各自带自己的随机数，`x-medusa` 内部的 `hashF13`
与准入用的 `branch` 是同一个函数的两种用法（前者用 20 字节摘要，后者只看低 4 位）。

---

## 2. 公共原语

```text
sm3(x)                 SM3-256，返回 32 字节
md5(x)                 MD5，返回 16 字节（x-ss-stub 时转大写 hex）
le32/be32/le64         小端 / 大端定长整数
varint(n)              protobuf 变长整数（负数按补码 64 位无符号推进）
zigzag(n)              sint 编码：n<0 → -2n-1，否则 2n
proto([tag, value, type])  type ∈ string/bytes/message/sint/float
rol32/ror32/ror64      循环移位
getIv(seed, data)      逐字节混合（奇偶位置两套公式）
sumMd5(data)           12 轮自定校验和
```

### 2.1 getIv（核心混合函数）

```text
value = u32(seed)
for i in 0..len(data)-1:
    i 偶数: value = u32((value >>> 4) ^ value ^ (value << 6) ^ data[i])
    i 奇数: value = u32(~((value >>> 7) ^ value ^ (data[i] | (value << 12))))
return value
```

### 2.2 sumMd5（20 字节摘要的后 4 字节）

```text
check = 0x20220420
for i in 0..11:
    temp  = (i 偶) ? (check >>> 3) ^ check : (check >>> 5) ^ check
    check = (i 偶) ? data[i] ^ (check << 7) : data[i] | (check << 11)
    if i 奇: check = ~check
    check = u32(check ^ temp)
return u32((check | 4) ^ 0x1000000)
```

---

## 3. 准入闸门：branch 与 `_rticket` 抖动

```text
branch(query, bodyMd5, ts):
    iv  = getIv(getIv(getIv(0x20230928, sm3(query)), bodyMd5), le32(ts))
    low = iv & 15
    return low - ((low * 171) >> 9) * 3      // 等价于 low % 3
```

`branch ∈ {0,1,2}`：

- **只有 0 会被平台接受**；
- 1 是服务端不认的变体（上游 JS 里对播放接口是「跳过 1、其他也发」，Kotlin 端直接只找 0）；
- 2 有独立的 `branch2F13` 实现（另一套 MD5 变体 + 64×64 顺序表），本工程不发送。

因为 `_rticket` 参与 `sm3(query)`，改它就能改 `branch`。做法：

```text
now = 毫秒时间戳; ts = now / 1000
for offset in 0..127:
    ticket = now + offset
    query  = encode(values + ts + _rticket)   // 顺序：设备参数 → ts → _rticket
    if branch(query, bodyMd5, ts) == 0: 采用该 ticket 并停止
```

试不出来就报错（128 次内必中，期望 3 次）。

---

## 4. x-gorgon

```text
input = md5(query)[0:4] + (body ? md5(body)[0:4] : 00 00 00 00)
        + 00 00 00 00 + le32(67503104) + be32(ts)          // 共 20 字节
key   = [0x4a, 0x40, 0x16, (rand>>8)&255, 0x47, 0x6c, 0x01, rand&255]
out   = rc4_variant(input, key)                            // 见下
for i in 0..len(out)-1:
    v    = ((out[i] >> 4) | (out[i] << 4)) & 255           // 半字节交换
    next = out[i+1] 或 out[0]（最后一个字节）
    out[i] = (~(reverseBits8((next ^ v) & 255) ^ 20)) & 255
result = hex("8404" + [rand&255, (rand>>8)&255, 0x40, 0x01] + out)   // 40 位 hex
```

`rc4_variant` 与标准 RC4 的差别：PRGA 不做 `s[i] ↔ s[j]` 交换，只做 `s[i] = s[j]`，且索引从 1 开始。

```text
s = [0..255]; j = 0
for i in 0..255: j = (j + s[i] + key[i % 8]) & 255; s[i] = s[j]   // KSA 同标准
j = 0
for n in 0..len(input)-1:
    i = n + 1; j = (j + s[i]) & 255; y = s[j]; s[i] = y
    out[n] = input[n] ^ s[(y + y) & 255]
```

---

## 5. x-helios

```text
rand   = 32 位随机数
digest = md5(le32(rand) + "8662")                 // 16 字节
ascii  = hex(digest) 的 ASCII 字节（32 字节）
words  = [ascii.i64LE(0), .i64LE(8), .i64LE(16), .i64LE(24)]

table = [words[0]]; b0 = words[0]; b8 = words[1]
for i in 0..33:
    x = u64(ror64(b8, 8) + b0); x ^= i
    table.push(x ^ ror64(b0, 61))
    b0 = x ^ ror64(b0, 61); b8 = 下一个 words 元素

text    = "{ts}-1588093228-8662"
pad     = 16 - len(text) % 16，填充值 = 该长度本身
output  = le32(rand)
for 每个 16 字节块 (a, b) = (u64LE@0, u64LE@8):
    for i in 0..33:
        b = table[i] ^ u64(a + ror64(b, 8))
        a = b ^ ror64(a, 61)
    output += le64(a) + le64(b)
result  = base64(output)
```

---

## 6. x-medusa

最复杂的一支，顺序是：`hashF13` → protobuf message → `xmxor` 混淆 → 外层包裹 → `AesV3` 加密 → base64。

### 6.1 hashF13（branch 0 分支，20 字节）

```text
iv      = getIv(getIv(getIv(0x20230928, sm3(query)), bodyMd5), le32(ts))
ivV0    = (iv & 15) / 3                       // 0..4
ivV1    = [0xc4a78580,0xb3c0fd39,0xc58c5686,0xc9aa3ba7,0xf5a7adf2,0x963c2ed1][ivV0]
countV1 = (ivV1 + ts + 1) & 255
countV2 = u32(ivV1 + ts)
tt      = hashTable[0..63] 每个 ror32(值, countV1)
shift   = (countV2 + 2) & 7
pad     = [0xfa,0x45,0x61,0xd7] 每个 ((v | v<<8) >> shift) & 255   // 4 字节
data    = sm3(query) + bodyMd5 + le32(ts) + pad + 00 00 00 00 00 00 01 a0

di = data 按大端 u32 读入（长度必为 4 的倍数）
for i in 0..111:
    x = di[i+1]; y = di[i+14]
    di.push(u32(di[i] + di[i+9] + (rol32(x,14)^rol32(x,25)^(x>>>3))
                                 + (rol32(y,13)^rol32(y,15)^(y>>>10))))

init   = [0x7aba4fc8,0x67166507,0x6403fa00,0x340f512f,
          984304912,3005047866,2874125293,2152413264] 每个 ror32(值, countV2)
chosen = 每个 ivV0 一个 11 元组（见 Signer.kt 的 chosen 表）
d = init 的可变副本
for i in 0..chosen[0]-1:
    base = ivV1 + i
    n1 = ((d[c3] ^ d[c4]) & d[c1]) ^ d[c3]
    n2 = rol32(d[c1],26) ^ rol32(d[c1],21) ^ rol32(d[c1],7)
    n4 = u32(di[base & 127] + n1 + n2 + tt[base & 63] + d[c5])
    n5 = rol32(d[c2],30) ^ rol32(d[c2],19) ^ rol32(d[c2],10)
    n6 = (d[c2] & d[c6]) | ((d[c2] | d[c6]) & d[c7])
    old = d[c9]
    d 旋转：d.unshift(d.pop())                 // Kotlin: d.add(0, d.removeAt(7))
    d[c10] = u32(n5 + n6 + n4); d[c8] = u32(old + n4)

ret    = 8 × be32(d[i] + init[i])              // 32 字节
folded = ret[0:16] ^ ret[16:32]
return folded + le32(sumMd5(folded))           // 20 字节
```

### 6.2 密钥派生 keyHash + 混淆 xmxor

```text
signKey = 8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5
hash    = sm3(signKey + le32(rand32) + signKey)          // 32 字节
d1      = (rand32 >> 16) & 255
d2      = u32(~(((d1 << 11) | (rand32 >>> 24)) ^ (d1 >> 5) ^ d1))
seed    = le32(d2)                                       // 4 字节
```

`xmxor(data, key)`：对每个字节取 `key[(i*4) & 28]` / `key[(i*4)&28 + 1]` 做「半字节交换 → 加 → 取反异或 → 循环移位 → 加 → 异或 → 取反」，
结果**倒序**写入；随后 4 步定点修正（首字节、次字节、第三字节、逐字节链式修正、末字节、首字节再修）。Kotlin 与 JS 实现逐句对应，见 `Signer.kt` 的 `xmxor`。

### 6.3 message（protobuf）

```text
nested = {1:111, 2:10, 3:694367, 5:586952199}        // 全部 sint
env    = {1: 100+rand(21), 2:146331399, 3:146331396, 5:7,
          6:"v04.06.04.03-bugfix", 7: 10001+rand(2000),
          12: deviceProto(), 13: {1: now/1000, 2:-2, 4:200}, 14: version_name}
message = {
  1:  f7e85ffad7d7dc3bd62ac87057cf6118   (bytes)
  2:  3        3:  rand32          4:  "8662"
  5:  device_id                6:  "1588093228"
  7:  version_name             8:  "v04.06.04-ml-android"
  9:  67503104                 10: 4001000000000000 (bytes)
  12: ts                       13: queryBodyTs = hashF13(sm3(query), bodyMd5, le32(ts), ts) (bytes)
  14: sm3(query)[0:6] (bytes)  15: nested (message)
  16: "AXYQOS6n2m60x1fVZHIrH3iol"
  17: ts                       19: sm3(query + bodyMd5 + "none") (bytes)
  20: "none"                   21: 312
  23: env (message)            24: "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0,\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}"
}
```

`deviceProto` 是 38 个字段的固定设备指纹（字段表见 `Signer.deviceProto`），其中 `device_id` / `version_name`
取自 `VendorConstants.device`。

### 6.4 外层包裹

```text
transformed = reverse(hex("4001000000000000") + xmxor(message, hash))
for i: transformed[i] ^= seed[(~i) & 3]

check = ((sm3(query)[0] & 63) << 14) | 0x18000001 | ((queryBodyTs[0] & 63) << 8)
packed = 0x35 + le32(rand32b) + le32(check) + transformed + [rand32>>16, rand32>>24]
encrypted = AesV3(key=f1593376766ea98d34f31b057a9d5be4, ts).encrypt(packed, iv=1fe109a4125283f418de9e051a969e12)

version = 03000000f7e85ffad7d7dc3bd62ac87057cf6118          // 20 字节
prefix  = 每 4 字节：le32(version.u32LE(i) ^ ts)              // 共 20 字节
result  = base64(prefix + [rand32&255, (rand32>>8)&255, 0x00, 0x01] + encrypted)
```

`packed` 必须 ≥ 248 字节（`AesV3.encrypt` 的硬性前置条件）。

### 6.5 AesV3（红果自有分组变换，非标准 AES）

| 环节 | 规则 |
| --- | --- |
| sbox | `VendorConstants.sbox` 1024 字节，按 `ts & 3` 取第 `word` 个 256 字节块 |
| 常量 | `con = [[1,0,2,3],[2,0,3,1],[0,1,3,2],[1,0,2,3]][ts&3]`（JS 里叫 `con2`，`con` 是死代码） |
| 初始向量 | `init = [0xca025ddc,0x823dc546,0xc9420583,0xc298225f][ts&3]` |
| 密钥扩展 | `xor(le32(init)×4, key)` 拼 32 字节 0 填充 → 8 轮步进到 12 组 4 字节轮密钥 |
| 加密轮 | 2 轮：addCon → sub(带行置换) → shift(order 表) → 第 1 轮加 shiftCon + MixColumns(0x1b) → addCon；收尾 add |
| 明文压缩 | 输入 ≥248 字节，每 8 字节抽 1 位拼成 32 字节「明文」，第 32 字节固定为 1 |
| 密钥导出 | 32 字节明文分 2 块，CBC（iv 给定）加密 → 两块共 32 字节作为「密钥」 |
| 回写 | 把密钥的 8 位逐位写进原字节的固定比特位（`&0xdf|…` 等 8 条掩码），密文前置 1 字节密钥末位 |

---

## 7. 其余请求头

| 头 | 值 |
| --- | --- |
| `x-khronos` | `ts` |
| `x-ss-req-ticket` | 抖动试出来的 `_rticket` |
| `x-ladon` | `base64(be32(ts))` |
| `x-argus` | `base64(le32(ts))` |
| `x-tt-request-tag` | `t=0;n=0` |
| `sdk-version` | `2` |
| `passport-sdk-version` | `50561` |
| `x-xs-from-web` | `0` |
| `x-tt-dt` | 空 |
| `Accept` | `application/json; charset=utf-8,application/x-protobuf` |
| `User-Agent` | 有 body → `VIDEO_UA`（播放器 71332）；无 body → `APP_UA`（App 72232） |

POST 额外：

| 头 | 值 |
| --- | --- |
| `Content-Type` | `application/json; charset=UTF-8` |
| `x-vc-bdturing-sdk-version` | `3.7.2.cn` |
| `x-ss-stub` | `MD5(body)` 的**大写** hex |

弹幕接口另外覆盖 `x-ss-stub=""` 并追加 `comment-source` / `server-channel`。

query 编码：`encodeURIComponent` 风格，空格用 `%20`，`~` 不转义（Kotlin 端 `Signer.encode`）。

---

## 8. 常量清单

| 名称 | 值 |
| --- | --- |
| IV 种子 | `0x20230928` |
| sumMd5 种子 | `0x20220420` |
| gorgon SDK 版本 | `67503104` |
| helios 盐 | `8662`、`1588093228` |
| medusa signKey | `8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5` |
| medusa AesV3 key | `f1593376766ea98d34f31b057a9d5be4` |
| medusa AesV3 iv | `1fe109a4125283f418de9e051a969e12` |
| medusa version 前缀 | `03000000f7e85ffad7d7dc3bd62ac87057cf6118` |
| medusa message 常量 | `f7e85ffad7d7dc3bd62ac87057cf6118`、`4001000000000000` |
| hashF13 尾部 | `00000000000001a0` |
| branch2 顺序表 | `BRANCH2_ORDERS` 64×64（本工程未使用） |

---

## 9. 实现落点与差异

1. **Kotlin 只发 branch 0**。`Signer.sign` 用 `_rticket` 从 `now` 起 +0..+127 抖动找 branch 0；
   上游 JS 的播放接口是「跳过 branch 1、其余都发」，这里更严格。
2. **版本参数两套**。搜索/弹幕用 App 版本（`version_code=72232`、`version_name=7.2.2.32`），
   播放地址解析用播放器版本（`71332` / `7.1.3.32`）；混用会被平台按旧客户端处理。
3. **随机数注入**。Kotlin `Signer(clock, random)` 两个参数都可注入，测试里用固定 LCG 复现。
4. **编码细节**。JS `pythonUrlEncode` 额外转义 `!'()`，Kotlin `Signer.encode` 不转义；
   现有参数集合不含这些字符，两者等价。
5. **JS `AesV3.con` 是死代码**，生效的是 `con2`；Kotlin 里只有一份 `con`，语义等同 `con2`。

---

## 10. 验证

```bash
# Kotlin 侧逐组比对 branch / hashF13 / gorgon / helios / medusa（12 组固定向量）
./android/gradlew -p android :core:test --tests 'com.hgtv.star.core.SignerTest'
```

固定向量 `android/core/src/test/resources/signing.json` 已随源码入库，构建与测试不需要任何外部参考实现。

向量覆盖：有 body / 无 body、12 个连续时间戳，`hashF13` 输出 hex、`gorgon` 40 位 hex、
`helios` 与 `medusa` 为 base64。任何一位常量改动都会让 12 组全部失败。

> 平台随时可能改字段或换签名材料；本文描述的是当前实现所依赖的行为。

---

## 11. 联网实测结论（2026-10-09）

实测方式：`android/core/src/test/kotlin/com/hgtv/star/core/LiveSigningProbeTest.kt`（Kotlin 签名器直接发真请求）
＋ 同一批对照实验逐头篡改后重发，观察平台判定。

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) HONGGUO_LIVE=1 \
  ./android/gradlew -p android --offline :core:test --tests 'com.hgtv.star.core.LiveSigningProbeTest' --info
```

不设 `HONGGUO_LIVE=1` 时该测试自动跳过，离线构建不受影响。

### 11.1 结果

| 端点 | 方法 | 正确签名 | 结论 |
| --- | --- | --- | --- |
| `POST /novel/player/multi_video_model/v1/` | POST + body | HTTP 200、`code=0`、24 KB video_model | 通过 |
| `POST /novel/commentapi/comment/list/{vid}/v1/` | POST + body | HTTP 200、`code=0`、637 B 弹幕 | 通过 |
| `GET /reading/bookapi/search/tab/v` | GET | HTTP 200、`code=0`、72 KB | 通过（同一路径 POST 是 404，方法不参与签名） |

### 11.2 平台实际校验哪些头

以 `multi_video_model` 为靶，逐个篡改后重发（同一 vid、同一时间窗口）：

| 改动 | 平台响应 | 判定 |
| --- | --- | --- |
| 基线（branch=0，全套签名头） | `code=0` | 通过 |
| `branch=1` | `code=110001` 未知异常 | **branch 是硬闸门** |
| `branch=2` | `code=0` | 通过（该端点接受 2，不只接受 0） |
| `x-medusa` 改 1 位 / 删除 | `code=110001` | **强校验** |
| `x-helios` 改 1 位 / 删除 | `code=110001` | **强校验** |
| body 改了但签名不变 | `code=110001` | body 经 medusa 绑定，**强校验** |
| 去掉全部签名头 | `code=110001` | 必须带签名 |
| `x-gorgon` 全随机 40 hex / 删除 | `code=0` | 该端点**不校验** gorgon |
| `x-ladon` / `x-argus` 删除或端序写反 | `code=0` | 不校验 |
| `x-ss-stub` 改错 | `code=0` | 不校验 |
| `x-ss-req-ticket` 与 query 里的 `_rticket` 不一致 | `code=0` | 不校验 |
| `x-khronos` 删除或与 query `ts` 差 1 小时 | `code=0` | 不校验（`ts` 以 query 为准） |
| UA 版本号改错 | `code=0` | 不校验 |

结论：**实际被校验的只有 `x-medusa`（含 body 绑定）与 `x-helios`，加上 branch ≠ 1 这一条**；
`x-gorgon` / `x-ladon` / `x-argus` / `x-ss-stub` / `x-ss-req-ticket` / `x-khronos` / UA 当前是「发了不亏、不发也行」。
它们仍然值得保留：平台可能随时开始校验，且部分头在别的端点（如 CDN、风控）另有用途。

对 Kotlin 端的意义：`Signer` 只发 branch 0 的策略**安全且更严格**（0 与 2 都被接受，1 必被拒）；
`x-medusa` / `x-helios` 一旦算错就一定失败，这也是固定向量测试必须逐位对齐的原因。
