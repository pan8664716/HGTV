#!/usr/bin/env python3
"""HGTV 的应用图标与电视横幅：一份几何、一套配色，出四份产物。

图标是**扁平风格**的：苹果公司的 Apple 标志（果身 + 果叶）两块纯色——
果身 `#EE4C33`、果叶 `#6E9F3E`（**一整块纯绿，叶面没有中脉**），就这些；
底下**铺一层纯白不透明背景**。没有描边、没有圆角矩形、没有渐变、没有阴影与高光。

2026-10-12 同一天改了两处（都按需求）：

1. 去掉果身正中那枚播放三角——图标缩到桌面的 48dp 之后它只剩几个像素，
   读起来像果身上的一块白斑而不是播放键；
2. 整枚图标从拟物改扁平——原来那套径向渐变（迎光面 → 本色 → 背光边缘）、
   接触阴影、左上高光、右下环境反光，缩到 48dp 之后都只剩一两个像素的杂色，
   扁平色块反而更干净、更清楚。颜色沿用拟物版的本色，因此与旧版是同一个红、同一个绿。

2026-10-16 按需求再去一处：**果叶上那条 `#3E6522` 的中脉删掉**，果叶变成一整块纯绿
（`#6E9F3E`，与果身同样的实心色块）。扁平版里它是唯一的细节，但在桌面 48dp 上只剩一条
深绿细丝，远看不像叶脉、倒像叶面上划了一道；位图与矢量两条渲染路径同时改——
`render_master()` 只剩两块纯色填充（画中脉的 `line_mask()` 一并删掉），
`vector_xml()` 只剩 2 条路径（果身 / 果叶各一条 `fillColor`，不再有 `strokeColor` 那条）。

**为什么是白底而不是透明**（2026-10-12 改版，按需求）：透明底时图标在桌面上的观感
完全由启动器决定，同一张图在不同设备上落成黑方块、深灰方块或直接贴壁纸，看起来像三个应用。

1. 自适应图标的 `background` 层留透明时，模拟器上的 Lawnchair 给每个图标垫一块纯黑底板
   （深色模式），桌面上就是一个黑方块；
2. 把 `background` 换成 alpha=1 的白色，量到的是 (1,1,1)——白底叠在黑底板上，
   说明那块底板在图标下面，白底压得住；
3. 把**前景也换成全透明**（图标什么都不画），那一块纯黑方圆仍然在（128×128、
   15596 个 `sum(rgb)<40` 的像素）——它跟图标的内容无关，换启动器即消失。

所以底板改由图标自己给：`background` 层是 `@color/ic_launcher_background`（纯白 #FFFFFF），
API 26 以下的位图兜底同样铺白底。这样任何启动器、任何壁纸上看到的是同一块白底 + 同一颗果标。

**前景必须待在安全区里**，这一条也在同一台设备上量过：启动器把 108dp 画布画成 192px、
只露出中央 72dp（128px）的方圆遮罩，越出去就被切。所以物件在画布里缩到 0.80：果身整个
落在中央 66dp 的圆里（最远 31dp），果叶尖到 42dp，离 72dp 的遮罩边缘还有 3dp（实测 7px）。
严格圆形的遮罩会削掉果叶尖最后几个像素，方形 / 圆角方形遮罩都完整。

四份产物都从这里生成，避免手改漂移：

| 产物 | 位置 | 用途 |
| --- | --- | --- |
| `app_icon.xml` | `app/src/main/res/drawable/` | 自适应图标的前景（矢量：果身 + 果叶两块纯色） |
| `ic_launcher(.round).xml` | `app/src/main/res/mipmap-anydpi-v26/` | 自适应图标：纯白背景层 + 上面的前景 |
| `ic_launcher(.round).png` | `app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/` | API 26 以下与直接读位图的启动器兜底（白底） |
| `tv_banner.png` | `app/src/main/res/drawable/` | Android TV 桌面卡片（320×180dp，出图 640×360） |

矢量与位图共用同一份轮廓与色值：位图用 PIL + numpy 按多边形填充（超采样抗锯齿），
矢量用同一批控制点转成三次贝塞尔、按同色值填充。因此 `--emit-vector` 写出的 XML
与位图是同一件东西的两种实现，两边都不含渐变。

用法：
    python3 tools/app-icon.py                # 出全部位图 + docs/preview/app-logo.png（透明底）
    python3 tools/app-icon.py --emit-vector  # 同时重写 app_icon.xml
    python3 tools/app-icon.py --check        # 只校验：矢量 / 位图是否与脚本同源（CI 用）
依赖：Pillow、numpy；中文用系统 PingFang / Hiragino。
"""

import json
import math
import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
K_RES = os.path.join(REPO, "android", "app", "src", "main", "res")
PREVIEW = os.path.join(REPO, "docs", "preview")

UNIT = 108.0          # 矢量画布坐标系：108dp 画布 = 108 单位
MASTER = 2048         # 主图边长（108 单位 → 2048px，约 19px/单位），再由它缩出所有尺寸
ICON_SCALE = 0.80     # 图标画布里物件的缩放：自适应图标安全区（见文件头）
MARK_H = 80.0         # 标志在 108 画布里的高度（果叶尖 → 果底）
MARK_CY = 54.0        # 标志的竖直中心（画布中心）：上下都要落在 72dp 可见区里
BANNER = (640, 360)   # 320×180dp 的卡片，出图按 2 倍

# Apple 标志的官方几何（814×1000，绝对坐标；果身一条闭合子路径 + 果叶一条闭合子路径）。
APPLE_D = (
    "M788.1 340.9c-5.8 4.5-108.2 62.2-108.2 190.5 0 148.4 130.3 200.9 134.2 202.2"
    "-.6 3.2-20.7 71.9-68.7 141.9-42.8 61.6-87.5 123.1-155.5 123.1"
    "s-85.5-39.5-164-39.5c-76.5 0-103.7 40.8-165.9 40.8s-105.6-57-155.5-127"
    "C46.7 790.7 0 663 0 541.8c0-194.4 126.4-297.5 250.8-297.5 66.1 0 121.2 43.4 162.7 43.4"
    " 39.5 0 101.1-46 176.3-46 28.5 0 130.9 2.6 198.3 99.2z"
    "m-234-181.5c31.1-36.9 53.1-88.1 53.1-139.3 0-7.1-.6-14.3-1.9-20.1"
    "-50.6 1.9-110.8 33.7-147.1 75.8-28.5 32.4-55.1 83.6-55.1 135.5 0 7.8 1.3 15.6 1.9 18.1"
    " 3.2.6 8.4 1.3 13.6 1.3 45.4 0 102.5-30.4 135.5-71.3z"
)

# 中文必须用带 CJK 字形的系统字体：PingFang.ttc 在这里取到的两个 face 都只有拉丁字形。
FONT_PATH = "/System/Library/Fonts/Hiragino Sans GB.ttc"


def font(size, bold=False):
    for idx in ((2, 0) if bold else (0, 2)):
        try:
            return ImageFont.truetype(FONT_PATH, size, index=idx)
        except Exception:
            pass
    try:
        return ImageFont.truetype("/System/Library/Fonts/STHeiti Medium.ttc", size)
    except Exception:
        return ImageFont.load_default()


# ============ 调色板（扁平风格：纯色块，没有渐变与阴影） ============

# 2026-10-12 第三次改版：图标整体换成扁平风格——果身与果叶各一个纯色块，
# 没有径向渐变、没有接触阴影、没有高光与反光。
# 2026-10-16 按需求：果叶上那条中脉去掉，果叶就是上面这一个纯色（见文件头）。
# 颜色沿用拟物版的本色，因此在桌面上与旧版是同一个红、同一个绿。
FRUIT = (0xEE, 0x4C, 0x33)         # 果身：单色（旧版的「本色」）
LEAF = (0x6E, 0x9F, 0x3E)          # 果叶：单色（一整块纯绿，叶面没有中脉）

# 图标背景：纯白不透明。底板由图标自己给（见文件头），所有启动器上看到的是同一块白底。
BG_WHITE = (0xFF, 0xFF, 0xFF)

INK = (0xF4, 0xF3, 0xF1)           # 横幅文字（TvStyle.playerText）
ACCENT = (0xDF, 0xB7, 0x7C)        # 香槟金（TvStyle.accent）
GRAPHITE = (0x2E, 0x32, 0x38)      # 深邃暗调底色（TvStyle.background）


# ============ 路径解析（SVG 子集 → 绝对三次贝塞尔） ============

TOKEN = re.compile(r"[MmLlHhVvCcSsZz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def parse_path(d):
    """返回 [(子路径, [('M'|'L'|'C', 点 或 (c1,c2,p))])]。"""
    toks = TOKEN.findall(d)
    subs = []
    segs = []
    x = y = 0.0
    sx = sy = 0.0
    c2 = None                      # 上一个控制点（给 S/s 用）
    i = 0
    cmd = None

    def take(n):
        nonlocal i
        vals = [float(v) for v in toks[i:i + n]]
        i += n
        return vals

    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
            if cmd in "Zz":
                segs.append(("Z", None))
                x, y = sx, sy
                continue
        if cmd in "Mm":
            px, py = take(2)
            x, y = (px, py) if cmd == "M" else (x + px, y + py)
            sx, sy = x, y
            if segs:
                subs.append(segs)
            segs = [("M", (x, y))]
            cmd = "L" if cmd == "M" else "l"
            c2 = None
        elif cmd in "Ll":
            px, py = take(2)
            x, y = (px, py) if cmd == "L" else (x + px, y + py)
            segs.append(("L", (x, y)))
            c2 = None
        elif cmd in "Hh":
            px = take(1)[0]
            x = px if cmd == "H" else x + px
            segs.append(("L", (x, y)))
            c2 = None
        elif cmd in "Vv":
            py = take(1)[0]
            y = py if cmd == "V" else y + py
            segs.append(("L", (x, y)))
            c2 = None
        elif cmd in "CcSs":
            if cmd in "Cc":
                x1, y1, x2v, y2v, px, py = take(6)
                if cmd == "c":
                    x1, y1, x2v, y2v, px, py = (x + x1, y + y1, x + x2v, y + y2v, x + px, y + py)
            else:
                x2v, y2v, px, py = take(4)
                if cmd == "s":
                    x2v, y2v, px, py = (x + x2v, y + y2v, x + px, y + py)
                x1, y1 = (2 * x - c2[0], 2 * y - c2[1]) if c2 else (x, y)
            segs.append(("C", ((x1, y1), (x2v, y2v), (px, py))))
            c2 = (x2v, y2v)
            x, y = px, py
        else:
            raise ValueError("不支持的路径命令：%r" % cmd)
    if segs:
        subs.append(segs)
    return subs


def sub_bbox(segs):
    xs, ys = [], []
    for cmd, arg in segs:
        if cmd == "M" or cmd == "L":
            xs.append(arg[0]); ys.append(arg[1])
        elif cmd == "C":
            for p in arg:
                xs.append(p[0]); ys.append(p[1])
    return min(xs), min(ys), max(xs), max(ys)


def flatten(segs, samples=26):
    """把子路径压成多边形点列。"""
    pts = []
    cur = (0.0, 0.0)
    for cmd, arg in segs:
        if cmd == "M":
            cur = arg
            pts.append(cur)
        elif cmd == "L":
            cur = arg
            pts.append(cur)
        elif cmd == "C":
            c1, c2, p = arg
            for s in range(1, samples + 1):
                t = s / samples
                u = 1 - t
                pts.append((
                    u ** 3 * cur[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * p[0],
                    u ** 3 * cur[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * p[1],
                ))
            cur = p
    return pts


# ============ 仿射：814×1000 → 108 画布 ============

_APPLE_SUBS = parse_path(APPLE_D)
_APPLE_BB = (min(sub_bbox(s)[0] for s in _APPLE_SUBS),
             min(sub_bbox(s)[1] for s in _APPLE_SUBS),
             max(sub_bbox(s)[2] for s in _APPLE_SUBS),
             max(sub_bbox(s)[3] for s in _APPLE_SUBS))
APPLE_S = MARK_H / (_APPLE_BB[3] - _APPLE_BB[1])
APPLE_CX = (_APPLE_BB[0] + _APPLE_BB[2]) / 2.0
APPLE_CY = (_APPLE_BB[1] + _APPLE_BB[3]) / 2.0


def T(p):
    """源坐标 → 108 画布坐标。"""
    return (54.0 + (p[0] - APPLE_CX) * APPLE_S, MARK_CY + (p[1] - APPLE_CY) * APPLE_S)


def t_segs(segs):
    return [(cmd, (T(arg) if cmd in ("M", "L") else tuple(T(p) for p in arg)) if arg else None)
            for cmd, arg in segs]


BODY_SEGS = t_segs(_APPLE_SUBS[0])
LEAF_SEGS = t_segs(_APPLE_SUBS[1])
BODY_PTS = flatten(BODY_SEGS, 30)
LEAF_PTS = flatten(LEAF_SEGS, 30)
BB = sub_bbox(BODY_SEGS)                     # 果身包围盒（108 画布）


def num(v):
    """路径与渐变的坐标：一位小数（108 单位里 0.1 ≈ 0.1dp，够用）。"""
    s = "%.1f" % v
    return s[:-2] if s.endswith(".0") else s


def num2(v):
    """group 的缩放：两位小数。0.88 被写成 0.9 会越过自适应图标的安全区。"""
    s = "%.2f" % v
    return s.rstrip("0").rstrip(".") if "." in s else s


def path_d(segs, dx=0.0, dy=0.0):
    out = []
    for cmd, arg in segs:
        if cmd == "M":
            out.append("M%s,%s" % (num(arg[0] + dx), num(arg[1] + dy)))
        elif cmd == "L":
            out.append("L%s,%s" % (num(arg[0] + dx), num(arg[1] + dy)))
        elif cmd == "C":
            c1, c2, p = arg
            out.append("C%s,%s %s,%s %s,%s" % (num(c1[0] + dx), num(c1[1] + dy),
                                               num(c2[0] + dx), num(c2[1] + dy),
                                               num(p[0] + dx), num(p[1] + dy)))
        else:
            out.append("Z")
    return "".join(out)


# ============ 位图渲染 ============


def ramp(t, stops):
    out = np.zeros(t.shape + (3,), np.float32)
    ts = np.clip(t, 0.0, 1.0)
    for i in range(len(stops) - 1):
        o0, c0 = stops[i]
        o1, c1 = stops[i + 1]
        if o1 <= o0:
            continue
        m = (ts >= o0) & (ts <= o1)
        f = np.clip((ts - o0) / (o1 - o0), 0.0, 1.0)[..., None]
        seg = np.array(c0, np.float32) * (1 - f) + np.array(c1, np.float32) * f
        out = np.where(m[..., None], seg, out)
    out = np.where((ts < stops[0][0])[..., None], np.array(stops[0][1], np.float32), out)
    out = np.where((ts > stops[-1][0])[..., None], np.array(stops[-1][1], np.float32), out)
    return out


def render_master():
    """扁平渲染：果身与果叶各一块纯色——物件的全部内容就是这两块色。

    没有径向渐变、没有接触阴影、没有高光与反光——缩到桌面的 48dp 之后，
    这些层次只剩一两个像素的杂色，扁平色块反而更清楚。果叶上的中脉 2026-10-16
    按需求删掉（见文件头）：在 48dp 上它只是一条深绿细丝，不成叶脉。
    """
    N = MASTER
    k = N / UNIT
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
    X, Y = xx / k, yy / k

    def poly_mask(pts, dx=0.0, dy=0.0):
        m = Image.new("L", (N, N), 0)
        ImageDraw.Draw(m).polygon([((x + dx) * k, (y + dy) * k) for x, y in pts], fill=255)
        return np.asarray(m, np.float32) / 255.0

    m_body = poly_mask(BODY_PTS)
    m_leaf = poly_mask(LEAF_PTS)

    rgb = np.zeros((N, N, 3), np.float32)
    alpha = np.zeros((N, N), np.float32)

    def over(l_rgb, l_a):
        nonlocal rgb, alpha
        la = np.clip(l_a, 0.0, 1.0)
        out_a = la + alpha * (1 - la)
        safe = np.maximum(out_a, 1e-6)[..., None]
        rgb = (np.asarray(l_rgb, np.float32) * la[..., None]
               + rgb * alpha[..., None] * (1 - la[..., None])) / safe
        alpha = out_a

    # —— 1. 果身：一块纯色 ——
    over(FRUIT, m_body)

    # —— 2. 果叶：一块纯色（叶面没有中脉，见文件头） ——
    over(LEAF, m_leaf)

    return Image.fromarray(
        np.dstack([np.clip(rgb, 0, 255), np.clip(alpha * 255, 0, 255)]).astype(np.uint8), "RGBA")


_MASTER_IMG = None


def master():
    global _MASTER_IMG
    if _MASTER_IMG is None:
        _MASTER_IMG = render_master()
    return _MASTER_IMG


def icon_image(size, scale=ICON_SCALE, transparent=False, bg=None):
    """把主图按 scale 放进画布中央，缩到 size×size。

    默认**铺纯白不透明底**（桌面图标与电视横幅用的都是这一版，见文件头）；
    `bg` 可以换一块别的纯色（只给预览用）；`transparent=True` 出透明底，
    只用来做「透明底在启动器上会被垫黑板」的对照，不产出任何仓库文件。
    """
    src = master()
    inner = max(1, int(round(size * scale)))
    art = src.resize((inner, inner), Image.LANCZOS)
    if transparent:
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    else:
        canvas = Image.new("RGBA", (size, size), tuple(bg or BG_WHITE) + (255,))
    off = (size - art.width) // 2
    canvas.alpha_composite(art, (off, off))
    return canvas


def readme_logo(size=512, margin=0.06):
    """README 首行用的应用图标：透明底，按可见内容裁紧后再缩到 size。

    `icon_image` 按自适应图标安全区把物件缩到 0.80，画布里会留一圈空白；
    桌面图标需要那圈余量，README 里不需要——这里裁掉空白、留一点边距即可。
    """
    src = icon_image(size * 2, transparent=True)          # 先在 2 倍画布上出图，裁完再缩，边缘更干净
    box = src.getbbox()
    if box:
        pad = int(round(max(box[2] - box[0], box[3] - box[1]) * margin))
        box = (max(0, box[0] - pad), max(0, box[1] - pad),
               min(src.width, box[2] + pad), min(src.height, box[3] + pad))
        src = src.crop(box)
    side = max(src.width, src.height)                     # 补成正方形，避免 README 里被拉伸
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.alpha_composite(src, ((side - src.width) // 2, (side - src.height) // 2))
    return canvas.resize((size, size), Image.LANCZOS)


def graphite_plate(w, h, center=(0.34, 0.5)):
    """石墨径向底：中心亮一档、四角压深，没有边界线也没有圆角。"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy = center[0] * w, center[1] * h
    r = np.sqrt(((xx - cx) / (w * 0.95)) ** 2 + ((yy - cy) / (h * 1.15)) ** 2)
    col = ramp(r, [(0.0, (0x40, 0x46, 0x4F)), (0.42, GRAPHITE), (1.0, (0x1E, 0x22, 0x27))])
    return Image.fromarray(col.astype(np.uint8), "RGB")


def banner_font(d, text, avail, largest=152, smallest=64):
    """横幅文字：从 [largest] 往下找第一个整行放得下的字号，返回（字体，墨迹包围盒）。

    写死字号会随系统字体的落点变化而溢出卡片（同一句话在不同字号下宽度差一截），
    因此按可用宽度自适应；[smallest] 只是兜底，真取到它说明布局参数被改坏了。
    """
    for size in range(largest, smallest - 1, -2):
        f = font(size, bold=True)
        box = d.textbbox((0, 0), text, font=f)
        if box[2] - box[0] <= avail:
            return f, box
    f = font(smallest, bold=True)
    return f, d.textbbox((0, 0), text, font=f)


def banner_mark(height):
    """横幅左侧的标志：**透明底的果标**，按可见内容裁紧后缩到 [height] 高。

        透明底这一版直接取 [icon_image] 的透明输出（与桌面图标同一份几何、同一套色值），
    再裁掉自适应图标安全区留下的空白、按高度等比缩放——因此物件本身就是图标那么大，
    不再是「一块白底板里的一颗小苹果」。

    为什么横幅用透明底、而桌面图标继续铺白底：图标的白底板是为了在各家启动器上底色一致
    （见文件头），横幅自带石墨底，白底板在这里只会变成一块突兀的白方块。
    """
    src = icon_image(height * 2, transparent=True)      # 2 倍出图再缩，边缘干净
    box = src.getbbox()
    if box:
        src = src.crop(box)
    width = max(1, int(round(src.width * height / src.height)))
    return src.resize((width, height), Image.LANCZOS)


def banner_image():
    """电视横幅（320×180dp，出图 640×360）：左边果标，右边「HGTV」。

    2026-10-16 按需求两次重排，这一版是结果：

    1. **标志用透明底**（见 [banner_mark]）：不再是一块白底板，直接压在石墨底上；
    2. **标志尺寸收到卡片高度的 52%**：果标本体约 187px 高。透明底之后果标本身就是「图标那么大」，
       58% 那一版在卡片里显得抢眼，52% 与右侧文字更配；
    3. **「HGTV」用放得下的最大字号**（640 宽下约 140 号，墨迹约 120px 高）：写死字号会随系统
       字体落点变化而溢出卡片，因此按可用宽度自适应；
    4. **去掉文字下方的金线**：品牌色靠标志本身表达，文字下面不再压一条装饰线；
    5. 左 / 右安全边距与标志、文字之间的间距按「整块 lockup 左右基本等距」重算，
       文字墨迹中心与标志竖向中心都压在卡片中线上。
    """
    w, h = BANNER
    img = graphite_plate(w, h).convert("RGBA")
    # 标志：苹果本体占卡片高度的 52%（宽高比固定，宽度由 banner_mark 按高度算出来）。
    mark = banner_mark(int(round(h * 0.52)))
    x = int(round(w * 0.0625))
    img.alpha_composite(mark, (x, (h - mark.height) // 2))
    d = ImageDraw.Draw(img)
    text = "HGTV"
    left = x + mark.width + int(round(w * 0.06))
    avail = w - left - int(round(w * 0.06))
    f, box = banner_font(d, text, avail)
    ink_w, ink_h = box[2] - box[0], box[3] - box[1]
    # 文字块在剩余宽度里居中，纵向墨迹中心压在卡片中线上（与标志同一条中线）。
    tx = left + (avail - ink_w) // 2 - box[0]
    ty = (h - ink_h) // 2 - box[1]
    d.text((tx, ty), text, font=f, fill=INK + (255,))
    return img.convert("RGB")


# ============ 矢量 ============


def hexc(rgb, a=None):
    if a is None:
        return "#%02X%02X%02X" % tuple(rgb)
    return "#%02X%02X%02X%02X" % ((a,) + tuple(rgb))


def vector_path(pd, fill, fill_type=None, indent=8):
    """一条纯色路径。扁平风格没有渐变，因此不再需要 `<aapt:attr>` 那一套。"""
    pad = " " * indent
    attrs = ['android:pathData="%s"' % pd]
    if fill_type:
        attrs.append('android:fillType="%s"' % fill_type)
    attrs.append('android:fillColor="%s"' % fill)
    return "%s<path %s/>" % (pad, " ".join(attrs))


def vector_xml():
    body_d = path_d(BODY_SEGS)
    leaf_d = path_d(LEAF_SEGS)

    L = []
    A = L.append
    A("<!--")
    A("  应用图标的前景：苹果公司的 Apple 标志（果身 + 果叶），扁平风格——纯色块，没有渐变与阴影。")
    A("")
    A("  这一层只有物件（透明底）；底板是纯白不透明的一层，由 mipmap-anydpi-v26/ic_launcher.xml")
    A("  的 background 层引用 @color/ic_launcher_background（#FFFFFF）。为什么要拆成两层：")
    A("  背景层留透明时，启动器会自己垫一块底板（模拟器的三星风格启动器上量到的是纯黑），")
    A("  同一张图在不同设备上就不是一个颜色了；底板由图标自己给，前景保持透明，")
    A("  物件才能干净地压在底板上。")
    A("")
    A("  物件的做法（2026-10-12 起，2026-10-16 去掉果叶中脉）：果身 #EE4C33、果叶 #6E9F3E")
    A("  两块纯色，就这些——果叶是一整块纯绿，叶面没有中脉。拟物版那套径向渐变")
    A("  （迎光面 → 本色 → 背光边缘）、接触阴影、左上高光与右下反光都删掉了——")
    A("  缩到桌面的 48dp 之后它们只剩一两个像素的杂色；同版还去掉了果身正中那枚播放三角")
    A("  （同上原因），后来那条 #3E6522 的果叶中脉也一起去掉，位置同上。")
    A("")
    A("  外层 group 的 0.80 是自适应图标的安全区（在真机启动器上量过：108dp 画布只露出")
    A("  中央 72dp）：果身整个落在中央 66dp 的圆里（最远 31dp），果叶尖到 42dp，离 72dp")
    A("  的遮罩边缘还有 3dp，不会被启动器切掉。改形状改 tools/app-icon.py，然后重跑，")
    A("  不要手改本文件。")
    A("-->")
    A('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
    A('    android:width="108dp" android:height="108dp"')
    A('    android:viewportWidth="108" android:viewportHeight="108">')
    A('    <group android:scaleX="%s" android:scaleY="%s" android:pivotX="54" android:pivotY="54">'
      % (num2(ICON_SCALE), num2(ICON_SCALE)))
    # 1 果身：纯色
    A(vector_path(body_d, fill=hexc(FRUIT)))
    # 2 果叶：纯色（一整块纯绿，没有中脉）
    A(vector_path(leaf_d, fill=hexc(LEAF)))
    A("    </group>")
    A("</vector>")
    return "\n".join(L) + "\n"


def adaptive_xml():
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!--\n'
            '  自适应图标：背景层是**纯白不透明**（@color/ic_launcher_background），前景是拟物果标。\n'
            '  底板由图标自己给，不再留给启动器决定：透明背景时不同启动器会垫黑 / 深灰底板，\n'
            '  同一张图在不同设备上看起来像三个应用（见 tools/app-icon.py 文件头的三轮实测）。\n'
            '  前景 @drawable/app_icon 已经把物件缩进安全区（见 app_icon.xml）。\n'
            '-->\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@drawable/app_icon"/>\n'
            '</adaptive-icon>\n')


def color_xml():
    """纯白背景层：一个色值资源，自适应图标与位图兜底共用同一块底。"""
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!--\n'
            '  应用图标的背景层：纯白不透明（#FFFFFF）。\n'
            '  改色值要同时改 tools/app-icon.py 的 BG_WHITE 并重跑，否则位图兜底与矢量会对不上。\n'
            '-->\n'
            '<resources>\n'
            '    <color name="ic_launcher_background">#FFFFFF</color>\n'
            '</resources>\n')


# ============ 输出 ============

MIPMAPS = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]


def save_xml(text, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def preview_icon(size, scale=ICON_SCALE):
    """图标本体（纯白底），预览里直接贴在壁纸上——遮罩交给启动器。"""
    return icon_image(size, scale).convert("RGBA")


def preview_sheet():
    """离线校对图：三种壁纸上的大图 + 真实小尺寸 + 白底/透明底对照 + 横幅。"""
    W, pad, big, head = 1160, 26, 300, 96
    wallpapers = [("深色壁纸", (0x18, 0x1E, 0x2A)), ("中灰", (0x8A, 0x90, 0x99)),
                  ("浅色壁纸", (0xE6, 0xEA, 0xEF))]
    strips = [("深色壁纸", (0x18, 0x1E, 0x2A)), ("浅色壁纸", (0xE6, 0xEA, 0xEF))]
    strip_h, side = 210, big // 2
    banner = banner_image().resize((int(BANNER[0] * 0.72), int(BANNER[1] * 0.72)), Image.LANCZOS)
    H = head + big + 52 + 34 + len(strips) * (strip_h + 16) + 34 + side + 20 + 46 + 30 + banner.height + 26
    img = Image.new("RGB", (W, H), (0xFA, 0xFA, 0xFB))
    d = ImageDraw.Draw(img)
    d.text((pad, 26), "HGTV 应用图标 · Apple 标志（扁平 · 纯白背景）",
           font=font(30, bold=True), fill=(0x1A, 0x1D, 0x22))
    d.text((pad, 62), "tools/app-icon.py 生成：两块纯色 + 一块纯白不透明底板，下面是同一张图贴在三种壁纸上。",
           font=font(19), fill=(0x5A, 0x62, 0x6C))
    x = pad
    for label, bg in wallpapers:
        cell = Image.new("RGB", (big + 40, big + 40), bg)
        art = preview_icon(big)
        cell.paste(art, (20, 20), art)
        img.paste(cell, (x, head))
        d.text((x + 6, head + big + 8), label, font=font(19), fill=(0x5A, 0x62, 0x6C))
        x += big + 40 + 14
    y = head + big + 52
    d.text((pad, y), "真实尺寸（48–192dp）：", font=font(19), fill=(0x5A, 0x62, 0x6C))
    y += 34
    for label, bg in strips:
        strip = Image.new("RGB", (W - 2 * pad, strip_h), bg)
        xx = 20
        for size in (192, 144, 96, 72, 48):
            art = preview_icon(size)
            strip.paste(art, (xx, strip_h // 2 - size // 2), art)
            xx += size + 26
        d.text((pad + 12, y + 6), label, font=font(19),
               fill=(0x3A, 0x40, 0x48) if bg[0] > 0x80 else (0xC8, 0xCE, 0xD6))
        img.paste(strip, (pad, y))
        y += strip_h + 16
    # 白底 / 透明底对照：同一张图不铺白底时，浅色壁纸上果身边缘发飘，深色壁纸上直接被吞掉。
    d.text((pad, y), "白底与透明底对照（同一块中灰壁纸，一半尺寸）：", font=font(19), fill=(0x5A, 0x62, 0x6C))
    y += 34
    cx = pad
    for label, art in (("纯白底（当前）", icon_image(big)),
                       ("透明底（旧版，对照）", icon_image(big, transparent=True))):
        cell = Image.new("RGB", (side + 20, side + 20), (0x8A, 0x90, 0x99))
        thumb = art.resize((side, side), Image.LANCZOS)
        cell.paste(thumb, (10, 10), thumb)
        img.paste(cell, (cx, y))
        d.text((cx + 4, y + side + 26), label, font=font(17), fill=(0x5A, 0x62, 0x6C))
        cx += side + 40
    y += side + 20 + 46
    d.text((pad, y), "电视横幅 tv_banner.png（320×180dp，Android TV 桌面卡片）：",
           font=font(19), fill=(0x5A, 0x62, 0x6C))
    img.paste(banner, (pad, y + 30))
    return img


def contrast(a, b):
    def lum(c):
        c = [v / 255.0 for v in c]
        c = [(v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4) for v in c]
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def check():
    """核对仓库里的矢量、自适应图标、位图与脚本同源（改完图标跑这一条）。"""
    bad = 0

    def want_file(path, want, what):
        nonlocal bad
        got = open(path, encoding="utf-8").read() if os.path.exists(path) else ""
        if got != want:
            print("漂移：%s（%s 与 tools/app-icon.py 不一致，跑 --emit-vector 重写）"
                  % (os.path.relpath(path, REPO), what))
            bad += 1

    def want_png(path, size):
        nonlocal bad
        if not os.path.exists(path):
            print("缺失：%s" % os.path.relpath(path, REPO))
            bad += 1
            return
        got = Image.open(path).size
        if got != size:
            print("尺寸不对：%s 是 %dx%d，应为 %dx%d"
                  % (os.path.relpath(path, REPO), got[0], got[1], size[0], size[1]))
            bad += 1

    res = K_RES
    want_file(os.path.join(res, "drawable", "app_icon.xml"), vector_xml(), "矢量")
    want_file(os.path.join(res, "values", "ic_launcher_background.xml"), color_xml(), "背景色")
    for name in ("ic_launcher", "ic_launcher_round"):
        want_file(os.path.join(res, "mipmap-anydpi-v26", "%s.xml" % name),
                  adaptive_xml(), "自适应图标")
    want_png(os.path.join(res, "drawable", "tv_banner.png"), BANNER)
    for dpi, px in MIPMAPS:
        want_png(os.path.join(res, "mipmap-%s" % dpi, "ic_launcher.png"), (px, px))
        want_png(os.path.join(res, "mipmap-%s" % dpi, "ic_launcher_round.png"), (px, px))

    if not bad:
        print("图标同源：1 份矢量、1 份背景色、2 份自适应图标、10 张启动器位图、1 张电视横幅")
    print("果肉 / 浅色桌面对比度：%.2f:1（图标自身轮廓要能站住，不看 4.5:1 文字门槛）"
          % contrast(FRUIT, (0xE0, 0xE5, 0xEC)))
    print("果肉 / 深色桌面对比度：%.2f:1" % contrast(FRUIT, GRAPHITE))
    return bad


def write_all(emit_vector=True):
    made = []

    def save(img, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        made.append(path)

    res = K_RES
    for dpi, px in MIPMAPS:
        # 位图兜底也铺白底：API 26 以下、以及直接读位图的启动器看到的是同一块纯白。
        img = icon_image(px)
        save(img, os.path.join(res, "mipmap-%s" % dpi, "ic_launcher.png"))
        # 圆形变体与方形同一张图：遮罩形状由启动器自己套
        save(img, os.path.join(res, "mipmap-%s" % dpi, "ic_launcher_round.png"))
    for name in ("ic_launcher", "ic_launcher_round"):
        save_xml(adaptive_xml(), os.path.join(res, "mipmap-anydpi-v26", "%s.xml" % name))
    save_xml(color_xml(), os.path.join(res, "values", "ic_launcher_background.xml"))
    save(banner_image(), os.path.join(res, "drawable", "tv_banner.png"))
    if emit_vector:
        save_xml(vector_xml(), os.path.join(res, "drawable", "app_icon.xml"))

    save(readme_logo(512), os.path.join(PREVIEW, "app-logo.png"))
    save(preview_sheet(), os.path.join(PREVIEW, ".local", "icon.png"))
    return made


def main():
    args = sys.argv[1:]
    if "--check" in args:
        sys.exit(1 if check() else 0)
    made = write_all(emit_vector="--emit-vector" in args or "--all" in args)
    print("标志：%d 条子路径，果身包围盒 x %.1f–%.1f / y %.1f–%.1f（108 画布）"
          % (len(_APPLE_SUBS), BB[0], BB[2], BB[1], BB[3]))
    print("写出 %d 个文件：" % len(made))
    for p in made:
        print("  " + os.path.relpath(p, REPO))


if __name__ == "__main__":
    main()
