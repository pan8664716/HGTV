#!/usr/bin/env python3
"""按钮系统静态校验：全站按钮只能有一套尺寸、一套外观、一套焦点反馈。

这不是真机验收的替代品（焦点移动、遥控器手感、滚动仍要在电视上确认），
而是防止后续改动把按钮重新写成「各页面各写一套」：
任何绕过 TvButton 的按钮、任何自己拼的内边距或高度，都会让这个脚本失败。

用法：
    python3 tools/verify-button-system.py [源码目录]
默认源码目录为 android/app/src/main/kotlin/com/hgtv/star
"""
import re, sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "android/app/src/main/kotlin/com/hgtv/star")
checks = []
def check(label, condition):
    if not condition: raise AssertionError(label)
    checks.append(label)

files = {p.name: p.read_text(encoding="utf-8") for p in sorted(root.glob("*.kt"))}
check("kotlin sources found", len(files) >= 10)
def lines(name): return [(i + 1, l) for i, l in enumerate(files[name].split("\n"))]

# 1. 组件齐备：三档尺寸、三种语义、唯一绘制函数、唯一模态框。
components = files["TvComponents.kt"]
for token in ["enum class ButtonSize", "enum class ButtonTone", "fun paintButton(",
              "internal open class TvButton(", "class IconButton(", "class ToggleChip(", "class EpisodeCell("]:
    check("TvComponents declares " + token, token in components)
check("TvDialog is the only modal implementation", "internal class TvDialog(" in files["TvDialog.kt"])

# 2. 唯一实现：除 TvComponents.kt 外没有第二个继承 TextView 的控件（也就是没有第二个手写按钮）。
for name, text in files.items():
    if name == "TvComponents.kt": continue
    check(name + ": no second TextView-based control", ": TextView(" not in text)
for name, text in files.items():
    if name == "TvComponents.kt": continue
    check(name + ": does not paint buttons itself", "paintButton(" not in text)

# 3. 尺寸令牌齐备，且按钮高度只引用令牌。
style = files["TvStyle.kt"]
for token in ["BUTTON_COMPACT = 40", "BUTTON_NORMAL = 48", "BUTTON_HERO = 56",
              "BUTTON_PAD_COMPACT", "BUTTON_PAD_NORMAL", "BUTTON_PAD_HERO",
              "BUTTON_TEXT_COMPACT", "BUTTON_TEXT_NORMAL", "BUTTON_TEXT_HERO",
              "GAP_INLINE", "GAP_TIGHT", "GAP_BLOCK", "GAP_GROUP"]:
    check("TvStyle defines " + token, token in style)
# 允许的最小高度：按钮档位令牌，加上两个非按钮用法——顶栏 TOOLBAR_HEIGHT、详情页内容撑满屏幕。
height_allow = ("TvStyle.BUTTON", "size.height", "screenHeight", "TOOLBAR_HEIGHT")
for name in files:
    for number, line in lines(name):
        if re.search(r"\b(minHeight|minimumHeight)\s*=", line):
            value = line.split("=", 1)[1]
            check("%s:%d button height comes from a token" % (name, number),
                  any(token in value for token in height_allow))

# 4. 内边距不再各写各的：旧的按钮内边距字面量必须清零。
legacy = ["setPadding(dp(32)", "setPadding(dp(22)", "setPadding(dp(20)",
          "setPadding(dp(18)", "setPadding(dp(14)"]
for name in files:
    for number, line in lines(name):
        for bad in legacy:
            check("%s:%d no hand-rolled button padding" % (name, number), bad not in line)

# 5. 焦点反馈唯一：缩放固定 TvStyle.FOCUS_SCALE，调用方不再传第三参数。
for name in files:
    for number, line in lines(name):
        call = re.search(r"TvStyle\.focus\([^)]*\)", line)
        if call:
            check("%s:%d focus scale is not overridden" % (name, number),
                  call.group(0).count(",") <= 1)

# 6. 弹窗统一：系统 AlertDialog 只允许出现在 TvDialog.kt 里。
for name, text in files.items():
    if name == "TvDialog.kt": continue
    check(name + ": no raw AlertDialog", "AlertDialog.Builder" not in text)

# 7. 焦点余量：滚动容器要么自己留 FOCUS_BLEED，要么显式标注为「边界就在屏幕边」的豁免。
#    焦点放大会让控件画出槽位约 4.4dp，滚动容器的裁剪边界就是它自己的边界，
#    所以余量必须在容器内部——外面加 padding 只会把边界一起挪走。
check("TvStyle defines FOCUS_BLEED", "FOCUS_BLEED = 7" in style)
check("TvComponents provides focusBleed", "fun ScrollView.focusBleed()" in components)
for name in files:
    # HorizontalScrollView 的横向裁剪由外层滚动容器负责（它自己 clipChildren=false），只算纵向滚动容器。
    wanted = [n for n, l in lines(name)
              if re.search(r"(?<!Horizontal)ScrollView\(", l) and "focus-bleed-exempt" not in l]
    kept = [n for n, l in lines(name) if "focusBleed()" in l]
    check("%s: every scroll container keeps the focus bleed" % name, len(wanted) <= len(kept))

# 8. 胶囊底色只能来自令牌：带 RADIUS_PILL 的形状不允许出现裸色值。
for name in files:
    for number, line in lines(name):
        if "RADIUS_PILL" in line and "shape(" in line:
            check("%s:%d pill colours come from tokens" % (name, number), "0x" not in line)

# 9. 裁剪边界必须落在滚动容器的**直接父级**（外壳）上，而不是滚动容器自己身上。
#    View.setDisplayListProperties() 里写的是
#        renderNode.setClipToBounds(mParent instanceof ViewGroup && ((ViewGroup) mParent).getClipChildren())
#    也就是说控件自己的 clipChildren 只决定「子控件会不会被裁到子控件自己的边界上」：
#    滚动容器打开它 = 把列表裁在列表自己的边界上（列表和内容一样高，等于没裁），
#    反而把第一行 / 最左边那张卡放大后画到列表边界之外的描边切掉
#    （观感就是「最左边的选中框只剩一半、上边框看不见」）；
#    而滚动内容能不能画到容器之外，只看滚动容器的父级裁不裁
#    （不裁的话，上滚的卡片直接盖在页头那一排按钮上，页头是透明底，
#     观感就是「按钮跑到列表后面，很乱」）。
#    因此两件事必须同时成立：容器自己 clipChildren=false + 外壳 clipChildren=true。
bleed = components.split("internal fun ScrollView.focusBleed", 1)[1].split("\n}", 1)[0]
check("focusBleed leaves the clipping to the host", "clipChildren = false" in bleed)
check("TvStyle defines the vertical focus bleed", "FOCUS_BLEED_VERTICAL = 12" in style)
check("TvComponents provides the scroll host", "fun Context.tvScrollHost(" in components)
host = components.split("internal fun Context.tvScrollHost(", 1)[1].split("\n}", 1)[0]
check("scroll host clips its child", "clipChildren = true" in host)
for name in files:
    src = files[name].split("\n")
    for index, line in enumerate(src):
        if re.search(r"(?<!Horizontal)ScrollView\(", line) and "focus-bleed-exempt" not in line:
            # 窗口放宽到 20 行：滚动容器通常先 apply {} 配好属性，再挂到外壳上。
            window = "\n".join(src[index:index + 20])
            check("%s:%d scroll container hangs on a clipping host" % (name, index + 1),
                  "tvScrollHost(" in window)

# 10. 重建型渲染函数里复用「长期持有的控件」必须经过 rehost()。
#     ViewGroup.addView() 遇到已经有父级的子控件会抛 IllegalStateException；
#     而列表整体重建时 removeAllViews() 只摘掉**直接**子控件，
#     行里面的控件仍然挂在旧行上 —— 第二次重建必崩，触发时机是收藏更新检查这类异步事件，
#     表现出来就是「用着用着偶发崩溃」。0.11.0 的首页 refresh 按钮就是这个坑。
#
#     这条规则原来写死成「HomeScreen 里必须有 rehost(refresh」：refresh 按钮后来整体
#     搬到了顶部圆形入口（见 MainActivity 顶部导航栏注释），那个字符串再也不存在，
#     断言就成了一条永远为假的死规则——既不保护代码，也让人以为护栏还在。
#     现在改成**通用检查**：缩进 4 空格声明的属性（= 跨函数存活的长期持有控件）
#     出现在一个带 removeAllViews() 的重建函数里、被裸 addView 挂载、且这个函数
#     没有重新给它赋值，就报错——这正是「第二次重建抛 IllegalStateException」的形状。
FIELD = re.compile(r"^ {4}(?:private |internal |protected )?(?:lateinit )?(?:val|var) (\w+)\s*[:=]", re.M)
FUNCTION = re.compile(r"^ {4}(?:private |internal |public |protected )?(?:override )?(?:suspend )?fun \w+|^ {4}init \{")


def function_bodies(text):
    """按顶层成员切成 (起始行号, 函数体)：缩进 4 空格的 fun / init 各算一段。"""
    source = text.split("\n")
    starts = [i for i, line in enumerate(source) if FUNCTION.match(line)]
    for index, begin in enumerate(starts):
        stop = starts[index + 1] if index + 1 < len(starts) else len(source)
        yield begin + 1, "\n".join(source[begin:stop])


for name, text in files.items():
    held = set(FIELD.findall(text))
    if not held:
        continue
    for start, body in function_bodies(text):
        if "removeAllViews()" not in body:
            continue
        for field in sorted(held):
            if not re.search(r"addView\(\s*%s\b" % re.escape(field), body):
                continue
            # 同一个函数里重新赋值 = 每次重建都是新控件，不共享父级，安全。
            if re.search(r"^\s*%s\s*=" % re.escape(field), body, re.M):
                continue
            check("%s:%d long-lived %s is re-hosted, not re-added" % (name, start, field), False)

# 11. 页头收展的动画表：只能先快照 / 先摘除，再取消。
#     ValueAnimator.cancel() 会**同步**回调 onAnimationEnd，而那条回调会从这张表里摘掉自己；
#     边遍历这张表边取消就是 ConcurrentModificationException。
#     页头有两行以上时（搜索行 / 榜单标题行 / 集合页标题行）必现，
#     而收展动画那 200ms 里「展示更多」「刷新榜单」、切分类、进详情都会重建页面
#     （releaseCatalogViews），表现出来就是「下拉加载更多时偶发崩溃」。
for number, line in lines("MainActivity.kt"):
    if "topAreaAnimators" in line and "forEach" in line:
        check("MainActivity.kt:%d page header animators are snapshotted before cancelling" % number,
              "toList()" in line)
check("MainActivity.kt detaches the finished header animator atomically",
      "topAreaAnimators.remove(row)===this@apply" in files["MainActivity.kt"])

# 12. 页头状态不能被「重建页面时自己产生的滚动」改写。
#     列表重建会连带产生三次滚动：位置恢复（内容还没测量，ScrollView 的 clamp 会把它夹成 0）、
#     焦点回带（把新卡片带回可见区域）、内容高度变化后的夹取。这些都不是用户滚动，
#     如果被 onListScroll() 当成「滚回顶部了」，页头就会在每次「展示更多」之后重新展开。
#     因此两件事必须同时成立：重建期把 topAreaSettleUntil 往后推，位置恢复挂在布局回调上（量完再算）。
check("MainActivity.kt silences the scrolling its own page rebuild produces",
      "topAreaSettleUntil=android.os.SystemClock.elapsedRealtime()+TvStyle.DURATION+120" in files["MainActivity.kt"])
check("MainActivity.kt restores the list position only after the list is measured",
      "list.addOnLayoutChangeListener" in files["MainActivity.kt"])

# 13. 选集网格只有一种翻页方式：四向「到边缘再翻页」，两个宿主共用同一份逻辑。
#     简介页原来带一个 verticalPaging=false 的开关，把上下键让给焦点系统，
#     于是 20 集以上的剧在简介页只能左右翻；现在两处统一，开关本身删掉，
#     防止后续有人再给某一处加回「只翻左右」。
check("EpisodeGrid has a single paging behaviour", "verticalPaging" not in files["EpisodeGrid.kt"])
check("MainActivity does not configure EpisodeGrid paging",
      "verticalPaging" not in files["MainActivity.kt"])
check("EpisodeGrid advertises four-way paging", "左 / 右 / 上 / 下 到边缘翻页" in files["EpisodeGrid.kt"])
#     越界必须返回 false 把按键交回焦点系统：简介页第一页第一行按上就靠这条回到
#     「继续观看 / 收藏 / 完整简介」那一行（详情页不留页头，这是唯一的向上出口）。
check("EpisodeGrid hands the key back when there is no target page",
      "if (target < 0 || target * 20 >= count) return false" in files["EpisodeGrid.kt"])

# 14. 主题：二十套配色 + 焦点色细环（2026-10-20）。
#     每套主题由八个语义色定义；焦点与选中都使用主题的 --focus 细环，
#     强调色只做进度条 / 名次星标，状态色只做删除 / 清空 / 错误。
theme = files["TvTheme.kt"]
THEME_NAMES = ["DEEP_SEA_BLUE", "GRAPHITE", "MIDNIGHT_PURPLE", "PINE_GREEN", "WARM_BROWN",
               "WINE_RED", "DARK_GOLD", "DEEP_TEAL", "STEEL_BLUE", "INK_PURPLE",
               "HAZE_BLUE", "CREAM", "MINT", "SAKURA", "LAVENDER", "SKY_BLUE",
               "OAT_GRAY", "LEMON", "CORAL", "SAGE"]
for name in THEME_NAMES:
    # 必须是「一整行就是一个枚举项」，不能用子串匹配：把 PALE_OLIVE 改成 PALE_OLIVE_GONE
    # 之后子串仍然命中，护栏就等于没有。
    check("TvTheme declares " + name, re.search(r"^    " + name + r"\($", theme, re.M) is not None)
# 每个主题一条 `XX(` 的枚举项：漏掉一个、或者多写一个没有对应项的名字，都会在这里被抓住。
check("TvTheme declares exactly the expected themes",
      len(re.findall(r"^    [A-Z][A-Z_]*\($", theme, re.M)) == len(THEME_NAMES))
check("TvTheme keeps a stable key per theme", theme.count("storedValue") >= 1 and '"deep-sea-blue"' in theme)
check("TvTheme derives every tier from three colours",
      "val surface: Int get() = mix(base, light, .45f)" in theme
      and "val well: Int get() = mix(base, dark, .45f)" in theme)
check("TvStyle reads the palette from the theme",
      "internal var theme: TvTheme" in style and "internal fun applyTheme(" in style)
check("switching the theme drops the soft cache", "Soft.trim()" in style)
check("the soft cache key carries the theme", "TvStyle.themeId" in files["TvSoft.kt"])
check("the theme lives outside the content library", "native-theme-v1" in theme)
check("settings offers every theme", "TvTheme.entries.chunked(" in files["MainActivity.kt"])
check("the current theme row is pressed in", "setSelectedState(option==TvStyle.theme)" in files["MainActivity.kt"])
# 14 套材质排成一行 4 个的网格：一行一个主题会把设置页顶出屏幕（14 × 60dp）。
check("the theme picker is a grid", "THEME_GRID_COLUMNS=4" in files["MainActivity.kt"])
check("no accent ring token remains", "accentInlay" not in style and "accentGlow" not in style)
check("the pressed-in state is an inset well",
      "selected -> TvStyle.well(context, TvStyle.selectedWell" in components)
check("the focus state is a lit plate with a focus ring",
      "focused -> TvStyle.lit" in components and "focused || selected -> TvStyle.focusRing" in components)
check("the player keeps theme-independent white",
      "val playerText" in style and "TvStyle.playerText" in files["MainActivity.kt"])
check("the player episode grid uses the player palette",
      "player=true" in files["MainActivity.kt"] and "private val player: Boolean" in files["EpisodeGrid.kt"])
check("no system checkbox remains", all("CheckBox(" not in text for text in files.values()))
for name, text in files.items():
    for number, line in lines(name):
        check("%s:%d no accent-coloured selection ring" % (name, number),
              not re.search(r"ring\s*=[^\n]*TvStyle\.accent\b", line))
        check("%s:%d no accent glow" % (name, number), "accentGlow" not in line)

# 15. 离线预览的主题表必须与 TvTheme.kt 一一对应。
#     预览是「没有设备时唯一的观感依据」，表里少一个色值就会出现「图上是深海蓝、机上不是」。
preview = Path("tools/theme-preview.py")
if preview.exists():
    script = preview.read_text(encoding="utf-8")
    # 预览脚本里写成三元组（`base=(0xE0, 0xE5, 0xEC)`），Kotlin 里写成打包值（`0xFFE0E5EC`），
    # 两种写法都要出现，才算「图上的材质就是机上的材质」。
    # 底色 + 焦点色各二十组：这两列同时决定「材质」和「焦点语言」，
    # 漏一个就会出现「图上是这套、机上不是」。
    for rgb in [(0x1E, 0x22, 0x28), (0x23, 0x27, 0x2C), (0x21, 0x1F, 0x2B), (0x1B, 0x23, 0x20),
                (0x24, 0x1E, 0x1A), (0x25, 0x1A, 0x1F), (0x22, 0x1F, 0x18), (0x18, 0x21, 0x28),
                (0x1A, 0x1F, 0x2B), (0x1C, 0x1A, 0x26), (0xE0, 0xE5, 0xEC), (0xEF, 0xEA, 0xE3),
                (0xE3, 0xEE, 0xE9), (0xF0, 0xE6, 0xEA), (0xE9, 0xE6, 0xF2), (0xE2, 0xED, 0xF2),
                (0xEC, 0xE9, 0xE4), (0xF1, 0xEF, 0xE0), (0xF3, 0xE7, 0xE4), (0xE8, 0xED, 0xE6),
                (0x4D, 0xA3, 0xFF), (0x7F, 0xD1, 0xFF), (0xB9, 0x8C, 0xFF), (0x4F, 0xE0, 0xA8),
                (0xFF, 0xB2, 0x7A), (0xFF, 0x7E, 0xA8), (0xE8, 0xC3, 0x6A), (0x38, 0xD2, 0xE0),
                (0x7A, 0x9C, 0xFF), (0xC7, 0x7D, 0xFF), (0x4A, 0x7D, 0xFF), (0xE0, 0x8A, 0x3C),
                (0x12, 0xA6, 0x7A), (0xD6, 0x46, 0x8C), (0x7C, 0x5C, 0xFF), (0x1A, 0x9B, 0xC7),
                (0x8A, 0x7B, 0x5E), (0xB6, 0xA2, 0x1E), (0xE2, 0x65, 0x4A), (0x5A, 0x9A, 0x45)]:
        packed = "0xFF%02X%02X%02X" % rgb
        triple = "(0x%02X, 0x%02X, 0x%02X)" % rgb
        check("theme-preview mirrors " + packed[2:], triple in script and packed in theme)
else:
    check("theme preview script is missing", False)

rules = {re.sub(r"^[^ ]+\.kt:\d+ ", "", c) for c in checks}
print("button system OK: %d 项规则 / %d 处断言" % (len(rules), len(checks)))
