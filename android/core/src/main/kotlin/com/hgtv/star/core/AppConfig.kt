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

        /** 会话响应体只嗅探开头这么多字节，避免为识别失效标记缓存整份正文。 */
        const val SESSION_SNIFF_LIMIT_BYTES = 32L * 1024L

        /** 播放传输连接池最多保留多少条空闲连接。 */
        const val PLAYBACK_TRANSPORT_MAX_IDLE = 4
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

        /** 榜单接口单页返回条数；HAR 的 `cell/change` 固定每页 10 条。 */
        const val RANK_API_LIMIT = 10

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
     * 内容仓库的请求重试、缓存容量和子域上限。
     */
    object Content {
        /** 账号仓库预热后延迟多久开始账号请求，避免和首屏内容抢带宽。 */
        const val ACCOUNT_PRECONNECT_DELAY_MS = 2_000L

        /** 账号读请求失败后的重试次数。 */
        /** 账号写请求失败后的重试次数。 */
        const val ACCOUNT_WRITE_ATTEMPTS = 4

        /** 账号写请求第一次退避时间。 */
        const val ACCOUNT_WRITE_BACKOFF_1_MS = 1_200L

        /** 账号写请求第二次退避时间。 */
        const val ACCOUNT_WRITE_BACKOFF_2_MS = 4_000L

        /** 账号写请求第三次退避时间。 */
        const val ACCOUNT_WRITE_BACKOFF_3_MS = 12_000L

        /** 一次批量详情请求最多带多少集。 */
        const val MULTI_DETAIL_LIMIT = 50

        /** 历史记录删除接口单批最多提交多少条。 */
        const val HISTORY_DELETE_BATCH = 50

        /** 清空历史失败后的重试次数。 */
        const val HISTORY_CLEAR_ATTEMPTS = 3

        /** 相关作品接口最多尝试几个候选域名。 */
        const val RELATED_ATTEMPTS = 8

        /** 相关作品内存缓存条数。 */
        const val RELATED_CACHE_LIMIT = 16

        /** 作者名解析缓存条数。 */
        const val AUTHOR_CACHE_LIMIT = 300

        /** 作者作品结果缓存条数。 */
        const val AUTHOR_WORKS_CACHE_LIMIT = 8

        /** 作者作品每页请求条数。 */
        const val AUTHOR_WORKS_PAGE_SIZE = 50

        /** 作者作品默认请求页数。 */
        const val AUTHOR_WORKS_PAGES = 6

        /** 作者作品最大请求页数。 */
        const val AUTHOR_WORKS_PAGES_MAX = 6

        /** 作者作品单页请求尝试次数。 */
        const val AUTHOR_WORKS_PAGE_ATTEMPTS = 3

        /** 作者作品页重试间隔。 */
        const val AUTHOR_WORKS_PAGE_RETRY_MS = 300L

        /** 连续多少空页后停止继续补作者作品。 */
        const val AUTHOR_WORKS_EMPTY_PAGES = 2

        /** 作者作品按系列并发补查的并发数。 */
        const val AUTHOR_WORKS_FAMILY_FANOUT = 3

        /** 作者作品按系列补查的最大结果数。 */
        const val AUTHOR_WORKS_FAMILY_MAX = 12

        /** 出品方作品每页请求条数。 */
        const val PUBLISHER_PAGE_SIZE = 50

        /** 出品方作品默认请求页数。 */
        const val PUBLISHER_PAGES = 6

        /** 出品方身份缓存上限。 */
        const val PUBLISHER_UIDS_LIMIT = 96

        /** 出品方未命中缓存的有效期。 */
        const val PUBLISHER_MISS_TTL_MS = 600_000L

        /** 分类接口最多尝试次数。 */
        const val CATEGORY_ATTEMPTS = 8

        /** 分类 / 榜单首屏最多尝试次数。 */
        const val FIRST_PAGE_ATTEMPTS = 3

        /** 推荐接口最多尝试次数。 */
        const val RECOMMEND_ATTEMPTS = 3

        /** 搜索游标缓存最多保留多少个查询上下文。 */
        const val SEARCH_CURSOR_LIMIT = 100

        /** 单个关键词最大字符数。 */
        const val SEARCH_KEYWORD_MAX_CHARS = 80

        /** 单次搜索联想词最大返回条数。 */
        const val SUGGESTION_LIMIT = 20

        /** 热搜词最大返回条数。 */
        const val HOT_SEARCH_LIMIT = 30

        /** 官网搜索游标 / 已见集合缓存上限。 */
        const val SEARCH_CACHE_LIMIT = 8

        /** 官网搜索单次返回条数上限。 */
        const val WEB_SEARCH_RESULT_LIMIT = 30

        /** 单个内容响应体最大字节数，防止异常响应撑爆内存。 */
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

        /** 播放模型最多尝试几个 TNC 候选域名。 */
        const val MODEL_HOST_ATTEMPTS = 3

        /** 播放地址专用 OkHttpClient 缓存上限。 */
        const val ADDRESS_CLIENT_CACHE_LIMIT = 8

        /** 播放模型预算分桶步长，减少相近预算生成过多客户端。 */
        const val MODEL_BUDGET_BUCKET_MS = 100L
    }

    /**
     * 账号快照、导入、手机号登录和设备自愈限制。
     */
    object Account {
        /** 账号快照 JSON 最大字节数。 */
        const val SNAPSHOT_MAX_BYTES = 512 * 1024

        /** 账号快照最长使用时间。 */
        const val SNAPSHOT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L

        /** 账号快照每一栏最多保存多少条。 */
        const val SNAPSHOT_MAX_ROWS = 200

        /** 账号快照里单部剧简介最多保留多少字符。 */
        const val SNAPSHOT_DESCRIPTION_CHARS = 400

        /** 导入账号参数页面允许上传的最大文件。 */
        const val IMPORT_MAX_BYTES = 512 * 1024

        /** 手机号最小位数。 */
        const val PHONE_MIN_DIGITS = 6

        /** 手机号最大位数。 */
        const val PHONE_MAX_DIGITS = 15

        /** 上行短信验证流程总窗口。 */
        const val MFA_WINDOW_MS = 10L * 60L * 1_000L

        /** 上行短信连续错误多少次后停止等待。 */
        const val UPSMS_ERROR_LIMIT = 4

        /** 上行短信最多允许多少轮 pending。 */
        const val UPSMS_PENDING_LIMIT = 6

        /** 电视端上行短信服务默认轮询次数。 */
        const val UPSMS_SERVER_POLLS = 5

        /** 电视端上行短信服务轮询间隔。 */
        const val UPSMS_SERVER_POLL_MS = 3_000L

    }

    /**
     * 封面 URL、内存缓存和磁盘缓存参数。
     */
    object Artwork {
        /** 封面请求固定宽度。 */
        const val URL_REQUEST_WIDTH = 640

        /** 封面解码宽度下限。 */
        const val URL_MIN_WIDTH = 160

        /** 封面解码宽度上限。 */
        const val URL_MAX_WIDTH = 640

        /** 封面宽度取整档位。 */
        const val URL_WIDTH_STEP = 80

        /** 封面长宽比下限，百分比。 */
        const val URL_MIN_ASPECT_PERCENT = 70

        /** 图片加载队列最多等待多少个任务。 */
        const val LOADER_MAX_PENDING = 320

        /** 图片网络并发请求上限。 */
        const val LOADER_MAX_REQUESTS = 16

        /** 图片连接池空闲连接上限。 */
        const val LOADER_MAX_IDLE_CONNECTIONS = 16

        /** 单张封面解码后允许的最大像素字节数。 */
        const val LOADER_MAX_DECODE_BYTES = 4 * 1024 * 1024

        /** 图片解码工作线程数。 */
        const val LOADER_WORKERS = 8

        /** 低于这个堆上限时按低内存设备处理。 */
        const val LOADER_HEAP_TIGHT_BYTES = 256L * 1024 * 1024

        /** 封面内存缓存占堆比例的低内存分支分母。 */
        const val LOADER_LOW_RAM_HEAP_DIVISOR = 5

        /** 封面内存缓存占堆比例的常规分支分母。 */
        const val LOADER_NORMAL_HEAP_DIVISOR = 3

        /** 低内存设备封面内存缓存下限。 */
        const val LOADER_LOW_RAM_FLOOR_BYTES = 8L * 1024 * 1024

        /** 常规设备封面内存缓存下限。 */
        const val LOADER_NORMAL_FLOOR_BYTES = 24L * 1024 * 1024

        /** 低内存设备封面内存缓存上限。 */
        const val LOADER_LOW_RAM_CEILING_BYTES = 32L * 1024 * 1024

        /** 常规设备封面内存缓存上限。 */
        const val LOADER_NORMAL_CEILING_BYTES = 192L * 1024 * 1024

        /** 封面解码最长边上限。 */
        const val LOADER_MAX_DECODE_DIMENSION = 1024

        /** 封面磁盘缓存单条上限。 */
        const val DISK_MAX_ENTRY_BYTES = 512 * 1024

        /** 封面磁盘缓存总字节上限。 */
        const val DISK_MAX_TOTAL_BYTES = 96L * 1024 * 1024

        /** 封面磁盘缓存条数上限。 */
        const val DISK_MAX_ENTRIES = 6_000

        /** 磁盘清理后的低水位比例。 */
        const val DISK_PRUNE_TARGET = 0.7

        /** 封面磁盘缓存有效期。 */
        const val DISK_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
    }

    /**
     * 局域网发现、同步包和本地传输会话参数。
     */
    object Lan {
        /** 局域网同步会话默认有效期。 */
        const val DEFAULT_TTL_MS = 300_000L

        /** 发现其他设备的默认等待窗口。 */
        const val DISCOVERY_WINDOW_MS = 6_000L

        /** 同步包单个值最大字节数。 */
        const val MAX_VALUE_BYTES = 128 * 1024

        /** 同步包总字节数上限。 */
        const val MAX_TOTAL_BYTES = 512 * 1024

        /** 本机传输服务允许的最大上传字节数。 */
        const val TRANSFER_MAX_BYTES = 8 * 1024 * 1024

        /** 传输服务单次 socket 读写超时。 */
        const val TRANSFER_SOCKET_TIMEOUT_MS = 2_000

        /** 传输服务 accept 轮询超时。 */
        const val TRANSFER_ACCEPT_TIMEOUT_MS = 250

        /** 单个 HTTP 头最大长度。 */
        const val HEADER_LINE_MAX_BYTES = 8 * 1024

        /** 请求头总长度上限。 */
        const val HEADER_TOTAL_MAX_BYTES = 16 * 1024
    }

    /**
     * 推荐流的取页、重试、去重、预热和行为上报参数。
     */
    object Recommend {
        /** 推荐首屏目标条数。 */
        const val FIRST_LIMIT = 60

        /** 推荐后续页请求条数。 */
        const val PAGE_LIMIT = 20

        /** 单次加载最多连续处理多少页，防止空页链无限循环。 */
        const val MAX_PAGES_PER_LOAD = 12

        /** 列表已有内容时，补页连续遇到这么多重复页就先回到用户操作，稍后再补。 */
        const val MAX_APPEND_EMPTY_PAGES = 2

        /** 空页 / 游标停滞时最多重启几次推荐流。 */
        const val MAX_RESTARTS = 2

        /** 单次加载总请求上限，是所有重试分支的最后保险。 */
        const val MAX_REQUESTS_PER_LOAD = 24

        /** 游标达到这个深度后，首屏加载会主动重置一次流头。 */
        const val DEEP_CURSOR = 120

        /** 首屏最多等多久；超时后先展示手里已有内容。 */
        const val FIRST_BUDGET_MS = Network.REQUEST_TIMEOUT_MS

        /** 带过滤条件快速失败时，连续两次后关闭 filter_ids。 */
        const val QUICK_FILTER_FAILURE_MS = 1_500L

        /** 首屏至少尝试凑到多少条。 */
        const val MIN_ITEMS_PER_LOAD = 60

        /** 推荐流连续播放失败多少条后停止自动翻页。 */
        const val SKIP_LIMIT = 4

        /** 首屏 60 条时，剩余约三分之一（20 条）即开始预取下一页。 */
        const val PREFETCH_REMAINING = 20

        /** 当前卡片之后预留多少条，用于快速翻页和预取。 */
        const val LOOKAHEAD = PREFETCH_REMAINING

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

        /** 推荐页一次批量预取多少个播放链接。 */
        const val BATCH_MODEL_SIZE = 20

        /** 当前锚点消耗多少条后补充下一批播放链接。 */
        const val BATCH_MODEL_REPLENISH_AFTER = 10

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

        /** 推荐取页回传给服务端的当前列表 filter_ids 最多带多少条。 */
        const val FILTER_IDS_SENT_LIMIT = 50

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

        /**
         * 相邻剧集预热保留的原始视频字节数。
         *
         * 当前短剧 720P 的实际码率约 1～2Mbps，2.5MB 覆盖约 2～3 秒；
         * 这是内存预热上限，不改变首帧所需的 [HEAD_CHUNK_BYTES]。
         */
        const val WARM_BYTES = 2_500_000L

        /** 等待别处预热首块的上限，超时后自己发 Range。 */
        const val HEAD_WAIT_MS = 3_000L

        /** 播放数据源第一次读取大小。 */
        const val DATA_FIRST_CHUNK_BYTES = 512L * 1024L

        /** 播放数据源后续每次读取大小。 */
        const val DATA_BUFFER_CHUNK_BYTES = 512L * 1024L

        /** 两次播放模型请求之间的最小间隔。 */
        const val MODEL_SPACING_MS = 200L

        /** 播放器批量模型单次请求上限。 */
        const val PLAYER_MODEL_BATCH_LIMIT = 20

        /** 后台预加载模型单次请求上限。 */
        const val PRELOAD_MODEL_LIMIT = 20

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
        const val PRELOAD_MAX_TARGETS = 5

        /** 手机播放器首帧后多久开始预取相邻集。 */
        const val PHONE_PREFETCH_START_MS = 2_000L

        /** 手机播放模型队列长度。 */
        const val PHONE_MODEL_QUEUE_SIZE = 14

        /** 手机横竖屏切换时控制层过渡时长。 */
        const val MODE_TRANSITION_MS = 220L

        /** PreparedSlot 中预热结果的有效期，刻意短于播放地址窗口。 */
        const val PREPARED_SLOT_TTL_MS = 120_000L

        /** 推荐页连续播放失败多少条后停止自动跳过。 */
        const val FAILURE_RECOMMEND_SKIP_LIMIT = 3

        /** 每集允许同档重建次数。 */
        const val SAME_REBUILD_LIMIT = 1

        /** 每集允许自动降档次数。 */
        const val QUALITY_DOWN_LIMIT = 2

        /** 每集允许手动重试归还次数。 */
        const val MANUAL_RETRY_LIMIT = 2

        /** 统一播放恢复：每个操作层最多实际发起 3 次。 */
        const val RECOVERY_ATTEMPTS = 3

        /** 稳定播放多久后视为新事件，重新获得分层恢复额度。 */
        const val RECOVERY_STABLE_MS = 5_000L

        /** 音频轨每集允许同档重建次数。 */
        const val AUDIO_REBUILD_LIMIT = 1

        /** 快进 / 快退设置的最小秒数。 */
        const val SEEK_MIN_SECONDS = 2

        /** 快进 / 快退设置的最大秒数。 */
        const val SEEK_MAX_SECONDS = 20

        /** 连续快进 / 快退的合并窗口。 */
        const val SEEK_MERGE_WINDOW_MS = 250L

        /** 片尾保护时间，避免提前触发自动连播。 */
        const val SEEK_TAIL_GUARD_MS = 500L

        /** 从第几次连续操作开始加速。 */
        const val SEEK_ACCEL_AFTER_TAPS = 5

        /** 连续操作加速倍数。 */
        const val SEEK_ACCEL_FACTOR = 3

        /** 电视队列项接回当前集的最多次数。 */
        const val TV_QUEUED_RECOVERY_LIMIT = 2

        /** 电视倍速滑杆停止多久后才提交到播放器。 */
        const val TV_SPEED_COMMIT_DELAY_MS = 500L

        /** 电视播放 HUD 进度条片尾保护时间。 */
        const val HUD_TAIL_GUARD_MS = 500L

        /** 手机换源时，旧播放器延迟多久关闭，避免 Surface 提前失效。 */
        const val PHONE_PREVIOUS_CLOSE_DELAY_MS = 1_000L

        /** 手机替换预热源时，旧对象延迟多久关闭。 */
        const val PHONE_REPLACE_CLOSE_DELAY_MS = 800L

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

            /** 推荐页最多同时保留几台播放器：当前页 + 前后各一台。 */
            const val PHONE_PLAYER_POOL_SIZE = 3

            /** 低内存设备只保留当前页与主方向下一台，避免多解码器一起占图形内存。 */
            const val PHONE_LOW_RAM_PLAYER_POOL_SIZE = 2

            /** 离开推荐页后，空闲播放器延迟多久释放。 */
            const val PHONE_PLAYER_POOL_IDLE_RELEASE_MS = 20_000L

            /** 相邻页静音预渲染首帧的最长等待时间，超时后暂停等待用户滑入。 */
            const val PHONE_PRE_RENDER_TIMEOUT_MS = 1_200L

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

        /** 播放设置面板自动收起的最小秒数。 */
        const val PLAYER_PANEL_TIMEOUT_MIN_SECONDS = 2

        /** 播放设置面板自动收起的最大秒数。 */
        const val PLAYER_PANEL_TIMEOUT_MAX_SECONDS = 15

        /** 播放设置面板自动收起的出厂默认秒数。 */
        const val PLAYER_PANEL_TIMEOUT_DEFAULT_SECONDS = 6

        /** 本机收藏列表上限。 */
        const val FAVORITES_LIMIT = 500

        /** 稍后观看列表上限。 */
        const val LATER_LIMIT = 100

        /** 本机历史记录上限。 */
        const val HISTORY_LIMIT = 200

        /** 隐藏剧集记录上限。 */
        const val HIDDEN_LIMIT = 500

        /** 搜索历史上限。 */
        const val SEARCH_HISTORY_LIMIT = 20

        /** 单条搜索历史允许的最大字符数。 */
        const val SEARCH_QUERY_MAX_CHARS = 80

        /** 历史删除墓碑上限。 */
        const val HISTORY_DELETED_LIMIT = 500

        /** 已看完集合上限。 */
        const val WATCHED_LIMIT = 500

        /** 写队列默认防抖时间。 */
        const val WRITE_QUEUE_DELAY_MS = 250L

        /** 写队列失败后的重试次数。 */
        const val WRITE_QUEUE_RETRIES = 2

        /** 写队列失败重试延迟。 */
        const val WRITE_QUEUE_RETRY_DELAY_MS = 1_000L
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
     * 页面结构：有哪些入口、栏目和分页，以及它们默认的展示顺序。
     *
     * 这里只放稳定 key 与顺序；中文文案仍由各端资源映射，避免配置层承担本地化。
     */
    object Navigation {
        /** 顶部导航可隐藏入口顺序。 */
        val NAV_KEYS = listOf("recommend", "search", "category", "rank", "profile")

        /** 手机底部主导航顺序，也是「打开应用进入」的可选页面。 */
        val PHONE_TAB_KEYS = listOf("recommend", "category", "rank", "mine")

        /** 手机默认打开首页。 */
        const val PHONE_LAUNCH_TAB_DEFAULT = "recommend"

        /** 启动页把已落盘的手机启动页面传给主界面。 */
        const val PHONE_LAUNCH_TAB_EXTRA = "phone_launch_tab"

        /** 首页栏目默认顺序：本机三栏 + 八个榜单。 */
        val HOME_COLUMN_KEYS = listOf(
            "history",
            "favorites",
            "later",
            "rank-recommend",
            "rank-hot",
            "rank-subscribe",
            "rank-new-release",
            "rank-hot-search",
            "rank-chase",
            "rank-must-watch",
            "rank-followed",
        )

        /** 榜单页顶部芯片顺序。 */
        val RANK_NAV_ORDER = listOf(
            "recommend",
            "hot",
            "subscribe",
            "new-release",
            "hot-search",
            "chase",
            "must-watch",
            "followed",
        )

        /** 「我的」账号侧栏目顺序。 */
        val ACCOUNT_TAB_KEYS = listOf("history", "favorites", "likes", "reserves")

        /** 未登录时账号登录方式顺序。 */
        val ACCOUNT_LOGIN_MODE_KEYS = listOf("qr", "phone")

        /** 设置页四大分页顺序。 */
        val SETTINGS_PAGE_LABELS = listOf("播放交互", "外观导航", "内容推荐", "系统高级")

        /** 播放画面左上角信息项顺序。 */
        val PLAY_INFO_KEYS = listOf("title", "episode", "speed", "clock")
    }

    /**
     * 共享设置列表的项与顺序。
     *
     * 删除某一项即从对应设置页隐藏；调整顺序即改变页面渲染顺序。Section 也作为一项参与排序。
     */
    object Settings {
        /** 播放页设置项顺序。 */
        val PLAYBACK_ORDER = listOf(
            "section:播放",
            "playback.quality",
            "playback.speed",
            "playback.frame",
            "playback.decoder",
            "playback.scale",
            "section:弹幕",
            "danmaku.mode",
            "danmaku.speed",
            "danmaku.merge",
            "danmaku.effects",
            "danmaku.merge.min",
            "danmaku.scale",
            "danmaku.alpha",
        )

        /** 外观与布局设置项顺序。 */
        val APPEARANCE_ORDER = listOf(
            "section:外观与布局",
            "appearance.launchTab",
            "theme-grid",
            "layout.columns",
            "recommend.swipe",
        )

        /** 内容推荐设置项顺序。 */
        val RECOMMEND_ORDER = listOf(
            "section:内容推荐",
            "recommend.gender",
            "search.source",
        )

        /** 系统高级设置项顺序。 */
        val SYSTEM_ORDER = listOf(
            "section:系统高级",
            "system.deviceForm",
            "system.lanSync",
            "system.export",
            "system.import",
            "system.restoreDefaults",
            "system.reset",
            "system.debug",
            "system.reduceAnim",
        )
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

        /** 二维码长时间未确认时，刷新轮询间隔相对正常轮询的倍数。 */
        const val QR_REFRESH_POLL_MULTIPLIER = 4

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

        /**
         * 手机 / 平板的几何参数。
         */
        object Phone {
            /** 海报网格左右边距。 */
            const val PAGE_INSET_DP = 0f

            /** 底部导航左右留白。 */
            const val TAB_INSET_DP = 8f

            /** 卡片间距。 */
            const val CARD_GAP_DP = 8f

            /** 海报卡之间横纵间距。 */
            const val POSTER_GAP_DP = 0f

            /** 频道 chip 行高。 */
            const val CHIP_ROW_DP = 45f

            /** 剧场卡片宽度。 */
            const val POSTER_WIDTH_DP = 293f

            /** 播放页右侧操作项高度。 */
            const val ACTION_ITEM_DP = 65f

            /** 柔光带宽度。 */
            const val SOFT_BAND_DP = 6f

            /** 大面板柔光带宽度。 */
            const val SOFT_BAND_LARGE_DP = 10f

            /** 卡片圆角。 */
            const val RADIUS_CARD_DP = 8f

            /** 胶囊圆角。 */
            const val RADIUS_PILL_DP = 26f

            /** 弹窗 / 抽屉圆角。 */
            const val RADIUS_SHEET_DP = 24f

            /** 进度条轨道高度。 */
            const val TRACK_HEIGHT_DP = 3f

            /** 竖屏底部导航高度。 */
            const val TAB_BAR_DP = 30f

            /** 横屏沉浸时底部导航高度。 */
            const val TAB_BAR_LANDSCAPE_DP = 22f

            /** 常规卡片宽度。 */
            const val CARD_WIDTH_DP = 116

            /** 紧凑卡片宽度。 */
            const val CARD_COMPACT_WIDTH_DP = 84

            /** 海报卡内容内缩。 */
            const val POSTER_INSET_DP = 0

            /** 海报卡柔光带宽度。 */
            const val POSTER_SOFT_BAND_DP = 2

            /** 海报角标文字缩放。 */
            const val POSTER_BADGE_TEXT_SCALE = .78f

            /** 海报标题文字缩放。 */
            const val POSTER_TITLE_TEXT_SCALE = .85f

            /** 海报卡圆角。 */
            const val POSTER_RADIUS_DP = 10

            /** 海报卡底部圆角。 */
            const val POSTER_BOTTOM_RADIUS_DP = 6f

            /** 竖屏视频操作栏高度。 */
            const val VIDEO_ACTION_BAR_DP = 26

            /** 竖屏视频信息左右边距。 */
            const val VIDEO_INFO_EDGE_DP = 6

            /** 竖屏视频操作图标边距。 */
            const val VIDEO_ACTION_GLYPH_EDGE_DP = 2
        }

        /**
         * 电视 1280×720 设计画布上的几何参数。
         */
        object Tv {
            /** 海报圆角。 */
            const val RADIUS_POSTER = 16

            /** 卡片圆角。 */
            const val RADIUS_CARD = 20

            /** 胶囊圆角。 */
            const val RADIUS_PILL = 999

            /** 模态框圆角。 */
            const val RADIUS_DIALOG = 32

            /** 弹窗面板圆角。 */
            const val RADIUS_DIALOG_PANEL = 30

            /** 弹窗宽度。 */
            const val DIALOG_WIDTH = 472

            /** 弹窗缩略图宽度。 */
            const val DIALOG_THUMB_WIDTH = 96

            /** 弹窗缩略图高度。 */
            const val DIALOG_THUMB_HEIGHT = 60

            /** 弹窗缩略图圆角。 */
            const val DIALOG_THUMB_RADIUS = 30

            /** 弹窗选项行高度。 */
            const val DIALOG_ITEM_HEIGHT = 62

            /** 弹窗选项行左右内边距。 */
            const val DIALOG_ITEM_PAD = 30

            /** 弹窗选项图标边长。 */
            const val DIALOG_ITEM_ICON = 22

            /** 弹窗图标与文字间距。 */
            const val DIALOG_ITEM_ICON_GAP = 15

            /** 弹窗选项间距。 */
            const val DIALOG_ITEM_GAP = 11

            /** 登录卡宽度。 */
            const val LOGIN_CARD_WIDTH = 680

            /** 登录卡圆角。 */
            const val RADIUS_LOGIN_CARD = 32

            /** 登录卡上内边距。 */
            const val LOGIN_PAD_TOP = 28

            /** 登录卡左右内边距。 */
            const val LOGIN_PAD_SIDE = 44

            /** 登录卡下内边距。 */
            const val LOGIN_PAD_BOTTOM = 26

            /** 登录输入框高度。 */
            const val LOGIN_FIELD_HEIGHT = 56

            /** 登录输入框左右内边距。 */
            const val LOGIN_INPUT_PAD = 30

            /** 区号按钮宽度。 */
            const val LOGIN_REGION_WIDTH = 116

            /** 获取验证码按钮宽度。 */
            const val LOGIN_SEND_WIDTH = 158

            /** 登录主按钮高度。 */
            const val LOGIN_BUTTON_HEIGHT = 60

            /** 登录字段间距。 */
            const val LOGIN_FIELD_GAP = 16

            /** 弹窗顶部内边距。 */
            const val DIALOG_PAD_TOP = 26

            /** 弹窗左右内边距。 */
            const val DIALOG_PAD_SIDE = 22

            /** 弹窗底部内边距。 */
            const val DIALOG_PAD_BOTTOM = 24

            /** 焦点描边宽度。 */
            const val RING = 2

            /** 焦点描边圆角。 */
            const val RING_RADIUS = 20

            /** 柔光带宽度。 */
            const val SOFT_BAND = 6

            /** 大面板柔光带宽度。 */
            const val SOFT_BAND_LARGE = 10

            /** 焦点放大比例。 */
            const val FOCUS_SCALE = 1.05f

            /** 焦点动画时长。 */
            const val DURATION_MS = 200L

            /** 横向焦点余量。 */
            const val FOCUS_BLEED = 7

            /** 纵向焦点余量。 */
            const val FOCUS_BLEED_VERTICAL = 12

            /** 紧凑按钮高度。 */
            const val BUTTON_COMPACT = 40

            /** 弹窗按钮高度。 */
            const val BUTTON_DIALOG = 36

            /** 常规按钮高度。 */
            const val BUTTON_NORMAL = 48

            /** 主操作按钮高度。 */
            const val BUTTON_HERO = 56

            /** 紧凑按钮内边距。 */
            const val BUTTON_PAD_COMPACT = 18

            /** 常规按钮内边距。 */
            const val BUTTON_PAD_NORMAL = 28

            /** 主操作按钮内边距。 */
            const val BUTTON_PAD_HERO = 36

            /** 弹窗按钮内边距。 */
            const val BUTTON_PAD_DIALOG = 14

            /** 收紧按钮内边距。 */
            const val BUTTON_PAD_TIGHT = 12

            /** 收紧按钮最小宽度。 */
            const val BUTTON_MIN_WIDTH_TIGHT = 44

            /** 常规按钮最小宽度。 */
            const val BUTTON_MIN_WIDTH = 92

            /** 顶部工具栏高度。 */
            const val TOOLBAR_HEIGHT = 88

            /** 同一行控件间距。 */
            const val GAP_INLINE = 12

            /** 密集排布间距。 */
            const val GAP_TIGHT = 8

            /** 区块间距。 */
            const val GAP_BLOCK = 16

            /** 大分组间距。 */
            const val GAP_GROUP = 24

            /** 电视选集网格列数。 */
            const val EPISODE_COLUMNS = 10

            /** 电视选集单元格间距。 */
            const val EPISODE_CELL_GAP = 4

            /** 电视选集单元格最小尺寸。 */
            const val EPISODE_CELL_MIN = 32

            /** 电视选集左右页边距。 */
            const val EPISODE_PAGE_SIDE_PAD = 78
        }
    }

    /**
     * 调试日志、性能采样和播放诊断上报上限。
     */
    object Diagnostics {
        /** 进程内播放诊断记录上限。 */
        const val PLAYBACK_MAX_REPORTS = 12

        /** 解码器错误上报上限。 */
        const val PLAYBACK_MAX_CODEC_ERROR_REPORTS = 3

        /** 播放失败后延迟上报的时间。 */
        const val PLAYBACK_REPORT_DELAY_MS = 1_500L

        /** 调试问题列表上限。 */
        const val MAX_ISSUES = 30

        /** 调试日志滚动条数上限。 */
        const val DEBUG_LOG_LIMIT = 40

        /** 性能采样间隔。 */
        const val PERF_INTERVAL_MS = 2_000L

        /** GPU 名称读取重试次数。 */
        const val PERF_GPU_RETRIES = 3

        /** 每多少次采样记录一条慢样本。 */
        const val PERF_SLOW_EVERY = 3

        /** 封面耗时诊断样本上限。 */
        const val ARTWORK_TIMING_SAMPLE_LIMIT = 8_000
    }
}
