package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class LibraryIndexTest {
    private fun series(id: Int) = Series("$id", "剧 $id", "https://example.com/$id.jpg", "完整本机简介", "全 80 集", "剧情")
    private fun progress(id: Int, position: Long = 100, updated: Long = id.toLong()) =
        BackupProgress(series(id), "${id + 1000}", 2, position, 60_000, false, updated)
    private fun data() = BackupData((1..500).map(::series), (1..200).map { progress(it) }, setOf("2"),
        listOf(RecentSearch("测试")), BackupSettings(720, 1.5f, false, ContentType.SHORT, VideoFrameMode.FIT),
        listOf(series(3)), setOf("4"))

    @Test fun `legacy keys round trip without dropping metadata or settings`() {
        val original = data()
        val values = mapOf("favorites" to LibraryDisk.series(original.favorites), "progress" to LibraryDisk.history(original.history),
            "later" to LibraryDisk.series(original.later), "hidden" to original.hidden, "watched" to original.watched,
            "searches" to SearchHistory.encode(original.searches), "quality" to 720, "playbackSpeed" to 1.5f,
            "autoNext" to false, "contentType" to "short", "frameMode" to "FIT",
            "favoriteUpdates" to LibraryDisk.updates(mapOf("1" to FavoriteUpdate(83, 80, 10))))
        val state = LibraryDisk.decode(values)
        assertEquals(original.copy(history = original.history.sortedByDescending { it.updatedAt }), state.snapshot())
        assertEquals("完整本机简介", state.progress("1")!!.series.description)
        assertEquals(3, state.favoriteUpdate("1")!!.added)
        assertEquals(1, state.updatedFavorites())
    }

    @Test fun `repeated reads reuse snapshots and paused checkpoints do not write`() {
        val state = LibraryIndex(data())
        val history = state.history()
        val favoriteRows = state.favorites()
        repeat(1000) {
            assertSame(history, state.history())
            assertSame(favoriteRows, state.favorites())
            assertSame(history.first(), state.progress("200"))
            assertTrue(state.favorite("500"))
        }
        assertFalse(state.save(progress(200, updated = 999)))
        assertSame(history, state.history())
        assertEquals(200L, state.progress("200")!!.updatedAt)
        assertTrue(state.save(progress(200, position = 9000, updated = 1000)))
        assertEquals(100L, history.first().position)
        assertEquals(9000L, state.history().first().position)
    }

    @Test fun `history and favorite eviction remove corresponding indexes`() {
        val state = LibraryIndex(data(), (1..500).associate { "$it" to FavoriteUpdate(81, 80, 1) })
        assertEquals(500, state.updatedFavorites())
        state.save(progress(201, updated = 1000))
        assertEquals(200, state.history().size)
        assertNull(state.progress("1"))
        assertNotNull(state.progress("201"))
        assertTrue(state.toggle(series(501)))
        assertFalse(state.favorite("500"))
        assertNull(state.favoriteUpdate("500"))
        assertEquals(499, state.updatedFavorites())
        assertTrue(state.observeFavorite("1", 81, 10, true))
        assertEquals(498, state.updatedFavorites())
        assertFalse(state.toggle(series(1)))
        assertNull(state.favoriteUpdate("1"))
        assertEquals(498, state.updatedFavorites())
    }

    @Test fun `captured snapshots survive later mutations and keep backup merge semantics`() {
        val state = LibraryIndex(data(), mapOf("1" to FavoriteUpdate(81, 80, 10)))
        val saved = state.snapshot()
        val updates = state.updates()
        state.setWatched("1", true)
        state.hide("5")
        state.toggleLater(series(3))
        state.observeFavorite("1", 85, 20, false)
        state.removeHistory("2")
        assertEquals(setOf("2"), saved.watched)
        assertEquals(setOf("4"), saved.hidden)
        assertEquals(listOf(series(3)), saved.later)
        assertEquals(1, updates.getValue("1").added)
        assertEquals(5, state.favoriteUpdate("1")!!.added)
        assertFalse(state.watched("2"))
        val merged = LibraryBackup.merge(state.snapshot(), saved, false)
        assertEquals(200, merged.history.size)
        assertEquals(state.settings, merged.settings)
    }

    /**
     * 删掉的观看记录要在本机留一块墓碑。
     *
     * 账号侧那半份（平台拉回来的历史）与本机进度是**合并**显示的，而平台的软删
     * 对短剧进度列表不一定生效——没有墓碑，删完过一会儿刷新就又回来了。
     * 墓碑要能落盘（[LibraryDisk] 的 `historyDeleted`）、要能挡住备份合并，
     * 并且**重新看这部剧**时自动摘掉（那时它本来就该重新出现）。
     */
    @Test fun `deleted history leaves a tombstone that survives reload and clears on replay`() {
        val state = LibraryIndex(data())
        state.removeHistory("2")
        assertTrue(state.historyDeleted("2"))
        assertNull(state.progress("2"))
        // 落盘 → 读回：墓碑跟着一起回来（否则重启之后账号侧那一条又冒出来）。
        val reloaded = LibraryDisk.decode(mapOf(
            "progress" to LibraryDisk.history(state.history()),
            "historyDeleted" to state.historyDeleted()
        ))
        assertTrue(reloaded.historyDeleted("2"))
        // 恢复备份不该把本机删过的条目带回来；但备份里**真的有**那一条时，墓碑让位。
        val tombstoned = LibraryBackup.merge(state.snapshot(), data().copy(history = emptyList()), false)
        assertTrue("2" in tombstoned.historyDeleted)
        val restored = LibraryBackup.merge(state.snapshot(),
            data().copy(history = listOf(progress(2, updated = 5))), false)
        assertFalse("2" in restored.historyDeleted)
        // 重新看这部剧：墓碑摘掉。
        state.save(progress(2, updated = 10))
        assertFalse(state.historyDeleted("2"))
        assertNotNull(state.progress("2"))
    }

    /** 清空历史：返回被清掉的 id、本机进度全清、墓碑留下，收藏 / 稍后观看不动。 */
    @Test fun `clear history returns removed ids and keeps other partitions`() {
        val state = LibraryIndex(data())
        val removed = state.clearHistory()
        assertEquals(200, removed.size)
        assertTrue(state.history().isEmpty())
        assertTrue(state.watched().isEmpty())
        assertEquals(200, state.historyDeleted().size)
        // 收藏与稍后观看一条不少。
        assertEquals(500, state.favorites().size)
        assertEquals(1, state.later().size)
        // 再清一次：已经没有可清的，返回空表且不重复记墓碑。
        assertTrue(state.clearHistory().isEmpty())
    }

    @Test fun `malformed rows do not discard valid records`() {
        val favoriteJson = "[null,{},${series(1).toJson()},false]"
        val state = LibraryDisk.decode(mapOf("favorites" to favoriteJson, "progress" to "bad", "favoriteUpdates" to "bad"))
        assertTrue(state.favorite("1"))
        assertTrue(state.history().isEmpty())
        assertEquals(0, state.updatedFavorites())
    }
}
