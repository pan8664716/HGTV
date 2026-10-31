// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

/**
 * 柔光位图要用到的四个颜色 + 主题标识。
 *
 * `Soft`（柔光绘制核心）在 `shared` 里，而主题令牌在各自的端上（TV 是 `TvStyle`，
 * 手机是 `PhoneStyle`）。这里只放**绘制柔光真正需要的五个值**，
 * 由各端在切换主题时写进来——公共层不认识任何一套主题定义。
 *
 * 默认值是中性石墨（深色），手机端第一版直接用这一套。
 */
object SoftPalette {
    /** 主题标识：只参与缓存 key，换主题必须换它，否则会拿旧材质的位图重画。 */
    @Volatile var themeId: Int = 0

    /** 右下深投影。 */
    @Volatile var shadowDark: Int = 0x66000000

    /** 左上浅高光。 */
    @Volatile var shadowLight: Int = 0x26FFFFFF

    /** 凹陷槽的左上深内阴影。 */
    @Volatile var innerDark: Int = 0x59000000

    /** 凹陷槽的右下亮内阴影。 */
    @Volatile var innerLight: Int = 0x1AFFFFFF
}
