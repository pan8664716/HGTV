// SPDX-License-Identifier: GPL-3.0-only
//
// iOS application-level tuning values.
//
// Keep protocol field names, wire values, vendor identity and page-specific design
// tokens in their owning files. Values that are expected to be tuned together
// across networking, playback, recommendation and defaults live here.

import Foundation

enum AppConfig {
    enum Network {
        static let defaultRequestTimeout: TimeInterval = 30
        static let resourceTimeout: TimeInterval = 45
        static let getRequestTimeout: TimeInterval = 20
        static let tncTimeout: TimeInterval = 8
        static let deviceRegisterTimeout: TimeInterval = 20
        static let sessionTimeout: TimeInterval = 20
        static let offlineVideoTimeout: TimeInterval = 60
        static let artworkTimeout: TimeInterval = 15
    }

    enum Catalog {
        static let categoryFirstLimit = 20
        static let categoryPageLimit = 60
        static let rankLimit = 100
        static let recommendLimit = 20
        static let maxPage = 100
        static let webSearchPageSize = 10
        static let appSearchPageSize = 50
        static let categoryAttempts = 4
        static let categoryRetryDelayNanoseconds: UInt64 = 250_000_000
        static let streamRetryDelayNanoseconds: UInt64 = 220_000_000
    }

    enum Recommend {
        static let windowLimit = 1_200
        static let windowTTL: TimeInterval = 7 * 24 * 3_600
        static let filterIdsLimit = 300
        static let loadMoreThreshold = 3
        static let failureSkipLimit = 2
    }

    enum Playback {
        static let completeTailMs: Int64 = 3_000
        static let periodicInterval: TimeInterval = 0.5
        static let progressSaveInterval: TimeInterval = 5
        static let panelTimeoutMinSeconds = 2
        static let panelTimeoutDefaultSeconds = 6
        static let danmakuLife: Double = 8
        static let danmakuFrameRate: Double = 30
        static let danmakuMergeMin = 2
        static let danmakuMergeMax = 10
        static let longPressDuration: Double = 0.35
        static let playIconVisibleNanoseconds: UInt64 = 450_000_000
        static let cardPlayIconVisibleNanoseconds: UInt64 = 500_000_000
        static let animationDuration: Double = 0.18
        static let quickAnimationDuration: Double = 0.15
        static let tapAnimationDuration: Double = 0.12
        static let releaseAnimationDuration: Double = 0.20
        static let temporarySpeed: Float = 2
        static let qualityOptions = [360, 480, 576, 720, 1_080]
        static let speedOptions: [Float] = [0.75, 1, 1.25, 1.5, 1.75, 2]
    }

    enum LibraryDefaults {
        static let maxQuality = 1_080
        static let playbackSpeed: Float = 1
        static let videoScale: Float = 100
        static let seekStep = 10
        static let playerPanelTimeout = AppConfig.Playback.panelTimeoutDefaultSeconds
        static let danmakuMergeMin = AppConfig.Playback.danmakuMergeMin
        static let danmakuAlpha: Float = 1
        static let danmakuSizePercent = 100
        static let gridColumns = 2
        static let episodePageSize = 20
        static let episodePageSizeOptions = [20, 30, 50, 80, 100]
        static let gridColumnOptions = [1, 2, 3]
        static let uiScale: Float = 1.6
        static let legacyUiScale: Float = 1.3
        static let uiScaleOptions: [Float] = [1.3, 1.45, 1.6, 1.75, 1.9]
        static let saveDebounceNanoseconds: UInt64 = 250_000_000
    }

    enum Cache {
        static let artworkMemoryCount = 160
        static let artworkMemoryBytes = 96 * 1024 * 1024
        static let offlineVideoBytes: Int64 = 1_200 * 1024 * 1024
    }

    enum Ui {
        static let toastSeconds: Double = 1.6
        static let toastAnimationDuration = AppConfig.Playback.animationDuration
        static let accountQrPollNanoseconds: UInt64 = 1_100_000_000
        static let gestureMinimumDistance: Double = 16
        static let cardGestureMinimumDistance: Double = 12
        static let verticalSwitchDistance: Double = 60
        static let horizontalDirectionBias: Double = 1.3
        static let cardHorizontalDirectionBias: Double = 1.4
        static let centerTapZoneMinRatio: Double = 0.34
        static let centerTapZoneMaxRatio: Double = 0.66
    }
}
