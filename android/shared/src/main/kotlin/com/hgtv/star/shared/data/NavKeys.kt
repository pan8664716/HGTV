// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

/**
 * 顶部导航栏里可以单独隐藏的入口 key。
 *
 * 这里是**数据层校验用的白名单**：`Library.hiddenNav` 落盘时只认这几个 key，
 * 界面认别的名字不会出现「磁盘里存了一份界面不认识的开关」。
 *
 * 两端的入口排版不同（TV 是顶部五枚圆形按钮，手机是底部三格 tab），
 * 但 key 共用同一套——同一个账号在两端同步「隐藏了哪些入口」时才对得上。
 * 标签文案属于各端自己的版面资源，不放在这里。
 */
const val NAV_KEY_RECOMMEND = "recommend"

val NAV_KEYS = setOf("recommend", "search", "category", "rank", "profile")
