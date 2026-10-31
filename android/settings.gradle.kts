pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "HGTV"

/**
 * 模块边界（依赖方向单向，不允许反向或横向依赖）：
 *
 * ```
 * core（纯 JVM）← shared（Android 公共层）← feature-tv    ← app
 *                                        ← feature-phone ←
 * ```
 *
 * - `:core`          网络协议、签名、解析、播放状态机；零 Android 依赖，可在 JVM 上跑单测。
 * - `:shared`        账号 / 会话 / 收藏 / 进度 / 封面 / 播放传输 / 弹幕渲染 / 调试面板。
 * - `:feature-tv`    遥控器 + 焦点导航 + 1280×720 设计画布。
 * - `:feature-phone` 触摸 + 竖屏 + 手势播放。
 * - `:app`           Application、启动路由、Manifest、图标与签名。
 */
include(":core", ":shared", ":feature-tv", ":feature-phone", ":app")
