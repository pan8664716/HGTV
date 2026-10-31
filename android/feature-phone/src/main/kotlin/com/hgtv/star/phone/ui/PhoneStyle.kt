// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Color
import com.hgtv.star.shared.ui.Soft
import com.hgtv.star.shared.ui.SoftPalette
import com.hgtv.star.shared.ui.TvTheme
import com.hgtv.star.shared.ui.TvThemeStore

/**
 * 手机端的新拟态令牌。
 *
 * 与 TV 端同一套设计语言（见 `docs/UI_NEUMORPHISM.md`）：全站一块石墨材质，
 * 层次只靠「凸起 / 凹陷」与柔光表达，**不用描边、不用色块区分层级**。
 *
 * 三档明度（背景 / 凸起面 / 凹陷面）之间只差 3% 左右——这是新拟态的关键：
 * 差值再大就变成「卡片 + 描边」，再小则 3 米外分不清哪里能点。
 *
 * 品牌红 `#FE2C55` 只做**线与小色块**（进度条、选中图标、主按钮），
 * 不做大面积底色：低饱和的材质里出现一块满饱和的色块，会像从别的应用贴进来的。
 */
object PhoneStyle {

    // —— 主题 ——

    /**
     * 当前主题。
     *
     * 与 TV 端**同一份定义、同一个落盘文件**（`TvThemeStore`，见 `shared/ui/TvTheme.kt`）：
     * 二十套配色只写一次，两端的「主题外观」设置项指向同一张表。默认深海蓝。
     *
     * 与 TV 的区别只有一处：TV 在建视图之前由 `MainActivity.onCreate` 读一次，
     * 手机端是**每个 Activity 进来时**调 [ensure]——手机上页面之间可以各自被系统单独拉起
     *（从最近任务、通知、深链），不保证一定经过主页面。
     */
    @Volatile private var current: TvTheme = TvTheme.DEEP_SEA_BLUE
    @Volatile private var loaded = false

    val theme: TvTheme get() = current

    /** 读一次落盘的主题；已经读过就直接返回（进程内只读一次盘）。 */
    fun ensure(context: Context) {
        if (loaded) return
        loaded = true
        applyTheme(TvThemeStore.load(context.applicationContext))
    }

    /**
     * 换主题：只改色值，不动几何（圆角、柔光带都不变）。
     *
     * 必须顺带做两件事：丢掉柔光位图缓存（里面烤着旧材质的投影色），
     * 由调用方重建当前页面（已建好的 View 挂着旧 Drawable）。
     */
    fun applyTheme(value: TvTheme) {
        if (value == current && loaded && SoftPalette.themeId == value.ordinal) return
        current = value
        loaded = true
        SoftPalette.themeId = value.ordinal
        SoftPalette.shadowDark = value.shadowDark
        SoftPalette.shadowLight = value.shadowLight
        SoftPalette.innerDark = value.innerDark
        SoftPalette.innerLight = value.innerLight
        Soft.trim()
    }

    init {
        SoftPalette.themeId = current.ordinal
        SoftPalette.shadowDark = current.shadowDark
        SoftPalette.shadowLight = current.shadowLight
        SoftPalette.innerDark = current.innerDark
        SoftPalette.innerLight = current.innerLight
    }

    // —— 材质 ——

    /**
     * 页面底板，也是柔光的基准色。
     *
     * **与 TV 端默认主题「深海蓝」逐值一致**（`TvTheme.DEEP_SEA_BLUE`）：
     * 两端同一套配色，用户在电视上看到的和手机上看到的才是同一个应用。
     */
    val background: Int get() = current.base

    /** 凸起面：按钮、卡片、导航栏（= TV 的 `surface`）。 */
    val surface: Int get() = current.surface

    /** 抬升面：浮层、弹窗、抬一档的选中态。 */
    val surfaceHigh: Int get() = current.surfaceHigh

    /** 凹陷面：搜索框、输入框、进度槽（= TV 的 `well`）。 */
    val well: Int get() = current.well

    /** 深凹槽：进度条轨道、禁用态。 */
    val wellDeep: Int get() = current.wellDeep

    // —— 柔光 ——

    val shadowDark: Int get() = current.shadowDark
    val shadowLight: Int get() = current.shadowLight
    val innerDark: Int get() = current.innerDark
    val innerLight: Int get() = current.innerLight

    // —— 文字 ——

    /** 与 TV 同值（`TvTheme.text` / `textDim`）。 */
    val text: Int get() = current.text
    val textDim: Int get() = current.textDim
    val textFaint: Int get() = (current.textDim and 0x00FFFFFF) or (0x80 shl 24)

    // —— 强调 ——

    /** 强调色（= TV 的 `accent`）：只做线与小色块。 */
    val accent: Int get() = current.accent

    /** 焦点环（= TV 的 `focusRing`）。 */
    val focusRing: Int get() = current.focusRing

    /** 提示色（= TV 的 `tips`）：榜单前三、评分星标。 */
    val tips: Int get() = current.tips

    /** 亮面板（主操作按钮）：材质里最亮的一档。 */
    val lit: Int get() = current.lit

    /** 亮面板上的字。 */
    val onLit: Int get() = current.onLit

    // —— 几何 ——

    /** 柔光带：控件的 padding，投影与高光全部画在这条带里。 */
    const val SOFT_BAND = 6f

    /** 大面板的柔光带。 */
    const val SOFT_BAND_LARGE = 10f

    /** 卡片圆角：小圆角，避免把海报压成胶囊形。 */
    const val RADIUS_CARD = 8f

    /** 胶囊（按钮、chip、导航）。 */
    const val RADIUS_PILL = 26f

    /** 弹窗 / 抽屉。 */
    const val RADIUS_SHEET = 24f

    /** 进度条轨道高度。 */
    const val TRACK_HEIGHT = 3f

    /** 播放界面压在视频上的字，永远暖白，不跟主题。 */
    val playerText = 0xFFF5F2EC.toInt()
    val playerDim = 0xB3F5F2EC.toInt()
    val playerLine = 0x33FFFFFF

    /** 分隔线。 */
    val separator: Int get() = current.separator

    /** 空色。 */
    val transparent = Color.TRANSPARENT
}
