package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 用同一份真实注册身份串行比较播放模型 API。
 *
 * 只用于现场排障，必须显式提供身份环境变量并设置 `HONGGUO_LIVE=1`：
 * `HONGGUO_DID`、`HONGGUO_IID`、`HONGGUO_COOKIE`，可选 `HONGGUO_KLINK`、
 * `HONGGUO_DEVICE_TOKEN`、`HONGGUO_VIDEO_ID`、`HONGGUO_API_HOST`。
 */
class LivePlaybackApiCompareTest {
    private val playback=PlaybackProfile(contentType=1004,videoPlatform=3)
    private val client=OkHttpClient.Builder().callTimeout(10,TimeUnit.SECONDS).build()

    private fun env(name: String,fallback: String=""): String =
        System.getenv(name)?.trim().takeUnless { it.isNullOrEmpty() } ?: fallback

    private fun profile(): Map<String,String> {
        val did=env("HONGGUO_DID")
        val iid=env("HONGGUO_IID")
        return VendorConstants.tvProfile(
            linkedMapOf(
                "device_id" to did,
                "iid" to iid,
                "install_id" to iid,
                "klink_egdi" to env("HONGGUO_KLINK"),
                "device_token" to env("HONGGUO_DEVICE_TOKEN"),
            ).filterValues { it.isNotBlank() },
            did,
        )
    }

    private fun query(values: Map<String,String>): LinkedHashMap<String,String> =
        linkedMapOf<String,String>().apply {
            for(key in ContentRepository.MODEL_QUERY_KEYS) {
                values[key]?.takeIf { it.isNotBlank() }?.let { put(key,it) }
            }
        }

    private fun send(label: String,path: String,body: String,values: Map<String,String>) {
        val host=env("HONGGUO_API_HOST","api5-normal-sinfonlinea.fqnovel.com")
        val raw=body.toByteArray()
        val gzip=java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(raw) }
        }.toByteArray()
        val signed=Signer(device=values).sign(
            "https://$host$path",
            query(values),
            gzip,
            deviceOverride=values,
        )
        val headers=LinkedHashMap(signed.headers).apply {
            remove("x-tt-dt")
            remove("x-tt-request-tag")
            put("passport-sdk-version","5051452")
            put("lc","101")
            put("x-ss-dp","8662")
            put("x-vc-bdturing-sdk-version","4.0.3.cn")
            put("x-tt-trace-id",VendorConstants.traceId())
            put("Content-Encoding","gzip")
            put("Cookie",env("HONGGUO_COOKIE"))
        }
        val request=Request.Builder()
            .url(signed.url)
            .post(gzip.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .apply { headers.forEach { (name,value) -> header(name,value) } }
            .build()
        val started=System.nanoTime()
        client.newCall(request).execute().use { response ->
            val text=response.body?.string().orEmpty()
            val elapsed=(System.nanoTime()-started)/1_000_000L
            val root=runCatching { JSONObject(text) }.getOrNull()
            val code=root?.optInt("Code",root.optInt("code",0)) ?: -1
            val model=root?.let(ContentPlaybackParser::videoModelOf)
            val inline=model?.let { ContentPlaybackParser.parseInlineStreamInfo(it,1080)!=null } ?: false
            val fallback=model?.let {
                when(val value=it.opt("fallback_api")) {
                    is JSONObject -> value.str("fallback_api")
                    else -> value?.toString().orEmpty()
                }
            }.orEmpty()
            println(
                "[api-compare] $label host=$host path=$path elapsed=${elapsed}ms "+
                    "HTTP=${response.code} bytes=${text.length} code=$code inline=$inline "+
                    "fallback=${fallback.isNotBlank()}",
            )
            if(model==null && text.isNotBlank()) println("[api-compare] $label body=${text.take(180)}")
        }
        Thread.sleep(250L)
    }

    @Test fun comparePlaybackApis() {
        assumeTrue("HONGGUO_LIVE",System.getenv("HONGGUO_LIVE")=="1")
        val videoId=env("HONGGUO_VIDEO_ID","7695729312577637438")
        val values=profile()
        require(values["device_id"].orEmpty().isNotBlank()) { "HONGGUO_DID 不能为空" }
        require(values["iid"].orEmpty().isNotBlank()) { "HONGGUO_IID 不能为空" }
        require(env("HONGGUO_COOKIE").isNotBlank()) { "HONGGUO_COOKIE 不能为空" }

        val sdkBody=JSONObject()
            .put("video_id",videoId)
            .put("biz_param",JSONObject()
                .put("video_id_type",0)
                .put("need_all_video_definition",true)
                .put("video_platform",3))
            .toString()
        send(
            "multi-player-app",
            "/novel/player/multi_video_model/player/v1",
            ContentPlaybackParser.directModelBody(videoId,playback),
            values,
        )
        send(
            "video-model-app",
            "/novel/player/video_model/v1/",
            ContentPlaybackParser.modelBody(videoId,"/novel/player/video_model/v1/",playback,4),
            values,
        )
        send(
            "video-model-sdk",
            "/novel/player/video_model/v1/",
            sdkBody,
            values,
        )
        send(
            "multi-old-app",
            "/novel/player/multi_video_model/v1/",
            ContentPlaybackParser.modelBody(videoId,"/novel/player/multi_video_model/v1/",playback,4),
            values,
        )
        send(
            "multi-preload-app",
            "/novel/player/multi_video_model/preload/v1",
            ContentPlaybackParser.batchModelBody(listOf(videoId),playback,"preload",4),
            values,
        )
    }
}
