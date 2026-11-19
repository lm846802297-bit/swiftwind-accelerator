package com.swiftwind.accelerator.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.swiftwind.accelerator.data.EgressRegion
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    private val geoClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val regionDetailCache = mutableMapOf<String, String>()

    // GoBackend 是进程级单例，内部只持有 applicationContext，不存在 Activity 泄漏。
    @SuppressLint("StaticFieldLeak")
    private lateinit var backend: GoBackend
    private lateinit var appContext: Context
    private var startedAt = 0L

    /** 串行化连接/断开/体检的入口，避免体检 finally 拆掉刚建立的连接。 */
    private val operationGate = Any()
    private var probing = false
    // 连接代际：连接协程被取消（比如 Activity 关闭导致 viewModelScope 取消）时，
    // 只允许清理属于本次连接的状态，避免误伤随后启动的新连接。
    private var connectionAttempt = 0L

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

    /**
     * App 进程重启后，检查系统里是否已有本 App 的隧道在运行。
     *
     * 只有 ViewModel 初始化时才允许把“Idle + 隧道仍在跑”接成 Connected，
     * 并且随后必须走 [verifyAdoptedConnection] 数据面复核。统计轮询和保活
     * 服务里的常规同步不允许做这种接管，避免体检清理残留或服务收尾期间
     * 把一个没有验证过的隧道误显示成已连接。
     */
    fun syncState(adoptRunningTunnel: Boolean = false) {
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
                    _state.value is VpnState.Idle && running && adoptRunningTunnel -> {
                        startedAt = SystemClock.elapsedRealtime()
                        updateState(VpnState.Connected)
                    }
                }
            }
        }
    }

    /** 当前已连接隧道的代际；连接成功时更新，用于丢弃过期的异步复核结果。 */
    fun connectionGeneration(): Long = startedAt

    /**
     * 读取当前隧道实际的 Cloudflare 出口国家/地区。
     * 对中国出口会进一步查询出口 IP 的省/市，其他地区只保留国家级。
     * 这不是固定归属，只用于节点行上的“最近检测出口”。
     */
    suspend fun currentEgressLocation(): EgressRegion.EgressLocation? = withContext(Dispatchers.IO) {
        if (!isRunning()) return@withContext null
        fetchEgressLocation()
    }

    /**
     * 连接中的周期性数据面探测。连续两次 HTTPS 请求都失败才返回 false；
     * 只读验证，不在这里拆隧道，由 ViewModel 结合用户操作和连接代际决定后续动作。
     */
    suspend fun verifyLiveConnection(): Boolean = withContext(Dispatchers.IO) {
        if (_state.value !is VpnState.Connected) return@withContext false
        // 隧道被系统回收后，普通 HTTPS 可能走底层直连而“成功”，
        // 不能把这种直连结果当成 VPN 数据面可用。
        if (!isRunning()) return@withContext false
        val generation = startedAt
        var dataOk = verifyTraffic()
        if (!dataOk) {
            delay(900L)
            if (_state.value is VpnState.Connected &&
                startedAt == generation &&
                isRunning()
            ) {
                dataOk = verifyTraffic()
            }
        }
        if (!isRunning()) dataOk = false
        dataOk
    }

    /**
     * 心跳确认节点失效后主动拆掉隧道并进入 Error。
     * generation 用于避免用户在复核期间已经切到新连接时，误拆新隧道。
     */
    suspend fun failCurrentConnection(message: String, generation: Long? = null): Boolean =
        withContext(Dispatchers.IO) {
            var shouldFail = false
            synchronized(operationGate) {
                if (_state.value is VpnState.Connected &&
                    (generation == null || startedAt == generation)
                ) {
                    runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                    shouldFail = true
                }
            }
            if (!shouldFail) return@withContext false

            // 不在 operationGate 的临界区里挂起；拆隧道后短暂等待系统释放。
            runCatching { awaitRunning(false, 900L) }
            synchronized(operationGate) {
                if (_state.value is VpnState.Connected &&
                    (generation == null || startedAt == generation)
                ) {
                    startedAt = 0L
                    updateState(VpnState.Error(message))
                }
            }
            true
        }

    /** App 进程仍存活时，若系统把前台保活服务回收了，补拉起一次。 */
    fun ensureKeepAlive() {
        if (!::appContext.isInitialized) return
        if (_state.value !is VpnState.Connected) return
        if (VpnKeepAliveService.isRunning()) return
        runCatching { VpnKeepAliveService.start(appContext) }
    }

    /**
     * App 进程重启后 syncState 只能看到“系统里还有本应用的隧道”，
     * 无法证明这条隧道仍能传输数据。这里做一次带重试的数据面复核；
     * 若隧道已失效则主动拆掉并回到 Error，避免界面长期显示假连接。
     *
     * 复核前后用 startedAt 作为连接代际标记：用户在复核期间断开/切节点时，
     * 不会把新连接误判成失效隧道。
     */
    suspend fun verifyAdoptedConnection(): Boolean = withContext(Dispatchers.IO) {
        if (_state.value !is VpnState.Connected) return@withContext true
        val generation = startedAt
        var dataOk = false
        repeat(2) {
            if (!dataOk) {
                dataOk = isRunning() && verifyTraffic()
                if (!dataOk) delay(600L)
            }
        }
        if (!isRunning()) dataOk = false
        var tearDown = false
        synchronized(operationGate) {
            // 复核期间用户已经断开或切换到了另一条隧道，当前状态不再归本次复核处理。
            if (_state.value !is VpnState.Connected || startedAt != generation) {
                return@withContext true
            }
            if (!dataOk) {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                tearDown = true
            }
        }
        if (tearDown) {
            // awaitRunning 不能在 operationGate 的临界区内挂起；
            // 拆隧道后再短暂等待系统回收，然后重新核对连接代际，避免误伤新连接。
            runCatching { awaitRunning(false, 900L) }
            synchronized(operationGate) {
                if (_state.value is VpnState.Connected && startedAt == generation) {
                    startedAt = 0L
                    updateState(VpnState.Error("现有隧道无法传输数据，请重新连接"))
                }
            }
        }
        dataOk
    }

    suspend fun connect(
        configText: String,
        verifyData: Boolean = true,
        handshakeTimeoutMs: Long = 2000L,
        stabilityMs: Long = 1200L
    ) = withContext(Dispatchers.IO) {
        // 先把配置解析完再进入 Connecting：配置无效时保持原状态，
        // 不能因为旧隧道还在运行就把一个根本没用上的节点显示成“已连接”。
        val config: Config = Config.parse(BufferedReader(StringReader(configText)))
        val attemptId = synchronized(operationGate) {
            if (probing) throw IllegalStateException("正在体检节点，请稍后再连接")
            when (_state.value) {
                is VpnState.Connecting,
                is VpnState.Disconnecting -> throw IllegalStateException("正在切换连接，请稍后再试")
                else -> Unit
            }
            connectionAttempt += 1L
            updateState(VpnState.Connecting)
            connectionAttempt
        }
        try {
            val connectStartedAt = SystemClock.elapsedRealtime()
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
            startTunnel(config, stabilityMs)
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
            if (t is CancellationException) {
                // Activity 关闭会取消 viewModelScope，连接协程可能停在 Connecting。
                // 不清理的话下次打开会卡在“正在连接”，连接/断开按钮都被拒绝。
                withContext(NonCancellable) {
                    synchronized(operationGate) {
                        if (connectionAttempt == attemptId &&
                            _state.value is VpnState.Connecting
                        ) {
                            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                            startedAt = 0L
                            updateState(VpnState.Idle)
                        }
                    }
                }
                throw t
            }
            if (_state.value is VpnState.Error) throw t
            // GoBackend 在切换配置失败时会尝试恢复旧隧道。这里不能只看“有隧道在跑”
            // 就直接报已连接：新建的隧道也可能残留在运行但根本不通。
            // 只有数据面验证通过，才接受这个恢复后的连接。
            val stillRunning = runCatching {
                ::backend.isInitialized && backend.getRunningTunnelNames().contains(TUNNEL_NAME)
            }.getOrDefault(false)
            if (stillRunning) {
                val dataOk = verifyTraffic()
                Log.d("SwiftWindSwitch", "connect catch stillRunning dataOk=$dataOk")
                if (dataOk) {
                    if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
                    updateState(VpnState.Connected)
                } else {
                    runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                    runCatching { awaitRunning(false, 800L) }
                    updateState(VpnState.Error(mapError(t)))
                }
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
    private suspend fun startTunnel(config: Config, stabilityMs: Long) {
        // 每次建立隧道前把 GoBackend 的静态 future 重新指向自定义服务；
        // 等待超时后仍走原有服务流程，不阻塞用户连接。
        HuayiVpnServiceBridge.prepare(appContext, 1200L)
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            val ok = runCatching {
                backend.setState(tunnel, Tunnel.State.UP, config)
                awaitStableRunning(stabilityMs, 2800L)
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
            val probeStartedAt = SystemClock.elapsedRealtime()
            Log.d("SwiftWindProbe", "probe start")
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
                val handshakeMs = awaitHandshake(timeoutMs)
                if (handshakeMs < 0L) {
                    return@withContext ProbeResult(healthy = false)
                }
                val speed = measureSpeed()
                // 测速接口可能被运营商/网络单独拦截，但隧道本身仍能上网。
                // 测速为 0 时补一次极小请求，避免把可用节点误标成“不可用”。
                val dataOk = speed.second > 0 || verifyTraffic()
                // 只有握手成功还不够：必须真的通过隧道收到应用层数据，否则会把
                // “能握手但无法上网”的节点误标为可用。
                // 数据面可用时再读一次 trace，记录本次实际出口国家/地区。
                val egressLocation = if (dataOk) fetchEgressLocation() else null
                ProbeResult(
                    healthy = dataOk,
                    handshakeMs = handshakeMs,
                    httpLatencyMs = speed.first,
                    throughputKbps = speed.second,
                    egressCountry = egressLocation?.countryCode,
                    egressDetail = egressLocation?.detail
                )
            } finally {
                val cleanupStartedAt = SystemClock.elapsedRealtime()
                // 取消体检时也要等隧道真正停止，再把 probing 放开；
                // 否则紧接着点“连接”可能和还在回收的旧隧道抢状态。
                withContext(NonCancellable) {
                    var stopped = false
                    for (attempt in 0..1) {
                        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                        val waitMs = if (attempt == 0) 700L else 900L
                        stopped = runCatching { awaitRunning(false, waitMs) }.getOrDefault(false)
                        if (stopped) break
                    }
                    synchronized(operationGate) {
                        probing = false
                        // DOWN 调用返回不等于系统已经释放 tun；仍在运行说明清理失败。
                        // 这种残留隧道不能静默留在后台，否则用户看到“未连接”但流量仍在被劫持。
                        if (!stopped && isRunning()) {
                            updateState(VpnState.Error("体检结束后 VPN 隧道未能释放，请重新连接"))
                        }
                    }
                }
                Log.d(
                    "SwiftWindProbe",
                    "probe cleanup done +${SystemClock.elapsedRealtime() - cleanupStartedAt}ms " +
                        "(total ${SystemClock.elapsedRealtime() - probeStartedAt}ms)"
                )
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

    /** 通过隧道请求 Cloudflare trace，并按需补充中国的省/市。 */
    private suspend fun fetchEgressLocation(): EgressRegion.EgressLocation? = withContext(Dispatchers.IO) {
        if (!isRunning()) return@withContext null
        val request = Request.Builder()
            .url(VERIFY_URL)
            .header("User-Agent", "SwiftWind/1.0")
            .build()
        val trace = runCatching {
            awaitBody(verifyClient, request)
        }.onFailure {
            Log.d("SwiftWindRegion", "trace request failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
        if (!isRunning()) return@withContext null
        Log.d(
            "SwiftWindRegion",
            "trace body=${trace?.take(60)?.replace('\n', '|')} running=${isRunning()}"
        )
        // Cloudflare 的 colo 是实际处理请求的边缘机房，比 anycast IP 的
        // IP 库归属更可靠。能映射时直接采用，避免把 LAX 误显示成上海。
        val edge = runCatching {
            EgressRegion.edgeLocation(trace)
        }.onFailure {
            Log.d("SwiftWindRegion", "edge parse failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
        Log.d("SwiftWindRegion", "edge=${edge?.countryCode}/${edge?.detail}")
        edge?.let { return@withContext it }
        val country = EgressRegion.parseTraceLoc(trace) ?: return@withContext null
        val detail = if (country == "CN") {
            val ip = EgressRegion.parseTraceIp(trace)
            if (ip != null) fetchChinaRegionDetail(ip) else null
        } else {
            null
        }
        if (!isRunning()) return@withContext null
        EgressRegion.EgressLocation(countryCode = country, detail = detail)
    }

    /**
     * 可取消的 OkHttp 请求。体检取消时 call.cancel() 会立即打断阻塞读取，
     * 不再等慢速 trace/geo 请求自然超时。
     */
    private suspend fun awaitBody(client: OkHttpClient, request: Request): String? =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = runCatching {
                        response.use { if (it.isSuccessful) it.body?.string() else null }
                    }.getOrNull()
                    if (cont.isActive) cont.resume(body)
                }
            })
        }

    /**
     * ipapi.co 的免费 HTTPS 接口。只在中国出口时调用，失败就退回国家级显示。
     * 结果按出口 IP 缓存在进程内，避免体检时重复查询同一出口。
     */
    private suspend fun fetchChinaRegionDetail(ip: String): String? {
        synchronized(regionDetailCache) {
            regionDetailCache[ip]?.let { return it }
        }
        val request = Request.Builder()
            .url("https://ipapi.co/$ip/json/")
            .header("User-Agent", "SwiftWind/1.0")
            .build()
        val body = runCatching { awaitBody(geoClient, request) }.getOrNull() ?: return null
        val detail = EgressRegion.parseGeoDetail(body) ?: return null
        synchronized(regionDetailCache) { regionDetailCache[ip] = detail }
        return detail
    }

    /** 通过隧道请求一个极小页面，确认握手后真的能收到应用层数据。 */
    private suspend fun verifyTraffic(): Boolean = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(VERIFY_URL)
            .header("User-Agent", "SwiftWind/1.0")
            .build()
        val call = verifyClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = runCatching {
                    response.use { resp ->
                        if (!resp.isSuccessful) return@use false
                        val body = resp.body?.string() ?: return@use false
                        body.isNotBlank()
                    }
                }.getOrDefault(false)
                if (cont.isActive) cont.resume(ok)
            }
        })
    }

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
