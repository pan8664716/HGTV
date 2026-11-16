package com.hgtv.star.shared.feed

import com.hgtv.star.core.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendFeedPolicyTest {
    @Test
    fun fetchesTwentyItemsAndPrefetchesAfterTwoThirdsConsumed() {
        val policy=RecommendFeedPolicy.standard("test")
        assertFalse(policy.shouldPrefetch(8))
        assertTrue(policy.shouldPrefetch(7))
        assertTrue(AppConfig.Recommend.FIRST_LIMIT==20)
        assertTrue(AppConfig.Recommend.PAGE_LIMIT==20)
    }
}
