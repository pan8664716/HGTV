// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class CategoryCatalogTest {
    /** 分类页三个大类与接口的 `genre` 一一对应；顺序就是界面上的顺序。 */
    @Test fun `three groups map to the three api genres`() {
        assertEquals(listOf("漫剧","真人","AI剧"),CategoryCatalog.groups.map { it.label })
        assertEquals(listOf("comic-drama","real-drama","ai-drama"),CategoryCatalog.groups.map { it.path })
        assertEquals(listOf("comic_series","short_play","ai_series"),CategoryCatalog.groups.map { it.genre })
    }

    /**
     * 每个大类下面的小类：第一个固定是「全部」（路径就是大类本身、没有主题标识），
     * 其余路径是 `大类/cate_*`，标识就是接口要的那个值。
     */
    @Test fun `each group lists all plus its own themed tags`() {
        CategoryCatalog.groups.forEach { group ->
            assertEquals("全部",group.tags.first().label)
            assertEquals(group.path,group.tags.first().path)
            assertNull("「全部」不带主题筛选",group.tags.first().themeId)
            group.tags.drop(1).forEach { tag ->
                assertTrue(tag.path.startsWith(group.path+"/cate_"))
                assertEquals(tag.path.removePrefix(group.path+"/"),tag.themeId)
                assertNotEquals("全部",tag.label)
            }
        }
        // 数量：漫剧 12 个题材、真人 29、AI剧 12（各外加「全部」）。
        assertEquals(listOf(13,30,13),CategoryCatalog.groups.map { it.tags.size })
    }

    /** 抽样核对中文名与标识（这几个是接口 `selector_rows` / 内容题材里真实存在的）。 */
    @Test fun `labels carry the api theme ids`() {
        val comic=CategoryCatalog.groups[0]
        assertEquals("cate_739",comic.tags.first { it.label=="逆袭" }.themeId)
        assertEquals("comic-drama/cate_7",comic.tags.first { it.label=="玄幻" }.path)
        assertEquals("cate_1021",CategoryCatalog.groups[1].tags.first { it.label=="现言" }.themeId)
        assertEquals("cate_262",CategoryCatalog.groups[1].tags.first { it.label=="脑洞" }.themeId)
        assertEquals("cate_739",CategoryCatalog.groups[2].tags.first { it.label=="逆袭" }.themeId)
    }

    /** 路径在同一份目录里唯一，否则选中的高亮会同时亮起两枚芯片。 */
    @Test fun `paths are unique inside every group`() {
        CategoryCatalog.groups.forEach { group ->
            val paths=group.tags.map { it.path }
            assertEquals(paths.size,paths.distinct().size)
        }
    }

    /** 默认选中「漫剧 · 全部」；反查与文案都按这一条走。 */
    @Test fun `default selection and describe`() {
        assertEquals("comic-drama",CategoryCatalog.defaultTag.path)
        assertEquals("漫剧 · 全部",CategoryCatalog.describe("comic-drama"))
        assertEquals("漫剧 · 玄幻",CategoryCatalog.describe("comic-drama/cate_7"))
        assertEquals("真人 · 现言",CategoryCatalog.describe("real-drama/cate_1021"))
        assertEquals(CategoryCatalog.groups[1],CategoryCatalog.groupOf("real-drama/cate_262"))
        assertNull(CategoryCatalog.groupOf("unknown"))
        // 认不出的路径原样返回，界面不会显示空文案。
        assertEquals("unknown",CategoryCatalog.describe("unknown"))
        // 「全部」没有中文小类名（`labelOf` 只在真·小类上有意义）；认不出的路径也没有。
        assertNull(CategoryCatalog.labelOf("comic-drama"))
        assertNull(CategoryCatalog.labelOf("comic-drama/cate_999999"))
        assertEquals("玄幻",CategoryCatalog.labelOf("comic-drama/cate_7"))
    }
}
