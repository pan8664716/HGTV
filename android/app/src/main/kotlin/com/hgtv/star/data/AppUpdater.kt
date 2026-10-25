// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.hgtv.star.core.AppUpdate
import com.hgtv.star.core.AppUpdateRepository
import com.hgtv.star.core.UpdatePrompt
import com.hgtv.star.core.UpdateFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import com.hgtv.star.R
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.DialogItem
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.tvColumn
import com.hgtv.star.tv.tvDp
import com.hgtv.star.tv.tvText
import com.hgtv.star.BuildConfig

/**
 * 应用内更新。
 *
 * 行为（按需求）：
 * - **进入应用时提示**：前台空闲（不在播放界面、没有别的弹窗）时检查一次新版，
 *   有新版就弹「是否更新」窗口；用户点「更新」才下载，下好交给系统安装器覆盖安装。
 * - **暂不更新就安静**：选过一次「暂不更新」后不再主动提示，直到设备上装上了新版本
 *   （`versionCode` 变了）才重新开始提示；设置里的手动检查始终可用。
 * - **播放中不打扰**：`screen == "player"` 或有弹窗时不发请求、不弹窗。
 * - **不拦安装**：下载只负责把字节取回来，不在应用内比对哈希 / 包名 / 版本 / 签名；
 *   安装直接交给系统安装器，能不能装由系统判断。
 * - **走镜像也能更新**：检查与下载都先走内置镜像（见 `UpdateSource.MIRRORS`，国内连不上
 *   `github.com` 的网络靠它），镜像失败自动回退直连；两条路都只搬字节。
 */
class AppUpdater(
    private val activity: Activity,
    private val idle: () -> Boolean,
    /**
     * 「用手机把安装包传进电视」：由界面层提供（见 `TvTools.updateUpload`）。
     *
     * 做成注入口而不是直接依赖 `TvTools`：更新器在 `onCreate` 里就建好，
     * 而二维码那套属于界面工具，两者各管一段，谁也不该反向依赖谁。
     */
    private val uploadFromPhone: (View?, File, (File) -> Unit) -> Unit = { _, _, _ -> }
) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = AppUpdateRepository()
    private val preferences = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    /**
     * 下载落点：**内部存储的 files 目录，不是 cache**。
     *
     * 电视的 cache 目录随时会被系统清掉（低存储时最优先被清的就是它），
     * 而 `PackageInstaller` 会在会话里再读一遍这个文件：包被清掉之后，
     * 用户看到的就是「点安装没反应 / 安装失败」，什么提示都没有。
     * files 目录不参与 cache 回收，只有用户主动清数据才会没。
     */
    private val apk = File(activity.filesDir, "updates/update.apk")

    /** 自动检查的最小间隔：反复切前后台时不重复请求。 */
    private val autoIntervalMs = 10 * 60 * 1000L
    /** 检查 / 下载失败后的退避时间。 */
    private val failureBackoffMs = 30 * 60 * 1000L

    /** 状态行后缀：这一趟走了镜像就说一句出处，直连不加。 */
    private fun via() = repository.source?.takeIf { it.prefix.isNotEmpty() }?.let { " · 经 ${it.name}" }.orEmpty()

    private var task: Future<*>? = null
    private var generation = 0
    private var active = false
    private var busy = false
    /** 正在后台体检安装包：期间不再接受第二次点击，但界面按钮保持可用（不显示「处理中…」）。 */
    private var preparing = false
    private var available: AppUpdate? = null
    private var ready = false
    /** 已下好、可以直接装的那个版本的版本名与 versionCode（来自元数据或本地包）。 */
    private var readyName = ""
    private var localCode = 0L
    private var permissionRequested = false
    private var retryAfter = 0L
    /** 本次前台已经提示过的版本，避免同一个版本反复弹窗。 */
    private var promptedCode = 0L
    private var status = "当前版本 ${BuildConfig.VERSION_NAME}"

    private var dialog: TvDialog? = null
    private var statusView: TextView? = null
    private var actionButton: DialogItem? = null
    private var anchor: View? = null

    init {
        // 老版本把包下在 `cacheDir/updates`；升级上来之后那份包还在，搬过来就不必重下 4 MB。
        // 反过来（新包被系统清掉）不处理：那是正常失效，重下一次就好。
        val legacy = File(activity.cacheDir, "updates/update.apk")
        if (!apk.isFile && legacy.isFile) runCatching {
            apk.parentFile?.mkdirs()
            legacy.copyTo(apk, overwrite = true)
            legacy.delete()
        }
    }

    /**
     * 安装会话的结果回执。
     *
     * 电视上的系统安装器**不一定**注册了 `ACTION_VIEW` / `ACTION_INSTALL_PACKAGE`
     * （很多定制固件只保留了系统自己的「应用安装」界面），因此安装主路径改成
     * `PackageInstaller` 会话：把包写进会话、commit，系统自己弹出确认界面。
     * 这条路不依赖任何 Activity 能被隐式 Intent 找到，装完 / 失败通过这条广播回来。
     */
    private var installerReceiver: BroadcastReceiver? = null

    /**
     * 会话提交后「连一句回执都没有」的看门狗。
     *
     * 电视上最常见的「装不上」不是报错，而是**什么都没发生**：某些定制固件把
     * `PackageInstaller` 的确认界面裁掉了，`commit()` 成功、回执却永远不来，
     * 用户按了「安装」之后界面上一点反应都没有，只能认为这个功能坏了。
     *
     * 只在「还没收到任何回执」时数秒（[STATUS_PENDING_USER_ACTION] 一到就撤掉，
     * 那之后是用户在系统界面上操作，等多久都正常）。
     */
    private val watchdog = Runnable {
        if (installerReceiver == null) return@Runnable
        // 已经切到后台：不要在用户看不见的时候把安装界面拉起来。
        if (!active) { stopInstallerReceiver(); return@Runnable }
        stopInstallerReceiver()
        fallbackInstall("系统安装器没有响应。")
    }

    private companion object {
        /** 会话提交后等第一句回执的时间：超过就认为系统的安装通道是死的。 */
        const val WATCHDOG_MS = 20000L
    }

    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun disarmWatchdog() = main.removeCallbacks(watchdog)

    val showing get() = dialog?.isShowing == true

    // —— 「暂不更新」的持久状态 ——

    /** 「暂不更新」的记忆；规则与边界由 core 的 [UpdatePrompt] 定义，这里只负责存取。 */
    private fun promptState() = UpdatePrompt(
        declined = preferences.getBoolean("declined", false),
        declinedAt = preferences.getLong("declined_at", -1L)
    )

    private fun save(state: UpdatePrompt) {
        preferences.edit()
            .putBoolean("declined", state.declined)
            .putLong("declined_at", state.declinedAt)
            .apply()
    }

    /** 用户选过「暂不更新」：装上新版本之前不再主动提示。 */
    private fun declined() = promptState().suppressed(BuildConfig.VERSION_CODE.toLong())

    private fun decline() = save(promptState().decline(BuildConfig.VERSION_CODE.toLong()))

    /** 已经装上新版本 → 清掉「暂不更新」，重新开始提示。 */
    private fun refreshDeclineState() = save(promptState().refresh(BuildConfig.VERSION_CODE.toLong()))

    // —— 生命周期 ——

    /**
     * 前台轮询：每 15 秒看一次，满足「空闲 + 没有弹窗 + 未选暂不更新」才动。
     * 已下载好的包先提示安装；否则距上次检查超过 [autoIntervalMs] 就再查一次。
     */
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (idle() && dialog == null && !busy && !declined()) {
                val now = System.currentTimeMillis()
                when {
                    ready && pendingCode() > 0L && promptedCode != pendingCode() -> {
                        promptedCode = pendingCode()
                        promptInstall()
                    }
                    now >= retryAfter &&
                        now - preferences.getLong("last_attempt", 0) >= autoIntervalMs -> check(manual = false)
                }
            }
            main.postDelayed(this, 15000)
        }
    }

    fun resume() {
        active = true
        refreshDeclineState()
        main.removeCallbacks(poll)
        main.postDelayed(poll, 8000)
        // 本地已经有一个下好的、更新的包时先把它认出来：这一刻不需要网络。
        // 很多「装不上」的用户就是这样卡住的——包已经下完了，下一次打开应用时
        // 网络正好连不上镜像，界面上再也找不到「安装」这个入口。
        worker.execute {
            val local = localUpdate()
            main.post {
                if (!active || local == null) return@post
                localCode = local.code
                ready = true
                readyName = local.name
            }
        }
        if (permissionRequested) {
            permissionRequested = false
            if (canRequestInstalls()) install()
            else {
                status = "安装权限未开启，可在设置里再次安装"
                openStatus(anchor, "关闭", startDownload = false)
            }
        }
    }

    fun pause() {
        active = false
        main.removeCallbacks(poll)
        interrupt()
        dialog?.dismiss()
    }

    fun interrupt() {
        generation++
        task?.cancel(true)
        task = null
        repository.cancel()
        busy = false
        preparing = false
    }

    fun destroy() {
        pause()
        stopInstallerReceiver()
        worker.shutdownNow()
        // TLS 连接的关闭可能发 close_notify，不能放在主线程上做。
        Thread({ repository.close() }, "hgtv-update-cleanup").start()
    }

    // —— 弹窗 ——

    /** 设置 → 「版本与更新」：手动检查 / 下载 / 安装。 */
    fun show(from: View?) {
        if (!active) return
        openStatus(from, "关闭", startDownload = false)
    }

    /** 进入应用时发现新版：先问「是否更新」，用户点了才下载。 */
    private fun prompt(update: AppUpdate) {
        if (!active || declined()) return
        dialog?.dismiss()
        val next = TvDialog(activity, "发现新版本")
            .message("${update.versionName} · ${"%.1f".format(update.size / 1048576.0)} MB")
            .message(update.notes.ifBlank { "新版已发布。" })
            .message("更新由系统安装器覆盖安装，收藏与观看记录保留。", 14f, TvStyle.muted)
        next.button("暂不更新", icon=R.drawable.ic_menu_close) { decline() }
        next.button("更新", icon=R.drawable.ic_menu_restore) { openStatus(null, "取消", startDownload = true) }
        dialog = next
        next.show(activity.currentFocus) { if (dialog === next) dialog = null }
    }

    /** 上次已经下载好、这次进应用才发现：直接问是否安装。 */
    private fun promptInstall() {
        if (!active || declined()) return
        dialog?.dismiss()
        val next = TvDialog(activity, "已下载新版本")
            .message("${readyName.ifBlank { "新版" }} 已下载完成，现在安装？")
        next.button("暂不更新", icon=R.drawable.ic_menu_close) { decline() }
        next.button("安装", icon=R.drawable.ic_menu_check) { install() }
        dialog = next
        next.show(activity.currentFocus) { if (dialog === next) dialog = null }
    }

    /**
     * 进度 / 手动检查共用的状态弹窗：一行状态文字 + 一个动作按钮。
     * 动作按钮随状态在「检查更新 / 下载新版 / 安装新版」之间切换。
     */
    private fun openStatus(from: View?, negative: String, startDownload: Boolean) {
        dialog?.dismiss()
        anchor = from
        val body = activity.tvColumn()
        statusView = activity.tvText(status, 16f, TvStyle.text).apply {
            setLineSpacing(activity.tvDp(TvStyle.GAP_TIGHT).toFloat(), 1f)
        }
        body.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        body.addView(
            activity.tvText("只在应用前台浏览时检查，播放中不打扰；更新优先走镜像，失败回退 GitHub。", 13f, TvStyle.muted)
                .apply { setPadding(0, activity.tvDp(TvStyle.GAP_TIGHT), 0, 0) },
            LinearLayout.LayoutParams(-1, -2)
        )
        val next = TvDialog(activity, "版本与更新 · ${BuildConfig.VERSION_NAME}").apply {
            content.addView(body, LinearLayout.LayoutParams(-1, -2))
            button(negative, icon = R.drawable.ic_menu_close) { if (busy) interrupt() }
            actionButton = button("检查更新", keepOpen = true, icon = R.drawable.ic_menu_restore) {
                when {
                    ready -> install()
                    available != null -> download()
                    else -> check(manual = true)
                }
            }
            // 「下载失败」时电视上没有任何别的出路：镜像连不上、系统浏览器打不开、
            // 安装器被裁……所以这条退路必须一直摆在弹窗里，不能只在某个失败分支才出现。
            // 用户手上有手机、有 WiFi 就能把包装进来，不需要电视联网。
            button("手机传安装包", icon = R.drawable.ic_action_phone) { phoneUpload() }
        }
        dialog = next
        renderActions()
        next.show(from) { if (dialog === next) { dialog = null; statusView = null; actionButton = null } }
        if (startDownload) download()
    }

    private fun updateStatus(text: String) {
        status = text
        statusView?.text = text
    }

    private fun renderActions() {
        actionButton?.text = when {
            busy -> "处理中…"
            ready -> "安装新版"
            available != null -> "下载新版"
            else -> "检查更新"
        }
    }

    // —— 后台任务 ——

    /** 一次检查的结果：远端元数据、本地那份包是否可直接用、以及本地包本身。 */
    private class Probe(val update: AppUpdate, val cached: Boolean, val local: Local?)

    /** 本地已下载的包（只在它**确实比本机新**时返回）。 */
    private class Local(val code: Long, val name: String)

    /**
     * 读本地那份安装包，判断它是不是一个比本机更新的、属于本应用的包。
     *
     * 只读清单，不校验哈希（那是 [check] 拿到元数据之后才做得了的事），
     * 因此可以在没有网络的时候单独用：包是上一次自己下下来的，
     * 用户重新打开应用就该能接着装。
     */
    @Suppress("DEPRECATION")
    private fun localUpdate(): Local? {
        if (!apk.isFile || apk.length() <= 0L) return null
        val archive = runCatching {
            activity.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags())
        }.getOrNull() ?: return null
        if (archive.packageName != activity.packageName) return null
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (code <= BuildConfig.VERSION_CODE) return null
        return Local(code, archive.versionName.orEmpty().ifBlank { "新版本" })
    }

    /** 当前「可以装」的那个版本号；没有可装的包时是 0。 */
    private fun pendingCode(): Long = available?.versionCode ?: localCode

    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        busy = true
        renderActions()
        val ticket = ++generation
        task = worker.submit {
            try {
                val result = action()
                main.post { if (active && generation == ticket) { busy = false; done(result); renderActions() } }
            } catch (e: Exception) {
                // 更新失败是用户会主动点、主动问的一条：「检查更新没反应」「下载到一半断了」。
                // 「尚无可用的更新版本」是正常结果，不算失败，别污染后台。
                if ((e as? IOException)?.message != "尚无可用的更新版本") {
                    com.hgtv.star.Diagnostics.fail(
                        "app_update",
                        "检查更新失败",
                        "${com.hgtv.star.devicePrefix(activity)} cause=${com.hgtv.star.Diagnostics.chainOf(e)}",
                        e
                    )
                }
                main.post {
                    if (active && generation == ticket) {
                        busy = false
                        ready = false
                        retryAfter = System.currentTimeMillis() + failureBackoffMs
                        updateStatus(failureText(e))
                        renderActions()
                    }
                }
            }
        }
    }

    /**
     * 失败时写给用户看的话：先说原因，再给一条手动安装的退路。
     *
     * 应用内更新失败时（连不上镜像与 GitHub、或系统安装入口不可用），
     * 用户至少知道去哪儿装；地址带镜像前缀，连不上 `github.com` 的网络也能打得开。
     * 「没有可用的更新版本」不加这句——那时候根本没有包可装。
     */
    private fun failureText(e: Exception): String {
        val message = (e as? IOException)?.message ?: "更新失败，请重试"
        if (message == "尚无可用的更新版本") return message
        return "$message\n若反复失败，可在电视浏览器打开 ${AppUpdate.MANUAL_URL} 手动安装"
    }

    /** 检查新版。[manual] 为用户在设置里主动点击。 */
    fun check(manual: Boolean) {
        if (busy || !active || !idle()) return
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        updateStatus("正在检查新版…")
        work({
            val update = repository.latest()
            // 上一趟已经下好、且与这次元数据逐字节对得上（长度 + SHA-256）：不必重下。
            // 校验放在后台线程：4 MB 的 SHA-256 在电视上要十几毫秒，不该压在界面线程上。
            val cached = update.versionCode > BuildConfig.VERSION_CODE && UpdateFiles.verify(apk, update)
            Probe(update, cached, localUpdate())
        }) { probe ->
            val update = probe.update
            val cached = probe.cached
            val local = probe.local
            // 只判断「有没有比本机更高的版本」——这是「有没有更新」本身，不是安装限制。
            // 最低系统版本不再过滤：能不能装由系统安装器判断。
            val found = update.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            available = found
            // 本地下好的包也要算「可以直接装」：它可能比元数据还新（镜像缓存滞后）。
            val usable = cached || (local != null && found == null)
            ready = usable
            if (usable) {
                localCode = local?.code ?: found?.versionCode ?: localCode
                readyName = local?.name ?: found?.versionName.orEmpty()
            }
            if (found == null && local == null) {
                updateStatus("已是最新版本 ${BuildConfig.VERSION_NAME}${via()}")
                worker.execute { apk.delete() }
            } else if (found == null && local != null) {
                updateStatus("已下载 ${local.name}，可直接安装")
                if (!manual) {
                    promptedCode = local.code
                    promptInstall()
                }
            } else {
                val next = found!!
                updateStatus(
                    "发现 ${next.versionName} · ${"%.1f".format(next.size / 1048576.0)} MB${via()}" +
                        (if (cached) "（已下载，可直接安装）" else "") +
                        "\n${next.notes}"
                )
                // 手动：留在弹窗里等用户按「下载新版」；自动：直接问是否更新。
                if (!manual) {
                    promptedCode = next.versionCode
                    if (cached) {
                        readyName = next.versionName
                        promptInstall()
                    } else prompt(next)
                }
            }
        }
    }

    private fun download() {
        val update = available ?: return
        if (busy || !active || !idle()) return
        updateStatus("正在下载 ${update.versionName}…${via()}")
        val ticket = generation + 1
        work({
            // 边收边算 SHA-256，与发布元数据核对；对不上就换下一个源重来。
            //
            // 这里**不再是「原样搬字节」**：镜像偶尔会给出一张错误页 / 被截断的包，
            // 那种包交给系统安装器只会得到一句「解析包时出现问题」，
            // 用户看不出是下载坏了还是版本不兼容。哈希对不上就在这里中止，
            // 界面能直接说「安装包校验未通过，请重试」。
            apk.delete()
            repository.download(update, apk) { percent ->
                main.post { if (generation == ticket && active) updateStatus("正在下载 ${update.versionName} · $percent%${via()}") }
            }
        }) {
            ready = true
            readyName = update.versionName
            localCode = update.versionCode
            dialog?.dismiss()
            install()
        }
    }

    /** 「允许安装未知应用」只有 API 26+ 才有逐应用开关；更低版本一律当作已允许。 */
    private fun canRequestInstalls(): Boolean =
        Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

    // —— 安装前体检 / 装不上时的出路 ——

    /** 自己能提前判出来的阻断原因。 */
    private enum class Blocker { NONE, DAMAGED, WRONG_PACKAGE, OLDER, SDK_TOO_LOW, SIGNATURE, STORAGE }

    private class Check(val blocker: Blocker, val detail: String = "")

    /**
     * 一个包的签名证书**历史**：API 28 起是 `apkContentsSigners` + `signingCertificateHistory`，
     * 之前是 `signatures`。
     *
     * 必须取历史而不是「当前第一张证书」：万一哪天真做了密钥轮换
     * （APK Signature Scheme v3 的 lineage），新包的当前证书与旧包不同，
     * 但系统会按历史判定为同一来源、允许覆盖安装。只比当前证书会把这种**能装上的更新**
     * 判成签名冲突，反而把用户挡在门外。
     */
    @Suppress("DEPRECATION")
    private fun signerHistory(info: PackageInfo): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return out
            signing.apkContentsSigners?.forEach { cert -> cert?.let { out += it.toByteArray() } }
            signing.signingCertificateHistory?.forEach { cert -> cert?.let { out += it.toByteArray() } }
        } else {
            info.signatures?.forEach { cert -> cert?.let { out += it.toByteArray() } }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    /**
     * 装之前把能自己判的错都判掉。
     *
     * 系统安装器在电视上只会给一句「应用未安装」或「解析包时出现问题」，
     * 用户根本分不清是包坏了、系统太旧、还是签名冲突；而这三类的处理方式完全不同：
     * 前两类重下 / 换机，签名冲突只能卸载重装。先把它们分开，再决定给什么出路。
     */
    @Suppress("DEPRECATION")
    private fun inspect(): Check {
        val pm = activity.packageManager
        val flags = signatureFlags()
        val archive = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
            ?: return Check(Blocker.DAMAGED)
        if (archive.packageName != activity.packageName) {
            return Check(Blocker.WRONG_PACKAGE, archive.packageName.orEmpty())
        }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val name = archive.versionName.orEmpty()
        if (code <= BuildConfig.VERSION_CODE) return Check(Blocker.OLDER, "$name（$code）")
        // `ApplicationInfo.minSdkVersion` 是 API 24 才有的字段，4.x 上读它会 NoSuchFieldError。
        val minSdk = if (Build.VERSION.SDK_INT >= 24) archive.applicationInfo?.minSdkVersion ?: 0 else 0
        if (minSdk > 0 && minSdk > Build.VERSION.SDK_INT) {
            return Check(Blocker.SDK_TOO_LOW, "$name 要求 Android $minSdk，本机是 ${Build.VERSION.SDK_INT}")
        }
        val local = runCatching { signerHistory(pm.getPackageInfo(activity.packageName, flags)) }.getOrNull().orEmpty()
        val remote = signerHistory(archive)
        val sharesCert = local.any { installed -> remote.any { incoming -> installed.contentEquals(incoming) } }
        if (local.isNotEmpty() && remote.isNotEmpty() && !sharesCert) return Check(Blocker.SIGNATURE)
        return Check(Blocker.NONE)
    }

    /** 内部存储够不够：安装时系统还要在 /data 里再展开一份同样的包，并写 dex 缓存。 */
    private fun hasInstallSpace(): Boolean = runCatching {
        val dir = apk.parentFile?.absolutePath ?: activity.filesDir.absolutePath
        StatFs(dir).availableBytes > apk.length() * 2 + 24L * 1048576
    }.getOrDefault(true)

    private fun reasonOf(check: Check): String = when (check.blocker) {
        Blocker.DAMAGED -> "下载到的文件不是一个完整的安装包（镜像可能返回了错误页）。重新下载一次通常就好。"
        Blocker.WRONG_PACKAGE -> "这个安装包不是红果TV（包名是 ${check.detail}），已停止安装。"
        Blocker.OLDER -> "下载到的是 ${check.detail}，不比本机版本新。镜像可能还在发旧包，过几分钟再试。"
        Blocker.SDK_TOO_LOW -> "${check.detail}。这台电视的系统版本装不了这个版本。"
        Blocker.SIGNATURE -> "本机已安装的红果TV与新版的签名不一致，系统一定会拒绝覆盖安装。"
        Blocker.STORAGE -> "内部存储不够了：安装时系统还要再展开一份同样的包。请先清理空间。"
        Blocker.NONE -> ""
    }

    /** 装不上时的统一出口：说清原因，并给出至少两条真正能走的安装路径。 */
    private fun showInstallHelp(check: Check) {
        dialog?.dismiss()
        val conflict = check.blocker == Blocker.SIGNATURE
        // 包本身有问题（坏包 / 旧包 / 不是本应用）→ 重下就能解决，把「重新下载」摆在主位。
        val retryable = check.blocker == Blocker.DAMAGED ||
            check.blocker == Blocker.OLDER ||
            check.blocker == Blocker.WRONG_PACKAGE
        val next = TvDialog(activity, if (conflict) "这台电视装不上这个版本" else "安装没有完成")
        next.message(reasonOf(check))
        if (conflict) {
            next.message(
                "只有一个办法：卸载后重装。\n卸载会清掉本机的收藏、观看记录与设置——" +
                    "先按「保存安装包」把包存到「下载」文件夹，再卸载，装回之后用「手机备份与恢复」把记录找回来。" +
                    "如果之前导出过备份，那一步就能原样恢复。",
                14f, TvStyle.muted
            )
        }
        next.button("关闭", icon = R.drawable.ic_menu_close)
        next.button("保存安装包", icon = R.drawable.ic_action_import) { saveForManualInstall() }
        next.button("用手机传安装包", icon = R.drawable.ic_action_phone) { phoneUpload() }
        when {
            conflict -> next.button("卸载后重装", tone = ButtonTone.PRIMARY, icon = R.drawable.ic_menu_trash) { confirmReinstall() }
            retryable -> next.button("重新下载", tone = ButtonTone.PRIMARY, icon = R.drawable.ic_menu_restore) {
                // 坏包留在原地只会让下一次「安装」再撞同一个错。
                // 整包重下由 [download] 负责：它先删旧文件，再边收边校验。
                ready = false
                download()
            }
            else -> next.button("换一种方式安装", tone = ButtonTone.PRIMARY, icon = R.drawable.ic_menu_restore) { moreWays() }
        }
        dialog = next
        next.show(anchor) { if (dialog === next) dialog = null }
    }

    /**
     * 其他安装方式。
     *
     * 电视上装不上只有几类原因，对应几条真正不同的路：
     * 1. 系统安装器被拦 / 没有界面 → 再走一次隐式入口（[fallbackInstall]）；
     * 2. 文件管理器能装、安装器不能 → 把包存到「下载」，用电视自带的管理器打开；
     * 3. 有 root → `pm install` 静默装，不过确认界面；
     * 4. 全都不行 → 把发布页地址摆出来，用电视浏览器自己下。
     */
    private fun moreWays() {
        dialog?.dismiss()
        val labels = mutableListOf<String>()
        val icons = mutableListOf<Int?>()
        val actions = mutableListOf<() -> Unit>()
        labels += "用手机把安装包传进电视"; icons += R.drawable.ic_action_phone; actions += { phoneUpload() }
        labels += "再交给系统安装器试一次"; icons += R.drawable.ic_menu_restore; actions += { fallbackInstall() }
        labels += "保存到「下载」文件夹，用文件管理器安装"; icons += R.drawable.ic_action_import; actions += { saveForManualInstall() }
        if (suAvailable()) {
            labels += "用 root 静默安装（检测到 su）"; icons += R.drawable.ic_menu_cpu; actions += { installViaRoot() }
        }
        labels += "查看手动安装地址"; icons += R.drawable.ic_menu_info; actions += { showManualAddress() }
        val next = TvDialog(activity, "其他安装方式")
        next.message(
            "电视本身下不动、装不上时，最省事的一条：手机下好 APK，扫一下码传进电视，电视不需要联网。",
            14f, TvStyle.muted
        )
        next.choices(labels, icons = icons) { index -> actions.getOrNull(index)?.invoke() }
        next.button("关闭", icon = R.drawable.ic_menu_close)
        dialog = next
        next.show(anchor) { if (dialog === next) dialog = null }
    }

    /**
     * 手机 → 电视上传安装包，收到后立刻体检并安装。
     *
     * 这条路不需要电视联网，因此它同时解决「连不上镜像」与「系统浏览器打不开」两类问题。
     * 上传页写在 `cacheDir/updates/incoming.apk`（界面层的 `TvTools.updateUpload` 负责），
     * 这里把它搬进正式的落点，再走同一条安装链路（体检 → 会话 → 兜底）。
     */
    private fun phoneUpload() {
        if (!active) return
        updateStatus("正在打开手机上传页…")
        uploadFromPhone(anchor, File(activity.cacheDir, "updates")) { file ->
            if (!active) return@uploadFromPhone
            worker.execute {
                val moved = runCatching {
                    apk.parentFile?.mkdirs()
                    file.copyTo(apk, overwrite = true)
                    file.delete()
                }.isSuccess
                val local = if (moved) localUpdate() else null
                main.post {
                    if (!active) return@post
                    if (local == null) {
                        // 传过来的包不是红果TV / 版本不比本机新 / 文件不完整：交给统一的体检出口说清楚。
                        val check = if (moved) inspect() else Check(Blocker.DAMAGED)
                        ready = false
                        showInstallHelp(if (check.blocker == Blocker.NONE) Check(Blocker.DAMAGED) else check)
                        return@post
                    }
                    ready = true
                    readyName = local.name
                    localCode = local.code
                    install()
                }
            }
        }
    }

    /**
     * 保存安装包并告诉用户路径（失败时给出浏览器下载的退路）。
     *
     * [note] 是「为什么走到这一步」：从系统安装器失败落下来的那一趟会带上系统的原话，
     * 用户拿着这段话去反馈才有意义。
     */
    private fun saveForManualInstall(note: String = "") {
        if (busy) return
        updateStatus("正在保存安装包…")
        worker.execute {
            val path = exportApk()
            main.post {
                if (!active) return@post
                val next = TvDialog(activity, if (path != null) "安装包已保存" else "保存失败")
                next.message(
                    (if (note.isNotBlank()) "$note\n\n" else "") +
                        if (path != null) "位置：\n$path\n\n" +
                            "在电视上打开「文件管理 / 电视管家」，找到这个文件点一下就会进入安装。" +
                            "装好之后这个文件可以删掉。"
                        else "这台电视不允许写公共目录。可以在电视浏览器打开下面的地址自行下载安装：\n${AppUpdate.MANUAL_URL}"
                )
                next.button("关闭", icon = R.drawable.ic_menu_close)
                next.button("查看手动安装地址", icon = R.drawable.ic_menu_info) { showManualAddress() }
                if (path == null) next.button("重新下载", tone = ButtonTone.PRIMARY, icon = R.drawable.ic_menu_restore) {
                    ready = false
                    download()
                }
                dialog?.dismiss()
                dialog = next
                next.show(anchor) { if (dialog === next) dialog = null }
            }
        }
    }

    private fun showManualAddress() {
        val next = TvDialog(activity, "手动安装地址")
        next.message("在电视的浏览器里打开下面的地址，下载 APK 后用文件管理器打开安装：\n\n${AppUpdate.MANUAL_URL}", 14f)
        next.button("关闭", icon = R.drawable.ic_menu_close)
        dialog?.dismiss()
        dialog = next
        next.show(anchor) { if (dialog === next) dialog = null }
    }

    /** 签名冲突的唯一出路：先存包，再拉起系统卸载。 */
    private fun confirmReinstall() {
        val next = TvDialog(activity, "卸载后重装")
        next.message(
            "顺序：① 保存安装包 → ② 系统卸载 → ③ 用文件管理器装回 → ④ 打开「手机备份与恢复」把记录找回来。\n\n" +
                "卸载会清掉本机全部记录，这一步不可撤销。"
        )
        next.button("取消", icon = R.drawable.ic_menu_close)
        next.button("先保存，再卸载", tone = ButtonTone.PRIMARY, icon = R.drawable.ic_menu_trash) {
            worker.execute {
                val path = exportApk()
                main.post {
                    if (!active) return@post
                    val opened = runCatching {
                        activity.startActivity(
                            Intent(Intent.ACTION_DELETE, Uri.parse("package:${activity.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                    if (!opened) {
                        updateStatus(
                            "这台电视没有卸载界面，请在「设置 → 应用」里手动卸载红果TV。" +
                                (if (path != null) "\n安装包已保存到：\n$path" else "")
                        )
                        openStatus(anchor, "关闭", startDownload = false)
                    }
                }
            }
        }
        dialog?.dismiss()
        dialog = next
        next.show(anchor) { if (dialog === next) dialog = null }
    }

    /** 有没有可用的 su。只查路径，不执行任何命令。 */
    private fun suAvailable(): Boolean = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sd/xbin/su", "/data/local/xbin/su", "/data/local/bin/su"
    ).any { runCatching { File(it).exists() }.getOrDefault(false) }

    /**
     * root 静默安装：`pm install -r -d` 直接覆盖，不弹系统确认界面。
     *
     * 只在检测到 su 时出现（见 [suAvailable]）：没有 root 的机器上这条路必然失败，
     * 摆出来只会让用户多点一次。`-d` 允许降级安装，`-r` 保留数据。
     */
    private fun installViaRoot() {
        if (busy) return
        updateStatus("正在用 root 安装…")
        worker.execute {
            val result = runCatching {
                // **不能直接 `pm install $apk`**：包在应用私有目录里，
                // 而 `pm` 最终要读这个文件——只有 `su` 那一段是 root，`pm` 自己按调用者身份读。
                // 先由 root 复制到 `/data/local/tmp`（shell 可读），再从那儿装。
                val staged = "/data/local/tmp/hgtv_update_${System.currentTimeMillis()}.apk"
                val command = "cp \"${apk.absolutePath}\" \"$staged\" && chmod 644 \"$staged\"" +
                    " && pm install -r -d \"$staged\"; rc=\$?; rm -f \"$staged\"; exit \$rc"
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                val code = process.waitFor()
                (code == 0 && output.contains("Success")) to output
            }.getOrElse { false to (it.message ?: "root 安装失败") }
            main.post {
                if (!active) return@post
                if (result.first) {
                    ready = false
                    updateStatus("新版已安装完成")
                    worker.execute { apk.delete() }
                    return@post
                }
                val next = TvDialog(activity, "root 安装没有成功")
                next.message("系统返回：\n${result.second.take(400).ifBlank { "（没有任何输出）" }}")
                next.button("关闭", icon = R.drawable.ic_menu_close)
                next.button("换一种方式安装", tone = ButtonTone.PRIMARY, icon = R.drawable.ic_menu_restore) { moreWays() }
                dialog?.dismiss()
                dialog = next
                next.show(anchor) { if (dialog === next) dialog = null }
            }
        }
    }

    private fun install() {
        if (busy || preparing || !active || !ready) return
        // 包没落盘就没有可装的东西：直说，不去碰安装器。
        if (!apk.isFile || apk.length() <= 0L) {
            ready = false
            updateStatus("安装包不完整，请重新下载")
            openStatus(anchor, "关闭", startDownload = false)
            return
        }
        // 装之前先自己体检一遍。系统安装器只会给一句「应用未安装」/「解析包时出现问题」，
        // 电视前面的人无从判断是包的问题、系统版本的问题，还是签名冲突；
        // 这里把能提前判出来的几种情况都拦在拉起安装器之前，并直接给对应出路。
        //
        // 体检放到后台线程：`getPackageArchiveInfo` 要解析一个 4 MB 包的清单，
        // 在 2GB 的电视上要十几到几十毫秒——按一下「安装」界面顿一下，用户会以为卡死了。
        preparing = true
        updateStatus("正在准备安装…")
        worker.execute {
            val check = inspect()
            val space = check.blocker != Blocker.NONE || hasInstallSpace()
            main.post {
                preparing = false
                if (!active) return@post
                when {
                    check.blocker != Blocker.NONE -> showInstallHelp(check)
                    !space -> showInstallHelp(Check(Blocker.STORAGE))
                    else -> proceedInstall()
                }
            }
        }
    }

    /** 体检通过之后的真正安装：先要「安装未知应用」权限，再交给系统安装器。 */
    private fun proceedInstall() {
        if (!canRequestInstalls() && Build.VERSION.SDK_INT >= 26) {
            // 「允许安装未知应用」是 Android 8.0（API 26）才有的逐应用授权。
            // 有些电视把这页设置也裁掉了：打不开时不能就此收手，继续走安装主路径
            // （系统安装器自己会给出「被拦截」的提示，比一句「没有入口」有用）。
            // 只有真的拉起了设置页才记下「回来要接着装」，否则 resume 会多弹一次状态窗。
            val opened = runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            }.isSuccess
            if (opened) { permissionRequested = true; return }
        }
        // 主路径：PackageInstaller 会话。不依赖隐式 Intent 能落到某个安装 Activity，
        // 覆盖安装由系统自己确认；Android 5.0 以下没有这套 API，直接走兜底。
        if (Build.VERSION.SDK_INT >= 21) {
            updateStatus("正在启动系统安装…")
            // 收起自己的窗口：系统确认界面要盖在最上面，留着它只会挡住电视上的「安装」按钮。
            dialog?.dismiss()
            worker.execute {
                val error = runCatching { installViaSession() }.exceptionOrNull()
                main.post {
                    if (!active || error == null) return@post
                    // 会话都建不起来（系统把 PackageInstaller 裁掉了 / 没有该权限）：
                    // 直接落到隐式入口那条路，并把原因带过去，别让用户看到白屏。
                    com.hgtv.star.Diagnostics.warn(
                        "app_update",
                        "install session failed: ${error.javaClass.simpleName}: ${error.message}",
                        error
                    )
                    fallbackInstall("系统安装会话没有建立起来（${error.javaClass.simpleName}）。")
                }
            }
        } else fallbackInstall()
    }

    /**
     * 把包写进 `PackageInstaller` 会话并 commit。
     *
     * 系统确认界面由 commit 之后的回执驱动：`STATUS_PENDING_USER_ACTION` 带着一个
     * 确认 Intent，必须由我们拉起来；用户确认 / 取消后再回来一次 SUCCESS / FAILURE。
     */
    private fun installViaSession() {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(listenInstaller().intentSender)
            }
            // commit 之后开始数秒：20 秒内连「请确认安装」都没回来，就换一条路走。
            armWatchdog()
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** 注册安装结果回执；返回给 [PackageInstaller.Session.commit] 的 PendingIntent。 */
    private fun listenInstaller(): PendingIntent {
        stopInstallerReceiver()
        val action = "${activity.packageName}.updates.INSTALL_RESULT"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                disarmWatchdog()
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // 系统要用户点一下「安装」：把确认界面拉起来，回执继续等。
                        val confirm = if (Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        val opened = confirm != null && runCatching {
                            activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                        if (!opened) { stopInstallerReceiver(); fallbackInstall() }
                        return
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        ready = false
                        updateStatus("新版已安装完成")
                    }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> updateStatus("安装已取消")
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                        // 系统明确说「被拦下」：多半是「安装未知应用」没开。把用户送到那个开关上，
                        // 比再试一遍隐式入口有用；开关页也打不开时才落到兜底。
                        val opened = openInstallPermission()
                        stopInstallerReceiver()
                        if (opened) {
                            updateStatus("请先在系统设置里允许安装未知应用，再回到这里点「安装新版」")
                            openStatus(anchor, "关闭", startDownload = false)
                        } else {
                            // 电视把这页设置也裁掉了：不能僵在这里，直接给能落地的装法。
                            fallbackInstall("系统阻止了安装，而且这台电视没有「安装未知应用」的设置页。")
                        }
                        return
                    }
                    else -> {
                        stopInstallerReceiver()
                        // 系统这一句英文里其实写着真正的病因。能翻译的就翻译成出路，
                        // 翻不出来的才退回「再交给安装器试一次」。
                        val kind = when {
                            message.contains("INCOMPATIBLE", true) ||
                                message.contains("signature", true) ||
                                message.contains("已安装", true) -> Blocker.SIGNATURE
                            message.contains("INVALID_APK", true) ||
                                message.contains("PARSE", true) ||
                                message.contains("解析", true) -> Blocker.DAMAGED
                            message.contains("STORAGE", true) ||
                                message.contains("空间", true) -> Blocker.STORAGE
                            message.contains("DOWNGRADE", true) ||
                                message.contains("OLDER", true) -> Blocker.OLDER
                            else -> Blocker.NONE
                        }
                        if (kind != Blocker.NONE) {
                            updateStatus("安装没有完成${if (message.isBlank()) "" else "：$message"}")
                            showInstallHelp(Check(kind))
                            return
                        }
                        updateStatus("系统安装未完成${if (message.isBlank()) "" else "：$message"}")
                        fallbackInstall(if (message.isBlank()) "" else "系统回报：$message")
                        return
                    }
                }
                stopInstallerReceiver()
            }
        }
        installerReceiver = receiver
        // 两参版本：targetSdk 29 在 Android 13/14 上仍允许（导出标志只对 targetSdk 34+ 强制），
        // 而回执是系统安装器发回来的，不能标成「仅本应用」。
        runCatching { activity.registerReceiver(receiver, IntentFilter(action)) }
        val intent = Intent(action).setPackage(activity.packageName)
        // 状态是通过 fill-in extras 送回来的，Android 12 起必须允许发送方补字段。
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(activity, 0x5A17, intent, flags)
    }

    /** 打开「允许安装未知应用」开关页；26 以下退到「安全」设置。打不开返回 false。 */
    private fun openInstallPermission(): Boolean {
        val intents = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= 26) intents += Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")
        )
        intents += Intent(Settings.ACTION_SECURITY_SETTINGS)
        return intents.any { intent ->
            runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }

    private fun stopInstallerReceiver() {
        disarmWatchdog()
        installerReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        installerReceiver = null
    }

    /**
     * 兜底：依次试系统安装器的几种隐式入口（`ACTION_INSTALL_PACKAGE` 在老电视上比
     * `ACTION_VIEW` 更常见），全都落不到时把包复制到「文件管理器看得到」的位置并告诉用户路径。
     */
    private fun fallbackInstall(note: String = "") {
        stopInstallerReceiver()
        val uri = runCatching {
            FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        }.getOrNull()
        val intents = mutableListOf<Intent>()
        if (uri != null) {
            @Suppress("DEPRECATION")
            intents += Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            intents += Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (Build.VERSION.SDK_INT < 24) {
            intents += Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive")
        }
        for (intent in intents) {
            intent.clipData = ClipData.newRawUri("更新安装包", intent.data)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { activity.startActivity(intent) }.isSuccess) {
                updateStatus("已交给系统安装器，请按屏幕提示确认")
                return
            }
        }
        permissionRequested = false
        // 走到这里说明「交给系统」这条路已经全部试完：不再弹一行状态字（没人会去读），
        // 直接给能落地的两件事——包存到哪儿、下一步在电视上点哪儿。
        // 4 MB 的复制放后台：主线程写盘会在遥控器按下的那一帧卡住。
        saveForManualInstall(note)
    }

    /**
     * 把包复制到用户找得到的地方，返回给用户看的路径。
     *
     * 三条路按系统走，前一条写不进去才试下一条：
     * 1. **公共「下载」目录直接写**——Android 9 及以下成立；本应用 targetSdk 29 且开了
     *    `requestLegacyExternalStorage`，10 上也仍然成立（传统存储模式下 `WRITE_EXTERNAL_STORAGE`
     *    覆盖整个 `/sdcard`）；
     * 2. **MediaStore 的 Downloads 集合**（10+）——不需要任何存储权限，文件管理器里看得到；
     * 3. **应用自己的外部目录**——文件管理器也能进 `Android/data/.../files`。
     *
     * 长度必须与原包一致才算成功：电视上「文件写了一半」和「写完」在界面上没有区别，
     * 只有长度能当场判出来。
     */
    private fun exportApk(): String? {
        val version = available?.versionName ?: readyName.ifBlank { BuildConfig.VERSION_NAME }
        val name = "HGTV_$version.apk"
        val download = runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        if (download != null) {
            val target = File(download, name)
            val ok = runCatching {
                download.mkdirs()
                apk.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            }.isSuccess
            if (ok && target.length() == apk.length()) return target.absolutePath
        }
        if (Build.VERSION.SDK_INT >= 29) exportViaMediaStore(name)?.let { return it }
        val own = runCatching { activity.getExternalFilesDir(null) }.getOrNull()
        if (own != null) {
            val target = File(own, name)
            val ok = runCatching {
                own.mkdirs()
                apk.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            }.isSuccess
            if (ok && target.length() == apk.length()) return target.absolutePath
        }
        return null
    }

    /**
     * Android 10+：写进系统「下载」集合。
     *
     * 走 `MediaStore.Downloads` 不需要任何权限，卸载应用也不删文件，
     * 电视自带的文件管理器 / 电视管家都能直接看到并点开安装。
     */
    @androidx.annotation.RequiresApi(29)
    private fun exportViaMediaStore(name: String): String? {
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return null
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { resolver.update(uri, done, null, null) }
        return "内部存储/${Environment.DIRECTORY_DOWNLOADS}/$name"
    }
}
