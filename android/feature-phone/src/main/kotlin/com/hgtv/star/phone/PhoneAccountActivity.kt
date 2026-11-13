// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hgtv.star.core.AccountCredentials
import com.hgtv.star.core.QrLogin
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneSoft.applyWell
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneEdgeToEdge
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.tabBarInset
import com.hgtv.star.shared.account.AccountLogin
import com.hgtv.star.shared.data.accountCache
import com.hgtv.star.shared.ui.SoftStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机 / 平板端账号页：登录、查看当前账号、退出。
 *
 * ## 三条登录路（与 TV 端完全同一套协议实现）
 *
 * | 入口 | 走什么 | 为什么手机上还留着它 |
 * | --- | --- | --- |
 * | 扫码登录 | [QrLogin]（Passport Web 出码 + 1.1 秒轮询） | 手机上扫码更顺手：另一台设备拍一下，本机立刻登录 |
 * | 手机号登录 | [com.hgtv.star.core.PhoneLogin]（发码 → 验证码 → 可能的二次验证） | 手机本来就有键盘，直接敲比扫码少一步 |
 * | 导入账号参数 | `cookieStore.xml` / `sessionid` | 从官方 App 导出过一次之后，换机 / 重装不必再登录 |
 *
 * 三条路最后都汇到同一处：`AccountLogin.verify` 拉一次账号资料，挡住平台的「匿名降级」，
 * 通过了才写进书库。因此「登录成功但一条记录都没有」这种假成功不会出现。
 */
class PhoneAccountActivity : PhoneActivity() {

    private companion object {
        const val LOGIN_QR = 0
        const val LOGIN_PHONE = 1
        const val LOGIN_IMPORT = 2
        const val STATE_LOGIN_MODE = "phone_account_login_mode"
        val LOGIN_LABELS = listOf("扫码登录", "手机号登录", "导入账号参数")
    }

    private lateinit var root: FrameLayout
    private lateinit var body: LinearLayout
    private lateinit var title: TextView
    private lateinit var modeBar: LinearLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var landscape = false
    private var loginMode = LOGIN_QR

    /** 扫码登录的轮询对象：页面收起时必须停掉，否则它在后台一直问同一个 token。 */
    private var qr: QrLogin? = null
    private var qrTimer: Runnable? = null
    private var qrClosed = false
    /** 「换一张二维码」：指向当前那次 [startQr] 内部的出码函数。 */
    private var qrRefresh: (() -> Unit)? = null

    /** 手机号登录的表单状态：二次验证（上行短信）那一步要重新登录，需要它们。 */
    private var login: com.hgtv.star.core.PhoneLogin? = null
    private var pendingPhone = ""
    private var pendingCode = ""
    /** 手机号登录选中的区号（`PhoneRegion.labels` 的下标）：留在页面上，重进表单要沿用。 */
    private var regionIndex = com.hgtv.star.core.PhoneRegion.DEFAULT
    /** 区号选择弹窗：非空且 showing 时挡掉重复打开。 */
    private var regionDialog: PhoneDialog? = null
    private var cooldownUntil = 0L
    private var cooldownTick: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneStyle.ensure(this)
        phoneEdgeToEdge()
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        loginMode = savedInstanceState?.getInt(STATE_LOGIN_MODE, LOGIN_QR)
            ?.takeIf { it in LOGIN_LABELS.indices } ?: LOGIN_QR
        buildUi()
        // 书库没读完时 `account()` / `accountCache()` 会抛（`Library` 的所有读取都要求先 load）：
        // 等它读完再铺内容，避免一进页面就是「未登录」再跳成「已登录」。
        scope.launch {
            PhoneServices.awaitLibrary(this@PhoneAccountActivity)
            render()
        }
    }

    // ---------------------------------------------------------------- 骨架

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(PhoneStyle.background) }

        val status = if (landscape) 0 else statusBarHeight()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (landscape) {
                // 横屏沉浸式会隐藏状态栏，顶部登录方式需要留出触屏边缘的呼吸空间。
                setPadding(dp(12), dp(10), dp(12), dp(6))
            } else {
                setPadding(dp(16), status + dp(8), dp(16), dp(8))
            }
        }
        title = TextView(this).apply {
            text = "账号"
            setTextColor(PhoneStyle.text)
            phoneSize(if (landscape) 13 else 18)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        if (landscape) {
            bar.addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            modeBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bar.addView(modeBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12) })
            bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            buildModeBar()
        } else {
            bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (landscape) {
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(4), dp(20), dp(8))
            } else {
                setPadding(dp(16), dp(6), dp(16), tabBarInset() + dp(20))
            }
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (landscape) {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                applySoft(SoftStyle.RAISED, PhoneStyle.surfaceHigh, PhoneStyle.RADIUS_CARD, 5f)
            }
        }
        if (landscape) {
            outer.addView(body, LinearLayout.LayoutParams(
                landscapeCardWidth(), ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        } else {
            outer.addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        // 字体放大后二维码和说明会变高；横屏也允许滚动，避免底部两个按钮被推出屏幕。
        // 横屏仍用 fillViewport 让内容不足一屏时保持原来的居中舞台。
        val contentHost = ScrollView(this).apply {
            isFillViewport = landscape
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(outer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(contentHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { topMargin = if (landscape) dp(40) else status + dp(56) })

        setContentView(root)
        if (landscape) applyLandscapeImmersive()
    }

    private fun render() {
        stopQr()
        body.removeAllViews()
        val lib = PhoneServices.library(this)
        val account = runCatching { lib.account() }.getOrNull()
        val signedIn = account?.configured == true || PhoneServices.repository(this).signedIn
        if (landscape) {
            modeBar.visibility = if (signedIn) View.GONE else View.VISIBLE
            updateModeBar()
        }
        when {
            signedIn -> renderSignedIn()
            landscape -> renderLandscapeSignedOut()
            else -> renderSignedOut()
        }
    }

    // ---------------------------------------------------------------- 未登录

    private fun renderSignedOut() {
        body.addView(note("登录后，收藏与观看记录会与账号同步；不登录也能正常看，记录只保存在本机。"))
        body.addView(primaryButton("扫码登录") { renderQr() }, blockTop())
        body.addView(secondaryButton("手机号登录") { renderPhoneLogin() }, blockTop())
        body.addView(secondaryButton("导入账号参数") { showImportDialog() }, blockTop())
        body.addView(note(
            "导入支持官方 App 的 cookieStore.xml，或一个 sessionid。" +
                "参数只保存在本机，不会上传到任何服务器。"
        ), blockTop())
    }

    private fun buildModeBar() {
        modeBar.removeAllViews()
        LOGIN_LABELS.forEachIndexed { index, label ->
            val chip = TextView(this).apply {
                text = label
                phoneSize(11)
                gravity = Gravity.CENTER
                includeFontPadding = false
                minWidth = dp(78)
                minHeight = dp(24)
                setPadding(dp(8), 0, dp(8), 0)
                contentDescription = label
                setOnClickListener { selectLoginMode(index) }
            }
            modeBar.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)
            ).apply {
                if (index > 0) leftMargin = dp(8)
            })
        }
    }

    private fun updateModeBar() {
        if (!landscape || !::modeBar.isInitialized) return
        repeat(modeBar.childCount) { index ->
            val chip = modeBar.getChildAt(index) as? TextView ?: return@repeat
            val active = index == loginMode
            chip.setTextColor(if (active) PhoneStyle.accent else PhoneStyle.textDim)
            chip.applySoft(
                if (active) SoftStyle.INSET else SoftStyle.RAISED,
                PhoneStyle.surface,
                PhoneStyle.RADIUS_PILL,
                3f,
            )
            chip.contentDescription = LOGIN_LABELS[index] + if (active) "，当前方式" else ""
        }
    }

    private fun selectLoginMode(mode: Int) {
        if (!landscape || mode == loginMode) return
        loginMode = mode
        render()
    }

    private fun renderLandscapeSignedOut() {
        when (loginMode) {
            LOGIN_PHONE -> renderPhoneLogin()
            LOGIN_IMPORT -> renderImport()
            else -> renderQr()
        }
    }

    // ---------------------------------------------------------------- 已登录

    private fun renderSignedIn() {
        val lib = PhoneServices.library(this)
        // 内存那一份最新（刚刷新过），没有才读缓存。
        val cached = PhoneAccountSync.data ?: runCatching { lib.accountCache() }.getOrNull()?.data
        val profile = cached?.profile
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_CARD, 5f)
        }
        val avatarSize = dp(56)
        val avatar = FrameLayout(this).apply {
            applySoft(SoftStyle.RAISED, PhoneStyle.surfaceHigh, 26f, 4f)
        }
        val avatarImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            com.hgtv.star.shared.compat.clipToCircleCompat(this)
        }
        avatar.addView(avatarImage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val avatarUrl = profile?.avatar.orEmpty()
        if (avatarUrl.isNotBlank()) {
            PhoneServices.artwork(this).load(
                avatarImage,
                avatarUrl,
                widthPx = avatarSize,
                heightPx = avatarSize,
            )
        }
        card.addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize))

        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        names.addView(TextView(this).apply {
            text = profile?.nickname?.takeIf { it.isNotBlank() } ?: "已登录"
            setTextColor(PhoneStyle.text)
            phoneSize(17)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val sub = buildList {
            if (!profile?.displayId.isNullOrBlank()) add(profile?.displayId.orEmpty())
            profile?.fans?.let { add("粉丝 $it") }
            profile?.follows?.let { add("关注 $it") }
        }.joinToString(" · ")
        names.addView(TextView(this).apply {
            text = sub.ifBlank { "登录态有效" }
            setTextColor(PhoneStyle.textFaint)
            phoneSize(12)
            setPadding(0, dp(4), 0, 0)
        })
        if (!profile?.signature.isNullOrBlank()) {
            names.addView(TextView(this).apply {
                text = profile?.signature
                setTextColor(PhoneStyle.textDim)
                phoneSize(12)
                maxLines = 2
                setPadding(0, dp(4), 0, 0)
            })
        }
        card.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(card)

        body.addView(note(
            if (cached == null) "正在读取账号数据…" else "收藏 ${cached.favorites.size} · 历史 ${cached.history.size} · 点赞 ${cached.likes.size}"
        ), blockTop())
        body.addView(secondaryButton("刷新账号数据") { refreshAccount() }, blockTop())
        body.addView(secondaryButton("退出登录") { confirmSignOut() }, blockTop())
    }

    /**
     * 刷新账号数据。
     *
     * 走 [PhoneAccountSync.refresh]（与 TV 的 `loadAccount` 同一套：合并 → 滤掉本机删过的 →
     * 与本机进度对账 → 落盘 → 把本机更超前的那几条推回云端）。只拉不推的话，
     * 这台机器上更新的进度永远回不到云端。
     */
    private fun refreshAccount() {
        scope.launch {
            PhoneAccountSync.refresh(this@PhoneAccountActivity)
            render()
        }
    }

    private fun confirmSignOut() {
        PhoneDialog(this, "退出登录")
            .message("退出后本机记录仍保留；账号侧的收藏与历史不再显示。")
            .button("取消")
            .button("退出", danger = true) {
                // 先停心跳再清凭证：反过来的话清完还会再打一次，且可能把刚退掉的会话又确认回来。
                com.hgtv.star.shared.data.SessionBootstrap.onLogout()
                val lib = PhoneServices.library(this)
                runCatching { lib.clearAccount() }
                PhoneServices.repository(this).credentials = null
                PhoneAccountSync.clear()
                // 清完再把仓库对一次：`signedIn` / 搜索口令 / 注册 Cookie 都跟着这份书库走。
                PhoneIdentity.apply(this, lib)
                render()
            }
            .show()
    }

    // ---------------------------------------------------------------- 扫码

    private fun renderQr() {
        stopQr()
        body.removeAllViews()
        qrClosed = false
        val library=PhoneServices.library(this@PhoneAccountActivity)
        val session = QrLogin().apply {
            // 登录先走当前主设备；账号设备只有在登录响应带回不同设备时才成为新的主身份。
            device = runCatching {
                LinkedHashMap(library.deviceProfile()).apply { putAll(library.deviceIdentity()) }
            }.getOrDefault(emptyMap())
            useDeviceCookies(runCatching { library.registrationCookies() }.getOrDefault(emptyMap()))
            log = { line -> android.util.Log.i("HGTV-QRLOGIN", line) }
        }
        qr = session

        if (landscape) {
            body.addView(loginTitle("扫码登录", "用手机上的官方 App 扫一扫，手机确认后本机即登录"))
        }
        body.addView(note(
            if (landscape) "二维码 60 秒有效，过期会自动刷新。"
            else "用手机上的官方 App 扫一扫，手机确认后本机即登录。二维码 60 秒有效，过期会自动刷新。"
        ))

        val code = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "登录二维码"
            // 白底是功能需求（平台给的就是白底黑块 PNG），因此不套主题色，只留一圈内边距。
            background = roundedBackground(android.graphics.Color.WHITE, dp(12).toFloat())
            val inset = dp(10)
            setPadding(inset, inset, inset, inset)
        }
        // 二维码边长：常规档是 220dp，但字号调大后 `dp()` 会跟着放大（最大档约 990px），
        // 直接铺上去会超出屏幕、被右侧裁掉——用户看到的就是「二维码变形 / 只显示一部分」。
        // 这里按可用宽度封顶，任何字号下都完整落在屏幕内。
        val qrSide = if (landscape) {
            val available = (landscapeCardWidth() - dp(36)).coerceAtLeast(dp(120))
            minOf(
                dp(120),
                (available * .72f).toInt(),
                (resources.displayMetrics.heightPixels * .30f).toInt(),
            ).coerceAtLeast(dp(72))
        } else {
            val available = resources.displayMetrics.widthPixels - dp(16) * 2
            minOf(dp(220), (available * .92f).toInt()).coerceAtLeast(dp(120))
        }
        body.addView(FrameLayout(this).apply {
            addView(code, FrameLayout.LayoutParams(qrSide, qrSide, Gravity.CENTER))
        }, blockTop())

        val state = TextView(this).apply {
            text = "正在获取二维码…"
            setTextColor(PhoneStyle.textDim)
            phoneSize(13)
            gravity = Gravity.CENTER
        }
        body.addView(state, blockTop())
        if (landscape) {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            actions.addView(secondaryButton("换一张二维码") { qrRefresh?.invoke() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(secondaryButton("手机号登录") { selectLoginMode(LOGIN_PHONE) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                })
            body.addView(actions, blockTop())
        } else {
            body.addView(secondaryButton("换一张二维码") { qrRefresh?.invoke() }, blockTop())
            body.addView(secondaryButton("返回") { render() }, blockTop())
        }

        startQr(session, code, state)
    }

    /**
     * 出码 + 轮询。
     *
     * 排下一次轮询放在**上一次结果落地之后**：网络慢时不会堆出多条并发请求，
     * 也就不会出现「同一个 token 被问两遍、状态来回跳」。
     */
    private fun startQr(session: QrLogin, code: ImageView, state: TextView) {
        qrTimer?.let { root.removeCallbacks(it) }
        qrTimer = null
        var token = ""

        fun paint(fresh: QrLogin.Code) {
            val bitmap = runCatching { BitmapFactory.decodeByteArray(fresh.png, 0, fresh.png.size) }.getOrNull()
            if (bitmap != null) code.setImageBitmap(bitmap)
            token = fresh.token
        }

        // 轮询体与排期互相引用：先声明空实现，下面赋值（与 TV 的 `poll` 同一种写法）。
        var poll: () -> Unit = {}
        fun schedule(delayMs: Long) {
            val run = Runnable { poll() }
            qrTimer = run
            root.postDelayed(run, delayMs)
        }

        fun fetchCode(manual: Boolean) {
            qrTimer?.let { root.removeCallbacks(it) }
            qrTimer = null
            token = ""
            state.text = if (manual) "正在刷新二维码…" else "正在获取二维码…"
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { runCatching { session.fetchCode() }.getOrNull() }
                if (qrClosed || isFinishing) return@launch
                if (fresh == null) {
                    state.text = session.lastError.ifBlank { "二维码获取失败，正在重试…" }
                    // 面板还开着就一直试；收起页面时 [stopQr] 会把它摘掉。
                    schedule(QrLogin.POLL_MS * 4)
                    return@launch
                }
                paint(fresh)
                state.text = "请用官方 App 扫码"
                schedule(QrLogin.POLL_MS)
            }
        }

        poll = poll@{
            if (qrClosed || isFinishing) return@poll
            val current = token
            if (current.isBlank()) return@poll
            scope.launch {
                val outcome = withContext(Dispatchers.IO) { runCatching { session.check(current) }.getOrNull() }
                    ?: QrLogin.Outcome.Failed("网络异常")
                if (qrClosed || isFinishing) return@launch
                // 期间手动刷新过 / 平台已经换了一张码：这一份结果属于旧码，丢掉。
                if (token != current) return@launch
                when (outcome) {
                    is QrLogin.Outcome.Waiting -> state.text = "请用官方 App 扫码"
                    is QrLogin.Outcome.Scanned -> state.text = "已扫码，请在手机上确认"
                    is QrLogin.Outcome.Renewed -> {
                        // 平台在 `expired` 的同一次响应里下发了新码：原地换掉，接着轮询。
                        paint(outcome.code)
                        state.text = "二维码已过期，已自动刷新，请重新扫码"
                    }
                    is QrLogin.Outcome.Confirmed -> {
                        state.text = "登录成功，正在验证…"
                        stopQr()
                        finishLogin(outcome.credentials, "扫码登录")
                        return@launch
                    }
                    is QrLogin.Outcome.Failed ->
                        state.text = outcome.message.ifBlank { "扫码状态读取失败，正在重试…" }
                }
                // 失败（断网 / 网关挡掉）不是终局：隔得久一点再问，别按 1.1 秒硬敲。
                schedule(if (outcome is QrLogin.Outcome.Failed) 3000L else QrLogin.POLL_MS)
            }
        }

        fetchCode(manual = false)
        // 「换一张二维码」按钮走同一条出码逻辑。
        qrRefresh = { fetchCode(manual = true) }
    }

    private fun stopQr() {
        qrClosed = true
        qrTimer?.let { root.removeCallbacks(it) }
        qrTimer = null
        qr = null
        qrRefresh = null
    }

    // ---------------------------------------------------------------- 手机号

    private fun renderPhoneLogin() {
        stopQr()
        body.removeAllViews()
        val library=PhoneServices.library(this@PhoneAccountActivity)
        val session = com.hgtv.star.core.PhoneLogin().apply {
            device = runCatching {
                LinkedHashMap(library.deviceProfile()).apply { putAll(library.deviceIdentity()) }
            }.getOrDefault(emptyMap())
            useDeviceCookies(runCatching { library.registrationCookies() }.getOrDefault(emptyMap()))
            log = { line -> android.util.Log.i("HGTV-LOGIN", line) }
        }
        login = session

        if (landscape) {
            body.addView(loginTitle("手机号登录", "验证码由平台短信发出，未注册手机号会自动创建账号"))
        }
        body.addView(note(
            if (landscape) "本机不会保存验证码。"
            else "未注册的手机号会自动创建账号。验证码由平台短信发出，本机不会保存它。"
        ))

        // 区号 + 手机号同一行，与电视端登录卡完全一致（左边区号按钮、右边输入框）。
        //
        // 手机端原先**没有这一栏**：号码直接交给 `e164Phone` 猜——只有「11 位且以 1 开头」
        // 才当中国大陆补 `+86`，其余一律 `+数字`。香港 8 位号码于是被拼成 `+91234567`
        //（少了 `852`），发码必然失败。现在与电视端走同一份 `PhoneRegion.normalize`。
        // 区号与号码**在同一个凹槽里**：左边区号按钮、中间一条细分隔线、右边输入框。
        val region = regionButton(com.hgtv.star.core.PhoneRegion.dialOf(regionIndex))
        region.setOnClickListener {
            // 连点两下不要叠出两个弹窗（电视端那枚 `PopupWindow` 也有同样的判断）。
            if (regionDialog?.isShowing == true) return@setOnClickListener
            val dialog = PhoneDialog(this, "选择国家 / 地区")
                .choices(com.hgtv.star.core.PhoneRegion.labels, regionIndex) { index ->
                    regionIndex = index
                    region.text = "${com.hgtv.star.core.PhoneRegion.dialOf(index)} ▾"
                }
            regionDialog = dialog
            dialog.show { regionDialog = null }
        }
        val phone = field(
            "手机号", "请输入手机号", InputType.TYPE_CLASS_PHONE, 20, leading = region,
        )
        // 从「二次验证 → 重新发码」回到这一页时预填上一次的号码：
        // 整串 E.164 塞进输入框会让框里出现 `+852`，提交时再拼一次就变成 `+852+852…`。
        if (pendingPhone.isNotBlank()) {
            val (index, local) = com.hgtv.star.core.PhoneRegion.split(pendingPhone)
            regionIndex = index
            region.text = "${com.hgtv.star.core.PhoneRegion.dialOf(index)} ▾"
            phone.input.setText(local)
            phone.input.setSelection(local.length)
        }
        body.addView(phone, blockTop())

        /** 手机号归一：与电视端同一个函数（带 `+` 的原样送出，否则按所选区号拼 E.164）。 */
        fun normalizedNumber(): String? =
            com.hgtv.star.core.PhoneRegion.normalize(
                phone.input.text?.toString().orEmpty(),
                regionIndex,
            )

        val state = TextView(this).apply {
            setTextColor(PhoneStyle.textDim)
            phoneSize(12)
            visibility = View.GONE
        }

        lateinit var send: TextView
        fun setState(text: String, show: Boolean = text.isNotEmpty()) {
            state.text = text
            state.visibility = if (show) View.VISIBLE else View.GONE
        }
        fun refreshCooldown() {
            val left = ((cooldownUntil - System.currentTimeMillis()) / 1000L).toInt()
            if (left <= 0) {
                cooldownUntil = 0
                send.isEnabled = true
                send.alpha = 1f
                send.text = "获取验证码"
                return
            }
            send.isEnabled = false
            send.alpha = .45f
            send.text = if (landscape) "$left 秒\n后可重发" else "$left 秒后可重发"
            val tick = object : Runnable { override fun run() = refreshCooldown() }
            cooldownTick = tick
            root.postDelayed(tick, 1000L)
        }

        /**
         * 真的发一条验证码（用户已在 [PhoneConsent.confirmSms] 里点过「同意」）。
         *
         * 抽成局部函数是因为它有两个入口：确认弹窗的「同意」与二次验证之后的「重发」。
         * 两处必须是同一段逻辑，否则冷却时间、`pendingPhone` 这些状态会在其中一条路上漏写。
         */
        fun sendCodeNow(number: String) {
            cooldownTick?.let { root.removeCallbacks(it) }
            send.isEnabled = false
            send.alpha = .45f
            send.text = "发送中…"
            setState("正在发送验证码…")
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { session.sendCode(number) { profile -> registerForLogin(profile) } }.getOrNull()
                }
                when (result) {
                    is com.hgtv.star.core.PhoneLogin.Result.Sent -> {
                        pendingPhone = number
                        setState("验证码已发送到 $number")
                        // 平台自己也有 retry_time（实测 60 秒），取同一档避免撞上它的限流。
                        cooldownUntil = System.currentTimeMillis() + 60_000L
                        cooldownTick?.let { root.removeCallbacks(it) }
                        refreshCooldown()
                    }
                    is com.hgtv.star.core.PhoneLogin.Result.NeedUpsms -> {
                        pendingPhone = number
                        renderUpsms(session, result.number, result.content)
                    }
                    is com.hgtv.star.core.PhoneLogin.Result.LoggedIn -> {
                        // 发码这一步理论上不会直接登录成功；真发生了就照常落地，不吞掉。
                        finishLogin(result.credentials, "手机号登录")
                    }
                    is com.hgtv.star.core.PhoneLogin.Result.Failed -> {
                        setState(result.message)
                        send.isEnabled = true
                        send.alpha = 1f
                        send.text = "获取验证码"
                    }
                    null -> {
                        setState("网络异常，请稍后重试")
                        send.isEnabled = true
                        send.alpha = 1f
                        send.text = "获取验证码"
                    }
                }
            }
        }

        send = secondaryButton("获取验证码") {
            val number = normalizedNumber()
            if (number == null) {
                setState("请先填手机号")
                return@secondaryButton
            }
            // **每一次发码都要确认**（与电视端 `confirmSmsConsent` 同一条规则）：
            // 进「我的」页确认过「这是第三方客户端」，不等于用户同意在这一次把手机号发出去。
            // 点「不同意」时一个请求都不会发出。
            PhoneConsent.confirmSms(this) { sendCodeNow(number) }
        }
        if (landscape) {
            send.background = null
            send.setTextColor(PhoneStyle.accent)
            send.phoneSize(10)
            send.setPadding(0, 0, 0, 0)
            send.includeFontPadding = false
            send.maxLines = 2
            send.gravity = Gravity.CENTER
        }
        val code = field(
            "验证码",
            "收到短信后填入",
            InputType.TYPE_CLASS_NUMBER,
            8,
            trailing = send.takeIf { landscape },
        )
        body.addView(code, blockTop())
        body.addView(state, blockTop())

        val loginButton = primaryButton("登录") {
            val number = normalizedNumber()
            val value = code.input.text?.toString()?.trim().orEmpty()
            if (number == null) { setState("请先填手机号"); return@primaryButton }
            if (value.isEmpty()) { setState("请先填验证码"); return@primaryButton }
            pendingPhone = number
            pendingCode = value
            setState("正在登录…")
            doLogin(session, number, value)
        }
        if (!landscape) body.addView(send, blockTop())
        body.addView(loginButton, blockTop())
        if (!landscape) body.addView(secondaryButton("返回") { render() }, blockTop())
    }

    private fun doLogin(session: com.hgtv.star.core.PhoneLogin, number: String, value: String, ticket: String = "") {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { session.login(number, value, ticket) }.getOrNull() }
            when (result) {
                is com.hgtv.star.core.PhoneLogin.Result.LoggedIn ->
                    finishLogin(result.credentials, "手机号登录")
                is com.hgtv.star.core.PhoneLogin.Result.NeedUpsms ->
                    renderUpsms(session, result.number, result.content)
                is com.hgtv.star.core.PhoneLogin.Result.Failed -> toast(result.message)
                else -> toast("登录失败，请稍后重试")
            }
        }
    }

    /**
     * 平台的二次验证：要求用本机号码发一条上行短信。
     *
     * 用户发完点「我已发送」→ [com.hgtv.star.core.PhoneLogin.verifyUpsms] 轮询票据；
     * 拿到票据后再用刚才那条验证码登录一次（平台在二次验证通过后才认它）。
     */
    private fun renderUpsms(session: com.hgtv.star.core.PhoneLogin, number: String, content: String) {
        body.removeAllViews()
        body.addView(note("平台要求再做一次验证：用本机号码把下面这条短信发出去，然后回来点「我已发送」。"))
        body.addView(note("收件号码：$number\n短信内容：$content"), blockTop())
        val state = TextView(this).apply {
            setTextColor(PhoneStyle.textDim)
            phoneSize(12)
            visibility = View.GONE
        }
        body.addView(state, blockTop())
        body.addView(primaryButton("我已发送") {
            state.visibility = View.VISIBLE
            state.text = "正在确认…"
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { session.verifyUpsms() }.getOrNull() }
                when (result) {
                    is com.hgtv.star.core.PhoneLogin.UpsmsResult.Ticket -> {
                        state.text = "验证通过，正在登录…"
                        // 二次验证通过之后平台要求重新发一条验证码，再用新码登录。
                        val resent = withContext(Dispatchers.IO) {
                            runCatching { session.sendCode(pendingPhone) { profile -> registerForLogin(profile) } }.getOrNull()
                        }
                        when (resent) {
                            is com.hgtv.star.core.PhoneLogin.Result.Sent -> {
                                pendingCode = ""
                                renderPhoneLogin()
                                toast("验证通过，新的验证码已发出")
                            }
                            is com.hgtv.star.core.PhoneLogin.Result.Failed -> toast(resent.message)
                            else -> renderPhoneLogin()
                        }
                    }
                    is com.hgtv.star.core.PhoneLogin.UpsmsResult.Failed -> state.text = result.message
                    else -> state.text = "平台还没看到这条短信，稍等几秒再点一次。"
                }
            }
        }, blockTop())
        if (!landscape) body.addView(secondaryButton("返回") { render() }, blockTop())
    }

    /**
     * 登录时注册设备（与 TV 的 `registerForPhoneLogin` 同一条路）。
     *
     * 注册请求必须由**本机**发：登录协议只做界面，设备身份要靠它下发的那一对 id 与 Cookie。
     */
    private fun registerForLogin(profile: Map<String, String>): com.hgtv.star.core.DeviceRegister.Registered? {
        val repo = PhoneServices.repository(this)
        return runCatching {
            com.hgtv.star.shared.data.DeviceRegistration.registerLoginDevice(
                PhoneServices.library(this),
                repo,
                profile,
            )
        }.getOrNull()
    }

    // ---------------------------------------------------------------- 导入

    /** 横屏的第三个登录页：不再用弹窗，直接作为顶部“导入账号参数”模式的内容。 */
    private fun renderImport() {
        body.removeAllViews()
        body.addView(loginTitle("导入账号参数", "粘贴官方 App 的 cookieStore.xml，或一个 sessionid"))
        body.addView(note(
            "参数只保存在本机，不会上传到任何服务器。导入后会先验证登录态，验证失败不会覆盖当前账号。"
        ), blockTop())
        val input = EditText(this).apply {
            hint = "粘贴 cookieStore.xml 全文，或一个 sessionid"
            setHintTextColor(PhoneStyle.textFaint)
            setTextColor(PhoneStyle.text)
            phoneSize(if (landscape) 13 else 15)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 8
            setPadding(dp(14), dp(12), dp(14), dp(12))
            applyWell(PhoneStyle.well, 14f, 4f)
        }
        body.addView(input, blockTop())
        body.addView(primaryButton("导入账号参数") {
            importText(input.text?.toString().orEmpty())
        }, blockTop())
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "粘贴 cookieStore.xml 全文，或一个 sessionid"
            setHintTextColor(PhoneStyle.textFaint)
            setTextColor(PhoneStyle.text)
            phoneSize(13)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = if (landscape) 2 else 4
            maxLines = if (landscape) 4 else 8
            setPadding(dp(14), dp(12), dp(14), dp(12))
            applyWell(PhoneStyle.well, 14f, 4f)
        }
        PhoneDialog(this, "导入账号参数")
            .content(input)
            .button("取消")
            .button("导入", primary = true) { importText(input.text?.toString().orEmpty()) }
            .show()
    }

    private fun importText(text: String) {
        if (text.isBlank()) return
        toast("正在验证账号参数…")
        scope.launch {
            val repo = PhoneServices.repository(this@PhoneAccountActivity)
            val lib = PhoneServices.library(this@PhoneAccountActivity)
            val result = withContext(Dispatchers.IO) {
                runCatching { AccountLogin.importText(text, repo, lib) }
            }
            result.onSuccess { credentials ->
                lib.saveAccount(credentials)
                PhoneIdentity.apply(this@PhoneAccountActivity, lib)
                // 导入的会话只有 cookie、没有 `X-Tt-Token`：立刻打一次 boot 心跳，
                // 让服务端在响应头里把 token 发回来（拦截器收到即落盘）。
                com.hgtv.star.shared.data.SessionBootstrap.onAppStart(force = true)
                toast("账号参数已导入")
                render()
            }.onFailure { failure ->
                toast(failure.message.orEmpty().ifBlank { "导入失败" })
            }
        }
    }

    // ---------------------------------------------------------------- 落地

    /**
     * 三条登录路的共同收尾：先确认这份会话真能用，再写进书库。
     *
     * 「协议说成功」不等于「登录上了」：平台对无效会话不报错，只把请求降级成匿名设备。
     * 不验一次的话，用户会看到一个「已登录」但一条记录都没有的页面。
     */
    private fun finishLogin(credentials: AccountCredentials, how: String) {
        scope.launch {
            val repo = PhoneServices.repository(this@PhoneAccountActivity)
            val lib = PhoneServices.library(this@PhoneAccountActivity)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val prepared=com.hgtv.star.shared.data.DeviceRegistration.prepareLogin(
                        lib,repo,credentials,
                    )
                    val problem=AccountLogin.verify(prepared,repo)
                    if(problem!=null) throw IllegalStateException(problem)
                    prepared
                }
            }
            val problem = result.exceptionOrNull()?.message
            if (problem != null) {
                toast(problem)
                render()
                return@launch
            }
            val prepared=result.getOrThrow()
            lib.saveAccount(prepared)
            // **立刻把新凭据灌进仓库**：不灌的话 `repository.signedIn` 还是 false，
            // 紧接着那次 `PhoneAccountSync.refresh` 会走「没登录」分支直接返回，
            // 用户看到的就是「登录成功但一条记录都没有」。
            PhoneIdentity.apply(this@PhoneAccountActivity, lib)
            com.hgtv.star.shared.data.SessionBootstrap.onAppStart(force = true)
            toast("${how}成功")
            // 顺手把账号数据拉一份回来并对账：回到「我的」页时不该还是空的，
            // 而且本机更超前的进度要借这次机会推回云端。
            PhoneAccountSync.refresh(this@PhoneAccountActivity)
            render()
        }
    }

    // ---------------------------------------------------------------- 控件

    /**
     * 一行「标签 + 凹槽输入框」。
     *
     * [leading] 非空时把它**并进同一个凹槽**（手机号那一行的区号按钮）：左边是它，
     * 中间一条细分隔线，右边才是输入框——读起来是一个控件，而不是两个并排的框。
     * [trailing] 同理放在右侧（验证码输入框里的「获取验证码」）。
     */
    private fun field(
        label: String,
        hintText: String,
        type: Int,
        maxLength: Int,
        leading: View? = null,
        trailing: View? = null,
    ): LinearLayout {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = label
            setTextColor(PhoneStyle.textDim)
            phoneSize(if (landscape) 10 else 12)
            setPadding(dp(4), 0, 0, dp(if (landscape) 2 else 6))
        })
        val input = EditText(this).apply {
            hint = hintText
            setHintTextColor(PhoneStyle.textFaint)
            setTextColor(PhoneStyle.text)
            phoneSize(if (landscape) 12 else 15)
            setSingleLine()
            background = null
            inputType = type
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            setPadding(dp(14), 0, dp(14), 0)
        }
        val box = FrameLayout(this).apply {
            applyWell(PhoneStyle.well, 18f, 4f)
        }
        val leadingWidth = if (leading == null) 0 else 62
        val trailingWidth = if (trailing == null) 0 else 62
        val leftInset = if (leading == null) 6 else 6 + leadingWidth + 2 + 1 + 8
        val rightInset = if (trailing == null) 6 else 6 + trailingWidth + 2 + 1 + 8
        if (leading != null) {
            // 区号占固定宽度（最长的 `+886 ▾` 也放得下），输入框吃剩下的全部。
            box.addView(leading, FrameLayout.LayoutParams(
                dp(leadingWidth), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START
            ).apply { leftMargin = dp(6) })
            // 一条细竖线：区号与号码是两件事，但共用一块凹槽。
            // 用主题的 `separator` 而不是写死的白色——浅色主题下白线是看不见的。
            box.addView(View(this).apply { setBackgroundColor(PhoneStyle.separator) },
                FrameLayout.LayoutParams(dp(1), dp(18), Gravity.CENTER_VERTICAL).apply {
                    leftMargin = dp(6 + leadingWidth + 2)
                })
        }
        if (trailing != null) {
            box.addView(View(this).apply { setBackgroundColor(PhoneStyle.separator) },
                FrameLayout.LayoutParams(dp(1), dp(18), Gravity.CENTER_VERTICAL or Gravity.END).apply {
                    rightMargin = dp(6 + trailingWidth + 2)
                })
            box.addView(trailing, FrameLayout.LayoutParams(
                dp(trailingWidth), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
            ).apply { rightMargin = dp(6) })
        }
        input.setPadding(dp(6), 0, dp(14), 0)
        box.addView(input, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            leftMargin = dp(leftInset)
            rightMargin = dp(rightInset)
        })
        column.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape) 26 else 48)
        ))
        column.tag = input
        return column
    }

    private val LinearLayout.input: EditText get() = tag as EditText

    private fun primaryButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(PhoneStyle.onLit)
            phoneSize(if (landscape) 12 else 15)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(if (landscape) 6 else 13), dp(16), dp(if (landscape) 6 else 13))
            applySoft(SoftStyle.RAISED, PhoneStyle.lit, PhoneStyle.RADIUS_PILL, 4f)
            setOnClickListener { onClick() }
        }

    private fun secondaryButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(PhoneStyle.text)
            phoneSize(if (landscape) 12 else 15)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(if (landscape) 6 else 13), dp(16), dp(if (landscape) 6 else 13))
            applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f)
            setOnClickListener { onClick() }
        }

    /**
     * 区号按钮：**并进手机号那个凹槽里**（见 [field] 的 `leading`），因此自己不画底板。
     *
     * 与电视端 `LoginForm.RegionButton` 同一个位置与含义（手机号输入框左边）。
     */
    private fun regionButton(dial: String): TextView =
        TextView(this).apply {
            text = "$dial ▾"
            setTextColor(PhoneStyle.text)
            phoneSize(13)
            gravity = Gravity.CENTER
            // 常态透明（底板由外层凹槽提供），按下时压深一档：
            // 没有按下态的话，点上去既没反馈、也不知道有没有点中。
            background = com.hgtv.star.phone.ui.PhoneSheet.pillBackground(
                android.graphics.Color.TRANSPARENT,
                PhoneStyle.wellDeep,
                dp(12).toFloat(),
            )
            isClickable = true
        }

    private fun note(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(PhoneStyle.textFaint)
            phoneSize(if (landscape) 11 else 12)
            setLineSpacing(dp(if (landscape) 2 else 3).toFloat(), 1f)
        }

    private fun blockTop() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(if (landscape) 4 else 12) }

    private fun loginTitle(heading: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@PhoneAccountActivity).apply {
                text = heading
                setTextColor(PhoneStyle.text)
                phoneSize(if (landscape) 16 else 18)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@PhoneAccountActivity).apply {
                text = subtitle
                setTextColor(PhoneStyle.textDim)
                phoneSize(if (landscape) 10 else 11)
                setPadding(0, dp(if (landscape) 2 else 3), 0, 0)
            })
        }

    private fun landscapeCardWidth(): Int {
        val available = (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(dp(220))
        return minOf(dp(360), (available * .64f).toInt())
            .coerceAtLeast(minOf(dp(200), available))
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    /** 横屏账号页按 TV 登录页处理：隐藏系统状态栏与导航栏，只保留页面自己的紧凑模式栏。 */
    @Suppress("DEPRECATION")
    private fun applyLandscapeImmersive() {
        if (!landscape) return
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.decorView.post {
            if (!landscape || isFinishing || isDestroyed) return@post
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (landscape) applyLandscapeImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && landscape) applyLandscapeImmersive()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_LOGIN_MODE, loginMode)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        // 页面不在前台时停掉扫码轮询：它在后台问下去没有意义，还会让手机发热。
        stopQr()
        cooldownTick?.let { root.removeCallbacks(it) }
    }

    override fun onDestroy() {
        stopQr()
        cooldownTick?.let { root.removeCallbacks(it) }
        scope.cancel()
        super.onDestroy()
    }
}
