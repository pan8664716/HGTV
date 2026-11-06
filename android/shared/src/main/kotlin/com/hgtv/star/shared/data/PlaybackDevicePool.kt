// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.content.Context
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.PlaybackDeviceLease
import com.hgtv.star.shared.debug.Diagnostics
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 播放接口的一次性匿名设备池。
 *
 * 单线程按固定节奏注册新的匿名设备，把完整身份（档案 + device_id/iid + Cookie）
 * 追加到磁盘。播放模型请求真正发出前消费队首一条，并先从磁盘删除再使用。
 * 注册与消费共用一把进程内锁，但网络请求在锁外执行，不阻塞播放取地址。
 */
object PlaybackDevicePool {
    private const val INTERVAL_MS=3_000L
    private const val MAX_QUEUE_SIZE=200
    private const val FILE_NAME="playback-device-pool-v1.jsonl"
    private const val FAILURE_LOG_GAP_MS=60_000L

    private val lock=ReentrantLock()
    private val roomAvailable=lock.newCondition()
    private val started=AtomicBoolean(false)
    private val registerRepository by lazy { ContentRepository(PlaybackTransport.client) }

    @Volatile private var poolFile: File?=null
    @Volatile private var lastFailureLogAt=0L

    /** 进程启动时调用一次；重复调用安全。 */
    fun start(context: Context) {
        val app=context.applicationContext
        lock.withLock {
            if(poolFile==null) poolFile=File(app.filesDir,FILE_NAME)
        }
        ContentRepository.playbackDeviceLeaseProvider={ consume() }
        if(!started.compareAndSet(false,true)) return
        Thread({ runLoop(app) },"hgtv-playback-device-pool").apply {
            isDaemon=true
            start()
        }
    }

    private fun runLoop(context: Context) {
        while(!Thread.currentThread().isInterrupted) {
            if(!awaitRoom()) return
            if(AppRuntime.library(context).manualDeviceId().isNotBlank()) {
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch(_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }
            val startedAt=System.currentTimeMillis()
            runCatching { registerOne(context) }
                .onFailure(::reportFailure)
            val waitMs=INTERVAL_MS-(System.currentTimeMillis()-startedAt)
            if(waitMs<=0L) continue
            try {
                Thread.sleep(waitMs)
            } catch(_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * 队列达到上限时暂停注册。消费线程删掉一条后会唤醒这里，
     * 后台线程再补注册一条，随后恢复原来的 3 秒节奏。
     */
    private fun awaitRoom(): Boolean = lock.withLock {
        while(queuedCountLocked()>=MAX_QUEUE_SIZE) {
            try {
                roomAvailable.await()
            } catch(_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        true
    }

    private fun queuedCountLocked(): Int {
        val file=poolFile ?: return 0
        if(!file.isFile) return 0
        return runCatching {
            file.useLines(Charsets.UTF_8) { lines -> lines.count { it.isNotBlank() } }
        }.getOrElse { problem ->
            reportFailure(problem)
            0
        }
    }

    private fun registerOne(context: Context) {
        val profile=AppRuntime.library(context).freshPlaybackDeviceProfile()
        if(profile["cdid"].orEmpty().isBlank() || profile["openudid"].orEmpty().isBlank()) {
            throw IOException("一次性播放设备档案为空")
        }
        val registered=registerRepository.registerAnonymousDevice(profile)
            ?: throw IOException("一次性播放设备注册失败")
        val completeProfile=LinkedHashMap(profile).apply {
            put("device_id",registered.deviceId)
            put("iid",registered.installId)
            put("install_id",registered.installId)
            registered.klinkEgdi.takeIf { it.isNotBlank() }?.let { put("klink_egdi",it) }
            registered.deviceToken.takeIf { it.isNotBlank() }?.let { put("device_token",it) }
        }
        val cookies=LinkedHashMap(registered.cookies).apply {
            put("install_id",registered.installId)
        }
        append(PlaybackDeviceLease(completeProfile,cookies))
    }

    /** 消费队首一条；返回前已从磁盘删除。队列为空或文件损坏时返回 null。 */
    private fun consume(): PlaybackDeviceLease? {
        return lock.withLock {
            val file=poolFile ?: return null
            if(!file.isFile) return null
            val lines=runCatching { file.readLines(Charsets.UTF_8) }.getOrElse { problem ->
                reportFailure(problem)
                return null
            }
            if(lines.isEmpty()) {
                file.delete()
                return null
            }
            var consumed: PlaybackDeviceLease?=null
            val remaining=ArrayList<String>(lines.size)
            for(line in lines) {
                if(line.isBlank()) continue
                if(consumed==null) {
                    val lease=decode(line)
                    if(lease!=null) {
                        consumed=lease
                        continue
                    }
                }
                remaining+=line
            }
            runCatching { replaceFile(file,remaining) }.onFailure { problem ->
                reportFailure(problem)
                return null
            }
            roomAvailable.signalAll()
            return consumed
        }
    }

    private fun append(lease: PlaybackDeviceLease) {
        val line=encode(lease).toString()
        lock.withLock {
            val file=poolFile ?: throw IOException("一次性播放设备池尚未初始化")
            file.parentFile?.mkdirs()
            FileOutputStream(file,true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
                out.fd.sync()
            }
        }
    }

    private fun replaceFile(file: File, lines: List<String>) {
        if(lines.isEmpty()) {
            if(file.exists() && !file.delete()) throw IOException("一次性播放设备池清理失败")
            return
        }
        val temporary=File(file.parentFile,file.name+".tmp")
        FileOutputStream(temporary,false).use { out ->
            for(line in lines) {
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
            }
            out.flush()
            out.fd.sync()
        }
        if(temporary.renameTo(file)) return
        if(file.exists() && !file.delete()) throw IOException("一次性播放设备池替换失败")
        if(!temporary.renameTo(file)) throw IOException("一次性播放设备池替换失败")
    }

    private fun encode(lease: PlaybackDeviceLease): JSONObject = JSONObject()
        .put("created_at",System.currentTimeMillis())
        .put("profile",JSONObject(lease.profile))
        .put("cookies",JSONObject(lease.cookies))

    private fun decode(line: String): PlaybackDeviceLease? = runCatching {
        val json=JSONObject(line)
        val profileObject=json.optJSONObject("profile") ?: return@runCatching null
        val cookieObject=json.optJSONObject("cookies") ?: JSONObject()
        val profile=buildMap {
            for(key in profileObject.keys()) {
                profileObject.optString(key).takeIf { it.isNotBlank() }?.let { put(key,it) }
            }
        }
        val cookies=buildMap {
            for(key in cookieObject.keys()) {
                cookieObject.optString(key).takeIf { it.isNotBlank() }?.let { put(key,it) }
            }
        }
        if(profile["device_id"].orEmpty().isBlank() || profile["iid"].orEmpty().isBlank()) {
            return@runCatching null
        }
        PlaybackDeviceLease(profile,cookies)
    }.getOrNull()

    private fun reportFailure(problem: Throwable) {
        val now=System.currentTimeMillis()
        if(now-lastFailureLogAt<FAILURE_LOG_GAP_MS) return
        lastFailureLogAt=now
        Diagnostics.warn("HGTV-DEVICE-POOL","一次性播放设备池操作失败",problem)
    }
}
