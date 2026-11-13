// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/** 电视搜索页用哪一套键盘。未选择过时默认使用 HG 键盘。 */
enum class SearchKeyboard(val storedValue: String, val label: String) {
    HONGGUO("hongguo", "HG键盘"),
    SYSTEM("system", "系统输入法");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value } ?: HONGGUO
    }
}
