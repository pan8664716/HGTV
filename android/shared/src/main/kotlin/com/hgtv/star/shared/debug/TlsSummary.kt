// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.debug

/**
 * 把 TLS 能力那一行压成面板上放得下的一句。
 *
 * ```
 * 可用版本=[TLSv1, TLSv1.1, TLSv1.2, TLSv1.3] 启用=[TLSv1.3, TLSv1.2]
 *   → 支持 1.0~1.3 · 启用 1.3/1.2
 * ```
 *
 * 面板上这一行要占满一行，原串 40 多个字符会折成两行；版本号本身是唯一的判读信息
 * （「只支持 1.0」就是连不上的原因），因此只压格式、不丢信息。
 *
 * ## 为什么放在公共层
 *
 * 电视的调试面板与手机的「网络诊断」读的是同一份 `TLS ` 日志行，
 * 早先两个模块各有一份逐字相同的实现。压缩规则一旦只改一边，
 * 两端对同一台机器就会给出两种读数——而这条读数正是用来判断「连不上是不是 TLS 太旧」的。
 */
fun compactTls(raw: String): String {
    fun versions(part: String): List<String> =
        Regex("TLSv(\\d(?:\\.\\d)?)").findAll(part).map { it.groupValues[1] }.toList()
    val supported = versions(raw.substringBefore("启用"))
    val enabled = versions(raw.substringAfter("启用", raw))
    if (supported.isEmpty()) return raw.take(60)
    // `TLSv1` 在安卓上就是 TLS 1.0（Java 的旧命名），直接写「1」会读成 1.0 还是 1.x 分不清。
    fun nice(value: String) = if (value == "1") "1.0" else value
    val span = if (supported.size == 1) nice(supported.first())
    else "${nice(supported.first())}~${nice(supported.last())}"
    return "支持 $span · 启用 ${enabled.map(::nice).joinToString("/").ifEmpty { "无" }}"
}
