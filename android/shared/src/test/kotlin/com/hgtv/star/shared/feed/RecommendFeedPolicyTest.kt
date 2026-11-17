package com.hgtv.star.shared.feed

import com.hgtv.star.core.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendFeedPolicyTest {
    @Test
    fun fetchesSixtyItemsAndPrefetchesAfterTwoThirdsConsumed() {
        val policy=RecommendFeedPolicy.standard("test")
        assertFalse(policy.shouldPrefetch(21))
        assertTrue(policy.shouldPrefetch(20))
        assertTrue(AppConfig.Recommend.FIRST_LIMIT==60)
        assertTrue(AppConfig.Recommend.PAGE_LIMIT==20)
        assertTrue(AppConfig.Recommend.MIN_ITEMS_PER_LOAD==60)
    }

    @Test
    fun playbackBatchReplenishesAfterTenConsumedItems() {
        val state=RecommendPlaybackBatchState(replenishAfter=10)
        assertTrue(state.shouldSchedule(0))
        assertFalse(state.shouldSchedule(9))
        assertTrue(state.shouldSchedule(10))
        state.reset()
        assertTrue(state.shouldSchedule(11))
    }
}
