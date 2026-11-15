package com.hgtv.star.core

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class LibraryBackupTest {
    private fun series(id: String)=Series(id,"测试 $id","https://example.com/cover.jpg","", "已完结", "剧情")
    private fun progress(id: String,time: Long)=BackupProgress(series(id),"100$id",0,time,10000,false,time)
    private fun data()=BackupData(listOf(series("1")),listOf(progress("1",100)),setOf("1"),listOf(RecentSearch("你好")),BackupSettings())
    @Test fun `backup round trips records and settings with cover and text sanitization`() {
        val original=data().copy(settings=BackupSettings(720,1.5f,false,ContentType.COMIC,VideoFrameMode.ZOOM,DanmakuMode.DEFAULT))
        assertEquals(original,LibraryBackup.decode(LibraryBackup.encode(original)))
        val sanitized=original.copy(favorites=listOf(series("1").copy(title="你\n好",cover="http://127.0.0.1/secret")))
        val result=LibraryBackup.decode(LibraryBackup.encode(sanitized))
        assertEquals("你 好",result.favorites.single().title); assertEquals("",result.favorites.single().cover)
    }
    @Test fun `danmaku display mode defaults and survives a backup round trip`() {
        assertEquals(DanmakuMode.DEFAULT,DanmakuMode.fromStored(null))
        assertEquals(DanmakuMode.HIDDEN,DanmakuMode.fromStored("hidden"))
        // 旧备份里没有 danmaku 字段：按当前默认档读回来。
        val legacy=LibraryBackup.encode(data()).let { JSONObject(it).apply { getJSONObject("settings").remove("danmaku") }.toString() }
        assertEquals(DanmakuMode.DEFAULT,LibraryBackup.decode(legacy).settings.danmaku)
        assertEquals(DanmakuMode.DEFAULT,LibraryBackup.decode(LibraryBackup.encode(data().copy(settings=BackupSettings(danmaku=DanmakuMode.DEFAULT)))).settings.danmaku)
    }
    @Test fun `merge retains newer progress and watched state unless imported progress is newer`() {
        val local=data(); val incoming=data().copy(favorites=listOf(series("2"),series("1")),history=listOf(progress("1",50),progress("2",200)),watched=emptySet(),settings=BackupSettings(480,2f))
        val merged=LibraryBackup.merge(local,incoming,false)
        assertEquals(listOf("1","2"),merged.favorites.map { it.id })
        assertEquals(listOf(200L,100L),merged.history.map { it.updatedAt })
        assertTrue("1" in merged.watched); assertEquals(local.settings,merged.settings)
        val newer=LibraryBackup.merge(local,incoming.copy(history=listOf(progress("1",300))),true)
        assertFalse("1" in newer.watched); assertEquals(incoming.settings,newer.settings)
        assertEquals(1,newer.searches.size)
    }
    @Test fun `danmaku text size and opacity survive a backup round trip and legacy files default to 100 percent`() {
        val styled=data().copy(settings=BackupSettings(danmakuScale=1.4f,danmakuAlpha=0.5f))
        val restored=LibraryBackup.decode(LibraryBackup.encode(styled)).settings
        assertEquals(1.4f,restored.danmakuScale,0f); assertEquals(0.5f,restored.danmakuAlpha,0f)
        // 旧备份里没有这两个字段：按 100% 读回来，旧文件仍然可以恢复。
        val legacy=LibraryBackup.encode(data()).let { JSONObject(it).apply {
            getJSONObject("settings").also { settings -> settings.remove("danmakuScale"); settings.remove("danmakuAlpha") }
        }.toString() }
        val legacySettings=LibraryBackup.decode(legacy).settings
        assertEquals(1f,legacySettings.danmakuScale,0f); assertEquals(1f,legacySettings.danmakuAlpha,0f)
        // 越界值不报错，夹进合法区间并吸附到 0.1 的整数倍。
        val wild=LibraryBackup.decode(LibraryBackup.encode(data()).let { JSONObject(it).apply { getJSONObject("settings").put("danmakuScale",9).put("danmakuAlpha",0.03) }.toString() }).settings
        assertEquals(DanmakuStyle.SCALE_MAX,wild.danmakuScale,0f); assertEquals(DanmakuStyle.ALPHA_MIN,wild.danmakuAlpha,0f)
        assertEquals("130%",DanmakuStyle.percent(DanmakuStyle.scale(1.27f)))
        assertEquals(0.2f,DanmakuStyle.alpha(0.2f),0f)
        assertEquals(1f,DanmakuStyle.alpha(Float.NaN),0f)
    }
    @Test fun `merge caps collections and does not mark local favorites watched from stale orphan flags`() {
        val local=data().copy(favorites=(1..500).map { series(it.toString()) })
        val merged=LibraryBackup.merge(local,data().copy(favorites=listOf(series("501")),history=emptyList(),watched=setOf("2")),false)
        assertEquals(500,merged.favorites.size); assertFalse("2" in merged.watched)
        val records=(1..200).map { progress(it.toString(),it.toLong()) }
        val history=LibraryBackup.merge(local.copy(history=records),data().copy(history=listOf(progress("201",201))),false).history
        assertEquals(200,history.size); assertEquals("201",history.first().series.id); assertFalse(history.any { it.series.id=="1" })
    }
    @Test fun `invalid format ranges settings duplicates and oversized nesting are rejected`() {
        fun reject(change: (JSONObject)->Unit) { val o=JSONObject(LibraryBackup.encode(data())); change(o); assertTrue(o.toString(),runCatching { LibraryBackup.decode(o.toString()) }.isFailure) }
        reject { it.put("version",2) }; reject { it.put("format","other") }
        reject { it.getJSONObject("settings").put("speed",5) }; reject { it.getJSONObject("settings").put("quality",123) }
        reject { it.getJSONObject("settings").put("autoNext","true") }; reject { it.getJSONObject("settings").put("frame","OTHER") }
        reject { it.getJSONArray("history").getJSONObject(0).put("updatedAt",-1) }
        reject { it.getJSONArray("history").getJSONObject(0).put("position",1.5) }
        reject { it.getJSONArray("favorites").put(it.getJSONArray("favorites").getJSONObject(0)) }
        reject { it.getJSONArray("favorites").getJSONObject(0).put("id","../x") }
        reject { it.put("searches",JSONArray((1..21).map { JSONObject().put("query","a").put("type",ContentType.COMIC.storedValue) })) }
        assertTrue(runCatching { LibraryBackup.decode("[".repeat(10000)) }.isFailure)
        assertTrue(runCatching { LibraryBackup.decode(" ".repeat(LibraryBackup.MAX_BYTES+1)) }.isFailure)
    }
}
