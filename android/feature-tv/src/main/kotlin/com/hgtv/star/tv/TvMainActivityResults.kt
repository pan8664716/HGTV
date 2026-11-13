// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent

internal fun TvMainActivity.handleActivityResultImpl(
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
) {
    if(requestCode==REQUEST_VOICE) {
        voiceLaunching=false
        voiceReturned=true
        // 语音界面返回后页面会重建：这一次别让输入框抢焦点、别弹输入法。
        suppressSearchFocusOnce=true
        val spoken=if(resultCode==Activity.RESULT_OK)
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }
        else null
        val clean=normalizeVoiceQuery(spoken.orEmpty())
        if(clean.isEmpty()) {
            noteVoice(if(resultCode==Activity.RESULT_OK) "empty" else "cancel")
            if(resultCode==Activity.RESULT_OK) showHint("没有听清，请再说一次")
        } else {
            pendingVoiceQuery=clean
            noteVoice("success")
        }
        // 识别界面如果是对话框（本页没有 onStop），onResume 不一定还会来一次；
        // 这里也投一次，`applyVoiceReturn` 自己保证只消费一次。
        main.post { applyVoiceReturn() }
        return
    }
    if(requestCode!=REQUEST_ACCOUNT_FILE || resultCode!=Activity.RESULT_OK) return
    pendingAccountUri=data?.data
}
