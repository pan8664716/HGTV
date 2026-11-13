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
private fun defaultHttpClient(): OkHttpClient = defaultContentHttpClient()

const val CATALOG_FIRST_PAGE_SIZE=30
class ContentRepository(
    httpClient: OkHttpClient? = null,
    internal val clock: () -> Long = System::currentTimeMillis,
    internal val modelSpacing: Long = MODEL_SPACING_MS,
    internal val modelCacheTtl: Long = MODEL_CACHE_MS
) : RecommendDataSource {
    private val httpRef=lazy(LazyThreadSafetyMode.SYNCHRONIZED) { httpClient ?: defaultHttpClient() }
    val http: OkHttpClient get() = httpRef.value
    internal val recommendStatConfigLock=Any()
    internal var recommendStatConfigLoading=false
    internal var recommendStatConfigNextAt=0L
    internal val officialNormalSessionId="${java.util.UUID.randomUUID()}#1"
    internal val officialColdStartSessionId=java.util.UUID.randomUUID().toString()
    internal val OFFICIAL_RECOMMEND_STREAM_COUNT=
        "[{\"scene\":\"1\",\"StreamCount\":1,\"StreamType\":\"1\"}]"
    internal val OFFICIAL_RECOMMEND_EXTRA=
        "eyJyZWNlbnRfZGlzbGlrZV9naWQiOltdLCJzZXNzaW9uX2FwcF9zdGF5X3RpbWUiOjB9"
    internal val tncHttp by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        http.newBuilder()
            .connectTimeout(4,TimeUnit.SECONDS)
            .readTimeout(6,TimeUnit.SECONDS)
            .callTimeout(8,TimeUnit.SECONDS)
            .build()
    }
    fun hasHttpClient(): Boolean = httpRef.isInitialized()
    fun preconnect() = preconnectImpl()
    fun warmSignature() = warmSignatureImpl()
    internal val signer = Signer()
    internal fun accountHost(): String = TncConfigStore.preferredHost("sinfonlinea")
    internal fun videoHost(): String = TncConfigStore.preferredHost("sinfonlineb")
    internal val ACCOUNT_PRECONNECT_DELAY_MS=2_000L
    private val ACCOUNT_RETRIES = 4
    internal val BOOK_TYPE_WATCH = 2
    internal val VIDEO_SHELF_ADD_SHELF = 0
    internal val VIDEO_SHELF_DEL_SHELF = 1
    var writeLog: (String) -> Unit = {}
    internal val ACCOUNT_WRITE_ATTEMPTS = 4
    internal val ACCOUNT_WRITE_BACKOFF = longArrayOf(1_200L,4_000L,12_000L)
    internal val MULTI_DETAIL_LIMIT = 50
    internal val HISTORY_BOOK_TYPES = listOf(2,3,4)

    internal val HISTORY_DELETE_BATCH = 50
    internal val streamCache=java.util.concurrent.ConcurrentHashMap<String,ContentRepositoryStreamCacheEntry>()
    internal val streamLocks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    internal val modelCache=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,JSONObject>>()
    @Volatile internal var preferredModelPath: String=""
    internal val HISTORY_CLEAR_ATTEMPTS = 3
    private val authorCache=java.util.LinkedHashMap<String,String>()
    internal val authorCacheStore get()=authorCache
    private val AUTHOR_CACHE_LIMIT = 300
    internal val authorCacheLimit get()=AUTHOR_CACHE_LIMIT
    internal val accountPool get()=WorkLanes.fanout.executor
    internal val SUGGEST_SEEDS = listOf("短剧","都市","逆袭","甜宠","战神")
    internal val HOT_WORD_ORDER = listOf("short_play_category","category","common_query","title","album")
    internal val SUGGEST_POOL get()=WorkLanes.fanout.executor
    internal val FALLBACK_HOT = listOf(
        "短剧最热榜前十名","AI短剧","都市逆袭","甜宠","战神归来","穿越剧大全",
        "萌宝助攻","玄幻修仙系列","悬疑","古装","逆袭人生","豪门"
    )
    internal val SITE_HOST: String
        get() = "https://${TncConfigStore.localHost("hongguoduanju.com","hongguoduanju.com")}"
    internal val WEB_SEARCH_PAGE_SIZE = 10
    internal val WEB_SEARCH_BATCH = 10
    internal val WEB_FALLBACK_BATCH = 3
    private val WEB_SEARCH_CONCURRENCY = 3
    internal val WEB_SEARCH_RETRY = 2
    internal val WEB_SEARCH_RETRY_DELAY = 200L
    internal val WEB_SEARCH_QUERY_MAX = 30
    internal val webSearchSeen=linkedMapOf<String,MutableSet<String>>()
    internal val webFallbackCursors=linkedMapOf<String,Int>()
    internal val webSearchNote=ThreadLocal<String>()
    internal data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    internal data class SearchCursorKey(val scope: Int, val keyword: String, val page: Int, val count: Int)
    internal val searchCursors=linkedMapOf<SearchCursorKey,SearchCursor>()
    @Volatile internal var appSearchAlive: Boolean?=null
    @Volatile var sessionToken: String?=null
    @Volatile var credentials: AccountCredentials?=null
        set(value) {
            if(field?.cookie!=value?.cookie) appSearchAlive=null
            if(field?.device!=value?.device) modelCacheGen++
            field=value
            signer.updateDevice(primaryProfile())
        }
    @Volatile var deviceIdentity: Map<String,String> = emptyMap()
        set(value) {
            val clean=value.filterValues { it.isNotBlank() }
            if(clean!=field) {
                appSearchAlive=null
                modelCacheGen++
            }
            field=clean
            signer.updateDevice(primaryProfile())
        }
    fun inheritDevice(from: ContentRepository) {
        credentials=from.credentials
        sessionToken=from.sessionToken
        registerProfile=from.registerProfile
        deviceIdentity=from.deviceIdentity
        registrationCookies=from.registrationCookies
        deviceLevel=from.deviceLevel
    }
    @Volatile var registerProfile: Map<String,String> = emptyMap()
        set(value) { field=value.filterValues { it.isNotBlank() }; signer.updateDevice(primaryProfile()) }
    @Volatile var lastRegisterError: Throwable? = null
        internal set
    val deviceRejected: Int get()=deviceRejectedCountValue
    @Volatile internal var registrationCookies: Map<String,String> = emptyMap()
        set(value) { field=LinkedHashMap(value) }
    private fun deviceProfile(): Map<String,String> = deviceProfileImpl()

    private fun accountDeviceProfile(account: AccountCredentials): Map<String,String> =
        accountDeviceProfileImpl(account)
    internal fun relatedHosts(): List<String> = relatedHostsImpl()
    private val RELATED_ATTEMPTS=8
    internal val relatedAttempts get()=RELATED_ATTEMPTS
    private val RELATED_CELL_SHOW_TYPE=547
    private val RELATED_CELL_NAME="相关作品"
    internal val relatedCellShowType get()=RELATED_CELL_SHOW_TYPE
    internal val relatedCellName get()=RELATED_CELL_NAME
    internal val RELATED_CACHE_LIMIT=16
    internal val relatedCache=linkedMapOf<String,RelatedWorks>()
    internal val AUTHOR_WORKS_CACHE_LIMIT=8
    internal val AUTHOR_WORKS_PAGE_SIZE=50
    internal val AUTHOR_WORKS_PAGES=6
    internal val AUTHOR_WORKS_PAGES_MAX=6
    internal val AUTHOR_WORKS_PAGE_ATTEMPTS=3
    internal val AUTHOR_WORKS_PAGE_RETRY_MS=300L
    internal val AUTHOR_WORKS_EMPTY_PAGES=2
    private val AUTHOR_WORKS_FAMILY_FANOUT=3
    internal val AUTHOR_WORKS_FAMILY_MAX=12
    internal val AUTHOR_WORKS_FAMILY_TIMEOUT_MS=12000L
    internal val authorWorksCache=linkedMapOf<String,AuthorWorksResult>()
    internal val PUBLISHER_PAGE_SIZE=50
    internal val PUBLISHER_PAGES=6
    internal val PUBLISHER_TABS=intArrayOf(11,23,21,16,8)
    internal data class PublisherIdentity(val uid: String, val avatar: String)
    internal val publisherIdentities=linkedMapOf<String,PublisherIdentity>()
    internal val PUBLISHER_UIDS_LIMIT=96
    internal val publisherMisses=linkedMapOf<String,Long>()
    internal val PUBLISHER_MISS_TTL_MS=600000L
    internal fun commentHosts(): List<String> = commentHostsImpl()
    @Volatile var deviceLevel: Int = 4
        set(value) { field = if(value>=5) 5 else 4 }

    internal val CATEGORY_PAGE_SIZE=100

    internal val CATEGORY_FIRST_PAGE_SIZE=CATALOG_FIRST_PAGE_SIZE

    private val RECOMMEND_PAGE_SIZE=20

    internal val LOGICAL_PAGE_SIZE=100
    internal val RANK_FIRST_PAGE_SIZE=CATALOG_FIRST_PAGE_SIZE

    internal val RANK_API_PAGE_SIZE=20

    internal val CATEGORY_ATTEMPTS=8
    internal val FIRST_PAGE_ATTEMPTS=3
    internal val RECOMMEND_ATTEMPTS=3
    internal val SEARCH_BLANK_RETRIES=1
    internal val SEARCH_BLANK_RETRY_DELAY_MS=160L
    internal val SEARCH_PAGE_SIZE=20
    internal val SEARCH_FEED_TAB=11
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
    internal val CATEGORY_VERSION_CODE="73532"
    internal val CATEGORY_VERSION_NAME="7.3.5.32"

    internal fun text(
        url: String,
        signed: Signer.Request? = null,
        client: OkHttpClient = http,
        allowBackup: Boolean = true,
        allowDomainFailover: Boolean = true,
        allowNotModified: Boolean = false,
        storeRegionHeaders: Boolean = true,
        userAgentProfile: Map<String,String>? = null,
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
        request.header("X-SS-DP","8662")
        if(storeRegionHeaders) {
            requestCookies["store-region"]?.takeIf { it.isNotBlank() }?.let { request.header("X-Tt-Store-Region",it) }
            requestCookies["store-region-src"]?.takeIf { it.isNotBlank() }?.let { request.header("X-Tt-Store-Region-Src",it) }
        }
        request.header("X-XS-From-Web","0")
        request.header("Sdk-Version","2")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
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

    internal val PROTOCOL_VALUES = linkedMapOf(
        "ac" to "wifi","aid" to "8662","app_name" to "novelread",
        "channel" to "69310258a","ssmix" to "a",
        "device_platform" to "android","os" to "android","language" to "zh",
        "compliance_status" to "0","need_personal_recommend" to "1","player_so_load" to "1",
        "host_abi" to "arm64-v8a"
    )
    internal val protocolValuesValue get()=PROTOCOL_VALUES
    private fun writeValues(): LinkedHashMap<String,String> = writeValuesImpl()
    internal fun appValues(): LinkedHashMap<String,String> = appValuesImpl()
    internal fun searchValues(): LinkedHashMap<String,String> = searchValuesImpl()
    internal fun searchRequest(values: LinkedHashMap<String,String>): Signer.Request =
        searchRequestImpl(values)
    private fun accountAppValues(): LinkedHashMap<String,String> = accountAppValuesImpl()
    internal fun deviceValues(versionCode: String, versionName: String): LinkedHashMap<String,String> =
        deviceValuesImpl(versionCode, versionName)
    private fun accountValues(versionCode: String, versionName: String): LinkedHashMap<String,String> =
        accountValuesImpl(versionCode, versionName)
    internal fun Signer.Request.withSession(): Signer.Request {
        return withSessionImpl(this@ContentRepository)
    }

    internal fun primaryProfile(): Map<String,String> = primaryProfileImpl()

    internal fun primaryCookieHeader(): String = primaryCookieHeaderImpl()

    private fun Signer.Request.withAccountSession(): Signer.Request {
        return withAccountSessionImpl(this@ContentRepository)
    }
    val signedIn: Boolean get()=credentials?.configured==true || !sessionToken.isNullOrBlank()
    override fun recommendFeed(
        selection: CategorySelection,
        sessionId: String,
        offset: Int,
        limit: Int,
        genres: List<String>,
        filterIds: String,
        tab: RecommendTab,
    ): RecommendFeed = recommendFeedImpl(selection,sessionId,offset,limit,genres,filterIds,tab)

    override fun reportRecommendStats(events: List<RecommendStatData>) =
        reportRecommendStatsImpl(events)

    fun search(keyword: String, page: Int = 1, source: SearchSource = SearchSource.APP, fallbackPages: Int = WEB_FALLBACK_BATCH): CatalogPage =
        searchImpl(keyword,page,source,fallbackPages)
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

    internal fun retryBlank(attempts: Int = ACCOUNT_RETRIES, action: () -> JSONObject): JSONObject {
        return accountTransport.retryBlank(attempts,action)
    }

    fun authorWorks(
        author: String,
        reference: Series? = null,
        pages: Int = AUTHOR_WORKS_PAGES,
        onAvatar: ((String) -> Unit)? = null,
        onPartial: ((List<Series>) -> Unit)? = null,
    ): List<Series> = authorWorksImpl(author,reference,pages,onAvatar,onPartial)

    private fun videoModel(
        id: String,
        deadline: Long,
        playback: PlaybackProfile,
        queue: List<String> = emptyList(),
    ): JSONObject = videoModelImpl(id,deadline,playback,queue)

    internal val ADDRESS_DEADLINE_MS=6_000L
    internal val ADDRESS_MIN_BUDGET_MS=300L
    internal val addressClients=linkedMapOf<Long,OkHttpClient>()
    companion object {
        @Volatile var deviceHeal: ((ContentRepository, String) -> Boolean)? = null
        @Volatile var playbackBackupDevices: (() -> List<PlaybackBackupDevice>)? = null
        @Volatile private var deviceRejectedCount: Int = 0
        internal val deviceRejectedCountValue get()=deviceRejectedCount
        internal fun incrementDeviceRejectedCount() { deviceRejectedCount++ }
        internal val modelGate=java.util.concurrent.Semaphore(1,true)
        internal val foregroundModelWaiters=java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile internal var backgroundModelScope: RequestScope?=null
        internal const val MODEL_GATE_TIMEOUT_MS=25_000L
        internal const val MODEL_SPACING_MS=200L
        internal val modelLastAttemptAt=java.util.concurrent.atomic.AtomicLong(0L)
        internal val deviceRotateLock=Any()
        @Volatile internal var modelCacheGen: Int=0
        internal const val MODEL_CACHE_MS=PlaybackCache.URL_FRESH_MS
        internal const val MODEL_HOST_ATTEMPT_MS=3_000L
        internal const val PLAYER_MODEL_PATH="/novel/player/multi_video_model/player/v1"
        internal const val PLAYER_MODEL_BATCH_LIMIT=15
        internal const val BATCH_MODEL_PATH="/novel/player/multi_video_model/preload/v1"
        internal const val PRELOAD_MODEL_LIMIT=6
        internal const val MODEL_CACHE_LIMIT=32
        internal val MODEL_QUERY_KEYS=listOf(
            "klink_egdi","iid","device_id","ac","channel","aid","app_name",
            "version_code","version_name","device_platform","os","ssmix",
            "device_type","device_brand","language","os_api","os_version",
            "manifest_version_code","resolution","dpi","update_version_code",
            "host_abi","dragon_device_type","pv_player","compliance_status",
            "need_personal_recommend","player_so_load","is_android_pad_screen",
            "rom_version","cdid",
        )
        internal val DEVICE_QUERY_KEYS=(MODEL_QUERY_KEYS + listOf(
            "install_id","device_token","openudid","clientudid","rom","carrier","mcc_mnc",
            "profile-format","openudid-source","apk_first_install_time",
        )).toSet()

        internal const val STREAM_CACHE_MS=PlaybackCache.URL_FRESH_MS
        internal const val STREAM_CACHE_LIMIT=8
        internal const val COMMENT_WINDOW_MS=30_000L
        internal const val COMMENT_SOURCE=601
        internal const val SERVER_CHANNEL=1000
        internal const val COMMENT_COUNT=90
        internal const val MAX_DANMAKU_WINDOWS=40
        internal const val MAX_DANMAKU=3000
        internal const val MAX_CATALOG_PAGE=100
        internal const val COMMENT_THROTTLE_MS=180L

        fun isHevcCodec(codec: String,key: String = ""): Boolean {
            val label=codec.trim().lowercase()
            if(label.isEmpty()) return true
            return when(label) {
                "bytevc1","h265_hvc1","hevc","h265","hvc1","hev1" -> true
                "bytevc2","bvc2","h264","avc","avc1","h263","vp9","av01","mp4v-es" -> false
                else -> key.lowercase().let { !it.contains("bytevc2") && !it.contains("avc") }
            }
        }

        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
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
