// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号信息。
 *
 * 数据来自两个接口，两者都给得出昵称与头像：
 * * `GET /reading/user/info/v1/`（不带参数 = 当前登录用户）——给昵称 / 头像 / 红果号；
 * * `GET /reading/user/basic_info/get/v1/`——同一个人，另外给关注 / 粉丝 / 获赞三个计数。
 *
 * [mobile] 平台不给（只在短信里出现），因此留空由界面跳过。
 */
data class AccountProfile(
    val userId: String,
    val nickname: String,
    val avatar: String,
    /** 红果号（App 里显示为「红果号：066831371270」）。 */
    val displayId: String = "",
    val signature: String = "",
    /** 关注 / 粉丝 / 获赞；取不到时为 null，界面据此决定要不要显示这一行。 */
    val follows: Int? = null,
    val fans: Int? = null,
    val likes: Int? = null,
    /**
     * 这份「资料」其实是**匿名设备**的临时账号。
     *
     * 平台对无效 / 过期的 `sessionid` 不报错，而是把请求当成匿名设备，返回一个按
     * `device_id` 现造的账号（昵称形如「用户19058925」，红果号就是 device_id）。
     * 这种响应必须挡住——否则一份坏凭据会被当成导入成功，界面上只是「一条记录都没有」。
     */
    val anonymous: Boolean = false
)

/** 「我的收藏」里的一项（`/reading/bookapi/bookshelf/video/info/v`）。 */
data class AccountFavorite(val series: Series, val collectTime: Long, val episodes: Int)

/** 「观看历史」里的一项。 */
data class AccountHistory(
    val series: Series,
    /** 看到第几集（从 0 起；平台给的是 `vid_index`，从 1 起）。 */
    val episodeIndex: Int,
    val episodeId: String,
    val updatedAt: Long,
    /** 这部剧一共多少集；未知时为 0。 */
    val episodes: Int = 0,
    /**
     * 这一集看到第几毫秒（平台的 `current_play_time`；上传时用的就是毫秒，见
     * `ContentRepository.accountUploadProgress`）。
     *
     * 有了它，从账号侧的历史点进去才能续到那一集的那个位置，而不是从第 1 集重新开始；
     * 老数据 / 平台没给时为 0（当作「从这一集开头起」）。
     */
    val position: Long = 0
)

/** 「点赞」里的一项（`/reading/ugc/action/mget/v` 的 `action_type=3` 记录）。 */
data class AccountLike(val series: Series, val updatedAt: Long)

/** 「预约 / 追更」里的一项（`/reading/user/subscribe/list/v0/` 的 `tab_type=13`）。 */
data class AccountReserve(val series: Series, val updatedAt: Long, val online: Boolean = false)

/**
 * 账号相关的四条列表 + 资料，一次拉齐。
 *
 * 界面拿到这一份就可以把「个人信息」页整页铺出来；四条列表各自独立，
 * 某一条失败只让那一栏空着（[errors] 里记原因），不影响其余三条。
 */
data class AccountData(
    val profile: AccountProfile?,
    val favorites: List<AccountFavorite>,
    val history: List<AccountHistory>,
    val likes: List<AccountLike>,
    val reserves: List<AccountReserve>,
    val errors: Map<String, String> = emptyMap()
) {
    /** 资料拿到了就算「参数已验证通过」：四条列表各自可以为空。 */
    val usable: Boolean get() = profile != null
}

/**
 * 从模拟器导出的凭据（`cookieStore.xml` 解析后的结果）。
 *
 * 与 [ContentRepository.sessionToken] 的区别：这里存的是**结构化的整套登录态**
 * （Cookie 串 + 设备参数），因为 App 接口既要 Cookie 又要与之一致的设备档案。
 */
data class AccountCredentials(
    /** 可直接当 `Cookie:` 头用的整段串（`sessionid=…; uid_tt=…`）。 */
    val cookie: String,
    /**
     * 会话 `X-Tt-Token`（响应头）。
     *
     * 与 [cookie] 是**两套并行凭证**，必须分头送：`X-Tt-Token` 只放它、`Cookie` 只放 cookie。
     * 导入 `cookieStore.xml` 时为空（那份文件里不存 token），此时会话靠心跳的响应头把 token
     * 换回来（见 `SessionKeeper`）；为空就说明还没换到，不代表没登录。
     */
    val token: String = "",
    /** TicketGuard 静态签名（`bd-ticket-guard-sign-res-static-ts-sign`），可空。 */
    val tsSign: String = "",
    val deviceId: String = "",
    val installId: String = "",
    val cdid: String = "",
    val openudid: String = "",
    /** `sid_guard` 里的过期时间（秒）；解析不出来时为 0。 */
    val expiresAt: Long = 0,
    /**
     * 真机设备档案（`device_profile.json` 的字段 → 值）。
     *
     * 这些**只能从设备上读**，sessionid 拿不到（平台侧的 `passport/account/info/v2`
     * 里 `device_id` 就是 0，说明它不存设备指纹）：
     * `device_id` / `cdid` / `klink_egdi` 在 `shared_prefs` 里，
     * 机型 / 系统版本 / 分辨率要读 `getprop` 与 `wm`。
     *
     * 签名里的 `x-medusa` 要上报这套档案，写接口（追剧 / 进度）会拿它做一致性判定，
     * 因此导入凭据时**一并带上**，缺哪项就回落到 [VendorConstants.device] 的内置值。
     */
    val device: Map<String, String> = emptyMap()
) {
    val configured: Boolean get() = cookie.isNotBlank()

    /** 剩余天数；未知时为 null。 */
    fun remainingDays(now: Long = System.currentTimeMillis()): Double? =
        if (expiresAt <= 0) null else (expiresAt * 1000 - now) / 86_400_000.0
}

/**
 * `cookieStore.xml` → [AccountCredentials]。
 *
 * 该文件是 App 自己写的 `SerializableHttpCookie` 序列化结果：
 * 每个 `<string name="http://<host>/|cookieName">` 的值是一段 Java 序列化流（十六进制），
 * 里面的字符串按 `TC_STRING(0x74) + u2 长度 + UTF-8` 排列，形如 `[name, value, domain, path]`。
 *
 * **同一份登录态在文件里有十几个 host 的副本**（`snssdk.com` / `doupay.com` / `fqnovel.com` …），
 * 内容一致，取哪一个都行——这里取 `snssdk.com`（App 自己的主域）。
 *
 * 只做「取需要的字段」这一件事，不做任何网络校验——校验交给 [ContentRepository.accountData]。
 */
object AccountCredentialsParser {
    /** 组装 `Cookie:` 头时要带的字段（顺序固定，便于比对）。 */
    val COOKIE_KEYS = listOf("sessionid", "sessionid_ss", "sid_tt", "uid_tt", "uid_tt_ss", "d_ticket",
        "sid_guard", "session_tlb_tag", "store-region", "store-region-src", "install_id", "n_mh", "odin_tt", "ttreq")

    /** 默认取哪个 host 下的 cookie（文件里每个 host 都有一份同样的值）。 */
    const val DEFAULT_HOST = "snssdk.com"

    /** 从 `cookieStore.xml` 里取某个 host 下所有 cookie 的 `name → value`。 */
    fun parse(xml: String, host: String = DEFAULT_HOST): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val pattern = Regex("""<string name="(https?://[^"|]+)/\|([^"]+)">([^<]*)</string>""")
        pattern.findAll(xml).forEach { match ->
            val site = match.groupValues[1].removePrefix("http://").removePrefix("https://").trimEnd('/')
            if (site != host) return@forEach
            val name = match.groupValues[2]
            val value = cookieValue(match.groupValues[3], name) ?: return@forEach
            result[name] = value
        }
        return result
    }

    /**
     * 从一段十六进制序列化流里取 [name] 后面的那个字符串。
     *
     * 手工按字节扫 `0x74 + u2 长度`，不依赖任何 Java 序列化实现：
     * 值里可能带 `%7C`（`sid_guard`）等转义，但长度前缀是字节数，因此不会错位。
     */
    private fun cookieValue(hex: String, name: String): String? {
        val bytes = hexToBytes(hex) ?: return null
        val fields = mutableListOf<String>()
        var index = 0
        while (index < bytes.size - 2) {
            if (bytes[index] == 0x74.toByte()) {
                val length = ((bytes[index + 1].toInt() and 255) shl 8) or (bytes[index + 2].toInt() and 255)
                if (length in 1..2048 && index + 3 + length <= bytes.size) {
                    val chunk = bytes.copyOfRange(index + 3, index + 3 + length)
                    if (chunk.all { it.toInt() and 255 in 0x20..0x7E }) {
                        fields += String(chunk, Charsets.US_ASCII)
                        index += 3 + length
                        continue
                    }
                }
            }
            index++
        }
        val at = fields.indexOf(name)
        return if (at >= 0 && at + 1 < fields.size) fields[at + 1] else null
    }

    private fun hexToBytes(value: String): ByteArray? {
        if (value.isEmpty() || value.length % 2 != 0 || value.any { it !in "0123456789abcdefABCDEF" }) return null
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** 取 `sid_guard` 的过期时间：`sessionid|签发时间戳|max-age|人类可读`，**过期 = 签发 + max-age**。 */
    fun expiryOf(cookie: Map<String, String>): Long {
        val guard = cookie["sid_guard"]?.replace("%7C", "|") ?: return 0
        val parts = guard.split('|')
        if (parts.size < 3) return 0
        val issued = parts[1].trim().toLongOrNull() ?: return 0
        val maxAge = parts[2].trim().toLongOrNull() ?: return 0
        return issued + maxAge
    }

    /** 组装可直接发出去的 `Cookie:` 头。 */
    fun headerOf(cookie: Map<String, String>): String =
        COOKIE_KEYS.mapNotNull { name -> cookie[name]?.let { "$name=$it" } }.joinToString("; ")

    /** 一步到位：`cookieStore.xml` → [AccountCredentials]。 */
    fun parseCredentials(xml: String, device: Map<String, String> = emptyMap()): AccountCredentials {
        val cookie = parse(xml)
        return AccountCredentials(
            cookie = headerOf(cookie),
            deviceId = device["device_id"].orEmpty(),
            installId = device["install_id"].orEmpty(),
            cdid = device["cdid"].orEmpty(),
            openudid = device["openudid"].orEmpty(),
            expiresAt = expiryOf(cookie),
            // 整份档案一起存下来：签名要用机型 / 系统版本 / cdid / klink_egdi 这些，
            // 只留上面五个字段不够。
            device = device.filterValues { it.isNotBlank() }
        )
    }

    /**
     * 从 `device_profile.json`（`tools/login-lab/dump_device.py` 的产物）里取设备档案。
     *
     * 导入时用户可以把这个文件一起传上来；解析失败就当没有，签名回落内置档案。
     */
    fun parseDeviceProfile(text: String): Map<String, String> {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (key in DEVICE_KEYS) {
                val value = when (key) {
                    "iid" -> json.str("install_id")
                    else -> json.str(key)
                }
                if (value.isNotBlank()) put(key, value)
            }
        }
    }

    /** 签名与 query 会用到的设备字段（`device_profile.json` 里的键名）。 */
    val DEVICE_KEYS = listOf("device_id", "iid", "cdid", "openudid", "device_type", "device_brand",
        "os_version", "os_api", "resolution", "dpi", "klink_egdi", "rom_version")

    /** `sessionid` 的形状：32 位十六进制。 */
    fun looksLikeSessionId(value: String) = value.matches(Regex("[0-9a-fA-F]{32}"))

    /**
     * 从用户粘贴的**任意一段文本**里取出 `sessionid` 的值。
     *
     * 认这几种输入（都是用户手上真实会出现的东西）：
     * 1. 纯值 —— `cf98cf3c…`；
     * 2. `sessionid=值`；
     * 3. 整段 Cookie —— `sessionid=…; uid_tt=…; …`；
     * 4. `cookieStore.xml` 里的**整行** —— `<string name="http://snssdk.com/|sessionid">HEX</string>`；
     * 5. 那一行的**值本身** —— App 序列化出来的十六进制流（`ACED00057372…`）。
     *
     * 第 4、5 两种是最容易踩的：文件里搜 `sessionid` 时，看到的是那一长串十六进制，
     * 直接粘进来就会变成一个**非法的 sessionid**（平台不报错，只把请求降级成匿名设备）。
     * 因此这里按字节扫一遍序列化流，把真正的值取出来。
     */
    fun sessionIdOf(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        // 4) 整行 <string>：取出引号里那段十六进制，再按下面第 5 条解。
        Regex("""<string name="[^"]*\|sessionid">([^<]*)</string>""").find(text)?.let { match ->
            val inner = sessionIdOf(match.groupValues[1])
            if (inner.isNotEmpty()) return inner
        }
        // 5) 十六进制序列化流：按 TC_STRING 扫出字段，取 `sessionid` 后面那一个。
        if (text.length > 64 && text.length % 2 == 0 && text.all { it in "0123456789abcdefABCDEF" }) {
            cookieValue(text, "sessionid")?.let { return it }
        }
        // 2)/3) k=v 串：从中挑出 sessionid 那一段（没有就返回空，由调用方报错）。
        if (text.contains('=')) {
            return text.split(';').mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null else part.substring(0, at).trim() to part.substring(at + 1).trim()
            }.firstOrNull { it.first == "sessionid" }?.second.orEmpty()
        }
        // 1) 纯值。
        return text
    }

    /** 直接粘整段 Cookie 时的入口：`key=value; key=value` → [AccountCredentials]。 */
    fun parseHeader(text: String): AccountCredentials {
        val map = text.split(';').mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null else part.substring(0, at).trim() to part.substring(at + 1).trim()
        }.toMap()
        return AccountCredentials(headerOf(map), expiresAt = expiryOf(map))
    }
}

/**
 * 账号接口的解析：把平台 JSON 变成 [AccountData]。纯函数，便于单测。
 *
 * 每条接口的真实响应形状都在这里注释清楚（参数见 [ContentRepository] 里各接口的注释）：
 * 这个平台的字段名与「看起来应该叫什么」经常不一样（历史叫 `video_data`、
 * 点赞记录藏在 `mixed_data_list` 里），因此解析逻辑集中放在这一处、只认实测结构。
 */
object AccountParser {
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

    /** 从视频条目里取剧集。短剧的封面/标题字段在各接口里名字不同，这里统一兜一遍。 */
    private fun seriesOf(video: JSONObject, outer: JSONObject = JSONObject()): Series {
        // `v1` 的预约条目里 id 叫 `item_id`（顶层），标题叫 `name`，简介叫 `item_desc`；
        // 其余接口仍用 `series_id` / `title` / `video_desc`，这里统一兜一遍。
        val id = video.str("series_id").ifEmpty { outer.str("series_id") }.ifEmpty { outer.str("book_id") }
            .ifEmpty { video.str("item_id") }.ifEmpty { outer.str("item_id") }
        val cover = outer.str("series_cover_url")
            .ifEmpty { video.str("cover") }
            .ifEmpty { video.str("cover_url") }
            .ifEmpty { video.str("thumb_url") }
            .ifEmpty { video.str("audio_thumb_uri") }
        val count = video.optInt("episode_cnt").let { if (it > 0) it else video.optInt("episode_count") }
            .let { if (it > 0) it else outer.optInt("episode_cnt") }
            .let { if (it > 0) it else video.str("drama_chapter_number").toIntOrNull() ?: 0 }
        // 题材：外层（书架 / 预约那一层）与 `video_data` 都可能带，两处都认；
        // 都没有时退回 `category`（旧接口给的那一个字符串）。
        fun genre(o: JSONObject) = runCatching { JSONArray(o.str("category_schema")) }.getOrNull()
            ?.objects()?.mapNotNull { it.str("name") }?.take(5)?.joinToString(" · ").orEmpty()
        val tags = genre(outer).ifEmpty { genre(video) }.ifEmpty { video.str("category") }
        return Series(id,
            outer.str("series_title").ifEmpty { video.str("title") }.ifEmpty { outer.str("book_name") }
                .ifEmpty { video.str("name") }.ifEmpty { outer.str("name") },
            cleanUrl(cover),
            outer.str("series_intro").ifEmpty { video.str("video_desc") }.ifEmpty { outer.str("abstract") }
                .ifEmpty { video.str("item_desc") },
            EpisodeLabel.of(count),
            tags,
            episodeCount = count)
    }

    /**
     * 账号资料。
     *
     * 优先 `user/info`（红果号在这里，字段名是 `req_id`——不是 `user_id`，那个是加密串）；
     * 计数从 `basic_info` 里补。两个都失败时返回 null，界面据此提示参数无效。
     */
    fun profile(info: JSONObject?, basic: JSONObject?): AccountProfile? {
        val name = info?.str("user_name").orEmpty().ifEmpty { basic?.str("user_name").orEmpty() }
        val avatar = info?.str("avatar_url").orEmpty().ifEmpty { info?.str("expand_avatar_url").orEmpty() }
            .ifEmpty { basic?.str("user_avatar").orEmpty() }
        if (name.isEmpty() && avatar.isEmpty()) return null
        // 红果号：`user/info` 的 `req_id` 是纯数字的那一个；`user_id` 是 `#c1967_…` 形式的加密串。
        // 红果号是 `biz_user_id`（形如 `066831371270`，12 位、可能带前导零）；
        // `req_id` / `user_id` 是内部的 user_id（16 位数字，`1739875314044148`），
        // 两者都不是一回事——显示与判重都要用 `biz_user_id`。
        val display = info?.str("biz_user_id").orEmpty()
        return AccountProfile(
            userId = display,
            nickname = name,
            avatar = cleanUrl(avatar),
            displayId = display,
            signature = info?.str("description").orEmpty().ifEmpty { basic?.str("author_desc").orEmpty() },
            follows = basic?.intOrNull("follow_user_num"),
            fans = basic?.intOrNull("fans_num"),
            likes = basic?.intOrNull("recv_digg_num"),
            anonymous = isAnonymous(info)
        )
    }

    /**
     * 这份资料是不是**匿名设备**的临时账号。
     *
     * 平台对无效 / 过期的 `sessionid` 不报错：它把请求当成匿名设备，按 `device_id` 现造一个账号。
     * 实测两者的区别很明确（同一个 `device_id` 下对比）：
     *
     * | | 真实账号 | 匿名设备 |
     * | --- | --- | --- |
     * | `has_set_username` | `true` | `false` |
     * | `biz_user_id`（红果号） | `066831371270` | **不返回** |
     * | `user_name` | 用户自己设的 | `用户19058925`（`用户` + device_id 前 8 位） |
     *
     * 因此判定按这三条来。**不要**拿 `req_id` / `user_id` 当红果号看位数——
     * 它是 16 位的内部 id（`1739875314044148`），真实账号也是 16 位，
     * 按它判会把正常账号误杀（这就是「导入失败：平台把它当成了匿名设备」那次误报）。
     */
    private fun isAnonymous(info: JSONObject?): Boolean {
        info ?: return false
        if (info.str("biz_user_id").isNotBlank()) return false
        if (info.has("has_set_username")) return !info.optBoolean("has_set_username", true)
        return info.str("user_name").matches(Regex("""用户\d{6,}"""))
    }

    /**
     * 批量详情：`GET /reading/bookapi/multi-detail/v?book_id=<逗号分隔>&book_type=2`
     * → `data[]`，每条给 `book_id` / `book_name` / `thumb_url` / `abstract` /
     * `drama_chapter_number`（总集数）/ `category`。
     *
     * 历史的接口（`read_progress/list`）**只给 series_id，不给片名与封面**，
     * 所以拿到 id 之后必须再来这里补一次元数据，否则界面上是一排没有名字的卡片。
     */
    fun multiDetail(raw: JSONObject): Map<String, Series> {
        val rows = raw.optJSONArray("data") ?: return emptyMap()
        return rows.objects().mapNotNull { row ->
            val id = row.str("book_id").ifEmpty { row.str("series_id") }
            val title = row.str("book_name").ifEmpty { row.str("title") }
            if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
            val count = row.str("drama_chapter_number").toIntOrNull()
                ?: row.str("serial_count").toIntOrNull() ?: 0
            id to Series(id, title,
                cleanUrl(row.str("thumb_url").ifEmpty { row.str("audio_thumb_uri") }),
                row.str("abstract"),
                EpisodeLabel.of(count),
                // 类型标签：`multi-detail` 有的条目带 `category_schema`，没有就用 `category`。
                runCatching { JSONArray(row.str("category_schema")) }.getOrNull()
                    ?.objects()?.mapNotNull { it.str("name") }?.take(5)?.joinToString(" · ")
                    .orEmpty().ifEmpty { row.str("category") },
                episodeCount = count)
        }.toMap()
    }

    /**
     * 我的收藏：短剧书架（`bookshelf/video/info` 带封面与简介，比 `video/list` 那份完整）。
     *
     * 每条形如 `{video_data:{series_id,episode_cnt,…}, series_title, series_cover_url, collect_time}`。
     */
    fun favorites(raw: JSONObject): List<AccountFavorite> {
        val rows = raw.optJSONObject("data")?.optJSONArray("video_shelf_info") ?: return emptyList()
        return rows.objects().mapNotNull { row ->
            val video = row.optJSONObject("video_data") ?: JSONObject()
            val series = seriesOf(video, row)
            if (!series.id.matches(Regex("[0-9]{1,30}")) || series.title.isBlank()) return@mapNotNull null
            AccountFavorite(series, row.optLong("collect_time"), video.optInt("episode_cnt"))
        }.distinctBy { it.series.id }.sortedByDescending { it.collectTime }
    }

    /**
     * 观看历史。
     *
     * 平台把它叫「阅读进度」：`GET /reading/bookapi/read_progress/list/v?book_types=2&limit=50`
     * 回的是 **`video_data`** 数组（不是 `data`），每项
     * `{series_id, vid, vid_index, episode_count, last_watch_time, current_play_time}`。
     * `vid_index` 从 1 起，因此这里减 1 存成 0 起的集号。
     *
     * `current_play_time`（集内毫秒位置）**要一起收下**：少了它，从「我的 → 历史」点进去
     * 只能从那一集的开头起，用户读到的是「明明看到了第 12 集，怎么又从第一集开始」。
     *
     * `read_history/list` 那条接口对短剧**恒返回空列表**（它是给小说的），不要用。
     */
    fun history(raw: JSONObject): List<AccountHistory> {
        val rows = raw.optJSONArray("video_data")
            ?: raw.optJSONObject("data")?.optJSONArray("video_data")
            ?: return emptyList()
        return rows.objects().mapNotNull { row ->
            val id = row.str("series_id").ifEmpty { row.str("book_id") }
            if (!id.matches(Regex("[0-9]{1,30}"))) return@mapNotNull null
            val index = (row.optInt("vid_index") - 1).coerceAtLeast(0)
            AccountHistory(Series(id, "", "", "", "", ""), index, row.str("vid"),
                row.optLong("last_watch_time"), row.optInt("episode_count"),
                row.optLong("current_play_time").coerceAtLeast(0))
        }.distinctBy { it.series.id }.sortedByDescending { it.updatedAt }
    }

    /**
     * 点赞：`GET /reading/ugc/action/mget/v?action_type=3&object_type_list=6,15,10`
     * （`action_type=3` = 我的点赞记录；`object_type_list` 里 6/10/15 是短剧相关类型）。
     *
     * 记录在 `data.mixed_data_list[]` 里，每条 `{data_type, video_data:{…, user_digg_timestamp_ms}}`。
     * 老的 `/reading/ugc/praise/user/record/v1/` 只给「送花」那类，短剧点赞不在里面。
     */
    fun likes(raw: JSONObject): List<AccountLike> {
        val rows = raw.optJSONObject("data")?.optJSONArray("mixed_data_list") ?: return emptyList()
        return rows.objects().mapNotNull { row ->
            val video = row.optJSONObject("video_data") ?: row.optJSONObject("book_data") ?: return@mapNotNull null
            val series = seriesOf(video, row)
            if (!series.id.matches(Regex("[0-9]{1,30}")) || series.title.isBlank()) return@mapNotNull null
            AccountLike(series, video.optLong("user_digg_timestamp_ms").let { if (it > 0) it else video.optLong("create_time") })
        }.distinctBy { it.series.id }.sortedByDescending { it.updatedAt }
    }

    /**
     * 预约 / 追更：`GET /reading/user/subscribe/list/v0/?tab_type=13&is_online=true`
     * （`tab_type=13` 才是「预约」页那一栏；11/12 是空壳）。
     *
     * 每条 `{is_online, item_id, subscribe_data:{series_id,title,cover,episode_cnt,…}, schedule_publish_time}`。
     */
    fun reserves(raw: JSONObject): List<AccountReserve> {
        val rows = raw.optJSONObject("data")?.optJSONArray("subscribe_items") ?: return emptyList()
        return rows.objects().mapNotNull { row ->
            // `v1`（当前版本，真机抓包 2026-10-19）的条目**没有** `subscribe_data`：
            // `item_id` / `name` / `cover` / `item_desc` / `sub_info` 全在顶层。
            // 老 `v0` 才把详情包在 `subscribe_data` 里。两种形状都要认，
            // 否则「预约」页会读成空列表。
            val video = row.optJSONObject("subscribe_data") ?: row.optJSONObject("video_data") ?: row
            val series = seriesOf(video, row)
            if (!series.id.matches(Regex("[0-9]{1,30}")) || series.title.isBlank()) return@mapNotNull null
            val time = row.optLong("schedule_publish_time").let { if (it > 0) it * 1000 else 0 }
            val online = row.optBoolean("is_online", false)
            // 待上线那一栏就是「预约中」：`sub_info` / `sub_title_list` 里有「10月20日上线」这类文案，
            // 卡片第二行直接显示它（与搜索里那张预告卡一致）。
            val notice = row.str("sub_info")
                .ifEmpty { runCatching { JSONArray(row.str("sub_title_list")) }.getOrNull()?.objects()?.mapNotNull { it.str("content") }?.firstOrNull().orEmpty() }
            AccountReserve(series.copy(upcoming = !online, notice = notice), time, online)
        }.distinctBy { it.series.id }.sortedByDescending { it.updatedAt }
    }
}
