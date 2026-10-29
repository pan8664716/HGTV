// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.compat

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.WindowManager
import kotlin.math.sqrt

/**
 * 这台机器在用户的眼里的形态：手机 / 平板 / 电视。
 *
 * 判成什么样只影响三件事：
 * 1. 需不需要触屏手势层、底部触屏控制条（[com.hgtv.star.MainActivity.touchArmed]）；
 * 2. 「竖屏短剧全屏」旋转的方向（电视 / 横屏平板 `+90`，手机 `−90`）；
 * 3. 设置页里「设备类型」那一格显示当前判定结果。
 *
 * 因此 [UNKNOWN] 只在探测本身失败时出现，正常路径一定落到前三档之一。
 */
internal enum class DeviceForm(val label: String) {
    PHONE("手机"),
    TABLET("平板"),
    TV("电视"),
    UNKNOWN("未判定");

    companion object {
        /** 落盘用的字符串（`auto` 之外的三档，见 `Library.deviceForm`）。 */
        fun ofStored(value: String): DeviceForm? = when (value) {
            "phone" -> PHONE
            "tablet" -> TABLET
            "tv" -> TV
            else -> null
        }

        fun storedValue(form: DeviceForm): String = when (form) {
            PHONE -> "phone"
            TABLET -> "tablet"
            TV -> "tv"
            UNKNOWN -> "auto"
        }
    }
}

/**
 * 一次探测的全部读数。
 *
 * [score] / [hits] 不是给用户看的，是给调试面板与线上反馈看的：判错的时候，
 * 「哪几条命中了、加起来几分」比一句「这是电视」有用得多。
 */
internal data class DeviceProfile(
    val form: DeviceForm,
    val touch: Boolean,
    val swDp: Int,
    val diagonalIn: Float,
    val score: Int,
    val hits: List<String>
) {
    /** 调试面板那一段：`触屏 否 · 短边 1080dp · 对角 55.0in · 11分 uiMode=tv+leanback+…`。 */
    val summary: String
        get() = buildString {
            append("触屏 ").append(if (touch) "是" else "否")
            append(" · 短边 ").append(swDp).append("dp")
            if (diagonalIn > 0f) append(" · 对角 ").append((diagonalIn * 10).toInt() / 10f).append("in")
            append(" · ").append(score).append("分")
            if (hits.isNotEmpty()) append(" ").append(hits.joinToString("+"))
        }
}

/**
 * 判断这台机器是手机 / 平板 / 电视。
 *
 * ## 为什么不能只看一个标志
 *
 * 单看任何一个都会误判，而这三档在国内市场上又都真实存在：
 *
 * - `UiModeManager.currentModeType == TELEVISION`：原生电视最可靠的一票，
 *   但大量国内盒子把它留在 `NORMAL`（系统没做电视那套 uiMode 适配）；
 * - `FEATURE_LEANBACK`：Google 认证的电视 / 盒子都有，可国产盒子常常既不给它、
 *   也不给 `FEATURE_TELEVISION`；
 * - `Configuration.touchscreen`：**这个项目里一律不用**（见 `MainActivityTouch.kt` 的说明），
 *   国内盒子谎报 `FINGER` 是常态；
 * - 物理对角线：盒子把 `densityDpi` 报成 160，55 寸电视算出来只有 13.8 寸，
 *   单看它会把电视判成手机；反过来，某些平板也会谎报 dpi。
 *
 * 所以这里走**打分**：每条证据各给一份权重，够分就判电视，不够分再按短边宽度
 * 区分手机与平板。`ro.build.characteristics` 里带 `tv` / `stb` / `box` 是国内盒子
 * 最有效的旁证（它描述的是「厂商把这台机器做成了什么」，不是应用能伪造的能力位）。
 *
 * ## 门槛为什么是 4 分
 *
 * | 证据 | 分 |
 * | --- | --- |
 * | `uiMode == TELEVISION` | 4 |
 * | `FEATURE_LEANBACK` | 4 |
 * | `FEATURE_TELEVISION` | 3 |
 * | 没有触屏、但有方向键 | 3 |
 * | `ro.build.characteristics` / 机型里带 tv、stb、box | 3 |
 * | 对角线 ≥ 24 寸 | 2 |
 * | 对角线 ≥ 20 寸且没有触屏 | 2 |
 * | 品牌词表（海信 / 创维 / TCL / 小米电视…） | 1 |
 *
 * - `score >= 4` → 电视：任意一条硬证据（uiMode / leanback）或两条软证据成立即可；
 * - **大屏 + 没有触屏 + 有方向键 → 电视**：不挣分，直接定性。0 分也能落这一档，
 *   因为「600dp 以上的平板一定带触屏」——反过来推，没有触屏的大屏就是电视 / 盒子；
 * - `score >= 3` 且没有触屏、对角线 ≥ 20 寸 → 也是电视（只有 `FEATURE_TELEVISION`
 *   的盒子、或只有 `characteristics` 的老盒子都落这一档）；
 * - 其余按短边 `swDp` 分：`>= 600` 是平板（Android 官方那条分界线），否则是手机。
 *
 * ## 短边为什么自己算
 *
 * `Configuration.smallestScreenWidthDp` 在一部分盒子上恒为 0（没跟着资源重算），
 * 而 `displayMetrics` 在任何设备上都有值。`min(宽,高) * 160 / densityDpi` 与它同义，
 * 且换分辨率 / 投屏之后重新调用一次就能拿到新值。
 */
internal fun Context.detectDeviceForm(): DeviceProfile {
    val hits = mutableListOf<String>()
    var score = 0
    val facts = screenFacts()
    val touch = hasInputSource(InputDevice.SOURCE_TOUCHSCREEN)
    val dpad = hasInputSource(InputDevice.SOURCE_DPAD)

    // ① 系统自己声明的形态：原生电视 / 一部分国产盒子会报。
    val uiMode = runCatching {
        (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
    }.getOrNull()
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) {
        score += 4
        hits += "uiMode=tv"
    }

    // ② 能力位：认证电视有 leanback，部分盒子只给 television。
    val pm = packageManager
    fun feature(name: String): Boolean = runCatching { pm.hasSystemFeature(name) }.getOrDefault(false)
    if (feature(PackageManager.FEATURE_LEANBACK)) {
        score += 4
        hits += "leanback"
    }
    if (feature(PackageManager.FEATURE_TELEVISION)) {
        score += 3
        hits += "television"
    }

    // ③ 输入设备：没有触屏、只有方向键——遥控器 + 电视的典型组合。
    //    **不用 `Configuration.touchscreen`**：国内盒子谎报 FINGER 是常态。
    if (!touch && dpad) {
        score += 3
        hits += "dpad"
    }

    // ④ 厂商把机器做成了什么：`ro.build.characteristics` 是最有力的旁证。
    //    机型 / 设备名里带 tv、box、stb 的也一起算（同一档，不重复加分）。
    val words = listOf(
        systemProperty("ro.build.characteristics").orEmpty(),
        systemProperty("ro.product.device").orEmpty(),
        Build.DEVICE, Build.BOARD, Build.MODEL, Build.MANUFACTURER
    ).joinToString(" ").lowercase()
    if (listOf("tv", "stb", "box", "ott", "droidtv").any { it in words }) {
        score += 3
        hits += "characteristics"
    }

    // ⑤ 物理尺寸：盒子上 dpi 经常是假的，因此只当旁证，不当主判据。
    if (facts.diagonalIn >= 24f) {
        score += 2
        hits += "≥24in"
    } else if (facts.diagonalIn >= 20f && !touch) {
        score += 2
        hits += "≥20in"
    }

    // ⑥ 品牌词表：海信 / 创维 / TCL / 小米 / 长虹 / 康佳 / 索尼 / 三星…的电视产品线。
    //    只加 1 分，单独成立不了，但能把「两分」的机器推到电视那一边。
    if (listOf(
            "hisense", "skyworth", "tcl", "changhong", "konka", "coocaa", "fissler",
            "mitv", "mibox", "letv", "leeco", "haier", "sharp", "philips", "sony",
            "samsung", "lge", "huawei"
        ).any { it in words }
    ) {
        score += 1
        hits += "brand"
    }

    // ⑦ 兜底的一条硬定性：**大屏 + 没有触屏 + 有方向键 = 电视 / 盒子**。
    //    上面六条都可能一条不中——实测的 S905L3B 盒子就是这样：`characteristics=mbx,nosdcard`、
    //    机型 `S905L3B`、`uiMode=NORMAL`、没有 leanback / television 能力位，只有
    //    「没有触屏、但有方向键」那 3 分；而它的 dpi 报 240，对角线算出来才 9 寸，
    //    于是旧规则把它按短边 720dp 判成**平板**，触屏层跟着亮起来、「竖屏短剧全屏」也跟着变灰。
    //    但 600dp 以上的平板一定带触屏（那是它的输入方式），没有触屏的大屏只能是电视 / 盒子。
    val remoteOnly = !touch && dpad && facts.swDp >= 600
    if (remoteOnly) hits += "大屏遥控"

    val form = when {
        score >= 4 -> DeviceForm.TV
        remoteOnly -> DeviceForm.TV
        score >= 3 && !touch && facts.diagonalIn >= 20f -> DeviceForm.TV
        facts.swDp >= 600 -> DeviceForm.TABLET
        else -> DeviceForm.PHONE
    }
    return DeviceProfile(form, touch, facts.swDp, facts.diagonalIn, score, hits)
}

/** 输入子系统里有没有注册过这一类设备（真报了，系统里就真有一件）。 */
private fun Context.hasInputSource(source: Int): Boolean = runCatching {
    InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        (device.sources and source) == source
    }
}.getOrDefault(false)

/**
 * 屏幕的两个数字：短边 dp、物理对角线英寸。
 *
 * 尺寸取**真实整屏**（`getRealMetrics`，API 17+），不是「应用可用区域」：
 * 分屏 / 挖孔 / 导航栏都会让后者偏小，而形态判定问的是这台机器有多大。
 * 对角线用 `xdpi` / `ydpi`；两者在盒子上常常是 0，此时退回 `densityDpi`
 * （等于按 160dpi 折算，算出来偏大，但那一档本来就只当旁证）。
 */
private class ScreenFacts(val swDp: Int, val diagonalIn: Float)

private fun Context.screenFacts(): ScreenFacts {
    val dm = resources.displayMetrics
    val real = DisplayMetrics()
    val hasReal = runCatching {
        @Suppress("DEPRECATION")
        val display = (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        display?.getRealMetrics(real)
    }.isSuccess && real.widthPixels > 0 && real.heightPixels > 0

    val width = if (hasReal) real.widthPixels else dm.widthPixels
    val height = if (hasReal) real.heightPixels else dm.heightPixels
    val density = (if (hasReal) real.densityDpi else dm.densityDpi).takeIf { it > 0 } ?: Resources.getSystem().displayMetrics.densityDpi
    val swDp = if (density > 0) minOf(width, height) * 160 / density else 0

    val xdpi = (if (hasReal) real.xdpi else dm.xdpi).takeIf { it > 1f } ?: density.toFloat()
    val ydpi = (if (hasReal) real.ydpi else dm.ydpi).takeIf { it > 1f } ?: density.toFloat()
    val diagonalIn = if (width > 0 && height > 0 && xdpi > 0f && ydpi > 0f) {
        val w = width / xdpi
        val h = height / ydpi
        sqrt(w * w + h * h)
    } else 0f
    return ScreenFacts(swDp, diagonalIn)
}

/**
 * 读一个系统属性（`ro.build.characteristics` 这类没有公开 API 的）。
 *
 * 反射 `android.os.SystemProperties.get`：在部分定制 ROM 上被隐藏 API 拦截，
 * 因此整段包 `runCatching`，拿不到就当空串——它只是一条旁证，缺了不影响判定。
 */
private val systemPropertiesClass: Class<*>? = runCatching {
    Class.forName("android.os.SystemProperties")
}.getOrNull()

internal fun systemProperty(key: String): String? = runCatching {
    systemPropertiesClass?.getMethod("get", String::class.java)?.invoke(null, key) as? String
}.getOrNull()
