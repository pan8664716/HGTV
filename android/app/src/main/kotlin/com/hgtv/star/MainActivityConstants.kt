// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.view.*
import android.widget.*
import com.google.android.exoplayer2.*
import com.hgtv.star.core.*
import com.hgtv.star.tv.TvStyle

// 本文件是从 MainActivity.kt 拆出来的「全站常量与静态表（标签下标、栅格列数、面板行号、阈值……）」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

/** 远端长列表（排行榜页的四个榜单、搜索结果）底部「展示更多」按钮的 tag，同时用作「焦点留在该按钮上」的标记。 */
internal const val LOAD_MORE="load:more"
/**
 * 设备身份自愈的预算：一次进程最多重建 2 次，两次之间至少隔 20 秒（见 `healDeviceIdentity`）。
 *
 * 重建要发一次真实的注册请求。服务端整体故障时，没有上限就会变成「每次搜索都先注册一次」；
 * 而正常情况下第一次重建就足够——重建后的档案与 `device_id` 是自洽的。
 */
internal const val DEVICE_HEAL_LIMIT=2
internal const val DEVICE_HEAL_GAP_MS=20_000L
/**
 * 顶部四个页面入口的标签下标。
 *
 * 圆形入口的顺序是 **搜索 → 分类 → 榜单 → 设置 → 个人信息**（`nav` 的下标 0..4，
 * 而 [MainActivity.navEntry] 用 `nav[tab-1]` 取，所以这五个标签从 1 开始编号）。
 * 首页是 0：品牌「HGTV」是标识不是按钮，不占入口位。
 */
internal const val TAB_HOME=0
internal const val TAB_SEARCH=1
internal const val TAB_CATEGORY=2
internal const val TAB_RANK=3
internal const val TAB_SETTINGS=4
/**
 * 「个人信息」页：设置按钮右边的那一个入口。
 *
 * 顺序是 **搜索 → 分类 → 榜单 → 设置 → 个人信息**（`nav` 下标 0..4），
 * 它显示的是账号侧的数据（收藏 / 历史 / 点赞 / 预约），与本机记录是两套东西。
 */
internal const val TAB_PROFILE=5

/**
 * 「本机文件导入账号参数」的请求码（[Activity.startActivityForResult]）。
 *
 * 用系统文件选择器（`ACTION_OPEN_DOCUMENT`）而不是自己做浏览界面：
 * 电视上的文件来源五花八门（U 盘 / 下载 / 文件管理应用），交给系统那一套最省事，
 * 也能让用户直接选中 `cookieStore.xml`。
 */
internal const val REQUEST_ACCOUNT_FILE=0x51

/**
 * 「会话镜像要写外部存储」的授权请求码（见 [MainActivity.requestMirrorStorage]）。
 *
 * 只有 Android 6–9（23–28）会用到：那个区间写 `Android/data/<包名>/` 需要运行时授权，
 * 而 4.4–5.1 是安装期权限、10 起不需要权限。
 */
internal const val REQUEST_MIRROR_STORAGE=0x52

/**
 * 「清空应用缓存」要外部存储权限的请求码（见 [MainActivity.performLocalReset]）。
 *
 * 与 [REQUEST_MIRROR_STORAGE] 分开：那一条拿到权限后要去恢复会话，这一条只是让
 * `/sdcard/.hgtv` 删得掉；两者同时复用会让「恢复出厂」把刚清掉的会话又救回来。
 */
internal const val REQUEST_RESET_STORAGE=0x53

/**
 * 系统语音搜索（[android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH]）的请求码。
 *
 * 与文件选择、存储授权分开：语音结果回来时要**先重建搜索页、再消费结果**，
 * 不能和账号导入那条路径共用分支。
 */
internal const val REQUEST_VOICE=0x54

/** 语音识别结果与输入框共用同一条长度上限（见输入框的 `LengthFilter`）。 */
internal const val VOICE_QUERY_MAX=80

/**
 * 账号数据拉取失败后的自动重试（见 `MainActivity.loadAccount` 的失败分支）。
 *
 * 为什么需要：重装后第一次启动时，登录态刚从本机备份恢复回来，首屏那一批请求又都挤在一起，
 * 这一次账号拉取失败并不罕见。原先失败了就只能靠用户自己去点一次「我的」把数据逼出来；
 * 这里自动补两次，首页那两栏（历史 / 收藏）自己就填上了。
 */
internal const val ACCOUNT_RETRY_MS=4000L
internal const val ACCOUNT_RETRIES=2


/**
 * 「我的」页的四个栏目：收藏 / 历史 / 点赞 / 预约。
 *
 * 与首页那三栏（历史记录 / 我的收藏 / 稍后观看）**不是同一份数据**：这里是账号侧的，
 * 走 App 接口（见 `ContentRepository.accountData`），本机记录不受影响。
 */
internal val ACCOUNT_TABS = listOf(
"history" to "历史",
"favorites" to "收藏",
"likes" to "点赞",
"reserves" to "预约"
)

/**
 * 页头随滚动收起时，「列表算回到最上边」的阈值（dp）。
 *
 * 小于等于它就是展开：留一点余量是因为 ScrollView 在内容刚好一屏、或刚布局完时
 * 会有几像素的偏移，严格按 0 判断会让页头在顶部来回抖。
 */
internal const val HEADER_SHOW_THRESHOLD=12

/**
 * 账号数据缓存的新鲜期。
 *
 * 这段时间内进「我的」页 / 收藏 / 历史**不再联网**，直接用上次那一份（见 [MainActivity.loadAccount]）。
 * 账号侧的五条数据（资料 / 收藏 / 历史 / 点赞 / 预约）不是每秒在变：进一页就打五个带签名的请求，
 * 换来的只是「页面转一圈」。想立刻要最新的用页头那枚「刷新」。
 */
internal const val ACCOUNT_FRESH_MS=5*60*1000L

/**
 * 预取下一页的触发点：**已加载的内容被消耗掉这么多**（百分比）就开始取下一页。
 *
 * 判据必须按**比例**算，不能按「还剩几行」算。原先写的是「焦点走到倒数第 2 行」，
 * 按一页 60 部（10 行）折算下来要到第 48 部才触发——用户按住下键时早就撞到底了，
 * 读到的就是「焦点走到哪儿才加载那一片」。
 *
 * 取 50：一页 100 部里走到第 50 部就取下一页，剩下 50 部（约 8 行）当缓冲，
 * 够好几个请求往返（实测一页 794ms）。宁可早取——预取是静默的，早取的代价只是
 * 队列里多几张图；晚取的代价是用户直接看到灰框。
 *
 * 之所以用比例而不是固定条数：翻了几页之后已加载的总量在长，缓冲也该跟着长——
 * 固定留 50 部的话，加载到 1000 部时那点缓冲就不够一个往返了。
 */
internal const val PREFETCH_TRIGGER_PERCENT=50

/**
 * 一次预取最多**连取几页**。
 *
 * 给「按住下键不放」那种走法留的余量：一页刚落地就又被走穿时，允许再补一页。
 * 有上限才不会把「一次预取」滚成把整份目录拉下来（追加落地后会按同一个比例判据重新判断，
 * 没走穿就自然停手）。
 */
internal const val PREFETCH_PAGES=2
/**
 * 预取窗口的半径（行数）：在**物化窗口**的上下各这么多行之内提前取封面。
 *
 * 一屏约 17 行（1080p 六列、卡片 304 宽），取 12 行 ≈ 0.7 屏——加上可见的那一屏，
 * 前后各留出不到一屏的缓冲。这个量与「焦点走多快」无关，因此按住下键时
 * 下面那一屏的图已经在路上（见 [MainActivity.prefetchAroundWindow]）。
 *
 * 一页现在是 100 条（`ContentRepository.LOGICAL_PAGE_SIZE`，各条路统一），
 * 约 17 行；12 行的半径意味着「一页刚看完约 2/3 的时候，下一页的图已经在路上」——
 * 与 `PREFETCH_TRIGGER_PERCENT=50` 那条判据是同一个意图（提前量要够一个请求往返）。
 *
 * 不取更大：排在队尾的来不及在用户看到之前完成，取多了只是把真正要用的那几张挤到后面。
 */
internal const val PREFETCH_ROWS=12

/** 需要登录态的功能在未导入凭据时的统一提示。 */
internal const val SIGN_IN_TOAST="需要先在「我的」页导入账号参数（sessionid）"

/** 「我的」页导航栏里那个圆形头像的直径（与工具条同一行，不能比圆形入口大）。 */
internal const val PROFILE_AVATAR=40

/** 卡片一行的默认张数（6）：首页剧架与所有卡片栅格共用的基线宽度。 */
internal const val GRID_COLUMNS_DEFAULT=6

/**
 * 当前设置下一行放几张卡片：4 / 5 / 6 / 7 / 8。
 *
 * **全局生效**：首页剧架、榜单 / 分类 / 搜索 / 收藏 / 历史 / 我的 这些页面共用同一份设置，
 * 因此 `GRID_COLUMNS_DEFAULT` 只作为「6 列的基准宽度」，不能再当常数用。
 */
internal val MainActivity.gridColumns: Int
    get() = if (library.isLoaded) library.homeColumns.coerceIn(4, 8) else GRID_COLUMNS_DEFAULT

/**
 * 芯片行（分类页的二级分类、搜索页的提示词）的固定高度（dp）。
 *
 * 紧凑档按钮底板 40dp + 两条柔光带 12dp = 52dp，上下各留 8dp 给焦点放大后的描边。
 * 这两行**必须固定高度**：换一级分类时小类的数量会变（8 / 24 / 8），
 * 提示词更是随输入实时增减；高度跟着内容走的话下面的卡片行会跟着上下跳。
 */
internal const val CHIP_ROW_HEIGHT=68

/**
 * 分类页筛选行的高度（dp）。芯片用 [com.hgtv.star.tv.ButtonSize.DENSE]
 * （30dp 底板 + 上下各 6dp 柔光带 = 42dp），行内不再额外留纵向间距。
 */
internal const val FILTER_ROW_HEIGHT=42

/**
 * 搜索框的提示文案与它的字号。
 *
 * 正文是 20sp 粗体，提示跟着那么大会被截断成半句（「输入剧名或关键词（遥控器选字 / 外接…」）；
 * 提示单独降到 [SEARCH_HINT_TEXT]，整句「怎么打字」的说明就都放得下了。
 */
internal const val SEARCH_HINT="输入剧名或关键词 · 可按「语音」说话或遥控器选字"
internal const val SEARCH_HINT_TEXT=12f

/**
 * 搜索页导航栏里输入框与两个按钮的档位。
 *
 * 导航栏那一行还挤着四个圆形入口，输入框与按钮按常规档（48dp 底板 / 16sp）摆会太占地方：
 * 输入框收到 [SEARCH_INPUT_HEIGHT]、按钮用紧凑档（40dp / 15sp），
 * 整行因此矮一档、也短一档，给标题与圆形入口让出宽度。
 * 输入框正文跟着按钮字号走（[SEARCH_INPUT_TEXT]），一行里的字读起来才是一个体系。
 */
internal const val SEARCH_INPUT_HEIGHT=TvStyle.BUTTON_COMPACT
internal const val SEARCH_INPUT_TEXT=17f

/**
 * 搜索页导航栏里输入框的宽度上下限（dp）。
 *
 * 这一行除了输入框与「搜索 / 手机输入」两枚按钮，还要摆七个圆形入口（含「推荐」），
 * 而各屏的可用宽度差别很大（1080p / 280dpi 上整行约 1083dp，4K 电视能到两千多）。
 * 因此输入框的宽度**按实测分配**：先让兄弟控件各自量出真实宽度，剩下的才是输入框的，
 * 并夹在这两个数之间——窄屏优先保住右边那排圆形入口（它们一寸都不能少），
 * 宽屏也不至于让输入框长成一条横幅。实现在 `MainActivitySearch.fitSearchRow`。
 */
internal const val SEARCH_INPUT_WIDTH=300
internal const val SEARCH_INPUT_MIN_WIDTH=150

/**
 * 「搜索 / 手机输入」两枚按钮之间的间距（dp）。
 *
 * 比 [TvStyle.GAP_TIGHT]（8dp）再紧一格：这两枚与输入框是一个动作组，
 * 彼此靠近才读得出「同一组」，省下来的几 dp 全给输入框。
 */
internal const val SEARCH_BUTTON_GAP=6

/** 搜索提示词一次取几条（接口上限 20，不传按 10）。 */
internal const val SUGGEST_COUNT=10

/** 输入停顿多久才去取提示词：每敲一个字都发请求会把接口打满，也会让这一行一直闪。 */
internal const val SUGGEST_DEBOUNCE=220L

/**
 * 首帧（还没量到视口高度时）至少物化几行。
 *
 * 只是「先铺几行再说」的起点：第一帧布局完成后 [MainActivity.syncGridWindow] 会按真实
 * 视口高度重算窗口，多出来的行进回收池。
 */
internal const val GRID_WINDOW_MIN_ROWS=4

/**
 * 窗口最多物化几行（硬上限）。
 *
 * 视口算出来的窗口本来就只有一屏多两行；这个上限是给「必须包含某一行」那条兜底规则用的：
 * 万一要落到的行离视口很远（恢复的焦点、跳转），**不能**把中间那几百行一次性物化出来，
 * 而是先把列表滚过去、再按新的视口算窗口（见 `MainActivity.focusGridIndex`）。
 */
internal const val GRID_WINDOW_MAX_ROWS=12

/** 远端长列表最多向下追加到第几页（与 [ContentRepository.home] 的 `require(page in 1..100)` 对齐）。 */
internal const val MAX_CATALOG_PAGE=100

/**
 * 设置页「主题外观」一行放几个主题芯片。
 * 20 套配色排成 5 行（每行 4 个），比「一行一个主题」省下四分之三的高度，
 * 芯片宽度按屏宽等分，中文名在 720p 画布上也放得下。
 */
internal const val THEME_GRID_COLUMNS=4

/**
 * 设置页「按键设置」那一栏一行放几格。
 *
 * 六个键（上 / 下 / 左 / 右 / 菜单 / 确定）排成两列三行：每一格都要写出「键名 · 当前动作」，
 * 一行放四格（跟着主题网格走）只会把文案挤成省略号，而两列正好让「上 / 下」「左 / 右」
 * 「菜单 / 确定」各自成行——用户找某一个键时按它旁边的那个键去数就行。
 */
internal const val KEY_GRID_COLUMNS=2

/**
 * 顶部导航栏里可以单独隐藏的圆形入口（五枚，设置页每行四枚排开）。
 *
 * 「设置」不在这里：它是唯一能回到设置页的入口（关掉就再也打不开），**始终显示**。
 * key 同时用于落盘（见 `Library.hiddenNav`）与设置页那一行的 `tag`。
 * 设置页「功能显示」每行放 [NAV_GRID_COLUMNS] 枚（与主题网格同列宽）。
 */
internal val NAV_ENTRIES=listOf(
    // 「推荐」排第一：它和搜索按钮挨着，位置最靠左；关掉之后导航栏不再有这枚入口，
    // 打开应用也不再自动进推荐（联动见 `Library.setNavHidden` 与 `Library.launchRecommend`）。
    "recommend" to "推荐",
    "search" to "搜索",
    "category" to "分类",
    "rank" to "榜单",
    "profile" to "我的"
)
internal val NAV_KEYS=NAV_ENTRIES.map { it.first }.toSet()
/**
 * 首页三栏本机记录（历史记录 / 我的收藏 / 稍后观看）：设置页里各有一枚「是否显示」的开关。
 *
 * 与导航栏那五枚（[NAV_ENTRIES]）不是一组：那五枚管的是**页面入口**，这三枚管的是
 * **首页上的栏目**。关掉之后首页不再铺那一栏（也就没有行末「查看全部」的入口），
 * 但记录本身一份不删——重新打开开关，内容原样回来。
 * key 同时用于落盘（见 `Library.hiddenHome`）与设置页那一格的 `tag`。
 */
internal val HOME_ENTRIES=listOf(
    "history" to "历史记录",
    "favorites" to "我的收藏",
    "later" to "稍后观看"
)
// 白名单只有一个来源（core 的 [com.hgtv.star.core.HOME_KEYS]）：落盘解码与设置页开关
// 用的是同一份 key，改名时不会出现「界面认这一套、磁盘认另一套」。
internal val HOME_KEYS=com.hgtv.star.core.HOME_KEYS
/** 设置页「功能显示」一行放几枚开关（与 [THEME_GRID_COLUMNS] 同列宽，五枚即两行）。 */
internal const val NAV_GRID_COLUMNS=4
/** 「推荐」那一枚入口的 key：它与「打开应用是否进入推荐」联动。 */
internal const val NAV_KEY_RECOMMEND="recommend"
/** 设置页某一行重建后的落点前缀：`settings-nav-<key>`。 */
internal const val SETTINGS_TAG_NAV_PREFIX="settings-nav-"
/** 设置页「调试模式」那一行的 tag：整页重建后焦点按它落回去。 */
internal const val SETTINGS_TAG_DEBUG="settings-debug"
/** 设置页「打开应用进入推荐」那一行的 tag。 */
internal const val SETTINGS_TAG_LAUNCH_RECOMMEND="settings-launch-recommend"
/** 设置页「减少动画优化性能」那一行的 tag（只在非低配设备上出现）。 */
internal const val SETTINGS_TAG_REDUCE_ANIM="settings-reduce-anim"
/** 设置页「本机记录显示」那一格的 tag 前缀：`settings-home-<key>`。 */
internal const val SETTINGS_TAG_HOME_PREFIX="settings-home-"

/** 设置页「播放显示」四格的 tag 前缀：`settings-play-<key>`。 */
internal const val SETTINGS_TAG_PLAY_PREFIX="settings-play-"

/** 设置页「短剧自动全屏」那一格。 */
internal const val SETTINGS_TAG_AUTO_FULLSCREEN="settings-auto-fullscreen"

/**
 * 设置页「使用设备」那一格（自动 / 手机 / 平板 / 电视）。
 *
 * 选中之后要按新形态重建整页（触屏手势层 / 底部控制条 / 旋转方向都是建页面时定的），
 * 重建完焦点按这个 tag 落回同一格。
 */
internal const val SETTINGS_TAG_DEVICE_FORM="settings-device-form"

/**
 * 播放画面左上角那一条里的四项（设置页「播放显示」一行四格）。
 *
 * `key` 与 [com.hgtv.star.data.Library] 上的四个偏好一一对应；
 * 顺序就是画面上的排列顺序：**剧名 → 剧集 → 网速 → 时间**。
 */
internal val PLAY_INFO_ENTRIES=listOf(
    "title" to "剧名",
    "episode" to "剧集",
    "speed" to "网速",
    "clock" to "时间"
)

/**
 * 播放设置面板的行号。
 *
 * 面板里好几处「改完就地重画这一行、再把焦点按回同一格」的调用都按行号寻址，
 * 中间插一行就会全部错位（改了清晰度结果选中态落在倍速上）。写死数字的代价已经出现过一次，
 * 因此行号收敛成常量，以后插行只改这里。
 *
 * 注意「弹幕速度 / 弹幕大小 / 弹幕透明度」三行在**隐藏弹幕**时不存在（整行不添加），
 * 所以「定时」的行号不是固定的：用 [sleepRow] 现算，不要再写死 7。
 */
/**
 * 「回到顶部」浮层的门槛：沿着列表第一列往下数，**滚过这么多条目**之后才出现（见
 * [com.hgtv.star.list.itemsScrolledPast]）。按条目而不是像素或行数：六列栅格一行 6 个
 * （50 大约是九行、一屏半），换成别的列数 / 海报尺寸都不用改这里。
 */
internal const val BACK_TO_TOP_ITEMS=50

internal const val MENU_ROW_QUALITY=0
internal const val MENU_ROW_SPEED=1
internal const val MENU_ROW_FRAME=2
internal const val MENU_ROW_DECODER=3
internal const val MENU_ROW_DANMAKU=4
internal const val MENU_ROW_DANMAKU_SPEED=5
internal const val MENU_ROW_SCALE=6
internal const val MENU_ROW_ALPHA=7
/** 「画面缩放」那一行：紧跟「弹幕透明度」，只缩画面，弹幕层保持整屏坐标。 */
internal const val MENU_ROW_VIDEO_SCALE=8

/**
 * 「画面缩放」那一行的下标。
 *
 * 它紧跟在「弹幕透明度」之后；隐藏弹幕时那三行不添加，它就直接跟在「弹幕」后面。
 */
internal fun videoScaleRow(showsStyle: Boolean)=if(showsStyle) MENU_ROW_VIDEO_SCALE else MENU_ROW_DANMAKU+1

/**
 * 「定时」那一行的下标：紧跟在「画面缩放」之后。
 *
 * 判定与设置页同一条规则（[DanmakuMode.showsStyle]），只是这里要显式传进来——
 * 它是 Activity 的字段。
 */
internal fun sleepRow(showsStyle: Boolean)=videoScaleRow(showsStyle)+1

/**
 * 列表滚动位置最多记住多少页。
 * key 里带着搜索词与页码，用户每搜一个新词就是一个新 key，不设上限就是一条只涨不跌的内存曲线。
 */
internal const val SCROLL_MEMORY=24

/**
 * 首页「再按一次退出」的确认窗口。
 *
 * 第一次按返回只弹一条提示并记下时刻，窗口内再按一次才真的退出；超时之后再按就重新算第一次
 * （提示也再弹一遍）。取 2 秒是为了和 [TvTools.toast] 的显示时长（1.9 秒）对齐：
 * 提示还在屏幕上时按第二次一定算数，用户不必去猜「几秒内有效」。
 */
internal const val EXIT_CONFIRM_WINDOW_MS=2000L

/** 首页按第一次返回时的提示（走 [TvTools.toast]，不吃焦点、不吃按键）。 */
internal const val EXIT_TOAST="再按一次返回键退出"
