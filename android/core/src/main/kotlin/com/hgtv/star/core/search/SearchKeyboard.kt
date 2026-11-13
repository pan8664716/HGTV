// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/** 电视搜索页用哪一套键盘。未选择过时沿用旧版的系统输入法。 */
enum class SearchKeyboard(val storedValue: String, val label: String) {
    HONGGUO("hongguo", "HG键盘"),
    SYSTEM("system", "系统输入法");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}
