// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
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
import com.hgtv.star.phone.ui.applyPhoneNavigationBar
import com.hgtv.star.phone.ui.bindPhoneInsets
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneBottomInsetPx
import com.hgtv.star.phone.ui.phoneEdgeToEdge
import com.hgtv.star.phone.ui.phoneNavigationBarUsesLightIcons
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.page.PhoneMinePage
import com.hgtv.star.shared.account.AccountLogin
import com.hgtv.star.shared.data.account
import com.hgtv.star.shared.data.accountCache
import com.hgtv.star.shared.data.clearAccount
import com.hgtv.star.shared.data.registrationCookies
import com.hgtv.star.shared.data.saveAccount
import com.hgtv.star.shared.ui.SoftStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhoneAccountActivity : PhoneActivity() {

    internal companion object {
        const val LOGIN_QR = 0
        const val LOGIN_PHONE = 1
        const val LOGIN_IMPORT = 2
        const val STATE_LOGIN_MODE = "phone_account_login_mode"
        val LOGIN_LABELS = listOf(
            UiText.get(com.hgtv.star.shared.R.string.ui_0318),
            UiText.get(com.hgtv.star.shared.R.string.ui_0363),
            UiText.get(com.hgtv.star.shared.R.string.ui_0364),
        )
    }

    internal lateinit var root: FrameLayout
    internal lateinit var body: LinearLayout
    private lateinit var outer: LinearLayout
    private lateinit var title: TextView
    private lateinit var modeBar: LinearLayout
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var landscape = false
    internal var loginMode = LOGIN_QR

    internal var qr: QrLogin? = null
    internal var qrTimer: Runnable? = null
    internal var qrClosed = false
    internal var qrRefresh: (() -> Unit)? = null

    internal var login: com.hgtv.star.core.PhoneLogin? = null
    internal var pendingPhone = ""
    internal var pendingCode = ""
    internal var regionIndex = com.hgtv.star.core.PhoneRegion.DEFAULT
    internal var regionDialog: PhoneDialog? = null
    internal var cooldownUntil = 0L
    internal var cooldownTick: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneStyle.ensure(this)
        phoneEdgeToEdge()
        applyPhoneNavigationBar(phoneNavigationBarUsesLightIcons())
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        loginMode = savedInstanceState?.getInt(STATE_LOGIN_MODE, LOGIN_QR)
            ?.takeIf { it in LOGIN_LABELS.indices } ?: LOGIN_QR
        buildUi()
        scope.launch {
            PhoneServices.awaitLibrary(this@PhoneAccountActivity)
            render()
        }
    }


    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(PhoneStyle.background) }

        val status = if (landscape) 0 else statusBarHeight()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (landscape) {
                setPadding(dp(12), dp(10), dp(12), dp(6))
            } else {
                setPadding(dp(16), status + dp(8), dp(16), dp(8))
            }
        }
        title = TextView(this).apply {
            text = UiText.get(com.hgtv.star.shared.R.string.ui_0230)
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

        outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (landscape) {
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(4), dp(20), dp(8) + phoneBottomInsetPx())
            } else {
                setPadding(dp(16), dp(6), dp(16), phoneBottomInsetPx() + dp(20))
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
        root.bindPhoneInsets { bottom -> applyBottomInset(bottom) }
        if (landscape) applyLandscapeImmersive()
    }

    private fun applyBottomInset(bottomInsetPx: Int) {
        if (landscape) {
            outer.setPadding(dp(20), dp(4), dp(20), dp(8) + bottomInsetPx)
        } else {
            outer.setPadding(dp(16), dp(6), dp(16), dp(20) + bottomInsetPx)
        }
    }

    internal fun render() {
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
            chip.contentDescription = LOGIN_LABELS[index] + if (active) UiText.get(com.hgtv.star.shared.R.string.ui_0283) else ""
        }
    }

    internal fun selectLoginMode(mode: Int) {
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


    private fun renderSignedIn() {
        val lib = PhoneServices.library(this)
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
            text = profile?.nickname?.takeIf { it.isNotBlank() } ?: UiText.get(com.hgtv.star.shared.R.string.ui_0080)
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
            text = sub.ifBlank { UiText.get(com.hgtv.star.shared.R.string.ui_0187) }
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
        body.addView(secondaryButton("退出登录") { confirmSignOut() }, blockTop())
    }

    private fun confirmSignOut() {
        PhoneDialog(this, UiText.get(com.hgtv.star.shared.R.string.ui_0249))
            .message(UiText.get(com.hgtv.star.shared.R.string.ui_0248))
            .button(UiText.get(com.hgtv.star.shared.R.string.ui_0046))
            .button(UiText.get(com.hgtv.star.shared.R.string.ui_0247), danger = true) {
                com.hgtv.star.shared.data.SessionBootstrap.onLogout()
                val lib = PhoneServices.library(this)
                runCatching { lib.clearAccount() }
                PhoneServices.repository(this).credentials = null
                PhoneAccountSync.clear()
                PhoneIdentity.apply(this, lib)
                render()
            }
            .show()
    }


    internal fun registerForLogin(profile: Map<String, String>): com.hgtv.star.core.DeviceRegister.Registered? {
        val repo = PhoneServices.repository(this)
        return runCatching {
            com.hgtv.star.shared.data.DeviceRegistration.registerLoginDevice(
                PhoneServices.library(this),
                repo,
                profile,
            )
        }.getOrNull()
    }


    private fun renderImport() {
        body.removeAllViews()
        body.addView(loginTitle("导入账号参数", "粘贴官方 App 的 cookieStore.xml，或一个 sessionid"))
        body.addView(note(
            "参数只保存在本机，不会上传到任何服务器。导入后会先验证登录态，验证失败不会覆盖当前账号。"
        ), blockTop())
        val input = EditText(this).apply {
            hint = UiText.get(com.hgtv.star.shared.R.string.ui_0205)
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
            hint = UiText.get(com.hgtv.star.shared.R.string.ui_0205)
            setHintTextColor(PhoneStyle.textFaint)
            setTextColor(PhoneStyle.text)
            phoneSize(13)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = if (landscape) 2 else 4
            maxLines = if (landscape) 4 else 8
            setPadding(dp(14), dp(12), dp(14), dp(12))
            applyWell(PhoneStyle.well, 14f, 4f)
        }
        PhoneDialog(this, UiText.get(com.hgtv.star.shared.R.string.ui_0067))
            .content(input)
            .button(UiText.get(com.hgtv.star.shared.R.string.ui_0046))
            .button(UiText.get(com.hgtv.star.shared.R.string.ui_0065), primary = true) { importText(input.text?.toString().orEmpty()) }
            .show()
    }

    private fun importText(text: String) {
        if (text.isBlank()) return
        toast(UiText.get(com.hgtv.star.shared.R.string.ui_0161))
        scope.launch {
            val repo = PhoneServices.repository(this@PhoneAccountActivity)
            val lib = PhoneServices.library(this@PhoneAccountActivity)
            val result = withContext(Dispatchers.IO) {
                runCatching { AccountLogin.importText(text, repo, lib) }
            }
            result.onSuccess { credentials ->
                lib.saveAccount(credentials)
                PhoneIdentity.apply(this@PhoneAccountActivity, lib)
                com.hgtv.star.shared.data.AppRuntime
                    .accountController(this@PhoneAccountActivity)
                    .bindCredentials()
                com.hgtv.star.shared.data.SessionBootstrap.onAppStart(force = true)
                toast(UiText.get(com.hgtv.star.shared.R.string.ui_0231))
                PhoneMinePage.requestHistoryOnNextShow()
                finish()
            }.onFailure { failure ->
                toast(failure.message.orEmpty().ifBlank { UiText.get(com.hgtv.star.shared.R.string.ui_0066) })
            }
        }
    }


    internal fun finishLogin(credentials: AccountCredentials, how: String) {
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
            PhoneIdentity.apply(this@PhoneAccountActivity, lib)
            com.hgtv.star.shared.data.AppRuntime
                .accountController(this@PhoneAccountActivity)
                .bindCredentials()
            com.hgtv.star.shared.data.SessionBootstrap.onAppStart(force = true)
            toast(UiText.get(com.hgtv.star.shared.R.string.ui_0011, how))
            PhoneMinePage.requestHistoryOnNextShow()
            finish()
        }
    }


    internal fun field(
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
            box.addView(leading, FrameLayout.LayoutParams(
                dp(leadingWidth), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START
            ).apply { leftMargin = dp(6) })
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

    internal val LinearLayout.input: EditText get() = tag as EditText

    internal fun primaryButton(text: String, onClick: () -> Unit): TextView =
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

    internal fun secondaryButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(PhoneStyle.text)
            phoneSize(if (landscape) 12 else 15)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(if (landscape) 6 else 13), dp(16), dp(if (landscape) 6 else 13))
            applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f)
            setOnClickListener { onClick() }
        }

    internal fun regionButton(dial: String): TextView =
        TextView(this).apply {
            text = "$dial ▾"
            setTextColor(PhoneStyle.text)
            phoneSize(13)
            gravity = Gravity.CENTER
            background = com.hgtv.star.phone.ui.PhoneSheet.pillBackground(
                android.graphics.Color.TRANSPARENT,
                PhoneStyle.wellDeep,
                dp(12).toFloat(),
            )
            isClickable = true
        }

    internal fun note(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(PhoneStyle.textFaint)
            phoneSize(if (landscape) 11 else 12)
            setLineSpacing(dp(if (landscape) 2 else 3).toFloat(), 1f)
        }

    internal fun blockTop() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(if (landscape) 4 else 12) }

    internal fun loginTitle(heading: String, subtitle: String): LinearLayout =
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

    internal fun landscapeCardWidth(): Int {
        val available = (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(dp(220))
        return minOf(dp(360), (available * .64f).toInt())
            .coerceAtLeast(minOf(dp(200), available))
    }

    internal fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    @Suppress("DEPRECATION")
    private fun applyLandscapeImmersive() {
        if (!landscape) return
        applyPhoneNavigationBar(false)
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
