// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feature.settings

/**
 * 设置页的行结构。只描述“有什么行、当前值是什么”，不携带平台控件与回调。
 * TV / Phone 各自把这些 spec 渲染成自己的按钮、开关、滑杆或对话框。
 */
sealed interface SettingRowSpec {
    val id: String

    data class Section(
        val title: String,
    ) : SettingRowSpec {
        override val id: String = "section:$title"
    }

    data class Choice(
        override val id: String,
        val title: String,
        val value: String,
        val options: List<String>,
    ) : SettingRowSpec

    data class Switch(
        override val id: String,
        val title: String,
        val checked: Boolean,
    ) : SettingRowSpec

    data class Slider(
        override val id: String,
        val title: String,
        val value: Float,
        val min: Float,
        val max: Float,
        val step: Float,
        val valueLabel: String,
    ) : SettingRowSpec

    data class Action(
        override val id: String,
        val title: String,
        val subtitle: String = "",
    ) : SettingRowSpec

    data class ThemeGrid(
        override val id: String = "theme-grid",
        val selected: String,
    ) : SettingRowSpec
}
