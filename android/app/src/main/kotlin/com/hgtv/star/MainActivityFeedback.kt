// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import com.hgtv.star.compat.setVectorIcon
import com.hgtv.star.compat.vectorDrawable
import com.hgtv.star.list.showCatalog
import com.hgtv.star.tv.SoftStyle
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.designPx

// 「问题反馈」整页：顶部导航栏最右那枚圆形按钮（「我的」后面）进来的一页。
// 电视上没有浏览器、也没有输入法，因此三条渠道只用二维码给手机扫（见 [FeedbackChannel]）。

/**
 * 三条反馈渠道。
 *
 * 二维码的配色与点形完全照设计稿：B站粉的圆点、QQ 的蓝色渐变圆点、GitHub 的白点，
 * 三块码的中心各嵌一枚自己的品牌标（[logo]）。[brandEnd] 非零时码点走线性渐变。
 */
internal enum class FeedbackChannel(
    val label: String,
    val detail: String,
    val url: String,
    val icon: Int,
    val brand: Int,
    val brandEnd: Int,
    val dots: Boolean
) {
    BILIBILI("B站", "视频演示与更新", "https://space.bilibili.com/350299266",
        R.drawable.ic_brand_bilibili, 0xFFFB7299.toInt(), 0, false),
    QQ("QQ 群", "红果TV交流群", "https://qm.qq.com/q/axlRuXng1G",
        R.drawable.ic_brand_qq, 0xFF12B7F5.toInt(), 0xFF0078D7.toInt(), true),
    GITHUB("GitHub", "源码与问题反馈", "https://github.com/pan8664716/hgtv",
        R.drawable.ic_brand_github, 0xFFFFFFFF.toInt(), 0, false)
}

/** 三张卡片之间的间距（设计 dp）。 */
private const val FEEDBACK_CARD_GAP = 28

/** 二维码凹槽的边长（设计 dp，含柔光带）。 */
private const val FEEDBACK_QR_SIDE = 250

/** 凹槽里的内边距：二维码的静区（设计 dp）。 */
private const val FEEDBACK_QR_INSET = 10

/** 卡片顶部圆形徽标的可见直径（设计 dp）。 */
private const val FEEDBACK_BADGE = 76

/**
 * 打开「问题反馈」整页（导航栏最右那枚圆形按钮进来）。
 *
 * 它盖在任何一页之上，但**不改 [tab]**：关掉之后用户回到原来那一页（返回键走 [closeFeedbackPage]）。
 *
 * 已经在这一页时再点一次**什么都不做**，与其余圆形入口一致——那些入口点当前页只是重建同一页，
 * 不会「退出去」。早先这里是「再点一次就关」，于是连按两下按钮页面就没了，
 * 用户读到的就是「反馈按钮点两下会自己退出」。这里只把焦点交回第一张卡片。
 */
internal fun MainActivity.openFeedbackPage() {
    if(feedbackOpen) {
        main.post { feedbackCards.firstOrNull()?.requestFocus() }
        return
    }
    feedbackOpen=true
    // 落点在第一张卡片：用户按下这枚按钮就是想看这三块码，方向键左右一扫就行。
    showCatalog()
}

/**
 * 关掉「问题反馈」页，回到进来之前那一页（[tab] 一直没变，重建即可）。
 *
 * 焦点交回导航栏最右那枚圆形按钮：用户刚按的就是它，返回之后停在原地才连贯。
 */
internal fun MainActivity.closeFeedbackPage() {
    if(!feedbackOpen) return
    feedbackOpen=false
    showCatalog()
    main.post { nav.lastOrNull()?.requestFocus() }
}

/**
 * 「问题反馈」页的正文：三张同宽卡片并排，每张都是「圆形徽标 → 二维码凹槽 → 名称 → 一行说明」。
 *
 * 版式照设计稿：卡片是凸起的大圆角面板，徽标与二维码都是**压进卡片**的凹槽，
 * 三张卡片等宽等高、间距相同——电视上退开两米看，三块码的边长一致才扫得舒服。
 *
 * 返回三张卡片，调用方用它接导航栏的「按下」与卡片行「按上」。
 */
internal fun MainActivity.feedbackPage(container: LinearLayout): List<View> {
    val gap=dp(FEEDBACK_CARD_GAP)
    val cardWidth=((gridContentWidth()-gap*2)/3).coerceAtLeast(dp(240))

    val stage=FrameLayout(this).apply { clipChildren=false; clipToPadding=false }
    container.addView(stage,lp(-1,0).apply { weight=1f })

    val column=column().apply { gravity=Gravity.CENTER_HORIZONTAL; clipChildren=false; clipToPadding=false }
    stage.addView(column,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER))

    column.addView(
        text("用手机相机或微信扫下面的码，三条渠道都能直接找到我们。",14f,TvStyle.muted)
            .apply { gravity=Gravity.CENTER },
        lp(-1,-2)
    )

    val cards=row().apply {
        gravity=Gravity.CENTER
        clipChildren=false; clipToPadding=false
    }
    column.addView(cards,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })

    feedbackCards=emptyList()
    val views=FeedbackChannel.entries.mapIndexed { index, channel ->
        val card=feedbackCard(channel)
        cards.addView(card,lp(cardWidth,-2).apply { if(index>0) leftMargin=gap })
        card
    }
    // 卡片之间左右相连，两端停住；按上回导航栏那一排圆形入口（由调用方接，见 ListPage）。
    views.forEachIndexed { index, card ->
        card.nextFocusLeftId=(views.getOrNull(index-1) ?: card).id
        card.nextFocusRightId=(views.getOrNull(index+1) ?: card).id
        card.nextFocusDownId=card.id
    }
    feedbackCards=views
    return views
}

/**
 * 一张反馈卡片：圆形徽标 + 二维码凹槽 + 名称 + 一行说明。
 *
 * 卡片本身可聚焦（电视上不给可聚焦内容，焦点会一直停在导航栏里，页面看起来像「死的」）。
 * 聚焦时**只加焦点环与一层柔光**，不改底色：卡片里是彩色码点与浅色字，换成亮面会让整块糊掉。
 * 按确认把链接复制到剪贴板——扫码是主路径，复制是电视上唯一还走得通的备用路径。
 */
private fun MainActivity.feedbackCard(channel: FeedbackChannel): LinearLayout {
    val band=dp(TvStyle.SOFT_BAND_LARGE)
    val pad=dp(26)+band
    val plate=TvStyle.soft(
        this,TvStyle.surfaceHigh,TvStyle.RADIUS_DIALOG,TvStyle.SOFT_BAND_LARGE,SoftStyle.RAISED,depth=1.05f
    )
    val card=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        gravity=Gravity.CENTER_HORIZONTAL
        clipChildren=false; clipToPadding=false
        setPadding(pad,pad,pad,pad)
        background=plate
        isFocusable=true
        isFocusableInTouchMode=true
        id=View.generateViewId()
        contentDescription="${channel.label} 二维码，${channel.detail}"
    }

    // 圆形徽标：**压进卡片**的凹槽。内边距与柔光带同宽，内阴影才有地方画；
    // 盘面取 wellDeep（比二维码凹槽再深一档），凹陷在深色主题上才看得出来。
    val badgeBand=dp(TvStyle.GAP_TIGHT)
    val badgeSide=dp(FEEDBACK_BADGE)+badgeBand*2
    val badge=FrameLayout(this).apply {
        clipChildren=false; clipToPadding=false
        setPadding(badgeBand,badgeBand,badgeBand,badgeBand)
        background=TvStyle.soft(
            this@feedbackCard,TvStyle.wellDeep,TvStyle.RADIUS_PILL,TvStyle.GAP_TIGHT,SoftStyle.INSET,depth=1.6f
        )
    }
    badge.addView(
        ImageView(this).apply {
            setVectorIcon(channel.icon)
            setColorFilter(channel.brand)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },
        FrameLayout.LayoutParams(dp(34),dp(34),Gravity.CENTER)
    )
    card.addView(badge,lp(badgeSide,badgeSide))

    // 二维码凹槽：与徽标同一种「压进去」的材质，码点直接画在槽面上（没有白板）。
    // 设计稿里二维码就是透明的，底色来自卡片；这里把卡片换成凹槽，层次更清楚、也不刺眼。
    val qrBand=dp(TvStyle.GAP_TIGHT)
    val qrSide=dp(FEEDBACK_QR_SIDE)
    val well=FrameLayout(this).apply {
        clipChildren=false; clipToPadding=false
        setPadding(qrBand+dp(FEEDBACK_QR_INSET),qrBand+dp(FEEDBACK_QR_INSET),
            qrBand+dp(FEEDBACK_QR_INSET),qrBand+dp(FEEDBACK_QR_INSET))
        background=TvStyle.soft(
            this@feedbackCard,TvStyle.well,TvStyle.RADIUS_DIALOG,TvStyle.GAP_TIGHT,SoftStyle.INSET,depth=1.35f
        )
    }
    well.addView(
        FeedbackQrView(this,channel.url,channel.brand,channel.brandEnd,channel.icon,channel.dots),
        FrameLayout.LayoutParams(-1,-1)
    )
    card.addView(well,lp(qrSide,qrSide).apply { topMargin=dp(TvStyle.GAP_BLOCK) })

    card.addView(
        text(channel.label,20f,TvStyle.text).apply {
            setTypeface(null,Typeface.BOLD)
            gravity=Gravity.CENTER
        },
        lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) }
    )
    card.addView(
        text(channel.detail,13f,TvStyle.muted).apply {
            gravity=Gravity.CENTER
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
        },
        lp(-1,-2).apply { topMargin=dp(3) }
    )

    card.setOnFocusChangeListener { view, focused ->
        plate.restyle(
            ring=if(focused) TvStyle.focusRing else Color.TRANSPARENT,
            ringWidth=if(focused) dp(2).toFloat() else 0f,
            glow=if(focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth=if(focused) 1.15f else 1.05f
        )
        TvStyle.focus(view,focused)
    }
    card.setOnClickListener {
        val clipboard=getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        if(clipboard==null) { showHint("没有可用的剪贴板"); return@setOnClickListener }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(channel.label,channel.url))
        showHint("已复制 ${channel.label} 链接，可在手机浏览器打开")
    }
    return card
}

/**
 * 按设计稿画的二维码：彩色码点 + 圆角定位角 + 中心品牌标。
 *
 * 为什么不直接用 ZXing 的位图：设计稿里码点是有形状、有颜色的（B站粉圆点、QQ 蓝色渐变、
 * GitHub 白点），定位角是「圆角方框 + 圆点」，中心还嵌着品牌标。ZXing 只给黑白方格，
 * 铺上去就是一块黑白砖。这里取它的**矩阵**，自己按格画。
 *
 * 纠错级别用 H（30%）：中心那块品牌标会挖掉一片码点，只有 H 还扫得稳。
 */
private class FeedbackQrView(
    context: Context,
    url: String,
    private val start: Int,
    private val end: Int,
    private val logo: Int,
    private val dots: Boolean
) : View(context) {

    /** 码矩阵：`get(x,y) == 1` 表示深色格。 */
    private val matrix: ByteMatrix = Encoder.encode(url,ErrorCorrectionLevel.H).matrix
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val rect=RectF()
    private val corners=FloatArray(8)
    private val logoDrawable=context.vectorDrawable(logo)
    private val dp=context.designPx(1)

    private fun dark(x: Int,y: Int): Boolean =
        x in 0 until matrix.width && y in 0 until matrix.height && matrix.get(x,y).toInt()==1

    /** 三个定位角（7×7 的方块）在矩阵里的左上角；这些格单独画成圆角方框。 */
    private fun finderOrigin(x: Int,y: Int,n: Int): Boolean =
        (x<7 && y<7) || (x>=n-7 && y<7) || (x<7 && y>=n-7)

    override fun onDraw(canvas: Canvas) {
        val n=matrix.width
        if(n<=0 || width<=0 || height<=0) return
        // 静区 2 格：贴着凹槽边也能扫。
        val quiet=2
        val cell=minOf(width,height).toFloat()/(n+quiet*2)
        val ox=(width-cell*n)/2f
        val oy=(height-cell*n)/2f

        paint.style=Paint.Style.FILL
        paint.shader=LinearGradient(
            ox,oy,ox+cell*n,oy+cell*n,
            start,if(end==0) start else end,Shader.TileMode.CLAMP
        )

        // 中心品牌标挖掉的那一片：边长约 5 格（占码面 1/6），H 级纠错兜得住。
        val logoModules=5f
        val logoLeft=(n-logoModules)/2f
        val logoTop=logoLeft
        fun inLogo(x: Int,y: Int)=x>=logoLeft && x<logoLeft+logoModules && y>=logoTop && y<logoTop+logoModules

        for(y in 0 until n) for(x in 0 until n) {
            if(!dark(x,y) || finderOrigin(x,y,n) || inLogo(x,y)) continue
            val left=ox+x*cell
            val top=oy+y*cell
            if(dots) {
                canvas.drawCircle(left+cell/2f,top+cell/2f,cell*.42f,paint)
            } else {
                // 「圆点」样式：外露的角才收圆，与相邻格连成一片——这正是设计稿里那种圆润的块。
                val r=cell*.5f
                corners[0]=if(!dark(x-1,y) && !dark(x,y-1)) r else 0f
                corners[1]=if(!dark(x+1,y) && !dark(x,y-1)) r else 0f
                corners[2]=if(!dark(x+1,y) && !dark(x,y+1)) r else 0f
                corners[3]=if(!dark(x-1,y) && !dark(x,y+1)) r else 0f
                corners[4]=corners[0]; corners[5]=corners[1]; corners[6]=corners[2]; corners[7]=corners[3]
                path.reset()
                rect.set(left,top,left+cell,top+cell)
                path.addRoundRect(rect,corners,Path.Direction.CW)
                canvas.drawPath(path,paint)
            }
        }

        // 定位角：外圈圆角方框（线宽一格）+ 中心圆点，和设计稿的 extra-rounded / dot 一致。
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=cell
        paint.strokeJoin=Paint.Join.ROUND
        listOf(0 to 0, n-7 to 0, 0 to n-7).forEach { (fx,fy) ->
            rect.set(
                ox+fx*cell+cell/2f,oy+fy*cell+cell/2f,
                ox+(fx+7)*cell-cell/2f,oy+(fy+7)*cell-cell/2f
            )
            canvas.drawRoundRect(rect,cell*2.2f,cell*2.2f,paint)
        }
        paint.style=Paint.Style.FILL
        listOf(0 to 0, n-7 to 0, 0 to n-7).forEach { (fx,fy) ->
            rect.set(
                ox+(fx+2)*cell,oy+(fy+2)*cell,
                ox+(fx+5)*cell,oy+(fy+5)*cell
            )
            canvas.drawRoundRect(rect,cell,cell,paint)
        }

        // 中心品牌标：设计稿里它是**透明的**（直接压在码面上），这里同样不垫白板。
        paint.shader=null
        logoDrawable?.let { drawable ->
            val side=(cell*logoModules*.62f).toInt().coerceAtLeast(16*dp)
            val left=(width-side)/2
            val top=(height-side)/2
            drawable.colorFilter=PorterDuffColorFilter(start,PorterDuff.Mode.SRC_IN)
            drawable.setBounds(left,top,left+side,top+side)
            drawable.draw(canvas)
        }
    }
}
