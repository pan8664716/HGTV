// 联网实测：默认跳过，只有 HONGGUO_LIVE=1 时才真的发请求。
//
// 用途：把分类页能翻到的封面全抓下来（实测三本目录共 38523 张唯一封面），
// 供「CDN 冷热尺寸」这类对照实验取**干净的样本**——深处的剧我们没请求过，
// 因此它们在 CDN 上任何模板档都是「没问过」的状态，测出来的命中率才是真的。
//
// 运行：HONGGUO_LIVE=1 ./gradlew :core:test --tests '*LiveCoverDumpTest*' --info
package com.hgtv.star.core

import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveCoverDumpTest {
    private val live = System.getenv("HONGGUO_LIVE") == "1"

    @Test fun dumpDeepCovers() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", live)
        val repo = ContentRepository()
        val seen = LinkedHashSet<String>()
        var empty = 0
        for (group in CategoryCatalog.groups) {
            for (tag in group.tags) {
                for (page in 1..30) {
                    val items = runCatching { repo.category(tag.path, page) }.getOrNull()?.items
                    if (items.isNullOrEmpty()) { empty++; break }
                    items.forEach { if (it.cover.isNotBlank()) seen += it.cover }
                }
            }
        }
        println("[cover-dump] 共 ${seen.size} 张唯一封面（空页 $empty 次）")
        seen.forEach { println("[cover-dump] $it") }
    }
}
