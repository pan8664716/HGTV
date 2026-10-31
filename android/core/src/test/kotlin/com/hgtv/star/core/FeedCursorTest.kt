// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedCursorTest {

    @Test
    fun `decode ignores malformed entries`() {
        assertEquals(emptyList<FeedCursor.Entry>(), FeedCursor.decode(""))
        assertEquals(emptyList<FeedCursor.Entry>(), FeedCursor.decode("abc"))
        assertEquals(emptyList<FeedCursor.Entry>(), FeedCursor.decode("comic|123"))
        assertEquals(emptyList<FeedCursor.Entry>(), FeedCursor.decode("comic|123|-5"))
        assertEquals(emptyList<FeedCursor.Entry>(), FeedCursor.decode("comic|123|abc"))
        assertEquals(
            listOf(FeedCursor.Entry("comic", "123", 40)),
            FeedCursor.decode("comic|123|40"),
        )
    }

    @Test
    fun `mixed feed uses empty genre`() {
        val raw = FeedCursor.upsert("", FeedCursor.Entry("", "s1", 20))
        assertEquals(FeedCursor.Entry("", "s1", 20), FeedCursor.of(raw, ""))
        assertNull(FeedCursor.of(raw, "comic"))
    }

    @Test
    fun `upsert replaces same genre and keeps others`() {
        var raw = FeedCursor.upsert("", FeedCursor.Entry("comic", "a", 20))
        raw = FeedCursor.upsert(raw, FeedCursor.Entry("real", "b", 40))
        raw = FeedCursor.upsert(raw, FeedCursor.Entry("comic", "c", 60))
        assertEquals(FeedCursor.Entry("comic", "c", 60), FeedCursor.of(raw, "comic"))
        assertEquals(FeedCursor.Entry("real", "b", 40), FeedCursor.of(raw, "real"))
    }

    @Test
    fun `remove drops only that genre`() {
        var raw = FeedCursor.upsert("", FeedCursor.Entry("comic", "a", 20))
        raw = FeedCursor.upsert(raw, FeedCursor.Entry("real", "b", 40))
        raw = FeedCursor.remove(raw, "comic")
        assertNull(FeedCursor.of(raw, "comic"))
        assertEquals(FeedCursor.Entry("real", "b", 40), FeedCursor.of(raw, "real"))
    }

    @Test
    fun `entries are capped`() {
        var raw = ""
        repeat(10) { i -> raw = FeedCursor.upsert(raw, FeedCursor.Entry("g$i", "s", i * 10)) }
        assertEquals(6, FeedCursor.decode(raw).size)
        // 最早的两条被淘汰，最后一条一定还在。
        assertNull(FeedCursor.of(raw, "g0"))
        assertEquals(90, FeedCursor.of(raw, "g9")?.offset)
    }

    @Test
    fun `separators inside values are stripped`() {
        val raw = FeedCursor.upsert("", FeedCursor.Entry("a|b;c", "s|1;2", 10))
        val entry = FeedCursor.decode(raw).single()
        assertEquals("abc", entry.genre)
        assertEquals("s12", entry.session)
    }
}
