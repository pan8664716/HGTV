// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.content.Context
import android.util.Log
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.PlaybackBackupDevice
import com.hgtv.star.core.registerAnonymousDevice
import com.hgtv.star.shared.debug.DebugLog
import com.hgtv.star.shared.debug.StartupTrace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 持久备用设备。
 *
 * 首次运行当前版本时在后台注册 [TARGET] 台备用设备并落盘；后续启动只读文件，
 * 数量齐全时不会再注册。每次主设备 `HTTP 200 + 空 body` 只取一台备用设备试一次，
 * 不覆盖主设备身份；profile / Cookie 整套替换，绝不与主身份混参。
 */
object PlaybackDeviceBackups {
    private const val TARGET=1
    private const val FILE_VERSION=1
    private const val FILE_NAME="playback-backup-devices-v1.json"

    private val lock=Any()
    private val started=AtomicBoolean(false)
    private var devices: List<PlaybackBackupDevice> = emptyList()
    private var cursor=0

    fun start(context: Context) {
        val app=context.applicationContext
        ContentRepository.playbackBackupDevices={ snapshot() }
        if(!started.compareAndSet(false,true)) return
        Thread({ ensureReady(app) },"hgtv-playback-backups").apply {
            isDaemon=true
            start()
        }
        StartupTrace.mark("backupDevices.worker.started")
    }

    private fun ensureReady(context: Context) {
        val startedAt=StartupTrace.now()
        StartupTrace.mark("backupDevices.ensure.start")
        try {
        val file=File(context.filesDir,FILE_NAME)
        val stored=load(file)
        val loaded=stored.take(TARGET)
        synchronized(lock) {
            devices=loaded
            cursor=0
        }
        if(stored.size>TARGET) save(file,loaded)
        val library=AppRuntime.library(context)
        if(!library.isLoaded) {
            val ready=CountDownLatch(1)
            library.loadAsync(
                onFailure={ ready.countDown() },
                onReady={ ready.countDown() },
            )
            if(!ready.await(8,TimeUnit.SECONDS) || !library.isLoaded) {
                Log.w("HGTV-BACKUP","backup registration skipped: library not ready")
                return
            }
        }
        val repository=ContentRepository(
            PlaybackTransport.client.newBuilder()
                .callTimeout(20,TimeUnit.SECONDS)
                .build()
        )
        // 顺序固定：先保证主设备注册成功，再准备唯一一台备用设备。
        if(!library.deviceRegistered()) {
            val main=DeviceRegistration.registerOnce(
                library=library,
                repository=repository,
                force=false,
                stage="device_register_primary",
                detail="PlaybackDeviceBackups",
            )
            if(main==DeviceRegistration.Outcome.FAILED) {
                Log.w("HGTV-BACKUP","backup registration skipped: primary device not ready")
                return
            }
        }
        if(loaded.size>=TARGET) {
            Log.i("HGTV-BACKUP","backup devices ready count=${loaded.size}")
            return
        }
        val current=synchronized(lock) { devices.toMutableList() }
        for(index in current.size until TARGET) {
            val profile=library.freshPlaybackBackupProfile()
            if(profile.isEmpty()) {
                Log.w("HGTV-BACKUP","backup registration skipped: empty device profile")
                return
            }
            val registered=runCatching {
                repository.registerAnonymousDevice(profile)
            }.getOrNull()
            if(registered==null) {
                Log.w("HGTV-BACKUP","backup registration failed index=${index+1}/$TARGET")
                break
            }
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
            current+=PlaybackBackupDevice(registered.deviceId,completeProfile,cookies)
            synchronized(lock) {
                devices=current.toList()
                cursor=0
            }
            save(file,current)
            Log.i(
                "HGTV-BACKUP",
                "backup device ready index=${index+1}/$TARGET id=${registered.deviceId.takeLast(6)}",
            )
        }
        DebugLog.record("设备","备用设备 ${current.size}/$TARGET 已保存",force=true)
        } finally {
            StartupTrace.done("backupDevices.ensure",startedAt)
        }
    }

    /** 每次主身份空响应只取唯一一台备用设备，试完一次就回到主身份。 */
    private fun snapshot(): List<PlaybackBackupDevice> {
        synchronized(lock) {
            if(devices.isEmpty()) return emptyList()
            val device=devices[cursor % devices.size]
            cursor=(cursor+1)%devices.size
            return listOf(device)
        }
    }

    private fun load(file: File): List<PlaybackBackupDevice> {
        if(!file.isFile) return emptyList()
        return runCatching {
            val root=JSONObject(file.readText(Charsets.UTF_8))
            if(root.optInt("version")!=FILE_VERSION) return emptyList()
            val rows=root.optJSONArray("devices") ?: JSONArray()
            buildList {
                for(index in 0 until rows.length()) {
                    val row=rows.optJSONObject(index) ?: continue
                    val profile=stringMap(row.optJSONObject("profile"))
                    val cookies=stringMap(row.optJSONObject("cookies"))
                    val id=profile["device_id"].orEmpty()
                    if(id.isBlank() || profile["iid"].orEmpty().isBlank()) continue
                    add(PlaybackBackupDevice(id,profile,cookies))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(file: File,items: List<PlaybackBackupDevice>) {
        runCatching {
            val rows=JSONArray()
            items.take(TARGET).forEach { device ->
                rows.put(JSONObject().apply {
                    put("profile",JSONObject(device.profile))
                    put("cookies",JSONObject(device.cookies))
                })
            }
            val root=JSONObject()
                .put("version",FILE_VERSION)
                .put("updated_at",System.currentTimeMillis())
                .put("devices",rows)
            val tmp=File(file.path+".tmp")
            tmp.writeText(root.toString(),Charsets.UTF_8)
            file.delete()
            if(!tmp.renameTo(file)) throw IllegalStateException("备用设备文件替换失败")
        }.onFailure { problem ->
            Log.w("HGTV-BACKUP","backup file save failed",problem)
        }
    }

    private fun stringMap(value: JSONObject?): Map<String,String> {
        if(value==null) return emptyMap()
        return buildMap {
            for(key in value.keys()) {
                value.optString(key).takeIf { it.isNotBlank() }?.let { put(key,it) }
            }
        }
    }
}
