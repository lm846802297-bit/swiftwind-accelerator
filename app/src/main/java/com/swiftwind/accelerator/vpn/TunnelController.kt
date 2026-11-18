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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit

object TunnelController {
    private const val TUNNEL_NAME = "swiftwind"
    private const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=32768"
    private val speedClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

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
     * 真实拉起隧道，先测 WireGuard 握手耗时，再在隧道内做一次小流量 HTTPS 下载。
     * 只在未连接状态下执行，结束后一定回落，不改变界面的连接状态。
     */
    suspend fun probe(configText: String, timeoutMs: Long = 3000L): ProbeResult =
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
                val handshakeMs = awaitHandshake(timeoutMs)
                if (handshakeMs < 0L) {
                    return@withContext ProbeResult(healthy = false)
                }
                val speed = measureSpeed()
                ProbeResult(
                    healthy = true,
                    handshakeMs = handshakeMs,
                    httpLatencyMs = speed.first,
                    throughputKbps = speed.second
                )
            } finally {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            }
        }

    /** 轮询握手时间戳；返回从拉起隧道到握手的耗时，超时返回 -1。 */
    private suspend fun awaitHandshake(timeoutMs: Long): Long {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            val statistics = backend.getStatistics(tunnel)
            val peer = statistics.peers().firstOrNull()
            val handshake = peer?.let { statistics.peer(it)?.latestHandshakeEpochMillis } ?: 0L
            if (handshake > 0L || statistics.totalRx() > 0L) {
                return SystemClock.elapsedRealtime() - started
            }
            delay(100)
        }
        return -1L
    }

    /**
     * 隧道内下载一个小文件，返回（首字节延迟 ms，吞吐 kbps）。
     * 测速失败不影响可用性判断，只会在质量分里扣掉吞吐部分。
     */
    private fun measureSpeed(): Pair<Long, Int> {
        val started = SystemClock.elapsedRealtime()
        return runCatching {
            val request = Request.Builder()
                .url(SPEED_TEST_URL)
                .header("User-Agent", "SwiftWind/1.0")
                .build()
            speedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use 0L to 0
                val body = response.body ?: return@use 0L to 0
                val httpLatencyMs = SystemClock.elapsedRealtime() - started
                var totalBytes = 0L
                val buffer = ByteArray(8192)
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        totalBytes += read
                    }
                }
                val totalMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                val kbps = (totalBytes * 8L / totalMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                httpLatencyMs to kbps
            }
        }.getOrDefault(0L to 0)
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
