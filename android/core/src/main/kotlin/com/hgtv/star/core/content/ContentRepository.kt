// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import com.hgtv.star.core.session.CookieRecord
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
/**
 * 内容接口的默认传输层。
 *
 * **钉死 HTTP/1.1**：OkHttp 的 h2 读线程是它自己起的裸线程，一旦平台 TLS
 * （Conscrypt）在解包时抛异常，没有任何一层能接住，整个进程直接退出。
 * 2026-10-18 的线上崩溃就是这个形状（TCL / Android 11，连续播放 34 分钟后）：
 *
 * ```
 * IndexOutOfBoundsException: off=0, len=1482 out of bounds (size=0)
 *   at ConscryptEngineSocket$SSLInputStream.processDataFromSocket
 *   at Http2Reader.nextFrame / Http2Connection$ReaderRunnable.execute
 * ```
 *
 * 这些接口都是小 JSON 请求，h1.1 与 h2 的差别只在连接数上；
 * 拿「进程不被平台 bug 打死」换几个连接，值得。
 * 图片链路（`ArtworkLoader`）不在这里，它仍然用 h2——那里并发多、复用收益真实。
 */
private fun defaultHttpClient(): OkHttpClient = defaultContentHttpClient()

/** 分类 / 榜单页首屏统一取 30 条；后续页再使用大页续接。 */
const val CATALOG_FIRST_PAGE_SIZE=30
class ContentRepository(
    /**
     * 传输层：显式传进来就用它（预取 / 预热各带自己的 client），不传则**用到时才建**。
     *
     * 为什么不是「构造时就建」：`TvMainActivity` 在字段初始化里建了四个 `ContentRepository`
     * （见 `TvMainActivity.kt` 的 `repository` / `prefetchHttp` 那几处），而那些初始化发生在
     * **主线程**、`onCreate` 之前。建 client 会顺带解析系统根证书表（首次 2~3 秒，
     * 见 `HgtvTls.ACCEPTED_ISSUERS`），于是冷启动还没开始建视图就先卡在那里。
     *
     * 改成懒建之后，第一个真正发请求的地方是 io 线程上的首页 / 推荐取数——那时
     * `HgtvApplication` 的预热线程通常已经把证书表解完了，这里只花一次数组拷贝。
     */
    httpClient: OkHttpClient? = null,
    internal val clock: () -> Long = System::currentTimeMillis,
    /** 播放接口两次请求之间的最小间隔（见 [waitModelSlot]）；测试里传 0，免得真等 3 秒。 */
    internal val modelSpacing: Long = MODEL_SPACING_MS,
    /**
     * 播放模型的合并窗口（见 [videoModel]）；测试里传 0 关掉。
     *
     * 缓存在 companion 里（三份实例共用），测试之间会互相污染：同一个 `id@deviceLevel`
     * 在上一个用例里被缓存过，下一个用例就拿到别人的模型，断言全部错位。
     */
    internal val modelCacheTtl: Long = MODEL_CACHE_MS
) : RecommendDataSource {
    private val httpRef=lazy(LazyThreadSafetyMode.SYNCHRONIZED) { httpClient ?: defaultHttpClient() }
    val http: OkHttpClient get() = httpRef.value
    internal val recommendStatConfigLock=Any()
    internal var recommendStatConfigLoading=false
    internal var recommendStatConfigNextAt=0L
    /**
     * 官方 `bookmall/tab` 的会话标识。
     *
     * `session_uuid` 是客户端游标，`normal_session_id` / `cold_start_session_id`
     * 会原样进入推荐卡片的 `recommend_info`，再被曝光上报带回服务端。
     * 同一进程内保持稳定，避免每次翻页都被服务端当成新会话。
     */
    internal val officialNormalSessionId="${java.util.UUID.randomUUID()}#1"
    internal val officialColdStartSessionId=java.util.UUID.randomUUID().toString()
    internal val OFFICIAL_RECOMMEND_STREAM_COUNT=
        "[{\"scene\":\"1\",\"StreamCount\":1,\"StreamType\":\"1\"}]"
    internal val OFFICIAL_RECOMMEND_EXTRA=
        "eyJyZWNlbnRfZGlzbGlrZV9naWQiOltdLCJzZXNzaW9uX2FwcF9zdGF5X3RpbWUiOjB9"
    /**
     * TNC 初始化专用 client。
     *
     * 五个入口必须依次获得一次机会；如果沿用普通请求的 45 秒超时，一个坏域名就会把
     * 后续入口全部堵住。这里共用 [http] 的连接池和 dispatcher，只收紧单次请求预算。
     */
    internal val tncHttp by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        http.newBuilder()
            .connectTimeout(4,TimeUnit.SECONDS)
            .readTimeout(6,TimeUnit.SECONDS)
            .callTimeout(8,TimeUnit.SECONDS)
            .build()
    }
    /**
     * 传输层到底建过没有。
     *
     * 销毁路径要用：这一屏如果一次请求都没发过，client 从未建出来，也就不该为了「关掉它」
     * 现建一个（建一次要解析系统根证书表，见 [httpClient] 上的说明）。
     */
    fun hasHttpClient(): Boolean = httpRef.isInitialized()
    /**
     * 提前把「到内容接口的 TCP + TLS」建好，留在本实例的连接池里。
     *
     * ## 为什么值得
     *
     * 冷启动到推荐首屏那段实测（2026-10-27，AOT 之后）里，
     * `Recommend stage filters +35ms` → `Recommend stage feed +3423ms`：
     * **3.4 秒全在一次请求里**。DNS 已经不是瓶颈（见 [HgtvDns]），剩下的是
     * 这条 HTTPS 的首次握手——DNS + TCP 三次握手 + TLS 1.2 两趟 + 我们自己的
     * PKIX 验链（系统根库 150 张，首验在这台 2G 盒子上要几百毫秒）。
     * 而只要连接已经在池子里，同一份请求就只剩一个 RTT。
     *
     * ## 怎么预热
     *
     * 对两个域名各发一个 `HEAD`（不带任何业务参数、不需要登录态）：
     * 服务器回 200/403/404 都无所谓——握手已经完成，连接按 keep-alive 留在池里，
     * 5 分钟内随便谁用。返回码与响应体全部丢弃，失败静默。
     *
     * 用 `HEAD` 而不是真业务接口：不消耗业务接口额度，
     * 也不会在服务端留下「取过一次推荐」的记录。
     *
     * **必须在后台线程调**（最长会等一个连接超时）。
     */
    fun preconnect() = preconnectImpl()
    /**
     * 提前把「签名」这条路走一遍。
     *
     * ## 为什么值得
     *
     * 冷启动实测（2026-10-27，AOT 之后）：推荐首屏那 3.1 秒里，
     * `Stage filters +36ms` → `Stage feed +2459ms`，而这一段的第一笔开销是**签名**——
     * 日志里 `sign ... body=1054B took=564ms`。同一台机器上第二次签名只要 30~60ms，
     * 差别全在「第一次」：SM3（BouncyCastle）/ MD5 / 那条 128 轮混淆循环的类加载与
     * 首轮 JIT，加上 1500 多个请求头的字符串拼接。564ms 里没有一秒是网络。
     *
     * 这里就在启动时用一份**和真实推荐请求同样大小、同样字段**的假 body 签一次，
     * 把这些类先拉起来。真正要发请求时那 500ms 已经花掉了（在后台，和建界面并行）。
     *
     * 不消耗任何接口额度：签名是纯本地运算，一个字节都不上网络。
     * **必须在后台线程调**。
     */
    fun warmSignature() = warmSignatureImpl()
    internal val signer = Signer()
    /** 账号 / 搜索 / 提示词接口的域名；由 TNC 动态下发，失败时回退内置表。 */
    internal fun accountHost(): String = TncConfigStore.preferredHost("sinfonlinea")
    /** 播放侧（详情 / 分集 / 播放地址）的域名；由 TNC 动态下发。 */
    internal fun videoHost(): String = TncConfigStore.preferredHost("sinfonlineb")
    /**
     * [accountHost] 的预热推迟多久（毫秒）。
     *
     * 账号 / 收藏 / 搜索在冷启动里**一个都不在首帧路径上**：未登录时一次都不发，
     * 登录了也只是后台刷一次。而一次 TLS 握手要 450ms 的 CPU，正好撞在建界面 + 建解码器那一段。
     * 推后 2 秒，等那阵忙过去再握手，两者都不耽误。
     */
    internal val ACCOUNT_PRECONNECT_DELAY_MS=2_000L
    /** 账号接口空响应体的重试次数。 */
    private val ACCOUNT_RETRIES = 4
    /** 写接口里「短剧」的 `book_type`（App 的 `ReadingBookType.Watch`）。 */
    internal val BOOK_TYPE_WATCH = 2
    /** 写接口里「加入追剧」的操作码（App 的 `VideoShelfOperateType.AddShelf`）。 */
    internal val VIDEO_SHELF_ADD_SHELF = 0
    /** 写接口里「取消追剧」的操作码（App 的 `VideoShelfOperateType.DelShelf`）。 */
    internal val VIDEO_SHELF_DEL_SHELF = 1
    /**
     * 写接口的诊断日志出口。
     *
     * `core` 是纯 Kotlin 模块（没有 Android SDK），所以不能直接 `Log.i`；
     * 界面层启动时把它接到 `android.util.Log`（见 `TvMainActivity`），不接就是空操作。
     */
    var writeLog: (String) -> Unit = {}
    /**
     * 写接口被风控（`101144 HIT_SHARK_RULE`）时的重试次数与退避间隔（毫秒）。
     *
     * 风控窗口通常是**秒级到十几秒**：实测同一条请求隔 1s 重发还是被挡、隔十几秒就能过，
     * 因此退避必须拉开（早期写的 400/1000/1600ms 全落在同一个窗口里，等于没重试）。
     */
    internal val ACCOUNT_WRITE_ATTEMPTS = 4
    internal val ACCOUNT_WRITE_BACKOFF = longArrayOf(1_200L,4_000L,12_000L)
    /**
     * 一次 `multi-detail` 最多带多少个 id。
     *
     * 实测 50 个 id 一次能全部回（0.6s），因此按历史条数上限取 50：
     * 取小了（曾经是 20）会让第 21 条起只剩 `series_id`、没有片名与封面，
     * 界面上就是一排**空框**——这正是「历史里很多框不显示内容」的原因。
     *
     * 补历史元数据时按这个数**分片**（见 [accountHistory]）：139 条一次要也能回，
     * 但单条响应会到 2 MB 量级，分片之后某一片失败只影响那 50 条，不会整栏报错。
     */
    internal val MULTI_DETAIL_LIMIT = 50
    /**
     * 观看历史一次要多少条。
     *
     * **这条接口的 `offset` 是假的**：`offset=50` / `100` / `150` 回来的永远是同一批前 50 条
     *（2026-10-21 实测，650 条请求里去重后只有 50 个 `series_id`），因此不能靠翻页补齐，
     * 只能一次把 `limit` 给大。实测 `limit=200` / `500` / `1000` 都回账号里的全部记录
     *（同一账号 139 条），超过真实条数时服务端就照实际数量返回，不会截断。
     *
     * 早先这里复用了 [MULTI_DETAIL_LIMIT]（50），于是**手机上有多少条、电视上最多只有 50 条**：
     * 用户看到的正是「同步来的不完全」。
     */
    /** 短剧历史实际会分布在 book_type 2/3/4；抓包里删除也同时出现 2 和 4。 */
    internal val HISTORY_BOOK_TYPES = listOf(2,3,4)

    /** 一次删除请求里带多少条（清空历史时按这个分批）。 */
    internal val HISTORY_DELETE_BATCH = 50
    /**
     * 上一次成功的播放模型路径（进程级）。
     *
     * 实测候选路径里通常只有一条能用，而且**同一台设备长期是那一条**
     *（2026-10-27 实测：只剩 `multi_video_model/v1` 回 `code 0`）。记住它之后，
     * 正常起播只发一次请求；记错了（路径失效 / 平台改版）也只是多花一次探测，
     * 随后会被新的成功路径覆盖，失败时立刻清空（见 [videoModel]）。
     */
    /**
     * 合并窗口内取到的播放模型：`<集 id>@<device_level>` → (取到时刻, 模型)。
     *
     * **按实例存**（不是 companion）：主实例与预取实例各有一份，重复请求由进程级
     * [modelGate] 串行化 + 这里各自的短缓存兜住。放成全局的会让测试之间互相污染
     *（上一个用例缓存过 `123@4`，下一个用例就拿到别人的模型，断言全部错位）。
     */
    /**
     * 播放地址的合并缓存与锁表（见 [stream]）。
     *
     * **按实例存**：缓存的是「这一份仓库看到的播放地址」，全局共享会让测试之间、
     * 以及主实例与预取实例之间的结果互相串味（前者表现为断言错位，后者表现为
     * 预取实例拿到主实例的旧地址却以为是自己的）。
     */
    internal val streamCache=java.util.concurrent.ConcurrentHashMap<String,ContentRepositoryStreamCacheEntry>()
    internal val streamLocks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    internal val modelCache=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,JSONObject>>()
    @Volatile internal var preferredModelPath: String=""
    /** 清空历史最多试几轮（删完回读校验，还有余量就再来一轮）。 */
    internal val HISTORY_CLEAR_ATTEMPTS = 3
    /** 作者缓存：详情页反复进出时不重复请求（一条几十毫秒，但没必要）。 */
    private val authorCache=java.util.LinkedHashMap<String,String>()
    internal val authorCacheStore get()=authorCache
    /**
     * 作者缓存上限。
     *
     * 只缓存**取到值**的条目：`multi-detail` 对少数剧返回空 `author`，把空串也缓存下来
     * 会让「之后接口补上了」永远读不到；空结果不缓存，下次进详情再问一次即可。
     */
    private val AUTHOR_CACHE_LIMIT = 300
    internal val authorCacheLimit get()=AUTHOR_CACHE_LIMIT
    /**
     * 账号那七个请求：彼此独立，串行发要两三秒。
     *
     * 走共享的 `fanout` 车道（见 [WorkLanes]），不再自己养一个 6 线程池——
     * 这条池子与「首页四栏」「热搜种子」「官网搜索」是同一类形态（一个调用方提交 N 个
     * 任务再 join），共用一条道才能把「最大扇出」这件事统一算清楚。
     */
    internal val accountPool get()=WorkLanes.fanout.executor
    /**
     * 热搜词的种子词（见 [hotSearches]）：都是题材向的泛词，联想接口对它们返回的候选里
     * 混着一批真正的查询词（`30集短剧` / `都市逆袭` / `甜宠` 这类）。
     * 只取前三个：每个种子一次请求，串行约 200ms，再多就只是把同一批词换几个说法。
     */
    internal val SUGGEST_SEEDS = listOf("短剧","都市","逆袭","甜宠","战神")
    /**
     * 热搜词的词类型优先级（见 [hotSearches]）。
     *
     * 题材 / 分类这类（`short_play_category` / `category`）读起来最像「大家在搜的题材」，
     * 排最前；`common_query` 是平台自己的搜索建议（`短剧最热榜前十名` 这种）排其次；
     * `title` / `album` 更像是书名 / 专辑名，放最后。
     *
     * 这些字符串就是提示词接口的 `word_type_str`（见 [suggestions]），与是否带 `video_data` 无关。
     */
    internal val HOT_WORD_ORDER = listOf("short_play_category","category","common_query","title","album")
    /**
     * 热搜词的并发：种子词彼此独立，串行五个要 1.8s，并发之后是单次请求的时间。
     *
     * 走共享的 `fanout` 车道，理由同 [accountPool]。
     */
    internal val SUGGEST_POOL get()=WorkLanes.fanout.executor
    /** 热搜词的兜底表：提示接口整体不可用时用（搜索页不能空着）。 */
    internal val FALLBACK_HOT = listOf(
        "短剧最热榜前十名","AI短剧","都市逆袭","甜宠","战神归来","穿越剧大全",
        "萌宝助攻","玄幻修仙系列","悬疑","古装","逆袭人生","豪门"
    )
    /**
     * 官网的地址：用**裸域名**，不要加 `www`。
     *
     * 2026-10 起 `www.hongguoduanju.com` 对一切路径都回 301 到裸域名，
     * 而 OkHttp 跟着 301 走之后拿到的那一页已经**不再内联 SSR 数据**（没有 `_ROUTER_DATA`），
     * 旧版解析搜索页 HTML 的路子因此整条失效——用户看到的就是「官网搜索源没结果」。
     * 现在改走官网自己的 JSON 接口（`/api/search/result`、`/api/search/suggestion`），
     * 裸域名直连，既没有 301，也不依赖页面结构。
     */
    internal val SITE_HOST = "https://hongguoduanju.com"
    /**
     * 官网搜索**服务端**一页的条数：固定 10 条。
     *
     * `count` / `page_size` / `pageSize` / `size` / `limit` / `num` / `rows` / `per_page` /
     * `pagesize` / `offset` 十种写法实测全部被忽略（2026-10-24 复测：返回的都是同一批 10 条），
     * 因此「一页 100 条」只能在客户端拼——见 [WEB_SEARCH_BATCH]。
     */
    internal val WEB_SEARCH_PAGE_SIZE = 10
    /**
     * 官网搜索一个**逻辑页**拼多少个物理页（10 × 10 = 100 条，与 App 搜索的一页同量）。
     *
     * 服务端一页只有 10 条，串行拉 10 页要 6~10 秒，因此按 [WEB_SEARCH_CONCURRENCY] 并发拉。
     */
    internal val WEB_SEARCH_BATCH = 10
    /** 官网自动降级时先给首屏的页数，后续按实际取到的物理页接着翻。 */
    internal val WEB_FALLBACK_BATCH = 3
    /**
     * 官网搜索拼页时的并发数。
     *
     * 官网这个接口对突发并发很敏感：实测同时打 5 个会有一部分回
     * `502 upstream_unavailable`（同一页单独重发又正常），压到 3 个之后基本不再出现。
     * 单页另有 [WEB_SEARCH_RETRY] 次重试兜底，所以慢一点也稳。
     */
    private val WEB_SEARCH_CONCURRENCY = 3
    /**
     * 官网搜索单页的重试次数：`upstream_unavailable` 是瞬时抖动，重发一次通常就好了。
     *
     * 实测同一页隔 0.2s 重发多数能过，因此给两次重试（共三次），并按下标线性退避
     * （见 [WEB_SEARCH_RETRY_DELAY]）。三次都失败才把这一页算失败。
     */
    internal val WEB_SEARCH_RETRY = 2
    /** 单页重试的退避基数（毫秒）：第 1 次重试等 200ms、第 2 次等 400ms。 */
    internal val WEB_SEARCH_RETRY_DELAY = 200L
    /**
     * 官网搜索关键词的长度上限：服务端只收 30 个字符
     *（更长的直接回 `400 invalid_query`，一个结果都不给）。
     */
    internal val WEB_SEARCH_QUERY_MAX = 30
    /**
     * 官网搜索的已发过名单：官网一次只给 10 条、按 `page` 翻页，
     * 翻到同一批（服务端分页重复）时用它把重复条目摘掉。
     *
     * 与 [searchCursors] 一样按「关键词」分组，只保留最近几组。
     */
    internal val webSearchSeen=linkedMapOf<String,MutableSet<String>>()
    /** App 搜索降级到官网后，该关键词下一次应当从第几个物理页继续取。 */
    internal val webFallbackCursors=linkedMapOf<String,Int>()
    /**
     * 本次官网搜索的「部分页失败」摘要，由 [webSearch] 写、[search] 读。
     *
     * 为什么用 ThreadLocal 而不是返回值：`search` 只认 [CatalogPage]（与 App 搜索同一套
     * 签名），失败摘要不属于列表数据。线程内一进一出，不需要额外加锁。
     */
    internal val webSearchNote=ThreadLocal<String>()
    internal data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    /**
     * 游标表的键：关键词 + 第几页 + **一页多少条**。
     *
     * 末位那个 `count` 不能省：用户手动搜一个词是 20 条一页，作者作品是 50 条一页
     *（见 [AUTHOR_WORKS_PAGE_SIZE]），两套的 `offset` 完全不同。
     * 若共用同一个键，「作者名恰好也被用户搜过」时就会拿错游标，翻页直接错位。
     */
    internal data class SearchCursorKey(val scope: Int, val keyword: String, val page: Int, val count: Int)
    internal val searchCursors=linkedMapOf<SearchCursorKey,SearchCursor>()
    /**
     * 搜索接口还能不能用：空响应体说明这台设备没在服务端注册过（实测所有线路、多组设备参数都一样），
     * 重发多少次都一样。null = 还没试过；判死之后同一进程里不再空跑。
     */
    @Volatile internal var appSearchAlive: Boolean?=null
    /**
     * 搜索登录态（可选）：搜索接口对未注册设备返回空响应体，带上 App 抓到的 `x-tt-token`
     * 后它会按该账号返回数据。留空时按匿名设备请求（本工程会先匿名注册一台设备，见 [registerDevice]）。
     */
    @Volatile var sessionToken: String?=null
    /**
     * 账号凭据（个人信息页导入的整套登录态）。
     *
     * 与 [sessionToken] 的分工：`sessionToken` 是历史遗留的「只给搜索用的口令」，
     * 这里这份带设备档案，账号接口（收藏 / 历史 / 点赞 / 预约 / 资料）与 App 搜索都用它。
     * 两处都配了时以这份为准（见 [withSession]）。
     */
    @Volatile var credentials: AccountCredentials?=null
        set(value) {
            // 登录态一换，「App 搜索还能不能用」这个判断就得重新做：
            // 匿名时接口回空 body（被判死），配上凭据之后它是能出数据的。
            if(field?.cookie!=value?.cookie) appSearchAlive=null
            // 导入官方档案会把签名设备从本机合成身份切到真机身份，旧模型缓存必须作废。
            if(field?.device!=value?.device) modelCacheGen++
            field=value
            // 签名里的 x-medusa 要上报设备档案（机型 / 系统版本 / cdid …），
            // 与凭据一起换掉，免得拿着 A 设备的档案去发 B 设备的写请求。
            // 档案是三层合并的结果（见 [deviceProfile]）：内置 → 本机身份 → 凭据的真机档案。
            signer.updateDevice(primaryProfile())
        }
    /**
     * 本机设备身份（`device_id` / `iid`），由界面层注入（见 `Library.deviceIdentity`）。
     *
     * **这份身份必须是平台注册过的**（[registerDevice] 的下发结果）：内容接口按
     * 「服务端注册过的 `device_id` + `install_id` 这一对」放行，随便编一对数字
     * （哪怕形状完全正确）都拿不到数据——App 搜索回 `200 + 0 字节`、播放回
     * `101000 服务异常`。注册是匿名的，见 [DeviceRegister]。
     *
     * 内置档案里那个 `device_id`（`1905892595378490`）另外还被单独拉黑（大量第三方共用），
     * 所以**不要**回落到它：没注册过时本字段就是空表，调用方要先注册。
     */
    @Volatile var deviceIdentity: Map<String,String> = emptyMap()
        set(value) {
            val clean=value.filterValues { it.isNotBlank() }
            // 身份一换，上一轮「搜索接口不可用」的判定就作废：那一轮是按**没注册**判死的，
            // 注册成功之后必须重新试一次。少了这一行，新装的电视第一次搜索把接口判死，
            // 同一进程里再也不发请求，页面上就一直只剩「重试」。
            if(clean!=field) {
                appSearchAlive=null
                modelCacheGen++
            }
            field=clean
            signer.updateDevice(primaryProfile())
        }
    /**
     * 共用另一份仓库实例的**设备身份、注册 Cookie 与设备档案**
     *（[deviceIdentity] / `registrationCookies` / [registerProfile]）。
     *
     * 播放预取与推荐页预热会另开一个 [ContentRepository]（独立连接池与超时），它自己没注册过设备。
     * 只带 `deviceIdentity` 是不够的——2026-10-27 抓到现场：那一份请求的 query 是
     * `device_id=<本机注册值>&device_type=25053RT47C&device_brand=Redmi&os_version=16`
     * （内置档案 + 本机身份），**缺 `cdid` / `openudid` / `rom_version`**，
     * 与主实例那份 `device_type=PHN110&device_brand=OPPO&os_version=12&cdid=…` 完全对不上。
     * 平台按「身份与指纹不自洽」直接把请求挡成 `200 + 0 字节`：12 条取地址请求里
     * 11 条是这份错档案、只有 1 条是对的，于是界面永远起不来。
     *
     * 因此档案也必须一起带过去（[registerProfile] 就是 `Library.deviceProfile()` 那一份）。
     */
    fun inheritDevice(from: ContentRepository) {
        credentials=from.credentials
        sessionToken=from.sessionToken
        registerProfile=from.registerProfile
        deviceIdentity=from.deviceIdentity
        registrationCookies=from.registrationCookies
        // 档位也要跟着走：换了档位就等于换了一套清晰度表，预热到的地址与起播选中的那一档
        // 对不上，预热就白做了（见 [deviceLevel]）。
        deviceLevel=from.deviceLevel
    }
    /**
     * 注册用的设备档案（机型 / 系统 / `cdid` / `openudid` …），与 [deviceIdentity] 分开存：
     * 注册要在**还没有** `device_id` 的时候发出去，注册完拿到的那对身份再交给 [deviceIdentity]。
     * 由界面层从本机设置注入（见 `Library.deviceProfile`）。
     */
    @Volatile var registerProfile: Map<String,String> = emptyMap()
        set(value) { field=value.filterValues { it.isNotBlank() }; signer.updateDevice(primaryProfile()) }
    /**
     * 匿名注册一台设备，拿到平台认可的 `device_id` / `install_id`。
     *
     * 成功后**立刻**写入 [deviceIdentity]（后续请求马上用得上），并把下发的 Cookie 并进
     * 签名档案。失败（网络 / 服务端拒绝）返回 null，调用方按「没注册」处理即可——
     * 播放与搜索都会各自走回退路径，不会因为注册失败而整页报错。
     */
    /**
     * 上一次 [registerDevice] 失败的原因；成功时清空。
     *
     * 为什么要有它：`registerDevice` 的契约是「失败返回 null」（调用方按没注册处理，
     * 播放与搜索各走回退路径），因此异常在函数内部就被 `getOrNull()` 吃掉了。
     * 但注册失败是「搜索空响应 / 播放取不到地址」的共同根因，上层必须能拿到原因去上报——
     * 没有这个字段，调用点只能看到一个 null，线上完全看不出为什么。
     */
    @Volatile var lastRegisterError: Throwable? = null
        internal set
    /**
     * 服务端把**本机这份设备身份**当成「未注册」的次数。
     *
     * 什么情况下会这样：注册下发的 `device_id` / `iid` 是真的，但签名里的 `cdid` / `openudid`
     * 与注册时那份档案不再一致（升级后重建了档案、换了安装随机数），平台按 `cdid` 认设备，
     * 两者对不上就一律按陌生设备处理——搜索回 `200 + 0 字节`、播放回 `101000 服务异常`。
     * 现象与「这台设备从没注册过」完全一样，但 `deviceRegistered()` 是 true，
     * 于是**不会**再注册，用户只能自己去「设置 → 清空缓存」。
     *
     * 界面层盯着这个计数：它一变就说明这份身份该丢了，重新注册一台再重试一次请求
     * （见 `TvMainActivity.healDeviceIdentity`）。身份为空时不计——那是真的还没注册。
     */
    /**
     * 全局计数（所有实例共享）：预取用的是另一个 [ContentRepository]，但它与主实例认的是
     * **同一台设备**，服务端拒绝也是同一件事——按实例各记一份，界面层就只看得到主实例那一次。
     */
    val deviceRejected: Int get()=deviceRejectedCountValue
    /**
     * 丢掉当前这份设备身份：注册结果与下发的 Cookie 一起清。
     *
     * 下一次 [registerDevice] 会拿到一套新的 `device_id` / `iid`，与当前设备档案自洽。
     */
    fun clearDeviceIdentity() = clearDeviceIdentityImpl()
    /** 记一次「服务端不认这份身份」。身份为空时不计：那是真的还没注册，不是被拒。 */
    internal fun noteDeviceRejected() = noteDeviceRejectedImpl()

    /**
     * @param profile 这一次注册用的设备档案；默认 [registerProfile]。
     * @param withIdentity 注册请求里要不要带上本机已有的 `device_id` / `iid`。
     *
     * 「换新身份」时界面层会把**候选档案**（还没被平台接受的那一份）传进来，并把这一项设为
     * `false`：平台按 `device_id` 认设备，请求里带着旧身份就只会**原样返回同一对**数字，
     * 新档案配旧身份，还是对不上号，换新等于没换。不带身份才会真的新建一台设备。
     *
     * **但只去掉 `device_id` 是不够的**（2026-11 实测）：注册主机 `log.snssdk.com` 在
     * `SessionInterceptor` 的会话 host 名单里，它会给这条请求补上
     * `Cookie: install_id=…; sessionid=…` 与 `X-Tt-Token`。服务端按这串 Cookie 认设备，
     * 于是 cdid / openudid / clientudid 全换成新的也照样回 `new_user:0` 与原 `device_id`。
     * 因此 `withIdentity = false` 时额外打一个匿名标记头，让拦截器整条跳过身份补齐。
     */
    fun registerDevice(profile: Map<String,String>? = null,withIdentity: Boolean = true): DeviceRegister.Registered? =
        registerDeviceImpl(profile,withIdentity)

    /**
     * 匿名注册一台备用设备，不改动当前主设备身份与 Cookie。
     *
     * 仅供 [playbackBackupDevices] 对应的持久备用池初始化使用。
     */
    fun registerAnonymousDevice(profile: Map<String,String>): DeviceRegister.Registered? =
        registerAnonymousDeviceImpl(profile)
    /** 注册下发的 Cookie（`install_id` / `ttreq` / `store-region`），随注册请求一起带上。 */
    @Volatile internal var registrationCookies: Map<String,String> = emptyMap()
        set(value) { field=LinkedHashMap(value) }
    /**
     * 当前注册下发的 Cookie 快照（给界面层落盘用）。
     *
     * 为什么要出去：这份 Cookie 原本只活在内存里，**进程一重启就没了**，于是每个新进程的
     * 第一轮请求都是「没有 `install_id` / `ttreq` 的裸设备」，平台按陌生设备处理（风控更严）。
     * 界面层拿到它写进 `native-session-v1`（键 `registered-cookies`），下次启动再用
     * [restoreDeviceCookies] 灌回来。
     */
    fun deviceCookiesSnapshot(): Map<String,String> = deviceCookiesSnapshotImpl()
    /** 当前主身份 Cookie 快照；登录后是账号设备 Cookie，未登录时是主设备 Cookie。 */
    fun primaryCookiesSnapshot(): Map<String,String> = primaryCookiesSnapshotImpl()
    /** 把上次落盘的注册 Cookie 灌回来（启动时调一次即可）。 */
    fun restoreDeviceCookies(cookies: Map<String,String>) = restoreDeviceCookiesImpl(cookies)

    /**
     * 后台拉取 TNC。
     *
     * TNC 支持匿名 bootstrap：没有 `device_id` / `iid` 时仍可先拿到域名和 HTTP DNS 配置；
     * 设备注册完成后再次刷新，带注册 Cookie 的响应会补充地区信息。
     */
    fun refreshTncAsync(force: Boolean=false): Boolean =
        refreshTncAsyncImpl(force)
    /**
     * 签名与设备参数共用的**三层合并档案**（后一层压前一层）：
     *
     * 1. [VendorConstants.device]：内置档案，兜底用；
     * 2. [deviceIdentity]：本机身份（`device_id` / `iid`），来自 [registerDevice] 的注册结果；
     * 这里只描述未登录时的主设备；登录后请求统一改读 [primaryIdentity] 的账号设备快照。
     */
    private fun deviceProfile(): Map<String,String> = deviceProfileImpl()

    /** 账号设备档案：登录后作为当前主身份。 */
    private fun accountDeviceProfile(account: AccountCredentials): Map<String,String> =
        accountDeviceProfileImpl(account)
    /**
     * 弹幕接口是播放器接口，走 App 域名（与搜索 / 详情同一套设备参数）。
     * 站点有多条线路，单条失败时依次换线，最后一条仍失败才放弃本窗口。
     */
    /**
     * 「相关作品」接口的线路，按实测命中率排序（同一条线路约一半概率回空响应体，
     * 换线重发即可；六条线路轮一圈的成功率接近 1）。
     */
    internal fun relatedHosts(): List<String> = relatedHostsImpl()
    /** 「相关作品」接口最多试几次（每次换一条线路）：空响应体是常态，不是错误。 */
    private val RELATED_ATTEMPTS=8
    internal val relatedAttempts get()=RELATED_ATTEMPTS
    /** 「相关作品」那一栏的 cell 标识：`show_type=547` / `cell_name="相关作品"`。 */
    private val RELATED_CELL_SHOW_TYPE=547
    private val RELATED_CELL_NAME="相关作品"
    internal val relatedCellShowType get()=RELATED_CELL_SHOW_TYPE
    internal val relatedCellName get()=RELATED_CELL_NAME
    /**
     * 相关作品的内存缓存条数：按剧集 ID 索引。
     *
     * 界面自己已经做了「同一部剧不重复预取」（`relatedFor`），这里再兜一层是为了
     * 「看一集 → 回简介 → 再点相关剧集」这条路径：那一栏的内容不会变，
     * 没必要每进一次详情就再打一次接口。
     */
    internal val RELATED_CACHE_LIMIT=16
    internal val relatedCache=linkedMapOf<String,RelatedWorks>()
    /** 作者作品的内存缓存条数：按作者名索引（见 [authorWorks]）。 */
    internal val AUTHOR_WORKS_CACHE_LIMIT=8
    /**
     * 作者作品**一页要多少条**：`count` 直接给 50。
     *
     * 搜索接口的 `count` 是**真的**（实测 `count=20/50/100` 分别回 20/50/100 个 cell，
     * `next_offset` 也跟着走 20/50/100），与官网搜索那种「`count` 一律被忽略」完全不同。
     * 因此没必要再用「20 条一页翻 4 次」凑数：
     *
     * | 取数方式 | 候选条数 | 命中同一作者的条数 | 请求数 | 耗时 |
     * | --- | --- | --- | --- | --- |
     * | 4 页 × 20 | 80 | 30 | 4 | 约 2.5s |
     * | 2 页 × 50 | 99 | 47 | 2 | 约 2.2s |
     * | 1 页 × 100 | 100 | 32 | 1 | 约 1.4s |
     *
     * （实测对象：`延津县种梦互联网信息服务有限公司`，2026-10-29。）
     * 100 那档反而更差：服务端对超大页的排序会把「不太相关但热度高」的条目挤进来，
     * 50 是命中率与单次响应体（约 340 KB）之间的折中。
     */
    internal val AUTHOR_WORKS_PAGE_SIZE=50
    /**
     * 按作者名搜多少页：翻到服务端说 `has_more=false`，或到这个上限。
     *
     * **不能再按「凑够 20 条就停」**：高产作者一次能筛出 29~34 部（实测 `星芒时代` 38 部、
     * `树下的椰子` 49 部），早停会让界面永远只显示前 20 部，用户读到的就是「作品显示不全」。
     * 实测第 1 页命中 10~34 条、第 2 页还有 6~14 条，第 3 页起基本归零，
     * 因此 6 页既够用又不会白等（每页约 1.3~3.0s，见 [AUTHOR_WORKS_PAGE_SIZE] 的实测表）。
     */
    internal val AUTHOR_WORKS_PAGES=6
    /** 页数硬上限：再多只是把同一个作者的作品重排一遍，白等 RTT。 */
    internal val AUTHOR_WORKS_PAGES_MAX=6
    /** 同一页最多试几次（见 [collectAuthorWorks]：网关成片回空响应体，一次失败就停会少一截）。 */
    internal val AUTHOR_WORKS_PAGE_ATTEMPTS=3
    /** 同一页重试的间隔（毫秒）：与 [SEARCH_BLANK_RETRY_DELAY_MS] 同档，再短基本还是空的。 */
    internal val AUTHOR_WORKS_PAGE_RETRY_MS=300L
    /** 连续多少页「一条新的都没有」才收手（见 [collectAuthorWorks]）。 */
    internal val AUTHOR_WORKS_EMPTY_PAGES=2
    /** 同系列补季时最多并发几条（与官网搜索同一档：平台对突发并发敏感）。 */
    private val AUTHOR_WORKS_FAMILY_FANOUT=3
    /**
     * 补季最多问几个家族。
     *
     * 一个家族一个 `related` 请求（约 1.1s），高产作者可能有十几个多季家族，
     * 全问一遍要十几秒——用户等的是「作者作品」这一栏，不是一次全量归档。
     * 12 个家族在 3 路并发下约 4~5s，已经覆盖「点进来最想看的那几部」；
     * **当前这一部的家族永远排在最前**（见 [fillFamilySeasons] 的 `picks`），不会被这个上限挤掉。
     */
    internal val AUTHOR_WORKS_FAMILY_MAX=12
    /** 单个家族的补季请求最多等多久：卡住的那一条不能拖住整栏。 */
    internal val AUTHOR_WORKS_FAMILY_TIMEOUT_MS=12000L
    internal val authorWorksCache=linkedMapOf<String,AuthorWorksResult>()
    /**
     * 发行方作品列表（`POST /novel/commentapi/user/profile/{uid}/v1/`）的页大小 / 页数上限。
     *
     * 页大小与「按作者名搜」同一档：50（见 [AUTHOR_WORKS_PAGE_SIZE] 的实测表）。
     * 客户端自己的 18 一页在这个场景太碎：一个发行方几十部就要翻三四页，
     * 而这条接口的 `count` 同样是真的，一页 50 就能把高产发行方一次取完。
     * 上限 6 页 = 300 部，够用且封顶住翻页
     *（`has_more` 与 `cursor` 是字符串化的 JSON，翻页由 [publisherPage] 解析）。
     */
    internal val PUBLISHER_PAGE_SIZE=50
    internal val PUBLISHER_PAGES=6
    /**
     * 请求「作品」时用的 tab 优先级。
     *
     * `11` = 出品方 / 发行方作品（文档里的那一个），`23` = 通用作品，`21` = 漫剧，
     * `16` = 演员混合，`8` = 演员作品。**`5`（全部视频）刻意不用**：那一栏混着单条视频，
     * `video_id` 不一定是 `series_id`，塞进 [Series] 会让详情页拿到一个不存在的剧集。
     *
     * 真正的顺序以服务端 `data.tabs[]` 为准（见 [ContentDetailParser.publisherTab]）：先发一次默认请求，
     * 读它下发的 `tab_type` 与 `total`，再按这个优先级从中挑一个 `total>0` 的继续翻页。
     */
    internal val PUBLISHER_TABS=intArrayOf(11,23,21,16,8)
    /** 作者身份：用户搜索里的 uid 与真实头像在同一份响应里，缓存也放在一起。 */
    internal data class PublisherIdentity(val uid: String, val avatar: String)
    /**
     * 发行方身份按作者名缓存：一次 tab 27 用户搜索约 1.4s，同一作者不该每次都问。
     * uid 和 `user_avatar` 必须一起缓存，否则分开淘汰后会出现「有 uid、没头像」。
     */
    internal val publisherIdentities=linkedMapOf<String,PublisherIdentity>()
    /** 身份缓存上限：只在内存里活，一百来个足以覆盖一次浏览会话。 */
    internal val PUBLISHER_UIDS_LIMIT=96
    /**
     * 「这个作者没有作品页」的短期记忆：名字 → 记录时刻。
     *
     * 这条直连对非版权方账号要白花 1.4s（用户搜索）+ 0.3s（作品页），
     * 而同一个名字在一次浏览里会被反复点开（列表、详情、返回）。记 10 分钟，
     * 期间直接走搜索；过期后重试，平台那边新开的号也能被认出来。
     */
    internal val publisherMisses=linkedMapOf<String,Long>()
    internal val PUBLISHER_MISS_TTL_MS=600000L
    internal fun commentHosts(): List<String> = commentHostsImpl()
    /**
     * 播放模型请求里上报的 `device_level`。
     *
     * **只使用 4 / 5 两档**：
     *
     * * 4 是默认档，通常下发约 4 个 HEVC 清晰度；
     * * 5 在 4 档空响应时升上去，通常能拿满 5 个 HEVC 清晰度。
     *
     * 1/2/3 档会下发 `bytevc2` 低清晰度，Android 没有对应解码器；过滤后容易只剩 1080P。
     * `need_all_video_definition=true` 仍是拿全档位的前提，每个播放模型请求都会带。
     */
    @Volatile var deviceLevel: Int = 4
        set(value) { field = if(value>=5) 5 else 4 }

    /**
     * 新一集起播前把档位重置回默认的 4。
     *
     * 限流是临时的，上一集升到 5 不代表下一集也要多要档位。
     */
    fun beginEpisode() = beginEpisodeImpl()

    /** 4 档空响应：升到 5 再请求一次；升到 5 后本集不再回退。 */
    internal fun escalateDeviceLevel() {
        escalateDeviceLevelImpl()
    }
    /**
     * 分类接口一页的条数。
     *
     * 早先这里写死 15 并注着「`limit` 传别的也按 15 返回」——**那是错的**，实测
     * `limit` 完全可以放大：给 60 回 60 条、给 200 回 200 条（耗时 866ms vs 15 条 600ms，
     * 单条成本降了一个数量级）。这一点直接决定「按住下键时图跟不跟得上」：
     * 一页 15 条只够铺两行半，焦点两三秒就跑穿了，于是**每次都得停下来等新的一页**——
     * 用户看到的就是「焦点往下走，走到哪儿才开始加载那一片」。
     *
     * 取 100：一屏 6 列 × 约 17 行。实测 100 条一次 794ms，而 15 条也要 600ms——
     * 单条成本差一个数量级，而首包只大一点点。一页铺满近三屏，配合「消耗过半就预取」
     * （见 `PREFETCH_TRIGGER_ROWS`）之后，按住下键基本不会撞到还没回来的下一页。
     */
    internal val CATEGORY_PAGE_SIZE=100

    /**
     * 分类页**换条件后的第一页**取 30 条。
     *
     * 分类页的筛选条件会频繁切换，每次切都拉 100 条既慢又浪费；30 条足够铺几行，
     * 足够确认「这次选中的是不是我要的」。用户继续往下翻时恢复 [CATEGORY_PAGE_SIZE]，
     * 第 2 页从 offset=30 开始，不会漏条。
     */
    internal val CATEGORY_FIRST_PAGE_SIZE=CATALOG_FIRST_PAGE_SIZE

    /**
     * 推荐流一页多少条。
     *
     * 推荐页是「上下换一部」的连续流，一页 20 条已经够刷一阵；取大了只会让进入推荐页
     * 那一下多等几百毫秒。
     */
    private val RECOMMEND_PAGE_SIZE=20

    /**
     * **逻辑页**大小：界面上「一页」按这么多条算，不管底层接口一页能给多少。
     *
     * 分类、榜单、搜索统一聚合成同一个逻辑页，界面侧不必知道底层接口一页是
     * 20 条还是 100 条。
     */
    internal val LOGICAL_PAGE_SIZE=100
    /** 分类 / 榜单页首次进入只取 30 条，后续页再恢复大页。 */
    internal val RANK_FIRST_PAGE_SIZE=CATALOG_FIRST_PAGE_SIZE

    /** 首页剧架那一行的榜单条数：一栏只显示 10 条，取 20 条足够。 */
    internal val RANK_API_PAGE_SIZE=20

    /**
     * 分类 / 榜单接口的重试次数与间隔。
     *
     * 这条 POST 回空响应（`HTTP 200 + Content-Length: 0`）有两个来源：
     *
     * * **2026-10-21 起的主因**：请求没带签名头。平台把这条接口从「免签」收紧成与搜索 / 详情
     *   同一套签名——缺 `x-medusa` 或 `x-helios` 就回空 body，补上后实测 10/10 成功；
     * * 设备没在服务端注册上（见 [registerDevice]）。
     *
     * 两种情况重发都可能拿到完整 JSON（签名每次都换 `ts`），因此保留退避重试：
     * 空响应本身只花约 90ms，最坏总耗时约 4s。
     */
    internal val CATEGORY_ATTEMPTS=8
    /** 首屏只给 3 次机会：失败后尽快出错误页，长重试留给后续分页与后台刷新。 */
    internal val FIRST_PAGE_ATTEMPTS=3
    /**
     * 推荐流那条的重试次数：3 次（退避 100+180ms）。
     *
     * 推荐页的「正在加载推荐…」全压在这条请求上：8 次（最坏 4s）花下去的每一秒，
     * 用户看到的都是一块静止的黑屏。3 次够覆盖「网关偶发空响应」（实测重发 1~2 次就回），
     * 再失败就交给界面换流或先放出手里这一页（见 `TvMainActivity.loadRecommendPage`）。
     */
    internal val RECOMMEND_ATTEMPTS=3
    /** 搜索接口空响应的原地补发次数 / 间隔（见 [retryBlankSearch]）。 */
    internal val SEARCH_BLANK_RETRIES=1
    internal val SEARCH_BLANK_RETRY_DELAY_MS=160L
    /**
     * App 搜索**一页多少条**（界面侧一页）。
     *
     * 搜索接口的 `count` 是真的（见 [AUTHOR_WORKS_PAGE_SIZE] 的实测表），
     * 但界面侧的翻页手感、缓存与「搜索结果已过期」判定都按 20 这一档验证过，
     * 因此这里保持 20 不动；只有作者作品那一栏自己用 50（[AUTHOR_WORKS_PAGE_SIZE]）。
     */
    internal val SEARCH_PAGE_SIZE=20
    /** 官方综合搜索 tab：一次响应包含多个 tab，不再按内容类型分两次请求。 */
    internal val SEARCH_FEED_TAB=11
    /** 综合请求与显式 tab 请求的游标相互隔离，避免探针把界面翻页游标改掉。 */
    internal val SEARCH_FEED_SCOPE=-1
    private val SEARCH_FEED_CONTEXT=linkedMapOf(
        "search_source" to "1","request_mode" to "0","is_first_enter_search" to "true",
        "only_feed" to "false","from_rs" to "false","from_half_screen" to "false",
        "use_lynx" to "false","bookstore_tab" to "16","tab_name" to "feed",
        "last_search_page_interval" to "0","last_book_consume_time" to "0",
        "last_consume_interval" to "0","app_launch_time" to "0",
        "live_room_id" to "0","product_id" to "0","seed_product_id" to "0",
        "line_words_num" to "0","pad_column_cover" to "0","only_large_card" to "false",
        "biz_config_ctx_infos" to "","report_info" to "",
    )
    internal val searchFeedContextValue get()=SEARCH_FEED_CONTEXT
    private val SEARCH_VERSION_CODE="74332"
    private val SEARCH_VERSION_NAME="7.4.3.32"
    internal val searchVersionCodeValue get()=SEARCH_VERSION_CODE
    internal val searchVersionNameValue get()=SEARCH_VERSION_NAME
    private val SEARCH_PATH="reading/bookapi/search/tab/v"
    internal val searchPathValue get()=SEARCH_PATH
    internal val CATEGORY_RETRY_DELAYS=longArrayOf(100L,180L,300L,450L,650L,900L,1200L)
    /**
     * 分类 / 榜单接口用的客户端版本，与改动前手拼 URL 里的那组一致（`73532` / `7.3.5.32`）。
     *
     * 这条接口对版本号不敏感（71332 / 72232 / 73532 / 73932 实测都回完整 JSON），
     * 因此保持原值不动，只把「免签」换成签名。
     */
    internal val CATEGORY_VERSION_CODE="73532"
    internal val CATEGORY_VERSION_NAME="7.3.5.32"

    internal fun text(
        url: String,
        signed: Signer.Request? = null,
        /**
         * 这一次请求用哪个 client 发。
         *
         * 默认 [http]（45 秒整调用超时）；**取地址那一段必须传 [addressClient]**，
         * 它按剩余预算收紧到 [ADDRESS_DEADLINE_MS] 以内。
         * 两者共用同一个连接池与 dispatcher（`newBuilder()` 派生），换 client 不等于换连接。
         *
         * 放在 [observe] **之前**是刻意的：`observe` 是尾随 lambda 的那个参数，
         * 顺序一交换，所有 `text(url, signed) { … }` 的写法都会把 lambda 绑到 client 上。
         */
        client: OkHttpClient = http,
        allowBackup: Boolean = true,
        /** 200 空 body 时先按同一套参数遍历其它接口域名。 */
        allowDomainFailover: Boolean = true,
        /** TNC 条件请求允许 304 Not Modified。 */
        allowNotModified: Boolean = false,
        /** 播放接口只靠注册 Cookie 表达区域，避免显式地区头把请求固定到单一调度区域。 */
        storeRegionHeaders: Boolean = true,
        /** 非空时用它生成 UA；推荐账号请求必须与签名设备档案保持同一份。 */
        userAgentProfile: Map<String,String>? = null,
        /**
         * 拿到响应、还没读 body 时回调一次。
         *
         * 播放接口被挡时回的是 `200 + 0 字节`，状态码与 body 都看不出区别，
         * 只有响应头里的 `tt_stable: 1` 能区分（见 [probeModelPath]），因此需要这个口子。
         */
        observe: ((okhttp3.Response) -> Unit)? = null,
    ): String {
        val requestCookie=signed?.headers?.entries
            ?.firstOrNull { it.key.equals("Cookie",ignoreCase=true) }
            ?.value
            ?: primaryCookieHeader()
        val requestCookies=com.hgtv.star.core.session.CookieRecord.headerToMap(requestCookie)
        val request = Request.Builder().url(url)
            .header("User-Agent",VendorConstants.userAgent(userAgentProfile ?: primaryProfile(), video = true))
            .header("Accept-Language","zh-CN,zh;q=0.9")
        // 账号 / App 接口的公共协议头。参考实现（`passport_client.go` 的 `request`）每一条都带，
        // 早先这里一条都没有：
        // * `X-SS-DP` = `8662`（HGTV的 aid，网关按它路由）；
        // * `X-Tt-Store-Region` / `-Src` —— 平台按区域分流；只使用注册下发的值，
        //   没有注册结果时直接省略，不伪造默认地区。
        request.header("X-SS-DP","8662")
        if(storeRegionHeaders) {
            requestCookies["store-region"]?.takeIf { it.isNotBlank() }?.let { request.header("X-Tt-Store-Region",it) }
            requestCookies["store-region-src"]?.takeIf { it.isNotBlank() }?.let { request.header("X-Tt-Store-Region-Src",it) }
        }
        request.header("X-XS-From-Web","0")
        request.header("Sdk-Version","2")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        // 方法以 [Signer.Request.method] 为准，不再靠「有没有 body」猜：
        // `search/tab` 只认 GET（POST 该路径是 404），`video_detail` 之类必须 POST。
        signed?.let { signedRequest ->
            when(signedRequest.method) {
                "POST" -> request.post(RequestBody.create("application/json; charset=utf-8".toMediaType(),
                    signedRequest.body ?: ByteArray(0)))
                "GET" -> request.get()
                else -> request.method(signedRequest.method, signedRequest.body?.let { payload ->
                    payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                })
            }
        }
        var httpCode=0
        val raw=RequestScope.execute(client.newCall(request.build())) { response ->
            httpCode=response.code
            observe?.invoke(response)
            if (!response.isSuccessful && !(allowNotModified && response.code==304)) {
                throw IOException("内容请求失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
        if(raw.isBlank() && httpCode==200 && !allowNotModified) {
            NetLog.warn("[API] empty-body host=${signed?.let { runCatching { okhttp3.HttpUrl.parse(it.url)?.host }.getOrNull() }.orEmpty()} path=${signed?.let { runCatching { okhttp3.HttpUrl.parse(it.url)?.encodedPath }.getOrNull() }.orEmpty()}")
        }
        if(raw.isBlank() && httpCode==200 && allowDomainFailover && signed!=null) {
            val currentHost=runCatching { okhttp3.HttpUrl.parse(signed.url)?.host }.getOrNull().orEmpty()
            val profile=userAgentProfile ?: primaryProfile()
            val hosts=alternateApiHosts(currentHost)
            for((index,host) in hosts.withIndex()) {
                NetLog.retry("domain_failover",index+1,hosts.size,"HTTP 200 empty body from $currentHost")
                val retry=resignForHost(signed,host,profile) ?: continue
                val value=text(
                    retry.url,
                    retry,
                    client=client,
                    allowBackup=false,
                    allowDomainFailover=false,
                    storeRegionHeaders=storeRegionHeaders,
                    userAgentProfile=profile,
                )
                if(value.isNotBlank()) {
                    TncConfigStore.markHostSuccess(host)
                    NetLog.emit("domain failover accepted host=$host")
                    return value
                }
            }
        }
        if(raw.isBlank() && httpCode==200 && allowBackup && signed!=null) {
            val anonymous=signed.headers["X-HGTV-Anonymous"]!=null
            val model=signed.url.contains("video_model") || signed.url.contains("multi_video_model")
            if(!anonymous && !model) {
                val backup=runCatching { playbackBackupDevices?.invoke()?.firstOrNull() }.getOrNull()
                if(backup!=null) {
                    val retry=backupRequest(signed,backup)
                    if(retry!=null) {
                        NetLog.retry("device_backup",1,1,"primary identity returned HTTP 200 empty body")
                        NetLog.emit("device backup retry host=${retry.url.substringAfter("//").substringBefore("/")} id=${backup.id.takeLast(6)}")
                        return text(
                            retry.url,
                            retry,
                            client=client,
                            allowBackup=false,
                            allowDomainFailover=false,
                            storeRegionHeaders=storeRegionHeaders,
                            userAgentProfile=backup.profile,
                        )
                    }
                }
            }
        }
        return raw
    }

    /**
     * 协议常量：官方 App 里也是硬编码，不随设备变。
     *
     * 动态化**只针对设备属性**（机型 / 系统 / device_id / cdid …），
     * `aid` / `app_name` 这类跟着官方包走就行。
     */
    internal val PROTOCOL_VALUES = linkedMapOf(
        "ac" to "wifi","aid" to "8662","app_name" to "novelread",
        "channel" to "69310258a","ssmix" to "a",
        "device_platform" to "android","os" to "android","language" to "zh",
        "compliance_status" to "0","need_personal_recommend" to "1","player_so_load" to "1",
        "host_abi" to "arm64-v8a"
    )
    internal val protocolValuesValue get()=PROTOCOL_VALUES
    /**
     * 写接口（追剧 / 进度 / 历史）用的设备参数。
     *
     * 与 [appValues] 的两点不同，都是照官方抓包对齐的：
     * * 客户端版本用 `73932` / `7.3.9.32`（写接口走的是这一版，混用会被按旧客户端处理）；
     * * 叠加导入凭据时带来的**真机档案**（`cdid` / `klink_egdi` / `rom_version` 这些
     *   内置档案里根本没有，而官方请求每条都带）。
     */
    private fun writeValues(): LinkedHashMap<String,String> = writeValuesImpl()
    /** 搜索 / 弹幕这类 App 接口共用的设备参数：与播放器版本对齐，避免平台按旧版本拒绝。 */
    internal fun appValues(): LinkedHashMap<String,String> = appValuesImpl()
    /** 搜索接口专用：官方抓包里的 7.4.3.32 设备版本与综合搜索上下文。 */
    internal fun searchValues(): LinkedHashMap<String,String> = searchValuesImpl()
    /** 综合搜索与用户 tab 共用这一份搜索签名。 */
    internal fun searchRequest(values: LinkedHashMap<String,String>): Signer.Request =
        searchRequestImpl(values)
    /** 账号页读写接口使用的当前主身份参数。 */
    private fun accountAppValues(): LinkedHashMap<String,String> = accountAppValuesImpl()
    /**
     * 设备参数使用 [primaryIdentity] 的单一快照：未登录是主设备，登录后是账号设备。
     * [PROTOCOL_VALUES] 与客户端版本只覆盖协议常量，不改变身份来源。
     */
    internal fun deviceValues(versionCode: String, versionName: String): LinkedHashMap<String,String> =
        deviceValuesImpl(versionCode, versionName)
    private fun accountValues(versionCode: String, versionName: String): LinkedHashMap<String,String> =
        accountValuesImpl(versionCode, versionName)
    /** 普通业务请求也使用当前唯一的主身份；登录后即账号设备。 */
    internal fun Signer.Request.withSession(): Signer.Request {
        return withSessionImpl(this@ContentRepository)
    }

    /** 所有请求共用的当前主设备档案：登录后以账号设备为准。 */
    internal fun primaryProfile(): Map<String,String> = primaryProfileImpl()

    /** 所有请求共用的当前主 Cookie：登录后只使用账号 Cookie，不再叠加匿名主设备 Cookie。 */
    internal fun primaryCookieHeader(): String = primaryCookieHeaderImpl()

    /** 账号页请求显式要求当前主身份必须是登录态。 */
    private fun Signer.Request.withAccountSession(): Signer.Request {
        return withAccountSessionImpl(this@ContentRepository)
    }
    /** 有没有可用的登录态：搜索要不要优先走 App 接口、个人信息页要不要联网，都看它。 */
    val signedIn: Boolean get()=credentials?.configured==true || !sessionToken.isNullOrBlank()
    /**
     * 推荐流的一页。
     *
     * 推荐页统一走分类接口，并固定按 `hot_score` 取内容；`session_id` / `offset`
     * 仍由调用方续流，避免每次打开都回到同一批。官方 `bookmall/tab` 流不再参与
     * 推荐页取数。
     *
     * [selection] 的 `gender` 仍会传进请求；[genres] 用于推荐范围筛选。
     *
     * **返回的 `nextOffset` 一定往前推**：早先这里有一句 `.coerceAtMost(2000)`，把游标钳在
     * 2000 上——实测 `offset=1980/1990/1999/2000` 回的都是 `next=2000`，于是每页都从 2000
     * 重新开始，用户每次打开推荐看到的永远是同一批（服务端自己也在 2000 钳位）。现在不钳，
     * 由界面侧按「游标不前进 = 这条流刷不动了」换新流（见 `TvMainActivity.loadRecommendPage`）。
     *
     * [filterIds] 是**本机已经推给用户看过的剧 id**（文档 §2.1 / §4.4 的 `filter_ids`）：
     * 传了就请服务端在这一页就把它们滤掉，客户端那层窗口只当兜底。空串 = 不带，
     * 与旧版请求逐字节一致。长度上限见 [RecommendFlow.FILTER_IDS_LIMIT]。
     */
    override fun recommendFeed(
        selection: CategorySelection,
        sessionId: String,
        offset: Int,
        limit: Int,
        genres: List<String>,
        filterIds: String,
        tab: RecommendTab,
    ): RecommendFeed = recommendFeedImpl(selection,sessionId,offset,limit,genres,filterIds,tab)

    /**
     * 上报推荐曝光（官方 `FilterImpression`）。
     *
     * 拉推荐流和上报必须使用同一套身份：登录时用账号 Cookie / token 与账号设备档案，
     * 未登录时才回落到主设备。批量、节流和失败重试由 [RecommendBehavior] 统一处理。
     */
    override fun reportRecommendStats(events: List<RecommendStatData>) =
        reportRecommendStatsImpl(events)

    /**
     * 出品方：把接口给的 `copyright` 归一成能直接显示的名字。
     *
     * 短剧那一栏里有一部分是**占位串**（`短剧版权3493645060`、`短剧版权5146586586`），
     * 那是平台没填版权方时自动生成的编号，显示出来只会让卡片第二行变成一串数字。
     * 这类一律当空值处理：卡片那一行收起，由 [authorsOf] 的详情兜底去补。
     */
    fun publisher(raw: String): String {
        return ContentCatalogParser.publisher(raw)
    }
    /**
     * 题材 / 类型标签：卡片第二行显示的那一串（`玄幻 · 逆袭`）。
     *
     * 三处来源同一个意思，字段名不同：
     * * App 列表 / 搜索 / 详情：`category_schema`（JSON 字符串，`[{"name":"玄幻"}]`）；
     * * 官网搜索：`category_list`（真数组）。
     *
     * 最多取 5 个、` · ` 连接（与详情页信息行同一套写法）。都没有就返回空串，
     * 卡片第二行退回状态行（进度 / 热度），而不是留一条空白。
     */
    fun tagsOf(o: JSONObject, key: String = "category_schema"): String {
        return ContentCatalogParser.tagsOf(o,key)
    }
    /**
     * 搜索提示词（联想词）：App 搜索框用的那个接口。
     *
     * ```
     * GET https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/suggest/v:version/?q={关键词}
     * ```
     *
     * 需要设备参数与签名（[searchValues] + [Signer]），不需要登录态。空关键词不发请求。
     *
     * 响应里 `data.query_result_v2[]` 每条的字段：
     *
     * | 字段 | 说明 |
     * | --- | --- |
     * | `name`（兜底 `display_words[0]`） | 提示词本身 |
     * | `word_type_str` | `short_play_name`（剧名）/ `title` / `album` / `common_query` / `short_play_category` … |
     * | `video_data` | **有 `series_id` 时**才是「这条本身就是一部剧」，可以直接进详情；否则只能当查询词 |
     *
     * `video_data` 与列表卡片是同一套字段（`title` / `cover` / `video_desc` / `episode_cnt` /
     * `copyright`），因此复用 [apiCard] 解析。
     */
    fun suggestions(keyword: String, count: Int = 10, source: SearchSource = SearchSource.APP): List<SearchSuggestion> =
        if(source==SearchSource.WEB) webSuggestions(keyword,count) else appSuggestions(keyword,count)
    /**
     * 官网的联想词：`GET /api/search/suggestion?query={关键词}&count=N`。
     *
     * 免签名、免登录；`video_data` 与卡片同源，有 `series_id` 的那几条本身就是一部剧。
     * 搜索源选「官网」时搜索页那一行用它。
     *
     * 老地址 `/incent_resource/suggestion?app_id=8662` 现在仍然能回，但字段是 `suggest_list`、
     * 与页面那一套绑得更紧；新地址跟搜索结果是同一个接口组，一起换过来更稳（两个都认）。
     */
    fun webSuggestions(keyword: String, count: Int = 10): List<SearchSuggestion> =
        webSuggestionsImpl(keyword,count)
    /**
     * 热搜词：搜索页空输入时铺在下面那一排。
     *
     * 平台**没有**热搜榜接口（`hot_word` / `suggest_word` 这类路径实测全是 404），
     * 但 [suggestions] 这个联想词接口本身就是「按热度排的候选」：拿几个题材词当种子各问一次，
     * 把返回里**不是某一部剧**的那些（`title` / `common_query` / `short_play_category` / `category` /
     * `album`——`video_data` 是空对象）收集起来去重，就是一份现成的热词表。
     *
     * 只取种子词里靠前的若干条：联想词是按相关度排的，越靠前越像「大家在搜的」。
     * 全部种子词都失败时退回 [FALLBACK_HOT]：搜索页不能因为一个提示接口而空着。
     */
    fun hotSearches(limit: Int = 12, source: SearchSource = SearchSource.APP): List<String> =
        hotSearchesImpl(limit,source)
    /**
     * 界面上的搜索入口。
     *
     * [source] 决定走哪一套：App 接口（默认，官方综合请求、游标翻页）或官网搜索
     * （免签名免登录，把 10 个物理页并成 1 个逻辑页）。界面侧只认 [CatalogPage]。
     */
    fun search(keyword: String, page: Int = 1, source: SearchSource = SearchSource.APP, fallbackPages: Int = WEB_FALLBACK_BATCH): CatalogPage =
        searchImpl(keyword,page,source,fallbackPages)
    /** 只搜一种内容类型：联调探针仍可显式指定 `tab_type`。 */
    fun search(keyword: String, page: Int, type: ContentType): CatalogPage =
        appSearch(keyword,page,type.searchTab,SEARCH_PAGE_SIZE)
    /**
     * 官网搜索：`GET https://hongguoduanju.com/api/search/result?query={关键词}&page=N`。
     *
     * 2026-10 之前走的是搜索页 HTML 里的 `_ROUTER_DATA`；`www` 改成 301 到裸域名、
     * 页面也不再内联 SSR 数据之后，那条路整条失效。现在直连官网自己用的 JSON 接口，
     * 字段与卡片同源（`searchList[].video_data`），`totalPages` / `hasMore` 由服务端给。
     *
     * **一个逻辑页给界面 100 条**：服务端一页只有 10 条，而且 `count` / `page_size` / `limit`
     * 这类参数一律被忽略（见 [WEB_SEARCH_PAGE_SIZE]），所以这里把 [WEB_SEARCH_BATCH] 个
     * 物理页并发拉回来拼成一份，界面侧「展示更多」一次就能多出一屏十行。
     *
     * 查不到东西时接口回的是 `{"searchList":[],"totalPages":1}`（HTTP 200），
     * 按「没有结果」处理，让界面显示「换个关键词试试」。
     */
    fun webSearch(keyword: String,page: Int = 1): CatalogPage =
        webSearchImpl(keyword,page)
    // ------------------------------------------------------------------ 账号（个人信息页）
    /**
     * 账号接口共用的一次请求：带当前主身份、设备参数和签名。
     * 登录后是账号身份；未登录时是已注册的主设备身份。
     *
     * 这些接口都在 App 域名下（与搜索 / 弹幕同一套），因此设备参数用 [appValues]；
     * 写接口（POST + body）改用 [writeValues]——它们走的是官方 7.3.9.32 那一版，
     * 而且平台会拿签名里的设备档案做一致性判定。
     *
     * 空响应体在这个平台上很常见（网关挡掉），统一翻成可读的 [IOException]，
     * 免得把 JSONException 抛到界面上。
     */
    private val accountTransport by lazy {
        ContentAccountTransport(
            accountValues = ::accountAppValues,
            writeValues = ::writeValues,
            primaryProfile = ::primaryProfile,
            accountHost = ::accountHost,
            sign = { url, values, body, profile ->
                signer.sign(url, values, body, deviceOverride=profile)
            },
            withSession = { it.withSession() },
            text = { url, request -> text(url, request) },
            retries = ACCOUNT_RETRIES,
        )
    }
    internal fun accountJson(path: String, params: Map<String,String> = emptyMap(), body: ByteArray? = null): JSONObject {
        return accountTransport.json(path,params,body)
    }

    /**
     * 空响应体重发：这个平台上「网关偶发挡掉」很常见（实测约一半概率），
     * 同一请求重发即可。只重试空 body，真的报错（100103 等）直接抛出去。
     */
    internal fun retryBlank(attempts: Int = ACCOUNT_RETRIES, action: () -> JSONObject): JSONObject {
        return accountTransport.retryBlank(attempts,action)
    }

    fun accountProfile(): AccountProfile? = accountProfileImpl()
    fun accountFavorites(): List<AccountFavorite> = accountFavoritesImpl()
    fun accountHistory(): List<AccountHistory> = accountHistoryImpl()
    fun accountLikes(): List<AccountLike> = accountLikesImpl()
    fun accountReserves(): List<AccountReserve> = accountReservesImpl()
    fun accountReserve(seriesId: String, subscribe: Boolean): Boolean = accountReserveImpl(seriesId,subscribe)
    fun accountAddFavorite(seriesId: String): Boolean = accountAddFavoriteImpl(seriesId)
    fun accountRemoveFavorite(seriesId: String): Boolean = accountRemoveFavoriteImpl(seriesId)
    fun accountUploadProgress(seriesId: String,episodeId: String,episodeIndex: Int,position: Long,duration: Long,episodeCount: Int=0): Boolean =
        accountUploadProgressImpl(seriesId,episodeId,episodeIndex,position,duration,episodeCount)
    fun accountDeleteHistory(seriesId: String): Boolean = accountDeleteHistoryImpl(seriesId)
    fun accountDeleteHistories(seriesIds: List<String>): Boolean = accountDeleteHistoriesImpl(seriesIds)
    fun accountHistoryIds(): List<String> = accountHistoryIdsImpl()
    fun accountClearHistory(extra: List<String> = emptyList()): Boolean = accountClearHistoryImpl(extra)
    fun accountData(): AccountData = accountDataImpl()
    internal fun authorOf(id: String): String = authorOfImpl(id)
    fun authorsOf(ids: List<String>): Map<String,String> = authorsOfImpl(ids)

    /**
     * 剧集详情（简介 + 分集列表）：走 App 播放侧的详情接口。
     *
     * ```
     * POST https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_detail/v1/?<设备 + 签名>
     * body: {"series_id":"<剧集 ID>"}      ← 参数是单值 `series_id`，给 `series_ids` / `book_id` 一律 100001
     * ```
     *
     * `data.video_data` 就是这一部的详情：`series_title` / `series_cover` / `series_intro` /
     * `episode_cnt` / `episode_right_text` / `category_schema`（题材）；**分集在 `video_list[]`**，
     * 每条的 `vid` 就是播放与弹幕要的那个分集 ID，`vid_index` 是集号（1 起，按它排序）。
     *
     * 实测：222 集的那一部一次给全 222 条（一个请求约 145 KB），顺序与集号一致。
     *
     * 简介页下方那一栏**不在这里取**：它是「相关作品」接口（见 [related]），要额外发一次请求，
     * 挂在详情上会让每次进简介页都多等一个 RTT。界面在详情上屏之后后台预取。
     *
     * [cached] 为真时先看落盘缓存（[DetailCache]，30 分钟）：推荐页起播走这一条——
     * 那一页要的只是「第 1 集是哪个 vid」，分集表旧半小时完全不影响起播；
     * 详情页保持默认（现取），用户在那里读的是集数与更新。
     *
     * [includeAuthor] 为 false 时跳过作者补查：详情页先拿到主体内容上屏，
     * 作者由界面在后台补，不再让一个附加字段挡住整页。
     */
    fun detail(id: String,cached: Boolean=false,includeAuthor: Boolean=true): Detail =
        detailImpl(id,cached,includeAuthor)
    fun related(id: String, title: String = ""): RelatedWorks = relatedImpl(id,title)
    fun authorWorks(
        author: String,
        reference: Series? = null,
        pages: Int = AUTHOR_WORKS_PAGES,
        onAvatar: ((String) -> Unit)? = null,
        onPartial: ((List<Series>) -> Unit)? = null,
    ): List<Series> = authorWorksImpl(author,reference,pages,onAvatar,onPartial)

    fun heatValue(raw: String): Long {
        return heatValueImpl(raw)
    }
    /**
     * 两个作者名是不是同一家。
     *
     * 只忽略空白与大小写：`multi-detail` 与列表接口给的是同一个字段（`copyright` → `author`），
     * 差异只可能出在两端空格与全角 / 半角上，做模糊匹配反而会把「同 IP」的别家作品混进来。
     */
    internal fun sameAuthor(left: String,right: String): Boolean {
        return sameAuthorImpl(left, right)
    }
    /**
     * 同 [sameAuthor]，但**作者为空也算命中**。
     *
     * 只给 [fillFamilySeasons] 用：`related` 的卡片不带 `copyright`（实测全为空串），
     * 未上线的季（`枯骨成仙第九季`）在搜索里也是空。此时「是不是这一家的」只能靠家族名判定
     *（调用方已经先按 [SeriesSeasons.normalize] 比对过剧名），空作者不能再被当成「别家」丢掉，
     * 否则那几季永远补不回来——这正是「同一部剧只显示 3 季」的另一半原因。
     *
     * 代价：`枯骨成仙第一季`（别家翻拍，`related` 里作者同样为空）会被一起收下。
     * 它没法与「未上线的自家季」区分，界面上它的作者行留空，不会误标成这一家。
     */
    internal fun authorUnknownOrSame(left: String,right: String): Boolean {
        return authorUnknownOrSameImpl(left, right)
    }

    /**
     * 取一集的播放模型（`video_model`）。
     *
     * 平台要求请求里的 `device_id`、`iid`、签名密文和注册 Cookie 来自同一台设备。
     * 2026-11-06 实测：注册下发的 `device_id` 返回 `code 0` 和完整模型，任意随机
     * 15 位数字则稳定返回 `200 + 0 字节`。因此空响应必须按设备身份失效处理，
     * 由界面层重新注册后再试，不能继续伪造新的 `device_id`。
     *
     * 候选表只保留「新路由 + 老路径」两条并串行探测；第一条业务失败才试第二条，
     * 设备身份失效则立即停止探测并交给自愈流程。
     */
    private fun videoModel(
        id: String,
        deadline: Long,
        playback: PlaybackProfile,
        queue: List<String> = emptyList(),
    ): JSONObject = videoModelImpl(id,deadline,playback,queue)

    /**
     * 用一次官方批量接口预取一组播放模型。
     *
     * 推荐页与正式播放页的批量窗口由界面层决定；这里只负责把整组 ID 放进同一次请求，
     * 并把响应写进共享模型缓存，后续 [stream] 直接命中。
     */
    fun prefetchModels(
        ids: List<String>,
        playback: PlaybackProfile = PlaybackProfile(),
    ): Int = prefetchModelsImpl(ids,playback)

    /**
     * 取一集的播放地址。
     *
     * 全站只有一种视频编码（HEVC），因此这里没有「挑哪条编码」这一维：
     * 上限之内取最高的一档，上限之内没有就取最低一档（标注「兼容资源」）。
     */
    fun stream(
        id: String,
        maxQuality: Int = 1080,
        playback: PlaybackProfile = PlaybackProfile(),
        queue: List<String> = emptyList(),
    ): StreamInfo = streamImpl(id,maxQuality,playback,queue)
    /**
     * 取地址（播放模型 + `fallback_api`）**整段**的时间上限。
     *
     * ## 为什么必须有它，而且是 6 秒
     *
     * 取地址是**交互动作**，不是慢速下载：正常一次 500~1100ms（实测
     * `Stage stream 1080P +624ms`）。可它原来吃的是传输层的
     * `callTimeout(45s)`——断网时 SYN 石沉大海，界面上那句「正在获取播放地址…」
     * 要一动不动地挂 **45 秒**，然后才开始自愈（再 45 秒）、再看 3 次自动重试。
     * 现场（2026-10-30，小米 TV）实测：断网切集 → 联网，界面冻了 **49.6 秒**。
     *
     * 6 秒的口径：**余额用完立刻放弃这一轮**，交给上层
     * （地址自愈 → 错误页 + 自动重试 → 用户「立即重试」），每一步都是秒级，
     * 全链路最坏也在十几秒内走完，而不是一次就烧掉 45 秒。
     *
     * 为什么是 6 秒：电视盒子在弱网 + 冷 DNS 下实测会到 1.3~3.1 秒
     *（`net POST … tcp=3135ms` 是断网恢复那一刻建连的真实耗时），
     * 3 秒会把「慢但能成」的一发误杀成失败；6 秒仍远低于原来的 45 秒，
     * 同时给冷 DNS、TLS 与服务端排队留出一次完整机会。
     */
    internal val ADDRESS_DEADLINE_MS=6_000L
    /** 余额低于这个值就不再发请求：几百毫秒不可能完成建连 + TLS + 服务端处理。 */
    internal val ADDRESS_MIN_BUDGET_MS=300L
    /**
     * 按「剩余预算」派生的取地址 client：连接 / 读 / 写 / 整调用四个超时全部压到余额。
     *
     * 用 `http.newBuilder()` 派生，因此**共用同一个连接池与 dispatcher**——
     * 取地址与分片读视频仍然是同一批连接，不会因为换 client 而多握手一次。
     * 按 100ms 分桶缓存：每一发都 `newBuilder().build()` 会不停复制拦截器链，
     * 而余额在 6 秒内的取值本来就只有六十个。
     */
    internal val addressClients=linkedMapOf<Long,OkHttpClient>()
    /**
     * 这一集取回来的地址不认了（403 / 404）：内存与落盘一起丢掉，下次重新取。
     *
     * 界面层在「取地址之后本地准备失败」时调它，然后重来一次——这样用户看到的是
     * 多等半秒，而不是「解码失败，请换一部」。
     */
    /**
     * [maxQuality] 必须与当初 [stream] 用的那个一致，且**读的是当前档位**：
     * 键里带着 `device_level`，降档重试时要按新的上限去删，否则删的是另一条
     *（真正那条还留在盘上，下一次取地址又命中它，白重试一轮）。
     */
    fun forgetStream(id: String,maxQuality: Int = 1080,playback: PlaybackProfile = PlaybackProfile()) =
        forgetStreamImpl(id,maxQuality,playback)

    /**
     * 这一集**所有档位**的地址都不认了。
     *
     * 用在「队列里那一集失败」这条路上：那一刻只知道是哪一集，不知道它当初是按哪个上限
     * 预取的（预取时的上限可能已经被降档改过，`device_level` 也可能变过），
     * 精确键算不出来——[forgetStream] 用错键就等于什么都没删，重切时原样命中同一条
     * 坏地址，第二次必然还是 403。
     *
     * 内存缓存按 `集:` 前缀整体清（键是明文，能前缀匹配）；落盘按集 id 清
     * （见 [PlaybackCache.invalidateUrlById]）。多删几条的代价只是下一次多一次往返。
     */
    fun forgetStreamAllQualities(id: String) = forgetStreamAllQualitiesImpl(id)

    /**
     * 用户侧「再试一次」时作废当前设备签发的播放模型缓存。
     *
     * 设备号只能由平台注册下发；身份失效时由 `DeviceHealer` 重新注册。
     * 这里保留原方法名供两端调用，但不再伪造新的 `device_id`。
     */
    fun rotateDeviceForRetry(): String = rotateDeviceForRetryImpl()

    /**
     * 取一集的弹幕，也就是「原 App」播放时看到的同一份数据。
     *
     * 接口是播放器自己的评论接口：`POST /novel/commentapi/comment/list/{vid}/v1/`，
     * 请求体带 `comment_source=601`（短剧）、`server_channel=1000`、`group_type=30`、`comment_type=20`，
     * `business_param` 里再给剧集 ID 与本集时长；响应按 30 秒一个窗口返回，
     * `extra.next_query_danmaku_list_time` 是下一个窗口的起点，`common_list_info.has_more` 表示还没到片尾。
     *
     * [durationSeconds] 是这一集的时长（秒），用来判断何时停：为 0 时按窗口数量上限兜底。
     * [onWindow] 每拉到一个窗口就被回调一次（传当前已收集的全部弹幕，按时间排好），
     * 让播放界面边拉边画：整集拉完要十几秒，等全量再上屏等于前十几秒一条弹幕都没有。
     * 回调跑在调用线程上，调用方自己负责切回主线程。
     * 网络失败或平台不再返回弹幕时返回空列表，播放界面不显示任何弹幕，也不弹错误。
     */
    fun danmaku(vid: String, seriesId: String, durationSeconds: Long = 0, shouldStop: () -> Boolean = { false }, onWindow: (List<Danmaku>) -> Unit = {}): List<Danmaku> =
        danmakuImpl(vid,seriesId,durationSeconds,shouldStop,onWindow)
    /**
     * 调试用：一个窗口的原始 `data_list`。
     *
     * 只看接口到底发了什么字段（表情是不是只有文本一种形式），联调表情表时用，界面不调用。
     */
    fun danmakuRawWindow(vid: String,seriesId: String,startMs: Long=0): List<org.json.JSONObject> =
        danmakuRawWindowImpl(vid,seriesId,startMs)
    companion object {
        /**
         * 设备身份自愈的**唯一入口**：服务端明确不认这份身份时，换一台设备重注册。
         *
         * `core` 是纯 Kotlin 模块，拿不到 `Library` 与注册流程（那些在 `:shared`），
         * 因此这里只留一个钩子，由 `AppRuntime` 在进程启动时接到
         * `com.hgtv.star.shared.data.DeviceHealer.heal`。
         *
         * 参数是「调用方的仓库」而不是固定实例：预取 / 收藏用的是另一个
         * [ContentRepository]，但它与主实例认的是同一台设备，换完之后要让它自己那一份
         * 内存身份也跟上（见 `DeviceHealer.heal` 的收尾）。
         *
         * 返回 true 表示**已经拿到一份可用的新身份**，调用方可以原地重试。
         * 钩子为空（单测 / 没接线）时一律按「修不了」处理，不改变原有行为。
         */
        @Volatile var deviceHeal: ((ContentRepository, String) -> Boolean)? = null
        /**
         * 主身份遇到 `HTTP 200 + 空 body` 时使用的唯一备用设备。
         *
         * provider 返回当前可用快照；备用设备只参与本次请求，不会覆盖主身份。
         */
        @Volatile var playbackBackupDevices: (() -> List<PlaybackBackupDevice>)? = null
        /**
         * 「服务端不认这份设备身份」的累计次数（进程级）。
         *
         * 为什么放在 companion 而不是实例字段：预取用的是**另一个** [ContentRepository]，
         * 但它与主实例认的是同一台设备，服务端拒绝也是同一件事。按实例各记一份的话，
         * 界面层只看得到主实例那一次，预取被拒就没人处理。
         */
        @Volatile private var deviceRejectedCount: Int = 0
        internal val deviceRejectedCountValue get()=deviceRejectedCount
        internal fun incrementDeviceRejectedCount() { deviceRejectedCount++ }
        /**
         * 取播放地址的**进程级单飞闸门**（见 [videoModel]）。
         *
         * 这条接口对并发极敏感：同一台设备单发 50~80% 命中、20 并发 0 命中（2026-10-27 实测）。
         * 而一次点播可能同时有主实例 / 下一集预取 / 推荐页预热三份请求，各发一轮就是三份并发。
         * 拿它串行化之后，同时到达的请求变成「一个人打、其余等结果」，成功率回到单发那一档。
         */
        internal val modelGate=java.util.concurrent.Semaphore(1,true)
        /** 正在等待 / 执行前台播放模型的调用数；preload 见到正数就让路。 */
        internal val foregroundModelWaiters=java.util.concurrent.atomic.AtomicInteger(0)
        /** 当前在途的后台 preload；前台起播时直接 cancel。 */
        @Volatile internal var backgroundModelScope: RequestScope?=null
        /** 等闸门的上限：给正在完成的那一发留足余量。 */
        internal const val MODEL_GATE_TIMEOUT_MS=25_000L
        /**
         * 两次播放模型请求之间的最小间隔（进程级，见 [waitModelSlot]）。
         *
         * 只留一个很小的值挡「异常循环里的连打」；身份失效由重注册处理。
         */
        internal const val MODEL_SPACING_MS=200L
        /** 上一次打播放接口的时刻（0 = 本次进程还没打过，第一次不等待）。 */
        internal val modelLastAttemptAt=java.util.concurrent.atomic.AtomicLong(0L)
        /** 保护播放模型缓存代次，避免并发重试时重复或漏掉作废。 */
        internal val deviceRotateLock=Any()
        /**
         * 「身份代次」：身份重注册或地址重试时 +1，并进 [videoModel] 的缓存键。
         *
         * 没有它，重试会命中 60 秒的 [MODEL_CACHE_MS] 模型缓存——那份模型里带着
         * **旧身份签发的 `fallback_api` 地址**，于是重注册后还会拿到同一条死地址。
         */
        @Volatile internal var modelCacheGen: Int=0
        /**
         * 播放模型的合并窗口。
         *
         * 必须覆盖当前批 6 集的消费时间；缓存太短会在同一台新匿名设备上再次请求批量接口，
         * 而该设备的第一批额度已经用完。地址本身与 [PlaybackCache.URL_FRESH_MS] 同窗。
         */
        internal const val MODEL_CACHE_MS=PlaybackCache.URL_FRESH_MS
        /** 播放器打开时批量取当前集 + 后 14 集；preload 只在首帧稳定后补后 6 集。 */
        /** 单个播放模型域名的预算；整段 6 秒内至少还能换到下一域名。 */
        internal const val MODEL_HOST_ATTEMPT_MS=3_000L
        internal const val PLAYER_MODEL_PATH="/novel/player/multi_video_model/player/v1"
        internal const val PLAYER_MODEL_BATCH_LIMIT=15
        internal const val BATCH_MODEL_PATH="/novel/player/multi_video_model/preload/v1"
        internal const val PRELOAD_MODEL_LIMIT=6
        internal const val MODEL_CACHE_LIMIT=32
        /** 官方 `video_model` query 的字段顺序；设备档案里的其它字段不进入播放请求。 */
        internal val MODEL_QUERY_KEYS=listOf(
            "klink_egdi","iid","device_id","ac","channel","aid","app_name",
            "version_code","version_name","device_platform","os","ssmix",
            "device_type","device_brand","language","os_api","os_version",
            "manifest_version_code","resolution","dpi","update_version_code",
            "host_abi","dragon_device_type","pv_player","compliance_status",
            "need_personal_recommend","player_so_load","is_android_pad_screen",
            "rom_version","cdid",
        )
        /**
         * 查询参数里的设备字段。
         *
         * 备用设备托底时这些键必须整套移除后用备用档案重填；业务参数（剧 id、分页、
         * 搜索词等）保留，避免出现「备用 did + 主设备 cdid / Cookie」的混参。
         */
        internal val DEVICE_QUERY_KEYS=(MODEL_QUERY_KEYS + listOf(
            "install_id","device_token","openudid","clientudid","rom","carrier","mcc_mnc",
            "profile-format","openudid-source","apk_first_install_time",
        )).toSet()

        /**
         * 播放地址（`fallback_api` 那一份 `video_info`）的缓存条数与有效期。
         *
         * 为什么需要：推荐页起播时会**同时**有两条路在取同一集——正在起播的这一部
         *（[stream]）和相邻两部的预热（`warmRecommendDetail` → `stream` + 前 1MB）。
         * 两者互相不认识，各自发一次请求，白等一个往返；用户按上下键快速切换时更明显。
         * 地址本身在窗口期内不会变，因此按「集 id + 清晰度 + 档位」缓存。
         *
         * **有效期直接引用 [PlaybackCache.URL_FRESH_MS]（10 分钟）**，不再自己写一个数字。
         * 早先这里是 2 分钟、落盘是 20 分钟：2 分钟一到内存失效，可紧接着又会命中落盘那份
         * 20 分钟的地址——「更短」只是多一次读盘，并没有让起播更安全，反而让人误以为
         * 内存缓存才是生效的那一层。现在三层（内存 / 落盘 / 预热）共用同一个窗口。
         */
        internal const val STREAM_CACHE_MS=PlaybackCache.URL_FRESH_MS
        internal const val STREAM_CACHE_LIMIT=8
        /** 弹幕窗口固定 30 秒一个，与平台的 `next_query_danmaku_list_time` 一致。 */
        internal const val COMMENT_WINDOW_MS=30_000L
        internal const val COMMENT_SOURCE=601
        internal const val SERVER_CHANNEL=1000
        internal const val COMMENT_COUNT=90
        /** 一集最多拉多少个窗口（按 30 秒一窗，40 窗 = 20 分钟）。 */
        internal const val MAX_DANMAKU_WINDOWS=40
        /** 一集最多保留多少条弹幕：低端电视的绘制预算，超出直接截断。 */
        internal const val MAX_DANMAKU=3000
        /** 搜索第 2 页起每次往后扫几页片库目录（一页 24 部）。 */
        /** 片库目录最多翻到第几页。 */
        internal const val MAX_CATALOG_PAGE=100
        /** 窗口之间的最小间隔，避开平台的弹幕频控。 */
        internal const val COMMENT_THROTTLE_MS=180L
        /** 搜索高亮文本可能带 `<em>` 标记；只用于兜底标题，不能让它污染季号解析。 */

        /**
         * 这一档是不是 HEVC（本工程唯一支持的视频编码）。
         *
         * 认得的 HEVC 标签：`bytevc1` / `h265_hvc1` / `hevc` / `h265` / `hvc1` / `hev1`。
         * 明确不是 HEVC 的标签一律剔除：`bytevc2` / `bvc2`（Android 没有解码器）、
         * `h264` / `avc` / `avc1`（不在本工程的播放范围内）。
         *
         * **空标签按 HEVC 收**：老片源大量没有 `codec_type`，实测这些档位的容器编码就是
         * hvc1；剔掉它们等于「老剧全部不能播」。
         */
        fun isHevcCodec(codec: String,key: String = ""): Boolean {
            val label=codec.trim().lowercase()
            if(label.isEmpty()) return true
            return when(label) {
                "bytevc1","h265_hvc1","hevc","h265","hvc1","hev1" -> true
                "bytevc2","bvc2","h264","avc","avc1","h263","vp9","av01","mp4v-es" -> false
                // 认不出的新标签：按 HEVC 处理（平台只下发 HEVC，认不出多半是标签写法变了）。
                else -> key.lowercase().let { !it.contains("bytevc2") && !it.contains("avc") }
            }
        }

        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
        /**
         * 一条资源属于哪一档清晰度。
         *
         * **按短边算**，不是按 `vheight`：竖屏短剧的 `vheight` 是长边（854 / 962 / 1282），
         * 短边在 `vwidth` 里（480 / 540 / 720）。只读 `vheight` 的话竖屏剧每一档都 >480，
         * 「默认 480P」会一路落到最低档、并标成「兼容资源」——用户看到的就是设置没生效。
         *
         * 顺序：`quality_desc`（官方档位文案，如 `480P`）→ `vwidth`/`vheight` 的短边 →
         * `height` → `quality` → 键名（`video_2`）。
         */
        fun qualityOf(row: JSONObject,key: String): Int {
            qualityNumber(row.str("quality_desc")).takeIf { it>0 }?.let { return it }
            val width=row.str("vwidth").toIntOrNull() ?: 0
            val height=row.str("vheight").toIntOrNull() ?: 0
            if(width>0 && height>0) qualityNumber(minOf(width,height).toString()).takeIf { it>0 }?.let { return it }
            if(height>0) qualityNumber(height.toString()).takeIf { it>0 }?.let { return it }
            qualityNumber(row.str("height")).takeIf { it>0 }?.let { return it }
            qualityNumber(row.str("quality")).takeIf { it>0 }?.let { return it }
            return qualityNumber(key)
        }
    }
}
