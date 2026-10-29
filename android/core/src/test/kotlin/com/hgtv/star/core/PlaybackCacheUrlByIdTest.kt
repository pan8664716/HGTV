// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * 「按集作废地址」的边界。
 *
 * 现场故障：播几集后按「下一集」→ `地址过期：播放地址已失效（HTTP 403）`，
 * 退出再进就好。根因是队列项失败后**没人清过那一集的地址**，重切时原样命中同一条
 * 坏地址（见 `MainActivityPlayback.playerFailure` 里队列分支的注释）。
 *
 * 清理必须**按集整体**做：预取时的上限 / `device_level` 可能已经被降档改过，
 * 精确键（`集:上限:档位`）算不出来，用错键就等于什么都没删。
 */
class PlaybackCacheUrlByIdTest {

    private lateinit var dir: java.io.File

    @Before fun setUp() {
        dir = Files.createTempDirectory("hgtv-url").toFile()
        PlaybackCache.detach()
        PlaybackCache.init(dir)
    }

    @After fun tearDown() {
        PlaybackCache.detach()
        dir.deleteRecursively()
    }

    private fun info(tag: String) = StreamInfo(
        url = "https://cdn.example.com/video?sign=$tag",
        key = null,
        quality = "1080P",
        requestedQuality = 1080,
        actualQuality = 1080,
        available = listOf(1080, 720),
    )

    @Test fun `按集作废会删掉这一集的所有档位`() {
        PlaybackCache.saveUrl("111:1080:4", info("a"))
        PlaybackCache.saveUrl("111:720:4", info("b"))
        assertNotNull(PlaybackCache.loadUrl("111:1080:4"))
        assertNotNull(PlaybackCache.loadUrl("111:720:4"))

        PlaybackCache.invalidateUrlById("111")

        assertNull("1080 那一档必须删掉", PlaybackCache.loadUrl("111:1080:4"))
        assertNull("720 那一档也必须删掉", PlaybackCache.loadUrl("111:720:4"))
    }

    @Test fun `按集作废不能误伤前缀相同的另一集`() {
        // `111` 与 `1111` 是两部不同的剧：删 `111` 不能把 `1111` 的地址一起带走。
        PlaybackCache.saveUrl("111:1080:4", info("a"))
        PlaybackCache.saveUrl("1111:1080:4", info("b"))

        PlaybackCache.invalidateUrlById("111")

        assertNull(PlaybackCache.loadUrl("111:1080:4"))
        assertNotNull("1111 那一集必须留着", PlaybackCache.loadUrl("1111:1080:4"))
    }

    @Test fun `按集作废不影响别的集`() {
        PlaybackCache.saveUrl("111:1080:4", info("a"))
        PlaybackCache.saveUrl("222:1080:4", info("b"))

        PlaybackCache.invalidateUrlById("111")

        assertNull(PlaybackCache.loadUrl("111:1080:4"))
        assertNotNull(PlaybackCache.loadUrl("222:1080:4"))
    }

    @Test fun `空 id 是空操作`() {
        PlaybackCache.saveUrl("111:1080:4", info("a"))
        PlaybackCache.invalidateUrlById("")
        assertNotNull("空 id 不能把整目录清掉", PlaybackCache.loadUrl("111:1080:4"))
    }
}
