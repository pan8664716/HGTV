// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import com.hgtv.star.core.PhoneLogin
import com.hgtv.star.core.QrLogin
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.LoginForm
import com.hgtv.star.tv.SoftStyle
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.tv.usableScreenHeight
import com.hgtv.star.compat.attachedToWindow

/**
 * 「我的」页的登录界面：未登录时直接铺在身份行下方，不再有入口按钮，也不再有弹窗。
 *
 * 导入账号参数那条路已经移除。表单本身不设默认焦点：用户从顶部入口按向下进来，
 * 再自己决定先改区号还是先填号码。
 */
internal const val ACCOUNT_PANEL_PHONE="phone"
/** 扫码登录：Passport Web 出码 + 1.1 秒轮询，协议见 [com.hgtv.star.core.QrLogin]。 */
internal const val ACCOUNT_PANEL_QR="qr"

/**
 * 未登录时「我的」页顶部导航栏里的两个入口，**顺序就是默认值**：
 * 第一个（二维码）是进页面时的落点。
 */
internal val ACCOUNT_LOGIN_MODES=listOf(ACCOUNT_PANEL_QR to "二维码登录",ACCOUNT_PANEL_PHONE to "手机登录")

/** 二维码的显示边长（设计单位，与登录卡同一条 1280dp 画布）。 */
private const val QR_LOGIN_SIZE=250

/** 登录表单左侧的区号候选（默认 +86）。 */
private val dialCodes=arrayOf(
    "+86 中国大陆","+852 中国香港","+853 中国澳门","+886 中国台湾",
    "+1 美国 / 加拿大","+81 日本","+82 韩国","+65 新加坡","+60 马来西亚"
)

/**
 * 在身份行下方展开面板。**重复调用同一个模式会重建**（用于「重新获取验证码」回到表单）。
 *
 * @return 面板里第一个要操作的控件（供导航栏接线），没有时返回 null。
 */
internal fun MainActivity.showAccountPanel(
    mode: String,
    trigger: View?,
    prefillPhone: String = "",
    /** 打开后自动点一次「获取验证码」：上行短信刚通过、需要重新发码时用。 */
    autoSend: Boolean = false
): View? {
    val host=accountPanelHost ?: return null
    closeAccountPanelSession()
    val gen=++accountPanelGeneration
    accountPanelMode=mode
    accountPanelTrigger=trigger

    host.removeAllViews()
    val card=accountPanelBody()
    val scroll=CappedScroll(this).apply {
        isVerticalScrollBarEnabled=false
        clipChildren=false
        clipToPadding=false
        // 登录卡比「我的」页其他面板高：标题区 + 两行字段 + 说明 + 主按钮约 500 设计单位，
        // 因此上限按可用高度的 80% 给（0.5 会把主按钮裁在滚动区外，用户得先滚动才能看到「登录」）。
        // 分母是**扣掉系统栏**之后的高度：按整屏算的话，Android 14 上最后那枚主按钮
        // 正好落在系统栏底下，用户以为「登录按钮没出来」。
        maxHeight=(usableScreenHeight()*.8f).toInt()
        addView(card,LinearLayout.LayoutParams(-1,-2))
    }
    // 设计稿是一张 680 宽的居中登录卡；窄屏（手机横屏 / 分屏）上按「屏宽 − 两侧留白」收一次。
    val cardWidth=dp(TvStyle.LOGIN_CARD_WIDTH)
        .coerceAtMost((resources.displayMetrics.widthPixels-dp(64)).coerceAtLeast(dp(320)))
    host.gravity=Gravity.CENTER_HORIZONTAL
    host.addView(scroll,LinearLayout.LayoutParams(cardWidth,-2))
    host.visibility=View.VISIBLE

    // 面板展开后**不主动给任何控件焦点**：想开始输入再按方向键进来。
    return when(mode) {
        ACCOUNT_PANEL_QR -> buildQrLoginPanel(card,gen,trigger)
        else -> buildPhoneLoginPanel(card,gen,trigger,prefillPhone,autoSend)
    }
}

/**
 * 切换登录方式（二维码 / 手机号）：重开面板 + 同步两枚入口的选中态与焦点链。
 *
 * 三条路都走这里：顶部那两枚芯片、扫码卡里的「用手机号登录」、以及手机表单回到扫码页。
 * 不在这儿统一一遍的话，任何一条路只换面板、忘了改芯片的按下键，遥控器就会从芯片
 * 跳到一块已经不在树上的控件上——表现出来是「按向下焦点消失」。
 */
internal fun MainActivity.selectLoginMode(
    mode: String,
    trigger: View?,
    prefillPhone: String = "",
    autoSend: Boolean = false
) {
    loginMode=mode
    loginModeChips.forEach { (key,view) -> (view as? ToggleChip)?.setActive(key==mode) }
    val head=showAccountPanel(mode,trigger ?: loginModeChips[mode],prefillPhone,autoSend)
    // 两枚入口与导航栏的按下键都落到面板里第一个可操作控件：那两枚芯片与圆形入口
    // 是**同一行**（都在导航栏里），下一行就是登录卡，方向上是连续的。
    loginModeChips.values.forEach { it.nextFocusDownId=head?.id ?: it.id }
    navIcons().forEach { nav -> nav.nextFocusDownId=head?.id ?: (loginModeChips[loginMode] ?: nav).id }
}

/** 收起面板并停掉里面在跑的东西（倒计时由控件自身生命周期带走，扫码服务要显式关）。 */
internal fun MainActivity.hideAccountPanel() {
    val host=accountPanelHost ?: return
    val wasOpen=accountPanelMode!=null
    closeAccountPanelSession()
    accountPanelGeneration++
    host.removeAllViews()
    host.visibility=View.GONE
    accountPanelMode=null
    // 面板里的控件已经移除：身份行原来的「下键 → 面板」接线一并作废，
    // 交回几何搜索（下面就是卡片栅格），避免按向下时指到一个不在树上的 View。
    profileHeadControls().forEach { it.nextFocusDownId=View.NO_ID }
    // 未登录那两枚「二维码登录 / 手机登录」同理：它们的下键原本指向面板里第一个控件，
    // 面板一收，那个控件就离树了。交回几何搜索（下面没有别的东西，会停在原地）。
    loginModeChips.values.forEach { it.nextFocusDownId=View.NO_ID }
    if(wasOpen) {
        val trigger=accountPanelTrigger
        if(trigger!=null && trigger.attachedToWindow) trigger.requestFocus()
    }
    accountPanelTrigger=null
}

/** 关掉面板里的一次性局域网服务（手机扫码上传）。 */
internal fun MainActivity.closeAccountPanelSession() {
    runCatching { accountPanelSession?.close() }
    accountPanelSession=null
}

/**
 * 面板本体：**不画背景框**，内容直接落在页面底板上。
 *
 * 只留一点焦点余量（左右各 [TvStyle.FOCUS_BLEED]），让按钮放大时的描边有地方画；
 * 这一点内边距与页面内容线叠加后仍然对得上（外层已经补过 pageBleed）。
 */
private fun MainActivity.accountPanelBody(): LinearLayout =
    column().apply {
        clipChildren=false
        clipToPadding=false
        setPadding(dp(TvStyle.FOCUS_BLEED),0,dp(TvStyle.FOCUS_BLEED),dp(4))
    }

/**
 * 高度封顶的滚动容器：内容短就按内容高，超过半屏才滚动。
 *
 * 直接用 `ScrollView` + `WRAP_CONTENT` 会让长内容把下面那栏卡片顶到屏幕外；
 * 固定 `MATCH_PARENT` 又会在内容很短时留下一大块空白。这里在测量阶段取两者的较小值。
 */
private class CappedScroll(context: Context) : ScrollView(context) {
    var maxHeight: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        if(measuredHeight>maxHeight) {
            super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(maxHeight,MeasureSpec.EXACTLY))
        }
    }
}

/**
 * 扫码登录面板（内嵌版）。
 *
 * 三步协议全在 [QrLogin] 里，这里只负责「画一张码、每秒问一次、按结果改一行字」：
 *
 * ```
 * 出码 → 轮询（1.1s）→ 拿到 Cookie 落盘
 *          ├ new       → 「请用手机上的官方 App 扫码」
 *          ├ scanned   → 「已扫码，请在手机上确认」
 *          ├ confirmed → hideAccountPanel() + savePhoneLogin()
 *          └ expired   → 平台在同一次响应里给了新码：换图接着轮询（过期自动刷新）
 * ```
 *
 * 轮询**不用** [MainActivity.work]：它挂在页面级 `workJob` 上，`invalidatePage()` 会把
 * 在途请求一并 cancel；而面板是「页面里的一块」，页面重建时面板自己也会被摘掉
 * （`releaseCatalogViews` 里 `accountPanelHost=null`），`alive()` 自然就拦住后续回调。
 * 这里走 [MainActivity.io] + [MainActivity.main]，与手机号表单同一条调度。
 *
 * 排下一次轮询放在**上一次结果落地之后**：网络慢时不会堆出多条并发请求，
 * 也就不会出现「同一个 token 被问两遍、状态来回跳」。
 */
private fun MainActivity.buildQrLoginPanel(
    card: LinearLayout,
    gen: Int,
    trigger: View?
): View {
    fun alive()=gen==accountPanelGeneration &&
        accountPanelMode==ACCOUNT_PANEL_QR &&
        accountPanelHost?.visibility==View.VISIBLE

    val login=QrLogin().apply {
        // 档案 + 本机身份一起给：确认登录拿到的 Cookie 要按同一台设备（`device_id` / `iid` /
        // `cdid` / `openudid`）落盘，否则后续账号接口的 `x-medusa` 签名与 Cookie 里的设备对不上。
        device=library.deviceProfile()+library.deviceIdentity()
        log={ line -> android.util.Log.i("HGTV-QRLOGIN",line) }
    }

    // 与手机号登录同一种卡片材质：大圆角 + 深投影 + 极淡的焦点色描边。
    val band=dp(TvStyle.SOFT_BAND_LARGE)
    card.background=TvStyle.soft(
        this,TvStyle.surfaceHigh,TvStyle.RADIUS_LOGIN_CARD,TvStyle.SOFT_BAND_LARGE,
        SoftStyle.RAISED,
        ring=(TvStyle.focus and 0x00FFFFFF) or (0x21 shl 24),
        ringWidth=1,
        depth=1.5f
    )
    card.setPadding(
        dp(TvStyle.LOGIN_PAD_SIDE)+band,
        dp(TvStyle.LOGIN_PAD_TOP)+band,
        dp(TvStyle.LOGIN_PAD_SIDE)+band,
        dp(TvStyle.LOGIN_PAD_BOTTOM)+band
    )
    LoginForm.header(card,"扫码登录","用手机上的官方 App 扫一扫，手机确认后电视即登录")

    // —— 二维码：白底是**功能需求**（平台给的就是白底黑块 PNG），因此不套主题色，
    //    只在外围留一圈内边距，让白块与卡面之间有一道呼吸。 ——
    val code=ImageView(this).apply {
        id=View.generateViewId()
        scaleType=ImageView.ScaleType.FIT_CENTER
        contentDescription="登录二维码"
        background=softPanel(Color.WHITE,TvStyle.RADIUS_CARD)
        val inset=dp(TvStyle.SOFT_BAND+8)
        setPadding(inset,inset,inset,inset)
    }
    val codeHolder=FrameLayout(this).apply {
        clipChildren=false
        clipToPadding=false
        addView(code,FrameLayout.LayoutParams(dp(QR_LOGIN_SIZE),dp(QR_LOGIN_SIZE),Gravity.CENTER))
    }
    card.addView(codeHolder,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })

    // —— 状态行：等扫码 / 已扫码 / 自动刷新 / 错误，都写在这一行 ——
    val status=LoginForm.note(this,"正在获取二维码…").apply { gravity=Gravity.CENTER }
    card.addView(status,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) })
    card.addView(
        LoginForm.note(this,"二维码 60 秒有效，过期会自动刷新；也可以点下面的按钮立刻换一张。").apply {
            gravity=Gravity.CENTER
        },
        lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_TIGHT) }
    )

    var token=""
    var bitmap: Bitmap?=null
    var closed=false
    /** 上一张码是过期自动换的：状态行要留着「已刷新」，别被下一轮 `new` 立刻盖成「请扫码」。 */
    var renewed=false
    /**
     * 已排期的那一次轮询。
     *
     * **只在这里保存"下一步"的句柄**：[schedule] 撤旧排新，[stop] 撤掉并封住；
     * 轮询体本身用 [poll]，不由这个字段承载。早先的写法把轮询体存在 `tick` 里、
     * 又在 `refreshCode` 开头 `tick=null`，于是出码成功后
     * `main.postDelayed(tick ?: return@post, …)` 直接 return——一次 `check_qrconnect`
     * 都没发出去，手机点完确认电视端永远停在「请扫码」。
     */
    var timer: Runnable?=null
    /** 出码失败后的自动重试（断网 / 网关挡掉都是暂时的，用户不该为此按一下遥控器）。 */
    var retry: Runnable?=null
    /** 轮询体（下面赋值；单独声明是为了让 [schedule] 与 [poll] 互不前置依赖）。 */
    var poll: () -> Unit = {}

    /**
     * 换一张码。
     *
     * 旧位图**不在这里 recycle**：它刚被 ImageView 换下来，但同一帧的绘制队列里可能还挂着它，
     * 提前回收会让老设备抛 `trying to use a recycled bitmap`。一张 512² 的 PNG 只有 1MB，
     * 60 秒才换一次，交给 GC 就够；真正要立刻放掉的是面板收起时那一张（见下面的 detach）。
     */
    fun paint(fresh: QrLogin.Code) {
        val decoded=runCatching { BitmapFactory.decodeByteArray(fresh.png,0,fresh.png.size) }.getOrNull()
        if(decoded!=null) {
            bitmap=decoded
            code.setImageBitmap(decoded)
        }
        token=fresh.token
    }

    fun stop() {
        closed=true
        timer?.let { main.removeCallbacks(it) }
        timer=null
        retry?.let { main.removeCallbacks(it) }
        retry=null
    }

    /** 排下一次轮询：**唯一入口**，撤掉旧的一次再排新的。 */
    fun schedule(delay: Long) {
        timer?.let { main.removeCallbacks(it) }
        timer=null
        if(closed || !alive()) return
        val step=object: Runnable {
            override fun run() {
                timer=null
                if(closed || !alive()) return
                poll()
            }
        }
        timer=step
        main.postDelayed(step,delay)
    }

    fun refreshCode(manual: Boolean) {
        timer?.let { main.removeCallbacks(it) }
        timer=null
        retry?.let { main.removeCallbacks(it) }
        retry=null
        token=""
        status.setTextColor(TvStyle.textDim)
        status.text=if(manual) "正在刷新二维码…" else "正在获取二维码…"
        io.submit {
            val fresh=login.fetchCode()
            main.post {
                if(closed || !alive()) return@post
                if(fresh==null) {
                    status.setTextColor(TvStyle.focusLine)
                    status.text=login.lastError.ifBlank { "二维码获取失败，正在重试…" }
                    // 5 秒后再试一次：面板还开着就一直试，收起面板时 [stop] 会把它摘掉。
                    retry?.let { main.removeCallbacks(it) }
                    val again=object: Runnable {
                        override fun run() {
                            if(closed || !alive()) return
                            refreshCode(manual=false)
                        }
                    }
                    retry=again
                    main.postDelayed(again,QrLogin.POLL_MS*4)
                    return@post
                }
                paint(fresh)
                renewed=false
                status.setTextColor(TvStyle.textDim)
                status.text="请用手机上的官方 App 扫码"
                // 出码成功才起轮询：这一刻之前没有 token 可问。
                schedule(QrLogin.POLL_MS)
            }
        }
    }

    // —— 两个按钮：刷新（手动换码）+ 切到手机号登录 ——
    val actions=row().apply { gravity=Gravity.CENTER_HORIZONTAL }
    val refresh=addButton(actions,"刷新二维码",size=ButtonSize.NORMAL) { refreshCode(manual=true) }
    // 切到手机号登录：`trigger` 用顶部那枚「手机登录」芯片，而不是这枚马上要被摘掉的按钮——
    // 手机面板按下键要回到芯片，登录失败的提示也要挂在一个还在树上的锚点上。
    val toPhone=addButton(actions,"手机号登录",size=ButtonSize.NORMAL,gap=TvStyle.GAP_BLOCK) {
        selectLoginMode(ACCOUNT_PANEL_PHONE,loginModeChips[ACCOUNT_PANEL_PHONE] ?: trigger)
    }
    card.addView(actions,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })

    poll={
        val current=token
        if(current.isNotBlank()) io.submit {
            val outcome=login.check(current)
            main.post {
                if(closed || !alive()) return@post
                // 期间手动刷新过 / 平台已经换了一张码：这一份结果属于旧码，丢掉。
                if(token!=current) return@post
                when(outcome) {
                    QrLogin.Outcome.Waiting -> {
                        status.setTextColor(TvStyle.textDim)
                        // 刚自动换过码时保留那一句提示，否则 1.1 秒后就被盖掉，
                        // 用户只看到二维码变了一下、不知道是过期刷新。
                        if(!renewed) status.text="请用手机上的官方 App 扫码"
                    }
                    is QrLogin.Outcome.Scanned -> {
                        renewed=false
                        status.setTextColor(TvStyle.textDim)
                        status.text="已扫码，请在手机上确认登录"
                    }
                    is QrLogin.Outcome.Renewed -> {
                        // 平台在 `expired` 的同一次响应里下发了新码：原地换掉，接着轮询。
                        paint(outcome.code)
                        renewed=true
                        status.setTextColor(TvStyle.textDim)
                        status.text="二维码已过期，已自动刷新，请重新扫码"
                    }
                    is QrLogin.Outcome.Confirmed -> {
                        status.text="登录成功"
                        stop()
                        hideAccountPanel()
                        savePhoneLogin(outcome.credentials,trigger)
                        return@post
                    }
                    is QrLogin.Outcome.Failed -> {
                        status.setTextColor(TvStyle.focusLine)
                        status.text=outcome.message.ifBlank { "扫码状态读取失败，正在重试…" }
                    }
                }
                // 失败（断网 / 网关挡掉）不是终局：隔得久一点再问，别按 1.1 秒硬敲。
                schedule(if(outcome is QrLogin.Outcome.Failed) 3000L else QrLogin.POLL_MS)
            }
        }
    }

    // 焦点：芯片 ↓ 刷新 →（右）手机号 →（上）回入口芯片。
    refresh.nextFocusRightId=toPhone.id
    toPhone.nextFocusLeftId=refresh.id
    toPhone.nextFocusRightId=toPhone.id
    if(trigger!=null) {
        refresh.nextFocusUpId=trigger.id
        toPhone.nextFocusUpId=trigger.id
    }

    refreshCode(manual=false)

    // 面板被收起 / 换模式：停轮询、放掉最后一张码。不停的话它会每 1.1 秒打一次接口。
    card.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {}
        override fun onViewDetachedFromWindow(view: View) {
            stop()
            val old=bitmap
            bitmap=null
            code.setImageDrawable(null)
            old?.recycle()
        }
    })
    return refresh
}

/**
 * 手机号 + 验证码表单（内嵌版）。
 *
 * 逻辑与原来的弹窗版一致：发码冷却、平台 `retry_time`、回车 / 确认键接管、
 * 上行短信转 [com.hgtv.star.tv.TvTools.upsmsOnTv]。区别只是所有控件挂在页面里，
 * 收起面板 / 成功登录之后由 [hideAccountPanel] 收尾。
 */
private fun MainActivity.buildPhoneLoginPanel(
    card: LinearLayout,
    gen: Int,
    trigger: View?,
    prefillPhone: String,
    autoSend: Boolean
): View {
    fun alive()=gen==accountPanelGeneration &&
        accountPanelMode==ACCOUNT_PANEL_PHONE &&
        accountPanelHost?.visibility==View.VISIBLE

    val login=PhoneLogin().apply {
        device=library.deviceProfile()
        log={ line -> android.util.Log.i("HGTV-LOGIN",line) }
    }
    var countryIndex=0

    // 面板本体就是设计稿里那张居中的登录卡：大圆角 + 深投影 + 一圈极淡的焦点色描边。
    val cardBand = dp(TvStyle.SOFT_BAND_LARGE)
    card.background = TvStyle.soft(
        this, TvStyle.surfaceHigh, TvStyle.RADIUS_LOGIN_CARD, TvStyle.SOFT_BAND_LARGE,
        SoftStyle.RAISED,
        ring = (TvStyle.focus and 0x00FFFFFF) or (0x21 shl 24),
        ringWidth = 1,
        depth = 1.5f
    )
    card.setPadding(
        dp(TvStyle.LOGIN_PAD_SIDE) + cardBand,
        dp(TvStyle.LOGIN_PAD_TOP) + cardBand,
        dp(TvStyle.LOGIN_PAD_SIDE) + cardBand,
        dp(TvStyle.LOGIN_PAD_BOTTOM) + cardBand
    )

    // —— 卡片顶部：小号标识 + 大标题 + 一行说明（设计稿的 lhead） ——
    LoginForm.header(card,"登录","使用手机号登录，同步你的收藏与观看记录")

    // —— 字段一：手机号（区号 + 输入框同一行） ——
    val phoneField=LoginForm.field(this,"手机号","请输入手机号",numeric=true,maxLength=20,phoneStyle=true)
    val codeField=LoginForm.field(this,"验证码","收到短信后填入",numeric=true,maxLength=8)
    val note=LoginForm.note(this,"未注册的手机号会自动创建账号。验证码由平台短信发出，我们不会保存它。")
    /**
     * 预填：把上次那串 E.164（`+8613812345678`）拆成「区号 + 本地号码」。
     *
     * 直接把整串塞进输入框会让框里出现 `+86`——区号已经在左边那枚按钮上写着了，
     * 输入框里再带一遍，用户看到的就是「号码里混进了地区码」；
     * 而且提交时还会再拼一次区号，变成 `+86+8613…`。
     */
    if(prefillPhone.isNotBlank()) {
        val raw=prefillPhone.trim()
        // 找最长匹配的区号（`+852` 要先于 `+85` 这种不存在的短前缀，因此按长度倒序）。
        val matched=dialCodes.withIndex()
            .map { it.index to it.value.substringBefore(" ") }
            .filter { (_,dial) -> raw.startsWith(dial) }
            .maxByOrNull { (_,dial) -> dial.length }
        if(matched!=null) {
            countryIndex=matched.first
            val local=raw.removePrefix(matched.second).filter { it.isDigit() }
            phoneField.setText(local)
            phoneField.setSelection(local.length)
        } else {
            val local=raw.filter { it.isDigit() }
            phoneField.setText(local)
            phoneField.setSelection(local.length)
        }
    }

    /**
     * 手机号归一：**只留数字**，再按所选区号拼成 E.164（带 `+`）。
     *
     * 这里不判断号码长度、也不判断「是不是 1 开头」：中国大陆是 11 位，
     * 香港 / 澳门 8 位、日本 10–11 位、美国 10 位，各地区的形状都不一样。
     * 按中国大陆的规则卡，其它地区的号码会在本地就被拦下来——用户看到的
     * 就是「号码明明是对的，却提示格式不对」。长度与合法性交给平台判，
     * 它回的错误文案原样显示在说明行里。
     */
    fun normalizedPhone(): String? {
        val raw=phoneField.text.toString().trim()
        // 用户自己把完整国际号码敲进来了（带 `+`）：原样送出去，不再套一次左边的区号。
        // 否则「+86」按钮 + 「+8613…」输入框会拼成 `+86+8613…`。
        if(raw.startsWith("+")) {
            val digits=raw.filter { it.isDigit() }
            return if(digits.isEmpty()) null else "+"+digits
        }
        val local=raw.filter { it.isDigit() }
        if(local.isEmpty()) return null
        val dial=dialCodes[countryIndex].substringBefore(" ").drop(1)
        // 输入框里只放本地号码（区号由左边那枚按钮表达），这里才拼上 `+区号`。
        // core 那边据此**不再**按中国大陆的形状猜一遍
        //（否则「+1 美国 10 位」会被当成 11 位大陆号码补成 +86）。
        return "+"+dial+local
    }

    var cooldownUntil=0L
    var countdown: Runnable?=null
    lateinit var send: LoginForm.PillButton
    fun refreshCooldown() {
        val left=((cooldownUntil-System.currentTimeMillis())/1000L).toInt()
        if(left<=0) {
            cooldownUntil=0
            send.isEnabled=true
            send.setLabel("获取验证码")
            return
        }
        send.isEnabled=false
        send.setLabel("$left 秒")
        if(send.isFocused) codeField.requestFocus()
        val tick=object: Runnable {
            override fun run()=refreshCooldown()
        }
        countdown=tick
        main.postDelayed(tick,1000L)
    }
    fun beginCooldown(seconds: Int) {
        cooldownUntil=System.currentTimeMillis()+seconds*1000L
        val pending=countdown
        if(pending!=null) main.removeCallbacks(pending)
        refreshCooldown()
    }
    /** 点「获取验证码」之后真正发码的那一段（须知确认前后都走这一份）。 */
    fun startSend(phone: String) {
        beginCooldown(60)
        note.text="正在向 $phone 发送验证码…"
        io.submit {
            val result=login.sendCode(phone) { profile -> registerForPhoneLogin(profile) }
            main.post {
                if(!alive()) return@post
                when(result) {
                    is PhoneLogin.Result.Sent -> {
                        beginCooldown(login.retryAfterSeconds.coerceAtLeast(60))
                        note.text="验证码已发到 $phone，请查看短信。"
                        codeField.requestFocus()
                    }
                    is PhoneLogin.Result.NeedUpsms -> {
                        // 发码这一步先被要求发一条上行短信：这时没有验证码可登，
                        // 通过后重开面板自动发一张新码（见 `PhoneLogin.mfaFromSendCode`）。
                        countdown?.let { main.removeCallbacks(it) }
                        cooldownUntil=0
                        send.isEnabled=true
                        send.setLabel("获取验证码")
                        hideAccountPanel()
                        tvTools.upsmsOnTv(trigger,login,phone,"",result.number,result.content,
                            { credentials -> savePhoneLogin(credentials,trigger) },
                            { selectLoginMode(ACCOUNT_PANEL_PHONE,trigger,prefillPhone=phone) },
                            onUpsmsVerified={
                                selectLoginMode(ACCOUNT_PANEL_PHONE,trigger,prefillPhone=phone,autoSend=true)
                            })
                    }
                    is PhoneLogin.Result.Failed -> {
                        countdown?.let { main.removeCallbacks(it) }
                        cooldownUntil=0
                        send.isEnabled=true
                        send.setLabel("获取验证码")
                        note.text=result.message
                    }
                    else -> {
                        countdown?.let { main.removeCallbacks(it) }
                        cooldownUntil=0
                        send.isEnabled=true
                        send.setLabel("获取验证码")
                        note.text="验证码未发出，请稍后重试。"
                    }
                }
            }
        }
    }
    send=LoginForm.PillButton(this,"获取验证码",TvStyle.LOGIN_SEND_WIDTH, action=send@{
        if(System.currentTimeMillis()<cooldownUntil) return@send
        val phone=normalizedPhone()
        if(phone==null) {
            note.text="请先填手机号。"
            phoneField.requestFocus(); return@send
        }
        // **每一次**点「获取验证码」都先弹一次确认（见 [confirmSmsConsent]）：
        // 它问的是「这一次要不要真的把手机号发给平台」，与「进『我的』页那条使用须知」
        // 是两回事，也不看那条确认过没有。点「不同意」就什么都不发。
        confirmSmsConsent(send) { startSend(phone) }
    })
    // —— 字段二：验证码 + 「获取验证码」（右侧窄胶囊） ——
    val phoneFieldHeight=dp(LoginForm.FIELD_HEIGHT+2*TvStyle.SOFT_BAND)
    val phoneRow=row()
    lateinit var countryButton: LoginForm.RegionButton

    val codeRow=row()
    codeRow.addView(codeField,LinearLayout.LayoutParams(0,phoneFieldHeight,1f))
    codeRow.addView(send,LinearLayout.LayoutParams(send.widthPx,-2).apply {
        leftMargin=dp(TvStyle.GAP_INLINE)
    })

    val phoneBlock=column()
    phoneBlock.addView(LoginForm.label(this,"手机号"))
    phoneBlock.addView(phoneRow,lp(-1,-2))
    card.addView(phoneBlock,lp(-1,-2))

    val codeBlock=column()
    codeBlock.addView(LoginForm.label(this,"验证码"))
    codeBlock.addView(codeRow,lp(-1,-2))
    card.addView(codeBlock,lp(-1,-2).apply { topMargin=dp(TvStyle.LOGIN_FIELD_GAP) })

    card.addView(note,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) })

    // —— 主操作：整宽胶囊「登录」 ——
    lateinit var submit: LoginForm.SubmitButton
    submit=LoginForm.SubmitButton(this,"登 录") {
        val phone=normalizedPhone()
        val code=codeField.text.toString().trim()
        if(phone==null) {
            note.text="请先填手机号。"
            phoneField.requestFocus(); return@SubmitButton
        }
        if(!code.matches(Regex("[0-9]{4,8}"))) {
            note.text="验证码是 4—8 位数字。"
            codeField.requestFocus(); return@SubmitButton
        }
        note.text="正在登录…"
        io.submit {
            val result=login.login(phone,code)
            main.post {
                if(!alive()) return@post
                when(result) {
                    is PhoneLogin.Result.LoggedIn -> {
                        hideAccountPanel()
                        savePhoneLogin(result.credentials,trigger)
                    }
                    is PhoneLogin.Result.NeedUpsms -> {
                        hideAccountPanel()
                        // 这一步平台要求用户用手机发一条短信，仍然用原来的二维码页（它是独立的一步）。
                        // 挑战来自发码那一步时没有验证码可登，通过后要重开面板发新码。
                        tvTools.upsmsOnTv(trigger,login,phone,code,
                            result.number,result.content,
                            { credentials -> savePhoneLogin(credentials,trigger) },
                            { selectLoginMode(ACCOUNT_PANEL_PHONE,trigger,prefillPhone=phone) },
                            onUpsmsVerified={
                                selectLoginMode(ACCOUNT_PANEL_PHONE,trigger,prefillPhone=phone,autoSend=true)
                            })
                    }
                    is PhoneLogin.Result.Failed -> note.text=result.message
                    else -> note.text="登录失败，请稍后重试。"
                }
            }
        }
    }
    card.addView(submit,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })

    // —— 区号下拉：与全站弹窗同一套材质（大圆角 + 大柔光带），选项按内容收紧 ——
    val countryPopup=PopupWindow(this).apply {
        isFocusable=true
        isOutsideTouchable=true
        inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
        setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
    val countryOptionsColumn=column().apply {
        val band=dp(TvStyle.SOFT_BAND_LARGE)
        background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_LOGIN_CARD,band=TvStyle.SOFT_BAND_LARGE,SoftStyle.RAISED,depth=1.25f)
        setPadding(dp(12)+band,dp(12)+band,dp(12)+band,dp(12)+band)
    }
    val countryOptions=dialCodes.mapIndexed { index,label ->
        val option=TvButton(this,label,ButtonSize.DIALOG,ButtonTone.SECONDARY,
            alignStart=true,action={
                countryIndex=index
                countryButton.text=dialCodes[index].substringBefore(" ")
                countryPopup.dismiss()
            })
        countryOptionsColumn.addView(option,lp(-1,-2).apply {
            if(countryOptionsColumn.childCount>0) topMargin=dp(6)
        })
        option
    }
    val countryScroll=CappedScroll(this).apply {
        isVerticalScrollBarEnabled=false
        clipChildren=false
        clipToPadding=false
        // 国家区号下拉：同样按**可用**高度算（见上面的登录卡），整屏高度会让最后几条
        // 落在系统栏底下——Android 14 上就是「列表下面少一截」。
        maxHeight=(usableScreenHeight()*.45f).toInt()
        addView(countryOptionsColumn,LinearLayout.LayoutParams(-1,-2))
    }
    countryPopup.contentView=countryScroll
    countryOptionsColumn.measure(
        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),
        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
    )
    countryPopup.width=countryOptionsColumn.measuredWidth.coerceIn(dp(200),dp(300))
    countryPopup.height=ViewGroup.LayoutParams.WRAP_CONTENT
    countryPopup.setOnDismissListener {
        if(countryButton.attachedToWindow) countryButton.requestFocus()
    }
    countryOptions.forEachIndexed { index,option ->
        option.nextFocusUpId=countryOptions[(index-1).coerceAtLeast(0)].id
        option.nextFocusDownId=countryOptions[(index+1).coerceAtMost(countryOptions.lastIndex)].id
        option.setOnKeyListener { _,keyCode,event ->
            if(event.action==KeyEvent.ACTION_DOWN && keyCode==KeyEvent.KEYCODE_BACK) {
                countryPopup.dismiss(); true
            } else false
        }
    }
    // 初始文案跟着 [countryIndex]：从「重新获取验证码」回来时预填的号码可能不是 +86，
    // 按钮上必须显示那一次选中的区号，否则输入框里是 8 位本地号码、按钮却写着 +86。
    countryButton=LoginForm.RegionButton(this,dialCodes[countryIndex].substringBefore(" ")) {
        if(countryPopup.isShowing) return@RegionButton
        countryPopup.showAsDropDown(countryButton,0,dp(6))
        val current=countryOptions.getOrNull(countryIndex) ?: countryOptions.first()
        current.post { current.requestFocus() }
    }
    phoneRow.removeAllViews()
    phoneRow.addView(countryButton,LinearLayout.LayoutParams(dp(TvStyle.LOGIN_REGION_WIDTH),-2))
    phoneRow.addView(phoneField,LinearLayout.LayoutParams(0,phoneFieldHeight,1f).apply {
        leftMargin=dp(TvStyle.GAP_INLINE)
    })

    // —— 回车 / 确认键：手机号 → 验证码，验证码 → 登录 ——
    phoneField.setOnEditorActionListener { _,_,event ->
        if(event?.action==KeyEvent.ACTION_DOWN) true
        else { codeField.requestFocus(); true }
    }
    codeField.setOnEditorActionListener { _,_,event ->
        if(event?.action==KeyEvent.ACTION_DOWN) true
        else { submit.performClick(); true }
    }

    // —— 焦点链：区号 →（右）手机号 →（下）验证码 →（右）获取验证码 →（下）登录 ——
    countryButton.nextFocusRightId=phoneField.id
    countryButton.nextFocusDownId=codeField.id
    phoneField.nextFocusLeftId=countryButton.id
    if(trigger!=null) phoneField.nextFocusUpId=trigger.id
    phoneField.nextFocusDownId=codeField.id
    codeField.nextFocusRightId=send.id
    codeField.nextFocusUpId=phoneField.id
    codeField.nextFocusDownId=submit.id
    send.nextFocusLeftId=codeField.id
    send.nextFocusUpId=phoneField.id
    send.nextFocusDownId=submit.id
    submit.nextFocusUpId=send.id

    // 上行短信刚通过：面板一展开就替用户把「获取验证码」按下去。
    // 放在最后，等所有控件都挂好、`send` 也能点了再触发。
    if(autoSend) send.post { if(send.isEnabled) send.performClick() }

    // 面板被收起 / 换模式时停掉倒计时：它每秒自己重排一次，
    // 不停就会在已经移除的按钮上一直跑到冷却结束。
    card.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {}
        override fun onViewDetachedFromWindow(view: View) {
            if(countryPopup.isShowing) countryPopup.dismiss()
            countdown?.let { main.removeCallbacks(it) }
            countdown=null
        }
    })
    return countryButton
}
