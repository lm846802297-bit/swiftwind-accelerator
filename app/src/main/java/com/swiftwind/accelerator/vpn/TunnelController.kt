package com.swiftwind.accelerator.vpn

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object TunnelController {
    private const val TUNNEL_NAME = "swiftwind"
    private const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=262144"
    private const val VERIFY_URL = "https://1.1.1.1/cdn-cgi/trace"
    private val speedClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val verifyClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private lateinit var backend: GoBackend
    private lateinit var appContext: Context
    private var startedAt = 0L

    /** 串行化连接/断开/体检的入口，避免体检 finally 拆掉刚建立的连接。 */
    private val operationGate = Any()
    private var probing = false

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!::backend.isInitialized) {
            backend = GoBackend(context.applicationContext)
        }
    }

    /** 统一状态出口：连接成功时拉起前台保活，断开/失败时释放。 */
    private fun updateState(newState: VpnState) {
        if (_state.value == newState) return
        _state.value = newState
        if (!::appContext.isInitialized) return
        when (newState) {
            is VpnState.Connected -> runCatching {
                VpnKeepAliveService.start(appContext)
            }
            is VpnState.Idle, is VpnState.Error -> runCatching {
                VpnKeepAliveService.stop(appContext)
            }
            else -> Unit
        }
    }

    /** App 进程重启后，检查系统里是否已有本 App 的隧道在运行。 */
    fun syncState() {
        if (!::backend.isInitialized) return
        synchronized(operationGate) {
            if (probing) return
            runCatching {
                val running = backend.getRunningTunnelNames().contains(TUNNEL_NAME)
                when {
                    _state.value is VpnState.Connected && !running -> {
                        startedAt = 0L
                        updateState(VpnState.Idle)
                    }
                    _state.value is VpnState.Idle && running -> {
                        startedAt = SystemClock.elapsedRealtime()
                        updateState(VpnState.Connected)
                    }
                }
            }
        }
    }

    suspend fun connect(
        configText: String,
        verifyData: Boolean = true,
        handshakeTimeoutMs: Long = 2000L
    ) = withContext(Dispatchers.IO) {
        synchronized(operationGate) {
            if (probing) throw IllegalStateException("正在体检节点，请稍后再连接")
            when (_state.value) {
                is VpnState.Connecting,
                is VpnState.Disconnecting -> throw IllegalStateException("正在切换连接，请稍后再试")
                else -> Unit
            }
            updateState(VpnState.Connecting)
        }
        try {
            val connectStartedAt = SystemClock.elapsedRealtime()
            val config: Config = Config.parse(BufferedReader(StringReader(configText)))
            // 用 backend 当前是否已有隧道来判断首次连接还是“已连接状态下切换节点”。
            val hadTunnel = isRunning()
            Log.d("SwiftWindSwitch", "connect start hadTunnel=$hadTunnel")
            if (hadTunnel) {
                // GoBackend 自带的 DOWN→UP 只隔几十毫秒，ColorOS/Realme 的系统 VPN
                // NetworkAgent 来不及回收，会出现新隧道刚起就被系统断开的竞态。
                // 这里显式先断开，等系统把 tun0 和 NetworkAgent 释放干净，再建立新隧道。
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                awaitRunning(false, 1500L)
                delay(400L)
                Log.d("SwiftWindSwitch", "connect old-down +${SystemClock.elapsedRealtime() - connectStartedAt}ms")
            }
            startTunnel(config)
            Log.d("SwiftWindSwitch", "connect tunnel-up +${SystemClock.elapsedRealtime() - connectStartedAt}ms")
            // 隧道“UP”不等于节点可用：必须收到 WireGuard 握手，否则会把手动选择的
            // 死节点显示成“已连接”，用户实际没有网络。
            val handshake = awaitHandshake(handshakeTimeoutMs)
            Log.d("SwiftWindSwitch", "connect handshake=$handshake +${SystemClock.elapsedRealtime() - connectStartedAt}ms")
            if (handshake < 0L) {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                awaitRunning(false, 800L)
                Log.d("SwiftWindSwitch", "connect dead-clean +${SystemClock.elapsedRealtime() - connectStartedAt}ms")
                val error = IllegalStateException("节点未响应，无法建立有效连接")
                updateState(VpnState.Error(mapError(error)))
                throw error
            }
            // 握手成功也不代表能上网：再做一次极小的 HTTPS 请求确认数据面。
            // 这同时让“连接成功”后的健康标签有真实依据；回滚原节点也保留这一步，
            // 避免“只握手成功、实际没网”的假连接。
            val verified = if (verifyData) verifyTraffic() else true
            Log.d("SwiftWindSwitch", "connect verify=$verified +${SystemClock.elapsedRealtime() - connectStartedAt}ms")
            if (!verified) {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                awaitRunning(false, 800L)
                val error = IllegalStateException("节点握手成功，但无法传输数据")
                updateState(VpnState.Error(mapError(error)))
                throw error
            }
            startedAt = SystemClock.elapsedRealtime()
            updateState(VpnState.Connected)
            Log.d("SwiftWindSwitch", "connect done +${SystemClock.elapsedRealtime() - connectStartedAt}ms")
        } catch (t: Throwable) {
            if (_state.value is VpnState.Error) throw t
            // GoBackend 在切换配置失败时会尝试恢复旧隧道；如果旧隧道确实还在，
            // 不能让 UI 停在“失败/未连接”，否则会出现界面与实际 VPN 状态相反。
            val stillRunning = runCatching {
                ::backend.isInitialized && backend.getRunningTunnelNames().contains(TUNNEL_NAME)
            }.getOrDefault(false)
            if (stillRunning) {
                if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
                updateState(VpnState.Connected)
            } else {
                updateState(VpnState.Error(mapError(t)))
            }
            throw t
        }
    }

    private fun isRunning(): Boolean = runCatching {
        ::backend.isInitialized && backend.getRunningTunnelNames().contains(TUNNEL_NAME)
    }.getOrDefault(false)

    /** 轮询 backend 的隧道运行状态，直到达到期望值或超时。 */
    private suspend fun awaitRunning(expected: Boolean, timeoutMs: Long): Boolean {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            if (isRunning() == expected) return true
            delay(100L)
        }
        return isRunning() == expected
    }

    /** 要求隧道在连续 minStableMs 内一直存在，避免建立后立刻被系统回收却被判定为成功。 */
    private suspend fun awaitStableRunning(minStableMs: Long, timeoutMs: Long): Boolean {
        val started = SystemClock.elapsedRealtime()
        var upSince = 0L
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            val now = SystemClock.elapsedRealtime()
            if (isRunning()) {
                if (upSince == 0L) upSince = now
                if (now - upSince >= minStableMs) return true
            } else {
                upSince = 0L
            }
            delay(100L)
        }
        return false
    }

    /**
     * 建立隧道并做稳定性校验。ColorOS 上偶发“Device started 后 0.1s 被系统断开”，
     * 因此不能只看 setState 是否返回成功，必须确认隧道连续存活，失败时清场重试一次。
     */
    private suspend fun startTunnel(config: Config) {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            val ok = runCatching {
                backend.setState(tunnel, Tunnel.State.UP, config)
                awaitStableRunning(1200L, 2800L)
            }.getOrElse { t ->
                lastError = t
                false
            }
            if (ok) return
            if (lastError == null) lastError = IllegalStateException("VPN 隧道未能保持运行，请重试")
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            awaitRunning(false, 1200L)
            if (attempt == 0) delay(500L)
        }
        throw lastError ?: IllegalStateException("VPN 隧道未能保持运行，请重试")
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        synchronized(operationGate) {
            if (probing) throw IllegalStateException("正在体检节点，请稍后再断开")
            if (_state.value is VpnState.Connecting) {
                throw IllegalStateException("正在连接，请稍后再断开")
            }
            updateState(VpnState.Disconnecting)
        }
        var stopped = true
        try {
            if (::backend.isInitialized) {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                stopped = awaitRunning(false, 1500L)
            }
        } catch (_: Throwable) {
            stopped = !isRunning()
        }
        if (stopped) {
            startedAt = 0L
            updateState(VpnState.Idle)
        } else {
            // 不能把“DOWN 超时但隧道仍在运行”报成未连接，否则界面关了、VPN 还在。
            updateState(VpnState.Connected)
            throw IllegalStateException("无法关闭 VPN 隧道，请重试")
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
            val config: Config = Config.parse(BufferedReader(StringReader(configText)))
            synchronized(operationGate) {
                if (probing) throw IllegalStateException("体检正在进行，请稍后再试")
                when (_state.value) {
                    is VpnState.Connected,
                    is VpnState.Connecting,
                    is VpnState.Disconnecting -> throw IllegalStateException("请先断开当前连接再体检")
                    else -> Unit
                }
                probing = true
            }
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
                val handshakeMs = awaitHandshake(timeoutMs)
                if (handshakeMs < 0L) {
                    return@withContext ProbeResult(healthy = false)
                }
                val speed = measureSpeed()
                // 只有握手成功还不够：必须真的通过隧道收到测速数据，否则会把
                // “能握手但无法上网”的节点误标为可用。
                ProbeResult(
                    healthy = speed.second > 0,
                    handshakeMs = handshakeMs,
                    httpLatencyMs = speed.first,
                    throughputKbps = speed.second
                )
            } finally {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                synchronized(operationGate) { probing = false }
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

    /** 通过隧道请求一个极小页面，确认握手后真的能收到应用层数据。 */
    private fun verifyTraffic(): Boolean = runCatching {
        val request = Request.Builder()
            .url(VERIFY_URL)
            .header("User-Agent", "SwiftWind/1.0")
            .build()
        verifyClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val body = response.body?.string() ?: return@use false
            body.isNotBlank()
        }
    }.getOrDefault(false)

    /**
     * 隧道内下载 256KB 样本，返回（首字节延迟 ms，吞吐 kbps）。
     * 吞吐从收到首个响应字节开始计时，避免把 TLS/TTFB 算进下载速度；
     * 超时导致的半截样本仍按已收字节计算，测速失败只扣吞吐分。
     *
     * 使用 OkHttp 异步回调，让体检取消时可以立即 call.cancel()，
     * 不再等一次慢速下载自然结束。
     */
    private suspend fun measureSpeed(): Pair<Long, Int> = suspendCancellableCoroutine { cont ->
        val started = SystemClock.elapsedRealtime()
        val request = Request.Builder()
            .url(SPEED_TEST_URL)
            .header("User-Agent", "SwiftWind/1.0")
            .build()
        val call = speedClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(0L to 0)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { resp ->
                        if (!resp.isSuccessful) return@use 0L to 0
                        val body = resp.body ?: return@use 0L to 0
                        val httpLatencyMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                        var firstByteAt = 0L
                        var totalBytes = 0L
                        val buffer = ByteArray(8192)
                        val input = body.byteStream()
                        try {
                            while (true) {
                                val read = try {
                                    input.read(buffer)
                                } catch (_: IOException) {
                                    break
                                }
                                if (read <= 0) break
                                if (firstByteAt == 0L) firstByteAt = SystemClock.elapsedRealtime()
                                totalBytes += read
                            }
                        } finally {
                            runCatching { input.close() }
                        }
                        val lastByteAt = SystemClock.elapsedRealtime()
                        val bodyMs = if (firstByteAt > 0L) {
                            (lastByteAt - firstByteAt).coerceAtLeast(1L)
                        } else {
                            1L
                        }
                        val kbps = (totalBytes * 8L / bodyMs)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()
                        httpLatencyMs to kbps
                    }
                }.getOrDefault(0L to 0)
                if (cont.isActive) cont.resume(result)
            }
        })
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
