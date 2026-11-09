// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feature.lan

import android.content.Context
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.lan.DeviceSyncPackage
import com.hgtv.star.shared.lan.DeviceSyncPayload
import com.hgtv.star.shared.lan.DeviceSyncSelection
import com.hgtv.star.shared.lan.LanLibrarySyncSession
import com.hgtv.star.shared.lan.LanSyncPeer
import com.hgtv.star.shared.lan.lanIpv4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 局域网同步的完整行为层。
 *
 * TV / Phone 只负责把 [state] 渲染成各自对话框，并把用户选择回传到这里。
 */
class LanSyncController(
    private val context: Context,
    private val library: Library,
    private val scope: CoroutineScope,
    private val discoveryWindowMs: Long = 6_000L,
    private val sourceTtlMs: Long = Long.MAX_VALUE,
) {
    enum class Role { NONE, SOURCE, RECEIVER }
    enum class Step { IDLE, SERVING, DISCOVERING, PEERS, LOADING, READY_TO_APPLY, APPLYING, DONE, ERROR }

    data class State(
        val step: Step = Step.IDLE,
        val role: Role = Role.NONE,
        val address: String = "",
        val peers: List<LanSyncPeer> = emptyList(),
        val downloadedCount: Int = 0,
        val payload: DeviceSyncPayload? = null,
        val message: String = "",
        val error: String = "",
    )

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var session: LanLibrarySyncSession? = null
    private var discoveryJob: Job? = null
    private var requestToken = 0

    /** 开始同步源。 */
    fun startSource() {
        val prepared = prepare(Role.SOURCE) ?: return
        var local: LanLibrarySyncSession? = null
        local = LanLibrarySyncSession(
            context = appContext,
            address = prepared.address,
            payload = prepared.payload,
            onPeers = {},
            onError = { message ->
                if (session === local) _state.update { it.copy(message = message, error = message) }
            },
            discoverPeers = false,
            onServing = {
                if (session === local) _state.update {
                    it.copy(step = Step.SERVING, message = "同步源已开启 · 等待其他设备连接")
                }
            },
            onDownloaded = {
                if (session === local) _state.update {
                    it.copy(
                        downloadedCount = it.downloadedCount + 1,
                        message = "已被 ${it.downloadedCount + 1} 台设备读取 · 继续等待其他设备",
                    )
                }
            },
            ttlMillis = sourceTtlMs,
        )
        session = local
        _state.value = State(
            step = Step.SERVING,
            role = Role.SOURCE,
            address = prepared.address,
            message = "正在开启同步源…",
        )
        runCatching { local.start() }.onFailure {
            stop()
            _state.update { it.copy(step = Step.ERROR, error = "无法开启同步源") }
        }
    }

    /** 开始查找其他设备；窗口结束后把发现结果写入 [State.peers]。 */
    fun startReceiver() {
        val prepared = prepare(Role.RECEIVER) ?: return
        stopSession()
        val found = linkedMapOf<String, LanSyncPeer>()
        var local: LanLibrarySyncSession? = null
        local = LanLibrarySyncSession(
            context = appContext,
            address = prepared.address,
            payload = prepared.payload,
            onPeers = { peers ->
                if (session === local) {
                    found.clear()
                    peers.forEach { found[it.serviceName] = it }
                    _state.update {
                        it.copy(
                            step = Step.DISCOVERING,
                            peers = found.values.toList(),
                            message = "已发现 ${found.size} 台设备",
                        )
                    }
                }
            },
            onError = { message ->
                if (session === local) _state.update { it.copy(error = message, message = message) }
            },
        )
        session = local
        _state.value = State(
            step = Step.DISCOVERING,
            role = Role.RECEIVER,
            address = prepared.address,
            message = "正在通过局域网查找其他设备。",
        )
        runCatching { local.start() }.onFailure {
            stop()
            _state.update { it.copy(step = Step.ERROR, error = "无法开启局域网同步") }
            return
        }
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            delay(discoveryWindowMs)
            if (session !== local) return@launch
            local.stopDiscovery()
            val peers = found.values.toList()
            _state.update {
                it.copy(
                    step = if (peers.isEmpty()) Step.ERROR else Step.PEERS,
                    peers = peers,
                    message = if (peers.isEmpty()) "没有发现同步设备" else "请选择同步设备",
                    error = if (peers.isEmpty()) "没有发现同步设备" else "",
                )
            }
        }
    }

    /** 选择一台设备并读取同步包。 */
    fun loadPeer(peer: LanSyncPeer) {
        stopSession()
        val token = ++requestToken
        _state.update {
            it.copy(step = Step.LOADING, message = "正在从 ${peer.displayName} 读取数据…", error = "")
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LanLibrarySyncSession.fetchPayload(peer) }
            }
            if (token != requestToken) return@launch
            result.onSuccess { payload ->
                _state.update {
                    it.copy(
                        step = Step.READY_TO_APPLY,
                        payload = payload,
                        message = "请选择要同步的数据",
                        error = "",
                    )
                }
            }.onFailure { problem ->
                _state.update {
                    it.copy(
                        step = Step.ERROR,
                        error = problem.message.orEmpty().ifBlank { "无法读取其他设备的数据" },
                    )
                }
            }
        }
    }

    /** 应用用户选择的同步内容。 */
    fun apply(selection: DeviceSyncSelection, onResult: (Boolean) -> Unit = {}) {
        val payload = state.value.payload ?: return
        val token = ++requestToken
        _state.update { it.copy(step = Step.APPLYING, message = "正在恢复所选类别…", error = "") }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    DeviceSyncPackage.restore(appContext, library, payload, selection)
                }
            }
            if (token != requestToken) return@launch
            result.onSuccess { saved ->
                _state.update {
                    it.copy(
                        step = if (saved) Step.DONE else Step.ERROR,
                        message = if (saved) "同步完成，正在重启应用" else "部分数据未能写入本机，请重试",
                        error = if (saved) "" else "部分数据未能写入本机，请重试",
                    )
                }
                onResult(saved)
            }.onFailure { problem ->
                val message = problem.message.orEmpty().ifBlank { "无法应用其他设备的数据" }
                _state.update { it.copy(step = Step.ERROR, error = message, message = message) }
                onResult(false)
            }
        }
    }

    fun reset() {
        requestToken++
        discoveryJob?.cancel()
        discoveryJob = null
        stopSession()
        _state.value = State()
    }

    fun stop() = reset()

    private fun prepare(role: Role): Prepared? {
        stop()
        val address = appContext.lanIpv4Address()
        if (address == null) {
            _state.value = State(step = Step.ERROR, role = role, error = "请让两台设备连接同一个局域网后重试")
            return null
        }
        val encoded = runCatching {
            DeviceSyncPackage.encode(DeviceSyncPackage.capture(appContext, library))
        }.getOrElse {
            _state.value = State(step = Step.ERROR, role = role, error = "本机记录暂时无法生成同步数据")
            return null
        }
        return Prepared(address, encoded)
    }

    private fun stopSession() {
        session?.close()
        session = null
    }

    private data class Prepared(val address: String, val payload: String)
}
