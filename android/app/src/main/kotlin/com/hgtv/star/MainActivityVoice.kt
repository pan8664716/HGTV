// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star


import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

// 本文件是从 MainActivity.kt 拆出来的「搜索页语音输入」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。
//
// 走系统 [RecognizerIntent] 而不是自带录音：录音由系统语音服务完成，APK 不需要
// `RECORD_AUDIO`，也不用处理麦克风权限、采样率与识别模型。代价是**有没有这项服务由设备决定**，
// 因此这里把「没有服务」当成一条正常分支（轻提示 + 埋点），而不是异常。

/**
 * 拉起系统语音识别界面。
 *
 * 调用前先把输入法收掉：识别界面是另一个 Activity，键盘留在下面既盖住画面，
 * 也会让部分 ROM 在返回时把焦点重新塞回输入框。
 *
 * 草稿在拉起前存一份（[voiceDraft]）：识别界面会让本页走一次 `onStop → onResume`，
 * 而重建只认已提交的 [query]；用户打了半句就点语音、又取消时，那半句必须还回去。
 */
internal fun MainActivity.startVoiceSearch() {
    // 连按：语音界面已经在前面了，不再叠一层。
    if(voiceLaunching || isFinishing) return
    if(screen!="catalog" || tab!=TAB_SEARCH) return
    val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // FREE_FORM：剧名是一句自然语言，不是固定词表；WEB_SEARCH 会把结果往网址上带。
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // 明确要中文：系统语言是英文的盒子上，不指定就会按英文识别。
        putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"zh-CN")
        putExtra(RecognizerIntent.EXTRA_PROMPT,"请说出剧名或关键词")
        // 只要一条：多条时还得再让用户选一次，电视上用遥控器选并不比重新说一遍快。
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
    }
    // 预检：没有 handler 时 `startActivityForResult` 会抛 ActivityNotFoundException，
    // 但那一下会走一遍异常构造与栈展开；先查一次，能给出更准确的提示。
    // targetSdk 仍是 29，不受包可见性限制；将来提到 30+ 时这里要补 `<queries>`。
    if(!hasSpeechRecognizer(intent)) {
        noteVoice("no_service")
        showHint("这台电视没有系统语音服务，请用遥控器输入")
        return
    }
    // 有服务才收键盘：没有服务时上面已经给了提示，不该顺手把用户的键盘关掉。
    (searchInput as? EditText)?.let { input ->
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken,0)
    }
    voiceDraft=(searchInput as? EditText)?.text?.toString()
    voiceLaunching=true
    voiceReturned=false
    try {
        startActivityForResult(intent,REQUEST_VOICE)
        noteVoice("launch")
    } catch(problem: ActivityNotFoundException) {
        // 预检之后仍可能没有：部分 ROM 的 resolver 与真实启动结果不一致。
        voiceLaunching=false; voiceDraft=null
        noteVoice("no_service")
        showHint("这台电视没有系统语音服务，请用遥控器输入")
    } catch(problem: Exception) {
        // SecurityException 等：不让一个可选功能把搜索页带崩。
        voiceLaunching=false; voiceDraft=null
        noteVoice("error")
        showHint("无法启动语音搜索，请用遥控器输入")
    }
}
/**
 * 系统里有没有能接 `ACTION_RECOGNIZE_SPEECH` 的 Activity。
 *
 * 统一走 `queryIntentActivities(Intent, Int)` 这个旧重载：33 起它只是被标记为废弃，
 * 功能仍在。换成 `ResolveInfoFlags` 的那个重载会让 4.4（minSdk 19）上的类校验
 * 去解析一个不存在的类，等于为了一个可选功能给老设备埋一颗 `NoClassDefFoundError`。
 */
private fun MainActivity.hasSpeechRecognizer(intent: Intent): Boolean = runCatching {
    @Suppress("DEPRECATION")
    packageManager.queryIntentActivities(intent,0).isNotEmpty()
}.getOrDefault(false)
/**
 * 语音界面返回后的收尾：**只消费一次**。
 *
 * 两条路都会调到这里：
 * - 全屏识别界面：本页走过 `onStop`，`onResume` 里先重建搜索页，再 post 一次；
 * - 对话框式识别界面：本页没有 `onStop`，`onActivityResult` 里直接 post 一次。
 *
 * 结果、草稿、焦点抑制都在这里一次取空，因此重复调用不会搜两遍、也不会把草稿盖回去。
 */
internal fun MainActivity.applyVoiceReturn() {
    if(!voiceReturned) return
    // 还没回到前台（`onActivityResult` 的 post 抢在 `onResume` 前面跑了）：
    // 留着标志，等 `onResume` 末尾再消费，避免在页面还没重建时先搜一遍。
    if(!foreground) return
    voiceReturned=false
    val pending=pendingVoiceQuery; pendingVoiceQuery=null
    val draft=voiceDraft; voiceDraft=null
    // 这一次没有重建（对话框式识别）：焦点抑制标志不该留到下一次无关的重建上。
    suppressSearchFocusOnce=false
    if(!pending.isNullOrEmpty()) {
        main.post {
            // 用户在识别期间切走了页面：结果丢弃，不把人家拽回搜索页。
            if(isDestroyed || screen!="catalog" || tab!=TAB_SEARCH) return@post
            runSearch(pending)
        }
        return
    }
    // 取消 / 没听清：把拉起前那半句还回输入框，但**不触发搜索**。
    main.post {
        if(isDestroyed || screen!="catalog" || tab!=TAB_SEARCH) return@post
        val input=searchInput as? EditText ?: return@post
        if(draft!=null && input.text.toString()!=draft) {
            input.setText(draft)
            input.setSelection(input.text.length)
        }
        // 落点给「语音」按钮而不是输入框：输入框拿到焦点时，不少电视会顺手把软键盘推上来，
        // 用户刚取消一次语音就看到一屏键盘。按钮就在旁边，往左一格仍是输入框。
        voiceButton?.requestFocus()
    }
}
/**
 * 识别结果归一化。
 *
 * 系统返回的是一条口语句子（「我想看……」「帮我搜一下……」），直接拿去搜会把前缀
 * 当成剧名的一部分。这里只做**低风险**的清理：
 * - 去首尾空白与标点 / 引号；
 * - 去掉明确的口语前缀，且要求剩下的部分至少两个字（避免把「找》」这种标题削掉）；
 * - 按输入框同一条上限截断（[VOICE_QUERY_MAX]），不让超长结果被静默丢弃。
 *
 * 不做同音字纠正、不做分词：那两类改动会把正确的剧名改错，而识别错的时候
 * 用户看得见输入框里的字，自己改一下比被“猜”更可靠。
 */
internal fun normalizeVoiceQuery(raw: String): String {
    var text=raw.trim()
    val edge=" \t\n\r。，、！？；：,.!?;:「」『』“”\"'《》<>()（）[]【】"
    text=text.trim { it in edge }
    val prefixes=listOf("我想看","我要看","我想搜","帮我搜","帮我找","搜索一下","搜一下","搜索","查找")
    for(prefix in prefixes) {
        if(text.startsWith(prefix)) {
            val rest=text.removePrefix(prefix).trim { it in edge }
            // 只剩一个字说明这一下本来就是前缀（例如剧名就叫「搜索」），不削。
            if(rest.length>=2) { text=rest; break }
        }
    }
    if(text.length>VOICE_QUERY_MAX) text=text.substring(0,VOICE_QUERY_MAX)
    return text
}
/**
 * 语音功能的阶段打点：只记「走到了哪一步」，**不上传识别文本**，也不发任何网络。
 *
 * 友盟统计已整体移除，这条链路改成**只写 logcat**（`adb logcat -s HGTV-VOICE`）：
 * 想知道「有多少人点了语音、多少台没有服务」时，在真机上抓一段日志就能看出来，
 * 不再需要外部统计后台。参数只有阶段名，不含任何识别内容。
 */
internal fun MainActivity.noteVoice(stage: String) {
    Diagnostics.trace("HGTV-VOICE",stage)
}
