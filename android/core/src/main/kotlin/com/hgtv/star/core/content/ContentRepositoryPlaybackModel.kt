// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

    /**
     * 取一集的播放模型（`video_model`）。
     *
     * 平台要求请求里的 `device_id`、`iid`、签名密文和注册 Cookie 来自同一台设备。
     * 2026-11-06 实测：注册下发的 `device_id` 返回 `code 0` 和完整模型，任意随机
     * 15 位数字则稳定返回 `200 + 0 字节`。因此空响应必须按设备身份失效处理，
     * 由界面层重新注册后再试，不能继续伪造新的 `device_id`。
     *
     * 候选表只保留「新路由 + 老路径」两条并串行探测；第一条业务失败才试第二条，
     * 设备身份失效则立即停止探测并交给自愈流程。
     */
    internal fun ContentRepository.videoModelImpl(
        id: String,
        deadline: Long,
        playback: PlaybackProfile,
        queue: List<String> = emptyList(),
    ): JSONObject {
        // 长时间驻留进程里配置也会过期；这里只排队后台刷新，不等待。
        refreshTncAsync()
        // TNC 下发候选域名；最近成功的排第一。播放接口最多在 6 秒预算内试前三条。
        val hosts=TncConfigStore.orderedApiHosts().take(3)
        // 播放器打开用 `multi_video_model/player` 取当前集 + 后 14 集；
        // 单集接口只作为兼容兜底，preload 留给首帧稳定后的后台补货。
        // 新匿名设备连续发单集请求时，第二发起会被网关静默丢弃（HTTP 200 + 0 字节）。
        val candidates=listOf(
            "/novel/player/video_model/v1/",
            "/novel/player/multi_video_model/v1/"
        )
        // 播放接口对**匿名设备**也放行（官方 App 冷启动未登录即可播放，见 HAR），
        // 前提是这台设备已在服务端注册过（见 [registerDevice]）：未注册回 `101000 服务异常`
        // 或 `200 + 0 字节`，注册后回 `code 0` + 完整播放模型。
        // 播放与其它业务请求共用当前主身份；登录后 Cookie 也切到账号设备。
        val cookie=primaryCookieHeader().takeIf { it.isNotEmpty() }
        // 记住的那条路径排到最前（见 [preferredModelPath]）。
        val ordered=if(preferredModelPath.isEmpty()) candidates
            else candidates.sortedByDescending { it==preferredModelPath }
        val scope=RequestScope.current()
        // 同一集刚取过就复用：推荐页预热、正片起播、下一集预取会**同时**要同一集的地址，
        // 各发一轮就是三份并发（见下面的单飞闸门）。缓存很短（[MODEL_CACHE_MS]），
        // 地址本身小时级有效，这里只是用来把「同一刻的重复请求」合并掉。
        // 键里带播放档案、[deviceLevel] 与 [modelCacheGen]：不同类型 / 平台不能复用模型。
        cachedModel(id,playback)?.let { return it }
        // **进程级单飞**：这条接口对并发极敏感（20 并发 0 命中、单发 50~80% 命中），
        // 而点一次播放就可能同时存在主实例 / 预取实例 / 推荐页预热实例三份请求。
        // 因此整个取地址段串行化：后来的等前一个结束，然后再查一次上面那份缓存。
        ContentRepository.foregroundModelWaiters.incrementAndGet()
        // 前台开始取当前集时，后台 preload 立即让路：取消它在途请求并释放闸门，
        // 不让用户点下一集时排在后台补货后面。
        ContentRepository.backgroundModelScope?.cancel()
        try {
            val gate=ContentRepository.modelGate
            // 等闸门也用余额兜住：三份实例同时起播时后来者要排队，但绝不允许排到整段预算之外
            //（[MODEL_GATE_TIMEOUT_MS] 那 25 秒是给「上一发正在慢速完成」留的余量，
            // 取地址整段已经被 [ADDRESS_DEADLINE_MS] 限死，这里只是个不会先到期的兜底）。
            val waitMs=remainingOf(deadline).coerceIn(1L,ContentRepository.MODEL_GATE_TIMEOUT_MS)
            if(!gate.tryAcquire(waitMs,TimeUnit.MILLISECONDS)) {
                throw addressTimeout()
            }
            try {
                cachedModel(id,playback)?.let { return it }
                // 播放器打开取当前集 + 后 14 集：官方同一批可返回 15 个模型，
                // 覆盖短剧常见的快速跳到第 8 / 15 集，避免第二次请求撞设备额度。
                // 更远的集交给首帧稳定后的 preload。
                val batchIds=(listOf(id)+queue)
                    .filter { it.matches(Regex("[0-9]{1,30}")) }
                    .distinct()
                    .take(ContentRepository.PLAYER_MODEL_BATCH_LIMIT)
                var batchModel: JSONObject?=null
                for((hostIndex,host) in hosts.withIndex()) {
                    if(hostIndex>0) {
                        NetLog.retry("model_batch",hostIndex+1,hosts.size,"previous host returned no model")
                    }
                    if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) break
                    val hostDeadline=minOf(deadline,clock()+ContentRepository.MODEL_HOST_ATTEMPT_MS)
                    try {
                        val models=tryBatchModel(
                            "https://$host",
                            ContentRepository.PLAYER_MODEL_PATH,
                            "player",
                            batchIds,
                            cookie,
                            hostDeadline,
                            playback,
                        )
                        rememberModels(models,playback)
                        batchModel=models[id]
                        if(batchModel!=null) {
                            TncConfigStore.markHostSuccess(host)
                            NetLog.emit("model batch host=$host accepted")
                            break
                        }
                    } catch(e: DeviceNotRegisteredException) {
                        throw e
                    } catch(e: AddressTimeoutException) {
                        NetLog.emit("model batch host=$host timeout")
                    } catch(e: IOException) {
                        NetLog.emit("model batch host=$host failed ${e.javaClass.simpleName}")
                    }
                }
                if(batchModel!=null) return batchModel
                val model=fetchModel(id,scope,ordered,hosts,cookie,deadline,playback)
                rememberModel(id,playback,model)
                return model
            } finally {
                gate.release()
            }
        } finally {
            ContentRepository.foregroundModelWaiters.decrementAndGet()
        }
    }

    /** 播放模型的合并缓存键：集 + 播放档案 + 当前档位 + 身份代次（见 [videoModelImpl]）。 */
    private fun ContentRepository.modelCacheKey(id: String,playback: PlaybackProfile)=
        "$id@${playback.contentType}:${playback.videoPlatform}@$deviceLevel@g${ContentRepository.modelCacheGen}"

    private fun ContentRepository.cachedModel(id: String,playback: PlaybackProfile): JSONObject? {
        if(modelCacheTtl<=0L) return null
        val key=modelCacheKey(id,playback)
        val cached=modelCache[key] ?: return null
        if(System.currentTimeMillis()-cached.first<=modelCacheTtl) return cached.second
        modelCache.remove(key)
        return null
    }

    private fun ContentRepository.rememberModel(
        id: String,
        playback: PlaybackProfile,
        model: JSONObject,
        at: Long = System.currentTimeMillis(),
    ) {
        if(modelCacheTtl<=0L) return
        val key=modelCacheKey(id,playback)
        if(modelCache.size>=ContentRepository.MODEL_CACHE_LIMIT && !modelCache.containsKey(key)) {
            modelCache.minByOrNull { it.value.first }?.key?.let(modelCache::remove)
        }
        modelCache[key]=at to model
    }

    private fun ContentRepository.rememberModels(models: Map<String,JSONObject>,playback: PlaybackProfile) {
        if(models.isEmpty() || modelCacheTtl<=0L) return
        val at=System.currentTimeMillis()
        models.forEach { (videoId,model) -> rememberModel(videoId,playback,model,at) }
    }

    /**
     * [videoModelImpl] 里真正打请求的那一段（持锁执行，见那里的 `modelGate`）。
     *
     * 平台只接受**注册下发的 `device_id`**。空响应先按额度/档位问题处理：
     * 4 档升 5 重发一次；5 档仍为空才视为设备身份失效，交给界面层重注册。
     */
    private fun ContentRepository.fetchModel(
        id: String,
        scope: RequestScope?,
        ordered: List<String>,
        hosts: List<String>,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
    ): JSONObject {
        var failure: IOException?=null
        hostLoop@ for((hostIndex,host) in hosts.withIndex()) {
            if(hostIndex>0) {
                NetLog.retry("model_host",hostIndex+1,hosts.size,failure?.message.orEmpty())
            }
            val hostDeadline=minOf(deadline,clock()+ContentRepository.MODEL_HOST_ATTEMPT_MS)
            for((pathIndex,path) in ordered.withIndex()) {
                if(pathIndex>0) {
                    NetLog.retry("model_path",pathIndex+1,ordered.size,"previous path returned no model")
                }
                // 余额用完就停：不要再往下试下一条路径 / 域名。
                if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) {
                    throw failure ?: addressTimeout()
                }
                val model=try {
                    RequestScope.withScope(scope) {
                        tryModelPath("https://$host",path,id,cookie,hostDeadline,playback)
                    }
                } catch(e: IOException) {
                    // 用户切走了（取消域）：这不是「这条路不通」，不该再往下试。
                    if(isInterruption(e) || e.message=="请求已取消") throw e
                    // 设备身份失效与路径 / 域名无关：换一条只会再被拒一次。
                    if(e is DeviceNotRegisteredException) throw e
                    failure=e
                    if(e is AddressTimeoutException) continue@hostLoop
                    null
                }
                if(model!=null) {
                    preferredModelPath=path
                    TncConfigStore.markHostSuccess(host)
                    NetLog.emit("model host=$host path=$path accepted")
                    return model
                }
            }
        }
        // 全失败：把记住的那条也作废，下一次从候选表第一条重新开始——
        // 否则记着的那条死路会一直排在队首，每次起播先白花一次往返。
        preferredModelPath=""
        throw failure ?: IOException("播放接口暂不可用")
    }

    /**
     * 在一条路径上请求一次播放模型。
     *
     * 空响应先由 [probeModelPath] 升到 5 档重试；仍为空才抛出
     * [DeviceNotRegisteredException]，让两端现有的设备自愈流程重新注册。
     */
    private fun ContentRepository.tryModelPath(
        host: String,
        path: String,
        id: String,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
    ): JSONObject {
        if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        return probeModelPath(host,path,id,cookie,deadline,playback)
    }

    /** 播放请求使用当前唯一的主身份；登录后以账号设备为准。 */
    internal fun ContentRepository.modelProfile(): Map<String,String> {
        val identity=primaryIdentity()
        val registeredId=if(identity.account) {
            identity.profile["device_id"].orEmpty()
        } else {
            deviceIdentity["device_id"].orEmpty()
        }
        val registeredIid=if(identity.account) {
            identity.profile["iid"].orEmpty()
                .ifBlank { identity.profile["install_id"].orEmpty() }
        } else {
            deviceIdentity["iid"].orEmpty()
        }
        // 未登录时必须由本机注册结果提供身份，不能拿 VendorConstants.device 的内置旧 ID
        // 冒充已注册设备。登录态则必须有账号设备自己的 device_id + iid/install_id。
        if(registeredId.isBlank() || registeredIid.isBlank()) {
            throw DeviceNotRegisteredException()
        }
        val base=LinkedHashMap(VendorConstants.device).apply {
            putAll(identity.profile)
            put("device_id",registeredId)
            put("iid",registeredIid)
            putAll(PROTOCOL_VALUES)
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) this[key]="73932"
            this["version_name"]="7.3.9.32"
        }
        return modelQueryProfile(base)
    }

    /** 官方 `video_model` query 的字段集合与顺序；设备档案里的其它字段不带进播放请求。 */
    internal fun ContentRepository.modelQueryProfile(values: Map<String,String>): LinkedHashMap<String,String> =
        linkedMapOf<String,String>().apply {
            for(key in ContentRepository.MODEL_QUERY_KEYS) values[key]?.takeIf { it.isNotBlank() }?.let { put(key,it) }
        }

    /**
     * 主设备拿到 `HTTP 200 + 空 body` 后，只取一台备用设备试一次。
     *
     * 只返回成功的播放模型，不改主设备身份、不落盘备用设备的使用结果。
     */
    private fun ContentRepository.tryBackupModel(
        host: String,
        path: String,
        id: String,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
    ): JSONObject? {
        val backups=runCatching { ContentRepository.playbackBackupDevices?.invoke() }.getOrNull().orEmpty()
        for((index,backup) in backups.withIndex()) {
            if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) break
            if(index>0) {
                NetLog.retry("model_backup",index+1,backups.size,"previous backup device returned no model")
            }
            try {
                val model=probeModelPath(host,path,id,cookie,deadline,playback,backup)
                NetLog.emit("model backup accepted id=${backup.id.takeLast(6)}")
                return model
            } catch(e: IOException) {
                if(isInterruption(e) || e.message=="请求已取消") throw e
            }
        }
        return null
    }

    /**
     * 作废当前设备签发的播放模型缓存。
     *
     * 重试时设备号本身不能伪造，只能等界面层重新注册；这里只保证下一次取流
     * 不会命中旧身份留下的模型。
     */
    internal fun ContentRepository.invalidateModelCache(): String {
        synchronized(ContentRepository.deviceRotateLock) { ContentRepository.modelCacheGen++ }
        return deviceIdentity["device_id"].orEmpty()
    }

    /**
     * 在 [path] 上打**一次**播放模型请求。
     *
     * 2026-11-06 实测：同一个请求用注册下发的 `device_id` 返回 `code 0` 和完整模型，
     * 改成任意随机 15 位数字则稳定返回 `200 + 0 字节`。因此空响应先升 5 档重发；
     * 5 档仍为空时再按设备身份失效处理，交给界面层重新注册。
     */
    private fun ContentRepository.probeModelPath(
        host: String,
        path: String,
        id: String,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
        backup: PlaybackBackupDevice? = null,
    ): JSONObject {
        // 这一发最多能用多少时间 = 整段剩余预算（见 [ADDRESS_DEADLINE_MS]）。
        // 全部用同一份「剩余预算」派生 client，因此预算是**整段**上限，不是每一发各算一次。
        val budget=remainingOf(deadline)
        if(budget<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        val body=ContentPlaybackParser.modelBody(id,path,playback,deviceLevel)
        val payload=java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(body.toByteArray()) }
        }.toByteArray()
        waitModelSlot()
        if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        // 注册档案包含大量仅供 device_register 使用的字段。播放接口只接受
        // MODEL_QUERY_KEYS 这一组；整份档案直接交给 signer 会把 openudid、
        // profile-format、device_token 等全部拼进 query，服务端随即回 200 + 0 字节。
        val profile=backup?.profile?.let { backupProfile ->
            modelQueryProfile(LinkedHashMap(VendorConstants.device).apply {
                putAll(backupProfile)
                putAll(PROTOCOL_VALUES)
                for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) this[key]="73932"
                this["version_name"]="7.3.9.32"
            })
        } ?: modelProfile()
        val effectiveCookie=backup?.cookieHeader ?: cookie
        // 档案要同时交给 query 与密文（`x-medusa` 里编了 device_id），否则服务端回 110001。
        val signed=signer.sign(host+path,profile,payload,deviceOverride=profile)
        val extra=linkedMapOf<String,String>()
        if(effectiveCookie!=null) extra["Cookie"]=effectiveCookie
        extra["Content-Encoding"]="gzip"
        // 视频接口按官方 7.3.9.32 抓包单独覆盖：
        // * passport-sdk-version=5051452（不是内容接口的 6031190）
        // * lc=101 / x-ss-dp=8662
        // * 官方没有空 x-tt-dt，也没有 x-tt-request-tag
        val videoHeaders=LinkedHashMap(signed.headers).apply {
            remove("x-tt-dt")
            remove("x-tt-request-tag")
            put("passport-sdk-version","5051452")
            put("lc","101")
            put("x-ss-dp","8662")
            put("x-vc-bdturing-sdk-version","4.0.3.cn")
            put("x-tt-trace-id",VendorConstants.traceId())
            putAll(extra)
        }
        val request=signed.copy(headers=videoHeaders)
        var stable=false
        val response=text(
            signed.url,
            request,
            client=addressClient(budget),
            storeRegionHeaders=false,
        ) { r -> stable=r.header("tt_stable")=="1" }
        // 网关挡掉请求时是 200 + 0 字节：业务码取不到会默认成 0，必须先判空。
        if(response.isBlank()) {
            NetLog.emit("model path=$path type=${playback.contentType}/${playback.videoPlatform} level=$deviceLevel id=${profile["device_id"]?.takeLast(6)} backup=${backup!=null} bytes=0 stable=$stable")
            if(backup!=null) throw IOException("备用设备未返回播放模型")
            // 主设备空响应先用备用设备顶替；备用池不可用时再走 4→5 档与身份自愈。
            tryBackupModel(host,path,id,cookie,deadline,playback)?.let { return it }
            // 4 档空响应先升 5 重发一次。若 5 仍为空，再按设备身份失效交给自愈流程。
            if(deviceLevel<5) {
                NetLog.retry("model_device_level",2,2,"empty model response at device_level=$deviceLevel")
                escalateDeviceLevel()
                return probeModelPath(host,path,id,cookie,deadline,playback)
            }
            noteDeviceRejected()
            invalidateModelCache()
            throw DeviceNotRegisteredException("播放接口暂不可用（设备身份已失效）")
        }
        val data=JSONObject(response)
        val code=data.optInt("Code",data.optInt("code",0))
        NetLog.emit("model path=$path type=${playback.contentType}/${playback.videoPlatform} level=$deviceLevel id=${profile["device_id"]?.takeLast(6)} backup=${backup!=null} bytes=${response.length} code=$code")
        // 业务码非 0 就是这一条路不通（110001 是旧路径的「未知异常」，100001 是参数错误）。
        // `101000 服务异常` 正是「这台设备没在服务端注册过」那一条，单独记一笔：
        // 界面层据此换一份身份重注册，而不是让用户看到「所有影片都放不了」。
        if(code==101000 && backup==null) { noteDeviceRejected(); invalidateModelCache() }
        if(code!=0) {
            if(code==101000 && backup!=null) throw IOException("备用设备不可用")
            throw IOException("播放接口暂不可用（${code}）")
        }
        val model=ContentPlaybackParser.videoModelOf(data)
            ?: throw IOException("播放接口暂不可用（没有播放模型）")
        return model
    }

    /**
     * 打一次官方批量播放模型请求，并解析 `data.<video_id>.video_model`。
     *
     * 批量接口的空响应先升到 5 档重发一次；5 档仍为空才按设备身份失效处理。
     * 非空但业务失败时由调用方走单集兼容兜底。
     */
    private fun ContentRepository.tryBatchModel(
        host: String,
        path: String,
        scene: String,
        ids: List<String>,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
        backup: PlaybackBackupDevice? = null,
    ): Map<String,JSONObject> {
        if(ids.isEmpty()) return emptyMap()
        val budget=remainingOf(deadline)
        if(budget<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        val body=ContentPlaybackParser.batchModelBody(ids,playback,scene,deviceLevel)
        val payload=java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(body.toByteArray()) }
        }.toByteArray()
        waitModelSlot()
        if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        val profile=backup?.profile?.let { backupProfile ->
            modelQueryProfile(LinkedHashMap(VendorConstants.device).apply {
                putAll(backupProfile)
                putAll(PROTOCOL_VALUES)
                for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) this[key]="73932"
                this["version_name"]="7.3.9.32"
            })
        } ?: modelProfile()
        val effectiveCookie=backup?.cookieHeader ?: cookie
        val signed=signer.sign(host+path,profile,payload,deviceOverride=profile)
        val extra=linkedMapOf<String,String>()
        if(effectiveCookie!=null) extra["Cookie"]=effectiveCookie
        extra["Content-Encoding"]="gzip"
        val videoHeaders=LinkedHashMap(signed.headers).apply {
            remove("x-tt-dt")
            remove("x-tt-request-tag")
            put("passport-sdk-version","5051452")
            put("lc","101")
            put("x-ss-dp","8662")
            put("x-vc-bdturing-sdk-version","4.0.3.cn")
            put("x-tt-trace-id",VendorConstants.traceId())
            putAll(extra)
        }
        val request=signed.copy(headers=videoHeaders)
        var stable=false
        val response=text(
            signed.url,
            request,
            client=addressClient(budget),
            storeRegionHeaders=false,
        ) { r -> stable=r.header("tt_stable")=="1" }
        if(response.isBlank()) {
            NetLog.emit("model batch path=$path scene=$scene type=${playback.contentType}/${playback.videoPlatform} level=$deviceLevel id=${profile["device_id"]?.takeLast(6)} backup=${backup!=null} bytes=0 stable=$stable")
            if(backup!=null) throw IOException("备用设备未返回批量播放模型")
            tryBackupBatch(host,path,scene,ids,cookie,deadline,playback)?.let { return it }
            if(deviceLevel<5) {
                escalateDeviceLevel()
                return tryBatchModel(host,path,scene,ids,cookie,deadline,playback)
            }
            noteDeviceRejected()
            invalidateModelCache()
            throw DeviceNotRegisteredException("播放接口暂不可用（设备身份已失效）")
        }
        val data=JSONObject(response)
        val code=data.optInt("Code",data.optInt("code",0))
        val models=ContentPlaybackParser.parseBatchModels(data,ids)
        NetLog.emit("model batch path=$path scene=$scene type=${playback.contentType}/${playback.videoPlatform} level=$deviceLevel id=${profile["device_id"]?.takeLast(6)} backup=${backup!=null} bytes=${response.length} code=$code models=${models.size}/${ids.size}")
        if(code==101000 && backup==null) {
            noteDeviceRejected()
            invalidateModelCache()
            throw DeviceNotRegisteredException("播放接口暂不可用（设备身份已失效）")
        }
        if(code!=0) {
            if(code==101000 && backup!=null) throw IOException("备用设备不可用")
            throw IOException("播放接口暂不可用（${code}）")
        }
        return models
    }

    private fun ContentRepository.tryBackupBatch(
        host: String,
        path: String,
        scene: String,
        ids: List<String>,
        cookie: String?,
        deadline: Long,
        playback: PlaybackProfile,
    ): Map<String,JSONObject>? {
        val backups=runCatching { ContentRepository.playbackBackupDevices?.invoke() }.getOrNull().orEmpty()
        for(backup in backups) {
            if(remainingOf(deadline)<ADDRESS_MIN_BUDGET_MS) break
            try {
                val models=tryBatchModel(host,path,scene,ids,cookie,deadline,playback,backup)
                if(models.isNotEmpty()) {
                    NetLog.emit("model batch backup accepted id=${backup.id.takeLast(6)} models=${models.size}/${ids.size}")
                    return models
                }
            } catch(e: IOException) {
                if(isInterruption(e) || e.message=="请求已取消") throw e
            }
        }
        return null
    }

    /**
     * 两次播放模型请求之间至少隔 [MODEL_SPACING_MS]，**进程级**节流。
     *
     * 必须先持有 `modelGate`（见 [videoModelImpl]）：只有全局串行时这个时间戳才说得清
     * 「上一次是什么时候打的」。第一次不等待（时间戳初值 0）。
     *
     * 这里只挡异常连打，不承担设备额度控制；身份失效由重注册处理。
     */
    private fun ContentRepository.waitModelSlot() {
        if(modelSpacing<=0L) return
        val wait=ContentRepository.modelLastAttemptAt.get()+modelSpacing-System.currentTimeMillis()
        if(wait>0L) {
            try {
                Thread.sleep(wait)
            } catch(e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("请求已取消")
            }
        }
        ContentRepository.modelLastAttemptAt.set(System.currentTimeMillis())
    }

    /**
     * 用一次官方批量接口预取一组播放模型。
     *
     * 推荐页与正式播放页的批量窗口由界面层决定；这里只负责把整组 ID 放进同一次请求，
     * 并把响应写进共享模型缓存，后续 [stream] 直接命中。
     */
    internal fun ContentRepository.prefetchModelsImpl(
        ids: List<String>,
        playback: PlaybackProfile,
    ): Int {
        val batchIds=ids
            .filter { it.matches(Regex("[0-9]{1,30}")) }
            .distinct()
            // preload 只补当前缓存缺口，避免把 player 已经拿到的当前集 / 后 2 集再请求一遍。
            .filter { cachedModel(it,playback)==null }
            .take(ContentRepository.PRELOAD_MODEL_LIMIT)
        if(batchIds.isEmpty()) return 0
        val deadline=clock()+ADDRESS_DEADLINE_MS
        val gate=ContentRepository.modelGate
        val waitMs=remainingOf(deadline).coerceIn(1L,ContentRepository.MODEL_GATE_TIMEOUT_MS)
        if(!gate.tryAcquire(waitMs,TimeUnit.MILLISECONDS)) {
            throw addressTimeout()
        }
        val scope=RequestScope()
        try {
            // 前台已经在等模型时，后台这次直接跳过；下次首帧稳定后的调度会再补。
            ContentRepository.backgroundModelScope=scope
            if(ContentRepository.foregroundModelWaiters.get()>0) return 0
            val cookie=primaryCookieHeader().takeIf { it.isNotEmpty() }
            val host=TncConfigStore.preferredHost()
            val models=RequestScope.withScope(scope) {
                tryBatchModel(
                    "https://$host",
                    ContentRepository.BATCH_MODEL_PATH,
                    "preload",
                    batchIds,
                    cookie,
                    deadline,
                    playback,
                )
            }
            if(models.isNotEmpty()) TncConfigStore.markHostSuccess(host)
            rememberModels(models,playback)
            return models.size
        } finally {
            if(ContentRepository.backgroundModelScope===scope) ContentRepository.backgroundModelScope=null
            gate.release()
        }
    }

    internal fun ContentRepository.addressClient(budgetMs: Long): OkHttpClient {
        val bucket=(((budgetMs.coerceIn(ADDRESS_MIN_BUDGET_MS,ADDRESS_DEADLINE_MS))+99)/100)*100
        synchronized(addressClients) {
            addressClients[bucket]?.let { return it }
            val client=http.newBuilder()
                .connectTimeout(bucket,TimeUnit.MILLISECONDS)
                .readTimeout(bucket,TimeUnit.MILLISECONDS)
                .writeTimeout(bucket,TimeUnit.MILLISECONDS)
                .callTimeout(bucket,TimeUnit.MILLISECONDS)
                .build()
            if(addressClients.size>=8) addressClients.keys.firstOrNull()?.let(addressClients::remove)
            addressClients[bucket]=client
            return client
        }
    }
    /** 这一轮取地址还剩多少毫秒预算。 */
    internal fun ContentRepository.remainingOf(deadline: Long)=deadline-clock()
    /**
     * 预算用完时抛的那个异常。
     *
     * 用独立的 [AddressTimeoutException]：它不能伪装成 [java.io.InterruptedIOException]，
     * 否则会被界面侧当成「用户切页造成的取消」静默丢掉。超时与取消必须分开：
     * 前者要清地址 / DNS / 连接池后自动重来，后者什么都不该做。
     */
    internal fun ContentRepository.addressTimeout(cause: Throwable? = null): AddressTimeoutException =
        AddressTimeoutException("取播放地址超过 ${ADDRESS_DEADLINE_MS}ms",cause)

    /** OkHttp 的 callTimeout / readTimeout 或本仓库的预算耗尽都算地址超时。 */
    internal fun Throwable.isAddressTimeout(): Boolean {
        var current: Throwable?=this
        var depth=0
        while(current!=null && depth<8) {
            if(current is AddressTimeoutException || current is java.net.SocketTimeoutException) return true
            if(current is java.io.InterruptedIOException &&
                current.message.orEmpty().contains("timeout",ignoreCase=true)) return true
            current=current.cause
            depth++
        }
        return false
    }
