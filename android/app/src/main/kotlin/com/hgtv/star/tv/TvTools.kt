// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hgtv.star.R
import com.hgtv.star.core.AccountCredentialsParser
import com.hgtv.star.core.AccountImportServer
import com.hgtv.star.core.AccountCredentials
import com.hgtv.star.core.PhoneLogin
import com.hgtv.star.core.PhoneLoginServer
import com.hgtv.star.core.PhoneSearchServer
import com.hgtv.star.core.LibraryTransferServer
import com.hgtv.star.core.BackupData
import com.hgtv.star.compat.activeNetworkOrNull
import java.net.Inet4Address
import java.util.concurrent.Executors
import com.hgtv.star.compat.ellipsizeCompat

/** 一条提示显示多久（毫秒）。短是刻意的：它只用来交代「刚才那一下失败了」。 */
private const val TOAST_MS=1900L
/** 提示淡入的时长（毫秒）。 */
private const val TOAST_FADE=120L
/** 提示距屏幕底边的距离（dp）。 */
private const val TOAST_MARGIN=34

/** 手填 sessionid 那个输入框的可见高度（与常规按钮同档，比默认的一行高出一倍）。 */
private const val SESSION_FIELD_HEIGHT=TvStyle.BUTTON_HERO
/** 手填 sessionid 的正文 / 提示字号。 */
private const val SESSION_FIELD_TEXT=20f
/**
 * 上行短信验证的自动确认间隔。
 *
 * 平台通常只比用户按下发送晚几秒就记下了这条短信，因此**从页面出现起**就每 4 秒问一次
 * （见 [upsmsOnTv] 的注释：等用户从手机走回电视再动手，平台那边早就判过
 * 「长时间未操作，验证已过期」了，因此电视这一页**没有**需要用户再按的按钮）。
 * 这里不再设次数上限：问多久由平台自己决定——
 * 它认了就给 ticket，它明确结束了就回 9001 那类话，[PhoneLogin] 里也有 10 分钟窗口兜底。
 */
private const val UPSMS_POLL_MS=4000L
/**
 * 上行短信页出现后**先等这么久**再发第一次确认。
 *
 * 用户要扫码、切到短信、按发送，最快也要十几秒；页面一出来就 `gen_verify_ticket`
 * 会在他还没看清号码时就把结果定掉（一旦换票失败，这一页立刻被关掉，
 * 表现就是「还没让我发短信就报错」）。这里留 8 秒，之后再按 4 秒一轮。
 */
private const val UPSMS_FIRST_POLL_MS=8000L

/**
 * 原生模态交互：手机输入、备份恢复、二次确认、列表选择。
 *
 * 全部走 [TvDialog]，因此弹窗里的按钮与页面上是同一套尺寸、圆角与焦点反馈——
 * 这里不再出现系统 AlertDialog 的 Material 文字按钮。
 */
class TvTools(private val activity: Activity) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog: TvDialog?=null
    val showing: Boolean get()=dialog?.isShowing==true
    private var phone: java.io.Closeable?=null
    private var toastView: TextView?=null
    private val hideToast=Runnable {
        val view=toastView ?: return@Runnable
        toastView=null
        (view.parent as? android.view.ViewGroup)?.removeView(view)
    }
    private fun dp(value: Number)=activity.designPx(value)
    /** 弹窗正文文字：与全站正文同一档字号与颜色，不再单独写一套白色 + 内边距。 */
    private fun label(value: String,size: Float=16f)=activity.tvText(value,size,TvStyle.textDim)
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private fun open(next: TvDialog,anchor: View?) {
        dialog?.dismiss(); dialog=next
        next.show(anchor) { if(dialog===next) { phone?.close(); phone=null; dialog=null } }
    }
    /**
     * 显示一个调用方自己搭好的 [TvDialog]。
     *
     * 绝大多数弹窗都该走 [info] / [confirm] / [choose] 这几个成品；只有内容形态特殊
     * （例如「使用须知」那种纯长文 + 单枚同意按钮）才直接用它，免得把同一个面板再拼一遍。
     */
    internal fun show(next: TvDialog,anchor: View?) = open(next,anchor)
    fun close() { dialog?.dismiss(); phone?.close(); phone=null }
    fun destroy() { close(); clearToast(); worker.shutdownNow() }
    /**
     * 轻量提示（短时浮层）。
     *
     * 用在「需要登录态的请求报错了」这类**必须让用户知道、又不值得打断操作**的场合：
     * 每条只显示 [TOAST_MS]，新的那条**立刻顶掉**旧的那条（不排队、不叠加），
     * 因此连续几次失败不会在屏幕上堆一串提示。
     *
     * 它不参与焦点、不可点击、也不吃按键：电视上没有系统 Toast 那一层浮窗，
     * 样式与字号仍然由本应用说了算（与 [MainActivity.showHint] 的分工是：
     * 那一行属于页面内的状态说明，这一层属于「刚才那一下失败了」的即时反馈）。
     */
    fun toast(message: String) {
        if(message.isBlank()) return
        val root=activity.window?.decorView as? android.view.ViewGroup ?: return
        clearToast()
        val view=TextView(activity).apply {
            text=message
            designTextSize(15f)
            setTextColor(TvStyle.text)
            maxLines=2
            setPadding(dp(20),dp(11),dp(20),dp(11))
            background=TvStyle.soft(activity,TvStyle.surfaceHigh,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,SoftStyle.RAISED,depth=1.15f)
            // 不吃焦点、不吃按键：提示出现时用户正在按的那一下不能被打断。
            isFocusable=false
            isFocusableInTouchMode=false
            isClickable=false
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha=0f
        }
        root.addView(
            view,
            FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin=dp(TOAST_MARGIN) }
        )
        toastView=view
        view.animate().alpha(1f).setDuration(TOAST_FADE).start()
        main.postDelayed(hideToast,TOAST_MS)
    }
    private fun clearToast() {
        main.removeCallbacks(hideToast)
        val view=toastView ?: return
        toastView=null
        view.animate().cancel()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
    }
    fun info(title: String,message: String,anchor: View?) {
        val next=TvDialog(activity,title).message(message)
        next.button("知道了", icon=R.drawable.ic_menu_check)
        open(next,anchor)
    }
    /**
     * 二次确认：焦点默认落在「取消」上，确认必须多按一次，误触不会造成破坏。
     *
     * 两个按钮都用常规档（**不用 PRIMARY**）：PRIMARY 是「常亮的亮面板」，
     * 在一排按钮里看起来就像「一直处于焦点」，退出确认这类弹窗里会让人以为焦点已经落在确认上。
     * 弹窗里靠文案区分动作就够了，按钮外观一律常态。
     */
    /**
     * 二次确认。[danger] 为 true 时「确认」用主题状态色，
     * 只给删除 / 清空这类不可撤销的操作。
     */
    fun confirm(title: String,message: String,anchor: View?,danger: Boolean=false,action: ()->Unit) {
        val next=TvDialog(activity,title).message(message)
        next.button("取消", icon=R.drawable.ic_menu_close) {}
        next.button("确认",tone=if(danger) ButtonTone.DANGER else ButtonTone.SECONDARY,
            icon=if(danger) R.drawable.ic_menu_trash else R.drawable.ic_menu_check) { action() }
        open(next,anchor)
    }
    /**
     * 列表选择：整行胶囊选项（左图标 + 文字），顶部可选封面信息区。
     *
     * [subtitle] / [cover] 给卡片长按菜单用：把这部剧的封面与「第几季 · 共几集」摆在面板顶部，
     * 用户一眼确认按对了哪一部。设置页那几个选择器不传，面板顶部就只留标题。
     * [icons] 与 [labels] 等长（缺省项不画图标），[danger] 里的下标用红字红环。
     */
    fun choose(
        title: String,
        labels: List<String>,
        anchor: View?,
        onChoice: (Int)->Unit,
        extra: String?=null,
        subtitle: String="",
        cover: String="",
        icons: List<Int?> = emptyList(),
        danger: Set<Int> = emptySet(),
        loadCover: ((android.widget.ImageView,String)->Unit)? = null,
        onExtra: ()->Unit={}
    ) {
        val next=TvDialog(activity,title)
        if(cover.isNotBlank() || subtitle.isNotBlank()) next.header(title,subtitle,cover,loadCover)
        next.choices(labels,icons=icons,danger=danger,onChoice=onChoice)
        if(extra!=null) next.button(extra, icon=R.drawable.ic_menu_trash) { onExtra() }
        next.button("取消", icon=R.drawable.ic_menu_close) {}
        open(next,anchor)
    }
    /**
     * 二维码：一块嵌在弹窗里的白色凸起方板。
     * 新拟态里「白」是最亮的一档材质，因此二维码不用描边也压得住深色弹窗，
     * 四周的柔光带同时也是二维码的静区（quiet zone），扫起来更稳。
     */
    private fun qr(url: String): FrameLayout {
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,360,360)
        val pixels=IntArray(360*360) { i->if(bits[i%360,i/360]) Color.BLACK else Color.WHITE }
        val code=ImageView(activity).apply {
            setImageBitmap(Bitmap.createBitmap(pixels,360,360,Bitmap.Config.ARGB_8888))
            // 白底黑码是给摄像头读的，不做缩放裁切：整块等比放进方板里。
            scaleType=ImageView.ScaleType.FIT_CENTER
            contentDescription="二维码"
        }
        // 白板只留一条很窄的静区：新拟态里白是最亮的一档，四周柔光带本身就是留白，
        // 再塞一圈厚内边距只会让二维码显得又小又闷（早先内边距是 14dp）。
        val inset=dp(TvStyle.SOFT_BAND)+dp(4)
        return FrameLayout(activity).apply {
            background=TvStyle.soft(activity,Color.WHITE,TvStyle.RADIUS_CARD,TvStyle.SOFT_BAND,SoftStyle.RAISED,depth=1.15f)
            setPadding(inset,inset,inset,inset)
            addView(code,FrameLayout.LayoutParams(-1,-1))
        }
    }
    /**
     * 电视在局域网里的 IPv4 地址：手机表单（手机输入 / 登录态 / 备份恢复）都要用它拼二维码。
     * 没有可用的 Wi-Fi 或有线网络时返回 null，由调用方给出各自的提示。
     */
    internal fun lanAddress(): String? {
        // API 19–22 没有 getActiveNetwork（23 才有），getNetworkCapabilities / getLinkProperties 是 21 才有；
        // 因此这条「按系统网络查地址」的路只在 23+ 走，其余版本统一退回
        // 「遍历本机网卡取第一个站点内 IPv4」：4.4 / 5.x 电视只有 Wi-Fi 或有线，两种都会以网卡形式出现。
        val connectivity=activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if(Build.VERSION.SDK_INT>=23 && connectivity!=null) {
            val network=connectivity.activeNetworkOrNull() ?: return null
            val capabilities=connectivity.getNetworkCapabilities(network) ?: return null
            if(!(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
            connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress?.let { return it }
        }
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
    /**
     * 上行短信的兜底信息：收件号码 + 短信内容，各占一条凹陷槽。
     *
     * 为什么要有：`sms:` 深链能不能被识别取决于手机的扫码器。认不出来时，
     * 用户至少要能把这两段**照抄**下来——早先它们被塞在说明文字里，
     * 号码与正文混在同一段，抄的时候很容易漏字。
     */
    private fun smsFallback(number: String,content: String): LinearLayout {
        val box=column()
        listOf("收件号码" to number,"短信内容" to content).forEachIndexed { index,(title,value) ->
            val item=column().apply {
                background=TvStyle.well(activity,TvStyle.well,TvStyle.RADIUS_CARD,TvStyle.SOFT_BAND)
                val band=dp(TvStyle.SOFT_BAND)
                setPadding(band+dp(TvStyle.GAP_INLINE),band+dp(TvStyle.GAP_TIGHT),band+dp(TvStyle.GAP_INLINE),band+dp(TvStyle.GAP_TIGHT))
            }
            item.addView(label(title,12f))
            item.addView(label(value,15f),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(2) })
            box.addView(item,LinearLayout.LayoutParams(-1,-2).apply {
                if(index>0) topMargin=dp(TvStyle.GAP_TIGHT)
            })
        }
        return box
    }
    /**
     * 扫码弹窗的正文：左边一块二维码方板，右边一列**分步说明**，最下面一行是局域网地址。
     *
     * 早先是一块 185dp 的方板 + 一整块凹陷槽里堆一大段文字，读起来是一坨：
     * 手机上要做什么、做完会怎样、打不开该看哪个地址，全糊在同一段里。
     * 现在按「扫码 → 手机上做什么 → 结果回到电视」拆成三行，每行前面一枚小序号；
     * 地址单独做成一条淡色胶囊（只在局域网地址时显示，`sms:` 深链不显示）。
     */
    internal fun scanBody(instructions: String,url: String,into: LinearLayout) {
        val row=LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            clipChildren=false; clipToPadding=false
        }
        // 方板边长收到 156dp：电视上离两三米看，这个尺寸仍然扫得稳，但不再把弹窗撑满。
        val side=dp(156+2*(TvStyle.SOFT_BAND+4))
        row.addView(qr(url),LinearLayout.LayoutParams(side,side))

        val column=column().apply { gravity=Gravity.CENTER_VERTICAL }
        val steps=instructions.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        steps.forEachIndexed { index, line ->
            val item=LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.TOP
                clipChildren=false; clipToPadding=false
            }
            // 序号圆点：比正文小一档，只做「这是第几步」的视觉锚点，不抢文字。
            val badge=TextView(activity).apply {
                text="${index+1}"
                designTextSize(12f)
                setTextColor(TvStyle.muted)
                gravity=Gravity.CENTER
                background=TvStyle.soft(activity,TvStyle.surfaceHigh,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,SoftStyle.RAISED,depth=1.05f)
            }
            item.addView(badge,LinearLayout.LayoutParams(dp(20),dp(20)).apply {
                topMargin=dp(2); rightMargin=dp(TvStyle.GAP_TIGHT)
            })
            item.addView(label(line,15f),LinearLayout.LayoutParams(0,-2,1f))
            column.addView(item,LinearLayout.LayoutParams(-1,-2).apply {
                if(index>0) topMargin=dp(TvStyle.GAP_TIGHT)
            })
        }
        // 地址：只在局域网地址时显示。做成一条淡色胶囊贴在说明下面，而不是正文里的一行。
        if(!url.startsWith("sms:")) {
            val host=TextView(activity).apply {
                text=url
                designTextSize(12f)
                setTextColor(TvStyle.textDim)
                maxLines=1
                // MIDDLE 在 API 19 及以下的 StaticLayout 里会下标越界（见 ellipsizeCompat）。
                ellipsizeCompat(android.text.TextUtils.TruncateAt.MIDDLE)
                setPadding(dp(12),dp(5),dp(12),dp(5))
                background=TvStyle.soft(activity,TvStyle.well,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,SoftStyle.INSET)
            }
            column.addView(host,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) })
        }
        row.addView(column,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(TvStyle.GAP_BLOCK) })
        into.addView(row)
    }
    /**
     * 手机输入剧名：电视出二维码，手机扫码后在一张竖屏页面上把词打完发过来。
     *
     * 遥控器上敲中文要按几十下方向键，而搜索框是这一页唯一需要长文本的地方，
     * 因此这里把「打字」整个搬到手机上：手机上打完按发送，电视立刻按这个词搜索。
     *
     * 与「手机号登录 / 导入账号参数 / 备份恢复」同一套一次性局域网约束
     * （随机地址、只监听局域网、限时、用完即关，见 [PhoneSearchServer]）。
     */
    fun phoneInput(anchor: View?, submit: (String)->Unit) {
        val address=lanAddress()
        if(address==null) {
            info("手机输入","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return
        }
        val next=TvDialog(activity,"手机输入剧名")
        next.button("关闭", icon=R.drawable.ic_menu_close)
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val session=PhoneSearchServer(address,
                    // 收到就先把这一句写进页面提示行，再走搜索：手机那边点了「发送到电视」，
                    // 电视上必须**看得见**它收到了什么，否则「发送成功但搜索框没变」分不清是哪一端的问题。
                    { query -> main.post { if(dialog===next) { next.dismiss(); submit(query) } } },
                    { main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("用手机相机扫下面的码，打开电视发的一次性页面\n在手机上把剧名打完，按「发送到电视」\n电视会按当前选择的漫剧 / 短剧分类立刻搜索",session.url,next.content)
                }
            } catch(e: Exception) {
                // 弹窗打不开时界面只给一句「请检查网络」，真正的原因（容器不支持、
                // 端口占用、权限被拒）全被吞掉——这类反馈最难查，因为用户只会说「点了没反应」。
                com.hgtv.star.Diagnostics.warn("HGTV-TOOLS","phone input dialog failed: ${e.javaClass.simpleName}: ${e.message}",e)
                main.post { if(dialog===next) status.text="无法开启手机输入，请检查网络后重试。" }
            }
        }
    }
    /**
     * 手机号 + 验证码登录：电视出二维码，**在手机上**完成「填号 → 收码 → 填码」。
     *
     * 与「导入账号参数」是同一个目的（拿到账号会话），但不用用户去翻 App 沙盒导文件；
     * 手机号 + 验证码用遥控器敲要按几十下，因此整套交互都放在手机页面上
     * （见 [PhoneLoginServer]）。
     *
     * 平台有时还会要求「用本机号码主动发一条短信」，那种情况下手机页面会显示
     * 平台给的收件号码与短信内容，用户发完点「我已发送」，页面自己接着走完。
     *
     * [deviceProfile] 由调用方给（本机真机档案），注册设备与请求签名共用它。
     */
    /**
     * 手机号 + 验证码登录（电视本地路径）：一页表单，输入走**系统键盘**。
     *
     * 版面是「手机号输入框 + 获取验证码 / 验证码输入框 + 登录」，与手机上常见的登录页一致：
     * 两栏各自有标签、输入框是凹陷槽 + 胶囊圆角（与搜索框同一套外观）、
     * 底下两枚按钮分主次（「获取验证码」次级、「登录」主色）。
     *
     * 与 [phoneLogin]（手机扫码）是同一个目的的两条路，走同一份 [PhoneLogin] 会话与同一套落盘逻辑；
     * 区别只是「在哪儿敲那串手机号」。
     *
     * 平台要求上行短信时转到 [upsmsOnTv]：那一步必须用手机发短信，
     * 因此那边**提供扫码**——手机扫码打开页面，页面上把收件号码与内容摆好，一键跳到短信 App。
     */
    fun phoneLoginForm(
        anchor: View?,
        deviceProfile: Map<String,String>,
        register: (Map<String,String>)->com.hgtv.star.core.DeviceRegister.Registered?,
        onLoggedIn: (AccountCredentials)->Unit,
        /**
         * 手机号预填：从「重新获取验证码」回来时用。
         *
         * 遥控器上敲手机号是这一页最费时间的一步，而平台对验证码与本次验证都有时效——
         * 让用户重敲一遍手机号只会让超时更容易发生。预填之后要做的只剩「获取验证码 → 填新码」。
         */
        prefillPhone: String = "",
        /**
         * 打开后**自动点一次「获取验证码」**。
         *
         * 只在「上行短信刚通过、平台要求重新发码」这一种收尾里用：那时手机号已经预填好，
         * 用户要做的就只剩「收新码 → 填码」，不必再按一次「获取验证码」。
         */
        autoSend: Boolean = false
    ) {
        val login=PhoneLogin().apply {
            this.device=deviceProfile
            log={ line ->
                android.util.Log.i("HGTV-LOGIN",line)
                com.hgtv.star.Diagnostics.trace("HGTV-LOGIN",line)
            }
        }
        val next=TvDialog(activity,"手机号登录")
        // 手机号不按中国大陆的 11 位判断：不同地区长度差别很大（6–15 位都有），
        // 输入框只留够 E.164（15 位）加上空格 / 横线 / `+` 的位置，归一在提交时做。
        val phoneField=LoginForm.field(activity,"手机号","请输入手机号",numeric=true,maxLength=20,phoneStyle=true)
        val codeField=LoginForm.field(activity,"验证码","收到短信后填入",numeric=true,maxLength=8)
        val note=LoginForm.note(activity,"未注册的手机号会自动创建账号。验证码由平台短信发出，我们不会保存它。")
        if(prefillPhone.isNotBlank()) {
            // 预填的是上一次归一后的 E.164（`+8613812345678`）：去掉开头的 `+86`
            // 再放进输入框。这个弹窗没有区号选择器，留着区号会让平台把号码当成
            // 「+86 +86…」，也会让用户看到号码里混进了地区码。
            val local=com.hgtv.star.core.e164Phone(prefillPhone)
                ?.removePrefix("+86")
                ?: prefillPhone.filter { it.isDigit() }
            phoneField.setText(local)
            phoneField.setSelection(local.length)
        }
        // 底部一排三枚：关闭 / 获取验证码 / 登录。后两枚用 `keepOpen`——
        // 发码要留在弹窗里接着填验证码、登录失败也要留在弹窗里改输入，
        // 登录成功时由回调自己关。
        next.button("关闭", icon=R.drawable.ic_menu_close)
        // 「获取验证码」的**防重复**：发码成功后进入倒计时，期间按钮置灰、文案带秒数；
        // 平台自己也有 `retry_time`（实测 60 秒），这里取两者的较大值，避免撞上它的限流
        // （`error_code=7 访问太频繁`）。
        // 冷却器：发码成功后按钮置灰、文案带秒数；网络往返期间也已经置灰，
        // 因此连点两下不会发出两条短信。
        var cooldownUntil=0L
        var countdown: Runnable?=null
        lateinit var send: TvButton
        fun refreshCooldown() {
            val left=((cooldownUntil-System.currentTimeMillis())/1000L).toInt()
            if(left<=0) {
                cooldownUntil=0
                send.isEnabled=true
                send.setLabel("获取验证码")
                return
            }
            send.isEnabled=false
            send.setLabel("$left 秒后可重发")
            // 置灰即不可聚焦：焦点正落在它上面（用户刚按了「获取验证码」）时立刻交给验证码框。
            // 不交接的话焦点会被框架清掉，弹窗里就没有任何控件持有焦点，方向键全部不动
            // ——用户读到的正是「按下没反应」。
            if(send.isFocused) codeField.requestFocus()
            countdown=object: Runnable {
                override fun run()=refreshCooldown()
            }
            main.postDelayed(countdown,1000L)
        }
        fun beginCooldown(seconds: Int) {
            cooldownUntil=System.currentTimeMillis()+seconds*1000L
            countdown?.let { main.removeCallbacks(it) }
            refreshCooldown()
        }
        // 注意：这里**不能**用 `next.button(...)`——那会把按钮挂到弹窗底部那一行，
        // 再 addView 到手机号那一行就会抛「child already has a parent」。
        // 因此直接建 TvButton，由下面 fieldRowWithButton 放进手机号那一行。
        // 与弹窗里其他按钮同档（[ButtonSize.DIALOG]）：弹窗内的控件统一小一圈。
        send=TvButton(activity,"获取验证码",ButtonSize.DIALOG,ButtonTone.SECONDARY,action=send@{
            if(System.currentTimeMillis()<cooldownUntil) return@send
            val phone=normalizePhone(phoneField.text.toString())
            if(phone.isEmpty()) {
                note.text="请先填手机号。"
                phoneField.requestFocus(); return@send
            }
            // 点下去立刻进入冷却（默认 60 秒，与平台自己的 `retry_time` 同档）：
            // 请求还没回来的这段时间里再点也不会发第二次。
            beginCooldown(60)
            note.text="正在向 $phone 发送验证码…"
            worker.execute {
                val result=login.sendCode(phone,register)
                main.post {
                    if(dialog!==next) return@post
                    when(result) {
                        is PhoneLogin.Result.Sent -> {
                            // 平台给的等待时间更长时按它的来（实测 `retry_time` 是 60）。
                            beginCooldown(login.retryAfterSeconds.coerceAtLeast(60))
                            note.text="验证码已发到 $phone，请查看短信。"
                            codeField.requestFocus()
                        }
                        is PhoneLogin.Result.NeedUpsms -> {
                            // 发码这一步先被要求发一条上行短信（官方客户端就在这一步弹 YZ 页面）。
                            // 通过之后平台会认这台设备，届时**重新发一张新码**即可；
                            // 这里没有验证码可拿去登录，所以不能走「带票据重登」那条路。
                            countdown?.let { main.removeCallbacks(it) }
                            cooldownUntil=0
                            send.isEnabled=true
                            send.setLabel("获取验证码")
                            next.dismiss()
                            upsmsOnTv(anchor,login,phone,"",result.number,result.content,onLoggedIn,
                                restart={ phoneLoginForm(anchor,deviceProfile,register,onLoggedIn,prefillPhone=phone) },
                                onUpsmsVerified={
                                    phoneLoginForm(anchor,deviceProfile,register,onLoggedIn,
                                        prefillPhone=phone,autoSend=true)
                                })
                        }
                        is PhoneLogin.Result.Failed -> {
                            // 失败就解除冷却（例如被平台判手机号格式不对），让用户改完能立刻重试。
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
        })
        // `lateinit` 是为了让按钮自己的动作里能把它置灰（见下面请求在途那一句）：
        // 用 `val` 的话，动作 lambda 捕获的是还没初始化的那个变量，编译不过。
        lateinit var submit: DialogItem
        submit=next.button("登录",tone=ButtonTone.PRIMARY,keepOpen=true) {
            val phone=normalizePhone(phoneField.text.toString())
            val code=codeField.text.toString().trim()
            if(phone.isEmpty()) {
                note.text="请先填手机号。"
                phoneField.requestFocus(); return@button
            }
            if(!code.matches(Regex("[0-9]{4,8}"))) {
                note.text="验证码是 4—8 位数字。"
                codeField.requestFocus(); return@button
            }
            note.text="正在登录…"
            // 连点（遥控器上的重复键）会把 `sms_login` 发两遍，而平台的验证码是一次性的：
            // 第二次会把第一张消耗掉。这里**不把按钮置灰**——置灰的控件同时也不可聚焦，
            // 而「验证码」那一行的下键正指向这一行按钮，一旦置灰，用户在请求在途的几秒里
            // 按下键就没有反应。防重复交给 core 的并发闸门（见 PhoneLogin.busy，回「正在处理上一步，请稍候」）。
            worker.execute {
                val result=login.login(phone,code)
                main.post {
                    if(dialog!==next) return@post
                    when(result) {
                        is PhoneLogin.Result.LoggedIn -> { next.dismiss(); onLoggedIn(result.credentials) }
                        is PhoneLogin.Result.NeedUpsms -> {
                            next.dismiss()
                            // 「重新获取验证码」= 把这一页原样再开一次（新的会话、新的验证码），
                            // 与参考实现「明确过期 → 回到获取新验证码并清掉二维码」同一条规则。
                            upsmsOnTv(anchor,login,phone,code,result.number,result.content,onLoggedIn,
                                restart={ phoneLoginForm(anchor,deviceProfile,register,onLoggedIn,prefillPhone=phone) })
                        }
                        is PhoneLogin.Result.Failed -> note.text=result.message
                        else -> note.text="登录失败，请稍后重试。"
                    }
                }
            }
        }
        // 手机号与「获取验证码」同行；验证码那一行右侧留等宽空位，两行的框因此一样长。
        // 按钮宽度按**最长的那条文案**预留（倒计时里是「88 秒后可重发」），
        // 否则倒计时一开始按钮就会把文字截成省略号。量完立刻把文案还原。
        //
        // 注意：改文案**之前**必须先给一个 LayoutParams。`TextView.setText` 会走
        // `checkForRelayout`，那里直接读 `getLayoutParams().width`——还没进过任何父容器的
        // 控件 LayoutParams 是 null，会抛 `NullPointerException`（实测踩过）。
        // 这里先挂一个 WRAP_CONTENT 的量体参数，真正的参数由下面 fieldRowWithButton 覆盖。
        send.layoutParams=LinearLayout.LayoutParams(-2,-2)
        val label=send.text.toString()
        send.setLabel("88 秒后可重发")
        val buttonWidth=send.measure(0,0).let { send.measuredWidth }.coerceAtLeast(dp(120))
        send.setLabel(label)
        next.content.addView(
            LoginForm.fieldRowWithButton(activity,"手机号",phoneField,send,buttonWidth,TvStyle.BUTTON_DIALOG)
        )
        next.content.addView(
            LoginForm.fieldRowWithButton(activity,"验证码",codeField,null,buttonWidth,TvStyle.BUTTON_DIALOG),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) }
        )
        next.content.addView(note,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })
        next.focusContentFirst()
        // 上行短信刚通过：直接把这一页要做的最后一步（发新码）替用户按下去。
        if(autoSend) send.post { if(send.isEnabled) send.performClick() }
        // 手机号已经预填（从「重新获取验证码」回来）时，落点直接给「获取验证码」：
        // 用户要做的下一步就是发新码，不必再按方向键从输入框挪过去。
        if(prefillPhone.isNotBlank()) send.post { if(send.isEnabled) send.requestFocus() }
        // 回车 / 确认键**必须自己接管**。
        //
        // 单行 `EditText` 的 `shouldAdvanceFocusOnEnter()` 恒为 true，框架在 `TextView.onKeyUp`
        // 里会拿 `nextFocusDownId` 去 `requestFocus`；目标存在、可见、focusable 但这一轮还没量出
        // 尺寸（或刚被置灰）时，`requestFocus` 返回 false，框架直接抛
        // 「focus search returned a view that wasn't able to take focus!」——就是线上那次崩溃。
        // 监听器返回 true 之后，`onKeyUp` 在调用 `focusSearch` 之前就返回了，这条路不再经过。
        // 注意：**同一个按键会进来两次**——`doKeyDown` 里先问一次（返回 true 后记下
        // `enterDown`），`onKeyUp` 再问一次。因此这里必须只在抬起（或输入法的 action 回调，
        // 那时 event 为 null）时执行动作；按下只吞掉，否则「登录 / 导入」会被执行两遍。
        phoneField.setOnEditorActionListener { _,_,event ->
            if(event?.action==KeyEvent.ACTION_DOWN) true
            else { codeField.requestFocus(); true }
        }
        codeField.setOnEditorActionListener { _,_,event ->
            if(event?.action==KeyEvent.ACTION_DOWN) true
            else { submit.performClick(); true }
        }
        // 焦点链：手机号 →（右）获取验证码、（下）验证码 →（下）底部按钮行；上键原路返回。
        //
        // 这里有一条必须守住的规则：**`nextFocusDownId` 绝不能指向可能被置灰的控件**。
        // 冷却期间「获取验证码」是 `isEnabled=false`（同时不可聚焦），而框架拿到显式指定
        // 的下键目标时**不再做几何搜索**——于是「焦点在验证码上、按下没反应」。
        // 所以手机号与验证码两行的「下」都直接指向下面那一行（验证码框 / 登录按钮），
        // 「获取验证码」只从手机号按右键进。
        phoneField.nextFocusRightId=send.id
        phoneField.nextFocusDownId=codeField.id
        send.nextFocusUpId=phoneField.id
        send.nextFocusDownId=codeField.id
        codeField.nextFocusUpId=phoneField.id
        codeField.nextFocusDownId=submit.id
        submit.nextFocusUpId=codeField.id
        // 必须走 [open]：它是唯一把 `dialog` 指向新弹窗的地方，
        // 而下面所有结果回调都用 `dialog!==next` 判「这个弹窗是不是当前那个」——
        // 直接 `next.show()` 会让那个判定永远为真，回调全部被丢掉，
        // 表现出来就是「一直显示正在登录」（实测踩过）。
        open(next,anchor)
        // 弹窗关掉时停掉倒计时，免得它继续跑在已经不可见的按钮上。
        next.setOnDismissExtra { countdown?.let { main.removeCallbacks(it) } }
    }
    /**
     * 平台要求上行短信：电视上直接出**短信深链的二维码**，手机一扫就跳系统短信 App。
     *
     * 这一条不走局域网、也不需要电视开端口：二维码里就是
     * `sms:<收件号码>?body=<短信内容>`，扫码器（相机 / 系统扫一扫）认出 `sms:` 协议后
     * 直接打开短信 App，收件人与正文都已填好，用户按发送即可。
     *
     * 好处是**用户不用抄号码、也不会抄错**；代价是能不能识别 `sms:` 取决于手机的扫码器
     * （系统相机与主流扫码 App 都认），因此弹窗里同时把号码与内容写出来兜底。
     */
    internal fun upsmsOnTv(
        anchor: View?,
        login: PhoneLogin,
        phone: String,
        code: String,
        number: String,
        content: String,
        onLoggedIn: (AccountCredentials)->Unit,
        restart: ()->Unit,
        note: String = "",
        /**
         * 「发码那一步就被要求发短信」时的收尾：平台认了这条短信之后**重新发一张新码**，
         * 而不是带票据重登——那一步根本没有可用的验证码（见 `PhoneLogin.mfaFromSendCode`）。
         */
        onUpsmsVerified: (()->Unit)? = null
    ) {
        android.util.Log.i("HGTV-LOGIN","打开上行短信页 number=$number content=$content fromSendCode=${login.mfaFromSendCode}")
        val next=TvDialog(activity,"还需要一条短信")
        val deepLink="sms:$number?body="+java.net.URLEncoder.encode(content,"UTF-8")
        // 上行短信这一页除了扫码，还要把「收件号码 / 短信内容」原样写出来兜底
        // （不是所有扫码器都认 `sms:`）。两段内容放进两条凹陷槽，与上面三步说明分开。
        scanBody(
            (if(note.isBlank()) "" else "$note\n") +
            "用手机相机扫下面的码，会自动打开短信 App，收件人与正文都已填好\n按发送即可，发完什么都不用做\n电视会自己向平台确认，认到了就直接登录",
            deepLink,
            next.content
        )
        next.content.addView(smsFallback(number,content),LinearLayout.LayoutParams(-1,-2).apply {
            topMargin=dp(TvStyle.GAP_BLOCK)
        })
        val status=label(if(note.isBlank()) "正在向平台确认这条短信…" else note)
        next.content.addView(
            status,
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) }
        )
        // 这里**没有**「我已发送」这种按钮：确认是电视自己在做的（见下面 [poll]），
        // 用户唯一的动作是在手机上把那条短信发出去。要他再回来按一下，只会让平台的
        // 「等待确认」多出几十秒空档，反而把挑战拖成「长时间未操作，验证已过期」。
        next.button("取消", icon=R.drawable.ic_menu_close)
        open(next,anchor)
        // **从页面出现起就轮询**。
        //
        // 实测（用户报的现象 + 平台返回）：短信比用户的动作快得多——他发完短信、从手机走回
        // 电视的这几十秒里，平台那边已经回过 `9001 长时间未操作，验证已过期` 了，而**短信其实
        // 早就被平台记下、设备也拿到了信任**（随后重新获取验证码再登录一次，就不再要求发短信）。
        // 这句「已过期」有两种来源，两条都要堵：① 客户端一直没跟平台说话，挑战被判超时——
        // 所以页面出现后定时问一次；② 确认请求的参数与平台对不上，平台把
        // 「找不到这次验证」也报成这一句——所以 [PhoneLogin] 严格按 H5 的 N 参数发请求，
        // 不再拿已经被 `sms_login` 消费的 `mobile_ticket` 去顶 `verify_ticket`。
        val poll=object: Runnable {
            override fun run() {
                if(dialog!==next) {
                    android.util.Log.i("HGTV-LOGIN","轮询停止：弹窗已不是当前这个")
                    return
                }
                worker.execute {
                    val verified=runCatching { login.verifyUpsms() }
                        .getOrElse { PhoneLogin.UpsmsResult.Failed(it.message.orEmpty().ifBlank { "请稍后重试。" }) }
                    android.util.Log.i("HGTV-LOGIN","轮询结果 → ${verified::class.java.simpleName}")
                    main.post {
                        if(dialog!==next) return@post
                        when(verified) {
                            is PhoneLogin.UpsmsResult.Pending -> {
                                // `note` 有内容说明这次不是「还没看到短信」，而是平台回了别的东西
                                // （网络抖动、未知错误码）。如实摆出来，别一直显示「还没看到」骗用户。
                                status.text=verified.note.ifBlank { "平台还没看到这条短信…（正在重试）" }
                                main.postDelayed(this, UPSMS_POLL_MS)
                            }
                            is PhoneLogin.UpsmsResult.Ticket -> {
                                if(login.mfaFromSendCode) {
                                    // 挑战来自发码那一步：这里没有验证码可登，认了就直接重发新码。
                                    next.dismiss()
                                    (onUpsmsVerified ?: restart)()
                                } else {
                                    confirmUpsms(next,login,phone,code,verified.value,onLoggedIn,restart,anchor)
                                }
                            }
                            is PhoneLogin.UpsmsResult.Failed -> {
                                // **不要**再拿同一张验证码重发 `sms_login`。
                                //
                                // 平台的验证码是一次性的：走到这一页时它已经被第一次 `sms_login`
                                // 消费掉了，再发一次只会拿到「验证码已失效」，而这句会被当成
                                // 「挑战结束的原因」展示给用户——看起来就像平台莫名其妙作废了验证码。
                                // 那条上行短信通常已经让平台记下了设备信任，所以下一步是**重新发一张
                                // 新码**（与官方客户端 `twice verify succeed, requestSendCode again`
                                // 同一个动作），而不是重试旧码。
                                next.dismiss()
                                needNewCode(verified.message,anchor,restart,login.lastDiagnostic)
                            }
                        }
                    }
                }
            }
        }
        // 关掉这一页（取消、返回、换页）就把在途的那一拍摘掉：页面上已经没东西可显示了。
        // 正在执行的那个请求会自己在 `dialog!==next` 那一关收手。
        next.setOnDismissExtra { main.removeCallbacks(poll) }
        main.postDelayed(poll,UPSMS_FIRST_POLL_MS)
    }
    /** 平台认了这条上行短信：带 ticket 重发一次短信登录，成功就交给 [onLoggedIn]。 */
    private fun confirmUpsms(
        page: TvDialog,
        login: PhoneLogin,
        phone: String,
        code: String,
        ticket: String,
        onLoggedIn: (AccountCredentials)->Unit,
        restart: ()->Unit,
        anchor: View?
    ) {
        worker.execute {
            val result=login.login(phone,code,ticket)
            main.post {
                if(dialog!==page) return@post
                page.dismiss()
                when(result) {
                    is PhoneLogin.Result.LoggedIn -> onLoggedIn(result.credentials)
                    // 带 ticket 重发短信登录仍失败：这张验证码 / 这次验证在平台那边已经不认了，
                    // 唯一有用的下一步就是重新获取验证码（重试同一张只会重复同一个失败）。
                    is PhoneLogin.Result.Failed -> needNewCode(result.message,anchor,restart)
                    // 带票据重登之后平台**又**要一次上行短信：别再弹一次「发短信」页面
                    // （用户会以为要发第二条），如实说明并让他重新获取验证码。
                    is PhoneLogin.Result.NeedUpsms -> needNewCode(
                        "平台仍要求二次验证，请重新获取验证码后再试一次。",anchor,restart)
                    else -> info("登录失败","验证未完成，请稍后重试。",anchor)
                }
            }
        }
    }
    /**
     * 「这次短信验证已经结束，得重新获取验证码」：把原因写清楚，并给一个直接回到登录页的入口。
     *
     * 与参考实现同一条规则：平台明确结束这次验证之后**不再重试、也不重复发短信**，
     * 用户只能重新走一遍「获取验证码 → 填码」。
     *
     * [raw] 是平台最近一次的原文（[PhoneLogin.lastDiagnostic]，已脱敏截断）。上行短信这一条
     * 只有真机 + 真短信能走通，字段一旦对不上，唯一能定位的就是「平台到底回了什么」——
     * 所以失败时把它一并摆出来。
     */
    private fun needNewCode(reason: String,anchor: View?,restart: ()->Unit,raw: String = "") {
        val next=TvDialog(activity,"需要重新获取验证码")
        next.message(reason)
        if(raw.isNotBlank()) next.message("平台原文（已省略票据）：$raw",12f,TvStyle.muted)
        next.message(
            "请在登录页重新获取验证码后再试一次（手机号已经帮你填好，只需填新的验证码）。",
            14f,TvStyle.muted
        )
        next.button("关闭", icon=R.drawable.ic_menu_close)
        next.button("重新获取验证码",tone=ButtonTone.PRIMARY, icon=R.drawable.ic_menu_restore) { restart() }
        open(next,anchor)
    }
    fun phoneLogin(
        anchor: View?,
        deviceProfile: Map<String,String>,
        register: (Map<String,String>)->com.hgtv.star.core.DeviceRegister.Registered?,
        onLoggedIn: (AccountCredentials)->Unit
    ) {
        val address=lanAddress()
        if(address==null) {
            info("手机号登录","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return
        }
        val next=TvDialog(activity,"手机号登录")
        next.button("关闭")
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val login=PhoneLogin().apply {
                    this.device=deviceProfile
                    log={ line ->
                android.util.Log.i("HGTV-LOGIN",line)
                com.hgtv.star.Diagnostics.trace("HGTV-LOGIN",line)
            }
                }
                val session=PhoneLoginServer(address,login,
                    { credentials -> main.post { if(dialog===next) next.dismiss(); onLoggedIn(credentials) } },
                    { main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                session.onRegister(register)
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("用手机相机扫下面的码，打开电视发的一次性登录页\n在手机上填手机号、收短信、填验证码\n平台若要求再发一条短信，页面会给出号码与内容\n手机与电视要在同一个局域网，最长 10 分钟",session.url,next.content)
                }
            } catch(e: Exception) {
                com.hgtv.star.Diagnostics.warn("HGTV-TOOLS","login dialog failed: ${e.javaClass.simpleName}: ${e.message}",e)
                main.post { if(dialog===next) status.text="无法开启登录页面，请检查网络后重试。" }
            }
        }
    }
    /**
     * 导入账号参数：手机扫码打开一个一次性页面，上传 App 的 `cookieStore.xml` 或粘贴整段 Cookie。
     *
     * 与「手机备份与恢复」同一套局域网约束（随机地址、限时、用完即关），
     * 区别是这里收的是**凭据文本**，由调用方解析与校验（见 `MainActivity.importAccount`）。
     */
    fun accountImport(anchor: View?,onImport: (String)->Unit,onLocalFile: ()->Unit) {
        // 先让用户挑方式：本机文件（电视上已存了 cookieStore.xml）、手机扫码上传，
        // 或者直接手填 sessionid。三者最终都归一到「一段文本」，
        // 由调用方解析（见 `MainActivity.parseCredentials`）。
        val pick=TvDialog(activity,"导入账号参数")
        pick.message("需要官方 App 的 cookieStore.xml，或者整段 Cookie；也可以只填 sessionid。\n\n三种方式：\n· 本机文件 —— 电视上已经有这个文件时用（U 盘 / 文件管理应用放进来的）；\n· 手机扫码 —— 手机与电视连同一局域网，用手机把文件或口令传过来；\n· 输入 sessionid —— 只拿到那一串时直接手填（其余字段平台不校验）。")
        pick.button("取消", icon=R.drawable.ic_menu_close) {}
        pick.button("手机扫码", icon=R.drawable.ic_menu_search) { phoneUpload(anchor,onImport) }
        pick.button("本机文件", icon=R.drawable.ic_menu_info) { onLocalFile() }
        // 弹窗里所有按钮都用常态档：PRIMARY 是「常亮的亮面板」，在一排按钮里看起来
        // 就像「一直处于焦点」——这一行有四个按钮，突出其中一个只会让人误以为焦点已经在它上面。
        pick.button("输入 sessionid", icon=R.drawable.ic_menu_key) { sessionIdEntry(anchor,onImport) }
        open(pick,anchor)
    }
    /**
     * 手填 `sessionid`。
     *
     * 单独留这一条路是因为**实测服务端只认 `sessionid`**（其余 13 个字段加上去结果一样，
     * 见 `AccountCredentialsParser.COOKIE_KEYS` 的注释），所以只要用户能拿到那一串就够了——
     * 不用去翻 `cookieStore.xml`。
     *
     * 输入框复用搜索页那一套外观（凹槽 + 胶囊 + 焦点加深），并且**只收 `sessionid` 的值**：
     * 粘贴整段 Cookie 或整行 `sessionid=…` 也认（[normalizeSessionId] 会剥掉前缀与空白）。
     */
    private fun sessionIdEntry(anchor: View?,onImport: (String)->Unit) {
        val next=TvDialog(activity,"输入 sessionid")
        next.message("填官方 App 登录态里的 sessionid：**32 位十六进制**，形如 cf98cf3cd41f0b56188dd3f2226a10c4。\n\n" +
            "在 cookieStore.xml 里搜 sessionid 会看到 19 条同名的（不同网址下各一份），值都一样，挑任意一条即可——" +
            "但**不要粘那一长串十六进制**（那是 App 的序列化数据）。\n" +
            "整行 <string …>、整段 Cookie、或直接填那 32 位，这三种都认。")
        // 输入框占满整行、高度与常规按钮同档（56dp + 柔光带）：只在正文里放一行
        // `wrap_content` 的 EditText 会被压成一条细槽，字都挤在里面。
        val input=sessionIdField()
        next.content.addView(input,LinearLayout.LayoutParams(-1,dp(SESSION_FIELD_HEIGHT+2*TvStyle.SOFT_BAND))
            .apply { topMargin=dp(TvStyle.GAP_BLOCK) })
        // 输入框下面一行小字：把「只用填这一项」这件事说清楚，省得用户去找整个文件。
        next.content.addView(
            label("服务端只校验这一项，填对就能显示账号信息。",12f),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(TvStyle.GAP_TIGHT) }
        )
        val cancel=next.button("取消", icon=R.drawable.ic_menu_close) {}
        // 同上：弹窗里按钮一律常态。`keepOpen` 让它点完不关（校验失败时还要留在框里）。
        val submit=next.button("导入",keepOpen=true, icon=R.drawable.ic_menu_check) {
            val value=normalizeSessionId(input.text.toString())
            if(value.isEmpty()) { input.error="请填 sessionid"; return@button }
            next.dismiss()
            onImport(value)
        }
        // 回车 / 确认键自己接管（原因同手机号登录页）：框架的自动移焦在目标量不出尺寸时
        // 会抛「focus search returned a view that wasn't able to take focus!」。
        // 这里直接把「完成」当成「导入」。
        input.setOnEditorActionListener { _,_,event ->
            if(event?.action==KeyEvent.ACTION_DOWN) true
            else { submit.performClick(); true }
        }
        // 焦点链：输入框按下键直接到「导入」，两个按钮按上键都回输入框。
        // 弹窗只在内容区溢出时才自动接线（见 `TvDialog.wireContentFocus`），这一页内容不长，
        // 因此这两条要自己设，否则焦点会困在输入框里出不来。
        input.nextFocusDownId=submit.id
        cancel.nextFocusUpId=input.id
        submit.nextFocusUpId=input.id
        // 焦点交给 [TvDialog] 自己的规则：**第一枚按钮**（也就是「取消」）。
        // 不主动把焦点塞进输入框——那样一打开弹窗就在输入框里闪光标，
        // 用户按左右键还会被输入框吃掉，想按「取消」得先往下走。
        open(next,anchor)
    }
    /** 手填 sessionid 的输入框：外观与搜索页那一条一致（凹陷槽 + 胶囊圆角 + 焦点加深）。 */
    private fun sessionIdField(): EditText {
        val band=dp(TvStyle.SOFT_BAND)
        val view=EditText(activity).apply {
            id=View.generateViewId()
            hint="在此粘贴 sessionid，或整段 Cookie"
            // 字号比按钮正文大一档（20sp）：这一行是弹窗里唯一要读的东西，
            // 32 位十六进制串在小字下很难核对。
            designTextSize(SESSION_FIELD_TEXT)
            setTextColor(TvStyle.text); setHintTextColor(TvStyle.muted)
            isSingleLine=true; maxLines=1
            // START 同上；输入框不省略时文字会横向滚动到光标处，末尾照样看得见。
            ellipsizeCompat(android.text.TextUtils.TruncateAt.START, legacy = null)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters=arrayOf(android.text.InputFilter.LengthFilter(512))
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            includeFontPadding=false
            // 内边距按常规档按钮（28dp）+ 柔光带：槽里左右各留出投影的位置，文字才不会贴着边。
            setPadding(dp(TvStyle.BUTTON_PAD_NORMAL)+band,band,dp(TvStyle.BUTTON_PAD_NORMAL)+band,band)
            background=TvStyle.soft(activity,TvStyle.well,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,SoftStyle.INSET,ring=TvStyle.outline,ringWidth=1)
            setOnFocusChangeListener { focused,_ ->
                val on=focused.hasFocus()
                focused.background=TvStyle.soft(activity,TvStyle.well,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,SoftStyle.INSET,
                    ring=if(on) TvStyle.focusLine else TvStyle.outline,
                    ringWidth=if(on) TvStyle.RING else 1,depth=if(on) 1.6f else 1.1f)
            }
        }
        return view
    }
    /**
     * 手机号归一：只留数字与**开头的 `+`**。
     *
     * **不判断长度、也不判断「是不是 1 开头」**：中国大陆是 11 位，
     * 但香港 8 位、澳门 8 位、日本 10–11 位、美国 10 位、部分地区短到 6 位——
     * 按中国大陆的形状去卡，其他地区的号码会被本地拦下来，连请求都发不出去。
     * 这里只做「去掉空格 / 横线 / 括号」这一件事，长度交给平台自己判
     * （它回的错误文案会原样显示在弹窗里）。
     *
     * 开头的 `+` 必须留着：`+1 415 555 1234` 去掉加号之后是「11 位、1 开头」，
     * 与一个中国大陆号码长得一模一样，core 那边就分不清该补 +86 还是照用 +1。
     */
    internal fun normalizePhone(raw: String): String {
        val text = raw.trim()
        val digits = text.filter { it.isDigit() }
        return if (text.startsWith("+")) "+$digits" else digits
    }

    /**
     * 把用户填的东西归一成**纯 sessionid 值**。
     *
     * 实际归一交给 [AccountCredentialsParser.sessionIdOf]（它认得整行 `<string>`、
     * 十六进制序列化流、整段 Cookie 与纯值），这里只做一次形状检查——
     * 用户最常踩的坑是把 `cookieStore.xml` 里那一长串十六进制粘进来，
     * 那会变成一个**非法 sessionid**：平台不报错，只把请求降级成匿名设备。
     */
    private fun normalizeSessionId(raw: String): String {
        val value=AccountCredentialsParser.sessionIdOf(raw)
        return if(AccountCredentialsParser.looksLikeSessionId(value)) value else ""
    }
    /** 手机扫码上传：一次性局域网页面，收 `cookieStore.xml` 或整段 Cookie。 */
    private fun phoneUpload(anchor: View?,onImport: (String)->Unit) {
        val address=lanAddress()
        if(address==null) {
            info("导入账号参数","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return
        }
        val next=TvDialog(activity,"手机上传账号参数")
        next.button("关闭")
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val session=AccountImportServer(address,{ text -> main.post { if(dialog===next) { next.dismiss(); onImport(text) } } },
                    { main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("用手机相机扫下面的码，打开电视发的一次性页面上传官方 App 的 cookieStore.xml\n也可以直接粘贴整段 Cookie\n只保存在电视本机，手机与电视要在同一个局域网，最长 5 分钟",session.url,next.content)
                }
            } catch(e: Exception) {
                com.hgtv.star.Diagnostics.warn("HGTV-TOOLS","upload dialog failed: ${e.javaClass.simpleName}: ${e.message}",e)
                main.post { if(dialog===next) status.text="无法开启上传页面，请检查网络后重试。" }
            }
        }
    }
    fun libraryTransfer(backup: String,anchor: View?,restore: (BackupData,Boolean)->Unit) {
        val address=lanAddress()
        if(address==null) {
            info("备份与恢复","请让电视和手机连接同一局域网后重试。",anchor); return
        }
        val next=TvDialog(activity,"手机备份与恢复")
        next.button("关闭")
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val session=LibraryTransferServer(address,backup,{ data -> main.post {
                    if(dialog!==next) return@post
                    next.dismiss()
                    val confirm=TvDialog(activity,"确认恢复本机记录")
                    confirm.message("备份含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.searches.size} 条搜索、${data.later.size} 部稍后观看、${data.hidden.size} 部已隐藏的剧集。\n\n收藏合并，观看位置以较新的记录为准；已有收藏优先保留（最多 500 部），历史保留最近 200 条。")
                    // 开关用全站统一的按钮：选中态是「按进去」的凹槽（不带颜色），
                    // 系统 CheckBox 的勾选色是自带的一套，与「选框不带颜色」的规则冲突。
                    var restoreSettings=false
                    lateinit var settings: ToggleChip
                    settings=ToggleChip(activity,"同时恢复播放设置和内容分类 · 关",false) {
                        restoreSettings=!restoreSettings
                        settings.setActive(restoreSettings)
                        settings.setLabel("同时恢复播放设置和内容分类 · ${if(restoreSettings) "开" else "关"}")
                    }
                    confirm.content.addView(settings,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })
                    confirm.button("取消", icon=R.drawable.ic_menu_close) {}
                    confirm.button("合并恢复", icon=R.drawable.ic_menu_restore) { restore(data,restoreSettings) }
                    open(confirm,anchor)
                } },{ main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("用手机相机扫下面的码，打开电视发的一次性页面\n在手机上下载备份，或选一份备份文件发回电视\n恢复要在电视上确认，手机与电视要在同一个局域网，最长 5 分钟",session.url,next.content)
                }
            } catch(e: Exception) {
                com.hgtv.star.Diagnostics.warn("HGTV-TOOLS","transfer dialog failed: ${e.javaClass.simpleName}: ${e.message}",e)
                main.post { if(dialog===next) status.text="无法开启备份传输，请检查网络后重试。" }
            }
        }
    }
}
