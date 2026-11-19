package com.swiftwind.accelerator.viewmodel

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swiftwind.accelerator.data.EgressRegion
import com.swiftwind.accelerator.data.Node
import com.swiftwind.accelerator.data.NodeSource
import com.swiftwind.accelerator.data.NodeStore
import com.swiftwind.accelerator.net.ConfigImporter
import com.swiftwind.accelerator.net.SubscriptionFetcher
import com.swiftwind.accelerator.net.WarpClient
import com.swiftwind.accelerator.vpn.TunnelController
import com.swiftwind.accelerator.vpn.TunnelStats
import com.swiftwind.accelerator.vpn.VpnState
import com.swiftwind.accelerator.vpn.NodeHealth
import com.swiftwind.accelerator.vpn.effectiveHealth
import com.swiftwind.accelerator.vpn.NodeQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_FAILOVER_CANDIDATES = 2
private const val HEARTBEAT_TICK_MS = 5_000L
private const val HEARTBEAT_INTERVAL_MS = 30_000L
private const val HEARTBEAT_SETTLE_MS = 6_000L

data class ProbeProgress(
    val done: Int = 0,
    val total: Int = 0,
    val currentName: String = "",
    val available: Int = 0,
    val cancelling: Boolean = false
)

data class UiState(
    val nodes: List<Node> = emptyList(),
    val selectedId: String? = null,
    val vpnState: VpnState = VpnState.Idle,
    val stats: TunnelStats = TunnelStats(),
    val busy: Boolean = false,
    val health: Map<String, NodeHealth> = emptyMap(),
    val healthCheckedAt: Map<String, Long> = emptyMap(),
    val quality: Map<String, NodeQuality> = emptyMap(),
    // 底层 Wi-Fi / 蜂窝网络是否仍有 Internet。VPN 隧道本身可以保持 UP，
    // 但没有底层网络时不能继续宣称“已连接 / 流量已受保护”。
    val networkAvailable: Boolean = true,
    // 网络恢复后正在重建隧道。此时底层网络已回来，但 WireGuard 握手
    // 还没完成，不能提前显示“已连接”。
    val reconnecting: Boolean = false,
    val probeProgress: ProbeProgress? = null
) {
    val selectedNode: Node?
        get() = nodes.firstOrNull { it.id == selectedId }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val warpClient = WarpClient()
    private val subscriptionFetcher = SubscriptionFetcher()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var statsJob: Job? = null
    private var heartbeatJob: Job? = null
    private var regionJob: Job? = null
    private var probeJob: Job? = null
    private var probeCancelRequestedAt = 0L
    private var lastHeartbeatAt = 0L
    private var lastHeartbeatGeneration = 0L
    private var heartbeatFailoverInProgress = false
    // connect() 在真正进入 Connecting 状态前有一小段异步窗口；
    // 用 Job 做同步闸门，避免快速双击时两条连接协程互相覆盖状态。
    private var connectJob: Job? = null
    private var disconnectJob: Job? = null

    private val connectivityManager = application.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val underlyingNetworks = LinkedHashSet<Network>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkMonitorReady = false
    // 只有真正经历过底层网络丢失后，才允许后续新增网络触发自动重连。
    // 否则 App 启动后系统补发初始网络回调时，会在已连接状态下误触发一次重连。
    private var networkReconnectArmed = false
    private var networkReconnectJob: Job? = null
    // 网络事件时间和用户最近一次主动连接操作时间，用于避免
    // 用户在切节点完成后又被一次旧的网络回调触发冗余重连。
    private var networkChangeEventAt = 0L
    private var lastUserConnectionActionAt = 0L

    init {
        val nodes = NodeStore.loadNodes()
        val savedSelected = NodeStore.selectedId
        val selected = nodes.firstOrNull { it.id == savedSelected }?.id ?: nodes.firstOrNull()?.id
        if (selected != null && savedSelected != selected) NodeStore.selectedId = selected

        _ui.value = UiState(
            nodes = nodes,
            selectedId = selected,
            vpnState = TunnelController.state.value,
            networkAvailable = hasUnderlyingInternet()
        )

        viewModelScope.launch {
            TunnelController.state.collect { state ->
                _ui.update { it.copy(vpnState = state) }
                when (state) {
                    is VpnState.Connected -> {
                        // 连接成功后的健康刷新和出口地区由实际发起连接的一方写入。
                        // 自动换线时 Connected 可能早于 selectedId 落库，不能在这里
                        // 用当时的选中节点做地区检测，否则会把新出口写到旧节点上。
                        // 这里也不能取消 regionJob：显式地区检测可能已经在本轮回调前后启动。
                        startStatsPolling()
                    }
                    is VpnState.Connecting -> {
                        // 切换节点时旧隧道的流量/时长不属于新连接，先清空，
                        // 避免界面在“正在连接”阶段继续显示上一节点的统计。
                        regionJob?.cancel()
                        _ui.update { it.copy(stats = TunnelStats()) }
                    }
                    is VpnState.Disconnecting -> Unit
                    else -> {
                        stopStatsPolling()
                        _ui.update { it.copy(stats = TunnelStats()) }
                    }
                }
            }
        }

        TunnelController.syncState(adoptRunningTunnel = true)
        if (TunnelController.state.value is VpnState.Connected) {
            _ui.value.selectedId?.let {
                markHealthy(it)
                detectCurrentRegion(it)
            }
            startStatsPolling()
            // syncState 只能确认系统里还留着本应用的隧道，不能证明数据面仍然可用。
            // 进程重启后补一次后台复核，避免残留隧道被长期显示成“已连接”。
            viewModelScope.launch {
                delay(1500L)
                Log.d("SwiftWindHealth", "verify adopted connection begin")
                val ok = try {
                    TunnelController.verifyAdoptedConnection()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w("SwiftWindHealth", "adopted tunnel verify failed", t)
                    true
                }
                Log.d("SwiftWindHealth", "verify adopted connection ok=$ok")
                if (!ok) {
                    _ui.value.selectedId?.let { markUnhealthy(it) }
                    notify("现有 VPN 隧道无法传输数据，请重新连接")
                }
            }
        }
        startNetworkMonitoring()
        startConnectionHeartbeat()
    }

    fun fetchWarpNode() {
        if (_ui.value.busy) return
        when (_ui.value.vpnState) {
            is VpnState.Connected,
            is VpnState.Connecting,
            is VpnState.Disconnecting -> {
                notify("请先断开当前连接再更新 WARP 节点")
                return
            }
            else -> Unit
        }
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { warpClient.registerNodes() }
                if (fresh.isEmpty()) {
                    notify("没有获取到可用的 WARP 线路，请稍后重试")
                    return@launch
                }
                val current = _ui.value.nodes
                val previous = current.firstOrNull { it.id == _ui.value.selectedId }
                val manual = current.filter { it.source != NodeSource.WARP }
                val merged = manual + fresh
                NodeStore.saveNodes(merged)
                val selected = when {
                    previous != null && previous.source != NodeSource.WARP &&
                        merged.any { it.id == previous.id } -> previous.id
                    previous != null -> fresh.firstOrNull {
                        it.endpoint == previous.endpoint
                    }?.id ?: fresh.first().id
                    else -> fresh.first().id
                }
                NodeStore.selectedId = selected
                _ui.update {
                    it.copy(
                        nodes = merged,
                        selectedId = selected,
                        health = emptyMap(),
                        healthCheckedAt = emptyMap(),
                        quality = emptyMap()
                    )
                }
                notify("已更新 ${fresh.size} 条 Cloudflare WARP 免费线路，建议点「一键体检」按质量排序")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                notify("获取免费节点失败：${TunnelController.mapError(t)}")
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun probeNodes() {
        if (_ui.value.busy || probeJob?.isActive == true) return
        val nodes = _ui.value.nodes
        if (nodes.isEmpty()) {
            notify("请先添加节点")
            return
        }
        when (TunnelController.state.value) {
            is VpnState.Connected,
            is VpnState.Connecting,
            is VpnState.Disconnecting -> {
                notify("请先断开当前连接再体检")
                return
            }
            else -> Unit
        }

        val total = nodes.size
        val previousHealth = _ui.value.health
        val previousHealthCheckedAt = _ui.value.healthCheckedAt
        val previousQuality = _ui.value.quality
        _ui.update {
            it.copy(
                busy = true,
                probeProgress = ProbeProgress(total = total),
                health = nodes.associate { node -> node.id to NodeHealth.CHECKING },
                healthCheckedAt = emptyMap(),
                quality = emptyMap()
            )
        }
        probeCancelRequestedAt = 0L
        probeJob = viewModelScope.launch {
            var available = 0
            var firstError: String? = null
            try {
                for ((index, node) in nodes.withIndex()) {
                    ensureActive()
                    _ui.update { state ->
                        val progress = state.probeProgress ?: ProbeProgress(total = total)
                        state.copy(
                            probeProgress = progress.copy(
                                done = index + 1,
                                currentName = node.name,
                                available = available
                            )
                        )
                    }
                    val result = try {
                        TunnelController.probe(node.config, timeoutMs = 3000L)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        if (firstError == null) firstError = TunnelController.mapError(t)
                        null
                    }
                    val success = result?.healthy == true
                    if (success) available++
                    if (result != null) {
                        Log.d(
                            "SwiftWindQuality",
                            "${node.name} ${node.endpoint} healthy=${result.healthy} " +
                                "score=${result.score} handshake=${result.handshakeMs}ms " +
                                "http=${result.httpLatencyMs}ms throughput=${result.throughputKbps}kbps"
                        )
                    }
                    if (result?.egressCountry != null) {
                        updateNodeRegion(node.id, result.egressCountry, result.egressDetail)
                    }
                    _ui.update { state ->
                        val progress = state.probeProgress ?: ProbeProgress(total = total)
                        state.copy(
                            health = state.health + (node.id to if (success) NodeHealth.OK else NodeHealth.FAIL),
                            healthCheckedAt = state.healthCheckedAt + (node.id to System.currentTimeMillis()),
                            quality = if (result != null) {
                                state.quality + (node.id to result.toQuality())
                            } else {
                                state.quality
                            },
                            probeProgress = progress.copy(
                                done = index + 1,
                                currentName = node.name,
                                available = available
                            )
                        )
                    }
                    delay(300)
                }
                ensureActive()
                val finalHealth = _ui.value.health
                val finalQuality = _ui.value.quality
                val currentNodes = _ui.value.nodes
                val best = currentNodes
                    .filter { finalHealth[it.id] == NodeHealth.OK }
                    .maxByOrNull { finalQuality[it.id]?.score ?: 0 }
                val currentSelected = _ui.value.selectedId
                if (best != null && best.id != currentSelected) {
                    NodeStore.selectedId = best.id
                    _ui.update { it.copy(selectedId = best.id) }
                }
                if (best != null) {
                    Log.d(
                        "SwiftWindQuality",
                        "best=${best.name} ${best.endpoint} score=${finalQuality[best.id]?.score}"
                    )
                }
                if (available == 0 && firstError != null) {
                    notify("体检失败：$firstError")
                } else if (best != null) {
                    val quality = finalQuality[best.id]
                    val detail = quality?.summary?.let { "（$it）" } ?: ""
                    notify("体检完成：$available/${nodes.size} 条线路可用，已选择最优 ${best.name}$detail")
                } else {
                    notify("体检完成：$available/${nodes.size} 条线路可用")
                }
            } catch (t: CancellationException) {
                // 取消体检不应把之前已经测出的结果一起丢掉；用户只是中止了本次刷新。
                _ui.update {
                    it.copy(
                        health = previousHealth,
                        healthCheckedAt = previousHealthCheckedAt,
                        quality = previousQuality
                    )
                }
                notify(
                    if (previousHealthCheckedAt.isNotEmpty()) {
                        "已取消体检，保留上次结果"
                    } else {
                        "已取消体检"
                    }
                )
                throw t
            } catch (t: Throwable) {
                notify("体检异常：${TunnelController.mapError(t)}")
            } finally {
                val cancelLatency = if (probeCancelRequestedAt > 0L) {
                    SystemClock.elapsedRealtime() - probeCancelRequestedAt
                } else {
                    -1L
                }
                _ui.update { state ->
                    val clearedHealth = state.health.mapValues { (_, value) ->
                        if (value == NodeHealth.CHECKING) NodeHealth.UNKNOWN else value
                    }
                    state.copy(busy = false, probeProgress = null, health = clearedHealth)
                }
                if (cancelLatency >= 0L) {
                    Log.d("SwiftWindProbe", "probe ui cleared +${cancelLatency}ms")
                }
                probeCancelRequestedAt = 0L
                probeJob = null
            }
        }
    }

    fun cancelProbe() {
        val job = probeJob ?: return
        if (job.isCompleted) return
        probeCancelRequestedAt = SystemClock.elapsedRealtime()
        Log.d("SwiftWindProbe", "cancel requested")
        _ui.update { state ->
            state.copy(probeProgress = state.probeProgress?.copy(cancelling = true))
        }
        job.cancel()
    }

    fun importText(raw: String) {
        if (_ui.value.busy) {
            notify("正在体检或刷新节点，请稍后再导入")
            return
        }
        if (raw.isBlank()) {
            notify("请先粘贴 WireGuard 配置")
            return
        }
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val texts = ConfigImporter.split(raw)
                if (texts.isEmpty()) {
                    notify("没有识别到有效的 WireGuard 配置")
                    return@launch
                }
                var invalid = 0
                val nodes = texts.mapIndexedNotNull { index, text ->
                    val parsed = runCatching { ConfigImporter.parse(text) }.getOrNull()
                    if (parsed == null) {
                        invalid++
                        null
                    } else {
                        Node(
                            name = "导入节点 ${_ui.value.nodes.size + index + 1}",
                            config = text,
                            endpoint = ConfigImporter.endpointOf(text),
                            source = NodeSource.IMPORT
                        )
                    }
                }
                if (nodes.isEmpty()) {
                    notify("没有识别到有效的 WireGuard 配置")
                    return@launch
                }
                val added = addNodes(nodes, selectFirst = _ui.value.selectedId == null)
                val suffix = if (invalid > 0) "，${invalid} 个配置无法解析" else ""
                notify("已导入 ${added} 个节点$suffix")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                notify("导入失败：${t.message ?: "配置内容无效"}")
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun addSubscription(url: String) {
        if (_ui.value.busy) {
            notify("正在体检或刷新节点，请稍后再添加订阅")
            return
        }
        if (url.isBlank()) {
            notify("请先填写订阅链接")
            return
        }
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) { subscriptionFetcher.fetch(url) }
                val texts = ConfigImporter.split(content)
                if (texts.isEmpty()) {
                    notify("订阅里没有可用的 WireGuard 配置")
                    return@launch
                }
                val nodes = texts.mapIndexedNotNull { index, text ->
                    if (runCatching { ConfigImporter.parse(text) }.isFailure) return@mapIndexedNotNull null
                    Node(
                        name = "订阅节点 ${_ui.value.nodes.size + index + 1}",
                        config = text,
                        endpoint = ConfigImporter.endpointOf(text),
                        source = NodeSource.SUBSCRIPTION
                    )
                }
                if (nodes.isEmpty()) {
                    notify("订阅里没有可用的 WireGuard 配置")
                    return@launch
                }
                val added = addNodes(nodes, selectFirst = _ui.value.selectedId == null)
                notify("订阅已更新，新增 ${added} 个节点")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                notify("订阅拉取失败：${TunnelController.mapError(t)}")
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun selectNode(id: String) {
        if (_ui.value.busy) {
            notify("正在体检或刷新节点，请稍后再切换")
            return
        }
        val node = _ui.value.nodes.firstOrNull { it.id == id } ?: return
        if (_ui.value.selectedId == id) return
        // 网络恢复任务可能已经进入 TunnelController.connect；此时取消 Job 会
        // 让底层连接中途被打断，状态停在 Connecting。等它结束再切换。
        if (networkReconnectJob?.isActive == true) {
            notify("正在恢复网络连接，请稍后再切换节点")
            return
        }
        // 先检查隧道状态再取消网络恢复任务：如果恢复已经进入 Connecting，
        // 直接取消 Job 可能把状态留在 Connecting，后续点击都会被拒。
        val currentState = TunnelController.state.value
        if (currentState is VpnState.Connecting || currentState is VpnState.Disconnecting) {
            notify("正在切换连接，请稍后再切换节点")
            return
        }
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        lastUserConnectionActionAt = SystemClock.elapsedRealtime()
        when (currentState) {
            is VpnState.Connected -> {
                // 已连接时先真正切换隧道，成功后再落选中项，避免 UI 与隧道不一致。
                _ui.update { it.copy(busy = true) }
                viewModelScope.launch {
                    val previous = _ui.value.nodes.firstOrNull { it.id == _ui.value.selectedId }
                    val switchStartedAt = SystemClock.elapsedRealtime()
                    Log.d("SwiftWindSwitch", "switch start target=${node.name} from=${previous?.name}")
                    try {
                        // 已知不可用的线路用更短的握手窗口，失败时更快回到原节点；
                        // 未测/可用线路保留 1.1 秒，避免误杀刚恢复的节点。
                        val targetHealth = effectiveHealthFor(node.id)
                        val targetHandshakeMs =
                            if (targetHealth == NodeHealth.FAIL) 800L else 1100L
                        TunnelController.connect(
                            node.config,
                            handshakeTimeoutMs = targetHandshakeMs,
                            stabilityMs = 600L
                        )
                        NodeStore.selectedId = id
                        _ui.update { it.copy(selectedId = id) }
                        markHealthy(id)
                        detectCurrentRegion(id)
                        Log.d(
                            "SwiftWindSwitch",
                            "switch success +${SystemClock.elapsedRealtime() - switchStartedAt}ms"
                        )
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        markUnhealthy(id)
                        val originalError = TunnelController.mapError(t)
                        Log.d(
                            "SwiftWindSwitch",
                            "switch target failed +${SystemClock.elapsedRealtime() - switchStartedAt}ms: $originalError"
                        )
                        // 切换会先拆掉旧隧道；新节点握手失败时尽量把原节点接回来，
                        // 避免用户点错一个死节点后直接断网。原节点也失效时，
                        // 再按健康度尝试少量备用线路，而不是把用户留在断网状态。
                        if (previous != null &&
                            previous.id != id &&
                            TunnelController.state.value is VpnState.Error
                        ) {
                            val restoredNode = restoreAfterSwitchFailure(previous, id)
                            if (restoredNode != null) {
                                NodeStore.selectedId = restoredNode.id
                                _ui.update { it.copy(selectedId = restoredNode.id) }
                                markHealthy(restoredNode.id)
                                detectCurrentRegion(restoredNode.id)
                                Log.d(
                                    "SwiftWindSwitch",
                                    "switch rollback +${SystemClock.elapsedRealtime() - switchStartedAt}ms -> ${restoredNode.name}"
                                )
                                if (restoredNode.id == previous.id) {
                                    notify("切换失败，已恢复“${previous.name}”")
                                } else {
                                    notify("切换失败，已切换到“${restoredNode.name}”")
                                }
                            } else {
                                markUnhealthy(previous.id)
                                notify("$originalError；备用线路也不可用，请重新连接")
                            }
                        } else {
                            notify(originalError)
                        }
                    } finally {
                        _ui.update { it.copy(busy = false) }
                    }
                }
            }
            else -> {
                NodeStore.selectedId = id
                _ui.update { it.copy(selectedId = id) }
            }
        }
    }

    fun deleteNode(id: String) {
        if (_ui.value.busy) {
            notify("正在体检或刷新节点，请稍后再删除")
            return
        }
        if (_ui.value.nodes.none { it.id == id }) return
        if (networkReconnectJob?.isActive == true) {
            notify("正在恢复网络连接，请稍后再删除节点")
            return
        }
        when (TunnelController.state.value) {
            is VpnState.Connecting, is VpnState.Disconnecting -> {
                notify("正在切换连接，请稍后再删除节点")
                return
            }
            else -> Unit
        }
        _ui.update { it.copy(busy = true) }
        val wasSelected = _ui.value.selectedId == id
        if (wasSelected) lastUserConnectionActionAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                if (wasSelected &&
                    (TunnelController.state.value is VpnState.Connected ||
                        TunnelController.state.value is VpnState.Error)
                ) {
                    try {
                        TunnelController.disconnect()
                    } catch (t: Throwable) {
                        notify(TunnelController.mapError(t))
                        return@launch
                    }
                }
                val remaining = _ui.value.nodes.filterNot { it.id == id }
                NodeStore.saveNodes(remaining)
                val newSelected = if (wasSelected) remaining.firstOrNull()?.id else _ui.value.selectedId
                NodeStore.selectedId = newSelected
                _ui.update {
                    it.copy(
                        nodes = remaining,
                        selectedId = newSelected,
                        health = it.health - id,
                        healthCheckedAt = it.healthCheckedAt - id,
                        quality = it.quality - id
                    )
                }
                notify("节点已删除")
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun connect() {
        val node = _ui.value.selectedNode
        if (node == null) {
            notify("请先添加一个节点")
            return
        }
        if (_ui.value.busy) {
            notify("正在体检或刷新节点，请稍后再连接")
            return
        }
        when (TunnelController.state.value) {
            is VpnState.Connecting -> return
            is VpnState.Disconnecting -> {
                notify("正在断开，请稍后再连接")
                return
            }
            else -> Unit
        }
        if (connectJob?.isActive == true) return
        if (networkReconnectJob?.isActive == true) {
            notify("正在恢复网络连接，请稍后再试")
            return
        }
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        networkReconnectArmed = false
        lastUserConnectionActionAt = SystemClock.elapsedRealtime()
        // 在启动协程前同步占位，避免“点连接后立刻切换节点/删除节点”抢在
        // TunnelController 进入 Connecting 之前改掉选中项或节点列表。
        _ui.update { it.copy(busy = true) }
        val job = viewModelScope.launch {
            try {
                val result = connectWithFailover(node, allowWarpFailover = true)
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                        ?: IllegalStateException("连接失败，请重试")
                    notify(TunnelController.mapError(error))
                }
            } finally {
                if (connectJob === coroutineContext[Job]) connectJob = null
                _ui.update { it.copy(busy = false) }
            }
        }
        connectJob = job
    }

    /**
     * 连接失败时按健康度/质量分尝试少量 WARP 备用线路。
     * 只对 WARP 免费节点自动换线；手动导入或订阅节点保持用户选择，避免偷偷改线路。
     */
    private suspend fun connectWithFailover(start: Node, allowWarpFailover: Boolean): Result<Node> {
        var current = start
        val attempted = mutableSetOf<String>()
        while (true) {
            attempted += current.id
            try {
                TunnelController.connect(current.config)
                if (current.id != start.id) {
                    NodeStore.selectedId = current.id
                    _ui.update { it.copy(selectedId = current.id) }
                    notify("已自动切换到“${current.name}”")
                }
                markHealthy(current.id)
                detectCurrentRegion(current.id)
                return Result.success(current)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                markUnhealthy(current.id)
                if (!allowWarpFailover || start.source != NodeSource.WARP) {
                    return Result.failure(t)
                }
                val next = failoverCandidates(start, attempted).firstOrNull()
                    ?: return Result.failure(t)
                notify("“${current.name}”未响应，尝试“${next.name}”…")
                current = next
            }
        }
    }

    private fun failoverCandidates(start: Node, attempted: Set<String>): List<Node> {
        if (start.source != NodeSource.WARP) return emptyList()
        val quality = _ui.value.quality
        val now = System.currentTimeMillis()
        return _ui.value.nodes
            .filter { it.source == NodeSource.WARP && it.id !in attempted }
            .sortedWith(
                compareByDescending<Node> {
                    when (effectiveHealthFor(it.id, now)) {
                        NodeHealth.OK -> 3
                        NodeHealth.UNKNOWN -> 2
                        NodeHealth.CHECKING -> 1
                        NodeHealth.FAIL -> 0
                    }
                }.thenByDescending { quality[it.id]?.score ?: 0 }
            )
            .take(MAX_FAILOVER_CANDIDATES)
    }

    /**
     * 切换失败后的恢复顺序：原节点优先，其次按健康度/质量分尝试少量 WARP 备用线。
     * 恢复阶段仍要做数据面验证：只握手成功但传不了数据时，不能把界面伪装成“已连接”。
     * 1.0 秒握手超时 + 0.6 秒稳定窗口 + 一次小请求，健康节点通常能在一秒多恢复。
     */
    private suspend fun restoreAfterSwitchFailure(previous: Node, failedTargetId: String): Node? {
        val attempted = mutableSetOf(previous.id, failedTargetId)
        val candidates = buildList {
            add(previous)
            addAll(failoverCandidates(previous, attempted))
        }.distinctBy { it.id }.take(MAX_FAILOVER_CANDIDATES)
        for (candidate in candidates) {
            try {
                TunnelController.connect(
                    candidate.config,
                    verifyData = true,
                    handshakeTimeoutMs = 1000L,
                    stabilityMs = 600L
                )
                return candidate
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                markUnhealthy(candidate.id)
                Log.d("SwiftWindSwitch", "switch rollback candidate failed: ${candidate.name}")
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun hasUnderlyingInternet(): Boolean {
        val manager = connectivityManager ?: return false
        return runCatching {
            manager.allNetworks.any { network ->
                val caps = manager.getNetworkCapabilities(network)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrDefault(false)
    }

    private fun startNetworkMonitoring() {
        val manager = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            // ConnectivityManager 的回调线程不是主线程；统一投回 viewModelScope（Main），
            // 避免 networkReconnectArmed / networkReconnectJob / 时间戳跨线程读写。
            override fun onAvailable(network: Network) {
                viewModelScope.launch {
                    // onAvailable 可能早于 onCapabilitiesChanged。此时无法判断
                    // 这个 Network 是不是 VPN，也不能假设它有 Internet；先不登记，
                    // 等带能力信息的回调到齐后再决定，避免把 VPN 自身当成底层网络。
                    val caps = manager.getNetworkCapabilities(network) ?: return@launch
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ) {
                        return@launch
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@launch
                    val added = synchronized(underlyingNetworks) { underlyingNetworks.add(network) }
                    if (added) {
                        networkChangeEventAt = SystemClock.elapsedRealtime()
                        _ui.update { it.copy(networkAvailable = true) }
                    }
                    if (added && networkMonitorReady && networkReconnectArmed) {
                        scheduleNetworkReconnect()
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                viewModelScope.launch {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@launch
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ) {
                        // 网络没有真正 Internet 能力时，不能继续把它留在“可用底层网络”集合里，
                        // 否则后续 isNotEmpty() 判断会把一次断网误当成仍可重连。
                        val removed = synchronized(underlyingNetworks) { underlyingNetworks.remove(network) }
                        if (removed) {
                            networkChangeEventAt = SystemClock.elapsedRealtime()
                            networkReconnectArmed = true
                            val hasNetwork = synchronized(underlyingNetworks) {
                                underlyingNetworks.isNotEmpty()
                            }
                            _ui.update { it.copy(networkAvailable = hasNetwork) }
                            if (hasNetwork && networkMonitorReady) scheduleNetworkReconnect()
                        }
                        return@launch
                    }
                    val added = synchronized(underlyingNetworks) { underlyingNetworks.add(network) }
                    if (added) {
                        networkChangeEventAt = SystemClock.elapsedRealtime()
                        _ui.update { it.copy(networkAvailable = true) }
                    }
                    if (added && networkMonitorReady && networkReconnectArmed) {
                        scheduleNetworkReconnect()
                    }
                }
            }

            override fun onLost(network: Network) {
                viewModelScope.launch {
                    val removed = synchronized(underlyingNetworks) { underlyingNetworks.remove(network) }
                    if (!removed) return@launch
                    networkChangeEventAt = SystemClock.elapsedRealtime()
                    // 记录“网络确实断过一次”，之后新网络出现时才执行重连。
                    networkReconnectArmed = true
                    val hasNetwork = synchronized(underlyingNetworks) { underlyingNetworks.isNotEmpty() }
                    _ui.update { it.copy(networkAvailable = hasNetwork) }
                    if (hasNetwork && networkMonitorReady) scheduleNetworkReconnect()
                }
            }
        }
        runCatching {
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
        }
        viewModelScope.launch {
            delay(2000L)
            networkMonitorReady = true
            // 极少数设备在启动后 2 秒内就完成一次网络切换；补一次补发检查。
            if (networkReconnectArmed &&
                TunnelController.state.value is VpnState.Connected &&
                synchronized(underlyingNetworks) { underlyingNetworks.isNotEmpty() }
            ) {
                scheduleNetworkReconnect()
            }
        }
    }

    private fun scheduleNetworkReconnect() {
        if (!networkMonitorReady) return
        if (TunnelController.state.value !is VpnState.Connected) return
        if (synchronized(underlyingNetworks) { underlyingNetworks.isEmpty() }) return
        val node = _ui.value.selectedNode ?: return
        if (networkReconnectJob?.isActive == true) return
        // 体检、手动连接/断开或心跳自动换线正在进行时，不要再启动网络恢复任务，
        // 否则两个协程会同时进入 TunnelController 的 Connecting 状态，互相覆盖。
        if (_ui.value.busy || _ui.value.reconnecting || heartbeatFailoverInProgress) return
        // 在启动协程前先同步占位，避免 onCapabilitiesChanged / onLost
        // 连续回调同时通过 isActive 检查，启动两条互相覆盖的重连协程。
        // 网络丢失事件只消费一次；重连失败后由用户手动处理，不再持续自旋。
        networkReconnectArmed = false
        networkReconnectJob = viewModelScope.launch reconnect@{
            val eventAt = networkChangeEventAt
            _ui.update { it.copy(reconnecting = true) }
            try {
                // 网络切换后系统 VPN 的底层 socket 需要一点时间稳定，避免刚切就握手。
                delay(1200L)
                if (lastUserConnectionActionAt > eventAt) return@reconnect
                if (TunnelController.state.value !is VpnState.Connected) return@reconnect
                notify("网络已切换，正在重连…")
                val result = connectWithFailover(node, allowWarpFailover = true)
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                        ?: IllegalStateException("网络切换后重连失败")
                    notify("网络切换后重连失败：${TunnelController.mapError(error)}")
                } else {
                    notify("网络切换后已恢复连接")
                }
            } finally {
                _ui.update { it.copy(reconnecting = false) }
                networkReconnectJob = null
            }
        }
    }

    override fun onCleared() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        regionJob?.cancel()
        regionJob = null
        networkReconnectJob?.cancel()
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        super.onCleared()
    }

    fun disconnect() {
        if (_ui.value.busy || disconnectJob?.isActive == true) return
        if (networkReconnectJob?.isActive == true) {
            notify("正在恢复网络连接，请稍后再断开")
            return
        }
        when (TunnelController.state.value) {
            is VpnState.Connecting, is VpnState.Disconnecting -> return
            else -> Unit
        }
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        networkReconnectArmed = false
        lastUserConnectionActionAt = SystemClock.elapsedRealtime()
        // 先同步占用 busy，避免“点断开后立刻再点连接 / 切节点”在
        // TunnelController 真正进入 Disconnecting 之前抢状态。
        _ui.update { it.copy(busy = true) }
        disconnectJob = viewModelScope.launch {
            try {
                runCatching { TunnelController.disconnect() }
                    .onFailure { notify(TunnelController.mapError(it)) }
            } finally {
                if (disconnectJob === coroutineContext[Job]) disconnectJob = null
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun addNodes(nodes: List<Node>, selectFirst: Boolean): Int {
        if (nodes.isEmpty()) return 0
        val current = _ui.value.nodes
        val existing = current.map { ConfigImporter.fingerprint(it.config) }.toSet()
        val fresh = nodes.filter { ConfigImporter.fingerprint(it.config) !in existing }
            .distinctBy { ConfigImporter.fingerprint(it.config) }
        if (fresh.isEmpty()) return 0

        val merged = current + fresh
        NodeStore.saveNodes(merged)
        var selected = _ui.value.selectedId
        if (selectFirst && fresh.isNotEmpty()) {
            selected = fresh.first().id
            NodeStore.selectedId = selected
        }
        _ui.update { it.copy(nodes = merged, selectedId = selected) }
        return fresh.size
    }

    private fun startStatsPolling() {
        if (statsJob?.isActive == true) return
        statsJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                TunnelController.syncState()
                // 系统回收前台服务时，TunnelController 的状态可能仍是 Connected；
                // 每 15 秒补一次心跳检查，服务不在就重新拉起。
                if (tick > 0 && tick % 15 == 0) TunnelController.ensureKeepAlive()
                _ui.update { it.copy(stats = TunnelController.snapshot()) }
                tick++
                delay(1000)
            }
        }
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
    }

    /**
     * 连接后的数据面心跳。WireGuard 握手成功、隧道保持 UP 都不代表还能上网，
     * 因此每隔 30 秒通过隧道做一次极小 HTTPS 请求；连续失败才判定节点失效，
     * 避免一次瞬时抖动就切走线路。
     *
     * 心跳循环常驻，不随单次连接启停：连接代际、底层网络和 busy 状态都在
     * 每轮重新判断，避免“心跳协程正在自动换线时被状态收集器取消”。
     */
    private fun startConnectionHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(HEARTBEAT_TICK_MS)
                val state = TunnelController.state.value
                if (state !is VpnState.Connected) {
                    lastHeartbeatAt = 0L
                    lastHeartbeatGeneration = 0L
                    continue
                }
                if (_ui.value.busy ||
                    _ui.value.reconnecting ||
                    networkReconnectJob?.isActive == true ||
                    !_ui.value.networkAvailable
                ) {
                    continue
                }
                val generation = TunnelController.connectionGeneration()
                val now = SystemClock.elapsedRealtime()
                if (generation == lastHeartbeatGeneration &&
                    now - lastHeartbeatAt < HEARTBEAT_INTERVAL_MS
                ) {
                    continue
                }
                // 连接刚建立时先让系统 VPN / 数据面稳定，避免把启动阶段的
                // 几百毫秒波动误判成节点失效。
                if (now - generation < HEARTBEAT_SETTLE_MS) continue

                lastHeartbeatGeneration = generation
                lastHeartbeatAt = now
                val dataOk = try {
                    TunnelController.verifyLiveConnection()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w("SwiftWindHealth", "live heartbeat verify failed", t)
                    false
                }
                if (TunnelController.state.value !is VpnState.Connected ||
                    TunnelController.connectionGeneration() != generation
                ) {
                    // 复核期间用户断开或切到了别的线路，本次结果作废。
                    continue
                }
                if (!_ui.value.networkAvailable) continue
                if (dataOk) {
                    _ui.value.selectedId?.let { markHealthy(it) }
                    Log.d("SwiftWindHealth", "live heartbeat ok generation=$generation")
                } else {
                    Log.d("SwiftWindHealth", "live heartbeat failed generation=$generation")
                    handleHeartbeatFailure(generation)
                }
            }
        }
    }

    private suspend fun handleHeartbeatFailure(generation: Long) {
        if (heartbeatFailoverInProgress) return
        if (_ui.value.busy ||
            _ui.value.reconnecting ||
            networkReconnectJob?.isActive == true ||
            !_ui.value.networkAvailable
        ) {
            return
        }
        if (TunnelController.state.value !is VpnState.Connected ||
            TunnelController.connectionGeneration() != generation
        ) {
            return
        }
        val current = _ui.value.selectedNode ?: return
        heartbeatFailoverInProgress = true
        _ui.update { it.copy(busy = true) }
        try {
            markUnhealthy(current.id)
            Log.d(
                "SwiftWindHealth",
                "heartbeat node failed: ${current.name} ${current.endpoint}"
            )
            // 手动导入 / 订阅节点是用户明确选择的线路，不自动替换；
            // 只把真实状态报出来，避免偷偷改走别的节点。
            if (current.source != NodeSource.WARP) {
                TunnelController.failCurrentConnection(
                    "当前节点无法传输数据，请切换节点或重新连接",
                    generation
                )
                notify("当前节点无法传输数据，请切换节点或重新连接")
                return
            }

            val candidates = failoverCandidates(current, setOf(current.id))
            if (candidates.isEmpty()) {
                TunnelController.failCurrentConnection(
                    "当前节点无法传输数据，请切换节点或重新连接",
                    generation
                )
                notify("当前节点无法传输数据，请切换节点或重新连接")
                return
            }

            notify("当前节点心跳失败，正在尝试备用线路…")
            for (candidate in candidates) {
                try {
                    TunnelController.connect(
                        candidate.config,
                        handshakeTimeoutMs = 1000L,
                        stabilityMs = 600L
                    )
                    NodeStore.selectedId = candidate.id
                    _ui.update { it.copy(selectedId = candidate.id) }
                    markHealthy(candidate.id)
                    detectCurrentRegion(candidate.id)
                    Log.d(
                        "SwiftWindHealth",
                        "heartbeat failover success -> ${candidate.name}"
                    )
                    notify("节点心跳失败，已自动切换到“${candidate.name}”")
                    return
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    markUnhealthy(candidate.id)
                    Log.d(
                        "SwiftWindHealth",
                        "heartbeat failover candidate failed: ${candidate.name}"
                    )
                }
            }

            // 所有备用线路都失败后，若仍残留 Connected 状态，主动拆掉，
            // 不能让界面继续显示一个已经被证实无法传输数据的连接。
            TunnelController.failCurrentConnection(
                "当前节点无法传输数据，备用线路也不可用，请重新连接",
                generation
            )
            notify("当前节点无法传输数据，备用线路也不可用，请重新连接")
        } finally {
            heartbeatFailoverInProgress = false
            _ui.update { it.copy(busy = false) }
        }
    }

    /**
     * 连接成功后顺带读取一次当前出口地区；它不挡连接流程，失败也不影响连接。
     * WARP 的 Anycast 出口可能变化，因此只写入“最近检测出口”，并在体检过期后隐藏。
     */
    private fun detectCurrentRegion(nodeIdArg: String? = _ui.value.selectedId) {
        val nodeId = nodeIdArg ?: return
        val generation = TunnelController.connectionGeneration()
        regionJob?.cancel()
        regionJob = viewModelScope.launch(Dispatchers.IO) {
            val location = runCatching {
                TunnelController.currentEgressLocation()
            }.getOrNull() ?: return@launch
            if (TunnelController.state.value !is VpnState.Connected ||
                TunnelController.connectionGeneration() != generation
            ) {
                return@launch
            }
            updateNodeRegion(nodeId, location.countryCode, location.detail)
        }
    }

    private fun updateNodeRegion(nodeId: String, rawCountry: String?, rawDetail: String? = null) {
        val country = EgressRegion.normalizeCode(rawCountry) ?: return
        val detail = rawDetail?.trim()?.takeIf { it.isNotBlank() }
        var changed = false
        val updated = _ui.value.nodes.map { node ->
            val nextDetail = detail ?: node.regionDetail?.takeIf { node.regionCode == country }
            if (node.id == nodeId && (node.regionCode != country || node.regionDetail != nextDetail)) {
                changed = true
                node.copy(regionCode = country, regionDetail = nextDetail)
            } else {
                node
            }
        }
        if (!changed) {
            // 同一出口再次检测时不会写库，但仍留一条校验日志，便于真机排查
            // “地区没有更新”到底是未执行检测，还是出口确实没变。
            Log.d("SwiftWindRegion", "node=$nodeId egress=$country${detail?.let { " / $it" } ?: ""} verified unchanged")
            return
        }
        NodeStore.saveNodes(updated)
        _ui.update { it.copy(nodes = updated) }
        Log.d("SwiftWindRegion", "node=$nodeId egress=$country${detail?.let { " / $it" } ?: ""}")
    }

    private fun effectiveHealthFor(id: String, now: Long = System.currentTimeMillis()): NodeHealth {
        val state = _ui.value
        return effectiveHealth(state.health[id], state.healthCheckedAt[id], now)
    }

    private fun markUnhealthy(id: String) {
        _ui.update { state ->
            state.copy(
                health = state.health + (id to NodeHealth.FAIL),
                healthCheckedAt = state.healthCheckedAt + (id to System.currentTimeMillis())
            )
        }
    }

    private fun markHealthy(id: String) {
        _ui.update { state ->
            state.copy(
                health = state.health + (id to NodeHealth.OK),
                healthCheckedAt = state.healthCheckedAt + (id to System.currentTimeMillis())
            )
        }
    }

    private fun notify(message: String) {
        _messages.tryEmit(message)
    }
}
