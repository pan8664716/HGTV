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

fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
/**
 * 宽松布尔：平台这类开关字段有时是 `true/false`、有时是 `1/0`、有时是 `"1"/"0"` 字符串。
 *
 * `optBoolean` 只认布尔与数字，字符串 `"1"` 会被当成「没有这个字段」而落回默认值——
 * 一个字符串 `online_subscribed` 就足以让简介页永远显示「预约」（用户读到的正是
 * 「明明预约过了，点进去还是预约」）。这里三种形态都接住，缺字段仍然按 `false`。
 */
fun JSONObject.flag(key: String) = when(val value=opt(key)) {
    is Boolean -> value
    is Number -> value.toDouble()!=0.0
    is String -> value.trim().let { it=="1" || it.equals("true",ignoreCase=true) || it.equals("yes",ignoreCase=true) }
    else -> false
}
fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
/** 分类 / 榜单接口的 HTTP 状态异常：4xx（429 除外）说明参数或路由有问题，重试没有意义。 */
internal class CatalogHttpException(val status: Int, message: String) : IOException(message)
/** 取播放地址超时：与用户主动取消分开，界面据此进入自动重试而不是静默退出。 */
class AddressTimeoutException(message: String, cause: Throwable? = null) : IOException(message, cause)
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
private fun defaultHttpClient(): OkHttpClient {
    val builder=HgtvTls.configure(OkHttpClient.Builder())
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS)
        .callTimeout(45,TimeUnit.SECONDS)
        // 会话身份（token / cookie / TokenGuard 回写）。四处 `ContentRepository(...)` 只要有默认
        // client 就都带上；显式传 client 的几个调用点（预取、`FavoriteMonitor` 用业务 client 派生）
        // 由它们各自的 `.newBuilder()` 继承，见 `SessionInterceptor` 的说明。
        .addInterceptor(com.hgtv.star.core.session.SessionInterceptor())
    // 调试包的连接耗时分解（DNS / TCP / TLS / ttfb），正式包不装（见 [NetLog.timing]）。
    if(NetLog.timing) builder.eventListenerFactory(NetTiming.Factory)
    return builder.build()
}

/** 分类 / 榜单页首屏统一取 30 条；后续页再使用大页续接。 */
const val CATALOG_FIRST_PAGE_SIZE=30
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

class ContentRepository(
    /**
     * 传输层：显式传进来就用它（预取 / 预热各带自己的 client），不传则**用到时才建**。
     *
     * 为什么不是「构造时就建」：`MainActivity` 在字段初始化里建了四个 `ContentRepository`
     * （见 `MainActivity.kt` 的 `repository` / `prefetchHttp` 那几处），而那些初始化发生在
     * **主线程**、`onCreate` 之前。建 client 会顺带解析系统根证书表（首次 2~3 秒，
     * 见 `HgtvTls.ACCEPTED_ISSUERS`），于是冷启动还没开始建视图就先卡在那里。
     *
     * 改成懒建之后，第一个真正发请求的地方是 io 线程上的首页 / 推荐取数——那时
     * `HgtvApplication` 的预热线程通常已经把证书表解完了，这里只花一次数组拷贝。
     */
    httpClient: OkHttpClient? = null,
    internal val clock: () -> Long = System::currentTimeMillis,
    /** 播放接口两次请求之间的最小间隔（见 [waitModelSlot]）；测试里传 0，免得真等 3 秒。 */
    private val modelSpacing: Long = MODEL_SPACING_MS,
    /**
     * 播放模型的合并窗口（见 [videoModel]）；测试里传 0 关掉。
     *
     * 缓存在 companion 里（三份实例共用），测试之间会互相污染：同一个 `id@deviceLevel`
     * 在上一个用例里被缓存过，下一个用例就拿到别人的模型，断言全部错位。
     */
    private val modelCacheTtl: Long = MODEL_CACHE_MS
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
    private val tncHttp by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
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
    fun preconnect() {
        // 两条域名各建一条连接：**账号/搜索**走 [accountHost]，**推荐/分类/详情/播放地址**走
        // [videoHost]。推荐首屏那次请求打的是后者，只热前者等于没热（实测那一发仍要自己
        // 握手 1.3 秒）。
        //
        // **两条不再同时发**（2026-10-27 改）：一次 TLS 1.2 握手在这台盒子上的实测是
        // `tcp=447ms tls=406ms`——两跳 RTT 加密钥交换，两个域名同时来就是两条各 ~700ms 的
        // 链接同时挤在一个 CDN 边上，而且这两条都在建界面、初始化解码器的那两秒里。
        // 现在：先热 [videoHost]（下一发就是它），[accountHost] 推后
        // [ACCOUNT_PRECONNECT_DELAY_MS]，而且**只有登录过才发**。
        warmAsync(videoHost())
        // [accountHost] 只在**登录过**才有用武之地：未登录时账号 / 收藏接口一次都不发，
        // 这一次握手就是纯浪费（这台盒子上 ~700ms 的背景流量 + 一个 RTT 的 CDN 配额）。
        // 判据在 2 秒后才读：那时 `Library` 已经加载完，`signedIn` 才是可信的。
        warmAsync(accountHost(),ACCOUNT_PRECONNECT_DELAY_MS) { signedIn }
    }
    private fun warmAsync(host: String,delayMs: Long=0L,needed: ()->Boolean={ true }) {
        val thread=Thread({
            // 低优先级：这台盒子只有两个核，而一次握手除了两跳 RTT 还有一段纯 CPU
            // （ECDHE + 150 张系统根证书的 PKIX 验链）。降到 MIN_PRIORITY（ART 上会落到
            // 后台 nice 档）之后，主线程建视图、解码器初始化都会先拿到 CPU，
            // 握手只是「把剩下的时间用掉」——它只要在建首帧之前完成就行。
            // `core` 是纯 JVM 模块（没有 Android 依赖），因此用 Java 的线程优先级。
            runCatching { Thread.currentThread().priority=Thread.MIN_PRIORITY }
            if(delayMs>0) runCatching { Thread.sleep(delayMs) }
            // 到点了再看还要不要：不需要就**一个包都不发**（见 [preconnect] 里 ACCOUNT 那段）。
            if(!runCatching { needed() }.getOrDefault(true)) {
                NetLog.log("preconnect $host skipped")
                return@Thread
            }
            val began=System.nanoTime()
            warmConnection(host)
            NetLog.log("preconnect $host took=${(System.nanoTime()-began)/1_000_000}ms")
        },"hgtv-preconnect-$host")
        thread.isDaemon=true
        thread.start()
    }
    /**
     * 对某个域名的根路径发一次 `HEAD`，把 DNS + TCP + TLS 全部做完，连接按 keep-alive
     * 留在 [http] 的连接池里。返回码与响应体全部丢弃，失败静默。
     *
     * 只在 [preconnect] 拉起的临时线程上跑，不占用调用方的线程。
     */
    private fun warmConnection(host: String) {
        runCatching {
            val request=Request.Builder()
                .url("https://$host/")
                .head()
                .header("User-Agent",VendorConstants.userAgent(primaryProfile(),video=false))
                .build()
            http.newCall(request).execute().use { it.body?.close() }
        }
    }
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
    fun warmSignature() {
        runCatching {
            val began=System.nanoTime()
            val body=org.json.JSONObject()
                .put("filter_ids","0".repeat(96))
                .put("offset",0)
                .put("limit",40)
                .put("session_id","")
                .put("genre","")
                .toString()
                .toByteArray()
            val valuesAt=System.nanoTime()
            val values=deviceValues(CATEGORY_VERSION_CODE,CATEGORY_VERSION_NAME)
            val signAt=System.nanoTime()
            signer.sign(categoryApiUrl(),values,body,method="POST").withSession()
            NetLog.log(
                "sign warm body=${body.size}B values=${(valuesAt-began)/1_000_000}ms"+
                " sign=${(signAt-valuesAt)/1_000_000}ms total=${(System.nanoTime()-began)/1_000_000}ms"
            )
        }
    }
    internal val signer = Signer()
    /** 账号 / 搜索 / 提示词接口的域名；由 TNC 动态下发，失败时回退内置表。 */
    private fun accountHost(): String = TncConfigStore.preferredHost("sinfonlinea")
    /** 播放侧（详情 / 分集 / 播放地址）的域名；由 TNC 动态下发。 */
    internal fun videoHost(): String = TncConfigStore.preferredHost("sinfonlineb")
    /**
     * [accountHost] 的预热推迟多久（毫秒）。
     *
     * 账号 / 收藏 / 搜索在冷启动里**一个都不在首帧路径上**：未登录时一次都不发，
     * 登录了也只是后台刷一次。而一次 TLS 握手要 450ms 的 CPU，正好撞在建界面 + 建解码器那一段。
     * 推后 2 秒，等那阵忙过去再握手，两者都不耽误。
     */
    private val ACCOUNT_PRECONNECT_DELAY_MS=2_000L
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
     * 界面层启动时把它接到 `android.util.Log`（见 `MainActivity`），不接就是空操作。
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
    private val streamCache=java.util.concurrent.ConcurrentHashMap<String,CachedStream>()
    private val streamLocks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private val modelCache=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,JSONObject>>()
    @Volatile private var preferredModelPath: String=""
    /** 清空历史最多试几轮（删完回读校验，还有余量就再来一轮）。 */
    internal val HISTORY_CLEAR_ATTEMPTS = 3
    /** 作者缓存：详情页反复进出时不重复请求（一条几十毫秒，但没必要）。 */
    private val authorCache=java.util.LinkedHashMap<String,String>()
    /**
     * 作者缓存上限。
     *
     * 只缓存**取到值**的条目：`multi-detail` 对少数剧返回空 `author`，把空串也缓存下来
     * 会让「之后接口补上了」永远读不到；空结果不缓存，下次进详情再问一次即可。
     */
    private val AUTHOR_CACHE_LIMIT = 300
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
     * 热搜词的并发：种子词彼此独立，串行五个要 1.8s，并发之后是单次请求的时间。
     *
     * 走共享的 `fanout` 车道，理由同 [accountPool]。
     */
    private val SUGGEST_POOL get()=WorkLanes.fanout.executor
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
    /** 官网自动降级时先给首屏的页数，后续按实际取到的物理页接着翻。 */
    private val WEB_FALLBACK_BATCH = 3
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
    /** App 搜索降级到官网后，该关键词下一次应当从第几个物理页继续取。 */
    private val webFallbackCursors=linkedMapOf<String,Int>()
    /**
     * 本次官网搜索的「部分页失败」摘要，由 [webSearch] 写、[search] 读。
     *
     * 为什么用 ThreadLocal 而不是返回值：`search` 只认 [CatalogPage]（与 App 搜索同一套
     * 签名），失败摘要不属于列表数据。线程内一进一出，不需要额外加锁。
     */
    private val webSearchNote=ThreadLocal<String>()
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    /**
     * 游标表的键：关键词 + 第几页 + **一页多少条**。
     *
     * 末位那个 `count` 不能省：用户手动搜一个词是 20 条一页，作者作品是 50 条一页
     *（见 [AUTHOR_WORKS_PAGE_SIZE]），两套的 `offset` 完全不同。
     * 若共用同一个键，「作者名恰好也被用户搜过」时就会拿错游标，翻页直接错位。
     */
    private data class SearchCursorKey(val scope: Int, val keyword: String, val page: Int, val count: Int)
    private val searchCursors=linkedMapOf<SearchCursorKey,SearchCursor>()
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
     *
     * **但只去掉 `device_id` 是不够的**（2026-11 实测）：注册主机 `log.snssdk.com` 在
     * `SessionInterceptor` 的会话 host 名单里，它会给这条请求补上
     * `Cookie: install_id=…; sessionid=…` 与 `X-Tt-Token`。服务端按这串 Cookie 认设备，
     * 于是 cdid / openudid / clientudid 全换成新的也照样回 `new_user:0` 与原 `device_id`。
     * 因此 `withIdentity = false` 时额外打一个匿名标记头，让拦截器整条跳过身份补齐。
     */
    fun registerDevice(profile: Map<String,String>? = null,withIdentity: Boolean = true): DeviceRegister.Registered? {
        val device=LinkedHashMap(profile ?: registerProfile).apply { if(withIdentity) putAll(deviceIdentity) }
        val registered=performDeviceRegistration(device,anonymous=!withIdentity)
        if(registered!=null) {
            lastRegisterError=null
            deviceIdentity=linkedMapOf(
                "device_id" to registered.deviceId,
                "iid" to registered.installId,
            ).apply {
                registered.deviceToken.takeIf { it.isNotBlank() }?.let { put("device_token",it) }
                registered.klinkEgdi.takeIf { it.isNotBlank() }?.let { put("klink_egdi",it) }
            }
            registrationCookies=LinkedHashMap(registrationCookies).apply {
                putAll(registered.cookies)
                // 旧身份留下的 `install_id` 必须被这次注册的结果顶掉：两个设备号同时出现，
                // 服务端按陌生设备处理（搜索空响应 / 播放 101000），等于白注册一次。
                if(registered.installId.isNotBlank()) put("install_id",registered.installId)
            }
        }
        return registered
    }

    /**
     * 匿名注册一台备用设备，不改动当前主设备身份与 Cookie。
     *
     * 仅供 [playbackBackupDevices] 对应的持久备用池初始化使用。
     */
    fun registerAnonymousDevice(profile: Map<String,String>): DeviceRegister.Registered? {
        val device=LinkedHashMap(profile).apply {
            remove("device_id")
            remove("iid")
            remove("install_id")
            remove("klink_egdi")
        }
        return performDeviceRegistration(device,anonymous=true)
    }

    private fun performDeviceRegistration(
        device: Map<String,String>,
        anonymous: Boolean,
    ): DeviceRegister.Registered? {
        val body=DeviceRegister.payload(device)
        val headers=DeviceRegister.headers(body,device)
        var lastFailure: Throwable?=null
        val hosts=TncConfigStore.logHosts()
        for((index,host) in hosts.withIndex()) {
            if(index>0) NetLog.retry("device_register",index+1,hosts.size,lastFailure?.message.orEmpty())
            val registered=runCatching {
                val request=Request.Builder().url(DeviceRegister.url(device,host=host))
                headers.forEach { (key,value) -> request.header(key,value) }
                // 换新身份必须匿名：带上 Cookie / token 的话服务端会按旧设备回同一对数字。
                if(anonymous) request.header("X-HGTV-Anonymous","1")
                request.post(body.toRequestBody("application/octet-stream".toMediaType()))
                RequestScope.execute(http.newCall(request.build())) { response ->
                    if(!response.isSuccessful) throw IOException("设备注册失败（HTTP ${response.code}）")
                    val bytes=response.body?.bytes() ?: throw IOException("设备注册响应为空")
                    DeviceRegister.parse(String(bytes,Charsets.UTF_8),response.headers("Set-Cookie"))
                        ?: throw IOException("设备注册被服务端拒绝")
                }
            }.onFailure {
                lastFailure=it
                NetLog.emit("device register host=$host failed ${it.javaClass.simpleName}")
            }.getOrNull()
            if(registered!=null) {
                lastRegisterError=null
                NetLog.log("device register host=$host accepted")
                return registered
            }
        }
        lastRegisterError=lastFailure
        return null
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
    /** 当前主身份 Cookie 快照；登录后是账号设备 Cookie，未登录时是主设备 Cookie。 */
    fun primaryCookiesSnapshot(): Map<String,String> = CookieRecord.headerToMap(primaryCookieHeader())
    /** 把上次落盘的注册 Cookie 灌回来（启动时调一次即可）。 */
    fun restoreDeviceCookies(cookies: Map<String,String>) {
        val clean=cookies.filterValues { it.isNotBlank() }
        if(clean.isEmpty()) return
        registrationCookies=LinkedHashMap(registrationCookies).apply { putAll(clean) }
    }

    /**
     * 后台拉取 TNC。
     *
     * TNC 支持匿名 bootstrap：没有 `device_id` / `iid` 时仍可先拿到域名和 HTTP DNS 配置；
     * 设备注册完成后再次刷新，带注册 Cookie 的响应会补充地区信息。
     */
    fun refreshTncAsync(force: Boolean=false): Boolean =
        TncConfigStore.refreshAsync(force) { summary -> fetchTnc(summary) }

    /** TNC 的 `/get_domains/v5/` 请求；优先带身份，失败后回退匿名 bootstrap。 */
    private fun fetchTnc(summary: String?): TncResponse? {
        val hosts=TncConfigStore.tncHosts()
        if(!primaryProfile()["device_id"].isNullOrBlank() && primaryCookieHeader().isNotBlank()) {
            fetchSignedTnc(summary,hosts)?.let { return it }
        }
        return fetchAnonymousTnc(summary,hosts)
    }

    /** 注册后带设备身份的 TNC 请求，用于获取当前地区的最终配置。 */
    private fun fetchSignedTnc(summary: String?,hosts: List<String>): TncResponse? {
        val profile=modelProfile()
        val cookie=primaryCookieHeader()
        val values=tncValues(profile)
        for(host in hosts) {
            try {
                val signed=signer.sign(
                    "https://$host/get_domains/v5/",
                    values,
                    method="GET",
                    deviceOverride=profile,
                )
                val headers=LinkedHashMap(signed.headers).apply {
                    remove("x-tt-dt")
                    remove("x-tt-request-tag")
                    put("passport-sdk-version","5051451")
                    put("lc","101")
                    put("sdk-version","2")
                    put("x-ss-dp","8662")
                    put("x-vc-bdturing-sdk-version","4.0.3.cn")
                    put("Cookie",cookie)
                    summary?.takeIf { it.isNotBlank() }?.let { put("x-tt-tnc-summary",it) }
                }
                val response=readTncResponse(
                    signed.url,
                    Signer.Request(signed.url,headers,signed.body,"GET"),
                    summary,
                )
                if(response!=null) {
                    NetLog.log(
                        if(response.notModified) "tnc signed host=$host unchanged"
                        else "tnc signed host=$host accepted bytes=${response.raw.length}"
                    )
                    return response
                }
            } catch(e: IOException) {
                if(isInterruption(e) || e.message=="请求已取消") throw e
                NetLog.emit("tnc signed host=$host failed ${e.javaClass.simpleName}")
            } catch(_: Exception) {
                NetLog.emit("tnc signed host=$host failed")
            }
        }
        return null
    }

    /**
     * 无设备身份 TNC bootstrap。
     *
     * 官方实测：最小设备参数即可返回完整配置，不需要 `device_id`、`iid`、Cookie 或签名。
     * `X-HGTV-Anonymous` 只用于阻止本工程的 SessionInterceptor 补身份，发网前会被删除。
     */
    private fun fetchAnonymousTnc(summary: String?,hosts: List<String>): TncResponse? {
        val base=LinkedHashMap(VendorConstants.device).apply {
            putAll(registerProfile)
            remove("device_id")
            remove("iid")
            remove("install_id")
            remove("klink_egdi")
            remove("cdid")
        }
        val values=tncValues(modelQueryProfile(base))
        val query=Signer.encode(values)
        for(host in hosts) {
            try {
                val url="https://$host/get_domains/v5/?$query"
                val headers=linkedMapOf(
                    "User-Agent" to VendorConstants.userAgent(values,video=true),
                    "Accept" to "application/json",
                    "X-SS-DP" to "8662",
                    "lc" to "101",
                    "sdk-version" to "2",
                    "passport-sdk-version" to "5051451",
                    "x-vc-bdturing-sdk-version" to "4.0.3.cn",
                    "X-HGTV-Anonymous" to "1",
                )
                summary?.takeIf { it.isNotBlank() }?.let { headers["x-tt-tnc-summary"]=it }
                val response=readTncResponse(url,Signer.Request(url,headers,null,"GET"),summary)
                if(response!=null) {
                    NetLog.log(
                        if(response.notModified) "tnc anonymous host=$host unchanged"
                        else "tnc anonymous host=$host accepted bytes=${response.raw.length}"
                    )
                    return response
                }
            } catch(e: IOException) {
                if(isInterruption(e) || e.message=="请求已取消") throw e
                NetLog.emit("tnc anonymous host=$host failed ${e.javaClass.simpleName}")
            } catch(_: Exception) {
                NetLog.emit("tnc anonymous host=$host failed")
            }
        }
        return null
    }

    private fun tncValues(profile: Map<String,String>): LinkedHashMap<String,String> =
        LinkedHashMap(profile).apply {
            this["abi"]=this["host_abi"].orEmpty().ifBlank { "arm64-v8a" }
            this["tnc_src"]="1"
            this["okhttp_version"]="4.2.243.17-hongguo"
            this["use_store_region_cookie"]="1"
            // TNC 抓包来自播放器 7.1.3.32；播放取地址的 7.3.9.32 不混进这条配置请求。
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
                this[key]=VendorConstants.VIDEO_VERSION_CODE
            }
            this["version_name"]="7.1.3.32"
        }

    private fun readTncResponse(url: String,signed: Signer.Request,summary: String?): TncResponse? {
        var httpCode=0
        var etag=""
        var tncControl=""
        val raw=text(
            url,
            signed,
            client=tncHttp,
            allowBackup=false,
            // TNC 的空 body 不能走业务域名 fallback；业务域名没有 /get_domains/v5/。
            allowDomainFailover=false,
            allowNotModified=true,
            storeRegionHeaders=false,
        ) { response ->
            httpCode=response.code
            etag=response.header("x-ss-etag").orEmpty()
            tncControl=response.header("x-tt-tnc-control").orEmpty()
        }
        if(raw.isBlank()) {
            if(httpCode==304 || tncControl=="1") {
                return TncResponse(
                    raw="",
                    summary=summary.orEmpty(),
                    etag=etag,
                    notModified=true,
                )
            }
            return null
        }
        val root=runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if(root.optString("message")!="success") return null
        return TncResponse(
            raw=raw,
            summary=root.optString("summary"),
            etag=etag,
        )
    }
    /**
     * 签名与设备参数共用的**三层合并档案**（后一层压前一层）：
     *
     * 1. [VendorConstants.device]：内置档案，兜底用；
     * 2. [deviceIdentity]：本机身份（`device_id` / `iid`），来自 [registerDevice] 的注册结果；
     * 这里只描述未登录时的主设备；登录后请求统一改读 [primaryIdentity] 的账号设备快照。
     */
    private fun deviceProfile(): Map<String,String> =
        LinkedHashMap(VendorConstants.device).apply {
            // 真机档案（机型 / 品牌 / 系统 / 分辨率 / 密度 / `rom_version` …）。
            // **这一层是方案 A 的关键**：注册请求与请求签名共用它，因此
            // query 的 `device_type`、注册 body 的 `device_model`、`x-medusa` 里上报的机型
            // 是同一个值——同一次会话里出现两台设备会被风控按异常客户端处理。
            putAll(registerProfile)
            putAll(deviceIdentity)
        }

    /** 账号设备档案：登录后作为当前主身份。 */
    private fun accountDeviceProfile(account: AccountCredentials): Map<String,String> =
        LinkedHashMap(VendorConstants.device).apply {
            account.device.forEach { (key,value) -> if(value.isNotBlank()) this[key]=value }
        }
    /**
     * 弹幕接口是播放器接口，走 App 域名（与搜索 / 详情同一套设备参数）。
     * 站点有多条线路，单条失败时依次换线，最后一条仍失败才放弃本窗口。
     */
    /**
     * 「相关作品」接口的线路，按实测命中率排序（同一条线路约一半概率回空响应体，
     * 换线重发即可；六条线路轮一圈的成功率接近 1）。
     */
    private fun relatedHosts(): List<String> = TncConfigStore.orderedApiHosts()
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
    private fun commentHosts(): List<String> = TncConfigStore.orderedApiHosts()
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
    fun beginEpisode() { deviceLevel=4 }

    /** 4 档空响应：升到 5 再请求一次；升到 5 后本集不再回退。 */
    private fun escalateDeviceLevel() {
        if(deviceLevel>=5) return
        deviceLevel=5
        NetLog.emit("model device_level 4 -> 5")
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
     * 再失败就交给界面换流或先放出手里这一页（见 `MainActivity.loadRecommendPage`）。
     */
    internal val RECOMMEND_ATTEMPTS=3
    /** 搜索接口空响应的原地补发次数 / 间隔（见 [retryBlankSearch]）。 */
    private val SEARCH_BLANK_RETRIES=1
    internal val SEARCH_BLANK_RETRY_DELAY_MS=160L
    /**
     * App 搜索**一页多少条**（界面侧一页）。
     *
     * 搜索接口的 `count` 是真的（见 [AUTHOR_WORKS_PAGE_SIZE] 的实测表），
     * 但界面侧的翻页手感、缓存与「搜索结果已过期」判定都按 20 这一档验证过，
     * 因此这里保持 20 不动；只有作者作品那一栏自己用 50（[AUTHOR_WORKS_PAGE_SIZE]）。
     */
    private val SEARCH_PAGE_SIZE=20
    /** 官方综合搜索 tab：一次响应包含多个 tab，不再按内容类型分两次请求。 */
    internal val SEARCH_FEED_TAB=11
    /** 综合请求与显式 tab 请求的游标相互隔离，避免探针把界面翻页游标改掉。 */
    private val SEARCH_FEED_SCOPE=-1
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
    private val SEARCH_VERSION_CODE="74332"
    private val SEARCH_VERSION_NAME="7.4.3.32"
    private val SEARCH_PATH="reading/bookapi/search/tab/v"
    internal val CATEGORY_RETRY_DELAYS=longArrayOf(100L,180L,300L,450L,650L,900L,1200L)
    /**
     * 分类 / 榜单接口用的客户端版本，与改动前手拼 URL 里的那组一致（`73532` / `7.3.5.32`）。
     *
     * 这条接口对版本号不敏感（71332 / 72232 / 73532 / 73932 实测都回完整 JSON），
     * 因此保持原值不动，只把「免签」换成签名。
     */
    private val CATEGORY_VERSION_CODE="73532"
    private val CATEGORY_VERSION_NAME="7.3.5.32"

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

    /** 同一套业务参数与设备参数，只换接口域名后重签。 */
    internal fun resignForHost(
        signed: Signer.Request,
        host: String,
        profile: Map<String,String>,
    ): Signer.Request? {
        val parsed=okhttp3.HttpUrl.parse(signed.url) ?: return null
        val values=linkedMapOf<String,String>()
        parsed.queryParameterNames().forEach { name ->
            if(name!="ts" && name!="_rticket") parsed.queryParameter(name)?.let { values[name]=it }
        }
        val base=parsed.newBuilder().scheme("https").host(host).query(null).build().toString()
        val next=signer.sign(base,values,signed.body,signed.method,deviceOverride=profile)
        val preserved=signed.headers.filterKeys {
            it.equals("Cookie",ignoreCase=true) ||
                it.equals("x-tt-token",ignoreCase=true) ||
                it.equals("X-HGTV-Identity",ignoreCase=true) ||
                it.equals("X-HGTV-Anonymous",ignoreCase=true) ||
                it.equals("comment-source",ignoreCase=true) ||
                it.equals("server-channel",ignoreCase=true) ||
                it.equals("x-ss-stub",ignoreCase=true)
        }
        return next.copy(headers=next.headers+preserved)
    }

    internal fun alternateApiHosts(currentHost: String): List<String> =
        TncConfigStore.orderedApiHosts().filterNot { it.equals(currentHost,ignoreCase=true) }
    /** 主设备 200 空响应时，把同一条签名请求临时改签成一台备用设备。 */
    internal fun backupRequest(signed: Signer.Request, backup: PlaybackBackupDevice): Signer.Request? {
        val parsed=okhttp3.HttpUrl.parse(signed.url) ?: return null
        val original=linkedMapOf<String,String>()
        parsed.queryParameterNames().forEach { name ->
            if(name!="ts" && name!="_rticket") parsed.queryParameter(name)?.let { original[name]=it }
        }
        val values=LinkedHashMap(original)
        DEVICE_QUERY_KEYS.forEach(values::remove)
        val fullProfile=LinkedHashMap(VendorConstants.device).apply {
            putAll(backup.profile)
            PROTOCOL_VALUES.forEach { (key,value) -> if(!containsKey(key)) put(key,value) }
            // 客户端版本是接口协议的一部分，沿用原请求，不因换设备而换版本。
            listOf("version_code","version_name","manifest_version_code","update_version_code","pv_player")
                .forEach { key -> original[key]?.let { put(key,it) } }
        }
        DEVICE_QUERY_KEYS.forEach { key ->
            fullProfile[key]?.takeIf { it.isNotBlank() }?.let { values[key]=it }
        }
        val base=parsed.newBuilder().query(null).build().toString()
        val next=signer.sign(
            base,
            values,
            signed.body,
            signed.method,
            deviceOverride=backup.profile,
        )
        val cookies=com.hgtv.star.core.session.CookieRecord.headerToMap(backup.cookieHeader)
        val extra=linkedMapOf(
            "Cookie" to backup.cookieHeader,
            "X-HGTV-Anonymous" to "1",
        )
        cookies["store-region"]?.takeIf { it.isNotBlank() }?.let { extra["X-Tt-Store-Region"]=it }
        cookies["store-region-src"]?.takeIf { it.isNotBlank() }?.let { extra["X-Tt-Store-Region-Src"]=it }
        signed.headers["comment-source"]?.let { extra["comment-source"]=it }
        signed.headers["server-channel"]?.let { extra["server-channel"]=it }
        signed.headers["x-ss-stub"]?.let { extra["x-ss-stub"]=it }
        return next.copy(headers=next.headers+extra)
    }

    /** 无签名请求的备用地址：只替换设备参数，业务参数原样保留。 */
    internal fun backupQueryUrl(url: String,backup: PlaybackBackupDevice): String? {
        val parsed=okhttp3.HttpUrl.parse(url) ?: return null
        val original=linkedMapOf<String,String>()
        parsed.queryParameterNames().forEach { name ->
            if(name!="ts" && name!="_rticket") parsed.queryParameter(name)?.let { original[name]=it }
        }
        val values=LinkedHashMap(original)
        DEVICE_QUERY_KEYS.forEach(values::remove)
        val fullProfile=LinkedHashMap(VendorConstants.device).apply {
            putAll(backup.profile)
            PROTOCOL_VALUES.forEach { (key,value) -> if(!containsKey(key)) put(key,value) }
            listOf("version_code","version_name","manifest_version_code","update_version_code","pv_player")
                .forEach { key -> original[key]?.let { put(key,it) } }
        }
        DEVICE_QUERY_KEYS.forEach { key ->
            fullProfile[key]?.takeIf { it.isNotBlank() }?.let { values[key]=it }
        }
        return parsed.newBuilder().query(null).build().toString()+"?"+
            Signer.encode(values)
    }

    internal fun urlWithHost(url: String,host: String): String {
        if(host.isBlank()) return url
        return okhttp3.HttpUrl.parse(url)?.newBuilder()
            ?.scheme("https")
            ?.host(host)
            ?.build()
            ?.toString()
            ?: url
    }
    /**
     * 协议常量：官方 App 里也是硬编码，不随设备变。
     *
     * 动态化**只针对设备属性**（机型 / 系统 / device_id / cdid …），
     * `aid` / `app_name` 这类跟着官方包走就行。
     */
    private val PROTOCOL_VALUES = linkedMapOf(
        "ac" to "wifi","aid" to "8662","app_name" to "novelread",
        "channel" to "69310258a","ssmix" to "a",
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
    private fun writeValues(): LinkedHashMap<String,String> = accountValues("73932","7.3.9.32")
    /** 搜索 / 弹幕这类 App 接口共用的设备参数：与播放器版本对齐，避免平台按旧版本拒绝。 */
    internal fun appValues(): LinkedHashMap<String,String> = deviceValues("72232","7.2.2.32")
    /** 搜索接口专用：官方抓包里的 7.4.3.32 设备版本与综合搜索上下文。 */
    internal fun searchValues(): LinkedHashMap<String,String> {
        val values=deviceValues(SEARCH_VERSION_CODE,SEARCH_VERSION_NAME)
        values.putAll(SEARCH_FEED_CONTEXT)
        return values
    }
    /** 综合搜索与用户 tab 共用这一份搜索签名。 */
    internal fun searchRequest(values: LinkedHashMap<String,String>): Signer.Request =
        signer.sign("https://${accountHost()}/$SEARCH_PATH",values,method="GET",deviceOverride=values)
    /** 账号页读写接口使用的当前主身份参数。 */
    private fun accountAppValues(): LinkedHashMap<String,String> = accountValues("72232","7.2.2.32")
    /**
     * 设备参数使用 [primaryIdentity] 的单一快照：未登录是主设备，登录后是账号设备。
     * [PROTOCOL_VALUES] 与客户端版本只覆盖协议常量，不改变身份来源。
     */
    private fun deviceValues(versionCode: String, versionName: String): LinkedHashMap<String,String> {
        val values=LinkedHashMap(primaryProfile())
        values.putAll(PROTOCOL_VALUES)
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[key]=versionCode
        values["version_name"]=versionName
        return values
    }
    private fun accountValues(versionCode: String, versionName: String): LinkedHashMap<String,String> {
        val values=LinkedHashMap(primaryProfile())
        values.putAll(PROTOCOL_VALUES)
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[key]=versionCode
        values["version_name"]=versionName
        return values
    }
    /** 普通业务请求也使用当前唯一的主身份；登录后即账号设备。 */
    internal fun Signer.Request.withSession(): Signer.Request {
        val identity=primaryIdentity()
        val extra=linkedMapOf<String,String>()
        if(identity.cookie.isNotBlank()) extra["Cookie"]=identity.cookie
        if(identity.token.isNotBlank()) extra["x-tt-token"]=identity.token
        if(identity.account) extra["X-HGTV-Identity"]="account"
        return if(extra.isEmpty()) this else copy(headers=headers+extra)
    }

    /**
     * 当前唯一的主身份快照。
     *
     * 未登录时是主设备；登录后如果账号设备与主设备不同，以账号设备为准。
     * profile / Cookie / token 必须从同一份快照读取，不能逐项回落到另一套身份。
     */
    internal data class PrimaryIdentity(
        val account: Boolean,
        val profile: Map<String,String>,
        val cookie: String,
        val token: String,
    )

    internal fun primaryIdentity(): PrimaryIdentity {
        val account=credentials?.takeIf {
            it.configured &&
                it.device["device_id"].orEmpty().isNotBlank() &&
                (it.device["iid"].orEmpty().isNotBlank() || it.device["install_id"].orEmpty().isNotBlank())
        }
            ?: return PrimaryIdentity(
                account=false,
                profile=deviceProfile(),
                cookie=com.hgtv.star.core.session.CookieRecord.mapToHeader(registrationCookies),
                token="",
            )
        val live=com.hgtv.star.core.session.SessionBridge.state
        val liveCookie=live?.cookieHeader.orEmpty()
        val liveSid=com.hgtv.star.core.session.CookieRecord.value(liveCookie,"sessionid")
        val accountSid=com.hgtv.star.core.session.CookieRecord.value(account.cookie,"sessionid")
        val sameSession=liveSid.isNotBlank() && liveSid==accountSid
        val cookie=CookieRecord.headerToMap(account.cookie).toMutableMap().apply {
            if(sameSession) putAll(CookieRecord.headerToMap(liveCookie))
        }
        val accountIid=account.device["iid"].orEmpty()
            .ifBlank { account.device["install_id"].orEmpty() }
        if(accountIid.isNotBlank() && cookie["install_id"].isNullOrBlank()) cookie["install_id"]=accountIid
        return PrimaryIdentity(
            account=true,
            profile=accountDeviceProfile(account),
            cookie=CookieRecord.mapToHeader(cookie),
            token=if(sameSession) live?.token.orEmpty().ifBlank { account.token } else account.token,
        )
    }

    /** 所有请求共用的当前主设备档案：登录后以账号设备为准。 */
    internal fun primaryProfile(): Map<String,String> = primaryIdentity().profile

    /** 所有请求共用的当前主 Cookie：登录后只使用账号 Cookie，不再叠加匿名主设备 Cookie。 */
    internal fun primaryCookieHeader(): String = primaryIdentity().cookie

    internal fun recommendationValues(identity: PrimaryIdentity): LinkedHashMap<String,String> =
        LinkedHashMap(identity.profile).apply {
            putAll(PROTOCOL_VALUES)
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
                this[key]=CATEGORY_VERSION_CODE
            }
            this["version_name"]=CATEGORY_VERSION_NAME
        }

    /** 账号页请求显式要求当前主身份必须是登录态。 */
    private fun Signer.Request.withAccountSession(): Signer.Request {
        val identity=primaryIdentity()
        if(!identity.account) throw SignInRequiredException()
        val extra=linkedMapOf<String,String>()
        if(identity.token.isNotBlank()) extra["x-tt-token"]=identity.token
        if(identity.cookie.isNotBlank()) extra["Cookie"]=identity.cookie
        extra["X-HGTV-Identity"]="account"
        return copy(headers=headers+extra)
    }
    /** 有没有可用的登录态：搜索要不要优先走 App 接口、个人信息页要不要联网，都看它。 */
    val signedIn: Boolean get()=credentials?.configured==true || !sessionToken.isNullOrBlank()
    /**
     * 榜单的**一个小页**（[RANK_API_PAGE_SIZE] 条）。
     *
     * 首页那四个剧架每栏只显示 10 条，取 20 条已经足够。
     */
    fun rankRaw(page: Int = 1, board: RankBoard = RankBoard.COMIC): CatalogPage {
        require(page in 1..100)
        return rankApiPage(page,board,RANK_API_PAGE_SIZE)
    }

    /**
     * 榜单页的一页：首次 30 条，后续页按 [LOGICAL_PAGE_SIZE] 条续接。
     *
     * 首屏先给用户 30 条，减少进入页面时的等待；第 2 页从 offset=30 开始，
     * 后续每页 100 条，不会漏条。
     */
    fun rank(page: Int = 1, board: RankBoard = RankBoard.COMIC): CatalogPage {
        require(page in 1..100)
        val size=if(page==1) RANK_FIRST_PAGE_SIZE else LOGICAL_PAGE_SIZE
        val offset=if(page==1) 0 else RANK_FIRST_PAGE_SIZE+(page-2)*LOGICAL_PAGE_SIZE
        return rankApiPage(page,board,size,offset)
    }
    /**
     * 首页四个榜单剧架的第 1 页：四个榜单彼此独立，串行要等四份 RTT，因此并发取。
     *
     * 单个榜单失败只让那一栏不出现（首页其余内容照常上屏），四个都失败才抛错。
     * 取消（切页 / 换标签）沿着 [RequestScope] 传进工作线程，在途的四个请求会一起收手。
     *
     * **不再每次调用新建一个池**：改造前这里是 `newFixedThreadPool(4)` + `finally shutdownNow()`，
     * 每次进首页都创建再销毁四条线程；`shutdownNow()` 还会打断在途请求（被下游的
     * `runCatching` 吞成「这一栏没有内容」）。现在用共享的 `fanout` 车道，
     * 并发度不变，创建/销毁与误打断都没了。
     */
    fun homeFeed(): Map<RankBoard,CatalogPage> {
        val scope=RequestScope.current()
        val pool=WorkLanes.fanout.executor
        val futures=RankBoard.entries.map { board ->
            // 首页每栏只显示 10 条，取 [RANK_API_PAGE_SIZE] 条的小页就够。
            board to pool.submit(java.util.concurrent.Callable { RequestScope.withScope(scope) { runCatching { rankRaw(1,board) }.getOrNull() } })
        }
        val result=linkedMapOf<RankBoard,CatalogPage>()
        futures.forEach { (board,future) -> future.get()?.let { page -> result[board]=page } }
        if(result.values.none { it.items.isNotEmpty() }) throw IOException("热门榜暂不可用")
        return result
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
     * `refresh_other_selector_row=true`）。换体裁时优先读取
     * [CategoryPanelCache]，命中后只请求内容列表。
     */
    fun categoryFeed(selection: CategorySelection, page: Int = 1, needPanel: Boolean = false): CategoryFeed {
        require(page in 1..MAX_CATALOG_PAGE)
        if(needPanel) {
            CategoryPanelCache.load(selection.genre)?.let { panel ->
                return CategoryFeed(categoryApi(selection,page,needPanel=false).page,panel)
            }
        }
        val result=categoryApi(selection,page,needPanel)
        if(needPanel) CategoryPanelCache.save(selection.genre,result.panel)
        return result
    }

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
     * 由界面侧按「游标不前进 = 这条流刷不动了」换新流（见 `MainActivity.loadRecommendPage`）。
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
    fun webSuggestions(keyword: String, count: Int = 10): List<SearchSuggestion> {
        val clean=keyword.trim().take(WEB_SEARCH_QUERY_MAX)
        if(clean.isEmpty()) return emptyList()
        val wanted=count.coerceIn(1,20)
        val url="$SITE_HOST/api/search/suggestion?query=${Signer.encode(clean)}&count=$wanted"
        val raw=runCatching { siteText(url) }.getOrNull().orEmpty()
        if(raw.isBlank()) return emptyList()
        return ContentSearchParser.parseWebSuggestions(raw,wanted)
    }
    private fun appSuggestions(keyword: String, count: Int = 10): List<SearchSuggestion> {
        val clean=keyword.trim()
        if(clean.isEmpty()) return emptyList()
        val wanted=count.coerceIn(1,20)
        val values=searchValues()
        values["q"]=clean
        val signed=signer.sign("https://${accountHost()}/reading/bookapi/search/suggest/v:version/",values,deviceOverride=values)
        val raw=text(signed.url,signed.withSession())
        if(raw.isBlank()) throw IOException("提示词暂不可用")
        return ContentSearchParser.parseAppSuggestions(raw,wanted)
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
     * [source] 决定走哪一套：App 接口（默认，官方综合请求、游标翻页）或官网搜索
     * （免签名免登录，把 10 个物理页并成 1 个逻辑页）。界面侧只认 [CatalogPage]。
     */
    fun search(keyword: String, page: Int = 1, source: SearchSource = SearchSource.APP, fallbackPages: Int = WEB_FALLBACK_BATCH): CatalogPage {
        if(source==SearchSource.APP && isWebFallback(keyword,page)) {
            val result=runCatching { webFallbackResult(keyword,page,fallbackPages) }.getOrNull()
            if(result!=null) {
                SearchDiag.record(keyword,page,SearchSource.WEB,result.items.size,
                    note="App 搜索接口没有数据，已自动改用官网搜索",
                    registered=!deviceIdentity["device_id"].isNullOrBlank(),alive=appSearchAlive,degraded=true)
                return result
            }
        }
        // 每次搜索都在这里留一份现场（见 [SearchDiag]）：失败时面板给的是**判读结论**，
        // 不是页面上那句「没有找到相关剧集」。成功也要记——「0 条」只有配上
        // 「接口真的回答了」才等于「确实没有匹配」。
        //
        // ## 失败时先自愈、再降级
        //
        // 顺序不能反。App 搜索空响应最常见的原因就是「这台设备在服务端没注册过」，
        // 那时正确的动作是**换一套身份重注册再搜一次**；直接退到官网搜索只是绕开了故障，
        // 设备仍然是坏的（播放照样回 `101000`），而且用户再也看不到「搜索能用」以外的修复。
        // 早先这里是先降级官网、成功后直接 return，`DeviceNotRegisteredException` 根本
        // 传不到界面层，电视端的自愈逻辑成了死代码。
        //
        // 自愈只做一次（[healed]），失败之后才走官网兜底；两条都不成，异常照旧抛给界面。
        var healed = false
        while (true) {
            val registered = !deviceIdentity["device_id"].isNullOrBlank()
            try {
                val result = if(source==SearchSource.WEB) webSearch(keyword,page)
                    else appSearchPage(keyword,page,SEARCH_PAGE_SIZE).also { clearWebFallback(keyword) }
                // 官网这条「部分物理页失败」不算整页失败（其余页照常出内容），但要写进判读结论：
                // 否则面板上是「有结果 37 条」加一行 502，用户不知道少掉的那几页去哪了。
                val note = if(source==SearchSource.WEB) webSearchNote.get().orEmpty() else ""
                SearchDiag.record(keyword,page,source,result.items.size,note=note,registered=registered,alive=appSearchAlive)
                return result
            } catch(e: Exception) {
                // ① 设备被拒：换一套身份重注册，然后原地重搜一次。
                if(!healed && source==SearchSource.APP && e is DeviceNotRegisteredException) {
                    healed = true
                    val ok = runCatching { deviceHeal?.invoke(this,"搜索被拒") }.getOrNull()==true
                    if(ok) {
                        NetLog.retry("search_device_heal",2,2,"device rejected; identity rebuilt")
                        NetLog.emit("device healed after search rejection; retrying app search")
                        continue
                    }
                }
                // ② **App 接口「答了但没数据」时自动改走官网**（issue #44）。
                //
                // 现场：`/reading/bookapi/search/tab/v` 回 HTTP 200 + 0 字节，或者回一份没有
                // `search_tabs` 的 JSON。这两种都不是「网络不通」，而是这条接口这一刻不给数据；
                // 官网那条（`/api/search/result`）同一时刻是 200 + 完整 JSON。
                // 早先这里如实抛错，界面就只能整页挡住「接口没有返回任何 tab 数据」，
                // 用户的第一反应是「电视坏了 / 网络坏了」。
                //
                // 只在「接口答了、只是没内容」时降级：真正断网时官网也一样不通，白等一个超时。
                if(source==SearchSource.APP && worthWebFallback(e)) {
                    val fallback=runCatching { webFallback(keyword,page,fallbackPages) }.getOrNull()
                    if(fallback!=null) {
                        SearchDiag.record(
                            keyword,page,SearchSource.WEB,fallback.items.size,
                            note="App 搜索接口没有数据，已自动改用官网搜索",
                            registered=registered,alive=appSearchAlive,degraded=true
                        )
                        return fallback
                    }
                }
                val (reason,detail)=SearchDiag.classify(e)
                SearchDiag.record(keyword,page,source,0,reason,detail,registered,appSearchAlive)
                throw e
            }
        }
    }

    /**
     * 这次失败值不值得换官网再试一次。
     *
     * 判据是「接口**答了**，只是没给内容」：设备没注册（空响应体）、响应里没有
     * `search_tabs`。游标过期不算——那是回到第 1 页重搜的事，换源只会把页码搞乱；
     * 网络类失败也不算——官网走的是同一根网线，换过去只会再等一次超时。
     */
    private fun worthWebFallback(e: Exception): Boolean = when(e) {
        is DeviceNotRegisteredException -> true
        is SearchApiException ->
            e.reason==SearchDiag.FAILURE_DEVICE || e.reason==SearchDiag.FAILURE_FORMAT
        else -> false
    }
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
    fun webSearch(keyword: String,page: Int = 1): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..MAX_CATALOG_PAGE)
        // 服务端只收 30 个字符（更长的回 400）：这里先截断，宁可少两个字的查询，
        // 也不要让用户看到「官网搜索没有结果」。
        val clean=keyword.trim().take(WEB_SEARCH_QUERY_MAX)
        // 上一次搜索留下的摘要不能带到这一次（同一个线程会被复用）。
        webSearchNote.set("")
        val first=(page-1)*WEB_SEARCH_BATCH+1
        return webSearchPages(clean,page,first,WEB_SEARCH_BATCH)
    }
    /** App 搜索自动降级：首屏少拉几页，后续逻辑页按实际物理页游标连续取。 */
    private fun webFallback(keyword: String,page: Int,batch: Int): CatalogPage {
        val clean=keyword.trim().take(WEB_SEARCH_QUERY_MAX)
        val wantedPages=batch.coerceIn(1,WEB_SEARCH_BATCH)
        val first=synchronized(webFallbackCursors) {
            if(page==1 || !webFallbackCursors.containsKey(clean)) 1 else webFallbackCursors.getValue(clean)
        }
        val result=webSearchPages(clean,page,first,wantedPages)
        synchronized(webFallbackCursors) {
            if(webFallbackCursors.size>=8) webFallbackCursors.keys.firstOrNull()?.let(webFallbackCursors::remove)
            webFallbackCursors[clean]=first+wantedPages
        }
        return result
    }
    private fun clearWebFallback(keyword: String) {
        val clean=keyword.trim().take(WEB_SEARCH_QUERY_MAX)
        synchronized(webFallbackCursors) { webFallbackCursors.remove(clean) }
    }
    private fun isWebFallback(keyword: String,page: Int): Boolean = page>1 &&
        synchronized(webFallbackCursors) { webFallbackCursors.containsKey(keyword.trim().take(WEB_SEARCH_QUERY_MAX)) }
    private fun webFallbackResult(keyword: String,page: Int,batch: Int): CatalogPage = webFallback(keyword,page,batch)
    private fun webSearchPages(clean: String,page: Int,first: Int,wantedPages: Int): CatalogPage {
        // 上一次搜索留下的摘要不能带到这一次（同一个线程会被复用）。
        webSearchNote.set("")
        val scope=RequestScope.current()
        // 与 [homeFeed] 同理：共享 `fanout` 车道，不再每次搜索新建一个 10 线程池
        // （那等于「每敲一次键盘就创建再销毁十条线程」）。
        val pool=WorkLanes.fanout.executor
        val tasks=(first until first+wantedPages).map { physical ->
            pool.submit(java.util.concurrent.Callable {
                // 取消（切页 / 重新搜索）沿着 [RequestScope] 传进这十条请求，一起收手。
                RequestScope.withScope(scope) { runCatching { webSearchPageRetry(clean,physical) }.getOrNull() }
            })
        }
        val parts=tasks.map { task -> runCatching { task.get() }.getOrNull() }
        // 十个物理页全部失败才算这一页失败：部分失败时把拿到的那些照常给界面。
        // 「查不到」不走这里：接口对没有结果的关键词回的是 200 + 空 `searchList`，
        // 因此走到这一句就是真的连不上（或后端整体不可用），该让界面给「重试」。
        if(parts.all { it==null }) {
            webSearchNote.set("官网 ${wantedPages} 个物理页全部失败")
            NetLog.log("hongguoduanju.com/api/search/result 第${first}~${first+wantedPages-1}页全部失败")
            throw SearchApiException(SearchDiag.FAILURE_NETWORK,"官网 ${wantedPages} 个物理页全部失败")
        }
        // 部分页失败：**只汇总一条**，不把每个 502 都写进面板（那会把 40 条近况刷满，
        // 看起来像「整个官网搜索坏了」）。逐条的 HTTP 状态已在 [webSearchPageRetry] 里静默。
        val failed=parts.count { it==null }
        if(failed>0) {
            val note="官网搜索 ${failed}/${wantedPages} 页重试后仍失败"
            webSearchNote.set(note)
            NetLog.log("hongguoduanju.com/api/search/result 第${first}~${first+wantedPages-1}页 · $note")
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
        val more=parts.filterNotNull().lastOrNull()?.hasMore==true && first+wantedPages-1<MAX_CATALOG_PAGE
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
                val delay=WEB_SEARCH_RETRY_DELAY*attempt
                NetLog.retry("web_search",attempt+1,WEB_SEARCH_RETRY+1,last?.message.orEmpty(),delay)
                try {
                    Thread.sleep(delay)
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
        val failure=last ?: IOException("官网搜索暂不可用")
        NetLog.error("[API] error scope=web_search attempts=${WEB_SEARCH_RETRY+1} reason=${failure.message.orEmpty().take(220)}")
        throw failure
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
        return ContentSearchParser.parseWebSearchPage(raw,page,WEB_SEARCH_PAGE_SIZE,MAX_CATALOG_PAGE)
    }
    /**
     * 官网页面的一次 GET：不走 App 的签名与设备参数（那套头对官网没有意义），
     * 只带一个普通浏览器的 UA 与语言。
     */
    private fun siteText(url: String,retries: Int = 0): String {
        var last: Throwable? = null
        repeat(retries+1) { attempt ->
            if(attempt>0) NetLog.retry("site_search",attempt+1,retries+1,last?.message.orEmpty())
            val request=Request.Builder().url(url)
                .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept-Language","zh-CN,zh;q=0.9")
                // 官网的 JSON 接口：不要 HTML，也不要缓存中间层给的那一份。
                .header("Accept","application/json")
                .get().build()
            try {
                return RequestScope.execute(http.newCall(request)) { response ->
                    val body=response.body ?: throw IOException("内容响应为空")
                    if(body.contentLength()>16*1024*1024) throw IOException("内容响应过大")
                    // 502 时响应体里仍然带着 `{"code":"upstream_unavailable"}`：读出来交给
                    // 调用方判读，比只丢一句「HTTP 502」有用得多。
                    val raw=body.string()
                    if(!response.isSuccessful && raw.isBlank()) throw IOException("内容请求失败（HTTP ${response.code}）")
                    raw
                }
            } catch(problem: Throwable) {
                last=problem
            }
        }
        val failure=last ?: IOException("内容请求失败")
        NetLog.error("[API] error scope=site_search attempts=${retries+1} reason=${failure.message.orEmpty().take(220)}")
        throw failure
    }
    /**
     * 搜索：只走 App 搜索接口（`GET /reading/bookapi/search/tab/v`，参数全在 query 里）。
     *
     * 官方 7.4.3.32 用一次不带 `tab_type` 的综合请求返回全部 tab，取首个有数据的 tab；
     * 第 2 页起带上一页的游标（`search_id` / `passback` / `next_offset`），见 [appSearch]。
     *
     * 失败语义：接口回空响应体 = 这台设备没在服务端注册过（见 [registerDevice]），
     * 如实抛错让界面提示，**不再**用别的数据源顶替；游标过期抛 [SearchSessionExpiredException]，
     * 由界面回到第 1 页重搜。
     */
    internal fun search(keyword: String, page: Int, types: List<ContentType>, count: Int = SEARCH_PAGE_SIZE): CatalogPage {
        require(types.isNotEmpty())
        return appSearchPage(keyword,page,count)
    }

    /** 官方形态的一次综合搜索。 */
    internal fun appSearchPage(keyword: String,page: Int,count: Int): CatalogPage =
        appSearch(keyword,page,SEARCH_FEED_TAB,count)
    /**
     * 搜索接口空响应时的原地补发（见 [appSearch]）。
     *
     * 最多补 [SEARCH_BLANK_RETRIES] 次、每次隔 [SEARCH_BLANK_RETRY_DELAY_MS]。
     * 间隔不能省：实测同一条请求连发（0 间隔）几乎全空，短暂停顿后才有命中。
     */
    internal fun retryBlankSearch(raw: String, signed: Signer.Request): String {
        var current=raw
        repeat(SEARCH_BLANK_RETRIES) { attempt ->
            if(current.isNotBlank()) return current
            NetLog.retry(
                "search_blank",
                attempt+1,
                SEARCH_BLANK_RETRIES,
                "HTTP 200 empty body",
                SEARCH_BLANK_RETRY_DELAY_MS,
            )
            try {
                Thread.sleep(SEARCH_BLANK_RETRY_DELAY_MS)
            } catch(e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("请求已取消",e)
            }
            current=text(signed.url,signed.withSession())
        }
        return current
    }

    private fun appSearch(keyword: String, page: Int, tabType: Int, count: Int = SEARCH_PAGE_SIZE): CatalogPage {
        val scope=if(tabType==SEARCH_FEED_TAB) SEARCH_FEED_SCOPE else tabType
        val signed=searchRequest(searchQuery(keyword,page,tabType,count))
        val tab=ContentSearchParser.parseTab(searchResponse(signed),tabType,SEARCH_FEED_TAB)
        return CatalogPage(ContentSearchParser.cards(tab),tab.hasMore && page<100).also {
            synchronized(searchCursors) {
                searchCursors[SearchCursorKey(scope,keyword,page,count)]=
                    SearchCursor(tab.searchId,tab.passback,tab.nextOffset,clock())
                while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
            }
        }
    }
    /** 官方综合搜索不带 `tab_type`；用户 / 类型专用查询继续显式指定。 */
    private fun searchQuery(keyword: String,page: Int,tabType: Int,count: Int): LinkedHashMap<String,String> {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        val scope=if(tabType==SEARCH_FEED_TAB) SEARCH_FEED_SCOPE else tabType
        val values=searchValues()
        values.putAll(linkedMapOf("query" to keyword,"count" to count.toString(),"offset" to ((page-1)*count).toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        if(tabType!=SEARCH_FEED_TAB) values["tab_type"]=tabType.toString()
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 ||
                (page==1 && it.key.scope==scope && it.key.keyword==keyword) }
            searchCursors[SearchCursorKey(scope,keyword,page-1,count)]
        }
        if(page>1 && cursor==null && appSearchAlive!=false) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        return values
    }
    /**
     * 发送搜索 GET，并处理空响应 / `100103`。空响应补发一次仍空才判设备未注册；
     * 这样保留设备自愈路径，又不会在高峰期原地退避很多次。
     */
    private fun searchResponse(signed: Signer.Request): String {
        var raw=retryBlankSearch(text(signed.url,signed.withSession()),signed)
        if(raw.isBlank()) { appSearchAlive=false; noteDeviceRejected(); throw DeviceNotRegisteredException() }
        var attempt=0
        while(attempt<3 && raw.startsWith("""{"code":100103""")) {
            attempt++
            NetLog.retry("search_100103",attempt,3,"platform returned code=100103",120L)
            Thread.sleep(120L)
            raw=retryBlankSearch(text(signed.url,signed.withSession()),signed)
            if(raw.isBlank()) { appSearchAlive=false; noteDeviceRejected(); throw DeviceNotRegisteredException() }
        }
        appSearchAlive=true
        return raw
    }
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

    internal fun authorOf(id: String): String = authorsOf(listOf(id))[id].orEmpty()
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
                val signed=signer.sign("https://${accountHost()}/reading/bookapi/multi-detail/v",values)
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
        return ContentCatalogParser.heatValue(raw)
    }
    /**
     * 两个作者名是不是同一家。
     *
     * 只忽略空白与大小写：`multi-detail` 与列表接口给的是同一个字段（`copyright` → `author`），
     * 差异只可能出在两端空格与全角 / 半角上，做模糊匹配反而会把「同 IP」的别家作品混进来。
     */
    internal fun sameAuthor(left: String,right: String): Boolean {
        fun norm(value: String)=value.filterNot { it.isWhitespace() }.lowercase()
        return norm(left).isNotEmpty() && norm(left)==norm(right)
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
        fun norm(value: String)=value.filterNot { it.isWhitespace() }.lowercase()
        val name=norm(left)
        return name.isEmpty() || name==norm(right)
    }

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
    internal fun relatedRows(id: String): List<RelatedEntry> {
        var failure: IOException?=null
        val hosts=relatedHosts()
        for(attempt in 0 until RELATED_ATTEMPTS) {
            val host=hosts[attempt%hosts.size]
            if(attempt>0) {
                NetLog.retry("related",attempt+1,RELATED_ATTEMPTS,failure?.message.orEmpty())
            }
            val values=appValues()
            values.putAll(linkedMapOf(
                "scene" to "10","from" to "detail_page_more_related","book_id" to id,
                "new_search_middle_page" to "false","bookstore_tab_type" to "0",
                "query_history_removed" to "false","user_is_login" to "0",
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
                val rows=ContentDetailParser.parseRelatedRows(
                    data,
                    RELATED_CELL_SHOW_TYPE,
                    RELATED_CELL_NAME,
                )
                if(rows.isNotEmpty()) return rows
                failure=IOException("相关作品数据为空")
            } catch(e: Exception) {
                failure=e as? IOException ?: IOException(e.message ?: "相关作品请求失败")
            }
        }
        throw failure ?: IOException("相关作品接口暂不可用")
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
    ): JSONObject {
        // 长时间驻留进程里配置也会过期；这里只排队后台刷新，不等待。
        refreshTncAsync()
        // TNC 下发候选域名；最近成功的排第一。播放接口最多在 6 秒预算内试前三条。
        val hosts=TncConfigStore.orderedApiHosts().take(3)
        // 播放器打开用 `multi_video_model/player` 取当前集 + 后 14 集；
        // 单集接口只作为兼容兜底，preload 留给首帧稳定后的后台补货。
        // 新匿名设备连续发单集请求时，第二发起会被网关静默丢弃（HTTP 200 + 0 字节）。
        val candidates=listOf(
            "/novel/player/video_model/v1/",
            "/novel/player/multi_video_model/v1/"
        )
        // 播放接口对**匿名设备**也放行（官方 App 冷启动未登录即可播放，见 HAR），
        // 前提是这台设备已在服务端注册过（见 [registerDevice]）：未注册回 `101000 服务异常`
        // 或 `200 + 0 字节`，注册后回 `code 0` + 完整播放模型。
        // 播放与其它业务请求共用当前主身份；登录后 Cookie 也切到账号设备。
        val cookie=primaryCookieHeader().takeIf { it.isNotEmpty() }
        // 记住的那条路径排到最前（见 [preferredModelPath]）。
        val ordered=if(preferredModelPath.isEmpty()) candidates
            else candidates.sortedByDescending { it==preferredModelPath }
        val scope=RequestScope.current()
        // 同一集刚取过就复用：推荐页预热、正片起播、下一集预取会**同时**要同一集的地址，
        // 各发一轮就是三份并发（见下面的单飞闸门）。缓存很短（[MODEL_CACHE_MS]），
        // 地址本身小时级有效，这里只是用来把「同一刻的重复请求」合并掉。
        // 键里带播放档案、[deviceLevel] 与 [modelCacheGen]：不同类型 / 平台不能复用模型。
        cachedModel(id,playback)?.let { return it }
        // **进程级单飞**：这条接口对并发极敏感（20 并发 0 命中、单发 50~80% 命中），
        // 而点一次播放就可能同时存在主实例 / 预取实例 / 推荐页预热实例三份请求。
        // 因此整个取地址段串行化：后来的等前一个结束，然后再查一次上面那份缓存。
        foregroundModelWaiters.incrementAndGet()
        // 前台开始取当前集时，后台 preload 立即让路：取消它在途请求并释放闸门，
        // 不让用户点下一集时排在后台补货后面。
        backgroundModelScope?.cancel()
        try {
            val gate=modelGate
            // 等闸门也用余额兜住：三份实例同时起播时后来者要排队，但绝不允许排到整段预算之外
            //（[MODEL_GATE_TIMEOUT_MS] 那 25 秒是给「上一发正在慢速完成」留的余量，
            // 取地址整段已经被 [ADDRESS_DEADLINE_MS] 限死，这里只是个不会先到期的兜底）。
            val waitMs=remainingOf(deadline).coerceIn(1L,MODEL_GATE_TIMEOUT_MS)
            if(!gate.tryAcquire(waitMs,TimeUnit.MILLISECONDS)) {
                throw addressTimeout()
            }
            try {
                cachedModel(id,playback)?.let { return it }
                // 播放器打开取当前集 + 后 14 集：官方同一批可返回 15 个模型，
                // 覆盖短剧常见的快速跳到第 8 / 15 集，避免第二次请求撞设备额度。
                // 更远的集交给首帧稳定后的 preload。
                val batchIds=(listOf(id)+queue)
                    .filter { it.matches(Regex("[0-9]{1,30}")) }
                    .distinct()
                    .take(PLAYER_MODEL_BATCH_LIMIT)
                var batchModel: JSONObject?=null
                for((hostIndex,host) in hosts.withIndex()) {
                    if(hostIndex>0) {
                        NetLog.retry("model_batch",hostIndex+1,hosts.size,"previous host returned no model")
                    }
                    if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) break
                    val hostDeadline=minOf(deadline,clock()+MODEL_HOST_ATTEMPT_MS)
                    try {
                        val models=tryBatchModel(
                            "https://$host",
                            PLAYER_MODEL_PATH,
                            "player",
                            batchIds,
                            cookie,
                            hostDeadline,
                            playback,
                        )
                        rememberModels(models,playback)
                        batchModel=models[id]
                        if(batchModel!=null) {
                            TncConfigStore.markHostSuccess(host)
                            NetLog.emit("model batch host=$host accepted")
                            break
                        }
                    } catch(e: DeviceNotRegisteredException) {
                        throw e
                    } catch(e: AddressTimeoutException) {
                        NetLog.emit("model batch host=$host timeout")
                    } catch(e: IOException) {
                        NetLog.emit("model batch host=$host failed ${e.javaClass.simpleName}")
                    }
                }
                if(batchModel!=null) return batchModel
                val model=fetchModel(id,scope,ordered,hosts,cookie,deadline,playback)
                rememberModel(id,playback,model)
                return model
            } finally {
                gate.release()
            }
        } finally {
            foregroundModelWaiters.decrementAndGet()
        }
    }

    /** 播放模型的合并缓存键：集 + 播放档案 + 当前档位 + 身份代次（见 [videoModel]）。 */
    private fun modelCacheKey(id: String,playback: PlaybackProfile)=
        "$id@${playback.contentType}:${playback.videoPlatform}@$deviceLevel@g$modelCacheGen"

    private fun cachedModel(id: String,playback: PlaybackProfile): JSONObject? {
        if(modelCacheTtl<=0L) return null
        val key=modelCacheKey(id,playback)
        val cached=modelCache[key] ?: return null
        if(System.currentTimeMillis()-cached.first<=modelCacheTtl) return cached.second
        modelCache.remove(key)
        return null
    }

    private fun rememberModel(
        id: String,
        playback: PlaybackProfile,
        model: JSONObject,
        at: Long = System.currentTimeMillis(),
    ) {
        if(modelCacheTtl<=0L) return
        val key=modelCacheKey(id,playback)
        if(modelCache.size>=MODEL_CACHE_LIMIT && !modelCache.containsKey(key)) {
            modelCache.minByOrNull { it.value.first }?.key?.let(modelCache::remove)
        }
        modelCache[key]=at to model
    }

    private fun rememberModels(models: Map<String,JSONObject>,playback: PlaybackProfile) {
        if(models.isEmpty() || modelCacheTtl<=0L) return
        val at=System.currentTimeMillis()
        models.forEach { (videoId,model) -> rememberModel(videoId,playback,model,at) }
    }

    /**
     * [videoModel] 里真正打请求的那一段（持锁执行，见那里的 `modelGate`）。
     *
     * 平台只接受**注册下发的 `device_id`**。空响应先按额度/档位问题处理：
     * 4 档升 5 重发一次；5 档仍为空才视为设备身份失效，交给界面层重注册。
     */
    private fun fetchModel(
        id: String,
        scope: RequestScope?,
        ordered: List<String>,
        hosts: List<String>,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
    ): JSONObject {
        var failure: IOException?=null
        hostLoop@ for((hostIndex,host) in hosts.withIndex()) {
            if(hostIndex>0) {
                NetLog.retry("model_host",hostIndex+1,hosts.size,failure?.message.orEmpty())
            }
            val hostDeadline=minOf(deadline,clock()+MODEL_HOST_ATTEMPT_MS)
            for((pathIndex,path) in ordered.withIndex()) {
                if(pathIndex>0) {
                    NetLog.retry("model_path",pathIndex+1,ordered.size,"previous path returned no model")
                }
                // 余额用完就停：不要再往下试下一条路径 / 域名。
                if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) {
                    throw failure ?: addressTimeout()
                }
                val model=try {
                    RequestScope.withScope(scope) {
                        tryModelPath("https://$host",path,id,cookie,hostDeadline,playback)
                    }
                } catch(e: IOException) {
                    // 用户切走了（取消域）：这不是「这条路不通」，不该再往下试。
                    if(isInterruption(e) || e.message=="请求已取消") throw e
                    // 设备身份失效与路径 / 域名无关：换一条只会再被拒一次。
                    if(e is DeviceNotRegisteredException) throw e
                    failure=e
                    if(e is AddressTimeoutException) continue@hostLoop
                    null
                }
                if(model!=null) {
                    preferredModelPath=path
                    TncConfigStore.markHostSuccess(host)
                    NetLog.emit("model host=$host path=$path accepted")
                    return model
                }
            }
        }
        // 全失败：把记住的那条也作废，下一次从候选表第一条重新开始——
        // 否则记着的那条死路会一直排在队首，每次起播先白花一次往返。
        preferredModelPath=""
        throw failure ?: IOException("播放接口暂不可用")
    }

    /**
     * 在一条路径上请求一次播放模型。
     *
     * 空响应先由 [probeModelPath] 升到 5 档重试；仍为空才抛出
     * [DeviceNotRegisteredException]，让两端现有的设备自愈流程重新注册。
     */
    private fun tryModelPath(
        host: String,
        path: String,
        id: String,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
    ): JSONObject {
        if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        return probeModelPath(host,path,id,cookie,deadline,playback)
    }

    /** 播放请求使用当前唯一的主身份；登录后以账号设备为准。 */
    private fun modelProfile(): Map<String,String> {
        val identity=primaryIdentity()
        val registeredId=if(identity.account) {
            identity.profile["device_id"].orEmpty()
        } else {
            deviceIdentity["device_id"].orEmpty()
        }
        val registeredIid=if(identity.account) {
            identity.profile["iid"].orEmpty()
                .ifBlank { identity.profile["install_id"].orEmpty() }
        } else {
            deviceIdentity["iid"].orEmpty()
        }
        // 未登录时必须由本机注册结果提供身份，不能拿 VendorConstants.device 的内置旧 ID
        // 冒充已注册设备。登录态则必须有账号设备自己的 device_id + iid/install_id。
        if(registeredId.isBlank() || registeredIid.isBlank()) {
            throw DeviceNotRegisteredException()
        }
        val base=LinkedHashMap(VendorConstants.device).apply {
            putAll(identity.profile)
            put("device_id",registeredId)
            put("iid",registeredIid)
            putAll(PROTOCOL_VALUES)
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) this[key]="73932"
            this["version_name"]="7.3.9.32"
        }
        return modelQueryProfile(base)
    }

    /** 官方 `video_model` query 的字段集合与顺序；设备档案里的其它字段不带进播放请求。 */
    private fun modelQueryProfile(values: Map<String,String>): LinkedHashMap<String,String> =
        linkedMapOf<String,String>().apply {
            for(key in MODEL_QUERY_KEYS) values[key]?.takeIf { it.isNotBlank() }?.let { put(key,it) }
        }

    /**
     * 主设备拿到 `HTTP 200 + 空 body` 后，只取一台备用设备试一次。
     *
     * 只返回成功的播放模型，不改主设备身份、不落盘备用设备的使用结果。
     */
    private fun tryBackupModel(
        host: String,
        path: String,
        id: String,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
    ): JSONObject? {
        val backups=runCatching { playbackBackupDevices?.invoke() }.getOrNull().orEmpty()
        for((index,backup) in backups.withIndex()) {
            if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) break
            if(index>0) {
                NetLog.retry("model_backup",index+1,backups.size,"previous backup device returned no model")
            }
            try {
                val model=probeModelPath(host,path,id,cookie,deadline,playback,backup)
                NetLog.emit("model backup accepted id=${backup.id.takeLast(6)}")
                return model
            } catch(e: IOException) {
                if(isInterruption(e) || e.message=="请求已取消") throw e
            }
        }
        return null
    }

    /**
     * 作废当前设备签发的播放模型缓存。
     *
     * 重试时设备号本身不能伪造，只能等界面层重新注册；这里只保证下一次取流
     * 不会命中旧身份留下的模型。
     */
    private fun invalidateModelCache(): String {
        synchronized(deviceRotateLock) { modelCacheGen++ }
        return deviceIdentity["device_id"].orEmpty()
    }

    /**
     * 在 [path] 上打**一次**播放模型请求。
     *
     * 2026-11-06 实测：同一个请求用注册下发的 `device_id` 返回 `code 0` 和完整模型，
     * 改成任意随机 15 位数字则稳定返回 `200 + 0 字节`。因此空响应先升 5 档重发；
     * 5 档仍为空时再按设备身份失效处理，交给界面层重新注册。
     */
    private fun probeModelPath(
        host: String,
        path: String,
        id: String,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
        backup: PlaybackBackupDevice? = null,
    ): JSONObject {
        // 这一发最多能用多少时间 = 整段剩余预算（见 [ADDRESS_DEADLINE_MS]）。
        // 全部用同一份「剩余预算」派生 client，因此预算是**整段**上限，不是每一发各算一次。
        val budget=remainingOf(deadline)
        if(budget<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        val body=ContentPlaybackParser.modelBody(id,path,playback,deviceLevel)
        val payload=java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(body.toByteArray()) }
        }.toByteArray()
        waitModelSlot()
        if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        // 注册档案包含大量仅供 device_register 使用的字段。播放接口只接受
        // MODEL_QUERY_KEYS 这一组；整份档案直接交给 signer 会把 openudid、
        // profile-format、device_token 等全部拼进 query，服务端随即回 200 + 0 字节。
        val profile=backup?.profile?.let { backupProfile ->
            modelQueryProfile(LinkedHashMap(VendorConstants.device).apply {
                putAll(backupProfile)
                putAll(PROTOCOL_VALUES)
                for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) this[key]="73932"
                this["version_name"]="7.3.9.32"
            })
        } ?: modelProfile()
        val effectiveCookie=backup?.cookieHeader ?: cookie
        // 档案要同时交给 query 与密文（`x-medusa` 里编了 device_id），否则服务端回 110001。
        val signed=signer.sign(host+path,profile,payload,deviceOverride=profile)
        val extra=linkedMapOf<String,String>()
        if(effectiveCookie!=null) extra["Cookie"]=effectiveCookie
        extra["Content-Encoding"]="gzip"
        // 视频接口按官方 7.3.9.32 抓包单独覆盖：
        // * passport-sdk-version=5051452（不是内容接口的 6031190）
        // * lc=101 / x-ss-dp=8662
        // * 官方没有空 x-tt-dt，也没有 x-tt-request-tag
        val videoHeaders=LinkedHashMap(signed.headers).apply {
            remove("x-tt-dt")
            remove("x-tt-request-tag")
            put("passport-sdk-version","5051452")
            put("lc","101")
            put("x-ss-dp","8662")
            put("x-vc-bdturing-sdk-version","4.0.3.cn")
            put("x-tt-trace-id",VendorConstants.traceId())
            putAll(extra)
        }
        val request=signed.copy(headers=videoHeaders)
        var stable=false
        val response=text(
            signed.url,
            request,
            client=addressClient(budget),
            storeRegionHeaders=false,
        ) { r -> stable=r.header("tt_stable")=="1" }
        // 网关挡掉请求时是 200 + 0 字节：业务码取不到会默认成 0，必须先判空。
        if(response.isBlank()) {
            NetLog.emit("model path=$path type=${playback.contentType}/${playback.videoPlatform} level=$deviceLevel id=${profile["device_id"]?.takeLast(6)} backup=${backup!=null} bytes=0 stable=$stable")
            if(backup!=null) throw IOException("备用设备未返回播放模型")
            // 主设备空响应先用备用设备顶替；备用池不可用时再走 4→5 档与身份自愈。
            tryBackupModel(host,path,id,cookie,deadline,playback)?.let { return it }
            // 4 档空响应先升 5 重发一次。若 5 仍为空，再按设备身份失效交给自愈流程。
            if(deviceLevel<5) {
                NetLog.retry("model_device_level",2,2,"empty model response at device_level=$deviceLevel")
                escalateDeviceLevel()
                return probeModelPath(host,path,id,cookie,deadline,playback)
            }
            noteDeviceRejected()
            invalidateModelCache()
            throw DeviceNotRegisteredException("播放接口暂不可用（设备身份已失效）")
        }
        val data=JSONObject(response)
        val code=data.optInt("Code",data.optInt("code",0))
        NetLog.emit("model path=$path type=${playback.contentType}/${playback.videoPlatform} level=$deviceLevel id=${profile["device_id"]?.takeLast(6)} backup=${backup!=null} bytes=${response.length} code=$code")
        // 业务码非 0 就是这一条路不通（110001 是旧路径的「未知异常」，100001 是参数错误）。
        // `101000 服务异常` 正是「这台设备没在服务端注册过」那一条，单独记一笔：
        // 界面层据此换一份身份重注册，而不是让用户看到「所有影片都放不了」。
        if(code==101000 && backup==null) { noteDeviceRejected(); invalidateModelCache() }
        if(code!=0) {
            if(code==101000 && backup!=null) throw IOException("备用设备不可用")
            throw IOException("播放接口暂不可用（${code}）")
        }
        val model=ContentPlaybackParser.videoModelOf(data)
            ?: throw IOException("播放接口暂不可用（没有播放模型）")
        return model
    }

    /**
     * 打一次官方批量播放模型请求，并解析 `data.<video_id>.video_model`。
     *
     * 批量接口的空响应先升到 5 档重发一次；5 档仍为空才按设备身份失效处理。
     * 非空但业务失败时由调用方走单集兼容兜底。
     */
    private fun tryBatchModel(
        host: String,
        path: String,
        scene: String,
        ids: List<String>,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
        backup: PlaybackBackupDevice? = null,
    ): Map<String,JSONObject> {
        if(ids.isEmpty()) return emptyMap()
        val budget=remainingOf(deadline)
        if(budget<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        val body=ContentPlaybackParser.batchModelBody(ids,playback,scene,deviceLevel)
        val payload=java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(body.toByteArray()) }
        }.toByteArray()
        waitModelSlot()
        if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        val profile=backup?.profile?.let { backupProfile ->
            modelQueryProfile(LinkedHashMap(VendorConstants.device).apply {
                putAll(backupProfile)
                putAll(PROTOCOL_VALUES)
                for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) this[key]="73932"
                this["version_name"]="7.3.9.32"
            })
        } ?: modelProfile()
        val effectiveCookie=backup?.cookieHeader ?: cookie
        val signed=signer.sign(host+path,profile,payload,deviceOverride=profile)
        val extra=linkedMapOf<String,String>()
        if(effectiveCookie!=null) extra["Cookie"]=effectiveCookie
        extra["Content-Encoding"]="gzip"
        val videoHeaders=LinkedHashMap(signed.headers).apply {
            remove("x-tt-dt")
            remove("x-tt-request-tag")
            put("passport-sdk-version","5051452")
            put("lc","101")
            put("x-ss-dp","8662")
            put("x-vc-bdturing-sdk-version","4.0.3.cn")
            put("x-tt-trace-id",VendorConstants.traceId())
            putAll(extra)
        }
        val request=signed.copy(headers=videoHeaders)
        var stable=false
        val response=text(
            signed.url,
            request,
            client=addressClient(budget),
            storeRegionHeaders=false,
        ) { r -> stable=r.header("tt_stable")=="1" }
        if(response.isBlank()) {
            NetLog.emit("model batch path=$path scene=$scene type=${playback.contentType}/${playback.videoPlatform} level=$deviceLevel id=${profile["device_id"]?.takeLast(6)} backup=${backup!=null} bytes=0 stable=$stable")
            if(backup!=null) throw IOException("备用设备未返回批量播放模型")
            tryBackupBatch(host,path,scene,ids,cookie,deadline,playback)?.let { return it }
            if(deviceLevel<5) {
                escalateDeviceLevel()
                return tryBatchModel(host,path,scene,ids,cookie,deadline,playback)
            }
            noteDeviceRejected()
            invalidateModelCache()
            throw DeviceNotRegisteredException("播放接口暂不可用（设备身份已失效）")
        }
        val data=JSONObject(response)
        val code=data.optInt("Code",data.optInt("code",0))
        val models=ContentPlaybackParser.parseBatchModels(data,ids)
        NetLog.emit("model batch path=$path scene=$scene type=${playback.contentType}/${playback.videoPlatform} level=$deviceLevel id=${profile["device_id"]?.takeLast(6)} backup=${backup!=null} bytes=${response.length} code=$code models=${models.size}/${ids.size}")
        if(code==101000 && backup==null) {
            noteDeviceRejected()
            invalidateModelCache()
            throw DeviceNotRegisteredException("播放接口暂不可用（设备身份已失效）")
        }
        if(code!=0) {
            if(code==101000 && backup!=null) throw IOException("备用设备不可用")
            throw IOException("播放接口暂不可用（${code}）")
        }
        return models
    }

    private fun tryBackupBatch(
        host: String,
        path: String,
        scene: String,
        ids: List<String>,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
    ): Map<String,JSONObject>? {
        val backups=runCatching { playbackBackupDevices?.invoke() }.getOrNull().orEmpty()
        for(backup in backups) {
            if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) break
            try {
                val models=tryBatchModel(host,path,scene,ids,cookie,deadline,playback,backup)
                if(models.isNotEmpty()) {
                    NetLog.emit("model batch backup accepted id=${backup.id.takeLast(6)} models=${models.size}/${ids.size}")
                    return models
                }
            } catch(e: IOException) {
                if(isInterruption(e) || e.message=="请求已取消") throw e
            }
        }
        return null
    }

    /**
     * 两次播放模型请求之间至少隔 [MODEL_SPACING_MS]，**进程级**节流。
     *
     * 必须先持有 `modelGate`（见 [videoModel]）：只有全局串行时这个时间戳才说得清
     * 「上一次是什么时候打的」。第一次不等待（时间戳初值 0）。
     *
     * 这里只挡异常连打，不承担设备额度控制；身份失效由重注册处理。
     */
    private fun waitModelSlot() {
        if(modelSpacing<=0L) return
        val wait=modelLastAttemptAt.get()+modelSpacing-System.currentTimeMillis()
        if(wait>0L) {
            try {
                Thread.sleep(wait)
            } catch(e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("请求已取消")
            }
        }
        modelLastAttemptAt.set(System.currentTimeMillis())
    }
    /**
     * 用一次官方批量接口预取一组播放模型。
     *
     * 推荐页与正式播放页的批量窗口由界面层决定；这里只负责把整组 ID 放进同一次请求，
     * 并把响应写进共享模型缓存，后续 [stream] 直接命中。
     */
    fun prefetchModels(
        ids: List<String>,
        playback: PlaybackProfile = PlaybackProfile(),
    ): Int {
        val batchIds=ids
            .filter { it.matches(Regex("[0-9]{1,30}")) }
            .distinct()
            // preload 只补当前缓存缺口，避免把 player 已经拿到的当前集 / 后 2 集再请求一遍。
            .filter { cachedModel(it,playback)==null }
            .take(PRELOAD_MODEL_LIMIT)
        if(batchIds.isEmpty()) return 0
        val deadline=clock()+ADDRESS_DEADLINE_MS
        val gate=modelGate
        val waitMs=remainingOf(deadline).coerceIn(1L,MODEL_GATE_TIMEOUT_MS)
        if(!gate.tryAcquire(waitMs,TimeUnit.MILLISECONDS)) {
            throw addressTimeout()
        }
        val scope=RequestScope()
        try {
            // 前台已经在等模型时，后台这次直接跳过；下次首帧稳定后的调度会再补。
            backgroundModelScope=scope
            if(foregroundModelWaiters.get()>0) return 0
            val cookie=primaryCookieHeader().takeIf { it.isNotEmpty() }
            val host=TncConfigStore.preferredHost()
            val models=RequestScope.withScope(scope) {
                tryBatchModel(
                    "https://$host",
                    BATCH_MODEL_PATH,
                    "preload",
                    batchIds,
                    cookie,
                    deadline,
                    playback,
                )
            }
            if(models.isNotEmpty()) TncConfigStore.markHostSuccess(host)
            rememberModels(models,playback)
            return models.size
        } finally {
            if(backgroundModelScope===scope) backgroundModelScope=null
            gate.release()
        }
    }

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
    ): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        // 同一集在两条路上同时被要时（起播 + 预热）只真正取一次：后来的那条等在锁上，
        // 前一条取回来之后它直接吃缓存。见 [STREAM_CACHE_MS]。
        val cacheKey=cacheKeyOf(id,maxQuality,playback)
        val lock=synchronized(streamLocks) { streamLocks.getOrPut(cacheKey) { Any() } }
        synchronized(lock) {
            val hit=synchronized(streamCache) { streamCache[cacheKey] }
            if(hit!=null && clock()-hit.at<STREAM_CACHE_MS) return hit.info
            // 落盘缓存：冷却启动（进程被杀）、退出再进、同一集重播都走这一条。
            //
            // 取一次地址实测 500~1100ms（`net POST … wait=892ms` 是服务端），而这
            // 500~1100ms 在「已经看过这一集」的所有场景里都是白付的：地址在有效期内
            // 逐字节相同。这里只缓存 [PlaybackCache.URL_FRESH_MS] 内的那一份，
            // 过期或 403 就重新取（见 `RemoteVideo.range` 与 [forgetStream]）。
            PlaybackCache.loadUrl(cacheKey)?.let { cached ->
                if(NetLog.timing) NetLog.log("stream cache hit id=${id.takeLast(6)} quality=${cached.info.quality} age=${(System.currentTimeMillis()-cached.at)/1000}s")
                rememberStream(cacheKey,cached.info,clock())
                return cached.info
            }
            // **整个取地址阶段的硬上限**（见 [ADDRESS_DEADLINE_MS]）：上限从这一刻算起，
            // 之后每一次请求都只用「剩余预算」当自己的超时，余额用完立刻抛。
            // 缓存命中不走这里，因此点「下一个」十几毫秒就能起播的那条路一分钱都不多花。
            val deadline=clock()+ADDRESS_DEADLINE_MS
            val info=try {
                fetchStream(id,maxQuality,deadline,playback,queue)
            } catch(e: AddressTimeoutException) {
                throw e
            } catch(e: IOException) {
                if(e.isAddressTimeout()) throw addressTimeout(e)
                throw e
            }
            rememberStream(cacheKey,info,clock())
            PlaybackCache.saveUrl(cacheKey,info)
            // 锁表跟着缓存一起收：这里大多是「取完就没人在等」的一次性键，
            // 不收的话每换一部剧就多留一个对象，长刷推荐会一直涨。
            synchronized(streamLocks) { if(streamLocks.size>STREAM_CACHE_LIMIT*4) streamLocks.remove(cacheKey) }
            return info
        }
    }
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
    private val ADDRESS_DEADLINE_MS=6_000L
    /** 余额低于这个值就不再发请求：几百毫秒不可能完成建连 + TLS + 服务端处理。 */
    private val ADDRESS_MIN_BUDGET_MS=300L
    /**
     * 按「剩余预算」派生的取地址 client：连接 / 读 / 写 / 整调用四个超时全部压到余额。
     *
     * 用 `http.newBuilder()` 派生，因此**共用同一个连接池与 dispatcher**——
     * 取地址与分片读视频仍然是同一批连接，不会因为换 client 而多握手一次。
     * 按 100ms 分桶缓存：每一发都 `newBuilder().build()` 会不停复制拦截器链，
     * 而余额在 6 秒内的取值本来就只有六十个。
     */
    private fun addressClient(budgetMs: Long): OkHttpClient {
        val bucket=(((budgetMs.coerceIn(ADDRESS_MIN_BUDGET_MS,ADDRESS_DEADLINE_MS))+99)/100)*100
        synchronized(addressClients) {
            addressClients[bucket]?.let { return it }
            val client=http.newBuilder()
                .connectTimeout(bucket,TimeUnit.MILLISECONDS)
                .readTimeout(bucket,TimeUnit.MILLISECONDS)
                .writeTimeout(bucket,TimeUnit.MILLISECONDS)
                .callTimeout(bucket,TimeUnit.MILLISECONDS)
                .build()
            if(addressClients.size>=8) addressClients.keys.firstOrNull()?.let(addressClients::remove)
            addressClients[bucket]=client
            return client
        }
    }
    private val addressClients=linkedMapOf<Long,OkHttpClient>()
    /** 这一轮取地址还剩多少毫秒预算。 */
    private fun remainingOf(deadline: Long)=deadline-clock()
    /**
     * 预算用完时抛的那个异常。
     *
     * 用独立的 [AddressTimeoutException]：它不能伪装成 [java.io.InterruptedIOException]，
     * 否则会被界面侧当成「用户切页造成的取消」静默丢掉。超时与取消必须分开：
     * 前者要清地址 / DNS / 连接池后自动重来，后者什么都不该做。
     */
    private fun addressTimeout(cause: Throwable? = null): AddressTimeoutException =
        AddressTimeoutException("取播放地址超过 ${ADDRESS_DEADLINE_MS}ms",cause)

    /** OkHttp 的 callTimeout / readTimeout 或本仓库的预算耗尽都算地址超时。 */
    private fun Throwable.isAddressTimeout(): Boolean {
        var current: Throwable?=this
        var depth=0
        while(current!=null && depth<8) {
            if(current is AddressTimeoutException || current is java.net.SocketTimeoutException) return true
            if(current is java.io.InterruptedIOException &&
                current.message.orEmpty().contains("timeout",ignoreCase=true)) return true
            current=current.cause
            depth++
        }
        return false
    }
    /**
     * 内存 / 落盘缓存与锁表的键：集 id + 请求上限 + 播放档案 + `device_level`。
     */
    private fun cacheKeyOf(id: String,maxQuality: Int,playback: PlaybackProfile): String =
        "$id:$maxQuality:${playback.contentType}:${playback.videoPlatform}:$deviceLevel"
    private fun rememberStream(key: String,info: StreamInfo,at: Long) {
        synchronized(streamCache) {
            if(streamCache.size>=STREAM_CACHE_LIMIT) streamCache.keys.firstOrNull()?.let(streamCache::remove)
            streamCache[key]=CachedStream(info,at)
        }
    }
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
    fun forgetStream(id: String,maxQuality: Int = 1080,playback: PlaybackProfile = PlaybackProfile()) {
        val key=cacheKeyOf(id,maxQuality,playback)
        synchronized(streamCache) { streamCache.remove(key) }
        PlaybackCache.invalidateUrl(key)
        // 「这条地址不认了」= 下一次取地址当成一次全新尝试，作废旧模型缓存。
        rotateDeviceForRetry()
    }

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
    fun forgetStreamAllQualities(id: String) {
        synchronized(streamCache) {
            streamCache.keys.filter { it.startsWith("$id:") }.forEach(streamCache::remove)
        }
        PlaybackCache.invalidateUrlById(id)
        rotateDeviceForRetry()
    }

    /**
     * 用户侧「再试一次」时作废当前设备签发的播放模型缓存。
     *
     * 设备号只能由平台注册下发；身份失效时由 `DeviceHealer` 重新注册。
     * 这里保留原方法名供两端调用，但不再伪造新的 `device_id`。
     */
    fun rotateDeviceForRetry(): String = invalidateModelCache()

    /** 真正去取播放地址（[stream] 的缓存之外那一半）。[deadline] 见 [ADDRESS_DEADLINE_MS]。 */
    private fun fetchStream(
        id: String,
        maxQuality: Int,
        deadline: Long,
        playback: PlaybackProfile,
        queue: List<String>,
    ): StreamInfo {
        val model=videoModel(id,deadline,playback,queue)
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        // 这一步同样是「取地址」的一部分：模型给了 `fallback_api`，但真正带 `video_list`
        // 的是它返回的那份 JSON。余额用完就直接抛，别让它在 45 秒的默认超时上再挂一次。
        val budget=remainingOf(deadline)
        if(budget<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        val info=JSONObject(text(fallback,client=addressClient(budget),storeRegionHeaders=false))
            .optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        return ContentPlaybackParser.parseStreamInfo(model,info,maxQuality)
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
        for(host in commentHosts()) {
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
        /**
         * 取播放地址的**进程级单飞闸门**（见 [videoModel]）。
         *
         * 这条接口对并发极敏感：同一台设备单发 50~80% 命中、20 并发 0 命中（2026-10-27 实测）。
         * 而一次点播可能同时有主实例 / 下一集预取 / 推荐页预热三份请求，各发一轮就是三份并发。
         * 拿它串行化之后，同时到达的请求变成「一个人打、其余等结果」，成功率回到单发那一档。
         */
        private val modelGate=java.util.concurrent.Semaphore(1,true)
        /** 正在等待 / 执行前台播放模型的调用数；preload 见到正数就让路。 */
        private val foregroundModelWaiters=java.util.concurrent.atomic.AtomicInteger(0)
        /** 当前在途的后台 preload；前台起播时直接 cancel。 */
        @Volatile private var backgroundModelScope: RequestScope?=null
        /** 等闸门的上限：给正在完成的那一发留足余量。 */
        private const val MODEL_GATE_TIMEOUT_MS=25_000L
        /**
         * 两次播放模型请求之间的最小间隔（进程级，见 [waitModelSlot]）。
         *
         * 只留一个很小的值挡「异常循环里的连打」；身份失效由重注册处理。
         */
        private const val MODEL_SPACING_MS=200L
        /** 上一次打播放接口的时刻（0 = 本次进程还没打过，第一次不等待）。 */
        private val modelLastAttemptAt=java.util.concurrent.atomic.AtomicLong(0L)
        /** 保护播放模型缓存代次，避免并发重试时重复或漏掉作废。 */
        private val deviceRotateLock=Any()
        /**
         * 「身份代次」：身份重注册或地址重试时 +1，并进 [videoModel] 的缓存键。
         *
         * 没有它，重试会命中 60 秒的 [MODEL_CACHE_MS] 模型缓存——那份模型里带着
         * **旧身份签发的 `fallback_api` 地址**，于是重注册后还会拿到同一条死地址。
         */
        @Volatile private var modelCacheGen: Int=0
        /**
         * 播放模型的合并窗口。
         *
         * 必须覆盖当前批 6 集的消费时间；缓存太短会在同一台新匿名设备上再次请求批量接口，
         * 而该设备的第一批额度已经用完。地址本身与 [PlaybackCache.URL_FRESH_MS] 同窗。
         */
        private const val MODEL_CACHE_MS=PlaybackCache.URL_FRESH_MS
        /** 播放器打开时批量取当前集 + 后 14 集；preload 只在首帧稳定后补后 6 集。 */
        /** 单个播放模型域名的预算；整段 6 秒内至少还能换到下一域名。 */
        private const val MODEL_HOST_ATTEMPT_MS=3_000L
        private const val PLAYER_MODEL_PATH="/novel/player/multi_video_model/player/v1"
        private const val PLAYER_MODEL_BATCH_LIMIT=15
        private const val BATCH_MODEL_PATH="/novel/player/multi_video_model/preload/v1"
        private const val PRELOAD_MODEL_LIMIT=6
        private const val MODEL_CACHE_LIMIT=32
        /** 官方 `video_model` query 的字段顺序；设备档案里的其它字段不进入播放请求。 */
        private val MODEL_QUERY_KEYS=listOf(
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
        private val DEVICE_QUERY_KEYS=(MODEL_QUERY_KEYS + listOf(
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
        private const val STREAM_CACHE_MS=PlaybackCache.URL_FRESH_MS
        private const val STREAM_CACHE_LIMIT=8
        /** 两条路同时要同一集时，后来的那条等在锁上（见 [stream]）。 */
        private data class CachedStream(val info: StreamInfo,val at: Long)
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
        internal const val MAX_CATALOG_PAGE=100
        /** 窗口之间的最小间隔，避开平台的弹幕频控。 */
        private const val COMMENT_THROTTLE_MS=180L
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
