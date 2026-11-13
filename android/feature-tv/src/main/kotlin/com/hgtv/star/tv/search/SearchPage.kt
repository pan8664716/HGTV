// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv
import com.hgtv.star.shared.debug.DebugLog

import android.app.Activity
import android.graphics.Typeface
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import android.text.TextUtils
import com.hgtv.star.core.*
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.shared.ui.SoftStyle
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.designPx
import com.hgtv.star.tv.designTextSize
import com.hgtv.star.tv.list.gridCards
import com.hgtv.star.tv.list.showCatalog
import com.hgtv.star.shared.compat.attachedToWindow
import com.hgtv.star.tv.R

/**
 * 确认键必须继续交给 [EditText] 的原生 `onKeyUp`，部分电视输入法靠它启动。
 *
 * Android 5 的少数固件会在原生 `onKeyUp` 里因空 `InputMethodInfo` 抛 NPE；
 * 这里只兜住这一种已知异常，并改用显式 IME 请求，不能把所有异常都吞掉。
 */
private class TvSearchEditText(
    context: Context,
    private val imeFallback: () -> Unit,
) : EditText(context) {
    override fun onKeyUp(keyCode: Int,event: KeyEvent): Boolean {
        return try {
            super.onKeyUp(keyCode,event)
        } catch(problem: NullPointerException) {
            if(keyCode==KeyEvent.KEYCODE_ENTER ||
                keyCode==KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER) {
                imeFallback()
                true
            } else throw problem
        }
    }
}

// 本文件是从 TvMainActivity.kt 拆出来的「搜索页：输入框、热词与联想提示、搜索历史」这一块。
// 这些成员仍然属于 TvMainActivity，只是以扩展成员（TvMainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

fun TvMainActivity.searchBar(top: LinearLayout,requestFocus: Boolean) {
    // HG键盘模式下这一行照建（顶栏与系统键盘模式完全一致），但输入框改成**只显示的镜子**：
    // 字符由左侧键盘写进来（见 syncHongguoInput），它自己不可聚焦、也不弹系统输入法。
    // 不这么做的话，同一页会有两个输入入口，焦点在两者之间来回跳、系统输入法还会盖住键盘。
    // 判据与 [showCatalog] 里那个 `hongguo` 完全一致：本机集合页（收藏 / 历史）虽然也在
    // 搜索这个标签下，但它不是搜索页，输入框要照常可用。
    val hongguo=hongguoKeyboardMode() && collection==null
    // 搜索框与同一行的按钮共用一套尺寸令牌：48dp 底板、胶囊圆角、左右 28dp 内边距，
    // 因此输入框和「搜索 / 历史」在一条基线上；差别只在「凹槽 vs 凸起」这一层语义。
    val searchBand=dp(TvStyle.SOFT_BAND)
    val input=TvSearchEditText(this) { focusSearchInput(120L) }.apply {
        // 提示里把「怎么打字」写清楚：电视上没有手机输入按钮之后，用户靠系统输入法
        // （遥控器方向键选字）或外接键盘打字，这一行是他唯一能看到说明的地方。
        //
        // 提示文字**比正文字号小一档**（[SEARCH_HINT_TEXT]）：正文是 20sp 的粗体，
        // 提示跟着那么大的话一句话就被截断，只能显示半句；小一档之后整句都放得下。
        // `hint` 只接受 CharSequence，字号靠 AbsoluteSizeSpan 挂在片段上。
        id=View.generateViewId()
        hint=android.text.SpannableString(SEARCH_HINT).apply {
            setSpan(
                // 提示字号同样走设计缩放：写死成 sp 的话，换分辨率就与正文脱节。
                android.text.style.AbsoluteSizeSpan(designPx(SEARCH_HINT_TEXT),false),
                0,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        setText(if(hongguo) hongguoDraft else query)
        designTextSize(SEARCH_INPUT_TEXT); setTypeface(typeface,Typeface.BOLD)
        setTextColor(TvStyle.text); setHintTextColor(TvStyle.muted); isSingleLine=true; maxLines=1
        ellipsize=TextUtils.TruncateAt.END
        filters=arrayOf(android.text.InputFilter.LengthFilter(80))
        imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        includeFontPadding=false
        isFocusableInTouchMode=true
        if(android.os.Build.VERSION.SDK_INT>=21) showSoftInputOnFocus=true
        if(hongguo) {
            // HG键盘：这一格是「当前输入」的显示框，**可聚焦但不接受系统输入**。
            // 键盘第一排按上要能回到它（方向上最近的一格就是它），所以它必须可聚焦；
            // 但它自己不许弹系统输入法：`showSoftInputOnFocus=false` 关掉输入法，光标也不画。
            // （这里**不能**清 `keyListener`：那会让 TextView 把自己变成不可聚焦，
            //   上键就再也回不到这一格了。）
            isFocusable=true
            isFocusableInTouchMode=true
            if(android.os.Build.VERSION.SDK_INT>=21) showSoftInputOnFocus=false
            isCursorVisible=false
        }
        // 输入框是一条「压进底板」的凹槽：内边距里含柔光带，内阴影才有地方画。
        // 左右内边距跟紧凑档按钮走（[TvStyle.BUTTON_PAD_COMPACT]），与同一行的按钮对齐。
        setPadding(dp(TvStyle.BUTTON_PAD_COMPACT)+searchBand,searchBand,dp(TvStyle.BUTTON_PAD_COMPACT)+searchBand,searchBand)
        background=softPanel(TvStyle.well,TvStyle.RADIUS_PILL,style=SoftStyle.INSET,ring=TvStyle.outline,ringWidth=1)
        setOnFocusChangeListener { view,_ ->
            val focused=view.hasFocus()
            // 凹陷控件的焦点没法靠「打亮」表达，只能把槽压得更深、让焦点色细环加深。
            // 环色取当前主题的 --focus，与按钮 / 海报卡的焦点环保持同一套语言。
            view.background=softPanel(
                TvStyle.well,TvStyle.RADIUS_PILL,style=SoftStyle.INSET,
                ring=if(focused) TvStyle.focusLine else TvStyle.outline,
                ringWidth=if(focused) TvStyle.RING else 1,
                depth=if(focused) 1.6f else 1.1f)
            if(!focused) cancelSearchInputMethod()
        }
        setOnClickListener {
            // HG键盘下这一格就是「搜索」按钮：功能行里已经没有搜索键了，
            // 按确定 / 点它就按当前输入去搜（空输入仍然打开搜索历史）。
            if(hongguo) {
                // 外接硬件键盘有可能直接改过这一行（与草稿不一致），先同步再搜。
                val typed=text.toString()
                if(typed!=hongguoDraft) setHongguoDraft(typed)
                searchHongguoDraft()
            } else focusSearchInput()
        }
    }
    top.addView(input,lp(dp(SEARCH_INPUT_WIDTH),dp(SEARCH_INPUT_HEIGHT+2*TvStyle.SOFT_BAND)).apply {
        // 初始宽度只是「还没量过之前」的占位：这一行建好之后由 [fitSearchRow] 按实测
        // 可用宽度重新分配（上限 [SEARCH_INPUT_WIDTH]、下限 [SEARCH_INPUT_MIN_WIDTH]）。
        //
        // 为什么不能写死一个宽度：这一行除了输入框与两枚按钮，还要摆七个圆形入口（含「推荐」）。
        // 在 1080p / 280dpi 这类屏上整行只有约 1083dp，固定 300dp 的输入框必然把右边那排
        // 圆形入口顶出屏幕——用户看到的就是「搜索一展开，圆形按钮被挤没了」。
        // 间距也从 GAP_GROUP 收到 GAP_BLOCK：这一组控件是**一整块**，内部不必留那么大的空。
        //
        // HG键盘下输入框要贴到屏幕左缘（与键盘第一列对齐）：负外边距正好抵消页面缩进。
        leftMargin=if(hongguo) -pageInset() else dp(TvStyle.GAP_BLOCK)
        rightMargin=dp(TvStyle.GAP_TIGHT)
    })
    // 切换键盘的圆形入口**只在系统输入法模式下建**，位置在「手机输入」后面（见下面那一段）：
    // HG键盘自己功能行里就有一枚「切换键盘」图标，顶上再放一枚是重复的；
    // 而系统输入法模式下没有键盘栏，需要这一枚才能切回HG键盘。
    // 输入框为空时按「搜索」= 打开搜索历史（原来这里只是把焦点丢回输入框）。
    // 历史按钮因此可以从导航栏里去掉，把宽度让给右边那排圆形入口。
    // 两条路都要先收键盘：空内容会弹「搜索历史」，而那是**模态框**——输入法浮在上面时
    // 它既盖住弹窗，又继续吃遥控器 / 外接键盘的按键，用户按确定 / 取消都没反应。
    // 早先只有 [runSearch] 收了键盘，于是「空内容回车 → 历史弹窗 → 取消按不动」。
    fun search() {
        val next=input.text.toString().trim()
        if(next.isBlank()) {
            cancelSearchInputMethod()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken,0)
            showSearchHistory(input)
            return
        }
        runSearch(next)
    }
    // EditText 自己会把上下键吃掉做光标移动，焦点因此困在输入框里出不去。
    // 这两下在这里显式交回页面：上=顶部圆形入口，下=提示词 / 热搜词那一行（没有时退到内容第一张卡）。
    // 确认键**完全交回 TextView**，与 1.3.5.1 一致。部分电视输入法只认原生
    // ACTION_DOWN / ACTION_UP；应用在 UP 返回 true 会截断这条启动链路。
    // Android 5 的已知 NPE 由 [TvSearchEditText.onKeyUp] 定点兜住。
    input.setOnKeyListener { view,keyCode,event ->
        if(event.action!=KeyEvent.ACTION_DOWN ||
            (keyCode!=KeyEvent.KEYCODE_DPAD_UP && keyCode!=KeyEvent.KEYCODE_DPAD_DOWN)) false
        else {
            // 判据必须与「能不能真的拿到焦点」一致：只看 `isShown` 会把页头收起后
            // 高度为 0 的那一行也算进来，`requestFocus` 失败后焦点原地不动。
            //
            // 按下键**先看提示词那一行**，而不是只看 `nextFocusDownId`：
            // 提示词是异步铺出来的，`renderHintRow` 里接线的那一刻芯片刚 addView、
            // 还没量过尺寸，`canTakeFocusNow` 的尺寸判据会把它筛掉，于是那条出口
            // 落到了内容第一张卡上（用户按向下就跳过联想词，直接进结果）。
            // 按键这一刻布局早已完成，按当前状态现找即可。
            val target=when {
                keyCode==KeyEvent.KEYCODE_DPAD_DOWN -> suggestButtons.firstOrNull(::canTakeFocusSoon)
                    ?: root.findViewById<View>(view.nextFocusDownId)?.takeIf(::canTakeFocusNow)
                    ?: gridCards().firstOrNull(::canTakeFocusNow) ?: navEntry()?.takeIf(::canTakeFocusNow)
                else -> root.findViewById<View>(view.nextFocusUpId)?.takeIf(::canTakeFocusNow)
                    ?: navEntry()?.takeIf(::canTakeFocusNow)
            }
            if(target==null) false
            else {
                cancelSearchInputMethod()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken,0)
                view.clearFocus(); target.requestFocus(); true
            }
        }
    }
    searchInput=input
    // HG键盘模式下这两枚**不建**：键盘最上面那一排已经有「搜索」，手机输入也没有存在意义
    // （字符在屏幕上的键盘里敲）。顶栏因此只剩「切换键盘 + 输入框 + 圆形入口」，
    // 输入框能往左挪的位置更多。
    if(!hongguo) {
        // 按钮用**收紧档**（[ButtonSize.TIGHT]：40dp 底板 / 15sp 字号 / 12dp 内边距）：
        // 高度与同一行的输入框对齐，横向比紧凑档再省 12dp（「搜索」90dp、「手机输入」120dp）。
        // 宽度按内容收紧（见 `paintButton` 里 TIGHT 的分支），不会再被常规档的 92dp 撑开。
        // 间距用 [SEARCH_BUTTON_GAP]（6dp）：这几枚与输入框是**一组**，彼此靠近。
        // 与回车是同一个动作：空内容按它就是搜索历史（见 [search]）。
        searchButton=addButton(top,"搜索",size=ButtonSize.TIGHT,gap=SEARCH_BUTTON_GAP,icon=R.drawable.ic_toolbar_search) { search() }
        // 「手机输入」：遥控器上敲中文要按几十下方向键，这里把打字整个搬到手机上。
        // 手机扫码 → 竖屏页面里打完 → 按发送，电视立刻按这个词搜索（见 [TvTools.phoneInput]）。
        // 与「搜索」同一档（TIGHT）并紧挨着：这两枚是同一组动作，右边那排圆形入口另属一组。
        val phone=addButton(top,"手机输入",size=ButtonSize.TIGHT,gap=SEARCH_BUTTON_GAP,icon=R.drawable.ic_action_phone) {
            cancelSearchInputMethod()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
            // 收到就先把这句话写进页面提示行，再走搜索：手机那边点了「发送到电视」，
            // 电视上必须**看得见**它收到了什么——否则「手机说已发送、电视没反应」时
            // 分不清是哪一端的问题。
            tvTools.phoneInput(input) { word -> showHint("已从手机收到「$word」"); runSearch(word) }
        }
        searchActions+=phone
        // 切换键盘的圆形入口排在「手机输入」后面：这三枚是同一组动作（都是「怎么把字弄进来」），
        // 紧挨着读起来才是一组；放在输入框和「搜索」之间会把输入与它的动作拆开。
        if(collection==null) {
            val switch=keyboardSwitchButton()
            hongguoSwitch=switch
            gapInto(top,switch,lp(-2,-2),TvStyle.GAP_BLOCK)
        }
    }
    // 语音入口已去掉：搜索页只留「输入框 + 搜索」两件。
    // 电视上用遥控器说话的场景本来就少，识别界面还会把整页切走再切回来；
    // 这一行留给输入框与搜索按钮之后，宽度宽裕得多，焦点链也少一跳。
    // 「历史」按钮去掉了：这一行原来挤着「输入框 + 搜索 + 历史」三件，
    // 把右边那排圆形入口挤得只剩一点宽度。搜索历史仍然可用——输入框为空时按「搜索」
    // 就会打开历史（见 [search] 与 [showSearchHistory]）。
    // 这一组（输入框 + 搜索）与右边那排圆形入口之间留一段弹性空白：
    // 前者靠左，后者仍然贴在右边，中间因此有一段看得出来的距离。
    top.addView(Space(this),lp(0,1).apply { weight=1f })
    // 只在「抬起 / 输入法动作」时执行：`doKeyDown` 与 `onKeyUp` 会各问一次同一个按键，
    // 不做这个区分的话一次回车会跑两遍 `search()`（两次请求、页面重建两回）。
    input.setOnEditorActionListener { _,_,event ->
        if(event?.action==KeyEvent.ACTION_DOWN) true
        else {
            if(input.text.toString().trim().isEmpty()) focusSearchInput(120L) else search()
            true
        }
    }
    // 打字就取提示词（去抖）；页面重建后监听器跟着输入框一起换新的。
    // HG键盘模式下不挂：那个框的字符是键盘写进来的，联想词由 `updateHongguoSuggestions`
    // 直接驱动中间那一列，再挂一条监听会变成同一次输入发两遍请求。
    if(!hongguo) input.addTextChangedListener(object: android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
        override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { scheduleSuggestions(s?.toString().orEmpty()) }
    })
    // 进搜索页、还没有关键词时焦点直接落在输入框上：这一页要「等用户输入」，
    // 焦点停在顶部圆形入口上等于让用户先按一下右键才能打字。
    // 有关键词（刚搜完切回这一页）时不抢焦点：那时焦点应该在结果或它原来的位置上。
    //
    // 这里**不能**顺手设 `nextFocusUpId`：这一排圆形入口是在 [searchBar] 之后才建好的，
    // 此刻 `navEntry()` 还是 null（设了就等于把「按上」指向自己）。上键的落点统一在
    // 建完导航栏之后由 [wireHeadNavUp] 接上。
    //
    // 语音入口去掉之后，这个「抑制一次焦点」的标志只剩一种来源（识别界面返回），
    // 而那条路已经不存在了；这里仍保留消费动作，免得它留到下一次无关的重建上。
    suppressSearchFocusOnce=false
    // 系统输入法模式下进页面就补一次 IME 请求：部分电视固件不会因焦点落地自动弹出。
    // 点击输入框 / 按确认 / 按搜索键仍走 [focusSearchInput] 的重试链路。
    // HG键盘模式下输入框不可聚焦，焦点直接交给左侧键盘的第一格。
    if(requestFocus) input.post {
        if(hongguo) hongguoKeyboard?.focusFirstKey()
        else if(input.attachedToWindow) {
            input.requestFocus()
            focusSearchInput(120L)
        }
    }
}

/**
 * 把焦点交给搜索框，并显式请求系统输入法。
 *
 * 只调 `requestFocus()` 在部分电视固件上不会触发 `showSoftInput`：输入框有光标，
 * 但屏幕上没有任何键盘。这里在焦点落地后再请求一次，并延迟重试，覆盖页面刚重建、
 * 窗口 token 尚未稳定的那一帧。
 */
fun TvMainActivity.focusSearchInput(delayMs: Long=0L) {
    // HG键盘模式下「聚焦输入框」= 把焦点交给键盘：那个框本身不可聚焦。
    if(hongguoKeyboardMode()) { hongguoKeyboard?.focusFirstKey(); return }
    val input=searchInput as? EditText ?: return
    val token=++searchImeToken
    val request=Runnable { requestSearchInputMethod(input,3,token) }
    if(delayMs>0L) input.postDelayed(request,delayMs) else input.post(request)
}

/** 作废所有尚未执行的输入法请求 / 重试。 */
fun TvMainActivity.cancelSearchInputMethod() {
    searchImeToken++
}

/**
 * 等输入框真正拿到窗口焦点后再请求 IME。
 *
 * 设备日志里的 `Ignoring startInput with null EditorInfo` 表示请求发得太早：
 * 页面已经有输入框，但 Android 还没把它登记为当前 Editor。最多重试三次，
 * 每次 120ms，最后仍不成功时再用强制标志兜底。
 */
private fun TvMainActivity.requestSearchInputMethod(input: EditText,remaining: Int,token: Int) {
    if(token!=searchImeToken || isDestroyed || !input.attachedToWindow) return
    val focused=input.requestFocus() || input.isFocused
    if(!focused) {
        if(remaining>0) input.postDelayed({ requestSearchInputMethod(input,remaining-1,token) },120L)
        return
    }
    input.setSelection(input.text.length)
    val imm=getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val flags=if(remaining==0) InputMethodManager.SHOW_FORCED else InputMethodManager.SHOW_IMPLICIT
    val shown=imm.showSoftInput(input,flags)
    if(!shown && remaining>0) {
        input.postDelayed({ requestSearchInputMethod(input,remaining-1,token) },120L)
    }
}
/**
 * 按**实测宽度**给搜索页导航栏那一行重新分配宽度：输入框吃「兄弟们剩下多少」。
 *
 * ## 为什么必须实测
 *
 * 这一行从左到右是：页面标题、输入框、「搜索」、「手机输入」、弹性空白、六枚圆形入口
 * （五枚标签入口 + 「推荐」）。输入框写死 300dp 时，整行总宽在 1080p / 280dpi 的屏上会超出可用宽度
 * 一百多 dp——`LinearLayout` 不会压缩子控件，超出部分直接被父容器裁掉，
 * 用户看到的就是「搜索页一展开，右边那排圆形按钮被挤掉了」。
 *
 * 反过来把输入框写死成一个很小的值，4K 电视上又白白浪费一半宽度。
 * 因此这里在**每次布局**时算一遍：把所有兄弟控件的实测宽度与间距加起来，
 * 从可用宽度里减掉，剩下的就是输入框的；再夹在
 * [SEARCH_INPUT_MIN_WIDTH] 与 [SEARCH_INPUT_WIDTH] 之间。
 *
 * ## 几个约束
 *
 * * **圆形入口一寸不让**：它们与其余兄弟控件一样按实测宽度先占位，输入框只拿剩下的。
 * * **不会来回抖**：只在算出的宽度与当前不同的时候改一次 `layoutParams`；
 *   输入框自己的宽度不参与求和，改完再布局一次结果就稳定了。
 * * **只在搜索页调用**：其余页面这一行没有输入框（只有一段弹性空白）。
 *
 * [top] 是导航栏那一行。
 */
fun TvMainActivity.fitSearchRow(top: LinearLayout) {
    top.addOnLayoutChangeListener { view,left,_,right,_,_,_,_,_ ->
        val input=searchInput as? EditText ?: return@addOnLayoutChangeListener
        val available=(right-left)-view.paddingLeft-view.paddingRight
        if(available<=0) return@addOnLayoutChangeListener
        val params=input.layoutParams as? LinearLayout.LayoutParams ?: return@addOnLayoutChangeListener
        // HG键盘：搜索框**与键盘区域同宽、左右对齐**（都从屏幕左缘起，宽 2/7）。
        //
        // 三栏那一行比本行多出一个内容缩进（负外边距，见 `showCatalog`），因此键盘栏宽要按
        // 「本行宽度 + 缩进」算；目标右缘换算回本行坐标要再减掉这个缩进。
        if(hongguoKeyboardMode() && collection==null) {
            val inset=pageInset()-pageBleed()
            val rowWidth=view.width+inset
            val targetRight=(rowWidth*2f/7f).toInt()-inset
            val width=(targetRight-input.left).coerceAtLeast(dp(SEARCH_INPUT_MIN_WIDTH))
            if(params.width!=width) {
                params.width=width
                input.layoutParams=params
            }
            return@addOnLayoutChangeListener
        }
        var used=0
        for(index in 0 until top.childCount) {
            val child=top.getChildAt(index)
            if(child===input) continue
            val params=child.layoutParams as? LinearLayout.LayoutParams ?: continue
            // 弹性空白不占宽：它本来就是「剩下的那一段」，算进来会把输入框挤到 0。
            if(params.weight>0f) continue
            used+=child.measuredWidth+params.leftMargin+params.rightMargin
        }
        val inputParams=input.layoutParams as? LinearLayout.LayoutParams ?: return@addOnLayoutChangeListener
        val room=available-used-inputParams.leftMargin-inputParams.rightMargin
        val width=when {
            room>=dp(SEARCH_INPUT_WIDTH) -> dp(SEARCH_INPUT_WIDTH)
            room>=dp(SEARCH_INPUT_MIN_WIDTH) -> room
            // 连下限都放不下（极窄屏 / 系统字号放大）：宁可输入框再窄一点，
            // 也不许把右边那排圆形入口顶出屏幕——它们才是这一行的固定成员。
            else -> room.coerceAtLeast(0)
        }
        if(inputParams.width!=width) {
            inputParams.width=width
            input.layoutParams=inputParams
        }
    }
}

/**
 * 搜索页导航栏里的控件「按上键」都回到那一排圆形入口。
 *
 * 必须在**圆形入口建好之后**调：导航栏是先建标题与搜索控件、最后才加四个圆形入口的，
 * 建搜索控件时 `navEntry()` 还是 null，那时设上键会把焦点指向自己。
 */
fun TvMainActivity.wireHeadNavUp() {
    val up=navEntry()?.id ?: return
    searchInput?.nextFocusUpId=up
    searchButton?.nextFocusUpId=up
    searchActions.forEach { it.nextFocusUpId=up }
}
/**
 * 输入变化 → 去抖取提示词。
 *
 * 去抖 220ms：每敲一个字都发请求会把接口打满，也会让那一行一直闪。
 * 清空输入时立刻清掉那一行（不发请求）。
 */
fun TvMainActivity.scheduleSuggestions(text: String) {
    suggestRun.getAndSet(null)?.let { main.removeCallbacks(it) }
    val clean=text.trim()
    // 清空输入：换回热搜词（而不是留一行空白）。
    if(clean.isEmpty()) { suggestToken.incrementAndGet(); loadHotSearches(); return }
    searchController.scheduleSuggestions(clean,SUGGEST_COUNT,SUGGEST_DEBOUNCE)
}
/**
 * 焦点进入搜索结果**第二行**时，把输入框里的词清空。
 *
 * 第一行是「刚搜完、先扫一眼」的那一屏：输入框里留着自己搜的词，用户能确认
 * 「我搜的是这个」。往下走到第二行，说明他已经在看内容、准备换词了，
 * 这时清空才不打断阅读，按上键回到输入框也直接是空的。
 *
 * 按**行号**判断而不是数卡片：行号来自栅格自己（[TvMainActivity.GridRow.index]），
 * 不受列数、来回上下移动影响，也不会因为重复经过同一张卡而数错。
 *
 * 为什么只清控件、不清 [query]：这一份结果、页码、预取与「展示更多」全都按 [query]
 * 取下一页（见 `requestNextPage` / `catalogPageKey`）。清掉它，已经显示的结果就成了一份
 * 「没有关键词的列表」，按展示更多会去搜一个空词。
 *
 * 清空之后 TextWatcher 会把输入框下面那一行换回搜索记录（[loadHotSearches]）——
 * 那正是空输入时该显示的内容，也方便用户直接点回上一个词。
 */
fun TvMainActivity.noteSearchRowFocused(row: Int) {
    if(isDestroyed || screen!="catalog" || tab!=TAB_SEARCH) return
    if(row<1) return
    clearSearchBoxOnResults()
}
/**
 * 把输入框里的词清空（只清控件，其余状态一概不动）。
 *
 * 清掉之后用户按上键回到输入框就是空的，直接敲新词，不用一路删干净。
 * 输入框本来为空时什么都不做：焦点在卡片之间移动不会反复触发。
 */
fun TvMainActivity.clearSearchBoxOnResults() {
    if(isDestroyed || screen!="catalog" || tab!=TAB_SEARCH) return
    // HG键盘：焦点离开键盘进到结果第二行，就把草稿清掉——
    // 中间那一列因此从「联想词」回到「搜索记录」，正是用户「不打了、开始看结果」时该看到的内容。
    if(hongguoKeyboardMode()) {
        if(hongguoDraft.isNotEmpty()) setHongguoDraft("")
        return
    }
    val input=searchInput as? EditText ?: return
    if(input.text.isNullOrEmpty()) return
    input.setText("")
}
/** 两套键盘共用的焦点入口：HG键盘聚焦首字母，系统键盘仍走原来的输入框。 */
fun TvMainActivity.focusHongguoKeyboard() {
    if(!hongguoKeyboardMode()) { focusSearchInput(); return }
    // HG键盘模式下系统输入法一律按下去：焦点交给自绘键盘，系统键盘不许冒出来。
    hideSystemKeyboard()
    hongguoKeyboard?.focusFirstKey()
}
/**
 * 搜索页空输入时，输入框下面那一行铺**本机搜索记录**。
 *
 * 与提示词共用 [suggestRow] 那一行与同一套令牌：搜索记录是「没有输入」时的默认内容，
 * 一旦开始打字就换成提示词。纯本机数据，不走网络；没有记录时整行隐藏。
 *
 * 记录条数本来就限到 20 条（见 `SearchHistory.remember`），一行放不下时横向滚动，
 * 因此这里直接整份铺出来，不再截断。
 */
fun TvMainActivity.loadHotSearches() {
    suggestJob?.cancel(true)
    // 作废在途的联想请求：清空输入那一刻回来的旧结果不能盖住搜索记录。
    suggestToken.incrementAndGet()
    if(isDestroyed || screen!="catalog" || tab!=TAB_SEARCH) return
    searchController.refreshHistory()
    searchController.loadHot()
    val state=searchController.state.value
    renderHintRow(state.emptyPrompts())
}
/**
 * 取一次提示词。
 *
 * 请求走自己的线程与令牌（不占用 [work] 那条「一次导航一个请求」的通道）：
 * 它是**边打字边发**的，绝不能把页面重排、更不能取消用户正在等的搜索。
 * 结果回来时先比对令牌与当前输入框内容——晚回来的旧结果直接丢掉。
 */
fun TvMainActivity.fetchSuggestions(keyword: String) {
    searchController.scheduleSuggestions(keyword,SUGGEST_COUNT,0L)
}
/**
 * 重建输入框下面那一行（热搜词 / 提示词共用）。
 *
 * **只动这一行**：换关键词、热搜换成提示词都不重画整页，因此输入时页面不会闪。
 * 每一枚芯片都是**一个搜索词**：点了就把这个词填进输入框并搜（见 [runSearch]）——
 * 即使服务端在这一条上给了剧集信息（`short_play_name`），也不直接跳简介页：
 * 那一行是「搜索建议」，用户点它是想搜这个词，不是想跳过搜索结果。
 * 空列表时整行隐藏，不留空槽。
 */
/**
 * 导航栏里那几件（输入框 / 搜索 / 手机输入）的「按下」出口。
 *
 * **必须永远指向一个真的能接收焦点的控件。** 框架的 `FocusFinder` 拿到显式
 * `nextFocusDownId` 时会**直接返回那个视图**，不做任何可聚焦性检查；输入框在
 * `TextView.onKeyUp` 里对回车/确认的处理是「拿不到焦点就抛 IllegalStateException」
 * （`focus search returned a view that wasn't able to take focus!`）。
 * 也就是说，只要这条出口指向了已经 `GONE` 的提示词芯片，用户在搜索框按确认就崩溃。
 *
 * 因此这里逐级兜底，并且**每个候选都要现查 `isShown && isFocusable && isEnabled`**：
 * 内容第一张卡 → 搜索按钮 → 顶部圆形入口 → 输入框自己（指自己一定成功，最差就是按下不动）。
 * 指向自己不是偷懒：它保证「按下去没反应」而不是「按下去崩掉」。
 */
/**
 * 「不考虑尺寸」的可聚焦判断：可见、可用、可聚焦，且页头没收起。
 *
 * 提示词那一行专用。它是**异步铺出来的**：`renderHintRow` 里 `addView` 之后
 * 这一帧还没量过尺寸，用 [canTakeFocusNow] 的尺寸判据去筛会把它一次性筛掉，
 * 于是「按下」出口落到内容第一张卡上——用户按向下就跳过联想词直接进结果。
 *
 * 而「页头收起导致这一行 0 高」这种真正拿不到焦点的情况，由 [topAreaHidden]
 * 兜住（收起时该标志立刻置位），不需要再靠尺寸判断。
 */
private fun TvMainActivity.canTakeFocusSoon(view: View?): Boolean {
    val target=view ?: return false
    if(!target.isFocusable || !target.isEnabled) return false
    var node: View?=target
    while(node!=null) {
        if(node.visibility!=View.VISIBLE) return false
        if(node===suggestRow && topAreaHidden) return false
        if(node===root) break
        node=node.parent as? View
    }
    return true
}
/**
 * 这个控件**此刻**能不能真的拿到焦点。
 *
 * 比 `isShown` / `isFocusable` 更严的一条是尺寸：框架的 `View.canTakeFocus()`
 * 要求 `hasSize()`（宽高都 > 0），**尺寸为 0 的控件 `requestFocus` 必然返回 false**。
 * 而页头收起时（[applyTopAreaState] 把行高写成 0）提示词那一行正好就是这个状态——
 * 行还在、`visibility` 还是 VISIBLE、芯片自己也还是 focusable，但它量出来是 0 高。
 *
 * 尺寸判断只对**已经布局过**的控件做：页面是在挂上窗口之前建好的，那时尺寸都还是 0，
 * 用尺寸去卡会把所有候选一次性筛光（与 [navIcons] 不用 `isShown` 是同一个道理）。
 * 祖先也要一并查：提示词芯片自己的高度跟着父行一起被压成 0，只看它自己就漏了。
 */
private fun TvMainActivity.canTakeFocusNow(view: View?): Boolean {
    val target=view ?: return false
    if(!target.isFocusable || !target.isEnabled) return false
    // 页面还没布局过（刚建好、还没挂上窗口）时所有控件都是 0×0，这时不能拿尺寸判死。
    // 一旦根容器有了尺寸，就说明这一轮布局跑过了，0 高的控件是真的拿不到焦点。
    val laidOut=root.width>0 && root.height>0
    var node: View?=target
    while(node!=null) {
        if(node.visibility!=View.VISIBLE) return false
        if(laidOut && (node.width<=0 || node.height<=0)) return false
        if(node===suggestRow && topAreaHidden) return false
        if(node===root) break
        node=node.parent as? View
    }
    return true
}
/**
 * 导航栏里那几件（输入框 / 搜索 / 手机输入）的「按下」出口。
 *
 * **必须永远指向一个真的能接收焦点的控件。** 框架的 `FocusFinder` 在找
 * `nextFocusDownId` 时只检查「可聚焦 + 可见 + 触摸模式下可聚焦」，**不检查尺寸**；
 * 而输入框在 `TextView.onKeyUp` 里对回车/确认的处理是「拿不到焦点就抛
 * IllegalStateException」（`focus search returned a view that wasn't able to take focus!`）。
 * 也就是说：只要这条出口指向了一个 0 高的控件（页头收起时的提示词行就是），
 * 用户在搜索框按确认就崩溃。
 *
 * 因此这里逐级兜底，并且每个候选都现查 [canTakeFocusNow]：
 * 提示词芯片 → 内容第一张卡 → 搜索按钮 → 顶部圆形入口 → 输入框自己。
 * 指向自己不是偷懒：它保证「按下去没反应」而不是「按下去崩掉」。
 */
fun TvMainActivity.wireSearchHeadDown(target: View?) {
    // 提示词芯片刚 `addView` 时还没量过尺寸，用 [canTakeFocusNow] 会被尺寸判据筛掉；
    // 它们只在「页头收起」时才真的拿不到焦点，而那件事由 [topAreaHidden] 判断。
    // 因此行内目标走 [canTakeFocusSoon]，其余目标仍走严格判据。
    val inSuggestRow=target!=null && suggestRow!=null && run {
        var node: View?=target
        while(node!=null) { if(node===suggestRow) return@run true; node=node.parent as? View }
        false
    }
    val candidate=target?.takeIf { if(inSuggestRow) canTakeFocusSoon(it) else canTakeFocusNow(it) }
    val fallback=listOfNotNull(
        candidate,
        gridCards().firstOrNull(::canTakeFocusNow),
        searchButton?.takeIf(::canTakeFocusNow),
        navEntry()?.takeIf(::canTakeFocusNow),
        searchInput
    ).firstOrNull()
    val id=fallback?.id ?: View.NO_ID
    if(id==View.NO_ID) return
    searchInput?.nextFocusDownId=id
    searchButton?.nextFocusDownId=id
    searchActions.forEach { it.nextFocusDownId=id }
}
fun TvMainActivity.renderHintRow(items: List<String>) {
    val line=suggestLine ?: return
    val scroll=suggestRow ?: return
    // 显隐与高度都改**硬裁剪视口**（与分类页筛选行同一个类）：
    // 它是这一行真正的显示边界，滚动容器只是它的子控件。
    val viewport=suggestViewport
    suggestButtons.clear()
    line.removeAllViews()
    if(items.isEmpty()) {
        (viewport ?: scroll).visibility=View.GONE
        // 这一行收起来之后，导航栏里那几件按下键要落到内容第一张卡；没有内容时
        // 由 [wireSearchHeadDown] 逐级兜底——**绝不能留着指向刚被 GONE 掉的芯片**。
        wireSearchHeadDown(null)
        return
    }
    // 从 GONE 恢复：高度按固定档写回去（页头收展动画会改这个 LayoutParams，
    // 因此这里每次都重新写一遍，不能只改 visibility）。
    (viewport ?: scroll).layoutParams=((viewport ?: scroll).layoutParams as LinearLayout.LayoutParams).apply {
        height=dp(CHIP_ROW_HEIGHT)
    }
    val chips=mutableListOf<View>()
    items.forEach { label ->
        // 与分类页筛选行同一档芯片（DENSE：30dp 底板 / 13sp），
        // 一行能放下的条数更多，长关键词也不会一枚就吃掉半屏。
        val chip=ToggleChip(this,label,false,ButtonSize.DENSE) {
            // 点这一条：输入框里换成这个词，然后**搜索**它。
            (searchInput as? EditText)?.setText(label)
            runSearch(label)
        }
        chip.contentDescription="$label，搜索"
        chips+=chip
        // 芯片之间用 6dp（与分类页筛选行一致）：它们是同一组候选，靠近一点才读得出「一组」。
        gapInto(line,chip,lp(-2,-2),6)
    }
    // 先让这一行可见，再接焦点链：下面 `wireSearchHeadDown` 会按 `isShown` 过滤，
    // 行还藏着的时候接出来只会落到兜底项上（按向下不进提示词行）。
    (viewport ?: scroll).visibility=View.VISIBLE
    // 左右在行内走（走到两端停在原地），向上回到输入框（它就在这一行正上方）。
    wireChipRow(chips,{ searchInput })
    // 向下进内容第一张卡；没有结果时停在原地，不会变成「按下没反应」。
    val first=gridCards().firstOrNull()?.id
    chips.forEach { it.nextFocusDownId=first ?: it.id }
    // 导航栏里那几件按下键先落进这一行（这一行就在它们正下方）。
    wireSearchHeadDown(chips.first())
    // 这一行是异步铺出来的，铺好的那一刻栅格可能还没建（那时只能指向自己）；
    // 等这一帧布局跑完再补一次，把下键接到内容第一张卡上。
    scroll.post {
        if(suggestRow!==scroll || (viewport ?: scroll).visibility!=View.VISIBLE) return@post
        gridCards().firstOrNull()?.let { card -> chips.forEach { it.nextFocusDownId=card.id } }
        // 这一帧布局跑完、芯片有了尺寸，再按真实可聚焦状态接一次「按下」出口：
        // 上面那次接线时芯片还是 0×0，会被尺寸判据筛掉，出口因此留在内容第一张卡上。
        wireSearchHeadDown(chips.first())
    }
    suggestButtons.addAll(chips)
}
fun TvMainActivity.runSearch(value: String) {
    val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
    if(hongguoKeyboardMode()) {
        hongguoDraft=clean
        // 这一次是**用户主动搜的**：结果落地后焦点交给第一张卡（见 seriesList 的落点分支）。
        // 其余重建（追加一页、联想词刷新）不动焦点，免得把正在打字的人踢走。
        hongguoLandFirstCard=true
        searchController.remember(clean); query=clean; tab=1; page=1; catalogFocus=""; showCatalog(true)
        return
    }
    cancelSearchInputMethod()
    (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
    searchController.remember(clean); query=clean; tab=1; page=1; catalogFocus=""; showCatalog(true)
}
/**
 * 搜索源：App / 官网。
 *
 * 只影响搜索这一件事（结果列表、联想词、热搜词）。切换之后下一次搜索就用新源，
 * 已经在屏幕上的那一页结果不会被就地换掉——用户按「搜索」才换。
 *
 * 两档的差别写进每一行的说明里：App 结果全、能一直翻页，但要求这台设备注册过；
 * 官网免登录，一次 10 条按页翻。
 */
fun TvMainActivity.showSearchSourcePicker(anchor: TextView) {
    val labels=SearchSource.entries.map { source ->
        if(source==searchController.state.value.source) "${source.label} · ${source.hint}（当前）" else "${source.label} · ${source.hint}"
    }
    tvTools.choose("搜索源",labels,anchor,{ index ->
        val selected=SearchSource.entries[index]
        val changed=searchController.state.value.source!=selected
        searchController.setSource(selected)
        anchor.text="搜索源  ·  ${selected.label}"
        anchor.contentDescription="搜索源，当前${selected.label}"
        if(changed) {
            showHint("搜索源已切换为${selected.label}，下次搜索生效")
            // 搜索页缓存的那一份结果来自**旧的源**：留着它会让「展示更多」接着问新源的第 2 页，
            // 两套数据拼在一起。丢掉这一页的缓存，回到搜索页时按新源重新取第 1 页。
            tabState.remove(TAB_SEARCH); tabFocus.remove(TAB_SEARCH)
            // 搜索页正开着且输入框为空：那一行是热搜词，换源之后重新铺一份。
            if(tab==TAB_SEARCH && (searchInput as? EditText)?.text?.toString()?.trim().isNullOrEmpty()) loadHotSearches()
        }
    },subtitle="只影响搜索，分类 / 榜单 / 播放不受影响")
}
fun TvMainActivity.showSearchHistory(anchor: View) {
    searchController.refreshHistory()
    val history=searchController.state.value.history
    if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
    tvTools.choose("搜索历史",history,anchor,{ index -> runSearch(history[index]) },"清空历史") {
        tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor,danger=true) { searchController.clearHistory() }
    }
}
/** 设置 → 布局启动里的默认输入法：两档写进本机偏好，下次搜索页按它重建。 */
fun TvMainActivity.showDefaultKeyboardPicker(anchor: TextView) {
    val options=SearchKeyboard.entries
    val labels=options.map { if(it==library.searchKeyboard) "${it.label}（当前）" else it.label }
    tvTools.choose("默认输入法",labels,anchor,{ index ->
        val selected=options[index]
        setSearchKeyboard(selected==SearchKeyboard.HONGGUO)
        anchor.text="默认输入法  ·  ${selected.label}"
        anchor.contentDescription="默认输入法，当前${selected.label}"
    },subtitle="HG键盘常驻搜索页左侧；系统输入法保留原来的搜索框")
}
/**
 * 本机记录 → 云端同步（尽力而为）。
 *
 * 账号参数可用时，本机的「收藏」与「观看记录」同时推给平台：收藏增删走
 * `bookshelf/add` / `bookshelf/video/update`，观看进度走 `read_progress/upload`
 * （`write_mode=1` 会一并写进云端历史）。没配参数、或请求失败都**静默放弃**：
 * 本机记录已经落盘，云端只是补一份，不该在遥控器上弹提示打断播放。
 *
 * **先改本地那份账号数据、再发网络请求**：账号侧的列表（「我的」页四栏、首页那两栏、
 * 集合页补在后面的那一段）都读 [accountData]，只在第一次进页面时联网取过一次。
 * 不就地改它的话，在本机收藏 / 播放 / 删记录之后再回到「我的」页，读到的还是那份旧快照——
 * 明明已经同步到云端了，页面上却还是旧样子。
 */
