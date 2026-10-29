// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.view.*
import android.widget.*
import com.google.android.exoplayer2.*
import com.hgtv.star.core.*
import com.hgtv.star.MainActivity.CatalogState
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.list.showCatalog
import com.hgtv.star.compat.attachedToWindow

// 本文件是从 MainActivity.kt 拆出来的「顶部五个入口、切换页面、分类与榜单芯片、页头导航与页标题」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.switchTab(next: Int) {
    // 本次运行第一次进这一页（见 [tabVisited]）。
    val firstVisit=tabVisited.add(next)
    // 首次进入分类页：回到默认的「全部体裁」。
    // 面板也一起丢掉，首次进入会重新取一份（主题等随体裁变化）。
    if(firstVisit && next==TAB_CATEGORY) { categorySelection=CategorySelection(); categoryPanel=null }
    if(next!=TAB_RANK && tab==TAB_RANK) boardButtons.values.firstOrNull { it.hasFocus() }?.clearFocus()
    if(next!=TAB_CATEGORY && tab==TAB_CATEGORY) {
        categoryFilterButtons.values.flatMap { it.values }.firstOrNull { it.hasFocus() }?.clearFocus()
    }
    // 从本机记录集合页（收藏 / 历史 / 稍后观看）切到顶部入口：集合页挂在首页标签上，
    // 但它是独立的一层。不清理的话，目标页仍会按 [collection] 渲染——标题还是集合名、
    // 内容还是那一份本机记录，用户读到的就是「点分类 / 榜单，显示的还是当前的数据」。
    // 同时丢掉目标标签缓存的那份内容 / 页码：集合页的列表不属于任何顶部标签，
    // 目标页若要内容就重新取一份（见下面的 [needsLoad]）。
    if(collection!=null) {
        collection=null
        catalogFocus=""
        tabState.remove(next); tabFocus.remove(next)
    }
    // 从别的页面重新进搜索页：输入框清空，回到「热搜词 + 空结果」的初始状态。
    // 上一次的关键词与它那一页结果都属于上一次搜索，留着会让用户以为这次还是搜那个词；
    // 输入框的初值就取自 [query]（见 searchBar），因此这里必须连同缓存一起清掉。
    // 只处理「从别的标签进来」：搜索页内部按搜索键、从详情 / 播放返回都还在同一次搜索里。
    if(next==TAB_SEARCH && tab!=TAB_SEARCH) {
        query=""
        tabState.remove(TAB_SEARCH); tabFocus.remove(TAB_SEARCH)
    }
    // 换榜单 / 换分类的请求还在路上时，屏上那份列表属于**上一条**选择：
    // 照存会让用户切回来时看到别的分类 / 榜单的剧集。这时只存一个空页占位，
    // 切回来自然会重新取一次（见 [chipFocusKey]）。
    tabState[tab]=CatalogState(page,if(chipFocusKey!=null) emptyList() else catalog,hasMore,ranking); tabFocus[tab]=catalogFocus
    tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
    // 首页的四个剧架就在内存里（[homeFeed]），切回来不需要重新打四次网络；
    // 想要最新的一份按顶部圆形「刷新」。搜索页与榜单页仍然是「没内容才去取」。
    val needsLoad=when(next) {
        TAB_HOME -> homeFeed.isEmpty()
        TAB_RANK, TAB_CATEGORY -> catalog.isEmpty()
        TAB_SEARCH -> query.isNotBlank() && catalog.isEmpty()
        else -> false
    }
    // 落点分两种：
    // - **首次**进分类 / 榜单 / 我的页：焦点落在页头那枚芯片上——分类页是二级分类的「全部」
    //   （默认选中「漫剧 · 全部」），榜单页是当前榜单「漫剧」，我的页是第一栏「历史」。
    //   这三页都是「先挑一个再看」，焦点直接跳到内容第一张卡会让人不知道自己看的是哪一类。
    // - 其余情况（含首页、搜索页、再次进这几页）：落到内容第一张卡，用户是来看内容的。
    if(firstVisit && (next==TAB_RANK || next==TAB_CATEGORY || next==TAB_PROFILE)) {
        chipFocusKey=when(next) {
            TAB_RANK -> "board:"+rankBoard.storedValue
            TAB_CATEGORY -> categoryChipKey(CategorySelection.TYPE_GENRE,categorySelection.genre)
            else -> "account:"+accountTab
        }
        focusFirstCardOnLanding=false
    } else if(needsLoad) focusFirstCardOnLanding=true
    showCatalog(load=needsLoad)
    // 进「我的」页先交代一次「这是第三方客户端」：这一页是唯一会用到账号（登录 / 收藏 / 历史）
    // 的入口，声明放在这里最贴题。确认过就不再弹（见 [ensureThirdPartyAccepted]）。
    //
    // 用 `post` 而不是当场弹：这一次 `showCatalog` 刚把页面建好，弹窗要等它挂上窗口
    // 才有锚点；而且焦点落点也在这之后才定，抢在它前面弹会让关掉弹窗时焦点无处可去。
    if(next==TAB_PROFILE && !library.thirdPartyAccepted) {
        main.post {
            if(isDestroyed || tab!=TAB_PROFILE) return@post
            ensureThirdPartyAccepted(navEntry()) {}
        }
    }
}
/**
 * 切换排行榜页看的榜单。首页四个剧架的「查看全部」走的也是这里——
 * 它们与榜单页读的是同一份数据（[ContentRepository.rank]），只是入口不同。
 *
 * [fromHome] 为真表示这是从首页剧架进来的：顺手把顶部标签切到排行榜，
 * 让用户看到的就是榜单页本身（页面名、来源行、底部加载都是同一套）。
 */
internal fun MainActivity.switchRankBoard(board: RankBoard,fromHome: Boolean=false) {
    if(rankBoard==board && !fromHome && tab==TAB_RANK) return
    // 从首页剧架「查看全部」进来也算进过榜单页：之后点「榜单」图标不该再按「首次进入」处理。
    tabVisited.add(TAB_RANK)
    // 这一下是「点了榜单页里的芯片」还是「从首页剧架进来的」，落点完全不同：
    // 前者焦点留在那枚芯片上（用户还在挑榜单），后者落到内容第一张卡（用户是来看榜单的）。
    val fromChip=!fromHome && tab==TAB_RANK && boardButtons.values.any { it.hasFocus() }
    boardButtons.values.firstOrNull { it.hasFocus() }?.clearFocus()
    rankBoard=board
    boardButtons.forEach { (option,chip) -> (chip as? ToggleChip)?.setActive(option==board) }
    // 榜单页的内容与页码都跟着榜单走：换榜就是换一份列表。
    tabState.remove(TAB_RANK); tabFocus.remove(TAB_RANK)
    releaseCatalogViews()
    // 只重置页码与「记住的焦点卡」：列表本身留给 [showCatalog] 决定清不清——
    // 点了芯片时它要留着旧的一屏顶住这一段时间（否则页面上只剩一行「正在加载…」，看起来就是闪屏）。
    page=1; catalogFocus=""
    // 从首页剧架进来：顺手把顶部标签切到排行榜，用户看到的就是榜单页本身
    // （页面名、来源行、底部加载都是同一套）。少了这一步，[showCatalog] 会按当前标签
    // 重新渲染首页，表现出来就是「在剧架最右边按右键 / 点『查看全部』进不去榜单」。
    if(fromHome) tab=TAB_RANK
    // 换榜单就是换一份列表。落点分两种：在榜单页点了另一枚芯片 → 焦点留在那枚芯片上；
    // 从首页剧架进来（用户是来看榜单的）→ 内容落地后落到第一张卡。
    chipFocusKey=if(fromChip) "board:"+board.storedValue else null
    focusFirstCardOnLanding=!fromChip
    showCatalog(load=true)
}
/** 分类页某一行里一枚芯片的焦点 key。 */
internal fun categoryChipKey(type: String,id: String?): String = "filter:$type:${id.orEmpty()}"

/**
 * 选中 / 取消一个筛选条件，并重新取第 1 页。
 *
 * * 「体裁」必须有一个值，点同一枚不取消；换体裁判定面板里与它相关的行会变，
 *   因此把面板置空、下一次请求重新带回整份 `selector_rows`；
 * * 其余行点已选中的芯片 = 回到「全部」（[id] 传 null）；
 * * 选中的芯片在重建后由 [categorySelection] 重新算，焦点留在刚点的那一枚上。
 */
internal fun MainActivity.selectCategoryFilter(type: String,id: String?) {
    if(tab!=TAB_CATEGORY) return
    val current=categorySelection.valueOf(type)
    // 体裁不允许取消；其余行再点一次就是「全部」。
    if(current==id && type==CategorySelection.TYPE_GENRE) return
    // 其余行（含「推荐」）再点一次就是「全部」。
    val target=if(current==id) null else id
    tabVisited.add(TAB_CATEGORY)
    val oldGenre=categorySelection.genre
    categorySelection=if(type==CategorySelection.TYPE_GENRE) {
        categorySelection.newGenre(target)
    } else {
        categorySelection.with(type,target)
    }
    // 换体裁：主题 / 设定 / 背景 / 状态的行都会变，旧面板不能再用。
    if(type==CategorySelection.TYPE_GENRE && oldGenre!=categorySelection.genre) categoryPanel=null
    tabState.remove(TAB_CATEGORY); tabFocus.remove(TAB_CATEGORY)
    releaseCatalogViews()
    page=1; catalogFocus=""
    chipFocusKey=categoryChipKey(type,target)
    focusFirstCardOnLanding=false
    showCatalog(load=true)
}
/** 本机记录集合页（收藏 / 历史 / 稍后观看）；远端列表只有榜单页一种，不再有「热门集合页」。 */
internal fun MainActivity.showCollection(kind: String) {
    collection=kind; page=1; catalogFocus=""; showCatalog()
}
/** 集合页标题（本机记录页专用）。 */
internal fun MainActivity.collectionName(kind: String)=when(kind) {
    "favorites" -> "我的收藏"; "history" -> "历史记录"; "later" -> "稍后观看"; else -> "本机记录"
}
/**
 * 顶部导航栏左侧显示的页面名。
 *
 * 首页与搜索 / 设置页显示品牌「HGTV」；二级界面（收藏 / 历史 / 稍后观看）显示自己的名字，
 * 榜单页统一显示「榜单」——这些页面原来的标题行因此可以整行去掉。
 */
internal fun MainActivity.pageTitle(): String = when {
    // 「我的」页优先：它下面那四个栏目也是 collection，但页面名固定是「我的」。
    tab==TAB_PROFILE -> "我的"
    collection!=null -> collectionName(collection.orEmpty())
    tab==TAB_RANK -> "榜单"
    tab==TAB_CATEGORY -> "分类"
    // 搜索页的导航栏里还挤着输入框与三个按钮，标题只留两个字给它们让宽度。
    tab==TAB_SEARCH -> "搜索"
    tab==TAB_SETTINGS -> "设置"
    else -> "HGTV"
}
/**
 * 当前标签对应的顶部图标入口。`nav` 是五个页面入口（搜索 / 分类 / 榜单 / 设置 / 个人信息），
 * 顺序与 [TAB_SEARCH]…[TAB_SETTINGS] 一一对应。
 *
 * 首页（[TAB_HOME]）没有自己的入口：品牌「HGTV」是标识而不是按钮，不参与焦点链，
 * 这里返回 null——调用方跳过聚焦即可（回主界面走返回键）。
 */
internal fun MainActivity.navEntry(index: Int = tab): View? = navKeyed[navKeyOf(index)]

/**
 * 标签页对应的圆形入口 key（与 [NAV_ENTRIES] 一一对应）。
 *
 * 入口可以逐个隐藏，因此不能再按下标去 [MainActivity.nav] 里取——那样一旦隐藏了中间某一枚，
 * 后面所有页面拿到的都是别人的按钮。这里先映射成 key，再去 [MainActivity.navKeyed] 查。
 */
internal fun navKeyOf(tab: Int): String? = when(tab) {
    TAB_SEARCH -> "search"
    TAB_CATEGORY -> "category"
    TAB_RANK -> "rank"
    TAB_SETTINGS -> "settings"
    TAB_PROFILE -> "profile"
    else -> null
}
/**
 * 顶部这一排图标入口。
 *
 * 这里按 `visibility` 过滤而**不是** `isShown`：页面是在挂上窗口之前建好的，
 * 那时 `isShown` 一律是 false，用它过滤会得到一张空表——
 * 于是「圆形入口按下键进内容」这条焦点链根本没接上（表现出来就是「按上回不到按钮 / 按下不动」）。
 */
internal fun MainActivity.navIcons(): List<View> =
    (listOfNotNull(recommendIcon)+nav).filter { it.visibility==View.VISIBLE }

/**
 * 导航栏圆形入口关掉之后的**页面切换兜底**：遥控器「菜单」键弹一个页面列表。
 *
 * 圆入口是切换五个标签的唯一常驻入口；整排关掉之后，返回键只能回首页、搜索键只能进搜索页，
 * 分类 / 榜单 / 我的 / 设置就没有别的路了。这里补一条：菜单键在任何内容页都能直达。
 * 卡片上的菜单键（长按菜单）优先——只有焦点**不在**卡片上时才由这里接管。
 */
internal fun MainActivity.showNavPicker(anchor: View?) {
    val labels=listOf("搜索","分类","榜单","设置","我的")
    val tabs=listOf(TAB_SEARCH,TAB_CATEGORY,TAB_RANK,TAB_SETTINGS,TAB_PROFILE)
    tvTools.choose("前往",labels,anchor,{ index -> switchTab(tabs[index]) })
}
/**
 * 页头里「把焦点交回页头」时该落到哪一枚控件。
 *
 * 榜单页是四个榜单芯片、分类页是大类芯片（两页都有自己的切换行），其余页面退到顶部圆形入口。
 * 首页返回 null：品牌不是按钮，调用方改落到第一栏第一张卡。
 */
internal fun MainActivity.firstHeadControl(): View? {
    return when(tab) {
    // 榜单页是四个榜单芯片、分类页是大类芯片（两页都有自己的切换行）。
    TAB_RANK -> boardButtons[rankBoard]
    // 分类页：体裁那一行里当前选中的芯片（分类页始终有一个体裁）。
    TAB_CATEGORY -> categoryFilterButtons[CategorySelection.TYPE_GENRE]?.get(categorySelection.genre.orEmpty())
        ?: categoryFilterButtons.values.firstOrNull()?.values?.firstOrNull()
    // 搜索页的内容入口就是输入框：焦点落在它上面，而不是顶部那排圆形入口。
    TAB_SEARCH -> searchInput
    // 「我的」页：内容第一行按上键先回身份行（头像 / 退出 / 刷新），
    // 那一行在芯片与内容之间，方向上是连续的。
    TAB_PROFILE -> profileHeadControl() ?: accountButtons[accountTab] ?: accountButtons.values.firstOrNull()
    // 首页：品牌不是按钮，调用方改落到第一栏第一张卡。
    TAB_HOME -> null
    // 圆形入口整排关掉时 navEntry() 是 null：页头里没有可落的控件，
    // 调用方退回内容区第一张卡（那是这一页唯一确定的落点）。
    else -> navEntry()
    }
}
/**
 * 把 [chipFocusKey] 的 key 查回页面上**当前那一枚**芯片。
 *
 * 查不到（芯片还没建好、或换了页面）就返回 null，调用方退回原来的落点规则。
 */
internal fun MainActivity.chipControl(key: String?): View? {
    val parts=key?.split(':',limit=2)?.takeIf { it.size==2 } ?: return null
    return when(parts[0]) {
        "board" -> RankBoard.entries.firstOrNull { it.storedValue==parts[1] }?.let { boardButtons[it] }
        "filter" -> {
            val rest=key.removePrefix("filter:").split(':',limit=2)
            val type=rest.getOrNull(0).orEmpty()
            val id=rest.getOrNull(1).orEmpty()
            categoryFilterButtons[type]?.get(id)
        }
        "account" -> accountButtons[parts[1]]
        else -> null
    }
}
/**
 * 栅格第一行按上键时该落到哪一枚控件：页头里「与内容直接相邻」的那一个。
 *
 * 搜索页是输入框、分类页是当前二级分类芯片、榜单页是当前榜单芯片，
 * 其余页面退到顶部圆形入口。
 * 没有可落的目标时返回 null，调用方把按键交回框架。
 */
internal fun MainActivity.headFocusTarget(): View? {
    return when(tab) {
    // 搜索页：内容第一行按上键先进输入框下面那一行（提示词 / 热搜词），
    // 那一行收起来时才直接回输入框——方向上是连续的。
    TAB_SEARCH -> suggestButtons.firstOrNull() ?: searchInput
    // 分类页：最靠近内容的最后一行筛选（状态 / 时间），优先它的选中项。
    TAB_CATEGORY -> categoryPanel?.rows?.lastOrNull()?.let { row ->
        categoryFilterButtons[row.type]?.get(categorySelection.valueOf(row.type).orEmpty())
            ?: categoryFilterButtons[row.type]?.values?.firstOrNull()
    }
    TAB_RANK -> boardButtons[rankBoard] ?: boardButtons.values.firstOrNull()
    TAB_PROFILE -> accountButtons[accountTab] ?: accountButtons.values.firstOrNull()
    TAB_HOME -> nav.firstOrNull() ?: navIcons().firstOrNull()
    else -> navEntry()
    }
}
/**
 * 把焦点交给页头里的一个控件。
 *
 * 页头随列表滚动收起时，那一行的高度是 0、alpha 也是 0：焦点请求本身会成功，
 * 但用户什么都看不见（读到的就是「按上没反应」）。因此凡是往页头里落焦点，
 * 一律先把它展开。
 */
internal fun MainActivity.focusHead(view: View) {
    if(topAreaHidden) setTopAreaHidden(false)
    view.requestFocus()
}
/**
 * 换榜单 / 换分类之后，把焦点交回**用户刚点的那一枚**芯片。
 *
 * 页面重建会把旧的芯片整行摘掉，不显式指定的话系统会把焦点交给新布局里第一个可聚焦控件
 * （分类页就是第一枚大类芯片）——表现出来就是「选完二级分类，焦点先跑到一级分类，
 * 再跟着内容落地跳到别处」。芯片建好之后立刻调一次，焦点从头到尾都停在那一枚上。
 * [prefix] 限定这一页该认哪一类芯片（榜单页 `board:`、分类页 `group:` / `tag:`）。
 */
internal fun MainActivity.restoreChipFocus(prefix: String) {
    val key=chipFocusKey ?: return
    if(!key.startsWith(prefix)) return
    val chip=chipControl(key) ?: return
    focusHead(chip)
    // 芯片刚建出来时还没走完布局，这一下请求可能不生效（焦点会落到新布局里第一个
    // 可聚焦控件上，也就是第一枚大类芯片）。等它挂上窗口再补一次。
    // 补的条件收紧到「焦点空着，或落在另一枚芯片上」：用户在这一帧里已经移走焦点时不抢回来。
    chip.post {
        val current=root.findFocus()
        if(chip.attachedToWindow && !chip.hasFocus() && (current==null || current is ToggleChip)) focusHead(chip)
    }
}
/**
 * 分类页当前的筛选摘要（空列表文案用）：体裁 + 已选的主题 / 设定 / 背景 / 受众 / 时间 / 状态。
 * 名字全部来自接口返回的面板，客户端不再维护中文名。
 */
internal fun MainActivity.categoryDescription(): String {
    val panel=categoryPanel
    val parts=mutableListOf<String>()
    parts += panel?.labelOf(CategorySelection.TYPE_GENRE,categorySelection.genre) ?: "全部"
    listOf(
        CategorySelection.TYPE_THEME,CategorySelection.TYPE_ROLE,CategorySelection.TYPE_EPOCH,
        CategorySelection.TYPE_GENDER,CategorySelection.TYPE_ONLINE_TIME,CategorySelection.TYPE_CREATION_STATUS
    ).forEach { type ->
        panel?.labelOf(type,categorySelection.valueOf(type))?.let { parts+=it }
    }
    return parts.joinToString(" · ")
}
/**
 * 页头那一行的切换芯片（榜单页的四个榜单、分类页的三个一级分类）是否放进导航栏里。
 * 屏幕窄或字号放大时退回导航栏下方单独一行——芯片本身按文字宽度排布，
 * 挤在导航栏里会把右边的圆形入口顶出屏幕。
 */
internal fun MainActivity.inlineHeadChips()=resources.configuration.fontScale<=1.2f && widthDp()>=900
/**
 * 一行芯片的四向焦点。
 *
 * - 左右在行内按顺序走；[after] 非空时「最后一枚往右」接它（[after] 往左回最后一枚），
 *   为空时走到两端**停在原地**（不跳出行外）；
 * - 向上交给 [up]：页头最上面那一行传自己（按上键停在原地），
 *   分类页的二级分类传它所属的一级分类芯片；
 * - 向下由 `wireGridFocus` 接上内容区的第一张卡。
 *
 * 左右键在这里**显式接管**：只写 `nextFocusRightId` 时，遥控器右移会被
 * 行外的控件（导航栏那一排圆形入口）抢走，表现出来就是「右键切不动焦点」。
 *
 * [onDown] 按「下」时先问一次（返回 true = 这一下已处理，不再走 `nextFocusDownId`）。
 * 设置页拿它做「在非当前大项的芯片上按下 → 切到那一块并进它的第一行」：
 * 那一块在切换前是 `GONE` 的，`nextFocusDownId` 指过去也拿不到焦点。
 * 默认 `null`，其余页面照旧由 `nextFocusDownId` 决定。
 */
internal fun MainActivity.wireChipRow(
    chips: List<View>,
    up: (Int)->View?,
    after: View?=null,
    onDown: ((Int)->Boolean)?=null
) {
    chips.forEachIndexed { index, chip ->
        val left=chips[(index-1).coerceAtLeast(0)]
        val right=if(index==chips.lastIndex) (after ?: chip) else chips[index+1]
        val above=up(index) ?: chip
        chip.nextFocusLeftId=left.id
        chip.nextFocusRightId=right.id
        chip.nextFocusUpId=above.id
        chip.setOnKeyListener { view,key,event ->
            if(event.action!=KeyEvent.ACTION_DOWN) false
            else when(key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if(left!==view) { focusHead(left); true } else true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if(right!==view) { focusHead(right); true } else true }
                KeyEvent.KEYCODE_DPAD_UP -> { if(above!==view) { focusHead(above); true } else true }
                KeyEvent.KEYCODE_DPAD_DOWN -> onDown?.invoke(index) ?: false
                else -> false
            }
        }
    }
    // 同一视觉行里紧跟在这些芯片后面的那一枚（导航栏的圆形入口）往左回最后一枚芯片。
    if(after!=null) after.nextFocusLeftId=chips.last().id
}
/**
 * 远端长列表：排行榜页的四个榜单，以及搜索结果。
 * 这些列表都是「往下接」而不是「翻页」：已显示的剧集不动，新的一页接在末尾。
 */
internal fun MainActivity.appendCatalog()=collection==null && (tab==TAB_RANK || tab==TAB_CATEGORY || tab==TAB_SEARCH)
/**
 * 一次全量铺出来的长列表：本机记录集合页（收藏 / 历史 / 稍后观看）与「我的」页的四个栏目。
 *
 * 这些列表的数据全在内存里（本机记录，或已经拉到手的账号数据），**不分页**：
 * 一次全交给栅格，由窗口化只物化视口附近那几行。以前集合页走的是「上一页 / 下一页」，
 * 一页 20 条、每翻一次整页重建，滚动位置还得自己记——与分类页那套长列表不是一套东西。
 */
internal fun MainActivity.fullList()=collection!=null || tab==TAB_PROFILE
/**
 * 搜索结果不再给「展示更多」按钮：滚到列表底部（或焦点走到最后一行）就自动往下取，
 * 底部只留一行不可聚焦的进度文字。
 */
internal fun MainActivity.autoAppendCatalog()=tab==TAB_SEARCH && collection==null
/** 本机记录集合页（我的收藏 / 历史记录 / 稍后观看）。 */
internal fun MainActivity.localCatalog()=collection!=null
/**
 * 本机记录集合页的内容：**本机在前，账号侧补在后面**（与首页那两栏同一套合并规则）。
 *
 * 少了账号这一半，导入了账号参数却在这台设备上没看过剧时，历史页会是空的
 * （账号里的历史只在「我的」页与首页出现过），用户读到的就是「历史记录没有任何记录」。
 */
internal fun MainActivity.localItems(): List<Series> = when(collection) {
    "favorites" -> mergeAccount(library.favorites()) { accountData?.favorites?.map { it.series }.orEmpty() }
    // 账号那一半带上「第几集 + 集内秒数」（见 accountHistoryCard）：卡片上要看得见看到哪儿了。
    // 账号侧那半份要滤掉**本机删过的**（[Library.historyDeleted]）：平台的「删除观看记录」
    // 走的是阅读历史那条软删，短剧进度列表不一定真的摘掉，不滤就会「删了过一会儿又回来」。
    // 完成度筛选与「我的 → 历史」共用（见 [accountHistoryFilter]）：两处看到的是同一档。
    "history" -> mergeAccount(
        library.history().filter { p -> accountHistoryFilter?.let { want -> p.completed==want } ?: true }
            .map { it.series }
    ) {
        accountData?.history
            ?.filterNot { library.historyDeleted(it.series.id) }
            ?.filter { row -> accountHistoryFilter?.let { want -> historyFinished(row)==want } ?: true }
            ?.map(::accountHistoryCard).orEmpty()
    }
    "later" -> library.later()
    else -> library.history().map { it.series }
}
/** 本机在前、账号在后，按 id 去重（同一部剧两边都有时以本机那条为准）。 */
internal fun MainActivity.mergeAccount(local: List<Series>,account: ()->List<Series>): List<Series> {
    val seen=local.map { it.id }.toHashSet()
    return local+account().filterNot { it.id in seen }
}
