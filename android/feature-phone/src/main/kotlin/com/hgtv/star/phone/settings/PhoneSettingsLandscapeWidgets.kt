// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.hgtv.star.phone.ui.PhoneSettingSlider
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneSwitchRow
import com.hgtv.star.shared.ui.SoftStyle

/** 与 TV 设置页同一种结构：整宽面板 + 面板标题/说明 + 两列或四列设置格。 */
internal fun PhoneSettingsActivity.landscapePanel(
    title: String,
    subtitle: String,
): LinearLayout {
    val panel=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(settingsDpImpl(12),settingsDpImpl(10),settingsDpImpl(12),settingsDpImpl(8))
        applySoft(SoftStyle.RAISED,PhoneStyle.surface,PhoneStyle.RADIUS_CARD,3f)
    }
    panel.addView(TextView(this).apply {
        text=title
        setTextColor(PhoneStyle.text)
        settingsTextSizeImpl(13)
        setTypeface(typeface,Typeface.BOLD)
        setPadding(settingsDpImpl(2),0,settingsDpImpl(2),0)
    })
    panel.addView(TextView(this).apply {
        text=subtitle
        setTextColor(PhoneStyle.textDim)
        settingsTextSizeImpl(11)
        setPadding(settingsDpImpl(2),settingsDpImpl(3),settingsDpImpl(2),settingsDpImpl(8))
    })
    settingsContainer.addView(panel,LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin=settingsDpImpl(8) })
    settingsSectionHost=panel
    return panel
}

/** 横屏设置格的两列流式容器：每行最多两个按钮，单按钮也固定占半行。 */
internal fun PhoneSettingsActivity.landscapeLine(panel: LinearLayout): LinearLayout {
    val flow=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    panel.addView(flow,LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin=settingsDpImpl(6) })
    return flow
}

internal fun PhoneSettingsActivity.landscapeCell(flow: LinearLayout, view: View) {
    val lastRow=flow.getChildAt(flow.childCount-1) as? LinearLayout
    val spacer=lastRow?.findViewWithTag<View>(TAG_LANDSCAPE_SETTING_SPACER)
    if(lastRow!=null && spacer!=null) {
        lastRow.removeView(spacer)
        lastRow.addView(view,LinearLayout.LayoutParams(
            0,settingRowHeightImpl(),1f
        ).apply { leftMargin=settingsDpImpl(6) })
        return
    }
    val row=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
        tag=TAG_LANDSCAPE_SETTING_ROW
    }
    flow.addView(row,LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { if(flow.childCount>1) topMargin=settingsDpImpl(6) })
    row.addView(view,LinearLayout.LayoutParams(0,settingRowHeightImpl(),1f))
    row.addView(
        Space(this).apply { tag=TAG_LANDSCAPE_SETTING_SPACER },
        LinearLayout.LayoutParams(0,1,1f),
    )
}

/** 横屏双栏：左、右两列各自纵向排列。 */
internal fun PhoneSettingsActivity.landscapeColumns(
    panel: LinearLayout,
): Pair<LinearLayout,LinearLayout> {
    val row=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL
        gravity=Gravity.TOP
    }
    val left=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    val right=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    row.addView(left,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
    row.addView(right,LinearLayout.LayoutParams(
        0,ViewGroup.LayoutParams.WRAP_CONTENT,1f
    ).apply { leftMargin=settingsDpImpl(6) })
    panel.addView(row,LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    return left to right
}

internal fun PhoneSettingsActivity.landscapeColumnCell(column: LinearLayout, view: View) {
    column.addView(view,LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,settingRowHeightImpl()
    ).apply { if(column.childCount>0) topMargin=settingsDpImpl(6) })
}

internal fun PhoneSettingsActivity.landscapeSetting(
    title: String,
    value: String,
    badge: Boolean = false,
    onClick: () -> Unit,
): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
        setPadding(settingsDpImpl(12),0,settingsDpImpl(10),0)
        applySoft(SoftStyle.RAISED,PhoneStyle.surfaceHigh,PhoneStyle.RADIUS_PILL,4f)
        val titleSlot=LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        titleSlot.addView(TextView(activity).apply {
            text=title
            setTextColor(PhoneStyle.text)
            settingsTextSizeImpl(13)
            maxLines=1
            ellipsize=TextUtils.TruncateAt.END
        },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        if(badge) {
            titleSlot.addView(View(activity).apply {
                background=GradientDrawable().apply {
                    shape=GradientDrawable.OVAL
                    setColor(0xFFE5484D.toInt())
                }
            },LinearLayout.LayoutParams(
                settingsDpImpl(6),settingsDpImpl(6)
            ).apply { leftMargin=settingsDpImpl(6) })
        }
        addView(titleSlot,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        addView(TextView(activity).apply {
            text=value
            setTextColor(PhoneStyle.textDim)
            settingsTextSizeImpl(11)
            maxLines=1
            ellipsize=TextUtils.TruncateAt.END
        })
        setOnClickListener { onClick() }
    }
}

internal fun PhoneSettingsActivity.landscapeSwitch(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
): View = PhoneSwitchRow(
    this,title,checked,
    densityScale=SETTINGS_DENSITY_SCALE,
    textScale=SETTINGS_TEXT_SCALE,
    onToggle=onToggle,
)

internal fun PhoneSettingsActivity.landscapeSlider(
    title: String,
    min: Float,
    max: Float,
    step: Float,
    value: Float,
    format: (Float) -> String,
    onValue: (Float) -> Unit,
): View = PhoneSettingSlider(
    this,title,min,max,step,value,onValue,format,
    densityScale=SETTINGS_DENSITY_SCALE,
    textScale=SETTINGS_TEXT_SCALE,
)
