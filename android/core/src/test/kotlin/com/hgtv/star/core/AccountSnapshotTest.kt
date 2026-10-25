// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountSnapshotTest {
    private fun series(id: String,title: String="测试 $id")=Series(id,title,"https://example.com/$id.jpg","简介","全 20 集","剧情")
    private fun data()=AccountData(
        AccountProfile("066831371270","阴阳穿梭者吴远","https://example.com/a.jpg","066831371270","签名",1,2,3),
        listOf(AccountFavorite(series("1"),1000,20),AccountFavorite(series("2"),900,30)),
        listOf(AccountHistory(series("3"),4,"3004",800,30)),
        listOf(AccountLike(series("5"),700)),
        listOf(AccountReserve(series("6"),600,true))
    )

    /** 五栏与资料都能原样读回来（缓存的意义就在这里）。 */
    @Test fun `round trips every column`() {
        val now=1_000_000L
        val entry=AccountSnapshot.decode(AccountSnapshot.encode(data(),now),now)
        assertNotNull(entry)
        assertEquals(now,entry!!.savedAt)
        assertEquals(data().profile,entry.data.profile)
        assertEquals(listOf("1","2"),entry.data.favorites.map { it.series.id })
        assertEquals(4,entry.data.history.single().episodeIndex)
        assertEquals("3004",entry.data.history.single().episodeId)
        assertEquals(listOf("5"),entry.data.likes.map { it.series.id })
        assertTrue(entry.data.reserves.single().online)
        assertTrue(entry.data.usable)
    }

    /** 过期、版本不符、坏 JSON、体积超限都当作「没有缓存」，而不是抛异常。 */
    @Test fun `rejects stale broken and oversized caches`() {
        val now=1_000_000L
        val raw=AccountSnapshot.encode(data(),now)
        assertNull(AccountSnapshot.decode(raw,now+AccountSnapshot.MAX_AGE+1))
        assertNull(AccountSnapshot.decode("{}",now))
        assertNull(AccountSnapshot.decode("不是 JSON",now))
        val bumped=JSONObject(raw).put("version",99).toString()
        assertNull(AccountSnapshot.decode(bumped,now))
        assertNull(AccountSnapshot.decode("x".repeat(AccountSnapshot.MAX_BYTES+1),now))
        // 未来时间戳（时钟被改过）也不接受。
        assertNull(AccountSnapshot.decode(raw,now-10_000))
    }

    /** 没有资料的快照不算可用：界面读到它会显示「正在读取」，而不是一片空白。 */
    @Test fun `a cache without a profile is not usable`() {
        val now=1_000L
        val entry=AccountSnapshot.decode(AccountSnapshot.encode(data().copy(profile=null),now),now)
        assertNotNull(entry)
        assertFalse(entry!!.data.usable)
    }

    /** 比对只看界面上看得见的东西：简介被截断 / 封面参数重排不算「变了」。 */
    @Test fun `comparison ignores invisible differences`() {
        val base=data()
        assertTrue(AccountSnapshot.changedKeys(base,base).isEmpty())
        val reworded=base.copy(favorites=base.favorites.map { it.copy(series=it.series.copy(description="另一段更长的简介",tags="剧情")) })
        assertTrue(AccountSnapshot.changedKeys(base,reworded).isEmpty())
        assertEquals(setOf("收藏"),AccountSnapshot.changedKeys(base,base.copy(favorites=base.favorites.drop(1))))
        assertEquals(setOf("历史"),AccountSnapshot.changedKeys(base,base.copy(history=emptyList())))
        assertEquals(setOf("资料"),AccountSnapshot.changedKeys(base,base.copy(profile=base.profile?.copy(nickname="改了名"))))
        assertEquals(setOf("点赞","预约"),AccountSnapshot.changedKeys(base,base.copy(likes=emptyList(),reserves=emptyList())))
        // 还没有缓存时五栏都算变了（界面必须铺一次）。
        assertEquals(AccountSnapshot.KEYS,AccountSnapshot.changedKeys(null,base))
    }

    /** 某一栏这次没回来（errors 里记着）时保留上一份，不要用空列表把它盖掉。 */
    @Test fun `failed columns keep the previous data`() {
        val previous=data()
        val fresh=AccountData(previous.profile,emptyList(),emptyList(),previous.likes,previous.reserves,
            mapOf("收藏" to "空响应体","历史" to "超时"))
        val merged=AccountSnapshot.merge(previous,fresh)
        assertEquals(previous.favorites,merged.favorites)
        assertEquals(previous.history,merged.history)
        assertEquals(previous.likes,merged.likes)
        // 失败的栏位不产生「变化」，界面因此不会被这次刷新惊动。
        assertTrue(AccountSnapshot.changedKeys(previous,merged).isEmpty())
        // 全成功时按新的来（真的清空了就是清空了）。
        val cleared=fresh.copy(errors=emptyMap())
        assertEquals(emptyList<AccountFavorite>(),AccountSnapshot.merge(previous,cleared).favorites)
        // 没有上一份时直接采用（第一次拿到数据）。
        assertEquals(fresh,AccountSnapshot.merge(null,fresh))
    }

    /** 落盘的那一份不带 errors：一次性的失败原因不该跟着缓存活到下次启动。 */
    @Test fun `cacheable drops transient errors`() {
        val now=1_000L
        val withErrors=data().copy(errors=mapOf("点赞" to "空响应体"))
        val raw=AccountSnapshot.encode(AccountSnapshot.cacheable(withErrors),now)
        assertTrue(AccountSnapshot.decode(raw,now)!!.data.errors.isEmpty())
    }
}
