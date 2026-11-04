# iOS 端移植说明（HGTV for iPhone）

> 目标：以 `feature-phone`（Android 手机端）为基准，做一款功能对等、视觉统一的 iOS 应用。
> 工程：`ios/HGTV.xcodeproj`（脚本生成，见 `ios/tools/gen_project.py`）。
> 验证：`xcodebuild` 编译通过；签名 12 组固定向量逐字节一致；接口链路与解密链路在本机跑通。

---

## 一、结论先说

| 维度 | 结果 |
| --- | --- |
| 页面 | 推荐 / 分类 / 榜单 / 我的 / 详情 / 播放 / 搜索 / 设置 / 账号，9 个页面全部落地 |
| 导航 | 底部四 tab + 系统返回栈（`NavigationStack`），详情 / 播放 / 搜索 / 设置 / 账号为压栈页 |
| 数据 | 与 Android 同一套接口、同一套签名、同一套解析；设备注册、分类榜单、推荐流、详情、相关作品、搜索、播放地址、弹幕全部可用 |
| 播放 | CENC 加密流在端内改包解密后交给 AVPlayer；AVFoundation 已确认能读出时长 / 视频轨 / 音频轨 |
| 本机数据 | 收藏 / 稍后看 / 历史 / 进度 / 隐藏 / 搜索记录 / 全部偏好，语义与 Android 逐项一致，备份可互相合并 |
| 视觉 | 20 套新拟态主题、360×640 设计画布、柔光、自绘 tab 图标，全部按 Android 的令牌重建 |
| 未做 | 手机号验证码登录、账号侧收藏/历史同步、服务端联想词、横屏全屏（详见 §8） |

一句话：**内容消费链路完整可用，账号同步与少数平台专属能力留了明确的边界。**

---

## 二、目录与分层

```
ios/
├── HGTV.xcodeproj/                 # 由 tools/gen_project.py 生成（新增 .swift 后重跑一次）
├── HGTV/
│   ├── App/                        # 入口、路由、根视图、全局提示
│   ├── Design/                     # 主题、画布、柔光、图标、控件、海报卡、视频层
│   ├── Core/
│   │   ├── Sign/                   # SM3 / AesV3 / Signer / 设备注册
│   │   ├── API/                    # HTTP 与内容接口
│   │   ├── Models.swift            # 剧集、分类、榜单、播放枚举
│   │   ├── Rules.swift             # 热度 / 集数 / 季号 / 分页 / 去重窗口 / 降级
│   │   ├── MediaCrypto.swift       # 播放地址解密
│   │   └── Mp4Crypto.swift         # CENC 样本表解析 + 逐样本解密 + 改包
│   ├── Data/                       # 书库（偏好+记录）、会话、离线视频
│   └── Features/                   # 九个页面 + 共用网格 / 选集 / 菜单 / 调试面板
└── tools/
    ├── gen_project.py              # 工程生成
    ├── signer_oracle.swift         # 签名向量比对（12 组）
    ├── api_smoke.swift             # 接口链路冒烟
    └── decrypt_check.swift         # 解密 + AVFoundation 容器校验
```

分层规则与 Android 同一条思路：**规则在 `Core`，状态在 `Data`，版面在 `Features`**。
页面里不出现接口路径、不出现签名、不出现解密。

---

## 三、页面映射

| Android（feature-phone） | iOS | 说明 |
| --- | --- | --- |
| `PhoneMainActivity` | `RootView` + `BottomTabBar` | 四 tab + 贴底栏；推荐页隐藏状态栏 |
| `page/PhoneRecommendPage` | `Features/Recommend/RecommendPage` | 三体裁横向分页；关掉「左右滑动」后只能点 tab |
| `PhoneFeedView` | `Features/Recommend/FeedView` + `FeedStore` | 一屏一集、上下翻页、进屏即播；不写历史 |
| `PhoneVideoPage` | `Features/Recommend/VideoCardView` | 信息区层级、通栏操作条、进度条与手势与 Android 同构 |
| `page/PhoneCategoryPage` | `Features/Category/CategoryPage` | 体裁 tab + 细分筛选行 + 海报网格 |
| `page/PhoneRankPage` | `Features/Rank/RankPage` | 四个榜单；「上新」不画名次 |
| `page/PhoneMinePage` | `Features/Mine/MinePage` | 账号行 + 历史/收藏/稍后看 + 筛选 + 网格 |
| `PhoneDetailActivity` | `Features/Detail/DetailView` | 封面 + 三个动作 + 简介展开 + 选集 + 相关剧集 |
| `PhonePlayerActivity` | `Features/Player/PlayerView` + `PlayerStore` | 竖屏全屏、手势、选集抽屉、播放设置、定时停止 |
| `PhoneSearchActivity` | `Features/Search/SearchView` | 输入框 + 记录/热搜 + 结果网格 |
| `PhoneSettingsActivity` | `Features/Settings/SettingsView` | 四大项分页，项目一一对应 |
| `PhoneAccountActivity` | `Features/Account/AccountView` | 扫码登录 + 本机记录概览 + 退出 |
| `ui/PhoneEpisodeGrid` | `Features/Shared/EpisodeGrid` | 每行 5 个、分页集数跟设置 |
| `ui/PhonePosterFeed` / `PhonePosterCard` | `Features/Shared/SeriesGrid` + `Design/PosterCard` | 网格、角标、进度条、长按菜单 |
| `ui/PhoneCardMenu` | `CardMenuItems`（原生 `contextMenu`） | 继续观看 / 收藏 / 稍后看 / 详情 / 删除记录 / 隐藏 |
| `ui/PhoneDebugOverlay` | `Features/Shared/DebugOverlay` | 调试模式下常驻读数 |

---

## 四、交互映射

| 能力 | Android | iOS |
| --- | --- | --- |
| 页面切换 | 多 Activity + 系统返回栈 | `NavigationStack` + 值路由（`AppRoute`） |
| 返回 | 系统返回键 / 各页 `onBackPressed` | 左上返回 + 系统侧滑 |
| 底部导航 | 贴底 `PhoneTabBar` | 同结构自绘，含安全区 |
| 双击当前 tab 回顶 | 支持 | 省略：iOS 用「点状态栏回顶」这一系统手势 |
| 播放单击 | 播放 / 暂停 | 一致 |
| 长按左右 1/3 | 2 倍速 | 一致 |
| 长按中间 1/3 | 播放设置 | 一致（系统 sheet 承载） |
| 左右拖 | 调进度 | 一致 |
| 上下滑（播放页） | 上一集 / 下一集 | 一致 |
| 长按海报 | 卡片菜单 | 原生 `contextMenu`（同样的条目） |
| 选集 | 底部抽屉 | 系统 sheet（`.presentationDetents`） |
| 弹窗确认 | 自绘 `PhoneDialog` | 系统 `alert`（危险操作带 destructive 角色） |
| 播放器全屏 | 按钮切横屏 | 本轮保持竖屏（见 §8） |

**保留的 iOS 特性**：系统侧滑返回、系统 sheet / alert / contextMenu、`ShareLink` 导出备份、
`fileImporter` 恢复备份、系统音量与音频会话（锁屏继续播放）、Dynamic Type 上限内缩放。

---

## 五、数据契约

### 5.1 请求链路

```
设备注册（匿名） → query 里带设备档案 + 本机身份
   → 签名六件套（x-gorgon / x-helios / x-medusa / x-ladon / x-argus / x-ss-req-ticket）
   → 分类 / 榜单 / 推荐（POST 分类接口，同一份 body 结构）
   → 详情（POST video_detail，空响应自动改 GET 重试一次）
   → 相关作品（GET plan/v:version）
   → 搜索（GET search/tab，漫剧 19 + 短剧 11 两个 tab 合并去重）
   → 播放地址（POST multi_video_model → fallback_api → video_list → main_url 解密）
```

签名移植以 Android 的 `Signer.kt` 为唯一依据，**用固定向量逐字节对齐**：

```bash
swiftc -O ios/HGTV/Core/Sign/*.swift ios/tools/signer_oracle.swift -o /tmp/signer_oracle
/tmp/signer_oracle android/core/src/test/resources/signing.json
# 全部一致（12 组）
```

### 5.2 设备身份

- 注册接口：`POST log.snssdk.com/service/2/device_register/`，body 是 `ttEncrypt(gzip(JSON))`。
- gzip 用「stored 块」实现：格式合法、零依赖（iOS 侧没有现成的 gzip 模块）。
- 落盘键与 Android 同名：`registered-device-id` / `registered-install-id` / `device-profile` /
  `registered-cookies`，重装前导出、重装后恢复即可免重注册。
- **身份要同时进 query 与签名器**：`x-medusa` 里编了 `device_id`，两边不一致时平台回 `110001`
  （这条踩过，见 §7 的验证记录）。

### 5.3 播放

播放接口按 `device_id` 计额度，本机注册身份往往已被打满，因此每次取地址**现造一个 15 位随机 id**
（`iid` 仍用注册值），空响应就换号重试——与 Android `modelDeviceId` 同一条处置。

平台下发的 MP4 是 **CENC AES-CTR** 加密（`encv` + 逐样本 IV + `spade_a` 密钥）。
AVFoundation 不接受这种容器，iOS 端的做法是：

1. 整集取回（单集 10–40MB）；
2. 解析 `moov → trak → stbl` 的 `stsz/stsc/stco/saiz/saio`，得到每个样本的
   `start / end / IV / 子样本偏移`；
3. 改包：`encv` 换回 `frma` 里记录的原始编码、`sinf` 改成 `free` 并清零；
4. 逐样本用 AES-ECB 批量生成 CTR keystream 再异或；
5. 落到 `Caches/video/<vid>-<quality>.mp4`，交给 AVPlayer。

验证（本机实测）：

```bash
swiftc -O ios/HGTV/Core/Sign/*.swift ios/HGTV/Core/API/*.swift \
       ios/HGTV/Core/MediaCrypto.swift ios/HGTV/Core/Mp4Crypto.swift \
       ios/HGTV/Data/OfflineVideo.swift ios/HGTV/Core/Models.swift \
       ios/HGTV/Core/Rules.swift ios/HGTV/Data/Session.swift \
       ios/tools/decrypt_check.swift -o /tmp/decrypt_check
/tmp/decrypt_check
# 剧集 … · 300 集 · vid=…
# 地址 720P，密钥 有
# 解密文件 … size=13492642
# AVFoundation: duration=170.6s video=1 audio=1
# 视频轨 1280x720
# ✅ 可播放容器
```

代价是**首次进入一集要等下载 + 解密**（十几 MB 通常几秒）。所以：
- 同一集同一清晰度只解一次，二次进入直接播本地文件；
- 缓存上限 1.2GB，按时间清理；
- 界面在准备期间显示「正在准备播放…」。

### 5.4 本机数据

`Library` 一份 JSON（记录）+ 一份 JSON（偏好），键名与默认值对齐 Android：

| 分类 | 项 |
| --- | --- |
| 播放 | 清晰度上限 1080、倍速 1×、画面模式完整画面、画面缩放 100%、自动连播开、快进 10 秒、面板 6 秒 |
| 显示 | 剧名 / 集数 / 时间 / 网速四项全关 |
| 弹幕 | 全屏、标准速度、聚合开、聚合 2 条、字号 100%、不透明度 100% |
| 列表 | 每行 2 张、选集每页 20、推荐页左右滑动开、启动进推荐 |
| 内容 | 推荐范围三类全开、受众不限、搜索源 App |
| 外观 | 深海蓝、界面倍率 1.6（对应 Android 的「标准」档） |
| 记录 | 收藏 / 稍后看 / 历史（≤200 条）/ 进度 / 隐藏 / 搜索记录（≤20 条） |

进度判据与 Android 一致：**离片尾不到 3 秒算看完**；历史按更新时间倒序；一剧一条。

备份格式（`exportBackup`）是 `{version, exportedAt, data, prefs}`，与 Android 的
`LibraryBackup` 语义相同：恢复时本机优先、备份补充，不做覆盖式回滚。

---

## 六、视觉系统

| 项 | Android | iOS |
| --- | --- | --- |
| 画布 | 360×640（横 640×360），`min(宽/设计宽, 高/设计高)` | 同一套；尺寸来自 `GeometryReader` |
| 几何倍率 | 画布比例 × 1.6（界面倍率） | 画布比例 × 1.25 × 界面倍率 |
| 字号 | 绕 15 号轴心压缩 × 0.5 基准 × 界面倍率 | 画布 1:1 换算 × 界面倍率（落在 iOS 正文档） |
| 主题 | 20 套，八色定义 + 派生 | 同一张表、同一条派生公式（`Theme.swift`） |
| 柔光 | 预渲染半分辨率位图 | 原生双层阴影 + 内阴影描边 |
| 图标 | `PhoneGlyph` 自绘 | tab 四枚自绘，其余用 SF Symbols |
| 底栏 | 42 设计单位 + 导航条 | 42 设计单位 + 安全区 |

**为什么几何不是 1.6**：Android 那套 dp 值在手机屏上偏小，靠 1.6 的界面倍率补回来；
iOS 的 point 本身就是与密度无关的抽象单位，再乘 1.6 会让 16 的内边距变成 28pt——那是平板的手感。
因此几何取 1.25（16 → 21.8pt ≈ 屏宽 5.5%，与 Android 的 6.2% 同量级），
字号按画布 1:1（13 → 14.2pt），正好落在 iOS 正文区间。两者都随屏宽与界面倍率缩放，
从 iPhone SE 到 Pro Max 版面比例不变。

---

## 七、验证记录

| 项 | 方法 | 结果 |
| --- | --- | --- |
| 编译 | `xcodebuild -sdk iphonesimulator build` | ✅ BUILD SUCCEEDED |
| 签名 | 12 组 `signing.json` 向量（hash / gorgon / helios / medusa） | ✅ 全部一致 |
| 设备注册 | 真机网络请求 | ✅ 拿到 device_id / install_id 与 4 条 Cookie |
| 榜单 | `rank(page:1,.comic)` | ✅ 100 条 + 100 个名次 |
| 分类 | `categoryFeed(needPanel:true)` | ✅ 20 条 + 7 行筛选面板 |
| 详情 | `detail(id:)` | ✅ 300 集、分集 vid 有序 |
| 播放地址 | `stream(id:720)` | ✅ 720P 地址（解密后的 CDN 链接） |
| 解密 | `Mp4Crypto` + AVFoundation | ✅ duration 170.6s / 1 视频轨 1280×720 / 1 音频轨 |
| 搜索 | `search("修仙")` | ✅ 37 条 |

本机没有安装 iOS 模拟器运行时（`xcrun simctl list runtimes` 为空），因此**界面渲染没有做截图级验证**，
只做到编译 + 数据链路 + 容器可播级验证。真机/模拟器上的第一轮回归清单见 §9。

---

## 八、已知边界（与 Android 的差异）

| 项 | 现状 | 原因 / 后续 |
| --- | --- | --- |
| 手机号验证码登录 | 未移植，只保留扫码 | Passport 是另一套签名头（`x-argus` 等）与二次验证流程，工作量与扫码相当；扫码是等价入口 |
| 账号侧收藏 / 历史 / 点赞 / 预约 | 未接入 | 需要账号接口族与合并规则（`AccountMerge`）；本机记录完全可用 |
| 收藏「更新至 N 集」 | 只显示本机看到第几集 | 依赖 `FavoriteMonitor` 的服务端比对，属账号侧能力 |
| 搜索联想词 | 本机记录 + 热搜匹配 | 服务端联想接口未移植；点任意候选的搜索行为一致 |
| 横屏全屏播放 | 只做竖屏 | 播放页锁竖屏；Android 的横屏入口依赖窗口旋转，iOS 需额外处理方向锁定与转场 |
| 视频解码切换 | 只显示「系统解码」 | AVFoundation 不暴露软解档位，强行做只是假开关 |
| 弹幕渲染 | 自绘滚动，三档模式 + 聚合 | 表情图未做（接口里是文本标签，Android 有表情表） |
| 双击 tab 回顶 | 省略 | iOS 用系统「点状态栏回顶」替代 |
| 播放缓存 | 按集缓存解密后的文件 | 不做边下边播（AVPlayer 对 CENC 的限制），首播有等待 |

---

## 九、真机回归清单

1. 首次启动：设备注册是否成功（设置 → 系统高级 → 调试模式，看「设备」一行）。
2. 推荐页：三条体裁能否起播；上下滑是否一屏一集；左右滑是否被设置正确锁住。
3. 播放页：单击 / 长按三区 / 左右拖 / 上下滑；选集抽屉翻页；倍速与清晰度改完是否生效。
4. 进度：退出再进是否续播；「我的 → 历史」是否有记录与进度条。
5. 收藏 / 稍后看 / 隐藏：长按菜单四项；榜单隐藏后是否从列表消失。
6. 搜索：App 源与官网源各搜一次；无结果与失败两种提示是否分得开。
7. 设置：20 套主题切换后整屏是否立即换色；字体大小五档是否同时改字号与控件。
8. 备份：导出 → 清空缓存 → 恢复，收藏与历史是否回来。
9. 弱网：断网进播放页，错误页与「重试」是否可用。
10. 不同机型：SE（小屏）/ Pro Max（大屏）各看一遍版面比例与安全区。

---

## 十、构建

```bash
cd ios
python3 tools/gen_project.py          # 新增/删除 .swift 后必须重跑
xcodebuild -project HGTV.xcodeproj -target HGTV -sdk iphonesimulator \
           -configuration Debug build CODE_SIGNING_ALLOWED=NO
```

真机运行需要 `DEVELOPMENT_TEAM` 与签名；工程默认 `TARGETED_DEVICE_FAMILY = 1`（仅 iPhone），
`IPHONEOS_DEPLOYMENT_TARGET = 17.0`（用到 `scrollTargetBehavior(.paging)` 与 `@Observable` 时代的 API）。
