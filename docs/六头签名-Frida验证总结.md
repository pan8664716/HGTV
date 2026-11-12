# 六请求头生成算法 —— Frida 被动验证总结

> **后续验证更正（同日）**：本文第七节把 `x-gorgon` 列为「必须重新逆向」，
> 该推断已被证伪；逐头消融表明服务端不校验 gorgon 负载，200 空响应的唯一
> 原因是 `x-medusa` 的 detection 位掩码。同时，本文写作时未发起线上请求，
> 后续已完成新 did 的单/批量多次实测（18/18 成功、0 空响应）。
> 完整根因、实验矩阵与修复见 [`200空响应-根因与修复.md`](200空响应-根因与修复.md)。

日期：2026-11-11 · 目标：`com.phoenix.read` 74332 · 设备：`VER AN00`（`127.0.0.1:16416`）
Frida 17.16.4 · 样本：**127 条抓包行 → 10 个去重请求样本（其中 6 个带完整 URL）**

---

## 一、结论先行

**login-lab 的六头实现只有 2 个头与当前官方 App 逐字节一致；其余 4 个存在形态或常量层面的偏差。**
根因不是实现 bug，而是**协议版本漂移**：仓库复刻的是旧版签名协议（Medusa 版本 3），
真机 74332 已升到版本 5，并简化了部分头。

| 头 | 官方真机形态 | `hgsign.Signer.sign()` | `sign_playback()` | 判定 |
| --- | --- | --- | --- | --- |
| `x-khronos` | 十进制秒，等于 query 里的 `ts` | `str(ts)` | `str(ts)` | ✅ 一致 10/10 |
| `x-helios` | 36 B = 4 B seed + 32 B 密文 | 同形态 | 同形态 | ✅ **逐字节一致 10/10** |
| `x-argus` | **4 B** = `base64(le32(ts))` | 226 B 完整 Argus | **4 B `le32(ts)`** | ⚠️ 仅 `sign_playback` 正确 |
| `x-ladon` | **4 B 随机值**（非时间戳） | 36 B 完整 Ladon | 4 B `be32(ts)` | ⚠️ 长度对、取值来源不对 |
| `x-gorgon` | 26 B：`8404` + rnd16 + flag(`0000`/`0405`) + 20 B | `8404` + rnd16 + `4001` + 20 B 旧混淆 | 同 `sign()` | ❌ **算法不匹配** |
| `x-medusa` | 908–955 B；前 20 B = `version_words ^ ts` | 结构相同、常量为版本 3 | 结构相同 | ⚠️ **结构正确、常量过期** |

一句话：**`sign_playback()` 的形态远比 `sign()` 接近真机**，但两者都不是逐字节等价。

---

## 二、关键证据

### 1. `x-helios`：严格逐字节复现（最强正证据）

头部前 4 字节即随机 seed。用该 seed 反推 `Signer` 的 RNG，再以真机 `ts` 重算：

```
seed = le32(x-helios[0:4])
computed = base64decode(Signer(rng=lambda: seed/2^32).helios(ts))
computed == x-helios   →  10/10 True
```

说明 `helios()` 的密钥派生、34 轮 key schedule、分组变换全部正确。

### 2. `x-argus`：确认为 4 字节时间戳

10/10 样本满足 `base64decode(x-argus) == le32(ts)`。
用同一 `ts=1794381370` 调 `sign_playback()` 得到 `Ohb0ag==`，与真机样本**完全相同**。
而 `sign()` 生成的完整 Argus 为 226 B，形态就不对。

### 3. `x-medusa`：算法结构正确，常量表过期

把前 20 B 按 4 字节小端拆成 5 个 word，逐个与 `ts` 异或：

```
word_i ^ ts = [0x00000005, 0xca4f4b2d, 0x430d7549, 0x2caeb53f, 0x56cc6d22]
```

这组值在全部 9 个完整样本上**恒定不变**，精确验证了仓库的 prefix 公式
`le32(i32at(version_bytes, i, True) ^ ts)` 是对的。反推官方常量：

| | version_bytes |
| --- | --- |
| 官方 74332（本次反推） | `050000002d4b4fca49750d433fb5ae2c226dcc56` |
| 仓库 `hgsign.py` / Go | `03000000f7e85ffad7d7dc3bd62ac87057cf6118` |

首字 `05` vs `03` 即协议版本号，其余 16 字节是随版本更换的常量块。
**这是本次可直接落地的修复点**：替换 `version_bytes` 即可让 medusa 前缀与真机一致。

### 4. `x-gorgon`：不是常量差异，是变换本身不同

- 正向：`hgsign.gorgon(query, None, ts, rnd16)` 与真机 26 B **0/6 匹配**（带 URL 的样本）。
- 参考实现：`~/Downloads/hongguo/tools/hg_sign.py` 的 `8404401c0000 + XOR KEY + rotl8 + rev8` 同样 **0/6 匹配**。
- 逆向：对两套混淆分别写出精确逆变换（均通过自检，能 100% 还原各自生成的 payload），
  用它们逆真机 20 B 负载，均**还原不出** `md5(query)[:4]`、`le32(67503104)`、`be32(ts)` 中任一已知字段。
- 常量扫描：对混淆常量 `c = 0..255` × rnd16 大小端 × 两套逆变换穷举，
  以 `payload[16:20] == be32(ts)` 为判据，**无任何命中**。

因此排除"只是改了个常量/字节序"，真机使用了新的 native 变换。
另注：真机 flag 字段出现 `0000` 与 `0405` 两种，仓库固定写 `4001`。

### 5. 三方同源性（不等于真机正确性）

`cd tools/signature-go && go test ./...` **通过**：Python / Go / Kotlin
（`android/core/src/test/resources/signing.json`）三份实现互相一致。
这只能证明**移植没有走样**，不能证明与当前官方 App 一致 —— 本次 Frida 结果正是反例。

---

## 三、参考 `hg` 材料的结论

| 来源 | 用途 | 结论 |
| --- | --- | --- |
| `~/Downloads/hongguo/tools/hg_sign.py` | 旧版 X-Gorgon 参考实现 | 与真机 74332 不匹配（见上） |
| `~/Downloads/hongguo/hgcap/hgcap.js` | BoringSSL `SSL_write` 明文抓包思路 | **采用**，是本次最可靠的 URL+六头配对来源 |
| `~/Downloads/hongguo/docs/红果推荐上报接口参数逆向分析.md` | 历史抓包 `84046030…` / `8404401c0000` 分支 | 属另一接口/版本分支，不能直接套用 |
| `tools/signature-go` `SignPlayback` | 4 B ladon/argus 的播放请求形态 | 与真机形态一致；其 `.runtime/hg-api-test-20261111-final.json` 记录播放接口 **3/3 成功** |

需要区分两个概念：**"服务端可接受"**（历史 Go 实测已证明）与**"逐字节等价于官方 App"**（本次证明不成立）。
风控可能只看其中部分头，所以前者成立不代表后者成立。

---

## 四、验证方法与踩过的坑

### 采集链路（全程被动）

新增 `tools/login-lab/frida/`：

| 文件 | 作用 |
| --- | --- |
| `sixheader_capture.js` | 被动 hook：`NetworkParams.tryAddSecurityFactor`、`okhttp3.Request.Builder.build`、`Headers.Builder.add`、`RequestEncryptUtils.tryEncryptRequestBody`、Cronet header 方法、BoringSSL `SSL_write` 明文兜底 |
| `capture_sixheaders.py` | 驱动 `frida` CLI（`-q -t N`），解析 `===ROW===` JSON，落盘结构化样本 |
| `verify_sixheaders.py` | 逐头解码 + 重算 + 逆变换 + 跨样本常量分析，输出 JSON 报告 |

产物：`.runtime/sixheader-capture-20261111-spawn{2,3}.json`、
`.runtime/sixheader-verify-20261111.json`（报告不含 Cookie / 完整 URL）。

### 三个必须记住的技术事实

1. **绝不主动调用 native 签名函数**。早期 `re/frida_call_security*.js` 在错误上下文里调
   metasec，导致 `libmetasec_ml.so` SIGSEGV（见 `.runtime/frida-signature-probe3-20261111.log`）。
   本次只观察 App 自己发起的请求。
2. **Frida 17 的 Python `Session.create_script()` 不注入 Java 桥**（脚本里 `typeof Java === 'undefined'`，
   且 `require` 也未定义）。只有 `frida` CLI 会注入。因此 runner 改为调用 CLI。
3. **spawn 时默认 ClassLoader 指向系统 webview**，直接 `Java.use` 一律 `ClassNotFoundException`。
   必须先 `Java.enumerateClassLoaders()` 找到能加载 App 类的 loader 并设
   `Java.classFactory.loader`，再装 hook。

另：attach 到已空闲的 App 抓不到流量，请求集中在**启动阶段**，故用 spawn 采集。

---

## 五、内存与进程控制

按本次要求执行：

- **未启动 jadx / IDA**。已有的 `.runtime/jadx-*.java` 与 `re/ida_metasec_*.py` 产物足够支撑分析，
  避免大 APK 全量反编译把内存打爆。
- 若后续要逆向 `x-gorgon` 的新变换，建议：`jadx` 用 `--single-class` 单类反编译并显式限制堆
  （`-Xmx1g`），IDA 用批处理脚本分时段跑完即退出，**不要同时开 GUI 与全量索引**。
- Frida 采集进程用 `-q -t N` 自动退出；每次 hook 仅安装一次（`--no-auto-reload`），
  SSL 明文输出限流 40 条、单条上限 12 KB，防止日志膨胀。
- 验证结束已 `am force-stop com.phoenix.read`；`/tmp/probe_java.*` 临时文件已删除。
- 复查：Mac 侧**无 frida / jadx / IDA 残留**。仅剩 PID 9147 Gradle daemon（`-Xmx3g`，
  非本次产生，未动；如需释放可执行 `./gradlew --stop`）。

---

## 六、限制与风险

- 样本全部是 **GET 请求**。带 body 的 POST 未覆盖，因此 `x-ss-stub`、body MD5 参与签名的
  部分（`gorgon`/`argus`/`medusa` 的 body 摘要）**未验证**。
- **未发起新的线上请求**（避免触发风控）。线上可用性仅引用已有实测产物。
- 采集环境是模拟器，设备指纹与真机不同；但头部结构与算法结论与设备指纹无关。
- 原始抓包日志含 Cookie，保存在本机 `.runtime/`（gitignore），报告已剔除敏感内容。
- `x-gorgon` 的结论是"两套已知算法均无法复现"，属**复现失败**，不是对真机算法性质的断言；
  它很可能已改为新版 native 变换。

---

## 七、修复优先级建议

1. **低风险**：把 `hgsign.py` / `hongguo74332.py` / Go 侧的 `version_bytes` 更新为
   `050000002d4b4fca49750d433fb5ae2c226dcc56`，先让 medusa 前缀对齐。
2. **统一入口**：内容/播放接口统一走 `sign_playback()` 的 4 B 形态，废弃 `sign()` 的
   226 B Argus / 36 B Ladon；同时查清真机 `x-ladon` 那 4 B 随机值的来源。
3. **需重新逆向**：`x-gorgon` 的新变换（目标 `libsscronet.so` / `libmetasec_ml.so`），
   这一步才需要 jadx/IDA，注意上文的内存约束。
4. **补样本**：抓一组带 body 的 POST 请求，补齐 body 摘要与 `x-ss-stub` 的验证。

---

## 八、复现命令

```bash
cd /Users/star/Documents/code/hongguoTV

# 1) 采集（spawn 模式，40 秒，自动退出）
python3 tools/login-lab/frida/capture_sixheaders.py \
    --seconds 40 --poke 2 \
    --output tools/login-lab/.runtime/sixheader-capture-YYYYMMDD.json

# 2) 逐头对拍
python3 tools/login-lab/frida/verify_sixheaders.py \
    tools/login-lab/.runtime/sixheader-capture-20261111-spawn2.json \
    tools/login-lab/.runtime/sixheader-capture-20261111-spawn3.json \
    --output tools/login-lab/.runtime/sixheader-verify-20261111.json

# 3) 三方同源校验
cd tools/signature-go && go test ./...
```

本次**未改动任何应用代码、版本号、CHANGELOG，未做推送**；新增内容仅为
`tools/login-lab/frida/` 下的三个验证脚本、`docs/` 下本文档与 `.runtime/` 采集产物。
