// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 首页本机记录三栏的 key（历史记录 / 我的收藏 / 稍后观看）。
 *
 * 与顶部导航那五枚入口不是一组：这三枚管的是**首页铺不铺那一栏**，
 * 设置页各有一枚「是否显示」的开关（见 `Library.hiddenHome`）。
 * 放在 core 里是因为落盘解码（[LibraryDisk]）与备份恢复都要用它做白名单。
 */
val HOME_KEYS=setOf("history","favorites","later")
data class BackupProgress(val series: Series,val episodeId: String,val episodeIndex: Int,val position: Long,val duration: Long,val completed: Boolean,val updatedAt: Long) {
    fun json()=JSONObject().put("series",LibraryBackup.seriesJson(series)).put("episodeId",episodeId).put("episodeIndex",episodeIndex).put("position",position).put("duration",duration).put("completed",completed).put("updatedAt",updatedAt)
}
data class BackupSettings(val quality: Int=1080,val speed: Float=1f,val autoNext: Boolean=true,val type: ContentType=ContentType.COMIC,val frame: VideoFrameMode=VideoFrameMode.FIT,val danmaku: DanmakuMode=DanmakuMode.FULL,val danmakuScale: Float=1f,val danmakuAlpha: Float=1f,val danmakuSpeed: DanmakuSpeed=DanmakuSpeed.NORMAL,
    /**
     * 播放界面的按键绑定（[KeyMap.encode] 的产物，只含改过的键；空串 = 全默认）。
     *
     * 与其余设置一样随备份走：换了电视之后手感不用重新调一遍。旧备份没有这个字段，
     * 读回来就是空串，也就是出厂绑定。
     */
    val keys: String="",
    /**
     * 视频解码方式（硬件 / 软件）。
     *
     * 同样是后加字段：旧备份没有它，读回来按「硬件解码」处理，与之前的默认行为一致。
     */
    val decoder: VideoDecoderMode=VideoDecoderMode.HARDWARE,
    /**
     * 相同弹幕是否聚合。
     *
     * 默认开启（与之前的行为一致）；关闭后同一句弹幕不再合并、也不再有数量标记，
     * 全部按原样逐条滚动。旧备份没有这个字段时按开启处理。
     */
    val danmakuMerge: Boolean=true,
    /**
     * 搜索数据源（App / 官网）。
     *
     * 后加字段：旧备份没有它，读回来按 App 处理（与之前的唯一行为一致）。
     */
    val searchSource: SearchSource=SearchSource.APP,
    /**
     * 每一行放几张卡片：4 / 5 / 6（默认 6，与旧版一致），全局生效。
     *
     * 后加字段：旧备份没有它，读回来按 6 处理。
     */
    val homeColumns: Int=6,
    /**
     * 播放时是否始终显示右上角时间。
     *
     * 默认关闭（与旧版一致：时间跟着标题条一起显隐）；打开后标题条收起，
     * 右上角的时间仍然留着。旧备份没有这个字段时按关闭处理。
     */
    val alwaysShowClock: Boolean=false,
    /**
     * 顶部导航栏里**被隐藏**的圆形入口，逗号分隔（search / category / rank / settings /
     * profile / feedback）。
     *
     * 每一项单独开关：默认全空 = 全部显示（与旧版一致）。空串也是旧备份的读回值。
     */
    val hiddenNav: String="",
    /**
     * 调试模式：打开后各页面右上角常驻一块现场面板（播放信息 / 接口与登录的近况）。
     *
     * 默认关闭。它是**排障开关**，不是给普通用户的设置，因此不进备份文件——
     * 换一台电视恢复记录时不该把「屏幕上多一块字」一起带过去。
     */
    val debugMode: Boolean=false,
    /**
     * 打开应用是否直接进入推荐页（默认开启，与需求 1 一致）。
     *
     * 与导航栏「推荐」入口联动：入口被隐藏时这一项强制为关（见 `Library.launchRecommend`），
     * 避免出现「导航栏里没有推荐、启动却还是进推荐」的自相矛盾状态。
     */
    val launchRecommend: Boolean=true,
    /**
     * 首页本机记录三栏（历史记录 / 我的收藏 / 稍后观看）里**被隐藏**的那些，逗号分隔。
     *
     * 默认空串 = 三栏全显示（与旧版一致）；旧备份没有这个字段时读回空串。
     * 关掉只影响首页铺不铺那一栏，记录本身一份不删。
     */
    val hiddenHome: String="",
    /**
     * 用户主动要求「减少动画、优先流畅」。
     *
     * 低配机不看这一项（那边一律按低配档跑，见 `compat/Platform.kt` 的 `lowEndTv`）；
     * 它只给**非低配**用户一个手动降级的开关——关掉焦点缩放、柔光位图、暗角与跑马灯，
     * 效果与低配机完全一致。
     *
     * 后加字段，旧备份没有它时按关闭处理（与之前的行为一致）。
     */
    val reduceAnim: Boolean=false) {
    fun json()=JSONObject().put("quality",quality).put("speed",speed.toDouble()).put("autoNext",autoNext).put("type",type.storedValue).put("frame",frame.name).put("danmaku",danmaku.storedValue)
        .put("danmakuScale",danmakuScale.toDouble()).put("danmakuAlpha",danmakuAlpha.toDouble()).put("danmakuSpeed",danmakuSpeed.storedValue)
        .put("keys",keys)
        .put("decoder",decoder.name)
        .put("danmakuMerge",danmakuMerge)
        .put("searchSource",searchSource.storedValue)
        .put("homeColumns",homeColumns)
        .put("alwaysShowClock",alwaysShowClock)
        .put("hiddenNav",hiddenNav)
        .put("launchRecommend",launchRecommend)
        .put("hiddenHome",hiddenHome)
        .put("reduceAnim",reduceAnim)
}
data class BackupData(
    val favorites: List<Series>,
    val history: List<BackupProgress>,
    val watched: Set<String>,
    val searches: List<RecentSearch>,
    val settings: BackupSettings,
    val later: List<Series> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /**
     * 本机删掉过的观看记录（剧集 ID）。不进备份文件，也不跨设备迁移：
     * 它只是「这台设备上用户已经删了，别再给我合并回来」的一份墓碑。
     */
    val historyDeleted: Set<String> = emptySet()
)

object LibraryBackup {
    const val MAX_BYTES=2*1024*1024
    private val idPattern=Regex("[0-9]{1,30}")
    private fun id(value: String)=value.also { require(idPattern.matches(it)) { "剧集标识无效" } }
    private fun cleanCover(value: String): String=runCatching {
        val uri=URI(value); val host=uri.host.orEmpty().lowercase()
        value.takeIf { it.length<=2048 && uri.scheme=="https" && uri.userInfo==null && host.isNotEmpty() && !host.contains(':') && !host.matches(Regex("[0-9.]+")) && host!="localhost" && !host.endsWith(".local") && !host.endsWith(".lan") && value.none(Char::isISOControl) }.orEmpty()
    }.getOrDefault("")
    private fun cleanText(value: String,max: Int)=value.map { if(it.isISOControl()) ' ' else it }.joinToString("").take(max)
    fun seriesJson(series: Series)=series.copy(title=cleanText(series.title,200),cover=cleanCover(series.cover),description="",badge=cleanText(series.badge,100),tags=cleanText(series.tags,200),author=cleanText(series.author,100),episodeCount=series.episodeCount).toJson()
    fun encode(data: BackupData): String {
        val result=JSONObject().put("format","hgtv.library").put("version",1).put("createdAt",System.currentTimeMillis())
            .put("later",JSONArray(data.later.map(::seriesJson))).put("hidden",JSONArray(data.hidden.toList()))
            .put("favorites",JSONArray(data.favorites.map(::seriesJson))).put("history",JSONArray(data.history.map { it.json() }))
            .put("watched",JSONArray(data.watched.intersect((data.favorites.map { it.id }+data.history.map { it.series.id }).toSet()).take(500)))
            .put("searches",JSONArray(SearchHistory.encode(data.searches.map { it.copy(query=cleanText(it.query,80)) }))).put("settings",data.settings.json()).toString()
        require(result.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份超过 2 MB 限制" }
        return result
    }
    private fun JSONObject.number(key: String,max: Long): Long {
        val value=get(key); require(value is Number) { "数值字段无效" }
        val decimal=value.toDouble(); val integer=value.toLong()
        require(decimal.isFinite() && decimal==integer.toDouble() && integer in 0..max) { "数值范围无效" }; return integer
    }
    private fun JSONObject.string(key: String,max: Int): String {
        val value=get(key); require(value is String && value.length<=max && value.none(Char::isISOControl)) { "文字字段无效" }; return value
    }
    private fun JSONObject.rows(key: String,max: Int): List<JSONObject> {
        val array=getJSONArray(key); require(array.length()<=max) { "记录数量超过上限" }
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun series(o: JSONObject): Series = Series(id(o.string("id",30)),o.string("title",200).also { require(it.isNotBlank()) },cleanCover(o.string("cover",2048)),"",o.string("badge",100),o.string("tags",200),o.string("author",200),o.optInt("episodeCount"),
        upcoming=o.optBoolean("upcoming",false),notice=o.optString("notice","").take(100))
    fun decode(value: String): BackupData {
        require(value.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份超过 2 MB 限制" }
        var depth=0; var quoted=false; var escaped=false
        value.forEach { c ->
            if(escaped) escaped=false
            else if(quoted && c=='\\') escaped=true
            else if(c=='"') quoted=!quoted
            else if(!quoted) { if(c=='{' || c=='[') { depth++; require(depth<=12) { "备份结构过深" } }; if(c=='}' || c==']') depth-- }
        }
        val o=JSONObject(value)
        require(o.getString("format")=="hgtv.library" && o.number("version",1)==1L) { "不支持的备份格式或版本" }
        val later=if(o.has("later")) o.rows("later",100).map(::series) else emptyList()
        require(later.distinctBy { it.id }.size==later.size) { "稍后观看包含重复剧集" }
        val hidden=if(o.has("hidden")) o.getJSONArray("hidden") else JSONArray()
        require(hidden.length()<=500)
        val hiddenIds=(0 until hidden.length()).map { id(hidden.getString(it)) }.toSet()
        val favorites=o.rows("favorites",500).map(::series)
        val history=o.rows("history",200).map { row ->
            val duration=row.number("duration",604_800_000)
            BackupProgress(series(row.getJSONObject("series")),id(row.string("episodeId",30)),row.number("episodeIndex",100_000).toInt(),row.number("position",604_800_000).let { if(duration>0) minOf(it,duration) else it },duration,row.get("completed").also { require(it is Boolean) } as Boolean,row.number("updatedAt",4_102_444_800_000))
        }
        require(favorites.distinctBy { it.id }.size==favorites.size && history.distinctBy { it.series.id }.size==history.size) { "备份包含重复剧集" }
        val watched=o.getJSONArray("watched"); require(watched.length()<=500)
        val watchedIds=(0 until watched.length()).map { id(watched.getString(it)) }.toSet()
        // 搜索历史只有关键词；旧备份里多出来的 `type` 字段直接忽略（不再按类型分开记）。
        val searches=o.rows("searches",20).map { row -> RecentSearch(row.string("query",80).also { require(it.isNotBlank()) }) }
        val settings=o.getJSONObject("settings")
        val quality=settings.number("quality",1080).toInt(); require(quality in PlaybackQuality.options)
        val speed=settings.get("speed"); require(speed is Number && speed.toFloat() in PlaybackSpeed.options)
        val autoNext=settings.get("autoNext"); require(autoNext is Boolean)
        val type=ContentType.entries.firstOrNull { it.storedValue==settings.getString("type") } ?: error("内容类型无效")
        val frame=VideoFrameMode.entries.firstOrNull { it.name==settings.getString("frame") } ?: error("画面模式无效")
        // 弹幕显示方式是后加的字段：旧备份没有它，按「全屏弹幕」处理，旧文件仍然能恢复。
        val danmaku=if(settings.has("danmaku")) DanmakuMode.fromStored(settings.optString("danmaku")) else DanmakuMode.FULL
        // 弹幕字号 / 透明度同样是后加字段：旧备份没有它们，按 100% 处理。
        // 新备份里出现越界值时也不报错，直接夹进合法区间（归一由 DanmakuStyle 负责）。
        val danmakuScale=if(settings.has("danmakuScale")) DanmakuStyle.scale(settings.get("danmakuScale").let { require(it is Number) { "弹幕字号无效" }; it.toFloat() }) else 1f
        val danmakuAlpha=if(settings.has("danmakuAlpha")) DanmakuStyle.alpha(settings.get("danmakuAlpha").let { require(it is Number) { "弹幕透明度无效" }; it.toFloat() }) else 1f
        // 弹幕速度也是后加字段：旧备份没有它，按「标准」处理（未知值同样回落到标准档）。
        val danmakuSpeed=DanmakuSpeed.fromStored(settings.optString("danmakuSpeed").takeIf { settings.has("danmakuSpeed") })
        // 按键绑定同样是后加字段：旧备份没有它，按出厂绑定处理。值本身再走一次
        // [KeyMap.decode] 逐条校验（坏段只丢自己那一条），因此这里只需要挡住长度与控制字符，
        // 不让一份手改过的备份把任意长文本塞进本机设置。
        val keys=if(settings.has("keys")) settings.optString("keys").takeIf { it.length<=400 && it.none(Char::isISOControl) } ?: "" else ""
        // 解码方式也是后加字段：旧备份没有它，按「硬件解码」处理（与集成前的默认行为一致）。
        val decoder=VideoDecoderMode.fromStored(settings.optString("decoder").takeIf { settings.has("decoder") })
        // 相同弹幕聚合也是后加字段：旧备份没有它，按开启处理。
        val danmakuMerge=settings.optBoolean("danmakuMerge",true)
        // 搜索源同样是后加字段：旧备份没有它，按 App 处理。
        val searchSource=SearchSource.fromStored(settings.optString("searchSource").takeIf { settings.has("searchSource") })
        // 首页每行卡片数也是后加字段：只接受 4 / 5 / 6，旧备份与坏值都按 6 处理。
        val homeColumns=if(settings.has("homeColumns")) settings.number("homeColumns",6).toInt().coerceIn(4,8) else 6
        // 始终显示时间同样是后加字段：旧备份没有它，按关闭处理。
        val alwaysShowClock=settings.optBoolean("alwaysShowClock",false)
        // 导航栏圆形入口：逐个开关，值是被隐藏的那些 key；旧备份没有这个字段 = 全部显示。
        val hiddenNav=settings.optString("hiddenNav","").takeIf { it.length<=80 && it.none(Char::isISOControl) }.orEmpty()
        // 启动进入推荐：后加字段，旧备份没有它时按开启处理（与需求 1 的默认行为一致）。
        val launchRecommend=settings.optBoolean("launchRecommend",true)
        // 首页本机记录三栏：同样是后加字段，旧备份没有它 = 三栏全显示。
        val hiddenHome=settings.optString("hiddenHome","").takeIf { it.length<=40 && it.none(Char::isISOControl) }.orEmpty()
        // 「减少动画」：后加字段，旧备份没有它时按关闭处理（与之前的行为一致）。
        val reduceAnim=settings.optBoolean("reduceAnim",false)
        return BackupData(favorites,history,watchedIds,searches,BackupSettings(quality,speed.toFloat(),autoNext,type,frame,danmaku,danmakuScale,danmakuAlpha,danmakuSpeed,keys,decoder,danmakuMerge,searchSource,homeColumns,alwaysShowClock,hiddenNav,debugMode=false,launchRecommend=launchRecommend,hiddenHome=hiddenHome,reduceAnim=reduceAnim),later,hiddenIds)
    }
    /** Existing favorites stay first; only newer imported progress replaces a local record. */
    fun merge(local: BackupData,incoming: BackupData,restoreSettings: Boolean): BackupData {
        val favorites=(local.favorites+incoming.favorites).distinctBy { it.id }.take(500)
        val records=local.history.associateBy { it.series.id }.toMutableMap()
        val watched=local.watched.toMutableSet()
        incoming.history.forEach { candidate ->
            val id=candidate.series.id; val prior=records[id]
            if(prior==null || candidate.updatedAt>prior.updatedAt) {
                records[id]=candidate
                if(id in incoming.watched) watched.add(id) else watched.remove(id)
            }
        }
        incoming.watched.filter { id -> id !in records && local.favorites.none { it.id==id } }.forEach { watched.add(it) }
        val history=records.values.sortedByDescending { it.updatedAt }.take(200)
        val retained=(favorites.map { it.id }+history.map { it.series.id }).toSet()
        // 墓碑跟着本机那份走，但**恢复备份是明确的「把我的记录拿回来」**：
        // 备份里出现的那几条要把墓碑摘掉，否则恢复完界面上仍然看不到它们。
        val deleted=local.historyDeleted - incoming.history.map { it.series.id }.toSet()
        return BackupData(favorites,history,watched.intersect(retained).take(500).toSet(),(local.searches+incoming.searches).distinct().take(20),if(restoreSettings) incoming.settings else local.settings,(local.later+incoming.later).distinctBy { it.id }.take(100),(local.hidden+incoming.hidden).take(500).toSet(),deleted)
    }
}
