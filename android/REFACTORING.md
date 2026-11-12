# Android 行为等价重构记录

> 基线提交：`140665f89ea816e4a38da50d3aa9428ecb722ead`
> 重构分支：`refactor/from-140665f`
> 最后更新：2026-11-13

本文档用于记录 Android 行为等价重构的基线、批次边界和验证结果。现有根目录
`docs/` 被 `.gitignore` 整体忽略，不能作为可回退的提交记录。

## 硬约束

- 不改变 UI、布局、资源、导航、交互、权限或可见文案。
- 不改变网络、缓存、数据库、序列化、路由、埋点、日志语义、线程或生命周期行为。
- 不升级依赖、Gradle、数据库 schema 或混淆规则。
- 不修改版本号和 versionCode；不推送、不创建 Release。
- 保留公开类名、资源 ID、XML ID 和方法签名。
- 保留 `baseline-prof.txt` 引用的文件级 JVM facade。
- `CodecProbe` 反射依赖 `ContentRepository.videoModel(1)` 和 `ContentRepository.text(2)`，不得改名或改变参数数量。

## 基线验证

构建必须使用 JDK 17；本机默认 JDK 26 会在 Gradle 启动阶段失败。

```bash
cd android
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew --console=plain \
  --continue :core:test :shared:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

基线结果：

- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无 `Error` / `Fatal`，仍有现有 Warning。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 435 例中 25 失败、28 跳过，基线本身为红。
- 基线失败主要位于 `ContentRepositoryTest`、`PlaybackCacheTest`、
  `SessionInterceptorTest`、`SignerTest`。
- 后续批次不得新增失败；当前比较基准是 25 个失败、28 个跳过。

## 当前大文件

生产 Kotlin 中仍有 6 个文件超过 2000 行：

| 文件 | 基线行数 |
| --- | ---: |
| `core/.../content/ContentRepository.kt` | 6041 |
| `feature-phone/.../PhonePlayerActivity.kt` | 4735 |
| `feature-tv/.../MainActivity.kt` | 2821 |
| `feature-phone/.../PhoneFeedView.kt` | 2387 |
| `feature-tv/.../player/PlaybackController.kt` | 2232 |
| `shared/.../Library.kt` | 2027 |

## 批次记录

### 批次 1：内容模型与目录整理

状态：已完成。

- 将 `ContentRepository.kt` 移到 `core/.../content/`，Kotlin 包名保持不变。
- 将内容模型原样移到 `model/ContentModels.kt`，公开类型全限定名不变。
- `CATALOG_FIRST_PAGE_SIZE` 和文件级顶层工具函数保留在原文件，以维持
  `ContentRepositoryKt` facade。
- `ContentRepository.kt` 从 6041 行降到 5816 行；`model/ContentModels.kt` 为 235 行。

验证：

- `git diff --check` 通过。
- 模型声明与基线逐行比较，除有意保留的 `CATALOG_FIRST_PAGE_SIZE` 外一致。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 通过。
- `:core:test` 仍为 435 例、25 失败、28 跳过，与基线一致。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 2：榜单、分类和推荐纯解析

状态：已完成。

- 新增 `ContentCatalogParser`，集中榜单名次、分类卡片、分类筛选面板和官方推荐卡片解析。
- 标签、季号、热度、作者和 JSON 字段读取保持原实现，仅迁移到同包内部对象。
- `publisher`、`tagsOf`、`heatValue` 保留原公开方法签名，内部委托新解析器。
- 网络请求、分页 offset/limit、游标推进、重试、缓存和并发逻辑未移动。
- `ContentRepository.kt` 从 5816 行降到 5473 行；`catalog/CatalogParser.kt` 为 400 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 3：App / 官网搜索纯解析

状态：已完成。

- 新增 `ContentSearchParser`，集中联想词、官网搜索结果、搜索 tab、卡片与标题解析。
- 搜索网络发送、空响应重试、游标、并发、官网降级、诊断和设备自愈仍留在原类。
- 搜索 tab 数据结构改为内部解析器中的私有数据类，不暴露公开 API。
- `ContentRepository.kt` 从 5473 行降到 5335 行；`search/SearchParser.kt` 为 195 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 4：账号传输与请求体

状态：已完成。

- 新增 `ContentAccountTransport`，封装账号请求的签名、会话头、空响应判断和空体重试。
- 新增 `ContentAccountRequests`，集中预约、收藏、进度上传和历史删除请求体。
- 公开账号方法、重试次数、退避顺序、日志、会话校验和调用时序仍留在原类。
- 历史删除的 `clock()` 仍按每条记录调用，没有改成整批共用时间。
- `ContentRepository.kt` 从 5335 行降到 5268 行；`account/AccountApi.kt` 为 174 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 5：详情与发行方解析

状态：已完成。

- 新增 `ContentDetailParser`，集中详情响应、相关作品 cell、发行方 tab 与卡片解析。
- 详情空响应回退、签名、缓存写入、作者补查时机、相关作品换线重试和作者作品并发仍留在原类。
- `RelatedEntry` 移到同包内部类型，公开 API 不变。
- `ContentRepository.kt` 从 5268 行降到 5184 行；`detail/DetailParser.kt` 为 101 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 6：播放模型与取流解析

状态：已完成。

- 新增 `ContentPlaybackParser`，集中播放模型请求体、模型响应、批量模型和 `video_list` 选流解析。
- 单飞闸门、模型缓存、身份代次、设备档位、备用设备、预算、重试和失败恢复仍留在原类。
- `videoModel` 反射入口、`text` 反射入口和缓存键均未改变。
- 弹幕继续使用原有独立行解析，请求窗口与频控循环未移动。
- `ContentRepository.kt` 从 5184 行降到 5023 行；`playback/PlaybackParser.kt` 为 169 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 7：Library 首页与账号缓存

状态：已完成。

- 新增 `LibraryHomeCacheStore`，承接首页榜单内存快照、过期判断、截断和写队列提交。
- 新增 `LibraryAccountCacheStore`，承接账号快照、指纹隔离、退出后失效和写队列提交。
- SharedPreferences 文件名、键、JSON 格式、7 天首页有效期、写队列和加载时机保持不变。
- `Library.loadAsync`、`state()` 前置校验、主线程回调和磁盘迁移仍在原类。
- `Library.kt` 从 2027 行降到 1984 行；`LibraryCacheStores.kt` 为 102 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 8：Library 偏好编解码

状态：已完成。

- 新增 `LibraryPreferenceCodec`，统一导航 / 首页隐藏项、推荐游标、推荐范围、受众和设备形态的编解码。
- 白名单过滤、逗号分隔格式、推荐体裁固定顺序、游标长度上限和控制字符过滤保持不变。
- SharedPreferences 键、默认值、写入队列、会话镜像、设备注册和启动迁移均未移动。
- `Library.kt` 仍为 1984 行；`LibraryPreferenceCodec.kt` 为 26 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 9：PhoneFeed 列表机制

状态：已完成。

- 新增 `PhoneFeedPager.kt`，独立承载竖向手势方向锁和 `PageHolder`。
- 手势判定阈值、主轴偏向规则、RecyclerView 生命周期和 ViewHolder 行为保持不变。
- `FeedAdapter` 直接操作播放器、推荐计时、页面绑定和私有 View 状态；本批没有为搬移它而扩大可见性或引入宽接口。
- `PhoneFeedView.kt` 从 2387 行降到 2342 行；`PhoneFeedPager.kt` 为 50 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 10：PhonePlayer 窗口与视图辅助

状态：已完成。

- 新增 `PhonePlayerViewSupport`，承接横屏 chip、间距、进度条染色、方向申请、自动旋转、画面模式和常亮标志。
- 横屏方向异常日志、API 21 染色保护、画面模式和推荐页 Surface 联动保持不变。
- 播放器、手势、生命周期、设置面板、选集和弹幕时钟仍留在原 Activity。
- `PhonePlayerActivity.kt` 从 4735 行降到 4703 行；`PhonePlayerViewSupport.kt` 为 87 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 11：PhonePlayer 选集面板

状态：已完成。

- 新增 `PhonePlayerEpisodePanels`，承接竖屏 5 列与横屏 10 列选集浮层。
- 每页集数、网格高度、边缘翻页、滑动分页、横屏侧边距和 TV 样式保持不变。
- 点击顺序仍是先关闭浮层，再在目标集不同时调用原换集路径。
- `PhonePlayerActivity.kt` 从 4703 行降到 4667 行；`PhonePlayerEpisodePanels.kt` 为 88 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 12：PhonePlayer 横屏设置抽屉

状态：已完成。

- 新增 `PhonePlayerSettingsPanel`，承接横屏右侧 40% 抽屉、左侧收起区、标题、滚动容器和背景。
- 宽度下限、圆角、描边、点击穿透防护、内容构建回调和收起顺序保持不变。
- 具体设置行、倍速、清晰度、弹幕、收藏、定时和即时应用仍留在原 Activity。
- `PhonePlayerActivity.kt` 从 4667 行降到 4605 行；`PhonePlayerSettingsPanel.kt` 为 88 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 13：TV 播放失败标签

状态：已完成。

- 新增 `PlaybackFailureLabels`，集中网络原因、错误短码和短原因提取。
- 异常类型优先级、cause 深度上限 8、消息长度上限和稳定短码保持不变。
- `playbackFailureTag`、`playbackFailureReason`、`networkFailureOf` 保留原顶层包装，
  因此 `MainActivityPlaybackKt` facade 不变。
- `player/PlaybackController.kt` 从 2232 行降到 2175 行；`PlaybackFailureLabels.kt` 为 50 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 14：TV RecommendWarm

状态：已完成。

- 将顶层 `RecommendWarm` 移到独立文件，类全限定名和公开构造保持不变。
- 取消域、启动时刻和 future 字段语义保持不变。
- `MainActivity.GridRow` 被其他文件以嵌套类型引用，本批没有改变其结构。
- `MainActivity.kt` 从 2821 行降到 2808 行；`RecommendWarm.kt` 为 15 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 15：TV 播放中控大块拆分

状态：已完成。

- `player/PlaybackController.kt` 从 2175 行拆为四个职责文件。
- 使用 `@JvmMultifileClass` 保留 `MainActivityPlaybackKt` 静态 facade。
- 队列、预取、选集推进：`player/PlaybackController.kt`，770 行。
- 网络恢复、失败归因、自动重试与错误页：`player/PlaybackRecovery.kt`，709 行。
- HUD、媒体会话、播放控制与睡眠停止：`player/PlaybackHud.kt`，301 行。
- 画面几何、清晰度/解码/弹幕选择器与 seek：`player/PlaybackGeometry.kt`，489 行。
- 只将跨文件使用的 `resetBudgetsForManualRetry` 从 private 调整为 internal，行为未变。
- 编译产物已确认仍包含 `MainActivityPlaybackKt` 和 `MainActivityPlaybackKt$WhenMappings`。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 16：ContentRepository 账号模块大块迁移

状态：已完成。

- 将账号资料、收藏、历史、点赞、预约、写接口、清空历史和账号聚合整体移到
  `account/AccountService.kt`。
- 公开账号方法保留在 `ContentRepository` 上，只委托到内部扩展实现，调用方签名不变。
- 账号传输、重试、退避、日志、写队列、历史分片和并发装配保持原实现。
- 为跨文件扩展开放了账号相关内部成员，均使用 `internal`，没有扩大公开 API。
- `ContentRepository.kt` 从 5023 行降到 4629 行；`account/AccountService.kt` 为 417 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 17：ContentRepository 详情与作者作品迁移

状态：已完成。

- 将详情、相关作品、作者作品编排、发行方作品探测、补季和作者补齐整体移到
  `detail/DetailService.kt`。
- `detail`、`related`、`authorWorks` 仍保留在 `ContentRepository` 上，公开签名和
  委托顺序不变。
- 详情空响应改 GET 重试、详情缓存写入、相关作品缓存、作者作品并发探测、分页重试、
  补季并发、超时和异常吞掉范围均按原实现迁移。
- 为跨文件扩展开放了详情/作者链路所需的 `internal` 成员；没有增加新的公开 API。
- `ContentRepository.kt` 从 4629 行降到 3955 行；`detail/DetailService.kt` 为 695 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- 聚合 `lintDebug` 仍被 `shared`、`feature-phone`、`feature-tv` 既有
  `MissingPermission` 错误阻断，本批未改动这些文件。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过；`HEAD` 基线为 25 失败、28 跳过，
  没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 18：ContentRepository 榜单与分类链路迁移

状态：已完成。

- 将榜单取数、分类分页、分类 offset/limit、分类请求签名、空响应换线路、备用设备
  重试和分类 URL 移到 `catalog/CatalogService.kt`。
- `rankRaw`、`rank`、`homeFeed`、`category`、`categoryFeed` 仍保留在
  `ContentRepository`，公开签名、默认参数和返回结构不变。
- 分类推荐流的 `has_more` 补偿、首屏/后续页重试次数、429 与 4xx 分支、日志和线程中断
  处理均按原实现迁移。
- 只将跨文件扩展所需的成员调整为 `internal`，没有新增公开 API。
- `ContentRepository.kt` 从 3955 行降到 3727 行；`catalog/CatalogService.kt` 为 235 行。

验证：

- `git diff --check` 通过。
- `:core:compileKotlin` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，与上一批一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 19：ContentRepository 推荐流与曝光上报迁移

状态：已完成。

- 将推荐流取数、官方推荐请求、推荐查询参数、推荐曝光上报、上报配置刷新和推荐会话头
  移到 `recommend/RecommendService.kt`。
- `recommendFeed` 和 `reportRecommendStats` 仍保留为 `ContentRepository` 的接口实现，
  只委托到内部扩展实现。
- 推荐游标前进规则、官方 session UUID、配置刷新锁、上报重试与异常路径均保持原实现。
- `ContentRepository.kt` 从 3727 行降到 3360 行；`recommend/RecommendService.kt` 为 393 行。

验证：

- `git diff --check` 通过。
- `:core:compileKotlin` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 20：ContentRepository 搜索链路迁移

状态：已完成。

- 将联想词、热搜词、App 综合搜索、官网搜索、搜索游标、空响应重试、设备自愈和官网降级
  移到 `search/SearchService.kt`。
- `suggestions`、`webSuggestions`、`hotSearches`、两个 `search` 入口和 `webSearch`
  仍保留在 `ContentRepository`，公开签名与默认参数不变。
- 搜索页并发、官网物理页重试、ThreadLocal 失败摘要、游标过期、诊断记录和取消传播
  均按原实现迁移。
- `ContentRepository.kt` 从 3360 行降到 2980 行；`search/SearchService.kt` 为 444 行。

验证：

- `git diff --check` 通过。
- `:core:compileKotlin` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 21：ContentRepository 播放模型与预取迁移

状态：已完成。

- 将播放模型探测、批量模型、模型缓存、设备档位重试、备用设备、进程级单飞闸门、
  请求节流、预取和地址预算 client 移到 `playback/PlaybackModelService.kt`。
- `videoModel` 仍保留为 `ContentRepository` 上的同名方法，参数数量不变，
  `CodecProbe` 的反射入口不被破坏。
- `prefetchModels` 保留公开签名；模型缓存键、预算、失败顺序、空响应升级和备用设备
  时序均按原实现迁移。
- `ContentRepository.kt` 从 2980 行降到 2397 行；`playback/PlaybackModelService.kt`
  为 621 行。

验证：

- `git diff --check` 通过。
- `:core:compileKotlin` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 22：ContentRepository 取流缓存迁移

状态：已完成。

- 将取流、内存/落盘地址缓存、取流锁、缓存失效和重试入口移到
  `playback/StreamCacheService.kt`。
- `stream`、`forgetStream`、`forgetStreamAllQualities`、`rotateDeviceForRetry`
  仍保留原公开签名。
- 地址预算、缓存键、锁粒度、超时转换、落盘窗口和 `device_level` 语义均按原实现迁移。
- `ContentRepository.kt` 从 2397 行降到 2313 行；`playback/StreamCacheService.kt` 为 109 行。

验证：

- `git diff --check` 通过。
- `:core:compileKotlin` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 23：ContentRepository 弹幕与网络/设备身份迁移

状态：已完成。

- 将弹幕窗口、频控、线路切换和原始窗口读取移到 `danmaku/DanmakuService.kt`。
- 将连接预热、签名预热、设备注册、TNC 请求和响应解析移到
  `network/NetworkBootstrapService.kt`。
- `danmaku`、`danmakuRawWindow`、`preconnect`、`warmSignature`、`registerDevice`、
  `registerAnonymousDevice`、`refreshTncAsync` 均保留原公开签名。
- `text` 反射入口暂留原文件，避免改变 `CodecProbe` 依赖的声明形态。
- 弹幕窗口上限、频控间隔、TNC 超时、注册匿名头、Cookie 覆盖和日志语义保持不变。
- `ContentRepository.kt` 从 2248 行降到 1959 行；`danmaku/DanmakuService.kt` 为 99 行，
  `network/NetworkBootstrapService.kt` 为 313 行。

验证：

- `git diff --check` 通过。
- `:core:compileKotlin` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 24：core/content 按业务目录与职责命名重组

状态：已完成。

- 将内容实现按 `account / catalog / detail / recommend / search / playback /
  danmaku / network / model` 分目录。
- 实现文件去掉 `ContentRepositoryXxx` 前缀，改为 `AccountService`、`DetailService`、
  `CatalogService`、`RecommendService`、`SearchService`、`PlaybackModelService`、
  `StreamCacheService`、`DanmakuService`、`NetworkBootstrapService`。
- 解析器改为 `AccountApi`、`CatalogParser`、`DetailParser`、`SearchParser`、
  `PlaybackParser`、`ContentModels`，目录即职责。
- Kotlin 包名保持 `com.hgtv.star.core`，避免改变 JVM 类型名、扩展函数名和调用点。
- 仅移动/重命名文件，代码内容、公开 API、资源、Manifest 和行为不变。

验证：

- `git diff --check` 通过。
- `:core:compileKotlin` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 25：TV 主入口类与文件重命名

状态：已完成。

- 将 `feature-tv` 主入口从 `MainActivity` 重命名为 `TvMainActivity`，文件改为
  `TvMainActivity.kt`，与 `PhoneMainActivity` 形成对称命名。
- 同步电视端扩展函数、显式类型引用、启动路由、baseline profile 和 Manifest。
- Manifest 新增旧组件名 `.tv.MainActivity` 的 `activity-alias`，目标指向
  `.tv.TvMainActivity`，保留已有外部显式 Intent 的兼容性。
- 未修改窗口方向、主题、启动模式、配置变更、导航目标和可见行为。
- 当前 `TvMainActivity.kt` 仍为 2808 行，本轮只完成命名和引用切换，下一步继续按页面职责拆分。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。
- 合并后的 Debug Manifest 已确认同时包含 `TvMainActivity` 与旧 `MainActivity` alias。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 26：TV 页面扩展文件按业务分目录

状态：已完成。

- 将 27 个 `MainActivityXxx.kt` 扩展文件移入 `account / home / detail / search /
  player / settings / input / navigation / system / library`。
- 文件名改为职责名，例如 `AccountPanel.kt`、`HomePage.kt`、`DetailPage.kt`、
  `SearchPage.kt`、`PlayerController.kt`、`SettingsPage.kt`、`TouchController.kt`。
- 播放多文件 facade 继续使用原有 `@file:JvmName("MainActivityPlaybackKt")` 和
  `@file:JvmMultifileClass`，baseline profile 中的 `MainActivityPlaybackKt` 保持不变。
- 其他扩展文件的 baseline facade 名已同步到新的职责文件名。
- Kotlin 包名仍为 `com.hgtv.star.tv`，没有改变扩展函数的 JVM 调用关系。
- 仅移动/重命名文件并更新注释引用，没有修改 UI、生命周期、焦点、播放或网络行为。

验证：

- `git diff --check` 通过。
- `:feature-tv:compileDebugKotlin` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 27：TV 页头收展控制器迁移

状态：已完成。

- 将滚动位置记忆、页头登记、页头高度测量、收起/展开动画、焦点判据和列表到底判断
  移到 `navigation/HeaderVisibilityController.kt`。
- `TvMainActivity` 保留 `rememberScroll`、`registerTopAreaRow`、`onListScroll`、
  `syncHeaderState`、`headerShouldHide`、`gridAtBottom`、`setTopAreaHidden` 同名门面方法。
- 动画时长、低配分支、静默期、焦点例外、封面恢复和预取触发顺序保持不变。
- `TvMainActivity.kt` 从 2808 行降到 2662 行；`HeaderVisibilityController.kt` 为 199 行。

验证：

- `git diff --check` 通过。
- `:feature-tv:compileDebugKotlin` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 28：TV 内存压力处理迁移

状态：已完成。

- 将封面摘挂、`onLowMemory` 处理、`onTrimMemory` 分级清理和推荐缓存收缩移到
  `system/MemoryPressureController.kt`。
- `TvMainActivity` 保留 `releaseOffscreenCovers`、`resumeVisibleCovers` 门面方法，
  系统回调仍由 Activity 本体保留并按原顺序调用 `super`。
- 新增只读 `artworkReady` 投影，用于跨文件判断 `lateinit artwork` 是否已初始化，
  不改变初始化时机。
- `TvMainActivity.kt` 从 2662 行降到 2611 行；`MemoryPressureController.kt` 为 89 行。

验证：

- `git diff --check` 通过。
- `:feature-tv:compileDebugKotlin` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 29：TV 调试面板状态迁移

状态：已完成。

- 将调试面板的视图引用、行缓存、刷新节流、最近问题与解码能力状态移到
  `debug/TvDebugOverlayState.kt`。
- `TvMainActivity` 保留原有属性访问器并委托到内部状态，`DebugOverlay.kt` 的刷新、
  采样、布局、日志与节流实现未改。
- `TvMainActivity.kt` 从 2611 行变为 2632 行；新增状态容器 26 行。本批只迁移状态归属，
  文件行数暂未下降。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

## 下一批

继续拆 `TvMainActivity.kt` 的生命周期初始化辅助，再评估把首页/详情/播放页面状态聚合
到独立 State 对象的边界。
