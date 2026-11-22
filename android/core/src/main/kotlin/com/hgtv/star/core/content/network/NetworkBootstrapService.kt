// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

    internal fun ContentRepository.preconnectImpl() {
        // 两条域名各建一条连接：**账号/搜索**走 [accountHost]，**推荐/分类/详情/播放地址**走
        // [videoHost]。推荐首屏那次请求打的是后者，只热前者等于没热（实测那一发仍要自己
        // 握手 1.3 秒）。
        //
        // **两条不再同时发**（2026-10-27 改）：一次 TLS 1.2 握手在这台盒子上的实测是
        // `tcp=447ms tls=406ms`——两跳 RTT 加密钥交换，两个域名同时来就是两条各 ~700ms 的
        // 链接同时挤在一个 CDN 边上，而且这两条都在建界面、初始化解码器的那两秒里。
        // 现在：先热 [videoHost]（下一发就是它），[accountHost] 推后
        // [ContentRepository.ACCOUNT_PRECONNECT_DELAY_MS]，而且**只有登录过才发**。
        warmAsync(videoHost())
        // [accountHost] 只在**登录过**才有用武之地：未登录时账号 / 收藏接口一次都不发，
        // 这一次握手就是纯浪费（这台盒子上 ~700ms 的背景流量 + 一个 RTT 的 CDN 配额）。
        // 判据在 2 秒后才读：那时 `Library` 已经加载完，`signedIn` 才是可信的。
        warmAsync(accountHost(),ACCOUNT_PRECONNECT_DELAY_MS) { signedIn }
    }

    private fun ContentRepository.warmAsync(host: String,delayMs: Long=0L,needed: ()->Boolean={ true }) {
        val thread=Thread({
            // 低优先级：这台盒子只有两个核，而一次握手除了两跳 RTT 还有一段纯 CPU
            // （ECDHE + 150 张系统根证书的 PKIX 验链）。降到 MIN_PRIORITY（ART 上会落到
            // 后台 nice 档）之后，主线程建视图、解码器初始化都会先拿到 CPU，
            // 握手只是「把剩下的时间用掉」——它只要在建首帧之前完成就行。
            // `core` 是纯 JVM 模块（没有 Android 依赖），因此用 Java 的线程优先级。
            runCatching { Thread.currentThread().priority=Thread.MIN_PRIORITY }
            if(delayMs>0) runCatching { Thread.sleep(delayMs) }
            // 到点了再看还要不要：不需要就**一个包都不发**（见 [preconnectImpl] 里 ACCOUNT 那段）。
            if(!runCatching { needed() }.getOrDefault(true)) {
                NetLog.log("preconnect $host skipped")
                return@Thread
            }
            val began=System.nanoTime()
            warmConnection(host)
            NetLog.log("preconnect $host took=${(System.nanoTime()-began)/1_000_000}ms")
        },"hgtv-preconnect-$host")
        thread.isDaemon=true
        thread.start()
    }

    /**
     * 对某个域名的根路径发一次 `HEAD`，把 DNS + TCP + TLS 全部做完，连接按 keep-alive
     * 留在 [ContentRepository.http] 的连接池里。返回码与响应体全部丢弃，失败静默。
     *
     * 只在 [preconnectImpl] 拉起的临时线程上跑，不占用调用方的线程。
     */
    private fun ContentRepository.warmConnection(host: String) {
        runCatching {
            val request=Request.Builder()
                .url("https://$host/")
                .head()
                .header("User-Agent",VendorConstants.userAgent(primaryProfile(),video=false))
                .build()
            http.newCall(request).execute().use { it.body?.close() }
        }
    }

    internal fun ContentRepository.warmSignatureImpl() {
        runCatching {
            val began=System.nanoTime()
            val body=JSONObject()
                .put("filter_ids","0".repeat(96))
                .put("offset",0)
                .put("limit",40)
                .put("session_id","")
                .put("genre","")
                .toString()
                .toByteArray()
            val valuesAt=System.nanoTime()
            val values=deviceValues(CATEGORY_VERSION_CODE,CATEGORY_VERSION_NAME)
            val signAt=System.nanoTime()
            signer.sign(categoryApiUrl(),values,body,method="POST",gzipBody=false).withSession()
            NetLog.log(
                "sign warm body=${body.size}B values=${(valuesAt-began)/1_000_000}ms"+
                " sign=${(signAt-valuesAt)/1_000_000}ms total=${(System.nanoTime()-began)/1_000_000}ms"
            )
        }
    }

    internal fun ContentRepository.registerDeviceImpl(
        profile: Map<String,String>?,
        withIdentity: Boolean,
    ): DeviceRegister.Registered? {
        val device=LinkedHashMap(profile ?: registerProfile).apply { if(withIdentity) putAll(deviceIdentity) }
        val registered=performDeviceRegistration(device,anonymous=!withIdentity)
        if(registered!=null) {
            lastRegisterError=null
            deviceIdentity=linkedMapOf(
                "device_id" to registered.deviceId,
                "iid" to registered.installId,
            ).apply {
                registered.deviceToken.takeIf { it.isNotBlank() }?.let { put("device_token",it) }
                registered.klinkEgdi.takeIf { it.isNotBlank() }?.let { put("klink_egdi",it) }
            }
            registrationCookies=LinkedHashMap(registrationCookies).apply {
                putAll(registered.cookies)
                // 旧身份留下的 `install_id` 必须被这次注册的结果顶掉：两个设备号同时出现，
                // 服务端按陌生设备处理（搜索空响应 / 播放 101000），等于白注册一次。
                if(registered.installId.isNotBlank()) put("install_id",registered.installId)
            }
        }
        return registered
    }

    private fun ContentRepository.performDeviceRegistration(
        device: Map<String,String>,
        anonymous: Boolean,
    ): DeviceRegister.Registered? {
        val body=DeviceRegister.payload(device)
        val headers=DeviceRegister.headers(body,device)
        var lastFailure: Throwable?=null
        val hosts=TncConfigStore.logHosts()
        for((index,host) in hosts.withIndex()) {
            if(index>0) NetLog.retry("device_register",index+1,hosts.size,lastFailure?.message.orEmpty())
            val registered=runCatching {
                val request=Request.Builder().url(DeviceRegister.url(device,host=host))
                headers.forEach { (key,value) -> request.header(key,value) }
                // 换新身份必须匿名：带上 Cookie / token 的话服务端会按旧设备回同一对数字。
                if(anonymous) request.header("X-HGTV-Anonymous","1")
                request.post(body.toRequestBody("application/octet-stream".toMediaType()))
                RequestScope.execute(http.newCall(request.build())) { response ->
                    if(!response.isSuccessful) throw IOException("设备注册失败（HTTP ${response.code}）")
                    val bytes=response.body?.bytes() ?: throw IOException("设备注册响应为空")
                    DeviceRegister.parse(String(bytes,Charsets.UTF_8),response.headers("Set-Cookie"))
                        ?: throw IOException("设备注册被服务端拒绝")
                }
            }.onFailure {
                lastFailure=it
                NetLog.emit("device register host=$host failed ${it.javaClass.simpleName}")
            }.getOrNull()
            if(registered!=null) {
                lastRegisterError=null
                NetLog.log("device register host=$host accepted")
                return registered
            }
        }
        lastRegisterError=lastFailure
        return null
    }

    internal fun ContentRepository.refreshTncAsyncImpl(force: Boolean): Boolean =
        TncConfigStore.refreshAsync(force) { summary -> fetchTnc(summary) }

    /** TNC 的 `/get_domains/v5/` 请求；优先带身份，失败后回退匿名 bootstrap。 */
    private fun ContentRepository.fetchTnc(summary: String?): TncResponse? {
        val hosts=TncConfigStore.tncHosts()
        if(!primaryProfile()["device_id"].isNullOrBlank() && primaryCookieHeader().isNotBlank()) {
            fetchSignedTnc(summary,hosts)?.let { return it }
        }
        return fetchAnonymousTnc(summary,hosts)
    }

    /** 注册后带设备身份的 TNC 请求，用于获取当前地区的最终配置。 */
    private fun ContentRepository.fetchSignedTnc(summary: String?,hosts: List<String>): TncResponse? {
        val profile=modelProfile()
        val cookie=primaryCookieHeader()
        val values=tncValues(profile)
        for(host in hosts) {
            try {
                val signed=signer.sign(
                    "https://$host/get_domains/v5/",
                    values,
                    method="GET",
                    deviceOverride=profile,
                )
                val headers=LinkedHashMap(signed.headers).apply {
                    remove("x-tt-dt")
                    remove("x-tt-request-tag")
                    put("passport-sdk-version","5051451")
                    put("lc","101")
                    put("sdk-version","2")
                    put("x-ss-dp","8662")
                    put("x-vc-bdturing-sdk-version","4.0.3.cn")
                    put("Cookie",cookie)
                    summary?.takeIf { it.isNotBlank() }?.let { put("x-tt-tnc-summary",it) }
                }
                val response=readTncResponse(
                    signed.url,
                    Signer.Request(signed.url,headers,signed.body,"GET"),
                    summary,
                )
                if(response!=null) {
                    NetLog.log(
                        if(response.notModified) "tnc signed host=$host unchanged"
                        else "tnc signed host=$host accepted bytes=${response.raw.length}"
                    )
                    return response
                }
            } catch(e: IOException) {
                if(isInterruption(e) || e.message=="请求已取消") throw e
                NetLog.emit("tnc signed host=$host failed ${e.javaClass.simpleName}")
            } catch(_: Exception) {
                NetLog.emit("tnc signed host=$host failed")
            }
        }
        return null
    }

    /**
     * 无设备身份 TNC bootstrap。
     *
     * 官方实测：最小设备参数即可返回完整配置，不需要 `device_id`、`iid`、Cookie 或签名。
     * `X-HGTV-Anonymous` 只用于阻止本工程的 SessionInterceptor 补身份，发网前会被删除。
     */
    private fun ContentRepository.fetchAnonymousTnc(summary: String?,hosts: List<String>): TncResponse? {
        val base=LinkedHashMap(VendorConstants.device).apply {
            putAll(registerProfile)
            remove("device_id")
            remove("iid")
            remove("install_id")
            remove("klink_egdi")
            remove("cdid")
        }
        val values=tncValues(modelQueryProfile(base))
        val query=Signer.encode(values)
        for(host in hosts) {
            try {
                val url="https://$host/get_domains/v5/?$query"
                val headers=linkedMapOf(
                    "User-Agent" to VendorConstants.userAgent(values,video=true),
                    "Accept" to "application/json",
                    "X-SS-DP" to "8662",
                    "lc" to "101",
                    "sdk-version" to "2",
                    "passport-sdk-version" to "5051451",
                    "x-vc-bdturing-sdk-version" to "4.0.3.cn",
                    "X-HGTV-Anonymous" to "1",
                )
                summary?.takeIf { it.isNotBlank() }?.let { headers["x-tt-tnc-summary"]=it }
                val response=readTncResponse(url,Signer.Request(url,headers,null,"GET"),summary)
                if(response!=null) {
                    NetLog.log(
                        if(response.notModified) "tnc anonymous host=$host unchanged"
                        else "tnc anonymous host=$host accepted bytes=${response.raw.length}"
                    )
                    return response
                }
            } catch(e: IOException) {
                if(isInterruption(e) || e.message=="请求已取消") throw e
                NetLog.emit("tnc anonymous host=$host failed ${e.javaClass.simpleName}")
            } catch(_: Exception) {
                NetLog.emit("tnc anonymous host=$host failed")
            }
        }
        return null
    }

    private fun ContentRepository.tncValues(profile: Map<String,String>): LinkedHashMap<String,String> =
        LinkedHashMap(profile).apply {
            this["abi"]=this["host_abi"].orEmpty().ifBlank { "arm64-v8a" }
            this["tnc_src"]="1"
            this["okhttp_version"]="4.2.243.17-hongguo"
            this["use_store_region_cookie"]="1"
            // TNC 抓包来自播放器 7.1.3.32；播放取地址的 7.4.3.32 不混进这条配置请求。
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
                this[key]=VendorConstants.VIDEO_VERSION_CODE
            }
            this["version_name"]="7.1.3.32"
        }

    private fun ContentRepository.readTncResponse(url: String,signed: Signer.Request,summary: String?): TncResponse? {
        var httpCode=0
        var etag=""
        var tncControl=""
        val raw=text(
            url,
            signed,
            client=tncHttp,
            allowNotModified=true,
            storeRegionHeaders=false,
        ) { response ->
            httpCode=response.code
            etag=response.header("x-ss-etag").orEmpty()
            tncControl=response.header("x-tt-tnc-control").orEmpty()
        }
        if(raw.isBlank()) {
            if(httpCode==304 || tncControl=="1") {
                return TncResponse(
                    raw="",
                    summary=summary.orEmpty(),
                    etag=etag,
                    notModified=true,
                )
            }
            return null
        }
        val root=runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if(root.optString("message")!="success") return null
        return TncResponse(
            raw=raw,
            summary=root.optString("summary"),
            etag=etag,
        )
    }
