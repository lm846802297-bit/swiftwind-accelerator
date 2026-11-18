package com.swiftwind.accelerator.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val quality: Map<String, NodeQuality> = emptyMap(),
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
    private var probeJob: Job? = null

    init {
        val nodes = NodeStore.loadNodes()
        val savedSelected = NodeStore.selectedId
        val selected = nodes.firstOrNull { it.id == savedSelected }?.id ?: nodes.firstOrNull()?.id
        if (selected != null && savedSelected != selected) NodeStore.selectedId = selected

        _ui.value = UiState(
            nodes = nodes,
            selectedId = selected,
            vpnState = TunnelController.state.value
        )

        viewModelScope.launch {
            TunnelController.state.collect { state ->
                _ui.update { it.copy(vpnState = state) }
                when (state) {
                    is VpnState.Connected -> startStatsPolling()
                    is VpnState.Connecting, is VpnState.Disconnecting -> Unit
                    else -> {
                        stopStatsPolling()
                        _ui.update { it.copy(stats = TunnelStats()) }
                    }
                }
            }
        }

        TunnelController.syncState()
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
                        quality = emptyMap()
                    )
                }
                notify("已更新 ${fresh.size} 条 Cloudflare WARP 免费线路，建议点「一键体检」按质量排序")
            } catch (t: Throwable) {
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
        _ui.update {
            it.copy(
                busy = true,
                probeProgress = ProbeProgress(total = total),
                health = nodes.associate { node -> node.id to NodeHealth.CHECKING },
                quality = emptyMap()
            )
        }
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
                    _ui.update { state ->
                        val progress = state.probeProgress ?: ProbeProgress(total = total)
                        state.copy(
                            health = state.health + (node.id to if (success) NodeHealth.OK else NodeHealth.FAIL),
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
                notify("已取消体检")
                throw t
            } catch (t: Throwable) {
                notify("体检异常：${TunnelController.mapError(t)}")
            } finally {
                _ui.update { state ->
                    val clearedHealth = state.health.mapValues { (_, value) ->
                        if (value == NodeHealth.CHECKING) NodeHealth.UNKNOWN else value
                    }
                    state.copy(busy = false, probeProgress = null, health = clearedHealth)
                }
                probeJob = null
            }
        }
    }

    fun cancelProbe() {
        val job = probeJob ?: return
        if (job.isCompleted) return
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
                val added = addNodes(nodes, selectFirst = _ui.value.selectedId == null)
                val suffix = if (invalid > 0) "，${invalid} 个配置无法解析" else ""
                notify("已导入 ${added} 个节点$suffix")
            } catch (t: Throwable) {
                notify("导入失败：${t.message ?: "配置内容无效"}")
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun addSubscription(url: String) {
        if (_ui.value.busy) return
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
                val added = addNodes(nodes, selectFirst = _ui.value.selectedId == null)
                notify("订阅已更新，新增 ${added} 个节点")
            } catch (t: Throwable) {
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
        when (TunnelController.state.value) {
            is VpnState.Connecting, is VpnState.Disconnecting -> {
                notify("正在切换连接，请稍后再切换节点")
                return
            }
            is VpnState.Connected -> {
                // 已连接时先真正切换隧道，成功后再落选中项，避免 UI 与隧道不一致。
                _ui.update { it.copy(busy = true) }
                viewModelScope.launch {
                    try {
                        TunnelController.connect(node.config)
                        NodeStore.selectedId = id
                        _ui.update { it.copy(selectedId = id) }
                    } catch (t: Throwable) {
                        notify(TunnelController.mapError(t))
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
        when (TunnelController.state.value) {
            is VpnState.Connecting, is VpnState.Disconnecting -> {
                notify("正在切换连接，请稍后再删除节点")
                return
            }
            else -> Unit
        }
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val wasSelected = _ui.value.selectedId == id
                if (wasSelected && TunnelController.state.value is VpnState.Connected) {
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
                _ui.update { it.copy(nodes = remaining, selectedId = newSelected) }
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
        viewModelScope.launch {
            runCatching { TunnelController.connect(node.config) }
                .onFailure { notify(TunnelController.mapError(it)) }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { TunnelController.disconnect() }
                .onFailure { notify(TunnelController.mapError(it)) }
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
            while (isActive) {
                TunnelController.syncState()
                _ui.update { it.copy(stats = TunnelController.snapshot()) }
                delay(1000)
            }
        }
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun notify(message: String) {
        _messages.tryEmit(message)
    }
}
