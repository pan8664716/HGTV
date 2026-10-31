// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.hgtv.star.shared.debug.Diagnostics

/**
 * 前台媒体会话：让**遥控器 / 耳机线控 / 锁屏与系统媒体面板**上的播放键能找到本应用。
 *
 * 两端共用同一份实现：
 *
 * * 电视上它接的是遥控器的播放 / 暂停 / 上下集；
 * * 手机上它接的是耳机线控、蓝牙耳机、锁屏卡片与下拉通知里的媒体面板——
 *   手机上这条链路比电视更常用（用户锁屏后仍要按耳机暂停）。
 *
 * Android 4.4（API 19）没有 `android.media.session.MediaSession`（API 21 才有），
 * 因此统一走 `MediaSessionCompat`：它在 21+ 就是系统会话的包装，19/20 上退化成
 * 只处理媒体按钮的本地实现，调用方不用分版本。
 *
 * 仍然只在前台播放时激活（`isActive`），不做后台播放服务：本应用的播放器是
 * 进程级单例，退到后台就该停（见各端 `onStop`）。
 */
class MediaSessionHost private constructor(
    context: Context,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Int) -> Unit,
    onStop: () -> Unit,
) {
    private val session = MediaSessionCompat(context, "HGTV").apply {
        // 不设置 setMediaButtonReceiver：它指向的 androidx MediaButtonReceiver 只会把按键
        // 转给本包的 MEDIA_BUTTON / MediaBrowserService，本应用没有这类 Service，被调用即抛
        // IllegalStateException 崩溃。前台按键由 Activity.onKeyDown（电视）与下面的 callback
        //（两端）处理。
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = onPlay.invoke()
            override fun onPause() = onPause.invoke()
            override fun onSeekTo(pos: Long) = onSeek(pos)
            override fun onSkipToNext() = onSkip(1)
            override fun onSkipToPrevious() = onSkip(-1)
            override fun onStop() = onStop.invoke()
        }, Handler(Looper.getMainLooper()))
    }
    private var metadataKey = ""

    /**
     * 把「正在放什么」同步给系统。
     *
     * @param episode 0 基集号（展示时 +1）
     * @param state [PlaybackStateCompat.STATE_*]
     * @param previous / [next] 有没有上 / 下一集：没有时**不声明**对应动作，
     *   系统面板上那颗键就是灰的，用户按了也不会得到「什么都没发生」的困惑。
     */
    fun update(
        title: String,
        episode: Int,
        duration: Long,
        position: Long,
        speed: Float,
        state: Int,
        previous: Boolean,
        next: Boolean,
    ) {
        // 会话是系统服务代理：容器/ROM 里 media_session 可能中途失效，任何一次调用都可能抛
        // RemoteException / NPE。媒体键只是附加能力，失败就静默降级，绝不能让播放路径崩掉。
        // 只接运行时异常：OOM 这类 Error 不在这里被吞掉。
        try {
            val key = "$title/$episode/$duration"
            if (metadataKey != key) {
                metadataKey = key
                session.setMetadata(MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "第 ${episode + 1} 集")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration).build())
            }
            var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
            if (duration > 0) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
            if (previous) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            if (next) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            session.setPlaybackState(PlaybackStateCompat.Builder().setActions(actions)
                .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) speed else 0f)
                .build())
            session.isActive = true
        } catch (e: Exception) {
            // 空 catch 是这里最糟的选择：媒体键失灵（耳机按暂停没反应）时，
            // 现场全被吞掉，用户报「按键不管用」我们一条线索都没有。
            Diagnostics.warn("HGTV-MEDIA", "session sync failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    fun deactivate() {
        try { session.isActive = false } catch (e: Exception) {
            Diagnostics.warn("HGTV-MEDIA", "deactivate failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    fun release() {
        try { session.release() } catch (e: Exception) {
            Diagnostics.warn("HGTV-MEDIA", "release failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    companion object {
        /**
         * 构造即向系统申请会话。API 21+ 走平台 `MediaSession`，而它的构造函数对
         * `MediaSessionManager.createSession()` 的返回值**不判空**：虚拟容器（SandVXposed 等）
         * 把 binder 代理成 null 时，会在 `mBinder.getController()` 直接 NPE，并顺着
         * `onCreate` 变成「Unable to start activity」闪退。因此这里统一用工厂方法兜住，
         * 创建失败就返回 null，由调用方降级为「没有媒体键」。
         */
        fun create(
            context: Context,
            onPlay: () -> Unit,
            onPause: () -> Unit,
            onSeek: (Long) -> Unit,
            onSkip: (Int) -> Unit,
            onStop: () -> Unit,
        ): MediaSessionHost? =
            // 只兜构造期的运行时异常（平台 MediaSession 的 NPE / 容器代理的 RemoteException）；
            // OOM、ThreadDeath 这类 Error 照常抛出，不在这里被吞掉。
            try {
                MediaSessionHost(context, onPlay, onPause, onSeek, onSkip, onStop)
            } catch (e: Exception) {
                // 虚拟容器（SandVXposed 等）把 binder 代理成 null 时必然走到这里。
                // 记一条：这样「这台设备媒体键按了没反应」能直接归因，
                // 而不是被当成播放器坏了。
                Diagnostics.warn("HGTV-MEDIA", "create failed: ${e.javaClass.simpleName}: ${e.message}", e)
                null
            }
    }
}
