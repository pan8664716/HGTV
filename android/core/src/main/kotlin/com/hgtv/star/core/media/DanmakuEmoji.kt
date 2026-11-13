// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 弹幕文本里的表情标签。
 *
 * 平台的弹幕里表情不是字形，而是一个方括号包起来的名字：`[笑哭]`、`【赞】`、`［狗头］`
 * （半角方括号、全角方括号与【】三种都出现过）。照原样画出来，屏幕上就是一串 `[笑哭]` 文字，
 * 看不出表情。这里把标签翻成对应的 Unicode 字形，弹幕层再按字形把表情画成图片
 * （图片在 `app/src/main/assets/danmaku_emoji`，映射见 [assetName]）。
 *
 * 表按「名字」给：同一张脸在平台上有很多叫法（`笑哭` / `爽` / `大笑` 都指 😆 一类），
 * 认不出来的名字**原样保留**，不会把用户打的字吃掉。
 */
object DanmakuEmoji {
    /** 标签名 → 表情字形。名字大小写不敏感（`OK` / `okk` 这类混写由 [glyph] 兜底）。 */
    private val glyphs = mapOf(
        "偷笑" to "🤭",
        "捂嘴笑" to "🤭",
        "笑哭" to "😂",
        "捂脸" to "🤦",
        "微笑" to "🙂",
        "大笑" to "😆",
        "呲牙" to "😁",
        "尬笑" to "😅",
        "害羞" to "😊",
        "奸笑" to "😏",
        "坏笑" to "😏",
        "赞" to "👍",
        "点赞" to "👍",
        "鼓掌" to "👏",
        "流泪" to "😢",
        "大哭" to "😭",
        "泪奔" to "😭",
        "发怒" to "😠",
        "愤怒" to "😡",
        "惊讶" to "😮",
        "惊呆" to "😲",
        "震惊" to "😱",
        "思考" to "🤔",
        "灵光一闪" to "💡",
        "爱慕" to "😍",
        "色" to "😍",
        "酷" to "😎",
        "酷拽" to "😎",
        "调皮" to "😛",
        "吐舌" to "😛",
        "得意" to "😏",
        "闭嘴" to "🤐",
        "困" to "😪",
        "睡" to "😴",
        "睡觉" to "😴",
        "晕" to "😵",
        "流汗" to "😓",
        "冷汗" to "😰",
        "亲亲" to "😘",
        "飞吻" to "😘",
        "拥抱" to "🤗",
        "握手" to "🤝",
        "祈祷" to "🙏",
        "抱拳" to "🙏",
        "加油" to "💪",
        "耶" to "✌️",
        "胜利" to "✌️",
        "OK" to "👌",
        "爱心" to "❤️",
        "比心" to "❤️",
        "心碎" to "💔",
        "玫瑰" to "🌹",
        "礼物" to "🎁",
        "庆祝" to "🎉",
        "蛋糕" to "🎂",
        "啤酒" to "🍺",
        "咖啡" to "☕️",
        "狗头" to "🐶",
        "爽" to "😆",
        "怒" to "😠",
        "发呆" to "😶",
        "抠鼻" to "👃",
        "舔屏" to "😋",
        "看" to "👀",
        "感谢" to "🙏",
        "抱抱你" to "🤗",
        "尴尬流汗" to "😅",
        "戳手手" to "👉👈",
        "星星眼" to "🤩",
        "杀马特" to "😎",
        "黄脸干杯" to "🍻",
        "抱紧自己" to "🤗",
        "拜拜" to "👋",
        "热化了" to "🥵",
        "黄脸祈祷" to "🙏",
        "懵" to "😵",
        "举手" to "🙋",
        "加功德" to "🙏",
        "摊手" to "🤷",
        "无语流汗" to "😓",
        "续火花吧" to "🔥",
        "点火" to "🔥",
        "哭哭" to "😢",
        "吐舌小狗" to "🐶😛",
        "送花" to "💐",
        "爱心手" to "❤️",
        "贴贴" to "🤗",
        "灵机一动" to "💡",
        "打脸" to "🤦",
        "机智" to "🧠",
        "送心" to "💝",
        "666" to "🤙",
        "来看我" to "👀",
        "一起加油" to "💪",
        "哈欠" to "😪",
        "衰" to "😞",
        "疑问" to "❓",
        "泣不成声" to "😭",
        "小鼓掌" to "👏",
        "大金牙" to "😁",
        "石化" to "😨",
        "吐血" to "😵💔",
        "可怜" to "🥺",
        "嘘" to "🤫",
        "撇嘴" to "😒",
        "憨笑" to "😄",
        "抓狂" to "😫",
        "钱" to "💰",
        "恐惧" to "😨",
        "愉快" to "😄",
        "快哭了" to "🥺",
        "翻白眼" to "🙄",
        "互粉" to "🤝",
        "我想静静" to "😔",
        "委屈" to "🥺",
        "鄙视" to "😒",
        "再见" to "👋",
        "紫薇别走" to "🥺👋",
        "听歌" to "🎧",
        "求抱抱" to "🤗",
        "绝望的凝视" to "😑",
        "不失礼貌的微笑" to "🙂",
        "不看" to "🙈",
        "裂开" to "💔",
        "干饭人" to "🍚",
        "呆无辜" to "😇",
        "白眼" to "🙄",
        "猪头" to "🐷",
        "冷漠" to "😐",
        "暗中观察" to "👀",
        "二哈" to "🐶",
        "菜狗" to "🐶🥬",
        "黑脸" to "🌚",
        "展开说说" to "💬",
        "蜜蜂狗" to "🐶🐝",
        "柴犬" to "🐕",
        "摸头" to "🖐️",
        "皱眉" to "😟",
        "擦汗" to "😓",
        "红脸" to "😳",
        "做鬼脸" to "😜",
        "强" to "👍",
        "如花" to "🤡",
        "吐" to "🤮",
        "惊喜" to "🤩",
        "敲打" to "🔨",
        "奋斗" to "💪",
        "吐彩虹" to "🌈",
        "嘿哈" to "💪",
        "惊恐" to "😱",
        "囧" to "😖",
        "难过" to "😞",
        "斜眼" to "😒",
        "阴险" to "😏",
        "悠闲" to "😌",
        "咒骂" to "🤬",
        "吃瓜群众" to "🍉",
        // —— 平台实测在用、参考表漏掉的名字 ——
        // 这 12 个名字是在 60 部剧、1.3 万条弹幕里逐条盘出来的（见 CHANGELOG.md 的 0.15.2）：
        // 参考实现的表里没有它们，所以 `[哭]`、`[笑]` 这类最高频的标签以前画不出来。
        // 字形按抖音表情集的同名/同义条目对：`笑` 就是抖音的 `xiao.png`（愉快脸），
        // `哭` 是 `kuku.png`（哭哭），`吃瓜` 是 `chiguaqunzhong.png`，`敬礼` 是 `jingli.png`。
        "哭" to "😭",
        "笑" to "😄",
        "没看够" to "🤩",
        "探究" to "🔍",
        "吃瓜" to "🍉",
        "敬礼" to "🫡",
        "求爆更" to "🙏",
        "顶帖" to "⬆️",
        "什么" to "❓",
        "你细品" to "🧐",
        "重拳出击" to "👊",
        "学会了" to "🤓",
        "挑眉" to "🤨",
        "馋" to "🤤",

        "绿帽子" to "🧢",
        "敢怒不敢言" to "😤",
        "求求了" to "🙏",
        "眼含热泪" to "🥺",
        "叹气" to "😮‍💨",
        "好开心" to "😄",
        "不是吧" to "😲",
        "鞠躬" to "🙇",
        "躺平" to "🛌",
        "九转大肠" to "🍲",
        "不你不想" to "🙅",
        "一头乱麻" to "😵",
        "kisskiss" to "😘",
        "你不大行" to "👎",
        "噢买尬" to "😱",
        "宕机" to "😵",
        "苦涩" to "😣",
        "逞强落泪" to "🥲",
        "求机位-黄脸" to "📍",
        "求机位3" to "📍",
        "精选" to "⭐",
        "强壮" to "💪",
        "碰拳" to "👊",
        "击掌" to "🙌",
        "左上" to "↖️",
        "勾引" to "😏",
        "拳头" to "✊",
        "弱" to "👎",
        "右边" to "👉",
        "左边" to "👈",
        "嘴唇" to "💋",
        "凋谢" to "🥀",
        "垃圾" to "🗑️",
        "撒花" to "🎉",
        "加一" to "➕",
        "减一" to "➖",
        "okk" to "👌",
        "V5" to "💪",
        "绝" to "🤩",
        "给力" to "💪",
        "红包" to "🧧",
        "屎" to "💩",
        "发" to "💰",
        "18禁" to "🔞",
        "炸弹" to "💣",
        "西瓜" to "🍉",
        "加鸡腿" to "🍗",
        "握爪" to "🐾",
        "太阳" to "☀️",
        "月亮" to "🌙",
        "给跪了" to "🙇",
        "蕉绿" to "😰",
        "扎心" to "💔",
        "胡瓜" to "🥒",
        "打call" to "📣",
        "栓Q" to "😓",
        "雪花" to "❄️",
        "圣诞树" to "🎄",
        "平安果" to "🍎",
        "圣诞帽" to "🎅",
        "气球" to "🎈",
        "烟花" to "🎆",
        "福" to "🧧",
        "candy" to "🍬",
        "糖葫芦" to "🍡",
        "鞭炮" to "🧨",
        "元宝" to "💰",
        "灯笼" to "🏮",
        "锦鲤" to "🎏",
        "巧克力" to "🍫",
        "戒指" to "💍",
        "棒棒糖" to "🍭",
        "纸飞机" to "✈️",
        "粽子" to "🍙",
    )

    /**
     * 表情字形 → `assets/danmaku_emoji` 下的文件名。
     *
     * 键是**完整的一个表情**：可能带变体选择符（`✌️`）、肤色、键帽，或用 ZWJ 连出来（`😮‍💨`）。
     * 图片是 Microsoft Fluent Emoji（MIT，见 assets/danmaku_emoji/LICENSE.txt）。
     */
    private val assets = mapOf(
        "🔍" to "1f50d.webp",
        "🫡" to "1fae1.webp",
        "⬆️" to "2b06-fe0f.webp",
        "🤨" to "1f928.webp",
        "🧐" to "1f9d0.webp",
        "🤓" to "1f913.webp",
        "🤤" to "1f924.webp",
        "↖️" to "2196-fe0f.webp",
        "☀️" to "2600-fe0f.webp",
        "☕️" to "2615-fe0f.webp",
        "✈️" to "2708-fe0f.webp",
        "✊" to "270a.webp",
        "✌️" to "270c-fe0f.webp",
        "❄️" to "2744-fe0f.webp",
        "❓" to "2753.webp",
        "❤️" to "2764-fe0f.webp",
        "➕" to "2795.webp",
        "➖" to "2796.webp",
        "⭐" to "2b50.webp",
        "🌈" to "1f308.webp",
        "🌙" to "1f319.webp",
        "🌚" to "1f31a.webp",
        "🌹" to "1f339.webp",
        "🍉" to "1f349.webp",
        "🍎" to "1f34e.webp",
        "🍗" to "1f357.webp",
        "🍙" to "1f359.webp",
        "🍚" to "1f35a.webp",
        "🍡" to "1f361.webp",
        "🍫" to "1f36b.webp",
        "🍬" to "1f36c.webp",
        "🍭" to "1f36d.webp",
        "🍲" to "1f372.webp",
        "🍺" to "1f37a.webp",
        "🍻" to "1f37b.webp",
        "🎁" to "1f381.webp",
        "🎂" to "1f382.webp",
        "🎄" to "1f384.webp",
        "🎅" to "1f385.webp",
        "🎆" to "1f386.webp",
        "🎈" to "1f388.webp",
        "🎉" to "1f389.webp",
        "🎏" to "1f38f.webp",
        "🎧" to "1f3a7.webp",
        "🏮" to "1f3ee.webp",
        "🐕" to "1f415.webp",
        "🐝" to "1f41d.webp",
        "🐶" to "1f436.webp",
        "🐷" to "1f437.webp",
        "🐾" to "1f43e.webp",
        "👀" to "1f440.webp",
        "👃" to "1f443.webp",
        "👈" to "1f448.webp",
        "👉" to "1f449.webp",
        "👊" to "1f44a.webp",
        "👋" to "1f44b.webp",
        "👌" to "1f44c.webp",
        "👍" to "1f44d.webp",
        "👎" to "1f44e.webp",
        "👏" to "1f44f.webp",
        "💋" to "1f48b.webp",
        "💍" to "1f48d.webp",
        "💐" to "1f490.webp",
        "💔" to "1f494.webp",
        "💝" to "1f49d.webp",
        "💡" to "1f4a1.webp",
        "💣" to "1f4a3.webp",
        "💩" to "1f4a9.webp",
        "💪" to "1f4aa.webp",
        "💬" to "1f4ac.webp",
        "💰" to "1f4b0.webp",
        "📍" to "1f4cd.webp",
        "📣" to "1f4e3.webp",
        "🔞" to "1f51e.webp",
        "🔥" to "1f525.webp",
        "🔨" to "1f528.webp",
        "🖐️" to "1f590-fe0f.webp",
        "🗑️" to "1f5d1-fe0f.webp",
        "😁" to "1f601.webp",
        "😂" to "1f602.webp",
        "😄" to "1f604.webp",
        "😅" to "1f605.webp",
        "😆" to "1f606.webp",
        "😇" to "1f607.webp",
        "😊" to "1f60a.webp",
        "😋" to "1f60b.webp",
        "😌" to "1f60c.webp",
        "😍" to "1f60d.webp",
        "😎" to "1f60e.webp",
        "😏" to "1f60f.webp",
        "😐" to "1f610.webp",
        "😑" to "1f611.webp",
        "😒" to "1f612.webp",
        "😓" to "1f613.webp",
        "😔" to "1f614.webp",
        "😖" to "1f616.webp",
        "😘" to "1f618.webp",
        "😛" to "1f61b.webp",
        "😜" to "1f61c.webp",
        "😞" to "1f61e.webp",
        "😟" to "1f61f.webp",
        "😠" to "1f620.webp",
        "😡" to "1f621.webp",
        "😢" to "1f622.webp",
        "😣" to "1f623.webp",
        "😤" to "1f624.webp",
        "😨" to "1f628.webp",
        "😪" to "1f62a.webp",
        "😫" to "1f62b.webp",
        "😭" to "1f62d.webp",
        "😮" to "1f62e.webp",
        "😮‍💨" to "1f62e-200d-1f4a8.webp",
        "😰" to "1f630.webp",
        "😱" to "1f631.webp",
        "😲" to "1f632.webp",
        "😳" to "1f633.webp",
        "😴" to "1f634.webp",
        "😵" to "1f635.webp",
        "😶" to "1f636.webp",
        "🙂" to "1f642.webp",
        "🙄" to "1f644.webp",
        "🙅" to "1f645.webp",
        "🙇" to "1f647.webp",
        "🙈" to "1f648.webp",
        "🙋" to "1f64b.webp",
        "🙌" to "1f64c.webp",
        "🙏" to "1f64f.webp",
        "🛌" to "1f6cc.webp",
        "🤐" to "1f910.webp",
        "🤔" to "1f914.webp",
        "🤗" to "1f917.webp",
        "🤙" to "1f919.webp",
        "🤝" to "1f91d.webp",
        "🤡" to "1f921.webp",
        "🤦" to "1f926.webp",
        "🤩" to "1f929.webp",
        "🤫" to "1f92b.webp",
        "🤬" to "1f92c.webp",
        "🤭" to "1f92d.webp",
        "🤮" to "1f92e.webp",
        "🤷" to "1f937.webp",
        "🥀" to "1f940.webp",
        "🥒" to "1f952.webp",
        "🥬" to "1f96c.webp",
        "🥲" to "1f972.webp",
        "🥵" to "1f975.webp",
        "🥺" to "1f97a.webp",
        "🧠" to "1f9e0.webp",
        "🧢" to "1f9e2.webp",
        "🧧" to "1f9e7.webp",
        "🧨" to "1f9e8.webp",
    )

    /**
     * 一个标签：`[名字]` / `【名字】` / `［名字］`。
     *
     * 名字最长 16 个字符，且不含任何括号与换行——这样 `[前面高能]然后[笑哭]` 不会把两段连起来吃掉，
     * 正文里本来就有的方括号（`[1,2]`、`[2024]`）也大多会落到「认不出的名字」而原样保留。
     */
    private val tag = Regex("""\[([^\[\]【】［］\r\n]{1,16})\]|【([^\[\]【】［］\r\n]{1,16})】|［([^\[\]【】［］\r\n]{1,16})］""")

    /** 标签总数 / 图片总数：测试与资源核对用。 */
    val tagCount get() = glyphs.size
    val assetCount get() = assets.size

    /** 标签名 → 字形；认不出来返回 null（调用方保留原文）。 */
    fun glyph(name: String): String? = glyphs[name] ?: glyphs[name.lowercase()] ?: glyphs[name.uppercase()]

    /** 字形 → 图片文件名；不在表里返回 null（正文里本来就有的 emoji 交给系统字体画）。 */
    fun assetName(glyph: String): String? = assets[glyph]

    /** 表里所有标签名（测试与资源核对用）。 */
    val tagNames: Set<String> get() = glyphs.keys

    /**
     * 片段缓存条数上限。
     *
     * 一集最多 3000 条（见 `ContentRepository.MAX_DANMAKU`）。原来定 512 就意味着长剧集里
     * 绝大多数条目永远缓存不上——弹幕是**边拉边画**的（40 个 30 秒窗口），
     * 每个窗口重排时都要把整集重切一遍。一条弹幕的片段总和就是它自己的长度，
     * 4096 条撑死几百 KB。
     */
    private const val CACHE_LINES = 4096

    /**
     * 把文本里的标签换成字形，其余原样保留。
     *
     * 纯文本场合用它（日志、测试、没有图片可画的降级路径）；播放画面上的弹幕走 [parts]，
     * 那一条会把表情切成独立片段、画成图片。
     */
    fun displayText(text: String): String = tag.replace(text) { match ->
        val name = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
        glyph(name) ?: match.value
    }

    /**
     * 切好的片段按弹幕原文缓存。
     *
     * 弹幕是**边拉边画**的：每拿到一个 30 秒窗口就整批重排一次轨道，同一批文本要切几十遍。
     * 缓存只按条数封顶（不按字节），一条弹幕的片段总和就是它自己的长度，512 条撑死几十 KB。
     *
     * 淘汰走 [lruPut]，**不用匿名子类重写 `removeEldestEntry`**：那种写法会生成一条
     * `invoke-super LinkedHashMap.getOrDefault`（API 24）的桥方法，4.4 上是颗雷（见 [lruPut]）。
     */
    private val cache = LinkedHashMap<String, List<DanmakuPart>>(64,0.75f,true)

    /**
     * 把一条弹幕拆成「文字片段 / 表情片段」。
     *
     * 先按标签换字形，再按字形簇逐个切：一个表情可能由多个码位组成（`✌️` 带变体选择符、
     * `😮‍💨` 用 ZWJ 连、`👉👈` 是两个表情并排），必须整簇一起查表，否则会拆出半个表情、
     * 把两张图叠在一起。表情片段的 [DanmakuPart.text] 是字形本身：
     * 图片还没解码好时按字形画字，屏幕上不会出现空档。
     */
    fun parts(text: String): List<DanmakuPart> = synchronized(cache) {
        cache[text] ?: split(text).also { cache.lruPut(CACHE_LINES,text,it) }
    }

    /** 真正做切分的地方；[parts] 负责缓存。返回的列表不可变，调用方可以直接拿着用。 */
    private fun split(text: String): List<DanmakuPart> {
        val out = mutableListOf<DanmakuPart>()
        val buffer = StringBuilder()
        fun flush() { if(buffer.isNotEmpty()) { out+=DanmakuPart(buffer.toString(),null); buffer.clear() } }
        val plain = displayText(text)
        var index = 0
        while (index < plain.length) {
            val end = clusterEnd(plain,index)
            val cluster = plain.substring(index,end)
            val asset = assets[cluster]
            if (asset==null) buffer.append(cluster) else { flush(); out+=DanmakuPart(cluster,asset) }
            index = end
        }
        flush()
        return java.util.Collections.unmodifiableList(out)
    }

    /** 从 [start] 起一个字形簇的结束下标：基字符 + 变体选择符 / 肤色 / 键帽，以及 ZWJ 连出去的部分。 */
    private fun clusterEnd(text: String,start: Int): Int {
        var index = start + Character.charCount(text.codePointAt(start))
        while (index < text.length) {
            val code = text.codePointAt(index)
            when {
                code == 0xFE0E || code == 0xFE0F || code == 0x20E3 || code in 0x1F3FB..0x1F3FF -> index += Character.charCount(code)
                // ZWJ：把连在后面的那个字形也吃进来（`😮‍💨` 这类组合表情）。
                code == 0x200D && index + Character.charCount(code) < text.length -> index = clusterEnd(text,index + Character.charCount(code))
                else -> return index
            }
        }
        return index
    }
}

/**
 * 一条弹幕里的一段：[asset] 为空是文字（按系统字体画），非空是一个表情（画 `assets/danmaku_emoji` 里的图）。
 * [text] 始终是能直接画出来的字形：图片没到位时用它兜底。
 */
data class DanmakuPart(val text: String,val asset: String?)
