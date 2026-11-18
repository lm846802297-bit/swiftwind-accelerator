package com.swiftwind.accelerator.vpn

import android.content.Context
import android.os.SystemClock
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

object TunnelController {
    private const val TUNNEL_NAME = "swiftwind"

    private lateinit var backend: GoBackend
    private var startedAt = 0L

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    fun init(context: Context) {
        if (!::backend.isInitialized) {
            backend = GoBackend(context.applicationContext)
        }
    }

    /** App 进程重启后，检查系统里是否已有本 App 的隧道在运行。 */
    fun syncState() {
        if (!::backend.isInitialized) return
        runCatching {
            val running = backend.getRunningTunnelNames()
            if (running.contains(TUNNEL_NAME)) {
                if (_state.value !is VpnState.Connected) startedAt = SystemClock.elapsedRealtime()
                _state.value = VpnState.Connected
            } else if (_state.value is VpnState.Connected) {
                _state.value = VpnState.Idle
                startedAt = 0L
            }
        }
    }

    suspend fun connect(configText: String) = withContext(Dispatchers.IO) {
        _state.value = VpnState.Connecting
        try {
            val config: Config = Config.parse(BufferedReader(StringReader(configText)))
            backend.setState(tunnel, Tunnel.State.UP, config)
            startedAt = SystemClock.elapsedRealtime()
            _state.value = VpnState.Connected
        } catch (t: Throwable) {
            _state.value = VpnState.Error(mapError(t))
            throw t
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        _state.value = VpnState.Disconnecting
        try {
            if (::backend.isInitialized) {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            }
        } catch (_: Throwable) {
        } finally {
            startedAt = 0L
            _state.value = VpnState.Idle
        }
    }

    /**
     * 真实拉起隧道并检查 WireGuard 握手，用来判断节点是否可用。
     * 只在未连接状态下执行，结束后一定回落，不改变界面的连接状态。
     */
    suspend fun probe(configText: String, timeoutMs: Long = 3500L): Boolean =
        withContext(Dispatchers.IO) {
            if (!::backend.isInitialized) {
                throw IllegalStateException("VPN 组件尚未初始化")
            }
            when (_state.value) {
                is VpnState.Connected,
                is VpnState.Connecting,
                is VpnState.Disconnecting -> throw IllegalStateException("请先断开当前连接再体检")
                else -> Unit
            }

            val config: Config = Config.parse(BufferedReader(StringReader(configText)))
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
                delay(timeoutMs)
                val statistics = backend.getStatistics(tunnel)
                val peer = statistics.peers().firstOrNull()
                val handshake = peer?.let { statistics.peer(it)?.latestHandshakeEpochMillis } ?: 0L
                handshake > 0L || statistics.totalRx() > 0L
            } finally {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            }
        }

    fun snapshot(): TunnelStats {
        if (!::backend.isInitialized || _state.value !is VpnState.Connected) {
            return TunnelStats()
        }
        return runCatching {
            val statistics = backend.getStatistics(tunnel)
            val handshake = statistics.peers().firstOrNull()
                ?.let { statistics.peer(it)?.latestHandshakeEpochMillis }
                ?: 0L
            TunnelStats(
                downloadBytes = statistics.totalRx(),
                uploadBytes = statistics.totalTx(),
                durationSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).coerceAtLeast(0),
                latestHandshakeMillis = handshake,
                connected = true
            )
        }.getOrDefault(TunnelStats())
    }

    fun mapError(t: Throwable): String = when (t) {
        is BackendException -> when (t.reason) {
            BackendException.Reason.VPN_NOT_AUTHORIZED -> "未获得系统 VPN 授权"
            BackendException.Reason.UNABLE_TO_START_VPN -> "无法启动 VPN 服务，请重试"
            BackendException.Reason.TUNNEL_MISSING_CONFIG -> "节点配置缺失"
            BackendException.Reason.DNS_RESOLUTION_FAILURE -> "节点域名解析失败"
            BackendException.Reason.TUN_CREATION_ERROR -> "创建虚拟网卡失败，可能被其他 VPN 占用"
            BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> "WireGuard 内核启动失败"
            else -> "连接失败（${t.reason}）"
        }
        is BadConfigException -> "节点配置无效：${t.message}"
        is IOException -> "网络错误：${t.message}"
        else -> t.message ?: "连接失败，请重试"
    }
}
