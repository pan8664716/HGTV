# Android 代码目录树与文件职责

> 基线生成日期：2026-11-17。文件总数为 656；新增、移动、删除文件后必须同步更新本文件。

> 适用范围：`android/` 下的 Kotlin、Java、XML、Gradle、ProGuard、JSON、Baseline Profile 和构建属性文件。
> 不包含：图片二进制、`build/`、`.gradle/`、`.idea/`、`.kotlin/`、`local.properties`、`keystore.properties`。

## 使用方式

定位 bug 时先查本文件的模块、目录职责和关键符号，再进入对应文件。目录名与 Kotlin 包名允许不一致；移动文件不代表改了包名。

## 快速定位索引

| 问题域 | 首选目录 | 关键入口 |
| --- | --- | --- |
| 全局可调参数 | `core/` | `AppConfig.kt` |
| 内容接口、账号/详情/搜索/推荐数据 | `core/content/` | `ContentRepository.kt` 及各子域 Service/Parser |
| 登录、设备注册、凭据 | `core/account/` | `PhoneLogin.kt`、`QrLogin.kt`、`DeviceRegister.kt` |
| 播放地址、缓存、降级、速度 | `core/playback/` | `PlaybackCache.kt`、`PlaybackDegradation.kt` |
| 本机历史、收藏、设置持久化 | `shared/data/`、`core/library/` | `Library.kt`、`LibraryIndex.kt` |
| 手机信息流 | `feature-phone/feed/` | `PhoneFeedView.kt` |
| 手机播放页 | `feature-phone/player/` | `PhonePlayerActivity.kt` |
| 手机详情与竖屏视频版面 | `feature-phone/detail/` | `PhoneDetailActivity.kt`、`PhoneVideoPage.kt` |
| 手机设置 | `feature-phone/settings/` | `PhoneSettingsActivity.kt` |
| 电视主页面、焦点和生命周期 | `feature-tv/`、`feature-tv/system/` | `TvMainActivity.kt` |
| 电视列表、首页剧架 | `feature-tv/list/` | `ListPage.kt`、`SeriesGrid.kt`、`HomeScreen.kt` |
| 电视播放内核与控制 | `feature-tv/player/` | `PlayerController.kt`、`PlaybackHud.kt` |
| 电视共用 UI 与弹窗 | `feature-tv/ui/` | `TvComponents.kt`、`TvDialog.kt` |
| 封面加载与缓存 | `shared/data/` | `ArtworkLoader.kt` |
| 弹幕绘制与模型 | `shared/media/`、`core/media/` | `DanmakuView.kt`、`Danmaku.kt` |

## 实时维护规则（强制）

1. 新增、删除、移动、重命名任何 Android 代码或资源文件时，必须在同一批修改中同步更新本文件。
2. 文件职责发生实质变化时，同步更新“作用”和“关键符号”；仅移动文件时更新路径，不复制 `REFACTORING.md` 的批次历史。
3. 提交前用 `git status --short` 和 `git diff --check` 确认目录树与实际文件一致。
4. 本文件是快速导航入口；完整重构批次、回滚 hash 与测试记录仍写入 `REFACTORING.md`。

## 模块总览

```text
android/
├── app/  (23 files, 12 directories)
├── core/  (227 files, 27 directories)
├── feature-phone/  (142 files, 11 directories)
├── feature-tv/  (154 files, 17 directories)
├── shared/  (104 files, 24 directories)
├── gradle/  (1 files, 1 directories)
├── root/config/  (3 files, 1 directories)
```

## 目录职责

- `core/`：core 模块入口、共享门面或根级配置。
- `core/account/`：账号、设备注册、二维码/手机号登录、凭据与网页模板。
- `core/catalog/`：目录、分类、榜单、首页布局、筛选和回到顶部策略。
- `core/common/`：跨域公共文案与进程级线程预算。
- `core/content/`：内容仓库及其模型、解析、请求编排、缓存和各业务服务。
- `core/crypto/`：AES、Medusa、Argus、签名和字节编解码。
- `core/identity/`：设备身份、设备等级、UA/版本档案。
- `core/label/`：卡片标签、角标、菜单与海报文案模型。
- `core/library/`：本机书库、历史、观看进度、备份和写队列。
- `core/media/`：封面 URL、弹幕模型、图片看门狗和滑动缓存。
- `core/network/`：DNS、TLS、HTTP 传输、网络计时与请求作用域。
- `core/playback/`：播放地址、缓存、探测、降级、速度、预取与播放按键。
- `core/recommend/`：推荐行为、推荐缓存、数据源和统计配置。
- `core/search/`：搜索来源、键盘、诊断、语音查询与局域网搜索服务。
- `core/session/`：登录会话与 Cookie/凭证拦截。
- `feature-phone/`：feature-phone 模块入口、共享门面或根级配置。
- `feature-phone/account/`：手机账号登录、账号同步和设备身份。
- `feature-phone/debug/`：手机调试报告、网络调试和推荐上报。
- `feature-phone/detail/`：手机详情页、相关内容区和竖屏视频版面。
- `feature-phone/feed/`：手机信息流页面、适配器、手势、生命周期与预取。
- `feature-phone/page/`：手机独立功能页。
- `feature-phone/player/`：手机播放器、横竖屏控制、选集、恢复和预加载。
- `feature-phone/search/`：手机搜索页面。
- `feature-phone/settings/`：手机设置页面、横竖屏布局、同步与系统 UI。
- `feature-phone/ui/`：手机共用控件与视觉组件。
- `feature-tv/`：feature-tv 模块入口、共享门面或根级配置。
- `feature-tv/account/`：电视账号面板与账号交互。
- `feature-tv/debug/`：电视调试面板、指标和网络日志。
- `feature-tv/detail/`：电视详情页。
- `feature-tv/home/`：电视首页、推荐和首页布局。
- `feature-tv/input/`：电视遥控/触屏输入与焦点控制。
- `feature-tv/library/`：电视本机书库与局域网同步。
- `feature-tv/list/`：电视列表、网格、首页剧架与分页。
- `feature-tv/navigation/`：电视导航、状态恢复、主页面 tick 与按键分发。
- `feature-tv/player/`：电视播放内核、HUD、恢复、渲染、诊断与多文件 facade。
- `feature-tv/search/`：电视搜索页。
- `feature-tv/settings/`：电视设置页。
- `feature-tv/system/`：TvMainActivity 的创建、配置、权限结果、保存状态和系统回调。
- `feature-tv/ui/`：电视共用控件、弹窗、样式、键盘与工具。
- `shared/`：shared 模块入口、共享门面或根级配置。
- `shared/account/`：共享账号模型。
- `shared/architecture/`：共享架构、执行器和播放状态协调。
- `shared/compat/`：Android 版本与平台兼容封装。
- `shared/data/`：共享数据层、Library、缓存、网络和资源加载。
- `shared/debug/`：共享日志、诊断和调试支持。
- `shared/feature/`：跨端业务功能控制器。
- `shared/feed/`：推荐流存储、调度和状态。
- `shared/lan/`：局域网同步基础能力。
- `shared/media/`：共享媒体、弹幕、视频数据源和播放失败解析。
- `shared/nav/`：共享路由定义。
- `shared/search/`：共享搜索状态。
- `shared/ui/`：共享 UI 工具、颜色、滑块和对话框布局。

## 文件清单与作用

### app

#### `app/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `build.gradle.kts` | Gradle Kotlin DSL 构建或设置脚本。 | gradle |
| `multidex-keep.pro` | R8/ProGuard 混淆与保留规则。 | proguard |
| `proguard-rules.pro` | R8/ProGuard 混淆与保留规则。 | proguard |

#### `app/src/main/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AndroidManifest.xml` | Android 应用/模块清单，声明组件、权限与启动配置。 | manifest |
| `baseline-prof.txt` | Android Baseline Profile，记录启动与常用路径的热点类/方法。 | baseline profile |

#### `app/src/main/res/drawable/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `app_icon.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `splash_icon.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `startup_window.xml` | Drawable 图形资源，根节点 layer-list。 | layer-list |

#### `app/src/main/res/mipmap-anydpi-v26/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ic_launcher.xml` | 启动图标/位图描述资源，根节点 adaptive-icon。 | adaptive-icon |
| `ic_launcher_round.xml` | 启动图标/位图描述资源，根节点 adaptive-icon。 | adaptive-icon |

#### `app/src/main/res/values/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ic_launcher_background.xml` | Values 资源定义，根节点 resources。 | resources |
| `styles.xml` | Values 资源定义，根节点 resources。 | resources |

#### `app/src/main/res/values-v21/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `styles.xml` | Values 资源定义，根节点 resources。 | resources |

#### `app/src/main/res/values-v23/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `styles.xml` | Values 资源定义，根节点 resources。 | resources |

#### `app/src/main/res/values-v28/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `styles.xml` | Values 资源定义，根节点 resources。 | resources |

#### `app/src/main/res/values-v31/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `styles.xml` | Values 资源定义，根节点 resources。 | resources |

#### `app/src/main/res/xml/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `network_security_config.xml` | XML 配置资源，根节点 network-security-config。 | network-security-config |

#### `app/src/main/java/com/umeng/pagesdk/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PageManger.java` | apm-plugin 2.0.0 injects calls to the newer pagesdk, while apm 1.5.2 does not ship it. This no-op compatibility class keeps release builds and runtime safe; page monitoring is not… | — |

#### `app/src/main/kotlin/com/hgtv/star/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DeviceFormActivity.kt` | 首页首次启动与设置页共用的设备类型选择器；按页面使用手机 / 电视拟态弹窗，电视支持遥控器，手机 / 平板触摸确认并 6 秒自动返回，电视直接进入；记录首次形态选择锚点。 | class DeviceFormActivity、fun showPhoneChooser、fun showTvChooser、fun startCountdown |
| `HgtvApplication.kt` | 应用进程入口，负责 Application 级初始化和全局运行时装配。 | class HgtvApplication |
| `HgtvStartup.kt` | 启动页负责的本地初始化；首帧后启动缓存、书库、会话存储，并把心跳、TNC、推荐曝光配置和友盟延后到播放首帧或兜底超时。 | object HgtvStartup、fun afterFirstFrame、fun onDeviceFormSelected |
| `LaunchActivity.kt` | 启动页与设备形态路由入口；首次启动打开统一设备类型选择器，完成后路由到对应主界面，并记录首帧链路锚点。 | class LaunchActivity、fun openDeviceFormSelector、fun targetFor |

### core

#### `core/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `build.gradle.kts` | Gradle Kotlin DSL 构建或设置脚本。 | gradle |

#### `core/src/main/kotlin/com/hgtv/star/core/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AppConfig.kt` | 应用级可调参数的唯一来源；除网络、内容、播放和缓存外，还集中导航/首页/设置顺序及手机 / 电视几何尺寸，供 core / shared / phone / TV 引用。 | object AppConfig |

#### `core/src/test/resources/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `signing.json` | JSON 配置或数据文件。 | json |

#### `core/src/test/kotlin/com/hgtv/star/core/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AccountCredentialsTest.kt` | cookieStore.xml 解析：用真实的 Java 序列化片段做输入。 | class AccountCredentialsTest |
| `AccountSnapshotTest.kt` | 自动化测试，覆盖 AccountSnapshotTest 相关行为。 | class AccountSnapshotTest |
| `ArtworkKeyTest.kt` | 封面请求的 Key 只由**地址**决定。 | class ArtworkKeyTest |
| `ArtworkUrlTest.kt` | 自动化测试，覆盖 ArtworkUrlTest 相关行为。 | class ArtworkUrlTest |
| `BackToTopPolicyTest.kt` | 「回到顶部」的显隐规则：两端共用这一份，因此锁死行为。 | class BackToTopPolicyTest |
| `CardMenuTest.kt` | 自动化测试，覆盖 CardMenuTest 相关行为。 | class CardMenuTest |
| `CategoryCatalogTest.kt` | 自动化测试，覆盖 CategoryCatalogTest 相关行为。 | class CategoryCatalogTest |
| `ContentRepositoryTest.kt` | 自动化测试，覆盖 ContentRepositoryTest 相关行为。 | class ContentRepositoryTest |
| `DanmakuEmojiTest.kt` | 自动化测试，覆盖 DanmakuEmojiTest 相关行为。 | class DanmakuEmojiTest |
| `DanmakuTest.kt` | 自动化测试，覆盖 DanmakuTest 相关行为。 | class DanmakuTest |
| `DetailCacheTest.kt` | 详情落盘缓存：推荐页起播的「详情」这一段（0.4~0.6s）靠它省掉。 | class DetailCacheTest |
| `EpisodePagingTest.kt` | 选集分页的页码与区间文案（电视与手机共用同一份，见 EpisodePaging）。 | class EpisodePagingTest |
| `FasterBrowsingTest.kt` | 自动化测试，覆盖 FasterBrowsingTest 相关行为。 | class FasterBrowsingTest |
| `FeedCursorTest.kt` | 自动化测试，覆盖 FeedCursorTest 相关行为。 | class FeedCursorTest |
| `HeatLabelTest.kt` | 热度文案的回归测试。 | class HeatLabelTest |
| `LabelStyleTest.kt` | 系统标签配色的回归测试。 | class LabelStyleTest |
| `LatestWriteQueueTest.kt` | 自动化测试，覆盖 LatestWriteQueueTest 相关行为。 | class LatestWriteQueueTest |
| `LibraryBackupTest.kt` | 自动化测试，覆盖 LibraryBackupTest 相关行为。 | class LibraryBackupTest |
| `LibraryIndexTest.kt` | 自动化测试，覆盖 LibraryIndexTest 相关行为。 | class LibraryIndexTest |
| `LibraryTransferServerTest.kt` | 自动化测试，覆盖 LibraryTransferServerTest 相关行为。 | class LibraryTransferServerTest |
| `LiveCooldownTest.kt` | 自动化测试，覆盖 LiveCooldownTest 相关行为。 | class LiveCooldownTest |
| `LiveCoverDumpTest.kt` | 自动化测试，覆盖 LiveCoverDumpTest 相关行为。 | class LiveCoverDumpTest |
| `LiveDiagTest.kt` | 诊断用：区分「IP 级风控」与「播放路径/参数问题」。 | class LiveDiagTest |
| `LiveIsolateTest.kt` | 隔离变量：签名分支 / query 参数集 / 身份（随机 vs 已注册）/ Cookie。 | class LiveIsolateTest |
| `LivePageSizeProbeTest.kt` | 自动化测试，覆盖 LivePageSizeProbeTest 相关行为。 | class LivePageSizeProbeTest |
| `LivePassportSignTest.kt` | 用 branch-2 签名打真实的 Passport 端点，验证签名**被接受**。 | class LivePassportSignTest |
| `LivePhoneLoginTest.kt` | 手机号登录链路的联网验收：注册设备 → 发码 → （人工给码后）换会话。 | class LivePhoneLoginTest |
| `LivePlaybackBranch2Test.kt` | 自动化测试，覆盖 LivePlaybackBranch2Test 相关行为。 | class LivePlaybackBranch2Test |
| `LivePlaybackBurn2Test.kt` | 自动化测试，覆盖 LivePlaybackBurn2Test 相关行为。 | class LivePlaybackBurn2Test |
| `LivePlaybackBurnTest.kt` | 自动化测试，覆盖 LivePlaybackBurnTest 相关行为。 | class LivePlaybackBurnTest |
| `LivePlaybackBurstTest.kt` | 自动化测试，覆盖 LivePlaybackBurstTest 相关行为。 | class LivePlaybackBurstTest |
| `LivePlaybackFieldsTest.kt` | 自动化测试，覆盖 LivePlaybackFieldsTest 相关行为。 | class LivePlaybackFieldsTest |
| `LivePlaybackFreshTest.kt` | 自动化测试，覆盖 LivePlaybackFreshTest 相关行为。 | class LivePlaybackFreshTest |
| `LivePlaybackMatrixTest.kt` | 自动化测试，覆盖 LivePlaybackMatrixTest 相关行为。 | class LivePlaybackMatrixTest |
| `LivePlaybackOldProfileTest.kt` | 自动化测试，覆盖 LivePlaybackOldProfileTest 相关行为。 | class LivePlaybackOldProfileTest |
| `LivePlaybackRecheckTest.kt` | 自动化测试，覆盖 LivePlaybackRecheckTest 相关行为。 | class LivePlaybackRecheckTest |
| `LivePlaybackVariantTest.kt` | 自动化测试，覆盖 LivePlaybackVariantTest 相关行为。 | class LivePlaybackVariantTest |
| `LiveQuotaTest.kt` | 自动化测试，覆盖 LiveQuotaTest 相关行为。 | class LiveQuotaTest |
| `LiveRegComboTest.kt` | 自动化测试，覆盖 LiveRegComboTest 相关行为。 | class LiveRegComboTest |
| `LiveSigningProbeTest.kt` | 自动化测试，覆盖 LiveSigningProbeTest 相关行为。 | class LiveSigningProbeTest |
| `LowEndPlaybackTest.kt` | 自动化测试，覆盖 LowEndPlaybackTest 相关行为。 | class LowEndPlaybackTest |
| `MediaCryptoTest.kt` | 自动化测试，覆盖 MediaCryptoTest 相关行为。 | class MediaCryptoTest |
| `OptionalFilterTest.kt` | 推荐筛选项的降级状态机（OptionalFilter）：回空页先重试、两次即永久关掉。 | class OptionalFilterTest |
| `PassportSignerTest.kt` | Passport（branch 2）签名的固定向量测试。 | class PassportSignerTest |
| `PhoneLoginTest.kt` | 手机号登录的协议测试：把 Passport 的响应换成本地桩，钉住四件事—— 编码方式（XOR 0x05 + hex）、请求路径、成功判据、以及会话凭证的收集。 | class PhoneLoginTest |
| `PhoneRegionTest.kt` | 区号与号码归一：两端（电视 / 手机）走同一份实现，这里钉住它。 | class PhoneRegionTest |
| `PhoneSearchServerTest.kt` | 自动化测试，覆盖 PhoneSearchServerTest 相关行为。 | class PhoneSearchServerTest |
| `PictureWatchdogTest.kt` | 自动化测试，覆盖 PictureWatchdogTest 相关行为。 | class PictureWatchdogTest |
| `PlaybackAttemptLedgerTest.kt` | 按集重试额度的重置语义（与 PlaybackAttemptLedger 的注释一一对应）。 | class PlaybackAttemptLedgerTest |
| `PlaybackCacheTest.kt` | 落盘缓存的两件事必须成立，否则二次启动会**更慢**（读坏数据 → 重新下载 → 再写一遍）： 1. 索引写出去再读回来，与原来逐字段一致（含样本偏移、长度、IV、加密子样本偏移）； 2. 数据坏了 / 文件总长对不上时返回 null，而不是把半个索引交给播放器。 | class PlaybackCacheTest |
| `PlaybackCacheUrlByIdTest.kt` | 「按集作废地址」的边界。 | class PlaybackCacheUrlByIdTest |
| `PlaybackComfortTest.kt` | 自动化测试，覆盖 PlaybackComfortTest 相关行为。 | class PlaybackComfortTest |
| `PlaybackDegradationTest.kt` | 降级决策的规格表（与 PlaybackDegradation 的注释一一对应）。 | class PlaybackDegradationTest |
| `PlaybackFailurePolicyTest.kt` | 两端共用的失败处置：钉住**判定顺序**与**额度记账的时机**。 | class PlaybackFailurePolicyTest |
| `PlaybackInfoLineTest.kt` | 播放信息条的两段文案：两端共用，因此锁死格式。 | class PlaybackInfoLineTest |
| `PlaybackSpeedTest.kt` | 自动化测试，覆盖 PlaybackSpeedTest 相关行为。 | class PlaybackSpeedTest |
| `PlaybackStageTest.kt` | 阶段标题表的两条不变量： 1. 每个阶段都有**自己的**标题（两档共用一个标题，用户与调试面板都分不出来）； 2. 未知阶段退回「播放失败」，不能把常量本身当标题读给用户。 | class PlaybackStageTest |
| `PreparedFreshnessTest.kt` | 「预热结果还能不能用」的判据。 | class PreparedFreshnessTest |
| `PublicDnsTest.kt` | PublicDns 的活体校验：手写的 DNS 报文必须能被公共 DNS 认，解析结果必须能用。 | class PublicDnsTest |
| `QrLoginUrlTest.kt` | 出码 / 轮询的 URL 拼装。 | class QrLoginUrlTest |
| `RankBoardTest.kt` | 自动化测试，覆盖 RankBoardTest 相关行为。 | class RankBoardTest |
| `RecommendCacheTest.kt` | 推荐快照 + 「停在哪一部」的落盘：冷启动续播（i → i+1）全靠这两份文件。 | class RecommendCacheTest |
| `RecommendFlowFilterTest.kt` | 推荐取页的两条两端共用判定（RecommendFlow.fresh / RecommendFlow.stalled）。 | class RecommendFlowFilterTest |
| `SearchDeviceHealTest.kt` | 搜索失败时的处置顺序：**先换设备重搜，再降级官网**。 | class SearchDeviceHealTest |
| `SeekStepPumpTest.kt` | 快进 / 快退的调度外框（SeekStepPump）：合并窗里只落地一次、窗内连按会加速、 换集 / 手拖之后作废。 | class SeekStepPumpTest |
| `SeekStepTest.kt` | 快进 / 快退的共用规则（SeekStep）：两端的手感靠这一份单测锁住。 | class SeekStepTest |
| `SeriesSeasonsTest.kt` | 自动化测试，覆盖 SeriesSeasonsTest 相关行为。 | class SeriesSeasonsTest |
| `SessionInterceptorTest.kt` | 注册请求的「匿名」开关。 | class SessionInterceptorTest |
| `SignerTest.kt` | 自动化测试，覆盖 SignerTest 相关行为。 | class SignerTest |
| `SleepTimerChoiceTest.kt` | 「定时关闭」八档的落点：**两端共用这一份**，因此档位与分钟 / 集数的对应关系 必须在这里钉住——早先电视与手机各写一遍字面量，改一端就会悄悄分叉。 | class SleepTimerChoiceTest |
| `SlideCacheTest.kt` | 双向滑动的三条规则必须能各自单独验证，否则接进推荐页之后没法判断 「回看慢」到底是窗口没覆盖，还是被 fling 降级了。 | class SlideCacheTest |
| `StableDeviceIdsTest.kt` | 设备标识派生的协议测试：钉住「同一个系统标识符永远派生出同一个值」。 | class StableDeviceIdsTest |
| `VideoFitTest.kt` | 画面模式的两端共用判定（VideoFrameMode.toFit）。 | class VideoFitTest |
| `ViewingToolsTest.kt` | 自动化测试，覆盖 ViewingToolsTest 相关行为。 | class ViewingToolsTest |
| `VoiceQueryTest.kt` | 语音结果的清理规则。 | class VoiceQueryTest |
| `WatchProgressFactoryTest.kt` | 观看进度的生成规则（电视与手机共用，见 BackupProgress.of / BackupProgress.completed）。 | class WatchProgressFactoryTest |
| `WatchingTest.kt` | 自动化测试，覆盖 WatchingTest 相关行为。 | class WatchingTest |

#### `core/src/main/kotlin/com/hgtv/star/core/account/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `Account.kt` | 账号信息。 | class AccountProfile、class AccountFavorite、class AccountHistory、class AccountLike、class AccountReserve、class AccountData |
| `AccountImportServer.kt` | 一次性局域网页面：把 App 的 cookieStore.xml（或整段 Cookie）从手机送到电视。 | class AccountImportServer |
| `AccountSnapshot.kt` | 一份落盘的账号数据（AccountSnapshot 的产物）与它的写入时刻。 | class AccountCacheEntry、object AccountSnapshot |
| `DeviceRegister.kt` | 设备注册（POST /service/2/device_register/）。 | object DeviceRegister |
| `OfficialDeviceIds.kt` | 官方 App 默认设备标识生成规则。 | object OfficialDeviceIds |
| `PassportSigner.kt` | Passport（账号体系）的请求签名，**只产 branch 2**。 | class PassportSigner |
| `PassportTables.kt` | F13 每轮的 16 个消息字下标：5 组 × 64 轮。 | val PASSPORT_ORDERS、val PASSPORT_MD5_CONSTANTS |
| `PhoneLogin.kt` | 手机号登录协议实现，负责发码、登录、上行短信验证、Cookie 和设备注册。 | class PhoneLogin、fun e164Phone |
| `PhoneLoginServer.kt` | 上行短信那一步在**这一页自己**的轮询预算：3 秒一次、五次（≈15 秒）。 | val UPSMS_POLLS、val UPSMS_POLL_MS、val LOGIN_FOOTER、class PhoneLoginServer |
| `PhonePage.kt` | 手机端一次性页面的**共用外壳**：新拟态（neumorphism）+ 竖屏。 | object PhonePage |
| `PhoneRegion.kt` | 登录表单左侧的国家 / 地区区号候选。 | object PhoneRegion |
| `QrLogin.kt` | 扫码登录（Passport 的 Web 出码 + 长轮询）。 | class QrLogin |
| `StableDeviceIds.kt` | 由**系统标识符**派生的设备标识：openudid / cdid / clientudid。 | object StableDeviceIds |

#### `core/src/main/kotlin/com/hgtv/star/core/catalog/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `BackToTopPolicy.kt` | 「回到顶部」浮层的显隐规则（Library.backToTop 四档）。 | object BackToTopPolicy |
| `CatalogFilter.kt` | 「用户手动隐藏的剧」不该出现在哪些地方。 | object CatalogFilter |
| `CatalogPageCache.kt` | 分类 / 榜单首屏的共享持久缓存；按实际筛选条件复用，最多保留 24 份、每份 100 条。 | object CatalogPageCache |
| `CatalogPaging.kt` | 榜单、分类与搜索共用的分页边界、去重、空页结束、名次合并和预取规则。 | class CatalogPagingResult、object CatalogPaging |
| `CatalogSnapshot.kt` | First-page metadata only; signed playback URLs and account data never go to disk. 首页有四个「热播…」剧架（对应四个热播榜），因此这份快照按**榜单**分别存一份， 键是 RankBoard.storedValue（见 Library.cacheHome / cachedHo… | object CatalogSnapshot |
| `CategoryCatalog.kt` | 分类页里的一个小类。 | class CategoryTag、class CategoryGroup、object CategoryCatalog |
| `CategoryFilters.kt` | 分类页的一枚筛选项：id 是接口的 selector_item_id，label 是 show_name。 | class CategoryFilterOption、class CategoryFilterRow、class CategoryGenreOption、object CategoryGenres、class CategoryPanel、class CategorySelection |
| `CategoryPanelCache.kt` | 分类页筛选面板缓存，按大类分别保存。 | object CategoryPanelCache |
| `ContentAuthorRules.kt` | 目录、分类、榜单、首页布局与筛选。文件 ContentAuthorRules 主要包含 fun ContentRepository.heatValueImpl、fun ContentRepository.sameAuthorImpl、fun ContentRepository.authorUnknownOrSameImpl。 | fun ContentRepository.heatValueImpl、fun ContentRepository.sameAuthorImpl、fun ContentRepository.authorUnknownOrSameImpl |
| `ContentType.kt` | 片库里的内容类型。 | class ContentType |
| `ContentValues.kt` | 目录、分类、榜单、首页布局与筛选；电视统一设备参数档案由底层 profile 提供。 | fun ContentRepository.writeValuesImpl、fun ContentRepository.appValuesImpl、fun ContentRepository.danmakuValuesImpl、fun ContentRepository.searchValuesImpl、fun ContentRepository.searchRequestImpl、fun ContentRepository.accountAppValuesImpl、fun ContentRepository.deviceValuesImpl |
| `FeedCursor.kt` | 推荐流的**续流游标**：session_id + next_offset，按体裁分开存。 | object FeedCursor |
| `HomeLayout.kt` | 首页的一栏。 | class HomeColumn、object HomeLayout |
| `OptionalFilter.kt` | 某一层「锦上添花」的推荐筛选项（受众 / filter_ids / 体裁）在服务端回空页时的降级状态。 | class OptionalFilter |
| `RankBoard.kt` | 四个榜单。 | class RankBoard |
| `SeriesSeasons.kt` | 剧名里的季号解析。 | object SeriesSeasons |

#### `core/src/main/kotlin/com/hgtv/star/core/common/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ThirdPartyNotice.kt` | 两条「知情同意」的正文与标题，**两端共用一份**。 | object ThirdPartyNotice |
| `WorkLanes.kt` | 全进程的线程预算（纯 JVM 部分）。 | object WorkLanes |

#### `core/src/main/kotlin/com/hgtv/star/core/content/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ContentAccountFacade.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 ContentAccountFacade 主要包含 fun ContentRepository.accountProfile、fun ContentRepository.accountFavorites、fun ContentRepository.accountHistory、fun ContentRepository.accountLikes。 | fun ContentRepository.accountProfile、fun ContentRepository.accountFavorites、fun ContentRepository.accountHistory、fun ContentRepository.accountLikes、fun ContentRepository.accountReserves、fun ContentRepository.accountReserve |
| `ContentAuthors.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 ContentAuthors 主要包含 fun ContentRepository.authorOfImpl、fun ContentRepository.authorsOfImpl。 | fun ContentRepository.authorOfImpl、fun ContentRepository.authorsOfImpl |
| `ContentCatalogFacade.kt` | 榜单与分类的业务门面：分页、首页并发和筛选面板缓存都在这里决定。 | fun ContentRepository.rankRaw、fun ContentRepository.rank、fun ContentRepository.homeFeed、fun ContentRepository.category、fun ContentRepository.categoryFeed |
| `ContentDanmakuFacade.kt` | 取一集的弹幕，也就是「原 App」播放时看到的同一份数据。 | fun ContentRepository.danmaku、fun ContentRepository.danmakuRawWindow |
| `ContentDetailFacade.kt` | 剧集详情（简介 + 分集列表）：走 App 播放侧的详情接口。 | fun ContentRepository.detail、fun ContentRepository.related |
| `ContentDeviceFacade.kt` | @param profile 这一次注册用的设备档案；默认 ContentRepository.registerProfile。 | fun ContentRepository.registerDevice、fun ContentRepository.deviceCookiesSnapshot、fun ContentRepository.primaryCookiesSnapshot、fun ContentRepository.restoreDeviceCookies、fun ContentRepository.refreshTncAsync |
| `ContentDeviceState.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 ContentDeviceState 主要包含 fun ContentRepository.clearDeviceIdentityImpl、fun ContentRepository.noteDeviceRejectedImpl、fun ContentRepository.deviceCookiesSnapshotImpl、fun ContentRepository.primaryCookiesSnapshotImpl。 | fun ContentRepository.clearDeviceIdentityImpl、fun ContentRepository.noteDeviceRejectedImpl、fun ContentRepository.deviceCookiesSnapshotImpl、fun ContentRepository.primaryCookiesSnapshotImpl、fun ContentRepository.restoreDeviceCookiesImpl |
| `ContentDeviceStateFacade.kt` | 设备身份与播放档位门面；每集重置 1 档，空响应时兼容升 5。 | fun ContentRepository.clearDeviceIdentity、fun ContentRepository.noteDeviceRejected、fun ContentRepository.beginEpisode、fun ContentRepository.escalateDeviceLevel |
| `ContentPlaybackFacade.kt` | 用一次官方批量接口预取一组播放模型。 | fun ContentRepository.prefetchModels |
| `ContentPrimaryIdentity.kt` | 当前唯一的主身份快照。 | class ContentPrimaryIdentity、fun ContentRepository.primaryIdentity、fun ContentRepository.recommendationValues |
| `ContentRelated.kt` | 取「相关作品」那一栏的原始条目。 | fun ContentRepository.relatedRows |
| `ContentRelationFacade.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 ContentRelationFacade 主要包含 fun ContentRepository.heatValue、fun ContentRepository.sameAuthor、fun ContentRepository.authorUnknownOrSame。 | fun ContentRepository.heatValue、fun ContentRepository.sameAuthor、fun ContentRepository.authorUnknownOrSame |
| `ContentRepository.kt` | 内容接口总仓库，编排账号、目录、详情、搜索、推荐、播放地址、缓存与设备形态；播放模型默认 1 档，空响应兼容升 5。 | fun defaultHttpClient、val CATALOG_FIRST_PAGE_SIZE、class ContentRepository、tvDeviceProfile、effectiveDeviceProfile、DIRECT_DEVICE_LEVEL |
| `ContentSearchFacade.kt` | 搜索提示词（联想词）：App 搜索框用的那个接口。 | fun ContentRepository.suggestions、fun ContentRepository.webSuggestions、fun ContentRepository.hotSearches |
| `ContentSearchResultsFacade.kt` | 只搜一种内容类型：联调探针仍可显式指定 tab_type。 | fun ContentRepository.search、fun ContentRepository.webSearch |
| `ContentSigning.kt` | 同一套业务参数与设备参数，只换接口域名后重签。 | fun ContentRepository.resignForHost、fun ContentRepository.alternateApiHosts、fun ContentRepository.urlWithHost |
| `ContentStreamFacade.kt` | 取一集的播放地址，并区分主动点集的直连参数与后台兼容路径。 | fun ContentRepository.stream、fun ContentRepository.forgetStream、fun ContentRepository.forgetStreamAllQualities、fun ContentRepository.rotateDeviceForRetry |
| `ContentTextFacade.kt` | 出品方：把接口给的 copyright 归一成能直接显示的名字。 | fun ContentRepository.publisher、fun ContentRepository.tagsOf |

#### `core/src/main/kotlin/com/hgtv/star/core/crypto/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AesV3.kt` | 加解密、签名与字节编解码。文件 AesV3 主要包含 class AesV3。 | class AesV3 |
| `ArgusLadon.kt` | X-Argus / X-Ladon 容器。 | object ArgusLadon |
| `Bytes.kt` | 加解密、签名与字节编解码。文件 Bytes 主要包含 fun Byte.u、fun hex、fun ByteArray.hex、val BASE64。 | fun Byte.u、fun hex、fun ByteArray.hex、val BASE64、fun decode64、fun ByteArray.base64 |
| `MediaCrypto.kt` | 加解密、签名与字节编解码。文件 MediaCrypto 主要包含 object MediaCrypto。 | object MediaCrypto |
| `MedusaAlgorithm.kt` | medusa 三个核心族的纯算法实现（等价于官方 metasec 的 core 段）。 | class Mix32Profile、class Word32Profile、class Table64Profile、val medusaMix32Profiles、val medusaWord32Profiles、val medusaTable64Profiles |
| `MedusaAlgorithmTables.kt` | 由 x_medusa.go 的常量表机械生成，勿手改。 | val medusaMix32Initial、val medusaMix32Constants、val medusaMix32Padding、val medusaWord32Initial、val medusaWord32Constants、val medusaWord32Padding |
| `MedusaCore.kt` | medusaTail 用到的检查位，来自 SDKState。 | class DigestChecks、fun medusaAP、fun medusaCore、fun medusaTail |
| `MedusaEnvelope.kt` | 加解密、签名与字节编解码。文件 MedusaEnvelope 主要包含 fun rotateLeft8、fun medusaExpand、fun medusaAddKey、fun medusaShift。 | fun rotateLeft8、fun medusaExpand、fun medusaAddKey、fun medusaShift、fun medusaSubShift、fun medusaXtime |
| `MedusaModel.kt` | 加解密、签名与字节编解码。文件 MedusaModel 主要包含 class MedusaIdentity、class MedusaDevice、class MedusaConfigMaps、class MedusaSdk。 | class MedusaIdentity、class MedusaDevice、class MedusaConfigMaps、class MedusaSdk、class MedusaProcess、class MedusaThread |
| `MedusaPb.kt` | Go medusaInteger：把各种整数统一成 Long。 | fun medusaInteger、fun medusaVarint、fun medusaPB |
| `MedusaSchema.kt` | 从 Go sign/x-header.go 的 medusaSchema 机械提取，请勿手改。 | class MedusaDescriptor、val medusaSchema |
| `MedusaState.kt` | 加解密、签名与字节编解码。文件 MedusaState 主要包含 val MEDUSA_ROOT_MAGIC、val MEDUSA_DEFAULT_KEY、val medusaFLTable、fun readLe32。 | val MEDUSA_ROOT_MAGIC、val MEDUSA_DEFAULT_KEY、val medusaFLTable、fun readLe32、fun medusaCRC64、fun medusaQuery |
| `MedusaTables.kt` | 从 Go sign/x-header.go 机械提取的 Medusa 常量表，请勿手改。 | val medusaSboxes、val medusaRcon、val medusaPerms、val medusaRow3、val medusaSelect、val medusaPatch |
| `Signer.kt` | 加解密、签名与字节编解码。文件 Signer 主要包含 class Signer。 | class Signer |

#### `core/src/main/kotlin/com/hgtv/star/core/identity/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ContentDeviceLevel.kt` | 设备身份、设备等级与版本档案。文件 ContentDeviceLevel 主要包含 fun ContentRepository.beginEpisodeImpl、fun ContentRepository.escalateDeviceLevelImpl。 | fun ContentRepository.beginEpisodeImpl、fun ContentRepository.escalateDeviceLevelImpl |
| `ContentIdentity.kt` | 设备身份、设备等级与版本档案。文件 ContentIdentity 主要包含 fun ContentRepository.primaryIdentityImpl、fun ContentRepository.primaryProfileImpl、fun ContentRepository.primaryCookieHeaderImpl、fun Signer.Request.withSessionImpl。 | fun ContentRepository.primaryIdentityImpl、fun ContentRepository.primaryProfileImpl、fun ContentRepository.primaryCookieHeaderImpl、fun Signer.Request.withSessionImpl、fun Signer.Request.withAccountSessionImpl |
| `ContentProfiles.kt` | 设备身份、设备等级与版本档案；电视 profile 覆盖本机真实字段并保留身份字段。 | fun ContentRepository.deviceProfileImpl、fun ContentRepository.accountDeviceProfileImpl、fun ContentRepository.relatedHostsImpl、fun ContentRepository.commentHostsImpl |
| `VendorConstants.kt` | 设备身份、设备等级与版本档案；提供电视统一手机兼容 profile。 | object VendorConstants、fun VendorConstants.tvProfile |

#### `core/src/main/kotlin/com/hgtv/star/core/label/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `CardMenu.kt` | 剧卡菜单里的一项：文案 + 语义动作。 | class CardMenuItem、class CardAction、object CardMenu |
| `EpisodeLabel.kt` | 卡片 / 简介页上的「集数」文案。 | object EpisodeLabel、object EpisodePaging |
| `HeatLabel.kt` | 卡片上那个「热度」数字怎么显示。 | object HeatLabel |
| `LabelStyle.kt` | 卡片左上角那个「系统标签」的配色。 | object LabelStyle |
| `PosterCardContent.kt` | 一张海报卡要显示的全部文案与角标状态。 | class PosterCardContent |

#### `core/src/main/kotlin/com/hgtv/star/core/library/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `HistoryReconcile.kt` | 本机进度与账号历史的**对账**（电视端与手机端共用一份，纯计算、可单测）。 | object HistoryReconcile |
| `LatestWriteQueue.kt` | Serial persistence with one pending value per key; serialization belongs inside write. | class LatestWriteQueue |
| `LibraryBackup.kt` | 本机记录备份的编码、解码、范围校验与局域网同步数据校验；首页栏位 key 和设置默认值也在这里。 | val HOME_KEYS、val BACK_TO_TOP_ALWAYS、val BACK_TO_TOP_ROWS5、val BACK_TO_TOP_ROWS8、val BACK_TO_TOP_NEVER、val BACK_TO_TOP_DEFAULT、object LibraryBackup |
| `LibraryIndex.kt` | Main-thread-owned library. JSON is parsed once, and getters never parse or sort. | class LibraryIndex、object LibraryDisk |
| `LibraryTransferServer.kt` | Short-lived LAN transfer. Uploaded records are validated here, but only the TV may apply them. | class LibraryTransferServer |
| `LruPut.kt` | 往定长 LRU 里写一条：写完把最久没用过的那条挤出去。 | fun LinkedHashMap |
| `ViewingTools.kt` | 一条搜索历史：只有关键词。 | class RecentSearch、object SearchHistory、object EpisodeTarget |
| `WatchSync.kt` | 本机观看进度（BackupProgress）与账号侧观看历史（AccountHistory）的对账规则。 | object WatchSync |
| `Watching.kt` | 本机书库、历史、进度、备份与写队列。文件 Watching 主要包含 object PlaybackQuality、class RecoveryBudget、class ResumeTarget、object ResumePlayback。 | object PlaybackQuality、class RecoveryBudget、class ResumeTarget、object ResumePlayback、class FavoriteUpdate、object EpisodePageSize |

#### `core/src/main/kotlin/com/hgtv/star/core/media/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ArtworkUrl.kt` | 封面地址的改写：**换到免签镜像域名、按卡片尺寸取图、用 webp**。 | object ArtworkUrl |
| `Danmaku.kt` | 一条弹幕。 | class Danmaku、fun parseDanmakuRows、fun rawDanmakuRows、class DanmakuMode、class DanmakuSpeed、class DanmakuRepeat |
| `DanmakuEmoji.kt` | 弹幕文本里的表情标签。 | object DanmakuEmoji、class DanmakuPart |
| `PictureWatchdog.kt` | 「有声音、没画面」与「一直卡在缓冲」这两种**不会抛异常**的故障的判据。 | class PictureWatchdog |
| `SlideCache.kt` | 缓存分级（文档第 3 节）。 | class CacheLevel、class SlideCacheManager |

#### `core/src/main/kotlin/com/hgtv/star/core/network/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ContentTransport.kt` | DNS、TLS、HTTP、网络计时与请求作用域；旧超时常量保留为 `AppConfig.Network` 的兼容别名。 | fun JSONArray.objects、fun JSONObject.str、fun JSONObject.flag、fun cleanUrl、fun defaultContentHttpClient、class CatalogHttpException |
| `HgtvDns.kt` | 带缓存的 DNS，替换 OkHttp 默认的「每次新建连接都问一遍系统」。 | object HgtvDns |
| `HgtvTls.kt` | 全工程共用的 TLS 协商参数。 | object HgtvTls、class PrepareTlsSocketFactory |
| `NetTiming.kt` | 一次请求的连接耗时分解（调试包专用，见 NetLog.timing）。 | class NetTiming |
| `OkHttp3Compat.kt` | OkHttp 3.12 is a Java API. These aliases keep the Kotlin call sites written for OkHttp 4 readable while the application needs to support Android 4.4. | val Request.url、val Request.method、val Request.headers、val Request.body、val OkHttpClient.connectionPool、val OkHttpClient.dispatcher |
| `PublicDns.kt` | 公共 DNS 兜底解析：自己发裸 UDP 查询，不经过系统解析器。 | object PublicDns |
| `RequestScope.kt` | 请求生命周期与网络失败统一出口，支持取消、诊断、失败记录和启动阶段硬预算。 | fun isInterruption、object NetLog、class NetLogLevel、class RequestScope、class StageDeadline、class StageTimeoutException |
| `TlsStatus.kt` | 调试面板读取的 TLS 现场。 | object TlsStatus |
| `TncConfigStore.kt` | TNC 响应：正文以及下一轮条件请求要带的摘要 / ETag。 | class TncResponse、class TncSnapshot、object TncConfigStore |

#### `core/src/main/kotlin/com/hgtv/star/core/playback/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `BitrateProbe.kt` | 一次性探针：抓几部短剧的 1080P 播放地址，从 MP4 头部量它们的真实码率。 | fun u32、fun u64、fun fourcc、fun boxes、fun find、class Track |
| `CodecProbe.kt` | 一次性探针：把播放接口返回的 video_list 原样摊开，看每个清晰度到底提供哪些编码。 | fun reflect、fun main |
| `KeyMap.kt` | 播放界面里可自定义的六个键。 | class PlayerKey、class PlayerKeyAction、class KeyMap |
| `PlaybackAttemptLedger.kt` | 正式播放页**按集计**的全部重试额度与账本：一处持有、三个入口重置。 | class PlaybackAttemptLedger |
| `PlaybackCache.kt` | 播放地址、索引和首块缓存，负责过期判定与本地复用。 | object PlaybackCache |
| `PlaybackComfort.kt` | 视频解码方式。 | class VideoDecoderMode、class VideoFrameMode、class VideoFit、fun VideoFrameMode.toFit、object SeekStep、class SleepTimer |
| `PlaybackDegradation.kt` | 播放重试 / 降级的**全部决策**都收敛在这个文件里，纯逻辑、不碰 Android、可直接单测。 | class MediaKind、class DegradationKind、class PlaybackRetryPlan、class GiveUpReason、interface RetryDecision、class FailureAttribution |
| `PlaybackFailurePolicy.kt` | 一次播放失败的**事实**：平台层负责从异常与现场里读出这些布尔量，决策层只认它们。 | class FailureFacts、interface FailureAction、object PlaybackFailurePolicy、object FailurePresentation、class FailureCapability |
| `PlaybackInfoLine.kt` | 播放界面左上角那几项信息的**文案**：电视与手机共用这一份。 | object PlaybackInfoLine |
| `PlaybackMachine.kt` | 驱动 PlaybackState 的事件。 | interface PlaybackEvent、object PlaybackMachine |
| `PlaybackSpeed.kt` | 播放地址、缓存、探测、降级与预取。文件 PlaybackSpeed 主要包含 object PlaybackSpeed。 | object PlaybackSpeed |
| `PlaybackStage.kt` | 播放失败的**阶段常量与标题表**：电视与手机共用这一份。 | object PlaybackStage |
| `PlaybackState.kt` | 一次播放所处的阶段。 | class PlaybackPhase、class PlaybackState、class MediaTrack、class PlaybackFailure |
| `PrefetchPolicy.kt` | 播放地址、缓存、探测、降级与预取。文件 PrefetchPolicy 主要包含 object PrefetchPolicy。 | object PrefetchPolicy |
| `PreparedSlot.kt` | One cancellable speculative result. Claim transfers ownership; stale completions close themselves. ttl 默认 2 分钟，**比 PlaybackCache.PREPARED_FRESH_MS（10 分钟）更严格**，这是有意的： 这个槽位装的是 Remot… | class PreparedSlot |
| `Probe.kt` | 弹幕里的表情标签：名字 / 【名字】 / ［名字］，与 DanmakuEmoji 同一条规则。 | val tag、fun main |
| `RemoteVideo.kt` | 播放地址已经失效（HTTP 403/404）。 | class StreamExpiredException、class VideoChangedException、class RemoteVideo |
| `SeekStepPump.kt` | 快进 / 快退的**调度外框**：连按计数、合并窗、待落地目标与最终那一次 seek。 | class SeekStepPump |
| `VideoProbe.kt` | 一次性探针：对每条候选路径，强制生成 branch 0 / 1 / 2 三种签名各打一次， 用来定位「哪一种签名变体被服务端接受」。 | fun main |

#### `core/src/main/kotlin/com/hgtv/star/core/recommend/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `RecommendBehavior.kt` | 推荐流客户端共用的曝光判定与上报入口。 | object RecommendBehavior、class RecommendStatData、object RecommendBehaviorReporter |
| `RecommendCache.kt` | 推荐页那一列卡片的落盘快照：**先画出来，再刷新**；支持按命名空间保存快照和播放位置、迁移旧版位置，并在推荐范围变化后排空旧写任务。 | object RecommendCache、fun migratePosition、fun clearAfterPendingWrites |
| `RecommendDataSource.kt` | 推荐页的四个入口；取流契约携带横屏请求模式。 | class RecommendTab、interface RecommendFeedSource、interface RecommendReportSink、interface RecommendDataSource |
| `RecommendFlow.kt` | 推荐流的**客户端那一半治理**：剧级去重窗口 + 本机兴趣排序 + 播放列表打散。 | object RecommendFlow、class RecommendMarks、class RecommendTaste、class RecommendArranger |
| `RecommendStatConfigStore.kt` | 推荐曝光上报的远程配置。 | object RecommendStatConfigStore |

#### `core/src/main/kotlin/com/hgtv/star/core/search/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneSearchServer.kt` | A one-use, short-lived LAN form. The page itself carries no cookies, no external resources and nothing stored server-side; it only receives what the operator types on the phone an… | class PhoneSearchServer |
| `SearchDiag.kt` | 上一次搜索到底发生了什么。 | object SearchDiag |
| `SearchKeyboard.kt` | 电视搜索页用哪一套键盘。 | class SearchKeyboard |
| `SearchSource.kt` | 搜索页的数据源（设置里可选）。 | class SearchSource |
| `VoiceQuery.kt` | 语音识别结果的归一化（电视端与手机端共用一份，纯文本规则，放在 core 里便于单测）。 | object VoiceQuery |

#### `core/src/main/kotlin/com/hgtv/star/core/session/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `CookieRecord.kt` | 一条 Set-Cookie 解析后的结果。 | class CookieRecord |
| `SessionClients.kt` | 会话模块自己的 client。 | object SessionClients |
| `SessionHeaders.kt` | 心跳 / 会话请求要带的设备头。 | object SessionHeaders |
| `SessionInterceptor.kt` | 对齐原版 px7/a.java（Retrofit 拦截器）+ TTTokenManager.addRequestHeader / processResponseHeader。 | class SessionInterceptor |
| `SessionKeeper.kt` | 心跳保活。 | class SessionKeeper |
| `SessionState.kt` | 会话状态的最小接口。 | interface SessionState、object SessionBridge |

#### `core/src/test/kotlin/com/hgtv/star/core/playback/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PlaybackMachineTest.kt` | 播放状态机的迁移规则。 | class PlaybackMachineTest |

#### `core/src/main/kotlin/com/hgtv/star/core/content/account/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AccountApi.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 AccountApi 主要包含 class ContentAccountTransport、object ContentAccountRequests。 | class ContentAccountTransport、object ContentAccountRequests |
| `AccountService.kt` | 账号资料。 | — |

#### `core/src/main/kotlin/com/hgtv/star/core/content/catalog/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `CatalogParser.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 CatalogParser 主要包含 object ContentCatalogParser。 | object ContentCatalogParser |
| `CatalogService.kt` | 榜单页：四个榜单（RankBoard）共用这一套取数，只有分类与排序不同。 | — |

#### `core/src/main/kotlin/com/hgtv/star/core/content/danmaku/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DanmakuService.kt` | 热弹幕分页、请求签名、本机设备档案与响应解析。 | class ContentRepositoryDanmakuPage |

#### `core/src/main/kotlin/com/hgtv/star/core/content/detail/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DetailCache.kt` | 剧集详情的落盘缓存。 | object DetailCache |
| `DetailParser.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 DetailParser 主要包含 class RelatedEntry、object ContentDetailParser。 | class RelatedEntry、object ContentDetailParser |
| `DetailService.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 DetailService 主要包含 DetailService。 | — |

#### `core/src/main/kotlin/com/hgtv/star/core/content/model/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ContentModels.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 ContentModels 主要包含 class Series、class PlaybackProfile、class Detail、class RelatedWorks。 | class Series、class PlaybackProfile、class Detail、class RelatedWorks、class SearchSuggestion、class RankPosition |

#### `core/src/main/kotlin/com/hgtv/star/core/content/network/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `NetworkBootstrapService.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 NetworkBootstrapService 主要包含 NetworkBootstrapService。 | — |

#### `core/src/main/kotlin/com/hgtv/star/core/content/playback/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PlaybackModelService.kt` | 取一集的播放模型；所有请求优先 1 档，用户主动点集走单集直连，空响应才兼容升到 5 档。 | — |
| `PlaybackParser.kt` | 播放模型、单集直连请求体、内联 / fallback 清晰度解析；同时兼容对象与数组形态的 `video_list`。 | object ContentPlaybackParser |
| `StreamCacheService.kt` | 播放地址内存 / 落盘缓存与取流；先解析模型内联 `video_list`，缺失时才请求 `fallback_api`。 | class ContentRepositoryStreamCacheEntry |

#### `core/src/main/kotlin/com/hgtv/star/core/content/recommend/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `RecommendService.kt` | 官方推荐流（含 `is_horizontal_screen` 请求参数）、曝光上报参数与首帧后的统计配置刷新。 | — |

#### `core/src/main/kotlin/com/hgtv/star/core/content/search/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `SearchParser.kt` | 内容仓库、模型、解析、请求编排与缓存。文件 SearchParser 主要包含 object ContentSearchParser。 | object ContentSearchParser |
| `SearchService.kt` | 官网的联想词：GET /api/search/suggestion? | — |

### feature-phone

#### `feature-phone/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `build.gradle.kts` | Gradle Kotlin DSL 构建或设置脚本。 | gradle |

#### `feature-phone/src/main/kotlin/com/hgtv/star/phone/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneActivity.kt` | 手机端 Activity 基类：普通页面由系统在屏幕尺寸或方向变化时重建整页。 | class PhoneActivity |
| `PhoneConsent.kt` | 手机端的两条「知情同意」，与电视端**同一份文案、同一条语义**（见 :core 的 com.hgtv.star.core.ThirdPartyNotice）。 | object PhoneConsent |
| `PhoneFirstRunTip.kt` | 首次进入时的「先调一下显示」提示。 | object PhoneFirstRunTip |
| `PhoneMainActivity.kt` | 手机 / 平板端主界面；推荐首屏后预热播放器，分类页延后到推荐首帧或兜底超时再创建。 | class PhoneMainActivity、fun revealPhoneStartup、fun warmStartupPlayer |
| `PhoneNavigator.kt` | 手机端的路由落地：把 Route 翻译成一个 Intent，交给系统返回栈。 | class PhoneNavigator、fun Context.phoneNav |
| `PhoneServices.kt` | 手机端服务入口；正式播放页使用当前活动 ExoPlayer，推荐池通过 `newPlayer` 创建独立实例并由 `activate` 登记当前实例。 | object PhoneServices、object PhoneDecoder、fun player、fun newPlayer、fun activate、fun warmPlayer |
| `PhoneSession.kt` | 手机端「这一台机器现在在放什么」的进程级事实。 | object PhoneSession |

#### `feature-phone/src/main/kotlin/com/hgtv/star/phone/account/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneAccountActivity.kt` | 手机账号页 Activity，管理登录模式、账号渲染和登录结果收尾。 | class PhoneAccountActivity |
| `PhoneAccountLogin.kt` | 手机账号登录、同步与设备身份。文件 PhoneAccountLogin 主要包含 fun PhoneAccountActivity.renderQr、fun PhoneAccountActivity.startQr、fun PhoneAccountActivity.stopQr、fun PhoneAccountActivity.renderPhoneLogin。 | fun PhoneAccountActivity.renderQr、fun PhoneAccountActivity.startQr、fun PhoneAccountActivity.stopQr、fun PhoneAccountActivity.renderPhoneLogin、fun PhoneAccountActivity.doLogin、fun PhoneAccountActivity.renderUpsms |
| `PhoneAccountSync.kt` | Phone 侧的账号兼容壳。 | object PhoneAccountSync |
| `PhoneIdentity.kt` | 把**本机身份与会话**灌进进程级仓库——电视端 TvMainActivity.applyAccount() 的手机版。 | object PhoneIdentity |

#### `feature-phone/src/main/kotlin/com/hgtv/star/phone/debug/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneDebugReport.kt` | 手机调试面板的公共现场。 | object PhoneDebugReport |
| `PhoneNetDebug.kt` | 手机端网络日志出口：与 TV 端同一份过滤规则，只把有结论的行放进调试面板。 | object PhoneNetDebug |
| `PhoneRecommendReporting.kt` | 手机端推荐来源行为的统一出口。 | fun reportRecommendBehavior |

#### `feature-phone/src/main/kotlin/com/hgtv/star/phone/detail/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneDetailActivity.kt` | 详情页：**与 TV 端的详情页一一对应**，按手机形态重排。 | class PhoneDetailActivity |
| `PhoneDetailSections.kt` | 手机详情页与竖屏视频版面。文件 PhoneDetailSections 主要包含 fun PhoneDetailActivity.prefetchRelated、fun PhoneDetailActivity.renderRelated、fun PhoneDetailActivity.prefetchAuthorWorks、fun PhoneDetailActivity.renderAuthorWorks。 | fun PhoneDetailActivity.prefetchRelated、fun PhoneDetailActivity.renderRelated、fun PhoneDetailActivity.prefetchAuthorWorks、fun PhoneDetailActivity.renderAuthorWorks、fun PhoneDetailActivity.sectionHead、fun PhoneDetailActivity.cardRow |
| `PhoneVideoPage.kt` | 手机竖屏视频版面，供信息流与正式播放页共用视频层、弹幕黑边遮罩、信息区和控制层。 | class PhoneVideoPage |
| `PhoneVideoPageState.kt` | 手机详情页与竖屏视频版面。文件 PhoneVideoPageState 主要包含 fun PhoneVideoPage.setChromeVisible、fun PhoneVideoPage.setTopScrimVisible、fun PhoneVideoPage.isChromeVisible、fun PhoneVideoPage.setUnderlayMode。 | fun PhoneVideoPage.setChromeVisible、fun PhoneVideoPage.setTopScrimVisible、fun PhoneVideoPage.isChromeVisible、fun PhoneVideoPage.setUnderlayMode、fun PhoneVideoPage.showCenterIcon、fun PhoneVideoPage.hideCenterIcon、fun PhoneVideoPage.setResizeMode、fun PhoneVideoPage.setVideoSurfaceVisible |

#### `feature-phone/src/main/kotlin/com/hgtv/star/phone/feed/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneFeedActions.kt` | 手机信息流页面、适配器、手势与预取。文件 PhoneFeedActions 主要包含 fun PhoneFeedView.isPlayingNowImpl、fun PhoneFeedView.notifyPlayStateChangedImpl、fun PhoneFeedView.scrollToTopImpl、fun PhoneFeedView.stopOrphanPlaybackImpl。 | fun PhoneFeedView.isPlayingNowImpl、fun PhoneFeedView.notifyPlayStateChangedImpl、fun PhoneFeedView.scrollToTopImpl、fun PhoneFeedView.stopOrphanPlaybackImpl |
| `PhoneFeedAdapter.kt` | 手机信息流页面、适配器、手势与预取。文件 PhoneFeedAdapter 主要包含 class PhoneFeedAdapter。 | class PhoneFeedAdapter |
| `PhoneFeedAdoption.kt` | 手机信息流页面、适配器、手势与预取。文件 PhoneFeedAdoption 主要包含 fun PhoneFeedView.adoptPlayingImpl。 | fun PhoneFeedView.adoptPlayingImpl |
| `PhoneFeedChrome.kt` | 手机信息流页面、适配器、手势与预取。文件 PhoneFeedChrome 主要包含 fun PhoneFeedView.currentChromePageImpl、fun PhoneFeedView.scheduleChromeHideImpl、fun PhoneFeedView.chromeTimeoutMsImpl、fun PhoneFeedView.revealChromeImpl。 | fun PhoneFeedView.currentChromePageImpl、fun PhoneFeedView.scheduleChromeHideImpl、fun PhoneFeedView.chromeTimeoutMsImpl、fun PhoneFeedView.revealChromeImpl |
| `PhoneFeedDanmaku.kt` | 取（或建）某一屏的弹幕加载器；首帧前只保留最后一次加载请求，首帧后再拉取；同步推荐顶栏占用的顶部避让并让首行紧贴导航。 | fun PhoneFeedView.danmakuLoaderOfImpl、fun PhoneFeedView.applyDanmakuSettingsImpl、fun PhoneFeedView.applyDanmakuTopInsetImpl、fun PhoneFeedView.loadDanmakuImpl |
| `PhoneFeedHide.kt` | 手机信息流页面、适配器、手势与预取。文件 PhoneFeedHide 主要包含 fun PhoneFeedView.onHiddenImpl。 | fun PhoneFeedView.onHiddenImpl |
| `PhoneFeedHost.kt` | 手机信息流页面、适配器、手势与预取；只有当前体裁上屏后才触发取流。 | fun PhoneFeedView.setGenreImpl、fun PhoneFeedView.onShownImpl、fun PhoneFeedView.onIdentityReadyImpl |
| `PhoneFeedInitializer.kt` | 手机信息流页面初始化；先铺快照，不主动为隐藏体裁发请求。 | fun PhoneFeedView.initializeFeed |
| `PhoneFeedLifecycle.kt` | 手机信息流页面、适配器、手势与预取。文件 PhoneFeedLifecycle 主要包含 fun PhoneFeedView.onFeedDetachedImpl、fun PhoneFeedView.releaseImpl。 | fun PhoneFeedView.onFeedDetachedImpl、fun PhoneFeedView.releaseImpl |
| `PhoneFeedPageSupport.kt` | 手机信息流页面、适配器、手势与预取。文件 PhoneFeedPageSupport 主要包含 fun PhoneFeedView.pageAtImpl、fun PhoneFeedView.applyPageVideoSettingsImpl、fun PhoneFeedView.handleFeedSizeChanged、fun PhoneFeedView.clockImpl。 | fun PhoneFeedView.pageAtImpl、fun PhoneFeedView.applyPageVideoSettingsImpl、fun PhoneFeedView.handleFeedSizeChanged、fun PhoneFeedView.clockImpl |
| `PhoneFeedPager.kt` | 竖向信息流：**只有明显纵向的手势才滚**。 | class VerticalPagerList、class PageHolder |
| `PhoneFeedPlayback.kt` | 推荐换作入口；优先激活已预热的独立播放器，池未命中时才走详情与地址准备，并立即暂停旧活动实例。 | fun PhoneFeedView.playIndexImpl |
| `PhoneFeedPlayerPool.kt` | 有上限的推荐播放器池：独立播放器/MediaSource、相邻页预热、活动切换、按距离淘汰、低内存上限、隐藏页延迟回收与 `RemoteVideo` 所有权。 | class FeedPlayerSlot、fun PhoneFeedView.ensurePooledPlayerSlot、fun PhoneFeedView.primePooledPlayerImpl、fun PhoneFeedView.activatePreparedPooledPlayerImpl、fun PhoneFeedView.trimPooledPlayers |
| `PhoneFeedPrefetch.kt` | 片尾 30 秒内用单集直连准备下一集地址与首块。 | fun PhoneFeedView.maybePrefetchNextImpl |
| `PhoneFeedReopen.kt` | 手机信息流原地重开当前集；地址走单集直连并复用内联清晰度。 | fun PhoneFeedView.reopenAtCurrentPositionImpl |
| `PhoneFeedResume.kt` | 手机信息流页面、适配器、手势与预取。文件 PhoneFeedResume 主要包含 fun PhoneFeedView.resumeOnShownImpl。 | fun PhoneFeedView.resumeOnShownImpl |
| `PhoneFeedSettings.kt` | 信息流长按中间打开的播放设置面板。 | fun PhoneFeedView.openSettingsImpl |
| `PhoneFeedSurface.kt` | 手机信息流页面、适配器、手势与预取。文件 PhoneFeedSurface 主要包含 fun PhoneFeedView.keepLivePlaybackImpl、fun PhoneFeedView.bindFeedSurfaceForHandoffImpl、fun PhoneFeedView.rebindCurrentPageImpl、fun PhoneFeedView.setUnderlayModeImpl。 | fun PhoneFeedView.keepLivePlaybackImpl、fun PhoneFeedView.bindFeedSurfaceForHandoffImpl、fun PhoneFeedView.rebindCurrentPageImpl、fun PhoneFeedView.setUnderlayModeImpl |
| `PhoneFeedView.kt` | 手机信息流主视图，承载竖屏视频页、滑动切集、播放器池、播放交接、预取和生命周期；记录首帧后补流状态。 | class PhoneFeedView、feedPooledPlayers、feedPlayerListeners、feedFirstFrameFillQueued |

#### `feature-phone/src/main/kotlin/com/hgtv/star/phone/page/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneCategoryPage.kt` | 分类页：**完全按 TV 端的两层筛选结构**。 | class PhoneCategoryPage |
| `PhoneMinePage.kt` | 我的页：顶部账号入口 + 历史 / 收藏栏目 + 内容网格，右上角提供设置入口。 | class PhoneMinePage |
| `PhoneRankPage.kt` | 榜单页：四个榜单（推荐 / 漫剧 / AI剧 / 真人）+ 带名次的两列海报。 | class PhoneRankPage |
| `PhoneRecommendPage.kt` | 推荐页：四条件裁横向滑动，每页一条独立的竖屏视频流，并把顶栏实际高度同步给弹幕层。 | class PhoneRecommendPage |

#### `feature-phone/src/main/kotlin/com/hgtv/star/phone/player/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneFeedAdvance.kt` | 一集播完：落 completed 进度 → 进正式播放页播下一集（最后一集则换下一部）。 | fun PhoneFeedView.onEpisodeEnded、fun PhoneFeedView.primeHandoff |
| `PhoneFeedAttach.kt` | 池未命中时的起播路径；为目标页创建/复用独立播放器、挂源、发布会话并在宽高比布局后 prepare。 | fun PhoneFeedView.attachImpl |
| `PhoneFeedControls.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhoneFeedControls 主要包含 fun PhoneFeedView.togglePlay、fun PhoneFeedView.beginTempSpeed、fun PhoneFeedView.endTempSpeed。 | fun PhoneFeedView.togglePlay、fun PhoneFeedView.beginTempSpeed、fun PhoneFeedView.endTempSpeed |
| `PhoneFeedData.kt` | 冷启动先铺落盘快照；首次无快照只取一个可播放条目，首帧后补齐列表。 | fun PhoneFeedView.restoreSnapshot、fun PhoneFeedView.loadMore、fun PhoneFeedView.scheduleFeedFillAfterFirstFrame、fun PhoneFeedView.refreshAfterIdentity、fun PhoneFeedView.adoptableIndexOf |
| `PhoneFeedListener.kt` | 每台池播放器独立挂监听；按索引更新对应页面，READY / 首帧后触发补流、首帧追踪与池状态。 | fun PhoneFeedView.attachFeedListener、fun PhoneFeedView.detachFeedListener、fun PhoneFeedView.detachAllFeedListeners |
| `PhoneFeedMetrics.kt` | 与电视端同一套「快速划过 / 划走 / 看过」判定。 | fun PhoneFeedView.settleRecommendWatch、fun PhoneFeedView.reportRecommendImpression、fun PhoneFeedView.scheduleRecommendBatchModels |
| `PhoneFeedNavigation.kt` | 进播放页。 | fun PhoneFeedView.openPlayer、fun PhoneFeedView.hideChromeForHandoff、fun PhoneFeedView.launchPlayer、fun PhoneFeedView.liveEpisodeOf |
| `PhoneFeedPageBinding.kt` | 活动页绑定；保留上一页自己的播放器与 Surface，按新片源尺寸预置首帧比例。 | fun PhoneFeedView.attachPageImpl |
| `PhoneFeedPlaybackClock.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhoneFeedPlaybackClock 主要包含 fun PhoneFeedView.syncDanmakuClock、fun PhoneFeedView.syncDanmakuClockNow、fun PhoneFeedView.syncProgressBarsNow、fun PhoneFeedView.startProgressTicker。 | fun PhoneFeedView.syncDanmakuClock、fun PhoneFeedView.syncDanmakuClockNow、fun PhoneFeedView.syncProgressBarsNow、fun PhoneFeedView.startProgressTicker |
| `PhoneFeedPreload.kt` | 相邻页调度；完整预热直接创建独立播放器并 prepare，地址级预热仍进入 `PhonePreload`。 | class PhoneFeedPreloadState、fun PhoneFeedView.preloadGateOpen、fun PhoneFeedView.scheduleDwell、fun PhoneFeedView.schedulePreload、fun PhoneFeedView.applyPreload |
| `PhoneNextSeason.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhoneNextSeason 主要包含 class PhoneNextSeasonState、fun PhonePlayerActivity.next、fun PhonePlayerActivity.prepareNextSeasonIfNeeded、fun PhonePlayerActivity.clearNextSeasonLookup。 | class PhoneNextSeasonState、fun PhonePlayerActivity.next、fun PhonePlayerActivity.prepareNextSeasonIfNeeded、fun PhonePlayerActivity.clearNextSeasonLookup、fun PhonePlayerActivity.ensureNextSeason、fun PhonePlayerActivity.completeNextSeasonLookup |
| `PhonePlaybackProgress.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlaybackProgress 主要包含 fun PhonePlayerActivity.startTicker、fun PhonePlayerActivity.updateProgress、fun PhonePlayerActivity.syncProgressBarsNow、fun PhonePlayerActivity.saveProgress。 | fun PhonePlayerActivity.startTicker、fun PhonePlayerActivity.updateProgress、fun PhonePlayerActivity.syncProgressBarsNow、fun PhonePlayerActivity.saveProgress、fun PhonePlayerActivity.maybePrefetchNext、fun PhonePlayerActivity.enqueueNextIfReady |
| `PhonePlaybackRecovery.kt` | 手机端的播放失败自愈：**决策全部来自 :core，与电视端同一套**。 | val PhonePlayerActivity.playbackMaxQuality、fun PhonePlayerActivity.playbackAttribution、fun PhonePlayerActivity.handlePlayerFailure、fun PhonePlayerActivity.decideAndRun、fun PhonePlayerActivity.refetchAndReplay、fun PhonePlayerActivity.recoverQueuedCurrent |
| `PhonePlaybackSettings.kt` | 播放设置面板的各行：**推荐信息流与正式播放页共用这一份**。 | fun buildPlaybackSettingsRows |
| `PhonePlayerActivity.kt` | 手机正式播放页 Activity，持有播放器、横竖屏状态、控制层和页面生命周期。 | class PhonePlayerActivity |
| `PhonePlayerAdoption.kt` | adoptFromSession 的前置检查；返回 null = 可以接，否则返回不能接的原因（只用于日志）。 | fun PhonePlayerActivity.adoptReject、fun PhonePlayerActivity.adoptFromSession |
| `PhonePlayerAttach.kt` | 把已准备好的视频源挂到当前形态对应的 PlayerView，并安排首帧前的 prepare。 | fun PhonePlayerActivity.attach |
| `PhonePlayerBack.kt` | 返回键决策；true = 已消费，false = 交给 Activity 默认返回。 | fun PhonePlayerActivity.handlePlayerBack |
| `PhonePlayerChips.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerChips 主要包含 fun PhonePlayerActivity.syncChips、fun PhonePlayerActivity.cycleDanmaku、fun PhonePlayerActivity.cycleSpeed。 | fun PhonePlayerActivity.syncChips、fun PhonePlayerActivity.cycleDanmaku、fun PhonePlayerActivity.cycleSpeed |
| `PhonePlayerChrome.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerChrome 主要包含 fun PhonePlayerActivity.applyChrome、fun PhonePlayerActivity.setChromeVisible、fun PhonePlayerActivity.scheduleChromeHide、fun PhonePlayerActivity.chromeTimeoutMs。 | fun PhonePlayerActivity.applyChrome、fun PhonePlayerActivity.setChromeVisible、fun PhonePlayerActivity.scheduleChromeHide、fun PhonePlayerActivity.chromeTimeoutMs、fun PhonePlayerActivity.handleChromeTouchEvent |
| `PhonePlayerCommonUi.kt` | 竖横共用的中心提示、缓冲、下一季提示、播放信息行和底部面板。 | fun PhonePlayerActivity.buildPlayerCommonUi |
| `PhonePlayerConfiguration.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerConfiguration 主要包含 fun PhonePlayerActivity.handlePlayerConfigurationChanged。 | fun PhonePlayerActivity.handlePlayerConfigurationChanged |
| `PhonePlayerControls.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerControls 主要包含 fun PhonePlayerActivity.togglePlay、fun PhonePlayerActivity.syncBufferingUi、fun PhonePlayerActivity.setBufferingLabel、fun PhonePlayerActivity.muteBufferingUi。 | fun PhonePlayerActivity.togglePlay、fun PhonePlayerActivity.syncBufferingUi、fun PhonePlayerActivity.setBufferingLabel、fun PhonePlayerActivity.muteBufferingUi、fun PhonePlayerActivity.syncPlayGlyph、fun PhonePlayerActivity.showCenter |
| `PhonePlayerCreate.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerCreate 主要包含 fun PhonePlayerActivity.initializePlayerActivity、fun PhonePlayerActivity.canBorrowFeedSurface。 | fun PhonePlayerActivity.initializePlayerActivity、fun PhonePlayerActivity.canBorrowFeedSurface |
| `PhonePlayerDanmaku.kt` | 手机播放器弹幕层与加载门面；当前集弹幕延后到播放首帧，换集后只保留最后一次请求。 | fun PhonePlayerActivity.danmakuTarget、fun PhonePlayerActivity.applyDanmakuSettings、fun PhonePlayerActivity.loadDanmaku、fun PhonePlayerActivity.syncDanmakuClock、fun PhonePlayerActivity.syncDanmakuClockNow、fun PhonePlayerActivity.replayDanmaku |
| `PhonePlayerDebug.kt` | 调试模式那一块面板：打开就挂、关掉就摘。 | fun PhonePlayerActivity.syncDebugOverlayImpl、fun PhonePlayerActivity.debugLinesImpl |
| `PhonePlayerDestroy.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerDestroy 主要包含 fun PhonePlayerActivity.handlePlayerDestroy。 | fun PhonePlayerActivity.handlePlayerDestroy |
| `PhonePlayerEpisodeBinding.kt` | 把「第几集」这件事刷到所有界面上（竖屏信息栏 / 横屏标题 / 选集按钮）。 | fun PhonePlayerActivity.bindEpisodeUi |
| `PhonePlayerEpisodePanels.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerEpisodePanels 主要包含 object PhonePlayerEpisodePanels。 | object PhonePlayerEpisodePanels |
| `PhonePlayerEpisodeSwitch.kt` | 上下滑换集优先推进 ExoPlayer 已经挂好的下一项。 | fun PhonePlayerActivity.playSwipeTarget、fun PhonePlayerActivity.seekToQueuedNext |
| `PhonePlayerEpisodes.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerEpisodes 主要包含 fun PhonePlayerActivity.openEpisodes、fun PhonePlayerActivity.openPortraitEpisodes、fun PhonePlayerActivity.openLandscapeEpisodes。 | fun PhonePlayerActivity.openEpisodes、fun PhonePlayerActivity.openPortraitEpisodes、fun PhonePlayerActivity.openLandscapeEpisodes |
| `PhonePlayerExit.kt` | 退出播放页：不要系统转场动画。 | fun PhonePlayerActivity.preparePlayerFinish、fun PhonePlayerActivity.handleNewPlayerIntent |
| `PhonePlayerFullscreen.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerFullscreen 主要包含 fun PhonePlayerActivity.enterImmersive、fun PhonePlayerActivity.enterFullscreen、fun PhonePlayerActivity.requestOrientation、fun PhonePlayerActivity.autoRotateEnabled。 | fun PhonePlayerActivity.enterImmersive、fun PhonePlayerActivity.enterFullscreen、fun PhonePlayerActivity.requestOrientation、fun PhonePlayerActivity.autoRotateEnabled、fun PhonePlayerActivity.exitFullscreen、fun PhonePlayerActivity.enterPortraitFullscreen |
| `PhonePlayerLandscapeAssembly.kt` | 横屏装配：相邻集页、视频、弹幕、黑边遮罩和透明手势层。 | fun PhonePlayerActivity.buildLandscapeBase、fun PhonePlayerActivity.buildLandscapeTopControls、fun PhonePlayerActivity.buildLandscapeBottomControls |
| `PhonePlayerLandscapePanel.kt` | 横屏播放设置外壳：左侧点击收起，右侧一栏承载同一套设置行。 | fun PhonePlayerActivity.openLandscapeSettingsImpl、fun PhonePlayerActivity.dismissLandscapeSettingsImpl、fun PhonePlayerActivity.syncFrameModeImpl、fun PhonePlayerActivity.applyFrameModeImpl |
| `PhonePlayerLandscapeSettingsFacade.kt` | 横屏播放设置：只保留右侧 40% 的可改栏。 | fun PhonePlayerActivity.openLandscapeSettings、fun PhonePlayerActivity.syncLandscapeSettingsInfo、fun PhonePlayerActivity.dismissLandscapeSettings、fun PhonePlayerActivity.dismissLandscapeSettingsForRelated、fun PhonePlayerActivity.buildSettingsRowsForLandscape、fun PhonePlayerActivity.setChromeVisibleForLandscape |
| `PhonePlayerLifecycle.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerLifecycle 主要包含 fun PhonePlayerActivity.releasePlayerImpl、fun PhonePlayerActivity.detachForHandoffImpl。 | fun PhonePlayerActivity.releasePlayerImpl、fun PhonePlayerActivity.detachForHandoffImpl |
| `PhonePlayerListener.kt` | 挂播放器监听；正式播放首帧写入统一启动追踪。 | fun PhonePlayerActivity.attachPlayerListener、fun PhonePlayerActivity.onAdvanced |
| `PhonePlayerLoad.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerLoad 主要包含 fun PhonePlayerActivity.load。 | fun PhonePlayerActivity.load |
| `PhonePlayerMode.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerMode 主要包含 fun PhonePlayerActivity.applyMode。 | fun PhonePlayerActivity.applyMode |
| `PhonePlayerModeSupport.kt` | 新形态那一层的入场过渡：淡入 + 从 97% 放到原尺寸。 | fun PhonePlayerActivity.animateModeEnter、fun PhonePlayerActivity.syncDanmakuTopInset、fun PhonePlayerActivity.applyLandscapeResize |
| `PhonePlayerPageFactory.kt` | 建一页播放界面（当前页与相邻页同一份配置，否则翻页时两侧观感会不一样）。 | fun PhonePlayerActivity.makePage、fun PhonePlayerActivity.wirePage |
| `PhonePlayerPause.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerPause 主要包含 fun PhonePlayerActivity.handlePlayerPause。 | fun PhonePlayerActivity.handlePlayerPause |
| `PhonePlayerPlay.kt` | 播放入口：打开会话、接预热源或后台取流并准备播放。 | fun PhonePlayerActivity.play |
| `PhonePlayerPlayInfo.kt` | 把「播放显示」四项铺到竖屏与横屏的信息行：剧名 / 集数靠左，网速 / 时间靠右。 | fun PhonePlayerActivity.syncPlayInfoImpl、fun PhonePlayerActivity.syncLandscapeSettingsInfoImpl |
| `PhonePlayerPortraitUi.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerPortraitUi 主要包含 fun PhonePlayerActivity.buildPortrait。 | fun PhonePlayerActivity.buildPortrait |
| `PhonePlayerPreparation.kt` | 本地准备失败必须保留异常：调用方据此决定是否作废缓存后重取。 | fun PhonePlayerActivity.prepareSource、fun PhonePlayerActivity.prepare、fun PhonePlayerActivity.enterPendingFullscreen |
| `PhonePlayerReopen.kt` | 原地换一条地址：**不换集、不换 Surface、不回片头**。 | fun PhonePlayerActivity.reopenCurrentEpisodeInPlace |
| `PhonePlayerResume.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerResume 主要包含 fun PhonePlayerActivity.handlePlayerResume。 | fun PhonePlayerActivity.handlePlayerResume |
| `PhonePlayerScreenState.kt` | 播放时保持屏幕常亮。 | fun PhonePlayerActivity.syncKeepScreenOn、fun PhonePlayerActivity.restoreUnderlayIfNeeded |
| `PhonePlayerServices.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerServices 主要包含 class PhonePlayerRuntimeState、fun PhonePlayerActivity.ensureMediaSession、fun PhonePlayerActivity.syncMediaSession、fun PhonePlayerActivity.watchNetwork。 | class PhonePlayerRuntimeState、fun PhonePlayerActivity.ensureMediaSession、fun PhonePlayerActivity.syncMediaSession、fun PhonePlayerActivity.watchNetwork、fun PhonePlayerActivity.unwatchNetwork |
| `PhonePlayerSettingsEntry.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerSettingsEntry 主要包含 fun PhonePlayerActivity.openSettings、fun PhonePlayerActivity.openPortraitSettings。 | fun PhonePlayerActivity.openSettings、fun PhonePlayerActivity.openPortraitSettings |
| `PhonePlayerSettingsPanel.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerSettingsPanel 主要包含 object PhonePlayerSettingsPanel。 | object PhonePlayerSettingsPanel |
| `PhonePlayerSettingsRows.kt` | 播放设置的各行：清晰度、倍速、画面、解码、缩放、定时、相关剧集、收藏与弹幕。 | fun PhonePlayerActivity.buildSettingsRowsImpl |
| `PhonePlayerSleep.kt` | 应用「定时关闭」的选择。 | fun PhonePlayerActivity.applySleepChoice、fun PhonePlayerActivity.stopForSleep |
| `PhonePlayerSwipe.kt` | 一屏的高度：竖屏 / 横屏各自的那一层。 | fun PhonePlayerActivity.slideHeightImpl、fun PhonePlayerActivity.beginSwipeImpl、fun PhonePlayerActivity.dragSwipeImpl、fun PhonePlayerActivity.endSwipeImpl、fun PhonePlayerActivity.settleBackImpl、fun PhonePlayerActivity.animateSwipeImpl |
| `PhonePlayerUiAssembly.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerUiAssembly 主要包含 fun PhonePlayerActivity.buildUi、fun PhonePlayerActivity.buildLandscape。 | fun PhonePlayerActivity.buildUi、fun PhonePlayerActivity.buildLandscape |
| `PhonePlayerUnderlay.kt` | 把播放器的输出交回下层推荐页的 Surface。 | fun PhonePlayerActivity.attachUnderlay、fun PhonePlayerActivity.dropUnderlay |
| `PhonePlayerVideoFrame.kt` | 把播放器当前的画面尺寸补到页面上（bind 之后调用），并同步横屏弹幕黑边背景与兜底遮罩。 | fun PhonePlayerActivity.syncVideoFrameImpl、fun PhonePlayerActivity.updateLandscapeVideoFrameImpl、fun PhonePlayerActivity.syncLandscapeLetterboxImpl、fun PhonePlayerActivity.placeLandscapeFullscreenImpl、fun PhonePlayerActivity.applyVideoScaleImpl、fun PhonePlayerActivity.applyLandscapeVideoScaleImpl |
| `PhonePlayerViewFacade.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhonePlayerViewFacade 主要包含 fun PhonePlayerActivity.seekTo、fun PhonePlayerActivity.syncVideoFrame、fun PhonePlayerActivity.applyVideoScale、fun PhonePlayerActivity.syncPlayInfo。 | fun PhonePlayerActivity.seekTo、fun PhonePlayerActivity.syncVideoFrame、fun PhonePlayerActivity.applyVideoScale、fun PhonePlayerActivity.syncPlayInfo |
| `PhonePlayerViewSupport.kt` | 手机播放器视图支撑、黑边几何计算与弹幕不可见时的兜底遮罩。 | object PhonePlayerViewSupport、class PhoneLetterboxMask |
| `PhonePreload.kt` | 推荐流「相邻几部」的预热结果（与电视端 MainActivityPreload 同一个思路，手机化之后只留必要部分）。 | object PhonePreload |
| `PhoneRelatedPanels.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhoneRelatedPanels 主要包含 class PhonePlayerRelatedState、fun PhonePlayerActivity.showRelated、fun PhonePlayerActivity.relatedHeadline、fun PhonePlayerActivity.fillRelated。 | class PhonePlayerRelatedState、fun PhonePlayerActivity.showRelated、fun PhonePlayerActivity.relatedHeadline、fun PhonePlayerActivity.fillRelated、fun PhonePlayerActivity.showAuthorWorks、fun PhonePlayerActivity.toggleFavorite |
| `PhoneSourceRetry.kt` | 手机播放器、控制、选集、恢复与预加载。文件 PhoneSourceRetry 主要包含 class PhoneSourceRetryState、fun PhonePlayerActivity.resetSourceRetryForFreshAttempt、fun PhonePlayerActivity.onEpisodeSourceMissing、fun PhonePlayerActivity.invalidateEpisodeCacheForRetry。 | class PhoneSourceRetryState、fun PhonePlayerActivity.resetSourceRetryForFreshAttempt、fun PhonePlayerActivity.onEpisodeSourceMissing、fun PhonePlayerActivity.invalidateEpisodeCacheForRetry |

#### `feature-phone/src/main/kotlin/com/hgtv/star/phone/search/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneSearchActivity.kt` | 搜索页：输入框 + 提示区 + 两列结果。 | class PhoneSearchActivity |

#### `feature-phone/src/main/kotlin/com/hgtv/star/phone/settings/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneDanmakuSize.kt` | 手机端弹幕字号。 | object PhoneDanmakuSize |
| `PhoneSettingsActions.kt` | 手机设置页、横竖屏布局与系统 UI。文件 PhoneSettingsActions 主要包含 fun PhoneSettingsActivity.appVersionName、fun PhoneSettingsActivity.showAboutDialog、fun PhoneSettingsActivity.restoreDefaults、fun PhoneSettingsActivity.exportBackup。 | fun PhoneSettingsActivity.appVersionName、fun PhoneSettingsActivity.showAboutDialog、fun PhoneSettingsActivity.restoreDefaults、fun PhoneSettingsActivity.exportBackup、fun PhoneSettingsActivity.importBackup、fun PhoneSettingsActivity.performReset |
| `PhoneSettingsActivity.kt` | 手机设置页 Activity，协调横竖屏布局、设置写入和页面生命周期。 | class PhoneSettingsPage、val LAN_SYNC_SOURCE_TTL_MS、val TAG_LANDSCAPE_SETTING_ROW、val TAG_LANDSCAPE_SETTING_SPACER、val SETTINGS_DENSITY_SCALE、val SETTINGS_TEXT_SCALE |
| `PhoneSettingsApply.kt` | 手机设置页、横竖屏布局与系统 UI。文件 PhoneSettingsApply 主要包含 fun PhoneSettingsActivity.applyChoiceImpl、fun PhoneSettingsActivity.applySwitchImpl、fun PhoneSettingsActivity.applySliderImpl。 | fun PhoneSettingsActivity.applyChoiceImpl、fun PhoneSettingsActivity.applySwitchImpl、fun PhoneSettingsActivity.applySliderImpl |
| `PhoneSettingsLanSync.kt` | 手机设置页、横竖屏布局与系统 UI。文件 PhoneSettingsLanSync 主要包含 fun PhoneSettingsActivity.closeLanSyncSessionImpl、fun PhoneSettingsActivity.showLanLibrarySync、fun PhoneSettingsActivity.startLanSyncRoleImpl、fun PhoneSettingsActivity.showLanSyncNoPeersImpl。 | fun PhoneSettingsActivity.closeLanSyncSessionImpl、fun PhoneSettingsActivity.showLanLibrarySync、fun PhoneSettingsActivity.startLanSyncRoleImpl、fun PhoneSettingsActivity.showLanSyncNoPeersImpl、fun PhoneSettingsActivity.showLanSyncRestoreConfirmImpl、fun PhoneSettingsActivity.renderLanSyncImpl |
| `PhoneSettingsLandscapePages.kt` | 手机设置页、横竖屏布局与系统 UI。文件 PhoneSettingsLandscapePages 主要包含 fun PhoneSettingsActivity.renderLandscapePlayback、fun PhoneSettingsActivity.renderLandscapeAppearance、fun PhoneSettingsActivity.renderLandscapeRecommend、fun PhoneSettingsActivity.renderLandscapeSystem。 | fun PhoneSettingsActivity.renderLandscapePlayback、fun PhoneSettingsActivity.renderLandscapeAppearance、fun PhoneSettingsActivity.renderLandscapeRecommend、fun PhoneSettingsActivity.renderLandscapeSystem |
| `PhoneSettingsLandscapeWidgets.kt` | 与 TV 设置页同一种结构：整宽面板 + 面板标题/说明 + 两列或四列设置格。 | fun PhoneSettingsActivity.landscapePanel、fun PhoneSettingsActivity.landscapeLine、fun PhoneSettingsActivity.landscapeCell、fun PhoneSettingsActivity.landscapeColumns、fun PhoneSettingsActivity.landscapeColumnCell、fun PhoneSettingsActivity.landscapeSetting |
| `PhoneSettingsPageSelector.kt` | 四个大项排成两列两行，和「我的」页的四个入口使用同一组名称。 | fun PhoneSettingsActivity.renderPageSelector |
| `PhoneSettingsPortrait.kt` | 手机设置页、横竖屏布局与系统 UI。文件 PhoneSettingsPortrait 主要包含 fun PhoneSettingsActivity.renderPlayback、fun PhoneSettingsActivity.renderDanmaku、fun PhoneSettingsActivity.renderAppearance、fun PhoneSettingsActivity.themeGrid。 | fun PhoneSettingsActivity.renderPlayback、fun PhoneSettingsActivity.renderDanmaku、fun PhoneSettingsActivity.renderAppearance、fun PhoneSettingsActivity.themeGrid、fun PhoneSettingsActivity.switchThemeImpl、fun PhoneSettingsActivity.renderRecommend |
| `PhoneSettingsScroll.kt` | 手机设置页、横竖屏布局与系统 UI。文件 PhoneSettingsScroll 主要包含 class ScrollAnchor、fun PhoneSettingsActivity.currentScrollAnchorImpl、fun PhoneSettingsActivity.anchorViewsImpl、fun PhoneSettingsActivity.topInContainerImpl。 | class ScrollAnchor、fun PhoneSettingsActivity.currentScrollAnchorImpl、fun PhoneSettingsActivity.anchorViewsImpl、fun PhoneSettingsActivity.topInContainerImpl、fun PhoneSettingsActivity.restoreScrollImpl |
| `PhoneSettingsSupport.kt` | 手机设置页、横竖屏布局与系统 UI。文件 PhoneSettingsSupport 主要包含 fun PhoneSettingsActivity.appVersionNameImpl、fun PhoneSettingsActivity.settingsDpImpl、fun TextView.settingsTextSizeImpl、fun PhoneSettingsActivity.settingRowHeightImpl。 | fun PhoneSettingsActivity.appVersionNameImpl、fun PhoneSettingsActivity.settingsDpImpl、fun TextView.settingsTextSizeImpl、fun PhoneSettingsActivity.settingRowHeightImpl、fun PhoneSettingsActivity.settingRowGapImpl、fun anchorKeyImpl |
| `PhoneSettingsSystem.kt` | 横屏设置页使用沉浸式：状态栏和底部系统导航都隐藏，只保留一小条分区导航。 | fun PhoneSettingsActivity.applyLandscapeImmersive、fun PhoneSettingsActivity.handleActivityResultImpl |
| `PhoneSettingsUnified.kt` | 手机设置页、横竖屏布局与系统 UI。文件 PhoneSettingsUnified 主要包含 fun PhoneSettingsActivity.renderSpecs、fun PhoneSettingsActivity.renderUnifiedPlayback、fun PhoneSettingsActivity.renderUnifiedAppearance、fun PhoneSettingsActivity.renderUnifiedRecommend。 | fun PhoneSettingsActivity.renderSpecs、fun PhoneSettingsActivity.renderUnifiedPlayback、fun PhoneSettingsActivity.renderUnifiedAppearance、fun PhoneSettingsActivity.renderUnifiedRecommend、fun PhoneSettingsActivity.renderUnifiedSystem、fun PhoneSettingsActivity.applyActionImpl |
| `PhoneSettingsWidgets.kt` | 手机设置页、横竖屏布局与系统 UI。文件 PhoneSettingsWidgets 主要包含 fun PhoneSettingsActivity.targetHostImpl、fun PhoneSettingsActivity.sectionImpl、fun PhoneSettingsActivity.hintImpl、fun PhoneSettingsActivity.rowImpl。 | fun PhoneSettingsActivity.targetHostImpl、fun PhoneSettingsActivity.sectionImpl、fun PhoneSettingsActivity.hintImpl、fun PhoneSettingsActivity.rowImpl、fun PhoneSettingsActivity.switchImpl、fun PhoneSettingsActivity.actionImpl |

#### `feature-phone/src/main/kotlin/com/hgtv/star/phone/ui/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PhoneCardMenu.kt` | 剧卡菜单（长按海报）：手机上等价于电视端的「确认键 / 菜单键」。 | object PhoneCardMenu |
| `PhoneCards.kt` | 「相关剧集 / 作者作品」那一排卡片：**详情页与播放页共用这一份**。 | object PhoneCards |
| `PhoneChipRow.kt` | 自动换行的芯片容器（搜索历史 / 热门搜索 / 题材标签）。 | class PhoneChipRow |
| `PhoneDebugOverlay.kt` | 手机端的「调试模式」面板（设置 → 设备与性能 → 调试模式）。 | class PhoneDebugOverlay |
| `PhoneDialog.kt` | 手机端统一弹窗：与电视端 TvDialog 同一套结构。 | class PhoneDialog |
| `PhoneEpisodeGrid.kt` | 选集网格（**带分页**，做法照 TV 端的 EpisodeGrid）。 | class PhoneEpisodeGrid |
| `PhoneGlyph.kt` | 通用矢量图标（自绘）。 | class PhoneGlyph、val SETTINGS_PATH_DATA |
| `PhonePagerMotion.kt` | 手机端所有整页翻动的统一运动参数。 | object PhonePagerMotion、class PhonePagerSnapHelper |
| `PhonePosterCard.kt` | 手机端统一海报卡：网格页与详情页 / 播放页的横排相关剧集共用。 | class PhonePosterCard、fun phonePosterScale、val PHONE_POSTER_INSET、val PHONE_SOFT_BAND、val PHONE_BADGE_TEXT_SCALE、val PHONE_TITLE_TEXT_SCALE |
| `PhonePosterFeed.kt` | 海报网格（竖屏基准 1–3 列，横屏映射为 4–6 列），分类 / 榜单 / 搜索三个页面共用。 | class PhonePosterFeed |
| `PhoneSettingControls.kt` | 设置页整行开关：左侧项名，右侧一枚自绘轨道。 | class PhoneSwitchRow、class PhoneSettingSlider |
| `PhoneSheet.kt` | 播放界面上的浮层（播放设置 / 选集共用一套壳）。 | class PhoneSheet |
| `PhoneSoft.kt` | 手机端的新拟态绘制入口。 | object PhoneSoft |
| `PhoneStyle.kt` | 手机端的新拟态令牌。 | object PhoneStyle |
| `PhoneTabBar.kt` | 底部导航栏：**贴底固定**，不做悬浮胶囊。 | class PhoneTabBar |
| `PhoneTheme.kt` | 手机 / 平板的视觉令牌。 | object PhoneColor、object PhoneDimen、fun Context.phoneScale、object PhoneUiScale、fun Context.dp、fun android.view.View.dp |
| `PhoneTopBar.kt` | 手机端统一的页面顶部栏。 | class PhoneTopBar |
| `PhoneTopCollapse.kt` | 列表上滑时把页头收起来、回到顶部再放出来（TV 的 onListScroll / setTopAreaHidden 同一条语义）。 | class PhoneTopCollapse |
| `PhoneTouch.kt` | 播放界面的手势状态机（竖屏信息流与播放页共用这一份）。 | class PhoneTouch |
| `PhoneWindow.kt` | 手机端统一的沉浸式窗口设置。 | fun Activity.phoneEdgeToEdge、fun Activity.phoneStatusBarHeight |
| `VideoFitUi.kt` | 画面模式 → ExoPlayer 的 RESIZE_MODE_*，**手机端唯一的一处绑定**。 | fun VideoFit.resizeMode、fun PlayerView.primeAspectRatio、fun PlayerView.runAfterAspectRatioLayout |

### feature-tv

#### `feature-tv/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `build.gradle.kts` | Gradle Kotlin DSL 构建或设置脚本。 | gradle |

#### `feature-tv/src/main/res/drawable/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ic_action_favorite.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_action_import.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_action_phone.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_action_play.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_action_related.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_action_reserve.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_action_restore.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_action_synopsis.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_action_voice.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_arrow_up.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_brand_bilibili.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_brand_qq.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_chevron_down.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_key_clear.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_key_clear_input.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_key_delete.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_key_space.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_check.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_clock.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_close.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_cpu.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_danmaku.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_frame.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_hd.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_hide.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_info.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_key.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_play.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_restore.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_search.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_speed.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_star.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_trash.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_menu_warning.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_player_back.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_player_episodes.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_player_forward.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_player_pause.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_player_play.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_player_rewind.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_toolbar_category.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_toolbar_keyboard.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_toolbar_profile.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_toolbar_rank.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_toolbar_recommend.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_toolbar_search.xml` | Drawable 图形资源，根节点 vector。 | vector |
| `ic_toolbar_settings.xml` | Drawable 图形资源，根节点 vector。 | vector |

#### `feature-tv/src/main/res/layout/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `player_surface_texture.xml` | 界面布局资源，根节点 com.google.android.exoplayer2.ui.PlayerView。 | com.google.android.exoplayer2.ui.PlayerView |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AppVersion.kt` | 应用版本号。 | object AppVersion |
| `TvMainActivity.kt` | 电视主界面 Activity，持有页面状态、焦点、生命周期、跨页面导航与首页视图缓存；推荐预热按设备档位选择首目标延迟。 | class TvMainActivity |
| `TvMainLifecycleFacade.kt` | feature-tv 模块的 TvMainLifecycleFacade.kt 职责。文件 TvMainLifecycleFacade 主要包含 fun TvMainActivity.returnToDetail、fun TvMainActivity.releaseOffscreenCovers、fun TvMainActivity.resumeVisibleCovers、fun TvMainActivity.handleLowMemory。 | fun TvMainActivity.returnToDetail、fun TvMainActivity.releaseOffscreenCovers、fun TvMainActivity.resumeVisibleCovers、fun TvMainActivity.handleLowMemory、fun TvMainActivity.handleTrimMemory、fun TvMainActivity.handleDestroy |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/account/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AccountActions.kt` | 本文件是从 TvMainActivity.kt 拆出来的「账号参数导入（本机文件 / 手机扫码）与设备注册」这一块。 | var mirrorStorageAsked、fun TvMainActivity.requestMirrorStorage、fun TvMainActivity.onMirrorStorageResult、fun TvMainActivity.pickAccountFile、fun TvMainActivity.consumePendingAccountFile、fun TvMainActivity.importAccount |
| `AccountPanel.kt` | 「我的」页的登录界面：未登录时直接铺在身份行下方，不再有入口按钮，也不再有弹窗。 | val ACCOUNT_PANEL_PHONE、val ACCOUNT_PANEL_QR、val ACCOUNT_LOGIN_MODES、val QR_LOGIN_SIZE、val dialCodes、fun TvMainActivity.showAccountPanel |
| `AccountSync.kt` | 本文件是从 TvMainActivity.kt 拆出来的「收藏 / 进度的账号侧同步（本机改动推到账号，账号数据落到本机）」这一块。 | val HISTORY_PUSHED_LIMIT、fun TvMainActivity.refreshLocalHistoryViews、fun TvMainActivity.syncFavorite、fun TvMainActivity.syncProgress、fun TvMainActivity.syncHistoryDelete、fun TvMainActivity.storeAccount |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/debug/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DebugOverlay.kt` | 「调试模式」：设置里打开后，右上角常驻一块现场面板。 | val DEBUG_TICK_MS、val DEBUG_TICK_IDLE_MS、val DEBUG_TICK_SLOW_MS、val DEBUG_IDLE_BACKOFF、val DEBUG_IDLE_DEEP、val DEBUG_ENV_TTL_MS |
| `TvDebugOverlayState.kt` | 调试面板在 Activity 生命周期内保留的可变状态。 | class TvDebugOverlayState |
| `TvMetrics.kt` | 设计画布：1280 × 720（像素），也就是「720P 电视上一比一」的那块版面。 | val DESIGN_WIDTH_PX、val DESIGN_HEIGHT_PX、val DESIGN_SCALE_MIN、val DESIGN_SCALE_MAX、fun Context.designScale、fun Context.designSize |
| `TvNetworkLogging.kt` | 网络失败诊断：客户设备上「首页空白」时，这行日志给出主机名与异常类型。 | fun TvMainActivity.setupNetworkLogging |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/detail/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DetailPage.kt` | 本文件是从 TvMainActivity.kt 拆出来的「详情页：简介、选集、相关推荐」这一块。 | val DETAIL_MEMO_LIMIT、fun TvMainActivity.openDetail、fun sameDetailContent、fun TvMainActivity.loadDetailAuthor、fun TvMainActivity.resumeTargetFor、fun TvMainActivity.prefetchRelated |
| `HongguoSearchPage.kt` | 当前搜索页是否用自绘键盘；书库未加载完成时沿用系统键盘页面。 | fun TvMainActivity.hongguoKeyboardMode、fun TvMainActivity.setSearchKeyboard、fun TvMainActivity.keyboardSwitchButton、fun TvMainActivity.appendHongguoDraft、fun TvMainActivity.setHongguoDraft、fun TvMainActivity.syncHongguoInput |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/home/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `HomePage.kt` | 首页剧架与卡片行；完整构建过的 `HomeScreen` 会跨页面复用并就地刷新数据。 | val SHELF_SIZE、fun TvMainActivity.renderHome、fun TvMainActivity.historyRows、fun TvMainActivity.favoriteRows、fun TvMainActivity.homeShelves、fun TvMainActivity.localShelf |
| `RecommendBatchPreload.kt` | 相邻一部的详情 / 地址 / 首块优先；播放模型批量预取晚一点再跑，预热详情不补作者。 | val RECOMMEND_BATCH_MODEL_DELAY_MS、fun TvMainActivity.prefetchRecommendNeighbors、fun TvMainActivity.cancelRecommendBatchModels、fun TvMainActivity.prefetchRecommendBatchModels、fun TvMainActivity.prefetchFormalBatchModels |
| `RecommendControls.kt` | 上下键换剧；索引变化交给播放选择层先摘除旧播放层，再准备下一部。 | fun TvMainActivity.switchRecommend、fun TvMainActivity.seekRecommend |
| `RecommendCover.kt` | 切换过渡：换作瞬间全黑，新片源真实出帧且宽高就绪后才淡出；加载期不暴露上一部旧帧，淡出完成可回调撤 loading。 | val COVER_OUT_MS、val COVER_OUT_FAST_MS、fun TvMainActivity.showRecommendCover、fun TvMainActivity.revealRecommendCover、val PICTURE_RECOVERY_LIMIT |
| `RecommendEntry.kt` | 打开推荐页并自动播放一部推荐；冷启动按统一命名空间读取上次播放位置，无快照时先取一个可播放条目，剩余列表在首帧后补齐。 | fun TvMainActivity.openRecommend、fun TvMainActivity.resumeIndex、fun TvMainActivity.nextRecommendIndex、val RESUME_CACHE_PROBE、fun TvMainActivity.mediaCached |
| `RecommendEpisode.kt` | 推荐页单集起播、停留结算、曝光上报与每部起播时重置预热轮次；播放位置写入与快照一致的命名空间，相邻地址与首块由播放首帧后的预热链补上。 | val WATCH_DWELL_MS、val WATCH_PLAY_MS、val QUICK_SWIPE_MS、fun TvMainActivity.startRecommendEpisode、fun TvMainActivity.settleRecommendWatch、fun TvMainActivity.reportRecommendImpression、fun TvMainActivity.reportRecommendProgress |
| `RecommendFeedControl.kt` | 冷启动续播时把「取推荐流」推迟到首帧之后；推荐范围或横屏请求变化时清列表、快照和游标。 | val RECOMMEND_FEED_DELAY_MS、fun TvMainActivity.requestRecommendFeedAfterFirstFrame、fun TvMainActivity.fireRecommendFeed、fun TvMainActivity.cancelRecommendFeed、fun TvMainActivity.invalidateRecommendForFilterChange、fun TvMainActivity.closeRecommendCache、fun TvMainActivity.cancelRecommendWarm |
| `RecommendFeedLoad.kt` | 取一页推荐流；支持首条快速加载的 minItems 参数并记录统一首帧链路。 | fun TvMainActivity.loadRecommendPage、fun TvMainActivity.ensureRecommendLookahead、fun TvMainActivity.settleRecommendLoad |
| `RecommendNavigation.kt` | 推荐页进入正式播放与返回首页路由；返回首页优先复用缓存并延后播放器重资源回收。 | fun TvMainActivity.enterFormalPlayback、fun TvMainActivity.goHomeFromRecommend、fun TvMainActivity.exitRecommendToHome、fun TvMainActivity.leaveRecommendPlaybackToHome |
| `RecommendOverlay.kt` | 推荐页底部那块信息浮层：进度 + 剧名 + 操作说明；中心 loading 只在真实首帧后撤掉。 | fun TvMainActivity.attachRecommendOverlay、fun TvMainActivity.recommendChip、fun TvMainActivity.dismissRecommendOverlay、fun TvMainActivity.updateRecommendOverlay |
| `RecommendPage.kt` | 本文件是「推荐」这一条独立的播放链路：抖音式的上下换剧、确定进正片、菜单开播放设置、返回回主页面；快照、位置和游标共用命名空间，首条可先起播，列表在首帧后补齐。 | val RECOMMEND_SKIP_LIMIT、val RECOMMEND_MAX_CHAIN、val RECOMMEND_CACHE_NAMESPACE、val RECOMMEND_FIRST_LIMIT、val RECOMMEND_FIRST_ITEMS、val RECOMMEND_PAGE_LIMIT、val RECOMMEND_LOOKAHEAD |
| `RecommendPlaybackSelection.kt` | 播放推荐列表里的第 index 部；换到另一部时保留稳定 PlayerView，通过 keep-content=false 清空旧 Surface、detach 并隐藏到新源准备完成、停止旧媒体、立即作废旧 work，再显示当前作品 loading 并取详情；推荐详情跳过作者查询。 | fun TvMainActivity.playRecommendAt、fun TvMainActivity.skipRecommend、fun TvMainActivity.resetRecommendPlaybackForSwitch |
| `RecommendUi.kt` | 本机兴趣画像（进推荐页时建一次，之后每页复用）。 | fun TvMainActivity.loadGenderOptions、fun TvMainActivity.showRecommendMessage、fun TvMainActivity.showRecommendLoading、fun TvMainActivity.showRecommendToast |
| `RecommendWarm.kt` | 一次在途的「相邻一部预热」。 | class RecommendWarm |
| `RecommendWarmDetail.kt` | 同时在途的预热条数上限（WiFi）。 | val RECOMMEND_WARM_SLOTS、val RECOMMEND_WARM_SLOTS_METERED、fun TvMainActivity.warmRecommendDetail、fun TvMainActivity.hasWarmSlot |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/input/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `TouchBar.kt` | 电视遥控/触屏输入与焦点控制。文件 TouchBar 主要包含 fun TvMainActivity.attachRecommendEntry、fun TvMainActivity.syncRecommendEntry、fun TvMainActivity.resetTouchBar、fun TvMainActivity.updateTouchPlayLabel。 | fun TvMainActivity.attachRecommendEntry、fun TvMainActivity.syncRecommendEntry、fun TvMainActivity.resetTouchBar、fun TvMainActivity.updateTouchPlayLabel、fun TvMainActivity.wireSeekTouch、fun TvMainActivity.onScrubProgress |
| `TouchController.kt` | 本文件：触屏适配（平板 / 手机 / 触屏一体机）。 | fun TvMainActivity.onTouchSeen、fun TvMainActivity.armTouchIfScreenExists、fun TvMainActivity.applyDeviceForm、fun TvMainActivity.deviceMode、fun TvMainActivity.wireSystemInsets、fun TvMainActivity.attachTouchLayer |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/library/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `LanLibrarySync.kt` | 电视本机书库与局域网同步。文件 LanLibrarySync 主要包含 var lanSyncUiJob、var lanSyncUiDialog、var lanSyncUiStep、var lanSyncUiStatus。 | var lanSyncUiJob、var lanSyncUiDialog、var lanSyncUiStep、var lanSyncUiStatus、fun TvMainActivity.closeLanSyncSession、fun TvMainActivity.showLanLibrarySync |
| `LibraryPage.kt` | 本机库加载、页面重建与异步任务封装；释放页面时保留栅格行池和完整首页视图供下次复用。 | fun TvMainActivity.showLibraryError、fun TvMainActivity.showStartupShell、fun TvMainActivity.showStartupLoading、fun TvMainActivity.loadLibrary、fun TvMainActivity.ensureSearchCollector、fun TvMainActivity.releaseCatalogViews |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/list/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `BackToTop.kt` | 「回到顶部」浮层：栅格列表页上的一枚快捷按钮，固定在**右下角**。 | fun TvMainActivity.attachBackToTop、fun TvMainActivity.updateBackToTop、fun TvMainActivity.backToTopVisible、val BACK_TO_TOP_EDGE_GAP、fun TvMainActivity.positionBackToTop、fun TvMainActivity.backToTopTarget |
| `HomeScreen.kt` | 电视首页横排剧架组件；首栏先建少量卡片，其余按帧增量补齐，负责焦点、滚动恢复和封面挂载。 | class HomeScreen、val HOME_FIRST_BATCH、val HOME_FIRST_CARDS、val HOME_BATCH_BUDGET_MS、val HOME_BATCH_BUDGET_LOW_RAM_MS |
| `ListPage.kt` | 电视列表页页面骨架，负责分类、榜单、搜索、我的页和设置页的页面组装。 | fun equalChipWidths、fun TvMainActivity.showCatalog |
| `ListPageContent.kt` | 电视列表、网格、剧架与分页；栅格卡片 View 通过跨页行池复用，只重绑数据与封面。 | fun TvMainActivity.setRankSubtitle、fun TvMainActivity.seriesList、class ClipFrameLayout、fun lastFocusable、fun firstFocusable |
| `SeriesActions.kt` | 剧卡上「点一下」与「长按 / 菜单键」这两个动作，全站只有这一份实现。 | fun TvMainActivity.openSeries、fun TvMainActivity.seriesMenu |
| `SeriesGrid.kt` | 剧卡栅格的窗口化复用、分页追加、滚动与焦点；回收行时从实际父容器摘除，避免复用 stale parent。 | fun TvMainActivity.gridCards、fun TvMainActivity.isGridTail、fun TvMainActivity.gridRowCount、fun TvMainActivity.rowStride、fun TvMainActivity.gridTextScale、fun TvMainActivity.updateGridSpacers、fun TvMainActivity.recycleGridRow |
| `SeriesGridPaging.kt` | 电视列表、网格、剧架与分页。文件 SeriesGridPaging 主要包含 fun TvMainActivity.moreLabel、fun TvMainActivity.loadMore、fun TvMainActivity.prefetchNextPage、fun TvMainActivity.applyNextPage。 | fun TvMainActivity.moreLabel、fun TvMainActivity.loadMore、fun TvMainActivity.prefetchNextPage、fun TvMainActivity.applyNextPage、fun TvMainActivity.nearGridEnd、fun TvMainActivity.rowsLeftAfterConsumed |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/navigation/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `HeaderVisibilityController.kt` | 记住一页列表的滚动位置；超出 SCROLL_MEMORY 条就丢掉最早的一条。 | fun TvMainActivity.rememberScrollImpl、fun TvMainActivity.registerTopAreaRowImpl、fun TvMainActivity.onListScrollImpl、fun TvMainActivity.syncHeaderStateImpl、fun TvMainActivity.headerShouldHideImpl、fun TvMainActivity.focusedContentRow |
| `MainKeyDispatch.kt` | 处理返回键导航。 | fun TvMainActivity.goBackImpl、fun TvMainActivity.confirmExitImpl、fun TvMainActivity.prepareDispatchTouchEvent、fun TvMainActivity.handleDispatchKeyEvent、fun TvMainActivity.focusOnCard |
| `TvKeyMap.kt` | 本文件是从 TvMainActivity.kt 拆出来的「播放界面按键自定义：设置页那一栏的文案 / 选择弹窗， 以及播放时的按键分发」这一块。 | fun TvMainActivity.keyRowLabel、fun TvMainActivity.restoreKeysLabel、fun TvMainActivity.showKeyActionPicker、fun TvMainActivity.playerKeyAction、fun TvMainActivity.dispatchPlayerKey |
| `TvLayout.kt` | 本文件是从 TvMainActivity.kt 拆出来的「尺寸换算、控件工厂（按钮 / 文本 / 行 / 页骨架）与窗口背景、主题切换」这一块。 | fun TvMainActivity.dp、fun TvMainActivity.widthDp、fun TvMainActivity.pageInset、fun TvMainActivity.pageBleed、fun TvMainActivity.applyPerformanceProfile、fun TvMainActivity.lp |
| `TvMainStateRestore.kt` | 按 onSaveInstanceState 存下的现场回到原来的页面。 | fun TvMainActivity.restoreLastScreenForResume |
| `TvMainTick.kt` | 播放页每秒调度：定时停止、界面刷新、预取、进度采样和落盘节拍。 | fun TvMainActivity.runTvMainTick |
| `TvNavigation.kt` | 本文件是从 TvMainActivity.kt 拆出来的「顶部五个入口、切换页面、分类与榜单芯片、页头导航与页标题」这一块。 | fun TvMainActivity.switchTab、fun TvMainActivity.switchRankBoard、fun TvMainActivity.cachedRankPage、fun categoryChipKey、fun TvMainActivity.catalogSelectionInFlight、fun TvMainActivity.rememberCategoryState |
| `TvNavigator.kt` | 电视端的路由落地：把 Route 翻译成「切 screen + 重建视图树」。 | class TvNavigator |
| `TvScreenConstants.kt` | 本文件是从 TvMainActivity.kt 拆出来的「全站常量与静态表（标签下标、栅格列数、面板行号、阈值……）」这一块。 | val LOAD_MORE、val TAB_HOME、val TAB_SEARCH、val TAB_CATEGORY、val TAB_RANK、val TAB_SETTINGS |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/player/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DanmakuSlider.kt` | 弹幕字号 / 透明度的滑动调节条（播放设置面板与设置页共用同一个控件）。 | class DanmakuSlider |
| `EpisodeGrid.kt` | 选集网格：简介页与播放界面共用同一份实现。 | class EpisodeGrid、val PAGER_HEIGHT、val CELL_GAP、val CELL_MIN、val CELL_MAX、val PAGE_SIDE_PAD |
| `EpisodePanel.kt` | 播放器底部的选集面板：贴底、整宽、约 300dp 高，只负责把 EpisodeGrid 放进一个透明弹窗里。 | class EpisodePanel、val MAX_SCREEN_SHARE |
| `PlaybackController.kt` | 本文件是从 TvMainActivity.kt 拆出来的「播放中控：缓冲抑制、下一集预取、重试恢复、HUD 与传输层回收」这一块。 @file:JvmName("MainActivityPlaybackKt") @file:JvmMultifileClass | fun TvMainActivity.closeTransport、fun TvMainActivity.suppressBufferingUi、fun TvMainActivity.restoreBufferingUi、fun TvMainActivity.syncBufferingDots、fun TvMainActivity.scheduleRetire、fun TvMainActivity.forceRetirePrevious |
| `PlaybackDiagnostics.kt` | 播放失败的可观测层：**正式包里唯一还活着的那份证据**。 | object PlaybackDiag、class PlaybackDiagnosticException、class PlaybackPrepareException、fun playbackStage、fun decoderOf、fun chainOf |
| `PlaybackFailureLabels.kt` | 电视播放内核、HUD、恢复、渲染与诊断。文件 PlaybackFailureLabels 主要包含 object PlaybackFailureLabels。 | object PlaybackFailureLabels |
| `PlaybackFailureReport.kt` | 电视播放内核、HUD、恢复、渲染与诊断。文件 PlaybackFailureReport 主要包含 fun TvMainActivity.playbackScene、val decoderTableCache、fun availableDecoders、fun looksSoftware。 | fun TvMainActivity.playbackScene、val decoderTableCache、fun availableDecoders、fun looksSoftware、fun PlaybackDiag.fillFormatFrom、fun formatLine |
| `PlaybackGeometry.kt` | 电视播放内核、HUD、恢复、渲染与诊断。文件 PlaybackGeometry 主要包含 fun com.hgtv.star.core.VideoFit.resizeMode、fun TvMainActivity.frameResizeMode、fun TvMainActivity.autoFullscreenAvailable、fun TvMainActivity.autoFullscreenRotated。 @file:JvmName("MainActivityPlaybackKt") @file:JvmMultifileClass | fun com.hgtv.star.core.VideoFit.resizeMode、fun TvMainActivity.frameResizeMode、fun TvMainActivity.autoFullscreenAvailable、fun TvMainActivity.autoFullscreenRotated、fun TvMainActivity.fullscreenSpinDirection、fun TvMainActivity.applyVideoGeometry |
| `PlaybackHud.kt` | 电视播放内核、HUD、恢复、渲染与诊断。文件 PlaybackHud 主要包含 fun TvMainActivity.hidePlaybackOverlay、fun TvMainActivity.scheduleOverlayAutoHide、fun TvMainActivity.cancelOverlayAutoHide、fun TvMainActivity.hudShown。 @file:JvmName("MainActivityPlaybackKt") @file:JvmMultifileClass | fun TvMainActivity.hidePlaybackOverlay、fun TvMainActivity.scheduleOverlayAutoHide、fun TvMainActivity.cancelOverlayAutoHide、fun TvMainActivity.hudShown、fun TvMainActivity.onPlaybackPage、fun TvMainActivity.syncKeepScreenOn |
| `PlaybackMenu.kt` | 播放器的底部设置面板（遥控器「上」打开）。 | class PlaybackMenu |
| `PlaybackMenuController.kt` | 播放菜单与选择器、进度保存，以及页面切换时播放器 Surface/资源的分离回收。 | fun TvMainActivity.showPlaybackMenu、fun TvMainActivity.buildPlaybackMenu、fun TvMainActivity.obtainPlaybackMenu、fun TvMainActivity.detachPlayerSurface、fun menuShape、fun TvMainActivity.syncPlaybackMenu、fun TvMainActivity.dropPlaybackMenuCache |
| `PlaybackRecovery.kt` | 电视播放内核、HUD、恢复、渲染与诊断。文件 PlaybackRecovery 主要包含 fun TvMainActivity.networkAvailable、fun TvMainActivity.refetchAddressOnNetwork、fun TvMainActivity.currentPosition、fun TvMainActivity.cancelRecovery。 @file:JvmName("MainActivityPlaybackKt") @file:JvmMultifileClass | fun TvMainActivity.networkAvailable、fun TvMainActivity.refetchAddressOnNetwork、fun TvMainActivity.currentPosition、fun TvMainActivity.cancelRecovery、fun TvMainActivity.scheduleRecovery、fun playbackMediaKind |
| `PlaybackSurfaceBuilder.kt` | 重建电视播放器 surface 与覆盖层；推荐页重建时补挂中心 loading，避免黑场空档。 | fun TvMainActivity.buildPlaybackSurface |
| `PlayerActions.kt` | 播放设置面板**第一行那排动作**（相关剧集 / 作者作品 / 加入收藏 / 查看简介）的实现。 | fun TvMainActivity.showPlayerRelated、fun TvMainActivity.showPlayerAuthorWorks、fun TvMainActivity.showPlayerWorksStrip、fun TvMainActivity.refreshStripData、fun TvMainActivity.showPlayerIntro、fun TvMainActivity.playerFavoriteLabel |
| `PlayerController.kt` | 电视播放起播内核，负责换集、播放源设置、播放器监听和播放页面装配；用户主动点集传直连标记，自动连播与恢复保持批量路径。 | fun TvMainActivity.loadDanmaku、fun TvMainActivity.playEpisode、fun TvMainActivity.scheduleRecommendAfterFirstFrame |
| `PreloadController.kt` | 推荐页的「双向滑动预加载」编排：窗口与规则在 core/SlideCache.kt（纯逻辑、有单测），这里负责取详情、取地址、解析索引、拉首块和让路；低配电视首帧先准备最近一部，再自动续补其余目标，预热详情不补作者。 | fun TvMainActivity.preloadGateOpen、val PRELOAD_MAX_TARGETS、fun TvMainActivity.cancelScheduledPreload、fun TvMainActivity.scheduleRecommendPreload、fun TvMainActivity.scheduleRecommendDwell、fun TvMainActivity.preloadScheduler、fun TvMainActivity.applyRecommendPlan |
| `RelatedPanel.kt` | 播放界面底部的「换一部接着看」浮层。 | class RelatedPanel |
| `TvDanmakuClock.kt` | 把播放器的当前状态采样给弹幕层。 | fun TvMainActivity.syncDanmakuClock |
| `TvPlaybackAnalytics.kt` | 播放链路的可观测点：只回答三个问题。 | fun TvMainActivity.playbackAnalytics |
| `TvPlaybackPreferences.kt` | 本次播放**实际生效**的清晰度上限。 | val TvMainActivity.playbackMaxQuality、val TvMainActivity.playbackDecoderMode、fun TvMainActivity.preparedKeyFor、fun TvMainActivity.playerDecoderMode |
| `TvPlaybackRunnables.kt` | 播放设置、选集和控制条共用同一枚「无操作自动消失」计时器。 | fun TvMainActivity.runOverlayAutoHide、fun TvMainActivity.runPendingSeek、fun TvMainActivity.runForcedRetire |
| `TvPlaybackWarmup.kt` | 把「首帧之前才发现很贵」的几处初始化提前做掉。 | fun TvMainActivity.primePlaybackClasses、fun TvMainActivity.probeHardwareHevcDecoder |
| `TvPlayerBuilder.kt` | 建一个播放器并解析设备 / 网络 / 首帧 EWMA 缓冲档位（**不含挂 Surface**）；记录播放器构建链路。 | fun TvMainActivity.buildPlayer、resolveTvBufferProfile |
| `TvPlayerDecoderSwitch.kt` | 换解码方式：重建播放器并从当前位置接着播。 | fun TvMainActivity.applyDecoderMode |
| `TvPlayerDecoding.kt` | 按「硬件解码 / 软件解码」筛**视频**解码器。 | fun decoderSelector |
| `TvPlayerLifecycle.kt` | 构造之后的配置，**只能在主线程调用**。 | fun TvMainActivity.configurePlayer、fun TvMainActivity.attachPlayerView、fun TvMainActivity.warmPlayer、fun TvMainActivity.buildPlayerAsync |
| `TvPlayerRenderers.kt` | 电视端渲染器工厂：两处针对播放链路的加固。 | class TvRenderersFactory、class SpeedSafeVideoRenderer |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/search/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `SearchPage.kt` | 本文件是从 TvMainActivity.kt 拆出来的「搜索页：输入框、热词与联想提示、搜索历史」这一块。 | fun TvMainActivity.searchBar、fun TvMainActivity.focusSearchInput、fun TvMainActivity.cancelSearchInputMethod、fun TvMainActivity.requestSearchInputMethod、fun TvMainActivity.fitSearchRow、fun TvMainActivity.wireHeadNavUp |
| `VoiceSearch.kt` | 本文件是从 TvMainActivity.kt 拆出来的「搜索页语音输入」这一块。 | fun TvMainActivity.startVoiceSearch、fun TvMainActivity.hasSpeechRecognizer、fun TvMainActivity.applyVoiceReturn、fun normalizeVoiceQuery、fun TvMainActivity.noteVoice |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/settings/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `SettingsPage.kt` | 电视设置页构建入口，组装播放、外观、推荐和系统设置分组；推荐范围支持只保留横屏短剧。 | fun TvMainActivity.settings、fun TvMainActivity.showAboutDialog、fun TvMainActivity.deviceFormSettingLabel、fun TvMainActivity.showDeviceFormPicker、fun TvMainActivity.applyDeviceFormSelection、fun TvMainActivity.showTouchOnlyDeviceFormConfirm |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/system/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ConsentDialogs.kt` | 本文件是两条「知情同意」：进「我的」页的那条使用须知，与「获取验证码」前的那次确认。 | val CONSENT_TEXT、val SMS_CONSENT_TEXT、val CONSENT_TITLE、val SMS_CONSENT_TITLE、fun TvMainActivity.ensureThirdPartyAccepted、fun TvMainActivity.confirmSmsConsent |
| `LocalReset.kt` | 本文件是从 TvMainActivity.kt 拆出来的「清空应用缓存（恢复出厂）」这一块。 | fun TvMainActivity.clearLocalData、var resetStorageAsked、fun TvMainActivity.performLocalReset、fun TvMainActivity.resetStorageGranted、fun resetLabel |
| `MainBackgroundLifecycle.kt` | TvMainActivity 创建、配置、权限结果与现场保存。文件 MainBackgroundLifecycle 主要包含 fun TvMainActivity.onPauseImpl、fun TvMainActivity.onStopImpl、fun TvMainActivity.onDestroyImpl。 | fun TvMainActivity.onPauseImpl、fun TvMainActivity.onStopImpl、fun TvMainActivity.onDestroyImpl |
| `MainInitialization.kt` | 第二阶段的完整主界面初始化。 | fun TvMainActivity.initializeMainAfterFirstFrameImpl |
| `MainResume.kt` | onResume 在调用 super 之后的页面恢复编排。 | fun TvMainActivity.resumeAfterSuper |
| `MemoryPressureController.kt` | 把视口之外的封面从 ImageView 上摘掉（低内存专用，见 ArtworkLoader.releaseHiddenCovers）。 | fun TvMainActivity.releaseOffscreenCoversImpl、fun TvMainActivity.resumeVisibleCoversImpl、fun TvMainActivity.onLowMemoryImpl、fun TvMainActivity.onTrimMemoryImpl |
| `PerformanceProfile.kt` | 本文件是从 TvMainActivity.kt 拆出来的「「我的」页：抬头、四个栏目、账号数据与登录提示」这一块。 | fun TvMainActivity.profileHead、fun TvMainActivity.accountItems、fun TvMainActivity.historyFinished、fun TvMainActivity.accountHistoryCard、fun TvMainActivity.switchAccountTab、fun TvMainActivity.profileHeadControls |
| `TvMainActivityConfig.kt` | TvMainActivity 创建、配置、权限结果与现场保存。文件 TvMainActivityConfig 主要包含 fun TvMainActivity.reloadAfterConfigurationChange。 | fun TvMainActivity.reloadAfterConfigurationChange |
| `TvMainActivityCreate.kt` | TvMainActivity 创建、配置、权限结果与现场保存。文件 TvMainActivityCreate 主要包含 fun TvMainActivity.initializeMainShell。 | fun TvMainActivity.initializeMainShell |
| `TvMainActivityResults.kt` | TvMainActivity 创建、配置、权限结果与现场保存。文件 TvMainActivityResults 主要包含 fun TvMainActivity.handleActivityResultImpl、fun TvMainActivity.handleRequestPermissionsResultImpl。 | fun TvMainActivity.handleActivityResultImpl、fun TvMainActivity.handleRequestPermissionsResultImpl |
| `TvMainActivitySaveState.kt` | TvMainActivity 创建、配置、权限结果与现场保存。文件 TvMainActivitySaveState 主要包含 fun TvMainActivity.saveMainState。 | fun TvMainActivity.saveMainState |

#### `feature-tv/src/main/kotlin/com/hgtv/star/tv/ui/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DialogItem.kt` | 弹窗里的选项行（设计稿的 .mitem）：整行胶囊，左边一枚线性图标，右边文字。 | class DialogItem |
| `EpisodeCell.kt` | 选集单元格：网格里的方块，高度、字号、焦点反馈与按钮共用同一套令牌。 | class EpisodeCell |
| `HongguoKeyboard.kt` | HG键盘的字符表：**每行 6 个**，字母在前、数字在后，中间不断行。 | val LETTER_ROWS、val NUMBER_ROWS、val KEY_COLUMNS、val FUNCTION_KEYS、val FOCUS_RADIUS、val ICON_SIZE |
| `IconButton.kt` | 顶部导航的圆形图标入口（搜索 / 排行榜 / 设置）。 | class IconButton |
| `LoginForm.kt` | 「手机号 + 验证码」登录表单的视觉件：输入框、字段说明、按钮行。 | object LoginForm |
| `PosterCard.kt` | 电视端海报卡：结构来自 PosterCardView，这里只补电视的材质、字号、进度条与焦点。 | class PosterCard |
| `SectionHeading.kt` | 分区标题：一行粗体文字，从内容线开始。 | class SectionHeading |
| `SeekIndicator.kt` | 播放进度条（对应播放器控制层，**不套新拟态**）。 | class SeekIndicator |
| `SettingSlider.kt` | 设置页里「项名 + 滑轨」合成一行的滑动条。 | class SettingSlider |
| `ToggleChip.kt` | 互斥开关（排行榜页的四个榜单、分类页的大类与小类）：就是统一按钮的选中态——按进去的凹槽。 | class ToggleChip |
| `TvComponents.kt` | 电视端通用控件集合，承载按钮、卡片、开关、标签等基础 UI。 | fun Context.tvDp、fun Context.softBand、fun Context.softBandLarge、fun Context.tvText、fun T.focusOverflow、fun ScrollView.focusBleed |
| `TvDialog.kt` | 电视端统一模态框，管理标题、内容、列表、按钮、焦点与关闭动画；支持 mandatory、touch-only、remote-only 与窗口焦点后补落初始焦点。 | class TvDialog、fun show、fun requestInitialFocus |
| `TvDialogFocus.kt` | 电视共用控件、弹窗、样式、键盘与工具。文件 TvDialogFocus 主要包含 fun TvDialog.wireListExitImpl、fun ViewGroup.firstChildOrNull、fun View.anyFocusable、fun View.firstFocusable。 | fun TvDialog.wireListExitImpl、fun ViewGroup.firstChildOrNull、fun View.anyFocusable、fun View.firstFocusable |
| `TvStyle.kt` | 电视端设计令牌：新拟态（Neumorphism / Soft UI）。 | object TvStyle |
| `TvSwitchRow.kt` | 设置页的整行开关：左边是项名，右边是一枚自绘的轨道 + 圆钮。 | class TvSwitchRow |
| `TvTools.kt` | 一条提示显示多久（毫秒）。 | val TOAST_MS、val TOAST_FADE、val TOAST_MARGIN、val SESSION_FIELD_HEIGHT、val SESSION_FIELD_TEXT、val UPSMS_POLL_MS |
| `TvToolsPhone.kt` | 电视共用控件、弹窗、样式、键盘与工具。文件 TvToolsPhone 主要包含 fun TvTools.toast、fun TvTools.clearToast、fun TvTools.qr、fun TvTools.lanAddress。 | fun TvTools.toast、fun TvTools.clearToast、fun TvTools.qr、fun TvTools.lanAddress、fun TvTools.smsFallback、fun TvTools.accountImport |

### shared

#### `shared/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `build.gradle.kts` | Gradle Kotlin DSL 构建或设置脚本。 | gradle |

#### `shared/src/main/kotlin/com/hgtv/star/shared/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `ProjectInfo.kt` | 项目公开地址。 | object ProjectInfo |

#### `shared/src/main/kotlin/com/hgtv/star/shared/account/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AccountLogin.kt` | 账号凭据的落地校验（电视端与手机端共用一份）。 | object AccountLogin、object AccountMerge |

#### `shared/src/main/kotlin/com/hgtv/star/shared/architecture/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AppExecutors.kt` | 线程预算的 Android 侧入口。 | object AppExecutors |
| `AppExit.kt` | 用户主动退出：先做同步能做的收尾，再移除任务并结束当前进程。 | object AppExit |
| `AppRelaunch.kt` | 重新进入应用。 | object AppRelaunch |
| `Lanes.kt` | 把协程挂到指定车道上的几个小工具。 | fun CoroutineScope.network、fun CoroutineScope.fanout、fun CoroutineScope.player、fun CoroutineScope.danmaku、fun CoroutineScope.disk |
| `PlaybackController.kt` | 播放会话的唯一事实来源。 | class PlaybackController |
| `PlaybackPower.kt` | 播放期间的**电源与网络保活**。 | class PlaybackPower |
| `PlaybackProgressTracker.kt` | 播放进度采样与落盘节拍。 | class PlaybackProgressTracker |
| `PlaybackSession.kt` | 播放行为的共享门面。 | class PlaybackSession |
| `PlaybackSourceCoordinator.kt` | TV / Phone 共用的播放源获取门面，透传主动点集的直连参数。 | class PlaybackSourceCoordinator |

#### `shared/src/main/kotlin/com/hgtv/star/shared/compat/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DeviceForm.kt` | 这台机器在用户的眼里的形态：手机 / 平板 / 电视。 | class DeviceForm、class DeviceProfile、fun Context.detectDeviceForm、fun Context.hasInputSource、class ScreenFacts、fun Context.screenFacts |
| `DeviceFormSelection.kt` | 首页与设置页共用的设备类型选择 Activity action / extra 契约，包含样式与结果字段。 | object DeviceFormSelection、EXTRA_TV_STYLE |
| `OutlineCompat.kt` | 圆角 / 圆形裁剪。 | fun roundCornersCompat、fun clipToCircleCompat、object RoundCornersApi21 |
| `Platform.kt` | Android 4.4（API 19）到现在的平台差异都收在这个文件里。 | fun Context.activityManager、fun Context.connectivityManager、fun ConnectivityManager.activeNetworkOrNull、fun ConnectivityManager.capabilitiesOf、fun ConnectivityManager.hasInternet、fun ConnectivityManager.isMeteredConnection |

#### `shared/src/main/kotlin/com/hgtv/star/shared/data/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AppRuntime.kt` | TV 与手机共用的进程级运行时。 | object AppRuntime |
| `AppSessionStore.kt` | SessionState 的落盘实现。 | class AppSessionStore |
| `ArtworkCache.kt` | 封面磁盘缓存：存的是**已经缩到卡片尺寸的 WebP**，不是原图。 | class ArtworkCache |
| `ArtworkLoader.kt` | 封面加载器，负责请求调度、磁盘/内存缓存、解码、预取和低内存裁剪。 | class ArtworkLoader |
| `ArtworkTiming.kt` | 封面加载的**计时统计**（只在 debug 包里用日志看，不参与界面逻辑）。 | class ArtworkTiming |
| `DeviceHealer.kt` | 设备身份自愈：**服务端不认这份身份**时，换一台设备重注册。 | object DeviceHealer |
| `DeviceRegistration.kt` | 设备注册的**唯一实现**：电视与手机共用；注册后的 TNC 刷新延后到播放首帧。 | object DeviceRegistration |
| `FavoriteMonitor.kt` | Serial foreground checks; cancellation owns only calls made by this monitor. | class FavoriteMonitor |
| `IdentityBinder.kt` | 把**本机身份与会话**灌进进程级仓库，并按实际设备形态选择电视统一设备档案。 | object IdentityBinder、fun Library.isTvDeviceForProfile |
| `Library.kt` | 本机书库门面，统一管理历史、收藏、稍后看、设置、推荐请求偏好、账号缓存和持久化；加载回调与 loader 启动由同一把锁串行化。 | typealias WatchProgress、class Library、fun loadAsync、fun discardPendingWrites、fun close、val recommendHorizontalOnly |
| `LibraryAccountCache.kt` | 缓存身份：登录账号优先；未登录但设备已注册时，用主设备身份隔离匿名缓存。 | fun Library.cacheIdentityKeyImpl、fun Library.cacheIdentityAvailableImpl、fun Library.accountCacheImpl、fun Library.cacheAccountImpl、fun Library.clearAccountCacheImpl |
| `LibraryAccountCredentials.kt` | 账号参数（个人信息页导入的整套登录态）。 | fun Library.accountImpl、fun Library.saveAccountImpl、fun Library.clearAccountImpl |
| `LibraryAccountCredentialsFacade.kt` | 账号参数（个人信息页导入的整套登录态）。 | fun Library.account、fun Library.saveAccount、fun Library.clearAccount、fun Library.accountDevice、fun Library.saveAccountDevice |
| `LibraryAccountDevice.kt` | 设备档案：**只能从设备上读**的那批参数（device_id / 机型 / 系统版本 / cdid …）。 | fun Library.accountDeviceImpl、fun Library.saveAccountDeviceImpl |
| `LibraryAccountFacade.kt` | 共享数据层、Library、缓存、网络与资源加载。文件 LibraryAccountFacade 主要包含 fun Library.accountCache、fun Library.cacheAccount、fun Library.clearAccountCache。 | fun Library.accountCache、fun Library.cacheAccount、fun Library.clearAccountCache |
| `LibraryCacheStores.kt` | 共享数据层、Library、缓存、网络与资源加载。文件 LibraryCacheStores 主要包含 class LibraryHomeCacheStore、class LibraryAccountCacheStore。 | class LibraryHomeCacheStore、class LibraryAccountCacheStore |
| `LibraryConsentFacade.kt` | 用户有没有确认过「第三方客户端」的使用须知。 | var Library.thirdPartyAccepted、fun Library.qualityChosenByUser |
| `LibraryDeviceIdentity.kt` | 本机设备身份：device_id 与 iid。 | fun Library.deviceIdentityImpl、fun Library.deviceRegisteredImpl、fun Library.registrationNeedsRefreshImpl、fun Library.profileStampImpl、fun Library.invalidateRegistrationImpl、fun Library.saveDeviceIdentityImpl |
| `LibraryDeviceProfile.kt` | 本机真机设备档案。 | fun Library.deviceProfileImpl、fun Library.buildOfficialProfileImpl、fun Library.readAndroidIdImpl |
| `LibraryIdentityLayout.kt` | 一次性把旧版设备身份归档，并清空当前主设备身份。 | fun Library.ensureIdentityLayoutImpl |
| `LibraryMirrorRestore.kt` | 把外部镜像里本机缺的项补回会话偏好。 | fun Library.restoreFromMirrorImpl、fun Library.retryMirrorRestoreImpl、fun Library.mirrorSyncImpl |
| `LibraryPendingProfile.kt` | 「换新身份」用的**候选档案**，以及电视注册时使用的统一档案。 | fun Library.pendingProfileImpl、fun Library.storePendingProfileImpl、fun Library.commitPendingProfileImpl、fun Library.registrationProfileImpl、fun Library.hasPendingProfileImpl |
| `LibraryPendingProfileFacade.kt` | 注册候选档案转正与注册档案选择门面。 | fun Library.commitPendingProfile、fun Library.registrationProfile、fun Library.hasPendingProfile |
| `LibraryPlaybackSettings.kt` | 共享数据层、Library、缓存、网络与资源加载。文件 LibraryPlaybackSettings 主要包含 fun Library.autoNextSetting、fun Library.setAutoNextSetting、fun Library.playbackSpeedSetting、fun Library.setPlaybackSpeedSetting。 | fun Library.autoNextSetting、fun Library.setAutoNextSetting、fun Library.playbackSpeedSetting、fun Library.setPlaybackSpeedSetting、fun Library.frameModeSetting、fun Library.setFrameModeSetting |
| `LibraryPreferenceCodec.kt` | 共享数据层、Library、缓存、网络与资源加载。文件 LibraryPreferenceCodec 主要包含 object LibraryPreferenceCodec。 | object LibraryPreferenceCodec |
| `LibraryRegistrationCookieFacade.kt` | 设备注册下发的 Cookie（install_id / ttreq / store-region …）。 | fun Library.registrationCookies、fun Library.clearRegistrationCookies、fun Library.saveRegistrationCookies |
| `LibraryRegistrationCookies.kt` | 设备注册下发的 Cookie（install_id / ttreq / store-region …）。 | fun Library.registrationCookiesImpl、fun Library.clearRegistrationCookiesImpl、fun Library.saveRegistrationCookiesImpl |
| `LibraryRegistrationFacade.kt` | 这份注册结果是不是**当前版本注册出来的**（缺 registered-profile-stamp = 不是）。 | fun Library.registrationNeedsRefresh、fun Library.invalidateRegistration、fun Library.markRegistrationPending、fun Library.clearRegistrationPending、fun Library.registrationPending |
| `LibraryRegistrationState.kt` | 这一次「换新身份」的注册失败了，下次启动 / 下次取播放地址前再试。 | fun Library.markRegistrationPendingImpl、fun Library.clearRegistrationPendingImpl、fun Library.registrationPendingImpl、fun Library.freshDeviceProfileImpl |
| `LibrarySearchTokenFacade.kt` | 搜索登录态（可选，App 抓到的 x-tt-token，整段 Cookie 也可以）。 | var Library.searchToken |
| `LibrarySyncFacade.kt` | 导出局域网全量同步允许迁移的设备身份与登录会话。 | fun Library.exportSyncSession、fun Library.importSyncSession |
| `LibrarySyncSession.kt` | 局域网全量同步时允许跨设备的会话键白名单。 | object LibrarySyncSessionKeys、fun Library.exportSyncSessionImpl、fun Library.importSyncSessionImpl |
| `LocalReset.kt` | 本机数据的「恢复出厂」：把应用自己留下的所有东西删干净。 | object LocalReset、val hasMultiVolumeDirs |
| `NavKeys.kt` | 顶部导航栏里可以单独隐藏的入口 key。 | val NAV_KEY_RECOMMEND、val NAV_KEYS |
| `PlaybackCapability.kt` | 这台设备能解什么。 | object PlaybackCapability |
| `PlaybackTransport.kt` | 播放链路（取地址 / 预热 / 分片读视频）共用的传输层。 | object PlaybackTransport |
| `ProgressStore.kt` | 观看进度的落盘：**一剧一文件**。 | class ProgressStore |
| `SessionBootstrap.kt` | 会话装配；会话存储可先初始化，心跳客户端按首帧后延后创建。 | object SessionBootstrap、fun initStore、fun startKeeper |
| `SessionEvents.kt` | 掉线通知。 | object SessionEvents |
| `SessionMirror.kt` | 设备身份与登录会话的**镜像**（同一份 JSON，沙盒 files/、/sdcard/.hgtv/、Android/data/ / 各存一份）。 | class SessionMirror |

#### `shared/src/main/kotlin/com/hgtv/star/shared/debug/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DebugFaults.kt` | **调试包专用**的故障注入：把「解码失败」这类只能等用户碰上的事，变成可以按需复现的一条命令。 | object DebugFaults |
| `DebugLog.kt` | 调试模式的「近况」环形缓冲：两端共用。 | val DEBUG_LOG_LIMIT、object DebugLog |
| `Diagnostics.kt` | 全站诊断的唯一出口。 | object Diagnostics、class DiagnosticsException、var debugSink、fun versionName、fun devicePrefix |
| `NetSpeed.kt` | 调试面板上的「网速」一行：本进程**当前**的收发速率（↓1.2M/s ↑8K/s）。 | object NetSpeed |
| `PerfStats.kt` | 调试面板上的「性能」一行：CPU / GPU / 内存 / 线程 / 温度 / 刷新率。 | object PerfStats |
| `StartupTrace.kt` | 启动与首帧追踪基础设施：阶段预算、首帧后任务、主线程 latest 调度和统一 launch id 链路。所有关键日志使用 TAG 与 Log.w，便于 release 现场排查。 | object StartupTrace、object StartupBudgets、object StartupDeferredTasks、object PlaybackStartupTrace |
| `TlsSummary.kt` | 把 TLS 能力那一行压成面板上放得下的一句。 | fun compactTls |

#### `shared/src/main/kotlin/com/hgtv/star/shared/feed/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PreloadScheduler.kt` | 推荐流「相邻预热」的**排程编排**：电视与手机共用这一份。 | class PreloadScheduler |
| `RecommendFeedEngine.kt` | 推荐流的加载、续流、降级、重排和快照落盘；TV 横屏偏好只传给服务端，不在客户端过滤卡片。 | class RecommendFeedEngine、class RecommendFeedPolicy、class RecommendFeedInput、class RecommendFeedResult |
| `RecommendStore.kt` | 推荐流的页面无关状态；按横屏/默认请求模式隔离续流游标，并在条件变化时支持重置。 | class RecommendStore |
| `RecommendWarmCoordinator.kt` | 推荐预热的数据链：详情 → 单集直连地址 → 索引 / 首块；预热详情不补作者，避免占用首帧路径。 | class RecommendWarmCoordinator |

#### `shared/src/main/kotlin/com/hgtv/star/shared/lan/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DeviceSyncPackage.kt` | 局域网同步基础能力。文件 DeviceSyncPackage 主要包含 class DeviceSyncSelection、class DeviceSyncPreferences、class DeviceSyncPayload、object DeviceSyncPackage。 | class DeviceSyncSelection、class DeviceSyncPreferences、class DeviceSyncPayload、object DeviceSyncPackage |
| `LanLibrarySync.kt` | 通过 Android NSD 发现同一局域网里打开了「同步其他设备数据」的 HGTV。 | class LanLibrarySyncSession、class LanSyncPeer、fun Context.lanIpv4Address |

#### `shared/src/main/kotlin/com/hgtv/star/shared/media/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DanmakuEmojiStore.kt` | 弹幕表情图的解码与缓存。 | class DanmakuEmojiStore |
| `DanmakuLoader.kt` | 「这一集拉到哪一步」的弹幕加载内核（电视端与手机端共用一份）。 | class DanmakuLoader |
| `DanmakuSpriteAtlas.kt` | 弹幕文字图集。 | class DanmakuSpriteAtlas |
| `DanmakuView.kt` | 弹幕绘制 View，负责轨道布局、滚动、高亮聚合、表情、绘制缓存和播放器黑边底层绘制；TV 与手机竖屏追加 16dp 顶部呼吸位，手机横屏保持原位置；推荐流可把首行文字实际顶边锚定到顶部导航。 | class DanmakuView、val topGapDp、val topTextAnchorPx |
| `MediaSessionHost.kt` | 前台媒体会话：让**遥控器 / 耳机线控 / 锁屏与系统媒体面板**上的播放键能找到本应用。 | class MediaSessionHost |
| `PlaybackFailures.kt` | 一次播放失败的**归因**：这条错误落在哪条轨上、该按哪一档、哪些档位还能换。 | object PlaybackFailures |
| `PlaybackMetrics.kt` | 播放现场的「解码事实」：解码器名、初始化耗时、编码格式、丢帧、音频欠载；首帧同时写入统一启动追踪。 | class PlaybackMetrics |
| `PlayerSourceSwap.kt` | 换媒体源：**先停、再挂**。 | fun ExoPlayer.swapMediaSource |
| `VideoDataSource.kt` | 共享媒体、弹幕、视频数据源与播放失败解析。文件 VideoDataSource 主要包含 class VideoDataSource。 | class VideoDataSource |

#### `shared/src/main/kotlin/com/hgtv/star/shared/nav/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `Navigator.kt` | 页面跳转的唯一入口。 | interface Navigator |
| `Route.kt` | 页面清单：两端「有哪些页面、每个页面要什么参数」的唯一来源。 | interface Route |

#### `shared/src/main/kotlin/com/hgtv/star/shared/search/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `VoiceQuery.kt` | 语音搜索里与 Android 相关的那一半（文本归一化在 core 的 com.hgtv.star.core.VoiceQuery）。 | object VoiceSearch |

#### `shared/src/main/kotlin/com/hgtv/star/shared/ui/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AvatarColor.kt` | 作者头像的底色：按名字在这几个深色里挑一个。 | fun authorColor、val AVATAR_PALETTE |
| `DialogLayout.kt` | 弹窗尺寸预算：TV 与手机共用同一套「屏幕占比 + 固定开销 + 可滚动区」算法。 | object DialogLayout |
| `LabelChip.kt` | 系统标签（新剧 / 爆剧…）的底色。 | fun labelBackground、fun cornerRadii |
| `LoadingDotsView.kt` | 全站统一的三点加载动画。 | class LoadingDotsView |
| `PosterCardMaterial.kt` | 海报卡的唯一材质基准，以电视端为准。 | object PosterCardMaterial |
| `PosterCardView.kt` | 电视与手机共用的竖版海报卡骨架。 | class PosterCardView、class PosterWell、class PosterProgressView |
| `SliderDrag.kt` | 横向滑杆的触摸状态机：**按下 → 越过 slop 判定拖动 / 轻点 → 抬起或取消**。 | class SliderDrag |
| `Soft.kt` | 新拟态的底板形态。 | class SoftStyle、object Soft、class SoftDrawable |
| `SoftPalette.kt` | 柔光位图要用到的四个颜色 + 主题标识。 | object SoftPalette |
| `TvTheme.kt` | 主题：二十套新拟态配色（设置 → 主题外观）。 | class TvTheme、fun mix、fun alpha、object TvThemeStore |

#### `shared/src/test/kotlin/com/hgtv/star/shared/debug/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `TlsSummaryTest.kt` | TLS 读数压缩的回归测试。 | class TlsSummaryTest |

#### `shared/src/test/kotlin/com/hgtv/star/shared/nav/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `RouteTest.kt` | 页面清单的回归测试。 | class RouteTest |

#### `shared/src/test/kotlin/com/hgtv/star/shared/ui/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AvatarColorTest.kt` | 头像底色的回归测试。 | class AvatarColorTest |
| `SliderDragTest.kt` | 滑杆触摸状态机的回归测试。 | class SliderDragTest |

#### `shared/src/main/kotlin/com/hgtv/star/shared/feature/account/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `AccountController.kt` | 账号侧的唯一状态与写入入口。 | class AccountController |

#### `shared/src/main/kotlin/com/hgtv/star/shared/feature/catalog/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `EpisodeGridState.kt` | 选集网格的纯状态：页大小、当前页、范围文案与翻页边界。 | class EpisodeGridState |

#### `shared/src/main/kotlin/com/hgtv/star/shared/feature/detail/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `DetailController.kt` | 详情页的行为层。 | class DetailController |

#### `shared/src/main/kotlin/com/hgtv/star/shared/feature/lan/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `LanSyncController.kt` | 局域网同步的完整行为层。 | class LanSyncController |

#### `shared/src/main/kotlin/com/hgtv/star/shared/feature/player/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `PlaybackCoordinator.kt` | 平台播放器需要暴露给共享编排层的最小能力。 | interface PlayerHost、class PlaybackCoordinator |

#### `shared/src/main/kotlin/com/hgtv/star/shared/feature/recommend/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `RecommendPlaybackController.kt` | 推荐流中“选一部并准备播放”的共享行为；推荐地址走单集直连，首播详情跳过作者查询，并对详情、地址、准备阶段执行预算。 | class RecommendPlaybackController |

#### `shared/src/main/kotlin/com/hgtv/star/shared/feature/search/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `SearchController.kt` | 搜索页的行为层。 | class SearchController |

#### `shared/src/main/kotlin/com/hgtv/star/shared/feature/settings/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `SettingRowSpec.kt` | 设置页的行结构。 | interface SettingRowSpec |
| `SettingsController.kt` | 设置页的共享读写入口。 | class SettingsController |

### gradle

#### `gradle/wrapper/`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `gradle-wrapper.properties` | Gradle 或模块属性配置。 | properties |

### root/config

#### `./`

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `build.gradle.kts` | Gradle Kotlin DSL 构建或设置脚本。 | gradle |
| `gradle.properties` | Gradle 或模块属性配置。 | properties |
| `settings.gradle.kts` | Gradle Kotlin DSL 构建或设置脚本。 | gradle |

<!-- generated-files: 649 -->
