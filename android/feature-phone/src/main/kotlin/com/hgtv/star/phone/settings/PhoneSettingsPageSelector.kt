// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.shared.ui.SoftStyle

/** 四个大项排成两列两行，和「我的」页的四个入口使用同一组名称。 */
internal fun PhoneSettingsActivity.renderPageSelector() {
    if (settingsLandscape) {
        settingsPageTabs.removeAllViews()
        PhoneSettingsPage.entries.forEachIndexed { index, option ->
            val active = option == settingsPage
            settingsPageTabs.addView(TextView(this).apply {
                text = option.label
                settingsTextSizeImpl(11)
                gravity = Gravity.CENTER
                minWidth = settingsDpImpl(78)
                minHeight = settingsDpImpl(24)
                includeFontPadding = false
                setPadding(settingsDpImpl(8), 0, settingsDpImpl(8), 0)
                setTextColor(if (active) PhoneStyle.accent else PhoneStyle.textDim)
                applySoft(
                    if (active) SoftStyle.INSET else SoftStyle.RAISED,
                    PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 3f,
                )
                contentDescription = option.label + "，" + option.hint +
                    if (active) "，当前分区" else ""
                setOnClickListener {
                    if (settingsPage == option) return@setOnClickListener
                    settingsPage = option
                    renderSettings(resetScroll = true)
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, settingsDpImpl(24)
            ).apply {
                if (index > 0) leftMargin = settingsDpImpl(8)
            })
        }
        return
    }
    val grid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = PhoneSettingsActivity.TAG_PAGE_GRID
    }
    PhoneSettingsPage.entries.chunked(2).forEachIndexed { rowIndex, line ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (rowIndex > 0) setPadding(0, settingsDpImpl(6), 0, 0)
        }
        line.forEachIndexed { index, option ->
            val active = option == settingsPage
            val button = TextView(this).apply {
                text = option.label
                settingsTextSizeImpl(14)
                gravity = Gravity.CENTER
                setTextColor(if (active) PhoneStyle.accent else PhoneStyle.text)
                applySoft(
                    if (active) SoftStyle.INSET else SoftStyle.RAISED,
                    PhoneStyle.surface, PhoneStyle.RADIUS_CARD, 3f,
                )
                contentDescription = option.label + "，" + option.hint +
                    if (active) "，当前页" else ""
                setOnClickListener {
                    if (settingsPage == option) return@setOnClickListener
                    settingsPage = option
                    renderSettings(resetScroll = true)
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, settingsDpImpl(42), 1f).apply {
                if (index > 0) leftMargin = settingsDpImpl(6)
            })
        }
        grid.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
    settingsContainer.addView(grid, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = settingsDpImpl(4) })
}
