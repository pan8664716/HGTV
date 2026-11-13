// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hgtv.star.core.AccountCredentialsParser
import com.hgtv.star.core.AccountImportServer
import com.hgtv.star.shared.compat.activeNetworkOrNull
import com.hgtv.star.shared.compat.ellipsizeCompat
import com.hgtv.star.shared.ui.SoftStyle
import java.net.Inet4Address

fun TvTools.toast(message: String) {
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

internal fun TvTools.clearToast() {
    main.removeCallbacks(hideToast)
    val view=toastView ?: return
    toastView=null
    view.animate().cancel()
    (view.parent as? android.view.ViewGroup)?.removeView(view)
}

internal fun TvTools.qr(url: String): FrameLayout {
    val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,360,360)
    val pixels=IntArray(360*360) { i->if(bits[i%360,i/360]) Color.BLACK else Color.WHITE }
    val code=ImageView(activity).apply {
        setImageBitmap(Bitmap.createBitmap(pixels,360,360,Bitmap.Config.ARGB_8888))
        scaleType=ImageView.ScaleType.FIT_CENTER
        contentDescription="二维码"
    }
    val inset=dp(TvStyle.SOFT_BAND)+dp(4)
    return FrameLayout(activity).apply {
        background=TvStyle.soft(activity,Color.WHITE,TvStyle.RADIUS_CARD,TvStyle.SOFT_BAND,SoftStyle.RAISED,depth=1.15f)
        setPadding(inset,inset,inset,inset)
        addView(code,FrameLayout.LayoutParams(-1,-1))
    }
}

fun TvTools.lanAddress(): String? {
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

internal fun TvTools.smsFallback(number: String,content: String): LinearLayout {
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

fun TvTools.accountImport(anchor: View?,onImport: (String)->Unit,onLocalFile: ()->Unit) {
    val pick=TvDialog(activity,"导入账号参数")
    pick.message("需要官方 App 的 cookieStore.xml，或者整段 Cookie；也可以只填 sessionid。\n\n三种方式：\n· 本机文件 —— 电视上已经有这个文件时用（U 盘 / 文件管理应用放进来的）；\n· 手机扫码 —— 手机与电视连同一局域网，用手机把文件或口令传过来；\n· 输入 sessionid —— 只拿到那一串时直接手填（其余字段平台不校验）。")
    pick.button("取消", icon=R.drawable.ic_menu_close) {}
    pick.button("手机扫码", icon=R.drawable.ic_menu_search) { phoneUpload(anchor,onImport) }
    pick.button("本机文件", icon=R.drawable.ic_menu_info) { onLocalFile() }
    pick.button("输入 sessionid", icon=R.drawable.ic_menu_key) { sessionIdEntry(anchor,onImport) }
    open(pick,anchor)
}

private fun TvTools.sessionIdEntry(anchor: View?,onImport: (String)->Unit) {
    val next=TvDialog(activity,"输入 sessionid")
    next.message("填官方 App 登录态里的 sessionid：**32 位十六进制**，形如 cf98cf3cd41f0b56188dd3f2226a10c4。\n\n" +
        "在 cookieStore.xml 里搜 sessionid 会看到 19 条同名的（不同网址下各一份），值都一样，挑任意一条即可——" +
        "但**不要粘那一长串十六进制**（那是 App 的序列化数据）。\n" +
        "整行 <string …>、整段 Cookie、或直接填那 32 位，这三种都认。")
    val input=sessionIdField()
    next.content.addView(input,LinearLayout.LayoutParams(-1,dp(SESSION_FIELD_HEIGHT+2*TvStyle.SOFT_BAND))
        .apply { topMargin=dp(TvStyle.GAP_BLOCK) })
    next.content.addView(
        label("服务端只校验这一项，填对就能显示账号信息。",12f),
        LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(TvStyle.GAP_TIGHT) }
    )
    val cancel=next.button("取消", icon=R.drawable.ic_menu_close) {}
    val submit=next.button("导入",keepOpen=true, icon=R.drawable.ic_menu_check) {
        val value=normalizeSessionId(input.text.toString())
        if(value.isEmpty()) { input.error="请填 sessionid"; return@button }
        next.dismiss()
        onImport(value)
    }
    input.setOnEditorActionListener { _,_,event ->
        if(event?.action==KeyEvent.ACTION_DOWN) true
        else { submit.performClick(); true }
    }
    input.nextFocusDownId=submit.id
    cancel.nextFocusUpId=input.id
    submit.nextFocusUpId=input.id
    open(next,anchor)
}

private fun TvTools.sessionIdField(): EditText {
    val band=dp(TvStyle.SOFT_BAND)
    return EditText(activity).apply {
        id=View.generateViewId()
        hint="在此粘贴 sessionid，或整段 Cookie"
        designTextSize(SESSION_FIELD_TEXT)
        setTextColor(TvStyle.text); setHintTextColor(TvStyle.muted)
        isSingleLine=true; maxLines=1
        ellipsizeCompat(android.text.TextUtils.TruncateAt.START, legacy = null)
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters=arrayOf(android.text.InputFilter.LengthFilter(512))
        imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        includeFontPadding=false
        setPadding(dp(TvStyle.BUTTON_PAD_NORMAL)+band,band,dp(TvStyle.BUTTON_PAD_NORMAL)+band,band)
        background=TvStyle.soft(activity,TvStyle.well,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,SoftStyle.INSET,ring=TvStyle.outline,ringWidth=1)
        setOnFocusChangeListener { focused,_ ->
            val on=focused.hasFocus()
            focused.background=TvStyle.soft(activity,TvStyle.well,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,SoftStyle.INSET,
                ring=if(on) TvStyle.focusLine else TvStyle.outline,
                ringWidth=if(on) TvStyle.RING else 1,depth=if(on) 1.6f else 1.1f)
        }
    }
}

fun TvTools.normalizePhone(raw: String): String {
    val text = raw.trim()
    val digits = text.filter { it.isDigit() }
    return if (text.startsWith("+")) "+$digits" else digits
}

private fun TvTools.normalizeSessionId(raw: String): String {
    val value=AccountCredentialsParser.sessionIdOf(raw)
    return if(AccountCredentialsParser.looksLikeSessionId(value)) value else ""
}

private fun TvTools.phoneUpload(anchor: View?,onImport: (String)->Unit) {
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
            com.hgtv.star.shared.debug.Diagnostics.warn("HGTV-TOOLS","upload dialog failed: ${e.javaClass.simpleName}: ${e.message}",e)
            main.post { if(dialog===next) status.text="无法开启上传页面，请检查网络后重试。" }
        }
    }
}
