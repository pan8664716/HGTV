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
| `feature-tv/.../MainActivityPlayback.kt` | 2232 |
| `shared/.../Library.kt` | 2027 |

## 批次记录

### 批次 1：内容模型与目录整理

状态：已完成。

- 将 `ContentRepository.kt` 移到 `core/.../content/`，Kotlin 包名保持不变。
- 将内容模型原样移到 `ContentModels.kt`，公开类型全限定名不变。
- `CATALOG_FIRST_PAGE_SIZE` 和文件级顶层工具函数保留在原文件，以维持
  `ContentRepositoryKt` facade。
- `ContentRepository.kt` 从 6041 行降到 5816 行；`ContentModels.kt` 为 235 行。

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
- `ContentRepository.kt` 从 5816 行降到 5473 行；`ContentCatalogParser.kt` 为 400 行。

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
- `ContentRepository.kt` 从 5473 行降到 5335 行；`ContentSearchParser.kt` 为 195 行。

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
- `ContentRepository.kt` 从 5335 行降到 5268 行；`ContentAccountApi.kt` 为 174 行。

验证：

- `git diff --check` 通过。
- `:app:assembleDebug` 通过。
- `:app:lintDebug` 通过，无新增 `Error` / `Fatal`。
- `:shared:testDebugUnitTest` 通过。
- `:core:test` 为 435 例、24 失败、28 跳过，较基线少 1 个失败，没有新增失败。

回滚：对本批提交执行 `git revert`，不改动其他批次。

## 下一批

批次 5 只处理详情、作者作品和发行方探测的纯解析与内部编排，保留请求顺序、缓存、
并发限制和公开方法。继续按既定计划执行。
