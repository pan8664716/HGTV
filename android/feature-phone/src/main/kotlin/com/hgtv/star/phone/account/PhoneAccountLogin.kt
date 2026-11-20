// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.graphics.BitmapFactory
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.QrLogin
import com.hgtv.star.shared.data.registrationCookies
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PhoneAccountActivity.renderQr() {
    stopQr()
    body.removeAllViews()
    qrClosed = false
    val library=PhoneServices.library(this)
    val session = QrLogin().apply {
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
        contentDescription = UiText.get(com.hgtv.star.shared.R.string.ui_0182)
        background = roundedBackground(android.graphics.Color.WHITE, dp(12).toFloat())
        val inset = dp(10)
        setPadding(inset, inset, inset, inset)
    }
    val qrSide = if (landscape) {
        val available = (landscapeCardWidth() - dp(36)).coerceAtLeast(dp(120))
        minOf(dp(120), (available * .72f).toInt(), (resources.displayMetrics.heightPixels * .30f).toInt())
            .coerceAtLeast(dp(72))
    } else {
        val available = resources.displayMetrics.widthPixels - dp(16) * 2
        minOf(dp(220), (available * .92f).toInt()).coerceAtLeast(dp(120))
    }
    body.addView(FrameLayout(this).apply {
        addView(code, FrameLayout.LayoutParams(qrSide, qrSide, Gravity.CENTER))
    }, blockTop())
    val state = TextView(this).apply {
        text = UiText.get(com.hgtv.star.shared.R.string.ui_0158)
        setTextColor(PhoneStyle.textDim)
        phoneSize(13)
        gravity = Gravity.CENTER
    }
    body.addView(state, blockTop())
    if (landscape) {
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(secondaryButton("换一张二维码") { qrRefresh?.invoke() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(secondaryButton("手机号登录") { selectLoginMode(PhoneAccountActivity.LOGIN_PHONE) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        body.addView(actions, blockTop())
    } else {
        body.addView(secondaryButton("换一张二维码") { qrRefresh?.invoke() }, blockTop())
        body.addView(secondaryButton("返回") { render() }, blockTop())
    }
    startQr(session, code, state)
}

private fun PhoneAccountActivity.startQr(session: QrLogin, code: ImageView, state: TextView) {
    qrTimer?.let { root.removeCallbacks(it) }
    qrTimer = null
    var token = ""
    fun paint(fresh: QrLogin.Code) {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(fresh.png, 0, fresh.png.size) }.getOrNull()
        if (bitmap != null) code.setImageBitmap(bitmap)
        token = fresh.token
    }
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
        state.text = if (manual) UiText.get(com.hgtv.star.shared.R.string.ui_0145) else UiText.get(com.hgtv.star.shared.R.string.ui_0158)
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { runCatching { session.fetchCode() }.getOrNull() }
            if (qrClosed || isFinishing) return@launch
            if (fresh == null) {
                state.text = session.lastError.ifBlank { UiText.get(com.hgtv.star.shared.R.string.ui_0021) }
                schedule(
                    QrLogin.POLL_MS *
                        com.hgtv.star.core.AppConfig.Ui.QR_REFRESH_POLL_MULTIPLIER,
                )
                return@launch
            }
            paint(fresh)
            state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0224)
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
            if (token != current) return@launch
            when (outcome) {
                is QrLogin.Outcome.Waiting -> state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0224)
                is QrLogin.Outcome.Scanned -> state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0077)
                is QrLogin.Outcome.Renewed -> {
                    paint(outcome.code)
                    state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0020)
                }
                is QrLogin.Outcome.Confirmed -> {
                    state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0189)
                    stopQr()
                    finishLogin(outcome.credentials, "扫码登录")
                    return@launch
                }
                is QrLogin.Outcome.Failed ->
                    state.text = outcome.message.ifBlank { UiText.get(com.hgtv.star.shared.R.string.ui_0107) }
            }
            schedule(
                if (outcome is QrLogin.Outcome.Failed) {
                    com.hgtv.star.core.AppConfig.Ui.QR_FAILURE_RETRY_MS
                } else {
                    QrLogin.POLL_MS
                },
            )
        }
    }
    fetchCode(manual = false)
    qrRefresh = { fetchCode(manual = true) }
}

internal fun PhoneAccountActivity.stopQr() {
    qrClosed = true
    qrTimer?.let { root.removeCallbacks(it) }
    qrTimer = null
    qr = null
    qrRefresh = null
}

internal fun PhoneAccountActivity.renderPhoneLogin() {
    stopQr()
    body.removeAllViews()
    val library=PhoneServices.library(this)
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
    val region = regionButton(com.hgtv.star.core.PhoneRegion.dialOf(regionIndex))
    region.setOnClickListener {
        if (regionDialog?.isShowing == true) return@setOnClickListener
        val dialog = PhoneDialog(this, UiText.get(com.hgtv.star.shared.R.string.ui_0251))
            .choices(com.hgtv.star.core.PhoneRegion.labels, regionIndex) { index ->
                regionIndex = index
                region.text = "${com.hgtv.star.core.PhoneRegion.dialOf(index)} ▾"
            }
        regionDialog = dialog
        dialog.show { regionDialog = null }
    }
    val phone = field("手机号", "请输入手机号", InputType.TYPE_CLASS_PHONE, 20, leading = region)
    if (pendingPhone.isNotBlank()) {
        val (index, local) = com.hgtv.star.core.PhoneRegion.split(pendingPhone)
        regionIndex = index
        region.text = "${com.hgtv.star.core.PhoneRegion.dialOf(index)} ▾"
        phone.input.setText(local)
        phone.input.setSelection(local.length)
    }
    body.addView(phone, blockTop())
    fun normalizedNumber(): String? =
        com.hgtv.star.core.PhoneRegion.normalize(phone.input.text?.toString().orEmpty(), regionIndex)
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
            send.text = UiText.get(com.hgtv.star.shared.R.string.ui_0212)
            return
        }
        send.isEnabled = false
        send.alpha = .45f
        send.text = if (landscape) UiText.get(com.hgtv.star.shared.R.string.ui_0006, left) else UiText.get(com.hgtv.star.shared.R.string.ui_0007, left)
        val tick = object : Runnable { override fun run() = refreshCooldown() }
        cooldownTick = tick
        root.postDelayed(tick, 1000L)
    }
    fun sendCodeNow(number: String) {
        cooldownTick?.let { root.removeCallbacks(it) }
        send.isEnabled = false
        send.alpha = .45f
        send.text = UiText.get(com.hgtv.star.shared.R.string.ui_0045)
        setState("正在发送验证码…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { session.sendCode(number) { profile -> registerForLogin(profile) } }.getOrNull()
            }
            when (result) {
                is com.hgtv.star.core.PhoneLogin.Result.Sent -> {
                    pendingPhone = number
                    setState("验证码已发送到 $number")
                    cooldownUntil = System.currentTimeMillis() + 60_000L
                    cooldownTick?.let { root.removeCallbacks(it) }
                    refreshCooldown()
                }
                is com.hgtv.star.core.PhoneLogin.Result.NeedUpsms -> {
                    pendingPhone = number
                    renderUpsms(session, result.number, result.content)
                }
                is com.hgtv.star.core.PhoneLogin.Result.LoggedIn ->
                    finishLogin(result.credentials, "手机号登录")
                is com.hgtv.star.core.PhoneLogin.Result.Failed -> {
                    setState(result.message)
                    send.isEnabled = true
                    send.alpha = 1f
                    send.text = UiText.get(com.hgtv.star.shared.R.string.ui_0212)
                }
                null -> {
                    setState("网络异常，请稍后重试")
                    send.isEnabled = true
                    send.alpha = 1f
                    send.text = UiText.get(com.hgtv.star.shared.R.string.ui_0212)
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
    val code = field("验证码", "收到短信后填入", InputType.TYPE_CLASS_NUMBER, 8, trailing = send.takeIf { landscape })
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

private fun PhoneAccountActivity.doLogin(
    session: com.hgtv.star.core.PhoneLogin,
    number: String,
    value: String,
    ticket: String = "",
) {
    scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { session.login(number, value, ticket) }.getOrNull() }
        when (result) {
            is com.hgtv.star.core.PhoneLogin.Result.LoggedIn ->
                finishLogin(result.credentials, "手机号登录")
            is com.hgtv.star.core.PhoneLogin.Result.NeedUpsms ->
                renderUpsms(session, result.number, result.content)
            is com.hgtv.star.core.PhoneLogin.Result.Failed -> toast(result.message)
            else -> toast(UiText.get(com.hgtv.star.shared.R.string.ui_0184))
        }
    }
}

private fun PhoneAccountActivity.renderUpsms(
    session: com.hgtv.star.core.PhoneLogin,
    number: String,
    content: String,
) {
    body.removeAllViews()
    body.addView(note("平台要求再做一次验证：用本机号码把下面这条短信发出去，然后回来点「我已发送」。"))
    body.addView(note("收件号码：$number\n短信内容：$content"), blockTop())
    val state = TextView(this).apply {
        setTextColor(PhoneStyle.textDim)
        phoneSize(12)
        visibility = View.GONE
    }
    body.addView(state, blockTop())
    lateinit var verifyButton: TextView
    verifyButton = primaryButton("我已发送") {
        verifyButton.isEnabled = false
        verifyButton.alpha = .45f
        state.visibility = View.VISIBLE
        state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0157)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { session.verifyUpsms() }.getOrNull() }
            when (result) {
                is com.hgtv.star.core.PhoneLogin.UpsmsResult.Ticket -> {
                    if (session.mfaFromSendCode) {
                        state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0273)
                        val resent = withContext(Dispatchers.IO) {
                            runCatching {
                                session.sendCode(pendingPhone) { profile -> registerForLogin(profile) }
                            }.getOrNull()
                        }
                        when (resent) {
                            is com.hgtv.star.core.PhoneLogin.Result.Sent -> {
                                pendingCode = ""
                                renderPhoneLogin()
                                toast(UiText.get(com.hgtv.star.shared.R.string.ui_0271))
                            }
                            is com.hgtv.star.core.PhoneLogin.Result.NeedUpsms -> {
                                pendingCode = ""
                                renderUpsms(session, resent.number, resent.content)
                            }
                            is com.hgtv.star.core.PhoneLogin.Result.LoggedIn ->
                                finishLogin(resent.credentials, "手机号登录")
                            is com.hgtv.star.core.PhoneLogin.Result.Failed -> {
                                state.text = resent.message
                                toast(resent.message)
                                verifyButton.isEnabled = true
                                verifyButton.alpha = 1f
                            }
                            null -> {
                                state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0207)
                                verifyButton.isEnabled = true
                                verifyButton.alpha = 1f
                            }
                        }
                        return@launch
                    }
                    state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0272)
                    val logged = withContext(Dispatchers.IO) {
                        runCatching {
                            session.login(pendingPhone, pendingCode, result.value)
                        }.getOrNull()
                    }
                    when (logged) {
                        is com.hgtv.star.core.PhoneLogin.Result.LoggedIn ->
                            finishLogin(logged.credentials, "手机号登录")
                        is com.hgtv.star.core.PhoneLogin.Result.Failed -> {
                            state.text = logged.message
                            toast(logged.message)
                            verifyButton.isEnabled = true
                            verifyButton.alpha = 1f
                        }
                        is com.hgtv.star.core.PhoneLogin.Result.NeedUpsms -> {
                            state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0086)
                            verifyButton.isEnabled = true
                            verifyButton.alpha = 1f
                        }
                        else -> {
                            state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0190)
                            verifyButton.isEnabled = true
                            verifyButton.alpha = 1f
                        }
                    }
                }
                is com.hgtv.star.core.PhoneLogin.UpsmsResult.Failed -> {
                    state.text = result.message
                    verifyButton.isEnabled = true
                    verifyButton.alpha = 1f
                }
                else -> {
                    state.text = UiText.get(com.hgtv.star.shared.R.string.ui_0089)
                    verifyButton.isEnabled = true
                    verifyButton.alpha = 1f
                }
            }
        }
    }
    body.addView(verifyButton, blockTop())
    if (!landscape) body.addView(secondaryButton("返回") { render() }, blockTop())
}
