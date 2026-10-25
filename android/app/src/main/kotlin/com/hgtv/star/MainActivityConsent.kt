// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.view.View
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.TvDialog

// 本文件是两条「知情同意」：进「我的」页的那条使用须知，与「获取验证码」前的那次确认。
//
// 两条是**不同**的弹窗，语义也不一样：
//
// * 使用须知（[ensureThirdPartyAccepted]）：告知「这是第三方客户端」，确认一次就记住，
//   之后进「我的」页不再打扰。
// * 发码确认（[confirmSmsConsent]）：**每一次**点「获取验证码」都要过一遍。
//   它问的不是「你知不知道这是第三方应用」，而是「这一次要不要真的把手机号发给平台」——
//   每次发码都是一次独立的、可撤销的决定，因此不能拿上一次的确认顶替。

/** 使用须知的正文。 */
private const val CONSENT_TEXT =
    "本应用是第三方客户端，与红果官方无任何关联，也未获得官方授权。\n\n" +
    "使用本应用登录账号，属于在非官方客户端上使用服务。若因违反平台规则" +
    "（包括但不限于多设备登录、异常客户端识别等）导致账号被限制、封禁或其他处置，" +
    "本应用不承担任何责任，也无法代为申诉或恢复。\n\n" +
    "请自行评估风险。继续使用即表示你已知晓并接受上述情况。"

/** 发码确认的正文：比使用须知短，因为这一段只交代「这一下要发生什么、风险在哪」。 */
private const val SMS_CONSENT_TEXT =
    "接下来会把手机号发送到红果平台并请求一条登录验证码。\n\n" +
    "这属于在非官方客户端上登录：若因违反平台规则导致账号被限制、封禁或其他处置，" +
    "本应用不承担任何责任，也无法代为申诉或恢复。\n\n" +
    "点击「我已知晓并同意使用」后才会发送验证码；点击「不同意」不会发出任何请求。"

/** 弹窗标题。 */
private const val CONSENT_TITLE = "使用须知"
private const val SMS_CONSENT_TITLE = "发送验证码前请确认"

/**
 * 进「我的」页时弹一次使用须知，确认之后才继续。
 *
 * 确认状态存在 `Library.thirdPartyAccepted`（会话偏好，清除数据 / 换设备要重新确认一次），
 * 因此**同一个用户只会看到一次**。已经确认过就直接执行 [onAccepted]。
 *
 * 这里只有「同意」一枚按钮：这一页只是来读声明的，没有「同意才继续」的分岔
 * （用户按返回键关掉即可）。要发验证码时走的是另一条 [confirmSmsConsent]。
 */
internal fun MainActivity.ensureThirdPartyAccepted(anchor: View?=null, onAccepted: ()->Unit) {
    if(library.thirdPartyAccepted) { onAccepted(); return }
    if(consentShowing) return
    // 本次进程弹过就不再弹（见 [consentShownThisRun]）：用户按返回键关掉之后切回来不该再弹。
    if(consentShownThisRun) return
    consentShowing=true; consentShownThisRun=true
    val dialog=TvDialog(this,CONSENT_TITLE).message(CONSENT_TEXT)
    // 外观是**红色警示图标 + 空心红描边**：按下它等于承认「账号可能被平台处置、后果自负」。
    // 图标用警示三角而不是对勾：对勾读起来是「已完成」，而这里还什么都没完成。
    dialog.button("我已知晓并同意使用",tone=ButtonTone.DANGER,icon=R.drawable.ic_menu_warning,outlined=true) {
        library.thirdPartyAccepted=true
        consentShowing=false
        onAccepted()
    }
    dialog.setOnDismissExtra { consentShowing=false }
    tvTools.show(dialog,anchor)
}

/**
 * 点「获取验证码」前的那一次确认：**每一次都要弹**。
 *
 * 与 [ensureThirdPartyAccepted] 刻意分开：
 *
 * * 它**不看** `Library.thirdPartyAccepted`——进「我的」页确认过，不等于用户同意
 *   在这一次把手机号发出去；
 * * 它**不落盘**——下一次点「获取验证码」仍然要重新问一遍。
 *
 * 只有点「我已知晓并同意使用」才执行 [onAccepted]（发码）；点「不同意」或按返回键
 * 都什么也不做，一个请求都不会发出。
 */
internal fun MainActivity.confirmSmsConsent(anchor: View?, onAccepted: ()->Unit) {
    // 已经有一个弹窗在显示：不叠加第二个，这一次点击直接作废（用户关掉后可以再点）。
    if(consentShowing) return
    consentShowing=true
    val dialog=TvDialog(this,SMS_CONSENT_TITLE).message(SMS_CONSENT_TEXT)
    // 「不同意」排在前面：弹窗的默认焦点是第一枚按钮，落在「不同意」上，
    // 误按一次确认键不会把手机号发出去。
    dialog.button("不同意",icon=R.drawable.ic_menu_close) {}
    dialog.button("我已知晓并同意使用",tone=ButtonTone.DANGER,icon=R.drawable.ic_menu_warning,outlined=true) {
        consentShowing=false
        onAccepted()
    }
    dialog.setOnDismissExtra { consentShowing=false }
    tvTools.show(dialog,anchor)
}
