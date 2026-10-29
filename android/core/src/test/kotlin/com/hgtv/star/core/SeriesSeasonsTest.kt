// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesSeasonsTest {
    @Test fun `strips season suffix and reads the season number`() {
        assertEquals("聚宝仙盆之杂灵根才是真BOSS", SeriesSeasons.base("聚宝仙盆之杂灵根才是真BOSS第十三季"))
        assertEquals(13, SeriesSeasons.season("聚宝仙盆之杂灵根才是真BOSS第十三季"))
        assertEquals("聚宝仙盆之杂灵根才是真BOSS", SeriesSeasons.base("聚宝仙盆之杂灵根才是真BOSS第13季"))
        assertEquals(13, SeriesSeasons.season("聚宝仙盆之杂灵根才是真BOSS第 13 季"))
        assertEquals(3, SeriesSeasons.season("聚宝仙盆之杂灵根才是真BOSS 3季"))
        assertEquals(2, SeriesSeasons.season("某某剧 S2"))
        assertEquals(5, SeriesSeasons.season("某某剧 Season 5"))
        assertEquals(20, SeriesSeasons.season("某某剧第二十季"))
        assertEquals(30, SeriesSeasons.season("某某剧第三十季"))
        assertEquals(1, SeriesSeasons.season("某某剧第一季"))
    }

    @Test fun `a title without a season suffix is the first season`() {
        val parsed = SeriesSeasons.parse("聚宝仙盆")
        assertEquals("聚宝仙盆", parsed.base)
        assertEquals(1, parsed.season)
        assertFalse(parsed.matched)
        // 「集」不是「季」：带集数的剧名不能被当成季号。
        assertEquals("全99集", SeriesSeasons.base("全99集"))
        assertEquals(1, SeriesSeasons.season("某某剧第0季"))   // 0 不是合法季号 → 按第 1 季
        assertEquals(1, SeriesSeasons.season("某某剧第100季")) // 超过两位数同样不认
    }

    @Test fun `chinese numbers cover the whole one and two digit range`() {
        assertEquals(1, SeriesSeasons.chineseNumber("一"))
        assertEquals(10, SeriesSeasons.chineseNumber("十"))
        assertEquals(13, SeriesSeasons.chineseNumber("十三"))
        assertEquals(20, SeriesSeasons.chineseNumber("二十"))
        assertEquals(25, SeriesSeasons.chineseNumber("二十五"))
        assertEquals(99, SeriesSeasons.chineseNumber("九十九"))
        assertEquals(null, SeriesSeasons.chineseNumber("甲"))
    }

    @Test fun `season labels cover chinese and arabic forms`() {
        assertEquals(listOf("第一季","第1季"), SeriesSeasons.labels(1))
        assertEquals(listOf("第十季","第10季"), SeriesSeasons.labels(10))
        assertEquals(listOf("第十一季","第11季"), SeriesSeasons.labels(11))
        assertEquals(listOf("第二十季","第20季"), SeriesSeasons.labels(20))
        assertEquals(listOf("第二十五季","第25季"), SeriesSeasons.labels(25))
        assertEquals(listOf("第九十九季","第99季"), SeriesSeasons.labels(99))
        assertTrue(SeriesSeasons.labels(0).isEmpty())
        assertTrue(SeriesSeasons.labels(100).isEmpty())
        // 生成的标签必须能被自己的解析器认回来（同一套规则的自洽性）。
        for(season in listOf(1,9,10,11,13,20,25,99)) {
            for(label in SeriesSeasons.labels(season)) {
                assertEquals(season, SeriesSeasons.season("某某剧$label"))
                assertEquals("某某剧", SeriesSeasons.base("某某剧$label"))
            }
        }
    }

    @Test fun `normalize ignores season suffix punctuation and width`() {
        val a = SeriesSeasons.normalize("一梦青玄，团宠小师妹一剑毁天灭地第七季")
        val b = SeriesSeasons.normalize("一梦青玄,团宠小师妹一剑毁天灭地")
        val c = SeriesSeasons.normalize("一梦青玄 团宠小师妹一剑毁天灭地")
        assertEquals(b, a)
        assertEquals(b, c)
    }

    @Test fun `siblings keep only the same series and sort by season`() {
        val rows = listOf(
            Series("1", "聚宝仙盆之杂灵根才是真BOSS第十三季"),
            Series("2", "聚宝仙盆之杂灵根才是真BOSS"),
            Series("3", "聚宝仙盆之杂灵根才是真BOSS第三季"),
            Series("4", "聚宝仙盆仙界篇第十季"),            // 同一 IP 的另一个系列：不能混进来
            Series("5", "咱家剑宗团宠小师妹第五季"),
            Series("6", "聚宝仙盆之杂灵根才是真BOSS第十二季"),
            Series("7", "聚宝仙盆之杂灵根才是真BOSS第十三季")   // 与当前剧同季（另一个条目）：按季号排序时相邻
        )
        val sorted = SeriesSeasons.siblings("聚宝仙盆之杂灵根才是真BOSS第十三季", rows)
        assertEquals(listOf("2", "3", "6", "1", "7"), sorted.map { it.id })
        assertEquals(listOf(1, 3, 12, 13, 13), sorted.map { SeriesSeasons.season(it.title) })
    }

    /**
     * 季号优先用**接口给的字段**（[Series.seasonIndex]），剧名解析只当兜底。
     *
     * 接口比剧名强的地方正是「季号不在剧名末尾」这一类：
     * `聚宝仙盆第二季：灵根亿点点废，战力亿点强`、`掀桌2以身入局`、`无主之花2`
     * ——剧名解析读不出来的，接口的 `sub_title_list` / `season_index` 都有。
     */
    @Test fun `the api season index wins over the title`() {
        val plain=Series("1","帐中香")                                        // 剧名没提季号
        val second=Series("2","掀桌2以身入局",seasonIndex=2)                   // 季号在中间
        val mid=Series("3","聚宝仙盆第二季：灵根亿点点废，战力亿点强",seasonIndex=2)
        val conflicting=Series("4","某某剧第二季",seasonIndex=5)               // 两边都有：以接口为准
        assertEquals(1, SeriesSeasons.season(plain))
        assertEquals(2, SeriesSeasons.season(second))
        assertEquals(2, SeriesSeasons.season(mid))
        assertEquals(5, SeriesSeasons.season(conflicting))
        // **每张卡都标季号**：剧名里没提季号的就是第 1 季（不再返回空串）。
        //
        // 早先这里断言空串（「单季的剧不标，免得每张卡都挂第 1 季」），后来改成一律标：
        // 同一部剧的第一季与第二季并排出现时，空角标那张读不出季数，用户分不清。
        assertEquals("第 1 季", SeriesSeasons.badge(plain))
        assertEquals("第 2 季", SeriesSeasons.badge(second))
        assertEquals("第 2 季", SeriesSeasons.badge(mid))
        assertEquals("第 5 季", SeriesSeasons.badge(conflicting))
        // 剧名里写了季号但接口没给（搜索 / 提示词那两条接口没有季号字段）：仍按剧名标。
        assertEquals("第 3 季", SeriesSeasons.badge(Series("5","宠妻无度第三季")))
        assertEquals("第 1 季", SeriesSeasons.badge(Series("6","宠妻无度第一季")))
        // 接口明确给 1（分类 / 榜单对单季的剧都会写「第1季」）也照标。
        assertEquals("第 1 季", SeriesSeasons.badge(Series("7","帐中香",seasonIndex=1)))
        // 接口给 0 / 负数等于没给：退回剧名解析，仍然是第 1 季。
        assertEquals("第 1 季", SeriesSeasons.badge(Series("8","帐中香",seasonIndex=0)))
    }

    /**
     * 季号写在**剧名中间**也要认得出是同一部剧（以前只剥尾部季号，这种会被当成别的剧）：
     * `聚宝仙盆：灵根亿点点废，战力亿点强` 与 `聚宝仙盆第二季：灵根亿点点废，战力亿点强`。
     */
    @Test fun `season tokens in the middle of a title still group as one series`() {
        val rows = listOf(
            Series("1","聚宝仙盆：灵根亿点点废，战力亿点强",seasonIndex=1),
            Series("2","聚宝仙盆第二季：灵根亿点点废，战力亿点强",seasonIndex=2),
            Series("3","聚宝仙盆第三季：灵根亿点点废，战力亿点强",seasonIndex=3),
            Series("4","聚宝仙盆仙界篇第十季",seasonIndex=10)     // 同一 IP 的另一个系列：不能混进来
        )
        val sorted = SeriesSeasons.siblings("聚宝仙盆：灵根亿点点废，战力亿点强", rows)
        assertEquals(listOf("1","2","3"), sorted.map { it.id })
    }

    /** 相关作品那条接口每行都带 `season_index`：分组内排序用它（两边不一致时以接口为准）。 */
    @Test fun `siblings sort by the api season index when it disagrees with the title`() {
        val rows = listOf(
            Series("1","聚宝仙盆第三季",seasonIndex=5),
            Series("2","聚宝仙盆",seasonIndex=1),
            Series("3","聚宝仙盆第二季",seasonIndex=2)
        )
        val sorted = SeriesSeasons.siblings("聚宝仙盆第一季", rows)
        assertEquals(listOf("2","3","1"), sorted.map { it.id })
        assertEquals(listOf(1,2,5), sorted.map { SeriesSeasons.season(it) })
    }

    @Test fun `no candidates means no siblings`() {
        assertTrue(SeriesSeasons.siblings("聚宝仙盆之杂灵根才是真BOSS第十三季", emptyList()).isEmpty())
        assertTrue(SeriesSeasons.siblings("", listOf(Series("1", "任意剧名"))).isEmpty())
    }
}
