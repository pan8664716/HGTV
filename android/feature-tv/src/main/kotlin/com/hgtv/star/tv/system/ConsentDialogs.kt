// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.tv.R

// 本文件是两条「知情同意」：进「我的」页的那条使用须知，与「获取验证码」前的那次确认。
//
// 两条是**不同**的弹窗，语义也不一样：
//
// * 使用须知（[ensureThirdPartyAccepted]）：告知「这是第三方客户端」，确认一次就记住，
//   之后进「我的」页不再打扰。
// * 发码确认（[confirmSmsConsent]）：**每一次**点「获取验证码」都要过一遍。
//   它问的不是「你知不知道这是第三方应用」，而是「这一次要不要真的把手机号发给平台」——
//   每次发码都是一次独立的、可撤销的决定，因此不能拿上一次的确认顶替。

// 正文与按钮文案统一放在 `:core` 的 [com.hgtv.star.core.ThirdPartyNotice]：手机端用的是同一份，
// 文案改一处两端同时生效，也就不会出现「电视上说了风险、手机上没说」。
private val CONSENT_TEXT get()=com.hgtv.star.core.ThirdPartyNotice.USAGE
private val SMS_CONSENT_TEXT get()=com.hgtv.star.core.ThirdPartyNotice.SMS
private val CONSENT_TITLE get()=com.hgtv.star.core.ThirdPartyNotice.USAGE_TITLE
private val SMS_CONSENT_TITLE get()=com.hgtv.star.core.ThirdPartyNotice.SMS_TITLE

/**
 * 进「我的」页时弹一次使用须知，确认之后才继续。
 *
 * 确认状态存在 `Library.thirdPartyAccepted`（会话偏好，清除数据 / 换设备要重新确认一次），
 * 因此**同一个用户只会看到一次**。已经确认过就直接执行 [onAccepted]。
 *
 * 这里只有「同意」一枚按钮：这一页只是来读声明的，没有「同意才继续」的分岔
 * （用户按返回键关掉即可）。要发验证码时走的是另一条 [confirmSmsConsent]。
 */
fun TvMainActivity.ensureThirdPartyAccepted(anchor: View?=null, onAccepted: ()->Unit) {
    if(library.thirdPartyAccepted) { onAccepted(); return }
    if(consentShowing) return
    // 本次进程弹过就不再弹（见 [consentShownThisRun]）：用户按返回键关掉之后切回来不该再弹。
    if(consentShownThisRun) return
    consentShowing=true; consentShownThisRun=true
    val dialog=TvDialog(this,CONSENT_TITLE).message(CONSENT_TEXT)
    // 外观是**红色警示图标 + 空心红描边**：按下它等于承认「账号可能被平台处置、后果自负」。
    // 图标用警示三角而不是对勾：对勾读起来是「已完成」，而这里还什么都没完成。
    dialog.button(com.hgtv.star.core.ThirdPartyNotice.ACCEPT,tone=ButtonTone.DANGER,icon=R.drawable.ic_menu_warning,outlined=true) {
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
fun TvMainActivity.confirmSmsConsent(anchor: View?, onAccepted: ()->Unit) {
    // 已经有一个弹窗在显示：不叠加第二个，这一次点击直接作废（用户关掉后可以再点）。
    if(consentShowing) return
    consentShowing=true
    val dialog=TvDialog(this,SMS_CONSENT_TITLE).message(SMS_CONSENT_TEXT)
    // 「不同意」排在前面：弹窗的默认焦点是第一枚按钮，落在「不同意」上，
    // 误按一次确认键不会把手机号发出去。
    dialog.button(com.hgtv.star.core.ThirdPartyNotice.DECLINE,icon=R.drawable.ic_menu_close) {}
    dialog.button(com.hgtv.star.core.ThirdPartyNotice.ACCEPT,tone=ButtonTone.DANGER,icon=R.drawable.ic_menu_warning,outlined=true) {
        consentShowing=false
        onAccepted()
    }
    dialog.setOnDismissExtra { consentShowing=false }
    tvTools.show(dialog,anchor)
}
