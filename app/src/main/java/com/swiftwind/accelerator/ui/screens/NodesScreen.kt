package com.swiftwind.accelerator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftwind.accelerator.data.Node
import com.swiftwind.accelerator.data.NodeSource
import com.swiftwind.accelerator.vpn.NodeHealth
import com.swiftwind.accelerator.vpn.NODE_HEALTH_TTL_MS
import com.swiftwind.accelerator.vpn.effectiveHealth
import com.swiftwind.accelerator.ui.theme.Danger
import com.swiftwind.accelerator.ui.theme.Mint
import com.swiftwind.accelerator.ui.theme.Pink
import com.swiftwind.accelerator.ui.theme.Rose
import com.swiftwind.accelerator.ui.theme.Sky
import com.swiftwind.accelerator.ui.theme.Stroke
import com.swiftwind.accelerator.ui.theme.Surface as SurfaceColor
import com.swiftwind.accelerator.ui.theme.SurfaceHi
import com.swiftwind.accelerator.ui.theme.TextPrimary
import com.swiftwind.accelerator.ui.theme.TextSecondary
import com.swiftwind.accelerator.viewmodel.ProbeProgress
import com.swiftwind.accelerator.viewmodel.UiState
import com.swiftwind.accelerator.vpn.VpnState
import kotlinx.coroutines.delay

@Composable
fun NodesScreen(
    ui: UiState,
    onFetchWarp: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImportText: (String) -> Unit,
    onAddSubscription: (String) -> Unit,
    onProbe: () -> Unit,
    onCancelProbe: () -> Unit
) {
    var showImport by remember { mutableStateOf(false) }
    var showSubscription by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Node?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val probeProgress = ui.probeProgress
    // 只统计当前列表里的节点，删除节点后不要留下已不存在节点的过期标签。
    val lastChecked = ui.nodes.mapNotNull { ui.healthCheckedAt[it.id] }.maxOrNull()
    val staleCount = ui.nodes.count { node ->
        val checkedAt = ui.healthCheckedAt[node.id] ?: return@count false
        now - checkedAt > NODE_HEALTH_TTL_MS
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    val nodeActionsEnabled = !ui.busy &&
        ui.vpnState !is VpnState.Connecting &&
        ui.vpnState !is VpnState.Disconnecting
    val canFetchWarp = !ui.busy &&
        ui.vpnState !is VpnState.Connected &&
        ui.vpnState !is VpnState.Connecting &&
        ui.vpnState !is VpnState.Disconnecting
    val canProbe = !ui.busy && ui.nodes.isNotEmpty() &&
        ui.vpnState !is VpnState.Connected &&
        ui.vpnState !is VpnState.Connecting &&
        ui.vpnState !is VpnState.Disconnecting

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("节点", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Text(
            "选择线路，或一键获取免费节点",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(18.dp))
        WarpCard(busy = ui.busy, enabled = canFetchWarp, onFetch = onFetchWarp)

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的节点", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                "  ${ui.nodes.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onProbe,
                enabled = canProbe
            ) {
                if (ui.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Pink
                    )
                } else {
                    Icon(
                        Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = if (canProbe) Pink else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    when {
                        probeProgress != null -> "体检中"
                        ui.busy -> "处理中"
                        else -> "一键体检"
                    },
                    color = if (canProbe) Pink else TextSecondary,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }
        Text(
            text = when {
                probeProgress != null -> "体检进行中 · 结果完成后刷新"
                lastChecked == null -> "尚未体检"
                staleCount > 0 ->
                    "上次体检 · ${relativeTime(lastChecked, now)} · ${staleCount} 条标签已过期，建议复检"
                else -> "上次体检 · ${relativeTime(lastChecked, now)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary.copy(alpha = 0.78f),
            modifier = Modifier.padding(top = 2.dp)
        )
        if (probeProgress != null) {
            Spacer(Modifier.height(8.dp))
            ProbeProgressCard(progress = probeProgress, onCancel = onCancelProbe)
        }
        Spacer(Modifier.height(10.dp))

        if (ui.nodes.isEmpty()) {
            EmptyNodes(Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    contentPadding = PaddingValues(bottom = 52.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(ui.nodes, key = { it.id }) { node ->
                        val checkedAt = ui.healthCheckedAt[node.id]
                        val health = effectiveHealth(ui.health[node.id], checkedAt, now)
                        NodeRow(
                            node = node,
                            selected = node.id == ui.selectedId,
                            health = health,
                            checkedAt = checkedAt,
                            now = now,
                            enabled = nodeActionsEnabled,
                            onSelect = { onSelect(node.id) },
                            onDelete = { pendingDelete = node }
                        )
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                }
                // 列表底部渐隐，遮住滚动边界处偶尔露出的下一行顶部。
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(38.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xFF0B0710))
                            )
                        )
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B0710))
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { showImport = true },
                enabled = !ui.busy,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("导入配置", modifier = Modifier.padding(start = 7.dp))
            }
            OutlinedButton(
                onClick = { showSubscription = true },
                enabled = !ui.busy,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("添加订阅", modifier = Modifier.padding(start = 7.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    if (showImport) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入 WireGuard 配置") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 6,
                    maxLines = 10,
                    placeholder = { Text("[Interface]\nPrivateKey = ...\n[Peer]\n...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImportText(text)
                        showImport = false
                    },
                    enabled = text.isNotBlank() && !ui.busy
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text("取消") }
            }
        )
    }

    if (showSubscription) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSubscription = false },
            title = { Text("添加订阅") },
            text = {
                Column {
                    Text(
                        "支持返回 WireGuard 配置的 HTTPS 链接，可包含多个节点。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        placeholder = { Text("https://example.com/subscription") },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddSubscription(url)
                        showSubscription = false
                    },
                    enabled = url.isNotBlank() && !ui.busy
                ) { Text("拉取") }
            },
            dismissButton = {
                TextButton(onClick = { showSubscription = false }) { Text("取消") }
            }
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除节点") },
            text = { Text("确定删除「${node.name}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(node.id)
                        pendingDelete = null
                    },
                    enabled = nodeActionsEnabled
                ) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

private fun relativeTime(timestamp: Long, now: Long): String {
    val minutes = ((now - timestamp).coerceAtLeast(0L) / 60_000L)
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "${minutes} 分钟前"
        minutes < 24L * 60L -> "${minutes / 60L} 小时前"
        else -> "${minutes / (24L * 60L)} 天前"
    }
}

@Composable
private fun ProbeProgressCard(progress: ProbeProgress, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF241227), RoundedCornerShape(16.dp))
            .border(1.dp, Pink.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = Pink
        )
        Column(Modifier.weight(1f).padding(start = 11.dp)) {
            Text(
                if (progress.cancelling) "正在取消体检…" else "正在体检 ${progress.done}/${progress.total}",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                when {
                    progress.cancelling -> "正在停止当前测速…"
                    progress.currentName.isNotBlank() ->
                        "当前 ${progress.currentName} · 已可用 ${progress.available}"
                    else -> "准备中…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onCancel, enabled = !progress.cancelling) {
            Text(
                if (progress.cancelling) "取消中" else "取消",
                color = if (progress.cancelling) TextSecondary else Pink
            )
        }
    }
}

@Composable
private fun WarpCard(busy: Boolean, enabled: Boolean, onFetch: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2A1027), Color(0xFF1A0E20))
                ),
                RoundedCornerShape(24.dp)
            )
            .border(1.dp, Pink.copy(alpha = 0.22f), RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(Pink.copy(alpha = 0.12f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Pink, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    "Cloudflare WARP",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "官方免费 · 一键生成多出口线路",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Button(
                onClick = onFetch,
                enabled = enabled && !busy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Pink,
                    contentColor = Color(0xFF2A0B1B)
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF2A0B1B)
                    )
                } else {
                    Text("获取", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: Node,
    selected: Boolean,
    health: NodeHealth,
    checkedAt: Long?,
    now: Long,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val expired = checkedAt != null && now - checkedAt > NODE_HEALTH_TTL_MS
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) SurfaceHi.copy(alpha = 0.9f) else SurfaceColor.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) Pink.copy(alpha = 0.38f) else Stroke
        )
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .border(1.dp, if (selected) Pink else TextSecondary.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(Modifier.size(10.dp).background(Pink, CircleShape))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    node.endpoint.ifBlank { "WireGuard 节点" },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = TextSecondary.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (checkedAt != null) {
                    Text(
                        "上次体检 · ${relativeTime(checkedAt, now)}" +
                            if (expired) " · 结果已过期" else "",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = TextSecondary.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            HealthBadge(health, expired = expired)
            SourceChip(node.source)
            IconButton(
                onClick = onDelete,
                enabled = enabled,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "删除节点",
                    tint = TextSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun HealthBadge(health: NodeHealth, expired: Boolean) {
    if (expired) {
        Text(
            "待复检",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 10.sp),
            color = TextSecondary,
            modifier = Modifier
                .padding(end = 5.dp)
                .background(TextSecondary.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
        return
    }
    if (health == NodeHealth.UNKNOWN) return
    val (label, color) = when (health) {
        NodeHealth.CHECKING -> "检测中" to Sky
        NodeHealth.OK -> "可用" to Mint
        NodeHealth.FAIL -> "不可用" to Danger
        NodeHealth.UNKNOWN -> return
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 10.sp),
        color = color,
        modifier = Modifier
            .padding(end = 5.dp)
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun SourceChip(source: NodeSource) {
    val (label, color) = when (source) {
        NodeSource.WARP -> "免费" to Pink
        NodeSource.SUBSCRIPTION -> "订阅" to Sky
        NodeSource.IMPORT -> "导入" to TextSecondary
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 10.sp),
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun EmptyNodes(modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(Pink.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Public, contentDescription = null, tint = Pink.copy(alpha = 0.7f), modifier = Modifier.size(32.dp))
        }
        Text(
            "还没有节点",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            "点击上方「获取」生成多条 WARP 免费线路，或导入自己的配置",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
