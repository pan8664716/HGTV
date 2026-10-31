// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.search

/**
 * 语音搜索里与 Android 相关的那一半（文本归一化在 `core` 的 [com.hgtv.star.core.VoiceQuery]）。
 *
 * 两端拉起的是同一个系统 [android.speech.RecognizerIntent]，拿回来的也是同一种句子；
 * 这份规则写两遍迟早会漂移（一边改了前缀表，另一边没改），因此只有 `core` 那一份实现。
 */
object VoiceSearch {

    /**
     * 系统里有没有能接 `ACTION_RECOGNIZE_SPEECH` 的 Activity。
     *
     * 统一走 `queryIntentActivities(Intent, Int)` 这个旧重载：33 起它只是被标记为废弃，
     * 功能仍在。换成 `ResolveInfoFlags` 的那个重载会让 4.4（minSdk 19）上的类校验
     * 去解析一个不存在的类，等于为了一个可选功能给老设备埋一颗 `NoClassDefFoundError`。
     */
    fun hasRecognizer(context: android.content.Context, intent: android.content.Intent): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }.getOrDefault(false)
}
