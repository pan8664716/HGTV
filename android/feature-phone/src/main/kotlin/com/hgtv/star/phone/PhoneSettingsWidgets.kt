// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.phone.ui.PhoneSettingSlider
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneSwitchRow
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.shared.ui.SoftStyle

internal fun PhoneSettingsActivity.targetHostImpl(): LinearLayout =
    settingsSectionHost ?: settingsContainer

internal fun PhoneSettingsActivity.sectionImpl(title: String) {
    if (settingsLandscape) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = anchorKeyImpl("section", title)
            setPadding(settingsDpImpl(12), settingsDpImpl(10), settingsDpImpl(12), settingsDpImpl(6))
            applySoft(
                SoftStyle.RAISED,
                PhoneStyle.surface,
                PhoneStyle.RADIUS_CARD,
                3f,
            )
        }
        panel.addView(TextView(this).apply {
            text = title
            setTextColor(PhoneStyle.text)
            settingsTextSizeImpl(13)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(settingsDpImpl(2), 0, settingsDpImpl(2), settingsDpImpl(6))
        })
        settingsContainer.addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = settingsDpImpl(8) })
        settingsSectionHost = panel
        return
    }
    settingsSectionHost = null
    settingsContainer.addView(TextView(this).apply {
        tag = anchorKeyImpl("section", title)
        text = title
        setTextColor(PhoneStyle.textDim)
        settingsTextSizeImpl(12)
        setPadding(
            settingsDpImpl(6),
            settingsDpImpl(20),
            settingsDpImpl(6),
            settingsDpImpl(6),
        )
    })
}

internal fun PhoneSettingsActivity.hintImpl(text: String) {
    targetHostImpl().addView(TextView(this).apply {
        this.text = text
        setTextColor(PhoneStyle.textFaint)
        settingsTextSizeImpl(if (settingsLandscape) 11 else 12)
        setPadding(
            settingsDpImpl(6),
            settingsDpImpl(if (settingsLandscape) 1 else 2),
            settingsDpImpl(6),
            settingsDpImpl(if (settingsLandscape) 3 else 4),
        )
    })
}

internal fun PhoneSettingsActivity.rowImpl(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    val activity = this
    val line = LinearLayout(this).apply {
        tag = anchorKeyImpl("row", title)
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(settingsDpImpl(14), 0, settingsDpImpl(12), 0)
        applySoft(
            SoftStyle.RAISED,
            PhoneStyle.surface,
            PhoneStyle.RADIUS_PILL,
            4f,
        )
        addView(TextView(activity).apply {
            text = title
            setTextColor(PhoneStyle.text)
            settingsTextSizeImpl(if (settingsLandscape) 14 else 15)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply {
            text = value
            setTextColor(PhoneStyle.textDim)
            settingsTextSizeImpl(if (settingsLandscape) 12 else 13)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(activity).apply {
            text = "›"
            setTextColor(PhoneStyle.textFaint)
            settingsTextSizeImpl(if (settingsLandscape) 13 else 14)
            setPadding(settingsDpImpl(6), 0, 0, 0)
        })
        setOnClickListener { onClick() }
    }
    targetHostImpl().addView(line, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, settingRowHeightImpl()
    ).apply { bottomMargin = settingRowGapImpl() })
}

internal fun PhoneSettingsActivity.switchImpl(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val line = PhoneSwitchRow(
        this, title, checked,
        densityScale=SETTINGS_DENSITY_SCALE,
        textScale=SETTINGS_TEXT_SCALE,
        onToggle=onToggle,
    ).apply {
        tag = anchorKeyImpl("row", title)
    }
    targetHostImpl().addView(line, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, settingRowHeightImpl()
    ).apply { bottomMargin = settingRowGapImpl() })
}

internal fun PhoneSettingsActivity.actionImpl(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) = rowImpl(title, subtitle, onClick)

internal fun PhoneSettingsActivity.sliderImpl(
    title: String,
    min: Float,
    max: Float,
    step: Float,
    value: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    val control = PhoneSettingSlider(
        this, title, min, max, step, value, onChange, format,
        densityScale=SETTINGS_DENSITY_SCALE,
        textScale=SETTINGS_TEXT_SCALE,
    ).apply {
        tag = anchorKeyImpl("row", title)
    }
    targetHostImpl().addView(control, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, settingRowHeightImpl()
    ).apply { bottomMargin = settingRowGapImpl() })
}

internal fun PhoneSettingsActivity.pickImpl(
    title: String,
    labels: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    PhoneDialog(this, title)
        .choices(labels, selected, onPick)
        .button("取消")
        .show()
}

internal fun PhoneSettingsActivity.confirmImpl(
    title: String,
    message: String,
    danger: Boolean,
    onOk: () -> Unit,
) {
    PhoneDialog(this, title)
        .message(message)
        .button("取消")
        .button(if (danger) "清空" else "确定", primary = true, danger = danger, run = onOk)
        .show()
}

internal fun PhoneSettingsActivity.toastImpl(text: String) =
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
