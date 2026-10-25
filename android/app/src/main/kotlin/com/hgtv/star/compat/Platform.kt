// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.compat

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat

/**
 * Android 4.4（API 19）到现在的平台差异都收在这个文件里。
 *
 * 4.4 缺的东西分三类，处理方式也分三类：
 * 1. **查系统服务**：`getSystemService(Class)` 是 API 23 才有的，统一改回按名字取；
 * 2. **网络状态**：`activeNetwork` / `getNetworkCapabilities` 是 API 21/23，网络回调也是 API 21+，
 *    这里按版本分三条路（24+ 默认回调、21–23 显式请求、19–20 广播）；
 * 3. **矢量图**：minSdk 19 时 AGP 会为每个 vector 额外生成 PNG 兜底，4.4 上资源解析
 *    命中的是位图而不是 `anydpi-v21` 的 XML——因此这里先让 `Resources` 自己挑，
 *    只有确实拿到 XML 时才交给 [VectorDrawableCompat]。
 */
internal fun Context.activityManager(): ActivityManager? =
    getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

internal fun Context.connectivityManager(): ConnectivityManager? =
    getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

/**
 * 当前默认网络。
 *
 * `getActiveNetwork()` 是 **API 23** 才有的（API 21/22 只有 `getActiveNetworkInfo()`）。
 * 因此版本判断必须留在**这个函数内部**：早先把它拆成「带 @TargetApi 的裸调用 + 另一个私有包装」，
 * 结果 `TvTools.lanAddress()` 直接调了裸调用，在 Android 5.x 上抛
 * `NoSuchMethodError: No virtual method getActiveNetwork()`（线上崩溃）。
 * 现在只有这一个入口，调用方不需要、也不应该自己再判版本。
 */
@android.annotation.TargetApi(23)
internal fun ConnectivityManager.activeNetworkOrNull(): Network? =
    if (Build.VERSION.SDK_INT >= 23) activeNetwork else null

private fun ConnectivityManager.capabilitiesOf(network: Network?): NetworkCapabilities? =
    if (Build.VERSION.SDK_INT >= 21 && network != null) getNetworkCapabilities(network) else null

/**
 * 是否有可用网络。
 *
 * 23+ 看 `NET_CAPABILITY_INTERNET`；19–22 退回 `activeNetworkInfo`。
 *
 * 21/22 也必须走 `activeNetworkInfo`：`activeNetworkOrNull()` 在 23 以下恒为 null，
 * 早先按「21+」分支写，等于在 5.0 / 5.1 上永远判成「没网」——预取被取消、
 * 播放出错后的自动重连也不会安排。
 */
internal fun ConnectivityManager.hasInternet(): Boolean {
    if (Build.VERSION.SDK_INT >= 23) {
        val capabilities = capabilitiesOf(activeNetworkOrNull()) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    @Suppress("DEPRECATION")
    val info = activeNetworkInfo ?: return false
    @Suppress("DEPRECATION")
    return info.isConnected
}

/**
 * 当前网络是否按流量计费。
 *
 * `isActiveNetworkMetered` 是 API 16 起就有，电视上直接用它即可（4.4 也覆盖）。
 * 原函数在 `return` 后面还留了一段永远执行不到的 `activeNetworkInfo` 判断——
 * 那是早先两种写法合并时留下的死代码，已删掉。
 */
internal fun ConnectivityManager.isMeteredConnection(): Boolean = isActiveNetworkMetered

/**
 * 可以撤掉的注册（网络回调 / 广播接收器）。
 *
 * **不能用 `AutoCloseable`**：它是 **API 19** 才加进 Android 的类。线上那台把
 * `ro.build.version.sdk` 报成 19、框架其实是 4.2 的盒子（见 [hasIsAttachedToWindow]）
 * 上它根本不存在，只要它出现在 `MainActivity` 的字段类型里，类加载就抛
 * `NoClassDefFoundError: java.lang.AutoCloseable`。
 * 一个只有 `cancel()` 的本地接口 API 1 起就能用，代价是调用方写 `.cancel()`。
 */
internal fun interface Cancellable {
    fun cancel()
}

/** 网络变化监听：关掉时把注册的东西原样撤掉。 */
internal object NetworkWatcher {
    fun watch(context: Context, onChange: () -> Unit): Cancellable {
        val manager = context.connectivityManager() ?: return Cancellable { }
        return when {
            Build.VERSION.SDK_INT >= 24 -> Api24.watch(manager, onChange)
            Build.VERSION.SDK_INT >= 21 -> Api21.watch(manager, onChange)
            else -> Legacy.watch(context, onChange)
        }
    }

    /** API 24+：默认网络回调，最省事也最准。 */
    @android.annotation.TargetApi(24)
    private object Api24 {
        fun watch(manager: ConnectivityManager, onChange: () -> Unit): Cancellable {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange()
                override fun onLost(network: Network) = onChange()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onChange()
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = onChange()
            }
            manager.registerDefaultNetworkCallback(callback)
            return Cancellable { runCatching { manager.unregisterNetworkCallback(callback) } }
        }
    }

    /** API 21–23：没有 `registerDefaultNetworkCallback`，用一条显式的 Internet 请求代替。 */
    @android.annotation.TargetApi(21)
    private object Api21 {
        fun watch(manager: ConnectivityManager, onChange: () -> Unit): Cancellable {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange()
                override fun onLost(network: Network) = onChange()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onChange()
            }
            manager.registerNetworkCallback(request, callback)
            return Cancellable { runCatching { manager.unregisterNetworkCallback(callback) } }
        }
    }

    /** API 19–20：没有 NetworkCallback，只能听系统广播。 */
    private object Legacy {
        fun watch(context: Context, onChange: () -> Unit): Cancellable {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) = onChange()
            }
            context.registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            return Cancellable { runCatching { context.unregisterReceiver(receiver) } }
        }
    }
}

/**
 * 滚动位置回调。
 *
 * `setOnScrollChangeListener` 是 API 23 才有的；4.4–5.1 用 `ViewTreeObserver` 的滚动通知代替。
 * 后者在布局变化时也会触发，因此记一个上次的 y，值没变就不回调——语义与 23+ 一致。
 */
internal fun ScrollView.onScrollYChanged(action: (Int) -> Unit) {
    if (Build.VERSION.SDK_INT >= 23) {
        setOnScrollChangeListener { _, _, y, _, _ -> action(y) }
        return
    }
    var last = scrollY
    viewTreeObserver.addOnScrollChangedListener {
        val y = scrollY
        if (y != last) { last = y; action(y) }
    }
}

/**
 * 圆角矩形。
 *
 * `Canvas.drawRoundRect(l,t,r,b,rx,ry,paint)` 是 API 21 才有的重载，4.4 上只有 `RectF` 版本。
 * 统一走 `RectF` 版本，两条路都只用 API 1 的接口。
 *
 * 矩形对象**预分配并复用**：这里的调用点是三个 `onDraw`（海报进度条、播放进度、弹幕滑杆），
 * 每次绘制都新分配一个 `RectF` 会在滚动 / 播放时持续产生垃圾。绘制都在界面线程上完成，
 * 因此共享一个实例是安全的。
 */
private val roundRectScratch = RectF()

internal fun drawRoundRectCompat(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float, paint: Paint) {
    if (right <= left || bottom <= top) return
    roundRectScratch.set(left, top, right, bottom)
    canvas.drawRoundRect(roundRectScratch, radius, radius, paint)
}

/** 4.4 上把图标安全地放进 `ImageView`；21+ 走系统实现。 */
internal fun ImageView.setVectorIcon(resId: Int) {
    if (Build.VERSION.SDK_INT >= 21) {
        setImageResource(resId)
        return
    }
    setImageDrawable(context.vectorDrawable(resId))
}

/**
 * 取一张图标（vector 或它的位图兜底）。
 *
 * **API 19/20 绝不能直接走 [VectorDrawableCompat.create]**。
 *
 * minSdk < 21 时 AGP 会为每个 vector 额外生成 `ldpi`…`xxxhdpi` 的 PNG
 * （见 `build/generated/res/pngs/`），而 `anydpi-v21` 的 XML 在 4.4 上被版本限定
 * 直接排除。于是同一个资源 ID 在 4.4 上解析出来的是**位图**：
 *
 * ```
 * resource 0x7f050059 drawable/ic_arrow_up
 *   (xhdpi) res/Im.png type=PNG        ← 4.4 / xhdpi 命中的就是它
 *   (anydpi-v21) res/U3.xml type=XML   ← 21+ 才命中
 * ```
 *
 * `VectorDrawableCompat.create()` 内部固定调 `Resources.getXml()`，会把这张 PNG
 * 当二进制 XML 解析并抛 `Resources$NotFoundException: File res/Im.png from xml type`——
 * 1.2.0.1 在 MI PAD（4.4.4）上「打开即闪退」就是这个原因（`Bad XML block: header size
 * 18254` 正是 PNG 文件头的 `0x474E`）。
 *
 * 因此顺序改成：**先让 `Resources` 自己挑**（挑到的就是那张 PNG 兜底，`ImageView` /
 * `TextView` 的 compound drawable 都能正常吃），只有它挑不出来（说明资源确实是 XML，
 * 例如以后关掉 `generatedDensities`）才回落 Compat。这样两种打包方式都不会崩。
 */
@Suppress("DEPRECATION")
internal fun Context.vectorDrawable(resId: Int): Drawable? {
    if (Build.VERSION.SDK_INT >= 21) return getDrawable(resId)
    // 4.4/5.0：优先用 Resources 解析出来的位图兜底。
    runCatching { resources.getDrawable(resId) }.getOrNull()?.let { return it }
    // 走到这里说明没有位图兜底、资源是 XML，此时 Compat 才是有意义的。
    return runCatching { VectorDrawableCompat.create(resources, resId, theme) }.getOrNull()
}

/**
 * 框架里有没有 `isAttachedToWindow()`（API 19 才有）。
 *
 * 只探一次。**不能用 `SDK_INT` 判**：线上那台盒子把 `ro.build.version.sdk` 报成 19、
 * 框架其实还是 4.2，`SDK_INT >= 19` 成立、调用却抛
 * `NoSuchMethodError: android.view.View.isAttachedToWindow`（栈顶 `onGlobalFocusChanged`）。
 */
private val hasIsAttachedToWindow: Boolean = runCatching {
    View::class.java.getMethod("isAttachedToWindow")
}.isSuccess

/**
 * 控件是否真的挂在窗口上。
 *
 * 有 `isAttachedToWindow()` 就用它（4.4+ 与原生完全一致，含弹窗、临时 detach 这些边界）；
 * 没有才退回 `getWindowToken()`——它在框架里就是 `mAttachInfo != null ? mWindowToken : null`，
 * 与 `isAttachedToWindow()` 同一个判据，API 1 起就有。
 */
internal val View.attachedToWindow: Boolean
    get() = if (hasIsAttachedToWindow) isAttachedToWindow else windowToken != null

/**
 * 框架里到底有没有 `isLaidOut()`。
 *
 * 只探一次：4.4+ 有，报高版本的 4.2/4.3 盒子没有。**不能用 `SDK_INT` 判**——
 * 那种盒子正是把版本号报成 19 才让 `isAttachedToWindow` 崩掉的。
 */
private val hasIsLaidOut: Boolean = runCatching {
    View::class.java.getMethod("isLaidOut")
}.isSuccess

/**
 * 是否已经走过一次布局。
 *
 * 这里**必须**尽量走真的 `isLaidOut()`，不能拿「宽高大于 0」顶替：
 * 框架在 `onAttachedToWindow` / `onDetachedFromWindowInternal` 里会清掉这个标志，
 * 但宽高是**留着上一次的值**。列表里的卡片是从回收池里挂回来的，那一下宽高已经是
 * 上一次布局的旧值、`getLocationInWindow` 却全是 0——`BackToTop` 正是靠这个区别
 * 决定「先藏起来等布局」，用宽高判断会把它摆到右上角去。
 *
 * 只有在探不到方法的旧盒子上才退回尺寸判断。
 */
internal val View.laidOut: Boolean
    get() = if (hasIsLaidOut) isLaidOut else width > 0 && height > 0

/** 框架里有没有 `getAllocationByteCount()`（API 19 才有）。只探一次，理由同上。 */
private val hasAllocationByteCount: Boolean = runCatching {
    Bitmap::class.java.getMethod("getAllocationByteCount")
}.isSuccess

/**
 * 位图占用的字节数。
 *
 * 有 `getAllocationByteCount()` 就用它（4.4+ 与原生完全一致）；没有才退回
 * `getByteCount()`（API 1）。本项目解码不用 `inBitmap` 复用，两者本就相等。
 * 线上那条 `NoSuchMethodError: android.graphics.Bitmap.getAllocationByteCount`
 * 栈顶在柔光图层的构造函数里，就是这里——同样不能靠版本号判断。
 */
@Suppress("DEPRECATION")
internal val Bitmap.memoryBytes: Int
    get() = if (hasAllocationByteCount) allocationByteCount else byteCount

/** 框架里有没有 `isLowRamDevice()`（API 19 才有）。只探一次，理由同上。 */
private val hasIsLowRamDevice: Boolean = runCatching {
    ActivityManager::class.java.getMethod("isLowRamDevice")
}.isSuccess

/**
 * 是否低内存设备。
 *
 * `ActivityManager.isLowRamDevice()` 是 API 19，且会在「报高版本」的盒子上抛
 * `NoSuchMethodError`（`onCreate` 里就要读它，等于一启动就崩）。取不到时退回
 * `memoryClass`：Android 给低内存设备的默认值是 64/96 MB，正常电视是 192 以上。
 */
internal fun ActivityManager?.isLowRamDeviceCompat(): Boolean {
    if (this == null) return false
    if (hasIsLowRamDevice) return isLowRamDevice
    return memoryClass < 128
}

/** 整机物理内存（MB）；读不到时返回 0。 */
internal fun Context.totalMemoryMb(): Int = runCatching {
    val info = ActivityManager.MemoryInfo()
    activityManager()?.getMemoryInfo(info)
    (info.totalMem / 1048576L).toInt()
}.getOrDefault(0)

/**
 * 这台机器算不算「低配电视」——**全应用唯一的低配判据**。
 *
 * ## 为什么不能只看 `isLowRamDevice()`
 *
 * 那一位来自系统属性 `ro.config.low_ram`，2GB 的电视盒子几乎一律是 `false`
 * （厂商为了让系统别降级动画会关掉它）。只看它，整个应用就按高端机跑：
 * 焦点缩放动画全开、柔光位图全质量、封面用 ARGB_8888、8 个解码线程。
 * 实测：这台 2GB 机器上 `isLowRamDevice()==false`，于是「按一下方向键就顿一下」
 * 的两笔最大开销（整卡面积的双线性柔光位图、每次焦点变化的全页 re-measure）全都留着。
 *
 * ## 三条判据，任一成立即算低配
 *
 * 1. **整机物理内存 ≤ 2560 MB** —— 主判据：1G / 1.5G / 2G / 2.5G 全部落进来。
 *    读得到物理内存时以它为准，**不再看** `isLowRamDevice()` 与 `memoryClass`；
 * 2. 堆上限 ≤ 96 MB —— 只有这一档是真放不下两屏海报的硬限制，无论整机多大都降级；
 * 3. 物理内存读不到（个别 4.x 盒子 `totalMem` 返 0）时才退回：
 *    `isLowRamDevice()` 为真，或堆上限 ≤ 192 MB。
 *
 * ## 为什么 `memoryClass ≤ 192` 不能当主判据
 *
 * `memoryClass` 是**堆上限**，不是整机内存：`192` 正好是 Android 电视与模拟器最常见
 * 的一档默认值，4GB 的机器（含 Android Studio 的 4GB AVD）照样报 192。
 * 早先把「≤ 192」当第三条判据，结果是 4GB 模拟器也被判成低配：
 * 全量降级、设置页里那枚「减少动画优化性能」永远不出现——用户在 4GB 机器上
 * 根本没法打开 / 关闭它。判据必须按物理内存走，堆上限只作读不到时的兜底。
 *
 * 判据只在这里算一次、由调用方缓存（`TvStyle.animate` / `Soft.shadowBitmaps` /
 * `ArtworkLoader.lowRam` 必须同源，否则会出现「动画关了、图片还按高端机解」这种半套降级）。
 */
internal fun Context.lowEndTv(): Boolean {
    val manager = activityManager()
    val heap = manager?.memoryClass ?: 0
    // 硬限制：堆小到连两屏封面都放不下，整机内存再大也只能降级。
    if (heap in 1..96) return true
    val total = totalMemoryMb()
    if (total > 0) return total <= 2560
    // 读不到物理内存：才退回系统标志与堆上限。
    if (manager?.isLowRamDeviceCompat() == true) return true
    return heap in 1..192
}

/**
 * 省略号位置。
 *
 * `START` / `MIDDLE` 在 `StaticLayout.calculateEllipsis` 里都是**从后往前**数的：
 *
 * ```
 * for (right = len; right >= 0; right--) { widths[right - 1 + lineStart - widthStart] ... }
 * ```
 *
 * 走到 `right == 0` 时下标就是 -1。这条循环在两个版本里才各自被改成 `> 0`：
 * `MIDDLE` 是 **5.1（API 22）**，`START` 是 **6.0（API 23）**。因此
 * 「21 以下才换掉」是错的——线上 5.0.1 的电视仍然崩：
 * `ArrayIndexOutOfBoundsException: length=61; index=-1`（局域网地址那条胶囊用的就是 MIDDLE）。
 *
 * 所以按**各自**的修复版本判断，早于它的换成 [legacy]（默认 `END`）。
 * 输入框这类「要看尾巴」的控件传 `legacy = null`：不省略时 EditText 会横向滚动到光标处，
 * 粘贴进去的那一串末尾照样看得见。
 */
internal fun TextView.ellipsizeCompat(
    where: TextUtils.TruncateAt?,
    legacy: TextUtils.TruncateAt? = TextUtils.TruncateAt.END
) {
    ellipsize = when {
        where == null -> null
        Build.VERSION.SDK_INT >= ellipsisFixedIn(where) -> where
        else -> legacy
    }
}

/** `StaticLayout` 里这个省略位置从哪个 API 起不再越界（见 [ellipsizeCompat]）。 */
private fun ellipsisFixedIn(where: TextUtils.TruncateAt): Int = when (where) {
    TextUtils.TruncateAt.MIDDLE -> 22
    TextUtils.TruncateAt.START -> 23
    else -> 1
}
