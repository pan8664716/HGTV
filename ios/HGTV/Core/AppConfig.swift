// SPDX-License-Identifier: GPL-3.0-only
//
// iOS 应用级可调参数的唯一来源。
//
// 使用约定：
// - 调网络、分页、推荐、播放、缓存、默认值或手势阈值时，优先只改本文件。
// - 协议字段、设备身份、状态机枚举和页面主题令牌仍留在各自模块。
// - 时间值按属性名中的单位使用；纳秒值直接用于 Task.sleep。

import Foundation

enum AppConfig {
    /// 网络请求超时。除特别标注外，单位均为秒。
    enum Network {
        /// URLSession 默认单次请求超时。
        static let defaultRequestTimeout: TimeInterval = 30

        /// URLSession 整次资源加载超时，必须大于单次请求超时。
        static let resourceTimeout: TimeInterval = 45

        /// 普通 GET 默认超时，用于二维码图片等轻量请求。
        static let getRequestTimeout: TimeInterval = 20

        /// TNC 配置刷新超时；它不应该长时间阻塞启动后的后台刷新。
        static let tncTimeout: TimeInterval = 8

        /// 设备注册超时；失败后由上层继续尝试其他候选入口。
        static let deviceRegisterTimeout: TimeInterval = 20

        /// 扫码登录和会话请求超时。
        static let sessionTimeout: TimeInterval = 20

        /// 整集离线下载和解密前的网络读取超时。
        static let offlineVideoTimeout: TimeInterval = 60

        /// 封面图片下载超时。
        static let artworkTimeout: TimeInterval = 15
    }

    /// 目录、榜单、推荐和搜索分页参数。
    enum Catalog {
        /// 分类首屏请求条数。
        static let categoryFirstLimit = 20

        /// 分类后续页请求条数。
        static let categoryPageLimit = 60

        /// 榜单单页请求条数。
        static let rankLimit = 100

        /// 推荐流单页请求条数。
        static let recommendLimit = 20

        /// 客户端允许的最大目录页码，防止异常游标无限翻页。
        static let maxPage = 100

        /// 官网搜索每页固定条数。
        static let webSearchPageSize = 10

        /// App 搜索每页请求条数。
        static let appSearchPageSize = 50

        /// 分类接口失败后的最大尝试次数。
        static let categoryAttempts = 4

        /// 分类接口重试间隔。
        static let categoryRetryDelayNanoseconds: UInt64 = 250_000_000

        /// 播放地址请求重试间隔。
        static let streamRetryDelayNanoseconds: UInt64 = 220_000_000
    }

    /// 推荐去重窗口和失败跳过策略。
    enum Recommend {
        /// 推荐曝光窗口最多记录多少条。
        static let windowLimit = 1_200

        /// 推荐曝光窗口有效期，单位秒。
        static let windowTTL: TimeInterval = 7 * 24 * 3_600

        /// 回传给服务端的 filter_ids 最多保留多少条。
        static let filterIdsLimit = 300

        /// 距离列表末尾多少条时提前加载下一页。
        static let loadMoreThreshold = 3

        /// 连续多少条播放失败后停止自动跳过。
        static let failureSkipLimit = 2
    }

    /// 播放、弹幕、动画和手势参数。
    enum Playback {
        /// 距片尾多少毫秒算已看完。
        static let completeTailMs: Int64 = 3_000

        /// AVPlayer 进度观察间隔，单位秒。
        static let periodicInterval: TimeInterval = 0.5

        /// 正式播放页进度落盘间隔，单位秒。
        static let progressSaveInterval: TimeInterval = 5

        /// 播放控制层自动收起的最小秒数。
        static let panelTimeoutMinSeconds = 2

        /// 播放控制层自动收起的默认秒数。
        static let panelTimeoutDefaultSeconds = 6

        /// 一条滚动弹幕在屏幕上的存活时间，单位秒。
        static let danmakuLife: Double = 8

        /// 弹幕绘制刷新率，单位帧每秒。
        static let danmakuFrameRate: Double = 30

        /// 相同弹幕允许聚合的最小重复条数。
        static let danmakuMergeMin = 2

        /// 相同弹幕允许聚合的最大重复条数。
        static let danmakuMergeMax = 10

        /// 长按倍速或打开设置的触发时长，单位秒。
        static let longPressDuration: Double = 0.35

        /// 正式播放页点击后播放图标的停留时间。
        static let playIconVisibleNanoseconds: UInt64 = 450_000_000

        /// 推荐卡片点击后播放图标的停留时间。
        static let cardPlayIconVisibleNanoseconds: UInt64 = 500_000_000

        /// 播放控制层常规动画时长，单位秒。
        static let animationDuration: Double = 0.18

        /// 推荐卡片图标出现动画时长。
        static let quickAnimationDuration: Double = 0.15

        /// 正式播放页点击图标出现动画时长。
        static let tapAnimationDuration: Double = 0.12

        /// 播放图标消失动画时长。
        static let releaseAnimationDuration: Double = 0.20

        /// 长按临时倍速。
        static let temporarySpeed: Float = 2

        /// 设置页和播放面板可选的清晰度档位。
        static let qualityOptions = [360, 480, 576, 720, 1_080]

        /// 设置页和播放面板可选的倍速档位。
        static let speedOptions: [Float] = [0.75, 1, 1.25, 1.5, 1.75, 2]
    }

    /// 首次安装和恢复默认设置时使用的偏好值。
    enum LibraryDefaults {
        /// 默认清晰度上限。
        static let maxQuality = 1_080

        /// 默认播放倍速。
        static let playbackSpeed: Float = 1

        /// 默认画面缩放百分比。
        static let videoScale: Float = 100

        /// 默认快进 / 快退步长，单位秒。
        static let seekStep = 10

        /// 默认播放控制层自动收起秒数。
        static let playerPanelTimeout = AppConfig.Playback.panelTimeoutDefaultSeconds

        /// 默认相同弹幕聚合阈值。
        static let danmakuMergeMin = AppConfig.Playback.danmakuMergeMin

        /// 默认弹幕透明度。
        static let danmakuAlpha: Float = 1

        /// 默认弹幕字号百分比。
        static let danmakuSizePercent = 100

        /// 默认列表每行卡片数。
        static let gridColumns = 2

        /// 默认选集每页集数。
        static let episodePageSize = 20

        /// 选集每页可选项。
        static let episodePageSizeOptions = [20, 30, 50, 80, 100]

        /// 手机竖屏列表每行可选项。
        static let gridColumnOptions = [1, 2, 3]

        /// 默认界面字号倍率。
        static let uiScale: Float = 1.6

        /// 旧版默认字号倍率，只用于一次性迁移。
        static let legacyUiScale: Float = 1.3

        /// 设置页字号倍率档位。
        static let uiScaleOptions: [Float] = [1.3, 1.45, 1.6, 1.75, 1.9]

        /// 偏好修改后的写盘防抖时间。
        static let saveDebounceNanoseconds: UInt64 = 250_000_000
    }

    /// 封面和离线视频缓存上限。
    enum Cache {
        /// 封面内存缓存对象数。
        static let artworkMemoryCount = 160

        /// 封面内存缓存总成本上限。
        static let artworkMemoryBytes = 96 * 1024 * 1024

        /// 离线解密视频缓存总大小。
        static let offlineVideoBytes: Int64 = 1_200 * 1024 * 1024
    }

    /// 通用提示、扫码轮询和手势识别阈值。
    enum Ui {
        /// 全局提示默认显示时长，单位秒。
        static let toastSeconds: Double = 1.6

        /// 提示出现 / 消失动画时长。
        static let toastAnimationDuration = AppConfig.Playback.animationDuration

        /// 扫码登录轮询间隔。
        static let accountQrPollNanoseconds: UInt64 = 1_100_000_000

        /// 正式播放页触发拖动所需的最小距离。
        static let gestureMinimumDistance: Double = 16

        /// 推荐卡片触发拖动所需的最小距离。
        static let cardGestureMinimumDistance: Double = 12

        /// 上下滑动切换集数的距离阈值。
        static let verticalSwitchDistance: Double = 60

        /// 正式播放页横向拖动相对纵向拖动的最小偏置。
        static let horizontalDirectionBias: Double = 1.3

        /// 推荐卡片横向拖动相对纵向拖动的最小偏置。
        static let cardHorizontalDirectionBias: Double = 1.4

        /// 长按中间设置区域的起始比例。
        static let centerTapZoneMinRatio: Double = 0.34

        /// 长按中间设置区域的结束比例。
        static let centerTapZoneMaxRatio: Double = 0.66
    }
}
