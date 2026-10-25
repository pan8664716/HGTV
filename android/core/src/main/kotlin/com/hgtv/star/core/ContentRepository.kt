// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
/** 分类 / 榜单接口的 HTTP 状态异常：4xx（429 除外）说明参数或路由有问题，重试没有意义。 */
private class CatalogHttpException(val status: Int, message: String) : IOException(message)
data class Series(
    val id: String,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val badge: String = "",
    val tags: String = "",
    /**
     * 作者 / 版权方（出品方）。
     *
     * 它来自 App 的批量详情接口
     * `GET /reading/bookapi/multi-detail/v?book_id=<id>&book_type=2` 的 `author` 字段
     *（同一条里还有 `publisher` / `source` / `copyright_info`，实测 `author` 最稳定）。
     * 列表接口不带它，因此只有从详情页进来的卡片才显示（见 [ContentRepository.detail]）。
     */
    val author: String = "",
    /**
     * 总集数（0 = 未知）。
     *
     * 卡片的右下角角标统一显示它（见 `PosterCard`）；列表接口大多带 `episode_cnt`，
     * 详情页带 `vid_list.size`，两处都写进这个字段，界面就不用再猜 `badge` 里那串文案。
     */
    val episodeCount: Int = 0,
    /**
     * 第几季（0 = 接口没给）。
     *
     * 列表接口每条都带：`sub_title_list` 里 `data_type == 0` 的那一条就是「第N季」
     * （**没有季号的剧也写「第1季」**，例如「帐中香」→「第1季」）；
     * 详情 / 相关作品那两条接口给的是数字字段 `season_index`。
     * 因此界面优先用它，剧名解析（[SeriesSeasons]）只当兜底。
     */
    val seasonIndex: Int = 0,
    /**
     * 还没上架（预告 / 预约中）。
     *
     * 平台对这类剧**照常给卡片**（搜索、相关作品里都有），但详情接口的 `episode_cnt=0`、
     * `video_list` 为空。旧实现把「没有分集」当异常，点进去就是错误页；
     * 现在按这个标记进「预告详情」：正常显示简介与封面，只是不摆选集、按钮换成「预约」。
     */
    val upcoming: Boolean = false,
    /** 预告文案（`预计11月6日上线` / `即将上线，火速预约` / `预告`）；没有就是空串。 */
    val notice: String = "",
    /**
     * 这一部是不是**竖屏**（接口卡片里的 `vertical` 字段，分类 / 榜单 / 搜索都带）。
     *
     * 接口**没有**「只要横屏」这样的筛选参数：`select_items.vertical` 会被忽略（实测
     * `["0"]` / `["1"]` 回同一批数据），`filter_ids` 传什么都不认。所以只能在客户端按这个
     * 字段筛。默认 false（老数据 / 备份里没有这个字段时按横屏算）。
     */
    val vertical: Boolean = false
) {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover)
        .put("description",description).put("badge",badge).put("tags",tags).put("author",author)
        .put("episodeCount",episodeCount).put("seasonIndex",seasonIndex)
        .put("upcoming",upcoming).put("notice",notice).put("vertical",vertical)
    companion object {
        fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),
            o.str("description"),o.str("badge"),o.str("tags"),o.str("author"),o.optInt("episodeCount"),
            o.optInt("seasonIndex"),o.optBoolean("upcoming",false),o.str("notice"),
            o.optBoolean("vertical",false))
    }
}
/**
 * 详情页的数据；[upcoming] 为真时 [episodes] 一定是空的（还没上架，没有分集）。
 *
 * [reserved] 是「我预约过这一部没有」，来自详情接口的 `online_subscribed`
 *（真机抓包 2026-10-19：预约前后这个字段确实会翻）。界面据此画「预约 / 已预约」。
 */
data class Detail(val series: Series, val episodes: List<String>, val upcoming: Boolean = false, val reserved: Boolean = false)

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
private fun defaultHttpClient(): OkHttpClient = HgtvTls.configure(OkHttpClient.Builder())
    .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
    .connectTimeout(15,TimeUnit.SECONDS)
    .readTimeout(30,TimeUnit.SECONDS)
    .callTimeout(45,TimeUnit.SECONDS)
    // 会话身份（token / cookie / TokenGuard 回写）。四处 `ContentRepository(...)` 只要有默认
    // client 就都带上；显式传 client 的几个调用点（预取、`FavoriteMonitor` 用业务 client 派生）
    // 由它们各自的 `.newBuilder()` 继承，见 `SessionInterceptor` 的说明。
    .addInterceptor(com.hgtv.star.core.session.SessionInterceptor())
    .build()

/**
 * 简介页下方那一栏的内容：同系列各季在前（按季号升序），其余相关作品在后，已去重。
 *
 * **不随详情一起返回**：那一栏要额外发一次 App 请求，而详情页每次进都要开一次，
 * 把它挂在详情上会让「进简介页」变慢。界面在详情上屏之后后台预取（见 `prefetchRelated`）。
 */
data class RelatedWorks(
    val items: List<Series>,
    /** 其中属于**同一部剧**的那些季（按季号升序，含正在看的这一季）：界面据此标注「第 N 季」。 */
    val seasons: List<Series>
)
/**
 * 一条搜索提示词（联想词）。
 *
 * [series] 非空表示这条提示词**本身就是一部剧**（服务端给了 `video_data.series_id`）：
 * 点它可以跳过搜索直接进详情；为空时它只是一个查询词（`common_query` / `title` / `album` /
 * 分类这类），点它就拿 [name] 去搜。
 */
data class SearchSuggestion(
    val name: String,
    val series: Series? = null,
    /** 服务端的 `word_type`：`short_play_name` / `title` / `common_query` / `album` / `category` 等。 */
    val wordType: String = ""
)

data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
/**
 * 推荐流的一页 + 续流游标。
 *
 * 推荐流是**有状态**的：同一个 `session_id` 配上一页返回的 `next_offset` 继续请求，
 * 才会拿到下一批；`session_id` 传空、`offset` 每次归零，服务端就永远回同一套默认列表
 * （实测：连续 8 次 `offset=0&session_id=""`，返回的 20 条完全一致）。
 * 因此客户端必须把这两个值记下来，下次进推荐接着往下取，而不是从头再来。
 */
data class RecommendFeed(val page: CatalogPage, val sessionId: String, val nextOffset: Int)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

/**
 * 搜索接口明确回了「不行」，并带上原因码。
 *
 * 为什么不用一句 `IOException("搜索接口暂不可用")`：那句话把「业务码非 0」「返回格式变了」
 * 「这个类型没有搜索 tab」混成同一件事，面板上就没法判断到底该重试、该换源、还是该等更新。
 */
class SearchApiException(val reason: String,message: String): IOException(message)
/**
 * 这台设备还没在平台注册上（或注册还在路上）。
 *
 * 搜索接口对未注册设备回 `HTTP 200 + 0 字节`：这是「等一会儿就好」而不是「网络坏了」，
 * 因此单独给一个类型，界面据此说「设备正在初始化」，不要让用户去检查路由器。
 */
class DeviceNotRegisteredException(message: String = "设备正在初始化，请稍后重试"): IOException(message)
/**
 * 这个请求需要登录态，而本机还没有可用的凭据（或凭据已失效）。
 *
 * 账号侧接口（收藏 / 历史 / 点赞 / 预约 / 资料）与带登录态的搜索都要求先导入
 * `sessionid`（见 [AccountCredentials]）。未导入时它们**一律不可用**：
 * 平台对匿名设备要么回空响应体、要么把请求降级成匿名账号，两者都会让界面显示
 * 「一条记录都没有」而不是「你还没导入」——因此统一抛这个异常，由界面翻成提示。
 */
class SignInRequiredException(message: String = "需要先导入账号参数（sessionid）"): IOException(message)

class ContentRepository(val http: OkHttpClient = defaultHttpClient(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    /** 账号 / 搜索 / 提示词接口的域名。与弹幕同一套（[appValues] 的设备参数对这几个域名都认）。 */
    private val ACCOUNT_HOST = "api5-normal-sinfonlinea.fqnovel.com"
    /** 播放侧（详情 / 分集 / 播放地址）的域名。设备参数与 [ACCOUNT_HOST] 同一套。 */
    private val VIDEO_HOST = "api5-normal-sinfonlineb.fqnovel.com"
    /** 账号接口空响应体的重试次数。 */
    private val ACCOUNT_RETRIES = 4
    /** 写接口里「短剧」的 `book_type`（App 的 `ReadingBookType.Watch`）。 */
    private val BOOK_TYPE_WATCH = 2
    /** 写接口里「加入追剧」的操作码（App 的 `VideoShelfOperateType.AddShelf`）。 */
    private val VIDEO_SHELF_ADD_SHELF = 0
    /** 写接口里「取消追剧」的操作码（App 的 `VideoShelfOperateType.DelShelf`）。 */
    private val VIDEO_SHELF_DEL_SHELF = 1
    /**
     * 写接口的诊断日志出口。
     *
     * `core` 是纯 Kotlin 模块（没有 Android SDK），所以不能直接 `Log.i`；
     * 界面层启动时把它接到 `android.util.Log`（见 `MainActivity`），不接就是空操作。
     */
    var writeLog: (String) -> Unit = {}
    /**
     * 写接口被风控（`101144 HIT_SHARK_RULE`）时的重试次数与退避间隔（毫秒）。
     *
     * 风控窗口通常是**秒级到十几秒**：实测同一条请求隔 1s 重发还是被挡、隔十几秒就能过，
     * 因此退避必须拉开（早期写的 400/1000/1600ms 全落在同一个窗口里，等于没重试）。
     */
    private val ACCOUNT_WRITE_ATTEMPTS = 4
    private val ACCOUNT_WRITE_BACKOFF = longArrayOf(1_200L,4_000L,12_000L)
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
    private val MULTI_DETAIL_LIMIT = 50
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
    private val HISTORY_LIMIT = 200

    /** 一次删除请求里带多少条（清空历史时按这个分批）。 */
    private val HISTORY_DELETE_BATCH = 50
    /** 清空历史最多试几轮（删完回读校验，还有余量就再来一轮）。 */
    private val HISTORY_CLEAR_ATTEMPTS = 3
    /** 作者缓存：详情页反复进出时不重复请求（一条几十毫秒，但没必要）。 */
    private val authorCache=java.util.LinkedHashMap<String,String>()
    /**
     * 作者缓存上限。
     *
     * 只缓存**取到值**的条目：`multi-detail` 对少数剧返回空 `author`，把空串也缓存下来
     * 会让「之后接口补上了」永远读不到；空结果不缓存，下次进详情再问一次即可。
     */
    private val AUTHOR_CACHE_LIMIT = 300
    /** 账号那七个请求共用的线程池：它们彼此独立，串行发要两三秒。 */
    private val accountPool=java.util.concurrent.Executors.newFixedThreadPool(6) { task ->
        Thread(task,"hgtv-account").apply { isDaemon=true }
    }
    /**
     * 热搜词的种子词（见 [hotSearches]）：都是题材向的泛词，联想接口对它们返回的候选里
     * 混着一批真正的查询词（`30集短剧` / `都市逆袭` / `甜宠` 这类）。
     * 只取前三个：每个种子一次请求，串行约 200ms，再多就只是把同一批词换几个说法。
     */
    private val SUGGEST_SEEDS = listOf("短剧","都市","逆袭","甜宠","战神")
    /**
     * 热搜词的词类型优先级（见 [hotSearches]）。
     *
     * 题材 / 分类这类（`short_play_category` / `category`）读起来最像「大家在搜的题材」，
     * 排最前；`common_query` 是平台自己的搜索建议（`短剧最热榜前十名` 这种）排其次；
     * `title` / `album` 更像是书名 / 专辑名，放最后。
     *
     * 这些字符串就是提示词接口的 `word_type_str`（见 [suggestions]），与是否带 `video_data` 无关。
     */
    private val HOT_WORD_ORDER = listOf("short_play_category","category","common_query","title","album")
    /**
     * 热搜词的并发池：种子词彼此独立，串行五个要 1.8s，并发之后是单次请求的时间。
     * 池子很小且只在取热搜词时用一下，因此按需创建、线程是守护线程（不挡进程退出）。
     */
    private val SUGGEST_POOL = java.util.concurrent.Executors.newFixedThreadPool(SUGGEST_SEEDS.size) { runnable ->
        Thread(runnable,"hgtv-hotword").apply { isDaemon=true }
    }
    /** 热搜词的兜底表：提示接口整体不可用时用（搜索页不能空着）。 */
    private val FALLBACK_HOT = listOf(
        "短剧最热榜前十名","AI短剧","都市逆袭","甜宠","战神归来","穿越剧大全",
        "萌宝助攻","玄幻修仙系列","悬疑","古装","逆袭人生","豪门"
    )
    private data class DanmakuPage(val items: List<Danmaku>,val nextStart: Long,val cursor: String,val hasMore: Boolean,
                                   val raw: List<org.json.JSONObject> = emptyList())
    /**
     * 官网的地址：用**裸域名**，不要加 `www`。
     *
     * 2026-10 起 `www.hongguoduanju.com` 对一切路径都回 301 到裸域名，
     * 而 OkHttp 跟着 301 走之后拿到的那一页已经**不再内联 SSR 数据**（没有 `_ROUTER_DATA`），
     * 旧版解析搜索页 HTML 的路子因此整条失效——用户看到的就是「官网搜索源没结果」。
     * 现在改走官网自己的 JSON 接口（`/api/search/result`、`/api/search/suggestion`），
     * 裸域名直连，既没有 301，也不依赖页面结构。
     */
    private val SITE_HOST = "https://hongguoduanju.com"
    /**
     * 官网搜索**服务端**一页的条数：固定 10 条。
     *
     * `count` / `page_size` / `pageSize` / `size` / `limit` / `num` / `rows` / `per_page` /
     * `pagesize` / `offset` 十种写法实测全部被忽略（2026-10-24 复测：返回的都是同一批 10 条），
     * 因此「一页 100 条」只能在客户端拼——见 [WEB_SEARCH_BATCH]。
     */
    private val WEB_SEARCH_PAGE_SIZE = 10
    /**
     * 官网搜索一个**逻辑页**拼多少个物理页（10 × 10 = 100 条，与 App 搜索的一页同量）。
     *
     * 服务端一页只有 10 条，串行拉 10 页要 6~10 秒，因此按 [WEB_SEARCH_CONCURRENCY] 并发拉。
     */
    private val WEB_SEARCH_BATCH = 10
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
     *（见 [WEB_SEARCH_RETRY_DELAY]）。三次都失败才把这一页算失败。
     */
    private val WEB_SEARCH_RETRY = 2
    /** 单页重试的退避基数（毫秒）：第 1 次重试等 200ms、第 2 次等 400ms。 */
    private val WEB_SEARCH_RETRY_DELAY = 200L
    /**
     * 官网搜索关键词的长度上限：服务端只收 30 个字符
     *（更长的直接回 `400 invalid_query`，一个结果都不给）。
     */
    private val WEB_SEARCH_QUERY_MAX = 30
    /**
     * 官网搜索的已发过名单：官网一次只给 10 条、按 `page` 翻页，
     * 翻到同一批（服务端分页重复）时用它把重复条目摘掉。
     *
     * 与 [searchCursors] 一样按「关键词」分组，只保留最近几组。
     */
    private val webSearchSeen=linkedMapOf<String,MutableSet<String>>()
    /**
     * 本次官网搜索的「部分页失败」摘要，由 [webSearch] 写、[search] 读。
     *
     * 为什么用 ThreadLocal 而不是返回值：`search` 只认 [CatalogPage]（与 App 搜索同一套
     * 签名），失败摘要不属于列表数据。线程内一进一出，不需要额外加锁。
     */
    private val webSearchNote=ThreadLocal<String>()
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    /**
     * 搜索接口还能不能用：空响应体说明这台设备没在服务端注册过（实测所有线路、多组设备参数都一样），
     * 重发多少次都一样。null = 还没试过；判死之后同一进程里不再空跑。
     */
    @Volatile private var appSearchAlive: Boolean?=null
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
            field=value
            // 签名里的 x-medusa 要上报设备档案（机型 / 系统版本 / cdid …），
            // 与凭据一起换掉，免得拿着 A 设备的档案去发 B 设备的写请求。
            // 档案是三层合并的结果（见 [deviceProfile]）：内置 → 本机身份 → 凭据的真机档案。
            signer.updateDevice(deviceProfile())
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
            if(clean["device_id"]!=field["device_id"]) appSearchAlive=null
            field=clean
            signer.updateDevice(deviceProfile())
        }
    /**
     * 共用另一份仓库实例的**设备身份与注册 Cookie**（[deviceIdentity] 与 `registrationCookies`）。
     *
     * 播放预取会另开一个 [ContentRepository]（独立连接池与超时），它自己没注册过设备：
     * 不把这两个值带过去，它签名时只能用内置那份档案（`device_id` 被平台拉黑），
     * 取播放地址必然空响应——那一集永远预取不命中。
     */
    fun inheritDevice(from: ContentRepository) {
        deviceIdentity=from.deviceIdentity
        registrationCookies=from.registrationCookies
    }
    /**
     * 注册用的设备档案（机型 / 系统 / `cdid` / `openudid` …），与 [deviceIdentity] 分开存：
     * 注册要在**还没有** `device_id` 的时候发出去，注册完拿到的那对身份再交给 [deviceIdentity]。
     * 由界面层从本机设置注入（见 `Library.deviceProfile`）。
     */
    @Volatile var registerProfile: Map<String,String> = emptyMap()
        set(value) { field=value.filterValues { it.isNotBlank() }; signer.updateDevice(deviceProfile()) }
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
        private set
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
     * （见 `MainActivity.healDeviceIdentity`）。身份为空时不计——那是真的还没注册。
     */
    /**
     * 全局计数（所有实例共享）：预取用的是另一个 [ContentRepository]，但它与主实例认的是
     * **同一台设备**，服务端拒绝也是同一件事——按实例各记一份，界面层就只看得到主实例那一次。
     */
    val deviceRejected: Int get()=deviceRejectedCount
    /**
     * 丢掉当前这份设备身份：注册结果与下发的 Cookie 一起清。
     *
     * 下一次 [registerDevice] 会拿到一套新的 `device_id` / `iid`，与当前设备档案自洽。
     */
    fun clearDeviceIdentity() {
        deviceIdentity=emptyMap()
        registrationCookies=emptyMap()
        appSearchAlive=null
    }
    /** 记一次「服务端不认这份身份」。身份为空时不计：那是真的还没注册，不是被拒。 */
    private fun noteDeviceRejected() {
        if(deviceIdentity["device_id"].isNullOrBlank()) return
        deviceRejectedCount++
    }

    /**
     * @param profile 这一次注册用的设备档案；默认 [registerProfile]。
     * @param withIdentity 注册请求里要不要带上本机已有的 `device_id` / `iid`。
     *
     * 「换新身份」时界面层会把**候选档案**（还没被平台接受的那一份）传进来，并把这一项设为
     * `false`：平台按 `device_id` 认设备，请求里带着旧身份就只会**原样返回同一对**数字，
     * 新档案配旧身份，还是对不上号，换新等于没换。不带身份才会真的新建一台设备。
     */
    fun registerDevice(profile: Map<String,String>? = null,withIdentity: Boolean = true): DeviceRegister.Registered? {
        val device=LinkedHashMap(profile ?: registerProfile).apply { if(withIdentity) putAll(deviceIdentity) }
        val body=DeviceRegister.payload(device)
        val headers=DeviceRegister.headers(body,device)
        val registered=runCatching {
            val request=Request.Builder().url(DeviceRegister.url(device))
            headers.forEach { (key,value) -> request.header(key,value) }
            request.post(RequestBody.create(MediaType.parse("application/octet-stream"),body))
            RequestScope.execute(http.newCall(request.build())) { response ->
                if(!response.isSuccessful) throw IOException("设备注册失败（HTTP ${response.code()}）")
                val bytes=response.body()?.bytes() ?: throw IOException("设备注册响应为空")
                DeviceRegister.parse(String(bytes,Charsets.UTF_8),response.headers("Set-Cookie"))
                    ?: throw IOException("设备注册被服务端拒绝")
            }
        }.onFailure { lastRegisterError=it }.getOrNull()
        if(registered!=null) {
            lastRegisterError=null
            deviceIdentity=linkedMapOf("device_id" to registered.deviceId,"iid" to registered.installId)
            registrationCookies=LinkedHashMap(registrationCookies).apply {
                putAll(registered.cookies)
                // 旧身份留下的 `install_id` 必须被这次注册的结果顶掉：两个设备号同时出现，
                // 服务端按陌生设备处理（搜索空响应 / 播放 101000），等于白注册一次。
                if(registered.installId.isNotBlank()) put("install_id",registered.installId)
            }
        }
        return registered
    }
    /** 注册下发的 Cookie（`install_id` / `ttreq` / `store-region`），随注册请求一起带上。 */
    @Volatile private var registrationCookies: Map<String,String> = emptyMap()
        set(value) { field=LinkedHashMap(value) }
    /**
     * 当前注册下发的 Cookie 快照（给界面层落盘用）。
     *
     * 为什么要出去：这份 Cookie 原本只活在内存里，**进程一重启就没了**，于是每个新进程的
     * 第一轮请求都是「没有 `install_id` / `ttreq` 的裸设备」，平台按陌生设备处理（风控更严）。
     * 界面层拿到它写进 `native-session-v1`（键 `registered-cookies`），下次启动再用
     * [restoreDeviceCookies] 灌回来。
     */
    fun deviceCookiesSnapshot(): Map<String,String> = LinkedHashMap(registrationCookies)
    /** 把上次落盘的注册 Cookie 灌回来（启动时调一次即可）。 */
    fun restoreDeviceCookies(cookies: Map<String,String>) {
        val clean=cookies.filterValues { it.isNotBlank() }
        if(clean.isEmpty()) return
        registrationCookies=LinkedHashMap(registrationCookies).apply { putAll(clean) }
    }
    /**
     * 签名与设备参数共用的**三层合并档案**（后一层压前一层）：
     *
     * 1. [VendorConstants.device]：内置档案，兜底用；
     * 2. [deviceIdentity]：本机身份（`device_id` / `iid`），来自 [registerDevice] 的注册结果；
     * 3. [credentials] 带来的**真机档案**：`device_type` / `cdid` / `klink_egdi` / `rom_version` …，
     *    写接口（追剧 / 进度）要与凭据一致，因此它优先级最高。
     */
    private fun deviceProfile(): Map<String,String> =
        LinkedHashMap(VendorConstants.device).apply {
            // 真机档案（机型 / 品牌 / 系统 / 分辨率 / 密度 / `rom_version` …）。
            // **这一层是方案 A 的关键**：注册请求与请求签名共用它，因此
            // query 的 `device_type`、注册 body 的 `device_model`、`x-medusa` 里上报的机型
            // 是同一个值——同一次会话里出现两台设备会被风控按异常客户端处理。
            putAll(registerProfile)
            putAll(deviceIdentity)
            credentials?.device?.forEach { (key,value) -> if(value.isNotBlank()) this[key]=value }
        }
    /**
     * 弹幕接口是播放器接口，走 App 域名（与搜索 / 详情同一套设备参数）。
     * 站点有多条线路，单条失败时依次换线，最后一条仍失败才放弃本窗口。
     */
    /**
     * 「相关作品」接口的线路，按实测命中率排序（同一条线路约一半概率回空响应体，
     * 换线重发即可；六条线路轮一圈的成功率接近 1）。
     */
    private val relatedHosts=listOf(
        "api5-normal-hl.fqnovel.com","api5-normal-lf.fqnovel.com","api5-normal-sinfonlinec.fqnovel.com",
        "api5-normal-sinfonlinea.fqnovel.com","api5-normal-lq.fqnovel.com","api5-normal-sinfonlineb.fqnovel.com"
    )
    /** 「相关作品」接口最多试几次（每次换一条线路）：空响应体是常态，不是错误。 */
    private val RELATED_ATTEMPTS=8
    /** 「相关作品」那一栏的 cell 标识：`show_type=547` / `cell_name="相关作品"`。 */
    private val RELATED_CELL_SHOW_TYPE=547
    private val RELATED_CELL_NAME="相关作品"
    /**
     * 相关作品的内存缓存条数：按剧集 ID 索引。
     *
     * 界面自己已经做了「同一部剧不重复预取」（`relatedFor`），这里再兜一层是为了
     * 「看一集 → 回简介 → 再点相关剧集」这条路径：那一栏的内容不会变，
     * 没必要每进一次详情就再打一次接口。
     */
    private val RELATED_CACHE_LIMIT=16
    private val relatedCache=linkedMapOf<String,RelatedWorks>()
    private val commentHosts=listOf("api5-normal-sinfonlinea.fqnovel.com","api5-normal-sinfonlinec.fqnovel.com","api5-normal-sinfonlineb.fqnovel.com","api5-normal-lf.fqnovel.com","api5-normal-lq.fqnovel.com","api5-normal-hl.fqnovel.com")
    /**
     * 播放接口空响应体的原地重发次数。
     *
     * 平台 2026-10-15 起对匿名设备一律回 `200 + 0 字节`；带上登录态后基本稳定，
     * 但仍偶发，因此同一条路径上先重发几次再换下一条——否则会把唯一能用的那条也跳过去。
     */
    private val PLAYBACK_RETRIES=3
    /**
     * 播放模型请求里上报的 `device_level`（设备档位，官方 App 传 3）。
     *
     * **这个值直接决定服务端给哪一套编码的清晰度表**，也就决定了「默认 480P 到底能不能生效」：
     *
     * | `device_level` | `fallback_api` 下发的 360/480/576/720 | 1080 |
     * | --- | --- | --- |
     * | 2 / 3（官方值） | `bytevc2` | `bytevc1`（HEVC） |
     * | 4 / 5 | `bytevc1`（HEVC） | `bytevc1`（HEVC） |
     *
     * 而 `bytevc2` 在 Android 的 MP4 解析器里往往只有音轨、没有画面（见 [stream] 的过滤），
     * 于是 3 档时**低清晰度全被跳过、只剩 1080P**——用户把默认清晰度设成 480P，
     * 第一集照样放 1080P；下一集又恰好是另一套编码，于是「一集 480P、一集 1080P」来回跳。
     * 实测（聚宝仙盆之杂灵根才是真BOSS，99 集）：`device_level=3` 时 53/99 集被顶到 1080P，
     * `device_level=4` 时 99/99 集都能给到 480P。
     *
     * ## 实际取值
     *
     * 界面层按设备解码能力覆盖 [deviceLevel]（见 `PlaybackCapability.deviceLevel`）：
     * 能解 HEVC 报 4，解不了就报官方的 3（报 4 只会拿到一套同样解不了的 HEVC）。
     * 这个字段的默认值因此只是「还没探测出结果时」的兜底。
     */
    @Volatile var deviceLevel: Int = 4
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
     * （见 `PREFETCH_TRIGGER_PERCENT`）之后，按住下键基本不会撞到还没回来的下一页。
     */
    private val CATEGORY_PAGE_SIZE=100

    /**
     * 分类页**换条件后的第一页**只取 20 条。
     *
     * 分类页的筛选条件会频繁切换，每次切都拉 100 条既慢又浪费；20 条正好铺三行多，
     * 足够确认「这次选中的是不是我要的」。用户继续往下翻时恢复 [CATEGORY_PAGE_SIZE]，
     * 第 2 页从 offset=20 开始，不会漏条。
     */
    private val CATEGORY_FIRST_PAGE_SIZE=20

    /**
     * 推荐流一页多少条（与 [CATEGORY_FIRST_PAGE_SIZE] 同档）。
     *
     * 推荐页是「上下换一部」的连续流，一页 20 条已经够刷一阵；取大了只会让进入推荐页
     * 那一下多等几百毫秒。
     */
    private val RECOMMEND_PAGE_SIZE=20

    /** 推荐流游标的上限：超过它就把游标重置回 0（服务端那条流远没有这么深）。 */
    private val RECOMMEND_MAX_OFFSET=2000

    /**
     * **逻辑页**大小：界面上「一页」按这么多条算，不管底层接口一页能给多少。
     *
     * 为什么必须统一成同一个数：界面侧那套「消耗过半就预取下一页」的判据是按比例算的
     * （见 `PREFETCH_TRIGGER_PERCENT`），而它假设「一页 = 一次预取的缓冲量」。
     * 各条路的物理页大小差得很远——
     *
     * | 来源 | 物理页 | 备注 |
     * | --- | --- | --- |
     * | 分类（App 接口） | 100 | `limit` 可放大，已经是一页 100 |
     * | 榜单（App 接口） | 100 | `limit` 给到 100 就真给 100 条，一次就是一整页 |
     * | 搜索（App 接口） | 20 | 一页 20 条，游标翻页 |
     *
     * 于是「按住下键」在分类页够用、在榜单页却每 20 条就撞一次墙（[rank] 里就是这么写的）。
     * 现在统一在**仓库层**聚合成一页 [LOGICAL_PAGE_SIZE] 条，界面侧不必知道底层是几页。
     */
    private val LOGICAL_PAGE_SIZE=100

    /** 首页剧架那一行的榜单条数：一栏只显示 10 条，取 20 条足够，没必要拉一整页 100 条。 */
    private val RANK_API_PAGE_SIZE=20

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
    private val CATEGORY_ATTEMPTS=8
    private val CATEGORY_RETRY_DELAYS=longArrayOf(100L,180L,300L,450L,650L,900L,1200L)
    /**
     * 分类 / 榜单接口用的客户端版本，与改动前手拼 URL 里的那组一致（`73532` / `7.3.5.32`）。
     *
     * 这条接口对版本号不敏感（71332 / 72232 / 73532 / 73932 实测都回完整 JSON），
     * 因此保持原值不动，只把「免签」换成签名。
     */
    private val CATEGORY_VERSION_CODE="73532"
    private val CATEGORY_VERSION_NAME="7.3.5.32"

    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.userAgent(deviceProfile(), video = true)).header("Accept-Language","zh-CN,zh;q=0.9")
        // 账号 / App 接口的公共协议头。参考实现（`passport_client.go` 的 `request`）每一条都带，
        // 早先这里一条都没有：
        // * `X-SS-DP` = `8662`（红果的 aid，网关按它路由）；
        // * `X-Tt-Store-Region` / `-Src` —— 平台按区域分流，注册下发的 `store-region`（如 `cn-gd`）
        //   会覆盖默认的 `cn`。
        request.header("X-SS-DP","8662")
        request.header("X-Tt-Store-Region",registrationCookies["store-region"]?.takeIf { it.isNotBlank() } ?: "cn")
        request.header("X-Tt-Store-Region-Src",registrationCookies["store-region-src"]?.takeIf { it.isNotBlank() } ?: "did")
        request.header("X-XS-From-Web","0")
        request.header("Sdk-Version","2")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        // 方法以 [Signer.Request.method] 为准，不再靠「有没有 body」猜：
        // `search/tab` 只认 GET（POST 该路径是 404），`video_detail` 之类必须 POST。
        signed?.let { signedRequest ->
            when(signedRequest.method) {
                "POST" -> request.post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"),
                    signedRequest.body ?: ByteArray(0)))
                "GET" -> request.get()
                else -> request.method(signedRequest.method, signedRequest.body?.let { payload ->
                    RequestBody.create(MediaType.parse("application/json; charset=utf-8"),payload)
                })
            }
        }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code()}）")
            val body = response.body() ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    /**
     * 协议常量：官方 App 里也是硬编码，不随设备变。
     *
     * 动态化**只针对设备属性**（机型 / 系统 / device_id / cdid …），
     * `aid` / `app_name` 这类跟着官方包走就行。
     */
    private val PROTOCOL_VALUES = linkedMapOf(
        "ac" to "wifi","aid" to "8662","app_name" to "novelread","ssmix" to "a",
        "device_platform" to "android","os" to "android","language" to "zh",
        "compliance_status" to "0","need_personal_recommend" to "1","player_so_load" to "1",
        "host_abi" to "arm64-v8a"
    )
    /**
     * 写接口（追剧 / 进度 / 历史）用的设备参数。
     *
     * 与 [appValues] 的两点不同，都是照官方抓包对齐的：
     * * 客户端版本用 `73932` / `7.3.9.32`（写接口走的是这一版，混用会被按旧客户端处理）；
     * * 叠加导入凭据时带来的**真机档案**（`cdid` / `klink_egdi` / `rom_version` 这些
     *   内置档案里根本没有，而官方请求每条都带）。
     */
    private fun writeValues(): LinkedHashMap<String,String> = deviceValues("73932","7.3.9.32")
    /** 搜索 / 弹幕这类 App 接口共用的设备参数：与播放器版本对齐，避免平台按旧版本拒绝。 */
    private fun appValues(): LinkedHashMap<String,String> = deviceValues("72232","7.2.2.32")
    /**
     * 设备参数的分层组装（后一层压前一层，合并规则见 [deviceProfile]）：
     *
     * 1. [VendorConstants.device]：内置档案，兜底用；
     * 2. [deviceIdentity]：本机身份（`device_id` / `iid`），来自 [registerDevice] 的注册结果；
     * 3. 导入凭据带来的**真机档案**：`device_type` / `device_brand` / `os_version` /
     *    `cdid` / `klink_egdi` / `rom_version` … 这些内置档案里没有或对不上的字段；
     * 3. [PROTOCOL_VALUES] 与客户端版本：协议常量，官方 App 也硬编码，
     *    放在最后一层保证 `aid` / `app_name` 不会被档案里的同名值冲掉。
     */
    private fun deviceValues(versionCode: String, versionName: String): LinkedHashMap<String,String> {
        val values=LinkedHashMap(deviceProfile())
        values.putAll(PROTOCOL_VALUES)
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[key]=versionCode
        values["version_name"]=versionName
        return values
    }
    /**
     * 给 App 接口补上身份。两件事，分开做：
     *
     * 1. **设备 Cookie**：`registerDevice` 下发的 `install_id` / `ttreq` / `store-region`
     *    每个请求都要带——平台靠它们把请求认到那台已注册设备上（少了这一步，
     *    即使 `device_id` 已在 query 里，服务端也可能不认）。
     * 2. **登录态**（可选）：账号凭据的整段 Cookie，或历史遗留的 `sessionToken`。
     *    两者都没有就只带设备 Cookie——搜索与播放对匿名设备一样放行。
     */
    private fun Signer.Request.withSession(): Signer.Request {
        // 账号凭据优先：它带的是整套 Cookie（App 接口既要 Cookie 又要与之一致的设备参数）。
        //
        // **token 与 cookie 必须分头送**：`x-tt-token` 只放 token（几十到三百多字符的 hex），
        // `Cookie` 只放 cookie。早先这里把整段 cookie 串（800+ 字符）也塞进了 `x-tt-token`，
        // 后果是播放取地址接口回 `200 + 0 字节`（那条接口对 token 长度极敏感，≥99 字符判死），
        // 账号写接口的签名头也与 Cookie 里的设备对不上。
        // 导入 `cookieStore.xml` 时 token 为空，此时**不发** `x-tt-token`——会话靠心跳
        // 的响应头把 token 换回来（见 `SessionKeeper`），换到之后这里自然就带上了。
        val account=credentials?.takeIf { it.configured }
        if(account!=null) {
            val extra=linkedMapOf<String,String>()
            // **现读会话层**：服务端可以在任意响应头里轮换 token、在任意响应里续期
            // `sid_guard`。凭据对象是登录那一刻的快照，照它发就会在轮换之后用旧 token
            // 一直请求下去——这正是「用一两天突然掉」的成因之一。
            val live=com.hgtv.star.core.session.SessionBridge.state
            val liveCookie=live?.cookieHeader.orEmpty()
            // **会话层只在与这份凭据同一次会话时才优先**。
            //
            // 早先无条件 `live ?: account`，于是「刚扫码拿到新会话」会被 SP 里上一份
            // （已过期）的 `sessionid` 顶掉，平台按匿名设备返回临时账号——界面报的正是
            // 「这份登录态无效：平台把它当成了匿名设备」。判据用 `sessionid`：
            // 它才是会话主键，`sid_guard` / `uid_tt` 都会随续期变化，不能拿来比对。
            val liveSid=com.hgtv.star.core.session.CookieRecord.value(liveCookie,"sessionid")
            val accountSid=com.hgtv.star.core.session.CookieRecord.value(account.cookie,"sessionid")
            val sameSession=liveSid.isNotBlank() && liveSid==accountSid
            val token=if(sameSession) live?.token.orEmpty() else ""
            (token.takeIf { it.isNotBlank() } ?: account.token.takeIf { it.isNotBlank() })
                ?.let { extra["x-tt-token"]=it }
            val cookie=if(sameSession) liveCookie else account.cookie
            if(cookie.isNotBlank()) extra["Cookie"]=cookie
            return copy(headers=headers+extra)
        }
        val deviceCookie=registrationCookies.entries.joinToString("; ") { (name,value) -> "$name=$value" }
        val token=sessionToken?.trim().orEmpty()
        if(token.isEmpty()) {
            return if(deviceCookie.isEmpty()) this else copy(headers=headers+linkedMapOf("Cookie" to deviceCookie))
        }
        val extra=linkedMapOf("x-tt-token" to token)
        // 只有整段 Cookie（含 `=`）才同时按 `Cookie` 头发一次：纯 token 不是 Cookie 串，
        // 混进 `Cookie` 会让服务端按 Cookie 解析。设备 Cookie 是另一回事，照常附上。
        when {
            token.contains('=') -> extra["Cookie"]=if(deviceCookie.isEmpty()) token else "$token; $deviceCookie"
            deviceCookie.isNotEmpty() -> extra["Cookie"]=deviceCookie
        }
        return copy(headers=headers+extra)
    }
    /** 有没有可用的登录态：搜索要不要优先走 App 接口、个人信息页要不要联网，都看它。 */
    val signedIn: Boolean get()=credentials?.configured==true || !sessionToken.isNullOrBlank()
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    /**
     * 榜单页：四个榜单（[RankBoard]）共用这一套取数，只有分类与排序不同。
     *
     * 首页的四个「热播…」剧架与排行榜页的四个榜单读的是**同一份数据**，
     * 因此剧架行末的「查看全部」直接进对应的榜单页，不再另做一套「热门集合页」。
     *
     * 数据取自 **App 分类接口按 [RankBoard.sort] 排**（参数与 [categoryApi] 同一套）：
     * 三个热播榜按最高热度（`hot_score`）取该分类里热度最高的一串，
     * 「上新」按最新上架（`online_time`）取全站综合，名次与热度都由服务端给——
     * 名次取 `recommend_info.rank`，热度取 `recommend_reason_list.sub_title_list` 里 `data_type==27`
     * 的那一段（形如「9014万」）。分类见 [RankBoard.genre]，`null` 表示不筛分类（全站综合）。
     */
    private fun rankApiPage(page: Int, board: RankBoard, size: Int): CatalogPage {
        val body=JSONObject()
            .put("filter_ids","")
            .put("req_scene","default")
            .put("offset",(page-1)*size)
            .put("need_selector_panel",false)
            .put("limit",size)
            .put("select_items",JSONObject()
                // 不筛分类时给一个空数组（与不传这一项等价，实测返回全站综合榜）。
                .put("genre",JSONArray().apply { board.genre?.let { put(it) } })
                .put("sort",JSONArray().put(board.sort)))
            .put("session_id","")
            .put("req_type","only_content")
            .put("client_req_type",3)
        val data=JSONObject(categoryApiRaw(body))
        if(data.optInt("code",-1)!=0) throw IOException("${board.label}榜接口暂不可用")
        val payload=data.optJSONObject("data") ?: throw IOException("${board.label}榜接口结构已变化")
        val positions=linkedMapOf<String,RankPosition>()
        // 卡片字段与分类页是同一套（`apiCard`：title / cover / video_desc / copyright / episode_cnt…），
        // 因此这里直接复用，只有名次与热度要多读两处。
        val rows=payload.optJSONArray("video_data")?.objects().orEmpty()
        // 名次要换算：这一页的「页内序号」加上前面已经排过的条数，才是榜单上的名次。
        val base=(page-1)*size
        val items=valid(rows.mapIndexed { index, row ->
            apiCard(row).also { series ->
                // 「上新」是时间序，不画名次（页内序号当名次会误导）。
                if(board.ranked) apiRank(row,base,index)?.let { positions[series.id]=it }
            }
        })
        if(items.isEmpty()) throw IOException("${board.label}榜暂无数据")
        // 接口只给 `has_more`，**没有总数**：因此不编造总量，只报「已看到第几页」。
        // 界面「N / M 部」据此显示（见 `moreCountText`），加载到底自然变成「已显示全部 N 部」。
        // 更新时间这一条接口没有，留空即由界面退回 [RankBoard.source] 那行来源文案。
        return CatalogPage(items,payload.optBoolean("has_more",false),ComicRanking(positions,page,""))
    }

    /**
     * 榜单一条里的名次与热度。
     *
     * **名次要换算**：接口的 `recommend_info.rank` 是**这一页内**的 1-based 序号，不是全站名次
     * （实测 `offset=20` 时第一名的 `rank` 又是 1，而它确实是第 21 名）。
     * 榜单页的名次是累计的，因此这里用 `base + 页内序号` 换算成榜单上的名次
     *（`base` 是这一页之前已经排过的条数）；
     * 服务端没给 `rank` 时退回「第几条」（这一串本来就是按热度排的，序号即名次）。
     *
     * `recommend_info` 有时是对象、有时是 JSON 字符串（同一次响应里两种都能见到），
     * `recommend_reason_list` 一直是字符串，因此两者都统一「能当对象就用、否则当字符串解」。
     *
     * 热度是服务端自己那串「9014万」：榜单本来就是按它排的，界面照它显示，不重排、不换算。
     * 两处都读不到就返回 null（卡片第二行由界面退回榜单名）。
     */
    private fun apiRank(row: JSONObject, base: Int, index: Int): RankPosition? {
        val info=row.optJSONObject("recommend_info")
            ?: runCatching { JSONObject(row.str("recommend_info")) }.getOrNull()
        val local=info?.optString("rank")?.toIntOrNull()?.takeIf { it>0 }
        val heat=runCatching { JSONObject(row.str("recommend_reason_list")) }.getOrNull()
            ?.optJSONArray("sub_title_list")?.objects()
            ?.firstOrNull { it.optInt("data_type",-1)==27 }?.str("content").orEmpty()
        if(local==null && heat.isEmpty()) return null
        return RankPosition(base+(local ?: (index+1)),heat)
    }

    /**
     * 榜单的**一个小页**（[RANK_API_PAGE_SIZE] 条）。
     *
     * 首页那四个剧架每栏只显示 10 条，用不着整页 100 条——[homeFeed] 走这一条。
     */
    fun rankRaw(page: Int = 1, board: RankBoard = RankBoard.COMIC): CatalogPage {
        require(page in 1..100)
        return rankApiPage(page,board,RANK_API_PAGE_SIZE)
    }

    /**
     * 榜单的**一个逻辑页**（[LOGICAL_PAGE_SIZE] 条）。
     *
     * 榜单页（`TAB_RANK`）走这一条：接口 `limit` 给到 100 就真给 100 条，一次请求就是一整页。
     */
    fun rank(page: Int = 1, board: RankBoard = RankBoard.COMIC): CatalogPage {
        require(page in 1..100)
        return rankApiPage(page,board,LOGICAL_PAGE_SIZE)
    }
    /**
     * 首页四个「热播…」剧架的第 1 页：四个榜单彼此独立，串行要等四份 RTT，因此并发取。
     *
     * 单个榜单失败只让那一栏不出现（首页其余内容照常上屏），四个都失败才抛错。
     * 取消（切页 / 换标签）沿着 [RequestScope] 传进工作线程，在途的四个请求会一起收手。
     */
    fun homeFeed(): Map<RankBoard,CatalogPage> {
        val scope=RequestScope.current()
        val pool=java.util.concurrent.Executors.newFixedThreadPool(RankBoard.entries.size) { task ->
            Thread(task,"hgtv-homefeed").apply { isDaemon=true }
        }
        return try {
            val futures=RankBoard.entries.map { board ->
                // 首页每栏只显示 10 条，取 [RANK_API_PAGE_SIZE] 条的小页就够——不要走 [rank] 的逻辑页（一次 100 条）。
                board to pool.submit(java.util.concurrent.Callable { RequestScope.withScope(scope) { runCatching { rankRaw(1,board) }.getOrNull() } })
            }
            val result=linkedMapOf<RankBoard,CatalogPage>()
            futures.forEach { (board,future) -> future.get()?.let { page -> result[board]=page } }
            if(result.values.none { it.items.isNotEmpty() }) throw IOException("热门榜暂不可用")
            result
        } finally {
            pool.shutdownNow()
        }
    }
    /**
     * 分类目录的一页（`path` 来自 [CategoryCatalog]，例如 `comic-drama/fantasy`）。
     *
     * 走 App 分类接口 [categoryApi]，一次给 [CATEGORY_PAGE_SIZE] 条、
     * `has_more` 可一直往下翻；分类页**没有名次与热度**，所以 [CatalogPage.ranking] 恒为 null。
     */
    fun category(path: String, page: Int = 1): CatalogPage {
        require(path.isNotBlank() && path.length<=80 && page in 1..MAX_CATALOG_PAGE)
        return categoryApi(CategoryCatalog.selectionOf(path),page,needPanel=false).page
    }

    /**
     * 分类页：按 [selection] 取一页内容；[needPanel] 为真时**同一次请求**
     * 顺带把整份筛选面板（`selector_rows`）带回来。
     *
     * 这就是「一次拿到所有筛选条件」的入口：`need_selector_panel=true` +
     * `req_type=default` 的响应里同时有 `video_data` 与 `selector_rows`，
     * 面板包含体裁 / 主题 / 设定 / 背景 / 推荐 / 受众 / 时间 / 状态全部行。
     *
     * 注意：主题 / 设定 / 背景 / 状态会随体裁变化（`genre` 行带
     * `refresh_other_selector_row=true`），因此换体裁后要再取一次面板。
     */
    fun categoryFeed(selection: CategorySelection, page: Int = 1, needPanel: Boolean = false): CategoryFeed {
        require(page in 1..MAX_CATALOG_PAGE)
        return categoryApi(selection,page,needPanel)
    }

    /**
     * 推荐流的一页：**带续流游标**地取分类接口的「全部推荐」。
     *
     * 与 [categoryFeed] 的区别只有两点：
     * * `session_id` 用调用方给的值（首次为空，之后用上一页返回的那个）；
     * * `offset` 用调用方给的值（首次 0，之后用上一页返回的 `next_offset`）。
     *
     * 这两点决定了「每次进推荐是不是同一套列表」：传空 + 0 永远回同一批。
     * `sort` 不传 = 服务端的推荐序（不是热播榜）；`genre` 由 [selection] 决定（推荐页只取漫剧）。
     */
    fun recommendFeed(selection: CategorySelection, sessionId: String, offset: Int, limit: Int = RECOMMEND_PAGE_SIZE): RecommendFeed {
        require(offset in 0..RECOMMEND_MAX_OFFSET)
        val body=JSONObject()
            .put("filter_ids","")
            .put("req_scene","default")
            .put("offset",offset)
            .put("need_selector_panel",false)
            .put("limit",limit)
            .put("select_items",categorySelectItems(selection,null))
            .put("session_id",sessionId)
            .put("req_type","only_content")
            .put("client_req_type",3)
        val data=JSONObject(categoryApiRaw(body))
        if(data.optInt("code",-1)!=0) throw IOException("推荐接口暂不可用")
        val payload=data.optJSONObject("data") ?: throw IOException("推荐接口结构已变化")
        val items=valid((payload.optJSONArray("video_data") ?: JSONArray()).objects().map(::apiCard))
        // `next_offset` 偶尔缺失 / 不往前走：用「本页实际条数」兜底，保证游标一定前进，
        // 否则下一次还是同一个位置，用户看到的就是「又一套一模一样的列表」。
        val reported=payload.optInt("next_offset",-1)
        val next=when {
            reported>offset -> reported
            items.isNotEmpty() -> offset+items.size
            else -> offset+limit
        }.coerceAtMost(RECOMMEND_MAX_OFFSET)
        val more=payload.optBoolean("has_more",items.isNotEmpty())
        return RecommendFeed(CatalogPage(items,more),payload.optString("session_id").ifBlank { sessionId },next)
    }

    /**
     * 分类接口：`POST /reading/distribution/category/landpage/v/`。
     *
     * **2026-10-21 起要签名**（早先只带 User-Agent / Accept / Content-Type 三个头就能用，
     * 现在缺签名头一律回空响应，见 [categoryApiRaw]）。不需要登录态。参数要点：
     *
     * * `select_items` 的每个值都必须是**数组**：给字符串会回
     *   `HTTP 400 req.SelectItems[genre]: expected array, actual value: short_play (string)`；
     * * `filter_ids` 必须是**字符串**（给 `[]` 会回 `expected string, actual value: []`）；
     * * 翻页靠 `offset`（0 / 60 / 120…）与 `limit`（见 [CATEGORY_PAGE_SIZE]，
     *   实测 `limit` 可以放大到 200，服务端会照数返回）；
     * * `req_type` 用 `only_content`（要筛选面板时才用 `default` + `need_selector_panel=true`）。
     *
     * [path] 来自 [CategoryCatalog]：大类段映射成接口的 `genre`
     *（`comic_series` / `short_play` / `ai_series`），小类段就是接口的主题标识（`cate_*`）。
     */
    private fun categoryApi(selection: CategorySelection, page: Int, needPanel: Boolean): CategoryFeed {
        // 「全部推荐」是服务端的一条**推荐流**：实测到第 100 条就 `has_more=false`，
        // 且第二页开始会混进重复条目；照它一直翻，用户看到的就是「只剩这么几部」。
        // 因此前两页先按推荐流取完这 100 条，第 3 页起自动接上 `hot_score` 继续翻，
        // 已显示的条目由界面按 id 去重（见 `applyNextPage`）。
        // 推荐流只适合「什么细分条件都没选」的默认页；带了主题 / 设定等条件时它经常回 0，
        // 这时直接从第 1 页就走 hot_score，保证细分类目也有完整列表。
        val recommendation=selection.sort==null && selection.hasNoDetailFilters()
        val fallback=selection.sort==null && (!recommendation || page>=3)
        val select=categorySelectItems(selection,if(fallback) "hot_score" else selection.sort)
        val body=JSONObject()
            .put("filter_ids","")
            .put("req_scene","default")
            .put("offset",categoryOffset(selection,page))
            .put("need_selector_panel",needPanel)
            .put("limit",categoryLimit(page))
            .put("select_items",select)
            .put("session_id","")
            .put("req_type",if(needPanel) "default" else "only_content")
            .put("client_req_type",3)
        val data=JSONObject(categoryApiRaw(body))
        if(data.optInt("code",-1)!=0) throw IOException("分类接口暂不可用")
        val payload=data.optJSONObject("data") ?: throw IOException("分类接口结构已变化")
        val items=valid((payload.optJSONArray("video_data") ?: JSONArray()).objects().map(::apiCard))
        val serverMore=payload.optBoolean("has_more",false)
        // 推荐流第 2 页会回 `has_more=false`，但后面还要接 hot_score，所以这里补一页。
        val more=(serverMore || (recommendation && page<=2)) && page<MAX_CATALOG_PAGE
        val panel=if(needPanel) parseCategoryPanel(payload.optJSONArray("selector_rows")) else null
        return CategoryFeed(CatalogPage(items,more),panel)
    }
    /** 分类页的 offset：第一页 20 条，之后每页 100 条（第 2 页从 20 开始）。 */
    private fun categoryOffset(selection: CategorySelection,page: Int): Int = when {
        // 「全部推荐」：第 1 页 20 条、第 2 页把推荐流剩下的约 80 条取完，
        // 第 3 页起改用 `hot_score`，从 0 重新铺（界面负责去重）。
        selection.sort==null && selection.hasNoDetailFilters() -> when {
            page<=1 -> 0
            page==2 -> CATEGORY_FIRST_PAGE_SIZE
            else -> (page-3)*CATEGORY_PAGE_SIZE
        }
        page<=1 -> 0
        else -> CATEGORY_FIRST_PAGE_SIZE+(page-2)*CATEGORY_PAGE_SIZE
    }
    private fun categoryLimit(page: Int): Int =
        if(page<=1) CATEGORY_FIRST_PAGE_SIZE else CATEGORY_PAGE_SIZE
    /**
     * `select_items`：每个维度都是数组，未选中的维度不传（与接口的「全部」等价）。
     * `genre` 始终传一个值：分类页与官方 App 一样始终停在一个体裁上。
     */
    private fun categorySelectItems(selection: CategorySelection,sort: String?): JSONObject {
        // 「全部体裁」= 空数组（与不传等价）；其余体裁传它自己的标识。
        val select=JSONObject().put("genre",JSONArray().apply { selection.genre?.let { put(it) } })
        selection.theme?.let { select.put(CategorySelection.TYPE_THEME,JSONArray().put(it)) }
        selection.role?.let { select.put(CategorySelection.TYPE_ROLE,JSONArray().put(it)) }
        selection.epoch?.let { select.put(CategorySelection.TYPE_EPOCH,JSONArray().put(it)) }
        selection.gender?.let { select.put(CategorySelection.TYPE_GENDER,JSONArray().put(it)) }
        selection.onlineTime?.let { select.put(CategorySelection.TYPE_ONLINE_TIME,JSONArray().put(it)) }
        selection.creationStatus?.let { select.put(CategorySelection.TYPE_CREATION_STATUS,JSONArray().put(it)) }
        // 排序为空 = 「全部推荐」：前两页不传 `sort`（服务端回它自己的推荐序），
        // 第 3 页起由调用方传入 `hot_score` 续翻。
        sort?.let { select.put(CategorySelection.TYPE_SORT,JSONArray().put(it)) }
        return select
    }
    /** 把 `selector_rows` 原样解析成界面用的面板；没有面板时返回 null（内容仍然可用）。 */
    private fun parseCategoryPanel(rows: JSONArray?): CategoryPanel? {
        val parsed=rows?.objects().orEmpty().mapNotNull { row ->
            val type=row.str("type")
            val options=row.optJSONArray("items")?.objects().orEmpty().mapNotNull { item ->
                val id=item.str("selector_item_id")
                val label=item.str("show_name")
                if(id.isBlank()||label.isBlank()) null else CategoryFilterOption(id,label)
            }
            if(type.isBlank()||options.isEmpty()) null
            else CategoryFilterRow(type,row.str("row_name"),options)
        }
        return if(parsed.isEmpty()) null else CategoryPanel(parsed)
    }
    private fun categoryApiRaw(body: JSONObject): String {
        val payload=body.toString().toByteArray()
        var problem: IOException=IOException("分类接口暂无响应")
        for(attempt in 0 until CATEGORY_ATTEMPTS) {
            if(attempt>0) {
                try {
                    Thread.sleep(CATEGORY_RETRY_DELAYS[attempt-1])
                } catch(cancelled: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("请求已取消",cancelled)
                }
            }
            try {
                // 每次重试都重签：`ts` / `_rticket` 是签名的一部分，复用旧签名会被判过期。
                // 分类 / 榜单这条只认 POST + JSON body（GET 该路径回 404）。
                val signed=signer.sign(categoryApiUrl(),deviceValues(CATEGORY_VERSION_CODE,CATEGORY_VERSION_NAME),payload,method="POST").withSession()
                val request=Request.Builder().url(signed.url)
                signed.headers.forEach { (key,value) -> if(key!="User-Agent" && key!="Accept") request.header(key,value) }
                // UA / Accept 保持与改动前一致：UA 用 App 那条（签名默认按「有 body」选视频 UA）。
                request.header("User-Agent",VendorConstants.userAgent(deviceProfile(), video = false))
                request.header("Accept","application/json; charset=utf-8,application/x-protobuf")
                request.post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"),payload))
                val raw=RequestScope.execute(http.newCall(request.build())) { response ->
                    if(!response.isSuccessful) {
                        throw CatalogHttpException(response.code(),"分类接口失败（HTTP ${response.code()}）")
                    }
                    response.body()?.string().orEmpty()
                }
                if(raw.isNotBlank()) return raw
                problem=IOException("分类接口暂无响应")
            } catch(failure: IOException) {
                // 只重发「可能自己好」的那两类：空响应体（上面那个分支）与 5xx / 429。
                // 取消、明确的 4xx、以及连接层的硬错误（超时 / 断网）立刻抛出——
                // 重发只是把同一次失败重复 8 遍，界面白等 4 秒还是一样的结果。
                if(Thread.currentThread().isInterrupted || failure.message=="请求已取消") throw failure
                if(failure !is CatalogHttpException) throw failure
                if(failure.status in 400..499 && failure.status!=429) throw failure
                problem=failure
            }
        }
        throw problem
    }

    private fun categoryApiUrl(): String =
        "https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/"
    /**
     * 分类接口的卡片：字段名与 SSR 那套不同（`title` / `cover` / `video_desc`）。
     *
     * **出品方这一条接口自带**（`copyright`），与详情页 `multi-detail` 的 `author` 同源，
     * 因此分类页的卡片不必再为作者发一次请求（见 [publisher]）。
     */
    private fun apiCard(item: JSONObject): Series {
        val count=item.optInt("episode_cnt",0)
        val tags=tagsOf(item)
        return Series(item.str("series_id"),item.str("title"),cleanUrl(item.str("cover")),
            item.str("video_desc"),EpisodeLabel.of(count),tags,
            author=publisher(item.str("copyright")),episodeCount=count,seasonIndex=seasonOf(item),
            vertical=item.optBoolean("vertical",false))
    }

    /**
     * 接口给的季号（0 = 没给）。
     *
     * 列表接口（分类 / 榜单 / 搜索 / 提示词）每条都带 `sub_title_list`：其中 `data_type == 0`
     * 的那一条就是「第N季」——**没有季号的剧也写「第1季」**，所以它比从剧名里猜更准
     * （剧名可能整个不提季号，例如「帐中香」）。`recommend_reason_list` 是同一份数据的字符串副本。
     *
     * 详情 / 相关作品那两条接口不给 `sub_title_list`，给的是数字字段 `season_index`。
     */
    private fun seasonOf(item: JSONObject): Int {
        fun fromRows(rows: JSONArray?): Int {
            rows?.objects()?.forEach { row ->
                val hit=SEASON_TEXT.find(row.str("content"))
                if(hit!=null) return hit.groupValues[1].toIntOrNull() ?: 0
            }
            return 0
        }
        fromRows(item.optJSONArray("sub_title_list")).takeIf { it>0 }?.let { return it }
        runCatching { JSONObject(item.str("recommend_reason_list")).optJSONArray("sub_title_list") }
            .getOrNull()?.let { rows -> fromRows(rows).takeIf { it>0 }?.let { return it } }
        return item.optInt("season_index",0)
    }

    /**
     * 出品方：把接口给的 `copyright` 归一成能直接显示的名字。
     *
     * 短剧那一栏里有一部分是**占位串**（`短剧版权3493645060`、`短剧版权5146586586`），
     * 那是平台没填版权方时自动生成的编号，显示出来只会让卡片第二行变成一串数字。
     * 这类一律当空值处理：卡片那一行收起，由 [authorsOf] 的详情兜底去补。
     */
    internal fun publisher(raw: String): String {
        val text=raw.trim()
        if(text.isEmpty()) return ""
        return if(Regex("^短剧版权[0-9]{6,}$").matches(text)) "" else text
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
    internal fun tagsOf(o: JSONObject, key: String = "category_schema"): String {
        val rows = runCatching { JSONArray(o.str(key)) }.getOrNull()
            ?: o.optJSONArray(key)
        val names = rows?.objects()?.mapNotNull { it.str("name").ifBlank { null } }.orEmpty()
        return names.take(5).joinToString(" · ")
            // 老接口 / 个别 cell 只给一个 `category` 字符串，也认。
            .ifEmpty { o.str("category") }
            // 官网搜索那一套字段。
            .ifEmpty { o.optJSONArray("category_list")?.objects()?.mapNotNull { it.str("name").ifBlank { null } }
                ?.take(5)?.joinToString(" · ").orEmpty() }
    }
    /**
     * 搜索提示词（联想词）：App 搜索框用的那个接口。
     *
     * ```
     * GET https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/suggest/v?query={关键词}
     * ```
     *
     * 需要设备参数与签名（[appValues] + [Signer]），不需要登录态。空关键词不发请求。
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
    fun webSuggestions(keyword: String, count: Int = 10): List<SearchSuggestion> {
        val clean=keyword.trim().take(WEB_SEARCH_QUERY_MAX)
        if(clean.isEmpty()) return emptyList()
        val wanted=count.coerceIn(1,20)
        val url="$SITE_HOST/api/search/suggestion?query=${Signer.encode(clean)}&count=$wanted"
        val raw=runCatching { siteText(url) }.getOrNull().orEmpty()
        if(raw.isBlank()) return emptyList()
        // 新接口的字段叫 `suggestionList`；老接口（`/incent_resource/suggestion`）叫 `suggest_list`。
        // 两个都认：接口再改名时不至于整行联想词直接消失。
        val rows=runCatching {
            val data=JSONObject(raw)
            (data.optJSONArray("suggestionList") ?: data.optJSONArray("suggest_list"))?.objects().orEmpty()
        }.getOrDefault(emptyList())
        return rows.mapNotNull { row ->
            val name=row.str("name").ifEmpty { row.optJSONArray("display_words")?.optString(0).orEmpty() }.trim()
            if(name.isEmpty()) return@mapNotNull null
            val video=row.optJSONObject("video_data")?.takeIf { it.length()>0 }
            val series=video?.let { webCard(it) }?.takeIf { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }
            SearchSuggestion(name,series,row.str("word_type"))
        }.distinctBy { it.name }.take(wanted)
    }
    private fun appSuggestions(keyword: String, count: Int = 10): List<SearchSuggestion> {
        val clean=keyword.trim()
        if(clean.isEmpty()) return emptyList()
        val wanted=count.coerceIn(1,20)
        val values=appValues()
        values["query"]=clean
        val signed=signer.sign("https://$ACCOUNT_HOST/reading/bookapi/search/suggest/v",values)
        val raw=text(signed.url,signed.withSession())
        if(raw.isBlank()) throw IOException("提示词暂不可用")
        val data=JSONObject(raw).optJSONObject("data") ?: return emptyList()
        val rows=data.optJSONArray("query_result_v2")?.objects() ?: return emptyList()
        return rows.mapNotNull { row ->
            val name=row.str("name").ifEmpty { row.optJSONArray("display_words")?.optString(0).orEmpty() }.trim()
            if(name.isEmpty()) return@mapNotNull null
            // `video_data` 里没有 `series_id` 的那些（title / common_query 这类）不算剧集，只能当查询词。
            val video=row.optJSONObject("video_data")?.takeIf { it.length()>0 }
            val series=video?.let { apiCard(it) }?.takeIf { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }
            SearchSuggestion(name,series,row.str("word_type_str"))
        }.distinctBy { it.name }.take(wanted)
    }
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
    fun hotSearches(limit: Int = 12, source: SearchSource = SearchSource.APP): List<String> {
        val wanted=limit.coerceIn(1,30)
        // 种子词**并发**问（串行五个要 1.8s，并发后是单次请求的时间）。
        // API 19 没有 CompletableFuture（API 24），用普通的 Future 提交到同一个池子里等齐。
        val tasks=SUGGEST_SEEDS.map { seed -> SUGGEST_POOL.submit(java.util.concurrent.Callable { suggestionsPool(seed,source) }) }
        val pools=tasks.map { task -> runCatching { task.get() }.getOrDefault(emptyList()) }
        // 分层 + 层内轮询：
        // - 先按词类型分层（题材 / 分类 > 平台搜索建议 > 书名）；
        // - 同一层里**按种子轮询**交替取——一个种子单独就能填满整张表，
        //   顺着一个种子取的话整排热搜词都是同一个题材的变体（实测全是「…短剧」）。
        val seen=linkedSetOf<String>()
        HOT_WORD_ORDER.forEach { type ->
            if(seen.size>=wanted) return@forEach
            val lists=pools.map { pool -> pool.filter { it.second==type }.map { it.first } }
            var round=0
            while(seen.size<wanted && lists.any { it.size>round }) {
                lists.forEach { list -> list.getOrNull(round)?.let { if(seen.size<wanted) seen += it } }
                round++
            }
        }
        return if(seen.isEmpty()) FALLBACK_HOT.take(wanted) else seen.toList()
    }
    /** 一个种子词的候选：`(词, word_type)`，过滤掉剧名、过长过短的与重复的。 */
    private fun suggestionsPool(seed: String,source: SearchSource): List<Pair<String,String>> =
        runCatching { suggestions(seed,20,source) }.getOrDefault(emptyList())
            .filter { it.series==null && it.name.length in 2..12 }
            .map { it.name to it.wordType }
            .distinctBy { it.first }
    /**
     * 界面上的搜索入口。
     *
     * [source] 决定走哪一套：App 接口（默认，结果全、游标翻页）或官网 SSR 搜索页
     * （免签名免登录，按 `page` 翻）。两套都按**逻辑页**给 100 条：官网那边由
     * [webSearch] 把 10 个物理页拼起来，因此界面侧翻页手感一致，
     * 也只认 [CatalogPage]，不需要知道数据是从哪来的。
     */
    fun search(keyword: String, page: Int = 1, source: SearchSource = SearchSource.APP): CatalogPage {
        // 每次搜索都在这里留一份现场（见 [SearchDiag]）：失败时面板给的是**判读结论**，
        // 不是页面上那句「没有找到相关剧集」。成功也要记——「0 条」只有配上
        // 「接口真的回答了」才等于「确实没有匹配」。
        val registered=!deviceIdentity["device_id"].isNullOrBlank()
        return try {
            val result=if(source==SearchSource.WEB) webSearch(keyword,page)
                else search(keyword,page,ContentType.entries.toList())
            // 官网这条「部分物理页失败」不算整页失败（其余页照常出内容），但要写进判读结论：
            // 否则面板上是「有结果 37 条」加一行 502，用户不知道少掉的那几页去哪了。
            val note=if(source==SearchSource.WEB) webSearchNote.get().orEmpty() else ""
            SearchDiag.record(keyword,page,source,result.items.size,note=note,registered=registered,alive=appSearchAlive)
            result
        } catch(e: Exception) {
            val (reason,detail)=SearchDiag.classify(e)
            SearchDiag.record(keyword,page,source,0,reason,detail,registered,appSearchAlive)
            throw e
        }
    }
    /** 只搜一种内容类型（App 搜索接口的一个 tab）：合并搜索与联调都走这一份实现。 */
    fun search(keyword: String, page: Int, type: ContentType): CatalogPage = search(keyword,page,listOf(type))
    /**
     * 官网搜索：`GET https://hongguoduanju.com/api/search/result?query={关键词}&page=N`。
     *
     * 2026-10 之前走的是搜索页 HTML 里的 `_ROUTER_DATA`；`www` 改成 301 到裸域名、
     * 页面也不再内联 SSR 数据之后，那条路整条失效。现在直连官网自己用的 JSON 接口，
     * 字段与卡片同源（`searchList[].video_data`），`totalPages` / `hasMore` 由服务端给。
     *
     * **一个逻辑页给界面 100 条**：服务端一页只有 10 条，而且 `count` / `page_size` / `limit`
     * 这类参数一律被忽略（见 [WEB_SEARCH_PAGE_SIZE]），所以这里把 [WEB_SEARCH_BATCH] 个
     * 物理页并发拉回来拼成一份——与 App 搜索的 [LOGICAL_PAGE_SIZE] 同量，
     * 界面侧「展示更多」一次就能多出一屏十行。
     *
     * 查不到东西时接口回的是 `{"searchList":[],"totalPages":1}`（HTTP 200），
     * 按「没有结果」处理，让界面显示「换个关键词试试」。
     */
    fun webSearch(keyword: String,page: Int = 1): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..MAX_CATALOG_PAGE)
        // 服务端只收 30 个字符（更长的回 400）：这里先截断，宁可少两个字的查询，
        // 也不要让用户看到「官网搜索没有结果」。
        val clean=keyword.trim().take(WEB_SEARCH_QUERY_MAX)
        // 上一次搜索留下的摘要不能带到这一次（同一个线程会被复用）。
        webSearchNote.set("")
        val first=(page-1)*WEB_SEARCH_BATCH+1
        val scope=RequestScope.current()
        val pool=java.util.concurrent.Executors.newFixedThreadPool(WEB_SEARCH_CONCURRENCY) { task ->
            Thread(task,"hgtv-websearch").apply { isDaemon=true }
        }
        val parts=try {
            val tasks=(first until first+WEB_SEARCH_BATCH).map { physical ->
                pool.submit(java.util.concurrent.Callable {
                    // 取消（切页 / 重新搜索）沿着 [RequestScope] 传进这十条请求，一起收手。
                    RequestScope.withScope(scope) { runCatching { webSearchPageRetry(clean,physical) }.getOrNull() }
                })
            }
            tasks.map { task -> runCatching { task.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
        // 十个物理页全部失败才算这一页失败：部分失败时把拿到的那些照常给界面。
        // 「查不到」不走这里：接口对没有结果的关键词回的是 200 + 空 `searchList`，
        // 因此走到这一句就是真的连不上（或后端整体不可用），该让界面给「重试」。
        if(parts.all { it==null }) {
            webSearchNote.set("官网 ${WEB_SEARCH_BATCH} 个物理页全部失败")
            NetLog.log("hongguoduanju.com/api/search/result 第${first}~${first+WEB_SEARCH_BATCH-1}页全部失败")
            throw SearchApiException(SearchDiag.FAILURE_NETWORK,"官网 ${WEB_SEARCH_BATCH} 个物理页全部失败")
        }
        // 部分页失败：**只汇总一条**，不把每个 502 都写进面板（那会把 40 条近况刷满，
        // 看起来像「整个官网搜索坏了」）。逐条的 HTTP 状态已在 [webSearchPageRetry] 里静默。
        val failed=parts.count { it==null }
        if(failed>0) {
            val note="官网搜索 ${failed}/${WEB_SEARCH_BATCH} 页重试后仍失败"
            webSearchNote.set(note)
            NetLog.log("hongguoduanju.com/api/search/result 第${first}~${first+WEB_SEARCH_BATCH-1}页 · $note")
        } else {
            webSearchNote.set("")
        }
        val items=mutableListOf<Series>()
        parts.forEach { part -> if(part!=null) items+=part.items }
        // 服务端分页偶尔重复（同一部剧出现在相邻两页）：按关键词记一份已发过的 id，只回新的。
        val seen=synchronized(webSearchSeen) {
            if(page==1) {
                if(webSearchSeen.size>=8) webSearchSeen.keys.firstOrNull()?.let(webSearchSeen::remove)
                webSearchSeen[clean]=mutableSetOf()
            }
            webSearchSeen.getOrPut(clean) { mutableSetOf() }
        }
        val fresh=synchronized(webSearchSeen) { items.filter { seen.add(it.id) } }
        // 「还有更多」看这一批里**最后一个成功的物理页**：它说没有了，后面几页也就没了。
        val more=parts.filterNotNull().lastOrNull()?.hasMore==true && page<MAX_CATALOG_PAGE
        return CatalogPage(fresh,more)
    }
    /**
     * 官网搜索一个物理页，带重试与**静默上报**。
     *
     * 两层保护：
     * * 后端抖动（`502 upstream_unavailable`）隔 200~400ms 重发通常就过，因此重试 [WEB_SEARCH_RETRY] 次；
     * * 每次尝试都在 [NetLog.quiet] 里跑：失败由 [webSearch] 汇总成一条，
     *   不让 10 个物理页的 502 把调试面板的「近况」刷满。
     *
     * 取消（切页 / 重新搜索）不重试：线程被打断或取消域已取消时立刻抛出，
     * 否则用户切走之后线程还在为上一次搜索空转。
     */
    private fun webSearchPageRetry(keyword: String,page: Int): CatalogPage {
        var last: Throwable?=null
        repeat(WEB_SEARCH_RETRY+1) { attempt ->
            if(attempt>0) {
                try {
                    Thread.sleep(WEB_SEARCH_RETRY_DELAY*attempt)
                } catch(cancelled: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("请求已取消",cancelled)
                }
            }
            try {
                return NetLog.quiet { webSearchPage(keyword,page) }
            } catch(problem: Throwable) {
                // 取消 / 打断不是「这一页坏了」：不重试，直接交回上层（上层按失败丢弃）。
                if(Thread.currentThread().isInterrupted || isInterruption(problem)) throw problem
                last=problem
            }
        }
        throw (last ?: IOException("官网搜索暂不可用"))
    }
    /**
     * 官网搜索的一个**物理页**（服务端一页 10 条）：只解析、不去重、不拼页。
     *
     * 去重放在 [webSearch] 里做：跨物理页的重复只有拼起来之后才看得出来。
     */
    private fun webSearchPage(keyword: String,page: Int): CatalogPage {
        val url="$SITE_HOST/api/search/result?query=${Signer.encode(keyword)}&page=$page"
        // 重试放在 [webSearchPageRetry]：502 带响应体时 [siteText] 不抛异常，
        // 这里的 `code` 分支才会判失败，因此重试必须在**解析之后**做。
        val raw=siteText(url)
        val data=JSONObject(raw)
        // 失败时接口回的是 `{"code":...,"message":...}`（HTTP 可能是 200 / 400 / 502）：
        // - `invalid_query`：关键词为空或超过 30 字，按「没有结果」处理；
        // - `upstream_unavailable`：官网搜索后端瞬时不可用，重试已经用过了，这一页算失败
        //  （[webSearch] 里十个物理页全失败才算整页失败，部分失败照常给结果）。
        if(data.has("code")) {
            if(data.optString("code")=="invalid_query") return CatalogPage(emptyList(),false)
            throw IOException("官网搜索暂不可用（${data.optString("code")}）")
        }
        val rows=data.optJSONArray("searchList") ?: return CatalogPage(emptyList(),false)
        // 超出末页时服务端不报错，而是**把整份结果一次性塞回来**（实测 page=99 时 216 条、
        // 且 totalPages 被改写成 99）：那已经不是「第 N 页」，照收会让列表里多出几百条
        // 与前面重复的内容。一页正常最多 10 条，明显超量就按「没有更多」处理。
        if(rows.length()>WEB_SEARCH_PAGE_SIZE*3) return CatalogPage(emptyList(),false)
        val items=valid(rows.objects().map(::webCard))
        val totalPages=data.optInt("totalPages",1).coerceIn(1,MAX_CATALOG_PAGE)
        val more=data.optBoolean("hasMore",page<totalPages) && page<totalPages
        return CatalogPage(items,more)
    }
    /** 官网搜索的卡片：`video_data` 与详情页同源（`series_title` / `series_cover` / `series_intro`）。 */
    private fun webCard(item: JSONObject): Series {
        val o=item.optJSONObject("video_data")?.takeIf { it.length()>0 } ?: item
        val count=o.optInt("episode_cnt",0)
        val tags=tagsOf(o,"category_list")
        return Series(
            id=o.str("series_id").ifEmpty { item.str("keyword") },
            title=o.str("series_title").ifEmpty { item.str("name") },
            cover=cleanUrl(o.str("series_cover")),
            description=o.str("series_intro"),
            badge=EpisodeLabel.normalize(o.str("episode_right_text")).ifEmpty { EpisodeLabel.of(count) },
            tags=tags,episodeCount=count)
    }
    /**
     * 官网页面的一次 GET：不走 App 的签名与设备参数（那套头对官网没有意义），
     * 只带一个普通浏览器的 UA 与语言。
     */
    private fun siteText(url: String,retries: Int = 0): String {
        var last: Throwable? = null
        repeat(retries+1) {
            val request=Request.Builder().url(url)
                .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept-Language","zh-CN,zh;q=0.9")
                // 官网的 JSON 接口：不要 HTML，也不要缓存中间层给的那一份。
                .header("Accept","application/json")
                .get().build()
            try {
                return RequestScope.execute(http.newCall(request)) { response ->
                    val body=response.body() ?: throw IOException("内容响应为空")
                    if(body.contentLength()>16*1024*1024) throw IOException("内容响应过大")
                    // 502 时响应体里仍然带着 `{"code":"upstream_unavailable"}`：读出来交给
                    // 调用方判读，比只丢一句「HTTP 502」有用得多。
                    val raw=body.string()
                    if(!response.isSuccessful && raw.isBlank()) throw IOException("内容请求失败（HTTP ${response.code()}）")
                    raw
                }
            } catch(problem: Throwable) {
                last=problem
            }
        }
        throw (last ?: IOException("内容请求失败"))
    }
    /**
     * 搜索：只走 App 搜索接口（`GET /reading/bookapi/search/tab/v`，参数全在 query 里）。
     *
     * 一次搜索会把 [types] 里**每个有搜索 tab 的类型**各问一次再合并按 id 去重
     *（`19` 漫剧、`11` 短剧 / 真人剧；AI 剧没有对应 tab，见 [ContentType.searchable]）。
     * 第 1 页起就带游标（`search_id` / `passback` / `next_offset`）可一直往下翻，见 [appSearch]。
     *
     * 失败语义：接口回空响应体 = 这台设备没在服务端注册过（见 [registerDevice]），
     * 如实抛错让界面提示，**不再**用别的数据源顶替；游标过期抛 [SearchSessionExpiredException]，
     * 由界面回到第 1 页重搜。
     */
    private fun search(keyword: String, page: Int, types: List<ContentType>): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100 && types.isNotEmpty())
        val items=mutableListOf<Series>()
        var hasMore=false; var answered=false; var expired=false
        var failure: IOException?=null
        // 本机是否已经拿到注册下发的那一对身份（见 [registerDevice]）。
        // 注册成功之后，之前那次「空响应」的结论不再成立，下面的判死要放行一次。
        val registered=!deviceIdentity["device_id"].isNullOrBlank()
        types.filter { it in ContentType.searchable }.distinct().forEach { type ->
            // 已经判死（空响应 = 设备没注册）就别再问另一个 tab：两个 tab 结论一致，
            // 多问一次只是把首屏又推后一个 RTT。
            if(appSearchAlive==false && !registered) return@forEach
            try {
                val result=appSearch(keyword,page,type)
                answered=true; items+=result.items; if(result.hasMore) hasMore=true
            } catch(e: SearchSessionExpiredException) { expired=true } catch(e: Exception) {
                failure=e as? IOException ?: IOException(e.message ?: "搜索接口暂不可用")
            }
        }
        // 有一个 tab 给了答案就用它：另一个 tab 空响应 / 少 tab 都不算失败。
        if(answered) return CatalogPage(valid(items),hasMore && page<100)
        if(expired) throw SearchSessionExpiredException()
        throw failure ?: SearchApiException(SearchDiag.FAILURE_FORMAT,"接口没有返回任何 tab 数据")
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=appValues()
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to if(signedIn) "1" else "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        // 漫剧翻页必须带上一页的游标。接口已经被平台关掉（空响应体）时不再抛「搜索结果已过期」：
        // 那样会把漫剧搜索钉死在第 1 页，用户看到的就是「第 2 页永远搜不出来」。
        if(type==ContentType.COMIC && page>1 && cursor==null && appSearchAlive!=false) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        // **必须 GET**：同一路径 POST 是 404（2026-10-21 实测），参数全在 query 里。
        // 签名只覆盖 query + body、不覆盖方法，因此这里显式把方法钉成 GET。
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values,method="GET")
        // **空响应体 = 这台设备没在服务端注册过，重发多少次都一样。**
        //
        // 平台按「服务端注册过的 `device_id` + `install_id`」放行：注册过的设备只回
        // `code 0` 或间歇性 `100103`，**从不回空响应**；没注册的一律空响应。
        // 而注册本身是匿名的（见 [registerDevice]），所以正确的做法是**去注册一台**，
        // 而不是原地重发——旧实现在这里退避 550/800/1050/1300/1550ms 重发 5 次，
        // 两个 tab 串行白等 12.8 秒，正是「搜索要十多秒才出结果」的来由。
        // 现在空响应**一次即判死**，立刻抛错让界面提示（而不是原地退避重发五次）。
        var raw=text(signed.url,signed.withSession())
        // 平台对匿名设备返回 HTTP 200 + 空响应体，这里必须先判空：
        // 直接丢给 JSONObject 会抛 JSONException（A JSONObject text must begin with '{'），
        // 那既不是可读的错误，也会把「接口不可用」这个结论说清楚的机会丢掉。
        if(raw.isBlank()) { appSearchAlive=false; noteDeviceRejected(); throw DeviceNotRegisteredException() }
        // `100103` 才是真正的间歇性错误（同一设备约一半概率），短间隔重发几次即可。
        var attempt=0
        while(attempt<3 && raw.startsWith("""{"code":100103""")) {
            attempt++
            Thread.sleep(120L)
            raw=text(signed.url,signed.withSession())
            if(raw.isBlank()) { appSearchAlive=false; noteDeviceRejected(); throw DeviceNotRegisteredException() }
        }
        appSearchAlive=true
        val data=JSONObject(raw)
        val code=data.optInt("code",-1)
        if(code!=0) throw SearchApiException(SearchDiag.FAILURE_CODE,"code=$code")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs")
            ?: throw SearchApiException(SearchDiag.FAILURE_FORMAT,"没有 search_tabs")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab }
            ?: throw SearchApiException(SearchDiag.FAILURE_TAB,"缺少 tab_type=${type.searchTab}")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull()
                if(video!=null) {
                    val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                    val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                    val count=video.optInt("episode_cnt")
                    // 出品方：搜索接口的 `video_data` 也带 `copyright`（与分类 / 详情同源），
                    // 直接取，卡片第二行就不用再为这一屏发一次 `multi-detail`。
                    // 类型标签：搜索的 `video_data` 与列表接口同源（`category_schema`）。
                    // 拿不到就留空，卡片第二行退回状态行（热度 / 进度），不拿季号顶替。
                    val tags=tagsOf(video)
                    list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),EpisodeLabel.of(count).ifEmpty { video.str("rec_text") },tags,
                        author=publisher(video.str("copyright")),episodeCount=count)
                    continue
                }
                // 未上架的剧在搜索里是另一种 cell：`show_type=462`，没有 `video_data`，
                // 数据在 `subscribe_items[]` 里（`item_id` / `name` / `cover` / `sub_info`）。
                // 早先这里直接 `continue`，于是「谁让这个文盲修仙的第六季」这种卡片根本不出现，
                // 用户只能从别处点进来、再撞上详情页的 404。
                val sub=row.optJSONArray("subscribe_items")?.objects()?.firstOrNull() ?: continue
                val id=sub.str("item_id").ifEmpty { sub.str("item_id_str") }
                val title=sub.str("name")
                val tags=tagsOf(sub)
                // `sub_info` 是「预计11月6日上线」这种最有用的一句；没有就退到「预告」角标。
                val notice=sub.str("sub_info").ifEmpty { sub.optJSONObject("tag_info")?.str("text").orEmpty() }
                list+=Series(id,title,cleanUrl(sub.str("cover")),sub.str("item_desc"),notice,tags,
                    episodeCount=0,upcoming=true,notice=notice)
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    // ------------------------------------------------------------------ 账号（个人信息页）
    /**
     * 账号接口共用的一次请求：带登录态、带设备参数、带签名。
     *
     * 这些接口都在 App 域名下（与搜索 / 弹幕同一套），因此设备参数用 [appValues]；
     * 写接口（POST + body）改用 [writeValues]——它们走的是官方 7.3.9.32 那一版，
     * 而且平台会拿签名里的设备档案做一致性判定。
     *
     * 空响应体在这个平台上很常见（网关挡掉），统一翻成可读的 [IOException]，
     * 免得把 JSONException 抛到界面上。
     */
    private fun accountJson(path: String, params: Map<String,String> = emptyMap(), body: ByteArray? = null): JSONObject {
        // 账号侧接口全部要求登录态：没有凭据时**不发请求**，直接给出可辨识的失败原因。
        if(!signedIn) throw SignInRequiredException()
        val values=if(body!=null) writeValues() else appValues()
        params.forEach { (key,value) -> values[key]=value }
        val signed=signer.sign("https://$ACCOUNT_HOST$path",values,body)
        val raw=text(signed.url,signed.withSession())
        if(raw.isBlank()) throw IOException("账号接口暂无响应（空响应体）")
        return JSONObject(raw)
    }

    /**
     * 空响应体重发：这个平台上「网关偶发挡掉」很常见（实测约一半概率），
     * 同一请求重发即可。只重试空 body，真的报错（100103 等）直接抛出去。
     */
    private fun retryBlank(attempts: Int = ACCOUNT_RETRIES, action: () -> JSONObject): JSONObject {
        var last: Exception?=null
        repeat(attempts) { attempt ->
            try { return action() } catch(e: Exception) {
                last=e
                if(!e.message.orEmpty().contains("空响应体")) throw e
                Thread.sleep(300L+attempt*250L)
            }
        }
        throw last ?: IOException("账号接口暂无响应")
    }

    /**
     * 账号资料。
     *
     * 两个接口一起用，因为信息是分开的（实测）：
     * * `user/info`（不带参数 = 当前登录用户）——昵称 / 头像 / **红果号**（字段名是 `req_id`）；
     * * `basic_info/get`——关注 / 粉丝 / 获赞三个计数。
     *
     * 注意 `profile/get` **必须带 `user_id`**（`?user_id=1739875314044148`），
     * 不带参数它回 `100107 network err`；它是看**别人**的资料用的，这里不用它。
     */
    fun accountProfile(): AccountProfile? {
        if(!signedIn) throw SignInRequiredException()
        val info=runCatching { accountJson("/reading/user/info/v1/") }.getOrNull()?.optJSONObject("data")
        val basic=runCatching { accountJson("/reading/user/basic_info/get/v1/") }.getOrNull()?.optJSONObject("data")
        return AccountParser.profile(info,basic)
    }

    /** 我的收藏：短剧书架（`bookshelf/video/info` 带封面与简介，比 `video/list` 那份完整）。 */
    fun accountFavorites(): List<AccountFavorite> {
        if(!signedIn) throw SignInRequiredException()
        return AccountParser.favorites(accountJson("/reading/bookapi/bookshelf/video/info/v"))
    }

    /**
     * 观看历史。
     *
     * 走 `read_progress/list`（不是 `read_history/list`——后者对短剧**恒返回空列表**，
     * 它是给小说用的）：`?book_types=2&limit=200`，短剧记录在 `video_data[]` 里。
     *
     * **一次取全，不翻页**：这条接口的 `offset` 不生效（给 50/100/150 回来的都是同一批），
     * 只有 `limit` 有用，而且超过真实条数时服务端照实际数量返回（见 [HISTORY_LIMIT]）。
     * 早先写死 `limit=50`，于是账号里有 139 条、电视上只出现 50 条——「同步来的不完全」。
     *
     * 它**只给 series_id**，所以再用 `multi-detail` 批量补片名与封面；补的时候按
     * [MULTI_DETAIL_LIMIT] 分片，某一片失败只丢那一片的元数据，不影响整栏。
     * 补不到元数据的条目仍然**不显示**（只有 id 的卡片是个空框，比少一条更糟），
     * 但会在 [writeLog] 里留一行，便于排查是哪一片没回来。
     */
    fun accountHistory(): List<AccountHistory> {
        if(!signedIn) throw SignInRequiredException()
        val rows=retryBlank {
            accountJson("/reading/bookapi/read_progress/list/v",
                mapOf("book_types" to "2","limit" to HISTORY_LIMIT.toString()))
        }.let(AccountParser::history)
        if(rows.isEmpty()) return rows
        val details=linkedMapOf<String,Series>()
        rows.map { it.series.id }.distinct().chunked(MULTI_DETAIL_LIMIT).forEach { chunk ->
            val part=runCatching {
                retryBlank {
                    accountJson("/reading/bookapi/multi-detail/v",
                        mapOf("book_id" to chunk.joinToString(","),"book_type" to "2"))
                }.let(AccountParser::multiDetail)
            }
            part.onFailure { writeLog("历史元数据分片失败 size=${chunk.size} ${it.message}") }
            details.putAll(part.getOrDefault(emptyMap()))
        }
        val kept=rows.mapNotNull { row -> details[row.series.id]?.let { row.copy(series=it) } }
        if(kept.size<rows.size) writeLog("历史元数据缺失 ${rows.size-kept.size}/${rows.size}")
        return kept
    }

    /**
     * 点赞：`ugc/action/mget` 的「我的点赞记录」。
     *
     * `action_type=3` 是「我点赞的」，`object_type_list=6,15,10` 是短剧相关的内容类型
     * （实测给 16 只回空；老的 `praise/user/record` 只覆盖送花那类，短剧点赞不在里面）。
     * 这个接口会间歇性回空响应体，重发即可。
     */
    fun accountLikes(): List<AccountLike> {
        if(!signedIn) throw SignInRequiredException()
        val params=mapOf("count" to "50","object_type_list" to "6,15,10","offset" to "0","action_type" to "3")
        return AccountParser.likes(retryBlank { accountJson("/reading/ugc/action/mget/v",params) })
    }

    /**
     * 预约 / 追更：`subscribe/list/v0` 的「预约」那一栏。
     *
     * `tab_type=13` 才是真数据（11/12 是空壳：`subscribe_items` 为 null）；
     * `is_online=true` 取「已上线」，false 取「待上线」。两批合起来就是 App 里那一栏的全部。
     */
    fun accountReserves(): List<AccountReserve> {
        if(!signedIn) throw SignInRequiredException()
        // `v1` 才是当前版本（真机抓包 2026-10-19）：待上线那一栏的条目字段在**顶层**
        //（`item_id` / `name` / `cover` / `sub_info`），不是 `v0` 的 `subscribe_data`。
        // 用 `v0` 读待上线会稳定返回空列表，界面上就成了「预约过的剧不见了」。
        fun page(online: Boolean) = AccountParser.reserves(accountJson("/reading/user/subscribe/list/v1/",
            mapOf("offset" to "0","limit" to "20","is_online" to online.toString(),"session_id" to "","tab_type" to "13",
                "subscribe_order_type" to "0","swipe_type" to "0","need_calendar_schema" to "false","subscribe_offset" to "0")))
        val online=page(true)
        val offline=runCatching { page(false) }.getOrDefault(emptyList())
        return (online+offline).distinctBy { it.series.id }.sortedByDescending { it.updatedAt }
    }
    /**
     * 预约 / 取消预约一部**未上架**的剧。
     *
     * 两个端点都来自真机抓包（2026-10-19，Reqable），字段名与类型必须逐字对上：
     *
     * ```
     * 添加 POST /reading/bookapi/search/uncover_subscribe/v1/
     *      {"item_id":7697091595459382297,"item_type":14,"op_type":1,
     *       "item_id_str":"7697091595459382297","wish_list_all_del":0,
     *       "shark_param":{"enter_from":"ShortSeriesActivity",…}}
     * 取消 POST /reading/user/subscribe/delete/v1/
     *      {"item_id":["<id>"],"all_select":false,"not_del_item_id":[],
     *       "is_online":false,"tab_type":13}
     * ```
     *
     * 注意三处容易写错、写错也照样回 `code 0` 的地方：
     * * 添加的 `item_id` 是**数字**（给字符串回 `100103`），但取消的 `item_id` 是**字符串数组**；
     * * 添加必须带 `item_id_str` 与 `shark_param`，缺了会被风控判 `101144 HIT_SHARK_RULE`；
     * * 平台对「刚改过又改回来」有短时限流：`101144` 隔几秒重发就能过，所以这里自己退避重试。
     *
     * 返回值只表示平台是否受理；界面先按成功改本地状态，失败再翻回去（见 `reserveSeries`）。
     */
    fun accountReserve(seriesId: String, subscribe: Boolean): Boolean {
        if(!signedIn || !seriesId.matches(Regex("[0-9]{1,30}"))) return false
        val payload = if(subscribe) JSONObject()
            .put("item_id",seriesId.toLong())
            .put("item_type",14)
            .put("op_type",1)
            .put("item_id_str",seriesId)
            .put("wish_list_all_del",0)
            .put("shark_param",JSONObject()
                .put("enter_from","ShortSeriesActivity")
                .put("previous_page","BulletActivity")
                .put("page_list","MainFragmentActivity,BulletActivity,ShortSeriesActivity"))
            .toString().toByteArray()
        else JSONObject()
            .put("item_id",JSONArray().put(seriesId))
            .put("all_select",false)
            .put("not_del_item_id",JSONArray())
            .put("is_online",false)
            .put("tab_type",13)
            .toString().toByteArray()
        val path = if(subscribe) "/reading/bookapi/search/uncover_subscribe/v1/" else "/reading/user/subscribe/delete/v1/"
        repeat(ACCOUNT_WRITE_ATTEMPTS) { attempt ->
            var detail=""
            val ok=runCatching {
                val reply=accountJson(path,emptyMap(),payload)
                val code=reply.optInt("code",-1)
                if(code!=0) detail="code=$code message=${reply.optString("message")}"
                code==0
            }.onFailure { detail="异常 ${it.message}" }.getOrDefault(false)
            if(ok) return true
            writeLog("预约写接口失败 attempt=${attempt+1} subscribe=$subscribe id=$seriesId $detail")
            // 101144 是风控的短时限流：退避要够长（实测几秒后才放行），别原地连发。
            if(attempt<ACCOUNT_WRITE_ATTEMPTS-1) Thread.sleep(ACCOUNT_WRITE_BACKOFF[attempt].coerceAtLeast(600L)*(attempt+1))
        }
        return false
    }

    /**
     * 账号写接口：把本机的收藏 / 观看记录同步到云端。
     *
     * 三个接口都是 `POST` + JSON body，body 的字段名**必须与 App 的 protobuf/gson 模型一致**
     * （从 `com.dragon.read.rpc.model` 反编译出来的 `@SerializedName`）：
     *
     * * 收藏加：`bookshelf/add/v`，`{"book_id":["<series_id>"]}`——**数组**，给字符串会回 100103；
     * * 收藏删：`bookshelf/video/update/v`，`update_bookshelf_video_list[].video_shelf_operate_type=1`（DelShelf）；
     * * 进度 / 历史：`read_progress/upload/v`，`books[]` 里的字段是 **`book_id` / `item_id` /
     *   `read_timestamp_ms`**（不是 `series_id` / `vid` / `last_watch_time`——用错名字平台照回
     *   `code=0 SUCCESS`，但云端一个字节都不改）；`write_mode=1` = 同时写进度与历史。
     *
     * 写接口失败**不打扰用户**：本机记录已经落盘，云端同步只是尽力而为，返回 false 由调用方决定要不要提示。
     */
    fun accountAddFavorite(seriesId: String): Boolean = videoShelf(seriesId,VIDEO_SHELF_ADD_SHELF)

    /** 取消收藏：`bookshelf/video/update` 的 DelShelf（`bookshelf/delete` 实测不生效）。 */
    fun accountRemoveFavorite(seriesId: String): Boolean = videoShelf(seriesId,VIDEO_SHELF_DEL_SHELF)

    /**
     * 短剧收藏的增 / 删：`POST /reading/bookapi/bookshelf/video/update/v`。
     *
     * 短剧收藏（App 里叫「追剧」）只有这一个写接口，`bookshelf/add` 是**小说书架**，
     * 写进 `book_shelf_info_all`，而「我的收藏」短剧那一栏读的是 `video_shelf_info`——
     * 用错接口平台照样回 `code=0 SUCCESS`，短剧收藏却一点不变。
     *
     * `book_type` 用 [BOOK_TYPE_WATCH]（`ReadingBookType.Watch`）；
     * 平台偶发 `101144 HIT_SHARK_RULE`（风控限流），重发即可——官方包自己也带 pending 重试队列。
     */
    private fun videoShelf(seriesId: String,operate: Int): Boolean {
        if(!signedIn || !seriesId.matches(Regex("[0-9]{1,30}"))) return false
        val entry=JSONObject()
            .put("book_id",seriesId).put("book_type",BOOK_TYPE_WATCH)
            .put("modify_time",clock())
            .put("video_shelf_operate_type",operate)
        // 「取消」在条目里带一个空的 `group_name`；「加入」不带。
        if(operate==VIDEO_SHELF_DEL_SHELF) entry.put("group_name","")
        // `shark_extra`（进入来源）是**body 的顶层字段**，不在条目里——这是 2026-10-15
        // 从官方包抓包核对出来的（`POST /reading/bookapi/bookshelf/video/update/v`）：
        // 官方那条请求的 body 形如
        // `{"is_cancelled":false,"shark_extra":{…},"update_bookshelf_video_list":[{…}]}`。
        // 早先把它塞进条目里，服务端照旧回 `101144 追剧失败`。
        val body=JSONObject()
            .put("is_cancelled",false)
        if(operate==VIDEO_SHELF_ADD_SHELF) {
            body.put("shark_extra",JSONObject()
                .put("enter_from","MainFragmentActivity")
                .put("previous_page","")
                .put("page_list","MainFragmentActivity")
                .put("is_active_behavior","true")
                .put("inactive_type","0"))
        }
        body.put("update_bookshelf_video_list",JSONArray().put(entry))
        val payload=body.toString().toByteArray()
        // 风控是**偶发**的（同一条请求重发两三次里总有一次过），因此这里自己重试：
        // [retryBlank] 只管空响应体，101144 要单独再发。
        repeat(ACCOUNT_WRITE_ATTEMPTS) { attempt ->
            var detail=""
            val ok=runCatching {
                val reply=accountJson("/reading/bookapi/bookshelf/video/update/v",emptyMap(),payload)
                val code=reply.optInt("code",-1)
                if(code!=0) detail="code=$code message=${reply.optString("message")}"
                code==0
            }.onFailure { detail="异常 ${it.message}" }.getOrDefault(false)
            if(ok) return true
            writeLog("收藏写接口失败 attempt=${attempt+1} operate=$operate id=$seriesId $detail")
            if(attempt<ACCOUNT_WRITE_ATTEMPTS-1) Thread.sleep(ACCOUNT_WRITE_BACKOFF[attempt])
        }
        return false
    }

    /**
     * 上传观看进度（`write_mode=1` 会同时写进「历史」）。
     *
     * [episodeCount] 只用于云端「全 N 集」的展示，未知时传 0；
     * `progress_rate` 是 0~1 的小数串，平台用它画进度条。
     */
    fun accountUploadProgress(seriesId: String,episodeId: String,episodeIndex: Int,position: Long,duration: Long,episodeCount: Int=0): Boolean {
        if(!signedIn || !seriesId.matches(Regex("[0-9]{1,30}"))) return false
        if(!episodeId.matches(Regex("[0-9]{1,30}"))) return false
        val rate=if(duration>0) (position.toDouble()/duration).coerceIn(0.0,1.0) else 0.0
        val entry=JSONObject()
            .put("book_id",seriesId)
            .put("item_id",episodeId)
            .put("vid_index",episodeIndex+1)
            .put("current_play_time",position.coerceAtLeast(0))
            .put("current_total_time",duration.coerceAtLeast(0))
            .put("episode_count",episodeCount.coerceAtLeast(0))
            .put("read_timestamp_ms",clock())
            .put("book_type",BOOK_TYPE_WATCH)
            .put("progress_rate","%.4f".format(java.util.Locale.US,rate))
            .put("player_cumulative_total_duration",position.coerceAtLeast(0))
        val body=JSONObject().put("books",JSONArray().put(entry)).put("write_mode",1).toString().toByteArray()
        repeat(ACCOUNT_WRITE_ATTEMPTS) { attempt ->
            var detail=""
            val ok=runCatching {
                val reply=accountJson("/reading/bookapi/read_progress/upload/v",emptyMap(),body)
                val code=reply.optInt("code",-1)
                if(code!=0) detail="code=$code message=${reply.optString("message")}"
                code==0
            }.onFailure { detail="异常 ${it.message}" }.getOrDefault(false)
            if(ok) return true
            writeLog("进度写接口失败 attempt=${attempt+1} id=$seriesId $detail")
            if(attempt<ACCOUNT_WRITE_ATTEMPTS-1) Thread.sleep(ACCOUNT_WRITE_BACKOFF[attempt])
        }
        return false
    }

    /**
     * 删除一条云端观看历史。
     *
     * 实测：`read_history/update` 必须带 `is_delete=true` + **`use_soft_delete=true`** +
     * `update_timestamp_ms`（带 `read_timestamp_ms` 会被放进 `update_fail_datas` 里拒绝）；
     * `book_id` 要给**数字**（字符串会被判 `100103`）。
     */
    fun accountDeleteHistory(seriesId: String): Boolean = accountDeleteHistories(listOf(seriesId))

    /**
     * 批量删除云端观看记录（清空历史用）。
     *
     * 与单条删除走同一个端点、同一套字段：`update_datas` 本来就是数组，一次塞多条即可。
     * 按 [HISTORY_DELETE_BATCH] 分批，避免一次请求体过大被网关拒掉。
     * 返回**全部批次都成功**才为 true；某一批失败只记日志，其余照常发。
     */
    fun accountDeleteHistories(seriesIds: List<String>): Boolean {
        val ids=seriesIds.filter { it.matches(Regex("[0-9]{1,30}")) }.distinct()
        if(!signedIn || ids.isEmpty()) return false
        var ok=true
        ids.chunked(HISTORY_DELETE_BATCH).forEach { chunk ->
            val rows=JSONArray()
            chunk.forEach { id ->
                rows.put(JSONObject()
                    .put("book_id",id.toLong())
                    .put("book_type",BOOK_TYPE_WATCH)
                    .put("is_delete",true)
                    .put("use_soft_delete",true)
                    .put("update_timestamp_ms",clock()))
            }
            val body=JSONObject().put("update_datas",rows).toString().toByteArray()
            val part=runCatching {
                val reply=accountJson("/reading/bookapi/read_history/update/v",emptyMap(),body)
                val code=reply.optInt("code",-1)
                val failed=(reply.optJSONObject("data")?.optJSONArray("update_fail_datas")?.length() ?: 0)
                if(code!=0 || failed>0) writeLog("历史删除写接口失败 size=${chunk.size} code=$code failed=$failed message=${reply.optString("message")}")
                code==0 && failed==0
            }.onFailure { writeLog("历史删除写接口异常 size=${chunk.size} ${it.message}") }.getOrDefault(false)
            if(!part) ok=false
        }
        return ok
    }

    /**
     * 云端观看记录的**全部 id**（不补片名与封面）。
     *
     * 与 [accountHistory] 读同一个接口（`read_progress/list`，`limit` 一次给满），
     * 但不做 `multi-detail` 那一轮元数据补齐：清空历史只关心「云端还有哪些 id」，
     * 补片名是纯浪费。`offset` 在这条接口上不生效（见 [HISTORY_LIMIT]），因此一次取全。
     */
    fun accountHistoryIds(): List<String> {
        if(!signedIn) throw SignInRequiredException()
        val rows=retryBlank {
            accountJson("/reading/bookapi/read_progress/list/v",
                mapOf("book_types" to "2","limit" to HISTORY_LIMIT.toString()))
        }.let(AccountParser::history)
        return rows.map { it.series.id }.distinct()
    }

    /**
     * 清空云端观看记录：**以云端那份为准**，不看本机缓存里记得的几条。
     *
     * 为什么不能只删本机知道的 id：历史那一栏是「本机进度 + 账号侧缓存」合并出来的，
     * 账号侧缓存只覆盖这台设备上一次拉到的那一批；手机上看过、这台设备上没看过的记录
     * 从来没有进过 [accountData]，只删本机那几条，云端会剩下一大截——
     * 用户读到的就是「清空之后手机上还在、下次刷新又冒出来」。
     *
     * 流程（最多 [HISTORY_CLEAR_ATTEMPTS] 轮）：
     * 1. 拉一次云端全量 id（[accountHistoryIds]），与本机已知的 [extra] 合并；
     * 2. 分批 `read_history/update` 软删（[accountDeleteHistories]）；
     * 3. **回读校验**：再拉一次云端 id，空了才算成功；还有就再来一轮
     *   （软删偶发只生效一部分，平台对短剧进度列表的处理不是严格即时）。
     *
     * 返回 true = 回读确认云端已经没有记录；false = 仍有余量或网络失败，
     * 由调用方提示用户稍后重试（本机那份无论如何已经清干净了）。
     */
    fun accountClearHistory(extra: List<String> = emptyList()): Boolean {
        if(!signedIn) return false
        val known=extra.filter { it.matches(Regex("[0-9]{1,30}")) }
        repeat(HISTORY_CLEAR_ATTEMPTS) { attempt ->
            val cloud=runCatching { accountHistoryIds() }
                .onFailure { writeLog("清空历史：读取云端列表失败 attempt=${attempt+1} ${it.message}") }
                .getOrElse { return@repeat }
            val ids=(cloud+known).distinct()
            if(ids.isNotEmpty() && !accountDeleteHistories(ids)) {
                writeLog("清空历史：删除批次未全部成功 attempt=${attempt+1} size=${ids.size}")
            }
            val left=runCatching { accountHistoryIds() }.getOrElse {
                writeLog("清空历史：回读校验失败 attempt=${attempt+1} ${it.message}")
                return@repeat
            }
            if(left.isEmpty()) return true
            writeLog("清空历史：回读仍有 ${left.size} 条 attempt=${attempt+1}")
            if(attempt<HISTORY_CLEAR_ATTEMPTS-1) Thread.sleep(ACCOUNT_WRITE_BACKOFF[attempt.coerceAtMost(ACCOUNT_WRITE_BACKOFF.lastIndex)])
        }
        return false
    }

    /**
     * 个人信息页要的全部内容：资料 + 四条列表，一次拉齐。
     *
     * 四条列表彼此独立：某一条失败只让它空着、把原因记进 [AccountData.errors]，
     * 其余三条照常显示。资料拉不到（或没配登录态）时 [AccountData.profile] 为 null，
     * 界面据此提示「需要先导入参数」。
     */
    fun accountData(): AccountData {
        // 未导入凭据时抛 [SignInRequiredException]（而不是回一份空数据）：
        // 空数据在界面上与「这个账号真的一条记录都没有」无法区分。
        if(!signedIn) throw SignInRequiredException()
        val errors=java.util.concurrent.ConcurrentHashMap<String,String>()
        fun <T> part(name: String, action: ()->T, fallback: T): T = try { action() } catch(e: Exception) {
            errors[name]=e.message.orEmpty().take(120); fallback
        }
        // 五个任务**并发**发出去：它们彼此独立，串行发要两三秒（每个请求都带签名，
        // 平台首包 200~600ms，个别还要重试）。并发之后整体耗时取决于最慢的那一个。
        val profile=accountPool.submit<AccountProfile?> { part("资料",{accountProfile()},null) }
        val favorites=accountPool.submit<List<AccountFavorite>> { part("收藏",{accountFavorites()},emptyList()) }
        val history=accountPool.submit<List<AccountHistory>> { part("历史",{accountHistory()},emptyList()) }
        val likes=accountPool.submit<List<AccountLike>> { part("点赞",{accountLikes()},emptyList()) }
        val reserves=accountPool.submit<List<AccountReserve>> { part("预约",{accountReserves()},emptyList()) }
        fun <T> take(future: java.util.concurrent.Future<T>, fallback: T): T =
            runCatching { future.get() }.getOrDefault(fallback)
        return AccountData(
            take(profile,null),take(favorites,emptyList()),take(history,emptyList()),
            take(likes,emptyList()),take(reserves,emptyList()),errors.toMap()
        )
    }

    /**
     * 作者 / 版权方：列表接口不带这一项，只有 App 的批量详情接口给。
     *
     * 实测：
     * - 《掀桌2以身入局》→ `author=原来如是`
     * - 《聚宝仙盆之杂灵根才是真BOSS第十四季》→ `author=陈柯文化`
     * - 《持械入宋：第九季》→ `author=瑾叶剧场`
     *
     * `publisher` 实测为空，因此按 `author` → `source` 的顺序取；都没有就返回空串
     *（界面上不显示，不编一个名字）。失败不影响详情：这是附加信息，网络抖动时宁可少一行。
     */
    private fun authorOf(id: String): String = authorsOf(listOf(id))[id].orEmpty()

    /**
     * 批量取作者：`book_id` 支持逗号分隔，一次最多 [MULTI_DETAIL_LIMIT] 个。
     *
     * 首页 / 榜单 / 分类的列表接口都不带作者，只有详情页才有；为了让卡片也能显示，
     * 界面在列表落地之后调一次这个方法（见 `MainActivity.loadAuthors`），
     * 一次请求补齐一整屏，而不是每张卡各发一次。
     *
     * 结果按 id 缓存（包括空结果）：详情页反复进出时不重复请求；
     * 作者 / 版权方基本不会变，缓存 300 条足够覆盖一次浏览。
     */
    fun authorsOf(ids: List<String>): Map<String,String> {
        val wanted=ids.filter { it.matches(Regex("[0-9]{1,30}")) }.distinct()
        if(wanted.isEmpty()) return emptyMap()
        val result=linkedMapOf<String,String>()
        val missing=mutableListOf<String>()
        synchronized(authorCache) {
            wanted.forEach { id ->
                val cached=authorCache[id]
                if(cached==null) missing.add(id)
                else if(cached.isNotBlank()) result[id]=cached
            }
        }
        if(missing.isEmpty()) return result
        missing.chunked(MULTI_DETAIL_LIMIT).forEach { chunk ->
            runCatching {
                val values=appValues()
                values.putAll(mapOf("book_id" to chunk.joinToString(","),"book_type" to "2"))
                val signed=signer.sign("https://$ACCOUNT_HOST/reading/bookapi/multi-detail/v",values)
                val raw=text(signed.url,signed.withSession())
                if(raw.isBlank()) return@runCatching
                val rows=JSONObject(raw).optJSONArray("data") ?: return@runCatching
                for(index in 0 until rows.length()) {
                    val row=rows.optJSONObject(index) ?: continue
                    val id=row.str("book_id").ifEmpty { row.str("series_id") }
                    val name=row.str("author").trim().ifEmpty { row.str("source").trim() }
                    if(id.isNotBlank()) {
                        if(name.isNotBlank()) result[id]=name
                        synchronized(authorCache) {
                            if(authorCache.size>=AUTHOR_CACHE_LIMIT) authorCache.keys.firstOrNull()?.let(authorCache::remove)
                            authorCache[id]=name
                        }
                    }
                }
            }
        }
        return result
    }

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
     */
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val values=appValues()
        values["series_id"]=id
        val signed=signer.sign("https://$VIDEO_HOST/novel/player/video_detail/v1/",values,JSONObject().toString().toByteArray())
        val raw=text(signed.url,signed.withSession())
        // 网关在业务处理前挡掉请求时是 200 + 0 字节（设备没注册也会这样）：先判空再解析。
        if(raw.isBlank()) throw IOException("剧集详情暂不可用（稍后再试）")
        val root=JSONObject(raw)
        if(root.optInt("code",-1)!=0) throw IOException("剧集详情暂不可用（${root.optInt("code")}）")
        val o=root.optJSONObject("data")?.optJSONObject("video_data") ?: throw IOException("剧集详情结构已变化")
        // 未上架的剧（预告 / 预约中）**也是正常响应**：`code 0`、封面简介齐全，
        // 只是 `episode_cnt=0`、`video_list=[]`。早先这里把空分集当异常抛，
        // 用户点开「谁让这个文盲修仙的第六季」就只看到一页错误——现在按预告详情返回。
        val rows=o.optJSONArray("video_list")
        val episodes=rows?.objects()
            ?.sortedBy { it.optInt("vid_index",0) }
            ?.map { it.str("vid") }
            ?.filter { it.matches(Regex("[0-9]{1,30}")) }
            ?.distinct()
            .orEmpty()
        val count=o.optInt("episode_cnt",episodes.size)
        // 有分集却解析不出合法 vid：那是真的数据异常，仍然报错（不能静默变成「预告」）。
        if(episodes.isEmpty() && count>0) throw IOException("分集数据异常")
        val upcoming=episodes.isEmpty()
        // 题材与列表接口同源（`category_schema` 里的 `name`），最多 5 个、 ` · ` 连接。
        val tags=tagsOf(o)
        // 预告文案：`series_sub_title_list` 里就是「预告 / 全0集 / 287.8万人已预约」这几条，
        // 详情接口本身不带「预计N月N日上线」——那一句只有搜索 / 预约列表有，界面自己补。
        val notice=runCatching { JSONArray(o.str("series_sub_title_list")) }.getOrNull()
            ?.objects()?.mapNotNull { it.str("content") }?.firstOrNull { it.contains("预告") }
            .orEmpty().ifEmpty { if(upcoming) "尚未上线" else "" }
        val series=Series(id,o.str("series_title"),cleanUrl(o.str("series_cover")),o.str("series_intro"),
            EpisodeLabel.normalize(o.str("episode_right_text")).ifEmpty { EpisodeLabel.of(count) },
            tags,author=authorOf(id),episodeCount=count,seasonIndex=o.optInt("season_index",0),
            upcoming=upcoming,notice=notice)
        return Detail(series,episodes,upcoming,reserved=o.optBoolean("online_subscribed",false))
    }
    /**
     * 相关作品：App 详情页「相关作品」那一栏的内容，**一次请求拿全**（替代原先那套
     * 早期那套「搜索 + 沿季号逐季搜 + 扫 6 页目录」的召回：实测 2~4s、还要二十多次请求）。
     *
     * ```
     * GET https://{线路}/reading/bookapi/plan/v:version/
     *     ?scene=10&from=detail_page_more_related&book_id={剧集 ID}（见 [relatedRows]）
     * ```
     *
     * 这一栏来自 App 详情页自己的 cell：
     * `data[]` 里 `show_type=547` / `cell_name="相关作品"` 的 `video_data[]`，
     * 每条带 `season_index`（季号）、`tag_info.text`（`第N季` / `同IP`）与完整的卡片字段。
     * 实测一次给全：万妖图录传 26 条（13 季 + 13 部同 IP）、聚宝仙盆 45 条。
     *
     * [title] 是正在看的那一部的剧名（界面手里就有，不用再取一次详情）。
     * 用它按 [SeriesSeasons.normalize] 把同一部剧的各季挑出来——**不能只看 `tag_info`**：
     * 万妖图录传第一季那一页的 `第N季` 组里混着「月落镇魔第二季」这种同 IP 的另一个系列。
     *
     * 同系列各季（按季号升序）排在前面，其余相关作品接在后面、按剧集 ID 去重；
     * 正在看的那一部本身不出现（它自己就是当前页）。接口整轮失败时返回空表，
     * 界面上那一栏就不出现（相关作品只是附加内容，不能影响详情本身）。
     */
    fun related(id: String, title: String = ""): RelatedWorks {
        require(id.matches(Regex("[0-9]{1,30}")))
        val current=title.trim()
        if(current.isNotEmpty()) synchronized(relatedCache) { relatedCache[id]?.let { return it } }
        val rows=runCatching { relatedRows(id) }.getOrNull().orEmpty()
        if(rows.isNotEmpty()) {
            val seasons=SeriesSeasons.siblings(current,rows.map { it.series })
            val seasonIds=seasons.map { it.id }.toSet()
            val others=rows.map { it.series }.filter { it.id!=id && it.id !in seasonIds }
            val works=RelatedWorks(seasons+others,seasons)
            if(current.isNotEmpty()) synchronized(relatedCache) {
                if(relatedCache.size>=RELATED_CACHE_LIMIT) relatedCache.keys.firstOrNull()?.let(relatedCache::remove)
                relatedCache[id]=works
            }
            return works
        }
        // 拿不到就空着：相关作品只是详情页的附加内容，不能影响详情本身。
        return RelatedWorks(emptyList(),emptyList())
    }

    /**
     * 「相关作品」里的一条：卡片 + 平台给的 `tag_info.text`（`第N季` / `同IP`）。
     *
     * [tag] 只当参考：界面上的季号仍由剧名推出来（见 [SeriesSeasons.badge]），
     * 因为同一个值在列表接口里没有、只有这一栏才有，两处得用同一套规则才不会互相打架。
     */
    private data class RelatedEntry(val series: Series, val tag: String)

    /**
     * 取「相关作品」那一栏的原始条目。
     *
     * 请求体（query）按官方 App 的形态给：`scene=10`、`from=detail_page_more_related`、
     * `book_id={剧集 ID}`，外加设备参数与签名（见 [appValues] / [Signer]）。
     * `from` 必须正是 `detail_page_more_related`：换别的取值服务端一律回空响应体。
     *
     * **空响应体是常态**（HTTP 200 + 0 字节，实测约一半概率），与设备是否注册无关：
     * 同一条线路连发几次里总有一次成功，所以这里换线重发、拿到 `code 0` 就返回；
     * 六条线路轮一圈都拿不到才抛出去（[related] 会把那一次失败当成「没有相关作品」）。
     */
    private fun relatedRows(id: String): List<RelatedEntry> {
        var failure: IOException?=null
        for(attempt in 0 until RELATED_ATTEMPTS) {
            val host=relatedHosts[attempt%relatedHosts.size]
            val values=appValues()
            values.putAll(linkedMapOf(
                "scene" to "10","from" to "detail_page_more_related","book_id" to id,
                "new_search_middle_page" to "false","bookstore_tab_type" to "0",
                "query_history_removed" to "false","user_is_login" to if(signedIn) "1" else "0",
                "bookstore_tab" to "0","search_middle_page_version" to "0","pad_column_detail" to "0",
                "hot_word_exchange" to "false","device_level" to "3","limit" to "0","offset" to "0",
                "is_horizontal_screen" to "false","reader_single_col_from_ip_strategy" to "false"
            ))
            try {
                val signed=signer.sign("https://$host/reading/bookapi/plan/v:version/",values)
                val raw=text(signed.url,signed.withSession())
                if(raw.isBlank()) { failure=IOException("相关作品接口暂无响应"); continue }
                val data=JSONObject(raw)
                if(data.optInt("code",-1)!=0) {
                    failure=IOException("相关作品接口暂不可用（${data.optInt("code")}）"); continue
                }
                val rows=parseRelatedRows(data)
                if(rows.isNotEmpty()) return rows
                failure=IOException("相关作品数据为空")
            } catch(e: Exception) {
                failure=e as? IOException ?: IOException(e.message ?: "相关作品请求失败")
            }
        }
        throw failure ?: IOException("相关作品接口暂不可用")
    }
    /** 从「相关作品」响应里取出那一栏的卡片（`title` / `cover` / `video_desc` / `episode_cnt`）。 */
    private fun parseRelatedRows(data: JSONObject): List<RelatedEntry> {
        val cells=data.optJSONArray("data") ?: return emptyList()
        val rows=linkedMapOf<String,RelatedEntry>()
        for(cell in cells.objects()) {
            if(cell.optInt("show_type")!=RELATED_CELL_SHOW_TYPE && cell.str("cell_name")!=RELATED_CELL_NAME) continue
            for(v in (cell.optJSONArray("video_data") ?: JSONArray()).objects()) {
                val seriesId=v.str("series_id")
                val title=v.str("title")
                if(!seriesId.matches(Regex("[0-9]{1,30}")) || title.isBlank()) continue
                val count=v.optInt("episode_cnt")
                // 同一栏里也会出现未上架的那一季：`episode_cnt=0`，`sub_title_list` 里写「预告」。
                // 标成预告态，卡片能正常显示、点进去是预约页而不是错误页。
                val notice=v.optJSONArray("sub_title_list")?.objects()
                    ?.mapNotNull { it.str("content") }?.firstOrNull { it.contains("预告") }.orEmpty()
                if(!rows.containsKey(seriesId)) rows[seriesId]=RelatedEntry(
                    // 相关作品的 `video_data` 也带 `category_schema`：卡片第二行同样显示类型标签。
                    Series(seriesId,title,cleanUrl(v.str("cover")),v.str("video_desc"),
                        EpisodeLabel.of(count),tagsOf(v),episodeCount=count,
                        seasonIndex=v.optInt("season_index",0),upcoming=count<=0,notice=notice),
                    v.optJSONObject("tag_info")?.str("text").orEmpty()
                )
            }
        }
        return rows.values.toList()
    }
    /**
     * 取一集的播放模型（`video_model`）。
     *
     * 平台 2026-10-14 又改了一次播放接口：原先带场景段的
     * `/novel/player/multi_video_model/:dr_scene/v1/` 现在一律返回 **HTTP 200 + 空响应体**，
     * 旧路径 `/novel/player/multi_video_model/v1/` 返回 `Code 110001 未知异常`，
     * 表现出来还是「所有影片都放不了」。现在能用的那条是 `/novel/player/video_model/v1/`，
     * 而且**请求体形态也变了**：要 `video_id` 单值，不再是 `mixed_video_id_map`
     *（给 `mixed_video_id_map` / `vid` / `video_ids` 都返回 `100001 video_id invalid`）。
     *
     * 因此按「新路由 → 带场景段 → 旧路径」的顺序试，第一个能取到 `video_model` 的用；
     * 全失败时抛出最后一个错误，界面照常显示「播放接口暂不可用」。
     *
     * **空响应体必须当失败**：网关在业务处理前挡掉请求时就是 `200 + 0 字节`，
     * 这时业务码取不到、默认成 0，不判空就会被当成成功往下走（0.15.0 那次改版栽的就是这个）。
     *
     * 2026-10-15 平台又加了一道门槛：**匿名请求一律 `200 + 0 字节`**，路径与请求体都没变，
     * 因此五条候选路径会全部走完、最后抛出最后一条的错误码（`110001`），
     * 表现出来还是「所有影片都放不了」。带上账号 Cookie 后同一个请求 15/15 稳定命中 `code 0`，
     * 所以这里给播放请求补上 `Cookie` 头。
     *
     * 补的**只有** `Cookie`：这个接口对 `x-tt-token` 的长度敏感（实测 ≥99 字符直接判死），
     * 而账号凭据那份整段 cookie 串有 800 多字符，同时塞进 `x-tt-token` 会让请求回空
     *（搜索接口容忍这种写法，播放接口不容忍——真 App 用的是 355 字符的 hex token）。
     */
    private fun videoModel(id: String): JSONObject {
        val deviceLevel=this.deviceLevel
        val host="https://api5-normal-sinfonlineb.fqnovel.com"
        // `biz_param` 必须是**对象**，不是序列化后的字符串。
        // 早先这里用字符串插值拼 `{"video_id":"…","biz_param":"{…}"}`，平台直接回
        // `HTTP 400 InvalidRequest: req.BizParam: expected map, actual value: {…} (string)`
        // （2026-10-16 实测）。参考实现（`hongguo-tv/core/cmd/hongguotv/minireel.go`）用的是
        // `map[string]any`，这里改成 `JSONObject` 对齐。
        val biz=JSONObject()
            .put("detail_page_version",0).put("device_level",deviceLevel).put("disable_digg_stat",false)
            .put("need_all_video_definition",true).put("need_mp4_align",false)
            .put("use_os_player",false).put("use_server_dns",false).put("video_platform",1024)
        // 新路由要 `video_id` 单值；带场景段与旧路径要 `mixed_video_id_map`（1004 = 短剧）。
        val videoMap=JSONObject()
            .put("biz_param",JSONObject(biz.toString()))
            .put("mixed_video_id_map",JSONObject().put("1004",JSONArray().put(id)))
            .toString()
        val candidates=listOf(
            "/novel/player/video_model/v1/" to JSONObject()
                .put("video_id",id).put("biz_param",JSONObject(biz.toString())).toString(),
            "/novel/player/multi_video_model/preload/v1/" to videoMap,
            "/novel/player/multi_video_model/default/v1/" to videoMap,
            "/novel/player/multi_video_model/detail/v1/" to videoMap,
            "/novel/player/multi_video_model/v1/" to videoMap
        )
        var failure: IOException?=null
        candidates.forEach { (path,body) ->
            val attempt=runCatching {
                val payload=body.toByteArray()
                // 播放接口对**匿名设备**也放行（官方 App 冷启动未登录即可播放，见 HAR），
                // 前提是这台设备已在服务端注册过（见 [registerDevice]）：未注册回 `101000 服务异常`
                // 或 `200 + 0 字节`，注册后回 `code 0` + 完整播放模型。
                // 因此这里用合并档案（含本机身份，见 [deviceProfile]），并带上注册下发的设备 Cookie；
                // 有账号凭据时以凭据的整段 Cookie 为准。
                // 会话层优先：`sid_guard` 会被心跳续期，凭据对象是登录那一刻的快照。
                val cookie=com.hgtv.star.core.session.SessionBridge.state?.cookieHeader?.takeIf { it.isNotBlank() }
                    ?: credentials?.cookie?.takeIf { it.isNotBlank() }
                    ?: registrationCookies.entries.joinToString("; ") { (name,value) -> "$name=$value" }.takeIf { it.isNotEmpty() }
                var response=""
                var blankRetry=0
                while(true) {
                    val signed=signer.sign(host+path,deviceProfile(),payload)
                    val request=if(cookie==null) signed else signed.copy(headers=signed.headers+linkedMapOf("Cookie" to cookie))
                    response=text(signed.url,request)
                    // 网关挡掉请求时是 200 + 0 字节：业务码取不到会默认成 0，必须先判空。
                    // 空响应先原地重发几次再换下一条：否则会把唯一能用的那条路径也跳过去。
                    if(response.isNotBlank() || blankRetry>=PLAYBACK_RETRIES) break
                    blankRetry++
                    Thread.sleep(200L+blankRetry*200L)
                }
                // 空响应**不**记「被拒」：播放这条路径上它本来就有约一半概率偶发（见 PLAYBACK_RETRIES），
                // 拿它当判据会把好设备的身份反复换掉。真正明确的信号是下面的 `101000`。
                if(response.isBlank()) throw IOException("播放接口暂不可用（空响应）")
                val data=JSONObject(response)
                val code=data.optInt("Code",data.optInt("code",0))
                // 业务码非 0 就是这一条路不通（110001 是旧路径的「未知异常」，100001 是参数错误）。
                // `101000 服务异常` 正是「这台设备没在服务端注册过」那一条，单独记一笔：
                // 界面层据此换一份身份重注册，而不是让用户看到「所有影片都放不了」。
                if(code==101000) noteDeviceRejected()
                if(code!=0) throw IOException("播放接口暂不可用（${code}）")
                videoModelOf(data) ?: throw IOException("播放接口暂不可用（没有播放模型）")
            }
            attempt.getOrNull()?.let { return it }
            failure=attempt.exceptionOrNull() as? IOException ?: IOException(attempt.exceptionOrNull()?.message ?: "播放接口暂不可用")
        }
        throw failure ?: IOException("播放接口暂不可用")
    }
    /**
     * 从播放接口响应里取出 `video_model`：新路由在 `data.video_model`，
     * 带场景段 / 旧路径在 `data[<vid>].video_model`。
     * `video_model` 可能是对象，也可能是**字符串化的 JSON**，两种都要处理。
     */
    private fun videoModelOf(data: JSONObject): JSONObject? {
        val root=data.optJSONObject("data") ?: return null
        model(root.opt("video_model"))?.let { return it }
        val entry=root.keys().asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull() ?: return null
        return model(entry.opt("video_model"))
    }
    private fun model(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val model=videoModel(id)
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityOf(o,k),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
    }
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
    fun danmaku(vid: String, seriesId: String, durationSeconds: Long = 0, shouldStop: () -> Boolean = { false }, onWindow: (List<Danmaku>) -> Unit = {}): List<Danmaku> {
        require(vid.matches(Regex("[0-9]{1,30}")) && seriesId.matches(Regex("[0-9]{1,30}")))
        val durationMs=(durationSeconds.coerceIn(0,4*60*60))*1000
        val collected=linkedMapOf<String,Danmaku>()
        var startMs=0L
        var cursor=""
        // 知道时长就按时长算窗口；不知道时先按上限走——平台的 has_more 会在片尾自己翻成 false，
        // 所以「先多要几个窗口」不会把整部剧拉下来，只是让长剧也能取全。
        val planned=if(durationMs>0) (durationMs+COMMENT_WINDOW_MS-1)/COMMENT_WINDOW_MS+4L else MAX_DANMAKU_WINDOWS.toLong()
        val windows=planned.coerceIn(1L,MAX_DANMAKU_WINDOWS.toLong()).toInt()
        for(index in 0 until windows) {
            if(shouldStop()) break
            val page=runCatching { commentWindow(vid,seriesId,durationMs,startMs,cursor) }.getOrNull() ?: break
            var added=false
            page.items.forEach { item -> val key="${item.offsetMs}:${item.text}"; if(!collected.containsKey(key)) { collected[key]=item; added=true } }
            if(added) onWindow(collected.values.sortedBy { it.offsetMs })
            if(collected.size>=MAX_DANMAKU) break
            if(page.nextStart<=startMs) break
            if(durationMs>0 && page.nextStart>=durationMs) break
            if(durationMs<=0 && !page.hasMore) break
            startMs=page.nextStart; cursor=page.cursor
            // 弹幕接口对匿名设备有频控：连发会返回风控错误，窗口之间留一点间隔。
            if(index<windows-1) runCatching { Thread.sleep(COMMENT_THROTTLE_MS) }
        }
        return collected.values.sortedBy { it.offsetMs }
    }
    /**
     * 调试用：一个窗口的原始 `data_list`。
     *
     * 只看接口到底发了什么字段（表情是不是只有文本一种形式），联调表情表时用，界面不调用。
     */
    fun danmakuRawWindow(vid: String,seriesId: String,startMs: Long=0): List<org.json.JSONObject> {
        val page=commentWindow(vid,seriesId,0,startMs,"")
        return page.raw
    }

    /** 一个 30 秒窗口的弹幕；所有线路都失败时抛 IOException，由 [danmaku] 决定是继续还是停下。 */
    private fun commentWindow(vid: String,seriesId: String,durationMs: Long,startMs: Long,cursor: String): DanmakuPage {
        val body=JSONObject()
            .put("comment_source",COMMENT_SOURCE).put("server_channel",SERVER_CHANNEL)
            .put("group_id",vid).put("group_type",30).put("comment_type",20).put("sort",1)
            .put("business_param",JSONObject().put("book_id",seriesId).put("start_offset_time",startMs)
                .put("playlet_item_duration",durationMs)
                .put("need_danmaku_guide_type",JSONArray(listOf(1,3,4,2))))
            .put("count",COMMENT_COUNT).put("cursor",cursor).put("aid",8662).put("compliance_status",0)
            .toString().toByteArray(Charsets.UTF_8)
        var failure: IOException?=null
        for(host in commentHosts) {
            try {
                val signed=signer.sign("https://$host/novel/commentapi/comment/list/$vid/v1/",appValues(),body)
                val headers=signed.headers.toMutableMap()
                // 这三个头是弹幕接口专用的，签名与普通接口共用同一套 App 参数。
                headers["comment-source"]=COMMENT_SOURCE.toString()
                headers["server-channel"]=SERVER_CHANNEL.toString()
                headers["x-ss-stub"]=""
                val raw=text(signed.url,Signer.Request(signed.url,headers,body))
                if(raw.isBlank()) throw IOException("弹幕接口暂无响应")
                val data=JSONObject(raw)
                if(data.optInt("code",-1)!=0) throw IOException("弹幕接口暂不可用（${data.optInt("code")}）")
                val payload=data.optJSONObject("data") ?: throw IOException("弹幕数据为空")
                val rows=payload.optJSONArray("data_list")
                val items=parseDanmakuRows(rows)
                val info=payload.optJSONObject("common_list_info")
                val next=payload.optJSONObject("extra")?.optLong("next_query_danmaku_list_time",-1L)?.takeIf { it>startMs }
                    ?: (startMs+COMMENT_WINDOW_MS)
                return DanmakuPage(items,next,info?.str("cursor").orEmpty(),info?.optBoolean("has_more",false) ?: false,rawDanmakuRows(rows))
            } catch(e: Exception) {
                failure=e as? IOException ?: IOException(e.message ?: "弹幕请求失败")
            }
        }
        throw failure ?: IOException("弹幕接口暂不可用")
    }
    companion object {
        /**
         * 「服务端不认这份设备身份」的累计次数（进程级）。
         *
         * 为什么放在 companion 而不是实例字段：预取用的是**另一个** [ContentRepository]，
         * 但它与主实例认的是同一台设备，服务端拒绝也是同一件事。按实例各记一份的话，
         * 界面层只看得到主实例那一次，预取被拒就没人处理。
         */
        @Volatile private var deviceRejectedCount: Int = 0
        /** 弹幕窗口固定 30 秒一个，与平台的 `next_query_danmaku_list_time` 一致。 */
        private const val COMMENT_WINDOW_MS=30_000L
        private const val COMMENT_SOURCE=601
        private const val SERVER_CHANNEL=1000
        private const val COMMENT_COUNT=90
        /** 一集最多拉多少个窗口（按 30 秒一窗，40 窗 = 20 分钟）。 */
        private const val MAX_DANMAKU_WINDOWS=40
        /** 一集最多保留多少条弹幕：低端电视的绘制预算，超出直接截断。 */
        private const val MAX_DANMAKU=3000
        /** 搜索第 2 页起每次往后扫几页片库目录（一页 24 部）。 */
        /** 片库目录最多翻到第几页。 */
        private const val MAX_CATALOG_PAGE=100
        /** 窗口之间的最小间隔，避开平台的弹幕频控。 */
        private const val COMMENT_THROTTLE_MS=180L
        /** 接口给的季号文案：`第14季`。只认这一种形状，别把别的角标文案当季号。 */
        private val SEASON_TEXT = Regex("^第\\s*([0-9]{1,3})\\s*季$")

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
