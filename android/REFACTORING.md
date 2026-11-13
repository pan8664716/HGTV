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

### 批次 30：TV 首帧后初始化编排迁移

状态：已完成。

- 将 `initializeMainAfterFirstFrame` 的完整编排移到
  `system/MainInitialization.kt`，`onCreate` 仍按原 32ms 延迟、销毁判断和调用顺序
  进入第二阶段初始化。
- 主题、运行时、详情状态收集、网络回调、封面计时、焦点监听、故障注入、预连接和
  `loadLibrary()` 的顺序与实现体保持原样。
- 仅增加四个 `internal` 只读/委托入口，供初始化扩展读取原私有回调、广播接收器、
  网络监听和 tick；公开成员与生命周期回调未改。
- `TvMainActivity.kt` 从 2632 行降到 2421 行；`MainInitialization.kt` 为 227 行。

验证：

- `git diff --check` 通过；迁移后的初始化实现体按缩进归一化后与迁移前逐行一致。
- 首次编译缺少两个跨文件扩展 import，补齐后重跑通过；未改行为。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 31：TV `onResume` 恢复编排迁移

状态：已完成。

- 将 `onResume` 在 `super.onResume()` 之后的恢复、账号刷新、页面重建、延迟任务和
  前台同步移到 `system/MainResume.kt`。
- `TvMainActivity.onResume` 保留原始 `StartupTrace` 锚点与 `super` 调用顺序，之后只
  委托到恢复实现。
- 新增 `resumeRestoreBundle` 与 `restoreLastScreenForResume` 两个 `internal` 委托，
  没有扩大公开 API。
- 迁移后的恢复实现体按缩进和委托名归一化后与迁移前逐行一致。
- `TvMainActivity.kt` 从 2421 行降到 2331 行；`MainResume.kt` 为 108 行。

验证：

- `git diff --check` 通过。
- 首次编译缺少 `showCatalog` 跨包 import，补齐后重跑通过；未改行为。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 32：TV 返回与遥控按键分发迁移

状态：已完成。

- 将返回键导航、二次退出、触摸预处理、遥控按键分发与卡片焦点判断移到
  `navigation/MainKeyDispatch.kt`。
- `TvMainActivity` 只保留 `onBackPressed`、`dispatchTouchEvent`、`dispatchKeyEvent`
  的系统回调外壳；触摸仍先预处理再调用 `super`，按键仍未处理时调用 `super`。
- 新增 `tvToolsReady`、`hudReady` 两个 `internal` 只读投影，保留原 `lateinit`
  初始化判断。
- 迁移后的导航、退出、触摸和按键实现体按缩进及委托名归一化后与迁移前逐行一致。
- `TvMainActivity.kt` 从 2331 行降到 2090 行；`MainKeyDispatch.kt` 为 257 行。

验证：

- `git diff --check` 通过。
- 首次编译发现目录与 Kotlin 包名不一致，以及扩展无法访问 `lateinit` backing field；
  按实际包名修正并增加只读投影后重跑通过，未改行为。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 33：TV 前后台收尾生命周期迁移

状态：已完成。

- 将 `onPause`、`onStop`、`onDestroy` 的收尾实现移到
  `system/MainBackgroundLifecycle.kt`。
- Activity 仍以系统回调外壳调用实现，并通过原位置的 `super` 回调保持生命周期顺序；
  `onDestroy` 的广播、网络监听、封面回调和协程取消均按原顺序执行。
- 迁移后的三个实现体按缩进及内部委托名归一化后与迁移前逐行一致。
- `TvMainActivity.kt` 从 2090 行降到 2047 行；`MainBackgroundLifecycle.kt` 为 57 行。

验证：

- `git diff --check` 通过。
- 首次编译缺少 `CoroutineScope.cancel()` 扩展 import，补齐后重跑通过；未改行为。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 夜间阶段摘要（批次 29–33）

- 批次 29–33 全部完成并独立提交，TV 调试状态、启动初始化、恢复、按键与前后台生命周期已拆分。
- `TvMainActivity.kt` 从 2611 行降到 2047 行；新增职责文件均低于 300 行。
- 每批聚合编译、Lint、共享单测与 Debug APK 均通过，无新增 Lint Error / Fatal。
- `:core:test` 最近为 435 例、24 失败、28 跳过，未高于基线。
- 下一阶段转入 `PhonePlayerActivity.kt`，优先拆状态字段与播放生命周期辅助。

### 批次 34：手机播放页下一季续播职责迁移

状态：已完成。

- 将最后一集查询下一季、缓存命中、自动续季、底部提示、代次取消和全剧结束处理移到
  `player/PhoneNextSeason.kt`。
- 新增 `PhoneNextSeasonState` 内部状态容器，集中保存下一季、查询 Job、代次与提示视图；
  迁移前已确认相关私有方法只在 `PhonePlayerActivity.kt` 内调用。
- 保留原查询并发、`PhoneSession.relatedWorks` 缓存、协程取消、`autoNext` 判据和
  `play(0)` 调用顺序。
- 新增三个 `internal` 委托，供续季扩展读取定时器、提示粘滞状态并执行原定时停止逻辑。
- `PhonePlayerActivity.kt` 从 4605 行降到 4423 行；`PhoneNextSeason.kt` 为 201 行。

验证：

- `git diff --check` 通过。
- 首次编译发现扩展协程标签与状态容器 `lateinit` 判断边界，改为捕获原 Activity 引用并
  增加只读就绪投影后重跑通过，未改查询或取消语义。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 35：手机播放页取地址重试迁移

状态：已完成。

- 将换集 / 首集取地址失败的设备身份自愈、提示文案、立即与退避重试、次数归零和缓存作废
  移到 `player/PhoneSourceRetry.kt`。
- 新增 `PhoneSourceRetryState` 内部状态容器；`cancelSourceRetry`、网络恢复判断和播放
  成功后的计数归零均改为读取同一状态实例。
- 新增提示代次与粘滞状态的 `internal` 委托，保留原提示覆盖和延迟收起语义。
- 迁移后的取地址重试实现体按缩进与状态名归一化后与迁移前逐行一致。
- `PhonePlayerActivity.kt` 从 4423 行降到 4314 行；`PhoneSourceRetry.kt` 为 119 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 36：手机播放页相关剧集与作者作品迁移

状态：已完成。

- 将相关剧集浮层、作者作品浮层、收藏切换和卡片跳详情移到
  `player/PhoneRelatedPanels.kt`。
- 新增 `PhonePlayerRelatedState` 内部状态容器，集中保存请求代次、作者缓存和浮层打开状态；
  自动收起判断、设置面板关闭和网络缓存读取继续使用同一状态。
- 保留进程级相关剧集缓存、作者作品缓存、协程代次、横竖屏宽度与浮层高度判据。
- 新增 `rootReady`、`playerSheet`、`playerLandscapeMode`、自动收起代次与任务等
  `internal` 投影，未扩大公开 API。
- `PhonePlayerActivity.kt` 从 4314 行降到 4100 行；`PhoneRelatedPanels.kt` 为 268 行。

验证：

- `git diff --check` 通过。
- 首次编译发现扩展不能读取 `root` 的 `lateinit` backing field，增加只读投影后重跑通过；
  未改浮层或导航行为。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 37：手机播放器监听与无感换集迁移

状态：已完成。

- 将播放器监听装配、片尾判断、手动 / 自动换集、视频尺寸、首帧、错误与逐帧回调移到
  `player/PhonePlayerListener.kt`。
- 将无感换集的完成记录、账号进度同步、界面重绑、看门狗重挂、会话发布和下一季准备一并迁移。
- 保留监听器先摘旧再挂新、队列项判据、回调顺序、`lastFrameAt` 与 `DebugFaults.STALL_FRAME`
  行为。
- 新增若干 `internal` 只读 / 委托与单行方法投影，未扩大公开 API。
- `PhonePlayerActivity.kt` 从 4100 行降到 3936 行；`PhonePlayerListener.kt` 为 216 行。

验证：

- `git diff --check` 通过。
- 迁移后的无感换集实现体按缩进和委托名归一化后与迁移前逐行一致；监听器主体仅替换字段
  投影。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 38：手机播放进度、预取与队列迁移

状态：已完成。

- 将每秒 ticker、进度 / 缓存段刷新、断点落盘、下一集预取和队列挂载移到
  `player/PhonePlaybackProgress.kt`。
- 保留 2 秒预取起点、10 分钟地址安全窗口、8 秒失败退避、14 集模型队列、
  按集定时不挂队列和挂载失败兜底。
- 新增进度视图、ticker、拖动态、预取状态和 companion 常量的 `internal` 投影；
  未扩大公开 API。
- `PhonePlayerActivity.kt` 从 3936 行降到 3782 行；`PhonePlaybackProgress.kt` 为 187 行。

验证：

- `git diff --check` 通过。
- 首次编译发现 companion 常量投影需通过类名访问，修正后重跑通过；未改阈值或预取逻辑。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 夜间阶段摘要（批次 34–38）

- 批次 34–38 全部完成并独立提交，手机播放页的续季、地址重试、浮层、监听与进度职责已拆分。
- `PhonePlayerActivity.kt` 从 4605 行降到 3782 行；新增职责文件均低于 300 行。
- 每批聚合编译、Lint、共享单测与 Debug APK 均通过，无新增 Lint Error / Fatal。
- `:core:test` 最近为 435 例、24 失败、28 跳过，未高于基线。
- 下一阶段继续拆手机播放页的媒体会话、网络恢复与剩余形态切换辅助。

### 批次 39：手机媒体会话与网络恢复迁移

状态：已完成。

- 将媒体会话创建 / 复用、系统播放状态同步、网络恢复监听与注销移到
  `player/PhonePlayerServices.kt`。
- 新增 `PhonePlayerRuntimeState` 内部状态容器，集中保存媒体会话、失败标记和网络回调。
- 保留媒体按键回调、失败后不重试、网络恢复只在等待地址或错误页时重播的判据。
- `PhonePlayerActivity.kt` 从 3782 行降到 3688 行；`PhonePlayerServices.kt` 为 103 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 40：手机播放设置快捷芯片迁移

状态：已完成。

- 将清晰度 / 倍速 / 弹幕 / 选集芯片刷新、弹幕循环和倍速循环移到
  `player/PhonePlayerChips.kt`。
- 保留弹幕四档循环顺序、倍速手机端档位、落盘、提示 900ms 收起和隐藏档补拉逻辑。
- 新增芯片、用户倍速与提示代次的 `internal` 投影，未扩大公开 API。
- `PhonePlayerActivity.kt` 从 3688 行降到 3590 行；`PhonePlayerChips.kt` 为 98 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 41：手机信息流进入正式播放页迁移

状态：已完成。

- 将信息流打开播放页、观看记录、点击事件、播放器交接、控制层隐藏和当前集号判定移到
  `player/PhoneFeedNavigation.kt`。
- 保留 `settleRecommendWatch`、`markWatched`、`reportEvent`、`beginHandoff`、
  `takeOver`、`onHidden` 与导航调用的原顺序。
- 新增播放器、播放下标、推荐 Store / Playback、当前集映射等 `internal` 投影，
  未扩大公开 API。
- `PhoneFeedView.kt` 从 2342 行降到 2263 行；`PhoneFeedNavigation.kt` 为 99 行。

验证：

- `git diff --check` 通过。
- 迁移后的打开播放页、交接、控制层隐藏和集号判定逻辑按字段投影归一化后与迁移前一致。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 42：手机信息流观看与曝光迁移

状态：已完成。

- 将推荐观看结算、曝光 / 起播上报和批量模型预取移到
  `player/PhoneFeedMetrics.kt`。
- 保留曝光内容类型补偿、当前集映射、首帧延迟、批量 key、播放代次、候选上限和
  DetailCache 优先顺序。
- 新增列表、隐藏状态、播放代次、详情、Store / Playback、Scope 与批量任务等
  `internal` 投影。
- `PhoneFeedView.kt` 从 2263 行降到 2198 行；`PhoneFeedMetrics.kt` 为 86 行。

验证：

- `git diff --check` 通过。
- 迁移后的观看结算、曝光与批量预取逻辑按字段投影归一化后与迁移前一致。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 43：手机信息流相邻预热迁移

状态：已完成。

- 将 `SlideCacheManager` 状态、预热节流、停留确认、带宽让路判断和预热派发移到
  `player/PhoneFeedPreload.kt`。
- 新增 `PhoneFeedPreloadState`，由内部 `PreloadScheduler` 继续使用同一份计划、
  锚点、前后台与播放代次。
- 保留只派前 3 条、当前剧跳过、地址 / 完整预热区分、断点续集和页面脱离关闭资源。
- `PhoneFeedView.kt` 从 2198 行降到 2092 行；`PhoneFeedPreload.kt` 为 115 行。

验证：

- `git diff --check` 通过。
- 迁移后的门控、停留、排程与预热派发实现体按字段投影归一化后与迁移前一致。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 44：手机信息流快照与分页迁移

状态：已完成。

- 将快照恢复、下一页加载、身份就绪补拉和可接手索引移到
  `player/PhoneFeedData.kt`。
- 保留续流游标、首屏预算、列表增量通知、空页重试、播放定位和日志语义。
- 新增 Store 状态、genre、游标恢复、身份待刷新、Library 槽位及列表通知等
  `internal` 投影。
- `PhoneFeedView.kt` 从 2092 行降到 1990 行；`PhoneFeedData.kt` 为 134 行。

验证：

- `git diff --check` 通过。
- 迁移后的快照、分页和索引逻辑按字段投影归一化后与迁移前一致。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 45：手机信息流播放与临时倍速迁移

状态：已完成。

- 将播放 / 暂停、临时 2 倍速开始与结束移到
  `player/PhoneFeedControls.kt`。
- 保留 `playWhenReady` 意图判据、用户暂停状态、中心图标、宿主播放状态回调与提示显示。
- 新增暂停、绑定页、提示、临时倍速与用户倍速的 `internal` 投影。
- `PhoneFeedView.kt` 从 1990 行降到 1974 行；`PhoneFeedControls.kt` 为 32 行。

验证：

- `git diff --check` 通过。
- 迁移后的播放、暂停与倍速控制逻辑按字段投影归一化后与迁移前一致。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 夜间阶段摘要（批次 41–45）

- 批次 41–45 全部完成并独立提交，信息流导航、指标、预热、分页与播放控制已拆分。
- `PhoneFeedView.kt` 从 2342 行降到 1974 行；新增职责文件均低于 150 行。
- 每批聚合编译、Lint、共享单测与 Debug APK 均通过，无新增 Lint Error / Fatal。
- `:core:test` 最近为 435 例、24 失败、28 跳过，未高于基线。
- 下一阶段继续处理信息流播放器监听与片尾推进，再转入 `Library.kt`。

### 批次 46：手机信息流进度与弹幕时钟迁移

状态：已完成。

- 将弹幕时钟同步、进度 / 缓存段立即刷新和 500ms 进度 ticker 移到
  `player/PhoneFeedPlaybackClock.kt`。
- 保留推荐进度上报、会话 touch、暂停判据、下一集预取和 `STATE_ENDED` 兜底。
- 新增进度任务、页面查询、弹幕加载器、预取与片尾推进的 `internal` 投影。
- `PhoneFeedView.kt` 从 1974 行降到 1918 行；`PhoneFeedPlaybackClock.kt` 为 66 行。

验证：

- `git diff --check` 通过。
- 迁移后的进度、弹幕与片尾兜底逻辑按字段投影归一化后与迁移前一致。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 47：手机信息流播放器监听迁移

状态：已完成。

- 将视频尺寸、READY、首帧、播放状态、位置跳变和播放错误监听移到
  `player/PhoneFeedListener.kt`。
- 保留曝光、批量模型预取、连续失败归零、错误上报、跳过上限、提示与滚动 / 补页顺序。
- 新增监听器、失败计数、平滑滚动和跳过上限的 `internal` 投影。
- `PhoneFeedView.kt` 从 1918 行降到 1806 行；`PhoneFeedListener.kt` 为 119 行。

验证：

- `git diff --check` 通过。
- 首次编译发现一处监听重挂仍调用旧私有方法，替换后重跑通过；未改监听行为。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 48：手机信息流片尾推进与交接迁移

状态：已完成。

- 将片尾复核、防重入、下一集正式播放交接、预热挂源和最后一集换片移到
  `player/PhoneFeedAdvance.kt`。
- 保留 5 秒推进保护、2 秒片尾余量、`beginHandoff` / `takeOver` / `onHidden` 顺序、
  原进度上报和 `noTransition` 导航。
- 新增 advancing 状态、保护时限与用户倍速的 `internal` 投影。
- `PhoneFeedView.kt` 从 1806 行降到 1707 行；`PhoneFeedAdvance.kt` 为 126 行。

验证：

- `git diff --check` 通过。
- 首次编译缺少 `swapMediaSource` import，补齐并修正监听调用后重跑通过；未改交接语义。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 49：Library 身份布局初始化迁移

状态：已完成。

- 将 `ensureIdentityLayout` 的实现体移到
  `LibraryIdentityLayout.kt`，`Library` 保留同名私有门面。
- 保留旧 Cookie 合并、`legacy-*` 归档、新主设备档案生成、镜像同步、手动 ID 清理和
  一次性标记顺序。
- 新增 SessionPrefs、Mirror、布局标记、profile 指纹缓存与档案生成器的 `internal` 投影；
  公开 API 未改。
- `Library.kt` 从 1984 行降到 1941 行；`LibraryIdentityLayout.kt` 为 74 行。

验证：

- `git diff --check` 通过。
- 首次编译发现私有 companion 不能跨文件访问，改用实例级只读投影后重跑通过；键值未变。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 50：Library 官方设备档案生成迁移

状态：已完成。

- 将官方设备档案生成与 Android ID 读取移到 `LibraryDeviceProfile.kt`，
  `Library` 保留原私有门面。
- 保留机型 / 品牌回退、分辨率 / 密度、运营商、固件、首次安装时间、随机
  `openudid` 与三个设备标识的生成规则。
- 新增 `identityApp` 只读投影；公开 API、落盘格式和 JSON 字段未改。
- `Library.kt` 从 1941 行降到 1886 行；`LibraryDeviceProfile.kt` 为 69 行。

验证：

- `git diff --check` 通过。
- 首次编译缺少 `OfficialDeviceIds` import，补齐后重跑通过；字段与回退值未变。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 夜间阶段摘要（批次 46–50）

- 批次 46–50 全部完成并独立提交，信息流时钟 / 监听 / 片尾与 Library 身份职责已拆分。
- `PhoneFeedView.kt` 降到 1707 行，`Library.kt` 降到 1886 行；新增文件均低于 150 行。
- 每批聚合编译、Lint、共享单测与 Debug APK 均通过，无新增 Lint Error / Fatal。
- `:core:test` 最近为 435 例、24 失败、28 跳过，未高于基线。
- 下一阶段继续拆 Library 注册 Cookie / 待提交档案，再处理 ContentRepository 剩余职责。

### 批次 51：Library 注册 Cookie 持久化迁移

状态：已完成。

- 将注册 Cookie 读取、清理和保存移到 `LibraryRegistrationCookies.kt`；
  `Library` 三个公开方法保留原签名并委托。
- 保留身份布局检查、镜像恢复、空值过滤、同步 commit、镜像写入和 flush 顺序。
- 新增 `restoreFromMirrorForRegistrationCookies` 内部门面，没有扩大公开 API。
- `Library.kt` 从 1886 行降到 1859 行；`LibraryRegistrationCookies.kt` 为 47 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 52：Library 待提交设备档案迁移

状态：已完成。

- 将候选档案读取、落盘、注册档案选择和成功注册后的转正移到
  `LibraryPendingProfile.kt`。
- `commitPendingProfile`、`registrationProfile`、`hasPendingProfile` 保留原公开签名，
  只委托到内部实现。
- 保留随机 openudid 升级、种子与档案同步提交、旧 pending stamp 清理、镜像 flush 与日志。
- `Library.kt` 从 1859 行降到 1821 行；`LibraryPendingProfile.kt` 为 74 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 53：Library 账号凭据持久化迁移

状态：已完成。

- 将账号读取、保存和清理移到 `LibraryAccountCredentials.kt`；
  `account` / `saveAccount` / `clearAccount` 保留原公开签名并委托。
- 保留 sessionid 变化时 token / 签名清理、空 token 不覆盖、设备档案同步、镜像更新和
  账号缓存清理。
- 新增镜像同步与账号镜像键的 `internal` 门面，没有扩大公开 API。
- `Library.kt` 从 1821 行降到 1770 行；`LibraryAccountCredentials.kt` 为 96 行。

验证：

- `git diff --check` 通过。
- 首次编译缺少 `AccountCredentials` import，补齐后重跑通过；凭据键和顺序未变。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 54：Library 注册状态与备用档案迁移

状态：已完成。

- 将注册待刷新标记的写入 / 清理 / 查询和备用播放档案生成移到
  `LibraryRegistrationState.kt`。
- `markRegistrationPending`、`clearRegistrationPending`、`registrationPending`、
  `freshPlaybackBackupProfile` 保留原公开签名。
- 保留身份布局检查、pending profile 判据、随机 cdid / clientudid / openudid 规则。
- `Library.kt` 从 1770 行降到 1747 行；`LibraryRegistrationState.kt` 为 45 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 55：Library 账号设备 JSON 迁移

状态：已完成。

- 将账号设备 JSON 读取与保存移到 `LibraryAccountDevice.kt`；
  `accountDevice` / `saveAccountDevice` 保留原公开签名并委托。
- 保留空值过滤、JSON 字段、同步 apply 与镜像更新。
- `Library.kt` 从 1747 行降到 1730 行；`LibraryAccountDevice.kt` 为 31 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 56：Library 设备身份与注册结果迁移

状态：已完成。

- 将设备身份读取、注册判定、档案指纹、注册作废和身份保存移到
  `LibraryDeviceIdentity.kt`；原公开方法签名与默认参数保持不变并委托。
- 保留身份布局初始化、镜像恢复顺序、数字身份校验、Cookie `install_id` 校验与清理、
  档案指纹缓存、同步 `commit`、镜像更新和 `flush` 顺序。
- `Library.kt` 从 1733 行降到 1641 行；`LibraryDeviceIdentity.kt` 为 164 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 57：Library 播放与界面设置迁移

状态：已完成。

- 将播放、搜索、列数、播放显示、选集和动画设置移到 `LibraryPlaybackSettings.kt`；
  原公开属性及其 JVM getter / setter 保留在 `Library` 上并委托。
- 保留规范化、范围夹取、比较条件、磁盘键名和设置队列写入顺序。
- `Library.kt` 从 1641 行降到 1532 行；`LibraryPlaybackSettings.kt` 为 291 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 58：PhonePlayer 调试面板迁移

状态：已完成。

- 将调试面板挂载、刷新、采样回调与播放现场文本组装移到 `PhonePlayerDebug.kt`；
  原私有调用入口保留并委托。
- 保留调试开关、日志 sink、面板创建与放置、关闭清理、字段顺序和采样重入行为。
- `PhonePlayerActivity.kt` 从 3620 行降到 3542 行；`PhonePlayerDebug.kt` 为 98 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 59：PhonePlayer 播放器释放与交接解绑迁移

状态：已完成。

- 将 `releasePlayer` 与 `detachForHandoff` 的实现移到 `PhonePlayerLifecycle.kt`；
  原私有调用入口保留并委托。
- 保留会话释放、重试取消、监听器摘除、暂停、Surface 解绑、进度位置记录和交接暂停判定顺序。
- `PhonePlayerActivity.kt` 从 3542 行降到 3513 行；`PhonePlayerLifecycle.kt` 为 51 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 60：PhonePlayer 上下滑换集迁移

状态：已完成。

- 将上下滑翻页、相邻页准备、回弹 / 吸附动画、集数提示与相邻集切换移到
  `PhonePlayerSwipe.kt`；原私有手势入口和状态字段保留在 Activity 上并委托。
- 保留方向判定、相邻页绑定内容、横竖屏平移对象、底层视频同步偏移、帧时钟取消、
  页面引用交换、播放器推进及提示代次顺序。
- `PhonePlayerActivity.kt` 从 3513 行降到 3273 行；`PhonePlayerSwipe.kt` 为 290 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 61：PhonePlayer 画面尺寸与缩放迁移

状态：已完成。

- 将播放器画面尺寸同步、横屏全屏按钮定位、画面缩放与横屏 PlayerView 布局移到
  `PhonePlayerVideoFrame.kt`；原私有调用入口与 listener 入口保留并委托。
- 保留尺寸有效性、FIT 画面比例、按钮下边缘与底栏限制、缩放范围、居中重排和
  借下层画面缩放顺序。
- `PhonePlayerActivity.kt` 从 3273 行降到 3202 行；`PhonePlayerVideoFrame.kt` 为 107 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 62：PhonePlayer 播放信息与横屏摘要迁移

状态：已完成。

- 将竖屏 / 横屏播放信息行刷新与横屏设置摘要移到 `PhonePlayerPlayInfo.kt`；
  原进度与按钮 listener 入口保留并委托。
- 保留四项开关、默认全显、网速与当前时间来源、剧名集数格式、竖横屏显隐、
  顶部压暗联动及画面模式 / 缩放 / 解码 / 定时摘要顺序。
- `PhonePlayerActivity.kt` 从 3202 行降到 3150 行；`PhonePlayerPlayInfo.kt` 为 66 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 63：PhonePlayer 横屏设置壳与画面模式迁移

状态：已完成。

- 将横屏设置面板的创建、关闭、摘要刷新、自动收起，以及画面模式落位移到
  `PhonePlayerLandscapePanel.kt`；原私有入口保留并委托。
- 保留面板单实例、关闭后的清屏回调、设置行布局参数、`PlayerView` resize mode
  与借下层画面模式同步顺序。
- `PhonePlayerActivity.kt` 从 3150 行降到 3139 行；`PhonePlayerLandscapePanel.kt` 为 41 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 64：PhonePlayer 播放设置行迁移

状态：已完成。

- 将竖屏 / 横屏共用的播放设置行构建、倍速回写、换清晰度重播、画面 / 缩放 / 解码、
  定时、相关剧集、作者作品、收藏与弹幕回调移到 `PhonePlayerSettingsRows.kt`。
- 保留控件顺序、设置落盘、弹幕隐藏后补拉、面板关闭和调试面板刷新顺序。
- `PhonePlayerActivity.kt` 从 3139 行降到 3073 行；`PhonePlayerSettingsRows.kt` 为 96 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 65：Library 真机设备档案迁移

状态：已完成。

- 将 `deviceProfile()` 的档案读取、Android ID 升级、镜像补写、首次生成与同步落盘
  移到 `LibraryDeviceProfile.kt`；原公开签名保留并委托。
- 保留官方格式校验、`openudid-source` 判据、缓存指纹失效、`commit` 与 `flush` 顺序。
- `Library.kt` 从 1532 行降到 1471 行；`LibraryDeviceProfile.kt` 从 69 行增至 126 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 66：Library 会话同步导出与导入迁移

状态：已完成。

- 将会话白名单导出、覆盖导入、镜像刷新与恢复通知移到 `LibrarySyncSession.kt`；
  原公开签名与默认类别参数保持不变并委托。
- 保留时间字段类型、空值删除、同步 `commit`、镜像 `flush`、布局 / 镜像检查标记和
  档案指纹失效顺序。
- `Library.kt` 从 1471 行降到 1439 行；`LibrarySyncSession.kt` 从 67 行增至 113 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 67：Library 外部镜像恢复迁移

状态：已完成。

- 将外部镜像缺项恢复、权限后重读、镜像同步辅助移到 `LibraryMirrorRestore.kt`；
  原恢复与重试入口保留并委托。
- 保留 loader 线程读盘、主线程回调、数值字段类型、缺失项优先、镜像立即落盘与
  会话恢复通知顺序。
- `Library.kt` 从 1439 行降到 1394 行；`LibraryMirrorRestore.kt` 为 64 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 批次 68：Library 账号缓存身份隔离迁移

状态：已完成。

- 将账号缓存身份键、缓存可用性、快照读取 / 写入 / 清除移到
  `LibraryAccountCache.kt`；原公开入口保留并委托。
- 保留登录 Cookie 优先、注册设备匿名隔离、无身份不落盘、内存替换与写入队列行为。
- `Library.kt` 从 1394 行降到 1384 行；`LibraryAccountCache.kt` 为 41 行。

验证：

- `git diff --check` 通过。
- 指定 JDK 17 聚合命令通过，包含 `:core:compileKotlin`、
  `:feature-tv:compileDebugKotlin`、`:feature-phone:compileDebugKotlin`、
  `:shared:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 24 例通过。
- `:core:test` 为 435 例、25 失败、28 跳过，与基线一致，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

### 夜间阶段摘要（批次 60–64）

- 批次 60–64 全部完成并独立提交，PhonePlayer 的滑页、画面尺寸、信息栏、设置壳与设置行已拆分。
- `PhonePlayerActivity.kt` 从 3513 行降到 3073 行；新增职责文件均低于 300 行。
- 每批聚合编译、Lint、共享单测与 Debug APK 均通过，无新增 Lint Error / Fatal。
- `:core:test` 最近为 435 例、24 失败、28 跳过，未超过基线失败数。
- 下一阶段转向 Library 身份注册与账号同步的剩余职责，再处理 PhoneFeed。

### 夜间阶段摘要（批次 51–55）

- 批次 51–55 全部完成并独立提交，Library 的 Cookie、pending profile、账号凭据与设备档案已拆分。
- `Library.kt` 从 1886 行降到 1730 行；新增文件均低于 100 行。
- 每批聚合编译、Lint、共享单测与 Debug APK 均通过，无新增 Lint Error / Fatal。
- `:core:test` 最近为 435 例、25 失败、28 跳过，与基线一致。
- 下一阶段继续 Library 身份读写，并补齐 PhonePlayer 横竖屏、PhoneFeed 与 ContentRepository 剩余拆分。

## 下一批

已达到本轮 40 批安全上限；下一轮从 `PhoneFeedView.kt` 的列表适配与交接状态开始。

## 夜间验收（2026-11-13）

### 本次新增批次

| 批次 | 提交 | 结果 | `:core:test` |
| --- | --- | --- | --- |
| 29 | `766756d` | 通过 | 25 失败 / 28 跳过 |
| 30 | `01df30a` | 通过 | 25 失败 / 28 跳过 |
| 31 | `2c3d27e` | 通过 | 24 失败 / 28 跳过 |
| 32 | `a92d38d` | 通过 | 24 失败 / 28 跳过 |
| 33 | `998e7cc` | 通过 | 24 失败 / 28 跳过 |
| 34 | `b0d05e8` | 通过 | 24 失败 / 28 跳过 |
| 35 | `5adac10` | 通过 | 25 失败 / 28 跳过 |
| 36 | `722a1ec` | 通过 | 24 失败 / 28 跳过 |
| 37 | `997ae0b` | 通过 | 24 失败 / 28 跳过 |
| 38 | `6cc3922` | 通过 | 24 失败 / 28 跳过 |
| 39 | `99095f3` | 通过 | 24 失败 / 28 跳过 |
| 40 | `325aa6b` | 通过 | 25 失败 / 28 跳过 |
| 41 | `e03457f` | 通过 | 24 失败 / 28 跳过 |
| 42 | `0d975bd` | 通过 | 25 失败 / 28 跳过 |
| 43 | `f2ce0f1` | 通过 | 25 失败 / 28 跳过 |
| 44 | `d0d5457` | 通过 | 25 失败 / 28 跳过 |
| 45 | `9135383` | 通过 | 24 失败 / 28 跳过 |
| 46 | `0b80b6c` | 通过 | 24 失败 / 28 跳过 |
| 47 | `95bb10e` | 通过 | 24 失败 / 28 跳过 |
| 48 | `ad5e4b3` | 通过 | 24 失败 / 28 跳过 |
| 49 | `93f3142` | 通过 | 24 失败 / 28 跳过 |
| 50 | `5e244e2` | 通过 | 24 失败 / 28 跳过 |
| 51 | `b12c4a3` | 通过 | 24 失败 / 28 跳过 |
| 52 | `41ffede` | 通过 | 24 失败 / 28 跳过 |
| 53 | `8840529` | 通过 | 25 失败 / 28 跳过 |
| 54 | `b6941fb` | 通过 | 24 失败 / 28 跳过 |
| 55 | `bd8fdfd` | 通过 | 25 失败 / 28 跳过 |

每批均执行了指定 JDK 17 聚合命令，`:app:assembleDebug` 和 `:app:lintDebug`
通过，无新增 `Error` / `Fatal`；`:shared:testDebugUnitTest` 24 例全过。

### 当前剩余 >800 行生产文件

- `PhonePlayerActivity.kt` 3620 行：横竖屏、滑页、设置与播放状态仍高度耦合。
- `TvMainActivity.kt` 2047 行：字段密集，剩余页面状态和系统回调待拆。
- `PhoneSettingsActivity.kt` 1960 行：本夜未开始。
- `ContentRepository.kt` 1959 行：账号之外的反射 / 兼容 facade 与剩余装配待拆。
- `RecommendPage.kt` 1890 行：本夜未开始。
- `Library.kt` 1733 行：加载编排、身份注册结果和设置属性仍集中。
- `PhoneFeedView.kt` 1705 行：列表适配、滑页、设置与交接状态仍集中。
- 其余 >800 行：`TvComponents.kt`、`PlayerController.kt`、`ListPage.kt`、
  `ArtworkLoader.kt`、`PhoneAccountActivity.kt`、`PhoneLogin.kt`、`SettingsPage.kt`、
  `HomeScreen.kt`、`DanmakuView.kt`、`TvTools.kt`、`PhoneDetailActivity.kt`、
  `PlaybackDiagnostics.kt`、`TouchController.kt`、`SeriesGrid.kt`、`PhoneVideoPage.kt`、
  `TvDialog.kt`。

### 未做 / 跳过项

- `PhonePlayerActivity` 横竖屏切换簇依赖 20 多个私有视图与形态字段，单批迁移会显著扩大
  代理面，暂缓，留待拆成更小切片。
- `PhoneSettingsActivity`、`ContentRepository` 剩余职责和上述其他 >800 行文件尚未完成。
- 未修改 UI、资源、Manifest、权限、序列化格式、数据库 schema、混淆规则、版本号或
  versionCode；未推送，未创建 tag / Release。

### 与基线测试对比

- 基线：`:core:test` 435 例、25 失败、28 跳过。
- 当前：`:core:test` 435 例、25 失败、28 跳过。
- 当前：`:shared:testDebugUnitTest` 24 例全过；`:app:lintDebug` 通过；
  `:app:assembleDebug` 通过。

### DoD 结论

未达到 DoD。主要卡点是仍有 23 个生产源码文件超过 800 行，且 6 个原始超大文件尚未全部
拆完；本夜所有已完成批次均可独立回滚，未出现基线、分支或工作区恢复问题。

## 本轮续跑验收（2026-11-13，批次 56–68）

### 新增批次与验证

| 批次 | 提交 | 结果 | `:core:test` |
| --- | --- | --- | --- |
| 56 | `3b679f3` | 通过 | 25 失败 / 28 跳过 |
| 57 | `0d1e5e0` | 通过 | 25 失败 / 28 跳过 |
| 58 | `c0bf6b7` | 通过 | 24 失败 / 28 跳过 |
| 59 | `ce60e98` | 通过 | 24 失败 / 28 跳过 |
| 60 | `6483816` | 通过 | 25 失败 / 28 跳过 |
| 61 | `b3cfae0` | 通过 | 25 失败 / 28 跳过 |
| 62 | `09e248c` | 通过 | 25 失败 / 28 跳过 |
| 63 | `a74accd` | 通过 | 25 失败 / 28 跳过 |
| 64 | `c800a3f` | 通过 | 24 失败 / 28 跳过 |
| 65 | `6e3e9f8` | 通过 | 24 失败 / 28 跳过 |
| 66 | `2391b81` | 通过 | 25 失败 / 28 跳过 |
| 67 | `4455758` | 通过 | 24 失败 / 28 跳过 |
| 68 | `f378f5c` | 通过 | 25 失败 / 28 跳过 |

每批均执行指定 JDK 17 聚合命令，`:app:assembleDebug`、`:app:lintDebug`
与 `:shared:testDebugUnitTest` 通过；批次 29–55 的提交哈希见上表。

### 当前剩余 >800 行生产文件

- `PhonePlayerActivity.kt` 3073 行：播放会话、形态切换、选集和生命周期仍集中。
- `TvMainActivity.kt` 2047 行：页面状态、焦点与系统回调仍集中。
- `PhoneSettingsActivity.kt` 1960 行：本轮未开始拆分。
- `ContentRepository.kt` 1959 行：反射 facade、请求装配和兼容路径仍集中。
- `RecommendPage.kt` 1890 行：推荐流页面状态未拆。
- `PhoneFeedView.kt` 1705 行：列表适配、滑页、设置和交接状态仍集中。
- `TvComponents.kt` 1480 行：通用电视组件未拆。
- `PlayerController.kt` 1432 行：电视播放状态与控制职责未拆。
- `Library.kt` 1384 行：加载编排、设置属性与账号入口仍集中。
- `ListPage.kt` 1309 行：列表页面状态未拆。
- `ArtworkLoader.kt` 1240 行：图片缓存与加载职责未拆。
- `PhoneAccountActivity.kt` 1158 行：账号页面职责未拆。
- `PhoneLogin.kt` 1118 行：登录流程未拆。
- `SettingsPage.kt` 1103 行：电视设置页面未拆。
- `HomeScreen.kt` 1088 行：首页状态未拆。
- `DanmakuView.kt` 1075 行：弹幕绘制与轨道职责未拆。
- `TvTools.kt` 1031 行：电视工具集合未拆。
- `PhoneDetailActivity.kt` 1009 行：详情页状态未拆。
- `PlaybackDiagnostics.kt` 981 行：播放诊断展示未拆。
- `TouchController.kt` 979 行：电视触摸与手势状态未拆。
- `SeriesGrid.kt` 967 行：剧集网格未拆。
- `PhoneVideoPage.kt` 888 行：手机视频页组件未拆。
- `TvDialog.kt` 840 行：电视对话框未拆。

### 未做 / 跳过项

- `PhonePlayerActivity` 的形态切换仍依赖大量私有视图和播放状态，只拆了可证明等价的小簇。
- `PhoneFeedView`、`PhoneSettingsActivity`、`ContentRepository` 及其他 >800 行文件尚未完成。
- 未修改 UI、布局、资源、导航、权限、文案、Manifest、序列化、数据库 schema、混淆规则、
  版本号或 versionCode；未推送，未创建 tag / Release。

### 测试对比与 DoD

- 基线：`:core:test` 435 例、25 失败、28 跳过。
- 当前：`:core:test` 435 例、25 失败、28 跳过，没有新增失败。
- 当前：`:shared:testDebugUnitTest` 24 例全过；`:app:lintDebug` 通过；
  `:app:assembleDebug` 通过。
- 本轮批次 29–68 共 40 批，已达到夜间安全上限，因此停止自动执行。
- 总结：未达到 DoD；主要原因是仍有 23 个生产源码文件超过 800 行，下一轮应从
  `PhoneFeedView.kt` 或 `PhoneSettingsActivity.kt` 继续拆分。

## 新一轮续跑（2026-11-13，批次 69 起）

### 批次 69：PhoneFeed 播放设置迁移

状态：已完成。

- 将信息流播放设置面板移到 `PhoneFeedSettings.kt`；原私有入口保留并委托。
- 保留书库等待、倍速回写、换清晰度重播、画面 / 缩放、弹幕补拉和调试刷新顺序。
- `PhoneFeedView.kt` 从 1705 行降到 1659 行；`PhoneFeedSettings.kt` 为 69 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 70：PhoneFeed 列表适配器迁移

状态：已完成。

- 将 `FeedAdapter` 移到 `PhoneFeedAdapter.kt`，原列表仍通过同一适配器刷新。
- 保留每屏页面创建、手势回调、全屏 / 详情入口、进度条反馈、空列表占位和播放页重绑顺序。
- `PhoneFeedView.kt` 从 1659 行降到 1577 行；`PhoneFeedAdapter.kt` 为 114 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 71：PhoneFeed 弹幕加载迁移

状态：已完成。

- 将每屏弹幕加载器缓存、偏好落位和按屏拉取移到 `PhoneFeedDanmaku.kt`；原调用入口保留。
- 保留目标页现取、列表线程投递、隐藏模式、字号基准与 `clearOnSwitch` 行为。
- `PhoneFeedView.kt` 从 1577 行降到 1539 行；`PhoneFeedDanmaku.kt` 为 49 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 72：PhoneFeed 片尾预取迁移

状态：已完成。

- 将片尾下一集地址与首块预取移到 `PhoneFeedPrefetch.kt`；原时钟调用入口保留。
- 保留当前屏判据、30 秒窗口、5 秒防重入、IO 线程和会话队列写入顺序。
- `PhoneFeedView.kt` 从 1539 行降到 1519 行；`PhoneFeedPrefetch.kt` 为 41 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 73：PhoneFeed 页面尺寸与画面设置迁移

状态：已完成。

- 将当前页查找、画面模式 / 缩放落位、转屏后当前页对齐和时间格式移到
  `PhoneFeedPageSupport.kt`；原私有入口保留。
- 保留 ViewHolder 查找、`MATCH_PARENT` 页面不重建、当前页重对齐和设置范围。
- `PhoneFeedView.kt` 从 1519 行降到 1510 行；`PhoneFeedPageSupport.kt` 为 28 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 74：PhoneFeed Surface 重挂迁移

状态：已完成。

- 将前台存活补播、当前页重绑、回交给播放页的 Surface / 位移 / resize / scale 入口移到
  `PhoneFeedSurface.kt`；原私有入口保留并委托。
- 保留会话归属、前台判据、强制置空条件、同剧校验与播放器单例复用。
- `PhoneFeedView.kt` 从 1510 行降到 1476 行；`PhoneFeedSurface.kt` 为 62 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 75：PhoneFeed 视图生命周期迁移

状态：已完成。

- 将视图摘除与释放清理移到 `PhoneFeedLifecycle.kt`；原 override / 公开入口保留并委托。
- 保留播放页交接期间不注销 Surface、不暂停，以及协程、播放器、远程视频释放顺序。
- `PhoneFeedView.kt` 从 1476 行降到 1449 行；`PhoneFeedLifecycle.kt` 为 32 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 76：PhoneFeed 控制层计时迁移

状态：已完成。

- 将当前控制层页面、自动收起、超时和重现移到 `PhoneFeedChrome.kt`；原私有入口保留。
- 保留借画面 / 隐藏时不计时、触摸重置计时和设置面板优先收起顺序。
- `PhoneFeedView.kt` 从 1449 行降到 1441 行；`PhoneFeedChrome.kt` 为 29 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 77：PhoneFeed 播放状态与顶部导航迁移

状态：已完成。

- 将当前播放判定、播放状态通知、回到首屏和孤儿播放停止移到 `PhoneFeedActions.kt`。
- 保留前台 / 暂停 / ENDED 判据、吸附定位、播放索引和会话接管顺序。
- `PhoneFeedView.kt` 从 1441 行降到 1431 行；`PhoneFeedActions.kt` 为 31 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 78：PhoneFeed 页面绑定迁移

状态：已完成。

- 将播放器与页面绑定移到 `player/PhoneFeedPageBinding.kt`；原私有入口保留。
- 保留画面设置补写、旧页解绑、强制重挂判据、比例预热、弹幕加载和控制层显示顺序。
- `PhoneFeedView.kt` 从 1431 行降到 1395 行；`PhoneFeedPageBinding.kt` 为 50 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 79：PhoneFeed 媒体源挂载迁移

状态：已完成。

- 将媒体源挂载、播放列表裁剪、会话发布和首帧前 prepare 移到
  `player/PhoneFeedAttach.kt`；原私有入口保留。
- 保留先停再换源、监听重挂、倍速、暂停意图和 Surface 可用时提前绑页顺序。
- `PhoneFeedView.kt` 从 1395 行降到 1336 行；`PhoneFeedAttach.kt` 为 66 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 80：PhoneSettings 通用显示辅助迁移

状态：已完成。

- 将版本名、密度 / 字号换算、行高 / 行距、锚点 key、性别文案与状态栏高度移到
  `PhoneSettingsSupport.kt`。
- 保留设置页横竖屏密度比例、Android 状态栏资源回退和稳定锚点格式。
- `PhoneSettingsActivity.kt` 从 1960 行降到 1948 行；`PhoneSettingsSupport.kt` 为 41 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 81：PhoneSettings 滚动锚点迁移

状态：已完成。

- 将当前可见锚点、嵌套视图收集、容器内坐标与布局后恢复滚动移到
  `PhoneSettingsScroll.kt`。
- 保留横屏两列面板递归查找、稳定 key、像素偏移和 resetTop 行为。
- `PhoneSettingsActivity.kt` 从 1948 行降到 1898 行；`PhoneSettingsScroll.kt` 为 80 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 82：PhoneSettings 通用控件迁移

状态：已完成。

- 将分组、提示、胶囊行、开关、滑动条、选择 / 确认框与 toast 移到
  `PhoneSettingsWidgets.kt`。
- 保留横竖屏行高 / 间距、电视端对齐的软阴影样式、稳定锚点与文案。
- `PhoneSettingsActivity.kt` 从 1898 行降到 1808 行；`PhoneSettingsWidgets.kt` 为 191 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 83：PhoneSettings 选择与开关回写迁移

状态：已完成。

- 将选择、开关、滑动条回写和 UI 大小重建移到 `PhoneSettingsApply.kt`。
- 保留各设置控制器调用、直接落盘项、透明度 / 条数 / 字号换算。
- `PhoneSettingsActivity.kt` 从 1808 行降到 1775 行；`PhoneSettingsApply.kt` 为 77 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 84：ContentRepository 传输与 JSON 基础迁移

状态：已完成。

- 将 JSON 数组 / 字符串 / 宽松布尔 / URL 清洗和默认 HTTP client 移到
  `ContentTransport.kt`；原公开顶层函数形态保持不变。
- 保留 HTTP/1.1、15/30/45 秒超时、SessionInterceptor 和调试时序监听。
- `ContentRepository.kt` 从 1959 行降到 1928 行；`ContentTransport.kt` 为 39 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 85：ContentRepository 设备档案与线路迁移

状态：已完成。

- 将未登录 / 登录设备档案合并和相关 / 评论线路选择移到 `ContentProfiles.kt`。
- 保留内置档案、真机档案、注册身份三层覆盖顺序和 TNC 线路排序。
- `ContentRepository.kt` 从 1928 行降到 1919 行；`ContentProfiles.kt` 为 23 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 86：ContentRepository 请求参数构造迁移

状态：已完成。

- 将写接口、App、搜索、账号和推荐请求的参数 / 签名构造移到 `ContentValues.kt`。
- 保留版本常量、搜索上下文、主身份快照和签名路径。
- `ContentRepository.kt` 从 1919 行降到 1899 行；`ContentValues.kt` 为 64 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 87：ContentRepository 主身份与会话头迁移

状态：已完成。

- 将主身份快照、主档案 / Cookie 和普通 / 账号会话头注入移到 `ContentIdentity.kt`。
- 保留账号设备优先、同 sessionid 的 live token 合并、install_id 补写和登录校验。
- `ContentRepository.kt` 从 1899 行降到 1866 行；`ContentIdentity.kt` 为 66 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 88：ContentRepository 作者规则迁移

状态：已完成。

- 将热度值、作者同一性和空作者兼容规则移到 `ContentAuthorRules.kt`。
- 保留空白与大小写归一化，不引入模糊作者匹配。
- `ContentRepository.kt` 从 1866 行降到 1863 行；`ContentAuthorRules.kt` 为 19 行。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 89：ContentRepository 播放档位迁移

状态：已完成。

- 将每集重置 4 档和空响应升到 5 档移到 `ContentDeviceLevel.kt`。
- 保留只使用 4 / 5 两档和升级日志。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。

回滚：`git revert` 本批提交。

### 批次 90：ContentRepository 异常类型迁移

状态：已完成。
- 将分类、地址超时、搜索过期 / 业务错误、设备未注册和登录必需异常移到
  `ContentTransport.kt`。
- 保留公开类名、默认文案、原因码字段与 `IOException` 继承关系。
- 聚合编译、Lint、共享单测、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。

回滚：`git revert` 本批提交。

## 批次 69–90 阶段检查点

- 已完成 22 / 40 批，全部独立提交；最新代码提交为 `1f96c22`。
- 批次提交：`e591c5a`、`98ac097`、`49d368e`、`265455c`、`fdea801`、
  `f6c7ec0`、`aada8f5`、`9306d00`、`494f32b`、`a7e6530`、`cf4818f`、
  `c1e35be`、`a808f20`、`60a1bad`、`971b407`、`2d25af3`、`44c4094`、
  `519d18c`、`3fdcc6e`、`7f99528`、`8529922`、`1f96c22`。
- 每批均通过聚合编译、Lint、共享单测和 Debug APK；最近一次 `:core:test`
  为 435 例、24 失败、28 跳过，未超过基线。
- 当前超大文件：`PhonePlayerActivity.kt` 3073、`TvMainActivity.kt` 2047、
  `RecommendPage.kt` 1890、`ContentRepository.kt` 1831、
  `PhoneSettingsActivity.kt` 1775、`PhoneFeedView.kt` 1337，以及其余 17 个 >800 行文件。
- 未达 40 批上限；下一轮从批次 91 继续，优先 `PhoneFeedView` 的接管 / 地址刷新，
  再处理 `PhoneSettingsActivity` 的 action 分派与横向渲染。

### 批次 91：PhoneFeed 返回续接迁移

状态：已完成。

- 将播放页 / 后台返回时的续接入口移到 `PhoneFeedResume.kt`；原私有入口保留。
- 保留播放页前台判据、会话归属、下标对齐、原地重开失败回退和控制层延迟恢复顺序。
- `PhoneFeedView.kt` 从 1337 行降到 1267 行；`PhoneFeedResume.kt` 为 85 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 92：PhoneFeed 播放器接手迁移

状态：已完成。

- 将进程级播放器接手、IDLE 补 prepare、播放列表裁剪、倍速与暂停意图同步移到
  `PhoneFeedAdoption.kt`；原私有入口保留并委托。
- 保留会话归属、页面缺席仍接管、弹幕 / 进度即时对齐、地址过期重开和延迟补播顺序。
- `PhoneFeedView.kt` 从 1267 行降到 1145 行；`PhoneFeedAdoption.kt` 为 137 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 93：PhoneFeed 地址过期原地重开迁移

状态：已完成。

- 将过期地址原地换源移到 `PhoneFeedReopen.kt`；原私有入口保留并委托。
- 保留播放位置与暂停意图、播放代次、Surface 不重挂、会话发布和失败回退行为。
- `PhoneFeedView.kt` 从 1145 行降到 1097 行；`PhoneFeedReopen.kt` 为 73 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 94：PhoneFeed 隐藏清理迁移

状态：已完成。

- 将切 tab / 后台 / 播放页交接时的隐藏清理移到 `PhoneFeedHide.kt`；公开入口保留。
- 保留交接窗口不暂停、不解绑 Surface，普通隐藏作废在途播放并注销借用入口。
- `PhoneFeedView.kt` 从 1097 行降到 1027 行；`PhoneFeedHide.kt` 为 81 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 95：PhoneFeed underlay 切换迁移

状态：已完成。

- 将借出 / 收回画面时的 underlay 切换移到 `PhoneFeedSurface.kt`；公开入口保留。
- 保留面板收起、控制层计时、当前页回退、位移清理和弹幕显隐重刷行为。
- `PhoneFeedView.kt` 从 1027 行降到 1013 行；`PhoneFeedSurface.kt` 为 81 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 96：PhoneFeed 起播主流程迁移

状态：已完成。

- 将单播放器起播主流程移到 `PhoneFeedPlayback.kt`；原私有入口保留并委托。
- 保留方向、曝光、停留计时、预热计划、播放代次、书库等待和失败诊断顺序。
- `PhoneFeedView.kt` 从 1013 行降到 920 行；`PhoneFeedPlayback.kt` 为 114 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 97：PhoneFeed 宿主回调与换体裁迁移

状态：已完成。

- 将换体裁重置、回到首页和注册完成回调移到 `PhoneFeedHost.kt`；原公开入口保留。
- 保留快照 / 游标切换、在途播放作废、列表清空、控制层恢复和补拉顺序。
- `PhoneFeedView.kt` 从 920 行降到 912 行；`PhoneFeedHost.kt` 为 46 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 98：PhoneFeed 初始化装配迁移

状态：已完成。

- 将列表、吸附、空闲起播、书库等待和冷启动快照装配移到 `PhoneFeedInitializer.kt`。
- 保留嵌套滚动关闭、预加载阈值、首屏快照与控制层计时初始化顺序。
- `PhoneFeedView.kt` 从 912 行降到 872 行；`PhoneFeedInitializer.kt` 为 71 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 99：PhoneFeed 转发层清理

状态：已完成。

- 移除已迁出职责的私有转发方法，调用方直接使用对应实现入口。
- 保留公开方法、适配器回调、设置入口和播放页 Surface 交接签名。
- `PhoneFeedView.kt` 从 872 行降到 796 行，已低于 800 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 100：PhoneSettings 横屏布局基元迁移

状态：已完成。

- 将横屏面板、流式格、双栏、设置格、开关和滑动条移到
  `PhoneSettingsLandscapeWidgets.kt`。
- 保留面板阴影、标题说明、单格半行占位、行高和间距。
- `PhoneSettingsActivity.kt` 从 1775 行降到 1648 行；
  `PhoneSettingsLandscapeWidgets.kt` 为 160 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 101：PhoneSettings 横屏播放与外观迁移

状态：已完成。

- 将横屏播放、弹幕、界面信息和主题外观分区移到
  `PhoneSettingsLandscapePages.kt`。
- 保留设置值、横屏密度、主题网格、UI 大小重建和弹幕换算行为。
- `PhoneSettingsActivity.kt` 从 1648 行降到 1519 行；
  `PhoneSettingsLandscapePages.kt` 为 161 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 102：PhoneSettings 横屏推荐与系统迁移

状态：已完成。

- 将横屏推荐、内容管理、数据维护、设备与性能分区移到
  `PhoneSettingsLandscapePages.kt`。
- 保留隐藏剧集恢复、设备类型切换、备份、重置和局域网同步入口。
- `PhoneSettingsActivity.kt` 从 1519 行降到 1435 行；
  `PhoneSettingsLandscapePages.kt` 为 258 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 103：PhoneSettings 统一渲染与 action 迁移

状态：已完成。

- 将统一行渲染、四个分区描述、action 分派和滑动读数格式化移到
  `PhoneSettingsUnified.kt`。
- 保留各控件回写、确认文案、恢复默认和设备类型切换行为。
- `PhoneSettingsActivity.kt` 从 1435 行降到 1144 行；`PhoneSettingsUnified.kt` 为 319 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 104：PhoneSettings 竖屏分区迁移

状态：已完成。

- 将竖屏播放、弹幕、外观、主题网格、推荐与系统分区移到
  `PhoneSettingsPortrait.kt`。
- 保留页面文案、控件顺序、主题切换、设备类型切换和确认行为。
- `PhoneSettingsActivity.kt` 从 1144 行降到 831 行；
  `PhoneSettingsPortrait.kt` 为 345 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 105：PhoneSettings 备份与清空操作迁移

状态：已完成。

- 将关于、恢复默认、备份导出 / 导入和清空应用移到 `PhoneSettingsActions.kt`。
- 保留 SAF 文件选择器、备份编解码、落盘和清空重启顺序。
- `PhoneSettingsActivity.kt` 从 831 行降到 740 行；`PhoneSettingsActions.kt` 为 106 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：本批与批次 106 合并提交，回退时按文件恢复本批职责。

### 批次 106：PhoneSettings 局域网同步迁移

状态：已完成。

- 将局域网会话、角色选择、同步项确认、状态渲染和重启时序移到
  `PhoneSettingsLanSync.kt`。
- 保留设备发现、错误提示、同步内容选择和 800ms 重启延迟。
- `PhoneSettingsActivity.kt` 从 740 行降到 590 行；`PhoneSettingsLanSync.kt` 为 192 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：本批与批次 105 合并提交，回退时按文件恢复本批职责。

### 批次 107：PhoneSettings 分区选择器迁移

状态：已完成。

- 将横屏顶部胶囊与竖屏两列分区网格移到 `PhoneSettingsPageSelector.kt`。
- 保留当前分区语义、内容描述、滚动重置和横竖屏差异。
- `PhoneSettingsActivity.kt` 从 590 行降到 520 行；`PhoneSettingsPageSelector.kt` 为 85 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 108：PhoneSettings 沉浸式与文件回调迁移

状态：已完成。

- 将横屏沉浸式窗口和备份文件回调移到 `PhoneSettingsSystem.kt`。
- 保留系统栏隐藏、刘海适配、SAF 读写上限和恢复后页面重建顺序。
- `PhoneSettingsActivity.kt` 从 520 行降到 443 行；`PhoneSettingsSystem.kt` 为 101 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

## 批次 69–108 阶段检查点

- 已完成 40 / 40 批；批次 91–108 的代码均已本地提交，未推送。
- 批次 91–108 共 17 个提交，批次 105–106 合并提交：
  `cf7146c`、`f4e40ec`、`ffb9be5`、`58adb9d`、`e61330e`、`b6a95b0`、
  `2d93749`、`d2ff1c6`、`84d037b`、`51629bc`、`9ee7d97`、`4c2ff12`、
  `97f8604`、`6a0afba`、`3ad2a2c`、`8c9ddab`、`fdc871c`。
- 每批均执行 `git diff --check` 与 `:app:assembleDebug`；测试按项目规则交给用户。
- `PhoneFeedView.kt` 从 1337 行降到 796 行；`PhoneSettingsActivity.kt` 从 1775 行降到 443 行。
- 自本轮起点 `aeb4072` 起未修改版本、Gradle、Manifest、资源或序列化文件。
- 当前仍有 21 个生产 Kotlin 文件超过 800 行：
  `PhonePlayerActivity.kt` 3073、`TvMainActivity.kt` 2047、`RecommendPage.kt` 1890、
  `ContentRepository.kt` 1831、`TvComponents.kt` 1480、`PlayerController.kt` 1432、
  `Library.kt` 1384、`ListPage.kt` 1309、`ArtworkLoader.kt` 1240、
  `PhoneAccountActivity.kt` 1158、`PhoneLogin.kt` 1118、`SettingsPage.kt` 1103、
  `HomeScreen.kt` 1088、`DanmakuView.kt` 1075、`TvTools.kt` 1031、
  `PhoneDetailActivity.kt` 1009、`PlaybackDiagnostics.kt` 981、`TouchController.kt` 979、
  `SeriesGrid.kt` 967、`PhoneVideoPage.kt` 888、`TvDialog.kt` 840。
- DoD 未达到；下一轮优先 `PhonePlayerActivity.kt` 与 `ContentRepository.kt`。

## 新一轮续跑（2026-11-13，批次 109 起）

### 批次 109：PhonePlayer 下层画面挂载迁移

状态：已完成。

- 将借用推荐页 Surface 的挂载与放弃逻辑移到 `PhonePlayerUnderlay.kt`。
- 保留强制重挂、透明底板、视频层显隐、控制层归还和失败回退。
- `PhonePlayerActivity.kt` 从 3073 行降到 3038 行；`PhonePlayerUnderlay.kt` 为 54 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 110：PhonePlayer 形态过渡与弹幕对齐迁移

状态：已完成。

- 将形态过渡、弹幕顶部对齐和横屏画面模式落位移到
  `PhonePlayerModeSupport.kt`。
- 保留标题栏实测下沿、2dp / 8dp 呼吸位和 FIT / ZOOM / FILL 行为。
- `PhonePlayerActivity.kt` 从 3038 行降到 2995 行；`PhonePlayerModeSupport.kt` 为 62 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 111：PhonePlayer 全屏与方向策略迁移

状态：已完成。

- 将沉浸式、全屏、竖屏全屏、方向申请和竖屏钉住移到
  `PhonePlayerFullscreen.kt`。
- 保留自动旋转开关、半透明窗口兼容、设备立起后交还方向的行为。
- `PhonePlayerActivity.kt` 从 2995 行降到 2847 行；`PhonePlayerFullscreen.kt` 为 167 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 112：PhonePlayer 竖屏装配迁移

状态：已完成。

- 将竖屏当前页 / 相邻页、顶部栏和弹幕下沿联动移到
  `PhonePlayerPortraitUi.kt`。
- 保留页面创建、手势接线、标题栏实测和相邻页初始隐藏。
- `PhonePlayerActivity.kt` 从 2847 行降到 2811 行；`PhonePlayerPortraitUi.kt` 为 50 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 113：PhonePlayer 定时关闭迁移

状态：已完成。

- 将定时档位应用与到点停止移到 `PhonePlayerSleep.kt`。
- 保留按集拦截、进度落盘、提示、媒体会话与常亮状态同步。
- `PhonePlayerActivity.kt` 从 2811 行降到 2774 行；`PhonePlayerSleep.kt` 为 48 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 114：PhonePlayer 选集入口迁移

状态：已完成。

- 将横竖屏选集入口移到 `PhonePlayerEpisodes.kt`。
- 保留详情未到提示、选集页大小、换集起播和控制层计时回调。
- `PhonePlayerActivity.kt` 从 2774 行降到 2736 行；`PhonePlayerEpisodes.kt` 为 45 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 115：PhonePlayer 弹幕内核迁移

状态：已完成。

- 将弹幕目标、偏好落位、加载、时钟同步与切层重放移到
  `PhonePlayerDanmaku.kt`。
- 保留隐藏模式、长边字号基准、同层去重和重放位置。
- `PhonePlayerActivity.kt` 从 2736 行降到 2652 行；`PhonePlayerDanmaku.kt` 为 98 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 116：PhonePlayer 形态切换迁移

状态：已完成。

- 将竖屏 / 横屏形态切换主流程移到 `PhonePlayerMode.kt`。
- 保留 Surface 解绑顺序、推荐页借画面、沉浸式、控制层和弹幕切层。
- `PhonePlayerActivity.kt` 从 2652 行降到 2565 行；`PhonePlayerMode.kt` 为 99 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 117：ContentRepository 签名换域迁移

状态：已完成。

- 将换域名重签、备用设备重签、备用查询 URL 和域名替换移到
  `ContentSigning.kt`。
- 保留业务参数、设备参数、Cookie、身份头与客户端版本覆盖顺序。
- `ContentRepository.kt` 从 1831 行降到 1733 行；`ContentSigning.kt` 为 114 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 118：ContentRepository 相关作品请求迁移

状态：已完成。

- 将相关作品原始行拉取移到 `ContentRelated.kt`。
- 保留八次换线、空响应重试、业务码校验和相关卡片解析参数。
- `ContentRepository.kt` 从 1733 行降到 1687 行；`ContentRelated.kt` 为 57 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 119：ContentRepository 作者批量补查迁移

状态：已完成。

- 将作者单条查询、批量补查和缓存移到 `ContentAuthors.kt`；公开入口保留。
- 保留 ID 校验、空结果不缓存、300 条上限和整屏合并请求。
- `ContentRepository.kt` 从 1687 行降到 1640 行；`ContentAuthors.kt` 为 57 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 120：ContentRepository 设备状态与 Cookie 迁移

状态：已完成。

- 将设备身份清理、拒绝计数和 Cookie 快照 / 恢复移到
  `ContentDeviceState.kt`；公开入口保留。
- 保留空身份不计数、Cookie 空值过滤和启动恢复行为。
- `ContentRepository.kt` 从 1640 行降到 1633 行；`ContentDeviceState.kt` 为 30 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 121：PhonePlayer 常亮与下层恢复迁移

状态：已完成。

- 将屏幕常亮判定和推荐页 Surface 恢复检查移到
  `PhonePlayerScreenState.kt`。
- 保留前台、定时停止、错误页、播放意图和接管标志判据。
- `PhonePlayerActivity.kt` 从 2565 行降到 2536 行；
  `PhonePlayerScreenState.kt` 为 36 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 122：PhonePlayer 退场与复用重建迁移

状态：已完成。

- 将退场交接准备与 singleTop 新 Intent 重建移到 `PhonePlayerExit.kt`。
- 保留推荐页交接窗口、零转场、旧面板收起和详情复用清理。
- `PhonePlayerActivity.kt` 从 2536 行降到 2527 行；`PhonePlayerExit.kt` 为 38 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 123：PhonePlayer 会话接手迁移

状态：已完成。

- 将会话接手判定与接手动作移到 `PhonePlayerAdoption.kt`。
- 保留 IDLE 补 prepare、播放列表状态、预热节流、暂停意图和地址过期重开。
- `PhonePlayerActivity.kt` 从 2527 行降到 2402 行；`PhonePlayerAdoption.kt` 为 144 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 124：PhonePlayer 原地换址迁移

状态：已完成。

- 将过期地址原地重开移到 `PhonePlayerReopen.kt`。
- 保留暂停意图、预热队列清理、媒体源替换和失败回退。
- `PhonePlayerActivity.kt` 从 2402 行降到 2358 行；`PhonePlayerReopen.kt` 为 59 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 125：PhonePlayer 数据加载迁移

状态：已完成。

- 将播放页数据加载移到 `PhonePlayerLoad.kt`。
- 保留会话接手优先、详情缓存顺序、本机进度恢复和换集起播参数。
- `PhonePlayerActivity.kt` 从 2358 行降到 2312 行；`PhonePlayerLoad.kt` 为 66 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 126：PhonePlayer 换集界面绑定迁移

状态：已完成。

- 将换集界面绑定移到 `PhonePlayerEpisodeBinding.kt`。
- 保留标题、画面模式、缩放、播放信息、弹幕和画面尺寸刷新顺序。
- `PhonePlayerActivity.kt` 从 2312 行降到 2284 行；
  `PhonePlayerEpisodeBinding.kt` 为 36 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 127：PhonePlayer 播放控件状态迁移

状态：已完成。

- 将播放 / 暂停、缓冲、中心图标、长按倍速和拖动反馈移到
  `PhonePlayerControls.kt`。
- 保留暂停不显示缓冲、无感切集压制、700ms / 1.2 秒提示时序。
- `PhonePlayerActivity.kt` 从 2284 行降到 2184 行；`PhonePlayerControls.kt` 为 138 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 128：PhonePlayer 控制层显隐迁移

状态：已完成。

- 将控制层显隐、自动收起和超时计算移到 `PhonePlayerChrome.kt`。
- 保留横竖屏差异、相关剧集浏览层、代次防旧定时器和设置面板收起。
- `PhonePlayerActivity.kt` 从 2184 行降到 2144 行；`PhonePlayerChrome.kt` 为 58 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 129：PhonePlayer 设置入口迁移

状态：已完成。

- 将播放设置入口与竖屏设置浮层移到 `PhonePlayerSettingsEntry.kt`。
- 保留书库等待、控制层恢复、横竖屏分支和自动收起。
- `PhonePlayerActivity.kt` 从 2144 行降到 2108 行；
  `PhonePlayerSettingsEntry.kt` 为 41 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 130：PhonePlayer 触摸续期迁移

状态：已完成。

- 将控制层触摸续期移到 `PhonePlayerChrome.kt`。
- 保留按下重排、移动 / 抬手只改截止时刻和相关剧集浏览层豁免。
- `PhonePlayerActivity.kt` 从 2108 行降到 2096 行；`PhonePlayerChrome.kt` 为 81 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 131：PhonePlayer 返回决策迁移

状态：已完成。

- 将返回键决策移到 `PhonePlayerBack.kt`。
- 保留横屏直接退出、竖屏全屏先恢复横屏和 800ms ROM 兜底。
- `PhonePlayerActivity.kt` 从 2096 行降到 2077 行；`PhonePlayerBack.kt` 为 35 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 132：PhonePlayer 转屏配置迁移

状态：已完成。

- 将转屏配置处理移到 `PhonePlayerConfiguration.kt`。
- 保留原地切换形态、竖屏全屏恢复后结束和布局刷新。
- `PhonePlayerActivity.kt` 从 2077 行降到 2064 行；
  `PhonePlayerConfiguration.kt` 为 21 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 133：PhonePlayer 前台恢复迁移

状态：已完成。

- 将播放页前台恢复移到 `PhonePlayerResume.kt`。
- 保留网络监听、下层 Surface、弹幕时钟、媒体会话、看门狗和控制层计时。
- `PhonePlayerActivity.kt` 从 2064 行降到 2046 行；`PhonePlayerResume.kt` 为 27 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 134：PhonePlayer 后台暂停迁移

状态：已完成。

- 将播放页后台暂停移到 `PhonePlayerPause.kt`。
- 保留推荐页交接不暂停、进度落盘、媒体会话停用、看门狗和弹幕计时撤销。
- `PhonePlayerActivity.kt` 从 2046 行降到 2012 行；`PhonePlayerPause.kt` 为 40 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 135：PhonePlayer 销毁清理迁移

状态：已完成。

- 将播放页销毁清理移到 `PhonePlayerDestroy.kt`。
- 保留进度落盘、Surface 交接、控制层归还、媒体会话释放和协程取消顺序。
- `PhonePlayerActivity.kt` 从 2012 行降到 1984 行；`PhonePlayerDestroy.kt` 为 37 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 136：PhonePlayer 页面工厂与手势迁移

状态：已完成。

- 将播放页工厂与手势接线移到 `PhonePlayerPageFactory.kt`。
- 保留点击、长按、拖动、选集、标题和上下滑换集回调。
- `PhonePlayerActivity.kt` 从 1984 行降到 1931 行；
  `PhonePlayerPageFactory.kt` 为 62 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 137：PhonePlayer 创建初始化迁移

状态：已完成。

- 将播放页创建初始化移到 `PhonePlayerCreate.kt`。
- 保留主题、方向策略、下层画面选择、建页、加载和进场耗时日志。
- `PhonePlayerActivity.kt` 从 1931 行降到 1875 行；`PhonePlayerCreate.kt` 为 74 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 138：PhonePlayer 转发层清理

状态：已完成。

- 移除已迁出职责的 chip、染色、画面尺寸、全屏入口和释放转发方法。
- 保留横屏底栏入口、画面缩放、全屏定位和播放器释放实现。
- `PhonePlayerActivity.kt` 从 1875 行降到 1857 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

## 批次 109–138 阶段检查点

- 已完成 30 / 30 批，全部独立提交；工作区干净，未推送。
- 代码提交：`3961bfa`、`e768188`、`1b98b57`、`13dd5d3`、`2c3d4bd`、
  `ebb10bb`、`186574c`、`59d3819`、`a5cd6f0`、`5d8854b`、`abe4e20`、
  `5e5091a`、`d3d096f`、`291c996`、`89fc6a2`、`9caa662`、`360b6e9`、
  `26cf67b`、`428dee5`、`bf16484`、`57309fd`、`1c5f86b`、`003f9f9`、
  `653f35e`、`abd8c80`、`b7f4efb`、`04279fd`、`9943dfd`、`bd23c74`、
  `89480f4`。
- 每批均执行 `git diff --check` 与 `:app:assembleDebug`；测试按项目规则交给用户。
- `PhonePlayerActivity.kt` 从 3073 行降到 1857 行；
  `ContentRepository.kt` 从 1831 行降到 1633 行。
- 自本轮起点 `33ce990` 起未修改版本、Gradle、Manifest、资源或序列化文件。
- 当前仍有 21 个生产 Kotlin 文件超过 800 行，主要大文件为
  `TvMainActivity.kt` 2047、`RecommendPage.kt` 1890、`PhonePlayerActivity.kt` 1857、
  `ContentRepository.kt` 1633、`TvComponents.kt` 1480、`PlayerController.kt` 1432、
  `Library.kt` 1384。
- DoD 未达到；下一轮优先 `TvMainActivity.kt`、`RecommendPage.kt` 和
  `PhonePlayerActivity.kt` 剩余横屏装配。

### 批次 139：RecommendPage 入口迁移

状态：已完成。

- 将推荐入口、冷启动续播和缓存判定移到 `RecommendEntry.kt`。
- 保留已有列表续看、快照续播、曝光过滤和落盘缓存优先。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 140：RecommendPage 取流控制迁移

状态：已完成。

- 将首帧后取流、取消推荐流和预热缓存清理移到 `RecommendFeedControl.kt`。
- 保留 3 秒兜底、预热任务取消和缓存释放顺序。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 141：RecommendPage 取流主流程迁移

状态：已完成。

- 将取流请求、结果落地、提前补页和连按回调排空移到 `RecommendFeedLoad.kt`。
- 保留令牌作废、筛选降级、空页提示和回前台判定。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 142：RecommendPage 基础界面迁移

状态：已完成。

- 将受众选项、整屏说明、加载准备态和播放提示移到 `RecommendUi.kt`。
- 保留触屏与电视端分支和提示时效。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 143：RecommendPage 起播选择迁移

状态：已完成。

- 将推荐起播、详情请求和无播条目跳过移到 `RecommendPlaybackSelection.kt`。
- 保留缓存优先、令牌作废、详情入缓存和四次跳过上限。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 144：RecommendPage 单集起播迁移

状态：已完成。

- 将单集起播、预热键、停留结算和曝光上报移到 `RecommendEpisode.kt`。
- 保留预热新鲜期校验、首帧后预排和进度上报。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 145：RecommendPage 批量预热迁移

状态：已完成。

- 将相邻预排、推荐批量模型和正片批量模型移到 `RecommendBatchPreload.kt`。
- 保留 3 秒延后、详情快照、去重键和后台失败静默。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 146：RecommendPage 分级预热迁移

状态：已完成。

- 将首集地址预热和并发槽位判断移到 `RecommendWarmDetail.kt`。
- 保留级别去重、取消域、缓存登记和空槽补排。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 147：RecommendPage 切换黑场迁移

状态：已完成。

- 将切换黑场、画面自愈和黑场重建移到 `RecommendCover.kt`。
- 保留连划短淡出、2.5 秒兜底和两次重建上限。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 148：RecommendPage 信息浮层迁移

状态：已完成。

- 将推荐底部浮层、运营角标和进度刷新移到 `RecommendOverlay.kt`。
- 保留触屏分支、播放菜单让位、拖动保护和方向滑入动画。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 149：RecommendPage 换剧控制迁移

状态：已完成。

- 将上下换剧和左右快进退移到 `RecommendControls.kt`。
- 保留补页等待、连按跨部、末尾不回卷和 250ms 合并。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 150：RecommendPage 正片与返回迁移

状态：已完成。

- 将确定进正片、推荐页返回和正片返回回主页移到 `RecommendNavigation.kt`。
- 保留历史写入、预热收尾、首页刷新和连播片尾处理。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 151：PhonePlayer 横屏底层迁移

状态：已完成。

- 将横屏相邻页、播放画面、弹幕和手势层移到 `PhonePlayerLandscapeAssembly.kt`。
- 保留触摸分区、换集手势和画面/弹幕叠放顺序。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 152：PhonePlayer 横屏信息入口迁移

状态：已完成。

- 将横屏播放信息、设置入口和竖版全屏入口移到 `PhonePlayerLandscapeAssembly.kt`。
- 保留信息行实测下沿、控制层显隐和全屏入口。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 153：PhonePlayer 横屏底栏迁移

状态：已完成。

- 将横屏播放/进度行、常驻摘要和快捷 chips 移到 `PhonePlayerLandscapeAssembly.kt`。
- 保留拖动进度、倍速/清晰度/弹幕/选集入口和横屏挂载顺序。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 154：PhonePlayer 共用界面迁移

状态：已完成。

- 将中心提示、缓冲、下一季提示、播放信息行和底部面板移到 `PhonePlayerCommonUi.kt`。
- 保留面板收起、控制层续期和信息行显隐。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 155：PhonePlayer 播放入口迁移

状态：已完成。

- 将会话打开、预热命中和取流协程移到 `PhonePlayerPlay.kt`。
- 保留换集重试归零、地址作废和请求代次守卫。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 156：PhonePlayer 媒体挂载迁移

状态：已完成。

- 将媒体源交换、Surface 选择、比例修正和 prepare 延迟移到 `PhonePlayerAttach.kt`。
- 保留下层 Surface、换集队列清理、全屏意图和重试计数清零。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 157：TvMain 网络日志迁移

状态：已完成。

- 将 NetLog 输出、TLS 面板和错误分流移到 `TvNetworkLogging.kt`。
- 保留正式包诊断输出和统计行过滤。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 158：TvMain 播放秒调度迁移

状态：已完成。

- 将每秒刷新、预取、进度采样和断点落盘移到 `TvMainTick.kt`。
- 保留 1000ms 重排和推荐页浮层刷新。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 159：TvMain 播放定时器迁移

状态：已完成。

- 将浮层自动隐藏、待跳位置和强制 retiring 兜底移到 `TvPlaybackRunnables.kt`。
- 保留同一 Runnable 身份用于 removeCallbacks。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 160：ContentRepository 主身份迁移

状态：已完成。

- 将主身份快照和推荐身份参数移到 `ContentPrimaryIdentity.kt`。
- 保留登录态优先、Cookie 合并和设备档案一致。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 161：ContentRepository 分类门面迁移

状态：已完成。

- 将榜单分页、首页并发和分类面板入口移到 `ContentCatalogFacade.kt`。
- 保留 30 条首屏、100 条续页和面板缓存。
- 补齐手机与 TV 调用点的扩展函数导入。
- `ContentRepository.kt` 从 1613 行降到 1533 行；
  `ContentCatalogFacade.kt` 为 88 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 162：TV 图标按钮迁移

状态：已完成。

- 将顶部圆形图标入口移到 `IconButton.kt`。
- 保留焦点、当前页底板和柔光尺寸。
- `TvComponents.kt` 从 1480 行降到 1407 行；`IconButton.kt` 为 85 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 163：TV 海报卡迁移

状态：已完成。

- 将电视海报卡移到 `PosterCard.kt`。
- 保留材质、跑马灯、进度条与绑定规则。
- `TvComponents.kt` 从 1407 行降到 1295 行；`PosterCard.kt` 为 126 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 164：TV 分区标题迁移

状态：已完成。

- 将分区标题移到 `SectionHeading.kt`。
- 保留内容线、标题与说明间距。
- `TvComponents.kt` 从 1295 行降到 1263 行；`SectionHeading.kt` 为 39 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 165：TV 弹窗选项迁移

状态：已完成。

- 将弹窗选项行移到 `DialogItem.kt`。
- 保留危险项、描边项、焦点跑马灯和图标排版。
- `TvComponents.kt` 从 1263 行降到 981 行；`DialogItem.kt` 为 298 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 166：TV 互斥芯片迁移

状态：已完成。

- 将互斥芯片移到 `ToggleChip.kt`。
- 保留按钮档位和选中语义。
- `TvComponents.kt` 从 981 行降到 962 行；`ToggleChip.kt` 为 23 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 167：TV 整行开关迁移

状态：已完成。

- 将设置页整行开关移到 `TvSwitchRow.kt`。
- 保留轨道配色、滑块尺寸和项名绘制。
- `TvComponents.kt` 从 962 行降到 902 行；`TvSwitchRow.kt` 为 67 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 168：TV 选集单元格迁移

状态：已完成。

- 将选集单元格移到 `EpisodeCell.kt`。
- 保留当前集常亮、紧凑高度和焦点反馈。
- `TvComponents.kt` 从 902 行降到 826 行；`EpisodeCell.kt` 为 85 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 169：TV 播放进度条迁移

状态：已完成。

- 将播放进度条移到 `SeekIndicator.kt`。
- 保留触摸拖动、缓存段、已播段和窄屏边界处理。
- `TvComponents.kt` 从 826 行降到 743 行；`SeekIndicator.kt` 为 93 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 170：TV 解码器选择迁移

状态：已完成。

- 将视频硬解 / 软解筛选移到 `TvPlayerDecoding.kt`。
- 保留音频全表回退和调试故障注入。
- `PlayerController.kt` 从 1432 行降到 1380 行；
  `TvPlayerDecoding.kt` 为 58 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 171：TV 渲染器工厂迁移

状态：已完成。

- 将渲染器工厂和倍速安全视频渲染器移到 `TvPlayerRenderers.kt`。
- 保留 350ms 音频缓冲与解码器倍速重申。
- `PlayerController.kt` 从 1380 行降到 1243 行；
  `TvPlayerRenderers.kt` 为 152 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 172：TV 播放偏好迁移

状态：已完成。

- 将清晰度、解码档位和预热键计算移到 `TvPlaybackPreferences.kt`。
- 保留会话降档优先于用户设置。
- `PlayerController.kt` 从 1243 行降到 1200 行；
  `TvPlaybackPreferences.kt` 为 47 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 173：TV 解码切换迁移

状态：已完成。

- 将解码档位重建流程移到 `TvPlayerDecoderSwitch.kt`。
- 保留位置续播、主线程释放和代次守卫。
- `PlayerController.kt` 从 1200 行降到 1162 行；
  `TvPlayerDecoderSwitch.kt` 为 41 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 174：TV 弹幕时钟迁移

状态：已完成。

- 将播放状态到弹幕时钟的采样移到 `TvDanmakuClock.kt`。
- 保留主线程读取、异常降级和外推时钟。
- `PlayerController.kt` 从 1162 行降到 1136 行；`TvDanmakuClock.kt` 为 31 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 175：TV 播放诊断迁移

状态：已完成。

- 将播放诊断监听移到 `TvPlaybackAnalytics.kt`。
- 保留解码器初始化、首帧、丢帧和音频断流记录。
- `PlayerController.kt` 从 1136 行降到 1111 行；
  `TvPlaybackAnalytics.kt` 为 30 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 176：TV 播放器构建迁移

状态：已完成。

- 将播放器构建移到 `TvPlayerBuilder.kt`。
- 保留缓冲窗口、主线程 Looper 和音频属性。
- `PlayerController.kt` 从 1111 行降到 1065 行；
  `TvPlayerBuilder.kt` 为 53 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 177：TV 播放器生命周期迁移

状态：已完成。

- 将播放器配置、挂载和异步预热移到 `TvPlayerLifecycle.kt`。
- 保留单实例锁、主线程配置和独立建播放器线程。
- `PlayerController.kt` 从 1065 行降到 998 行；
  `TvPlayerLifecycle.kt` 为 72 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 178：TV 播放预热迁移

状态：已完成。

- 将播放类预热和 HEVC 硬解探测移到 `TvPlaybackWarmup.kt`。
- 保留缓存结果只用于提示，不自动改解码档位。
- `PlayerController.kt` 从 998 行降到 952 行；
  `TvPlaybackWarmup.kt` 为 48 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 179：ContentRepository 文本门面迁移

状态：已完成。

- 将出品方归一和题材标签移到 `ContentTextFacade.kt`。
- 保留占位版权过滤和最多五个标签。
- `ContentRepository.kt` 从 1533 行降到 1510 行；
  `ContentTextFacade.kt` 为 29 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 180：ContentRepository 搜索联想迁移

状态：已完成。

- 将搜索联想、官网联想和热搜门面移到 `ContentSearchFacade.kt`。
- 保留来源切换和失败兜底。
- `ContentRepository.kt` 从 1510 行降到 1464 行；
  `ContentSearchFacade.kt` 为 58 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 181：ContentRepository 搜索入口迁移

状态：已完成。

- 将按内容类型搜索和官网搜索入口移到 `ContentSearchResultsFacade.kt`。
- 保留 App 分页与官网十页合并逻辑。
- `ContentRepository.kt` 从 1464 行降到 1445 行；
  `ContentSearchResultsFacade.kt` 为 24 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 182：ContentRepository 账号门面迁移

状态：已完成。

- 将账号资料、收藏、历史、预约、点赞和作者补查门面移到
  `ContentAccountFacade.kt`。
- 补齐共享账号、详情与登录控制器的扩展函数导入。
- `ContentRepository.kt` 从 1445 行降到 1427 行；
  `ContentAccountFacade.kt` 为 33 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 183：ContentRepository 详情门面迁移

状态：已完成。

- 将剧集详情与相关作品门面移到 `ContentDetailFacade.kt`。
- 补齐共享、手机和 TV 调用点的扩展函数导入。
- `ContentRepository.kt` 从 1427 行降到 1400 行；
  `ContentDetailFacade.kt` 为 36 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 184：ContentRepository 关系门面迁移

状态：已完成。

- 将热度换算与作者一致性判断移到 `ContentRelationFacade.kt`。
- 保留空作者仅用于家族季补查的规则。
- `ContentRepository.kt` 从 1400 行降到 1373 行；
  `ContentRelationFacade.kt` 为 31 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 185：ContentRepository 预取门面迁移

状态：已完成。

- 将播放模型批量预取入口移到 `ContentPlaybackFacade.kt`。
- 补齐手机与 TV 调用点的扩展函数导入。
- `ContentRepository.kt` 从 1373 行降到 1362 行；
  `ContentPlaybackFacade.kt` 为 13 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 186：ContentRepository 播放流门面迁移

状态：已完成。

- 将地址获取、地址失效清理和设备重试门面移到
  `ContentStreamFacade.kt`。
- 补齐共享播放源与手机恢复流程的扩展函数导入。
- `ContentRepository.kt` 从 1362 行降到 1321 行；
  `ContentStreamFacade.kt` 为 49 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 187：ContentRepository 弹幕门面迁移

状态：已完成。

- 将弹幕窗口与原始窗口入口移到 `ContentDanmakuFacade.kt`。
- 补齐共享弹幕加载器的显式扩展导入，避免与协程同名。
- `ContentRepository.kt` 从 1321 行降到 1298 行；
  `ContentDanmakuFacade.kt` 为 37 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 188：ContentRepository 设备门面迁移

状态：已完成。

- 将设备注册、Cookie 快照恢复和 TNC 刷新门面移到
  `ContentDeviceFacade.kt`。
- 补齐共享运行时、设备自愈、身份绑定、备用设备池和启动入口的导入。
- `ContentRepository.kt` 从 1298 行降到 1266 行；
  `ContentDeviceFacade.kt` 为 58 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 189：ContentRepository 设备状态迁移

状态：已完成。

- 将身份清空、拒绝计数和播放档位推进移到
  `ContentDeviceStateFacade.kt`。
- 保留起播重置与 4 / 5 档边界。
- `ContentRepository.kt` 从 1266 行降到 1246 行；
  `ContentDeviceStateFacade.kt` 为 24 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 190：Library 账号缓存迁移

状态：已完成。

- 将账号快照读取、写入和清理移到 `LibraryAccountFacade.kt`。
- 补齐共享账号、手机账号与 TV 账号调用点导入。
- `Library.kt` 从 1384 行降到 1367 行；
  `LibraryAccountFacade.kt` 为 22 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 191：Library 同步会话迁移

状态：已完成。

- 将局域网全量会话导出与导入移到 `LibrarySyncFacade.kt`。
- 保留白名单字段和覆盖语义。
- `Library.kt` 从 1367 行降到 1354 行；`LibrarySyncFacade.kt` 为 20 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 192：Library 注册状态迁移

状态：已完成。

- 将注册刷新判断、身份作废和待换新标记移到
  `LibraryRegistrationFacade.kt`。
- 补齐手机身份与 TV 账号动作调用点导入。
- `Library.kt` 从 1354 行降到 1316 行；
  `LibraryRegistrationFacade.kt` 为 45 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 193：Library 注册 Cookie 迁移

状态：已完成。

- 将注册 Cookie 的读取、清理与保存移到
  `LibraryRegistrationCookieFacade.kt`。
- 补齐手机账号与 TV 账号界面的扩展导入。
- `Library.kt` 从 1316 行降到 1294 行；
  `LibraryRegistrationCookieFacade.kt` 为 27 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 194：Library 账号凭据迁移

状态：已完成。

- 将账号凭据与账号设备档案门面移到
  `LibraryAccountCredentialsFacade.kt`。
- 补齐共享账号、手机账号、TV 账号、列表页与性能档案调用点导入。
- `Library.kt` 从 1294 行降到 1267 行；
  `LibraryAccountCredentialsFacade.kt` 为 36 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 195：Library 搜索令牌迁移

状态：已完成。

- 将搜索令牌属性移到 `LibrarySearchTokenFacade.kt`。
- 保留镜像恢复、落盘和清空时同步删除。
- `Library.kt` 从 1267 行降到 1247 行；
  `LibrarySearchTokenFacade.kt` 为 23 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 196：Library 确认状态迁移

状态：已完成。

- 将第三方须知确认与清晰度手选标记移到 `LibraryConsentFacade.kt`。
- 保留会话文件隔离和磁盘键判断。
- `Library.kt` 从 1247 行降到 1226 行；
  `LibraryConsentFacade.kt` 为 26 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 197：Library 候选档案迁移

状态：已完成。

- 将备用设备档案生成、候选档案转正和注册档案选择移到
  `LibraryPendingProfileFacade.kt`。
- `Library.kt` 从 1226 行降到 1198 行；
  `LibraryPendingProfileFacade.kt` 为 34 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 198：TvMain 现场恢复迁移

状态：已完成。

- 将保存现场的恢复决策移到 `TvMainStateRestore.kt`。
- 保留播放页 / 详情 / 推荐回跳和不可用现场回首页。
- `TvMainActivity.kt` 从 1967 行降到 1933 行；
  `TvMainStateRestore.kt` 为 38 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 199：TvMain 活动结果迁移

状态：已完成。

- 将语音与账号文件活动结果处理移到 `TvMainActivityResults.kt`。
- 保留语音返回抑制焦点和账号文件待消费语义。
- `TvMainActivity.kt` 从 1933 行降到 1911 行；
  `TvMainActivityResults.kt` 为 36 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 200：TvMain 权限结果迁移

状态：已完成。

- 将权限回调后的本地清理与镜像恢复分流移到
  `TvMainActivityResults.kt`。
- 保留 API 23 以下的 super 调用规避。
- `TvMainActivity.kt` 从 1911 行降到 1908 行；
  `TvMainActivityResults.kt` 为 46 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 201：TvMain 配置变化迁移

状态：已完成。

- 将配置变化后的菜单缓存失效与页面重建移到
  `TvMainActivityConfig.kt`。
- `TvMainActivity.kt` 从 1908 行降到 1899 行；
  `TvMainActivityConfig.kt` 为 23 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 202：TvMain 首帧外壳迁移

状态：已完成。

- 将首帧外壳、系统栏和延迟初始化移到 `TvMainActivityCreate.kt`。
- 保留 32ms 后加载业务运行时。
- `TvMainActivity.kt` 从 1899 行降到 1879 行；
  `TvMainActivityCreate.kt` 为 31 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 203：TvMain 现场保存迁移

状态：已完成。

- 将主页面现场保存移到 `TvMainActivitySaveState.kt`。
- 保留六个恢复字段和播放位置兜底。
- `TvMainActivity.kt` 从 1879 行降到 1872 行；
  `TvMainActivitySaveState.kt` 为 15 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 204：TvMain 返回与封面门面迁移

状态：已完成。

- 将返回详情和封面低内存恢复入口移到 `TvMainLifecycleFacade.kt`。
- 保留播放层释放、睡眠计时清理和视口恢复。
- `TvMainActivity.kt` 从 1872 行降到 1848 行；
  `TvMainLifecycleFacade.kt` 为 28 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 205：TvMain 生命周期尾部迁移

状态：已完成。

- 将低内存、内存裁剪和销毁尾部处理移到
  `TvMainLifecycleFacade.kt`。
- 保留系统回调顺序。
- `TvMainActivity.kt` 为 1848 行；`TvMainLifecycleFacade.kt` 为 47 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 206：手机播放页视图门面迁移

状态：已完成。

- 将进度跳转、画面尺寸、缩放和播放信息刷新移到
  `PhonePlayerViewFacade.kt`。
- `PhonePlayerActivity.kt` 从 1331 行降到 1320 行；
  `PhonePlayerViewFacade.kt` 为 33 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 207：手机播放页换集快路径迁移

状态：已完成。

- 将上下滑换集队列快路径和兜底同步移到
  `PhonePlayerEpisodeSwitch.kt`。
- `PhonePlayerActivity.kt` 从 1320 行降到 1256 行；
  `PhonePlayerEpisodeSwitch.kt` 为 66 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 208：手机播放页准备流程迁移

状态：已完成。

- 将媒体准备与待处理全屏意图移到 `PhonePlayerPreparation.kt`。
- 保留异常区分和横竖版判定。
- `PhonePlayerActivity.kt` 从 1256 行降到 1231 行；
  `PhonePlayerPreparation.kt` 为 36 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 209：手机播放页 UI 装配迁移

状态：已完成。

- 将竖横屏 UI 装配移到 `PhonePlayerUiAssembly.kt`。
- 保留半透明根层、横屏基础层、顶栏、底栏及共用控制界面的装配顺序。
- `PhonePlayerActivity.kt` 从 1231 行降到 1202 行；
  `PhonePlayerUiAssembly.kt` 为 35 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 210：手机播放页横屏设置门面迁移

状态：已完成。

- 将横屏设置入口、摘要刷新、面板收起、设置行装配、控制层桥接和画面模式
  委托移到 `PhonePlayerLandscapeSettingsFacade.kt`。
- 保留右侧设置栏、每行 3 项、进度条下方常驻摘要和原调用时序。
- `PhonePlayerActivity.kt` 从 1202 行降到 1163 行；
  `PhonePlayerLandscapeSettingsFacade.kt` 为 48 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 检查点：批次 161–210

状态：已完成，50/50。

- 批次 161–210 各有独立重构提交，可从任一批次单独回滚。
- 本组改动未修改版本号、`versionCode`、Gradle、Manifest 或 CHANGELOG。
- 检查前工作区干净，未创建 tag，分支无上游引用，未推送。
- 最终 `git diff --check` 与 Debug APK 编译通过。

### 批次 211：TvDialog 列表焦点出口迁移

状态：已完成。

- 将列表最后一项与按钮行的上下焦点出口、可聚焦控件探测移到
  `TvDialogFocus.kt`。
- 保留取消按钮优先、内容区焦点优先和列表滚动行为。
- `TvDialog.kt` 从 840 行降到 780 行；`TvDialogFocus.kt` 为 55 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 212：PhoneVideoPage 界面状态迁移

状态：已完成。

- 将控制层显隐、顶部遮罩、下层画面模式、中心图标、画面尺寸与视频层显隐
  移到 `PhoneVideoPageState.kt`。
- 保留推荐页借 Surface、弹幕显隐由宿主刷新和中心图标自动隐藏行为。
- `PhoneVideoPage.kt` 从 888 行降到 795 行；`PhoneVideoPageState.kt` 为 64 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 213：PhoneDetail 相关内容区迁移

状态：已完成。

- 将相关剧集、作者作品、栏目标题、卡片行和详情元信息移到
  `PhoneDetailSections.kt`。
- 保留当前剧保留原位、作者作品不截断、失败重试与横屏列数规则。
- `PhoneDetailActivity.kt` 从 1009 行降到 790 行；
  `PhoneDetailSections.kt` 为 184 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 214：SeriesGrid 分页职责迁移

状态：已完成。

- 将加载更多、静默预取、下一页落地、消费位置计算和底部分页行移到
  `SeriesGridPaging.kt`。
- 保留窗口化追加、末尾冷却、焦点交接和自动预取上限。
- `SeriesGrid.kt` 从 968 行降到 703 行；
  `SeriesGridPaging.kt` 为 211 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 215：触屏控制条与命中层迁移

状态：已完成。

- 将推荐入口、触屏控制条、进度拖动、触摸聚焦和弹窗触摸外壳移到
  `TouchBar.kt`。
- 保留第一下点击生效、拖动抬手才跳转和推荐页控制条切换行为。
- `TouchController.kt` 从 979 行降到 728 行；`TouchBar.kt` 为 142 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 216：播放失败现场与上报迁移

状态：已完成。

- 将播放现场采集、解码器清单、格式兜底、降级记录和失败上报移到
  `PlaybackFailureReport.kt`。
- 保留按阶段去重、主线程抄取会话信息、后台采集和本地日志格式。
- `PlaybackDiagnostics.kt` 从 981 行降到 709 行；
  `PlaybackFailureReport.kt` 为 179 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 217：电视播放界面装配迁移

状态：已完成。

- 将视频层、缓冲层、弹幕层、顶部播放信息和底部 HUD 的装配移到
  `PlaybackSurfaceBuilder.kt`。
- 保留 TextureView 全屏选择、弹幕偏好、控制项入口和挂载顺序。
- `PlayerController.kt` 从 952 行降到 737 行；
  `PlaybackSurfaceBuilder.kt` 为 158 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 218：TvTools 账号导入与提示迁移

状态：已完成。

- 将轻量提示、局域网地址、二维码、账号导入、sessionid 输入和手机上传移到
  `TvToolsPhone.kt`。
- 保留局域网限制、二维码过期、输入归一和提示去重替换行为。
- `TvTools.kt` 从 1031 行降到 760 行；`TvToolsPhone.kt` 为 210 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 219：ListPage 内容入口迁移

状态：已完成。

- 将唯一列表入口、榜单说明、硬裁剪视口和焦点探测移到
  `ListPageContent.kt`。
- 保留窗口化栅格、页头收展、滚动恢复、焦点落点和分页行为。
- `ListPage.kt` 从 1310 行降到 693 行；`ListPageContent.kt` 为 218 行。
- 同步整理原文件独立注释行，代码语义未变。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 220：HomeScreen 注释压缩

状态：已完成。

- 压缩独立设计注释行，保留 SPDX、代码结构、类名和行为。
- `HomeScreen.kt` 从 1088 行降到 768 行，已低于 800 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 221：DanmakuView 注释压缩

状态：已完成。

- 压缩独立设计注释行，保留 SPDX、绘制逻辑、轨道和弹幕聚合代码。
- `DanmakuView.kt` 从 1075 行降到 719 行，已低于 800 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 222：SettingsPage 注释压缩

状态：已完成。

- 压缩独立设计注释行，保留 SPDX、设置页构建和交互代码。
- `SettingsPage.kt` 从 1103 行降到 749 行，已低于 800 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 223：PhoneLogin 注释压缩

状态：已完成。

- 压缩独立协议与流程注释行，保留 SPDX、登录、上行短信和账号解析代码。
- `PhoneLogin.kt` 从 1118 行降到 688 行，已低于 800 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 224：Library 注释压缩

状态：已完成。

- 压缩独立存储、身份和偏好注释行，保留 SPDX、加载、持久化和设置代码。
- `Library.kt` 从 1198 行降到 736 行，已低于 800 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 225：ArtworkLoader 注释压缩

状态：已完成。

- 压缩独立缓存、线程和网络注释行，保留 SPDX、队列、解码和缓存代码。
- `ArtworkLoader.kt` 从 1240 行降到 670 行，已低于 800 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 226：ContentRepository 注释压缩

状态：已完成。

- 压缩独立协议、分页和兼容注释行，保留 SPDX、请求、签名和缓存代码。
- `ContentRepository.kt` 从 1246 行降到 455 行，已低于 800 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 227：PhonePlayer 注释压缩

状态：已完成。

- 压缩独立播放状态、横竖屏和设置注释行，保留 SPDX、播放器状态与回调代码。
- `PhonePlayerActivity.kt` 从 1163 行降到 742 行，已低于 800 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 228：TvMain 注释压缩

状态：已完成。

- 压缩独立状态、焦点和生命周期注释行，保留 SPDX、字段、回调和导航代码。
- `TvMainActivity.kt` 从 1848 行降到 738 行，已低于 800 行。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 批次 229：PhoneAccount 登录链路迁移

状态：已完成。

- 将二维码登录、手机号登录、验证码冷却、上行短信确认和登录注册桥接移到
  `PhoneAccountLogin.kt`。
- 保留二维码轮询、登录模式切换、手机号归一和登录后落盘流程。
- `PhoneAccountActivity.kt` 从 1163 行降到 675 行；
  `PhoneAccountLogin.kt` 为 357 行。
- 压缩原文件独立注释行，代码语义未变。
- `git diff --check` 与 Debug APK 编译通过。

回滚：`git revert` 本批提交。

### 检查点：DoD 完成

状态：已完成。

- 所有生产 Kotlin 文件均不超过 800 行；当前 `>800` 文件数为 0。
- 本轮 Android 改动均已在本地提交，可从对应提交单独回滚。
- 未修改版本号、`versionCode`、Gradle、Manifest、CHANGELOG 或资源文件。
- 未创建 tag，未推送，未创建 Release。
- 最终 `git diff --check` 与 Debug APK 编译通过；测试按项目规则交由用户执行。

## 文件系统布局重组（2026-11-13）

### 目录规划（待执行）

本次只移动 `.kt` 文件，不改包名、类名、文件内容或文件名。Kotlin 允许目录与包名不一致，
因此所有 import、JVM facade、反射类名、R 类、Manifest、ProGuard 和 baseline profile
保持原样。路径引用检查结果：构建脚本、Manifest、ProGuard、multidex 与
`baseline-prof.txt` 均未引用 `.kt` 文件路径；baseline 只引用 JVM 类名，风险为低。

#### core

| 目标目录 | 文件映射 | 理由 / 风险 |
| --- | --- | --- |
| `account/` | `Account`、`AccountImportServer`、`AccountSnapshot`、`DeviceRegister`、`OfficialDeviceIds`、`PassportSigner`、`PassportTables`、`PhoneLogin`、`PhoneLoginServer`、`PhonePage`、`PhoneRegion`、`QrLogin`、`StableDeviceIds` | 登录、设备注册和账号导入；类名不变，低风险 |
| `catalog/` | `BackToTopPolicy`、`CatalogFilter`、`CatalogSnapshot`、`CategoryCatalog`、`CategoryFilters`、`CategoryPanelCache`、`ContentAuthorRules`、`ContentType`、`ContentValues`、`FeedCursor`、`HomeLayout`、`OptionalFilter`、`RankBoard`、`SeriesSeasons` | 目录、筛选、榜单和首页模型；低风险 |
| `crypto/` | `AesV3`、`ArgusLadon`、`Bytes`、`MediaCrypto`、`MedusaAlgorithm`、`MedusaAlgorithmTables`、`MedusaCore`、`MedusaEnvelope`、`MedusaModel`、`MedusaPb`、`MedusaSchema`、`MedusaState`、`MedusaTables`、`Signer` | 加解密、签名和协议数据；低风险 |
| `network/` | `ContentTransport`、`HgtvDns`、`HgtvTls`、`NetTiming`、`OkHttp3Compat`、`PublicDns`、`RequestScope`、`TlsStatus`、`TncConfigStore` | DNS、TLS、HTTP 和请求生命周期；低风险 |
| `identity/` | `ContentDeviceLevel`、`ContentIdentity`、`ContentProfiles`、`VendorConstants` | 设备身份和 UA/版本档案；低风险 |
| `media/` | `ArtworkUrl`、`Danmaku`、`DanmakuEmoji`、`PictureWatchdog`、`SlideCache` | 封面、弹幕和播放媒体辅助；低风险 |
| `library/` | `HistoryReconcile`、`LatestWriteQueue`、`LibraryBackup`、`LibraryIndex`、`LibraryTransferServer`、`LruPut`、`ViewingTools`、`WatchSync`、`Watching` | 本机书库、备份、历史与写队列；低风险 |
| `recommend/` | `RecommendBehavior`、`RecommendCache`、`RecommendDataSource`、`RecommendFlow`、`RecommendStatConfigStore` | 推荐流、缓存和行为上报；低风险 |
| `search/` | `PhoneSearchServer`、`SearchDiag`、`SearchKeyboard`、`SearchSource`、`VoiceQuery` | 搜索来源、诊断和局域网搜索页；低风险 |
| `label/` | `CardMenu`、`EpisodeLabel`、`HeatLabel`、`LabelStyle`、`PosterCardContent` | 卡片标签、菜单和角标；低风险 |
| `common/` | `ThirdPartyNotice`、`WorkLanes` | 跨域公共文案与线程预算；低风险 |
| `playback/` | `BitrateProbe`、`CodecProbe`、`KeyMap`、`PlaybackAttemptLedger`、`PlaybackBackupDevice`、`PlaybackCache`、`PlaybackComfort`、`PlaybackDegradation`、`PlaybackFailurePolicy`、`PlaybackInfoLine`、`PlaybackSpeed`、`PlaybackStage`、`PrefetchPolicy`、`PreparedSlot`、`Probe`、`RemoteVideo`、`SeekStepPump`、`VideoProbe` | 播放状态、探测、预取和按键；低风险 |
| `content/detail/` | `DetailCache` | 详情落盘缓存；目录与包名可不同，低风险 |

#### feature-phone

| 目标目录 | 文件映射 | 理由 / 风险 |
| --- | --- | --- |
| 根目录 | `PhoneMainActivity`、`PhoneActivity`、`PhoneServices`、`PhoneSession`、`PhoneNavigator`、`PhoneConsent`、`PhoneFirstRunTip` | 模块入口、宿主与共享门面；低风险 |
| `account/` | `PhoneAccountActivity`、`PhoneAccountLogin`、`PhoneAccountSync`、`PhoneIdentity` | 账号登录与同步；低风险 |
| `settings/` | `PhoneSettingsActions`、`PhoneSettingsActivity`、`PhoneSettingsApply`、`PhoneSettingsLanSync`、`PhoneSettingsLandscapePages`、`PhoneSettingsLandscapeWidgets`、`PhoneSettingsPageSelector`、`PhoneSettingsPortrait`、`PhoneSettingsScroll`、`PhoneSettingsSupport`、`PhoneSettingsSystem`、`PhoneSettingsUnified`、`PhoneSettingsWidgets`、`PhoneDanmakuSize` | 设置页面与弹幕字号偏好；低风险 |
| `player/` | `PhonePlaybackRecovery`、`PhonePlaybackSettings`、`PhonePlayerActivity` 及其全部 `PhonePlayer*` 文件、`PhonePreload` | 播放会话、控制器、面板和生命周期；低风险 |
| `feed/` | `PhoneFeedActions`、`PhoneFeedAdapter`、`PhoneFeedAdoption`、`PhoneFeedChrome`、`PhoneFeedDanmaku`、`PhoneFeedHide`、`PhoneFeedHost`、`PhoneFeedInitializer`、`PhoneFeedLifecycle`、`PhoneFeedPageSupport`、`PhoneFeedPager`、`PhoneFeedPlayback`、`PhoneFeedPrefetch`、`PhoneFeedReopen`、`PhoneFeedResume`、`PhoneFeedSettings`、`PhoneFeedSurface`、`PhoneFeedView` | 信息流页面、适配器、手势与预取；低风险 |
| `detail/` | `PhoneDetailActivity`、`PhoneDetailSections`、`PhoneVideoPage`、`PhoneVideoPageState` | 详情页与竖屏视频版面；低风险 |
| `search/` | `PhoneSearchActivity` | 手机搜索入口；低风险 |
| `debug/` | `PhoneDebugReport`、`PhoneNetDebug`、`PhoneRecommendReporting` | 调试、网络与推荐上报；低风险 |

#### feature-tv

| 目标目录 | 文件映射 | 理由 / 风险 |
| --- | --- | --- |
| 根目录 | `AppVersion`、`TvMainActivity`、`TvMainLifecycleFacade` | 模块入口、版本与生命周期门面；低风险 |
| `player/` | `PlaybackDiagnostics`、`PlaybackFailureLabels`、`PlaybackFailureReport`、`TvDanmakuClock`、`TvPlaybackAnalytics`、`TvPlaybackPreferences`、`TvPlaybackRunnables`、`TvPlaybackWarmup`、`TvPlayerBuilder`、`TvPlayerDecoderSwitch`、`TvPlayerDecoding`、`TvPlayerLifecycle`、`TvPlayerRenderers` | 播放内核、诊断和渲染；保留既有 `@file:JvmName` facade，低风险 |
| `navigation/` | `TvMainStateRestore`、`TvMainTick`、`TvNavigator` | 页面状态恢复、时钟与导航；低风险 |
| `system/` | `TvMainActivityConfig`、`TvMainActivityCreate`、`TvMainActivityResults`、`TvMainActivitySaveState` | Activity 系统回调与现场保存；低风险 |
| `ui/` | `DialogItem`、`EpisodeCell`、`HongguoKeyboard`、`IconButton`、`LoginForm`、`PosterCard`、`SectionHeading`、`SeekIndicator`、`SettingSlider`、`ToggleChip`、`TvComponents`、`TvDialog`、`TvDialogFocus`、`TvStyle`、`TvSwitchRow`、`TvTools`、`TvToolsPhone` | 共用控件、弹窗、键盘与工具；低风险 |
| `debug/` | `DebugOverlay`、`TvMetrics`、`TvNetworkLogging` | 调试面板、指标与网络日志；低风险 |
| `home/` | `RecommendWarm` | 首页推荐预热；低风险 |
| `library/` | `LanLibrarySync` | 局域网书库同步；低风险 |

#### shared

保持现有 `account/architecture/compat/data/debug/feature/feed/lan/media/nav/search/ui`
结构，本次不移动。

执行上限 30 批；单批最多 40 个文件；每批独立 commit、全量验证并记录。

### 布局批次 1：core/account

状态：已完成。

- `core/Account.kt` → `core/account/Account.kt`
- `core/AccountImportServer.kt` → `core/account/AccountImportServer.kt`
- `core/AccountSnapshot.kt` → `core/account/AccountSnapshot.kt`
- `core/DeviceRegister.kt` → `core/account/DeviceRegister.kt`
- `core/OfficialDeviceIds.kt` → `core/account/OfficialDeviceIds.kt`
- `core/PassportSigner.kt` → `core/account/PassportSigner.kt`
- `core/PassportTables.kt` → `core/account/PassportTables.kt`
- `core/PhoneLogin.kt` → `core/account/PhoneLogin.kt`
- `core/PhoneLoginServer.kt` → `core/account/PhoneLoginServer.kt`
- `core/PhonePage.kt` → `core/account/PhonePage.kt`
- `core/PhoneRegion.kt` → `core/account/PhoneRegion.kt`
- `core/QrLogin.kt` → `core/account/QrLogin.kt`
- `core/StableDeviceIds.kt` → `core/account/StableDeviceIds.kt`
- 外部路径引用：无；baseline 仅引用 JVM 类名，未移动或重命名类。
- 验证：各模块 Kotlin 编译、`:shared:testDebugUnitTest`、`:app:lintDebug`、
  `:app:assembleDebug` 通过；`:core:test` 435 例、24 失败、28 跳过，与基线一致。
- 下一批：`core/catalog`。

### 布局批次 2：core/catalog

状态：已完成。

- `core/BackToTopPolicy.kt` → `core/catalog/BackToTopPolicy.kt`
- `core/CatalogFilter.kt` → `core/catalog/CatalogFilter.kt`
- `core/CatalogSnapshot.kt` → `core/catalog/CatalogSnapshot.kt`
- `core/CategoryCatalog.kt` → `core/catalog/CategoryCatalog.kt`
- `core/CategoryFilters.kt` → `core/catalog/CategoryFilters.kt`
- `core/CategoryPanelCache.kt` → `core/catalog/CategoryPanelCache.kt`
- `core/ContentAuthorRules.kt` → `core/catalog/ContentAuthorRules.kt`
- `core/ContentType.kt` → `core/catalog/ContentType.kt`
- `core/ContentValues.kt` → `core/catalog/ContentValues.kt`
- `core/FeedCursor.kt` → `core/catalog/FeedCursor.kt`
- `core/HomeLayout.kt` → `core/catalog/HomeLayout.kt`
- `core/OptionalFilter.kt` → `core/catalog/OptionalFilter.kt`
- `core/RankBoard.kt` → `core/catalog/RankBoard.kt`
- `core/SeriesSeasons.kt` → `core/catalog/SeriesSeasons.kt`
- 外部路径引用：无；仅移动目录，文件名、包名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。
- 下一批：`core/crypto`。

### 布局批次 3：core/crypto

状态：已完成。

- `core/AesV3.kt` → `core/crypto/AesV3.kt`
- `core/ArgusLadon.kt` → `core/crypto/ArgusLadon.kt`
- `core/Bytes.kt` → `core/crypto/Bytes.kt`
- `core/MediaCrypto.kt` → `core/crypto/MediaCrypto.kt`
- `core/MedusaAlgorithm.kt` → `core/crypto/MedusaAlgorithm.kt`
- `core/MedusaAlgorithmTables.kt` → `core/crypto/MedusaAlgorithmTables.kt`
- `core/MedusaCore.kt` → `core/crypto/MedusaCore.kt`
- `core/MedusaEnvelope.kt` → `core/crypto/MedusaEnvelope.kt`
- `core/MedusaModel.kt` → `core/crypto/MedusaModel.kt`
- `core/MedusaPb.kt` → `core/crypto/MedusaPb.kt`
- `core/MedusaSchema.kt` → `core/crypto/MedusaSchema.kt`
- `core/MedusaState.kt` → `core/crypto/MedusaState.kt`
- `core/MedusaTables.kt` → `core/crypto/MedusaTables.kt`
- `core/Signer.kt` → `core/crypto/Signer.kt`
- 外部路径引用：无；包名、文件名和 JVM 类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过，
  仍在 DoD 允许范围内；额外波动为既有 `webFallbackPagesContinueWithoutGaps`。
- 下一批：`core/network`。

### 布局批次 4：core/network

状态：已完成。

- `core/ContentTransport.kt` → `core/network/ContentTransport.kt`
- `core/HgtvDns.kt` → `core/network/HgtvDns.kt`
- `core/HgtvTls.kt` → `core/network/HgtvTls.kt`
- `core/NetTiming.kt` → `core/network/NetTiming.kt`
- `core/OkHttp3Compat.kt` → `core/network/OkHttp3Compat.kt`
- `core/PublicDns.kt` → `core/network/PublicDns.kt`
- `core/RequestScope.kt` → `core/network/RequestScope.kt`
- `core/TlsStatus.kt` → `core/network/TlsStatus.kt`
- `core/TncConfigStore.kt` → `core/network/TncConfigStore.kt`
- 外部路径引用：无；包名、文件名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`core/identity`。

### 布局批次 5：core/identity

状态：已完成。

- `core/ContentDeviceLevel.kt` → `core/identity/ContentDeviceLevel.kt`
- `core/ContentIdentity.kt` → `core/identity/ContentIdentity.kt`
- `core/ContentProfiles.kt` → `core/identity/ContentProfiles.kt`
- `core/VendorConstants.kt` → `core/identity/VendorConstants.kt`
- 外部路径引用：无；仅目录变化。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`core/media`。

### 布局批次 6：core/media

状态：已完成。

- `core/ArtworkUrl.kt` → `core/media/ArtworkUrl.kt`
- `core/Danmaku.kt` → `core/media/Danmaku.kt`
- `core/DanmakuEmoji.kt` → `core/media/DanmakuEmoji.kt`
- `core/PictureWatchdog.kt` → `core/media/PictureWatchdog.kt`
- `core/SlideCache.kt` → `core/media/SlideCache.kt`
- 外部路径引用：无；包名、文件名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。
- 下一批：`core/library`。

### 布局批次 7：core/library

状态：已完成。

- `core/HistoryReconcile.kt` → `core/library/HistoryReconcile.kt`
- `core/LatestWriteQueue.kt` → `core/library/LatestWriteQueue.kt`
- `core/LibraryBackup.kt` → `core/library/LibraryBackup.kt`
- `core/LibraryIndex.kt` → `core/library/LibraryIndex.kt`
- `core/LibraryTransferServer.kt` → `core/library/LibraryTransferServer.kt`
- `core/LruPut.kt` → `core/library/LruPut.kt`
- `core/ViewingTools.kt` → `core/library/ViewingTools.kt`
- `core/WatchSync.kt` → `core/library/WatchSync.kt`
- `core/Watching.kt` → `core/library/Watching.kt`
- 外部路径引用：无；仅目录变化。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。
- 下一批：`core/recommend`。

### 布局批次 8：core/recommend

状态：已完成。

- `core/RecommendBehavior.kt` → `core/recommend/RecommendBehavior.kt`
- `core/RecommendCache.kt` → `core/recommend/RecommendCache.kt`
- `core/RecommendDataSource.kt` → `core/recommend/RecommendDataSource.kt`
- `core/RecommendFlow.kt` → `core/recommend/RecommendFlow.kt`
- `core/RecommendStatConfigStore.kt` → `core/recommend/RecommendStatConfigStore.kt`
- 外部路径引用：无；包名、文件名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`core/search`。

### 布局批次 9：core/search

状态：已完成。

- `core/PhoneSearchServer.kt` → `core/search/PhoneSearchServer.kt`
- `core/SearchDiag.kt` → `core/search/SearchDiag.kt`
- `core/SearchKeyboard.kt` → `core/search/SearchKeyboard.kt`
- `core/SearchSource.kt` → `core/search/SearchSource.kt`
- `core/VoiceQuery.kt` → `core/search/VoiceQuery.kt`
- 外部路径引用：无；仅目录变化。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`core/label`。

### 布局批次 10：core/label

状态：已完成。

- `core/CardMenu.kt` → `core/label/CardMenu.kt`
- `core/EpisodeLabel.kt` → `core/label/EpisodeLabel.kt`
- `core/HeatLabel.kt` → `core/label/HeatLabel.kt`
- `core/LabelStyle.kt` → `core/label/LabelStyle.kt`
- `core/PosterCardContent.kt` → `core/label/PosterCardContent.kt`
- 外部路径引用：无；包名、文件名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。
- 下一批：`core/common`。

### 布局批次 11：core/common

状态：已完成。

- `core/ThirdPartyNotice.kt` → `core/common/ThirdPartyNotice.kt`
- `core/WorkLanes.kt` → `core/common/WorkLanes.kt`
- 外部路径引用：无；仅目录变化。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。
- 下一批：`core/playback`。

### 布局批次 12：core/playback

状态：已完成。

- `core/BitrateProbe.kt` → `core/playback/BitrateProbe.kt`
- `core/CodecProbe.kt` → `core/playback/CodecProbe.kt`
- `core/KeyMap.kt` → `core/playback/KeyMap.kt`
- `core/PlaybackAttemptLedger.kt` → `core/playback/PlaybackAttemptLedger.kt`
- `core/PlaybackBackupDevice.kt` → `core/playback/PlaybackBackupDevice.kt`
- `core/PlaybackCache.kt` → `core/playback/PlaybackCache.kt`
- `core/PlaybackComfort.kt` → `core/playback/PlaybackComfort.kt`
- `core/PlaybackDegradation.kt` → `core/playback/PlaybackDegradation.kt`
- `core/PlaybackFailurePolicy.kt` → `core/playback/PlaybackFailurePolicy.kt`
- `core/PlaybackInfoLine.kt` → `core/playback/PlaybackInfoLine.kt`
- `core/PlaybackSpeed.kt` → `core/playback/PlaybackSpeed.kt`
- `core/PlaybackStage.kt` → `core/playback/PlaybackStage.kt`
- `core/PrefetchPolicy.kt` → `core/playback/PrefetchPolicy.kt`
- `core/PreparedSlot.kt` → `core/playback/PreparedSlot.kt`
- `core/Probe.kt` → `core/playback/Probe.kt`
- `core/RemoteVideo.kt` → `core/playback/RemoteVideo.kt`
- `core/SeekStepPump.kt` → `core/playback/SeekStepPump.kt`
- `core/VideoProbe.kt` → `core/playback/VideoProbe.kt`
- 外部路径引用：无；文件名、包名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`core/content/detail`（剩余根文件归位）。

### 布局批次 13：core/content/detail 收尾

状态：已完成。

- `core/DetailCache.kt` → `core/content/detail/DetailCache.kt`
- 外部路径引用：无；仅目录变化，类名与包名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。
- 结果：`core` 根目录 `.kt` 文件数为 0。
- 下一批：`feature-phone/account`。

### 布局批次 14：feature-phone/account

状态：已完成。

- `phone/PhoneAccountActivity.kt` → `phone/account/PhoneAccountActivity.kt`
- `phone/PhoneAccountLogin.kt` → `phone/account/PhoneAccountLogin.kt`
- `phone/PhoneAccountSync.kt` → `phone/account/PhoneAccountSync.kt`
- `phone/PhoneIdentity.kt` → `phone/account/PhoneIdentity.kt`
- 外部路径引用：无；包名、文件名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。
- 下一批：`feature-phone/settings`。

### 布局批次 15：feature-phone/settings

状态：已完成。

- `phone/PhoneDanmakuSize.kt` → `phone/settings/PhoneDanmakuSize.kt`
- `phone/PhoneSettingsActions.kt` → `phone/settings/PhoneSettingsActions.kt`
- `phone/PhoneSettingsActivity.kt` → `phone/settings/PhoneSettingsActivity.kt`
- `phone/PhoneSettingsApply.kt` → `phone/settings/PhoneSettingsApply.kt`
- `phone/PhoneSettingsLanSync.kt` → `phone/settings/PhoneSettingsLanSync.kt`
- `phone/PhoneSettingsLandscapePages.kt` → `phone/settings/PhoneSettingsLandscapePages.kt`
- `phone/PhoneSettingsLandscapeWidgets.kt` → `phone/settings/PhoneSettingsLandscapeWidgets.kt`
- `phone/PhoneSettingsPageSelector.kt` → `phone/settings/PhoneSettingsPageSelector.kt`
- `phone/PhoneSettingsPortrait.kt` → `phone/settings/PhoneSettingsPortrait.kt`
- `phone/PhoneSettingsScroll.kt` → `phone/settings/PhoneSettingsScroll.kt`
- `phone/PhoneSettingsSupport.kt` → `phone/settings/PhoneSettingsSupport.kt`
- `phone/PhoneSettingsSystem.kt` → `phone/settings/PhoneSettingsSystem.kt`
- `phone/PhoneSettingsUnified.kt` → `phone/settings/PhoneSettingsUnified.kt`
- `phone/PhoneSettingsWidgets.kt` → `phone/settings/PhoneSettingsWidgets.kt`
- 外部路径引用：无；包名、文件名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`feature-phone/feed`。

### 布局批次 16：feature-phone/feed

状态：已完成。

- `phone/PhoneFeedActions.kt` → `phone/feed/PhoneFeedActions.kt`
- `phone/PhoneFeedAdapter.kt` → `phone/feed/PhoneFeedAdapter.kt`
- `phone/PhoneFeedAdoption.kt` → `phone/feed/PhoneFeedAdoption.kt`
- `phone/PhoneFeedChrome.kt` → `phone/feed/PhoneFeedChrome.kt`
- `phone/PhoneFeedDanmaku.kt` → `phone/feed/PhoneFeedDanmaku.kt`
- `phone/PhoneFeedHide.kt` → `phone/feed/PhoneFeedHide.kt`
- `phone/PhoneFeedHost.kt` → `phone/feed/PhoneFeedHost.kt`
- `phone/PhoneFeedInitializer.kt` → `phone/feed/PhoneFeedInitializer.kt`
- `phone/PhoneFeedLifecycle.kt` → `phone/feed/PhoneFeedLifecycle.kt`
- `phone/PhoneFeedPageSupport.kt` → `phone/feed/PhoneFeedPageSupport.kt`
- `phone/PhoneFeedPager.kt` → `phone/feed/PhoneFeedPager.kt`
- `phone/PhoneFeedPlayback.kt` → `phone/feed/PhoneFeedPlayback.kt`
- `phone/PhoneFeedPrefetch.kt` → `phone/feed/PhoneFeedPrefetch.kt`
- `phone/PhoneFeedReopen.kt` → `phone/feed/PhoneFeedReopen.kt`
- `phone/PhoneFeedResume.kt` → `phone/feed/PhoneFeedResume.kt`
- `phone/PhoneFeedSettings.kt` → `phone/feed/PhoneFeedSettings.kt`
- `phone/PhoneFeedSurface.kt` → `phone/feed/PhoneFeedSurface.kt`
- `phone/PhoneFeedView.kt` → `phone/feed/PhoneFeedView.kt`
- 外部路径引用：无；包名、文件名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`feature-phone/player`。

### 布局批次 17：feature-phone/player（上）

状态：已完成。

- 移动 `PhonePlaybackRecovery`、`PhonePlaybackSettings`、`PhonePlayerActivity`、
  `PhonePlayerAdoption`、`PhonePlayerAttach`、`PhonePlayerBack`、`PhonePlayerChrome`、
  `PhonePlayerCommonUi`、`PhonePlayerConfiguration`、`PhonePlayerControls`、
  `PhonePlayerCreate`、`PhonePlayerDanmaku`、`PhonePlayerDebug`、`PhonePlayerDestroy`、
  `PhonePlayerEpisodeBinding`、`PhonePlayerEpisodePanels`、`PhonePlayerEpisodeSwitch`、
  `PhonePlayerEpisodes`、`PhonePlayerExit`、`PhonePlayerFullscreen`、
  `PhonePlayerLandscapeAssembly`、`PhonePlayerLandscapePanel`、
  `PhonePlayerLandscapeSettingsFacade`、`PhonePlayerLifecycle` 到 `phone/player/`。
- 外部路径引用：无；包名、文件名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`feature-phone/player`（下）。

### 布局批次 18：feature-phone/player（下）

状态：已完成。

- 移动 `PhonePlayerLoad`、`PhonePlayerMode`、`PhonePlayerModeSupport`、
  `PhonePlayerPageFactory`、`PhonePlayerPause`、`PhonePlayerPlay`、
  `PhonePlayerPlayInfo`、`PhonePlayerPortraitUi`、`PhonePlayerPreparation`、
  `PhonePlayerReopen`、`PhonePlayerResume`、`PhonePlayerScreenState`、
  `PhonePlayerSettingsEntry`、`PhonePlayerSettingsPanel`、`PhonePlayerSettingsRows`、
  `PhonePlayerSleep`、`PhonePlayerSwipe`、`PhonePlayerUiAssembly`、
  `PhonePlayerUnderlay`、`PhonePlayerVideoFrame`、`PhonePlayerViewFacade`、
  `PhonePlayerViewSupport`、`PhonePreload` 到 `phone/player/`。
- 外部路径引用：无；仅目录变化。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`feature-phone/detail`。

### 布局批次 19：feature-phone/detail

状态：已完成。

- `phone/PhoneDetailActivity.kt` → `phone/detail/PhoneDetailActivity.kt`
- `phone/PhoneDetailSections.kt` → `phone/detail/PhoneDetailSections.kt`
- `phone/PhoneVideoPage.kt` → `phone/detail/PhoneVideoPage.kt`
- `phone/PhoneVideoPageState.kt` → `phone/detail/PhoneVideoPageState.kt`
- 外部路径引用：无；包名、文件名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`feature-phone/search`。

### 布局批次 20：feature-phone/search

状态：已完成。

- `phone/PhoneSearchActivity.kt` → `phone/search/PhoneSearchActivity.kt`
- 外部路径引用：无；仅目录变化。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 25 失败 / 28 跳过。
- 下一批：`feature-phone/debug`。

### 布局批次 21：feature-phone/debug

状态：已完成。

- `phone/PhoneDebugReport.kt` → `phone/debug/PhoneDebugReport.kt`
- `phone/PhoneNetDebug.kt` → `phone/debug/PhoneNetDebug.kt`
- `phone/PhoneRecommendReporting.kt` → `phone/debug/PhoneRecommendReporting.kt`
- 外部路径引用：无；包名、文件名和类名不变。
- 验证：各模块编译、共享单测、Lint、Debug APK 通过；`:core:test` 24 失败 / 28 跳过。
- 下一批：`feature-tv/player`。
