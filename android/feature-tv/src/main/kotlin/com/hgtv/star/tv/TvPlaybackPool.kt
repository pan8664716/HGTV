// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.util.MimeTypes
import com.hgtv.star.core.Detail
import com.hgtv.star.core.AppConfig
import com.hgtv.star.core.PlaybackCache
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.core.detail
import com.hgtv.star.core.lruPut
import com.hgtv.star.shared.media.VideoDataSource
import java.util.concurrent.Future

internal class TvPlaybackPoolSlot(
    val pageIndex: Int,
    val seriesId: String,
    val episodeId: String,
    val episodeIndex: Int,
    val detail: Detail,
    val remote: RemoteVideo,
    val http: okhttp3.OkHttpClient?,
    val player: ExoPlayer,
    val decoderMode: VideoDecoderMode,
    val bufferProfile: TvBufferProfile,
) {
    var sourcePrepared=true
    var firstFrameRendered=false
    var preRendering=false
    var preRenderToken=0
    var preparedAt=0L
    var listener: Player.Listener?=null
}

internal class TvPlaybackPoolState {
    val slots=LinkedHashMap<String,TvPlaybackPoolSlot>()
    val jobs=HashMap<String,Future<*>>()
    var generation=0

    fun key(screen: String,seriesId: String,episodeId: String): String =
        "$screen|$seriesId|$episodeId"

    fun clear() {
        generation++
        jobs.values.forEach { runCatching { it.cancel(true) } }
        jobs.clear()
        slots.values.toList().forEach(::releaseSlot)
        slots.clear()
    }

    fun releaseSlot(slot: TvPlaybackPoolSlot) {
        slot.listener?.let(slot.player::removeListener)
        slot.listener=null
        runCatching { slot.player.pause(); slot.player.release() }
        runCatching { slot.remote.close() }
    }
}

internal fun TvMainActivity.currentPlaybackPageIndex(): Int =
    if(screen=="recommend") recommendIndex.coerceAtLeast(0) else episodeIndex.coerceAtLeast(0)

/**
 * 在同一套播放层里切换推荐 / 正片页数据。
 *
 * 不能直接重建 `PlayerView`：那样会把当前活动播放器的 Surface 摘掉，电视上会黑一帧。
 */
internal fun TvMainActivity.switchTvPlaybackPagerScreen(next: String,index: Int) {
    val pager=tvPlaybackPager ?: return
    if(pager.screen==next) {
        pager.setCurrentIndex(index,false)
        pager.post {
            if(!isDestroyed && tvPlaybackPager===pager && pager.currentIndex==index) {
                pager.syncCurrentPlayerWhenReady(index)
            }
        }
        return
    }
    releaseTvPlaybackPool()
    pager.switchScreen(next,index)
    pager.post {
        if(!isDestroyed && tvPlaybackPager===pager && pager.currentIndex==index) {
            pager.syncCurrentPlayerWhenReady(index)
            applyVideoGeometry()
        }
    }
}

/**
 * 电视端池上限。
 *
 * 池容量按 Phone 对齐为 3。当前页由活动播放器单独持有，池里主要保留前后相邻页；
 * 低端电视也不把容量降成 1，避免为了回看上一页把下一页的预渲染实例挤掉。
 */
private fun TvMainActivity.tvPoolLimit(): Int = 3

internal fun TvMainActivity.bindTvPlaybackPage(page: TvPlaybackSurfacePage,index: Int) {
    val entry=tvPlaybackPool.slots.entries.firstOrNull { it.value.pageIndex==index }
    val slot=entry?.value?.takeIf { tvPoolSlotMatches(it,index) }
    if(entry!=null && slot==null) releaseTvPoolEntry(entry.key)
    if(index==currentPlaybackPageIndex() && player!=null) {
        page.playerView.player=player
        playerView=page.playerView
        return
    }
    if(slot!=null) {
        attachTvPoolSlotToPage(slot,page)
    } else if(page.playerView.player!==player) {
        page.detachPlayerOutput()
    }
}

internal fun TvMainActivity.detachTvPlaybackPage(page: TvPlaybackSurfacePage) {
    if(page.playerView===playerView || page.playerView.player===player) return
    page.detachPlayerOutput()
}

private fun TvMainActivity.attachTvPoolSlotToPage(
    slot: TvPlaybackPoolSlot,
    page: TvPlaybackSurfacePage?=tvPlaybackPager?.pageAt(slot.pageIndex),
) {
    page ?: return
    if(slot.player===player || page.playerView.player===player) return
    if(slot.pageIndex==currentPlaybackPageIndex()) return
    if(com.hgtv.star.tv.BuildConfig.DEBUG) {
        android.util.Log.i(
            "HGTV-POOL",
            "attach key=${slot.seriesId}|${slot.episodeId} page=${slot.pageIndex} "+
                "surface=${page.playerView.videoSurfaceView?.javaClass?.simpleName}",
        )
    }
    page.playerView.setKeepContentOnPlayerReset(true)
    page.playerView.visibility=View.VISIBLE
    if(!slot.firstFrameRendered) page.playerView.holdFirstFrameAspectRatio()
    if(page.playerView.player!==slot.player) {
        page.detachPlayerOutput()
        page.playerView.player=slot.player
    }
    if(!slot.firstFrameRendered && !slot.preRendering) startTvPoolPreRender(slot)
}

private fun TvMainActivity.startTvPoolPreRender(slot: TvPlaybackPoolSlot) {
    if(slot.firstFrameRendered || slot.preRendering || slot.player===player) return
    slot.preRendering=true
    val token=++slot.preRenderToken
    slot.player.volume=0f
    slot.player.playWhenReady=true
    main.postDelayed({
        if(tvPlaybackPool.slots.values.any { it===slot } && slot.preRendering &&
            slot.preRenderToken==token) {
            slot.preRendering=false
            runCatching { slot.player.pause() }
        }
    },AppConfig.Playback.Preload.PHONE_PRE_RENDER_TIMEOUT_MS)
}

internal fun TvMainActivity.storeActivePlayerInTvPool() {
    val p=player ?: return
    val r=video ?: return
    val d=detail ?: return
    if(screen!="recommend" && screen!="player") return
    val index=if(screen=="recommend") recommendIndex else episodeIndex
    val episodeIndex=this.episodeIndex.coerceAtLeast(0)
    val episodeId=d.episodes.getOrNull(episodeIndex) ?: return
    val key=tvPlaybackPool.key(screen,d.series.id,episodeId)
    val existing=tvPlaybackPool.slots[key]
    if(existing?.player===p) {
        existing.remote.let { if(it!==r) runCatching { it.close() } }
        return
    }
    releaseTvPoolEntry(key)
    playerListener?.let(p::removeListener)
    playerListener=null
    setTvPlayerAudioFocus(p,false)
    p.playWhenReady=false
    p.volume=0f
    runCatching { p.pause() }
    runCatching {
        val current=p.currentMediaItemIndex.coerceAtLeast(0)
        if(p.mediaItemCount>current+1) p.removeMediaItems(current+1,p.mediaItemCount)
    }
    val slot=TvPlaybackPoolSlot(
        pageIndex=index,
        seriesId=d.series.id,
        episodeId=episodeId,
        episodeIndex=episodeIndex,
        detail=d,
        remote=r,
        http=playbackHttp,
        player=p,
        decoderMode=builtPlayerDecoderMode ?: playbackDecoderMode,
        bufferProfile=builtPlayerBufferProfile ?: resolveTvBufferProfile(),
    ).apply {
        sourcePrepared=true
        firstFrameRendered=lastRenderedMediaId==episodeId || playbackReady
        preparedAt=android.os.SystemClock.elapsedRealtime()
    }
    // 所有权已经交给池槽。调用方随后会换到新的活动实例；这里先清掉页面字段，
    // 避免 resetRecommendPlaybackForSwitch() / detachPlayerSurface() 再关一次。
    player=null
    video=null
    playbackHttp=null
    tvPlaybackPool.slots[key]=slot
    attachTvPoolSlotToPage(slot)
    trimTvPlaybackPool(index)
}

internal fun TvMainActivity.takeTvPlaybackPooledSlot(
    index: Int,
    seriesId: String,
    episodeId: String,
): TvPlaybackPoolSlot? {
    val key=tvPlaybackPool.key(screen,seriesId,episodeId)
    val slot=tvPlaybackPool.slots[key] ?: return null
    if(slot.pageIndex!=index) return null
    if(!slot.sourcePrepared || slot.player.playerError!=null ||
        slot.preparedAt<=0L ||
        android.os.SystemClock.elapsedRealtime()-slot.preparedAt>PlaybackCache.URL_FRESH_MS ||
        slot.decoderMode!=playbackDecoderMode ||
        slot.bufferProfile!=resolveTvBufferProfile()
    ) {
        releaseTvPoolEntry(key)
        return null
    }
    tvPlaybackPool.slots.remove(key)
    return slot
}

private fun TvMainActivity.trimTvPlaybackPool(anchor: Int) {
    if(com.hgtv.star.tv.BuildConfig.DEBUG) {
        android.util.Log.i(
            "HGTV-POOL",
            "trim anchor=$anchor screen=$screen limit=${tvPoolLimit()} "+
                tvPlaybackPool.slots.values.joinToString(",") {
                    "page=${it.pageIndex}:series=${it.seriesId}:episode=${it.episodeId}:match="+
                        tvPoolSlotMatches(it,it.pageIndex)
                },
        )
    }
    tvPlaybackPool.slots.entries
        .filter { !tvPoolSlotMatches(it.value,it.value.pageIndex) }
        .map { it.key }
        .toList()
        .forEach(::releaseTvPoolEntry)
    val limit=tvPoolLimit()
    val preferred=anchor+1
    val keep=tvPlaybackPool.slots.entries
        .sortedWith(
            compareBy<MutableMap.MutableEntry<String,TvPlaybackPoolSlot>> {
                // 池槽数量不足时，下一页预渲染优先于回看上一页。
                when(it.value.pageIndex) {
                    preferred -> 0
                    anchor -> 2
                    else -> 1
                }
            }.thenBy {
                kotlin.math.abs(it.value.pageIndex-anchor)
            }.thenBy { it.value.pageIndex }
        )
        .take(limit)
        .map { it.key }
        .toHashSet()
    tvPlaybackPool.slots.entries
        .filter { it.key !in keep }
        .map { it.key }
        .toList()
        .forEach { key ->
            releaseTvPoolEntry(key)
        }
}

internal fun TvMainActivity.releaseTvPlaybackPool() {
    tvPlaybackPool.slots.values.forEach { slot ->
        tvPlaybackPager?.pageAt(slot.pageIndex)?.let { page ->
            if(page.playerView.player===slot.player) page.detachPlayerOutput()
        }
        closeTransport(slot.http)
    }
    tvPlaybackPool.clear()
}

internal fun TvMainActivity.releaseTvPoolSlotAt(pageIndex: Int) {
    tvPlaybackPool.slots.entries
        .filter { it.value.pageIndex==pageIndex }
        .map { it.key }
        .toList()
        .forEach { key ->
            releaseTvPoolEntry(key)
        }
}

private fun TvMainActivity.releaseTvPoolEntry(key: String) {
    val slot=tvPlaybackPool.slots.remove(key) ?: return
    if(com.hgtv.star.tv.BuildConfig.DEBUG) {
        android.util.Log.i(
            "HGTV-POOL",
            "release key=$key page=${slot.pageIndex} active=${slot.player===player} "+
                "screen=$screen current=${currentPlaybackPageIndex()}",
        )
    }
    tvPlaybackPager?.pageAt(slot.pageIndex)?.let { page ->
        if(page.playerView.player===slot.player) page.detachPlayerOutput()
    }
    slot.preRendering=false
    slot.preRenderToken++
    closeTransport(slot.http)
    tvPlaybackPool.releaseSlot(slot)
}

/**
 * 当前页首帧后准备前后页独立播放器。
 *
 * 播放器默认不申请音频焦点，只以 0 音量完成 MediaSource / 解码器 / 首帧预热；
 * 真正提升为活动实例时才恢复音量和焦点。
 */
internal fun TvMainActivity.scheduleTvPlaybackPoolNeighbors() {
    val pager=tvPlaybackPager ?: return
    if(screen!="recommend" && screen!="player") return
    if(!foreground || playError || pausedForLifecycle) return
    val anchor=currentPlaybackPageIndex()
    // 当前页由活动播放器持有；旧任务留下的同页槽位必须让位给下一页。
    releaseTvPoolSlotAt(anchor)
    val allTargets=when(screen) {
        "recommend" -> listOf(anchor+1,anchor-1).filter { it in recommendItems.indices }
        "player" -> listOf(anchor+1,anchor-1).filter {
            detail?.episodes?.indices?.contains(it)==true
        }
        else -> emptyList()
    }
    val targets=if(tvPoolLimit()<=1 && allTargets.size>1) {
        val preferred=anchor+1
        listOf(allTargets.firstOrNull { it==preferred } ?: allTargets.first())
    } else allTargets
    if(targets.isEmpty()) return
    val generation=tvPlaybackPool.generation
    var inFlight=tvPlaybackPool.jobs.keys.count { it.startsWith("$screen|") || it.startsWith("detail|") }
    for(target in targets) {
        if(inFlight>=1) break
        val sourceDetail=when(screen) {
            "recommend" -> recommendItems.getOrNull(target)?.let { recommendDetails[it.id] }
            else -> detail
        }
        val seriesId=when(screen) {
            "recommend" -> recommendItems.getOrNull(target)?.id.orEmpty()
            else -> detail?.series?.id.orEmpty()
        }
        if(seriesId.isEmpty()) continue
        if(sourceDetail==null) {
            prepareTvPoolRecommendDetail(target,seriesId,generation)
            inFlight++
            continue
        }
        val episodeIndex=if(screen=="recommend") 0 else target
        val episodeId=sourceDetail.episodes.getOrNull(episodeIndex) ?: continue
        val key=tvPlaybackPool.key(screen,seriesId,episodeId)
        if(tvPlaybackPool.slots.containsKey(key) || tvPlaybackPool.jobs.containsKey(key)) continue
        prepareTvPoolSlot(
            pageIndex=target,
            detail=sourceDetail,
            episodeIndex=episodeIndex,
            episodeId=episodeId,
            generation=generation,
        )
        inFlight++
    }
    // 页面刚创建时可能还没有 ViewHolder；再绑定一次是幂等的。
    pager.currentPage()?.let { bindTvPlaybackPage(it,anchor) }
}

private fun TvMainActivity.prepareTvPoolRecommendDetail(
    pageIndex: Int,
    seriesId: String,
    generation: Int,
) {
    val key="detail|$seriesId"
    if(tvPlaybackPool.jobs.containsKey(key)) return
    tvPlaybackPool.jobs[key]=io.submit(Runnable {
        val d: Detail? = try {
            repository.detail(seriesId,cached=true,includeAuthor=false)
        } catch (_: Exception) {
            null
        }
        main.post {
            tvPlaybackPool.jobs.remove(key)
            if(d==null || generation!=tvPlaybackPool.generation ||
                isDestroyed || screen!="recommend" ||
                recommendItems.getOrNull(pageIndex)?.id!=seriesId ||
                !tvPoolTargetNeighbor(pageIndex)
            ) return@post
            if(d.episodes.isEmpty()) return@post
            recommendDetails.lruPut(RECOMMEND_DETAIL_LIMIT,seriesId,d)
            val episodeId=d.episodes.first()
            prepareTvPoolSlot(pageIndex,d,0,episodeId,generation)
        }
    })
}

private fun TvMainActivity.prepareTvPoolSlot(
    pageIndex: Int,
    detail: Detail,
    episodeIndex: Int,
    episodeId: String,
    generation: Int,
) {
    val seriesId=detail.series.id
    val poolScreen=screen
    val key=tvPlaybackPool.key(poolScreen,seriesId,episodeId)
    if(tvPlaybackPool.slots.containsKey(key) || tvPlaybackPool.jobs.containsKey(key)) return
    tvPlaybackPool.jobs[key]=io.submit {
        val remote=runCatching {
            val queue=detail.episodes.drop(episodeIndex+1).take(5)
            val info=playbackSource.stream(
                episodeId,
                playbackMaxQuality,
                detail.playback,
                queue=queue,
                direct=poolScreen=="recommend",
            )
            // 与 Phone 的 FULL 预热一致：这里只做 warm（地址 / 索引元数据 / 首块），
            // 不在池线程上提前做一次完整 prepare；真正 prepare 交给池播放器，
            // 否则 S905 这类低端设备会把相邻页预热拖到 2 秒以上。
            playbackSource.warm(episodeId,info) { it.warm() }
        }.getOrNull()
        if(remote==null) {
            main.post { tvPlaybackPool.jobs.remove(key) }
            return@submit
        }
        val decoderMode=playerDecoderMode()
        val bufferProfile=resolveTvBufferProfile()
        val built=runCatching {
            buildPlayer(
                mode=decoderMode,
                profile=bufferProfile,
                handleAudioFocus=false,
            )
        }.getOrNull()
        main.post {
            tvPlaybackPool.jobs.remove(key)
            if(built==null) {
                runCatching { remote.close() }
                return@post
            }
            if(generation!=tvPlaybackPool.generation || isDestroyed ||
                screen!=poolScreen || !tvPoolTargetNeighbor(pageIndex)
            ) {
                runCatching { built.release() }
                runCatching { remote.close() }
                return@post
            }
            val slot=TvPlaybackPoolSlot(
                pageIndex=pageIndex,
                seriesId=seriesId,
                episodeId=episodeId,
                episodeIndex=episodeIndex,
                detail=detail,
                remote=remote,
                http=null,
                player=built,
                decoderMode=decoderMode,
                bufferProfile=bufferProfile,
            ).apply {
                sourcePrepared=true
                preparedAt=android.os.SystemClock.elapsedRealtime()
            }
            val listener=object: Player.Listener {
                override fun onRenderedFirstFrame() {
                    if(tvPlaybackPool.slots.values.none { it===slot }) return
                    if(com.hgtv.star.tv.BuildConfig.DEBUG) {
                        android.util.Log.i(
                            "HGTV-POOL",
                            "firstFrame page=${slot.pageIndex} series=${slot.seriesId} "+
                                "episode=${slot.episodeId}",
                        )
                    }
                    slot.firstFrameRendered=true
                    slot.preRendering=false
                    slot.preRenderToken++
                    tvPlaybackPager?.pageAt(slot.pageIndex)?.let { page ->
                        page.playerView.completeFirstFrameAspectRatio()
                        page.playerView.requestLayout()
                    }
                    if(slot.player!==player) {
                        runCatching { slot.player.pause() }
                        slot.player.volume=0f
                    }
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    if(tvPlaybackPool.slots.values.none { it===slot }) return
                    main.post {
                        val current=tvPlaybackPool.slots.entries.firstOrNull { it.value===slot }
                        if(current!=null) releaseTvPoolEntry(current.key)
                    }
                }
            }
            slot.listener=listener
            built.addListener(listener)
            val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }
                .createMediaSource(
                    MediaItem.Builder()
                        .setMediaId(episodeId)
                        .setUri("hgtv://episode/$episodeId")
                        .setMimeType(MimeTypes.VIDEO_MP4)
                        .build()
                )
            if(runCatching {
                    built.setMediaSource(source)
                    built.setPlaybackSpeed(library.playbackSpeed)
                    built.volume=0f
                    built.playWhenReady=true
                    built.prepare()
                }.isFailure
            ) {
                slot.listener?.let(built::removeListener)
                runCatching { built.release() }
                runCatching { remote.close() }
                return@post
            }
            if(!tvPoolTargetNeighbor(pageIndex)) {
                slot.listener?.let(built::removeListener)
                runCatching { built.release() }
                runCatching { remote.close() }
                return@post
            }
            tvPlaybackPool.slots[key]=slot
            if(com.hgtv.star.tv.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-POOL",
                    "insert key=$key page=$pageIndex series=$seriesId episode=$episodeId "+
                        "screen=$screen current=${currentPlaybackPageIndex()}",
                )
            }
            trimTvPlaybackPool(currentPlaybackPageIndex())
            attachTvPoolSlotToPage(slot)
            scheduleTvPlaybackPoolNeighbors()
        }
    }
}

private fun TvMainActivity.tvPoolNeighbor(pageIndex: Int): Boolean {
    if(screen!="recommend" && screen!="player") return false
    val count=if(screen=="recommend") recommendItems.size else detail?.episodes?.size ?: 0
    if(pageIndex !in 0 until count) return false
    return kotlin.math.abs(pageIndex-currentPlaybackPageIndex())<=1
}

private fun TvMainActivity.tvPoolTargetNeighbor(pageIndex: Int): Boolean =
    pageIndex!=currentPlaybackPageIndex() && tvPoolNeighbor(pageIndex)

private fun TvMainActivity.tvPoolSlotMatches(slot: TvPlaybackPoolSlot,index: Int): Boolean =
    when(screen) {
        "recommend" -> recommendItems.getOrNull(index)?.id==slot.seriesId
        "player" -> detail?.episodes?.getOrNull(index)==slot.episodeId
        else -> false
    }
