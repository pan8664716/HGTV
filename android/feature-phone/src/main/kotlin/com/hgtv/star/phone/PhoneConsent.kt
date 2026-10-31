// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.Activity
import com.hgtv.star.core.ThirdPartyNotice
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.shared.data.Library

/**
 * 手机端的两条「知情同意」，与电视端**同一份文案、同一条语义**（见 `:core` 的
 * [com.hgtv.star.core.ThirdPartyNotice]）。
 *
 * ## 为什么手机端也必须有
 *
 * 这不是「电视上有、手机上也来一份」的形式对齐：它交代的是**账号风险**。
 * 电视端早先就补上了，手机端一直没有——同一个账号在手机上登录时什么都没说，
 * 这比两端都没有更糟：用户会以为手机上那条路是「官方客户端」。
 *
 * ## 与电视端的唯一差别：用什么控件
 *
 * 电视用遥控器焦点弹窗（`TvDialog`），手机用同一结构的 `PhoneDialog`——
 * 标题、正文与竖排胶囊按钮保持一致，只把遥控器焦点换成触屏点击。
 * 判断逻辑（确认一次就记住 / 每次发码都要问）完全一致。
 */
object PhoneConsent {

    /** 本次进程已经弹过使用须知：用户按返回关掉之后，切回这一页不该再弹。 */
    private var shownThisRun = false

    /**
     * 进「我的」页弹一次使用须知；已经确认过就直接执行 [onAccepted]。
     *
     * 确认状态存在 [Library.thirdPartyAccepted]（落盘、跨启动）：同一个用户只看到一次，
     * 清除数据 / 换设备要重新确认。
     */
    fun ensureUsage(activity: Activity, library: Library, onAccepted: () -> Unit = {}) {
        if (library.thirdPartyAccepted) { onAccepted(); return }
        if (shownThisRun) return
        shownThisRun = true
        PhoneDialog(activity, ThirdPartyNotice.USAGE_TITLE)
            .message(ThirdPartyNotice.USAGE)
            .button(ThirdPartyNotice.ACCEPT, danger = true) {
                library.thirdPartyAccepted = true
                onAccepted()
            }
            .show()
    }

    /**
     * 点「获取验证码」前的那一次确认：**每一次都要弹**。
     *
     * 与 [ensureUsage] 刻意分开：它**不看** `thirdPartyAccepted`——进「我的」页确认过，
     * 不等于用户同意在这一次把手机号发出去；它也**不落盘**，下一次仍然要重新问。
     * 只有点「我已知晓并同意使用」才执行 [onAccepted]（发码），点「不同意」一个请求都不发。
     */
    fun confirmSms(activity: Activity, onAccepted: () -> Unit) {
        PhoneDialog(activity, ThirdPartyNotice.SMS_TITLE)
            .message(ThirdPartyNotice.SMS)
            .button(ThirdPartyNotice.DECLINE)
            .button(ThirdPartyNotice.ACCEPT, danger = true) { onAccepted() }
            .show()
    }

    /** 宿主界面重建：允许下一次重新弹（用户这次还没确认就退出了应用）。 */
    fun resetRun() { shownThisRun = false }
}
