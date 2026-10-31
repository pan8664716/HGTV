// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音结果的清理规则。
 *
 * 这几条都是「削错就搜不到、留着就搜不准」的边界，因此逐条钉住：
 * 去标点、去口语前缀、前缀本身就是剧名时不削、超长截断。
 */
class VoiceQueryTest {

    @Test fun `strips edge punctuation and quotes`() {
        assertEquals("末日漂流", VoiceQuery.normalize("  《末日漂流》。 "))
        assertEquals("末日漂流", VoiceQuery.normalize("「末日漂流」"))
        assertEquals("", VoiceQuery.normalize("   "))
    }

    @Test fun `removes spoken prefixes`() {
        assertEquals("末日漂流", VoiceQuery.normalize("我想看末日漂流"))
        assertEquals("末日漂流", VoiceQuery.normalize("帮我搜一下末日漂流"))
        assertEquals("末日漂流", VoiceQuery.normalize("搜一下：末日漂流"))
    }

    @Test fun `keeps a title that is itself a prefix`() {
        // 只剩一个字时不能削：剧名就叫「搜索」的那种，削完就什么都没了。
        assertEquals("搜索", VoiceQuery.normalize("搜索"))
        assertEquals("搜", VoiceQuery.normalize("搜索一下 搜"))
    }

    @Test fun `truncates to the input limit`() {
        val long = "剧".repeat(VoiceQuery.MAX + 20)
        assertEquals(VoiceQuery.MAX, VoiceQuery.normalize(long).length)
    }

    @Test fun `does not guess homophones or split words`() {
        // 只做上面那几条，不做同音字纠正：识别错的时候让用户自己改，比被「猜」可靠。
        assertEquals("墨日漂流", VoiceQuery.normalize("墨日漂流"))
        assertTrue(VoiceQuery.normalize("").isEmpty())
    }
}
