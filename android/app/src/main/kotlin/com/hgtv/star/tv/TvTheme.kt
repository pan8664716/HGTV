// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Color

/**
 * 主题：二十套新拟态配色（设置 → 主题外观）。
 *
 * 2026-10-20 按设计稿重做。一套配色由八个语义色完整定义：
 *
 * | 令牌 | 用途 |
 * | --- | --- |
 * | [base] 底色 | 页面底板，也是所有控件的材质基准 |
 * | [light] 亮阴影 | 左上高光 |
 * | [dark] 暗阴影 | 右下投影 |
 * | [text] 主文字 | 标题、正文 |
 * | [sub] 次文字 | 说明、辅助信息 |
 * | [focus] 焦点色 | 焦点环、输入框聚焦线 |
 * | [accent] 强调色 | 进度条、开关滑块、名次星标 |
 * | [danger] 状态色 | 删除、清空、错误提示 |
 *
 * 其余档位仍然由这三个材质色派生（凸起 / 抬升 / 凹陷 / 深槽），
 * 这样二十套主题共用同一条几何规则，只换颜色：
 *
 * | 派生 | 算法 | 用途 |
 * | --- | --- | --- |
 * | [surface] 凸起面 | 底色 → 亮阴影 45% | 按钮、卡片、设置项的常态 |
 * | [surfaceHigh] 抬升面 | 底色 → 亮阴影 72% | 焦点容器、设置分组 |
 * | [well] 凹陷面 | 底色 → 暗阴影 45% | 搜索框、海报井、选中态 |
 * | [wellDeep] 深槽 | 底色 → 暗阴影 72% | 禁用态、进度条轨道 |
 * | [lit] 焦点面 | 浅色主题取亮阴影；深色主题向白色提亮 | 焦点控件、主操作 |
 *
 * 十套深色 + 十套浅色，顺序与设计稿一致。[DEEP_SEA_BLUE] 是默认主题；
 * 旧版本落盘的 storedValue 通过 [of] 的别名表迁移到最接近的新配色，
 * 升级后用户的选择不会丢。
 *
 * `tools/theme-preview.py` 与这张表一一对应，改色时两处一起改。
 */
internal enum class TvTheme(
    /** 设置里显示的名字。 */
    val label: String,
    /** 气质 / 适用场景。 */
    val scene: String,
    /** 落盘用的稳定字符串：改了显示名也不会丢用户的选择。 */
    val storedValue: String,
    /** 背景与元素底色。 */
    val base: Int,
    /** 亮部阴影色：左上高光。 */
    val light: Int,
    /** 暗部阴影色：右下投影。 */
    val dark: Int,
    /** 主文字。 */
    val text: Int,
    /** 次文字。 */
    val sub: Int,
    /** 焦点色：焦点环、输入框聚焦线。 */
    val focus: Int,
    /** 强调色：进度条、开关滑块、名次星标。 */
    val accent: Int,
    /** 状态色：删除、清空、错误提示。 */
    val danger: Int,
    /** 浅色主题还是深色主题：只影响柔光强度与焦点面的提亮方式。 */
    val isDark: Boolean
) {
    // —— 深色系 10 套 ——

    /** 深海蓝：默认主题，冷调深灰蓝。 */
    DEEP_SEA_BLUE(
        "深海蓝", "默认 · 冷调深色", "deep-sea-blue",
        0xFF1E2228.toInt(), 0xFF2A3038.toInt(), 0xFF12151A.toInt(),
        0xFFF2F5F8.toInt(), 0xFFAAB4C0.toInt(), 0xFF4DA3FF.toInt(),
        0xFF5B8DEF.toInt(), 0xFFFF6B6B.toInt(), true
    ),

    /** 石墨灰：中性深灰，克制耐看。 */
    GRAPHITE(
        "石墨灰", "中性深灰", "graphite",
        0xFF23272C.toInt(), 0xFF2F343A.toInt(), 0xFF171A1E.toInt(),
        0xFFF4F6F8.toInt(), 0xFFA6AFB8.toInt(), 0xFF7FD1FF.toInt(),
        0xFF6FA8FF.toInt(), 0xFFFF7A7A.toInt(), true
    ),

    /** 午夜紫：冷紫夜色。 */
    MIDNIGHT_PURPLE(
        "午夜紫", "冷紫夜色", "midnight-purple",
        0xFF211F2B.toInt(), 0xFF2E2B3A.toInt(), 0xFF15131C.toInt(),
        0xFFF3F1FA.toInt(), 0xFFADA8C2.toInt(), 0xFFB98CFF.toInt(),
        0xFF9B7BFF.toInt(), 0xFFFF6B8A.toInt(), true
    ),

    /** 松林绿：墨绿夜色。 */
    PINE_GREEN(
        "松林绿", "墨绿夜色", "pine-green",
        0xFF1B2320.toInt(), 0xFF26302C.toInt(), 0xFF111715.toInt(),
        0xFFEFF6F2.toInt(), 0xFFA3B5AC.toInt(), 0xFF4FE0A8.toInt(),
        0xFF3ECF98.toInt(), 0xFFFF7A7A.toInt(), true
    ),

    /** 暖棕夜：暖棕底色。 */
    WARM_BROWN(
        "暖棕夜", "暖棕夜色", "warm-brown",
        0xFF241E1A.toInt(), 0xFF312822.toInt(), 0xFF16110E.toInt(),
        0xFFF7F0EA.toInt(), 0xFFC0ADA0.toInt(), 0xFFFFB27A.toInt(),
        0xFFFF9A5C.toInt(), 0xFFFF6B6B.toInt(), true
    ),

    /** 酒红夜：酒红点缀。 */
    WINE_RED(
        "酒红夜", "酒红夜色", "wine-red",
        0xFF251A1F.toInt(), 0xFF33242B.toInt(), 0xFF170F13.toInt(),
        0xFFFAF0F3.toInt(), 0xFFC3A6AE.toInt(), 0xFFFF7EA8.toInt(),
        0xFFFF5C8A.toInt(), 0xFFFF4D4D.toInt(), true
    ),

    /** 暗金岩：暗金点缀。 */
    DARK_GOLD(
        "暗金岩", "暗金岩色", "dark-gold",
        0xFF221F18.toInt(), 0xFF2F2B22.toInt(), 0xFF15130E.toInt(),
        0xFFF7F3EA.toInt(), 0xFFBFB5A0.toInt(), 0xFFE8C36A.toInt(),
        0xFFD9B154.toInt(), 0xFFFF7A6B.toInt(), true
    ),

    /** 深海青：青蓝深色。 */
    DEEP_TEAL(
        "深海青", "青蓝深色", "deep-teal",
        0xFF182128.toInt(), 0xFF232F37.toInt(), 0xFF0F161B.toInt(),
        0xFFEAF4F8.toInt(), 0xFF9FB6C2.toInt(), 0xFF38D2E0.toInt(),
        0xFF2EC4D6.toInt(), 0xFFFF6B7A.toInt(), true
    ),

    /** 钢蓝夜：钢蓝深色。 */
    STEEL_BLUE(
        "钢蓝夜", "钢蓝深色", "steel-blue",
        0xFF1A1F2B.toInt(), 0xFF252C3A.toInt(), 0xFF10141C.toInt(),
        0xFFEEF2FA.toInt(), 0xFF9FAABF.toInt(), 0xFF7A9CFF.toInt(),
        0xFF5B7CE0.toInt(), 0xFFFF6B7A.toInt(), true
    ),

    /** 墨紫夜：墨紫深色。 */
    INK_PURPLE(
        "墨紫夜", "墨紫深色", "ink-purple",
        0xFF1C1A26.toInt(), 0xFF272433.toInt(), 0xFF111018.toInt(),
        0xFFF0EEFA.toInt(), 0xFFA8A3BE.toInt(), 0xFFC77DFF.toInt(),
        0xFFA55BFF.toInt(), 0xFFFF6B9A.toInt(), true
    ),

    // —— 浅色系 10 套 ——

    /** 雾霾蓝：最经典的浅色新拟态。 */
    HAZE_BLUE(
        "雾霾蓝", "雾霾蓝 · 浅色", "haze-blue",
        0xFFE0E5EC.toInt(), 0xFFFFFFFF.toInt(), 0xFFA3B1C6.toInt(),
        0xFF3A4553.toInt(), 0xFF6B7788.toInt(), 0xFF4A7DFF.toInt(),
        0xFF5B8DEF.toInt(), 0xFFE5484D.toInt(), false
    ),

    /** 奶油米：暖白米色。 */
    CREAM(
        "奶油米", "奶油米 · 浅色", "cream",
        0xFFEFEAE3.toInt(), 0xFFFFFFFF.toInt(), 0xFFC3B8A8.toInt(),
        0xFF3B352C.toInt(), 0xFF7A7164.toInt(), 0xFFE08A3C.toInt(),
        0xFFD9822B.toInt(), 0xFFD64545.toInt(), false
    ),

    /** 薄荷浅：浅薄荷绿。 */
    MINT(
        "薄荷浅", "薄荷浅 · 浅色", "mint",
        0xFFE3EEE9.toInt(), 0xFFFFFFFF.toInt(), 0xFFAABFB6.toInt(),
        0xFF2F3E38.toInt(), 0xFF66796F.toInt(), 0xFF12A67A.toInt(),
        0xFF0E9A70.toInt(), 0xFFD64545.toInt(), false
    ),

    /** 樱粉浅：浅樱粉。 */
    SAKURA(
        "樱粉浅", "樱粉浅 · 浅色", "sakura",
        0xFFF0E6EA.toInt(), 0xFFFFFFFF.toInt(), 0xFFC9B2BC.toInt(),
        0xFF3E2F35.toInt(), 0xFF7C6670.toInt(), 0xFFD6468C.toInt(),
        0xFFC93C82.toInt(), 0xFFD64545.toInt(), false
    ),

    /** 薰衣浅：浅薰衣草紫。 */
    LAVENDER(
        "薰衣浅", "薰衣浅 · 浅色", "lavender",
        0xFFE9E6F2.toInt(), 0xFFFFFFFF.toInt(), 0xFFB8B0CE.toInt(),
        0xFF35304A.toInt(), 0xFF6F6888.toInt(), 0xFF7C5CFF.toInt(),
        0xFF6B4DFF.toInt(), 0xFFD64545.toInt(), false
    ),

    /** 天青浅：浅天青色。 */
    SKY_BLUE(
        "天青浅", "天青浅 · 浅色", "sky-blue",
        0xFFE2EDF2.toInt(), 0xFFFFFFFF.toInt(), 0xFFA6BCC8.toInt(),
        0xFF2C3C45.toInt(), 0xFF627885.toInt(), 0xFF1A9BC7.toInt(),
        0xFF128FBA.toInt(), 0xFFD64545.toInt(), false
    ),

    /** 燕麦灰：暖灰中性色。 */
    OAT_GRAY(
        "燕麦灰", "燕麦灰 · 浅色", "oat-gray",
        0xFFECE9E4.toInt(), 0xFFFFFFFF.toInt(), 0xFFBFB9AF.toInt(),
        0xFF3A362F.toInt(), 0xFF787167.toInt(), 0xFF8A7B5E.toInt(),
        0xFF7A6C4F.toInt(), 0xFFD64545.toInt(), false
    ),

    /** 浅柠檬：低饱和黄绿。 */
    LEMON(
        "浅柠檬", "浅柠檬 · 浅色", "lemon",
        0xFFF1EFE0.toInt(), 0xFFFFFFFF.toInt(), 0xFFC8C2A6.toInt(),
        0xFF3A3826.toInt(), 0xFF7B775D.toInt(), 0xFFB6A21E.toInt(),
        0xFFA38F12.toInt(), 0xFFD64545.toInt(), false
    ),

    /** 浅珊瑚：暖粉珊瑚色。 */
    CORAL(
        "浅珊瑚", "浅珊瑚 · 浅色", "coral",
        0xFFF3E7E4.toInt(), 0xFFFFFFFF.toInt(), 0xFFCDB5AF.toInt(),
        0xFF432F2B.toInt(), 0xFF876E68.toInt(), 0xFFE2654A.toInt(),
        0xFFD9543A.toInt(), 0xFFC0392B.toInt(), false
    ),

    /** 浅灰绿：低饱和灰绿。 */
    SAGE(
        "浅灰绿", "浅灰绿 · 浅色", "sage",
        0xFFE8EDE6.toInt(), 0xFFFFFFFF.toInt(), 0xFFB5C0B0.toInt(),
        0xFF333D2F.toInt(), 0xFF6E7868.toInt(), 0xFF5A9A45.toInt(),
        0xFF4C8C39.toInt(), 0xFFD64545.toInt(), false
    );

    /** 凸起面：按钮、卡片、设置项的常态。 */
    val surface: Int get() = mix(base, light, .45f)

    /** 抬升面：焦点容器、设置分组、抬一档的选中态。 */
    val surfaceHigh: Int get() = mix(base, light, .72f)

    /** 凹陷面：搜索框、海报井，以及被按进去的选中态。 */
    val well: Int get() = mix(base, dark, .45f)

    /** 更深的凹槽：禁用态、进度条轨道。 */
    val wellDeep: Int get() = mix(base, dark, .72f)

    /**
     * 焦点面：浅色主题直接取亮阴影（通常是纯白），
     * 深色主题的亮阴影只有一档灰，必须继续向白色提亮，焦点才立得出来。
     */
    val lit: Int get() = if (isDark) mix(light, 0xFFFFFFFF.toInt(), .82f) else light

    /** 亮面板上的字：深色主题用近黑，浅色主题用比正文更深的墨色。 */
    val onLit: Int get() =
        if (isDark) 0xFF15181C.toInt() else mix(text, 0xFF0B0D10.toInt(), .42f)

    /** 焦点面外的一圈柔光：直接用焦点色，换主题时跟着换。 */
    val litGlow: Int get() = alpha(focus, if (isDark) .24f else .16f)

    /** 焦点环：设计稿里的 `--focus`，压到 90% 不透明。 */
    val focusRing: Int get() = alpha(focus, .90f)

    /** 凹陷控件（输入框）的聚焦线：同样取焦点色。 */
    val focusLine: Int get() = focus

    /** 正文强调（88%）：区块标题。 */
    val textStrong: Int get() = alpha(text, .88f)

    /** 卡片标题：主文字与次文字之间的一档。 */
    val textDim: Int get() = mix(text, sub, .55f)

    /** 辅助说明：直接用设计稿给的次文字。 */
    val muted: Int get() = sub

    /** 必须画一条线的地方（输入框边框、二维码衬底）。 */
    val outline: Int get() = alpha(text, .10f)

    /** 分组分隔线。 */
    val separator: Int get() = alpha(text, .08f)

    /** 名次星标与提示色：跟随强调色。 */
    val tips: Int get() = accent

    // 柔光强度：深色底本身暗，投影要更实；浅色底的暗阴影已经是一块中灰。
    val shadowDark: Int get() = alpha(dark, if (isDark) .62f else .72f)
    val shadowLight: Int get() = alpha(light, if (isDark) .62f else .72f)
    val innerDark: Int get() = alpha(dark, if (isDark) .50f else .72f)
    val innerLight: Int get() = alpha(light, if (isDark) .10f else .50f)

    companion object {
        /** 读不到偏好时用深海蓝：默认主题，也是设计稿的第一套。 */
        val fallback: TvTheme = DEEP_SEA_BLUE

        /**
         * 旧版本落盘的键 → 新主题。
         *
         * 十四套旧主题的色值大多能落到新表里最接近的一套；映射而不是清空，
         * 是为了让升级后的第一次启动仍然是用户熟悉的那块材质。
         */
        private val legacy: Map<String, TvTheme> = mapOf(
            "soft-gray" to HAZE_BLUE,
            "creamy-milk" to CREAM,
            "deep-dark" to DEEP_SEA_BLUE,
            "cool-mint" to MINT,
            "porcelain" to OAT_GRAY,
            "moonstone" to HAZE_BLUE,
            "ivory-sand" to OAT_GRAY,
            "silver-sage" to SAGE,
            "blue-haze" to HAZE_BLUE,
            "champagne-silk" to OAT_GRAY,
            "dusty-mauve" to SAKURA,
            "pale-olive" to SAGE,
            "graphite" to GRAPHITE,
            "midnight-blue" to STEEL_BLUE
        )

        /** 按落盘字符串取主题；未知值（降级、手改配置）一律退回 [fallback]。 */
        fun of(value: String?): TvTheme =
            entries.firstOrNull { it.storedValue == value } ?: legacy[value] ?: fallback
    }
}

/** 两色线性混合：[ratio] 为 0 取 [from]，为 1 取 [to]。 */
private fun mix(from: Int, to: Int, ratio: Float): Int = Color.rgb(
    (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt(),
    (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt(),
    (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt()
)

/** 同一个色相、换一档不透明度。 */
private fun alpha(color: Int, ratio: Float): Int =
    (color and 0x00FFFFFF) or (((ratio * 255f).toInt().coerceIn(0, 255)) shl 24)

/**
 * 主题偏好的落盘：一个独立的小 SharedPreferences 文件。
 *
 * 主题是纯界面偏好，因此**不进** `com.hgtv.star.core` 的 `Library`：
 * 那里装的是内容与观看记录，还要参与手机备份的合并与冲突处理，
 * 让一套配色跟着备份一起在设备之间搬家没有意义。
 *
 * 读取是同步的，因为它必须在 `MainActivity.onCreate` 建视图之前完成——
 * 一个只有一项的小文件，代价是一次很短的磁盘读。
 */
internal object TvThemeStore {
    private const val FILE = "native-theme-v1"
    private const val KEY = "theme"

    fun load(context: Context): TvTheme = context
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getString(KEY, null)
        .let(TvTheme::of)

    fun save(context: Context, theme: TvTheme) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, theme.storedValue)
            .apply()
    }
}
