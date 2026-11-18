package com.swiftwind.accelerator.viewmodel

import android.app.Application
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

data class UiState(
    val nodes: List<Node> = emptyList(),
    val selectedId: String? = null,
    val vpnState: VpnState = VpnState.Idle,
    val stats: TunnelStats = TunnelStats(),
    val busy: Boolean = false,
    val health: Map<String, NodeHealth> = emptyMap()
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
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                val nodes = withContext(Dispatchers.IO) { warpClient.registerNodes() }
                val added = addNodes(nodes, selectFirst = true)
                val suffix = if (added < nodes.size) "（新增 $added 条，其余已存在）" else ""
                notify("已获取 ${nodes.size} 条 Cloudflare WARP 免费线路$suffix，建议点「一键体检」确认可用")
            } catch (t: Throwable) {
                notify("获取免费节点失败：${TunnelController.mapError(t)}")
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun probeNodes() {
        if (_ui.value.busy) return
        val nodes = _ui.value.nodes
        if (nodes.isEmpty()) {
            notify("请先添加节点")
            return
        }
        when (_ui.value.vpnState) {
            is VpnState.Connected,
            is VpnState.Connecting,
            is VpnState.Disconnecting -> {
                notify("请先断开当前连接再体检")
                return
            }
            else -> Unit
        }

        viewModelScope.launch {
            _ui.update {
                it.copy(
                    busy = true,
                    health = it.nodes.associate { node -> node.id to NodeHealth.CHECKING }
                )
            }
            var available = 0
            var firstError: String? = null
            for (node in nodes) {
                val success = try {
                    TunnelController.probe(node.config)
                } catch (t: Throwable) {
                    if (firstError == null) firstError = TunnelController.mapError(t)
                    false
                }
                if (success) available++
                _ui.update {
                    it.copy(
                        health = it.health + (node.id to if (success) NodeHealth.OK else NodeHealth.FAIL)
                    )
                }
                delay(300)
            }
            val finalHealth = _ui.value.health
            val currentSelected = _ui.value.selectedId
            if (currentSelected == null || finalHealth[currentSelected] == NodeHealth.FAIL) {
                val firstHealthy = nodes.firstOrNull { finalHealth[it.id] == NodeHealth.OK }?.id
                if (firstHealthy != null && firstHealthy != currentSelected) {
                    NodeStore.selectedId = firstHealthy
                    _ui.update { it.copy(selectedId = firstHealthy) }
                }
            }
            _ui.update { it.copy(busy = false) }
            if (available == 0 && firstError != null) {
                notify("体检失败：$firstError")
            } else {
                notify("体检完成：$available/${nodes.size} 条线路握手成功")
            }
        }
    }

    fun importText(raw: String) {
        viewModelScope.launch {
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
        }
    }

    fun addSubscription(url: String) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
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
        if (_ui.value.selectedId == id) return
        NodeStore.selectedId = id
        _ui.update { it.copy(selectedId = id) }
        if (_ui.value.vpnState is VpnState.Connected) {
            val node = _ui.value.nodes.firstOrNull { it.id == id } ?: return
            viewModelScope.launch {
                runCatching { TunnelController.connect(node.config) }
                    .onFailure { notify(TunnelController.mapError(it)) }
            }
        }
    }

    fun deleteNode(id: String) {
        viewModelScope.launch {
            val wasSelected = _ui.value.selectedId == id
            if (wasSelected && _ui.value.vpnState is VpnState.Connected) {
                TunnelController.disconnect()
            }
            val remaining = _ui.value.nodes.filterNot { it.id == id }
            NodeStore.saveNodes(remaining)
            val newSelected = if (wasSelected) remaining.firstOrNull()?.id else _ui.value.selectedId
            NodeStore.selectedId = newSelected
            _ui.update { it.copy(nodes = remaining, selectedId = newSelected) }
            notify("节点已删除")
        }
    }

    fun connect() {
        val node = _ui.value.selectedNode
        if (node == null) {
            notify("请先添加一个节点")
            return
        }
        if (_ui.value.vpnState is VpnState.Connecting) return
        viewModelScope.launch {
            runCatching { TunnelController.connect(node.config) }
                .onFailure { notify(TunnelController.mapError(it)) }
        }
    }

    fun disconnect() {
        viewModelScope.launch { TunnelController.disconnect() }
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
