package com.hgtv.star.core

import okhttp3.*
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LowEndPlaybackTest {
    private class FakeCall: Call {
        var started=false
        @Volatile var cancelled=false
        override fun request()=Request.Builder().url("https://example.invalid/video").build()
        override fun execute(): Response {
            started=true
            if(cancelled) throw IOException("cancelled")
            return Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("data".toResponseBody(null)).build()
        }
        override fun enqueue(responseCallback: Callback)=error("not used")
        override fun cancel() { cancelled=true }
        override fun isExecuted()=started
        override fun isCanceled()=cancelled
        override fun timeout()=Timeout.NONE
        override fun clone(): Call=FakeCall()
    }
    @Test fun cancelledNavigationDoesNotStartQueuedNetworkWork() {
        val scope=RequestScope(); val call=FakeCall(); scope.cancel()
        assertThrows(IOException::class.java) { scope.run { RequestScope.execute(call) { it.body!!.string() } } }
        assertFalse(call.started)
    }
    @Test fun cancelStillOwnsCallWhileResponseBodyIsBeingConsumed() {
        val scope=RequestScope(); val call=FakeCall(); val reading=CountDownLatch(1); val finish=CountDownLatch(1)
        val worker=Thread { scope.run { RequestScope.execute(call) { reading.countDown(); finish.await(2,TimeUnit.SECONDS) } } }
        worker.start()
        try {
            assertTrue(reading.await(2,TimeUnit.SECONDS)); scope.cancel(); assertTrue(call.cancelled)
        } finally { finish.countDown(); worker.join(2000) }
        assertFalse(worker.isAlive)
    }
    @Test fun cancellingOldPageDoesNotCancelAnotherPagesOrCompletedCalls() {
        val old=RequestScope(); val current=RequestScope(); val first=FakeCall(); val next=FakeCall()
        assertEquals("data",old.run { RequestScope.execute(first) { it.body!!.string() } })
        old.cancel(); assertFalse(first.cancelled)
        assertEquals("data",current.run { RequestScope.execute(next) { it.body!!.string() } })
        assertFalse(next.cancelled)
    }
    @Test fun prefetchWindowUsesRemainingWallTimeAndRejectsUnknownDuration() {
        assertFalse(PrefetchPolicy.nearEnd(5_000,180_000,1f))
        assertTrue(PrefetchPolicy.nearEnd(150_000,180_000,1f))
        assertTrue(PrefetchPolicy.nearEnd(120_000,180_000,2f))
        assertFalse(PrefetchPolicy.nearEnd(150_000,180_000,.5f))
        assertFalse(PrefetchPolicy.nearEnd(0,0,1f))
        assertFalse(PrefetchPolicy.nearEnd(0,180_000,Float.NaN))
    }
    @Test fun expiredPrefetchCanBeReplacedAfterLongPauseWithoutReturningOldResource() {
        var now=0L
        class Resource: Closeable { var closed=false; override fun close() { closed=true } }
        val slot=PreparedSlot<Resource>({now}); val old=Resource()
        slot.complete(slot.begin("next"),"next",old); assertTrue(slot.isFresh("next"))
        now=120_001; assertFalse(slot.isFresh("next"))
        val fresh=Resource(); slot.complete(slot.begin("next"),"next",fresh)
        assertTrue(old.closed); assertSame(fresh,slot.take("next")); assertFalse(fresh.closed)
    }
}
