// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

    /**
     * 账号资料。
     *
     * 两个接口一起用，因为信息是分开的（实测）：
     * * `user/info`（不带参数 = 当前登录用户）——昵称 / 头像 / **平台号**（字段名是 `req_id`）；
     * * `basic_info/get`——关注 / 粉丝 / 获赞三个计数。
     *
     * 注意 `profile/get` **必须带 `user_id`**（`?user_id=1739875314044148`），
     * 不带参数它回 `100107 network err`；它是看**别人**的资料用的，这里不用它。
     */
    internal fun ContentRepository.accountProfileImpl(): AccountProfile? {
        if(!signedIn) throw SignInRequiredException()
        val info=runCatching { accountJson("/reading/user/info/v1/") }.getOrNull()?.optJSONObject("data")
        val basic=runCatching { accountJson("/reading/user/basic_info/get/v1/") }.getOrNull()?.optJSONObject("data")
        return AccountParser.profile(info,basic)
    }

    /** 我的收藏：短剧书架（`bookshelf/video/info` 带封面与简介，比 `video/list` 那份完整）。 */
    internal fun ContentRepository.accountFavoritesImpl(): List<AccountFavorite> {
        return AccountParser.favorites(accountJson("/reading/bookapi/bookshelf/video/info/v"))
    }

    /**
     * 观看历史。
     *
     * 走官方 `read_history/list`：`?book_type=2&query_soft_deleted=false`，
     * 短剧记录在 `data.data_list[]` 里；`read_progress/list` 只负责当前播放进度。
     *
     * **一次取全，不翻页**：官方请求用 `offset=0&limit=0`。
     * 早先写死 `limit=50`，于是账号里有 139 条、电视上只出现 50 条——「同步来的不完全」。
     *
     * 它**只给 series_id**，所以再用 `multi-detail` 批量补片名与封面；补的时候按
     * [MULTI_DETAIL_LIMIT] 分片，某一片失败只丢那一片的元数据，不影响整栏。
     * 补不到元数据的条目仍然**不显示**（只有 id 的卡片是个空框，比少一条更糟），
     * 但会在 [writeLog] 里留一行，便于排查是哪一片没回来。
     */
    internal fun ContentRepository.accountHistoryImpl(): List<AccountHistory> {
        val rows=historyRows().let(AccountParser::history)
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
        val kept=rows.mapNotNull { row -> details[row.series.id]?.let { row.copy(series=totalOf(it,row.episodes)) } }
        if(kept.size<rows.size) writeLog("历史元数据缺失 ${rows.size-kept.size}/${rows.size}")
        return kept
    }

    /**
     * 用**进度那一行的总集数**校正 `multi-detail` 给的集数。
     *
     * `multi-detail` 的 `drama_chapter_number` 对一部分剧是错的：实测「持械入宋：第九季」
     * 回 14（真 100 集）、「聚宝仙盆之杂灵根才是真BOSS」回 14（真 99 集）、
     * 「技能一块钱升一级请叫我SSS级法神」回 11（真 130 集）——199 条历史里有 29 条对不上。
     * 卡片右下角「共 N 集」读的就是这个字段，于是「我的 → 历史」里写「共 14 集」，
     * 而首页历史栏（走详情接口）写的是对的——两边同一部剧显示不同的集数。
     *
     * `read_progress` 自己给的 `episode_count` 与搜索卡片的 `episode_cnt` 完全一致
     *（2026-10-30 实测 5 部对不上的剧，进度值 = 搜索卡值），因此以它为准；
     * 进度没给（0）时才退回 `multi-detail` 的数。
     */
    internal fun ContentRepository.totalOf(series: Series,progressCount: Int): Series {
        if(progressCount<=0 || progressCount==series.episodeCount) return series
        return series.copy(episodeCount=progressCount,badge=EpisodeLabel.of(progressCount))
    }

    /**
     * 点赞：`ugc/action/mget` 的「我的点赞记录」。
     *
     * `action_type=3` 是「我点赞的」，`object_type_list=6,15,10` 是短剧相关的内容类型
     * （实测给 16 只回空；老的 `praise/user/record` 只覆盖送花那类，短剧点赞不在里面）。
     * 这个接口会间歇性回空响应体，重发即可。
     */
    internal fun ContentRepository.accountLikesImpl(): List<AccountLike> {
        val params=mapOf("count" to "50","object_type_list" to "6,15,10","offset" to "0","action_type" to "3")
        return AccountParser.likes(retryBlank { accountJson("/reading/ugc/action/mget/v",params) })
    }

    /**
     * 预约 / 追更：`subscribe/list/v0` 的「预约」那一栏。
     *
     * `tab_type=13` 才是真数据（11/12 是空壳：`subscribe_items` 为 null）；
     * `is_online=true` 取「已上线」，false 取「待上线」。两批合起来就是 App 里那一栏的全部。
     */
    internal fun ContentRepository.accountReservesImpl(): List<AccountReserve> {
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
    internal fun ContentRepository.accountReserveImpl(seriesId: String, subscribe: Boolean): Boolean {
        if(!signedIn || !seriesId.matches(Regex("[0-9]{1,30}"))) return false
        val (path,payload)=ContentAccountRequests.reserve(seriesId,subscribe)
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
            if(attempt<ACCOUNT_WRITE_ATTEMPTS-1) {
                val delay=ACCOUNT_WRITE_BACKOFF[attempt].coerceAtLeast(600L)*(attempt+1)
                NetLog.retry("reserve_write",attempt+2,ACCOUNT_WRITE_ATTEMPTS,detail,delay)
                Thread.sleep(delay)
            }
        }
        NetLog.error("[API] error scope=reserve_write attempts=$ACCOUNT_WRITE_ATTEMPTS subscribe=$subscribe")
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
    internal fun ContentRepository.accountAddFavoriteImpl(seriesId: String): Boolean = videoShelf(seriesId,VIDEO_SHELF_ADD_SHELF)

    /** 取消收藏：`bookshelf/video/update` 的 DelShelf（`bookshelf/delete` 实测不生效）。 */
    internal fun ContentRepository.accountRemoveFavoriteImpl(seriesId: String): Boolean = videoShelf(seriesId,VIDEO_SHELF_DEL_SHELF)

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
    internal fun ContentRepository.videoShelf(seriesId: String,operate: Int): Boolean {
        if(!signedIn || !seriesId.matches(Regex("[0-9]{1,30}"))) return false
        val payload=ContentAccountRequests.favorite(
            seriesId,
            operate,
            clock(),
            BOOK_TYPE_WATCH,
            VIDEO_SHELF_ADD_SHELF,
            VIDEO_SHELF_DEL_SHELF,
        )
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
            if(attempt<ACCOUNT_WRITE_ATTEMPTS-1) {
                val delay=ACCOUNT_WRITE_BACKOFF[attempt]
                NetLog.retry("favorite_write",attempt+2,ACCOUNT_WRITE_ATTEMPTS,detail,delay)
                Thread.sleep(delay)
            }
        }
        NetLog.error("[API] error scope=favorite_write attempts=$ACCOUNT_WRITE_ATTEMPTS operate=$operate")
        return false
    }

    /**
     * 上传观看进度（`write_mode=1` 会同时写进「历史」）。
     *
     * [episodeCount] 只用于云端「全 N 集」的展示，未知时传 0；
     * `progress_rate` 是 0~1 的小数串，平台用它画进度条。
     */
    internal fun ContentRepository.accountUploadProgressImpl(seriesId: String,episodeId: String,episodeIndex: Int,position: Long,duration: Long,episodeCount: Int=0): Boolean {
        if(!seriesId.matches(Regex("[0-9]{1,30}"))) return false
        if(!episodeId.matches(Regex("[0-9]{1,30}"))) return false
        val body=ContentAccountRequests.uploadProgress(
            seriesId,
            episodeId,
            episodeIndex,
            position,
            duration,
            episodeCount,
            clock(),
            BOOK_TYPE_WATCH,
        )
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
            if(attempt<ACCOUNT_WRITE_ATTEMPTS-1) {
                val delay=ACCOUNT_WRITE_BACKOFF[attempt]
                NetLog.retry("progress_write",attempt+2,ACCOUNT_WRITE_ATTEMPTS,detail,delay)
                Thread.sleep(delay)
            }
        }
        NetLog.error("[API] error scope=progress_write attempts=$ACCOUNT_WRITE_ATTEMPTS")
        return false
    }

    /**
     * 删除一条云端观看历史。
     *
     * 实测：`read_history/update` 必须带 `is_delete=true` + **`use_soft_delete=true`** +
     * `update_timestamp_ms`（带 `read_timestamp_ms` 会被放进 `update_fail_datas` 里拒绝）；
     * `book_id` 要给**数字**（字符串会被判 `100103`）。
     */
    internal fun ContentRepository.accountDeleteHistoryImpl(seriesId: String): Boolean = accountDeleteHistoriesImpl(listOf(seriesId))

    /**
     * 批量删除云端观看记录（清空历史用）。
     *
     * 与单条删除走同一个端点、同一套字段：`update_datas` 本来就是数组，一次塞多条即可。
     * 按 [HISTORY_DELETE_BATCH] 分批，避免一次请求体过大被网关拒掉。
     * 返回**全部批次都成功**才为 true；某一批失败只记日志，其余照常发。
     */
    internal fun ContentRepository.accountDeleteHistoriesImpl(seriesIds: List<String>): Boolean {
        val ids=seriesIds.filter { it.matches(Regex("[0-9]{1,30}")) }.distinct()
        if(ids.isEmpty()) return false
        val known=runCatching {
            historyRows().optJSONObject("data")?.optJSONArray("data_list")?.objects().orEmpty()
                .associateBy { it.str("book_id").ifEmpty { it.str("book_id_str") } }
        }.getOrDefault(emptyMap())
        var ok=true
        ids.chunked(HISTORY_DELETE_BATCH).forEach { chunk ->
            val body=ContentAccountRequests.deleteHistory(
                chunk,
                known,
                BOOK_TYPE_WATCH,
                { clock() },
            )
            val part=runCatching {
                val reply=accountJson("/reading/bookapi/read_history/update/v",emptyMap(),body)
                val code=reply.optInt("code",-1)
                val failed=(reply.optJSONObject("data")?.optJSONArray("update_fail_datas")?.length() ?: 0)
                if(code!=0 || failed>0) {
                    NetLog.error("[API] error scope=history_delete size=${chunk.size} code=$code failed=$failed message=${reply.optString("message").take(160)}")
                    writeLog("历史删除写接口失败 size=${chunk.size} code=$code failed=$failed message=${reply.optString("message")}")
                }
                code==0 && failed==0
            }.onFailure {
                NetLog.error("[API] error scope=history_delete size=${chunk.size} ${it.javaClass.simpleName}: ${it.message.orEmpty().take(180)}")
                writeLog("历史删除写接口异常 size=${chunk.size} ${it.message}")
            }.getOrDefault(false)
            if(!part) ok=false
        }
        return ok
    }

    /**
     * 云端观看记录的**全部 id**（不补片名与封面）。
     *
     * 与 [accountHistory] 读同一个接口（`read_history/list`，`limit=0` 一次给满），
     * 但不做 `multi-detail` 那一轮元数据补齐：清空历史只关心「云端还有哪些 id」，
     * 补片名是纯浪费。`offset=0&limit=0` 一次取全。
     */
    internal fun ContentRepository.accountHistoryIdsImpl(): List<String> {
        return historyRows().optJSONObject("data")?.optJSONArray("data_list")?.objects().orEmpty().mapNotNull { row ->
            row.str("book_id").ifEmpty { row.str("book_id_str") }
                .takeIf { it.matches(Regex("[0-9]{1,30}")) }
        }.distinct()
    }

    /** 官方短剧历史列表；`read_progress/list` 只负责当前进度，不能拿来判断历史删除。 */
    internal fun ContentRepository.historyRows(): JSONObject {
        val merged=JSONArray()
        HISTORY_BOOK_TYPES.forEach { type ->
            val raw=retryBlank {
                accountJson("/reading/bookapi/read_history/list/v", mapOf(
                    "book_type" to type.toString(),
                    "offset" to "0",
                    "limit" to "0",
                    "query_soft_deleted" to "false",
                    "is_first_load" to "true",
                    "last_min_read_timestamp_ms" to "0",
                    "full_field" to "false",
                ))
            }
            raw.optJSONObject("data")?.optJSONArray("data_list")?.objects().orEmpty().forEach(merged::put)
        }
        return JSONObject().put("data",JSONObject().put("data_list",merged))
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
     * 3. **回读校验**：再拉一次 `read_history/list`，空了才算成功；还有就再来一轮
     *   （软删偶发只生效一部分，平台对短剧进度列表的处理不是严格即时）。
     *
     * 返回 true = 回读确认云端已经没有记录；false = 仍有余量或网络失败，
     * 由调用方提示用户稍后重试（本机那份无论如何已经清干净了）。
     */
    internal fun ContentRepository.accountClearHistoryImpl(extra: List<String> = emptyList()): Boolean {
        val known=extra.filter { it.matches(Regex("[0-9]{1,30}")) }
        repeat(HISTORY_CLEAR_ATTEMPTS) { attempt ->
            val cloud=runCatching { accountHistoryIdsImpl() }
                .onFailure { writeLog("清空历史：读取云端列表失败 attempt=${attempt+1} ${it.message}") }
                .getOrElse { return@repeat }
            val ids=(cloud+known).distinct()
            if(ids.isNotEmpty() && !accountDeleteHistoriesImpl(ids)) {
                writeLog("清空历史：删除批次未全部成功 attempt=${attempt+1} size=${ids.size}")
            }
            val left=runCatching { accountHistoryIdsImpl() }.getOrElse {
                writeLog("清空历史：回读校验失败 attempt=${attempt+1} ${it.message}")
                return@repeat
            }
            if(left.isEmpty()) return true
            writeLog("清空历史：回读仍有 ${left.size} 条 attempt=${attempt+1}")
            if(attempt<HISTORY_CLEAR_ATTEMPTS-1) {
                val delay=ACCOUNT_WRITE_BACKOFF[attempt.coerceAtMost(ACCOUNT_WRITE_BACKOFF.lastIndex)]
                NetLog.retry("history_clear",attempt+2,HISTORY_CLEAR_ATTEMPTS,"cloud still has ${left.size} records",delay)
                Thread.sleep(delay)
            }
        }
        return false
    }

    /**
     * 个人信息页要的全部内容：资料 + 四条列表，一次拉齐。
     *
     * 四条列表彼此独立：某一条失败只让它空着、把原因记进 [AccountData.errors]，
     * 其余三条照常显示。未登录时资料拉不到，但设备级历史 / 收藏 / 预约列表仍会请求。
     */
    internal fun ContentRepository.accountDataImpl(): AccountData {
        val errors=java.util.concurrent.ConcurrentHashMap<String,String>()
        fun <T> part(name: String, action: ()->T, fallback: T): T = try { action() } catch(e: Exception) {
            errors[name]=e.message.orEmpty().take(120); fallback
        }
        // 五个任务**并发**发出去：它们彼此独立，串行发要两三秒（每个请求都带签名，
        // 平台首包 200~600ms，个别还要重试）。并发之后整体耗时取决于最慢的那一个。
        val profile=accountPool.submit<AccountProfile?> { part("资料",{accountProfileImpl()},null) }
        val favorites=accountPool.submit<List<AccountFavorite>> { part("收藏",{accountFavoritesImpl()},emptyList()) }
        val history=accountPool.submit<List<AccountHistory>> { part("历史",{accountHistoryImpl()},emptyList()) }
        val likes=accountPool.submit<List<AccountLike>> { part("点赞",{accountLikesImpl()},emptyList()) }
        val reserves=accountPool.submit<List<AccountReserve>> { part("预约",{accountReservesImpl()},emptyList()) }
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
