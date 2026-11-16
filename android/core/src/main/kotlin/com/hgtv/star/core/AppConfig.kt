// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 应用级可调参数的唯一来源，供 core、shared、手机端和电视端共同引用。
 *
 * 使用约定：
 * - 需要调网络、启动、缓存、推荐、播放、弹幕或交互节奏时，优先只改本文件。
 * - 协议字段名、设备身份、状态机枚举、颜色 / 圆角 / 字体等设计令牌仍留在各自模块。
 * - 原文件里的公开常量继续作为兼容别名，但不要再在别名处写第二份数值。
 */
object AppConfig {
    /**
     * 网络请求、DNS、TNC 和会话保活参数。
     *
     * 除特别标注外，时间单位为毫秒；OkHttp 的 timeout 使用秒。
     */
    object Network {
        /** 业务请求共用的连接 / 读取 / 写入 / 整次调用超时，单位秒。 */
        const val REQUEST_TIMEOUT_SECONDS = 20L

        /** [REQUEST_TIMEOUT_SECONDS] 对应的毫秒值，供协程、预算和本地同步使用。 */
        const val REQUEST_TIMEOUT_MS = REQUEST_TIMEOUT_SECONDS * 1_000L

        /** HttpURLConnection 只接受 Int 毫秒，避免调用点各写一份 20_000。 */
        const val REQUEST_TIMEOUT_MS_INT = 20_000

        /** DNS 成功结果在内存中的有效期；短视频 CDN 在一次观看会话内通常不变。 */
        const val DNS_MEMORY_TTL_MS = 10L * 60L * 1_000L

        /** DNS 落盘结果直接视为有效的时长。 */
        const val DNS_DISK_TTL_MS = 30L * 60L * 1_000L

        /** 系统 DNS 失败时允许使用旧落盘 IP 的兜底窗口；视频 CDN 不走这条兜底。 */
        const val DNS_FALLBACK_TTL_MS = 30L * 60L * 1_000L

        /** DNS 内存表最多保存多少主机，超过后整体清空，避免冷启动优化无限增长。 */
        const val DNS_MAX_HOSTS = 32

        /** 启动时等待 DNS 磁盘缓存载入的上限，超过就直接走系统解析。 */
        const val DNS_DISK_READY_WAIT_MS = 300L

        /** 单个公共 DNS 服务器超时；UDP 失败后同一条查询还可能再走 TCP。 */
        const val PUBLIC_DNS_TIMEOUT_MS = 1_500

        /** TNC 服务端没有下发更新周期时使用的默认刷新间隔。 */
        const val TNC_DEFAULT_UPDATE_INTERVAL_MS = 60L * 60L * 1_000L

        /** 服务端下发过短刷新周期时的下限，避免频繁请求配置。 */
        const val TNC_MIN_UPDATE_INTERVAL_MS = 5L * 60L * 1_000L

        /** 服务端下发过长刷新周期时的上限，避免配置长期不更新。 */
        const val TNC_MAX_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1_000L

        /** TNC 刷新前等待本地快照读取完成的最长时间。 */
        const val TNC_LOAD_WAIT_MS = 300L

        /** TNC 连续失败后的最大退避时间。 */
        const val TNC_MAX_BACKOFF_MS = 30L * 60L * 1_000L

        /** 会话心跳循环间隔；对齐原版 10 分钟心跳。 */
        const val SESSION_HEARTBEAT_INTERVAL_MS = 600_000L

        /** 会话循环自检延迟，用于发现心跳线程没有正常启动的情况。 */
        const val SESSION_SELF_CHECK_DELAY_MS = 60_000L

        /** frontier 通道恢复后的短延迟，给会话状态一个稳定的切换窗口。 */
        const val SESSION_FRONTIER_RESUME_MS = 3_000L

        /** 回到前台时两次补心跳之间的最小间隔。 */
        const val SESSION_FOREGROUND_MIN_GAP_MS = 60_000L

        /** 距上次成功心跳超过这个时间，回前台时补一次 token 续期。 */
        const val SESSION_RENEW_INTERVAL_MS = 30L * 60_000L

        /** 会话剩余不足这个时间时才提前续期，避免正常状态下多发请求。 */
        const val SESSION_RENEW_BEFORE_MS = 3L * 24L * 60L * 60L * 1_000L

        /** 封面图片请求超时，单位秒。 */
        const val IMAGE_TIMEOUT_SECONDS = 20L

        /** 局域网同步 HTTP 请求超时。 */
        const val LAN_TIMEOUT_MS = REQUEST_TIMEOUT_MS

        /** 扫码登录轮询间隔；过短会增加平台压力，过长会让确认状态延迟。 */
        const val QR_POLL_MS = 1_100L
    }

    /**
     * 启动关键路径预算。
     *
     * 这些预算到点后会取消对应请求，避免注册、推荐、详情或播放准备无限卡在加载态。
     */
    object Startup {
        /** 设备注册阶段最多等待多久。 */
        const val REGISTER_MS = Network.REQUEST_TIMEOUT_MS

        /** 推荐首屏首次请求预算；快照补齐和后续翻页不使用这个预算。 */
        const val FEED_FIRST_MS = Network.REQUEST_TIMEOUT_MS

        /** 详情首屏请求预算。 */
        const val DETAIL_MS = Network.REQUEST_TIMEOUT_MS

        /** 取播放地址阶段预算。 */
        const val STREAM_MS = Network.REQUEST_TIMEOUT_MS

        /** 播放器准备阶段预算。 */
        const val PREPARE_MS = Network.REQUEST_TIMEOUT_MS

        /** 首帧后普通延迟任务的兜底执行时间。 */
        const val DEFERRED_TASK_TIMEOUT_MS = 20_000L

        /** 首帧后 latest 任务的兜底执行时间；通常用于弹幕这类可覆盖任务。 */
        const val DEFERRED_LATEST_TIMEOUT_MS = 30_000L
    }

    /**
     * 目录、搜索、分页与目录缓存参数。
     */
    object Catalog {
        /** 逻辑页大小；榜单、分类和搜索都按这个条数归一。 */
        const val PAGE_SIZE = 100

        /** 首屏逻辑页大小；保留独立名称，便于以后单独调整首屏。 */
        const val FIRST_PAGE_SIZE = PAGE_SIZE

        /** 已加载内容只剩这么多条时提前取下一页。 */
        const val PREFETCH_REMAINING = 50

        /** 服务端允许的最大目录页码，防止异常游标无限翻页。 */
        const val MAX_PAGE = 100

        /** 一次追加失败后最多连续补取几页。 */
        const val PREFETCH_PAGES = 2

        /** 自动续页失败后的重试冷却时间。 */
        const val RETRY_COOLDOWN_MS = 3_000L

        /** 分类首屏请求条数。 */
        const val CATEGORY_FIRST_LIMIT = 20

        /** 分类后续页请求条数。 */
        const val CATEGORY_PAGE_LIMIT = 60

        /** 榜单接口单页请求条数。 */
        const val RANK_API_LIMIT = 20

        /** 推荐接口单页请求条数。 */
        const val RECOMMEND_PAGE_LIMIT = 20

        /** 官网搜索每页固定条数。 */
        const val WEB_SEARCH_PAGE_SIZE = 10

        /** 官网搜索单次批量拼接页数。 */
        const val WEB_SEARCH_BATCH = 10

        /** 官网搜索并发请求上限，过高会放大弱网下的超时概率。 */
        const val WEB_SEARCH_CONCURRENCY = 3

        /** 官网搜索失败后的重试次数。 */
        const val WEB_SEARCH_RETRY = 2

        /** 官网搜索重试间隔。 */
        const val WEB_SEARCH_RETRY_DELAY_MS = 200L

        /** 搜索关键词最大长度，手机和电视共用。 */
        const val WEB_SEARCH_QUERY_MAX = 30

        /** App 搜索返回空正文时的短重试间隔。 */
        const val SEARCH_BLANK_RETRY_DELAY_MS = 160L

        /** 首页 / 分类快照的最长使用时间。 */
        const val SNAPSHOT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L

        /** 快照编码默认保留的卡片数。 */
        const val SNAPSHOT_DEFAULT_ITEMS = 30

        /** 解码快照时允许的最大卡片数，防止坏文件撑爆内存。 */
        const val SNAPSHOT_MAX_DECODE_ITEMS = 100

        /** 单份快照 JSON 最大字符数。 */
        const val SNAPSHOT_MAX_VALUE_CHARS = 200_000

        /** 分类 / 榜单首屏缓存最多保留多少个筛选组合。 */
        const val PAGE_CACHE_MAX_ENTRIES = 24

        /** 每个缓存条目最多保存多少张卡片。 */
        const val PAGE_CACHE_MAX_ITEMS = 100

        /** 分类筛选面板缓存有效期；筛选项变化很慢，可以比内容缓存更长。 */
        const val PANEL_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
    }

    /**
     * 推荐流的取页、重试、去重、预热和行为上报参数。
     */
    object Recommend {
        /** 推荐首屏请求条数。 */
        const val FIRST_LIMIT = 60

        /** 推荐后续页请求条数。 */
        const val PAGE_LIMIT = 60

        /** 单次加载最多连续处理多少页，防止空页链无限循环。 */
        const val MAX_PAGES_PER_LOAD = 3

        /** 空页 / 游标停滞时最多重启几次推荐流。 */
        const val MAX_RESTARTS = 2

        /** 单次加载总请求上限，是所有重试分支的最后保险。 */
        const val MAX_REQUESTS_PER_LOAD = 12

        /** 游标达到这个深度后，首屏加载会主动重置一次流头。 */
        const val DEEP_CURSOR = 120

        /** 首屏最多等多久；超时后先展示手里已有内容。 */
        const val FIRST_BUDGET_MS = Network.REQUEST_TIMEOUT_MS

        /** 带过滤条件快速失败时，连续两次后关闭 filter_ids。 */
        const val QUICK_FILTER_FAILURE_MS = 1_500L

        /** 首屏至少尝试凑到多少条。 */
        const val MIN_ITEMS_PER_LOAD = 6

        /** 推荐流连续播放失败多少条后停止自动翻页。 */
        const val SKIP_LIMIT = 4

        /** 当前卡片之后预留多少条，用于快速翻页和预取。 */
        const val LOOKAHEAD = 10

        /** 推荐页最多保留多少份已准备播放结果。 */
        const val PREPARED_LIMIT = 8

        /** 详情预取并发上限。 */
        const val DETAIL_PREFETCH_LIMIT = 3

        /** 推荐详情内存缓存条数。 */
        const val DETAIL_LIMIT = 8

        /** 非计费网络下最多预热多少个详情槽位。 */
        const val WARM_SLOTS = 2

        /** 计费网络下只保留一个详情预热槽位。 */
        const val WARM_SLOTS_METERED = 1

        /** 推荐卡片首帧稳定后，延迟多久批量预取播放模型。 */
        const val BATCH_MODEL_DELAY_MS = 3_000L

        /** 推荐流后台补页延迟，避免和首屏播放抢带宽。 */
        const val FEED_DELAY_MS = 8_000L

        /** 推荐快照和详情缓存有效期。 */
        const val CACHE_TTL_MS = 30L * 60L * 1_000L

        /** 推荐快照最多保存多少张卡片。 */
        const val CACHE_MAX_ITEMS = 60

        /** 曝光窗口最多记录多少条 id / 标题键。 */
        const val MARKS_LIMIT = 1_200

        /** 本地过滤窗口保存的曝光条数上限。 */
        const val FILTER_IDS_LIMIT = 200

        /** 回传给服务端的 filter_ids 最多带多少条。 */
        const val FILTER_IDS_SENT_LIMIT = 300

        /** 带过滤条件连续回空多少次后永久关闭该条件。 */
        const val FILTER_MISS_LIMIT = 2

        /** 推荐兴趣权重半衰期，单位天。 */
        const val TASTE_HALF_LIFE_DAYS = 14.0

        /** 停留低于这个时间算快速划走。 */
        const val QUICK_SWIPE_MS = 1_200L

        /** 停留达到这个时间算认真观看。 */
        const val WATCH_DWELL_MS = 6_000L

        /** 实际播放达到这个时间也算认真观看。 */
        const val WATCH_PLAY_MS = 5_000L

        /** 推荐曝光上报服务端未配置窗口时使用的默认批量时长。 */
        const val REPORT_DEFAULT_DURATION_MS = 10_000L
    }

    /**
     * 播放地址、缓存、预取、看门狗和进度保存参数。
     */
    object Playback {
        /** 播放地址统一有效期；内存、磁盘和预热结果共用。 */
        const val URL_FRESH_MS = 10L * 60L * 1_000L

        /** 已准备播放结果的有效期，必须与地址有效期一致。 */
        const val PREPARED_FRESH_MS = URL_FRESH_MS

        /** 视频索引缓存兜底寿命。 */
        const val INDEX_TTL_MS = 7L * 24L * 60L * 60L * 1_000L

        /** 播放缓存目录总大小上限。 */
        const val CACHE_MAX_BYTES = 48L * 1024L * 1024L

        /** 单份视频索引文件大小上限。 */
        const val INDEX_MAX_FILE_BYTES = 16L * 1024L * 1024L

        /** 内存中保留的索引对象数量。 */
        const val INDEX_MEMORY_LIMIT = 3

        /** 首块缓存文件最大字节数，Long 版本用于大小计算。 */
        const val HEAD_MAX_BYTES = 1024L * 1024L

        /** 首块缓存文件最大字节数，Int 版本用于数组长度校验。 */
        const val HEAD_MAX_BYTES_INT = 1024 * 1024

        /** 首帧只需要的前块大小。 */
        const val HEAD_CHUNK_BYTES = 512L * 1024L

        /** 下一集预热时保留的原始视频字节数。 */
        const val WARM_BYTES = 1024L * 1024L

        /** 等待别处预热首块的上限，超时后自己发 Range。 */
        const val HEAD_WAIT_MS = 3_000L

        /** 播放数据源第一次读取大小。 */
        const val DATA_FIRST_CHUNK_BYTES = 512L * 1024L

        /** 播放数据源后续每次读取大小。 */
        const val DATA_BUFFER_CHUNK_BYTES = 512L * 1024L

        /** 两次播放模型请求之间的最小间隔。 */
        const val MODEL_SPACING_MS = 200L

        /** 播放器批量模型单次请求上限。 */
        const val PLAYER_MODEL_BATCH_LIMIT = 15

        /** 后台预加载模型单次请求上限。 */
        const val PRELOAD_MODEL_LIMIT = 6

        /** 播放模型内存缓存条数。 */
        const val MODEL_CACHE_LIMIT = 32

        /** 播放地址内存缓存条数。 */
        const val STREAM_CACHE_LIMIT = 8

        /** 单集弹幕最大条数。 */
        const val MAX_DANMAKU = 1_000

        /** 热弹幕单次请求条数。 */
        const val HOT_COMMENT_COUNT = 200

        /** 热弹幕时间窗口宽度。 */
        const val HOT_COMMENT_WINDOW_MS = 30_000L

        /** 热弹幕窗口缓存上限。 */
        const val MAX_HOT_COMMENT_WINDOWS = 40

        /** 热弹幕总量上限，防止异常响应造成过大内存占用。 */
        const val MAX_HOT_DANMAKU = 3_000

        /** 热弹幕请求最小节流间隔。 */
        const val HOT_COMMENT_THROTTLE_MS = 180L

        /** 距片尾多少毫秒算已看完。 */
        const val COMPLETE_TAIL_MS = 3_000L

        /** 播放进度落盘采样间隔。 */
        const val PROGRESS_SAVE_INTERVAL_MS = 15_000L

        /** 距片尾多少毫秒开始预取下一集。 */
        const val PREFETCH_WINDOW_MS = 30_000L

        /** READY 后多久仍无画面就判“有声音没画面”。 */
        const val WATCHDOG_NO_FRAME_MS = 15_000L

        /** 起播后多久仍未进入可播放状态就判缓冲卡死。 */
        const val WATCHDOG_STALL_MS = 90_000L

        /** 画面看门狗轮询间隔。 */
        const val WATCHDOG_TICK_MS = 3_000L

        /** 正式播放停帧检测轮询间隔。 */
        const val FRAME_STALL_TICK_MS = 4_000L

        /** 连续多久没有新帧算停帧。 */
        const val FRAME_STALL_MS = 12_000L

        /** 连续多久没有缓冲推进算缓冲卡死。 */
        const val BUFFER_STALL_MS = 30_000L

        /** 推荐页画面异常时允许的自动恢复次数。 */
        const val PICTURE_RECOVERY_LIMIT = 2

        /** 推荐页两次画面恢复之间的冷却时间。 */
        const val PICTURE_RECOVERY_COOLDOWN_MS = 20_000L

        /** 正式播放页画面异常时允许的自动恢复次数。 */
        const val FORMAL_PICTURE_RECOVERY_LIMIT = 1

        /** 正式播放页画面恢复冷却时间。 */
        const val FORMAL_PICTURE_RECOVERY_COOLDOWN_MS = 20_000L

        /** 电视相邻内容预加载最多同时保留多少个目标。 */
        const val PRELOAD_MAX_TARGETS = 4

        /** 手机播放器首帧后多久开始预取相邻集。 */
        const val PHONE_PREFETCH_START_MS = 2_000L

        /** 手机预取地址时给播放器预留的有效期。 */
        const val PHONE_PREFETCH_URL_RESERVE_MS = 30_000L

        /** 手机播放模型队列长度。 */
        const val PHONE_MODEL_QUEUE_SIZE = 14

        /** 手机横竖屏切换时控制层过渡时长。 */
        const val MODE_TRANSITION_MS = 220L

        /**
         * 相邻内容滑动预取规则。
         */
        object Preload {
            /** 主方向窗口大小，包含当前项之后的完整预热深度。 */
            const val FORWARD_WINDOW = 3

            /** 反方向窗口大小，保证用户回看时也有准备。 */
            const val BACKWARD_WINDOW = 2

            /** 主方向前几项做完整预热。 */
            const val L1_DEPTH = 1

            /** 主方向前几项做元数据预热。 */
            const val L2_DEPTH = 2

            /** 两次切项间隔低于此值算一次快滑。 */
            const val FLING_PER_ITEM_MS = 500L

            /** 连续快滑多少次进入 FLING 模式。 */
            const val FLING_STREAK = 3

            /** 超过这个停留时间算慢滑或认真停留。 */
            const val SLOW_SLIDE_MS = 1_000L

            /** 滑回来多快算回看并提升预热等级。 */
            const val REVISIT_MS = 3_000L

            /** 停留多久把主方向目标提升到完整预热。 */
            const val DWELL_PROMOTE_MS = 600L

            /** 首帧后延迟多久开始相邻预取。 */
            const val FIRST_FRAME_DELAY_MS = 300L

            /** 缓冲不足让路后，隔多久重新检查。 */
            const val RETRY_DELAY_MS = 750L

            /** 快滑结束后停留多久确认预取。 */
            const val DWELL_DELAY_MS = 600L

            /** 手机端最多保留多少份完整预热。 */
            const val PHONE_MAX_FULL = 2

            /** 淘汰预热对象后延迟关闭的时间，等待旧 MediaSource 释放。 */
            const val PHONE_CLOSE_DELAY_MS = 1_000L
        }

        /**
         * 电视 ExoPlayer 缓冲档位。
         *
         * 首帧 EWMA 只用于下一次构建播放器，不会在播放中热切换缓冲参数。
         */
        object TvBuffer {
            /** 达到可播放状态所需的最小缓冲时间。 */
            const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 1_000

            /** 重缓冲后恢复播放所需的最小缓冲时间。 */
            const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

            /** 首帧耗时 EWMA 的旧值权重。 */
            const val EWMA_OLD_WEIGHT = 0.7f

            /** 首帧耗时 EWMA 的新值权重。 */
            const val EWMA_NEW_WEIGHT = 0.3f

            /** 首帧样本超过这个时间就丢弃，避免异常值污染档位。 */
            const val EWMA_SAMPLE_MAX_MS = 120_000L

            /** 首帧超过这个时间按慢网络档位构建播放器。 */
            const val SLOW_FIRST_FRAME_MS = 7_000L

            /** 首帧低于这个时间按快速设备档位构建播放器。 */
            const val FAST_FIRST_FRAME_MAX_MS = 3_000L

            /** 低配电视最小缓冲。 */
            const val LOW_END_MIN_BUFFER_MS = 30_000

            /** 低配电视最大缓冲。 */
            const val LOW_END_MAX_BUFFER_MS = 60_000

            /** 低配电视字节保护上限。 */
            const val LOW_END_TARGET_BYTES = 16 * 1024 * 1024

            /** 慢网络最小缓冲。 */
            const val SLOW_MIN_BUFFER_MS = 30_000

            /** 慢网络最大缓冲。 */
            const val SLOW_MAX_BUFFER_MS = 90_000

            /** 慢网络字节保护上限。 */
            const val SLOW_TARGET_BYTES = 24 * 1024 * 1024

            /** 快速设备最小缓冲。 */
            const val FAST_MIN_BUFFER_MS = 10_000

            /** 快速设备最大缓冲。 */
            const val FAST_MAX_BUFFER_MS = 30_000

            /** 快速设备字节保护上限。 */
            const val FAST_TARGET_BYTES = 16 * 1024 * 1024

            /** 均衡档最小缓冲。 */
            const val BALANCED_MIN_BUFFER_MS = 20_000

            /** 均衡档最大缓冲。 */
            const val BALANCED_MAX_BUFFER_MS = 60_000

            /** 均衡档字节保护上限。 */
            const val BALANCED_TARGET_BYTES = 24 * 1024 * 1024
        }
    }

    /**
     * 本机书库、备份和设置默认值。
     */
    object Library {
        /** 本机备份 JSON 最大字节数。 */
        const val BACKUP_MAX_BYTES = 2 * 1024 * 1024

        /** 局域网传输会话最长有效期。 */
        const val TRANSFER_MAX_TTL_MS = 24L * 60L * 60L * 1_000L

        /** 首页榜单快照最长使用时间。 */
        const val HOME_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1_000L

        /** 首页每个剧架最多缓存多少张卡片。 */
        const val HOME_CACHE_MAX_ITEMS = 30

        /** 账号进度与本机进度时间差在此范围内视为同一时刻。 */
        const val WATCH_SYNC_SAME_MS = 2_000L

        /** 同一集比较进度时，时钟偏差小于此值仍以片内位置为准。 */
        const val WATCH_SYNC_SKEW_MS = 120_000L

        /** 已推送进度记录的内存表上限。 */
        const val HISTORY_PUSHED_LIMIT = 300

        /** 播放设置面板自动收起的最小秒数。 */
        const val PLAYER_PANEL_TIMEOUT_MIN_SECONDS = 2

        /** 播放设置面板自动收起的最大秒数。 */
        const val PLAYER_PANEL_TIMEOUT_MAX_SECONDS = 15

        /** 播放设置面板自动收起的出厂默认秒数。 */
        const val PLAYER_PANEL_TIMEOUT_DEFAULT_SECONDS = 6
    }

    /**
     * 弹幕聚合、突出显示、绘制窗口和低配 Surface 参数。
     */
    object Danmaku {
        /** 相同内容停发超过这个时间后重新开始计数。 */
        const val REPEAT_WINDOW_MS = 20_000L

        /** 聚合功能允许的最小重复条数。 */
        const val MERGE_MIN_COUNT = 2

        /** 聚合功能允许的最大重复条数。 */
        const val MERGE_MAX_COUNT = 10

        /** 进入中央突出显示所需的最小重复条数。 */
        const val HIGHLIGHT_MIN_COUNT = 2

        /** 中央突出显示最多同时占用几个槽位。 */
        const val HIGHLIGHT_MAX_SLOTS = 3

        /** 中央突出显示的最短停留时间。 */
        const val HIGHLIGHT_MIN_DWELL_MS = 7_000L

        /** 弹幕字号缩放下限。 */
        const val SCALE_MIN = 0.6f

        /** 弹幕字号缩放上限。 */
        const val SCALE_MAX = 2f

        /** 弹幕字号滑动步长。 */
        const val SCALE_STEP = 0.1f

        /** 弹幕透明度下限。 */
        const val ALPHA_MIN = 0.2f

        /** 弹幕透明度上限。 */
        const val ALPHA_MAX = 1f

        /** 弹幕透明度滑动步长。 */
        const val ALPHA_STEP = 0.1f

        /** 一条滚动弹幕横穿屏幕的基础时长，单位秒。 */
        const val BASE_SCROLL_SECONDS = 6f

        /** 弹幕层底部避让高度。 */
        const val BOTTOM_INSET_DP = 120

        /** 文字弹幕之间的横向安全间距系数。 */
        const val LANE_GAP_TEXT = 0.9f

        /** 轨道之间的纵向安全间距系数。 */
        const val LANE_GAP_VERTICAL = 0.35f

        /** 轨道最小纵向间距。 */
        const val LANE_MIN_GAP_DP = 12

        /** 单帧延迟排程的最大补偿时间。 */
        const val MAX_DELAY_MS = 4_000L

        /** 中央突出弹幕相对普通弹幕的放大倍数。 */
        const val HIGHLIGHT_SCALE = 1.25f

        /** 中央突出弹幕背景留白。 */
        const val HIGHLIGHT_PAD_DP = 13

        /** 计数达到多少后使用满格视觉。 */
        const val HIGHLIGHT_COUNTER_FULL_COUNT = 40

        /** 计数徽标最大放大倍数。 */
        const val HIGHLIGHT_COUNTER_MAX_SCALE = 1.4f

        /** 计数徽标温热状态颜色。 */
        const val HIGHLIGHT_COUNTER_WARM_COLOR = 0xFFFFC857.toInt()

        /** 计数徽标高热状态颜色。 */
        const val HIGHLIGHT_COUNTER_HOT_COLOR = 0xFFFF5722.toInt()

        /** 计数达到多少后开始脉冲。 */
        const val HIGHLIGHT_COUNTER_PULSE_MIN_COUNT = 20

        /** 计数脉冲时长。 */
        const val HIGHLIGHT_COUNTER_PULSE_DURATION_MS = 420L

        /** 计数脉冲最大放大倍数。 */
        const val HIGHLIGHT_COUNTER_PULSE_MAX_SCALE = 1.6f

        /** 表情图相对文字字号的比例。 */
        const val EMOJI_SCALE = 1.2f

        /** 文本测量缓存条数。 */
        const val CACHE_LINES = 4_096

        /** 弹幕精灵图提前加载窗口。 */
        const val SPRITE_AHEAD_MS = 60_000L

        /** 弹幕精灵图保留的历史窗口。 */
        const val SPRITE_BEHIND_MS = 8_000L

        /** 精灵图刷新间隔。 */
        const val SPRITE_REFRESH_MS = 30_000L

        /** 弹幕排程提前量。 */
        const val SCHEDULE_AHEAD_MS = 30_000L

        /** 已结束弹幕行保留时间。 */
        const val LINE_RETENTION_MS = 1_000L

        /** 替换当前活动行时允许的宽限时间。 */
        const val REPLACE_ACTIVE_GRACE_MS = 500L

        /** 播放时钟回拨容忍时间。 */
        const val SEEK_BACK_TOLERANCE_MS = 250L

        /** 弹幕行对象池上限。 */
        const val LINE_POOL_LIMIT = 256

        /** 低配设备弹幕 Surface 最大宽度。 */
        const val LOW_END_SURFACE_MAX_WIDTH = 1_280

        /** 低配设备弹幕 Surface 最大高度。 */
        const val LOW_END_SURFACE_MAX_HEIGHT = 720
    }

    /**
     * 页面交互时长、登录轮询、账号刷新和调试面板节奏。
     */
    object Ui {
        /** 电视 Toast 显示时长。 */
        const val TOAST_MS = 1_900L

        /** Toast 淡入淡出时长。 */
        const val TOAST_FADE_MS = 120L

        /** Toast 距屏幕底部距离。 */
        const val TOAST_MARGIN_DP = 34

        /** 弹窗关闭淡出时长，避免电视上整屏亮度瞬跳。 */
        const val DIALOG_EXIT_FADE_MS = 120L

        /** 手机底部导航同一格连点判定窗口。 */
        const val TAB_DOUBLE_TAP_MS = 320L

        /** 手机主界面两次返回退出的判定窗口。 */
        const val EXIT_DOUBLE_BACK_MS = 2_000L

        /** 上行短信验证码自动确认轮询间隔。 */
        const val UPSMS_POLL_MS = 4_000L

        /** 上行短信页首次确认前的等待时间。 */
        const val UPSMS_FIRST_POLL_MS = 8_000L

        /** 扫码失败后的重试间隔。 */
        const val QR_FAILURE_RETRY_MS = 3_000L

        /** 账号数据拉取失败后的自动重试间隔。 */
        const val ACCOUNT_RETRY_MS = 4_000L

        /** 账号数据自动重试次数。 */
        const val ACCOUNT_RETRIES = 2

        /** 账号数据缓存新鲜期。 */
        const val ACCOUNT_FRESH_MS = 60_000L

        /** 播放器在两个页面之间交接时，禁止暂停的保护窗口。 */
        const val HANDOFF_WINDOW_MS = 3_000L

        /** 推荐流片尾推进的防重入标记有效期。 */
        const val ADVANCE_GUARD_MS = 5_000L

        /** 队列项距片尾多近时允许接回当前集。 */
        const val QUEUED_NEAR_END_MS = 2_000L

        /** 同一队列项最多允许接回几次。 */
        const val QUEUED_RECOVER_LIMIT = 1

        /** 首页首屏同步构建的剧架数。 */
        const val HOME_FIRST_BATCH = 1

        /** 首页首屏同步构建的卡片数。 */
        const val HOME_FIRST_CARDS = 1

        /** 首页后续剧架每批构建的帧预算。 */
        const val HOME_BATCH_BUDGET_MS = 6L

        /** 低内存设备首页后续剧架的帧预算。 */
        const val HOME_BATCH_BUDGET_LOW_RAM_MS = 3L

        /** 首页每个剧架一屏显示条数。 */
        const val HOME_SHELF_SIZE = 10

        /** “我的”页头像直径。 */
        const val PROFILE_AVATAR_DP = 40

        /** 电视卡片默认列数。 */
        const val GRID_COLUMNS_DEFAULT = 6

        /** 电视网格预取窗口半径。 */
        const val PREFETCH_ROWS = 12

        /** 电视扫码登录二维码边长。 */
        const val QR_LOGIN_SIZE_DP = 250

        /** 手机 Pager 平滑滚动基础时长。 */
        const val PAGER_BASE_DURATION_MS = 300f

        /** 手机 Pager 平滑滚动最大时长。 */
        const val PAGER_MAX_DURATION_MS = 2_000L

        /** 调试面板正常刷新间隔。 */
        const val DEBUG_TICK_MS = 1_000L

        /** 调试面板内容无变化时的第一档退避间隔。 */
        const val DEBUG_TICK_IDLE_MS = 2_000L

        /** 调试面板长时间无变化时的第二档退避间隔。 */
        const val DEBUG_TICK_SLOW_MS = 5_000L

        /** 连续多少轮无变化后进入第一档退避。 */
        const val DEBUG_IDLE_BACKOFF = 3

        /** 连续多少轮无变化后进入第二档退避。 */
        const val DEBUG_IDLE_DEEP = 8

        /** 调试面板系统状态缓存时间。 */
        const val DEBUG_ENV_TTL_MS = 5_000L
    }
}
