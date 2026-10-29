// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 电视端设计令牌：新拟态（Neumorphism / Soft UI）。
 *
 * 与上一版「深色 + 描边 + 强调色块」的设计系统相比，规则换成四条：
 *
 * 1. **全站只有一块材质**。底色 [background]、凸起面 [surface]、凹陷面 [well] 是同一个色相的
 *    三档明度（差值只有 3–5%），层次由**柔光**表达：右下深投影 + 左上浅高光（见 [SoftDrawable]）。
 *    不再用 1dp 描边去勾卡片轮廓——描边会把「软」的观感打回「扁平 + 线框」。
 * 2. **圆角整体放大一档**：卡片 12–14dp、胶囊 26dp、弹窗 24dp。新拟态的柔和来自
 *    「大圆角 + 柔光」，圆角小一半、投影再软也还是硬边。
 * 3. **每个控件四周留 [SOFT_BAND] 的透明带**，投影 / 高光画在这条带里（[SoftDrawable] 的实现细节）。
 *    因此组件不再靠「父级不裁剪」来显示投影，也不会和滚动容器的焦点余量互相争空间：
 *    焦点余量管的是「放大 1.05 倍溢出的那 2.5%」，柔光带管的是「投影和高光」。
 * 4. **状态只靠「凹 / 凸 / 亮」三个台阶表达，不再画任何带颜色的选框**
 *    （2026-10-07 主题改版，见下）：
 *    - 常态 = 凸起面 [surface]；
 *    - 选中 = **凹进去**（[selectedWell] + [SoftStyle.INSET]，开关 / 选项 / 设置项 / 主题行）
 *      或 **抬一档 + 更深的柔光**（[selectedFace]，顶部当前页图标）；
 *      选集里的当前集是**常亮的亮面板**（[lit]，与主操作同一档，见 [EpisodeCell]）；
 *    - 焦点 = 被打亮的 [lit] 亮面板 + [onLit] 近黑字 + [litGlow] 光晕（[SoftStyle.RAISED]）；
 *    - 主操作 = 常亮的亮面板 [lit]。
 *    强调色 [accent] 因此退到只做「线」与「小色块」：进度条、名次星标。
 *
 * 低内存设备保留全部视觉层级，只把过渡时长压到 0，并降低柔光强度（[Soft.quality]）。
 *
 * ## 主题（2026-10-20 改版）：二十套配色 + 统一的焦点色
 *
 * 设计稿把每套配色拆成八个语义色：底色、亮阴影、暗阴影、主文字、次文字、焦点色、
 * 强调色、状态色。[TvTheme] 原样收下这八个值，其余材质档位继续派生。
 *
 * 全局规则收敛成四条：
 *
 * 1. **焦点 = 打亮 + 一圈焦点色细环**。亮面板 [lit] 负责「被点亮」，焦点色细环
 *    （[focusRing]，2dp）负责在彩色封面上也一眼可见；两者都来自当前主题。
 * 2. **选中 = 按进去 + 细环**。选中态是凹槽 [selectedWell]，主题芯片再加一圈焦点环，
 *    对应设计稿里 `.theme-btn.active` 的 inset + 2px focus。
 * 3. **强调色只做线与小色块**：播放进度、名次星标、开关滑块。它不再负责焦点。
 * 4. **状态色只做破坏性操作与错误**：[danger] 用于删除 / 清空确认和错误提示。
 *
 * 切换主题只改色值，不改任何几何：`TvStyle.applyTheme()` 之后由调用方重建当前页面。
 * 柔光位图里烤着上一套材质的投影色，因此 [applyTheme] 必须丢掉 [Soft] 的缓存
 * （缓存 key 里也带上了主题，见 [themeId]）。
 *
 * 二十套的文字是「浅色主题用深墨、深色主题用暖白」，所以 [text] / [muted] 这类令牌
 * 在两个方向上都能直接用。唯一不跟主题走的是**播放界面**：它压在视频上，
 * 永远用 [playerText] 的暖白（见下面「播放界面」一节）。
 *
 * 二十套排成 7 列 × 3 行并排比过（`python3 tools/theme-preview.py` → `docs/preview/theme.png`）。
 */
object TvStyle {
    // —— 主题 ——

    /**
     * 当前主题。默认 [TvTheme.DEEP_SEA_BLUE]（设计稿的第一套），
     * 由 `MainActivity.onCreate` 按本机保存的偏好覆盖，之后只经 [applyTheme] 改动。
     */
    internal var theme: TvTheme = TvTheme.fallback
        private set

    /**
     * 柔光缓存用的主题标识。
     *
     * [SoftDrawable] 把投影 / 高光**预渲染成位图**，位图里烤着当时的材质色，
     * 因此缓存 key 必须带上主题，否则换主题后会画上一套材质的投影。
     */
    internal val themeId: Int get() = theme.ordinal

    /**
     * 切换主题：只改色值，不动任何几何（圆角、柔光带、按钮档位都不变）。
     *
     * 两个副作用是必须的：丢掉柔光位图缓存（里面烤着旧材质的投影色），
     * 并由调用方重建当前页面（已经建好的 View 上挂着旧的背景 Drawable）。
     */
    internal fun applyTheme(value: TvTheme) {
        if (theme == value) return
        theme = value
        Soft.trim()
    }

    // —— 材质：同一块底色的三档明度（数值全部来自当前主题） ——

    /**
     * 页面底色。新拟态要求「底板与控件同色」，这也是凸起面的基准色。
     *
     * 主题把它连同两档阴影色一起给定，其余档位由 [TvTheme] 派生——
     * 上一版这里是一串写死的石墨色值，换主题就得逐条改，漏一条就会出现
     * 「背景是浅色、卡片还是深色」的混搭。
     */
    val background: Int get() = theme.base

    /** 凸起面：比底色亮一档，投影 / 高光由 [SoftDrawable] 负责。 */
    val surface: Int get() = theme.surface

    /** 抬升面：焦点容器、设置分组、抬一档的选中态（顶部当前页图标）。 */
    val surfaceHigh: Int get() = theme.surfaceHigh

    /** 凹陷面：搜索框、海报井、以及**被按进去的选中态**。 */
    val well: Int get() = theme.well

    /** 更深的凹槽：禁用态、播放器进度轨道。 */
    val wellDeep: Int get() = theme.wellDeep

    /**
     * 焦点面：整个材质里最亮的一档。
     *
     * 焦点**不是**「换成强调色实心块」，也**不再带一圈强调色细环**（2026-10-07 主题改版）：
     * 新拟态里「被打亮」才是焦点的母语——同一块材质被推到最亮的一档，
     * 外面再补一层 [litGlow] 光晕，3 米外一眼就能认出来。
     * 浅色主题里它就是白色，深色主题里是暖白 `#F0EAE0`。
     */
    val lit: Int get() = theme.lit

    /** 亮面板上的字：浅色主题是深墨，深色主题是暖近黑。 */
    val onLit: Int get() = theme.onLit

    /** 亮面板外的一圈柔光，让「打亮」看起来是发光的而不是贴纸。 */
    val litGlow: Int get() = theme.litGlow

    /**
     * 卡片 / 按钮焦点描边：主题焦点色压到 90% 不透明。
     *
     * 新拟态靠明度差表达层次，但海报卡的封面是彩色图片、底板又只有 3–5% 的明度差，
     * 光靠「打亮一档」在一屏十几张卡里并不够醒目。这里补一圈**同主题的焦点色**细边：
     * 颜色来自当前主题，宽度只有 [RING]，又足够让用户一眼认出焦点在哪一张。
     */
    val focusRing: Int get() = theme.focusRing

    /**
     * 抬一档的选中面：给「本来就在凹陷背板里」的控件用（顶部当前页图标）。
     * 选集里的当前集比它更高一档，直接用常亮的 [lit]（见 [EpisodeCell]）。
     *
     * 它们周围已经是凹的，再凹一层就分不清了，所以改用「抬一档 + 更深的柔光」。
     * 其余选中态一律用 [selectedWell]。
     */
    val selectedFace: Int get() = theme.surfaceHigh

    /**
     * 按进去的选中面：开关、选项、设置项、主题行都用它。
     *
     * 在一堆凸起的按钮里，被选中的那一个**凹下去**；
     * 主题芯片再补一圈 [focusRing] 细环（对应设计稿的 active 态）。
     */
    val selectedWell: Int get() = theme.well

    // —— 柔光 ——

    /** 右下深投影（`setShadowLayer` 的颜色，alpha 就是强度）。 */
    val shadowDark: Int get() = theme.shadowDark

    /** 左上浅高光。 */
    val shadowLight: Int get() = theme.shadowLight

    /** 凹陷内阴影：左上偏深、右下偏亮。 */
    val innerDark: Int get() = theme.innerDark
    val innerLight: Int get() = theme.innerLight

    /** 描边：只留给「必须画一条线」的地方（分隔线、二维码白框），不再是卡片轮廓。 */
    val outline: Int get() = theme.outline

    /** 分组分隔线。 */
    val separator: Int get() = theme.separator

    /**
     * 凹陷控件的聚焦线：只在**搜索框 / 输入框**上补一条边界。
     *
     * 凹陷的焦点没法靠「打亮」表达，只能把槽压得更深、再让这条焦点色细线加深。
     */
    val focusLine: Int get() = theme.focusLine

    // —— 强调 ——

    /**
     * 强调色：**只做线与小色块**——进度条、名次星标。
     *
     * 焦点环改用独立的焦点色之后，强调色不再出现在任何焦点 / 选中态上。
     * 二十套各有一支，颜色直接来自设计稿。
     */
    val accent: Int get() = theme.accent

    /** 提示色，只用于评分星标与榜单前三：跟随当前主题的强调色。 */
    val tips: Int get() = theme.tips

    /** 焦点色：焦点环、输入框聚焦线。 */
    val focus: Int get() = theme.focus

    /** 状态色：删除 / 清空 / 错误提示。 */
    val danger: Int get() = theme.danger

    /** 次文字：说明、辅助信息，直接取主题的 `--sub`。 */
    val sub: Int get() = theme.sub

    // —— 文字四级（浅色主题是深墨、深色主题是暖白） ——

    /** 正文。 */
    val text: Int get() = theme.text

    /** 88%：区块标题。 */
    val textStrong: Int get() = theme.textStrong

    /** 主文字与次文字之间：卡片标题、正文。 */
    val textDim: Int get() = theme.textDim

    /** 次文字：辅助说明、次要按钮文字。 */
    val muted: Int get() = theme.muted

    // —— 播放界面：不跟主题走 ——
    // 播放时这些字与线一直压在视频画面上，浅色主题下如果跟着变成深色字就会完全看不见。
    // 因此播放界面固定用暖白 + 黑压暗层，与主题无关（见 docs/UI_NEUMORPHISM.md 的适配清单）。

    /** 播放界面的文字：永远暖白。 */
    val playerText = Color.rgb(0xF4, 0xF3, 0xF1)

    /** 播放界面的次级文字：65% 暖白。 */
    val playerDim = 0xA6F4F3F1.toInt()

    /** 播放进度条轨道：18% 暖白。 */
    val playerLine = 0x2EF4F3F1.toInt()

    /** 75% 黑：时间角标底。 */
    val overlay = 0xBF000000.toInt()

    /** 60% 黑：播放器控制条。 */
    val track = 0x99000000.toInt()

    // —— 比例 ——

    /** 海报宽高比（宽 / 高），对应宫格的 357:477（≈3:4）。 */
    const val POSTER_ASPECT = 3f / 4f

    // —— 圆角：按设计稿再放大一档 ——
    // 新拟态的柔和来自「大圆角 + 柔光」；胶囊一律取 999，由绘制层夹到高度的一半。

    const val RADIUS_POSTER = 16
    const val RADIUS_CARD = 20
    const val RADIUS_PILL = 999

    /** 模态框面板的圆角：比卡片大、比胶囊小，一眼能看出是浮层。 */
    const val RADIUS_DIALOG = 32

    /**
     * 弹窗面板的圆角：比卡片大一档（卡片 20），但**不再走设计稿的 56**。
     *
     * 56 在 1280 画布上已经接近四分之一面板高度，电视上退开两米看就是一块「椭圆板」，
     * 与面板里那些胶囊选项叠在一起显得过软。收到 30 之后：
     * 面板仍是明显的大圆角浮层，边角又收得住，选项的胶囊形状才成为画面里唯一的圆形。
     */
    const val RADIUS_DIALOG_PANEL = 30

    // —— 弹窗几何（设计稿 1280 × 720 画布上的数值） ——
    // 弹窗是一块独立的小面板：宽 472、标题 20、副标题 12.5、选项高 62 / 图标 22 / 字号 19。

    /** 弹窗面板宽度（设计 dp）。窄屏会按「屏宽 − 两侧留白」夹一次。 */
    const val DIALOG_WIDTH = 472

    /** 弹窗标题字号。 */
    const val DIALOG_TITLE_TEXT = 20f

    /** 弹窗副标题（集数 / 时长这类一行说明）字号。 */
    const val DIALOG_SUB_TEXT = 12.5f

    /** 顶部封面缩略图：宽 / 高 / 圆角。 */
    const val DIALOG_THUMB_WIDTH = 96
    const val DIALOG_THUMB_HEIGHT = 60
    const val DIALOG_THUMB_RADIUS = 30

    /** 选项行的高度（底板，不含柔光带）。 */
    const val DIALOG_ITEM_HEIGHT = 62

    /** 选项行左右内边距（图标 / 文字到面板边缘的距离）。 */
    const val DIALOG_ITEM_PAD = 30

    /** 选项行图标边长与「图标 → 文字」的间距。 */
    const val DIALOG_ITEM_ICON = 22
    const val DIALOG_ITEM_ICON_GAP = 15

    /** 选项行字号。 */
    const val DIALOG_ITEM_TEXT = 19f

    /** 选项之间的竖向间距。 */
    const val DIALOG_ITEM_GAP = 11

    // —— 登录界面（设计稿 1280 × 720 画布上的数值） ——
    // 一张居中的登录卡：宽 680、圆角 32、内边距 40 / 48 / 40；
    // 卡片里是「小号 logo + 大标题 + 一行说明」，下面两行胶囊输入，最后一条整宽主按钮。

    /** 登录卡宽度（设计 dp）。窄屏按「屏宽 − 两侧留白」夹一次。 */
    const val LOGIN_CARD_WIDTH = 680

    /** 登录卡圆角：比弹窗小一档，比卡片大一档。 */
    const val RADIUS_LOGIN_CARD = 32

    /** 登录卡内边距（上 / 左右 / 下）。整体收紧一档：卡片矮一点，一屏里不显得空。 */
    const val LOGIN_PAD_TOP = 28
    const val LOGIN_PAD_SIDE = 44
    const val LOGIN_PAD_BOTTOM = 26

    /** 小号 logo 文字（字母拉开的那种标识行）。 */
    const val LOGIN_LOGO_TEXT = 12f

    /** 大标题「登录」。 */
    const val LOGIN_TITLE_TEXT = 30f

    /** 标题下面那行说明。 */
    const val LOGIN_SUB_TEXT = 14f

    /** 字段上方的小标签（「手机号」「验证码」）。 */
    const val LOGIN_LABEL_TEXT = 13f

    /** 输入框 / 地区码 / 获取验证码这三件的高度（底板）。 */
    const val LOGIN_FIELD_HEIGHT = 56

    /** 输入框里的字号。 */
    const val LOGIN_INPUT_TEXT = 20f

    /** 输入框左右内边距（到面板边缘的距离）。 */
    const val LOGIN_INPUT_PAD = 30

    /** 地区码按钮的宽度。 */
    const val LOGIN_REGION_WIDTH = 116

    /** 「获取验证码」按钮的宽度。 */
    const val LOGIN_SEND_WIDTH = 158

    /** 登录主按钮的高度。 */
    const val LOGIN_BUTTON_HEIGHT = 60

    /** 登录主按钮的字号。 */
    const val LOGIN_BUTTON_TEXT = 18f

    /** 登录卡里各字段之间的竖向间距。 */
    const val LOGIN_FIELD_GAP = 16

    /** 弹窗顶部 / 左右 / 底部的内边距。 */
    const val DIALOG_PAD_TOP = 26
    const val DIALOG_PAD_SIDE = 22
    const val DIALOG_PAD_BOTTOM = 24
    const val RING = 2

    /** 焦点描边圆角。 */
    const val RING_RADIUS = 20

    // —— 柔光带宽 ——
    // 控件四周为投影 / 高光预留的透明带，也是控件自身的内边距。
    // 5dp 的带里能放下「偏移 1.7dp + 模糊 4.8dp」的投影，观感是柔和的，同时不会把
    // 一屏 6 张的海报架撑得过宽；弹窗、设置分组、播放器底座这类大面板用 9dp。

    const val SOFT_BAND = 6

    /** 大面板的柔光带宽。 */
    const val SOFT_BAND_LARGE = 10

    const val FOCUS_SCALE = 1.05f
    const val DURATION = 200L

    /**
     * 焦点余量：放大 [FOCUS_SCALE] 后，控件会画出自身槽位 (FOCUS_SCALE-1)/2 ≈ 2.5%，
     * 175dp 的海报卡就是 4.4dp。**滚动容器必须左右各留这么多内边距**，
     * 否则最左 / 最右那一张的焦点描边会被滚动容器自己裁掉——这正是「第一张卡片左边框看不见」的原因。
     */
    const val FOCUS_BLEED = 7

    /**
     * 纵向焦点余量：比横向大一档，因为卡片是竖的。
     *
     * 放大 [FOCUS_SCALE] 会让控件向上 / 向下各画出自身高度的 (FOCUS_SCALE-1)/2 = 2.5%，
     * 再加上 [RING] 描边有一半画在边界外，一张 288dp 高的海报卡就要 0.025×288 + 1 ≈ 8.2dp；
     * 4K 画布（1920dp 宽）上卡片约 410dp 高，需要约 11.3dp。取 12dp 覆盖到约 440dp 的卡片。
     */
    const val FOCUS_BLEED_VERTICAL = 12

    // —— 按钮 ——
    // 全站按钮只有三档高度，横向内边距与字号跟着档位走；圆角一律胶囊（RADIUS_PILL）。
    // 这里的高度是**底板高度**，控件实际高度还要加上四周的柔光带（见 paintButton）。

    /** 紧凑档：菜单选项、行内文字按钮（更多 ›）。 */
    const val BUTTON_COMPACT = 40

    /**
     * 弹窗档：**只给弹窗里的按钮用**（底部动作行与选项列表）。
     *
     * 弹窗是一块浮在页面上的面板，里面的按钮如果与页面上同样大，整框看起来就又宽又重：
     * 一行「取消 / 确认」各占一百多 dp，视觉上比弹窗标题还抢。这里统一收一档
     * （36dp 底板 / 14dp 内边距 / 14sp），弹窗内的按钮因此比页面上的常规按钮小一圈。
     */
    const val BUTTON_DIALOG = 36

    /** 默认档：返回、分页、重试、收藏、刷新这类常规操作。 */
    const val BUTTON_NORMAL = 48

    /** 主操作档：详情页「开始观看」这类页面里唯一的强调按钮。 */
    const val BUTTON_HERO = 56

    const val BUTTON_PAD_COMPACT = 18
    const val BUTTON_PAD_NORMAL = 28
    const val BUTTON_PAD_HERO = 36
    const val BUTTON_PAD_DIALOG = 14

    /**
     * 收紧档：**只有搜索页导航栏里那两枚按钮用**（搜索 / 手机输入）。
     *
     * 那一行还要摆七个圆形入口（含「推荐」），本身就紧；紧凑档的 18dp 内边距
     * 让「手机输入」占到 132dp，两枚加起来就顶掉了输入框一半的宽度。
     * 收到 12dp 之后高度与字号都不变，只是横向收窄，与同一行的输入框仍是同一档观感。
     */
    const val BUTTON_PAD_TIGHT = 12

    /** 收紧档的最小宽度：两三个字按内容宽收紧，不撑到常规档的 92dp。 */
    const val BUTTON_MIN_WIDTH_TIGHT = 44

    /**
     * 分区芯片的字号：比常规档（16sp）小一档，但高度仍是 [BUTTON_NORMAL]。
     *
     * 设置页那四枚大项标签（四个汉字）用它——字小一点，按内容宽收起来的芯片才不会
     * 把顶部那一行撑满；高度与榜单页的芯片一致，因此两页的芯片看起来是同一档控件。
     */
    const val BUTTON_TEXT_CHIP = 14f

    const val BUTTON_TEXT_COMPACT = 15f
    const val BUTTON_TEXT_NORMAL = 16f
    const val BUTTON_TEXT_HERO = 17f
    const val BUTTON_TEXT_DIALOG = 14f

    /**
     * 海报卡角标的字号：**右下角状态角标与右上角季号共用这一档**。
     *
     * 两枚角标贴在同一个井口的两角，字号不一致会读成两套体系；2026-10-14 起从 18sp 收到 12sp
     * （与卡片状态行同档），不再盖过剧名与封面本身。
     */
    const val POSTER_BADGE_TEXT = 12f

    /** 按钮最小宽度：短文案（「收藏」「重试」）也不会瘦成一条。 */
    const val BUTTON_MIN_WIDTH = 92

    /**
     * 顶部导航栏的高度。
     *
     * 这一条**不画底板**（没有边框、也没有可以按进去的面板），里面最高的一件是圆形图标入口
     * （[BUTTON_NORMAL] + 2 × [SOFT_BAND] = 60dp），上下各留 14dp 就是 88dp；
     * 文字与图标都按内容线（`pageInset()` 78dp）对齐。页头收展动画直接改这一行的高度，
     * 因此它必须是固定值。
     */
    const val TOOLBAR_HEIGHT = 88

    /** 同一行按钮之间、行内控件之间的间距。 */
    const val GAP_INLINE = 12

    /** 网格单元格、密集排布的间距。 */
    const val GAP_TIGHT = 8

    /** 区块之间、按钮行与正文之间的间距。 */
    const val GAP_BLOCK = 16

    /** 大分组之间的间距。 */
    const val GAP_GROUP = 24

    /** 禁用态整体透明度，三档按钮一致。 */
    const val DISABLED_ALPHA = .4f

    /** 低内存设备在 onCreate 中置 false，焦点切换立即生效、不排队动画。 */
    var animate = true

    // —— 形状工厂 ——

    /**
     * 新拟态底板：凸起 / 凹陷 / 只描边三种形态，投影与内阴影由 [SoftDrawable] 绘制。
     * 所有控件的背景都应该走这里，不要再自己拼 [shape]。
     */
    internal fun soft(
        context: Context,
        fill: Int = surface,
        radius: Int = RADIUS_CARD,
        band: Int = SOFT_BAND,
        style: SoftStyle = SoftStyle.RAISED,
        ring: Int = Color.TRANSPARENT,
        ringWidth: Int = 0,
        glow: Int = Color.TRANSPARENT,
        depth: Float = 1f,
        /**
         * 「大面积、一屏十几份」的底板：只有海报卡 / 剧架底板传 true。
         *
         * 它们会在低配上关掉预渲染柔光位图（一屏 18 张 × 整卡面积 = 2.0 M 像素/帧，
         * 见 [Soft.shadowBitmaps]）。按钮与弹窗面板一屏只有三五个、又没有彩色封面兜底，
         * 保持默认的 false，柔光原样保留。
         */
        heavy: Boolean = false
    ) = SoftDrawable(
        fill = fill,
        // 几何一律走 designPx：圆角 / 柔光带必须和控件自己的 tvDp 内边距同一个比例，
        // 否则小屏上会出现「底板缩了、描边和投影还按 1280dp 画」的错位。
        radiusPx = context.designPx(radius).toFloat(),
        bandPx = context.designPx(band).toFloat(),
        style = style,
        ringColor = ring,
        ringWidthPx = context.designPx(ringWidth).toFloat(),
        glowColor = glow,
        depth = depth,
        heavy = heavy
    )

    /** 凹陷槽：搜索框、海报井、进度轨道。 */
    internal fun well(
        context: Context,
        fill: Int = well,
        radius: Int = RADIUS_CARD,
        band: Int = SOFT_BAND,
        ring: Int = Color.TRANSPARENT,
        ringWidth: Int = 0,
        depth: Float = 1f
    ) = soft(context, fill, radius, band, SoftStyle.INSET, ring, ringWidth, Color.TRANSPARENT, depth)

    /** 只有一圈描边的前景（焦点环），不画投影、不画底。 */
    internal fun ring(
        context: Context,
        color: Int = accent,
        radius: Int = RING_RADIUS,
        band: Int = SOFT_BAND,
        width: Int = RING
    ) = soft(context, Color.TRANSPARENT, radius, band, SoftStyle.RING, color, width)

    /** 纯色圆角底，保留给进度条分段、角标压暗层这类不需要柔光的小色块。 */
    fun shape(
        context: Context,
        color: Int,
        border: Int = Color.TRANSPARENT,
        radius: Int = RADIUS_POSTER,
        stroke: Int = 1
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.designPx(radius).toFloat()
        if (stroke > 0) setStroke(context.designPx(stroke), border)
    }

    /**
     * 焦点反馈：放大到 1.05。位移会打乱行内对齐，因此只做缩放。
     * 焦点态的形状变化由控件自己通过 [soft] 完成（打亮的亮面 + 焦点色细环 + 外发光）。
     */
    fun focus(view: View, focused: Boolean, scale: Float = FOCUS_SCALE, duration: Long = DURATION) {
        // 低配（[animate] == false）：**完全不做缩放**，连 1.05 的静态缩放都不留。
        //
        // 缩放是 RenderNode 的几何属性：它一变，这张卡里的封面大图、柔光位图、
        // 径向暗角全部要按新的采样矩阵重新光栅化。而焦点是否醒目已经由
        // `applyFocus` 的「亮面 + 焦点环 + 外发光」表达完了——那三样只改配色，不动几何。
        // 2GB 盒子上「缩放」的观感收益远小于它造成的丢帧。
        val target = if (animate && focused) scale else 1f
        if (!animate) {
            // 已经归位就不必再走一遍动画管线（低配上这条路径每次按键都要经过两次：
            // 旧卡退出、新卡进入）。
            if (view.scaleX == target && view.scaleY == target) return
            view.animate().cancel()
            view.scaleX = target
            view.scaleY = target
            return
        }
        view.animate().cancel()
        view.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 竖向渐变，用于海报文字浮层、推荐位底部与播放器控制层。 */
    fun gradient(
        top: Int,
        bottom: Int,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TOP_BOTTOM
    ) = GradientDrawable(orientation, intArrayOf(top, bottom))

    /**
     * 播放器底部控制面板的三段渐变：压在画面上自然暗下去，而不是盖一块不透明的浮层。
     */
    fun panel(
        top: Int = Color.TRANSPARENT,
        middle: Int = 0xA6000000.toInt(),
        bottom: Int = 0xF0000000.toInt()
    ) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(top, middle, bottom)
    )

    /**
     * 浮层背板的不透明度（0xF0 = 94%）。
     *
     * 页面内的浮层（简介页底部条）压在静态底图上，可以做得实一点；
     * 压在**正在播放的画面**上的那两块交给 [SHEET_ALPHA_VIDEO]。
     */
    const val SHEET_ALPHA = 0xF0

    /**
     * 播放界面上两块浮层（播放设置 / 选集）的背板不透明度（0xB8 ≈ 72%）。
     *
     * 94% 在视频上读起来是一块实心板子：面板边界之内与之外的画面亮度断成两截，
     * 用户看不到「这是一层压在画面上的浮层」；82% 在那个大屏上还是偏实，
     * 因此再透一档到 72%：画面里的亮部与运动仍然透得上来，
     * 而面板里的文字 / 按钮（凹槽、亮面都画在背板之上，不受这一层影响）依旧清楚。
     *
     * **播放界面、推荐界面、选集面板三处共用这一个值**（都走 [sheet] 的 `alpha`）：
     * 三块浮层压在同一个播放画面上，任何一个单独飘开都会读成「两块不同的板子」。
     */
    const val SHEET_ALPHA_VIDEO = 0xB8

    /**
     * 播放界面上两块浮层的高度（设计 dp，720 画布的三分之一）。
     *
     * 播放设置与选集**同高**：它们贴在同一条屏幕底边上、铺的是同一块材质，
     * 一高一矮时上沿会差出一截，看起来像两块不同层级的浮层。
     * 两处都从这里取值，因此改一处两处一起变。
     */
    const val PLAYER_PANEL_HEIGHT = 300

    /**
     * 浮层面板的主题背板：**播放设置、选集、相关剧集三块浮层共用这一份**。
     *
     * 这三块原来铺的是「透明 → 60% 黑 → 88% 黑」的黑渐变。黑色不属于任何一套主题：
     * 浅色主题下它成了整屏唯一一块冷黑，而浮层里的按钮用的是凸起面 / 凹槽 / 亮面三档主题材质，
     * 压在纯黑上时「凹槽」（`well`，本来就是一块近黑的深色）与背景几乎同色，
     * 选中态因此读不出来——这正是「弹窗按钮的选中态与主题风格不符」在播放界面里的样子。
     *
     * 改成主题材质之后，浮层与页面是同一块底板：按钮的凸 / 凹 / 亮三档在浮层里也成立。
     * 上缘两角的圆度由 [radius] 决定（下缘永远贴到底边，不圆）：页面内的浮层按弹窗圆角走，
     * 播放界面那两块传 `0`（**直角**——它们压在视频上、整宽贴底，圆角会在上沿切出两个缺口）；
     * 不透明度由 [alpha] 决定：默认 [SHEET_ALPHA]，压在视频上的两块传 [SHEET_ALPHA_VIDEO]。
     */
    fun sheet(
        context: Context,
        fill: Int? = null,
        alpha: Int = SHEET_ALPHA,
        /** 上缘两角的圆角（设计 dp）；`0` = 直角。 */
        radius: Int = RADIUS_DIALOG
    ) = GradientDrawable().apply {
        // 默认 94% 不透明：页面内的浮层（简介页底部条）要压住自己的底图。
        // 压在**正在播放的画面**上那两块（播放设置 / 选集）用 [SHEET_ALPHA_VIDEO]，
        // 更透一档，视频的亮部能透上来，面板不会读成一整块实心板子。
        setColor(fill ?: ((surfaceHigh and 0x00FFFFFF) or (alpha shl 24)))
        // 直角时干脆不设 cornerRadii：一组 0 与「没有圆角」在绘制上等价，
        // 但不设值可以让 Drawable 走矩形快路径，省掉一次圆角几何的构造。
        if (radius > 0) {
            val r = context.designPx(radius).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }

    /**
     * 只圆**顶部**两角的竖向渐变：右上角那枚季号角标的压暗层。
     *
     * 参数与 [scrim] 同序（先上后下），只是圆角换到上边：角标贴在井口上角，
     * 该圆的是它的上缘，下缘要与封面无缝衔接。
     */
    fun scrimTop(context: Context, top: Int, bottom: Int, radius: Int = RADIUS_POSTER) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(top, bottom)
    ).apply {
        val r = context.designPx(radius).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    /**
     * 左上角名次角标的压暗层：横向渐变，最深处贴左上。
     *
     * 与右上角那枚（竖向渐变）区分方向：两枚都在封面上缘，方向不同才不会看成一条通栏暗带。
     */
    fun scrimTopStart(context: Context, start: Int, end: Int, radius: Int = RADIUS_POSTER) = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(start, end)
    ).apply {
        val r = context.designPx(radius).toFloat()
        cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
    }

    /** 只圆底部的竖向渐变：角标背后的压暗层，上缘要与封面无缝衔接。 */
    fun scrim(context: Context, top: Int, bottom: Int, radius: Int = RADIUS_CARD) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(top, bottom)
    ).apply {
        val r = context.designPx(radius).toFloat()
        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
    }

}
