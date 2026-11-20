// SPDX-License-Identifier: GPL-3.0-only
//
// iOS 应用级可调参数的唯一来源。
//
// 使用约定：
// - 调网络、分页、推荐、播放、缓存、默认值或手势阈值时，优先只改本文件。
// - 协议字段、设备身份、状态机枚举和页面主题令牌仍留在各自模块。
// - 时间值按属性名中的单位使用；纳秒值直接用于 Task.sleep。

import Foundation
import CoreGraphics

enum AppConfig {
    /// 页面结构与默认顺序。
    enum Navigation {
        /// 手机底部 tab 的 key 顺序。
        static let phoneTabKeys = ["recommend", "category", "rank", "mine"]

        /// 榜单页顶部芯片顺序。
        static let rankBoardOrder = [
            "recommend", "hot", "subscribe", "new-release",
            "hot-search", "chase", "must-watch", "followed"
        ]

        /// 设置页四大分页顺序。
        static let settingsPageOrder = ["playback", "appearance", "recommend", "system"]

        /// 顶部导航可隐藏入口 key。
        static let navKeys = ["recommend", "search", "category", "rank", "profile"]
    }

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

        /// TNC 没有下发更新周期时的默认刷新间隔，单位秒。
        static let tncDefaultUpdateInterval: TimeInterval = 3_600

        /// TNC 刷新周期下限，单位秒。
        static let tncMinUpdateInterval: TimeInterval = 300

        /// TNC 刷新周期上限，单位秒。
        static let tncMaxUpdateInterval: TimeInterval = 86_400

        /// 扫码登录成功后本地会话默认保留时间，单位秒。
        static let sessionLifetime: TimeInterval = 30 * 24 * 3_600
    }

    /// 目录、榜单、推荐和搜索分页参数。
    enum Catalog {
        /// 分类首屏请求条数。
        static let categoryFirstLimit = 20

        /// 分类后续页请求条数。
        static let categoryPageLimit = 60

        /// 榜单接口单页返回条数；HAR 的 `cell/change` 固定每页 10 条。
        static let rankLimit = 10

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

        /// 播放模型请求最多换几次设备身份。
        static let streamModelAttempts = 3

        /// 官网搜索单页最大页码。
        static let webSearchMaxPage = 100

        /// 搜索关键词最大字符数。
        static let searchKeywordMaxLength = 80

        /// 官网搜索服务端允许的最大查询字符数。
        static let webSearchQueryMaxLength = 30

        /// 搜索联想词最大返回条数。
        static let suggestionLimit = 20

        /// 热搜词最大返回条数。
        static let hotSearchLimit = 30

        /// 热搜接口默认请求条数。
        static let hotSearchRequestCount = 12

        /// 分类接口前多少页继续走推荐序。
        static let categoryRecommendationPages = 2

        /// 分类接口从第几页开始回退 hot_score。
        static let categoryFallbackStartPage = 3

        /// 分类第 3 页之后的 offset 步长。
        static let categoryOffsetStep = 60

        /// 标签、作者等多值字段在卡片上最多展示几项。
        static let labelItemLimit = 5
    }

    /// 推荐去重窗口和失败跳过策略。
    enum Recommend {
        /// 推荐曝光窗口最多记录多少条。
        static let windowLimit = 1_200

        /// 推荐曝光窗口有效期，单位秒。
        static let windowTTL: TimeInterval = 7 * 24 * 3_600

        /// 回传给服务端的 filter_ids 最多保留多少条。
        static let filterIdsLimit = 300

        /// 连续多少条播放失败后停止自动跳过。
        static let failureSkipLimit = 2

        /// 距离列表末尾多少条时提前加载下一页。
        static let nearEndLoadThreshold = 3
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

        /// 播放地址失败后的自动重试次数。
        static let sourceRetryLimit = 2

        /// 播放地址失败后的退避步长，单位秒。
        static let sourceRetryStep: TimeInterval = 3

        /// 每集允许同档重建次数。
        static let sameQualityRebuildLimit = 2

        /// 每集允许自动降档次数。
        static let qualityDownLimit = 2

        /// 片尾 seek 保护，避免提前触发播放结束，单位秒。
        static let seekTailGuard: Double = 0.5
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
        static let uiScale: Float = 1.5

        /// 旧版默认字号倍率，只用于一次性迁移。
        static let legacyUiScale: Float = 1.3

        /// 上一版默认字号倍率，只用于迁移到 1.5。
        static let previousUiScale: Float = 1.6

        /// 设置页字号倍率档位。
        static let uiScaleOptions: [Float] = [1.3, 1.45, 1.5, 1.75, 1.9]

        /// 偏好修改后的写盘防抖时间。
        static let saveDebounceNanoseconds: UInt64 = 250_000_000

        /// 本机历史记录上限。
        static let historyLimit = 200

        /// 本机搜索历史上限。
        static let searchHistoryLimit = 20

        /// 设置页画面缩放下限。
        static let videoScaleMin: Double = 1

        /// 设置页画面缩放上限。
        static let videoScaleMax: Double = 100

        /// 设置页快进 / 快退最小秒数。
        static let seekStepMin = 2

        /// 设置页快进 / 快退最大秒数。
        static let seekStepMax = 20

        /// 播放控制层自动收起最小秒数。
        static let panelTimeoutMin = 2

        /// 播放控制层自动收起最大秒数。
        static let panelTimeoutMax = 15

        /// 弹幕大小百分比下限。
        static let danmakuSizeMin = 50

        /// 弹幕大小百分比上限。
        static let danmakuSizeMax = 150

        /// 弹幕大小百分比步长。
        static let danmakuSizeStep = 5

        /// 弹幕透明度下限。
        static let danmakuAlphaMin: Double = 0.2

        /// 弹幕透明度上限。
        static let danmakuAlphaMax: Double = 1

        /// 弹幕透明度步长。
        static let danmakuAlphaStep: Double = 0.05

        /// 手机列表最大列数。
        static let maxGridColumns = 3

        /// 选集每页最小集数。
        static let episodePageMin = 5

        /// 选集网格每行集数。
        static let episodeColumns = 5

        /// 海报网格最大列数。
        static let maxPosterColumns = 3
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

    /// 弹幕绘制的基础排版参数。
    enum Danmaku {
        /// 弹幕最小字号。
        static let minFontSize: CGFloat = 10

        /// 弹幕基准字号。
        static let baseFontSize: CGFloat = 15

        /// 轨道高度相对字号的倍数。
        static let laneHeightScale: CGFloat = 1.6

        /// 全屏模式可用高度比例。
        static let fullHeightRatio: CGFloat = 0.75

        /// 顶部模式可用高度比例。
        static let topHeightRatio: CGFloat = 0.35

        /// 轨道文字垂直居中偏移。
        static let laneCenterOffset: CGFloat = 0.9

        /// 全屏模式顶部额外偏移比例。
        static let fullTopOffsetRatio: CGFloat = 0.12

        /// 普通模式顶部额外偏移比例。
        static let normalTopOffsetRatio: CGFloat = 0.08

        /// 弹幕离开右边缘后再进入的额外宽度。
        static let exitWidthGap: CGFloat = 20
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

        /// 底部导航同一格双击判定窗口，单位秒。
        static let tabDoubleTapWindow: TimeInterval = 0.32

        /// 轻触反馈动画时长。
        static let pressAnimationDuration: TimeInterval = 0.12

        /// 搜索候选词最多显示条数。
        static let searchSuggestionLimit = 8

        /// 手机 / 平板几何参数。
        enum Phone {
            /// 海报网格左右边距。
            static let pageInset: CGFloat = 0

            /// 底部导航左右留白。
            static let tabInset: CGFloat = 8

            /// 卡片间距。
            static let cardGap: CGFloat = 8

            /// 海报卡之间横纵间距。
            static let posterGap: CGFloat = 0

            /// 频道 chip 行高。
            static let chipRow: CGFloat = 45

            /// 剧场卡片宽度。
            static let posterWidth: CGFloat = 293

            /// 播放页右侧操作项高度。
            static let actionItem: CGFloat = 65

            /// 柔光带宽度。
            static let softBand: CGFloat = 6

            /// 大面板柔光带宽度。
            static let softBandLarge: CGFloat = 10

            /// 卡片圆角。
            static let radiusCard: CGFloat = 8

            /// 胶囊圆角。
            static let radiusPill: CGFloat = 26

            /// 弹窗 / 抽屉圆角。
            static let radiusSheet: CGFloat = 24

            /// 进度条轨道高度。
            static let trackHeight: CGFloat = 3

            /// 底部导航栏高度。
            static let tabBar: CGFloat = 42

            /// 选集网格每行集数。
            static let episodeGridColumns = 5

            /// 选集网格间距。
            static let episodeGridSpacing: CGFloat = 4

            /// 紧凑模式分页条最大宽度。
            static let episodePagerCompactWidth: CGFloat = 140

            /// 常规模式分页条最大宽度。
            static let episodePagerWidth: CGFloat = 200

            /// 紧凑模式选集格高度。
            static let episodeCellHeightCompact: CGFloat = 30

            /// 常规模式选集格高度。
            static let episodeCellHeight: CGFloat = 34
        }
    }
}
