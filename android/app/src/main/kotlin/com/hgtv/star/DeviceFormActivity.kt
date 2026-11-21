// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import com.hgtv.star.shared.ui.UiText
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.compat.DeviceFormSelection
import com.hgtv.star.shared.data.RecoverySnapshotManager
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.hgtv.star.shared.ui.TvThemeStore
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.tvDp
import com.hgtv.star.tv.tvText
import com.hgtv.star.tv.R as TvR

internal const val FIRST_RUN_PREFS="first-run-ui-v1"
internal const val KEY_DEVICE_FORM_CONFIRMED="deviceFormConfirmed"

/**
 * 首页首次启动与设置页共用的设备类型选择器。
 *
 * 选择器沿用当前页面的拟态弹窗：手机 / 平板设置与首页使用 [PhoneDialog]，电视设置
 * 与电视首页使用 [TvDialog]。手机 / 平板档必须触摸确认，6 秒无操作回到选择页；
 * 电视档直接保存并进入。
 */
class DeviceFormActivity : Activity() {
    private var tvDialog: TvDialog?=null
    private var phoneDialog: PhoneDialog?=null
    private var current: DeviceForm?=null
    private var firstRun=false
    private var tvStyle=false
    private var advancing=false
    private var finished=false
    private var chooserScheduled=false
    private val handler=Handler(Looper.getMainLooper())
    private var countdownTick: Runnable?=null
    private var countdownView: TextView?=null
    private var countdownTimeout: (() -> Unit)?=null
    private var countdownRemaining=6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstRun=intent.getBooleanExtra(DeviceFormSelection.EXTRA_FIRST_RUN,false)
        tvStyle=intent.getBooleanExtra(DeviceFormSelection.EXTRA_TV_STYLE,false)
        if(tvStyle) {
            TvStyle.applyTheme(TvThemeStore.load(this))
        } else {
            PhoneStyle.ensure(this)
        }
        current=DeviceForm.ofStored(
            intent.getStringExtra(DeviceFormSelection.EXTRA_CURRENT_FORM).orEmpty()
        )
        showChooser()
    }

    private fun showChooser() {
        if(finished || isFinishing || isDestroyed) return
        if(tvDialog?.isShowing==true || phoneDialog?.isShowing==true) return
        cancelCountdown()
        advancing=false
        if(tvStyle) showTvChooser() else showPhoneChooser()
    }

    private fun showTvChooser() {
        val picker=TvDialog(this,UiText.get(com.hgtv.star.shared.R.string.ui_0218))
        picker.choices(
            DeviceForm.pickerLabels(),
            selected=current?.let { DeviceForm.optionIndex(DeviceForm.storedValue(it)) } ?: -1,
        ) { index ->
            advancing=true
            choose(DeviceForm.SELECTABLE[index])
        }
        if(!firstRun) {
            picker.button(UiText.get(com.hgtv.star.shared.R.string.ui_0046),icon=TvR.drawable.ic_menu_close) { finishCanceled() }
        }
        tvDialog=picker
        picker.show(mandatory=firstRun) {
            if(tvDialog===picker) tvDialog=null
            if(!advancing && !firstRun) finishCanceled()
        }
    }

    private fun showPhoneChooser() {
        val picker=PhoneDialog(this,chooserTitle())
        picker.choices(
            DeviceForm.pickerLabels(),
            selected=current?.let { DeviceForm.optionIndex(DeviceForm.storedValue(it)) } ?: -1,
            onChoice={ index ->
                choose(DeviceForm.SELECTABLE[index])
            },
            beforeChoice={
                advancing=true
            },
        )
        if(!firstRun) {
            picker.button(UiText.get(com.hgtv.star.shared.R.string.ui_0046)) { finishCanceled() }
        }
        phoneDialog=picker
        picker.show {
            if(phoneDialog===picker) phoneDialog=null
            if(!advancing) {
                if(firstRun) {
                    scheduleChooser()
                } else {
                    finishCanceled()
                }
            }
        }
    }

    private fun chooserTitle(): String =
        if(!firstRun && !tvStyle) "设备类型（切换后自动重新进入）" else "设备类型"

    private fun choose(form: DeviceForm) {
        if(form==DeviceForm.TV) {
            saveAndFinish(form)
        } else {
            if(tvStyle) showTvConfirmation(form) else showPhoneConfirmation(form)
        }
    }

    private fun showTvConfirmation(form: DeviceForm) {
        if(finished || isFinishing || isDestroyed) return
        val confirm=TvDialog(this,UiText.get(com.hgtv.star.shared.R.string.ui_0199, form.label))
        confirmationMessages(form).forEach { confirm.message(it) }
        val countdownView=tvText("6 秒后自动取消",15f,TvStyle.textStrong)
        confirm.content.addView(
            countdownView,
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=tvDp(TvStyle.GAP_INLINE) },
        )
        val confirmButton=confirm.button(
            "触屏点击确认",
            icon=TvR.drawable.ic_menu_check,
        ) {
            saveAndFinish(form)
        }
        touchOnly(confirmButton)
        tvDialog=confirm
        confirm.show(
            touchOnly=true,
            mandatory=true,
            onUserTouch={ resetCountdown() },
        ) {
            if(tvDialog===confirm) tvDialog=null
            cancelCountdown()
            if(!finished) scheduleChooser()
        }
        startCountdown(countdownView) { confirm.dismiss() }
    }

    private fun showPhoneConfirmation(form: DeviceForm) {
        if(finished || isFinishing || isDestroyed) return
        val confirm=PhoneDialog(this,UiText.get(com.hgtv.star.shared.R.string.ui_0199, form.label))
        confirmationMessages(form).forEach { confirm.message(it) }
        val countdownView=TextView(this).apply {
            text=UiText.get(com.hgtv.star.shared.R.string.ui_0016)
            setTextColor(PhoneStyle.text)
            phoneSize(15)
            setPadding(0,dp(4),0,dp(4))
        }
        confirm.content.addView(
            countdownView,
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) },
        )
        val confirmButton=confirm.buttonView("触屏点击确认") {
            saveAndFinish(form)
        }
        touchOnly(confirmButton)
        phoneDialog=confirm
        confirm.onUserTouch { resetCountdown() }
        confirm.show {
            if(phoneDialog===confirm) phoneDialog=null
            cancelCountdown()
            if(!finished) scheduleChooser()
        }
        startCountdown(countdownView) { confirm.dismiss() }
    }

    private fun confirmationMessages(form: DeviceForm): List<String> = listOf(
        "切换到${form.label}后，界面会改为触屏操作，遥控器将无法完成这次确认。",
        "请使用手机 / 平板的触摸屏点击下方按钮。若本机没有触摸屏，请不要继续；" +
            "弹窗将在 6 秒后自动取消，不保存本次选择。",
    )

    /** 确认按钮只接受手指触摸；键盘、遥控器和鼠标事件都不触发点击。 */
    private fun touchOnly(view: View) {
        view.isFocusable=false
        view.isFocusableInTouchMode=false
        view.setOnKeyListener { _,_,_-> true }
        view.setOnTouchListener { _,event ->
            !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
        }
    }

    private fun startCountdown(view: TextView, onTimeout: () -> Unit) {
        cancelCountdown()
        countdownView=view
        countdownTimeout=onTimeout
        countdownRemaining=6
        scheduleCountdownTick()
    }

    private fun resetCountdown() {
        if(countdownTimeout==null) return
        countdownTick?.let(handler::removeCallbacks)
        countdownRemaining=6
        countdownView?.text=UiText.get(com.hgtv.star.shared.R.string.ui_0016)
        scheduleCountdownTick()
    }

    private fun scheduleCountdownTick() {
        val tick=object: Runnable {
            override fun run() {
                if(countdownTick!==this) return
                countdownRemaining--
                if(countdownRemaining<=0) {
                    val timeout=countdownTimeout
                    cancelCountdown()
                    timeout?.invoke()
                    return
                }
                countdownView?.text=UiText.get(com.hgtv.star.shared.R.string.ui_0001, countdownRemaining)
                handler.postDelayed(this,1000L)
            }
        }
        countdownTick=tick
        handler.postDelayed(tick,1000L)
    }

    private fun cancelCountdown() {
        countdownTick?.let(handler::removeCallbacks)
        countdownTick=null
        countdownView=null
        countdownTimeout=null
        countdownRemaining=6
    }

    private fun saveAndFinish(form: DeviceForm) {
        val selected=DeviceForm.storedValue(form)
        val formSaved=getSharedPreferences(LIBRARY_PREFS,MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_FORM,selected)
            .commit()
        if(!formSaved) {
            Toast.makeText(this,UiText.get(com.hgtv.star.shared.R.string.ui_0219),Toast.LENGTH_SHORT).show()
            closeDialogs()
            scheduleChooser()
            return
        }
        if(firstRun) {
            val promptSaved=getSharedPreferences(FIRST_RUN_PREFS,MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DEVICE_FORM_CONFIRMED,true)
                .commit()
            if(!promptSaved) {
                Toast.makeText(this,UiText.get(com.hgtv.star.shared.R.string.ui_0267),Toast.LENGTH_SHORT).show()
                closeDialogs()
                scheduleChooser()
                return
            }
        }
        AppRuntime.library(this).deviceForm=selected
        RecoverySnapshotManager.schedule(applicationContext,0L)
        PlaybackStartupTrace.point("deviceForm.selected", "form=$selected firstRun=$firstRun")
        HgtvStartup.onDeviceFormSelected(this)
        finished=true
        cancelCountdown()
        closeDialogs()
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(DeviceFormSelection.EXTRA_SELECTED_FORM,selected)
                putExtra(DeviceFormSelection.EXTRA_CHANGED,current?.let { DeviceForm.storedValue(it) }!=selected)
            },
        )
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0,0)
    }

    private fun finishCanceled() {
        if(finished) return
        finished=true
        cancelCountdown()
        closeDialogs()
        setResult(RESULT_CANCELED)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0,0)
    }

    private fun closeDialogs() {
        tvDialog?.dismiss()
        tvDialog=null
        phoneDialog?.dismiss()
        phoneDialog=null
    }

    private fun scheduleChooser() {
        if(chooserScheduled || finished || isFinishing || isDestroyed) return
        chooserScheduled=true
        rootWindow().post {
            chooserScheduled=false
            showChooser()
        }
    }

    private fun rootWindow() = window.decorView

    @Deprecated("Activity 的旧回调；项目不引入 AndroidX Activity Result")
    override fun onBackPressed() {
        if(firstRun) return
        finishCanceled()
    }

    override fun onDestroy() {
        cancelCountdown()
        closeDialogs()
        super.onDestroy()
    }

    private companion object {
        const val LIBRARY_PREFS="native-library-v1"
        const val KEY_DEVICE_FORM="deviceForm"
    }
}
