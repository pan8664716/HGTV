#!/usr/bin/env python3
"""新拟态界面的离线预览。

这个脚本用 PIL + numpy 复刻 `SoftDrawable` 的绘制顺序（柔光 = 形状的模糊剪影 + 偏移，
凹陷 = 两条 45° 线性渐变裁剪到面板内，暗角 = 径向渐变），令牌数值与 `TvStyle.kt` 一一对应。
它的用途只有一个：**在没有设备的情况下校对观感**——材质明度差够不够、投影强度合不合适、
焦点态在深色底上够不够亮。

它**不是** Android 渲染器的替代品：字体、字距、真实海报、遥控器焦点移动、
低端 GPU 上的帧率都不在这里验证，这些仍要在实体电视上看。

这里的色板是**深邃暗调**（默认主题）那一套的镜像，状态语言与 2026-10-07 主题改版一致：
焦点 = 打亮（`lit` + 光晕，**没有细环**）、选中 = 按进去（`inset`）或抬一档（`surfaceHigh`）、
强调色只做线与小色块。十四套主题排成 7 列 × 2 行的对比图在 `tools/theme-preview.py`（`docs/preview/theme.png`）。

用法：
    python3 tools/neumorphism-preview.py [输出目录]     # 默认 ../docs/preview/.local（不入库）
依赖：Pillow、numpy；中文用系统字体 PingFang（macOS）。
"""
import os, sys
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "preview", ".local")
os.makedirs(OUT, exist_ok=True)

# ============ 绘制核心（对应 TvSoft.kt / TvStyle.kt） ============
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import numpy as np

# 色板与 TvTheme.kt 的 DEEP_DARK（深邃暗调）一一对应：底色 #2E3238 / 亮部 #383D44 / 暗部 #24272C。
# 凸起面、抬升面、凹陷面都是这三个值派生的（45% / 72%），与 TvTheme 的 mix() 同一套公式。
BACKGROUND=(0x2E,0x32,0x38)
SURFACE=tuple(round(BACKGROUND[i]+((0x38,0x3D,0x44)[i]-BACKGROUND[i])*.45) for i in range(3))
SURFACE_HIGH=tuple(round(BACKGROUND[i]+((0x38,0x3D,0x44)[i]-BACKGROUND[i])*.72) for i in range(3))
WELL=tuple(round(BACKGROUND[i]+((0x24,0x27,0x2C)[i]-BACKGROUND[i])*.45) for i in range(3))
WELL_DEEP=tuple(round(BACKGROUND[i]+((0x24,0x27,0x2C)[i]-BACKGROUND[i])*.72) for i in range(3))
ACCENT=(0xDF,0xB7,0x7C); TEXT=(0xF4,0xF3,0xF1)
LIT=(0xF0,0xEA,0xE0); ON_LIT=(0x1A,0x16,0x12)          # 焦点 / 主操作：被打亮的暖白面板
LIT_GLOW=(0xFF,0xF3,0xDF,0x33)
SELECTED=SURFACE_HIGH                                   # 选中（抬一档）：网格当前集、当前页图标
SELECTED_WELL=WELL                                      # 选中（按进去）：开关、选项、设置项、主题行
SHADOW_DARK=(0x24,0x27,0x2C); SHADOW_LIGHT=(0x38,0x3D,0x44)   # 投影 / 高光用表里的两档阴影色
SHADOW_DARK_A=0x9E; SHADOW_LIGHT_A=0x9E; INNER_DARK_A=0x80; INNER_LIGHT_A=0x1A
OUTLINE_A=0x1A
TEXT_STRONG_A=0xD6; TEXT_DIM_A=0xB3; MUTED_A=0x9E   # 84% / 70% / 62%，与 TvTheme 的四级一致
RADIUS_POSTER=12; RADIUS_CARD=14; RADIUS_PILL=26; RADIUS_DIALOG=24
BAND=6; BAND_LARGE=10
# 中文必须用带 CJK 字形的系统字体：PingFang.ttc 在这里取到的两个 face 都只有拉丁字形，
# 预览图会整片方框。Hiragino Sans GB 的 index 2 是 W6（粗），index 0 是 W3（常规）。
FONT="/System/Library/Fonts/Hiragino Sans GB.ttc"
def font(size,bold=False):
    for idx in ((2,0) if bold else (0,2)):
        try: return ImageFont.truetype(FONT,size,index=idx)
        except Exception: pass
    try: return ImageFont.truetype("/System/Library/Fonts/STHeiti Medium.ttc",size)
    except Exception: return ImageFont.load_default()

class Canvas:
    def __init__(self,w,h,bg):
        self.w,self.h=w,h
        self.img=Image.new('RGBA',(w,h),bg+(255,))
        self.d=ImageDraw.Draw(self.img)
    def mask(self,rect,radius):
        m=Image.new('L',(self.w,self.h),0)
        ImageDraw.Draw(m).rounded_rectangle(rect,radius=radius,fill=255)
        return m
    def comp(self,layer): self.img=Image.alpha_composite(self.img,layer); self.d=ImageDraw.Draw(self.img)
    def flat(self,rect,radius,color,alpha=255):
        m=self.mask(rect,radius)
        if alpha<255: m=m.point(lambda v:int(v*alpha/255))
        l=Image.new('RGBA',(self.w,self.h),color+(255,)); l.putalpha(m); self.comp(l)
    def blur_off(self,mask,sigma,dx,dy):
        m=mask.filter(ImageFilter.GaussianBlur(max(sigma,.6)))
        return m.transform((self.w,self.h),Image.AFFINE,(1,0,-dx,0,1,-dy))
    def plate(self,rect,radius,band,fill,depth=1.0,glow=None,ring=None,ring_w=0,q=1.0):
        x0,y0,x1,y1=rect; p=(x0+band,y0+band,x1-band,y1-band)
        if glow: self.comp_tint(self.blur_off(self.mask(p,radius),band*1.35*q*.6,0,0),glow)
        if depth>0:
            self.comp_tint(self.blur_off(self.mask(p,radius),band*1.05*q*.6,band*.36*depth*q,band*.46*depth*q),SHADOW_DARK+(SHADOW_DARK_A,))
            self.comp_tint(self.blur_off(self.mask(p,radius),band*.95*q*.6,-band*.32*depth*q,-band*.40*depth*q),SHADOW_LIGHT+(SHADOW_LIGHT_A,))
        if fill and fill[3]>0: self.comp_tint(self.mask(p,radius),fill)
        if ring and ring_w>0: self.stroke(p,radius,ring,ring_w)
        return p
    def stroke(self,rect,radius,color,width):
        m=Image.new('L',(self.w,self.h),0)
        ImageDraw.Draw(m).rounded_rectangle(rect,radius=radius,outline=255,width=width)
        self.comp_tint(m,color)
    def comp_tint(self,mask,color):
        l=Image.new('RGBA',(self.w,self.h),color[:3]+(255,))
        l.putalpha(mask.point(lambda v:int(v*color[3]/255)))
        self.comp(l)
    def inset(self,rect,radius,band,fill,depth=1.0,ring=None):
        x0,y0,x1,y1=rect; p=(x0+band,y0+band,x1-band,y1-band)
        if fill and fill[3]>0: self.comp_tint(self.mask(p,radius),fill)
        clip=np.asarray(self.mask(p,radius),dtype=np.float32)/255.0
        yy,xx=np.mgrid[0:self.h,0:self.w]
        reach=max(band*2.6*depth,1.0)
        t=np.clip(((xx-p[0])+(yy-p[1]))/(2*reach),0,1)
        a_dark=(1-t)*INNER_DARK_A*clip
        t2=np.clip(((p[2]-xx)+(p[3]-yy))/(2*reach),0,1)
        a_light=(1-t2)*INNER_LIGHT_A*clip
        base=np.asarray(self.img,dtype=np.float32)
        for a,color in ((a_dark,SHADOW_DARK),(a_light,SHADOW_LIGHT)):
            al=(a/255.0)[...,None]
            base[...,:3]=base[...,:3]*(1-al)+np.array(color,dtype=np.float32)*al
        self.img=Image.fromarray(np.clip(base,0,255).astype(np.uint8),'RGBA')
        self.d=ImageDraw.Draw(self.img)
        if ring: self.stroke(p,radius,ring,1)
        return p
    def shade(self,rect,radius,band,fill):
        """SHADE：面板 + 径向暗角（压在海报上的井）。"""
        p=self.inset(rect,radius,band,fill) if fill and fill[3]>0 else (rect[0]+band,rect[1]+band,rect[2]-band,rect[3]-band)
        clip=np.asarray(self.mask(p,radius),dtype=np.float32)/255.0
        yy,xx=np.mgrid[0:self.h,0:self.w]
        cx,cy=(p[0]+p[2])/2,(p[1]+p[3])/2
        rad=max(p[2]-p[0],p[3]-p[1])*.62
        t=np.sqrt((xx-cx)**2+(yy-cy)**2)/rad
        a=np.clip((t-.62)/.38,0,1)*INNER_DARK_A*clip
        base=np.asarray(self.img,dtype=np.float32); al=(a/255.0)[...,None]
        base[...,:3]=base[...,:3]*(1-al)
        self.img=Image.fromarray(np.clip(base,0,255).astype(np.uint8),'RGBA')
        self.d=ImageDraw.Draw(self.img)
        return p
    def text(self,xy,s,size=16,color=TEXT,bold=False,anchor="la"):
        self.d.text(xy,s,font=font(size,bold),fill=color+(255,),anchor=anchor)
    def rect(self,box,color,radius=0):
        self.d.rounded_rectangle(box,radius=radius,fill=color+(255,) if len(color)==3 else color)

def vgrad(cv,rect,radius,stops):
    """播放界面的竖向渐变（透明 → 60% 黑 → 90% 黑），对应 TvStyle.panel()。"""
    x0,y0,x1,y1=rect; h=max(int(y1-y0),1)
    g=Image.new('RGBA',(cv.w,cv.h),(0,0,0,0)); d=ImageDraw.Draw(g)
    for i in range(h):
        t=i/(h-1)
        seg=min(int(t*(len(stops)-1)),len(stops)-2); f=t*(len(stops)-1)-seg
        a,b=stops[seg],stops[seg+1]
        c=tuple(int(a[k]+(b[k]-a[k])*f) for k in range(3))
        al=int(a[3]+(b[3]-a[3])*f)
        d.line([(x0,y0+i),(x1,y0+i)],fill=c+(al,))
    m=cv.mask(rect,radius)
    g.putalpha(Image.composite(g.getchannel('A'),Image.new('L',(cv.w,cv.h),0),m))
    cv.comp(g)

def blend(a,color,over=SURFACE):
    return tuple(int(color[i]*a/255+over[i]*(1-a/255)) for i in range(3))

def on_bg(a): return blend(a,TEXT,BACKGROUND)       # 底色上的 80% / 65% / 40% 白
def on_surface(a): return blend(a,TEXT,SURFACE)     # 凸起面上的同级文字
def on_well(a): return blend(a,TEXT,WELL)

def poster_card(cv,rect,focused=False,progress=None):
    p=cv.plate(rect,RADIUS_CARD,BAND,SURFACE_HIGH+(255,) if focused else SURFACE+(255,),
               depth=1.35 if focused else 1.0,
               glow=LIT_GLOW if focused else None)
    inner=14
    pr=(p[0]+inner,p[1]+inner+2,p[2]-inner,p[1]+inner+2+(p[2]-p[0]-2*inner)*4/3)
    cv.flat(pr,RADIUS_POSTER,(0x3A,0x3D,0x44))
    cv.shade(pr,RADIUS_POSTER,0,None)
    if progress is not None:
        cv.rect((pr[0],pr[3]-4,pr[0]+(pr[2]-pr[0])*progress/100,pr[3]),ACCENT)
    cv.text((p[0]+9,pr[3]+8),"这是剧名标题一行",18,TEXT if focused else on_surface(TEXT_DIM_A),True)
    cv.text((p[0]+9,pr[3]+34),"第 12 集 · 03:41",15,on_bg(MUTED_A))
    return p

def button(cv,rect,label,focused=False,selected=False,primary=False,disabled=False,size=16):
    pill=RADIUS_PILL
    if focused:
        cv.plate(rect,pill,BAND,LIT+(255,),depth=1.2,glow=LIT_GLOW)
        col=ON_LIT
    elif disabled:
        cv.plate(rect,pill,BAND,WELL+(255,),depth=.45); col=(0x8A,0x8C,0x92)
    elif selected:
        cv.inset(rect,pill,BAND,SELECTED_WELL+(255,)); col=TEXT
    elif primary:
        cv.plate(rect,pill,BAND,LIT+(255,),depth=1.1); col=ON_LIT
    else:
        cv.plate(rect,pill,BAND,SURFACE+(255,)); col=TEXT
    x0,y0,x1,y1=rect
    cv.text(((x0+x1)/2,(y0+y1)/2),label,size,col,True,"mm")

# ============ 首页 ============
W,H=1920,1080
cv=Canvas(W,H,BACKGROUND)
INSET=78; BLEED=7
cont=(INSET-BLEED,W-INSET+BLEED)          # 页面容器 71..1849
# —— 工具条（凸起面板，内容再让出 BAND）——
# 面板外沿与内容线 INSET(78) 齐平，与下面的分区标题、海报栅格同一条竖线；
# 高度 TOOLBAR_HEIGHT(88)，面板本体 76，内容再内缩 SOFT_BAND+GAP(34)
top=(INSET-BAND,12,W-INSET+BAND,12+88)
tp=cv.plate(top,RADIUS_DIALOG,BAND,SURFACE+(255,),depth=.8)
cv.text((tp[0]+BAND+28,tp[1]+38),"HGTV",30,on_bg(TEXT_STRONG_A),True,"lm")
for i,(active,focused) in enumerate([(True,False),(False,False),(False,True)]):
    r=(tp[2]-BAND-28-58-i*74,tp[1]+8,tp[2]-BAND-28-i*74,tp[1]+68)
    pill=RADIUS_PILL
    if focused: cv.plate(r,pill,BAND,LIT+(255,),depth=1.2,glow=LIT_GLOW)
    elif active: cv.plate(r,pill,BAND,SELECTED+(255,),depth=1.3)
    else: cv.plate(r,pill,BAND,SURFACE+(255,))
    cx,cy=(r[0]+r[2])/2,(r[1]+r[3])/2
    cv.rect((cx-9,cy-9,cx+9,cy+9),ON_LIT if focused else TEXT,4)
# —— 分区标题 ——
# 工具条下面不再有信息条：剧架直接跟在页头之后（滚动容器的 12dp 上边距 + 标题行 18dp 上内边距）
hy=112
line=cont[0]+BLEED                       # 内容线 78：标题与下面的卡片共用
# 标题前不再有强调色圆点（2026-10-12）：标题只留字，节奏交给行距。
cv.text((line,hy+20),"热门推荐",24,on_bg(TEXT_STRONG_A),True,"lm")
# 标题行不再挂「更多 ›」文字入口：剧架行末的「查看全部」底板就是入口。
# —— 剧架：6 张海报卡 ——
row=(cont[0]+BLEED+2,hy+56,cont[1]-BLEED-2,hy+56)
gap=10; cw=(row[2]-row[0]-gap*5)//6
for i in range(6):
    x0=row[0]+i*(cw+gap)
    cv.plate((x0,row[1],x0+cw,row[1]+2*(BAND)+4+ (cw-2*BAND-8)*4/3 + 66),RADIUS_CARD,BAND,SURFACE+(255,))
for i in range(6):
    x0=row[0]+i*(cw+gap)
    poster_card(cv,(x0,row[1],x0+cw,row[1]+2*(BAND)+4+ (cw-2*BAND-8)*4/3 + 66),focused=(i==1),progress=(38 if i==0 else None))

cv.img.convert('RGB').save(os.path.join(OUT,'home.png'))
# ============ 组件总览 ============
W,H=1920,1420
cv=Canvas(W,H,BACKGROUND)
def label(x,y,s): cv.text((x,y),s,15,on_bg(MUTED_A))
# 按钮六态
y=60; x=120
for name,kw in [("常态",{}),("选中",{"selected":True}),("主操作",{"primary":True}),
                ("焦点",{"focused":True}),("禁用",{"disabled":True}),("焦点+选中",{"focused":True,"selected":True})]:
    cv.plate((x,y,x+260,y+58),RADIUS_PILL,BAND,WELL+(255,),depth=.35)   # 占位，下面覆盖
    button(cv,(x,y,x+260,y+58),"继续观看",**kw)
    label(x,y+70,name); x+=300
# 圆形图标入口
x=120; y=220
for name,kw in [("常态",{}),("当前页",{"active":True}),("焦点",{"focused":True})]:
    r=(x,y,x+58,y+58); pill=RADIUS_PILL
    if kw.get("focused"): cv.plate(r,pill,BAND,LIT+(255,),depth=1.2,glow=LIT_GLOW)
    elif kw.get("active"): cv.plate(r,pill,BAND,SELECTED+(255,),depth=1.3)
    else: cv.plate(r,pill,BAND,SURFACE+(255,))
    cx,cy=(r[0]+r[2])/2,(r[1]+r[3])/2
    cv.rect((cx-9,cy-9,cx+9,cy+9),ON_LIT if kw.get("focused") else TEXT,4)
    label(x,y+70,name); x+=110
# 选集网格
x0,y0=120,340
cv.inset((x0,y0,x0+1000,y0+206),RADIUS_DIALOG,BAND_LARGE,WELL+(255,),ring=TEXT+(OUTLINE_A,))
cv.text((x0+58,y0+18+16),"选集",20,on_bg(TEXT_STRONG_A),True,"lm")
cv.text((x0+120,y0+18+16),"正在看第 12 集 · 共 86 集",14,on_bg(MUTED_A),False,"lm")
cv.text((x0+58,y0+52),"第 1—20 集",14,on_bg(MUTED_A),False,"lm")
for r in range(2):
    for c in range(10):
        i=r*10+c+1
        cx=x0+58+c*88; cy=y0+76+r*62
        cell=(cx,cy,cx+84,cy+58)
        # 12 = 当前集（未聚焦：常亮的亮面板，和主操作同一档），13 = 焦点态，两个状态并排可比
        focused=(i==13); current=(i==12)
        if focused: cv.plate(cell,RADIUS_CARD,BAND,LIT+(255,),depth=1.2,glow=LIT_GLOW)
        elif current: cv.plate(cell,RADIUS_CARD,BAND,LIT+(255,),depth=1.15)
        else: cv.plate(cell,RADIUS_CARD,BAND,SURFACE+(255,))
        col=ON_LIT if (focused or current) else TEXT
        cv.text(((cell[0]+cell[2])/2,(cell[1]+cell[3])/2),str(i),16,col,True,"mm")
# 弹窗
dx,dy=120,600
cv.plate((dx,dy,dx+900,dy+330),RADIUS_DIALOG,BAND_LARGE,SURFACE_HIGH+(255,),depth=1.3)
cv.text((dx+BAND_LARGE+24,dy+BAND_LARGE+30),"删除这条记录",22,on_bg(TEXT_STRONG_A),True)
cv.text((dx+BAND_LARGE+24,dy+BAND_LARGE+80),"删除《废物垫底皇子》的本机观看进度？收藏会保留。",16,on_bg(TEXT_DIM_A))
button(cv,(dx+900-BAND_LARGE-24-120,dy+330-BAND_LARGE-24-58,dx+900-BAND_LARGE-24,dy+330-BAND_LARGE-24),"取消")
button(cv,(dx+900-BAND_LARGE-24-120-12-160,dy+330-BAND_LARGE-24-58,dx+900-BAND_LARGE-24-120-12,dy+330-BAND_LARGE-24),"确认",focused=True,primary=True)
# 播放设置：播放界面里的浮层，底座是原来的三段黑渐变，选项按钮仍是全站统一的胶囊按钮
px,py=1080,600
cv.flat((px,py,px+760,py+300),RADIUS_DIALOG,(0x0A,0x0B,0x0F))
vgrad(cv,(px,py,px+760,py+300),RADIUS_DIALOG,[(0,0,0,0),(0,0,0,0x99),(0,0,0,0xE0)])
cv.text((px+30,py+34),"播放设置",20,TEXT,True)
for i,(t,sel) in enumerate([("清晰度","720P"),("倍速","1.25×"),("画面模式","完整画面"),("定时","关闭")]):
    ry=py+70+i*56
    cv.text((px+30,ry+25),t,17,TEXT,True,"lm")
    for j,o in enumerate(["480P","720P","1080P"] if i==0 else ["1×","1.25×","1.5×"]):
        bx=px+140+j*180
        r=(bx,ry,bx+170,ry+50)
        # 选中 = 按进去（与全站一致），焦点 = 打亮；播放设置面板里不再有强调色填充。
        if sel==o and i<2: cv.inset(r,RADIUS_PILL,BAND,SELECTED_WELL+(255,)); col=TEXT
        else: cv.plate(r,RADIUS_PILL,BAND,SURFACE+(255,)); col=TEXT
        cv.text(((r[0]+r[2])/2,(r[1]+r[3])/2),o,15,col,True,"mm")

# 播放界面：标题条 + 控制层 + 进度条，全部保持原样（无柔光底板）
cv.text((120,970),"播放界面（标题条 / 控制层 / 进度条）保持原样，不套新拟态",18,on_bg(TEXT_STRONG_A),True)
video=(120,1010,1800,1400)
cv.flat(video,0,(0x2C,0x2E,0x33))                      # 占位画面
vgrad(cv,(video[0],video[1],video[2],video[1]+96),0,[(0,0,0,0x80),(0,0,0,0)])   # 顶部标题条
cv.text((video[0]+78,video[1]+48),"废物垫底皇子，反倒成为千古一帝第二季  ·  第 12 集",24,TEXT,True,"lm")
cv.text((video[2]-78,video[1]+48),"21:36",24,TEXT,False,"rm")
hud=(video[0],video[3]-200,video[2],video[3])
vgrad(cv,hud,0,[(0,0,0,0),(0,0,0,0xA6),(0,0,0,0xF0)])                          # 底部控制层
cv.rect((hud[0]+78,hud[1]+70,hud[0]+78+43,hud[1]+70+43),(0,0,0,0),21)
cv.rect((hud[0]+86,hud[1]+78,hud[0]+106,hud[1]+98),(0xFF,0xFF,0xFF),3)          # 播放键（占位三角）
tb=(hud[0]+134,hud[1]+88,hud[2]-78-170,hud[1]+88+4)
cv.rect((tb[0],tb[1],tb[2],tb[3]),(0xF2,0xF4,0xF9,OUTLINE_A),2)                 # 4dp 轨道 18% 白
cv.rect((tb[0],tb[1],tb[0]+(tb[2]-tb[0])*0.38,tb[3]),(0xF2,0xF4,0xF9),2)        # 已播白
cv.rect((tb[0]+(tb[2]-tb[0])*0.38-5,hud[1]+85,tb[0]+(tb[2]-tb[0])*0.38+5,hud[1]+95),(0xF2,0xF4,0xF9),5)
cv.text((hud[2]-78,hud[1]+90),"00:38:12 / 01:42:30",18,TEXT,False,"rm")
for i,t in enumerate(["清晰度 720P","倍速 1.25×","画面模式 完整画面","定时 关闭"]):
    cv.text((hud[0]+78+i*250,hud[1]+140),t,20,TEXT,False,"lm")
cv.text((hud[2]-78,hud[1]+168),"⌄",22,on_bg(MUTED_A),False,"rm")

cv.img.convert('RGB').save(os.path.join(OUT,'sheet.png'))

# ============ 强调色候选（2026-10-07 第二次色板改版的依据）============
# 同一块石墨材质上并排比 6 支强调色。第三次改版之后强调色**只做线与小色块**
# （进度条、名次星标），不再画任何选框，因此每列只比「它作为一条线好不好读」：
# 海报卡上的进度条、状态行文字、以及提示色的小方块（区块标题的圆点已于 2026-10-12 去掉）。
# 第一列是上一版的品牌蓝——它当年被用来画焦点环，是全屏唯一一块高饱和色，
# 贴在中性石墨上就是「和整体风格不搭」的来源；现在它作为一条进度条仍然太吵。
# 高饱和琥珀会和提示黄撞车，雾青偏「医用」，雾银没有彩色信号，最后留下第二列香槟金。
# 以后再动色板，先重跑这张图，比在真机上逐个改值快得多。
CANDIDATES=[
    ("品牌蓝（上一版）",(0x59,0x80,0xFF),"#5980FF"),
    ("香槟金（采用）",(0xDF,0xB7,0x7C),"#DFB77C"),
    ("暖铜 / 玫瑰铜",(0xDE,0x9E,0x77),"#DE9E77"),
    ("琥珀（高饱和）",(0xE8,0xA3,0x3D),"#E8A33D"),
    ("雾青 / 冷杉",(0x86,0xC4,0xB4),"#86C4B4"),
    ("雾银（近乎无彩）",(0xC9,0xD3,0xE2),"#C9D3E2"),
]
CW=380; PADX=28; TOP=110
cv=Canvas(PADX*2+CW*len(CANDIDATES),900,BACKGROUND)
for i,(name,acc,hexv) in enumerate(CANDIDATES):
    x0=PADX+i*CW; x1=x0+CW-28
    cv.text((x0,34),name,22,TEXT,True)
    cv.text((x0,64),hexv,16,on_bg(MUTED_A))
    # 卡片高度按海报比例反推（4:3 竖版 + 一行剧名），否则海报会顶出卡片压到下面的说明行。
    r=(x0,TOP,x1,TOP+440)
    # 海报卡：焦点态（抬一档 + 光晕，无细环），进度条用候选强调色。
    cv.plate(r,RADIUS_CARD,BAND,SURFACE_HIGH+(255,),depth=1.35,glow=LIT_GLOW)
    p=(r[0]+BAND,r[1]+BAND,r[2]-BAND,r[3]-BAND); inner=14
    pr=(p[0]+inner,p[1]+inner+2,p[2]-inner,p[1]+inner+2+(p[2]-p[0]-2*inner)*4/3)
    cv.flat(pr,RADIUS_POSTER,(0x3A,0x3D,0x44)); cv.shade(pr,RADIUS_POSTER,0,None)
    cv.rect((pr[0],pr[3]-4,pr[0]+(pr[2]-pr[0])*0.62,pr[3]),acc)
    cv.text((p[0]+9,pr[3]+10),"这是剧名标题一行",18,TEXT,True)
    cv.text((p[0]+9,pr[3]+36),"第 12 集 · 03:41",15,on_surface(MUTED_A))
    y=TOP+520
    # 选中态不再用强调色：按进去的胶囊，文字是正文色。
    r=(x0,y,x0+170,y+60)
    cv.inset(r,RADIUS_PILL,BAND,SELECTED_WELL+(255,))
    cv.text(((r[0]+r[2])/2,(r[1]+r[3])/2),"720P",16,TEXT,True,"mm")
    r=(x0+186,y,x1,y+60)
    cv.plate(r,RADIUS_PILL,BAND,LIT+(255,),depth=1.2,glow=LIT_GLOW)
    cv.text(((r[0]+r[2])/2,(r[1]+r[3])/2),"焦点",16,ON_LIT,True,"mm")
    y+=90
    # 强调色只剩两处线与小色块：进度条、状态行文字（区块标题的圆点已于 2026-10-12 去掉）。
    cv.rect((x0,y,x1,y+8),WELL,4)
    cv.rect((x0,y,x0+(x1-x0)*0.62,y+8),acc,4)
    cv.text((x0,y+37),"热门推荐",20,on_bg(TEXT_STRONG_A),True,"lm")
    y+=74
    cv.text((x0,y),"进度条 / 文字 两处",15,on_bg(MUTED_A))
    y+=40
    cv.rect((x0,y,x0+18,y+18),(0xFF,0xC2,0x4B),3)
    cv.text((x0+28,y+9),"提示黄 #FFC24B（评分 / 榜单）",16,(0xFF,0xC2,0x4B),False,"lm")
cv.img.convert('RGB').save(os.path.join(OUT,'palette.png'))

print('written to', OUT)
