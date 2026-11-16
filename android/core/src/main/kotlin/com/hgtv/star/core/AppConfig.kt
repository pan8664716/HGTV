// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * Application-level tuning values shared by core, shared, phone and TV.
 *
 * Keep protocol field names, enum wire values and page-specific design tokens in
 * their owning modules. This file is the single source for values that are
 * expected to be tuned together across features.
 */
object AppConfig {
    object Network {
        const val REQUEST_TIMEOUT_SECONDS = 20L
        const val REQUEST_TIMEOUT_MS = REQUEST_TIMEOUT_SECONDS * 1_000L
        const val REQUEST_TIMEOUT_MS_INT = 20_000

        const val DNS_MEMORY_TTL_MS = 10L * 60L * 1_000L
        const val DNS_DISK_TTL_MS = 30L * 60L * 1_000L
        const val DNS_FALLBACK_TTL_MS = 30L * 60L * 1_000L
        const val DNS_MAX_HOSTS = 32
        const val DNS_DISK_READY_WAIT_MS = 300L
        const val PUBLIC_DNS_TIMEOUT_MS = 1_500

        const val TNC_DEFAULT_UPDATE_INTERVAL_MS = 60L * 60L * 1_000L
        const val TNC_MIN_UPDATE_INTERVAL_MS = 5L * 60L * 1_000L
        const val TNC_MAX_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1_000L
        const val TNC_LOAD_WAIT_MS = 300L
        const val TNC_MAX_BACKOFF_MS = 30L * 60L * 1_000L

        const val SESSION_HEARTBEAT_INTERVAL_MS = 600_000L
        const val SESSION_SELF_CHECK_DELAY_MS = 60_000L
        const val SESSION_FRONTIER_RESUME_MS = 3_000L
        const val SESSION_FOREGROUND_MIN_GAP_MS = 60_000L
        const val SESSION_RENEW_INTERVAL_MS = 30L * 60_000L
        const val SESSION_RENEW_BEFORE_MS = 3L * 24L * 60L * 60L * 1_000L

        const val IMAGE_TIMEOUT_SECONDS = 20L
        const val LAN_TIMEOUT_MS = REQUEST_TIMEOUT_MS
        const val QR_POLL_MS = 1_100L
    }

    object Startup {
        const val REGISTER_MS = Network.REQUEST_TIMEOUT_MS
        const val FEED_FIRST_MS = Network.REQUEST_TIMEOUT_MS
        const val DETAIL_MS = Network.REQUEST_TIMEOUT_MS
        const val STREAM_MS = Network.REQUEST_TIMEOUT_MS
        const val PREPARE_MS = Network.REQUEST_TIMEOUT_MS
        const val DEFERRED_TASK_TIMEOUT_MS = 20_000L
        const val DEFERRED_LATEST_TIMEOUT_MS = 30_000L
    }

    object Catalog {
        const val PAGE_SIZE = 100
        const val FIRST_PAGE_SIZE = PAGE_SIZE
        const val PREFETCH_REMAINING = 50
        const val MAX_PAGE = 100
        const val PREFETCH_PAGES = 2
        const val RETRY_COOLDOWN_MS = 3_000L

        const val CATEGORY_FIRST_LIMIT = 20
        const val CATEGORY_PAGE_LIMIT = 60
        const val RANK_API_LIMIT = 20
        const val RECOMMEND_PAGE_LIMIT = 20
        const val WEB_SEARCH_PAGE_SIZE = 10
        const val WEB_SEARCH_BATCH = 10
        const val WEB_SEARCH_CONCURRENCY = 3
        const val WEB_SEARCH_RETRY = 2
        const val WEB_SEARCH_RETRY_DELAY_MS = 200L
        const val WEB_SEARCH_QUERY_MAX = 30
        const val SEARCH_BLANK_RETRY_DELAY_MS = 160L

        const val SNAPSHOT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
        const val SNAPSHOT_DEFAULT_ITEMS = 30
        const val SNAPSHOT_MAX_DECODE_ITEMS = 100
        const val SNAPSHOT_MAX_VALUE_CHARS = 200_000
        const val PAGE_CACHE_MAX_ENTRIES = 24
        const val PAGE_CACHE_MAX_ITEMS = 100
        const val PANEL_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
    }

    object Recommend {
        const val FIRST_LIMIT = 60
        const val PAGE_LIMIT = 60
        const val MAX_PAGES_PER_LOAD = 3
        const val MAX_RESTARTS = 2
        const val MAX_REQUESTS_PER_LOAD = 12
        const val DEEP_CURSOR = 120
        const val FIRST_BUDGET_MS = Network.REQUEST_TIMEOUT_MS
        const val QUICK_FILTER_FAILURE_MS = 1_500L
        const val MIN_ITEMS_PER_LOAD = 6

        const val SKIP_LIMIT = 4
        const val LOOKAHEAD = 10
        const val PREPARED_LIMIT = 8
        const val DETAIL_PREFETCH_LIMIT = 3
        const val DETAIL_LIMIT = 8
        const val WARM_SLOTS = 2
        const val WARM_SLOTS_METERED = 1
        const val BATCH_MODEL_DELAY_MS = 3_000L
        const val FEED_DELAY_MS = 8_000L

        const val CACHE_TTL_MS = 30L * 60L * 1_000L
        const val CACHE_MAX_ITEMS = 60
        const val MARKS_LIMIT = 1_200
        const val FILTER_IDS_LIMIT = 200
        const val FILTER_IDS_SENT_LIMIT = 300
        const val FILTER_MISS_LIMIT = 2
        const val TASTE_HALF_LIFE_DAYS = 14.0

        const val QUICK_SWIPE_MS = 1_200L
        const val WATCH_DWELL_MS = 6_000L
        const val WATCH_PLAY_MS = 5_000L
        const val REPORT_DEFAULT_DURATION_MS = 10_000L
    }

    object Playback {
        const val URL_FRESH_MS = 10L * 60L * 1_000L
        const val PREPARED_FRESH_MS = URL_FRESH_MS
        const val INDEX_TTL_MS = 7L * 24L * 60L * 60L * 1_000L
        const val CACHE_MAX_BYTES = 48L * 1024L * 1024L
        const val INDEX_MAX_FILE_BYTES = 16L * 1024L * 1024L
        const val INDEX_MEMORY_LIMIT = 3
        const val HEAD_MAX_BYTES = 1024L * 1024L
        const val HEAD_MAX_BYTES_INT = 1024 * 1024
        const val HEAD_CHUNK_BYTES = 512L * 1024L
        const val WARM_BYTES = 1024L * 1024L
        const val HEAD_WAIT_MS = 3_000L
        const val DATA_FIRST_CHUNK_BYTES = 512L * 1024L
        const val DATA_BUFFER_CHUNK_BYTES = 512L * 1024L

        const val MODEL_SPACING_MS = 200L
        const val PLAYER_MODEL_BATCH_LIMIT = 15
        const val PRELOAD_MODEL_LIMIT = 6
        const val MODEL_CACHE_LIMIT = 32
        const val STREAM_CACHE_LIMIT = 8
        const val MAX_DANMAKU = 1_000
        const val HOT_COMMENT_COUNT = 200
        const val HOT_COMMENT_WINDOW_MS = 30_000L
        const val MAX_HOT_COMMENT_WINDOWS = 40
        const val MAX_HOT_DANMAKU = 3_000
        const val HOT_COMMENT_THROTTLE_MS = 180L

        const val COMPLETE_TAIL_MS = 3_000L
        const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
        const val PREFETCH_WINDOW_MS = 30_000L
        const val WATCHDOG_NO_FRAME_MS = 15_000L
        const val WATCHDOG_STALL_MS = 90_000L
        const val WATCHDOG_TICK_MS = 3_000L
        const val FRAME_STALL_TICK_MS = 4_000L
        const val FRAME_STALL_MS = 12_000L
        const val BUFFER_STALL_MS = 30_000L
        const val PICTURE_RECOVERY_LIMIT = 2
        const val PICTURE_RECOVERY_COOLDOWN_MS = 20_000L
        const val FORMAL_PICTURE_RECOVERY_LIMIT = 1
        const val FORMAL_PICTURE_RECOVERY_COOLDOWN_MS = 20_000L
        const val PRELOAD_MAX_TARGETS = 4

        const val PHONE_PREFETCH_START_MS = 2_000L
        const val PHONE_PREFETCH_URL_RESERVE_MS = 30_000L
        const val PHONE_MODEL_QUEUE_SIZE = 14
        const val MODE_TRANSITION_MS = 220L

        object Preload {
            const val FORWARD_WINDOW = 3
            const val BACKWARD_WINDOW = 2
            const val L1_DEPTH = 1
            const val L2_DEPTH = 2
            const val FLING_PER_ITEM_MS = 500L
            const val FLING_STREAK = 3
            const val SLOW_SLIDE_MS = 1_000L
            const val REVISIT_MS = 3_000L
            const val DWELL_PROMOTE_MS = 600L
            const val FIRST_FRAME_DELAY_MS = 300L
            const val RETRY_DELAY_MS = 750L
            const val DWELL_DELAY_MS = 600L
            const val PHONE_MAX_FULL = 2
            const val PHONE_CLOSE_DELAY_MS = 1_000L
        }

        object TvBuffer {
            const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 1_000
            const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000
            const val EWMA_OLD_WEIGHT = 0.7f
            const val EWMA_NEW_WEIGHT = 0.3f
            const val EWMA_SAMPLE_MAX_MS = 120_000L
            const val SLOW_FIRST_FRAME_MS = 7_000L
            const val FAST_FIRST_FRAME_MAX_MS = 3_000L

            const val LOW_END_MIN_BUFFER_MS = 30_000
            const val LOW_END_MAX_BUFFER_MS = 60_000
            const val LOW_END_TARGET_BYTES = 16 * 1024 * 1024
            const val SLOW_MIN_BUFFER_MS = 30_000
            const val SLOW_MAX_BUFFER_MS = 90_000
            const val SLOW_TARGET_BYTES = 24 * 1024 * 1024
            const val FAST_MIN_BUFFER_MS = 10_000
            const val FAST_MAX_BUFFER_MS = 30_000
            const val FAST_TARGET_BYTES = 16 * 1024 * 1024
            const val BALANCED_MIN_BUFFER_MS = 20_000
            const val BALANCED_MAX_BUFFER_MS = 60_000
            const val BALANCED_TARGET_BYTES = 24 * 1024 * 1024
        }
    }

    object Library {
        const val BACKUP_MAX_BYTES = 2 * 1024 * 1024
        const val TRANSFER_MAX_TTL_MS = 24L * 60L * 60L * 1_000L
        const val HOME_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1_000L
        const val HOME_CACHE_MAX_ITEMS = 30
        const val WATCH_SYNC_SAME_MS = 2_000L
        const val WATCH_SYNC_SKEW_MS = 120_000L
        const val HISTORY_PUSHED_LIMIT = 300
        const val PLAYER_PANEL_TIMEOUT_MIN_SECONDS = 2
        const val PLAYER_PANEL_TIMEOUT_MAX_SECONDS = 15
        const val PLAYER_PANEL_TIMEOUT_DEFAULT_SECONDS = 6
    }

    object Danmaku {
        const val REPEAT_WINDOW_MS = 20_000L
        const val MERGE_MIN_COUNT = 2
        const val MERGE_MAX_COUNT = 10
        const val HIGHLIGHT_MIN_COUNT = 2
        const val HIGHLIGHT_MAX_SLOTS = 3
        const val HIGHLIGHT_MIN_DWELL_MS = 7_000L
        const val SCALE_MIN = 0.6f
        const val SCALE_MAX = 2f
        const val SCALE_STEP = 0.1f
        const val ALPHA_MIN = 0.2f
        const val ALPHA_MAX = 1f
        const val ALPHA_STEP = 0.1f

        const val BASE_SCROLL_SECONDS = 6f
        const val BOTTOM_INSET_DP = 120
        const val LANE_GAP_TEXT = 0.9f
        const val LANE_GAP_VERTICAL = 0.35f
        const val LANE_MIN_GAP_DP = 12
        const val MAX_DELAY_MS = 4_000L
        const val HIGHLIGHT_SCALE = 1.25f
        const val HIGHLIGHT_PAD_DP = 13
        const val HIGHLIGHT_COUNTER_FULL_COUNT = 40
        const val HIGHLIGHT_COUNTER_MAX_SCALE = 1.4f
        const val HIGHLIGHT_COUNTER_WARM_COLOR = 0xFFFFC857.toInt()
        const val HIGHLIGHT_COUNTER_HOT_COLOR = 0xFFFF5722.toInt()
        const val HIGHLIGHT_COUNTER_PULSE_MIN_COUNT = 20
        const val HIGHLIGHT_COUNTER_PULSE_DURATION_MS = 420L
        const val HIGHLIGHT_COUNTER_PULSE_MAX_SCALE = 1.6f
        const val EMOJI_SCALE = 1.2f
        const val CACHE_LINES = 4_096
        const val SPRITE_AHEAD_MS = 60_000L
        const val SPRITE_BEHIND_MS = 8_000L
        const val SPRITE_REFRESH_MS = 30_000L
        const val SCHEDULE_AHEAD_MS = 30_000L
        const val LINE_RETENTION_MS = 1_000L
        const val REPLACE_ACTIVE_GRACE_MS = 500L
        const val SEEK_BACK_TOLERANCE_MS = 250L
        const val LINE_POOL_LIMIT = 256
        const val LOW_END_SURFACE_MAX_WIDTH = 1_280
        const val LOW_END_SURFACE_MAX_HEIGHT = 720
    }

    object Ui {
        const val TOAST_MS = 1_900L
        const val TOAST_FADE_MS = 120L
        const val TOAST_MARGIN_DP = 34
        const val DIALOG_EXIT_FADE_MS = 120L
        const val TAB_DOUBLE_TAP_MS = 320L
        const val EXIT_DOUBLE_BACK_MS = 2_000L
        const val UPSMS_POLL_MS = 4_000L
        const val UPSMS_FIRST_POLL_MS = 8_000L
        const val QR_FAILURE_RETRY_MS = 3_000L
        const val ACCOUNT_RETRY_MS = 4_000L
        const val ACCOUNT_RETRIES = 2
        const val ACCOUNT_FRESH_MS = 60_000L
        const val HANDOFF_WINDOW_MS = 3_000L
        const val ADVANCE_GUARD_MS = 5_000L
        const val QUEUED_NEAR_END_MS = 2_000L
        const val QUEUED_RECOVER_LIMIT = 1
        const val HOME_FIRST_BATCH = 1
        const val HOME_FIRST_CARDS = 1
        const val HOME_BATCH_BUDGET_MS = 6L
        const val HOME_BATCH_BUDGET_LOW_RAM_MS = 3L
        const val HOME_SHELF_SIZE = 10
        const val PROFILE_AVATAR_DP = 40
        const val GRID_COLUMNS_DEFAULT = 6
        const val PREFETCH_ROWS = 12
        const val QR_LOGIN_SIZE_DP = 250
        const val PAGER_BASE_DURATION_MS = 300f
        const val PAGER_MAX_DURATION_MS = 2_000L

        const val DEBUG_TICK_MS = 1_000L
        const val DEBUG_TICK_IDLE_MS = 2_000L
        const val DEBUG_TICK_SLOW_MS = 5_000L
        const val DEBUG_IDLE_BACKOFF = 3
        const val DEBUG_IDLE_DEEP = 8
        const val DEBUG_ENV_TTL_MS = 5_000L
    }
}
